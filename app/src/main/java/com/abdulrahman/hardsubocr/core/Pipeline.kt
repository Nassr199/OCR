package com.abdulrahman.hardsubocr.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** How the raw engine text is cleaned. */
enum class CleanMode {
    /** arabic_filter() of the PRO GUI: one line, tashkeel removed, Lens garbage removed. */
    PRO,

    /** clean_ocr_output() of the CLI v2.1: keeps line breaks and diacritics exactly. */
    PRECISE,

    /** v2.6: clean_ocr_output() + conservative_clean_diacritics() (fragmented words, over-marked words). */
    V26,

    /** English / Latin subtitles: Arabic filters would delete every line. */
    ENGLISH
}

object Cleaner {
    fun clean(raw: String, mode: CleanMode, stripDiacritics: Boolean = false): String {
        val t = when (mode) {
            CleanMode.PRO -> ArabicText.arabicFilter(raw)
            CleanMode.PRECISE -> ArabicText.cleanOcrOutput(raw).let { if (ArabicText.isSymbolsOnly(it)) "" else it }
            CleanMode.V26 -> ArabicV26.conservativeCleanDiacritics(ArabicText.cleanOcrOutput(raw))
                .let { if (it.isEmpty() || ArabicText.isSymbolsOnly(it)) "" else it }
            CleanMode.ENGLISH -> EnglishText.clean(raw)
        }
        if (mode == CleanMode.ENGLISH) return t
        if (t.isEmpty()) return ""
        return if (stripDiacritics && mode != CleanMode.PRO) ArabicText.pyStrip(ArabicText.stripDiacritics(t)) else t
    }
}

/** tenacity wait_random_exponential(multiplier=1, min=2, max=20) + a retry ceiling. */
class RetryPolicy(val maxRetries: Int = 6, val minDelayMs: Long = 2_000, val maxDelayMs: Long = 20_000) {
    fun delayFor(attempt: Int, hintMs: Long?): Long {
        if (hintMs != null) return if (hintMs <= 0) 0 else hintMs + Random.nextLong(0, 750)
        val exp = (1000L shl (attempt - 1).coerceIn(0, 20)).coerceIn(minDelayMs, maxDelayMs)
        return if (exp <= minDelayMs) minDelayMs else Random.nextLong(minDelayMs, exp + 1)
    }
}

class OcrItem(
    /** Stable identity used by the resume cache (folder + file + size …). */
    val key: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val load: suspend () -> ByteArray
)

sealed class ItemOutcome {
    class Text(val text: String, val cached: Boolean) : ItemOutcome()
    class Empty(val cached: Boolean) : ItemOutcome()
    class Failed(val message: String) : ItemOutcome()
}

interface PipelineListener {
    fun onItem(item: OcrItem, outcome: ItemOutcome) {}
    fun onRetry(item: OcrItem, attempt: Int, delayMs: Long, reason: String) {}
}

interface OcrCache {
    fun get(key: String): String?
    fun put(key: String, text: String)
}

class PipelineStats {
    val processed = AtomicInteger()
    val ok = AtomicInteger()
    val empty = AtomicInteger()
    val failed = AtomicInteger()
    val cached = AtomicInteger()
    val retries = AtomicInteger()
}

class OcrPipeline(
    private val engine: OcrEngine,
    private val preparer: ImagePreparer,
    private val cleanMode: CleanMode,
    private val stripDiacritics: Boolean,
    concurrency: Int,
    private val retry: RetryPolicy = RetryPolicy(),
    private val cache: OcrCache? = null,
    private val listener: PipelineListener = object : PipelineListener {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** After this many images in a row fail for good, the job stops (service is refusing us). */
    private val breakerThreshold: Int = 12
) {
    private val workers = concurrency.coerceIn(1, 16)
    val results = ConcurrentLinkedQueue<SubEntry>()
    val failures = ConcurrentLinkedQueue<Pair<String, String>>()
    val stats = PipelineStats()

    private val cooldownUntil = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    private fun cacheKey(item: OcrItem) = "${engine.cacheId}|$cleanMode|$stripDiacritics|${item.key}"

    /** Consumes [items] with N parallel workers until the channel is closed. */
    suspend fun run(items: ReceiveChannel<OcrItem>) = coroutineScope {
        repeat(workers) {
            launch(dispatcher) {
                for (item in items) process(item)
            }
        }
    }

    private suspend fun process(item: OcrItem) {
        val ck = cacheKey(item)
        cache?.get(ck)?.let { cachedText ->
            stats.processed.incrementAndGet(); stats.cached.incrementAndGet()
            if (cachedText.isNotEmpty()) {
                results.add(SubEntry(item.startMs, item.endMs, cachedText))
                stats.ok.incrementAndGet()
                listener.onItem(item, ItemOutcome.Text(cachedText, true))
            } else {
                stats.empty.incrementAndGet()
                listener.onItem(item, ItemOutcome.Empty(true))
            }
            return
        }

        val outcome: ItemOutcome = try {
            val prepared = withContext(Dispatchers.IO) { item.load() }.let { bytes ->
                preparer.prepare(bytes, item.name, engine.kind)
            }
            val raw = recognizeWithRetry(item, prepared)
            val text = Cleaner.clean(raw, cleanMode, stripDiacritics)
            cache?.put(ck, text)
            consecutiveFailures.set(0)
            if (text.isNotEmpty()) ItemOutcome.Text(text, false) else ItemOutcome.Empty(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrError.Fatal) {
            throw e
        } catch (e: OcrError) {
            ItemOutcome.Failed(e.message ?: "خطأ")
        } catch (e: OutOfMemoryError) {
            ItemOutcome.Failed("الذاكرة غير كافية لهذه الصورة")
        } catch (e: Throwable) {
            ItemOutcome.Failed(describeError(e))
        }

        stats.processed.incrementAndGet()
        when (outcome) {
            is ItemOutcome.Text -> {
                stats.ok.incrementAndGet()
                results.add(SubEntry(item.startMs, item.endMs, outcome.text))
            }
            is ItemOutcome.Empty -> stats.empty.incrementAndGet()
            is ItemOutcome.Failed -> {
                stats.failed.incrementAndGet()
                failures.add(item.name to outcome.message)
                if (consecutiveFailures.incrementAndGet() >= breakerThreshold) {
                    listener.onItem(item, outcome)
                    throw OcrError.Fatal("فشلت $breakerThreshold صور متتالية — آخر خطأ: ${outcome.message}")
                }
            }
        }
        listener.onItem(item, outcome)
    }

    private suspend fun recognizeWithRetry(item: OcrItem, img: PreparedImage): String {
        var attempt = 0
        while (true) {
            val wait = cooldownUntil.get() - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                return engine.recognize(img)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrError.Retryable) {
                attempt++
                if (attempt > retry.maxRetries) throw OcrError.Failed("${e.message} (بعد ${retry.maxRetries} محاولات)")
                val d = retry.delayFor(attempt, e.retryAfterMs)
                if (e.rateLimited) {
                    val until = System.currentTimeMillis() + d
                    cooldownUntil.updateAndGet { maxOf(it, until) }
                }
                stats.retries.incrementAndGet()
                listener.onRetry(item, attempt, d, e.message ?: "")
                delay(d)
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt > retry.maxRetries) throw OcrError.Failed("انقطاع الشبكة: ${e.message}")
                val d = retry.delayFor(attempt, null)
                stats.retries.incrementAndGet()
                listener.onRetry(item, attempt, d, "الشبكة: ${e.message}")
                delay(d)
            }
        }
    }

    /**
     * Final subtitles. With [stabilize] the v2.6 cross-frame diacritics consensus runs first, so the
     * merge then sees identical texts for repeated frames of the same line.
     */
    fun mergedResult(mode: MergeMode, stabilize: Boolean = false): List<SubEntry> {
        var items = results.toList().sortedBy { it.startMs }
        if (stabilize && cleanMode != CleanMode.PRO) items = ArabicV26.stabilizeConsecutiveDiacritics(items)
        return when (mode) {
            MergeMode.PRO -> Merge.mergePro(items, 350, normalize = cleanMode == CleanMode.PRO)
            MergeMode.STRICT -> Merge.mergeSplits(items)
            MergeMode.NONE -> Merge.noMerge(items)
        }
    }
}

/** "Type: message ← cause" chain, readable in a screenshot. */
fun describeError(e: Throwable): String {
    val parts = ArrayList<String>()
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth < 4) {
        val msg = cur.message?.takeIf { it.isNotBlank() }
        parts.add(if (msg != null) "${cur.javaClass.simpleName}: $msg" else cur.javaClass.simpleName)
        cur = cur.cause.takeIf { it !== cur }
        depth++
    }
    return parts.joinToString(" ← ")
}

fun stackTraceOf(e: Throwable): String {
    val sw = java.io.StringWriter()
    e.printStackTrace(java.io.PrintWriter(sw))
    return sw.toString()
}
