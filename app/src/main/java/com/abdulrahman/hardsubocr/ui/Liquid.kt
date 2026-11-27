package com.abdulrahman.hardsubocr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ============================================================================ the liquid lens
//
// Every choice in the app — the tabs, the segmented controls, the tiles, the lists of options — is
// marked by one lens that belongs to the control, not to an item. At rest it is the control's quiet
// selected look. It moves between items on a spring, stretching along its path and squeezing across
// it like a drop, and while it moves fast it turns into clear liquid glass. Pressed and held it lifts
// out of the control, grows, magnifies what is under it and follows the finger; let go, it lands on
// the nearest item and chooses it. On Android 13+ the glass is the shader of LiquidGlass.kt; earlier,
// a light lens of tint and rim.

enum class LensAxis { Horizontal, Vertical, Both }

/** How the chosen item looks at rest: drawn under the items ([below]) or over them. */
class LensRest(val below: Boolean, val draw: DrawScope.(rect: Rect, corner: Float, alpha: Float) -> Unit) {
    companion object {
        /** A filled lens under the items. */
        fun fill(color: Color) = LensRest(true) { r, c, a ->
            drawRoundRect(color.copy(alpha = color.alpha * a), r.topLeft, r.size, CornerRadius(c))
        }

        /** A ring with a light wash over the items (tiles that have their own surface). */
        fun ring(color: Color, width: Dp = 2.dp) = LensRest(false) { r, c, a ->
            drawRoundRect(color.copy(alpha = 0.10f * a), r.topLeft, r.size, CornerRadius(c))
            val w = width.toPx()
            drawRoundRect(
                color.copy(alpha = a), Offset(r.left + w / 2, r.top + w / 2), Size(r.width - w, r.height - w),
                CornerRadius(max(c - w / 2, 0f)), style = Stroke(w)
            )
        }
    }
}

/** Hold this long without moving to pick the lens up in a control inside a scrolling page. */
private const val HOLD_MS = 280L

/** Speed (dp/s) at which a moving lens is all glass. */
private const val GLASS_SPEED = 1300f

@Stable
internal class SelectorState(val scope: CoroutineScope) {
    val items = HashMap<Int, LayoutCoordinates>()
    var host: LayoutCoordinates? = null
    var selected = -1
    var placed by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    val center = Animatable(Offset.Zero, Offset.VectorConverter)
    val size = Animatable(Size.Zero, Size.VectorConverter)
    val held = Animatable(0f)

    private val moveSpec: AnimationSpec<Offset> = spring(dampingRatio = 0.62f, stiffness = 380f)
    private val resizeSpec: AnimationSpec<Size> = spring(dampingRatio = 0.72f, stiffness = 420f)
    private val followSpec: AnimationSpec<Offset> = spring(dampingRatio = 0.82f, stiffness = 1400f)
    private val followSizeSpec: AnimationSpec<Size> = spring(dampingRatio = 0.8f, stiffness = 800f)

    fun rect(i: Int): Rect? {
        val h = host ?: return null
        val c = items[i] ?: return null
        if (!h.isAttached || !c.isAttached) return null
        return h.localBoundingBoxOf(c, clipBounds = false)
    }

    fun indexAt(p: Offset): Int? = items.keys.firstOrNull { rect(it)?.contains(p) == true }

    fun nearest(p: Offset): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (k in items.keys) {
            val r = rect(k) ?: continue
            val d = (r.center - p).getDistanceSquared()
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Puts the lens on item [i] at once (first layout, a new size of the control). */
    fun snapTo(i: Int) {
        val r = rect(i) ?: return
        if (placed && (center.value - r.center).getDistance() < 0.5f && abs(size.value.width - r.width) < 0.5f && abs(size.value.height - r.height) < 0.5f) return
        scope.launch {
            center.snapTo(r.center)
            size.snapTo(r.size)
            placed = true
        }
    }

    suspend fun moveTo(i: Int) {
        val r = rect(i) ?: return
        if (!placed) {
            center.snapTo(r.center)
            size.snapTo(r.size)
            placed = true
            return
        }
        coroutineScope {
            launch { size.animateTo(r.size, resizeSpec) }
            center.animateTo(r.center, moveSpec)
        }
    }

    /** After a drag: lands on [target]; if the choice was not taken, goes back to the chosen item. */
    fun settle(target: Int) {
        scope.launch {
            moveTo(target)
            if (!dragging && selected != target && selected >= 0) moveTo(selected)
        }
    }

    fun press(to: Float) {
        scope.launch { held.animateTo(to, spring(dampingRatio = 0.6f, stiffness = 650f)) }
    }

    fun release() {
        scope.launch { held.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
    }

    /** The lens follows the finger, held to the row (or column) of items. */
    fun follow(p: Offset, axis: LensAxis) {
        val rs = items.keys.mapNotNull { rect(it) }
        if (rs.isEmpty()) return
        val ox = rs[0].width * 0.3f
        val oy = rs[0].height * 0.3f
        val minX = rs.minOf { it.center.x } - ox
        val maxX = rs.maxOf { it.center.x } + ox
        val minY = rs.minOf { it.center.y } - oy
        val maxY = rs.maxOf { it.center.y } + oy
        val near = nearest(p)?.let { rect(it) } ?: rs[0]
        val t = when (axis) {
            LensAxis.Horizontal -> Offset(p.x.coerceIn(minX, maxX), near.center.y)
            LensAxis.Vertical -> Offset(near.center.x, p.y.coerceIn(minY, maxY))
            LensAxis.Both -> Offset(p.x.coerceIn(minX, maxX), p.y.coerceIn(minY, maxY))
        }
        scope.launch { center.animateTo(t, followSpec) }
        scope.launch { size.animateTo(near.size, followSizeSpec) }
    }

    /** How much of the lens is liquid glass now: held up, or moving fast. */
    fun glass(density: Float): Float {
        val v = center.velocity
        val speed = (abs(v.x) + abs(v.y)) / density
        return max(held.value, (speed / GLASS_SPEED).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** The lens now: its item's box, grown while held, stretched along its motion. */
    fun lens(density: Float, growX: Float, growY: Float): Rect? {
        if (!placed) return null
        val c = center.value
        val s = size.value
        val h = held.value
        val v = center.velocity
        val sx = (abs(v.x) / density / 3600f).coerceAtMost(0.3f)
        val sy = (abs(v.y) / density / 3600f).coerceAtMost(0.3f)
        val w = s.width * (1f + growX * h) * (1f + sx - 0.35f * sy)
        val hh = s.height * (1f + growY * h) * (1f + sy - 0.35f * sx)
        return Rect(c.x - w / 2f, c.y - hh / 2f, c.x + w / 2f, c.y + hh / 2f)
    }
}

/** What a selector's content can use: where its items are, and how liquid the lens is now. */
@Stable
class LiquidSelectorScope internal constructor(private val state: SelectorState, private val densityValue: Float) {
    /** Marks this element as item [index] of the control. */
    fun Modifier.liquidItem(index: Int): Modifier = this.onGloballyPositioned { c ->
        state.items[index] = c
        if (index == state.selected && !state.dragging && !state.center.isRunning) state.snapTo(index)
    }

    /** 0 while the lens rests, up to 1 while it is held or moving fast: the chosen item fades its own look. */
    val glass: Float get() = state.glass(densityValue)
}

/** Light clear glass for a lens; it magnifies while [held]. */
internal fun lensLook(dark: Boolean, held: Float) = GlassLook(
    tint = Color.White.copy(alpha = if (dark) 0.035f else 0.10f),
    frost = 0f, band = 11.dp, depth = 7.dp, split = 0.15f, vibrance = 1.12f,
    specular = 0.95f, edge = 1.2.dp, magnify = 0.15f * held,
    fallbackTint = Color.White.copy(alpha = if (dark) 0.12f else 0.35f)
)

/** The lens without the shader (Android 8–12): a light wash with a lit rim. */
internal fun DrawScope.fallbackLens(r: Rect, corner: Float, amount: Float, dark: Boolean) {
    drawRoundRect(Color.White.copy(alpha = (if (dark) 0.12f else 0.35f) * amount), r.topLeft, r.size, CornerRadius(corner))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f * amount), Color.White.copy(alpha = 0.08f * amount)), r.top, r.bottom),
        r.topLeft, r.size, CornerRadius(corner), style = Stroke(1.dp.toPx())
    )
}

/**
 * A control whose choice is marked by the liquid lens. [content] lays out the items and marks each
 * with `Modifier.liquidItem(i)`; the items handle their own taps (selectable), the lens handles
 * presses and drags. [axis]: the direction the lens may be dragged. [holdToDrag]: inside a scrolling
 * page, the lens is picked up by holding still, so a scroll is never mistaken for a drag. [under]:
 * what shows around the control when the held lens reaches past it (the page, for the tab bar).
 * [base]: the opaque ground under the control. [background]: the control's own surface, under the lens.
 */
@Composable
fun LiquidSelector(
    selected: Int,
    onSelect: (Int) -> Unit,
    rest: LensRest,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    axis: LensAxis = LensAxis.Horizontal,
    holdToDrag: Boolean = false,
    corner: Dp? = null,
    growX: Float = 0.08f,
    growY: Float = 0.12f,
    under: Backdrop? = null,
    base: Color = MaterialTheme.colorScheme.surfaceContainer,
    background: Modifier = Modifier,
    content: @Composable LiquidSelectorScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val state = remember { SelectorState(scope) }
    state.selected = selected
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentEnabled by rememberUpdatedState(enabled)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val parts = rememberGlassParts()
    val contentLayer = rememberGraphicsLayer()
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val dark = isDarkScheme()
    val itemScope = remember(state, density) { LiquidSelectorScope(state, density) }
    val restAlpha = if (enabled) 1f else 0.5f

    // a new choice (a tap, a restored setting) moves the lens there
    LaunchedEffect(selected) { if (!state.dragging && selected >= 0) state.moveTo(selected) }

    Box(
        modifier
            .onGloballyPositioned { state.host = it; hostOrigin = it.positionInRoot() }
            .pointerInput(axis, holdToDrag) {
                lensGestures(state, axis, holdToDrag, haptic, { currentEnabled }) { currentOnSelect(it) }
            }
            .drawWithContent {
                val lens = state.lens(density, growX, growY)
                val g = if (lens == null) 0f else state.glass(density)
                val glassOn = lens != null && g > 0.004f
                val hw = drawContext.canvas.nativeCanvas.isHardwareAccelerated
                if (glassOn && parts != null && hw) {
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                } else {
                    drawContent()
                }
                if (lens == null) return@drawWithContent
                val r = cornerOf(lens, corner?.toPx())
                if (!rest.below) rest.draw(this, lens, r, (1f - g) * restAlpha)
                if (glassOn) {
                    if (parts != null && hw) {
                        drawLiquid(parts, lens.topLeft, lens.size, r, lensLook(dark, state.held.value), base, dark, alpha = g) {
                            under?.drawAt(this, hostOrigin)
                            drawLayer(contentLayer)
                        }
                    } else {
                        fallbackLens(lens, r, g, dark)
                    }
                }
            }
    ) {
        Box(Modifier.matchParentSize().then(background))
        if (rest.below) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val lens = state.lens(density, growX, growY) ?: return@drawBehind
                    rest.draw(this, lens, cornerOf(lens, corner?.toPx()), (1f - state.glass(density)) * restAlpha)
                }
            )
        }
        itemScope.content()
    }
}

private fun cornerOf(r: Rect, corner: Float?): Float = min(corner ?: Float.MAX_VALUE, min(r.width, r.height) / 2f)

private suspend fun PointerInputScope.lensGestures(
    state: SelectorState,
    axis: LensAxis,
    holdToDrag: Boolean,
    haptic: HapticFeedback,
    enabled: () -> Boolean,
    onSelect: (Int) -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!enabled() || !state.placed) return@awaitEachGesture
    val slop = viewConfiguration.touchSlop
    var drag = false
    var last = down.position
    if (holdToDrag) {
        // still under the finger for a moment: the lens comes up; a move first is a scroll, an early lift a tap
        val ended = withTimeoutOrNull(HOLD_MS) {
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (ch == null || !ch.pressed || ch.isConsumed) break
                if ((ch.position - down.position).getDistance() > slop) break
            }
            true
        }
        drag = ended == null && state.indexAt(down.position) != null
    } else {
        // pressed on the chosen item, the lens starts to lift; a move along the control drags it
        if (state.indexAt(down.position) == state.selected) state.press(0.55f)
        var acc = Offset.Zero
        while (true) {
            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed || ch.isConsumed) break
            acc += ch.positionChange()
            last = ch.position
            val along = when (axis) {
                LensAxis.Horizontal -> abs(acc.x)
                LensAxis.Vertical -> abs(acc.y)
                LensAxis.Both -> acc.getDistance()
            }
            val across = when (axis) {
                LensAxis.Horizontal -> abs(acc.y)
                LensAxis.Vertical -> abs(acc.x)
                LensAxis.Both -> 0f
            }
            if (across > slop && across > along) break
            if (along > slop) {
                drag = true
                ch.consume()
                break
            }
        }
    }
    if (!drag) {
        state.release()
        return@awaitEachGesture
    }

    state.dragging = true
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    state.press(1f)
    var near = state.nearest(last)
    var completed = false
    try {
        state.follow(last, axis)
        while (true) {
            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            ch.consume()
            if (!ch.pressed) {
                completed = true
                break
            }
            last = ch.position
            state.follow(last, axis)
            val n = state.nearest(last)
            if (n != null && n != near) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                near = n
            }
        }
    } finally {
        state.dragging = false
        state.release()
        // the item under the finger where it lifts (the lens trails the finger a little)
        val target = state.nearest(last)
        if (target != null) {
            if (completed && target != state.selected) onSelect(target)
            state.settle(target)
        }
    }
}

// ============================================================================ the liquid switch

/**
 * A switch whose thumb is a drop of liquid glass: pressed, it lifts into a clear lens over the track
 * and magnifies it; it can be dragged and lands on the side it is nearer to; tapped, it slides over
 * with a stretch. The track's colour follows the thumb.
 */
@Composable
fun LiquidSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    val held = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val parts = rememberGlassParts()
    val onTrack = scheme.primary
    val offTrack = scheme.surfaceContainerHighest
    val offEdge = scheme.outline
    val base = scheme.surfaceContainer
    val alpha = if (enabled) 1f else 0.45f

    LaunchedEffect(checked) { if (!dragging) pos.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 520f)) }

    // Draws the track; [w] is its rim width in px, converted by the caller (the track is also drawn inside
    // the thumb's glass recording; 3.0.0 converted it there, which looped in Compose: StackOverflowError).
    val drawTrack: DrawScope.(Size, Float, Float) -> Unit = { s, p, w ->
        val r = CornerRadius(s.height / 2f)
        drawRoundRect(lerp(offTrack, onTrack, p).copy(alpha = alpha), size = s, cornerRadius = r)
        if (p < 1f) {
            drawRoundRect(
                offEdge.copy(alpha = 0.55f * (1f - p) * alpha), Offset(w / 2, w / 2), Size(s.width - w, s.height - w),
                CornerRadius(s.height / 2f - w / 2), style = Stroke(w)
            )
        }
    }

    Box(
        modifier
            .size(52.dp, 32.dp)
            .toggleable(value = checked, interactionSource = null, indication = null, enabled = enabled, role = Role.Switch) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            }
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    scope.launch { held.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 700f)) }
                    val slop = viewConfiguration.touchSlop
                    val travel = (52.dp - 6.dp - 26.dp).toPx()
                    val start = pos.value
                    val dir = if (rtl) -1f else 1f
                    var dx = 0f
                    var dy = 0f
                    var drag = false
                    try {
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                if (drag) ch.consume()
                                break
                            }
                            val d = ch.positionChange()
                            dx += d.x
                            dy += d.y
                            if (!drag) {
                                if (abs(dy) > slop && abs(dy) > abs(dx)) break // a scroll of the page
                                if (abs(dx) > slop) {
                                    drag = true
                                    dragging = true
                                }
                            }
                            if (drag) {
                                ch.consume()
                                val p = (start + dir * dx / travel).coerceIn(0f, 1f)
                                scope.launch { pos.snapTo(p) }
                            }
                        }
                    } finally {
                        if (drag) {
                            dragging = false
                            val on = pos.value >= 0.5f
                            scope.launch { pos.animateTo(if (on) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f)) }
                            if (on != currentChecked) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnChange(on)
                            }
                        }
                        scope.launch { held.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                    }
                }
            }
            .drawBehind {
                val p = pos.value
                val h = held.value
                val rimW = 1.5.dp.toPx()
                drawTrack(size, p, rimW)
                val inset = 3.dp.toPx()
                val d = size.height - 2f * inset
                val travelPx = size.width - 2f * inset - d
                val speed = abs(pos.velocity) * travelPx / density
                val stretch = (speed / 1800f).coerceAtMost(0.35f)
                val tw = d * (1f + 0.42f * h) * (1f + stretch)
                val th = d * (1f + 0.12f * h) * (1f - 0.25f * stretch)
                val fromStart = inset + d / 2f + travelPx * p
                val cx = if (rtl) size.width - fromStart else fromStart
                val cy = size.height / 2f
                val thumb = Rect(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
                val r = CornerRadius(th / 2f)
                val solid = 1f - h
                if (solid > 0f) {
                    drawRoundRect(Color.Black.copy(alpha = 0.16f * solid * alpha), thumb.topLeft + Offset(0f, 1.dp.toPx()), thumb.size, r)
                    drawRoundRect(Color.White.copy(alpha = solid * (if (enabled) 1f else 0.7f)), thumb.topLeft, thumb.size, r)
                }
                if (h > 0.004f) {
                    val trackSize = size
                    if (parts != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                        drawLiquid(parts, thumb.topLeft, thumb.size, th / 2f, lensLook(dark, h), base, dark, alpha = h) {
                            drawTrack(this, trackSize, p, rimW)
                        }
                    } else {
                        drawRoundRect(Color.White.copy(alpha = h * 0.85f), thumb.topLeft, thumb.size, r)
                        fallbackLens(thumb, th / 2f, h, dark)
                    }
                }
            }
    )
}
