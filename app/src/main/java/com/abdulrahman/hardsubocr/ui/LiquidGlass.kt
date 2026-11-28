package com.abdulrahman.hardsubocr.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ============================================================================ liquid glass
//
// Liquid glass bends light instead of only scattering it. Each piece of glass samples what lies
// under it from an area a little larger than itself: near its rim the surface curves down, so the
// rim shows a compressed, bent image of what is just outside the glass, with a slight colour split;
// the middle is frosted (or clear, for a lens). The edge is lit from above rather than outlined.
//
// The whole material is one AGSL shader run by Android's GPU renderer (RenderEffect, Android 13+),
// on the device's own graphics API: Vulkan where the system draws with Vulkan, OpenGL ES elsewhere.
// Nothing of it runs on the CPU per frame. Android 12 gets the frosted blur alone, Android 8–11 a tint.
//
// The optical model follows the design notes of shayann07/liquidglass (Apache License 2.0):
// displacement before blur, a sample region larger than the element, a rim sharper than the middle,
// a lit edge instead of a drawn outline. This shader, its profile and its numbers are this app's own.

/**
 * The glass shader (AGSL). Its input `content` is laid out as the host records it: the backdrop
 * under the glass with [uPad] px to spare on every side, and below that (from row [uStrip]) the same
 * backdrop blurred at [uWide] scale for the frosted interior.
 */
internal const val LIQUID_GLASS_SKSL = """
uniform shader content;

uniform float2 uSize;      // the glass, px
uniform float  uPad;       // backdrop recorded beyond each edge, px
uniform float  uRadius;    // corner radius, px
uniform float  uStrip;     // row where the blurred copy starts; 0 = none
uniform float  uWide;      // scale of that copy
uniform float  uBand;      // px from the rim over which the surface curves
uniform float  uDepth;     // px of displacement at the rim
uniform float  uSplit;     // colour split, a share of the displacement
uniform float  uFrost;     // 0 clear, 1 frosted interior
uniform float  uMag;       // interior magnification (a lens held up), 0 = none
uniform float4 uTint;      // rgb, strength
uniform float  uVibrance;  // saturation of what shows through
uniform float2 uLight;     // unit vector toward the light (y down)
uniform float  uSpecular;  // highlight on the lit edge
uniform float  uEdge;      // px over which the edge light fades inward
uniform float  uDark;      // 1 dark glass, 0 light glass

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

// Outward normal of the rounded box. Exact wherever the curved band can reach: the host keeps
// the band no wider than the corner radius, so the flat interior never needs one.
float2 boxNormal(float2 p, float2 b, float r) {
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 q = abs(p) - b + float2(r);
    if (q.x > 0.0 && q.y > 0.0) { return s * normalize(q); }
    if (q.x > q.y) { return float2(s.x, 0.0); }
    return float2(0.0, s.y);
}

float3 sharpAt(float2 p) {
    float2 lim = uSize + float2(2.0 * uPad);
    return float3(content.eval(clamp(p, float2(0.5), lim - float2(0.5))).rgb);
}

float3 softAt(float2 p) {
    float2 lim = (uSize + float2(2.0 * uPad)) * uWide;
    float2 q = clamp(p * uWide, float2(0.5), lim - float2(0.5));
    return float3(content.eval(float2(q.x, uStrip + q.y)).rgb);
}

half4 main(float2 coord) {
    float2 hs = uSize * 0.5;
    float2 p = coord - float2(uPad) - hs;
    float r = min(uRadius, min(hs.x, hs.y));
    float d = sdBox(p, hs, r);
    float cover = clamp(0.5 - d, 0.0, 1.0);
    if (cover <= 0.0) { return half4(0.0); }

    // A circular bevel: flat inside the band, steepest at the rim.
    float depth = max(-d, 0.0);
    float2 n = boxNormal(p, hs, r);
    float band = max(uBand, 1.0);
    float x = clamp(1.0 - depth / band, 0.0, 1.0);
    float bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * smoothstep(0.0, 1.5, depth);

    // Sampling outward pulls the surroundings in and compresses them into the rim.
    float2 base = coord - p * (uMag / (1.0 + uMag));
    float2 push = n * (bend * uDepth);
    float3 col = float3(
        sharpAt(base + push * (1.0 - uSplit)).r,
        sharpAt(base + push).g,
        sharpAt(base + push * (1.0 + uSplit)).b);

    // Frosted in the middle, clearer toward the rim, where the bent image shows.
    if (uFrost > 0.0 && uStrip > 0.0) {
        float frost = uFrost * mix(0.45, 1.0, smoothstep(0.0, 1.0, 1.0 - x));
        col = mix(col, softAt(base + push), frost);
    }

    float l = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(float3(l), col, uVibrance), 0.0, 1.0);
    col = mix(col, uTint.rgb, uTint.a);

    // The edge catches the light where it faces it, and a little on the far side.
    float rim = exp(-depth / max(uEdge, 0.5));
    float lit = max(dot(n, uLight), 0.0);
    float back = max(-dot(n, uLight), 0.0);
    float spec = (lit * lit * lit + 0.5 * back * back * back) * rim;
    float sheen = x * x * mix(0.055, 0.035, uDark);
    col = clamp(col + float3(spec * uSpecular + sheen), 0.0, 1.0);
    return half4(half3(col * cover), half(cover));
}
"""

/**
 * The source the app compiles. Android 13+ samples a child shader with `content.eval(p)` (AGSL). The
 * JVM tests run Robolectric's older Skia, which only knows `sample(content, p)`: with the system
 * property `hsocr.legacySksl` (set by the test task only) the same shader is compiled in that form, so
 * the tests check and draw the real material.
 */
internal val LIQUID_GLASS_SOURCE: String
    get() = if (System.getProperty("hsocr.legacySksl") == "1") LIQUID_GLASS_SKSL.replace("content.eval(", "sample(content, ")
    else LIQUID_GLASS_SKSL

/** How one piece of glass looks. Lengths are in dp. */
@Immutable
data class GlassLook(
    /** Colour laid over what shows through; its alpha is the strength. */
    val tint: Color,
    /** 0 clear (a lens), 1 frosted. */
    val frost: Float = 1f,
    /** Blur of the frosted interior. */
    val blur: Dp = 22.dp,
    /** Width of the curved rim. */
    val band: Dp = 14.dp,
    /** How far the rim bends what lies outside it. */
    val depth: Dp = 9.dp,
    val split: Float = 0.12f,
    val vibrance: Float = 1.4f,
    val specular: Float = 0.7f,
    val edge: Dp = 1.5.dp,
    /** Interior magnification: a lens held up. */
    val magnify: Float = 0f,
    /** Tint of the frosted fallback (Android 8–12), where nothing is bent. */
    val fallbackTint: Color = tint
)

/** Light from above, a little from the start side. */
private const val LIGHT_X = -0.34f
private const val LIGHT_Y = -0.94f

/** The shader's inputs for one draw. */
internal data class GlassUniforms(
    val w: Float, val h: Float, val pad: Float, val radius: Float, val strip: Float, val wide: Float,
    val band: Float, val depth: Float, val split: Float, val frost: Float, val mag: Float,
    val tint: Color, val vibrance: Float, val specular: Float, val edge: Float, val dark: Float
) {
    @RequiresApi(33)
    fun applyTo(s: RuntimeShader) {
        s.setFloatUniform("uSize", w, h)
        s.setFloatUniform("uPad", pad)
        s.setFloatUniform("uRadius", radius)
        s.setFloatUniform("uStrip", strip)
        s.setFloatUniform("uWide", wide)
        s.setFloatUniform("uBand", band)
        s.setFloatUniform("uDepth", depth)
        s.setFloatUniform("uSplit", split)
        s.setFloatUniform("uFrost", frost)
        s.setFloatUniform("uMag", mag)
        s.setFloatUniform("uTint", tint.red, tint.green, tint.blue, tint.alpha)
        s.setFloatUniform("uVibrance", vibrance)
        s.setFloatUniform("uLight", LIGHT_X, LIGHT_Y)
        s.setFloatUniform("uSpecular", specular)
        s.setFloatUniform("uEdge", edge)
        s.setFloatUniform("uDark", dark)
    }

    companion object {
        val Probe = GlassUniforms(100f, 40f, 10f, 20f, 0f, 0.25f, 12f, 8f, 0.1f, 0f, 0f, Color.Black, 1f, 0.5f, 2f, 1f)
    }
}

/** One compiled shader and the effect made from it; a new effect only when an input changes. */
@RequiresApi(33)
internal class LiquidMaterial {
    val shader = RuntimeShader(LIQUID_GLASS_SOURCE)
    private var last: GlassUniforms? = null
    private var effect: RenderEffect? = null

    fun effect(u: GlassUniforms): RenderEffect {
        val e = effect
        if (e != null && u == last) return e
        u.applyTo(shader)
        last = u
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect().also { effect = it }
    }
}

/** Whether the real material runs here: Android 13+ and the shader compiled on this device. */
internal object LiquidShader {
    /**
     * Tests only (system property `hsocr.glassPaths`, set by the test task): Robolectric cannot make the
     * effect, so without this the glass paths would never run there. With it every glass is recorded and
     * drawn exactly as on a phone, only without the shader on top.
     */
    internal val drawPathsWithoutShader: Boolean = System.getProperty("hsocr.glassPaths") == "1"

    /** Why the shader is not in use on this device (null when it is, or on Android 12 and older). */
    @Volatile
    var failure: Throwable? = null
        private set

    val available: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && runCatching { probe() }.onFailure { failure = it }.isSuccess
    }

    @RequiresApi(33)
    private fun probe() {
        LiquidMaterial().effect(GlassUniforms.Probe)
    }
}

/** What one piece of glass keeps between frames: its shader (null only in tests) and the two layers it samples. */
internal class GlassParts(val material: Any?, val sampler: GraphicsLayer, val wide: GraphicsLayer)

@Composable
internal fun rememberGlassParts(): GlassParts? {
    if (Build.VERSION.SDK_INT < 33) return null
    val real = LiquidShader.available
    if (!real && !LiquidShader.drawPathsWithoutShader) return null
    val sampler = rememberGraphicsLayer()
    val wide = rememberGraphicsLayer()
    return remember(sampler, wide) { GlassParts(if (real) LiquidMaterial() else null, sampler, wide) }
}

/**
 * Draws a piece of liquid glass at [topLeft] / [size] of this scope. [under] draws what lies beneath
 * it, in this scope's coordinates; [base] is the opaque ground under that. [cornerPx] is the corner
 * radius (half the short side for a pill). [alpha] fades the whole piece.
 *
 * [under] runs inside the glass's own recording, which has a plain density of its own: it may convert
 * dp to px like any drawing.
 */
internal fun DrawScope.drawLiquid(
    parts: GlassParts,
    topLeft: Offset,
    size: Size,
    cornerPx: Float,
    look: GlassLook,
    base: Color,
    dark: Boolean,
    alpha: Float = 1f,
    under: DrawScope.() -> Unit
) {
    if (Build.VERSION.SDK_INT < 33) return
    if (size.width < 2f || size.height < 2f || alpha <= 0f) return
    val material = parts.material as LiquidMaterial?
    val radius = min(cornerPx, min(size.width, size.height) / 2f)
    // the curved band stays within the corner radius, where the shader's normal is exact
    val band = min(look.band.toPx(), max(radius * 0.95f, 1f))
    val depth = look.depth.toPx()
    val pad = ceil(depth * (1f + look.split) + 2f).toInt()
    val w = ceil(size.width).toInt() + 2 * pad
    val h = ceil(size.height).toInt() + 2 * pad
    val frosted = look.frost > 0.01f
    val ws = 0.25f
    val stripY = if (frosted) h + 1 else 0
    val stripH = if (frosted) ceil(h * ws).toInt() + 2 else 0
    val dx = pad - topLeft.x
    val dy = pad - topLeft.y
    // Recorded with a plain density, not through the node's own scope: inside a layer recorded from a
    // node's draw, Compose 1.7 resolves the density in a loop (the 3.0.0 crash on every switch).
    val plain = Density(density, fontScale)

    if (frosted) {
        val blur = look.blur.toPx() * ws
        parts.wide.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
        parts.wide.record(plain, layoutDirection, IntSize(ceil(w * ws).toInt() + 1, stripH)) {
            drawRect(base)
            scale(ws, ws, pivot = Offset.Zero) { translate(dx, dy) { under() } }
        }
    }
    val u = GlassUniforms(
        w = size.width, h = size.height, pad = pad.toFloat(), radius = radius,
        strip = stripY.toFloat(), wide = ws, band = band, depth = depth, split = look.split,
        frost = look.frost, mag = look.magnify, tint = look.tint, vibrance = look.vibrance,
        specular = look.specular, edge = look.edge.toPx(), dark = if (dark) 1f else 0f
    )
    parts.sampler.renderEffect = material?.effect(u)
    parts.sampler.alpha = alpha.coerceIn(0f, 1f)
    parts.sampler.record(plain, layoutDirection, IntSize(w, h + stripH + 1)) {
        drawRect(base, size = Size(w.toFloat(), h.toFloat()))
        clipRect(0f, 0f, w.toFloat(), h.toFloat()) { translate(dx, dy) { under() } }
        if (frosted) translate(0f, stripY.toFloat()) { drawLayer(parts.wide) }
    }
    translate(-dx, -dy) { drawLayer(parts.sampler) }
}

/**
 * Liquid glass behind the element, over [backdrop]. A pill unless [corner] is given. Android 12 gets
 * the frosted blur of [glass], Android 8–11 a nearly opaque tint.
 */
@Composable
fun Modifier.liquidGlass(backdrop: Backdrop, look: GlassLook, corner: Dp? = null, base: Color = MaterialTheme.colorScheme.background): Modifier {
    val parts = rememberGlassParts()
    if (parts == null) {
        val shape = if (corner == null) androidx.compose.foundation.shape.RoundedCornerShape(percent = 50) else androidx.compose.foundation.shape.RoundedCornerShape(corner)
        return this.glass(backdrop, shape, look.fallbackTint)
    }
    val dark = isDarkScheme()
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val r = corner?.toPx() ?: (min(size.width, size.height) / 2f)
            if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                // a View drawn into a Bitmap cannot run the effect: the tint alone
                drawRoundRect(look.fallbackTint.copy(alpha = max(look.fallbackTint.alpha, 0.94f)), cornerRadius = CornerRadius(r))
                return@drawBehind
            }
            drawLiquid(parts, Offset.Zero, size, r, look, base, dark) { backdrop.drawAt(this, origin) }
        }
}
