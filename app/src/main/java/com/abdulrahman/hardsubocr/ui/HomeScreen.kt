@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abdulrahman.hardsubocr.core.SubLang
import com.abdulrahman.hardsubocr.data.VideoMode
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.data.SourceMode
import com.abdulrahman.hardsubocr.job.JobOutput
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Bad
import com.abdulrahman.hardsubocr.ui.theme.Good
import java.util.Locale

fun shareSrt(context: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-subrip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(i, "مشاركة ملف الترجمة"))
}

private val cardPad = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenRoi: () -> Unit,
    onOpenEditor: (Int) -> Unit
) {
    val context = LocalContext.current
    val state by vm.job.collectAsState()
    val running = state is JobState.Running
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    var logFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }
    // a job starts: show its progress from the top
    LaunchedEffect(running) { if (running) list.animateScrollToItem(0) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onImagePicked(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onOutputPicked(it) }
    val pickParent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onMultiParentPicked(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onVideoPicked(it) }
    val saveDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveDiagnostics(it) }
    var saveAsIndex by remember { mutableIntStateOf(-1) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) {
        vm.saveOutputAs(saveAsIndex, it)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }

    fun startClicked() {
        val problem = vm.canStart()
        if (problem != null) {
            vm.message = problem; return
        }
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val onSave: (Int, String) -> Unit = { i, name ->
        if (!vm.saveOutput(i)) {
            saveAsIndex = i; saveAs.launch("$name.srt")
        }
    }
    val diag = DiagActions(
        save = { saveDiag.launch(vm.diagFileName()) },
        share = { vm.shareDiagnostics(context) }
    )

    val (statusText, statusColor) = when (val st = state) {
        is JobState.Running -> String.format(Locale.US, "يعمل الآن • %.1f%%", st.progress.fraction * 100) to Amber
        is JobState.Done -> (if (st.stopped) "أُوقف" else "اكتمل") to (if (st.stopped) Amber else Good)
        is JobState.Error -> "حدث خطأ" to Bad
        JobState.Idle -> "جاهز" to Good
    }

    Scaffold(
        // Insets are handled by the header (status bar) and the action bar (navigation bar).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ActionBar(
                vm = vm,
                running = running,
                onStart = { startClicked() },
                onStop = { vm.stop() }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            state = list,
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 14.dp)) { AppHeader(statusText, statusColor, onOpenSettings, busy = running) }
            }
            item(key = "crash") {
                AnimatedVisibility(
                    visible = vm.lastErrorReport != null,
                    enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
                ) {
                    CrashBanner(vm.lastErrorReport ?: "", diag, onDismiss = { vm.dismissErrorReport() }, modifier = cardPad)
                }
            }
            item(key = "state") {
                AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(Motion.NORMAL)) + scaleIn(tween(Motion.NORMAL), initialScale = 0.96f)) togetherWith
                            fadeOut(tween(Motion.FAST)) using SizeTransform(clip = false)
                    },
                    label = "jobState"
                ) { st ->
                    when (st) {
                        is JobState.Running -> ProgressCard(st.progress, cardPad)
                        is JobState.Done -> ResultCard(
                            title = st.summary,
                            good = !st.stopped,
                            elapsed = st.elapsedSec,
                            details = null,
                            outputs = st.outputs,
                            onEdit = onOpenEditor,
                            onShare = { uri -> shareSrt(context, uri) },
                            onSave = onSave,
                            onClose = { vm.dismissResult() },
                            diag = null,
                            modifier = cardPad
                        )
                        is JobState.Error -> ResultCard(
                            title = st.message,
                            good = false,
                            elapsed = null,
                            details = st.details,
                            outputs = st.outputs,
                            onEdit = onOpenEditor,
                            onShare = { uri -> shareSrt(context, uri) },
                            onSave = onSave,
                            onClose = { vm.dismissResult() },
                            diag = diag,
                            modifier = cardPad
                        )
                        JobState.Idle -> Box(Modifier.fillMaxWidth())
                    }
                }
            }

            val st = state
            if (st is JobState.Running) {
                logSection(st.progress.log, logFilter) { logFilter = it }
            } else {
                item(key = "engine") { EngineCard(vm, enabled = true, modifier = cardPad) }
                item(key = "source") {
                    SourceCard(
                        vm, enabled = true,
                        onPickImage = { pickImage.launch(arrayOf("image/*")) },
                        onPickFolder = { pickFolder.launch(null) },
                        onPickParent = { pickParent.launch(null) },
                        onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                        onOpenRoi = onOpenRoi,
                        modifier = cardPad
                    )
                }
                item(key = "output") { OutputCard(vm, enabled = true, onPick = { pickOutput.launch(null) }, modifier = cardPad) }
            }
        }
    }

    vm.folderDialog?.let { choices ->
        FolderPickerDialog(
            loading = vm.folderDialogLoading,
            choices = choices,
            onToggle = { vm.toggleFolderChoice(it) },
            onAll = { vm.setAllFolderChoices(it) },
            onOther = { pickParent.launch(null) },
            onConfirm = { vm.confirmFolderChoices() },
            onDismiss = { vm.dismissFolderDialog() }
        )
    }
}

/** «حفظ / مشاركة ملف التشخيص» from the crash banner and the error card. */
class DiagActions(val save: () -> Unit, val share: () -> Unit)

@Composable
private fun DiagButtons(diag: DiagActions) {
    Hint("ملف التشخيص يحتوي خطوات المعالجة والأخطاء ومعلومات الجهاز، بدون أي مفتاح API.", Modifier.padding(top = 4.dp))
    RowSpaced {
        OutlinedButton(onClick = diag.save, modifier = Modifier.weight(1f)) { Text("💾 حفظ ملف التشخيص") }
        OutlinedButton(onClick = diag.share, modifier = Modifier.weight(1f)) { Text("↗ إرساله") }
    }
}

// ============================================================================ action bar

private fun sourceSummary(vm: MainViewModel): String = when (vm.sourceMode) {
    SourceMode.IMAGE -> vm.image?.let { "🖼 ${it.name}" } ?: "🖼 لم تُختر صورة"
    SourceMode.FOLDER -> vm.folder?.let { f -> "📁 ${f.name}" + (vm.folderImages?.let { " • $it صورة" } ?: "") } ?: "📁 لم يُختر مجلد"
    SourceMode.MULTI -> if (vm.multi.isEmpty()) "🗂 لا توجد مجلدات" else "🗂 ${vm.multi.size} مجلد • ${vm.multi.sumOf { it.images }} صورة"
    SourceMode.VIDEO -> vm.video?.let { "🎬 ${it.name}" } ?: "🎬 لم يُختر فيديو"
}

@Composable
private fun ActionBar(vm: MainViewModel, running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val model = when (vm.engine) {
                    EngineKind.GEMINI -> " • " + vm.geminiModel
                    EngineKind.OPENROUTER -> " • " + vm.openRouterModel.substringAfter('/')
                    EngineKind.LENS, EngineKind.VISION -> ""
                }
                Text(
                    engineName(vm.engine) + model,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    sourceSummary(vm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (fadeIn(tween(Motion.NORMAL)) + scaleIn(tween(Motion.NORMAL), initialScale = 0.9f)) togetherWith
                        fadeOut(tween(Motion.FAST)) using SizeTransform(clip = false)
                },
                label = "action"
            ) { isRunning ->
                if (isRunning) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp))
                        Text("إيقاف", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                vm.sourceMode == SourceMode.MULTI -> "استخراج الكل"
                                vm.sourceMode == SourceMode.VIDEO && vm.videoMode == VideoMode.FRAMES_ONLY -> "حفظ الصور"
                                else -> "استخراج"
                            },
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================ crash banner

@Composable
private fun CrashBanner(report: String, diag: DiagActions, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .border(1.dp, Bad.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = Bad)
            Spacer(Modifier.width(8.dp))
            Text("أُغلق التطبيق بشكل غير متوقع في المرة السابقة", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "إخفاء") }
        }
        Hint("أرسل ملف التشخيص أو انسخ التقرير للمطوّر ليُصلح السبب بدقة.")
        ErrorDetails(report)
        DiagButtons(diag)
    }
}

// ============================================================================ source

@Composable
private fun SourceCard(
    vm: MainViewModel,
    enabled: Boolean,
    onPickImage: () -> Unit,
    onPickFolder: () -> Unit,
    onPickParent: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenRoi: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard("المصدر", "🗂", modifier) {
        SectionLabel("لغة الترجمة في الفيديو/الصور")
        Segmented(
            listOf(SubLang.ARABIC to "العربية", SubLang.ENGLISH to "English"),
            vm.subLang,
            enabled
        ) { vm.chooseLang(it) }
        Hint(
            if (vm.subLang == SubLang.ARABIC) "مضبوط للعربية: الحروف المتصلة، النقاط والتشكيل، ومنع انقسام السطر الواحد."
            else "مضبوط للإنجليزية: كشف الحروف اللاتينية وقراءتها وتنظيفها.",
            Modifier.padding(top = 4.dp, bottom = 10.dp, start = 4.dp)
        )
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectTile("🖼", "صورة", "صورة واحدة", vm.sourceMode == SourceMode.IMAGE, enabled, Modifier.weight(1f).fillMaxHeight()) {
                vm.chooseSource(SourceMode.IMAGE)
            }
            SelectTile("📁", "مجلد صور", "صور بتوقيت في الاسم", vm.sourceMode == SourceMode.FOLDER, enabled, Modifier.weight(1f).fillMaxHeight()) {
                vm.chooseSource(SourceMode.FOLDER)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectTile("🗂", "مجلدات متعددة", "حلقات كاملة دفعة واحدة", vm.sourceMode == SourceMode.MULTI, enabled, Modifier.weight(1f).fillMaxHeight()) {
                vm.chooseSource(SourceMode.MULTI)
            }
            SelectTile("🎬", "فيديو", "اكتشاف تلقائي للترجمة", vm.sourceMode == SourceMode.VIDEO, enabled, Modifier.weight(1f).fillMaxHeight()) {
                vm.chooseSource(SourceMode.VIDEO)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (vm.sourceMode) {
            SourceMode.IMAGE -> {
                PathChip(vm.image?.name ?: "لم يتم اختيار صورة", empty = vm.image == null)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onPickImage, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("اختيار صورة") }
                Hint("إذا كان اسم الصورة يحمل التوقيت (مثل 0_00_10_000__0_00_12_000.png، صيغة VideoSubFinder) يُنشأ ملف SRT، وإلا يُعرض النص فقط.", Modifier.padding(top = 4.dp))
            }
            SourceMode.FOLDER -> {
                val f = vm.folder
                PathChip(
                    if (f == null) "لم يتم اختيار مجلد" else "📁 ${f.name}" + (vm.folderImages?.let { "  •  $it صورة" } ?: ""),
                    empty = f == null
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onPickFolder, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("📁 اختيار مجلد الصور") }
                Hint("أسماء الصور تحمل التوقيت، مثل 0_00_10_000__0_00_12_000.png (الصيغة التي يستخدمها VideoSubFinder وهذا التطبيق).", Modifier.padding(top = 4.dp))
            }
            SourceMode.MULTI -> {
                RowSpaced {
                    FilledTonalButton(onClick = onPickParent, enabled = enabled, modifier = Modifier.weight(1f)) { Text("📁 إضافة مجلدات") }
                    if (vm.multi.isNotEmpty()) {
                        OutlinedButton(onClick = { vm.clearMulti() }, enabled = enabled) { Text("مسح الكل") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (vm.multi.isEmpty()) {
                    Hint("اختر المجلد الذي يحتوي على مجلدات الحلقات ثم حدّد المطلوب. اسم كل مجلد يصبح اسم ملف SRT.")
                } else {
                    for (row in vm.multi.toList()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
                                .padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("📁 ${row.folder.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Hint("${row.images} صورة → ${row.folder.name}.srt")
                            }
                            IconButton(onClick = { vm.removeMulti(row) }, enabled = enabled) {
                                Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            SourceMode.VIDEO -> {
                val v = vm.video
                PathChip(v?.name ?: "لم يتم اختيار فيديو", empty = v == null)
                vm.videoInfo?.let { Hint(it.label, Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(8.dp))
                RowSpaced {
                    FilledTonalButton(onClick = onPickVideo, enabled = enabled, modifier = Modifier.weight(1f)) { Text("🎬 اختيار فيديو") }
                    OutlinedButton(onClick = onOpenRoi, enabled = enabled && v != null, modifier = Modifier.weight(1f)) { Text("▭ منطقة الترجمة") }
                }
                val r = vm.roi.normalized()
                Hint(
                    String.format(
                        Locale.US, "المنطقة: %d%%–%d%% أفقيًا، %d%%–%d%% عموديًا",
                        (r.left * 100).toInt(), (r.right * 100).toInt(), (r.top * 100).toInt(), (r.bottom * 100).toInt()
                    ),
                    Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                SectionLabel("بعد تحليل الفيديو")
                Segmented(
                    listOf(VideoMode.FRAMES_AND_TEXT to "الصور + استخراج النص", VideoMode.FRAMES_ONLY to "حفظ الصور فقط"),
                    vm.videoMode,
                    enabled
                ) { vm.chooseVideoMode(it) }
                Hint(
                    if (vm.videoMode == VideoMode.FRAMES_ONLY)
                        "تُحفظ صورة لكل ترجمة في مجلد «اسم الفيديو_RGBImages» بأسماء تحمل التوقيت (كما يحفظها VideoSubFinder) ثم ينتهي. لاستخراج النص لاحقًا اختر «مجلد صور» وحدد ذلك المجلد. لا يحتاج مفتاح API الآن."
                    else
                        "تُحفظ صور الترجمات في مجلد «اسم الفيديو_RGBImages»، وتُقرأ أثناء التحليل، ثم يُكتب ملف SRT.",
                    Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OutputCard(vm: MainViewModel, enabled: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard("مكان حفظ الترجمات", "💾", modifier) {
        val o = vm.output
        PathChip(
            o?.let { "📁 ${it.name}" } ?: if (vm.sourceMode == SourceMode.VIDEO) "مطلوب لوضع الفيديو" else "تلقائي — داخل مجلد الصور نفسه",
            empty = o == null
        )
        Spacer(Modifier.height(8.dp))
        RowSpaced {
            FilledTonalButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) { Text("📁 اختيار مجلد") }
            if (o != null) OutlinedButton(onClick = { vm.clearOutput() }, enabled = enabled) { Text("إزالة") }
        }
    }
}

// ============================================================================ results

@Composable
private fun ResultCard(
    title: String,
    good: Boolean,
    elapsed: Double?,
    details: String?,
    outputs: List<JobOutput>,
    onEdit: (Int) -> Unit,
    onShare: (Uri) -> Unit,
    onSave: (Int, String) -> Unit,
    onClose: () -> Unit,
    diag: DiagActions?,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    SectionCard(
        title = if (good) "تم الاستخراج" else "النتيجة",
        emoji = if (good) "✅" else "⚠️",
        modifier = modifier,
        trailing = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق") } }
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (good) Good else MaterialTheme.colorScheme.error)
        elapsed?.let { Hint("المدة: " + Timestamps.human(it)) }
        if (details != null) {
            Spacer(Modifier.height(4.dp))
            ErrorDetails(details)
        }
        if (diag != null) DiagButtons(diag)
        for ((i, o) in outputs.withIndex()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (o.error == null) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (o.error == null && !o.partial) Good else Amber, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (o.plainText != null || o.imagesSaved > 0) o.name else "${o.name}.srt", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(if (o.imagesSaved > 0 && o.entries.isEmpty()) "${o.imagesSaved} صورة" else "${o.entries.size} مقطع", style = MaterialTheme.typography.bodySmall)
                }
                o.info?.let { Hint(it, Modifier.padding(top = 4.dp)) }
                val info = buildList {
                    if (o.uri != null) add("محفوظ في: ${o.savedIn ?: "الملف المختار"}")
                    else if (o.plainText == null && o.entries.isNotEmpty()) add(if (o.partial) "جزئي — غير محفوظ" else "غير محفوظ")
                    else if (o.imagesSaved == 0 && o.entries.isEmpty() && o.plainText == null && o.error == null) add("لم تُكتشف أي ترجمة")
                    if (o.failures.isNotEmpty()) add("فشلت ${o.failures.size} صورة")
                    if (o.withoutTimestamp > 0) add("${o.withoutTimestamp} بدون توقيت")
                    o.error?.let { add(it) }
                }
                if (info.isNotEmpty()) Hint(info.joinToString("  •  "))
                o.plainText?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { PathChip(text) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("📋 نسخ النص") }
                }
                if (o.failures.isNotEmpty()) {
                    for ((name, err) in o.failures.take(3)) Hint("✖ $name: $err")
                }
                if (o.entries.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    RowSpaced {
                        FilledTonalButton(onClick = { onEdit(i) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("عرض وتعديل")
                        }
                        val uri = o.uri
                        if (uri != null) {
                            OutlinedButton(onClick = { onShare(uri) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("مشاركة")
                            }
                        } else {
                            OutlinedButton(onClick = { onSave(i, o.name) }, modifier = Modifier.weight(1f)) { Text("💾 حفظ") }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================ multi-folder dialog

@Composable
private fun FolderPickerDialog(
    loading: Boolean,
    choices: List<FolderChoice>,
    onToggle: (Int) -> Unit,
    onAll: (Boolean) -> Unit,
    onOther: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختيار مجلدات الحلقات") },
        text = {
            Column {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp)); Text("جاري البحث عن المجلدات…")
                    }
                } else {
                    Row {
                        TextButton(onClick = { onAll(true) }) { Text("تحديد الكل") }
                        TextButton(onClick = { onAll(false) }) { Text("إلغاء التحديد") }
                        TextButton(onClick = onOther) { Text("مجلد آخر") }
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(choices) { i, c ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(checked = c.checked, onCheckedChange = { onToggle(i) })
                                Text("📁 ${c.folder.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.images} صورة", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !loading) { Text("إضافة المحدد") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
