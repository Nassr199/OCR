package com.abdulrahman.hardsubocr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.abdulrahman.hardsubocr.ui.theme.AppFonts
import com.abdulrahman.hardsubocr.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The liquid glass and its controls on the JVM (Robolectric, native graphics): the shader compiles and
 * draws; a drag along the tab bar or the rail reaches every tab from every tab (2.9.0 refused some);
 * the settings scroll on a phone on its side (2.9.0 froze); the switch follows a drag; the title font
 * loads from memory. Pictures go to app/build/shots with the screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LiquidTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(b: Bitmap, name: String) {
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The shader compiles (in the form Robolectric's older Skia reads; the device form differs only in
     * `content.eval`) and takes every uniform the app sets. Robolectric cannot run a shader as a
     * RenderEffect nor read the GPU back, so its pixels are checked with verification/glass/preview.py.
     */
    @Test
    fun shader_compiles_and_takes_its_uniforms() {
        println("device form here: " + runCatching { RuntimeShader(LIQUID_GLASS_SKSL) }.fold({ "compiles" }, { "${it.message?.lines()?.firstOrNull()}" }))
        val compiled = runCatching { RuntimeShader(LIQUID_GLASS_SOURCE) }
        compiled.exceptionOrNull()?.let { throw AssertionError("compile: $it", it) }
        val shader = compiled.getOrThrow()
        runCatching { GlassUniforms.Probe.applyTo(shader) }.exceptionOrNull()?.let { throw AssertionError("uniforms: $it", it) }
        // a few real looks, as the bar, the button and a held lens set them
        for (u in listOf(
            GlassUniforms(1000f, 180f, 30f, 90f, 211f, 0.25f, 42f, 26f, 0.1f, 1f, 0f, Color(0xFF0B1413).copy(alpha = 0.52f), 1.5f, 0.55f, 4f, 1f),
            GlassUniforms(200f, 200f, 25f, 100f, 251f, 0.25f, 37f, 21f, 0.12f, 1f, 0f, Color(0xFF5FD4C4).copy(alpha = 0.8f), 1.25f, 0.9f, 4f, 1f),
            GlassUniforms(240f, 190f, 25f, 95f, 0f, 0.25f, 29f, 18f, 0.25f, 0f, 0.15f, Color.White.copy(alpha = 0.035f), 1.12f, 0.95f, 3f, 1f)
        )) u.applyTo(shader)
        println("RenderEffect from a shader here: " + runCatching { android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content") }.fold({ "yes" }, { "no (${it.javaClass.simpleName})" }))
    }

    private val tabs = listOf(
        NavItem("الاستخراج", AppIcons.Subtitles),
        NavItem("المعالجة", AppIcons.Activity),
        NavItem("الإعدادات", AppIcons.Tune)
    )

    private fun center(text: String): Offset = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    private fun drag(from: String, to: String) {
        val a = center(from)
        val b = center(to)
        rule.onRoot().performTouchInput {
            down(a)
            for (i in 1..12) moveTo(a + (b - a) * (i / 12f))
            moveTo(b)
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun barDragReachesEveryTab(vertical: Boolean) {
        var tab by mutableIntStateOf(0)
        rule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize()) {
                    val backdrop = rememberBackdrop()
                    Box(Modifier.fillMaxSize().backdropSource(backdrop)) { AppBackground() }
                    GlassTabs(
                        backdrop, tabs, tab, { tab = it }, vertical = vertical,
                        modifier = Modifier.align(if (vertical) Alignment.CenterStart else Alignment.BottomCenter)
                    )
                }
            }
        }
        rule.waitForIdle()
        // 2.9.0: the bar kept the tab it was first drawn with, so once the tab changed a drag back to it was refused
        rule.runOnIdle { tab = 2 }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        drag("الإعدادات", "الاستخراج")
        assertEquals("settings → extraction", 0, tab)
        drag("الاستخراج", "المعالجة")
        assertEquals("extraction → processing", 1, tab)
        drag("المعالجة", "الإعدادات")
        assertEquals("processing → settings", 2, tab)
        drag("الإعدادات", "المعالجة")
        assertEquals("settings → processing", 1, tab)
        drag("المعالجة", "الاستخراج")
        assertEquals("processing → extraction", 0, tab)
        drag("الاستخراج", "الإعدادات")
        assertEquals("extraction → settings", 2, tab)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun bar_drag_reaches_every_tab() = barDragReachesEveryTab(vertical = false)

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun rail_drag_reaches_every_tab() = barDragReachesEveryTab(vertical = true)

    /**
     * Every move of the lens is liquid, however it was chosen. 3.0.1 snapped the lens onto a tapped item
     * (the tap re-laid the items before the move began), so a tap jumped; only right after a drag, with
     * the lens still settling, did it travel. Here three taps, each after a wait: each must travel lifted
     * into glass and land at rest.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun every_tap_moves_the_lens_as_liquid_glass() {
        var sel by mutableIntStateOf(0)
        var g = 0f
        var peak = 0f
        rule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LiquidSelector(sel, { sel = it }, LensRest.fill(Color.Gray)) {
                        // read on every frame the lens changes: the highest value of each move is kept
                        g = glass
                        peak = maxOf(peak, g)
                        androidx.compose.foundation.layout.Row {
                            for (i in 0 until 3) {
                                Box(
                                    Modifier.size(80.dp, 48.dp).liquidItem(i)
                                        .selectable(selected = i == sel, interactionSource = null, indication = null) { sel = i },
                                    contentAlignment = Alignment.Center
                                ) { Text("خيار $i") }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        fun tapPeak(text: String): Float {
            peak = 0f
            rule.onNodeWithText(text).performClick()
            rule.waitForIdle() // runs every frame of the move
            val p = peak
            rule.mainClock.advanceTimeBy(3_000) // and a wait: the next tap starts from rest
            rule.waitForIdle()
            return p
        }
        val peaks = listOf(tapPeak("خيار 1"), tapPeak("خيار 2"), tapPeak("خيار 1"))
        println("glass at its peak on each tap: $peaks")
        assertEquals("the last tap chose", 1, sel)
        for (p in peaks) assertTrue("each tap lifts the lens into glass (peaks $peaks)", p >= HOP - 0.02f)
        assertTrue("and it lands at rest", g < 0.05f)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi")
    fun landscape_settings_page_scrolls() {
        rule.setContent {
            AppTheme {
                CompositionLocalProvider(LocalChrome provides Chrome(top = 0.dp, bottom = 0.dp, start = 0.dp, wide = true, twoPane = true, short = true)) {
                    SettingsPageScreen("اختبار", onBack = null) {
                        repeat(40) { Text("سطر رقم $it") }
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithText("سطر رقم 0").fetchSemanticsNode().boundsInRoot.top
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 300) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val after = rule.onNodeWithText("سطر رقم 0").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the page must scroll on a phone on its side (top $before → $after)", after < before - 50f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun switch_follows_a_drag_and_a_tap() {
        var on by mutableStateOf(false)
        rule.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LiquidSwitch(on, { on = it })
                }
            }
        }
        rule.waitForIdle()
        val box = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val c = box.center
        // right to left (on, in Arabic)
        rule.onRoot().performTouchInput {
            down(c + Offset(25.dp.toPx(), 0f))
            for (i in 1..10) moveTo(c + Offset(25.dp.toPx() - 5.dp.toPx() * i, 0f))
            up()
        }
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        assertTrue("dragged toward on", on)
        rule.onRoot().performTouchInput { click(c) }
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        assertTrue("tapped off", !on)
    }

    /**
     * 3.0.0 crashed on every switch (StackOverflowError): the thumb's glass recorded the track through the
     * node's own draw scope, and the track's dp → px lookup looped inside Compose. The glass now records with
     * a plain density; what it draws may convert dp. Robolectric's frames never reach the glass (no hardware
     * canvas), so the recording is called here directly from a node's draw, as on a phone.
     */
    @Test
    fun glass_recording_inside_a_draw_may_convert_dp() {
        var ran = false
        var bare: Throwable? = null
        var glass: Throwable? = null
        rule.setContent {
            val parts = rememberGlassParts()
            val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
            Box(
                Modifier.size(160.dp).drawBehind {
                    ran = true
                    bare = runCatching { layer.record(IntSize(8, 8)) { drawRect(Color.Red, size = Size(1.5.dp.toPx(), 1f)) } }.exceptionOrNull()
                    glass = runCatching {
                        drawLiquid(parts!!, Offset(10f, 10f), Size(120f, 60f), 30f, lensLook(true, 1f), Color.Black, true) {
                            drawRect(Color.Red, size = Size(1.5.dp.toPx(), 1f))
                        }
                    }.exceptionOrNull()
                }
            )
        }
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        assertTrue("the node drew", ran)
        println("a layer recorded through the node's scope, converting dp: ${bare ?: "fine"}")
        println("the glass recording, converting dp: ${glass ?: "fine"}")
        // drawing the recorded layer onto this software canvas may be refused; looping never
        assertTrue("the glass recording loops: $glass", glass !is StackOverflowError)
    }

    @Test
    fun title_font_loads_from_memory() {
        val f = listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
        ).map(::File).firstOrNull { it.exists() }
        Assume.assumeTrue("no system font to try the loader with", f != null)
        val tf = AppFonts.typefaceFrom(rule.activity, f!!.readBytes(), 700)
        assertNotNull(tf)
        val b = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(android.graphics.Color.rgb(15, 23, 22))
        c.drawText("HardSub OCR 3.0", 20f, 80f, Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 56f; color = android.graphics.Color.WHITE })
        save(b, "font_loader_check")
    }
}
