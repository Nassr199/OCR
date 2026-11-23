import com.abdulrahman.hardsubocr.core.*

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/** Google Lens: reading order of the lines and the same place read twice (LensProtocol.Result.readingText(1000, 1000)). */
fun main() {
    // Lens: same place read twice
    fun line(t: String, cx: Float, cy: Float, w: Float, h: Float) = LensProtocol.Line(t, LensProtocol.Box(cx, cy, w, h, 0f))
    val dup = LensProtocol.Result(listOf(
        LensProtocol.Paragraph(listOf(line("بخير أجل أنا بخير", 0.45f, 0.8f, 0.4f, 0.06f)), null),
        LensProtocol.Paragraph(listOf(line("بخير بخير أجل أنا بخير", 0.5f, 0.8f, 0.5f, 0.06f)), null)
    ), "ar", null)
    check("Lens partial copy dropped", dup.readingText(1000, 1000) == "بخير بخير أجل أنا بخير", dup.readingText(1000, 1000))
    val two = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("أين كنت؟", 0.5f, 0.75f, 0.3f, 0.06f), line("كنت أبحث عنك", 0.5f, 0.82f, 0.4f, 0.06f)), null)), "ar", null)
    check("Lens two lines unchanged", two.readingText(1000, 1000) == two.fullText && two.fullText == "أين كنت؟\nكنت أبحث عنك")
    val same = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("لا", 0.5f, 0.75f, 0.05f, 0.06f), line("لا", 0.5f, 0.815f, 0.05f, 0.06f)), null)), "ar", null)
    check("Lens same text on two rows kept", same.readingText(1000, 1000) == "لا\nلا", same.readingText(1000, 1000))
    val nobox = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(LensProtocol.Line("أ", null), LensProtocol.Line("أ", null)), null)), "ar", null)
    check("Lens without boxes unchanged", nobox.readingText(1000, 1000) == nobox.fullText)
    val credits = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("Netflix", 0.1f, 0.8f, 0.12f, 0.05f), line("حبيبي، هل أنت بخير؟", 0.55f, 0.8f, 0.45f, 0.06f)), null)), "ar", null)
    check("Lens far chunks on one row stay separate lines (right one first)", credits.readingText(1000, 1000) == "حبيبي، هل أنت بخير؟\nNetflix", credits.readingText(1000, 1000))
    check("…and the crew name is removed by cleaning", Cleaner.clean(credits.readingText(1000, 1000), CleanMode.PRO) == "حبيبي، هل أنت بخير؟")
    // Lens chunks of a two-line subtitle handed out interleaved / left to right (Subtitle Edit #12149)
    val inter = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("كنت", 0.62f, 0.75f, 0.1f, 0.06f), line("أبحث عنك", 0.45f, 0.82f, 0.2f, 0.06f),
        line("أين", 0.75f, 0.75f, 0.08f, 0.06f), line("كنت", 0.62f, 0.82f, 0.08f, 0.06f)), null)), "ar", null)
    check("Lens interleaved chunks → rows, right to left", inter.readingText(1000, 1000) == "أين كنت\nكنت أبحث عنك", inter.readingText(1000, 1000))
    val lr = LensProtocol.Result(listOf(
        LensProtocol.Paragraph(listOf(line("هل أنت بخير؟", 0.45f, 0.8f, 0.3f, 0.06f)), null),
        LensProtocol.Paragraph(listOf(line("حبيبي،", 0.66f, 0.8f, 0.1f, 0.06f)), null)), "ar", null)
    check("Lens blocks of one line left to right → one line right to left", lr.readingText(1000, 1000) == "حبيبي، هل أنت بخير؟", lr.readingText(1000, 1000))
    val en = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(line("World", 0.6f, 0.8f, 0.1f, 0.06f), line("Hello", 0.45f, 0.8f, 0.1f, 0.06f)), null)), "en", null)
    check("Lens English row left to right", en.readingText(1000, 1000) == "Hello World", en.readingText(1000, 1000))
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
