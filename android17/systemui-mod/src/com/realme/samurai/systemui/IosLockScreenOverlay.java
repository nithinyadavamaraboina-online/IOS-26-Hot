package com.realme.samurai.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clean-room iOS 26 Spatial / Liquid Glass Lock Screen Overlay (`IosLockScreenOverlay`).
 *
 * CRITICAL TOUCH CONTRACT:
 * - Only claims touches landing inside the Bottom-Left Flashlight `52dp` orb or Bottom-Right
 *   Camera (`GoogleCameraEng`) `52dp` orb.
 * - All other Lock Screen touches (Goodix G3 optical UDFPS sensor `(540, 2070)`, vertical
 *   swipe-up to bouncer/unlock, notification taps, shade pull-down) are 100% `TOUCH_PASS_THROUGH`!
 */
public final class IosLockScreenOverlay extends View {

    private static final int ORB_NONE = 0;
    private static final int ORB_LEFT_TORCH = 1;
    private static final int ORB_RIGHT_CAMERA = 2;

    private final RuntimeShader mLeftOrbShader = SamuraiAgslEngine.createSurfaceShader();
    private final RuntimeShader mRightOrbShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mOrbShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClockFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClockSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClockChromaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mLeftOrbRect = new RectF();
    private final RectF mRightOrbRect = new RectF();
    private final int[] mTmpLoc = new int[2];

    private final SamuraiSpring mLeftOrbSpring;
    private final SamuraiSpring mRightOrbSpring;
    private final SamuraiSpring mSwipeFadeSpring;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private boolean mKeyguardShowing = false;
    private boolean mKeyguardOccluded = false;
    private boolean mKeyguardGoingAway = false;
    private boolean mCanDismissLockScreen = false;
    private float mDozeAmount = 0f;
    private float mExternalFadeProgress = 0f;
    private float mLeftOrbScale = 1f;
    private float mRightOrbScale = 1f;
    private int mActiveOrb = ORB_NONE;
    private boolean mOrbFired = false;
    private long mOrbDownTimeMs = 0L;

    // Passive swipe-up/down observation for smooth 90 Hz fade
    private float mPassiveDownX;
    private float mPassiveDownY;
    private boolean mObservingPassiveTouch = false;

    private float mBreathePhase = 0f;
    private boolean mBreathing = false;
    private boolean mTimeReceiverRegistered = false;

    private final BroadcastReceiver mTimeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            invalidate();
        }
    };

    private final Choreographer.FrameCallback mBreatheCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mBreathing || !isAttachedToWindow() || getVisibility() != VISIBLE) {
                mBreathing = false;
                return;
            }
            mBreathePhase = (frameTimeNanos / 1_000_000_000f) * 2.1f;
            invalidate();
            // Throttle UDFPS catchlight refresh to ~30 Hz when idle to save OLED/CPU power
            postDelayed(() -> {
                if (mBreathing && isAttachedToWindow() && getVisibility() == VISIBLE) {
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    mBreathing = false;
                }
            }, 32L);
        }
    };

    public IosLockScreenOverlay(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = prefs;

        mDatePaint.setTextAlign(Paint.Align.CENTER);
        mDatePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mDatePaint.setShadowLayer(8f, 0f, 2f, 0x55000000);

        mClockFillPaint.setTextAlign(Paint.Align.CENTER);
        mClockSpecPaint.setTextAlign(Paint.Align.CENTER);
        mClockSpecPaint.setStyle(Paint.Style.STROKE);
        mClockChromaPaint.setTextAlign(Paint.Align.CENTER);
        mClockChromaPaint.setStyle(Paint.Style.STROKE);

        mRingPaint.setStyle(Paint.Style.STROKE);

        mLeftOrbSpring = new SamuraiSpring(1f, (val, vel) -> {
            mLeftOrbScale = val;
            checkOrbThreshold(ORB_LEFT_TORCH, val);
            invalidate();
        }).setTuning(30f, 0.72f);

        mRightOrbSpring = new SamuraiSpring(1f, (val, vel) -> {
            mRightOrbScale = val;
            checkOrbThreshold(ORB_RIGHT_CAMERA, val);
            invalidate();
        }).setTuning(30f, 0.72f);

        mSwipeFadeSpring = new SamuraiSpring(0f, (val, vel) -> {
            mExternalFadeProgress = SamuraiGlassPrefs.clampF(val, 0f, 1f);
            invalidate();
        }).setTuning(28f, 0.82f);

        SystemUiGlassHelper.addTorchListener(this::postInvalidate);
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        syncVisibility();
    }

    public void setKeyguardState(
            boolean showing,
            boolean occluded,
            boolean goingAway,
            boolean canDismiss,
            float dozeAmount) {
        mKeyguardShowing = showing;
        mKeyguardOccluded = occluded;
        mKeyguardGoingAway = goingAway;
        mCanDismissLockScreen = canDismiss;
        mDozeAmount = SamuraiGlassPrefs.clampF(dozeAmount, 0f, 1f);
        if (!showing || occluded) {
            mSwipeFadeSpring.snapTo(0f);
        }
        syncVisibility();
    }

    private boolean shouldBeVisible() {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        return snap.lockScreenEnabled
                && mKeyguardShowing
                && !mKeyguardOccluded
                && !mKeyguardGoingAway
                && mDozeAmount < 0.92f;
    }

    private void syncVisibility() {
        boolean vis = shouldBeVisible();
        setVisibility(vis ? VISIBLE : GONE);
        if (vis && isAttachedToWindow() && !mBreathing) {
            mBreathing = true;
            Choreographer.getInstance().postFrameCallback(mBreatheCallback);
        } else if (!vis) {
            mBreathing = false;
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mTimeReceiverRegistered) {
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_TIME_TICK);
                f.addAction(Intent.ACTION_TIME_CHANGED);
                f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
                f.addAction(Intent.ACTION_LOCALE_CHANGED);
                getContext().registerReceiver(mTimeReceiver, f);
                mTimeReceiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
        syncVisibility();
    }

    @Override
    protected void onDetachedFromWindow() {
        mBreathing = false;
        mLeftOrbSpring.cancel();
        mRightOrbSpring.cancel();
        mSwipeFadeSpring.cancel();
        if (mTimeReceiverRegistered) {
            try {
                getContext().unregisterReceiver(mTimeReceiver);
            } catch (Throwable ignored) {
            }
            mTimeReceiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private void computeOrbRects() {
        float d = getContext().getResources().getDisplayMetrics().density;
        float orbSize = 52f * d;
        float sideMargin = 38f * d;
        float bottomMargin = 44f * d;
        float top = getHeight() - bottomMargin - orbSize;
        mLeftOrbRect.set(sideMargin, top, sideMargin + orbSize, top + orbSize);
        mRightOrbRect.set(
                getWidth() - sideMargin - orbSize,
                top,
                getWidth() - sideMargin,
                top + orbSize);
    }

    private int hitTestOrb(float x, float y) {
        if (!shouldBeVisible() || mExternalFadeProgress > 0.35f) return ORB_NONE;
        computeOrbRects();
        float pad = SystemUiGlassHelper.dp(getContext(), 8f);
        if (x >= mLeftOrbRect.left - pad && x <= mLeftOrbRect.right + pad
                && y >= mLeftOrbRect.top - pad && y <= mLeftOrbRect.bottom + pad) {
            return ORB_LEFT_TORCH;
        }
        if (x >= mRightOrbRect.left - pad && x <= mRightOrbRect.right + pad
                && y >= mRightOrbRect.top - pad && y <= mRightOrbRect.bottom + pad) {
            return ORB_RIGHT_CAMERA;
        }
        return ORB_NONE;
    }

    /**
     * Returns true ONLY if touch is inside the bottom-left Torch or bottom-right Camera 52dp orbs.
     * Everything else (UDFPS, bouncer swipe, notifications) returns false (`TOUCH_PASS_THROUGH`).
     */
    public boolean handleOrbTouch(MotionEvent ev) {
        if (!shouldBeVisible()) return false;
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();

        if (action == MotionEvent.ACTION_DOWN) {
            int orb = hitTestOrb(x, y);
            if (orb == ORB_NONE) {
                mActiveOrb = ORB_NONE;
                return false;
            }
            mActiveOrb = orb;
            mOrbFired = false;
            mOrbDownTimeMs = android.os.SystemClock.uptimeMillis();
            SamuraiGlassPrefs.hapticTick(this);
            if (orb == ORB_LEFT_TORCH) {
                mLeftOrbSpring.animateTo(1.26f);
            } else {
                mRightOrbSpring.animateTo(1.26f);
            }
            return true;
        }

        if (mActiveOrb == ORB_NONE) {
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            int curOrb = hitTestOrb(x, y);
            if (curOrb != mActiveOrb) {
                resetActiveOrb();
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            int releasedOrb = mActiveOrb;
            long heldMs = android.os.SystemClock.uptimeMillis() - mOrbDownTimeMs;
            boolean alreadyFired = mOrbFired;
            resetActiveOrb();
            if (!alreadyFired && heldMs >= 65L && hitTestOrb(x, y) == releasedOrb) {
                triggerOrbAction(releasedOrb);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            resetActiveOrb();
            return true;
        }
        return false;
    }

    private void checkOrbThreshold(int orb, float scale) {
        if (mActiveOrb == orb && !mOrbFired && scale >= 1.22f) {
            mOrbFired = true;
            triggerOrbAction(orb);
        }
    }

    private void triggerOrbAction(int orb) {
        Context ctx = getContext();
        SamuraiGlassPrefs.hapticHeavySpring(ctx, this);
        if (orb == ORB_LEFT_TORCH) {
            SystemUiGlassHelper.toggleTorch(ctx);
        } else if (orb == ORB_RIGHT_CAMERA) {
            SystemUiGlassHelper.launchCamera(ctx, true);
        }
    }

    private void resetActiveOrb() {
        mActiveOrb = ORB_NONE;
        mOrbFired = false;
        mLeftOrbSpring.animateTo(1f);
        mRightOrbSpring.animateTo(1f);
    }

    /**
     * Passively observes Lock Screen touches without consuming them so the Spatial Clock & Orbs
     * smoothly fade out as the user swipes up to bouncer/unlock or pulls down notifications.
     */
    public void observePassThroughTouch(MotionEvent ev) {
        if (!shouldBeVisible()) return;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mPassiveDownX = ev.getX();
            mPassiveDownY = ev.getY();
            mObservingPassiveTouch = true;
        } else if (action == MotionEvent.ACTION_MOVE && mObservingPassiveTouch) {
            float dy = ev.getY() - mPassiveDownY;
            float dx = ev.getX() - mPassiveDownX;
            if (Math.abs(dy) > Math.abs(dx) * 1.15f) {
                float range = SystemUiGlassHelper.dp(getContext(), 220f);
                float fade = SamuraiGlassPrefs.clampF((Math.abs(dy) - 18f) / Math.max(1f, range), 0f, 1f);
                mSwipeFadeSpring.animateTo(fade);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mObservingPassiveTouch = false;
            mSwipeFadeSpring.animateTo(0f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shouldBeVisible() || getWidth() <= 2 || getHeight() <= 2) return;
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(ctx, null);

        float masterAlpha = (1f - mDozeAmount) * (1f - mExternalFadeProgress);
        masterAlpha = SamuraiGlassPrefs.clampF(masterAlpha, 0f, 1f);
        if (masterAlpha <= 0.01f) return;

        int alpha255 = Math.round(masterAlpha * 255f);
        float w = getWidth();
        float cx = w * 0.5f;
        float headerLift = -26f * d * mExternalFadeProgress;

        // 1. Top Padlock Glyph + "EEEE, d MMMM" Date Line
        canvas.save();
        canvas.translate(0f, headerLift);

        float lockCy = 62f * d;
        SystemUiGlassHelper.drawGlyph(
                canvas,
                mCanDismissLockScreen
                        ? SystemUiGlassHelper.GLYPH_PADLOCK_OPEN
                        : SystemUiGlassHelper.GLYPH_PADLOCK_CLOSED,
                cx,
                lockCy,
                16f * d,
                (Math.round(masterAlpha * 225f) << 24) | 0x00F4F8FF);

        Date now = new Date();
        String dateStr = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        mDatePaint.setTextSize(19.5f * d);
        mDatePaint.setColor((Math.round(masterAlpha * 232f) << 24) | 0x00F2F7FF);
        float dateY = 96f * d;
        canvas.drawText(dateStr, cx, dateY, mDatePaint);

        // 2. Tall Variable-Height Refractive Glass Clock (0=Spatial Tall, 1=Rounded Bold, 2=Stencil)
        boolean is24 = DateFormat.is24HourFormat(ctx);
        String timePattern = is24 ? "HH:mm" : "h:mm";
        String timeStr = new SimpleDateFormat(timePattern, Locale.getDefault()).format(now);

        int style = snap.lockClockStyle;
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float fontSizeDp = 88f;
        Typeface clockTf;
        if (style == SamuraiGlassPrefs.CLOCK_ROUNDED_BOLD) {
            clockTf = Typeface.create("sans-serif-black", Typeface.BOLD);
            scaleX = 1.04f;
            scaleY = 1.04f;
            fontSizeDp = 86f;
        } else if (style == SamuraiGlassPrefs.CLOCK_SLIM_STENCIL) {
            clockTf = Typeface.create("sans-serif-light", Typeface.NORMAL);
            scaleX = 0.96f;
            scaleY = 1.18f;
            fontSizeDp = 90f;
        } else {
            // CLOCK_TALL_SPATIAL (default iOS 26 tall variable-height crystal numerals)
            clockTf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            scaleX = 0.94f;
            scaleY = 1.30f;
            fontSizeDp = 92f;
        }

        float clockBaseY = 198f * d;
        float fontPx = fontSizeDp * d;
        for (Paint p : new Paint[] { mClockFillPaint, mClockSpecPaint, mClockChromaPaint }) {
            p.setTypeface(clockTf);
            p.setTextSize(fontPx);
            p.setLetterSpacing(-0.025f);
        }

        canvas.save();
        canvas.translate(cx, clockBaseY);
        canvas.scale(scaleX, scaleY);

        // Chromatic cyan / violet edge dispersion rim
        mClockChromaPaint.setStrokeWidth(1.6f * d);
        mClockChromaPaint.setColor((Math.round(masterAlpha * 72f) << 24) | 0x005AC8FA);
        canvas.drawText(timeStr, 1.1f * d, 0.8f * d, mClockChromaPaint);
        mClockChromaPaint.setColor((Math.round(masterAlpha * 64f) << 24) | 0x00BF5AF2);
        canvas.drawText(timeStr, -1.1f * d, -0.6f * d, mClockChromaPaint);

        // Translucent refractive glass gradient fill
        int topCol = (Math.round(masterAlpha * 230f) << 24) | 0x00FFFFFF;
        int midCol = (Math.round(masterAlpha * 172f) << 24) | 0x00DCEBFF;
        int botCol = (Math.round(masterAlpha * 208f) << 24) | 0x00F4F8FF;
        mClockFillPaint.setShader(new LinearGradient(
                0f, -fontPx * 0.85f, 0f, fontPx * 0.10f,
                new int[] { topCol, midCol, botCol },
                new float[] { 0f, 0.56f, 1f },
                Shader.TileMode.CLAMP));
        if (style == SamuraiGlassPrefs.CLOCK_SLIM_STENCIL) {
            mClockFillPaint.setAlpha(Math.round(masterAlpha * 145f));
        }
        canvas.drawText(timeStr, 0f, 0f, mClockFillPaint);

        // Crisp top-left white specular inner bevel
        mClockSpecPaint.setStrokeWidth((style == SamuraiGlassPrefs.CLOCK_SLIM_STENCIL ? 2.1f : 1.35f) * d);
        mClockSpecPaint.setColor((Math.round(masterAlpha * 210f) << 24) | 0x00FFFFFF);
        canvas.drawText(timeStr, -0.7f * d, -0.9f * d, mClockSpecPaint);

        canvas.restore();
        canvas.restore();

        // 3. Non-Clickable Goodix G3 Optical UDFPS Refractive Crystal Ring (TOUCH_PASS_THROUGH)
        Point udfps = SamuraiSystemUiHooks.getUdfpsSensorCenter(ctx);
        getLocationOnScreen(mTmpLoc);
        float udfpsX = udfps.x - mTmpLoc[0];
        float udfpsY = udfps.y - mTmpLoc[1];
        float breath = 0.5f + 0.5f * (float) Math.sin(mBreathePhase);
        float outerR = (38f + 1.8f * breath) * d;
        float innerR = 30.5f * d;

        int ringAlpha1 = Math.round(masterAlpha * (85f + 65f * breath));
        int ringAlpha2 = Math.round(masterAlpha * 28f);
        SweepGradient sweep = new SweepGradient(
                udfpsX,
                udfpsY,
                new int[] {
                        (ringAlpha2 << 24) | 0x00FFFFFF,
                        (ringAlpha1 << 24) | 0x00E8F6FF,
                        (ringAlpha2 << 24) | 0x005AC8FA,
                        (ringAlpha1 << 24) | 0x00FFFFFF,
                        (ringAlpha2 << 24) | 0x00FFFFFF
                },
                new float[] { 0f, 0.22f, 0.50f, 0.78f, 1f });
        mRingPaint.setShader(sweep);
        mRingPaint.setStrokeWidth(1.8f * d);
        canvas.drawCircle(udfpsX, udfpsY, outerR, mRingPaint);
        mRingPaint.setShader(null);
        mRingPaint.setStrokeWidth(1.0f * d);
        mRingPaint.setColor((Math.round(masterAlpha * 56f) << 24) | 0x00DCEEFF);
        canvas.drawCircle(udfpsX, udfpsY, innerR, mRingPaint);

        // 4. Bottom-Left Flashlight & Bottom-Right Camera 52dp Refractive Glass Orbs
        computeOrbRects();
        SamuraiAgslEngine.BackdropPair bg =
                SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, snap.darkTheme);
        boolean torchOn = SystemUiGlassHelper.isTorchEnabled(ctx);

        drawLockOrb(
                canvas,
                mLeftOrbShader,
                mLeftOrbRect,
                mLeftOrbScale,
                SystemUiGlassHelper.GLYPH_FLASHLIGHT,
                torchOn,
                bg,
                snap,
                alpha255);
        drawLockOrb(
                canvas,
                mRightOrbShader,
                mRightOrbRect,
                mRightOrbScale,
                SystemUiGlassHelper.GLYPH_CAMERA,
                false,
                bg,
                snap,
                alpha255);
    }

    private void drawLockOrb(
            Canvas canvas,
            RuntimeShader shader,
            RectF baseRect,
            float scale,
            int glyph,
            boolean activeGlow,
            SamuraiAgslEngine.BackdropPair bg,
            SamuraiGlassPrefs.Snapshot snap,
            int alpha255) {
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        float cx = baseRect.centerX();
        float cy = baseRect.centerY();
        float w = baseRect.width() * scale;
        float h = baseRect.height() * scale;
        float r = w * 0.5f;

        if (activeGlow) {
            mGlowPaint.setColor((Math.round((alpha255 / 255f) * 96f) << 24) | 0x00FFE082);
            canvas.drawCircle(cx, cy, r * 1.18f, mGlowPaint);
        }

        canvas.save();
        canvas.translate(cx - r, cy - r);

        float screenW = Math.max(1f, ctx.getResources().getDisplayMetrics().widthPixels);
        float screenH = Math.max(1f, ctx.getResources().getDisplayMetrics().heightPixels);
        shader.setInputShader("uSharpBg", bg.sharpShader);
        shader.setInputShader("uSoftBg", bg.softShader);
        shader.setFloatUniform("uSize", w, h);
        shader.setFloatUniform("uScreenOrigin", mTmpLoc[0] + (cx - r), mTmpLoc[1] + (cy - r));
        shader.setFloatUniform("uScreenSize", screenW, screenH);
        shader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        shader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        shader.setFloatUniform("uRadius", r);
        shader.setFloatUniform("uDensity", d);
        shader.setFloatUniform("uGlassMode", (float) snap.glassMode);
        shader.setFloatUniform("uRefractScale", Math.max(0.22f, snap.refractScale()));
        shader.setFloatUniform("uFrostScale", snap.frostScale());
        shader.setFloatUniform("uDarkTheme", 1.0f);
        shader.setFloatUniform("uTouchPos", r, r);
        shader.setFloatUniform("uTouchAmp", Math.max(0f, (scale - 1f) * 3.5f));
        shader.setFloatUniform("uTintColorR", activeGlow ? 1.0f : 0.92f);
        shader.setFloatUniform("uTintColorG", activeGlow ? 0.94f : 0.96f);
        shader.setFloatUniform("uTintColorB", activeGlow ? 0.72f : 1.0f);
        shader.setFloatUniform("uTintStrength", activeGlow ? 0.72f : 0.14f);

        mOrbShaderPaint.setShader(shader);
        mOrbShaderPaint.setAlpha(alpha255);
        canvas.drawRect(0f, 0f, w, h, mOrbShaderPaint);
        canvas.restore();

        mRingPaint.setStrokeWidth(1.1f * d);
        mRingPaint.setColor(((Math.round((alpha255 / 255f) * 120f)) << 24) | 0x00FFFFFF);
        canvas.drawCircle(cx, cy, r - 0.6f * d, mRingPaint);

        int iconColor = activeGlow
                ? ((alpha255 << 24) | 0x001A1D24)
                : ((alpha255 << 24) | 0x00FFFFFF);
        SystemUiGlassHelper.drawGlyph(canvas, glyph, cx, cy, 22f * d * scale, iconColor);
    }
}
