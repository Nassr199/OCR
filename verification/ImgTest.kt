import com.abdulrahman.hardsubocr.core.*
import java.io.File
import javax.imageio.ImageIO
fun main() {
    val cases = File("/home/claude/tests/img/cases.json").readText().trim('[', ']').split("],").map { it.replace("[", "").replace("]", "").split(",").map { s -> s.trim().toInt() } }
    for ((i, ew, eh) in cases) {
        val bi = ImageIO.read(File("/home/claude/tests/img/in$i.png"))
        val w = bi.width; val h = bi.height
        val px = bi.getRGB(0, 0, w, h, null, 0, w)
        val t0 = System.nanoTime()
        val out = ImageV26.prepare(px, w, h, ImageV26.Options())
        val ms = (System.nanoTime() - t0) / 1e6
        val exp = File("/home/claude/tests/img/out$i.raw").readBytes()
        var diff = 0; var maxd = 0
        if (out.width == ew && out.height == eh) for (k in exp.indices) { val d = kotlin.math.abs((exp[k].toInt() and 255) - (out.data[k].toInt() and 255)); if (d > 0) diff++; if (d > maxd) maxd = d }
        println("case $i ${w}x$h -> ${out.width}x${out.height} (exp ${ew}x$eh)  pixels differing=$diff maxDiff=$maxd  time=%.1fms".format(ms))
        File("/home/claude/tests/img/k$i.png").writeBytes(PngGray.encode(out))
    }
}
