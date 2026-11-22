package com.abdulrahman.hardsubocr.core

/**
 * Subtitles drawn inside an opaque dark box (fansub style "BorderStyle=3"): finds the box itself, so
 * everything outside it — the Turkish credits, the channel logo, "2. Ekip …", earrings, teeth —
 * can be ignored. Credits written next to (or behind) the box are what made one line start early
 * («حبيبي» at 52.300 instead of ~52.700), cut lines into short pieces and turned «أبي» into "2.".
 *
 * A box is found by growing a rectangle out of each piece of text: a side moves out while the new
 * row/column is >= 90 % dark-or-text and >= 85 % of its non-text pixels are dark (the box's
 * padding — the letters themselves are holes in it). The grown rectangle is a box when at least 3 of
 * its sides are clean edges: just outside, the non-text pixels are mostly NOT dark (the scene). The
 * 4th side may touch a dark jacket or shadow. Credits written on the scene never grow (no dark
 * padding around them); a black jacket has no straight clean edges.
 */
object SubBox {

    class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
        fun contains(x: Int, y: Int) = x >= x0 && x < x1 && y >= y0 && y < y1
        fun touches(o: Box) = x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1
        override fun toString() = "[$x0,$y0 ${w}x$h]"
    }

    class Found(val boxes: List<Box>, val black: Int, val threshold: Int)

    /** Test hook: told why each grown rectangle was rejected. */
    @JvmField var debug: ((String) -> Unit)? = null

    /** Darkest level that is common enough to be a box (0.4th percentile). */
    fun blackLevel(g: LumaGrid): Int {
        val hist = IntArray(256)
        val n = g.width * g.height
        for (i in 0 until n) hist[g.data[i].toInt() and 0xFF]++
        val need = maxOf(1, n / 250)
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= need) return v
        }
        return 255
    }

    /**
     * [minHeight]: smallest box height in grid pixels. The scanner passes 4.5 % of the frame height
     * (a subtitle box around one word is taller than that; a credit letter standing on a black
     * jacket is not).
     */
    fun find(g: LumaGrid, mask: TextMask, minHeight: Int = 24, minSize: Int = 8): Found {
        val black = blackLevel(g)
        if (black > 48) return Found(emptyList(), black, -1)
        // pure black first (a box next to a dark jacket stays separate), then a wider tolerance
        for (tol in intArrayOf(8, 14)) {
            val boxes = scan(g, mask, black + tol, minSize, minHeight)
            if (boxes.isNotEmpty()) return Found(boxes, black, black + tol)
        }
        return Found(emptyList(), black, black + 14)
    }

    private fun scan(g: LumaGrid, mask: TextMask, t: Int, minSize: Int, minHeight: Int): List<Box> {
        val w = g.width
        val h = g.height
        val n = w * h
        val dark = BooleanArray(n)
        for (i in 0 until n) dark[i] = (g.data[i].toInt() and 0xFF) <= t
        // text and its halo (JPEG ringing around the letters is neither dark nor text)
        val text = BooleanArray(n)
        val r = 2
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask.bits[y * w + x]) continue
            for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) text[yy * w + xx] = true
        }

        // one line of pixels: (total, text, dark among non-text)
        val stat = IntArray(3)
        fun line(horizontal: Boolean, fixed: Int, from: Int, to: Int) {
            var tot = 0; var tx = 0; var dk = 0
            for (k in from until to) {
                val i = if (horizontal) fixed * w + k else k * w + fixed
                tot++
                if (text[i]) tx++ else if (dark[i]) dk++
            }
            stat[0] = tot; stat[1] = tx; stat[2] = dk
        }
        fun inside(horizontal: Boolean, fixed: Int, from: Int, to: Int): Boolean {
            line(horizontal, fixed, from, to)
            val nonText = stat[0] - stat[1]
            if (nonText == 0) return true
            return (stat[1] + stat[2]) >= 0.93 * stat[0] && stat[2] >= 0.93 * nonText
        }
        /**
         * Clean edge: just outside (1–3 px: the edge itself is blurred by scaling / JPEG) the
         * non-text pixels are mostly NOT dark — the scene.
         */
        fun scene(horizontal: Boolean, fixed: Int, outward: Int, from: Int, to: Int): Double {
            val limit = if (horizontal) h else w
            var best = 0.0
            for (d in 0..2) {
                val f = fixed + outward * d
                if (f < 0 || f >= limit) break
                line(horizontal, f, from, to)
                val nonText = stat[0] - stat[1]
                if (nonText >= 0.4 * stat[0]) best = maxOf(best, 1.0 - stat[2].toDouble() / nonText)
            }
            return best
        }
        fun clean(horizontal: Boolean, fixed: Int, outward: Int, from: Int, to: Int) =
            scene(horizontal, fixed, outward, from, to) >= 0.65
        /** Inner border of a box = its padding: almost no letter pixels (a gap between letters is no box). */
        fun padding(horizontal: Boolean, fixed: Int, from: Int, to: Int): Boolean {
            var tx = 0
            for (k in from until to) if (mask.bits[if (horizontal) fixed * w + k else k * w + fixed]) tx++
            return tx <= 0.15 * (to - from)
        }

        // seeds: 8-connected pieces of text
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        class Cand(val b: Box, val clean: Int)
        val cands = ArrayList<Cand>()
        for (start in 0 until n) {
            if (!mask.bits[start] || seen[start]) continue
            var size = 0
            stack[size++] = start
            seen[start] = true
            var k = 0
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            while (k < size) {
                val i = stack[k++]
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (mask.bits[j] && !seen[j]) { seen[j] = true; stack[size++] = j }
                }
            }
            if (size < 3) continue
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            if (cands.any { it.b.contains(cx, cy) }) continue
            // grow a rectangle while the new row / column is box (dark padding or letters)
            var bx0 = x0; var bx1 = x1 + 1; var by0 = y0; var by1 = y1 + 1
            var grew = true
            var steps = 0
            // the seed is the box's own rim (bright scene pixels hugging a black box read as a thin
            // ring of "text"): the box is what the ring encloses
            val sw = x1 - x0 + 1
            val sh = y1 - y0 + 1
            var rim = false
            fun covered(horizontal: Boolean, fixed: Int, from: Int, to: Int): Boolean {
                var c = 0
                for (k in from..to) if (mask.bits[if (horizontal) fixed * w + k else k * w + fixed]) c++
                return c >= 0.7 * (to - from + 1)
            }
            if (sw >= minSize + 8 && sh >= maxOf(minSize + 6, minHeight) &&
                covered(true, y0, x0, x1) && covered(true, y1, x0, x1) && covered(false, x0, y0, y1) && covered(false, x1, y0, y1)
            ) {
                var nonText = 0
                var dk = 0
                for (y in y0 + 2..y1 - 2) for (x in x0 + 2..x1 - 2) {
                    val i = y * w + x
                    if (!text[i]) { nonText++; if (dark[i]) dk++ }
                }
                if (nonText > 0 && dk >= 0.6 * nonText) {
                    rim = true
                    bx0 = x0 + 2; bx1 = x1 - 1; by0 = y0 + 2; by1 = y1 - 1
                    grew = false
                }
            }
            while (grew && steps < w + h) {
                grew = false
                steps++
                if (by0 > 0 && inside(true, by0 - 1, bx0, bx1)) { by0--; grew = true }
                if (by1 < h && inside(true, by1, bx0, bx1)) { by1++; grew = true }
                if (bx0 > 0 && inside(false, bx0 - 1, by0, by1)) { bx0--; grew = true }
                if (bx1 < w && inside(false, bx1, by0, by1)) { bx1++; grew = true }
            }
            val bw = bx1 - bx0
            val bh = by1 - by0
            // a box has padding around its text: it grew beyond the seed on several sides
            // a side on the edge of the subtitle region = the region cuts the box there
            val atEdge = (if (by0 == 0) 1 else 0) + (if (by1 == h) 1 else 0) + (if (bx0 == 0) 1 else 0) + (if (bx1 == w) 1 else 0)
            val pad = if (rim) 4 else (if (bx0 < x0 || bx0 == 0) 1 else 0) + (if (bx1 > x1 + 1 || bx1 == w) 1 else 0) +
                (if (by0 < y0 || by0 == 0) 1 else 0) + (if (by1 > y1 + 1 || by1 == h) 1 else 0)
            if (bw < minSize + 4 || bh < maxOf(minSize + 2, minHeight) || pad < 3) { if (bw > 30 && bh > 20) debug?.invoke("t=$t ${Box(bx0, by0, bx1, by1)} small/pad=$pad seed=${Box(x0, y0, x1 + 1, y1 + 1)}"); continue }
            if ((by0 > 0 && !padding(true, by0, bx0, bx1)) || (by1 < h && !padding(true, by1 - 1, bx0, bx1)) ||
                (bx0 > 0 && !padding(false, bx0, by0, by1)) || (bx1 < w && !padding(false, bx1 - 1, by0, by1))
            ) { if (bw > 30) debug?.invoke("t=$t ${Box(bx0, by0, bx1, by1)} border has text"); continue }
            val sTop = scene(true, by0 - 1, -1, bx0, bx1)
            val sBottom = scene(true, by1, 1, bx0, bx1)
            val sLeft = scene(false, bx0 - 1, -1, by0, by1)
            val sRight = scene(false, bx1, 1, by0, by1)
            var cleanSides = listOf(sTop, sBottom, sLeft, sRight).count { it >= 0.65 }
            // two boxes stacked (one per line, different widths): top and bottom are clean edges,
            // the sides are clean only beside the narrower box
            if (cleanSides == 2 && sTop >= 0.65 && sBottom >= 0.65 && sLeft >= 0.3 && sRight >= 0.3) cleanSides = 3
            // one side cut by the region counts when every other side is a clean edge
            if (atEdge == 1 && cleanSides == 3) cleanSides = 4
            if (bw > 30) debug?.invoke("t=$t ${Box(bx0, by0, bx1, by1)} clean=$cleanSides")
            if (cleanSides >= 2) cands.add(Cand(Box(bx0, by0, bx1, by1), cleanSides))
        }
        // 3 clean sides = a box; 2 clean sides = a box stacked on (touching) another box
        val boxes = ArrayList<Box>()
        for (c in cands) if (c.clean >= 3 && boxes.none { it.x0 == c.b.x0 && it.y0 == c.b.y0 && it.x1 == c.b.x1 && it.y1 == c.b.y1 }) boxes.add(c.b)
        var added = true
        while (added) {
            added = false
            for (c in cands) if (c.clean == 2 && boxes.none { it === c.b } && boxes.any { it.touches(c.b) }) { boxes.add(c.b); added = true }
        }
        // a box is not a thin strip, and a smaller rectangle mostly inside a bigger box is part of it
        val shaped = boxes.filter { it.w >= 0.5 * it.h }.sortedByDescending { it.w * it.h }
        val distinct = ArrayList<Box>()
        for (b in shaped) {
            val inBigger = distinct.any { o ->
                val ix = minOf(b.x1, o.x1) - maxOf(b.x0, o.x0)
                val iy = minOf(b.y1, o.y1) - maxOf(b.y0, o.y0)
                ix > 0 && iy > 0 && ix * iy >= 0.5 * b.w * b.h
            }
            if (!inBigger) distinct.add(b)
        }
        // the box must hold real text, not a stray pixel
        return distinct.filter { b ->
            var tx = 0
            for (y in b.y0 until b.y1) for (x in b.x0 until b.x1) if (mask.bits[y * w + x]) tx++
            val ok = tx >= maxOf(6, b.w * b.h / 150)
            if (!ok) debug?.invoke("t=$t $b text=$tx rejected")
            ok
        }
    }

    /** The mask with every pixel outside the boxes cleared. */
    fun keepInside(m: TextMask, boxes: List<Box>): TextMask {
        if (boxes.isEmpty()) return TextMask(m.width, m.height, BooleanArray(m.width * m.height), 0)
        val bits = BooleanArray(m.width * m.height)
        var count = 0
        for (b in boxes) {
            for (y in maxOf(0, b.y0) until minOf(m.height, b.y1)) {
                val row = y * m.width
                for (x in maxOf(0, b.x0) until minOf(m.width, b.x1)) {
                    val i = row + x
                    if (m.bits[i] && !bits[i]) { bits[i] = true; count++ }
                }
            }
        }
        return TextMask(m.width, m.height, bits, count)
    }

    /** Is [b] still a box in this frame (its pixels dark or text), e.g. while it touches a dark jacket? */
    fun stillThere(g: LumaGrid, m: TextMask?, b: Box, threshold: Int): Boolean {
        if (threshold < 0) return false
        var ok = 0
        var all = 0
        for (y in maxOf(0, b.y0) until minOf(g.height, b.y1)) {
            val row = y * g.width
            for (x in maxOf(0, b.x0) until minOf(g.width, b.x1)) {
                all++
                if ((g.data[row + x].toInt() and 0xFF) <= threshold || (m != null && m.bits[row + x])) ok++
            }
        }
        return all > 0 && ok >= 0.8 * all
    }

    /** ARGB image with everything outside the boxes (scaled by [scale] = grid px per image px) painted black. */
    fun paintOutside(argb: IntArray, width: Int, height: Int, boxes: List<Box>, scale: Double, margin: Int = 2) {
        if (boxes.isEmpty()) return
        val keep = BooleanArray(width * height)
        for (b in boxes) {
            val x0 = ((b.x0 * scale).toInt() - margin).coerceIn(0, width)
            val x1 = ((b.x1 * scale).toInt() + margin).coerceIn(0, width)
            val y0 = ((b.y0 * scale).toInt() - margin).coerceIn(0, height)
            val y1 = ((b.y1 * scale).toInt() + margin).coerceIn(0, height)
            for (y in y0 until y1) for (x in x0 until x1) keep[y * width + x] = true
        }
        val blackPx = 0xFF000000.toInt()
        for (i in argb.indices) if (!keep[i]) argb[i] = blackPx
    }

    /**
     * Per-video state: the boxes of the last frame are kept for up to [holdFrames] frames while they
     * are still there (dark or text), so one frame where the box touches a black jacket and is not
     * found does not cut the subtitle.
     */
    class Gate(private val holdFrames: Int = 8) {
        private var held: List<Box> = emptyList()
        private var heldThreshold = -1
        private var age = 0
        var framesWithBox = 0
            private set

        fun boxes(g: LumaGrid, mask: TextMask, found: Found): List<Box> {
            if (found.boxes.isNotEmpty()) {
                held = found.boxes
                heldThreshold = found.threshold
                age = 0
                framesWithBox++
                return found.boxes
            }
            if (held.isNotEmpty() && age < holdFrames) {
                val still = held.filter { stillThere(g, mask, it, heldThreshold) }
                if (still.isNotEmpty()) {
                    age++
                    framesWithBox++
                    return still
                }
            }
            held = emptyList()
            return emptyList()
        }
    }
}
