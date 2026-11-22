package com.abdulrahman.hardsubocr.core

/**
 * Video hard-sub detection (new on Android — replaces VideoSubFinder for phones).
 *
 * Every analysed frame is reduced to a small luma grid of the subtitle region (ROI).
 * A "text mask" marks bright pixels that have a dark outline/shadow next to them — the
 * signature of burned-in subtitles. Unlike a plain frame difference (v17 of the Windows
 * app), this mask barely changes when the *video behind* the subtitle moves, but it
 * changes completely when the subtitle text changes. A small state machine turns the
 * stream of masks into frame-accurate [start, end] segments, and asks the decoder to
 * capture one sharp colour crop per segment for OCR.
 */
class LumaGrid(val width: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size >= width * height) { "grid buffer too small" }
    }

    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

data class MaskParams(
    /** Minimum brightness of a subtitle pixel (white/yellow text ≈ 190-255). */
    val brightThreshold: Int = 175,
    /** How much darker the outline/shadow must be. */
    val contrastDelta: Int = 60,
    /** Outline search distance in grid pixels. */
    val radius: Int = 1,
    /** Dark text on a bright band (rare). */
    val darkText: Boolean = false
)

class TextMask(val width: Int, val height: Int, val bits: BooleanArray, val count: Int)

object TextMaskOps {

    fun compute(g: LumaGrid, p: MaskParams): TextMask {
        val w = g.width
        val h = g.height
        val bits = BooleanArray(w * h)
        val r = p.radius.coerceAtLeast(1)
        val inv = p.darkText
        val data = g.data
        var count = 0
        fun v(x: Int, y: Int): Int {
            val raw = data[y * w + x].toInt() and 0xFF
            return if (inv) 255 - raw else raw
        }
        if (w <= 2 * r || h <= 2 * r) return TextMask(w, h, bits, 0)
        for (y in r until h - r) {
            for (x in r until w - r) {
                val c = v(x, y)
                if (c < p.brightThreshold) continue
                val limit = c - p.contrastDelta
                var hit = false
                var d = 1
                while (d <= r && !hit) {
                    if (v(x - d, y) <= limit || v(x + d, y) <= limit || v(x, y - d) <= limit || v(x, y + d) <= limit ||
                        v(x - d, y - d) <= limit || v(x + d, y + d) <= limit || v(x - d, y + d) <= limit ||
                        v(x + d, y - d) <= limit
                    ) hit = true
                    d++
                }
                if (hit) {
                    bits[y * w + x] = true; count++
                }
            }
        }
        return TextMask(w, h, bits, count)
    }

    /** Square dilation (separable). */
    fun dilate(m: TextMask, r: Int): BooleanArray {
        if (r <= 0) return m.bits.copyOf()
        val w = m.width
        val h = m.height
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!m.bits[row + x]) continue
                val a = maxOf(0, x - r)
                val b = minOf(w - 1, x + r)
                for (k in a..b) tmp[row + k] = true
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (!tmp[row + x]) continue
                val a = maxOf(0, y - r)
                val b = minOf(h - 1, y + r)
                for (k in a..b) out[k * w + x] = true
            }
        }
        return out
    }

    /** a AND bDil, as a new mask. */
    fun intersect(a: TextMask, bDil: BooleanArray): TextMask {
        val out = BooleanArray(a.bits.size)
        var c = 0
        for (i in out.indices) if (a.bits[i] && bDil[i]) {
            out[i] = true; c++
        }
        return TextMask(a.width, a.height, out, c)
    }

    /** Symmetric mismatch tolerant to ±r pixel jitter: 0 = identical, 1 = unrelated. */
    fun distance(a: TextMask, aDil: BooleanArray, b: TextMask, bDil: BooleanArray): Double {
        if (a.count + b.count == 0) return 0.0
        var miss = 0
        val n = a.bits.size
        for (i in 0 until n) {
            if (a.bits[i] && !bDil[i]) miss++
            if (b.bits[i] && !aDil[i]) miss++
        }
        return miss.toDouble() / (a.count + b.count).toDouble()
    }
}

data class SegmenterParams(
    val mask: MaskParams = MaskParams(),
    /** A frame "has text" when this fraction of the ROI grid is text pixels. */
    val minTextFraction: Double = 0.003,
    val minTextPixels: Int = 16,
    /** Mask distance above which the subtitle is considered changed. */
    val changeThreshold: Double = 0.32,
    /** Analysed frames a new state must stay stable before it is accepted (debounce). */
    val stableFrames: Int = 2,
    val minDurationMs: Long = 200,
    val dilateRadius: Int = 1,
    /** Within this time after a subtitle appears, a fuller (faded-in) frame replaces the capture. */
    val recaptureWindowMs: Long = 1500
)

class ClosedSegment(val id: Int, val startUs: Long, val endUs: Long)

class FrameDecision(val captureFor: Int?, val closed: List<ClosedSegment>, val discarded: List<Int>) {
    companion object {
        val NONE = FrameDecision(null, emptyList(), emptyList())
    }
}

class SubtitleSegmenter(private val p: SegmenterParams = SegmenterParams()) {

    private class Seg(
        val id: Int, val startUs: Long, var lastUs: Long, var ref: TextMask, var refDil: BooleanArray,
        val isText: Boolean, var bestCount: Int
    )

    /** [prevUs] = pts of the analysed frame just before the candidate: with temporal persistence
     *  the change becomes visible one frame late, so that is the true start. */
    private class Cand(
        val startUs: Long, val prevUs: Long, var mask: TextMask, var dil: BooleanArray, var count: Int, val isText: Boolean
    )

    private var current: Seg? = null
    private var cand: Cand? = null
    private var nextId = 1
    private var prevRawDil: BooleanArray? = null
    /** pts of the first frame that stopped matching the current segment (its real end). */
    private var firstMismatchUs: Long = -1
    private var prevPtsUs: Long = -1
    var framesSeen = 0
        private set
    var lastMask: TextMask? = null
        private set

    private fun hasText(m: TextMask): Boolean =
        m.count >= maxOf(p.minTextPixels, (p.minTextFraction * m.width * m.height).toInt())

    private fun same(aText: Boolean, a: TextMask, aDil: BooleanArray, bText: Boolean, b: TextMask, bDil: BooleanArray): Boolean {
        if (!aText && !bText) return true
        if (aText != bText) return false
        return TextMaskOps.distance(a, aDil, b, bDil) <= p.changeThreshold
    }

    fun push(ptsUs: Long, grid: LumaGrid): FrameDecision = pushRaw(ptsUs, TextMaskOps.compute(grid, p.mask))

    /** Same as [push] with the frame's text mask already computed (e.g. restricted to a subtitle box). */
    fun pushRaw(ptsUs: Long, raw: TextMask): FrameDecision {
        framesSeen++
        val before = prevPtsUs
        prevPtsUs = ptsUs
        val rawDil = TextMaskOps.dilate(raw, p.dilateRadius)
        // Temporal persistence: keep only pixels that were also text in the previous frame.
        // Burned-in text is static, moving bright background (lights, sky, explosions) is not.
        val m = prevRawDil?.let { TextMaskOps.intersect(raw, it) } ?: raw
        prevRawDil = rawDil
        lastMask = m
        val text = hasText(m)
        val dil = if (m === raw) rawDil else TextMaskOps.dilate(m, p.dilateRadius)
        val cur = current

        if (cur != null && same(cur.isText, cur.ref, cur.refDil, text, m, dil)) {
            cur.lastUs = ptsUs
            cand = null
            firstMismatchUs = -1
            if (cur.isText && ptsUs - cur.startUs <= p.recaptureWindowMs * 1000 && m.count > cur.bestCount * 1.10) {
                cur.bestCount = m.count; cur.ref = m; cur.refDil = dil
                return FrameDecision(cur.id, emptyList(), emptyList())
            }
            return FrameDecision.NONE
        }

        if (firstMismatchUs < 0) firstMismatchUs = ptsUs
        val c = cand
        if (c != null && same(c.isText, c.mask, c.dil, text, m, dil)) {
            c.count++; c.mask = m; c.dil = dil
        } else {
            cand = Cand(ptsUs, if (before >= 0) before else ptsUs, m, dil, 1, text)
        }
        val cc = cand!!
        if (cc.count < p.stableFrames) return FrameDecision.NONE

        // Accept the new state: close the old segment at the moment the change started.
        val closed = ArrayList<ClosedSegment>(1)
        val discarded = ArrayList<Int>(1)
        var start = cc.prevUs
        if (cur != null) {
            val end = minOf(firstMismatchUs, cc.startUs)
            closeInto(cur, end, closed, discarded)
            if (cur.isText) start = maxOf(start, end)
        }
        val seg = Seg(nextId++, start, ptsUs, cc.mask, cc.dil, cc.isText, cc.mask.count)
        current = seg
        cand = null
        firstMismatchUs = -1
        return FrameDecision(if (seg.isText) seg.id else null, closed, discarded)
    }

    private fun closeInto(s: Seg, endUs: Long, closed: MutableList<ClosedSegment>, discarded: MutableList<Int>) {
        if (!s.isText) return
        if (endUs - s.startUs >= p.minDurationMs * 1000) closed.add(ClosedSegment(s.id, s.startUs, endUs))
        else discarded.add(s.id)
    }

    /** Call once after the last frame. [endUs] = pts of last frame + one frame duration. */
    fun finish(endUs: Long): FrameDecision {
        val cur = current ?: return FrameDecision.NONE
        val closed = ArrayList<ClosedSegment>(1)
        val discarded = ArrayList<Int>(1)
        val end = if (firstMismatchUs >= 0) firstMismatchUs else endUs
        closeInto(cur, end, closed, discarded)
        current = null
        cand = null
        firstMismatchUs = -1
        return FrameDecision(null, closed, discarded)
    }
}
