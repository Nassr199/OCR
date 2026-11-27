@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.data.RoiRect
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Good
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================ the region screen
//
// Where the subtitles are in the video: the picture with the region on it, a timeline to find a frame
// that has subtitles (a strip of frames across the video, and steps down to one frame), and the text
// detector's settings. Display only: what the extraction reads is the region and the two thresholds,
// exactly as before.

/** Smallest region: 4 % of the height, 10 % of the width. */
private const val MIN_H = 0.04f
private const val MIN_W = 0.10f

/** What a drag on the picture changes. */
private enum class Grip { TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, NEW }

@Composable
fun RoiScreen(vm: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("منطقة الترجمة في الفيديو") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = onBack) { Icon(Icons.Filled.Check, "تم") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
            )
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            val screenH = maxHeight
            if (wide) {
                // a phone on its side or a tablet: the picture and its timeline beside the settings
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RegionPicture(vm, maxPictureHeight = screenH * 0.62f)
                        RegionReadout(vm)
                        TimelineCard(vm)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GuideHint()
                        Presets(vm)
                        DetectorCard(vm)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GuideHint()
                    RegionPicture(vm, maxPictureHeight = screenH * 0.55f)
                    RegionReadout(vm)
                    Presets(vm)
                    TimelineCard(vm)
                    DetectorCard(vm)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun GuideHint() {
    Hint("اسحب حافة المستطيل أو زاويته لضبطه، واسحب داخله لتحريكه، أو ارسم مستطيلًا جديدًا خارجه. الأحمر هو ما سيعتبره البرنامج نصًا.")
}

// ---------------------------------------------------------------------------- the picture

/** The frame with the region on it: edges and corners have grips, the inside moves, outside draws anew. */
@Composable
private fun RegionPicture(vm: MainViewModel, maxPictureHeight: Dp) {
    val info = vm.videoInfo
    val bmp = vm.preview
    val aspect = when {
        bmp != null -> bmp.width.toFloat() / bmp.height
        info != null && info.height > 0 -> info.width.toFloat() / info.height
        else -> 16f / 9f
    }.coerceIn(0.3f, 4f)
    val haptic = LocalHapticFeedback.current
    val roi by rememberUpdatedState(vm.roi)
    var grip by remember { mutableStateOf<Grip?>(null) }
    val measurer = rememberTextMeasurer()
    val overlay = vm.maskOverlay
    val overlayImg = remember(overlay) { overlay?.asImageBitmap() }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    val labelStyle = TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val w = min(maxWidth, maxPictureHeight * aspect)
        // video pixels are never mirrored: the picture is drawn left to right in the RTL app
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .width(w)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                if (img != null) {
                    Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val sw = size.width.toFloat().coerceAtLeast(1f)
                                val sh = size.height.toFloat().coerceAtLeast(1f)
                                val start = roi.normalized()
                                val g = hit(down.position, start, sw, sh, 26.dp.toPx())
                                val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                    ?: return@awaitEachGesture
                                grip = g
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.setRoi(dragged(g, start, down.position, moved.position, sw, sh), persist = false)
                                drag(moved.id) { change ->
                                    change.consume()
                                    vm.setRoi(dragged(g, start, down.position, change.position, sw, sh), persist = false)
                                }
                                grip = null
                                // a new rectangle too small to be a region keeps the old one
                                val r = vm.roi
                                val ok = abs(r.bottom - r.top) >= MIN_H && abs(r.right - r.left) >= MIN_W
                                vm.setRoi(if (ok) r.normalized() else start, persist = true)
                            }
                        }
                ) {
                    val r = roi.normalized()
                    val box = Rect(r.left * size.width, r.top * size.height, r.right * size.width, r.bottom * size.height)
                    val dim = Color.Black.copy(alpha = 0.5f)
                    drawRect(dim, Offset.Zero, Size(size.width, box.top))
                    drawRect(dim, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
                    drawRect(dim, Offset(0f, box.top), Size(box.left, box.height))
                    drawRect(dim, Offset(box.right, box.top), Size(size.width - box.right, box.height))
                    if (vm.showMask && overlayImg != null && grip == null) {
                        drawImage(
                            overlayImg,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(overlayImg.width, overlayImg.height),
                            dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                            dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.None
                        )
                    }
                    regionFrame(box, grip)
                    if (grip != null) {
                        // while dragging: where the region runs, in % of the height
                        val text = "${pct(r.top)} – ${pct(r.bottom)}"
                        val layout = measurer.measure(text, labelStyle)
                        val pad = 6.dp.toPx()
                        val bw = layout.size.width + 2 * pad
                        val bh = layout.size.height + pad
                        val x = (box.left + 8.dp.toPx()).coerceIn(0f, size.width - bw)
                        val y = if (box.top - bh - 6.dp.toPx() >= 0f) box.top - bh - 6.dp.toPx() else box.top + 6.dp.toPx()
                        drawRoundRect(Amber, Offset(x, y), Size(bw, bh), CornerRadius(bh / 2))
                        drawText(layout, topLeft = Offset(x + pad, y + pad / 2))
                    }
                }
                if (vm.previewLoading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp),
                        strokeWidth = 2.5.dp, color = Color.White
                    )
                }
                if (bmp == null && !vm.previewLoading) {
                    Text("لا توجد معاينة", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** The region's outline with grips on its edges and corners; the edge being dragged is lit. */
private fun DrawScope.regionFrame(box: Rect, active: Grip?) {
    val line = 2.dp.toPx()
    drawRect(Amber, box.topLeft, box.size, style = Stroke(line))
    fun isActive(vararg g: Grip) = active != null && g.any { it == active }
    // the edges being dragged are lit
    val w3 = 3.dp.toPx()
    if (isActive(Grip.TOP, Grip.TOP_LEFT, Grip.TOP_RIGHT, Grip.MOVE)) drawLine(Color.White, box.topLeft, box.topRight, w3)
    if (isActive(Grip.BOTTOM, Grip.BOTTOM_LEFT, Grip.BOTTOM_RIGHT, Grip.MOVE)) drawLine(Color.White, box.bottomLeft, box.bottomRight, w3)
    if (isActive(Grip.LEFT, Grip.TOP_LEFT, Grip.BOTTOM_LEFT, Grip.MOVE)) drawLine(Color.White, box.topLeft, box.bottomLeft, w3)
    if (isActive(Grip.RIGHT, Grip.TOP_RIGHT, Grip.BOTTOM_RIGHT, Grip.MOVE)) drawLine(Color.White, box.topRight, box.bottomRight, w3)
    // grips: pills on the edges, brackets on the corners
    val long = 34.dp.toPx().coerceAtMost(box.width * 0.4f)
    val tall = 26.dp.toPx().coerceAtMost(box.height * 0.6f)
    val thick = 6.dp.toPx()
    fun pill(c: Offset, w: Float, h: Float) {
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(c.x - w / 2 - 1.5f, c.y - h / 2 - 1.5f), Size(w + 3f, h + 3f), CornerRadius(h))
        drawRoundRect(Color.White, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(h / 2))
    }
    pill(Offset(box.center.x, box.top), long, thick)
    pill(Offset(box.center.x, box.bottom), long, thick)
    pill(Offset(box.left, box.center.y), thick, tall)
    pill(Offset(box.right, box.center.y), thick, tall)
    val arm = 14.dp.toPx().coerceAtMost(minOf(box.width, box.height) * 0.4f)
    val bw = 3.5.dp.toPx()
    for ((corner, dir) in listOf(
        box.topLeft to Offset(1f, 1f), box.topRight to Offset(-1f, 1f),
        box.bottomLeft to Offset(1f, -1f), box.bottomRight to Offset(-1f, -1f)
    )) {
        drawLine(Color.White, corner, corner + Offset(arm * dir.x, 0f), bw, StrokeCap.Round)
        drawLine(Color.White, corner, corner + Offset(0f, arm * dir.y), bw, StrokeCap.Round)
    }
}

/** Which part of the region a finger at [p] takes: the nearest edge or corner within [tol] px, else inside or outside. */
private fun hit(p: Offset, r: RoiRect, w: Float, h: Float, tol: Float): Grip {
    val l = r.left * w
    val t = r.top * h
    val rr = r.right * w
    val b = r.bottom * h
    val inX = p.x in (l - tol)..(rr + tol)
    val inY = p.y in (t - tol)..(b + tol)
    val dT = if (inX) abs(p.y - t) else Float.MAX_VALUE
    val dB = if (inX) abs(p.y - b) else Float.MAX_VALUE
    val dL = if (inY) abs(p.x - l) else Float.MAX_VALUE
    val dR = if (inY) abs(p.x - rr) else Float.MAX_VALUE
    val vertical = if (minOf(dT, dB) <= tol) (if (dT <= dB) Grip.TOP else Grip.BOTTOM) else null
    val horizontal = if (minOf(dL, dR) <= tol) (if (dL <= dR) Grip.LEFT else Grip.RIGHT) else null
    return when {
        vertical == Grip.TOP && horizontal == Grip.LEFT -> Grip.TOP_LEFT
        vertical == Grip.TOP && horizontal == Grip.RIGHT -> Grip.TOP_RIGHT
        vertical == Grip.BOTTOM && horizontal == Grip.LEFT -> Grip.BOTTOM_LEFT
        vertical == Grip.BOTTOM && horizontal == Grip.RIGHT -> Grip.BOTTOM_RIGHT
        vertical != null -> vertical
        horizontal != null -> horizontal
        p.x in l..rr && p.y in t..b -> Grip.MOVE
        else -> Grip.NEW
    }
}

/** The region after a drag of [grip] from [from] to [to] (px), starting from [start]. */
private fun dragged(grip: Grip, start: RoiRect, from: Offset, to: Offset, w: Float, h: Float): RoiRect {
    val dx = (to.x - from.x) / w
    val dy = (to.y - from.y) / h
    var l = start.left
    var t = start.top
    var r = start.right
    var b = start.bottom
    fun top() { t = (start.top + dy).coerceIn(0f, b - MIN_H) }
    fun bottom() { b = (start.bottom + dy).coerceIn(t + MIN_H, 1f) }
    fun left() { l = (start.left + dx).coerceIn(0f, r - MIN_W) }
    fun right() { r = (start.right + dx).coerceIn(l + MIN_W, 1f) }
    when (grip) {
        Grip.TOP -> top()
        Grip.BOTTOM -> bottom()
        Grip.LEFT -> left()
        Grip.RIGHT -> right()
        Grip.TOP_LEFT -> { top(); left() }
        Grip.TOP_RIGHT -> { top(); right() }
        Grip.BOTTOM_LEFT -> { bottom(); left() }
        Grip.BOTTOM_RIGHT -> { bottom(); right() }
        Grip.MOVE -> {
            val mx = dx.coerceIn(-start.left, 1f - start.right)
            val my = dy.coerceIn(-start.top, 1f - start.bottom)
            l = start.left + mx; r = start.right + mx; t = start.top + my; b = start.bottom + my
        }
        Grip.NEW -> {
            val ax = (from.x / w).coerceIn(0f, 1f)
            val ay = (from.y / h).coerceIn(0f, 1f)
            val bx = (to.x / w).coerceIn(0f, 1f)
            val by = (to.y / h).coerceIn(0f, 1f)
            l = minOf(ax, bx); r = maxOf(ax, bx); t = minOf(ay, by); b = maxOf(ay, by)
        }
    }
    return RoiRect(l, t, r, b)
}

private fun pct(f: Float) = String.format(java.util.Locale.US, "%.1f%%", f * 100f)

// ---------------------------------------------------------------------------- the region in numbers

/** Where the top and bottom edges are (% of the height and video px), each with fine steps of 0.5 %. */
@Composable
private fun RegionReadout(vm: MainViewModel) {
    val r = vm.roi.normalized()
    val videoH = vm.videoInfo?.height ?: 0
    fun nudge(top: Boolean, by: Float) {
        val n = if (top) r.copy(top = (r.top + by).coerceIn(0f, r.bottom - MIN_H))
        else r.copy(bottom = (r.bottom + by).coerceIn(r.top + MIN_H, 1f))
        vm.setRoi(n, persist = true)
    }
    Row(Modifier.fillMaxWidth().glassCard(RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        EdgeControl("الحد العلوي", r.top, videoH, Modifier.weight(1f), up = { nudge(true, -0.005f) }, down = { nudge(true, 0.005f) })
        Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
        Spacer(Modifier.width(12.dp))
        EdgeControl("الحد السفلي", r.bottom, videoH, Modifier.weight(1f), up = { nudge(false, -0.005f) }, down = { nudge(false, 0.005f) })
    }
}

@Composable
private fun EdgeControl(label: String, at: Float, videoH: Int, modifier: Modifier, up: () -> Unit, down: () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("⁦" + pct(at) + "⁩", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            if (videoH > 0) {
                Text(
                    "⁦${(at * videoH).roundToInt()} px⁩",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
        FilledTonalIconButton(onClick = up, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "$label أعلى") }
        Spacer(Modifier.width(4.dp))
        FilledTonalIconButton(onClick = down, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "$label أسفل") }
    }
}

@Composable
private fun Presets(vm: MainViewModel) {
    fun apply(r: RoiRect) = vm.setRoi(r, persist = true)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { apply(RoiRect(0.02f, 0.70f, 0.98f, 0.98f)) }, label = { Text("أسفل 30%") })
        AssistChip(onClick = { apply(RoiRect(0.05f, 0.78f, 0.95f, 0.98f)) }, label = { Text("أسفل 20%") })
        AssistChip(onClick = { apply(RoiRect(0.02f, 0.55f, 0.98f, 0.98f)) }, label = { Text("أسفل 45% (سطران عاليان)") })
        AssistChip(onClick = { apply(RoiRect(0.02f, 0.02f, 0.98f, 0.28f)) }, label = { Text("أعلى الشاشة") })
        AssistChip(onClick = { apply(RoiRect(0f, 0f, 1f, 1f)) }, label = { Text("الإطار كامل") })
    }
}

// ---------------------------------------------------------------------------- the timeline

/**
 * Where in the video: the time, a strip of frames across the whole video (drag on it to move, the
 * picture follows; tap to jump), and steps back and forward of 5 s, 1 s and one frame.
 */
@Composable
private fun TimelineCard(vm: MainViewModel) {
    val info = vm.videoInfo
    val dur = info?.durationUs ?: 0L
    val frameUs = info?.frameUs ?: 41_708L
    SectionCard("الوقت في الفيديو", "🎞") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(
                "⁦" + clock(vm.previewTimeUs) + "⁩",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text("⁦/ " + clock(dur).substringBefore('.') + "⁩", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
        FilmStrip(vm, dur)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // in Arabic the timeline runs right to left: back is on the right, forward on the left
            StepGroup("للخلف", listOf("5 ث" to -5_000_000L, "1 ث" to -1_000_000L, "إطار" to -frameUs), Modifier.weight(1f)) {
                vm.loadPreview(vm.previewTimeUs + it)
            }
            StepGroup("للأمام", listOf("إطار" to frameUs, "1 ث" to 1_000_000L, "5 ث" to 5_000_000L), Modifier.weight(1f)) {
                vm.loadPreview(vm.previewTimeUs + it)
            }
        }
        Spacer(Modifier.height(6.dp))
        Hint("اسحب على شريط اللقطات للتنقل، وتتبعك الصورة. ابحث عن لحظة فيها ترجمة من سطرين، وتأكد أن المنطقة تشملها كاملة.")
    }
}

@Composable
private fun StepGroup(title: String, steps: List<Pair<String, Long>>, modifier: Modifier, onStep: (Long) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, delta) in steps) {
                FilledTonalButton(
                    onClick = { onStep(delta) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) { Text(label, fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** Frames across the whole video with the playhead on them. Right to left, as the app reads. */
@Composable
private fun FilmStrip(vm: MainViewModel, dur: Long) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val count = (maxWidth / 46.dp).toInt().coerceIn(5, 16)
        LaunchedEffect(count, vm.video, dur) { vm.loadFilmstrip(count) }
        val frames = vm.filmstrip
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0C1413))
                .pointerInput(dur, rtl) {
                    if (dur <= 0) return@pointerInput
                    fun timeAt(x: Float): Long {
                        var f = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (rtl) f = 1f - f
                        return (f * dur).toLong()
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.loadPreview(timeAt(down.position.x))
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() != Offset.Zero) {
                                ch.consume()
                                vm.loadPreview(timeAt(ch.position.x))
                            }
                        }
                    }
                }
        ) {
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until count) {
                    val b = frames.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 0.5.dp).background(Color(0xFF1A2624))) {
                        if (b != null) {
                            val bi = remember(b) { b.asImageBitmap() }
                            Image(bi, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val f = if (dur > 0) (vm.previewTimeUs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                val x = (if (rtl) 1f - f else f) * size.width
                // what is behind the playhead is shaded
                if (rtl) drawRect(Color.Black.copy(alpha = 0.35f), Offset(x, 0f), Size(size.width - x, size.height))
                else drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, Size(x, size.height))
                drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 6.dp.toPx())
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(Amber, 5.dp.toPx(), Offset(x, 5.dp.toPx()))
                drawCircle(Amber, 5.dp.toPx(), Offset(x, size.height - 5.dp.toPx()))
            }
        }
    }
}

/** 00:12:34.567 */
private fun clock(us: Long): String = Timestamps.srt(us / 1000).replace(',', '.')

// ---------------------------------------------------------------------------- the detector

@Composable
private fun DetectorCard(vm: MainViewModel) {
    var bright by remember { mutableFloatStateOf(vm.brightThreshold.toFloat()) }
    var contrast by remember { mutableFloatStateOf(vm.contrastDelta.toFloat()) }
    SectionCard("كاشف النص", "🧪") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = vm.textDetected()
            Text(
                if (ok) "✓ تم رصد نص في هذا الإطار" else "لا يوجد نص واضح في هذا الإطار",
                color = if (ok) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            Text("${vm.maskCount} px", style = MaterialTheme.typography.labelMedium)
        }
        SwitchRow("إظهار القناع الأحمر", null, vm.showMask) { vm.showMask = it }
        SliderRow("حد سطوع النص", "${bright.roundToInt()}", bright, 90f..240f, 0,
            onChange = { bright = it }, onDone = { vm.setMaskParams(bright = bright.roundToInt()) })
        SliderRow("تباين الحدود", "${contrast.roundToInt()}", contrast, 20f..150f, 0,
            onChange = { contrast = it }, onDone = { vm.setMaskParams(contrast = contrast.roundToInt()) })
        SwitchRow("نص داكن على خلفية فاتحة", "نادر — فعّله فقط إن كانت الترجمة سوداء", vm.darkText) {
            vm.setMaskParams(dark = it)
        }
        Hint("انقل الوقت إلى لحظة فيها ترجمة: يجب أن تغطي البكسلات الحمراء حروف الترجمة فقط. الافتراضي (150 / 60) مناسب للترجمة البيضاء والصفراء والرمادية ذات الحدود أو الظل. لا يلزم أن تكون كل الحروف حمراء — الأهم ألا تكون الخلفية حمراء.")
    }
}
