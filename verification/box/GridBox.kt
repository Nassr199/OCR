import com.abdulrahman.hardsubocr.core.*
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

fun main(args: Array<String>) {
    if (args[0] == "probe") { probeMain(args.drop(1)); return }
    if (args[0] == "rim") { rimMain(); return }
    val prefix = "/home/claude/vt/" + args[0]
    val j = File("$prefix.json").readText()
    val gw = Regex("\"gw\": *(\\d+)").find(j)!!.groupValues[1].toInt()
    val gh = Regex("\"gh\": *(\\d+)").find(j)!!.groupValues[1].toInt()
    SubBox.debug = { println("   · $it") }
    for (fs in args.drop(1)) {
        val f = fs.toInt()
        val b = ByteArray(gw * gh)
        RandomAccessFile("$prefix.grid", "r").use { it.seek(f.toLong() * gw * gh); it.readFully(b) }
        val g = LumaGrid(gw, gh, b)
        val m = ScanMask.compute(g, ScanParams.preset(SubLang.ARABIC))
        val found = SubBox.find(g, m, 16)
        println("${args[0]} frame $f black=${found.black} thr=${found.threshold} boxes=${found.boxes} mask=${m.count}")
        val out = BufferedImage(gw, gh, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until gw * gh) { val v = b[i].toInt() and 255; out.setRGB(i % gw, i / gw, if (m.bits[i]) 0xFF0000 else v * 0x010101) }
        for (bx in found.boxes) for (x in bx.x0 until bx.x1) { out.setRGB(x, bx.y0, 0x00FF00); out.setRGB(x, bx.y1 - 1, 0x00FF00) }
        ImageIO.write(out, "png", File("/tmp/box/${args[0]}_$f.png"))
    }
}
