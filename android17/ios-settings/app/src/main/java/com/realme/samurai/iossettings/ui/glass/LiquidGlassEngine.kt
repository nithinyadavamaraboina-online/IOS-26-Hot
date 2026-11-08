package com.realme.samurai.iossettings.ui.glass

import android.content.Context
import android.database.ContentObserver
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.realme.samurai.iossettings.ui.theme.IosTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live system-wide Liquid Glass parameters synchronized with `iOS Styler` via `Settings.Global`.
 */
@Immutable
data class SystemGlassConfig(
    /** 0 = Clear Refractive, 1 = Tinted Frosted, 2 = Solid Matte. */
    val mode: Int = MODE_CLEAR_REFRACTIVE,
    val refractionPct: Int = DEFAULT_PCT,
    val frostPct: Int = DEFAULT_PCT,
) {
    val refractionScale: Float get() = refractionPct.coerceIn(0, MAX_PCT) / 100f
    val frostScale: Float get() = frostPct.coerceIn(0, MAX_PCT) / 100f
    val isClearRefractive: Boolean get() = mode == MODE_CLEAR_REFRACTIVE
    val isFrostedTinted: Boolean get() = mode == MODE_FROSTED_TINTED
    val isSolidMatte: Boolean get() = mode == MODE_SOLID_MATTE

    companion object {
        const val MODE_CLEAR_REFRACTIVE = 0
        const val MODE_FROSTED_TINTED = 1
        const val MODE_SOLID_MATTE = 2
        const val DEFAULT_PCT = 20
        const val MAX_PCT = 150

        const val KEY_LIQUID_GLASS_MODE = "samurai_liquid_glass_mode"
        const val KEY_REFRACTION_PCT = "samurai_ios26_refraction_pct"
        const val KEY_FROST_PCT = "samurai_ios26_frost_pct"

        fun read(context: Context): SystemGlassConfig = runCatching {
            val cr = context.contentResolver
            val mode = Settings.Global.getInt(cr, KEY_LIQUID_GLASS_MODE, MODE_CLEAR_REFRACTIVE)
                .coerceIn(MODE_CLEAR_REFRACTIVE, MODE_SOLID_MATTE)
            val refraction = Settings.Global.getInt(cr, KEY_REFRACTION_PCT, DEFAULT_PCT)
                .coerceIn(0, MAX_PCT)
            val frost = Settings.Global.getInt(cr, KEY_FROST_PCT, DEFAULT_PCT)
                .coerceIn(0, MAX_PCT)
            SystemGlassConfig(mode = mode, refractionPct = refraction, frostPct = frost)
        }.getOrDefault(SystemGlassConfig())
    }
}

val LocalSystemGlassConfig = staticCompositionLocalOf { SystemGlassConfig() }

@Composable
fun rememberSystemGlassConfig(): SystemGlassConfig {
    if (LocalInspectionMode.current) return SystemGlassConfig()
    val context = LocalContext.current
    var config by remember(context) { mutableStateOf(SystemGlassConfig.read(context)) }

    DisposableEffect(context) {
        val resolver = runCatching { context.contentResolver }.getOrNull()
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        val observer = if (resolver != null) {
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    config = SystemGlassConfig.read(context)
                }
            }
        } else null

        if (resolver != null && observer != null) {
            runCatching {
                listOf(
                    SystemGlassConfig.KEY_LIQUID_GLASS_MODE,
                    SystemGlassConfig.KEY_REFRACTION_PCT,
                    SystemGlassConfig.KEY_FROST_PCT,
                ).forEach { key ->
                    Settings.Global.getUriFor(key)?.let { uri ->
                        resolver.registerContentObserver(uri, false, observer)
                    }
                }
                config = SystemGlassConfig.read(context)
            }
        }
        onDispose {
            if (resolver != null && observer != null) {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }
    }
    return config
}

/**
 * Real hardware `GraphicsLayer` + AGSL `RenderEffect` Liquid Glass backdrop engine.
 * Used strictly on floating iOS 26 chrome (bottom `Search` capsule, `<` Back droplet,
 * floating toolbar/preview capsules) — NEVER on Settings content cards.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var recorded by mutableStateOf(false)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            drawContent()
            return@drawWithContent
        }
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
        if (!backdrop.recorded) backdrop.recorded = true
    }

data class GlassStyle(
    val blur: Dp = 6.dp,
    val refractionHeight: Dp = 15.dp,
    val refractionAmount: Dp = 18.dp,
    val dispersion: Float = 0.12f,
    val saturation: Float = 1.55f,
    val tint: Color? = null,
    val ignoreSystemScale: Boolean = false,
) {
    internal fun scaledBy(sysGlass: SystemGlassConfig): GlassStyle {
        if (ignoreSystemScale) return this
        if (sysGlass.isSolidMatte) {
            return copy(blur = 0.dp, refractionAmount = 0.dp, dispersion = 0f)
        }
        // Keep a subtle baseline optical lens on Settings floating chrome even at calibrated 20%
        // so scrolling rows visibly bend around the bottom Search pill & `<` Back droplet like iOS 26.
        val rScale = (0.45f + 0.55f * sysGlass.refractionScale).coerceIn(0f, 1.6f)
        val fScale = (0.45f + 0.55f * sysGlass.frostScale).coerceIn(0f, 1.6f)
        val baseBlur = if (sysGlass.isFrostedTinted) blur * 1.65f else blur
        return copy(
            blur = baseBlur * fScale,
            refractionAmount = refractionAmount * rScale,
            dispersion = dispersion * rScale,
        )
    }
}

fun Modifier.liquidGlass(
    shape: Shape,
    style: GlassStyle = GlassStyle(),
    pressProgress: Float = 0f,
    forceTinted: Boolean? = null,
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val colors = IosTheme.colors
    val sysGlass = LocalSystemGlassConfig.current
    val glassLayer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) LensShader.create() else null }

    val effectiveStyle = remember(style, sysGlass) { style.scaledBy(sysGlass) }
    val isTintedLook = forceTinted ?: (colors.glassTinted || sysGlass.isFrostedTinted)

    val tint = style.tint ?: when {
        sysGlass.isSolidMatte && colors.isDark -> Color(0xF51C1C1E)
        sysGlass.isSolidMatte -> Color(0xFAFFFFFF)
        isTintedLook && colors.isDark -> Color(0xBF242428)
        isTintedLook -> Color(0xD9F6F6F9)
        colors.isDark -> Color(0x88242428)
        else -> Color(0x9EFAFAFC)
    }
    val fallbackFill = when {
        isTintedLook && !colors.isDark -> Color(0xEBFFFFFF)
        isTintedLook && colors.isDark -> Color(0xEB28282C)
        else -> colors.glassFill
    }

    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = outline.toPath()
            val press = pressProgress.coerceIn(0f, 1f)
            val canRefract = !sysGlass.isSolidMatte &&
                backdrop != null && backdrop.recorded &&
                Build.VERSION.SDK_INT >= 31 &&
                drawContext.canvas.nativeCanvas.isHardwareAccelerated

            if (canRefract) {
                drawRefractedBackdrop(backdrop!!, glassLayer, path, position, effectiveStyle, shader, outline)
                drawOutline(outline, tint)
            } else {
                drawOutline(outline, fallbackFill)
            }

            if (press > 0.001f) {
                val bloomAlpha = (if (colors.isDark) 0.12f else 0.22f) * press
                drawOutline(outline, Color.White.copy(alpha = bloomAlpha))
            }

            // Directional 34-deg top-left & bottom-right specular rim catchlight (exact match to hot0151_settings.png)
            val rimBoost = 0.16f * press
            val rim = Brush.linearGradient(
                colors = if (colors.isDark) {
                    listOf(
                        Color.White.copy(alpha = (0.42f + rimBoost).coerceAtMost(0.85f)),
                        Color.White.copy(alpha = (0.10f + rimBoost * 0.5f).coerceAtMost(0.45f)),
                        Color.White.copy(alpha = (0.06f + rimBoost * 0.35f).coerceAtMost(0.35f)),
                        Color.White.copy(alpha = (0.30f + rimBoost * 0.8f).coerceAtMost(0.75f)),
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = (0.96f + rimBoost * 0.2f).coerceAtMost(1f)),
                        Color.White.copy(alpha = (0.42f + rimBoost).coerceAtMost(0.8f)),
                        Color.White.copy(alpha = (0.24f + rimBoost * 0.6f).coerceAtMost(0.6f)),
                        Color.White.copy(alpha = (0.90f + rimBoost * 0.5f).coerceAtMost(1f)),
                    )
                },
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            drawOutline(outline, rim, style = Stroke(width = (1.25f + 0.25f * press).dp.toPx()))
            drawOutline(
                outline,
                if (colors.isDark) Color(0x22FFFFFF) else Color(0x14000000),
                style = Stroke(0.5.dp.toPx()),
            )
        }
}

private fun DrawScope.drawRefractedBackdrop(
    backdrop: Backdrop,
    glassLayer: GraphicsLayer,
    clip: Path,
    position: Offset,
    style: GlassStyle,
    shader: Any?,
    outline: Outline,
) {
    val margin = (style.refractionAmount.toPx() + style.blur.toPx() * 3f).roundToInt()
    val w = size.width.roundToInt() + margin * 2
    val h = size.height.roundToInt() + margin * 2
    if (w <= 0 || h <= 0) return

    glassLayer.topLeft = IntOffset(-margin, -margin)
    glassLayer.renderEffect = effectFor(style, shader, margin, outline)
    glassLayer.colorFilter = ColorFilter.colorMatrix(vibrancy(style.saturation))
    glassLayer.record(size = IntSize(w, h)) {
        translate(
            left = backdrop.origin.x - position.x + margin,
            top = backdrop.origin.y - position.y + margin,
        ) {
            drawLayer(backdrop.layer)
        }
    }
    clipPath(clip) { drawLayer(glassLayer) }
}

private fun DrawScope.effectFor(style: GlassStyle, shader: Any?, margin: Int, outline: Outline): RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    val blurPx = style.blur.toPx()
    val blur = if (blurPx > 0f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
    val amountPx = style.refractionAmount.toPx()
    if (amountPx <= 0f && style.dispersion <= 0f) return blur
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        return runCatching {
            lensEffect(shader as RuntimeShader, blur, style, margin, cornerRadiusOf(outline))
        }.getOrNull() ?: blur
    }
    return blur
}

@RequiresApi(33)
private fun DrawScope.lensEffect(
    shader: RuntimeShader,
    blur: RenderEffect?,
    style: GlassStyle,
    margin: Int,
    radius: Float,
): RenderEffect {
    shader.setFloatUniform("origin", margin.toFloat(), margin.toFloat())
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("radius", min(radius, min(size.width, size.height) / 2f))
    shader.setFloatUniform("height", style.refractionHeight.toPx())
    shader.setFloatUniform("amount", style.refractionAmount.toPx())
    shader.setFloatUniform("chroma", style.dispersion)
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val chained = if (blur != null) {
        android.graphics.RenderEffect.createChainEffect(lens, blur.asAndroidRenderEffect())
    } else {
        lens
    }
    return chained.asComposeRenderEffect()
}

private fun DrawScope.cornerRadiusOf(outline: Outline): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    is Outline.Generic -> min(size.width, size.height) / 2f
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

private fun vibrancy(saturation: Float): ColorMatrix {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    val lift = 0.03f * 255f
    val v = m.values
    v[4] += lift; v[9] += lift; v[14] += lift
    return m
}

@RequiresApi(33)
private object LensShader {
    private const val SRC = """
        uniform shader content;
        uniform float2 origin;
        uniform float2 size;
        uniform float radius;
        uniform float height;
        uniform float amount;
        uniform float chroma;

        float sdRoundRect(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 p = coord - origin - halfSize;
            float d = sdRoundRect(p, halfSize, radius);
            if (d > 0.0 || height <= 0.0) { return content.eval(coord); }

            float e = 1.0;
            float2 n = float2(
                sdRoundRect(p + float2(e, 0.0), halfSize, radius) - sdRoundRect(p - float2(e, 0.0), halfSize, radius),
                sdRoundRect(p + float2(0.0, e), halfSize, radius) - sdRoundRect(p - float2(0.0, e), halfSize, radius));
            float len = length(n);
            n = len > 0.0001 ? n / len : float2(0.0);

            float x = clamp(1.0 + d / height, 0.0, 1.0);
            float bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * amount;
            float2 offs = -n * bend;

            half4 g = content.eval(coord + offs);
            if (chroma <= 0.0) { return g; }
            half4 r = content.eval(coord + offs * (1.0 + chroma));
            half4 b = content.eval(coord + offs * (1.0 - chroma));
            return half4(r.r, g.g, b.b, g.a);
        }
    """

    fun create(): RuntimeShader? = runCatching { RuntimeShader(SRC) }.getOrNull()
}
