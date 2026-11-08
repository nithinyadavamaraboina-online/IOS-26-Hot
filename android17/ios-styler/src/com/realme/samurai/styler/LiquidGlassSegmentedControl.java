package com.realme.samurai.styler;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 90Hz iOS 26 Segmented Liquid Glass Selector (`Default` / `Dark` / `Clear`, or Glass Material modes).
 *
 * Both the recessed capsule track AND the sliding/lifting active droplet pill are rendered via
 * real-time AGSL `PanelShaderInstance` refraction over the live screen-space backdrop, driven by
 * a critically-damped Hooke spring (`stiffness=460, damping=34`).
 */
public class LiquidGlassSegmentedControl extends View {
    public interface OnSegmentSelectedListener {
        void onSegmentSelected(int index, int value);
    }

    private final StylerAgslEngine.OpticalContext mOpt;
    private final String[] mLabels;
    private final int[] mValues;
    private final StylerAgslEngine.PanelShaderInstance mTrackShader =
            new StylerAgslEngine.PanelShaderInstance();
    private final StylerAgslEngine.PanelShaderInstance mThumbShader =
            new StylerAgslEngine.PanelShaderInstance();

    private final float mDensity;
    private final Paint mShellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF mThumbRect = new RectF();
    private final int[] mLoc = new int[2];

    private int mSelectedIndex = 0;
    private float mPillIndex = 0f;
    private float mPillVel = 0f;
    private float mLift = 0f;
    private float mLiftVel = 0f;
    private boolean mScrubbing = false;
    private float mTouchX = -100f;
    private float mTouchY = -100f;
    private long mLastNanos = 0L;
    private OnSegmentSelectedListener mListener;

    public LiquidGlassSegmentedControl(
            Context context,
            StylerAgslEngine.OpticalContext opt,
            String[] labels,
            int[] values,
            int initialValue) {
        super(context);
        mOpt = opt;
        mLabels = labels;
        mValues = values;
        mDensity = context.getResources().getDisplayMetrics().density;

        for (int i = 0; i < values.length; i++) {
            if (values[i] == initialValue) {
                mSelectedIndex = i;
                mPillIndex = (float) i;
                break;
            }
        }

        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(1.25f * mDensity);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public void setOnSegmentSelectedListener(OnSegmentSelectedListener listener) {
        mListener = listener;
    }

    public void setSelectedValue(int value, boolean notify) {
        for (int i = 0; i < mValues.length; i++) {
            if (mValues[i] == value && mSelectedIndex != i) {
                mSelectedIndex = i;
                if (notify && mListener != null) {
                    mListener.onSegmentSelected(i, value);
                }
                startSpring();
                return;
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Math.max(MeasureSpec.getSize(widthMeasureSpec), Math.round(220f * mDensity));
        int h = resolveSize(Math.round(50f * mDensity), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        int action = event.getActionMasked();
        mTouchX = event.getX();
        mTouchY = event.getY();

        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            mScrubbing = true;
            StylerPrefsBridge.performTick(this, false);
            updateFromTouch(event.getX());
            startSpring();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (mScrubbing) {
                updateFromTouch(event.getX());
                startSpring();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mScrubbing) {
                mScrubbing = false;
                int snapped = StylerPrefsBridge.clamp(Math.round(mPillIndex), 0, mLabels.length - 1);
                if (snapped != mSelectedIndex) {
                    mSelectedIndex = snapped;
                    if (mListener != null) {
                        mListener.onSegmentSelected(mSelectedIndex, mValues[mSelectedIndex]);
                    }
                }
                StylerPrefsBridge.performTick(this, true);
                startSpring();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateFromTouch(float x) {
        float pad = 4.5f * mDensity;
        float slotW = Math.max(1f, (getWidth() - pad * 2f) / Math.max(1, mLabels.length));
        float raw = StylerPrefsBridge.clampF(
                (x - pad) / slotW - 0.5f, 0f, (float) (mLabels.length - 1));
        int nearest = StylerPrefsBridge.clamp(Math.round(raw), 0, mLabels.length - 1);
        if (nearest != mSelectedIndex) {
            mSelectedIndex = nearest;
            StylerPrefsBridge.performTick(this, true);
            if (mListener != null) {
                mListener.onSegmentSelected(mSelectedIndex, mValues[mSelectedIndex]);
            }
        }
        mPillIndex = raw;
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
            float fIdx = 460f * (mSelectedIndex - mPillIndex) - 34f * mPillVel;
            mPillVel += fIdx * dt;
            mPillIndex += mPillVel * dt;
        }
        float targetLift = mScrubbing ? 1f : 0f;
        float fLift = 520f * (targetLift - mLift) - 36f * mLiftVel;
        mLiftVel += fLift * dt;
        mLift = StylerPrefsBridge.clampF(mLift + mLiftVel * dt, 0f, 1.15f);

        boolean idxDone = !mScrubbing
                && Math.abs(mSelectedIndex - mPillIndex) < 0.002f
                && Math.abs(mPillVel) < 0.01f;
        boolean liftDone = !mScrubbing
                && Math.abs(targetLift - mLift) < 0.003f
                && Math.abs(mLiftVel) < 0.01f;
        if (idxDone && liftDone) {
            mPillIndex = (float) mSelectedIndex;
            mPillVel = 0f;
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

        boolean keepAnimating = stepSpring();
        float wf = (float) w;
        float hf = (float) h;
        float outerR = hf * 0.5f;
        boolean dark = mOpt != null && mOpt.isDarkBackdrop();

        getLocationOnScreen(mLoc);

        // 1. Recessed Liquid Glass track capsule
        if (mTrackShader.bindToPaint(
                mShellPaint, mOpt, wf, hf, (float) mLoc[0], (float) mLoc[1],
                outerR, mDensity, mTouchX, mTouchY, mLift * 0.5f, -0.05f)) {
            canvas.drawRect(0f, 0f, wf, hf, mShellPaint);
        } else {
            mShellPaint.setShader(null);
            mShellPaint.setColor(dark ? Color.argb(120, 14, 24, 42) : Color.argb(120, 232, 242, 255));
            canvas.drawRoundRect(0f, 0f, wf, hf, outerR, outerR, mShellPaint);
        }

        // 2. Active sliding & lifting refractive Liquid Glass thumb pill
        float pad = 4.5f * mDensity;
        float slotW = (wf - pad * 2f) / Math.max(1, mLabels.length);
        float expandX = 2.6f * mDensity * mLift;
        float expandY = 2.0f * mDensity * mLift;
        float pLeft = pad + mPillIndex * slotW - expandX;
        float pTop = pad - expandY;
        float pRight = pLeft + slotW + expandX * 2f;
        float pBot = hf - pad + expandY;
        mThumbRect.set(pLeft, pTop, pRight, pBot);
        float innerR = mThumbRect.height() * 0.5f;

        mShadowPaint.setColor(Color.argb(Math.round(38f + 26f * mLift), 4, 12, 28));
        canvas.drawRoundRect(
                mThumbRect.left,
                mThumbRect.top + (1.8f + 1.8f * mLift) * mDensity,
                mThumbRect.right,
                mThumbRect.bottom + (1.8f + 1.8f * mLift) * mDensity,
                innerR, innerR, mShadowPaint);

        float tw = mThumbRect.width();
        float th = mThumbRect.height();
        if (mThumbShader.bindToPaint(
                mThumbPaint, mOpt, tw, th,
                mLoc[0] + mThumbRect.left, mLoc[1] + mThumbRect.top,
                innerR, mDensity, tw * 0.5f, th * 0.5f, 0.35f + 0.65f * mLift, 0.38f)) {
            canvas.save();
            canvas.translate(mThumbRect.left, mThumbRect.top);
            canvas.drawRect(0f, 0f, tw, th, mThumbPaint);
            canvas.restore();
        } else {
            mThumbPaint.setShader(null);
            mThumbPaint.setColor(dark ? Color.argb(175, 72, 148, 255) : Color.argb(225, 255, 255, 255));
            canvas.drawRoundRect(mThumbRect, innerR, innerR, mThumbPaint);
        }

        // Extra luminous 34-deg specular rim on lifted active pill
        mRimPaint.setShader(new LinearGradient(
                mThumbRect.left, mThumbRect.top, mThumbRect.right, mThumbRect.bottom,
                new int[]{
                        Color.argb(235, 255, 255, 255),
                        Color.argb(36, 255, 255, 255),
                        Color.argb(175, 195, 238, 255)
                },
                new float[]{0f, 0.50f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mThumbRect, innerR, innerR, mRimPaint);

        // 3. Crisp segment labels
        float textSp = mLabels.length >= 3 ? 12.6f : 13.5f;
        mTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, textSp, getResources().getDisplayMetrics()));
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = hf * 0.5f - (fm.ascent + fm.descent) * 0.5f;

        for (int i = 0; i < mLabels.length; i++) {
            float cx = pad + (i + 0.5f) * slotW;
            boolean active = Math.abs(i - mPillIndex) < 0.52f;
            if (active) {
                mTextPaint.setColor(dark ? Color.WHITE : Color.rgb(10, 26, 56));
                mTextPaint.setShadowLayer(2f * mDensity, 0f, 0.8f * mDensity,
                        dark ? Color.argb(95, 0, 10, 28) : Color.argb(40, 255, 255, 255));
            } else {
                mTextPaint.setColor(dark
                        ? Color.argb(196, 218, 234, 255)
                        : Color.argb(188, 24, 44, 78));
                mTextPaint.clearShadowLayer();
            }
            canvas.drawText(mLabels[i], cx, baseline, mTextPaint);
        }

        if (keepAnimating) {
            postInvalidateOnAnimation();
        }
    }
}
