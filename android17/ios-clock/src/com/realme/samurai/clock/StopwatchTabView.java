package com.realme.samurai.clock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tab 2 — 90Hz Choreographer Precision Stopwatch (`StopwatchTabView`) with toggleable iOS 26
 * Analog Sweep Dial, refractive Start/Stop & Lap/Reset circular orbs, Fastest/Slowest Lap table,
 * and live `SamuraiDynamicIslandBus` publishing.
 */
public final class StopwatchTabView extends FrameLayout {

    private static final class LapRecord {
        final int lapNumber;
        final long splitNanos;
        final long totalNanos;

        LapRecord(int lapNumber, long splitNanos, long totalNanos) {
            this.lapNumber = lapNumber;
            this.splitNanos = splitNanos;
            this.totalNanos = totalNanos;
        }
    }

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final List<SamuraiGlassCard> mGlassCards = new ArrayList<>();
    private final List<LapRecord> mLaps = new ArrayList<>();

    private final LinearLayout mMainCol;
    private final TextView mHeaderTitle;
    private final TextView mModeTogglePill;
    private final TextView mDigitalHeroTv;
    private final AnalogChronographView mAnalogView;
    private final TextView mAnalogDigitalSubTv;

    private final SamuraiGlassCard mLeftOrbCard;
    private final TextView mLeftOrbTv;
    private final SamuraiGlassCard mRightOrbCard;
    private final TextView mRightOrbTv;

    private final SamuraiGlassCard mLapCard;
    private final LinearLayout mLapRowsCol;
    private TextView mLiveLapSplitTv;

    private boolean mAnalogMode = false;
    private boolean mRunning = false;
    private boolean mTabVisible = false;
    private long mAccumulatedNanos = 0L;
    private long mRunStartRealtimeNanos = 0L;
    private long mLastLapTotalNanos = 0L;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning || !mTabVisible) return;
            renderFrame();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public StopwatchTabView(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = prefs;
        float d = context.getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);

        mMainCol = new LinearLayout(context);
        mMainCol.setOrientation(LinearLayout.VERTICAL);
        mMainCol.setPadding(side, Math.round(52f * d), side, Math.round(112f * d));
        addView(mMainCol, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Top Bar: Stopwatch Title + Digital / Analog Sweep Toggle Pill
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        mHeaderTitle = new TextView(context);
        mHeaderTitle.setText("Stopwatch");
        mHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30f);
        mHeaderTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mHeaderTitle.setLetterSpacing(-0.02f);
        topRow.addView(mHeaderTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        SamuraiGlassCard modeOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(18f)
                .setInteractivePressScale(true);
        modeOrb.setPadding(Math.round(14f * d), Math.round(7f * d), Math.round(14f * d), Math.round(7f * d));
        mModeTogglePill = new TextView(context);
        mModeTogglePill.setText("Analog Dial");
        mModeTogglePill.setTextColor(0xFFFF9F0A);
        mModeTogglePill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mModeTogglePill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        modeOrb.addView(mModeTogglePill);
        modeOrb.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            toggleDialMode();
        });
        mGlassCards.add(modeOrb);
        topRow.addView(modeOrb);
        mMainCol.addView(topRow);

        // Clock Face Container (Digital Hero OR 90Hz Analog Sweep Dial)
        FrameLayout faceStage = new FrameLayout(context);
        faceStage.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            toggleDialMode();
        });

        mDigitalHeroTv = new TextView(context);
        mDigitalHeroTv.setText("00:00.00");
        mDigitalHeroTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 68f);
        mDigitalHeroTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        mDigitalHeroTv.setFontFeatureSettings("tnum");
        mDigitalHeroTv.setGravity(Gravity.CENTER);
        faceStage.addView(mDigitalHeroTv, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout analogWrap = new LinearLayout(context);
        analogWrap.setOrientation(LinearLayout.VERTICAL);
        analogWrap.setGravity(Gravity.CENTER);
        mAnalogView = new AnalogChronographView(context);
        int dialPx = Math.round(230f * d);
        analogWrap.addView(mAnalogView, new LinearLayout.LayoutParams(dialPx, dialPx));

        mAnalogDigitalSubTv = new TextView(context);
        mAnalogDigitalSubTv.setText("00:00.00");
        mAnalogDigitalSubTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        mAnalogDigitalSubTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mAnalogDigitalSubTv.setFontFeatureSettings("tnum");
        mAnalogDigitalSubTv.setPadding(0, Math.round(4f * d), 0, 0);
        analogWrap.addView(mAnalogDigitalSubTv);

        analogWrap.setVisibility(View.GONE);
        faceStage.addView(analogWrap, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(262f * d));
        stageLp.topMargin = Math.round(6f * d);
        mMainCol.addView(faceStage, stageLp);

        // Refractive Circular Control Orbs Row: [Lap / Reset]  <--->  [Start / Stop]
        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setPadding(Math.round(8f * d), Math.round(4f * d), Math.round(8f * d), Math.round(14f * d));

        int orbDia = Math.round(82f * d);

        mLeftOrbCard = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(41f)
                .setInteractivePressScale(true);
        mLeftOrbCard.setGravity(Gravity.CENTER);
        mLeftOrbTv = new TextView(context);
        mLeftOrbTv.setText("Lap");
        mLeftOrbTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
        mLeftOrbTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mLeftOrbTv.setGravity(Gravity.CENTER);
        mLeftOrbCard.addView(mLeftOrbTv, new LinearLayout.LayoutParams(orbDia, orbDia));
        mLeftOrbCard.setOnClickListener(v -> onLeftOrbClicked());
        mGlassCards.add(mLeftOrbCard);
        controlsRow.addView(mLeftOrbCard, new LinearLayout.LayoutParams(orbDia, orbDia));

        controlsRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        mRightOrbCard = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(41f)
                .setTint(0xFF30D158, 0.36f)
                .setInteractivePressScale(true);
        mRightOrbCard.setGravity(Gravity.CENTER);
        mRightOrbTv = new TextView(context);
        mRightOrbTv.setText("Start");
        mRightOrbTv.setTextColor(0xFF30D158);
        mRightOrbTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
        mRightOrbTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mRightOrbTv.setGravity(Gravity.CENTER);
        mRightOrbCard.addView(mRightOrbTv, new LinearLayout.LayoutParams(orbDia, orbDia));
        mRightOrbCard.setOnClickListener(v -> onRightOrbClicked());
        mGlassCards.add(mRightOrbCard);
        controlsRow.addView(mRightOrbCard, new LinearLayout.LayoutParams(orbDia, orbDia));

        mMainCol.addView(controlsRow);

        // Live Scrollable Lap Table inside a `SamuraiGlassCard`
        mLapCard = new SamuraiGlassCard(context, prefs).setCornerRadiusDp(24f);
        mLapCard.setPadding(Math.round(16f * d), Math.round(8f * d), Math.round(16f * d), Math.round(8f * d));
        mGlassCards.add(mLapCard);

        ScrollView lapScroll = new ScrollView(context);
        lapScroll.setVerticalScrollBarEnabled(false);
        mLapRowsCol = new LinearLayout(context);
        mLapRowsCol.setOrientation(LinearLayout.VERTICAL);
        lapScroll.addView(mLapRowsCol);
        mLapCard.addView(lapScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        mMainCol.addView(mLapCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        applyTheme(prefs.darkTheme);
        rebuildLapTable();
        syncOrbVisuals();
    }

    public void setWindowInsetsPadding(int topInset, int bottomInset) {
        float d = getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);
        mMainCol.setPadding(
                side,
                topInset + Math.round(14f * d),
                side,
                bottomInset + Math.round(98f * d));
    }

    public void setTabVisible(boolean visible) {
        mTabVisible = visible;
        Choreographer.getInstance().removeFrameCallback(mFrameCallback);
        if (visible) {
            renderFrame();
            if (mRunning) {
                Choreographer.getInstance().postFrameCallback(mFrameCallback);
            }
        }
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        for (SamuraiGlassCard c : mGlassCards) c.updatePrefs(prefs);
        mAnalogView.setDarkTheme(prefs.darkTheme);
        applyTheme(prefs.darkTheme);
        rebuildLapTable();
        syncOrbVisuals();
    }

    private void toggleDialMode() {
        mAnalogMode = !mAnalogMode;
        mModeTogglePill.setText(mAnalogMode ? "Digital" : "Analog Dial");
        mDigitalHeroTv.setVisibility(mAnalogMode ? View.GONE : View.VISIBLE);
        ((View) mAnalogView.getParent()).setVisibility(mAnalogMode ? View.VISIBLE : View.GONE);
        renderFrame();
    }

    private void onRightOrbClicked() {
        Context ctx = getContext();
        SamuraiGlassPrefs.hapticClick(ctx, mRightOrbCard);
        if (!mRunning) {
            mRunning = true;
            mRunStartRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            long elapsedMs = currentTotalNanos() / 1_000_000L;
            long virtualStartEpoch = System.currentTimeMillis() - elapsedMs;
            ClockDataStore.setStopwatchBusState(true, virtualStartEpoch);
            SamuraiDynamicIslandBus.publishTimer(
                    ctx, true, true, "Stopwatch", virtualStartEpoch, ctx.getPackageName());

            Choreographer.getInstance().removeFrameCallback(mFrameCallback);
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        } else {
            mAccumulatedNanos += (SystemClock.elapsedRealtimeNanos() - mRunStartRealtimeNanos);
            mRunning = false;
            Choreographer.getInstance().removeFrameCallback(mFrameCallback);
            clearOrRestoreTimerBus(ctx);
        }
        syncOrbVisuals();
        rebuildLapTable();
        renderFrame();
    }

    private void onLeftOrbClicked() {
        Context ctx = getContext();
        if (mRunning) {
            SamuraiGlassPrefs.hapticTick(mLeftOrbCard);
            long total = currentTotalNanos();
            long split = Math.max(0L, total - mLastLapTotalNanos);
            mLastLapTotalNanos = total;
            mLaps.add(0, new LapRecord(mLaps.size() + 1, split, total));
            rebuildLapTable();
        } else if (mAccumulatedNanos > 0L) {
            SamuraiGlassPrefs.hapticClick(ctx, mLeftOrbCard);
            mAccumulatedNanos = 0L;
            mLastLapTotalNanos = 0L;
            mLaps.clear();
            clearOrRestoreTimerBus(ctx);
            syncOrbVisuals();
            rebuildLapTable();
            renderFrame();
        }
    }

    private void clearOrRestoreTimerBus(Context ctx) {
        ClockDataStore.setStopwatchBusState(false, 0L);
        ClockDataStore.TimerState ts = ClockDataStore.loadTimerState(ctx);
        if (ts.running && ts.endEpochMs > System.currentTimeMillis()) {
            SamuraiDynamicIslandBus.publishTimer(
                    ctx, true, false, ts.label, ts.endEpochMs, ctx.getPackageName());
        } else {
            SamuraiDynamicIslandBus.clear(ctx, SamuraiDynamicIslandBus.KEY_DI_TIMER);
        }
    }

    private long currentTotalNanos() {
        if (mRunning) {
            return mAccumulatedNanos + (SystemClock.elapsedRealtimeNanos() - mRunStartRealtimeNanos);
        }
        return mAccumulatedNanos;
    }

    private void renderFrame() {
        long totalNs = currentTotalNanos();
        long curLapNs = Math.max(0L, totalNs - mLastLapTotalNanos);
        String formatted = formatStopwatch(totalNs);
        mDigitalHeroTv.setText(formatted);
        mAnalogDigitalSubTv.setText(formatted);
        if (mAnalogMode) {
            mAnalogView.setTimes(totalNs, curLapNs, !mLaps.isEmpty());
        }
        if (mLiveLapSplitTv != null) {
            mLiveLapSplitTv.setText(formatStopwatch(curLapNs));
        }
    }

    private void syncOrbVisuals() {
        boolean dark = mPrefs.darkTheme;
        if (mRunning) {
            mRightOrbCard.setTint(0xFFFF453A, 0.36f);
            mRightOrbTv.setText("Stop");
            mRightOrbTv.setTextColor(dark ? 0xFFFF6961 : 0xFFD70015);
            mLeftOrbTv.setText("Lap");
            mLeftOrbTv.setTextColor(dark ? 0xFFF5F5FA : 0xFF1C1C1E);
            mLeftOrbCard.setAlpha(1.0f);
        } else {
            mRightOrbCard.setTint(0xFF30D158, 0.36f);
            mRightOrbTv.setText("Start");
            mRightOrbTv.setTextColor(dark ? 0xFF32D74B : 0xFF1E8E3E);
            boolean canReset = mAccumulatedNanos > 0L;
            mLeftOrbTv.setText(canReset ? "Reset" : "Lap");
            mLeftOrbTv.setTextColor(dark ? 0xFFF5F5FA : 0xFF1C1C1E);
            mLeftOrbCard.setAlpha(canReset ? 1.0f : 0.52f);
        }
    }

    private void rebuildLapTable() {
        mLapRowsCol.removeAllViews();
        mLiveLapSplitTv = null;
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int pri = mPrefs.darkTheme ? 0xFFF5F5FA : 0xFF14161F;
        int sec = mPrefs.darkTheme ? 0x99EBEBF5 : 0x8A3C3C43;

        if (!mRunning && mAccumulatedNanos == 0L && mLaps.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("Tap Start to begin 90 Hz precision timing.\nTap the clock face anytime to switch Analog / Digital.");
            empty.setTextColor(sec);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Math.round(12f * d), Math.round(28f * d), Math.round(12f * d), Math.round(28f * d));
            mLapRowsCol.addView(empty);
            return;
        }

        // Current Live Lap Row at top
        int liveNum = mLaps.size() + 1;
        LinearLayout liveRow = new LinearLayout(ctx);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        int padV = Math.round(10f * d);
        liveRow.setPadding(Math.round(4f * d), padV, Math.round(4f * d), padV);

        TextView liveLbl = new TextView(ctx);
        liveLbl.setText("Lap " + liveNum);
        liveLbl.setTextColor(pri);
        liveLbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        liveRow.addView(liveLbl, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mLiveLapSplitTv = new TextView(ctx);
        mLiveLapSplitTv.setText(formatStopwatch(Math.max(0L, currentTotalNanos() - mLastLapTotalNanos)));
        mLiveLapSplitTv.setTextColor(pri);
        mLiveLapSplitTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        mLiveLapSplitTv.setFontFeatureSettings("tnum");
        liveRow.addView(mLiveLapSplitTv);
        mLapRowsCol.addView(liveRow);

        // Identify Fastest & Slowest Laps when >= 2 laps recorded
        int fastestLapNum = -1;
        int slowestLapNum = -1;
        if (mLaps.size() >= 2) {
            long minSplit = Long.MAX_VALUE;
            long maxSplit = Long.MIN_VALUE;
            for (LapRecord r : mLaps) {
                if (r.splitNanos < minSplit) {
                    minSplit = r.splitNanos;
                    fastestLapNum = r.lapNumber;
                }
                if (r.splitNanos > maxSplit) {
                    maxSplit = r.splitNanos;
                    slowestLapNum = r.lapNumber;
                }
            }
        }

        for (LapRecord rec : mLaps) {
            View div = new View(ctx);
            div.setBackgroundColor(mPrefs.darkTheme ? 0x22FFFFFF : 0x18000000);
            mLapRowsCol.addView(div, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.75f * d))));

            int rowCol = pri;
            String tag = "";
            if (rec.lapNumber == fastestLapNum) {
                rowCol = 0xFF30D158; // Fastest = Green
                tag = "  \u2022 Fastest";
            } else if (rec.lapNumber == slowestLapNum) {
                rowCol = 0xFFFF453A; // Slowest = Red
                tag = "  \u2022 Slowest";
            }

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Math.round(4f * d), padV, Math.round(4f * d), padV);

            TextView numTv = new TextView(ctx);
            numTv.setText("Lap " + rec.lapNumber + tag);
            numTv.setTextColor(rowCol);
            numTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            numTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(numTv, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView splitTv = new TextView(ctx);
            splitTv.setText(formatStopwatch(rec.splitNanos));
            splitTv.setTextColor(rowCol);
            splitTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            splitTv.setFontFeatureSettings("tnum");
            row.addView(splitTv);

            mLapRowsCol.addView(row);
        }
    }

    private void applyTheme(boolean dark) {
        int pri = dark ? 0xFFF5F5FA : 0xFF14161F;
        mHeaderTitle.setTextColor(pri);
        mDigitalHeroTv.setTextColor(pri);
        mAnalogDigitalSubTv.setTextColor(pri);
        mAnalogView.setDarkTheme(dark);
    }

    public static String formatStopwatch(long nanos) {
        long centisTotal = Math.max(0L, nanos / 10_000_000L);
        long cs = centisTotal % 100L;
        long totalSec = centisTotal / 100L;
        long sec = totalSec % 60L;
        long min = (totalSec / 60L) % 60L;
        long hrs = totalSec / 3600L;
        if (hrs > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d", hrs, min, sec, cs);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs);
    }

    /**
     * Authentic iOS 26 90Hz Analog Chronograph Sweep Dial (`AnalogChronographView`).
     */
    private static final class AnalogChronographView extends View {
        private final Paint mTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long mTotalNs = 0L;
        private long mLapNs = 0L;
        private boolean mShowLapHand = false;
        private boolean mDark = true;

        AnalogChronographView(Context context) {
            super(context);
            mTickPaint.setStrokeCap(Paint.Cap.ROUND);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);
            mNumPaint.setTextAlign(Paint.Align.CENTER);
            mNumPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setTimes(long totalNs, long lapNs, boolean showLapHand) {
            mTotalNs = totalNs;
            mLapNs = lapNs;
            mShowLapHand = showLapHand;
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
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = Math.min(w, h) * 0.5f - 6f * d;

            int majorCol = mDark ? 0xFFEBEBF5 : 0xFF1C1C1E;
            int minorCol = mDark ? 0x66EBEBF5 : 0x553C3C43;

            // 120 half-second perimeter ticks + 12 numerals
            for (int i = 0; i < 120; i++) {
                double ang = (i / 120.0) * Math.PI * 2.0 - Math.PI * 0.5;
                boolean isFiveSec = (i % 10 == 0);
                boolean isOneSec = (i % 2 == 0);
                float len = isFiveSec ? (10.5f * d) : (isOneSec ? (6.5f * d) : (3.8f * d));
                mTickPaint.setStrokeWidth((isFiveSec ? 2.0f : 1.1f) * d);
                mTickPaint.setColor(isOneSec ? majorCol : minorCol);

                float x0 = cx + (float) Math.cos(ang) * (r - len);
                float y0 = cy + (float) Math.sin(ang) * (r - len);
                float x1 = cx + (float) Math.cos(ang) * r;
                float y1 = cy + (float) Math.sin(ang) * r;
                canvas.drawLine(x0, y0, x1, y1, mTickPaint);
            }

            mNumPaint.setColor(majorCol);
            mNumPaint.setTextSize(12.5f * d);
            Paint.FontMetrics fm = mNumPaint.getFontMetrics();
            float textCenterOff = -(fm.ascent + fm.descent) * 0.5f;
            for (int s = 5; s <= 60; s += 5) {
                double ang = (s / 60.0) * Math.PI * 2.0 - Math.PI * 0.5;
                float nr = r - 21f * d;
                float nx = cx + (float) Math.cos(ang) * nr;
                float ny = cy + (float) Math.sin(ang) * nr + textCenterOff;
                canvas.drawText(String.valueOf(s), nx, ny, mNumPaint);
            }

            // 30-Minute Upper Sub-Dial
            float subCy = cy - r * 0.34f;
            float subR = r * 0.25f;
            mTickPaint.setColor(minorCol);
            mTickPaint.setStrokeWidth(1.0f * d);
            for (int m = 0; m < 30; m++) {
                double ang = (m / 30.0) * Math.PI * 2.0 - Math.PI * 0.5;
                float sl = (m % 5 == 0) ? (4.5f * d) : (2.4f * d);
                canvas.drawLine(
                         cx + (float) Math.cos(ang) * (subR - sl),
                        subCy + (float) Math.sin(ang) * (subR - sl),
                        cx + (float) Math.cos(ang) * subR,
                        subCy + (float) Math.sin(ang) * subR,
                        mTickPaint);
            }
            double totalMinutes = (mTotalNs / 1_000_000_000.0) / 60.0;
            double minAng = ((totalMinutes % 30.0) / 30.0) * Math.PI * 2.0 - Math.PI * 0.5;
            mHandPaint.setColor(0xFFFF9F0A);
            mHandPaint.setStrokeWidth(1.9f * d);
            canvas.drawLine(
                    cx, subCy,
                    cx + (float) Math.cos(minAng) * (subR - 3f * d),
                    subCy + (float) Math.sin(minAng) * (subR - 3f * d),
                    mHandPaint);
            canvas.drawCircle(cx, subCy, 2.4f * d, mHandPaint);

            // Optional Blue Split/Lap Sweep Hand
            if (mShowLapHand) {
                double lapSec = (mLapNs / 1_000_000_000.0) % 60.0;
                double lapAng = (lapSec / 60.0) * Math.PI * 2.0 - Math.PI * 0.5;
                mHandPaint.setColor(0xFF0A84FF);
                mHandPaint.setStrokeWidth(2.0f * d);
                canvas.drawLine(
                        cx - (float) Math.cos(lapAng) * (16f * d),
                        cy - (float) Math.sin(lapAng) * (16f * d),
                        cx + (float) Math.cos(lapAng) * (r - 5f * d),
                        cy + (float) Math.sin(lapAng) * (r - 5f * d),
                        mHandPaint);
            }

            // Signature Orange Main Sweep Hand
            double mainSec = (mTotalNs / 1_000_000_000.0) % 60.0;
            double mainAng = (mainSec / 60.0) * Math.PI * 2.0 - Math.PI * 0.5;
            mHandPaint.setColor(0xFFFF9F0A);
            mHandPaint.setStrokeWidth(2.3f * d);
            canvas.drawLine(
                    cx - (float) Math.cos(mainAng) * (20f * d),
                    cy - (float) Math.sin(mainAng) * (20f * d),
                    cx + (float) Math.cos(mainAng) * (r - 3f * d),
                    cy + (float) Math.sin(mainAng) * (r - 3f * d),
                    mHandPaint);

            canvas.drawCircle(cx, cy, 4.6f * d, mHandPaint);
            mHandPaint.setColor(mDark ? 0xFF14161F : 0xFFFFFFFF);
            canvas.drawCircle(cx, cy, 1.9f * d, mHandPaint);
        }
    }
}
