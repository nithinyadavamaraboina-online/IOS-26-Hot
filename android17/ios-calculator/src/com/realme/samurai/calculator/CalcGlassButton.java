package com.realme.samurai.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

/**
 * 90 Hz AGSL Refractive Circular / Capsule Keypad Button (`CalcGlassButton`).
 *
 * Uses `SamuraiAgslEngine.createSurfaceShader()` with Gaussian touch-bulge lens, `SamuraiSpring`
 * press bounce, AW8697 LRA `hapticTick`, and smooth operator white-amber inversion.
 */
public final class CalcGlassButton extends View {

    public static final int ROLE_DIGIT = 0;
    public static final int ROLE_UTILITY = 1;
    public static final int ROLE_OPERATOR = 2;
    public static final int ROLE_SCIENTIFIC = 3;

    private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mTmpLoc = new int[2];

    private final int mRole;
    private String mLabel;
    private boolean mLeftAlignedCapsule = false;
    private boolean mSelectedState = false;
    private float mTouchX;
    private float mTouchY;
    private float mTouchAmp = 0f;
    private float mSelectProgress = 0f;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final SamuraiSpring mPressSpring;
    private final SamuraiSpring mSelectSpring;
    private Runnable mOnTap;
    private Runnable mOnLongTap;
    private boolean mLongTriggered = false;
    private final Runnable mLongPressRunnable = () -> {
        if (isPressed() && mOnLongTap != null) {
            mLongTriggered = true;
            SamuraiGlassPrefs.hapticHeavySpring(getContext(), this);
            mOnLongTap.run();
        }
    };

    public CalcGlassButton(
            Context context,
            SamuraiGlassPrefs.Snapshot prefs,
            String label,
            int role) {
        super(context);
        mPrefs = (prefs != null) ? prefs : SamuraiGlassPrefs.read(context, null);
        mLabel = label;
        mRole = role;

        mTextPaint.setTextAlign(Paint.Align.CENTER);
        if (role == ROLE_OPERATOR) {
            mTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        } else if (role == ROLE_SCIENTIFIC) {
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            mTextPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        mTextPaint.setFontFeatureSettings("tnum");

        mPressSpring = new SamuraiSpring(0f, (val, vel) -> {
            mTouchAmp = Math.max(0f, val);
            float scale = 1.0f - 0.065f * mTouchAmp;
            setScaleX(scale);
            setScaleY(scale);
            invalidate();
        }).setTuning(34f, 0.72f);

        mSelectSpring = new SamuraiSpring(0f, (val, vel) -> {
            mSelectProgress = Math.max(0f, Math.min(1f, val));
            invalidate();
        }).setTuning(28f, 0.80f);
    }

    public CalcGlassButton setLeftAlignedCapsule(boolean enabled) {
        mLeftAlignedCapsule = enabled;
        invalidate();
        return this;
    }

    public CalcGlassButton setOnTap(Runnable onTap) {
        mOnTap = onTap;
        return this;
    }

    public CalcGlassButton setOnLongTap(Runnable onLongTap) {
        mOnLongTap = onLongTap;
        return this;
    }

    public void setLabel(String label) {
        if (label != null && !label.equals(mLabel)) {
            mLabel = label;
            invalidate();
        }
    }

    public String getLabel() {
        return mLabel;
    }

    public void setSelectedOperator(boolean selected) {
        if (mSelectedState != selected) {
            mSelectedState = selected;
            mSelectSpring.animateTo(selected ? 1f : 0f);
        }
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            setPressed(true);
            mLongTriggered = false;
            mTouchX = event.getX();
            mTouchY = event.getY();
            mPressSpring.animateTo(1.0f);
            SamuraiGlassPrefs.hapticTick(this);
            if (mOnLongTap != null) {
                postDelayed(mLongPressRunnable, 420L);
            }
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            mTouchX = event.getX();
            mTouchY = event.getY();
            invalidate();
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(mLongPressRunnable);
            setPressed(false);
            mPressSpring.animateTo(0.0f);
            if (!mLongTriggered
                    && event.getX() >= -12f && event.getX() <= getWidth() + 12f
                    && event.getY() >= -12f && event.getY() <= getHeight() + 12f) {
                if (mOnTap != null) {
                    mOnTap.run();
                }
            }
            return true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(mLongPressRunnable);
            setPressed(false);
            mPressSpring.animateTo(0.0f);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 2) return;

        float d = getResources().getDisplayMetrics().density;
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        SamuraiAgslEngine.BackdropPair bg =
                SamuraiAgslEngine.getSystemOrProceduralBackdrop(getContext(), snap.darkTheme);

        float radiusPx = Math.min(w, h) * 0.5f;
        int tintColor;
        float tintStrength;
        int textColor;

        switch (mRole) {
            case ROLE_OPERATOR: {
                // Luminous iOS amber-orange (#FF9F0A) -> inverts to glowing white-amber (#FFF8EC) when selected
                int amber = 0xFFFF9F0A;
                int whiteAmber = 0xFFFFF8EC;
                tintColor = blendArgb(amber, whiteAmber, mSelectProgress);
                tintStrength = 0.78f + 0.14f * mSelectProgress + 0.08f * mTouchAmp;
                textColor = blendArgb(0xFFFFFFFF, 0xFFFF9500, mSelectProgress);
                break;
            }
            case ROLE_UTILITY: {
                tintColor = snap.darkTheme ? 0xFFA4ADBF : 0xFF8E99B0;
                tintStrength = (snap.darkTheme ? 0.44f : 0.34f) + 0.14f * mTouchAmp;
                textColor = snap.darkTheme ? 0xFFF8FAFF : 0xFF141821;
                break;
            }
            case ROLE_SCIENTIFIC: {
                int baseTint = snap.darkTheme ? 0xFF1D2332 : 0xFFDDE5F4;
                int activeTint = 0xFF0A84FF;
                tintColor = blendArgb(baseTint, activeTint, mSelectProgress);
                tintStrength = (snap.darkTheme ? 0.36f : 0.28f) + 0.36f * mSelectProgress + 0.14f * mTouchAmp;
                textColor = mSelectProgress > 0.4f
                        ? 0xFFFFFFFF
                        : (snap.darkTheme ? 0xFFE5EAF5 : 0xFF1C2230);
                break;
            }
            case ROLE_DIGIT:
            default: {
                tintColor = snap.darkTheme ? 0xFF2B3142 : 0xFFFFFFFF;
                tintStrength = (snap.darkTheme ? 0.30f : 0.24f) + 0.16f * mTouchAmp;
                textColor = snap.darkTheme ? 0xFFF5F7FC : 0xFF12151E;
                break;
            }
        }

        SamuraiAgslEngine.bindSurfaceShader(
                mShader,
                this,
                mTmpLoc,
                bg,
                snap,
                radiusPx,
                mTouchX,
                mTouchY,
                mTouchAmp,
                tintColor,
                Math.min(0.95f, tintStrength));
        mShaderPaint.setShader(mShader);
        canvas.drawRect(0f, 0f, w, h, mShaderPaint);

        if (mTouchAmp > 0.02f) {
            int flashAlpha = Math.round(38f * mTouchAmp);
            mGlowPaint.setColor((flashAlpha << 24) | 0x00FFFFFF);
            canvas.drawRoundRect(0f, 0f, w, h, radiusPx, radiusPx, mGlowPaint);
        }

        float minDim = Math.min(w, h);
        float textSizePx;
        if (mRole == ROLE_OPERATOR) {
            textSizePx = Math.min(32f * d, minDim * 0.48f);
        } else if (mRole == ROLE_SCIENTIFIC) {
            textSizePx = Math.min(15.5f * d, minDim * 0.39f);
        } else if (mRole == ROLE_UTILITY) {
            textSizePx = Math.min(23f * d, minDim * 0.38f);
        } else {
            textSizePx = Math.min(28f * d, minDim * 0.44f);
        }
        mTextPaint.setTextSize(textSizePx);
        mTextPaint.setColor(textColor);

        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float cy = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        float cx = mLeftAlignedCapsule ? (h * 0.5f) : (w * 0.5f);
        canvas.drawText(mLabel, cx, cy, mTextPaint);
    }

    private static int blendArgb(int c0, int c1, float t) {
        float u = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((c0 >>> 24) & 0xFF) * (1f - u) + ((c1 >>> 24) & 0xFF) * u);
        int r = Math.round(((c0 >> 16) & 0xFF) * (1f - u) + ((c1 >> 16) & 0xFF) * u);
        int g = Math.round(((c0 >> 8) & 0xFF) * (1f - u) + ((c1 >> 8) & 0xFF) * u);
        int b = Math.round((c0 & 0xFF) * (1f - u) + (c1 & 0xFF) * u);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
