package com.abdulrahman.hardsubocr.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abdulrahman.hardsubocr.core.SubEntry
import com.abdulrahman.hardsubocr.job.JobManager
import com.abdulrahman.hardsubocr.job.JobOutput
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.job.LogLine
import com.abdulrahman.hardsubocr.job.Progress
import com.abdulrahman.hardsubocr.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Every tab on a phone held upright, a phone on its side and a tablet, rendered by Robolectric's
 * native graphics (no device in the build loop). Pictures go to app/build/shots (workflow
 * "UI screenshots" publishes them). A job's states are shown with sample data; nothing runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenShots {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val log = listOf(
        "✓ 0_12_04_310__0_12_06_120  ←  هوليا هي آخر همي الآن",
        "✓ 0_12_06_900__0_12_08_400  ←  - هوليا ⏎ - ابتعدي يا جوليزار",
        "↻ 0_12_09_050__0_12_10_600  —  إعادة المحاولة بعد 4 ث (429)",
        "✓ 0_12_11_000__0_12_12_900  ←  هوليا. ساعدوني ⏎ ماذا حدث؟",
        "♻ 0_12_13_200__0_12_15_000  ←  أريد أمي",
        "✓ 0_12_15_400__0_12_17_100  ←  بخير بخير أجل أنا بخير",
        "✖ 0_12_17_800__0_12_19_000  —  OpenRouter: انتهت مهلة الاتصال",
        "✓ 0_12_19_500__0_12_21_200  ←  هوليا. استيقظي"
    ).mapIndexed { i, t -> LogLine(100L - i, t) }

    /** Video «الصور ثم النص», stage 2: every image is out, the engine reads them. */
    private val running = JobState.Running(
        Progress(
            title = "الحلقة 12", phase = "المرحلة 2 من 2 · قراءة النصوص بـ Gemini (gemini-3.5-flash-lite)", fraction = 0.77f,
            done = 418, total = 996, ok = 402, empty = 11, failed = 5, cached = 0, retries = 7,
            speed = 3.4, etaSec = 172.0, unitIndex = 0, unitCount = 1, decodeFraction = 1f, log = log,
            elapsedSec = 431.0, frames = 64410, framesPerSec = 238.0, subtitlesFound = 996, stage = 2
        )
    )

    /** Stage 1: the images are being extracted; reading starts when they are all out. */
    private val runningImages = JobState.Running(
        Progress(
            title = "الحلقة 12", phase = "المرحلة 1 من 2 · استخراج الصور 64% • 41230 إطار • 612 ترجمة", fraction = 0.38f,
            done = 0, total = 612, ok = 0, empty = 0, failed = 0, cached = 0, retries = 0,
            speed = 0.0, etaSec = 98.0, unitIndex = 0, unitCount = 1, decodeFraction = 0.64f,
            log = listOf(
                "🖼 00:12:11,000 → 00:12:12,900  (41 إطار)",
                "🖼 00:12:13,200 → 00:12:15,000  (38 إطار)",
                "🖼 00:12:15,400 → 00:12:17,100  (35 إطار)",
                "✓ Google Lens جاهز — المرحلة 1: استخراج صور الترجمة كلها، ثم المرحلة 2: قراءتها تلقائيًا"
            ).mapIndexed { i, t -> LogLine(10L - i, t) },
            elapsedSec = 175.0, frames = 41230, framesPerSec = 238.0, subtitlesFound = 612, stage = 1
        )
    )

    private val done = JobState.Done(
        outputs = listOf(
            JobOutput(
                "الحلقة 12", Uri.parse("content://sample/episode12.srt"),
                listOf(SubEntry(724310, 726120, "هوليا هي آخر همي الآن"), SubEntry(726900, 728400, "- هوليا\n- ابتعدي يا جوليزار")),
                savedIn = "Movies/Subtitles", folderForSave = null
            )
        ),
        stopped = false, elapsedSec = 412.0, summary = "اكتمل الاستخراج: 612 ترجمة"
    )

    private fun shot(name: String, tab: Tab, state: JobState = JobState.Idle, before: (() -> Unit)? = null) {
        JobManager.preview(state)
        rule.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    Shell(vm, tab, onTab = {}, onOpenRoi = {}, onOpenEditor = {})
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        if (before != null) {
            before()
            rule.waitForIdle()
        }
        val view = rule.activity.window.decorView
        val bmp = runCatching { hardwareShot(view) }.getOrElse {
            println("hardware capture failed for $name: $it — software drawing")
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { b -> view.draw(android.graphics.Canvas(b)) }
        }
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        JobManager.preview(JobState.Idle)
    }

    /** A frame of a drama with a two-line subtitle, the upper line high in the picture. */
    private fun sampleFrame(w: Int, h: Int, shade: Int = 0): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            android.graphics.Color.rgb(70 + shade, 92, 104), android.graphics.Color.rgb(176, 140 - shade, 112), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = android.graphics.Color.rgb(60, 44, 38)
        c.drawCircle(w * 0.62f, h * 0.42f, h * 0.30f, p)
        p.color = android.graphics.Color.rgb(32, 30, 34)
        c.drawCircle(w * 0.30f, h * 0.55f, h * 0.36f, p)
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.textSize = h * 0.075f
        for ((line, y) in listOf("- لم أنظر إلى أي امرأة غيرك قط" to h * 0.70f, "- أعرف ذلك" to h * 0.82f)) {
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = h * 0.012f; p.color = android.graphics.Color.BLACK
            c.drawText(line, w / 2f, y, p)
            p.style = android.graphics.Paint.Style.FILL; p.color = android.graphics.Color.WHITE
            c.drawText(line, w / 2f, y, p)
        }
        return b
    }

    private fun roiShot(name: String) {
        rule.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val info = com.abdulrahman.hardsubocr.video.VideoInfo(2_532_000_000L, 1920, 1080, 0, 23.976f, "video/avc")
                        vm.showPreviewForTest(sampleFrame(1280, 720), info, List(16) { sampleFrame(240, 135, it * 6) }, 755_340_000L)
                    }
                    RoiScreen(vm, onBack = {})
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = runCatching { hardwareShot(view) }.getOrElse {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { b -> view.draw(android.graphics.Canvas(b)) }
        }
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The region screen: picture with grips, the edges in numbers, presets, the timeline strip and steps. */
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_11_region() = roiShot("phone_11_region")

    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi") fun land_6_region() = roiShot("land_6_region")

    /**
     * The window drawn by the GPU renderer (Robolectric's native HardwareRenderer), as on a phone: render
     * nodes and the frosted glass's blur included. (captureToImage waits for a frame Robolectric never
     * draws; a software canvas cannot draw render nodes.)
     */
    private fun hardwareShot(view: android.view.View): Bitmap {
        val w = view.width
        val h = view.height
        val node = android.graphics.RenderNode("shot").apply { setPosition(0, 0, w, h) }
        val c = node.beginRecording()
        view.draw(c)
        node.endRecording()
        val reader = android.media.ImageReader.newInstance(
            w, h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = android.graphics.HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            try {
                val hb = image.hardwareBuffer!!
                val wrapped = Bitmap.wrapHardwareBuffer(hb, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))!!
                return wrapped.copy(Bitmap.Config.ARGB_8888, false).also { hb.close() }
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    // phone held upright (1080 × 2340, dark)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_1_extract() = shot("phone_1_extract", Tab.EXTRACT)

    /** Video source: «الصور ثم النص» (two stages, the engine named) next to «حفظ الصور فقط». */
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_12_video_stages() = shot("phone_12_video_stages", Tab.EXTRACT) {
        rule.onNodeWithText("اكتشاف تلقائي للترجمة").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("الصور ثم النص").performScrollTo()
        rule.mainClock.advanceTimeBy(1_000)
    }
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_2_running() = shot("phone_2_running", Tab.PROCESS, running)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_3_done() = shot("phone_3_done", Tab.PROCESS, done)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_4_idle() = shot("phone_4_idle", Tab.PROCESS)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_5_settings() = shot("phone_5_settings", Tab.SETTINGS)
    @Test @Config(qualifiers = "w411dp-h891dp-port-notnight-xxhdpi") fun phone_6_extract_light() = shot("phone_6_extract_light", Tab.EXTRACT)
    @Test @Config(qualifiers = "w360dp-h780dp-port-night-xxhdpi") fun phone_7_small_running() = shot("phone_7_small_running", Tab.PROCESS, runningImages)

    /** The tab bar's lens held and dragged halfway from the first tab toward the second. */
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_8_lens_held() = shot("phone_8_lens_held", Tab.EXTRACT) {
        val a = rule.onNodeWithText("الاستخراج").fetchSemanticsNode().boundsInRoot.center
        val b = rule.onNodeWithText("المعالجة").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput {
            down(a)
            for (i in 1..8) moveTo(a + (b - a) * (i / 16f))
        }
        rule.mainClock.advanceTimeBy(600)
    }

    /** A settings page: options, segmented control, switches. */
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_9_settings_video() = shot("phone_9_settings_video", Tab.SETTINGS) {
        rule.onNodeWithText("الفيديو").performClick()
        rule.mainClock.advanceTimeBy(1_500)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-notnight-xxhdpi") fun phone_10_settings_video_light() = shot("phone_10_settings_video_light", Tab.SETTINGS) {
        rule.onNodeWithText("الفيديو").performClick()
        rule.mainClock.advanceTimeBy(1_500)
    }

    // phone on its side (2340 × 1080, dark)
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi") fun land_1_extract() = shot("land_1_extract", Tab.EXTRACT)
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi") fun land_2_running() = shot("land_2_running", Tab.PROCESS, running)
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi") fun land_3_settings() = shot("land_3_settings", Tab.SETTINGS)

    // tablet (2560 × 1600 on its side, 1600 × 2560 upright)
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi") fun tablet_1_extract() = shot("tablet_1_extract", Tab.EXTRACT)
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi") fun tablet_2_running() = shot("tablet_2_running", Tab.PROCESS, running)
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi") fun tablet_3_done() = shot("tablet_3_done", Tab.PROCESS, done)
    @Test @Config(qualifiers = "w1280dp-h800dp-land-night-xhdpi") fun tablet_4_settings() = shot("tablet_4_settings", Tab.SETTINGS)
    @Test @Config(qualifiers = "w800dp-h1280dp-port-night-xhdpi") fun tablet_5_upright_running() = shot("tablet_5_upright_running", Tab.PROCESS, running)
    @Test @Config(qualifiers = "w800dp-h1280dp-port-night-xhdpi") fun tablet_6_upright_extract() = shot("tablet_6_upright_extract", Tab.EXTRACT)
    /** Settings on a phone on its side, both panes scrolled: the bars above them stay clear (3.0.0 drew dark boxes). */
    @Test @Config(qualifiers = "w891dp-h411dp-land-night-xxhdpi") fun land_5_settings_scrolled() = shot("land_5_settings_scrolled", Tab.SETTINGS) {
        rule.onRoot().performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.35f, durationMillis = 400) }
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.3f, height * 0.85f))
            for (i in 1..10) moveTo(Offset(width * 0.3f, height * (0.85f - 0.05f * i)))
            up()
        }
        rule.mainClock.advanceTimeBy(1_000)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-notnight-xxhdpi") fun land_4_running_light() = shot("land_4_running_light", Tab.PROCESS, running)
}
