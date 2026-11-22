package com.abdulrahman.hardsubocr.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    when (screen) {
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
