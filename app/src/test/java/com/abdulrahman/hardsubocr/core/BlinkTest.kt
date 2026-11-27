package com.abdulrahman.hardsubocr.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.0.3 — a one-word line («أخي») over a fast scene came out as many short pieces with holes between
 * them: in the player it blinked. The user's video is a 25 fps film inside a 60 fps file, and a car's
 * number plate and chrome (text-like, and bigger than the word) drive away behind the line; the
 * detector takes each step of the car for a change.
 *
 * The fix is not a merge by time: the detector proves, per piece, that the text on screen never left
 * since the piece before (ScanSegment.continuesId), and the reading confirms the same text
 * (Arbiter.joinUnbroken). Without that proof nothing is joined, whatever the gap.
 *
 * The scene here is drawn, not taken from any video: a car with a plate, chrome and a grille drives
 * away over grass and asphalt while the camera follows; the subtitle band becomes the app's luma grid;
 * the three detectors, the reading arbiter (reading simulated: a piece reads the line that covers its
 * frames) and «بدون دمج» (no merge at all) run as in the app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BlinkTest {

    private fun piece(name: String, text: String, continues: Boolean): Arbiter.Piece {
        val (a, b) = Timestamps.parseVsfName(name)!!
        return Arbiter.Piece(a * 1000, (b + 1) * 1000, text, continues)
    }

    private val names = listOf(
        "0_04_06_850__0_04_06_915", "0_04_06_933__0_04_06_999", "0_04_07_050__0_04_07_115",
        "0_04_07_133__0_04_07_199", "0_04_07_250__0_04_07_315", "0_04_07_333__0_04_07_399",
        "0_04_07_450__0_04_07_515", "0_04_07_533__0_04_07_599", "0_04_07_650__0_04_07_715"
    ).map { it + "_0000006701875041019201080.jpeg" }

    /** The user's nine pieces of «أخي», each proven to continue the one before: one line, from the first to the last. */
    @Test
    fun the_users_nine_pieces_are_one_line_with_the_proof() {
        val pieces = names.mapIndexed { i, n -> piece(n, "أخي", i > 0) }
        val j = Arbiter.joinUnbroken(pieces)
        assertEquals(1, j.size)
        assertEquals(246_850_000L, j[0].startUs)
        assertEquals(247_716_000L, j[0].endUs)
        assertEquals(9, j[0].joined)
        val lines = Arbiter.resolve(pieces, listOf(emptyList(), emptyList(), emptyList()), 16_683, 3)
        assertEquals(1, lines.size)
        assertEquals(246_850_000L, lines[0].startUs)
        assertEquals(247_716_000L, lines[0].endUs)
    }

    /** No proof, no join — whatever the gap; a proof never joins different texts, or a piece that read nothing. */
    @Test
    fun nothing_is_joined_without_the_proof_and_the_same_text() {
        assertEquals(9, Arbiter.joinUnbroken(names.map { piece(it, "أخي", false) }).size)
        val other = listOf(piece(names[0], "أخي", false), piece(names[1], "أختي", true))
        assertEquals(2, Arbiter.joinUnbroken(other).size)
        val plate = listOf(piece("0_04_06_733__0_04_06_799_x.jpeg", "", false), piece(names[0], "أخي", true))
        assertEquals(2, Arbiter.joinUnbroken(plate).size)
        val marks = listOf(piece(names[0], "أخيَ", false), piece(names[1], "أخي", true))
        assertEquals(1, Arbiter.joinUnbroken(marks).size) // the same line read with and without a mark
    }

    @Test
    fun one_word_over_a_car_driving_away_is_one_line() {
        val word = "أخي"
        val cases = listOf(
            Scene.render(1.6, 59.94, 25.0, listOf(Scene.Line(24, 76, word))) to "film 25 fps in a 60 fps file",
            Scene.render(1.6, 59.94, 0.0, listOf(Scene.Line(24, 76, word))) to "true 60 fps",
            Scene.render(1.3, 59.94, 25.0, listOf(Scene.Line(18, 70, word)), cutAt = 18) to "a cut into the scene with the line"
        )
        for ((v, what) in cases) {
            val before = Modes.run(v, proof = false)
            val r = Modes.run(v, proof = true)
            println("$what: 3.0.2 → ${before.best.size} line(s), now → " + r.best.joinToString { "${it.from}-${it.to} ${it.text}" } +
                " (سريع: ${before.fast.size} → ${r.fast.size})")
            assertEquals("$what: one line", 1, r.best.size)
            val l = r.best[0]
            val t = v.lines[0]
            assertEquals(word, l.text)
            assertTrue("$what: start ${l.from} vs ${t.from}", kotlin.math.abs(l.from - t.from) <= 6)
            assertTrue("$what: end ${l.to} vs ${t.to}", kotlin.math.abs(l.to - t.to) <= 3)
            assertTrue("$what: the fast mode never gets worse", r.fast.size <= before.fast.size || before.fast.isEmpty())
        }
        // the test reproduces the fault: a 25 fps film in a 60 fps file blinked in 3.0.2
        assertTrue(Modes.run(cases[0].first, proof = false).best.size > 1)
    }

    /** A new line that looks like the last one shifted («لا» → «لا لا») is still a new line. */
    @Test
    fun a_new_line_after_a_like_one_stays_a_new_line() {
        val v = Scene.render(2.0, 59.94, 25.0, listOf(Scene.Line(20, 60, "لا"), Scene.Line(60, 104, "لا لا")))
        val r = Modes.run(v, proof = true)
        println("لا → لا لا: " + r.best.joinToString { "${it.from}-${it.to} ${it.text}" })
        assertEquals(listOf("لا", "لا لا"), r.best.map { it.text })
        assertTrue(kotlin.math.abs(r.best[1].from - 60) <= 3 && kotlin.math.abs(r.best[1].to - 104) <= 3)
    }

    /** The scene: what the app's subtitle band looks like, as luma grids (drawn with Skia, as on a phone). */
    object Scene {
        const val W = 1280
        const val H = 300
        private const val STEP = 2

        class Line(val from: Int, val to: Int, val text: String)
        class Video(val fps: Double, val grids: List<ByteArray>, val gw: Int, val gh: Int, val lines: List<Line>)

        private val ground: Bitmap by lazy {
            val gw = 2200
            val gh = 2400
            val px = IntArray(gw * gh)
            val r = java.util.Random(7)
            for (y in 0 until gh step 4) for (x in 0 until gw step 4) {
                val n = r.nextInt(40)
                val c = when {
                    x < 700 -> Color.rgb(60 + n, 95 + n, 45 + n / 2)
                    x < 1300 -> Color.rgb(70 + n, 66 + n, 60 + n)
                    else -> Color.rgb(95 + n / 2, 95 + n / 2, 98 + n / 2)
                }
                for (yy in y until y + 4) java.util.Arrays.fill(px, yy * gw + x, yy * gw + x + 4, c)
            }
            val b = Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(900) { // pebbles and leaves
                val x = r.nextInt(gw).toFloat()
                val y = r.nextInt(gh).toFloat()
                p.color = Color.rgb(170 + r.nextInt(60), 165 + r.nextInt(60), 150 + r.nextInt(60))
                cv.drawOval(RectF(x, y, x + 3 + r.nextInt(5), y + 2 + r.nextInt(4)), p)
            }
            p.color = Color.rgb(150, 150, 145); cv.drawRect(0f, 1500f, gw.toFloat(), 1510f, p) // the kerb
            b
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun rect(c: Canvas, l: Int, t: Int, w: Int, h: Int, color: Int) {
            fill.style = Paint.Style.FILL; fill.color = color
            c.drawRect(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat(), fill)
        }

        private fun drawCar(c: Canvas, cx: Float, cy: Float, s: Float) {
            c.save()
            c.translate(cx, cy); c.scale(s, s)
            fill.style = Paint.Style.FILL; fill.color = Color.rgb(38, 42, 46)
            c.drawRoundRect(RectF(-430f, -260f, 430f, 70f), 20f, 20f, fill)
            rect(c, -400, -40, 800, 120, Color.rgb(28, 30, 33))
            for (k in -6..6) rect(c, k * 40 - 3, -250, 6, 150, Color.rgb(205, 205, 210)) // grille: thin bright bars
            rect(c, -250, -40, 42, 110, Color.rgb(215, 215, 220)); rect(c, 208, -40, 42, 110, Color.rgb(215, 215, 220)) // chrome
            rect(c, -160, -30, 320, 66, Color.rgb(235, 236, 238)) // number plate
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 3f; fill.color = Color.rgb(30, 30, 35)
            c.drawRect(-160f, -30f, 160f, 36f, fill)
            rect(c, -158, -28, 22, 62, Color.rgb(30, 70, 190))
            fill.style = Paint.Style.FILL; fill.color = Color.rgb(20, 20, 22)
            fill.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); fill.textSize = 44f
            c.drawText("34 SNU 3510", -128f, 22f, fill)
            rect(c, -270, 92, 540, 14, Color.rgb(175, 176, 180)) // silver bar
            fill.color = Color.rgb(22, 22, 24)
            c.drawRoundRect(RectF(-430f, 40f, -320f, 240f), 15f, 15f, fill); c.drawRoundRect(RectF(320f, 40f, 430f, 240f), 15f, 15f, fill)
            c.restore()
        }

        private fun drawSubtitle(c: Canvas, text: String) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.textSize = 62f
            val b = Rect()
            p.getTextBounds(text, 0, text.length, b)
            val x = W / 2f - b.exactCenterX()
            p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeJoin = Paint.Join.ROUND; p.color = Color.BLACK
            c.drawText(text, x, 255f, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawText(text, x, 255f, p)
        }

        /**
         * [film] > 0: the picture changes only [film] times a second (25 = a PAL film inside a 60 fps
         * file, each picture shown 2–3 times). [cutAt] >= 0: another, still shot before that frame.
         */
        fun render(seconds: Double, fps: Double, film: Double, lines: List<Line>, cutAt: Int = -1): Video {
            val n = (seconds * fps).toInt()
            val gw = W / STEP
            val gh = H / STEP
            val grids = ArrayList<ByteArray>(n)
            var lastKey = ""
            var last: ByteArray? = null
            val img = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val px = IntArray(W * H)
            for (i in 0 until n) {
                val t = i / fps
                val tp = if (film > 0) Math.floor(t * film + 1e-9) / film else t
                val text = lines.firstOrNull { i >= it.from && i < it.to }?.text
                val before = cutAt >= 0 && i < cutAt
                val key = "$tp|$text|$before"
                val prev = last
                if (key == lastKey && prev != null) {
                    grids.add(prev); continue
                }
                val c = Canvas(img)
                if (before) {
                    val p = Paint()
                    p.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.rgb(70, 60, 55), Color.rgb(35, 32, 30), Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
                    rect(c, 200, 40, 260, 180, Color.rgb(120, 100, 80))
                } else {
                    val tm = tp - maxOf(0, cutAt) / fps
                    val gy = (300 + tm * 260).toInt()
                    c.drawBitmap(ground, Rect(300, gy, 300 + W, gy + H), Rect(0, 0, W, H), null)
                    drawCar(c, (W / 2.0 + 30 * tm).toFloat(), (175.0 - 150 * tm).toFloat(), (0.62 * Math.exp(-0.28 * tm)).toFloat())
                }
                if (text != null) drawSubtitle(c, text)
                img.getPixels(px, 0, W, 0, 0, W, H)
                val grid = ByteArray(gw * gh)
                for (y in 0 until gh) for (x in 0 until gw) {
                    val v = px[y * STEP * W + x * STEP]
                    val yy = 16 + (65.481 * ((v shr 16) and 255) + 128.553 * ((v shr 8) and 255) + 24.966 * (v and 255)) / 255.0
                    grid[y * gw + x] = Math.round(yy).toInt().coerceIn(0, 255).toByte()
                }
                grids.add(grid); last = grid; lastKey = key
            }
            return Video(fps, grids, gw, gh, lines)
        }
    }

    /** «الأدق» and «سريع» as VideoScanner + JobRunner run them, frames in, SRT lines out («بدون دمج»). */
    object Modes {
        class Out(val best: List<Scene.Line>, val fast: List<Scene.Line>)

        fun run(v: Scene.Video, proof: Boolean): Out {
            val fus = (1_000_000.0 / v.fps).toLong()
            val p = ScanParams.preset(SubLang.ARABIC, SubStyle.LIGHT, 150, 60, true, false, 10)
            fun frames(us: Long) = ((us + fus / 2) / fus).toInt()
            /** (start frame, end frame, 1 = proven to continue the piece before) in the order the pieces come */
            fun track(pp: ScanParams): List<IntArray> {
                val tr = SubtitleTracker(pp)
                val out = ArrayList<IntArray>()
                var last = -1
                fun add(c: ScanSegment) {
                    out.add(intArrayOf(frames(c.startUs), frames(c.endUs), if (c.continuesId >= 0 && c.continuesId == last) 1 else 0))
                    last = c.id
                }
                for ((i, b) in v.grids.withIndex()) for (c in tr.pushRaw(i * fus, ScanMask.compute(LumaGrid(v.gw, v.gh, b), pp)).closed) add(c)
                for (c in tr.finish(v.grids.size * fus).closed) add(c)
                return out
            }
            val sg = SubtitleSegmenter(SegmenterParams())
            val c20 = ArrayList<IntArray>()
            for ((i, b) in v.grids.withIndex()) for (c in sg.push(i * fus, LumaGrid(v.gw, v.gh, b)).closed) c20.add(intArrayOf(frames(c.startUs), frames(c.endUs)))
            for (c in sg.finish(v.grids.size * fus).closed) c20.add(intArrayOf(frames(c.startUs), frames(c.endUs)))
            val v22 = track(p.copy(joinUnbroken = false))
            val v21 = track(p.legacy21().copy(joinUnbroken = proof))
            fun read(s: Int, e: Int): String {
                val g = v.lines.maxByOrNull { maxOf(0, minOf(e, it.to) - maxOf(s, it.from)) } ?: return ""
                val o = maxOf(0, minOf(e, g.to) - maxOf(s, g.from))
                return if (o > 0 && o >= minOf(3, (e - s + 1) / 2)) g.text else ""
            }
            val minFrames = maxOf(3, (10_000 / fus).toInt())
            fun piece(s: IntArray, t: String?) = Arbiter.Piece(s[0] * fus, s[1] * fus, t)
            val base = v21.map { Arbiter.Piece(it[0] * fus, it[1] * fus, read(it[0], it[1]), it[2] == 1) }
            val raw = listOf(v22.map { piece(it, null) }, c20.map { piece(it, null) }, v21.map { piece(it, null) })
            val need = Arbiter.needed(base, raw, fus, minFrames)
            val alts = listOf(
                v22.mapIndexed { i, s -> piece(s, if (i in need[0]) read(s[0], s[1]) else null) },
                c20.mapIndexed { i, s -> piece(s, if (i in need[1]) read(s[0], s[1]) else null) },
                v21.mapIndexed { i, s -> piece(s, if (i in need[2]) read(s[0], s[1]) else null) })
            fun srt(l: List<Triple<Long, Long, String>>): List<Scene.Line> {
                // as JobRunner: SubEntry(start ms, end ms - 1), then «بدون دمج»
                val e = l.map { val s = it.first / 1000; SubEntry(s, maxOf(s, it.second / 1000 - 1), it.third) }
                val ms = fus / 1000.0
                return Merge.noMerge(e).map { Scene.Line(Math.round(it.startMs / ms).toInt(), Math.round((it.endMs + 1) / ms).toInt(), it.text) }
            }
            val best = srt(Arbiter.resolve(base, alts, fus, minFrames).map { Triple(it.startUs, it.endUs, it.text) })
            val fast = srt(Arbiter.joinUnbroken(base).map { Triple(it.startUs, it.endUs, it.text ?: "") })
            return Out(best, fast)
        }
    }
}
