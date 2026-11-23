import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

class Fake(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) : HttpTransport {
    var last: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse { last = request; return HttpResponse(code, body.toByteArray(), headers) }
}

fun main() = runBlocking {
    val img = PreparedImage(byteArrayOf(1, 2, 3, 4), "image/png", 2, 2)
    // 1) request shape
    val ok = Fake(200, """{"responses":[{"textAnnotations":[{"locale":"ar","description":"مرحبا يا صديقي\nكيف حالك؟\n"},{"description":"مرحبا"}],
        "fullTextAnnotation":{"text":"مرحبا يا صديقي\nكيف حالك؟\n"}}]}""")
    val e = VisionEngine(ok, "KEY123", "ar")
    val text = e.recognize(img)
    val req = ok.last!!
    check("endpoint", req.url == "https://vision.googleapis.com/v1/images:annotate", req.url)
    check("key in header, not URL", req.headers["x-goog-api-key"] == "KEY123" && !req.url.contains("KEY123"))
    val body = Json.parseToJsonElement(req.body!!.toString(Charsets.UTF_8)).jsonObject
    val r0 = body["requests"]!!.jsonArray[0].jsonObject
    check("base64 image", r0["image"]!!.jsonObject["content"]!!.jsonPrimitive.content == "AQIDBA==")
    check("TEXT_DETECTION", r0["features"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "TEXT_DETECTION")
    val ctx = r0["imageContext"]!!.jsonObject
    check("language hints ar + en (as Subtitle Edit)", ctx["languageHints"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("ar", "en"))
    check("word confidence requested", ctx["textDetectionParams"]!!.jsonObject["enableTextDetectionConfidenceScore"]!!.jsonPrimitive.content == "true")
    val enBody = Json.parseToJsonElement(VisionEngine(ok, "k", "en").buildBody(img)).jsonObject["requests"]!!.jsonArray[0].jsonObject
    check("English subtitles: hint en only", enBody["imageContext"]!!.jsonObject["languageHints"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("en"))
    check("full text, trailing newline trimmed", text == "مرحبا يا صديقي\nكيف حالك؟", text)
    check("cacheId per language", e.cacheId == "vision:ar:v4" && VisionEngine(ok, "k", "en").cacheId == "vision:en:v4")
    // 2) fallback to textAnnotations, empty response
    check("textAnnotations fallback", VisionEngine.parseText("""{"responses":[{"textAnnotations":[{"description":"أبي\n"}]}]}""") == "أبي")
    check("no text → empty", VisionEngine.parseText("""{"responses":[{}]}""") == "")
    // 3) cleaning: same as Lens (classic OCR, AUTO → PRO): credits line dropped, Arabic kept
    val raw = "2. Ekip Görüntü Yönetmeni\nأبي\nTuğberk Süel"
    check("Lens/Vision cleaning drops credits", Cleaner.clean(raw, CleanMode.PRO) == "أبي", Cleaner.clean(raw, CleanMode.PRO))
    check("classic OCR group", EngineKind.LENS.isClassicOcr && EngineKind.VISION.isClassicOcr && !EngineKind.GEMINI.isClassicOcr && !EngineKind.OPENROUTER.isClassicOcr)
    // 4) errors
    suspend fun err(code: Int, body: String): OcrError = try { VisionEngine(Fake(code, body), "k").recognize(img); error("no throw") } catch (x: OcrError) { x }
    val bad = err(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""")
    check("bad key → stop job", bad is OcrError.Fatal && bad.message!!.contains("غير صالح"), bad.message ?: "")
    val billing = err(403, """{"error":{"code":403,"message":"This API method requires billing to be enabled.","status":"PERMISSION_DENIED","details":[{"reason":"BILLING_DISABLED"}]}}""")
    check("billing → stop job with reason", billing is OcrError.Fatal && billing.message!!.contains("الفوترة"), billing.message ?: "")
    val disabled = err(403, """{"error":{"code":403,"message":"Cloud Vision API has not been used in project 123 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}""")
    check("API disabled → stop job", disabled is OcrError.Fatal && disabled.message!!.contains("فعّل"), disabled.message ?: "")
    val rl = err(429, """{"error":{"code":429,"message":"Quota exceeded for quota metric 'Requests' per minute","status":"RESOURCE_EXHAUSTED"}}""")
    check("429 per minute → retry", rl is OcrError.Retryable && rl.rateLimited)
    val srv = err(503, "oops")
    check("5xx → retry", srv is OcrError.Retryable && !srv.rateLimited)
    val perImage = try { VisionEngine.parseText("""{"responses":[{"error":{"code":3,"message":"Bad image data."}}]}"""); null } catch (x: OcrError) { x }
    check("per-image bad data → only this image fails", perImage is OcrError.Failed, perImage?.message ?: "")
    val perImageRl = try { VisionEngine.parseText("""{"responses":[{"error":{"code":8,"message":"Resource exhausted"}}]}"""); null } catch (x: OcrError) { x }
    check("per-image quota → retry", perImageRl is OcrError.Retryable)
    check("blank key refused", try { VisionEngine(ok, " "); false } catch (x: OcrError.Fatal) { true })
    // 5) Gemini model list
    check("Gemini list current", GeminiEngine.SUGGESTED_MODELS == listOf("gemini-4-hydrogen", "gemini-4-neon", "gemini-4-carbon", "gemini-4-argon", "gemini-3.8-flash", "gemini-3.5-flash-lite"))
    check("fetched list filter", listOf("gemini-4-neon", "gemini-3.8-flash", "gemini-3.5-flash-lite").all { GeminiEngine.keepForOcr(it) } &&
        listOf("gemini-3.8-flash-tts", "gemini-3.1-flash-image", "gemini-embedding-2", "gemini-3.8-live", "gemini-2.0-flash", "gemma-3-27b-it", "gemini-omni-1.1-flash", "gemini-3.5-transcribe").none { GeminiEngine.keepForOcr(it) })
    check("OpenRouter ids", OpenRouterEngine.SUGGESTED_MODELS.first() == "google/gemini-4-hydrogen" && OpenRouterEngine.DEFAULT_MODEL == "google/gemini-3.5-flash-lite")
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
