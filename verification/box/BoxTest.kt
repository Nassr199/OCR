import com.abdulrahman.hardsubocr.core.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

fun lumaOf(img: BufferedImage, limited: Boolean): LumaGrid {
    val w = img.width; val h = img.height
    val d = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val c = img.getRGB(x, y)
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        var v = (0.299 * r + 0.587 * g + 0.114 * b)
        if (limited) v = 16 + v * 219 / 255   // like the codec's Y plane
        d[y * w + x] = v.toInt().coerceIn(0, 255).toByte()
    }
    return LumaGrid(w, h, d)
}

fun main(args: Array<String>) {
    if (System.getenv("DBG") != null) SubBox.debug = { println("   · $it") }
    for (path in args) {
        val img = ImageIO.read(File(path))
        for (limited in listOf(false, true)) {
            val g = lumaOf(img, limited)
            val p = ScanParams.preset(SubLang.ARABIC)
            val m = ScanMask.compute(g, p)
            val t0 = System.nanoTime()
            val f = SubBox.find(g, m, (0.045 * 720 * g.width / 1280).toInt())
            val ms = (System.nanoTime() - t0) / 1e6
            val kept = SubBox.keepInside(m, f.boxes)
            println("${File(path).name} limited=$limited black=${f.black} thr=${f.threshold} boxes=${f.boxes} mask ${m.count} -> ${kept.count}  (${"%.1f".format(ms)} ms)")
            if (!limited) {
                val out = BufferedImage(g.width, g.height, BufferedImage.TYPE_INT_RGB)
                for (i in 0 until g.width * g.height) {
                    val v = g.data[i].toInt() and 255
                    out.setRGB(i % g.width, i / g.width, if (kept.bits[i]) 0x00FF00 else if (m.bits[i]) 0xFF0000 else (v / 3) * 0x010101)
                }
                for (b in f.boxes) for (x in b.x0 until b.x1) { out.setRGB(x, b.y0, 0xFFFF00); out.setRGB(x, b.y1 - 1, 0xFFFF00) }
                ImageIO.write(out, "png", File("/tmp/box/" + File(path).nameWithoutExtension + "_box.png"))
            }
        }
    }
}
