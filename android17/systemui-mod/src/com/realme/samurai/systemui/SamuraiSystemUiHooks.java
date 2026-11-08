package com.realme.samurai.systemui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Point;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Static Clean-Room Entry-Point Bridge (`SamuraiSystemUiHooks`) wired into stock Android 17
 * (`CP3A.260905.010`) `SystemUI` smali classes.
 *
 * SAFETY GUARANTEES:
 * - Every public static hook is guarded by `try { ... } catch (Throwable ignored)` so SystemUI
 *   can never crash even across future runtime/state variations.
 * - Live `SamuraiGlassPrefs.Snapshot` observer supports 0-reboot runtime toggling of Full OS Mode,
 *   Lock Screen, Control Center, Dynamic Island, Clock Style, Refraction, Frost, and Glass Mode.
 */
public final class SamuraiSystemUiHooks {

    private static final int KEYGUARD_ROOT_VIEW_ID = 0x7f0a045e;
    private static final int DEFAULT_UDFPS_X_1080 = 540;
    private static final int DEFAULT_UDFPS_Y_2400 = 2156;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object FORCE_OPEN_TOKEN = new Object();

    private static volatile Context sAppContext;
    private static volatile SamuraiGlassPrefs.Snapshot sPrefs = new SamuraiGlassPrefs.Snapshot();
    private static ContentObserver sPrefsObserver;

    private static FrameLayout sShadeWindowView;
    private static IosShadeOverlayHost sOverlayHost;
    private static FrameLayout sStatusBarView;
    private static DynamicIslandView sDynamicIslandView;
    private static DynamicIslandController sIslandController;
    private static IosVolumeHudView sVolumeHud;
    private static IosPowerMenuView sPowerMenu;

    // Cached reflection handles for NotificationShadeWindowControllerImpl.setForcePluginOpen
    private static Object sShadeWindowController;
    private static Method sSetForcePluginOpenMethod;
    private static boolean sLastForceOpenApplied = false;

    // Live Keyguard & Doze state
    private static volatile boolean sKeyguardShowing = false;
    private static volatile boolean sKeyguardOccluded = false;
    private static volatile boolean sKeyguardGoingAway = false;
    private static volatile boolean sCanDismissLockScreen = false;
    private static volatile float sDozeAmount = 0f;
    private static boolean sLastHideStockLockChrome = false;

    // Cached reflection handles for KeyguardStateControllerImpl
    private static Field sKgShowingField;
    private static Field sKgOccludedField;
    private static Field sKgGoingAwayField;
    private static Field sKgCanDismissField;

    // Cached AuthController & Goodix G3 UDFPS sensor location
    private static Object sLastAuthController;
    private static Method sGetUdfpsLocationMethod;
    private static volatile Point sUdfpsCenter;

    // Cached VolumeDialogControllerImpl reflection handles
    private static Field sVolContextField;
    private static Field sVolAudioField;

    // Cached GlobalActionsDialogLite reflection handles
    private static Field sGaContextField;
    private static Field sGaWmFuncsField;

    // Cached Jetpack Compose MutableState bridge for 0-latency SceneContainer recomposition
    private static volatile Object sComposeEpochState;
    private static Method sMutableStateGetValue;
    private static Method sMutableStateSetValue;
    private static int sComposeEpoch = 0;

    private SamuraiSystemUiHooks() {}

    // =========================================================================
    // Core Initialization & Live Settings Observer
    // =========================================================================

    private static void ensureCoreInitialized(Context context) {
        if (context == null) return;
        if (sAppContext == null) {
            Context app = context.getApplicationContext();
            sAppContext = (app != null) ? app : context;
            sPrefs = SamuraiGlassPrefs.read(sAppContext, sPrefs);
            SystemUiGlassHelper.ensureTorchMonitor(sAppContext);
        }
        if (sIslandController == null) {
            sIslandController = new DynamicIslandController(sAppContext);
        }
        if (sPrefsObserver == null) {
            sPrefsObserver = SamuraiGlassPrefs.registerObserver(
                    sAppContext, SamuraiSystemUiHooks::onPrefsChangedMainThread);
        }
    }

    private static void onPrefsChangedMainThread() {
        try {
            if (sAppContext == null) return;
            sPrefs = SamuraiGlassPrefs.read(sAppContext, sPrefs);
            SamuraiAgslEngine.invalidateWallpaperCache();

            if (!sPrefs.fullOsMode) {
                if (sIslandController != null) {
                    sIslandController.setExpanded(false);
                }
                if (sOverlayHost != null) {
                    sOverlayHost.collapseControlCenter(false);
                }
                if (sVolumeHud != null) {
                    sVolumeHud.hideHud();
                }
                if (sPowerMenu != null) {
                    sPowerMenu.dismissMenu(false);
                }
            } else if (!sPrefs.dynamicIslandEnabled && sIslandController != null) {
                sIslandController.setExpanded(false);
            }

            if (sOverlayHost != null) {
                sOverlayHost.updatePrefs(sPrefs);
            }
            if (sDynamicIslandView != null) {
                sDynamicIslandView.updatePrefs(sPrefs);
            }
            syncKeyguardOverlayAndStockChrome(true);
            notifyOverlayTouchabilityChanged();
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 1. NotificationShadeWindowView & ShadeTouchableRegionManager Hooks
    // =========================================================================

    public static void onShadeWindowAttached(FrameLayout shadeWindowView) {
        try {
            if (shadeWindowView == null) return;
            Context ctx = shadeWindowView.getContext();
            ensureCoreInitialized(ctx);

            sShadeWindowView = shadeWindowView;
            shadeWindowView.setClipChildren(false);
            shadeWindowView.setClipToPadding(false);

            if (sOverlayHost == null || sOverlayHost.getContext() != ctx) {
                if (sOverlayHost != null && sOverlayHost.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sOverlayHost.getParent()).removeView(sOverlayHost);
                }
                sOverlayHost = new IosShadeOverlayHost(ctx, sIslandController, sPrefs);
            } else if (sOverlayHost.getParent() != shadeWindowView) {
                if (sOverlayHost.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sOverlayHost.getParent()).removeView(sOverlayHost);
                }
            }

            if (sOverlayHost.getParent() != shadeWindowView) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                shadeWindowView.addView(sOverlayHost, lp);
            }
            shadeWindowView.bringChildToFront(sOverlayHost);

            resolveShadeControllersFromView(shadeWindowView);
            syncKeyguardOverlayAndStockChrome(true);
            notifyOverlayTouchabilityChanged();
        } catch (Throwable ignored) {
        }
    }

    public static boolean onShadeWindowDispatchTouch(FrameLayout shadeWindowView, MotionEvent ev) {
        try {
            if (shadeWindowView == null || ev == null) return false;
            if (sOverlayHost == null || sOverlayHost.getParent() != shadeWindowView) {
                onShadeWindowAttached(shadeWindowView);
            }
            if (sOverlayHost == null) return false;

            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int idx = shadeWindowView.indexOfChild(sOverlayHost);
                if (idx >= 0 && idx < shadeWindowView.getChildCount() - 1) {
                    shadeWindowView.bringChildToFront(sOverlayHost);
                }
            }

            if (!sPrefs.fullOsMode) {
                return false;
            }
            return sOverlayHost.handleShadeDispatchTouch(ev);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean onShadeWindowInterceptTouch(FrameLayout shadeWindowView, MotionEvent ev) {
        try {
            if (shadeWindowView == null || ev == null || sOverlayHost == null) {
                return false;
            }
            if (!sPrefs.fullOsMode) {
                return false;
            }
            return sOverlayHost.handleShadeInterceptTouch(ev);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldMakeEntireShadeTouchable() {
        try {
            return sPrefs.fullOsMode
                    && sOverlayHost != null
                    && sOverlayHost.requiresFullScreenTouch();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void notifyOverlayTouchabilityChanged() {
        try {
            boolean needFull = shouldMakeEntireShadeTouchable();
            if (sShadeWindowController == null && sShadeWindowView != null) {
                resolveShadeControllersFromView(sShadeWindowView);
            }
            if (sShadeWindowController != null && sSetForcePluginOpenMethod != null) {
                if (needFull != sLastForceOpenApplied) {
                    sLastForceOpenApplied = needFull;
                    sSetForcePluginOpenMethod.invoke(
                            sShadeWindowController, FORCE_OPEN_TOKEN, needFull);
                }
            }
            if (sShadeWindowView != null) {
                sShadeWindowView.requestLayout();
                sShadeWindowView.invalidate();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resolveShadeControllersFromView(FrameLayout shadeWindowView) {
        try {
            Field handlerField = findField(shadeWindowView.getClass(), "mInteractionEventHandler");
            if (handlerField == null) return;
            Object interactionHandler = handlerField.get(shadeWindowView);
            if (interactionHandler == null) return;

            Field outerField = findField(interactionHandler.getClass(), "this$0");
            if (outerField == null) return;
            Object viewController = outerField.get(interactionHandler);
            if (viewController == null) return;

            Field nswcField = findField(viewController.getClass(), "mNotificationShadeWindowController");
            if (nswcField != null) {
                Object nswc = nswcField.get(viewController);
                if (nswc != null) {
                    sShadeWindowController = nswc;
                    if (sSetForcePluginOpenMethod == null) {
                        Method m = findMethod(
                                nswc.getClass(),
                                "setForcePluginOpen",
                                Object.class,
                                boolean.class);
                        if (m != null) {
                            sSetForcePluginOpenMethod = m;
                        }
                    }
                }
            }

            Field sbStateField = findField(viewController.getClass(), "mStatusBarStateController");
            if (sbStateField != null) {
                Object sbState = sbStateField.get(viewController);
                if (sbState != null) {
                    Field dozeF = findField(sbState.getClass(), "mDozeAmount");
                    if (dozeF != null) {
                        sDozeAmount = dozeF.getFloat(sbState);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 2. Keyguard, Doze, UDFPS & Compose SceneContainer Chrome Hooks
    // =========================================================================

    public static void onKeyguardControllerUpdated(Object controller) {
        try {
            if (controller == null) return;
            Class<?> cls = controller.getClass();
            if (sKgShowingField == null) {
                sKgShowingField = findField(cls, "mShowing");
                sKgOccludedField = findField(cls, "mOccluded");
                sKgGoingAwayField = findField(cls, "mKeyguardGoingAway");
                sKgCanDismissField = findField(cls, "mCanDismissLockScreen");
                Field ctxField = findField(cls, "mContext");
                if (ctxField != null && sAppContext == null) {
                    Object ctxObj = ctxField.get(controller);
                    if (ctxObj instanceof Context) {
                        ensureCoreInitialized((Context) ctxObj);
                    }
                }
            }

            boolean showing = (sKgShowingField != null) && sKgShowingField.getBoolean(controller);
            boolean occluded = (sKgOccludedField != null) && sKgOccludedField.getBoolean(controller);
            boolean goingAway = (sKgGoingAwayField != null) && sKgGoingAwayField.getBoolean(controller);
            boolean canDismiss = (sKgCanDismissField != null) && sKgCanDismissField.getBoolean(controller);

            sKeyguardShowing = showing;
            sKeyguardOccluded = occluded;
            sKeyguardGoingAway = goingAway;
            sCanDismissLockScreen = canDismiss;

            if (Looper.myLooper() == Looper.getMainLooper()) {
                syncKeyguardOverlayAndStockChrome(false);
            } else {
                MAIN_HANDLER.post(() -> syncKeyguardOverlayAndStockChrome(false));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onDozeAmountChanged(Object sbStateController, float dozeAmount) {
        try {
            sDozeAmount = SamuraiGlassPrefs.clampF(dozeAmount, 0f, 1f);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                syncKeyguardOverlayAndStockChrome(false);
            } else {
                MAIN_HANDLER.post(() -> syncKeyguardOverlayAndStockChrome(false));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void syncKeyguardOverlayAndStockChrome(boolean forceComposeBump) {
        try {
            if (sOverlayHost != null) {
                sOverlayHost.setKeyguardState(
                        sKeyguardShowing,
                        sKeyguardOccluded,
                        sKeyguardGoingAway,
                        sCanDismissLockScreen,
                        sDozeAmount);
            }
            boolean hideStockChrome = sPrefs.fullOsMode
                    && sPrefs.lockScreenEnabled
                    && sDozeAmount < 0.85f;
            applyLegacyKeyguardRootScaling(hideStockChrome && sKeyguardShowing && !sKeyguardOccluded);
            if (forceComposeBump || hideStockChrome != sLastHideStockLockChrome) {
                sLastHideStockLockChrome = hideStockChrome;
                bumpComposeEpoch();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyLegacyKeyguardRootScaling(boolean hideStock) {
        FrameLayout shade = sShadeWindowView;
        if (shade == null) return;
        try {
            View root = shade.findViewById(KEYGUARD_ROOT_VIEW_ID);
            if (!(root instanceof ViewGroup)) return;
            ViewGroup kgRoot = (ViewGroup) root;
            int count = kgRoot.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = kgRoot.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getSimpleName();
                // Keep Goodix G3 UDFPS / DeviceEntryIcon / KeyguardIndication untouched
                if (name.contains("DeviceEntry")
                        || name.contains("Udfps")
                        || name.contains("LockIcon")
                        || name.contains("Indication")
                        || name.contains("Notification")) {
                    if (child.getScaleX() != 1f || child.getScaleY() != 1f) {
                        child.setScaleX(1f);
                        child.setScaleY(1f);
                    }
                    continue;
                }
                float targetScale = hideStock ? 0f : 1f;
                if (child.getScaleX() != targetScale || child.getScaleY() != targetScale) {
                    child.setScaleX(targetScale);
                    child.setScaleY(targetScale);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Subscribes `LockscreenElementFactoryImpl.LockscreenElement` to our live settings/doze
     * Compose `MutableState` so toggling `samurai_ios26_lockscreen_enabled` or `fullOsMode`
     * recomposes the Lock Screen instantaneously with zero reboot.
     */
    public static void observeComposeEpoch() {
        try {
            Object state = ensureComposeEpochState();
            if (state != null && sMutableStateGetValue != null) {
                sMutableStateGetValue.invoke(state);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns `0.0f` for stock Lock Screen clock, date/weather smartspace, and bottom-corner
     * quick affordance shortcuts when the iOS 26 Spatial Lock Screen is active (preserving exact
     * layout bounds for `LockscreenNotificationStack`), and `1.0f` for notifications, Goodix G3
     * `LockIcon` UDFPS, `IndicationArea`, AOD elements, or when Lock Screen mode is disabled.
     */
    public static float getLockscreenElementAlpha(Context ctx, String debugName) {
        try {
            if (ctx != null && sAppContext == null) {
                ensureCoreInitialized(ctx);
            }
            if (debugName == null) return 1.0f;
            SamuraiGlassPrefs.Snapshot snap = sPrefs;
            if (!snap.fullOsMode || !snap.lockScreenEnabled || sDozeAmount >= 0.85f) {
                return 1.0f;
            }
            if ("LargeClock".equals(debugName)
                    || "SmallClock".equals(debugName)
                    || "LargeClockRegion".equals(debugName)
                    || "SmallClockRegion".equals(debugName)
                    || "ShortcutStart".equals(debugName)
                    || "ShortcutEnd".equals(debugName)
                    || "SmartspaceCards".equals(debugName)
                    || "SliceView".equals(debugName)
                    || debugName.startsWith("SmartspaceDWA")) {
                return 0.0f;
            }
            return 1.0f;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static Object ensureComposeEpochState() {
        Object existing = sComposeEpochState;
        if (existing != null) return existing;
        try {
            Class<?> snapshotKt = Class.forName("androidx.compose.runtime.SnapshotStateKt");
            Class<?> policyCls = Class.forName("androidx.compose.runtime.SnapshotMutationPolicy");
            Method factory = snapshotKt.getMethod(
                    "mutableStateOf$default",
                    Object.class,
                    policyCls,
                    int.class,
                    Object.class);
            Object state = factory.invoke(null, Integer.valueOf(sComposeEpoch), null, Integer.valueOf(2), null);
            if (state != null) {
                Class<?> msCls = Class.forName("androidx.compose.runtime.MutableState");
                sMutableStateGetValue = msCls.getMethod("getValue");
                sMutableStateSetValue = msCls.getMethod("setValue", Object.class);
                sComposeEpochState = state;
            }
            return state;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void bumpComposeEpoch() {
        try {
            Object state = sComposeEpochState;
            if (state != null && sMutableStateSetValue != null) {
                sComposeEpoch++;
                sMutableStateSetValue.invoke(state, Integer.valueOf(sComposeEpoch));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onAuthControllerUpdated(Object authController) {
        try {
            if (authController == null) return;
            sLastAuthController = authController;
            refreshUdfpsFromAuthController(authController);
            // Retry shortly after boot registration in case FingerprintManager callback completes async
            MAIN_HANDLER.postDelayed(() -> refreshUdfpsFromAuthController(sLastAuthController), 650L);
        } catch (Throwable ignored) {
        }
    }

    private static void refreshUdfpsFromAuthController(Object authController) {
        if (authController == null) return;
        try {
            if (sGetUdfpsLocationMethod == null) {
                sGetUdfpsLocationMethod = findMethod(authController.getClass(), "getUdfpsLocation");
            }
            if (sGetUdfpsLocationMethod != null) {
                Object res = sGetUdfpsLocationMethod.invoke(authController);
                if (res instanceof Point) {
                    Point p = (Point) res;
                    if (p.x > 0 && p.y > 0) {
                        sUdfpsCenter = new Point(p.x, p.y);
                        if (sOverlayHost != null) {
                            sOverlayHost.postInvalidate();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static Point getUdfpsSensorCenter(Context ctx) {
        try {
            Point cached = sUdfpsCenter;
            if (cached == null && sLastAuthController != null) {
                refreshUdfpsFromAuthController(sLastAuthController);
                cached = sUdfpsCenter;
            }
            if (cached != null && cached.x > 0 && cached.y > 0) {
                return new Point(cached.x, cached.y);
            }
            if (ctx != null) {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int x = Math.round(DEFAULT_UDFPS_X_1080 * (dm.widthPixels / 1080f));
                int y = Math.round(DEFAULT_UDFPS_Y_2400 * (dm.heightPixels / 2400f));
                return new Point(x, y);
            }
        } catch (Throwable ignored) {
        }
        return new Point(DEFAULT_UDFPS_X_1080, DEFAULT_UDFPS_Y_2400);
    }

    public static boolean isKeyguardShowing() {
        return sKeyguardShowing && !sKeyguardOccluded;
    }

    // =========================================================================
    // 3. PhoneStatusBarView (Dynamic Island Capsule) Hooks
    // =========================================================================

    public static void onStatusBarAttached(FrameLayout phoneStatusBarView) {
        try {
            if (phoneStatusBarView == null) return;
            Context ctx = phoneStatusBarView.getContext();
            ensureCoreInitialized(ctx);

            sStatusBarView = phoneStatusBarView;
            phoneStatusBarView.setClipChildren(false);
            phoneStatusBarView.setClipToPadding(false);
            ViewParent parent = phoneStatusBarView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).setClipChildren(false);
                ((ViewGroup) parent).setClipToPadding(false);
            }

            if (sDynamicIslandView == null || sDynamicIslandView.getContext() != ctx) {
                if (sDynamicIslandView != null && sDynamicIslandView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sDynamicIslandView.getParent()).removeView(sDynamicIslandView);
                }
                sDynamicIslandView = new DynamicIslandView(ctx, sIslandController, sPrefs);
            } else if (sDynamicIslandView.getParent() != phoneStatusBarView) {
                if (sDynamicIslandView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) sDynamicIslandView.getParent()).removeView(sDynamicIslandView);
                }
            }

            if (sDynamicIslandView.getParent() != phoneStatusBarView) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        SystemUiGlassHelper.dpInt(ctx, 250f),
                        SystemUiGlassHelper.dpInt(ctx, 38f),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = SystemUiGlassHelper.dpInt(ctx, 6f);
                sDynamicIslandView.setElevation(SystemUiGlassHelper.dp(ctx, 24f));
                phoneStatusBarView.addView(sDynamicIslandView, lp);
            }
            phoneStatusBarView.bringChildToFront(sDynamicIslandView);
        } catch (Throwable ignored) {
        }
    }

    public static boolean onStatusBarDispatchTouch(FrameLayout phoneStatusBarView, MotionEvent ev) {
        try {
            if (phoneStatusBarView == null || ev == null) return false;
            if (sDynamicIslandView == null || sDynamicIslandView.getParent() != phoneStatusBarView) {
                onStatusBarAttached(phoneStatusBarView);
            }
            if (sDynamicIslandView == null || !sPrefs.fullOsMode || !sPrefs.dynamicIslandEnabled) {
                return false;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int idx = phoneStatusBarView.indexOfChild(sDynamicIslandView);
                if (idx >= 0 && idx < phoneStatusBarView.getChildCount() - 1) {
                    phoneStatusBarView.bringChildToFront(sDynamicIslandView);
                }
            }
            return sDynamicIslandView.handleStatusBarTouch(ev);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =========================================================================
    // 4. Side-Edge Volume Pill & Frosted Slide-to-Power-Off Menu Hooks
    // =========================================================================

    public static boolean onVolumeShowRequested(Object volumeController, int stream) {
        try {
            if (volumeController == null) return false;
            Class<?> cls = volumeController.getClass();
            if (sVolContextField == null) {
                sVolContextField = findField(cls, "mContext");
                sVolAudioField = findField(cls, "mAudio");
            }
            if (sVolContextField == null) return false;
            Object ctxObj = sVolContextField.get(volumeController);
            if (!(ctxObj instanceof Context)) return false;
            Context ctx = (Context) ctxObj;
            ensureCoreInitialized(ctx);

            SamuraiGlassPrefs.Snapshot snap = SamuraiGlassPrefs.read(sAppContext, sPrefs);
            sPrefs = snap;
            if (!snap.fullOsMode) {
                if (sVolumeHud != null) {
                    MAIN_HANDLER.post(sVolumeHud::hideHud);
                }
                return false;
            }

            AudioManager audio = null;
            if (sVolAudioField != null) {
                Object amObj = sVolAudioField.get(volumeController);
                if (amObj instanceof AudioManager) {
                    audio = (AudioManager) amObj;
                }
            }
            final AudioManager finalAudio = audio;
            final int finalStream = stream;
            MAIN_HANDLER.post(() -> {
                try {
                    if (sVolumeHud == null) {
                        sVolumeHud = new IosVolumeHudView(sAppContext);
                    }
                    sVolumeHud.showForStream(finalAudio, finalStream);
                } catch (Throwable ignored) {
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean onGlobalActionsShowOrHide(
            Object globalActionsLite,
            boolean keyguardShowing,
            boolean deviceProvisioned) {
        try {
            if (globalActionsLite == null) return false;
            Class<?> cls = globalActionsLite.getClass();
            if (sGaContextField == null) {
                sGaContextField = findField(cls, "mContext");
                sGaWmFuncsField = findField(cls, "mWindowManagerFuncs");
            }
            if (sGaContextField == null) return false;
            Object ctxObj = sGaContextField.get(globalActionsLite);
            if (!(ctxObj instanceof Context)) return false;
            Context ctx = (Context) ctxObj;
            ensureCoreInitialized(ctx);

            SamuraiGlassPrefs.Snapshot snap = SamuraiGlassPrefs.read(sAppContext, sPrefs);
            sPrefs = snap;
            if (!snap.fullOsMode) {
                if (sPowerMenu != null && sPowerMenu.isMenuShowing()) {
                    sPowerMenu.dismissMenu(false);
                }
                return false;
            }

            final Object wmFuncs = (sGaWmFuncsField != null)
                    ? sGaWmFuncsField.get(globalActionsLite)
                    : null;

            if (Looper.myLooper() == Looper.getMainLooper()) {
                return toggleIosPowerMenuOnMain(wmFuncs);
            } else {
                MAIN_HANDLER.post(() -> toggleIosPowerMenuOnMain(wmFuncs));
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean toggleIosPowerMenuOnMain(Object wmFuncs) {
        try {
            if (sPowerMenu == null) {
                sPowerMenu = new IosPowerMenuView(sAppContext);
            }
            if (sPowerMenu.isMenuShowing()) {
                sPowerMenu.dismissMenu(true);
                return true;
            }
            collapseControlCenter(false);
            collapseDynamicIsland();
            return sPowerMenu.showMenu(wmFuncs);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =========================================================================
    // Cross-Surface Collapse Helpers
    // =========================================================================

    public static void collapseControlCenter(boolean animated) {
        try {
            Runnable action = () -> {
                if (sOverlayHost != null) {
                    sOverlayHost.collapseControlCenter(animated);
                }
                notifyOverlayTouchabilityChanged();
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run();
            } else {
                MAIN_HANDLER.post(action);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void collapseDynamicIsland() {
        try {
            Runnable action = () -> {
                if (sIslandController != null) {
                    sIslandController.setExpanded(false);
                }
                notifyOverlayTouchabilityChanged();
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action.run();
            } else {
                MAIN_HANDLER.post(action);
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Reflection Lookup Utilities
    // =========================================================================

    private static Field findField(Class<?> startCls, String fieldName) {
        Class<?> cur = startCls;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> startCls, String methodName, Class<?>... paramTypes) {
        Class<?> cur = startCls;
        while (cur != null && cur != Object.class) {
            try {
                Method m = cur.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            Method m = startCls.getMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
