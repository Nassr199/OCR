package com.abdulrahman.hardsubocr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.job.LogLine
import com.abdulrahman.hardsubocr.job.Progress
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Bad
import com.abdulrahman.hardsubocr.ui.theme.Good
import java.util.Locale
import com.abdulrahman.hardsubocr.ui.theme.swashTitle

private fun n(v: Int) = String.format(Locale.US, "%,d", v)

@Composable
fun ProgressCard(p: Progress, modifier: Modifier = Modifier) {
    SectionCard(
        title = if (p.unitCount > 1) "${p.title}  (${p.unitIndex + 1}/${p.unitCount})" else p.title.ifEmpty { "جاري الاستخراج" },
        emoji = "⚙️",
        modifier = modifier,
        trailing = { Hint("⏱ " + Timestamps.human(p.elapsedSec)) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(p.fraction, "الإجمالي")
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.phase.isNotEmpty()) Text(p.phase, fontWeight = FontWeight.SemiBold, maxLines = 3)
                Text(
                    when {
                        p.stage == 1 -> "${n(p.subtitlesFound)} صورة ترجمة حتى الآن"
                        p.total > 0 -> "${n(p.done)} من ${n(p.total)} صورة"
                        else -> "${n(p.done)} صورة"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (p.speed > 0) Hint(String.format(Locale.US, "⚡ %.2f صورة/ث", p.speed))
                Hint((if (p.stage == 1) "⏳ المتبقي للصور: " else "⏳ المتبقي: ") + if (p.etaSec.isNaN()) "يُحسب…" else Timestamps.human(p.etaSec))
            }
        }
        p.decodeFraction?.let { d ->
            Spacer(Modifier.height(10.dp))
            ProgressLine(
                if (p.stage > 0) "المرحلة 1 · استخراج الصور" else "تحليل الفيديو",
                String.format(Locale.US, "%.1f%%", d.coerceIn(0f, 1f) * 100) +
                    (if (p.frames > 0) " • ${n(p.frames)} إطار" else "") +
                    (if (p.framesPerSec > 0) String.format(Locale.US, " • %.0f/ث", p.framesPerSec) else ""),
                d, Amber
            )
            ProgressLine(
                if (p.stage > 0) "المرحلة 2 · قراءة النصوص" else "قراءة النصوص",
                if (p.stage == 1) "تبدأ بعد اكتمال الصور"
                else "${n(p.done)} / ${n(maxOf(p.total, p.subtitlesFound))}" + (if (p.subtitlesFound > 0) " • ${n(p.subtitlesFound)} ترجمة" else ""),
                if (p.stage == 1 || p.total <= 0) 0f else p.done.toFloat() / p.total,
                MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(12.dp))
        RowSpaced {
            StatTile("ناجحة", n(p.ok), Good, Modifier.weight(1f))
            StatTile("فارغة", n(p.empty), Amber, Modifier.weight(1f))
            StatTile("فاشلة", n(p.failed), if (p.failed > 0) Bad else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
        if (p.cached > 0 || p.retries > 0) {
            Spacer(Modifier.height(6.dp))
            Hint("♻ من ذاكرة الاستئناف: ${n(p.cached)}   •   ↻ إعادات: ${n(p.retries)}")
        }
    }
}

enum class LogFilter { ALL, PROBLEMS, TEXT }

private fun LogFilter.keep(line: String): Boolean = when (this) {
    LogFilter.ALL -> true
    LogFilter.PROBLEMS -> line.startsWith("✖") || line.startsWith("↻") || line.contains("⚠")
    LogFilter.TEXT -> line.startsWith("✓") || line.startsWith("♻")
}

/**
 * The live log as list items of the screen itself: the whole page scrolls as one, new lines appear
 * at the top without moving what you are reading (items are keyed), and nothing is cut to 10 lines.
 */
fun LazyListScope.logSection(lines: List<LogLine>, filter: LogFilter, onFilter: (LogFilter) -> Unit) {
    val shown = lines.filter { filter.keep(it.text) }
    item(key = "logHeader") {
        LogHeader(lines, shown.size, filter, onFilter)
    }
    items(shown, key = { it.id }) { line ->
        val first = line.id == shown.first().id
        val last = line.id == shown.last().id
        Text(
            line.text,
            modifier = Modifier
                .animateItem()
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(
                    Color(0xFF07100F),
                    RoundedCornerShape(
                        topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                        bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .padding(top = if (first) 8.dp else 0.dp, bottom = if (last) 8.dp else 0.dp),
            fontSize = 11.sp,
            color = logColor(line.text)
        )
    }
    if (shown.isEmpty()) item(key = "logEmpty") {
        Hint("لا توجد أسطر هنا بعد.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

@Composable
private fun LogHeader(lines: List<LogLine>, shown: Int, filter: LogFilter, onFilter: (LogFilter) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(swashTitle("سجل العمليات"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Hint("$shown سطر")
            TextButton(onClick = { clipboard.setText(AnnotatedString(lines.reversed().joinToString("\n") { it.text })) }) { Text("📋 نسخ") }
        }
        Segmented(
            listOf(LogFilter.ALL to "الكل", LogFilter.TEXT to "النصوص", LogFilter.PROBLEMS to "المشاكل"),
            filter
        ) { onFilter(it) }
    }
}
