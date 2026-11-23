import com.abdulrahman.hardsubocr.core.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/** args: path x0 y0 x1 y1 (subtitle area, simulating the subtitle's own mask) */
fun main(args: Array<String>) {
    val img = ImageIO.read(File(args[0]))
    val w = img.width; val h = img.height
    val argb = IntArray(w * h); img.getRGB(0, 0, w, h, argb, 0, w)
    val luma = ByteArray(w * h)
    for (i in argb.indices) { val c = argb[i]; luma[i] = ((299 * ((c shr 16) and 0xFF) + 587 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) / 1000).toByte() }
    val p = ScanParams()
    val m = ScanMask.compute(LumaGrid(w, h, luma), p)
    val (ax0, ay0, ax1, ay1) = args.drop(1).map { it.toInt() }
    val bits = BooleanArray(w * h) { i -> m.bits[i] && i % w in ax0..ax1 && i / w in ay0..ay1 }
    val mask = TextMask(w, h, bits, bits.count { it })
    val clean = FrameComposite.clean(argb, w, h, SubStyle.LIGHT, p, mask, 1)
    val g = OcrImage.fromClean(clean, w, h)!!
    val out = BufferedImage(g.width, g.height, BufferedImage.TYPE_BYTE_GRAY)
    out.raster.setDataElements(0, 0, g.width, g.height, g.data)
    val o = File("/tmp/real/ocr_" + File(args[0]).nameWithoutExtension + ".png")
    ImageIO.write(out, "png", o)
    File(o.path + ".bin").writeBytes(PngGray.encode(g))
    println("$o ${g.width}x${g.height} png=${PngGray.encode(g).size} bytes")
}
