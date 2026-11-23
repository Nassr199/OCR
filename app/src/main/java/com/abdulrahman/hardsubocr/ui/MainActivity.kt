package com.abdulrahman.hardsubocr.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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

    // Screens fade and settle in (direction-neutral: right for the Arabic layout, left for English)
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.HOME
            (fadeIn(tween(260, delayMillis = 40)) + scaleIn(tween(260), initialScale = if (forward) 0.96f else 1.03f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = if (forward) 1.02f else 0.97f))
        },
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
