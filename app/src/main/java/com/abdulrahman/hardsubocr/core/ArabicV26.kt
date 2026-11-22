package com.abdulrahman.hardsubocr.core

/**
 * Port of the diacritics guard of ocr_windows_v2.6.py (regex-free, same results as Python):
 *  - fix_fragmented_arabic_words   : "سِ لِ م تِ" -> "سِلِمتِ"
 *  - sanitize_word_diacritics       : strips harakat from words that are obviously over-marked
 *  - conservative_clean_diacritics  : both of the above + tatweel removal
 *  - stabilize_consecutive_diacritics: consecutive frames of the same subtitle keep only the
 *                                     harakat that ALL frames agree on (kills random hallucinated marks)
 */
object ArabicV26 {
    private const val TATWEEL = 'ـ'

    /** [ً-ْٰ] */
    fun isMark(c: Char): Boolean = c in 'ً'..'ْ' || c == 'ٰ'

    /** [ء-يٱ] */
    private fun isBaseLetter(c: Char): Boolean = c in 'ء'..'ي' || c == 'ٱ'

    private fun isArabicTokenChar(c: Char): Boolean =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c == TATWEEL

    fun stripDiacritics(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) if (!isMark(c)) sb.append(c)
        return sb.toString()
    }

    fun stripDiacriticsAndTatweel(text: String): String = stripDiacritics(text).replace(TATWEEL.toString(), "")

    /** '\n'.join(' '.join(line.split()) for line in text.split('\n')) */
    fun normalizeSpaces(text: String): String =
        text.split('\n').joinToString("\n") { ArabicText.pySplitWs(it).joinToString(" ") }

    fun normalizeForTemporalMatch(text: String): String = normalizeSpaces(stripDiacriticsAndTatweel(text))

    fun countBaseLetters(text: String): Int = stripDiacriticsAndTatweel(text).count { isBaseLetter(it) }

    fun countDiacritics(text: String): Int = text.count { isMark(it) }

    /** ARABIC_WORD_TOKEN_PATTERN.match(token) */
    private fun isArabicToken(t: String): Boolean = t.isNotEmpty() && t.all { isArabicTokenChar(it) }

    fun fixFragmentedArabicWords(text: String): String {
        val fixed = ArrayList<String>()
        for (line in text.split('\n')) {
            val tokens = ArabicText.pySplitWs(line)
            if (tokens.isEmpty()) {
                fixed.add(line); continue
            }
            val rebuilt = ArrayList<String>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (isArabicToken(token)) {
                    var j = i
                    val run = ArrayList<String>()
                    while (j < tokens.size && isArabicToken(tokens[j]) && countBaseLetters(tokens[j]) == 1) {
                        run.add(tokens[j]); j++
                    }
                    if (run.size >= 3) {
                        rebuilt.add(run.joinToString("")); i = j; continue
                    }
                }
                rebuilt.add(token)
                i++
            }
            fixed.add(rebuilt.joinToString(" "))
        }
        return fixed.joinToString("\n")
    }

    fun sanitizeWordDiacritics(word0: String): String {
        if (word0.isEmpty()) return word0
        val word = word0.replace(TATWEEL.toString(), "")
        val letters = countBaseLetters(word)
        if (letters == 0) return word
        val diacritics = countDiacritics(word)
        if (diacritics == 0) return word
        if (diacritics > letters) return stripDiacritics(word)
        if (diacritics.toDouble() / maxOf(letters, 1) > 0.60) return stripDiacritics(word)
        if (letters <= 4 && diacritics >= 3) return stripDiacritics(word)
        return word
    }

    fun conservativeCleanDiacritics(text: String): String {
        val t = fixFragmentedArabicWords(text).replace(TATWEEL.toString(), "")
        return t.split('\n').joinToString("\n") { line ->
            ArabicText.pySplitWs(line).joinToString(" ") { sanitizeWordDiacritics(it) }
        }
    }

    private class Part(val ch: Char, var marks: String)

    private fun decompose(text: String): List<Part> {
        val parts = ArrayList<Part>()
        for (ch in text) {
            if (isMark(ch)) {
                if (parts.isNotEmpty()) parts[parts.size - 1].marks += ch
                continue
            }
            if (ch == TATWEEL) continue
            parts.add(Part(ch, ""))
        }
        return parts
    }

    private fun leastMarked(texts: List<String>): String {
        var best = texts[0]
        var bestN = countDiacritics(best)
        for (t in texts) {
            val n = countDiacritics(t)
            if (n < bestN) {
                best = t; bestN = n
            }
        }
        return best
    }

    fun buildGroupConsensusText(texts: List<String>): String {
        if (texts.isEmpty()) return ""
        val dec = texts.map { decompose(it) }
        if (dec.map { it.size }.toSet().size != 1) return leastMarked(texts)
        val bases = dec.map { d -> d.joinToString("") { it.ch.toString() } }
        if (bases.toSet().size != 1) return leastMarked(texts)
        val sb = StringBuilder()
        for (idx in dec[0].indices) {
            sb.append(dec[0][idx].ch)
            val marks = dec.map { it[idx].marks }
            if (marks.all { it.isNotEmpty() } && marks.toSet().size == 1) sb.append(marks[0])
        }
        return sb.toString()
    }

    /** stabilize_consecutive_diacritics(entries, max_gap=0.05): entries must be sorted by start. */
    fun stabilizeConsecutiveDiacritics(entries: List<SubEntry>, maxGapMs: Long = 50): List<SubEntry> {
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<SubEntry>(entries.size)
        var i = 0
        while (i < entries.size) {
            val current = entries[i]
            val group = arrayListOf(current)
            val key = normalizeForTemporalMatch(current.text)
            var lastEnd = current.endMs
            var j = i + 1
            while (j < entries.size) {
                val cand = entries[j]
                if (cand.startMs - lastEnd > maxGapMs || normalizeForTemporalMatch(cand.text) != key) break
                group.add(cand)
                lastEnd = cand.endMs
                j++
            }
            if (group.size == 1) {
                out.add(group[0])
            } else {
                val consensus = conservativeCleanDiacritics(buildGroupConsensusText(group.map { it.text }))
                for (g in group) out.add(SubEntry(g.startMs, g.endMs, consensus))
            }
            i = j
        }
        return out
    }
}
