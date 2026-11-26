package com.abdulrahman.hardsubocr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abdulrahman.hardsubocr.job.JobState
import com.abdulrahman.hardsubocr.ui.theme.AppTheme

/** Full-screen pages opened over the tabs. */
enum class Screen { HOME, ROI, EDITOR }

/** The three tabs of the app. */
enum class Tab { EXTRACT, PROCESS, SETTINGS }

/**
 * Room the navigation takes over a tab: the frosted bar at the bottom of a phone ([bottom]), or the
 * rail on the start side of a wide screen ([start]); [top] is the status bar.
 */
data class Chrome(val top: Dp, val bottom: Dp, val start: Dp, val wide: Boolean)

val LocalChrome = staticCompositionLocalOf { Chrome(0.dp, 0.dp, 0.dp, false) }

/** A width from which the screen is laid out in two columns with a side rail (tablets, phones on their side). */
val WIDE_FROM = 600.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var tab by rememberSaveable { mutableStateOf(Tab.EXTRACT) }
    val goHome = {
        if (screen == Screen.EDITOR) vm.closeEditor()
        screen = Screen.HOME
    }
    BackHandler(enabled = screen != Screen.HOME) { goHome() }
    // back from another tab returns to the extraction tab before it leaves the app
    BackHandler(enabled = screen == Screen.HOME && tab != Tab.EXTRACT) { tab = Tab.EXTRACT }

    // Pages move along the reading direction (Material shared axis X): a page opened from the
    // tabs comes in from the left in Arabic, and goes back the same way it came.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val distance = with(LocalDensity.current) { Motion.AXIS_DISTANCE.roundToPx() }
    AnimatedContent(
        targetState = screen,
        transitionSpec = { Motion.sharedAxisX(forward = targetState != Screen.HOME, rtl = rtl, distancePx = distance) },
        label = "screen"
    ) { s ->
        when (s) {
            Screen.HOME -> Shell(
                vm, tab, onTab = { tab = it },
                onOpenRoi = { vm.openRoiEditor(); screen = Screen.ROI },
                onOpenEditor = { i -> vm.openEditor(i); screen = Screen.EDITOR }
            )
            Screen.ROI -> RoiScreen(vm, onBack = goHome)
            Screen.EDITOR -> if (vm.editIndex == null) {
                LaunchedEffect(Unit) { screen = Screen.HOME }
            } else {
                ResultScreen(vm, onBack = goHome)
            }
        }
    }
}

/**
 * The tabs under frosted glass. A phone held upright has the bar at the bottom: the tabs, and beside
 * them the start / stop button. A wide screen has them as a rail on the start side, and its tabs lay
 * their cards out in two columns. A job that starts brings its tab forward.
 */
@Composable
fun Shell(vm: MainViewModel, tab: Tab, onTab: (Tab) -> Unit, onOpenRoi: () -> Unit, onOpenEditor: (Int) -> Unit) {
    val context = LocalContext.current
    val state by vm.job.collectAsState()
    val running = state is JobState.Running
    val backdrop = rememberBackdrop()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }
    LaunchedEffect(running) { if (running) onTab(Tab.PROCESS) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val action = {
        if (running) vm.stop()
        else {
            val problem = vm.canStart()
            if (problem != null) {
                vm.message = problem
            } else {
                val needAsk = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
            }
        }
    }
    val fraction = (state as? JobState.Running)?.progress?.fraction
    val items = listOf(
        NavItem("الاستخراج", AppIcons.Subtitles),
        NavItem("المعالجة", AppIcons.Activity, progress = fraction),
        NavItem("الإعدادات", AppIcons.Tune)
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_FROM
        val ld = LocalLayoutDirection.current
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val chrome = if (wide) {
            Chrome(top = top, bottom = safe.calculateBottomPadding() + 20.dp, start = safe.calculateStartPadding(ld) + 16.dp + 76.dp + 12.dp, wide = true)
        } else {
            Chrome(top = top, bottom = navBottom + 12.dp + 66.dp + 24.dp, start = 0.dp, wide = false)
        }

        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            AppBackground()
            CompositionLocalProvider(LocalChrome provides chrome) {
                AnimatedContent(targetState = tab, transitionSpec = { Motion.fadeThrough() }, label = "tab") { t ->
                    when (t) {
                        Tab.EXTRACT -> ExtractTab(vm, running, fraction, onOpenRoi, onShowProgress = { onTab(Tab.PROCESS) })
                        Tab.PROCESS -> ProcessTab(vm, state, onOpenEditor, onGoExtract = { onTab(Tab.EXTRACT) })
                        Tab.SETTINGS -> SettingsScreen(vm, onBack = { onTab(Tab.EXTRACT) }, asTab = true)
                    }
                }
            }
        }

        if (wide) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = safe.calculateStartPadding(ld) + 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GlassTabs(backdrop, items, tab.ordinal, { onTab(Tab.entries[it]) }, vertical = true)
                Spacer(Modifier.height(14.dp))
                GlassAction(backdrop, running, null, action)
            }
        } else {
            // the start button carries its label where the width allows (most phones), on the extraction tab
            val label = if (maxWidth >= 400.dp && tab == Tab.EXTRACT) (if (running) "إيقاف" else startLabel(vm)) else null
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassTabs(backdrop, items, tab.ordinal, { onTab(Tab.entries[it]) }, vertical = false)
                Spacer(Modifier.width(10.dp))
                GlassAction(backdrop, running, label, action)
            }
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = chrome.bottom - (if (wide) 0.dp else 12.dp), start = chrome.start)
        )
    }
}
