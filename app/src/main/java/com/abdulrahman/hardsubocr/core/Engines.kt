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
private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
private fun JsonElement?.long(): Long? = double()?.toLong()

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
    private val apiKey: String = LensProtocol.API_KEY,
    /** Extra looks at a suspicious picture (ClassicViews); null = one look only. */
    private val views: ImageResampler? = null,
    /** Receives one line per picture that got extra looks (what each look read and the choice). */
    private val looks: ((String) -> Unit)? = null
) : OcrEngine {
    override val kind = EngineKind.LENS
    override val cacheId = "lens:$language:$region:c$CLASSIC_OCR_VERSION" + (if (views != null) ":mv" else "")

    /**
     * One look at the whole picture; when its reading is suspicious (no Arabic, a short subtitle,
     * background fragments next to it, or a dialogue with a dash on some lines only) a few more looks
     * at the same pixels — enlarged, around the subtitle, its band — and the reading they agree on
     * (ClassicViews.vote), with the dialogue dashes the looks read on its lines (ClassicShape).
     */
    override suspend fun recognize(image: PreparedImage): String {
        val first = read(image)
        val text1 = first.readingText(image.width, image.height)
        val vr = views ?: return text1
        // the looks and the vote are made for Arabic subtitles
        if (language != "ar") return text1
        if (!ClassicViews.suspicious(text1, first.strays(image.width, image.height)) && !ClassicShape.dashMissing(text1)) return text1
        val plan = ClassicViews.plan(image.width, image.height, first.arabicBoxes(image.width, image.height))
        val readings = arrayListOf(text1)
        val names = arrayListOf("الصورة")
        for (v in plan) {
            val img = runCatching { vr.view(image, v.x0, v.y0, v.x1, v.y1, v.scale) }.getOrNull() ?: continue
            val named = PreparedImage(img.bytes, img.mime, img.width, img.height, image.name + " [" + v.name + "]")
            val r = try {
                read(named)
            } catch (e: OcrError.Fatal) {
                throw e
            } catch (e: OcrError) {
                continue // this look is lost; the others stand
            } catch (e: java.io.IOException) {
                continue
            }
            readings.add(r.readingText(img.width, img.height)); names.add(v.name)
        }
        val i = ClassicViews.vote(readings)
        // a dialogue line whose dash only another look read, on the same line, gets it back
        val chosen = if (i < 0) "" else readings[i]
        val shaped = if (i < 0 || !ClassicShape.dashMissing(chosen)) chosen
        else ClassicShape.restoreDashes(chosen, readings.filterIndexed { j, _ -> j != i })
        val line = names.indices.joinToString(" | ") { "${names[it]} «${readings[it].replace("\n", " ⏎ ").take(80)}»" } +
            " → " + (if (i < 0) "لا ترجمة" else names[i]) +
            (if (shaped != chosen) " + شرطة حوار من قراءة أخرى «${shaped.replace("\n", " ⏎ ").take(80)}»" else "")
        DiagLog.i("lens", "${image.name} looks: $line")
        looks?.invoke(line)
        return shaped
    }

    /** One Lens request: the parsed answer, or an OcrError for the HTTP problem. */
    private suspend fun read(image: PreparedImage): LensProtocol.Result {
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
                // diagnostic file: exactly what Lens returned for every image
                if (result.paragraphs.isNotEmpty()) DiagLog.i("lens", "${image.name} lines: " + result.describe())
                val text = result.readingText(image.width, image.height)
                if (text != result.fullText) DiagLog.i("lens", "${image.name} order: «${result.fullText.replace("\n", " ⏎ ").take(200)}» → «${text.replace("\n", " ⏎ ").take(200)}»")
                return result
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
    private val language: String = "ar",
    /**
     * DOCUMENT_TEXT_DETECTION (default) or TEXT_DETECTION — the two choices of the user's own
     * Vision tool. Google: TEXT_DETECTION is "optimized for areas of sparse text within a larger
     * image" and auto-detects only a subset of languages; DOCUMENT_TEXT_DETECTION auto-detects
     * the full set, returns confidence without asking, and in field tests found text in far more
     * photos. Same price.
     */
    private val document: Boolean = true,
    /**
     * Vision's OCR model: "" = Google's default ("builtin/stable"), or "builtin/weekly" — the
     * "bleeding edge release updated weekly" that Google documents for TEXT_DETECTION and
     * DOCUMENT_TEXT_DETECTION. If Google refuses it, the job carries on with the default model.
     */
    private val model: String = "",
    /**
     * For the focused second look (see [recognize]); null = one look only. Crops the same
     * picture — no other change to it.
     */
    private val cropper: ImageCropper? = null,
    /** Receives what Vision read for every image (the Vision report next to the SRT). */
    private val trace: ((Trace) -> Unit)? = null
) : OcrEngine {
    override val kind = EngineKind.VISION
    override val cacheId = "vision:$language:${if (document) "doc" else "text"}:${model.ifEmpty { "stable" }}:c$CLASSIC_OCR_VERSION"

    /** Set once Google refused [model]: every later request uses the default model. */
    private val modelRefused = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The model actually sent ("" = Google's default). */
    val modelInUse: String get() = if (modelRefused.get()) "" else model

    /** For the report: which model read the pictures. */
    fun modelNote(): String = when {
        model.isEmpty() -> "المعتاد (builtin/stable)"
        modelRefused.get() -> "الأحدث ($model) غير متاح لحسابك، فقرأ المعتاد (builtin/stable)"
        else -> "الأحدث ($model)"
    }

    private fun refuseModel(m: String, why: String) {
        if (modelRefused.compareAndSet(false, true)) {
            DiagLog.w("vision", "Google refused model $m: $why — default model from now on")
        }
    }

    /** One image in the Vision report. */
    class Trace(
        val name: String,
        /** Vision's own text, exactly as returned. */
        val vision: String,
        /** After ordering (before cleaning). */
        val text: String,
        /** Mean confidence of the subtitle's words, 0 when Vision gave none. */
        val confidence: Double,
        /** The focused second look, when there was one. */
        val second: String? = null,
        val secondChosen: Boolean = false,
        /** The text given on when a dialogue dash of one look was put back on the kept one (ClassicShape). */
        val restored: String? = null
    )

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل مفتاح Google Cloud Vision أولاً")
    }

    fun buildBody(image: PreparedImage, useModel: String = modelInUse): String = buildJsonObject {
        putJsonArray("requests") {
            addJsonObject {
                putJsonObject("image") { put("content", Base64.getEncoder().encodeToString(image.bytes)) }
                putJsonArray("features") {
                    addJsonObject {
                        put("type", if (document) "DOCUMENT_TEXT_DETECTION" else "TEXT_DETECTION")
                        if (useModel.isNotEmpty()) put("model", useModel)
                    }
                }
                putJsonObject("imageContext") {
                    // the subtitle language alone, as in the user's own Vision tool; Google: for
                    // the best Arabic quality the hint must be given explicitly
                    putJsonArray("languageHints") { add(JsonPrimitive(language)) }
                    // TEXT_DETECTION gives word confidence only when asked (DOCUMENT always does)
                    if (!document) putJsonObject("textDetectionParams") { put("enableTextDetectionConfidenceScore", true) }
                }
            }
        }
    }.toString()

    /** One Vision request: the words with their boxes, and Vision's own text. */
    private suspend fun annotate(image: PreparedImage): Answer {
        val m = modelInUse
        val resp = http.execute(
            HttpRequest(
                url = ENDPOINT,
                headers = mapOf("x-goog-api-key" to apiKey),
                body = buildBody(image, m).toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                timeoutMs = 60_000
            )
        )
        if (resp.code == 200) {
            // a model Google does not accept comes back as this image's INVALID_ARGUMENT
            val err = imageError(resp.text)
            if (m.isNotEmpty() && err != null && err.first == 3 && err.second.contains("model", ignoreCase = true)) {
                refuseModel(m, err.second)
                return annotate(image)
            }
            return parseAnswer(resp.text)
        }
        val e = GoogleApiError.parse(resp.text)
        if (m.isNotEmpty() && resp.code == 400 && e.message.contains("model", ignoreCase = true)) {
            refuseModel(m, e.message)
            return annotate(image)
        }
        throw classify(resp.code, e.status, e.message.ifBlank { shortBody(resp.text) }, e.reason, parseRetryAfter(resp.header("Retry-After")))
    }

    /**
     * Two-stage reading, used by OCR systems to read a line next to unrelated text:
     * 1) the whole picture, as Lens gets it;
     * 2) only when Vision's own answer shows the subtitle was read together with other text on
     *    its rows (crew names beside or touching the box) or unsure: the subtitle's area, located
     *    by Vision's own word boxes, cropped from the same picture and read again. The better
     *    reading of the subtitle is kept (more of it, without the noise, or surer).
     * Pictures with the subtitle alone are read once, exactly as before.
     */
    override suspend fun recognize(image: PreparedImage): String {
        val first = annotate(image)
        val text1 = textOf(first, image.name)
        val conf1 = VisionLayout.confidence(first.words)
        val firstOnly = { trace?.invoke(Trace(image.name, first.full, text1, conf1)); text1 }
        val crop = cropper ?: return firstOnly()
        val area = VisionLayout.focusArea(first.words, image.width, image.height) ?: return firstOnly()
        val part = runCatching { crop.crop(image, area[0], area[1], area[2], area[3]) }.getOrNull() ?: return firstOnly()
        val name2 = image.name + " [focus]"
        val second = try {
            annotate(PreparedImage(part.bytes, part.mime, part.width, part.height, name2))
        } catch (e: OcrError.Fatal) {
            throw e
        } catch (e: OcrError) {
            null // the first reading stands
        } catch (e: java.io.IOException) {
            null
        } ?: return firstOnly()
        val text2 = textOf(second, name2)
        val better = text2.isNotBlank() && VisionLayout.secondIsBetter(first.words, second.words)
        DiagLog.i(
            "vision",
            "${image.name} focus ${area.joinToString(",")}: «${oneLine(text1)}» | «${oneLine(text2)}» → ${if (better) "focus" else "first"}"
        )
        val kept = if (better) text2 else text1
        // a dialogue whose kept look lost a line's dash that the other look read in its place
        var result = kept
        if (ClassicShape.dashMissing(kept)) {
            val donor = if (better) text1 else text2
            val checked = VisionLayout.dashedLines(if (better) first.words else second.words)
            result = ClassicShape.restoreDashes(kept, listOf(donor), checked)
            if (result != kept) DiagLog.i("vision", "${image.name} dash from the other look: «${oneLine(kept)}» → «${oneLine(result)}»")
        }
        trace?.invoke(Trace(image.name, first.full, text1, conf1, text2, better, result.takeIf { it != kept }))
        return result
    }

    /** Words (with boxes) of one answer and Vision's own text of it. */
    class Answer(val words: List<VisionLayout.Word>, val full: String)

    companion object {
        const val ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"

        /** google.rpc code and message of the first response's own error, if it has one. */
        fun imageError(body: String): Pair<Int, String>? {
            val r = parseJsonOrNull(body).obj()?.get("responses").arr()?.firstOrNull().obj() ?: return null
            val err = r["error"].obj() ?: return null
            val code = (err["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            return code to (err["message"].str() ?: "")
        }

        /** Full text of the first response; a per-image error is raised like an HTTP one. */
        fun parseText(body: String, name: String = ""): String = textOf(parseAnswer(body), name)

        /** Words and Vision's own text of the first response; a per-image error is raised like an HTTP one. */
        fun parseAnswer(body: String): Answer {
            val root = parseJsonOrNull(body).obj() ?: throw OcrError.Retryable("رد Google Vision غير مفهوم")
            val r = root["responses"].arr()?.firstOrNull().obj() ?: return Answer(emptyList(), "")
            r["error"].obj()?.let { err ->
                val code = (err["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val msg = err["message"].str() ?: ""
                throw classify(rpcToHttp(code), "", msg, null, null)
            }
            val full = (r["fullTextAnnotation"].obj()?.get("text").str()
                ?: r["textAnnotations"].arr()?.firstOrNull().obj()?.get("description").str()
                ?: return Answer(emptyList(), "")).trimEnd('\n', ' ')
            return Answer(VisionLayout.wordsOf(r), full)
        }

        /**
         * Vision's own text repeats words read twice and lays blocks out left to right: the text is
         * rebuilt from the word boxes (VisionLayout), its own text is the fallback.
         */
        fun textOf(a: Answer, name: String = ""): String {
            // diagnostic file: exactly what Vision returned for every image
            if (a.words.isNotEmpty()) DiagLog.i("vision", "$name words: " + VisionLayout.describe(a.words))
            val rebuilt = VisionLayout.rebuild(a.words) ?: return a.full
            if (rebuilt != a.full) DiagLog.i("vision", "$name layout: «${oneLine(a.full)}» → «${oneLine(rebuilt)}»")
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

// =====================================================================================  Gemini and OpenRouter

/** How much the model thinks before answering (Gemini thinkingLevel / OpenRouter reasoning.effort). */
enum class ThinkingLevel(val api: String?) { DEFAULT(null), MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high") }

/**
 * Reading style of the AI engines — the prompt and the processing that goes with it. Gemini and
 * OpenRouter use the same style with the same request, so the same model reads the same way through
 * either of them.
 */
enum class AiProfile(
    val id: String,
    val prompt: String,
    val clean: CleanMode,
    val merge: MergeMode,
    val enhanceImages: Boolean,
    val stabilize: Boolean
) {
    /**
     * Default: ocr_windows_v2.6.py's reading with what it lacked for an exact copy (Prompts.EXACT_PROMPT):
     * the tashkeel the picture clearly shows is copied instead of treated as optional, the subtitle's
     * lines and dialogue dashes are kept. Everything else as v2.6: subtitle-band image enhancement,
     * diacritics guard (CleanMode.EXACT), consecutive frames agreeing on tashkeel, merge of split pieces only.
     */
    EXACT("exact", Prompts.EXACT_PROMPT, CleanMode.EXACT, MergeMode.STRICT, true, true),

    /** ocr_windows_v2.6.py exactly (its prompt and cleaning). Kept for comparison; not offered in the app. */
    V26("v26", Prompts.GEMINI_V26_PROMPT, CleanMode.V26, MergeMode.STRICT, true, true),

    /** ocr_windows_v2.1.py: long ultra-precise prompt, clean_ocr_output, merge rules 2A–2E. */
    V21("v21", Prompts.GEMINI_SYSTEM_PROMPT, CleanMode.PRECISE, MergeMode.STRICT, false, false),

    /** HardSubArabicOCR_v2_PRO.py (OpenRouter): short Arabic prompt, arabic_filter, merge_entries. */
    PRO("pro", Prompts.OPENROUTER_PROMPT, CleanMode.PRO, MergeMode.PRO, false, false)
}

/**
 * What the AI engines used, for the job's diagnostic log: images answered and tokens (Gemini
 * usageMetadata / OpenRouter usage). Thinking tokens show what the thinking level costs in time.
 */
class AiUsage {
    val answers = java.util.concurrent.atomic.AtomicInteger()
    val promptTokens = java.util.concurrent.atomic.AtomicLong()
    val outputTokens = java.util.concurrent.atomic.AtomicLong()
    val thinkingTokens = java.util.concurrent.atomic.AtomicLong()
    /** OpenRouter's cost in credits (USD), when it reports it; micro-units. */
    val costMicros = java.util.concurrent.atomic.AtomicLong()

    fun add(prompt: Long?, output: Long?, thinking: Long?, cost: Double? = null) {
        answers.incrementAndGet()
        prompt?.let { promptTokens.addAndGet(it) }
        output?.let { outputTokens.addAndGet(it) }
        thinking?.let { thinkingTokens.addAndGet(it) }
        cost?.let { costMicros.addAndGet((it * 1_000_000).toLong()) }
    }

    fun summary(): String? {
        val n = answers.get()
        if (n == 0) return null
        val sb = StringBuilder()
        sb.append(n).append(" صورة • لكل صورة في المتوسط: إدخال ").append(promptTokens.get() / n)
            .append(" وحدة، إخراج ").append(outputTokens.get() / n).append("، تفكير ").append(thinkingTokens.get() / n)
        if (costMicros.get() > 0) sb.append(" • التكلفة ").append("%.4f".format(java.util.Locale.US, costMicros.get() / 1e6)).append("$")
        return sb.toString()
    }
}

/**
 * The one request recipe of both AI engines (ocr_windows_v2.6.py's, with Google's current guidance):
 * a single user message holding the picture and then the instructions (Google: one picture works best
 * placed before the text; Gemini 3.8 and later also require text in the final user turn), temperature 0
 * and top-p 1 as v2.6 (newer models ignore them), the picture at Google's high detail level, and the
 * same thinking level.
 */
object AiRecipe {
    /** What the model is told, for this style and subtitle language. */
    fun prompt(profile: AiProfile, lang: SubLang): String = if (lang == SubLang.ENGLISH) Prompts.ENGLISH_PROMPT else profile.prompt

    fun cacheId(engine: String, model: String, profile: AiProfile, thinking: ThinkingLevel, high: Boolean, lang: SubLang): String =
        "$engine:$model:${profile.id}:t=${thinking.api ?: "auto"}:${if (high) "hi" else "def"}" + (if (lang == SubLang.ARABIC) "" else ":${lang.code}")
}

class GeminiEngine(
    private val http: HttpTransport,
    private val apiKey: String,
    model: String,
    thinking: ThinkingLevel = ThinkingLevel.LOW,
    private val profile: AiProfile = AiProfile.EXACT,
    highResolution: Boolean = true,
    private val lang: SubLang = SubLang.ARABIC,
    /** Tokens used, for the job's log (optional). */
    val usage: AiUsage = AiUsage()
) : OcrEngine {
    val model: String = model.trim().removePrefix("models/")
    override val kind = EngineKind.GEMINI
    override val cacheId = AiRecipe.cacheId("gemini", this.model, profile, thinking, highResolution, lang)
    private val prompt = AiRecipe.prompt(profile, lang)

    /** Lowered automatically when a model rejects it (Gemini 4 and 3.8 have no "minimal"). */
    @Volatile
    var thinkingLevel: String? = thinking.api
        private set

    @Volatile
    var mediaHigh: Boolean = highResolution
        private set

    /** temperature 0 / topP 1 (v2.6); dropped if a model ever refuses them (Gemini 4 ignores them). */
    @Volatile
    var sampling: Boolean = true
        private set

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل مفتاح Gemini API أولاً")
        if (this.model.isBlank()) throw OcrError.Fatal("اختر نموذج Gemini")
    }

    fun buildBody(image: PreparedImage, thinking: String?, high: Boolean): String = buildJsonObject {
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
                    addJsonObject { put("text", prompt) }
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
            if (sampling) {
                put("temperature", 0.0)
                put("topP", 1.0)
            }
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
        if (resp.code == 200) {
            val root = parseJsonOrNull(resp.text).obj()
            root?.get("usageMetadata").obj()?.let { u ->
                usage.add(u["promptTokenCount"].long(), u["candidatesTokenCount"].long(), u["thoughtsTokenCount"].long())
            }
            return parseText(resp.text)
        }

        val err = GeminiErrors.parse(resp.text)
        val msg = err.message.ifBlank { shortBody(resp.text) }
        val low = msg.lowercase()
        if (resp.code == 400 && level != null && low.contains("thinking")) {
            // minimal -> low -> (no thinking config)
            thinkingLevel = if (level == "minimal") "low" else null
            DiagLog.w("gemini", "$model refused thinking level $level: $msg")
            throw OcrError.Retryable("النموذج لا يدعم مستوى التفكير «$level» — تعديل تلقائي", retryAfterMs = 0)
        }
        if (resp.code == 400 && high && (low.contains("media_resolution") || low.contains("mediaresolution") ||
                low.contains("media resolution"))
        ) {
            mediaHigh = false
            DiagLog.w("gemini", "$model refused mediaResolution HIGH: $msg")
            throw OcrError.Retryable("النموذج لا يقبل تحديد دقة الصورة — إعادة بدقته الافتراضية", retryAfterMs = 0)
        }
        if (resp.code == 400 && sampling && (low.contains("temperature") || low.contains("top_p") || low.contains("topp"))) {
            sampling = false
            DiagLog.w("gemini", "$model refused temperature/topP: $msg")
            throw OcrError.Retryable("النموذج لا يقبل ضبط العشوائية — إعادة بإعداده الافتراضي", retryAfterMs = 0)
        }
        throw GeminiErrors.classify(resp.code, err.status, msg, err.retryDelayMs)
    }

    companion object {
        /**
         * Current Gemini models that read images (Gemini API, checked 26 Nov 2026), in the order shown.
         * Older ones (3.6 Flash, 3 Flash Preview, 3.1 Flash-Lite, 2.x) are no longer offered here.
         */
        val MODELS = listOf(
            ModelInfo("gemini-4-hydrogen", "Gemini 4 Hydrogen", "Gemini 4", "الأسرع في Gemini 4 — مناسب للاستخراج اليومي"),
            ModelInfo("gemini-4-neon", "Gemini 4 Neon", "Gemini 4", "بين Hydrogen وCarbon: أقوى، وأبطأ وأغلى من Hydrogen"),
            ModelInfo("gemini-4-carbon", "Gemini 4 Carbon", "Gemini 4", "أقوى استدلال في Gemini 4 — الأبطأ"),
            ModelInfo("gemini-4-argon", "Gemini 4 Argon", "Gemini 4", "أول نماذج Gemini 4"),
            ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", "Gemini 3", "أحدث Flash في Gemini 3"),
            ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Gemini 3", "الأخف والأرخص — للمجلدات الكبيرة")
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
            code == 404 || status == "NOT_FOUND" -> OcrError.Fatal("النموذج غير موجود في Gemini: $msg")
            code == 429 && (m.contains("per day") || m.contains("perday") || m.contains("limit: 0")) ->
                OcrError.Fatal("انتهت الحصة اليومية لـ Gemini: $msg")
            // Google's own wait when it gives one (RetryInfo), else the pipeline's growing wait
            code == 429 -> OcrError.Retryable("Gemini: طلبات أكثر من حصة الدقيقة (429)", retryDelayMs, true)
            code in 500..599 -> OcrError.Retryable("خطأ مؤقت في Gemini ($code)", retryDelayMs)
            code == 400 -> OcrError.Failed("Gemini رفض الطلب: $msg")
            else -> OcrError.Retryable("Gemini أعاد $code: $msg", retryDelayMs)
        }
    }
}

class OpenRouterEngine(
    private val http: HttpTransport,
    private val apiKey: String,
    model: String,
    thinking: ThinkingLevel = ThinkingLevel.LOW,
    private val profile: AiProfile = AiProfile.EXACT,
    private val highResolution: Boolean = true,
    private val lang: SubLang = SubLang.ARABIC,
    /** Tokens and cost, for the job's log (optional). */
    val usage: AiUsage = AiUsage()
) : OcrEngine {
    private val prompt = AiRecipe.prompt(profile, lang)
    val model: String = model.trim().ifBlank { DEFAULT_MODEL }
    override val kind = EngineKind.OPENROUTER
    override val cacheId = AiRecipe.cacheId("openrouter", this.model, profile, thinking, highResolution, lang)

    /** reasoning.effort: OpenRouter passes it to Gemini as its thinkingLevel (a level the model lacks → the nearest). */
    @Volatile
    var effort: String? = thinking.api
        private set

    /** temperature 0 / top_p 1, dropped if the model refuses them (some reasoning models do). */
    @Volatile
    var sampling: Boolean = true
        private set

    init {
        if (apiKey.isBlank()) throw OcrError.Fatal("أدخل مفتاح OpenRouter أولاً")
    }

    fun buildBody(image: PreparedImage): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:${image.mime};base64," + Base64.getEncoder().encodeToString(image.bytes))
                            if (highResolution) put("detail", "high")
                        }
                    }
                    addJsonObject { put("type", "text"); put("text", prompt) }
                }
            }
        }
        if (sampling) {
            put("temperature", 0.0)
            put("top_p", 1.0)
        }
        // without it OpenRouter lets the model think at its own default (Gemini 4 Hydrogen: high — far slower)
        effort?.let { e -> putJsonObject("reasoning") { put("effort", e); put("exclude", true) } }
    }.toString()

    override suspend fun recognize(image: PreparedImage): String {
        val resp = http.execute(
            HttpRequest(
                url = "https://openrouter.ai/api/v1/chat/completions",
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "HTTP-Referer" to APP_URL,
                    "X-OpenRouter-Title" to APP_TITLE,
                    "X-Title" to APP_TITLE
                ),
                body = buildBody(image).toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                timeoutMs = 120_000
            )
        )
        val root = parseJsonOrNull(resp.text).obj()
        // an error before the answer (any status), or one that cut the answer (200, finish_reason "error")
        val errObj = root?.get("error").obj()
            ?: root?.get("choices").arr()?.firstOrNull().obj()?.takeIf { it["finish_reason"].str() == "error" }?.get("error").obj()
        if (resp.code == 200 && errObj == null) {
            root?.get("usage").obj()?.let { u ->
                usage.add(
                    u["prompt_tokens"].long(), u["completion_tokens"].long(),
                    u["completion_tokens_details"].obj()?.get("reasoning_tokens").long(), u["cost"].double()
                )
            }
            return parseText(root)
        }
        throw classify(resp, errObj)
    }

    /** What went wrong, in the user's words, with the provider behind it when OpenRouter names it. */
    private fun classify(resp: HttpResponse, errObj: JsonObject?): OcrError {
        val e = parseError(errObj, resp.text)
        val code = if (resp.code == 200) (e.code ?: 502) else resp.code
        val by = e.provider?.let { " — $it" } ?: ""
        return when {
            code == 401 -> OcrError.Fatal("مفتاح OpenRouter غير صالح: ${e.message}")
            code == 402 && e.limitSource == "openrouter_in_flight_budget" ->
                OcrError.Retryable("OpenRouter: طلبات جارية تتجاوز الرصيد المتاح مؤقتًا (402)", parseRetryAfter(resp.header("Retry-After")), true)
            code == 402 -> OcrError.Fatal("رصيد OpenRouter أو حد هذا المفتاح لا يكفي (402): ${e.message}")
            code == 403 -> OcrError.Fatal("OpenRouter رفض الطلب (403): ${e.message}")
            code == 404 -> OcrError.Fatal("النموذج غير موجود في OpenRouter أو لا يقرأ الصور (404): ${e.message}")
            code == 429 && e.limitSource == "openrouter_free_tier_daily" ->
                OcrError.Fatal("انتهى حد النماذج المجانية اليومي في OpenRouter: ${e.message}")
            code == 429 && e.limitSource?.startsWith("openrouter_free_tier") == true -> OcrError.Retryable(
                "OpenRouter: حد الدقيقة للنماذج المجانية (429)",
                parseRetryAfter(resp.header("Retry-After")) ?: resetIn(resp.header("X-RateLimit-Reset")), true
            )
            // a paid model: OpenRouter sets no limit there — the provider serving it (Google AI Studio /
            // Vertex) is out of capacity for now; OpenRouter already tried the others
            code == 429 -> OcrError.Retryable(
                "OpenRouter: مزوّد النموذج مزدحم الآن (429$by) — ليس رصيدك",
                parseRetryAfter(resp.header("Retry-After")), true
            )
            code == 400 && sampling && e.message.lowercase().let { it.contains("temperature") || it.contains("top_p") } -> {
                sampling = false
                DiagLog.w("openrouter", "$model refused temperature/top_p: ${e.message}")
                OcrError.Retryable("النموذج لا يقبل ضبط العشوائية — إعادة بإعداده الافتراضي", retryAfterMs = 0)
            }
            code == 400 && effort != null && e.message.lowercase().let { it.contains("reasoning") || it.contains("thinking") } -> {
                DiagLog.w("openrouter", "$model refused reasoning effort $effort: ${e.message}")
                effort = null
                OcrError.Retryable("النموذج لا يقبل مستوى التفكير — إعادة بإعداده الافتراضي", retryAfterMs = 0)
            }
            code == 400 -> OcrError.Failed("OpenRouter رفض الطلب$by: ${e.message}")
            code == 408 -> OcrError.Retryable("OpenRouter: انتهت مهلة المزوّد (408$by)")
            code in 500..599 -> OcrError.Retryable("خطأ مؤقت عند مزوّد النموذج على OpenRouter ($code$by): ${e.message}")
            else -> OcrError.Retryable("OpenRouter أعاد $code$by: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_MODEL = "google/gemini-3.5-flash-lite"

        /** App attribution sent to OpenRouter (HTTP-Referer / X-OpenRouter-Title). */
        const val APP_URL = "https://github.com/Nassr199/OCR"
        const val APP_TITLE = "HardSub Arabic OCR"

        /** OpenRouter ids of the same current Gemini models (checked on openrouter.ai, 26 Nov 2026). */
        val MODELS = GeminiEngine.MODELS.map { it.copy(id = "google/" + it.id) }
        val SUGGESTED_MODELS = MODELS.map { it.id }

        /** OpenRouter's error object: message, code, and metadata (provider_name, raw, limit_source). */
        class Err(val message: String, val code: Int?, val provider: String?, val limitSource: String?)

        fun parseError(err: JsonObject?, body: String): Err {
            if (err == null) return Err(shortBody(body), null, null, null)
            val meta = err["metadata"].obj()
            val raw = meta?.get("raw")?.let { r -> r.str() ?: r.toString() }?.let { shortBody(it, 160) }
            val base = err["message"].str() ?: shortBody(body)
            val code = (err["code"] as? JsonPrimitive)?.content?.toIntOrNull()
            return Err(
                if (raw != null && raw !in base) "$base ($raw)" else base,
                code,
                meta?.get("provider_name").str(),
                meta?.get("limit_source").str()
            )
        }

        /** X-RateLimit-Reset (ms since the epoch) → how long to wait. */
        private fun resetIn(v: String?): Long? {
            val t = v?.trim()?.toLongOrNull() ?: return null
            return (t - System.currentTimeMillis()).coerceIn(1_000, 120_000)
        }

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
            if (resp.code != 200) throw OcrError.Retryable("تعذر جلب قائمة النماذج (${resp.code})")
            val data = parseJsonOrNull(resp.text).obj()?.get("data").arr() ?: return emptyList()
            return data.mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val inputs = o["architecture"].obj()?.get("input_modalities").arr()?.mapNotNull { it.str() }
                if (inputs != null && "image" !in inputs) return@mapNotNull null
                o["id"].str()
            }.sorted()
        }

        /** GET /api/v1/key: is the key valid, its limit and what it used (for «اختبار المفتاح»). */
        class KeyInfo(val label: String?, val limit: Double?, val remaining: Double?, val usage: Double?, val freeTier: Boolean?)

        suspend fun keyInfo(http: HttpTransport, apiKey: String): KeyInfo {
            val resp = http.execute(
                HttpRequest(
                    url = "https://openrouter.ai/api/v1/key", method = "GET",
                    headers = mapOf("Authorization" to "Bearer $apiKey"), timeoutMs = 30_000
                )
            )
            val root = parseJsonOrNull(resp.text).obj()
            if (resp.code != 200) {
                val e = parseError(root?.get("error").obj(), resp.text)
                throw if (resp.code == 401 || resp.code == 403) OcrError.Fatal("مفتاح OpenRouter غير صالح: ${e.message}")
                else OcrError.Retryable("تعذر فحص المفتاح (${resp.code}): ${e.message}")
            }
            val d = root?.get("data").obj() ?: throw OcrError.Retryable("رد OpenRouter غير مفهوم")
            return KeyInfo(d["label"].str(), d["limit"].double(), d["limit_remaining"].double(), d["usage"].double(), d["is_free_tier"].bool())
        }
    }
}
