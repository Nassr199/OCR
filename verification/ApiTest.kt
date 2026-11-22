import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class FakeHttp(val responses: MutableList<HttpResponse>) : HttpTransport {
    val seen = ArrayList<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse { seen.add(request); return responses.removeAt(0) }
}
fun r(code: Int, body: String, h: Map<String,String> = emptyMap()) = HttpResponse(code, body.toByteArray(), h)
fun main() = runBlocking {
    val img = PreparedImage(byteArrayOf(1,2,3), "image/png", 1, 1)
    // Gemini success with a thought part
    val h = FakeHttp(mutableListOf(r(200, """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"مرحبا\nبك"}],"role":"model"},"finishReason":"STOP"}]}""")))
    val g = GeminiEngine(h, "KEY", "models/gemini-3-flash-preview", ThinkingLevel.LOW)
    println("gemini text=" + g.recognize(img).replace("\n","|") + " url=" + h.seen[0].url)
    val body = Json.parseToJsonElement(String(h.seen[0].body!!)).jsonObject
    println("  sys len=" + body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.length +
        " gen=" + body["generationConfig"] + " safety=" + body["safetySettings"]!!.jsonArray.size)
    // thinking unsupported -> retryable once then body without thinking
    val h2 = FakeHttp(mutableListOf(r(400, """{"error":{"code":400,"message":"Thinking level is not supported for this model.","status":"INVALID_ARGUMENT"}}"""),
                                     r(200, """{"candidates":[{"content":{"parts":[{"text":"نص"}]}}]}""")))
    val g2 = GeminiEngine(h2, "KEY", "gemini-2.5-flash", ThinkingLevel.LOW)
    try { g2.recognize(img) } catch (e: OcrError.Retryable) { println("thinking fallback: ${e.message} after=${e.retryAfterMs}") }
    println("  second=" + g2.recognize(img) + " hasThinking=" + String(h2.seen[1].body!!).contains("thinkingConfig"))
    // errors
    for ((code, b) in listOf(
        429 to """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"13s"}]}}""",
        429 to """{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, limit: 0","status":"RESOURCE_EXHAUSTED"}}""",
        400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""",
        404 to """{"error":{"code":404,"message":"models/xyz is not found","status":"NOT_FOUND"}}""",
        503 to """{"error":{"code":503,"message":"The model is overloaded","status":"UNAVAILABLE"}}""")) {
        try { GeminiEngine(FakeHttp(mutableListOf(r(code, b))), "K", "m", ThinkingLevel.DEFAULT).recognize(img) }
        catch (e: OcrError) { println("  $code -> ${e.javaClass.simpleName}: ${e.message} ${(e as? OcrError.Retryable)?.retryAfterMs ?: ""}") }
    }
    // blocked prompt
    println("blocked='" + GeminiEngine.parseText("""{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""") + "'")
    // OpenRouter
    val h3 = FakeHttp(mutableListOf(r(200, """{"id":"x","choices":[{"message":{"role":"assistant","content":"الترجمة هنا"}}]}"""),
        r(200, """{"error":{"code":502,"message":"Provider returned error"}}"""), r(402, """{"error":{"code":402,"message":"Insufficient credits"}}""")))
    val o = OpenRouterEngine(h3, "sk-or", "")
    println("openrouter=" + o.recognize(img) + " model=" + o.model + " auth=" + h3.seen[0].headers["Authorization"])
    try { o.recognize(img) } catch (e: OcrError) { println("  200+error -> ${e.javaClass.simpleName}: ${e.message}") }
    try { o.recognize(img) } catch (e: OcrError) { println("  402 -> ${e.javaClass.simpleName}: ${e.message}") }
    val content = Json.parseToJsonElement(String(h3.seen[0].body!!)).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
    println("  img url prefix=" + content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.take(30))
    // Lens HTTP mapping
    val h4 = FakeHttp(mutableListOf(r(429, "", mapOf("Retry-After" to "3"))))
    try { LensEngine(h4).recognize(img) } catch (e: OcrError.Retryable) { println("lens 429 -> after=${e.retryAfterMs} rl=${e.rateLimited} ct=${h4.seen[0].contentType} key=${h4.seen[0].headers["X-Goog-Api-Key"]?.take(6)}") }
    // model listing
    val h5 = FakeHttp(mutableListOf(r(200, """{"models":[{"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]},{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""")))
    println("models=" + GeminiEngine.listModels(h5, "K"))
}
