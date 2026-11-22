package com.abdulrahman.hardsubocr.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.abdulrahman.hardsubocr.core.ClosedSegment
import com.abdulrahman.hardsubocr.core.SegmenterParams
import com.abdulrahman.hardsubocr.core.SubtitleSegmenter
import com.abdulrahman.hardsubocr.data.RoiRect

/**
 * Decodes the whole video once, feeds the subtitle region to [SubtitleSegmenter] and hands
 * every finished subtitle segment (with one sharp colour crop) to [onSegment].
 */
class VideoScanner(
    private val context: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    private val roi: RoiRect,
    private val params: SegmenterParams,
    private val analyzeEvery: Int,
    private val onProgress: (fraction: Float, analysedFrames: Int) -> Unit,
    private val onSegment: (segment: ClosedSegment, crop: Bitmap) -> Unit
) {
    class Result(val frames: Int, val analysed: Int, val segments: Int)

    fun run(isActive: () -> Boolean): Result {
        val segmenter = SubtitleSegmenter(params)
        val crops = HashMap<Int, Bitmap>()
        var rect: Rect? = null
        var reuse: ByteArray? = null
        var frameIndex = 0
        var analysed = 0
        var segments = 0
        var offsetUs = -1L
        var lastNormPts = 0L
        var lastProgressAt = 0L
        val every = analyzeEvery.coerceAtLeast(1)
        val duration = info.durationUs.coerceAtLeast(1)

        fun handleClosed(list: List<ClosedSegment>) {
            for (c in list) {
                val bmp = crops.remove(c.id) ?: continue
                segments++
                try {
                    onSegment(c, bmp)
                } finally {
                    bmp.recycle()
                }
            }
        }

        fun handleDiscarded(list: List<Int>) {
            for (id in list) crops.remove(id)?.recycle()
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
                    val r = rect ?: Yuv.roiPixels(image, roi).also { rect = it }
                    val grid = Yuv.lumaGrid(image, r, reuse)
                    reuse = grid.data
                    val d = segmenter.push(t, grid)
                    analysed++
                    d.captureFor?.let { id ->
                        crops.remove(id)?.recycle()
                        crops[id] = Yuv.toBitmap(image, r)
                    }
                    handleClosed(d.closed)
                    handleDiscarded(d.discarded)
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt > 250) {
                        lastProgressAt = now
                        onProgress((t.toFloat() / duration).coerceIn(0f, 1f), analysed)
                    }
                    isActive()
                }
            )
            if (isActive()) {
                val end = segmenter.finish(lastNormPts + info.frameUs * every)
                handleClosed(end.closed)
                handleDiscarded(end.discarded)
                onProgress(1f, analysed)
            }
        } finally {
            for (b in crops.values) b.recycle()
            crops.clear()
        }
        return Result(frameIndex, analysed, segments)
    }
}
