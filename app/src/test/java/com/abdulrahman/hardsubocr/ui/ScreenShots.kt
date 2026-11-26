package com.abdulrahman.hardsubocr.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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

    private val running = JobState.Running(
        Progress(
            title = "الحلقة 12", phase = "قراءة النصوص بـ Gemini 3.5 Flash-Lite", fraction = 0.42f,
            done = 418, total = 996, ok = 402, empty = 11, failed = 5, cached = 0, retries = 7,
            speed = 3.4, etaSec = 172.0, unitIndex = 0, unitCount = 1, decodeFraction = 0.64f, log = log,
            elapsedSec = 131.0, frames = 41230, framesPerSec = 238.0, subtitlesFound = 612
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

    private fun shot(name: String, tab: Tab, state: JobState = JobState.Idle) {
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
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        JobManager.preview(JobState.Idle)
    }

    // phone held upright (1080 × 2340, dark)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_1_extract() = shot("phone_1_extract", Tab.EXTRACT)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_2_running() = shot("phone_2_running", Tab.PROCESS, running)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_3_done() = shot("phone_3_done", Tab.PROCESS, done)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_4_idle() = shot("phone_4_idle", Tab.PROCESS)
    @Test @Config(qualifiers = "w411dp-h891dp-port-night-xxhdpi") fun phone_5_settings() = shot("phone_5_settings", Tab.SETTINGS)
    @Test @Config(qualifiers = "w411dp-h891dp-port-notnight-xxhdpi") fun phone_6_extract_light() = shot("phone_6_extract_light", Tab.EXTRACT)
    @Test @Config(qualifiers = "w360dp-h780dp-port-night-xxhdpi") fun phone_7_small_running() = shot("phone_7_small_running", Tab.PROCESS, running)

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
}
