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
import com.abdulrahman.hardsubocr.core.Ensemble
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

/**
 * Decodes the whole video once (every frame, hardware MediaCodec), computes the text masks of the
 * subtitle region on all CPU cores in parallel, feeds them in order to the Arabic-first [SubtitleTracker] and keeps, per subtitle, the best frame plus a small pool of
 * frames for the background-cleaning composite. Every finished subtitle is handed to [onSegment].
 */
/** Which detector(s) run over the video. */
enum class VideoEngine { ENSEMBLE, CLASSIC, V21, V22 }

/**
 * Decodes the whole video once (every frame, hardware MediaCodec), computes the text masks of the
 * subtitle region on all CPU cores in parallel and feeds them in order to the selected engine(s):
 *  - CLASSIC: the 2.0 segmenter;  V21: SubScan 2.1;  V22: SubScan 2.2 (stability + motion filter);
 *  - ENSEMBLE: all three on the same frames, fused by frame-level voting (any engine sees text →
 *    text; a boundary where >= 2 engines agree). Lines one engine misses are recovered by another.
 * Every resulting subtitle is handed to [onSegment] with its multi-frame image cropped to its box.
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
    private val onLog: (String) -> Unit,
    private val onProgress: (fraction: Float, analysedFrames: Int, subtitles: Int) -> Unit,
    private val onSegment: (startUs: Long, endUs: Long, image: SubImage) -> Unit
) {
    class Result(val frames: Int, val analysed: Int, val segments: Int, val report: String)

    private class Store {
        var best: IntArray? = null
        val pool = ArrayList<IntArray>(6)
    }

    /** A finished subtitle of one engine in ensemble mode (image parked on disk until fusion). */
    private class Parked(val startUs: Long, val endUs: Long, val file: File?, val image: SubImage?)

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
        val parked = Array(3) { ArrayList<Parked>() }
        val parkDir = File(context.cacheDir, "subscan-park/${System.nanoTime()}").apply { mkdirs() }
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
        val gpuOn = java.util.concurrent.atomic.AtomicBoolean(gpu != null)
        fun mask(grid: com.abdulrahman.hardsubocr.core.LumaGrid): TextMask {
            val g = gpu
            if (gpuOn.get() && g != null) {
                val fromGpu = runCatching { g.classify(grid, params) }.getOrNull()
                if (fromGpu != null) {
                    if (verified.get() < 30) {
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

        fun image(st: Store, x0: Int, y0: Int, x1: Int, y1: Int, mask: TextMask?): SubImage? {
            val frames = if (st.pool.size >= 2) st.pool.toList() else listOfNotNull(st.best ?: st.pool.firstOrNull())
            if (frames.isEmpty()) return null
            val r = rect ?: return null
            val full = FrameComposite.combine(frames, params.style)
            val fullClean = if (saveClean && mask != null) runCatching {
                FrameComposite.clean(full, capW, capH, params.style, params, mask, maxOf(1, gridStep / capStep))
            }.getOrNull() else null
            // crop to the subtitle's own box (+margin): neighbouring credits / scenery stay out of OCR
            val k = gridStep.toDouble() / capStep
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            val mx = maxOf(12, bw * 15 / 100)
            val my = maxOf(4, bh * 6 / 10)
            val cx0 = ((x0 - mx).coerceAtLeast(0) * k).toInt().coerceIn(0, capW - 1)
            val cy0 = ((y0 - my).coerceAtLeast(0) * k).toInt().coerceIn(0, capH - 1)
            val cx1 = ((x1 + 1 + mx).coerceAtMost(gridW) * k).toInt().coerceIn(cx0 + 1, capW)
            val cy1 = ((y1 + 1 + my).coerceAtMost(gridH) * k).toInt().coerceIn(cy0 + 1, capH)
            val ow = cx1 - cx0
            val oh = cy1 - cy0
            return SubImage(
                crop(full, capW, cx0, cy0, ow, oh), ow, oh, fullClean?.let { crop(it, capW, cx0, cy0, ow, oh) },
                r.left + cx0 * capStep, r.top + cy0 * capStep, ow * capStep, oh * capStep, frameW, frameH, frames.size
            )
        }

        fun finished(e: Int, startUs: Long, endUs: Long, img: SubImage?) {
            if (img == null) return
            if (engine != VideoEngine.ENSEMBLE) {
                segments++; onSegment(startUs, endUs, img); return
            }
            // park on disk (JPEG) until the whole video is known and the engines can vote
            val f = File(parkDir, "$e-$startUs.jpg")
            val ok = runCatching {
                val b = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
                try {
                    f.outputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, 95, o) }
                } finally {
                    b.recycle()
                }
            }.isSuccess
            parked[e].add(Parked(startUs, endUs, if (ok) f else null, if (ok) SubImage(IntArray(0), img.width, img.height, img.clean, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH, img.framesUsed) else img))
        }

        fun closeScan(e: Int, list: List<ScanSegment>) {
            for (c in list) {
                val st = stores[e].remove(c.id) ?: continue
                finished(e, c.startUs, c.endUs, image(st, c.x0, c.y0, c.x1, c.y1, c.mask))
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
                    finished(2, c.startUs, c.endUs, image(st, 0, 0, gridW - 1, gridH - 1, null))
                }
                for (id in d.discarded) stores[2].remove(id)
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > 250) {
                lastProgressAt = now
                val found = if (engine == VideoEngine.ENSEMBLE) parked.maxOf { it.size } else segments
                onProgress((fr.ptsUs.toFloat() / duration).coerceIn(0f, 1f), analysed, found)
            }
        }

        val grids = ArrayDeque<com.abdulrahman.hardsubocr.core.LumaGrid>()
        fun drainOne() {
            val (fr, fut) = inflight.removeFirst()
            process(fr, fut.get(), grids.removeFirst())
            free.addLast(fr)
        }

        var report = ""
        try {
            VideoDecoder(context, uri).decode(
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
                        finished(2, c.startUs, c.endUs, image(st, 0, 0, gridW - 1, gridH - 1, null))
                    }
                }
                if (engine == VideoEngine.ENSEMBLE) report = fuseAndEmit(parked, endUs, isActive)
                else report = "engine ${engines.joinToString()}: $segments subtitles\n"
                onProgress(1f, analysed, segments)
            }
        } finally {
            pool.shutdownNow()
            gpu?.close()
            gpu = null
            inflight.clear()
            for (m in stores) m.clear()
            parkDir.deleteRecursively()
        }
        return Result(frameIndex, analysed, segments, report)
    }

    /** Frame-level voting over the three engines' subtitles, then hands the fused ones out. */
    private fun fuseAndEmit(parked: Array<ArrayList<Parked>>, endUs: Long, isActive: () -> Boolean): String {
        val fus = (info.frameUs * analyzeEvery.coerceAtLeast(1)).coerceAtLeast(1)
        fun idx(us: Long) = ((us + fus / 2) / fus).toInt()
        val lists = parked.map { l -> l.sortedBy { it.startUs } }
        val fused = Ensemble.fuseFrames(lists.map { l -> l.map { intArrayOf(idx(it.startUs), maxOf(idx(it.startUs) + 1, idx(it.endUs))) } },
            minFrames = maxOf(1, (params.minDurationMs * 1000 / fus).toInt()))
        val sb = StringBuilder()
        val names = listOf("2.2", "2.1", "2.0")
        for ((e, l) in lists.withIndex()) {
            sb.append("== engine ").append(names[e]).append(": ").append(l.size).append(" subtitles\n")
            for (p in l) sb.append(Timestamps.srt(p.startUs / 1000)).append(" --> ").append(Timestamps.srt(p.endUs / 1000)).append('\n')
        }
        sb.append("== fused (voting): ").append(fused.size).append(" subtitles\n")
        for (f in fused) {
            if (!isActive()) break
            val p = lists[f[2]][f[3]]
            val startUs = f[0].toLong() * fus
            val endUs2 = minOf(endUs, f[1].toLong() * fus)
            sb.append(Timestamps.srt(startUs / 1000)).append(" --> ").append(Timestamps.srt(endUs2 / 1000))
                .append("   from ").append(names[f[2]]).append('\n')
            val img = p.image ?: continue
            val argb = if (p.file != null) {
                val b = BitmapFactory.decodeFile(p.file.absolutePath) ?: continue
                try {
                    IntArray(b.width * b.height).also { a -> b.getPixels(a, 0, b.width, 0, 0, b.width, b.height) }
                } finally {
                    b.recycle()
                }
            } else img.argb
            onSegment(startUs, endUs2, SubImage(argb, img.width, img.height, img.clean, img.roiX, img.roiY, img.roiW, img.roiH, img.frameW, img.frameH, img.framesUsed))
        }
        return sb.toString()
    }

    private fun crop(src: IntArray, srcW: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (j in 0 until h) System.arraycopy(src, (y0 + j) * srcW + x0, out, j * w, w)
        return out
    }
}
