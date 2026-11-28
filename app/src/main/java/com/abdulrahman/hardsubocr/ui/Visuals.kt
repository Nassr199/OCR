package com.abdulrahman.hardsubocr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlin.math.max

// ============================================================================ icons

/** The app's own icons (Material shapes, 24 × 24), so the tabs read as one set. */
object AppIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = false)
        for (d in paths) b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
        return b.build()
    }

    /** Subtitles: a screen with two lines of text — the extraction tab. */
    val Subtitles: ImageVector = icon(
        "subtitles",
        "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z"
    )

    /** A dial: the processing tab. */
    val Activity: ImageVector = icon(
        "activity",
        "M16.24,7.76C15.07,6.59 13.54,6 12,6v6l-4.24,4.24c2.34,2.34 6.14,2.34 8.49,0 2.34,-2.34 2.34,-6.14 -0.01,-8.48zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z"
    )

    /** Sliders: the settings tab. */
    val Tune: ImageVector = icon(
        "tune",
        "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"
    )

    val Play: ImageVector = icon("play", "M8,5v14l11,-7z")

    /** Stop: a square with soft corners. */
    val Stop: ImageVector = icon("stop", "M8,6h8c1.1,0 2,0.9 2,2v8c0,1.1 -0.9,2 -2,2H8c-1.1,0 -2,-0.9 -2,-2V8c0,-1.1 0.9,-2 2,-2z")
}

// ============================================================================ entrance

/**
 * Entrance of a card or a row: it rises 24 dp, fades in and settles from 97 % on a soft spring, one
 * after another in reading order ([index] × 45 ms, the first eight). Runs when the element first
 * appears — a tab or a page opening — and again when [key] changes.
 */
@Composable
fun Modifier.reveal(index: Int, key: Any? = Unit): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(45L * index.coerceIn(0, 8))
        a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 240f))
    }
    val rise = with(LocalDensity.current) { 24.dp.toPx() }
    return this.graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * rise
        val s = 0.97f + 0.03f * v
        scaleX = s; scaleY = s
    }
}

// ============================================================================ background

/**
 * The surface under every tab: the theme's background with two soft glows (teal from the top
 * corner, amber low on the other side). Static — nothing redraws while a long job runs — and it is
 * what the frosted bar shows when nothing scrolls under it.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val teal = scheme.primary.copy(alpha = if (dark) 0.16f else 0.12f)
    val amber = scheme.secondary.copy(alpha = if (dark) 0.07f else 0.08f)
    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .drawBehind {
                val r = max(size.width, size.height)
                drawCircle(Brush.radialGradient(listOf(teal, Color.Transparent), Offset(size.width * 0.85f, 0f), r * 0.65f), r * 0.65f, Offset(size.width * 0.85f, 0f))
                drawCircle(Brush.radialGradient(listOf(amber, Color.Transparent), Offset(size.width * 0.1f, size.height * 0.92f), r * 0.55f), r * 0.55f, Offset(size.width * 0.1f, size.height * 0.92f))
            }
    )
}
