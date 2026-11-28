import com.abdulrahman.hardsubocr.core.*
fun dbgMain(name: String, frames: List<Int>) {
    val prefix = "/home/claude/vt/$name"; val (j, gts, _) = loadTruth(prefix)
    FRAME_US = 1_000_000L / j["fps"].toString().toInt()
    val lang = if (name == "en") SubLang.ENGLISH else SubLang.ARABIC
    val c20 = runOld(prefix); val v21 = runNew(prefix, ScanParams.preset(lang).legacy21()).first; val v22 = runNew(prefix, ScanParams.preset(lang)).first
    for (f in frames) {
        println("--- around $f")
        gts.filter { it.end > f - 80 && it.start < f + 80 }.forEach { println("   GT  ${it.start}-${it.end} ${it.text}") }
        v21.filter { it.e > f - 80 && it.s < f + 80 }.forEach { println("   2.1 ${it.s}-${it.e} '${ocrComposite(gts, it.s, it.e)}'") }
        v22.filter { it.e > f - 80 && it.s < f + 80 }.forEach { println("   2.2 ${it.s}-${it.e} '${ocrComposite(gts, it.s, it.e)}'") }
        c20.filter { it.e > f - 80 && it.s < f + 80 }.forEach { println("   2.0 ${it.s}-${it.e} '${ocrMiddle(gts, it.s, it.e)}'") }
    }
}
fun dbgLines(name: String, from: Int, to: Int) {
    val prefix = "/home/claude/vt/$name"; val (j, gts, _) = loadTruth(prefix)
    FRAME_US = 1_000_000L / j["fps"].toString().toInt()
    val c20 = runOld(prefix); val v21 = runNew(prefix, ScanParams.preset(SubLang.ARABIC).legacy21()).first; val v22 = runNew(prefix, ScanParams.preset(SubLang.ARABIC)).first
    fun piece(s: Seg, t: String?) = Arbiter.Piece(s.s * FRAME_US, s.e * FRAME_US, t)
    val base = v21.map { piece(it, ocrComposite(gts, it.s, it.e)) }
    val raw = listOf(v22.map { piece(it, null) }, c20.map { piece(it, null) }, v21.map { piece(it, null) })
    val need = Arbiter.needed(base, raw, FRAME_US, 4)
    val alts = listOf(v22.mapIndexed { i, s -> piece(s, if (i in need[0]) ocrComposite(gts, s.s, s.e, 2) else null) },
        c20.mapIndexed { i, s -> piece(s, if (i in need[1]) ocrMiddle(gts, s.s, s.e) else null) },
        v21.mapIndexed { i, s -> piece(s, if (i in need[2]) ocrBest(gts, s.s, s.e) else null) })
    val res = Arbiter.resolve(base, alts, FRAME_US, 4)
    gts.filter { it.end > from && it.start < to }.forEach { println("GT   ${it.start}-${it.end} ${it.text}") }
    v21.filter { it.e > from && it.s < to }.forEach { println("2.1  ${it.s}-${it.e} '${ocrComposite(gts, it.s, it.e)}'") }
    res.filter { it.endUs / FRAME_US > from && it.startUs / FRAME_US < to }.forEach { println("ARB  ${it.startUs / FRAME_US}-${it.endUs / FRAME_US} ${it.note}: ${it.text}") }
    alts.forEachIndexed { e, l -> l.forEachIndexed { i, p -> val s = (p.startUs / FRAME_US).toInt(); val t = (p.endUs / FRAME_US).toInt(); if (t > from && s < to) println("  alt${listOf("2.2", "2.0", "2.1b")[e]} $s-$t ${if (p.text == null) "(not read)" else "'" + p.text + "'"}") } }
}
