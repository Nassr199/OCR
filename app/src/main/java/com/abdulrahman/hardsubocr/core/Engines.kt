package com.abdulrahman.hardsubocr.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun parseJsonOrNull(text: String): JsonElement? = try {
    json.parseToJsonElement(text)
} catch (e: Exception) {
    null
}

private fun shortBody(text: String, max: Int = 300): String =
    text.replace(Regex("\\s+"), " ").let { if (it.length > max) it.substring(0, max) + "…" else it }

// =====================================================================================  Google Lens

class LensEngine(
    private val http: HttpTransport,
    private val language: String = "ar",
    private val region: String = "US",
    private val timeZone: String = "America/New_York",
    private val apiKey: String = LensProtocol.API_KEY
) : OcrEngine {
    override val kind = EngineKind.LENS
    override val cacheId = "lens:$language"

    override suspend fun recognize(image: PreparedImage): String {
        val body = LensProtocol.buildRequest(image.bytes, image.width, image.height, language, region, timeZone)
        val resp = http.execute(
            HttpRequest(
                url = LensProtocol.ENDPOINT,
                headers = mapOf(
                    "X-Goog-Api-Key" to apiKey,
                    "User-Agent" to LensProtocol.USER_AGENT,
                    "Accept" to "*/*",
                    // like Subtitle Edit: the reading language also in the HTTP header
                    "Accept-Language" to "$language,en;q=0.9"
                ),
                body = body,
                contentType = "application/x-protobuf",
                timeoutMs = 60_000
            )
        )
        when {
            resp.code == 200 -> {
                val result = try {
                    LensProtocol.parseResponse(resp.body)
                } catch (e: Exception) {
                    throw OcrError.Retryable("رد Google Lens غير مفهوم: ${e.message}")
                }
                val text = result.readingText
                if (text != result.fullText) DiagLog.i("lens", "reading order / read twice: «${result.fullText.replace("\n", " ⏎ ").take(200)}» → «${text.replace("\n", " ⏎ ").take(200)}»")
                return text
            }
            resp.code == 429 -> throw OcrError.Retryable(
                "Google Lens: طلبات كثيرة (429)", parseRetryAfter(resp.header("Retry-After")) ?: 8_000, true
            )
            resp.code == 401 || resp.code == 403 -> throw OcrError.Retryable(
                "Google Lens رفض الطلب (${resp.code})", 15_000, true
            )
            resp.code in 500..599 -> throw OcrError.Retryable("خطأ مؤقت في خادم Google Lens (${resp.code})")
            resp.code == 400 || resp.code == 413 -> throw OcrError.Failed("Google Lens رفض هذه الصورة (${resp.code})")
            else -> throw OcrError.Retryable("Google Lens أعاد رمز ${resp.code}: ${shortBody(resp.text)}")
        }
    }
}

/** One entry of the model picker. */
data class ModelInfo(val id: String, val name: String, val family: String, val note: String)

// =====================================================================================  Google Cloud Vision

/**
 * Google Cloud Vision OCR (images:annotate, TEXT_DETECTION — text in pictures, not documents), with
 * the user's own Google Cloud API key. Handled like Google Lens: same image (PNG ≤ 1500 px), same
 * cleaning and merging, raw text exactly as recognised.
 */
class VisionEngine(
    private val http: HttpTransport,
    private val apiKey: String,
    private val language: String = "ar"
) : OcrEngine {
    override val kind = EngineKind.VISION
    override val cacheId = "vision:$language:layout"

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل مفتاح Google Cloud Vision أولاً")
    }

    fun buildBody(image: PreparedImage): String = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                putJsonObject("image") { put("content", Base64.getEncoder().encodeToString(image.bytes)) }
                putJsonArray("features") { addJsonObject { put("type", "TEXT_DETECTION") } }
                putJsonObject("imageContext") { putJsonArray("languageHints") { add(JsonPrimitive(language)) } }
            }
        }
    }.toString()

    override suspend fun recognize(image: PreparedImage): String {
        val resp = http.execute(
            HttpRequest(
                url = ENDPOINT,
                headers = mapOf("x-goog-api-key" to apiKey),
                body = buildBody(image).toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                timeoutMs = 60_000
            )
        )
        if (resp.code == 200) return parseText(resp.text)
        val e = GoogleApiError.parse(resp.text)
        throw classify(resp.code, e.status, e.message.ifBlank { shortBody(resp.text) }, e.reason, parseRetryAfter(resp.header("Retry-After")))
    }

    companion object {
        const val ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"

        /** Full text of the first response; a per-image error is raised like an HTTP one. */
        fun parseText(body: String): String {
            val root = parseJsonOrNull(body).obj() ?: throw OcrError.Retryable("رد Google Vision غير مفهوم")
            val r = root["responses"].arr()?.firstOrNull().obj() ?: return ""
            r["error"].obj()?.let { err ->
                val code = (err["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val msg = err["message"].str() ?: ""
                throw classify(rpcToHttp(code), "", msg, null, null)
            }
            val full = (r["fullTextAnnotation"].obj()?.get("text").str()
                ?: r["textAnnotations"].arr()?.firstOrNull().obj()?.get("description").str()
                ?: return "").trimEnd('\n', ' ')
            // Vision's own text repeats words read twice and lays blocks out left to right:
            // the text is rebuilt from the word boxes (VisionLayout), its own text is the fallback.
            val words = VisionLayout.wordsOf(r)
            val rebuilt = VisionLayout.rebuild(words) ?: return full
            if (rebuilt != full) {
                DiagLog.i("vision", "layout: «${oneLine(full)}» → «${oneLine(rebuilt)}»")
                DiagLog.i("vision", "words: " + VisionLayout.describe(words))
            }
            return rebuilt
        }

        private fun oneLine(s: String) = s.replace("\n", " ⏎ ").let { if (it.length > 160) it.take(160) + "…" else it }

        /** google.rpc.Code of a per-image error → the HTTP code the same problem gets. */
        private fun rpcToHttp(code: Int): Int = when (code) {
            3 -> 400; 7 -> 403; 16 -> 401; 8 -> 429; 4 -> 504; 14 -> 503; 13 -> 500; 5 -> 404
            else -> 500
        }

        fun classify(code: Int, status: String, msg: String, reason: String?, retryAfterMs: Long?): OcrError {
            val m = msg.lowercase()
            val r = reason.orEmpty()
            return when {
                r == "API_KEY_INVALID" || m.contains("api key not valid") || m.contains("api_key_invalid") ->
                    OcrError.Fatal("مفتاح Google Cloud Vision غير صالح")
                r == "BILLING_DISABLED" || m.contains("billing") ->
                    OcrError.Fatal("Cloud Vision يتطلب تفعيل الفوترة (Billing) في مشروع Google Cloud: $msg")
                r == "SERVICE_DISABLED" || m.contains("has not been used") || m.contains("is disabled") ->
                    OcrError.Fatal("فعّل Cloud Vision API في مشروع Google Cloud الخاص بالمفتاح: $msg")
                r == "API_KEY_SERVICE_BLOCKED" || r == "API_KEY_ANDROID_APP_BLOCKED" || m.contains("are blocked") ->
                    OcrError.Fatal("المفتاح مقيّد ولا يسمح بـ Cloud Vision API: $msg")
                code == 401 || code == 403 || status == "PERMISSION_DENIED" ->
                    OcrError.Fatal("Google Vision رفض المفتاح أو الصلاحية: $msg")
                code == 429 && (m.contains("per day") || m.contains("perday")) ->
                    OcrError.Fatal("انتهت الحصة اليومية لـ Google Vision: $msg")
                code == 429 -> OcrError.Retryable("Google Vision: طلبات كثيرة (429)", retryAfterMs ?: 8_000, true)
                code in 500..599 -> OcrError.Retryable("خطأ مؤقت في Google Vision ($code)")
                code == 400 || code == 413 -> OcrError.Failed("Google Vision رفض هذه الصورة: $msg")
                else -> OcrError.Retryable("Google Vision أعاد $code: $msg")
            }
        }
    }
}

/** The error object every Google API returns: {"error": {code, message, status, details: [{reason}]}}. */
internal object GoogleApiError {
    class Parsed(val message: String, val status: String, val reason: String?)

    fun parse(body: String): Parsed {
        val e = parseJsonOrNull(body).obj()?.get("error").obj() ?: return Parsed("", "", null)
        var reason: String? = null
        e["details"].arr()?.forEach { d -> d.obj()?.get("reason").str()?.let { if (reason == null) reason = it } }
        return Parsed(e["message"].str() ?: "", e["status"].str() ?: "", reason)
    }
}

// =====================================================================================  Gemini (direct)

enum class ThinkingLevel(val api: String?) { DEFAULT(null), MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high") }

/**
 * Reading style for the AI engines — each bundles the prompt and the post-processing of one
 * Windows program, so results match what the user already tuned there.
 */
enum class AiProfile(
    val id: String,
    val prompt: String,
    val clean: CleanMode,
    val merge: MergeMode,
    val enhanceImages: Boolean,
    val stabilize: Boolean
) {
    /** ocr_windows_v2.6.py: subtitle-band image enhancement, conservative prompt, diacritics guard. */
    V26("v26", Prompts.GEMINI_V26_PROMPT, CleanMode.V26, MergeMode.STRICT, true, true),

    /** ocr_windows_v2.1.py: long ultra-precise prompt, clean_ocr_output, merge rules 2A–2E. */
    V21("v21", Prompts.GEMINI_SYSTEM_PROMPT, CleanMode.PRECISE, MergeMode.STRICT, false, false),

    /** HardSubArabicOCR_v2_PRO.py (OpenRouter): short Arabic prompt, arabic_filter, merge_entries. */
    PRO("pro", Prompts.OPENROUTER_PROMPT, CleanMode.PRO, MergeMode.PRO, false, false)
}

class GeminiEngine(
    private val http: HttpTransport,
    private val apiKey: String,
    model: String,
    thinking: ThinkingLevel = ThinkingLevel.LOW,
    private val profile: AiProfile = AiProfile.V26,
    highResolution: Boolean = true,
    private val lang: SubLang = SubLang.ARABIC
) : OcrEngine {
    val model: String = model.trim().removePrefix("models/")
    override val kind = EngineKind.GEMINI
    override val cacheId = "gemini:${this.model}:${profile.id}:${if (highResolution) "hi" else "def"}" +
        (if (lang == SubLang.ARABIC) "" else ":${lang.code}")
    private val systemPrompt = if (lang == SubLang.ENGLISH) Prompts.ENGLISH_PROMPT else profile.prompt

    /** Lowered automatically when a model rejects it (e.g. Gemini 4 / 3.8 have no "minimal"). */
    @Volatile
    var thinkingLevel: String? = thinking.api
        private set

    @Volatile
    var mediaHigh: Boolean = highResolution
        private set

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل Gemini API Key أولاً")
        if (this.model.isBlank()) throw OcrError.Fatal("اختر موديل Gemini")
    }

    fun buildBody(image: PreparedImage, thinking: String?, high: Boolean): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", image.mime)
                            put("data", Base64.getEncoder().encodeToString(image.bytes))
                        }
                    }
                }
            }
        }
        putJsonArray("safetySettings") {
            for (c in listOf(
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
            )) addJsonObject { put("category", c); put("threshold", "BLOCK_NONE") }
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.0)
            put("topP", 1.0)
            put("candidateCount", 1)
            if (high) put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
            if (thinking != null) putJsonObject("thinkingConfig") { put("thinkingLevel", thinking) }
        }
    }.toString()

    override suspend fun recognize(image: PreparedImage): String {
        val level = thinkingLevel
        val high = mediaHigh
        val resp = http.execute(
            HttpRequest(
                url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                headers = mapOf("x-goog-api-key" to apiKey),
                body = buildBody(image, level, high).toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                timeoutMs = 120_000
            )
        )
        if (resp.code == 200) return parseText(resp.text)

        val err = GeminiErrors.parse(resp.text)
        val msg = err.message.ifBlank { shortBody(resp.text) }
        val low = msg.lowercase()
        if (resp.code == 400 && level != null && low.contains("thinking")) {
            // minimal -> low -> (no thinking config)
            thinkingLevel = if (level == "minimal") "low" else null
            throw OcrError.Retryable("الموديل لا يدعم مستوى التفكير «$level» — تعديل تلقائي", retryAfterMs = 0)
        }
        if (resp.code == 400 && high && (low.contains("media_resolution") || low.contains("mediaresolution") ||
                low.contains("media resolution"))
        ) {
            mediaHigh = false
            throw OcrError.Retryable("الموديل لا يدعم الدقة العالية للصور — إعادة بالافتراضي", retryAfterMs = 0)
        }
        throw GeminiErrors.classify(resp.code, err.status, msg, err.retryDelayMs)
    }

    companion object {
        /**
         * Current Gemini models that read images (Gemini API, checked 23 Nov 2026), in the order shown.
         * Older ones (3.6 Flash, 3 Flash Preview, 3.1 Flash-Lite, 2.x) are no longer offered here.
         */
        val MODELS = listOf(
            ModelInfo("gemini-4-hydrogen", "Gemini 4 Hydrogen", "Gemini 4", "الأسرع في Gemini 4 — مناسب للاستخراج اليومي"),
            ModelInfo("gemini-4-neon", "Gemini 4 Neon", "Gemini 4", "بين Hydrogen وCarbon: قراءة أعمق بسرعة معتدلة"),
            ModelInfo("gemini-4-carbon", "Gemini 4 Carbon", "Gemini 4", "أقوى استدلال في Gemini 4 — الأبطأ"),
            ModelInfo("gemini-4-argon", "Gemini 4 Argon", "Gemini 4", "أول نماذج Gemini 4"),
            ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", "Gemini 3", "أحدث Flash في Gemini 3"),
            ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Gemini 3", "الأخف — للمجلدات الكبيرة")
        )
        val SUGGESTED_MODELS = MODELS.map { it.id }
        const val DEFAULT_MODEL = "gemini-4-hydrogen"

        /** Models that were offered before and are gone from the list (kept working if typed by hand). */
        val RETIRED = setOf("gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.1-flash-lite")

        fun info(id: String): ModelInfo? = MODELS.firstOrNull { it.id == id.removePrefix("models/") }

        /** From a model list: only current Gemini text models that can read an image. */
        fun keepForOcr(id: String): Boolean {
            if (!id.startsWith("gemini-")) return false
            val skip = listOf(
                "embedding", "tts", "image", "live", "banana", "lyria", "transcribe", "robotics", "omni",
                "translate", "computer-use", "native-audio", "aqa", "learnlm", "-exp", "gemini-1.", "gemini-2.0"
            )
            return skip.none { id.contains(it) }
        }

        fun parseText(body: String): String {
            val root = parseJsonOrNull(body).obj() ?: throw OcrError.Retryable("رد Gemini غير مفهوم")
            val cands = root["candidates"].arr()
            if (cands == null || cands.isEmpty()) {
                // Blocked prompt (promptFeedback.blockReason) -> no subtitle text for this image
                return ""
            }
            val parts = cands[0].obj()?.get("content").obj()?.get("parts").arr() ?: return ""
            val sb = StringBuilder()
            for (p in parts) {
                val o = p.obj() ?: continue
                if (o["thought"].bool() == true) continue
                o["text"].str()?.let { sb.append(it) }
            }
            return sb.toString()
        }

        /** GET models that support generateContent (for the model picker). */
        suspend fun listModels(http: HttpTransport, apiKey: String): List<String> {
            val resp = http.execute(
                HttpRequest(
                    url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                    method = "GET",
                    headers = mapOf("x-goog-api-key" to apiKey),
                    timeoutMs = 30_000
                )
            )
            if (resp.code != 200) {
                val e = GeminiErrors.parse(resp.text)
                throw GeminiErrors.classify(resp.code, e.status, e.message.ifBlank { shortBody(resp.text) }, null)
            }
            val models = parseJsonOrNull(resp.text).obj()?.get("models").arr() ?: return emptyList()
            return models.mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val methods = o["supportedGenerationMethods"].arr()?.mapNotNull { it.str() } ?: emptyList()
                if ("generateContent" !in methods) return@mapNotNull null
                o["name"].str()?.removePrefix("models/")
            }.filter { keepForOcr(it) }.sortedDescending()
        }
    }
}

internal object GeminiErrors {
    class Parsed(val message: String, val status: String, val retryDelayMs: Long?)

    fun parse(body: String): Parsed {
        val e = parseJsonOrNull(body).obj()?.get("error").obj() ?: return Parsed("", "", null)
        var delay: Long? = null
        e["details"].arr()?.forEach { d ->
            d.obj()?.get("retryDelay").str()?.let { s ->
                s.removeSuffix("s").toDoubleOrNull()?.let { delay = (it * 1000).toLong() }
            }
        }
        return Parsed(e["message"].str() ?: "", e["status"].str() ?: "", delay)
    }

    fun classify(code: Int, status: String, msg: String, retryDelayMs: Long?): OcrError {
        val m = msg.lowercase()
        return when {
            code == 400 && (m.contains("api key not valid") || m.contains("api_key_invalid")) ->
                OcrError.Fatal("مفتاح Gemini API غير صالح")
            code == 401 || code == 403 || status == "PERMISSION_DENIED" ->
                OcrError.Fatal("Gemini رفض المفتاح أو الصلاحية: $msg")
            code == 404 || status == "NOT_FOUND" -> OcrError.Fatal("الموديل غير موجود في Gemini: $msg")
            code == 429 && (m.contains("per day") || m.contains("perday") || m.contains("limit: 0")) ->
                OcrError.Fatal("انتهت الحصة اليومية لـ Gemini: $msg")
            code == 429 -> OcrError.Retryable("Gemini: تجاوز المعدل (429)", retryDelayMs ?: 10_000, true)
            code in 500..599 -> OcrError.Retryable("خطأ مؤقت في Gemini ($code)", retryDelayMs)
            code == 400 -> OcrError.Failed("Gemini رفض الطلب: $msg")
            else -> OcrError.Retryable("Gemini أعاد $code: $msg", retryDelayMs)
        }
    }
}

// =====================================================================================  OpenRouter

class OpenRouterEngine(
    private val http: HttpTransport,
    private val apiKey: String,
    model: String,
    private val profile: AiProfile = AiProfile.PRO,
    private val lang: SubLang = SubLang.ARABIC
) : OcrEngine {
    private val prompt: String = if (lang == SubLang.ENGLISH) Prompts.ENGLISH_PROMPT else profile.prompt
    val model: String = model.trim().ifBlank { DEFAULT_MODEL }
    override val kind = EngineKind.OPENROUTER
    override val cacheId = "openrouter:${this.model}:${profile.id}" + (if (lang == SubLang.ARABIC) "" else ":${lang.code}")

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل OpenRouter API Key أولاً")
    }

    fun buildBody(image: PreparedImage): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("type", "text"); put("text", prompt) }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${image.mime};base64," + Base64.getEncoder().encodeToString(image.bytes))
                        }
                    }
                }
            }
        }
        put("temperature", 0)
    }.toString()

    override suspend fun recognize(image: PreparedImage): String {
        val resp = http.execute(
            HttpRequest(
                url = "https://openrouter.ai/api/v1/chat/completions",
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "HTTP-Referer" to "https://openrouter.ai/",
                    "X-Title" to "HardSubArabicOCR Android"
                ),
                body = buildBody(image).toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                timeoutMs = 120_000
            )
        )
        val root = parseJsonOrNull(resp.text).obj()
        val errObj = root?.get("error").obj()
        if (resp.code == 200 && errObj == null) return parseText(root)
        val msg = errObj?.get("message").str() ?: shortBody(resp.text)
        val code = if (resp.code == 200) ((errObj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: 502) else resp.code
        throw when (code) {
            401 -> OcrError.Fatal("مفتاح OpenRouter غير صالح: $msg")
            402 -> OcrError.Fatal("رصيد OpenRouter غير كافٍ: $msg")
            403 -> OcrError.Fatal("OpenRouter رفض الطلب: $msg")
            404 -> OcrError.Fatal("الموديل غير موجود في OpenRouter: $msg")
            429 -> OcrError.Retryable("OpenRouter: تجاوز المعدل (429)", parseRetryAfter(resp.header("Retry-After")) ?: 10_000, true)
            400 -> OcrError.Failed("OpenRouter رفض الطلب: $msg")
            in 500..599 -> OcrError.Retryable("خطأ مؤقت في OpenRouter ($code): $msg")
            else -> OcrError.Retryable("OpenRouter أعاد $code: $msg")
        }
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.5-flash-lite"

        /** OpenRouter ids of the same current Gemini models (checked on openrouter.ai, 23 Nov 2026). */
        val MODELS = GeminiEngine.MODELS.map { it.copy(id = "google/" + it.id) }
        val SUGGESTED_MODELS = MODELS.map { it.id }

        fun parseText(root: JsonObject?): String {
            val choices = root?.get("choices").arr() ?: throw OcrError.Retryable("رد OpenRouter غير مفهوم")
            if (choices.isEmpty()) return ""
            val content = choices[0].obj()?.get("message").obj()?.get("content") ?: return ""
            content.str()?.let { return it }
            val arr = content.arr() ?: return ""
            return arr.mapNotNull { it.obj()?.get("text").str() }.joinToString("")
        }

        suspend fun listModels(http: HttpTransport): List<String> {
            val resp = http.execute(
                HttpRequest(url = "https://openrouter.ai/api/v1/models", method = "GET", timeoutMs = 30_000)
            )
            if (resp.code != 200) throw OcrError.Retryable("تعذر جلب قائمة الموديلات (${resp.code})")
            val data = parseJsonOrNull(resp.text).obj()?.get("data").arr() ?: return emptyList()
            return data.mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val inputs = o["architecture"].obj()?.get("input_modalities").arr()?.mapNotNull { it.str() }
                if (inputs != null && "image" !in inputs) return@mapNotNull null
                o["id"].str()
            }.sorted()
        }
    }
}
