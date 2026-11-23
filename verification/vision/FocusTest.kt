import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

fun vw(t: String, x0: Int, y0: Int, x1: Int, y1: Int, c: Double = 0.95, p: Int = 0) = VisionLayout.Word(t, x0, y0, x1, y1, c.toFloat(), p)

/** Vision answer JSON from words (each word its own paragraph p). */
fun json(ws: List<VisionLayout.Word>): String {
    val blocks = ws.groupBy { it.par }.values.map { pw -> pw.map { W(it.text, it.x0, it.y0, it.x1, it.y1, it.conf.toDouble()) } }
    return answer(*blocks.toTypedArray())
}

class TwoAnswers(val a: String, val b: String, val failSecond: Int = 0) : HttpTransport {
    val bodies = ArrayList<String>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        bodies.add(request.body!!.toString(Charsets.UTF_8))
        if (bodies.size == 2 && failSecond != 0) return HttpResponse(failSecond, """{"error":{"code":$failSecond,"message":"x"}}""".toByteArray(), emptyMap())
        return HttpResponse(200, (if (bodies.size == 1) a else b).toByteArray(), emptyMap())
    }
}

fun main() {
    // ---- your real frames: where the second look would read (Vision-like word boxes measured on the crops)
    // «حبيبي، هل أنت بخير؟» between «Mustafa Gümüş» and «Beste Bayoğlu» (1230x201)
    val habibi = listOf(
        vw("Saç", 178, 75, 222, 92, p = 1), vw("Tasarım", 228, 75, 286, 92, p = 1),
        vw("Mustafa", 113, 105, 228, 138, p = 2), vw("Gümüş", 236, 105, 352, 138, p = 2),
        vw("بيبي،", 707, 125, 836, 169, 0.62, p = 3), vw("هل", 631, 109, 683, 154, p = 3), vw("أنت", 533, 100, 609, 144, p = 3), vw("بخير؟", 393, 104, 507, 156, p = 3),
        vw("Makyaj", 943, 75, 1010, 92, p = 4), vw("Tasarım", 1016, 75, 1088, 92, p = 4),
        vw("Beste", 907, 105, 1000, 140, p = 5), vw("Bayoğlu", 1008, 105, 1120, 140, p = 5)
    )
    val a1 = VisionLayout.focusArea(habibi, 1230, 201)
    check("«حبيبي» frame: second look asked (crew names on the subtitle's row)", a1 != null, a1?.joinToString() ?: "")
    a1?.let { check("…area holds the whole subtitle (393–836) and no crew name (≤352, ≥907)", it[0] in 353..393 && it[2] in 836..906 && it[1] <= 100 && it[3] >= 169, it.joinToString()) }
    // «أبي» inside the box between «Dan» and «ineş», «Yapım Koordinatörü» above
    val abi = listOf(
        vw("Yapım", 525, 75, 597, 91, p = 1), vw("Koordinatörü", 609, 75, 699, 91, p = 1),
        vw("Dan", 513, 104, 573, 130, p = 2), vw("أبي", 585, 100, 648, 169, 0.93, p = 3), vw("ineş", 659, 107, 719, 137, p = 4),
        vw("Gökhan", 112, 104, 212, 130, p = 5), vw("Yılmaz", 219, 104, 349, 130, p = 5)
    )
    val a2 = VisionLayout.focusArea(abi, 1230, 201)
    check("«أبي» frame: second look asked", a2 != null, a2?.joinToString() ?: "")
    a2?.let { check("…area = the box only: right of «Dan» (573), left of «ineş» (659), under «Koordinatörü» (91)", it[0] in 574..585 && it[2] in 648..658 && it[1] in 92..100 && it[3] >= 169, it.joinToString()) }
    // crops of the real frames, to look at
    for ((f, a) in listOf("/mnt/user-data/uploads/eb00bca5-image.jpg" to a1, "/mnt/user-data/uploads/ce77b849-image.jpg" to a2)) {
        if (a == null || !File(f).exists()) continue
        val img = ImageIO.read(File(f))
        ImageIO.write(img.getSubimage(a[0], a[1], a[2] - a[0], a[3] - a[1]), "png", File("/tmp/real/focus_" + File(f).nameWithoutExtension + ".png"))
    }
    // subtitle alone, read sure → one look only
    val alone = listOf(vw("حبيبي،", 707, 100, 836, 169), vw("هل", 631, 109, 683, 154), vw("أنت", 533, 100, 609, 144), vw("بخير؟", 393, 104, 507, 156))
    check("subtitle alone, read sure → no second request", VisionLayout.focusArea(alone, 1230, 201) == null)
    check("…also when Vision gives no confidence", VisionLayout.focusArea(alone.map { vw(it.text, it.x0, it.y0, it.x1, it.y1, 0.0) }, 1230, 201) == null)
    check("subtitle alone but unsure → second look", VisionLayout.focusArea(alone.mapIndexed { i, w -> vw(w.text, w.x0, w.y0, w.x1, w.y1, if (i == 0) 0.4 else 0.95) }, 1230, 201) != null)
    check("crew names on other rows only (title line above, far) → no second request",
        VisionLayout.focusArea(alone + listOf(vw("Makyaj", 943, 20, 1010, 40, p = 9)), 1230, 201) == null)

    // ---- which reading is kept
    val firstHabibi = habibi
    val secondHabibi = listOf(vw("حبيبي،", 329, 36, 458, 80, 0.96), vw("هل", 253, 20, 305, 65), vw("أنت", 155, 11, 231, 55), vw("بخير؟", 15, 15, 129, 67))
    check("focused «حبيبي، …» (more letters, surer) is kept", VisionLayout.secondIsBetter(firstHabibi, secondHabibi))
    check("focused «بيبي، …» again (same) → first stays", !VisionLayout.secondIsBetter(firstHabibi, listOf(vw("بيبي،", 329, 36, 458, 80, 0.62), vw("هل", 253, 20, 305, 65), vw("أنت", 155, 11, 231, 55), vw("بخير؟", 15, 15, 129, 67))))
    val noisy = listOf(vw("انا", 659, 105, 700, 137, 0.41, p = 4), vw("أبي", 585, 100, 648, 169, 0.93, p = 3), vw("Dan", 513, 104, 573, 130, p = 2), vw("ineş", 705, 107, 750, 137, p = 4))
    check("«انا» made of a crew name's letters is noise: focused «أبي» kept", VisionLayout.secondIsBetter(noisy, listOf(vw("أبي", 10, 7, 73, 76, 0.94))))
    check("focused look that lost part of the subtitle is refused", !VisionLayout.secondIsBetter(alone, listOf(vw("حبيبي،", 10, 7, 139, 76, 0.99))))
    check("focused look with nothing Arabic is refused", !VisionLayout.secondIsBetter(alone, listOf(vw("Beste", 10, 7, 100, 40))))

    // ---- the engine: one or two requests, the better reading, errors of the second look
    val cropped = ArrayList<IntArray>()
    val cropper = ImageCropper { im, x0, y0, x1, y1 -> cropped.add(intArrayOf(x0, y0, x1, y1)); PreparedImage("CROP".toByteArray(), "image/png", x1 - x0, y1 - y0, im.name) }
    val img = PreparedImage("FULL".toByteArray(), "image/png", 1230, 201, "0_00_52_300__0_00_54_016.jpeg")
    val t1 = TwoAnswers(json(firstHabibi), json(secondHabibi))
    val r1 = runBlocking { VisionEngine(t1, "k", "ar", cropper = cropper).recognize(img) }
    check("engine: two requests, second one with the cropped picture", t1.bodies.size == 2 && t1.bodies[1].contains(java.util.Base64.getEncoder().encodeToString("CROP".toByteArray())), "${t1.bodies.size}")
    check("engine: «حبيبي، هل أنت بخير؟» returned", Cleaner.clean(r1, CleanMode.PRO) == "حبيبي، هل أنت بخير؟", r1.replace("\n", " ⏎ "))
    val t2 = TwoAnswers(json(alone), "{}")
    val r2 = runBlocking { VisionEngine(t2, "k", "ar", cropper = cropper).recognize(img) }
    check("engine: subtitle alone → one request, same text as before", t2.bodies.size == 1 && r2 == "حبيبي، هل أنت بخير؟", r2)
    val t3 = TwoAnswers(json(firstHabibi), "", failSecond = 429)
    val r3 = runBlocking { VisionEngine(t3, "k", "ar", cropper = cropper).recognize(img) }
    check("engine: second look refused (429) → first reading, no error", Cleaner.clean(r3, CleanMode.PRO) == "بيبي، هل أنت بخير؟", r3.replace("\n", " ⏎ "))
    val t4 = TwoAnswers(json(firstHabibi), "", failSecond = 403)
    val fatal = try { runBlocking { VisionEngine(t4, "k", "ar", cropper = cropper).recognize(img) }; false } catch (e: OcrError.Fatal) { true }
    check("engine: key refused on the second look → stops like any refused key", fatal)
    val t5 = TwoAnswers(json(firstHabibi), json(secondHabibi))
    runBlocking { VisionEngine(t5, "k", "ar").recognize(img) }
    check("engine without a cropper (key test) → one request", t5.bodies.size == 1)
    check("cache id new", VisionEngine(t5, "k", "ar").cacheId == "vision:ar:doc:stable:c$CLASSIC_OCR_VERSION")
    // the Vision report: every image read, Vision's own text, the second look
    val traces = ArrayList<VisionEngine.Trace>()
    val t6 = TwoAnswers(json(firstHabibi), json(secondHabibi))
    runBlocking { VisionEngine(t6, "k", "ar", cropper = cropper, trace = { traces.add(it) }).recognize(img) }
    val t7 = TwoAnswers(json(alone), "{}")
    runBlocking { VisionEngine(t7, "k", "ar", cropper = cropper, trace = { traces.add(it) }).recognize(PreparedImage("FULL".toByteArray(), "image/png", 1230, 201, "0_00_55_000__0_00_56_000.jpeg")) }
    check("trace for every image read", traces.size == 2 && traces[0].second != null && traces[0].secondChosen && traces[1].second == null, traces.map { it.name }.toString())
    val rep = VisionReport.build("ep1", true, "ar", traces, 3)
    check("report: Vision's own text, the text, the second look, times", rep.contains("00:00:52,300 → 00:00:54,016") && rep.contains("الترجمة وحدها: «حبيبي، هل أنت بخير؟» ← اختيرت") && rep.contains("من ذاكرة الاستئناف (لم تُرسل): 3") && rep.contains("Vision: «"), rep.lines().take(8).joinToString(" / "))
    java.io.File("/tmp/real/vision_report_sample.txt").writeText(rep)
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
