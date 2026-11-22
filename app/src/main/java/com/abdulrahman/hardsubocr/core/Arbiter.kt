package com.abdulrahman.hardsubocr.core

/**
 * "The text read decides" — fusion of the video engines by OCR evidence instead of pixel votes.
 *
 * The 2.1 engine's subtitles are the BASE and are kept exactly (same times, same images, same text).
 * The other engines (2.2, 2.0) are only EVIDENCE: their subtitles are read too (each from its own
 * frames) and change the result only where what was read proves 2.1 wrong:
 *  - time where 2.1 has no readable subtitle but another engine reads Arabic  → a new line
 *    (a line 2.1 missed, or a 2.1 image that read only "2." / credits);
 *  - inside a 2.1 subtitle, a SHORTER piece of another engine reads a different line → split
 *    (2.1 joined two subtitles; its composite then showed only one of them);
 *  - at the edges of a 2.1 subtitle, shorter pieces read nothing (credits only) → trim the time;
 *  - right after a 2.1 subtitle another engine still reads the same line → extend it.
 * With no contradicting evidence every 2.1 subtitle comes out unchanged.
 */
object Arbiter {

    /** One subtitle candidate. [text]: cleaned OCR text ("" = read nothing), null = not read. */
    class Piece(val startUs: Long, val endUs: Long, val text: String?)

    /** A final line. [engine] = -1 for the base, else the evidence engine's index. */
    class Line(
        val startUs: Long,
        val endUs: Long,
        val text: String,
        val engine: Int,
        val ref: Int,
        val note: String
    )

    /** Same subtitle line despite OCR noise (diacritics, punctuation, a wrong letter, a partial read)? */
    fun same(a: String, b: String): Boolean {
        val ka = Merge.matchKey(a)
        val kb = Merge.matchKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        val s = if (ka.length <= kb.length) ka else kb
        val l = if (ka.length <= kb.length) kb else ka
        if (s.length >= 3 && l.contains(s)) return true
        return SequenceMatcher.ratio(ka, kb) >= 0.8
    }

    /**
     * Clearly a different line (what splitting a base line needs): not [same] and less than 60 %
     * alike. Between the two (a misread of the same line) the base line is kept.
     */
    fun different(a: String, b: String): Boolean {
        val ka = Merge.matchKey(a)
        val kb = Merge.matchKey(b)
        if (ka.isEmpty() || kb.isEmpty() || same(a, b)) return false
        return SequenceMatcher.ratio(ka, kb) < 0.6
    }

    private class Grid(base: List<Piece>, alts: List<List<Piece>>, val frameUs: Long) {
        val f = frameUs.coerceAtLeast(1)
        fun idx(us: Long): Int = ((us + f / 2) / f).toInt()
        val t0: Int
        val n: Int
        init {
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (p in base) { lo = minOf(lo, idx(p.startUs)); hi = maxOf(hi, idx(p.endUs)) }
            for (l in alts) for (p in l) { lo = minOf(lo, idx(p.startUs)); hi = maxOf(hi, idx(p.endUs)) }
            if (lo == Int.MAX_VALUE) { lo = 0; hi = 0 }
            t0 = lo
            n = maxOf(0, hi - lo)
        }
        fun s(p: Piece) = idx(p.startUs) - t0
        fun e(p: Piece) = maxOf(idx(p.startUs) + 1, idx(p.endUs)) - t0
        fun len(p: Piece) = e(p) - s(p)
        /** Per frame: index of the piece covering it (pieces of one engine never overlap; last wins). */
        fun cover(list: List<Piece>, keep: (Int) -> Boolean = { true }): IntArray {
            val a = IntArray(n) { -1 }
            for ((i, p) in list.withIndex()) if (keep(i)) for (x in s(p) until minOf(n, e(p))) a[x] = i
            return a
        }
    }

    /**
     * Which evidence pieces are worth reading: not a copy of a base piece (same start and end within
     * 2 frames) and either more specific than a base piece they overlap (shorter than 90 % of it) or
     * covering at least [minFrames] frames that no base piece covers. When the base texts are known,
     * every piece over a base piece that read nothing ("" — e.g. only "2." or credits were read) is
     * read too, copies included: another engine's frames may show the line the composite lost.
     */
    fun needed(base: List<Piece>, alts: List<List<Piece>>, frameUs: Long, minFrames: Int): List<List<Int>> {
        val g = Grid(base, alts, frameUs)
        val baseAt = g.cover(base)
        return alts.map { list ->
            val out = ArrayList<Int>()
            for ((i, a) in list.withIndex()) {
                val a0 = g.s(a)
                val a1 = minOf(g.n, g.e(a))
                var uncovered = 0
                var specific = false
                var copy = false
                var lastB = -2
                var overBlank = 0
                for (x in a0 until a1) {
                    val b = baseAt[x]
                    if (b < 0) { uncovered++; continue }
                    if (base[b].text?.isBlank() == true) overBlank++
                    if (b == lastB) continue
                    lastB = b
                    val bp = base[b]
                    if (kotlin.math.abs(g.s(bp) - a0) <= 2 && kotlin.math.abs(g.e(bp) - a1) <= 2) copy = true
                    if (g.len(a) < g.len(bp) * 0.9) specific = true
                }
                if ((!copy && (specific || uncovered >= minFrames)) || overBlank >= minFrames.coerceAtLeast(1)) out.add(i)
            }
            out
        }
    }

    @JvmField var TRIM = true

    private const val NONE = 0
    private const val EMPTY = 1      // evidence read nothing here (outside a base line)
    private const val EMPTY_IN = 2   // evidence read nothing here, inside base line [b]
    private const val BASE = 3
    private const val ALT = 4

    private class Run(var kind: Int, var b: Int, var e: Int, var i: Int, var from: Int, var to: Int) {
        val len get() = to - from
        val isText get() = kind == BASE || kind == ALT
    }

    /**
     * Final lines from the base pieces (texts read) and the evidence pieces (texts read where
     * [needed] said so, null elsewhere). [minFrames]: shortest line that may be created or kept
     * as a separate piece by the evidence.
     */
    fun resolve(base: List<Piece>, alts: List<List<Piece>>, frameUs: Long, minFrames: Int): List<Line> {
        if (base.isEmpty() && alts.all { it.isEmpty() }) return emptyList()
        val g = Grid(base, alts, frameUs)
        val n = g.n
        val baseAt = g.cover(base)
        val altAt = alts.map { l -> g.cover(l) { i -> l[i].text != null } }
        val minF = minFrames.coerceAtLeast(1)

        fun baseText(b: Int): String? = base[b].text?.takeIf { it.isNotBlank() }
        fun altText(e: Int, i: Int): String = alts[e][i].text ?: ""

        // An evidence piece's text is true for SOME of its frames, not all of them. A piece that reads
        // the same line as a base line it overlaps is about that line: its frames outside the base
        // line prove nothing (a long piece spanning several lines) unless most of it lies there.
        val gapOk = alts.mapIndexed { e, l ->
            BooleanArray(l.size) { i ->
                val p = l[i]
                val t = p.text
                if (t.isNullOrBlank()) true else {
                    var outside = 0
                    var confirms = false
                    val a0 = g.s(p)
                    val a1 = minOf(n, g.e(p))
                    for (x in a0 until a1) {
                        val b = baseAt[x]
                        val bt = if (b >= 0) baseText(b) else null
                        if (bt == null) outside++ else if (!confirms && !different(t, bt)) confirms = true
                    }
                    !confirms || outside * 2 >= a1 - a0
                }
            }
        }

        // 1) per-frame label
        val kind = IntArray(n)
        val kb = IntArray(n) { -1 }
        val ke = IntArray(n) { -1 }
        val ki = IntArray(n) { -1 }
        for (x in 0 until n) {
            val b = baseAt[x]
            val t = if (b >= 0) baseText(b) else null
            // covering evidence, most specific (shortest) first
            var c1e = -1; var c1i = -1; var c1len = Int.MAX_VALUE
            var c2e = -1; var c2i = -1; var c2len = Int.MAX_VALUE
            for (e in altAt.indices) {
                val i = altAt[e][x]
                if (i < 0) continue
                val l = g.len(alts[e][i])
                if (l < c1len) { c2e = c1e; c2i = c1i; c2len = c1len; c1e = e; c1i = i; c1len = l }
                else if (l < c2len) { c2e = e; c2i = i; c2len = l }
            }
            if (t != null) {
                kb[x] = b
                val bl = g.len(base[b])
                if (c1e < 0 || c1len >= bl * 0.9) { kind[x] = BASE; continue }
                val p1 = altText(c1e, c1i)
                if (p1.isNotBlank()) {
                    if (different(p1, t)) { kind[x] = ALT; ke[x] = c1e; ki[x] = c1i }
                    else kind[x] = BASE
                } else {
                    val other = if (c2e >= 0) altText(c2e, c2i) else ""
                    kind[x] = if (other.isNotBlank() && same(other, t)) BASE else EMPTY_IN
                }
            } else {
                val t1 = if (c1e >= 0 && gapOk[c1e][c1i]) altText(c1e, c1i) else ""
                val t2 = if (c2e >= 0 && gapOk[c2e][c2i]) altText(c2e, c2i) else ""
                when {
                    t1.isNotBlank() -> { kind[x] = ALT; ke[x] = c1e; ki[x] = c1i }
                    t2.isNotBlank() -> { kind[x] = ALT; ke[x] = c2e; ki[x] = c2i }
                    c1e >= 0 -> kind[x] = EMPTY
                    else -> kind[x] = NONE
                }
            }
        }

        // 2) runs of equal labels
        fun textOf(r: Run): String = if (r.kind == BASE) baseText(r.b)!! else altText(r.e, r.i)
        fun sameLabel(a: Run, k: Int, b: Int, e: Int, i: Int): Boolean = when {
            a.kind == BASE && k == BASE -> a.b == b
            a.kind == ALT && k == ALT -> (a.e == e && a.i == i) || !different(altText(a.e, a.i), altText(e, i))
            a.kind == EMPTY_IN && k == EMPTY_IN -> a.b == b
            else -> a.kind == k && (k == EMPTY || k == NONE)
        }
        val runs = ArrayList<Run>()
        for (x in 0 until n) {
            val last = runs.lastOrNull()
            if (last != null && last.to == x && sameLabel(last, kind[x], kb[x], ke[x], ki[x])) { last.to = x + 1; continue }
            runs.add(Run(kind[x], kb[x], ke[x], ki[x], x, x + 1))
        }

        // 3) evidence of "nothing" inside a base line: a dropout in the middle keeps the line;
        //    at its edges it trims the time (only if long enough and the line keeps >= minFrames)
        fun belongsTo(r: Run?, b: Int): Boolean =
            r != null && ((r.kind == BASE && r.b == b) || (r.kind == ALT && baseText(b)?.let { same(altText(r.e, r.i), it) } == true))
        for ((k, r) in runs.withIndex()) {
            if (r.kind != EMPTY_IN) continue
            val prev = runs.getOrNull(k - 1)?.takeIf { it.to == r.from }
            val next = runs.getOrNull(k + 1)?.takeIf { it.from == r.to }
            val inside = belongsTo(prev, r.b) && belongsTo(next, r.b)
            var kept = 0
            for (q in runs) if (q.kind == BASE && q.b == r.b) kept += q.len
            r.kind = if (TRIM && !inside && r.len >= minF && kept >= minF) EMPTY else BASE
        }

        // 4) pieces too short to be a line of their own join a text neighbour (base first)
        var changed = true
        while (changed) {
            changed = false
            // merge touching runs that now carry the same line
            var k = 0
            while (k + 1 < runs.size) {
                val a = runs[k]
                val b = runs[k + 1]
                val merge = a.to == b.from && (
                    (a.kind == BASE && b.kind == BASE && a.b == b.b) ||
                        (a.isText && b.isText && !different(textOf(a), textOf(b)) && !(a.kind == BASE && b.kind == BASE)) ||
                        (!a.isText && !b.isText && a.kind == b.kind)
                    )
                if (merge) {
                    // the merged run keeps the base label if either side is base, else the longer side
                    if (b.kind == BASE && a.kind != BASE || (a.kind == b.kind && b.len > a.len && a.kind == ALT)) {
                        a.kind = b.kind; a.b = b.b; a.e = b.e; a.i = b.i
                    }
                    a.to = b.to
                    runs.removeAt(k + 1)
                    changed = true
                } else k++
            }
            for ((j, r) in runs.withIndex()) {
                if (!r.isText || r.len >= minF) continue
                if (r.kind == BASE && runs.none { it !== r && it.kind == BASE && it.b == r.b }) continue // a whole base line: never dropped
                val prev = runs.getOrNull(j - 1)?.takeIf { it.to == r.from && it.isText }
                val next = runs.getOrNull(j + 1)?.takeIf { it.from == r.to && it.isText }
                val into = when {
                    prev != null && next != null ->
                        if (prev.kind == BASE && next.kind != BASE) prev
                        else if (next.kind == BASE && prev.kind != BASE) next
                        else if (prev.len >= next.len) prev else next
                    else -> prev ?: next
                }
                if (into == null) {
                    r.kind = NONE
                } else {
                    r.kind = into.kind; r.b = into.b; r.e = into.e; r.i = into.i
                }
                changed = true
                break
            }
        }

        // 5) one line cut in two (2.1's dropout / flash split): rejoin two pieces of the same text when
        //    another engine saw ONE subtitle across the gap and read that same text
        run {
            var k = 0
            while (k < runs.size) {
                val a = runs[k]
                if (!a.isText) { k++; continue }
                var m = k + 1
                while (m < runs.size && !runs[m].isText) m++
                if (m >= runs.size) break
                val b = runs[m]
                val ta = textOf(a)
                if (b.from > a.to && same(ta, textOf(b))) {
                    val bridged = alts.indices.any { e ->
                        alts[e].any { p ->
                            val t = p.text
                            !t.isNullOrBlank() && g.s(p) < a.to && g.e(p) > b.from && same(t, ta)
                        }
                    }
                    if (bridged) {
                        if (a.kind != BASE && b.kind == BASE) { a.kind = BASE; a.b = b.b; a.e = b.e; a.i = b.i }
                        a.to = b.to
                        for (z in m downTo k + 1) runs.removeAt(z)
                        continue
                    }
                }
                k = m
            }
        }

        // 6) lines, with exact times wherever a run boundary is a piece boundary
        fun usAtStart(x: Int, r: Run): Long {
            if (r.kind == BASE && g.s(base[r.b]) == x) return base[r.b].startUs
            for (bp in base) if (g.s(bp) == x) return bp.startUs
            for (l in alts) for (p in l) if (p.text != null && g.s(p) == x) return p.startUs
            return (x + g.t0).toLong() * g.f
        }
        fun usAtEnd(x: Int, r: Run): Long {
            if (r.kind == BASE && g.e(base[r.b]) == x) return base[r.b].endUs
            for (bp in base) if (g.e(bp) == x) return bp.endUs
            for (l in alts) for (p in l) if (p.text != null && g.e(p) == x) return p.endUs
            return (x + g.t0).toLong() * g.f
        }
        val names = listOf("2.2", "2.0", "أوضح إطار في 2.1")
        val out = ArrayList<Line>()
        for (r in runs) {
            if (!r.isText) continue
            val s = usAtStart(r.from, r)
            val e = maxOf(s + 1, usAtEnd(r.to, r))
            if (r.kind == BASE) {
                val bp = base[r.b]
                val note = when {
                    s == bp.startUs && e == bp.endUs -> "2.1"
                    base.any { it !== bp && it.startUs >= s && it.endUs <= e && it.text?.let { t -> same(t, bp.text ?: "") } == true } ->
                        "2.1 (قطعتان من نفس السطر وُصلتا بالقراءة)"
                    s >= bp.startUs && e <= bp.endUs -> "2.1 (وقت مصحح بالقراءة)"
                    else -> "2.1 (ممدود بالقراءة)"
                }
                out.add(Line(s, e, baseText(r.b)!!, -1, r.b, note))
            } else {
                val inBase = (r.from until r.to).any { baseAt[it] >= 0 && baseText(baseAt[it]) != null }
                val overBlank = !inBase && (r.from until r.to).any { baseAt[it] >= 0 }
                val who = names.getOrElse(r.e) { "?" }
                val note = when {
                    inBase -> "انفصل عن سطر 2.1 — قرأه $who"
                    overBlank -> "صورة 2.1 لم يُقرأ منها سطر — قرأه $who"
                    else -> "فاته 2.1 — قرأه $who"
                }
                out.add(Line(s, e, altText(r.e, r.i), r.e, r.i, note))
            }
        }
        return out
    }

    /**
     * Frames-only mode (nothing is read): the base images plus the evidence pieces that cover time no
     * base piece covers (engine order = preference). Returns (engine, index, startUs, endUs) clipped
     * to the uncovered time.
     */
    fun gapPieces(base: List<Piece>, alts: List<List<Piece>>, frameUs: Long, minFrames: Int): List<LongArray> {
        val g = Grid(base, alts, frameUs)
        val taken = BooleanArray(g.n)
        for (b in base) for (x in g.s(b) until minOf(g.n, g.e(b))) taken[x] = true
        val out = ArrayList<LongArray>()
        for ((e, list) in alts.withIndex()) for ((i, p) in list.withIndex()) {
            var x = g.s(p)
            val end = minOf(g.n, g.e(p))
            while (x < end) {
                while (x < end && taken[x]) x++
                var y = x
                while (y < end && !taken[y]) y++
                if (y - x >= minFrames.coerceAtLeast(1)) {
                    val s = if (x == g.s(p)) p.startUs else (x + g.t0).toLong() * g.f
                    val t = if (y == g.e(p)) p.endUs else (y + g.t0).toLong() * g.f
                    out.add(longArrayOf(e.toLong(), i.toLong(), s, t))
                    for (z in x until y) taken[z] = true
                }
                x = y
            }
        }
        return out.sortedBy { it[2] }
    }
}
