package com.abdulrahman.hardsubocr.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.abdulrahman.hardsubocr.core.LumaGrid
import com.abdulrahman.hardsubocr.data.RoiRect
import java.io.IOException

class VideoInfo(
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameRate: Float,
    val mime: String
) {
    val frameUs: Long get() = if (frameRate > 1f) (1_000_000f / frameRate).toLong() else 41_708L
    val label: String
        get() = "${width}×$height • " + (if (frameRate > 0) "%.3g fps • ".format(java.util.Locale.US, frameRate) else "") +
            com.abdulrahman.hardsubocr.core.Timestamps.srt(durationUs / 1000).substringBefore(',')
}

object VideoProbe {

    fun findVideoTrack(ex: MediaExtractor): Int? {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    fun frameRate(f: MediaFormat): Float {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } catch (e: Exception) {
            try {
                f.getFloat(MediaFormat.KEY_FRAME_RATE)
            } catch (e2: Exception) {
                0f
            }
        }
    }

    fun probe(context: Context, uri: Uri): VideoInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val idx = findVideoTrack(ex) ?: throw IOException("لا يوجد مسار فيديو في هذا الملف")
            val f = ex.getTrackFormat(idx)
            var dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            if (dur <= 0) dur = retrieverDurationUs(context, uri)
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            return VideoInfo(
                dur, f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), rot,
                frameRate(f), f.getString(MediaFormat.KEY_MIME) ?: ""
            )
        } finally {
            ex.release()
        }
    }

    private fun retrieverDurationUs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }
}

/** YUV_420_888 (and 10-bit P010) access helpers. */
object Yuv {
    private const val FORMAT_P010 = 0x36 // ImageFormat.YCBCR_P010 (API 33)

    private fun hiByte(image: Image): Int = if (image.format == FORMAT_P010) 1 else 0

    /** ROI in pixel coordinates inside the visible (crop) area of the decoded frame. */
    fun roiPixels(image: Image, roi: RoiRect): Rect {
        val c = image.cropRect
        val cw = c.width()
        val ch = c.height()
        val r = roi.normalized()
        var l = c.left + (r.left * cw).toInt()
        var t = c.top + (r.top * ch).toInt()
        var rr = c.left + (r.right * cw).toInt()
        var b = c.top + (r.bottom * ch).toInt()
        l = l.coerceIn(c.left, c.right - 2); rr = rr.coerceIn(l + 2, c.right)
        t = t.coerceIn(c.top, c.bottom - 2); b = b.coerceIn(t + 2, c.bottom)
        // even origin keeps chroma aligned
        if (l % 2 == 1) l -= 1
        if (t % 2 == 1) t -= 1
        return Rect(l, t, rr, b)
    }

    fun gridStep(roiWidth: Int, target: Int = 640): Int = maxOf(1, roiWidth / target)

    /** Nearest-neighbour luma grid of the ROI (exactly what the segmenter tests were run on). */
    fun lumaGrid(image: Image, r: Rect, reuse: ByteArray?): LumaGrid {
        val step = gridStep(r.width())
        val gw = maxOf(1, r.width() / step)
        val gh = maxOf(1, r.height() / step)
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val hi = hiByte(image)
        val data = if (reuse != null && reuse.size >= gw * gh) reuse else ByteArray(gw * gh)
        for (gy in 0 until gh) {
            val row = (r.top + gy * step) * rs
            val out = gy * gw
            for (gx in 0 until gw) {
                data[out + gx] = buf.get(row + (r.left + gx * step) * ps + hi)
            }
        }
        return LumaGrid(gw, gh, data)
    }

    /** Colour crop of [r] (BT.601) every [step] pixels. */
    fun toBitmap(image: Image, r: Rect, step: Int = 1): Bitmap {
        val w = maxOf(1, r.width() / step)
        val h = maxOf(1, r.height() / step)
        return Bitmap.createBitmap(toArgb(image, r, step), w, h, Bitmap.Config.ARGB_8888)
    }

    /** Same as [toBitmap] but as an ARGB array (kept in memory for multi-frame composites). */
    fun toArgb(image: Image, r: Rect, step: Int = 1): IntArray {
        val w = maxOf(1, r.width() / step)
        val h = maxOf(1, r.height() / step)
        val px = IntArray(w * h)
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yb = yP.buffer
        val ub = uP.buffer
        val vb = vP.buffer
        val yrs = yP.rowStride
        val yps = yP.pixelStride
        val urs = uP.rowStride
        val ups = uP.pixelStride
        val vrs = vP.rowStride
        val vps = vP.pixelStride
        val hi = hiByte(image)
        for (j in 0 until h) {
            val sy = r.top + j * step
            val yRow = sy * yrs
            val cy = sy shr 1
            val uRow = cy * urs
            val vRow = cy * vrs
            val o = j * w
            for (i in 0 until w) {
                val sx = r.left + i * step
                val y = (yb.get(yRow + sx * yps + hi).toInt() and 0xFF) - 16
                val cx = sx shr 1
                val u = (ub.get(uRow + cx * ups + hi).toInt() and 0xFF) - 128
                val v = (vb.get(vRow + cx * vps + hi).toInt() and 0xFF) - 128
                val c = (if (y < 0) 0 else y) * 1192
                var rr = (c + 1634 * v) shr 10
                var gg = (c - 833 * v - 400 * u) shr 10
                var bb = (c + 2066 * u) shr 10
                if (rr < 0) rr = 0 else if (rr > 255) rr = 255
                if (gg < 0) gg = 0 else if (gg > 255) gg = 255
                if (bb < 0) bb = 0 else if (bb > 255) bb = 255
                px[o + i] = -0x1000000 or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return px
    }

    fun previewBitmap(image: Image, maxWidth: Int = 1280): Bitmap {
        val c = image.cropRect
        val step = maxOf(1, (c.width() + maxWidth - 1) / maxWidth)
        val r = Rect(c.left - (c.left % 2), c.top - (c.top % 2), c.right, c.bottom)
        return toBitmap(image, r, step)
    }
}

/**
 * Sequential hardware decoding with MediaCodec. Unlike MediaMetadataRetriever (one seek per
 * frame) every frame is decoded exactly once, so a full episode is analysed frame by frame.
 */
class VideoDecoder(private val context: Context, private val uri: Uri) {

    /** Told which decoder is used (e.g. c2.qti.avc.decoder = Qualcomm hardware). */
    var onCodec: ((name: String, software: Boolean) -> Unit)? = null

    private fun createCodec(mime: String, format: MediaFormat, software: Boolean): MediaCodec {
        if (!software) return MediaCodec.createDecoderByType(mime)
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val info = list.codecInfos.firstOrNull { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (ci.name.startsWith("c2.android.") || ci.name.startsWith("OMX.google."))
        } ?: return MediaCodec.createDecoderByType(mime)
        return MediaCodec.createByCodecName(info.name)
    }

    /**
     * @param want  return true for presentation times whose pixels are needed
     * @param onFrame called with the decoded image; return false to stop early
     * @return last presentation time seen (µs) or -1
     */
    fun decode(
        startUs: Long = 0,
        isActive: () -> Boolean,
        want: (Long) -> Boolean,
        onFrame: (Long, Image) -> Boolean
    ): Long {
        var lastErr: Exception? = null
        for (software in listOf(false, true)) {
            try {
                return decodeOnce(startUs, software, isActive, want, onFrame)
            } catch (e: MediaCodec.CodecException) {
                lastErr = e
            } catch (e: IllegalStateException) {
                lastErr = e
            } catch (e: IllegalArgumentException) {
                lastErr = e
            }
        }
        throw IOException("تعذر فك ترميز الفيديو: ${lastErr?.message}", lastErr)
    }

    private fun decodeOnce(
        startUs: Long,
        software: Boolean,
        isActive: () -> Boolean,
        want: (Long) -> Boolean,
        onFrame: (Long, Image) -> Boolean
    ): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var lastPts = -1L
        try {
            ex.setDataSource(context, uri, null)
            val track = VideoProbe.findVideoTrack(ex) ?: throw IOException("لا يوجد مسار فيديو")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("نوع فيديو غير معروف")
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = createCodec(mime, format, software)
            codec = c
            runCatching { onCodec?.invoke(c.name, software) }
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var stop = false
            var idle = 0
            while (!outputDone && !stop) {
                if (!isActive()) break
                if (idle > 3000) throw IOException("مفكك الفيديو توقف عن الاستجابة")
                if (!inputDone) {
                    var guard = 0
                    while (guard++ < 8) {
                        val inIdx = c.dequeueInputBuffer(if (guard == 1) 5_000L else 0L)
                        if (inIdx < 0) break
                        val buf = c.getInputBuffer(inIdx) ?: break
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            break
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                var drained = 0
                while (drained++ < 8) {
                    val outIdx = c.dequeueOutputBuffer(info, 5_000L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        idle++; break
                    }
                    if (outIdx < 0) continue // format / buffers changed
                    idle = 0
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = info.presentationTimeUs
                    if (info.size > 0 || !eos) {
                        if (want(pts)) {
                            val image = c.getOutputImage(outIdx)
                            if (image != null) {
                                try {
                                    if (!onFrame(pts, image)) stop = true
                                } finally {
                                    image.close()
                                }
                            }
                        }
                        if (pts > lastPts) lastPts = pts
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (eos) {
                        outputDone = true; break
                    }
                    if (stop) break
                }
            }
            return lastPts
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            ex.release()
        }
    }
}

object FramePreview {
    /**
     * A small picture for the timeline strip of the region screen: the key frame at or just before
     * [timeUs] (one frame decoded, so it is quick), <= [maxWidth] px wide, in the same orientation as
     * the preview. Display only: nothing of the extraction uses it.
     */
    fun thumb(context: Context, uri: Uri, timeUs: Long, maxWidth: Int = 240): Bitmap? {
        var bmp: Bitmap? = null
        VideoDecoder(context, uri).decode(
            startUs = timeUs,
            isActive = { true },
            want = { true },
            onFrame = { _, img -> bmp = Yuv.previewBitmap(img, maxWidth); false }
        )
        return bmp
    }

    /** First frame at or after [timeUs], down-scaled to <= 1280 px wide. */
    fun grab(context: Context, uri: Uri, timeUs: Long): Bitmap? {
        var bmp: Bitmap? = null
        VideoDecoder(context, uri).decode(
            startUs = timeUs,
            isActive = { true },
            want = { pts -> pts >= timeUs - 1_000 },
            onFrame = { _, img -> bmp = Yuv.previewBitmap(img); false }
        )
        return bmp
    }
}
