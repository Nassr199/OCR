package com.abdulrahman.hardsubocr.core

/**
 * A second reading by Google Lens for the AI engines (Gemini, OpenRouter). Lens is free and a classic
 * OCR: it finds each word on the picture, so it does not miscount a word the subtitle repeats, and it
 * has no filter that stops an answer half-way. A language model does both: «بخير بخير أجل أنا بخير»
 * came back as «بخير بخير بخير أجل أنا بخير» on four frames in a row (models fail at counting repeated
 * words, the same way every time), and «أريد أمي» as «أر» (an answer stopped before its end).
 *
 * - The AI text repeats a word or a phrase side by side → Lens reads the original picture. When both
 *   readings have the same words in the same order and differ only in HOW MANY TIMES a word or phrase
 *   repeats, Lens's count is taken; the AI's own spelling (hamza, diacritics, punctuation, lines) stays.
 *   Any other difference leaves the AI text as it is: Lens never rewrites the AI's words.
 * - The AI answer was cut twice ([OcrError.Cut]) → Lens's reading is the subtitle, said in the log.
 *
 * Lens is asked only in these two cases, so a job costs the same and is as fast as before.
 */
object AiCheck {
    /** The letters of a word for comparing two readings: no marks, one form of alef, ya, ta marbuta. */
    fun key(word: String): String {
        val sb = StringBuilder(word.length)
        for (c in word) {
            if (!c.isLetterOrDigit() || c == 'ـ') continue
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى', 'ئ' -> 'ي'
                    'ة' -> 'ه'
                    'ؤ' -> 'و'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** One word of a reading with the marks around it («- », «؟») and its line. */
    class Unit(val text: String, val key: String, val line: Int)

    /** The words of [text]; a token without letters (a dash, «…») joins the next word of its line, or the previous one at the end. */
    fun units(text: String): List<Unit> {
        val out = ArrayList<Unit>()
        for ((li, line) in text.split('\n').withIndex()) {
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var pending = ""
            for (t in tokens) {
                val k = key(t)
                if (k.isEmpty()) {
                    val last = out.lastOrNull()
                    if (pending.isEmpty() && last != null && last.line == li && t == tokens.last()) {
                        out[out.size - 1] = Unit(last.text + " " + t, last.key, li)
                    } else pending = if (pending.isEmpty()) t else "$pending $t"
                    continue
                }
                out.add(Unit(if (pending.isEmpty()) t else "$pending $t", k, li))
                pending = ""
            }
            if (pending.isNotEmpty()) {
                val last = out.lastOrNull()
                if (last != null && last.line == li) out[out.size - 1] = Unit(last.text + " " + pending, last.key, li)
            }
        }
        return out
    }

    /** A word or a phrase of up to 3 words written twice side by side («بخير بخير», «هل تسمعينني؟ هل تسمعينني؟»). */
    fun hasRepeat(keys: List<String>): Boolean {
        for (n in 1..3) for (i in 0..keys.size - 2 * n) {
            if ((0 until n).all { keys[i + it] == keys[i + n + it] }) return true
        }
        return false
    }

    /** The AI text has a repeat worth checking with Lens. */
    fun needsLens(text: String): Boolean = hasRepeat(units(text).map { it.key })

    /** The words with every side-by-side repeat of a word or a phrase (up to 3 words) folded into one. */
    fun skeleton(keys: List<String>): List<String> {
        val cur = keys.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (n in 1..3) for (i in 0..cur.size - 2 * n) {
                if ((0 until n).all { cur[i + it] == cur[i + n + it] }) {
                    repeat(n) { cur.removeAt(i + n) }
                    changed = true
                    break@loop
                }
            }
        }
        return cur
    }

    /**
     * The AI text with Lens's count of repeats, or null when there is nothing to change (the same
     * words, or readings that differ in more than repeats).
     */
    fun withLensRepeats(ai: String, lens: String): String? {
        val a = units(ai)
        val l = units(lens).map { it.key }
        val ak = a.map { it.key }
        if (a.isEmpty() || l.isEmpty() || ak == l) return null
        if (skeleton(ak) != skeleton(l)) return null
        // align the two by their words (LCS): Lens's extra copies take the AI word before them,
        // the AI's extra copies go
        val n = ak.size; val m = l.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (ak[i] == l[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Unit>()
        var i = 0; var j = 0
        while (j < m) {
            if (i < n && ak[i] == l[j] && dp[i][j] == dp[i + 1][j + 1] + 1) { out.add(a[i]); i++; j++ }
            else if (i < n && dp[i + 1][j] >= dp[i][j + 1]) i++            // an extra copy in the AI text
            else {                                                         // a copy only Lens has
                val src = out.lastOrNull { it.key == l[j] } ?: a.firstOrNull { it.key == l[j] } ?: return null
                out.add(src); j++
            }
        }
        val lines = out.groupBy { it.line }.toSortedMap().values.map { us -> us.joinToString(" ") { it.text } }
        return lines.joinToString("\n").takeIf { it.isNotBlank() && it != ai }
    }
}

/** Google Lens as the AI engines' second reader ([AiCheck]); its text is cleaned like a Lens job's. */
class LensCheck(private val lens: OcrEngine, private val stripDiacritics: Boolean) {
    /** Lens's cleaned reading of the original picture, or null when Lens could not read it. */
    suspend fun read(image: PreparedImage): String? = try {
        Cleaner.clean(lens.recognize(image), CleanMode.CLASSIC, stripDiacritics)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        DiagLog.w("ai", "${image.name}: Google Lens check failed: ${e.message}")
        null
    }
}
