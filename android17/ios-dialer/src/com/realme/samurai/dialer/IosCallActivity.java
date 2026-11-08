package com.realme.samurai.dialer;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Full-Screen 90Hz AGSL iOS 26.1 Liquid Glass In-Call UI (`IosCallActivity`):
 *  - Animated deep bioluminescent aurora + blurred contact photo backdrop
 *  - Unified `DialerGlassEngine.SpringFloat` physics across all orbs, DTMF keys, action lenses,
 *    and locked `slide to answer` Liquid Glass knob
 *  - Colored Refractive Liquid Glass Lenses (`bindToPaintTinted`) for `Decline` (`#FF3B30`),
 *    `Accept` (`#34C759`), `End` (`#FF3B30`), `Secondary Call Swap Pill`, and `Locked Decline Pill`
 */
public class IosCallActivity extends Activity
        implements CallManager.Listener, DialerGlassEngine.SettingsChangeListener {

    private static final String[] DTMF_KEYS = {
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "*", "0", "#"
    };

    private static final String[] DTMF_SUB = {
            "", "ABC", "DEF",
            "GHI", "JKL", "MNO",
            "PQRS", "TUV", "WXYZ",
            "", "+", ""
    };

    private static final int[] DTMF_TONES = {
            ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
            ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6,
            ToneGenerator.TONE_DTMF_7, ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9,
            ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0, ToneGenerator.TONE_DTMF_P
    };

    private static final CallManager.CallerProfile FALLBACK_PROFILE =
            new CallManager.CallerProfile("Unknown Caller", "", "mobile", "", null);

    private final Handler mTimerHandler = new Handler(Looper.getMainLooper());
    private DialerGlassEngine.LiveOpticalState mOpt;
    private InCallStageView mStageView;
    private ToneGenerator mToneGen;
    private Bitmap mLastCallerPhoto;
    private boolean mFinishingSoon = false;

    private boolean mCachedDeviceLocked = false;
    private boolean mCachedCrossWindowBlur = false;
    private Call mCachedPrimaryCall;
    private CallManager.CallerProfile mCachedPrimaryProfile = FALLBACK_PROFILE;
    private Call mCachedSecondaryCall;
    private CallManager.CallerProfile mCachedSecondaryProfile = FALLBACK_PROFILE;
    private int mCachedAudioRoute = CallManager.ROUTE_EARPIECE;
    private boolean mCachedMuted = false;
    private boolean mCachedHasBtOrHeadset = false;
    private boolean mCrossBlurListenerRegistered = false;

    private final java.util.function.Consumer<Boolean> mCrossBlurListener = enabled -> {
        boolean next = Boolean.TRUE.equals(enabled);
        if (mCachedCrossWindowBlur != next) {
            mCachedCrossWindowBlur = next;
            if (mStageView != null) {
                mStageView.invalidate();
            }
        }
    };

    private final Runnable mSecondTicker = new Runnable() {
        @Override
        public void run() {
            if (mStageView != null) {
                mStageView.invalidate();
            }
            mTimerHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        configureEdgeToEdge90HzWindow();

        mOpt = new DialerGlassEngine.LiveOpticalState(this, true);
        mOpt.setSoftBlurReadyCallback(() -> {
            if (mStageView != null) {
                mStageView.invalidate();
            }
        });
        refreshCachedWindowAndLockState();
        refreshCachedCallState();
        mStageView = new InCallStageView(this);
        mStageView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int bot = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            mStageView.setSystemInsets(top, bot);
            return insets;
        });
        setContentView(mStageView);
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdge90HzWindow() {
        Window w = getWindow();
        if (w == null) return;
        w.setDecorFitsSystemWindows(false);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        try {
            int blurPx = Math.round(54f * getResources().getDisplayMetrics().density);
            w.setBackgroundBlurRadius(blurPx);
        } catch (Throwable ignored) {
        }
        WindowInsetsController ic = w.getInsetsController();
        if (ic != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(0, mask);
        }
        try {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode best90 = null;
                for (Display.Mode m : display.getSupportedModes()) {
                    if (m.getRefreshRate() >= 88f) {
                        if (best90 == null || m.getPhysicalWidth() >= best90.getPhysicalWidth()) {
                            best90 = m;
                        }
                    }
                }
                if (best90 != null) {
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.preferredDisplayModeId = best90.getModeId();
                    w.setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOpt.register(this);
        if (!mCrossBlurListenerRegistered) {
            try {
                WindowManager wm = getWindowManager();
                if (wm != null) {
                    wm.addCrossWindowBlurEnabledListener(mCrossBlurListener);
                    mCrossBlurListenerRegistered = true;
                }
            } catch (Throwable ignored) {
            }
        }
        CallManager.get().addListener(this);
        mTimerHandler.post(mSecondTicker);
        onCallsUpdated();
    }

    @Override
    protected void onPause() {
        mTimerHandler.removeCallbacks(mSecondTicker);
        CallManager.get().removeListener(this);
        if (mCrossBlurListenerRegistered) {
            try {
                WindowManager wm = getWindowManager();
                if (wm != null) {
                    wm.removeCrossWindowBlurEnabledListener(mCrossBlurListener);
                }
            } catch (Throwable ignored) {
            }
            mCrossBlurListenerRegistered = false;
        }
        mOpt.unregister(this);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        boolean prevLocked = mCachedDeviceLocked;
        boolean prevBlur = mCachedCrossWindowBlur;
        refreshCachedWindowAndLockState();
        if ((prevLocked != mCachedDeviceLocked || prevBlur != mCachedCrossWindowBlur) && mStageView != null) {
            mStageView.invalidate();
        }
    }

    private void refreshCachedWindowAndLockState() {
        boolean locked = false;
        try {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            locked = km != null && km.isKeyguardLocked();
        } catch (Throwable ignored) {
        }
        boolean hwBlur = false;
        try {
            WindowManager wm = getWindowManager();
            hwBlur = wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable ignored) {
        }
        mCachedDeviceLocked = locked;
        mCachedCrossWindowBlur = hwBlur;
    }

    private void refreshCachedCallState() {
        CallManager cm = CallManager.get();
        Call primary = cm.getPrimaryCall();
        mCachedPrimaryCall = primary;
        mCachedPrimaryProfile = (primary != null) ? cm.getCallerProfile(primary) : FALLBACK_PROFILE;
        Call secondary = (primary != null) ? cm.getSecondaryCall(primary) : null;
        mCachedSecondaryCall = secondary;
        mCachedSecondaryProfile = (secondary != null) ? cm.getCallerProfile(secondary) : FALLBACK_PROFILE;
        mCachedAudioRoute = cm.getAudioRoute();
        mCachedMuted = cm.isMuted();
        mCachedHasBtOrHeadset = cm.hasBluetoothOrHeadsetEndpoint();
    }

    @Override
    protected void onDestroy() {
        mTimerHandler.removeCallbacksAndMessages(null);
        if (mToneGen != null) {
            try {
                mToneGen.release();
            } catch (Throwable ignored) {
            }
            mToneGen = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mOpt != null) {
            mOpt.updateThemeFromConfig(newConfig);
            if (mLastCallerPhoto != null && !mLastCallerPhoto.isRecycled()) {
                mOpt.setCallerPhotoBackdrop(mLastCallerPhoto);
            }
        }
        if (mStageView != null) {
            mStageView.invalidate();
        }
    }

    @Override
    public void onGlassSettingsChanged() {
        if (mStageView != null) {
            mStageView.invalidate();
        }
    }

    @Override
    public void onCallsUpdated() {
        refreshCachedWindowAndLockState();
        refreshCachedCallState();
        Call primary = mCachedPrimaryCall;
        if (primary == null) {
            if (!mFinishingSoon) {
                mFinishingSoon = true;
                mTimerHandler.postDelayed(this::finishAndRemoveTask, 650L);
            }
            if (mStageView != null) mStageView.invalidate();
            return;
        }
        int st = CallManager.callState(primary);
        if (st == Call.STATE_DISCONNECTED && CallManager.get().getCalls().size() <= 1) {
            if (!mFinishingSoon) {
                mFinishingSoon = true;
                mTimerHandler.postDelayed(this::finishAndRemoveTask, 850L);
            }
        } else {
            mFinishingSoon = false;
        }

        CallManager.CallerProfile prof = mCachedPrimaryProfile;
        if (prof.photoBitmap != mLastCallerPhoto) {
            mLastCallerPhoto = prof.photoBitmap;
            mOpt.setCallerPhotoBackdrop(mLastCallerPhoto);
        }
        if (mStageView != null) {
            mStageView.invalidate();
        }
    }

    private void playLocalDtmfFeedback(int idx) {
        if (idx < 0 || idx >= DTMF_TONES.length) return;
        if (!TelecomHelper.isKeypadSoundsEnabled(this)) return;
        try {
            if (mToneGen == null) {
                mToneGen = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
            }
            mToneGen.startTone(DTMF_TONES[idx], 110);
        } catch (Throwable ignored) {
        }
    }

    private void openDialerToAddCall() {
        try {
            Intent add = new Intent(this, DialerMainActivity.class);
            add.setAction(Intent.ACTION_DIAL);
            add.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(add);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Unified 90Hz AGSL In-Call Stage rendering Incoming, Active 2x3 Grid, and In-Call DTMF Keypad.
     */
    private final class InCallStageView extends View {
        private final float d;
        private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mStatusPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mKeyMainPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mKeySubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSlideTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

        private final DialerGlassEngine.GlassSurfaceInstance[] mGridGlass =
                new DialerGlassEngine.GlassSurfaceInstance[6];
        private final DialerGlassEngine.SpringFloat[] mGridSprings =
                new DialerGlassEngine.SpringFloat[6];
        private final DialerGlassEngine.GlassSurfaceInstance[] mDtmfGlass =
                new DialerGlassEngine.GlassSurfaceInstance[12];
        private final DialerGlassEngine.SpringFloat[] mDtmfSprings =
                new DialerGlassEngine.SpringFloat[12];

        private final DialerGlassEngine.GlassSurfaceInstance mRemindGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mMsgGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mDeclineGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mAcceptGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mLockedDeclineGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mSecondaryBannerGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mSliderTrackGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mSliderKnobGlass =
                new DialerGlassEngine.GlassSurfaceInstance();

        private final DialerGlassEngine.SpringFloat mRemindSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mMsgSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mDeclineSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mAcceptSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mSlideSpring =
                DialerGlassEngine.SpringFloat.morphSpring(0f, 0f, 1f);

        private final RectF[] mGridRects = new RectF[6];
        private final RectF[] mDtmfRects = new RectF[12];
        private final RectF mRemindRect = new RectF();
        private final RectF mMsgRect = new RectF();
        private final RectF mDeclineRect = new RectF();
        private final RectF mAcceptRect = new RectF();
        private final RectF mSlideTrackRect = new RectF();
        private final RectF mLockedDeclinePill = new RectF();
        private final RectF mHideDtmfRect = new RectF();
        private final RectF mSecondaryBannerRect = new RectF();
        private final int[] mLoc = new int[2];
        private final Matrix mShaderMatrix = new Matrix();
        private final LinearGradient mPhotoScrimGrad = new LinearGradient(
                0f, 0f, 0f, 1f,
                new int[]{0x70080A10, 0x58080A10, 0xAA05070B},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        private final LinearGradient mHwBlurScrimGrad = new LinearGradient(
                0f, 0f, 0f, 1f,
                new int[]{0xAD12151C, 0xBD0E1117, 0xD8090B10},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        private final LinearGradient mFallbackScrimGrad = new LinearGradient(
                0f, 0f, 0f, 1f,
                new int[]{0xFF222630, 0xFF161920, 0xFF0C0E13},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        private final LinearGradient mSlideShimmerGrad = new LinearGradient(
                -1f, 0f, 1f, 0f,
                new int[]{0x78FFFFFF, 0xFFFFFFFF, 0x78FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        private final Paint.FontMetrics mSlideFm = new Paint.FontMetrics();
        private final boolean[] mGridLit = new boolean[6];
        private final String[] mGridLabels = new String[6];
        private Bitmap mCachedSoftBmp;
        private BitmapShader mCachedSoftShader;
        private Bitmap mCachedPhotoBmp;
        private BitmapShader mCachedPhotoShader;
        private int mCachedStatusState = Integer.MIN_VALUE;
        private long mCachedStatusSec = -1L;
        private boolean mCachedStatusHd = false;
        private String mCachedStatusType = null;
        private String mCachedStatusSim = null;
        private String mCachedStatusStr = "";
        private String mCachedSecName = null;
        private String mCachedSecBannerStr = "";
        private int mCachedDtmfLen = 0;
        private String mCachedDtmfStr = "";

        private final StringBuilder mDtmfTyped = new StringBuilder();
        private int mTopInset = 0;
        private int mBotInset = 0;
        private int mPressedGrid = -1;
        private int mPressedDtmf = -1;
        private boolean mRemindDown = false;
        private boolean mMsgDown = false;
        private boolean mDeclineDown = false;
        private boolean mAcceptDown = false;
        private boolean mDraggingSlider = false;
        private long mLastSpringNanos = 0L;
        private final long mStartRealtimeNs = System.nanoTime();

        InCallStageView(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 6; i++) {
                mGridGlass[i] = new DialerGlassEngine.GlassSurfaceInstance();
                mGridSprings[i] = DialerGlassEngine.SpringFloat.pressSpring();
                mGridRects[i] = new RectF();
            }
            for (int i = 0; i < 12; i++) {
                mDtmfGlass[i] = new DialerGlassEngine.GlassSurfaceInstance();
                mDtmfSprings[i] = DialerGlassEngine.SpringFloat.pressSpring();
                mDtmfRects[i] = new RectF();
            }
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(0.55f * d);

            mNamePaint.setColor(Color.WHITE);
            mNamePaint.setTextAlign(Paint.Align.CENTER);
            mNamePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mNamePaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 36f, getResources().getDisplayMetrics()));

            mStatusPaint.setColor(0xBCEBEBF5);
            mStatusPaint.setTextAlign(Paint.Align.CENTER);
            mStatusPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mStatusPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 17.5f, getResources().getDisplayMetrics()));

            mLabelPaint.setColor(0xEEFFFFFF);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mLabelPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12.8f, getResources().getDisplayMetrics()));

            mKeyMainPaint.setColor(Color.WHITE);
            mKeyMainPaint.setTextAlign(Paint.Align.CENTER);
            mKeyMainPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

            mKeySubPaint.setColor(0xCCFFFFFF);
            mKeySubPaint.setTextAlign(Paint.Align.CENTER);
            mKeySubPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mKeySubPaint.setLetterSpacing(0.16f);

            mSlideTextPaint.setTextAlign(Paint.Align.CENTER);
            mSlideTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mSlideTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 19f, getResources().getDisplayMetrics()));
        }

        void setSystemInsets(int top, int bot) {
            mTopInset = top;
            mBotInset = bot;
            computeLayout(getWidth(), getHeight());
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            computeLayout(w, h);
        }

        private void computeLayout(int w, int h) {
            if (w <= 10 || h <= 10) return;
            float botSafe = Math.max(28f * d, mBotInset + 20f * d);

            // 2x3 Active Call Grid (76dp circles)
            float gridDia = 76f * d;
            float gridGapX = Math.min(32f * d, (w - 3f * gridDia - 48f * d) * 0.5f);
            float gridGapY = 38f * d;
            float gridW = 3f * gridDia + 2f * gridGapX;
            float gridStartX = (w - gridW) * 0.5f;
            float gridRow2Top = h - botSafe - gridDia - 24f * d;
            float gridRow1Top = gridRow2Top - gridDia - gridGapY;

            for (int i = 0; i < 6; i++) {
                int r = i / 3;
                int c = i % 3;
                float left = gridStartX + c * (gridDia + gridGapX);
                float top = (r == 0) ? gridRow1Top : gridRow2Top;
                mGridRects[i].set(left, top, left + gridDia, top + gridDia);
            }

            // Incoming Call Controls
            float ansDia = 76f * d;
            float ansY = h - botSafe - ansDia - 24f * d;
            float ansSpread = Math.min(w * 0.27f, 110f * d);
            mDeclineRect.set(
                    w * 0.5f - ansSpread - ansDia * 0.5f, ansY,
                    w * 0.5f - ansSpread + ansDia * 0.5f, ansY + ansDia);
            mAcceptRect.set(
                    w * 0.5f + ansSpread - ansDia * 0.5f, ansY,
                    w * 0.5f + ansSpread + ansDia * 0.5f, ansY + ansDia);

            // Slide-to-answer track (when device is locked)
            float trackH = 74f * d;
            float trackMargin = 34f * d;
            mSlideTrackRect.set(
                    trackMargin, h - botSafe - trackH - 18f * d,
                    w - trackMargin, h - botSafe - 18f * d);

            float smallDia = 50f * d;
            float smallY = mSlideTrackRect.top - smallDia - 42f * d;
            mRemindRect.set(
                    mDeclineRect.centerX() - smallDia * 0.5f, smallY,
                    mDeclineRect.centerX() + smallDia * 0.5f, smallY + smallDia);
            mMsgRect.set(
                    mAcceptRect.centerX() - smallDia * 0.5f, smallY,
                    mAcceptRect.centerX() + smallDia * 0.5f, smallY + smallDia);

            // Subtle locked-screen Decline Liquid Glass pill at top-right
            mLockedDeclinePill.set(
                    w - 104f * d, mTopInset + 12f * d,
                    w - 18f * d, mTopInset + 46f * d);

            mSecondaryBannerRect.set(
                    24f * d, mTopInset + 10f * d,
                    w - 24f * d, mTopInset + 48f * d);

            // In-Call 3x4 DTMF Keypad
            float dtmfDia = Math.min(68f * d, (h - mTopInset - botSafe - 220f * d) / 5.2f);
            dtmfDia = Math.max(54f * d, dtmfDia);
            float dGapX = 20f * d;
            float dGapY = 12f * d;
            float dTotalW = 3f * dtmfDia + 2f * dGapX;
            float dStartX = (w - dTotalW) * 0.5f;
            float dEndTop = h - botSafe - gridDia - 18f * d;
            float dStartY = dEndTop - 4f * (dtmfDia + dGapY) - 6f * d;
            for (int i = 0; i < 12; i++) {
                int r = i / 3;
                int c = i % 3;
                float l = dStartX + c * (dtmfDia + dGapX);
                float t = dStartY + r * (dtmfDia + dGapY);
                mDtmfRects[i].set(l, t, l + dtmfDia, t + dtmfDia);
            }
            mHideDtmfRect.set(
                    dStartX + 2f * (dtmfDia + dGapX), dEndTop,
                    dStartX + 2f * (dtmfDia + dGapX) + dtmfDia, dEndTop + gridDia);
        }

        private boolean isDeviceLocked() {
            return mCachedDeviceLocked;
        }

        private void kickSprings() {
            if (mLastSpringNanos == 0L) {
                mLastSpringNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSprings() {
            if (mLastSpringNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastSpringNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastSpringNanos = now;
            boolean moving = false;

            for (int i = 0; i < 6; i++) {
                if (mGridSprings[i].step(dt)) moving = true;
            }
            for (int i = 0; i < 12; i++) {
                if (mDtmfSprings[i].step(dt)) moving = true;
            }
            if (mRemindSpring.step(dt)) moving = true;
            if (mMsgSpring.step(dt)) moving = true;
            if (mDeclineSpring.step(dt)) moving = true;
            if (mAcceptSpring.step(dt)) moving = true;
            if (!mDraggingSlider && mSlideSpring.step(dt)) moving = true;

            if (!moving && mPressedGrid < 0 && mPressedDtmf < 0 && !mDraggingSlider
                    && !mRemindDown && !mMsgDown && !mDeclineDown && !mAcceptDown) {
                mLastSpringNanos = 0L;
            }
            return moving;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            Call primary = CallManager.get().getPrimaryCall();
            if (primary == null) return false;

            int st = CallManager.callState(primary);
            boolean ringing = (st == Call.STATE_RINGING || st == Call.STATE_SIMULATED_RINGING);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                refreshCachedWindowAndLockState();
            }
            boolean locked = isDeviceLocked();
            boolean dtmfOpen = !ringing && CallManager.get().isInCallKeypadVisible();
            float x = event.getX();
            float y = event.getY();

            if (ringing) {
                if (action == MotionEvent.ACTION_DOWN) {
                    if (hitCircle(mRemindRect, x, y)) {
                        mRemindDown = true;
                        mRemindSpring.target = 1f;
                        DialerGlassEngine.performKeyboardTap(this);
                        kickSprings();
                        return true;
                    }
                    if (hitCircle(mMsgRect, x, y)) {
                        mMsgDown = true;
                        mMsgSpring.target = 1f;
                        DialerGlassEngine.performKeyboardTap(this);
                        kickSprings();
                        return true;
                    }
                    if (locked) {
                        if (mLockedDeclinePill.contains(x, y)) {
                            DialerGlassEngine.performTick(this, true);
                            CallManager.get().rejectOrHangup();
                            return true;
                        }
                        if (mSlideTrackRect.contains(x, y)) {
                            mDraggingSlider = true;
                            DialerGlassEngine.performTick(this, false);
                            updateSliderPos(x);
                            kickSprings();
                            return true;
                        }
                    } else {
                        if (hitCircle(mDeclineRect, x, y)) {
                            mDeclineDown = true;
                            mDeclineSpring.target = 1f;
                            DialerGlassEngine.performKeyboardTap(this);
                            kickSprings();
                            return true;
                        }
                        if (hitCircle(mAcceptRect, x, y)) {
                            mAcceptDown = true;
                            mAcceptSpring.target = 1f;
                            DialerGlassEngine.performKeyboardTap(this);
                            kickSprings();
                            return true;
                        }
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE && mDraggingSlider) {
                    updateSliderPos(x);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    boolean up = (action == MotionEvent.ACTION_UP);
                    if (mRemindDown) {
                        mRemindDown = false;
                        mRemindSpring.target = 0f;
                        kickSprings();
                        if (up && hitCircle(mRemindRect, x, y)) {
                            CallManager.CallerProfile prof = CallManager.get().getCallerProfile(primary);
                            IosSheetsAndDialogs.showRemindMeSheet(
                                    IosCallActivity.this, mOpt, prof.displayName, CallManager.rawNumberOf(primary));
                        }
                        return true;
                    }
                    if (mMsgDown) {
                        mMsgDown = false;
                        mMsgSpring.target = 0f;
                        kickSprings();
                        if (up && hitCircle(mMsgRect, x, y)) {
                            IosSheetsAndDialogs.showQuickReplyMessageSheet(
                                    IosCallActivity.this, mOpt, CallManager.rawNumberOf(primary));
                        }
                        return true;
                    }
                    if (mDeclineDown) {
                        mDeclineDown = false;
                        mDeclineSpring.target = 0f;
                        kickSprings();
                        if (up && hitCircle(mDeclineRect, x, y)) {
                            DialerGlassEngine.performTick(this, true);
                            CallManager.get().rejectOrHangup();
                        }
                        return true;
                    }
                    if (mAcceptDown) {
                        mAcceptDown = false;
                        mAcceptSpring.target = 0f;
                        kickSprings();
                        if (up && hitCircle(mAcceptRect, x, y)) {
                            DialerGlassEngine.performTick(this, true);
                            CallManager.get().answerRinging();
                        }
                        return true;
                    }
                    if (mDraggingSlider) {
                        mDraggingSlider = false;
                        if (up && mSlideSpring.value >= 0.78f) {
                            mSlideSpring.snapTo(1f);
                            DialerGlassEngine.performTick(this, true);
                            CallManager.get().answerRinging();
                        } else {
                            mSlideSpring.target = 0f;
                            kickSprings();
                        }
                        return true;
                    }
                }
                return true;
            }

            // Active / Outgoing Call Touch Handling
            if (dtmfOpen) {
                if (action == MotionEvent.ACTION_DOWN) {
                    for (int i = 0; i < 12; i++) {
                        if (hitCircle(mDtmfRects[i], x, y)) {
                            mPressedDtmf = i;
                            mDtmfSprings[i].target = 1f;
                            DialerGlassEngine.performKeyboardTap(this);
                            char digit = DTMF_KEYS[i].charAt(0);
                            mDtmfTyped.append(digit);
                            playLocalDtmfFeedback(i);
                            CallManager.get().playDtmf(digit);
                            kickSprings();
                            return true;
                        }
                    }
                    if (hitCircle(mGridRects[4], x, y)) {
                        mPressedGrid = 4;
                        mGridSprings[4].target = 1f;
                        DialerGlassEngine.performKeyboardTap(this);
                        kickSprings();
                        return true;
                    }
                    if (mHideDtmfRect.contains(x, y)) {
                        DialerGlassEngine.performKeyboardTap(this);
                        CallManager.get().setInCallKeypadVisible(false);
                        invalidate();
                        return true;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (mPressedDtmf >= 0 && mPressedDtmf < 12) {
                        mDtmfSprings[mPressedDtmf].target = 0f;
                        mPressedDtmf = -1;
                    }
                    if (mPressedGrid == 4) {
                        mGridSprings[4].target = 0f;
                        mPressedGrid = -1;
                        if (action == MotionEvent.ACTION_UP && hitCircle(mGridRects[4], x, y)) {
                            DialerGlassEngine.performTick(this, true);
                            CallManager.get().rejectOrHangup();
                        }
                    }
                    kickSprings();
                    return true;
                }
                return true;
            }

            // 2x3 Grid Touch Handling
            if (action == MotionEvent.ACTION_DOWN) {
                Call sec = CallManager.get().getSecondaryCall(primary);
                if (sec != null && mSecondaryBannerRect.contains(x, y)) {
                    DialerGlassEngine.performKeyboardTap(this);
                    CallManager.get().swapOrMergeCalls();
                    return true;
                }
                for (int i = 0; i < 6; i++) {
                    if (hitCircle(mGridRects[i], x, y)) {
                        mPressedGrid = i;
                        mGridSprings[i].target = 1f;
                        DialerGlassEngine.performKeyboardTap(this);
                        kickSprings();
                        return true;
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (mPressedGrid >= 0 && mPressedGrid < 6) {
                    int idx = mPressedGrid;
                    mGridSprings[idx].target = 0f;
                    mPressedGrid = -1;
                    if (hitCircle(mGridRects[idx], x, y)) {
                        handleGridButtonClick(idx, primary);
                    }
                }
                kickSprings();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                if (mPressedGrid >= 0 && mPressedGrid < 6) {
                    mGridSprings[mPressedGrid].target = 0f;
                    mPressedGrid = -1;
                }
                kickSprings();
                return true;
            }
            return true;
        }

        private void updateSliderPos(float touchX) {
            float knobR = (mSlideTrackRect.height() - 8f * d) * 0.5f;
            float minCx = mSlideTrackRect.left + 4f * d + knobR;
            float maxCx = mSlideTrackRect.right - 4f * d - knobR;
            float frac = DialerGlassEngine.clampF((touchX - minCx) / Math.max(1f, maxCx - minCx), 0f, 1f);
            mSlideSpring.snapTo(frac);
            invalidate();
        }

        private void handleGridButtonClick(int index, Call primary) {
            switch (index) {
                case 0: // Audio / Speaker
                    if (CallManager.get().hasBluetoothOrHeadsetEndpoint()) {
                        IosSheetsAndDialogs.showAudioRouteSheet(IosCallActivity.this, mOpt);
                    } else {
                        CallManager.get().toggleSpeakerSimple();
                    }
                    break;
                case 1: // Hold
                    CallManager.get().toggleHold();
                    break;
                case 2: // Mute
                    CallManager.get().toggleMute();
                    break;
                case 3: // Add Call or Merge/Swap
                    if (CallManager.get().getSecondaryCall(primary) != null) {
                        CallManager.get().swapOrMergeCalls();
                    } else {
                        openDialerToAddCall();
                    }
                    break;
                case 4: // End
                    DialerGlassEngine.performTick(this, true);
                    CallManager.get().rejectOrHangup();
                    break;
                case 5: // Keypad
                    CallManager.get().setInCallKeypadVisible(true);
                    break;
                default:
                    break;
            }
        }

        private boolean hitCircle(RectF b, float x, float y) {
            float r = b.width() * 0.56f;
            float dx = x - b.centerX();
            float dy = y - b.centerY();
            return dx * dx + dy * dy <= r * r;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;

            boolean springAnim = stepSprings();
            float elapsedSec = (System.nanoTime() - mStartRealtimeNs) / 1_000_000_000f;
            mOpt.phase = elapsedSec;
            getLocationOnScreen(mLoc);

            Call primary = mCachedPrimaryCall;
            CallManager.CallerProfile prof = (primary != null && mCachedPrimaryProfile != null)
                    ? mCachedPrimaryProfile
                    : FALLBACK_PROFILE;
            boolean hasPhoto = primary != null && prof.photoBitmap != null && !prof.photoBitmap.isRecycled();

            // 1. Live SurfaceFlinger Blurred Wallpaper Scrim OR Blurred Contact Photo Backdrop
            boolean hwBlur = mCachedCrossWindowBlur;

            if (hasPhoto && mOpt.activeBackdrop != null && mOpt.activeBackdrop.softBitmap != null) {
                DialerGlassEngine.BackdropBuffers bd = mOpt.activeBackdrop;
                mBgPaint.reset();
                mBgPaint.setAntiAlias(true);
                mBgPaint.setFilterBitmap(true);
                if (mCachedSoftBmp != bd.softBitmap || mCachedSoftShader == null) {
                    mCachedSoftBmp = bd.softBitmap;
                    mCachedSoftShader = new BitmapShader(
                            bd.softBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                float sx = (float) w / bd.softW;
                float sy = (float) h / bd.softH;
                float sc = Math.max(sx, sy) * 1.08f;
                mShaderMatrix.setScale(sc, sc);
                mShaderMatrix.postTranslate((w - bd.softW * sc) * 0.5f, (h - bd.softH * sc) * 0.5f);
                mCachedSoftShader.setLocalMatrix(mShaderMatrix);
                mBgPaint.setShader(mCachedSoftShader);
                canvas.drawRect(0f, 0f, w, h, mBgPaint);
                mBgPaint.setShader(null);

                mShaderMatrix.setScale(1f, Math.max(1f, h));
                mPhotoScrimGrad.setLocalMatrix(mShaderMatrix);
                mBgPaint.setShader(mPhotoScrimGrad);
                canvas.drawRect(0f, 0f, w, h, mBgPaint);
                mBgPaint.setShader(null);
            } else if (hwBlur) {
                // Translucent dark iOS frosted scrim over SurfaceFlinger 54dp blurred live wallpaper
                mBgPaint.reset();
                mBgPaint.setAntiAlias(true);
                mShaderMatrix.setScale(1f, Math.max(1f, h));
                mHwBlurScrimGrad.setLocalMatrix(mShaderMatrix);
                mBgPaint.setShader(mHwBlurScrimGrad);
                canvas.drawRect(0f, 0f, w, h, mBgPaint);
                mBgPaint.setShader(null);
            } else {
                // Clean opaque dark slate gradient fallback when cross-window blur is disabled
                mBgPaint.reset();
                mBgPaint.setAntiAlias(true);
                mShaderMatrix.setScale(1f, Math.max(1f, h));
                mFallbackScrimGrad.setLocalMatrix(mShaderMatrix);
                mBgPaint.setShader(mFallbackScrimGrad);
                canvas.drawRect(0f, 0f, w, h, mBgPaint);
                mBgPaint.setShader(null);
            }

            if (primary == null) {
                mStatusPaint.setColor(0xBCEBEBF5);
                canvas.drawText("call ended", w * 0.5f, mTopInset + 78f * d, mStatusPaint);
                return;
            }

            int st = CallManager.callState(primary);
            boolean ringing = (st == Call.STATE_RINGING || st == Call.STATE_SIMULATED_RINGING);
            boolean dtmfOpen = !ringing && CallManager.get().isInCallKeypadVisible();
            Call secondary = mCachedSecondaryCall;

            // Frosted Refractive Secondary Call Swap Pill at top
            if (secondary != null && !ringing) {
                CallManager.CallerProfile secProf = (mCachedSecondaryProfile != null)
                        ? mCachedSecondaryProfile
                        : FALLBACK_PROFILE;
                float sw = mSecondaryBannerRect.width();
                float sh = mSecondaryBannerRect.height();
                float sr = sh * 0.5f;
                if (mSecondaryBannerGlass.bindToPaint(
                        mGlassPaint, mOpt, sw, sh,
                        mLoc[0] + mSecondaryBannerRect.left, mLoc[1] + mSecondaryBannerRect.top,
                        sr, d, sw * 0.5f, sh * 0.5f, 0f, 0.14f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mSecondaryBannerRect.left, mSecondaryBannerRect.top, sw, sh, mGlassPaint);
                } else {
                    mGlassPaint.setShader(null);
                    mGlassPaint.setColor(0x2EFFFFFF);
                    canvas.drawRoundRect(mSecondaryBannerRect, sr, sr, mGlassPaint);
                }
                mRimPaint.setShader(null);
                mRimPaint.setColor(0x28FFFFFF);
                canvas.drawRoundRect(mSecondaryBannerRect, sr, sr, mRimPaint);
                mLabelPaint.setColor(Color.WHITE);
                if (!java.util.Objects.equals(mCachedSecName, secProf.displayName)) {
                    mCachedSecName = secProf.displayName;
                    mCachedSecBannerStr = secProf.displayName + " · On Hold (Tap to Swap)";
                }
                canvas.drawText(
                        mCachedSecBannerStr,
                        mSecondaryBannerRect.centerX(),
                        mSecondaryBannerRect.centerY() + 4.5f * d,
                        mLabelPaint);
            }

            // 2. Authentic iOS 26 Call Header: Status/Timer on top + Large White Caller Name/Number
            //    (Optional 68dp photo ONLY when contact has a real photo — never a cartoon initials circle!)
            String statusStr = formatCallStatusLine(primary, st, prof);
            if (!dtmfOpen) {
                float headerY = mTopInset + (secondary != null ? 64f * d : 56f * d);
                if (hasPhoto) {
                    float avR = 34f * d;
                    float avCx = w * 0.5f;
                    float avCy = headerY + avR;
                    mIconPaint.reset();
                    mIconPaint.setAntiAlias(true);
                    if (mCachedPhotoBmp != prof.photoBitmap || mCachedPhotoShader == null) {
                        mCachedPhotoBmp = prof.photoBitmap;
                        mCachedPhotoShader = new BitmapShader(
                                prof.photoBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    }
                    float sc = (avR * 2f) / Math.max(
                            1f, Math.min(prof.photoBitmap.getWidth(), prof.photoBitmap.getHeight()));
                    mShaderMatrix.setScale(sc, sc);
                    mShaderMatrix.postTranslate(
                            avCx - prof.photoBitmap.getWidth() * sc * 0.5f,
                            avCy - prof.photoBitmap.getHeight() * sc * 0.5f);
                    mCachedPhotoShader.setLocalMatrix(mShaderMatrix);
                    mIconPaint.setShader(mCachedPhotoShader);
                    canvas.drawCircle(avCx, avCy, avR, mIconPaint);
                    mIconPaint.setShader(null);

                    mRimPaint.setShader(null);
                    mRimPaint.setColor(0x38FFFFFF);
                    canvas.drawCircle(avCx, avCy, avR + 0.5f * d, mRimPaint);
                    headerY = avCy + avR + 22f * d;
                } else {
                    headerY += 18f * d;
                }

                mStatusPaint.setColor(0xBCEBEBF5);
                canvas.drawText(statusStr, w * 0.5f, headerY, mStatusPaint);

                String displayTitle = prof.displayName;
                float nameSp = displayTitle.length() > 18 ? 28f : 36f;
                mNamePaint.setTextSize(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, nameSp, getResources().getDisplayMetrics()));
                canvas.drawText(displayTitle, w * 0.5f, headerY + 42f * d, mNamePaint);
            } else {
                float headerTop = mTopInset + 28f * d;
                int dtmfLen = mDtmfTyped.length();
                if (dtmfLen != mCachedDtmfLen) {
                    mCachedDtmfLen = dtmfLen;
                    mCachedDtmfStr = dtmfLen > 0 ? mDtmfTyped.toString() : "";
                }
                String typed = dtmfLen > 0 ? mCachedDtmfStr : prof.displayName;
                mNamePaint.setTextSize(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 32f, getResources().getDisplayMetrics()));
                canvas.drawText(typed, w * 0.5f, headerTop + 32f * d, mNamePaint);
                mStatusPaint.setColor(0xBCEBEBF5);
                canvas.drawText(statusStr, w * 0.5f, headerTop + 56f * d, mStatusPaint);
            }

            // 3. Mode-Specific Controls
            if (ringing) {
                drawIncomingControls(canvas, elapsedSec);
            } else if (dtmfOpen) {
                drawInCallDtmfKeypad(canvas);
            } else {
                drawActiveGridControls(canvas, primary, st);
            }

            if (ringing || springAnim) {
                postInvalidateOnAnimation();
            }
        }

        private void drawIncomingControls(Canvas canvas, float elapsedSec) {
            float remPress = mRemindSpring.value;
            drawGlassCircle(canvas, mRemindGlass, mRemindRect, remPress, false);
            IosVectorIcons.drawAlarmClock(
                    canvas, mRemindRect.centerX(), mRemindRect.centerY(),
                    22f * d, Color.WHITE, mIconPaint);
            mLabelPaint.setColor(0xEEFFFFFF);
            canvas.drawText("Remind Me", mRemindRect.centerX(), mRemindRect.bottom + 18f * d, mLabelPaint);

            float msgPress = mMsgSpring.value;
            drawGlassCircle(canvas, mMsgGlass, mMsgRect, msgPress, false);
            IosVectorIcons.drawMessageBubble(
                    canvas, mMsgRect.centerX(), mMsgRect.centerY(),
                    22f * d, Color.WHITE, mIconPaint);
            canvas.drawText("Message", mMsgRect.centerX(), mMsgRect.bottom + 18f * d, mLabelPaint);

            if (isDeviceLocked()) {
                float pw = mLockedDeclinePill.width();
                float ph = mLockedDeclinePill.height();
                float pr = ph * 0.5f;
                if (mLockedDeclineGlass.bindToPaint(
                        mGlassPaint, mOpt, pw, ph,
                        mLoc[0] + mLockedDeclinePill.left, mLoc[1] + mLockedDeclinePill.top,
                        pr, d, pw * 0.5f, ph * 0.5f, 0f, 0.14f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mLockedDeclinePill.left, mLockedDeclinePill.top, pw, ph, mGlassPaint);
                } else {
                    mGlassPaint.setShader(null);
                    mGlassPaint.setColor(0x30FFFFFF);
                    canvas.drawRoundRect(mLockedDeclinePill, pr, pr, mGlassPaint);
                }
                mRimPaint.setShader(null);
                mRimPaint.setColor(0x28FFFFFF);
                canvas.drawRoundRect(mLockedDeclinePill, pr, pr, mRimPaint);
                mLabelPaint.setColor(0xFFFF453A);
                canvas.drawText(
                        "Decline",
                        mLockedDeclinePill.centerX(),
                        mLockedDeclinePill.centerY() + 4.2f * d,
                        mLabelPaint);

                // Authentic iOS refractive `slide to answer` track + lit Liquid Glass knob
                float slideFrac = DialerGlassEngine.clampF(mSlideSpring.value, 0f, 1f);
                float tr = mSlideTrackRect.height() * 0.5f;
                float tw = mSlideTrackRect.width();
                float th = mSlideTrackRect.height();
                float knobR = (th - 8f * d) * 0.5f;
                float minCx = mSlideTrackRect.left + 4f * d + knobR;
                float maxCx = mSlideTrackRect.right - 4f * d - knobR;
                float knobCx = minCx + slideFrac * (maxCx - minCx);
                float knobCy = mSlideTrackRect.centerY();

                if (mSliderTrackGlass.bindToPaint(
                        mGlassPaint, mOpt, tw, th,
                        mLoc[0] + mSlideTrackRect.left, mLoc[1] + mSlideTrackRect.top,
                        tr, d, knobCx - mSlideTrackRect.left, th * 0.5f,
                        mDraggingSlider ? 0.45f : 0.15f, 0.12f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mSlideTrackRect.left, mSlideTrackRect.top, tw, th, mGlassPaint);
                } else {
                    mGlassPaint.setShader(null);
                    mGlassPaint.setColor(0x28FFFFFF);
                    canvas.drawRoundRect(mSlideTrackRect, tr, tr, mGlassPaint);
                }

                mRimPaint.setShader(null);
                mRimPaint.setColor(0x26FFFFFF);
                canvas.drawRoundRect(mSlideTrackRect, tr, tr, mRimPaint);

                float shimmerX = mSlideTrackRect.left + ((elapsedSec * 0.55f) % 1.4f - 0.2f) * tw;
                mSlideTextPaint.setAlpha(Math.round(255f * (1f - slideFrac)));
                mShaderMatrix.setScale(Math.max(1f, 65f * d), 1f);
                mShaderMatrix.postTranslate(shimmerX, 0f);
                mSlideShimmerGrad.setLocalMatrix(mShaderMatrix);
                mSlideTextPaint.setShader(mSlideShimmerGrad);
                mSlideTextPaint.getFontMetrics(mSlideFm);
                canvas.drawText(
                        "slide to answer",
                        mSlideTrackRect.centerX() + 20f * d,
                        mSlideTrackRect.centerY() - (mSlideFm.ascent + mSlideFm.descent) * 0.5f,
                        mSlideTextPaint);
                mSlideTextPaint.setShader(null);

                mShadowPaint.setColor(0x38000000);
                canvas.drawCircle(knobCx, knobCy + 1.8f * d, knobR, mShadowPaint);

                float knobLeft = knobCx - knobR;
                float knobTop = knobCy - knobR;
                float knobDia = knobR * 2f;
                if (mSliderKnobGlass.bindToPaintInCallLit(
                        mGlassPaint, mOpt, knobDia, knobDia,
                        mLoc[0] + knobLeft, mLoc[1] + knobTop,
                        knobR, d, knobR, knobR, mDraggingSlider ? 0.65f : 0.18f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, knobLeft, knobTop, knobDia, knobDia, mGlassPaint);
                } else {
                    mGlassPaint.setShader(null);
                    mGlassPaint.setColor(Color.WHITE);
                    canvas.drawCircle(knobCx, knobCy, knobR, mGlassPaint);
                }

                IosVectorIcons.drawPhoneHandset(
                        canvas, knobCx, knobCy, knobR * 0.90f,
                        DialerGlassEngine.IOS_GREEN, false, mIconPaint);
            } else {
                drawTintedActionLens(
                        canvas, mDeclineGlass, mDeclineRect, mDeclineSpring.value,
                        DialerGlassEngine.IOS_RED, true);
                mLabelPaint.setColor(0xEEFFFFFF);
                canvas.drawText("Decline", mDeclineRect.centerX(), mDeclineRect.bottom + 20f * d, mLabelPaint);

                drawTintedActionLens(
                        canvas, mAcceptGlass, mAcceptRect, mAcceptSpring.value,
                        DialerGlassEngine.IOS_GREEN, false);
                canvas.drawText("Accept", mAcceptRect.centerX(), mAcceptRect.bottom + 20f * d, mLabelPaint);
            }
        }

        private void drawActiveGridControls(Canvas canvas, Call primary, int st) {
            int route = mCachedAudioRoute;
            boolean audioLit = (route == CallManager.ROUTE_SPEAKER || route == CallManager.ROUTE_BLUETOOTH);
            boolean holdLit = (st == Call.STATE_HOLDING);
            boolean muteLit = mCachedMuted;
            boolean hasTwoCalls = (mCachedSecondaryCall != null);

            mGridLit[0] = audioLit;
            mGridLit[1] = holdLit;
            mGridLit[2] = muteLit;
            mGridLit[3] = false;
            mGridLit[4] = false;
            mGridLit[5] = false;
            String audioLbl = (route == CallManager.ROUTE_BLUETOOTH)
                    ? "Bluetooth"
                    : (mCachedHasBtOrHeadset ? "Audio" : "Speaker");
            mGridLabels[0] = audioLbl;
            mGridLabels[1] = holdLit ? "Unhold" : "Hold";
            mGridLabels[2] = "Mute";
            mGridLabels[3] = hasTwoCalls ? "Merge" : "Add";
            mGridLabels[4] = "End";
            mGridLabels[5] = "Keypad";

            for (int i = 0; i < 6; i++) {
                RectF b = mGridRects[i];
                float press = DialerGlassEngine.clampF(mGridSprings[i].value, 0f, 1f);
                float cx = b.centerX();
                float cy = b.centerY();
                float sz = b.width() * 0.40f;

                if (i == 4) {
                    drawTintedActionLens(
                            canvas, mGridGlass[4], b, press, DialerGlassEngine.IOS_RED, true);
                } else {
                    drawGlassCircle(canvas, mGridGlass[i], b, press, mGridLit[i]);
                    int iconCol = mGridLit[i] ? 0xFF1C1C1E : Color.WHITE;
                    switch (i) {
                        case 0:
                            if (route == CallManager.ROUTE_BLUETOOTH) {
                                IosVectorIcons.drawBluetoothGlyph(canvas, cx, cy, sz, iconCol, mIconPaint);
                            } else {
                                IosVectorIcons.drawSpeakerWave(canvas, cx, cy, sz, iconCol, mIconPaint);
                            }
                            break;
                        case 1:
                            IosVectorIcons.drawPauseHold(canvas, cx, cy, sz, iconCol, mIconPaint);
                            break;
                        case 2:
                            IosVectorIcons.drawMicSlash(canvas, cx, cy, sz, iconCol, mIconPaint);
                            break;
                        case 3:
                            if (hasTwoCalls) {
                                IosVectorIcons.drawSwapMerge(canvas, cx, cy, sz, iconCol, true, mIconPaint);
                            } else {
                                IosVectorIcons.drawPersonBadgePlus(canvas, cx, cy, sz * 1.12f, iconCol, mIconPaint);
                            }
                            break;
                        case 5:
                            DialerGlassEngine.drawZeroAllocTabIcon(
                                    canvas, IosVectorIcons.TAB_KEYPAD, cx, cy, sz * 1.05f, iconCol, mIconPaint);
                            break;
                        default:
                            break;
                    }
                }
                mLabelPaint.setColor(0xEEFFFFFF);
                canvas.drawText(mGridLabels[i], cx, b.bottom + 19f * d, mLabelPaint);
            }
        }

        private void drawInCallDtmfKeypad(Canvas canvas) {
            float dia = mDtmfRects[0].width();
            mKeyMainPaint.setTextSize(dia * 0.45f);
            mKeySubPaint.setTextSize(dia * 0.122f);

            for (int i = 0; i < 12; i++) {
                RectF b = mDtmfRects[i];
                float press = DialerGlassEngine.clampF(mDtmfSprings[i].value, 0f, 1f);
                drawGlassCircle(canvas, mDtmfGlass[i], b, press, false);
                float cx = b.centerX();
                float cy = b.centerY();
                boolean single = (i == 0 || i == 9 || i == 11);
                canvas.drawText(DTMF_KEYS[i], cx, single ? (cy + dia * 0.16f) : (cy + dia * 0.08f), mKeyMainPaint);
                if (!DTMF_SUB[i].isEmpty()) {
                    canvas.drawText(DTMF_SUB[i], cx, cy + dia * 0.285f, mKeySubPaint);
                }
            }

            drawTintedActionLens(
                    canvas, mGridGlass[4], mGridRects[4],
                    DialerGlassEngine.clampF(mGridSprings[4].value, 0f, 1f),
                    DialerGlassEngine.IOS_RED, true);

            mStatusPaint.setColor(Color.WHITE);
            canvas.drawText("Hide", mHideDtmfRect.centerX(), mHideDtmfRect.centerY() + 6f * d, mStatusPaint);
            mStatusPaint.setColor(0xBCEBEBF5);
        }

        private void drawGlassCircle(
                Canvas canvas,
                DialerGlassEngine.GlassSurfaceInstance shader,
                RectF b,
                float press,
                boolean litWhite) {
            float cx = b.centerX();
            float cy = b.centerY();
            float dia = b.width();
            float r = dia * 0.5f;
            float p = DialerGlassEngine.clampF(press, 0f, 1.15f);
            float pUnit = DialerGlassEngine.clampF(press, 0f, 1f);
            float sc = 1.0f + 0.036f * pUnit;

            canvas.save();
            canvas.scale(sc, sc, cx, cy);

            boolean bound = (shader != null) && (litWhite
                    ? shader.bindToPaintInCallLit(
                            mGlassPaint, mOpt, dia, dia,
                            mLoc[0] + b.left, mLoc[1] + b.top,
                            r, d, r, r, p)
                    : shader.bindToPaint(
                            mGlassPaint, mOpt, dia, dia,
                            mLoc[0] + b.left, mLoc[1] + b.top,
                            r, d, r, r, p, 0.12f + 0.24f * pUnit));

            if (bound) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, b.left, b.top, dia, dia, mGlassPaint);
            } else {
                mGlassPaint.setShader(null);
                if (litWhite) {
                    mGlassPaint.setColor(DialerGlassEngine.blendArgb(Color.WHITE, 0xFFDADAE0, pUnit));
                } else {
                    mGlassPaint.setColor(DialerGlassEngine.blendArgb(0x28FFFFFF, 0x52FFFFFF, pUnit));
                }
                canvas.drawCircle(cx, cy, r, mGlassPaint);
            }

            mRimPaint.setShader(null);
            mRimPaint.setColor(litWhite ? 0x52FFFFFF : 0x28FFFFFF);
            canvas.drawCircle(cx, cy, r - 0.3f * d, mRimPaint);
            canvas.restore();
        }

        private void drawTintedActionLens(
                Canvas canvas,
                DialerGlassEngine.GlassSurfaceInstance shader,
                RectF b,
                float press,
                int color,
                boolean hangupRot) {
            float cx = b.centerX();
            float cy = b.centerY();
            float dia = b.width();
            float r = dia * 0.5f;
            float p = DialerGlassEngine.clampF(press, 0f, 1.15f);
            float pUnit = DialerGlassEngine.clampF(press, 0f, 1f);
            float sc = 1.0f + 0.036f * pUnit;

            canvas.save();
            canvas.scale(sc, sc, cx, cy);

            mShadowPaint.setColor(Color.argb(66,
                    (color >> 16) & 0x7F, (color >> 8) & 0x7F, color & 0x7F));
            canvas.drawCircle(cx, cy + 2.0f * d, r * 0.96f, mShadowPaint);

            if (shader != null && shader.bindToPaintTinted(
                    mGlassPaint, mOpt, dia, dia,
                    mLoc[0] + b.left, mLoc[1] + b.top,
                    r, d, r, r, p, color, 0.74f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, b.left, b.top, dia, dia, mGlassPaint);
            } else {
                int pressedCol = (color == DialerGlassEngine.IOS_GREEN) ? 0xFF28A748 : 0xFFD93025;
                mGlassPaint.setShader(null);
                mGlassPaint.setColor(DialerGlassEngine.blendArgb(color, pressedCol, pUnit));
                canvas.drawCircle(cx, cy, r, mGlassPaint);
            }

            mRimPaint.setShader(null);
            mRimPaint.setColor(0x38FFFFFF);
            canvas.drawCircle(cx, cy, r - 0.3f * d, mRimPaint);

            IosVectorIcons.drawPhoneHandset(
                    canvas, cx, cy, dia * 0.44f, Color.WHITE, hangupRot, mIconPaint);
            canvas.restore();
        }

        private String formatCallStatusLine(Call call, int state, CallManager.CallerProfile prof) {
            Call.Details det = call.getDetails();
            long sec = -1L;
            boolean hd = false;
            if (state == Call.STATE_ACTIVE) {
                long connMs = (det != null) ? det.getConnectTimeMillis() : 0L;
                sec = (connMs > 0L) ? Math.max(0L, (System.currentTimeMillis() - connMs) / 1000L) : 0L;
                hd = det != null && det.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO);
            }
            if (state == mCachedStatusState
                    && sec == mCachedStatusSec
                    && hd == mCachedStatusHd
                    && java.util.Objects.equals(prof.typeLabel, mCachedStatusType)
                    && java.util.Objects.equals(prof.simBadge, mCachedStatusSim)) {
                return mCachedStatusStr;
            }
            mCachedStatusState = state;
            mCachedStatusSec = sec;
            mCachedStatusHd = hd;
            mCachedStatusType = prof.typeLabel;
            mCachedStatusSim = prof.simBadge;

            String simPart = !prof.simBadge.isEmpty() ? (" · " + prof.simBadge) : "";
            switch (state) {
                case Call.STATE_RINGING:
                case Call.STATE_SIMULATED_RINGING:
                    mCachedStatusStr = prof.typeLabel + simPart;
                    break;
                case Call.STATE_DIALING:
                case Call.STATE_CONNECTING:
                    mCachedStatusStr = "calling " + prof.typeLabel + "…" + simPart;
                    break;
                case Call.STATE_HOLDING:
                    mCachedStatusStr = "HOLD" + simPart;
                    break;
                case Call.STATE_DISCONNECTING:
                case Call.STATE_DISCONNECTED:
                    mCachedStatusStr = "call ended";
                    break;
                case Call.STATE_ACTIVE: {
                    long hrs = sec / 3600L;
                    long mins = (sec % 3600L) / 60L;
                    long rem = sec % 60L;
                    String timer = (hrs > 0L)
                            ? String.format(Locale.US, "%d:%02d:%02d", hrs, mins, rem)
                            : String.format(Locale.US, "%02d:%02d", mins, rem);
                    mCachedStatusStr = (hd ? (timer + " · HD") : timer) + simPart;
                    break;
                }
                default:
                    mCachedStatusStr = prof.typeLabel + simPart;
                    break;
            }
            return mCachedStatusStr;
        }
    }
}
