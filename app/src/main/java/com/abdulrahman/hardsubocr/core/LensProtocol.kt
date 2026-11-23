package com.abdulrahman.hardsubocr.core

import kotlin.random.Random

/**
 * Native implementation of the Google Lens "crupload" protocol used by Chromium's
 * Lens Overlay. This is the same protocol chrome-lens-py implements and that the
 * Windows build reached through Chrome-Lens-OCR (chrome-lens.exe) — here it is a
 * direct HTTPS call, with no external executable, 7-Zip or Python runtime.
 *
 * Field numbers follow Chromium's third_party/lens_server_proto.
 */
object LensProtocol {

    const val ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
    const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val MAX_DIMENSION = 1500

    private const val PLATFORM_WEB = 3L
    private const val SURFACE_CHROMIUM = 4L
    private const val FILTER_AUTO = 7L
    private const val RENDERING_ENV_LENS_OVERLAY = 14L

    fun buildRequest(
        image: ByteArray,
        width: Int,
        height: Int,
        language: String = "ar",
        region: String = "US",
        timeZone: String = "America/New_York",
        uuid: Long = Random.nextLong(0, Long.MAX_VALUE),
        timeUsec: Long = System.currentTimeMillis() * 1000L
    ): ByteArray = ProtoWriter().message(1) {                 // LensOverlayServerRequest.objects_request
        message(1) {                                          // LensOverlayObjectsRequest.request_context
            message(3) {                                      // LensOverlayRequestContext.request_id
                varint(1, uuid)                               //   uuid
                int32(2, 1)                                   //   sequence_id
                int32(3, 1)                                   //   image_sequence_id
                varint(7, timeUsec)                           //   time_usec
            }
            message(4) {                                      // client_context
                varint(1, PLATFORM_WEB)                       //   platform
                varint(2, SURFACE_CHROMIUM)                   //   surface
                message(4) {                                  //   locale_context
                    string(1, language)
                    string(2, region)
                    string(3, timeZone)
                }
                message(17) {                                 //   client_filters (AppliedFilters)
                    message(1) { varint(1, FILTER_AUTO) }     //     filter { filter_type: AUTO_FILTER }
                }
                message(20) {                                 //   rendering_context
                    varint(2, RENDERING_ENV_LENS_OVERLAY)
                }
            }
        }
        message(3) {                                          // LensOverlayObjectsRequest.image_data
            message(1) { bytes(1, image) }                    //   payload.image_bytes
            message(3) {                                      //   image_metadata
                int32(1, width)
                int32(2, height)
            }
        }
    }.toByteArray()

    data class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float)
    data class Line(val text: String, val box: Box?)
    data class Paragraph(val lines: List<Line>, val box: Box?)
    data class Result(val paragraphs: List<Paragraph>, val language: String, val serverError: Int?) {
        val lines: List<Line> get() = paragraphs.flatMap { it.lines }

        /** Same assembly as chrome-lens-py: lines joined by '\n', paragraphs by '\n'. */
        val fullText: String
            get() = paragraphs.joinToString("\n") { p -> p.lines.joinToString("\n") { it.text } }.trim()

        /** Diagnostic file: every line with its box (centre x, y, width, height). */
        fun describe(): String = paragraphs.withIndex().joinToString(" ") { (pi, p) ->
            p.lines.joinToString(" ") { l ->
                val b = l.box
                if (b == null) "${l.text}[p$pi]"
                else "${l.text}[" + listOf(b.cx, b.cy, b.w, b.h).joinToString(",") { "%.3f".format(java.util.Locale.US, it) } + " p$pi]"
            }
        }.take(1500)

        /** Lines [readingText] drops as fragments of the background (see [withoutStrays]). */
        fun strays(width: Int, height: Int): Int = lines.size - withoutStrays(lines, width, height).size

        /**
         * Pixel boxes (x0, y0, x1, y1) of the Arabic lines kept by [readingText] — where the subtitle
         * is, for a closer second look. Empty when Lens found no Arabic or gave no boxes.
         */
        fun arabicBoxes(width: Int, height: Int): List<IntArray> = withoutStrays(lines, width, height).mapNotNull { l ->
            val b = l.box ?: return@mapNotNull null
            if (b.w <= 0f || b.h <= 0f || ClassicViews.arabicLetters(l.text) < 2) return@mapNotNull null
            intArrayOf(
                Math.round((b.cx - b.w / 2) * width), Math.round((b.cy - b.h / 2) * height),
                Math.round((b.cx + b.w / 2) * width), Math.round((b.cy + b.h / 2) * height)
            )
        }

        /** --ocr-single-line equivalent. */
        val singleLine: String get() = lines.joinToString(" ") { it.text }.trim()

        /**
         * [fullText] in reading order, without a second reading of the same place:
         * - a line lying on a fuller line with the same letters (text read twice) is dropped;
         * - lines are put in rows by their height on the image, top to bottom, and the pieces of one
         *   row are joined right to left when Arabic. Lens can hand the chunks of a two-line
         *   subtitle out interleaved between the rows (Subtitle Edit fixes the same, #12149).
         * Line text itself is Lens's own. Without a box on every line it is [fullText] unchanged.
         *
         * Lens boxes are fractions of the image width (x) and height (y): they are turned into pixels
         * of the [width] × [height] picture first, so distances mean the same across and down (before
         * 2.7.6 a 1230×201 crop counted a horizontal gap six times too small, and crew names on the
         * subtitle's row were glued to it).
         */
        fun readingText(width: Int, height: Int): String {
                val ls = withoutStrays(lines, width, height)
                if (ls.size < 2) return ls.joinToString("\n") { it.text }.trim()
                val sx = width.coerceAtLeast(1).toFloat()
                val sy = height.coerceAtLeast(1).toFloat()
                val words = ls.mapIndexed { i, l ->
                    val b = l.box ?: return ls.joinToString("\n") { it.text }.trim()
                    if (b.w <= 0f || b.h <= 0f) return ls.joinToString("\n") { it.text }.trim()
                    // centre box → pixel box
                    VisionLayout.Word(
                        l.text,
                        Math.round((b.cx - b.w / 2) * sx), Math.round((b.cy - b.h / 2) * sy),
                        Math.round((b.cx + b.w / 2) * sx), Math.round((b.cy + b.h / 2) * sy),
                        par = i
                    )
                }
                val kept = VisionLayout.dedupe(words).toHashSet()
                // rows: a line belongs to the row it overlaps by half of the smaller height (a dialogue
                // dash, a few pixels high, falls inside its line's row)
                val rows = ArrayList<MutableList<VisionLayout.Word>>()
                val spans = ArrayList<IntArray>()
                for (w in words.filter { it in kept }.sortedBy { it.cy }) {
                    var best = -1
                    var bestOv = 0.0
                    for ((k, sp) in spans.withIndex()) {
                        val ov = (minOf(sp[1], w.y1) - maxOf(sp[0], w.y0)).toDouble() / minOf(sp[1] - sp[0], w.h).coerceAtLeast(1)
                        if (ov > bestOv) { bestOv = ov; best = k }
                    }
                    if (best >= 0 && bestOv >= 0.5) {
                        rows[best].add(w)
                        spans[best][0] = minOf(spans[best][0], w.y0); spans[best][1] = maxOf(spans[best][1], w.y1)
                    } else {
                        rows.add(mutableListOf(w)); spans.add(intArrayOf(w.y0, w.y1))
                    }
                }
                val order = rows.indices.sortedBy { spans[it][0] }
                val out = ArrayList<String>()
                for (r in order.map { rows[it] }) {
                    val text = r.joinToString("") { it.text }
                    val ar = text.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
                    val lat = text.count { it.isLetter() } - ar
                    val rtl = ar > 0 && ar * 10 >= (ar + lat) * 3
                    // chunks close together are one line; a far one (crew names beside the subtitle) stays
                    // apart, and so does a chunk in another script (a Latin name touching an Arabic line)
                    val pieces = ArrayList<MutableList<VisionLayout.Word>>()
                    var right = Int.MIN_VALUE
                    for (w in r.sortedBy { it.x0 }) {
                        val last = pieces.lastOrNull()
                        if (last != null && w.x0 - right <= maxOf(w.h, last.last().h) * 2.5 &&
                            sameScript(w.text, last.last().text)
                        ) {
                            last.add(w); right = maxOf(right, w.x1)
                        } else {
                            pieces.add(mutableListOf(w)); right = w.x1
                        }
                    }
                    for (p in if (rtl) pieces.reversed() else pieces) {
                        // each piece in its own direction (an Arabic piece on a row of long crew names)
                        out.add((if (script(p.joinToString("") { it.text }) == 1) p.reversed() else p).joinToString(" ") { it.text })
                    }
                }
                return out.joinToString("\n").trim()
            }
    }

    /**
     * Lens reads the parts of crew names hidden behind or cut by the subtitle box as lone letters
     * («- لماذا؟ و», «ا أبي»): with a real Arabic line (2+ letters) in the picture, a line that is a
     * single Arabic letter is a background fragment and is dropped, and so is a line of marks only
     * («-», «'») — unless it sits beside an Arabic line, on its row: that is the line's own dialogue
     * dash or punctuation («- هوليا», «لا تذهب !»). Arabic punctuation alone (؟ ، ؛) is kept.
     * [width]/[height]: the picture in pixels (Lens boxes are fractions of each side).
     */
    fun withoutStrays(lines: List<Line>, width: Int = 1, height: Int = 1): List<Line> {
        val arabic = lines.filter { ClassicViews.arabicLetters(it.text) >= 2 }
        if (arabic.isEmpty()) return lines
        return lines.filter { l ->
            val t = l.text
            val ar = ClassicViews.arabicLetters(t)
            val letters = t.count { it.isLetter() }
            when {
                ar == 1 && letters == 1 -> false
                letters == 0 && t.none { it == '؟' || it == '،' || it == '؛' } -> besideArabic(l, arabic, width, height)
                else -> true
            }
        }
    }

    /**
     * [mark] is on an Arabic line's row (centre within its height) and next to it, not over it: on
     * its right, where an Arabic line starts (dialogue dash, opening quote), or on its left only when
     * it is closing punctuation («!», «…»). A dash on the left of an Arabic line is a piece of a crew
     * name beside the subtitle box («E» of «Ece» read as «-»), not the line's dash.
     */
    private fun besideArabic(mark: Line, arabic: List<Line>, width: Int, height: Int): Boolean {
        val m = mark.box ?: return false
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val closing = mark.text.trim().let { t -> t.isNotEmpty() && t.all { it in "!?.…؟،؛:»\")" } }
        for (a in arabic) {
            val b = a.box ?: continue
            if (m.cx < b.cx && !closing) continue
            val ah = b.h * h
            if (Math.abs(m.cy - b.cy) * h > ah * 0.4f) continue
            val gap = maxOf((m.cx - m.w / 2) - (b.cx + b.w / 2), (b.cx - b.w / 2) - (m.cx + m.w / 2)) * w
            if (gap >= -ah * 0.2f && gap <= ah * 1.2f) return true
        }
        return false
    }

    private fun sameScript(a: String, b: String): Boolean {
        val x = script(a); val y = script(b)
        return x == y || x == 0 || y == 0
    }

    /** 1 = mostly Arabic, 2 = mostly Latin, 0 = neither (digits, punctuation). */
    private fun script(t: String): Int {
        val ar = t.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
        val lat = t.count { it.isLetter() } - ar
        return when {
            ar == 0 && lat == 0 -> 0
            ar >= lat -> 1
            else -> 2
        }
    }

    private fun parseBox(geometry: ProtoField?): Box? {
        val bb = geometry?.asMessage()?.firstOf(1)?.asMessage() ?: return null
        fun f(n: Int) = bb.firstOf(n)?.takeIf { it.wire == 5 }?.asFloat() ?: 0f
        return Box(f(1), f(2), f(3), f(4), f(5))
    }

    fun parseResponse(data: ByteArray): Result {
        val root = ProtoReader.parse(data)
        val error = root.firstOf(1)?.asMessage()?.firstOf(1)?.asInt()
        val objects = root.firstOf(2)?.asMessage() ?: return Result(emptyList(), "", error)
        val text = objects.firstOf(3)?.asMessage() ?: return Result(emptyList(), "", error)
        val language = text.firstOf(2)?.asString() ?: ""
        val layout = text.firstOf(1)?.asMessage() ?: return Result(emptyList(), language, error)
        val paragraphs = layout.allOf(1).map { pf ->
            val p = pf.asMessage()
            val lines = p.allOf(2).map { lf ->
                val l = lf.asMessage()
                val sb = StringBuilder()
                for (wf in l.allOf(1)) {
                    val w = wf.asMessage()
                    sb.append(w.firstOf(2)?.asString() ?: "")
                    sb.append(w.firstOf(3)?.asString() ?: "")
                }
                Line(sb.toString().trim(), parseBox(l.firstOf(2)))
            }.filter { it.text.isNotEmpty() }
            Paragraph(lines, parseBox(p.firstOf(3)))
        }.filter { it.lines.isNotEmpty() }
        return Result(paragraphs, language, error)
    }
}
