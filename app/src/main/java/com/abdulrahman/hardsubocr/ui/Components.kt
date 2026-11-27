@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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

// ============================================================================ motion

/** One motion language for the whole app: short, calm, no bounce on content. */
object Motion {
    const val FAST = 160
    const val NORMAL = 240
    /** Page to page (Material shared axis): 300 ms, 30 dp. */
    const val PAGE = 300
    val AXIS_DISTANCE = 30.dp
    fun <T> soft() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> quick() = tween<T>(NORMAL, easing = FastOutSlowInEasing)

    /**
     * Material shared-axis X between a page and the page it opens. Forward moves along the reading
     * direction: the new page comes from the end side (from the left in Arabic) while the old one
     * fades out in the first 90 ms; back is the mirror image.
     */
    /**
     * Material fade-through between tabs and between the states of a card: the old content fades out
     * in 90 ms, the new one fades in and grows from 94 %.
     */
    fun fadeThrough(): ContentTransform =
        (fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(280, delayMillis = 90, easing = FastOutSlowInEasing), initialScale = 0.94f)) togetherWith
            fadeOut(tween(90, easing = FastOutLinearInEasing))

    fun sharedAxisX(forward: Boolean, rtl: Boolean, distancePx: Int): ContentTransform {
        val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
        val enter = slideInHorizontally(tween(PAGE, easing = FastOutSlowInEasing)) { sign * distancePx } +
            fadeIn(tween(PAGE - 90, delayMillis = 90, easing = LinearOutSlowInEasing))
        val exit = slideOutHorizontally(tween(PAGE, easing = FastOutSlowInEasing)) { -sign * distancePx } +
            fadeOut(tween(90, easing = FastOutLinearInEasing))
        return enter togetherWith exit
    }
}

/** Slight press-in feedback for big tappable surfaces. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.soft(), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

// ============================================================================ surfaces

/**
 * A card as a slab of glass over the app's background: a translucent fill that lets the background's
 * glow through, a faint sheen at the top, and a hairline rim lit from above.
 */
@Composable
fun Modifier.glassCard(shape: Shape = RoundedCornerShape(24.dp)): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val fill = scheme.surfaceContainer.copy(alpha = if (dark) 0.80f else 0.78f)
    val sheen = Color.White.copy(alpha = if (dark) 0.045f else 0.32f)
    val rimTop = Color.White.copy(alpha = if (dark) 0.15f else 0.85f)
    val rimBottom = Color.White.copy(alpha = if (dark) 0.03f else 0.25f)
    return this
        .clip(shape)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, fill)
            drawOutline(outline, Brush.verticalGradient(listOf(sheen, Color.Transparent), 0f, size.height * 0.45f))
            drawOutline(outline, Brush.verticalGradient(listOf(rimTop, rimBottom)), style = Stroke(1.dp.toPx()))
        }
}

@Composable
fun SectionCard(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    collapsible: Boolean = false,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(title) { mutableStateOf(true) }
    val arrow by animateFloatAsState(if (open) 0f else -90f, Motion.quick(), label = "arrow")
    Column(
        modifier
            .fillMaxWidth()
            .glassCard()
            .animateContentSize(Motion.soft())
            .padding(16.dp)
    ) {
        Row(
            (if (collapsible) Modifier.clickable { open = !open } else Modifier).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != null) Hint(subtitle, maxLines = 1)
            }
            trailing?.invoke()
            if (collapsible) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, if (open) "طي" else "فتح",
                    Modifier.rotate(arrow), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(
            visible = open || !collapsible,
            enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

// ============================================================================ settings list

/** Title above a group of settings rows or cards (Material list subheader, 16 dp inset). */
@Composable
fun GroupTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
}

/** Rows of one settings group on one rounded surface; rows are separated by inset dividers. */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        Column(
            Modifier
                .fillMaxWidth()
                .glassCard(),
            content = content
        )
    }
}

/** Controls of a settings page on one rounded surface with 16 dp padding. */
@Composable
fun SettingsCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        Column(
            Modifier
                .fillMaxWidth()
                .glassCard()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content
        )
    }
}

/**
 * Two-line list item (72 dp): 40 dp icon tile, title, what it is set to now, and a chevron that
 * points the way the page opens (left in Arabic).
 */
@Composable
fun SettingsRow(emoji: String, title: String, summary: String, divider: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent, Motion.quick(), label = "rowSel")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer)),
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 18.sp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = scheme.onSurfaceVariant)
    }
    if (divider) {
        HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 1.dp, color = scheme.outlineVariant.copy(alpha = 0.5f))
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
fun AppHeader(statusText: String, statusColor: Color, onSettings: () -> Unit, busy: Boolean = false) {
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
                StatusPill(statusText, statusColor, busy)
            }
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "الإعدادات")
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color, busy: Boolean = false) {
    val c by animateColorAsState(color, Motion.quick(), label = "pill")
    Row(
        Modifier
            .background(c.copy(alpha = 0.16f), RoundedCornerShape(50))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .animateContentSize(Motion.soft()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Big selectable tile (engine / source choice). [ringed]: the tile sits in a [LiquidTiles] grid, whose
 * lens draws the selected ring, so the tile draws only its own surface.
 */
@Composable
fun SelectTile(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    ringed: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(
        if (selected) scheme.primaryContainer.copy(alpha = if (ringed) 0.42f else 0.55f) else scheme.surfaceContainerHigh, Motion.quick(), label = "tileBg"
    )
    val edge by animateColorAsState(
        if (selected && !ringed) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f), Motion.quick(), label = "tileEdge"
    )
    val edgeW by animateDpAsState(if (selected && !ringed) 2.dp else 1.dp, Motion.quick(), label = "tileEdgeW")
    Column(
        modifier
            .pressScale(src)
            .background(bg, shape)
            .border(edgeW, edge, shape)
            .clip(shape)
            .selectable(selected = selected, interactionSource = src, indication = LocalIndication.current, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(4.dp))
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

/** Counter tile; a changed value slides in. */
@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(Motion.quick()) { it / 2 } + fadeIn(Motion.quick())) togetherWith
                    (slideOutVertically(Motion.quick()) { -it / 2 } + fadeOut(tween(Motion.FAST)))
            },
            label = "stat"
        ) { v -> Text(v, color = color, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), maxLines = 1) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Big determinate ring with the percentage in the middle. The value glides between the job's
 * updates (published every 300 ms) instead of jumping.
 */
@Composable
fun ProgressRing(fraction: Float, label: String, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(320, easing = LinearEasing), label = "ring")
    Box(modifier.size(118.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { f },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 11.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(String.format(java.util.Locale.US, "%.1f%%", f * 100), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Labelled thin progress bar (video analysis, reading) with the same gliding value. */
@Composable
fun ProgressLine(title: String, detail: String, fraction: Float, color: Color) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(320, easing = LinearEasing), label = "bar")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { f },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round
        )
    }
}

/** Colour of a live-log line from its leading symbol. */
fun logColor(line: String): Color = when {
    line.startsWith("✓") -> Color(0xFF7FE3B0)
    line.startsWith("✖") -> Color(0xFFFF8A80)
    line.startsWith("↻") -> Color(0xFFFFD27F)
    line.startsWith("♻") -> Color(0xFF9CCBFF)
    else -> Color(0xFFB6C4C1)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
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
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Spacer(Modifier.width(10.dp))
        LiquidSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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
    onDone: (() -> Unit)? = null,
    startLabel: String? = null,
    endLabel: String? = null
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
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(startLabel ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(endLabel ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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

/**
 * Outcome of a test (key, GPU…): green for success, red for failure. It can be hidden («إخفاء») and
 * shown again («إظهار النتيجة») for as long as it stands; a new result shows itself.
 */
@Composable
fun ResultLine(text: String?, ok: Boolean) {
    var hidden by rememberSaveable(text) { mutableStateOf(false) }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
    ) {
        val c = if (ok) Good else MaterialTheme.colorScheme.error
        AnimatedContent(
            targetState = hidden,
            transitionSpec = { (fadeIn(tween(Motion.NORMAL, delayMillis = 60)) togetherWith fadeOut(tween(Motion.FAST))).using(SizeTransform(clip = false)) },
            label = "resultHidden"
        ) { isHidden ->
            if (isHidden) {
                TextButton(onClick = { hidden = false }, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.size(8.dp).background(c, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("إظهار النتيجة", style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(c.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text ?: "",
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        color = c,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { hidden = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Close, "إخفاء النتيجة", Modifier.size(16.dp), tint = c)
                    }
                }
            }
        }
    }
}

// ============================================================================ choices

/**
 * Segmented control (equal-width cells, labels never clipped, 2 lines max). The chosen cell is marked
 * by the liquid lens: it slides between cells, and can be dragged across them.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val index = options.indexOfFirst { it.first == selected }
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LiquidSelector(
        selected = index,
        onSelect = { i -> currentOptions.getOrNull(i)?.let { currentOnSelect(it.first) } },
        rest = remember(scheme.primary) { LensRest.fill(scheme.primary) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        axis = LensAxis.Horizontal,
        corner = 12.dp,
        growX = 0.05f,
        growY = 0.22f,
        base = scheme.surfaceContainer,
        background = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
    ) {
        val g = glass
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { i, (value, label) ->
                val on = i == index
                val sel by animateFloatAsState(if (on) 1f else 0f, Motion.quick(), label = "seg")
                val fg = lerp(scheme.onSurfaceVariant, lerp(scheme.onPrimary, scheme.onSurface, g), sel)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .liquidItem(i)
                        .selectable(selected = on, interactionSource = null, indication = null, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = fg,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.5.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

data class ChoiceItem<T>(val value: T, val title: String, val subtitle: String? = null)

/**
 * Vertical list of options (settings, where labels are long). The chosen row is marked by the liquid
 * lens; holding a row picks the lens up, and it can be slid to another row.
 */
@Composable
fun <T> ChoiceList(items: List<ChoiceItem<T>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val index = items.indexOfFirst { it.value == selected }
    val currentItems by rememberUpdatedState(items)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LiquidSelector(
        selected = index,
        onSelect = { i -> currentItems.getOrNull(i)?.let { currentOnSelect(it.value) } },
        rest = remember(scheme.primaryContainer) { LensRest.fill(scheme.primaryContainer.copy(alpha = 0.45f)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        axis = LensAxis.Vertical,
        holdToDrag = true,
        corner = 14.dp,
        growX = 0.02f,
        growY = 0.06f,
        base = scheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, it ->
                val on = i == index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .liquidItem(i)
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(
                            selected = on, interactionSource = null, indication = LocalIndication.current,
                            enabled = enabled, role = Role.RadioButton
                        ) { onSelect(it.value) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = on, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(it.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        it.subtitle?.let { sub -> Hint(sub) }
                    }
                }
            }
        }
    }
}

/** One choice of a [LiquidTiles] grid. */
class TileSpec(val emoji: String, val title: String, val subtitle: String, val badge: String? = null)

/**
 * Big choice tiles (engine, source) in a grid. The chosen tile is ringed by the liquid lens, which
 * flows from tile to tile; holding a tile picks it up, and it can be slid to another tile.
 */
@Composable
fun LiquidTiles(
    tiles: List<TileSpec>,
    selected: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    onSelect: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val currentOnSelect by rememberUpdatedState(onSelect)
    LiquidSelector(
        selected = selected,
        onSelect = { currentOnSelect(it) },
        rest = remember(scheme.primary) { LensRest.ring(scheme.primary) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        axis = LensAxis.Both,
        holdToDrag = true,
        corner = 20.dp,
        growX = 0.05f,
        growY = 0.06f,
        base = scheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(columns).forEachIndexed { row, chunk ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunk.forEachIndexed { col, t ->
                        val i = row * columns + col
                        SelectTile(
                            t.emoji, t.title, t.subtitle, i == selected, enabled,
                            Modifier.weight(1f).fillMaxHeight().liquidItem(i), badge = t.badge, ringed = true
                        ) { onSelect(i) }
                    }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
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
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(Motion.quick()) + expandVertically(Motion.soft()),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(Motion.soft())
    ) {
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
