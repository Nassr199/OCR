package com.abdulrahman.hardsubocr.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abdulrahman.hardsubocr.App
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.GeminiEngine
import com.abdulrahman.hardsubocr.core.LumaGrid
import com.abdulrahman.hardsubocr.core.ScanMask
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubLang
import com.abdulrahman.hardsubocr.core.SubStyle
import com.abdulrahman.hardsubocr.data.VideoMode
import com.abdulrahman.hardsubocr.core.OpenRouterEngine
import com.abdulrahman.hardsubocr.core.Srt
import com.abdulrahman.hardsubocr.core.SubEntry
import com.abdulrahman.hardsubocr.core.ThinkingLevel
import com.abdulrahman.hardsubocr.data.Choice
import com.abdulrahman.hardsubocr.data.CleanChoice
import com.abdulrahman.hardsubocr.data.EnhanceChoice
import com.abdulrahman.hardsubocr.data.FileOcrCache
import com.abdulrahman.hardsubocr.data.FolderRef
import com.abdulrahman.hardsubocr.data.RoiRect
import com.abdulrahman.hardsubocr.data.Saf
import com.abdulrahman.hardsubocr.data.SourceMode
import com.abdulrahman.hardsubocr.job.JobManager
import com.abdulrahman.hardsubocr.job.JobOutput
import com.abdulrahman.hardsubocr.job.JobRequest
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.job.Source
import com.abdulrahman.hardsubocr.net.Net
import com.abdulrahman.hardsubocr.video.FramePreview
import com.abdulrahman.hardsubocr.video.VideoInfo
import com.abdulrahman.hardsubocr.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PickedFile(val uri: Uri, val name: String)
data class MultiRow(val folder: FolderRef, val images: Int)
data class FolderChoice(val folder: FolderRef, val images: Int, val checked: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<App>()
    val s = (app as App).settings

    val job: StateFlow<JobState> = JobManager.state

    // ------------------------------------------------------------------ engine
    var engine by mutableStateOf(s.engine)
        private set
    var geminiKey by mutableStateOf(s.geminiKey)
    var openRouterKey by mutableStateOf(s.openRouterKey)
    var geminiModel by mutableStateOf(s.geminiModel)
    var openRouterModel by mutableStateOf(s.openRouterModel)
    var thinking by mutableStateOf(s.thinking)
        private set
    var profileGemini by mutableStateOf(s.profileGemini)
        private set
    var profileOpenRouter by mutableStateOf(s.profileOpenRouter)
        private set
    var enhanceChoice by mutableStateOf(s.enhanceChoice)
        private set
    var mediaHigh by mutableStateOf(s.mediaHigh)
        private set
    var lastErrorReport by mutableStateOf(s.lastErrorReport)
        private set
    var fetchedGemini by mutableStateOf<List<String>>(emptyList())
        private set
    var fetchedOpenRouter by mutableStateOf<List<String>>(emptyList())
        private set
    var loadingModels by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ source / output
    var sourceMode by mutableStateOf(s.sourceMode)
        private set
    var image by mutableStateOf(parseFile(s.lastImage))
        private set
    var folder by mutableStateOf(FolderRef.parse(s.lastFolder))
        private set
    var folderImages by mutableStateOf<Int?>(null)
        private set
    val multi = mutableStateListOf<MultiRow>()
    var video by mutableStateOf(parseFile(s.lastVideo))
        private set
    var videoInfo by mutableStateOf<VideoInfo?>(null)
        private set
    var output by mutableStateOf(FolderRef.parse(s.outputFolder))
        private set
    var roi by mutableStateOf(s.roi)
        private set

    // multi-folder picker dialog
    var folderDialog by mutableStateOf<List<FolderChoice>?>(null)
        private set
    var folderDialogLoading by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ video preview / ROI
    var previewTimeUs by mutableStateOf(0L)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var previewLoading by mutableStateOf(false)
        private set
    var maskOverlay by mutableStateOf<Bitmap?>(null)
        private set
    var maskCount by mutableStateOf(0)
        private set
    var showMask by mutableStateOf(true)
    var brightThreshold by mutableStateOf(s.brightThreshold)
        private set
    var contrastDelta by mutableStateOf(s.contrastDelta)
        private set
    var darkText by mutableStateOf(s.darkText)
        private set
    var subLang by mutableStateOf(s.subLang)
        private set
    var videoMode by mutableStateOf(s.videoMode)
        private set
    private var previewJob: Job? = null
    private var maskJob: Job? = null

    // ------------------------------------------------------------------ messages & editor
    var message by mutableStateOf<String?>(null)
    var editIndex by mutableStateOf<Int?>(null)
        private set
    val editEntries = mutableStateListOf<SubEntry>()
    var editDirty by mutableStateOf(false)
        private set

    // settings-screen mirror (forces recomposition on change)
    var settingsVersion by mutableStateOf(0)
        private set
    fun bumpSettings() { settingsVersion++ }

    init {
        s.lastMulti?.split('\u001E')?.mapNotNull { FolderRef.parse(it) }?.forEach { multi.add(MultiRow(it, -1)) }
        folder?.let { refreshFolderCount(it) }
        video?.let { loadVideoInfo(it.uri) }
    }

    private fun parseFile(s: String?): PickedFile? {
        if (s.isNullOrBlank()) return null
        val p = s.split('\u001F')
        return if (p.size >= 2) PickedFile(Uri.parse(p[0]), p[1]) else null
    }

    private fun PickedFile.ser() = uri.toString() + "\u001F" + name

    // ================================================================== engine actions
    fun chooseEngine(e: EngineKind) {
        engine = e; s.engine = e
    }

    fun saveKeys() {
        s.geminiKey = geminiKey
        s.openRouterKey = openRouterKey
        s.geminiModel = geminiModel
        s.openRouterModel = openRouterModel
        message = "✓ تم حفظ المفتاح والموديل على هذا الجهاز (مشفّر)"
    }

    fun chooseThinking(t: ThinkingLevel) {
        thinking = t; s.thinking = t
    }

    fun modelSuggestions(): List<String> = when (engine) {
        EngineKind.GEMINI -> (GeminiEngine.SUGGESTED_MODELS + fetchedGemini).distinct()
        EngineKind.OPENROUTER -> (OpenRouterEngine.SUGGESTED_MODELS + fetchedOpenRouter).distinct()
        EngineKind.LENS -> emptyList()
    }

    fun chooseProfile(p: AiProfile) {
        if (engine == EngineKind.OPENROUTER) {
            profileOpenRouter = p; s.profileOpenRouter = p
        } else {
            profileGemini = p; s.profileGemini = p
        }
    }

    fun currentProfile(): AiProfile = if (engine == EngineKind.OPENROUTER) profileOpenRouter else profileGemini

    /** Effective "enhance images" for the selected engine (AUTO follows the profile). */
    fun enhanceEffective(): Boolean = when (enhanceChoice) {
        EnhanceChoice.ON -> true
        EnhanceChoice.OFF -> false
        EnhanceChoice.AUTO -> engine != EngineKind.LENS && currentProfile().enhanceImages
    }

    fun setEnhance(on: Boolean) {
        enhanceChoice = if (on) EnhanceChoice.ON else EnhanceChoice.OFF
        s.enhanceChoice = enhanceChoice
    }

    fun chooseEnhanceChoice(c: EnhanceChoice) {
        enhanceChoice = c; s.enhanceChoice = c
    }

    fun chooseMediaHigh(v: Boolean) {
        mediaHigh = v; s.mediaHigh = v
    }

    fun dismissErrorReport() {
        lastErrorReport = null; s.lastErrorReport = null
    }

    fun fetchModels() {
        if (loadingModels) return
        loadingModels = true
        viewModelScope.launch {
            try {
                when (engine) {
                    EngineKind.GEMINI -> {
                        if (geminiKey.isBlank()) {
                            message = "أدخل Gemini API Key أولاً"; return@launch
                        }
                        fetchedGemini = GeminiEngine.listModels(Net.transport, geminiKey.trim())
                        message = "✓ تم جلب ${fetchedGemini.size} موديل"
                    }
                    EngineKind.OPENROUTER -> {
                        fetchedOpenRouter = OpenRouterEngine.listModels(Net.transport)
                        message = "✓ تم جلب ${fetchedOpenRouter.size} موديل يدعم الصور"
                    }
                    EngineKind.LENS -> Unit
                }
            } catch (e: Exception) {
                message = "تعذر جلب الموديلات: ${e.message}"
            } finally {
                loadingModels = false
            }
        }
    }

    // ================================================================== source actions
    fun chooseSource(m: SourceMode) {
        sourceMode = m; s.sourceMode = m
    }

    fun onImagePicked(uri: Uri?) {
        uri ?: return
        Saf.persist(ctx, uri, false)
        val f = PickedFile(uri, Saf.displayName(ctx, uri))
        image = f; s.lastImage = f.ser()
    }

    fun onFolderPicked(uri: Uri?) {
        uri ?: return
        Saf.persist(ctx, uri, true)
        val f = Saf.folderFromTree(ctx, uri)
        folder = f; s.lastFolder = f.serialize()
        refreshFolderCount(f)
    }

    private fun refreshFolderCount(f: FolderRef) {
        folderImages = null
        viewModelScope.launch {
            folderImages = withContext(Dispatchers.IO) {
                runCatching { Saf.listImages(ctx, f).size }.getOrNull()
            }
        }
    }

    fun onOutputPicked(uri: Uri?) {
        uri ?: return
        Saf.persist(ctx, uri, true)
        val f = Saf.folderFromTree(ctx, uri)
        output = f; s.outputFolder = f.serialize()
    }

    fun clearOutput() {
        output = null; s.outputFolder = null
    }

    fun onMultiParentPicked(uri: Uri?) {
        uri ?: return
        Saf.persist(ctx, uri, true)
        val parent = Saf.folderFromTree(ctx, uri)
        folderDialogLoading = true
        folderDialog = emptyList()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Saf.imageFolders(ctx, parent) }.getOrDefault(emptyList()) }
            folderDialogLoading = false
            if (found.isEmpty()) {
                folderDialog = null
                message = "لم يتم العثور على مجلدات تحتوي على صور داخل «${parent.name}»"
            } else {
                val already = multi.map { it.folder.docId }.toSet()
                folderDialog = found.map { (f, n) -> FolderChoice(f, n, false) }.filter { it.folder.docId !in already }
                    .ifEmpty { found.map { (f, n) -> FolderChoice(f, n, false) } }
            }
        }
    }

    fun toggleFolderChoice(index: Int) {
        val d = folderDialog ?: return
        folderDialog = d.mapIndexed { i, c -> if (i == index) c.copy(checked = !c.checked) else c }
    }

    fun setAllFolderChoices(value: Boolean) {
        folderDialog = folderDialog?.map { it.copy(checked = value) }
    }

    fun confirmFolderChoices() {
        val chosen = folderDialog?.filter { it.checked } ?: emptyList()
        if (chosen.isEmpty()) {
            message = "حدد مجلدًا واحدًا على الأقل."; return
        }
        for (c in chosen) if (multi.none { it.folder.docId == c.folder.docId && it.folder.treeUri == c.folder.treeUri }) {
            multi.add(MultiRow(c.folder, c.images))
        }
        folderDialog = null
        persistMulti()
    }

    fun dismissFolderDialog() {
        folderDialog = null
    }

    fun removeMulti(row: MultiRow) {
        multi.remove(row); persistMulti()
    }

    fun clearMulti() {
        multi.clear(); persistMulti()
    }

    private fun persistMulti() {
        s.lastMulti = multi.joinToString("\u001E") { it.folder.serialize() }.ifEmpty { null }
    }

    fun onVideoPicked(uri: Uri?) {
        uri ?: return
        Saf.persist(ctx, uri, false)
        val f = PickedFile(uri, Saf.displayName(ctx, uri))
        video = f; s.lastVideo = f.ser()
        preview = null; maskOverlay = null
        loadVideoInfo(uri)
    }

    private fun loadVideoInfo(uri: Uri) {
        videoInfo = null
        viewModelScope.launch {
            videoInfo = withContext(Dispatchers.IO) { runCatching { VideoProbe.probe(ctx, uri) }.getOrNull() }
            if (videoInfo == null) message = "تعذر قراءة معلومات الفيديو"
            else if (previewTimeUs == 0L) previewTimeUs = (videoInfo!!.durationUs / 3).coerceAtLeast(0)
        }
    }

    // ================================================================== ROI screen
    fun openRoiEditor() {
        if (preview == null) loadPreview(previewTimeUs)
    }

    fun loadPreview(timeUs: Long) {
        val v = video ?: return
        val dur = videoInfo?.durationUs ?: 0L
        val t = if (dur > 0) timeUs.coerceIn(0, (dur - 200_000).coerceAtLeast(0)) else timeUs.coerceAtLeast(0)
        previewTimeUs = t
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(120) // debounce slider moves
            previewLoading = true
            val bmp = withContext(Dispatchers.Default) {
                runCatching { FramePreview.grab(ctx, v.uri, t) }.getOrNull()
            }
            previewLoading = false
            if (bmp == null) {
                message = "تعذر عرض الإطار"; return@launch
            }
            preview = bmp
            recomputeMask()
        }
    }

    fun setRoi(r: RoiRect, persist: Boolean) {
        roi = r
        if (persist) {
            s.roi = r.normalized(); recomputeMask()
        }
    }

    fun chooseLang(l: SubLang) {
        subLang = l; s.subLang = l; recomputeMask()
    }

    fun chooseVideoMode(m: VideoMode) {
        videoMode = m; s.videoMode = m
    }

    fun setMaskParams(bright: Int = brightThreshold, contrast: Int = contrastDelta, dark: Boolean = darkText) {
        brightThreshold = bright; contrastDelta = contrast; darkText = dark
        s.brightThreshold = bright; s.contrastDelta = contrast; s.darkText = dark
        recomputeMask()
    }

    private fun recomputeMask() {
        val bmp = preview ?: return
        val r = roi.normalized()
        val params = ScanParams.preset(subLang, if (darkText) SubStyle.DARK else SubStyle.LIGHT, brightThreshold, contrastDelta)
        maskJob?.cancel()
        maskJob = viewModelScope.launch {
            val res = withContext(Dispatchers.Default) { runCatching { maskOf(bmp, r, params) }.getOrNull() }
            if (res != null) {
                maskOverlay = res.first; maskCount = res.second
            }
        }
    }

    /** Same grid + mask as the decoder, computed from the preview frame (luma from RGB, BT.601). */
    private fun maskOf(bmp: Bitmap, r: RoiRect, params: ScanParams): Pair<Bitmap, Int> {
        val l = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val t = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val rw = ((r.right * bmp.width).toInt() - l).coerceIn(2, bmp.width - l)
        val rh = ((r.bottom * bmp.height).toInt() - t).coerceIn(2, bmp.height - t)
        val step = maxOf(1, rw / 640)
        val gw = maxOf(1, rw / step)
        val gh = maxOf(1, rh / step)
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, l, t, rw, rh)
        val luma = ByteArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val c = px[(gy * step) * rw + gx * step]
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            luma[gy * gw + gx] = (((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        }
        val mask = ScanMask.lineFilter(ScanMask.compute(LumaGrid(gw, gh, luma), params), params)
        val out = IntArray(gw * gh)
        for (i in out.indices) if (mask.bits[i]) out[i] = 0xE6FF3B30.toInt()
        return Bitmap.createBitmap(out, gw, gh, Bitmap.Config.ARGB_8888) to mask.count
    }

    fun textDetected(): Boolean {
        val m = maskOverlay ?: return false
        return maskCount >= maxOf(24, (0.0006 * m.width * m.height).toInt())
    }

    // ================================================================== start / stop
    fun canStart(): String? {
        if (JobManager.isRunning) return "يوجد استخراج قيد التشغيل"
        val imagesOnly = sourceMode == SourceMode.VIDEO && videoMode == VideoMode.FRAMES_ONLY
        if (!imagesOnly) when (engine) {
            EngineKind.GEMINI -> if (geminiKey.isBlank()) return "أدخل Gemini API Key أولاً"
            EngineKind.OPENROUTER -> if (openRouterKey.isBlank()) return "أدخل OpenRouter API Key أولاً"
            EngineKind.LENS -> Unit
        }
        return when (sourceMode) {
            SourceMode.IMAGE -> if (image == null) "اختر صورة أولاً." else null
            SourceMode.FOLDER -> if (folder == null) "اختر مجلد صور أولاً." else null
            SourceMode.MULTI -> if (multi.isEmpty()) "أضف مجلدًا واحدًا على الأقل." else null
            SourceMode.VIDEO -> when {
                video == null -> "اختر فيديو أولاً."
                output == null -> if (imagesOnly) "اختر مجلد الحفظ أولاً — فيه تُحفظ صور الترجمة." else "اختر مجلد حفظ الترجمات أولاً."
                else -> null
            }
        }
    }

    fun start() {
        canStart()?.let { message = it; return }
        // remember what was typed even if the user did not press «حفظ»
        s.geminiModel = geminiModel; s.openRouterModel = openRouterModel
        if (geminiKey.isNotBlank()) s.geminiKey = geminiKey
        if (openRouterKey.isNotBlank()) s.openRouterKey = openRouterKey

        val src: Source = when (sourceMode) {
            SourceMode.IMAGE -> image!!.let { Source.Image(it.uri, it.name) }
            SourceMode.FOLDER -> Source.Folder(folder!!)
            SourceMode.MULTI -> Source.Multi(multi.map { it.folder })
            SourceMode.VIDEO -> video!!.let { Source.Video(it.uri, it.name, roi.normalized()) }
        }
        val e = engine
        val req = JobRequest(
            source = src,
            output = output,
            engine = e,
            geminiKey = geminiKey.trim(),
            openRouterKey = openRouterKey.trim(),
            geminiModel = geminiModel.trim(),
            openRouterModel = openRouterModel.trim(),
            thinking = thinking,
            workers = s.workersFor(e),
            retries = s.retries,
            cleanMode = s.cleanModeFor(e),
            mergeMode = s.mergeModeFor(e),
            stripDiacritics = s.stripDiacritics,
            bom = s.bom,
            crlf = s.crlf,
            overwrite = s.overwrite,
            resume = s.resume,
            lensLanguage = s.lensLanguage,
            lensRegion = s.lensRegion,
            scan = s.scanParams(),
            analyzeEvery = s.analyzeEvery,
            videoMode = s.videoMode,
            videoEngine = s.videoEngine,
            gpu = s.gpu,
            saveClean = s.saveClean,
            lang = s.subLang,
            profile = s.profileFor(e),
            enhance = s.enhanceFor(e),
            mediaHigh = s.mediaHigh,
            stabilize = s.stabilizeFor(e)
        )
        JobManager.start(ctx, req)
    }

    fun stop() = JobManager.stop()

    fun dismissResult() = JobManager.dismiss()

    // ================================================================== results
    fun outputs(): List<JobOutput> = when (val st = job.value) {
        is JobState.Done -> st.outputs
        is JobState.Error -> st.outputs
        else -> emptyList()
    }

    fun openEditor(index: Int) {
        val o = outputs().getOrNull(index) ?: return
        editEntries.clear(); editEntries.addAll(o.entries)
        editIndex = index; editDirty = false
    }

    fun closeEditor() {
        editIndex = null
    }

    fun updateEntry(i: Int, text: String) {
        if (i !in editEntries.indices) return
        editEntries[i] = editEntries[i].copy(text = text.trim()); editDirty = true
    }

    fun deleteEntry(i: Int) {
        if (i !in editEntries.indices) return
        editEntries.removeAt(i); editDirty = true
    }

    private fun srtBytes(entries: List<SubEntry>) = Srt.bytes(entries, s.bom, s.crlf)

    /** Saves [index] (edited entries if the editor is open on it). Returns false when a "save as" is needed. */
    fun saveOutput(index: Int): Boolean {
        val o = outputs().getOrNull(index) ?: return true
        val entries = if (editIndex == index) editEntries.toList() else o.entries
        val bytes = srtBytes(entries)
        return try {
            val uri = when {
                o.uri != null && Saf.overwrite(ctx, o.uri, bytes) -> o.uri
                o.folderForSave != null -> Saf.writeFile(ctx, o.folderForSave, "${o.name}.srt", Saf.SRT_MIME, bytes, true)
                output != null -> Saf.writeFile(ctx, output!!, "${o.name}.srt", Saf.SRT_MIME, bytes, true)
                else -> return false
            }
            JobManager.replaceOutput(
                index, o.copy(uri = uri, entries = entries, partial = false, savedIn = o.folderForSave?.name ?: output?.name)
            )
            editDirty = false
            message = "✓ تم حفظ ${o.name}.srt"
            true
        } catch (e: Exception) {
            message = "تعذر الحفظ: ${e.message}"
            true
        }
    }

    fun saveOutputAs(index: Int, target: Uri?) {
        target ?: return
        val o = outputs().getOrNull(index) ?: return
        val entries = if (editIndex == index) editEntries.toList() else o.entries
        if (Saf.overwrite(ctx, target, srtBytes(entries))) {
            JobManager.replaceOutput(index, o.copy(uri = target, entries = entries, partial = false))
            editDirty = false
            message = "✓ تم الحفظ"
        } else message = "تعذر الحفظ في الملف المختار"
    }

    // ================================================================== settings helpers
    fun clearResumeCache() {
        val n = FileOcrCache.clearAll(ctx)
        message = "تم مسح ذاكرة الاستئناف ($n ملف)"
        bumpSettings()
    }

    fun cacheSizeLabel(): String {
        val b = FileOcrCache.sizeBytes(ctx)
        return if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
    }

    fun mergeLabel(c: Choice): String = when (c) {
        Choice.AUTO -> "تلقائي (حسب الأسلوب)"
        Choice.PRO -> "دمج التكرار الذكي"
        Choice.STRICT_OR_PRECISE -> "دمج الانقسام فقط (آمن)"
        Choice.NONE -> "بدون دمج إطلاقًا"
    }

    fun cleanLabel(c: CleanChoice): String = when (c) {
        CleanChoice.AUTO -> "تلقائي (حسب الأسلوب)"
        CleanChoice.V26 -> "حماية التشكيل (موصى به)"
        CleanChoice.STRICT_OR_PRECISE -> "حرفي متعدد الأسطر"
        CleanChoice.PRO -> "سريع: سطر واحد بدون تشكيل"
    }

    fun enhanceLabel(c: EnhanceChoice): String = when (c) {
        EnhanceChoice.AUTO -> "تلقائي (حسب الأسلوب)"
        EnhanceChoice.ON -> "دائمًا"
        EnhanceChoice.OFF -> "أبدًا"
    }
}
