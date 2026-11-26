package com.abdulrahman.hardsubocr.core

/** Tiny HTTP abstraction so all engine logic stays pure Kotlin (tested on the JVM, OkHttp on Android). */
class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val timeoutMs: Long = 120_000
)

class HttpResponse(val code: Int, val body: ByteArray, headers: Map<String, String>) {
    private val lower = headers.mapKeys { it.key.lowercase() }
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = lower[name.lowercase()]
}

/** Implementations must throw java.io.IOException for network problems and support coroutine cancellation. */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Errors raised by engines. The pipeline decides what to do with each kind. */
sealed class OcrError(message: String) : Exception(message) {
    /** Temporary problem (network, 429, 5xx): retried with back-off. */
    class Retryable(message: String, val retryAfterMs: Long? = null, val rateLimited: Boolean = false) :
        OcrError(message)

    /** Nothing will work until the user fixes something (bad key, no credit, unknown model): stops the job. */
    class Fatal(message: String) : OcrError(message)

    /** Only this image failed. The job continues and the image is listed as failed. */
    class Failed(message: String) : OcrError(message)

    /**
     * An AI answer stopped before its end twice (filter, length): only this image failed, and the
     * pipeline may take Google Lens's reading of it instead ([LensCheck]). [partial] is what was written.
     */
    class Cut(message: String, val partial: String) : OcrError(message)
}

enum class EngineKind { LENS, GEMINI, OPENROUTER, VISION }

/**
 * Classic OCR (Google Lens, Google Cloud Vision): no prompt, the text exactly as recognised. Both get
 * the same treatment everywhere: cleaning, merging, image preparation, parallelism and image sizes.
 */
val EngineKind.isClassicOcr: Boolean get() = this == EngineKind.LENS || this == EngineKind.VISION

/**
 * Version of the classic-OCR reading (Lens/Vision request, text assembly, cleaning). Part of their
 * resume-memory identity: bump it whenever any of that changes, so a re-run never replays a reading
 * made by an older version (before 2.7.6 Lens's identity never changed, and every re-run of the same
 * video got its first reading back instantly).
 */
const val CLASSIC_OCR_VERSION = 8

class PreparedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    /** Image name, only for the diagnostic file. */
    val name: String = ""
)

/** Decoding / scaling / re-encoding lives on the platform side (Bitmap on Android). */
fun interface ImagePreparer {
    fun prepare(bytes: ByteArray, nameHint: String, kind: EngineKind): PreparedImage
}

/** Crops a prepared image, in its own pixels (end exclusive); platform side (Bitmap on Android). */
fun interface ImageCropper {
    fun crop(image: PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int): PreparedImage?
}

/** A part of a prepared image (its own pixels, end exclusive) scaled by [scale]; platform side. */
fun interface ImageResampler {
    fun view(image: PreparedImage, x0: Int, y0: Int, x1: Int, y1: Int, scale: Float): PreparedImage?
}

interface OcrEngine {
    val kind: EngineKind

    /** Identifies engine + settings that change the result (used by the resume cache). */
    val cacheId: String

    /** Returns the RAW text produced by the engine (cleaning happens in the pipeline). */
    suspend fun recognize(image: PreparedImage): String
}

internal fun parseRetryAfter(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { return it * 1000L }
    value.trim().toDoubleOrNull()?.let { return (it * 1000).toLong() }
    return null
}
