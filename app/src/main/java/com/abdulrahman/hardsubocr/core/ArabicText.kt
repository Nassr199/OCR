package com.abdulrahman.hardsubocr.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Faithful ports of the text logic of the Windows programs:
 *  - PRO GUI  (HardSubArabicOCR_v2_PRO.py): norm_text, _clean_suspicious_numbers, arabic_filter, similarity
 *  - CLI v2.1 (ocr_windows_v2.1.py):        clean_ocr_output, NO_TEXT_PHRASES, symbol-only check,
 *                                            _calculate_text_similarity
 *
 * IMPORTANT: written WITHOUT regular expressions on purpose. Android's java.util.regex is
 * ICU-based and differs from desktop Java (it rejects (?U), treats \w \s \d differently, …),
 * which crashed v1.0 with ExceptionInInitializerError. Plain character loops behave the same
 * on every runtime and reproduce Python's `re` semantics exactly (verified on 11k+ cases).
 */
object ArabicText {

    // ------------------------------------------------------------- Python character classes

    /** Python str.isspace() / `\s` for str patterns. */
    fun pyIsSpace(c: Char): Boolean =
        Character.isSpaceChar(c) || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' ||
            c == '\r' || c == '\u001C' || c == '\u001D' || c == '\u001E' || c == '\u001F' || c == '\u0085'

    private fun isNumberType(t: Int): Boolean =
        t == Character.DECIMAL_DIGIT_NUMBER.toInt() || t == Character.LETTER_NUMBER.toInt() ||
            t == Character.OTHER_NUMBER.toInt()

    /** Python `\w` for str patterns: letters, numbers, underscore (NOT combining marks). */
    fun pyIsWord(c: Char): Boolean = c == '_' || Character.isLetter(c) || isNumberType(Character.getType(c))

    /** Python `\d`: Unicode decimal digits. */
    fun pyIsDigit(c: Char): Boolean = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    private fun isTashkeelPro(c: Char): Boolean = c in 'ً'..'ٟ' || c == 'ٰ'

    /** Python str.strip() with no argument. */
    fun pyStrip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && pyIsSpace(s[a])) a++
        while (b > a && pyIsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** Python str.split() with no argument. */
    fun pySplitWs(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && pyIsSpace(s[i])) i++
            if (i >= n) break
            val st = i
            while (i < n && !pyIsSpace(s[i])) i++
            out.add(s.substring(st, i))
        }
        return out
    }

    /** Python str.splitlines() (no keepends). */
    fun pySplitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var st = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val br = c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u001C' ||
                c == '\u001D' || c == '\u001E' || c == '\u0085' || c == ' ' || c == ' '
            if (br) {
                out.add(text.substring(st, i))
                if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                st = i + 1
            }
            i++
        }
        if (st < n) out.add(text.substring(st))
        return out
    }

    // ----------------------------------------------------------------------------- PRO GUI

    private const val PUNCT_NO_SPACE_BEFORE = "،؛,.!?؟:"

    /** norm_text(): strips tashkeel + tatweel, collapses spaces, fixes space before punctuation. */
    fun normText(s: String?): String {
        val src = s ?: ""
        // 1) re.sub(r'[ً-ٰٟ]', '') and 2) .replace('ـ', '')
        val a = StringBuilder(src.length)
        for (c in src) if (!isTashkeelPro(c) && c != 'ـ') a.append(c)
        // 3) re.sub(r'[\s‏‎]+', ' ').strip()
        val b = StringBuilder(a.length)
        var inWs = false
        for (c in a) {
            if (pyIsSpace(c) || c == '‏' || c == '‎') {
                if (!inWs) b.append(' ')
                inWs = true
            } else {
                b.append(c); inWs = false
            }
        }
        val t = pyStrip(b.toString())
        // 4) re.sub(r'\s+([،؛,.!?؟:])', r'\1')
        val c = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            if (pyIsSpace(t[i])) {
                var j = i
                while (j < t.length && pyIsSpace(t[j])) j++
                if (j < t.length && PUNCT_NO_SPACE_BEFORE.indexOf(t[j]) >= 0) {
                    i = j; continue
                }
                c.append(t, i, j); i = j; continue
            }
            c.append(t[i]); i++
        }
        // 5) .strip(' \t\r\n')
        return c.toString().trim(' ', '\t', '\r', '\n')
    }

    /** PRO similarity(): normalized, spaces removed, difflib ratio. */
    fun similarityPro(a: String, b: String): Double {
        val x = normText(a).replace(" ", "")
        val y = normText(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return SequenceMatcher.ratio(x, y)
    }

    private fun wordBefore(s: String, i: Int) = i > 0 && pyIsWord(s[i - 1])
    private fun wordAt(s: String, i: Int) = i < s.length && pyIsWord(s[i])

    /** re.sub(r'(?<!\w)(?:0{2,}\s+\d{1,3})(?!\w)', ' ', s) */
    private fun sus1(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '0' && !wordBefore(s, i)) {
                var j = i
                while (j < s.length && s[j] == '0') j++
                if (j - i >= 2 && j < s.length && pyIsSpace(s[j])) {
                    var k = j
                    while (k < s.length && pyIsSpace(s[k])) k++
                    var d = k
                    while (d < s.length && pyIsDigit(s[d])) d++
                    val nd = d - k
                    if (nd in 1..3 && !wordAt(s, d)) {
                        out.append(' '); i = d; continue
                    }
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** re.sub(r'(?<!\w)0{2,}(?!\w)', ' ', s) */
    private fun sus2(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '0' && !wordBefore(s, i)) {
                var j = i
                while (j < s.length && s[j] == '0') j++
                if (j - i >= 2 && !wordAt(s, j)) {
                    out.append(' '); i = j; continue
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** re.sub(r'(?<!\w)(\d{1,3})\s+\1(?!\w)', r'\1', s) */
    private fun sus3(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (pyIsDigit(s[i]) && !wordBefore(s, i)) {
                var j = i
                while (j < s.length && pyIsDigit(s[j])) j++
                val len = j - i
                if (len in 1..3 && j < s.length && pyIsSpace(s[j])) {
                    var k = j
                    while (k < s.length && pyIsSpace(s[k])) k++
                    if (k + len <= s.length && s.regionMatches(k, s, i, len) && !wordAt(s, k + len)) {
                        out.append(s, i, j); i = k + len; continue
                    }
                }
            }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** _clean_suspicious_numbers(): drops Lens background number garbage like "000 10". */
    fun cleanSuspiciousNumbers(s: String): String = normText(sus3(sus2(sus1(s))))

    private const val EXTRA_KEEP = "[](){}<>+-=*/%:;,.!?…–—_"
    private val NONE_WORDS = setOf("none", "no text", "no subtitle", "text not found", "na", "n/a")

    fun hasArabic(s: String): Boolean {
        for (c in s) if (c in '؀'..'ۿ') return true
        return false
    }

    /** re.sub(r'[\s\W_]+', '', s): keep only Unicode letters and numbers (by code point). */
    private fun dedupeKey(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (Character.isLetter(cp) || isNumberType(Character.getType(cp))) sb.appendCodePoint(cp)
            i += n
        }
        return sb.toString()
    }

    /**
     * arabic_filter(): keeps Arabic subtitle text only, line by line, dropping Latin/Turkish/…,
     * duplicated Lens regions and background number garbage. Output is a single line.
     */
    fun arabicFilter(text: String?): String {
        val cleanedLines = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw0 in pySplitLines(text ?: "")) {
            var raw = pyStrip(raw0)
            if (raw.isEmpty()) continue
            // re.sub(r'^[-•*\s]+', '', raw)
            var b = 0
            while (b < raw.length && (raw[b] == '-' || raw[b] == '•' || raw[b] == '*' || pyIsSpace(raw[b]))) b++
            raw = raw.substring(b)
            if (raw.lowercase() in NONE_WORDS) continue
            if (!hasArabic(raw)) continue
            val out = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' || ch in 'ࢠ'..'ࣿ' ||
                    ch.isDigit() || pyIsSpace(ch) || isPunctuation(ch)
                ) {
                    out.append(ch)
                } else if (EXTRA_KEEP.indexOf(ch) >= 0) {
                    out.append(ch)
                }
            }
            var cleaned = normText(out.toString())
            cleaned = cleanSuspiciousNumbers(cleaned)
            if (!hasArabic(cleaned)) continue
            val key = dedupeKey(cleaned)
            if (key.isNotEmpty() && seen.add(key)) cleanedLines.add(cleaned)
        }
        return if (cleanedLines.isNotEmpty()) normText(cleanedLines.joinToString(" ")) else ""
    }

    // ----------------------------------------------------------------------------- CLI v2.1

    val NO_TEXT_PHRASES = listOf(
        "لا يوجد نص عربي في الصورة", "لا يحتوي على نص عربي",
        "لا يوجد أي نص عربي في الصورة", "لا يوجد نص في الصورة",
        "لا توجد أية كتابة عربية في الصورة", "لا يوجد نص عربي",
        "لا يوجد نص", ".الصورة في عربي نص يوجد لا",
        ".يحتوي لا عربي نص على", ".الصورة في عربي نص أي يوجد لا",
        ".الصورة في نص يوجد لا", ".الصورة في عربية كتابة أية توجد لا",
        ".عربي نص يوجد لا", ".نص يوجد لا"
    )

    private val strictJson = Json { isLenient = false }

    /** re.sub(r'```.*?```', '', text, flags=re.DOTALL) */
    private fun removeCodeBlocks(s: String): String {
        val out = StringBuilder(s.length)
        var cur = 0
        while (true) {
            val i = s.indexOf("```", cur)
            if (i < 0) break
            val j = s.indexOf("```", i + 3)
            if (j < 0) break
            out.append(s, cur, i)
            cur = j + 3
        }
        out.append(s, cur, s.length)
        return out.toString()
    }

    /** re.sub(r'{[^}]*}', '') / re.sub(r'\[[^\]]*\]', '') */
    private fun removeEnclosed(s: String, open: Char, close: Char): String {
        val out = StringBuilder(s.length)
        var cur = 0
        while (true) {
            val i = s.indexOf(open, cur)
            if (i < 0) break
            val j = s.indexOf(close, i + 1)
            if (j < 0) break
            out.append(s, cur, i)
            cur = j + 1
        }
        out.append(s, cur, s.length)
        return out.toString()
    }

    /** re.search(r'"text_content":\s*"([^"]*)"', text) */
    private fun findTextContent(s: String): String? {
        val key = "\"text_content\":"
        var from = 0
        while (true) {
            val i = s.indexOf(key, from)
            if (i < 0) return null
            var k = i + key.length
            while (k < s.length && pyIsSpace(s[k])) k++
            if (k < s.length && s[k] == '"') {
                val e = s.indexOf('"', k + 1)
                if (e >= 0) return s.substring(k + 1, e)
            }
            from = i + 1
        }
    }

    private fun isAllowedPrecise(c: Char): Boolean =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
            c in 'ﭐ'..'﷏' || c in 'ﷰ'..'﷿' || c in 'ﹰ'..'﻿' ||
            c in '0'..'9' || pyIsSpace(c) || ".,!?:؛،؟\"()%[]-/#".indexOf(c) >= 0

    /**
     * The exact reading (AiProfile.EXACT) also keeps the punctuation Arabic subtitles use that v2.1/v2.6
     * dropped: «…» (one character), «» quotes, the en/em dash of a dialogue, the apostrophe and «;».
     */
    fun isAllowedExact(c: Char): Boolean = isAllowedPrecise(c) || "…«»–—';".indexOf(c) >= 0

    /** clean_ocr_output(): keeps diacritics and line breaks exactly as the model returned them. */
    fun cleanOcrOutput(input: String?): String = cleanOcrOutput(input) { isAllowedPrecise(it) }

    /** clean_ocr_output() with another set of kept characters ([allowed]). */
    fun cleanOcrOutput(input: String?, allowed: (Char) -> Boolean): String {
        var text = input ?: ""
        for (phrase in NO_TEXT_PHRASES) if (text.contains(phrase)) return ""

        if (text.lowercase().contains("json") && (text.contains('{') || text.contains('['))) {
            val parsed: JsonElement? = try {
                strictJson.parseToJsonElement(text.trim('`', ' ', '\n'))
            } catch (e: Exception) {
                null
            }
            if (parsed != null) {
                text = when {
                    parsed is JsonObject && parsed.containsKey("text_content") -> {
                        val v = if (parsed.containsKey("text")) parsed["text"] else parsed["text_content"]
                        (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
                    }
                    parsed is JsonArray && parsed.isNotEmpty() && parsed[0] is JsonObject &&
                        (parsed[0] as JsonObject).containsKey("label") -> {
                        parsed.filterIsInstance<JsonObject>()
                            .mapNotNull { o -> o["label"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() } }
                            .joinToString(" ")
                    }
                    else -> ""
                }
            } else {
                val m = findTextContent(text)
                text = m ?: removeEnclosed(removeEnclosed(text, '{', '}'), '[', ']')
            }
        }

        text = removeCodeBlocks(text)
        text = text.replace("﻿", "").replace("￾", "")
        val cleaned = StringBuilder(text.length)
        for (c in text) if (allowed(c)) cleaned.append(c)
        val lines = ArrayList<String>()
        for (line in cleaned.split('\n')) {
            val l = pyStrip(line).replace("\r", "")
            if (l.isNotEmpty()) lines.add(l)
        }
        return lines.joinToString("\n")
    }

    /** process_single_image(): results made only of quotes/symbols are not real subtitles. */
    fun isSymbolsOnly(text: String): Boolean {
        val t = pyStrip(text)
        if (t == "\"\"" || t == "''") return true
        if (t.isEmpty()) return false
        for (c in t) if (!(pyIsSpace(c) || "\"'()[]{}،.,:;!?؟-_".indexOf(c) >= 0)) return false
        return true
    }

    /** _calculate_text_similarity() of the CLI (keeps diacritics, removes whitespace). */
    fun similarityStrict(t1: String, t2: String): Double {
        if (t1 == t2) return 1.0
        if (t1.isEmpty() || t2.isEmpty()) return 0.0
        val c1 = t1.filterNot { pyIsSpace(it) }
        val c2 = t2.filterNot { pyIsSpace(it) }
        if (c1 == c2) return 1.0
        if (c1.isEmpty() || c2.isEmpty()) return 0.0
        return SequenceMatcher.ratio(c1, c2)
    }

    /** Removes diacritics only (used when the user switches tashkeel off in precise mode). */
    fun stripDiacritics(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (!isTashkeelPro(c) && c != 'ـ') sb.append(c)
        return sb.toString()
    }
}
