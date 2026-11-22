import com.abdulrahman.hardsubocr.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class FakeEngine(val failEvery: Int = 0, val fatalAt: Int = -1) : OcrEngine {
    override val kind = EngineKind.LENS
    override val cacheId = "fake"
    val calls = AtomicInteger(); val inflight = AtomicInteger(); var maxInflight = 0
    override suspend fun recognize(image: PreparedImage): String {
        val n = calls.incrementAndGet()
        val f = inflight.incrementAndGet(); synchronized(this) { if (f > maxInflight) maxInflight = f }
        try {
            delay(20)
            if (n == fatalAt) throw OcrError.Fatal("bad key")
            if (failEvery > 0 && n % failEvery == 0) throw OcrError.Retryable("429", retryAfterMs = 50, rateLimited = true)
            val name = String(image.bytes)
            return if (name.contains("empty")) "HOTEL" else "- مرحبا يا $name\nPOLICE"
        } finally { inflight.decrementAndGet() }
    }
}
class MapCache : OcrCache { val m = ConcurrentHashMap<String, String>()
    override fun get(key: String) = m[key]; override fun put(key: String, text: String) { m[key] = text } }

fun items(n: Int) = (0 until n).map { i ->
    val name = if (i % 5 == 4) "empty$i" else "img$i"
    OcrItem("k$i", name, i * 1000L, i * 1000L + 900, { name.toByteArray() })
}
fun main() = runBlocking {
    val prep = ImagePreparer { b, _, _ -> PreparedImage(b, "image/png", 10, 10) }
    // 1) concurrency + retries + cleaning
    val e1 = FakeEngine(failEvery = 7); val cache = MapCache()
    val p1 = OcrPipeline(e1, prep, CleanMode.PRO, false, 4, RetryPolicy(5, 10, 50), cache)
    val ch = Channel<OcrItem>(Channel.UNLIMITED); items(40).forEach { ch.send(it) }; ch.close()
    val t = System.currentTimeMillis(); p1.run(ch)
    println("1) ok=${p1.stats.ok} empty=${p1.stats.empty} failed=${p1.stats.failed} retries=${p1.stats.retries} maxInflight=${e1.maxInflight} ms=${System.currentTimeMillis()-t}")
    println("   sample=" + p1.results.sortedBy { it.startMs }.first().text)
    // 2) resume from cache: no engine calls
    val e2 = FakeEngine(); val p2 = OcrPipeline(e2, prep, CleanMode.PRO, false, 4, RetryPolicy(5, 10, 50), cache)
    val ch2 = Channel<OcrItem>(Channel.UNLIMITED); items(40).forEach { ch2.send(it) }; ch2.close(); p2.run(ch2)
    println("2) cached=${p2.stats.cached} engineCalls=${e2.calls.get()} ok=${p2.stats.ok}")
    // 3) fatal stops job, partial results kept
    val e3 = FakeEngine(fatalAt = 10); val p3 = OcrPipeline(e3, prep, CleanMode.PRO, false, 3, RetryPolicy(5, 10, 50))
    val ch3 = Channel<OcrItem>(Channel.UNLIMITED); items(100).forEach { ch3.send(it) }; ch3.close()
    try { p3.run(ch3); println("3) BAD no fatal") } catch (e: OcrError.Fatal) { println("3) fatal ok: ${e.message} partial=${p3.results.size} calls=${e3.calls.get()}") }
    // 4) cancellation
    val e4 = FakeEngine(); val p4 = OcrPipeline(e4, prep, CleanMode.PRO, false, 4)
    val ch4 = Channel<OcrItem>(Channel.UNLIMITED); items(1000).forEach { ch4.send(it) }; ch4.close()
    val job = launch { p4.run(ch4) }; delay(120); job.cancelAndJoin()
    println("4) cancelled after ${e4.calls.get()} calls, partial=${p4.results.size}, merged=${p4.mergedResult(MergeMode.PRO).size}")
    // 5) breaker: every call fails permanently
    val e5 = object : OcrEngine { override val kind = EngineKind.LENS; override val cacheId = "x"
        override suspend fun recognize(image: PreparedImage): String = throw OcrError.Failed("400") }
    val p5 = OcrPipeline(e5, prep, CleanMode.PRO, false, 2)
    val ch5 = Channel<OcrItem>(Channel.UNLIMITED); items(50).forEach { ch5.send(it) }; ch5.close()
    try { p5.run(ch5); println("5) BAD") } catch (e: OcrError.Fatal) { println("5) breaker ok: ${e.message}") }
    // 6) merged SRT
    println(Srt.build(p1.mergedResult(MergeMode.PRO).take(2), bom = false))
}
