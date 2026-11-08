package com.realme.samurai.styler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 90Hz iOS 26 Elastic Overshoot Slider (`0..150%`) with Refractive Liquid Glass Droplet Thumb.
 *
 * - Scrubbing past `0%` or `150%` rubber-bands horizontally (`±12dp`) while thinning track height
 *   to conserve visual volume.
 * - While touched/dragged, the pill thumb lifts (`1.36×`) and morphs from opaque white porcelain
 *   into a live AGSL-refracting clear water droplet (`PanelShaderInstance` + refracted cyan core).
 */
public class LiquidGlassDropletSlider extends View {
    public interface OnPercentChangedListener {
        void onPercentChanged(int percent);
    }

    private final StylerAgslEngine.OpticalContext mOpt;
    private final StylerAgslEngine.PanelShaderInstance mDropletShader =
            new StylerAgslEngine.PanelShaderInstance();
    private final float mDensity;
    private final int mMinPct;
    private final int mMaxPct;
    private final int mDefaultPct;

    private int mPercent;
    private float mProgress;
    private float mOverscroll = 0f;
    private float mOverscrollVel = 0f;
    private float mLift = 0f;
    private float mLiftVel = 0f;
    private boolean mScrubbing = false;
    private boolean mHitEdgeHaptic = false;
    private long mLastNanos = 0L;

    private final RectF mTrackRect = new RectF();
    private final RectF mFillRect = new RectF();
    private final RectF mThumbRect = new RectF();
    private final int[] mLoc = new int[2];

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAgslDropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnPercentChangedListener mListener;

    public LiquidGlassDropletSlider(
            Context context,
            StylerAgslEngine.OpticalContext opt,
            int minPct,
            int maxPct,
            int initialPct) {
        this(context, opt, minPct, maxPct, initialPct, 100);
    }

    public LiquidGlassDropletSlider(
            Context context,
            StylerAgslEngine.OpticalContext opt,
            int minPct,
            int maxPct,
            int initialPct,
            int defaultPct) {
        super(context);
        mOpt = opt;
        mDensity = context.getResources().getDisplayMetrics().density;
        mMinPct = minPct;
        mMaxPct = Math.max(minPct + 1, maxPct);
        mDefaultPct = StylerPrefsBridge.clamp(defaultPct, mMinPct, mMaxPct);
        mPercent = StylerPrefsBridge.clamp(initialPct, mMinPct, mMaxPct);
        mProgress = (mPercent - mMinPct) / (float) (mMaxPct - mMinPct);

        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(1.4f * mDensity);
    }

    public void setOnPercentChangedListener(OnPercentChangedListener listener) {
        mListener = listener;
    }

    public int getPercent() {
        return mPercent;
    }

    public void setPercent(int pct, boolean notify) {
        int clean = StylerPrefsBridge.clamp(pct, mMinPct, mMaxPct);
        mPercent = clean;
        mProgress = (clean - mMinPct) / (float) (mMaxPct - mMinPct);
        if (notify && mListener != null) {
            mListener.onPercentChanged(clean);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.max(MeasureSpec.getSize(widthMeasureSpec), Math.round(180f * mDensity));
        int h = resolveSize(Math.round(48f * mDensity), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        float sidePad = 22f * mDensity;
        float usableW = Math.max(1f, getWidth() - sidePad * 2f);
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            mScrubbing = true;
            mHitEdgeHaptic = false;
            updateFromTouch(event.getX(), sidePad, usableW);
            StylerPrefsBridge.performTick(this, false);
            startSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            updateFromTouch(event.getX(), sidePad, usableW);
            startSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mScrubbing = false;
            StylerPrefsBridge.performTick(this, false);
            startSpring();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x, float sidePad, float usableW) {
        float raw = (x - sidePad) / usableW;
        float clamped = StylerPrefsBridge.clampF(raw, 0f, 1f);
        mProgress = clamped;

        int newPct = Math.round(mMinPct + clamped * (mMaxPct - mMinPct));
        if (newPct != mPercent) {
            boolean crossedDefault = (mPercent != mDefaultPct && newPct == mDefaultPct);
            mPercent = newPct;
            if (crossedDefault) {
                StylerPrefsBridge.performTick(this, true);
            }
            if (mListener != null) {
                mListener.onPercentChanged(mPercent);
            }
        }

        if (raw < 0f) {
            mOverscroll = -rubberBand(-raw);
        } else if (raw > 1f) {
            mOverscroll = rubberBand(raw - 1f);
        } else {
            mOverscroll = 0f;
        }

        if ((raw <= 0f || raw >= 1f) && !mHitEdgeHaptic) {
            mHitEdgeHaptic = true;
            StylerPrefsBridge.performTick(this, true);
        } else if (raw > 0.03f && raw < 0.97f) {
            mHitEdgeHaptic = false;
        }
    }

    private static float rubberBand(float excess) {
        return StylerPrefsBridge.clampF(1f - 1f / (1f + excess * 2.8f), 0f, 1f);
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

        if (!mScrubbing) {
            float fOver = -520f * mOverscroll - 36f * mOverscrollVel;
            mOverscrollVel += fOver * dt;
            mOverscroll += mOverscrollVel * dt;
        }
        float targetLift = mScrubbing ? 1f : 0f;
        float fLift = 540f * (targetLift - mLift) - 38f * mLiftVel;
        mLiftVel += fLift * dt;
        mLift = StylerPrefsBridge.clampF(mLift + mLiftVel * dt, 0f, 1.16f);

        boolean overDone = !mScrubbing
                && Math.abs(mOverscroll) < 0.002f
                && Math.abs(mOverscrollVel) < 0.01f;
        boolean liftDone = !mScrubbing
                && Math.abs(targetLift - mLift) < 0.003f
                && Math.abs(mLiftVel) < 0.01f;
        if (overDone && liftDone) {
            mOverscroll = 0f;
            mOverscrollVel = 0f;
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

        float sidePad = 22f * mDensity;
        float stretchPx = mOverscroll * 12f * mDensity;
        float thin = 1f - 0.18f * Math.abs(mOverscroll);
        float barH = 10.5f * mDensity * thin;
        float cy = h * 0.5f;

        float leftX = sidePad + Math.min(stretchPx, 0f);
        float rightX = (w - sidePad) + Math.max(stretchPx, 0f);
        mTrackRect.set(leftX, cy - barH * 0.5f, rightX, cy + barH * 0.5f);
        float barR = barH * 0.5f;

        mTrackPaint.setColor(dark
                ? Color.argb(92, 210, 232, 255)
                : Color.argb(66, 16, 36, 72));
        canvas.drawRoundRect(mTrackRect, barR, barR, mTrackPaint);

        // Subtle default calibration tick mark along track (20% / 20% / 70%)
        float normDef = (mDefaultPct - mMinPct) / (float) (mMaxPct - mMinPct);
        if (normDef > 0.05f && normDef < 0.95f) {
            float tickX = leftX + (rightX - leftX) * normDef;
            mTrackPaint.setColor(dark ? Color.argb(135, 255, 255, 255) : Color.argb(110, 20, 40, 80));
            canvas.drawCircle(tickX, cy - barH * 0.95f, 1.6f * mDensity, mTrackPaint);
        }

        float thumbCx = leftX + (rightX - leftX) * mProgress;
        mFillRect.set(leftX, mTrackRect.top, Math.max(thumbCx, leftX + barH), mTrackRect.bottom);
        mFillPaint.setShader(new LinearGradient(
                mFillRect.left, mFillRect.top, mFillRect.right, mFillRect.bottom,
                Color.rgb(34, 152, 255),
                Color.rgb(78, 232, 255),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mFillRect, barR, barR, mFillPaint);

        // Droplet Thumb: lifts 1.36x and morphs into live AGSL refractive water droplet!
        float morph = StylerPrefsBridge.clampF(mLift, 0f, 1f);
        float tw = (30f * mDensity) * (1f + 0.36f * morph);
        float th = (21f * mDensity) * (1f + 0.32f * morph);
        mThumbRect.set(thumbCx - tw * 0.5f, cy - th * 0.5f, thumbCx + tw * 0.5f, cy + th * 0.5f);
        float tr = th * 0.5f;

        mShadowPaint.setColor(Color.argb(Math.round(48f + 30f * morph), 2, 12, 30));
        canvas.drawRoundRect(
                mThumbRect.left,
                mThumbRect.top + (2.4f + 2.4f * morph) * mDensity,
                mThumbRect.right,
                mThumbRect.bottom + (2.4f + 2.4f * morph) * mDensity,
                tr, tr, mShadowPaint);

        if (morph > 0.04f) {
            getLocationOnScreen(mLoc);
            if (mDropletShader.bindToPaint(
                    mAgslDropPaint,
                    mOpt,
                    tw,
                    th,
                    mLoc[0] + mThumbRect.left,
                    mLoc[1] + mThumbRect.top,
                    tr,
                    mDensity,
                    tw * 0.5f,
                    th * 0.5f,
                    morph,
                    0.28f)) {
                canvas.save();
                canvas.translate(mThumbRect.left, mThumbRect.top);
                canvas.drawRect(0f, 0f, tw, th, mAgslDropPaint);
                canvas.restore();
            }

            // Refracted magnified cyan track core visible through the clear water droplet
            canvas.save();
            canvas.clipRect(mThumbRect);
            float refractScale = mOpt != null
                    ? StylerPrefsBridge.clampF(mOpt.refractScale, 0f, 1.5f)
                    : (StylerPrefsBridge.DEFAULT_REFRACTION_PCT / 100f);
            float magH = barH * (1f + 0.28f * morph * refractScale);
            mFillPaint.setAlpha(Math.round(185f * morph));
            canvas.drawRoundRect(
                    mThumbRect.left - barR,
                    cy - magH * 0.5f,
                    thumbCx + 4f * mDensity * morph,
                    cy + magH * 0.5f,
                    magH * 0.5f,
                    magH * 0.5f,
                    mFillPaint);
            mFillPaint.setAlpha(255);
            canvas.restore();
        }

        int whiteA = StylerPrefsBridge.clamp(Math.round((1f - 0.82f * morph) * 255f), 28, 255);
        mThumbPaint.setShader(new LinearGradient(
                mThumbRect.left, mThumbRect.top, mThumbRect.right, mThumbRect.bottom,
                Color.argb(whiteA, 255, 255, 255),
                Color.argb(Math.round(whiteA * 0.86f), 228, 245, 255),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mThumbRect, tr, tr, mThumbPaint);

        mRimPaint.setShader(new LinearGradient(
                mThumbRect.left, mThumbRect.top, mThumbRect.right, mThumbRect.bottom,
                new int[]{
                        Color.argb(Math.min(255, Math.round(180f + 75f * morph)), 255, 255, 255),
                        Color.argb(Math.round(24f + 22f * morph), 255, 255, 255),
                        Color.argb(Math.min(255, Math.round(115f + 95f * morph)), 188, 238, 255)
                },
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mThumbRect, tr, tr, mRimPaint);

        if (keepGoing) {
            postInvalidateOnAnimation();
        }
    }
}
