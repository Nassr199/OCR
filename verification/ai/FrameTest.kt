import com.abdulrahman.hardsubocr.core.SubtitleFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO

/**
 * 2.9.0: the AI engines' picture framed on the subtitle ([SubtitleFrame]). On every bench frame
 * (bench/images, with the drawn letters' box in truth.json "text"), the frame keeps every letter of
 * the subtitle — dashes, dots, the short first line of a dialogue — and cuts the scene around it.
 * Usage: FrameTestKt <bench/images>
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "/home/claude/ocr/bench/images" })
    val cases = Json.parseToJsonElement(File(dir, "truth.json").readText()).jsonArray
    var ok = 0; var bad = 0; var framed = 0
    var kept = 0.0
    for (c in cases) {
        val o = c.jsonObject
        val file = o["file"]!!.jsonPrimitive.content
        val t = o["text"]!!.jsonArray.map { it.jsonPrimitive.int }
        val img = ImageIO.read(File(dir, file))
        val w = img.width; val h = img.height
        val box = SubtitleFrame.find(img.getRGB(0, 0, w, h, null, 0, w), w, h)
        val (l, tp, r, b) = if (box == null) listOf(0, 0, w, h) else listOf(box.left, box.top, box.right, box.bottom)
        // every letter inside, with room around it (at least 4 px)
        val inside = t[0] >= l + minOf(4, t[0]) && t[1] >= tp + minOf(4, t[1]) && t[2] <= r - minOf(4, w - t[2]) && t[3] <= b - minOf(4, h - t[3])
        val share = (r - l).toDouble() * (b - tp) / (w.toDouble() * h)
        if (box != null) { framed++; kept += share }
        if (inside) ok++ else bad++
        println((if (inside) "✓ " else "✖ ") + "$file ${w}×$h text $t → " + (box?.toString() ?: "whole picture") + " (%.0f%% of the picture)".format(share * 100))
    }
    println("framed $framed/${cases.size}, mean kept %.0f%% · every letter inside: $ok/${cases.size}".format(100 * kept / maxOf(1, framed)) + if (bad > 0) " ✖" else " ✓")
}
