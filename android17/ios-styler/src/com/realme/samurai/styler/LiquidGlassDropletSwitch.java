package com.realme.samurai.styler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/**
 * 90Hz iOS 26 Velocity-Preserving Liquid Droplet Switch (`#34C759` ON / AGSL Frosted Glass OFF).
 *
 * Pressing or dragging lifts the pill thumb (`1.30×`) and morphs it from opaque porcelain white
 * into a live AGSL-refracting clear Liquid Glass droplet!
 */
public class LiquidGlassDropletSwitch extends View {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    private final StylerAgslEngine.OpticalContext mOpt;
    private final StylerAgslEngine.PanelShaderInstance mTrackGlassShader =
            new StylerAgslEngine.PanelShaderInstance();
    private final StylerAgslEngine.PanelShaderInstance mDropGlassShader =
            new StylerAgslEngine.PanelShaderInstance();

    private final float mDensity;
    private final float mPadX;
    private final float mPadY;
    private final float mTrackW;
    private final float mTrackH;
    private final float mThumbRestW;
    private final float mThumbRestH;

    private boolean mChecked;
    private float mPos;
    private float mPosVel = 0f;
    private float mLift = 0f;
    private float mLiftVel = 0f;
    private boolean mDragging = false;
    private boolean mMoved = false;
    private float mDownX = 0f;
    private float mDownPos = 0f;
    private boolean mLastHapticSide;
    private VelocityTracker mVelTracker;
    private long mLastNanos = 0L;

    private final RectF mTrackRect = new RectF();
    private final RectF mThumbRect = new RectF();
    private final int[] mLoc = new int[2];

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackAgslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDropAgslPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnCheckedChangeListener mListener;

    public LiquidGlassDropletSwitch(
            Context context,
            StylerAgslEngine.OpticalContext opt,
            boolean initialChecked) {
        super(context);
        mOpt = opt;
        mDensity = context.getResources().getDisplayMetrics().density;
        mPadX = 6f * mDensity;
        mPadY = 6f * mDensity;
        mTrackW = 58f * mDensity;
        mTrackH = 32f * mDensity;
        mThumbRestW = 34f * mDensity;
        mThumbRestH = 26f * mDensity;

        mChecked = initialChecked;
        mPos = initialChecked ? 1f : 0f;
        mLastHapticSide = initialChecked;

        mTrackRimPaint.setStyle(Paint.Style.STROKE);
        mTrackRimPaint.setStrokeWidth(1.1f * mDensity);
        mThumbRimPaint.setStyle(Paint.Style.STROKE);
        mThumbRimPaint.setStrokeWidth(1.35f * mDensity);
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    public void setCheckedAnimated(boolean checked, boolean notify) {
        if (mChecked == checked) return;
        mChecked = checked;
        mLastHapticSide = checked;
        if (notify && mListener != null) {
            mListener.onCheckedChanged(checked);
        }
        startSpring();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int dw = Math.round(mTrackW + mPadX * 2f);
        int dh = Math.round(mTrackH + mPadY * 2f);
        setMeasuredDimension(resolveSize(dw, widthMeasureSpec), resolveSize(dh, heightMeasureSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (mVelTracker != null) {
                mVelTracker.recycle();
            }
            mVelTracker = VelocityTracker.obtain();
            mVelTracker.addMovement(event);
            mDragging = true;
            mMoved = false;
            mDownX = event.getX();
            mDownPos = mPos;
            mLastHapticSide = mPos >= 0.5f;
            StylerPrefsBridge.performTick(this, false);
            startSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (mVelTracker != null) {
                mVelTracker.addMovement(event);
            }
            float dx = event.getX() - mDownX;
            if (Math.abs(dx) > 4f * mDensity) {
                mMoved = true;
            }
            float travel = Math.max(1f, mTrackW - mThumbRestW - 6f * mDensity);
            float raw = mDownPos + dx / travel;
            if (raw < 0f) {
                mPos = -0.08f * (1f - 1f / (1f - raw * 3f));
            } else if (raw > 1f) {
                mPos = 1f + 0.08f * (1f - 1f / (1f + (raw - 1f) * 3f));
            } else {
                mPos = raw;
            }
            boolean curSide = mPos >= 0.5f;
            if (curSide != mLastHapticSide) {
                mLastHapticSide = curSide;
                StylerPrefsBridge.performTick(this, true);
            }
            startSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float vx = 0f;
            if (mVelTracker != null) {
                mVelTracker.addMovement(event);
                mVelTracker.computeCurrentVelocity(1000);
                vx = mVelTracker.getXVelocity();
                mVelTracker.recycle();
                mVelTracker = null;
            }
            float travel = Math.max(1f, mTrackW - mThumbRestW - 6f * mDensity);
            float normVel = StylerPrefsBridge.clampF(vx / travel, -12f, 12f);

            boolean next;
            if (action == MotionEvent.ACTION_CANCEL) {
                next = mChecked;
            } else if (!mMoved) {
                next = !mChecked;
            } else if (Math.abs(normVel) > 2.2f) {
                next = normVel > 0f;
            } else {
                next = mPos >= 0.5f;
            }

            mDragging = false;
            mPosVel = normVel;
            boolean changed = (next != mChecked);
            mChecked = next;
            if (changed || !mMoved) {
                StylerPrefsBridge.performTick(this, true);
            }
            if (changed && mListener != null) {
                mListener.onCheckedChanged(mChecked);
            }
            startSpring();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void startSpring() {
        if (mLastNanos == 0L) {
            mLastNanos = System.nanoTime();
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    private boolean stepSpring() {
        if (mLastNanos == 0L) return false;
        long now = System.nanoTime();
        float dt = StylerPrefsBridge.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
        mLastNanos = now;

        float targetPos = mChecked ? 1f : 0f;
        float targetLift = mDragging ? 1f : 0f;
        if (!mDragging) {
            float fPos = 480f * (targetPos - mPos) - 36f * mPosVel;
            mPosVel += fPos * dt;
            mPos += mPosVel * dt;
        }
        float fLift = 540f * (targetLift - mLift) - 38f * mLiftVel;
        mLiftVel += fLift * dt;
        mLift = StylerPrefsBridge.clampF(mLift + mLiftVel * dt, 0f, 1.18f);

        boolean posDone = !mDragging
                && Math.abs(targetPos - mPos) < 0.002f
                && Math.abs(mPosVel) < 0.01f;
        boolean liftDone = !mDragging
                && Math.abs(targetLift - mLift) < 0.003f
                && Math.abs(mLiftVel) < 0.01f;
        if (posDone && liftDone) {
            mPos = targetPos;
            mPosVel = 0f;
            mLift = 0f;
            mLiftVel = 0f;
            mLastNanos = 0L;
            return false;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) return;

        boolean keepGoing = stepSpring();
        boolean dark = mOpt != null && mOpt.isDarkBackdrop();

        float tl = (w - mTrackW) * 0.5f;
        float tt = (h - mTrackH) * 0.5f;
        mTrackRect.set(tl, tt, tl + mTrackW, tt + mTrackH);
        float tr = mTrackH * 0.5f;

        getLocationOnScreen(mLoc);
        if (mTrackGlassShader.bindToPaint(
                mTrackAgslPaint, mOpt, mTrackW, mTrackH,
                mLoc[0] + tl, mLoc[1] + tt, tr, mDensity,
                mTrackW * 0.5f, mTrackH * 0.5f, 0f, -0.05f)) {
            canvas.save();
            canvas.translate(tl, tt);
            canvas.drawRect(0f, 0f, mTrackW, mTrackH, mTrackAgslPaint);
            canvas.restore();
        }

        // Translucent iOS #34C759 green glow overlaid smoothly as pos -> 1
        float pClamped = StylerPrefsBridge.clampF(mPos, 0f, 1f);
        int offTint = dark ? Color.argb(72, 60, 74, 102) : Color.argb(56, 110, 124, 148);
        int onTint = Color.argb(232, 52, 199, 89);
        mTrackPaint.setColor(blendArgb(offTint, onTint, pClamped));
        canvas.drawRoundRect(mTrackRect, tr, tr, mTrackPaint);

        mTrackRimPaint.setShader(new LinearGradient(
                mTrackRect.left, mTrackRect.top, mTrackRect.right, mTrackRect.bottom,
                new int[]{
                        Color.argb(210, 255, 255, 255),
                        Color.argb(22, 255, 255, 255),
                        Color.argb(130, 210, 245, 255)
                },
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mTrackRect, tr, tr, mTrackRimPaint);

        // Lifted clear refractive Liquid Glass droplet thumb
        float morph = StylerPrefsBridge.clampF(mLift, 0f, 1f);
        float curW = mThumbRestW * (1f + 0.30f * morph);
        float curH = mThumbRestH * (1f + 0.28f * morph);
        float inset = 3f * mDensity;
        float minCx = tl + inset + mThumbRestW * 0.5f;
        float maxCx = tl + mTrackW - inset - mThumbRestW * 0.5f;
        float cx = minCx + (maxCx - minCx) * mPos;
        float cy = tt + mTrackH * 0.5f - 1.4f * mDensity * morph;

        mThumbRect.set(cx - curW * 0.5f, cy - curH * 0.5f, cx + curW * 0.5f, cy + curH * 0.5f);
        float thumbR = curH * 0.5f;

        mShadowPaint.setColor(Color.argb(Math.round(48f + 32f * morph), 0, 16, 10));
        canvas.drawRoundRect(
                mThumbRect.left,
                mThumbRect.top + (2f + 2.6f * morph) * mDensity,
                mThumbRect.right,
                mThumbRect.bottom + (2f + 2.6f * morph) * mDensity,
                thumbR, thumbR, mShadowPaint);

        if (morph > 0.04f && mDropGlassShader.bindToPaint(
                mDropAgslPaint, mOpt, curW, curH,
                mLoc[0] + mThumbRect.left, mLoc[1] + mThumbRect.top,
                thumbR, mDensity, curW * 0.5f, curH * 0.5f, morph, 0.24f)) {
            canvas.save();
            canvas.translate(mThumbRect.left, mThumbRect.top);
            canvas.drawRect(0f, 0f, curW, curH, mDropAgslPaint);
            canvas.restore();
        }

        int whiteA = StylerPrefsBridge.clamp(Math.round((1f - 0.80f * morph) * 255f), 34, 255);
        mThumbPaint.setShader(new LinearGradient(
                mThumbRect.left, mThumbRect.top, mThumbRect.right, mThumbRect.bottom,
                Color.argb(whiteA, 255, 255, 255),
                Color.argb(Math.round(whiteA * 0.88f), 236, 248, 255),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mThumbRect, thumbR, thumbR, mThumbPaint);

        mThumbRimPaint.setShader(new LinearGradient(
                mThumbRect.left, mThumbRect.top, mThumbRect.right, mThumbRect.bottom,
                new int[]{
                        Color.argb(Math.min(255, Math.round(185f + 70f * morph)), 255, 255, 255),
                        Color.argb(26, 255, 255, 255),
                        Color.argb(Math.min(255, Math.round(110f + 90f * morph)), 195, 255, 218)
                },
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mThumbRect, thumbR, thumbR, mThumbRimPaint);

        if (keepGoing) {
            postInvalidateOnAnimation();
        }
    }

    private static int blendArgb(int c0, int c1, float t) {
        float u = 1f - t;
        int a = Math.round(Color.alpha(c0) * u + Color.alpha(c1) * t);
        int r = Math.round(Color.red(c0) * u + Color.red(c1) * t);
        int g = Math.round(Color.green(c0) * u + Color.green(c1) * t);
        int b = Math.round(Color.blue(c0) * u + Color.blue(c1) * t);
        return Color.argb(a, r, g, b);
    }
}
