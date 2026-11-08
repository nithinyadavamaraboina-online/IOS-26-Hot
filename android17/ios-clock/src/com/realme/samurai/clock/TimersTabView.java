package com.realme.samurai.clock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 3 — iOS 26 Timers (`TimersTabView`) with Quick Preset Orbs (`1m..45m`), custom H:M:S picker,
 * animated 90Hz refractive countdown ring, `AlarmManager` completion chime, and live
 * `SamuraiDynamicIslandBus` updates.
 */
public final class TimersTabView extends FrameLayout {

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final List<SamuraiGlassCard> mGlassCards = new ArrayList<>();
    private final List<TextView> mPrimaryLabels = new ArrayList<>();
    private final List<TextView> mSecondaryLabels = new ArrayList<>();

    private final LinearLayout mMainCol;
    private final TextView mHeaderTitle;

    // Setup View (Presets + Custom H:M:S + Label)
    private final LinearLayout mSetupSection;
    private final TextView mHoursValTv;
    private final TextView mMinsValTv;
    private final TextView mSecsValTv;
    private final EditText mTimerLabelInput;

    // Active / Paused Ring View
    private final LinearLayout mActiveSection;
    private final CountdownRingView mRingView;
    private final TextView mActiveCountdownTv;
    private final TextView mTargetBellTv;
    private final TextView mActiveLabelTv;

    // Control Orbs
    private final SamuraiGlassCard mCancelOrb;
    private final TextView mCancelTv;
    private final SamuraiGlassCard mPrimaryActionOrb;
    private final TextView mPrimaryActionTv;

    private int mPickHours = 0;
    private int mPickMins = 5;
    private int mPickSecs = 0;

    private ClockDataStore.TimerState mState;
    private boolean mTabVisible = false;

    private final Choreographer.FrameCallback mRingFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mTabVisible || mState == null || !mState.running) return;
            long rem = mState.endEpochMs - System.currentTimeMillis();
            if (rem <= 0L) {
                mState.running = false;
                mState.pausedRemainingMs = 0L;
                ClockDataStore.saveTimerState(getContext(), mState);
                syncSectionsAndButtons();
                return;
            }
            updateRingUi(rem);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public TimersTabView(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = prefs;
        mState = ClockDataStore.loadTimerState(context);
        float d = context.getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mMainCol = new LinearLayout(context);
        mMainCol.setOrientation(LinearLayout.VERTICAL);
        mMainCol.setPadding(side, Math.round(52f * d), side, Math.round(126f * d));
        scroll.addView(mMainCol, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mHeaderTitle = new TextView(context);
        mHeaderTitle.setText("Timers");
        mHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 33f);
        mHeaderTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mHeaderTitle.setLetterSpacing(-0.02f);
        mHeaderTitle.setPadding(Math.round(4f * d), Math.round(4f * d), 0, Math.round(10f * d));
        mPrimaryLabels.add(mHeaderTitle);
        mMainCol.addView(mHeaderTitle);

        // --- Setup Section ---
        mSetupSection = new LinearLayout(context);
        mSetupSection.setOrientation(LinearLayout.VERTICAL);

        TextView presetHdr = new TextView(context);
        presetHdr.setText("QUICK PRESETS");
        presetHdr.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        presetHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        presetHdr.setLetterSpacing(0.06f);
        presetHdr.setPadding(Math.round(6f * d), 0, 0, Math.round(8f * d));
        mSecondaryLabels.add(presetHdr);
        mSetupSection.addView(presetHdr);

        HorizontalScrollView presetScroll = new HorizontalScrollView(context);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow = new LinearLayout(context);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] presetMinutes = { 1, 3, 5, 10, 15, 30, 45 };
        for (int i = 0; i < presetMinutes.length; i++) {
            final int mins = presetMinutes[i];
            SamuraiGlassCard orb = new SamuraiGlassCard(context, prefs)
                    .setCornerRadiusDp(28f)
                    .setInteractivePressScale(true);
            orb.setGravity(Gravity.CENTER);
            int dia = Math.round(56f * d);
            TextView pTv = new TextView(context);
            pTv.setText(mins + "m");
            pTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            pTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            pTv.setGravity(Gravity.CENTER);
            mPrimaryLabels.add(pTv);
            orb.addView(pTv, new LinearLayout.LayoutParams(dia, dia));
            orb.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(context, v);
                mPickHours = 0;
                mPickMins = mins;
                mPickSecs = 0;
                refreshPickerTexts();
                startTimerWithDuration(mins * 60_000L, currentLabelText());
            });
            mGlassCards.add(orb);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dia, dia);
            if (i > 0) olp.leftMargin = Math.round(9f * d);
            presetRow.addView(orb, olp);
        }
        presetScroll.addView(presetRow);
        mSetupSection.addView(presetScroll);

        // Custom H : M : S Picker Card
        SamuraiGlassCard pickerCard = new SamuraiGlassCard(context, prefs).setCornerRadiusDp(26f);
        int cp = Math.round(18f * d);
        pickerCard.setPadding(cp, cp, cp, cp);
        mGlassCards.add(pickerCard);

        LinearLayout hmsRow = new LinearLayout(context);
        hmsRow.setOrientation(LinearLayout.HORIZONTAL);
        hmsRow.setGravity(Gravity.CENTER);

        mHoursValTv = new TextView(context);
        mPrimaryLabels.add(mHoursValTv);
        hmsRow.addView(buildHmsColumn(context, "hours", mHoursValTv,
                () -> { mPickHours = (mPickHours + 1) % 24; refreshPickerTexts(); },
                () -> { mPickHours = (mPickHours + 23) % 24; refreshPickerTexts(); }, d));

        hmsRow.addView(buildColon(context, d));

        mMinsValTv = new TextView(context);
        mPrimaryLabels.add(mMinsValTv);
        hmsRow.addView(buildHmsColumn(context, "min", mMinsValTv,
                () -> { mPickMins = (mPickMins + 1) % 60; refreshPickerTexts(); },
                () -> { mPickMins = (mPickMins + 59) % 60; refreshPickerTexts(); }, d));

        hmsRow.addView(buildColon(context, d));

        mSecsValTv = new TextView(context);
        mPrimaryLabels.add(mSecsValTv);
        hmsRow.addView(buildHmsColumn(context, "sec", mSecsValTv,
                () -> { mPickSecs = (mPickSecs + 5) % 60; refreshPickerTexts(); },
                () -> { mPickSecs = (mPickSecs + 55) % 60; refreshPickerTexts(); }, d));

        pickerCard.addView(hmsRow);

        // Label Input inside Picker Card
        mTimerLabelInput = new EditText(context);
        mTimerLabelInput.setHint("Timer Label (e.g. Tea, Workout, Focus)");
        mTimerLabelInput.setText(mState.label != null ? mState.label : "Timer");
        mTimerLabelInput.setSingleLine(true);
        mTimerLabelInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setCornerRadius(14f * d);
        inBg.setColor(0x26787880);
        mTimerLabelInput.setBackground(inBg);
        int ip = Math.round(11f * d);
        mTimerLabelInput.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = Math.round(14f * d);
        pickerCard.addView(mTimerLabelInput, inLp);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = Math.round(14f * d);
        mSetupSection.addView(pickerCard, pcLp);
        mMainCol.addView(mSetupSection);

        // --- Active Countdown Ring Section ---
        mActiveSection = new LinearLayout(context);
        mActiveSection.setOrientation(LinearLayout.VERTICAL);
        mActiveSection.setGravity(Gravity.CENTER_HORIZONTAL);

        SamuraiGlassCard ringPlatter = new SamuraiGlassCard(context, prefs).setCornerRadiusDp(30f);
        ringPlatter.setGravity(Gravity.CENTER);
        ringPlatter.setPadding(cp, Math.round(24f * d), cp, Math.round(24f * d));
        mGlassCards.add(ringPlatter);

        FrameLayout ringBox = new FrameLayout(context);
        int ringDia = Math.round(244f * d);
        mRingView = new CountdownRingView(context);
        ringBox.addView(mRingView, new LayoutParams(ringDia, ringDia, Gravity.CENTER));

        LinearLayout ringCenterCol = new LinearLayout(context);
        ringCenterCol.setOrientation(LinearLayout.VERTICAL);
        ringCenterCol.setGravity(Gravity.CENTER);

        mActiveLabelTv = new TextView(context);
        mActiveLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        mActiveLabelTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSecondaryLabels.add(mActiveLabelTv);
        ringCenterCol.addView(mActiveLabelTv);

        mActiveCountdownTv = new TextView(context);
        mActiveCountdownTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 46f);
        mActiveCountdownTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        mActiveCountdownTv.setFontFeatureSettings("tnum");
        mPrimaryLabels.add(mActiveCountdownTv);
        ringCenterCol.addView(mActiveCountdownTv);

        mTargetBellTv = new TextView(context);
        mTargetBellTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mSecondaryLabels.add(mTargetBellTv);
        ringCenterCol.addView(mTargetBellTv);

        ringBox.addView(ringCenterCol, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        ringPlatter.addView(ringBox);

        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = Math.round(8f * d);
        mActiveSection.addView(ringPlatter, actLp);
        mMainCol.addView(mActiveSection);

        // --- Bottom Control Orbs Row: [Cancel]  <--->  [Start / Pause / Resume] ---
        LinearLayout ctrlRow = new LinearLayout(context);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setGravity(Gravity.CENTER_VERTICAL);
        ctrlRow.setPadding(Math.round(10f * d), Math.round(18f * d), Math.round(10f * d), 0);

        int orbDia = Math.round(84f * d);
        mCancelOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(42f)
                .setInteractivePressScale(true);
        mCancelOrb.setGravity(Gravity.CENTER);
        mCancelTv = new TextView(context);
        mCancelTv.setText("Cancel");
        mCancelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
        mCancelTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mCancelTv.setGravity(Gravity.CENTER);
        mPrimaryLabels.add(mCancelTv);
        mCancelOrb.addView(mCancelTv, new LinearLayout.LayoutParams(orbDia, orbDia));
        mCancelOrb.setOnClickListener(v -> onCancelClicked());
        mGlassCards.add(mCancelOrb);
        ctrlRow.addView(mCancelOrb, new LinearLayout.LayoutParams(orbDia, orbDia));

        ctrlRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        mPrimaryActionOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(42f)
                .setTint(0xFF30D158, 0.36f)
                .setInteractivePressScale(true);
        mPrimaryActionOrb.setGravity(Gravity.CENTER);
        mPrimaryActionTv = new TextView(context);
        mPrimaryActionTv.setText("Start");
        mPrimaryActionTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
        mPrimaryActionTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mPrimaryActionTv.setGravity(Gravity.CENTER);
        mPrimaryActionOrb.addView(mPrimaryActionTv, new LinearLayout.LayoutParams(orbDia, orbDia));
        mPrimaryActionOrb.setOnClickListener(v -> onPrimaryActionClicked());
        mGlassCards.add(mPrimaryActionOrb);
        ctrlRow.addView(mPrimaryActionOrb, new LinearLayout.LayoutParams(orbDia, orbDia));

        mMainCol.addView(ctrlRow);

        refreshPickerTexts();
        applyTheme(prefs.darkTheme);
        syncSectionsAndButtons();
    }

    public void startExternalTimer(int seconds, String label) {
        long ms = Math.max(1, seconds) * 1000L;
        String cleanLbl = (label != null && !label.trim().isEmpty()) ? label.trim() : "Timer";
        mTimerLabelInput.setText(cleanLbl);
        startTimerWithDuration(ms, cleanLbl);
    }

    public void setWindowInsetsPadding(int topInset, int bottomInset) {
        float d = getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);
        mMainCol.setPadding(
                side,
                topInset + Math.round(14f * d),
                side,
                bottomInset + Math.round(108f * d));
    }

    public void setTabVisible(boolean visible) {
        mTabVisible = visible;
        mState = ClockDataStore.loadTimerState(getContext());
        Choreographer.getInstance().removeFrameCallback(mRingFrameCallback);
        syncSectionsAndButtons();
        if (visible && mState.running) {
            Choreographer.getInstance().postFrameCallback(mRingFrameCallback);
        }
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        for (SamuraiGlassCard c : mGlassCards) c.updatePrefs(prefs);
        mRingView.setDarkTheme(prefs.darkTheme);
        applyTheme(prefs.darkTheme);
        syncSectionsAndButtons();
    }

    private void onPrimaryActionClicked() {
        Context ctx = getContext();
        SamuraiGlassPrefs.hapticClick(ctx, mPrimaryActionOrb);

        if (!mState.running && mState.pausedRemainingMs <= 0L) {
            long totalMs = (mPickHours * 3600L + mPickMins * 60L + mPickSecs) * 1000L;
            if (totalMs <= 0L) {
                totalMs = 60_000L; // minimum 1m safeguard
            }
            startTimerWithDuration(totalMs, currentLabelText());
        } else if (mState.running) {
            // Pause
            long rem = Math.max(1000L, mState.endEpochMs - System.currentTimeMillis());
            mState.running = false;
            mState.pausedRemainingMs = rem;
            ClockDataStore.saveTimerState(ctx, mState);
            AlarmScheduler.cancelTimerCompletion(ctx);
            Choreographer.getInstance().removeFrameCallback(mRingFrameCallback);
            clearOrRestoreStopwatchBus(ctx);
            syncSectionsAndButtons();
        } else {
            // Resume
            long endMs = System.currentTimeMillis() + mState.pausedRemainingMs;
            mState.running = true;
            mState.endEpochMs = endMs;
            mState.pausedRemainingMs = 0L;
            ClockDataStore.saveTimerState(ctx, mState);
            AlarmScheduler.scheduleTimerCompletion(ctx, endMs, mState.label);
            SamuraiDynamicIslandBus.publishTimer(
                    ctx, true, false, mState.label, endMs, ctx.getPackageName());
            syncSectionsAndButtons();
            Choreographer.getInstance().postFrameCallback(mRingFrameCallback);
        }
    }

    private void startTimerWithDuration(long durationMs, String label) {
        Context ctx = getContext();
        long endMs = System.currentTimeMillis() + durationMs;
        mState.totalDurationMs = durationMs;
        mState.endEpochMs = endMs;
        mState.pausedRemainingMs = 0L;
        mState.running = true;
        mState.label = label;
        ClockDataStore.saveTimerState(ctx, mState);

        AlarmScheduler.scheduleTimerCompletion(ctx, endMs, label);
        SamuraiDynamicIslandBus.publishTimer(
                ctx, true, false, label, endMs, ctx.getPackageName());

        syncSectionsAndButtons();
        Choreographer.getInstance().removeFrameCallback(mRingFrameCallback);
        if (mTabVisible) {
            Choreographer.getInstance().postFrameCallback(mRingFrameCallback);
        }
    }

    private void onCancelClicked() {
        if (!mState.running && mState.pausedRemainingMs <= 0L) return;
        Context ctx = getContext();
        SamuraiGlassPrefs.hapticClick(ctx, mCancelOrb);
        mState.running = false;
        mState.pausedRemainingMs = 0L;
        mState.endEpochMs = 0L;
        ClockDataStore.saveTimerState(ctx, mState);
        AlarmScheduler.cancelTimerCompletion(ctx);
        Choreographer.getInstance().removeFrameCallback(mRingFrameCallback);
        clearOrRestoreStopwatchBus(ctx);
        syncSectionsAndButtons();
    }

    private void clearOrRestoreStopwatchBus(Context ctx) {
        if (ClockDataStore.isStopwatchActive()) {
            SamuraiDynamicIslandBus.publishTimer(
                    ctx,
                    true,
                    true,
                    "Stopwatch",
                    ClockDataStore.getStopwatchVirtualStartEpochMs(),
                    ctx.getPackageName());
        } else {
            SamuraiDynamicIslandBus.clear(ctx, SamuraiDynamicIslandBus.KEY_DI_TIMER);
        }
    }

    private void syncSectionsAndButtons() {
        boolean activeOrPaused = mState.running || mState.pausedRemainingMs > 0L;
        mSetupSection.setVisibility(activeOrPaused ? View.GONE : View.VISIBLE);
        mActiveSection.setVisibility(activeOrPaused ? View.VISIBLE : View.GONE);
        mCancelOrb.setAlpha(activeOrPaused ? 1.0f : 0.45f);

        boolean dark = mPrefs.darkTheme;
        if (!activeOrPaused) {
            mPrimaryActionOrb.setTint(0xFF30D158, 0.36f);
            mPrimaryActionTv.setText("Start");
            mPrimaryActionTv.setTextColor(dark ? 0xFF32D74B : 0xFF1E8E3E);
        } else if (mState.running) {
            mPrimaryActionOrb.setTint(0xFFFF9F0A, 0.38f);
            mPrimaryActionTv.setText("Pause");
            mPrimaryActionTv.setTextColor(dark ? 0xFFFFB340 : 0xFFB25E00);
            long rem = Math.max(0L, mState.endEpochMs - System.currentTimeMillis());
            updateRingUi(rem);
        } else {
            mPrimaryActionOrb.setTint(0xFF30D158, 0.36f);
            mPrimaryActionTv.setText("Resume");
            mPrimaryActionTv.setTextColor(dark ? 0xFF32D74B : 0xFF1E8E3E);
            updateRingUi(mState.pausedRemainingMs);
            mTargetBellTv.setText("Paused");
        }
    }

    private void updateRingUi(long remainingMs) {
        long total = Math.max(1000L, mState.totalDurationMs);
        float progress = SamuraiGlassPrefs.clampF(remainingMs / (float) total, 0f, 1f);
        mRingView.setProgress(progress);
        mActiveLabelTv.setText(mState.label != null ? mState.label : "Timer");
        mActiveCountdownTv.setText(formatCountdown(remainingMs));
        if (mState.running) {
            String endStr = new SimpleDateFormat("HH:mm", Locale.US)
                    .format(new Date(mState.endEpochMs));
            mTargetBellTv.setText("\uD83D\uDD14  " + endStr);
        }
    }

    private String currentLabelText() {
        String raw = mTimerLabelInput.getText() != null
                ? mTimerLabelInput.getText().toString().trim() : "";
        return raw.isEmpty() ? "Timer" : raw;
    }

    private void refreshPickerTexts() {
        mHoursValTv.setText(String.format(Locale.US, "%02d", mPickHours));
        mMinsValTv.setText(String.format(Locale.US, "%02d", mPickMins));
        mSecsValTv.setText(String.format(Locale.US, "%02d", mPickSecs));
    }

    private void applyTheme(boolean dark) {
        int pri = dark ? 0xFFF5F5FA : 0xFF14161F;
        int sec = dark ? 0x99EBEBF5 : 0x8A3C3C43;
        for (TextView tv : mPrimaryLabels) tv.setTextColor(pri);
        for (TextView tv : mSecondaryLabels) tv.setTextColor(sec);
        mTimerLabelInput.setTextColor(pri);
        mTimerLabelInput.setHintTextColor(sec);
        mRingView.setDarkTheme(dark);
    }

    private static TextView buildColon(Context ctx, float d) {
        TextView c = new TextView(ctx);
        c.setText(":");
        c.setTextColor(0xFFFF9F0A);
        c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f);
        c.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        c.setPadding(Math.round(10f * d), 0, Math.round(10f * d), Math.round(12f * d));
        return c;
    }

    private LinearLayout buildHmsColumn(
            Context ctx, String unit, TextView valTv, Runnable inc, Runnable dec, float d) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView up = new TextView(ctx);
        up.setText("\u25B2");
        up.setTextColor(0xFFFF9F0A);
        up.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        up.setGravity(Gravity.CENTER);
        up.setPadding(Math.round(16f * d), Math.round(6f * d), Math.round(16f * d), Math.round(6f * d));
        up.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            inc.run();
        });
        col.addView(up);

        valTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 38f);
        valTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        valTv.setFontFeatureSettings("tnum");
        valTv.setGravity(Gravity.CENTER);
        col.addView(valTv);

        TextView dn = new TextView(ctx);
        dn.setText("\u25BC");
        dn.setTextColor(0xFFFF9F0A);
        dn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        dn.setGravity(Gravity.CENTER);
        dn.setPadding(Math.round(16f * d), Math.round(6f * d), Math.round(16f * d), Math.round(6f * d));
        dn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            dec.run();
        });
        col.addView(dn);

        TextView uTv = new TextView(ctx);
        uTv.setText(unit);
        uTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        mSecondaryLabels.add(uTv);
        col.addView(uTv);

        return col;
    }

    private static String formatCountdown(long ms) {
        long totalSec = Math.max(0L, (ms + 999L) / 1000L);
        long s = totalSec % 60L;
        long m = (totalSec / 60L) % 60L;
        long h = totalSec / 3600L;
        if (h > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /**
     * Animated 90Hz Refractive Circular Countdown Ring (`CountdownRingView`).
     */
    private static final class CountdownRingView extends View {
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mOval = new RectF();
        private float mProgress = 1.0f;
        private boolean mDark = true;

        CountdownRingView(Context context) {
            super(context);
            mTrackPaint.setStyle(Paint.Style.STROKE);
            mArcPaint.setStyle(Paint.Style.STROKE);
            mArcPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setProgress(float p) {
            mProgress = SamuraiGlassPrefs.clampF(p, 0f, 1f);
            invalidate();
        }

        void setDarkTheme(boolean dark) {
            mDark = dark;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 8f || h <= 8f) return;
            float d = getResources().getDisplayMetrics().density;
            float stroke = 11.5f * d;
            float pad = stroke + 4f * d;
            mOval.set(pad, pad, w - pad, h - pad);

            mTrackPaint.setStrokeWidth(stroke);
            mTrackPaint.setColor(mDark ? 0x33787880 : 0x24787880);
            canvas.drawOval(mOval, mTrackPaint);

            mArcPaint.setStrokeWidth(stroke);
            mArcPaint.setShader(new SweepGradient(
                    w * 0.5f, h * 0.5f,
                    new int[] { 0xFFFF9F0A, 0xFFFF5E3A, 0xFFFF9F0A },
                    new float[] { 0f, 0.5f, 1f }));

            float sweep = 360f * mProgress;
            canvas.drawArc(mOval, -90f, sweep, false, mArcPaint);

            // Specular leading tip bead
            double tipAng = Math.toRadians(-90.0 + sweep);
            float r = mOval.width() * 0.5f;
            float tx = w * 0.5f + (float) Math.cos(tipAng) * r;
            float ty = h * 0.5f + (float) Math.sin(tipAng) * r;
            mBeadPaint.setColor(0xFFFFFFFF);
            mBeadPaint.setShadowLayer(4f * d, 0f, 0f, 0x99FF9F0A);
            canvas.drawCircle(tx, ty, stroke * 0.42f, mBeadPaint);
        }
    }
}
