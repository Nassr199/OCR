package com.abdulrahman.hardsubocr.job

import android.net.Uri
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.MergeMode
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubLang
import com.abdulrahman.hardsubocr.data.VideoMode
import com.abdulrahman.hardsubocr.video.VideoEngine
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
    val visionKey: String,
    /** Google Vision: DOCUMENT_TEXT_DETECTION (true) or TEXT_DETECTION. */
    val visionDocument: Boolean = true,
    /** Google Lens: extra looks at suspicious pictures. */
    val lensMultiView: Boolean = true,
    /** Google Vision OCR model ("" = builtin/stable). */
    val visionModel: String = "",
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
    val scan: ScanParams,
    val analyzeEvery: Int,
    val videoMode: VideoMode,
    val videoEngine: VideoEngine,
    val gpu: Boolean,
    val saveClean: Boolean,
    val lang: SubLang,
    val profile: AiProfile?,
    val enhance: Boolean,
    val mediaHigh: Boolean,
    val stabilize: Boolean
)

/** One line of the live log; [id] keeps the list smooth when new lines arrive. */
data class LogLine(val id: Long, val text: String)

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
    val log: List<LogLine>,
    /** Seconds since the job started. */
    val elapsedSec: Double = 0.0,
    /** Video: frames analysed so far and analysis speed (frames per second), subtitles found. */
    val frames: Int = 0,
    val framesPerSec: Double = 0.0,
    val subtitlesFound: Int = 0
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
    val plainText: String? = null,
    /** Extra line for the result card (e.g. where the subtitle images were saved). */
    val info: String? = null,
    /** Video "images only" mode: number of subtitle images written. */
    val imagesSaved: Int = 0
)

sealed class JobState {
    object Idle : JobState()
    data class Running(val progress: Progress) : JobState()
    data class Done(val outputs: List<JobOutput>, val stopped: Boolean, val elapsedSec: Double, val summary: String) : JobState()
    data class Error(val message: String, val outputs: List<JobOutput>, val details: String? = null) : JobState()
}
