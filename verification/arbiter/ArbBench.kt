import com.abdulrahman.hardsubocr.core.*

/** Simulated OCR: SubScan composites (5 frames spread over the piece, a line survives only if it is
 *  on >= 3 of them); classic 2.0 = its middle frame. Credits are not Arabic → "" (Cleaner). */
var NOISE = 0.0   // probability that a read is garbled (~70 % alike) ; same again for "read nothing"
fun noisy(t: String, s: Int, e: Int, salt: Int): String {
    if (NOISE <= 0 || t.isEmpty()) return t
    val r = java.util.Random((s * 7919L + e * 104729L + salt * 31L) xor 0x5DEECE66DL)
    val x = r.nextDouble()
    if (x < NOISE) return ""
    if (x < 2 * NOISE) { val c = t.toCharArray(); for (k in c.indices) if (r.nextDouble() < 0.3 && c[k] != ' ') c[k] = 'ث'; return String(c) }
    return t
}
fun ocrComposite(gts: List<Gt>, s: Int, e: Int, salt: Int = 1): String = noisy(ocrComposite0(gts, s, e), s, e, salt)
fun ocrMiddle(gts: List<Gt>, s: Int, e: Int): String = noisy(ocrMiddle0(gts, s, e), s, e, 3)
fun ocrComposite0(gts: List<Gt>, s: Int, e: Int): String {
    val n = 5
    val cnt = HashMap<String, Int>()
    for (k in 0 until n) {
        val f = s + ((e - s) * (2 * k + 1)) / (2 * n)
        gts.firstOrNull { f >= it.start && f < it.end }?.let { cnt[it.text] = (cnt[it.text] ?: 0) + 1 }
    }
    return cnt.entries.firstOrNull { it.value >= 3 }?.key ?: ""
}
/** SubScan's BEST frame = most text pixels: a subtitle on screen beats credits alone. */
fun ocrBest(gts: List<Gt>, s: Int, e: Int): String {
    val g = gts.maxByOrNull { ov(s, e, it.start, it.end) } ?: return ""
    return if (ov(s, e, g.start, g.end) >= 3) noisy(g.text, s, e, 5) else ""
}
fun ocrMiddle0(gts: List<Gt>, s: Int, e: Int): String {
    val f = (s + e) / 2
    return gts.firstOrNull { f >= it.start && f < it.end }?.text ?: ""
}

class Out(val s: Int, val e: Int, val text: String)

fun e2e(name: String, gts: List<Gt>, lines0: List<Out>, calls: Int) {
    val ms = FRAME_US / 1000
    val merged = Merge.mergeSplits(lines0.filter { it.text.isNotBlank() }.map { SubEntry(it.s * ms, it.e * ms - 1, it.text) })
    val lines = merged.map { Out((it.startMs / ms).toInt(), ((it.endMs + 1) / ms).toInt(), it.text) }
    var correct = 0; var found = 0; var dups = 0; var err = 0.0
    val miss = ArrayList<String>()
    for (g in gts) {
        val mine = lines.filter { it.text == g.text && ov(it.s, it.e, g.start, g.end) > 0 }
        if (mine.isNotEmpty()) found++ else miss.add("${g.start}-${g.end} ${g.text}")
        if (mine.size > 1) dups += mine.size - 1
        val best = mine.maxByOrNull { ov(it.s, it.e, g.start, g.end).toDouble() / (maxOf(it.e, g.end) - minOf(it.s, g.start)) }
        if (best != null) {
            val iou = ov(best.s, best.e, g.start, g.end).toDouble() / (maxOf(best.e, g.end) - minOf(best.s, g.start))
            if (iou >= 0.5) { correct++; err += (Math.abs(best.s - g.start) + Math.abs(best.e - g.end)) / 2.0 }
        }
    }
    val wrong = lines.count { l -> gts.none { it.text == l.text && ov(l.s, l.e, it.start, it.end) > 0 } }
    println(String.format("  %-26s lines found %5.1f%%  correct(IoU>=.5) %5.1f%%  missing %2d  wrong %2d  dup %2d  mean err %.2f f  OCR %d",
        name, 100.0 * found / gts.size, 100.0 * correct / gts.size, gts.size - found, wrong, dups, if (correct > 0) err / correct else 0.0, calls))
    if (System.getenv("V") != null) miss.forEach { println("      MISSING $it") }
}

fun main(args: Array<String>) {
    NOISE = (System.getenv("NOISE") ?: "0").toDouble(); Arbiter.TRIM = System.getenv("NOTRIM") == null
    if (args[0] == "lines") { dbgLines(args[1], args[2].toInt(), args[3].toInt()); return }
    if (args[0] == "dbg") { dbgMain(args[1], args.drop(2).map { it.toInt() }); return }
    for (name in args) {
        val prefix = "/home/claude/vt/$name"; val (j, gts, _) = loadTruth(prefix)
        FRAME_US = 1_000_000L / j["fps"].toString().toInt()
        val lang = if (name == "en") SubLang.ENGLISH else SubLang.ARABIC
        val c20 = runOld(prefix)
        val v21 = runNew(prefix, ScanParams.preset(lang).legacy21()).first
        val v22 = runNew(prefix, ScanParams.preset(lang)).first
        println("=== $name  (${gts.size} subtitles)")
        e2e("2.0 alone", gts, c20.map { Out(it.s, it.e, ocrMiddle(gts, it.s, it.e)) }, c20.size)
        e2e("2.1 alone", gts, v21.map { Out(it.s, it.e, ocrComposite(gts, it.s, it.e)) }, v21.size)
        e2e("2.2 alone", gts, v22.map { Out(it.s, it.e, ocrComposite(gts, it.s, it.e, 2)) }, v22.size)
        // 2.3: frame voting, image of the best-covering engine segment
        val lists = listOf(v22, v21, c20)
        val voted = Ensemble.fuseFrames(lists.map { l -> l.map { intArrayOf(it.s, it.e) } }, 4)
        e2e("2.3 voting (shipped)", gts, voted.map { f ->
            val p = lists[f[2]][f[3]]
            Out(f[0], f[1], if (f[2] == 2) ocrMiddle(gts, p.s, p.e) else ocrComposite(gts, p.s, p.e, if (f[2] == 0) 2 else 1))
        }, voted.size)
        // 2.4: OCR arbitration, 2.1 base
        fun piece(s: Seg, t: String?) = Arbiter.Piece(s.s * FRAME_US, s.e * FRAME_US, t)
        val base = v21.map { piece(it, ocrComposite(gts, it.s, it.e)) }
        val altsRaw = listOf(v22.map { piece(it, null) }, c20.map { piece(it, null) }, v21.map { piece(it, null) })
        val need = Arbiter.needed(base, altsRaw, FRAME_US, 4)
        val alts = listOf(
            v22.mapIndexed { i, s -> piece(s, if (i in need[0]) ocrComposite(gts, s.s, s.e, 2) else null) },
            c20.mapIndexed { i, s -> piece(s, if (i in need[1]) ocrMiddle(gts, s.s, s.e) else null) },
            v21.mapIndexed { i, s -> piece(s, if (i in need[2]) ocrBest(gts, s.s, s.e) else null) })
        val res = Arbiter.resolve(base, alts, FRAME_US, 4)
        e2e("2.4 arbiter (2.1 base)", gts, res.map { Out((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt(), it.text) },
            base.size + need.sumOf { it.size })
        if (System.getenv("V") != null) res.filter { it.note != "2.1" }.forEach { println("      ${it.startUs / FRAME_US}-${it.endUs / FRAME_US} ${it.note}: ${it.text}") }
    }
}
