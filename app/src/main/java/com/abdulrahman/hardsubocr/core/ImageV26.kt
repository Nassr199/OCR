package com.abdulrahman.hardsubocr.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

class GrayImage(val width: Int, val height: Int, val data: ByteArray)

/**
 * prepare_image_for_ocr() of ocr_windows_v2.6.py, re-implemented in pure Kotlin with Pillow's exact
 * algorithms (crop → Lanczos upscale → grayscale → autocontrast(cutoff=1) → Contrast(1.12) → SHARPEN),
 * so a phone produces the same pixels as the Windows script. Verified pixel-by-pixel against Pillow.
 */
object ImageV26 {

    data class Options(
        /** Keep only the subtitle band (6–94 % width, 35–96 % height) like v2.6. Off for video ROI crops. */
        val cropBand: Boolean = true,
        /** ×2 below 220 px height, ×1.5 below 340 px (Lanczos). */
        val upscale: Boolean = true,
        /** grayscale + autocontrast + contrast + sharpen. */
        val enhance: Boolean = true,
        /** Optional final limit of the longest side (Lens accepts at most 1500 px). 0 = none. */
        val maxDimension: Int = 0,
        /**
         * The band never cuts through the subtitle ([textSafeBand]). Off = v2.6's fixed band exactly
         * (what the Pillow comparison checks); the app turns it on.
         */
        val keepText: Boolean = false,
        /**
         * Then keep only the subtitle itself with generous margins ([SubtitleFrame], 2.9.0): the AI
         * engines see the whole picture through a fixed number of tokens, so the letters get more of
         * them, and the scene and watermarks less. Nothing is cut when the subtitle is not clear.
         */
        val frame: Boolean = false
    )

    /** The subtitle's area found by [Options.frame] in the last [prepare] of this thread (diagnostics). */
    val lastFrame = ThreadLocal<SubtitleFrame.Box?>()

    fun prepare(argb: IntArray, width: Int, height: Int, opt: Options = Options()): GrayImage {
        var px = argb
        var w = width
        var h = height
        if (opt.cropBand) {
            val left = (w * 0.06).toInt()
            val right = (w * 0.94).toInt()
            var top = (h * 0.35).toInt()
            var bottom = (h * 0.96).toInt()
            if (opt.keepText && right - left >= 64 && bottom - top >= 32) {
                val band = textSafeBand(px, w, h, left, right, top, bottom)
                top = band[0]; bottom = band[1]
            }
            if (right - left >= 64 && bottom - top >= 32) {
                px = crop(px, w, left, top, right, bottom)
                w = right - left
                h = bottom - top
            }
        }
        lastFrame.set(null)
        if (opt.frame) {
            SubtitleFrame.find(px, w, h)?.let { b ->
                lastFrame.set(b)
                px = crop(px, w, b.left, b.top, b.right, b.bottom)
                w = b.width
                h = b.height
            }
        }
        if (opt.upscale) {
            val scale = when {
                h < 220 -> 2.0
                h < 340 -> 1.5
                else -> 1.0
            }
            if (scale != 1.0) {
                val nw = (w * scale).toInt()
                val nh = (h * scale).toInt()
                px = resizeRgb(px, w, h, nw, nh)
                w = nw; h = nh
            }
        }
        var gray = toGray(px)
        if (opt.enhance) {
            gray = autocontrast(gray, 1)
            gray = contrast(gray, 1.12f)
            gray = sharpen(gray, w, h)
        }
        var img = GrayImage(w, h, gray)
        if (opt.maxDimension > 0 && maxOf(w, h) > opt.maxDimension) {
            val s = opt.maxDimension.toDouble() / maxOf(w, h)
            val nw = maxOf(1, (w * s).toInt())
            val nh = maxOf(1, (h * s).toInt())
            img = GrayImage(nw, nh, resizeGray(gray, w, h, nw, nh))
        }
        return img
    }

    /**
     * v2.6's band (35–96 % of the height), widened so it never cuts through the subtitle: v2.6 was
     * written for whole video frames, where the subtitle sits low, but the picture is often a band
     * already (a VideoSubFinder crop, ~6:1) with a two-line subtitle starting in its upper third, and
     * the fixed cut went through the first line. Text rows are found by their sharp light/dark steps
     * (white letters with a dark outline or box; soft scene rows have few) and grouped into blocks —
     * the lines of one subtitle are closer to each other than an eighth of the picture. Every block
     * that reaches into v2.6's band is kept whole; text far above it (a logo, a sign in the scene)
     * stays outside as in v2.6. A band-shaped picture with nothing in v2.6's band keeps its strongest
     * block. Returns [top, bottom] — v2.6's own when no text crosses its edges.
     */
    fun textSafeBand(px: IntArray, w: Int, h: Int, left: Int, right: Int, top0: Int, bottom0: Int): IntArray {
        val steps = IntArray(h)
        for (y in 0 until h) {
            val o = y * w
            var n = 0
            var prev = lum(px[o + left])
            for (x in left + 1 until right) {
                val l = lum(px[o + x])
                if (Math.abs(l - prev) >= 64) n++
                prev = l
            }
            steps[y] = n
        }
        val strongest = steps.max()
        // a line of subtitle text has dozens of steps across the band; under 1 % of its width is no text
        if (strongest < maxOf(6, (right - left) / 100)) return intArrayOf(top0, bottom0)
        val textual = maxOf(4, strongest / 4)
        val gapMax = maxOf(3, h / 8)
        // blocks of text rows: [first row, last row + 1, weight]
        val blocks = ArrayList<IntArray>()
        var y = 0
        while (y < h) {
            if (steps[y] < textual) { y++; continue }
            var lastText = y
            var weight = steps[y]
            var k = y + 1
            while (k < h && k - lastText <= gapMax) {
                if (steps[k] >= textual) { lastText = k; weight += steps[k] }
                k++
            }
            blocks.add(intArrayOf(y, lastText + 1, weight))
            y = lastText + 1
        }
        var keep = blocks.filter { it[1] > top0 && it[0] < bottom0 }
        if (keep.isEmpty() && w * 2 >= h * 5) keep = listOfNotNull(blocks.maxByOrNull { it[2] })
        if (keep.isEmpty()) return intArrayOf(top0, bottom0)
        var first = keep.minOf { it[0] }
        var last = keep.maxOf { it[1] }
        // the letters' tops and tails (alef and lam, dots, tashkeel above, descenders) have far fewer
        // steps than the body of the line: followed while rows still have some
        val low = maxOf(3, strongest / 20)
        var gap = 0
        var r = first - 1
        while (r >= 0 && gap <= 2) { if (steps[r] >= low) { first = r; gap = 0 } else gap++; r-- }
        gap = 0
        r = last
        while (r < h && gap <= 2) { if (steps[r] >= low) { last = r + 1; gap = 0 } else gap++; r++ }
        val margin = maxOf(2, h / 25)
        return intArrayOf(
            if (first < top0) maxOf(0, first - margin) else top0,
            if (last > bottom0) minOf(h, last + margin) else bottom0
        )
    }

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    fun crop(px: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): IntArray {
        val cw = r - l
        val out = IntArray(cw * (b - t))
        for (y in t until b) System.arraycopy(px, y * w + l, out, (y - t) * cw, cw)
        return out
    }

    /** Pillow RGB→L: (R*19595 + G*38470 + B*7471 + 0x8000) >> 16 */
    fun toGray(px: IntArray): ByteArray {
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) ushr 16).toByte()
        }
        return out
    }

    /** ImageOps.autocontrast(image, cutoff) */
    fun autocontrast(gray: ByteArray, cutoffPercent: Int): ByteArray {
        val h = LongArray(256)
        for (v in gray) h[v.toInt() and 0xFF]++
        if (cutoffPercent > 0) {
            var n = 0L
            for (x in h) n += x
            var cut = n * cutoffPercent / 100
            for (lo in 0 until 256) {
                if (cut > h[lo]) {
                    cut -= h[lo]; h[lo] = 0
                } else {
                    h[lo] -= cut; cut = 0
                }
                if (cut <= 0) break
            }
            cut = n * cutoffPercent / 100
            for (hi in 255 downTo 0) {
                if (cut > h[hi]) {
                    cut -= h[hi]; h[hi] = 0
                } else {
                    h[hi] -= cut; cut = 0
                }
                if (cut <= 0) break
            }
        }
        var lo = 0
        while (lo < 255 && h[lo] == 0L) lo++
        var hi = 255
        while (hi > 0 && h[hi] == 0L) hi--
        val lut = IntArray(256)
        if (hi <= lo) {
            for (i in 0 until 256) lut[i] = i
        } else {
            val scale = 255.0 / (hi - lo)
            val offset = -lo * scale
            for (i in 0 until 256) {
                var v = (i * scale + offset).toInt() // Python int(): truncation toward zero
                if (v < 0) v = 0 else if (v > 255) v = 255
                lut[i] = v
            }
        }
        val out = ByteArray(gray.size)
        for (i in gray.indices) out[i] = lut[gray[i].toInt() and 0xFF].toByte()
        return out
    }

    /** ImageEnhance.Contrast(image).enhance(factor) = Image.blend(mean-gray, image, factor) */
    fun contrast(gray: ByteArray, factor: Float): ByteArray {
        if (gray.isEmpty()) return gray
        var sum = 0L
        for (v in gray) sum += (v.toInt() and 0xFF)
        val mean = (sum.toDouble() / gray.size + 0.5).toInt()
        val out = ByteArray(gray.size)
        val extrapolate = factor < 0f || factor > 1f
        for (i in gray.indices) {
            val v = gray[i].toInt() and 0xFF
            val temp: Float = mean.toFloat() + factor * (v - mean).toFloat()
            out[i] = if (extrapolate) {
                when {
                    temp <= 0f -> 0
                    temp >= 255f -> 255
                    else -> temp.toInt()
                }.toByte()
            } else temp.toInt().toByte()
        }
        return out
    }

    /** ImageFilter.SHARPEN: 3x3 kernel (-2…32…-2)/16, border pixels copied unchanged. */
    fun sharpen(gray: ByteArray, w: Int, h: Int): ByteArray {
        val out = gray.copyOf()
        if (w < 3 || h < 3) return out
        for (y in 1 until h - 1) {
            val r0 = (y - 1) * w
            val r1 = y * w
            val r2 = (y + 1) * w
            for (x in 1 until w - 1) {
                fun g(i: Int) = (gray[i].toInt() and 0xFF)
                val neighbours = g(r0 + x - 1) + g(r0 + x) + g(r0 + x + 1) + g(r1 + x - 1) + g(r1 + x + 1) +
                    g(r2 + x - 1) + g(r2 + x) + g(r2 + x + 1)
                val ss = 0.5f + 2.0f * g(r1 + x) - 0.125f * neighbours
                out[r1 + x] = when {
                    ss <= 0f -> 0
                    ss >= 255f -> 255
                    else -> ss.toInt()
                }.toByte()
            }
        }
        return out
    }

    // --------------------------------------------------------------------- Pillow Lanczos resample

    private const val PRECISION_BITS = 22

    private fun sinc(x0: Double): Double {
        if (x0 == 0.0) return 1.0
        val x = x0 * PI
        return sin(x) / x
    }

    private fun lanczos(x: Double): Double = if (-3.0 <= x && x < 3.0) sinc(x) * sinc(x / 3) else 0.0

    private class Coeffs(val ksize: Int, val bounds: IntArray, val k: IntArray)

    private fun precompute(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = 3.0 * filterscale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val pre = DoubleArray(outSize * ksize)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var ww = 0.0
            val ss = 1.0 / filterscale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            val base = xx * ksize
            for (x in 0 until xmax) {
                val w = lanczos((x + xmin - center + 0.5) * ss)
                pre[base + x] = w
                ww += w
            }
            for (x in 0 until xmax) if (ww != 0.0) pre[base + x] /= ww
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        val k = IntArray(pre.size)
        for (i in pre.indices) {
            val v = pre[i] * (1 shl PRECISION_BITS)
            k[i] = if (pre[i] < 0) (-0.5 + v).toInt() else (0.5 + v).toInt()
        }
        return Coeffs(ksize, bounds, k)
    }

    private fun clip8(v: Int): Int {
        val s = v shr PRECISION_BITS
        return if (s < 0) 0 else if (s > 255) 255 else s
    }

    /** Image.resize((nw, nh), LANCZOS) for RGB: horizontal pass then vertical pass, 8-bit fixed point. */
    fun resizeRgb(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val ch = precompute(w, nw)
        val cv = precompute(h, nh)
        val needH = nw != w
        val needV = nh != h
        var src = px
        var sw = w
        var rowsFirst = 0
        var rowsCount = h
        if (needH) {
            // only the rows the vertical pass needs
            rowsFirst = if (needV) cv.bounds[0] else 0
            val last = if (needV) cv.bounds[nh * 2 - 2] + cv.bounds[nh * 2 - 1] else h
            rowsCount = last - rowsFirst
            val tmp = IntArray(nw * rowsCount)
            for (yy in 0 until rowsCount) {
                val rowIn = (yy + rowsFirst) * w
                for (xx in 0 until nw) {
                    val xmin = ch.bounds[xx * 2]
                    val xmax = ch.bounds[xx * 2 + 1]
                    val kb = xx * ch.ksize
                    var s0 = 1 shl (PRECISION_BITS - 1)
                    var s1 = s0
                    var s2 = s0
                    for (x in 0 until xmax) {
                        val c = px[rowIn + x + xmin]
                        val kk = ch.k[kb + x]
                        s0 += ((c shr 16) and 0xFF) * kk
                        s1 += ((c shr 8) and 0xFF) * kk
                        s2 += (c and 0xFF) * kk
                    }
                    tmp[yy * nw + xx] = -0x1000000 or (clip8(s0) shl 16) or (clip8(s1) shl 8) or clip8(s2)
                }
            }
            src = tmp
            sw = nw
        }
        if (!needV) return src
        val out = IntArray(nw * nh)
        for (yy in 0 until nh) {
            val ymin = cv.bounds[yy * 2] - (if (needH) rowsFirst else 0)
            val ymax = cv.bounds[yy * 2 + 1]
            val kb = yy * cv.ksize
            for (xx in 0 until nw) {
                var s0 = 1 shl (PRECISION_BITS - 1)
                var s1 = s0
                var s2 = s0
                for (y in 0 until ymax) {
                    val c = src[(ymin + y) * sw + xx]
                    val kk = cv.k[kb + y]
                    s0 += ((c shr 16) and 0xFF) * kk
                    s1 += ((c shr 8) and 0xFF) * kk
                    s2 += (c and 0xFF) * kk
                }
                out[yy * nw + xx] = -0x1000000 or (clip8(s0) shl 16) or (clip8(s1) shl 8) or clip8(s2)
            }
        }
        return out
    }

    /** Same Lanczos for a single 8-bit channel (used to fit Lens' 1500 px limit). */
    fun resizeGray(g: ByteArray, w: Int, h: Int, nw: Int, nh: Int): ByteArray {
        val px = IntArray(g.size) { val v = g[it].toInt() and 0xFF; -0x1000000 or (v shl 16) or (v shl 8) or v }
        val r = resizeRgb(px, w, h, nw, nh)
        return ByteArray(r.size) { (r[it] and 0xFF).toByte() }
    }
}

/** Minimal 8-bit grayscale PNG encoder (smaller than RGBA and identical pixels). */
object PngGray {
    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(len ushr 24); out.write(len ushr 16 and 0xFF); out.write(len ushr 8 and 0xFF); out.write(len and 0xFF)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t); crc.update(data)
        val c = crc.value
        out.write((c ushr 24).toInt() and 0xFF); out.write((c ushr 16).toInt() and 0xFF)
        out.write((c ushr 8).toInt() and 0xFF); out.write(c.toInt() and 0xFF)
    }

    fun encode(img: GrayImage): ByteArray {
        val w = img.width
        val h = img.height
        val raw = ByteArray((w + 1) * h)
        for (y in 0 until h) {
            val o = y * (w + 1)
            raw[o] = 1 // filter: Sub (good for text strips)
            val r = y * w
            raw[o + 1] = img.data[r]
            for (x in 1 until w) raw[o + 1 + x] = (img.data[r + x] - img.data[r + x - 1]).toByte()
        }
        val def = Deflater(6)
        def.setInput(raw)
        def.finish()
        val z = ByteArrayOutputStream(raw.size / 3 + 64)
        val buf = ByteArray(65536)
        while (!def.finished()) {
            val n = def.deflate(buf)
            z.write(buf, 0, n)
        }
        def.end()
        val out = ByteArrayOutputStream(z.size() + 64)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        ihdr[0] = (w ushr 24).toByte(); ihdr[1] = (w ushr 16).toByte(); ihdr[2] = (w ushr 8).toByte(); ihdr[3] = w.toByte()
        ihdr[4] = (h ushr 24).toByte(); ihdr[5] = (h ushr 16).toByte(); ihdr[6] = (h ushr 8).toByte(); ihdr[7] = h.toByte()
        ihdr[8] = 8; ihdr[9] = 0; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }
}
