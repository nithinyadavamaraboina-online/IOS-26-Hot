package com.realme.samurai.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Tactile iOS 26 Liquid-Droplet Toggle Switch (`SamuraiGlassSwitch`) with 90Hz spring elongation.
 */
public class SamuraiGlassSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private boolean mChecked;
    private float mProgress;
    private float mStretch;
    private boolean mDarkTheme;
    private int mActiveColor = 0xFF30D158;
    private OnCheckedChangeListener mListener;
    private final SamuraiSpring mSpring;

    public SamuraiGlassSwitch(Context context, boolean initialChecked, boolean darkTheme) {
        super(context);
        mChecked = initialChecked;
        mProgress = initialChecked ? 1f : 0f;
        mDarkTheme = darkTheme;
        mRimPaint.setStyle(Paint.Style.STROKE);

        mSpring = new SamuraiSpring(mProgress, (val, vel) -> {
            mProgress = Math.max(-0.08f, Math.min(1.08f, val));
            mStretch = Math.min(1.0f, Math.abs(vel) * 0.12f);
            invalidate();
        }).setTuning(32f, 0.74f);
    }

    public SamuraiGlassSwitch setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
        return this;
    }

    public SamuraiGlassSwitch setActiveColor(int argb) {
        mActiveColor = argb;
        invalidate();
        return this;
    }

    public void setDarkTheme(boolean dark) {
        mDarkTheme = dark;
        invalidate();
    }

    public void setChecked(boolean checked, boolean animate) {
        mChecked = checked;
        if (animate) {
            mSpring.animateTo(checked ? 1f : 0f);
        } else {
            mSpring.snapTo(checked ? 1f : 0f);
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        setMeasuredDimension(Math.round(54f * d), Math.round(31f * d));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mStretch = 0.35f;
            invalidate();
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            mChecked = !mChecked;
            mSpring.animateTo(mChecked ? 1f : 0f);
            SamuraiGlassPrefs.hapticClick(getContext(), this);
            if (mListener != null) {
                mListener.onCheckedChanged(mChecked);
            }
            return true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mStretch = 0f;
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        float pad = 2.2f * d;

        int offCol = mDarkTheme ? 0x52787880 : 0x3D787880;
        float t = Math.max(0f, Math.min(1f, mProgress));
        mTrackPaint.setColor(blendArgb(offCol, mActiveColor, t));
        mRect.set(0, 0, w, h);
        canvas.drawRoundRect(mRect, h * 0.5f, h * 0.5f, mTrackPaint);

        float baseDia = h - pad * 2f;
        float extraW = baseDia * 0.24f * mStretch;
        float thumbW = baseDia + extraW;
        float travel = w - pad * 2f - thumbW;
        float left = pad + travel * t;

        mThumbPaint.setColor(0xFFFFFFFF);
        mThumbPaint.setShadowLayer(3.2f * d, 0f, 1.4f * d, 0x38000000);
        mRect.set(left, pad, left + thumbW, h - pad);
        canvas.drawRoundRect(mRect, baseDia * 0.5f, baseDia * 0.5f, mThumbPaint);

        mRimPaint.setStrokeWidth(1.0f * d);
        mRimPaint.setColor(0x44FFFFFF);
        canvas.drawRoundRect(mRect, baseDia * 0.5f, baseDia * 0.5f, mRimPaint);
    }

    private static int blendArgb(int c0, int c1, float t) {
        int a = Math.round(((c0 >>> 24) & 0xFF) * (1f - t) + ((c1 >>> 24) & 0xFF) * t);
        int r = Math.round(((c0 >> 16) & 0xFF) * (1f - t) + ((c1 >> 16) & 0xFF) * t);
        int g = Math.round(((c0 >> 8) & 0xFF) * (1f - t) + ((c1 >> 8) & 0xFF) * t);
        int b = Math.round((c0 & 0xFF) * (1f - t) + (c1 & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
