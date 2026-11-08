package com.realme.samurai.notes;

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
 * Floating Warm-Gold (`#FFB800` / `#FFD60A`) 90 Hz AGSL Refractive Compose Orb (`WarmGoldComposeOrb`).
 */
public final class WarmGoldComposeOrb extends View {

    private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mTmpLoc = new int[2];

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final SamuraiSpring mPressSpring;
    private float mTouchX;
    private float mTouchY;
    private float mTouchAmp = 0f;
    private Runnable mOnTap;

    public WarmGoldComposeOrb(Context context, SamuraiGlassPrefs.Snapshot prefs, Runnable onTap) {
        super(context);
        mPrefs = (prefs != null) ? prefs : SamuraiGlassPrefs.read(context, null);
        mOnTap = onTap;

        mGlyphPaint.setTextAlign(Paint.Align.CENTER);
        mGlyphPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        mPressSpring = new SamuraiSpring(0f, (val, vel) -> {
            mTouchAmp = Math.max(0f, val);
            float scale = 1.0f - 0.08f * mTouchAmp;
            setScaleX(scale);
            setScaleY(scale);
            invalidate();
        }).setTuning(32f, 0.72f);
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mTouchX = event.getX();
            mTouchY = event.getY();
            mPressSpring.animateTo(1.0f);
            SamuraiGlassPrefs.hapticTick(this);
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            mTouchX = event.getX();
            mTouchY = event.getY();
            invalidate();
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            mPressSpring.animateTo(0.0f);
            if (event.getX() >= -12f && event.getX() <= getWidth() + 12f
                    && event.getY() >= -12f && event.getY() <= getHeight() + 12f) {
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                if (mOnTap != null) mOnTap.run();
            }
            return true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
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

        float radius = Math.min(w, h) * 0.5f;
        mHaloPaint.setColor(snap.darkTheme ? 0x33FFD60A : 0x30FFB800);
        canvas.drawCircle(w * 0.5f, h * 0.5f, radius, mHaloPaint);

        int warmGold = snap.darkTheme ? 0xFFFFD60A : 0xFFFFB800;
        SamuraiAgslEngine.bindSurfaceShader(
                mShader,
                this,
                mTmpLoc,
                bg,
                snap,
                radius - 1.5f * d,
                mTouchX,
                mTouchY,
                mTouchAmp,
                warmGold,
                0.76f);
        mShaderPaint.setShader(mShader);
        canvas.drawRect(0f, 0f, w, h, mShaderPaint);

        mGlyphPaint.setTextSize(22f * d);
        mGlyphPaint.setColor(0xFF1F1600);
        Paint.FontMetrics fm = mGlyphPaint.getFontMetrics();
        float cy = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText("\u270E", w * 0.5f, cy, mGlyphPaint);
    }
}
