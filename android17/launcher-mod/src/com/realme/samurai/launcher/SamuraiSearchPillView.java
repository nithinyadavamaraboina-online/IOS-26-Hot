package com.realme.samurai.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import com.android.launcher3.Hotseat;
import com.android.launcher3.Workspace;

/**
 * iOS 26 SpringBoard Dewdrop "Q Search" <-> Page Indicator Dots ("• •") Liquid Glass Capsule.
 *
 * - True water-clear optical crystal at Frost = 0% (100% 540x1200 unblurred uCrystalWp + spherical
 *   meniscus refraction & RGB dispersion, zero milky veil) and smooth frost up to 150%.
 * - Layout-locked screen coordinates invariant to app-close zoom scale & hardware layer caching.
 */
public final class SamuraiSearchPillView extends View {
    public static final float PILL_WIDTH_DP = 86.0f;
    public static final float PILL_HEIGHT_DP = 30.0f;
    public static final float PILL_DOCK_GAP_DP = SamuraiDockGlassRenderer.DOCK_BOTTOM_MARGIN_DP;

    private static final long DOTS_HOLD_MS = 850L;
    private static final long DOTS_FADE_MS = 260L;

    private static final String PILL_AGSL = ""
            + "uniform shader uCrystalWp;\n"
            + "uniform shader uSoftWp;\n"
            + "uniform float2 uCapOrigin;\n"
            + "uniform float2 uCapSize;\n"
            + "uniform float2 uScreenOrigin;\n"
            + "uniform float2 uScreenSize;\n"
            + "uniform float2 uCrystalBufSize;\n"
            + "uniform float2 uSoftBufSize;\n"
            + "uniform float uDensity;\n"
            + "uniform float uWpZoom;\n"
            + "uniform float uWpBrightness;\n"
            + "uniform float uBaseFrost;\n"
            + "uniform float uVeilAlpha;\n"
            + "uniform float3 uVeilColor;\n"
            + "uniform float uRefractionScale;\n"
            + "uniform float uFrostScale;\n"
            + "uniform float uShadowStrength;\n"
            + "\n"
            + "float sdCapsule(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + float2(r);\n"
            + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "float2 sampleWpCoord(float2 screenPx, float2 bufSize) {\n"
            + "    float2 center = uScreenSize * 0.5;\n"
            + "    float2 zoomedPx = center + (screenPx - center) / max(uWpZoom, 1.0);\n"
            + "    float2 p = clamp(zoomedPx, float2(0.0), uScreenSize);\n"
            + "    return p * (bufSize / max(uScreenSize, float2(1.0)));\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 local = fragCoord - uCapOrigin;\n"
            + "    float2 halfSize = uCapSize * 0.5;\n"
            + "    float r = halfSize.y;\n"
            + "    float2 rel = local - halfSize;\n"
            + "    float d = sdCapsule(rel, halfSize, r);\n"
            + "    float aa = 1.0 - smoothstep(-0.8, 0.6, d);\n"
            + "\n"
            + "    half shadowA = half(0.0);\n"
            + "    if (uShadowStrength > 0.001 && d > -0.6) {\n"
            + "        float dSh = sdCapsule(rel - float2(0.0, 1.5 * uDensity), halfSize, r);\n"
            + "        float shFall = exp(-max(dSh, 0.0) / max(2.4 * uDensity, 1.0));\n"
            + "        shadowA = half(uShadowStrength * shFall * (1.0 - aa));\n"
            + "    }\n"
            + "    if (aa <= 0.001) {\n"
            + "        return half4(half3(0.031, 0.055, 0.110) * shadowA, shadowA);\n"
            + "    }\n"
            + "\n"
            + "    float eps = 1.0;\n"
            + "    float dx = sdCapsule(rel + float2(eps, 0.0), halfSize, r) - sdCapsule(rel - float2(eps, 0.0), halfSize, r);\n"
            + "    float dy = sdCapsule(rel + float2(0.0, eps), halfSize, r) - sdCapsule(rel - float2(0.0, eps), halfSize, r);\n"
            + "    float2 n = normalize(float2(dx, dy) + float2(1e-5));\n"
            + "\n"
            + "    float lipW = 9.0 * uDensity;\n"
            + "    float edge = clamp(1.0 + d / lipW, 0.0, 1.0);\n"
            + "    float meniscus = 1.0 - sqrt(max(1.0 - edge * edge, 0.0));\n"
            + "    float2 dome = (rel / max(halfSize, float2(1.0))) * float2(2.2 * uDensity, 3.2 * uDensity) * uRefractionScale;\n"
            + "    float2 pull = -n * float2(7.0 * uDensity, 10.5 * uDensity) * meniscus * uRefractionScale - dome;\n"
            + "    float2 baseScreen = uScreenOrigin + fragCoord + pull;\n"
            + "    float disp = (1.65 * uDensity) * meniscus * uRefractionScale;\n"
            + "\n"
            + "    half3 crystalCol = half3(\n"
            + "        uCrystalWp.eval(sampleWpCoord(baseScreen + n * disp, uCrystalBufSize)).r,\n"
            + "        uCrystalWp.eval(sampleWpCoord(baseScreen, uCrystalBufSize)).g,\n"
            + "        uCrystalWp.eval(sampleWpCoord(baseScreen - n * disp, uCrystalBufSize)).b) * half(uWpBrightness);\n"
            + "    half3 softCol = half3(\n"
            + "        uSoftWp.eval(sampleWpCoord(baseScreen + n * disp, uSoftBufSize)).r,\n"
            + "        uSoftWp.eval(sampleWpCoord(baseScreen, uSoftBufSize)).g,\n"
            + "        uSoftWp.eval(sampleWpCoord(baseScreen - n * disp, uSoftBufSize)).b) * half(uWpBrightness);\n"
            + "\n"
            + "    float frostClamped = clamp(uFrostScale, 0.0, 1.5);\n"
            + "    float localFrost = clamp(uBaseFrost * frostClamped, 0.0, 1.0) * (1.0 - 0.60 * meniscus);\n"
            + "    float veilScaled = clamp(uVeilAlpha * frostClamped, 0.0, 0.80);\n"
            + "    half3 glass = mix(crystalCol, softCol, half(localFrost));\n"
            + "    glass = mix(glass, half3(uVeilColor), half(veilScaled));\n"
            + "\n"
            + "    float depthPx = -d;\n"
            + "    float lightDot = clamp(dot(n, float2(-0.5592, -0.8290)), 0.0, 1.0);\n"
            + "    float oppDot   = clamp(dot(n, float2( 0.5592,  0.8290)), 0.0, 1.0);\n"
            + "    float rim = exp(-pow((depthPx - 0.85 * uDensity) / (0.95 * uDensity), 2.0))\n"
            + "        * (0.09 + 0.30 * pow(lightDot, 1.45) + 0.10 * pow(oppDot, 2.0));\n"
            + "    glass = clamp(glass + half3(rim), half3(0.0), half3(1.0));\n"
            + "    half glassA = half(aa);\n"
            + "    return half4(glass * glassA + half3(0.031, 0.055, 0.110) * shadowA, glassA + shadowA);\n"
            + "}\n";

    private final Hotseat mHotseat;
    private RuntimeShader mShader;
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF mTmpRect = new RectF();

    private int mLastScrollX = Integer.MIN_VALUE;
    private int mLastPageCount = -1;
    private long mLastScrollUptimeMs = 0L;
    private float mLastRenderedDotsMix = 0.0f;
    private float mLastTouchX = 0.0f;

    public SamuraiSearchPillView(Context context, Hotseat hotseat) {
        super(context);
        mHotseat = hotseat;
        setClickable(true);
        setFocusable(true);
        setContentDescription("Search");
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mDotPaint.setStyle(Paint.Style.FILL);
        setOnClickListener(this::handlePillClick);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event != null) {
            mLastTouchX = event.getX();
        }
        return super.onTouchEvent(event);
    }

    private void handlePillClick(View v) {
        Context ctx = v.getContext();
        Workspace ws = mHotseat != null ? mHotseat.mWorkspace : null;
        int pageCount = ws != null ? ws.getChildCount() : 1;
        boolean searchEnabled = SamuraiLauncherHooks.isSearchPillEnabled(ctx);

        if (ws != null && pageCount > 1 && (!searchEnabled || mLastRenderedDotsMix >= 0.5f)) {
            int cur = ws.getCurrentPage();
            int next = (mLastTouchX < getWidth() * 0.5f)
                    ? Math.max(0, cur - 1)
                    : Math.min(pageCount - 1, cur + 1);
            if (next != cur) {
                ws.snapToPage(next);
                mLastScrollUptimeMs = SystemClock.uptimeMillis();
                invalidate();
            }
            return;
        }
        if (searchEnabled) {
            SamuraiLauncherHooks.triggerLauncherSearch(ctx);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawPillInternal(canvas);
        } catch (Throwable ignored) {
        }
    }

    private void drawPillInternal(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 2) return;

        Context ctx = getContext();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        float r = h * 0.5f;

        Workspace ws = mHotseat != null ? mHotseat.mWorkspace : null;
        int pageCount = ws != null ? Math.max(1, ws.getChildCount()) : 1;
        boolean searchEnabled = SamuraiLauncherHooks.isSearchPillEnabled(ctx);
        float scrollNorm = SamuraiDockGlassRenderer.readWorkspaceScrollNorm(mHotseat);

        long now = SystemClock.uptimeMillis();
        if (ws != null) {
            int sx = ws.getScrollX();
            if (mLastScrollX == Integer.MIN_VALUE) {
                mLastScrollX = sx;
                mLastPageCount = pageCount;
            } else if (sx != mLastScrollX || pageCount != mLastPageCount) {
                mLastScrollX = sx;
                mLastPageCount = pageCount;
                if (pageCount > 1) {
                    mLastScrollUptimeMs = now;
                }
            }
        }

        float dotsMix;
        if (!searchEnabled) {
            if (pageCount <= 1) {
                mLastRenderedDotsMix = 0.0f;
                return;
            }
            dotsMix = 1.0f;
        } else if (pageCount <= 1 || mLastScrollUptimeMs == 0L) {
            dotsMix = 0.0f;
        } else {
            long elapsed = now - mLastScrollUptimeMs;
            if (elapsed < DOTS_HOLD_MS) {
                dotsMix = 1.0f;
                postInvalidateDelayed(Math.max(16L, DOTS_HOLD_MS - elapsed));
            } else if (elapsed < DOTS_HOLD_MS + DOTS_FADE_MS) {
                float t = (elapsed - DOTS_HOLD_MS) / (float) DOTS_FADE_MS;
                float smooth = t * t * (3.0f - 2.0f * t);
                dotsMix = 1.0f - smooth;
                postInvalidateOnAnimation();
            } else {
                dotsMix = 0.0f;
            }
        }
        mLastRenderedDotsMix = dotsMix;

        int visibleDots = Math.min(pageCount, 7);
        float dotDiaPx = 6.6f * density;
        float dotGapPx = 6.8f * density;
        float dotsRowW = visibleDots * dotDiaPx + Math.max(0, visibleDots - 1) * dotGapPx;
        float dotsCapW = clamp(dotsRowW + 21.0f * density, 44.0f * density, (float) w);
        float capW = w + (dotsCapW - w) * dotsMix;
        float capLeft = (w - capW) * 0.5f;
        float capRight = capLeft + capW;

        int glassMode = SamuraiLauncherHooks.getLiquidGlassMode(ctx);
        float frostScale = SamuraiLauncherHooks.getFrostScale(ctx);
        float wpBrightness = SamuraiLauncherHooks.getWallpaperBrightnessScale(ctx);
        boolean isSystemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        SamuraiWallpaperBlurCache.Snapshot wp = SamuraiWallpaperBlurCache.getOrCreate(ctx);

        if (glassMode == 2) {
            mShadowPaint.setColor(isSystemDark ? 0xD81E222A : 0xD8F3F6FB);
            mTmpRect.set(capLeft, 0f, capRight, h);
            canvas.drawRoundRect(mTmpRect, r, r, mShadowPaint);
        } else {
            if (mShader == null) {
                mShader = new RuntimeShader(PILL_AGSL);
            }
            View root = getRootView();
            float screenW = Math.max(w,
                    Math.max(root != null ? root.getWidth() : 0, dm.widthPixels));
            float screenH = Math.max(mHotseat != null ? mHotseat.getBottom() : h,
                    Math.max(root != null ? root.getHeight() : 0, dm.heightPixels));
            float hsLeft = (mHotseat != null) ? mHotseat.getLeft() : 0f;
            float hsTop = (mHotseat != null)
                    ? ((mHotseat.getBottom() > mHotseat.getTop())
                            ? mHotseat.getTop()
                            : (screenH - mHotseat.getHeight()))
                    : 0f;
            float pillScreenX = hsLeft + getLeft();
            float pillScreenY = hsTop + getTop();

            float pillLuma = clamp(wp.meanLuma * wpBrightness, 0.0f, 1.0f);
            boolean darkGlass = isSystemDark || pillLuma < 0.28f;
            float darkFade = (wpBrightness < 0.35f) ? (0.35f + 0.65f * (wpBrightness / 0.35f)) : 1.0f;
            float baseFrost = (glassMode == 1) ? 0.92f : 0.62f;
            float veilAlpha = ((glassMode == 1) ? 0.48f : (darkGlass ? 0.16f : 0.10f)) * darkFade;
            float vr = darkGlass ? 0.08f : 0.97f;
            float vg = darkGlass ? 0.10f : 0.99f;
            float vb = darkGlass ? 0.15f : 1.00f;
            float shadowStrength = (pillLuma > 0.08f) ? (isSystemDark ? 0.18f : 0.12f) : 0.0f;
            float pad = (float) Math.ceil(6.0f * density);

            mShader.setInputShader("uCrystalWp", wp.crystalShader);
            mShader.setInputShader("uSoftWp", wp.softShader);
            mShader.setFloatUniform("uCapOrigin", capLeft, 0f);
            mShader.setFloatUniform("uCapSize", capW, (float) h);
            mShader.setFloatUniform("uScreenOrigin", pillScreenX, pillScreenY);
            mShader.setFloatUniform("uScreenSize", screenW, screenH);
            mShader.setFloatUniform("uCrystalBufSize", (float) wp.crystalW, (float) wp.crystalH);
            mShader.setFloatUniform("uSoftBufSize", (float) wp.softW, (float) wp.softH);
            mShader.setFloatUniform("uDensity", density);
            mShader.setFloatUniform("uWpZoom", SamuraiWallpaperBlurCache.getSystemWallpaperZoomScale());
            mShader.setFloatUniform("uWpBrightness", wpBrightness);
            mShader.setFloatUniform("uBaseFrost", baseFrost);
            mShader.setFloatUniform("uVeilAlpha", veilAlpha);
            mShader.setFloatUniform("uVeilColor", vr, vg, vb);
            mShader.setFloatUniform("uRefractionScale", SamuraiLauncherHooks.getRefractionScale(ctx));
            mShader.setFloatUniform("uFrostScale", frostScale);
            mShader.setFloatUniform("uShadowStrength", shadowStrength);

            mGlassPaint.setShader(mShader);
            canvas.drawRect(capLeft - pad, -pad, capRight + pad, h + pad, mGlassPaint);
        }

        float effPillLuma = wp.meanLuma * wpBrightness;
        boolean useDarkInk = (glassMode == 1 && !isSystemDark && effPillLuma >= 0.28f && frostScale > 0.35f)
                || (glassMode == 2 && !isSystemDark && effPillLuma >= 0.18f)
                || (!isSystemDark && effPillLuma > 0.78f);
        int baseRgb = useDarkInk ? 0x001A202C : 0x00FFFFFF;
        float centerY = h * 0.5f;

        // Layer 1: "Q Search" (fades out cleanly as dotsMix -> 1)
        float searchAlpha = 1.0f - dotsMix;
        if (searchEnabled && searchAlpha > 0.02f) {
            int fgAlpha = Math.round((useDarkInk ? 235f : 247f) * searchAlpha);
            int fgColor = (fgAlpha << 24) | baseRgb;
            int shAlpha = Math.round((useDarkInk ? 28f : 86f) * searchAlpha);
            int shadowColor = (shAlpha << 24) | (useDarkInk ? 0x00FFFFFF : 0x00081020);

            float textSize = 12.2f * density;
            mTextPaint.setTextSize(textSize);
            mTextPaint.setColor(fgColor);
            mTextPaint.setShadowLayer(2.4f * density, 0f, 0.7f * density, shadowColor);

            String label = "Search";
            float textW = mTextPaint.measureText(label);
            float iconDia = 11.2f * density;
            float gap = 4.6f * density;
            float totalW = iconDia + gap + textW;
            float startX = (w - totalW) * 0.5f;

            mGlyphPaint.setColor(fgColor);
            mGlyphPaint.setStyle(Paint.Style.STROKE);
            mGlyphPaint.setStrokeWidth(1.55f * density);
            mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
            float rCircle = 4.1f * density;
            float cx = startX + rCircle + 0.6f * density;
            float cy = centerY - 0.6f * density;
            canvas.drawCircle(cx, cy, rCircle, mGlyphPaint);
            float hStart = rCircle * 0.72f;
            float hEnd = rCircle * 1.48f;
            canvas.drawLine(cx + hStart, cy + hStart, cx + hEnd, cy + hEnd, mGlyphPaint);

            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            float baseline = centerY - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, startX + iconDia + gap, baseline, mTextPaint);
        }

        // Layer 2: iOS 26 Page Dots ("• •") with real-time continuous scroll highlight
        if (pageCount > 1 && dotsMix > 0.02f) {
            float continuousPage = scrollNorm * Math.max(1, pageCount - 1);
            int windowStart = 0;
            if (pageCount > visibleDots) {
                int focusIdx = Math.round(continuousPage);
                windowStart = clampInt(focusIdx - visibleDots / 2, 0, pageCount - visibleDots);
            }
            float startDotX = (w - dotsRowW) * 0.5f + dotDiaPx * 0.5f;
            for (int i = 0; i < visibleDots; i++) {
                int pageIdx = windowStart + i;
                float dist = Math.abs(continuousPage - pageIdx);
                float activeWeight = clamp(1.0f - dist, 0.0f, 1.0f);
                float dotAlphaFrac = (0.36f + 0.60f * activeWeight) * dotsMix;
                int dotA = clampInt(Math.round(255f * dotAlphaFrac), 0, 255);
                mDotPaint.setColor((dotA << 24) | baseRgb);
                float dotRadius = (3.0f + 0.45f * activeWeight) * density;
                float cx = startDotX + i * (dotDiaPx + dotGapPx);
                canvas.drawCircle(cx, centerY, dotRadius, mDotPaint);
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
