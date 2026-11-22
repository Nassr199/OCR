@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.R
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Good

// ============================================================================ surfaces

@Composable
fun SectionCard(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .animateContentSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 17.sp) }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

/** Gradient header that runs under the status bar. */
@Composable
fun AppHeader(statusText: String, statusColor: Color, onSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surface)),
                RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
            )
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(Color(0xFF0B3D3C), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HardSub OCR", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "استخراج الترجمة العربية المدمجة",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                StatusPill(statusText, statusColor)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "الإعدادات") }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Big selectable tile (engine / source choice). */
@Composable
fun SelectTile(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .background(if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerHigh, shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f),
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
        )
        Text(
            subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
            maxLines = 2, textAlign = TextAlign.Center
        )
        if (badge != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                badge,
                modifier = Modifier
                    .background(Amber.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 1.dp),
                color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CheckLine(text: String, color: Color = Good) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Big determinate ring with the percentage in the middle. */
@Composable
fun ProgressRing(fraction: Float, label: String, modifier: Modifier = Modifier) {
    Box(modifier.size(118.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 11.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(fraction * 100).toInt()}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Terminal-style live log. */
@Composable
fun TerminalLog(lines: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 230.dp)
            .background(Color(0xFF07100F), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        for (line in lines) {
            val c = when {
                line.startsWith("✓") -> Color(0xFF7FE3B0)
                line.startsWith("✖") -> Color(0xFFFF8A80)
                line.startsWith("↻") -> Color(0xFFFFD27F)
                line.startsWith("♻") -> Color(0xFF9CCBFF)
                else -> Color(0xFFB6C4C1)
            }
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c)
        }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun PathChip(text: String, modifier: Modifier = Modifier, empty: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
    onDone: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onDone
        )
    }
}

@Composable
fun CenterNote(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun RowSpaced(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

// ============================================================================ choices

/** Compact segmented control that never clips its labels (equal-width cells, 2 lines max). */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(if (on) scheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

data class ChoiceItem<T>(val value: T, val title: String, val subtitle: String? = null)

/** Vertical radio list, used where labels are long (settings). */
@Composable
fun <T> ChoiceList(items: List<ChoiceItem<T>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        for (it in items) {
            val on = it.value == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(enabled = enabled) { onSelect(it.value) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = on, onClick = { onSelect(it.value) }, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    it.subtitle?.let { sub -> Hint(sub) }
                }
            }
        }
    }
}

// ============================================================================ error details

/** Expandable technical details with a copy button (for error reports). */
@Composable
fun ErrorDetails(details: String, startExpanded: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(startExpanded) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = !open }) { Text(if (open) "▲ إخفاء التفاصيل التقنية" else "▼ عرض التفاصيل التقنية") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) { Text("📋 نسخ التقرير") }
    }
    if (open) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .background(Color(0xFF07100F), RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            // Stack traces are English: lay them out left-to-right inside the RTL app.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SelectionContainer {
                    Text(details, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = Color(0xFFFFB4AB))
                }
            }
        }
    }
}
