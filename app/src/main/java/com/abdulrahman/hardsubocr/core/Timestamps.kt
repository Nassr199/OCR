package com.abdulrahman.hardsubocr.core

import java.util.Locale

/**
 * Time helpers. All times are kept as integer milliseconds to avoid the float
 * rounding problems of the Windows version (fmt() could print "00:00:60,000"
 * and format_timestamp() truncated 1.001 s to 1,000).
 */
object Timestamps {

    // VideoSubFinder naming: 0_00_10_000__0_00_12_000[...].png
    // Windows used a single hour digit; \d+ also accepts 10+ hours correctly.
    private val VSF = Regex("""(\d+)_(\d{2})_(\d{2})_(\d{3})__(\d+)_(\d{2})_(\d{2})_(\d{3})""")

    /** Returns (startMs, endMs) parsed from a VideoSubFinder style file name, or null. */
    fun parseVsfName(name: String): Pair<Long, Long>? {
        val m = VSF.find(name) ?: return null
        val g = m.groupValues
        return try {
            val a = toMs(g[1], g[2], g[3], g[4])
            val b = toMs(g[5], g[6], g[7], g[8])
            a to b
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000L + m.toLong() * 60_000L + s.toLong() * 1_000L + ms.toLong()

    /** SRT time: HH:MM:SS,mmm */
    fun srt(ms: Long): String {
        val t = if (ms < 0) 0L else ms
        val h = t / 3_600_000L
        val m = (t % 3_600_000L) / 60_000L
        val s = (t % 60_000L) / 1_000L
        val milli = t % 1_000L
        return "%02d:%02d:%02d,%03d".format(Locale.US, h, m, s, milli)
    }

    /** VideoSubFinder compatible name part: h_mm_ss_mmm */
    fun vsfPart(ms: Long): String {
        val t = if (ms < 0) 0L else ms
        val h = t / 3_600_000L
        val m = (t % 3_600_000L) / 60_000L
        val s = (t % 60_000L) / 1_000L
        val milli = t % 1_000L
        return "%d_%02d_%02d_%03d".format(Locale.US, h, m, s, milli)
    }

    fun vsfName(startMs: Long, endMs: Long, ext: String = "png"): String =
        "${vsfPart(startMs)}__${vsfPart(endMs)}.$ext"

    /** Human readable duration for the dashboard. */
    fun human(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "—"
        if (seconds < 60) return "%.1f ث".format(Locale.US, seconds)
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d س %02d د".format(Locale.US, h, m) else "%d د %02d ث".format(Locale.US, m, s)
    }
}
