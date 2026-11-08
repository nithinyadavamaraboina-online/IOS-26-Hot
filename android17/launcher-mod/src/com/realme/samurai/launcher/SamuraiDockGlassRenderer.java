package com.realme.samurai.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.Hotseat;
import com.android.launcher3.Workspace;

/**
 * 90Hz iOS 26 Concentric Icon-Cushion Liquid Glass Dock Renderer (AGSL RuntimeShader).
 *
 * Calibrated specifically for Realme X2 Pro (RMX1931, 1080x2400, 420dpi = 2.625 px/dp):
 * - Lifted iOS 26 Dock Elevation: 62 px (23.62 dp) bottom margin above the 92 px bottom OLED bezel
 *   so dock icons align with Apple iOS 26 SpringBoard's ~92% screen-height thumb line (y_center = 2220 px).
 * - Uniform 4-Sided Icon Cushion: 38 px (14.48 dp) side margin + 236 px (89.90 dp) dock height
 *   yields ~39-40 px uniform glass cushion on top, bottom, left, and right around the 4 squircle icons.
 * - Smooth Continuous Curved Corners: R_dock = 96 px (36.57 dp, ~40.7% of dock height) with Apple's
 *   UIKit continuous container superellipse (p = 2.65), wrapping the outer squircle icons concentrically.
 * - True Water-Clear Crystal at Frost = 0% (540x1200 unblurred uCrystalWp, zero interior blur/veil/haze)
 *   + Layout-Locked Screen Origin (zero color shift or 1000ms hardware-layer snap when closing apps).
 */
public final class SamuraiDockGlassRenderer {
    // Exact 420dpi (2.625 px/dp) iOS 26 lifted & curved dock geometry constants:
    // 236 px / 2.625 = 89.9048 dp; 38 px / 2.625 = 14.4762 dp; 62 px / 2.625 = 23.6190 dp; 96 px / 2.625 = 36.5714 dp
    public static final float DISPLAY_BOTTOM_RADIUS_DP = 35.0476f; // 92 px @ 420dpi
    public static final float DOCK_HEIGHT_DP = 89.9048f;           // 236 px @ 420dpi (~38.5 px top/bottom icon cushion)
    public static final float DOCK_SIDE_MARGIN_DP = 14.4762f;      // 38 px @ 420dpi (~40 px left/right icon cushion)
    public static final float DOCK_BOTTOM_MARGIN_DP = 23.6190f;    // 62 px @ 420dpi (lifted iOS 26 elevation, pill hidden)
    public static final float DOCK_BOTTOM_PILL_EXTRA_DP = 3.0476f; // +8 px -> 70 px when gesture pill shown
    public static final float DOCK_CORNER_RADIUS_DP = 36.5714f;    // 96 px @ 420dpi (p = 2.65 continuous curved dock)

    private static final String DOCK_AGSL = ""
            + "uniform shader uCrystalWp;\n"
            + "uniform shader uSoftWp;\n"
            + "uniform shader uIconBloom;\n"
            + "uniform float2 uDockSize;\n"
            + "uniform float2 uDockScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uCrystalBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float2 uBloomScale;\n"
            + "uniform float uPad;\n"
            + "uniform float uRadius;\n"
            + "uniform float uDensity;\n"
            + "uniform float uWpZoom;\n"
            + "uniform float uWpBrightness;\n"
            + "uniform float uBaseFrost;\n"
            + "uniform float uVeilAlpha;\n"
            + "uniform float3 uVeilColor;\n"
            + "uniform float uDispersionScale;\n"
            + "uniform float uRefractionScale;\n"
            + "uniform float uFrostScale;\n"
            + "uniform float uDockLuma;\n"
            + "uniform float uShadowStrength;\n"
            + "uniform float uMasterAlpha;\n"
            + "\n"
            + "// Apple UIKit continuous container superellipse (exponent 2.65) rounded rectangle SDF\n"
            + "float sdSuperellipseRect(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + float2(r);\n"
            + "    float2 qPos = max(q, 0.0005);\n"
            + "    if (q.x <= 0.0 && q.y <= 0.0) {\n"
            + "        return min(max(q.x, q.y), 0.0) - r;\n"
            + "    }\n"
            + "    float corner = pow(pow(qPos.x, 2.65) + pow(qPos.y, 2.65), 0.37735849);\n"
            + "    return min(max(q.x, q.y), 0.0) + corner - r;\n"
            + "}\n"
            + "\n"
            + "// Exact inverse of Android 17 ImageWallpaper Home Screen center-zoom (config_wallpaperMaxScale = 1.10)\n"
            + "// Guarantees 0.00px displacement across the glass boundary when uRefractionScale == 0.0!\n"
            + "float2 sampleWpCoord(float2 screenPx, float2 bufSize) {\n"
            + "    float2 center = uScreenSize * 0.5;\n"
            + "    float2 zoomedPx = center + (screenPx - center) / max(uWpZoom, 1.0);\n"
            + "    float2 p = clamp(zoomedPx, float2(0.0), uScreenSize);\n"
            + "    return p * (bufSize / max(uScreenSize, float2(1.0)));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 local = fragCoord - float2(uPad);\n"
            + "    float2 halfSize = uDockSize * 0.5;\n"
            + "    float2 rel = local - halfSize;\n"
            + "    float r = min(uRadius, min(halfSize.x, halfSize.y));\n"
            + "    float d = sdSuperellipseRect(rel, halfSize, r);\n"
            + "    float aa = 1.0 - smoothstep(-0.75, 0.65, d);\n"
            + "\n"
            + "    // Exterior-only contact shadow (strictly outside dock perimeter -> zero interior tint bleed!)\n"
            + "    half shadowA = half(0.0);\n"
            + "    if (uShadowStrength > 0.001 && d > -0.65) {\n"
            + "        float dSh = sdSuperellipseRect(rel - float2(0.0, 2.2 * uDensity), halfSize, r);\n"
            + "        float shFall = exp(-max(dSh, 0.0) / max(3.4 * uDensity, 1.0));\n"
            + "        shadowA = half(uShadowStrength * shFall * (1.0 - aa) * uMasterAlpha);\n"
            + "    }\n"
            + "    if (aa <= 0.001) {\n"
            + "        return half4(half3(0.024, 0.047, 0.094) * shadowA, shadowA);\n"
            + "    }\n"
            + "\n"
            + "    float eps = 1.1;\n"
            + "    float dx = sdSuperellipseRect(rel + float2(eps, 0.0), halfSize, r) - sdSuperellipseRect(rel - float2(eps, 0.0), halfSize, r);\n"
            + "    float dy = sdSuperellipseRect(rel + float2(0.0, eps), halfSize, r) - sdSuperellipseRect(rel - float2(0.0, eps), halfSize, r);\n"
            + "    float2 n = normalize(float2(dx, dy) + float2(1e-5));\n"
            + "\n"
            + "    // Spherical meniscus circleMap(edge) + subtle convex slab bend across full glass body\n"
            + "    float lipW = 16.5 * uDensity;\n"
            + "    float edge = clamp(1.0 + d / lipW, 0.0, 1.0);\n"
            + "    float meniscus = 1.0 - sqrt(max(1.0 - edge * edge, 0.0));\n"
            + "    float2 dome = (rel / max(halfSize, float2(1.0))) * float2(3.2 * uDensity, 5.0 * uDensity) * uRefractionScale;\n"
            + "    float pullX = -n.x * (12.5 * uDensity) * meniscus * uRefractionScale - dome.x;\n"
            + "    float pullY = -n.y * (19.5 * uDensity) * meniscus * uRefractionScale - dome.y;\n"
            + "    float2 baseScreen = uDockScreenOrigin + local + float2(pullX, pullY);\n"
            + "\n"
            + "    float disp = (2.35 * uDensity) * meniscus * uDispersionScale * uRefractionScale;\n"
            + "    float2 pR = baseScreen + n * disp;\n"
            + "    float2 pG = baseScreen;\n"
            + "    float2 pB = baseScreen - n * disp;\n"
            + "\n"
            + "    half3 crystalCol = half3(\n"
            + "        uCrystalWp.eval(sampleWpCoord(pR, uCrystalBufSize)).r,\n"
            + "        uCrystalWp.eval(sampleWpCoord(pG, uCrystalBufSize)).g,\n"
            + "        uCrystalWp.eval(sampleWpCoord(pB, uCrystalBufSize)).b) * half(uWpBrightness);\n"
            + "    half3 softCol = half3(\n"
            + "        uSoftWp.eval(sampleWpCoord(pR, uSoftBufSize)).r,\n"
            + "        uSoftWp.eval(sampleWpCoord(pG, uSoftBufSize)).g,\n"
            + "        uSoftWp.eval(sampleWpCoord(pB, uSoftBufSize)).b) * half(uWpBrightness);\n"
            + "\n"
            + "    // True zero-frost optical crystal when uFrostScale == 0.0 (localFrost == 0.0 everywhere)\n"
            + "    float frostClamped = clamp(uFrostScale, 0.0, 1.5);\n"
            + "    float localFrost = clamp(uBaseFrost * frostClamped, 0.0, 1.0) * (1.0 - 0.62 * meniscus);\n"
            + "    half3 glass = mix(crystalCol, softCol, half(localFrost));\n"
            + "\n"
    // Crisp saturation inside crystal medium (0% boost when both Frost=0% and Refraction=0% -> 1:1 color match)
            + "    half luma = dot(glass, half3(0.2126, 0.7152, 0.0722));\n"
            + "    float satBoost = 1.0 + 0.10 * clamp(max(uFrostScale, uRefractionScale), 0.0, 1.0);\n"
            + "    glass = clamp(mix(half3(luma), glass, half(satBoost)), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    // Subsurface dock-icon bloom & milky frost veil scale strictly to 0.0 at Frost=0%\n"
            + "    float frostVis = clamp(uFrostScale, 0.0, 1.25);\n"
            + "    if (frostVis > 0.005) {\n"
            + "        half4 bloom = uIconBloom.eval(clamp(local, float2(0.0), uDockSize) * uBloomScale);\n"
            + "        glass = mix(glass, bloom.rgb, half(0.15 * frostVis) * bloom.a);\n"
            + "    }\n"
            + "    float veilScaled = clamp(uVeilAlpha * frostClamped, 0.0, 0.80);\n"
            + "    glass = mix(glass, half3(uVeilColor), half(veilScaled));\n"
            + "    float yNorm = clamp(local.y / max(uDockSize.y, 1.0), 0.0, 1.0);\n"
            + "    float topSheen = (1.0 - yNorm) * (0.022 + 0.024 * uDockLuma) * frostVis;\n"
            + "    glass = clamp(glass + half3(topSheen), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    // Subtle internal top meniscus refractive caustic (scales strictly with uRefractionScale)\n"
            + "    float refractVis = clamp(uRefractionScale, 0.0, 1.5);\n"
            + "    float depthPx = -d;\n"
            + "    float topAlign = clamp(-n.y, 0.0, 1.0);\n"
            + "    float causticBand = exp(-pow((depthPx - 3.8 * uDensity) / (2.6 * uDensity), 2.0)) * topAlign;\n"
            + "    glass += half3(1.0) * half(causticBand * (0.032 + 0.035 * uDockLuma) * refractVis);\n"
            + "\n"
            + "    // Hair-fine 0.58dp (~1.5px) 34-deg SpringBoard crystal specular rim (zero grey sausage tube)\n"
            + "    float lightDot = clamp(dot(n, float2(-0.5592, -0.8290)), 0.0, 1.0);\n"
            + "    float oppDot   = clamp(dot(n, float2( 0.5592,  0.8290)), 0.0, 1.0);\n"
            + "    float rimLine  = exp(-pow((depthPx - 0.62 * uDensity) / (0.58 * uDensity), 2.0));\n"
            + "    float rimBase  = 0.038 + 0.035 * uDockLuma;\n"
            + "    float rimPeak  = 0.22 + 0.09 * uDockLuma;\n"
            + "    float rimSpec  = rimLine * (rimBase + rimPeak * pow(lightDot, 1.65) + 0.08 * pow(oppDot, 2.2));\n"
            + "    glass = clamp(glass + half3(rimSpec), half3(0.0), half3(1.0));\n"
            + "\n"
            + "    half glassA = half(aa * uMasterAlpha);\n"
            + "    half outA = glassA + shadowA;\n"
            + "    half3 outPremul = glass * glassA + half3(0.024, 0.047, 0.094) * shadowA;\n"
            + "    return half4(outPremul, outA);\n"
            + "}\n";

    private static RuntimeShader sShader;
    private static final Paint sGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint sFlatFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF sDockRect = new RectF();

    private static BitmapShader sCachedBloomShader;
    private static long sCachedBloomKey = Long.MIN_VALUE;
    private static int sCachedBloomW = 1;
    private static int sCachedBloomH = 1;

    private SamuraiDockGlassRenderer() {}

    private static RuntimeShader getShader() {
        if (sShader == null) {
            sShader = new RuntimeShader(DOCK_AGSL);
        }
        return sShader;
    }

    public static float computeBottomMarginPx(Context ctx, float density, int insetBottomPx) {
        // 3-Button navigation inset is ~48dp -> clear nav buttons cleanly.
        if (insetBottomPx > Math.round(34f * density)) {
            return insetBottomPx + 8f * density;
        }
        float base = DOCK_BOTTOM_MARGIN_DP * density;
        if (ctx != null && SamuraiLauncherHooks.isGesturePillEnabled(ctx)) {
            base += DOCK_BOTTOM_PILL_EXTRA_DP * density;
        }
        return base;
    }

    public static float computeConcentricCornerRadiusPx(float density) {
        return DOCK_CORNER_RADIUS_DP * density;
    }

    public static void computeDockBoundsInHotseat(Hotseat hotseat, int insetBottomPx, RectF outRect) {
        DisplayMetrics dm = hotseat.getResources().getDisplayMetrics();
        float density = dm.density;
        int w = hotseat.getWidth();
        int h = hotseat.getHeight();
        float sideMargin = Math.round(DOCK_SIDE_MARGIN_DP * density);
        float dockH = Math.round(DOCK_HEIGHT_DP * density);
        float bottomMargin = Math.round(computeBottomMarginPx(hotseat.getContext(), density, insetBottomPx));

        float dockBottom = h - bottomMargin;
        float dockTop = dockBottom - dockH;
        ViewGroup sw = hotseat.mShortcutsAndWidgets;
        float tx = sw != null ? sw.getTranslationX() : 0f;
        outRect.set(sideMargin + tx, dockTop, w - sideMargin + tx, dockBottom);
    }

    public static void drawDock(Hotseat hotseat, Canvas canvas, int insetBottomPx) {
        try {
            drawDockInternal(hotseat, canvas, insetBottomPx);
        } catch (Throwable ignored) {
        }
    }

    private static void drawDockInternal(Hotseat hotseat, Canvas canvas, int insetBottomPx) {
        ViewGroup sw = hotseat.mShortcutsAndWidgets;
        if (sw == null || sw.getVisibility() != View.VISIBLE) {
            return;
        }
        float masterAlpha = sw.getAlpha();
        if (masterAlpha < 0.01f || hotseat.getWidth() <= 0 || hotseat.getHeight() <= 0) {
            return;
        }
        if (masterAlpha < 0.999f) {
            hotseat.postInvalidateOnAnimation();
        }

        computeDockBoundsInHotseat(hotseat, insetBottomPx, sDockRect);
        float dockW = sDockRect.width();
        float dockH = sDockRect.height();
        if (dockW <= 4f || dockH <= 4f) {
            return;
        }

        Context ctx = hotseat.getContext();
        DisplayMetrics dm = hotseat.getResources().getDisplayMetrics();
        float density = dm.density;
        float radius = computeConcentricCornerRadiusPx(density);

        int glassMode = SamuraiLauncherHooks.getLiquidGlassMode(ctx);
        boolean isSystemDark = (hotseat.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        if (glassMode == 2) {
            sFlatFallbackPaint.setColor(isSystemDark ? 0xD81C1F26 : 0xD8F2F5FA);
            sFlatFallbackPaint.setAlpha(Math.round(216f * masterAlpha));
            canvas.drawRoundRect(sDockRect, radius, radius, sFlatFallbackPaint);
            return;
        }

        SamuraiWallpaperBlurCache.Snapshot wp = SamuraiWallpaperBlurCache.getOrCreate(ctx);
        SamuraiLauncherHooks.ensureRootDimSynced(hotseat);
        float wpBrightness = SamuraiLauncherHooks.getWallpaperBrightnessScale(ctx);
        float dockLuma = clamp01(wp.meanLuma * wpBrightness);
        BitmapShader bloomShader = getOrUpdateBloomShader(sw, sDockRect);

        // Layout-locked resting screen coordinates: invariant to ScalingWorkspaceRevealAnim's
        // 0.85f zoom scale, pivotY=-1011px, and 1000ms LAYER_TYPE_HARDWARE offscreen cache!
        View root = hotseat.getRootView();
        float screenW = Math.max(hotseat.getWidth(),
                Math.max(root != null ? root.getWidth() : 0, dm.widthPixels));
        float screenH = Math.max(hotseat.getBottom(),
                Math.max(root != null ? root.getHeight() : 0, dm.heightPixels));
        float hsLeft = hotseat.getLeft();
        float hsTop = (hotseat.getBottom() > hotseat.getTop())
                ? hotseat.getTop()
                : (screenH - hotseat.getHeight());
        float dockScreenX = hsLeft + sDockRect.left;
        float dockScreenY = hsTop + sDockRect.top;

        int effIconMode = SamuraiLauncherHooks.getEffectiveIconMode(ctx);
        float baseFrost;
        float veilAlpha;
        float vr, vg, vb;
        float dispersionScale;

        float darkFade = (wpBrightness < 0.35f) ? (0.35f + 0.65f * (wpBrightness / 0.35f)) : 1.0f;
        if (glassMode == 1) {
            // iOS 26.1 Tinted (frosted high-contrast legibility when Frost > 0%)
            baseFrost = 0.92f;
            dispersionScale = 0.45f;
            veilAlpha = 0.46f * darkFade;
            if (isSystemDark || effIconMode == 2 || dockLuma < 0.28f) {
                vr = 0.08f; vg = 0.10f; vb = 0.15f;
            } else {
                vr = 0.95f; vg = 0.97f; vb = 0.99f;
            }
        } else {
            // Mode 0 = Clear iOS 26 Liquid Glass
            baseFrost = 0.62f;
            dispersionScale = 1.0f;
            if (dockLuma < 0.28f) {
                veilAlpha = 0.15f * darkFade;
                vr = 0.08f; vg = 0.10f; vb = 0.15f;
            } else if (effIconMode == 3) {
                veilAlpha = 0.065f;
                vr = 0.94f; vg = 0.97f; vb = 1.0f;
            } else if (effIconMode == 2) {
                veilAlpha = 0.13f * darkFade;
                vr = 0.09f; vg = 0.11f; vb = 0.15f;
            } else {
                veilAlpha = 0.09f;
                vr = 0.96f; vg = 0.98f; vb = 1.0f;
            }
        }

        float shadowStrength = (dockLuma > 0.08f)
                ? (isSystemDark ? 0.22f : 0.15f)
                : 0.0f;
        float pad = (float) Math.ceil(9.0f * density);

        RuntimeShader shader = getShader();
        shader.setInputShader("uCrystalWp", wp.crystalShader);
        shader.setInputShader("uSoftWp", wp.softShader);
        shader.setInputShader("uIconBloom", bloomShader);
        shader.setFloatUniform("uDockSize", dockW, dockH);
        shader.setFloatUniform("uDockScreenOrigin", dockScreenX, dockScreenY);
        shader.setFloatUniform("uScreenSize", screenW, screenH);
        shader.setFloatUniform("uCrystalBufSize", (float) wp.crystalW, (float) wp.crystalH);
        shader.setFloatUniform("uSoftBufSize", (float) wp.softW, (float) wp.softH);
        shader.setFloatUniform("uBloomScale", sCachedBloomW / dockW, sCachedBloomH / dockH);
        shader.setFloatUniform("uPad", pad);
        shader.setFloatUniform("uRadius", radius);
        shader.setFloatUniform("uDensity", density);
        shader.setFloatUniform("uWpZoom", SamuraiWallpaperBlurCache.getSystemWallpaperZoomScale());
        shader.setFloatUniform("uWpBrightness", wpBrightness);
        shader.setFloatUniform("uBaseFrost", baseFrost);
        shader.setFloatUniform("uVeilAlpha", veilAlpha);
        shader.setFloatUniform("uVeilColor", vr, vg, vb);
        shader.setFloatUniform("uDispersionScale", dispersionScale);
        shader.setFloatUniform("uRefractionScale", SamuraiLauncherHooks.getRefractionScale(ctx));
        shader.setFloatUniform("uFrostScale", SamuraiLauncherHooks.getFrostScale(ctx));
        shader.setFloatUniform("uDockLuma", dockLuma);
        shader.setFloatUniform("uShadowStrength", shadowStrength);
        shader.setFloatUniform("uMasterAlpha", masterAlpha);

        sGlassPaint.setShader(shader);
        canvas.save();
        canvas.translate(sDockRect.left - pad, sDockRect.top - pad);
        canvas.drawRect(0f, 0f, dockW + pad * 2f, dockH + pad * 2f, sGlassPaint);
        canvas.restore();
    }

    private static BitmapShader getOrUpdateBloomShader(ViewGroup sw, RectF dockRect) {
        int count = sw.getChildCount();
        long key = ((long) count << 48)
                ^ ((long) Math.round(dockRect.width()) << 32)
                ^ ((long) Math.round(dockRect.height()) << 16)
                ^ SamuraiLauncherHooks.getIconCacheSerial();
        for (int i = 0; i < count; i++) {
            View c = sw.getChildAt(i);
            if (c != null) {
                key = key * 31L + c.getLeft() * 7L + c.getWidth();
            }
        }
        if (sCachedBloomShader == null || key != sCachedBloomKey) {
            int bw = Math.max(24, Math.round(dockRect.width() / 6f));
            int bh = Math.max(12, Math.round(dockRect.height() / 6f));
            sCachedBloomShader = SamuraiWallpaperBlurCache.buildDockIconBloomShader(
                    sw, dockRect.left, dockRect.top, dockRect.width(), dockRect.height());
            sCachedBloomW = bw;
            sCachedBloomH = bh;
            sCachedBloomKey = key;
        }
        return sCachedBloomShader;
    }

    public static void invalidateBloomCache() {
        sCachedBloomKey = Long.MIN_VALUE;
    }

    public static float readWorkspaceScrollNorm(Hotseat hotseat) {
        try {
            Workspace ws = hotseat.mWorkspace;
            if (ws != null) {
                int pageCount = ws.getChildCount();
                if (pageCount > 1) {
                    int maxScrollPx = ws.computeMaxScroll();
                    float maxScroll = maxScrollPx > 0
                            ? (float) maxScrollPx
                            : (pageCount - 1) * (float) Math.max(1, ws.getWidth());
                    float sx = ws.getScrollX();
                    float norm = sx / maxScroll;
                    return norm < 0f ? 0f : (norm > 1f ? 1f : norm);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.0f;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
