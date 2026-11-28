package com.abdulrahman.hardsubocr.core

/**
 * The dialogue dashes of a subtitle read more than once (Lens's extra looks, Vision's focused look).
 *
 * Seen on a real frame «- هوليا ⏎ - ابتعدي يا جوليزار»: the dash of the second, wider line is the
 * outermost mark of the whole subtitle — the first thing a tight crop cuts and a small look misses —
 * so the reading that was kept came back «- هوليا ⏎ ابتعدي يا جوليزار». The letters of all the looks
 * agreed; only the dash was in one of them and not in the other.
 *
 * Nothing is guessed: a line gets a dash only when another look read THE SAME LINE (same Arabic
 * letters) with a dash in front of it. A line no look read with a dash stays as it is — one speaker's
 * sentence wrapped on two lines has a dash on its first line only, and keeps it that way.
 */
object ClassicShape {

    private const val DASHES = "-–—"

    /** The line starts with a dialogue dash. */
    fun startsWithDash(line: String): Boolean = line.trimStart().firstOrNull()?.let { it in DASHES } == true

    /** A dialogue dash written against its word («-هوليا») gets its space, as on screen («- هوليا»). */
    fun spaceAfterDash(line: String): String {
        val t = line.trim()
        return if (t.length >= 2 && t[0] in DASHES && t[1] != ' ' && t[1] !in DASHES) t[0] + " " + t.substring(1) else line
    }

    private fun endsWithDash(line: String): Boolean = line.trimEnd().lastOrNull()?.let { it in DASHES } == true

    /** The subtitle's own lines: 2+ Arabic letters (crew names and lone marks are removed later anyway). */
    private fun subtitleLines(text: String): List<String> = text.split('\n').filter { ClassicViews.arabicLetters(it) >= 2 }

    /**
     * A dialogue whose dash may have been missed: two or more subtitle lines, some starting with a
     * dash and some not. Worth another look; whether a dash is added is decided only by what the
     * other looks read ([restoreDashes]).
     */
    fun dashMissing(text: String): Boolean {
        val ls = subtitleLines(text)
        return ls.size >= 2 && ls.any { startsWithDash(it) } && !ls.all { startsWithDash(it) }
    }

    /** Identity of a line for matching across looks: its Arabic letters and word breaks. */
    fun lineKey(line: String): String = ClassicViews.key(line)

    /**
     * [chosen] with, in front of each of its lines that has no dash, the dash another reading in
     * [others] has in front of the same line. [allowed] (optional) limits it to lines whose dash was
     * checked where it stands on the picture (Vision: [VisionLayout.dashedLines]).
     */
    fun restoreDashes(chosen: String, others: List<String>, allowed: Set<String>? = null): String {
        val dashed = HashMap<String, Char>()
        for (o in others) for (l in o.split('\n')) {
            val t = l.trim()
            if (!startsWithDash(t)) continue
            val k = lineKey(t)
            if (ClassicViews.arabicLetters(k) < 2) continue
            if (allowed != null && k !in allowed) continue
            dashed.putIfAbsent(k, t.first())
        }
        if (dashed.isEmpty()) return chosen
        var changed = false
        val out = chosen.split('\n').map { l ->
            val t = l.trim()
            // a line that already has a dash on either end is left alone (never two dashes)
            if (t.isEmpty() || startsWithDash(t) || endsWithDash(t)) return@map l
            val d = dashed[lineKey(t)] ?: return@map l
            changed = true
            "$d $t"
        }
        return if (changed) out.joinToString("\n") else chosen
    }
}
