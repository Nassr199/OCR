package com.abdulrahman.hardsubocr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

val Teal = Color(0xFF5FD4C4)
val Amber = Color(0xFFF2B544)
val Good = Color(0xFF4CC38A)
val Bad = Color(0xFFFF7A6E)

private val DarkScheme = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0B4F4A),
    onPrimaryContainer = Color(0xFFB8F2E9),
    secondary = Amber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF4A3A10),
    onSecondaryContainer = Color(0xFFFFE1A6),
    tertiary = Color(0xFF9CCBFF),
    background = Color(0xFF0F1716),
    onBackground = Color(0xFFE1EAE8),
    surface = Color(0xFF0F1716),
    onSurface = Color(0xFFE1EAE8),
    surfaceVariant = Color(0xFF1F2B29),
    onSurfaceVariant = Color(0xFFBCCAC7),
    surfaceContainerLowest = Color(0xFF0A1110),
    surfaceContainerLow = Color(0xFF151F1E),
    surfaceContainer = Color(0xFF192423),
    surfaceContainerHigh = Color(0xFF212E2C),
    surfaceContainerHighest = Color(0xFF2B3836),
    outline = Color(0xFF879492),
    outlineVariant = Color(0xFF3D4947),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B62),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9FF2E5),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF7D5700),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA8),
    onSecondaryContainer = Color(0xFF271900),
    tertiary = Color(0xFF2F628C),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF5F3),
    surfaceContainer = Color(0xFFE8EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE4E2),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6)
)

/**
 * Every text of the app in Thmanyah Sans: titles and big numbers (display, headline, title) in Bold
 * and Black, what is read at length (body: hints, logs, the subtitles) in Regular, labels (buttons,
 * tabs, chips) in Medium. Its lines are 1.25 em tall (ascender 1000, descender 250 per 1000), so every
 * line height below leaves the marks above and below Arabic letters room. No letter spacing: Arabic
 * letters join. Without the font in this build, Material's defaults.
 */
fun appTypography(family: FontFamily?): Typography {
    val t = Typography()
    if (family == null) return t
    val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    // titles: line height as a share of the size (as before); text and labels: Material's own (≥ 1.33 em)
    fun TextStyle.d(w: FontWeight, lh: Float = 1.32f) =
        copy(fontFamily = family, fontWeight = w, lineHeight = (fontSize.value * lh).sp, lineHeightStyle = trim, letterSpacing = 0.sp)
    fun TextStyle.f(w: FontWeight) =
        copy(fontFamily = family, fontWeight = w, lineHeightStyle = trim, letterSpacing = 0.sp)
    return t.copy(
        displayLarge = t.displayLarge.d(FontWeight.Black),
        displayMedium = t.displayMedium.d(FontWeight.Black),
        displaySmall = t.displaySmall.d(FontWeight.Bold),
        headlineLarge = t.headlineLarge.d(FontWeight.Bold),
        headlineMedium = t.headlineMedium.d(FontWeight.Bold),
        headlineSmall = t.headlineSmall.d(FontWeight.Bold),
        titleLarge = t.titleLarge.d(FontWeight.Bold),
        titleMedium = t.titleMedium.d(FontWeight.Bold, 1.36f),
        titleSmall = t.titleSmall.f(FontWeight.Medium),
        bodyLarge = t.bodyLarge.f(FontWeight.Normal),
        bodyMedium = t.bodyMedium.f(FontWeight.Normal),
        bodySmall = t.bodySmall.f(FontWeight.Normal),
        labelLarge = t.labelLarge.f(FontWeight.Medium),
        labelMedium = t.labelMedium.f(FontWeight.Medium),
        labelSmall = t.labelSmall.f(FontWeight.Medium)
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val typography = remember { appTypography(AppFonts.family(context)) }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = typography) {
        // The whole interface is Arabic: force right-to-left even on an English phone.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
