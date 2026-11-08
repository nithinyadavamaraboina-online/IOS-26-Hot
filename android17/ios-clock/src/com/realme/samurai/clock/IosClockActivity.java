package com.realme.samurai.clock;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPillTabBar;
import com.realme.samurai.glass.SamuraiGlassPrefs;

/**
 * Flagship Clean-Room 90Hz AGSL iOS 26 Clock (`IosClockActivity`).
 *
 * Hosts 4 full Liquid Glass tabs (`World Clock`, `Alarms`, `Stopwatch`, `Timers`) driven by the
 * polynomial-morphing `SamuraiGlassPillTabBar` (`sminPoly`), synced live with `iOS Styler` and
 * `SamuraiDynamicIslandBus`.
 */
public class IosClockActivity extends Activity {

    public static final String EXTRA_INITIAL_TAB = "initial_tab";

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private ContentObserver mGlassObserver;

    private FrameLayout mRoot;
    private WorldClockTabView mWorldTab;
    private AlarmsTabView mAlarmsTab;
    private StopwatchTabView mStopwatchTab;
    private TimersTabView mTimersTab;
    private SamuraiGlassPillTabBar mTabBar;
    private FrameLayout mTabDock;

    private int mActiveTab = 0;

    private final OnBackInvokedCallback mBackCallback = () -> {
        if (mActiveTab == 0 && mWorldTab != null && mWorldTab.closeOverlayIfOpen()) {
            return;
        }
        if (mActiveTab == 1 && mAlarmsTab != null && mAlarmsTab.closeOverlayIfOpen()) {
            return;
        }
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SamuraiGlassPrefs.configure90HzEdgeToEdge(this);
        mPrefs = SamuraiGlassPrefs.read(this, null);

        float d = getResources().getDisplayMetrics().density;

        mRoot = new FrameLayout(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                SamuraiAgslEngine.drawProceduralAppBackground(
                        canvas, getWidth(), getHeight(), mPrefs.darkTheme);
                super.onDraw(canvas);
            }
        };
        mRoot.setWillNotDraw(false);

        mWorldTab = new WorldClockTabView(this, mPrefs);
        mAlarmsTab = new AlarmsTabView(this, mPrefs);
        mStopwatchTab = new StopwatchTabView(this, mPrefs);
        mTimersTab = new TimersTabView(this, mPrefs);

        FrameLayout.LayoutParams fullLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        mRoot.addView(mWorldTab, fullLp);
        mRoot.addView(mAlarmsTab, fullLp);
        mRoot.addView(mStopwatchTab, fullLp);
        mRoot.addView(mTimersTab, fullLp);

        // Signature Floating Bottom `sminPoly` Morphing Liquid Glass Pill Tab Bar
        String[] labels = { "World Clock", "Alarms", "Stopwatch", "Timers" };
        String[] glyphs = { "\u25CE", "\u25C9", "\u25F4", "\u25F7" };
        mTabBar = new SamuraiGlassPillTabBar(this, mPrefs, labels, glyphs)
                .setTrailingOrb(true, "+")
                .setOnTabSelectedListener(new SamuraiGlassPillTabBar.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(int index) {
                        switchTab(index, false);
                    }

                    @Override
                    public void onTrailingOrbClicked() {
                        if (mActiveTab == 0) {
                            mWorldTab.openAddCitySheet();
                        } else if (mActiveTab == 1) {
                            mAlarmsTab.openCreateAlarmSheet();
                        }
                    }
                });

        mTabDock = new FrameLayout(this);
        int side = Math.round(14f * d);
        mTabDock.setPadding(side, Math.round(6f * d), side, Math.round(18f * d));
        mTabDock.addView(mTabBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(64f * d)));

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        mRoot.addView(mTabDock, dockLp);

        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            mWorldTab.setWindowInsetsPadding(top, navBottom);
            mAlarmsTab.setWindowInsetsPadding(top, navBottom);
            mStopwatchTab.setWindowInsetsPadding(top, navBottom);
            mTimersTab.setWindowInsetsPadding(top, navBottom);
            mTabDock.setPadding(side, Math.round(6f * d), side, navBottom + Math.round(12f * d));
            return insets;
        });

        setContentView(mRoot);

        switchTab(0, false);
        handleIncomingIntent(getIntent());

        mGlassObserver = SamuraiGlassPrefs.registerObserver(this, () -> {
            SamuraiAgslEngine.invalidateWallpaperCache();
            SamuraiGlassPrefs.read(this, mPrefs);
            syncAllTabsPrefs();
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SamuraiGlassPrefs.read(this, mPrefs);
        syncAllTabsPrefs();
        updateTabRunningStates(true);
    }

    @Override
    protected void onPause() {
        updateTabRunningStates(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        SamuraiGlassPrefs.unregisterObserver(this, mGlassObserver);
        try {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        SamuraiGlassPrefs.read(this, mPrefs);
        syncAllTabsPrefs();
    }

    private void switchTab(int index, boolean animateBar) {
        mActiveTab = Math.max(0, Math.min(3, index));
        mWorldTab.setVisibility(mActiveTab == 0 ? View.VISIBLE : View.GONE);
        mAlarmsTab.setVisibility(mActiveTab == 1 ? View.VISIBLE : View.GONE);
        mStopwatchTab.setVisibility(mActiveTab == 2 ? View.VISIBLE : View.GONE);
        mTimersTab.setVisibility(mActiveTab == 3 ? View.VISIBLE : View.GONE);

        mTabBar.setTrailingOrb(mActiveTab <= 1, "+");
        if (mTabBar.getSelectedIndex() != mActiveTab) {
            mTabBar.setSelectedIndex(mActiveTab, animateBar);
        }
        updateTabRunningStates(true);
    }

    private void updateTabRunningStates(boolean foreground) {
        mWorldTab.setTabVisible(foreground && mActiveTab == 0);
        mStopwatchTab.setTabVisible(foreground && mActiveTab == 2);
        mTimersTab.setTabVisible(foreground && mActiveTab == 3);
        if (foreground && mActiveTab == 1) {
            mAlarmsTab.reloadAndRenderAlarms();
        }
    }

    private void syncAllTabsPrefs() {
        mWorldTab.updatePrefs(mPrefs);
        mAlarmsTab.updatePrefs(mPrefs);
        mStopwatchTab.updatePrefs(mPrefs);
        mTimersTab.updatePrefs(mPrefs);
        mTabBar.updatePrefs(mPrefs);
        mRoot.invalidate();
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        int requestedTab = intent.getIntExtra(EXTRA_INITIAL_TAB, -1);
        if (requestedTab >= 0 && requestedTab <= 3) {
            switchTab(requestedTab, false);
            return;
        }
        String act = intent.getAction();
        if (act == null) return;

        if (AlarmClock.ACTION_SHOW_ALARMS.equals(act)) {
            switchTab(1, false);
        } else if (AlarmClock.ACTION_SET_ALARM.equals(act)) {
            switchTab(1, false);
            int h = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1);
            int m = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0);
            String msg = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE);
            mAlarmsTab.openCreateAlarmSheetWithPreset(h, m, msg);
        } else if (AlarmClock.ACTION_SHOW_TIMERS.equals(act)) {
            switchTab(3, false);
        } else if (AlarmClock.ACTION_SET_TIMER.equals(act)) {
            switchTab(3, false);
            int lenSec = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 300);
            String msg = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE);
            mTimersTab.startExternalTimer(lenSec, msg);
        }
    }
}
