package com.realme.samurai.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;

import com.android.launcher3.CellLayout;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Hotseat;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherState;
import com.android.launcher3.Workspace;
import com.android.launcher3.deviceprofile.WorkspaceProfile;
import com.android.launcher3.icons.ClockDrawableWrapper;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.icons.IconProvider;
import com.android.launcher3.icons.LauncherIconProviderImpl;
import com.android.launcher3.icons.PersistedItemState;
import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.LoaderTask;
import com.android.launcher3.model.ModelTaskController;
import com.android.launcher3.responsive.CalculatedHotseatSpec;
import com.android.launcher3.statemanager.BaseState;
import com.android.launcher3.util.Executors;
import com.android.launcher3.views.ActivityContext;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Surgical Smali Bridge for Pixel Launcher (NexusLauncherRelease.apk v1.5.2):
 * 1. 100% Bottom Google QSB Removal (0x0 GONE dummy view + hotseatQsbSpace=0).
 * 2. Lifted & Curved 90Hz Liquid Glass Dock + iOS 26 "Q Search" <-> Page Dots ("• •") Capsule.
 * 3. Global Bottom Navigation Bar Hide-by-Default (StashedHandleView draw hook + live Settings toggle).
 * 4. 1:1 Bundled iOS 26 / App Store Icon Pack + Clean Multi-Plane Fallback with instant live reload.
 * 5. iOS 26 Left-Right Swipes Only: Vertical slide-up All Apps disabled + automatic placement of all
 *    installed & newly installed apps onto left-right Home Screen pages (0 duplicates, 0 orphaned apps).
 */
public final class SamuraiLauncherHooks {
    public static final String KEY_LIQUID_GLASS_MODE = "samurai_liquid_glass_mode";
    public static final String KEY_IOS26_ICON_MODE = "samurai_ios26_icon_mode";
    public static final String KEY_SEARCH_PILL_ENABLED = "samurai_search_pill_enabled";
    public static final String KEY_GESTURE_PILL_ENABLED = "samurai_gesture_pill_enabled";
    public static final String KEY_SECURE_GESTURE_PILL = "samurai_ios26_gesture_pill";
    public static final String KEY_LAUNCHER_BAR_STATE = "samurai_launcher_bar_state";
    public static final String KEY_CC_STATUS = "samurai_cc_status";
    public static final String KEY_WALLPAPER_SEQ = "samurai_wallpaper_seq";
    public static final String KEY_REFRACTION_PCT = "samurai_ios26_refraction_pct";
    public static final String KEY_FROST_PCT = "samurai_ios26_frost_pct";
    public static final String KEY_WP_BRIGHTNESS_PCT = "samurai_ios26_wp_brightness_pct";
    public static final int DEFAULT_REFRACTION_PCT = 20;    // 20% default Liquid Glass refraction strength
    public static final int DEFAULT_FROST_PCT = 20;         // 20% default Liquid Glass blur / frost
    public static final int DEFAULT_WP_BRIGHTNESS_PCT = 70; // 70% wallpaper vs 100% icons depth separation

    private static final int SEARCH_PILL_TAG_KEY = 0x53414D50; // 'SAMP'
    private static final int INSET_BOTTOM_TAG_KEY = 0x53414D49; // 'SAMI'

    private static final List<WeakReference<Hotseat>> sHotseats = new ArrayList<>();
    private static final List<WeakReference<View>> sStashedHandles = new ArrayList<>();
    private static final List<WeakReference<View>> sLauncherRoots = new ArrayList<>();
    private static volatile WeakReference<LauncherIconProviderImpl> sIconProviderRef;
    private static volatile boolean sObserverRegistered = false;
    private static volatile int sCachedGlassMode = 0;      // 0=Clear, 1=Tinted, 2=Off
    private static volatile int sCachedIconModeRaw = 0;    // 0=Auto, 1=Default, 2=Dark, 3=Clear
    private static volatile int sCachedSearchPill = 1;     // 1=Show, 0=Hide
    private static volatile int sCachedGesturePill = 1;    // 1=Show iOS Home Indicator by default on ios_beta
    private static volatile boolean sCachedOverlayHomeBarOk = false;
    private static volatile boolean sInNormalWorkspace = true;
    private static volatile boolean sLauncherResumed = false;
    private static volatile boolean sLauncherHasWindowFocus = false;
    private static volatile boolean sRecentsVisible = false;
    private static volatile int sDesiredBarState = 1;
    private static final AtomicInteger sLastPublishedBarState = new AtomicInteger(-1);
    private static final Paint sFallbackHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF sFallbackHandleRect = new RectF();
    private static volatile int sCachedWallpaperSeq = 0;
    private static volatile int sCachedRefractionPct = DEFAULT_REFRACTION_PCT; // 0..150 (20% default)
    private static volatile int sCachedFrostPct = DEFAULT_FROST_PCT;           // 0..150 (20% default)
    private static volatile int sCachedWpBrightnessPct = DEFAULT_WP_BRIGHTNESS_PCT; // 0..100 (70% default)
    private static volatile int sLastDrawnRootDimAlpha = -1;
    private static volatile long sIconCacheSerial = 1L;

    private SamuraiLauncherHooks() {}

    private static void ensureObserver(Context context) {
        if (sObserverRegistered || context == null) return;
        synchronized (SamuraiLauncherHooks.class) {
            if (sObserverRegistered) return;
            final Context appCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            refreshPrefsFromSettings(appCtx);
            SamuraiWallpaperBlurCache.setInvalidator(SamuraiLauncherHooks::invalidateAllHotseats);

            try {
                ContentResolver cr = appCtx.getContentResolver();
                Handler mainHandler = new Handler(Looper.getMainLooper());
                ContentObserver obs = new ContentObserver(mainHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (uri != null && KEY_CC_STATUS.equals(uri.getLastPathSegment())) {
                            boolean prevOverlayOk = sCachedOverlayHomeBarOk;
                            refreshOverlayHomeBarStatus(appCtx);
                            if (sCachedOverlayHomeBarOk != prevOverlayOk) {
                                invalidateAllStashedHandles();
                            }
                            return;
                        }
                        int prevIconMode = sCachedIconModeRaw;
                        int prevWpSeq = sCachedWallpaperSeq;
                        int prevGesturePill = sCachedGesturePill;
                        boolean prevOverlayOk = sCachedOverlayHomeBarOk;
                        int prevWpBright = sCachedWpBrightnessPct;
                        refreshPrefsFromSettings(appCtx);

                        if (sCachedWallpaperSeq != prevWpSeq) {
                            SamuraiWallpaperBlurCache.requestRefresh(appCtx);
                        }
                        if (sCachedGesturePill != prevGesturePill || sCachedOverlayHomeBarOk != prevOverlayOk) {
                            invalidateAllStashedHandles();
                        }
                        if (sCachedWpBrightnessPct != prevWpBright) {
                            invalidateAllLauncherRoots();
                        }
                        SamuraiDockGlassRenderer.invalidateBloomCache();
                        relayoutAndInvalidateAllHotseats(false);

                        boolean iconModeChanged = (sCachedIconModeRaw != prevIconMode);
                        boolean clearWpChanged = (sCachedWallpaperSeq != prevWpSeq)
                                && (getEffectiveIconMode(appCtx) == 3);
                        if (iconModeChanged || clearWpChanged) {
                            sIconCacheSerial++;
                            triggerLiveIconReload();
                        }
                    }
                };
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_LIQUID_GLASS_MODE), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_IOS26_ICON_MODE), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_SEARCH_PILL_ENABLED), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_GESTURE_PILL_ENABLED), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(KEY_GESTURE_PILL_ENABLED), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(KEY_SECURE_GESTURE_PILL), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_CC_STATUS), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_WALLPAPER_SEQ), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_REFRACTION_PCT), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(KEY_REFRACTION_PCT), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_FROST_PCT), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(KEY_FROST_PCT), false, obs);
                cr.registerContentObserver(Settings.Global.getUriFor(KEY_WP_BRIGHTNESS_PCT), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(KEY_WP_BRIGHTNESS_PCT), false, obs);
                sObserverRegistered = true;
            } catch (Throwable ignored) {
                sObserverRegistered = true;
            }
        }
    }

    private static void refreshOverlayHomeBarStatus(Context context) {
        try {
            String ccStatus = Settings.Global.getString(context.getContentResolver(), KEY_CC_STATUS);
            sCachedOverlayHomeBarOk = (ccStatus != null && ccStatus.contains("hbar=ok"));
        } catch (Throwable ignored) {
        }
    }

    private static void refreshPrefsFromSettings(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            int glassDef = Settings.Secure.getInt(cr, "samurai_ios26_glass_mode", 0);
            sCachedGlassMode = clamp(Settings.Global.getInt(cr, KEY_LIQUID_GLASS_MODE, glassDef), 0, 2);
            sCachedIconModeRaw = clamp(Settings.Global.getInt(cr, KEY_IOS26_ICON_MODE, 0), 0, 3);
            sCachedSearchPill = clamp(Settings.Global.getInt(cr, KEY_SEARCH_PILL_ENABLED, 1), 0, 1);
            int secPill = Settings.Secure.getInt(
                    cr, KEY_SECURE_GESTURE_PILL, Settings.Secure.getInt(cr, KEY_GESTURE_PILL_ENABLED, 1));
            sCachedGesturePill = clamp(Settings.Global.getInt(cr, KEY_GESTURE_PILL_ENABLED, secPill), 0, 1);
            refreshOverlayHomeBarStatus(context);
            sCachedWallpaperSeq = Settings.Global.getInt(cr, KEY_WALLPAPER_SEQ, 0);
            int refDef = Settings.Secure.getInt(cr, KEY_REFRACTION_PCT, DEFAULT_REFRACTION_PCT);
            int frsDef = Settings.Secure.getInt(cr, KEY_FROST_PCT, DEFAULT_FROST_PCT);
            int wpBrDef = Settings.Secure.getInt(cr, KEY_WP_BRIGHTNESS_PCT, DEFAULT_WP_BRIGHTNESS_PCT);
            sCachedRefractionPct = clamp(Settings.Global.getInt(cr, KEY_REFRACTION_PCT, refDef), 0, 150);
            sCachedFrostPct = clamp(Settings.Global.getInt(cr, KEY_FROST_PCT, frsDef), 0, 150);
            sCachedWpBrightnessPct = clamp(Settings.Global.getInt(cr, KEY_WP_BRIGHTNESS_PCT, wpBrDef), 0, 100);
        } catch (Throwable ignored) {
        }
    }

    public static float getRefractionScale(Context context) {
        ensureObserver(context);
        return sCachedRefractionPct / 100.0f;
    }

    public static float getFrostScale(Context context) {
        ensureObserver(context);
        return sCachedFrostPct / 100.0f;
    }

    public static int computeWallpaperDimAlpha(int pct) {
        int clean = clamp(pct, 0, 100);
        if (clean >= 100) return 0;
        if (clean <= 0) return 255;
        float s = clean / 100.0f;
        float trans = (float) Math.pow(s, 1.65);
        int keepByte = clamp(Math.round(trans * 255.0f), 0, 255);
        return 255 - keepByte;
    }

    public static int getWallpaperDimAlpha(Context context) {
        ensureObserver(context);
        return computeWallpaperDimAlpha(sCachedWpBrightnessPct);
    }

    public static float getWallpaperBrightnessScale(Context context) {
        return (255 - getWallpaperDimAlpha(context)) / 255.0f;
    }

    public static int getLiquidGlassMode(Context context) {
        ensureObserver(context);
        return sCachedGlassMode;
    }

    public static int getEffectiveIconMode(Context context) {
        ensureObserver(context);
        int raw = sCachedIconModeRaw;
        if (raw == 1 || raw == 2 || raw == 3) {
            return raw;
        }
        if (context != null) {
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return night ? 2 : 1;
        }
        return 1;
    }

    public static boolean isSearchPillEnabled(Context context) {
        ensureObserver(context);
        return sCachedSearchPill != 0;
    }

    public static boolean isGesturePillEnabled(Context context) {
        // Keep Home Screen Dock margin strictly fixed at 62px (23.62dp); iOS Home Indicator
        // auto-hides on the Home Screen and never shifts the Dock upward.
        return false;
    }

    public static boolean isHomeIndicatorEnabled(Context context) {
        ensureObserver(context);
        return sCachedGesturePill != 0;
    }

    public static long getIconCacheSerial() {
        return sIconCacheSerial;
    }

    // =========================================================================
    // Hook 25: Launcher / QuickstepLauncher Workspace & Recents Bar State Sync
    // =========================================================================
    private static boolean shouldHideHomeBarInWorkspace() {
        return sLauncherResumed && sLauncherHasWindowFocus && sInNormalWorkspace;
    }

    public static void onLauncherCreated(Launcher launcher) {
        if (launcher != null) {
            ensureObserver(launcher);
        }
        boolean prevHide = shouldHideHomeBarInWorkspace();
        sInNormalWorkspace = true;
        sRecentsVisible = false;
        sLauncherHasWindowFocus = (launcher != null && launcher.hasWindowFocus());
        if (prevHide != shouldHideHomeBarInWorkspace()) {
            invalidateAllStashedHandles();
        }
        syncLauncherBarState(launcher);
    }

    public static void onLauncherStateChanged(Launcher launcher, BaseState baseState) {
        if (!(baseState instanceof LauncherState)) {
            return;
        }
        LauncherState ls = (LauncherState) baseState;
        boolean prevHide = shouldHideHomeBarInWorkspace();
        sInNormalWorkspace = (ls.ordinal == 0 && !ls.isRecentsViewVisible);
        sRecentsVisible = ls.isRecentsViewVisible;
        if (launcher != null) {
            sLauncherHasWindowFocus = launcher.hasWindowFocus();
        }
        if (prevHide != shouldHideHomeBarInWorkspace()) {
            invalidateAllStashedHandles();
        }
        syncLauncherBarState(launcher);
    }

    public static void onLauncherResumed(Launcher launcher) {
        boolean prevHide = shouldHideHomeBarInWorkspace();
        sLauncherResumed = true;
        if (launcher != null) {
            sLauncherHasWindowFocus = launcher.hasWindowFocus();
        }
        if (prevHide != shouldHideHomeBarInWorkspace()) {
            invalidateAllStashedHandles();
        }
        syncLauncherBarState(launcher);
    }

    public static void onLauncherPaused(Launcher launcher) {
        boolean prevHide = shouldHideHomeBarInWorkspace();
        sLauncherResumed = false;
        sLauncherHasWindowFocus = false;
        if (prevHide != shouldHideHomeBarInWorkspace()) {
            invalidateAllStashedHandles();
        }
        syncLauncherBarState(launcher);
    }

    public static void onLauncherWindowFocusChanged(Launcher launcher, boolean hasFocus) {
        boolean prevHide = shouldHideHomeBarInWorkspace();
        sLauncherHasWindowFocus = hasFocus;
        if (prevHide != shouldHideHomeBarInWorkspace()) {
            invalidateAllStashedHandles();
        }
        syncLauncherBarState(launcher);
    }

    private static void syncLauncherBarState(Context context) {
        if (context == null) {
            return;
        }
        final int target = (!sLauncherResumed || !sLauncherHasWindowFocus)
                ? 0
                : (sRecentsVisible ? 2 : (sInNormalWorkspace ? 1 : 0));
        sDesiredBarState = target;
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            int latest = sDesiredBarState;
            try {
                ContentResolver cr = appCtx.getContentResolver();
                int currentGlobal = Settings.Global.getInt(cr, KEY_LAUNCHER_BAR_STATE, -1);
                if (sLastPublishedBarState.get() == latest && currentGlobal == latest) {
                    return;
                }
                Settings.Global.putInt(cr, KEY_LAUNCHER_BAR_STATE, latest);
                sLastPublishedBarState.set(latest);
            } catch (Throwable ignored) {
                sLastPublishedBarState.set(-1);
            }
        });
    }

    // =========================================================================
    // Hook 0 & Hook 7: StashedHandleView.<init> & draw(Canvas) -> Pre-Attach Fallback Capsule
    // =========================================================================
    public static void onStashedHandleInit(View handleView) {
        if (handleView == null) return;
        ensureObserver(handleView.getContext());
        synchronized (sStashedHandles) {
            for (int i = sStashedHandles.size() - 1; i >= 0; i--) {
                View existing = sStashedHandles.get(i).get();
                if (existing == null || existing == handleView) {
                    sStashedHandles.remove(i);
                }
            }
            sStashedHandles.add(new WeakReference<>(handleView));
        }
    }

    public static void drawIosStashedHandleIfNeeded(View handleView, Canvas canvas) {
        if (handleView == null || canvas == null) {
            return;
        }
        Context ctx = handleView.getContext();
        if (shouldHideHomeBarInWorkspace()
                || !isHomeIndicatorEnabled(ctx)
                || sCachedOverlayHomeBarOk) {
            return;
        }
        int vw = handleView.getWidth();
        int vh = handleView.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        float d = handleView.getResources().getDisplayMetrics().density;
        if (d <= 0f) {
            d = 2.75f;
        }
        float pillW = Math.min(vw * 0.90f, 138.0f * d);
        float pillH = 5.0f * d;
        float bottomInset = 8.0f * d;
        float left = (vw - pillW) * 0.5f;
        float bottom = Math.max(pillH, vh - bottomInset);
        float top = bottom - pillH;
        boolean night = (handleView.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        synchronized (sFallbackHandleRect) {
            sFallbackHandleRect.set(left, top, left + pillW, bottom);
            sFallbackHandlePaint.setColor(night ? 0xD8FFFFFF : 0xBF1C1C1E);
            float r = pillH * 0.5f;
            canvas.drawRoundRect(sFallbackHandleRect, r, r, sFallbackHandlePaint);
        }
    }

    private static void invalidateAllStashedHandles() {
        synchronized (sStashedHandles) {
            Iterator<WeakReference<View>> it = sStashedHandles.iterator();
            while (it.hasNext()) {
                View hv = it.next().get();
                if (hv == null) {
                    it.remove();
                    continue;
                }
                hv.invalidate();
            }
        }
    }

    // =========================================================================
    // Hook 17: LauncherRootView.dispatchDraw(Canvas) -> 0..100% Wallpaper vs 100% Icons Z-Depth
    // =========================================================================
    public static void onLauncherRootPreDispatchDraw(
            View rootView, Object statefulContainer, Canvas canvas) {
        if (rootView == null || canvas == null || !(statefulContainer instanceof Launcher)) {
            return;
        }
        Context ctx = rootView.getContext();
        ensureObserver(ctx);
        trackLauncherRoot(rootView);
        int dimAlpha = computeWallpaperDimAlpha(sCachedWpBrightnessPct);
        sLastDrawnRootDimAlpha = dimAlpha;
        if (dimAlpha > 0) {
            canvas.drawColor(dimAlpha << 24);
        }
    }

    public static void ensureRootDimSynced(View childView) {
        if (childView == null) return;
        int targetAlpha = getWallpaperDimAlpha(childView.getContext());
        if (sLastDrawnRootDimAlpha != targetAlpha) {
            invalidateAllLauncherRoots();
            android.view.ViewParent p = childView.getParent();
            while (p instanceof View) {
                View v = (View) p;
                if ("com.android.launcher3.LauncherRootView".equals(v.getClass().getName())) {
                    v.invalidate();
                    v.postInvalidateOnAnimation();
                    break;
                }
                p = v.getParent();
            }
        }
    }

    private static void trackLauncherRoot(View rootView) {
        synchronized (sLauncherRoots) {
            Iterator<WeakReference<View>> it = sLauncherRoots.iterator();
            while (it.hasNext()) {
                View existing = it.next().get();
                if (existing == null) {
                    it.remove();
                } else if (existing == rootView) {
                    return;
                }
            }
            sLauncherRoots.add(new WeakReference<>(rootView));
        }
    }

    private static void invalidateAllLauncherRoots() {
        synchronized (sLauncherRoots) {
            Iterator<WeakReference<View>> it = sLauncherRoots.iterator();
            while (it.hasNext()) {
                View rv = it.next().get();
                if (rv == null) {
                    it.remove();
                    continue;
                }
                rv.invalidate();
                rv.postInvalidateOnAnimation();
            }
        }
    }

    private static void invalidateAllHotseats() {
        relayoutAndInvalidateAllHotseats(false);
    }

    private static void relayoutAndInvalidateAllHotseats(boolean relayoutDockMargin) {
        synchronized (sHotseats) {
            Iterator<WeakReference<Hotseat>> it = sHotseats.iterator();
            while (it.hasNext()) {
                Hotseat hs = it.next().get();
                if (hs == null) {
                    it.remove();
                    continue;
                }
                if (relayoutDockMargin) {
                    int insetBottom = getInsetBottom(hs);
                    if (hs.mWorkspace != null) {
                        syncWorkspaceCellsLive(hs.mWorkspace, insetBottom);
                    }
                    adjustHotseatLayout(hs, new Rect(0, 0, 0, insetBottom));
                    hs.requestLayout();
                }
                syncSearchPillState(hs);
                hs.invalidate();
                Object tag = hs.getTag(SEARCH_PILL_TAG_KEY);
                if (tag instanceof View) {
                    ((View) tag).invalidate();
                }
            }
        }
    }

    // =========================================================================
    // Uniform Home Screen Vertical Spacing: G_1 == G_2 == G_3 == G
    // =========================================================================
    public static int computeUniformGapPx(Context context, float density, int insetBottomPx) {
        int bottomMargin = Math.round(
                SamuraiDockGlassRenderer.computeBottomMarginPx(context, density, insetBottomPx));
        if (insetBottomPx > Math.round(34f * density)) {
            return Math.max(Math.round(8f * density), bottomMargin - insetBottomPx);
        }
        return bottomMargin;
    }

    private static int measureSingleLineLabelHeightPx(int iconTextSizePx) {
        if (iconTextSizePx <= 0) {
            return 0;
        }
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        try {
            Typeface base = Typeface.create("google-sans-flex", Typeface.NORMAL);
            tp.setTypeface(Typeface.create(base, 500, false));
        } catch (Throwable ignored) {
        }
        tp.setTextSize((float) iconTextSizePx);
        Paint.FontMetrics fm = tp.getFontMetrics();
        Paint.FontMetricsInt fmi = tp.getFontMetricsInt();
        int ceilFloat = (int) Math.ceil(fm.bottom - fm.top);
        int intBox = fmi.bottom - fmi.top;
        return Math.max(ceilFloat, intBox);
    }

    private static void applyUniformWorkspaceSpacing(
            DeviceProfile dp, Context fallbackCtx, Rect insetsOverride) {
        if (dp == null || dp.inv == null || dp.deviceProperties == null
                || dp.workspaceProfile == null || dp.hotseatProfile == null) {
            return;
        }
        if (dp.deviceProperties.isVerticalBarLayout()
                || dp.deviceProperties.isLandscape
                || dp.deviceProperties.isTwoPanels) {
            return;
        }
        int screenH = dp.deviceProperties.heightPx;
        int numRows = dp.inv.numRows;
        if (screenH <= 0 || numRows < 2) {
            return;
        }

        Context ctx = (dp.displayInfo != null && dp.displayInfo.context != null)
                ? dp.displayInfo.context : fallbackCtx;
        if (ctx != null) {
            ensureObserver(ctx);
        }
        float density;
        if (ctx != null && ctx.getResources() != null) {
            density = ctx.getResources().getDisplayMetrics().density;
        } else if (dp.displayInfo != null && dp.displayInfo.densityDpi > 0) {
            density = dp.displayInfo.densityDpi / 160f;
        } else {
            density = Resources.getSystem().getDisplayMetrics().density;
        }

        Rect srcInsets = insetsOverride != null ? insetsOverride : dp.deviceProperties.insets;
        int insetTop = srcInsets != null ? Math.max(0, srcInsets.top) : 0;
        int insetBottom = srcInsets != null ? Math.max(0, srcInsets.bottom) : 0;

        int bottomMargin = Math.round(
                SamuraiDockGlassRenderer.computeBottomMarginPx(ctx, density, insetBottom));
        int uniformGap = computeUniformGapPx(ctx, density, insetBottom);
        int dockH = Math.round(SamuraiDockGlassRenderer.DOCK_HEIGHT_DP * density);
        int pillH = Math.round(SamuraiSearchPillView.PILL_HEIGHT_DP * density);
        int desiredHotseatH = bottomMargin + dockH + uniformGap + pillH;

        dp.hotseatProfile.barSizePx = Math.max(
                dp.hotseatProfile.cellHeightPx, desiredHotseatH - insetBottom);

        WorkspaceProfile wp = dp.workspaceProfile;
        int labelH = wp.isItemsLabelHidden ? 0 : measureSingleLineLabelHeightPx(wp.iconTextSizePx);
        int drawablePad = wp.isItemsLabelHidden ? 0 : Math.max(0, wp.iconDrawablePaddingPx);
        int contentH = wp.iconSizePx + drawablePad + labelH;
        if (contentH <= 0) {
            return;
        }

        int pillScreenTop = screenH - desiredHotseatH;
        int targetGridBottom = pillScreenTop - uniformGap;

        int minGutterY = Math.round(4f * density);
        int desiredTopClearance = Math.round(14.5f * density);
        int rawSpan = targetGridBottom - (insetTop + desiredTopClearance);
        int minRequiredSpan = numRows * contentH + (numRows - 1) * minGutterY;
        if (rawSpan < minRequiredSpan) {
            desiredTopClearance = Math.round(4f * density);
            rawSpan = targetGridBottom - (insetTop + desiredTopClearance);
            if (rawSpan < minRequiredSpan) {
                return;
            }
        }

        int gutterY = (rawSpan - numRows * contentH) / (numRows - 1);
        int exactGridHeight = numRows * contentH + (numRows - 1) * gutterY;
        int gridScreenTop = targetGridBottom - exactGridHeight;

        int totalTopFromInset = Math.max(0, gridScreenTop - insetTop);
        int totalBottomFromInset = Math.max(0, (screenH - insetBottom) - targetGridBottom);

        Rect clPad = wp.cellLayoutPaddingPx != null ? wp.cellLayoutPaddingPx : new Rect();
        Rect wsPad = wp.workspacePadding != null ? wp.workspacePadding : new Rect();
        int baseClPadV = Math.max(clPad.left, clPad.right);
        if (baseClPadV <= 0) {
            baseClPadV = Math.round(10.5f * density);
        }
        int clPadTop = Math.min(baseClPadV, totalTopFromInset);
        int clPadBottom = Math.min(baseClPadV, totalBottomFromInset);
        int wsPadTop = totalTopFromInset - clPadTop;
        int wsPadBottom = totalBottomFromInset - clPadBottom;

        clPad.set(clPad.left, clPadTop, clPad.right, clPadBottom);
        wsPad.set(wsPad.left, wsPadTop, wsPad.right, wsPadBottom);
        wp.cellLayoutPaddingPx = clPad;
        wp.workspacePadding = wsPad;
        wp.workspaceTopPadding = wsPadTop;
        wp.workspaceBottomPadding = wsPadBottom;

        if (wp.cellLayoutBorderSpacePx != null) {
            wp.cellLayoutBorderSpacePx.y = gutterY;
        } else {
            wp.cellLayoutBorderSpacePx = new Point(0, gutterY);
        }
        wp.cellHeightPx = contentH;
        if (wp.cellSize != null) {
            wp.cellSize.y = contentH;
        } else {
            wp.cellSize = new Point(wp.cellWidthPx, contentH);
        }
        wp.cellYPaddingPx = 0;
        wp.iconCenterVertically = false;
        if (!wp.isItemsLabelHidden) {
            wp.maxIconTextLineCount = 1;
        }
        wp.cellLayoutHeightSpecification = exactGridHeight + clPadTop + clPadBottom;
        wp.extraSpace = 0;
        wp.maxEmptySpace = 0;
    }

    public static void onDeviceProfileCreated(DeviceProfile dp) {
        try {
            applyUniformWorkspaceSpacing(dp, null, null);
        } catch (Throwable ignored) {
        }
    }

    public static void onWorkspacePreSetInsets(Workspace ws, Rect insets) {
        if (ws == null) return;
        try {
            ActivityContext act = ws.mLauncher;
            DeviceProfile dp = act != null ? act.getDeviceProfile() : null;
            if (dp == null) return;
            applyUniformWorkspaceSpacing(dp, ws.getContext(), insets);
            WorkspaceProfile wp = dp.workspaceProfile;
            if (wp == null || wp.cellLayoutBorderSpacePx == null) return;
            int count = ws.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = ws.getChildAt(i);
                if (child instanceof CellLayout) {
                    CellLayout cl = (CellLayout) child;
                    if (cl.mContainerType == 0
                            && (cl.mBorderSpace == null
                                    || !cl.mBorderSpace.equals(wp.cellLayoutBorderSpacePx))) {
                        cl.resetCellSizeInternal(dp);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void syncWorkspaceCellsLive(Workspace ws, int insetBottomPx) {
        if (ws == null) return;
        try {
            ActivityContext act = ws.mLauncher;
            DeviceProfile dp = act != null ? act.getDeviceProfile() : null;
            if (dp == null || dp.workspaceProfile == null) return;
            Rect baseInsets = dp.deviceProperties != null ? dp.deviceProperties.insets : null;
            Rect liveInsets = new Rect(
                    baseInsets != null ? baseInsets.left : 0,
                    baseInsets != null ? baseInsets.top : 0,
                    baseInsets != null ? baseInsets.right : 0,
                    insetBottomPx);
            applyUniformWorkspaceSpacing(dp, ws.getContext(), liveInsets);
            WorkspaceProfile wp = dp.workspaceProfile;
            if (wp.workspacePadding != null) {
                ws.setPadding(
                        wp.workspacePadding.left,
                        wp.workspacePadding.top,
                        wp.workspacePadding.right,
                        wp.workspacePadding.bottom);
            }
            int count = ws.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = ws.getChildAt(i);
                if (child instanceof CellLayout) {
                    CellLayout cl = (CellLayout) child;
                    if (cl.mContainerType == 0) {
                        cl.resetCellSizeInternal(dp);
                        if (wp.cellLayoutPaddingPx != null) {
                            cl.setPadding(
                                    wp.cellLayoutPaddingPx.left,
                                    wp.cellLayoutPaddingPx.top,
                                    wp.cellLayoutPaddingPx.right,
                                    wp.cellLayoutPaddingPx.bottom);
                        }
                        cl.requestLayout();
                    }
                }
            }
            ws.requestLayout();
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Hook 1: QsbWidgetFactory.createView(ViewGroup, int) -> 0x0 GONE Dummy View
    // =========================================================================
    public static View createHiddenQsbView(ViewGroup parent, int viewId) {
        Context ctx = parent.getContext();
        ensureObserver(ctx);
        View dummy = new View(ctx);
        if (viewId != 0) {
            dummy.setId(viewId);
        }
        dummy.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
        dummy.setVisibility(View.GONE);
        return dummy;
    }

    // =========================================================================
    // Hook 2: HotseatSpecsProvider.getCalculatedSpec -> Zero QSB Space + Reserve Dock/Pill Margin
    // =========================================================================
    public static void adjustCalculatedHotseatSpec(CalculatedHotseatSpec spec) {
        if (spec == null) return;
        spec.hotseatQsbSpace = 0;
        float density = Resources.getSystem().getDisplayMetrics().density;
        int minEdge = Math.round(78f * density);
        if (spec.edgePadding < minEdge) {
            spec.edgePadding = minEdge;
        }
    }

    // =========================================================================
    // Hook 3: Hotseat.<init>, setInsets, onMeasure, onLayout + Workspace.onScrollChanged
    // =========================================================================
    public static void onHotseatInit(Hotseat hotseat) {
        if (hotseat == null) return;
        Context ctx = hotseat.getContext();
        ensureObserver(ctx);
        SamuraiWallpaperApplier.applyDefaultIfNeeded(ctx);
        SamuraiWallpaperBlurCache.getOrCreate(ctx);

        if (hotseat.mQsb != null) {
            hotseat.mQsb.setVisibility(View.GONE);
        }
        hotseat.setClipChildren(false);
        hotseat.setClipToPadding(false);

        SamuraiSearchPillView pill = new SamuraiSearchPillView(ctx, hotseat);
        hotseat.setTag(SEARCH_PILL_TAG_KEY, pill);
        hotseat.addView(pill);
        syncSearchPillState(hotseat);

        synchronized (sHotseats) {
            for (int i = sHotseats.size() - 1; i >= 0; i--) {
                Hotseat existing = sHotseats.get(i).get();
                if (existing == null || existing == hotseat) {
                    sHotseats.remove(i);
                }
            }
            sHotseats.add(new WeakReference<>(hotseat));
        }

        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Executors.UI_HELPER_EXECUTOR.execute(() -> ensureControlCenterServiceRunning(appCtx));
    }

    public static void adjustHotseatLayout(Hotseat hotseat, Rect insets) {
        if (hotseat == null) return;
        if (hotseat.mQsb != null) {
            hotseat.mQsb.setVisibility(View.GONE);
        }
        int insetBottom = insets != null ? insets.bottom : 0;
        hotseat.setTag(INSET_BOTTOM_TAG_KEY, insetBottom);

        ActivityContext act = hotseat.mActivity;
        if (act == null) return;
        DeviceProfile dp = act.getDeviceProfile();
        if (dp == null || dp.deviceProperties == null || dp.hotseatProfile == null) return;
        if (dp.deviceProperties.isVerticalBarLayout()) {
            Object tag = hotseat.getTag(SEARCH_PILL_TAG_KEY);
            if (tag instanceof View) {
                ((View) tag).setVisibility(View.GONE);
            }
            return;
        }

        DisplayMetrics dm = hotseat.getResources().getDisplayMetrics();
        float density = dm.density;
        int cellH = dp.hotseatProfile.cellHeightPx;
        if (cellH <= 0) return;

        Context ctx = hotseat.getContext();
        int dockH = Math.round(SamuraiDockGlassRenderer.DOCK_HEIGHT_DP * density);
        int bottomMargin = Math.round(SamuraiDockGlassRenderer.computeBottomMarginPx(
                ctx, density, insetBottom));
        int uniformGap = computeUniformGapPx(ctx, density, insetBottom);
        int pillH = Math.round(SamuraiSearchPillView.PILL_HEIGHT_DP * density);

        ViewGroup.LayoutParams lp = hotseat.getLayoutParams();
        int desiredH = bottomMargin + dockH + uniformGap + pillH;
        if (lp != null && lp.height != desiredH) {
            lp.height = desiredH;
            hotseat.setLayoutParams(lp);
        }

        // Concentrically center mShortcutsAndWidgets inside [dockTop .. dockBottom]
        int padBottom = Math.max(0, bottomMargin + (dockH - cellH) / 2);
        int padTop = Math.max(0, desiredH - cellH - padBottom);
        int minSidePad = Math.round(21.5f * density);
        int padLeft = Math.max(hotseat.getPaddingLeft(), minSidePad);
        int padRight = Math.max(hotseat.getPaddingRight(), minSidePad);
        hotseat.setPadding(padLeft, padTop, padRight, padBottom);
        syncSearchPillState(hotseat);
    }

    public static void onHotseatMeasure(Hotseat hotseat) {
        if (hotseat == null) return;
        Object tag = hotseat.getTag(SEARCH_PILL_TAG_KEY);
        if (!(tag instanceof View)) return;
        View pill = (View) tag;
        float density = hotseat.getResources().getDisplayMetrics().density;
        int pw = Math.round(SamuraiSearchPillView.PILL_WIDTH_DP * density);
        int ph = Math.round(SamuraiSearchPillView.PILL_HEIGHT_DP * density);
        pill.measure(
                View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
    }

    public static void onHotseatLayout(Hotseat hotseat, int l, int t, int r, int b) {
        if (hotseat == null) return;
        if (hotseat.mQsb != null) {
            hotseat.mQsb.setVisibility(View.GONE);
            hotseat.mQsb.layout(0, 0, 0, 0);
        }
        Object tag = hotseat.getTag(SEARCH_PILL_TAG_KEY);
        if (!(tag instanceof View)) return;
        View pill = (View) tag;

        int w = r - l;
        int h = b - t;
        float density = hotseat.getResources().getDisplayMetrics().density;
        int pw = pill.getMeasuredWidth() > 0 ? pill.getMeasuredWidth()
                : Math.round(SamuraiSearchPillView.PILL_WIDTH_DP * density);
        int ph = pill.getMeasuredHeight() > 0 ? pill.getMeasuredHeight()
                : Math.round(SamuraiSearchPillView.PILL_HEIGHT_DP * density);

        Context ctx = hotseat.getContext();
        int insetBottom = getInsetBottom(hotseat);
        int bottomMargin = Math.round(SamuraiDockGlassRenderer.computeBottomMarginPx(
                ctx, density, insetBottom));
        int uniformGap = computeUniformGapPx(ctx, density, insetBottom);
        int dockH = Math.round(SamuraiDockGlassRenderer.DOCK_HEIGHT_DP * density);
        int dockTop = h - bottomMargin - dockH;
        int pillBottom = dockTop - uniformGap;
        int pillTop = Math.max(0, pillBottom - ph);
        int pillLeft = (w - pw) / 2;
        pill.layout(pillLeft, pillTop, pillLeft + pw, pillTop + ph);
    }

    public static void onWorkspaceScrolled(Workspace ws) {
        if (ws != null) {
            suppressStockPageIndicator(ws);
        }
        synchronized (sHotseats) {
            Iterator<WeakReference<Hotseat>> it = sHotseats.iterator();
            while (it.hasNext()) {
                Hotseat hs = it.next().get();
                if (hs == null) {
                    it.remove();
                    continue;
                }
                if (ws == null || hs.mWorkspace == ws) {
                    syncSearchPillState(hs);
                    hs.invalidate();
                    Object tag = hs.getTag(SEARCH_PILL_TAG_KEY);
                    if (tag instanceof View) {
                        ((View) tag).invalidate();
                    }
                }
            }
        }
    }

    private static void suppressStockPageIndicator(Workspace ws) {
        if (ws == null) return;
        try {
            View pi = ws.getPageIndicator();
            if (pi != null) {
                if (pi.getAlpha() != 0f) {
                    pi.setAlpha(0f);
                }
                if (pi.getVisibility() == View.VISIBLE) {
                    pi.setVisibility(View.INVISIBLE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Hook 4: CellLayout.dispatchDraw -> 90Hz Liquid Glass Dock + Lockstep Pill Sync
    // =========================================================================
    public static void onCellLayoutPreDispatchDraw(CellLayout cellLayout, Canvas canvas) {
        if (!(cellLayout instanceof Hotseat)) {
            return;
        }
        Hotseat hotseat = (Hotseat) cellLayout;
        ActivityContext act = hotseat.mActivity;
        if (act != null) {
            DeviceProfile dp = act.getDeviceProfile();
            if (dp != null && dp.deviceProperties != null && dp.deviceProperties.isVerticalBarLayout()) {
                return;
            }
        }
        if (!SamuraiWallpaperApplier.isConfirmedApplied()) {
            SamuraiWallpaperApplier.applyDefaultIfNeeded(hotseat.getContext());
        }
        syncSearchPillState(hotseat);
        SamuraiDockGlassRenderer.drawDock(hotseat, canvas, getInsetBottom(hotseat));
    }

    public static void onMultiValueAlphaApplied(Object target) {
        if (!(target instanceof View)) {
            return;
        }
        View v = (View) target;
        ViewParent parent = v.getParent();
        if (parent instanceof Hotseat) {
            Hotseat hs = (Hotseat) parent;
            syncSearchPillState(hs);
            hs.invalidate();
            Object tag = hs.getTag(SEARCH_PILL_TAG_KEY);
            if (tag instanceof View) {
                ((View) tag).invalidate();
            }
        }
    }

    private static void syncSearchPillState(Hotseat hotseat) {
        Workspace ws = hotseat.mWorkspace;
        if (ws != null) {
            suppressStockPageIndicator(ws);
        }
        Object tag = hotseat.getTag(SEARCH_PILL_TAG_KEY);
        if (!(tag instanceof View)) return;
        View pill = (View) tag;

        int pageCount = ws != null ? ws.getChildCount() : 1;
        boolean searchOn = isSearchPillEnabled(hotseat.getContext());
        if (!searchOn && pageCount <= 1) {
            if (pill.getVisibility() != View.GONE) {
                pill.setVisibility(View.GONE);
            }
            return;
        }
        ViewGroup sw = hotseat.mShortcutsAndWidgets;
        if (sw == null || sw.getVisibility() != View.VISIBLE || sw.getAlpha() < 0.02f) {
            if (pill.getVisibility() != View.INVISIBLE) {
                pill.setVisibility(View.INVISIBLE);
            }
            return;
        }
        if (pill.getVisibility() != View.VISIBLE) {
            pill.setVisibility(View.VISIBLE);
        }
        pill.setAlpha(sw.getAlpha());
        pill.setTranslationX(sw.getTranslationX());
    }

    private static int getInsetBottom(Hotseat hotseat) {
        Object o = hotseat.getTag(INSET_BOTTOM_TAG_KEY);
        return (o instanceof Integer) ? (Integer) o : 0;
    }

    public static void triggerLauncherSearch(Context context) {
        try {
            Context act = ActivityContext.lookupContextNoThrow(context);
            if (act instanceof Launcher) {
                ((Launcher) act).toggleAllApps(true, true);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent(Intent.ACTION_ALL_APPS);
            intent.setPackage(context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Hook 5 & 6: 1:1 Bundled iOS 26 Icon Assets + Clean Multi-Plane Fallback + Live Reload
    // =========================================================================
    public static void onIconProviderCreated(LauncherIconProviderImpl provider) {
        if (provider == null) return;
        sIconProviderRef = new WeakReference<>(provider);
        ensureObserver(provider.mContext);
    }

    public static PersistedItemState appendIconSystemState(
            LauncherIconProviderImpl provider, PersistedItemState state) {
        if (state == null) return null;
        Context ctx = provider != null ? provider.mContext : null;
        ensureObserver(ctx);
        int effMode = getEffectiveIconMode(ctx);
        String tag;
        if (effMode == 2) {
            tag = "samurai_ios26_v165_dark";
        } else if (effMode == 3) {
            tag = "samurai_ios26_v165_clear_" + sCachedWallpaperSeq;
        } else {
            tag = "samurai_ios26_v165_default";
        }
        return state.withAdditionalValues(tag);
    }

    public static Drawable transformIcon(
            IconProvider provider,
            PackageItemInfo itemInfo,
            ApplicationInfo appInfo,
            int iconDpi,
            Drawable rawIcon) {
        if (rawIcon == null || provider == null || provider.mContext == null) {
            return rawIcon;
        }
        try {
            Context ctx = provider.mContext;
            ensureObserver(ctx);
            int effMode = getEffectiveIconMode(ctx);

            String pkg = null;
            if (itemInfo != null && itemInfo.packageName != null) {
                pkg = itemInfo.packageName;
            } else if (appInfo != null) {
                pkg = appInfo.packageName;
            }

            SamuraiWallpaperBlurCache.Snapshot wp = SamuraiWallpaperBlurCache.getOrCreate(ctx);
            Resources res = ctx.getResources();

            // Priority 1: Bundled 576x576 lossless WebP 1:1 iOS 26 / App Store assets
            // (includes com.realme.samurai.iossettings -> com.android.settings 3-plane brushed pewter gear)
            Drawable bundled = SamuraiIconSynthesizer.tryLoadBundledAssetIcon(ctx, pkg, effMode, wp.avgTintRgb);
            if (bundled != null) {
                return bundled;
            }

            if (effMode == 1 && rawIcon instanceof ClockDrawableWrapper) {
                return rawIcon;
            }

            // Priority 2: Clean Plane-0 + Plane-1 optical synthesizer for Android-only apps
            Drawable universal = SamuraiIconSynthesizer.synthesizeUniversal(res, rawIcon, effMode, wp.avgTintRgb);
            return universal != null ? universal : rawIcon;
        } catch (Throwable ignored) {
            return rawIcon;
        }
    }

    // =========================================================================
    // Hook 8: iOS 26 Left-Right Home Screen Auto-Population (Boot + Package Install/Update)
    // =========================================================================
    public static void onLoaderTaskComplete(LoaderTask task) {
        if (task == null || task.mStopped || task.mModel == null) {
            return;
        }
        try {
            task.mModel.enqueueModelUpdateTask(new SamuraiPopulateWorkspaceTask());
        } catch (Throwable ignored) {
        }
    }

    public static void onPackageUpdatedTaskComplete(
            ModelTaskController controller,
            BgDataModel dataModel,
            AllAppsList allAppsList) {
        SamuraiPopulateWorkspaceTask.populateMissingApps(controller, dataModel, allAppsList);
    }

    private static void triggerLiveIconReload() {
        WeakReference<LauncherIconProviderImpl> ref = sIconProviderRef;
        final LauncherIconProviderImpl provider = ref != null ? ref.get() : null;
        if (provider == null) return;
        try {
            Executors.MODEL_EXECUTOR.execute(() -> {
                try {
                    provider.updateSystemState();
                    if (provider.iconCacheProvider != null) {
                        IconCache ic = (IconCache) provider.iconCacheProvider.get();
                        if (ic != null) {
                            synchronized (ic) {
                                ic.assertWorkerThread();
                                if (ic.cache != null) {
                                    ic.cache.clear();
                                }
                            }
                        }
                    }
                    if (provider.modelProvider != null) {
                        LauncherModel model = (LauncherModel) provider.modelProvider.get();
                        if (model != null) {
                            model.reloadIfActive("SamuraiIos26IconMode");
                        }
                    }
                    Executors.MAIN_EXECUTOR.execute(() -> {
                        SamuraiDockGlassRenderer.invalidateBloomCache();
                        invalidateAllHotseats();
                    });
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Hook 19: StatusBarTouchController -> Full iOS 26 Left Notification Center + Right Control Center
    // =========================================================================
    private static final long sLauncherSessionId =
            Math.max(1L, android.os.SystemClock.elapsedRealtimeNanos() & 0x7FFFFFFFFFFFFFFFL);
    private static final java.util.concurrent.atomic.AtomicLong sSwipeSeq =
            new java.util.concurrent.atomic.AtomicLong(
                    (Math.max(1L, android.os.SystemClock.uptimeMillis()) & 0x7FFFFFFFFFFFL) << 16);
    private static volatile long sSwipeDownTime = -1L;
    private static volatile int sSwipePointerId = MotionEvent.INVALID_POINTER_ID;
    private static volatile boolean sSwipeSettledForGesture = false;
    private static volatile float sSwipeStartX = 0f;
    private static volatile float sSwipeStartY = 0f;
    private static volatile String sSwipeMode = "control_center";
    private static volatile float sLastSentProgress = -1f;
    private static volatile long sLastSentUptimeMs = 0L;
    private static volatile float sLastMoveY = 0f;
    private static volatile long sLastMoveTimeMs = 0L;
    private static volatile float sVelocityY = 0f;

    public static boolean handleHomeStatusBarSwipe(
            Context launcher, PointF downEvent, MotionEvent event) {
        if (launcher == null || event == null) {
            return false;
        }
        ensureObserver(launcher);
        DisplayMetrics dm = launcher.getResources().getDisplayMetrics();
        int screenW = dm != null ? dm.widthPixels : 1080;
        float density = dm != null && dm.density > 0f ? dm.density : 2.75f;
        int fullScreenH = 0;
        try {
            WindowManager wm = launcher.getSystemService(WindowManager.class);
            if (wm != null) {
                fullScreenH = wm.getCurrentWindowMetrics().getBounds().height();
            }
        } catch (Throwable ignored) {
        }
        if (fullScreenH <= 0) {
            fullScreenH = (dm != null && dm.heightPixels > 0) ? dm.heightPixels : 2400;
        }
        final float dragRange = Math.max(640f, (float) fullScreenH);

        long downTime = event.getDownTime();
        int action = event.getActionMasked();
        final Context appCtx = launcher.getApplicationContext() != null
                ? launcher.getApplicationContext() : launcher;

        if (action == MotionEvent.ACTION_DOWN || downTime != sSwipeDownTime) {
            sSwipeDownTime = downTime;
            sSwipePointerId = event.getPointerId(0);
            sSwipeSettledForGesture = false;
            sSwipeStartX = (downEvent != null && downEvent.x > 0f) ? downEvent.x : event.getRawX();
            sSwipeStartY = (downEvent != null && downEvent.y > 0f) ? downEvent.y : event.getRawY();
            sSwipeMode = (sSwipeStartX < screenW * 0.50f) ? "notifications" : "control_center";
            sLastSentProgress = -1f;
            sLastSentUptimeMs = 0L;
            sLastMoveY = event.getRawY();
            sLastMoveTimeMs = event.getEventTime();
            sVelocityY = 0f;
        }

        if (sSwipeSettledForGesture) {
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            int pIdx = event.findPointerIndex(sSwipePointerId);
            if (pIdx < 0) {
                return true;
            }
            long nowMs = event.getEventTime();
            long dt = nowMs - sLastMoveTimeMs;
            float rawY = event.getRawY(pIdx);
            if (dt > 0L) {
                float instVy = (rawY - sLastMoveY) * 1000f / dt;
                sVelocityY = sVelocityY * 0.45f + instVy * 0.55f;
            }
            sLastMoveY = rawY;
            sLastMoveTimeMs = nowMs;

            float dy = rawY - sSwipeStartY;
            if (dy > 4f * density) {
                final float progress = Math.max(0f, Math.min(1f, dy / dragRange));
                final float minStep = 1f / dragRange;
                final float fastStep = (8f * density) / dragRange;
                final float delta = Math.abs(progress - sLastSentProgress);
                if ((nowMs - sLastSentUptimeMs >= 11L && delta >= minStep) || delta >= fastStep) {
                    sLastSentUptimeMs = nowMs;
                    sLastSentProgress = progress;
                    final String mode = sSwipeMode;
                    final float velY = sVelocityY;
                    final long seq = sSwipeSeq.incrementAndGet();
                    Executors.UI_HELPER_EXECUTOR.execute(
                            () -> dispatchHomeSwipeToOverlay(appCtx, mode, progress, velY, false, true, seq));
                }
            }
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP
                        && event.getPointerId(event.getActionIndex()) == sSwipePointerId)) {
            int pIdx = (action == MotionEvent.ACTION_POINTER_UP)
                    ? event.getActionIndex()
                    : event.findPointerIndex(sSwipePointerId);
            float rawEndY = (pIdx >= 0 && pIdx < event.getPointerCount()) ? event.getRawY(pIdx) : sLastMoveY;
            float dy = rawEndY - sSwipeStartY;
            final float rawProgress = Math.max(0f, Math.min(1f, dy / dragRange));
            final float effectiveProgress = Math.max(rawProgress, Math.max(0f, sLastSentProgress));
            final boolean shouldOpen;
            if (action == MotionEvent.ACTION_CANCEL) {
                // Do not snap blank when window focus / Quickstep cancels mid-pull downward!
                shouldOpen = sVelocityY >= -120f * density
                        && (effectiveProgress >= 0.24f || sVelocityY >= 520f * density);
            } else {
                shouldOpen = effectiveProgress >= 0.24f
                        || sVelocityY >= 650f * density;
            }
            final String mode = sSwipeMode;
            final float velY = sVelocityY;
            final long seq = sSwipeSeq.incrementAndGet();
            sSwipeSettledForGesture = true;
            sSwipePointerId = MotionEvent.INVALID_POINTER_ID;
            if (action != MotionEvent.ACTION_POINTER_UP) {
                sSwipeDownTime = -1L;
            }
            Executors.UI_HELPER_EXECUTOR.execute(
                    () -> dispatchHomeSwipeToOverlay(appCtx, mode, effectiveProgress, velY, true, shouldOpen, seq));
        }

        // Always return true so stock Android SystemUiProxy shade NEVER opens or glitches!
        return true;
    }

    private static void ensureControlCenterServiceRunning(Context ctx) {
        if (ctx == null) return;
        try {
            Intent svc = new Intent("com.realme.samurai.controlcenter.ACTION_ENSURE_RUNNING");
            svc.setClassName(
                    "com.realme.samurai.controlcenter",
                    "com.realme.samurai.controlcenter.service.ControlCenterOverlayService");
            ctx.startService(svc);
        } catch (Throwable ignored) {
        }
    }

    private static void dispatchHomeSwipeToOverlay(
            Context ctx,
            String mode,
            float progress,
            float velocityY,
            boolean finished,
            boolean shouldOpen,
            long seq) {
        if (ctx == null) return;
        try {
            Intent svc = new Intent("com.realme.samurai.controlcenter.ACTION_HOME_SWIPE");
            svc.setClassName(
                    "com.realme.samurai.controlcenter",
                    "com.realme.samurai.controlcenter.service.ControlCenterOverlayService");
            svc.putExtra("mode", mode);
            svc.putExtra("progress", progress);
            svc.putExtra("velocity_y", velocityY);
            svc.putExtra("finished", finished);
            svc.putExtra("should_open", shouldOpen);
            svc.putExtra("seq", seq);
            svc.putExtra("session_id", sLauncherSessionId);
            ctx.startService(svc);
            return;
        } catch (Throwable ignored) {
        }
        if (finished && shouldOpen) {
            try {
                String fallbackAction = "notifications".equals(mode)
                        ? "com.realme.samurai.controlcenter.ACTION_OPEN_NOTIFICATIONS"
                        : "com.realme.samurai.controlcenter.ACTION_OPEN";
                Intent bcast = new Intent(fallbackAction);
                bcast.setPackage("com.realme.samurai.controlcenter");
                bcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                ctx.sendBroadcast(bcast);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
