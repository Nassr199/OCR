package com.abdulrahman.hardsubocr.video

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.abdulrahman.hardsubocr.core.CaptureKind
import com.abdulrahman.hardsubocr.core.FrameComposite
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.ScanSegment
import com.abdulrahman.hardsubocr.core.SubtitleTracker
import com.abdulrahman.hardsubocr.core.ScanMask
import com.abdulrahman.hardsubocr.core.TextMask
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * Decodes the whole video once (every frame, hardware MediaCodec), computes the text masks of the
 * subtitle region on all CPU cores in parallel, feeds them in order to the Arabic-first [SubtitleTracker] and keeps, per subtitle, the best frame plus a small pool of
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
        var gridW = 1
        var gridH = 1

        // All cores but one compute the per-frame text masks; tracking stays in frame order here.
        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 7)
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "subscan").apply { isDaemon = true } }
        val inflight = ArrayDeque<Pair<RoiFrame, Future<TextMask>>>()
        val free = ArrayDeque<RoiFrame>()

        fun handleClosed(list: List<ScanSegment>) {
            for (c in list) {
                val st = stores.remove(c.id) ?: continue
                val frames = if (st.pool.size >= 2) st.pool.toList() else listOfNotNull(st.best ?: st.pool.firstOrNull())
                if (frames.isEmpty()) continue
                val full = FrameComposite.combine(frames, params.style)
                val fullClean = if (saveClean) {
                    runCatching {
                        FrameComposite.clean(full, capW, capH, params.style, params, c.mask, maxOf(1, gridStep / capStep))
                    }.getOrNull()
                } else null
                val r = rect ?: continue
                // crop to the subtitle's own box (+margin): neighbouring credits / scenery stay out of OCR
                val k = gridStep.toDouble() / capStep
                val bw = c.x1 - c.x0 + 1
                val bh = c.y1 - c.y0 + 1
                val mx = maxOf(12, bw * 15 / 100)
                val my = maxOf(4, bh * 6 / 10)
                val cx0 = ((c.x0 - mx).coerceAtLeast(0) * k).toInt().coerceIn(0, capW - 1)
                val cy0 = ((c.y0 - my).coerceAtLeast(0) * k).toInt().coerceIn(0, capH - 1)
                val cx1 = ((c.x1 + 1 + mx).coerceAtMost(gridW) * k).toInt().coerceIn(cx0 + 1, capW)
                val cy1 = ((c.y1 + 1 + my).coerceAtMost(gridH) * k).toInt().coerceIn(cy0 + 1, capH)
                val ow = cx1 - cx0
                val oh = cy1 - cy0
                val argb = crop(full, capW, cx0, cy0, ow, oh)
                val clean = fullClean?.let { crop(it, capW, cx0, cy0, ow, oh) }
                segments++
                onSegment(
                    c,
                    SubImage(
                        argb, ow, oh, clean,
                        r.left + cx0 * capStep, r.top + cy0 * capStep, ow * capStep, oh * capStep,
                        frameW, frameH, frames.size
                    )
                )
            }
        }

        fun process(fr: RoiFrame, raw: TextMask) {
            val d = tracker.pushRaw(fr.ptsUs, raw)
            analysed++
            for (cap in d.captures) {
                val st = stores.getOrPut(cap.segId) { Store() }
                when (cap.kind) {
                    CaptureKind.BEST -> st.best = fr.toArgb(capStep)
                    CaptureKind.POOL -> st.pool.add(fr.toArgb(capStep))
                    CaptureKind.RESTART -> {
                        st.pool.clear(); st.best = fr.toArgb(capStep)
                    }
                }
            }
            handleClosed(d.closed)
            for (id in d.discarded) stores.remove(id)
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > 250) {
                lastProgressAt = now
                onProgress((fr.ptsUs.toFloat() / duration).coerceIn(0f, 1f), analysed, segments)
            }
        }

        fun drainOne() {
            val (fr, fut) = inflight.removeFirst()
            process(fr, fut.get())
            free.addLast(fr)
        }

        try {
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
                        gridW = maxOf(1, it.width() / gridStep)
                        gridH = maxOf(1, it.height() / gridStep)
                    }
                    val fr = free.removeFirstOrNull() ?: RoiFrame(r.width(), r.height())
                    fr.fill(image, r)
                    fr.ptsUs = t
                    val grid = fr.grid(gridStep)
                    inflight.addLast(fr to pool.submit<TextMask> { ScanMask.compute(grid, params) })
                    while (inflight.size > workers + 1) drainOne()
                    isActive()
                }
            )
            while (inflight.isNotEmpty() && isActive()) drainOne()
            if (isActive()) {
                val end = tracker.finish(lastNormPts + info.frameUs * every)
                handleClosed(end.closed)
                for (id in end.discarded) stores.remove(id)
                onProgress(1f, analysed, segments)
            }
        } finally {
            pool.shutdownNow()
            inflight.clear()
            stores.clear()
        }
        return Result(frameIndex, analysed, segments)
    }

    private fun crop(src: IntArray, srcW: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (j in 0 until h) System.arraycopy(src, (y0 + j) * srcW + x0, out, j * w, w)
        return out
    }
}
