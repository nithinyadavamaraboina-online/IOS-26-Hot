package com.realme.samurai.systemui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Modular 90 Hz iOS 26 Liquid Glass Control Center (`IosControlCenterView`).
 *
 * Pulled down interactively from the top-right status bar (`x >= 0.58f * width`, `y < 42dp`) with
 * `SamuraiSpring` rubber-banding, live `SamuraiAgslEngine` backdrop refraction, and swipe-up or
 * scrim-tap dismissal.
 */
public final class IosControlCenterView extends FrameLayout {

    private final DynamicIslandController mIslandController;
    private final LinearLayout mSheetContent;
    private final Paint mScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SamuraiSpring mOpenSpring;
    private final List<SamuraiGlassCard> mAllCards = new ArrayList<>();

    // Header
    private final TextView mHeaderClockView;
    private final TextView mHeaderStatusView;

    // Connectivity orbs
    private final ConnectivityOrbView mOrbAirplane;
    private final ConnectivityOrbView mOrbCellular;
    private final ConnectivityOrbView mOrbWifi;
    private final ConnectivityOrbView mOrbBluetooth;

    // Media platter view
    private final MediaPlatterContentView mMediaContent;

    // Vertical liquid-stretch capsules
    private final LiquidVerticalCapsuleView mBrightnessCapsule;
    private final LiquidVerticalCapsuleView mVolumeCapsule;

    // Modular orbs
    private final ActionOrbCard mOrbTorch;
    private final ActionOrbCard mOrbRotation;
    private final ActionOrbCard mOrbRinger;

    // RMX1931 Exclusive hardware pills
    private final HardwarePillCard mPill90Hz;
    private final HardwarePillCard mPillDcDim;
    private final HardwarePillCard mPillVooc80;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private float mProgress = 0f;
    private boolean mTargetOpen = false;
    private boolean mDraggingSwipeDismiss = false;
    private float mDismissDownX;
    private float mDismissDownY;
    private VelocityTracker mVelocityTracker;

    public IosControlCenterView(
            Context context,
            DynamicIslandController islandController,
            SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mIslandController = islandController;
        mPrefs = prefs;

        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        mOpenSpring = new SamuraiSpring(0f, (val, vel) -> {
            mProgress = Math.max(0f, val);
            applyProgressTransforms();
            SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
            invalidate();
        }).setTuning(26f, 0.78f);

        mSheetContent = new LinearLayout(context);
        mSheetContent.setOrientation(LinearLayout.VERTICAL);
        mSheetContent.setClipChildren(false);
        mSheetContent.setClipToPadding(false);
        int sidePad = SystemUiGlassHelper.dpInt(context, 18f);
        int topPad = SystemUiGlassHelper.dpInt(context, 44f);
        int botPad = SystemUiGlassHelper.dpInt(context, 22f);
        mSheetContent.setPadding(sidePad, topPad, sidePad, botPad);
        LayoutParams sheetLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(mSheetContent, sheetLp);

        // 0. Top iOS 26 Control Center Status Bar Row
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        mHeaderClockView = new TextView(context);
        mHeaderClockView.setTextColor(0xF2FFFFFF);
        mHeaderClockView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        mHeaderClockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        mHeaderStatusView = new TextView(context);
        mHeaderStatusView.setTextColor(0xD8E8F2FF);
        mHeaderStatusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        mHeaderStatusView.setGravity(Gravity.END);
        mHeaderStatusView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        headerRow.addView(mHeaderClockView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(mHeaderStatusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hdrLp.bottomMargin = SystemUiGlassHelper.dpInt(context, 14f);
        mSheetContent.addView(headerRow, hdrLp);

        int gap = SystemUiGlassHelper.dpInt(context, 12f);

        // 1. Row 1: 2x2 Connectivity Glass Platter + 2x2 Media Glass Platter (164dp tall)
        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);
        row1.setClipToPadding(false);
        int platterH = SystemUiGlassHelper.dpInt(context, 166f);

        SamuraiGlassCard connCard = createCard(context, 28f);
        int cardPad = SystemUiGlassHelper.dpInt(context, 12f);
        connCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        LinearLayout connTopRow = new LinearLayout(context);
        connTopRow.setOrientation(LinearLayout.HORIZONTAL);
        mOrbAirplane = new ConnectivityOrbView(
                context,
                SystemUiGlassHelper.GLYPH_AIRPLANE,
                "Airplane",
                0xFFFF9F0A,
                () -> {
                    SystemUiGlassHelper.toggleAirplaneMode(context);
                    refreshLiveStates();
                },
                () -> SystemUiGlassHelper.openSettingsAction(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        mOrbCellular = new ConnectivityOrbView(
                context,
                SystemUiGlassHelper.GLYPH_CELLULAR,
                "Cellular",
                0xFF30D158,
                () -> {
                    SystemUiGlassHelper.toggleMobileData(context);
                    refreshLiveStates();
                },
                () -> SystemUiGlassHelper.openSettingsAction(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
        connTopRow.addView(mOrbAirplane, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        connTopRow.addView(mOrbCellular, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout connBotRow = new LinearLayout(context);
        connBotRow.setOrientation(LinearLayout.HORIZONTAL);
        mOrbWifi = new ConnectivityOrbView(
                context,
                SystemUiGlassHelper.GLYPH_WIFI,
                "Wi-Fi",
                0xFF0A84FF,
                () -> {
                    SystemUiGlassHelper.toggleWifi(context);
                    postDelayed(this::refreshLiveStates, 250L);
                    refreshLiveStates();
                },
                () -> SystemUiGlassHelper.openSettingsAction(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
        mOrbBluetooth = new ConnectivityOrbView(
                context,
                SystemUiGlassHelper.GLYPH_BLUETOOTH,
                "Bluetooth",
                0xFF0A84FF,
                () -> {
                    SystemUiGlassHelper.toggleBluetooth(context);
                    postDelayed(this::refreshLiveStates, 300L);
                    refreshLiveStates();
                },
                () -> SystemUiGlassHelper.openSettingsAction(context, Settings.ACTION_BLUETOOTH_SETTINGS));
        connBotRow.addView(mOrbWifi, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        connBotRow.addView(mOrbBluetooth, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        connCard.addView(connTopRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        connCard.addView(connBotRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        SamuraiGlassCard mediaCard = createCard(context, 28f);
        mMediaContent = new MediaPlatterContentView(context, mIslandController);
        mediaCard.addView(mMediaContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams leftHalfLp = new LinearLayout.LayoutParams(0, platterH, 1f);
        leftHalfLp.rightMargin = gap / 2;
        LinearLayout.LayoutParams rightHalfLp = new LinearLayout.LayoutParams(0, platterH, 1f);
        rightHalfLp.leftMargin = gap / 2;
        row1.addView(connCard, leftHalfLp);
        row1.addView(mediaCard, rightHalfLp);

        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, platterH);
        row1Lp.bottomMargin = gap;
        mSheetContent.addView(row1, row1Lp);

        // 2. Row 2: Left 2x3 Modular Orbs Grid + Right Dual Vertical Liquid-Stretch Capsules
        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setClipChildren(false);
        row2.setClipToPadding(false);
        int row2H = SystemUiGlassHelper.dpInt(context, 214f);

        LinearLayout orbsGrid = new LinearLayout(context);
        orbsGrid.setOrientation(LinearLayout.VERTICAL);
        orbsGrid.setClipChildren(false);
        orbsGrid.setClipToPadding(false);

        mOrbTorch = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_FLASHLIGHT, "Torch", 0xFFFFD60A, () -> {
            SystemUiGlassHelper.toggleTorch(context);
            refreshLiveStates();
        });
        mOrbRotation = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_ROTATION, "Lock", 0xFFFF453A, () -> {
            SystemUiGlassHelper.toggleRotationLock(context);
            refreshLiveStates();
        });
        mOrbRinger = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_BELL, "Sound", 0xFFFF9F0A, () -> {
            SystemUiGlassHelper.cycleRingerMode(context);
            refreshLiveStates();
        });
        ActionOrbCard orbCalc = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_CALC, "Calc", 0xFF5AC8FA,
                () -> SystemUiGlassHelper.launchCalculator(context));
        ActionOrbCard orbTimer = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_TIMER, "Timer", 0xFFFF9F0A,
                () -> SystemUiGlassHelper.launchClock(context));
        ActionOrbCard orbCamera = new ActionOrbCard(context, prefs, SystemUiGlassHelper.GLYPH_CAMERA, "Camera", 0xFF5AC8FA,
                () -> SystemUiGlassHelper.launchCamera(context, SamuraiSystemUiHooks.isKeyguardShowing()));

        mAllCards.add(mOrbTorch);
        mAllCards.add(mOrbRotation);
        mAllCards.add(mOrbRinger);
        mAllCards.add(orbCalc);
        mAllCards.add(orbTimer);
        mAllCards.add(orbCamera);

        orbsGrid.addView(makeOrbPairRow(context, mOrbTorch, mOrbRotation, gap),
                makeGridRowLp(gap, true));
        orbsGrid.addView(makeOrbPairRow(context, mOrbRinger, orbCalc, gap),
                makeGridRowLp(gap, true));
        orbsGrid.addView(makeOrbPairRow(context, orbTimer, orbCamera, gap),
                makeGridRowLp(0, false));

        LinearLayout slidersBox = new LinearLayout(context);
        slidersBox.setOrientation(LinearLayout.HORIZONTAL);
        slidersBox.setClipChildren(false);
        slidersBox.setClipToPadding(false);

        mBrightnessCapsule = new LiquidVerticalCapsuleView(context, true, prefs);
        mVolumeCapsule = new LiquidVerticalCapsuleView(context, false, prefs);

        LinearLayout.LayoutParams slLeftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        slLeftLp.rightMargin = gap / 2;
        LinearLayout.LayoutParams slRightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        slRightLp.leftMargin = gap / 2;
        slidersBox.addView(mBrightnessCapsule, slLeftLp);
        slidersBox.addView(mVolumeCapsule, slRightLp);

        LinearLayout.LayoutParams orbsLp = new LinearLayout.LayoutParams(0, row2H, 1f);
        orbsLp.rightMargin = gap / 2;
        LinearLayout.LayoutParams slidersLp = new LinearLayout.LayoutParams(0, row2H, 1f);
        slidersLp.leftMargin = gap / 2;
        row2.addView(orbsGrid, orbsLp);
        row2.addView(slidersBox, slidersLp);

        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, row2H);
        row2Lp.bottomMargin = gap;
        mSheetContent.addView(row2, row2Lp);

        // 3. Row 3: 3 RMX1931 Exclusive Hardware Liquid Glass Pills
        LinearLayout row3 = new LinearLayout(context);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setClipChildren(false);
        row3.setClipToPadding(false);
        int pillH = SystemUiGlassHelper.dpInt(context, 68f);

        mPill90Hz = new HardwarePillCard(
                context,
                prefs,
                SystemUiGlassHelper.GLYPH_PROMOTION_90,
                "ProMotion",
                0xFF5AC8FA,
                () -> {
                    SystemUiGlassHelper.toggleProMotion90Hz(context);
                    refreshLiveStates();
                });
        mPillDcDim = new HardwarePillCard(
                context,
                prefs,
                SystemUiGlassHelper.GLYPH_DC_DIM,
                "DC Dimming",
                0xFFBF5AF2,
                () -> {
                    SystemUiGlassHelper.toggleDcDimming(context);
                    refreshLiveStates();
                });
        mPillVooc80 = new HardwarePillCard(
                context,
                prefs,
                SystemUiGlassHelper.GLYPH_VOOC_BOLT,
                "SuperVOOC",
                0xFF30D158,
                () -> {
                    SystemUiGlassHelper.toggleVooc80Limit(context);
                    refreshLiveStates();
                });

        mAllCards.add(mPill90Hz);
        mAllCards.add(mPillDcDim);
        mAllCards.add(mPillVooc80);

        int thirdGap = SystemUiGlassHelper.dpInt(context, 8f);
        LinearLayout.LayoutParams p1Lp = new LinearLayout.LayoutParams(0, pillH, 1f);
        p1Lp.rightMargin = thirdGap;
        LinearLayout.LayoutParams p2Lp = new LinearLayout.LayoutParams(0, pillH, 1f);
        p2Lp.rightMargin = thirdGap;
        LinearLayout.LayoutParams p3Lp = new LinearLayout.LayoutParams(0, pillH, 1f);
        row3.addView(mPill90Hz, p1Lp);
        row3.addView(mPillDcDim, p2Lp);
        row3.addView(mPillVooc80, p3Lp);

        LinearLayout.LayoutParams row3Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, pillH);
        row3Lp.bottomMargin = SystemUiGlassHelper.dpInt(context, 16f);
        mSheetContent.addView(row3, row3Lp);

        // 4. Bottom Refractive Grabber Pill
        View grabber = new View(context) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF mR = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                mP.setColor(0x88FFFFFF);
                mR.set(0f, 0f, getWidth(), getHeight());
                float r = getHeight() * 0.5f;
                canvas.drawRoundRect(mR, r, r, mP);
            }
        };
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(
                SystemUiGlassHelper.dpInt(context, 44f),
                SystemUiGlassHelper.dpInt(context, 5f));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        mSheetContent.addView(grabber, grabLp);

        SystemUiGlassHelper.addTorchListener(this::refreshLiveStates);
        mIslandController.addListener(() -> {
            mMediaContent.invalidate();
            refreshHeader();
        });
    }

    private SamuraiGlassCard createCard(Context ctx, float radiusDp) {
        SamuraiGlassCard card = new SamuraiGlassCard(ctx, mPrefs)
                .setCornerRadiusDp(radiusDp)
                .setInteractivePressScale(true);
        mAllCards.add(card);
        return card;
    }

    private static LinearLayout makeOrbPairRow(Context ctx, View left, View right, int gap) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lLp.rightMargin = gap / 2;
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rLp.leftMargin = gap / 2;
        row.addView(left, lLp);
        row.addView(right, rLp);
        return row;
    }

    private static LinearLayout.LayoutParams makeGridRowLp(int bottomGap, boolean hasBottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        if (hasBottom) lp.bottomMargin = bottomGap;
        return lp;
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        for (SamuraiGlassCard c : mAllCards) {
            c.updatePrefs(prefs);
        }
        mBrightnessCapsule.updatePrefs(prefs);
        mVolumeCapsule.updatePrefs(prefs);
        if (prefs != null && !prefs.controlCenterEnabled) {
            collapseImmediate();
        }
        invalidate();
    }

    public boolean isShowingOrAnimating() {
        return mProgress > 0.005f || mTargetOpen;
    }

    public float getOpenProgress() {
        return mProgress;
    }

    public void beginTopRightPull() {
        SamuraiAgslEngine.invalidateWallpaperCache();
        setVisibility(VISIBLE);
        mIslandController.rebindMediaSessions();
        refreshLiveStates();
    }

    public void setInteractivePullProgress(float rawProgress) {
        setVisibility(VISIBLE);
        float p;
        if (rawProgress <= 1f) {
            p = Math.max(0f, rawProgress);
        } else {
            float extra = rawProgress - 1f;
            p = 1f + 0.14f * (1f - (float) Math.exp(-extra * 2.2f));
        }
        mOpenSpring.snapTo(p);
    }

    public void finishInteractivePull(float velocityYPxPerSec) {
        boolean shouldOpen;
        if (Math.abs(velocityYPxPerSec) > SystemUiGlassHelper.dp(getContext(), 320f)) {
            shouldOpen = velocityYPxPerSec > 0f;
        } else {
            shouldOpen = mProgress >= 0.36f;
        }
        if (shouldOpen) {
            openWithVelocity(velocityYPxPerSec);
        } else {
            collapse(true);
        }
    }

    public void openWithVelocity(float velocityYPxPerSec) {
        setVisibility(VISIBLE);
        mTargetOpen = true;
        refreshLiveStates();
        SamuraiGlassPrefs.hapticClick(getContext(), this);
        float normVel = SamuraiGlassPrefs.clampF(velocityYPxPerSec / 1800f, -1.8f, 2.2f);
        mOpenSpring.addImpulse(normVel * 0.35f);
        mOpenSpring.animateTo(1f);
    }

    public void collapse(boolean animated) {
        mTargetOpen = false;
        if (!animated) {
            collapseImmediate();
            return;
        }
        mOpenSpring.animateTo(0f);
    }

    public void collapseImmediate() {
        mTargetOpen = false;
        mOpenSpring.snapTo(0f);
        setVisibility(GONE);
        SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
    }

    private void applyProgressTransforms() {
        if (mProgress <= 0.004f && !mTargetOpen) {
            if (getVisibility() != GONE) {
                setVisibility(GONE);
            }
            return;
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        float clamped = SamuraiGlassPrefs.clampF(mProgress, 0f, 1f);
        float overscroll = Math.max(0f, mProgress - 1f);
        float travelPx = SystemUiGlassHelper.dp(getContext(), 92f);
        mSheetContent.setAlpha(SamuraiGlassPrefs.clampF(clamped * 1.22f, 0f, 1f));
        mSheetContent.setTranslationY(-(1f - clamped) * travelPx + overscroll * SystemUiGlassHelper.dp(getContext(), 28f));
        float scale = 0.93f + 0.07f * clamped;
        mSheetContent.setScaleX(scale);
        mSheetContent.setScaleY(scale + overscroll * 0.04f);
        mSheetContent.setPivotX(getWidth() * 0.82f);
        mSheetContent.setPivotY(0f);
    }

    public void refreshLiveStates() {
        Context ctx = getContext();
        refreshHeader();

        boolean air = SystemUiGlassHelper.isAirplaneModeOn(ctx);
        boolean cell = !air && SystemUiGlassHelper.isMobileDataEnabled(ctx);
        boolean wifi = SystemUiGlassHelper.isWifiEnabled(ctx);
        boolean bt = SystemUiGlassHelper.isBluetoothEnabled(ctx);

        mOrbAirplane.setState(air, air ? "On" : "Off");
        mOrbCellular.setState(cell, cell ? "5G / LTE" : "Off");
        mOrbWifi.setState(wifi, SystemUiGlassHelper.getConnectedWifiSsid(ctx));
        mOrbBluetooth.setState(bt, bt ? "On" : "Off");

        boolean torch = SystemUiGlassHelper.isTorchEnabled(ctx);
        mOrbTorch.setState(torch, torch ? "On" : "Torch");

        boolean rotLocked = SystemUiGlassHelper.isRotationLocked(ctx);
        mOrbRotation.setState(rotLocked, rotLocked ? "Locked" : "Auto");

        int rm = SystemUiGlassHelper.getRingerMode(ctx);
        if (rm == AudioManager.RINGER_MODE_SILENT) {
            mOrbRinger.setGlyphAndState(SystemUiGlassHelper.GLYPH_BELL_SLASH, true, "Silent", 0xFFFF453A);
        } else if (rm == AudioManager.RINGER_MODE_VIBRATE) {
            mOrbRinger.setGlyphAndState(SystemUiGlassHelper.GLYPH_VIBRATE, true, "Vibrate", 0xFFFF9F0A);
        } else {
            mOrbRinger.setGlyphAndState(SystemUiGlassHelper.GLYPH_BELL, false, "Ring", 0xFFFF9F0A);
        }

        boolean p90 = SystemUiGlassHelper.isProMotion90Hz(ctx);
        mPill90Hz.setState(p90, p90 ? "90Hz Smooth" : "60Hz Saver");

        boolean dc = SystemUiGlassHelper.isDcDimmingEnabled(ctx);
        mPillDcDim.setState(dc, dc ? "Anti-Flicker" : "PWM Default");

        boolean v80 = SystemUiGlassHelper.isVooc80LimitEnabled(ctx);
        mPillVooc80.setState(v80, v80 ? "80% Guard" : "100% 50W");

        mBrightnessCapsule.syncFromSystem();
        mVolumeCapsule.syncFromSystem();
        mMediaContent.invalidate();
    }

    private void refreshHeader() {
        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        mHeaderClockView.setText(clock);
        String ssid = SystemUiGlassHelper.getConnectedWifiSsid(getContext());
        int batt = mIslandController.getBatteryPct();
        mHeaderStatusView.setText(("Off".equals(ssid) ? "RMX1931" : ssid) + "  •  " + batt + "%");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mProgress > 0.004f) {
            float clamped = SamuraiGlassPrefs.clampF(mProgress, 0f, 1f);
            int alpha = Math.round(clamped * 168f);
            mScrimPaint.setColor((alpha << 24) | 0x00070B16);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), mScrimPaint);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDraggingSwipeDismiss = false;
            mDismissDownX = ev.getX();
            mDismissDownY = ev.getY();
            recycleTracker();
            mVelocityTracker = VelocityTracker.obtain();
            mVelocityTracker.addMovement(ev);
            // Do not intercept drags inside the vertical Brightness/Volume capsules!
            if (isPointInsideView(mBrightnessCapsule, ev.getRawX(), ev.getRawY())
                    || isPointInsideView(mVolumeCapsule, ev.getRawX(), ev.getRawY())) {
                return false;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isPointInsideView(mBrightnessCapsule, ev.getRawX(), ev.getRawY())
                    || isPointInsideView(mVolumeCapsule, ev.getRawX(), ev.getRawY())) {
                return false;
            }
            if (mVelocityTracker != null) mVelocityTracker.addMovement(ev);
            float dy = ev.getY() - mDismissDownY;
            float dx = ev.getX() - mDismissDownX;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (dy < -slop * 1.4f && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                mDraggingSwipeDismiss = true;
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        if (action == MotionEvent.ACTION_DOWN) {
            mDismissDownX = ev.getX();
            mDismissDownY = ev.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dy = ev.getY() - mDismissDownY;
            if (mDraggingSwipeDismiss || dy < -SystemUiGlassHelper.dp(getContext(), 14f)) {
                mDraggingSwipeDismiss = true;
                float range = Math.max(1f, SystemUiGlassHelper.dp(getContext(), 340f));
                float nextP = SamuraiGlassPrefs.clampF(1f + (dy / range), 0.02f, 1.05f);
                mOpenSpring.snapTo(nextP);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vy = mVelocityTracker.getYVelocity();
            recycleTracker();
            if (mDraggingSwipeDismiss) {
                mDraggingSwipeDismiss = false;
                if (vy < -SystemUiGlassHelper.dp(getContext(), 280f) || mProgress < 0.72f) {
                    SamuraiGlassPrefs.hapticTick(this);
                    collapse(true);
                } else {
                    mOpenSpring.animateTo(1f);
                }
                return true;
            }
            // Tap outside mSheetContent dismisses Control Center
            if (ev.getY() > mSheetContent.getBottom()
                    && Math.hypot(ev.getX() - mDismissDownX, ev.getY() - mDismissDownY)
                            < SystemUiGlassHelper.dp(getContext(), 18f)) {
                SamuraiGlassPrefs.hapticTick(this);
                collapse(true);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            mDraggingSwipeDismiss = false;
            recycleTracker();
            if (mTargetOpen) mOpenSpring.animateTo(1f);
            else collapse(true);
            return true;
        }
        return true;
    }

    private void recycleTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private static boolean isPointInsideView(View v, float rawX, float rawY) {
        if (v == null || !v.isShown()) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= (loc[0] + v.getWidth())
                && rawY >= loc[1] && rawY <= (loc[1] + v.getHeight());
    }

    // =========================================================================
    // Inner Module Views
    // =========================================================================

    private static final class ConnectivityOrbView extends View {
        private final int mGlyph;
        private final String mTitle;
        private final int mActiveColor;
        private final Paint mCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mActive = false;
        private String mSubtitle = "";

        ConnectivityOrbView(
                Context ctx,
                int glyph,
                String title,
                int activeColor,
                Runnable onTap,
                Runnable onLongPress) {
            super(ctx);
            mGlyph = glyph;
            mTitle = title;
            mActiveColor = activeColor;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(ctx, this);
                onTap.run();
            });
            setOnLongClickListener(v -> {
                SamuraiGlassPrefs.hapticHeavySpring(ctx, this);
                onLongPress.run();
                return true;
            });
        }

        void setState(boolean active, String subtitle) {
            mActive = active;
            mSubtitle = subtitle != null ? subtitle : mTitle;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float orbR = Math.min(getWidth(), getHeight()) * 0.30f;
            float orbCy = getHeight() * 0.39f;

            mCirclePaint.setColor(mActive ? mActiveColor : 0x28FFFFFF);
            canvas.drawCircle(cx, orbCy, orbR, mCirclePaint);

            mRimPaint.setStrokeWidth(0.9f * d);
            mRimPaint.setColor(mActive ? 0x66FFFFFF : 0x30FFFFFF);
            canvas.drawCircle(cx, orbCy, orbR, mRimPaint);

            SystemUiGlassHelper.drawGlyph(
                    canvas, mGlyph, cx, orbCy, orbR * 1.05f, 0xFFFFFFFF);

            mLabelPaint.setTextSize(10.5f * d);
            mLabelPaint.setColor(0xE8FFFFFF);
            String caption = mSubtitle.isEmpty() ? mTitle : mSubtitle;
            if (caption.length() > 10) {
                caption = caption.substring(0, 9) + "…";
            }
            canvas.drawText(caption, cx, getHeight() - 5f * d, mLabelPaint);
        }
    }

    private static final class MediaPlatterContentView extends View {
        private final DynamicIslandController mController;
        private final Paint mArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArtRect = new RectF();
        private final RectF mPrevRect = new RectF();
        private final RectF mPlayRect = new RectF();
        private final RectF mNextRect = new RectF();
        private final RectF mTmp = new RectF();
        private float mPhase = 0f;
        private boolean mAnim = false;

        private final Choreographer.FrameCallback mTick = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!mAnim || !isShown()) {
                    mAnim = false;
                    return;
                }
                mPhase = (frameTimeNanos / 1_000_000_000f) * 6.2f;
                invalidate();
                if (mController.isMediaPlaying()) {
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    mAnim = false;
                }
            }
        };

        MediaPlatterContentView(Context ctx, DynamicIslandController controller) {
            super(ctx);
            mController = controller;
            mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = ev.getX();
                float y = ev.getY();
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                if (mPrevRect.contains(x, y)) {
                    mController.mediaPrevious();
                } else if (mPlayRect.contains(x, y)) {
                    mController.mediaPlayPause();
                } else if (mNextRect.contains(x, y)) {
                    mController.mediaNext();
                } else {
                    mController.openActiveIslandDestination();
                }
                invalidate();
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float pad = 14f * d;
            float w = getWidth();
            float h = getHeight();

            boolean playing = mController.isMediaPlaying();
            if (playing && !mAnim && isShown()) {
                mAnim = true;
                Choreographer.getInstance().postFrameCallback(mTick);
            }

            // Album Art thumbnail top-left
            float artSize = 44f * d;
            mArtRect.set(pad, pad, pad + artSize, pad + artSize);
            Bitmap art = mController.getMediaAlbumArt();
            if (art != null && !art.isRecycled()) {
                BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(
                        artSize / Math.max(1f, art.getWidth()),
                        artSize / Math.max(1f, art.getHeight()));
                m.postTranslate(mArtRect.left, mArtRect.top);
                bs.setLocalMatrix(m);
                mArtPaint.setShader(bs);
                canvas.drawRoundRect(mArtRect, 12f * d, 12f * d, mArtPaint);
                mArtPaint.setShader(null);
            } else {
                mBoxPaint.setColor(0x305AC8FA);
                canvas.drawRoundRect(mArtRect, 12f * d, 12f * d, mBoxPaint);
                SystemUiGlassHelper.drawGlyph(
                        canvas,
                        SystemUiGlassHelper.GLYPH_MUSIC,
                        mArtRect.centerX(),
                        mArtRect.centerY(),
                        20f * d,
                        0xFF5AC8FA);
            }

            // Animated mini waveform top-right
            float waveRight = w - pad;
            mBoxPaint.setColor(playing ? 0xFF5AC8FA : 0x55FFFFFF);
            for (int i = 0; i < 4; i++) {
                float amp = playing
                        ? (0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(mPhase + i * 1.1f)))
                        : 0.22f;
                float bh = (5f + 13f * amp) * d;
                float bx = waveRight - (4 - i) * 5.2f * d;
                float wy = pad + 16f * d;
                mTmp.set(bx, wy - bh * 0.5f, bx + 2.8f * d, wy + bh * 0.5f);
                canvas.drawRoundRect(mTmp, 1.5f * d, 1.5f * d, mBoxPaint);
            }

            // Track Title & Artist
            float maxTextW = Math.max(20f, w - pad * 2f);
            mTitlePaint.setTextSize(14.5f * d);
            mTitlePaint.setColor(0xF5FFFFFF);
            android.text.TextPaint tpTitle = new android.text.TextPaint(mTitlePaint);
            String title = TextUtils.ellipsize(
                    mController.getMediaTitle(), tpTitle, maxTextW, TextUtils.TruncateAt.END).toString();
            canvas.drawText(title, pad, mArtRect.bottom + 21f * d, mTitlePaint);

            mSubPaint.setTextSize(12f * d);
            mSubPaint.setColor(0xB5DCE8FA);
            android.text.TextPaint tpSub = new android.text.TextPaint(mSubPaint);
            String artist = TextUtils.ellipsize(
                    mController.getMediaArtist(), tpSub, maxTextW, TextUtils.TruncateAt.END).toString();
            canvas.drawText(artist, pad, mArtRect.bottom + 38f * d, mSubPaint);

            // Transport controls row at bottom
            float cy = h - 25f * d;
            float rBtn = 19f * d;
            mPrevRect.set(w * 0.23f - rBtn, cy - rBtn, w * 0.23f + rBtn, cy + rBtn);
            mPlayRect.set(w * 0.50f - rBtn * 1.1f, cy - rBtn * 1.1f, w * 0.50f + rBtn * 1.1f, cy + rBtn * 1.1f);
            mNextRect.set(w * 0.77f - rBtn, cy - rBtn, w * 0.77f + rBtn, cy + rBtn);

            mBoxPaint.setColor(0x28FFFFFF);
            canvas.drawOval(mPlayRect, mBoxPaint);

            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_PREV, mPrevRect.centerX(), cy, 16f * d, 0xF0FFFFFF);
            SystemUiGlassHelper.drawGlyph(
                    canvas,
                    playing ? SystemUiGlassHelper.GLYPH_PAUSE : SystemUiGlassHelper.GLYPH_PLAY,
                    mPlayRect.centerX(),
                    cy,
                    20f * d,
                    0xFFFFFFFF);
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_NEXT, mNextRect.centerX(), cy, 16f * d, 0xF0FFFFFF);
        }
    }

    /**
     * Vertical Refractive Liquid-Stretch Capsule (Brightness & Volume) with 6% overscroll stretch
     * and AW8697 haptic tick detents.
     */
    private static final class LiquidVerticalCapsuleView extends View {
        private final boolean mIsBrightness;
        private final RuntimeShader mShader = SamuraiAgslEngine.createSurfaceShader();
        private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mClipPath = new Path();
        private final RectF mRect = new RectF();
        private final int[] mTmpLoc = new int[2];

        private final SamuraiSpring mOverscrollSpring;
        private SamuraiGlassPrefs.Snapshot mPrefs;
        private float mLevel01 = 0.5f;
        private float mOverscroll = 0f; // -1..+1 -> up to 6% stretch
        private float mLastTouchY = 0f;
        private float mTouchX = 0f;
        private float mTouchY = 0f;
        private boolean mDragging = false;
        private int mLastHapticBucket = -1;

        LiquidVerticalCapsuleView(Context ctx, boolean isBrightness, SamuraiGlassPrefs.Snapshot prefs) {
            super(ctx);
            mIsBrightness = isBrightness;
            mPrefs = prefs;
            mRimPaint.setStyle(Paint.Style.STROKE);

            mOverscrollSpring = new SamuraiSpring(0f, (val, vel) -> {
                mOverscroll = SamuraiGlassPrefs.clampF(val, -1.2f, 1.2f);
                float stretchY = 1f + 0.06f * Math.abs(mOverscroll);
                float squishX = 1f - 0.028f * Math.abs(mOverscroll);
                setPivotX(getWidth() * 0.5f);
                setPivotY(mOverscroll >= 0f ? getHeight() : 0f);
                setScaleY(stretchY);
                setScaleX(squishX);
                invalidate();
            }).setTuning(30f, 0.74f);

            syncFromSystem();
        }

        void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
            mPrefs = prefs;
            invalidate();
        }

        void syncFromSystem() {
            if (mDragging) return;
            Context ctx = getContext();
            if (mIsBrightness) {
                int b = SystemUiGlassHelper.getScreenBrightness(ctx);
                mLevel01 = SamuraiGlassPrefs.clampF((b - 1f) / 254f, 0f, 1f);
            } else {
                try {
                    AudioManager am = ctx.getSystemService(AudioManager.class);
                    if (am != null) {
                        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        mLevel01 = SamuraiGlassPrefs.clampF(cur / (float) max, 0f, 1f);
                    }
                } catch (Throwable ignored) {
                }
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            mTouchX = ev.getX();
            mTouchY = ev.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                mDragging = true;
                mLastTouchY = ev.getY();
                mLastHapticBucket = Math.round(mLevel01 * 10f);
                SamuraiGlassPrefs.hapticTick(this);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && mDragging) {
                float dy = mLastTouchY - ev.getY();
                mLastTouchY = ev.getY();
                float h = Math.max(1f, getHeight());
                float rawNext = mLevel01 + (dy / (h * 0.88f));
                if (rawNext > 1f) {
                    mLevel01 = 1f;
                    float over = SamuraiGlassPrefs.clampF(mOverscroll + (dy / (h * 0.35f)), 0f, 1f);
                    mOverscrollSpring.snapTo(over);
                } else if (rawNext < 0f) {
                    mLevel01 = 0f;
                    float under = SamuraiGlassPrefs.clampF(mOverscroll + (dy / (h * 0.35f)), -1f, 0f);
                    mOverscrollSpring.snapTo(under);
                } else {
                    mLevel01 = rawNext;
                    if (Math.abs(mOverscroll) > 0.01f) {
                        mOverscrollSpring.animateTo(0f);
                    }
                }
                applySystemValue();
                int bucket = Math.round(mLevel01 * 10f);
                if (bucket != mLastHapticBucket) {
                    mLastHapticBucket = bucket;
                    SamuraiGlassPrefs.hapticTick(this);
                }
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mDragging = false;
                mOverscrollSpring.animateTo(0f);
                invalidate();
                return true;
            }
            return super.onTouchEvent(ev);
        }

        private void applySystemValue() {
            Context ctx = getContext();
            if (mIsBrightness) {
                int val = 1 + Math.round(mLevel01 * 254f);
                SystemUiGlassHelper.setScreenBrightness(ctx, val);
            } else {
                try {
                    AudioManager am = ctx.getSystemService(AudioManager.class);
                    if (am != null) {
                        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                        int target = Math.round(mLevel01 * max);
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 2 || getHeight() <= 2) return;
            Context ctx = getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            float radius = Math.min(w * 0.46f, 28f * d);

            SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                    ? mPrefs
                    : SamuraiGlassPrefs.read(ctx, null);
            SamuraiAgslEngine.BackdropPair bg =
                    SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, snap.darkTheme);
            SamuraiAgslEngine.bindSurfaceShader(
                    mShader,
                    this,
                    mTmpLoc,
                    bg,
                    snap,
                    radius,
                    mTouchX,
                    mTouchY,
                    mDragging ? 1f : 0f,
                    0x00000000,
                    0f);
            mShaderPaint.setShader(mShader);
            canvas.drawRect(0f, 0f, w, h, mShaderPaint);

            // Clipped luminous white liquid fill column
            mRect.set(0f, 0f, w, h);
            mClipPath.reset();
            mClipPath.addRoundRect(mRect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(mClipPath);

            float fillTop = h * (1f - mLevel01);
            mFillPaint.setColor(0xE0FFFFFF);
            canvas.drawRect(0f, fillTop, w, h, mFillPaint);
            canvas.restore();

            // Specular border
            mRimPaint.setStrokeWidth(1.0f * d);
            mRimPaint.setColor(0x52FFFFFF);
            canvas.drawRoundRect(mRect, radius, radius, mRimPaint);

            // Sun / Speaker glyph near bottom
            float iconCy = h - 28f * d;
            boolean overWhite = fillTop < (iconCy + 8f * d);
            int glyphColor = overWhite ? 0xE618202C : 0xF0FFFFFF;
            int glyph;
            if (mIsBrightness) {
                glyph = SystemUiGlassHelper.GLYPH_SUN;
            } else if (mLevel01 <= 0.02f) {
                glyph = SystemUiGlassHelper.GLYPH_SPEAKER_MUTE;
            } else if (mLevel01 < 0.52f) {
                glyph = SystemUiGlassHelper.GLYPH_SPEAKER_LOW;
            } else {
                glyph = SystemUiGlassHelper.GLYPH_SPEAKER_HIGH;
            }
            SystemUiGlassHelper.drawGlyph(canvas, glyph, w * 0.5f, iconCy, 22f * d, glyphColor);
        }
    }

    /**
     * Modular Squircle Refractive Orb Card (`SamuraiGlassCard` subclass).
     */
    private static final class ActionOrbCard extends SamuraiGlassCard {
        private int mGlyph;
        private int mActiveColor;
        private boolean mActive = false;
        private String mCaption;
        private final Paint mCaptionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ActionOrbCard(
                Context ctx,
                SamuraiGlassPrefs.Snapshot prefs,
                int glyph,
                String caption,
                int activeColor,
                Runnable onTap) {
            super(ctx, prefs);
            mGlyph = glyph;
            mCaption = caption;
            mActiveColor = activeColor;
            setCornerRadiusDp(22f);
            setInteractivePressScale(true);
            mCaptionPaint.setTextAlign(Paint.Align.CENTER);
            mCaptionPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(ctx, this);
                onTap.run();
            });
        }

        void setState(boolean active, String caption) {
            mActive = active;
            mCaption = caption;
            setTint(mActiveColor, active ? 0.58f : 0f);
            invalidate();
        }

        void setGlyphAndState(int glyph, boolean active, String caption, int activeColor) {
            mGlyph = glyph;
            mActiveColor = activeColor;
            setState(active, caption);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() * 0.5f;
            float iconCy = getHeight() * 0.40f;
            int iconCol = mActive ? 0xFFFFFFFF : 0xF0FFFFFF;
            SystemUiGlassHelper.drawGlyph(canvas, mGlyph, cx, iconCy, 21f * d, iconCol);

            mCaptionPaint.setTextSize(10.5f * d);
            mCaptionPaint.setColor(0xE8FFFFFF);
            canvas.drawText(mCaption, cx, getHeight() - 8f * d, mCaptionPaint);
        }
    }

    /**
     * RMX1931 Exclusive Hardware Liquid Glass Pill (`90Hz ProMotion`, `OLED DC Dimming`, `SuperVOOC 80%`).
     */
    private static final class HardwarePillCard extends SamuraiGlassCard {
        private final int mGlyph;
        private final String mTitle;
        private final int mAccentColor;
        private boolean mActive = false;
        private String mStateText = "";
        private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HardwarePillCard(
                Context ctx,
                SamuraiGlassPrefs.Snapshot prefs,
                int glyph,
                String title,
                int accentColor,
                Runnable onTap) {
            super(ctx, prefs);
            mGlyph = glyph;
            mTitle = title;
            mAccentColor = accentColor;
            setCornerRadiusDp(22f);
            setInteractivePressScale(true);
            mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticHeavySpring(ctx, this);
                onTap.run();
            });
        }

        void setState(boolean active, String stateText) {
            mActive = active;
            mStateText = stateText;
            setTint(mAccentColor, active ? 0.42f : 0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float pad = 11f * d;

            // Top row: icon + live status dot
            SystemUiGlassHelper.drawGlyph(
                    canvas,
                    mGlyph,
                    pad + 9f * d,
                    pad + 9f * d,
                    17f * d,
                    mActive ? 0xFFFFFFFF : mAccentColor);

            mDotPaint.setColor(mActive ? mAccentColor : 0x44FFFFFF);
            canvas.drawCircle(getWidth() - pad - 4f * d, pad + 6f * d, 3.6f * d, mDotPaint);

            mTitlePaint.setTextSize(11.5f * d);
            mTitlePaint.setColor(0xF6FFFFFF);
            canvas.drawText(mTitle, pad, getHeight() - 21f * d, mTitlePaint);

            mSubPaint.setTextSize(10f * d);
            mSubPaint.setColor(mActive ? 0xFFFFFFFF : 0xB8DCE6F8);
            canvas.drawText(mStateText, pad, getHeight() - 8f * d, mSubPaint);
        }
    }
}
