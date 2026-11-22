package com.abdulrahman.hardsubocr.core

/**
 * SubScan — Arabic-first burned-in subtitle tracker (successor of [SubtitleSegmenter]).
 *
 * Why VideoSubFinder struggles with Arabic, and what is done differently here:
 *
 *  1. VSF only accepts text that survives an AND of the text masks of `sub_frame_length`
 *     (8) consecutive frames, then filters it with glyph-shape rules written for separated
 *     Latin/CJK letters. Thin, connected Arabic strokes and the dots/tashkeel flicker under
 *     compression, so the intersection erodes them and the line is rejected (missed lines).
 *     → Here: a two-level (hysteresis) mask keeps weak anti-aliased strokes that touch strong
 *       ones, persistence is checked against the previous frame only, and a frame is accepted
 *       as "text" by a line-band test, not by glyph shapes.
 *
 *  2. VSF decides "new subtitle" per text line from vertical-edge pixel differences with a
 *     30 % tolerance. Arabic has few vertical edges and a lot of flickering dots, so the ratio
 *     crosses 30 % in the middle of a subtitle → one line saved as two images (split).
 *     → Here: each subtitle keeps a majority-vote reference mask built from all its frames;
 *       a change needs text *lost from the reference* or *new text outside it* (both jitter
 *       tolerant), must persist for several frames (hysteresis), and short blanks/flashes or
 *       false changes are re-joined when the same text comes back (split repair).
 *
 *  3. Every subtitle is exported as a multi-frame composite: text pixels are identical in all
 *     frames while the background moves, so an order statistic across frames suppresses the
 *     background and keeps the letters (much cleaner input for OCR).
 */
enum class SubLang(val code: String) { ARABIC("ar"), ENGLISH("en") }

/** LIGHT = white/yellow/bright text with an outline or shadow (most hard-subs). DARK = dark text on a light band. */
enum class SubStyle { LIGHT, DARK }

data class ScanParams(
    val lang: SubLang = SubLang.ARABIC,
    val style: SubStyle = SubStyle.LIGHT,
    /** Strong text pixel: brightness and local contrast against its darkest neighbour. */
    val brightHigh: Int = 150,
    val contrastHigh: Int = 60,
    /** Weak text pixel, accepted only when 8-connected to a strong one (thin strokes, fades). */
    val brightLow: Int = 110,
    val contrastLow: Int = 38,
    /** Outline search radius in grid pixels. */
    val radius: Int = 1,
    /** Strong pixels must lie on a thin bright stroke: darker pixels on BOTH sides within this radius. */
    val ridgeRadius: Int = 4,
    /** Components at least this big are strokes; smaller ones survive only within [attachRadius] of a stroke. */
    val strokeComponent: Int = 16,
    val attachRadius: Int = 5,
    val minTextPixels: Int = 24,
    val minTextFraction: Double = 0.0006,
    /** A text line must span at least this fraction of the ROI width. */
    val minSpanFraction: Double = 0.010,
    /**
     * A real subtitle change is TWO-SIDED: text disappears from the reference AND new text appears
     * (different sentences: both ≈ 0.7). A scene cut behind the same subtitle only shrinks or grows
     * its mask (one side ≈ 0.5, the other ≈ 0) — that is exactly what makes VSF split one line in two.
     */
    val changeBoth: Double = 0.25,
    /** One-sided change large enough to be a new subtitle anyway. */
    val changeExtreme: Double = 0.85,
    /** Same, per text line, both sides (one line of a two-line subtitle replaced). */
    val bandThreshold: Double = 0.60,
    /** Persistent one-sided growth (e.g. a second dialogue line appears): the reference is replaced. */
    val growGain: Double = 0.30,
    val growConfirmFrames: Int = 8,
    /** Analysed frames a change must persist before it is accepted. */
    val confirmFrames: Int = 3,
    val minDurationMs: Long = 160,
    /** Fade-in: during this time a fuller version of the same text replaces the reference. */
    val growWindowMs: Long = 700,
    /** Re-join pieces of the same subtitle (false splits, flashes, short occlusions). */
    val repairSplits: Boolean = true,
    val bridgeMs: Long = 280,
    /** Frames kept per subtitle for the background-cleaning composite (1 = best frame only). */
    val compositeFrames: Int = 5,
    val captureSpacingMs: Long = 200
) {
    companion object {
        /** Tuned defaults per language. [sensitivity] 0..1 scales the change thresholds (0.5 = default). */
        fun preset(
            lang: SubLang,
            style: SubStyle = SubStyle.LIGHT,
            brightHigh: Int = 150,
            contrastHigh: Int = 60,
            repairSplits: Boolean = true,
            composite: Boolean = true,
            minDurationMs: Long = 160,
            changeTolerance: Double? = null
        ): ScanParams {
            val bh = brightHigh.coerceIn(60, 250)
            val ch = contrastHigh.coerceIn(15, 200)
            val bl = (bh - 40).coerceAtLeast(50)
            val cl = (ch * 0.63).toInt().coerceAtLeast(10)
            return when (lang) {
                // Arabic: dots/tashkeel flicker, cursive strokes → wider tolerance, longer confirmation.
                SubLang.ARABIC -> ScanParams(
                    lang, style, bh, ch, bl, cl,
                    changeBoth = changeTolerance ?: 0.25,
                    bandThreshold = 0.60,
                    confirmFrames = 3,
                    minDurationMs = minDurationMs,
                    repairSplits = repairSplits,
                    bridgeMs = 440,
                    compositeFrames = if (composite) 5 else 1
                )
                // Latin: stable vertical strokes → tighter, reacts faster to quick dialogue changes.
                SubLang.ENGLISH -> ScanParams(
                    lang, style, bh, ch, bl, cl,
                    changeBoth = changeTolerance ?: 0.22,
                    bandThreshold = 0.56,
                    confirmFrames = 2,
                    minDurationMs = minDurationMs,
                    repairSplits = repairSplits,
                    bridgeMs = 360,
                    compositeFrames = if (composite) 5 else 1
                )
            }
        }
    }
}

// ================================================================================ mask

object ScanMask {

    /** Hysteresis text mask of a luma grid (see class doc, point 1). */
    fun compute(g: LumaGrid, p: ScanParams): TextMask {
        val w = g.width
        val h = g.height
        val n = w * h
        val bits = BooleanArray(n)
        if (w < 3 || h < 3) return TextMask(w, h, bits, 0)
        val inv = p.style == SubStyle.DARK
        val v = IntArray(n)
        for (i in 0 until n) {
            val raw = g.data[i].toInt() and 0xFF
            v[i] = if (inv) 255 - raw else raw
        }
        val nmin = minFilter(v, w, h, p.radius.coerceAtLeast(1))
        // 0 = none, 1 = weak, 2 = strong
        val cls = ByteArray(n)
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        for (i in 0 until n) {
            val c = v[i]
            if (c < p.brightLow) continue
            val d = c - nmin[i]
            if (c >= p.brightHigh && d >= p.contrastHigh && ridge(v, w, h, i, c - p.contrastHigh, p.ridgeRadius)) {
                cls[i] = 2; bits[i] = true; queue[qt++] = i
            } else if (d >= p.contrastLow) {
                cls[i] = 1
            }
        }
        // grow strong pixels into connected weak pixels (8-neighbourhood)
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
                    if (cls[j].toInt() == 1 && !bits[j]) {
                        bits[j] = true; queue[qt++] = j
                    }
                }
            }
        }
        return componentFilter(bits, w, h, p)
    }

    /**
     * Keeps only pixels on subtitle-like text lines: rows standing out above the clutter level,
     * grouped into bands no taller than 80 % of the region, extended up/down so that tashkeel and
     * dots stay attached. Applied after old static pixels were removed (see SubtitleTracker.fgSince).
     */
    fun lineFilter(m: TextMask, p: ScanParams): TextMask {
        if (m.count == 0) return m
        val w = m.width
        val h = m.height
        val rows = IntArray(h)
        var maxRow = 0
        for (y in 0 until h) {
            var c = 0
            val row = y * w
            for (x in 0 until w) if (m.bits[row + x]) c++
            rows[y] = c
            if (c > maxRow) maxRow = c
        }
        // robust: clutter raises every row (median), a text line stands out above it
        val med = rows.sorted()[h / 2]
        val thr = maxOf(3, med + ((maxRow - med) * 0.15).toInt())
        val keepRow = BooleanArray(h)
        var y = 0
        var any = false
        while (y < h) {
            if (rows[y] < thr) { y++; continue }
            var e = y
            var last = y
            while (e + 1 < h && (rows[e + 1] >= thr || (e + 1 - last <= 2 && rows.sliceSum(e + 1, minOf(h - 1, last + 3), thr)))) {
                e++; if (rows[e] >= thr) last = e
            }
            val top = y
            val bot = last
            val bh = bot - top + 1
            var lo = w
            var hi = -1
            var c = 0
            for (yy in top..bot) {
                val row = yy * w
                for (x in 0 until w) if (m.bits[row + x]) {
                    c++; if (x < lo) lo = x; if (x > hi) hi = x
                }
            }
            if (bh >= 2 && bh <= h * 0.8 && c >= p.minTextPixels / 2) {
                val margin = maxOf(2, (bh * 0.45).toInt())
                for (yy in maxOf(0, top - margin)..minOf(h - 1, bot + margin)) keepRow[yy] = true
                any = true
            }
            y = bot + 1
        }
        if (!any) return TextMask(w, h, BooleanArray(w * h), 0)
        val out = BooleanArray(w * h)
        var c = 0
        for (yy in 0 until h) if (keepRow[yy]) {
            val row = yy * w
            for (x in 0 until w) if (m.bits[row + x]) {
                out[row + x] = true; c++
            }
        }
        return TextMask(w, h, out, c)
    }

    private fun IntArray.sliceSum(a: Int, b: Int, thr: Int): Boolean {
        for (i in a..b) if (this[i] >= thr) return true
        return false
    }

    /**
     * Keeps stroke-sized components, and small ones (Arabic dots, tashkeel, Latin i-dots, commas)
     * only when they sit close to a stroke. Scattered isolated blobs — lit windows, sparkles,
     * static background texture — are removed.
     */
    fun componentFilter(bits: BooleanArray, w: Int, h: Int, p: ScanParams): TextMask {
        val n = w * h
        val label = IntArray(n)
        val sizes = ArrayList<Int>()
        sizes.add(0)
        val queue = IntArray(n)
        for (s0 in 0 until n) {
            if (!bits[s0] || label[s0] != 0) continue
            val id = sizes.size
            var qh = 0
            var qt = 0
            queue[qt++] = s0; label[s0] = id
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
                        if (bits[j] && label[j] == 0) {
                            label[j] = id; queue[qt++] = j
                        }
                    }
                }
            }
            sizes.add(qt)
        }
        val big = BooleanArray(n)
        var bigCount = 0
        for (i in 0 until n) if (label[i] != 0 && sizes[label[i]] >= p.strokeComponent) {
            big[i] = true; bigCount++
        }
        if (bigCount == 0) return TextMask(w, h, BooleanArray(n), 0)
        val near = TextMaskOps.dilate(TextMask(w, h, big, bigCount), p.attachRadius)
        val out = BooleanArray(n)
        var c = 0
        for (i in 0 until n) if (bits[i] && (big[i] || near[i])) {
            out[i] = true; c++
        }
        return TextMask(w, h, out, c)
    }

    /**
     * Thin-stroke test: a darker pixel (<= [limit]) on both the left and the right, or both above and
     * below, within [r]. Letter strokes pass; the edge of a big bright area (shirt, wall, sky) does not.
     */
    private fun ridge(v: IntArray, w: Int, h: Int, i: Int, limit: Int, r: Int): Boolean {
        val x = i % w
        val y = i / w
        var a = false
        var b = false
        for (k in 1..r) {
            if (!a && x - k >= 0 && v[i - k] <= limit) a = true
            if (!b && x + k < w && v[i + k] <= limit) b = true
            if (a && b) return true
        }
        a = false; b = false
        for (k in 1..r) {
            if (!a && y - k >= 0 && v[i - k * w] <= limit) a = true
            if (!b && y + k < h && v[i + k * w] <= limit) b = true
            if (a && b) return true
        }
        return false
    }

    /** Square min filter (separable, edges clamped). */
    fun minFilter(v: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(v.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 255
                val a = maxOf(0, x - r)
                val b = minOf(w - 1, x + r)
                for (k in a..b) {
                    val t = v[row + k]; if (t < m) m = t
                }
                tmp[row + x] = m
            }
        }
        val out = IntArray(v.size)
        for (y in 0 until h) {
            val a = maxOf(0, y - r)
            val b = minOf(h - 1, y + r)
            for (x in 0 until w) {
                var m = 255
                for (k in a..b) {
                    val t = tmp[k * w + x]; if (t < m) m = t
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Horizontal text bands: rows with text, gaps of <= [gap] rows bridged (tashkeel sits apart). */
    fun bands(bits: BooleanArray, w: Int, h: Int, minRow: Int = 2, gap: Int = 2): List<IntArray> {
        val rows = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            val row = y * w
            for (x in 0 until w) if (bits[row + x]) c++
            rows[y] = c
        }
        val out = ArrayList<IntArray>()
        var start = -1
        var last = -1
        for (y in 0 until h) {
            if (rows[y] >= minRow) {
                if (start < 0) start = y
                else if (y - last - 1 > gap) {
                    out.add(intArrayOf(start, last)); start = y
                }
                last = y
            }
        }
        if (start >= 0) out.add(intArrayOf(start, last))
        return out
    }

    /** "Is there a subtitle line?" — pixel count plus at least one band with a real horizontal extent. */
    fun isText(m: TextMask, p: ScanParams): Boolean {
        val need = maxOf(p.minTextPixels, (p.minTextFraction * m.width * m.height).toInt())
        if (m.count < need) return false
        val w = m.width
        val minSpan = maxOf(4, (p.minSpanFraction * w).toInt())
        for (b in bands(m.bits, w, m.height)) {
            if (b[1] - b[0] + 1 < 2) continue
            var lo = w
            var hi = -1
            var c = 0
            for (y in b[0]..b[1]) {
                val row = y * w
                for (x in 0 until w) if (m.bits[row + x]) {
                    c++; if (x < lo) lo = x; if (x > hi) hi = x
                }
            }
            if (hi - lo + 1 >= minSpan && c >= p.minTextPixels / 2) return true
        }
        return false
    }
}

/** How a frame mask relates to a reference mask. */
class MaskCmp(val lost: Double, val gained: Double, val bandLost: Double, val bandGained: Double)

object MaskCompare {

    fun compare(
        ref: BooleanArray, refCount: Int, refDil: BooleanArray,
        cur: BooleanArray, curCount: Int, curDil: BooleanArray,
        w: Int, h: Int
    ): MaskCmp {
        var lost = 0
        var gained = 0
        val n = w * h
        for (i in 0 until n) {
            if (ref[i] && !curDil[i]) lost++
            if (cur[i] && !refDil[i]) gained++
        }
        val l = if (refCount == 0) 0.0 else lost.toDouble() / refCount
        val g = if (curCount == 0) 0.0 else gained.toDouble() / curCount
        return MaskCmp(l, g, worstBand(ref, refCount, curDil, w, h), worstBand(cur, curCount, refDil, w, h))
    }

    /** Max over significant text lines of a (fraction of that line not covered by [otherDil]). */
    private fun worstBand(a: BooleanArray, aCount: Int, otherDil: BooleanArray, w: Int, h: Int): Double {
        if (aCount == 0) return 0.0
        var worst = 0.0
        for (b in ScanMask.bands(a, w, h)) {
            var tot = 0
            var miss = 0
            for (y in b[0]..b[1]) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    if (a[i]) {
                        tot++; if (!otherDil[i]) miss++
                    }
                }
            }
            if (tot * 100 < aCount * 15) continue // ignore tiny bands (dots, noise)
            worst = maxOf(worst, miss.toDouble() / tot)
        }
        return worst
    }

    /** Symmetric distance used for re-joining split pieces (0 identical .. 1 unrelated). */
    fun distance(a: BooleanArray, aCount: Int, aDil: BooleanArray, b: BooleanArray, bCount: Int, bDil: BooleanArray): Double {
        if (aCount + bCount == 0) return 0.0
        var miss = 0
        for (i in a.indices) {
            if (a[i] && !bDil[i]) miss++
            if (b[i] && !aDil[i]) miss++
        }
        return miss.toDouble() / (aCount + bCount)
    }
}

// ================================================================================ tracker

/** A finished subtitle: [endUs] is exclusive (first frame without it). [mask] = its stable text mask on the grid. */
class ScanSegment(val id: Int, val startUs: Long, val endUs: Long, val mask: TextMask)

/** BEST: replace the best frame. POOL: add to the composite pool. RESTART: drop best+pool, this frame becomes best. */
enum class CaptureKind { BEST, POOL, RESTART }

class Capture(val segId: Int, val kind: CaptureKind)

class ScanDecision(val captures: List<Capture>, val closed: List<ScanSegment>, val discarded: List<Int>) {
    companion object {
        val NONE = ScanDecision(emptyList(), emptyList(), emptyList())
    }
}

class SubtitleTracker(private val p: ScanParams) {

    private inner class Seg(val id: Int, var startUs: Long, val isText: Boolean, w: Int, h: Int, val startFrame: Int) {
        var lastUs = startUs
        var endUs = -1L
        val counts = if (isText) ShortArray(w * h) else ShortArray(0)
        var n = 0
        var stable = BooleanArray(if (isText) w * h else 0)
        var stableCount = 0
        var stableDil = BooleanArray(0)
        var bestScore = Double.NEGATIVE_INFINITY
        var pool = 0
        var lastPoolUs = Long.MIN_VALUE / 2
        var growFrames = 0

        fun reseed(m: TextMask) {
            java.util.Arrays.fill(counts, 0.toShort())
            n = 0
            add(m)
        }

        fun add(m: TextMask) {
            if (!isText) return
            n++
            for (i in counts.indices) if (m.bits[i] && counts[i] < Short.MAX_VALUE) counts[i]++
            var c = 0
            val s = BooleanArray(counts.size)
            for (i in counts.indices) if (counts[i] * 2 >= n && counts[i] > 0) {
                s[i] = true; c++
            }
            stable = s
            stableCount = c
            stableDil = TextMaskOps.dilate(TextMask(m.width, m.height, s, c), DIL)
        }

        fun mask(w: Int, h: Int) = TextMask(w, h, stable, stableCount)
    }

    private class Cand(val startUs: Long, val prevUs: Long, var mask: TextMask, var dil: BooleanArray, val isText: Boolean, val startFrame: Int) {
        var count = 1
    }

    private var current: Seg? = null
    private var held: Seg? = null
    private var cand: Cand? = null
    private var nextId = 1
    private var prevRawDil: BooleanArray? = null
    private var firstMismatchUs = -1L
    private var prevPtsUs = -1L
    private var gw = 0
    private var gh = 0
    /** Consecutive analysed frames each pixel has been text-like (static background grows old). */
    private var age = IntArray(0)
    private var frameIdx = 0

    /** Pixels of [m] that appeared at or after [startFrame] (with slack): background that was already there is ignored. */
    private fun fgSince(m: TextMask, startFrame: Int): TextMask {
        val limit = frameIdx - startFrame + p.confirmFrames + 3
        val out = BooleanArray(m.bits.size)
        var c = 0
        for (i in out.indices) if (m.bits[i] && age[i] <= limit) {
            out[i] = true; c++
        }
        return if (c == m.count) m else TextMask(m.width, m.height, out, c)
    }

    var framesSeen = 0
        private set
    var lastMask: TextMask? = null
        private set

    private fun cmpTo(s: Seg, m: TextMask, mDil: BooleanArray): MaskCmp =
        MaskCompare.compare(s.stable, s.stableCount, s.stableDil, m.bits, m.count, mDil, gw, gh)

    private fun isChange(c: MaskCmp) =
        (c.lost >= p.changeBoth && c.gained >= p.changeBoth) ||
            c.lost >= p.changeExtreme || c.gained >= p.changeExtreme ||
            (c.bandLost >= p.bandThreshold && c.bandGained >= p.bandThreshold)

    fun push(ptsUs: Long, grid: LumaGrid): ScanDecision {
        framesSeen++
        gw = grid.width; gh = grid.height
        val before = prevPtsUs
        prevPtsUs = ptsUs

        val raw = ScanMask.compute(grid, p)
        val rawDil = TextMaskOps.dilate(raw, 1)
        // Burned-in text is static; moving bright background is not (one-frame persistence).
        val prev = prevRawDil
        val mAll = if (prev != null && prev.size == raw.bits.size) TextMaskOps.intersect(raw, prev) else raw
        prevRawDil = rawDil
        if (age.size != mAll.bits.size) age = IntArray(mAll.bits.size)
        for (i in age.indices) age[i] = if (mAll.bits[i]) age[i] + 1 else 0
        frameIdx++
        lastMask = mAll
        // judged against the current state: ignore what was already on screen before it began
        val m = ScanMask.lineFilter(current?.let { fgSince(mAll, it.startFrame) } ?: mAll, p)
        val text = ScanMask.isText(m, p)
        val mDil = TextMaskOps.dilate(m, DIL)

        val captures = ArrayList<Capture>(2)
        val closed = ArrayList<ScanSegment>(1)
        val discarded = ArrayList<Int>(1)

        val cur = current
        if (cur != null) {
            val match: Boolean
            var cmp: MaskCmp? = null
            var restart = false
            if (!cur.isText && !text) match = true
            else if (cur.isText != text) match = false
            else {
                val c = cmpTo(cur, m, mDil)
                cmp = c
                val young = ptsUs - cur.startUs <= p.growWindowMs * 1000
                val growth = c.lost <= 0.12 && c.gained >= p.growGain && m.count > cur.stableCount * 1.12
                match = (young && growth) || !isChange(c)
                if (match) {
                    cur.growFrames = if (growth) cur.growFrames + 1 else 0
                    if (growth && (young || cur.growFrames >= p.growConfirmFrames)) {
                        // fade-in finished / a line was added: the fuller text becomes the reference
                        cur.reseed(m)
                        cur.growFrames = 0
                        restart = true
                    }
                }
            }
            if (match) {
                cur.lastUs = ptsUs
                cand = null
                firstMismatchUs = -1
                if (cur.isText && cmp != null) {
                    if (restart) {
                        cur.pool = 0
                        cur.lastPoolUs = Long.MIN_VALUE / 2
                        cur.bestScore = m.count.toDouble()
                        captures.add(Capture(cur.id, CaptureKind.RESTART))
                    } else {
                        cur.add(m)
                        planCaptures(cur, ptsUs, m, cmp, captures)
                    }
                }
                expireHeld(ptsUs, closed, discarded)
                return ScanDecision(captures, closed, discarded)
            }
        }

        // ---- mismatch: candidate for a new state
        if (firstMismatchUs < 0) firstMismatchUs = ptsUs
        val c0 = cand
        val sameAsCand = c0 != null && c0.isText == text && (!text ||
            !isChange(MaskCompare.compare(c0.mask.bits, c0.mask.count, c0.dil, m.bits, m.count, mDil, gw, gh)))
        if (sameAsCand) {
            c0!!.count++
            val cm = ScanMask.lineFilter(fgSince(mAll, c0.startFrame), p)
            if (ScanMask.isText(cm, p) == c0.isText) {
                c0.mask = cm; c0.dil = TextMaskOps.dilate(cm, DIL)
            }
        } else {
            // a new state starts now: only what appeared in the last few frames belongs to it
            val cm = if (text) ScanMask.lineFilter(fgSince(mAll, frameIdx), p) else m
            val ct = if (text) ScanMask.isText(cm, p) else false
            cand = Cand(ptsUs, if (before >= 0) before else ptsUs, if (ct) cm else m, TextMaskOps.dilate(if (ct) cm else m, DIL), text, frameIdx - 1)
        }
        val cc = cand!!
        if (cur != null && cc.count < p.confirmFrames) {
            expireHeld(ptsUs, closed, discarded)
            return ScanDecision(captures, closed, discarded)
        }

        // ---- accept the new state
        var start = cc.prevUs
        if (cur != null) {
            val end = minOf(firstMismatchUs, cc.startUs)
            cur.endUs = end
            if (cur.isText) {
                start = maxOf(start, end)
                hold(cur, closed, discarded)
            }
        }
        current = null
        cand = null
        firstMismatchUs = -1

        if (cc.isText) {
            val h = held
            if (p.repairSplits && h != null && cc.startUs - h.endUs <= p.bridgeMs * 1000 &&
                !isChange(MaskCompare.compare(h.stable, h.stableCount, h.stableDil, cc.mask.bits, cc.mask.count, cc.dil, gw, gh))
            ) {
                // Same subtitle again after a flash / short blank / false change: continue it.
                held = null
                h.endUs = -1
                h.lastUs = ptsUs
                h.add(cc.mask)
                current = h
                planCaptures(h, ptsUs, cc.mask, cmpTo(h, cc.mask, cc.dil), captures)
                return ScanDecision(captures, closed, discarded)
            }
            val seg = Seg(nextId++, start, true, gw, gh, cc.startFrame)
            seg.lastUs = ptsUs
            seg.add(cc.mask)
            current = seg
            seg.bestScore = cc.mask.count.toDouble()
            captures.add(Capture(seg.id, CaptureKind.BEST))
        } else {
            current = Seg(nextId++, start, false, gw, gh, cc.startFrame)
        }
        expireHeld(ptsUs, closed, discarded)
        return ScanDecision(captures, closed, discarded)
    }

    private fun planCaptures(s: Seg, ptsUs: Long, m: TextMask, c: MaskCmp, out: MutableList<Capture>) {
        // quality = text that agrees with the reference minus text that does not
        val score = m.count * (1.0 - 2.0 * c.gained) - s.stableCount * c.lost
        if (score > s.bestScore * 1.04 + 1) {
            s.bestScore = score
            out.add(Capture(s.id, CaptureKind.BEST))
        }
        if (p.compositeFrames > 1 && s.pool < p.compositeFrames && c.lost <= 0.15 && c.gained <= 0.15 &&
            ptsUs - s.lastPoolUs >= p.captureSpacingMs * 1000
        ) {
            s.pool++
            s.lastPoolUs = ptsUs
            out.add(Capture(s.id, CaptureKind.POOL))
        }
    }

    private fun hold(s: Seg, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        val h = held
        if (h != null) emit(h, closed, discarded)
        if (p.repairSplits) held = s else {
            held = null; emit(s, closed, discarded)
        }
    }

    private fun expireHeld(ptsUs: Long, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        val h = held ?: return
        if (ptsUs - h.endUs > p.bridgeMs * 1000) {
            held = null
            emit(h, closed, discarded)
        }
    }

    private fun emit(s: Seg, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        if (s.endUs - s.startUs >= p.minDurationMs * 1000 && s.stableCount > 0) {
            closed.add(ScanSegment(s.id, s.startUs, s.endUs, s.mask(gw, gh)))
        } else discarded.add(s.id)
    }

    /** Call once after the last frame. [endUs] = pts of the last frame + one frame. */
    fun finish(endUs: Long): ScanDecision {
        val closed = ArrayList<ScanSegment>(2)
        val discarded = ArrayList<Int>(1)
        val cur = current
        current = null
        cand = null
        if (cur != null && cur.isText) {
            cur.endUs = if (firstMismatchUs >= 0) firstMismatchUs else endUs
            val h = held
            held = null
            if (h != null) emit(h, closed, discarded)
            emit(cur, closed, discarded)
        } else {
            val h = held
            held = null
            if (h != null) emit(h, closed, discarded)
        }
        firstMismatchUs = -1
        return ScanDecision(emptyList(), closed, discarded)
    }

    private companion object {
        /** Burned-in text does not move: pixel-exact comparison separates different sentences best. */
        const val DIL = 0
    }
}
