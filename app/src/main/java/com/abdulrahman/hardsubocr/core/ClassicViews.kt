package com.abdulrahman.hardsubocr.core

/**
 * Multi-view reading for classic OCR (Google Lens), Arabic first.
 *
 * Measured live on subtitle crops shaped like the real ones (bench/, GitHub Actions): Lens reads full
 * sentences perfectly, but a SHORT subtitle in a small box over crew credits is often missed or
 * clipped — «أبي» read as nothing or «بي», «أمي» as «ي», «حسنًا» as nothing — and the credit
 * letters hidden by the box come back as phantom letters («- لماذا؟ و»). One look at the picture
 * cannot fix that; a few looks can: the same picture enlarged, the subtitle's surroundings alone,
 * its band across the width. Each look fails on different pictures, so the reading most looks agree
 * on is kept (a reading that only adds the letters the others lost at a word's edge wins over them).
 *
 * Only pictures whose first reading is suspicious get the extra looks; every look is the original
 * pixels (cropped and/or scaled), never altered.
 */
object ClassicViews {

    /** A rectangle of the picture (its own pixels) read at [scale]. */
    class View(val name: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val scale: Float)

    fun isArabicLetter(c: Char): Boolean {
        val x = c.code
        if (x == 0x0640) return false // tatweel
        return (x in 0x0621..0x064A) || x == 0x0671 || (x in 0x066E..0x06D3) || (x in 0x06FA..0x06FF) ||
            (x in 0x0750..0x077F) || ((x in 0xFB50..0xFDFF || x in 0xFE70..0xFEFC) && c.isLetter())
    }

    fun arabicLetters(s: String): Int = s.count { isArabicLetter(it) }

    /**
     * First reading worth a second look: no Arabic at all, a short subtitle (≤ 4 letters — the kind
     * Lens drops or clips), or background fragments removed next to it.
     */
    fun suspicious(text: String, strays: Int): Boolean {
        val n = arabicLetters(text)
        return n <= 4 || strays > 0
    }

    /** The extra looks for a [w]×[h] picture, given where the first reading found Arabic. */
    fun plan(w: Int, h: Int, arabic: List<IntArray>, maxDimension: Int = 2600): List<View> {
        val views = ArrayList<View>(3)
        if (maxOf(w, h) * 2 <= maxDimension) views.add(View("×2", 0, 0, w, h, 2f))
        if (arabic.isNotEmpty()) {
            val x0 = arabic.minOf { it[0] }; val y0 = arabic.minOf { it[1] }
            val x1 = arabic.maxOf { it[2] }; val y1 = arabic.maxOf { it[3] }
            val th = (y1 - y0).coerceAtLeast(8)
            // the subtitle with twice its height on each side, full height: credits beside it mostly cut
            val ax0 = (x0 - 2 * th).coerceAtLeast(0); val ax1 = (x1 + 2 * th).coerceAtMost(w)
            if (ax1 - ax0 < w * 9 / 10) views.add(View("حول الترجمة", ax0, 0, ax1, h, 1f))
            // its rows across the whole width: the lines above and below (other credits) cut
            val by0 = (y0 - th / 4).coerceAtLeast(0); val by1 = (y1 + th / 4).coerceAtMost(h)
            if (by1 - by0 < h * 9 / 10) views.add(View("شريط الترجمة", 0, by0, w, by1, 1f))
        } else {
            // nothing Arabic found: subtitles are centred
            views.add(View("الوسط", w / 4, 0, w - w / 4, h, 1f))
        }
        return views
    }

    /** What the vote compares: Arabic letters and the spaces between words (no tashkeel, punctuation). */
    fun key(text: String): String {
        val sb = StringBuilder(text.length)
        var space = false
        for (c in Cleaner.clean(text, CleanMode.PRO)) {
            if (isArabicLetter(c)) {
                if (space && sb.isNotEmpty()) sb.append(' ')
                sb.append(c); space = false
            } else if (c.isWhitespace()) space = true
        }
        return sb.toString()
    }

    /**
     * [longer] is [shorter] with 1–2 letters more glued to its first or last word (a letter the other
     * looks lost at the edge: «أبي» / «بي», «حبيبي …» / «بيبي …»), not a separate word.
     */
    fun extends(longer: String, shorter: String): Boolean {
        if (shorter.isEmpty()) return false
        val d = longer.length - shorter.length
        if (d !in 1..2) return false
        if (longer.endsWith(shorter)) {
            val added = longer.substring(0, d)
            return ' ' !in added && longer[d - 1] != ' '
        }
        if (longer.startsWith(shorter)) {
            val added = longer.substring(shorter.length)
            return ' ' !in added
        }
        return false
    }

    /**
     * Index of the reading to keep, or -1 when no reading has a real Arabic text (2+ letters — no
     * subtitle is a single letter). The most frequent reading wins (ties: the earlier look, the whole
     * picture first); a reading that [extends] the winner — the letters the others lost at a word's
     * edge — wins over it.
     */
    fun vote(readings: List<String>): Int {
        val keys = readings.map { k -> key(k).let { if (arabicLetters(it) < 2) "" else it } }
        val nonEmpty = keys.indices.filter { keys[it].isNotEmpty() }
        if (nonEmpty.isEmpty()) return -1
        val groups = nonEmpty.groupBy { keys[it] }
        val top = groups.values.maxOf { it.size }
        val winner = groups.entries.filter { it.value.size == top }.minBy { it.value.first() }
        val longer = groups.entries.filter { extends(it.key, winner.key) }
            .sortedWith(compareByDescending<Map.Entry<String, List<Int>>> { it.value.size }.thenBy { it.value.first() })
            .firstOrNull()
        return (longer ?: winner).value.first()
    }
}
