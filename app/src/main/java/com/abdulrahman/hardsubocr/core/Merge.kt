package com.abdulrahman.hardsubocr.core

data class SubEntry(val startMs: Long, val endMs: Long, val text: String)

enum class MergeMode { PRO, STRICT }

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
     * merge_ocr_entries() of the CLI v2.1 ("ORIGINAL MERGE LOGIC - DO NOT MODIFY"):
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
