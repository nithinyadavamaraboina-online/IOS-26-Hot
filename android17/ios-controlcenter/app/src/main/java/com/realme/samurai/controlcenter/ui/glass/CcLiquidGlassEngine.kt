package com.realme.samurai.controlcenter.ui.glass

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realme.samurai.controlcenter.ui.theme.ContinuousSquircleShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Immutable
data class CcGlassConfig(
    val isTinted: Boolean = false,
    val isSolidMatte: Boolean = false,
    val refractionPct: Int = 20,
    val frostPct: Int = 20,
) {
    /**
     * Exact 1:1 optical refraction scale matching Home Screen Dock (`SamuraiDockGlassRenderer.java`
     * + `SamuraiLauncherHooks.getRefractionScale`): 20% -> 0.20f subtle, clean meniscus bend; 0% -> 0.0f.
     */
    val refractionScale: Float
        get() = if (isSolidMatte || refractionPct <= 0) 0f
        else (refractionPct.coerceIn(0, 150) / 100f)

    /**
     * Exact 1:1 optical frost scale matching Home Screen Dock (`SamuraiLauncherHooks.getFrostScale`):
     * 20% -> 0.20f; 0% -> 0.0f pure water crystal.
     */
    val frostScale: Float
        get() = if (frostPct <= 0) 0f
        else (frostPct.coerceIn(0, 150) / 100f)
}

@Composable
fun rememberCcGlassConfig(
    context: Context?,
    overrideConfig: CcGlassConfig? = null,
): CcGlassConfig {
    if (overrideConfig != null || context == null) {
        return overrideConfig ?: CcGlassConfig()
    }
    fun readIntGlobalThenSecure(globalKey: String, secureKey: String, fallback: Int): Int {
        val cr = context.contentResolver
        try {
            return Settings.Global.getInt(cr, globalKey)
        } catch (_: Throwable) {
        }
        return try {
            Settings.Secure.getInt(cr, secureKey, fallback)
        } catch (_: Throwable) {
            fallback
        }
    }

    fun readCurrent(): CcGlassConfig {
        val mode = readIntGlobalThenSecure("samurai_liquid_glass_mode", "samurai_ios26_glass_mode", 0)
            .coerceIn(0, 2)
        val refr = readIntGlobalThenSecure("samurai_ios26_refraction_pct", "samurai_ios26_refraction_pct", 20)
            .coerceIn(0, 150)
        val frost = readIntGlobalThenSecure("samurai_ios26_frost_pct", "samurai_ios26_frost_pct", 20)
            .coerceIn(0, 150)
        return CcGlassConfig(
            isTinted = mode == 1,
            isSolidMatte = mode == 2,
            refractionPct = refr,
            frostPct = frost,
        )
    }

    var cfg by remember { mutableStateOf(readCurrent()) }
    DisposableEffect(context) {
        val cr = context.contentResolver
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                cfg = readCurrent()
            }
        }
        try {
            cr.registerContentObserver(Settings.Global.getUriFor("samurai_liquid_glass_mode"), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor("samurai_ios26_refraction_pct"), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor("samurai_ios26_frost_pct"), false, obs)
            cr.registerContentObserver(Settings.Secure.getUriFor("samurai_ios26_glass_mode"), false, obs)
            cr.registerContentObserver(Settings.Secure.getUriFor("samurai_ios26_refraction_pct"), false, obs)
            cr.registerContentObserver(Settings.Secure.getUriFor("samurai_ios26_frost_pct"), false, obs)
        } catch (_: Throwable) {
        }
        onDispose {
            try {
                cr.unregisterContentObserver(obs)
            } catch (_: Throwable) {
            }
        }
    }
    return cfg
}

/**
 * Dual-Buffer Optical Wallpaper Snapshot shared by Control Center, Notification Center, and Lock Screen:
 * - [crystalBitmap] / [crystalShader]: 100% sharp unblurred wallpaper for meniscus & Lock Screen backdrop
 * - [ambientImage]: Gently depth-softened background shown between Control Center / Notification Center droplets
 * - [softBitmap] / [softShader]: Silky Gaussian frost blended smoothly inside glass platters
 */
class CcOpticalEnvironment(
    val crystalBitmap: Bitmap,
    val softBitmap: Bitmap,
    val ambientBitmap: Bitmap,
    val hasSpatialDiscForeground: Boolean = false,
) {
    val crystalShader = BitmapShader(crystalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val softShader = BitmapShader(softBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val crystalImage: ImageBitmap = crystalBitmap.asImageBitmap()
    val ambientImage: ImageBitmap = ambientBitmap.asImageBitmap()
    val softImage: ImageBitmap = softBitmap.asImageBitmap()
    val crystalW: Float = crystalBitmap.width.toFloat()
    val crystalH: Float = crystalBitmap.height.toFloat()
    val softW: Float = softBitmap.width.toFloat()
    val softH: Float = softBitmap.height.toFloat()

    // Allocated lazily ONLY if drawing onto a non-hardware-accelerated software Canvas (e.g. Roborazzi).
    val crystalPixels: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        val w = crystalBitmap.width
        val h = crystalBitmap.height
        IntArray(w * h).also { crystalBitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }
    val softPixels: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        val w = softBitmap.width
        val h = softBitmap.height
        IntArray(w * h).also { softBitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }
}

@Immutable
data class CcGlassSceneState(
    val env: CcOpticalEnvironment? = null,
    val screenWidthPx: Float = 1080f,
    val screenHeightPx: Float = 2400f,
    val pullOffsetProvider: () -> Float = { 0f },
    val motionTickProvider: () -> Int = { 0 },
)

val LocalCcGlassScene = compositionLocalOf { CcGlassSceneState() }

/**
 * 90Hz AGSL RuntimeShader for the 1:1 Finger-Tracked Sliding Liquid Glass Sheet (NC / CC TopSheet
 * and rising Lock Screen glass curtain):
 * - Evaluates a rounded slab SDF over `y in [-64dp .. uSheetEdgeY]` with bottom corner radius `uCornerRadiusPx`
 * - Refracts sharp live wallpaper (`uCrystalWp`) across a velocity-responsive `32dp` meniscus lip at `uSheetEdgeY`
 *   with `2.6dp` R/B chromatic dispersion, `34 deg` crystal specular rim, and inner refractive caustic
 * - Supports `Solid Matte` mode (`uIsSolidMatte`), unblurred Lock Screen interior (`uIsLockCurtain`), and
 *   transparent / live-app exterior reveal (`uOutsideBackdropAlpha`).
 */
private const val IOS26_SLIDING_SHEET_AGSL = """
    uniform shader uCrystalWp;
    uniform shader uSoftWp;
    uniform float2 uScreenSize;
    uniform float2 uCrystalBufSize;
    uniform float2 uSoftBufSize;
    uniform float uSheetEdgeY;
    uniform float uBevelHeightPx;
    uniform float uCornerRadiusPx;
    uniform float uDensity;
    uniform float uRefractionScale;
    uniform float uFrostScale;
    uniform float uVeilAlpha;
    uniform float3 uVeilColor;
    uniform float uVelocityNorm;
    uniform float uIsLockCurtain;
    uniform float uLockBlurActive;
    uniform float uOutsideBackdropAlpha;
    uniform float uIsSolidMatte;

    float2 toSheetWpCoord(float2 screenPx, float2 bufSize) {
        float2 clamped = clamp(screenPx, float2(0.0), max(uScreenSize - float2(1.0), float2(1.0)));
        return clamped * (bufSize / max(uScreenSize, float2(1.0)));
    }

    float sq(float v) {
        return v * v;
    }

    float safePow(float x, float p) {
        return x > 0.0001 ? pow(max(x, 0.0001), p) : 0.0;
    }

    float sdSheetSlab(float2 p, float w, float edgeY, float r, float topExt) {
        float2 c = float2(w * 0.5, (edgeY - topExt) * 0.5);
        float2 b = max(float2(w * 0.5, (edgeY + topExt) * 0.5), float2(1.0));
        float rr = clamp(r, 0.0, min(b.x, b.y));
        float2 q = abs(p - c) - b + float2(rr);
        return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rr;
    }

    half3 evalLockExteriorRgb(float2 fragCoord) {
        half3 cExt = uCrystalWp.eval(toSheetWpCoord(fragCoord, uCrystalBufSize)).rgb;
        float liftFrac = clamp(1.0 - uSheetEdgeY / max(uScreenSize.y, 1.0), 0.0, 1.0);
        float baseFrost = (uLockBlurActive >= 0.5) ? 0.85 : 0.0;
        float extFrost = (uFrostScale <= 0.001)
            ? 0.0
            : clamp(baseFrost + liftFrac * 0.92, 0.0, 0.94);
        half3 extRgb = cExt;
        if (extFrost > 0.004) {
            half3 sExt = uSoftWp.eval(toSheetWpCoord(fragCoord, uSoftBufSize)).rgb;
            extRgb = mix(cExt, sExt, half(extFrost));
        }
        float dim = 0.24 * liftFrac;
        return mix(extRgb, half3(0.016, 0.035, 0.078), half(dim));
    }

    half4 evalExterior(float2 fragCoord) {
        if (uIsLockCurtain >= 0.5 && uOutsideBackdropAlpha > 0.001) {
            half3 extWp = evalLockExteriorRgb(fragCoord);
            half extA = half(clamp(uOutsideBackdropAlpha, 0.0, 1.0));
            return half4(extWp * extA, extA);
        }
        return half4(0.0);
    }

    half4 main(float2 fragCoord) {
        if (uSheetEdgeY <= 0.5) {
            return evalExterior(fragCoord);
        }

        float topExt = 64.0 * uDensity;
        float d = sdSheetSlab(fragCoord, uScreenSize.x, uSheetEdgeY, uCornerRadiusPx, topExt);
        if (d > 0.75) {
            return evalExterior(fragCoord);
        }

        float slabAA = 1.0 - smoothstep(-0.45, 0.55, d);
        float depthPx = max(-d, 0.0);

        if (uIsLockCurtain < 0.5 && uIsSolidMatte > 0.5) {
            float rimStroke = 1.0 - smoothstep(0.35 * uDensity, 1.25 * uDensity, depthPx);
            half3 matteRgb = mix(half3(0.0784, 0.0902, 0.1216), half3(1.0), half(rimStroke * 0.1804));
            half matteA = half((0.9216 + (1.0 - 0.9216) * rimStroke * 0.1804) * slabAA);
            return half4(matteRgb * matteA, matteA);
        }

        float bevelH = max(uBevelHeightPx, 4.0 * uDensity);
        float velMag = clamp(abs(uVelocityNorm), 0.0, 1.0);
        float effRefr = (uRefractionScale <= 0.001)
            ? 0.0
            : ((uIsLockCurtain >= 0.5) ? max(uRefractionScale, 0.20) : uRefractionScale);
        float rimPresence = clamp((uScreenSize.y - uSheetEdgeY) / max(12.0 * uDensity, 1.0), 0.0, 1.0);
        float lipBand = smoothstep(
            uSheetEdgeY - max(uCornerRadiusPx, 8.0 * uDensity) - bevelH * 1.35,
            uSheetEdgeY - bevelH * 0.85,
            fragCoord.y
        );
        float edgePresence = rimPresence * lipBand;

        half3 sheetGlass;
        float meniscus = 0.0;
        float2 n = float2(0.0, 1.0);

        if (depthPx < bevelH) {
            float eps = 1.25;
            float dx = sdSheetSlab(fragCoord + float2(eps, 0.0), uScreenSize.x, uSheetEdgeY, uCornerRadiusPx, topExt)
                     - sdSheetSlab(fragCoord - float2(eps, 0.0), uScreenSize.x, uSheetEdgeY, uCornerRadiusPx, topExt);
            float dy = sdSheetSlab(fragCoord + float2(0.0, eps), uScreenSize.x, uSheetEdgeY, uCornerRadiusPx, topExt)
                     - sdSheetSlab(fragCoord - float2(0.0, eps), uScreenSize.x, uSheetEdgeY, uCornerRadiusPx, topExt);
            n = normalize(float2(dx, dy) + float2(0.0, 1e-4));

            float edge = clamp(1.0 - depthPx / bevelH, 0.0, 1.0);
            meniscus = (1.0 - sqrt(max(1.0 - edge * edge, 0.0))) * edgePresence;

            float pullMag = (24.0 * uDensity) * effRefr * (1.0 + 0.30 * velMag) * meniscus;
            float velShearY = -uVelocityNorm * (5.5 * uDensity) * meniscus * effRefr;
            float2 baseScreen = fragCoord - n * pullMag + float2(0.0, velShearY);
            float disp = (2.6 * uDensity) * meniscus * clamp(effRefr * 1.35, 0.0, 1.2);
            float2 pR = baseScreen + n * disp;
            float2 pG = baseScreen;
            float2 pB = baseScreen - n * disp;

            half3 cCol = half3(
                uCrystalWp.eval(toSheetWpCoord(pR, uCrystalBufSize)).r,
                uCrystalWp.eval(toSheetWpCoord(pG, uCrystalBufSize)).g,
                uCrystalWp.eval(toSheetWpCoord(pB, uCrystalBufSize)).b
            );
            float wantSoft = (uIsLockCurtain >= 0.5)
                ? (uLockBlurActive >= 0.5 ? 0.88 : 0.0)
                : clamp(0.32 + 0.62 * uFrostScale, 0.0, 0.96);
            if (uFrostScale <= 0.001) {
                wantSoft = 0.0;
            }
            float localFrost = wantSoft * (1.0 - 0.72 * meniscus);
            if (localFrost > 0.004) {
                half3 sCol = half3(
                    uSoftWp.eval(toSheetWpCoord(pR, uSoftBufSize)).r,
                    uSoftWp.eval(toSheetWpCoord(pG, uSoftBufSize)).g,
                    uSoftWp.eval(toSheetWpCoord(pB, uSoftBufSize)).b
                );
                sheetGlass = mix(cCol, sCol, half(localFrost));
            } else {
                sheetGlass = cCol;
            }
        } else {
            half3 cCol = uCrystalWp.eval(toSheetWpCoord(fragCoord, uCrystalBufSize)).rgb;
            float wantSoft = (uIsLockCurtain >= 0.5)
                ? (uLockBlurActive >= 0.5 ? 0.92 : 0.0)
                : clamp(0.32 + 0.62 * uFrostScale, 0.0, 0.96);
            if (uFrostScale <= 0.001) {
                wantSoft = 0.0;
            }
            if (wantSoft > 0.004) {
                half3 sCol = uSoftWp.eval(toSheetWpCoord(fragCoord, uSoftBufSize)).rgb;
                sheetGlass = mix(cCol, sCol, half(wantSoft));
            } else {
                sheetGlass = cCol;
            }
        }

        if (uIsLockCurtain >= 0.5) {
            float yNorm = clamp(fragCoord.y / max(uScreenSize.y, 1.0), 0.0, 1.0);
            float topVig = (1.0 - smoothstep(0.0, 0.26, yNorm)) * (uLockBlurActive >= 0.5 ? 0.34 : 0.16);
            float botVig = smoothstep(0.68, 1.0, yNorm) * (uLockBlurActive >= 0.5 ? 0.44 : 0.26) * (1.0 - 0.65 * meniscus);
            float vig = clamp(topVig + botVig, 0.0, 0.60);
            sheetGlass = mix(sheetGlass, half3(0.016, 0.035, 0.078), half(vig));
        } else {
            float veilAmt = clamp(uVeilAlpha, 0.0, 0.75) * (1.0 - 0.48 * meniscus);
            sheetGlass = mix(sheetGlass, half3(uVeilColor), half(veilAmt));
        }

        if (depthPx < bevelH && edgePresence > 0.002) {
            float causBand = exp(-sq((depthPx - 5.2 * uDensity) / max(3.6 * uDensity, 0.8)));
            float causGlow = causBand * (0.085 + 0.12 * clamp(effRefr, 0.0, 1.2)) * edgePresence;
            float lightDot = clamp(dot(n, float2(-0.5592, -0.8290)), 0.0, 1.0);
            float oppDot   = clamp(dot(n, float2( 0.5592,  0.8290)), 0.0, 1.0);
            float rimLine  = exp(-sq((depthPx - 0.85 * uDensity) / max(0.82 * uDensity, 0.35)));
            float rimSpec  = rimLine * (0.14 + 0.30 * safePow(lightDot, 1.6) + 0.22 * safePow(oppDot, 1.8)) * edgePresence;
            sheetGlass = clamp(sheetGlass + half3(causGlow + rimSpec), half3(0.0), half3(1.0));
        }

        if (uIsLockCurtain >= 0.5 && uOutsideBackdropAlpha > 0.001 && slabAA < 0.999) {
            half3 extWp = evalLockExteriorRgb(fragCoord);
            float extA = clamp(uOutsideBackdropAlpha, 0.0, 1.0) * (1.0 - slabAA);
            float totA = clamp(slabAA + extA, 0.0, 1.0);
            half3 premul = sheetGlass * half(slabAA) + extWp * half(extA);
            return half4(premul, half(totA));
        }

        return half4(sheetGlass * half(slabAA), half(slabAA));
    }
"""

/**
 * 90Hz AGSL RuntimeShader ported 1:1 from `SamuraiDockGlassRenderer.java` (Home Screen Dock optics),
 * with exact Euclidean capsule SDF (`r >= min(b.x, b.y) * 0.96`), integrated active-state Liquid Glass
 * illumination (`uActiveFraction`), and integrated internal liquid slider fill (`uSliderFill`):
 * - Zero geometry/size/alignment shift between OFF and ON states
 * - Active tiles, active pills, and Brightness/Volume slider columns blend physically INSIDE the
 *   refractive glass (keeping wallpaper hues, curved meniscus bevel, caustic, and 34° crystal rim alive).
 */
private const val IOS26_PLATTER_AGSL = """
    uniform shader uCrystalWp;
    uniform shader uSoftWp;
    uniform float2 uElemSize;
    uniform float2 uElemScreenOrigin;
    uniform float2 uScreenSize;
    uniform float2 uCrystalBufSize;
    uniform float2 uSoftBufSize;
    uniform float uPad;
    uniform float uRadius;
    uniform float uDensity;
    uniform float uIsCircle;
    uniform float uEffectiveFrost;
    uniform float uVeilAlpha;
    uniform float3 uVeilColor;
    uniform float uRefractionScale;
    uniform float uFrostScale;
    uniform float uDispersionScale;
    uniform float uShadowStrength;
    uniform float uActiveFraction;
    uniform float uSliderFill;

    float sq(float v) {
        return v * v;
    }

    float safePow(float x, float p) {
        return x > 0.0001 ? pow(max(x, 0.0001), p) : 0.0;
    }

    float sdGlassShape(float2 p, float2 b, float r, float isCircle) {
        if (isCircle > 0.5) {
            return length(p) - min(b.x, b.y);
        }
        float maxR = min(b.x, b.y);
        float2 q = abs(p) - b + float2(r);
        if (r >= maxR * 0.96) {
            return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
        }
        if (q.x <= 0.0 && q.y <= 0.0) {
            return min(max(q.x, q.y), 0.0) - r;
        }
        float2 qPos = max(q, 0.0005);
        float corner = pow(pow(qPos.x, 2.65) + pow(qPos.y, 2.65), 0.37735849);
        return corner - r;
    }

    float2 toBufCoord(float2 screenPx, float2 bufSize) {
        float2 clamped = clamp(screenPx, float2(0.0), max(uScreenSize - float2(1.0), float2(1.0)));
        return clamped * (bufSize / max(uScreenSize, float2(1.0)));
    }

    half4 main(float2 fragCoord) {
        float2 local = fragCoord - float2(uPad);
        float2 halfSize = max(uElemSize * 0.5, float2(1.0));
        float2 rel = local - halfSize;
        float r = clamp(uRadius, 0.0, min(halfSize.x, halfSize.y));
        float d = sdGlassShape(rel, halfSize, r, uIsCircle);
        float aa = 1.0 - smoothstep(-0.65, 0.65, d);

        // 1. Subtle exterior-only contact shadow matching SamuraiDockGlassRenderer
        half shadowA = half(0.0);
        if (uShadowStrength > 0.001 && d > -0.65) {
            float dSh = sdGlassShape(rel - float2(0.0, 2.2 * uDensity), halfSize, r, uIsCircle);
            float shFall = exp(-max(dSh, 0.0) / max(3.4 * uDensity, 1.0));
            shadowA = half(uShadowStrength * shFall * (1.0 - aa));
        }
        if (aa <= 0.001) {
            return half4(half3(0.024, 0.047, 0.094) * shadowA, shadowA);
        }

        // 2. Analytic surface normal
        float eps = 1.1;
        float dx = sdGlassShape(rel + float2(eps, 0.0), halfSize, r, uIsCircle)
                 - sdGlassShape(rel - float2(eps, 0.0), halfSize, r, uIsCircle);
        float dy = sdGlassShape(rel + float2(0.0, eps), halfSize, r, uIsCircle)
                 - sdGlassShape(rel - float2(0.0, eps), halfSize, r, uIsCircle);
        float2 n = normalize(float2(dx, dy) + float2(1e-5));

        // 3. Spherical meniscus circleMap(edge) + subtle convex slab bend (1:1 Dock calibration)
        float minDim = min(halfSize.x, halfSize.y);
        float lipW = min(16.5 * uDensity, max(minDim * 0.62, 6.0 * uDensity));
        float edge = clamp(1.0 + d / lipW, 0.0, 1.0);
        float meniscus = 1.0 - sqrt(max(1.0 - edge * edge, 0.0));
        float2 dome = (rel / max(halfSize, float2(1.0))) * float2(3.2 * uDensity, 5.0 * uDensity) * uRefractionScale;
        float pullX = -n.x * (12.5 * uDensity) * meniscus * uRefractionScale - dome.x;
        float pullY = -n.y * (19.5 * uDensity) * meniscus * uRefractionScale - dome.y;
        float2 baseScreen = uElemScreenOrigin + local + float2(pullX, pullY);

        // 4. Gentle Dock chromatic dispersion (0.47dp at 20% -> zero harsh rainbow split)
        float disp = (2.35 * uDensity) * meniscus * uDispersionScale * uRefractionScale;
        float2 pR = baseScreen + n * disp;
        float2 pG = baseScreen;
        float2 pB = baseScreen - n * disp;

        half3 crystalCol = half3(
            uCrystalWp.eval(toBufCoord(pR, uCrystalBufSize)).r,
            uCrystalWp.eval(toBufCoord(pG, uCrystalBufSize)).g,
            uCrystalWp.eval(toBufCoord(pB, uCrystalBufSize)).b
        );
        half3 softCol = half3(
            uSoftWp.eval(toBufCoord(pR, uSoftBufSize)).r,
            uSoftWp.eval(toBufCoord(pG, uSoftBufSize)).g,
            uSoftWp.eval(toBufCoord(pB, uSoftBufSize)).b
        );

        // 5. Smooth Dock frost diffusion with water-clear meniscus rim
        float localFrost = clamp(uEffectiveFrost, 0.0, 1.0) * (1.0 - 0.62 * meniscus);
        half3 glass = mix(crystalCol, softCol, half(localFrost));

        // Gentle Dock saturation (1.02 at 20%, 1.10 max)
        half luma = dot(glass, half3(0.2126, 0.7152, 0.0722));
        float satBoost = 1.0 + 0.10 * clamp(max(uFrostScale, uRefractionScale), 0.0, 1.0);
        glass = clamp(mix(half3(luma), glass, half(satBoost)), half3(0.0), half3(1.0));

        // Calm contrast veil + delicate top sheen matching SamuraiDockGlassRenderer
        glass = mix(glass, half3(uVeilColor), half(clamp(uVeilAlpha, 0.0, 0.80)));
        float yNorm = clamp(local.y / max(uElemSize.y, 1.0), 0.0, 1.0);
        float frostVis = clamp(uFrostScale * 1.4, 0.0, 1.0);
        float topSheen = (1.0 - yNorm) * 0.032 * frostVis;
        glass = clamp(glass + half3(topSheen), half3(0.0), half3(1.0));

        // 5b. Integrated Active Liquid Glass Illumination (blended INSIDE the glass, tapering at meniscus!)
        if (uActiveFraction > 0.002) {
            float act = clamp(uActiveFraction, 0.0, 1.0);
            half3 pearlTint = mix(
                half3(0.965, 0.980, 1.0),
                clamp(glass * half(0.42) + half3(0.62, 0.65, 0.70), half3(0.0), half3(1.0)),
                half(0.26)
            );
            float coreBlend = act * (0.62 - 0.27 * meniscus);
            glass = mix(glass, pearlTint, half(coreBlend));
        }

        // 5c. Integrated Vertical Capsule Liquid Slider Fill (luminous liquid inside the glass tube!)
        if (uSliderFill >= 0.0) {
            float sf = clamp(uSliderFill, 0.0, 1.0);
            if (sf > 0.002) {
                float fillTopY = uElemSize.y * (1.0 - sf);
                float inFill = sf >= 0.998
                    ? 1.0
                    : smoothstep(fillTopY - 0.9 * uDensity, fillTopY + 0.9 * uDensity, local.y);
                half3 liqTint = mix(
                    half3(0.970, 0.985, 1.0),
                    clamp(glass * half(0.44) + half3(0.60, 0.64, 0.70), half3(0.0), half3(1.0)),
                    half(0.28)
                );
                float fillBlend = inFill * (0.60 - 0.27 * meniscus);
                glass = mix(glass, liqTint, half(fillBlend));

                if (sf > 0.015 && sf < 0.985) {
                    float surfDist = (local.y - fillTopY) / max(1.15 * uDensity, 0.5);
                    float surfCrest = exp(-surfDist * surfDist) * (1.0 - 0.45 * meniscus);
                    glass = clamp(glass + half3(surfCrest * 0.16), half3(0.0), half3(1.0));
                }
            }
        }

        // 6. Subtle internal top meniscus refractive caustic
        float refractVis = clamp(uRefractionScale, 0.0, 1.5);
        float depthPx = max(-d, 0.0);
        float topAlign = clamp(-n.y, 0.0, 1.0);
        float causticBand = exp(-sq((depthPx - 3.8 * uDensity) / max(2.6 * uDensity, 0.6))) * topAlign;
        glass = clamp(glass + half3(causticBand * 0.055 * refractVis), half3(0.0), half3(1.0));

        // 7. Hair-fine 0.58dp 34-deg SpringBoard Dock crystal specular rim (unbroken around active/slider glass!)
        float lightDot = clamp(dot(n, float2(-0.5592, -0.8290)), 0.0, 1.0);
        float oppDot   = clamp(dot(n, float2( 0.5592,  0.8290)), 0.0, 1.0);
        float rimLine  = exp(-sq((depthPx - 0.62 * uDensity) / max(0.58 * uDensity, 0.30)));
        float rimSpec  = rimLine * (0.068 + 0.29 * safePow(lightDot, 1.65) + 0.10 * safePow(oppDot, 2.2));
        glass = clamp(glass + half3(rimSpec), half3(0.0), half3(1.0));

        half glassA = half(aa);
        half outA = glassA + shadowA;
        half3 outPremul = glass * glassA + half3(0.024, 0.047, 0.094) * shadowA;
        return half4(outPremul, outA);
    }
"""

/**
 * 90Hz AGSL RuntimeShader for Apple's iOS 26 3D Cylindrical Refractive Liquid Glass Clock Numerals:
 * - Evaluates the smooth 3D tubular glass surface normal `(nx, ny)` from the cached numeral dome heightfield
 * - Physically refracts and disperses `uCrystalWp` (the sharp live wallpaper behind each digit!)
 * - Renders 3D cylindrical glass bevel thickness (inner shadow-side refractive groove + top-left cylindrical
 *   specular highlight + razor-fine 34° crystal catchlight) with crystal-clear interior transparency.
 */
private const val IOS26_CLOCK_GLASS_AGSL = """
    uniform shader uCrystalWp;
    uniform shader uSoftWp;
    uniform shader uSilMask;
    uniform shader uDomeMask;
    uniform float2 uElemSize;
    uniform float2 uElemScreenOrigin;
    uniform float2 uScreenSize;
    uniform float2 uCrystalBufSize;
    uniform float2 uSoftBufSize;
    uniform float2 uDomeScale;
    uniform float uDensity;
    uniform float uRefractionScale;
    uniform float uFrostScale;
    uniform float uIsTinted;
    uniform float uHasSpatialForeground;

    float sq(float v) {
        return v * v;
    }

    float safePow(float x, float p) {
        return x > 0.0001 ? pow(max(x, 0.0001), p) : 0.0;
    }

    float2 toWpCoord(float2 screenPx, float2 bufSize) {
        float2 clamped = clamp(screenPx, float2(0.0), max(uScreenSize - float2(1.0), float2(1.0)));
        return clamped * (bufSize / max(uScreenSize, float2(1.0)));
    }

    float2 toBgWpCoord(float2 screenPx, float2 bufSize, float avoidFg) {
        float2 wp = toWpCoord(screenPx, bufSize);
        if (avoidFg > 0.5) {
            float2 fgC = float2(-0.34043 * bufSize.x, 0.85319 * bufSize.y);
            float fgGuardR = 1.15993 * bufSize.x + 2.8;
            float2 dv = wp - fgC;
            float d = length(dv);
            if (d < fgGuardR && d > 1e-3) {
                wp = fgC + (dv / d) * fgGuardR;
            }
        }
        return wp;
    }

    half4 main(float2 fragCoord) {
        float2 p = clamp(fragCoord, float2(0.5), max(uElemSize - float2(0.5), float2(0.5)));
        half4 silSample = uSilMask.eval(p);
        float aa = float(silSample.a);

        // iOS 26 Spatial Scene Foreground Disc Occlusion (tucks lower-left foot of tall Hero Clock behind
        // the translucent foreground glass disc with a subtle 12% ghost, matching ref_ios26_ls_9to5mac.jpg)
        float fgCover = 0.0;
        if (uHasSpatialForeground > 0.5) {
            float2 wpNow = toWpCoord(uElemScreenOrigin + p, uCrystalBufSize);
            float2 fgCenter = float2(-0.34043 * uCrystalBufSize.x, 0.85319 * uCrystalBufSize.y);
            float fgR = 1.15993 * uCrystalBufSize.x;
            float fgDist = length(wpNow - fgCenter);
            fgCover = 1.0 - smoothstep(fgR - 0.85, fgR + 0.85, fgDist);
        }
        float visFront = 1.0 - fgCover;

        // Exterior two-scale 3D lift & contact AO shadow (pre-integrated Euclidean field in domeMask.b)
        float2 shPos = clamp(p - float2(0.9 * uDensity, 2.5 * uDensity), float2(0.5), max(uElemSize - float2(0.5), float2(0.5)));
        float shField = float(uDomeMask.eval(shPos * uDomeScale).b);
        half shadowA = half(0.27 * shField * (1.0 - aa) * visFront);
        if (aa <= 0.002) {
            return half4(half3(0.015, 0.032, 0.068) * shadowA, shadowA);
        }

        // True Signed-EDT circular-cylinder 3D glass heightfield (R = cylinder dome, G = normalized 8.8dp wall depth)
        float eps = max(2.0 * uDensity, 1.5);
        half4 domeC = uDomeMask.eval(p * uDomeScale);
        float wallDepth = float(domeC.g);
        float hL = float(uDomeMask.eval(clamp(p - float2(eps, 0.0), float2(0.0), uElemSize) * uDomeScale).r);
        float hR = float(uDomeMask.eval(clamp(p + float2(eps, 0.0), float2(0.0), uElemSize) * uDomeScale).r);
        float hT = float(uDomeMask.eval(clamp(p - float2(0.0, eps), float2(0.0), uElemSize) * uDomeScale).r);
        float hB = float(uDomeMask.eval(clamp(p + float2(0.0, eps), float2(0.0), uElemSize) * uDomeScale).r);

        float gx = hL - hR;
        float gy = hT - hB;
        float gLen = length(float2(gx, gy));
        // Smooth 3D surface lateral normal (-dz/dx, -dz/dy) / sqrt(cz^2 + |grad|^2):
        // passes smoothly through (0, 0) at stem centerlines (zero medial crease or V-spike!) and peaks at outer wall!
        float2 n3d = float2(gx, gy) / sqrt(0.025 + gx * gx + gy * gy);
        float slope = clamp(length(n3d), 0.0, 1.0);
        float2 dir = gLen > 1e-4 ? (float2(gx, gy) / gLen) : float2(-0.52, -0.85);

        // Monotonic plano-convex cylindrical magnifying lens + smooth ~11dp curved meniscus bend (1:1 Apple iOS 26)
        float refrAmp = (uRefractionScale <= 0.001)
            ? 0.0
            : (clamp(max(uRefractionScale, 0.20) * 2.50, 0.42, 1.15) * visFront);
        float2 pull = -n3d * (33.0 * uDensity) * refrAmp;
        float2 baseScreen = uElemScreenOrigin + p + pull;

        float disp = (0.85 * uDensity) * slope * slope * refrAmp;
        float2 pR = baseScreen + dir * disp;
        float2 pG = baseScreen;
        float2 pB = baseScreen - dir * disp;

        float avoidFg = (uHasSpatialForeground > 0.5 && visFront > 0.05) ? 1.0 : 0.0;
        half3 crystalCol = half3(
            uCrystalWp.eval(toBgWpCoord(pR, uCrystalBufSize, avoidFg)).r,
            uCrystalWp.eval(toBgWpCoord(pG, uCrystalBufSize, avoidFg)).g,
            uCrystalWp.eval(toBgWpCoord(pB, uCrystalBufSize, avoidFg)).b
        );
        half3 softCol = half3(
            uSoftWp.eval(toBgWpCoord(pR, uSoftBufSize, avoidFg)).r,
            uSoftWp.eval(toBgWpCoord(pG, uSoftBufSize, avoidFg)).g,
            uSoftWp.eval(toBgWpCoord(pB, uSoftBufSize, avoidFg)).b
        );

        // Water-clear lens core + polished crystal diffusion on steeply curved bevel shoulders (strictly 0.0 at Frost=0%)
        float frostMix = (uFrostScale <= 0.001)
            ? 0.0
            : (((uIsTinted > 0.5 ? 0.24 : 0.045) + (uIsTinted > 0.5 ? 0.10 : 0.14) * safePow(slope, 1.55))
                * clamp(uFrostScale / 0.20, 0.0, 1.6));
        half3 glass = mix(crystalCol, softCol, half(clamp(frostMix, 0.0, 0.65)));

        half luma = dot(glass, half3(0.2126, 0.7152, 0.0722));
        glass = clamp(mix(half3(luma), glass, half(1.12)), half3(0.0), half3(1.0));

        float bodyTintA = uIsTinted > 0.5 ? 0.11 : 0.048;
        glass = mix(glass, half3(0.95, 0.98, 1.0), half(bodyTintA));

        // Molded 5..7dp 3D crystal bevel depth: broad inner TIR meniscus trough + soft lit shoulder + droplet caustic
        float lightDot = clamp(dot(dir, float2(-0.52, -0.85)), 0.0, 1.0);
        float oppDot   = clamp(dot(dir, float2( 0.52,  0.85)), 0.0, 1.0);
        float botCurve = clamp(dir.y, 0.0, 1.0);
        float troughBand = smoothstep(0.04, 0.24, wallDepth) * (1.0 - smoothstep(0.32, 0.78, wallDepth));
        float tirDarken = troughBand * (0.10 + 0.12 * oppDot + 0.07 * botCurve) * visFront;
        glass = glass * half(1.0 - tirDarken);

        float shoulderBand = (1.0 - smoothstep(0.05, 0.42, wallDepth)) * smoothstep(0.0, 0.08, wallDepth);
        float bevelSheen = shoulderBand * 0.16 * sq(lightDot) * visFront;
        float causticFocus = smoothstep(0.20, 0.48, wallDepth) * (1.0 - smoothstep(0.52, 0.86, wallDepth)) * safePow(oppDot, 1.8) * 0.068 * visFront;
        float cushionGlow = smoothstep(0.45, 0.92, wallDepth) * 0.042 * (0.35 + 0.65 * visFront);
        glass = clamp(glass + half3(bevelSheen + causticFocus + cushionGlow), half3(0.0), half3(1.0));

        // Apple's grazing top-arch & bottom-U-curve liquid-mercury rim (whisper-subtle on vertical stems!)
        float edgeField = clamp(float(silSample.r) / max(aa, 0.02), 0.0, 1.0);
        float rimLine = exp(-sq((edgeField - 0.57) / 0.16)) * (1.0 - smoothstep(0.70, 0.93, edgeField));
        float horizGrazing = safePow(abs(dir.y), 2.3);
        float topLeftGlint = safePow(clamp(dot(dir, float2(-0.46, -0.89)), 0.0, 1.0), 2.1);
        float botFresnel   = safePow(clamp(dot(dir, float2( 0.24,  0.97)), 0.0, 1.0), 2.2);
        float rimSpec = rimLine * (0.032 + 0.24 * topLeftGlint + 0.16 * horizGrazing + 0.15 * botFresnel) * visFront;
        glass = clamp(glass + half3(rimSpec), half3(0.0), half3(1.0));

        half glassA = half(aa * (1.0 - 0.88 * fgCover));
        half outA = glassA + shadowA;
        half3 outPremul = glass * glassA + half3(0.015, 0.032, 0.068) * shadowA;
        return half4(outPremul, outA);
    }
"""

private data class SwSheetCacheKey(
    val wInt: Int,
    val hInt: Int,
    val densCentis: Int,
    val edgeQuarterPx: Int,
    val cornerQuarterPx: Int,
    val velQuant: Int,
    val extAlphaByte: Int,
    val isLockCurtain: Boolean,
    val lockBlurActive: Boolean,
    val isSolidMatte: Boolean,
    val isTinted: Boolean,
    val refractionPct: Int,
    val frostPct: Int,
    val wpGenerationId: Int,
)

private data class SwPlatterCacheKey(
    val wQuarterPx: Int,
    val hQuarterPx: Int,
    val densCentis: Int,
    val originXHalfPx: Int,
    val originYHalfPx: Int,
    val radiusQuarterPx: Int,
    val isCircle: Boolean,
    val isLargePlatter: Boolean,
    val isTinted: Boolean,
    val refractionPct: Int,
    val frostPct: Int,
    val activeByte: Int,
    val sliderQuant: Int,
    val wpGenerationId: Int,
)

private data class SwClockCacheKey(
    val clockText: String,
    val wInt: Int,
    val hInt: Int,
    val densCentis: Int,
    val originXHalfPx: Int,
    val originYHalfPx: Int,
    val isTinted: Boolean,
    val hasSpatialFg: Boolean,
    val refractionPct: Int,
    val frostPct: Int,
    val wpGenerationId: Int,
)

private class SlidingSheetShaderHolder {
    val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            RuntimeShader(IOS26_SLIDING_SHEET_AGSL)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val bmpPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    var swCacheKey: SwSheetCacheKey? = null
    var swCacheBmp: Bitmap? = null
}

private class PlatterShaderHolder {
    val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            RuntimeShader(IOS26_PLATTER_AGSL)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val bmpPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    var swCacheKey: SwPlatterCacheKey? = null
    var swCacheBmp: Bitmap? = null
}

private class ClockMaskPair(
    val text: String,
    val width: Int,
    val height: Int,
    val layout: ClockLayoutMetrics,
    val glyphPath: AndroidPath,
    val silBitmap: Bitmap,
    val domeBitmap: Bitmap,
) {
    val silShader = BitmapShader(silBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val domeShader = BitmapShader(domeBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val domeW: Int = domeBitmap.width
    val domeH: Int = domeBitmap.height
    val silPixels: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(width * height).also { silBitmap.getPixels(it, 0, width, 0, 0, width, height) }
    }
    val domePixels: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(domeW * domeH).also { domeBitmap.getPixels(it, 0, domeW, 0, 0, domeW, domeH) }
    }
}

private class ClockShaderHolder {
    val shader: RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            RuntimeShader(IOS26_CLOCK_GLASS_AGSL)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val bmpPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    var maskCache: ClockMaskPair? = null
    var swCacheKey: SwClockCacheKey? = null
    var swCacheBmp: Bitmap? = null
}

/**
 * Clips inner sheet or rising Lock Screen curtain content in outer unpadded root coordinates
 * to `RoundRect(0f, -cornerRadiusPx, size.width, sheetEdgeYPx, CornerRadius(effectiveRadiusPx))`,
 * relaxing `effectiveRadiusPx` linearly to `0f` as `sheetEdgeYPx` settles across
 * `[size.height - cornerRadiusPx .. size.height]` so fully open sheets cover bottom display corners.
 */
fun Modifier.clipWithMovingGlassRim(
    sheetEdgeYPx: Float,
    cornerRadius: Dp = 28.dp,
): Modifier = this.drawWithCache {
    val clipRoundedPath = Path()
    val cornerRadiusPx = cornerRadius.toPx().coerceAtLeast(0f)
    val clampedEdge = sheetEdgeYPx.coerceIn(0f, size.height)
    val hasVisibleSlab = clampedEdge > 0.5f && size.width > 0f
    if (hasVisibleSlab) {
        val relaxStart = (size.height - cornerRadiusPx).coerceAtLeast(1f)
        val relaxFrac = if (cornerRadiusPx > 0.5f && clampedEdge > relaxStart) {
            ((size.height - clampedEdge) / cornerRadiusPx).coerceIn(0f, 1f)
        } else {
            1f
        }
        val effectiveRadiusPx = cornerRadiusPx * relaxFrac
        clipRoundedPath.addRoundRect(
            RoundRect(
                left = 0f,
                top = -cornerRadiusPx - 2f,
                right = size.width,
                bottom = clampedEdge,
                topLeftCornerRadius = CornerRadius.Zero,
                topRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius(effectiveRadiusPx, effectiveRadiusPx),
                bottomRightCornerRadius = CornerRadius(effectiveRadiusPx, effectiveRadiusPx),
            )
        )
    }
    onDrawWithContent {
        if (hasVisibleSlab) {
            clipPath(clipRoundedPath) {
                this@onDrawWithContent.drawContent()
            }
        }
    }
}

private fun resolveWindowFallbackSizePx(
    context: Context?,
    density: Density,
    cfgWidthDp: Int,
    cfgHeightDp: Int,
): Pair<Float, Float> {
    if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = wm?.currentWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width().toFloat() to bounds.height().toFloat()
            }
        } catch (_: Throwable) {
        }
    }
    val w = with(density) { cfgWidthDp.dp.toPx() }.coerceAtLeast(360f)
    val h = with(density) { cfgHeightDp.dp.toPx() }.coerceAtLeast(640f)
    return w to h
}

@Composable
private fun SlidingGlassSlabBackdrop(
    opticalEnv: CcOpticalEnvironment,
    glassConfig: CcGlassConfig,
    resolvedEdgeYPx: Float,
    sheetVelocityPxPerSec: Float,
    isLockCurtain: Boolean,
    lockBlurActive: Boolean,
    outsideBackdropAlpha: Float,
    cornerRadius: Dp = 28.dp,
) {
    val holder = remember { SlidingSheetShaderHolder() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        val dens = density.coerceAtLeast(1f)
        val clampedEdgeY = resolvedEdgeYPx.coerceIn(0f, h)
        val clampedOutsideA = outsideBackdropAlpha.coerceIn(0f, 1f)
        if (clampedEdgeY <= 0.5f && (!isLockCurtain || clampedOutsideA <= 0.001f)) {
            return@Canvas
        }

        val maxCornerPx = cornerRadius.toPx().coerceAtLeast(0f)
        val relaxStart = (h - maxCornerPx).coerceAtLeast(1f)
        val relaxFrac = if (maxCornerPx > 0.5f && clampedEdgeY > relaxStart) {
            ((h - clampedEdgeY) / maxCornerPx).coerceIn(0f, 1f)
        } else {
            1f
        }
        val effectiveCornerPx = maxCornerPx * relaxFrac

        val velocityNorm = (sheetVelocityPxPerSec / (2400f * dens)).coerceIn(-1f, 1f)
        val bevelHeightPx = (32f + 10f * abs(velocityNorm)) * dens
        val veilAlpha: Float
        val vr: Float
        val vg: Float
        val vb: Float
        if (glassConfig.isTinted) {
            veilAlpha = 0.28f
            vr = 0.05f; vg = 0.09f; vb = 0.16f
        } else {
            veilAlpha = 0.13f
            vr = 0.03f; vg = 0.06f; vb = 0.12f
        }

        val nc = drawContext.canvas.nativeCanvas
        val shader = holder.shader
        if (nc.isHardwareAccelerated && shader != null) {
            shader.setInputShader("uCrystalWp", opticalEnv.crystalShader)
            shader.setInputShader("uSoftWp", opticalEnv.softShader)
            shader.setFloatUniform("uScreenSize", w, h)
            shader.setFloatUniform("uCrystalBufSize", opticalEnv.crystalW, opticalEnv.crystalH)
            shader.setFloatUniform("uSoftBufSize", opticalEnv.softW, opticalEnv.softH)
            shader.setFloatUniform("uSheetEdgeY", clampedEdgeY)
            shader.setFloatUniform("uBevelHeightPx", bevelHeightPx)
            shader.setFloatUniform("uCornerRadiusPx", effectiveCornerPx)
            shader.setFloatUniform("uDensity", dens)
            shader.setFloatUniform("uRefractionScale", glassConfig.refractionScale)
            shader.setFloatUniform("uFrostScale", glassConfig.frostScale)
            shader.setFloatUniform("uVeilAlpha", veilAlpha)
            shader.setFloatUniform("uVeilColor", vr, vg, vb)
            shader.setFloatUniform("uVelocityNorm", velocityNorm)
            shader.setFloatUniform("uIsLockCurtain", if (isLockCurtain) 1f else 0f)
            shader.setFloatUniform("uLockBlurActive", if (lockBlurActive) 1f else 0f)
            shader.setFloatUniform("uOutsideBackdropAlpha", clampedOutsideA)
            shader.setFloatUniform("uIsSolidMatte", if (glassConfig.isSolidMatte) 1f else 0f)

            holder.paint.shader = shader
            nc.drawRect(0f, 0f, w, h, holder.paint)
        } else {
            val wInt = w.roundToInt().coerceAtLeast(2)
            val hInt = h.roundToInt().coerceAtLeast(2)
            val key = SwSheetCacheKey(
                wInt = wInt,
                hInt = hInt,
                densCentis = (dens * 100f).roundToInt(),
                edgeQuarterPx = (clampedEdgeY * 4f).roundToInt(),
                cornerQuarterPx = (effectiveCornerPx * 4f).roundToInt(),
                velQuant = ((velocityNorm + 1f) * 127f).roundToInt(),
                extAlphaByte = (clampedOutsideA * 255f).roundToInt(),
                isLockCurtain = isLockCurtain,
                lockBlurActive = lockBlurActive,
                isSolidMatte = glassConfig.isSolidMatte,
                isTinted = glassConfig.isTinted,
                refractionPct = glassConfig.refractionPct,
                frostPct = glassConfig.frostPct,
                wpGenerationId = opticalEnv.crystalBitmap.generationId,
            )

            var bmp = holder.swCacheBmp
            if (bmp == null || holder.swCacheKey != key) {
                bmp = renderSoftwareSlidingSheet(
                    env = opticalEnv,
                    outW = wInt,
                    outH = hInt,
                    sheetEdgeY = clampedEdgeY,
                    bevelHeightPx = bevelHeightPx,
                    cornerRadiusPx = effectiveCornerPx,
                    dens = dens,
                    refractionScale = glassConfig.refractionScale,
                    frostScale = glassConfig.frostScale,
                    veilAlpha = veilAlpha,
                    vr = vr,
                    vg = vg,
                    vb = vb,
                    velocityNorm = velocityNorm,
                    isLockCurtain = isLockCurtain,
                    lockBlurActive = lockBlurActive,
                    outsideBackdropAlpha = clampedOutsideA,
                    isSolidMatte = glassConfig.isSolidMatte,
                )
                holder.swCacheKey = key
                holder.swCacheBmp = bmp
            }
            nc.drawBitmap(bmp, 0f, 0f, holder.bmpPaint)
        }
    }
}

/**
 * Full-screen iOS 26 Sliding Liquid Glass Sheet Host + Scene Provider for Control Center & Notification Center.
 */
@Composable
fun ControlCenterGlassSceneHost(
    wallpaperBitmap: Bitmap?,
    glassConfig: CcGlassConfig,
    openProgress: Float = 1f,
    modifier: Modifier = Modifier,
    pullOffsetProvider: () -> Float = { 0f },
    sheetEdgeYPx: Float = Float.NaN,
    sheetVelocityPxPerSec: Float = 0f,
    motionTickProvider: () -> Int = { 0 },
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val (fallbackW, fallbackH) = resolveWindowFallbackSizePx(
        context = context,
        density = density,
        cfgWidthDp = cfg.screenWidthDp,
        cfgHeightDp = cfg.screenHeightDp,
    )

    val opticalEnv = remember(wallpaperBitmap, glassConfig.isTinted) {
        buildOpticalEnvironment(
            sourceWallpaper = wallpaperBitmap,
            isTinted = glassConfig.isTinted,
        )
    }

    val latestPullProvider by rememberUpdatedState(pullOffsetProvider)
    val latestMotionProvider by rememberUpdatedState(motionTickProvider)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = (if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
            constraints.maxWidth.toFloat()
        } else {
            fallbackW
        }).coerceAtLeast(360f)
        val screenH = (if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            fallbackH
        }).coerceAtLeast(640f)

        val sceneState = remember(opticalEnv, screenW, screenH) {
            CcGlassSceneState(
                env = opticalEnv,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                pullOffsetProvider = { latestPullProvider() },
                motionTickProvider = { latestMotionProvider() },
            )
        }

        val resolvedEdgeY = (if (!sheetEdgeYPx.isNaN()) {
            sheetEdgeYPx
        } else {
            openProgress.coerceIn(0f, 1f) * screenH
        }).coerceIn(0f, screenH)

        CompositionLocalProvider(LocalCcGlassScene provides sceneState) {
            Box(modifier = Modifier.fillMaxSize()) {
                SlidingGlassSlabBackdrop(
                    opticalEnv = opticalEnv,
                    glassConfig = glassConfig,
                    resolvedEdgeYPx = resolvedEdgeY,
                    sheetVelocityPxPerSec = sheetVelocityPxPerSec,
                    isLockCurtain = false,
                    lockBlurActive = false,
                    outsideBackdropAlpha = 0f,
                )
                content()
            }
        }
    }
}

/**
 * Full-screen 1:1 iOS 26 Lock Screen Scene Host:
 * - Renders the sharp, unblurred Lock Screen wallpaper (or soft blurred aura in Full-Art Music mode)
 *   as a rising 1:1 refractive Liquid Glass curtain (`IOS26_SLIDING_SHEET_AGSL`)
 * - Supplies referentially stable [LocalCcGlassScene] so Lock Screen clock numerals, notification cards,
 *   Now Playing platter, and Flashlight / Camera droplets sample live root coordinates inside draw scopes.
 */
@Composable
fun LockScreenGlassSceneHost(
    wallpaperBitmap: Bitmap?,
    glassConfig: CcGlassConfig,
    blurBackdrop: Boolean = false,
    modifier: Modifier = Modifier,
    curtainLiftOffsetProvider: () -> Float = { 0f },
    sheetEdgeYPx: Float = Float.NaN,
    sheetVelocityPxPerSec: Float = 0f,
    outsideBackdropAlpha: Float = 0f,
    motionTickProvider: () -> Int = { 0 },
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val (fallbackW, fallbackH) = resolveWindowFallbackSizePx(
        context = context,
        density = density,
        cfgWidthDp = cfg.screenWidthDp,
        cfgHeightDp = cfg.screenHeightDp,
    )

    val opticalEnv = remember(wallpaperBitmap, glassConfig.isTinted) {
        buildOpticalEnvironment(
            sourceWallpaper = wallpaperBitmap,
            isTinted = glassConfig.isTinted,
        )
    }

    val latestLiftProvider by rememberUpdatedState(curtainLiftOffsetProvider)
    val latestMotionProvider by rememberUpdatedState(motionTickProvider)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenW = (if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
            constraints.maxWidth.toFloat()
        } else {
            fallbackW
        }).coerceAtLeast(360f)
        val screenH = (if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            fallbackH
        }).coerceAtLeast(640f)

        val sceneState = remember(opticalEnv, screenW, screenH) {
            CcGlassSceneState(
                env = opticalEnv,
                screenWidthPx = screenW,
                screenHeightPx = screenH,
                pullOffsetProvider = { latestLiftProvider() },
                motionTickProvider = { latestMotionProvider() },
            )
        }

        val resolvedEdgeY = (if (!sheetEdgeYPx.isNaN()) {
            sheetEdgeYPx
        } else {
            screenH + latestLiftProvider()
        }).coerceIn(0f, screenH)

        CompositionLocalProvider(LocalCcGlassScene provides sceneState) {
            Box(modifier = Modifier.fillMaxSize()) {
                SlidingGlassSlabBackdrop(
                    opticalEnv = opticalEnv,
                    glassConfig = glassConfig,
                    resolvedEdgeYPx = resolvedEdgeY,
                    sheetVelocityPxPerSec = sheetVelocityPxPerSec,
                    isLockCurtain = true,
                    lockBlurActive = blurBackdrop,
                    outsideBackdropAlpha = outsideBackdropAlpha,
                )
                content()
            }
        }
    }
}

/**
 * Applies Dock-grade 3D refractive iOS 26 Liquid Glass (`SamuraiDockGlassRenderer` calibration)
 * to any Control Center, Notification Center, or Lock Screen element, including blended active
 * illumination ([activeFraction]) and internal liquid slider columns ([sliderFill]).
 */
@Composable
fun Modifier.ccGlassPlatter(
    shape: Shape,
    glassConfig: CcGlassConfig,
    isLargePlatter: Boolean = false,
    activeFraction: Float = 0f,
    sliderFill: Float = -1f,
): Modifier {
    val scene = LocalCcGlassScene.current
    val holder = remember { PlatterShaderHolder() }
    val coordsRef = remember { Ref<LayoutCoordinates>() }
    val lastOriginRef = remember { Ref<Offset>() }

    return this
        .onGloballyPositioned { coords ->
            coordsRef.value = coords
            if (coords.isAttached) {
                lastOriginRef.value = coords.positionInRoot()
            }
        }
        .drawBehind {
            val env = scene.env
            val shader = holder.shader
            val w = size.width
            val h = size.height
            if (w <= 2f || h <= 2f) return@drawBehind

            // Subscribe ONLY this draw scope to sliding-sheet pull/lift and list-scroll ticks (0 recompositions)
            val pullTick = scene.pullOffsetProvider()
            val motionTick = scene.motionTickProvider()
            if (pullTick.isNaN() && motionTick == Int.MIN_VALUE) return@drawBehind

            val outline = shape.createOutline(size, layoutDirection, this)
            val cornerRadiusPx = extractCornerRadiusPx(shape, outline, size, this)
            val isCircle = if (
                abs(w - h) < 2.5f &&
                cornerRadiusPx >= min(w, h) * 0.46f
            ) 1f else 0f

            val clampedActive = activeFraction.coerceIn(0f, 1f)
            val clampedSlider = if (sliderFill < 0f) -1f else sliderFill.coerceIn(0f, 1f)

            if (glassConfig.isSolidMatte || env == null) {
                val baseFill = if (glassConfig.isSolidMatte) {
                    Color(0xD81C1F26)
                } else if (glassConfig.isTinted) {
                    Color(0x4AD8E2F0)
                } else {
                    Color(0x24FFFFFF)
                }
                val fill = if (clampedActive > 0.01f) {
                    Color(
                        red = baseFill.red + (0.94f - baseFill.red) * clampedActive * 0.65f,
                        green = baseFill.green + (0.96f - baseFill.green) * clampedActive * 0.65f,
                        blue = baseFill.blue + (1.0f - baseFill.blue) * clampedActive * 0.65f,
                        alpha = baseFill.alpha + (0.72f - baseFill.alpha) * clampedActive,
                    )
                } else {
                    baseFill
                }
                drawOutline(outline = outline, color = fill)
                if (clampedSlider > 0.002f) {
                    val clip = Path().apply { addOutline(outline) }
                    clipPath(clip) {
                        val fillTopY = h * (1f - clampedSlider)
                        drawRect(
                            color = Color(0xD8F2F6FF),
                            topLeft = Offset(0f, fillTopY),
                            size = Size(w, (h - fillTopY).coerceAtLeast(0f)),
                        )
                    }
                }
                val rim = Brush.linearGradient(
                    0.00f to Color.White.copy(alpha = 0.36f),
                    0.50f to Color.White.copy(alpha = 0.10f),
                    1.00f to Color.White.copy(alpha = 0.18f),
                    start = Offset.Zero,
                    end = Offset(w, h),
                )
                drawOutline(outline = outline, brush = rim, style = Stroke(width = 0.75.dp.toPx()))
                return@drawBehind
            }

            val dens = density.coerceAtLeast(1f)
            val pad = ceil(8f * dens)

            // Smooth Dock-calibrated frost: 0% -> 0.0 pure crystal; 20% -> 0.42 Clear / 0.82 Tinted
            val effectiveFrost = if (glassConfig.frostPct <= 0) {
                0f
            } else if (glassConfig.isTinted) {
                (0.75f + 0.35f * glassConfig.frostScale).coerceIn(0f, 0.96f)
            } else {
                (0.31f + 0.55f * glassConfig.frostScale).coerceIn(0f, 0.88f)
            }

            val frostVisFactor = if (glassConfig.frostPct <= 0) 0.25f else 1.0f
            val veilAlpha: Float
            val vr: Float
            val vg: Float
            val vb: Float
            val dispScale: Float
            if (glassConfig.isTinted) {
                veilAlpha = (if (isLargePlatter) 0.23f else 0.26f) * frostVisFactor
                vr = 0.78f; vg = 0.84f; vb = 0.92f
                dispScale = 0.45f
            } else {
                veilAlpha = (if (isLargePlatter) 0.115f else 0.105f) * frostVisFactor
                vr = 0.09f; vg = 0.12f; vb = 0.18f
                dispScale = 1.0f
            }

            val liveOrigin = coordsRef.value?.takeIf { it.isAttached }?.positionInRoot()?.also {
                lastOriginRef.value = it
            } ?: (lastOriginRef.value ?: Offset.Zero)
            val originX = liveOrigin.x
            val originY = liveOrigin.y
            val shadowStrength = if (isLargePlatter) 0.19f else 0.15f
            val nc = drawContext.canvas.nativeCanvas

            if (nc.isHardwareAccelerated && shader != null) {
                shader.setInputShader("uCrystalWp", env.crystalShader)
                shader.setInputShader("uSoftWp", env.softShader)
                shader.setFloatUniform("uElemSize", w, h)
                shader.setFloatUniform("uElemScreenOrigin", originX, originY)
                shader.setFloatUniform("uScreenSize", scene.screenWidthPx, scene.screenHeightPx)
                shader.setFloatUniform("uCrystalBufSize", env.crystalW, env.crystalH)
                shader.setFloatUniform("uSoftBufSize", env.softW, env.softH)
                shader.setFloatUniform("uPad", pad)
                shader.setFloatUniform("uRadius", cornerRadiusPx)
                shader.setFloatUniform("uDensity", dens)
                shader.setFloatUniform("uIsCircle", isCircle)
                shader.setFloatUniform("uEffectiveFrost", effectiveFrost)
                shader.setFloatUniform("uVeilAlpha", veilAlpha)
                shader.setFloatUniform("uVeilColor", vr, vg, vb)
                shader.setFloatUniform("uRefractionScale", glassConfig.refractionScale)
                shader.setFloatUniform("uFrostScale", glassConfig.frostScale)
                shader.setFloatUniform("uDispersionScale", dispScale)
                shader.setFloatUniform("uShadowStrength", shadowStrength)
                shader.setFloatUniform("uActiveFraction", clampedActive)
                shader.setFloatUniform("uSliderFill", clampedSlider)

                holder.paint.shader = shader
                nc.save()
                nc.translate(-pad, -pad)
                nc.drawRect(0f, 0f, w + pad * 2f, h + pad * 2f, holder.paint)
                nc.restore()
            } else {
                // Software Canvas (e.g. Roborazzi verification): evaluate exact 1:1 Dock-grade + active/slider optics.
                val key = SwPlatterCacheKey(
                    wQuarterPx = (w * 4f).roundToInt(),
                    hQuarterPx = (h * 4f).roundToInt(),
                    densCentis = (dens * 100f).roundToInt(),
                    originXHalfPx = (originX * 2f).roundToInt(),
                    originYHalfPx = (originY * 2f).roundToInt(),
                    radiusQuarterPx = (cornerRadiusPx * 4f).roundToInt(),
                    isCircle = isCircle > 0.5f,
                    isLargePlatter = isLargePlatter,
                    isTinted = glassConfig.isTinted,
                    refractionPct = glassConfig.refractionPct,
                    frostPct = glassConfig.frostPct,
                    activeByte = (clampedActive * 255f).roundToInt(),
                    sliderQuant = if (clampedSlider < 0f) -1 else (clampedSlider * 255f).roundToInt(),
                    wpGenerationId = env.crystalBitmap.generationId,
                )

                var bmp = holder.swCacheBmp
                if (bmp == null || holder.swCacheKey != key) {
                    bmp = renderSoftwareLiquidGlassPlatter(
                        env = env,
                        elemW = w,
                        elemH = h,
                        originX = originX,
                        originY = originY,
                        screenW = scene.screenWidthPx,
                        screenH = scene.screenHeightPx,
                        pad = pad,
                        radius = cornerRadiusPx,
                        dens = dens,
                        isCircle = isCircle > 0.5f,
                        effectiveFrost = effectiveFrost,
                        veilAlpha = veilAlpha,
                        vr = vr,
                        vg = vg,
                        vb = vb,
                        refractionScale = glassConfig.refractionScale,
                        frostScale = glassConfig.frostScale,
                        dispScale = dispScale,
                        shadowStrength = shadowStrength,
                        activeFraction = clampedActive,
                        sliderFill = clampedSlider,
                    )
                    holder.swCacheKey = key
                    holder.swCacheBmp = bmp
                }
                nc.drawBitmap(bmp, -pad, -pad, holder.bmpPaint)
            }
        }
}

/**
 * True 3D Cylindrical Refractive iOS 26 Liquid Glass Clock Numerals (`0-9` & `:`), shared by
 * both `IosLockScreen` and `NotificationCenterScreen` Cover Sheet:
 * - Parametric height-variable Apple SF Lock Clock geometry (uniform tubular stroke width at any height,
 *   zero stretched-font distortion)
 * - Live AGSL refraction of the wallpaper behind each digit stroke (`IOS26_CLOCK_GLASS_AGSL`)
 * - Also supports `isLiquidGlass = false` (Solid Pearl White option).
 */
@Composable
fun Ios26RefractiveClockGlass(
    clockText: String,
    isLiquidGlass: Boolean,
    glassConfig: CcGlassConfig,
    targetHeight: Dp,
    enableSpatialDepth: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scene = LocalCcGlassScene.current
    val holder = remember { ClockShaderHolder() }
    val coordsRef = remember { Ref<LayoutCoordinates>() }
    val lastOriginRef = remember { Ref<Offset>() }

    Canvas(
        modifier = modifier
            .height(targetHeight)
            .onGloballyPositioned { coords ->
                coordsRef.value = coords
                if (coords.isAttached) {
                    lastOriginRef.value = coords.positionInRoot()
                }
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 12f || h <= 12f) return@Canvas

        // Subscribe ONLY this draw scope to sliding-sheet pull/lift and list-scroll ticks (0 recompositions)
        val pullTick = scene.pullOffsetProvider()
        val motionTick = scene.motionTickProvider()
        if (pullTick.isNaN() && motionTick == Int.MIN_VALUE) return@Canvas

        val wInt = w.roundToInt().coerceAtLeast(16)
        val hInt = h.roundToInt().coerceAtLeast(16)
        val dens = density.coerceAtLeast(1f)
        val nc = drawContext.canvas.nativeCanvas

        var masks = holder.maskCache
        if (masks == null || masks.text != clockText || masks.width != wInt || masks.height != hInt) {
            masks = buildIos26ClockMaskPair(clockText, wInt, hInt, dens)
            holder.maskCache = masks
            holder.swCacheKey = null
        }

        val env = scene.env
        if (!isLiquidGlass || glassConfig.isSolidMatte || env == null) {
            // Crisp Solid Pearl-White iOS 26 Clock option using exact height-variable geometry
            drawIos26SolidClock(nc, masks.glyphPath, masks.layout, dens)
            return@Canvas
        }

        val liveOrigin = coordsRef.value?.takeIf { it.isAttached }?.positionInRoot()?.also {
            lastOriginRef.value = it
        } ?: (lastOriginRef.value ?: Offset.Zero)
        val originX = liveOrigin.x
        val originY = liveOrigin.y
        val domeScaleX = masks.domeW.toFloat() / wInt.toFloat()
        val domeScaleY = masks.domeH.toFloat() / hInt.toFloat()
        val hasSpatialFg = enableSpatialDepth && env.hasSpatialDiscForeground && targetHeight >= 300.dp
        val shader = holder.shader

        if (nc.isHardwareAccelerated && shader != null) {
            shader.setInputShader("uCrystalWp", env.crystalShader)
            shader.setInputShader("uSoftWp", env.softShader)
            shader.setInputShader("uSilMask", masks.silShader)
            shader.setInputShader("uDomeMask", masks.domeShader)
            shader.setFloatUniform("uElemSize", wInt.toFloat(), hInt.toFloat())
            shader.setFloatUniform("uElemScreenOrigin", originX, originY)
            shader.setFloatUniform("uScreenSize", scene.screenWidthPx, scene.screenHeightPx)
            shader.setFloatUniform("uCrystalBufSize", env.crystalW, env.crystalH)
            shader.setFloatUniform("uSoftBufSize", env.softW, env.softH)
            shader.setFloatUniform("uDomeScale", domeScaleX, domeScaleY)
            shader.setFloatUniform("uDensity", dens)
            shader.setFloatUniform("uRefractionScale", glassConfig.refractionScale)
            shader.setFloatUniform("uFrostScale", glassConfig.frostScale)
            shader.setFloatUniform("uIsTinted", if (glassConfig.isTinted) 1f else 0f)
            shader.setFloatUniform("uHasSpatialForeground", if (hasSpatialFg) 1f else 0f)

            holder.paint.shader = shader
            nc.drawRect(0f, 0f, wInt.toFloat(), hInt.toFloat(), holder.paint)
        } else {
            val key = SwClockCacheKey(
                clockText = clockText,
                wInt = wInt,
                hInt = hInt,
                densCentis = (dens * 100f).roundToInt(),
                originXHalfPx = (originX * 2f).roundToInt(),
                originYHalfPx = (originY * 2f).roundToInt(),
                isTinted = glassConfig.isTinted,
                hasSpatialFg = hasSpatialFg,
                refractionPct = glassConfig.refractionPct,
                frostPct = glassConfig.frostPct,
                wpGenerationId = env.crystalBitmap.generationId,
            )

            var bmp = holder.swCacheBmp
            if (bmp == null || holder.swCacheKey != key) {
                bmp = renderSoftwareLiquidGlassClock(
                    env = env,
                    masks = masks,
                    elemW = wInt,
                    elemH = hInt,
                    originX = originX,
                    originY = originY,
                    screenW = scene.screenWidthPx,
                    screenH = scene.screenHeightPx,
                    dens = dens,
                    refractionScale = glassConfig.refractionScale,
                    frostScale = glassConfig.frostScale,
                    isTinted = glassConfig.isTinted,
                    hasSpatialForeground = hasSpatialFg,
                )
                holder.swCacheKey = key
                holder.swCacheBmp = bmp
            }
            nc.drawBitmap(bmp, 0f, 0f, holder.bmpPaint)
        }
    }
}

/**
 * Shared iOS 26 Liquid Glass Home Indicator capsule (`138dp..156dp x 5dp`, `2.5dp` radius, `8dp` bottom inset)
 * inside a `220dp x 28dp` container. Supports 90Hz horizontal scrub (`+-14dp`), stretch (`+18dp`),
 * upward lift (`-5dp`), and `2500ms` idle dimming (`0.42f`).
 */
@Composable
fun Ios26HomeIndicatorBar(
    modifier: Modifier = Modifier,
    scrubFraction: Float = 0f,
    stretchFraction: Float = 0f,
    liftFraction: Float = 0f,
    idleDimmed: Boolean = false,
) {
    Canvas(
        modifier = modifier.size(width = 220.dp, height = 28.dp),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val clampedScrub = scrubFraction.coerceIn(-1f, 1f)
        val clampedStretch = stretchFraction.coerceIn(0f, 1f)
        val clampedLift = liftFraction.coerceIn(0f, 1f)

        val pillW = (138.dp.toPx() + 18.dp.toPx() * clampedStretch).coerceAtMost(w - 4.dp.toPx())
        val pillH = 5.dp.toPx()
        val radiusPx = pillH * 0.5f
        val scrubOffsetPx = 14.dp.toPx() * clampedScrub
        val liftOffsetPx = 5.dp.toPx() * clampedLift
        val bottomInsetPx = 8.dp.toPx()

        val left = ((w - pillW) * 0.5f + scrubOffsetPx).coerceIn(1f, max(1f, w - pillW - 1f))
        val top = (h - bottomInsetPx - pillH - liftOffsetPx).coerceIn(1f, max(1f, h - pillH - 1f))

        val activityBoost = max(abs(clampedScrub), max(clampedStretch, clampedLift))
        val masterAlpha = if (idleDimmed) {
            0.42f
        } else {
            (0.86f + 0.14f * activityBoost).coerceIn(0f, 1f)
        }
        val layerAlphaInt = (masterAlpha * 255f).roundToInt().coerceIn(0, 255)
        if (layerAlphaInt <= 0) return@Canvas

        val nc = drawContext.canvas.nativeCanvas
        val saveCount = nc.saveLayerAlpha(0f, 0f, w, h, layerAlphaInt)
        try {
            // Subtle dual-adaptive contact shadow for high legibility over bright white apps
            drawRoundRect(
                color = Color(0xFF060B14).copy(alpha = 0.28f),
                topLeft = Offset(left, top + 0.8.dp.toPx()),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            )

            // Luminous Liquid Glass body gradient
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.0f to Color(0xFFFAFCFF).copy(alpha = 0.94f),
                    0.55f to Color(0xFFEAF0FA).copy(alpha = 0.86f),
                    1.0f to Color(0xFFD6E0F0).copy(alpha = 0.82f),
                    startY = top,
                    endY = top + pillH,
                ),
                topLeft = Offset(left, top),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            )

            // 34-deg crystal specular rim highlight
            drawRoundRect(
                brush = Brush.linearGradient(
                    0.0f to Color.White.copy(alpha = 0.96f),
                    0.5f to Color.White.copy(alpha = 0.38f),
                    1.0f to Color.White.copy(alpha = 0.72f),
                    start = Offset(left, top),
                    end = Offset(left + pillW, top + pillH),
                ),
                topLeft = Offset(left, top),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = 0.65.dp.toPx()),
            )
        } finally {
            nc.restoreToCount(saveCount)
        }
    }
}

private data class ClockLayoutMetrics(
    val glyphs: List<Char>,
    val advanceWidths: FloatArray,
    val startX: Float,
    val yTop: Float,
    val digitH: Float,
    val strokeW: Float,
    val gap: Float,
)

private fun computeClockLayout(
    clockText: String,
    canvasW: Float,
    canvasH: Float,
    dens: Float,
): ClockLayoutMetrics {
    val glyphs = clockText.filter { it.isDigit() || it == ':' }.toList()
    val yTop = canvasH * 0.035f
    val digitH = (canvasH * 0.93f).coerceAtLeast(28f)

    // Authentic Apple iOS 26 SF Lock Screen height-variable tall condensed numeral proportions (`ref_ios26_ls_9to5mac.jpg`)
    var stdW = min(canvasW * 0.262f, digitH * 0.45f).coerceAtLeast(22f)
    var gap = stdW * 0.115f

    fun rawWidthFor(ch: Char, baseW: Float): Float = when (ch) {
        ':' -> baseW * 0.32f
        '1' -> baseW * 0.56f
        else -> baseW
    }

    var rawTotal = 0f
    for (i in glyphs.indices) {
        rawTotal += rawWidthFor(glyphs[i], stdW)
        if (i < glyphs.lastIndex) {
            val adjColon = glyphs[i] == ':' || glyphs[i + 1] == ':'
            rawTotal += if (adjColon) gap * 0.62f else gap
        }
    }

    val maxAllowedW = canvasW * 0.90f
    if (rawTotal > maxAllowedW && rawTotal > 1f) {
        val fit = maxAllowedW / rawTotal
        stdW *= fit
        gap *= fit
        rawTotal = maxAllowedW
    }

    val strokeW = (stdW * 0.228f).coerceIn(8.5f * dens, 26.0f * dens)
    val widths = FloatArray(glyphs.size) { rawWidthFor(glyphs[it], stdW) }
    val startX = (canvasW - rawTotal) * 0.5f

    return ClockLayoutMetrics(
        glyphs = glyphs,
        advanceWidths = widths,
        startX = startX,
        yTop = yTop,
        digitH = digitH,
        strokeW = strokeW,
        gap = gap,
    )
}

private fun stadiumPath(left: Float, top: Float, right: Float, bottom: Float): AndroidPath {
    val p = AndroidPath()
    if (right > left && bottom > top) {
        val rx = min((right - left) * 0.5f, (bottom - top) * 0.5f)
        p.addRoundRect(RectF(left, top, right, bottom), rx, rx, AndroidPath.Direction.CW)
    }
    return p
}

private fun annularStadiumPath(left: Float, top: Float, right: Float, bottom: Float, t: Float): AndroidPath {
    val outer = stadiumPath(left, top, right, bottom)
    val inner = stadiumPath(left + t, top + t, right - t, bottom - t)
    outer.op(inner, AndroidPath.Op.DIFFERENCE)
    return outer
}

private fun roundBoxPath(left: Float, top: Float, right: Float, bottom: Float, r: Float): AndroidPath {
    val p = AndroidPath()
    if (right > left && bottom > top) {
        val cr = min(r, min((right - left) * 0.5f, (bottom - top) * 0.5f))
        p.addRoundRect(RectF(left, top, right, bottom), cr, cr, AndroidPath.Direction.CW)
    }
    return p
}

/**
 * Builds a single closed vector [AndroidPath] for Apple iOS 26 SF Lock Screen Variable Numerals (`0-9` & `:`):
 * - Uniform wall thickness `T = layout.strokeW` at any height `H` (zero stretched-font distortion)
 * - Authentic flat horizontal/vertical SF terminal cuts with subtle micro-fillets (zero skinny balloon caps).
 */
private fun buildIos26ClockVectorPath(layout: ClockLayoutMetrics): AndroidPath {
    val master = AndroidPath()
    val t = layout.strokeW
    val cr = t * 0.14f
    val y0 = layout.yTop
    val y1 = layout.yTop + layout.digitH
    val h = max(y1 - y0, t * 2f)

    var curX = layout.startX
    val glyphs = layout.glyphs
    for (idx in glyphs.indices) {
        val ch = glyphs[idx]
        val gw = layout.advanceWidths[idx]
        val x0 = curX
        val x1 = curX + gw
        val cx = (x0 + x1) * 0.5f
        val r = min(gw * 0.5f, h * 0.25f)

        val gPath = AndroidPath()
        when (ch) {
            ':' -> {
                val dotR = t * 0.62f
                gPath.addCircle(cx, y0 + h * 0.382f, dotR, AndroidPath.Direction.CW)
                gPath.addCircle(cx, y0 + h * 0.682f, dotR, AndroidPath.Direction.CW)
            }
            '0' -> {
                gPath.set(annularStadiumPath(x0, y0, x1, y1, t))
            }
            '1' -> {
                val xStem = x1 - t
                val stem = roundBoxPath(xStem, y0, x1, y1, cr)
                val flag = AndroidPath().apply {
                    moveTo(xStem + cr, y0)
                    lineTo(xStem + t * 0.55f, y0 + t * 1.05f)
                    lineTo(x0, y0 + t * 1.98f)
                    lineTo(x0, y0 + t * 0.94f)
                    close()
                }
                stem.op(flag, AndroidPath.Op.UNION)
                val bounds = roundBoxPath(x0, y0, x1, y1, cr)
                stem.op(bounds, AndroidPath.Op.INTERSECT)
                gPath.set(stem)
            }
            '2' -> {
                val archBot = min(y0 + 2f * r + h * 0.16f, y1 - t)
                val topArch = annularStadiumPath(x0, y0, x1, archBot, t)
                val yLeftHook = y0 + r * 1.38f
                val yRightTurn = y0 + r * 1.24f
                val cutLeft = AndroidPath().apply {
                    addRect(x0 - 4f, yLeftHook, cx + 1f, y1 + 4f, AndroidPath.Direction.CW)
                }
                val cutRight = AndroidPath().apply {
                    addRect(cx - 1f, yRightTurn, x1 + 4f, y1 + 4f, AndroidPath.Direction.CW)
                }
                topArch.op(cutLeft, AndroidPath.Op.DIFFERENCE)
                topArch.op(cutRight, AndroidPath.Op.DIFFERENCE)

                val spanY = (y1 - t * 0.72f) - yRightTurn
                val diagW = t * 1.04f
                val ribbon = AndroidPath().apply {
                    moveTo(x1 - t, yRightTurn - 2f)
                    cubicTo(
                        x1 - t, yRightTurn + spanY * 0.34f,
                        x0, yRightTurn + spanY * 0.58f,
                        x0, y1 - t * 0.65f,
                    )
                    lineTo(x0, y1)
                    lineTo(x0 + diagW, y1 - t * 0.55f)
                    cubicTo(
                        x0 + diagW, yRightTurn + spanY * 0.62f,
                        x1, yRightTurn + spanY * 0.38f,
                        x1, yRightTurn - 2f,
                    )
                    close()
                }
                val baseBar = roundBoxPath(x0, y1 - t, x1, y1, cr)
                topArch.op(ribbon, AndroidPath.Op.UNION)
                topArch.op(baseBar, AndroidPath.Op.UNION)
                val bounds = roundBoxPath(x0, y0, x1, y1, cr)
                topArch.op(bounds, AndroidPath.Op.INTERSECT)
                gPath.set(topArch)
            }
            '3' -> {
                val yMid = y0 + h * 0.475f
                val inTop = gw * 0.025f
                val rTop = min((gw - inTop) * 0.5f, (yMid + t * 0.5f - y0) * 0.5f)
                val topArch = annularStadiumPath(x0 + inTop, y0, x1, yMid + t * 0.5f, t)
                val cutTop = AndroidPath().apply {
                    addRect(x0 - 4f, y0 + rTop * 1.16f, cx + gw * 0.05f, yMid + t, AndroidPath.Direction.CW)
                }
                topArch.op(cutTop, AndroidPath.Op.DIFFERENCE)

                val rBot = min(gw * 0.5f, (y1 - (yMid - t * 0.5f)) * 0.5f)
                val botArch = annularStadiumPath(x0, yMid - t * 0.5f, x1, y1, t)
                val cutBot = AndroidPath().apply {
                    addRect(x0 - 4f, yMid - t, cx + gw * 0.05f, y1 - rBot * 1.16f, AndroidPath.Direction.CW)
                }
                botArch.op(cutBot, AndroidPath.Op.DIFFERENCE)

                val waist = roundBoxPath(cx - gw * 0.10f, yMid - t * 0.5f, x1 - t * 0.30f, yMid + t * 0.5f, cr)
                topArch.op(botArch, AndroidPath.Op.UNION)
                topArch.op(waist, AndroidPath.Op.UNION)
                gPath.set(topArch)
            }
            '4' -> {
                val xStem = x0 + gw * 0.60f
                val yCross = y0 + h * 0.62f
                val stem = roundBoxPath(xStem, y0, xStem + t, y1, cr)
                val cross = roundBoxPath(x0, yCross, x1, yCross + t, cr)
                val diag = AndroidPath().apply {
                    moveTo(xStem, y0)
                    lineTo(xStem + t * 0.92f, y0)
                    lineTo(x0 + t * 1.06f, yCross + t * 0.55f)
                    lineTo(x0, yCross + t * 0.55f)
                    lineTo(x0, yCross)
                    close()
                }
                stem.op(cross, AndroidPath.Op.UNION)
                stem.op(diag, AndroidPath.Op.UNION)
                val bounds = roundBoxPath(x0, y0, x1, y1, cr)
                stem.op(bounds, AndroidPath.Op.INTERSECT)
                gPath.set(stem)
            }
            '5' -> {
                val yBowlTop = y0 + h * 0.415f
                val rBowl = min(gw * 0.5f, (y1 - yBowlTop) * 0.5f)
                val bowl = annularStadiumPath(x0, yBowlTop, x1, y1, t)
                val cutLeftGap = AndroidPath().apply {
                    addRect(
                        x0 - 4f,
                        yBowlTop + rBowl * 0.90f,
                        cx + gw * 0.02f,
                        y1 - rBowl * 1.18f,
                        AndroidPath.Direction.CW,
                    )
                }
                bowl.op(cutLeftGap, AndroidPath.Op.DIFFERENCE)

                val leftStem = roundBoxPath(x0, y0, x0 + t, yBowlTop + rBowl * 0.94f, cr)
                val topBar = roundBoxPath(x0, y0, x1 - gw * 0.02f, y0 + t, cr)
                bowl.op(leftStem, AndroidPath.Op.UNION)
                bowl.op(topBar, AndroidPath.Op.UNION)
                gPath.set(bowl)
            }
            '6' -> {
                val yBowlTop = y0 + h * 0.44f
                val outerHook = annularStadiumPath(x0, y0, x1, y1, t)
                val cutRight = AndroidPath().apply {
                    addRect(
                        cx - gw * 0.02f,
                        y0 + r * 1.18f,
                        x1 + 4f,
                        yBowlTop + (y1 - yBowlTop) * 0.5f,
                        AndroidPath.Direction.CW,
                    )
                }
                outerHook.op(cutRight, AndroidPath.Op.DIFFERENCE)
                val bowl = annularStadiumPath(x0, yBowlTop, x1, y1, t)
                outerHook.op(bowl, AndroidPath.Op.UNION)
                gPath.set(outerHook)
            }
            '7' -> {
                val topBar = roundBoxPath(x0, y0, x1, y0 + t, cr)
                val xFoot = x0 + gw * 0.22f
                val stem = AndroidPath().apply {
                    moveTo(x1 - t * 1.06f, y0 + t * 0.5f)
                    lineTo(x1, y0 + t * 0.92f)
                    cubicTo(
                        x1, y0 + h * 0.34f,
                        xFoot + t * 1.04f, y0 + h * 0.66f,
                        xFoot + t * 1.04f, y1,
                    )
                    lineTo(xFoot, y1)
                    cubicTo(
                        xFoot, y0 + h * 0.62f,
                        x1 - t * 1.06f, y0 + h * 0.30f,
                        x1 - t * 1.06f, y0 + t * 0.5f,
                    )
                    close()
                }
                topBar.op(stem, AndroidPath.Op.UNION)
                val bounds = roundBoxPath(x0, y0, x1, y1, cr)
                topBar.op(bounds, AndroidPath.Op.INTERSECT)
                gPath.set(topBar)
            }
            '8' -> {
                val yMid = y0 + h * 0.475f
                val inTop = gw * 0.035f
                val topLoop = annularStadiumPath(x0 + inTop, y0, x1 - inTop, yMid + t * 0.5f, t)
                val botLoop = annularStadiumPath(x0, yMid - t * 0.5f, x1, y1, t)
                topLoop.op(botLoop, AndroidPath.Op.UNION)
                gPath.set(topLoop)
            }
            '9' -> {
                val yBowlBot = y0 + h * 0.56f
                val outerHook = annularStadiumPath(x0, y0, x1, y1, t)
                val cutLeft = AndroidPath().apply {
                    addRect(
                        x0 - 4f,
                        y0 + (yBowlBot - y0) * 0.5f,
                        cx + gw * 0.02f,
                        y1 - r * 1.18f,
                        AndroidPath.Direction.CW,
                    )
                }
                outerHook.op(cutLeft, AndroidPath.Op.DIFFERENCE)
                val bowl = annularStadiumPath(x0, y0, x1, yBowlBot, t)
                outerHook.op(bowl, AndroidPath.Op.UNION)
                gPath.set(outerHook)
            }
        }
        master.op(gPath, AndroidPath.Op.UNION)

        if (idx < glyphs.lastIndex) {
            val adjColon = ch == ':' || glyphs[idx + 1] == ':'
            curX += gw + (if (adjColon) layout.gap * 0.62f else layout.gap)
        }
    }
    return master
}

private fun drawIos26SolidClock(
    canvas: AndroidCanvas,
    glyphPath: AndroidPath,
    layout: ClockLayoutMetrics,
    dens: Float,
) {
    val shPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.FILL
        color = 0x2E040A18
    }
    canvas.save()
    canvas.translate(0f, 2.4f * dens)
    canvas.drawPath(glyphPath, shPaint)
    canvas.restore()

    val grad = LinearGradient(
        0f, layout.yTop,
        0f, layout.yTop + layout.digitH,
        intArrayOf(0xFAFFFFFF.toInt(), 0xECEAF3FF.toInt(), 0xF8FFFFFF.toInt()),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    val fgPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.FILL
        shader = grad
    }
    canvas.drawPath(glyphPath, fgPaint)
}

private fun buildIos26ClockMaskPair(
    clockText: String,
    w: Int,
    h: Int,
    dens: Float,
): ClockMaskPair {
    val sil = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val silCanvas = AndroidCanvas(sil)
    val layout = computeClockLayout(clockText, w.toFloat(), h.toFloat(), dens)
    val glyphPath = buildIos26ClockVectorPath(layout)

    val fillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        style = AndroidPaint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    silCanvas.drawPath(glyphPath, fillPaint)

    val domeW = max(8, w / 2)
    val domeH = max(8, h / 2)
    // Build halfSil BEFORE modifying sil RGB so halfSil alpha represents pure glyph silhouette
    val halfSil = Bitmap.createScaledBitmap(sil, domeW, domeH, true)

    // Encode sub-pixel ~0.85dp edge field into premultiplied RGB of sil while preserving exact AA silhouette in Alpha
    val silPx = IntArray(w * h)
    sil.getPixels(silPx, 0, w, 0, 0, w, h)
    val boxR = (0.80f * dens).roundToInt().coerceIn(1, 5)
    encodeSubpixelEdgeIntoSil(silPx, w, h, boxR)
    sil.setPixels(silPx, 0, w, 0, 0, w, h)

    // Exact 2D Signed Euclidean Distance Transform (Felzenszwalb-Huttenlocher O(N)) in half-res space ->
    // packs 3 optical fields into opaque (A=255) domeBitmap with ZERO medial-axis crease or premultiplied loss:
    //   R = smooth circular-cylinder 3D heightfield (strictly monotonic cylindrical lens refraction + outer meniscus)
    //   G = normalized 8.8dp physical wall depth (broad 5..7dp inner TIR meniscus trough + droplet caustic)
    //   B = pre-integrated two-scale Euclidean exterior 3D lift & contact AO shadow field
    val halfScaleX = domeW.toFloat() / w.toFloat()
    val rHalf = max(layout.strokeW * 0.5f * halfScaleX, 2.5f)
    val bevelNormHalf = min(8.8f * dens * halfScaleX, rHalf * 0.88f).coerceAtLeast(2.0f)
    val dome = buildSignedEdtCylinderDomeBitmap(
        halfSil = halfSil,
        domeW = domeW,
        domeH = domeH,
        rHalf = rHalf,
        bevelNormHalf = bevelNormHalf,
        densHalf = dens * halfScaleX,
    )
    if (halfSil !== sil) {
        halfSil.recycle()
    }

    return ClockMaskPair(
        text = clockText,
        width = w,
        height = h,
        layout = layout,
        glyphPath = glyphPath,
        silBitmap = sil,
        domeBitmap = dome,
    )
}

private fun edt1dParabola(f: FloatArray, d: FloatArray, v: IntArray, z: FloatArray, n: Int) {
    var k = 0
    v[0] = 0
    z[0] = -1e20f
    z[1] = 1e20f
    for (q in 1 until n) {
        var s: Float
        while (true) {
            val vk = v[k]
            s = ((f[q] + q * q) - (f[vk] + vk * vk)) / (2f * (q - vk))
            if (k == 0 || s > z[k]) break
            k--
        }
        if (s <= z[0]) {
            k = 0
            v[0] = q
            z[0] = -1e20f
            z[1] = 1e20f
        } else {
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20f
        }
    }
    k = 0
    for (q in 0 until n) {
        while (z[k + 1] < q) k++
        val dx = (q - v[k]).toFloat()
        d[q] = dx * dx + f[v[k]]
    }
}

private fun computeEdt2dSquared(seedMask: BooleanArray, w: Int, h: Int): FloatArray {
    val inf = 1e12f
    val grid = FloatArray(w * h) { if (seedMask[it]) 0f else inf }
    val maxDim = max(w, h)
    val f = FloatArray(maxDim)
    val d = FloatArray(maxDim)
    val v = IntArray(maxDim)
    val z = FloatArray(maxDim + 1)

    for (x in 0 until w) {
        for (y in 0 until h) f[y] = grid[y * w + x]
        edt1dParabola(f, d, v, z, h)
        for (y in 0 until h) grid[y * w + x] = d[y]
    }
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) f[x] = grid[row + x]
        edt1dParabola(f, d, v, z, w)
        for (x in 0 until w) grid[row + x] = d[x]
    }
    return grid
}

private fun smoothFloatFieldSeparable(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
    val radius = ceil(sigma * 2.5f).toInt().coerceIn(1, 14)
    val kernel = FloatArray(radius * 2 + 1)
    var sum = 0f
    val twoSigSq = 2f * sigma * sigma
    for (i in -radius..radius) {
        val kv = exp(-(i * i) / twoSigSq)
        kernel[i + radius] = kv
        sum += kv
    }
    for (i in kernel.indices) kernel[i] /= sum

    val tmp = FloatArray(w * h)
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) {
                acc += src[row + (x + k).coerceIn(0, w - 1)] * kernel[k + radius]
            }
            tmp[row + x] = acc
        }
    }
    for (x in 0 until w) {
        for (y in 0 until h) {
            var acc = 0f
            for (k in -radius..radius) {
                acc += tmp[(y + k).coerceIn(0, h - 1) * w + x] * kernel[k + radius]
            }
            out[y * w + x] = acc
        }
    }
    return out
}

private fun buildSignedEdtCylinderDomeBitmap(
    halfSil: Bitmap,
    domeW: Int,
    domeH: Int,
    rHalf: Float,
    bevelNormHalf: Float,
    densHalf: Float,
): Bitmap {
    val n = domeW * domeH
    val halfPx = IntArray(n)
    halfSil.getPixels(halfPx, 0, domeW, 0, 0, domeW, domeH)

    val inside = BooleanArray(n)
    val outside = BooleanArray(n)
    for (i in 0 until n) {
        val a = (halfPx[i] ushr 24) and 0xFF
        inside[i] = a >= 128
        outside[i] = a < 128
    }

    val dSqToOutside = computeEdt2dSquared(outside, domeW, domeH)
    val dSqToInside = computeEdt2dSquared(inside, domeW, domeH)

    val zRaw = FloatArray(n)
    val depthRaw = FloatArray(n)
    val shRaw = FloatArray(n)
    val tightFall = max(2.2f * densHalf, 1.0f)
    val softFall = max(6.2f * densHalf, 2.5f)

    for (i in 0 until n) {
        val aFrac = ((halfPx[i] ushr 24) and 0xFF) / 255f
        val sdHalf = if (inside[i]) {
            sqrt(dSqToOutside[i]) - 0.5f + (aFrac - 0.5f) * 0.5f
        } else {
            -(sqrt(dSqToInside[i]) - 0.5f) + (aFrac - 0.5f) * 0.5f
        }

        // Circular-cylinder dome height continuing smoothly slightly past u=1 so peak slope sits right on outer wall
        val u = (1f - sdHalf / rHalf).coerceIn(0f, 1.25f)
        val zVal = if (u <= 1f) {
            sqrt(max(1f - 0.86f * u * u, 0f))
        } else {
            (0.37416f - 1.15f * (u - 1f)).coerceAtLeast(0f)
        }
        zRaw[i] = zVal.coerceIn(0f, 1f)

        depthRaw[i] = (sdHalf / bevelNormHalf).coerceIn(0f, 1f)

        val extDist = max(-sdHalf, 0f)
        shRaw[i] = if (sdHalf >= 0f) {
            1f
        } else {
            (0.66f * exp(-extDist / tightFall) + 0.34f * exp(-extDist / softFall)).coerceIn(0f, 1f)
        }
    }

    val zSmooth = smoothFloatFieldSeparable(zRaw, domeW, domeH, 1.65f)
    val depthSmooth = smoothFloatFieldSeparable(depthRaw, domeW, domeH, 0.85f)
    val shSmooth = smoothFloatFieldSeparable(shRaw, domeW, domeH, 0.90f)

    val packed = IntArray(n)
    for (i in 0 until n) {
        val rInt = (zSmooth[i] * 255f).roundToInt().coerceIn(0, 255)
        val gInt = (depthSmooth[i] * 255f).roundToInt().coerceIn(0, 255)
        val bInt = (shSmooth[i] * 255f).roundToInt().coerceIn(0, 255)
        packed[i] = (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
    }
    val out = Bitmap.createBitmap(domeW, domeH, Bitmap.Config.ARGB_8888)
    out.setPixels(packed, 0, domeW, 0, 0, domeW, domeH)
    return out
}

/**
 * O(1)-per-pixel 2-pass separable sliding-window blur of `silPx` alpha into premultiplied RGB (`rPremul <= aInt`),
 * producing a silky ~0.95dp sub-pixel edge ramp in < 2ms with zero inner halo bands.
 */
private fun encodeSubpixelEdgeIntoSil(silPx: IntArray, w: Int, h: Int, r: Int) {
    val n = w * h
    val srcA = IntArray(n) { (silPx[it] ushr 24) and 0xFF }
    val tmp = IntArray(n)
    val dst = IntArray(n)
    val win = 2 * r + 1

    fun passH(inp: IntArray, out: IntArray) {
        for (y in 0 until h) {
            val row = y * w
            var sum = inp[row] * (r + 1)
            for (dx in 1..r) {
                sum += inp[row + dx.coerceAtMost(w - 1)]
            }
            for (x in 0 until w) {
                out[row + x] = (sum + win / 2) / win
                val addIdx = (x + r + 1).coerceAtMost(w - 1)
                val subIdx = (x - r).coerceAtLeast(0)
                sum += inp[row + addIdx] - inp[row + subIdx]
            }
        }
    }

    fun passV(inp: IntArray, out: IntArray) {
        for (x in 0 until w) {
            var sum = inp[x] * (r + 1)
            for (dy in 1..r) {
                sum += inp[dy.coerceAtMost(h - 1) * w + x]
            }
            for (y in 0 until h) {
                out[y * w + x] = (sum + win / 2) / win
                val addIdx = (y + r + 1).coerceAtMost(h - 1)
                val subIdx = (y - r).coerceAtLeast(0)
                sum += inp[addIdx * w + x] - inp[subIdx * w + x]
            }
        }
    }

    // Two H+V passes yield a smooth piecewise-quadratic Gaussian-like kernel
    passH(srcA, tmp)
    passV(tmp, dst)
    passH(dst, tmp)
    passV(tmp, dst)

    for (i in 0 until n) {
        val aInt = srcA[i]
        if (aInt == 0) {
            silPx[i] = 0
        } else {
            // Bitmap.setPixels expects unpremultiplied ARGB and premultiplies internally for AGSL BitmapShader
            val eInt = dst[i].coerceIn(0, 255)
            silPx[i] = (aInt shl 24) or (eInt shl 16) or (eInt shl 8) or eInt
        }
    }
}

private fun sampleDomeChannelBilinear(
    pixels: IntArray,
    bufW: Int,
    bufH: Int,
    x: Float,
    y: Float,
    shift: Int,
): Float {
    val fx = (x - 0.5f).coerceIn(0f, (bufW - 1).toFloat())
    val fy = (y - 0.5f).coerceIn(0f, (bufH - 1).toFloat())
    val x0 = floor(fx).toInt().coerceIn(0, bufW - 1)
    val y0 = floor(fy).toInt().coerceIn(0, bufH - 1)
    val x1 = (x0 + 1).coerceAtMost(bufW - 1)
    val y1 = (y0 + 1).coerceAtMost(bufH - 1)
    val tx = fx - x0
    val ty = fy - y0

    val v00 = ((pixels[y0 * bufW + x0] ushr shift) and 0xFF) / 255f
    val v10 = ((pixels[y0 * bufW + x1] ushr shift) and 0xFF) / 255f
    val v01 = ((pixels[y1 * bufW + x0] ushr shift) and 0xFF) / 255f
    val v11 = ((pixels[y1 * bufW + x1] ushr shift) and 0xFF) / 255f

    val top = v00 + (v10 - v00) * tx
    val bot = v01 + (v11 - v01) * tx
    return top + (bot - top) * ty
}

private fun renderSoftwareLiquidGlassClock(
    env: CcOpticalEnvironment,
    masks: ClockMaskPair,
    elemW: Int,
    elemH: Int,
    originX: Float,
    originY: Float,
    screenW: Float,
    screenH: Float,
    dens: Float,
    refractionScale: Float,
    frostScale: Float,
    isTinted: Boolean,
    hasSpatialForeground: Boolean = false,
): Bitmap {
    val outPx = IntArray(elemW * elemH)
    val silPx = masks.silPixels
    val domePx = masks.domePixels
    val dW = masks.domeW
    val dH = masks.domeH
    val dScaleX = dW.toFloat() / elemW.toFloat()
    val dScaleY = dH.toFloat() / elemH.toFloat()

    val cPx = env.crystalPixels
    val cW = env.crystalBitmap.width
    val cH = env.crystalBitmap.height
    val sPx = env.softPixels
    val sW = env.softBitmap.width
    val sH = env.softBitmap.height

    val fgCenterX = -0.34043f * cW
    val fgCenterY = 0.85319f * cH
    val fgRadius = 1.15993f * cW

    val eps = max(2.0f * dens, 1.5f)
    val baseRefrAmp = if (refractionScale <= 0.001f) {
        0f
    } else {
        (max(refractionScale, 0.20f) * 2.50f).coerceIn(0.42f, 1.15f)
    }
    val frostFactor = if (frostScale <= 0.001f) {
        0f
    } else {
        (frostScale / 0.20f).coerceIn(0f, 1.6f)
    }
    val bodyTintA = if (isTinted) 0.11f else 0.048f

    fun sampleBgChannel(
        bufPx: IntArray,
        bW: Int,
        bH: Int,
        sx: Float,
        sy: Float,
        avoidFg: Boolean,
        shift: Int,
    ): Float {
        var adjSx = sx
        var adjSy = sy
        if (avoidFg) {
            val scaleX = bW / max(screenW, 1f)
            val scaleY = bH / max(screenH, 1f)
            val wpX = sx.coerceIn(0f, max(screenW - 1f, 1f)) * scaleX
            val wpY = sy.coerceIn(0f, max(screenH - 1f, 1f)) * scaleY
            val fgCx = -0.34043f * bW
            val fgCy = 0.85319f * bH
            val fgGr = 1.15993f * bW + 2.8f * (bW.toFloat() / max(cW, 1).toFloat())
            val dvx = wpX - fgCx
            val dvy = wpY - fgCy
            val d = sqrt(dvx * dvx + dvy * dvy)
            if (d < fgGr && d > 1e-3f) {
                val pushedX = fgCx + (dvx / d) * fgGr
                val pushedY = fgCy + (dvy / d) * fgGr
                adjSx = pushedX / max(scaleX, 1e-5f)
                adjSy = pushedY / max(scaleY, 1e-5f)
            }
        }
        return sampleBilinearChannel(bufPx, bW, bH, adjSx, adjSy, screenW, screenH, shift)
    }

    for (iy in 0 until elemH) {
        val py = iy + 0.5f
        val shPy = (py - 2.5f * dens).coerceIn(0.5f, elemH - 0.5f)
        val rowOff = iy * elemW

        for (ix in 0 until elemW) {
            val px = ix + 0.5f
            val shPx = (px - 0.9f * dens).coerceIn(0.5f, elemW - 0.5f)
            val silVal = silPx[rowOff + ix]
            val aIntSil = (silVal ushr 24) and 0xFF
            val aa = aIntSil / 255f

            var fgCover = 0f
            if (hasSpatialForeground) {
                val wpX = ((originX + px).coerceIn(0f, max(screenW - 1f, 1f))) * (cW / max(screenW, 1f))
                val wpY = ((originY + py).coerceIn(0f, max(screenH - 1f, 1f))) * (cH / max(screenH, 1f))
                val dxFg = wpX - fgCenterX
                val dyFg = wpY - fgCenterY
                val fgDist = sqrt(dxFg * dxFg + dyFg * dyFg)
                fgCover = 1f - smoothstepCpu(fgRadius - 0.85f, fgRadius + 0.85f, fgDist)
            }
            val visFront = 1f - fgCover

            val shField = sampleDomeChannelBilinear(domePx, dW, dH, shPx * dScaleX, shPy * dScaleY, 0)
            val shadowA = 0.27f * shField * (1f - aa) * visFront

            if (aa <= 0.002f) {
                if (shadowA > 0.002f) {
                    val sa = shadowA.coerceIn(0f, 1f)
                    val aInt = (sa * 255f).roundToInt().coerceIn(0, 255)
                    outPx[rowOff + ix] = (aInt shl 24) or (4 shl 16) or (8 shl 8) or 17
                }
                continue
            }

            val wallDepth = sampleDomeChannelBilinear(domePx, dW, dH, px * dScaleX, py * dScaleY, 8)
            val hL = sampleDomeChannelBilinear(domePx, dW, dH, (px - eps).coerceAtLeast(0f) * dScaleX, py * dScaleY, 16)
            val hR = sampleDomeChannelBilinear(domePx, dW, dH, (px + eps).coerceAtMost(elemW.toFloat()) * dScaleX, py * dScaleY, 16)
            val hT = sampleDomeChannelBilinear(domePx, dW, dH, px * dScaleX, (py - eps).coerceAtLeast(0f) * dScaleY, 16)
            val hB = sampleDomeChannelBilinear(domePx, dW, dH, px * dScaleX, (py + eps).coerceAtMost(elemH.toFloat()) * dScaleY, 16)

            val gx = hL - hR
            val gy = hT - hB
            val gLen = sqrt(gx * gx + gy * gy)
            val inv3d = 1f / sqrt(0.025f + gx * gx + gy * gy)
            val n3dX = gx * inv3d
            val n3dY = gy * inv3d
            val slope = sqrt(n3dX * n3dX + n3dY * n3dY).coerceIn(0f, 1f)
            val dirX = if (gLen > 1e-4f) gx / gLen else -0.52f
            val dirY = if (gLen > 1e-4f) gy / gLen else -0.85f

            val refrAmp = baseRefrAmp * visFront
            val pullX = -n3dX * (33.0f * dens) * refrAmp
            val pullY = -n3dY * (33.0f * dens) * refrAmp
            val baseX = originX + px + pullX
            val baseY = originY + py + pullY

            val disp = (0.85f * dens) * slope * slope * refrAmp
            val avoidFg = hasSpatialForeground && visFront > 0.05f
            val cR = sampleBgChannel(cPx, cW, cH, baseX + dirX * disp, baseY + dirY * disp, avoidFg, 16)
            val cG = sampleBgChannel(cPx, cW, cH, baseX, baseY, avoidFg, 8)
            val cB = sampleBgChannel(cPx, cW, cH, baseX - dirX * disp, baseY - dirY * disp, avoidFg, 0)

            val sR = sampleBgChannel(sPx, sW, sH, baseX + dirX * disp, baseY + dirY * disp, avoidFg, 16)
            val sG = sampleBgChannel(sPx, sW, sH, baseX, baseY, avoidFg, 8)
            val sB = sampleBgChannel(sPx, sW, sH, baseX - dirX * disp, baseY - dirY * disp, avoidFg, 0)

            val frostMix = if (frostFactor <= 0f) {
                0f
            } else {
                (((if (isTinted) 0.24f else 0.045f) +
                    (if (isTinted) 0.10f else 0.14f) * slope.toDouble().pow(1.55).toFloat()) * frostFactor)
                    .coerceIn(0f, 0.65f)
            }
            var gr = cR + (sR - cR) * frostMix
            var gg = cG + (sG - cG) * frostMix
            var gb = cB + (sB - cB) * frostMix

            val luma = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb
            gr = (luma + (gr - luma) * 1.12f).coerceIn(0f, 1f)
            gg = (luma + (gg - luma) * 1.12f).coerceIn(0f, 1f)
            gb = (luma + (gb - luma) * 1.12f).coerceIn(0f, 1f)

            gr = gr + (0.95f - gr) * bodyTintA
            gg = gg + (0.98f - gg) * bodyTintA
            gb = gb + (1.00f - gb) * bodyTintA

            val lightDot = (-0.52f * dirX - 0.85f * dirY).coerceIn(0f, 1f)
            val oppDot = (0.52f * dirX + 0.85f * dirY).coerceIn(0f, 1f)
            val botCurve = dirY.coerceIn(0f, 1f)
            val troughBand = smoothstepCpu(0.04f, 0.24f, wallDepth) * (1f - smoothstepCpu(0.32f, 0.78f, wallDepth))
            val tirDarken = troughBand * (0.10f + 0.12f * oppDot + 0.07f * botCurve) * visFront
            gr *= (1f - tirDarken)
            gg *= (1f - tirDarken)
            gb *= (1f - tirDarken)

            val shoulderBand = (1f - smoothstepCpu(0.05f, 0.42f, wallDepth)) * smoothstepCpu(0f, 0.08f, wallDepth)
            val bevelSheen = shoulderBand * 0.16f * lightDot * lightDot * visFront
            val causticFocus = smoothstepCpu(0.20f, 0.48f, wallDepth) * (1f - smoothstepCpu(0.52f, 0.86f, wallDepth)) *
                oppDot.toDouble().pow(1.8).toFloat() * 0.068f * visFront
            val cushionGlow = smoothstepCpu(0.45f, 0.92f, wallDepth) * 0.042f * (0.35f + 0.65f * visFront)
            val interiorBoost = bevelSheen + causticFocus + cushionGlow
            gr = (gr + interiorBoost).coerceIn(0f, 1f)
            gg = (gg + interiorBoost).coerceIn(0f, 1f)
            gb = (gb + interiorBoost).coerceIn(0f, 1f)

            val edgeField = (((silVal ushr 16) and 0xFF) / 255f).coerceIn(0f, 1f)
            val efDelta = (edgeField - 0.57f) / 0.16f
            val rimLine = exp(-efDelta * efDelta) * (1f - smoothstepCpu(0.70f, 0.93f, edgeField))
            val horizGrazing = abs(dirY).toDouble().pow(2.3).toFloat()
            val topLeftGlint = ((-0.46f * dirX - 0.89f * dirY).coerceIn(0f, 1f)).toDouble().pow(2.1).toFloat()
            val botFresnel = ((0.24f * dirX + 0.97f * dirY).coerceIn(0f, 1f)).toDouble().pow(2.2).toFloat()
            val rimSpec = rimLine * (0.032f + 0.24f * topLeftGlint + 0.16f * horizGrazing + 0.15f * botFresnel) * visFront
            gr = (gr + rimSpec).coerceIn(0f, 1f)
            gg = (gg + rimSpec).coerceIn(0f, 1f)
            gb = (gb + rimSpec).coerceIn(0f, 1f)

            val glassA = aa * (1f - 0.88f * fgCover)
            val outA = (glassA + shadowA).coerceIn(0f, 1f)
            val invA = if (outA > 1e-4f) 1f / outA else 0f
            val finalR = ((gr * glassA + 0.015f * shadowA) * invA).coerceIn(0f, 1f)
            val finalG = ((gg * glassA + 0.032f * shadowA) * invA).coerceIn(0f, 1f)
            val finalB = ((gb * glassA + 0.068f * shadowA) * invA).coerceIn(0f, 1f)

            outPx[rowOff + ix] = ((outA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                ((finalR * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                ((finalG * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                (finalB * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    val bmp = Bitmap.createBitmap(elemW, elemH, Bitmap.Config.ARGB_8888)
    bmp.setPixels(outPx, 0, elemW, 0, 0, elemW, elemH)
    return bmp
}

private fun sdGlassShapeCpu(px: Float, py: Float, bx: Float, by: Float, r: Float, isCircle: Boolean): Float {
    if (isCircle) {
        return sqrt(px * px + py * py) - min(bx, by)
    }
    val maxR = min(bx, by)
    val qx = abs(px) - bx + r
    val qy = abs(py) - by + r
    if (r >= maxR * 0.96f) {
        val mx = max(qx, 0f)
        val my = max(qy, 0f)
        return sqrt(mx * mx + my * my) + min(max(qx, qy), 0f) - r
    }
    if (qx <= 0f && qy <= 0f) {
        return min(max(qx, qy), 0f) - r
    }
    val qPosX = max(qx, 0.0005f).toDouble()
    val qPosY = max(qy, 0.0005f).toDouble()
    val corner = (qPosX.pow(2.65) + qPosY.pow(2.65)).pow(0.37735849).toFloat()
    return corner - r
}

private fun smoothstepCpu(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun sampleBilinearChannel(
    pixels: IntArray,
    bufW: Int,
    bufH: Int,
    screenX: Float,
    screenY: Float,
    screenW: Float,
    screenH: Float,
    shift: Int,
): Float {
    val cx = screenX.coerceIn(0f, max(screenW - 1f, 1f)) * (bufW.toFloat() / max(screenW, 1f))
    val cy = screenY.coerceIn(0f, max(screenH - 1f, 1f)) * (bufH.toFloat() / max(screenH, 1f))
    val fx = (cx - 0.5f).coerceIn(0f, (bufW - 1).toFloat())
    val fy = (cy - 0.5f).coerceIn(0f, (bufH - 1).toFloat())
    val x0 = floor(fx).toInt().coerceIn(0, bufW - 1)
    val y0 = floor(fy).toInt().coerceIn(0, bufH - 1)
    val x1 = (x0 + 1).coerceAtMost(bufW - 1)
    val y1 = (y0 + 1).coerceAtMost(bufH - 1)
    val tx = fx - x0
    val ty = fy - y0

    val c00 = ((pixels[y0 * bufW + x0] ushr shift) and 0xFF) / 255f
    val c10 = ((pixels[y0 * bufW + x1] ushr shift) and 0xFF) / 255f
    val c01 = ((pixels[y1 * bufW + x0] ushr shift) and 0xFF) / 255f
    val c11 = ((pixels[y1 * bufW + x1] ushr shift) and 0xFF) / 255f

    val top = c00 + (c10 - c00) * tx
    val bot = c01 + (c11 - c01) * tx
    return top + (bot - top) * ty
}

private fun renderSoftwareLiquidGlassPlatter(
    env: CcOpticalEnvironment,
    elemW: Float,
    elemH: Float,
    originX: Float,
    originY: Float,
    screenW: Float,
    screenH: Float,
    pad: Float,
    radius: Float,
    dens: Float,
    isCircle: Boolean,
    effectiveFrost: Float,
    veilAlpha: Float,
    vr: Float,
    vg: Float,
    vb: Float,
    refractionScale: Float,
    frostScale: Float,
    dispScale: Float,
    shadowStrength: Float,
    activeFraction: Float,
    sliderFill: Float,
): Bitmap {
    val outW = max(1, ceil(elemW + pad * 2f).toInt())
    val outH = max(1, ceil(elemH + pad * 2f).toInt())
    val outPx = IntArray(outW * outH)

    val halfX = max(elemW * 0.5f, 1f)
    val halfY = max(elemH * 0.5f, 1f)
    val r = radius.coerceIn(0f, min(halfX, halfY))
    val minDim = min(halfX, halfY)
    val lipW = min(16.5f * dens, max(minDim * 0.62f, 6.0f * dens))

    val cPx = env.crystalPixels
    val cW = env.crystalBitmap.width
    val cH = env.crystalBitmap.height
    val sPx = env.softPixels
    val sW = env.softBitmap.width
    val sH = env.softBitmap.height

    val eps = 1.1f
    val satBoost = 1f + 0.10f * max(frostScale, refractionScale).coerceIn(0f, 1f)
    val veilClamped = veilAlpha.coerceIn(0f, 0.80f)
    val frostVis = (frostScale * 1.4f).coerceIn(0f, 1f)
    val refractVis = refractionScale.coerceIn(0f, 1.5f)
    val effFrostClamped = effectiveFrost.coerceIn(0f, 1f)
    val actClamped = activeFraction.coerceIn(0f, 1f)
    val hasSlider = sliderFill >= 0f
    val sfClamped = if (hasSlider) sliderFill.coerceIn(0f, 1f) else 0f
    val fillTopY = elemH * (1f - sfClamped)

    for (iy in 0 until outH) {
        val localY = iy + 0.5f - pad
        val relY = localY - halfY
        val yNorm = (localY / max(elemH, 1f)).coerceIn(0f, 1f)
        val topSheen = (1f - yNorm) * 0.032f * frostVis
        val rowOff = iy * outW

        for (ix in 0 until outW) {
            val localX = ix + 0.5f - pad
            val relX = localX - halfX
            val d = sdGlassShapeCpu(relX, relY, halfX, halfY, r, isCircle)
            val aa = 1f - smoothstepCpu(-0.65f, 0.65f, d)

            var shadowA = 0f
            if (shadowStrength > 0.001f && d > -0.65f) {
                val dSh = sdGlassShapeCpu(relX, relY - 2.2f * dens, halfX, halfY, r, isCircle)
                val shFall = exp(-max(dSh, 0f) / max(3.4f * dens, 1f))
                shadowA = shadowStrength * shFall * (1f - aa)
            }
            if (aa <= 0.001f) {
                if (shadowA > 0.002f) {
                    val sa = shadowA.coerceIn(0f, 1f)
                    val aInt = (sa * 255f).roundToInt().coerceIn(0, 255)
                    val rInt = (0.024f * 255f).roundToInt().coerceIn(0, 255)
                    val gInt = (0.047f * 255f).roundToInt().coerceIn(0, 255)
                    val bInt = (0.094f * 255f).roundToInt().coerceIn(0, 255)
                    outPx[rowOff + ix] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                }
                continue
            }

            val dx = sdGlassShapeCpu(relX + eps, relY, halfX, halfY, r, isCircle) -
                sdGlassShapeCpu(relX - eps, relY, halfX, halfY, r, isCircle)
            val dy = sdGlassShapeCpu(relX, relY + eps, halfX, halfY, r, isCircle) -
                sdGlassShapeCpu(relX, relY - eps, halfX, halfY, r, isCircle)
            val nLen = max(sqrt(dx * dx + dy * dy), 1e-5f)
            val nx = dx / nLen
            val ny = dy / nLen

            val edge = (1f + d / lipW).coerceIn(0f, 1f)
            val meniscus = 1f - sqrt(max(1f - edge * edge, 0f))
            val domeX = (relX / halfX) * (3.2f * dens) * refractionScale
            val domeY = (relY / halfY) * (5.0f * dens) * refractionScale
            val pullX = -nx * (12.5f * dens) * meniscus * refractionScale - domeX
            val pullY = -ny * (19.5f * dens) * meniscus * refractionScale - domeY
            val baseX = originX + localX + pullX
            val baseY = originY + localY + pullY

            val disp = (2.35f * dens) * meniscus * dispScale * refractionScale
            val pRx = baseX + nx * disp
            val pRy = baseY + ny * disp
            val pBx = baseX - nx * disp
            val pBy = baseY - ny * disp

            val cR = sampleBilinearChannel(cPx, cW, cH, pRx, pRy, screenW, screenH, 16)
            val cG = sampleBilinearChannel(cPx, cW, cH, baseX, baseY, screenW, screenH, 8)
            val cB = sampleBilinearChannel(cPx, cW, cH, pBx, pBy, screenW, screenH, 0)

            val sR = sampleBilinearChannel(sPx, sW, sH, pRx, pRy, screenW, screenH, 16)
            val sG = sampleBilinearChannel(sPx, sW, sH, baseX, baseY, screenW, screenH, 8)
            val sB = sampleBilinearChannel(sPx, sW, sH, pBx, pBy, screenW, screenH, 0)

            val localFrost = effFrostClamped * (1f - 0.62f * meniscus)
            var gr = cR + (sR - cR) * localFrost
            var gg = cG + (sG - cG) * localFrost
            var gb = cB + (sB - cB) * localFrost

            val luma = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb
            gr = (luma + (gr - luma) * satBoost).coerceIn(0f, 1f)
            gg = (luma + (gg - luma) * satBoost).coerceIn(0f, 1f)
            gb = (luma + (gb - luma) * satBoost).coerceIn(0f, 1f)

            gr = (gr + (vr - gr) * veilClamped + topSheen).coerceIn(0f, 1f)
            gg = (gg + (vg - gg) * veilClamped + topSheen).coerceIn(0f, 1f)
            gb = (gb + (vb - gb) * veilClamped + topSheen).coerceIn(0f, 1f)

            if (actClamped > 0.002f) {
                val liftR = (gr * 0.42f + 0.62f).coerceIn(0f, 1f)
                val liftG = (gg * 0.42f + 0.65f).coerceIn(0f, 1f)
                val liftB = (gb * 0.42f + 0.70f).coerceIn(0f, 1f)
                val pR2 = 0.965f + (liftR - 0.965f) * 0.26f
                val pG2 = 0.980f + (liftG - 0.980f) * 0.26f
                val pB2 = 1.000f + (liftB - 1.000f) * 0.26f
                val coreBlend = actClamped * (0.62f - 0.27f * meniscus)
                gr += (pR2 - gr) * coreBlend
                gg += (pG2 - gg) * coreBlend
                gb += (pB2 - gb) * coreBlend
            }

            if (hasSlider && sfClamped > 0.002f) {
                val inFill = if (sfClamped >= 0.998f) {
                    1f
                } else {
                    smoothstepCpu(fillTopY - 0.9f * dens, fillTopY + 0.9f * dens, localY)
                }
                val liftR = (gr * 0.44f + 0.60f).coerceIn(0f, 1f)
                val liftG = (gg * 0.44f + 0.64f).coerceIn(0f, 1f)
                val liftB = (gb * 0.44f + 0.70f).coerceIn(0f, 1f)
                val lR = 0.970f + (liftR - 0.970f) * 0.28f
                val lG = 0.985f + (liftG - 0.985f) * 0.28f
                val lB = 1.000f + (liftB - 1.000f) * 0.28f
                val fillBlend = inFill * (0.60f - 0.27f * meniscus)
                gr += (lR - gr) * fillBlend
                gg += (lG - gg) * fillBlend
                gb += (lB - gb) * fillBlend

                if (sfClamped > 0.015f && sfClamped < 0.985f) {
                    val surfDist = (localY - fillTopY) / max(1.15f * dens, 0.5f)
                    val surfCrest = exp(-(surfDist * surfDist)) * (1f - 0.45f * meniscus) * 0.16f
                    gr = (gr + surfCrest).coerceIn(0f, 1f)
                    gg = (gg + surfCrest).coerceIn(0f, 1f)
                    gb = (gb + surfCrest).coerceIn(0f, 1f)
                }
            }

            val depthPx = max(-d, 0f)
            val topAlign = (-ny).coerceIn(0f, 1f)
            val causArg = (depthPx - 3.8f * dens) / max(2.6f * dens, 0.6f)
            val caustic = exp(-(causArg * causArg)) * topAlign * 0.055f * refractVis
            gr = (gr + caustic).coerceIn(0f, 1f)
            gg = (gg + caustic).coerceIn(0f, 1f)
            gb = (gb + caustic).coerceIn(0f, 1f)

            val lightDot = (-0.5592f * nx - 0.8290f * ny).coerceIn(0f, 1f)
            val oppDot = (0.5592f * nx + 0.8290f * ny).coerceIn(0f, 1f)
            val rimArg = (depthPx - 0.62f * dens) / max(0.58f * dens, 0.30f)
            val rimLine = exp(-(rimArg * rimArg))
            val rimSpec = rimLine * (
                0.068f +
                    0.29f * lightDot.toDouble().pow(1.65).toFloat() +
                    0.10f * oppDot.toDouble().pow(2.2).toFloat()
                )
            gr = (gr + rimSpec).coerceIn(0f, 1f)
            gg = (gg + rimSpec).coerceIn(0f, 1f)
            gb = (gb + rimSpec).coerceIn(0f, 1f)

            val outA = (aa + shadowA).coerceIn(0f, 1f)
            val invA = if (outA > 1e-4f) 1f / outA else 0f
            val finalR = ((gr * aa + 0.024f * shadowA) * invA).coerceIn(0f, 1f)
            val finalG = ((gg * aa + 0.047f * shadowA) * invA).coerceIn(0f, 1f)
            val finalB = ((gb * aa + 0.094f * shadowA) * invA).coerceIn(0f, 1f)

            outPx[rowOff + ix] = ((outA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                ((finalR * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                ((finalG * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                (finalB * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    bmp.setPixels(outPx, 0, outW, 0, 0, outW, outH)
    return bmp
}

private fun extractCornerRadiusPx(
    shape: Shape,
    outline: Outline,
    size: Size,
    density: Density,
): Float {
    val maxR = min(size.width, size.height) * 0.5f
    if (shape is ContinuousSquircleShape) {
        return with(density) { shape.cornerRadius.toPx() }.coerceIn(0f, maxR)
    }
    return when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x.coerceIn(0f, maxR)
        is Outline.Rectangle -> 0f
        is Outline.Generic -> maxR * 0.44f
    }
}

private fun buildOpticalEnvironment(
    sourceWallpaper: Bitmap?,
    isTinted: Boolean,
): CcOpticalEnvironment {
    val crystal: Bitmap = if (sourceWallpaper != null && !sourceWallpaper.isRecycled &&
        sourceWallpaper.width > 0 && sourceWallpaper.height > 0
    ) {
        ensureSoftwareBitmap(sourceWallpaper, 540, 1200)
    } else {
        synthesizeCoastalReferenceWallpaper(540, 1200)
    }

    val ambScaled = Bitmap.createScaledBitmap(crystal, 270, 600, true)
    val ambientSigma = if (isTinted) 10.5f else 6.5f
    val ambient = blurSeparable(ambScaled, ambientSigma)

    val softScaled = Bitmap.createScaledBitmap(crystal, 180, 400, true)
    val soft = blurSeparable(softScaled, 11.5f)
    val hasSpatialFg = detectSpatialDiscForeground(crystal)

    return CcOpticalEnvironment(
        crystalBitmap = crystal,
        softBitmap = soft,
        ambientBitmap = ambient,
        hasSpatialDiscForeground = hasSpatialFg,
    )
}

/**
 * Automatically detects whether [crystal] is one of our iOS 26 Spatial Glass Disc wallpapers
 * (`wp_ios26_sky_light`, `wp_ios26_sky_dark`, `Aurora Cyan`, or `default_wallpaper`) by testing
 * 6 normal vs tangential gradient samples along the exact foreground disc arc
 * `(cx = -0.34043*W, cy = 0.85319*H, R = 1.15993*W)`. Returns false for custom user photos.
 */
private fun detectSpatialDiscForeground(crystal: Bitmap): Boolean {
    val w = crystal.width
    val h = crystal.height
    if (w < 120 || h < 240) return false
    val cx = -0.34043f * w
    val cy = 0.85319f * h
    val r = 1.15993f * w
    val xFracs = floatArrayOf(0.06f, 0.14f, 0.22f, 0.30f, 0.38f, 0.44f)
    var hits = 0
    for (xf in xFracs) {
        val px = xf * w
        val radSq = r * r - (px - cx) * (px - cx)
        if (radSq <= 4f) continue
        val py = cy - sqrt(radSq)
        val nx = (px - cx) / r
        val ny = (py - cy) / r
        val ixIn = (px - nx * 4.5f).roundToInt().coerceIn(0, w - 1)
        val iyIn = (py - ny * 4.5f).roundToInt().coerceIn(0, h - 1)
        val ixOut = (px + nx * 4.5f).roundToInt().coerceIn(0, w - 1)
        val iyOut = (py + ny * 4.5f).roundToInt().coerceIn(0, h - 1)

        val cIn = crystal.getPixel(ixIn, iyIn)
        val cOut = crystal.getPixel(ixOut, iyOut)
        val dr = ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
        val dg = ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
        val db = (cIn and 0xFF) - (cOut and 0xFF)
        val normDiff = sqrt((dr * dr + dg * dg + db * db).toFloat())
        if (normDiff > 21.0f) {
            hits++
        }
    }
    return hits >= 5
}

private fun ensureSoftwareBitmap(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
    val sw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
        src.copy(Bitmap.Config.ARGB_8888, false) ?: return synthesizeCoastalReferenceWallpaper(targetW, targetH)
    } else {
        src
    }
    if (sw.width == targetW && sw.height == targetH && sw.config == Bitmap.Config.ARGB_8888) {
        return sw
    }
    val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(out)
    val scale = max(targetW.toFloat() / sw.width.coerceAtLeast(1), targetH.toFloat() / sw.height.coerceAtLeast(1))
    val dw = sw.width * scale
    val dh = sw.height * scale
    val dx = (targetW - dw) * 0.5f
    val dy = (targetH - dh) * 0.5f
    val p = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)
    c.save()
    c.translate(dx, dy)
    c.scale(scale, scale)
    c.drawBitmap(sw, 0f, 0f, p)
    c.restore()
    if (sw !== src) {
        sw.recycle()
    }
    return out
}

private fun synthesizeCoastalReferenceWallpaper(w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

    p.shader = LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(
            0xFF3E6C96.toInt(),
            0xFF6A98BA.toInt(),
            0xFFA8C6DC.toInt(),
            0xFFE2C9B6.toInt(),
            0xFF9C7644.toInt(),
            0xFF564A28.toInt(),
            0xFF28271B.toInt(),
            0xFF151813.toInt(),
        ),
        floatArrayOf(0.00f, 0.24f, 0.44f, 0.57f, 0.66f, 0.76f, 0.88f, 1.00f),
        Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

    // Subtle upper-sky diagonal sunbeams so clock refraction bends visible sky gradients even before WallpaperManager loads
    for (idx in 0 until 4) {
        val xStart = w * (0.18f + idx * 0.22f)
        val beamPath = AndroidPath().apply {
            moveTo(xStart, -h * 0.04f)
            lineTo(xStart + w * 0.12f, -h * 0.04f)
            lineTo(xStart - w * 0.16f, h * 0.46f)
            lineTo(xStart - w * 0.28f, h * 0.46f)
            close()
        }
        p.shader = LinearGradient(
            xStart, 0f, xStart - w * 0.20f, h * 0.46f,
            intArrayOf(0x28E8F4FF, 0x16DCEEFF, 0x00DCEEFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawPath(beamPath, p)
    }

    p.shader = null
    val cloudPath = AndroidPath().apply {
        moveTo(-w * 0.10f, h * 0.54f)
        cubicTo(w * 0.26f, h * 0.44f, w * 0.62f, h * 0.43f, w * 1.12f, h * 0.56f)
        lineTo(w * 1.12f, h * 0.67f)
        cubicTo(w * 0.64f, h * 0.59f, w * 0.30f, h * 0.58f, -w * 0.10f, h * 0.63f)
        close()
    }
    p.shader = LinearGradient(
        0f, h * 0.44f, w.toFloat(), h * 0.66f,
        intArrayOf(0xAAFFF3E6.toInt(), 0xB8F5D4BC.toInt(), 0x88C8A286.toInt()),
        floatArrayOf(0.0f, 0.52f, 1.0f),
        Shader.TileMode.CLAMP,
    )
    c.drawPath(cloudPath, p)

    val ridgePath = AndroidPath().apply {
        moveTo(-w * 0.05f, h * 0.56f)
        cubicTo(w * 0.22f, h * 0.59f, w * 0.58f, h * 0.66f, w * 1.08f, h * 0.72f)
        lineTo(w * 1.08f, h * 1.02f)
        lineTo(-w * 0.05f, h * 1.02f)
        close()
    }
    p.shader = LinearGradient(
        0f, h * 0.56f, 0f, h.toFloat(),
        intArrayOf(0xDD9A6E34.toInt(), 0xEE4A4021.toInt(), 0xFF141812.toInt()),
        floatArrayOf(0.0f, 0.45f, 1.0f),
        Shader.TileMode.CLAMP,
    )
    c.drawPath(ridgePath, p)
    return bmp
}

private fun blurAlphaSeparable(src: Bitmap, sigma: Float): Bitmap {
    val w = src.width
    val h = src.height
    val radius = ceil(sigma * 2.4f).toInt().coerceIn(1, 28)
    val kernel = FloatArray(radius * 2 + 1)
    var sum = 0f
    val twoSigmaSq = 2f * sigma * sigma
    for (i in -radius..radius) {
        val v = exp(-(i * i) / twoSigmaSq)
        kernel[i + radius] = v
        sum += v
    }
    for (i in kernel.indices) kernel[i] /= sum

    val inPx = IntArray(w * h)
    val tmpA = FloatArray(w * h)
    val outPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)

    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var a = 0f
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, w - 1)
                a += ((inPx[row + sx] ushr 24) and 0xFF) * kernel[k + radius]
            }
            tmpA[row + x] = a
        }
    }

    for (x in 0 until w) {
        for (y in 0 until h) {
            var a = 0f
            for (k in -radius..radius) {
                val sy = (y + k).coerceIn(0, h - 1)
                a += tmpA[sy * w + x] * kernel[k + radius]
            }
            val aInt = a.roundToInt().coerceIn(0, 255)
            outPx[y * w + x] = (aInt shl 24) or (aInt shl 16) or (aInt shl 8) or aInt
        }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(outPx, 0, w, 0, 0, w, h)
    return out
}

private fun blurSeparable(src: Bitmap, sigma: Float): Bitmap {
    val w = src.width
    val h = src.height
    val radius = ceil(sigma * 2.4f).toInt().coerceIn(1, 28)
    val kernel = FloatArray(radius * 2 + 1)
    var sum = 0f
    val twoSigmaSq = 2f * sigma * sigma
    for (i in -radius..radius) {
        val v = exp(-(i * i) / twoSigmaSq)
        kernel[i + radius] = v
        sum += v
    }
    for (i in kernel.indices) kernel[i] /= sum

    val inPx = IntArray(w * h)
    val tmpPx = IntArray(w * h)
    val outPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)

    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            for (k in -radius..radius) {
                val sx = (x + k).coerceIn(0, w - 1)
                val c = inPx[row + sx]
                val wt = kernel[k + radius]
                r += ((c ushr 16) and 0xFF) * wt
                g += ((c ushr 8) and 0xFF) * wt
                b += (c and 0xFF) * wt
            }
            tmpPx[row + x] = (0xFF shl 24) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }

    for (x in 0 until w) {
        for (y in 0 until h) {
            var r = 0f; var g = 0f; var b = 0f
            for (k in -radius..radius) {
                val sy = (y + k).coerceIn(0, h - 1)
                val c = tmpPx[sy * w + x]
                val wt = kernel[k + radius]
                r += ((c ushr 16) and 0xFF) * wt
                g += ((c ushr 8) and 0xFF) * wt
                b += (c and 0xFF) * wt
            }
            outPx[y * w + x] = (0xFF shl 24) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(outPx, 0, w, 0, 0, w, h)
    return out
}

private fun sdSheetSlabCpu(px: Float, py: Float, w: Float, edgeY: Float, r: Float, topExt: Float): Float {
    val cx = w * 0.5f
    val cy = (edgeY - topExt) * 0.5f
    val bx = max(w * 0.5f, 1f)
    val by = max((edgeY + topExt) * 0.5f, 1f)
    val rr = r.coerceIn(0f, min(bx, by))
    val qx = abs(px - cx) - bx + rr
    val qy = abs(py - cy) - by + rr
    val ox = max(qx, 0f)
    val oy = max(qy, 0f)
    return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - rr
}

private fun renderSoftwareSlidingSheet(
    env: CcOpticalEnvironment,
    outW: Int,
    outH: Int,
    sheetEdgeY: Float,
    bevelHeightPx: Float,
    cornerRadiusPx: Float,
    dens: Float,
    refractionScale: Float,
    frostScale: Float,
    veilAlpha: Float,
    vr: Float,
    vg: Float,
    vb: Float,
    velocityNorm: Float,
    isLockCurtain: Boolean,
    lockBlurActive: Boolean,
    outsideBackdropAlpha: Float,
    isSolidMatte: Boolean,
): Bitmap {
    val outPx = IntArray(outW * outH)
    val screenW = outW.toFloat()
    val screenH = outH.toFloat()
    val cPx = env.crystalPixels
    val cW = env.crystalBitmap.width
    val cH = env.crystalBitmap.height
    val sPx = env.softPixels
    val sW = env.softBitmap.width
    val sH = env.softBitmap.height
    val extAlpha = outsideBackdropAlpha.coerceIn(0f, 1f)
    val drawExterior = isLockCurtain && extAlpha > 0.001f
    val extLiftFrac = (1f - sheetEdgeY / max(screenH, 1f)).coerceIn(0f, 1f)
    val extFrost = if (!drawExterior || frostScale <= 0.001f) {
        0f
    } else {
        ((if (lockBlurActive) 0.85f else 0f) + extLiftFrac * 0.92f).coerceIn(0f, 0.94f)
    }
    val extDim = if (drawExterior) 0.24f * extLiftFrac else 0f

    fun sampleLockExteriorChannel(fx: Float, fy: Float, shift: Int, dimTarget: Float): Float {
        var v = sampleBilinearChannel(cPx, cW, cH, fx, fy, screenW, screenH, shift)
        if (extFrost > 0.004f) {
            val sv = sampleBilinearChannel(sPx, sW, sH, fx, fy, screenW, screenH, shift)
            v += (sv - v) * extFrost
        }
        if (extDim > 0.0005f) {
            v += (dimTarget - v) * extDim
        }
        return v.coerceIn(0f, 1f)
    }

    if (sheetEdgeY <= 0.5f) {
        if (drawExterior) {
            val aInt = (extAlpha * 255f).roundToInt().coerceIn(0, 255)
            for (iy in 0 until outH) {
                val fy = iy + 0.5f
                val rowOff = iy * outW
                for (ix in 0 until outW) {
                    val fx = ix + 0.5f
                    val r = (sampleLockExteriorChannel(fx, fy, 16, 0.016f) * 255f).roundToInt().coerceIn(0, 255)
                    val g = (sampleLockExteriorChannel(fx, fy, 8, 0.035f) * 255f).roundToInt().coerceIn(0, 255)
                    val b = (sampleLockExteriorChannel(fx, fy, 0, 0.078f) * 255f).roundToInt().coerceIn(0, 255)
                    outPx[rowOff + ix] = (aInt shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPx, 0, outW, 0, 0, outW, outH)
        }
    }

    val topExt = 64f * dens
    val bevelH = max(bevelHeightPx, 4f * dens)
    val velMag = abs(velocityNorm).coerceIn(0f, 1f)
    val effRefr = if (refractionScale <= 0.001f) {
        0f
    } else if (isLockCurtain) {
        max(refractionScale, 0.20f)
    } else {
        refractionScale
    }
    val rimPresence = ((screenH - sheetEdgeY) / max(12f * dens, 1f)).coerceIn(0f, 1f)
    val maxRow = if (drawExterior) outH else min(outH, ceil(sheetEdgeY + 2f).toInt().coerceAtLeast(0))

    val baseSoftBevel = if (frostScale <= 0.001f) {
        0f
    } else if (isLockCurtain) {
        if (lockBlurActive) 0.88f else 0f
    } else {
        (0.32f + 0.62f * frostScale).coerceIn(0f, 0.96f)
    }
    val baseSoftInterior = if (frostScale <= 0.001f) {
        0f
    } else if (isLockCurtain) {
        if (lockBlurActive) 0.92f else 0f
    } else {
        (0.32f + 0.62f * frostScale).coerceIn(0f, 0.96f)
    }

    for (iy in 0 until maxRow) {
        val fy = iy + 0.5f
        val rowOff = iy * outW
        val yNorm = (fy / max(screenH, 1f)).coerceIn(0f, 1f)
        val lipBand = smoothstepCpu(
            sheetEdgeY - max(cornerRadiusPx, 8f * dens) - bevelH * 1.35f,
            sheetEdgeY - bevelH * 0.85f,
            fy,
        )
        val edgePresence = rimPresence * lipBand
        for (ix in 0 until outW) {
            val fx = ix + 0.5f
            val d = sdSheetSlabCpu(fx, fy, screenW, sheetEdgeY, cornerRadiusPx, topExt)
            if (d > 0.75f) {
                if (drawExterior) {
                    val aInt = (extAlpha * 255f).roundToInt().coerceIn(0, 255)
                    val r = (sampleLockExteriorChannel(fx, fy, 16, 0.016f) * 255f).roundToInt().coerceIn(0, 255)
                    val g = (sampleLockExteriorChannel(fx, fy, 8, 0.035f) * 255f).roundToInt().coerceIn(0, 255)
                    val b = (sampleLockExteriorChannel(fx, fy, 0, 0.078f) * 255f).roundToInt().coerceIn(0, 255)
                    outPx[rowOff + ix] = (aInt shl 24) or (r shl 16) or (g shl 8) or b
                }
                continue
            }

            val slabAA = 1f - smoothstepCpu(-0.45f, 0.55f, d)
            val depthPx = max(-d, 0f)

            if (!isLockCurtain && isSolidMatte) {
                val rimStroke = 1f - smoothstepCpu(0.35f * dens, 1.25f * dens, depthPx)
                val blend = rimStroke * 0.1804f
                val mr = 0.0784f + (1f - 0.0784f) * blend
                val mg = 0.0902f + (1f - 0.0902f) * blend
                val mb = 0.1216f + (1f - 0.1216f) * blend
                val ma = ((0.9216f + (1f - 0.9216f) * blend) * slabAA).coerceIn(0f, 1f)
                outPx[rowOff + ix] = ((ma * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                    ((mr * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((mg * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (mb * 255f).roundToInt().coerceIn(0, 255)
                continue
            }

            var gr: Float
            var gg: Float
            var gb: Float
            var meniscus = 0f
            var nx = 0f
            var ny = 1f

            if (depthPx < bevelH) {
                val eps = 1.25f
                val dx = sdSheetSlabCpu(fx + eps, fy, screenW, sheetEdgeY, cornerRadiusPx, topExt) -
                    sdSheetSlabCpu(fx - eps, fy, screenW, sheetEdgeY, cornerRadiusPx, topExt)
                val dy = sdSheetSlabCpu(fx, fy + eps, screenW, sheetEdgeY, cornerRadiusPx, topExt) -
                    sdSheetSlabCpu(fx, fy - eps, screenW, sheetEdgeY, cornerRadiusPx, topExt) + 1e-4f
                val nLen = max(sqrt(dx * dx + dy * dy), 1e-5f)
                nx = dx / nLen
                ny = dy / nLen

                val edge = (1f - depthPx / bevelH).coerceIn(0f, 1f)
                meniscus = (1f - sqrt(max(1f - edge * edge, 0f))) * edgePresence

                val pullMag = (24f * dens) * effRefr * (1f + 0.30f * velMag) * meniscus
                val velShearY = -velocityNorm * (5.5f * dens) * meniscus * effRefr
                val baseX = fx - nx * pullMag
                val baseY = fy - ny * pullMag + velShearY
                val disp = (2.6f * dens) * meniscus * (effRefr * 1.35f).coerceIn(0f, 1.2f)

                val pRx = baseX + nx * disp
                val pRy = baseY + ny * disp
                val pBx = baseX - nx * disp
                val pBy = baseY - ny * disp

                val cR = sampleBilinearChannel(cPx, cW, cH, pRx, pRy, screenW, screenH, 16)
                val cG = sampleBilinearChannel(cPx, cW, cH, baseX, baseY, screenW, screenH, 8)
                val cB = sampleBilinearChannel(cPx, cW, cH, pBx, pBy, screenW, screenH, 0)

                val localFrost = baseSoftBevel * (1f - 0.72f * meniscus)
                if (localFrost > 0.004f) {
                    val sR = sampleBilinearChannel(sPx, sW, sH, pRx, pRy, screenW, screenH, 16)
                    val sG = sampleBilinearChannel(sPx, sW, sH, baseX, baseY, screenW, screenH, 8)
                    val sB = sampleBilinearChannel(sPx, sW, sH, pBx, pBy, screenW, screenH, 0)
                    gr = cR + (sR - cR) * localFrost
                    gg = cG + (sG - cG) * localFrost
                    gb = cB + (sB - cB) * localFrost
                } else {
                    gr = cR; gg = cG; gb = cB
                }
            } else {
                val cR = sampleBilinearChannel(cPx, cW, cH, fx, fy, screenW, screenH, 16)
                val cG = sampleBilinearChannel(cPx, cW, cH, fx, fy, screenW, screenH, 8)
                val cB = sampleBilinearChannel(cPx, cW, cH, fx, fy, screenW, screenH, 0)
                if (baseSoftInterior > 0.004f) {
                    val sR = sampleBilinearChannel(sPx, sW, sH, fx, fy, screenW, screenH, 16)
                    val sG = sampleBilinearChannel(sPx, sW, sH, fx, fy, screenW, screenH, 8)
                    val sB = sampleBilinearChannel(sPx, sW, sH, fx, fy, screenW, screenH, 0)
                    gr = cR + (sR - cR) * baseSoftInterior
                    gg = cG + (sG - cG) * baseSoftInterior
                    gb = cB + (sB - cB) * baseSoftInterior
                } else {
                    gr = cR; gg = cG; gb = cB
                }
            }

            if (isLockCurtain) {
                val topVig = (1f - smoothstepCpu(0f, 0.26f, yNorm)) * (if (lockBlurActive) 0.34f else 0.16f)
                val botVig = smoothstepCpu(0.68f, 1f, yNorm) * (if (lockBlurActive) 0.44f else 0.26f) * (1f - 0.65f * meniscus)
                val vig = (topVig + botVig).coerceIn(0f, 0.60f)
                gr += (0.016f - gr) * vig
                gg += (0.035f - gg) * vig
                gb += (0.078f - gb) * vig
            } else {
                val veilAmt = veilAlpha.coerceIn(0f, 0.75f) * (1f - 0.48f * meniscus)
                gr += (vr - gr) * veilAmt
                gg += (vg - gg) * veilAmt
                gb += (vb - gb) * veilAmt
            }

            if (depthPx < bevelH && edgePresence > 0.002f) {
                val causArg = (depthPx - 5.2f * dens) / max(3.6f * dens, 0.8f)
                val causGlow = exp(-(causArg * causArg)) * (0.085f + 0.12f * effRefr.coerceIn(0f, 1.2f)) * edgePresence
                val lightDot = (-0.5592f * nx - 0.8290f * ny).coerceIn(0f, 1f)
                val oppDot = (0.5592f * nx + 0.8290f * ny).coerceIn(0f, 1f)
                val rimArg = (depthPx - 0.85f * dens) / max(0.82f * dens, 0.35f)
                val rimLine = exp(-(rimArg * rimArg))
                val rimSpec = rimLine * (
                    0.14f +
                        0.30f * lightDot.toDouble().pow(1.6).toFloat() +
                        0.22f * oppDot.toDouble().pow(1.8).toFloat()
                    ) * edgePresence
                val boost = causGlow + rimSpec
                gr = (gr + boost).coerceIn(0f, 1f)
                gg = (gg + boost).coerceIn(0f, 1f)
                gb = (gb + boost).coerceIn(0f, 1f)
            }

            if (drawExterior && slabAA < 0.999f) {
                val eR = sampleLockExteriorChannel(fx, fy, 16, 0.016f)
                val eG = sampleLockExteriorChannel(fx, fy, 8, 0.035f)
                val eB = sampleLockExteriorChannel(fx, fy, 0, 0.078f)
                val remA = extAlpha * (1f - slabAA)
                val totA = (slabAA + remA).coerceIn(0f, 1f)
                val invA = if (totA > 1e-4f) 1f / totA else 0f
                val fR = ((gr * slabAA + eR * remA) * invA).coerceIn(0f, 1f)
                val fG = ((gg * slabAA + eG * remA) * invA).coerceIn(0f, 1f)
                val fB = ((gb * slabAA + eB * remA) * invA).coerceIn(0f, 1f)
                outPx[rowOff + ix] = ((totA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                    ((fR * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((fG * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (fB * 255f).roundToInt().coerceIn(0, 255)
            } else {
                val outA = slabAA.coerceIn(0f, 1f)
                outPx[rowOff + ix] = ((outA * 255f).roundToInt().coerceIn(0, 255) shl 24) or
                    ((gr.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    ((gg.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    (gb.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }

    return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
        it.setPixels(outPx, 0, outW, 0, 0, outW, outH)
    }
}
