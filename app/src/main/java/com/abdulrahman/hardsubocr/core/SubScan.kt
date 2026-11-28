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
    val captureSpacingMs: Long = 200,
    /** Temporal stability: a pixel is text only if present in [stableNeed] of the last [stableWindow] frames. */
    val stableWindow: Int = 5,
    val stableNeed: Int = 4,
    /** Letters/words closer than this (grid px, horizontally) form one text object. */
    val wordGap: Int = 6,
    /** Mean motion energy above which a text-like object is moving scenery, not a subtitle. */
    val motionLimit: Double = 0.12,
    /**
     * A separate block of text appearing next to the current text (a subtitle next to the credits,
     * «حبيبي» 0.4 s after "Saç Tasarım"), or vanishing while the rest stays, starts a new subtitle
     * instead of being absorbed as "growth".
     */
    val farCut: Boolean = true,
    /**
     * When the text around a subtitle disappears and nothing new comes (the credits beside «أبي»
     * fade out), the part of the subtitle still on screen keeps the state alive instead of turning
     * into "background" and leaving a hole in the subtitle's time. Everything else is decided
     * exactly as without it.
     */
    val carryText: Boolean = true,
    /**
     * A line cut into pieces by moving scenery (a number plate or chrome passing behind a short line
     * over a fast scene) is ONE subtitle again — one piece, one picture — when the detector sees that
     * it never left the screen: the pixels on screen without a break since the first piece began still
     * form a line; nothing that stood still has left without something standing beside it (a subtitle
     * replaced, the line gone); nothing new has come and stayed far from that line (a word or a line
     * added, the next subtitle). Moving scenery does not stay on a pixel, so it gives no such proof.
     * Pieces are joined only once this has been watched long enough; where it fails, they are left as
     * found. Only with [repairSplits], in 2.1's way ([legacy21]); no other decision of the detector
     * changes. The proof also goes
     * with the pieces it does not join ([ScanSegment.continuesId]): if they read the same, they are
     * one line ([Arbiter.joinUnbroken]).
     */
    val joinUnbroken: Boolean = true
) {
    /** The 2.1 engine (one-frame persistence, no motion filter) — used by the ensemble. */
    fun legacy21(): ScanParams = copy(stableWindow = 1, stableNeed = 1, motionLimit = 1.0)

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
    fun compute(g: LumaGrid, p: ScanParams): TextMask = fromClasses(classify(g, p), g.width, g.height, p)

    /**
     * Per-pixel stage (the GPU computes exactly the same bytes, see video/GpuClassifier):
     * 0 = not text, 1 = weak (bright enough + some contrast), 2 = strong (bright, high contrast,
     * on a thin stroke).
     */
    fun classify(g: LumaGrid, p: ScanParams): ByteArray {
        val w = g.width
        val h = g.height
        val n = w * h
        val cls = ByteArray(n)
        if (w < 3 || h < 3) return cls
        val inv = p.style == SubStyle.DARK
        val v = IntArray(n)
        for (i in 0 until n) {
            val raw = g.data[i].toInt() and 0xFF
            v[i] = if (inv) 255 - raw else raw
        }
        val nmin = minFilter(v, w, h, p.radius.coerceAtLeast(1))
        for (i in 0 until n) {
            val c = v[i]
            if (c < p.brightLow) continue
            val d = c - nmin[i]
            if (c >= p.brightHigh && d >= p.contrastHigh && ridge(v, w, h, i, c - p.contrastHigh, p.ridgeRadius)) {
                cls[i] = 2
            } else if (d >= p.contrastLow) {
                cls[i] = 1
            }
        }
        return cls
    }

    /** Hysteresis (strong pixels grow into 8-connected weak ones) + component filter. */
    fun fromClasses(cls: ByteArray, w: Int, h: Int, p: ScanParams): TextMask {
        val n = w * h
        val bits = BooleanArray(n)
        if (w < 3 || h < 3) return TextMask(w, h, bits, 0)
        val queue = IntArray(n)
        var qh = 0
        var qt = 0
        for (i in 0 until n) if (cls[i].toInt() == 2) {
            bits[i] = true; queue[qt++] = i
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

    /** Dilation by [rx] horizontally and [ry] vertically (joins the letters/words of one line). */
    fun dilateXY(a: BooleanArray, w: Int, h: Int, rx: Int, ry: Int): BooleanArray {
        val tmp = BooleanArray(a.size)
        for (y in 0 until h) {
            val row = y * w
            var last = -100000
            for (x in 0 until w) {
                if (a[row + x]) last = x
                if (x - last <= rx) tmp[row + x] = true
            }
            last = 100000
            for (x in w - 1 downTo 0) {
                if (a[row + x]) last = x
                if (last - x <= rx) tmp[row + x] = true
            }
        }
        if (ry <= 0) return tmp
        val out = BooleanArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (!tmp[y * w + x]) continue
            for (yy in maxOf(0, y - ry)..minOf(h - 1, y + ry)) out[yy * w + x] = true
        }
        return out
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

/**
 * A finished subtitle: [endUs] is exclusive (first frame without it). [mask] = its stable text mask on
 * the grid; [x0]..[y1] = bounding box of that mask in grid pixels (inclusive).
 */
class ScanSegment(
    val id: Int,
    val startUs: Long,
    val endUs: Long,
    val mask: TextMask,
    /** Id of the piece just before this one whose text never left the screen up to this one ([ScanParams.joinUnbroken]); -1 = no proof. */
    val continuesId: Int = -1
) {
    val x0: Int
    val y0: Int
    val x1: Int
    val y1: Int

    init {
        var a = mask.width
        var b = mask.height
        var c = -1
        var d = -1
        for (i in mask.bits.indices) if (mask.bits[i]) {
            val x = i % mask.width
            val y = i / mask.width
            if (x < a) a = x; if (x > c) c = x; if (y < b) b = y; if (y > d) d = y
        }
        if (c < 0) {
            a = 0; b = 0; c = mask.width - 1; d = mask.height - 1
        }
        x0 = a; y0 = b; x1 = c; y1 = d
    }
}

/** BEST: replace the best frame. POOL: add to the composite pool. RESTART: drop best+pool, this frame becomes best. */
enum class CaptureKind { BEST, POOL, RESTART }

class Capture(val segId: Int, val kind: CaptureKind)

class ScanDecision(val captures: List<Capture>, val closed: List<ScanSegment>, val discarded: List<Int>) {
    companion object {
        val NONE = ScanDecision(emptyList(), emptyList(), emptyList())
    }
}

class SubtitleTracker(private val p: ScanParams) {

    /** Proof of an unbroken line (see [ScanParams.joinUnbroken]). */
    private val proof = p.repairSplits && p.joinUnbroken
    /** The detector itself joins such a line (2.1's way of reading frames, one frame of persistence). */
    private val chain = proof && p.stableNeed <= 1

    /** Pieces joined into the line they continue (one subtitle, one picture) — for the report. */
    var joinedPieces = 0
        private set
    /** Pieces that started with their line still on screen (linked) / refused: no line stayed, something left, something new. */
    var linkOk = 0
        private set
    var linkNoLine = 0
        private set
    var linkGone = 0
        private set
    var linkNew = 0
        private set
    /** Joins undone while watching: nothing stayed as a line, something left, something new came and stayed. */
    var breakVoid = 0
        private set
    var breakGone = 0
        private set
    var breakNew = 0
        private set
    /** Per pixel of the frame's text mask: since when it has been there without a break (-1 = not now). */
    private var since = LongArray(0)
    private var leavingBuf = IntArray(256)
    private var leavingFrom = LongArray(256)

    /** Per pixel: when it last left after standing still with nothing standing still beside it (a line gone)... */
    private var goneAt = LongArray(0)
    /** ...and since when it had been there. */
    private var goneFrom = LongArray(0)
    /** The usual time between two analysed frames. */
    private var frameDtUs = 40_000L

    private inner class Seg(val id: Int, var startUs: Long, val isText: Boolean, w: Int, h: Int, val startFrame: Int) {
        /** frame index at which this state was created (startFrame may be an inherited age baseline) */
        val createdFrame = frameIdx
        /**
         * Set when only the remaining part of the previous subtitle is on screen (it is carried over:
         * older than [startFrame]). Such a state is judged as a state without text, like before.
         */
        var carry: BooleanArray? = null
        /**
         * A carried line still on screen when new text replaced the carried state: not part of this
         * state (nothing is judged with it), but it is the subtitle if this state's own text leaves.
         */
        var dormant: BooleanArray? = null
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
        /** The held piece whose line this one continues, as far as can be told yet (see [link], [watch]). */
        var continues: Seg? = null
        /** Pieces of the same line joined into this one; their ids go when this one is final. */
        val parts = ArrayList<Seg>(0)
        /** This piece's own end, before [parts] were joined into it. */
        var ownEndUs = -1L
        /** Nothing more joins this one (its line left, something new came, or no line stayed). */
        var chainClosed = false
        /** Its chain's line left at this time: it joins only if it ended by then. */
        var endCap = Long.MAX_VALUE
        /** Its chain was watched until only what stands still was left of it, and that was a line ([seenCount] pixels). */
        var lineSeen = false
        var seenCount = 0
        /** Its line was looked at once, [LINE_US] after it began. */
        var lineChecked = false
        /**
         * With [continues]: what was new when this piece began (what made the detector start it) and
         * since when. If it stays, this piece is a subtitle (or a still thing) of its own, not scenery
         * passing behind the line; when it has left, it was scenery ([ownLeft]).
         */
        var own: IntArray? = null
        var ownFrom = 0L
        var ownLeft = false
        /** The chain this piece was joined into (its picture and times are that one's). */
        var joinedInto: Seg? = null
        /**
         * The piece just before this one, when a text line never left the screen in between (pixels on
         * screen without a break since that piece began still form a line when this one starts, and
         * what stood still when it ended is that line). Goes with the piece ([ScanSegment.continuesId]):
         * the reading joins the two if they read the same ([Arbiter.joinUnbroken]).
         */
        var proofPrev: Seg? = null
        /** What stood still on screen when this piece ended (pixels without a break for [STATIC_US]). */
        var stillAtEnd: BooleanArray? = null

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
        /** the remaining part of the current subtitle, carried into this state (see [persistentOf]) */
        var carry: BooleanArray? = null
        /** with [carry]: the mask without it; every comparison uses this one, as before */
        var own: TextMask? = null
        var ownDil: BooleanArray? = null
    }

    private var current: Seg? = null
    private var held: Seg? = null
    private var cand: Cand? = null
    private var nextId = 1
    private var prevRawDil: BooleanArray? = null
    private var firstMismatchUs = -1L
    private var firstMismatchIdx = -1
    /** Start of the current run of frames losing the reference (A→B: A vanishes before B is stable). */
    private var lossRunUs = -1L
    private var farSinceUs = -1L
    private var farFrames = 0
    private var integral = IntArray(0)
    private var lossRunIdx = -1
    private var prevPtsUs = -1L
    private var gw = 0
    private var gh = 0
    private var presence = ByteArray(0)
    private val history = ArrayDeque<BooleanArray>()
    private val ptsRing = LongArray(64)

    /** Per-pixel motion energy: running average (~25 frames) of "this pixel flipped since the last frame". */
    private var energy = FloatArray(0)
    private var lastRaw: BooleanArray? = null

    private fun updateMotion(raw: TextMask) {
        val n = raw.bits.size
        if (energy.size != n) energy = FloatArray(n)
        val prev = lastRaw
        if (prev != null && prev.size == n) {
            for (i in 0 until n) energy[i] = energy[i] * 0.96f + if (prev[i] != raw.bits[i]) 0.04f else 0f
        }
        lastRaw = raw.bits
    }

    /**
     * Removes whole text-like objects whose area keeps changing (teeth while talking, jewellery,
     * foliage, water): a burned-in subtitle flips its pixels once when it appears and then sits still.
     */
    private fun suppressMoving(m: TextMask): TextMask {
        if (m.count == 0 || p.motionLimit >= 1.0) return m
        val w = m.width
        val h = m.height
        val n = m.bits.size
        val joined = ScanMask.dilateXY(m.bits, w, h, p.wordGap, 1)
        val label = IntArray(n)
        val queue = IntArray(n)
        val out = m.bits.copyOf()
        var removed = 0
        var next = 0
        for (s0 in 0 until n) {
            if (!joined[s0] || label[s0] != 0) continue
            next++
            var qh = 0
            var qt = 0
            queue[qt++] = s0; label[s0] = next
            var e = 0.0
            var c = 0
            while (qh < qt) {
                val i = queue[qh++]
                if (m.bits[i]) {
                    e += energy[i]; c++
                }
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (joined[j] && label[j] == 0) {
                            label[j] = next; queue[qt++] = j
                        }
                    }
                }
            }
            if (c > 0 && e / c > p.motionLimit) {
                for (k in 0 until qt) {
                    val i = queue[k]
                    if (out[i]) {
                        out[i] = false; removed++
                    }
                }
            }
        }
        return if (removed == 0) m else TextMask(w, h, out, m.count - removed)
    }

    private var legacyPrevDil: BooleanArray? = null

    private fun stabilize(raw: TextMask): TextMask {
        if (p.stableNeed <= 1) {
            // 2.1 behaviour: one-frame persistence
            val prev = legacyPrevDil
            legacyPrevDil = TextMaskOps.dilate(raw, 1)
            return if (prev != null && prev.size == raw.bits.size) TextMaskOps.intersect(raw, prev) else raw
        }
        val n = raw.bits.size
        if (presence.size != n) {
            presence = ByteArray(n); history.clear()
        }
        for (i in 0 until n) if (raw.bits[i]) presence[i]++
        history.addLast(raw.bits)
        if (history.size > p.stableWindow) {
            val old = history.removeFirst()
            for (i in 0 until n) if (old[i]) presence[i]--
        }
        val need = minOf(p.stableNeed, history.size)
        val out = BooleanArray(n)
        var c = 0
        for (i in 0 until n) if (raw.bits[i] && presence[i] >= need) {
            out[i] = true; c++
        }
        return TextMask(raw.width, raw.height, out, c)
    }

    /** Text becomes visible in the stable mask (stableNeed - 1) frames after it really appeared. */
    private fun backDate(us: Long): Long {
        val lag = p.stableNeed - 1
        if (lag <= 0 || frameIdx <= lag) return us
        // ptsRing holds the pts of analysed frames by index; find the frame `lag` before the one at `us`
        for (k in 0 until minOf(frameIdx, ptsRing.size - lag)) {
            val idx = frameIdx - 1 - k // 0-based index of an analysed frame
            if (ptsRing[idx % ptsRing.size] == us && idx - lag >= 0) return ptsRing[(idx - lag) % ptsRing.size]
        }
        return us
    }

    /** Consecutive analysed frames each pixel has been text-like (static background grows old). */
    private var age = IntArray(0)
    private var frameIdx = 0

    /** Pixels of [m] that appeared at or after [startFrame] (with slack): background that was already there is ignored. */
    private fun fgSince(m: TextMask, startFrame: Int, carry: BooleanArray? = null): TextMask {
        val limit = frameIdx - startFrame + p.confirmFrames + 3
        val out = BooleanArray(m.bits.size)
        var c = 0
        for (i in out.indices) if (m.bits[i] && (age[i] <= limit || (carry != null && carry[i]))) {
            out[i] = true; c++
        }
        return if (c == m.count) m else TextMask(m.width, m.height, out, c)
    }

    /**
     * The part of subtitle [s] that has stayed on screen without a break since the state was created
     * and is still there — only when it stands out as a line: static texture that came with a scene
     * change (and so got into the state) fills every row of the band about evenly and is not carried.
     */
    private fun persistentOf(s: Seg, mAll: TextMask): BooleanArray? {
        if (!p.carryText || !s.isText || s.carry != null) return null
        val need = frameIdx - s.createdFrame - 1
        if (need < 1) return null
        val d = s.dormant
        val n = mAll.bits.size
        val out = BooleanArray(n)
        val rowsAll = IntArray(gh)
        val carriedRow = BooleanArray(gh)
        var c = 0
        for (i in 0 until n) if (mAll.bits[i] && age[i] >= need) {
            val y = i / gw
            rowsAll[y]++
            if (s.stable[i] || (d != null && d[i])) {
                out[i] = true; c++; carriedRow[y] = true
            }
        }
        if (c < p.minTextPixels) return null
        // everything else that stayed on screen as long, row by row: the clutter level of this picture
        val other = ArrayList<Int>(gh)
        var sum = 0
        var rowsIn = 0
        for (y in 0 until gh) if (carriedRow[y]) {
            sum += rowsAll[y]; rowsIn++
        } else other.add(rowsAll[y])
        val clutter = if (other.size >= 3) other.sorted()[other.size / 2] else 0
        return if (sum.toDouble() / rowsIn >= 2.0 * clutter + 1) out else null
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

    fun push(ptsUs: Long, grid: LumaGrid): ScanDecision = pushRaw(ptsUs, ScanMask.compute(grid, p))

    /**
     * Same as [push] with the per-frame mask already computed ([ScanMask.compute] is independent per
     * frame, so the video scanner runs it on all CPU cores and feeds the results here in order).
     */
    fun pushRaw(ptsUs: Long, raw: TextMask): ScanDecision {
        framesSeen++
        gw = raw.width; gh = raw.height
        if (proof) trackPixels(ptsUs, raw.bits)
        val before = prevPtsUs
        prevPtsUs = ptsUs

        // Burned-in text sits on the same pixels frame after frame; moving detail (teeth, jewellery,
        // leaves, lights) does not: keep pixels present in >= stableNeed of the last stableWindow frames.
        updateMotion(raw)
        val mAll = suppressMoving(stabilize(raw))
        ptsRing[frameIdx % ptsRing.size] = ptsUs
        if (age.size != mAll.bits.size) age = IntArray(mAll.bits.size)
        for (i in age.indices) age[i] = if (mAll.bits[i]) age[i] + 1 else 0
        frameIdx++
        lastMask = mAll
        // judged against the current state: ignore what was already on screen before it began
        val m = ScanMask.lineFilter(current?.let { fgSince(mAll, it.startFrame, it.carry) } ?: mAll, p)
        val text = ScanMask.isText(m, p)
        val mDil = TextMaskOps.dilate(m, DIL)
        // a carried state is judged without its carried line, exactly as the state without text it replaces
        val carried = current?.carry != null
        val m0 = if (carried) ScanMask.lineFilter(fgSince(mAll, current!!.startFrame), p) else m
        val m0Dil = if (carried) TextMaskOps.dilate(m0, DIL) else mDil

        val captures = ArrayList<Capture>(2)
        val closed = ArrayList<ScanSegment>(1)
        val discarded = ArrayList<Int>(1)
        if (chain) watch(ptsUs, closed, discarded)

        val cur = current
        if (cur != null) {
            val match: Boolean
            var cmp: MaskCmp? = null
            var restart = false
            if (!cur.isText && !text) match = true
            else if (cur.isText != text) match = false
            else if (carried) {
                // only the remaining line is there: it lasts until something new is text (or it leaves)
                cmp = cmpTo(cur, m, mDil)
                match = !ScanMask.isText(m0, p)
            } else {
                val c = cmpTo(cur, m, mDil)
                cmp = c
                val young = ptsUs - cur.startUs <= p.growWindowMs * 1000
                val far = p.farCut && farChange(cur, m, c)
                if (far) {
                    if (farFrames == 0) farSinceUs = ptsUs
                    farFrames++
                } else {
                    farFrames = 0; farSinceUs = -1
                }
                val growth = !far && c.lost <= 0.12 && c.gained >= p.growGain && m.count > cur.stableCount * 1.12
                match = (young && growth) || !isChange(c)
                if (match && far && farFrames >= p.confirmFrames) {
                    // a separate text block appeared / vanished while the rest stays: the subtitle changed.
                    // The new state keeps the same age baseline, so it holds everything still on screen.
                    val at = farSinceUs
                    cur.endUs = at
                    hold(cur, closed, discarded)
                    val seg = Seg(nextId++, at, true, gw, gh, cur.startFrame)
                    seg.dormant = cur.dormant
                    seg.proofPrev = held?.let { lastPiece(it) }?.takeIf { unbroken(it, seg) }
                    seg.continues = link(held, seg, ptsUs, BooleanArray(m.bits.size) { m.bits[it] && !cur.stable[it] }, at)
                    seg.lastUs = ptsUs
                    seg.add(m)
                    seg.bestScore = m.count.toDouble()
                    current = seg
                    captures.add(Capture(seg.id, CaptureKind.BEST))
                    cand = null
                    firstMismatchUs = -1
                    lossRunUs = -1
                    farFrames = 0
                    farSinceUs = -1
                    expireHeld(ptsUs, closed, discarded)
                    return ScanDecision(captures, closed, discarded)
                }
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
                val lc = cmp
                if (lc != null && lc.lost >= p.changeBoth) {
                    if (lossRunUs < 0) {
                        lossRunUs = ptsUs; lossRunIdx = frameIdx
                    }
                } else {
                    lossRunUs = -1
                }
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
        if (firstMismatchUs < 0) {
            firstMismatchUs = ptsUs; firstMismatchIdx = frameIdx
        }
        val c0 = cand
        val c0Mask = c0?.own ?: c0?.mask
        val c0Dil = c0?.ownDil ?: c0?.dil
        val sameAsCand = c0 != null && c0.isText == text && (!text ||
            !isChange(MaskCompare.compare(c0Mask!!.bits, c0Mask.count, c0Dil!!, m0.bits, m0.count, m0Dil, gw, gh)))
        if (sameAsCand) {
            c0!!.count++
            val cm = ScanMask.lineFilter(fgSince(mAll, c0.startFrame), p)
            if (ScanMask.isText(cm, p) == c0.isText) {
                if (c0.carry != null) {
                    c0.own = cm; c0.ownDil = TextMaskOps.dilate(cm, DIL)
                    val wc = ScanMask.lineFilter(fgSince(mAll, c0.startFrame, c0.carry), p)
                    c0.mask = wc; c0.dil = TextMaskOps.dilate(wc, DIL)
                } else {
                    c0.mask = cm; c0.dil = TextMaskOps.dilate(cm, DIL)
                }
            }
        } else {
            // a new state starts now: only what appeared in the last few frames belongs to it
            val cm = if (text) ScanMask.lineFilter(fgSince(mAll, frameIdx), p) else m0
            val ct = if (text) ScanMask.isText(cm, p) else false
            val base = if (ct) cm else m0
            cand = Cand(ptsUs, if (before >= 0) before else ptsUs, base, if (ct) TextMaskOps.dilate(cm, DIL) else m0Dil, text, frameIdx - 1)
            // nothing new is text, yet part of the current subtitle is still on screen: it stays a
            // subtitle (before, it became "background" here and the subtitle's time got a hole)
            val carry = if (text && !ct && cur != null) persistentOf(cur, mAll) else null
            if (carry != null) {
                val wc = ScanMask.lineFilter(fgSince(mAll, frameIdx, carry), p)
                if (ScanMask.isText(wc, p)) {
                    val cd = cand!!
                    cd.carry = carry
                    cd.own = cd.mask; cd.ownDil = cd.dil
                    cd.mask = wc; cd.dil = TextMaskOps.dilate(wc, DIL)
                }
            }
        }
        val cc = cand!!
        if (cur != null && cc.count < p.confirmFrames) {
            expireHeld(ptsUs, closed, discarded)
            return ScanDecision(captures, closed, discarded)
        }

        // ---- accept the new state (start corrected for the stability delay)
        var start = if (cc.isText && p.stableNeed > 1) backDate(cc.startUs) else cc.prevUs
        if (cur != null) {
            var end = minOf(firstMismatchUs, cc.startUs)
            if (p.stableNeed > 1 && lossRunUs in 0 until end && firstMismatchIdx - lossRunIdx <= p.stableNeed) end = lossRunUs
            cur.endUs = end
            if (cur.isText) {
                // after a carried state the next one starts where it would after a state without text
                if (cur.carry != null) cur.endUs = minOf(end, start) else start = maxOf(start, end)
                hold(cur, closed, discarded)
            }
        }
        current = null
        cand = null
        firstMismatchUs = -1
        lossRunUs = -1

        if (cc.isText) {
            val h = held
            val om = cc.own ?: cc.mask
            val oDil = cc.ownDil ?: cc.dil
            val last = h?.let { lastPiece(it) }
            if (p.repairSplits && h != null && last != null && backDate(cc.startUs) - h.endUs <= p.bridgeMs * 1000 &&
                !isChange(MaskCompare.compare(last.stable, last.stableCount, last.stableDil, om.bits, om.count, oDil, gw, gh))
            ) {
                // Same subtitle again after a flash / short blank / false change: continue it.
                held = null
                val r = resumeChain(h, last, discarded, closed)
                r.endUs = -1
                r.lastUs = ptsUs
                r.add(om)
                current = r
                planCaptures(r, ptsUs, om, cmpTo(r, om, oDil), captures)
                return ScanDecision(captures, closed, discarded)
            }
            val seg = Seg(nextId++, start, true, gw, gh, cc.startFrame)
            seg.proofPrev = held?.let { lastPiece(it) }?.takeIf { unbroken(it, seg) }
            seg.continues = link(held, seg, ptsUs, om.bits, cc.startUs)
            seg.carry = cc.carry
            if (cc.carry == null && cur != null) seg.dormant = cur.carry ?: cur.dormant
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

    /**
     * True when a block of text at least [minFar] pixels big appeared farther than r from all the
     * current text (or such a block of the current text vanished while something stays). Fade-in
     * (new pixels next to the existing strokes) is never "far".
     */
    private fun farChange(s: Seg, m: TextMask, c: MaskCmp): Boolean {
        if (!s.isText || (c.gained < 0.02 && c.lost < 0.02)) return false
        if (frameIdx - s.startFrame <= 3 || s.n < 3) return false
        val minFar = maxOf(p.minTextPixels * 2, (0.05 * maxOf(s.stableCount, m.count)).toInt())
        if (s.stableCount < minFar) return false
        val r = maxOf(2, gh / 40)
        if (farCount(m.bits, s.stable, r, minFar) >= minFar) return true
        return m.count >= minFar && farCount(s.stable, m.bits, r, minFar) >= minFar
    }

    /** Pixels of [a] with no pixel of [b] within [r] (square window); stops counting at [stopAt]. */
    private fun farCount(a: BooleanArray, b: BooleanArray, r: Int, stopAt: Int): Int {
        var n = 0
        forEachFar(a, b, r) { n++; n < stopAt }
        return n
    }

    /** Calls [f] for each pixel of [a] with no pixel of [b] within [r] (a square); stops when [f] says false. */
    private inline fun forEachFar(a: BooleanArray, b: BooleanArray, r: Int, f: (Int) -> Boolean) {
        buildIntegralOf(b)
        for (y in 0 until gh) {
            val row = y * gw
            for (x in 0 until gw) {
                if (!a[row + x] || b[row + x]) continue
                if (windowCount(x, y, r) == 0 && !f(row + x)) return
            }
        }
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
        if (proof) s.stillAtEnd = stillNow(prevPtsUs)
        val h = held
        if (h != null && joins(s, h)) {
            // its line never left: one subtitle with the held piece (watched until it is final)
            join(h, s)
            return
        }
        if (h != null) settle(h, prevPtsUs, closed, discarded)
        if (p.repairSplits) held = s else {
            held = null; emit(s, closed, discarded)
        }
    }

    private fun expireHeld(ptsUs: Long, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        val h = held ?: return
        if (linked(current, h)) return // kept while the piece that continues its line is on
        if (ptsUs - h.endUs > p.bridgeMs * 1000) {
            held = null
            settle(h, ptsUs, closed, discarded)
        }
    }

    private fun linked(s: Seg?, h: Seg) = s != null && s.continues === h

    /** [s] (just ended) is one subtitle with [h]: linked, and ended before its line left. */
    private fun joins(s: Seg, h: Seg) = linked(s, h) && s.endUs <= s.endCap

    private fun lastPiece(h: Seg) = h.parts.lastOrNull() ?: h

    /**
     * The piece to go on with when the last piece of [h]'s chain ([last]) comes back after a flash
     * (as before: that piece goes on). With pieces joined into [h] whose scenery has passed, the line
     * never left: the chain becomes that one piece (from [h]'s start). Otherwise the chain is final
     * without it.
     */
    private fun resumeChain(h: Seg, last: Seg, discarded: MutableList<Int>, closed: MutableList<ScanSegment>): Seg {
        if (last === h) return h
        h.parts.remove(last)
        last.joinedInto = null
        last.continues = null
        if (h.lineSeen && h.parts.all { ownStays(it) < minEvidence() } && ownStays(last) < minEvidence()) {
            for (q in h.parts) discarded.add(q.id)
            joinedPieces += h.parts.size + 1
            h.parts.clear()
            discarded.add(h.id)
            last.startUs = h.startUs
            last.proofPrev = h.proofPrev
            return last
        }
        h.endUs = if (h.parts.isEmpty()) h.ownEndUs else h.parts.last().endUs
        settle(h, prevPtsUs, closed, discarded)
        return last
    }

    private fun join(h: Seg, s: Seg) {
        if (h.parts.isEmpty()) h.ownEndUs = h.endUs
        h.parts.add(s)
        s.joinedInto = h
        h.endUs = maxOf(h.endUs, s.endUs)
        h.lastUs = maxOf(h.lastUs, s.lastUs)
    }

    private fun emit(s: Seg, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        joinedPieces += s.parts.size
        for (q in s.parts) discarded.add(q.id)
        s.parts.clear()
        if (s.endUs - s.startUs >= p.minDurationMs * 1000 && s.stableCount > 0) {
            val prev = s.proofPrev?.let { it.joinedInto ?: it }
            closed.add(ScanSegment(s.id, s.startUs, s.endUs, s.mask(gw, gh), prev?.id ?: -1))
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
            if (h != null && joins(cur, h)) {
                join(h, cur)
                settle(h, prevPtsUs, closed, discarded)
            } else {
                if (h != null) settle(h, prevPtsUs, closed, discarded)
                emit(cur, closed, discarded)
            }
        } else {
            val h = held
            held = null
            if (h != null) settle(h, prevPtsUs, closed, discarded)
        }
        firstMismatchUs = -1
        return ScanDecision(emptyList(), closed, discarded)
    }

    /** Pixels on screen without a break for [STATIC_US] or more (what moves does not stay on a pixel). */
    private fun stillNow(nowUs: Long): BooleanArray {
        val out = BooleanArray(since.size)
        for (i in since.indices) if (since[i] >= 0 && nowUs - since[i] >= STATIC_US) out[i] = true
        return out
    }

    /**
     * Does [b], starting now, continue [a] (the piece just before, ended a moment ago) — for the
     * reading ([ScanSegment.continuesId])? On the frame masks: what has been on screen without a
     * break since [a] began is a text line, and what stood still when [a] ended is that line. What the
     * two pieces SAY is not known here: the reading decides ([Arbiter.joinUnbroken]). (3.0.4.)
     */
    private fun unbroken(a: Seg, b: Seg): Boolean {
        if (!proof || !a.isText) return false
        if (b.startUs - a.endUs > p.bridgeMs * 1000 || since.size != gw * gh) return false
        val kept = heldSince(a.startUs + READ_SLACK_US)
        if (kept.count < p.minTextPixels) return false
        // 1. a text line has stayed, unbroken
        if (!ScanMask.isText(ScanMask.lineFilter(kept, p), p)) return false
        // 2. what stood still is that line
        val still = a.stillAtEnd ?: return false
        val minFar = maxOf(p.minTextPixels * 2, (0.05 * kept.count).toInt())
        return farCount(still, kept.bits, maxOf(2, gh / 40), minFar) < minFar
    }

    // ---- one line cut into pieces by moving scenery (ScanParams.joinUnbroken) ----
    //
    // Burned-in text sits on its pixels from the moment it appears until it leaves; moving scenery does
    // not stay on a pixel. Per pixel the tracker keeps since when it has been on screen without a break
    // ([since]) and when it last left after standing still with nothing standing still beside it
    // ([goneAt]: the edge of a sliding bar or a flickering pixel of a subtitle has still neighbours, a
    // subtitle that leaves or is replaced has none). A chain = the held piece and the pieces joined into
    // it; its anchor is the held piece's start (+ a frame and a half: the line's pixels come with it).

    private fun lateUs() = maxOf(LATE_MIN_US, frameDtUs * 3 / 2)

    private fun anchorUs(h: Seg) = h.startUs + lateUs()

    private fun rNear() = maxOf(2, gh / 80)

    /** A pixel that leaves with something still standing this close stays with it: an edge that slid, not a line gone. */
    private fun rStay() = maxOf(2, gh / 30)

    private fun minEvidence() = p.minTextPixels * 2

    private fun trackPixels(ptsUs: Long, bits: BooleanArray) {
        val n = bits.size
        if (since.size != n) {
            since = LongArray(n) { -1L }
            goneAt = LongArray(n) { Long.MIN_VALUE }
            goneFrom = LongArray(n)
        }
        if (prevPtsUs in 0 until ptsUs) frameDtUs = ptsUs - prevPtsUs
        // one pass: since when each pixel has been on screen; the ones leaving after standing still
        var nl = 0
        for (i in 0 until n) {
            val t = since[i]
            if (bits[i]) {
                if (t < 0) since[i] = ptsUs
            } else if (t >= 0) {
                if (ptsUs - t >= STATIC_US) {
                    if (nl == leavingBuf.size) {
                        leavingBuf = leavingBuf.copyOf(maxOf(256, nl * 2)); leavingFrom = leavingFrom.copyOf(leavingBuf.size)
                    }
                    leavingBuf[nl] = i; leavingFrom[nl] = t; nl++
                }
                since[i] = -1L
            }
        }
        // a pixel leaving after standing still is gone, unless the thing it belongs to is still beside
        // it — slid along (something still standing close by) or moved over (on screen right next to it)
        if (nl > 0 && chain) {
            val r = rStay()
            val rm = rNear()
            for (k in 0 until nl) {
                val i = leavingBuf[k]
                val x = i % gw
                val y = i / gw
                if (anyNear(x, y, r) { j -> bits[j] && ptsUs - since[j] >= STATIC_US }) continue
                if (anyNear(x, y, rm) { j -> bits[j] }) continue
                goneAt[i] = ptsUs; goneFrom[i] = leavingFrom[k]
            }
        }
    }

    /** What has been on screen without a break since [anchor] (the line of the chain, if it is one). */
    private fun heldSince(anchor: Long): TextMask {
        val out = BooleanArray(since.size)
        var c = 0
        for (i in since.indices) {
            val t = since[i]
            if (t in 0..anchor) {
                out[i] = true; c++
            }
        }
        return TextMask(gw, gh, out, c)
    }

    private fun isLine(m: TextMask) = m.count >= p.minTextPixels && ScanMask.isText(ScanMask.lineFilter(m, p), p)

    /** Pixels of [m] in its text line's rows. */
    private fun lineRows(m: TextMask) = ScanMask.lineFilter(m, p).count

    /**
     * When (and since when) something on screen since [anchor] left after standing still at least
     * [LINE_US], with nothing still beside it: the line of the chain gone or replaced. Scenery that came
     * later is not the line; and a car driving away leaves pixels behind all the time, some after
     * standing a moment, few after [LINE_US].
     */
    private fun goneAfter(anchor: Long): Pair<LongArray, LongArray> {
        var c = 0
        for (i in goneAt.indices) if (isGone(i, anchor)) c++
        val at = LongArray(c)
        val from = LongArray(c)
        c = 0
        for (i in goneAt.indices) if (isGone(i, anchor)) {
            at[c] = goneAt[i]; from[c] = goneFrom[i]; c++
        }
        return at to from
    }

    private fun isGone(i: Int, anchor: Long) = goneAt[i] > anchor && goneFrom[i] <= anchor && goneAt[i] - goneFrom[i] >= LINE_US

    /**
     * The most of those that came together and left together (within [GONE_WINDOW_US] both) and the
     * middle of their leaving: a line came at once and leaves at once; scenery that stood a while
     * (an edge sliding, a bar thinning as a car drives away) came and leaves a few pixels at a time.
     */
    private fun goneBurst(anchor: Long): Pair<Int, Long> {
        val (at, from) = goneAfter(anchor)
        if (at.size < minEvidence()) return 0 to 0L
        val order = at.indices.sortedBy { at[it] }
        var best = 0
        var mid = 0L
        var j = 0
        val window = LongArray(at.size)
        for (i in order.indices) {
            // only at the last pixel that left in a frame (all of that frame are in the window then)
            if (i + 1 < order.size && at[order[i + 1]] == at[order[i]]) continue
            while (at[order[i]] - at[order[j]] > GONE_WINDOW_US) j++
            if (i - j + 1 <= best) continue
            // within this leaving window: the most that came within one window too
            var n = 0
            for (k in j..i) window[n++] = from[order[k]]
            java.util.Arrays.sort(window, 0, n)
            var a = 0
            for (b in 0 until n) {
                while (window[b] - window[a] > GONE_WINDOW_US) a++
                if (b - a + 1 > best) {
                    best = b - a + 1; mid = at[order[(i + j) / 2]]
                }
            }
        }
        return best to mid
    }

    /** Arrival times of text that came after [anchor], stayed [NEW_US], has settled and is far from [line]. */
    private fun newSince(anchor: Long, line: TextMask, nowUs: Long): LongArray {
        val stay = BooleanArray(since.size)
        var c = 0
        for (i in since.indices) {
            val t = since[i]
            if (t > anchor && nowUs - t >= NEW_US) {
                stay[i] = true; c++
            }
        }
        if (c < minEvidence()) return LongArray(0)
        // settled: nothing newer right beside it (not the growing end of something sliding along)
        val late = lateUs()
        val r = rNear()
        c = 0
        for (i in stay.indices) if (stay[i]) {
            if (newerNear(i, since[i] + late, r)) stay[i] = false else c++
        }
        if (c < minEvidence()) return LongArray(0)
        val out = ArrayList<Long>()
        forEachFar(stay, line.bits, rNear()) { out.add(since[it]); true }
        return out.toLongArray()
    }

    private fun median(a: LongArray): Long {
        a.sort()
        return a[a.size / 2]
    }

    /**
     * May [b], starting now, continue the line of [h] (held, ended a moment ago)? What has been on
     * screen without a break since [h] began is still a line, nothing that stood still has left
     * (a subtitle replaced or gone) and nothing new has come and stayed. Early in a chain this also
     * holds scenery that has not moved away yet: the chain is watched until it is sure ([watch]).
     */
    private fun link(h: Seg?, b: Seg, nowUs: Long, new: BooleanArray, newFrom: Long): Seg? {
        if (h == null || !chain || !h.isText || h.chainClosed) return null
        if (b.startUs - h.endUs > p.bridgeMs * 1000 || since.size != gw * gh) return null
        val anchor = anchorUs(h)
        val line = heldSince(anchor)
        if (!isLine(line) || (h.lineSeen && lineRows(line) < h.seenCount / 2)) {
            linkNoLine++; return null
        }
        if (goneBurst(anchor).first >= minEvidence()) {
            linkGone++; return null
        }
        if (newSince(anchor, line, nowUs).size >= minEvidence()) {
            linkNew++; return null
        }
        linkOk++
        // what made the detector start [b], apart from the line itself: if it stays, [b] is no scenery
        var c = 0
        for (i in new.indices) if (new[i] && !line.bits[i]) c++
        val o = IntArray(c)
        c = 0
        for (i in new.indices) if (new[i] && !line.bits[i]) o[c++] = i
        b.own = o
        b.ownFrom = newFrom
        return h
    }

    private enum class Break { VOID, GONE, OWN, NEW }

    /** What, if anything, breaks the chain of [h] now: the kind and the time from which its pieces go. */
    private fun chainBreak(h: Seg, nowUs: Long): Pair<Break, Long>? {
        val anchor = anchorUs(h)
        if (nowUs <= anchor) return null
        val (gone, goneMid) = goneBurst(anchor)
        if (gone >= minEvidence()) return Break.GONE to goneMid - lateUs()
        val line = heldSince(anchor)
        if (!h.lineSeen && nowUs >= anchor + LINE_US) {
            // what moves has left by now: what is left of the chain's start stands still — a line, or no chain
            if (!isLine(line)) return Break.VOID to h.startUs
            h.lineSeen = true
            h.seenCount = lineRows(line)
        }
        // the line (what stood still since the chain began) has mostly left
        if (h.lineSeen && lineRows(line) < h.seenCount / 2) return Break.GONE to nowUs - frameDtUs - lateUs()
        // a piece whose own new content stayed is a subtitle of its own (a word or a line came, the text changed)
        for (q in h.parts) if (ownStayed(q, nowUs)) return Break.OWN to q.startUs
        val cur = current
        if (cur != null && linked(cur, h) && ownStayed(cur, nowUs)) return Break.OWN to cur.startUs
        val fresh = newSince(anchor, line, nowUs)
        if (fresh.size >= minEvidence()) return Break.NEW to median(fresh) - lateUs()
        return null
    }

    /**
     * How much of what was new when [s] began is still there, without a break since then, and settled:
     * nothing newer right beside it. (A long bar sliding along itself keeps the pixels it newly covers
     * for a while, but it keeps growing beside them; a word that came to stay does not.)
     */
    private fun ownStays(s: Seg): Int {
        val o = s.own ?: return 0
        if (s.ownLeft) return 0
        val until = s.ownFrom + lateUs()
        val late = lateUs()
        val r = rNear()
        var c = 0
        for (i in o) {
            val t = since[i]
            if (t in 0..until && !newerNear(i, t + late, r)) c++
        }
        if (c < minEvidence()) s.ownLeft = true
        return c
    }

    /** Is anything on screen within [r] of pixel [i] that arrived after [t]? */
    private fun newerNear(i: Int, t: Long, r: Int): Boolean {
        val x = i % gw
        val y = i / gw
        return anyNear(x, y, r) { since[it] > t }
    }

    private fun ownStayed(s: Seg, nowUs: Long) = !s.ownLeft && nowUs >= s.startUs + STATIC_US && ownStays(s) >= minEvidence()

    /**
     * Every frame while pieces are joined (or about to be) into the held one: when the chain breaks,
     * the pieces from the break on are not joined to it. They become a chain of their own (watched
     * again from its own start), or go on as they were found.
     */
    private fun watch(nowUs: Long, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        for (round in 0 until 8) {
            val h = held ?: return
            val cur = current
            if (h.parts.isEmpty() && !linked(cur, h)) {
                // a held piece alone: once, when what moves has left, see whether its line stayed
                if (h.isText && !h.lineSeen && !h.lineChecked && nowUs >= anchorUs(h) + LINE_US) {
                    h.lineChecked = true
                    val line = heldSince(anchorUs(h))
                    if (isLine(line) && goneBurst(anchorUs(h)).first < minEvidence()) {
                        h.lineSeen = true; h.seenCount = lineRows(line)
                    } else h.chainClosed = true
                }
                return
            }
            val (kind, cut) = chainBreak(h, nowUs) ?: return
            h.chainClosed = true
            val k = h.parts.indexOfFirst { it.startUs >= cut }
            val curAfter = linked(cur, h) && cur!!.startUs >= cut
            if (k >= 0 || curAfter) when (kind) {
                Break.VOID -> breakVoid++
                Break.GONE -> breakGone++
                Break.OWN, Break.NEW -> breakNew++
            }
            if (k < 0) {
                // nothing joined after the break. The piece now on screen joins only if it ends by the
                // time the line left (its end is not the line's end otherwise); after anything else, never
                if (linked(cur, h)) {
                    if (kind == Break.GONE) cur!!.endCap = minOf(cur.endCap, cut + lateUs()) else cur!!.continues = null
                }
                return
            }
            val rest = h.parts.subList(k, h.parts.size).toList()
            val keep = h.parts.subList(0, k).toList()
            h.parts.clear()
            h.parts.addAll(keep)
            h.endUs = if (keep.isEmpty()) h.ownEndUs else keep.last().endUs
            for (q in rest) q.joinedInto = null
            held = null
            settle(h, nowUs, closed, discarded)
            // the rest: a chain of its own, its line watched from its own start
            val nh = rest[0]
            nh.continues = null
            nh.ownEndUs = nh.endUs
            for (q in rest.drop(1)) {
                nh.parts.add(q)
                q.continues = nh
                q.joinedInto = nh
                nh.endUs = maxOf(nh.endUs, q.endUs)
                nh.lastUs = maxOf(nh.lastUs, q.lastUs)
            }
            held = nh
            if (curAfter) cur!!.continues = nh
            // what the new chain's line is: what has been on screen without a break since it began
            val nl = heldSince(anchorUs(nh))
            for (q in nh.parts + listOfNotNull(cur?.takeIf { curAfter })) {
                val o = q.own ?: continue
                q.own = o.filter { !nl.bits[it] }.toIntArray()
            }
        }
    }

    /**
     * [h] is final now: it goes with the pieces whose line was watched long enough to be sure; the
     * others go on as they were found, each with its proof, so the reading can still join them.
     */
    private fun settle(h: Seg, nowUs: Long, closed: MutableList<ScanSegment>, discarded: MutableList<Int>) {
        if (h.parts.isNotEmpty()) {
            if (!h.lineSeen && nowUs >= anchorUs(h) + LINE_US && goneBurst(anchorUs(h)).first < minEvidence()) {
                val line = heldSince(anchorUs(h))
                if (isLine(line)) {
                    h.lineSeen = true; h.seenCount = lineRows(line)
                }
            }
            // a joined piece is sure once what made the detector start it has left (scenery passing by)
            val k = if (!h.lineSeen) 0 else h.parts.indexOfFirst { ownStays(it) >= minEvidence() }
            if (k >= 0) {
                val rest = h.parts.subList(k, h.parts.size).toList()
                val keep = h.parts.subList(0, k).toList()
                h.parts.clear()
                h.parts.addAll(keep)
                h.endUs = if (keep.isEmpty()) h.ownEndUs else keep.last().endUs
                for (q in rest) q.joinedInto = null
                emit(h, closed, discarded)
                for (q in rest) emit(q, closed, discarded)
                return
            }
        }
        emit(h, closed, discarded)
    }

    private fun buildIntegralOf(b: BooleanArray) = buildIntegral { b[it] }

    /** Is there a pixel where [on] holds in the square of radius [r] around (x, y)? */
    private inline fun anyNear(x: Int, y: Int, r: Int, on: (Int) -> Boolean): Boolean {
        for (yy in maxOf(0, y - r)..minOf(gh - 1, y + r)) {
            val row = yy * gw
            for (xx in maxOf(0, x - r)..minOf(gw - 1, x + r)) if (on(row + xx)) return true
        }
        return false
    }

    /** Summed-area table of the pixels where [on] holds (for [windowCount]). */
    private inline fun buildIntegral(on: (Int) -> Boolean) {
        val w = gw
        val h = gh
        val stride = w + 1
        if (integral.size != stride * (h + 1)) integral = IntArray(stride * (h + 1))
        val ii = integral
        for (y in 0 until h) {
            var run = 0
            val row = y * w
            val o = (y + 1) * stride
            for (x in 0 until w) {
                if (on(row + x)) run++
                ii[o + x + 1] = ii[o - stride + x + 1] + run
            }
        }
    }

    /** Pixels counted by the last [buildIntegral] in the square of radius [r] around (x, y). */
    private fun windowCount(x: Int, y: Int, r: Int): Int {
        val stride = gw + 1
        val x0 = maxOf(0, x - r)
        val x1 = minOf(gw, x + r + 1)
        val y0 = maxOf(0, y - r)
        val y1 = minOf(gh, y + r + 1)
        val ii = integral
        return ii[y1 * stride + x1] - ii[y0 * stride + x1] - ii[y1 * stride + x0] + ii[y0 * stride + x0]
    }

    private companion object {
        /** Burned-in text does not move: pixel-exact comparison separates different sentences best. */
        const val DIL = 0
        /** The pixels of a line come with its piece: up to this long (or a frame and a half) after the piece began. */
        const val LATE_MIN_US = 50_000L
        /** The same for the reading's proof ([unbroken], as in 3.0.4). */
        const val READ_SLACK_US = 100_000L
        /** On screen without a break this long, it stands still (a moving thing does not stay on a pixel). */
        const val STATIC_US = 200_000L
        /**
         * New text on screen without a break this long has come to stay. Measured on a car driving
         * behind a short line (the user's scene): far from the line, at most 9 pixels of the scenery
         * stay 160 ms, 3 stay 200 ms, none 300 ms; a word added to a subtitle stays with it.
         */
        const val NEW_US = 160_000L
        /** After this long, what was on screen at a chain's start and is still there stands still (nothing moving stays). */
        const val LINE_US = 300_000L
        /** What leaves within this long leaves together (a line, even one fading out, leaves at once). */
        const val GONE_WINDOW_US = 120_000L
    }
}
