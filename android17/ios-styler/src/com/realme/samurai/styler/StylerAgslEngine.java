/*
 * Clean-Room 90Hz AGSL Liquid Glass Optical Engine for Samurai Android 17 (`iOS Styler`).
 *
 * Open-Source Shader Math Attributions & Licenses:
 * 1. Spherical meniscus `circleMap(x) = 1.0 - sqrt(1.0 - x*x)` bevel refraction profile &
 *    7-band ROYGCBP spectral chromatic dispersion adapted from `Kyant0/AndroidLiquidGlass`
 *    (Copyright 2025 Kyant, Apache License, Version 2.0 — http://www.apache.org/licenses/LICENSE-2.0).
 * 2. Smooth polynomial liquid shape fusion (`sminPoly`), Gaussian touch-bulge lens magnification,
 *    and highlight-protected non-linear Apple vibrancy saturation adapted from
 *    `QWEA0/Liquid-Glass-Android` (Copyright 2025-2026 pandadog, MIT License).
 * 3. Analytic Adreno-640-safe G2 superellipse SDF (`n = 4.6`, guarded `pow(max(q, 0.001), 4.6)`)
 *    and 34-degree Schlick Fresnel directional catchlights tailored for Realme X2 Pro (RMX1931).
 */
package com.realme.samurai.styler;

import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.view.View;

/**
 * Provides two hardware-accelerated 90Hz AGSL shader pipelines:
 *  1) `PanelSurfaceShader` — bound to `Paint` on EVERY `LiquidGlassCardView`, `LiquidGlassSegmentedControl`,
 *     and glass pill so every surface in `iOS Styler` refracts, disperses (7-band ROYGCBP), blurs,
 *     and touch-bulges the live screen-space iOS 26 backdrop with zero flat Material cards.
 *  2) `HomeStageShader` — bound via `RenderEffect.createRuntimeShaderEffect(..., "content")` on the
 *     Live Interactive iOS 26 Home Preview Card so the Dock, `"Q Search"` capsule, and draggable
 *     Loupe Lens refract & `sminPoly`-merge over live Home Screen icons & clock numerals at 90Hz.
 */
public final class StylerAgslEngine {

    /**
     * Screen-space AGSL Liquid Glass shader for cards, segmented controls, pills, and droplet thumbs.
     */
    private static final String PANEL_SURFACE_AGSL = ""
            + "uniform shader uSharpBg;\n"
            + "uniform shader uSoftBg;\n"
            + "uniform float2 uSize;\n"
            + "uniform float2 uScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uSharpBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float  uRadius;\n"
            + "uniform float  uDensity;\n"
            + "uniform float  uGlassMode;\n"      // 0=Clear Refractive, 1=Frosted Tinted, 2=Solid Matte
            + "uniform float  uRefractScale;\n"   // 0.0 .. 1.5 (from samurai_ios26_refraction_pct)
            + "uniform float  uFrostScale;\n"     // 0.0 .. 1.5 (from samurai_ios26_frost_pct)
            + "uniform float  uDarkTheme;\n"      // 1.0 = Dark backdrop/UI, 0.0 = Bright
            + "uniform float2 uTouchPos;\n"       // Local touch coords
            + "uniform float  uTouchAmp;\n"       // 0.0 .. 1.0 spring touch bulge
            + "uniform float  uPhase;\n"          // Subtle live aurora drift phase
            + "uniform float  uTintBoost;\n"      // Extra active-pill luminosity boost
            + "uniform float  uWpBrightness;\n"   // Effective wallpaper transmission (0.0 .. 1.0)
            + "\n"
            + "float sdSuperellipse(float2 p, float2 halfSize, float r) {\n"
            + "    float minH = min(halfSize.x, halfSize.y);\n"
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
            + "float circleMap(float x) {\n"
            + "    float cx = clamp(x, 0.0, 1.0);\n"
            + "    return 1.0 - sqrt(max(1.0 - cx * cx, 0.0));\n"
            + "}\n"
            + "\n"
            + "float2 toBgCoord(float2 screenPx, float2 bufSize) {\n"
            + "    float bleed = 10.0 * uDensity;\n"
            + "    float dx = sin(uPhase * 0.7) * 6.0 * uDensity;\n"
            + "    float dy = cos(uPhase * 0.5) * 5.0 * uDensity;\n"
            + "    float waveX = sin(uPhase * 0.7 + screenPx.y * 0.0025) * 2.5 * uDensity * uRefractScale;\n"
            + "    float waveY = cos(uPhase * 0.5 + screenPx.x * 0.0025) * 2.0 * uDensity * uRefractScale;\n"
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
            + "    half amt = half(1.0) + (satFactor - half(1.0)) * mix(half(0.32), half(1.0), room * hl);\n"
            + "    return clamp(mix(half3(lum), c, amt), half3(0.0), half3(1.0));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 halfSize = uSize * 0.5;\n"
            + "    float2 rel = fragCoord - halfSize;\n"
            + "    float d = sdSuperellipse(rel, halfSize, uRadius);\n"
            + "    float aa = 1.0 - smoothstep(-0.85, 0.75, d);\n"
            + "    if (aa <= 0.002) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "\n"
            + "    float eps = 1.2;\n"
            + "    float dx = sdSuperellipse(rel + float2(eps, 0.0), halfSize, uRadius) - sdSuperellipse(rel - float2(eps, 0.0), halfSize, uRadius);\n"
            + "    float dy = sdSuperellipse(rel + float2(0.0, eps), halfSize, uRadius) - sdSuperellipse(rel - float2(0.0, eps), halfSize, uRadius);\n"
            + "    float2 grad = float2(dx, dy) / max(length(float2(dx, dy)), 0.0001);\n"
            + "\n"
            + "    float bevelH = clamp(min(halfSize.x, halfSize.y) * 0.34, 10.0 * uDensity, 28.0 * uDensity);\n"
            + "    float edgeFrac = 1.0 - clamp(-d / max(bevelH, 1.0), 0.0, 1.0);\n"
            + "    float meniscus = circleMap(edgeFrac);\n"
            + "\n"
            + "    if (uGlassMode > 1.5) {\n"
            + "        // Mode 2: Solid Matte iOS Plate (subtle satin bevel + crisp 34-deg rim, zero transparency)\n"
            + "        float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n"
            + "        half3 matte = (uDarkTheme > 0.5)\n"
            + "            ? mix(half3(0.14, 0.17, 0.24), half3(0.09, 0.11, 0.16), half(yNorm))\n"
            + "            : mix(half3(0.95, 0.97, 1.00), half3(0.88, 0.92, 0.97), half(yNorm));\n"
            + "        matte = clamp(matte + half3(uTintBoost * 0.12), half3(0.0), half3(1.0));\n"
            + "        float ndotlM = dot(grad, float2(-0.5592, -0.8290));\n"
            + "        float rimM = exp(-pow((d + 1.0 * uDensity) / (1.2 * uDensity), 2.0)) * (0.10 + 0.28 * max(ndotlM, 0.0));\n"
            + "        matte = clamp(matte + half3(rimM), half3(0.0), half3(1.0));\n"
            + "        return half4(matte * half(aa), half(aa));\n"
            + "    }\n"
            + "\n"
            + "    // Kyant0 spherical meniscus Snell refraction + QWEA0 touch liquid bulge\n"
            + "    float modeRefractMul = (uGlassMode > 0.5) ? 0.52 : 1.0;\n"
            + "    float refractPx = (24.0 * uDensity) * uRefractScale * modeRefractMul;\n"
            + "    float2 domeDir = rel / max(halfSize, float2(12.0));\n"
            + "    float2 offset = (-grad * meniscus * refractPx) - (domeDir * 2.2 * uDensity * uRefractScale);\n"
            + "\n"
            + "    if (uTouchAmp > 0.005) {\n"
            + "        float2 tp = fragCoord - uTouchPos;\n"
            + "        float tr = length(tp);\n"
            + "        float sigma = 72.0 * uDensity;\n"
            + "        float bump = uTouchAmp * exp(-(tr * tr) / max(sigma * sigma, 1.0));\n"
            + "        if (tr > 1.0) {\n"
            + "            offset -= (tp / tr) * (bump * 18.0 * uDensity * uRefractScale);\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    float2 baseScreen = uScreenOrigin + fragCoord + offset;\n"
            + "\n"
            + "    // 7-band ROYGCBP spectral chromatic dispersion (Kyant0 Apache-2.0 optical model)\n"
            + "    float dispPx = (3.4 * uDensity) * meniscus * uRefractScale * ((uGlassMode > 0.5) ? 0.42 : 1.0);\n"
            + "    float2 dispVec = grad * dispPx;\n"
            + "\n"
            + "    half3 sharpSpec = half3(0.0);\n"
            + "    sharpSpec.r += uSharpBg.eval(toBgCoord(baseScreen + dispVec, uSharpBufSize)).r * half(0.2857);\n"
            + "    half3 cOrg = uSharpBg.eval(toBgCoord(baseScreen + dispVec * 0.6667, uSharpBufSize)).rgb;\n"
            + "    sharpSpec.r += cOrg.r * half(0.2857);\n"
            + "    sharpSpec.g += cOrg.g * half(0.1429);\n"
            + "    half3 cYel = uSharpBg.eval(toBgCoord(baseScreen + dispVec * 0.3333, uSharpBufSize)).rgb;\n"
            + "    sharpSpec.r += cYel.r * half(0.2857);\n"
            + "    sharpSpec.g += cYel.g * half(0.2857);\n"
            + "    sharpSpec.g += uSharpBg.eval(toBgCoord(baseScreen, uSharpBufSize)).g * half(0.2857);\n"
            + "    half3 cCyn = uSharpBg.eval(toBgCoord(baseScreen - dispVec * 0.3333, uSharpBufSize)).rgb;\n"
            + "    sharpSpec.g += cCyn.g * half(0.2857);\n"
            + "    sharpSpec.b += cCyn.b * half(0.3333);\n"
            + "    sharpSpec.b += uSharpBg.eval(toBgCoord(baseScreen - dispVec * 0.6667, uSharpBufSize)).b * half(0.3333);\n"
            + "    half3 cPur = uSharpBg.eval(toBgCoord(baseScreen - dispVec, uSharpBufSize)).rgb;\n"
            + "    sharpSpec.r += cPur.r * half(0.1429);\n"
            + "    sharpSpec.b += cPur.b * half(0.3333);\n"
            + "    sharpSpec *= half(uWpBrightness);\n"
            + "\n"
            + "    half3 softCol = uSoftBg.eval(toBgCoord(baseScreen, uSoftBufSize)).rgb * half(uWpBrightness);\n"
            + "\n"
            + "    // True water-clear optical crystal at Frost=0% (frostMix == 0.0 -> 100% 540x1200 sharpSpec)\n"
            + "    float frostFactor = clamp(uFrostScale, 0.0, 1.35);\n"
            + "    float baseFrost = (uGlassMode > 0.5) ? 0.90 : 0.56;\n"
            + "    float frostMix = clamp(baseFrost * uFrostScale, 0.0, 1.0) * (1.0 - 0.52 * meniscus);\n"
            + "    half3 glass = mix(sharpSpec, softCol, half(frostMix));\n"
            + "    float satAmt = 1.0 + 0.16 * clamp(max(uFrostScale, uRefractScale), 0.0, 1.0);\n"
            + "    glass = applyVibrancy(glass, half(satAmt));\n"
            + "\n"
            + "    // Adaptive iOS 26 frost veil scales strictly to 0.0 at Frost=0% (zero milky haze)\n"
            + "    float localLum = float(dot(glass, half3(0.2126, 0.7152, 0.0722)));\n"
            + "    float pocketLift = ((uDarkTheme > 0.5)\n"
            + "        ? (0.16 * smoothstep(0.54, 0.88, localLum))\n"
            + "        : (0.20 * (1.0 - smoothstep(0.16, 0.52, localLum)))) * clamp(uFrostScale, 0.0, 1.0);\n"
            + "    half3 veilCol = (uDarkTheme > 0.5)\n"
            + "        ? ((uGlassMode > 0.5) ? half3(0.08, 0.12, 0.20) : half3(0.10, 0.16, 0.26))\n"
            + "        : ((uGlassMode > 0.5) ? half3(0.92, 0.96, 1.00) : half3(0.96, 0.99, 1.00));\n"
            + "    float veilBase = (uGlassMode > 0.5)\n"
            + "        ? ((uDarkTheme > 0.5) ? 0.54 : 0.58)\n"
            + "        : ((uDarkTheme > 0.5) ? 0.20 : 0.18);\n"
            + "    float boostVeil = max(uTintBoost, 0.0) * (0.06 + 0.14 * clamp(uFrostScale, 0.0, 1.0));\n"
            + "    float veilAlpha = clamp(veilBase * frostFactor + pocketLift + boostVeil, 0.0, 0.84);\n"
            + "    glass = mix(glass, veilCol, half(veilAlpha));\n"
            + "    if (uTintBoost < -0.001) {\n"
            + "        glass *= half(1.0 + uTintBoost * 0.45);\n"
            + "    }\n"
            + "\n"
            + "    // Top-to-bottom internal sheen also scales to 0.0 at Frost=0%\n"
            + "    float yNorm = clamp(fragCoord.y / max(uSize.y, 1.0), 0.0, 1.0);\n"
            + "    float topSheen = (1.0 - yNorm) * (0.032 * frostFactor + 0.04 * max(uTintBoost, 0.0));\n"
            + "    glass = clamp(glass + half3(topSheen), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    float2 lightTL = float2(-0.5592, -0.8290);\n"
            + "    float ndotl = dot(grad, lightTL);\n"
            + "    float caustic = exp(-pow((d + bevelH * 0.36) / max(bevelH * 0.26, 2.0), 2.0));\n"
            + "    float causticStr = 0.065 * caustic * (0.25 + 0.65 * max(ndotl, 0.0) + 0.30 * max(-ndotl, 0.0)) * clamp(uRefractScale, 0.0, 1.5);\n"
            + "    glass = clamp(glass + half3(causticStr), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    if (uTouchAmp > 0.005) {\n"
            + "        float tr = length(fragCoord - uTouchPos);\n"
            + "        float radialGlow = uTouchAmp * 0.14 * exp(-(tr * tr) / max(pow(84.0 * uDensity, 2.0), 1.0));\n"
            + "        glass = clamp(glass + half3(radialGlow * 0.85, radialGlow * 0.95, radialGlow), half3(0.0), half3(1.0));\n"
            + "    }\n"
            + "\n"
            + "    // Hair-fine 34-deg Schlick Fresnel specular catchlight (TL primary + BR bounce)\n"
            + "    float rimLine = exp(-pow((d + 1.05 * uDensity) / (1.15 * uDensity), 2.0));\n"
            + "    float specTL = pow(max(ndotl, 0.0001), 1.50);\n"
            + "    float specBR = pow(max(-ndotl, 0.0001), 2.10);\n"
            + "    float rimGlow = rimLine * (0.11 + 0.56 * specTL + 0.36 * specBR + 0.16 * uTintBoost);\n"
            + "    glass = clamp(glass + half3(rimGlow), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    return half4(glass * half(aa), half(aa));\n"
            + "}\n";

    /**
     * Real-time `RenderEffect` AGSL shader for the Live Interactive iOS 26 Home Preview Stage:
     * Simultaneously refracts & `sminPoly`-fuses:
     *  1) Lifted iOS 26 Liquid Glass Dock (`uDockRect`)
     *  2) Floating `"Q Search"` Spotlight Capsule (`uPillRect`, scaled by `uPillWeight`)
     *  3) Interactive Draggable Liquid Glass Loupe Lens (`uLoupeRect`, `uEnergize`)
     */
    private static final String PREVIEW_STAGE_AGSL = ""
            + "uniform shader content;\n"
            + "uniform float2 uRes;\n"
            + "uniform float4 uDockRect;\n"       // cx, cy, halfW, halfH
            + "uniform float  uDockRadius;\n"
            + "uniform float4 uPillRect;\n"       // cx, cy, halfW, halfH
            + "uniform float  uPillWeight;\n"     // 0.0 .. 1.0
            + "uniform float4 uLoupeRect;\n"      // cx, cy, halfW, halfH
            + "uniform float  uLoupeRadius;\n"
            + "uniform float  uGlassMode;\n"      // 0=Clear, 1=Tinted, 2=Solid Matte
            + "uniform float  uRefractScale;\n"   // 0.0 .. 1.5
            + "uniform float  uFrostScale;\n"     // 0.0 .. 1.5
            + "uniform float  uDarkTheme;\n"
            + "uniform float  uEnergize;\n"
            + "uniform float  uDensity;\n"
            + "\n"
            + "float sdSuperellipse(float2 p, float2 halfSize, float r) {\n"
            + "    float minH = min(halfSize.x, halfSize.y);\n"
            + "    float cr = clamp(r, 1.5, max(minH, 1.5));\n"
            + "    float2 q = abs(p) - (halfSize - float2(cr));\n"
            + "    float inside = min(max(q.x, q.y), 0.0);\n"
            + "    if (q.x <= 0.0 && q.y <= 0.0) return inside - cr;\n"
            + "    float2 mq = max(q, float2(0.001));\n"
            + "    return pow(pow(mq.x, 4.6) + pow(mq.y, 4.6), 0.2173913) + inside - cr;\n"
            + "}\n"
            + "\n"
            + "float sminPoly(float a, float b, float k) {\n"
            + "    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 0.001), 0.0, 1.0);\n"
            + "    return mix(b, a, h) - k * h * (1.0 - h);\n"
            + "}\n"
            + "\n"
            + "float circleMap(float x) {\n"
            + "    float cx = clamp(x, 0.0, 1.0);\n"
            + "    return 1.0 - sqrt(max(1.0 - cx * cx, 0.0));\n"
            + "}\n"
            + "\n"
            + "float sceneGlassSdf(float2 p) {\n"
            + "    float dDock = sdSuperellipse(p - uDockRect.xy, uDockRect.zw, uDockRadius);\n"
            + "    float dLoupe = sdSuperellipse(p - uLoupeRect.xy, uLoupeRect.zw, uLoupeRadius);\n"
            + "    float d = sminPoly(dDock, dLoupe, 14.0 * uDensity);\n"
            + "    if (uPillWeight > 0.02) {\n"
            + "        float2 pillHalf = uPillRect.zw * float2(0.6 + 0.4 * uPillWeight, uPillWeight);\n"
            + "        float dPill = sdSuperellipse(p - uPillRect.xy, max(pillHalf, float2(2.0)), pillHalf.y);\n"
            + "        d = sminPoly(d, dPill, 10.0 * uDensity * uPillWeight);\n"
            + "    }\n"
            + "    return d;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    half4 base = content.eval(fragCoord);\n"
            + "    float d = sceneGlassSdf(fragCoord);\n"
            + "\n"
            + "    // Soft exterior contact shadow around fused Liquid Glass bodies\n"
            + "    if (d > 0.4 && d < 18.0 * uDensity) {\n"
            + "        float dSh = sceneGlassSdf(fragCoord - float2(1.0 * uDensity, 4.5 * uDensity));\n"
            + "        float sh = (1.0 - smoothstep(0.0, 14.0 * uDensity, max(dSh, 0.0))) * smoothstep(-0.8, 1.0, d);\n"
            + "        base.rgb *= half(1.0 - 0.18 * sh);\n"
            + "    }\n"
            + "    if (d >= 0.85) {\n"
            + "        return base;\n"
            + "    }\n"
            + "\n"
            + "    float aa = 1.0 - smoothstep(-0.85, 0.85, d);\n"
            + "    float eps = 1.2;\n"
            + "    float dx = sceneGlassSdf(fragCoord + float2(eps, 0.0)) - sceneGlassSdf(fragCoord - float2(eps, 0.0));\n"
            + "    float dy = sceneGlassSdf(fragCoord + float2(0.0, eps)) - sceneGlassSdf(fragCoord - float2(0.0, eps));\n"
            + "    float2 grad = float2(dx, dy) / max(length(float2(dx, dy)), 0.0001);\n"
            + "\n"
            + "    float dLoupeOnly = sdSuperellipse(fragCoord - uLoupeRect.xy, uLoupeRect.zw, uLoupeRadius);\n"
            + "    float inLoupe = 1.0 - smoothstep(-4.0, 8.0, dLoupeOnly);\n"
            + "    float effMode = mix(uGlassMode, 0.0, inLoupe * 0.85);\n"
            + "\n"
            + "    if (effMode > 1.5) {\n"
            + "        half3 matte = (uDarkTheme > 0.5) ? half3(0.12, 0.15, 0.21) : half3(0.93, 0.96, 0.99);\n"
            + "        float ndotlM = dot(grad, float2(-0.5592, -0.8290));\n"
            + "        float rimM = exp(-pow((d + 1.0 * uDensity) / (1.2 * uDensity), 2.0)) * (0.12 + 0.30 * max(ndotlM, 0.0));\n"
            + "        half3 outM = clamp(matte + half3(rimM), half3(0.0), half3(1.0));\n"
            + "        return half4(mix(base.rgb, outM, half(aa)), base.a);\n"
            + "    }\n"
            + "\n"
            + "    float bevelPx = 17.5 * uDensity;\n"
            + "    float edge = 1.0 - clamp(-d / bevelPx, 0.0, 1.0);\n"
            + "    float meniscus = circleMap(edge);\n"
            + "    float refractMag = (20.0 + 7.0 * uEnergize * inLoupe) * uDensity * uRefractScale * ((effMode > 0.5) ? 0.50 : 1.0);\n"
            + "    float2 loupeRel = (fragCoord - uLoupeRect.xy) / max(uLoupeRect.zw, float2(12.0));\n"
            + "    float2 uvOff = (-grad * meniscus * refractMag) - (loupeRel * inLoupe * 3.5 * uDensity * uRefractScale);\n"
            + "\n"
            + "    float2 lo = float2(1.0, 1.0);\n"
            + "    float2 hi = max(uRes - float2(1.0, 1.0), float2(2.0, 2.0));\n"
            + "    float disp = (2.8 * uDensity) * meniscus * uRefractScale;\n"
            + "    float2 cR = clamp(fragCoord + uvOff + grad * disp, lo, hi);\n"
            + "    float2 cG = clamp(fragCoord + uvOff, lo, hi);\n"
            + "    float2 cB = clamp(fragCoord + uvOff - grad * disp, lo, hi);\n"
            + "\n"
            + "    half3 sharpDisp = half3(content.eval(cR).r, content.eval(cG).g, content.eval(cB).b);\n"
            + "    float frostBlend = clamp(((effMode > 0.5) ? 0.92 : 0.62) * uFrostScale, 0.0, 1.0) * (1.0 - 0.55 * meniscus);\n"
            + "    half3 acc = sharpDisp;\n"
            + "    if (frostBlend > 0.004) {\n"
            + "        float frostRad = ((effMode > 0.5) ? 10.5 : 5.6) * uDensity * clamp(uFrostScale, 0.0, 1.5);\n"
            + "        half3 blurAcc = sharpDisp * half(0.20);\n"
            + "        for (int i = 0; i < 8; ++i) {\n"
            + "            float fi = float(i) + 1.0;\n"
            + "            float ang = fi * 2.3999632;\n"
            + "            float rad = frostRad * sqrt(fi / 8.0);\n"
            + "            float2 suv = clamp(cG + float2(cos(ang), sin(ang)) * rad, lo, hi);\n"
            + "            blurAcc += content.eval(suv).rgb * half(0.10);\n"
            + "        }\n"
            + "        acc = mix(sharpDisp, blurAcc, half(frostBlend));\n"
            + "    }\n"
            + "\n"
            + "    half luma = dot(acc, half3(0.2126, 0.7152, 0.0722));\n"
            + "    float satAmt = 1.0 + 0.12 * clamp(max(uFrostScale, uRefractScale), 0.0, 1.0);\n"
            + "    acc = clamp(mix(half3(luma), acc, half(satAmt)), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    half3 tint = (uDarkTheme > 0.5)\n"
            + "        ? ((effMode > 0.5) ? half3(0.10, 0.14, 0.22) : half3(0.11, 0.15, 0.22))\n"
            + "        : ((effMode > 0.5) ? half3(0.94, 0.97, 1.00) : half3(0.97, 0.99, 1.00));\n"
            + "    float veilA = clamp(((effMode > 0.5) ? 0.52 : 0.14) * clamp(uFrostScale, 0.0, 1.35), 0.0, 0.78);\n"
            + "    half3 body = mix(acc, tint, half(veilA));\n"
            + "\n"
            + "    float2 lightTL = float2(-0.5592, -0.8290);\n"
            + "    float ndotl = dot(grad, lightTL);\n"
            + "    float sheen = exp(-pow((d + bevelPx * 0.35) / max(bevelPx * 0.26, 2.0), 2.0)) * 0.065 * (0.25 + 0.65 * max(ndotl, 0.0)) * clamp(uRefractScale, 0.0, 1.5);\n"
            + "    float rim = exp(-pow((d + 1.05 * uDensity) / (1.20 * uDensity), 2.0))\n"
            + "        * (0.12 + 0.62 * pow(max(ndotl, 0.0001), 1.45) + 0.40 * pow(max(-ndotl, 0.0001), 2.05))\n"
            + "        * (1.0 + 0.18 * uEnergize * inLoupe);\n"
            + "    half3 lit = clamp(body + half3(sheen + rim), half3(0.0), half3(1.0));\n"
            + "    return half4(mix(base.rgb, lit, half(aa)), base.a);\n"
            + "}\n";

    /**
     * Shared live optical state pushed at 90Hz to every Liquid Glass surface in the activity.
     */
    public static final class OpticalContext {
        public StylerWallpaperEngine.BackdropPair backdrop;
        public int glassMode = StylerPrefsBridge.GLASS_CLEAR_REFRACTIVE;
        public int iconMode = StylerPrefsBridge.ICON_DEFAULT;
        public float refractScale = StylerPrefsBridge.DEFAULT_REFRACTION_PCT / 100f;
        public float frostScale = StylerPrefsBridge.DEFAULT_FROST_PCT / 100f;
        public float wpBrightnessScale = StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT / 100f;
        public boolean searchPillEnabled = true;
        public boolean gesturePillEnabled = false;
        public float phase = 0f;
        public int screenW = 1080;
        public int screenH = 2400;
        public int bgScreenX = 0;
        public int bgScreenY = 0;

        public float getEffectiveWpTransmission() {
            int pct = Math.round(wpBrightnessScale * 100f);
            return StylerPrefsBridge.getEffectiveWallpaperTransmission(pct);
        }

        public boolean isDarkBackdrop() {
            float luma = (backdrop != null) ? backdrop.meanLuma : 0.5f;
            return (luma * getEffectiveWpTransmission()) < 0.38f;
        }
    }

    public static final class PanelShaderInstance {
        private final RuntimeShader mShader;

        public PanelShaderInstance() {
            RuntimeShader s = null;
            try {
                s = new RuntimeShader(PANEL_SURFACE_AGSL);
            } catch (Throwable ignored) {
            }
            mShader = s;
        }

        public boolean isSupported() {
            return mShader != null;
        }

        public boolean bindToPaint(
                Paint paint,
                OpticalContext opt,
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
            if (mShader == null || opt == null || opt.backdrop == null
                    || widthPx <= 1f || heightPx <= 1f) {
                return false;
            }
            try {
                mShader.setInputShader("uSharpBg", opt.backdrop.sharpShader);
                mShader.setInputShader("uSoftBg", opt.backdrop.softShader);
                mShader.setFloatUniform("uSize", widthPx, heightPx);
                mShader.setFloatUniform("uScreenOrigin",
                        screenX - opt.bgScreenX, screenY - opt.bgScreenY);
                mShader.setFloatUniform("uScreenSize",
                        Math.max(1f, opt.screenW), Math.max(1f, opt.screenH));
                mShader.setFloatUniform("uSharpBufSize",
                        (float) opt.backdrop.sharpW, (float) opt.backdrop.sharpH);
                mShader.setFloatUniform("uSoftBufSize",
                        (float) opt.backdrop.softW, (float) opt.backdrop.softH);
                mShader.setFloatUniform("uRadius", radiusPx);
                mShader.setFloatUniform("uDensity", density);
                mShader.setFloatUniform("uGlassMode", (float) opt.glassMode);
                mShader.setFloatUniform("uRefractScale", opt.refractScale);
                mShader.setFloatUniform("uFrostScale", opt.frostScale);
                mShader.setFloatUniform("uDarkTheme", opt.isDarkBackdrop() ? 1.0f : 0.0f);
                mShader.setFloatUniform("uTouchPos", touchX, touchY);
                mShader.setFloatUniform("uTouchAmp", StylerPrefsBridge.clampF(touchAmp, 0f, 1f));
                mShader.setFloatUniform("uPhase", opt.phase);
                mShader.setFloatUniform("uTintBoost", tintBoost);
                mShader.setFloatUniform("uWpBrightness", opt.getEffectiveWpTransmission());
                paint.setShader(mShader);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static final class PreviewStageShaderInstance {
        private final RuntimeShader mShader;

        public PreviewStageShaderInstance() {
            RuntimeShader s = null;
            try {
                s = new RuntimeShader(PREVIEW_STAGE_AGSL);
            } catch (Throwable ignored) {
            }
            mShader = s;
        }

        public boolean isSupported() {
            return mShader != null;
        }

        public void applyToView(
                View target,
                OpticalContext opt,
                float w,
                float h,
                float dockCx,
                float dockCy,
                float dockHw,
                float dockHh,
                float dockR,
                float pillCx,
                float pillCy,
                float pillHw,
                float pillHh,
                float pillWeight,
                float loupeCx,
                float loupeCy,
                float loupeHw,
                float loupeHh,
                float loupeR,
                float energize,
                float density) {
            if (mShader == null || opt == null || w <= 2f || h <= 2f) {
                return;
            }
            try {
                mShader.setFloatUniform("uRes", w, h);
                mShader.setFloatUniform("uDockRect", dockCx, dockCy, dockHw, dockHh);
                mShader.setFloatUniform("uDockRadius", dockR);
                mShader.setFloatUniform("uPillRect", pillCx, pillCy, pillHw, pillHh);
                mShader.setFloatUniform("uPillWeight", StylerPrefsBridge.clampF(pillWeight, 0f, 1f));
                mShader.setFloatUniform("uLoupeRect", loupeCx, loupeCy, loupeHw, loupeHh);
                mShader.setFloatUniform("uLoupeRadius", loupeR);
                mShader.setFloatUniform("uGlassMode", (float) opt.glassMode);
                mShader.setFloatUniform("uRefractScale", opt.refractScale);
                mShader.setFloatUniform("uFrostScale", opt.frostScale);
                mShader.setFloatUniform("uDarkTheme", opt.isDarkBackdrop() ? 1.0f : 0.0f);
                mShader.setFloatUniform("uEnergize", StylerPrefsBridge.clampF(energize, 0f, 1f));
                mShader.setFloatUniform("uDensity", density);
                target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(mShader, "content"));
            } catch (Throwable ignored) {
            }
        }

        public void detach(View target) {
            try {
                target.setRenderEffect(null);
            } catch (Throwable ignored) {
            }
        }
    }

    private StylerAgslEngine() {}
}
