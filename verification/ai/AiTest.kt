import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

// 2.7.8: Gemini and OpenRouter read the same way (one recipe), OpenRouter's errors explained,
// the number of requests in flight adapts to 429 answers, the exact reading's cleaning.

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

class Fake(val answers: MutableList<HttpResponse>) : HttpTransport {
    val seen = ArrayList<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse { seen.add(request); return answers.removeAt(0) }
}
fun r(code: Int, body: String, h: Map<String, String> = emptyMap()) = HttpResponse(code, body.toByteArray(), h)
fun obj(req: HttpRequest) = Json.parseToJsonElement(String(req.body!!)).jsonObject

const val GEM_OK = """{"candidates":[{"content":{"parts":[{"text":"…","thought":true},{"text":"- هوليا\n- ابتعدي"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1400,"candidatesTokenCount":12,"thoughtsTokenCount":90}}"""
const val OR_OK = """{"id":"g","model":"google/gemini-4-hydrogen","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"- هوليا\n- ابتعدي"}}],"usage":{"prompt_tokens":1400,"completion_tokens":102,"completion_tokens_details":{"reasoning_tokens":90},"cost":0.00213}}"""

fun main() = runBlocking {
    val img = PreparedImage(byteArrayOf(7, 7, 7), "image/png", 10, 4)

    // ------------------------------------------------------------------ the same request through both engines
    val gh = Fake(mutableListOf(r(200, GEM_OK)))
    val g = GeminiEngine(gh, "KEY", "gemini-4-hydrogen")
    check("Gemini: text without the thought part", g.recognize(img) == "- هوليا\n- ابتعدي")
    val gb = obj(gh.seen[0])
    val gParts = gb["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
    check("Gemini: one user message, the picture first then the prompt", gb["contents"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content == "user" &&
        gParts.size == 2 && gParts[0].jsonObject.containsKey("inlineData") && gParts[1].jsonObject.containsKey("text"))
    check("Gemini: no systemInstruction, no candidateCount", !gb.containsKey("systemInstruction") && !gb["generationConfig"]!!.jsonObject.containsKey("candidateCount"))
    val gc = gb["generationConfig"]!!.jsonObject
    check("Gemini: temperature 0, topP 1, high detail, thinking low (the defaults)",
        gc["temperature"]!!.jsonPrimitive.double == 0.0 && gc["topP"]!!.jsonPrimitive.double == 1.0 &&
            gc["mediaResolution"]!!.jsonPrimitive.content == "MEDIA_RESOLUTION_HIGH" &&
            gc["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content == "low", gc.toString())
    check("Gemini: the exact prompt by default", gParts[1].jsonObject["text"]!!.jsonPrimitive.content == Prompts.EXACT_PROMPT)
    check("Gemini: usage counted", g.usage.answers.get() == 1 && g.usage.thinkingTokens.get() == 90L && g.usage.promptTokens.get() == 1400L)

    val oh = Fake(mutableListOf(r(200, OR_OK)))
    val o = OpenRouterEngine(oh, "sk-or", "google/gemini-4-hydrogen")
    check("OpenRouter: text", o.recognize(img) == "- هوليا\n- ابتعدي")
    val ob = obj(oh.seen[0])
    val oContent = ob["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
    check("OpenRouter: one user message, the picture first then the prompt",
        ob["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content == "user" && oContent.size == 2 &&
            oContent[0].jsonObject["type"]!!.jsonPrimitive.content == "image_url" && oContent[1].jsonObject["type"]!!.jsonPrimitive.content == "text")
    check("OpenRouter: the SAME prompt as Gemini", oContent[1].jsonObject["text"]!!.jsonPrimitive.content == gParts[1].jsonObject["text"]!!.jsonPrimitive.content)
    check("OpenRouter: the same picture bytes", oContent[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content ==
        "data:image/png;base64," + gParts[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    check("OpenRouter: high detail", oContent[0].jsonObject["image_url"]!!.jsonObject["detail"]?.jsonPrimitive?.content == "high")
    check("OpenRouter: temperature 0, top_p 1, reasoning low (hidden) like Gemini's thinking",
        ob["temperature"]!!.jsonPrimitive.double == 0.0 && ob["top_p"]!!.jsonPrimitive.double == 1.0 &&
            ob["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content == "low" && ob["reasoning"]!!.jsonObject["exclude"]!!.jsonPrimitive.boolean, ob.toString().take(0))
    check("OpenRouter: attribution is the app's page, not openrouter.ai", oh.seen[0].headers["HTTP-Referer"] == "https://github.com/Nassr199/OCR" && oh.seen[0].headers["X-OpenRouter-Title"] != null)
    check("OpenRouter: usage and cost counted", o.usage.thinkingTokens.get() == 90L && o.usage.costMicros.get() == 2130L, o.usage.summary() ?: "")
    // thinking "auto": nothing sent, the model decides
    val oh2 = Fake(mutableListOf(r(200, OR_OK)))
    OpenRouterEngine(oh2, "sk-or", "google/gemini-4-hydrogen", ThinkingLevel.DEFAULT).recognize(img)
    check("OpenRouter: thinking «auto» → no reasoning field", !obj(oh2.seen[0]).containsKey("reasoning"))
    val gh2 = Fake(mutableListOf(r(200, GEM_OK)))
    GeminiEngine(gh2, "KEY", "gemini-4-hydrogen", ThinkingLevel.DEFAULT).recognize(img)
    check("Gemini: thinking «auto» → no thinkingConfig", !obj(gh2.seen[0])["generationConfig"]!!.jsonObject.containsKey("thinkingConfig"))
    // other styles go the same way through both
    for (p in AiProfile.values()) {
        val a = Fake(mutableListOf(r(200, GEM_OK))); val b = Fake(mutableListOf(r(200, OR_OK)))
        GeminiEngine(a, "K", "gemini-4-hydrogen", profile = p).recognize(img)
        OpenRouterEngine(b, "K", "google/gemini-4-hydrogen", profile = p).recognize(img)
        val pa = obj(a.seen[0])["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content
        val pb = obj(b.seen[0])["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content
        check("style ${p.name}: same prompt through Gemini and OpenRouter", pa == pb && pa == p.prompt)
    }
    // English subtitles
    val ge = Fake(mutableListOf(r(200, GEM_OK))); val oe = Fake(mutableListOf(r(200, OR_OK)))
    GeminiEngine(ge, "K", "gemini-4-hydrogen", lang = SubLang.ENGLISH).recognize(img)
    OpenRouterEngine(oe, "K", "google/gemini-4-hydrogen", lang = SubLang.ENGLISH).recognize(img)
    check("English: the English prompt through both", obj(ge.seen[0]).toString().contains("NO_TEXT") && obj(oe.seen[0]).toString().contains("NO_TEXT"))
    // resume-memory identity: style, thinking and detail in it, same shape for both
    check("cache ids", GeminiEngine(Fake(mutableListOf()), "K", "gemini-4-hydrogen").cacheId == "gemini:gemini-4-hydrogen:exact:t=low:hi" &&
        OpenRouterEngine(Fake(mutableListOf()), "K", "google/gemini-4-hydrogen").cacheId == "openrouter:google/gemini-4-hydrogen:exact:t=low:hi",
        GeminiEngine(Fake(mutableListOf()), "K", "gemini-4-hydrogen").cacheId)

    // ------------------------------------------------------------------ OpenRouter errors, in plain words
    suspend fun orError(code: Int, body: String, h: Map<String, String> = emptyMap()): OcrError? =
        try { OpenRouterEngine(Fake(mutableListOf(r(code, body, h))), "K", "google/gemini-3.8-flash").recognize(img); null } catch (e: OcrError) { e }
    val upstream = orError(429, """{"error":{"code":429,"message":"Rate limit exceeded","metadata":{"error_type":"rate_limit_exceeded","provider_name":"Google AI Studio","raw":"{\"error\":{\"code\":429,\"message\":\"Resource exhausted\"}}"}}}""")
    check("429 from the provider: retried, rate limited, names the provider, says it is not the credit",
        upstream is OcrError.Retryable && upstream.rateLimited && upstream.retryAfterMs == null &&
            upstream.message!!.contains("Google AI Studio") && upstream.message!!.contains("ليس رصيدك"), upstream?.message ?: "")
    val withHint = orError(429, """{"error":{"code":429,"message":"Rate limit exceeded"}}""", mapOf("Retry-After" to "7"))
    check("429 with Retry-After: that wait", (withHint as? OcrError.Retryable)?.retryAfterMs == 7000L)
    val daily = orError(429, """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day","metadata":{"limit_source":"openrouter_free_tier_daily"}}}""")
    check("free models' daily limit: stops the job", daily is OcrError.Fatal, daily?.message ?: "")
    val perMin = orError(429, """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min.","metadata":{"limit_source":"openrouter_free_tier_per_minute"}}}""",
        mapOf("X-RateLimit-Reset" to (System.currentTimeMillis() + 9000).toString()))
    check("free models' minute limit: waits until the reset", perMin is OcrError.Retryable && ((perMin as OcrError.Retryable).retryAfterMs ?: 0) in 7000..10000, (perMin as? OcrError.Retryable)?.retryAfterMs.toString())
    check("402 credit: stops", orError(402, """{"error":{"code":402,"message":"Insufficient credits"}}""") is OcrError.Fatal)
    check("402 in-flight budget: waits", orError(402, """{"error":{"code":402,"message":"busy","metadata":{"limit_source":"openrouter_in_flight_budget"}}}""") is OcrError.Retryable)
    check("401: stops", orError(401, """{"error":{"code":401,"message":"No auth credentials found"}}""") is OcrError.Fatal)
    val p502 = orError(502, """{"error":{"code":502,"message":"Provider returned error","metadata":{"provider_name":"Google Vertex","raw":"deadline exceeded"}}}""")
    check("502: retried, names the provider and its words", p502 is OcrError.Retryable && p502.message!!.contains("Google Vertex") && p502.message!!.contains("deadline exceeded"), p502?.message ?: "")
    val mid = orError(200, """{"choices":[{"finish_reason":"error","error":{"code":503,"message":"Provider disconnected"},"message":{"content":""}}]}""")
    check("error inside a 200 answer (cut generation): retried, not an empty subtitle", mid is OcrError.Retryable, mid?.message ?: "null")
    val bad = orError(400, """{"error":{"code":400,"message":"Provider returned error","metadata":{"provider_name":"Google AI Studio","raw":"INVALID_ARGUMENT: image too large"}}}""")
    check("400: this image only, with the provider's reason", bad is OcrError.Failed && bad.message!!.contains("image too large"), bad?.message ?: "")
    // a model that refuses temperature / top_p, or reasoning: adjusted once, then read
    val fs = Fake(mutableListOf(r(400, """{"error":{"code":400,"message":"Unsupported parameter: 'temperature' is not supported with this model."}}"""), r(200, OR_OK)))
    val oo = OpenRouterEngine(fs, "K", "openai/gpt-x")
    val firstTry = try { oo.recognize(img); null } catch (e: OcrError) { e }
    check("OpenRouter: refused temperature → retried at once without it", firstTry is OcrError.Retryable && firstTry.retryAfterMs == 0L && oo.recognize(img).isNotEmpty() &&
        !obj(fs.seen[1]).containsKey("temperature") && !obj(fs.seen[1]).containsKey("top_p") && obj(fs.seen[1]).containsKey("reasoning"))
    val fr = Fake(mutableListOf(r(400, """{"error":{"code":400,"message":"Reasoning is not supported for this model"}}"""), r(200, OR_OK)))
    val o2 = OpenRouterEngine(fr, "K", "some/vl-model")
    runCatching { o2.recognize(img) }
    o2.recognize(img)
    check("OpenRouter: refused reasoning → retried without it", !obj(fr.seen[1]).containsKey("reasoning") && obj(fr.seen[1]).containsKey("temperature"))
    val gs = Fake(mutableListOf(r(400, """{"error":{"code":400,"message":"Invalid value at 'generation_config.temperature'","status":"INVALID_ARGUMENT"}}"""), r(200, GEM_OK)))
    val g3 = GeminiEngine(gs, "K", "gemini-9")
    runCatching { g3.recognize(img) }
    g3.recognize(img)
    check("Gemini: refused temperature → retried without temperature/topP, thinking kept",
        obj(gs.seen[1])["generationConfig"]!!.jsonObject.let { !it.containsKey("temperature") && !it.containsKey("topP") && it.containsKey("thinkingConfig") })
    // key check
    val ki = OpenRouterEngine.keyInfo(Fake(mutableListOf(r(200, """{"data":{"label":"sk-or-v1-0e6...1c96","limit":null,"limit_remaining":null,"usage":3.25,"is_free_tier":false}}"""))), "K")
    check("key check: paid account, no key limit, usage", ki.freeTier == false && ki.limit == null && ki.usage == 3.25)
    val kb = try { OpenRouterEngine.keyInfo(Fake(mutableListOf(r(401, """{"error":{"code":401,"message":"User not found."}}"""))), "K"); null } catch (e: OcrError) { e }
    check("key check: a wrong key is said so", kb is OcrError.Fatal)
    // Gemini 429 without Google's wait → the pipeline's growing wait (not a fixed 10 s)
    val g429 = try { GeminiEngine(Fake(mutableListOf(r(429, """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}"""))), "K", "m").recognize(img); null } catch (e: OcrError) { e }
    check("Gemini 429 without retryDelay: rate limited, no fixed wait", g429 is OcrError.Retryable && g429.rateLimited && g429.retryAfterMs == null)

    // ------------------------------------------------------------------ requests in flight adapt to 429
    val lim = AdaptiveLimit(4)
    check("limit starts at the workers", lim.current == 4)
    lim.acquire(); lim.release(rateLimited = true)
    check("429 halves it", lim.current == 2)
    lim.acquire(); lim.release(true); lim.acquire(); lim.release(true)
    check("…down to one, never zero", lim.current == 1)
    repeat(4) { lim.acquire(); lim.release(false) }
    check("calm gives one back", lim.current == 2)
    repeat(40) { lim.acquire(); lim.release(false) }
    check("…up to the workers again, not beyond", lim.current == 4)

    // the pipeline: a service that answers 429 while more than 2 requests are in flight
    class Crowded : OcrEngine {
        override val kind = EngineKind.OPENROUTER
        override val cacheId = "crowded"
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val maxSeen = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val refused = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun recognize(image: PreparedImage): String {
            calls.incrementAndGet()
            val n = inFlight.incrementAndGet()
            try {
                maxSeen.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                delay(30)
                if (n > 2) { refused.incrementAndGet(); throw OcrError.Retryable("busy", 1, true) }
                return "نص"
            } finally { inFlight.decrementAndGet() }
        }
    }
    val eng = Crowded()
    val limits = ArrayList<Int>()
    val pipe = OcrPipeline(eng, { b, n, k -> PreparedImage(b, "image/png", 1, 1, n) }, CleanMode.PRO, false, 6,
        retry = RetryPolicy(maxRetries = 2), listener = object : PipelineListener { override fun onLimit(limit: Int, workers: Int) { synchronized(limits) { limits.add(limit) } } })
    val ch = Channel<OcrItem>(Channel.UNLIMITED)
    repeat(60) { i -> ch.trySend(OcrItem("k$i", "i$i", i * 1000L, i * 1000L + 900) { byteArrayOf(1) }) }
    ch.close()
    val t0 = System.currentTimeMillis()
    pipe.run(ch)
    check("crowded service: every image read, none lost", pipe.stats.ok.get() == 60 && pipe.stats.failed.get() == 0, "ok ${pipe.stats.ok.get()} failed ${pipe.stats.failed.get()}")
    check("…the limit came down from 6 (log: ${limits.take(6)})", limits.isNotEmpty() && limits.min() <= 2)
    check("…refusals stayed few (${eng.refused.get()} of ${eng.calls.get()} calls) in ${(System.currentTimeMillis() - t0) / 1000.0} s", eng.refused.get() < 30)

    // ------------------------------------------------------------------ the exact reading's cleaning
    fun ex(s: String) = Cleaner.clean(s, CleanMode.EXACT)
    check("exact: lines and dialogue dashes kept", ex("- هوليا\n- ابتعدي يا جوليزار") == "- هوليا\n- ابتعدي يا جوليزار")
    check("exact: «-word» spaced as on screen", ex("-هوليا\n-ابتعدي") == "- هوليا\n- ابتعدي")
    check("exact: «…» «» kept (v2.6 dropped them)", ex("انتظر… «لا» تذهب!") == "انتظر… «لا» تذهب!", ex("انتظر… «لا» تذهب!"))
    check("exact: visible tashkeel kept", ex("أنتِ هنا؟ شكرًا") == "أنتِ هنا؟ شكرًا")
    check("exact: v2.6 guard still strips an over-marked word", ex("ذَهَبَ إلى البيت") == "ذهب إلى البيت", ex("ذَهَبَ إلى البيت"))
    check("exact: fragmented word joined (and, over-marked, stripped — as v2.6)", ex("سِ لِ م تِ") == "سلمت", ex("سِ لِ م تِ"))
    check("exact: model reply / empty", ex("\"\"") == "" && ex("لا يوجد نص عربي في الصورة") == "" && ex("```\n\n```") == "")
    check("exact: Latin credit lines dropped", ex("Selin Aydın\n- هوليا") == "- هوليا")
    check("exact: tatweel removed", ex("جميـــل") == "جميل")
    check("exact: «remove all tashkeel» still works", Cleaner.clean("- أنتِ هنا", CleanMode.EXACT, stripDiacritics = true) == "- أنت هنا")

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
