package com.realme.samurai.styler;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * Card 1: Live Interactive 90Hz AGSL iOS 26 Home Preview Stage.
 *
 * Architecture:
 * - `StageCanvasView` renders the active iOS 26 wallpaper, `"9:41"` Lock/Home clock hero, and
 *   upper Home Screen G2 squircle icons, and has `StylerAgslEngine.PreviewStageShaderInstance`
 *   attached as a hardware `RenderEffect`. That shader performs real-time spherical meniscus Snell
 *   refraction, ROYGCBP spectral dispersion, and `sminPoly` liquid fusion across:
 *     1) The lifted iOS 26 Liquid Glass Dock
 *     2) The `"Q Search"` Spotlight Capsule
 *     3) A user-draggable Liquid Glass Loupe Lens
 * - `HudOverlayView` sits above the RenderEffect layer to render razor-sharp Dock squircle icons
 *   (live-switching between `Default`, `Dark`, and `Clear` Plane-0/Plane-1 optics), the `"Q Search"`
 *   glyph & label, the animated Bottom Home Bar gesture pill, and a live optical telemetry badge.
 */
public class LiveHomePreviewView extends FrameLayout {
    private final StylerAgslEngine.OpticalContext mOpt;
    private final StylerAgslEngine.PreviewStageShaderInstance mStageShader =
            new StylerAgslEngine.PreviewStageShaderInstance();
    private final float mDensity;
    private final float mStageRadiusPx;

    private final StageCanvasView mStageCanvas;
    private final HudOverlayView mHudOverlay;

    // Geometry in px (updated in updateGeometry)
    private float mDockCx;
    private float mDockCy;
    private float mDockHw;
    private float mDockHh;
    private float mDockR;

    private float mPillCx;
    private float mPillCy;
    private float mPillHw;
    private float mPillHh;

    private float mLoupeCx = -1f;
    private float mLoupeCy = -1f;
    private float mLoupeRestHw;
    private float mLoupeRestHh;

    // 90Hz Hooke spring states
    private float mPillWeight;
    private float mPillVel = 0f;
    private float mGestureWeight;
    private float mGestureVel = 0f;
    private float mEnergize = 0f;
    private float mEnergizeVel = 0f;
    private boolean mDraggingLoupe = false;
    private long mLastNanos = 0L;
    private long mLastDownMs = 0L;

    private final Paint mOuterRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mOuterPath = new Path();

    public LiveHomePreviewView(Context context, StylerAgslEngine.OpticalContext opt) {
        super(context);
        mOpt = opt;
        mDensity = context.getResources().getDisplayMetrics().density;
        mStageRadiusPx = 28f * mDensity;
        mLoupeRestHw = 44f * mDensity;
        mLoupeRestHh = 30f * mDensity;

        mPillWeight = (opt != null && opt.searchPillEnabled) ? 1f : 0f;
        mGestureWeight = (opt != null && opt.gesturePillEnabled) ? 1f : 0f;

        setClipChildren(true);
        setClipToOutline(true);
        setOutlineProvider(StylerSquircle.outlineProvider(mStageRadiusPx));
        setWillNotDraw(false);

        mStageCanvas = new StageCanvasView(context);
        mHudOverlay = new HudOverlayView(context);
        addView(mStageCanvas, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(mHudOverlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mOuterRimPaint.setStyle(Paint.Style.STROKE);
        mOuterRimPaint.setStrokeWidth(1.45f * mDensity);
    }

    public void notifyOpticsChanged() {
        kickSpring();
        mStageCanvas.invalidate();
        mHudOverlay.invalidate();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int desiredH = Math.round(268f * mDensity);
        int h = resolveSize(desiredH, heightMeasureSpec);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGeometry(w, h);
        pushShaderUniforms(w, h);
    }

    private void updateGeometry(int w, int h) {
        if (w <= 4 || h <= 4) return;
        float wf = (float) w;
        float hf = (float) h;

        float dockMarginX = 16f * mDensity;
        float dockBottomPad = 16f * mDensity;
        float dockH = 74f * mDensity;
        mDockHw = Math.max(40f, (wf - dockMarginX * 2f) * 0.5f);
        mDockHh = dockH * 0.5f;
        mDockCx = wf * 0.5f;
        mDockCy = hf - dockBottomPad - mDockHh;
        mDockR = 26f * mDensity;

        mPillHw = 46f * mDensity;
        mPillHh = 14.5f * mDensity;
        mPillCx = wf * 0.5f;
        mPillCy = mDockCy - mDockHh - 12f * mDensity - mPillHh;

        if (mLoupeCx < 0f || mLoupeCy < 0f) {
            mLoupeCx = wf * 0.68f;
            mLoupeCy = hf * 0.39f;
        } else {
            mLoupeCx = StylerPrefsBridge.clampF(mLoupeCx, mLoupeRestHw + 8f, wf - mLoupeRestHw - 8f);
            mLoupeCy = StylerPrefsBridge.clampF(mLoupeCy, mLoupeRestHh + 8f, hf - mLoupeRestHh - 8f);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) return false;

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs - mLastDownMs < 280L) {
                // Double-tap resets loupe to optical showcase focal point
                mLoupeCx = w * 0.68f;
                mLoupeCy = h * 0.39f;
                StylerPrefsBridge.performTick(this, true);
            } else {
                mLoupeCx = StylerPrefsBridge.clampF(x, mLoupeRestHw + 8f, w - mLoupeRestHw - 8f);
                mLoupeCy = StylerPrefsBridge.clampF(y, mLoupeRestHh + 8f, h - mLoupeRestHh - 8f);
                StylerPrefsBridge.performTick(this, false);
            }
            mLastDownMs = nowMs;
            mDraggingLoupe = true;
            kickSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (mDraggingLoupe) {
                mLoupeCx = StylerPrefsBridge.clampF(x, mLoupeRestHw + 8f, w - mLoupeRestHw - 8f);
                mLoupeCy = StylerPrefsBridge.clampF(y, mLoupeRestHh + 8f, h - mLoupeRestHh - 8f);
                kickSpring();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDraggingLoupe = false;
            StylerPrefsBridge.performTick(this, false);
            kickSpring();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void kickSpring() {
        if (mLastNanos == 0L) {
            mLastNanos = System.nanoTime();
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    private boolean stepSprings() {
        if (mLastNanos == 0L) {
            float targetPill = (mOpt != null && mOpt.searchPillEnabled) ? 1f : 0f;
            float targetGest = (mOpt != null && mOpt.gesturePillEnabled) ? 1f : 0f;
            if (Math.abs(targetPill - mPillWeight) > 0.005f
                    || Math.abs(targetGest - mGestureWeight) > 0.005f
                    || mDraggingLoupe) {
                mLastNanos = System.nanoTime();
            } else {
                return false;
            }
        }
        long now = System.nanoTime();
        float dt = StylerPrefsBridge.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
        mLastNanos = now;

        float targetPill = (mOpt != null && mOpt.searchPillEnabled) ? 1f : 0f;
        float fPill = 420f * (targetPill - mPillWeight) - 34f * mPillVel;
        mPillVel += fPill * dt;
        mPillWeight = StylerPrefsBridge.clampF(mPillWeight + mPillVel * dt, 0f, 1.05f);

        float targetGest = (mOpt != null && mOpt.gesturePillEnabled) ? 1f : 0f;
        float fGest = 440f * (targetGest - mGestureWeight) - 34f * mGestureVel;
        mGestureVel += fGest * dt;
        mGestureWeight = StylerPrefsBridge.clampF(mGestureWeight + mGestureVel * dt, 0f, 1.05f);

        float targetEn = mDraggingLoupe ? 1f : 0f;
        float fEn = 480f * (targetEn - mEnergize) - 34f * mEnergizeVel;
        mEnergizeVel += fEn * dt;
        mEnergize = StylerPrefsBridge.clampF(mEnergize + mEnergizeVel * dt, 0f, 1.15f);

        boolean pillDone = Math.abs(targetPill - mPillWeight) < 0.003f && Math.abs(mPillVel) < 0.01f;
        boolean gestDone = Math.abs(targetGest - mGestureWeight) < 0.003f && Math.abs(mGestureVel) < 0.01f;
        boolean enDone = !mDraggingLoupe
                && Math.abs(targetEn - mEnergize) < 0.003f
                && Math.abs(mEnergizeVel) < 0.01f;

        if (pillDone && gestDone && enDone) {
            mPillWeight = targetPill;
            mPillVel = 0f;
            mGestureWeight = targetGest;
            mGestureVel = 0f;
            mEnergize = 0f;
            mEnergizeVel = 0f;
            mLastNanos = 0L;
            return false;
        }
        return true;
    }

    private void pushShaderUniforms(int w, int h) {
        float scale = 1f + 0.14f * StylerPrefsBridge.clampF(mEnergize, 0f, 1f);
        float lhw = mLoupeRestHw * scale;
        float lhh = mLoupeRestHh * scale;
        float lr = lhh;
        mStageShader.applyToView(
                mStageCanvas,
                mOpt,
                (float) w,
                (float) h,
                mDockCx,
                mDockCy,
                mDockHw,
                mDockHh,
                mDockR,
                mPillCx,
                mPillCy,
                mPillHw,
                mPillHh,
                StylerPrefsBridge.clampF(mPillWeight, 0f, 1f),
                mLoupeCx,
                mLoupeCy,
                lhw,
                lhh,
                lr,
                StylerPrefsBridge.clampF(mEnergize, 0f, 1f),
                mDensity);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 4 && h > 4) {
            updateGeometry(w, h);
            boolean keepGoing = stepSprings();
            pushShaderUniforms(w, h);
            if (keepGoing) {
                mStageCanvas.invalidate();
                mHudOverlay.invalidate();
                postInvalidateOnAnimation();
            }
        }
        super.dispatchDraw(canvas);

        if (w > 4 && h > 4) {
            float inset = 0.75f * mDensity;
            StylerSquircle.buildPath(inset, inset, w - inset, h - inset,
                    mStageRadiusPx - inset, mOuterPath);
            mOuterRimPaint.setShader(new LinearGradient(
                    0f, 0f, (float) w, (float) h,
                    new int[]{
                            Color.argb(230, 255, 255, 255),
                            Color.argb(34, 255, 255, 255),
                            Color.argb(165, 185, 236, 255)
                    },
                    new float[]{0f, 0.50f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mOuterPath, mOuterRimPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mStageShader.detach(mStageCanvas);
        super.onDetachedFromWindow();
    }

    /**
     * Draws a crisp iOS 26 multi-plane G2 squircle mini-icon adapting live to `iconMode`:
     *   1 = Default (vivid Apple color plate + frosted white glyph)
     *   2 = Dark    (`#313238 -> #141519` obsidian plate + luminous neon glyph)
     *   3 = Clear   (wallpaper-tinted translucent frosted glass plate + white crystal glyph)
     */
    private void drawMiniSquircleIcon(
            Canvas canvas,
            float cx,
            float cy,
            float sizePx,
            int kind,
            int iconMode,
            int cTop,
            int cBot,
            int neonAccent,
            Path scratchPath,
            Paint fillPaint,
            Paint rimPaint,
            Paint glyphPaint) {
        float half = sizePx * 0.5f;
        float l = cx - half;
        float t = cy - half;
        float r = cx + half;
        float b = cy + half;
        float rad = sizePx * 0.228f;

        // Ambient contact shadow
        StylerSquircle.buildPath(l, t + 1.8f * mDensity, r, b + 1.8f * mDensity, rad, scratchPath);
        fillPaint.setShader(null);
        fillPaint.setColor(Color.argb(46, 2, 10, 26));
        canvas.drawPath(scratchPath, fillPaint);

        StylerSquircle.buildPath(l, t, r, b, rad, scratchPath);
        if (iconMode == StylerPrefsBridge.ICON_DARK) {
            fillPaint.setShader(new LinearGradient(
                    l, t, l, b,
                    Color.rgb(49, 51, 58),
                    Color.rgb(20, 21, 25),
                    Shader.TileMode.CLAMP));
        } else if (iconMode == StylerPrefsBridge.ICON_CLEAR) {
            int wpRgb = (mOpt != null && mOpt.backdrop != null)
                    ? mOpt.backdrop.avgTintRgb : Color.rgb(120, 195, 235);
            int topClear = Color.argb(
                    118,
                    Math.min(255, Color.red(wpRgb) + 55),
                    Math.min(255, Color.green(wpRgb) + 55),
                    Math.min(255, Color.blue(wpRgb) + 55));
            int botClear = Color.argb(
                    88,
                    Math.max(0, Color.red(wpRgb) - 10),
                    Math.max(0, Color.green(wpRgb) - 10),
                    Math.max(0, Color.blue(wpRgb) - 10));
            fillPaint.setShader(new LinearGradient(l, t, r, b, topClear, botClear, Shader.TileMode.CLAMP));
        } else {
            fillPaint.setShader(new LinearGradient(l, t, l, b, cTop, cBot, Shader.TileMode.CLAMP));
        }
        canvas.drawPath(scratchPath, fillPaint);

        // 34-deg Schlick Fresnel squircle rim
        rimPaint.setStrokeWidth(1.1f * mDensity);
        rimPaint.setShader(new LinearGradient(
                l, t, r, b,
                new int[]{
                        Color.argb(iconMode == StylerPrefsBridge.ICON_DARK ? 140 : 220, 255, 255, 255),
                        Color.argb(20, 255, 255, 255),
                        Color.argb(iconMode == StylerPrefsBridge.ICON_DARK ? 90 : 145, 200, 238, 255)
                },
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(scratchPath, rimPaint);

        // Plane-1 Foreground Glyph
        int fgColor = (iconMode == StylerPrefsBridge.ICON_DARK) ? neonAccent : Color.WHITE;
        glyphPaint.setShader(null);
        glyphPaint.setColor(fgColor);

        if (kind == 0) {
            // Phone handset curve
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeWidth(sizePx * 0.115f);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            RectF arc = new RectF(
                    cx - sizePx * 0.22f, cy - sizePx * 0.22f,
                    cx + sizePx * 0.22f, cy + sizePx * 0.22f);
            canvas.drawArc(arc, 118f, 125f, false, glyphPaint);
        } else if (kind == 1) {
            // Messages speech bubble
            glyphPaint.setStyle(Paint.Style.FILL);
            RectF bub = new RectF(
                    cx - sizePx * 0.26f, cy - sizePx * 0.21f,
                    cx + sizePx * 0.26f, cy + sizePx * 0.16f);
            canvas.drawOval(bub, glyphPaint);
            scratchPath.reset();
            scratchPath.moveTo(cx - sizePx * 0.08f, cy + sizePx * 0.12f);
            scratchPath.lineTo(cx - sizePx * 0.20f, cy + sizePx * 0.26f);
            scratchPath.lineTo(cx + sizePx * 0.04f, cy + sizePx * 0.14f);
            scratchPath.close();
            canvas.drawPath(scratchPath, glyphPaint);
        } else if (kind == 2) {
            // Safari compass ring + needle
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeWidth(sizePx * 0.065f);
            canvas.drawCircle(cx, cy, sizePx * 0.27f, glyphPaint);
            glyphPaint.setStyle(Paint.Style.FILL);
            scratchPath.reset();
            scratchPath.moveTo(cx + sizePx * 0.17f, cy - sizePx * 0.17f);
            scratchPath.lineTo(cx + sizePx * 0.05f, cy + sizePx * 0.05f);
            scratchPath.lineTo(cx - sizePx * 0.05f, cy - sizePx * 0.05f);
            scratchPath.close();
            glyphPaint.setColor(iconMode == StylerPrefsBridge.ICON_CLEAR
                    ? Color.WHITE : Color.rgb(255, 75, 75));
            canvas.drawPath(scratchPath, glyphPaint);
            scratchPath.reset();
            scratchPath.moveTo(cx - sizePx * 0.17f, cy + sizePx * 0.17f);
            scratchPath.lineTo(cx + sizePx * 0.05f, cy + sizePx * 0.05f);
            scratchPath.lineTo(cx - sizePx * 0.05f, cy - sizePx * 0.05f);
            scratchPath.close();
            glyphPaint.setColor(fgColor);
            canvas.drawPath(scratchPath, glyphPaint);
        } else if (kind == 3) {
            // iOS Styler Liquid Glass sliders + droplets
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeWidth(sizePx * 0.075f);
            float y1 = cy - sizePx * 0.12f;
            float y2 = cy + sizePx * 0.12f;
            canvas.drawLine(cx - sizePx * 0.23f, y1, cx + sizePx * 0.23f, y1, glyphPaint);
            canvas.drawLine(cx - sizePx * 0.23f, y2, cx + sizePx * 0.23f, y2, glyphPaint);
            glyphPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx + sizePx * 0.09f, y1, sizePx * 0.105f, glyphPaint);
            canvas.drawCircle(cx - sizePx * 0.08f, y2, sizePx * 0.105f, glyphPaint);
        } else if (kind == 4) {
            // Photos flower
            glyphPaint.setStyle(Paint.Style.FILL);
            int[] cols = {
                    Color.rgb(255, 94, 126), Color.rgb(255, 168, 52),
                    Color.rgb(255, 220, 56), Color.rgb(62, 214, 118),
                    Color.rgb(58, 178, 255), Color.rgb(168, 108, 255)
            };
            for (int k = 0; k < 6; k++) {
                double a = k * Math.PI / 3.0;
                float px = cx + (float) Math.cos(a) * sizePx * 0.13f;
                float py = cy + (float) Math.sin(a) * sizePx * 0.13f;
                glyphPaint.setColor(iconMode == StylerPrefsBridge.ICON_CLEAR
                        ? Color.argb(215, 255, 255, 255) : cols[k]);
                canvas.drawCircle(px, py, sizePx * 0.11f, glyphPaint);
            }
        } else if (kind == 5) {
            // Weather sun + cloud
            glyphPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setColor(iconMode == StylerPrefsBridge.ICON_CLEAR
                    ? Color.argb(220, 255, 240, 175) : Color.rgb(255, 212, 38));
            canvas.drawCircle(cx - sizePx * 0.09f, cy - sizePx * 0.08f, sizePx * 0.14f, glyphPaint);
            glyphPaint.setColor(Color.WHITE);
            RectF cl = new RectF(
                    cx - sizePx * 0.22f, cy - sizePx * 0.02f,
                    cx + sizePx * 0.25f, cy + sizePx * 0.20f);
            canvas.drawRoundRect(cl, sizePx * 0.11f, sizePx * 0.11f, glyphPaint);
        } else if (kind == 6) {
            // Camera body + lens
            glyphPaint.setStyle(Paint.Style.FILL);
            RectF cam = new RectF(
                    cx - sizePx * 0.26f, cy - sizePx * 0.16f,
                    cx + sizePx * 0.26f, cy + sizePx * 0.20f);
            canvas.drawRoundRect(cam, sizePx * 0.07f, sizePx * 0.07f, glyphPaint);
            glyphPaint.setColor(iconMode == StylerPrefsBridge.ICON_DARK
                    ? Color.rgb(22, 24, 30) : Color.rgb(42, 54, 76));
            canvas.drawCircle(cx, cy + sizePx * 0.02f, sizePx * 0.105f, glyphPaint);
        } else {
            // Settings gear ring
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeWidth(sizePx * 0.095f);
            canvas.drawCircle(cx, cy, sizePx * 0.19f, glyphPaint);
        }
    }

    /**
     * Layer 0: Background wallpaper + `"9:41"` clock + upper Home Screen squircles, processed by
     * `PREVIEW_STAGE_AGSL` `RenderEffect` so the Dock, Search Pill, and Loupe refract them live!
     */
    private final class StageCanvasView extends View {
        private final Rect mSrcRect = new Rect();
        private final RectF mDstRect = new RectF();
        private final Paint mBmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mClockPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mScratchPath = new Path();

        StageCanvasView(Context context) {
            super(context);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mClockPaint.setTextAlign(Paint.Align.LEFT);
            mClockPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
            mSubPaint.setTextAlign(Paint.Align.LEFT);
            mSubPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;

            // 1. Live iOS 26 Wallpaper Backdrop (540x1200 verified Sky Light / Dark / Aurora)
            Bitmap bmp = (mOpt != null && mOpt.backdrop != null)
                    ? mOpt.backdrop.displayBitmap : null;
            if (bmp != null && !bmp.isRecycled()) {
                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                // Sample central rich petal region of the 9:20 wallpaper
                int cropH = Math.min(bh, Math.max(1, Math.round(bw * (h / (float) w))));
                int topY = Math.max(0, (bh - cropH) / 2);
                mSrcRect.set(0, topY, bw, topY + cropH);
                mDstRect.set(0f, 0f, (float) w, (float) h);
                canvas.drawBitmap(bmp, mSrcRect, mDstRect, mBmpPaint);
            } else {
                canvas.drawColor(Color.rgb(22, 92, 168));
            }
            // Home Screen Z-Depth Dimming (0..100% Wallpaper Brightness vs 100% App Icons)
            int wpPct = (mOpt != null)
                    ? Math.round(mOpt.wpBrightnessScale * 100f)
                    : StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT;
            int dimAlpha = StylerPrefsBridge.computeWallpaperDimAlpha(wpPct);
            if (dimAlpha > 0) {
                canvas.drawColor(dimAlpha << 24);
            }

            // 2. iOS 26 Clock Hero on left (gets refracted when user drags the Loupe over it!)
            mClockPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 34f, getResources().getDisplayMetrics()));
            mClockPaint.setColor(Color.argb(242, 255, 255, 255));
            mClockPaint.setShadowLayer(6f * mDensity, 0f, 2f * mDensity, Color.argb(68, 4, 18, 44));
            canvas.drawText("9:41", 20f * mDensity, 64f * mDensity, mClockPaint);

            mSubPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 11.2f, getResources().getDisplayMetrics()));
            mSubPaint.setColor(Color.argb(215, 240, 249, 255));
            canvas.drawText("iOS 26 Liquid Glass", 21f * mDensity, 82f * mDensity, mSubPaint);

            // 3. Four upper Home Screen squircle icons on the right half (prime targets for Loupe!)
            int iconMode = (mOpt != null) ? mOpt.iconMode : StylerPrefsBridge.ICON_DEFAULT;
            float iconSize = 40f * mDensity;
            float startX = w * 0.50f;
            float endX = w - 34f * mDensity;
            float stepX = (endX - startX) / 3f;
            float rowY = 78f * mDensity;

            int[] kinds = {4, 5, 6, 7};
            int[] tops = {
                    Color.rgb(255, 255, 255),
                    Color.rgb(56, 178, 255),
                    Color.rgb(228, 234, 242),
                    Color.rgb(162, 172, 186)
            };
            int[] bots = {
                    Color.rgb(232, 240, 252),
                    Color.rgb(18, 102, 224),
                    Color.rgb(168, 178, 192),
                    Color.rgb(98, 108, 122)
            };
            int[] neons = {
                    Color.rgb(255, 115, 165),
                    Color.rgb(90, 205, 255),
                    Color.rgb(220, 232, 248),
                    Color.rgb(175, 195, 220)
            };

            for (int i = 0; i < 4; i++) {
                float icx = startX + i * stepX;
                drawMiniSquircleIcon(
                        canvas, icx, rowY, iconSize, kinds[i], iconMode,
                        tops[i], bots[i], neons[i],
                        mScratchPath, mFillPaint, mRimPaint, mGlyphPaint);
            }

            // Fallback glass plates if RenderEffect is unavailable on host emulator
            if (!mStageShader.isSupported()) {
                mFillPaint.setShader(null);
                mFillPaint.setColor(Color.argb(115, 240, 249, 255));
                StylerSquircle.buildPath(
                        mDockCx - mDockHw, mDockCy - mDockHh,
                        mDockCx + mDockHw, mDockCy + mDockHh, mDockR, mScratchPath);
                canvas.drawPath(mScratchPath, mFillPaint);
                if (mPillWeight > 0.05f) {
                    canvas.drawRoundRect(
                            mPillCx - mPillHw, mPillCy - mPillHh,
                            mPillCx + mPillHw, mPillCy + mPillHh,
                            mPillHh, mPillHh, mFillPaint);
                }
            }
        }
    }

    /**
     * Layer 1: Razor-sharp foreground Dock squircle icons, `"Q Search"` typography,
     * Bottom Home Bar gesture pill, and optical HUD pill.
     */
    private final class HudOverlayView extends View {
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mScratchPath = new Path();
        private final RectF mTmpRect = new RectF();

        HudOverlayView(Context context) {
            super(context);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;

            boolean dark = mOpt != null && mOpt.isDarkBackdrop();
            int iconMode = (mOpt != null) ? mOpt.iconMode : StylerPrefsBridge.ICON_DEFAULT;

            // 1. Live Optical Telemetry HUD Pill (top-right)
            int refPct = mOpt != null
                    ? Math.round(mOpt.refractScale * 100f)
                    : StylerPrefsBridge.DEFAULT_REFRACTION_PCT;
            int frsPct = mOpt != null
                    ? Math.round(mOpt.frostScale * 100f)
                    : StylerPrefsBridge.DEFAULT_FROST_PCT;
            String hud = String.format(Locale.US,
                    "Drag Lens  •  Refract %d%%  •  Frost %d%%", refPct, frsPct);
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 10.5f, getResources().getDisplayMetrics()));
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            float hudW = mTextPaint.measureText(hud) + 22f * mDensity;
            float hudH = 23f * mDensity;
            float hudLeft = w - hudW - 14f * mDensity;
            float hudTop = 13f * mDensity;
            mTmpRect.set(hudLeft, hudTop, hudLeft + hudW, hudTop + hudH);
            mBadgePaint.setColor(Color.argb(dark ? 118 : 96, 8, 20, 44));
            canvas.drawRoundRect(mTmpRect, hudH * 0.5f, hudH * 0.5f, mBadgePaint);
            mRimPaint.setShader(null);
            mRimPaint.setStrokeWidth(0.9f * mDensity);
            mRimPaint.setColor(Color.argb(128, 255, 255, 255));
            canvas.drawRoundRect(mTmpRect, hudH * 0.5f, hudH * 0.5f, mRimPaint);
            Paint.FontMetrics hfm = mTextPaint.getFontMetrics();
            mTextPaint.setColor(Color.argb(242, 244, 251, 255));
            canvas.drawText(hud, mTmpRect.centerX(),
                    mTmpRect.centerY() - (hfm.ascent + hfm.descent) * 0.5f, mTextPaint);

            // 2. Subtle Loupe Lens grip label when resting
            if (mEnergize < 0.35f) {
                int hintA = Math.round((1f - mEnergize / 0.35f) * 185f);
                mTextPaint.setTextSize(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 9.8f, getResources().getDisplayMetrics()));
                mTextPaint.setColor(Color.argb(hintA, 255, 255, 255));
                canvas.drawText("LOUPE", mLoupeCx, mLoupeCy + 3.5f * mDensity, mTextPaint);
            }

            // 3. "Q Search" Spotlight Capsule foreground magnifying glass + label
            float pw = StylerPrefsBridge.clampF(mPillWeight, 0f, 1f);
            if (pw > 0.04f) {
                int alpha = Math.round(pw * 245f);
                boolean matteLight = (mOpt != null
                        && mOpt.glassMode == StylerPrefsBridge.GLASS_SOLID_MATTE && !dark);
                int fgCol = matteLight
                        ? Color.argb(alpha, 16, 34, 68)
                        : Color.argb(alpha, 255, 255, 255);

                canvas.save();
                canvas.scale(0.82f + 0.18f * pw, 0.82f + 0.18f * pw, mPillCx, mPillCy);

                mTextPaint.setTextSize(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 11.8f, getResources().getDisplayMetrics()));
                String label = "Search";
                float tw = mTextPaint.measureText(label);
                float iconR = 4.6f * mDensity;
                float gap = 5.5f * mDensity;
                float totalW = iconR * 2f + gap + tw;
                float startX = mPillCx - totalW * 0.5f;

                mGlyphPaint.setShader(null);
                mGlyphPaint.setStyle(Paint.Style.STROKE);
                mGlyphPaint.setStrokeWidth(1.55f * mDensity);
                mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
                mGlyphPaint.setColor(fgCol);
                float gcx = startX + iconR;
                float gcy = mPillCy - 0.6f * mDensity;
                canvas.drawCircle(gcx, gcy, iconR, mGlyphPaint);
                canvas.drawLine(
                        gcx + iconR * 0.72f, gcy + iconR * 0.72f,
                        gcx + iconR * 1.48f, gcy + iconR * 1.48f, mGlyphPaint);

                mTextPaint.setTextAlign(Paint.Align.LEFT);
                mTextPaint.setColor(fgCol);
                Paint.FontMetrics fm = mTextPaint.getFontMetrics();
                canvas.drawText(label, startX + iconR * 2f + gap,
                        mPillCy - (fm.ascent + fm.descent) * 0.5f, mTextPaint);
                canvas.restore();
            }

            // 4. Four concentric G2 squircle Dock icons (Phone, Messages, Safari, iOS Styler)
            float dockPadX = 18f * mDensity;
            float dockInnerLeft = (mDockCx - mDockHw) + dockPadX;
            float dockInnerRight = (mDockCx + mDockHw) - dockPadX;
            float iconSize = 50f * mDensity;
            float slotStep = (dockInnerRight - dockInnerLeft - iconSize) / 3f;

            int[] dKinds = {0, 1, 2, 3};
            int[] dTops = {
                    Color.rgb(58, 234, 108),
                    Color.rgb(52, 228, 98),
                    Color.rgb(56, 202, 255),
                    Color.rgb(78, 218, 255)
            };
            int[] dBots = {
                    Color.rgb(12, 182, 60),
                    Color.rgb(8, 176, 52),
                    Color.rgb(0, 104, 238),
                    Color.rgb(22, 58, 176)
            };
            int[] dNeons = {
                    Color.rgb(58, 236, 110),
                    Color.rgb(54, 232, 102),
                    Color.rgb(66, 196, 255),
                    Color.rgb(98, 232, 255)
            };

            for (int i = 0; i < 4; i++) {
                float icx = dockInnerLeft + iconSize * 0.5f + i * slotStep;
                drawMiniSquircleIcon(
                        canvas, icx, mDockCy, iconSize, dKinds[i], iconMode,
                        dTops[i], dBots[i], dNeons[i],
                        mScratchPath, mFillPaint, mRimPaint, mGlyphPaint);
            }

            // 5. Bottom Home Bar Gesture Pill (animates smoothly when toggled ON/OFF)
            float gw = StylerPrefsBridge.clampF(mGestureWeight, 0f, 1f);
            if (gw > 0.03f) {
                float barW = (118f * mDensity) * (0.65f + 0.35f * gw);
                float barH = 4.4f * mDensity;
                float by = h - 7.5f * mDensity;
                mTmpRect.set(w * 0.5f - barW * 0.5f, by - barH * 0.5f,
                        w * 0.5f + barW * 0.5f, by + barH * 0.5f);
                mFillPaint.setShader(null);
                mFillPaint.setColor(Color.argb(Math.round(230f * gw), 255, 255, 255));
                canvas.drawRoundRect(mTmpRect, barH * 0.5f, barH * 0.5f, mFillPaint);
            }
        }
    }
}
