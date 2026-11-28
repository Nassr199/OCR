package com.abdulrahman.hardsubocr.video

import android.graphics.Rect
import android.media.Image
import com.abdulrahman.hardsubocr.core.LumaGrid

/**
 * Copy of the subtitle region of one decoded frame (luma full resolution, chroma half), so the
 * hardware decoder's buffer can be released at once and the frame analysed on another core.
 * Handles 8-bit YUV_420_888 (planar or semi-planar) and 10-bit P010 (high byte), any strides.
 */
class RoiFrame(val w: Int, val h: Int) {
    val y = ByteArray(w * h)
    private val cw = maxOf(1, w / 2)
    private val ch = maxOf(1, h / 2)
    private val u = ByteArray(cw * ch)
    private val v = ByteArray(cw * ch)
    private var row = ByteArray(0)
    var ptsUs = 0L

    fun fill(image: Image, r: Rect) {
        val hi = if (image.format == FORMAT_P010) 1 else 0
        copy(image.planes[0], r.left, r.top, w, h, hi, y)
        copy(image.planes[1], r.left / 2, r.top / 2, cw, ch, hi, u)
        copy(image.planes[2], r.left / 2, r.top / 2, cw, ch, hi, v)
    }

    private fun copy(pl: Image.Plane, x0: Int, y0: Int, cols: Int, rows: Int, hi: Int, dst: ByteArray) {
        val buf = pl.buffer.duplicate()
        val rs = pl.rowStride
        val ps = pl.pixelStride
        val need = (cols - 1) * ps + 1 + hi
        if (row.size < need) row = ByteArray(need)
        for (j in 0 until rows) {
            val start = (y0 + j) * rs + x0 * ps
            if (start >= buf.limit()) break
            val len = minOf(need, buf.limit() - start)
            buf.position(start)
            buf.get(row, 0, len)
            val o = j * cols
            if (ps == 1 && hi == 0) {
                System.arraycopy(row, 0, dst, o, minOf(cols, len))
            } else {
                var k = hi
                for (i in 0 until cols) {
                    if (k < len) dst[o + i] = row[k]
                    k += ps
                }
            }
        }
    }

    /** Same sampling as [Yuv.lumaGrid] (nearest pixel every [step]). */
    fun grid(step: Int): LumaGrid {
        val gw = maxOf(1, w / step)
        val gh = maxOf(1, h / step)
        val data = ByteArray(gw * gh)
        for (gy in 0 until gh) {
            val src = gy * step * w
            val o = gy * gw
            for (gx in 0 until gw) data[o + gx] = y[src + gx * step]
        }
        return LumaGrid(gw, gh, data)
    }

    /** BT.601 limited-range → ARGB, every [step] pixels (same math as [Yuv.toArgb]). */
    fun toArgb(step: Int): IntArray {
        val ow = maxOf(1, w / step)
        val oh = maxOf(1, h / step)
        val px = IntArray(ow * oh)
        for (j in 0 until oh) {
            val sy = j * step
            val cy = minOf(ch - 1, sy shr 1)
            val o = j * ow
            for (i in 0 until ow) {
                val sx = i * step
                val cx = minOf(cw - 1, sx shr 1)
                val yy = (y[sy * w + sx].toInt() and 0xFF) - 16
                val uu = (u[cy * cw + cx].toInt() and 0xFF) - 128
                val vv = (v[cy * cw + cx].toInt() and 0xFF) - 128
                val c = (if (yy < 0) 0 else yy) * 1192
                var rr = (c + 1634 * vv) shr 10
                var gg = (c - 833 * vv - 400 * uu) shr 10
                var bb = (c + 2066 * uu) shr 10
                if (rr < 0) rr = 0 else if (rr > 255) rr = 255
                if (gg < 0) gg = 0 else if (gg > 255) gg = 255
                if (bb < 0) bb = 0 else if (bb > 255) bb = 255
                px[o + i] = -0x1000000 or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return px
    }

    private companion object {
        const val FORMAT_P010 = 0x36
    }
}
