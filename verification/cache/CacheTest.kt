import com.abdulrahman.hardsubocr.data.FileOcrCache
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

fun open(f: File): FileOcrCache {
    val c = FileOcrCache::class.java.getDeclaredConstructor(File::class.java)
    c.isAccessible = true
    return c.newInstance(f)
}

fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "cachetest-" + System.nanoTime()).apply { mkdirs() }
    val f = File(dir, "v.jsonl")
    val lens = "lens:ar:US:c6:mv|PRO|false|"
    val gem = "gemini:gemini-3-flash:v26:def|V26|false|"
    var c = open(f)
    c.put(lens + "1000-2000", "أبي"); c.put(lens + "3000-4000", "حبيبي، هل أنت بخير؟"); c.put(gem + "1000-2000", "أبي")
    check("count per engine", c.count(lens) == 2 && c.count(gem) == 1)
    c.close()
    c = open(f)
    check("kept across runs (interrupted job resumes)", c.get(lens + "1000-2000") == "أبي" && c.size == 3)
    val n = c.forget(lens)
    check("finished Lens job: its readings forgotten", n == 2 && c.count(lens) == 0 && c.get(gem + "1000-2000") == "أبي")
    c.put(lens + "5000-6000", "x") // after forget: ignored
    c.close()
    c = open(f)
    check("…on disk too, Gemini's kept, nothing written after", c.count(lens) == 0 && c.get(gem + "1000-2000") == "أبي" && c.size == 1, "size=${c.size}")
    check("forget of an engine with nothing", c.forget("vision:") == 0)
    val n2 = c.forget(gem)
    check("forgetting the last entries deletes the file", n2 == 1 && !f.exists())
    c.close()
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
