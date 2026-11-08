package com.realme.samurai.styler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * Real-time 90Hz AGSL Liquid Glass Container Card (`PANEL_SURFACE_AGSL`).
 *
 * Replaces all flat Material cards:
 * - Samples the live iOS 26 backdrop in exact screen-space coordinates (`getLocationOnScreen`)
 *   so scrolling bends, refracts, and 7-band ROYGCBP-disperses the petals underneath at 90Hz.
 * - Non-intrusively observes touch coordinates in `dispatchTouchEvent` to animate a smooth
 *   Gaussian liquid bulge + specular glow directly beneath the user's finger.
 */
public class LiquidGlassCardView extends LinearLayout {
    private final StylerAgslEngine.OpticalContext mOpt;
    private final StylerAgslEngine.PanelShaderInstance mPanelShader =
            new StylerAgslEngine.PanelShaderInstance();
    private final float mDensity;
    private final float mCornerRadiusPx;
    private float mTintBoost;

    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mCardPath = new Path();
    private final Path mShadowPath = new Path();
    private final int[] mLoc = new int[2];

    private float mTouchX = -200f;
    private float mTouchY = -200f;
    private float mTouchAmp = 0f;
    private float mTouchVel = 0f;
    private boolean mFingerDown = false;
    private long mLastNanos = 0L;

    public LiquidGlassCardView(
            Context context,
            StylerAgslEngine.OpticalContext opt,
            float cornerRadiusDp,
            float tintBoost) {
        super(context);
        mOpt = opt;
        mDensity = context.getResources().getDisplayMetrics().density;
        mCornerRadiusPx = cornerRadiusDp * mDensity;
        mTintBoost = tintBoost;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        mFallbackRim.setStyle(Paint.Style.STROKE);
        mFallbackRim.setStrokeWidth(1.2f * mDensity);
    }

    public void setTintBoost(float boost) {
        if (Math.abs(mTintBoost - boost) > 0.005f) {
            mTintBoost = boost;
            invalidate();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                mTouchX = ev.getX();
                mTouchY = ev.getY();
                mFingerDown = true;
                kickSpring();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mFingerDown = false;
                kickSpring();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void kickSpring() {
        if (mLastNanos == 0L) {
            mLastNanos = System.nanoTime();
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w > 2 && h > 2) {
            boolean keepGoing = stepTouchSpring();
            drawGlassSurface(canvas, w, h);
            if (keepGoing) {
                postInvalidateOnAnimation();
            }
        }
        super.dispatchDraw(canvas);
    }

    private boolean stepTouchSpring() {
        if (mLastNanos == 0L) {
            return false;
        }
        long now = System.nanoTime();
        float dt = StylerPrefsBridge.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
        mLastNanos = now;

        float target = mFingerDown ? 1f : 0f;
        float force = 420f * (target - mTouchAmp) - 34f * mTouchVel;
        mTouchVel += force * dt;
        mTouchAmp = StylerPrefsBridge.clampF(mTouchAmp + mTouchVel * dt, 0f, 1.05f);

        if (!mFingerDown && Math.abs(target - mTouchAmp) < 0.004f && Math.abs(mTouchVel) < 0.01f) {
            mTouchAmp = 0f;
            mTouchVel = 0f;
            mLastNanos = 0L;
            return false;
        }
        return true;
    }

    private void drawGlassSurface(Canvas canvas, int w, int h) {
        float wf = (float) w;
        float hf = (float) h;

        // Soft G2 ambient elevation shadow outside card perimeter
        float dy = 3.5f * mDensity;
        StylerSquircle.buildPath(1.5f * mDensity, dy, wf - 1.5f * mDensity, hf + dy * 0.45f,
                mCornerRadiusPx, mShadowPath);
        boolean dark = mOpt != null && mOpt.isDarkBackdrop();
        mShadowPaint.setColor(Color.argb(dark ? 54 : 30, 4, 10, 24));
        canvas.drawPath(mShadowPath, mShadowPaint);

        getLocationOnScreen(mLoc);
        boolean shaderReady = mPanelShader.bindToPaint(
                mGlassPaint,
                mOpt,
                wf,
                hf,
                (float) mLoc[0],
                (float) mLoc[1],
                mCornerRadiusPx,
                mDensity,
                mTouchX,
                mTouchY,
                mTouchAmp,
                mTintBoost);

        if (shaderReady) {
            canvas.drawRect(0f, 0f, wf, hf, mGlassPaint);
        } else {
            StylerSquircle.buildPath(0f, 0f, wf, hf, mCornerRadiusPx, mCardPath);
            mFallbackFill.setColor(dark ? Color.argb(148, 18, 28, 48) : Color.argb(168, 248, 252, 255));
            canvas.drawPath(mCardPath, mFallbackFill);
            mFallbackRim.setShader(new LinearGradient(
                    0f, 0f, wf, hf,
                    new int[]{
                            Color.argb(215, 255, 255, 255),
                            Color.argb(24, 255, 255, 255),
                            Color.argb(135, 198, 236, 255)
                    },
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(mCardPath, mFallbackRim);
        }
    }
}
