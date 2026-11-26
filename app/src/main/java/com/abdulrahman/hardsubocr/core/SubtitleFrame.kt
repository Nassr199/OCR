package com.abdulrahman.hardsubocr.core

/**
 * The subtitle's own area in a picture for Gemini/OpenRouter (2.9.0).
 *
 * A vision model sees a picture through a fixed number of tokens: the whole picture is scaled to
 * that budget, so the share of it the letters take decides how many pixels each letter gets. A short
 * subtitle in a 1920-wide video band ends up a few pixels high, and the scene, the faces and any
 * watermark around it compete for the model's attention. v2.6 did this by a fixed band
 * (prepare_image_for_ocr: "focus on the subtitle band and enlarge it"); here the band is the
 * subtitle itself, found by its letters.
 *
 * Letters of a burned-in subtitle (white or yellow with a dark outline, a shadow or a box) make sharp
 * light/dark steps along a row — dozens per line; a soft scene makes few. Rows rich in steps are the
 * lines; the subtitle is the strongest block of lines, together with any line close above or below
 * it (a short line «- هوليا» over a two-line dialogue has far fewer steps than the long one). Its
 * width is where those rows have steps, as one stretch: a logo or a sign far to the side, on the same
 * rows, stays out. The margins are generous (a line height and more), and when the picture shows no
 * clear subtitle nothing is cut: the picture is used as it is.
 */
object SubtitleFrame {

    /** [left, top, right, bottom) in pixels, and the height of one line of the subtitle. */
    class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val lineHeight: Int) {
        val width get() = right - left
        val height get() = bottom - top
        override fun toString() = "[$left,$top → $right,$bottom] line $lineHeight"
    }

    private const val STEP = 64

    private fun lum(c: Int): Int = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** The subtitle's area with its margins, or null when the picture shows no clear subtitle (or it fills it already). */
    fun find(px: IntArray, w: Int, h: Int): Box? {
        if (w < 64 || h < 24) return null
        val lum = IntArray(w * h) { lum(px[it]) }
        val steps = IntArray(h)
        for (y in 0 until h) {
            val o = y * w
            var n = 0
            for (x in 1 until w) if (Math.abs(lum[o + x] - lum[o + x - 1]) >= STEP) n++
            steps[y] = n
        }
        val strongest = steps.max()
        // a line of subtitle text has dozens of steps; under 1 % of the width is no text
        if (strongest < maxOf(8, w / 100)) return null
        // a short line («- هوليا» over «- ابتعدي يا جوليزار») has a third of the long line's steps
        val textual = maxOf(4, strongest / 8)

        // lines: runs of textual rows (a gap of a couple of rows inside a line is a thin part of it)
        class Run(var top: Int, var bottom: Int, var weight: Int)
        val runs = ArrayList<Run>()
        var y = 0
        while (y < h) {
            if (steps[y] < textual) { y++; continue }
            val r = Run(y, y + 1, steps[y])
            var k = y + 1
            var gap = 0
            while (k < h && gap <= 2) {
                if (steps[k] >= textual) { r.bottom = k + 1; r.weight += steps[k]; gap = 0 } else gap++
                k++
            }
            runs.add(r)
            y = r.bottom
        }
        if (runs.isEmpty()) return null
        // a run thinner than a few rows is a scene edge, not a line of letters
        val lines = runs.filter { it.bottom - it.top >= maxOf(4, h / 60) }.ifEmpty { return null }
        val lineH = lines.maxOf { it.bottom - it.top }

        // the subtitle: the heaviest group of lines that are less than a line apart
        class Block(val lines: MutableList<Run>) {
            val top get() = lines.first().top
            val bottom get() = lines.last().bottom
            val weight get() = lines.sumOf { it.weight }
        }
        val blocks = ArrayList<Block>()
        for (r in lines) {
            val last = blocks.lastOrNull()
            if (last != null && r.top - last.bottom <= lineH) last.lines.add(r) else blocks.add(Block(mutableListOf(r)))
        }
        val main = blocks.maxByOrNull { it.weight } ?: return null
        var top = main.top
        var bottom = main.bottom
        // a short line next to it (a name, «- لا») is part of the subtitle when it is close
        for (b in blocks) {
            if (b === main) continue
            val near = (b.bottom <= top && top - b.bottom <= lineH * 3 / 2) || (b.top >= bottom && b.top - bottom <= lineH * 3 / 2)
            if (near && b.weight * 8 >= main.weight / main.lines.size) {
                top = minOf(top, b.top); bottom = maxOf(bottom, b.bottom)
            }
        }
        // another block as strong, out of reach: which one is the subtitle is not clear — nothing is cut
        for (b in blocks) if (b.top < top || b.bottom > bottom) {
            if (b.bottom <= top || b.top >= bottom) if (b.weight * 3 >= main.weight) return null
        }
        // tops and tails of the letters (alef, lam, dots, tashkeel, descenders) have fewer steps
        val low = maxOf(3, strongest / 40)
        var gap = 0
        var r = top - 1
        while (r >= 0 && gap <= 5) { if (steps[r] >= low) { top = r; gap = 0 } else gap++; r-- }
        gap = 0
        r = bottom
        while (r < h && gap <= 5) { if (steps[r] >= low) { bottom = r + 1; gap = 0 } else gap++; r++ }

        // its width: the columns of those rows with steps, as one stretch around the heaviest part
        val col = IntArray(w)
        for (yy in top until bottom) {
            val o = yy * w
            for (x in 1 until w) if (Math.abs(lum[o + x] - lum[o + x - 1]) >= STEP) { col[x]++; col[x - 1]++ }
        }
        val join = lineH * 3 / 2
        class Span(val left: Int, var right: Int, var weight: Int)
        val spans = ArrayList<Span>()
        var lastX = -1_000_000
        for (x in 0 until w) {
            if (col[x] == 0) continue
            val s = spans.lastOrNull()
            if (s != null && x - lastX <= join) { s.right = x + 1; s.weight += col[x] } else spans.add(Span(x, x + 1, col[x]))
            lastX = x
        }
        val heavy = spans.maxByOrNull { it.weight } ?: return null
        var left = heavy.left
        var right = heavy.right
        // a dash or a question mark a little apart from the words still belongs to the line
        var changed = true
        while (changed) {
            changed = false
            for (s in spans) {
                if (s.right <= left && left - s.right <= lineH * 3 && s.weight * 50 >= heavy.weight) { left = s.left; changed = true }
                if (s.left >= right && s.left - right <= lineH * 3 && s.weight * 50 >= heavy.weight) { right = s.right; changed = true }
            }
        }

        // a stretch as strong as the subtitle far to its side: keep the whole width
        if (spans.any { (it.right <= left || it.left >= right) && it.weight * 3 >= heavy.weight }) {
            left = 0; right = w
        }

        // generous margins: a line height (and an eighth of the picture) above and below, a line and a
        // half (and 4 % of the width) at the sides
        val vm = maxOf(lineH, h / 8, 12)
        val hm = maxOf(lineH * 3 / 2, w / 25)
        val box = Box(
            maxOf(0, left - hm), maxOf(0, top - vm),
            minOf(w, right + hm), minOf(h, bottom + vm),
            lineH
        )
        // nothing worth cutting: the subtitle fills the picture already
        if (box.width.toLong() * box.height >= w.toLong() * h * 85 / 100) return null
        return box
    }
}
