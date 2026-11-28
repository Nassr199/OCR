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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import com.abdulrahman.hardsubocr.ui.theme.swashTitle

fun shareSrt(context: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-subrip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(i, "مشاركة ملف الترجمة"))
}

internal val cardPad = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)

/**
 * The extraction tab: what the next job reads with (engine), what it reads (source) and where it
 * writes (output), under a card that says whether all of it is ready. Cards come in one after another.
 * A wide screen (a tablet, a phone on its side) puts them in two columns beside the navigation rail.
 */
@Composable
fun ExtractTab(
    vm: MainViewModel,
    running: Boolean,
    progress: Float?,
    onOpenRoi: () -> Unit,
    onShowProgress: () -> Unit
) {
    val context = LocalContext.current
    val chrome = LocalChrome.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onImagePicked(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onOutputPicked(it) }
    val pickParent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onMultiParentPicked(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.onVideoPicked(it) }
    val saveDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveDiagnostics(it) }
    val diag = DiagActions(save = { saveDiag.launch(vm.diagFileName()) }, share = { vm.shareDiagnostics(context) })

    val header: LazyListScope.() -> Unit = {
        item(key = "header") {
            TabHeader(
                "HardSub OCR", "استخراج الترجمة العربية المدمجة",
                status = if (running) "يعمل الآن" to Amber else if (vm.canStart() == null) "جاهز" to Good else null,
                logo = true, modifier = Modifier.reveal(0)
            )
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
        item(key = "ready") {
            AnimatedContent(running, transitionSpec = { Motion.fadeThrough() }, label = "ready") { busy ->
                if (busy) RunningBanner(progress ?: 0f, onShowProgress, cardPad.reveal(1))
                else ReadyCard(vm, cardPad.reveal(1))
            }
        }
    }
    val source: LazyListScope.(Int) -> Unit = { i ->
        item(key = "source") {
            SourceCard(
                vm, enabled = !running,
                onPickImage = { pickImage.launch(arrayOf("image/*")) },
                onPickFolder = { pickFolder.launch(null) },
                onPickParent = { pickParent.launch(null) },
                onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                onOpenRoi = onOpenRoi,
                modifier = cardPad.reveal(i)
            )
        }
        item(key = "output") { OutputCard(vm, enabled = !running, onPick = { pickOutput.launch(null) }, modifier = cardPad.reveal(i + 1)) }
    }
    val engine: LazyListScope.(Int) -> Unit = { i ->
        item(key = "engine") { EngineCard(vm, enabled = !running, modifier = cardPad.reveal(i)) }
    }

    if (chrome.twoPane) {
        Row(Modifier.fillMaxSize().padding(start = chrome.start).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(top = chrome.top + 8.dp, bottom = chrome.bottom)
            ) {
                header(); source(2)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(top = chrome.top + 8.dp, bottom = chrome.bottom)
            ) {
                engine(2)
            }
        }
    } else {
        Box(Modifier.fillMaxSize().padding(start = chrome.start), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = COLUMN_MAX).fillMaxSize().imePadding(),
                contentPadding = PaddingValues(top = chrome.top + 8.dp, bottom = chrome.bottom)
            ) {
                header(); engine(2); source(3)
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

/** Title of a tab: the app's mark (on the first tab), the title, one line under it, its status. */
@Composable
fun TabHeader(
    title: String,
    subtitle: String,
    status: Pair<String, Color>? = null,
    logo: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (logo) {
            Box(
                Modifier
                    .size(50.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = scheme.primary, spotColor = scheme.primary)
                    .background(Brush.linearGradient(listOf(Color(0xFF0B4F4A), Color(0xFF07201E))), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.abdulrahman.hardsubocr.R.drawable.ic_launcher_foreground), null,
                    Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(swashTitle(title), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 27.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2)
        }
        if (status != null) {
            Spacer(Modifier.width(8.dp))
            StatusPill(status.first, status.second)
        }
        trailing?.invoke()
    }
}

private fun sourceSummary(vm: MainViewModel): String = when (vm.sourceMode) {
    SourceMode.IMAGE -> vm.image?.name ?: "لم تُختر صورة"
    SourceMode.FOLDER -> vm.folder?.let { f -> f.name + (vm.folderImages?.let { " • $it صورة" } ?: "") } ?: "لم يُختر مجلد"
    SourceMode.MULTI -> if (vm.multi.isEmpty()) "لا توجد مجلدات" else "${vm.multi.size} مجلد • ${vm.multi.sumOf { it.images.coerceAtLeast(0) }} صورة"
    SourceMode.VIDEO -> vm.video?.name ?: "لم يُختر فيديو"
}

private fun sourceKind(m: SourceMode): String = when (m) {
    SourceMode.IMAGE -> "صورة"
    SourceMode.FOLDER -> "مجلد صور"
    SourceMode.MULTI -> "مجلدات متعددة"
    SourceMode.VIDEO -> "فيديو"
}

/** Label of the start button for the chosen source. */
fun startLabel(vm: MainViewModel): String =
    if (vm.sourceMode == SourceMode.VIDEO && vm.videoMode == VideoMode.FRAMES_ONLY) "حفظ الصور" else "استخراج"

/**
 * Is everything set for a job: the engine (and its key), the source, the output folder — each on a
 * line with a green or amber mark, and what is still missing in the title.
 */
@Composable
private fun ReadyCard(vm: MainViewModel, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val problem = vm.canStart()
    val imagesOnly = vm.sourceMode == SourceMode.VIDEO && vm.videoMode == VideoMode.FRAMES_ONLY
    val keyOk = imagesOnly || when (vm.engine) {
        EngineKind.GEMINI -> vm.geminiKey.isNotBlank()
        EngineKind.OPENROUTER -> vm.openRouterKey.isNotBlank()
        EngineKind.VISION -> vm.visionKey.isNotBlank()
        EngineKind.LENS -> true
    }
    val sourceOk = when (vm.sourceMode) {
        SourceMode.IMAGE -> vm.image != null
        SourceMode.FOLDER -> vm.folder != null
        SourceMode.MULTI -> vm.multi.isNotEmpty()
        SourceMode.VIDEO -> vm.video != null
    }
    val outputOk = vm.output != null || vm.sourceMode != SourceMode.VIDEO
    val model = when (vm.engine) {
        EngineKind.GEMINI -> " • " + vm.geminiModel
        EngineKind.OPENROUTER -> " • " + vm.openRouterModel.substringAfter('/')
        EngineKind.LENS, EngineKind.VISION -> ""
    }
    val accent by animateColorAsState(if (problem == null) Good else Amber, Motion.quick(), label = "readyAccent")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(scheme.primaryContainer.copy(alpha = if (isDarkScheme()) 0.55f else 0.7f), scheme.surfaceContainer)
                )
            )
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
            .padding(18.dp)
            .animateContentSize(Motion.soft())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                swashTitle(if (problem == null) "جاهز للاستخراج" else problem.removeSuffix(".")),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        ReadyLine("المحرك", engineName(vm.engine) + model, keyOk)
        ReadyLine(sourceKind(vm.sourceMode), sourceSummary(vm), sourceOk)
        ReadyLine(
            "الحفظ",
            vm.output?.name ?: if (vm.sourceMode == SourceMode.VIDEO) "اختر مجلدًا" else "بجانب الصور",
            outputOk
        )
    }
}

@Composable
private fun ReadyLine(label: String, value: String, ok: Boolean) {
    val c by animateColorAsState(if (ok) Good else Amber, Motion.quick(), label = "readyLine")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, Modifier.size(18.dp), tint = c)
        Spacer(Modifier.width(10.dp))
        Text(swashTitle(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** While a job runs, the extraction tab says so and leads to its progress; its cards are locked. */
@Composable
private fun RunningBanner(fraction: Float, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.soft(), label = "banner")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Amber.copy(alpha = 0.14f))
            .border(1.dp, Amber.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
            .clickable(onClick = onShow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { f }, modifier = Modifier.fillMaxSize(), color = Amber, strokeWidth = 4.dp,
                trackColor = scheme.surfaceContainerHighest, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Text(String.format(Locale.US, "%.0f%%", f * 100), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("استخراج قيد التشغيل", style = MaterialTheme.typography.titleMedium)
            Hint("الإعدادات مقفلة حتى ينتهي أو توقفه.")
        }
        TextButton(onClick = onShow) { Text("عرض التقدم") }
    }
}

/** «حفظ / مشاركة ملف التشخيص» from the crash banner and the error card. */
class DiagActions(val save: () -> Unit, val share: () -> Unit)

@Composable
internal fun DiagButtons(diag: DiagActions) {
    Hint("ملف التشخيص يحتوي خطوات المعالجة والأخطاء ومعلومات الجهاز، بدون أي مفتاح API.", Modifier.padding(top = 4.dp))
    RowSpaced {
        OutlinedButton(onClick = diag.save, modifier = Modifier.weight(1f)) { Text("💾 حفظ ملف التشخيص") }
        OutlinedButton(onClick = diag.share, modifier = Modifier.weight(1f)) { Text("↗ إرساله") }
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
            Text(swashTitle("أُغلق التطبيق بشكل غير متوقع في المرة السابقة"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
        val modes = listOf(SourceMode.IMAGE, SourceMode.FOLDER, SourceMode.MULTI, SourceMode.VIDEO)
        LiquidTiles(
            listOf(
                TileSpec("🖼", "صورة", "صورة واحدة"),
                TileSpec("📁", "مجلد صور", "صور بتوقيت في الاسم"),
                TileSpec("🗂", "مجلدات متعددة", "حلقات كاملة دفعة واحدة"),
                TileSpec("🎬", "فيديو", "اكتشاف تلقائي للترجمة")
            ),
            selected = modes.indexOf(vm.sourceMode),
            enabled = enabled
        ) { vm.chooseSource(modes[it]) }
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
                                Hint("${row.images} صورة → ${row.folder.name}\u2066.srt\u2069")
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
internal fun ResultCard(
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
        Text(swashTitle(title), fontWeight = FontWeight.Bold, color = if (good) Good else MaterialTheme.colorScheme.error)
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
                        if (o.plainText != null || o.imagesSaved > 0) o.name else "${o.name}\u2066.srt\u2069", fontWeight = FontWeight.SemiBold,
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
        title = { Text(swashTitle("اختيار مجلدات الحلقات")) },
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
