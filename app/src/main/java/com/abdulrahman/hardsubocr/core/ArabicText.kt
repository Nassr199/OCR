package com.abdulrahman.hardsubocr.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Faithful ports of the text logic of the two Windows programs:
 *  - PRO GUI  (HardSubArabicOCR_v2_PRO.py): norm_text, _clean_suspicious_numbers, arabic_filter, similarity
 *  - CLI v2.1 (ocr_windows_v2.1.py):        clean_ocr_output, NO_TEXT_PHRASES, symbol-only check,
 *                                            _calculate_text_similarity
 *
 * Python's `re` is Unicode aware by default, so every Java pattern below uses (?U)
 * (UNICODE_CHARACTER_CLASS) to get the same \s \w \d semantics.
 */
object ArabicText {

    // ----------------------------------------------------------------- PRO GUI

    private val DIACRITICS = Regex("[\\u064B-\\u065F\\u0670]")
    private val SPACES = Regex("(?U)[\\s\\u200f\\u200e]+")
    private val SPACE_BEFORE_PUNCT = Regex("(?U)\\s+([،؛,.!?؟:])")

    /** norm_text(): strips tashkeel + tatweel, collapses spaces, fixes space before punctuation. */
    fun normText(s: String?): String {
        var t = DIACRITICS.replace(s ?: "", "")
        t = t.replace("ـ", "")
        t = SPACES.replace(t, " ").trim()
        t = SPACE_BEFORE_PUNCT.replace(t, "$1")
        return t.trim(' ', '\t', '\r', '\n')
    }

    /** PRO similarity(): normalized, spaces removed, difflib ratio. */
    fun similarityPro(a: String, b: String): Double {
        val x = normText(a).replace(" ", "")
        val y = normText(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return SequenceMatcher.ratio(x, y)
    }

    // Python's \w is [\p{L}\p{N}_] (it excludes combining marks, Java's (?U)\w does not).
    private const val PW = "[\\p{L}\\p{N}_]"
    private val SUS_1 = Regex("(?U)(?<!$PW)(?:0{2,}\\s+\\d{1,3})(?!$PW)")
    private val SUS_2 = Regex("(?U)(?<!$PW)0{2,}(?!$PW)")
    private val SUS_3 = Regex("(?U)(?<!$PW)(\\d{1,3})\\s+\\1(?!$PW)")

    /** _clean_suspicious_numbers(): drops Lens background number garbage like "000 10". */
    fun cleanSuspiciousNumbers(s: String): String {
        var t = SUS_1.replace(s, " ")
        t = SUS_2.replace(t, " ")
        t = SUS_3.replace(t, "$1")
        return normText(t)
    }

    private val LINE_SPLIT = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085  ]")
    private val LEADING_BULLETS = Regex("(?U)^[-•*\\s]+")
    private val NONE_LINE = Regex("(?i)(none|no text|no subtitle|text not found|n/?a)")
    private val HAS_ARABIC = Regex("[\\u0600-\\u06FF]")
    private val KEY_STRIP = Regex("[^\\p{L}\\p{N}]+") // == Python [\\s\\W_]+
    private const val EXTRA_KEEP = "[](){}<>+-=*/%:;,.!?…–—_"

    /** Python str.splitlines() (no keepends), dropping nothing. */
    fun pySplitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = LINE_SPLIT.split(text)
        // splitlines() never yields a trailing empty element for a terminating newline
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /** Python str.isspace() for a single character. */
    fun pyIsSpace(c: Char): Boolean =
        Character.isSpaceChar(c) || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' ||
            c == '\r' || c == '\u001C' || c == '\u001D' || c == '\u001E' || c == '\u001F' || c == '\u0085'

    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * arabic_filter(): keeps Arabic subtitle text only, line by line, dropping Latin/Turkish/…,
     * duplicated Lens regions and background number garbage. Output is a single line.
     */
    fun arabicFilter(text: String?): String {
        val cleanedLines = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw0 in pySplitLines(text ?: "")) {
            var raw = raw0.trim()
            if (raw.isEmpty()) continue
            raw = LEADING_BULLETS.replace(raw, "")
            if (NONE_LINE.matches(raw)) continue
            if (!HAS_ARABIC.containsMatchIn(raw)) continue
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
            if (!HAS_ARABIC.containsMatchIn(cleaned)) continue
            val key = KEY_STRIP.replace(cleaned, "")
            if (key.isNotEmpty() && seen.add(key)) cleanedLines.add(cleaned)
        }
        return if (cleanedLines.isNotEmpty()) normText(cleanedLines.joinToString(" ")) else ""
    }

    // ----------------------------------------------------------------- CLI v2.1

    val NO_TEXT_PHRASES = listOf(
        "لا يوجد نص عربي في الصورة", "لا يحتوي على نص عربي",
        "لا يوجد أي نص عربي في الصورة", "لا يوجد نص في الصورة",
        "لا توجد أية كتابة عربية في الصورة", "لا يوجد نص عربي",
        "لا يوجد نص", ".الصورة في عربي نص يوجد لا",
        ".يحتوي لا عربي نص على", ".الصورة في عربي نص أي يوجد لا",
        ".الصورة في نص يوجد لا", ".الصورة في عربية كتابة أية توجد لا",
        ".عربي نص يوجد لا", ".نص يوجد لا"
    )

    private val CODE_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_CONTENT = Regex("\"text_content\":\\s*\"([^\"]*)\"")
    private val BRACES = Regex("\\{[^}]*\\}")
    private val BRACKETS = Regex("\\[[^\\]]*\\]")
    private val DISALLOWED = Regex(
        "(?U)[^\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDCF\\uFDF0-\\uFDFF\\uFE70-\\uFEFF" +
            "\\u064B-\\u0652\\u0030-\\u0039\\s.,!?:؛،؛؟\"()%\\[\\]\\-/#]+"
    )
    private val SYMBOLS_ONLY = Regex("(?U)^[\\s\"'()\\[\\]{}،.,:;!?؟\\-_]+$")
    private val lenientJson = Json { isLenient = false }

    /** clean_ocr_output(): keeps diacritics and line breaks exactly as the model returned them. */
    fun cleanOcrOutput(input: String?): String {
        var text = input ?: ""
        for (phrase in NO_TEXT_PHRASES) if (text.contains(phrase)) return ""

        if (text.lowercase().contains("json") && (text.contains('{') || text.contains('['))) {
            val parsed: JsonElement? = try {
                lenientJson.parseToJsonElement(text.trim('`', ' ', '\n'))
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
                val m = TEXT_CONTENT.find(text)
                if (m != null) {
                    text = m.groupValues[1]
                } else {
                    text = BRACES.replace(text, "")
                    text = BRACKETS.replace(text, "")
                }
            }
        }

        text = CODE_BLOCK.replace(text, "")
        text = text.replace("﻿", "").replace("￾", "")
        val cleaned = DISALLOWED.replace(text, "")
        val lines = ArrayList<String>()
        for (line in cleaned.split('\n')) {
            val l = line.trim().replace("\r", "")
            if (l.isNotEmpty()) lines.add(l)
        }
        return lines.joinToString("\n")
    }

    /** process_single_image(): results made only of quotes/symbols are not real subtitles. */
    fun isSymbolsOnly(text: String): Boolean {
        val t = text.trim()
        if (t == "\"\"" || t == "''") return true
        return SYMBOLS_ONLY.matches(t)
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
    fun stripDiacritics(s: String): String = DIACRITICS.replace(s, "").replace("ـ", "")
}
