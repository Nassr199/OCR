package com.abdulrahman.hardsubocr.job

import android.content.Context
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.AiUsage
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.GeminiEngine
import com.abdulrahman.hardsubocr.core.ItemOutcome
import com.abdulrahman.hardsubocr.core.LensEngine
import com.abdulrahman.hardsubocr.core.OcrEngine
import com.abdulrahman.hardsubocr.core.OcrError
import com.abdulrahman.hardsubocr.core.OcrItem
import com.abdulrahman.hardsubocr.core.OcrPipeline
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.VisionEngine
import com.abdulrahman.hardsubocr.core.VisionReport
import com.abdulrahman.hardsubocr.core.LensProtocol
import com.abdulrahman.hardsubocr.core.isClassicOcr
import com.abdulrahman.hardsubocr.core.PipelineListener
import com.abdulrahman.hardsubocr.core.RetryPolicy
import com.abdulrahman.hardsubocr.core.Srt
import com.abdulrahman.hardsubocr.core.SubEntry
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.core.describeError
import com.abdulrahman.hardsubocr.core.DiagLog
import com.abdulrahman.hardsubocr.core.stackTraceOf
import com.abdulrahman.hardsubocr.data.FileOcrCache
import com.abdulrahman.hardsubocr.data.FolderRef
import com.abdulrahman.hardsubocr.data.Saf
import com.abdulrahman.hardsubocr.img.AndroidImageCropper
import com.abdulrahman.hardsubocr.img.AndroidImagePreparer
import com.abdulrahman.hardsubocr.img.AndroidResampler
import com.abdulrahman.hardsubocr.net.Net
import com.abdulrahman.hardsubocr.video.VideoProbe
import com.abdulrahman.hardsubocr.video.VideoScanner
import com.abdulrahman.hardsubocr.video.VideoEngine
import com.abdulrahman.hardsubocr.core.Arbiter
import com.abdulrahman.hardsubocr.core.FrameComposite
import com.abdulrahman.hardsubocr.data.VideoMode
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

private const val LOG_KEEP = 400

class JobRunner(
    private val context: Context,
    private val req: JobRequest,
    private val publish: (JobState) -> Unit
) {
    private val startedAt = System.currentTimeMillis()
    private val log = ConcurrentLinkedDeque<LogLine>()
    private val logIds = java.util.concurrent.atomic.AtomicLong(0)
    private val imagePreparer = AndroidImagePreparer(enhance = req.enhance, cropBand = true)
    private val videoPreparer = AndroidImagePreparer(enhance = req.enhance, cropBand = false)

    // live unit state for the progress ticker
    @Volatile private var pipeline: OcrPipeline? = null
    @Volatile private var unitTitle = ""
    @Volatile private var unitIndex = 0
    @Volatile private var unitCount = 1
    @Volatile private var unitTotal = 0
    @Volatile private var unitStartedAt = System.currentTimeMillis()
    @Volatile private var decodeFraction: Float? = null
    @Volatile private var videoFrames = 0
    @Volatile private var videoStartedAt = 0L
    @Volatile private var lastPhaseKind = ""
    @Volatile private var phase = ""
        set(v) {
            field = v
            // logged when the kind of work changes, not on every percentage update
            val kind = v.filter { !it.isDigit() && it != '%' && it != '.' }.substringBefore('•').trim()
            if (kind != lastPhaseKind) {
                lastPhaseKind = kind
                DiagLog.i("Job", "المرحلة: $v")
            }
        }
    private val discoveredSegments = AtomicInteger(0)

    private fun addLog(line: String) {
        log.addFirst(LogLine(logIds.incrementAndGet(), line))
        while (log.size > LOG_KEEP) log.pollLast()
        DiagLog.i("Job", line)
    }

    /** What this job does, without the keys (first block of the job in the diagnostic log). */
    private fun describeRequest(): String = buildString {
        append("المحرك: ").append(req.engine)
        when (req.engine) {
            EngineKind.GEMINI -> append(" • ").append(req.geminiModel).append(" • تفكير ").append(req.thinking)
                .append(" • دقة الصورة ").append(if (req.mediaHigh) "عالية" else "افتراضية")
            EngineKind.OPENROUTER -> append(" • ").append(req.openRouterModel).append(" • تفكير ").append(req.thinking)
                .append(" • دقة الصورة ").append(if (req.mediaHigh) "عالية" else "افتراضية")
            EngineKind.LENS -> append(" • منطقة ").append(req.lensRegion).append(" • نظرات إضافية ").append(req.lensMultiView)
            EngineKind.VISION -> append(if (req.visionDocument) " • DOCUMENT_TEXT_DETECTION" else " • TEXT_DETECTION")
                .append(" • نموذج ").append(req.visionModel.ifEmpty { "builtin/stable" })
        }
        append(" • لغة ").append(req.lang).append(" • النص الناتج ").append(req.profile?.title ?: "—").append('\n')
        append("المصدر: ").append(
            when (val s = req.source) {
                is Source.Image -> "صورة ${s.name}"
                is Source.Folder -> "مجلد ${s.folder.name}"
                is Source.Multi -> "${s.folders.size} مجلدات"
                is Source.Video -> "فيديو ${s.name} • منطقة ${s.roi.normalized()}"
            }
        ).append('\n')
        append("العمليات المتوازية ").append(req.workers).append(" • إعادة ").append(req.retries)
            .append(" • تنظيف ").append(req.cleanMode).append(" • دمج ").append(req.mergeMode)
            .append(" • تحسين الصور ").append(req.enhance).append(" • استئناف ").append(req.resume)
        if (req.source is Source.Video) {
            append('\n').append("الفيديو: ").append(req.videoEngine).append(" • ").append(req.videoMode)
                .append(" • كل ").append(req.analyzeEvery).append(" إطار • Vulkan ").append(req.gpu)
                .append(" • ").append(req.scan)
        }
    }

    private fun buildEngine(): OcrEngine = when (req.engine) {
        EngineKind.LENS -> LensEngine(
            Net.transport, req.lang.code, req.lensRegion,
            views = if (req.lensMultiView) AndroidResampler else null
        )
        // Gemini and OpenRouter: the same request recipe, style and thinking level (AiRecipe)
        EngineKind.GEMINI -> GeminiEngine(
            Net.transport, req.geminiKey, req.geminiModel, req.thinking, req.profile ?: AiProfile.V26, req.mediaHigh, req.lang,
            usage = aiUsage
        )
        EngineKind.OPENROUTER -> OpenRouterEngine(
            Net.transport, req.openRouterKey, req.openRouterModel, req.thinking, req.profile ?: AiProfile.V26, req.mediaHigh, req.lang,
            usage = aiUsage
        )
        EngineKind.VISION -> VisionEngine(
            Net.transport, req.visionKey, req.lang.code,
            document = req.visionDocument,
            model = req.visionModel,
            cropper = AndroidImageCropper,
            trace = { visionTraces.add(it) }
        ).also { visionEngine = it }
    }

    @Volatile private var visionEngine: VisionEngine? = null

    /** Tokens (and OpenRouter's cost) of the AI engines, summed over the job. */
    private val aiUsage = AiUsage()

    /**
     * Image enhancement is part of what an AI engine reads; it enters the resume-memory key only when
     * it differs from the profile's own choice, so the keys of existing Gemini/OpenRouter memories stay
     * exactly as they were. Lens/Vision never get enhanced images (their key has CLASSIC_OCR_VERSION).
     */
    private val keySuffix: String =
        if (!req.engine.isClassicOcr && req.enhance != (req.profile?.enhanceImages ?: false)) "|enh=${req.enhance}" else ""

    /** Before reading: say plainly how many readings come back from the resume memory. */
    private fun announceMemory(cache: FileOcrCache?, p: OcrPipeline) {
        val n = cache?.count(p.cachePrefix) ?: 0
        if (n > 0) addLog("♻ ذاكرة الاستئناف: $n صورة قُرئت في تشغيل سابق لم يكتمل بنفس المحرك والإعدادات — تُكمل المهمة من حيث توقفت ولا تُقرأ من جديد")
    }

    /**
     * Lens/Vision job that read every image: its readings leave the resume memory, so running the
     * same folder/video again reads it afresh (memory is for interrupted jobs). Gemini/OpenRouter
     * keep theirs (they cost per image).
     */
    private fun forgetFinished(cache: FileOcrCache?, p: OcrPipeline) {
        if (cache == null || !req.engine.isClassicOcr || p.failures.isNotEmpty()) return
        val n = runCatching { cache.forget(p.cachePrefix) }.getOrDefault(0)
        if (n > 0) DiagLog.i("Cache", "اكتملت قراءة ${req.engine}: حُذفت $n قراءة من ذاكرة الاستئناف (التشغيل التالي يقرأ من جديد)")
    }

    private fun newPipeline(
        engine: OcrEngine,
        cache: FileOcrCache?,
        preparer: AndroidImagePreparer = imagePreparer
    ): OcrPipeline = OcrPipeline(
        engine = engine,
        preparer = preparer,
        cleanMode = req.cleanMode,
        stripDiacritics = req.stripDiacritics,
        concurrency = req.workers,
        retry = RetryPolicy(maxRetries = req.retries),
        cache = cache,
        keySuffix = keySuffix,
        listener = object : PipelineListener {
            override fun onItem(item: OcrItem, outcome: ItemOutcome) {
                when (outcome) {
                    is ItemOutcome.Text -> addLog((if (outcome.cached) "♻ " else "✓ ") + item.name.take(28) + "  ←  " + outcome.text.replace('\n', ' ').take(60))
                    is ItemOutcome.Empty -> addLog("○ " + item.name.take(28) + "  —  لا توجد ترجمة")
                    is ItemOutcome.Failed -> addLog("✖ " + item.name.take(28) + "  —  " + outcome.message.take(80))
                }
            }

            override fun onRetry(item: OcrItem, attempt: Int, delayMs: Long, reason: String) {
                addLog("↻ إعادة ($attempt) بعد ${"%.1f".format(java.util.Locale.US, delayMs / 1000.0)} ث — ${reason.take(80)}")
            }

            override fun onLimit(limit: Int, workers: Int) {
                addLog(
                    if (limit < workers) "⇣ الخدمة مزدحمة: تُرسل الآن $limit صورة في الوقت نفسه بدل $workers، ويعود العدد تدريجيًا"
                    else "⇡ عادت الطلبات المتزامنة إلى $workers"
                )
            }
        }
    )

    private fun snapshot(): Progress {
        val p = pipeline
        val st = p?.stats
        val done = st?.processed?.get() ?: 0
        val total = maxOf(unitTotal, discoveredSegments.get(), done)
        val elapsed = (System.currentTimeMillis() - unitStartedAt) / 1000.0
        val speed = if (elapsed > 0.5) done / elapsed else 0.0
        val dec = decodeFraction
        val ocrFraction = if (total > 0) done.toFloat() / total else 0f
        val unitFraction = if (dec != null) (dec * 0.6f + (if (dec >= 1f) ocrFraction else ocrFraction * dec) * 0.4f) else ocrFraction
        val overall = ((unitIndex + unitFraction.coerceIn(0f, 1f)) / unitCount.coerceAtLeast(1))
        val remaining = total - done
        val eta = if (dec != null && dec < 1f) {
            // still analysing: the remaining frames at the current analysis speed
            val vEl = (System.currentTimeMillis() - videoStartedAt) / 1000.0
            if (dec > 0.02f && vEl > 3) vEl * (1 - dec) / dec else Double.NaN
        } else if (speed > 0) remaining / speed else Double.NaN
        val vSec = (System.currentTimeMillis() - videoStartedAt) / 1000.0
        return Progress(
            title = unitTitle, phase = phase, fraction = overall, done = done, total = total,
            ok = st?.ok?.get() ?: 0, empty = st?.empty?.get() ?: 0, failed = st?.failed?.get() ?: 0,
            cached = st?.cached?.get() ?: 0, retries = st?.retries?.get() ?: 0,
            speed = speed, etaSec = eta, unitIndex = unitIndex, unitCount = unitCount,
            decodeFraction = dec, log = log.take(LOG_KEEP),
            elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0,
            frames = videoFrames,
            framesPerSec = if (dec != null && videoStartedAt > 0 && vSec > 0.5) videoFrames / vSec else 0.0,
            subtitlesFound = discoveredSegments.get()
        )
    }

    private fun partialOutput(name: String, folder: FolderRef?, error: String?): JobOutput? {
        val p = pipeline ?: return null
        // stopped before the engines were matched: keep 2.1's lines read so far
        p.promoteTagged("b:")
        val merged = p.mergedResult(req.mergeMode, req.stabilize)
        if (merged.isEmpty() && error == null) return null
        return JobOutput(
            name = name, uri = null, entries = merged, savedIn = null, folderForSave = folder,
            failures = p.failures.toList(), partial = true, error = error
        )
    }

    suspend fun run() {
        DiagLog.section("بدء مهمة")
        DiagLog.i("Job", describeRequest())
        val engine = try {
            buildEngine()
        } catch (e: OcrError.Fatal) {
            DiagLog.w("Job", "لم تبدأ: ${e.message}")
            publish(JobState.Error(e.message ?: "إعدادات غير مكتملة", emptyList()))
            return
        }
        val units: List<Source> = when (val s = req.source) {
            is Source.Multi -> s.folders.map { Source.Folder(it) }
            else -> listOf(s)
        }
        unitCount = units.size
        val outputs = ArrayList<JobOutput>()

        coroutineScope {
            val ticker = launch {
                var lastHealth = System.currentTimeMillis()
                while (isActive) {
                    val snap = snapshot()
                    publish(JobState.Running(snap))
                    val now = System.currentTimeMillis()
                    if (now - lastHealth >= 30_000) {
                        // every 30 s: where the job is and how much memory it uses (finds hangs and OOMs)
                        lastHealth = now
                        val rt = Runtime.getRuntime()
                        DiagLog.i(
                            "Health",
                            "${(snap.fraction * 100).toInt()}% • ${snap.done}/${snap.total} • ✓${snap.ok} ○${snap.empty} ✖${snap.failed} ↻${snap.retries}" +
                                (if (snap.decodeFraction != null) " • إطارات ${snap.frames} (${"%.1f".format(java.util.Locale.US, snap.framesPerSec)}/ث)" else "") +
                                " • ذاكرة ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB"
                        )
                    }
                    delay(300)
                }
            }
            try {
                for ((i, unit) in units.withIndex()) {
                    unitIndex = i
                    pipeline = null
                    decodeFraction = null
                    try {
                        outputs.add(runUnit(unit, engine))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OcrError.Fatal) {
                        throw e
                    } catch (e: Throwable) {
                        val name = unitName(unit)
                        val why = if (e is IllegalStateException || e is java.io.IOException) (e.message ?: describeError(e)) else describeError(e)
                        addLog("✖ $name — $why")
                        DiagLog.e("Job", "فشل «$name»", e)
                        val out = partialOutput(name, outputFolderFor(unit), why)
                            ?: JobOutput(name, null, emptyList(), null, outputFolderFor(unit), error = why)
                        outputs.add(out)
                        if (units.size == 1) {
                            ticker.cancel()
                            publish(JobState.Error(why, outputs, stackTraceOf(e)))
                            return@coroutineScope
                        }
                    }
                }
                ticker.cancel()
                val n = outputs.count { it.uri != null }
                val totalEntries = outputs.sumOf { it.entries.size }
                endLog("اكتملت", outputs)
                publish(
                    JobState.Done(
                        outputs, stopped = false, elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0,
                        summary = when {
                            outputs.any { it.imagesSaved > 0 } && outputs.all { it.entries.isEmpty() } ->
                                "تم حفظ ${outputs.sumOf { it.imagesSaved }} صورة ترجمة"
                            units.size > 1 -> "تم إنشاء $n ملفات SRT"
                            else -> "تم إنشاء $totalEntries مقطع ترجمة"
                        }
                    )
                )
            } catch (e: CancellationException) {
                ticker.cancel()
                val unit = units.getOrNull(unitIndex)
                if (unit != null) partialOutput(unitName(unit), outputFolderFor(unit), null)?.let { outputs.add(it) }
                endLog("أوقفها المستخدم", outputs)
                publish(
                    JobState.Done(
                        outputs, stopped = true, elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0,
                        summary = "تم إيقاف الاستخراج"
                    )
                )
                throw e
            } catch (e: OcrError.Fatal) {
                ticker.cancel()
                val unit = units.getOrNull(unitIndex)
                if (unit != null) partialOutput(unitName(unit), outputFolderFor(unit), e.message)?.let { outputs.add(it) }
                DiagLog.e("Job", "توقفت بخطأ دائم: ${e.message}")
                endLog("توقفت بخطأ", outputs)
                publish(JobState.Error(e.message ?: "خطأ", outputs))
            }
        }
    }

    private fun endLog(how: String, outputs: List<JobOutput>) {
        aiUsage.summary()?.let { addLog("🧮 ${if (req.engine == EngineKind.OPENROUTER) "OpenRouter" else "Gemini"}: $it") }
        val st = pipeline?.stats
        DiagLog.i(
            "Job",
            "$how بعد ${Timestamps.human((System.currentTimeMillis() - startedAt) / 1000.0)} • " +
                "${outputs.sumOf { it.entries.size }} سطر • ✓${st?.ok?.get() ?: 0} ○${st?.empty?.get() ?: 0} ✖${st?.failed?.get() ?: 0}" +
                " • من الذاكرة ${st?.cached?.get() ?: 0} • إعادات ${st?.retries?.get() ?: 0}"
        )
        for (o in outputs) {
            o.error?.let { DiagLog.w("Job", "${o.name}: $it") }
            for ((n, err) in o.failures.take(50)) DiagLog.w("Job", "صورة فشلت: $n — $err")
        }
        DiagLog.flush()
    }

    private fun unitName(u: Source): String = when (u) {
        is Source.Image -> u.name.substringBeforeLast('.')
        is Source.Folder -> u.folder.name
        is Source.Video -> u.name.substringBeforeLast('.')
        is Source.Multi -> "multi"
    }

    private fun outputFolderFor(u: Source): FolderRef? = req.output ?: (u as? Source.Folder)?.folder

    private fun save(folder: FolderRef?, baseName: String, entries: List<SubEntry>): Pair<android.net.Uri?, String?> {
        if (folder == null) return null to null
        val name = "$baseName.srt"
        val uri = Saf.writeFile(context, folder, name, Saf.SRT_MIME, Srt.bytes(entries, req.bom, req.crlf), req.overwrite)
        writeVisionReport(folder, baseName)
        return uri to folder.name
    }

    /** Google Vision: what Vision read for every image of this unit, next to the SRT. */
    private val visionTraces = java.util.concurrent.ConcurrentLinkedQueue<VisionEngine.Trace>()

    private fun writeVisionReport(folder: FolderRef, baseName: String) {
        if (req.engine != EngineKind.VISION || visionTraces.isEmpty()) return
        val text = VisionReport.build(
            baseName, req.visionDocument, req.lang.code, visionTraces.toList(),
            pipeline?.stats?.cached?.get() ?: 0,
            visionEngine?.modelNote() ?: req.visionModel.ifEmpty { "builtin/stable" }
        )
        val ok = runCatching {
            Saf.writeFile(context, folder, "${baseName}_vision.txt", "text/plain", text.toByteArray(Charsets.UTF_8), true)
        }.isSuccess
        if (ok) addLog("📄 تقرير Vision بجانب الترجمة: ${baseName}_vision.txt (${visionTraces.size} صورة)")
    }

    private fun beginUnit(title: String, total: Int, p: OcrPipeline) {
        visionTraces.clear()
        unitTitle = title
        unitTotal = total
        unitStartedAt = System.currentTimeMillis()
        discoveredSegments.set(0)
        pipeline = p
    }

    private suspend fun runUnit(unit: Source, engine: OcrEngine): JobOutput = when (unit) {
        is Source.Image -> runImage(unit, engine)
        is Source.Folder -> runFolder(unit.folder, engine)
        is Source.Video -> runVideo(unit, engine)
        is Source.Multi -> error("unreachable")
    }

    private suspend fun runImage(u: Source.Image, engine: OcrEngine): JobOutput {
        phase = "قراءة الصورة"
        val ts = Timestamps.parseVsfName(u.name)
        val p = newPipeline(engine, null)
        beginUnit(u.name, 1, p)
        val ch = Channel<OcrItem>(1)
        ch.trySend(OcrItem("image|${u.uri}", u.name, ts?.first ?: 0L, ts?.second ?: 2000L) { Saf.readBytes(context, u.uri) })
        ch.close()
        p.run(ch)
        p.failures.firstOrNull()?.let { throw IllegalStateException(it.second) }
        val entries = p.mergedResult(req.mergeMode, req.stabilize)
        val base = unitName(u)
        if (ts == null) {
            // Like the Windows build this mode needs a time code for SRT — here we still show the text.
            return JobOutput(
                name = base, uri = null, entries = entries, savedIn = null, folderForSave = req.output,
                plainText = entries.joinToString("\n") { it.text }.ifEmpty { "لم يتم العثور على ترجمة عربية في الصورة." }
            )
        }
        val (uri, savedIn) = withContext(Dispatchers.IO) { save(req.output, base, entries) }
        return JobOutput(base, uri, entries, savedIn, req.output)
    }

    private suspend fun runFolder(folder: FolderRef, engine: OcrEngine): JobOutput {
        phase = "جاري فحص المجلد"
        unitTitle = folder.name
        val images = withContext(Dispatchers.IO) { Saf.listImages(context, folder) }
        if (images.isEmpty()) throw IllegalStateException("لا توجد صور في المجلد: ${folder.name}")
        var noTs = 0
        val items = ArrayList<OcrItem>(images.size)
        for (d in images) {
            val ts = Timestamps.parseVsfName(d.name)
            if (ts == null) {
                noTs++; continue
            }
            val uri = d.uri
            items.add(OcrItem("${folder.docId}/${d.name}/${d.size}", d.name, ts.first, ts.second) { Saf.readBytes(context, uri) })
        }
        if (items.isEmpty()) {
            throw IllegalStateException(
                "صور «${folder.name}» بلا توقيت في الاسم. يجب أن تحمل الأسماء التوقيت مثل 0_00_10_000__0_00_12_000.png (صيغة VideoSubFinder)"
            )
        }
        if (noTs > 0) addLog("○ تم تجاهل $noTs صورة بدون توقيت في الاسم")
        val cache = if (req.resume) withContext(Dispatchers.IO) {
            FileOcrCache.open(context, "folder|${folder.treeUri}|${folder.docId}")
        } else null
        try {
            val p = newPipeline(engine, cache)
            beginUnit(folder.name, items.size, p)
            announceMemory(cache, p)
            phase = "قراءة الصور (${items.size})"
            val ch = Channel<OcrItem>(Channel.UNLIMITED)
            for (item in items) ch.trySend(item)
            ch.close()
            p.run(ch)
            phase = "دمج وحفظ"
            val entries = p.mergedResult(req.mergeMode, req.stabilize)
            val target = req.output ?: folder
            val (uri, savedIn) = withContext(Dispatchers.IO) { save(target, folder.name, entries) }
            if (uri != null) forgetFinished(cache, p)
            return JobOutput(folder.name, uri, entries, savedIn, target, p.failures.toList(), noTs)
        } finally {
            cache?.close()
        }
    }

    private suspend fun runVideo(u: Source.Video, engine: OcrEngine): JobOutput {
        phase = "تحليل الفيديو"
        unitTitle = u.name
        val base = unitName(u)
        val imagesOnly = req.videoMode == VideoMode.FRAMES_ONLY
        val info = withContext(Dispatchers.IO) { VideoProbe.probe(context, u.uri) }
        val frameDir = File(context.cacheDir, "video-frames/${System.currentTimeMillis()}").apply { mkdirs() }
        // Images are saved first, VideoSubFinder style: <name>_RGBImages (+ <name>_TXTImages).
        val rgbFolder = if (req.output != null) withContext(Dispatchers.IO) {
            runCatching { Saf.freshSubFolder(context, req.output, "${base}_RGBImages") }.getOrNull()
        } else null
        val txtFolder = if (req.output != null && req.saveClean) withContext(Dispatchers.IO) {
            runCatching { Saf.freshSubFolder(context, req.output, "${base}_TXTImages") }.getOrNull()
        } else null
        if (imagesOnly && rgbFolder == null) throw IllegalStateException("اختر مجلد الحفظ لحفظ صور الترجمة")
        val roi = u.roi.normalized()
        val sourceId = "video5|${u.uri}|${roi}|${req.scan}|${req.analyzeEvery}|${req.videoEngine}"
        val cache = if (req.resume && !imagesOnly) withContext(Dispatchers.IO) { FileOcrCache.open(context, sourceId) } else null
        val maxCrop = if (req.engine.isClassicOcr) LensProtocol.MAX_DIMENSION else 3072
        var saved = 0
        var saveErrors = 0
        var scan: VideoScanner.Result? = null
        var evidenceReport = ""
        // evidence images (ENSEMBLE): kept until the arbiter has decided which become lines
        class AltImg(val file: File, val roiX: Int, val roiY: Int, val roiW: Int, val roiH: Int, val frameW: Int, val frameH: Int)
        val altImgs = java.util.concurrent.ConcurrentHashMap<String, AltImg>()
        val sent = AtomicInteger(0)
        var scanner: VideoScanner? = null
        try {
            val p = newPipeline(engine, cache, videoPreparer)
            beginUnit(u.name, 0, p)
            announceMemory(cache, p)
            decodeFraction = 0f
            videoFrames = 0
            videoStartedAt = System.currentTimeMillis()
            DiagLog.i("Video", "${info.label} • ${info.mime} • ${info.width}x${info.height} • ${info.frameRate} fps • دوران ${info.rotation}")
            val ch = Channel<OcrItem>(Channel.UNLIMITED)
            coroutineScope {
                val ocr = if (imagesOnly) null else launch { p.run(ch) }
                try {
                    withContext(Dispatchers.Default) {
                        val sc = VideoScanner(
                            context, u.uri, info, roi, req.scan, req.analyzeEvery, req.saveClean, req.videoEngine, req.gpu,
                            readText = !imagesOnly,
                            onLog = { line -> addLog(line) },
                            onProgress = { f, frames, subs ->
                                decodeFraction = f
                                videoFrames = frames
                                phase = "تحليل الفيديو ${(f * 100).toInt()}% • $frames إطار • $subs ترجمة"
                            },
                            onSegment = { segStartUs, segEndUs, img, tag ->
                                val startMs = segStartUs / 1000
                                val endMs = segEndUs / 1000
                                // "a:…" = another engine's subtitle, read only as evidence (not saved as is)
                                val isAlt = tag?.startsWith("a:") == true
                                val name = FrameComposite.vsfFileName(
                                    startMs, endMs, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH, "jpeg"
                                )
                                val bmp = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                                val jpeg = try {
                                    ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
                                } finally {
                                    bmp.recycle()
                                }
                                if (rgbFolder != null && !isAlt) {
                                    if (runCatching { Saf.createFile(context, rgbFolder, name, "image/jpeg", jpeg) }.isSuccess) saved++
                                    else saveErrors++
                                }
                                if (isAlt && rgbFolder != null) {
                                    val full = File(frameDir, "full_" + tag!!.replace(':', '_') + ".jpeg")
                                    if (runCatching { full.writeBytes(jpeg) }.isSuccess) {
                                        altImgs[tag] = AltImg(full, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH)
                                    }
                                }
                                if (txtFolder != null && img.clean != null && !isAlt) {
                                    val cb = Bitmap.createBitmap(img.clean, img.width, img.height, Bitmap.Config.ARGB_8888)
                                    val png = try {
                                        AndroidImagePreparer.toPng(cb)
                                    } finally {
                                        cb.recycle()
                                    }
                                    runCatching { Saf.createFile(context, txtFolder, name.substringBeforeLast('.') + ".png", "image/png", png) }
                                }
                                discoveredSegments.incrementAndGet()
                                if (!imagesOnly) {
                                    // OCR input: Lens / Vision get <= 1500 px
                                    val ocrBytes = if (img.width > maxCrop) {
                                        val b = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                                        val sc = AndroidImagePreparer.scaleToFit(b, maxCrop)
                                        try {
                                            ByteArrayOutputStream().use { sc.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
                                        } finally {
                                            if (sc !== b) sc.recycle(); b.recycle()
                                        }
                                    } else jpeg
                                    val fileName = if (isAlt) "دليل_" + tag!!.substring(2).replace(':', '_') + "_" + name else name
                                    val file = File(frameDir, fileName)
                                    file.writeBytes(ocrBytes)
                                    val key = if (isAlt) "$tag|$startMs-$endMs" else "$startMs-$endMs"
                                    sent.incrementAndGet()
                                    ch.trySend(OcrItem(key, fileName, startMs, maxOf(startMs, endMs - 1), tag) { file.readBytes() })
                                } else {
                                    addLog("🖼 ${Timestamps.srt(startMs)} → ${Timestamps.srt(endMs)}  (${img.framesUsed} إطار)")
                                }
                            }
                        )
                        scanner = sc
                        val r = sc.run(isActive = { isActive })
                        scan = r
                        addLog("🎬 تم تحليل ${r.analysed} إطار — ${r.segments} ترجمة")
                        if (ocr != null && req.videoEngine == VideoEngine.ENSEMBLE && isActive) {
                            // 2.1's images are read first; then the other engines' images are read only
                            // where they disagree with 2.1 or where 2.1's image read nothing
                            phase = "قراءة صور الترجمة"
                            while (isActive && ocr.isActive && p.stats.processed.get() < sent.get()) delay(150)
                            val basePieces = r.base.mapIndexed { n, b -> Arbiter.Piece(b[0], b[1], p.tagged["b:$n"]) }
                            val altRaw = r.alts.map { l -> l.map { a -> Arbiter.Piece(a[0], a[1], null) } }
                            val need = Arbiter.needed(basePieces, altRaw, r.frameUs, r.minFrames)
                            phase = "قراءة الشهود (${need.sumOf { it.size }})"
                            evidenceReport = sc.emitEvidence(need) { isActive }
                            addLog("🔎 ${need.sumOf { it.size }} صورة إضافية من طريقتي الكشف الأخريين للتحقق من الأسطر المختلف عليها")
                        }
                    }
                } finally {
                    ch.close()
                }
                decodeFraction = 1f
                if (ocr != null) {
                    phase = "قراءة النصوص"
                    ocr.join()
                }
            }
            // ENSEMBLE: the text read decides (2.1 kept unless what the other engines read proves it wrong)
            var report = (scan?.report ?: "") + evidenceReport
            val sc = scan
            if (!imagesOnly && sc != null && req.videoEngine == VideoEngine.ENSEMBLE) {
                phase = "مطابقة المحركات بالقراءة"
                val basePieces = sc.base.mapIndexed { n, b -> Arbiter.Piece(b[0], b[1], p.tagged["b:$n"]) }
                val altPieces = sc.alts.mapIndexed { e, l -> l.mapIndexed { i, a -> Arbiter.Piece(a[0], a[1], p.tagged["a:$e:$i"]) } }
                val lines = Arbiter.resolve(basePieces, altPieces, sc.frameUs, sc.minFrames)
                for (l in lines) {
                    val sMs = l.startUs / 1000
                    p.results.add(SubEntry(sMs, maxOf(sMs, l.endUs / 1000 - 1), l.text))
                }
                val changed = lines.filter { it.note != "2.1" }
                val rep = StringBuilder(report)
                rep.append("== النتيجة بعد القراءة: ").append(lines.size).append(" سطر، منها ").append(changed.size).append(" تغيّر بدليل القراءة\n")
                for (l in changed) {
                    rep.append(Timestamps.srt(l.startUs / 1000)).append(" --> ").append(Timestamps.srt(l.endUs / 1000))
                        .append("   ").append(l.note).append(": ").append(l.text.replace('\n', ' ')).append('\n')
                }
                report = rep.toString()
                val added = changed.count { it.engine >= 0 }
                if (changed.isNotEmpty()) addLog("🧠 القراءة صحّحت ${changed.size} سطرًا (منها $added سطر فاته الكشف الأساسي)")
                // images of the lines that came from the other engines, next to 2.1's
                if (rgbFolder != null) withContext(Dispatchers.IO) {
                    for (l in lines) {
                        if (l.engine < 0) continue
                        val a = altImgs["a:${l.engine}:${l.ref}"] ?: continue
                        val nm = FrameComposite.vsfFileName(l.startUs / 1000, l.endUs / 1000, a.roiX, a.roiY, a.roiW, a.roiH, a.frameW, a.frameH, "jpeg")
                        if (runCatching { Saf.createFile(context, rgbFolder, nm, "image/jpeg", a.file.readBytes()) }.isSuccess) saved++
                    }
                }
            }
            // per-engine report next to the SRT: what each detector found and what the reading changed
            if (req.output != null && report.isNotBlank()) withContext(Dispatchers.IO) {
                runCatching {
                    Saf.writeFile(context, req.output, "${base}_engines.txt", "text/plain", report.toByteArray(Charsets.UTF_8), true)
                }
            }
            val where = rgbFolder?.let { "${req.output?.name}/${it.name}" }
            if (imagesOnly) {
                return JobOutput(
                    rgbFolder!!.name, null, emptyList(), where, req.output,
                    info = "تم حفظ $saved صورة ترجمة في: $where" +
                        (if (txtFolder != null) "\nونسخ منظفة في: ${req.output?.name}/${txtFolder.name}" else "") +
                        (if (saveErrors > 0) "\nتعذر حفظ $saveErrors صورة" else "") +
                        "\nلاستخراج النص لاحقًا: اختر «مجلد صور» وحدد هذا المجلد.",
                    imagesSaved = saved
                )
            }
            phase = "دمج وحفظ"
            val entries = p.mergedResult(req.mergeMode, req.stabilize)
            val (uri, savedIn) = withContext(Dispatchers.IO) { save(req.output, base, entries) }
            if (uri != null) forgetFinished(cache, p)
            return JobOutput(
                base, uri, entries, savedIn, req.output, p.failures.toList(),
                info = where?.let { "صور الترجمة محفوظة في: $it ($saved صورة)" }
            )
        } finally {
            cache?.close()
            scanner?.close()
            frameDir.deleteRecursively()
        }
    }

}
