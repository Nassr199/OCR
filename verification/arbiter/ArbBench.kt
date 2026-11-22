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
val PER = LinkedHashMap<String, LinkedHashMap<String, String>>()

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
    if (System.getenv("PERLINE") != null) for (g in gts) {
        val mine = lines.filter { it.text == g.text && ov(it.s, it.e, g.start, g.end) > 0 }
        val best = mine.maxByOrNull { ov(it.s, it.e, g.start, g.end).toDouble() / (maxOf(it.e, g.end) - minOf(it.s, g.start)) }
        PER.getOrPut(name) { LinkedHashMap() }["${g.start}-${g.end}"] = best?.let { "${it.s}-${it.e}" } ?: "none"
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
        val v21x = runNew(prefix, ScanParams.preset(lang).legacy21().copy(farCut = false, carryText = false)).first
        val v26 = runNew(prefix, ScanParams.preset(lang).legacy21().copy(carryText = false)).first
        val v21 = runNew(prefix, ScanParams.preset(lang).legacy21()).first
        val v22 = runNew(prefix, ScanParams.preset(lang)).first
        println("=== $name  (${gts.size} subtitles)")
        // images are now ONE best frame (no multi-frame composite): best = most text pixels
        e2e("2.0 alone", gts, c20.map { Out(it.s, it.e, ocrMiddle(gts, it.s, it.e)) }, c20.size)
        e2e("2.1 exact (2.4)", gts, v21x.map { Out(it.s, it.e, ocrBest(gts, it.s, it.e)) }, v21x.size)
        e2e("2.6 (cut, no carry)", gts, v26.map { Out(it.s, it.e, ocrBest(gts, it.s, it.e)) }, v26.size)
        e2e("2.6.1 (carried line)  ", gts, v21.map { Out(it.s, it.e, ocrBest(gts, it.s, it.e)) }, v21.size)
        val gaps = { l: List<Seg> -> gts.count { g -> val mine = l.filter { it.s < g.end && it.e > g.start }.sortedBy { it.s }
            mine.zipWithNext().any { (a, b) -> b.s - a.e >= 2 && b.s < g.end - 1 && a.e > g.start } } }
        println("      subtitles with a hole between two of their pieces: 2.6 ${gaps(v26)}, 2.6.1 ${gaps(v21)}")
        fun piece(s: Seg, t: String?) = Arbiter.Piece(s.s * FRAME_US, s.e * FRAME_US, t)
        val v22n = runNew(prefix, ScanParams.preset(lang).copy(carryText = false)).first
        var res: List<Arbiter.Line> = emptyList()
        for ((label, b21, b22) in listOf(Triple("max 2.6", v26, v22n), Triple("max 2.6.1 (الأدق)      ", v21, v22))) {
            val base = b21.map { piece(it, ocrBest(gts, it.s, it.e)) }
            val altsRaw = listOf(b22.map { piece(it, null) }, c20.map { piece(it, null) }, b21.map { piece(it, null) })
            val need = Arbiter.needed(base, altsRaw, FRAME_US, 4)
            val alts = listOf(
                b22.mapIndexed { i, s -> piece(s, if (i in need[0]) ocrBest(gts, s.s, s.e) else null) },
                c20.mapIndexed { i, s -> piece(s, if (i in need[1]) ocrMiddle(gts, s.s, s.e) else null) },
                b21.mapIndexed { i, s -> piece(s, if (i in need[2]) ocrBest(gts, s.s, s.e) else null) })
            res = Arbiter.resolve(base, alts, FRAME_US, 4)
            e2e(label, gts, res.map { Out((it.startUs / FRAME_US).toInt(), (it.endUs / FRAME_US).toInt(), it.text) },
                base.size + need.sumOf { it.size })
        }
        if (System.getenv("PERLINE") != null) {
            for ((a, b) in listOf("2.6 (cut, no carry)" to "2.6.1 (carried line)  ", "max 2.6" to "max 2.6.1 (الأدق)      ")) {
                val pa = PER[a]!!; val pb = PER[b]!!
                fun err(gk: String, v: String): Int { if (v == "none") return 999; val (gs, ge) = gk.split("-").map { it.toInt() }; val (s0, e0) = v.split("-").map { it.toInt() }; return Math.abs(s0 - gs) + Math.abs(e0 - ge) }
                for (k in pa.keys) { val ea = err(k, pa[k]!!); val eb = err(k, pb[k]!!); if (Math.abs(ea - eb) >= 2) println("      [$b vs $a] GT $k: ${pa[k]} -> ${pb[k]}  (err $ea -> $eb)") }
            }
            PER.clear()
        }
        if (System.getenv("V") != null) res.filter { it.note != "2.1" }.forEach { println("      ${it.startUs / FRAME_US}-${it.endUs / FRAME_US} ${it.note}: ${it.text}") }
    }
}
