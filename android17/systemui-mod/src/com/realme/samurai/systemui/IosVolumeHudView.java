package com.realme.samurai.systemui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

import java.lang.reflect.Field;

/**
 * Side-Edge Refractive iOS 26 Volume Pill (`IosVolumeHudView`).
 *
 * Aligned to RMX1931's physical left-edge volume rocker:
 * - Slim `14dp` refractive capsule on volume button press.
 * - Expands via 90 Hz `SamuraiSpring` to `46dp` interactive touch-drag capsule when touched,
 *   with 6% vertical rubber-band overscroll stretch & AW8697 haptic detents.
 */
public final class IosVolumeHudView extends View {

    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final long AUTO_HIDE_SLIM_MS = 2300L;
    private static final long AUTO_HIDE_DRAG_MS = 2800L;
    private static final long SLIM_DOWN_DELAY_MS = 950L;

    private final Context mAppContext;
    private final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mLp;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();
    private final RectF mCapsuleRect = new RectF();
    private final int[] mTmpLoc = new int[2];

    private final SamuraiSpring mShowSpring;
    private final SamuraiSpring mWidthDpSpring;
    private final SamuraiSpring mOverscrollSpring;

    private AudioManager mAudioManager;
    private int mActiveStream = AudioManager.STREAM_MUSIC;
    private float mLevel01 = 0.5f;
    private float mShowProgress = 0f;
    private float mPillWidthDp = 14f;
    private float mOverscroll = 0f;
    private boolean mAttachedToWm = false;
    private boolean mWantVisible = false;
    private boolean mDragging = false;
    private float mLastTouchY = 0f;
    private int mLastBucket = -1;

    private final Runnable mSlimRunnable = this::slimDownIfIdle;

    private final Runnable mHideRunnable = () -> {
        if (!mDragging) {
            hideHud();
        }
    };

    private void slimDownIfIdle() {
        if (!mDragging && mWantVisible) {
            mWidthDpSpring.animateTo(14f);
        }
    }

    public IosVolumeHudView(Context context) {
        super(context.getApplicationContext());
        mAppContext = context.getApplicationContext();
        mWindowManager = mAppContext.getSystemService(WindowManager.class);
        mAudioManager = mAppContext.getSystemService(AudioManager.class);
        mRimPaint.setStyle(Paint.Style.STROKE);

        int winW = SystemUiGlassHelper.dpInt(mAppContext, 68f);
        int winH = SystemUiGlassHelper.dpInt(mAppContext, 224f);
        mLp = new WindowManager.LayoutParams(
                winW,
                winH,
                TYPE_VOLUME_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        mLp.x = SystemUiGlassHelper.dpInt(mAppContext, 8f);
        mLp.y = -SystemUiGlassHelper.dpInt(mAppContext, 68f);
        mLp.setTitle("SamuraiIos26VolumeHud");
        addTrustedPrivateFlags(mLp);

        mShowSpring = new SamuraiSpring(0f, (val, vel) -> {
            mShowProgress = SamuraiGlassPrefs.clampF(val, 0f, 1.08f);
            if (mShowProgress <= 0.008f && !mWantVisible) {
                detachWindowSafely();
            } else {
                invalidate();
            }
        }).setTuning(28f, 0.78f);

        mWidthDpSpring = new SamuraiSpring(14f, (val, vel) -> {
            mPillWidthDp = Math.max(10f, val);
            invalidate();
        }).setTuning(30f, 0.76f);

        mOverscrollSpring = new SamuraiSpring(0f, (val, vel) -> {
            mOverscroll = SamuraiGlassPrefs.clampF(val, -1f, 1f);
            invalidate();
        }).setTuning(32f, 0.75f);
    }

    private static void addTrustedPrivateFlags(WindowManager.LayoutParams lp) {
        try {
            Field f = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            f.setAccessible(true);
            int cur = f.getInt(lp);
            // PRIVATE_FLAG_SHOW_FOR_ALL_USERS (0x10) | PRIVATE_FLAG_TRUSTED_OVERLAY (0x20000000)
            f.setInt(lp, cur | 0x00000010 | 0x20000000);
        } catch (Throwable ignored) {
        }
    }

    public void showForStream(AudioManager am, int stream) {
        if (am != null) mAudioManager = am;
        mActiveStream = (stream >= 0) ? stream : AudioManager.STREAM_MUSIC;
        syncVolumeFromAudioManager();

        boolean firstAppearance = !mWantVisible || mShowProgress < 0.15f;
        mWantVisible = true;
        attachWindowIfNeeded();

        if (firstAppearance) {
            mWidthDpSpring.animateTo(26f);
            mHandler.removeCallbacks(mSlimRunnable);
            mHandler.postDelayed(mSlimRunnable, SLIM_DOWN_DELAY_MS);
        } else if (!mDragging && mPillWidthDp < 16f) {
            mWidthDpSpring.animateTo(16f);
            mHandler.removeCallbacks(mSlimRunnable);
            mHandler.postDelayed(mSlimRunnable, 650L);
        }

        mShowSpring.animateTo(1f);
        rescheduleHide(AUTO_HIDE_SLIM_MS);
        invalidate();
    }

    public void hideHud() {
        mWantVisible = false;
        mDragging = false;
        mHandler.removeCallbacks(mSlimRunnable);
        mHandler.removeCallbacks(mHideRunnable);
        mShowSpring.animateTo(0f);
    }

    private void rescheduleHide(long delayMs) {
        mHandler.removeCallbacks(mHideRunnable);
        mHandler.postDelayed(mHideRunnable, delayMs);
    }

    private void syncVolumeFromAudioManager() {
        if (mDragging || mAudioManager == null) return;
        try {
            int max = Math.max(1, mAudioManager.getStreamMaxVolume(mActiveStream));
            int cur = mAudioManager.getStreamVolume(mActiveStream);
            mLevel01 = SamuraiGlassPrefs.clampF(cur / (float) max, 0f, 1f);
        } catch (Throwable ignored) {
        }
    }

    private void attachWindowIfNeeded() {
        if (mAttachedToWm || mWindowManager == null) return;
        try {
            setVisibility(VISIBLE);
            mLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mWindowManager.addView(this, mLp);
            mAttachedToWm = true;
        } catch (Throwable ignored) {
            // Fallback window type if 2020 ever rejected
            try {
                mLp.type = 2017;
                mWindowManager.addView(this, mLp);
                mAttachedToWm = true;
            } catch (Throwable ignored2) {
            }
        }
    }

    private void detachWindowSafely() {
        if (!mAttachedToWm || mWindowManager == null) return;
        try {
            mWindowManager.removeViewImmediate(this);
        } catch (Throwable ignored) {
        }
        mAttachedToWm = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mWantVisible) return false;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDragging = true;
            mLastTouchY = ev.getY();
            mLastBucket = Math.round(mLevel01 * 12f);
            mHandler.removeCallbacks(mSlimRunnable);
            mHandler.removeCallbacks(mHideRunnable);
            mWidthDpSpring.animateTo(46f);
            SamuraiGlassPrefs.hapticClick(mAppContext, this);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && mDragging) {
            float dy = mLastTouchY - ev.getY();
            mLastTouchY = ev.getY();
            float usableH = Math.max(1f, getHeight() * 0.78f);
            float next = mLevel01 + (dy / usableH);
            if (next > 1f) {
                mLevel01 = 1f;
                mOverscrollSpring.snapTo(SamuraiGlassPrefs.clampF(mOverscroll + dy / (usableH * 0.35f), 0f, 1f));
            } else if (next < 0f) {
                mLevel01 = 0f;
                mOverscrollSpring.snapTo(SamuraiGlassPrefs.clampF(mOverscroll + dy / (usableH * 0.35f), -1f, 0f));
            } else {
                mLevel01 = next;
                mOverscrollSpring.animateTo(0f);
            }
            if (mAudioManager != null) {
                try {
                    int max = Math.max(1, mAudioManager.getStreamMaxVolume(mActiveStream));
                    int target = Math.round(mLevel01 * max);
                    mAudioManager.setStreamVolume(mActiveStream, target, 0);
                } catch (Throwable ignored) {
                }
            }
            int bucket = Math.round(mLevel01 * 12f);
            if (bucket != mLastBucket) {
                mLastBucket = bucket;
                SamuraiGlassPrefs.hapticTick(this);
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mDragging = false;
            mOverscrollSpring.animateTo(0f);
            mHandler.postDelayed(mSlimRunnable, 600L);
            rescheduleHide(AUTO_HIDE_DRAG_MS);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mShowProgress <= 0.008f || getWidth() <= 2 || getHeight() <= 2) return;
        float d = mAppContext.getResources().getDisplayMetrics().density;
        SamuraiGlassPrefs.Snapshot snap = SamuraiGlassPrefs.read(mAppContext, null);

        float slideX = -(1f - SamuraiGlassPrefs.clampF(mShowProgress, 0f, 1f)) * (48f * d);
        float baseH = 186f * d;
        float stretchH = baseH * (1f + 0.06f * Math.abs(mOverscroll));
        float pillW = mPillWidthDp * d;
        float left = 4f * d + slideX;
        float cy = getHeight() * 0.5f - (mOverscroll * 5f * d);
        float top = cy - stretchH * 0.5f;
        mCapsuleRect.set(left, top, left + pillW, top + stretchH);
        float radius = pillW * 0.5f;

        // Refractive AGSL glass body
        canvas.save();
        canvas.translate(mCapsuleRect.left, mCapsuleRect.top);
        SamuraiAgslEngine.BackdropPair bg =
                SamuraiAgslEngine.getSystemOrProceduralBackdrop(mAppContext, snap.darkTheme);
        getLocationOnScreen(mTmpLoc);
        float screenW = Math.max(1f, mAppContext.getResources().getDisplayMetrics().widthPixels);
        float screenH = Math.max(1f, mAppContext.getResources().getDisplayMetrics().heightPixels);
        mShader.setInputShader("uSharpBg", bg.sharpShader);
        mShader.setInputShader("uSoftBg", bg.softShader);
        mShader.setFloatUniform("uSize", pillW, stretchH);
        mShader.setFloatUniform("uScreenOrigin", mTmpLoc[0] + mCapsuleRect.left, mTmpLoc[1] + mCapsuleRect.top);
        mShader.setFloatUniform("uScreenSize", screenW, screenH);
        mShader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        mShader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        mShader.setFloatUniform("uRadius", radius);
        mShader.setFloatUniform("uDensity", d);
        mShader.setFloatUniform("uGlassMode", (float) snap.glassMode);
        mShader.setFloatUniform("uRefractScale", Math.max(0.25f, snap.refractScale()));
        mShader.setFloatUniform("uFrostScale", Math.max(0.30f, snap.frostScale()));
        mShader.setFloatUniform("uDarkTheme", snap.darkTheme ? 1f : 0f);
        mShader.setFloatUniform("uTouchPos", pillW * 0.5f, stretchH * (1f - mLevel01));
        mShader.setFloatUniform("uTouchAmp", mDragging ? 1f : 0f);
        mShader.setFloatUniform("uTintColorR", 0.08f);
        mShader.setFloatUniform("uTintColorG", 0.11f);
        mShader.setFloatUniform("uTintColorB", 0.18f);
        mShader.setFloatUniform("uTintStrength", 0.28f);

        int alpha = Math.round(SamuraiGlassPrefs.clampF(mShowProgress, 0f, 1f) * 255f);
        mShaderPaint.setShader(mShader);
        mShaderPaint.setAlpha(alpha);
        canvas.drawRect(0f, 0f, pillW, stretchH, mShaderPaint);
        canvas.restore();

        // Luminous white volume level column clipped to capsule
        mClipPath.reset();
        mClipPath.addRoundRect(mCapsuleRect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClipPath);
        float fillTop = mCapsuleRect.bottom - mCapsuleRect.height() * mLevel01;
        mFillPaint.setColor((Math.round((alpha / 255f) * 234f) << 24) | 0x00FFFFFF);
        canvas.drawRect(mCapsuleRect.left, fillTop, mCapsuleRect.right, mCapsuleRect.bottom, mFillPaint);
        canvas.restore();

        // Specular Fresnel outline
        mRimPaint.setStrokeWidth(1.0f * d);
        mRimPaint.setColor((Math.round((alpha / 255f) * 128f) << 24) | 0x00FFFFFF);
        canvas.drawRoundRect(mCapsuleRect, radius, radius, mRimPaint);

        // Speaker glyph when expanded >= 22dp
        float iconFade = SamuraiGlassPrefs.clampF((mPillWidthDp - 20f) / 14f, 0f, 1f);
        if (iconFade > 0.04f) {
            float iconCy = mCapsuleRect.bottom - 22f * d;
            boolean overWhite = fillTop < (iconCy + 6f * d);
            int iconAlpha = Math.round((alpha / 255f) * iconFade * 240f);
            int col = overWhite
                    ? ((iconAlpha << 24) | 0x001A202C)
                    : ((iconAlpha << 24) | 0x00FFFFFF);
            int glyph = (mLevel01 <= 0.02f)
                    ? SystemUiGlassHelper.GLYPH_SPEAKER_MUTE
                    : (mLevel01 < 0.52f
                            ? SystemUiGlassHelper.GLYPH_SPEAKER_LOW
                            : SystemUiGlassHelper.GLYPH_SPEAKER_HIGH);
            SystemUiGlassHelper.drawGlyph(
                    canvas, glyph, mCapsuleRect.centerX(), iconCy, 17f * d, col);
        }
    }
}
