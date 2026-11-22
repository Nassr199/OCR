import com.abdulrahman.hardsubocr.core.*
import java.io.File
import java.io.RandomAccessFile
fun rimMain() {
    val prefix = "/home/claude/vt/ar4"; val gw = 640; val gh = 108; val f = 1020
    val b = ByteArray(gw * gh)
    RandomAccessFile("$prefix.grid", "r").use { it.seek(f.toLong() * gw * gh); it.readFully(b) }
    val g = LumaGrid(gw, gh, b)
    val m = ScanMask.compute(g, ScanParams.preset(SubLang.ARABIC))
    for (y in 54..60) println("row $y: " + (236..414).count { m.bits[y * gw + it] } + "/179  luma@300=" + (g.data[y * gw + 300].toInt() and 255))
    for (y in 98..104) println("row $y: " + (236..414).count { m.bits[y * gw + it] } + "/179  luma@300=" + (g.data[y * gw + 300].toInt() and 255))
    for (x in listOf(236, 237, 238, 239, 240, 409, 410, 411, 412, 413)) println("col $x: " + (56..100).count { m.bits[it * gw + x] } + "/45")
}
