package com.realme.samurai.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Signature iOS 26 Floating Bottom Morphing Liquid Glass Tab Bar (`SamuraiGlassPillTabBar`).
 *
 * Renders a floating refractive pill with a spring-driven liquid droplet indicator (`sminPoly`
 * shader) and an optional detached/liquid-fusing right-hand action/search orb!
 */
public class SamuraiGlassPillTabBar extends View {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
        default void onTrailingOrbClicked() {}
    }

    private final RuntimeShader mShader = SamuraiAgslEngine.createMorphPillBarShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mTmpLoc = new int[2];

    private String[] mLabels = new String[0];
    private String[] mGlyphs = new String[0];
    private boolean mHasTrailingOrb = false;
    private String mTrailingOrbGlyph = "\uD83D\uDD0D";
    private int mSelectedIndex = 0;
    private float mAnimatedIndex = 0f;
    private float mIndicatorStretch = 0f;

    private final SamuraiSpring mIndexSpring;
    private SamuraiGlassPrefs.Snapshot mPrefs;
    private OnTabSelectedListener mListener;

    public SamuraiGlassPillTabBar(
            Context context,
            SamuraiGlassPrefs.Snapshot prefs,
            String[] labels,
            String[] glyphs) {
        super(context);
        mPrefs = (prefs != null) ? prefs : SamuraiGlassPrefs.read(context, null);
        mLabels = labels != null ? labels : new String[0];
        mGlyphs = glyphs != null ? glyphs : new String[0];

        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mIconPaint.setTextAlign(Paint.Align.CENTER);
        mIconPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        mIndexSpring = new SamuraiSpring(0f, (val, vel) -> {
            mAnimatedIndex = val;
            mIndicatorStretch = Math.min(1.0f, Math.abs(vel) * 0.08f);
            invalidate();
        }).setTuning(28f, 0.76f);
    }

    public SamuraiGlassPillTabBar setTrailingOrb(boolean enabled, String glyph) {
        mHasTrailingOrb = enabled;
        if (glyph != null) {
            mTrailingOrbGlyph = glyph;
        }
        invalidate();
        return this;
    }

    public SamuraiGlassPillTabBar setOnTabSelectedListener(OnTabSelectedListener listener) {
        mListener = listener;
        return this;
    }

    public void setSelectedIndex(int idx, boolean animate) {
        if (mLabels.length == 0) return;
        int clamped = Math.max(0, Math.min(mLabels.length - 1, idx));
        mSelectedIndex = clamped;
        if (animate) {
            mIndexSpring.animateTo((float) clamped);
        } else {
            mIndexSpring.snapTo((float) clamped);
        }
    }

    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mLabels.length == 0) return false;
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float orbSpace = mHasTrailingOrb ? (64f * d) : 0f;
        float mainRight = w - orbSpace;

        int action = event.getActionMasked();
        float x = event.getX();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (x <= mainRight || !mHasTrailingOrb) {
                float slotW = mainRight / mLabels.length;
                int hit = Math.max(0, Math.min(mLabels.length - 1, (int) (x / Math.max(1f, slotW))));
                if (hit != mSelectedIndex) {
                    mSelectedIndex = hit;
                    mIndexSpring.animateTo((float) hit);
                    SamuraiGlassPrefs.hapticTick(this);
                    if (mListener != null) {
                        mListener.onTabSelected(hit);
                    }
                }
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            if (mHasTrailingOrb && x > mainRight) {
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                if (mListener != null) {
                    mListener.onTrailingOrbClicked();
                }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4 || mLabels.length == 0) return;

        float d = getResources().getDisplayMetrics().density;
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        SamuraiAgslEngine.BackdropPair bg =
                SamuraiAgslEngine.getSystemOrProceduralBackdrop(getContext(), snap.darkTheme);
        getLocationOnScreen(mTmpLoc);

        float pad = 4f * d;
        float orbGap = mHasTrailingOrb ? (10f * d) : 0f;
        float orbDia = mHasTrailingOrb ? (h - pad * 2f) : 0f;
        float mainW = w - pad * 2f - (mHasTrailingOrb ? (orbDia + orbGap) : 0f);
        float mainH = h - pad * 2f;
        float mainCx = pad + mainW * 0.5f;
        float mainCy = h * 0.5f;

        float orbCx = mHasTrailingOrb ? (w - pad - orbDia * 0.5f) : 0f;
        float orbHalf = mHasTrailingOrb ? (orbDia * 0.5f) : 0f;

        float innerPad = 5f * d;
        float slotW = (mainW - innerPad * 2f) / mLabels.length;
        float indCx = pad + innerPad + (mAnimatedIndex + 0.5f) * slotW;
        float indHalfW = (slotW * 0.48f) * (1.0f + 0.14f * mIndicatorStretch);
        float indHalfH = (mainH * 0.5f - 4f * d) * (1.0f - 0.06f * mIndicatorStretch);

        mShader.setInputShader("uSharpBg", bg.sharpShader);
        mShader.setInputShader("uSoftBg", bg.softShader);
        mShader.setFloatUniform("uSize", (float) w, (float) h);
        mShader.setFloatUniform("uScreenOrigin", (float) mTmpLoc[0], (float) mTmpLoc[1]);
        mShader.setFloatUniform("uScreenSize",
                (float) getResources().getDisplayMetrics().widthPixels,
                (float) getResources().getDisplayMetrics().heightPixels);
        mShader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        mShader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        mShader.setFloatUniform("uDensity", d);
        mShader.setFloatUniform("uDarkTheme", snap.darkTheme ? 1.0f : 0.0f);
        mShader.setFloatUniform("uRefractScale", snap.refractScale());
        mShader.setFloatUniform("uFrostScale", snap.frostScale());
        mShader.setFloatUniform("uMainRect", mainCx, mainCy, mainW * 0.5f, mainH * 0.5f);
        mShader.setFloatUniform("uOrbRect", orbCx, mainCy, orbHalf, orbHalf);
        mShader.setFloatUniform("uIndicatorRect", indCx, mainCy, indHalfW, indHalfH);
        mShader.setFloatUniform("uSminK", 14.0f * d);

        mShaderPaint.setShader(mShader);
        canvas.drawRect(0f, 0f, w, h, mShaderPaint);

        int activeColor = 0xFF0A84FF;
        int inactiveColor = snap.darkTheme ? 0xB8EBEBF5 : 0xA81C1C1E;
        mIconPaint.setTextSize(17.5f * d);
        mTextPaint.setTextSize(10.5f * d);

        for (int i = 0; i < mLabels.length; i++) {
            float cx = pad + innerPad + (i + 0.5f) * slotW;
            float dist = Math.abs(mAnimatedIndex - i);
            int col = (dist < 0.48f) ? activeColor : inactiveColor;
            mIconPaint.setColor(col);
            mTextPaint.setColor(col);
            String glyph = (i < mGlyphs.length) ? mGlyphs[i] : "\u2022";
            canvas.drawText(glyph, cx, mainCy - 2f * d, mIconPaint);
            canvas.drawText(mLabels[i], cx, mainCy + 14.5f * d, mTextPaint);
        }

        if (mHasTrailingOrb) {
            mIconPaint.setColor(snap.darkTheme ? 0xFFF2F2F7 : 0xFF1C1C1E);
            mIconPaint.setTextSize(17f * d);
            canvas.drawText(mTrailingOrbGlyph, orbCx, mainCy + 6f * d, mIconPaint);
        }
    }
}
