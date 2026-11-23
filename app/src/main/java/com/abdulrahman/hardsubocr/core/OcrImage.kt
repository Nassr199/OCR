package com.abdulrahman.hardsubocr.core

/**
 * The image Google Lens / Vision read in video mode: the subtitle's letters alone, black on
 * white (VideoSubFinder's TXTImages), as these classic OCR engines read printed text best.
 * No box, no outline or shadow, no background and no crew names next to the subtitle — nothing
 * they could read twice or mix into the line.
 *
 * From [FrameComposite.clean]: cropped to the letters with a white margin, small text enlarged
 * (a line under 32 px is read worse), and the longest side kept within [maxDim].
 */
object OcrImage {

    /** Ink = dark pixel of the black-on-white image. */
    private fun ink(c: Int) = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) < 384

    /** null when the image has no letters (the original picture is read instead). */
    fun fromClean(clean: IntArray, w: Int, h: Int, maxDim: Int = LensProtocol.MAX_DIMENSION): GrayImage? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        val rows = IntArray(h)
        var total = 0
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) if (ink(clean[o + x])) {
                rows[y]++; total++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
            }
            if (rows[y] > 0) {
                if (y < y0) y0 = y
                y1 = y
            }
        }
        if (x1 < 0 || total < 12) return null
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val lineH = lineHeight(rows, y0, y1)
        // enlarge small text: Lens / Vision read a 40–90 px line best
        var scale = when {
            lineH < 14 -> 3.0
            lineH < 32 -> 2.0
            else -> 1.0
        }
        val margin0 = maxOf(12, lineH / 2)
        // the whole image (letters + margins) within maxDim
        val fit = (maxDim - 2.0 * margin0) / maxOf(bw, bh)
        if (scale > fit) scale = maxOf(fit, 0.1)
        val margin = maxOf(8, (margin0 * minOf(scale, 1.0)).toInt())
        val ow = maxOf(1, (bw * scale).toInt())
        val oh = maxOf(1, (bh * scale).toInt())
        val W = ow + 2 * margin
        val H = oh + 2 * margin
        val out = ByteArray(W * H) { 0xFF.toByte() }
        if (scale >= 1.0) {
            for (oy in 0 until oh) {
                val sy = y0 + minOf(bh - 1, (oy / scale).toInt())
                val so = sy * w
                val d = (oy + margin) * W + margin
                for (ox in 0 until ow) {
                    val sx = x0 + minOf(bw - 1, (ox / scale).toInt())
                    if (ink(clean[so + sx])) out[d + ox] = 0
                }
            }
        } else {
            // shrinking: average the ink of each output pixel's area (keeps thin strokes as grey)
            for (oy in 0 until oh) {
                val sy0 = y0 + (oy / scale).toInt()
                val sy1 = minOf(y1 + 1, y0 + ((oy + 1) / scale).toInt()).coerceAtLeast(sy0 + 1)
                val d = (oy + margin) * W + margin
                for (ox in 0 until ow) {
                    val sx0 = x0 + (ox / scale).toInt()
                    val sx1 = minOf(x1 + 1, x0 + ((ox + 1) / scale).toInt()).coerceAtLeast(sx0 + 1)
                    var n = 0; var k = 0
                    for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                        n++
                        if (ink(clean[sy * w + sx])) k++
                    }
                    out[d + ox] = (255 - 255 * k / maxOf(1, n)).toByte()
                }
            }
        }
        return GrayImage(W, H, out)
    }

    /**
     * Height of the tallest text line: rows with ink, joined over small gaps (dots and
     * diacritics sit a few pixels above or below the letters).
     */
    private fun lineHeight(rows: IntArray, y0: Int, y1: Int): Int {
        val bridge = maxOf(3, (y1 - y0 + 1) / 8)
        var best = 0
        var start = -1
        var lastInk = -1
        for (y in y0..y1) {
            if (rows[y] == 0) continue
            if (start < 0 || y - lastInk > bridge) {
                if (start >= 0) best = maxOf(best, lastInk - start + 1)
                start = y
            }
            lastInk = y
        }
        if (start >= 0) best = maxOf(best, lastInk - start + 1)
        return best
    }
}
