package com.abdulrahman.hardsubocr.core

import java.text.Normalizer

/**
 * Cleaning for Google Lens and Google Vision that keeps the subtitle's shape: every line as it is on
 * the screen, dialogue dashes, punctuation, quotes, numbers and the tashkeel the picture shows.
 * Only what is not the subtitle goes: Latin letters (crew names read on the same row), lines with no
 * Arabic letter, tatweel, and spaces OCR puts before closing punctuation («ماذا حدث ؟»).
 *
 * Unlike the AI cleanings there is no phrase or reply handling: an OCR result never contains a
 * model's answer, so a subtitle that says «لا يوجد نص» stays.
 */
object ClassicText {

    private const val PUNCT = ".,!?:;؛،؟\"'()%[]«»-–—/#…"
    private const val CLOSING = ".,!?:;؛،؟…»)]"

    private fun keep(c: Char): Boolean {
        val x = c.code
        return (x in 0x0600..0x06FF && x != 0x0640) || x in 0x0750..0x077F || x in 0x08A0..0x08FF ||
            c in '0'..'9' || c == ' ' || PUNCT.indexOf(c) >= 0
    }

    private fun isPresentation(c: Char): Boolean = c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF

    /** Arabic presentation forms (lam-alef ligature, positional letters) from OCR become the ordinary letters. */
    private fun ordinary(s: String): String {
        if (s.none { isPresentation(it) }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (isPresentation(c)) sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC))
            else sb.append(c)
        }
        return sb.toString()
    }

    fun cleanLine(line: String): String {
        val src = ordinary(line).replace('\t', ' ').replace('\u00A0', ' ')
        val sb = StringBuilder(src.length)
        for (c in src) if (keep(c)) sb.append(c) else if (c.isWhitespace()) sb.append(' ')
        // one space between words, none before closing punctuation
        val out = StringBuilder(sb.length)
        for (c in sb) {
            if (c == ' ') {
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            } else {
                if (CLOSING.indexOf(c) >= 0 && out.isNotEmpty() && out.last() == ' ' && out.length >= 2 && out[out.length - 2] != '-') {
                    out.setLength(out.length - 1)
                }
                out.append(c)
            }
        }
        var t = out.toString().trim()
        // a dialogue dash written against the word («-هوليا») gets its space, as on screen
        if (t.length >= 2 && (t[0] == '-' || t[0] == '–' || t[0] == '—') && t[1] != ' ' && t[1] != t[0]) {
            t = t[0] + " " + t.substring(1)
        }
        return t
    }

    /**
     * The subtitle as on screen. Lines keep their order and breaks; a line with no Arabic letter is
     * dropped (Latin credits, a lone mark). [stripDiacritics] = the user's «remove all tashkeel».
     */
    fun clean(raw: String, stripDiacritics: Boolean = false): String {
        val lines = ArrayList<String>()
        for (l in raw.split('\n')) {
            var t = cleanLine(l)
            if (stripDiacritics) t = ArabicText.pyStrip(ArabicText.stripDiacritics(t)).let { cleanLine(it) }
            if (t.isEmpty() || ClassicViews.arabicLetters(t) == 0) continue
            lines.add(t)
        }
        val text = lines.joinToString("\n")
        return if (text.isEmpty() || ArabicText.isSymbolsOnly(text)) "" else text
    }
}
