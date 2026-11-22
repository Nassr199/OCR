package com.abdulrahman.hardsubocr.job

import android.content.Context
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.GeminiEngine
import com.abdulrahman.hardsubocr.core.ItemOutcome
import com.abdulrahman.hardsubocr.core.LensEngine
import com.abdulrahman.hardsubocr.core.OcrEngine
import com.abdulrahman.hardsubocr.core.OcrError
import com.abdulrahman.hardsubocr.core.OcrItem
import com.abdulrahman.hardsubocr.core.OcrPipeline
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.PipelineListener
import com.abdulrahman.hardsubocr.core.RetryPolicy
import com.abdulrahman.hardsubocr.core.Srt
import com.abdulrahman.hardsubocr.core.SubEntry
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.core.describeError
import com.abdulrahman.hardsubocr.core.stackTraceOf
import com.abdulrahman.hardsubocr.data.FileOcrCache
import com.abdulrahman.hardsubocr.data.FolderRef
import com.abdulrahman.hardsubocr.data.Saf
import com.abdulrahman.hardsubocr.img.AndroidImagePreparer
import com.abdulrahman.hardsubocr.net.Net
import com.abdulrahman.hardsubocr.video.VideoProbe
import com.abdulrahman.hardsubocr.video.VideoScanner
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

class JobRunner(
    private val context: Context,
    private val req: JobRequest,
    private val publish: (JobState) -> Unit
) {
    private val startedAt = System.currentTimeMillis()
    private val log = ConcurrentLinkedDeque<String>()
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
    @Volatile private var phase = ""
    private val discoveredSegments = AtomicInteger(0)

    private fun addLog(line: String) {
        log.addFirst(line)
        while (log.size > 80) log.pollLast()
    }

    private fun buildEngine(): OcrEngine = when (req.engine) {
        EngineKind.LENS -> LensEngine(Net.transport, req.lang.code, req.lensRegion)
        EngineKind.GEMINI -> GeminiEngine(
            Net.transport, req.geminiKey, req.geminiModel, req.thinking, req.profile ?: AiProfile.V26, req.mediaHigh, req.lang
        )
        EngineKind.OPENROUTER -> OpenRouterEngine(
            Net.transport, req.openRouterKey, req.openRouterModel, req.profile ?: AiProfile.PRO, req.lang
        )
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
        listener = object : PipelineListener {
            override fun onItem(item: OcrItem, outcome: ItemOutcome) {
                when (outcome) {
                    is ItemOutcome.Text -> addLog((if (outcome.cached) "♻ " else "✓ ") + item.name.take(28) + "  ←  " + outcome.text.replace('\n', ' ').take(60))
                    is ItemOutcome.Empty -> addLog("○ " + item.name.take(28) + "  —  لا توجد ترجمة")
                    is ItemOutcome.Failed -> addLog("✖ " + item.name.take(28) + "  —  " + outcome.message.take(80))
                }
            }

            override fun onRetry(item: OcrItem, attempt: Int, delayMs: Long, reason: String) {
                addLog("↻ إعادة ($attempt) بعد ${delayMs / 1000.0} ث — ${reason.take(60)}")
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
        val eta = if (dec != null && dec < 1f) Double.NaN else if (speed > 0) remaining / speed else Double.NaN
        return Progress(
            title = unitTitle, phase = phase, fraction = overall, done = done, total = total,
            ok = st?.ok?.get() ?: 0, empty = st?.empty?.get() ?: 0, failed = st?.failed?.get() ?: 0,
            cached = st?.cached?.get() ?: 0, retries = st?.retries?.get() ?: 0,
            speed = speed, etaSec = eta, unitIndex = unitIndex, unitCount = unitCount,
            decodeFraction = dec, log = log.take(40)
        )
    }

    private fun partialOutput(name: String, folder: FolderRef?, error: String?): JobOutput? {
        val p = pipeline ?: return null
        val merged = p.mergedResult(req.mergeMode, req.stabilize)
        if (merged.isEmpty() && error == null) return null
        return JobOutput(
            name = name, uri = null, entries = merged, savedIn = null, folderForSave = folder,
            failures = p.failures.toList(), partial = true, error = error
        )
    }

    suspend fun run() {
        val engine = try {
            buildEngine()
        } catch (e: OcrError.Fatal) {
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
                while (isActive) {
                    publish(JobState.Running(snapshot()))
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
                publish(JobState.Error(e.message ?: "خطأ", outputs))
            }
        }
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
        return uri to folder.name
    }

    private fun beginUnit(title: String, total: Int, p: OcrPipeline) {
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
                "صور «${folder.name}» بلا توقيت في الاسم. يجب أن تكون بصيغة VideoSubFinder مثل 0_00_10_000__0_00_12_000.png"
            )
        }
        if (noTs > 0) addLog("○ تم تجاهل $noTs صورة بدون توقيت في الاسم")
        val cache = if (req.resume) withContext(Dispatchers.IO) {
            FileOcrCache.open(context, "folder|${folder.treeUri}|${folder.docId}")
        } else null
        try {
            val p = newPipeline(engine, cache)
            beginUnit(folder.name, items.size, p)
            phase = "قراءة الصور (${items.size})"
            val ch = Channel<OcrItem>(Channel.UNLIMITED)
            for (item in items) ch.trySend(item)
            ch.close()
            p.run(ch)
            phase = "دمج وحفظ"
            val entries = p.mergedResult(req.mergeMode, req.stabilize)
            val target = req.output ?: folder
            val (uri, savedIn) = withContext(Dispatchers.IO) { save(target, folder.name, entries) }
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
        val sourceId = "video2|${u.uri}|${roi}|${req.scan}|${req.analyzeEvery}"
        val cache = if (req.resume && !imagesOnly) withContext(Dispatchers.IO) { FileOcrCache.open(context, sourceId) } else null
        val maxCrop = if (req.engine == EngineKind.LENS) 1500 else 3072
        var saved = 0
        var saveErrors = 0
        try {
            val p = newPipeline(engine, cache, videoPreparer)
            beginUnit(u.name, 0, p)
            decodeFraction = 0f
            val ch = Channel<OcrItem>(Channel.UNLIMITED)
            coroutineScope {
                val ocr = if (imagesOnly) null else launch { p.run(ch) }
                try {
                    withContext(Dispatchers.Default) {
                        val scanner = VideoScanner(
                            context, u.uri, info, roi, req.scan, req.analyzeEvery, req.saveClean,
                            onProgress = { f, frames, subs ->
                                decodeFraction = f
                                phase = "تحليل الفيديو ${(f * 100).toInt()}% • $frames إطار • $subs ترجمة"
                            },
                            onSegment = { seg, img ->
                                val startMs = seg.startUs / 1000
                                val endMs = seg.endUs / 1000
                                val name = FrameComposite.vsfFileName(
                                    startMs, endMs, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH, "jpeg"
                                )
                                val bmp = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                                val jpeg = try {
                                    ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
                                } finally {
                                    bmp.recycle()
                                }
                                if (rgbFolder != null) {
                                    if (runCatching { Saf.createFile(context, rgbFolder, name, "image/jpeg", jpeg) }.isSuccess) saved++
                                    else saveErrors++
                                }
                                if (txtFolder != null && img.clean != null) {
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
                                    // OCR input: Lens only accepts <= 1500 px
                                    val ocrBytes = if (img.width > maxCrop) {
                                        val b = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                                        val sc = AndroidImagePreparer.scaleToFit(b, maxCrop)
                                        try {
                                            ByteArrayOutputStream().use { sc.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
                                        } finally {
                                            if (sc !== b) sc.recycle(); b.recycle()
                                        }
                                    } else jpeg
                                    val file = File(frameDir, name)
                                    file.writeBytes(ocrBytes)
                                    ch.trySend(OcrItem("$startMs-$endMs", name, startMs, maxOf(startMs, endMs - 1)) { file.readBytes() })
                                } else {
                                    addLog("🖼 ${Timestamps.srt(startMs)} → ${Timestamps.srt(endMs)}  (${img.framesUsed} إطار)")
                                }
                            }
                        )
                        val r = scanner.run(isActive = { isActive })
                        addLog("🎬 تم تحليل ${r.analysed} إطار — ${r.segments} ترجمة")
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
            return JobOutput(
                base, uri, entries, savedIn, req.output, p.failures.toList(),
                info = where?.let { "صور الترجمة محفوظة في: $it ($saved صورة)" }
            )
        } finally {
            cache?.close()
            frameDir.deleteRecursively()
        }
    }

}
