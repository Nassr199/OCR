package com.abdulrahman.hardsubocr.data

import android.content.Context
import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.MergeMode
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.ThinkingLevel

enum class SourceMode { IMAGE, FOLDER, MULTI, VIDEO }

/** AUTO = the behaviour of the Windows program that originally used this engine. */
enum class Choice { AUTO, PRO, STRICT_OR_PRECISE }

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

    var geminiModel: String
        get() = str("gemini_model", "gemini-3-flash-preview")
        set(v) = put("gemini_model", v.trim())

    var openRouterModel: String
        get() = str("openrouter_model", OpenRouterEngine.DEFAULT_MODEL)
        set(v) = put("openrouter_model", v.trim())

    var thinking: ThinkingLevel
        get() = enumOf("thinking", ThinkingLevel.LOW)
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

    var cleanChoice: Choice
        get() = enumOf("clean_choice", Choice.AUTO)
        set(v) = put("clean_choice", v.name)

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

    var brightThreshold: Int
        get() = sp.getInt("v_bright", 175)
        set(v) = putInt("v_bright", v)

    var contrastDelta: Int
        get() = sp.getInt("v_contrast", 60)
        set(v) = putInt("v_contrast", v)

    var darkText: Boolean
        get() = sp.getBoolean("v_dark", false)
        set(v) = putBool("v_dark", v)

    var changeThreshold: Float
        get() = sp.getFloat("v_change", 0.32f)
        set(v) = putFloat("v_change", v)

    var analyzeEvery: Int
        get() = sp.getInt("v_every", 1)
        set(v) = putInt("v_every", v)

    var minDurationMs: Int
        get() = sp.getInt("v_min_ms", 200)
        set(v) = putInt("v_min_ms", v)

    var exportFrames: Boolean
        get() = sp.getBoolean("v_export", false)
        set(v) = putBool("v_export", v)

    // ---------------------------------------------------------------- derived
    fun cleanModeFor(kind: EngineKind): CleanMode = when (cleanChoice) {
        Choice.PRO -> CleanMode.PRO
        Choice.STRICT_OR_PRECISE -> CleanMode.PRECISE
        Choice.AUTO -> if (kind == EngineKind.GEMINI) CleanMode.PRECISE else CleanMode.PRO
    }

    fun mergeModeFor(kind: EngineKind): MergeMode = when (mergeChoice) {
        Choice.PRO -> MergeMode.PRO
        Choice.STRICT_OR_PRECISE -> MergeMode.STRICT
        Choice.AUTO -> if (kind == EngineKind.GEMINI) MergeMode.STRICT else MergeMode.PRO
    }

    fun workersFor(kind: EngineKind): Int = if (kind == EngineKind.LENS) workersLens else workersAi
}
