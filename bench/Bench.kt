package com.abdulrahman.hardsubocr.bench

import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.Cleaner
import com.abdulrahman.hardsubocr.core.HttpRequest
import com.abdulrahman.hardsubocr.core.HttpResponse
import com.abdulrahman.hardsubocr.core.HttpTransport
import com.abdulrahman.hardsubocr.core.LensProtocol
import com.abdulrahman.hardsubocr.core.ProtoReader
import com.abdulrahman.hardsubocr.core.VisionEngine
import com.abdulrahman.hardsubocr.core.VisionLayout
import com.abdulrahman.hardsubocr.core.allOf
import com.abdulrahman.hardsubocr.core.firstOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Live OCR bench (GitHub Actions only — the build sandbox cannot reach Google): reads the synthetic
 * subtitle crops of bench/images with Google Lens and, when the VISION_API_KEY secret exists, with
 * every Google Cloud Vision variant. Writes a report; never prints a key.
 */
class JvmTransport : HttpTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val b = java.net.http.HttpRequest.newBuilder(URI(request.url)).timeout(Duration.ofMillis(request.timeoutMs))
        for ((k, v) in request.headers) runCatching { b.header(k, v) }
        request.contentType?.let { b.header("Content-Type", it) }
        val body = request.body
        if (request.method == "GET") b.GET()
        else b.method(request.method, if (body != null) java.net.http.HttpRequest.BodyPublishers.ofByteArray(body) else java.net.http.HttpRequest.BodyPublishers.noBody())
        val r = client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        HttpResponse(r.statusCode(), r.body(), r.headers().map().mapValues { it.value.firstOrNull() ?: "" })
    }
}

private class Png(val bytes: ByteArray, val w: Int, val h: Int)

/** Same as the app's classic-OCR path: the picture as PNG (≤ 1500 px). */
private fun toPng(jpeg: ByteArray): Png {
    val img = ImageIO.read(jpeg.inputStream())
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return Png(out.toByteArray(), img.width, img.height)
}

/** The picture as JPEG at [quality] (0–100), same size: what Chrome's Lens overlay uploads (JPEG, quality 40). */
private fun toJpeg(jpeg: ByteArray, quality: Int): Png {
    val src = ImageIO.read(jpeg.inputStream())
    val rgb = java.awt.image.BufferedImage(src.width, src.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param = writer.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { ios -> writer.output = ios; writer.write(null, javax.imageio.IIOImage(rgb, null, null), param) }
    writer.dispose()
    return Png(out.toByteArray(), src.width, src.height)
}

private fun toJpegBytes(img: ByteArray, quality: Int): ByteArray = toJpeg(img, quality).bytes

/** One Lens request, timed (ms of the HTTP exchange only). */
private suspend fun timedLens(http: HttpTransport, img: Png): Pair<ByteArray?, Long> {
    val body = LensProtocol.buildRequest(img.bytes, img.w, img.h, "ar", "US", "America/New_York")
    val t0 = System.nanoTime()
    val r = runCatching {
        http.execute(
            HttpRequest(
                LensProtocol.ENDPOINT,
                headers = mapOf(
                    "X-Goog-Api-Key" to LensProtocol.API_KEY, "User-Agent" to LensProtocol.USER_AGENT,
                    "Accept" to "*/*", "Accept-Language" to "ar,en;q=0.9"
                ),
                body = body, contentType = "application/x-protobuf", timeoutMs = 60_000
            )
        )
    }.getOrNull()
    val ms = (System.nanoTime() - t0) / 1_000_000
    kotlinx.coroutines.delay(300)
    return (if (r != null && r.code == 200) r.body else null) to ms
}

/** The app's ImageResampler, on the JVM (bilinear, like Android's createScaledBitmap(filter = true)). */
object JvmResampler : com.abdulrahman.hardsubocr.core.ImageResampler {
    override fun view(image: com.abdulrahman.hardsubocr.core.PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int, scale: Float): com.abdulrahman.hardsubocr.core.PreparedImage? {
        val p = transform(image.bytes, x0, y0, x1, y1, scale.toDouble())
        // as the app's AndroidResampler since 2.8.0: the look as JPEG (quality 90)
        val j = toJpegBytes(p.bytes, 90)
        return com.abdulrahman.hardsubocr.core.PreparedImage(j, "image/jpeg", p.w, p.h, image.name)
    }
}

/** The app's ImageCropper (Vision's focused second look), on the JVM: the same pixels, cropped. */
object JvmCropper : com.abdulrahman.hardsubocr.core.ImageCropper {
    override fun crop(image: com.abdulrahman.hardsubocr.core.PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int): com.abdulrahman.hardsubocr.core.PreparedImage? {
        val p = transform(image.bytes, x0, y0, x1, y1, 1.0)
        return com.abdulrahman.hardsubocr.core.PreparedImage(p.bytes, "image/png", p.w, p.h, image.name)
    }
}

private fun transform(src: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int, scale: Double): Png {
    val img = ImageIO.read(src.inputStream())
    val l = x0.coerceIn(0, img.width - 1); val t = y0.coerceIn(0, img.height - 1)
    val r = x1.coerceIn(l + 1, img.width); val b = y1.coerceIn(t + 1, img.height)
    val part = img.getSubimage(l, t, r - l, b - t)
    val w = (part.width * scale).toInt(); val h = (part.height * scale).toInt()
    val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(part, 0, 0, w, h, null); g.dispose()
    val bos = ByteArrayOutputStream(); ImageIO.write(out, "png", bos)
    return Png(bos.toByteArray(), w, h)
}

private fun one(s: String) = s.replace("\n", " ⏎ ")

/**
 * The app's picture for Gemini/OpenRouter (AndroidImagePreparer): v2.6 enhancement, the band with the
 * text-safe edges for an image folder (no band for the video mode's area, "_roi_" frames), and since
 * 2.9.0 framed on the subtitle itself ([frame]).
 */
private fun aiPicture(jpeg: ByteArray, name: String, frame: Boolean): com.abdulrahman.hardsubocr.core.PreparedImage {
    val img = ImageIO.read(jpeg.inputStream())
    val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    val out = com.abdulrahman.hardsubocr.core.ImageV26.prepare(
        px, img.width, img.height,
        com.abdulrahman.hardsubocr.core.ImageV26.Options(cropBand = "_roi_" !in name, keepText = true, frame = frame)
    )
    return com.abdulrahman.hardsubocr.core.PreparedImage(com.abdulrahman.hardsubocr.core.PngGray.encode(out), "image/png", out.width, out.height, name)
}

/** The AI instructions of 2.8.0, to compare with today's (Prompts.SUBTITLE_PROMPT). */
private const val PROMPT_280: String =
        "You are performing ULTRA-PRECISE Optical Character Recognition (OCR) on Arabic subtitle images.\n" +
        "This is PURE PIXEL EXTRACTION - you are a high-precision scanner, NOT an editor or interpreter.\n" +
        "\n" +
        "CRITICAL RULES:\n" +
        "- Extract only the main Arabic subtitle dialogue.\n" +
        "- Ignore watermarks, logos, channel names, static overlays, and all non-Arabic text. A website, channel or watermark name is never subtitle dialogue, even when it is written in Arabic or placed near the subtitle.\n" +
        "- Read the base Arabic letters first. Never invent or repair letters.\n" +
        "- Write every word exactly as many times as it appears in the image. Dialogue often repeats a word or a phrase: count the repetitions in the image and never add or drop one.\n" +
        "- Preserve punctuation, numbers, brackets, spacing, and line breaks exactly when visible.\n" +
        "- Arabic diacritics are OPTIONAL, not required.\n" +
        "- Add a diacritic only if it is unmistakably visible in the pixels and clearly attached to a specific letter.\n" +
        "- If a diacritic is faint, blurred, stylized, tiny, partially cut, or uncertain, OMIT it.\n" +
        "- Never add grammatical endings from context.\n" +
        "- Never split a word into separate letters.\n" +
        "- Never add tatweel/kashida (ـ).\n" +
        "- If no Arabic subtitle dialogue exists, output an empty string \"\".\n" +
        "\n" +
        "Output only the subtitle text with no commentary.\n" +
        ""

/** One way of reading with an AI engine: the picture (framed or not), the instructions, the thinking level. */
private class AiVariant(val name: String, val frame: Boolean, val prompt: String?, val thinking: com.abdulrahman.hardsubocr.core.ThinkingLevel)

private val AI_VARIANTS = listOf(
    AiVariant("2.8.0", false, PROMPT_280, com.abdulrahman.hardsubocr.core.ThinkingLevel.MINIMAL),
    AiVariant("2.9.0", true, null, com.abdulrahman.hardsubocr.core.ThinkingLevel.MINIMAL),
    AiVariant("2.9.0 thinking low", true, null, com.abdulrahman.hardsubocr.core.ThinkingLevel.LOW),
    AiVariant("frame only", true, PROMPT_280, com.abdulrahman.hardsubocr.core.ThinkingLevel.MINIMAL),
    AiVariant("prompt only", false, null, com.abdulrahman.hardsubocr.core.ThinkingLevel.MINIMAL),
)

/** One-line, no tashkeel — the comparison form (the app's classic cleaning). */
private fun norm(s: String) = Cleaner.clean(s, CleanMode.PRO)

private fun lev(a: String, b: String): Int {
    val d = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = d[0]; d[0] = i
        for (j in 1..b.length) {
            val t = d[j]
            d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = t
        }
    }
    return d[b.length]
}

private class Score { var exact = 0; var errors = 0; var chars = 0; var n = 0 }

/** Lens words, raw: text + separator per word, line by line (what the app joins). */
private fun lensRaw(body: ByteArray): String {
    val root = ProtoReader.parse(body)
    val text = root.firstOf(2)?.asMessage()?.firstOf(3)?.asMessage() ?: return "(no text)"
    val layout = text.firstOf(1)?.asMessage() ?: return "(no layout)"
    val sb = StringBuilder()
    for ((pi, pf) in layout.allOf(1).withIndex()) {
        val p = pf.asMessage()
        val dir = p.firstOf(4)?.number
        val lang = p.firstOf(5)?.asString()
        sb.append("    p").append(pi).append(" dir=").append(dir).append(" lang=").append(lang).append('\n')
        for (lf in p.allOf(2)) {
            sb.append("      line:")
            for (wf in lf.asMessage().allOf(1)) {
                val w = wf.asMessage()
                sb.append(" «").append(w.firstOf(2)?.asString() ?: "").append("»")
                val sep = w.firstOf(3)?.asString()
                if (!sep.isNullOrEmpty()) sb.append("+sep«").append(sep).append("»")
                // word box (field 4 → geometry → bounding box: centre x, centre y, width, height)
                val bb = w.firstOf(4)?.asMessage()?.firstOf(1)?.asMessage()
                if (bb == null) sb.append("@-")
                else sb.append("@").append((1..4).joinToString(",") { n ->
                    "%.3f".format(java.util.Locale.US, bb.firstOf(n)?.takeIf { it.wire == 5 }?.asFloat() ?: 0f)
                })
            }
            sb.append('\n')
        }
    }
    return sb.toString()
}

private suspend fun lens(http: HttpTransport, png: Png): LensProtocol.Result? = lensBody(http, png)?.let { LensProtocol.parseResponse(it) }

/** The raw Lens answer (protobuf), or null. */
private suspend fun lensBody(http: HttpTransport, png: Png): ByteArray? {
    val body = LensProtocol.buildRequest(png.bytes, png.w, png.h, "ar", "US", "America/New_York")
    val r = runCatching {
        http.execute(
            HttpRequest(
                LensProtocol.ENDPOINT,
                headers = mapOf(
                    "X-Goog-Api-Key" to LensProtocol.API_KEY, "User-Agent" to LensProtocol.USER_AGENT,
                    "Accept" to "*/*", "Accept-Language" to "ar,en;q=0.9"
                ),
                body = body, contentType = "application/x-protobuf", timeoutMs = 60_000
            )
        )
    }.getOrNull() ?: return null
    if (r.code != 200) return null
    kotlinx.coroutines.delay(400)
    return r.body
}

fun main(args: Array<String>) = runBlocking {
    val dir = File(args[0])
    val outFile = File(args[1])
    val http = JvmTransport()
    val visionKey = System.getenv("VISION_API_KEY")?.trim().orEmpty()
    // AI engines: only when the secrets exist and the push asked for it ([bench-ai]) — they cost money
    val geminiKey = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
    val openRouterKey = System.getenv("OPENROUTER_API_KEY")?.trim().orEmpty()
    val geminiModel = System.getenv("BENCH_GEMINI_MODEL")?.trim()?.ifEmpty { null } ?: com.abdulrahman.hardsubocr.core.GeminiEngine.DEFAULT_MODEL
    val openRouterModel = System.getenv("BENCH_OPENROUTER_MODEL")?.trim()?.ifEmpty { null } ?: ("google/" + geminiModel)
    val gemini = if (geminiKey.isBlank()) null else com.abdulrahman.hardsubocr.core.GeminiEngine(http, geminiKey, geminiModel)
    val openRouter = if (openRouterKey.isBlank()) null else com.abdulrahman.hardsubocr.core.OpenRouterEngine(http, openRouterKey, openRouterModel)
    /** "[ai-only]" in the commit message: only the AI engines, on the frames of their faults (and 01–09, 25). */
    val aiOnly = System.getenv("BENCH_AI_ONLY") == "1" && (geminiKey.isNotBlank() || openRouterKey.isNotBlank())
    val cases = Json.parseToJsonElement(File(dir, "truth.json").readText()).jsonArray
    val sb = StringBuilder()
    sb.append("# OCR bench — commit ").append(System.getenv("GITHUB_SHA") ?: "local").append(" — ").append(java.time.Instant.now()).append("\n\n")
    sb.append("Vision: ").append(if (visionKey.isBlank()) "no VISION_API_KEY secret — Lens only" else "key present (not shown)").append("\n")
    sb.append("AI: ").append(listOfNotNull(gemini?.let { "Gemini $geminiModel" }, openRouter?.let { "OpenRouter $openRouterModel" }).ifEmpty { listOf("not run (needs the secrets and [bench-ai] in the commit message)") }.joinToString(", "))
        .append(" — versions of the reading: ").append(AI_VARIANTS.joinToString(" · ") { it.name }).append(" (each twice)\n\n")
    val scores = LinkedHashMap<String, Score>()
    class Fmt { var n = 0; var failed = 0; var bytes = 0L; val times = ArrayList<Long>() }
    val formats = LinkedHashMap<String, Fmt>()
    val shapes = LinkedHashMap<String, IntArray>()
    /** AI variants: [line left out, «خوليا», dot at a line start, letters wrong, failed] per run. */
    val aiFaults = LinkedHashMap<String, IntArray>()
    val aiGate = kotlinx.coroutines.sync.Semaphore(4)
    /** The subtitle's shape: lines, dashes and punctuation exactly (the app's Lens/Vision cleaning). */
    fun shape(name: String, got: String, truth: String): String {
        val s = shapes.getOrPut(name) { IntArray(2) }
        val ok = com.abdulrahman.hardsubocr.core.ClassicText.clean(got) == com.abdulrahman.hardsubocr.core.ClassicText.clean(truth)
        s[1]++; if (ok) s[0]++
        return if (ok) "shape ✓" else "shape ✗ «" + one(com.abdulrahman.hardsubocr.core.ClassicText.clean(got)) + "»"
    }
    fun score(name: String, got: String, truth: String): String {
        val s = scores.getOrPut(name) { Score() }
        val g = norm(got); val t = norm(truth)
        val e = lev(g, t)
        s.n++; s.chars += t.length; s.errors += e; if (e == 0) s.exact++
        return if (e == 0) "✓" else "✗ ($e)"
    }

    for (c in cases) {
        val o = c.jsonObject
        val file = o["file"]!!.jsonPrimitive.content
        val truth = o["truth"]!!.jsonPrimitive.content
        val png = toPng(File(dir, file).readBytes())
        sb.append("## ").append(file).append(" — ").append(o["note"]!!.jsonPrimitive.content).append('\n')
        sb.append("truth: «").append(one(truth)).append("»\n\n")

        if (aiOnly && !file.contains("_roi_") && !file.startsWith("0") && !file.startsWith("25")) { sb.append('\n'); continue }
        if (!aiOnly) {
        // ---------------------------------------------------------------- Google Lens: one look (raw)
        val body = lensBody(http, png)
        val res = body?.let { LensProtocol.parseResponse(it) }
        if (res == null) sb.append("- Lens one look: failed\n") else {
            val reading = res.readingText(png.w, png.h)
            sb.append("- Lens one look ").append(score("lens one look", reading, truth)).append(' ').append(shape("lens one look", reading, truth))
                .append(" «").append(one(norm(reading))).append("» strays ").append(res.strays(png.w, png.h)).append('\n')
            sb.append("    full «").append(one(res.fullText)).append("»\n")
            sb.append("    lines ").append(res.describe()).append('\n')
            if (res.repeats.isNotEmpty()) sb.append("    read twice in the same place, left out: ").append(res.repeats.joinToString(" ") { "«$it»" }).append('\n')
            sb.append("    words (Lens's own lines, word by word with its box)\n").append(lensRaw(body))
        }
        // ---------------------------------------------------------------- Google Lens: upload format (speed vs accuracy)
        run {
            val src = File(dir, file).readBytes()
            val line = StringBuilder("- Lens format:")
            for ((fmt, img) in listOf("png" to png, "jpeg95" to toJpeg(src, 95), "jpeg85" to toJpeg(src, 85), "jpeg70" to toJpeg(src, 70), "jpeg40" to toJpeg(src, 40))) {
                val (b, ms) = timedLens(http, img)
                val rr = b?.let { LensProtocol.parseResponse(it) }
                val text = rr?.readingText(img.w, img.h)
                val f = formats.getOrPut(fmt) { Fmt() }
                f.bytes += img.bytes.size; f.times.add(ms); f.n++
                if (text == null) { f.failed++; line.append(" $fmt ✖"); continue }
                line.append(" $fmt ").append(score("lens format $fmt", text, truth)).append(" ${img.bytes.size / 1024}KB ${ms}ms;")
                shape("lens format $fmt", text, truth)
            }
            sb.append(line).append('\n')
        }
        // ---------------------------------------------------------------- Google Lens: the app (multi-view)
        run {
            var looks = ""
            val engine = com.abdulrahman.hardsubocr.core.LensEngine(http, "ar", "US", views = JvmResampler, looks = { looks = it })
            // as the app since 2.8.0: a JPEG that fits goes to Lens as it is
            val src = File(dir, file).readBytes()
            val img = com.abdulrahman.hardsubocr.core.PreparedImage(src, "image/jpeg", png.w, png.h, file)
            val t = runCatching { engine.recognize(img) }
            val text = t.getOrNull()
            if (text == null) sb.append("- Lens app: ").append(t.exceptionOrNull()?.message).append('\n')
            else {
                sb.append("- Lens app ").append(score("lens app (multi-view)", text, truth)).append(' ').append(shape("lens app (multi-view)", text, truth))
                    .append(" «").append(one(norm(text))).append("»\n")
                if (looks.isNotEmpty()) sb.append("    looks: ").append(looks).append('\n')
            }
        }

        }
        // ---------------------------------------------------------------- Gemini / OpenRouter: versions of the reading
        // the same picture and instructions as the app, each variant twice (a model is not always the same)
        for ((label, key, model) in listOf(Triple("gemini", geminiKey, geminiModel), Triple("openrouter", openRouterKey, openRouterModel))) {
            if (key.isBlank()) continue
            val src = File(dir, file).readBytes()
            val pics = mapOf(false to aiPicture(src, file, false), true to aiPicture(src, file, true))
            sb.append("- ").append(label).append(" pictures: whole ").append(pics[false]!!.width).append('×').append(pics[false]!!.height)
                .append(", framed ").append(pics[true]!!.width).append('×').append(pics[true]!!.height).append('\n')
            val runs = kotlinx.coroutines.coroutineScope {
                AI_VARIANTS.flatMap { v -> (1..2).map { n -> v to n } }.map { (v, n) ->
                    async(Dispatchers.IO) {
                        aiGate.acquire()
                        try {
                            val engine = if (label == "gemini") com.abdulrahman.hardsubocr.core.GeminiEngine(http, key, model, v.thinking, promptOverride = v.prompt)
                            else com.abdulrahman.hardsubocr.core.OpenRouterEngine(http, key, model, v.thinking, promptOverride = v.prompt)
                            Triple(v, n, runCatching { engine.recognize(pics[v.frame]!!) })
                        } finally { aiGate.release() }
                    }
                }.map { it.await() }
            }
            for ((v, n, t) in runs) {
                val raw = t.getOrNull()
                val name = "$label ${v.name}"
                if (raw == null) {
                    sb.append("  - ").append(name).append(" #").append(n).append(": ")
                        .append((t.exceptionOrNull()?.message ?: "").replace(geminiKey.ifEmpty { "\u0000" }, "***").replace(openRouterKey.ifEmpty { "\u0000" }, "***").take(200)).append('\n')
                    aiFaults.getOrPut(name) { IntArray(5) }[4]++
                    continue
                }
                val text = Cleaner.clean(raw, CleanMode.EXACT)
                val f = aiFaults.getOrPut(name) { IntArray(5) }
                val truthLines = truth.split('\n').size
                if (text.split('\n').size < truthLines) f[0]++                                   // a line left out
                if ("خوليا" in text && "خوليا" !in truth) f[1]++                                   // a name changed
                if (text.lines().any { it.trimStart().firstOrNull() in setOf('.', '،', ',') }) f[2]++ // punctuation added at a line start
                if (norm(text) != norm(truth)) f[3]++                                              // any letter wrong
                sb.append("  - ").append(name).append(" #").append(n).append(' ').append(score(name, text, truth)).append(' ')
                    .append(shape("$name (with tashkeel)", text, truth)).append(" «").append(one(text)).append("»\n")
            }
        }

        // ---------------------------------------------------------------- Google Cloud Vision: the app
        if (visionKey.isNotBlank() && !aiOnly) {
            var trace: com.abdulrahman.hardsubocr.core.VisionEngine.Trace? = null
            val engine = VisionEngine(http, visionKey, "ar", document = true, cropper = JvmCropper, trace = { trace = it })
            // as the app since 2.8.0: a JPEG that fits goes to Lens as it is
            val src = File(dir, file).readBytes()
            val img = com.abdulrahman.hardsubocr.core.PreparedImage(src, "image/jpeg", png.w, png.h, file)
            val t = runCatching { engine.recognize(img) }
            val text = t.getOrNull()
            if (text == null) sb.append("- Vision app: ").append((t.exceptionOrNull()?.message ?: "").replace(visionKey, "***")).append('\n')
            else {
                sb.append("- Vision app ").append(score("vision app (focus look)", text, truth)).append(' ').append(shape("vision app (focus look)", text, truth))
                    .append(" «").append(one(com.abdulrahman.hardsubocr.core.ClassicText.clean(text))).append("»\n")
                trace?.second?.let { s ->
                    sb.append("    focus look «").append(one(s)).append("» → ").append(if (trace?.secondChosen == true) "focus" else "first").append('\n')
                }
            }
        }

        // ---------------------------------------------------------------- Google Cloud Vision
        if (visionKey.isNotBlank() && !aiOnly) {
            for (feature in listOf("DOCUMENT_TEXT_DETECTION", "TEXT_DETECTION"))
                for (model in listOf("", "builtin/latest", "builtin/weekly"))
                    for (hints in listOf(listOf("ar"), listOf("ar", "en"))) {
                        val name = "vision ${if (feature.startsWith("DOC")) "DOC" else "TEXT"} ${model.ifEmpty { "stable" }} ${hints.joinToString("+")}"
                        val req = buildJsonObject {
                            putJsonArray("requests") {
                                addJsonObject {
                                    putJsonObject("image") { put("content", Base64.getEncoder().encodeToString(png.bytes)) }
                                    putJsonArray("features") {
                                        addJsonObject { put("type", feature); if (model.isNotEmpty()) put("model", model) }
                                    }
                                    putJsonObject("imageContext") {
                                        putJsonArray("languageHints") { hints.forEach { add(JsonPrimitive(it)) } }
                                        if (feature == "TEXT_DETECTION") putJsonObject("textDetectionParams") { put("enableTextDetectionConfidenceScore", true) }
                                    }
                                }
                            }
                        }.toString()
                        val r = runCatching {
                            http.execute(
                                HttpRequest(
                                    VisionEngine.ENDPOINT, headers = mapOf("x-goog-api-key" to visionKey),
                                    body = req.toByteArray(), contentType = "application/json; charset=utf-8", timeoutMs = 60_000
                                )
                            )
                        }.getOrNull()
                        if (r == null || r.code != 200) {
                            sb.append("- ").append(name).append(": HTTP ").append(r?.code ?: -1).append(' ')
                                .append((r?.text ?: "").replace(visionKey, "***").replace(Regex("\\s+"), " ").take(300)).append('\n')
                            continue
                        }
                        val a = runCatching { VisionEngine.parseAnswer(r.text) }
                        val ans = a.getOrNull()
                        if (ans == null) {
                            sb.append("- ").append(name).append(": ").append(a.exceptionOrNull()?.message).append('\n'); continue
                        }
                        val text = VisionEngine.textOf(ans)
                        sb.append("- ").append(name).append(' ').append(score(name, text, truth)).append(' ').append(shape(name, text, truth))
                            .append(" app «").append(one(norm(text))).append("» conf ")
                            .append("%.2f".format(java.util.Locale.US, VisionLayout.confidence(ans.words)))
                            .append(" | vision «").append(one(ans.full)).append("»\n")
                        sb.append("    words ").append(VisionLayout.describe(ans.words)).append('\n')
                    }
        }
        sb.append('\n')
    }

    if (formats.isNotEmpty()) {
        sb.append("## Lens upload format (same picture, same size; one look each)\n\n| format | avg size | median ms | p90 ms | failed |\n|---|---|---|---|---|\n")
        for ((k, f) in formats) {
            val t = f.times.sorted()
            sb.append("| $k | ${f.bytes / maxOf(1, f.n) / 1024} KB | ${t.getOrElse(t.size / 2) { 0 }} | ${t.getOrElse((t.size * 9) / 10) { 0 }} | ${f.failed} |\n")
        }
        sb.append('\n')
    }
    if (aiFaults.isNotEmpty()) {
        sb.append("## AI faults (runs)\n\n| variant | line left out | «خوليا» | dot at a line start | letters wrong | failed |\n|---|---|---|---|---|---|\n")
        for ((k, f) in aiFaults) sb.append("| $k | ${f[0]} | ${f[1]} | ${f[2]} | ${f[3]} | ${f[4]} |\n")
        sb.append('\n')
    }
    sb.append("## Summary (after the app's classic cleaning; errors = edit distance to the truth)\n\n")
    sb.append("| engine | exact | char errors | CER |\n|---|---|---|---|\n")
    for ((k, s) in scores) {
        sb.append("| ").append(k).append(" | ").append(s.exact).append('/').append(s.n).append(" | ")
            .append(s.errors).append(" | ").append("%.1f%%".format(java.util.Locale.US, 100.0 * s.errors / maxOf(1, s.chars))).append(" |\n")
    }
    sb.append("\n## Shape kept (lines, dialogue dashes, punctuation — after the app's Lens/Vision cleaning)\n\n| engine | exact shape |\n|---|---|\n")
    for ((k, v) in shapes) sb.append("| ").append(k).append(" | ").append(v[0]).append('/').append(v[1]).append(" |\n")
    outFile.parentFile?.mkdirs()
    outFile.writeText(sb.toString())
    println(sb)
}
