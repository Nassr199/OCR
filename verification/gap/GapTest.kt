import com.abdulrahman.hardsubocr.core.*
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

fun luma(img: BufferedImage): IntArray {
    val w = img.width; val h = img.height
    return IntArray(w * h) { i -> val c = img.getRGB(i % w, i / w); val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        (16 + (0.299 * r + 0.587 * g + 0.114 * b) * 219 / 255).toInt().coerceIn(0, 255) }
}
fun blend(a: IntArray, b: IntArray, t: Double) = IntArray(a.size) { (a[it] * (1 - t) + b[it] * t).toInt() }

/**
 * v2.6.1 — the «أبي» hole, reproduced with three real frames from the user's video (not included in
 * the repository): the 06.266 piece (credits beside «أبي»), the 06.433 piece (credits fading) and the
 * 06.716 piece (new credits). Usage: GapTestKt <exact|v26|new> <folder with the three frames>
 */
fun main(args: Array<String>) {
    val up = (args.getOrNull(1) ?: "/mnt/user-data/uploads") + "/"
    val fA = ImageIO.read(File(up + "cd355bf1-image.jpg"))   // 06.266 piece: İsmail Durmaz credits full
    val fF = ImageIO.read(File(up + "4ea0d654-image.jpg"))   // 06.433 piece: credits fading
    val fB = ImageIO.read(File(up + "ce77b849-image.jpg"))   // 06.716 piece: Gökhan Yılmaz credits
    val w = fA.width; val h = fA.height
    val A = luma(fA); val F = luma(fF); val B = luma(fB)
    // the scene: A held, A→fading, fading→B (cross-fade), B held
    val seq = ArrayList<IntArray>()
    repeat(8) { seq.add(A) }
    for (k in 1..5) seq.add(blend(A, F, k / 5.0))
    repeat(2) { seq.add(F) }
    for (k in 1..7) seq.add(blend(F, B, k / 7.0))
    repeat(10) { seq.add(B) }
    val frameUs = 33_367L
    val variant = args.getOrElse(0) { "cut" }
    val p0 = ScanParams.preset(SubLang.ARABIC, composite = false, minDurationMs = 30).legacy21()
    val p = when (variant) { "exact" -> p0.copy(farCut = false, carryText = false); "v26" -> p0.copy(carryText = false); else -> p0 }
    val tr = SubtitleTracker(p)
    val segs = ArrayList<String>()
    for ((f, l) in seq.withIndex()) {
        val g = LumaGrid(w, h, ByteArray(w * h) { l[it].toByte() })
        val d = tr.push(f * frameUs, g)
        for (s in d.closed) segs.add("frames ${s.startUs / frameUs}-${s.endUs / frameUs}")
        val m = tr.lastMask
        if (System.getenv("V") != null) println("f$f mask=${m?.count}")
    }
    for (s in tr.finish(seq.size * frameUs).closed) segs.add("frames ${s.startUs / frameUs}-${s.endUs / frameUs}")
    println("$variant: ${seq.size} frames (A 0-7, A→fade 8-12, fade 13-14, fade→B 15-21, B 22-31) → ${segs.joinToString("  ")}")
}
