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
                    "Accept" to "*/*"
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
                return result.fullText
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
        /** Newest first (verified Nov 2026). "Fetch models" adds whatever the key can really use. */
        val SUGGESTED_MODELS = listOf(
            "gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon",
            "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
            "gemini-3-flash-preview", "gemini-3.1-flash-lite"
        )
        const val DEFAULT_MODEL = "gemini-4-hydrogen"

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
            }.filter {
                !it.contains("embedding") && !it.contains("tts") && !it.contains("image") &&
                    !it.contains("live") && !it.contains("banana") && !it.contains("lyria")
            }.sortedDescending()
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
        const val DEFAULT_MODEL = "google/gemini-3.1-flash-lite"
        val SUGGESTED_MODELS = listOf(
            DEFAULT_MODEL, "google/gemini-4-hydrogen", "google/gemini-4-argon",
            "google/gemini-3.8-flash", "google/gemini-3.5-flash-lite", "google/gemini-3-flash-preview"
        )

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
