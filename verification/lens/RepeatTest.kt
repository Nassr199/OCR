import com.abdulrahman.hardsubocr.core.*

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/**
 * Google Lens: a place read twice INSIDE one line (the letters and their outline / drop shadow), the
 * fault of the user's SRT «هل تسمعينني؟ هل تسمعينني؟ انظري إلي هل تسمعينني؟ هل» and «… على الجرح الجرح».
 * The answers are real Lens protobuf (Chromium's field numbers), built here and read by the app's parser.
 */
class W(val text: String, val sep: String, val box: FloatArray?) // box: cx, cy, w, h (fractions)
class L(val words: List<W>, val box: FloatArray?)

fun ProtoWriter.geometry(field: Int, b: FloatArray) = message(field) {
    message(1) { float32(1, b[0]); float32(2, b[1]); float32(3, b[2]); float32(4, b[3]); float32(5, 0f) }
}

fun answer(paragraphs: List<List<L>>): ByteArray = ProtoWriter().message(2) {     // objects_response
    message(3) {                                                                  //   text
        message(1) {                                                              //     text_layout
            for (p in paragraphs) message(1) {                                    //       paragraph
                for (l in p) message(2) {                                         //         line
                    for (w in l.words) message(1) {                               //           word
                        string(2, w.text)
                        if (w.sep.isNotEmpty()) string(3, w.sep)
                        w.box?.let { geometry(4, it) }
                    }
                    l.box?.let { geometry(2, it) }
                }
            }
        }
        string(2, "ar")
    }
}.toByteArray()

/**
 * An Arabic line laid out right to left the way Lens hands it: one word per entry, a mark («؟», «،»)
 * as its own word with no space before it. Returns the words with their boxes and the line box.
 */
fun rtl(text: String, cx: Float = 0.5f, cy: Float = 0.85f, h: Float = 0.06f, charW: Float = 0.012f): L {
    val tokens = ArrayList<Pair<String, String>>() // text, separator
    for ((i, part) in text.split(' ').withIndex()) {
        val last = i == text.split(' ').size - 1
        val mark = part.lastOrNull()?.let { it in "؟،.!" } == true && part.length > 1
        if (mark) { tokens.add(part.dropLast(1) to ""); tokens.add(part.last().toString() to if (last) "" else " ") }
        else tokens.add(part to if (last) "" else " ")
    }
    val widths = tokens.map { (t, s) -> t.length * charW + (if (s.isNotEmpty()) charW else 0f) }
    val total = widths.sum()
    var right = cx + total / 2
    val words = tokens.mapIndexed { i, (t, s) ->
        val w = tokens[i].first.length * charW
        val b = floatArrayOf(right - w / 2, cy, w, h)
        right -= widths[i]
        W(t, s, b)
    }
    return L(words, floatArrayOf(cx, cy, total, h))
}

/** [l]'s words [idx] read a second time a few pixels off (the shadow), appended to the line. */
fun withCopies(l: L, idx: List<Int>, dx: Float = 0.002f, dy: Float = 0.004f): L {
    val copies = idx.map { i -> val w = l.words[i]; W(w.text, " ", floatArrayOf(w.box!![0] + dx, w.box[1] + dy, w.box[2], w.box[3])) }
    val words = l.words.dropLast(1) + W(l.words.last().text, " ", l.words.last().box) + copies
    return L(words, l.box)
}

fun read(paragraphs: List<List<L>>): LensProtocol.Result = LensProtocol.parseResponse(answer(paragraphs))
fun text(r: LensProtocol.Result) = r.readingText(1500, 844)

fun main() {
    val hear = "هل تسمعينني؟ هل تسمعينني؟ انظري إلي"
    val wound = "قمت بالضغط المباشر على الجرح"

    // the answers Lens gives without a copy: the app's text is the subtitle (and the parser joins words as before)
    val clean = read(listOf(listOf(rtl(hear))))
    check("clean line: text as before", text(clean) == hear && clean.repeats.isEmpty(), text(clean))

    // 1) the user's first SRT line: «هل تسمعينني؟ هل» read again on its place, appended to the line
    val l1 = rtl(hear)
    val r1 = read(listOf(listOf(withCopies(l1, listOf(0, 1, 2, 3)))))
    val raw1 = withCopies(l1, listOf(0, 1, 2, 3)).words.joinToString("") { it.text + it.sep }.trim()
    check("before: Lens's own line repeats the start", raw1.startsWith("$hear هل تسمعينني") && raw1.endsWith("هل"), raw1)
    check("«… انظري إلي هل تسمعينني؟ هل» → the subtitle", text(r1) == hear, text(r1))
    check("…the words left out are listed for the diagnostic file", r1.repeats == listOf("هل", "تسمعينني", "؟", "هل"), r1.repeats.toString())

    // 2) the second SRT line: «الجرح» twice
    val l2 = rtl(wound)
    val r2 = read(listOf(listOf(withCopies(l2, listOf(l2.words.size - 1)))))
    check("«… على الجرح الجرح» → the subtitle", text(r2) == wound, text(r2))

    // 3) words the subtitle really says twice stand in two places: kept
    val twice = read(listOf(listOf(rtl("لا لا لا"))))
    check("real repeat «لا لا لا» kept", text(twice) == "لا لا لا", text(twice))
    val hearOnly = read(listOf(listOf(rtl("هل تسمعينني؟ هل تسمعينني؟"))))
    check("real repeat «هل تسمعينني؟ هل تسمعينني؟» kept", text(hearOnly) == "هل تسمعينني؟ هل تسمعينني؟", text(hearOnly))
    check("…and written to the diagnostic file with its places", hearOnly.twiceKept().split("«").size - 1 == 4 && hearOnly.repeats.isEmpty(), hearOnly.twiceKept())
    check("a clean single reading has nothing for that line", clean.twiceKept().split("«").size - 1 == 4 && read(listOf(listOf(rtl(wound)))).twiceKept().isEmpty())
    val sameRow = read(listOf(listOf(rtl("لا", cy = 0.80f), rtl("لا", cy = 0.86f))))
    check("the same word on two lines kept", text(sameRow) == "لا\nلا", text(sameRow))

    // 4) a shadow copy of a real repeat: only the copy goes
    val l4 = rtl("لا لا")
    val r4 = read(listOf(listOf(withCopies(l4, listOf(1)))))
    check("«لا لا» + copy of the second «لا» → «لا لا»", text(r4) == "لا لا", text(r4))

    // 5) word boxes that are the line's box (no word geometry worth the name): nothing is compared
    val lineBox = floatArrayOf(0.5f, 0.85f, 0.4f, 0.06f)
    val flat = L(listOf(W("لا", " ", lineBox), W("لا", " ", lineBox), W("لا", "", lineBox)), lineBox)
    check("words carrying the line's box: untouched", text(read(listOf(listOf(flat)))) == "لا لا لا")
    // 6) no word boxes at all: untouched (the line text as Lens joins it)
    val noBox = L(listOf(W("الجرح", " ", null), W("الجرح", "", null)), lineBox)
    check("words without boxes: untouched", text(read(listOf(listOf(noBox)))) == "الجرح الجرح")

    // 7) a dialogue: the copy of the first line's word appended to the second line
    val d1 = rtl("- هوليا", cy = 0.80f); val d2 = rtl("- ابتعدي يا جوليزار", cy = 0.87f)
    val d2c = L(d2.words.dropLast(1) + W(d2.words.last().text, " ", d2.words.last().box) +
        W("هوليا", "", d1.words[1].box!!.let { floatArrayOf(it[0] + 0.002f, it[1] + 0.003f, it[2], it[3]) }), d2.box)
    val r7 = read(listOf(listOf(d1, d2c)))
    check("dialogue: «هوليا» copied into the second line → removed, both dashes kept", text(r7) == "- هوليا\n- ابتعدي يا جوليزار", text(r7))

    // 8) the mark read twice on its place goes; the same mark on another line stays
    val m1 = rtl("- هوليا", cy = 0.80f)
    val m1c = L(m1.words + W("-", "", m1.words[0].box!!.let { floatArrayOf(it[0] + 0.001f, it[1] + 0.002f, it[2], it[3]) }), m1.box)
    val r8 = read(listOf(listOf(m1c, d2)))
    check("a dash read twice → one dash", text(r8) == "- هوليا\n- ابتعدي يا جوليزار", text(r8))

    // 9) a piece of a word read on its place («تسمعين» on «تسمعينني»)
    val l9 = rtl(hear)
    val b = l9.words[1].box!!
    val piece = L(l9.words.dropLast(1) + W(l9.words.last().text, " ", l9.words.last().box) +
        W("تسمعين", "", floatArrayOf(b[0] + b[2] * 0.15f, b[1], b[2] * 0.7f, b[3])), l9.box)
    check("a piece of a word on its place → removed", text(read(listOf(listOf(piece)))) == hear, text(read(listOf(listOf(piece)))))

    // 10) the copy as its own line (another paragraph), lying on the right part of the line: removed
    val l10 = rtl(hear)
    val part = L(l10.words.take(4).map { w -> W(w.text, w.sep, w.box!!.let { floatArrayOf(it[0] + 0.002f, it[1] + 0.004f, it[2], it[3]) }) }, null)
    val r10 = read(listOf(listOf(l10), listOf(part)))
    check("a partial copy as a line of its own → removed", text(r10) == hear, text(r10))
    // …and when the copy comes FIRST, the full line still wins (fuller lines are taken first)
    val r10b = read(listOf(listOf(part), listOf(l10)))
    check("…also when Lens gives the copy first", text(r10b) == hear, text(r10b))

    // 11) a copy with a different reading of a hamza («الي» on «إلي») is still the same word
    val l11 = rtl(hear)
    val e = l11.words.last().box!!
    val c11 = L(l11.words.dropLast(1) + W(l11.words.last().text, " ", e) + W("الي", "", floatArrayOf(e[0] + 0.002f, e[1] + 0.003f, e[2], e[3])), l11.box)
    check("copy read with another hamza → removed", text(read(listOf(listOf(c11)))) == hear, text(read(listOf(listOf(c11)))))

    // 12) two different words that touch (no copy): both stay, even with the same letters nearby
    val near = read(listOf(listOf(rtl("على الجرح الجرح الآن"))))
    check("a word written twice side by side stays twice", text(near) == "على الجرح الجرح الآن", text(near))

    // 13) a credit name beside the subtitle, not overlapping: unaffected
    val credit = L(listOf(W("Ali", " ", floatArrayOf(0.1f, 0.85f, 0.04f, 0.05f)), W("Kaya", "", floatArrayOf(0.15f, 0.85f, 0.05f, 0.05f))),
        floatArrayOf(0.125f, 0.85f, 0.1f, 0.05f))
    val r13 = read(listOf(listOf(credit), listOf(withCopies(rtl(wound), listOf(4)))))
    check("credits beside: only the copy goes", Cleaner.clean(text(r13), CleanMode.PRO) == wound && r13.repeats == listOf("الجرح"), text(r13))

    // 14) the line box shrinks to the words left (the row order of a two-line subtitle is unchanged)
    val l14 = withCopies(rtl(wound), listOf(4))
    val r14 = read(listOf(listOf(l14)))
    val lb = r14.lines[0].box!!
    check("line box = its remaining words", Math.abs(lb.w - rtl(wound).box!![2]) < 0.02f, "w=${lb.w}")

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
