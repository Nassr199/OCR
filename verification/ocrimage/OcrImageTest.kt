import com.abdulrahman.hardsubocr.core.*

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/** white image with black rectangles (x0, y0, x1, y1 inclusive) */
fun img(w: Int, h: Int, vararg r: IntArray): IntArray {
    val a = IntArray(w * h) { -1 }
    for (b in r) for (y in b[1]..b[3]) for (x in b[0]..b[2]) a[y * w + x] = -0x1000000
    return a
}
fun inkBox(g: GrayImage): IntArray {
    var x0 = g.width; var y0 = g.height; var x1 = -1; var y1 = -1
    for (y in 0 until g.height) for (x in 0 until g.width) if ((g.data[y * g.width + x].toInt() and 0xFF) < 128) {
        x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
    }
    return intArrayOf(x0, y0, x1, y1)
}

fun main() {
    check("no letters → null (original picture is read)", OcrImage.fromClean(IntArray(400 * 100) { -1 }, 400, 100) == null)
    // 1080p-like line: 48 px tall letters → same size, cropped, white margin
    val a = OcrImage.fromClean(img(1200, 200, intArrayOf(300, 80, 900, 127), intArrayOf(500, 60, 510, 70)), 1200, 200)!!
    val ba = inkBox(a)
    check("big text: not scaled", ba[2] - ba[0] + 1 == 601 && ba[3] - ba[1] + 1 == 68, ba.toList().toString())
    check("big text: cropped with margin ≥ 12 px on every side", ba[0] >= 12 && ba[1] >= 12 && a.width - 1 - ba[2] >= 12 && a.height - 1 - ba[3] >= 12, "${a.width}x${a.height}")
    // small 720p line: 20 px → ×2
    val b = OcrImage.fromClean(img(800, 100, intArrayOf(100, 40, 500, 59)), 800, 100)!!
    val bb = inkBox(b)
    check("small text ×2", bb[3] - bb[1] + 1 == 40 && bb[2] - bb[0] + 1 == 802, bb.toList().toString())
    // tiny 10 px → ×3
    val c = OcrImage.fromClean(img(400, 60, intArrayOf(50, 20, 150, 29)), 400, 60)!!
    check("tiny text ×3", inkBox(c).let { it[3] - it[1] + 1 } == 30)
    // two lines: line height = one line, not both
    val d = OcrImage.fromClean(img(1000, 200, intArrayOf(200, 40, 800, 69), intArrayOf(250, 100, 750, 129)), 1000, 200)!!
    check("two 30 px lines → ×2 (line height, not block height)", inkBox(d).let { it[3] - it[1] + 1 } == 180, inkBox(d).toList().toString())
    // very wide: fits 1500 px
    val e = OcrImage.fromClean(img(1920, 120, intArrayOf(0, 30, 1919, 59)), 1920, 120)!!
    check("wide line fits Lens 1500 px", e.width <= 1500 && e.height < e.width, "${e.width}x${e.height}")
    val f = OcrImage.fromClean(img(1600, 100, intArrayOf(10, 40, 1590, 49)), 1600, 100)!!
    check("small but very wide: enlarged only as far as 1500 px allows", f.width <= 1500, "${f.width}x${f.height}")
    // dots above the letters belong to the line
    val g = OcrImage.fromClean(img(600, 120, intArrayOf(100, 50, 500, 89), intArrayOf(200, 42, 204, 46)), 600, 120)!!
    check("dots kept, line height includes them", inkBox(g).let { it[1] } < inkBox(g).let { it[3] })
    check("PNG encodes", PngGray.encode(a).size > 50)
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
