package com.abdulrahman.hardsubocr.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Rebuilds the text of a Google Cloud Vision answer from the position of every word, instead of
 * taking Vision's ready-made text.
 *
 * Vision's own text has two faults with Arabic subtitles (seen on real frames):
 * - the same words read twice (the letters and their outline / shadow, as two blocks):
 *   «بخير بخير أجل أنا بخير» came back as «بخير أجل أنا بخير بخير بخير أجل أنا بخير»;
 * - blocks of one line laid out left to right: «حبيبي، هل أنت بخير؟» came back «هل أنت بخير؟ حبيبي،».
 *
 * So: a second reading of the same place is dropped (a whole paragraph, or single words), the rest
 * is grouped into lines by height, each line is read right to left when it is Arabic, and blocks far
 * apart on one row become separate lines (credits beside the subtitle are then removed by the
 * Arabic line filter, as with Lens). Nothing is added: every output word is a word Vision read.
 */
object VisionLayout {

    class Word(
        val text: String,
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val conf: Float = 0f,
        /** Paragraph of the answer the word came from (-1: none known). */
        val par: Int = -1
    ) {
        val w get() = (x1 - x0).coerceAtLeast(1)
        val h get() = (y1 - y0).coerceAtLeast(1)
        val area get() = w.toLong() * h
        val cx get() = (x0 + x1) / 2.0
        val cy get() = (y0 + y1) / 2.0
        val letters: Int by lazy { text.count { it.isLetterOrDigit() } }
        val punct get() = letters == 0
        override fun toString() = "$text[$x0,$y0,$x1,$y1]"
    }

    // ------------------------------------------------------------------ reading the answer

    private fun JsonElement?.o() = this as? JsonObject
    private fun JsonElement?.a() = this as? JsonArray
    private fun JsonElement?.s() = (this as? JsonPrimitive)?.content
    private fun JsonElement?.i() = (this as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
    private fun JsonElement?.f() = (this as? JsonPrimitive)?.content?.toFloatOrNull()

    /** Box from {vertices: [{x, y}…]} — Vision leaves out a coordinate that is 0. */
    private fun box(poly: JsonElement?): IntArray? {
        val vs = poly.o()?.get("vertices").a() ?: return null
        if (vs.isEmpty()) return null
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (v in vs) {
            val x = v.o()?.get("x").i() ?: 0
            val y = v.o()?.get("y").i() ?: 0
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
        }
        return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
    }

    /** Every word with its box: from fullTextAnnotation, else from textAnnotations[1…]. */
    fun wordsOf(response: JsonObject): List<Word> {
        val out = ArrayList<Word>()
        var parId = 0
        response["fullTextAnnotation"].o()?.get("pages").a()?.forEach { page ->
            page.o()?.get("blocks").a()?.forEach { block ->
                block.o()?.get("paragraphs").a()?.forEach { par ->
                    val id = parId++
                    par.o()?.get("words").a()?.forEach { wd ->
                        val w = wd.o() ?: return@forEach
                        val text = w["symbols"].a()?.joinToString("") { it.o()?.get("text").s() ?: "" }.orEmpty().trim()
                        val b = box(w["boundingBox"])
                        if (text.isNotEmpty() && b != null) out.add(Word(text, b[0], b[1], b[2], b[3], w["confidence"].f() ?: 0f, id))
                    }
                }
            }
        }
        if (out.isNotEmpty()) return out
        response["textAnnotations"].a()?.drop(1)?.forEach { t ->
            val o = t.o() ?: return@forEach
            val text = o["description"].s().orEmpty().trim()
            val b = box(o["boundingPoly"])
            if (text.isNotEmpty() && b != null) out.add(Word(text, b[0], b[1], b[2], b[3]))
        }
        return out
    }

    // ------------------------------------------------------------------ geometry

    private fun inter(ax0: Int, ay0: Int, ax1: Int, ay1: Int, bx0: Int, by0: Int, bx1: Int, by1: Int): Long {
        val w = minOf(ax1, bx1) - maxOf(ax0, bx0)
        val h = minOf(ay1, by1) - maxOf(ay0, by0)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h
    }

    private fun inter(a: Word, b: Word) = inter(a.x0, a.y0, a.x1, a.y1, b.x0, b.y0, b.x1, b.y1)

    /** Letters (and digits) of [a] also found in [b], counted with repeats — order free. */
    private fun shared(a: String, b: String): Int {
        val m = HashMap<Char, Int>()
        for (c in b) if (c.isLetterOrDigit()) m[c] = (m[c] ?: 0) + 1
        var n = 0
        for (c in a) if (c.isLetterOrDigit()) {
            val k = m[c] ?: 0
            if (k > 0) { n++; m[c] = k - 1 }
        }
        return n
    }

    /** [b] is a second reading of the place of [a] (outline, shadow, overlapping block). */
    private fun sameSpot(a: Word, b: Word): Boolean {
        val i = inter(a, b)
        if (i == 0L) return false
        if (a.punct || b.punct) {
            // a mark is a copy only of the same mark, or of a word that already carries it
            val p = if (a.punct) a else b
            val o = if (a.punct) b else a
            if (o.punct) return i.toDouble() / (a.area + b.area - i) >= 0.3
            return i.toDouble() / p.area >= 0.6 && p.text.all { it in o.text }
        }
        if (i.toDouble() / (a.area + b.area - i) >= 0.4) return true
        // a short word read twice a few pixels apart: the boxes overlap little but sit on one spot
        val hMin = minOf(a.h, b.h)
        if (Math.abs(a.cx - b.cx) <= hMin * 0.35 && Math.abs(a.cy - b.cy) <= hMin * 0.35 &&
            shared(a.text, b.text) >= maxOf(a.letters, b.letters) * 0.8
        ) return true
        val small = if (a.area <= b.area) a else b
        val big = if (small === a) b else a
        return i.toDouble() / small.area >= 0.75 && shared(small.text, big.text) >= small.letters * 0.6
    }

    /** [w] lies on [o] (a reading of the same letters there), whatever the word split. */
    private fun onTop(w: Word, o: Word): Boolean {
        val i = inter(w, o)
        if (i == 0L) return false
        if (w.punct) return i.toDouble() / w.area >= 0.5 && w.text.all { it in o.text }
        if (o.punct) return false
        return (i.toDouble() / (w.area + o.area - i) >= 0.3 || i.toDouble() / minOf(w.area, o.area) >= 0.6) &&
            shared(w.text, o.text) >= minOf(w.letters, o.letters) * 0.8
    }

    /**
     * Drops second readings, keeping the better one:
     * 0) of paragraphs that contradict each other on the same place, the consistent set reading most;
     * 1) a paragraph whose words lie on the words of a fuller paragraph (a partial copy), or a weaker
     *    paragraph read with the same words;
     * 2) a word whose box holds two or more words with its letters — the same text read without the
     *    space: the separate words stay when they are spaced like words, else the joined one stays;
     * 3) of words on the same spot, the best one (higher confidence, more letters, larger).
     */
    fun dedupe(input: List<Word>): List<Word> {
        // 0) of contradicting readings of the same place, one consistent set of paragraphs
        var words = keepConsistentReadings(input)
        // 1) paragraphs
        val pars = words.filter { it.par >= 0 }.groupBy { it.par }
        if (pars.size > 1) {
            class P(val id: Int, val ws: List<Word>) {
                val letters = ws.sumOf { it.letters }
                val conf = ws.map { it.conf }.average()
            }
            val ps = pars.map { (id, ws) -> P(id, ws) }
                .sortedWith(compareByDescending<P> { it.letters }.thenByDescending { it.conf })
            val kept = ArrayList<P>()
            val dropped = HashSet<Int>()
            for (p in ps) {
                // every word of it lies on a word of a fuller reading (or of the same reading with the
                // same words), so nothing is lost; other overlaps are decided word by word
                val copy = p.letters > 0 && kept.any { q ->
                    (p.letters < q.letters || p.ws.size == q.ws.size) && p.conf <= q.conf + 0.2 &&
                        p.ws.all { w -> q.ws.any { o -> onTop(w, o) } }
                }
                if (copy) dropped.add(p.id) else kept.add(p)
            }
            if (dropped.isNotEmpty()) words = words.filter { it.par !in dropped }
        }
        // 2) joined readings
        val drop = HashSet<Word>()
        for (big in words) {
            if (big.punct || big in drop) continue
            val parts = words.filter { s ->
                s !== big && !s.punct && s.area < big.area * 0.9 && inter(big, s).toDouble() / s.area >= 0.8
            }
            if (parts.size < 2 || shared(parts.joinToString("") { it.text }, big.text) < big.letters * 0.7) continue
            val sorted = parts.sortedBy { it.x0 }
            val hMed = sorted.map { it.h }.sorted()[sorted.size / 2]
            val spaced = sorted.zipWithNext().all { (l, r) -> r.x0 - l.x1 >= hMed * 0.1 }
            if (spaced) drop.add(big) else drop.addAll(parts)
        }
        if (drop.isNotEmpty()) words = words.filter { it !in drop }
        // 3) single words
        val kept = ArrayList<Word>()
        val order = words.sortedWith(
            compareBy<Word> { it.punct }.thenByDescending { it.conf }.thenByDescending { it.letters }.thenByDescending { it.area }
        )
        for (w in order) {
            val k = kept.indexOfFirst { sameSpot(it, w) }
            if (k < 0) { kept.add(w); continue }
            // a partial reading kept first (higher confidence) gives way to the full one:
            // «بيبي» read inside «حبيبي،» must not replace it
            val o = kept[k]
            if (fuller(w, o) && kept.withIndex().none { (j, x) -> j != k && sameSpot(x, w) }) kept[k] = w
        }
        return kept
    }

    private fun iou(a: Word, b: Word): Double {
        val i = inter(a, b)
        return if (i == 0L) 0.0 else i.toDouble() / (a.area + b.area - i)
    }

    /** Same letters (one reading contains the other): a copy, joined or split — not a contradiction. */
    private fun subset(a: Word, b: Word) =
        shared(a.text, b.text) >= a.letters * 0.9 || shared(b.text, a.text) >= b.letters * 0.9

    /**
     * Two paragraphs that put DIFFERENT words on the same place are two contradicting readings
     * of it (one of them with its words laid out wrongly); taking words from both makes a line in
     * the wrong order. Of each group of overlapping paragraphs, the combination without
     * contradictions that reads the most letters (each place counted once) is kept, better
     * confidence first; copies with the same letters stay and are decided word by word.
     */
    private fun keepConsistentReadings(words: List<Word>): List<Word> {
        val byPar = words.filter { it.par >= 0 }.groupBy { it.par }
        if (byPar.size < 2) return words
        val ids = byPar.keys.toList()
        val n = ids.size
        val conflict = Array(n) { BooleanArray(n) }
        val dupLetters = Array(n) { DoubleArray(n) }
        val touch = Array(n) { BooleanArray(n) }
        var anyConflict = false
        for (i in 0 until n) for (j in i + 1 until n) {
            var c = false
            var dup = 0.0
            for (a in byPar.getValue(ids[i])) for (b in byPar.getValue(ids[j])) {
                if (a.punct || b.punct) continue
                val i0 = inter(a, b)
                if (i0 == 0L) continue
                val o = i0.toDouble() / (a.area + b.area - i0)
                if (subset(a, b)) {
                    // the same word on (nearly) the same place: counted once
                    if (o >= 0.3 || i0.toDouble() / minOf(a.area, b.area) >= 0.5) {
                        touch[i][j] = true; touch[j][i] = true
                        dup += minOf(a.letters, b.letters) * (0.6 + minOf(a.conf, b.conf))
                    }
                } else if (o >= 0.3) {
                    // different words on the same place
                    touch[i][j] = true; touch[j][i] = true
                    c = true
                }
            }
            conflict[i][j] = c; conflict[j][i] = c
            dupLetters[i][j] = dup; dupLetters[j][i] = dup
            if (c) anyConflict = true
        }
        if (!anyConflict) return words
        val weight = DoubleArray(n) { k -> byPar.getValue(ids[k]).sumOf { it.letters * (0.6 + it.conf) } }
        // groups of touching paragraphs
        val group = IntArray(n) { -1 }
        var groups = 0
        for (k in 0 until n) if (group[k] < 0) {
            val stack = ArrayDeque<Int>().apply { add(k) }
            group[k] = groups
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                for (y in 0 until n) if (touch[x][y] && group[y] < 0) { group[y] = groups; stack.add(y) }
            }
            groups++
        }
        val drop = HashSet<Int>()
        for (g in 0 until groups) {
            val members = (0 until n).filter { group[it] == g }
            if (members.size < 2 || members.size > 12) continue
            if (members.none { a -> members.any { b -> conflict[a][b] } }) continue
            var best = -1.0
            var bestMask = 0
            for (mask in 1 until (1 shl members.size)) {
                val chosen = members.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
                if (chosen.any { a -> chosen.any { b -> conflict[a][b] } }) continue
                var score = chosen.sumOf { weight[it] }
                for (x in chosen.indices) for (y in x + 1 until chosen.size) score -= dupLetters[chosen[x]][chosen[y]]
                if (score > best + 1e-9) { best = score; bestMask = mask }
            }
            members.forEachIndexed { i, m -> if ((bestMask shr i) and 1 == 0) drop.add(ids[m]) }
        }
        return if (drop.isEmpty()) words else words.filter { it.par !in drop }
    }

    /** [w] reads all of [o] and more, on the same place, with confidence not much lower. */
    private fun fuller(w: Word, o: Word): Boolean =
        !w.punct && w.letters > o.letters && shared(o.text, w.text) >= o.letters * 0.9 &&
            inter(w, o).toDouble() / o.area >= 0.8 && w.conf >= o.conf - 0.2f

    /** Compact list of the words for the diagnostic file: text[x0,y0,x1,y1 confidence paragraph]. */
    fun describe(words: List<Word>, limit: Int = 60): String =
        words.take(limit).joinToString(" ") { "${it.text}[${it.x0},${it.y0},${it.x1},${it.y1} ${"%.2f".format(java.util.Locale.US, it.conf)} p${it.par}]" } +
            if (words.size > limit) " …(${words.size})" else ""

    private fun isArabicChar(c: Char) =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    private fun hasArabic(s: String) = s.any { isArabicChar(it) }
    private fun hasLatin(s: String) = s.any { it.isLetter() && !isArabicChar(it) }

    private fun arabicLine(ws: List<Word>): Boolean {
        var ar = 0; var lat = 0
        for (w in ws) for (c in w.text) if (isArabicChar(c)) ar++ else if (c.isLetter()) lat++
        // an Arabic subtitle with a Latin name in it is still an Arabic line
        return ar > 0 && ar * 10 >= (ar + lat) * 3
    }

    /**
     * Visual order → reading order. Arabic line: right to left, except that a run of Latin words
     * inside it is still read left to right. Latin line: left to right, Arabic runs right to left.
     */
    private fun readingOrder(piece: List<Word>, rtl: Boolean): List<Word> {
        val v = if (rtl) piece.sortedByDescending { it.cx } else piece.sortedBy { it.cx }
        val out = ArrayList<Word>(v.size)
        var i = 0
        while (i < v.size) {
            val foreign: (Word) -> Boolean =
                if (rtl) { w -> !hasArabic(w.text) && (hasLatin(w.text) || w.text.any { it.isDigit() }) }
                else { w -> hasArabic(w.text) }
            if (!foreign(v[i])) { out.add(v[i]); i++; continue }
            var j = i
            while (j < v.size && (foreign(v[j]) || (v[j].punct && j + 1 < v.size && foreign(v[j + 1])))) j++
            val run = v.subList(i, j)
            // digits alone keep the line's direction; a run with letters of the other script flips
            val flip = if (rtl) run.any { hasLatin(it.text) } else true
            out.addAll(if (flip && run.size > 1) run.reversed() else run)
            i = j
        }
        return out
    }

    /** Words → rows (by vertical overlap) → pieces split at wide gaps; each piece is one output line. */
    fun lines(words: List<Word>): List<List<Word>> {
        val rows = ArrayList<MutableList<Word>>()
        val spans = ArrayList<IntArray>() // y0, y1 of each row
        for (w in words.sortedBy { it.cy }) {
            var best = -1
            var bestOv = 0.0
            for ((k, s) in spans.withIndex()) {
                val ov = (minOf(s[1], w.y1) - maxOf(s[0], w.y0)).toDouble() / minOf(s[1] - s[0], w.h).coerceAtLeast(1)
                if (ov > bestOv) { bestOv = ov; best = k }
            }
            if (best >= 0 && bestOv >= 0.5) {
                rows[best].add(w)
                spans[best][0] = minOf(spans[best][0], w.y0); spans[best][1] = maxOf(spans[best][1], w.y1)
            } else {
                rows.add(mutableListOf(w)); spans.add(intArrayOf(w.y0, w.y1))
            }
        }
        val out = ArrayList<List<Word>>()
        for (k in rows.indices.sortedBy { spans[it][0] }) {
            val row = rows[k].sortedBy { it.x0 }
            val letterH = row.filter { !it.punct }.ifEmpty { row }.map { it.h }.sorted()
            val hMed = letterH[letterH.size / 2]
            // a gap much wider than a word space: a separate block on the same row
            val pieces = ArrayList<MutableList<Word>>()
            var right = Int.MIN_VALUE
            for (w in row) {
                val last = pieces.lastOrNull()
                if (last != null && w.x0 - right <= hMed * 2.5) {
                    last.add(w); right = maxOf(right, w.x1)
                } else {
                    pieces.add(mutableListOf(w)); right = w.x1
                }
            }
            val rtl = arabicLine(row)
            for (p in if (rtl) pieces.reversed() else pieces) out.add(readingOrder(p, rtl))
        }
        return out
    }

    private const val CLOSING = ".,،؛;:!?؟…»)]}"
    private const val OPENING = "«([{"

    private fun join(line: List<Word>): String {
        val sb = StringBuilder()
        var glueNext = false
        for (w in line) {
            val closing = w.punct && w.text.all { it in CLOSING }
            if (sb.isNotEmpty() && !closing && !glueNext) sb.append(' ')
            sb.append(w.text)
            glueNext = w.punct && w.text.all { it in OPENING }
        }
        return sb.toString()
    }

    /**
     * A lone alef or lam at the edge of a line is the stroke of a Latin letter beside the subtitle
     * ("l" of a crew name), never an Arabic word; with hamza or madda only when read unsure.
     */
    private fun stray(w: Word): Boolean {
        val t = w.text.trim()
        if (t.length != 1) return false
        return t[0] == 'ا' || t[0] == 'ل' || (t[0] in "أإآ" && w.conf > 0f && w.conf < 0.9f)
    }

    private fun dropStrays(line: List<Word>): List<Word> {
        if (line.count { it.letters >= 2 } == 0) return line
        var a = 0
        var b = line.size
        while (a < b && stray(line[a])) a++
        while (b > a && stray(line[b - 1])) b--
        return line.subList(a, b)
    }

    /** Text from the word boxes; null when the answer has no usable boxes. */
    fun rebuild(words: List<Word>): String? {
        if (words.isEmpty()) return null
        return lines(dedupe(words)).map { dropStrays(it) }.filter { it.isNotEmpty() }
            .joinToString("\n") { join(it) }.trim().ifEmpty { null }
    }

    // ------------------------------------------------------------------ focused second look

    /**
     * The subtitle's words (core) and Arabic "words" that are only noise: an unsure word right
     * next to other text on its row — the stroke of a crew name read as Arabic letters.
     */
    private fun subtitleWords(ws: List<Word>): Pair<List<Word>, List<Word>> {
        val ar = ws.filter { hasArabic(it.text) && !stray(it) }
        val other = ws.filter { (!hasArabic(it.text) && !it.punct) || stray(it) }
        val noise = ar.filter { w ->
            w.conf > 0f && w.conf < 0.6f && other.any { o ->
                minOf(o.y1, w.y1) - maxOf(o.y0, w.y0) > 0 && maxOf(o.x0 - w.x1, w.x0 - o.x1) < w.h * 1.5
            }
        }
        return ar.filter { it !in noise } to noise
    }

    /**
     * Where to look again — only when the first answer itself shows the subtitle was read together
     * with other text on its rows (crew names beside or touching the box), or unsure. The area is
     * the subtitle's words with a small margin, never reaching into that other text: the same
     * picture, cropped, nothing else changed. null = the first answer stands (no second request).
     */
    fun focusArea(words: List<Word>, imgW: Int, imgH: Int): IntArray? {
        val ws = dedupe(words)
        val (core, noise) = subtitleWords(ws)
        if (core.isEmpty()) return null
        val ax0 = core.minOf { it.x0 }; val ax1 = core.maxOf { it.x1 }
        val ay0 = core.minOf { it.y0 }; val ay1 = core.maxOf { it.y1 }
        val h = core.map { it.h }.sorted()[core.size / 2]
        val others = ws.filter { it !in core && !it.punct }
        val beside = others.any { o -> minOf(o.y1, ay1) - maxOf(o.y0, ay0) > 0 }
        val sure = core.filter { it.conf > 0f }
        val unsure = sure.isNotEmpty() && (sure.any { it.conf < 0.5f } ||
            sure.sumOf { it.conf.toDouble() * it.letters } / sure.sumOf { it.letters } < 0.8)
        if (!beside && !unsure && noise.isEmpty()) return null
        var x0 = ax0 - (0.35 * h).toInt()
        var x1 = ax1 + (0.35 * h).toInt()
        // other text on the subtitle's rows: the area stops before it (never inside the subtitle)
        for (o in others) {
            if (minOf(o.y1, ay1) - maxOf(o.y0, ay0) <= 0) continue
            if (o.cx < ax0) x0 = maxOf(x0, minOf(o.x1 + 2, ax0))
            else if (o.cx > ax1) x1 = minOf(x1, maxOf(o.x0 - 2, ax1))
        }
        var y0 = ay0 - (0.25 * h).toInt()
        var y1 = ay1 + (0.25 * h).toInt()
        // other text above or below inside that width (a crew title over the box)
        for (o in others) {
            if (minOf(o.x1, x1) - maxOf(o.x0, x0) <= 0) continue
            if (o.cy < ay0 && o.y1 <= ay0 + h / 4) y0 = maxOf(y0, minOf(o.y1 + 2, ay0))
            else if (o.cy > ay1 && o.y0 >= ay1 - h / 4) y1 = minOf(y1, maxOf(o.y0 - 2, ay1))
        }
        x0 = x0.coerceAtLeast(0); y0 = y0.coerceAtLeast(0)
        x1 = x1.coerceAtMost(imgW); y1 = y1.coerceAtMost(imgH)
        if (x1 - x0 < 8 || y1 - y0 < 8) return null
        // nearly the whole picture: looking again would see the same
        if ((x1 - x0).toLong() * (y1 - y0) >= imgW.toLong() * imgH * 0.85) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /**
     * Is the focused look the better reading of the subtitle? It must not lose a quarter of it;
     * it wins when it reads more of it, when it is the same subtitle without the noise of the
     * first look, or when Vision is clearly surer of it.
     */
    fun secondIsBetter(first: List<Word>, second: List<Word>): Boolean {
        val (core1, noise1) = subtitleWords(dedupe(first))
        val core2 = dedupe(second).filter { hasArabic(it.text) && !stray(it) }
        val l1 = core1.sumOf { it.letters }
        val l2 = core2.sumOf { it.letters }
        if (l2 == 0) return false
        if (l1 == 0) return true
        if (l2 < l1 * 0.75) return false
        fun conf(ws: List<Word>): Double {
            val c = ws.filter { it.conf > 0f }
            return if (c.isEmpty()) 0.0 else c.sumOf { it.conf.toDouble() * it.letters } / c.sumOf { it.letters }
        }
        val c1 = conf(core1)
        val c2 = conf(core2)
        if (l2 > l1 && c2 >= c1 - 0.05) return true
        if (noise1.isNotEmpty() && l2 >= l1 && c2 >= c1 - 0.05) return true
        if (c1 > 0 || c2 > 0) return c2 > c1 + 0.02
        return l2 > l1
    }
}
