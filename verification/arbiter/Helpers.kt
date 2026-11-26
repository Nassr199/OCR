import com.abdulrahman.hardsubocr.core.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class Gt(val start: Int, val end: Int, val text: String, val tag: String, val box: IntArray? = null)
class Seg(val s: Int, val e: Int, val box: IntArray? = null, val motion: Double = 0.0)

fun loadTruth(prefix: String): Triple<JsonObject, List<Gt>, IntArray> {
    val j = Json.parseToJsonElement(File("$prefix.json").readText()).jsonObject
    val subs = j["subs"]!!.jsonArray.map {
        val o = it.jsonObject
        Gt(o["start"]!!.jsonPrimitive.int, o["end"]!!.jsonPrimitive.int, o["text"]!!.jsonPrimitive.content, o["tag"]!!.jsonPrimitive.content,
            o["box"]?.jsonArray?.map { v -> v.jsonPrimitive.int }?.toIntArray())
    }
    return Triple(j, subs, intArrayOf(j["frames"]!!.jsonPrimitive.int, j["gw"]!!.jsonPrimitive.int, j["gh"]!!.jsonPrimitive.int))
}

fun frames(prefix: String, n: Int, gw: Int, gh: Int): Sequence<ByteArray> = sequence {
    RandomAccessFile("$prefix.grid", "r").use { f ->
        for (i in 0 until n) {
            val b = ByteArray(gw * gh); f.readFully(b); yield(b)
        }
    }
}

var FRAME_US = 40_000L

fun runNew(prefix: String, p: ScanParams): Pair<List<Seg>, Long> {
    val (_, _, d) = loadTruth(prefix)
    val tr = SubtitleTracker(p)
    val out = ArrayList<Seg>()
    val t0 = System.nanoTime()
    var f = 0
    for (b in frames(prefix, d[0], d[1], d[2])) {
        val g = LumaGrid(d[1], d[2], b)
        val r = tr.push(f * FRAME_US, g)
        r.closed.forEach { out.add(Seg((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt(), intArrayOf(it.x0, it.y0, it.x1, it.y1))) }
        f++
    }
    tr.finish(f * FRAME_US).closed.forEach { out.add(Seg((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt(), intArrayOf(it.x0, it.y0, it.x1, it.y1))) }
    return out.sortedBy { it.s } to (System.nanoTime() - t0) / d[0] / 1000
}

fun runOld(prefix: String): List<Seg> {
    val (_, _, d) = loadTruth(prefix)
    val sg = SubtitleSegmenter(SegmenterParams())
    val out = ArrayList<Seg>()
    var f = 0
    for (b in frames(prefix, d[0], d[1], d[2])) {
        val g = LumaGrid(d[1], d[2], b)
        val r = sg.push(f * FRAME_US, g)
        r.closed.forEach { out.add(Seg((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt())) }
        f++
    }
    sg.finish(f * FRAME_US).closed.forEach { out.add(Seg((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt())) }
    return out.sortedBy { it.s }
}

fun ov(a: Int, b: Int, c: Int, d: Int) = maxOf(0, minOf(b, d) - maxOf(a, c))
/** With ground-truth boxes: does the segment's box lie (mostly) on the subtitle's box? */
fun onBox(s: Seg, g: Gt): Boolean {
    val b = g.box ?: return true
    val sb = s.box ?: return true
    val ix = ov(sb[0], sb[2] + 1, b[0] - 3, b[2] + 4); val iy = ov(sb[1], sb[3] + 1, b[1] - 3, b[3] + 4)
    return ix * iy >= 0.5 * (sb[2] - sb[0] + 1) * (sb[3] - sb[1] + 1)
}

fun motionReport(gts: List<Gt>, segs: List<Seg>) {
    val sub = segs.filter { s -> gts.any { g -> onBox(s, g) && ov(s.s, s.e, g.start, g.end) > 0 } }.map { it.motion }.sorted()
    val other = segs.filter { s -> gts.none { g -> onBox(s, g) && ov(s.s, s.e, g.start, g.end) > 0 } }.map { it.motion }.sorted()
    fun q(l: List<Double>, f: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * f).toInt()]
    println(String.format("    motion: subtitles n=%d p50 %.3f p90 %.3f max %.3f | other n=%d p10 %.3f p50 %.3f", sub.size, q(sub, .5), q(sub, .9), q(sub, 1.0), other.size, q(other, .1), q(other, .5)))
}

fun score(name: String, gts: List<Gt>, segs: List<Seg>, verbose: Boolean = false) {
    var found = 0; var split = 0; var merged = 0; var fp = 0; var exact = 0
    var bErr = 0.0
    val byTag = LinkedHashMap<String, IntArray>() // found, total, split
    val problems = ArrayList<String>()
    for (g in gts) {
        val dur = g.end - g.start
        val mine = segs.filter { onBox(it, g) }
        val hits = mine.filter { ov(it.s, it.e, g.start, g.end) >= minOf(3, dur / 2 + 1) }
        val best = mine.maxByOrNull { ov(it.s, it.e, g.start, g.end) }
        val cov = if (best == null) 0.0 else ov(best.s, best.e, g.start, g.end).toDouble() / dur
        val isFound = cov >= 0.5
        val isSplit = hits.size >= 2
        if (isFound) {
            found++
            val e = (Math.abs(best!!.s - g.start) + Math.abs(best.e - g.end)) / 2.0
            bErr += e
            if (Math.abs(best.s - g.start) <= 2 && Math.abs(best.e - g.end) <= 2) exact++
        } else problems.add("MISSED  ${g.start}-${g.end} [${g.tag}] ${g.text}")
        if (isSplit) { split++; problems.add("SPLIT   ${g.start}-${g.end} [${g.tag}] ${g.text} -> " + hits.joinToString { "${it.s}-${it.e}" }) }
        for (t in (g.tag.split(' ').filter { it.isNotBlank() } + "all")) {
            val a = byTag.getOrPut(t) { IntArray(3) }
            a[1]++; if (isFound) a[0]++; if (isSplit) a[2]++
        }
    }
    for (s in segs) {
        val covered = gts.filter { onBox(s, it) && ov(s.s, s.e, it.start, it.end) >= 0.5 * (it.end - it.start) }
        if (covered.size >= 2) { merged += covered.size - 1; problems.add("MERGED  ${s.s}-${s.e}: " + covered.joinToString(" | ") { it.text }) }
        val best = gts.filter { onBox(s, it) }.maxOfOrNull { ov(s.s, s.e, it.start, it.end) } ?: 0
        if (best < 0.3 * (s.e - s.s)) { fp++; problems.add("OTHER   ${s.s}-${s.e} box ${s.box?.joinToString(",")}") }
    }
    println(String.format("%-34s found %3d/%d (%.1f%%)  split %2d  over-merged %2d  other %2d  boundary ±2f %3d  mean err %.2f f",
        name, found, gts.size, 100.0 * found / gts.size, split, merged, fp, exact, if (found > 0) bErr / found else 0.0))
    println("    " + byTag.entries.joinToString("  ") { "${it.key}:${it.value[0]}/${it.value[1]}" + (if (it.value[2] > 0) "(split ${it.value[2]})" else "") })
    if (verbose) problems.forEach { println("      $it") }
}

fun compositeTest(prefix: String, p: ScanParams) {
    val (j, _, _) = loadTruth(prefix)
    val n = j["composite"]!!.jsonObject["frames"]!!.jsonArray.size
    val imgs = (1..n).map { ImageIO.read(File("${prefix}_full_$it.png")) }
    val w = imgs[0].width; val h = imgs[0].height
    val arr = imgs.map { im -> IntArray(w * h).also { im.getRGB(0, 0, w, h, it, 0, w) } }
    val comp = FrameComposite.combine(arr, SubStyle.LIGHT)
    // grid mask of the composite → restrict clean image
    val step = 3; val gw = w / step; val gh = h / step
    val g = ByteArray(gw * gh)
    for (y in 0 until gh) for (x in 0 until gw) {
        val c = comp[(y * step) * w + x * step]
        val yy = 16 + (65.481 * ((c shr 16) and 255) + 128.553 * ((c shr 8) and 255) + 24.966 * (c and 255)) / 255.0
        g[y * gw + x] = Math.round(yy).toInt().coerceIn(0, 255).toByte()
    }
    val gm = ScanMask.compute(LumaGrid(gw, gh, g), p)
    val clean = FrameComposite.clean(comp, w, h, SubStyle.LIGHT, p, gm, step)
    fun save(px: IntArray, name: String) {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, w, h, px, 0, w); ImageIO.write(bi, "png", File(name))
    }
    save(arr[0], "${prefix}_single.png"); save(comp, "${prefix}_composite.png"); save(clean, "${prefix}_clean.png")
    // background energy: bright pixels far from text rows (top 40 % of the band has no subtitle)
    fun bright(px: IntArray): Int { var c = 0; for (y in 0 until h * 4 / 10) for (x in 0 until w) { val v = px[y * w + x]; if (((v shr 8) and 255) > 150) c++ }; return c }
    println("composite: bright background pixels single=${bright(arr[0])} composite=${bright(comp)}  (frames=$n)")
}

