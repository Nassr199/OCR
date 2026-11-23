package com.abdulrahman.hardsubocr.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abdulrahman.hardsubocr.ui.theme.AppTheme

enum class Screen { HOME, SETTINGS, ROI, EDITOR }

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
    val goHome = {
        if (screen == Screen.EDITOR) vm.closeEditor()
        screen = Screen.HOME
    }
    BackHandler(enabled = screen != Screen.HOME) { goHome() }

    // Pages move along the reading direction (Material shared axis X): a page opened from the
    // home screen comes in from the left in Arabic, and goes back the same way it came.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val distance = with(LocalDensity.current) { Motion.AXIS_DISTANCE.roundToPx() }
    AnimatedContent(
        targetState = screen,
        transitionSpec = { Motion.sharedAxisX(forward = targetState != Screen.HOME, rtl = rtl, distancePx = distance) },
        label = "screen"
    ) { s ->
        when (s) {
            Screen.HOME -> HomeScreen(
                vm,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenRoi = { vm.openRoiEditor(); screen = Screen.ROI },
                onOpenEditor = { i -> vm.openEditor(i); screen = Screen.EDITOR }
            )
            Screen.SETTINGS -> SettingsScreen(vm, onBack = goHome)
            Screen.ROI -> RoiScreen(vm, onBack = goHome)
            Screen.EDITOR -> if (vm.editIndex == null) {
                LaunchedEffect(Unit) { screen = Screen.HOME }
            } else {
                ResultScreen(vm, onBack = goHome)
            }
        }
    }
}
