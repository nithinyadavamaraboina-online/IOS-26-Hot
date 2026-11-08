package com.realme.samurai.systemui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Full-Screen Frosted Liquid Glass iOS 26 Slide-to-Power-Off Menu (`IosPowerMenuView`).
 *
 * Interactive horizontal drag sliders:
 * 1. Slide to Power Off (`mWindowManagerFuncs.shutdown()` / `IPowerManager.shutdown`)
 * 2. Slide to Reboot (`PowerManager.reboot(null)` / `mWindowManagerFuncs.reboot(false)`)
 * 3. Slide to Recovery (`PowerManager.reboot("recovery")`)
 * 4. Bottom Cancel refractive glass orb
 */
public final class IosPowerMenuView extends FrameLayout {

    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;

    private final Context mAppContext;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mLp;
    private final LinearLayout mCenterStack;
    private final Paint mScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SamuraiSpring mOpenSpring;

    private Object mWmFuncs;
    private float mProgress = 0f;
    private boolean mShowing = false;
    private boolean mAttached = false;

    @SuppressWarnings("deprecation")
    public IosPowerMenuView(Context context) {
        super(context.getApplicationContext());
        mAppContext = context.getApplicationContext();
        mWindowManager = mAppContext.getSystemService(WindowManager.class);

        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        mLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT);
        mLp.gravity = Gravity.FILL;
        mLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLp.setBlurBehindRadius(SystemUiGlassHelper.dpInt(mAppContext, 54f));
        mLp.setTitle("SamuraiIos26PowerMenu");
        applyPrivateFlags(mLp);

        mCenterStack = new LinearLayout(mAppContext);
        mCenterStack.setOrientation(LinearLayout.VERTICAL);
        mCenterStack.setGravity(Gravity.CENTER_HORIZONTAL);
        int sidePad = SystemUiGlassHelper.dpInt(mAppContext, 32f);
        mCenterStack.setPadding(sidePad, SystemUiGlassHelper.dpInt(mAppContext, 88f),
                sidePad, SystemUiGlassHelper.dpInt(mAppContext, 62f));

        mOpenSpring = new SamuraiSpring(0f, (val, vel) -> {
            mProgress = SamuraiGlassPrefs.clampF(val, 0f, 1.05f);
            float clamped = SamuraiGlassPrefs.clampF(mProgress, 0f, 1f);
            mCenterStack.setAlpha(clamped);
            float s = 0.92f + 0.08f * clamped;
            mCenterStack.setScaleX(s);
            mCenterStack.setScaleY(s);
            if (mProgress <= 0.008f && !mShowing) {
                detachImmediate();
            } else {
                invalidate();
            }
        }).setTuning(26f, 0.80f);

        LayoutParams stackLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(mCenterStack, stackLp);

        int sliderW = SystemUiGlassHelper.dpInt(mAppContext, 312f);
        int sliderH = SystemUiGlassHelper.dpInt(mAppContext, 66f);
        int sliderGap = SystemUiGlassHelper.dpInt(mAppContext, 22f);

        // 1. Slide to Power Off
        SlideActionTrackView powerTrack = new SlideActionTrackView(
                mAppContext,
                "slide to power off",
                SystemUiGlassHelper.GLYPH_POWER,
                0xFFFF453A,
                () -> {
                    dismissMenu(false);
                    SystemUiGlassHelper.executeShutdown(mWmFuncs);
                });
        LinearLayout.LayoutParams t1Lp = new LinearLayout.LayoutParams(sliderW, sliderH);
        t1Lp.bottomMargin = sliderGap;
        mCenterStack.addView(powerTrack, t1Lp);

        // 2. Slide to Reboot
        SlideActionTrackView rebootTrack = new SlideActionTrackView(
                mAppContext,
                "slide to reboot",
                SystemUiGlassHelper.GLYPH_REBOOT,
                0xFF0A84FF,
                () -> {
                    dismissMenu(false);
                    SystemUiGlassHelper.executeReboot(mAppContext, mWmFuncs, null);
                });
        LinearLayout.LayoutParams t2Lp = new LinearLayout.LayoutParams(sliderW, sliderH);
        t2Lp.bottomMargin = sliderGap;
        mCenterStack.addView(rebootTrack, t2Lp);

        // 3. Slide to Recovery
        SlideActionTrackView recoveryTrack = new SlideActionTrackView(
                mAppContext,
                "slide to recovery",
                SystemUiGlassHelper.GLYPH_RECOVERY,
                0xFFFF9F0A,
                () -> {
                    dismissMenu(false);
                    SystemUiGlassHelper.executeReboot(mAppContext, mWmFuncs, "recovery");
                });
        LinearLayout.LayoutParams t3Lp = new LinearLayout.LayoutParams(sliderW, sliderH);
        mCenterStack.addView(recoveryTrack, t3Lp);

        // Spacer pushing Cancel orb cleanly to bottom
        View spacer = new View(mAppContext);
        mCenterStack.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // 4. Bottom Cancel Refractive Glass Orb + Label
        CancelOrbView cancelOrb = new CancelOrbView(mAppContext, () -> dismissMenu(true));
        int cancelSize = SystemUiGlassHelper.dpInt(mAppContext, 62f);
        mCenterStack.addView(cancelOrb, new LinearLayout.LayoutParams(cancelSize, cancelSize));

        TextView cancelLabel = new TextView(mAppContext);
        cancelLabel.setText("Cancel");
        cancelLabel.setTextColor(0xE8FFFFFF);
        cancelLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f);
        cancelLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancelLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.topMargin = SystemUiGlassHelper.dpInt(mAppContext, 10f);
        mCenterStack.addView(cancelLabel, lblLp);

        setOnClickListener(v -> dismissMenu(true));
    }

    private static void applyPrivateFlags(WindowManager.LayoutParams lp) {
        try {
            Field f = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            f.setAccessible(true);
            int cur = f.getInt(lp);
            f.setInt(lp, cur | 0x00000010 | 0x20000000);
        } catch (Throwable ignored) {
        }
    }

    public boolean isMenuShowing() {
        return mShowing && mAttached;
    }

    public boolean showMenu(Object wmFuncs) {
        mWmFuncs = wmFuncs;
        SamuraiAgslEngine.invalidateWallpaperCache();
        for (int i = 0; i < mCenterStack.getChildCount(); i++) {
            View child = mCenterStack.getChildAt(i);
            if (child instanceof SlideActionTrackView) {
                ((SlideActionTrackView) child).resetThumb();
            }
        }
        if (!mAttached) {
            if (mWindowManager == null) return false;
            try {
                mWindowManager.addView(this, mLp);
                mAttached = true;
            } catch (Throwable t1) {
                try {
                    mLp.type = 2020;
                    mWindowManager.addView(this, mLp);
                    mAttached = true;
                } catch (Throwable t2) {
                    return false;
                }
            }
        }
        mShowing = true;
        requestFocus();
        SamuraiGlassPrefs.hapticHeavySpring(mAppContext, this);
        notifyGlobalActions(wmFuncs, "onGlobalActionsShown");
        mOpenSpring.animateTo(1f);
        return true;
    }

    public void dismissMenu(boolean animated) {
        if (!mShowing && !mAttached) return;
        mShowing = false;
        notifyGlobalActions(mWmFuncs, "onGlobalActionsHidden");
        if (!animated) {
            mOpenSpring.snapTo(0f);
            detachImmediate();
        } else {
            SamuraiGlassPrefs.hapticTick(this);
            mOpenSpring.animateTo(0f);
        }
    }

    private void detachImmediate() {
        if (!mAttached || mWindowManager == null) return;
        try {
            mWindowManager.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
        mAttached = false;
    }

    private static void notifyGlobalActions(Object wmFuncs, String methodName) {
        if (wmFuncs == null) return;
        try {
            Method m = wmFuncs.getClass().getMethod(methodName);
            m.setAccessible(true);
            m.invoke(wmFuncs);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP) {
            dismissMenu(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int alpha = Math.round(SamuraiGlassPrefs.clampF(mProgress, 0f, 1f) * 184f);
        mScrimPaint.setColor((alpha << 24) | 0x00060A14);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), mScrimPaint);
        super.onDraw(canvas);
    }

    // -------------------------------------------------------------------------
    // Horizontal Refractive Liquid Glass Slide Track
    // -------------------------------------------------------------------------

    private static final class SlideActionTrackView extends View {
        private final String mLabel;
        private final int mGlyph;
        private final int mKnobAccent;
        private final Runnable mOnTriggered;

        private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
        private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mTrackRect = new RectF();
        private final int[] mTmpLoc = new int[2];

        private final SamuraiSpring mDragSpring;
        private float mDrag01 = 0f;
        private boolean mDraggingKnob = false;
        private boolean mTriggered = false;
        private float mDownX = 0f;

        SlideActionTrackView(
                Context ctx,
                String label,
                int glyph,
                int knobAccent,
                Runnable onTriggered) {
            super(ctx);
            mLabel = label;
            mGlyph = glyph;
            mKnobAccent = knobAccent;
            mOnTriggered = onTriggered;

            mRimPaint.setStyle(Paint.Style.STROKE);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

            mDragSpring = new SamuraiSpring(0f, (val, vel) -> {
                mDrag01 = SamuraiGlassPrefs.clampF(val, 0f, 1f);
                invalidate();
            }).setTuning(28f, 0.78f);
        }

        void resetThumb() {
            mDraggingKnob = false;
            mTriggered = false;
            mDragSpring.snapTo(0f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float d = getResources().getDisplayMetrics().density;
            float pad = 5f * d;
            float knobD = getHeight() - pad * 2f;
            float maxTravel = Math.max(1f, getWidth() - pad * 2f - knobD);
            float knobCx = pad + knobD * 0.5f + mDrag01 * maxTravel;

            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (Math.abs(ev.getX() - knobCx) <= knobD * 0.85f) {
                    mDraggingKnob = true;
                    mDownX = ev.getX() - (mDrag01 * maxTravel);
                    SamuraiGlassPrefs.hapticClick(getContext(), this);
                    return true;
                }
                return false;
            }
            if (!mDraggingKnob) return false;

            if (action == MotionEvent.ACTION_MOVE) {
                float frac = SamuraiGlassPrefs.clampF((ev.getX() - mDownX) / maxTravel, 0f, 1f);
                mDragSpring.snapTo(frac);
                if (!mTriggered && frac >= 0.88f) {
                    mTriggered = true;
                    SamuraiGlassPrefs.hapticHeavySpring(getContext(), this);
                    mOnTriggered.run();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mDraggingKnob = false;
                if (!mTriggered) {
                    if (mDrag01 >= 0.86f && action == MotionEvent.ACTION_UP) {
                        mTriggered = true;
                        SamuraiGlassPrefs.hapticHeavySpring(getContext(), this);
                        mDragSpring.animateTo(1f);
                        mOnTriggered.run();
                    } else {
                        mDragSpring.animateTo(0f);
                    }
                }
                return true;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 2 || getHeight() <= 2) return;
            Context ctx = getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float r = h * 0.5f;

            SamuraiGlassPrefs.Snapshot snap = SamuraiGlassPrefs.read(ctx, null);
            SamuraiAgslEngine.BackdropPair bg =
                    SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, true);

            float pad = 5f * d;
            float knobD = h - pad * 2f;
            float maxTravel = Math.max(1f, w - pad * 2f - knobD);
            float knobCx = pad + knobD * 0.5f + mDrag01 * maxTravel;
            float knobCy = h * 0.5f;

            SamuraiAgslEngine.bindSurfaceShader(
                    mShader,
                    this,
                    mTmpLoc,
                    bg,
                    snap,
                    r,
                    knobCx,
                    knobCy,
                    mDraggingKnob ? 1f : 0f,
                    mKnobAccent,
                    0.12f + 0.35f * mDrag01);
            mShaderPaint.setShader(mShader);
            canvas.drawRect(0f, 0f, w, h, mShaderPaint);

            mTrackRect.set(0.6f * d, 0.6f * d, w - 0.6f * d, h - 0.6f * d);
            mRimPaint.setStrokeWidth(1.1f * d);
            mRimPaint.setColor(0x52FFFFFF);
            canvas.drawRoundRect(mTrackRect, r, r, mRimPaint);

            // Label fades smoothly as thumb slides right
            float textFade = SamuraiGlassPrefs.clampF(1f - mDrag01 * 1.45f, 0f, 1f);
            if (textFade > 0.02f) {
                mTextPaint.setTextSize(16f * d);
                mTextPaint.setColor((Math.round(textFade * 226f) << 24) | 0x00FFFFFF);
                Paint.FontMetrics fm = mTextPaint.getFontMetrics();
                float base = knobCy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(mLabel, w * 0.56f, base, mTextPaint);
            }

            // Luminous circular knob
            mKnobPaint.setColor(0xF6FFFFFF);
            canvas.drawCircle(knobCx, knobCy, knobD * 0.5f, mKnobPaint);
            SystemUiGlassHelper.drawGlyph(
                    canvas, mGlyph, knobCx, knobCy, knobD * 0.44f, mKnobAccent);
        }
    }

    private static final class CancelOrbView extends View {
        private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
        private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] mTmpLoc = new int[2];

        CancelOrbView(Context ctx, Runnable onCancel) {
            super(ctx);
            mRimPaint.setStyle(Paint.Style.STROKE);
            setOnClickListener(v -> onCancel.run());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 2 || getHeight() <= 2) return;
            Context ctx = getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float r = w * 0.5f;
            SamuraiGlassPrefs.Snapshot snap = SamuraiGlassPrefs.read(ctx, null);
            SamuraiAgslEngine.BackdropPair bg =
                    SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, true);
            SamuraiAgslEngine.bindSurfaceShader(
                    mShader, this, mTmpLoc, bg, snap, r, r, r, 0f, 0xFFFFFFFF, 0.14f);
            mShaderPaint.setShader(mShader);
            canvas.drawRect(0f, 0f, w, h, mShaderPaint);

            mRimPaint.setStrokeWidth(1.1f * d);
            mRimPaint.setColor(0x58FFFFFF);
            canvas.drawCircle(r, r, r - 0.6f * d, mRimPaint);
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_CLOSE, r, r, 20f * d, 0xFFFFFFFF);
        }
    }
}
