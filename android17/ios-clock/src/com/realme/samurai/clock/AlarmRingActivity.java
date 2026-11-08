package com.realme.samurai.clock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiDynamicIslandBus;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-Screen Lockscreen 90Hz AGSL Liquid Glass Alarm & Timer Ringing Screen (`AlarmRingActivity`).
 */
public class AlarmRingActivity extends Activity {

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private int mAlarmId = 1001;
    private String mLabel = "Alarm";
    private boolean mSnoozeAllowed = true;
    private boolean mIsTimer = false;

    private TextView mTimeTv;
    private TextView mDateTv;
    private TextView mLabelTv;
    private View mSnoozeCard;
    private float mPulsePhase = 0f;
    private boolean mRunningAnim = false;

    private final Handler mClockHandler = new Handler(Looper.getMainLooper());
    private final Runnable mClockTick = new Runnable() {
        @Override
        public void run() {
            updateClockText();
            mClockHandler.postDelayed(this, 1000L);
        }
    };

    private final Choreographer.FrameCallback mPulseCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunningAnim) return;
            mPulsePhase = (float) ((frameTimeNanos / 1_000_000_000.0) * 2.4);
            getWindow().getDecorView().invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final BroadcastReceiver mCloseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finishAndRemoveTask();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) {
                km.requestDismissKeyguard(this, null);
            }
        } catch (Throwable ignored) {
        }

        SamuraiGlassPrefs.configure90HzEdgeToEdge(this);
        mPrefs = SamuraiGlassPrefs.read(this, null);
        mPrefs.darkTheme = true; // Lockscreen alarm screen uses dramatic dark refractive obsidian glass
        readExtras(getIntent());

        float d = getResources().getDisplayMetrics().density;
        final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FrameLayout root = new FrameLayout(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                SamuraiAgslEngine.drawProceduralAppBackground(canvas, w, h, true);
                if (w > 4 && h > 4) {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(mPulsePhase);
                    float r = w * (0.48f + 0.14f * pulse);
                    glowPaint.setShader(new RadialGradient(
                            w * 0.5f, h * 0.34f, r,
                            new int[] { 0x55FF9F0A, 0x00FF9F0A },
                            new float[] { 0f, 1f },
                            Shader.TileMode.CLAMP));
                    canvas.drawCircle(w * 0.5f, h * 0.34f, r, glowPaint);
                }
                super.onDraw(canvas);
            }
        };
        root.setWillNotDraw(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Math.round(28f * d);
        col.setPadding(side, Math.round(92f * d), side, Math.round(64f * d));

        mDateTv = new TextView(this);
        mDateTv.setTextColor(0xCCEBEBF5);
        mDateTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f);
        mDateTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mDateTv.setGravity(Gravity.CENTER);
        col.addView(mDateTv);

        mTimeTv = new TextView(this);
        mTimeTv.setTextColor(0xFFFFFFFF);
        mTimeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 84f);
        mTimeTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        mTimeTv.setFontFeatureSettings("tnum");
        mTimeTv.setGravity(Gravity.CENTER);
        col.addView(mTimeTv);

        mLabelTv = new TextView(this);
        mLabelTv.setText(mLabel);
        mLabelTv.setTextColor(0xFFFFB340);
        mLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        mLabelTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mLabelTv.setGravity(Gravity.CENTER);
        mLabelTv.setPadding(0, Math.round(8f * d), 0, 0);
        col.addView(mLabelTv);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Refractive Amber Snooze (9 min) Pill
        SamuraiGlassCard snoozeBtn = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(34f)
                .setTint(0xFFFF9F0A, 0.42f)
                .setInteractivePressScale(true);
        snoozeBtn.setGravity(Gravity.CENTER);
        snoozeBtn.setPadding(Math.round(32f * d), Math.round(20f * d), Math.round(32f * d), Math.round(20f * d));
        TextView snoozeTv = new TextView(this);
        snoozeTv.setText("Snooze (9 min)");
        snoozeTv.setTextColor(0xFFFFFFFF);
        snoozeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f);
        snoozeTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        snoozeTv.setGravity(Gravity.CENTER);
        snoozeBtn.addView(snoozeTv);
        snoozeBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticHeavySpring(this, v);
            AlarmScheduler.scheduleSnooze9Min(this, mAlarmId, mLabel);
            SamuraiDynamicIslandBus.publishToast(
                    this,
                    SamuraiDynamicIslandBus.TYPE_TIMER,
                    "Snoozing 9 min",
                    mLabel,
                    0xFFFF9F0A,
                    2600L);
            AlarmRingService.stopRinging(this);
            finishAndRemoveTask();
        });
        mSnoozeCard = snoozeBtn;
        mSnoozeCard.setVisibility((mSnoozeAllowed && !mIsTimer) ? View.VISIBLE : View.GONE);
        col.addView(snoozeBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Refractive Stop Button
        SamuraiGlassCard stopBtn = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(30f)
                .setTint(0xFFFF453A, 0.36f)
                .setInteractivePressScale(true);
        stopBtn.setGravity(Gravity.CENTER);
        stopBtn.setPadding(Math.round(32f * d), Math.round(17f * d), Math.round(32f * d), Math.round(17f * d));
        TextView stopTv = new TextView(this);
        stopTv.setText("Stop");
        stopTv.setTextColor(0xFFFFFFFF);
        stopTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18.5f);
        stopTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stopTv.setGravity(Gravity.CENTER);
        stopBtn.addView(stopTv);
        stopBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            AlarmRingService.stopRinging(this);
            finishAndRemoveTask();
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopLp.topMargin = Math.round(16f * d);
        col.addView(stopBtn, stopLp);

        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        registerReceiver(
                mCloseReceiver,
                new IntentFilter(AlarmRingService.ACTION_CLOSE_RING_UI),
                Context.RECEIVER_NOT_EXPORTED);
        updateClockText();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readExtras(intent);
        if (mLabelTv != null) mLabelTv.setText(mLabel);
        if (mSnoozeCard != null) {
            mSnoozeCard.setVisibility((mSnoozeAllowed && !mIsTimer) ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRunningAnim = true;
        Choreographer.getInstance().postFrameCallback(mPulseCallback);
        mClockHandler.removeCallbacks(mClockTick);
        mClockHandler.post(mClockTick);
    }

    @Override
    protected void onPause() {
        mRunningAnim = false;
        Choreographer.getInstance().removeFrameCallback(mPulseCallback);
        mClockHandler.removeCallbacks(mClockTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(mCloseReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void readExtras(Intent intent) {
        if (intent == null) return;
        mAlarmId = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, 1001);
        String lbl = intent.getStringExtra(AlarmReceiver.EXTRA_LABEL);
        if (lbl != null && !lbl.isEmpty()) {
            mLabel = lbl;
        }
        mSnoozeAllowed = intent.getBooleanExtra(AlarmReceiver.EXTRA_SNOOZE_ALLOWED, true);
        mIsTimer = intent.getBooleanExtra(AlarmReceiver.EXTRA_IS_TIMER, false);
    }

    private void updateClockText() {
        Date now = new Date();
        if (mTimeTv != null) {
            mTimeTv.setText(new SimpleDateFormat("HH:mm", Locale.US).format(now));
        }
        if (mDateTv != null) {
            mDateTv.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now));
        }
    }
}
