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

    /**
     * Gemini / OpenRouter default (AiProfile.V26): V26's cleaning that also keeps «…», «» and the
     * en/em dash, and spaces a dialogue dash as on screen (ArabicV26.cleanExact).
     */
    EXACT,

    /** English / Latin subtitles: Arabic filters would delete every line. */
    ENGLISH,

    /**
     * Google Lens / Google Vision: the subtitle as on screen — lines, dialogue dashes, punctuation and
     * tashkeel kept; Latin letters, lines without Arabic and tatweel removed (ClassicText).
     */
    CLASSIC
}

object Cleaner {
    fun clean(raw0: String, mode: CleanMode, stripDiacritics: Boolean = false): String {
        // Arabic subtitles: an OCR line without a single Arabic letter is background text read
        // together with the subtitle (credits "2. Ekip Görüntü Yönetmeni", "Tuğberk Süel", logos)
        val raw = if (mode == CleanMode.ENGLISH || '\n' !in raw0) raw0
        else raw0.split('\n').filter { ArabicText.hasArabic(it) }.joinToString("\n")
        val t = when (mode) {
            CleanMode.PRO -> ArabicText.arabicFilter(raw)
            CleanMode.PRECISE -> ArabicText.cleanOcrOutput(raw).let { if (ArabicText.isSymbolsOnly(it)) "" else it }
            CleanMode.V26 -> ArabicV26.conservativeCleanDiacritics(ArabicText.cleanOcrOutput(raw))
                .let { if (it.isEmpty() || ArabicText.isSymbolsOnly(it)) "" else it }
            CleanMode.EXACT -> ArabicV26.cleanExact(raw)
            CleanMode.ENGLISH -> EnglishText.clean(raw)
            CleanMode.CLASSIC -> ClassicText.clean(raw, stripDiacritics)
        }
        if (mode == CleanMode.ENGLISH) return t
        // Arabic subtitles: a result without a single Arabic letter is background text
        // (credits "Gökhan Yılmaz", numerals "2.", channel logos) — never a subtitle line.
        if (!ArabicText.hasArabic(t)) return ""
        if (t.isEmpty()) return ""
        return if (stripDiacritics && mode != CleanMode.PRO && mode != CleanMode.CLASSIC) ArabicText.pyStrip(ArabicText.stripDiacritics(t)) else t
    }
}

/** tenacity wait_random_exponential(multiplier=1, min=2, max=20) + a retry ceiling. */
class RetryPolicy(val maxRetries: Int = 6, val minDelayMs: Long = 2_000, val maxDelayMs: Long = 20_000) {
    fun delayFor(attempt: Int, hintMs: Long?): Long {
        if (hintMs != null) return if (hintMs <= 0) 0 else hintMs + Random.nextLong(0, 750)
        val exp = (1000L shl (attempt - 1).coerceIn(0, 20)).coerceIn(minDelayMs, maxDelayMs)
        return if (exp <= minDelayMs) minDelayMs else Random.nextLong(minDelayMs, exp + 1)
    }

    /**
     * "Too many requests" with no wait given by the service: 4 s, then a random wait growing up to 45 s
     * (OpenRouter's advice: exponential back-off with jitter). The service's own wait is used when given.
     */
    fun rateLimitDelay(attempt: Int, hintMs: Long?): Long {
        if (hintMs != null) return delayFor(attempt, hintMs)
        val cap = (4_000L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(45_000)
        return if (cap <= 4_000) 4_000 else Random.nextLong(4_000, cap + 1)
    }

    /** A busy service is waited out longer than other errors: an image is not lost to a crowded minute. */
    val rateLimitRetries: Int get() = maxOf(maxRetries * 2, 8)
}

/**
 * How many requests are in flight at once. Starts at the number of workers; each "too many requests"
 * (429) halves it, and a run of answers without one gives one back. On a shared capacity — the
 * providers behind OpenRouter, Gemini's per-minute quota — the job settles at what the service accepts
 * instead of losing images to retries that all hit the wall together.
 */
class AdaptiveLimit(val max: Int, private val onChange: (Int) -> Unit = {}) {
    private val lock = Any()
    private var limit = max
    private var inFlight = 0
    private var okRun = 0

    val current: Int get() = synchronized(lock) { limit }

    suspend fun acquire() {
        while (true) {
            synchronized(lock) {
                if (inFlight < limit) {
                    inFlight++; return
                }
            }
            delay(150)
        }
    }

    fun release(rateLimited: Boolean) {
        var changed = -1
        synchronized(lock) {
            inFlight--
            if (rateLimited) {
                okRun = 0
                val next = maxOf(1, limit / 2)
                if (next != limit) { limit = next; changed = next }
            } else if (limit < max && ++okRun >= limit * 4) {
                okRun = 0
                limit++; changed = limit
            }
        }
        if (changed > 0) onChange(changed)
    }
}

class OcrItem(
    /** Stable identity used by the resume cache (folder + file + size …). */
    val key: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    /** Set for video-engine evidence: the text goes to [OcrPipeline.tagged], not to the results. */
    val tag: String? = null,
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
    /** The AI cross-check needed a third reading for [item]; [texts] are the readings, [chosen] the decision. */
    fun onCrossCheck(item: OcrItem, texts: List<String>, chosen: String) {}
    /** The AI cross-check's two readings of [item] agreed. */
    fun onCrossAgreed(item: OcrItem) {}

    /** The number of requests sent at once changed ([AdaptiveLimit]): [limit] of [workers]. */
    fun onLimit(limit: Int, workers: Int) {}
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
    /** AI cross-check: images whose two readings agreed, and images a third reading decided. */
    val crossAgreed = AtomicInteger()
    val crossDecided = AtomicInteger()
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
    private val breakerThreshold: Int = 12,
    /** Extra part of the resume-memory key (settings that change the reading but not the engine id). */
    private val keySuffix: String = "",
    /** Gemini/OpenRouter: read each image from two pictures, a third deciding when they differ ([AiConsensus]). */
    private val crossCheck: Boolean = false
) {
    private val workers = concurrency.coerceIn(1, 16)

    /** Requests in flight: lowered on 429 answers, raised back after calm (see [AdaptiveLimit]). */
    val limit = AdaptiveLimit(workers) { listener.onLimit(it, workers) }
    val results = ConcurrentLinkedQueue<SubEntry>()
    /** Texts of tagged items ("" = read nothing); failed items are absent. */
    val tagged = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val taggedEntries = java.util.concurrent.ConcurrentHashMap<String, SubEntry>()

    /**
     * Stopped before the tagged items were resolved: their texts (tags starting with [prefix], e.g.
     * 2.1's subtitles "b:") become ordinary results, so what was read can still be saved.
     */
    fun promoteTagged(prefix: String) {
        if (results.isNotEmpty()) return
        for ((tag, e) in taggedEntries) if (tag.startsWith(prefix)) results.add(e)
    }
    val failures = ConcurrentLinkedQueue<Pair<String, String>>()
    val stats = PipelineStats()

    private val cooldownUntil = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

    private fun cacheKey(item: OcrItem) = "${engine.cacheId}|$cleanMode|$stripDiacritics|${item.key}$keySuffix" + (if (crossCheck) "|x" else "")

    /** Every resume-memory key of this engine and its settings starts with this. */
    val cachePrefix: String get() = "${engine.cacheId}|"

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
            item.tag?.let {
                tagged[it] = cachedText
                if (cachedText.isNotEmpty()) taggedEntries[it] = SubEntry(item.startMs, item.endMs, cachedText)
            }
            if (cachedText.isNotEmpty()) {
                if (item.tag == null) results.add(SubEntry(item.startMs, item.endMs, cachedText))
                stats.ok.incrementAndGet()
                listener.onItem(item, ItemOutcome.Text(cachedText, true))
            } else {
                stats.empty.incrementAndGet()
                listener.onItem(item, ItemOutcome.Empty(true))
            }
            return
        }

        val outcome: ItemOutcome = try {
            val bytes = withContext(Dispatchers.IO) { item.load() }
            val text = if (crossCheck && !engine.kind.isClassicOcr) {
                val views = withContext(Dispatchers.IO) { preparer.views(bytes, item.name, engine.kind) }
                    .map { PreparedImage(it.bytes, it.mime, it.width, it.height, item.name) }
                val outcome = AiConsensus.read(views) { img ->
                    val raw = recognizeWithRetry(item, img)
                    Cleaner.clean(raw, cleanMode, stripDiacritics).also { logDropped(item, raw, it) }
                }
                if (outcome.agreed) {
                    stats.crossAgreed.incrementAndGet()
                    listener.onCrossAgreed(item)
                }
                else if (outcome.readings > 2) {
                    stats.crossDecided.incrementAndGet()
                    listener.onCrossCheck(item, outcome.texts, outcome.text)
                }
                outcome.text
            } else {
                val prepared = preparer.prepare(bytes, item.name, engine.kind)
                    .let { PreparedImage(it.bytes, it.mime, it.width, it.height, item.name) }
                val raw = recognizeWithRetry(item, prepared)
                Cleaner.clean(raw, cleanMode, stripDiacritics).also { logDropped(item, raw, it) }
            }
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
                if (item.tag == null) results.add(SubEntry(item.startMs, item.endMs, outcome.text))
                else {
                    tagged[item.tag] = outcome.text
                    taggedEntries[item.tag] = SubEntry(item.startMs, item.endMs, outcome.text)
                }
            }
            is ItemOutcome.Empty -> {
                stats.empty.incrementAndGet()
                item.tag?.let { tagged[it] = "" }
            }
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

    /** Diagnostics: what the engine read when cleaning kept nothing or much less of it. */
    private fun logDropped(item: OcrItem, raw: String, text: String) {
        val r = raw.count { it.isLetter() }
        if (r == 0) return
        if (text.isEmpty() || text.count { it.isLetter() } * 10 < r * 7) {
            DiagLog.i("ocr", "cleaning ${item.name}: «${oneLine(raw)}» → «${oneLine(text)}»")
        }
    }

    private fun oneLine(s: String) = s.replace("\n", " ⏎ ").let { if (it.length > 200) it.take(200) + "…" else it }

    private suspend fun recognizeWithRetry(item: OcrItem, img: PreparedImage): String {
        var attempt = 0
        var busy = 0
        while (true) {
            val wait = cooldownUntil.get() - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // one request of the allowed number in flight; given back before any waiting
            limit.acquire()
            val answer: Result<String> = try {
                Result.success(engine.recognize(img))
            } catch (e: CancellationException) {
                limit.release(false)
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val error = answer.exceptionOrNull()
            limit.release(rateLimited = (error as? OcrError.Retryable)?.rateLimited == true)
            if (error == null) return answer.getOrThrow()
            when (error) {
                is OcrError.Retryable -> {
                    val d: Long
                    if (error.rateLimited) {
                        // a crowded service: waited out longer, and all workers pause together
                        busy++
                        if (busy > retry.rateLimitRetries) throw OcrError.Failed("${error.message} (بعد ${retry.rateLimitRetries} محاولات)")
                        d = retry.rateLimitDelay(busy, error.retryAfterMs)
                        val until = System.currentTimeMillis() + d
                        cooldownUntil.updateAndGet { maxOf(it, until) }
                    } else {
                        attempt++
                        if (attempt > retry.maxRetries) throw OcrError.Failed("${error.message} (بعد ${retry.maxRetries} محاولات)")
                        d = retry.delayFor(attempt, error.retryAfterMs)
                    }
                    stats.retries.incrementAndGet()
                    listener.onRetry(item, attempt + busy, d, error.message ?: "")
                    delay(d)
                }
                is java.io.IOException -> {
                    attempt++
                    if (attempt > retry.maxRetries) throw OcrError.Failed("انقطاع الشبكة: ${error.message}")
                    val d = retry.delayFor(attempt, null)
                    stats.retries.incrementAndGet()
                    listener.onRetry(item, attempt, d, "الشبكة: ${error.message}")
                    delay(d)
                }
                else -> throw error
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
