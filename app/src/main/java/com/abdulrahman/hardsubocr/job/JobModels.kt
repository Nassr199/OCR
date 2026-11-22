package com.abdulrahman.hardsubocr.job

import android.net.Uri
import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.MergeMode
import com.abdulrahman.hardsubocr.core.SegmenterParams
import com.abdulrahman.hardsubocr.core.SubEntry
import com.abdulrahman.hardsubocr.core.ThinkingLevel
import com.abdulrahman.hardsubocr.data.FolderRef
import com.abdulrahman.hardsubocr.data.RoiRect

sealed class Source {
    data class Image(val uri: Uri, val name: String) : Source()
    data class Folder(val folder: FolderRef) : Source()
    data class Multi(val folders: List<FolderRef>) : Source()
    data class Video(val uri: Uri, val name: String, val roi: RoiRect) : Source()
}

data class JobRequest(
    val source: Source,
    val output: FolderRef?,
    val engine: EngineKind,
    val geminiKey: String,
    val openRouterKey: String,
    val geminiModel: String,
    val openRouterModel: String,
    val thinking: ThinkingLevel,
    val workers: Int,
    val retries: Int,
    val cleanMode: CleanMode,
    val mergeMode: MergeMode,
    val stripDiacritics: Boolean,
    val bom: Boolean,
    val crlf: Boolean,
    val overwrite: Boolean,
    val resume: Boolean,
    val lensLanguage: String,
    val lensRegion: String,
    val segmenter: SegmenterParams,
    val analyzeEvery: Int,
    val exportFrames: Boolean
)

data class Progress(
    val title: String,
    val phase: String,
    val fraction: Float,
    val done: Int,
    val total: Int,
    val ok: Int,
    val empty: Int,
    val failed: Int,
    val cached: Int,
    val retries: Int,
    val speed: Double,
    val etaSec: Double,
    val unitIndex: Int,
    val unitCount: Int,
    val decodeFraction: Float?,
    val log: List<String>
) {
    companion object {
        fun starting() = Progress(
            "جاري التحضير…", "", 0f, 0, 0, 0, 0, 0, 0, 0, 0.0, Double.NaN, 0, 1, null, emptyList()
        )
    }
}

data class JobOutput(
    val name: String,
    /** Saved SRT, or null when nothing was written yet (stopped job, no output folder…). */
    val uri: Uri?,
    val entries: List<SubEntry>,
    val savedIn: String?,
    val folderForSave: FolderRef?,
    val failures: List<Pair<String, String>> = emptyList(),
    val withoutTimestamp: Int = 0,
    val partial: Boolean = false,
    val error: String? = null,
    /** Single image without a VideoSubFinder time code: the plain OCR text. */
    val plainText: String? = null
)

sealed class JobState {
    object Idle : JobState()
    data class Running(val progress: Progress) : JobState()
    data class Done(val outputs: List<JobOutput>, val stopped: Boolean, val elapsedSec: Double, val summary: String) : JobState()
    data class Error(val message: String, val outputs: List<JobOutput>) : JobState()
}
