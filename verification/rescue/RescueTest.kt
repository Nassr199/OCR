import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

var fails = 0
fun check(name: String, ok: Boolean, detail: String = "") { println((if (ok) "✓ " else "✖ ") + name + (if (detail.isNotEmpty()) "  [$detail]" else "")); if (!ok) fails++ }

/** First image → `first[name]`, enhanced image (mime "image/x-enh") → `second[name]`. */
class FakeEngine(override val kind: EngineKind, val first: Map<String, String>, val second: Map<String, String>, val failSecond: Boolean = false) : OcrEngine {
    override val cacheId = "fake"
    val calls = ArrayList<String>()
    override suspend fun recognize(image: PreparedImage): String {
        val name = String(image.bytes)
        val enh = image.mime == "image/x-enh"
        calls.add((if (enh) "2:" else "1:") + name)
        if (enh && failSecond) throw OcrError.Failed("bad image")
        return (if (enh) second else first)[name] ?: ""
    }
}

class MapCache : OcrCache { val m = HashMap<String, String>(); override fun get(key: String) = m[key]; override fun put(key: String, text: String) { m[key] = text } }

val plain = ImagePreparer { b, _, _ -> PreparedImage(b, "image/png", 1, 1) }
val enh = ImagePreparer { b, _, _ -> PreparedImage(b, "image/x-enh", 1, 1) }

fun run(engine: FakeEngine, names: List<String>, cache: OcrCache? = null, rescue: ImagePreparer? = enh): OcrPipeline = runBlocking {
    val p = OcrPipeline(engine, plain, CleanMode.PRO, false, 2, RetryPolicy(0), cache, rescuePreparer = rescue)
    val ch = Channel<OcrItem>(Channel.UNLIMITED)
    names.forEachIndexed { i, n -> ch.send(OcrItem("k:$n", n, i * 1000L, i * 1000L + 900) { n.toByteArray() }) }
    ch.close()
    p.run(ch)
    p
}

fun main() {
    val first = mapOf("a" to "مرحبا", "b" to "", "c" to "Netflix")
    val second = mapOf("a" to "خطأ", "b" to "ماذا حدث؟", "c" to "شكرا")
    // 1) Lens: empty and cleaned-to-empty images get a second read; good reads are untouched
    val e1 = FakeEngine(EngineKind.LENS, first, second)
    val p1 = run(e1, listOf("a", "b", "c"))
    val texts = p1.results.sortedBy { it.startMs }.map { it.text }
    check("good read untouched", texts[0] == "مرحبا", texts.toString())
    check("empty read rescued", texts.getOrNull(1) == "ماذا حدث؟", texts.toString())
    check("cleaned-to-empty rescued", texts.getOrNull(2) == "شكرا", texts.toString())
    check("no second read of a good image", "2:a" !in e1.calls, e1.calls.toString())
    check("stats", p1.stats.rescueTried.get() == 2 && p1.stats.rescued.get() == 2 && p1.stats.ok.get() == 3 && p1.stats.empty.get() == 0)
    // 2) Vision too; Gemini never
    val e2 = FakeEngine(EngineKind.VISION, first, second); run(e2, listOf("b"))
    check("Vision rescue", "2:b" in e2.calls)
    val e3 = FakeEngine(EngineKind.GEMINI, first, second); val p3 = run(e3, listOf("b"))
    check("AI engines never", e3.calls == listOf("1:b") && p3.stats.empty.get() == 1, e3.calls.toString())
    // 3) off
    val e4 = FakeEngine(EngineKind.LENS, first, second); run(e4, listOf("b"), rescue = null)
    check("off → one read", e4.calls == listOf("1:b"))
    // 4) rescue fails → still Empty (not Failed), tried again next time
    val c5 = MapCache()
    val e5 = FakeEngine(EngineKind.LENS, first, second, failSecond = true); val p5 = run(e5, listOf("b"), c5)
    check("failed second read keeps 'no subtitle'", p5.stats.empty.get() == 1 && p5.stats.failed.get() == 0)
    check("…and no marker", c5.m.keys.none { it.endsWith("|second-read") })
    // 5) resume: empty cached without marker → only the second read; with marker → nothing
    val e6 = FakeEngine(EngineKind.LENS, first, second); val p6 = run(e6, listOf("b"), c5)
    check("old cached empty → second read only", e6.calls == listOf("2:b") && p6.results.single().text == "ماذا حدث؟", e6.calls.toString())
    val e7 = FakeEngine(EngineKind.LENS, first, second); val p7 = run(e7, listOf("b"), c5)
    check("rescued result from cache, no reads", e7.calls.isEmpty() && p7.results.single().text == "ماذا حدث؟" && p7.stats.cached.get() == 1)
    val c8 = MapCache()
    val e8 = FakeEngine(EngineKind.LENS, mapOf("z" to ""), mapOf("z" to "")); run(e8, listOf("z"), c8)
    val e9 = FakeEngine(EngineKind.LENS, mapOf("z" to ""), mapOf("z" to "")); val p9 = run(e9, listOf("z"), c8)
    check("truly empty: read twice once, then cached", e8.calls.size == 2 && e9.calls.isEmpty() && p9.stats.empty.get() == 1)
    // 6) cached good text never re-read
    val c10 = MapCache(); run(FakeEngine(EngineKind.LENS, first, second), listOf("a"), c10)
    val e10 = FakeEngine(EngineKind.LENS, first, second); run(e10, listOf("a"), c10)
    check("cached text never re-read", e10.calls.isEmpty())

    // Lens: same place read twice
    fun line(t: String, cx: Float, cy: Float, w: Float, h: Float) = LensProtocol.Line(t, LensProtocol.Box(cx, cy, w, h, 0f))
    val dup = LensProtocol.Result(listOf(
        LensProtocol.Paragraph(listOf(line("بخير أجل أنا بخير", 0.45f, 0.8f, 0.4f, 0.06f)), null),
        LensProtocol.Paragraph(listOf(line("بخير بخير أجل أنا بخير", 0.5f, 0.8f, 0.5f, 0.06f)), null)
    ), "ar", null)
    check("Lens partial copy dropped", dup.dedupedText == "بخير بخير أجل أنا بخير", dup.dedupedText)
    val two = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("أين كنت؟", 0.5f, 0.75f, 0.3f, 0.06f), line("كنت أبحث عنك", 0.5f, 0.82f, 0.4f, 0.06f)), null)), "ar", null)
    check("Lens two lines unchanged", two.dedupedText == two.fullText && two.fullText == "أين كنت؟\nكنت أبحث عنك")
    val same = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("لا", 0.5f, 0.75f, 0.05f, 0.06f), line("لا", 0.5f, 0.815f, 0.05f, 0.06f)), null)), "ar", null)
    check("Lens same text on two rows kept", same.dedupedText == "لا\nلا", same.dedupedText)
    val nobox = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(LensProtocol.Line("أ", null), LensProtocol.Line("أ", null)), null)), "ar", null)
    check("Lens without boxes unchanged", nobox.dedupedText == nobox.fullText)
    val credits = LensProtocol.Result(listOf(LensProtocol.Paragraph(listOf(
        line("Netflix", 0.1f, 0.8f, 0.12f, 0.05f), line("حبيبي، هل أنت بخير؟", 0.55f, 0.8f, 0.45f, 0.06f)), null)), "ar", null)
    check("Lens lines side by side kept", credits.dedupedText == credits.fullText)
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
}
