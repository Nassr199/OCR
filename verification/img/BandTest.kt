import com.abdulrahman.hardsubocr.core.*
import java.io.File
import javax.imageio.ImageIO

// 2.7.8: the subtitle band (image enhancement for Gemini/OpenRouter) never cuts a line of the subtitle.
var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

fun band(f: File): Triple<IntArray, IntArray, Int> {
    val bi = ImageIO.read(f)
    val w = bi.width; val h = bi.height
    val px = bi.getRGB(0, 0, w, h, null, 0, w)
    val left = (w * 0.06).toInt(); val right = (w * 0.94).toInt()
    val top0 = (h * 0.35).toInt(); val bottom0 = (h * 0.96).toInt()
    return Triple(intArrayOf(top0, bottom0), ImageV26.textSafeBand(px, w, h, left, right, top0, bottom0), h)
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val truth = File(dir, "truth.json").readText()
    // the box of each case (bench/make_frames.py): its text must be inside the band
    val boxes = Regex("\"file\": \"([^\"]+)\"[^}]*?\"box\": \\[\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\s*\\]").findAll(truth)
        .associate { it.groupValues[1] to it.groupValues.drop(2).map { v -> v.toInt() } }
    for (f in dir.listFiles()!!.filter { it.name.endsWith(".jpg") }.sortedBy { it.name }) {
        val (v26, safe, h) = band(f)
        val box = boxes[f.name]
        val changed = !v26.contentEquals(safe)
        val note = "v2.6 ${v26.joinToString("–")} → ${safe.joinToString("–")}" + (box?.let { " box y ${it[1]}–${it[3]}" } ?: "")
        if (box != null && box[1] > 0) {
            // the subtitle's own rows: white letter pixels inside its box
            val bi = ImageIO.read(f)
            var t = -1; var bt = -1
            for (y in box[1] until box[3]) for (x in maxOf(box[0], 74) until minOf(box[2], bi.width - 74)) {
                val c = bi.getRGB(x, y); val l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                if (l > 200) { if (t < 0) t = y; bt = y; break }
            }
            check("${f.name}: band holds every row of the subtitle ($t–$bt)" + (if (changed) " — widened" else ""), safe[0] <= t && safe[1] > bt, note)
            if (t >= v26[0]) check("${f.name}: …and is v2.6's own band when v2.6 cut nothing", !changed, note)
        } else println("  ${f.name}: $note" + if (changed) " (widened: outlined text above v2.6's cut)" else "")
    }
    // a whole video frame: subtitle low, a sign high in the scene → v2.6's band unchanged
    val w = 1280; val h = 720
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = java.awt.Color(70, 90, 110); g.fillRect(0, 0, w, h)
    g.font = java.awt.Font("DejaVu Sans", java.awt.Font.BOLD, 40)
    g.color = java.awt.Color.WHITE; g.drawString("HOTEL PARADISE", 400, 90)      // a sign high in the scene
    g.color = java.awt.Color.BLACK; g.fillRect(380, 590, 520, 70)
    g.color = java.awt.Color.WHITE; g.drawString("Subtitle line here", 420, 640)
    g.dispose()
    val px = img.getRGB(0, 0, w, h, null, 0, w)
    val b = ImageV26.textSafeBand(px, w, h, (w * 0.06).toInt(), (w * 0.94).toInt(), (h * 0.35).toInt(), (h * 0.96).toInt())
    check("whole frame: subtitle low, sign high → v2.6's band as is", b[0] == (h * 0.35).toInt() && b[1] == (h * 0.96).toInt(), b.joinToString("–"))
    // the same frame, the subtitle's first line crossing the 35 % line
    val img2 = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g2 = img2.createGraphics()
    g2.color = java.awt.Color(70, 90, 110); g2.fillRect(0, 0, w, h)
    g2.font = java.awt.Font("DejaVu Sans", java.awt.Font.BOLD, 40)
    g2.color = java.awt.Color.WHITE; g2.drawString("First line of it", 420, 268); g2.drawString("Second line of it", 420, 325)
    g2.dispose()
    val px2 = img2.getRGB(0, 0, w, h, null, 0, w)
    val b2 = ImageV26.textSafeBand(px2, w, h, (w * 0.06).toInt(), (w * 0.94).toInt(), (h * 0.35).toInt(), (h * 0.96).toInt())
    check("whole frame: a line crossing the 35 % cut is kept whole", b2[0] <= 268 - 40, b2.joinToString("–"))
    // Options(): v2.6 exactly (the Pillow comparison); keepText only when asked
    val out1 = ImageV26.prepare(px2, w, h, ImageV26.Options())
    val out2 = ImageV26.prepare(px2, w, h, ImageV26.Options(keepText = true))
    check("default options = v2.6's band; keepText widens it", out1.height < out2.height, "${out1.width}x${out1.height} vs ${out2.width}x${out2.height}")
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
