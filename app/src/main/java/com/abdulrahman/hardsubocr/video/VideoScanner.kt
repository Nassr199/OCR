package com.abdulrahman.hardsubocr.video

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.abdulrahman.hardsubocr.core.CaptureKind
import com.abdulrahman.hardsubocr.core.FrameComposite
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.ScanSegment
import com.abdulrahman.hardsubocr.core.SubtitleTracker
import com.abdulrahman.hardsubocr.data.RoiRect

/** One finished subtitle image (composite) + where it came from (for VideoSubFinder-style names). */
class SubImage(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    /** Black-on-white version (TXTImages), when requested. */
    val clean: IntArray?,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val frameW: Int,
    val frameH: Int,
    val framesUsed: Int
)

/**
 * Decodes the whole video once (every frame, hardware MediaCodec), feeds the subtitle region to the
 * Arabic-first [SubtitleTracker] and keeps, per subtitle, the best frame plus a small pool of
 * frames for the background-cleaning composite. Every finished subtitle is handed to [onSegment].
 */
class VideoScanner(
    private val context: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    private val roi: RoiRect,
    private val params: ScanParams,
    private val analyzeEvery: Int,
    private val saveClean: Boolean,
    private val onProgress: (fraction: Float, analysedFrames: Int, subtitles: Int) -> Unit,
    private val onSegment: (segment: ScanSegment, image: SubImage) -> Unit
) {
    class Result(val frames: Int, val analysed: Int, val segments: Int)

    private class Store {
        var best: IntArray? = null
        val pool = ArrayList<IntArray>(6)
    }

    fun run(isActive: () -> Boolean): Result {
        val tracker = SubtitleTracker(params)
        val stores = HashMap<Int, Store>()
        var rect: Rect? = null
        var frameW = 0
        var frameH = 0
        var reuse: ByteArray? = null
        var frameIndex = 0
        var analysed = 0
        var segments = 0
        var offsetUs = -1L
        var lastNormPts = 0L
        var lastProgressAt = 0L
        val every = analyzeEvery.coerceAtLeast(1)
        val duration = info.durationUs.coerceAtLeast(1)
        var capW = 0
        var capH = 0
        var capStep = 1
        var gridStep = 1

        fun handleClosed(list: List<ScanSegment>) {
            for (c in list) {
                val st = stores.remove(c.id) ?: continue
                val frames = if (st.pool.size >= 2) st.pool.toList() else listOfNotNull(st.best ?: st.pool.firstOrNull())
                if (frames.isEmpty()) continue
                val argb = FrameComposite.combine(frames, params.style)
                val clean = if (saveClean) {
                    runCatching {
                        FrameComposite.clean(argb, capW, capH, params.style, params, c.mask, maxOf(1, gridStep / capStep))
                    }.getOrNull()
                } else null
                val r = rect ?: continue
                segments++
                onSegment(
                    c,
                    SubImage(argb, capW, capH, clean, r.left, r.top, r.width(), r.height(), frameW, frameH, frames.size)
                )
            }
        }

        fun handleDiscarded(list: List<Int>) {
            for (id in list) stores.remove(id)
        }

        VideoDecoder(context, uri).decode(
            isActive = isActive,
            want = { _ ->
                val take = frameIndex % every == 0
                frameIndex++
                take
            },
            onFrame = { pts, image ->
                if (offsetUs < 0) offsetUs = if (pts > 500_000) pts else 0L
                val t = (pts - offsetUs).coerceAtLeast(0)
                lastNormPts = maxOf(lastNormPts, t)
                val r = rect ?: Yuv.roiPixels(image, roi).also {
                    rect = it
                    frameW = image.cropRect.width()
                    frameH = image.cropRect.height()
                    // keep composites <= 1920 px wide (4K video: memory)
                    capStep = maxOf(1, (it.width() + 1919) / 1920)
                    capW = maxOf(1, it.width() / capStep)
                    capH = maxOf(1, it.height() / capStep)
                    gridStep = Yuv.gridStep(it.width())
                }
                val grid = Yuv.lumaGrid(image, r, reuse)
                reuse = grid.data
                val d = tracker.push(t, grid)
                analysed++
                for (cap in d.captures) {
                    val st = stores.getOrPut(cap.segId) { Store() }
                    when (cap.kind) {
                        CaptureKind.BEST -> st.best = Yuv.toArgb(image, r, capStep)
                        CaptureKind.POOL -> st.pool.add(Yuv.toArgb(image, r, capStep))
                        CaptureKind.RESTART -> {
                            st.pool.clear(); st.best = Yuv.toArgb(image, r, capStep)
                        }
                    }
                }
                handleClosed(d.closed)
                handleDiscarded(d.discarded)
                val now = System.currentTimeMillis()
                if (now - lastProgressAt > 250) {
                    lastProgressAt = now
                    onProgress((t.toFloat() / duration).coerceIn(0f, 1f), analysed, segments)
                }
                isActive()
            }
        )
        if (isActive()) {
            val end = tracker.finish(lastNormPts + info.frameUs * every)
            handleClosed(end.closed)
            handleDiscarded(end.discarded)
            onProgress(1f, analysed, segments)
        }
        stores.clear()
        return Result(frameIndex, analysed, segments)
    }
}
