package com.abdulrahman.hardsubocr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Bad
import com.abdulrahman.hardsubocr.ui.theme.Good
import java.util.Locale

/**
 * The processing tab: the running job's dashboard, or the last job's result, with the log under it
 * (kept after the job ends). On a wide screen the dashboard and the log stand side by side, each
 * scrolling on its own.
 */
@Composable
fun ProcessTab(vm: MainViewModel, state: JobState, onOpenEditor: (Int) -> Unit, onGoExtract: () -> Unit) {
    val context = LocalContext.current
    val chrome = LocalChrome.current
    var logFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val saveDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.saveDiagnostics(it) }
    var saveAsIndex by remember { mutableIntStateOf(-1) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) {
        vm.saveOutputAs(saveAsIndex, it)
    }
    val onSave: (Int, String) -> Unit = { i, name ->
        if (!vm.saveOutput(i)) {
            saveAsIndex = i; saveAs.launch("$name.srt")
        }
    }
    val diag = DiagActions(save = { saveDiag.launch(vm.diagFileName()) }, share = { vm.shareDiagnostics(context) })
    val log = (state as? JobState.Running)?.progress?.log ?: vm.lastLog

    val (subtitle, status) = when (state) {
        is JobState.Running -> (state.progress.phase.ifEmpty { state.progress.title }.ifEmpty { "جاري الاستخراج" }) to
            (String.format(Locale.US, "%.1f%%", state.progress.fraction * 100) to Amber)
        is JobState.Done -> ("المدة " + Timestamps.human(state.elapsedSec)) to ((if (state.stopped) "أُوقف" else "اكتمل") to (if (state.stopped) Amber else Good))
        is JobState.Error -> "انتهت المهمة بخطأ" to ("خطأ" to Bad)
        JobState.Idle -> "لا توجد مهمة الآن" to null
    }

    val header: LazyListScope.() -> Unit = {
        item(key = "header") { TabHeader("المعالجة", subtitle, status, modifier = Modifier.reveal(0)) }
    }
    val main: LazyListScope.() -> Unit = {
        item(key = "state") {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { Motion.fadeThrough() using SizeTransform(clip = false) },
                label = "jobState"
            ) { st ->
                when (st) {
                    is JobState.Running -> ProgressCard(st.progress, cardPad.reveal(1))
                    is JobState.Done -> ResultCard(
                        title = st.summary, good = !st.stopped, elapsed = st.elapsedSec, details = null,
                        outputs = st.outputs, onEdit = onOpenEditor,
                        onShare = { uri -> shareSrt(context, uri) }, onSave = onSave,
                        onClose = { vm.dismissResult() }, diag = null, modifier = cardPad.reveal(1)
                    )
                    is JobState.Error -> ResultCard(
                        title = st.message, good = false, elapsed = null, details = st.details,
                        outputs = st.outputs, onEdit = onOpenEditor,
                        onShare = { uri -> shareSrt(context, uri) }, onSave = onSave,
                        onClose = { vm.dismissResult() }, diag = diag, modifier = cardPad.reveal(1)
                    )
                    JobState.Idle -> EmptyJob(onGoExtract, cardPad.reveal(1))
                }
            }
        }
    }
    val logs: LazyListScope.() -> Unit = {
        if (log.isNotEmpty() || state is JobState.Running) logSection(log, logFilter) { logFilter = it }
    }

    if (chrome.twoPane) {
        Row(Modifier.fillMaxSize().padding(start = chrome.start)) {
            LazyColumn(
                Modifier.weight(0.48f).fillMaxHeight(),
                contentPadding = PaddingValues(top = chrome.top + 8.dp, bottom = chrome.bottom)
            ) { header(); main() }
            LazyColumn(
                Modifier.weight(0.52f).fillMaxHeight(),
                contentPadding = PaddingValues(top = chrome.top + 22.dp, bottom = chrome.bottom)
            ) {
                logs()
                if (log.isEmpty() && state !is JobState.Running) item(key = "noLog") {
                    Hint("سجل العمليات يظهر هنا أثناء الاستخراج ويبقى بعده.", Modifier.padding(24.dp).reveal(2))
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().padding(start = chrome.start), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = COLUMN_MAX).fillMaxSize(),
                contentPadding = PaddingValues(top = chrome.top + 8.dp, bottom = chrome.bottom)
            ) { header(); main(); logs() }
        }
    }
}

/** No job yet: what this tab will show, and the way to start one. */
@Composable
private fun EmptyJob(onGoExtract: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(76.dp)
                .background(Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer)), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(AppIcons.Activity, null, Modifier.size(38.dp), tint = scheme.onPrimaryContainer) }
        Spacer(Modifier.height(4.dp))
        Text("لا توجد مهمة الآن", fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(
            "اختر المحرك والمصدر من «الاستخراج» ثم اضغط زر البدء. يظهر هنا تقدّم المهمة وسجلها، ثم ملفات الترجمة الناتجة.",
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(onClick = onGoExtract) { Text("الذهاب إلى الاستخراج") }
    }
}
