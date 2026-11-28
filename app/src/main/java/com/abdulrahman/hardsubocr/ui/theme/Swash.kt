package com.abdulrahman.hardsubocr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Thmanyah's swash letters («الأحرف المرسلة»): the long final ت of «ملخصات» in Thmanyah's own app
 * comes from the font's `swsh` feature, not from another font file. Thmanyah's guide sets two rules:
 * not many in one sentence, and never on two neighbouring words. So a title gets one swash at most:
 * the first word whose last letter has a swash form.
 *
 * Which forms have one is read from Thmanyah Sans 1.003 (the same in all five weights): final and
 * lone ئ ب ت ث س ش ص ض ف ق ك ن ى ي (and پ ں ٮ ٯ ڡ), and م only when it stands alone (رسوم, not
 * علم). A ك inside the word has its own swash form, so such words are skipped: one swash per word.
 */
object Swash {
    /** The font feature that draws the swash forms. */
    const val FEATURE = "swsh"

    private const val FINAL = "ئبتثسشصضفقكنىيپںٮٯڡ"
    private const val ALONE = FINAL + "م"
    /** Letters that never join the letter after them: the next letter takes its lone form. */
    private const val NO_LEFT_JOIN = "ءآأؤإاةدذرزوٱژڈڑۀۆۇۈۋےۓ"
    private const val TATWEEL = '\u0640'

    private fun isMark(c: Char) = c in '\u064B'..'\u065F' || c == '\u0670'
    private fun isArabicLetter(c: Char) =
        c in '\u0621'..'\u063A' || c in '\u0641'..'\u064A' || c in '\u066E'..'\u066F' || c in '\u0671'..'\u06D3'

    /** The range (start until end) of the word to draw with swash, or null when no word may take one. */
    fun word(text: String): IntRange? {
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            if (i > start && eligible(text, start, i)) return start until i
        }
        return null
    }

    private fun eligible(text: String, start: Int, end: Int): Boolean {
        // the last letter, past any closing marks and punctuation; a tatweel after it means it isn't final
        var j = end - 1
        while (j >= start && !isArabicLetter(text[j])) {
            if (text[j] == TATWEEL || text[j].isLetterOrDigit()) return false
            j--
        }
        if (j < start) return false
        val last = text[j]
        // the letter before it decides between the final and the lone form
        var p = j - 1
        while (p >= start && isMark(text[p])) p--
        val prev = if (p >= start) text[p] else null
        val joined = prev != null && (isArabicLetter(prev) || prev == TATWEEL) && prev !in NO_LEFT_JOIN
        if (!(if (joined) last in FINAL else last in ALONE)) return false
        // a ك before the end would take a second swash in the same word
        for (k in start until j) if (text[k] == 'ك') return false
        return true
    }
}

/**
 * [text] with Thmanyah's swash on one word, for titles. Plain text when the Thmanyah font isn't loaded
 * (the system font has no swash forms) or when no word may take one.
 */
@Composable
fun swashTitle(text: String): AnnotatedString {
    val thmanyah = MaterialTheme.typography.titleMedium.fontFamily != null
    return remember(text, thmanyah) {
        val range = if (thmanyah) Swash.word(text) else null
        if (range == null) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(fontFeatureSettings = Swash.FEATURE), range.first, range.last + 1)
        }
    }
}
