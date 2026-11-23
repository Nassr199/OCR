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

/** The app's ImageResampler, on the JVM (bilinear, like Android's createScaledBitmap(filter = true)). */
object JvmResampler : com.abdulrahman.hardsubocr.core.ImageResampler {
    override fun view(image: com.abdulrahman.hardsubocr.core.PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int, scale: Float): com.abdulrahman.hardsubocr.core.PreparedImage? {
        val p = transform(image.bytes, x0, y0, x1, y1, scale.toDouble())
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
            }
            sb.append('\n')
        }
    }
    return sb.toString()
}

private suspend fun lens(http: HttpTransport, png: Png): LensProtocol.Result? {
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
    return LensProtocol.parseResponse(r.body)
}

fun main(args: Array<String>) = runBlocking {
    val dir = File(args[0])
    val outFile = File(args[1])
    val http = JvmTransport()
    val visionKey = System.getenv("VISION_API_KEY")?.trim().orEmpty()
    val cases = Json.parseToJsonElement(File(dir, "truth.json").readText()).jsonArray
    val sb = StringBuilder()
    sb.append("# OCR bench — commit ").append(System.getenv("GITHUB_SHA") ?: "local").append(" — ").append(java.time.Instant.now()).append("\n\n")
    sb.append("Vision: ").append(if (visionKey.isBlank()) "no VISION_API_KEY secret — Lens only" else "key present (not shown)").append("\n\n")
    val scores = LinkedHashMap<String, Score>()
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

        // ---------------------------------------------------------------- Google Lens: one look (raw)
        val res = lens(http, png)
        if (res == null) sb.append("- Lens one look: failed\n") else {
            val reading = res.readingText(png.w, png.h)
            sb.append("- Lens one look ").append(score("lens one look", reading, truth))
                .append(" «").append(one(norm(reading))).append("» strays ").append(res.strays).append('\n')
            sb.append("    full «").append(one(res.fullText)).append("»\n")
            sb.append("    lines ").append(res.describe()).append('\n')
        }
        // ---------------------------------------------------------------- Google Lens: the app (multi-view)
        run {
            var looks = ""
            val engine = com.abdulrahman.hardsubocr.core.LensEngine(http, "ar", "US", views = JvmResampler, looks = { looks = it })
            val img = com.abdulrahman.hardsubocr.core.PreparedImage(png.bytes, "image/png", png.w, png.h, file)
            val t = runCatching { engine.recognize(img) }
            val text = t.getOrNull()
            if (text == null) sb.append("- Lens app: ").append(t.exceptionOrNull()?.message).append('\n')
            else {
                sb.append("- Lens app ").append(score("lens app (multi-view)", text, truth)).append(" «").append(one(norm(text))).append("»\n")
                if (looks.isNotEmpty()) sb.append("    looks: ").append(looks).append('\n')
            }
        }

        // ---------------------------------------------------------------- Google Cloud Vision
        if (visionKey.isNotBlank()) {
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
                        sb.append("- ").append(name).append(' ').append(score(name, text, truth))
                            .append(" app «").append(one(norm(text))).append("» conf ")
                            .append("%.2f".format(java.util.Locale.US, VisionLayout.confidence(ans.words)))
                            .append(" | vision «").append(one(ans.full)).append("»\n")
                        sb.append("    words ").append(VisionLayout.describe(ans.words)).append('\n')
                    }
        }
        sb.append('\n')
    }

    sb.append("## Summary (after the app's classic cleaning; errors = edit distance to the truth)\n\n")
    sb.append("| engine | exact | char errors | CER |\n|---|---|---|---|\n")
    for ((k, s) in scores) {
        sb.append("| ").append(k).append(" | ").append(s.exact).append('/').append(s.n).append(" | ")
            .append(s.errors).append(" | ").append("%.1f%%".format(java.util.Locale.US, 100.0 * s.errors / maxOf(1, s.chars))).append(" |\n")
    }
    outFile.parentFile?.mkdirs()
    outFile.writeText(sb.toString())
    println(sb)
}
