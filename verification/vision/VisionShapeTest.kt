import com.abdulrahman.hardsubocr.core.*

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/** Google Vision: the subtitle's shape (lines, dialogue dashes, punctuation) through layout + cleaning. */
fun main() {
    fun ocr(body: String) = ClassicText.clean(VisionEngine.parseText(body))
    // geometry of the user's frames: 1230 px wide crop, outlined text, two dialogue lines
    val l1 = listOf(W("-", 690, 70, 712, 100), W("هوليا", 548, 52, 676, 112))
    val l2 = listOf(W("-", 805, 140, 827, 170), W("ابتعدي", 640, 122, 790, 182), W("يا", 580, 122, 628, 182), W("جوليزار", 405, 122, 568, 182))
    check("vision: dialogue dashes in front of both lines", ocr(answer(l1, l2)) == "- هوليا\n- ابتعدي يا جوليزار", ocr(answer(l1, l2)).replace("\n", " ⏎ "))
    check("vision: …blocks given left to right", ocr(answer(l2.reversed(), l1.reversed())) == "- هوليا\n- ابتعدي يا جوليزار", ocr(answer(l2.reversed(), l1.reversed())).replace("\n", " ⏎ "))
    val two = listOf(W("هوليا.", 640, 52, 780, 112), W("ساعدوني", 450, 52, 625, 112))
    val q = listOf(W("ماذا", 600, 122, 700, 182), W("حدث", 500, 122, 590, 182), W("؟", 470, 122, 495, 182))
    check("vision: two lines with period and question mark", ocr(answer(two, q)) == "هوليا. ساعدوني\nماذا حدث؟", ocr(answer(two, q)).replace("\n", " ⏎ "))
    val bang = listOf(W("انتظر...", 640, 52, 800, 112), W("لا", 590, 52, 630, 112), W("تذهب", 480, 52, 580, 112), W("!", 460, 52, 475, 112))
    check("vision: ellipsis and exclamation", ocr(answer(bang)) == "انتظر... لا تذهب!", ocr(answer(bang)))
    val credits = listOf(W("Mustafa", 80, 60, 230, 110), W("Gümüş", 240, 60, 380, 110)) + listOf(W("-", 690, 70, 712, 100), W("هوليا", 548, 52, 676, 112))
    check("vision: crew names on the row removed, dash kept", ocr(answer(credits)) == "- هوليا", ocr(answer(credits)))
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
