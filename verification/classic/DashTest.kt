import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.runBlocking

// 2.7.8: the dash of a dialogue's second (wider) line — «- هوليا ⏎ - ابتعدي يا جوليزار» came back
// «- هوليا ⏎ ابتعدي يا جوليزار». Compile with classic/ClassicTest.kt's helpers left out:
//   ./kc.sh out ../app/.../core/*.kt classic/DashTest.kt vision/LayoutCommon.kt

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }
fun one(s: String) = s.replace("\n", " ⏎ ")

fun vw(t: String, x0: Int, y0: Int, x1: Int, y1: Int, c: Double = 0.95, p: Int = 0) = VisionLayout.Word(t, x0, y0, x1, y1, c.toFloat(), p)

fun json(ws: List<VisionLayout.Word>): String {
    val blocks = ws.groupBy { it.par }.values.map { pw -> pw.map { W(it.text, it.x0, it.y0, it.x1, it.y1, it.conf.toDouble()) } }
    return answer(*blocks.toTypedArray())
}

class Answers(private val bodies: List<ByteArray>) : HttpTransport {
    var n = 0
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val b = bodies.getOrNull(n) ?: throw java.io.IOException("no more answers")
        n++
        return HttpResponse(200, b, emptyMap())
    }
}

/** A Lens crupload answer: one paragraph per line, boxes as fractions (cx, cy, w, h). */
fun lensAnswer(vararg lines: Pair<String, FloatArray>): ByteArray {
    val w = ProtoWriter()
    w.message(2) {
        message(3) {
            message(1) {
                for ((t, b) in lines) {
                    message(1) {
                        message(2) {
                            val ws = t.split(' ')
                            for ((i, word) in ws.withIndex()) message(1) { string(2, word); if (i < ws.size - 1) string(3, " ") }
                            message(2) { message(1) { float32(1, b[0]); float32(2, b[1]); float32(3, b[2]); float32(4, b[3]) } }
                        }
                        varint(4, 2L)
                    }
                }
            }
        }
    }
    return w.toByteArray()
}

fun main() = runBlocking {
    // ------------------------------------------------------------------ ClassicShape
    check("dashMissing: dash on line 1 only", ClassicShape.dashMissing("- هوليا\nابتعدي يا جوليزار"))
    check("dashMissing: both dashes → no", !ClassicShape.dashMissing("- هوليا\n- ابتعدي يا جوليزار"))
    check("dashMissing: no dash at all → no", !ClassicShape.dashMissing("هوليا. ساعدوني\nماذا حدث؟"))
    check("dashMissing: one line → no", !ClassicShape.dashMissing("- لا أعرف"))
    check("dashMissing: crew names are not subtitle lines", ClassicShape.dashMissing("Selin Aydın\n- هوليا\nابتعدي يا جوليزار\nAli Kaya"))
    check("dashMissing: en dash counts", ClassicShape.dashMissing("– هوليا\nابتعدي"))

    val lost = "- هوليا\nابتعدي يا جوليزار"
    val full = "- هوليا\n- ابتعدي يا جوليزار"
    check("restore: the dash another look read on the same line", ClassicShape.restoreDashes(lost, listOf(full)) == full, one(ClassicShape.restoreDashes(lost, listOf(full))))
    check("restore: «-word» in the other look → «- word»", ClassicShape.restoreDashes(lost, listOf("-هوليا\n-ابتعدي يا جوليزار")) == full)
    check("restore: same line with other tashkeel / punctuation still matches",
        ClassicShape.restoreDashes("- ماذا؟\nلا شيء، عودي إلى البيت الآن", listOf("- ماذا\n- لا شيءٍ عودي إلى البيت الآن")) == "- ماذا؟\n- لا شيء، عودي إلى البيت الآن")
    check("restore: a different line (other letters) gets nothing", ClassicShape.restoreDashes(lost, listOf("- هوليا\n- ابتعد يا جوليزار")) == lost)
    check("restore: nothing read → unchanged", ClassicShape.restoreDashes(lost, listOf(lost, "هوليا\nابتعدي يا جوليزار")) == lost)
    check("restore: never two dashes (a dash at the line's end stays alone)",
        ClassicShape.restoreDashes("- هوليا\nابتعدي يا جوليزار -", listOf(full)) == "- هوليا\nابتعدي يا جوليزار -")
    val wrap = "- لا أعرف ماذا سأفعل\nبعد كل ما حدث"
    check("restore: one speaker wrapped on two lines keeps one dash", ClassicShape.restoreDashes(wrap, listOf(wrap, wrap)) == wrap)
    check("restore: only lines allowed", ClassicShape.restoreDashes(lost, listOf(full), setOf("هوليا")) == lost &&
        ClassicShape.restoreDashes(lost, listOf(full), setOf(ClassicShape.lineKey("ابتعدي يا جوليزار"))) == full)
    check("restore: credits lines untouched", ClassicShape.restoreDashes("Ali Kaya\n$lost", listOf(full)) == "Ali Kaya\n$full")

    // ------------------------------------------------------------------ Google Vision: the focused look
    // geometry of bench/images/14_dialogue_box_credits.jpg (1230×201): box 444..786, credits beside it
    val credits = listOf(
        vw("Görüntü", 92, 75, 180, 91, p = 1), vw("Yönetmeni", 186, 75, 258, 91, p = 1),
        vw("Selin", 68, 103, 160, 133, p = 2), vw("Aydın", 168, 103, 282, 133, p = 2),
        vw("Kurgu", 1035, 75, 1086, 91, p = 5), vw("Ali", 982, 103, 1032, 133, p = 6), vw("Kaya", 1042, 103, 1137, 133, p = 6)
    )
    val line1 = listOf(vw("-", 656, 102, 670, 115, 0.9, 3), vw("هوليا", 560, 89, 644, 124, 0.8, 3))
    val line2 = listOf(vw("-", 755, 151, 769, 164, 0.9, 4), vw("ابتعدي", 635, 138, 743, 172, 0.75, 4), vw("يا", 597, 138, 623, 170, 0.8, 4), vw("جوليزار", 461, 138, 586, 173, 0.78, 4))
    val firstLook = credits + line1 + line2
    check("Vision layout of the first look keeps both dashes", VisionLayout.rebuild(firstLook)?.let { ClassicText.clean(it) } == full,
        one(VisionLayout.rebuild(firstLook) ?: ""))
    val area = VisionLayout.focusArea(firstLook, 1230, 201)
    check("focus look asked (crew names on the subtitle's rows)", area != null, area?.joinToString() ?: "")
    area?.let {
        check("focus area holds the dash of the wider line (769) and stops before «Ali» (982)", it[2] in 769..981, it.joinToString())
        check("…and holds the end of the line on the left (461) without «Aydın» (282)", it[0] in 283..461, it.joinToString())
    }
    // before 2.7.8 the area ended at «ابتعدي» + a third of a line height: x1 ≤ 755, the dash was cut
    check("dashes checked where they stand: both lines", VisionLayout.dashedLines(firstLook) ==
        setOf(ClassicShape.lineKey("هوليا"), ClassicShape.lineKey("ابتعدي يا جوليزار")), VisionLayout.dashedLines(firstLook).toString())
    // a «-» that is the stroke of a Latin name touching the subtitle is not the line's dash
    val phantom = listOf(vw("لماذا؟", 560, 100, 650, 140), vw("-", 662, 112, 672, 122), vw("Ece", 675, 104, 730, 134))
    check("a mark against a Latin name is not a dialogue dash", VisionLayout.dashedLines(phantom).isEmpty(), VisionLayout.dashedLines(phantom).toString())
    check("…nor part of the focus area beyond the subtitle", (VisionLayout.focusArea(phantom + credits, 1230, 201)?.get(2) ?: 0) <= 674,
        VisionLayout.focusArea(phantom + credits, 1230, 201)?.joinToString() ?: "null")

    // the engine: the focused look reads the subtitle surer but without the second dash → the dash comes back
    val focusWords = listOf(
        vw("-", 212, 33, 226, 46, 0.97), vw("هوليا", 116, 20, 200, 55, 0.97),
        vw("ابتعدي", 191, 69, 299, 103, 0.96), vw("يا", 153, 69, 179, 101, 0.97), vw("جوليزار", 17, 69, 142, 104, 0.96)
    )
    val crops = ArrayList<IntArray>()
    val cropper = ImageCropper { im, x0, y0, x1, y1 -> crops.add(intArrayOf(x0, y0, x1, y1)); PreparedImage("CROP".toByteArray(), "image/png", x1 - x0, y1 - y0, im.name) }
    var tr: VisionEngine.Trace? = null
    val vt = Answers(listOf(json(firstLook).toByteArray(), json(focusWords).toByteArray()))
    val v = VisionEngine(vt, "k", "ar", cropper = cropper, trace = { tr = it }).recognize(PreparedImage("FULL".toByteArray(), "image/png", 1230, 201, "x.jpeg"))
    check("Vision engine: two requests", vt.n == 2, "${vt.n}")
    check("Vision engine: focused look kept, its lost dash put back", ClassicText.clean(v) == full, one(v))
    check("Vision report: the restored text is shown", tr?.restored != null && tr?.secondChosen == true, tr?.restored ?: "null")
    // the focused look keeps both dashes → nothing to restore
    val focusFull = focusWords + vw("-", 311, 82, 325, 95, 0.97)
    val vt2 = Answers(listOf(json(firstLook).toByteArray(), json(focusFull).toByteArray()))
    var tr2: VisionEngine.Trace? = null
    val v2 = VisionEngine(vt2, "k", "ar", cropper = cropper, trace = { tr2 = it }).recognize(PreparedImage("FULL".toByteArray(), "image/png", 1230, 201, "x.jpeg"))
    check("Vision engine: focused look with both dashes kept as is", ClassicText.clean(v2) == full && tr2?.restored == null, one(v2))
    // one speaker wrapped: no dash appears on the second line
    val wrapFirst = credits + listOf(
        vw("-", 787, 92, 801, 105, 0.9, 3), vw("لا", 745, 79, 775, 114, 0.7, 3), vw("أعرف", 655, 79, 735, 114, 0.7, 3),
        vw("ماذا", 575, 79, 645, 114, 0.7, 3), vw("سأفعل", 445, 79, 565, 114, 0.7, 3),
        vw("بعد", 650, 130, 710, 165, 0.7, 4), vw("كل", 600, 130, 640, 165, 0.7, 4), vw("ما", 560, 130, 590, 165, 0.7, 4), vw("حدث", 480, 130, 550, 165, 0.7, 4)
    )
    val vt3 = Answers(listOf(json(wrapFirst).toByteArray(), json(wrapFirst.filter { it.par >= 3 }).toByteArray()))
    val v3 = VisionEngine(vt3, "k", "ar", cropper = cropper).recognize(PreparedImage("FULL".toByteArray(), "image/png", 1230, 201, "x.jpeg"))
    check("Vision engine: one speaker's wrapped sentence keeps a single dash", ClassicText.clean(v3) == wrap, one(v3))

    // ------------------------------------------------------------------ Google Lens: extra looks for a dialogue
    val resampler = ImageResampler { im, x0, y0, x1, y1, s -> PreparedImage("VIEW".toByteArray(), "image/png", Math.round((x1 - x0) * s), Math.round((y1 - y0) * s), im.name) }
    val b1 = floatArrayOf(0.500f, 0.530f, 0.090f, 0.170f)   // «- هوليا»
    val b2 = floatArrayOf(0.500f, 0.770f, 0.252f, 0.170f)   // «- ابتعدي يا جوليزار»
    val b2NoDash = floatArrayOf(0.494f, 0.770f, 0.240f, 0.170f)
    val lensLost = lensAnswer("- هوليا" to b1, "ابتعدي يا جوليزار" to b2NoDash)
    val lensFull = lensAnswer("- هوليا" to b1, "- ابتعدي يا جوليزار" to b2)
    var looks = ""
    val lt = Answers(listOf(lensLost, lensFull, lensLost, lensLost))
    val l = LensEngine(lt, "ar", views = resampler, looks = { looks = it }).recognize(PreparedImage("PNG".toByteArray(), "image/png", 1230, 201, "x.png"))
    check("Lens: a dialogue with a dash on one line only gets the extra looks", lt.n == 4, "${lt.n}")
    check("Lens: the dash the enlarged look read comes back", ClassicText.clean(l) == full, one(l))
    check("Lens: the log says so", looks.contains("شرطة حوار"), looks)
    // the whole dialogue read with both dashes: one look only, as before
    val lt2 = Answers(listOf(lensFull))
    val l2 = LensEngine(lt2, "ar", views = resampler).recognize(PreparedImage("PNG".toByteArray(), "image/png", 1230, 201, "x.png"))
    check("Lens: both dashes read → one look", lt2.n == 1 && ClassicText.clean(l2) == full, "${lt2.n} ${one(l2)}")
    // one speaker wrapped: looked at again, nothing added
    val wrapLens = lensAnswer("- لا أعرف ماذا سأفعل" to floatArrayOf(0.5f, 0.48f, 0.30f, 0.17f), "بعد كل ما حدث" to floatArrayOf(0.5f, 0.74f, 0.19f, 0.17f))
    val lt3 = Answers(listOf(wrapLens, wrapLens, wrapLens, wrapLens))
    val l3 = LensEngine(lt3, "ar", views = resampler).recognize(PreparedImage("PNG".toByteArray(), "image/png", 1230, 201, "x.png"))
    check("Lens: one speaker's wrapped sentence → extra looks, unchanged", ClassicText.clean(l3) == wrap, "${lt3.n} ${one(l3)}")
    // without extra looks (setting off) the reading is Lens's own, as before
    val lt4 = Answers(listOf(lensLost))
    val l4 = LensEngine(lt4, "ar", views = null).recognize(PreparedImage("PNG".toByteArray(), "image/png", 1230, 201, "x.png"))
    check("Lens: extra looks off → one look, unchanged", lt4.n == 1 && ClassicText.clean(l4) == lost, one(l4))

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
