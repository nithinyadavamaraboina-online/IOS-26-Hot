package com.realme.samurai.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * Reusable 90Hz AGSL Liquid Glass Card Container (`SamuraiGlassCard`).
 *
 * Renders G2 superellipse squircle geometry, Kyant0 spherical meniscus bevel refraction, 7-band
 * ROYGCBP spectral dispersion, Gaussian touch-bulge lens, and 34-degree Fresnel rim catchlights.
 */
public class SamuraiGlassCard extends LinearLayout {

    private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mTmpLoc = new int[2];
    private final SamuraiSpring mTouchSpring;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private SamuraiAgslEngine.BackdropPair mCustomBackdrop;
    private float mCornerRadiusDp = 26f;
    private float mTouchX;
    private float mTouchY;
    private float mTouchAmp = 0f;
    private int mTintColor = 0x00000000;
    private float mTintStrength = 0f;
    private boolean mInteractivePressScale = false;

    public SamuraiGlassCard(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = (prefs != null) ? prefs : SamuraiGlassPrefs.read(context, null);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        mTouchSpring = new SamuraiSpring(0f, (value, vel) -> {
            mTouchAmp = Math.max(0f, value);
            if (mInteractivePressScale) {
                float s = 1.0f - 0.025f * mTouchAmp;
                setScaleX(s);
                setScaleY(s);
            }
            invalidate();
        }).setTuning(30f, 0.76f);
    }

    public SamuraiGlassCard setCornerRadiusDp(float dp) {
        mCornerRadiusDp = dp;
        invalidate();
        return this;
    }

    public SamuraiGlassCard setTint(int argb, float strength) {
        mTintColor = argb;
        mTintStrength = Math.max(0f, Math.min(strength, 1f));
        invalidate();
        return this;
    }

    public SamuraiGlassCard setCustomBackdrop(SamuraiAgslEngine.BackdropPair pair) {
        mCustomBackdrop = pair;
        invalidate();
        return this;
    }

    public SamuraiGlassCard setInteractivePressScale(boolean enabled) {
        mInteractivePressScale = enabled;
        return this;
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            mTouchX = ev.getX();
            mTouchY = ev.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                mTouchSpring.animateTo(1.0f);
            } else {
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mTouchSpring.animateTo(0.0f);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() > 2 && getHeight() > 2) {
            SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                    ? mPrefs
                    : SamuraiGlassPrefs.read(getContext(), null);
            SamuraiAgslEngine.BackdropPair bg = (mCustomBackdrop != null)
                    ? mCustomBackdrop
                    : SamuraiAgslEngine.getSystemOrProceduralBackdrop(getContext(), snap.darkTheme);
            float radiusPx = mCornerRadiusDp * getResources().getDisplayMetrics().density;
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
                    mTintColor,
                    mTintStrength);
            mPaint.setShader(mShader);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), mPaint);
        }
        super.onDraw(canvas);
    }
}
