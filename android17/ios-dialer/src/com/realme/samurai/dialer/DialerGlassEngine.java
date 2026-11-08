/*
 * Clean-Room 90Hz AGSL Optical Liquid Glass Engine for Samurai Android 17 Phone (`com.realme.samurai.dialer`).
 *
 * Open-Source Shader Math Attributions & Licenses:
 * 1. Spherical meniscus `circleMap(x) = 1.0 - sqrt(1.0 - x*x)` bevel refraction profile &
 *    7-band ROYGCBP spectral chromatic dispersion adapted from `Kyant0/AndroidLiquidGlass`
 *    (Copyright 2025 Kyant, Apache License, Version 2.0).
 * 2. Gaussian touch-bulge lens magnification and highlight-protected Apple vibrancy adapted from
 *    `QWEA0/Liquid-Glass-Android` (Copyright 2025-2026 pandadog, MIT License).
 * 3. Analytic Adreno-640-safe G2 superellipse SDF (`n = 4.6`), double-meniscus Schlick Fresnel
 *    directional catchlights, colored Liquid Glass lens body, and neighbor-key caustic light
 *    coupling tailored for Realme X2 Pro (`RMX1931`).
 */
package com.realme.samurai.dialer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ListView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DialerGlassEngine {

    public static final int MODE_CLEAR_REFRACTIVE = 0;
    public static final int MODE_FROSTED_TINTED = 1;
    public static final int MODE_SOLID_MATTE = 2;

    public static final int THEME_LIGHT = 0;
    public static final int THEME_DARK = 1;
    public static final int THEME_INCALL_AURORA = 2;

    // Canonical iOS Styler / Launcher Settings Keys & Defaults (20% Refract, 20% Frost, 70% WP Depth)
    public static final String KEY_GLOBAL_GLASS_MODE = "samurai_liquid_glass_mode";
    public static final String KEY_SECURE_GLASS_MODE = "samurai_ios26_glass_mode";
    public static final String KEY_REFRACTION_PCT = "samurai_ios26_refraction_pct";
    public static final String KEY_FROST_PCT = "samurai_ios26_frost_pct";
    public static final String KEY_WP_BRIGHTNESS_PCT = "samurai_ios26_wp_brightness_pct";
    private static final String GLASS_PROP_PATH = "/data/ota_package/hw/glass.prop";

    public static final int DEFAULT_REFRACTION_PCT = 20;
    public static final int DEFAULT_FROST_PCT = 20;
    public static final int DEFAULT_WP_BRIGHTNESS_PCT = 70;

    // Canonical 90Hz Hooke Spring Constants for 100% Liquid Glass Animation Consistency
    public static final float PRESS_STIFFNESS = 500f;
    public static final float PRESS_DAMPING = 32f;
    public static final float MORPH_STIFFNESS = 440f;
    public static final float MORPH_DAMPING = 31f;

    // Authentic Apple iOS 26.1 Semantic Palette
    public static final int IOS_BLUE_LIGHT = 0xFF007AFF;
    public static final int IOS_BLUE_DARK = 0xFF0A84FF;
    public static final int IOS_GREEN = 0xFF34C759;
    public static final int IOS_RED = 0xFFFF3B30;
    public static final int IOS_ORANGE = 0xFFFF9500;
    public static final int LIGHT_BG = 0xFFF2F2F7;
    public static final int DARK_BG = 0xFF000000;

    public interface SettingsChangeListener {
        void onGlassSettingsChanged();
    }

    /**
     * Reusable Hooke's-law spring integrator ensuring identical 90Hz Liquid Glass feel
     * across Number Pad keys, Call/Backspace lenses, Small UI pills, Tab Droplet, and In-Call orbs.
     */
    public static final class SpringFloat {
        public float value;
        public float velocity;
        public float target;
        private final float mStiffness;
        private final float mDamping;
        private final float mMinClamp;
        private final float mMaxClamp;

        public SpringFloat(float initial, float stiffness, float damping, float minClamp, float maxClamp) {
            this.value = initial;
            this.target = initial;
            this.mStiffness = stiffness;
            this.mDamping = damping;
            this.mMinClamp = minClamp;
            this.mMaxClamp = maxClamp;
        }

        public static SpringFloat pressSpring() {
            return new SpringFloat(0f, PRESS_STIFFNESS, PRESS_DAMPING, 0f, 1.18f);
        }

        public static SpringFloat morphSpring(float initial, float min, float max) {
            return new SpringFloat(initial, MORPH_STIFFNESS, MORPH_DAMPING, min, max);
        }

        public void snapTo(float v) {
            this.value = v;
            this.target = v;
            this.velocity = 0f;
        }

        public boolean step(float dt) {
            float force = mStiffness * (target - value) - mDamping * velocity;
            velocity += force * dt;
            value = clampF(value + velocity * dt, mMinClamp, mMaxClamp);
            if (Math.abs(target - value) < 0.0025f && Math.abs(velocity) < 0.014f) {
                value = target;
                velocity = 0f;
                return false;
            }
            return true;
        }
    }

    private static final String SURFACE_AGSL = ""
            + "uniform shader uSharpBg;\n"
            + "uniform shader uSoftBg;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uSharpBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float  uRadius;\n"
            + "uniform float  uDensity;\n"
            + "uniform float  uGlassMode;\n"      // 0=Clear, 1=Frosted Tinted, 2=Solid Matte
            + "uniform float  uRefractScale;\n"   // 0.0 .. 1.5 (strictly 0.0 at 0%)
            + "uniform float  uFrostScale;\n"     // 0.0 .. 1.5 (strictly 0.0 at 0%)
            + "uniform float  uDarkTheme;\n"      // 1.0 = Dark/InCall, 0.0 = Light
            + "uniform float  uInCallMode;\n"     // 1.0 = InCall translucent button mode
            + "uniform float2 uTouchPos;\n"
            + "uniform float  uTouchAmp;\n"       // 0.0 .. 1.15 spring touch bulge
            + "uniform float  uPhase;\n"
            + "uniform float  uTintBoost;\n"
            + "uniform float3 uTintColor;\n"      // Colored Liquid Glass lens RGB (0..1)
            + "uniform float  uTintMix;\n"        // 0.0 = neutral crystal, >0 = colored glass lens
            + "uniform float2 uNeighborDir;\n"    // Unit vector toward pressed neighbor key
            + "uniform float  uNeighborGlow;\n"   // 0.0 .. 1.0 neighbor caustic coupling
            + "uniform float  uIsLiveCapture;\n"  // 1.0 = exact 1:1 live scene buffer, 0.0 = studio bleed
            + "\n"
            + "float sdShape(float2 p, float2 halfSize, float r) {\n"
            + "    float minH = min(halfSize.x, halfSize.y);\n"
            + "    if (r >= minH * 0.94) {\n"
            + "        float2 qPill = abs(p) - (halfSize - float2(minH));\n"
            + "        return length(max(qPill, float2(0.0))) + min(max(qPill.x, qPill.y), 0.0) - minH;\n"
            + "    }\n"
            + "    float cr = clamp(r, 1.5, max(minH, 1.5));\n"
            + "    float2 q = abs(p) - (halfSize - float2(cr));\n"
            + "    float inside = min(max(q.x, q.y), 0.0);\n"
            + "    if (q.x <= 0.0 && q.y <= 0.0) {\n"
            + "        return inside - cr;\n"
            + "    }\n"
            + "    float2 mq = max(q, float2(0.001));\n"
            + "    float corner = pow(pow(mq.x, 4.6) + pow(mq.y, 4.6), 0.2173913);\n"
            + "    return corner + inside - cr;\n"
            + "}\n"
            + "\n"
            + "float sq(float v) {\n"
            + "    return v * v;\n"
            + "}\n"
            + "\n"
            + "float safePow(float x, float p) {\n"
            + "    return x > 0.0001 ? pow(max(x, 0.0001), p) : 0.0;\n"
            + "}\n"
            + "\n"
            + "float circleMap(float x) {\n"
            + "    float cx = clamp(x, 0.0, 1.0);\n"
            + "    return 1.0 - sqrt(max(1.0 - cx * cx, 0.0));\n"
            + "}\n"
            + "\n"
            + "float2 toBgCoord(float2 screenPx, float2 bufSize) {\n"
            + "    if (uIsLiveCapture > 0.5) {\n"
            + "        return clamp(screenPx / max(uScreenSize, float2(2.0)), float2(0.0), float2(1.0)) * bufSize;\n"
            + "    }\n"
            + "    float bleed = 8.0 * uDensity;\n"
            + "    float dx = sin(uPhase * 0.65) * 4.0 * uDensity * uRefractScale;\n"
            + "    float dy = cos(uPhase * 0.48) * 3.5 * uDensity * uRefractScale;\n"
            + "    float waveX = sin(uPhase * 0.65 + screenPx.y * 0.0024) * 1.8 * uDensity * uRefractScale;\n"
            + "    float waveY = cos(uPhase * 0.48 + screenPx.x * 0.0024) * 1.5 * uDensity * uRefractScale;\n"
            + "    float2 span = max(uScreenSize + float2(bleed * 2.0), float2(2.0));\n"
            + "    float2 uv = (screenPx + float2(waveX, waveY) - float2(-bleed + dx, -bleed + dy)) / span;\n"
            + "    return clamp(uv, float2(0.0), float2(1.0)) * bufSize;\n"
            + "}\n"
            + "\n"
            + "half3 applyVibrancy(half3 c, half satFactor) {\n"
            + "    half lum = dot(c, half3(0.2126, 0.7152, 0.0722));\n"
            + "    half satNow = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));\n"
            + "    half room = 1.0 - smoothstep(half(0.20), half(0.85), satNow);\n"
            + "    half hl = 1.0 - smoothstep(half(0.75), half(0.98), lum);\n"
            + "    half amt = half(1.0) + (satFactor - half(1.0)) * mix(half(0.34), half(1.0), room * hl);\n"
            + "    return clamp(mix(half3(lum), c, amt), half3(0.0), half3(1.0));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 halfSize = uSize * 0.5;\n"
            + "    float2 rel = fragCoord - halfSize;\n"
            + "    float d = sdShape(rel, halfSize, uRadius);\n"
            + "    float aa = 1.0 - smoothstep(-0.85, 0.75, d);\n"
            + "    if (aa <= 0.002) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "\n"
            + "    float eps = 1.15;\n"
            + "    float dx = sdShape(rel + float2(eps, 0.0), halfSize, uRadius) - sdShape(rel - float2(eps, 0.0), halfSize, uRadius);\n"
            + "    float dy = sdShape(rel + float2(0.0, eps), halfSize, uRadius) - sdShape(rel - float2(0.0, eps), halfSize, uRadius);\n"
            + "    float2 grad = float2(dx, dy) / max(length(float2(dx, dy)), 0.0001);\n"
            + "\n"
            + "    float bevelH = clamp(min(halfSize.x, halfSize.y) * 0.36, 7.5 * uDensity, 24.0 * uDensity);\n"
            + "    float edgeFrac = 1.0 - clamp(-d / max(bevelH, 1.0), 0.0, 1.0);\n"
            + "    float meniscus = circleMap(edgeFrac);\n"
            + "\n"
            + "    if (uGlassMode > 1.5) {\n"
            + "        float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n"
            + "        half3 matte = (uDarkTheme > 0.5)\n"
            + "            ? mix(half3(0.15, 0.16, 0.20), half3(0.10, 0.11, 0.14), half(yNorm))\n"
            + "            : mix(half3(0.99, 0.995, 1.00), half3(0.92, 0.94, 0.97), half(yNorm));\n"
            + "        if (uTintMix > 0.01) {\n"
            + "            matte = mix(matte, half3(uTintColor), half(clamp(uTintMix, 0.0, 1.0)));\n"
            + "        }\n"
            + "        matte = clamp(matte + half3(uTintBoost * 0.12), half3(0.0), half3(1.0));\n"
            + "        float ndotlM = dot(grad, float2(-0.5592, -0.8290));\n"
            + "        float rimM = exp(-sq((d + 1.0 * uDensity) / (1.2 * uDensity))) * (0.12 + 0.30 * max(ndotlM, 0.0));\n"
            + "        matte = clamp(matte + half3(rimM), half3(0.0), half3(1.0));\n"
            + "        return half4(matte * half(aa), half(aa));\n"
            + "    }\n"
            + "\n"
            + "    // Calibrated so default 20% refraction bends crisp curved bevels while 0% is strictly 0.0px\n"
            + "    float modeRefractMul = (uGlassMode > 0.5) ? 0.55 : 1.0;\n"
            + "    float refractPx = (34.0 * uDensity) * uRefractScale * modeRefractMul;\n"
            + "    float2 domeDir = rel / max(halfSize, float2(10.0));\n"
            + "    float2 offset = (-grad * meniscus * refractPx) - (domeDir * 3.0 * uDensity * uRefractScale);\n"
            + "\n"
            + "    if (uTouchAmp > 0.005) {\n"
            + "        float2 tp = fragCoord - uTouchPos;\n"
            + "        float tr = length(tp);\n"
            + "        float sigma = 60.0 * uDensity;\n"
            + "        float bump = uTouchAmp * exp(-(tr * tr) / max(sigma * sigma, 1.0));\n"
            + "        if (tr > 1.0) {\n"
            + "            offset -= (tp / tr) * (bump * 20.0 * uDensity * uRefractScale);\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    float2 baseScreen = uScreenOrigin + fragCoord + offset;\n"
            + "    float dispPx = (4.2 * uDensity) * meniscus * uRefractScale * ((uGlassMode > 0.5) ? 0.45 : 1.0);\n"
            + "    float2 dispVec = grad * dispPx;\n"
            + "\n"
            + "    half3 sharpSpec;\n"
            + "    if (dispPx > 0.05) {\n"
            + "        // 5-tap spectral dispersion across curved meniscus (<= 5 sharp + <= 1 soft = <= 6 eval() fetches)\n"
            + "        sharpSpec = half3(0.0);\n"
            + "        sharpSpec.r += uSharpBg.eval(toBgCoord(baseScreen + dispVec, uSharpBufSize)).r * half(0.45);\n"
            + "        half3 cWarm = uSharpBg.eval(toBgCoord(baseScreen + dispVec * 0.45, uSharpBufSize)).rgb;\n"
            + "        sharpSpec.r += cWarm.r * half(0.40);\n"
            + "        sharpSpec.g += cWarm.g * half(0.25);\n"
            + "        half3 cMid = uSharpBg.eval(toBgCoord(baseScreen, uSharpBufSize)).rgb;\n"
            + "        sharpSpec.r += cMid.r * half(0.15);\n"
            + "        sharpSpec.g += cMid.g * half(0.50);\n"
            + "        sharpSpec.b += cMid.b * half(0.15);\n"
            + "        half3 cCool = uSharpBg.eval(toBgCoord(baseScreen - dispVec * 0.45, uSharpBufSize)).rgb;\n"
            + "        sharpSpec.g += cCool.g * half(0.25);\n"
            + "        sharpSpec.b += cCool.b * half(0.40);\n"
            + "        sharpSpec.b += uSharpBg.eval(toBgCoord(baseScreen - dispVec, uSharpBufSize)).b * half(0.45);\n"
            + "    } else {\n"
            + "        sharpSpec = uSharpBg.eval(toBgCoord(baseScreen, uSharpBufSize)).rgb;\n"
            + "    }\n"
            + "\n"
            + "    // Strictly 0.0 blur mix at Frost=0%, rich frosted diffusion as Frost increases\n"
            + "    float frostFactor = clamp(uFrostScale, 0.0, 1.35);\n"
            + "    float baseFrost = (uGlassMode > 0.5) ? 0.92 : 0.66;\n"
            + "    float frostMix = clamp(baseFrost * uFrostScale, 0.0, 1.0) * (1.0 - 0.48 * meniscus);\n"
            + "    half3 glass = (frostMix > 0.002)\n"
            + "            ? mix(sharpSpec, uSoftBg.eval(toBgCoord(baseScreen, uSoftBufSize)).rgb, half(frostMix))\n"
            + "            : sharpSpec;\n"
            + "    float satAmt = 1.0 + 0.18 * clamp(max(uFrostScale, uRefractScale), 0.0, 1.0);\n"
            + "    glass = applyVibrancy(glass, half(satAmt));\n"
            + "\n"
            + "    // Intrinsic iOS 26.1 optical crystal body + Frost veil\n"
            + "    float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n"
            + "    half3 veilCol;\n"
            + "    float crystalBody;\n"
            + "    float frostVeil;\n"
            + "    if (uInCallMode > 0.5) {\n"
            + "        veilCol = half3(0.84, 0.90, 1.00);\n"
            + "        crystalBody = 0.08;\n"
            + "        frostVeil = (uGlassMode > 0.5) ? 0.24 : 0.14;\n"
            + "    } else if (uDarkTheme > 0.5) {\n"
            + "        veilCol = mix(half3(0.16, 0.18, 0.22), half3(0.10, 0.11, 0.14), half(yNorm));\n"
            + "        crystalBody = 0.18;\n"
            + "        frostVeil = (uGlassMode > 0.5) ? 0.36 : 0.20;\n"
            + "    } else {\n"
            + "        veilCol = mix(half3(1.00, 1.00, 1.00), half3(0.955, 0.968, 0.992), half(yNorm));\n"
            + "        crystalBody = 0.22;\n"
            + "        frostVeil = (uGlassMode > 0.5) ? 0.34 : 0.18;\n"
            + "    }\n"
            + "    float boostVeil = max(uTintBoost, 0.0) * (0.14 + 0.14 * clamp(uFrostScale, 0.0, 1.0));\n"
            + "    float veilAlpha = clamp(crystalBody + frostVeil * frostFactor + boostVeil, 0.0, 0.92);\n"
            + "    glass = mix(glass, veilCol, half(veilAlpha));\n"
            + "    if (uTintBoost < -0.001) {\n"
            + "        glass *= half(1.0 + uTintBoost * 0.36);\n"
            + "    }\n"
            + "\n"
            + "    // Colored Liquid Glass Lens (Emerald Call, Crimson End, Cobalt Selection)\n"
            + "    if (uTintMix > 0.005) {\n"
            + "        half3 tc = half3(uTintColor);\n"
            + "        half3 topLit = clamp(tc * half(1.16) + half3(0.06), half3(0.0), half3(1.0));\n"
            + "        half3 botDeep = tc * half(0.86);\n"
            + "        half3 lensCol = mix(topLit, botDeep, half(yNorm));\n"
            + "        glass = mix(glass, lensCol, half(clamp(uTintMix, 0.0, 0.94)));\n"
            + "    }\n"
            + "\n"
            + "    // Convex top dome sheen + double-meniscus inner counter-caustic ring\n"
            + "    float topSheen = (1.0 - yNorm) * (0.045 + 0.035 * frostFactor + 0.055 * max(uTintBoost, 0.0));\n"
            + "    glass = clamp(glass + half3(topSheen), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    float2 lightTL = float2(-0.5592, -0.8290);\n"
            + "    float ndotl = dot(grad, lightTL);\n"
            + "    float causticOuter = exp(-sq((d + bevelH * 0.34) / max(bevelH * 0.24, 2.0)));\n"
            + "    float causticInner = exp(-sq((d + bevelH * 0.72) / max(bevelH * 0.30, 2.5)));\n"
            + "    float cScale = 0.28 + 0.52 * clamp(uRefractScale, 0.0, 1.5);\n"
            + "    float causticStr = (0.042 * causticOuter * (0.30 + 0.70 * max(ndotl, 0.0) + 0.35 * max(-ndotl, 0.0))\n"
            + "                      + 0.014 * causticInner * (0.35 + 0.65 * max(-ndotl, 0.0))) * cScale;\n"
            + "    glass = clamp(glass + half3(causticStr), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    // Radial liquid touch bloom wave\n"
            + "    if (uTouchAmp > 0.005) {\n"
            + "        float tr = length(fragCoord - uTouchPos);\n"
            + "        float radialGlow = uTouchAmp * 0.14 * exp(-(tr * tr) / max(sq(66.0 * uDensity), 1.0));\n"
            + "        glass = clamp(glass + half3(radialGlow * 0.96, radialGlow * 0.98, radialGlow), half3(0.0), half3(1.0));\n"
            + "    }\n"
            + "\n"
            + "    // Neighbor-key caustic light coupling along facing perimeter\n"
            + "    float neighborRim = 0.0;\n"
            + "    if (uNeighborGlow > 0.004) {\n"
            + "        float faceDot = max(dot(grad, uNeighborDir), 0.0);\n"
            + "        neighborRim = uNeighborGlow * safePow(faceDot, 1.75) * (0.28 * meniscus + 0.42 * exp(-sq((d + 1.4 * uDensity) / (2.0 * uDensity))));\n"
            + "    }\n"
            + "\n"
            + "    // Subtle 34-deg Schlick Fresnel perimeter catchlight (TL primary + BR bounce)\n"
            + "    float rimLine = exp(-sq((d + 0.85 * uDensity) / (0.95 * uDensity)));\n"
            + "    float specTL = safePow(ndotl, 1.48);\n"
            + "    float specBR = safePow(-ndotl, 2.10);\n"
            + "    float rimScale = (uDarkTheme > 0.5) ? 0.52 : 0.46;\n"
            + "    float rimGlow = rimLine * rimScale * (0.12 + 0.55 * specTL + 0.28 * specBR + 0.12 * max(uTintBoost, 0.0) + 0.18 * uTouchAmp) + neighborRim * 0.28;\n"
            + "    glass = clamp(glass + half3(rimGlow), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    return half4(glass * half(aa), half(aa));\n"
            + "}\n";

    public static final class BackdropBuffers {
        public final Bitmap sharpBitmap;
        public final Bitmap softBitmap;
        public final BitmapShader sharpShader;
        public final BitmapShader softShader;
        public final int sharpW;
        public final int sharpH;
        public final int softW;
        public final int softH;

        BackdropBuffers(Bitmap sharp, Bitmap soft) {
            this.sharpBitmap = sharp;
            this.softBitmap = soft;
            this.sharpW = sharp.getWidth();
            this.sharpH = sharp.getHeight();
            this.softW = soft.getWidth();
            this.softH = soft.getHeight();
            this.sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    public static final class AnchorListScrollTracker {
        private int mPrevFirstPos = -1;
        private int[] mPrevChildTops = new int[32];
        private int mPrevChildCount = 0;
        private int mCumulativeScrollYPx = 0;
        private final SparseIntArray mRowHeights = new SparseIntArray(128);

        public int onListScrolled(ListView listView) {
            if (listView == null || listView.getChildCount() == 0) {
                reset();
                return 0;
            }
            int firstPos = listView.getFirstVisiblePosition();
            int count = listView.getChildCount();
            if (firstPos == 0) {
                View firstChild = listView.getChildAt(0);
                mCumulativeScrollYPx = (firstChild != null)
                        ? Math.max(0, listView.getPaddingTop() - firstChild.getTop())
                        : 0;
                recordSnapshot(listView, firstPos, count);
                return mCumulativeScrollYPx;
            }
            if (mPrevFirstPos >= 0 && mPrevChildCount > 0) {
                boolean matched = false;
                int deltaY = 0;
                for (int i = 0; i < count; i++) {
                    int adapterPos = firstPos + i;
                    int prevIdx = adapterPos - mPrevFirstPos;
                    if (prevIdx >= 0 && prevIdx < mPrevChildCount) {
                        View child = listView.getChildAt(i);
                        if (child != null) {
                            deltaY = mPrevChildTops[prevIdx] - child.getTop();
                            matched = true;
                            break;
                        }
                    }
                }
                if (!matched) {
                    View first = listView.getChildAt(0);
                    int approxH = (first != null && first.getHeight() > 0) ? first.getHeight() : 140;
                    int curPartial = (first != null) ? (listView.getPaddingTop() - first.getTop()) : 0;
                    int prevPartial = listView.getPaddingTop() - mPrevChildTops[0];
                    int sumH = 0;
                    if (firstPos > mPrevFirstPos) {
                        for (int p = mPrevFirstPos; p < firstPos; p++) {
                            sumH += mRowHeights.get(p, approxH);
                        }
                        deltaY = sumH + curPartial - prevPartial;
                    } else {
                        for (int p = firstPos; p < mPrevFirstPos; p++) {
                            sumH -= mRowHeights.get(p, approxH);
                        }
                        deltaY = sumH + curPartial - prevPartial;
                    }
                }
                mCumulativeScrollYPx = Math.max(firstPos, mCumulativeScrollYPx + deltaY);
            } else {
                View first = listView.getChildAt(0);
                int approxH = (first != null && first.getHeight() > 0) ? first.getHeight() : 140;
                int partial = (first != null) ? Math.max(0, listView.getPaddingTop() - first.getTop()) : 0;
                int sumH = 0;
                for (int p = 0; p < firstPos; p++) {
                    sumH += mRowHeights.get(p, approxH);
                }
                mCumulativeScrollYPx = Math.max(firstPos, sumH + partial);
            }
            recordSnapshot(listView, firstPos, count);
            return mCumulativeScrollYPx;
        }

        public int getScrollYPx() {
            return mCumulativeScrollYPx;
        }

        public void reset() {
            mPrevFirstPos = -1;
            mPrevChildCount = 0;
            mCumulativeScrollYPx = 0;
            mRowHeights.clear();
        }

        private void recordSnapshot(ListView listView, int firstPos, int count) {
            if (count > mPrevChildTops.length) {
                mPrevChildTops = new int[Math.max(count, mPrevChildTops.length * 2)];
            }
            for (int i = 0; i < count; i++) {
                View c = listView.getChildAt(i);
                if (c != null) {
                    mPrevChildTops[i] = c.getTop();
                    int h = c.getHeight();
                    if (h > 0) {
                        mRowHeights.put(firstPos + i, h);
                    }
                } else {
                    mPrevChildTops[i] = 0;
                }
            }
            mPrevFirstPos = firstPos;
            mPrevChildCount = count;
        }
    }

    public static final class LiveOpticalState {
        private static final int SOFT_BLUR_RADIUS = 7;
        private static final ExecutorService sBlurExecutor =
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "DialerGlassBlur");
                    t.setDaemon(true);
                    return t;
                });

        private final Context mAppCtx;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final CopyOnWriteArrayList<SettingsChangeListener> mListeners =
                new CopyOnWriteArrayList<>();
        private final ContentObserver mObserver;
        private boolean mRegistered = false;

        public volatile int glassMode = MODE_CLEAR_REFRACTIVE;
        public volatile float refractScale = DEFAULT_REFRACTION_PCT / 100.0f;
        public volatile float frostScale = DEFAULT_FROST_PCT / 100.0f;
        public volatile float wpBrightnessScale = DEFAULT_WP_BRIGHTNESS_PCT / 100.0f;
        public volatile float phase = 0.0f;
        public volatile int screenW = 1080;
        public volatile int screenH = 2400;
        public volatile boolean darkTheme = false;
        public volatile boolean inCallMode = false;
        public volatile BackdropBuffers activeBackdrop;

        // Reusable double-buffered live-scene capture buffers (zero GC churn & zero RenderThread tearing)
        private Bitmap mLiveSharpBmpA;
        private Bitmap mLiveSharpBmpB;
        private boolean mFrontIsSharpA = true;
        private Bitmap mLiveDownsampleBmp;
        private Bitmap mLiveSoftBmpA;
        private Bitmap mLiveSoftBmpB;
        private boolean mFrontIsSoftA = true;
        private Canvas mLiveSharpCanvasA;
        private Canvas mLiveSharpCanvasB;
        private Canvas mLiveDownsampleCanvas;
        private Canvas mLiveSoftSeedCanvasA;
        private Canvas mLiveSoftSeedCanvasB;
        private BackdropBuffers mLiveBuffersAA;
        private BackdropBuffers mLiveBuffersAB;
        private BackdropBuffers mLiveBuffersBA;
        private BackdropBuffers mLiveBuffersBB;
        private final android.graphics.Rect mSharpDstRect = new android.graphics.Rect(0, 0, 216, 480);
        private final android.graphics.Rect mSoftDstRect = new android.graphics.Rect(0, 0, 108, 240);
        private static final android.graphics.Rect sCallerDstRect = new android.graphics.Rect(0, 0, 270, 600);
        private static Bitmap sCallerStageBmp;
        private static Canvas sCallerStageCanvas;
        private static int[] sCallerStagePx;
        private static final Paint sCallerScalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int[] mCaptureLoc = new int[2];
        volatile BackdropBuffers mLiveSceneBuffers;
        private int[] mBlurWorkA;
        private int[] mBlurWorkB;
        private boolean mBlurInFlight = false;
        private boolean mBlurPending = false;
        private int mCaptureEpoch = 0;
        private int mCallerPhotoEpoch = 0;
        private volatile WeakReference<View> mCapturedRootView;
        volatile int mCapturedScrollYPx = 0;
        volatile int mCurrentScrollYPx = 0;
        volatile int mScrollViewportTopPx = 0;
        private Runnable mSoftBlurReadyCallback;
        private final Paint mLiveBlendPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private boolean mCapturingScene = false;
        private long mLastCaptureUptimeMs = 0L;

        private static BackdropBuffers sLightBackdrop;
        private static BackdropBuffers sDarkBackdrop;
        private static BackdropBuffers sAuroraBackdrop;

        public LiveOpticalState(Context context, boolean forInCall) {
            mAppCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            inCallMode = forInCall;
            refreshScreenDimensions(context);
            updateThemeFromConfig(context.getResources().getConfiguration());
            reloadSettings();

            mObserver = new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    reloadSettings();
                    for (SettingsChangeListener l : mListeners) {
                        l.onGlassSettingsChanged();
                    }
                }
            };
        }

        private void refreshScreenDimensions(Context ctx) {
            Context c = ctx != null ? ctx : mAppCtx;
            int w = 0;
            int h = 0;
            try {
                WindowManager wm = c.getSystemService(WindowManager.class);
                if (wm != null) {
                    android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                    w = bounds.width();
                    h = bounds.height();
                }
            } catch (Throwable ignored) {
            }
            if (w < 320 || h < 320) {
                DisplayMetrics dm = c.getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            screenW = Math.max(320, w);
            screenH = Math.max(320, h);
        }

        public Context getAppContext() {
            return mAppCtx;
        }

        public void setSoftBlurReadyCallback(Runnable callback) {
            mSoftBlurReadyCallback = callback;
        }

        public View getCapturedRootView() {
            WeakReference<View> ref = mCapturedRootView;
            return ref != null ? ref.get() : null;
        }

        public void updateLiveScroll(int scrollYPx, int viewportTopPx) {
            mCurrentScrollYPx = scrollYPx;
            mScrollViewportTopPx = viewportTopPx;
        }

        public void resetLiveScroll() {
            mCurrentScrollYPx = 0;
            mCapturedScrollYPx = 0;
            mScrollViewportTopPx = 0;
        }

        public void register(SettingsChangeListener listener) {
            if (listener != null && !mListeners.contains(listener)) {
                mListeners.add(listener);
            }
            if (!mRegistered) {
                try {
                    ContentResolver cr = mAppCtx.getContentResolver();
                    String[] keys = {
                            KEY_GLOBAL_GLASS_MODE,
                            KEY_SECURE_GLASS_MODE,
                            KEY_REFRACTION_PCT,
                            KEY_FROST_PCT,
                            KEY_WP_BRIGHTNESS_PCT
                    };
                    for (String k : keys) {
                        cr.registerContentObserver(Settings.Global.getUriFor(k), false, mObserver);
                        cr.registerContentObserver(Settings.Secure.getUriFor(k), false, mObserver);
                    }
                    mRegistered = true;
                } catch (Throwable ignored) {
                }
            }
            reloadSettings();
        }

        public void unregister(SettingsChangeListener listener) {
            if (listener != null) {
                mListeners.remove(listener);
            }
            if (mListeners.isEmpty() && mRegistered) {
                try {
                    mAppCtx.getContentResolver().unregisterContentObserver(mObserver);
                } catch (Throwable ignored) {
                }
                mRegistered = false;
            }
        }

        public void reloadSettings() {
            ContentResolver cr = mAppCtx.getContentResolver();

            // Canonical Settings.Global first, falling back to Settings.Secure, then lazy glass.prop
            int gm = getGlobalInt(cr, KEY_GLOBAL_GLASS_MODE, -1);
            if (gm < MODE_CLEAR_REFRACTIVE || gm > MODE_SOLID_MATTE) {
                gm = getSecureInt(cr, KEY_SECURE_GLASS_MODE, -1);
            }

            int refPct = getGlobalInt(cr, KEY_REFRACTION_PCT, -1);
            if (refPct < 0 || refPct > 150) {
                refPct = getSecureInt(cr, KEY_REFRACTION_PCT, -1);
            }

            int frPct = getGlobalInt(cr, KEY_FROST_PCT, -1);
            if (frPct < 0 || frPct > 150) {
                frPct = getSecureInt(cr, KEY_FROST_PCT, -1);
            }

            int wpPct = getGlobalInt(cr, KEY_WP_BRIGHTNESS_PCT, -1);
            if (wpPct < 0 || wpPct > 100) {
                wpPct = getSecureInt(cr, KEY_WP_BRIGHTNESS_PCT, -1);
            }

            boolean needPropFallback = (gm < MODE_CLEAR_REFRACTIVE || gm > MODE_SOLID_MATTE)
                    || (refPct < 0 || refPct > 150)
                    || (frPct < 0 || frPct > 150)
                    || (wpPct < 0 || wpPct > 100);
            if (needPropFallback) {
                int propGm = -1;
                int propRef = -1;
                int propFrost = -1;
                int propWp = -1;
                File gp = new File(GLASS_PROP_PATH);
                if (gp.isFile()) {
                    try (Scanner sc = new Scanner(gp, StandardCharsets.UTF_8.name())) {
                        while (sc.hasNextLine()) {
                            String ln = sc.nextLine().trim();
                            if (ln.startsWith("glass_mode=")) propGm = parseIntSafe(ln.substring(11), -1);
                            else if (ln.startsWith("refraction_pct=")) propRef = parseIntSafe(ln.substring(15), -1);
                            else if (ln.startsWith("frost_pct=")) propFrost = parseIntSafe(ln.substring(10), -1);
                            else if (ln.startsWith("wp_brightness_pct=")) propWp = parseIntSafe(ln.substring(18), -1);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (gm < MODE_CLEAR_REFRACTIVE || gm > MODE_SOLID_MATTE) {
                    gm = (propGm >= MODE_CLEAR_REFRACTIVE && propGm <= MODE_SOLID_MATTE)
                            ? propGm : MODE_CLEAR_REFRACTIVE;
                }
                if (refPct < 0 || refPct > 150) {
                    refPct = (propRef >= 0 && propRef <= 150) ? propRef : DEFAULT_REFRACTION_PCT;
                }
                if (frPct < 0 || frPct > 150) {
                    frPct = (propFrost >= 0 && propFrost <= 150) ? propFrost : DEFAULT_FROST_PCT;
                }
                if (wpPct < 0 || wpPct > 100) {
                    wpPct = (propWp >= 0 && propWp <= 100) ? propWp : DEFAULT_WP_BRIGHTNESS_PCT;
                }
            }

            glassMode = clamp(gm, MODE_CLEAR_REFRACTIVE, MODE_SOLID_MATTE);
            refractScale = clamp(refPct, 0, 150) / 100.0f;
            frostScale = clamp(frPct, 0, 150) / 100.0f;
            wpBrightnessScale = clamp(wpPct, 0, 100) / 100.0f;
        }

        private static int parseIntSafe(String s, int def) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Throwable ignored) {
                return def;
            }
        }

        public void updateThemeFromConfig(Configuration cfg) {
            refreshScreenDimensions(mAppCtx);
            mCaptureEpoch++;
            mBlurPending = false;
            if (inCallMode) {
                darkTheme = true;
                activeBackdrop = getOrCreateBackdrop(THEME_INCALL_AURORA);
                return;
            }
            boolean night = cfg != null
                    && (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            darkTheme = night;
            mLiveSceneBuffers = null;
            mCapturedRootView = null;
            resetLiveScroll();
            activeBackdrop = getOrCreateBackdrop(night ? THEME_DARK : THEME_LIGHT);
        }

        public boolean isCapturingScene() {
            return mCapturingScene;
        }

        public BackdropBuffers getNeutralBackdrop() {
            if (inCallMode) {
                return activeBackdrop != null ? activeBackdrop : getOrCreateBackdrop(THEME_INCALL_AURORA);
            }
            return getOrCreateBackdrop(darkTheme ? THEME_DARK : THEME_LIGHT);
        }

        public void resetToNeutralBackdrop() {
            if (!inCallMode) {
                mCaptureEpoch++;
                mBlurPending = false;
                mLiveSceneBuffers = null;
                mCapturedRootView = null;
                activeBackdrop = getOrCreateBackdrop(darkTheme ? THEME_DARK : THEME_LIGHT);
            }
        }

        /**
         * Captures a downsampled snapshot of the live underlying page blended with the subtle
         * iOS 26 luminance depth mesh so floating Liquid Glass bars & small orbs genuinely refract
         * scrolled avatars, red/blue call labels, and keypad numerals in real time!
         */
        public void captureLiveScene(View sceneView, boolean forceNow) {
            if (inCallMode || sceneView == null || mCapturingScene) return;
            int vw = sceneView.getWidth();
            int vh = sceneView.getHeight();
            if (vw < 64 || vh < 64) return;

            long now = SystemClock.uptimeMillis();
            if (!forceNow && (now - mLastCaptureUptimeMs) < 34L) {
                return;
            }
            mLastCaptureUptimeMs = now;

            final int sw = 216;
            final int sh = 480;
            final int bw = 108;
            final int bh = 240;

            try {
                BackdropBuffers proc = getOrCreateBackdrop(darkTheme ? THEME_DARK : THEME_LIGHT);
                if (mLiveSharpBmpA == null || mLiveSharpBmpB == null || mLiveDownsampleBmp == null
                        || mLiveSoftBmpA == null || mLiveSoftBmpB == null) {
                    mLiveSharpBmpA = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    mLiveSharpBmpB = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    mLiveDownsampleBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    mLiveSoftBmpA = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    mLiveSoftBmpB = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    mLiveSharpCanvasA = new Canvas(mLiveSharpBmpA);
                    mLiveSharpCanvasB = new Canvas(mLiveSharpBmpB);
                    mLiveDownsampleCanvas = new Canvas(mLiveDownsampleBmp);
                    mLiveSoftSeedCanvasA = new Canvas(mLiveSoftBmpA);
                    mLiveSoftSeedCanvasB = new Canvas(mLiveSoftBmpB);
                    mLiveBuffersAA = new BackdropBuffers(mLiveSharpBmpA, mLiveSoftBmpA);
                    mLiveBuffersAB = new BackdropBuffers(mLiveSharpBmpA, mLiveSoftBmpB);
                    mLiveBuffersBA = new BackdropBuffers(mLiveSharpBmpB, mLiveSoftBmpA);
                    mLiveBuffersBB = new BackdropBuffers(mLiveSharpBmpB, mLiveSoftBmpB);
                    mBlurWorkA = new int[bw * bh];
                    mBlurWorkB = new int[bw * bh];
                }

                // When starting a fresh tab/scene capture, seed the BACK soft buffer from the
                // studio neutral soft backdrop so we never mutate a front buffer in flight on RenderThread.
                if (mLiveSceneBuffers == null) {
                    boolean writeSoftA = !mFrontIsSoftA;
                    Bitmap seedSoft = writeSoftA ? mLiveSoftBmpA : mLiveSoftBmpB;
                    Canvas seedCanvas = writeSoftA ? mLiveSoftSeedCanvasA : mLiveSoftSeedCanvasB;
                    mLiveBlendPaint.setAlpha(255);
                    seedCanvas.drawBitmap(proc.softBitmap, null, mSoftDstRect, mLiveBlendPaint);
                    seedSoft.prepareToDraw();
                    mFrontIsSoftA = writeSoftA;
                }

                // Draw into the BACK sharp buffer so RenderThread's active BitmapShader is never mutated
                boolean writeSharpA = !mFrontIsSharpA;
                Bitmap backSharp = writeSharpA ? mLiveSharpBmpA : mLiveSharpBmpB;
                Canvas backSharpCanvas = writeSharpA ? mLiveSharpCanvasA : mLiveSharpCanvasB;

                mCapturingScene = true;
                backSharpCanvas.save();
                // Base iOS 26 multi-tone optical field so clean areas still show convex glass caustics
                mLiveBlendPaint.setAlpha(255);
                backSharpCanvas.drawBitmap(proc.sharpBitmap, null, mSharpDstRect, mLiveBlendPaint);

                // Overlay live scrolled scene content into screen-space coordinates
                sceneView.getLocationOnScreen(mCaptureLoc);
                float sx = (float) sw / Math.max(1f, screenW);
                float sy = (float) sh / Math.max(1f, screenH);
                backSharpCanvas.scale(sx, sy);
                backSharpCanvas.translate(mCaptureLoc[0], mCaptureLoc[1]);
                sceneView.draw(backSharpCanvas);
                backSharpCanvas.restore();
                backSharp.prepareToDraw();
                mFrontIsSharpA = writeSharpA;

                View root = sceneView.getRootView();
                if (root == null) {
                    mCapturedRootView = null;
                } else if (mCapturedRootView == null || mCapturedRootView.get() != root) {
                    mCapturedRootView = new WeakReference<>(root);
                }
                mCapturedScrollYPx = mCurrentScrollYPx;
                mLiveSceneBuffers = selectLiveBuffers(writeSharpA, mFrontIsSoftA);
                activeBackdrop = mLiveSceneBuffers;

                dispatchAsyncSoftBlurOnUiThread();
            } catch (Throwable ignored) {
            } finally {
                mCapturingScene = false;
            }
        }

        private BackdropBuffers selectLiveBuffers(boolean sharpIsA, boolean softIsA) {
            return sharpIsA
                    ? (softIsA ? mLiveBuffersAA : mLiveBuffersAB)
                    : (softIsA ? mLiveBuffersBA : mLiveBuffersBB);
        }

        private void dispatchAsyncSoftBlurOnUiThread() {
            Bitmap frontSharp = mFrontIsSharpA ? mLiveSharpBmpA : mLiveSharpBmpB;
            if (frontSharp == null || mLiveDownsampleBmp == null
                    || mBlurWorkA == null || mBlurWorkB == null) {
                return;
            }
            if (mBlurInFlight) {
                mBlurPending = true;
                return;
            }
            final int bw = 108;
            final int bh = 240;
            mLiveBlendPaint.setAlpha(255);
            mLiveDownsampleCanvas.drawBitmap(frontSharp, null, mSoftDstRect, mLiveBlendPaint);
            mLiveDownsampleBmp.getPixels(mBlurWorkA, 0, bw, 0, 0, bw, bh);
            mBlurInFlight = true;
            final int epoch = mCaptureEpoch;
            sBlurExecutor.execute(() -> {
                blurInPlace2Pass(mBlurWorkA, mBlurWorkB, bw, bh, SOFT_BLUR_RADIUS);
                mMainHandler.post(() -> onSoftBlurCompletedUi(epoch, bw, bh));
            });
        }

        private void onSoftBlurCompletedUi(int epoch, int bw, int bh) {
            mBlurInFlight = false;
            Bitmap frontSharp = mFrontIsSharpA ? mLiveSharpBmpA : mLiveSharpBmpB;
            if (epoch == mCaptureEpoch && !inCallMode
                    && frontSharp != null && mLiveSceneBuffers != null) {
                boolean nextSoftIsA = !mFrontIsSoftA;
                Bitmap backSoft = nextSoftIsA ? mLiveSoftBmpA : mLiveSoftBmpB;
                backSoft.setPixels(mBlurWorkA, 0, bw, 0, 0, bw, bh);
                backSoft.prepareToDraw();
                mFrontIsSoftA = nextSoftIsA;
                mLiveSceneBuffers = selectLiveBuffers(mFrontIsSharpA, nextSoftIsA);
                activeBackdrop = mLiveSceneBuffers;
                if (mSoftBlurReadyCallback != null) {
                    mSoftBlurReadyCallback.run();
                }
            }
            if (mBlurPending) {
                mBlurPending = false;
                if (!inCallMode && mLiveSceneBuffers != null) {
                    dispatchAsyncSoftBlurOnUiThread();
                }
            }
        }

        public void setCallerPhotoBackdrop(final Bitmap callerPhoto) {
            if (!inCallMode) return;
            final int photoEpoch = ++mCallerPhotoEpoch;
            if (callerPhoto == null || callerPhoto.isRecycled()) {
                activeBackdrop = getOrCreateBackdrop(THEME_INCALL_AURORA);
                return;
            }
            final int sw = 270;
            final int sh = 600;
            sBlurExecutor.execute(() -> {
                final BackdropBuffers buffers;
                try {
                    if (callerPhoto.isRecycled()) return;
                    if (sCallerStageBmp == null || sCallerStageCanvas == null || sCallerStagePx == null) {
                        sCallerStageBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                        sCallerStageCanvas = new Canvas(sCallerStageBmp);
                        sCallerStagePx = new int[sw * sh];
                    }
                    sCallerStageCanvas.drawBitmap(callerPhoto, null, sCallerDstRect, sCallerScalePaint);
                    sCallerStageBmp.getPixels(sCallerStagePx, 0, sw, 0, 0, sw, sh);
                    buffers = buildPhotoInfusedAuroraFromPixels(sCallerStagePx, sw, sh);
                } catch (Throwable t) {
                    mMainHandler.post(() -> {
                        if (inCallMode && photoEpoch == mCallerPhotoEpoch) {
                            activeBackdrop = getOrCreateBackdrop(THEME_INCALL_AURORA);
                        }
                    });
                    return;
                }
                mMainHandler.post(() -> {
                    if (inCallMode && photoEpoch == mCallerPhotoEpoch) {
                        activeBackdrop = buffers;
                        if (mSoftBlurReadyCallback != null) {
                            mSoftBlurReadyCallback.run();
                        }
                    }
                });
            });
        }

        private static synchronized BackdropBuffers getOrCreateBackdrop(int theme) {
            if (theme == THEME_LIGHT) {
                if (sLightBackdrop == null) {
                    sLightBackdrop = buildProceduralBackdrop(THEME_LIGHT);
                }
                return sLightBackdrop;
            }
            if (theme == THEME_DARK) {
                if (sDarkBackdrop == null) {
                    sDarkBackdrop = buildProceduralBackdrop(THEME_DARK);
                }
                return sDarkBackdrop;
            }
            if (sAuroraBackdrop == null) {
                sAuroraBackdrop = buildProceduralBackdrop(THEME_INCALL_AURORA);
            }
            return sAuroraBackdrop;
        }
    }

    public static final class GlassSurfaceInstance {
        private final RuntimeShader mShader;

        public GlassSurfaceInstance() {
            RuntimeShader s = null;
            try {
                s = new RuntimeShader(SURFACE_AGSL);
            } catch (Throwable ignored) {
            }
            mShader = s;
        }

        public boolean bindToPaint(
                Paint paint,
                LiveOpticalState opt,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp,
                float tintBoost) {
            return bindToPaintEx(
                    paint, opt, widthPx, heightPx, screenX, screenY, radiusPx, density,
                    touchX, touchY, touchAmp, tintBoost, 0, 0f, 0f, -1f, 0f);
        }

        public boolean bindToPaintTinted(
                Paint paint,
                LiveOpticalState opt,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp,
                int tintColorArgb,
                float tintMix) {
            return bindToPaintEx(
                    paint, opt, widthPx, heightPx, screenX, screenY, radiusPx, density,
                    touchX, touchY, touchAmp, 0.18f, tintColorArgb, tintMix, 0f, -1f, 0f);
        }

        public boolean bindToPaintNeutral(
                Paint paint,
                LiveOpticalState opt,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp,
                float tintBoost) {
            if (opt == null) return false;
            return bindToPaintWithBuffers(
                    paint, opt, opt.getNeutralBackdrop(),
                    widthPx, heightPx, screenX, screenY, radiusPx, density,
                    touchX, touchY, touchAmp, tintBoost, 0, 0f, 0f, -1f, 0f);
        }

        public boolean bindToPaintEx(
                Paint paint,
                LiveOpticalState opt,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp,
                float tintBoost,
                int tintColorArgb,
                float tintMix,
                float neighborDirX,
                float neighborDirY,
                float neighborGlow) {
            if (opt == null) return false;
            return bindToPaintWithBuffers(
                    paint, opt, opt.activeBackdrop,
                    widthPx, heightPx, screenX, screenY, radiusPx, density,
                    touchX, touchY, touchAmp, tintBoost, tintColorArgb, tintMix,
                    neighborDirX, neighborDirY, neighborGlow);
        }

        public boolean bindToPaintInCallLit(
                Paint paint,
                LiveOpticalState opt,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp) {
            return bindToPaintEx(
                    paint, opt, widthPx, heightPx, screenX, screenY, radiusPx, density,
                    touchX, touchY, touchAmp, 0.24f, 0xFFF4F7FF, 0.78f, 0f, -1f, 0f);
        }

        public static void drawTranslated(
                Canvas canvas, float left, float top, float w, float h, Paint paint) {
            if (canvas == null || paint == null || w <= 0f || h <= 0f) return;
            int save = canvas.save();
            canvas.translate(left, top);
            canvas.drawRect(0f, 0f, w, h, paint);
            canvas.restoreToCount(save);
        }

        public boolean bindToPaintWithBuffers(
                Paint paint,
                LiveOpticalState opt,
                BackdropBuffers bd,
                float widthPx,
                float heightPx,
                float screenX,
                float screenY,
                float radiusPx,
                float density,
                float touchX,
                float touchY,
                float touchAmp,
                float tintBoost,
                int tintColorArgb,
                float tintMix,
                float neighborDirX,
                float neighborDirY,
                float neighborGlow) {
            if (mShader == null || opt == null || bd == null
                    || opt.isCapturingScene() || widthPx <= 1f || heightPx <= 1f) {
                return false;
            }
            try {
                BackdropBuffers liveBd = opt.mLiveSceneBuffers;
                boolean isLive = (liveBd != null && bd == liveBd);
                float effectiveScreenY = screenY;
                if (isLive && (screenY + heightPx) > opt.mScrollViewportTopPx) {
                    effectiveScreenY = screenY + (opt.mCurrentScrollYPx - opt.mCapturedScrollYPx);
                }

                mShader.setLocalMatrix(null);
                mShader.setInputShader("uSharpBg", bd.sharpShader);
                mShader.setInputShader("uSoftBg", bd.softShader);
                mShader.setFloatUniform("uSize", widthPx, heightPx);
                mShader.setFloatUniform("uScreenOrigin", screenX, effectiveScreenY);
                mShader.setFloatUniform("uScreenSize",
                        Math.max(1f, opt.screenW), Math.max(1f, opt.screenH));
                mShader.setFloatUniform("uSharpBufSize", (float) bd.sharpW, (float) bd.sharpH);
                mShader.setFloatUniform("uSoftBufSize", (float) bd.softW, (float) bd.softH);
                mShader.setFloatUniform("uRadius", radiusPx);
                mShader.setFloatUniform("uDensity", density);
                mShader.setFloatUniform("uGlassMode", (float) opt.glassMode);
                mShader.setFloatUniform("uRefractScale", opt.refractScale);
                mShader.setFloatUniform("uFrostScale", opt.frostScale);
                mShader.setFloatUniform("uDarkTheme", opt.darkTheme ? 1.0f : 0.0f);
                mShader.setFloatUniform("uInCallMode", opt.inCallMode ? 1.0f : 0.0f);
                mShader.setFloatUniform("uTouchPos", touchX, touchY);
                mShader.setFloatUniform("uTouchAmp", clampF(touchAmp, 0f, 1.15f));
                mShader.setFloatUniform("uPhase", opt.phase);
                mShader.setFloatUniform("uTintBoost", tintBoost);

                float tr = ((tintColorArgb >> 16) & 0xFF) / 255.0f;
                float tg = ((tintColorArgb >> 8) & 0xFF) / 255.0f;
                float tb = (tintColorArgb & 0xFF) / 255.0f;
                mShader.setFloatUniform("uTintColor", tr, tg, tb);
                mShader.setFloatUniform("uTintMix", clampF(tintMix, 0f, 1f));
                mShader.setFloatUniform("uNeighborDir", neighborDirX, neighborDirY);
                mShader.setFloatUniform("uNeighborGlow", clampF(neighborGlow, 0f, 1f));
                mShader.setFloatUniform("uIsLiveCapture", isLive ? 1.0f : 0.0f);

                paint.setShader(mShader);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static void applyBlurRenderEffect(View view, float radiusPx) {
        if (view == null) return;
        try {
            if (radiusPx <= 0.5f) {
                view.setRenderEffect(null);
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(
                        radiusPx, radiusPx, Shader.TileMode.CLAMP));
            }
        } catch (Throwable ignored) {
        }
    }

    // Continuous G2 Superellipse (kCACornerCurveContinuous)
    public static final class Squircle {
        private static final float P = 1.52866483f;
        private static final float A = 1.08849323f;
        private static final float B = 0.86840689f;
        private static final float C = 0.63149399f;
        private static final float D = 0.07491139f;
        private static final float E = 0.37282392f;
        private static final float F = 0.16906013f;

        private Squircle() {}

        public static Path buildPath(float l, float t, float r, float b, float cornerRadius, Path out) {
            Path p = (out != null) ? out : new Path();
            p.reset();
            float w = r - l;
            float h = b - t;
            if (w <= 0f || h <= 0f) return p;
            float halfMin = 0.5f * Math.min(w, h);
            float cr = clampF(cornerRadius, 0f, halfMin);
            if (cr <= 0.5f) {
                p.addRect(l, t, r, b, Path.Direction.CW);
                return p;
            }
            if (cr >= halfMin * 0.96f) {
                p.addRoundRect(l, t, r, b, halfMin, halfMin, Path.Direction.CW);
                return p;
            }

            float px = Math.min(w * 0.5f, P * cr);
            float py = Math.min(h * 0.5f, P * cr);
            float sx = px / (P * cr);
            float sy = py / (P * cr);

            float ax = A * cr * sx;
            float bx = B * cr * sx;
            float cx = C * cr * sx;
            float dx = D * cr * sy;
            float ex = E * cr * sx;
            float fx = F * cr * sy;
            float gx = F * cr * sx;
            float hx = E * cr * sy;
            float kx = D * cr * sx;
            float mx = C * cr * sy;
            float by = B * cr * sy;
            float ay = A * cr * sy;

            p.moveTo(l + px, t);
            p.lineTo(r - px, t);
            p.cubicTo(r - ax, t, r - bx, t, r - cx, t + dx);
            p.cubicTo(r - ex, t + fx, r - gx, t + hx, r - kx, t + mx);
            p.cubicTo(r, t + by, r, t + ay, r, t + py);
            p.lineTo(r, b - py);
            p.cubicTo(r, b - ay, r, b - by, r - kx, b - mx);
            p.cubicTo(r - gx, b - hx, r - ex, b - fx, r - cx, b - dx);
            p.cubicTo(r - bx, b, r - ax, b, r - px, b);
            p.lineTo(l + px, b);
            p.cubicTo(l + ax, b, l + bx, b, l + cx, b - dx);
            p.cubicTo(l + ex, b - fx, l + gx, b - hx, l + kx, b - mx);
            p.cubicTo(l, b - by, l, b - ay, l, b - py);
            p.lineTo(l, t + py);
            p.cubicTo(l, t + ay, l, t + by, l + kx, t + mx);
            p.cubicTo(l + gx, t + hx, l + ex, t + fx, l + cx, t + dx);
            p.cubicTo(l + bx, t, l + ax, t, l + px, t);
            p.close();
            return p;
        }

        public static ViewOutlineProvider outlineProvider(final float radiusPx) {
            return new ViewOutlineProvider() {
                private final Path mPath = new Path();

                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) return;
                    buildPath(0f, 0f, w, h, radiusPx, mPath);
                    outline.setPath(mPath);
                }
            };
        }
    }

    // Theme Palette Helpers
    public static int accentBlue(boolean dark) {
        return dark ? IOS_BLUE_DARK : IOS_BLUE_LIGHT;
    }

    public static int canvasBg(boolean dark) {
        return dark ? DARK_BG : LIGHT_BG;
    }

    public static int cardBg(boolean dark) {
        return dark ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    public static int primaryInk(boolean dark) {
        return dark ? 0xFFFFFFFF : 0xFF000000;
    }

    public static int secondaryInk(boolean dark) {
        return dark ? 0xFF8E8E93 : 0xFF6C6C70;
    }

    public static int tertiaryInk(boolean dark) {
        return dark ? 0xFF636366 : 0xFFAEAEB2;
    }

    public static int separatorColor(boolean dark) {
        return dark ? 0xFF2C2C2E : 0xFFD1D1D6;
    }

    public static int searchBarFill(boolean dark) {
        return dark ? 0xFF1C1C1E : 0xFFE3E3E9;
    }

    // Crisp Haptic Feedback (Respects Phone Settings System Haptics toggle)
    public static void performKeyboardTap(View v) {
        if (v == null || !TelecomHelper.isHapticsEnabled(v.getContext())) return;
        try {
            if (v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        vibrateFallback(v.getContext(), false);
    }

    public static void performTick(View v, boolean strong) {
        if (v == null || !TelecomHelper.isHapticsEnabled(v.getContext())) return;
        try {
            int code = strong
                    ? HapticFeedbackConstants.CONTEXT_CLICK
                    : HapticFeedbackConstants.CLOCK_TICK;
            if (v.performHapticFeedback(code)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        vibrateFallback(v.getContext(), strong);
    }

    private static void vibrateFallback(Context ctx, boolean strong) {
        if (ctx == null) return;
        try {
            VibratorManager vm = ctx.getSystemService(VibratorManager.class);
            if (vm != null) {
                Vibrator vib = vm.getDefaultVibrator();
                if (vib.hasVibrator()) {
                    vib.vibrate(VibrationEffect.createPredefined(
                            strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static BackdropBuffers buildProceduralBackdrop(int theme) {
        int sw = 270;
        int sh = 600;
        int[] sharpPx = new int[sw * sh];
        float invW = 1f / (sw - 1);
        float invH = 1f / (sh - 1);

        for (int y = 0; y < sh; y++) {
            float yn = y * invH;
            int row = y * sw;
            for (int x = 0; x < sw; x++) {
                float xn = x * invW;
                // 2D multi-lobe studio optical field so convex meniscus displacement bends
                // tangible horizontal + vertical tonal gradients across all 12 keypad orbs & tab bar
                float dx1 = xn - 0.18f;
                float dy1 = yn - 0.16f;
                float lobeTL = (float) Math.exp(-(dx1 * dx1 + dy1 * dy1) / 0.13f);

                float dx2 = xn - 0.84f;
                float dy2 = yn - 0.50f;
                float lobeMR = (float) Math.exp(-(dx2 * dx2 + dy2 * dy2) / 0.11f);

                float dx3 = xn - 0.22f;
                float dy3 = yn - 0.72f;
                float troughLL = (float) Math.exp(-(dx3 * dx3 + dy3 * dy3) / 0.12f);

                float dx4 = xn - 0.64f;
                float dy4 = yn - 0.90f;
                float troughBC = (float) Math.exp(-(dx4 * dx4 + dy4 * dy4) / 0.14f);

                float diagWave = 0.5f + 0.5f * (float) Math.sin((xn * 2.35f - yn * 1.85f) * Math.PI);
                float crossWave = 0.5f + 0.5f * (float) Math.cos((xn * 1.65f + yn * 2.15f) * Math.PI);

                float fWarm = clampF(
                        0.44f + 0.34f * lobeTL + 0.28f * lobeMR
                                - 0.30f * troughLL - 0.24f * troughBC + 0.14f * diagWave,
                        0f, 1f);
                float fMid = clampF(
                        0.45f + 0.32f * lobeTL + 0.32f * lobeMR
                                - 0.28f * troughLL - 0.25f * troughBC + 0.14f * crossWave,
                        0f, 1f);
                float fCool = clampF(
                        0.48f + 0.28f * lobeTL + 0.36f * lobeMR
                                - 0.26f * troughLL - 0.24f * troughBC + 0.16f * crossWave,
                        0f, 1f);

                float r;
                float g;
                float b;
                if (theme == THEME_LIGHT) {
                    // 2D studio silver-ivory field spanning 231..253 (22/255 span)
                    r = 231f + 22f * fWarm;
                    g = 232f + 21f * fMid;
                    b = 234f + 19f * fCool;
                } else if (theme == THEME_DARK) {
                    // 2D OLED studio charcoal-slate field spanning 10..38 (28/255 span)
                    r = 10f + 24f * fWarm;
                    g = 11f + 25f * fMid;
                    b = 13f + 25f * fCool;
                } else {
                    // 2D iOS 26 deep slate-indigo-teal call studio field spanning 14..78 (64/255 span)
                    r = 14f + 46f * fWarm;
                    g = 17f + 52f * fMid;
                    b = 22f + 56f * fCool;
                }
                sharpPx[row + x] = packRgb(r, g, b);
            }
        }
        Bitmap sharpBmp = Bitmap.createBitmap(sharpPx, sw, sh, Bitmap.Config.ARGB_8888);
        sharpBmp.prepareToDraw();

        int bw = 135;
        int bh = 300;
        Bitmap scaled = Bitmap.createScaledBitmap(sharpBmp, bw, bh, true);
        int[] softSrc = new int[bw * bh];
        scaled.getPixels(softSrc, 0, bw, 0, 0, bw, bh);
        if (scaled != sharpBmp) {
            scaled.recycle();
        }
        int[] softPx = boxBlur3Pass(softSrc, bw, bh, 10);
        Bitmap softBmp = Bitmap.createBitmap(softPx, bw, bh, Bitmap.Config.ARGB_8888);
        softBmp.prepareToDraw();
        return new BackdropBuffers(sharpBmp, softBmp);
    }

    private static BackdropBuffers buildPhotoInfusedAuroraFromPixels(int[] pPx, int sw, int sh) {
        int[] blurredPhoto = boxBlur3Pass(pPx, sw, sh, 16);
        float invH = 1f / (sh - 1);
        for (int y = 0; y < sh; y++) {
            float yn = y * invH;
            int row = y * sw;
            float darken = 0.58f - 0.16f * yn;
            for (int x = 0; x < sw; x++) {
                int c = blurredPhoto[row + x];
                float r = ((c >> 16) & 0xFF) * darken + 10f;
                float g = ((c >> 8) & 0xFF) * darken + 14f;
                float b = (c & 0xFF) * darken + 24f;
                blurredPhoto[row + x] = packRgb(r, g, b);
            }
        }
        Bitmap sharpBmp = Bitmap.createBitmap(blurredPhoto, sw, sh, Bitmap.Config.ARGB_8888);
        sharpBmp.prepareToDraw();
        int bw = 135;
        int bh = 300;
        Bitmap softScaled = Bitmap.createScaledBitmap(sharpBmp, bw, bh, true);
        int[] softSrc = new int[bw * bh];
        softScaled.getPixels(softSrc, 0, bw, 0, 0, bw, bh);
        if (softScaled != sharpBmp) {
            softScaled.recycle();
        }
        int[] softPx = boxBlur3Pass(softSrc, bw, bh, 11);
        Bitmap softBmp = Bitmap.createBitmap(softPx, bw, bh, Bitmap.Config.ARGB_8888);
        softBmp.prepareToDraw();
        return new BackdropBuffers(sharpBmp, softBmp);
    }

    private static int packRgb(float r, float g, float b) {
        return Color.rgb(
                clamp(Math.round(r), 0, 255),
                clamp(Math.round(g), 0, 255),
                clamp(Math.round(b), 0, 255));
    }

    private static void blurInPlace2Pass(int[] a, int[] b, int w, int h, int radius) {
        for (int pass = 0; pass < 2; pass++) {
            blurHorizontal(a, b, w, h, radius);
            blurVertical(b, a, w, h, radius);
        }
    }

    private static int[] boxBlur3Pass(int[] src, int w, int h, int radius) {
        int[] a = src.clone();
        int[] b = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(a, b, w, h, radius);
            blurVertical(b, a, w, h, radius);
        }
        return a;
    }

    private static void blurHorizontal(int[] src, int[] dst, int w, int h, int rad) {
        int win = rad * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int c = src[row + clamp(k, 0, w - 1)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000
                        | ((sr / win) << 16)
                        | ((sg / win) << 8)
                        | (sb / win);
                int cOut = src[row + clamp(x - rad, 0, w - 1)];
                int cIn = src[row + clamp(x + rad + 1, 0, w - 1)];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int w, int h, int rad) {
        int win = rad * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int c = src[clamp(k, 0, h - 1) * w + x];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((sr / win) << 16)
                        | ((sg / win) << 8)
                        | (sb / win);
                int cOut = src[clamp(y - rad, 0, h - 1) * w + x];
                int cIn = src[clamp(y + rad + 1, 0, h - 1) * w + x];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static int getGlobalInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.Global.getInt(cr, key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int getSecureInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.Secure.getInt(cr, key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int blendArgb(int c1, int c2, float frac) {
        float t = clampF(frac, 0f, 1f);
        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF;
        int r2 = (c2 >>> 16) & 0xFF;
        int g2 = (c2 >>> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        return (Math.round(a1 + (a2 - a1) * t) << 24)
                | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8)
                | Math.round(b1 + (b2 - b1) * t);
    }

    // =========================================================================
    // Zero-Allocation 90Hz UI-Thread Avatar, Tab Icon & Backspace Tag Renderers
    // =========================================================================
    private static final LinearGradient[] sMonogramUnitGrads = {
            new LinearGradient(0f, -1f, 0f, 1f, 0xFF5AC8FA, 0xFF007AFF, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFF34C759, 0xFF248A3D, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFFFF9F0A, 0xFFFF6B00, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFFBF5AF2, 0xFF8944AB, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFFFF375F, 0xFFD70015, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFF64D2FF, 0xFF0A84FF, Shader.TileMode.CLAMP),
            new LinearGradient(0f, -1f, 0f, 1f, 0xFF8E8E93, 0xFF636366, Shader.TileMode.CLAMP)
    };
    private static final Matrix sAvatarMatrix = new Matrix();
    private static final Typeface sAvatarTypeface =
            Typeface.create("sans-serif-medium", Typeface.BOLD);
    private static final Paint.FontMetrics sAvatarFm = new Paint.FontMetrics();
    private static final LruCache<String, String> sInitialsCache = new LruCache<>(256);
    private static final LruCache<String, Bitmap> sResolvedPhotoCache = new LruCache<>(64);
    private static final LruCache<String, Boolean> sRequestedPhotoUris = new LruCache<>(128);
    private static final LruCache<Bitmap, BitmapShader> sAvatarShaderCache = new LruCache<>(64);

    private static final Path sVecPath = new Path();
    private static final Path sVecCutPath = new Path();
    private static final Path sVecAuxPath = new Path();
    private static final Path sVecInnerClipPath = new Path();
    private static final RectF sVecRect = new RectF();
    private static final SparseArray<CornerPathEffect> sCornerEffects = new SparseArray<>(16);

    private static CornerPathEffect getCachedCornerEffect(float radiusPx) {
        int key = Math.max(1, Math.round(radiusPx * 20f));
        CornerPathEffect fx = sCornerEffects.get(key);
        if (fx == null) {
            fx = new CornerPathEffect(key / 20f);
            sCornerEffects.put(key, fx);
        }
        return fx;
    }

    private static String getCachedInitials(String name) {
        if (name == null || name.isEmpty()) return "";
        String cached = sInitialsCache.get(name);
        if (cached != null) return cached;
        char c0 = 0;
        char c1 = 0;
        boolean inWord = false;
        int len = name.length();
        for (int i = 0; i < len; i++) {
            char ch = name.charAt(i);
            if (Character.isWhitespace(ch)) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                if (Character.isLetter(ch)) {
                    char up = Character.toUpperCase(ch);
                    if (c0 == 0) {
                        c0 = up;
                    } else {
                        c1 = up;
                        break;
                    }
                }
            }
        }
        String res = (c0 == 0) ? "" : (c1 == 0 ? String.valueOf(c0) : new String(new char[]{c0, c1}));
        sInitialsCache.put(name, res);
        return res;
    }

    public static void drawZeroAllocAvatar(
            Canvas canvas,
            DialerDataRepository repo,
            float cx,
            float cy,
            float radius,
            String name,
            final String photoUri,
            Paint workPaint,
            final Runnable onPhotoLoaded) {
        Bitmap photo = null;
        if (repo != null && photoUri != null && !photoUri.isEmpty()) {
            photo = sResolvedPhotoCache.get(photoUri);
            if (photo == null || photo.isRecycled()) {
                photo = null;
                if (sRequestedPhotoUris.get(photoUri) == null) {
                    sRequestedPhotoUris.put(photoUri, Boolean.TRUE);
                    Bitmap immediate = repo.getCachedOrLoadPhoto(photoUri, () -> {
                        Bitmap loaded = repo.getCachedOrLoadPhoto(photoUri, null);
                        if (loaded != null && !loaded.isRecycled()) {
                            sResolvedPhotoCache.put(photoUri, loaded);
                        }
                        if (onPhotoLoaded != null) {
                            onPhotoLoaded.run();
                        }
                    });
                    if (immediate != null && !immediate.isRecycled()) {
                        sResolvedPhotoCache.put(photoUri, immediate);
                        photo = immediate;
                    }
                }
            }
        }

        if (photo != null && !photo.isRecycled()) {
            BitmapShader bs = sAvatarShaderCache.get(photo);
            if (bs == null) {
                bs = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sAvatarShaderCache.put(photo, bs);
            }
            workPaint.reset();
            workPaint.setAntiAlias(true);
            float scale = (radius * 2f) / Math.max(1f, Math.min(photo.getWidth(), photo.getHeight()));
            sAvatarMatrix.setScale(scale, scale);
            sAvatarMatrix.postTranslate(
                    cx - photo.getWidth() * scale * 0.5f,
                    cy - photo.getHeight() * scale * 0.5f);
            bs.setLocalMatrix(sAvatarMatrix);
            workPaint.setShader(bs);
            canvas.drawCircle(cx, cy, radius, workPaint);
            workPaint.setShader(null);
            return;
        }

        String initials = getCachedInitials(name);
        int hash = name != null ? (name.hashCode() & 0x7FFFFFFF) : 0;
        LinearGradient grad = initials.isEmpty()
                ? sMonogramUnitGrads[6]
                : sMonogramUnitGrads[hash % (sMonogramUnitGrads.length - 1)];

        workPaint.reset();
        workPaint.setAntiAlias(true);
        sAvatarMatrix.setScale(1f, Math.max(1f, radius));
        sAvatarMatrix.postTranslate(cx, cy);
        grad.setLocalMatrix(sAvatarMatrix);
        workPaint.setShader(grad);
        canvas.drawCircle(cx, cy, radius, workPaint);
        workPaint.setShader(null);

        if (!initials.isEmpty()) {
            workPaint.setColor(Color.WHITE);
            workPaint.setTextAlign(Paint.Align.CENTER);
            workPaint.setTypeface(sAvatarTypeface);
            workPaint.setTextSize(radius * 0.84f);
            workPaint.getFontMetrics(sAvatarFm);
            canvas.drawText(initials, cx, cy - (sAvatarFm.ascent + sAvatarFm.descent) * 0.5f, workPaint);
        } else {
            drawZeroAllocTabIcon(
                    canvas, IosVectorIcons.TAB_CONTACTS, cx, cy, radius * 1.35f,
                    Color.argb(225, 255, 255, 255), workPaint);
        }
    }

    public static void invalidateGlassSubtree(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0, n = vg.getChildCount(); i < n; i++) {
                invalidateGlassSubtree(vg.getChildAt(i));
            }
        }
    }

    public static void drawZeroAllocTabIcon(
            Canvas c, int tab, float cx, float cy, float size, int color, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        float r = size * 0.5f;
        float sw = Math.max(1.9f, size * 0.098f);

        switch (tab) {
            case IosVectorIcons.TAB_UNIFIED_CALLS:
                IosVectorIcons.drawPhoneHandset(c, cx, cy, size * 0.94f, color, false, p);
                break;
            case IosVectorIcons.TAB_FAVORITES: {
                sVecPath.reset();
                float outer = r * 0.98f;
                float inner = outer * 0.44f;
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2.0 + i * (Math.PI / 5.0);
                    float rad = (i % 2 == 0) ? outer : inner;
                    float px = cx + (float) Math.cos(ang) * rad;
                    float py = cy + (float) Math.sin(ang) * rad + size * 0.02f;
                    if (i == 0) sVecPath.moveTo(px, py);
                    else sVecPath.lineTo(px, py);
                }
                sVecPath.close();
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(getCachedCornerEffect(size * 0.055f));
                c.drawPath(sVecPath, p);
                p.setPathEffect(null);
                break;
            }
            case IosVectorIcons.TAB_RECENTS: {
                float cr = r * 0.90f;
                float hw = Math.max(1.05f, size * 0.052f);
                sVecPath.reset();
                sVecPath.addCircle(cx, cy, cr, Path.Direction.CW);

                sVecCutPath.reset();
                sVecRect.set(cx - hw, cy - cr * 0.60f, cx + hw, cy + hw);
                sVecCutPath.addRoundRect(sVecRect, hw, hw, Path.Direction.CW);

                sVecAuxPath.reset();
                sVecRect.set(cx - hw, cy - hw, cx + cr * 0.52f, cy + hw);
                sVecAuxPath.addRoundRect(sVecRect, hw, hw, Path.Direction.CW);

                sVecCutPath.op(sVecAuxPath, Path.Op.UNION);
                sVecPath.op(sVecCutPath, Path.Op.DIFFERENCE);

                p.setStyle(Paint.Style.FILL);
                c.drawPath(sVecPath, p);
                break;
            }
            case IosVectorIcons.TAB_CONTACTS: {
                float cr = r * 0.92f;
                sVecPath.reset();
                sVecPath.addCircle(cx, cy, cr, Path.Direction.CW);

                sVecCutPath.reset();
                sVecCutPath.addCircle(cx, cy - cr * 0.20f, cr * 0.30f, Path.Direction.CW);

                sVecAuxPath.reset();
                sVecRect.set(cx - cr * 0.64f, cy + cr * 0.20f, cx + cr * 0.64f, cy + cr * 1.16f);
                sVecAuxPath.addOval(sVecRect, Path.Direction.CW);

                sVecInnerClipPath.reset();
                sVecInnerClipPath.addCircle(cx, cy, cr * 0.78f, Path.Direction.CW);
                sVecAuxPath.op(sVecInnerClipPath, Path.Op.INTERSECT);

                sVecCutPath.op(sVecAuxPath, Path.Op.UNION);
                sVecPath.op(sVecCutPath, Path.Op.DIFFERENCE);

                p.setStyle(Paint.Style.FILL);
                c.drawPath(sVecPath, p);
                break;
            }
            case IosVectorIcons.TAB_KEYPAD: {
                p.setStyle(Paint.Style.FILL);
                float dotR = size * 0.088f;
                float stepX = size * 0.285f;
                float stepY = size * 0.252f;
                for (int row = 0; row < 3; row++) {
                    float ry = cy + (row - 1.42f) * stepY;
                    for (int col = -1; col <= 1; col++) {
                        c.drawCircle(cx + col * stepX, ry, dotR, p);
                    }
                }
                c.drawCircle(cx, cy + (3f - 1.42f) * stepY, dotR, p);
                break;
            }
            case IosVectorIcons.TAB_VOICEMAIL:
                IosVectorIcons.drawVoicemailTape(c, cx, cy, size, color, sw, p);
                break;
            default:
                break;
        }
    }

    public static void drawZeroAllocBackspaceTag(
            Canvas c, float cx, float cy, float size, int fillColor, int crossColor, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        float w = size * 1.12f;
        float h = size * 0.78f;
        float left = cx - w * 0.50f;
        float right = cx + w * 0.50f;
        float top = cy - h * 0.5f;
        float bot = cy + h * 0.5f;
        float tip = left + w * 0.28f;
        float cr = h * 0.22f;

        sVecPath.reset();
        sVecPath.moveTo(tip, top);
        sVecPath.lineTo(right - cr, top);
        sVecPath.quadTo(right, top, right, top + cr);
        sVecPath.lineTo(right, bot - cr);
        sVecPath.quadTo(right, bot, right - cr, bot);
        sVecPath.lineTo(tip, bot);
        sVecPath.lineTo(left, cy);
        sVecPath.close();

        if ((fillColor >>> 24) > 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(fillColor);
            p.setPathEffect(getCachedCornerEffect(h * 0.15f));
            c.drawPath(sVecPath, p);
            p.setPathEffect(null);
        } else {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(crossColor);
            p.setStrokeWidth(Math.max(1.8f, size * 0.095f));
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(sVecPath, p);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setColor(crossColor);
        p.setStrokeWidth(Math.max(1.8f, size * 0.095f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float xCx = cx + w * 0.09f;
        float d = h * 0.21f;
        c.drawLine(xCx - d, cy - d, xCx + d, cy + d, p);
        c.drawLine(xCx + d, cy - d, xCx - d, cy + d, p);
    }

    private DialerGlassEngine() {}
}
