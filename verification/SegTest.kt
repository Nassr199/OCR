import com.abdulrahman.hardsubocr.core.*
import kotlinx.serialization.json.*
import java.io.File
fun main(args: Array<String>) {
    val meta = Json.parseToJsonElement(File("/home/claude/tests/grids.json").readText()).jsonObject
    val w = meta["w"]!!.jsonPrimitive.int; val h = meta["h"]!!.jsonPrimitive.int; val n = meta["n"]!!.jsonPrimitive.int
    val fps = meta["fps"]!!.jsonPrimitive.int
    val raw = File("/home/claude/tests/grids.bin").readBytes()
    val seg = SubtitleSegmenter(SegmenterParams())
    val frameUs = 1_000_000L / fps
    val captures = HashMap<Int, Int>()
    val out = ArrayList<Triple<Int, Long, Long>>()
    var t0 = System.nanoTime()
    for (i in 0 until n) {
        val g = LumaGrid(w, h, raw.copyOfRange(i * w * h, (i + 1) * w * h))
        val d = seg.push(i * frameUs, g)
        d.captureFor?.let { captures[it] = i }
        d.closed.forEach { out.add(Triple(it.id, it.startUs, it.endUs)) }
        if (i % 40 == 0 || i in 448..472 step 6) println("f=$i count=${seg.lastMask!!.count}")
    }
    seg.finish(n * frameUs).closed.forEach { out.add(Triple(it.id, it.startUs, it.endUs)) }
    val ms = (System.nanoTime() - t0) / 1e6
    println("segments (frame indices):")
    for ((id, s, e) in out) println("  seg#$id  ${s / frameUs} -> ${e / frameUs}   captured@${captures[id]}")
    println("expected: " + meta["subs"])
    println("time per frame: %.3f ms".format(ms / n))
}
