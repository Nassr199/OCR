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

        /** --ocr-single-line equivalent. */
        val singleLine: String get() = lines.joinToString(" ") { it.text }.trim()

        /**
         * [fullText] without a second reading of the same place: a line lying on a fuller line with
         * the same letters (outlined subtitles can be read twice) is dropped. Lens's own order and
         * line text are kept; without boxes on every line it is [fullText] unchanged.
         */
        val dedupedText: String
            get() {
                val ls = lines
                if (ls.size < 2) return fullText
                val words = ls.mapIndexed { i, l ->
                    val b = l.box ?: return fullText
                    if (b.w <= 0f || b.h <= 0f) return fullText
                    // normalised centre box → integer box (same scale for every line)
                    VisionLayout.Word(
                        l.text,
                        ((b.cx - b.w / 2) * 10_000).toInt(), ((b.cy - b.h / 2) * 10_000).toInt(),
                        ((b.cx + b.w / 2) * 10_000).toInt(), ((b.cy + b.h / 2) * 10_000).toInt(),
                        par = i
                    )
                }
                val kept = VisionLayout.dedupe(words).toHashSet()
                if (kept.size == words.size) return fullText
                return words.filter { it in kept }.joinToString("\n") { it.text }.trim()
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
