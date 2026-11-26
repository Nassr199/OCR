package com.abdulrahman.hardsubocr.data

import android.content.Context
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.GeminiEngine
import com.abdulrahman.hardsubocr.core.MergeMode
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubLang
import com.abdulrahman.hardsubocr.core.SubStyle
import com.abdulrahman.hardsubocr.core.ThinkingLevel
import com.abdulrahman.hardsubocr.core.isClassicOcr

enum class SourceMode { IMAGE, FOLDER, MULTI, VIDEO }

/** AUTO = the behaviour of the Windows program that originally used this engine. */
enum class Choice { AUTO, PRO, STRICT_OR_PRECISE, NONE }

/** Video: only save the subtitle images (VideoSubFinder-style), or save them and read them too. */
enum class VideoMode { FRAMES_ONLY, FRAMES_AND_TEXT }

/** Image enhancement before sending (v2.6 prepare_image_for_ocr): the switch in the AI engine panel. */
enum class EnhanceChoice { AUTO, ON, OFF }

/** Normalised (0..1) subtitle region of the video frame. */
data class RoiRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized(): RoiRect {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(0f, 1f)
        return if (r - l < 0.02f || b - t < 0.02f) DEFAULT else RoiRect(l, t, r, b)
    }

    companion object {
        val DEFAULT = RoiRect(0.02f, 0.70f, 0.98f, 0.98f)
    }
}

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("hardsubocr_settings", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun putFloat(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    private inline fun <reified T : Enum<T>> enumOf(k: String, d: T): T =
        runCatching { enumValueOf<T>(str(k, d.name)) }.getOrDefault(d)

    var engine: EngineKind
        get() = enumOf("engine", EngineKind.LENS)
        set(v) = put("engine", v.name)

    var geminiKey: String
        get() = SecureStore.decrypt(sp.getString("gemini_key", null))
        set(v) = put("gemini_key", SecureStore.encrypt(v.trim()))

    var openRouterKey: String
        get() = SecureStore.decrypt(sp.getString("openrouter_key", null))
        set(v) = put("openrouter_key", SecureStore.encrypt(v.trim()))

    /** Google Cloud API key with Cloud Vision API enabled. */
    var visionKey: String
        get() = SecureStore.decrypt(sp.getString("vision_key", null))
        set(v) = put("vision_key", SecureStore.encrypt(v.trim()))

    /** Google Vision reading: DOCUMENT_TEXT_DETECTION (true, default) or TEXT_DETECTION. */
    var visionDocument: Boolean
        get() = sp.getBoolean("vision_document", true)
        set(v) = putBool("vision_document", v)

    /**
     * Google Lens / Vision text shape: false (default) = as on screen (lines, dialogue dashes,
     * punctuation); true = one line without tashkeel (the PRO program's cleaning).
     */
    var classicOneLine: Boolean
        get() = sp.getBoolean("classic_one_line", false)
        set(v) = putBool("classic_one_line", v)

    /** Google Lens: extra looks at a picture whose first reading is suspicious (ClassicViews). */
    var lensMultiView: Boolean
        get() = sp.getBoolean("lens_multiview", true)
        set(v) = putBool("lens_multiview", v)

    /** Google Vision OCR model: "" = Google's default (builtin/stable), or "builtin/weekly". */
    var visionModel: String
        get() = str("vision_model", "")
        set(v) = put("vision_model", v.trim())

    var geminiModel: String
        get() = str("gemini_model", GeminiEngine.DEFAULT_MODEL).ifBlank { GeminiEngine.DEFAULT_MODEL }
        set(v) = put("gemini_model", v.trim())

    var openRouterModel: String
        get() = str("openrouter_model", OpenRouterEngine.DEFAULT_MODEL)
        set(v) = put("openrouter_model", v.trim())

    /**
     * Thinking level of Gemini and OpenRouter (the same for both). Default «minimal» — the fastest; a model
     * without it (Gemini 4, 3.8 Flash) is raised to «low» automatically. 2.7.8 had dropped «minimal» and
     * read a saved one as «low»; 2.7.9 gives it back as it was saved.
     */
    var thinking: ThinkingLevel
        get() = enumOf("thinking", ThinkingLevel.MINIMAL)
        set(v) = put("thinking", v.name)

    var workersLens: Int
        get() = sp.getInt("workers_lens", 4)
        set(v) = putInt("workers_lens", v)

    var workersAi: Int
        get() = sp.getInt("workers_ai", 4)
        set(v) = putInt("workers_ai", v)

    var retries: Int
        get() = sp.getInt("retries", 6)
        set(v) = putInt("retries", v)

    var mergeChoice: Choice
        get() = enumOf("merge_choice", Choice.AUTO)
        set(v) = put("merge_choice", v.name)

    /**
     * Text form of Gemini AND OpenRouter — one setting, so the same model reads the same way through
     * either. Default V26 (ocr_windows_v2.6.py); 2.7.8's «EXACT» no longer exists and reads as V26.
     */
    var aiProfile: AiProfile
        get() = enumOf("ai_style", AiProfile.V26)
        set(v) = put("ai_style", v.name)

    var enhanceChoice: EnhanceChoice
        get() = enumOf("enhance_choice", EnhanceChoice.AUTO)
        set(v) = put("enhance_choice", v.name)


    /** Last crash/error report shown on the next start (for screenshots). */
    var lastErrorReport: String?
        get() = sp.getString("last_error_report", null)
        set(v) = put("last_error_report", v)

    var stripDiacritics: Boolean
        get() = sp.getBoolean("strip_diacritics", false)
        set(v) = putBool("strip_diacritics", v)

    var bom: Boolean
        get() = sp.getBoolean("bom", true)
        set(v) = putBool("bom", v)

    var crlf: Boolean
        get() = sp.getBoolean("crlf", false)
        set(v) = putBool("crlf", v)

    var overwrite: Boolean
        get() = sp.getBoolean("overwrite", true)
        set(v) = putBool("overwrite", v)

    var resume: Boolean
        get() = sp.getBoolean("resume", true)
        set(v) = putBool("resume", v)

    var lensLanguage: String
        get() = str("lens_lang", "ar")
        set(v) = put("lens_lang", v.trim().ifEmpty { "ar" })

    var lensRegion: String
        get() = str("lens_region", "US")
        set(v) = put("lens_region", v.trim().ifEmpty { "US" })

    var sourceMode: SourceMode
        get() = enumOf("source_mode", SourceMode.FOLDER)
        set(v) = put("source_mode", v.name)

    /** Persisted output folder, serialized FolderRef. */
    var outputFolder: String?
        get() = sp.getString("output_folder", null)
        set(v) = put("output_folder", v)

    // ---------------------------------------------------------------- last selections
    var lastFolder: String?
        get() = sp.getString("last_folder", null)
        set(v) = put("last_folder", v)

    var lastMulti: String?
        get() = sp.getString("last_multi", null)
        set(v) = put("last_multi", v)

    var lastVideo: String?
        get() = sp.getString("last_video", null)
        set(v) = put("last_video", v)

    var lastImage: String?
        get() = sp.getString("last_image", null)
        set(v) = put("last_image", v)

    // ---------------------------------------------------------------- video
    var roi: RoiRect
        get() = RoiRect(
            sp.getFloat("roi_l", RoiRect.DEFAULT.left), sp.getFloat("roi_t", RoiRect.DEFAULT.top),
            sp.getFloat("roi_r", RoiRect.DEFAULT.right), sp.getFloat("roi_b", RoiRect.DEFAULT.bottom)
        )
        set(v) {
            putFloat("roi_l", v.left); putFloat("roi_t", v.top); putFloat("roi_r", v.right); putFloat("roi_b", v.bottom)
        }

    var subLang: SubLang
        get() = enumOf("sub_lang", SubLang.ARABIC)
        set(v) = put("sub_lang", v.name)

    // v2.1: new detector → new keys so the old 175 / 0.32 defaults are not carried over
    var brightThreshold: Int
        get() = sp.getInt("v_bright2", 150)
        set(v) = putInt("v_bright2", v)

    var contrastDelta: Int
        get() = sp.getInt("v_contrast2", 60)
        set(v) = putInt("v_contrast2", v)

    var darkText: Boolean
        get() = sp.getBoolean("v_dark", false)
        set(v) = putBool("v_dark", v)

    val subStyle: SubStyle get() = if (darkText) SubStyle.DARK else SubStyle.LIGHT

    var analyzeEvery: Int
        get() = sp.getInt("v_every", 1)
        set(v) = putInt("v_every", v)

    var minDurationMs: Int
        get() = sp.getInt("v_min_ms2", 80)
        set(v) = putInt("v_min_ms2", v)

    var videoMode: VideoMode
        get() = enumOf("v_mode", VideoMode.FRAMES_AND_TEXT)
        set(v) = put("v_mode", v.name)

    var videoEngine: com.abdulrahman.hardsubocr.video.VideoEngine
        get() = enumOf("v_engine", com.abdulrahman.hardsubocr.video.VideoEngine.ENSEMBLE).let {
            // 2.2 is no longer offered on its own (it still works inside "the most accurate")
            if (it == com.abdulrahman.hardsubocr.video.VideoEngine.V22) com.abdulrahman.hardsubocr.video.VideoEngine.V21 else it
        }
        set(v) = put("v_engine", v.name)

    /** Vulkan compute acceleration (Adreno…), auto-verified against the CPU. */
    var gpu: Boolean
        get() = sp.getBoolean("v_gpu", true)
        set(v) = putBool("v_gpu", v)

    /** Re-join one line that was cut into two pieces (flash, scene cut, false change). */
    var repairSplits: Boolean
        get() = sp.getBoolean("v_repair", true)
        set(v) = putBool("v_repair", v)

    /** Background removal by combining several frames of the same subtitle. */
    /** Also save black-on-white images (VideoSubFinder TXTImages). */
    var saveClean: Boolean
        get() = sp.getBoolean("v_clean", false)
        set(v) = putBool("v_clean", v)

    fun scanParams(): ScanParams = ScanParams.preset(
        // composite = false: every subtitle image is ONE real frame (merging frames erased lines)
        subLang, subStyle, brightThreshold, contrastDelta, repairSplits, false, minDurationMs.toLong()
    )

    // ---------------------------------------------------------------- derived
    fun profileFor(kind: EngineKind): AiProfile? = when (kind) {
        EngineKind.GEMINI, EngineKind.OPENROUTER -> aiProfile
        EngineKind.LENS, EngineKind.VISION -> null
    }

    /**
     * Lens and Vision are classic OCR, not a model that understands the picture: the cleanings written
     * for Gemini/OpenRouter (v2.6 / v2.1) never touch them. Their text keeps its shape on screen
     * (CleanMode.CLASSIC: lines, dialogue dashes, punctuation), or one line if the user asks for it;
     * and they always read the original picture (see [enhanceFor]).
     */
    fun cleanModeFor(kind: EngineKind): CleanMode = if (subLang == SubLang.ENGLISH) CleanMode.ENGLISH
    else if (kind.isClassicOcr) (if (classicOneLine) CleanMode.PRO else CleanMode.CLASSIC)
    // the AI text form chosen on the main screen decides its cleaning (2.7.9: the separate cleaning
    // override in the settings is gone — a value saved there, "clean_choice", is no longer read)
    else profileFor(kind)?.clean ?: CleanMode.PRO

    fun mergeModeFor(kind: EngineKind): MergeMode = when (mergeChoice) {
        Choice.PRO -> MergeMode.PRO
        Choice.STRICT_OR_PRECISE -> MergeMode.STRICT
        Choice.NONE -> MergeMode.NONE
        Choice.AUTO -> profileFor(kind)?.merge ?: MergeMode.PRO
    }

    fun enhanceFor(kind: EngineKind): Boolean = if (kind.isClassicOcr) false else when (enhanceChoice) {
        EnhanceChoice.ON -> true
        EnhanceChoice.OFF -> false
        EnhanceChoice.AUTO -> profileFor(kind)?.enhanceImages ?: false
    }

    fun stabilizeFor(kind: EngineKind): Boolean = !kind.isClassicOcr && subLang == SubLang.ARABIC && cleanModeFor(kind) != CleanMode.PRO &&
        (profileFor(kind)?.stabilize ?: false)

    /** Lens and Vision share the "classic OCR" setting. */
    fun workersFor(kind: EngineKind): Int = if (kind.isClassicOcr) workersLens else workersAi

    /** Detailed on-device diagnostic log (sent by the user when something goes wrong). */
    var diagnostics: Boolean
        get() = sp.getBoolean("diag_log", true)
        set(v) = putBool("diag_log", v)
}
