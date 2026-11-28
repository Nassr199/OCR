import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

// 2.7.8: Gemini and OpenRouter read the same way (one recipe), OpenRouter's errors explained,
// the number of requests in flight adapts to 429 answers, the exact reading's cleaning.
// 2.7.9: the recipe is v2.6's again (its prompt first, then the picture; thinking «minimal», sent as
// «low» to models without it), and an answer cut before its end is never the subtitle.

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
    check("Gemini: one user message, the prompt then the picture (v2.6)", gb["contents"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content == "user" &&
        gParts.size == 2 && gParts[0].jsonObject.containsKey("text") && gParts[1].jsonObject.containsKey("inlineData"))
    check("Gemini: no systemInstruction, no candidateCount", !gb.containsKey("systemInstruction") && !gb["generationConfig"]!!.jsonObject.containsKey("candidateCount"))
    val gc = gb["generationConfig"]!!.jsonObject
    check("Gemini: no temperature/topP (ignored by current models), output room 8192, high detail, thinking «minimal» sent as «low» to Gemini 4",
        !gc.containsKey("temperature") && !gc.containsKey("topP") && gc["maxOutputTokens"]!!.jsonPrimitive.int == 8192 &&
            gc["mediaResolution"]!!.jsonPrimitive.content == "MEDIA_RESOLUTION_HIGH" &&
            gc["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content == "low", gc.toString())
    check("Gemini: the subtitle prompt (v2.6's with the repeat and Arabic-watermark lines)", gParts[0].jsonObject["text"]!!.jsonPrimitive.content == Prompts.SUBTITLE_PROMPT)
    val v26 = Prompts.GEMINI_V26_PROMPT.lines(); val sub = Prompts.SUBTITLE_PROMPT.lines()
    check("…every other v2.6 line unchanged, in order", sub.filter { it in v26 } == v26.filter { !it.startsWith("- Ignore watermarks") } &&
        sub.size == v26.size + 1 && sub.single { it.startsWith("- Ignore watermarks") }.startsWith(v26.single { it.startsWith("- Ignore watermarks") }))
    check("Gemini: usage counted", g.usage.answers.get() == 1 && g.usage.thinkingTokens.get() == 90L && g.usage.promptTokens.get() == 1400L)

    val oh = Fake(mutableListOf(r(200, OR_OK)))
    val o = OpenRouterEngine(oh, "sk-or", "google/gemini-4-hydrogen")
    check("OpenRouter: text", o.recognize(img) == "- هوليا\n- ابتعدي")
    val ob = obj(oh.seen[0])
    val oContent = ob["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
    check("OpenRouter: one user message, the prompt then the picture (as Gemini)",
        ob["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content == "user" && oContent.size == 2 &&
            oContent[0].jsonObject["type"]!!.jsonPrimitive.content == "text" && oContent[1].jsonObject["type"]!!.jsonPrimitive.content == "image_url")
    check("OpenRouter: the SAME prompt as Gemini", oContent[0].jsonObject["text"]!!.jsonPrimitive.content == gParts[0].jsonObject["text"]!!.jsonPrimitive.content)
    check("OpenRouter: the same picture bytes", oContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content ==
        "data:image/png;base64," + gParts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    check("OpenRouter: high detail", oContent[1].jsonObject["image_url"]!!.jsonObject["detail"]?.jsonPrimitive?.content == "high")
    // 2.9.0: low thinking asks 2048 (OpenRouter holds the room's price against the balance while it runs)
    check("OpenRouter: no temperature/top_p, max_tokens 2048, reasoning low (hidden) like Gemini's thinking",
        !ob.containsKey("temperature") && !ob.containsKey("top_p") && ob["max_tokens"]!!.jsonPrimitive.int == 2048 &&
            ob["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content == "low" && ob["reasoning"]!!.jsonObject["exclude"]!!.jsonPrimitive.boolean, ob.toString().take(0))
    check("max output: 2048 for minimal/low, 8192 for medium/high/the model's own",
        AiRecipe.maxTokens("minimal") == 2048 && AiRecipe.maxTokens("low") == 2048 && AiRecipe.maxTokens("medium") == 8192 &&
            AiRecipe.maxTokens("high") == 8192 && AiRecipe.maxTokens(null) == 8192)
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
        val pa = obj(a.seen[0])["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        val pb = obj(b.seen[0])["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        check("style ${p.name}: same prompt through Gemini and OpenRouter", pa == pb && pa == p.prompt)
    }
    // English subtitles
    val ge = Fake(mutableListOf(r(200, GEM_OK))); val oe = Fake(mutableListOf(r(200, OR_OK)))
    GeminiEngine(ge, "K", "gemini-4-hydrogen", lang = SubLang.ENGLISH).recognize(img)
    OpenRouterEngine(oe, "K", "google/gemini-4-hydrogen", lang = SubLang.ENGLISH).recognize(img)
    check("English: the English prompt through both", obj(ge.seen[0]).toString().contains("NO_TEXT") && obj(oe.seen[0]).toString().contains("NO_TEXT"))
    // resume-memory identity: style, thinking and detail in it, same shape for both
    check("cache ids", GeminiEngine(Fake(mutableListOf()), "K", "gemini-4-hydrogen").cacheId == "gemini:gemini-4-hydrogen:v26s:t=minimal:hi:r2" &&
        OpenRouterEngine(Fake(mutableListOf()), "K", "google/gemini-4-hydrogen").cacheId == "openrouter:google/gemini-4-hydrogen:v26s:t=minimal:hi:r2",
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
    // a model that refuses reasoning: adjusted once, then read
    val fr = Fake(mutableListOf(r(400, """{"error":{"code":400,"message":"Reasoning is not supported for this model"}}"""), r(200, OR_OK)))
    val o2 = OpenRouterEngine(fr, "K", "some/vl-model")
    runCatching { o2.recognize(img) }
    o2.recognize(img)
    check("OpenRouter: refused reasoning → retried without it", !obj(fr.seen[1]).containsKey("reasoning") && obj(fr.seen[1]).containsKey("max_tokens"))
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

    // ------------------------------------------------------------------ 2.7.9: v2.6 thinking, cut answers
    // «minimal»: Flash-Lite gets it as is (its own default, as v2.6 left it); Gemini 4 / 3.8 get «low»
    // from the first request (no 400 round trip); the setting «auto» sends nothing
    val lite = Fake(mutableListOf(r(200, GEM_OK)))
    GeminiEngine(lite, "K", "gemini-3.5-flash-lite").recognize(img)
    check("Flash-Lite: thinking «minimal» sent as is", obj(lite.seen[0])["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content == "minimal")
    check("Gemini 3.8 Flash / OpenRouter Gemini 4: «minimal» → «low» from the start",
        AiRecipe.thinkingFor("gemini-3.8-flash", ThinkingLevel.MINIMAL) == "low" && AiRecipe.thinkingFor("google/gemini-4-neon", ThinkingLevel.MINIMAL) == "low" &&
            AiRecipe.thinkingFor("google/gemini-3.5-flash-lite", ThinkingLevel.MINIMAL) == "minimal" && AiRecipe.thinkingFor("gemini-4-carbon", ThinkingLevel.HIGH) == "high" &&
            AiRecipe.thinkingFor("gemini-4-carbon", ThinkingLevel.DEFAULT) == null)
    val orLite = Fake(mutableListOf(r(200, OR_OK)))
    OpenRouterEngine(orLite, "K", "google/gemini-3.5-flash-lite").recognize(img)
    check("OpenRouter Flash-Lite: effort «minimal»", obj(orLite.seen[0])["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content == "minimal")
    // an unknown model that refuses «minimal»: lowered once, then read
    val unk = Fake(mutableListOf(r(400, """{"error":{"code":400,"message":"Thinking level minimal is not supported for this model.","status":"INVALID_ARGUMENT"}}"""), r(200, GEM_OK)))
    val gu = GeminiEngine(unk, "K", "gemini-9-pro")
    runCatching { gu.recognize(img) }
    check("unknown model refusing «minimal» → «low»", gu.recognize(img) == "- هوليا\n- ابتعدي" &&
        obj(unk.seen[1])["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content == "low")

    fun gem(text: String, reason: String?) = r(200, """{"candidates":[{"content":{"parts":[{"text":"$text"}]}${if (reason != null) ",\"finishReason\":\"$reason\"" else ""}}]}""")
    suspend fun gRead(vararg answers: HttpResponse): Pair<Any, Fake> {
        val f = Fake(answers.toMutableList())
        return (try { GeminiEngine(f, "K", "gemini-3.5-flash-lite").recognize(img) } catch (e: OcrError) { e }) to f
    }
    // «أر» of «أريد أمي»: the answer stopped by a filter is not the subtitle; sent again at once
    val (c1, f1) = gRead(gem("أر", "RECITATION"), gem("أريد أمي", "STOP"))
    check("cut answer («أر», RECITATION) → sent again, the whole answer kept", c1 == "أريد أمي" && f1.seen.size == 2, c1.toString())
    val (c2, f2) = gRead(gem("أر", "SAFETY"), gem("أر", "SAFETY"))
    check("cut twice → this image fails with the reason (no «أر» in the SRT)", c2 is OcrError.Cut && (c2 as OcrError.Cut).message!!.contains("SAFETY") && c2.partial == "أر" && f2.seen.size == 2, (c2 as? OcrError)?.message ?: c2.toString())
    val (c3, _) = gRead(gem("بخير بخير", "MAX_TOKENS"), gem("بخير بخير أجل أنا بخير", "STOP"))
    check("length limit → sent again", c3 == "بخير بخير أجل أنا بخير", c3.toString())
    val (c4, _) = gRead(r(200, """{"promptFeedback":{"blockReason":"OTHER"}}"""), r(200, """{"promptFeedback":{"blockReason":"OTHER"}}"""))
    check("picture refused twice → failed, not an empty subtitle", c4 is OcrError.Cut && (c4 as OcrError).message!!.contains("BLOCKED_OTHER"), (c4 as? OcrError)?.message ?: c4.toString())
    val (c5, f5) = gRead(gem("أريد أمي", null))
    check("no finish reason given → whole", c5 == "أريد أمي" && f5.seen.size == 1)
    val (c6, _) = gRead(gem("", "STOP"))
    check("an empty whole answer (no subtitle) stays empty", c6 == "")

    fun orAns(text: String, reason: String?, native: String? = null) = r(200, """{"choices":[{${if (reason != null) "\"finish_reason\":\"$reason\"," else ""}${if (native != null) "\"native_finish_reason\":\"$native\"," else ""}"message":{"role":"assistant","content":"$text"}}]}""")
    suspend fun oRead(vararg answers: HttpResponse): Any = try { OpenRouterEngine(Fake(answers.toMutableList()), "K", "google/gemini-3.5-flash-lite").recognize(img) } catch (e: OcrError) { e }
    check("OpenRouter: cut by length → sent again", oRead(orAns("أر", "length", "MAX_TOKENS"), orAns("أريد أمي", "stop")) == "أريد أمي")
    val oc = oRead(orAns("أر", "content_filter", "RECITATION"), orAns("أر", "content_filter", "RECITATION"))
    check("OpenRouter: cut twice → failed with the provider's reason", oc is OcrError.Cut && (oc as OcrError).message!!.contains("RECITATION"), (oc as? OcrError)?.message ?: oc.toString())
    check("OpenRouter: no finish reason → whole", oRead(orAns("أريد أمي", null)) == "أريد أمي")

    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
