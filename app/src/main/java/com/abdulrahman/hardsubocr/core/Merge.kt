package com.abdulrahman.hardsubocr.core

data class SubEntry(val startMs: Long, val endMs: Long, val text: String)

/**
 * PRO    = merge repeats: neighbours within 350 ms whose texts are >= 90 % similar.
 * STRICT = merge split frames only (safe): touching pieces (gap <= 10 ms, VSF writes 1 ms) of the
 *          SAME text. Different texts are never merged.
 * NONE   = no merging at all: every image is its own subtitle, exactly as named.
 */
enum class MergeMode { PRO, STRICT, NONE }

object Merge {

    /**
     * merge_entries() of the PRO GUI (called with gap = 0.35 s there):
     * adjacent pieces closer than [gapMs] whose texts are >= 90% similar become one subtitle.
     * [normalize] = true reproduces `t = norm_text(t)` (used with the PRO cleaning profile).
     */
    fun mergePro(items: List<SubEntry>, gapMs: Long = 350, normalize: Boolean = true): List<SubEntry> {
        val sorted = items.sortedBy { it.startMs } // stable, like Python's sorted()
        val out = ArrayList<SubEntry>()
        for (it0 in sorted) {
            val t = if (normalize) ArabicText.normText(it0.text) else it0.text.trim()
            if (t.isEmpty()) continue
            if (out.isEmpty()) {
                out.add(SubEntry(it0.startMs, it0.endMs, t)); continue
            }
            val last = out[out.size - 1]
            if (it0.startMs - last.endMs <= gapMs && ArabicText.similarityPro(last.text, t) >= 0.90) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, it0.endMs))
            } else {
                out.add(SubEntry(it0.startMs, it0.endMs, t))
            }
        }
        return out
    }

    /**
     * Safe split-frame merge (replaces v2.1's rules in the app).
     *
     * v2.1 rule 2E merged two DIFFERENT sentences whenever they were 70–90 % similar and kept only
     * the first one, so the second subtitle silently disappeared ("هل أنت بخير؟" + "هل أنت متأكد؟").
     * Because VideoSubFinder writes back-to-back subtitles with a 1 ms gap, almost every pair hit it.
     *
     * Here two pieces are joined only when they touch (gap <= [maxGapMs]) and are the same text
     * ([sameThreshold] after removing tashkeel/punctuation case-insensitively). The kept text is
     * the one of the longer piece (more frames = the fully visible state, not a fade).
     */
    fun mergeSplits(items: List<SubEntry>, maxGapMs: Long = 10, sameThreshold: Double = 0.92): List<SubEntry> {
        val sorted = items.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        val out = ArrayList<SubEntry>(sorted.size)
        val keys = ArrayList<String>(sorted.size)
        val longest = ArrayList<Long>(sorted.size)
        for (cur in sorted) {
            val key = matchKey(cur.text)
            if (out.isNotEmpty()) {
                val last = out[out.size - 1]
                val lastKey = keys[keys.size - 1]
                val gap = cur.startMs - last.endMs
                if (gap in -maxGapMs..maxGapMs && key.isNotEmpty() &&
                    (key == lastKey || SequenceMatcher.ratio(lastKey, key) >= sameThreshold)
                ) {
                    val dur = cur.endMs - cur.startMs
                    val keepCur = dur > longest[longest.size - 1]
                    out[out.size - 1] = SubEntry(last.startMs, maxOf(last.endMs, cur.endMs), if (keepCur) cur.text else last.text)
                    if (keepCur) {
                        keys[keys.size - 1] = key; longest[longest.size - 1] = dur
                    }
                    continue
                }
            }
            out.add(cur); keys.add(key); longest.add(cur.endMs - cur.startMs)
        }
        return out
    }

    /** No merging: sorted, empty texts dropped, everything else exactly as recognised. */
    fun noMerge(items: List<SubEntry>): List<SubEntry> =
        items.filter { it.text.isNotBlank() }.sortedBy { it.startMs }

    /** Comparison key: letters and digits only, tashkeel/tatweel removed, lower-case. */
    fun matchKey(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (ArabicV26.isMark(c) || c == 'ـ') continue
            if (Character.isLetterOrDigit(c)) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /**
     * merge_ocr_entries() of the CLI v2.1 ("ORIGINAL MERGE LOGIC - DO NOT MODIFY"), kept only for
     * the verification vectors. The app uses [mergeSplits] instead (see its doc for the 2E bug).
     * only split frames (gap <= 0.01 s) are merged, using similarity + duration-ratio rules 2A..2E.
     * The first (cleaner) text is always kept.
     */
    fun mergeStrict(items: List<SubEntry>): List<SubEntry> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedBy { it.startMs }
        val merged = ArrayList<SubEntry>()
        for (cur in sorted) {
            if (merged.isEmpty()) {
                merged.add(cur); continue
            }
            val last = merged[merged.size - 1]
            val gap = cur.startMs - last.endMs
            if (gap <= 10) {
                val lastDuration = (last.endMs - last.startMs).toDouble()
                val curDuration = (cur.endMs - cur.startMs).toDouble()
                if (lastDuration > 0) {
                    val ratio = curDuration / lastDuration
                    val sim = ArabicText.similarityStrict(last.text, cur.text)
                    when {
                        sim >= 0.95 -> merged[merged.size - 1] = last.copy(endMs = cur.endMs)          // 2A
                        sim >= 0.90 && ratio <= 2.0 -> merged[merged.size - 1] = last.copy(endMs = cur.endMs) // 2B
                        sim < 0.70 -> merged.add(cur)                                                    // 2C
                        ratio > 2.0 -> merged.add(cur)                                                   // 2D
                        else -> merged[merged.size - 1] = last.copy(endMs = cur.endMs)                  // 2E
                    }
                } else {
                    merged[merged.size - 1] = last.copy(endMs = cur.endMs)
                }
            } else {
                merged.add(cur)
            }
        }
        return merged
    }
}

object Srt {
    const val BOM = "﻿"

    /** write_srt(): UTF-8 (+BOM by default, like utf-8-sig on Windows), '\n' line endings. */
    fun build(entries: List<SubEntry>, bom: Boolean = true, crlf: Boolean = false): String {
        val nl = if (crlf) "\r\n" else "\n"
        val sb = StringBuilder()
        if (bom) sb.append(BOM)
        entries.forEachIndexed { i, e ->
            sb.append(i + 1).append(nl)
            sb.append(Timestamps.srt(e.startMs)).append(" --> ").append(Timestamps.srt(e.endMs)).append(nl)
            sb.append(if (crlf) e.text.replace("\n", "\r\n") else e.text).append(nl).append(nl)
        }
        return sb.toString()
    }

    fun bytes(entries: List<SubEntry>, bom: Boolean = true, crlf: Boolean = false): ByteArray =
        build(entries, bom, crlf).toByteArray(Charsets.UTF_8)
}
