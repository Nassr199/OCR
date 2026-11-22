package com.abdulrahman.hardsubocr.video

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.abdulrahman.hardsubocr.core.CaptureKind
import com.abdulrahman.hardsubocr.core.FrameComposite
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.ScanSegment
import com.abdulrahman.hardsubocr.core.SubtitleTracker
import com.abdulrahman.hardsubocr.core.SubtitleSegmenter
import com.abdulrahman.hardsubocr.core.SegmenterParams
import com.abdulrahman.hardsubocr.core.MaskParams
import com.abdulrahman.hardsubocr.core.SubStyle
import com.abdulrahman.hardsubocr.core.Arbiter
import com.abdulrahman.hardsubocr.core.Timestamps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import com.abdulrahman.hardsubocr.core.ScanMask
import com.abdulrahman.hardsubocr.core.TextMask
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.abdulrahman.hardsubocr.data.RoiRect

/** One finished subtitle image (composite) + where it came from (for VideoSubFinder-style names). */
class SubImage(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    /** Black-on-white version (TXTImages), when requested. */
    val clean: IntArray?,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val frameW: Int,
    val frameH: Int,
    val framesUsed: Int
)

/** Which detector(s) run over the video. */
enum class VideoEngine { ENSEMBLE, CLASSIC, V21, V22 }

/**
 * Decodes the whole video once (every frame, hardware MediaCodec), computes the text masks of the
 * subtitle region on all CPU cores (or the GPU, verified) in parallel and feeds them in order to the
 * selected engine(s):
 *  - CLASSIC: the 2.0 segmenter;  V21: SubScan 2.1;  V22: SubScan 2.2 (stability + motion filter);
 *  - ENSEMBLE: all three on the same frames. 2.1's subtitles are handed out at once (tag "b:<n>")
 *    exactly as 2.1 makes them; the other engines' subtitles are evidence: with [readText] the ones
 *    [Arbiter.needed] picks are handed out to be read (tag "a:<engine>:<index>", engine 0 = 2.2,
 *    1 = 2.0) and [Arbiter.resolve] decides afterwards; without it (frames only) the pieces that
 *    cover time 2.1 left empty are handed out as extra images.
 * Images are the whole subtitle region (like 2.1 and VideoSubFinder), composited from several frames.
 */
class VideoScanner(
    private val context: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    private val roi: RoiRect,
    private val params: ScanParams,
    private val analyzeEvery: Int,
    private val saveClean: Boolean,
    private val engine: VideoEngine,
    private val useGpu: Boolean,
    private val readText: Boolean,
    private val onLog: (String) -> Unit,
    private val onProgress: (fraction: Float, analysedFrames: Int, subtitles: Int) -> Unit,
    private val onSegment: (startUs: Long, endUs: Long, image: SubImage, tag: String?) -> Unit
) {
    class Result(
        val frames: Int,
        val analysed: Int,
        val segments: Int,
        val report: String,
        /** ENSEMBLE: 2.1's subtitles (start, end µs), index = n of tag "b:n". */
        val base: List<LongArray> = emptyList(),
        /** ENSEMBLE: evidence per engine (0 = 2.2, 1 = 2.0, 2 = 2.1 best single frame), index = i of tag "a:e:i". */
        val alts: List<List<LongArray>> = emptyList(),
        /** Frame period used by the engines (µs), for [Arbiter]. */
        val frameUs: Long = 0,
        val minFrames: Int = 1
    )

    private class Store {
        var best: IntArray? = null
        val pool = ArrayList<IntArray>(6)
    }

    /** A finished evidence subtitle in ensemble mode (image parked on disk as JPEG until the end). */
    private class Parked(val startUs: Long, val endUs: Long, val file: File?, val meta: SubImage)

    /** Evidence: 0 = 2.2, 1 = 2.0, 2 = 2.1's single best frame of each of its subtitles. */
    private val parked = Array(3) { ArrayList<Parked>() }
    private val parkDir = File(context.cacheDir, "subscan-park/${System.nanoTime()}")

    fun run(isActive: () -> Boolean): Result {
        val useClassic = engine == VideoEngine.CLASSIC || engine == VideoEngine.ENSEMBLE
        val use21 = engine == VideoEngine.V21 || engine == VideoEngine.ENSEMBLE
        val use22 = engine == VideoEngine.V22 || engine == VideoEngine.ENSEMBLE
        val t22 = if (use22) SubtitleTracker(params) else null
        val t21 = if (use21) SubtitleTracker(params.legacy21()) else null
        val classic = if (useClassic) SubtitleSegmenter(
            SegmenterParams(mask = MaskParams(175, 60, 1, params.style == SubStyle.DARK))
        ) else null
        val engines = listOfNotNull(t22?.let { "2.2" }, t21?.let { "2.1" }, classic?.let { "2.0" })
        val stores = Array(3) { HashMap<Int, Store>() }
        val baseList = ArrayList<LongArray>()
        if (engine == VideoEngine.ENSEMBLE) parkDir.mkdirs()
        var rect: Rect? = null
        var frameW = 0
        var frameH = 0
        var frameIndex = 0
        var analysed = 0
        var segments = 0
        var offsetUs = -1L
        var lastNormPts = 0L
        var lastProgressAt = 0L
        val every = analyzeEvery.coerceAtLeast(1)
        val duration = info.durationUs.coerceAtLeast(1)
        var capW = 0
        var capH = 0
        var capStep = 1
        var gridStep = 1
        var gridW = 1
        var gridH = 1

        val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 7)
        // GPU (Adreno / Mali …): per-pixel stage on OpenGL ES 3.1 compute, verified against the CPU
        var gpu: GpuClassifier? = if (useGpu) GpuClassifier.create { why -> onLog("🖥 GPU غير مستخدم: $why") } else null
        gpu?.let { g -> onLog("🖥 GPU: ${g.renderer}") }
        val verified = java.util.concurrent.atomic.AtomicInteger(0)
        val gpuCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gpuOn = java.util.concurrent.atomic.AtomicBoolean(gpu != null)
        fun mask(grid: com.abdulrahman.hardsubocr.core.LumaGrid): TextMask {
            val g = gpu
            if (gpuOn.get() && g != null) {
                val fromGpu = runCatching { g.classify(grid, params) }.getOrNull()
                if (fromGpu != null) {
                    // first 30 frames, then one frame in 256 for the whole video: checked against the CPU
                    if (verified.get() < 30 || gpuCalls.incrementAndGet() % 256 == 0) {
                        val cpu = ScanMask.classify(grid, params)
                        if (!cpu.contentEquals(fromGpu)) {
                            gpuOn.set(false); onLog("🖥 GPU أعطى نتيجة مختلفة عن المعالج — تم إيقافه والمتابعة على المعالج")
                            return ScanMask.fromClasses(cpu, grid.width, grid.height, params)
                        }
                        if (verified.incrementAndGet() == 30) onLog("🖥 GPU مطابق للمعالج بكسلًا بكسل ✓ (30 إطارًا) — التسريع مفعّل")
                    }
                    return ScanMask.fromClasses(fromGpu, grid.width, grid.height, params)
                }
                gpuOn.set(false); onLog("🖥 خطأ في GPU — المتابعة على المعالج")
            }
            return ScanMask.compute(grid, params)
        }
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "subscan").apply { isDaemon = true } }
        val inflight = ArrayDeque<Pair<RoiFrame, Future<TextMask>>>()
        val free = ArrayDeque<RoiFrame>()

        fun image(st: Store, mask: TextMask?): SubImage? {
            val frames = if (st.pool.size >= 2) st.pool.toList() else listOfNotNull(st.best ?: st.pool.firstOrNull())
            if (frames.isEmpty()) return null
            val r = rect ?: return null
            // the whole subtitle region, as 2.1 / VideoSubFinder save it: a crop to the detected box
            // (2.2) could cut a line the mask did not cover, and the v2.6 band crop then cut more
            val argb = FrameComposite.combine(frames, params.style)
            val clean = if (saveClean && mask != null) runCatching {
                FrameComposite.clean(argb, capW, capH, params.style, params, mask, maxOf(1, gridStep / capStep))
            }.getOrNull() else null
            return SubImage(argb, capW, capH, clean, r.left, r.top, r.width(), r.height(), frameW, frameH, frames.size)
        }

        /** [e]: 0 = 2.2, 1 = 2.1, 2 = 2.0 */
        fun finished(e: Int, startUs: Long, endUs: Long, img: SubImage?) {
            if (img == null) return
            if (engine != VideoEngine.ENSEMBLE) {
                segments++; onSegment(startUs, endUs, img, null); return
            }
            if (e == 1) {
                val n = baseList.size
                baseList.add(longArrayOf(startUs, endUs))
                segments++
                onSegment(startUs, endUs, img, "b:$n")
                return
            }
            park(if (e == 0) 0 else 1, startUs, endUs, img)
        }

        fun parkBest(st: Store, startUs: Long, endUs: Long) {
            val one = st.best ?: st.pool.firstOrNull() ?: return
            val r = rect ?: return
            park(2, startUs, endUs, SubImage(one, capW, capH, null, r.left, r.top, r.width(), r.height(), frameW, frameH, 1))
        }

        fun closeScan(e: Int, list: List<ScanSegment>) {
            for (c in list) {
                val st = stores[e].remove(c.id) ?: continue
                val img = image(st, c.mask)
                // 2.1's best single frame: read only if 2.1's composite reads nothing
                if (e == 1 && img != null && engine == VideoEngine.ENSEMBLE) parkBest(st, c.startUs, c.endUs)
                finished(e, c.startUs, c.endUs, img)
            }
        }

        fun capture(e: Int, id: Int, kind: CaptureKind, fr: RoiFrame) {
            val st = stores[e].getOrPut(id) { Store() }
            when (kind) {
                CaptureKind.BEST -> st.best = fr.toArgb(capStep)
                CaptureKind.POOL -> st.pool.add(fr.toArgb(capStep))
                CaptureKind.RESTART -> {
                    st.pool.clear(); st.best = fr.toArgb(capStep)
                }
            }
        }

        fun process(fr: RoiFrame, raw: TextMask, grid: com.abdulrahman.hardsubocr.core.LumaGrid) {
            analysed++
            t22?.let { t ->
                val d = t.pushRaw(fr.ptsUs, raw)
                for (c in d.captures) capture(0, c.segId, c.kind, fr)
                closeScan(0, d.closed); for (id in d.discarded) stores[0].remove(id)
            }
            t21?.let { t ->
                val d = t.pushRaw(fr.ptsUs, raw)
                for (c in d.captures) capture(1, c.segId, c.kind, fr)
                closeScan(1, d.closed); for (id in d.discarded) stores[1].remove(id)
            }
            classic?.let { sg ->
                val d = sg.push(fr.ptsUs, grid)
                d.captureFor?.let { id -> stores[2].getOrPut(id) { Store() }.best = fr.toArgb(capStep) }
                for (c in d.closed) {
                    val st = stores[2].remove(c.id) ?: continue
                    finished(2, c.startUs, c.endUs, image(st, null))
                }
                for (id in d.discarded) stores[2].remove(id)
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > 250) {
                lastProgressAt = now
                onProgress((fr.ptsUs.toFloat() / duration).coerceIn(0f, 1f), analysed, segments)
            }
        }

        val grids = ArrayDeque<com.abdulrahman.hardsubocr.core.LumaGrid>()
        fun drainOne() {
            val (fr, fut) = inflight.removeFirst()
            process(fr, fut.get(), grids.removeFirst())
            free.addLast(fr)
        }

        var report = ""
        var fus = 0L
        var minFrames = 1
        var alts: List<List<LongArray>> = emptyList()
        try {
            VideoDecoder(context, uri).apply {
                onCodec = { name, sw ->
                    val vendor = when {
                        name.startsWith("c2.qti") || name.startsWith("OMX.qcom") -> " — Qualcomm"
                        name.startsWith("c2.mtk") || name.startsWith("OMX.MTK") -> " — MediaTek"
                        name.startsWith("c2.exynos") || name.startsWith("OMX.Exynos") -> " — Samsung"
                        else -> ""
                    }
                    onLog(if (sw) "🎞 فك الترميز برمجيًا: $name" else "🎞 فك الترميز بالعتاد: $name$vendor")
                }
            }.decode(
                isActive = isActive,
                want = { _ ->
                    val take = frameIndex % every == 0
                    frameIndex++
                    take
                },
                onFrame = { pts, img ->
                    if (offsetUs < 0) offsetUs = if (pts > 500_000) pts else 0L
                    val t = (pts - offsetUs).coerceAtLeast(0)
                    lastNormPts = maxOf(lastNormPts, t)
                    val r = rect ?: Yuv.roiPixels(img, roi).also {
                        rect = it
                        frameW = img.cropRect.width()
                        frameH = img.cropRect.height()
                        capStep = maxOf(1, (it.width() + 1919) / 1920) // composites <= 1920 px wide
                        capW = maxOf(1, it.width() / capStep)
                        capH = maxOf(1, it.height() / capStep)
                        gridStep = Yuv.gridStep(it.width())
                        gridW = maxOf(1, it.width() / gridStep)
                        gridH = maxOf(1, it.height() / gridStep)
                    }
                    val fr = free.removeFirstOrNull() ?: RoiFrame(r.width(), r.height())
                    fr.fill(img, r)
                    fr.ptsUs = t
                    val grid = fr.grid(gridStep)
                    grids.addLast(grid)
                    inflight.addLast(fr to pool.submit<TextMask> { mask(grid) })
                    while (inflight.size > workers + 1) drainOne()
                    isActive()
                }
            )
            while (inflight.isNotEmpty() && isActive()) drainOne()
            if (isActive()) {
                val endUs = lastNormPts + info.frameUs * every
                t22?.let { t -> closeScan(0, t.finish(endUs).closed) }
                t21?.let { t -> closeScan(1, t.finish(endUs).closed) }
                classic?.let { sg ->
                    for (c in sg.finish(endUs).closed) {
                        val st = stores[2].remove(c.id) ?: continue
                        finished(2, c.startUs, c.endUs, image(st, null))
                    }
                }
                if (engine == VideoEngine.ENSEMBLE) {
                    fus = (info.frameUs * every).coerceAtLeast(1)
                    minFrames = maxOf(1, (params.minDurationMs * 1000 / fus).toInt())
                    alts = parked.map { l -> l.map { longArrayOf(it.startUs, it.endUs) } }
                    val (rep, extra) = endOfScan(baseList, fus, minFrames, isActive)
                    report = rep
                    segments += extra
                } else report = "engine ${engines.joinToString()}: $segments subtitles\n"
                onProgress(1f, analysed, segments)
            }
        } finally {
            pool.shutdownNow()
            gpu?.close()
            gpu = null
            inflight.clear()
            for (m in stores) m.clear()
            if (engine != VideoEngine.ENSEMBLE || !readText) close()
        }
        return Result(frameIndex, analysed, segments, report, baseList.toList(), alts, fus, minFrames)
    }

    private fun park(k: Int, startUs: Long, endUs: Long, img: SubImage) {
        val f = File(parkDir, "$k-${parked[k].size}.jpg")
        val ok = runCatching {
            val b = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
            try {
                f.outputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, 95, o) }
            } finally {
                b.recycle()
            }
        }.isSuccess
        parked[k].add(
            Parked(
                startUs, endUs, if (ok) f else null,
                SubImage(IntArray(0), img.width, img.height, null, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH, img.framesUsed)
            )
        )
    }

    /** Deletes the parked evidence images (call when the job is done with this scanner). */
    fun close() {
        runCatching { parkDir.deleteRecursively() }
        for (l in parked) l.clear()
    }

    private val names = listOf("2.2", "2.0", "أوضح إطار في 2.1")

    /**
     * End of an ENSEMBLE scan: the report of what each engine found; in frames-only mode the
     * evidence pieces covering time 2.1 left empty are handed out as extra images.
     * Returns (report, extra images).
     */
    private fun endOfScan(base: List<LongArray>, fus: Long, minFrames: Int, isActive: () -> Boolean): Pair<String, Int> {
        val sb = StringBuilder()
        sb.append("== 2.1 (الأساس): ").append(base.size).append(" ترجمة\n")
        for (b in base) sb.append(Timestamps.srt(b[0] / 1000)).append(" --> ").append(Timestamps.srt(b[1] / 1000)).append('\n')
        for (e in 0..1) {
            val l = parked[e]
            sb.append("== ").append(names[e]).append(" (شاهد): ").append(l.size).append(" ترجمة\n")
            for (p in l) sb.append(Timestamps.srt(p.startUs / 1000)).append(" --> ").append(Timestamps.srt(p.endUs / 1000)).append('\n')
        }
        var extra = 0
        if (!readText) {
            val basePieces = base.map { Arbiter.Piece(it[0], it[1], null) }
            val altPieces = (0..1).map { e -> parked[e].map { Arbiter.Piece(it.startUs, it.endUs, null) } }
            val gaps = Arbiter.gapPieces(basePieces, altPieces, fus, minFrames)
            sb.append("== صور إضافية في أوقات تركها 2.1 فارغة: ").append(gaps.size).append('\n')
            for (g in gaps) {
                if (!isActive()) break
                val e = g[0].toInt()
                sb.append(Timestamps.srt(g[2] / 1000)).append(" --> ").append(Timestamps.srt(g[3] / 1000)).append("   من ").append(names[e]).append('\n')
                if (out(parked[e][g[1].toInt()], g[2], g[3], null)) extra++
            }
        }
        return sb.toString() to extra
    }

    private fun out(p: Parked, startUs: Long, endUs: Long, tag: String?): Boolean {
        val f = p.file ?: return false
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return false
        val argb = try {
            IntArray(b.width * b.height).also { a -> b.getPixels(a, 0, b.width, 0, 0, b.width, b.height) }
        } finally {
            b.recycle()
        }
        val m = p.meta
        onSegment(startUs, endUs, SubImage(argb, m.width, m.height, null, m.roiX, m.roiY, m.roiW, m.roiH, m.frameW, m.frameH, m.framesUsed), tag)
        return true
    }

    /**
     * ENSEMBLE reading mode, after 2.1's subtitles have been read: hands out the evidence pieces
     * [need] (from [Arbiter.needed], indices per engine of [Result.alts]) with tag "a:e:i".
     * Returns a report line.
     */
    fun emitEvidence(need: List<List<Int>>, isActive: () -> Boolean): String {
        var n = 0
        for ((e, list) in need.withIndex()) for (i in list) {
            if (!isActive()) break
            val p = parked.getOrNull(e)?.getOrNull(i) ?: continue
            if (out(p, p.startUs, p.endUs, "a:$e:$i")) n++
        }
        return "== قراءة الشهود: $n صورة (حيث يختلف 2.2 أو 2.0 عن 2.1، أو حيث لم يُقرأ من صورة 2.1 شيء)\n"
    }
}
