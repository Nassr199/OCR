import com.abdulrahman.hardsubocr.core.*

var fails = 0
fun check(name: String, got: String, want: String) {
    val ok = got == want
    println((if (ok) "✓ " else "✖ ") + name + (if (!ok) "\n    got:  «$got»\n    want: «$want»" else "  «${got.replace("\n", " ⏎ ")}»"))
    if (!ok) fails++
}

fun ocr(body: String) = VisionEngine.parseText(body)
fun W.shift(dx: Int, dy: Int, c: Double = this.c) = W(t, x0 + dx, y0 + dy, x1 + dx, y1 + dy, c)

/** One line right to left from (right, top): words [ws] with widths [px] per letter, 18 px between words. */
fun rtlLine(ws: List<String>, right: Int = 1100, top: Int = 700, h: Int = 52, perChar: Int = 22): List<W> {
    var r = right
    return ws.map { t -> val w = W(t, r - t.length * perChar, top, r, top + h); r -= t.length * perChar + 18; w }
}

/**
 * Google Vision with the faults of the user's SRT (2.7.9): the place of some words read a second time
 * (the letters and their drop shadow) inside the SAME paragraph, after the line. Vision's text is
 * rebuilt from the word boxes (VisionLayout), so the copies must go and real repeats must stay.
 */
fun main() {
    val hear = rtlLine(listOf("هل", "تسمعينني؟", "هل", "تسمعينني؟", "انظري", "إلي"))
    val hearText = "هل تسمعينني؟ هل تسمعينني؟ انظري إلي"
    check("clean reading", ocr(answer(hear)), hearText)
    check("«… انظري إلي هل تسمعينني؟ هل» (copy in the same paragraph)", ocr(answer(hear + hear.take(3).map { it.shift(2, 3, 0.7) })), hearText)
    check("copy as its own block first", ocr(answer(hear.take(3).map { it.shift(2, 3, 0.7) }, hear)), hearText)
    val wound = rtlLine(listOf("قمت", "بالضغط", "المباشر", "على", "الجرح"))
    val woundText = "قمت بالضغط المباشر على الجرح"
    check("«… على الجرح الجرح» (copy in the same paragraph)", ocr(answer(wound + wound.last().shift(2, 3, 0.7))), woundText)
    check("copy with the higher confidence still one word", ocr(answer(wound + wound.last().shift(2, 3, 0.99))), woundText)
    check("real repeat «هل تسمعينني؟ هل تسمعينني؟» kept", ocr(answer(rtlLine(listOf("هل", "تسمعينني؟", "هل", "تسمعينني؟")))), "هل تسمعينني؟ هل تسمعينني؟")
    check("real repeat «على الجرح الجرح الآن» kept", ocr(answer(rtlLine(listOf("على", "الجرح", "الجرح", "الآن")))), "على الجرح الجرح الآن")
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
