package com.abdulrahman.hardsubocr.core

/**
 * Cleaning for English (Latin) subtitles. Regex-free on purpose (Android ICU regex differs from
 * desktop Java — the cause of the 1.0 crash).
 *
 * Keeps: line breaks between subtitle lines, punctuation, apostrophes, dialogue dashes ("- Hi"),
 * music notes. Removes: code fences, "no text" answers, labels like "Text:", symbol-only lines,
 * surrounding quotes, duplicated consecutive lines. Fixes the classic "|" → "I" OCR confusion.
 */
object EnglishText {

    private val NO_TEXT = listOf(
        "no_text", "no text", "no subtitle", "no subtitles", "there is no text", "no visible text",
        "no readable text", "image does not contain", "[empty]", "(empty)"
    )
    private val LABELS = listOf("text:", "subtitle:", "subtitles:", "ocr:", "output:", "transcription:")

    fun clean(raw: String?): String {
        var t = (raw ?: "").replace("\r\n", "\n").replace('\r', '\n')
        t = stripFences(t)
        val low = t.trim().lowercase()
        if (low.isEmpty()) return ""
        if (low.length <= 60 && NO_TEXT.any { low.contains(it) }) return ""
        val out = ArrayList<String>()
        for (line0 in t.split('\n')) {
            var line = collapse(line0)
            val ll = line.lowercase()
            for (lb in LABELS) if (ll.startsWith(lb)) {
                line = line.substring(lb.length).trim(); break
            }
            line = unquote(line)
            line = fixPipes(line)
            if (line.isEmpty() || !line.any { it.isLetterOrDigit() }) continue
            if (out.isNotEmpty() && out[out.size - 1] == line) continue
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun stripFences(s: String): String {
        if (!s.contains("```")) return s
        val sb = StringBuilder()
        for (line in s.split('\n')) if (!line.trim().startsWith("```")) sb.append(line).append('\n')
        return sb.toString()
    }

    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        var ws = false
        for (c in s) {
            if (c.isWhitespace()) {
                if (!ws && sb.isNotEmpty()) sb.append(' '); ws = true
            } else {
                sb.append(c); ws = false
            }
        }
        return sb.toString().trim()
    }

    private fun unquote(s: String): String {
        if (s.length >= 2) {
            val a = s[0]
            val b = s[s.length - 1]
            if ((a == '"' && b == '"') || (a == '“' && b == '”') || (a == '\'' && b == '\'' && s.count { it == '\'' } == 2)) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    /** "| don't know" / "Then |'ll go" → I. Only a pipe standing where a word starts. */
    private fun fixPipes(s: String): String {
        if (!s.contains('|')) return s
        val sb = StringBuilder(s)
        for (i in sb.indices) {
            if (sb[i] != '|') continue
            val before = if (i == 0) ' ' else sb[i - 1]
            val after = if (i + 1 < sb.length) sb[i + 1] else ' '
            if ((before == ' ' || before == '-' || before == '"') && (after == ' ' || after == '\'' || after == '’' || after == ',' || after == '.')) sb.setCharAt(i, 'I')
        }
        return sb.toString()
    }
}
