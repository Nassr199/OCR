@file:OptIn(ExperimentalTextApi::class)

package com.abdulrahman.hardsubocr.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import java.nio.ByteBuffer

/**
 * Thmanyah Sans (ثمانية سانس, © Thmanyah Publishing & Distribution), the app's typeface for every
 * text, used under the Thmanyah font license. The license allows the font inside an app only as part
 * of a packaged or obfuscated product and forbids making the font files extractable, so the APK
 * carries each of the five weights scrambled (assets/type/tNNN.bin, made by tools/pack_fonts.py) and
 * it is unscrambled in memory here: on Android 10+ it never exists as a file at all; on Android 8–9 it
 * exists for the instant the system takes to map it, in the app's private storage. A build without
 * the font (no key in CI) has no assets, and the app uses the system font.
 */
object AppFonts {
    private const val BASE = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    /** Light, Regular, Medium, Bold, Black. */
    private val WEIGHTS = intArrayOf(300, 400, 500, 700, 900)

    @Volatile private var loaded = false
    @Volatile private var family: FontFamily? = null

    /** The app's family (all five weights), or null when this build carries no font (or it could not be read). */
    fun family(context: Context): FontFamily? {
        if (loaded) return family
        synchronized(this) {
            if (!loaded) {
                family = runCatching { build(context.applicationContext) }.getOrNull()
                loaded = true
            }
        }
        return family
    }

    private fun build(context: Context): FontFamily? {
        val present = context.assets.list("type")?.toSet().orEmpty()
        val fonts = WEIGHTS.filter { "t$it.bin" in present }.mapNotNull { w ->
            val tf = typeface(context, w) ?: return@mapNotNull null
            ScrambledFont(FontWeight(w), tf)
        }
        return if (fonts.size == WEIGHTS.size) FontFamily(fonts) else null
    }

    private fun typeface(context: Context, weight: Int): Typeface? {
        val raw = context.assets.open("type/t$weight.bin").use { it.readBytes() }
        if (raw.size < 8 || !raw.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        return typefaceFrom(context, unscramble(raw, 4, weight), weight)
    }

    /** A typeface from the font's bytes: in memory on Android 10+, through a private file before. */
    internal fun typefaceFrom(context: Context, otf: ByteArray, weight: Int): Typeface? =
        if (Build.VERSION.SDK_INT >= 29) inMemory(otf, weight) else viaPrivateFile(context, otf, weight)

    /** The inverse of tools/pack_fonts.py `scramble` (the same xorshift stream, XOR is its own inverse). */
    internal fun unscramble(src: ByteArray, offset: Int, weight: Int): ByteArray {
        var x = BASE xor (weight.toLong() * 0x100000001B3L)
        if (x == 0L) x = 1L
        val out = ByteArray(src.size - offset)
        for (i in out.indices) {
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            out[i] = (src[i + offset].toInt() xor (x ushr 56).toInt()).toByte()
        }
        return out
    }

    @RequiresApi(29)
    private fun inMemory(otf: ByteArray, weight: Int): Typeface {
        val buf = ByteBuffer.allocateDirect(otf.size).put(otf)
        buf.flip()
        val font = android.graphics.fonts.Font.Builder(buf).setWeight(weight)
            .setSlant(android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build()
        val fam = android.graphics.fonts.FontFamily.Builder(font).build()
        return Typeface.CustomFallbackBuilder(fam)
            .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif")
            .build()
    }

    private fun viaPrivateFile(context: Context, otf: ByteArray, weight: Int): Typeface? {
        val f = File(context.noBackupFilesDir, ".t$weight")
        return try {
            f.writeBytes(otf)
            Typeface.Builder(f).setWeight(weight).setItalic(false).build()
        } finally {
            f.delete()
        }
    }

    /** One weight, already loaded: Compose matches weights across the five and never fakes bold. */
    private class ScrambledFont(override val weight: FontWeight, val typeface: Typeface) :
        AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
        override val style: FontStyle = FontStyle.Normal
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = (font as? ScrambledFont)?.typeface
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = (font as? ScrambledFont)?.typeface
    }
}
