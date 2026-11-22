@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.abdulrahman.hardsubocr.core.Timestamps
import com.abdulrahman.hardsubocr.data.RoiRect
import com.abdulrahman.hardsubocr.ui.theme.Amber
import com.abdulrahman.hardsubocr.ui.theme.Good
import kotlin.math.roundToInt

@Composable
fun RoiScreen(vm: MainViewModel, onBack: () -> Unit) {
    val info = vm.videoInfo
    val dur = info?.durationUs ?: 0L
    var sliderPos by remember { mutableFloatStateOf(if (dur > 0) vm.previewTimeUs.toFloat() / dur else 0f) }
    var bright by remember { mutableFloatStateOf(vm.brightThreshold.toFloat()) }
    var contrast by remember { mutableFloatStateOf(vm.contrastDelta.toFloat()) }

    fun applyPreset(r: RoiRect) = vm.setRoi(r, persist = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("منطقة الترجمة في الفيديو", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = onBack) { Icon(Icons.Filled.Check, "تم") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Hint("اسحب بإصبعك على الصورة لرسم مستطيل حول مكان الترجمة. اللون الأحمر = البكسلات التي سيعتبرها البرنامج نصًا.")

            val bmp = vm.preview
            val aspect = when {
                bmp != null -> bmp.width.toFloat() / bmp.height
                info != null && info.height > 0 -> info.width.toFloat() / info.height
                else -> 16f / 9f
            }
            // Video pixels are never mirrored: draw the frame in LTR even though the UI is RTL.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect.coerceIn(0.3f, 4f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    if (bmp != null) {
                        val img = remember(bmp) { bmp.asImageBitmap() }
                        Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                    }
                    val overlay = vm.maskOverlay
                    val overlayImg = remember(overlay) { overlay?.asImageBitmap() }
                    var dragStart by remember { mutableStateOf<Offset?>(null) }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { o -> dragStart = o },
                                    onDragEnd = {
                                        dragStart = null
                                        vm.setRoi(vm.roi, persist = true)
                                    },
                                    onDragCancel = { dragStart = null },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val st = dragStart
                                        if (st != null) {
                                            val p = change.position
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            val h = size.height.toFloat().coerceAtLeast(1f)
                                            vm.setRoi(
                                                RoiRect(
                                                    (minOf(st.x, p.x) / w).coerceIn(0f, 1f),
                                                    (minOf(st.y, p.y) / h).coerceIn(0f, 1f),
                                                    (maxOf(st.x, p.x) / w).coerceIn(0f, 1f),
                                                    (maxOf(st.y, p.y) / h).coerceIn(0f, 1f)
                                                ),
                                                persist = false
                                            )
                                        }
                                    }
                                )
                            }
                    ) {
                        val r = vm.roi
                        val l = minOf(r.left, r.right) * size.width
                        val t = minOf(r.top, r.bottom) * size.height
                        val rw = (kotlin.math.abs(r.right - r.left) * size.width).coerceAtLeast(1f)
                        val rh = (kotlin.math.abs(r.bottom - r.top) * size.height).coerceAtLeast(1f)
                        // dim outside the region
                        val dim = Color.Black.copy(alpha = 0.45f)
                        drawRect(dim, Offset.Zero, Size(size.width, t))
                        drawRect(dim, Offset(0f, t + rh), Size(size.width, size.height - t - rh))
                        drawRect(dim, Offset(0f, t), Size(l, rh))
                        drawRect(dim, Offset(l + rw, t), Size(size.width - l - rw, rh))
                        if (vm.showMask && overlayImg != null && dragStart == null) {
                            drawImage(
                                overlayImg,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(overlayImg.width, overlayImg.height),
                                dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
                                dstSize = IntSize(rw.roundToInt(), rh.roundToInt()),
                                filterQuality = FilterQuality.None
                            )
                        }
                        drawRect(Amber, Offset(l, t), Size(rw, rh), style = Stroke(width = 3.dp.toPx()))
                    }
                    if (vm.previewLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
                    }
                    if (bmp == null && !vm.previewLoading) {
                        Text("لا توجد معاينة", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Timestamps.srt(vm.previewTimeUs / 1000).substringBefore(','), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = sliderPos,
                    onValueChange = { sliderPos = it },
                    onValueChangeFinished = { vm.loadPreview((sliderPos * dur).toLong()) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { applyPreset(RoiRect(0.02f, 0.70f, 0.98f, 0.98f)) }, label = { Text("أسفل 30%") })
                AssistChip(onClick = { applyPreset(RoiRect(0.05f, 0.78f, 0.95f, 0.98f)) }, label = { Text("أسفل 20%") })
                AssistChip(onClick = { applyPreset(RoiRect(0.02f, 0.02f, 0.98f, 0.28f)) }, label = { Text("أعلى الشاشة") })
                AssistChip(onClick = { applyPreset(RoiRect(0f, 0f, 1f, 1f)) }, label = { Text("الإطار كامل") })
            }

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
                SliderRow("حد سطوع النص", "${bright.roundToInt()}", bright, 100f..250f, 0,
                    onChange = { bright = it }, onDone = { vm.setMaskParams(bright = bright.roundToInt()) })
                SliderRow("تباين الحدود", "${contrast.roundToInt()}", contrast, 20f..150f, 0,
                    onChange = { contrast = it }, onDone = { vm.setMaskParams(contrast = contrast.roundToInt()) })
                SwitchRow("نص داكن على خلفية فاتحة", "نادر — فعّله فقط إن كانت الترجمة سوداء", vm.darkText) {
                    vm.setMaskParams(dark = it)
                }
                Hint("انقل الشريط إلى لحظة فيها ترجمة: يجب أن تغطي البكسلات الحمراء حروف الترجمة فقط. الافتراضي (175 / 60) مناسب للترجمة البيضاء أو الصفراء ذات الحدود السوداء.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
