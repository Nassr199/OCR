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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abdulrahman.hardsubocr.core.EngineKind
import com.abdulrahman.hardsubocr.core.ThinkingLevel
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.data.SourceMode
import com.abdulrahman.hardsubocr.job.JobOutput
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.job.Progress
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

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onImagePicked(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onOutputPicked(it) }
    val pickParent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onMultiParentPicked(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onVideoPicked(it) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HardSub Arabic OCR", fontWeight = FontWeight.Bold)
                        Text(
                            "استخراج الترجمة العربية المدمجة إلى SRT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "الإعدادات") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EngineCard(vm, enabled = !running)
            SourceCard(
                vm, enabled = !running,
                onPickImage = { pickImage.launch(arrayOf("image/*")) },
                onPickFolder = { pickFolder.launch(null) },
                onPickParent = { pickParent.launch(null) },
                onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                onOpenRoi = onOpenRoi
            )
            OutputCard(vm, enabled = !running, onPick = { pickOutput.launch(null) })

            if (running) {
                Button(
                    onClick = { vm.stop() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp))
                    Text("إيقاف", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { startClicked() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(
                        if (vm.sourceMode == SourceMode.MULTI) "بدء استخراج ترجمات متعددة" else "استخراج الترجمة",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            when (val st = state) {
                is JobState.Running -> ProgressCard(st.progress)
                is JobState.Done -> ResultCard(
                    title = if (st.stopped) "■ " + st.summary else "✓ " + st.summary,
                    good = !st.stopped,
                    elapsed = st.elapsedSec,
                    outputs = st.outputs,
                    onEdit = onOpenEditor,
                    onShare = { uri -> shareSrt(context, uri) },
                    onSave = { i, name ->
                        if (!vm.saveOutput(i)) {
                            saveAsIndex = i; saveAs.launch("$name.srt")
                        }
                    },
                    onClose = { vm.dismissResult() }
                )
                is JobState.Error -> ResultCard(
                    title = "✖ " + st.message,
                    good = false,
                    elapsed = null,
                    outputs = st.outputs,
                    onEdit = onOpenEditor,
                    onShare = { uri -> shareSrt(context, uri) },
                    onSave = { i, name ->
                        if (!vm.saveOutput(i)) {
                            saveAsIndex = i; saveAs.launch("$name.srt")
                        }
                    },
                    onClose = { vm.dismissResult() }
                )
                JobState.Idle -> Unit
            }
            Spacer(Modifier.height(24.dp))
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

// ============================================================================ engine

@Composable
private fun EngineCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard("محرك القراءة", "🔎") {
        val opts = listOf(EngineKind.LENS to "Google Lens", EngineKind.GEMINI to "Gemini", EngineKind.OPENROUTER to "OpenRouter")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (kind, label) ->
                SegmentedButton(
                    selected = vm.engine == kind,
                    onClick = { vm.chooseEngine(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = opts.size),
                    enabled = enabled
                ) { Text(label, maxLines = 1, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (vm.engine) {
            EngineKind.LENS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Good)
                    Spacer(Modifier.width(8.dp))
                    Text("مجاني — بدون مفتاح API وبدون تنزيل أي برنامج", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Hint("اتصال مباشر ببروتوكول Google Lens (نفس Chrome-Lens-OCR في ويندوز لكن بدون chrome-lens.exe و7-Zip). التنظيف: arabic_filter الخاص بنسخة PRO.")
            }
            EngineKind.GEMINI -> ApiEngineFields(vm, enabled, gemini = true)
            EngineKind.OPENROUTER -> ApiEngineFields(vm, enabled, gemini = false)
        }
    }
}

@Composable
private fun ApiEngineFields(vm: MainViewModel, enabled: Boolean, gemini: Boolean) {
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val key = if (gemini) vm.geminiKey else vm.openRouterKey
    OutlinedTextField(
        value = key,
        onValueChange = { if (gemini) vm.geminiKey = it else vm.openRouterKey = it },
        label = { Text(if (gemini) "Gemini API Key" else "OpenRouter API Key") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) { Icon(Icons.Filled.Lock, contentDescription = "إظهار") }
        },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    RowSpaced {
        OutlinedButton(
            onClick = {
                val t = clipboard.getText()?.text?.trim().orEmpty()
                if (t.isEmpty()) vm.message = "الحافظة لا تحتوي على نص."
                else if (gemini) vm.geminiKey = t else vm.openRouterKey = t
            },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) { Text("📋 لصق") }
        FilledTonalButton(onClick = { vm.saveKeys() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("💾 حفظ API") }
    }
    Spacer(Modifier.height(10.dp))
    Box {
        OutlinedTextField(
            value = if (gemini) vm.geminiModel else vm.openRouterModel,
            onValueChange = { if (gemini) vm.geminiModel = it else vm.openRouterModel = it },
            label = { Text("الموديل") },
            singleLine = true,
            enabled = enabled,
            trailingIcon = {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Filled.KeyboardArrowDown, "اختيار") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            for (m in vm.modelSuggestions()) {
                DropdownMenuItem(text = { Text(m) }, onClick = {
                    if (gemini) vm.geminiModel = m else vm.openRouterModel = m
                    menu = false
                })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.fetchModels() }, enabled = enabled && !vm.loadingModels) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("جلب الموديلات المتاحة من الحساب")
        }
        if (vm.loadingModels) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    if (gemini) {
        Text("مستوى التفكير (Gemini 3)", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        val labels = mapOf(
            ThinkingLevel.DEFAULT to "افتراضي", ThinkingLevel.MINIMAL to "أدنى (أسرع)", ThinkingLevel.LOW to "منخفض",
            ThinkingLevel.MEDIUM to "متوسط", ThinkingLevel.HIGH to "عالٍ"
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in ThinkingLevel.entries) {
                FilterChip(selected = vm.thinking == t, onClick = { vm.chooseThinking(t) }, label = { Text(labels[t] ?: t.name) }, enabled = enabled)
            }
        }
        Hint("موجّه النظام systemInstruction الأصلي من نسخة v2.1 يُرسل كما هو حرفيًا. التنظيف الافتراضي: clean_ocr_output (يحفظ التشكيل والأسطر).")
    } else {
        Hint("نفس موجّه نسخة PRO وتنظيف arabic_filter. الموديل الافتراضي google/gemini-3.1-flash-lite.")
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
    onOpenRoi: () -> Unit
) {
    SectionCard("المصدر", "🗂") {
        val modes = listOf(
            SourceMode.IMAGE to "🖼 صورة", SourceMode.FOLDER to "📁 مجلد صور",
            SourceMode.MULTI to "🗂 مجلدات متعددة", SourceMode.VIDEO to "🎬 فيديو"
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((m, label) in modes) {
                FilterChip(selected = vm.sourceMode == m, onClick = { vm.chooseSource(m) }, label = { Text(label) }, enabled = enabled)
            }
        }
        Spacer(Modifier.height(10.dp))
        when (vm.sourceMode) {
            SourceMode.IMAGE -> {
                PathChip(vm.image?.name ?: "لم يتم اختيار صورة", empty = vm.image == null)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onPickImage, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("اختيار صورة") }
                Hint("اسم الصورة بتوقيت VideoSubFinder (0_00_10_000__0_00_12_000) يعطي ملف SRT، وبدونه يُعرض النص فقط.")
            }
            SourceMode.FOLDER -> {
                val f = vm.folder
                PathChip(
                    if (f == null) "لم يتم اختيار مجلد" else "📁 ${f.name}" + (vm.folderImages?.let { "  •  $it صورة" } ?: ""),
                    empty = f == null
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onPickFolder, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("📁 اختيار مجلد الصور") }
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
                    Hint("اختر المجلد الذي يحتوي على مجلدات الحلقات ثم حدّد المطلوب. يُستخدم اسم كل مجلد كاسم لملف SRT.")
                } else {
                    for (row in vm.multi.toList()) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📁 ${row.folder.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${row.folder.name}.srt", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                            )
                            IconButton(onClick = { vm.removeMulti(row) }, enabled = enabled) {
                                Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Hint("${vm.multi.size} مجلد في قائمة الانتظار")
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
                        Locale.US, "المنطقة: من %d%% إلى %d%% أفقيًا، ومن %d%% إلى %d%% عموديًا",
                        (r.left * 100).toInt(), (r.right * 100).toInt(), (r.top * 100).toInt(), (r.bottom * 100).toInt()
                    ),
                    Modifier.padding(top = 4.dp)
                )
                Hint("جديد في أندرويد: فكّ ترميز الفيديو بالعتاد إطارًا بإطار واكتشاف ظهور/اختفاء الترجمة بدقة الإطار، ثم قراءة لقطة واحدة لكل ترجمة — بدون VideoSubFinder.")
            }
        }
    }
}

@Composable
private fun OutputCard(vm: MainViewModel, enabled: Boolean, onPick: () -> Unit) {
    SectionCard("مجلد حفظ الترجمات", "💾") {
        val o = vm.output
        PathChip(
            o?.let { "📁 ${it.name}" } ?: if (vm.sourceMode == SourceMode.VIDEO) "مطلوب لوضع الفيديو" else "غير محدد — تُحفظ داخل مجلد الصور نفسه",
            empty = o == null
        )
        Spacer(Modifier.height(8.dp))
        RowSpaced {
            FilledTonalButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) { Text("📁 اختيار") }
            if (o != null) OutlinedButton(onClick = { vm.clearOutput() }, enabled = enabled) { Text("إزالة") }
        }
    }
}

// ============================================================================ progress & results

@Composable
private fun ProgressCard(p: Progress) {
    SectionCard(
        title = if (p.unitCount > 1) "${p.title}  (${p.unitIndex + 1}/${p.unitCount})" else p.title.ifEmpty { "جاري الاستخراج" },
        emoji = "⚙️"
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(p.fraction * 100).toInt()}%", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(p.phase, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        LinearProgressIndicator(
            progress = { p.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            strokeCap = StrokeCap.Round
        )
        p.decodeFraction?.let { d ->
            Spacer(Modifier.height(8.dp))
            Hint("تحليل الفيديو ${(d * 100).toInt()}%")
            LinearProgressIndicator(
                progress = { d.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Amber,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(12.dp))
        RowSpaced {
            StatTile("تمت", "${p.done}/${p.total}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile("ناجحة", "${p.ok}", Good, Modifier.weight(1f))
            StatTile("فارغة", "${p.empty}", Amber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        RowSpaced {
            StatTile("فاشلة", "${p.failed}", if (p.failed > 0) Bad else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile("السرعة", String.format(Locale.US, "%.2f/ث", p.speed), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile("المتبقي", Timestamps.human(p.etaSec), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (p.cached > 0 || p.retries > 0) {
            Spacer(Modifier.height(6.dp))
            Hint("♻ من ذاكرة الاستئناف: ${p.cached}   •   ↻ إعادات: ${p.retries}")
        }
        if (p.log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(top = 2.dp)
            ) {
                for (line in p.log.take(9)) {
                    Text(
                        line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    good: Boolean,
    elapsed: Double?,
    outputs: List<JobOutput>,
    onEdit: (Int) -> Unit,
    onShare: (Uri) -> Unit,
    onSave: (Int, String) -> Unit,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    SectionCard(
        title = "النتيجة",
        emoji = if (good) "✅" else "⚠️",
        trailing = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق") } }
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (good) Good else MaterialTheme.colorScheme.error)
        elapsed?.let { Hint("المدة: " + Timestamps.human(it)) }
        for ((i, o) in outputs.withIndex()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (o.error == null) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                        tint = if (o.error == null && !o.partial) Good else Amber, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (o.plainText != null) o.name else "${o.name}.srt", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("${o.entries.size} مقطع", style = MaterialTheme.typography.bodySmall)
                }
                val info = buildList {
                    if (o.uri != null) add("محفوظ في: ${o.savedIn ?: "الملف المختار"}")
                    else if (o.plainText == null && o.entries.isNotEmpty()) add(if (o.partial) "جزئي — غير محفوظ" else "غير محفوظ")
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
                    Spacer(Modifier.height(6.dp))
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
        title = { Text("اختيار مجلدات صور متعددة") },
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
