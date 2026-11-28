import com.abdulrahman.hardsubocr.core.AiConsensus
import com.abdulrahman.hardsubocr.core.CleanMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.ImagePreparer
import com.abdulrahman.hardsubocr.core.OcrCache
import com.abdulrahman.hardsubocr.core.OcrEngine
import com.abdulrahman.hardsubocr.core.OcrError
import com.abdulrahman.hardsubocr.core.OcrItem
import com.abdulrahman.hardsubocr.core.OcrPipeline
import com.abdulrahman.hardsubocr.core.PipelineListener
import com.abdulrahman.hardsubocr.core.PreparedImage
import com.abdulrahman.hardsubocr.core.RetryPolicy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2.9.0 «التحقق المزدوج» (AiConsensus): two pictures read at once, a third when they differ, the
 * shared text or the closest one chosen; in the pipeline with Gemini/OpenRouter only.
 */
private var failed = 0
private fun check(name: String, ok: Boolean, detail: Any? = null) {
    if (!ok) failed++
    println((if (ok) "✓ " else "✖ ") + name + (if (!ok && detail != null) "  [$detail]" else ""))
}

private fun pic(tag: String) = PreparedImage(tag.toByteArray(), "image/png", 10, 10)

/** Answers by picture: "<item>/<view>" → text (or "!" to fail, "~" for a retry once). */
private class ViewEngine(val answers: Map<String, String>, override val kind: EngineKind = EngineKind.GEMINI) : OcrEngine {
    override val cacheId = "views"
    val calls = AtomicInteger()
    val seen = ConcurrentHashMap<String, Int>()
    override suspend fun recognize(image: PreparedImage): String {
        calls.incrementAndGet()
        val tag = String(image.bytes)
        val n = seen.merge(tag, 1, Int::plus)!!
        val a = answers[tag] ?: ""
        if (a == "!") throw OcrError.Failed("refused")
        if (a == "~" && n == 1) throw OcrError.Retryable("busy", retryAfterMs = 5)
        return if (a == "~") answers["$tag+"] ?: "" else a
    }
}

fun main() = runBlocking {
    // ----------------------------------------------------------------- the decision
    var reads = mutableListOf<String>()
    suspend fun run(vararg texts: String): AiConsensus.Outcome {
        reads = mutableListOf()
        val views = texts.indices.map { pic(it.toString()) }
        return AiConsensus.read(views) { p -> String(p.bytes).toInt().let { i -> reads.add("v$i"); texts[i] } }
    }
    run("هوليا هي آخر همي الآن", "هوليا هي آخر همي الآن", "X").let {
        check("same text: accepted after two readings", it.agreed && it.readings == 2 && it.text == "هوليا هي آخر همي الآن" && "v2" !in reads, reads)
    }
    run("هُوليا  هي آخر", "هوليا هي آخر", "X").let {
        check("tashkeel and spacing aside, the same reading", it.agreed && it.text == "هُوليا  هي آخر")
    }
    run("خوليا هي آخر همي الآن", "هوليا هي آخر همي الآن", "هوليا هي آخر همي الآن").let {
        check("«خوليا» against «هوليا»: the third decides «هوليا»", !it.agreed && it.readings == 3 && it.text == "هوليا هي آخر همي الآن", it.text)
    }
    run("هوليا. ساعدوني\nماذا حدث؟", "هوليا، ساعدوني ماذا حدث؟", "هوليا. ساعدوني\nماذا حدث؟").let {
        check("two lines joined into one: the two-line reading wins", it.text == "هوليا. ساعدوني\nماذا حدث؟", it.text)
    }
    run("بخير بخير أجل أنا بخير", "بخير بخير بخير أجل أنا بخير", "بخير بخير أجل أنا بخير").let {
        check("a word written three times: outvoted", it.text == "بخير بخير أجل أنا بخير", it.text)
    }
    run("هوليا، استيقظي", "أهوليام، استيقظي!", "هوليا. استيقظي").let {
        check("three different readings: the closest to the others", it.text == "هوليا، استيقظي", it.text)
    }
    run("", "", "X").let { check("no subtitle in both: empty, two readings", it.agreed && it.text.isEmpty()) }
    check("key keeps lines and punctuation", AiConsensus.key("هوليا. ساعدوني\nماذا") != AiConsensus.key("هوليا، ساعدوني ماذا"))
    check("distance", AiConsensus.distance("هوليا", "خوليا") == 1 && AiConsensus.distance("", "abc") == 3)

    // one picture refused: the other two decide
    val failing = AiConsensus.read(listOf(pic("a"), pic("b"), pic("c"))) { p ->
        when (String(p.bytes)) { "a" -> throw OcrError.Failed("cut"); "b" -> "أريد أمي"; else -> "أريد أمي" }
    }
    check("one reading failed: the two others agree", failing.text == "أريد أمي" && failing.readings == 3)
    val bothFail = runCatching {
        AiConsensus.read(listOf(pic("a"), pic("b"), pic("c"))) { _ -> throw OcrError.Failed("cut") }
    }
    check("all readings failed: the image fails", bothFail.exceptionOrNull() is OcrError.Failed)

    // ----------------------------------------------------------------- in the pipeline
    val prep = object : ImagePreparer {
        override fun prepare(bytes: ByteArray, nameHint: String, kind: EngineKind) = pic(String(bytes) + "/0")
        override fun views(bytes: ByteArray, nameHint: String, kind: EngineKind) = (0..2).map { pic(String(bytes) + "/$it") }
    }
    val answers = mapOf(
        "a/0" to "هوليا هي آخر همي الآن", "a/1" to "هوليا هي آخر همي الآن", "a/2" to "never read",
        "b/0" to "خوليا. استيقظي", "b/1" to "هوليا. استيقظي", "b/2" to "هوليا. استيقظي",
        "c/0" to "~", "c/0+" to "أريد أمي", "c/1" to "أريد أمي"
    )
    val engine = ViewEngine(answers)
    val decided = mutableListOf<String>()
    val cache = object : OcrCache {
        val m = ConcurrentHashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, text: String) { m[key] = text }
    }
    val p = OcrPipeline(
        engine, prep, CleanMode.EXACT, false, 3, RetryPolicy(3, 5, 20), cache,
        listener = object : PipelineListener {
            override fun onCrossCheck(item: OcrItem, texts: List<String>, chosen: String) { synchronized(decided) { decided.add(item.name + "→" + chosen) } }
        },
        crossCheck = true
    )
    val ch = Channel<OcrItem>(Channel.UNLIMITED)
    listOf("a", "b", "c").forEachIndexed { i, n -> ch.send(OcrItem("k$i", n, i * 1000L, i * 1000L + 900) { n.toByteArray() }) }
    ch.close()
    p.run(ch)
    val texts = p.results.sortedBy { it.startMs }.map { it.text }
    check("pipeline: the agreed, the decided and the retried texts", texts == listOf("هوليا هي آخر همي الآن", "هوليا. استيقظي", "أريد أمي"), texts)
    check("pipeline: counts (2 agreed, 1 decided)", p.stats.crossAgreed.get() == 2 && p.stats.crossDecided.get() == 1, "${p.stats.crossAgreed} ${p.stats.crossDecided}")
    check("pipeline: the third picture read only where the two differ", engine.seen["a/2"] == null && engine.seen["b/2"] == 1)
    check("pipeline: listener told of the decision", decided == listOf("b→هوليا. استيقظي"), decided)
    check("pipeline: the resume key marks the cross-check", cache.m.keys.all { it.endsWith("|x") } && cache.m.size == 3, cache.m.keys)

    // classic OCR never cross-checks, even when asked
    val lens = ViewEngine(mapOf("a/0" to "مرحبا"), EngineKind.LENS)
    val p2 = OcrPipeline(lens, prep, CleanMode.EXACT, false, 1, crossCheck = true)
    val ch2 = Channel<OcrItem>(1); ch2.send(OcrItem("k", "a", 0, 900) { "a".toByteArray() }); ch2.close()
    p2.run(ch2)
    check("Lens: one reading of one picture", lens.calls.get() == 1 && p2.results.single().text == "مرحبا")

    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
}
