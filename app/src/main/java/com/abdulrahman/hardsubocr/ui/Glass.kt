package com.abdulrahman.hardsubocr.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================================ frosted glass
//
// The bar and the rail are frosted glass over the screen, as in iOS's Liquid Glass and X's latest
// tab bar: what scrolls under them shows through, blurred and tinted, with a light rim. The screen's
// content is recorded once per frame into a graphics layer ([backdropSource]); each glass surface
// draws that layer again behind itself, blurred, clipped to its shape ([glass]). The blur is a GPU
// RenderEffect (Android 12+); on Android 8–11 the glass is an opaque tint with the same rim.

/** Android 12+ blurs on the GPU; before that the glass is a solid tint. */
val glassBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** What the glass shows through: the content under it, and where that content sits on the screen. */
@Stable
class Backdrop {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop = remember { Backdrop() }

/** Records the content it wraps for the glass surfaces drawn over it. */
@Composable
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    if (!glassBlur) return this
    val layer = rememberGraphicsLayer()
    SideEffect { backdrop.layer = layer }
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/** True when the app shows its dark scheme. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Frosted glass behind the element: the backdrop blurred by [blur], a [tint] over it, a rim lit from
 * above. Under Android 12 the tint alone, nearly opaque.
 */
@Composable
fun Modifier.glass(backdrop: Backdrop, shape: Shape, tint: Color, blur: Dp = 30.dp, rim: Boolean = true): Modifier {
    val dark = isDarkScheme()
    val blurLayer = if (glassBlur) rememberGraphicsLayer() else null
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurPx = with(LocalDensity.current) { blur.toPx() }
    val rimTop = if (dark) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.95f)
    val rimBottom = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.35f)
    val solid = tint.copy(alpha = max(tint.alpha, if (dark) 0.95f else 0.94f))
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            val src = backdrop.layer
            if (blurLayer != null && src != null) {
                blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                val o = origin - backdrop.origin
                blurLayer.record { translate(-o.x, -o.y) { drawLayer(src) } }
                clipPath(path) { drawLayer(blurLayer) }
                drawPath(path, tint)
            } else {
                drawPath(path, solid)
            }
            if (rim) drawPath(path, Brush.verticalGradient(listOf(rimTop, rimBottom)), style = Stroke(1.dp.toPx()))
        }
}

/** The tint of the bar and the rail. */
@Composable
fun glassTint(): Color =
    if (isDarkScheme()) Color(0xFF0E1817).copy(alpha = 0.58f) else Color(0xFFF6FBFA).copy(alpha = 0.62f)

// ============================================================================ the liquid tab bar

class NavItem(val label: String, val icon: ImageVector, val progress: Float? = null)

/**
 * The tabs on frosted glass, with a liquid lens under the selected one. The lens moves on a spring
 * and stretches with its speed along the way, squeezing a little across it, like a drop; it grows
 * while pressed and can be dragged across the tabs, landing on the nearest one. Horizontal at the
 * bottom of a phone, vertical at the side of a wide screen.
 */
@Composable
fun GlassTabs(
    backdrop: Backdrop,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = isDarkScheme()
    val primary = MaterialTheme.colorScheme.primary
    val lensFill = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.055f)
    val lensRim = if (dark) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.9f)
    val pos = remember { Animatable(selected.toFloat()) }
    val grow = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }
    val lensSpring = spring<Float>(dampingRatio = 0.58f, stiffness = 380f)
    LaunchedEffect(selected) { if (!dragging) pos.animateTo(selected.toFloat(), lensSpring) }

    val shape = RoundedCornerShape(percent = 50)
    val itemW = if (vertical) 66.dp else 70.dp
    val itemH = if (vertical) 62.dp else 56.dp
    val n = items.size
    val container: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (vertical) Column(verticalArrangement = Arrangement.Center) { content() } else Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
    Box(
        modifier
            .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .glass(backdrop, shape, glassTint())
            .padding(5.dp)
    ) {
        Box(
            Modifier
                .drawBehind {
                    val step = if (vertical) size.height / n else size.width / n
                    val p = pos.value.coerceIn(-0.35f, n - 0.65f)
                    val stretch = (abs(pos.velocity) * 0.055f).coerceAtMost(0.42f)
                    val g = grow.value
                    var w = if (vertical) size.width else step
                    var h = if (vertical) step else size.height
                    if (vertical) { h *= 1 + stretch; w *= 1 - stretch * 0.16f } else { w *= 1 + stretch; h *= 1 - stretch * 0.16f }
                    w *= g; h *= g
                    val along = step * (p + 0.5f)
                    val c = if (vertical) Offset(size.width / 2, along) else Offset(if (rtl) size.width - along else along, size.height / 2)
                    val tl = Offset(c.x - w / 2, c.y - h / 2)
                    val r = CornerRadius(min(w, h) / 2)
                    drawCircle(
                        Brush.radialGradient(listOf(primary.copy(alpha = if (dark) 0.34f else 0.22f), Color.Transparent), c, max(w, h) * 0.62f),
                        max(w, h) * 0.62f, c
                    )
                    drawRoundRect(lensFill, tl, Size(w, h), r)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(lensRim, lensRim.copy(alpha = lensRim.alpha * 0.25f)), tl.y, tl.y + h),
                        tl, Size(w, h), r, style = Stroke(1.dp.toPx())
                    )
                }
                .pointerInput(n, vertical, rtl) {
                    val step = if (vertical) size.height.toFloat() / n else size.width.toFloat() / n
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            scope.launch { grow.animateTo(1.1f, spring(0.6f, Spring.StiffnessMedium)) }
                        },
                        onDragEnd = {
                            dragging = false
                            val target = pos.value.roundToInt().coerceIn(0, n - 1)
                            scope.launch { grow.animateTo(1f, spring(0.55f, Spring.StiffnessMedium)) }
                            scope.launch { pos.animateTo(target.toFloat(), lensSpring) }
                            if (target != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(target)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { grow.animateTo(1f); pos.animateTo(selected.toFloat(), lensSpring) }
                        }
                    ) { change, drag ->
                        change.consume()
                        val d = if (vertical) drag.y / step else (if (rtl) -drag.x else drag.x) / step
                        scope.launch { pos.snapTo((pos.value + d).coerceIn(-0.35f, n - 0.65f)) }
                    }
                }
        ) {
            container {
                items.forEachIndexed { i, item ->
                    val on = i == selected
                    val src = remember { MutableInteractionSource() }
                    val pressed by src.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "tabPress")
                    val tint by animateColorAsState(
                        if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant, Motion.quick(), label = "tabTint"
                    )
                    Column(
                        Modifier
                            .size(itemW, itemH)
                            .clickable(interactionSource = src, indication = null) {
                                if (i != selected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(i)
                                }
                            }
                            .semantics { this.selected = on; this.contentDescription = item.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale },
                            contentAlignment = Alignment.Center
                        ) {
                            item.progress?.let { f -> ProgressHalo(f, tint) }
                            Icon(item.icon, null, Modifier.size(if (item.progress != null) 18.dp else 24.dp), tint = tint)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label, fontSize = 11.sp, color = tint, maxLines = 1,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** A thin ring of the job's progress around the processing tab's icon. */
@Composable
private fun ProgressHalo(fraction: Float, color: Color) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.soft(), label = "halo")
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val sw = 2.5.dp.toPx()
                val inset = sw / 2
                val arcSize = Size(size.width - sw, size.height - sw)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
                drawArc(color, -90f, 360f * f, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
    )
}

// ============================================================================ the main action

/**
 * The start / stop button beside the tabs: tinted glass (teal to start, red to stop), a circle, or a
 * pill with its label when there is room. Its colour, icon and width move on springs.
 */
@Composable
fun GlassAction(
    backdrop: Backdrop,
    running: Boolean,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 66.dp
) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(0.45f, Spring.StiffnessMedium), label = "actionPress")
    val tint by animateColorAsState(
        if (running) Color(0xFFE5484D).copy(alpha = 0.86f) else scheme.primary.copy(alpha = 0.9f), Motion.quick(), label = "actionTint"
    )
    val onTint = if (running) Color.White else scheme.onPrimary
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(16.dp, shape, clip = false, ambientColor = tint, spotColor = tint)
            .glass(backdrop, shape, tint)
            .clickable(interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .heightIn(min = size)
            .widthIn(min = size)
            .animateContentSize(spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = running to label,
            transitionSpec = {
                (fadeIn(Motion.quick()) + scaleIn(spring(0.6f, Spring.StiffnessMedium), initialScale = 0.6f)) togetherWith
                    (fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.6f))
            },
            label = "actionContent"
        ) { (isRunning, text) ->
            Row(
                Modifier.padding(horizontal = if (text != null) 20.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (isRunning) AppIcons.Stop else AppIcons.Play, if (isRunning) "إيقاف" else "استخراج", Modifier.size(26.dp), tint = onTint)
                if (text != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = onTint, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                }
            }
        }
    }
}
