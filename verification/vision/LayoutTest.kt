import com.abdulrahman.hardsubocr.core.*
import kotlinx.serialization.json.*

var fails = 0
fun check(name: String, got: String, want: String) {
    val ok = got == want
    println((if (ok) "✓ " else "✖ ") + name + (if (!ok) "\n    got:  «$got»\n    want: «$want»" else "  «${got.replace("\n", " ⏎ ")}»"))
    if (!ok) fails++
}

fun ocr(body: String) = VisionEngine.parseText(body)
fun pro(body: String) = Cleaner.clean(ocr(body), CleanMode.PRO)
fun W.shift(dx: Int, dy: Int, t: String = this.t, c: Double = this.c) = W(t, x0 + dx, y0 + dy, x1 + dx, y1 + dy, c)

fun main() {
    // «بخير بخير أجل أنا بخير» — visual right → left
    val w1 = W("بخير", 900, 100, 1000, 150); val w2 = W("بخير", 780, 100, 880, 150)
    val w3 = W("أجل", 690, 100, 760, 150); val w4 = W("أنا", 600, 100, 670, 150); val w5 = W("بخير", 480, 100, 580, 150)
    val line = listOf(w1, w2, w3, w4, w5)
    val copy = line.map { it.shift(2, 2, c = 0.8) }
    val want = "بخير بخير أجل أنا بخير"
    check("user case: [w2..w5] + [w1] + [w2..w5] (outline copy)", ocr(answer(copy.drop(1), listOf(w1), line.drop(1))), want)
    check("user case: partial copy + full line", ocr(answer(copy.drop(1), line)), want)
    check("user case: all 9 words in one paragraph", ocr(answer(copy.drop(1) + listOf(w1) + line.drop(1))), want)
    check("user case after PRO cleaning", pro(answer(copy.drop(1), listOf(w1), line.drop(1))), want)
    check("clean single reading unchanged", ocr(answer(line)), want)
    check("real repeats kept: لا لا لا", ocr(answer(listOf(W("لا", 700, 100, 740, 150), W("لا", 640, 100, 680, 150), W("لا", 580, 100, 620, 150)))), "لا لا لا")

    // «حبيبي، هل أنت بخير؟» — blocks laid out left to right
    val h1 = W("حبيبي،", 850, 100, 1000, 150); val h2 = W("هل", 760, 100, 820, 150)
    val h3 = W("أنت", 650, 100, 740, 150); val h4 = W("بخير؟", 500, 100, 630, 150)
    check("block order RTL", ocr(answer(listOf(h2, h3, h4), listOf(h1))), "حبيبي، هل أنت بخير؟")
    check("block order RTL, wide gap → 2 lines → PRO one line", pro(answer(listOf(h2, h3, h4), listOf(h1.shift(150, 0)))), "حبيبي، هل أنت بخير؟")
    check("misread copy loses to better copy", ocr(answer(listOf(h2, h3, h4), listOf(h1.shift(3, 1, "ببيي،", 0.55)), listOf(h1))), "حبيبي، هل أنت بخير؟")
    check("punctuation as own word", ocr(answer(listOf(W("ماذا", 640, 100, 760, 150), W("حدث", 520, 100, 620, 150), W("؟", 495, 100, 515, 150)))), "ماذا حدث؟")
    check("punctuation copy over a word that has it", ocr(answer(listOf(W("ماذا", 640, 100, 760, 150), W("حدث؟", 495, 100, 620, 150)), listOf(W("؟", 497, 101, 517, 151)))), "ماذا حدث؟")

    // joined vs separate readings of the same two words
    val j = W("بخيربخير", 780, 100, 1000, 150, 0.7)
    check("joined copy → separate words stay", ocr(answer(listOf(j), listOf(w1, w2))), "بخير بخير")
    check("fragments of one word → whole word stays", ocr(answer(listOf(W("بخير", 600, 100, 700, 150, 0.6)), listOf(W("بخ", 652, 101, 700, 150, 0.9), W("ير", 600, 101, 651, 150, 0.9)))), "بخير")

    // two lines, credits on the row, edge coordinates
    val top = listOf(W("أين", 800, 40, 900, 90), W("كنت؟", 650, 40, 780, 90))
    val bot = listOf(W("كنت", 800, 110, 900, 160), W("أبحث", 680, 110, 780, 160), W("عنك", 570, 110, 660, 160))
    check("two lines top → bottom", ocr(answer(bot, top)), "أين كنت؟\nكنت أبحث عنك")
    check("credits far left on the same row → own line, removed by PRO", pro(answer(listOf(W("Netflix", 0, 105, 160, 150)), line)), want)
    check("x = 0 left out of vertices", ocr(answer(listOf(W("أبي", 0, 0, 120, 50)))), "أبي")
    check("Latin run inside Arabic line read LTR", ocr(answer(listOf(W("شاهد", 800, 100, 900, 150), W("Big", 640, 100, 700, 150), W("Ben", 710, 100, 780, 150)))), "شاهد Big Ben")
    check("English line LTR", ocr(answer(listOf(W("World", 300, 100, 450, 150), W("Hello", 100, 100, 280, 150)))), "Hello World")
    check("digits keep Arabic order", ocr(answer(listOf(W("الساعة", 800, 100, 950, 150), W("10", 720, 100, 780, 150)))), "الساعة 10")

    // genuine words in separate paragraphs must all stay
    check("line split across paragraphs (box of one covers the other)", ocr(answer(top + bot.take(1), bot.drop(1))), "أين كنت؟\nكنت أبحث عنك")
    check("short paragraph beside a long one", ocr(answer(listOf(W("لا", 940, 100, 1000, 150)), listOf(W("أعرف", 820, 100, 920, 150), W("الآن", 700, 100, 800, 150)))), "لا أعرف الآن")
    check("same text on two lines stays twice", ocr(answer(listOf(W("لا", 700, 40, 760, 90)), listOf(W("لا", 700, 110, 760, 160)))), "لا\nلا")
    check("tashkeel word kept", ocr(answer(listOf(W("مَرْحَبًا", 700, 95, 900, 150)))), "مَرْحَبًا")
    check("comma box low on the row", ocr(answer(listOf(W("نعم", 800, 100, 900, 150), W("،", 780, 130, 795, 150), W("أنا", 680, 100, 760, 150)))), "نعم، أنا")
    check("big shadow offset (6 px) copy dropped", ocr(answer(line.map { it.shift(6, 5, c = 0.5) }, line)), want)
    check("opening quote glued", ocr(answer(listOf(W("قال", 900, 100, 980, 150), W("«", 880, 100, 895, 150), W("مرحبا", 760, 100, 875, 150), W("»", 740, 100, 755, 150)))), "قال «مرحبا»")

    // no boxes → Vision's own text
    check("no geometry → own text", ocr("""{"responses":[{"textAnnotations":[{"description":"أبي\n"}],"fullTextAnnotation":{"text":"أبي\n"}}]}"""), "أبي")
    check("only textAnnotations words", ocr("""{"responses":[{"textAnnotations":[{"description":"هل أنت بخير؟ حبيبي،"},
        {"description":"هل","boundingPoly":{"vertices":[{"x":760,"y":100},{"x":820,"y":100},{"x":820,"y":150},{"x":760,"y":150}]}},
        {"description":"حبيبي،","boundingPoly":{"vertices":[{"x":850,"y":100},{"x":1000,"y":100},{"x":1000,"y":150},{"x":850,"y":150}]}}]}]}"""), "حبيبي، هل")
    check("empty answer", ocr("""{"responses":[{}]}"""), "")
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
