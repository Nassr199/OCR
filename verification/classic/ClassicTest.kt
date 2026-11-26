import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.runBlocking

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

fun line(t: String, cx: Float, cy: Float, w: Float, h: Float) = LensProtocol.Line(t, LensProtocol.Box(cx, cy, w, h, 0f))
fun par(vararg l: LensProtocol.Line) = LensProtocol.Paragraph(l.toList(), null)

class FakeHttp(val answers: ArrayDeque<ByteArray>) : HttpTransport {
    val sizes = ArrayList<Pair<Int, Int>>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val b = answers.removeFirstOrNull() ?: throw java.io.IOException("no more answers")
        return HttpResponse(200, b, emptyMap())
    }
}

/** A Lens crupload answer with the given lines (paragraph per line), boxes as fractions. */
fun lensAnswer(vararg lines: Triple<String, FloatArray, Int>): ByteArray {
    val w = ProtoWriter()
    w.message(2) {                       // objects response
        message(3) {                     // text
            message(1) {                 // layout
                for ((t, b, dir) in lines) {
                    message(1) {         // paragraph
                        message(2) {     // line
                            for ((i, word) in t.split(' ').withIndex()) message(1) { string(2, word); if (i < t.split(' ').size - 1) string(3, " ") }
                            message(2) { message(1) { float32(1, b[0]); float32(2, b[1]); float32(3, b[2]); float32(4, b[3]) } }
                        }
                        varint(4, dir.toLong())
                    }
                }
            }
        }
    }
    return w.toByteArray()
}

fun main() = runBlocking {
    // ---------------------------------------------------------------- key / extends / vote
    check("key: letters and spaces only", ClassicViews.key("حبيبي، هل أنتَ بخير؟") == "حبيبي هل أنت بخير", ClassicViews.key("حبيبي، هل أنتَ بخير؟"))
    check("extends: lost first letter", ClassicViews.extends("أبي", "بي"))
    check("extends: lost first letter of a line", ClassicViews.extends("حبيبي هل أنت بخير", "بيبي هل أنت بخير"))
    check("extends: lost last letter", ClassicViews.extends("أمي", "أم"))
    check("extends: a separate phantom word is not an extension", !ClassicViews.extends("و لماذا", "لماذا"))
    check("extends: 3 letters more is not", !ClassicViews.extends("أنابخير", "بخير"))
    // live bench cases (Lens on synthetic crops)
    check("vote: missed by the whole picture, read by two looks", ClassicViews.vote(listOf("", "أبي", "أبي")) == 1)
    check("vote: the look that kept the edge letter", ClassicViews.vote(listOf("بي", "بي", "بي", "ابي")) == 3)
    check("vote: «ي» «امي» «مي» «مي» → «امي»", ClassicViews.vote(listOf("ي", "امي", "مي", "مي")) == 1)
    check("vote: majority", ClassicViews.vote(listOf("أمي", "", "أمي", "می")) == 0)
    check("vote: tie → the whole picture", ClassicViews.vote(listOf("و لماذا؟", "لماذا؟")) == 0)
    check("vote: single letters are not subtitles", ClassicViews.vote(listOf("و", "ا", "")) == -1)
    check("vote: nothing at all", ClassicViews.vote(listOf("", "Ketay", "")) == -1)
    check("suspicious: short", ClassicViews.suspicious("أبي", 0))
    check("suspicious: fragments removed", ClassicViews.suspicious("لماذا؟", 2))
    check("not suspicious: a sentence", !ClassicViews.suspicious("حبيبي، هل أنت بخير؟", 0))

    // ---------------------------------------------------------------- strays and order (real Lens geometry, 1230×201)
    val r = LensProtocol.Result(listOf(
        par(line("-", 0.47f, 0.55f, 0.01f, 0.05f)), par(line("لماذا ؟", 0.50f, 0.62f, 0.10f, 0.25f)), par(line("و", 0.56f, 0.6f, 0.01f, 0.1f)),
        par(line("Senaryo", 0.5f, 0.41f, 0.06f, 0.09f))
    ), "ar", null)
    check("strays: «-» and «و» dropped", r.strays(1230, 201) == 2, r.strays(1230, 201).toString())
    check("strays: reading without them", Cleaner.clean(r.readingText(1230, 201), CleanMode.PRO).replace(" ", "") == "لماذا؟", Cleaner.clean(r.readingText(1230, 201), CleanMode.PRO))
    val lone = LensProtocol.Result(listOf(par(line("و", 0.5f, 0.6f, 0.01f, 0.1f)), par(line("Kerem Çatay", 0.5f, 0.6f, 0.2f, 0.2f))), "ar", null)
    check("strays: nothing removed without a real Arabic line", lone.strays(1230, 201) == 0)
    val credits = LensProtocol.Result(listOf(
        par(line("Saç Tasarım", 0.187f, 0.410f, 0.085f, 0.085f)), par(line("Mustafa Gümüş", 0.187f, 0.654f, 0.245f, 0.259f)),
        par(line("حبيبي، هل أنت بخير؟", 0.500f, 0.665f, 0.335f, 0.259f)),
        par(line("Makyaj Tasarım", 0.821f, 0.413f, 0.115f, 0.100f)), par(line("Beste Bayoğlu", 0.822f, 0.597f, 0.219f, 0.169f))
    ), "ar", null)
    val rt = credits.readingText(1230, 201)
    check("order: the Arabic line is a line of its own (no Latin glued to it)", rt.lines().any { it == "حبيبي، هل أنت بخير؟" }, rt.replace("\n", " ⏎ "))
    val boxes = credits.arabicBoxes(1230, 201)
    check("arabic boxes in pixels", boxes.size == 1 && boxes[0][0] in 405..412 && boxes[0][2] in 818..824, boxes.joinToString { it.joinToString(",") })
    val inter = LensProtocol.Result(listOf(par(
        line("كنت", 0.55f, 0.35f, 0.08f, 0.25f), line("أبحث عنك", 0.44f, 0.70f, 0.15f, 0.25f),
        line("أين", 0.62f, 0.35f, 0.06f, 0.25f), line("كنت", 0.55f, 0.70f, 0.06f, 0.25f))), "ar", null)
    check("order: interleaved chunks → rows right to left", inter.readingText(1230, 201) == "أين كنت\nكنت أبحث عنك", inter.readingText(1230, 201).replace("\n", " ⏎ "))
    val two = LensProtocol.Result(listOf(par(line("أين كنت؟", 0.5f, 0.35f, 0.2f, 0.25f), line("كنت أبحث عنك", 0.5f, 0.7f, 0.25f, 0.25f))), "ar", null)
    check("order: two lines unchanged", two.readingText(1230, 201) == "أين كنت؟\nكنت أبحث عنك")

    // ---------------------------------------------------------------- the subtitle's shape (2.7.7)
    fun ct(s: String, strip: Boolean = false) = ClassicText.clean(s, strip)
    check("shape: dialogue dashes and lines kept", ct("- هوليا\n- ابتعدي يا جوليزار") == "- هوليا\n- ابتعدي يا جوليزار", ct("- هوليا\n- ابتعدي يا جوليزار"))
    check("shape: two lines and punctuation kept, space before ؟ removed", ct("هوليا. ساعدوني\nماذا حدث ؟") == "هوليا. ساعدوني\nماذا حدث؟")
    check("shape: dash glued to the word gets its space", ct("-هوليا") == "- هوليا")
    check("shape: Latin credits lines dropped", ct("Saç Tasarım\nحبيبي، هل أنت بخير؟\nBeste Bayoğlu") == "حبيبي، هل أنت بخير؟")
    check("shape: Latin glued to the line removed", ct("Mustafa Gümüş حبيبي") == "حبيبي")
    check("shape: presentation forms → ordinary letters", ct("\uFEFB أعرف") == "لا أعرف", ct("\uFEFB أعرف"))
    check("shape: tatweel removed", ct("جـميل") == "جميل")
    check("shape: a subtitle saying «لا يوجد نص» stays (no AI reply filter)", ct("لا يوجد نص في الرسالة") == "لا يوجد نص في الرسالة")
    check("shape: tashkeel kept as read", ct("سَأَعودُ") == "سَأَعودُ")
    check("shape: tashkeel removed when asked", ct("سَأَعودُ", true) == "سأعود")
    check("shape: ellipsis and exclamation", ct("انتظر... لا تذهب !") == "انتظر... لا تذهب!")
    check("shape: quotes and numbers", ct("قال: «ارحل» الساعة 12:30") == "قال: «ارحل» الساعة 12:30", ct("قال: «ارحل» الساعة 12:30"))
    check("shape: a lone mark is not a subtitle", ct("-") == "" && ct("- \n Ketay") == "")
    check("cleaner: CLASSIC mode = ClassicText", Cleaner.clean("Kurgu\n- هوليا\n- ابتعدي", CleanMode.CLASSIC) == "- هوليا\n- ابتعدي")
    check("cleaner: CLASSIC + remove tashkeel", Cleaner.clean("سَأَعودُ", CleanMode.CLASSIC, stripDiacritics = true) == "سأعود")
    // Lens: a dash read as its own line beside the text is the dialogue dash; one over the text is a fragment
    val dash = LensProtocol.Result(listOf(
        par(line("هوليا", 0.50f, 0.36f, 0.10f, 0.25f)), par(line("-", 0.565f, 0.37f, 0.012f, 0.03f)),
        par(line("ابتعدي يا جوليزار", 0.48f, 0.68f, 0.26f, 0.25f)), par(line("-", 0.625f, 0.69f, 0.012f, 0.03f))
    ), "ar", null)
    val dt = dash.readingText(1230, 230)
    check("lens: dialogue dashes beside the lines kept, in front", ClassicText.clean(dt) == "- هوليا\n- ابتعدي يا جوليزار", dt.replace("\n", " ⏎ "))
    val over = LensProtocol.Result(listOf(par(line("لماذا ؟", 0.50f, 0.62f, 0.10f, 0.25f)), par(line("-", 0.505f, 0.55f, 0.01f, 0.05f))), "ar", null)
    check("lens: a dash over the text is a fragment", over.strays(1230, 201) == 1 && ClassicText.clean(over.readingText(1230, 201)) == "لماذا؟", over.readingText(1230, 201))
    val bang = LensProtocol.Result(listOf(par(line("لا تذهب", 0.52f, 0.5f, 0.15f, 0.25f)), par(line("!", 0.437f, 0.5f, 0.01f, 0.2f))), "ar", null)
    check("lens: «!» beside the line stays at its end", ClassicText.clean(bang.readingText(1230, 201)) == "لا تذهب!", bang.readingText(1230, 201))

    // live bench 07_short_4_a170: «E» of «Ece» left of the box read as «-» on the subtitle's row
    val leftDash = LensProtocol.Result(listOf(
        par(line("-", 0.426f, 0.609f, 0.035f, 0.154f)), par(line("لماذا ؟", 0.502f, 0.609f, 0.094f, 0.154f)), par(line("و", 0.573f, 0.609f, 0.024f, 0.154f))
    ), "ar", null)
    check("lens: a dash on the left of an Arabic line is a crew-name piece", ClassicText.clean(leftDash.readingText(1230, 201)) == "لماذا؟" && leftDash.strays(1230, 201) == 2, leftDash.readingText(1230, 201))

    val mixedRow = LensProtocol.Result(listOf(
        par(line("Yardımcı Yönetmen Gökhan Yılmaz", 0.2f, 0.5f, 0.3f, 0.2f)), par(line("هوليا", 0.50f, 0.5f, 0.10f, 0.25f)),
        par(line("-", 0.565f, 0.5f, 0.012f, 0.03f)), par(line("2. Ekip Görüntü Yönetmeni Tuğberk Süel", 0.82f, 0.5f, 0.3f, 0.2f))
    ), "ar", null)
    check("lens: on a row of long crew names the Arabic piece still reads right to left", ClassicText.clean(mixedRow.readingText(1230, 230)) == "- هوليا", mixedRow.readingText(1230, 230).replace("\n", " ⏎ "))

    // ---------------------------------------------------------------- plan
    val p = ClassicViews.plan(1230, 201, boxes)
    check("plan: ×2, around, band", p.map { it.name } == listOf("×2", "حول الترجمة", "شريط الترجمة"), p.joinToString { it.name })
    val around = p[1]
    check("plan: around keeps the whole line", around.x0 <= boxes[0][0] && around.x1 >= boxes[0][2] && around.y0 == 0 && around.y1 == 201)
    val p0 = ClassicViews.plan(1230, 201, emptyList())
    check("plan: nothing Arabic → ×2 and the centre", p0.map { it.name } == listOf("×2", "الوسط"))
    check("plan: no ×2 for a big picture", ClassicViews.plan(1500, 844, emptyList()).none { it.scale > 1f })

    // ---------------------------------------------------------------- engine: the looks and the vote
    val views = ImageResampler { img, x0, y0, x1, y1, sc -> PreparedImage(byteArrayOf(1), "image/png", ((x1 - x0) * sc).toInt(), ((y1 - y0) * sc).toInt(), img.name) }
    val missed = lensAnswer(Triple("Yapım Koordinatörü", floatArrayOf(0.5f, 0.41f, 0.14f, 0.1f), 0))
    val abi = lensAnswer(Triple("أبي", floatArrayOf(0.5f, 0.67f, 0.05f, 0.25f), 1))
    var seen = ""
    val e = LensEngine(FakeHttp(ArrayDeque(listOf(missed, abi, abi))), "ar", "US", views = views, looks = { seen = it })
    val got = e.recognize(PreparedImage(byteArrayOf(0), "image/png", 1230, 201, "x"))
    check("engine: whole picture missed «أبي», the looks found it", got == "أبي", "$got | $seen")
    check("engine: report line names the looks", seen.contains("×2") && seen.contains("الوسط"), seen)
    val sentence = lensAnswer(Triple("حبيبي، هل أنت بخير؟", floatArrayOf(0.5f, 0.67f, 0.33f, 0.26f), 1))
    val http1 = FakeHttp(ArrayDeque(listOf(sentence)))
    val e1 = LensEngine(http1, "ar", "US", views = views)
    check("engine: a clear sentence is read once", e1.recognize(PreparedImage(byteArrayOf(0), "image/png", 1230, 201, "y")) == "حبيبي، هل أنت بخير؟" && http1.answers.isEmpty())
    val enHttp = FakeHttp(ArrayDeque(listOf(lensAnswer(Triple("Hi", floatArrayOf(0.5f, 0.67f, 0.05f, 0.25f), 0)))))
    check("engine: English subtitles never get the Arabic looks", LensEngine(enHttp, "en", "US", views = views).recognize(PreparedImage(byteArrayOf(0), "image/png", 1230, 201, "z")) == "Hi")
    val failingViews = FakeHttp(ArrayDeque(listOf(lensAnswer(Triple("بي", floatArrayOf(0.5f, 0.67f, 0.04f, 0.25f), 1)))))
    check("engine: looks that fail leave the first reading", LensEngine(failingViews, "ar", "US", views = views).recognize(PreparedImage(byteArrayOf(0), "image/png", 1230, 201, "w")) == "بي")
    check("cache id: classic version and multi-view in it", e.cacheId == "lens:ar:US:c$CLASSIC_OCR_VERSION:w2:mv" && LensEngine(FakeHttp(ArrayDeque()), "ar", "US").cacheId == "lens:ar:US:c$CLASSIC_OCR_VERSION:w2", e.cacheId)

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
