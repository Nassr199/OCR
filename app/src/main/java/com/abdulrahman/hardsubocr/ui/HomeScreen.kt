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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abdulrahman.hardsubocr.core.AiProfile
import com.abdulrahman.hardsubocr.core.SubLang
import com.abdulrahman.hardsubocr.data.VideoMode
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

    val onSave: (Int, String) -> Unit = { i, name ->
        if (!vm.saveOutput(i)) {
            saveAsIndex = i; saveAs.launch("$name.srt")
        }
    }

    val (statusText, statusColor) = when (val st = state) {
        is JobState.Running -> "يعمل الآن • ${(st.progress.fraction * 100).toInt()}%" to Amber
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            AppHeader(statusText, statusColor, onOpenSettings)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                vm.lastErrorReport?.let { report ->
                    CrashBanner(report, onDismiss = { vm.dismissErrorReport() })
                }

                when (val st = state) {
                    is JobState.Running -> ProgressCard(st.progress)
                    is JobState.Done -> ResultCard(
                        title = st.summary,
                        good = !st.stopped,
                        elapsed = st.elapsedSec,
                        details = null,
                        outputs = st.outputs,
                        onEdit = onOpenEditor,
                        onShare = { uri -> shareSrt(context, uri) },
                        onSave = onSave,
                        onClose = { vm.dismissResult() }
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
                        onClose = { vm.dismissResult() }
                    )
                    JobState.Idle -> Unit
                }

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
                Spacer(Modifier.height(8.dp))
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

// ============================================================================ action bar

private fun engineName(e: EngineKind) = when (e) {
    EngineKind.LENS -> "Google Lens"
    EngineKind.GEMINI -> "Gemini"
    EngineKind.OPENROUTER -> "OpenRouter"
}

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
                    EngineKind.LENS -> ""
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
            if (running) {
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

// ============================================================================ crash banner

@Composable
private fun CrashBanner(report: String, onDismiss: () -> Unit) {
    Column(
        Modifier
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
        Hint("انسخ التقرير وأرسله للمطوّر ليُصلح السبب بدقة.")
        ErrorDetails(report)
    }
}

// ============================================================================ engine

@Composable
private fun EngineCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard("محرك القراءة", "🔎") {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectTile(
                "⚡", "Google Lens", "مجاني • بلا مفتاح", vm.engine == EngineKind.LENS, enabled,
                Modifier.weight(1f).fillMaxHeight()
            ) { vm.chooseEngine(EngineKind.LENS) }
            SelectTile(
                "✨", "Gemini", "الأدق • يحفظ التشكيل", vm.engine == EngineKind.GEMINI, enabled,
                Modifier.weight(1f).fillMaxHeight(), badge = "Gemini 4"
            ) { vm.chooseEngine(EngineKind.GEMINI) }
            SelectTile(
                "🌐", "OpenRouter", "موديلات متعددة", vm.engine == EngineKind.OPENROUTER, enabled,
                Modifier.weight(1f).fillMaxHeight()
            ) { vm.chooseEngine(EngineKind.OPENROUTER) }
        }
        Spacer(Modifier.height(14.dp))
        when (vm.engine) {
            EngineKind.LENS -> {
                CheckLine("مجاني تمامًا — لا يحتاج مفتاح API")
                CheckLine("اتصال مباشر بـ Google Lens بدون أي برامج إضافية")
                CheckLine("عدة صور بالتوازي (من الإعدادات)")
                Spacer(Modifier.height(6.dp))
                EnhanceSwitch(vm, enabled, lens = true)
            }
            EngineKind.GEMINI -> ApiEngineFields(vm, enabled, gemini = true)
            EngineKind.OPENROUTER -> ApiEngineFields(vm, enabled, gemini = false)
        }
    }
}

@Composable
private fun EnhanceSwitch(vm: MainViewModel, enabled: Boolean, lens: Boolean) {
    SwitchRow(
        "تحسين الصور قبل القراءة",
        if (lens) "قصّ منطقة الترجمة + تكبير + تباين وحدّة. جرّبه إن كانت النتائج ضعيفة."
        else "قصّ منطقة الترجمة + تكبير Lanczos + تباين وحدّة (مطابق لـ Pillow بكسلًا بكسل).",
        vm.enhanceEffective(),
        enabled
    ) { vm.setEnhance(it) }
}

private fun profileHint(p: AiProfile): String = when (p) {
    AiProfile.V26 -> "تحسين الصور + حماية التشكيل + تثبيت التشكيل بين الإطارات المتتالية. الأفضل مع Gemini 4."
    AiProfile.V21 -> "تعليمات مفصّلة جدًا وتنظيف حرفي يحفظ الأسطر والتشكيل كما هي، ودمج الانقسام فقط."
    AiProfile.PRO -> "تعليمات مختصرة: سطر واحد بدون تشكيل، ودمج التكرار الذكي. الأسرع والأرخص."
}

private val thinkingOptions = listOf(
    ThinkingLevel.DEFAULT to "تلقائي",
    ThinkingLevel.MINIMAL to "أدنى",
    ThinkingLevel.LOW to "منخفض",
    ThinkingLevel.MEDIUM to "متوسط",
    ThinkingLevel.HIGH to "عالٍ"
)

@Composable
private fun ApiEngineFields(vm: MainViewModel, enabled: Boolean, gemini: Boolean) {
    val clipboard = LocalClipboardManager.current
    var showKey by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val key = if (gemini) vm.geminiKey else vm.openRouterKey

    SectionLabel("مفتاح API")
    OutlinedTextField(
        value = key,
        onValueChange = { if (gemini) vm.geminiKey = it else vm.openRouterKey = it },
        placeholder = { Text(if (gemini) "AIza…" else "sk-or-…") },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
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
        FilledTonalButton(onClick = { vm.saveKeys() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("💾 حفظ") }
    }
    if (key.isBlank()) {
        Hint(
            if (gemini) "احصل على مفتاح مجاني من aistudio.google.com ← Get API key"
            else "احصل على مفتاح من openrouter.ai/keys",
            Modifier.padding(top = 4.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    SectionLabel("الموديل")
    Box {
        OutlinedTextField(
            value = if (gemini) vm.geminiModel else vm.openRouterModel,
            onValueChange = { if (gemini) vm.geminiModel = it else vm.openRouterModel = it },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            trailingIcon = {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Filled.KeyboardArrowDown, "اختيار") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            for (m in vm.modelSuggestions()) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m, fontWeight = FontWeight.SemiBold)
                            modelNote(m)?.let { Hint(it) }
                        }
                    },
                    onClick = {
                        if (gemini) vm.geminiModel = m else vm.openRouterModel = m
                        menu = false
                    }
                )
            }
        }
    }
    val note = modelNote(if (gemini) vm.geminiModel else vm.openRouterModel)
    if (note != null) Hint("ℹ️ $note", Modifier.padding(top = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.fetchModels() }, enabled = enabled && !vm.loadingModels) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("جلب الموديلات المتاحة لحسابك")
        }
        if (vm.loadingModels) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }

    Spacer(Modifier.height(6.dp))
    SectionLabel("الأسلوب")
    Segmented(
        listOf(AiProfile.V26 to "ذكي + تشكيل", AiProfile.V21 to "حرفي دقيق", AiProfile.PRO to "سريع"),
        vm.currentProfile(),
        enabled
    ) { vm.chooseProfile(it) }
    Hint(profileHint(vm.currentProfile()), Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))

    if (gemini) {
        Spacer(Modifier.height(12.dp))
        SectionLabel("مستوى التفكير")
        Segmented(thinkingOptions, vm.thinking, enabled) { vm.chooseThinking(it) }
        Hint(
            "\"منخفض\" هو الأنسب للـ OCR. إن لم يدعم الموديل المستوى المختار يتراجع التطبيق تلقائيًا بدون أخطاء.",
            Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            "دقة عالية للصور",
            "media_resolution = HIGH: قراءة أدق للحروف الصغيرة والتشكيل (رموز أكثر قليلًا).",
            vm.mediaHigh,
            enabled
        ) { vm.chooseMediaHigh(it) }
    }
    EnhanceSwitch(vm, enabled, lens = false)
}

/** One-line description for the well-known models in the dropdown. */
private fun modelNote(m: String): String? = when (m.substringAfter("google/")) {
    "gemini-4-hydrogen" -> "Gemini 4 Hydrogen — الأحدث (نوفمبر 2026)، سريع ومتاح مجانًا. موصى به"
    "gemini-4-argon" -> "Gemini 4 Argon — أقوى في القراءة الصعبة، أبطأ وأغلى"
    "gemini-4-carbon" -> "Gemini 4 Carbon — الأعلى دقة (مدفوع فقط)"
    "gemini-3.8-flash" -> "Gemini 3.8 Flash — متوازن"
    "gemini-3.6-flash" -> "Gemini 3.6 Flash — سريع"
    "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash-Lite — الأرخص للمجلدات الكبيرة"
    "gemini-3-flash-preview" -> "Gemini 3 Flash (معاينة)"
    "gemini-3.1-flash-lite" -> "Gemini 3.1 Flash-Lite — اقتصادي"
    else -> null
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
        SectionLabel("لغة الترجمة في الفيديو/الصور")
        Segmented(
            listOf(SubLang.ARABIC to "العربية", SubLang.ENGLISH to "English"),
            vm.subLang,
            enabled
        ) { vm.chooseLang(it) }
        Hint(
            if (vm.subLang == SubLang.ARABIC) "مضبوط للعربية: الحروف المتصلة، النقاط والتشكيل، ومنع انقسام السطر الواحد."
            else "English mode: Latin-tuned detection, English OCR prompt and cleaning.",
            Modifier.padding(top = 4.dp, bottom = 10.dp, start = 4.dp)
        )
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectTile("🖼", "صورة", "صورة واحدة", vm.sourceMode == SourceMode.IMAGE, enabled, Modifier.weight(1f).fillMaxHeight()) {
                vm.chooseSource(SourceMode.IMAGE)
            }
            SelectTile("📁", "مجلد صور", "صور VideoSubFinder", vm.sourceMode == SourceMode.FOLDER, enabled, Modifier.weight(1f).fillMaxHeight()) {
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
                Hint("إذا كان اسم الصورة بتوقيت VideoSubFinder يُنشأ ملف SRT، وإلا يُعرض النص فقط.", Modifier.padding(top = 4.dp))
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
                        "تُحفظ صورة لكل ترجمة في مجلد name_RGBImages بأسماء VideoSubFinder ثم ينتهي. لاستخراج النص لاحقًا اختر «مجلد صور» وحدد ذلك المجلد — لا يحتاج مفتاح API الآن."
                    else
                        "تُحفظ صور الترجمات أولًا في name_RGBImages، وتُقرأ بالتوازي أثناء التحليل، ثم يُكتب ملف SRT.",
                    Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OutputCard(vm: MainViewModel, enabled: Boolean, onPick: () -> Unit) {
    SectionCard("مكان حفظ الترجمات", "💾") {
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

// ============================================================================ progress & results

@Composable
private fun ProgressCard(p: Progress) {
    SectionCard(
        title = if (p.unitCount > 1) "${p.title}  (${p.unitIndex + 1}/${p.unitCount})" else p.title.ifEmpty { "جاري الاستخراج" },
        emoji = "⚙️"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(p.fraction, "مكتمل")
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.phase.isNotEmpty()) Text(p.phase, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${p.done} من ${p.total}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format(Locale.US, "⚡ %.2f صورة/ث", p.speed),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "⏳ المتبقي: " + Timestamps.human(p.etaSec),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        p.decodeFraction?.let { d ->
            Spacer(Modifier.height(10.dp))
            Hint("تحليل الفيديو ${(d * 100).toInt()}%")
            LinearProgressIndicator(
                progress = { d.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Amber,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(12.dp))
        RowSpaced {
            StatTile("ناجحة", "${p.ok}", Good, Modifier.weight(1f))
            StatTile("فارغة", "${p.empty}", Amber, Modifier.weight(1f))
            StatTile("فاشلة", "${p.failed}", if (p.failed > 0) Bad else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (p.cached > 0 || p.retries > 0) {
            Spacer(Modifier.height(6.dp))
            Hint("♻ من ذاكرة الاستئناف: ${p.cached}   •   ↻ إعادات: ${p.retries}")
        }
        if (p.log.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TerminalLog(p.log.take(10))
        }
    }
}

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
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    SectionCard(
        title = if (good) "تم الاستخراج" else "النتيجة",
        emoji = if (good) "✅" else "⚠️",
        trailing = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "إغلاق") } }
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = if (good) Good else MaterialTheme.colorScheme.error)
        elapsed?.let { Hint("المدة: " + Timestamps.human(it)) }
        if (details != null) {
            Spacer(Modifier.height(4.dp))
            ErrorDetails(details)
        }
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
