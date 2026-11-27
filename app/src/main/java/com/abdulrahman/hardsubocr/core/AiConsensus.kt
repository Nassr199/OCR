package com.abdulrahman.hardsubocr.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * «التحقق المزدوج» for Gemini/OpenRouter (2.9.0): the model checks itself, with no other engine.
 *
 * Measured live on Gemini 3.5 Flash-Lite (bench, 3 runs of each frame): the model's mistakes depend
 * on the picture it is given, and each picture fails on different frames — v2.6's grayscale picture
 * wrote «أهوليام، استيقظي» on a light floor and joined two lines; the original picture misread a dot;
 * a picture cut close around the subtitle fixed both and then wrote «بخير بخير بخير» three times out
 * of three. The same model rarely makes the same mistake on two different pictures.
 *
 * So each image is read from two pictures at once ([views] 0 and 1, e.g. the original and v2.6's).
 * The same text (letters, punctuation and lines; tashkeel aside) is accepted. Two different texts
 * mean one of them is wrong: a third picture is read ([views] 2, the subtitle framed) and decides —
 * the text two readings share, or else the one closest to the other two.
 */
object AiConsensus {

    class Outcome(val text: String, val readings: Int, val agreed: Boolean, val texts: List<String>)

    /**
     * Reads [views] (at least two; a third breaks ties) with [read], which returns the cleaned text
     * or throws. The first two are read at the same time. A failure of one reading is not the
     * image's failure while another reading succeeds.
     */
    suspend fun read(views: List<PreparedImage>, read: suspend (PreparedImage) -> String): Outcome = coroutineScope {
        if (views.size < 2) return@coroutineScope views.first().let { Outcome(read(it), 1, true, emptyList()) }
        val first = listOf(async { runCatching { read(views[0]) } }, async { runCatching { read(views[1]) } }).map { it.await() }
        val ok = first.mapNotNull { it.getOrNull() }.toMutableList()
        if (ok.size == 2 && key(ok[0]) == key(ok[1])) return@coroutineScope Outcome(ok[0], 2, true, ok)
        if (views.size < 3) {
            if (ok.isEmpty()) throw first.first().exceptionOrNull()!!
            return@coroutineScope Outcome(ok.first(), 2, false, ok)
        }
        val third = runCatching { read(views[2]) }
        third.getOrNull()?.let { ok.add(it) }
        if (ok.isEmpty()) throw first.first().exceptionOrNull()!!
        Outcome(pick(ok), 3, false, ok)
    }

    /** What two readings must share to be the same reading: letters, punctuation, lines — not tashkeel or spacing. */
    fun key(text: String): String =
        text.lines()
            .map { line ->
                buildString {
                    for (c in line) if (!isMark(c)) append(c)
                }.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun isMark(c: Char): Boolean = c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ'

    /** The text two of [texts] share (the first of them), or else the one closest to all the others. */
    fun pick(texts: List<String>): String {
        if (texts.size == 1) return texts[0]
        val keys = texts.map { key(it) }
        for (i in texts.indices) for (j in i + 1 until texts.size) if (keys[i] == keys[j]) return texts[i]
        var best = 0
        var bestSum = Int.MAX_VALUE
        for (i in texts.indices) {
            val sum = texts.indices.filter { it != i }.sumOf { distance(keys[i], keys[it]) }
            if (sum < bestSum) { best = i; bestSum = sum }
        }
        return texts[best]
    }

    /** Edit distance (insert, delete, replace = 1). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
