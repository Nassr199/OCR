package com.abdulrahman.hardsubocr.core

/**
 * Multi-frame subtitle images (pure Kotlin, ARGB IntArrays).
 *
 * [combine]: the letters are the same pixels in every frame of a subtitle while the video behind
 * them moves. For light text an order statistic close to the per-pixel minimum keeps the bright
 * letters and their dark outline but darkens everything that changed — the background fades
 * away. (Dark text: the mirror image, close to the maximum.)
 *
 * [clean]: black text on white, like VideoSubFinder's TXTImages — full-resolution hysteresis mask
 * restricted to the subtitle's stable grid mask, so background specks never survive.
 */
object FrameComposite {

    fun combine(frames: List<IntArray>, style: SubStyle): IntArray {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0].copyOf()
        val n = frames[0].size
        val k = frames.size
        // with >= 4 frames skip the most extreme one (a glitched/blurred frame must not win)
        val rank = if (k >= 4) 1 else 0
        val out = IntArray(n)
        val r = IntArray(k)
        val g = IntArray(k)
        val b = IntArray(k)
        val light = style == SubStyle.LIGHT
        for (i in 0 until n) {
            for (f in 0 until k) {
                val c = frames[f][i]
                r[f] = (c shr 16) and 0xFF; g[f] = (c shr 8) and 0xFF; b[f] = c and 0xFF
            }
            out[i] = -0x1000000 or (pick(r, rank, light) shl 16) or (pick(g, rank, light) shl 8) or pick(b, rank, light)
        }
        return out
    }

    /** rank-th smallest (light) or rank-th largest (dark); small k → insertion sort. */
    private fun pick(a: IntArray, rank: Int, light: Boolean): Int {
        for (i in 1 until a.size) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j]; j--
            }
            a[j + 1] = v
        }
        return if (light) a[rank] else a[a.size - 1 - rank]
    }

    /**
     * Black-on-white text image. [grid] = the subtitle's stable mask on the analysis grid,
     * [step] = grid step in pixels (grid x * step = image x). Pass null to skip the restriction.
     */
    fun clean(argb: IntArray, w: Int, h: Int, style: SubStyle, p: ScanParams, grid: TextMask?, step: Int): IntArray {
        val n = w * h
        val v = IntArray(n)
        val dark = style == SubStyle.DARK
        for (i in 0 until n) {
            val c = argb[i]
            val y = (299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000
            v[i] = if (dark) 255 - y else y
        }
        // outline radius in full-resolution pixels: thick strokes need a wider window
        val r = maxOf(2, 2 * step)
        val nmin = ScanMask.minFilter(v, w, h, r)
        val allow = grid?.let { allowedArea(it, step, w, h) }
        val cls = ByteArray(n)
        val text = BooleanArray(n)
        val queue = IntArray(n)
        var qt = 0
        var qh = 0
        val bl = maxOf(60, p.brightLow - 10)
        for (i in 0 until n) {
            if (allow != null && !allow[i]) continue
            val c = v[i]
            if (c < bl) continue
            val d = c - nmin[i]
            if (c >= p.brightHigh && d >= p.contrastHigh) {
                cls[i] = 2; text[i] = true; queue[qt++] = i
            } else if (d >= p.contrastLow / 2) cls[i] = 1
        }
        while (qh < qt) {
            val i = queue[qh++]
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    val j = yy * w + xx
                    if (cls[j].toInt() == 1 && !text[j]) {
                        text[j] = true; queue[qt++] = j
                    }
                }
            }
        }
        val out = IntArray(n)
        for (i in 0 until n) out[i] = if (text[i]) -0x1000000 else -0x1
        return out
    }

    /** Grid mask dilated by 2 cells and scaled up to image pixels. */
    private fun allowedArea(grid: TextMask, step: Int, w: Int, h: Int): BooleanArray {
        val dil = TextMaskOps.dilate(grid, 2)
        val out = BooleanArray(w * h)
        val s = maxOf(1, step)
        for (y in 0 until h) {
            val gy = minOf(grid.height - 1, y / s)
            for (x in 0 until w) {
                val gx = minOf(grid.width - 1, x / s)
                out[y * w + x] = dil[gy * grid.width + gx]
            }
        }
        return out
    }

    /**
     * VideoSubFinder file name, byte-compatible with its OCR/"join images" stage:
     * `h_mm_ss_mmm__h_mm_ss_mmm_` + `%.1d%.4d%.4d%.4d%.4d%.4d%.4d` (line, xmin, ymin, w, h, W, H).
     * VSF writes the end as (first frame after the subtitle − 1 ms).
     */
    fun vsfFileName(startMs: Long, endExclusiveMs: Long, xmin: Int, ymin: Int, w: Int, h: Int, frameW: Int, frameH: Int, ext: String): String {
        val end = maxOf(startMs, endExclusiveMs - 1)
        fun d4(v: Int) = v.coerceIn(0, 9999).toString().padStart(4, '0')
        val suffix = "0" + d4(xmin) + d4(ymin) + d4(w) + d4(h) + d4(frameW) + d4(frameH)
        return Timestamps.vsfPart(startMs) + "__" + Timestamps.vsfPart(end) + "_" + suffix + "." + ext
    }
}
