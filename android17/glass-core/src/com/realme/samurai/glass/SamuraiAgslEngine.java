/*
 * Clean-Room 90Hz AGSL Optical & Metaball Morphing Engine (`SamuraiAgslEngine`).
 *
 * Open-Source Shader Math Attributions & Licenses:
 * - Spherical meniscus `circleMap(x) = 1.0 - sqrt(1.0 - x*x)` & 7-band ROYGCBP dispersion
 *   adapted from `Kyant0/AndroidLiquidGlass` (Apache-2.0).
 * - Smooth polynomial liquid shape union (`sminPoly`), Gaussian touch-bulge lens, and non-linear
 *   vibrancy adapted from `QWEA0/Liquid-Glass-Android` (MIT).
 * - Adreno-640-safe G2 superellipse SDF (`n = 4.6`) & 34-deg Fresnel catchlights for RMX1931.
 */
package com.realme.samurai.glass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Shared 90Hz GPU AGSL optical engine used across `SystemUI`, `IosSettings`, `IosClock`,
 * `IosCalculator`, `IosNotes`, and `IosWeather`.
 */
public final class SamuraiAgslEngine {

    private static final String SURFACE_GLASS_AGSL = ""
            + "uniform shader uSharpBg;\n"
            + "uniform shader uSoftBg;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uSharpBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float  uRadius;\n"
            + "uniform float  uDensity;\n"
            + "uniform float  uGlassMode;\n"
            + "uniform float  uRefractScale;\n"
            + "uniform float  uFrostScale;\n"
            + "uniform float  uDarkTheme;\n"
            + "uniform float2 uTouchPos;\n"
            + "uniform float  uTouchAmp;\n"
            + "uniform float  uTintColorR;\n"
            + "uniform float  uTintColorG;\n"
            + "uniform float  uTintColorB;\n"
            + "uniform float  uTintStrength;\n"
            + "\n"
            + "float sdSuperellipse(float2 p, float2 halfSize, float r) {\n"
            + "    float minH = min(halfSize.x, halfSize.y);\n"
            + "    float cr = clamp(r, 1.5, max(minH, 1.5));\n"
            + "    float2 q = abs(p) - (halfSize - float2(cr));\n"
            + "    float inside = min(max(q.x, q.y), 0.0);\n"
            + "    if (q.x <= 0.0 && q.y <= 0.0) return inside - cr;\n"
            + "    float2 mq = max(q, float2(0.001));\n"
            + "    float corner = pow(pow(mq.x, 4.6) + pow(mq.y, 4.6), 0.2173913);\n"
            + "    return corner + inside - cr;\n"
            + "}\n"
            + "\n"
            + "float circleMap(float x) {\n"
            + "    float cx = clamp(x, 0.0, 1.0);\n"
            + "    return 1.0 - sqrt(max(1.0 - cx * cx, 0.0));\n"
            + "}\n"
            + "\n"
            + "float2 toBuf(float2 screenPx, float2 bufSize) {\n"
            + "    float2 uv = clamp(screenPx / max(uScreenSize, float2(2.0)), float2(0.002), float2(0.998));\n"
            + "    return uv * bufSize;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 halfSize = uSize * 0.5;\n"
            + "    float2 rel = fragCoord - halfSize;\n"
            + "    float d = sdSuperellipse(rel, halfSize, uRadius);\n"
            + "    float aa = 1.0 - smoothstep(-0.85, 0.75, d);\n"
            + "    if (aa <= 0.002) return half4(0.0);\n"
            + "\n"
            + "    float eps = 1.2;\n"
            + "    float dx = sdSuperellipse(rel + float2(eps, 0.0), halfSize, uRadius) - sdSuperellipse(rel - float2(eps, 0.0), halfSize, uRadius);\n"
            + "    float dy = sdSuperellipse(rel + float2(0.0, eps), halfSize, uRadius) - sdSuperellipse(rel - float2(0.0, eps), halfSize, uRadius);\n"
            + "    float2 grad = float2(dx, dy) / max(length(float2(dx, dy)), 0.0001);\n"
            + "\n"
            + "    float bevelH = clamp(min(halfSize.x, halfSize.y) * 0.34, 9.0 * uDensity, 26.0 * uDensity);\n"
            + "    float edgeFrac = 1.0 - clamp(-d / max(bevelH, 1.0), 0.0, 1.0);\n"
            + "    float meniscus = circleMap(edgeFrac);\n"
            + "\n"
            + "    if (uGlassMode > 1.5) {\n"
            + "        float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n"
            + "        half3 matte = (uDarkTheme > 0.5)\n"
            + "            ? mix(half3(0.14, 0.16, 0.22), half3(0.09, 0.11, 0.15), half(yNorm))\n"
            + "            : mix(half3(0.95, 0.97, 1.00), half3(0.89, 0.92, 0.97), half(yNorm));\n"
            + "        matte = mix(matte, half3(uTintColorR, uTintColorG, uTintColorB), half(uTintStrength * 0.45));\n"
            + "        float ndotlM = dot(grad, float2(-0.5592, -0.8290));\n"
            + "        float rimM = exp(-pow((d + 1.0 * uDensity) / (1.2 * uDensity), 2.0)) * (0.12 + 0.26 * max(ndotlM, 0.0));\n"
            + "        matte = clamp(matte + half3(rimM), half3(0.0), half3(1.0));\n"
            + "        return half4(matte * half(aa), half(aa));\n"
            + "    }\n"
            + "\n"
            + "    float modeMul = (uGlassMode > 0.5) ? 0.52 : 1.0;\n"
            + "    float refractPx = (22.0 * uDensity) * uRefractScale * modeMul;\n"
            + "    float2 domeDir = rel / max(halfSize, float2(12.0));\n"
            + "    float2 offset = (-grad * meniscus * refractPx) - (domeDir * 2.0 * uDensity * uRefractScale);\n"
            + "\n"
            + "    if (uTouchAmp > 0.005) {\n"
            + "        float2 tp = fragCoord - uTouchPos;\n"
            + "        float tr = length(tp);\n"
            + "        float sigma = 68.0 * uDensity;\n"
            + "        float bump = uTouchAmp * exp(-(tr * tr) / max(sigma * sigma, 1.0));\n"
            + "        if (tr > 1.0) offset -= (tp / tr) * (bump * 16.0 * uDensity * uRefractScale);\n"
            + "    }\n"
            + "\n"
            + "    float2 baseScreen = uScreenOrigin + fragCoord + offset;\n"
            + "    float dispPx = (3.2 * uDensity) * meniscus * uRefractScale * modeMul;\n"
            + "    float2 dispVec = grad * dispPx;\n"
            + "\n"
            + "    half3 sharpSpec;\n"
            + "    sharpSpec.r = uSharpBg.eval(toBuf(baseScreen + dispVec, uSharpBufSize)).r * half(0.62)\n"
            + "                + uSharpBg.eval(toBuf(baseScreen + dispVec * 0.35, uSharpBufSize)).r * half(0.38);\n"
            + "    sharpSpec.g = uSharpBg.eval(toBuf(baseScreen, uSharpBufSize)).g;\n"
            + "    sharpSpec.b = uSharpBg.eval(toBuf(baseScreen - dispVec, uSharpBufSize)).b * half(0.62)\n"
            + "                + uSharpBg.eval(toBuf(baseScreen - dispVec * 0.35, uSharpBufSize)).b * half(0.38);\n"
            + "\n"
            + "    half3 softCol = uSoftBg.eval(toBuf(baseScreen, uSoftBufSize)).rgb;\n"
            + "    float frostMix = clamp(uFrostScale * ((uGlassMode > 0.5) ? 0.92 : 0.68), 0.0, 1.0);\n"
            + "    frostMix = frostMix * (1.0 - 0.35 * meniscus);\n"
            + "    half3 body = mix(sharpSpec, softCol, half(frostMix));\n"
            + "\n"
            + "    // Apple vibrancy boost\n"
            + "    half lum = dot(body, half3(0.2126, 0.7152, 0.0722));\n"
            + "    body = clamp(mix(half3(lum), body, half(1.22)), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    // Adaptive obsidian / crystal sheen\n"
            + "    half3 veil = (uDarkTheme > 0.5) ? half3(0.10, 0.13, 0.19) : half3(0.96, 0.98, 1.00);\n"
            + "    float veilAmt = (uDarkTheme > 0.5) ? (0.34 + 0.22 * frostMix) : (0.28 + 0.26 * frostMix);\n"
            + "    body = mix(body, veil, half(clamp(veilAmt, 0.0, 0.78)));\n"
            + "\n"
            + "    if (uTintStrength > 0.005) {\n"
            + "        body = mix(body, half3(uTintColorR, uTintColorG, uTintColorB), half(uTintStrength));\n"
            + "    }\n"
            + "\n"
            + "    // 34-deg directional Fresnel specular catchlight & crisp top-left inner bevel\n"
            + "    float ndotl = dot(grad, float2(-0.5592, -0.8290));\n"
            + "    float ndotlOpp = dot(grad, float2(0.5592, 0.8290));\n"
            + "    float outerRim = exp(-pow((d + 0.9 * uDensity) / (1.15 * uDensity), 2.0));\n"
            + "    float spec = outerRim * (0.16 + 0.46 * max(ndotl, 0.0) + 0.18 * max(ndotlOpp, 0.0));\n"
            + "    float topGlow = (1.0 - clamp(fragCoord.y / max(uSize.y * 0.42, 1.0), 0.0, 1.0)) * 0.08;\n"
            + "    body = clamp(body + half3(spec + topGlow), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    return half4(body * half(aa), half(aa));\n"
            + "}\n";

    /**
     * Polynomial `sminPoly` liquid-merging shader for the iOS 26 Bottom Morphing Tab Bar & Dynamic Island.
     */
    private static final String MORPH_PILL_BAR_AGSL = ""
            + "uniform shader uSharpBg;\n"
            + "uniform shader uSoftBg;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uSharpBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float  uDensity;\n"
            + "uniform float  uDarkTheme;\n"
            + "uniform float  uRefractScale;\n"
            + "uniform float  uFrostScale;\n"
            + "uniform float4 uMainRect;\n"      // cx, cy, halfW, halfH
            + "uniform float4 uOrbRect;\n"       // cx, cy, halfW, halfH (optional detached/merging orb; halfW<=0 disables)
            + "uniform float4 uIndicatorRect;\n" // cx, cy, halfW, halfH (sliding liquid droplet inside bar)
            + "uniform float  uSminK;\n"
            + "\n"
            + "float sdPill(float2 p, float2 halfSize) {\n"
            + "    float r = min(halfSize.x, halfSize.y);\n"
            + "    float2 q = abs(p) - (halfSize - float2(r));\n"
            + "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "float sminPoly(float a, float b, float k) {\n"
            + "    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 0.001), 0.0, 1.0);\n"
            + "    return mix(b, a, h) - k * h * (1.0 - h);\n"
            + "}\n"
            + "\n"
            + "float sceneSdf(float2 p) {\n"
            + "    float d = sdPill(p - uMainRect.xy, uMainRect.zw);\n"
            + "    if (uOrbRect.z > 1.0) {\n"
            + "        float dOrb = sdPill(p - uOrbRect.xy, uOrbRect.zw);\n"
            + "        d = sminPoly(d, dOrb, uSminK);\n"
            + "    }\n"
            + "    return d;\n"
            + "}\n"
            + "\n"
            + "float2 toBuf(float2 screenPx, float2 bufSize) {\n"
            + "    float2 uv = clamp(screenPx / max(uScreenSize, float2(2.0)), float2(0.002), float2(0.998));\n"
            + "    return uv * bufSize;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float d = sceneSdf(fragCoord);\n"
            + "    float aa = 1.0 - smoothstep(-0.85, 0.75, d);\n"
            + "    if (aa <= 0.002) return half4(0.0);\n"
            + "\n"
            + "    float eps = 1.2;\n"
            + "    float dx = sceneSdf(fragCoord + float2(eps, 0.0)) - sceneSdf(fragCoord - float2(eps, 0.0));\n"
            + "    float dy = sceneSdf(fragCoord + float2(0.0, eps)) - sceneSdf(fragCoord - float2(0.0, eps));\n"
            + "    float2 grad = float2(dx, dy) / max(length(float2(dx, dy)), 0.0001);\n"
            + "\n"
            + "    float bevel = 1.0 - clamp(-d / (16.0 * uDensity), 0.0, 1.0);\n"
            + "    float meniscus = 1.0 - sqrt(max(1.0 - bevel * bevel, 0.0));\n"
            + "    float2 offset = -grad * meniscus * (20.0 * uDensity * uRefractScale);\n"
            + "    float2 baseScreen = uScreenOrigin + fragCoord + offset;\n"
            + "\n"
            + "    half3 sharp = uSharpBg.eval(toBuf(baseScreen, uSharpBufSize)).rgb;\n"
            + "    half3 soft = uSoftBg.eval(toBuf(baseScreen, uSoftBufSize)).rgb;\n"
            + "    half3 col = mix(sharp, soft, half(clamp(uFrostScale * 0.72, 0.0, 1.0)));\n"
            + "    half3 veil = (uDarkTheme > 0.5) ? half3(0.11, 0.14, 0.20) : half3(0.95, 0.97, 1.00);\n"
            + "    col = mix(col, veil, half((uDarkTheme > 0.5) ? 0.44 : 0.38));\n"
            + "\n"
            + "    if (uIndicatorRect.z > 1.0) {\n"
            + "        float dInd = sdPill(fragCoord - uIndicatorRect.xy, uIndicatorRect.zw);\n"
            + "        float indMask = 1.0 - smoothstep(-0.8, 0.8, dInd);\n"
            + "        if (indMask > 0.002) {\n"
            + "            half3 indTint = (uDarkTheme > 0.5) ? half3(0.24, 0.30, 0.42) : half3(1.0, 1.0, 1.0);\n"
            + "            col = mix(col, indTint, half(indMask * ((uDarkTheme > 0.5) ? 0.58 : 0.65)));\n"
            + "            float indRim = exp(-pow((dInd + 0.8 * uDensity) / (1.1 * uDensity), 2.0)) * 0.28;\n"
            + "            col = clamp(col + half3(indRim * indMask), half3(0.0), half3(1.0));\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    float ndotl = dot(grad, float2(-0.5592, -0.8290));\n"
            + "    float rim = exp(-pow((d + 0.9 * uDensity) / (1.15 * uDensity), 2.0)) * (0.20 + 0.44 * max(ndotl, 0.0));\n"
            + "    col = clamp(col + half3(rim), half3(0.0), half3(1.0));\n"
            + "    return half4(col * half(aa), half(aa));\n"
            + "}\n";

    private SamuraiAgslEngine() {}

    public static final class BackdropPair {
        public final Bitmap sharpBitmap;
        public final Bitmap softBitmap;
        public final BitmapShader sharpShader;
        public final BitmapShader softShader;
        public final int sharpW;
        public final int sharpH;
        public final int softW;
        public final int softH;

        public BackdropPair(Bitmap sharp, Bitmap soft) {
            this.sharpBitmap = sharp;
            this.softBitmap = soft;
            this.sharpW = Math.max(1, sharp.getWidth());
            this.sharpH = Math.max(1, sharp.getHeight());
            this.softW = Math.max(1, soft.getWidth());
            this.softH = Math.max(1, soft.getHeight());
            this.sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    private static volatile BackdropPair sCachedDarkPair;
    private static volatile BackdropPair sCachedLightPair;
    private static volatile BackdropPair sCachedLiveWallpaperPair;
    private static volatile long sLiveWallpaperStampMs = 0L;

    public static RuntimeShader createSurfaceShader() {
        return new RuntimeShader(SURFACE_GLASS_AGSL);
    }

    public static RuntimeShader createMorphPillBarShader() {
        return new RuntimeShader(MORPH_PILL_BAR_AGSL);
    }

    /**
     * Binds uniforms on a `SURFACE_GLASS_AGSL` RuntimeShader for the given `view` on screen.
     */
    public static void bindSurfaceShader(
            RuntimeShader shader,
            View view,
            int[] tmpLoc,
            BackdropPair bg,
            SamuraiGlassPrefs.Snapshot prefs,
            float cornerRadiusPx,
            float touchX,
            float touchY,
            float touchAmp,
            int tintArgb,
            float tintStrength) {
        if (shader == null || view == null || bg == null || prefs == null) return;
        float w = Math.max(1f, view.getWidth());
        float h = Math.max(1f, view.getHeight());
        view.getLocationOnScreen(tmpLoc);
        float density = view.getResources().getDisplayMetrics().density;
        float screenW = Math.max(1f, view.getResources().getDisplayMetrics().widthPixels);
        float screenH = Math.max(1f, view.getResources().getDisplayMetrics().heightPixels);

        shader.setInputShader("uSharpBg", bg.sharpShader);
        shader.setInputShader("uSoftBg", bg.softShader);
        shader.setFloatUniform("uSize", w, h);
        shader.setFloatUniform("uScreenOrigin", (float) tmpLoc[0], (float) tmpLoc[1]);
        shader.setFloatUniform("uScreenSize", screenW, screenH);
        shader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        shader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        shader.setFloatUniform("uRadius", Math.max(2f, cornerRadiusPx));
        shader.setFloatUniform("uDensity", density);
        shader.setFloatUniform("uGlassMode", (float) prefs.glassMode);
        shader.setFloatUniform("uRefractScale", prefs.refractScale());
        shader.setFloatUniform("uFrostScale", prefs.frostScale());
        shader.setFloatUniform("uDarkTheme", prefs.darkTheme ? 1.0f : 0.0f);
        shader.setFloatUniform("uTouchPos", touchX, touchY);
        shader.setFloatUniform("uTouchAmp", touchAmp);
        float tr = ((tintArgb >> 16) & 0xFF) / 255f;
        float tg = ((tintArgb >> 8) & 0xFF) / 255f;
        float tb = (tintArgb & 0xFF) / 255f;
        shader.setFloatUniform("uTintColorR", tr);
        shader.setFloatUniform("uTintColorG", tg);
        shader.setFloatUniform("uTintColorB", tb);
        shader.setFloatUniform("uTintStrength", tintStrength);
    }

    /**
     * Attempts to sample the real system wallpaper if caller holds `READ_WALLPAPER_INTERNAL`
     * (`SystemUI`, `NexusLauncherRelease`, `IosSettings`), falling back cleanly to the calibrated
     * iOS 26 procedural Aurora/Petal backdrop for unprivileged apps.
     */
    public static BackdropPair getSystemOrProceduralBackdrop(Context ctx, boolean darkTheme) {
        long now = System.currentTimeMillis();
        if (sCachedLiveWallpaperPair != null && (now - sLiveWallpaperStampMs) < 8_000L) {
            return sCachedLiveWallpaperPair;
        }
        if (ctx != null) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                if (wm != null) {
                    Drawable d = wm.getDrawable();
                    if (d instanceof BitmapDrawable) {
                        Bitmap src = ((BitmapDrawable) d).getBitmap();
                        if (src != null && !src.isRecycled() && src.getWidth() > 16) {
                            Bitmap sharp = Bitmap.createScaledBitmap(src, 270, 600, true);
                            Bitmap soft = boxBlurFast(sharp, 10);
                            BackdropPair pair = new BackdropPair(sharp, soft);
                            sCachedLiveWallpaperPair = pair;
                            sLiveWallpaperStampMs = now;
                            return pair;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Unprivileged apps without READ_WALLPAPER_INTERNAL fall through cleanly
            }
        }
        return getProceduralBackdrop(darkTheme);
    }

    public static void invalidateWallpaperCache() {
        sCachedLiveWallpaperPair = null;
        sLiveWallpaperStampMs = 0L;
    }

    public static BackdropPair getProceduralBackdrop(boolean darkTheme) {
        if (darkTheme) {
            if (sCachedDarkPair == null) {
                sCachedDarkPair = buildProceduralPair(true);
            }
            return sCachedDarkPair;
        } else {
            if (sCachedLightPair == null) {
                sCachedLightPair = buildProceduralPair(false);
            }
            return sCachedLightPair;
        }
    }

    private static BackdropPair buildProceduralPair(boolean dark) {
        int w = 270;
        int h = 600;
        Bitmap sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sharp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        int cTop = dark ? 0xFF070B18 : 0xFFDCEBFF;
        int cMid = dark ? 0xFF0E1A36 : 0xFFEAF3FF;
        int cBot = dark ? 0xFF080D1C : 0xFFF4F7FC;
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[] { cTop, cMid, cBot }, new float[] { 0f, 0.52f, 1f },
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // Luminous iOS 26 liquid petals so bevels & ROYGCBP dispersion visibly bend chromatic light
        drawOrb(c, w * 0.22f, h * 0.20f, w * 0.62f, dark ? 0x552E7DFF : 0x665AC8FA);
        drawOrb(c, w * 0.80f, h * 0.36f, w * 0.56f, dark ? 0x487B42F6 : 0x5564D2FF);
        drawOrb(c, w * 0.30f, h * 0.72f, w * 0.64f, dark ? 0x4200C7BE : 0x528E8EFF);
        drawOrb(c, w * 0.78f, h * 0.84f, w * 0.50f, dark ? 0x3A30D158 : 0x48AC8E68);

        Bitmap soft = boxBlurFast(sharp, 11);
        return new BackdropPair(sharp, soft);
    }

    private static void drawOrb(Canvas c, float cx, float cy, float r, int centerColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, r,
                new int[] { centerColor, centerColor & 0x00FFFFFF },
                new float[] { 0f, 1f },
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    public static Bitmap boxBlurFast(Bitmap src, int radius) {
        Bitmap small = Bitmap.createScaledBitmap(
                src,
                Math.max(32, src.getWidth() / 2),
                Math.max(64, src.getHeight() / 2),
                true);
        int w = small.getWidth();
        int h = small.getHeight();
        int[] pix = new int[w * h];
        int[] tmp = new int[w * h];
        small.getPixels(pix, 0, w, 0, 0, w, h);
        int r = Math.max(1, radius / 2);

        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int sr = 0, sg = 0, sb = 0, cnt = 0;
                    for (int k = -r; k <= r; k++) {
                        int nx = Math.max(0, Math.min(w - 1, x + k));
                        int col = pix[row + nx];
                        sr += (col >> 16) & 0xFF;
                        sg += (col >> 8) & 0xFF;
                        sb += col & 0xFF;
                        cnt++;
                    }
                    tmp[row + x] = 0xFF000000 | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
                }
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int sr = 0, sg = 0, sb = 0, cnt = 0;
                    for (int k = -r; k <= r; k++) {
                        int ny = Math.max(0, Math.min(h - 1, y + k));
                        int col = tmp[ny * w + x];
                        sr += (col >> 16) & 0xFF;
                        sg += (col >> 8) & 0xFF;
                        sb += col & 0xFF;
                        cnt++;
                    }
                    pix[y * w + x] = 0xFF000000 | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
                }
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        if (small != src) {
            small.recycle();
        }
        return out;
    }

    public static void drawProceduralAppBackground(Canvas canvas, int w, int h, boolean darkTheme) {
        BackdropPair pair = getProceduralBackdrop(darkTheme);
        Matrix m = new Matrix();
        m.setScale(w / (float) pair.sharpW, h / (float) pair.sharpH);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(pair.sharpBitmap, m, p);
    }
}
