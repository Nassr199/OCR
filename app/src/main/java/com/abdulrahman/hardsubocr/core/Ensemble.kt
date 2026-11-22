package com.abdulrahman.hardsubocr.core

/**
 * Engine ensemble for video: the primary engine's subtitles are kept as they are; a subtitle found by
 * another engine is added only where nothing kept overlaps it (a line the primary missed). Engines are
 * consulted in the given order, so earlier fill-in engines win over later ones.
 */
object Ensemble {

    /** Same subtitle if they overlap by at least [minShare] of the shorter one. */
    fun sameSubtitle(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long, minShare: Double = 0.30): Boolean {
        val ov = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        if (ov <= 0) return false
        val shorter = minOf(aEnd - aStart, bEnd - bStart).coerceAtLeast(1)
        return ov >= shorter * minShare
    }

    /** Offline form (tests): returns the indices (engine, index) that make the final list, sorted by start. */
    fun fuse(engines: List<List<LongArray>>): List<Pair<Int, Int>> {
        val kept = ArrayList<LongArray>()
        val out = ArrayList<Pair<Int, Int>>()
        for ((e, list) in engines.withIndex()) {
            for ((i, s) in list.withIndex()) {
                if (e > 0 && kept.any { k -> sameSubtitle(k[0], k[1], s[0], s[1]) }) continue
                kept.add(s); out.add(e to i)
            }
        }
        return out.sortedBy { (e, i) -> engines[e][i][0] }
    }

    /**
     * Frame-level voting. [engines] = per engine, its segments as [startFrame, endFrameExclusive].
     * Returns fused segments as [start, end, engine, index] where (engine, index) is the engine segment
     * that best covers it (its image is used). A frame is text if any engine sees text; a cut is made
     * where >= 2 engines put a boundary within ±[tol] frames (or where only one engine sees text).
     */
    fun fuseFrames(engines: List<List<IntArray>>, minFrames: Int, tol: Int = 2): List<IntArray> {
        val all = engines.flatten()
        if (all.isEmpty()) return emptyList()
        val t0 = all.minOf { it[0] }
        val t1 = all.maxOf { it[1] }
        val n = t1 - t0 + 1
        val e = engines.size
        // per engine: covering segment index at each frame
        val label = Array(e) { IntArray(n) { -1 } }
        for (k in 0 until e) for ((i, s) in engines[k].withIndex()) for (f in s[0] until s[1]) label[k][f - t0] = i
        // boundary marks: a segment starts/ends at f (transitions, including text→other text)
        val mark = Array(e) { BooleanArray(n + 1) }
        for (k in 0 until e) for (s in engines[k]) {
            mark[k][s[0] - t0] = true; mark[k][s[1] - t0] = true
        }
        val present = BooleanArray(n) { f -> (0 until e).any { k -> label[k][f] >= 0 } }
        val cut = BooleanArray(n + 1)
        for (f in 0..n) {
            var votes = 0
            var seeing = 0
            for (k in 0 until e) {
                val lo = maxOf(0, f - tol)
                val hi = minOf(n, f + tol)
                var m = false
                for (g in lo..hi) if (mark[k][g]) { m = true; break }
                if (m) votes++
                var sees = false
                for (g in maxOf(0, f - tol) until minOf(n, f + tol + 1)) if (label[k][g] >= 0) { sees = true; break }
                if (sees) seeing++
            }
            cut[f] = votes >= minOf(2, maxOf(1, seeing))
        }
        // keep one cut per cluster of adjacent cut frames (the middle one)
        val cuts = ArrayList<Int>()
        var f = 0
        while (f <= n) {
            if (!cut[f]) { f++; continue }
            var g = f
            while (g + 1 <= n && cut[g + 1]) g++
            cuts.add((f + g) / 2)
            f = g + 1
        }
        val bounds = (cuts + listOf(0, n)).distinct().sorted()
        val out = ArrayList<IntArray>()
        for (b in 0 until bounds.size - 1) {
            var a = bounds[b]
            val z = bounds[b + 1]
            // split the interval further where presence changes
            var x = a
            while (x < z) {
                while (x < z && !present[x]) x++
                if (x >= z) break
                var y = x
                while (y < z && present[y]) y++
                if (y - x >= minFrames) {
                    // best covering engine segment (preference: earlier engine on ties)
                    var bestE = -1
                    var bestI = -1
                    var bestOv = 0
                    for (k in 0 until e) {
                        val cnt = HashMap<Int, Int>()
                        for (q in x until y) if (label[k][q] >= 0) cnt[label[k][q]] = (cnt[label[k][q]] ?: 0) + 1
                        for ((i, c) in cnt) if (c > bestOv) { bestOv = c; bestE = k; bestI = i }
                    }
                    out.add(intArrayOf(x + t0, y + t0, bestE, bestI))
                }
                x = y
            }
            a = z
        }
        return out
    }
}
