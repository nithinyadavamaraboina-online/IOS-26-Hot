package com.realme.samurai.controlcenter.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.app.Service
import android.app.TaskInfo
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.realme.samurai.controlcenter.ControlCenterActivity
import com.realme.samurai.controlcenter.IosKeyguardDismissActivity
import com.realme.samurai.controlcenter.R
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.ui.glass.Ios26HomeIndicatorBar
import com.realme.samurai.controlcenter.ui.glass.rememberCcGlassConfig
import com.realme.samurai.controlcenter.ui.screens.IosLockScreenRoot
import com.realme.samurai.controlcenter.ui.screens.IosSheetMode
import com.realme.samurai.controlcenter.ui.screens.IosTopOverlayRootScreen
import com.realme.samurai.controlcenter.ui.theme.IosControlCenterTheme
import java.lang.reflect.Field
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class ControlCenterOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var homeBarWindowManager: WindowManager? = null
    private var lockWindowManager: WindowManager? = null
    private var panelWindowManager: WindowManager? = null
    private var triggerWindowManager: WindowManager? = null
    private var controller: ControlCenterController? = null
    private var lifecycleOwner: OverlayWindowLifecycleOwner? = null

    private var homeBarHostView: FrameLayout? = null
    private var homeBarParams: WindowManager.LayoutParams? = null
    private var homeBarFadeAnimator: ValueAnimator? = null
    private var homeBarSpringAnimator: ValueAnimator? = null
    private var homeBarTargetShown: Boolean = false
    private var lockHostView: FrameLayout? = null
    private var lockParams: WindowManager.LayoutParams? = null
    private var triggerView: View? = null
    private var triggerParams: WindowManager.LayoutParams? = null
    private var panelHostView: FrameLayout? = null
    private var panelComposeView: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var activeWindowType: Int = 0
    private var attachedA11yIdentity: Int = 0
    private var lastFailedA11yIdentity: Int = 0
    private var appliedLockWindowFlags: Int = 0
    private var appliedPanelWindowFlags: Int = 0

    // Tracks actual composed mode/progress + ViewTreeObserver pre-draw gate so ViewRootImpl
    // NEVER presents a cached pre-GONE RenderNode frame of the opposite sheet!
    private var lastComposedSheetMode: IosSheetMode? = null
    private var lastComposedOpenProgress: Float = 0f
    private var panelPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    // Display-level SPY InputMonitor ("samurai-top-swipe") for top bezel pull-downs + bottom Lock/Sheet/HomeBar gestures
    private var topGestureMonitor: InputMonitor? = null
    private var topGestureReceiver: InputEventReceiver? = null
    private var topGestureCleanupHook: (() -> Unit)? = null
    private var isTopBezelGestureActive: Boolean = false
    private var lastTopBezelGestureUptimeMs: Long = 0L
    private var rebuildingWindowStack: Boolean = false

    // Persistent StatusBarManager instance (holds consistent Binder token for disable/re-enable)
    private var statusBarManagerObj: Any? = null
    private var androidShadeLockedOut: Boolean = false

    private val activeSheetModeState = mutableStateOf(IosSheetMode.CONTROL_CENTER)
    private val openProgressState = mutableFloatStateOf(0f)
    private val sheetVelocityState = mutableFloatStateOf(0f)
    private val lockCurtainProgressState = mutableFloatStateOf(0f)
    private val lockCurtainVelocityState = mutableFloatStateOf(0f)
    private val isSecureKeyguardState = mutableStateOf(false)
    private val isUdfpsFingerDownState = mutableStateOf(false)
    private val hasEnrolledUdfpsState = mutableStateOf(false)
    private val gesturePillEnabledState = mutableStateOf(true)
    private val isLockScreenVisibleState = mutableStateOf(false)
    private val lockScreenSessionEpochState = mutableIntStateOf(0)
    private val lockWakeTickState = mutableIntStateOf(0)
    private var lockUdfpsApertureCleanup: (() -> Unit)? = null

    // SamuraiIos26HomeBar live state (`0 = IN_APP`, `1 = HOME_NORMAL`, `2 = OVERVIEW_RECENTS`)
    private var launcherBarState: Int = 0
    private var lastObservedTopPkg: String? = null
    private var navBarsVisible: Boolean = true
    private var a11yImeVisible: Boolean = false
    private var a11yHasExternalFullscreenApp: Boolean = false
    private var a11yHasFullscreenSystemShade: Boolean = false
    private val homeBarScrubState = mutableFloatStateOf(0f)
    private val homeBarStretchState = mutableFloatStateOf(0f)
    private val homeBarLiftState = mutableFloatStateOf(0f)
    private val homeBarIdleDimState = mutableStateOf(false)
    private val homeBarIdleDimRunnable = Runnable {
        homeBarIdleDimState.value = true
    }
    private val homeBarPostTouchReconcileRunnable = Runnable {
        if (lastObservedTopPkg == null || lastObservedTopPkg == NEXUS_LAUNCHER_PKG) {
            val persisted = readLauncherBarState()
            if (launcherBarState != persisted) {
                launcherBarState = persisted
                syncHomeBarVisibility(immediate = false)
            }
        }
    }

    private var progressAnimator: ValueAnimator? = null
    private var lockCurtainAnimator: ValueAnimator? = null
    private var overlayAttachRetries = 0
    private var receiverRegistered = false
    private var settingsObserverRegistered = false

    // Anti-glitch tracking for Home Screen, Bezel pull-downs, & Bottom SPY Lock/Sheet gestures
    private var isSheetGestureActive: Boolean = false
    private var isBottomSpyUnlockingLockScreen: Boolean = false
    private var isBottomSpyClosingSheet: Boolean = false
    private var isComposeLockDragging: Boolean = false
    private var isLockCurtainUnlocking: Boolean = false
    private var lastGestureUptimeMs: Long = 0L
    private var lastTopOpenGestureUptimeMs: Long = 0L
    private var lastLockTouchUptimeMs: Long = 0L
    private var lastBottomSpyLockSettleUptimeMs: Long = 0L
    private var lastBottomSpySheetSettleUptimeMs: Long = 0L
    private var lastScreenOffUptimeMs: Long = 0L
    @Volatile
    private var lastCameraLaunchDetectedUptimeMs: Long = -10_000L
    private var lastHomeSwipeSessionId: Long = 0L
    private var lastHomeSwipeSeq: Long = -1L
    private var interactiveCloseStartProgress: Float = 1f
    private var lastInteractiveCloseMoveUptimeMs: Long = 0L
    private var taskStackListener: TaskStackListener? = null

    // Suppresses stock Android SystemUI insecure NotificationShade keyguard when `samurai_ios26_lockscreen == 1`
    // and `!km.isKeyguardSecure` (Swipe mode), so unlocking reveals the live Home Screen at 90Hz with zero
    // stock Android NotificationShade bleed-through, zero blank wallpaper stall, and zero task-close black bar!
    @Suppress("DEPRECATION")
    private var systemKeyguardLock: KeyguardManager.KeyguardLock? = null
    private var systemKeyguardSuppressed: Boolean = false
    private var iosSwipeLockEngaged: Boolean = false
    private var swipeLockInitialTaskId: Int = -1
    private var swipeLockInitialComp: String? = null
    private var lastLockDismissUptimeMs: Long = 0L
    private var lastKnownKeyguardLocked: Boolean = false
    private var lastKeyguardLockedTransitionUptimeMs: Long = 0L
    private var lastBouncerCancelUptimeMs: Long = 0L
    private var keyguardLockedListener: KeyguardManager.KeyguardLockedStateListener? = null

    // Lock Screen bouncer passthrough state (true while PIN/Pattern/Goodix UDFPS bouncer or Secure Camera is frontmost)
    private var bouncerPassthrough: Boolean = false
    private var bouncerPassthroughSinceUptimeMs: Long = 0L
    private var bouncerVisualMaskHoldActive: Boolean = false
    private var bouncerRevealFadeAnimator: ValueAnimator? = null
    private var secureBouncerCoordinator: SecureBouncerHandoffCoordinator? = null
    private var secureCameraPassthroughActive: Boolean = false
    private var secureCameraSawAppWindow: Boolean = false
    private var secureCameraLaunchUptimeMs: Long = 0L

    private fun cancelBouncerRevealFade(resetHostAlpha: Boolean = true) {
        val anim = bouncerRevealFadeAnimator
        bouncerRevealFadeAnimator = null
        if (anim != null) {
            anim.removeAllListeners()
            anim.removeAllUpdateListeners()
            anim.cancel()
        }
        if (resetHostAlpha) {
            lockHostView?.alpha = 1f
        }
    }

    private fun startBouncerVisualMaskFadeOut(seq: Long) {
        val host = lockHostView
        if (host == null || host.visibility != View.VISIBLE || !bouncerVisualMaskHoldActive) {
            bouncerVisualMaskHoldActive = false
            val coord = secureBouncerCoordinator
            if (coord != null && coord.activeSessionSeq == seq) {
                coord.notifyVisualMaskFadeCompleted(seq)
            } else {
                syncLockScreenVisibility()
            }
            host?.alpha = 1f
            return
        }
        cancelBouncerRevealFade(resetHostAlpha = false)
        val startAlpha = host.alpha.coerceIn(0.05f, 1f)
        bouncerRevealFadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
            duration = 85L
            interpolator = DecelerateInterpolator(1.4f)
            var cancelled = false
            addUpdateListener { anim ->
                if (!cancelled && bouncerVisualMaskHoldActive) {
                    host.alpha = (anim.animatedValue as Float).coerceIn(0f, 1f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (bouncerRevealFadeAnimator === animation) {
                        bouncerRevealFadeAnimator = null
                    }
                    if (!cancelled) {
                        bouncerVisualMaskHoldActive = false
                        val coord = secureBouncerCoordinator
                        if (coord != null && coord.activeSessionSeq == seq) {
                            coord.notifyVisualMaskFadeCompleted(seq)
                        } else {
                            syncLockScreenVisibility()
                        }
                        if (host.visibility == View.VISIBLE && bouncerPassthrough && !bouncerVisualMaskHoldActive) {
                            syncLockScreenVisibility()
                        }
                    }
                    host.alpha = 1f
                }
            })
            start()
        }
    }

    private fun setBouncerPassthroughState(active: Boolean) {
        val changed = bouncerPassthrough != active
        if (active) {
            if (!bouncerPassthrough) {
                bouncerPassthroughSinceUptimeMs = SystemClock.uptimeMillis()
            }
            bouncerPassthrough = true
            isUdfpsFingerDownState.value = false
        } else {
            bouncerPassthrough = false
            bouncerPassthroughSinceUptimeMs = 0L
        }
        if (changed) {
            syncLockUdfpsApertureIfChanged()
        }
    }

    private var lastCarvedUdfpsAperture: Boolean? = null

    private fun shouldCarveUdfpsTouchAperture(): Boolean {
        return lockHostView?.visibility == View.VISIBLE &&
            isSecureKeyguardState.value &&
            hasEnrolledUdfpsState.value &&
            !bouncerPassthrough &&
            !secureCameraPassthroughActive &&
            !isLockCurtainUnlocking &&
            lockCurtainProgressState.floatValue <= 0.02f &&
            openProgressState.floatValue <= 0.01f
    }

    private fun invalidateLockUdfpsAperture() {
        val host = lockHostView ?: return
        host.requestLayout()
        host.invalidate()
    }

    private fun syncLockUdfpsApertureIfChanged() {
        if (lockHostView == null) {
            lastCarvedUdfpsAperture = null
            return
        }
        val wantCarve = shouldCarveUdfpsTouchAperture()
        if (lastCarvedUdfpsAperture != wantCarve) {
            invalidateLockUdfpsAperture()
        }
    }

    private fun syncControllerPanelVisibility() {
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive == true
        val topSheetActive = interactive &&
            panelHostView?.visibility == View.VISIBLE &&
            openProgressState.floatValue > 0.005f
        val lockScreenActive = interactive &&
            lockHostView?.visibility == View.VISIBLE &&
            isLockScreenVisibleState.value
        if (topSheetActive || lockScreenActive) {
            controller?.onPanelOpening()
        } else {
            controller?.onPanelClosed()
        }
    }

    private val sheetGestureSafetyWatchdog = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            if (!isSheetGestureActive) return
            val elapsed = SystemClock.uptimeMillis() - lastGestureUptimeMs
            if (elapsed >= 1650L) {
                Log.w(TAG, "Watchdog: settling stale unlocked/locked isSheetGestureActive after ${elapsed}ms")
                clearSheetGestureActive()
                isTopBezelGestureActive = false
                isBottomSpyClosingSheet = false
                animatePanelTo(if (openProgressState.floatValue >= 0.5f) 1f else 0f)
            } else {
                mainHandler.postDelayed(this, (1800L - elapsed).coerceAtLeast(200L))
            }
        }
    }

    private val postKeyguardLockVerifyRunnable = Runnable {
        if (activeInstance === this) {
            syncLockScreenVisibility()
        }
    }

    private val postBouncerVerifyRunnable = Runnable {
        if (activeInstance !== this) return@Runnable
        IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
        if (secureBouncerCoordinator?.isSessionActive != true && !secureCameraPassthroughActive) {
            setBouncerPassthroughState(false)
        }
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = true)
    }

    private val windowStackRecoveryRunnable = Runnable {
        Log.w(TAG, "Rebuilding overlay window stack after stale WindowManager layout/token failure")
        removeWindowsSafely()
        tryAttachWindows()
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = true)
    }

    private val lockSafetyWatchdog = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            val nowUptime = SystemClock.uptimeMillis()

            if (lockHostView == null &&
                iosSwipeLockEngaged &&
                isUserUnlocked() &&
                IosSystemOverlayAccessibilityService.instance != null
            ) {
                tryAttachWindows()
            }

            // Self-heal stale TopSheet gesture or invisible-yet-touchable TopSheet blocking Lock Screen touches
            if (isSheetGestureActive && (nowUptime - lastGestureUptimeMs) > 2200L) {
                Log.w(TAG, "Watchdog: settling stale isSheetGestureActive over Lock Screen")
                clearSheetGestureActive()
                isTopBezelGestureActive = false
                isBottomSpyClosingSheet = false
                animatePanelTo(if (openProgressState.floatValue >= 0.5f) 1f else 0f)
            } else if (
                !isSheetGestureActive &&
                progressAnimator?.isRunning != true &&
                openProgressState.floatValue <= 0.02f &&
                (
                    panelHostView?.visibility == View.VISIBLE ||
                        (panelHostView != null && (appliedPanelWindowFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0)
                    )
            ) {
                Log.w(TAG, "Watchdog: closing stale touchable TopSheet over Lock Screen")
                closePanelImmediate()
            }

            // Self-heal stale Lock Screen drag flags or half-lifted curtain
            if (isBottomSpyUnlockingLockScreen && (nowUptime - lastLockTouchUptimeMs) > 2200L) {
                isBottomSpyUnlockingLockScreen = false
            }
            if (isComposeLockDragging && (nowUptime - lastLockTouchUptimeMs) > 2200L) {
                isComposeLockDragging = false
            }
            if (!isBottomSpyUnlockingLockScreen &&
                !isComposeLockDragging &&
                !isLockCurtainUnlocking &&
                !bouncerPassthrough &&
                !bouncerVisualMaskHoldActive &&
                lockCurtainAnimator?.isRunning != true &&
                lockCurtainProgressState.floatValue > 0.01f &&
                (nowUptime - lastLockTouchUptimeMs) > 1200L
            ) {
                animateLockCurtainBackToZero()
            }

            val km = getSystemService(KeyguardManager::class.java)
            val isSysLockedNow = km?.isKeyguardLocked == true
            val taskOccludingNow = queryKeyguardOccludingTopTask(nowUptime, isSystemLocked = isSysLockedNow)
            val coord = secureBouncerCoordinator
            if (coord?.isSessionActive == true) {
                coord.checkWatchdog(
                    nowUptimeMs = nowUptime,
                    imeVisible = a11yImeVisible,
                    secureCameraActive = secureCameraPassthroughActive,
                    taskOccluding = taskOccludingNow,
                )
            } else if (
                bouncerPassthrough &&
                (nowUptime - bouncerPassthroughSinceUptimeMs) > SecureBouncerHandoffCoordinator.IDLE_WATCHDOG_TIMEOUT_MS &&
                !IosKeyguardDismissActivity.isDismissActivityAlive &&
                !secureCameraPassthroughActive &&
                !taskOccludingNow
            ) {
                Log.w(TAG, "Watchdog: clearing orphaned bouncerPassthrough after ${SecureBouncerHandoffCoordinator.IDLE_WATCHDOG_TIMEOUT_MS}ms")
                bouncerVisualMaskHoldActive = false
                cancelBouncerRevealFade(resetHostAlpha = true)
                setBouncerPassthroughState(false)
            }

            if (secureCameraPassthroughActive) {
                IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
            }

            syncSystemKeyguardSuppression()
            syncLockScreenVisibility()
            val pm = getSystemService(PowerManager::class.java)
            val stillLocked = (km?.isKeyguardLocked == true) ||
                (lockHostView?.visibility == View.VISIBLE) ||
                iosSwipeLockEngaged ||
                secureCameraPassthroughActive ||
                bouncerPassthrough
            if (stillLocked && pm?.isInteractive == true) {
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

    private val lockSettingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            val path = uri?.lastPathSegment.orEmpty()
            if (path == "samurai_launcher_bar_state") {
                val persisted = readLauncherBarState()
                launcherBarState = persisted
                if (persisted == 1 || persisted == 2) {
                    lastObservedTopPkg = NEXUS_LAUNCHER_PKG
                }
                syncHomeBarVisibility(immediate = false)
                return
            }
            if (path == "samurai_gesture_pill_enabled" || path == "samurai_ios26_gesture_pill") {
                gesturePillEnabledState.value = readGesturePillEnabled()
                syncHomeBarVisibility(immediate = false)
                return
            }
            gesturePillEnabledState.value = readGesturePillEnabled()
            val persisted = readLauncherBarState()
            launcherBarState = persisted
            if (persisted == 1 || persisted == 2) {
                lastObservedTopPkg = NEXUS_LAUNCHER_PKG
            }
            syncSystemKeyguardSuppression()
            syncAndroidShadeLockout()
            syncLockScreenVisibility()
            syncHomeBarVisibility(immediate = false)
        }
    }

    private val systemCloseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    lastScreenOffUptimeMs = SystemClock.uptimeMillis()
                    if ((lastScreenOffUptimeMs - lastCameraLaunchDetectedUptimeMs) > 600L) {
                        lastCameraLaunchDetectedUptimeMs = -10_000L
                    }
                    IosKeyguardDismissActivity.cancelOnScreenOff()
                    secureBouncerCoordinator?.onScreenOff()
                    bouncerVisualMaskHoldActive = false
                    cancelBouncerRevealFade(resetHostAlpha = true)
                    mainHandler.removeCallbacks(postBouncerVerifyRunnable)
                    isBottomSpyUnlockingLockScreen = false
                    isBottomSpyClosingSheet = false
                    isComposeLockDragging = false
                    isLockCurtainUnlocking = false
                    isUdfpsFingerDownState.value = false
                    closePanelImmediate()
                    if (!iosSwipeLockEngaged) {
                        captureSwipeLockBaselineTask()
                    }
                    syncSystemKeyguardSuppression()
                    val km = getSystemService(KeyguardManager::class.java)
                    val lockOverlayReady = lockHostView != null &&
                        lockParams?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    if (systemKeyguardSuppressed &&
                        isUserUnlocked() &&
                        isLockScreenEnabled() &&
                        km?.isKeyguardSecure != true &&
                        lockOverlayReady
                    ) {
                        iosSwipeLockEngaged = true
                    } else {
                        iosSwipeLockEngaged = false
                        clearSwipeLockBaselineTask()
                    }
                    // Pre-arm iOS 26 Lock Screen while OLED is off so waking shows Frame 0 with zero Android flash!
                    setBouncerPassthroughState(false)
                    secureCameraPassthroughActive = false
                    secureCameraSawAppWindow = false
                    lockCurtainAnimator?.cancel()
                    lockCurtainProgressState.floatValue = 0f
                    lockCurtainVelocityState.floatValue = 0f
                    lockScreenSessionEpochState.intValue += 1
                    syncAndroidShadeLockout()
                    syncLockScreenVisibility(preArmWhileScreenOff = true)
                    syncControllerPanelVisibility()
                }

                Intent.ACTION_SCREEN_ON -> {
                    lockWakeTickState.intValue += 1
                    IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
                    syncSystemKeyguardSuppression()
                    syncAndroidShadeLockout()
                    IosNotificationListenerService.instance?.refreshAll()
                    syncLockScreenVisibility()
                    syncControllerPanelVisibility()
                }

                Intent.ACTION_USER_PRESENT -> {
                    val km = getSystemService(KeyguardManager::class.java)
                    val pm = getSystemService(PowerManager::class.java)
                    if (km?.isKeyguardLocked == true || pm?.isInteractive != true) {
                        return
                    }
                    mainHandler.removeCallbacks(postBouncerVerifyRunnable)
                    secureBouncerCoordinator?.onUserPresentOrKeyguardUnlocked()
                    bouncerVisualMaskHoldActive = false
                    cancelBouncerRevealFade(resetHostAlpha = true)
                    upgradeToAccessibilityOverlayIfReady()
                    syncSystemKeyguardSuppression()
                    isLockCurtainUnlocking = false
                    isUdfpsFingerDownState.value = false
                    setBouncerPassthroughState(false)
                    secureCameraPassthroughActive = false
                    secureCameraSawAppWindow = false
                    if (!iosSwipeLockEngaged) {
                        lastScreenOffUptimeMs = -10_000L
                        clearSwipeLockBaselineTask()
                        lockCurtainAnimator?.cancel()
                        lockCurtainProgressState.floatValue = 0f
                    }
                    syncAndroidShadeLockout()
                    syncLockScreenVisibility()
                    syncControllerPanelVisibility()
                }

                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    dismissTopSheetIfCriticalInterrupt()
                    syncLockScreenVisibility()
                }

                @Suppress("DEPRECATION")
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    // Ignore internal SystemUI collapsePanels broadcasts! Only react when the user
                    // explicitly triggers Home or Recents.
                    val reason = intent.getStringExtra("reason").orEmpty()
                    if (reason == "recentapps" && launcherBarState == 1) {
                        launcherBarState = 0
                        syncHomeBarVisibility(immediate = false)
                    }
                    if (reason == "homekey") {
                        lastCameraLaunchDetectedUptimeMs = -10_000L
                    }
                    if (reason == "homekey" && secureCameraPassthroughActive) {
                        mainHandler.removeCallbacks(postBouncerVerifyRunnable)
                        secureCameraPassthroughActive = false
                        secureCameraSawAppWindow = false
                        setBouncerPassthroughState(false)
                        syncLockScreenVisibility()
                    }
                    val nowUptime = SystemClock.uptimeMillis()
                    val sinceTopOpen = nowUptime - lastTopOpenGestureUptimeMs
                    if ((reason == "homekey" || reason == "recentapps") &&
                        !isTopBezelGestureActive &&
                        sinceTopOpen > 450L &&
                        openProgressState.floatValue > 0.01f
                    ) {
                        clearSheetGestureActive()
                        isBottomSpyClosingSheet = false
                        animatePanelTo(0f)
                    } else if (
                        reason == "homekey" &&
                        openProgressState.floatValue <= 0.02f &&
                        iosSwipeLockEngaged &&
                        !bouncerPassthrough &&
                        (nowUptime - lastLockDismissUptimeMs) > 250L
                    ) {
                        val km = getSystemService(KeyguardManager::class.java)
                        val pm = getSystemService(PowerManager::class.java)
                        if (km?.isKeyguardLocked != true && pm?.isInteractive == true) {
                            animateLockCurtainAndUnlock(null)
                        }
                    }
                }

                Intent.ACTION_USER_UNLOCKED -> {
                    Log.i(TAG, "ACTION_USER_UNLOCKED -> enabling a11y + notification services & attaching windows")
                    ensurePrivilegedServicesEnabled()
                    controller?.ensureHardwareListeners()
                    upgradeToAccessibilityOverlayIfReady()
                    tryAttachWindows()
                    syncSystemKeyguardSuppression()
                    syncAndroidShadeLockout()
                    syncLockScreenVisibility()
                    syncHomeBarVisibility(immediate = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        gesturePillEnabledState.value = readGesturePillEnabled()
        launcherBarState = readLauncherBarState()
        Log.i(TAG, "ControlCenterOverlayService.onCreate (v2.1.5-b3 Direct Primary Bouncer handoff + self-healing overlay)")

        secureBouncerCoordinator = SecureBouncerHandoffCoordinator(
            scheduler = HandlerBouncerScheduler(mainHandler),
            bridge = AndroidKeyguardPlatformBridge(
                context = applicationContext,
                isLockWindowFocusedProvider = { lockHostView?.hasWindowFocus() == true },
            ),
            onSyncPassthroughAndMask = { passthroughActive, holdVisualMask ->
                bouncerVisualMaskHoldActive = passthroughActive && holdVisualMask
                cancelBouncerRevealFade(resetHostAlpha = true)
                setBouncerPassthroughState(passthroughActive)
                syncLockScreenVisibility()
                syncHomeBarVisibility(immediate = true)
            },
            onStartVisualMaskFadeOut = { seq ->
                startBouncerVisualMaskFadeOut(seq)
            },
            onSessionFinished = { unlocked ->
                handleBouncerResultInternal(unlocked)
            },
        )

        val ctrl = ControlCenterController(
            context = applicationContext,
            deferInitialLoad = true,
        ).also { c ->
            c.onRequestDismissAndRun = { afterDismiss ->
                animatePanelTo(0f) {
                    val km = getSystemService(KeyguardManager::class.java)
                    if (km?.isKeyguardLocked == true) {
                        if (isLockOverlayVisible() && !bouncerPassthrough) {
                            animateLockCurtainAndUnlock(afterDismiss)
                        } else {
                            requestKeyguardUnlockAndRun(afterDismiss)
                        }
                    } else if (iosSwipeLockEngaged) {
                        animateLockCurtainAndUnlock(afterDismiss)
                    } else {
                        afterDismiss()
                    }
                }
            }
        }
        controller = ctrl

        val owner = OverlayWindowLifecycleOwner().also { it.onCreate() }
        lifecycleOwner = owner

        IosSystemOverlayAccessibilityService.onConnectedCallback = {
            mainHandler.post { onAccessibilityServiceChanged() }
        }

        registerCloseReceiver()
        registerKeyguardLockedStateListener()
        registerTaskStackWatcher()
        registerLockSettingsObserver()
        ensurePrivilegedServicesEnabled()
        IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
        tryAttachWindows()
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = true)

        if (isUserUnlocked()) {
            mainHandler.postDelayed({
                ensurePrivilegedServicesEnabled()
                ctrl.ensureHardwareListeners()
                IosNotificationListenerService.instance?.refreshAll()
                upgradeToAccessibilityOverlayIfReady()
                tryAttachWindows()
                syncSystemKeyguardSuppression()
                syncAndroidShadeLockout()
                syncLockScreenVisibility()
                syncHomeBarVisibility(immediate = false)
            }, 650L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // Zero-overhead fast path for 90Hz Home Screen swipe frames (prevents window re-attachment / shade broadcast glitches!)
        if (action == ACTION_HOME_SWIPE) {
            handleHomeSwipeIntent(intent)
            return START_STICKY
        }

        if (action == ACTION_BOUNCER_RESULT) {
            if (intent.getStringExtra(EXTRA_INTERNAL_TOKEN) != LOCAL_IPC_TOKEN) {
                Log.w(TAG, "Rejected untrusted ACTION_BOUNCER_RESULT without intra-process token")
                return START_STICKY
            }
            handleBouncerResultInternal(intent.getBooleanExtra(EXTRA_UNLOCKED, false))
            return START_STICKY
        }

        ensurePrivilegedServicesEnabled()
        upgradeToAccessibilityOverlayIfReady()

        if ((triggerView == null && topGestureMonitor == null) ||
            (isUserUnlocked() && homeBarHostView == null) ||
            (isUserUnlocked() && panelHostView == null) ||
            (isUserUnlocked() && isA11yOverlayHealthy() && lockHostView == null)
        ) {
            tryAttachWindows()
        }
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = false)

        when (action) {
            ACTION_A11Y_CONNECTED -> {
                if (intent.getStringExtra(EXTRA_INTERNAL_TOKEN) == LOCAL_IPC_TOKEN) {
                    onAccessibilityServiceChanged()
                } else {
                    Log.w(TAG, "Rejected untrusted ACTION_A11Y_CONNECTED without intra-process token")
                }
            }

            ACTION_OPEN -> {
                activeSheetModeState.value = IosSheetMode.CONTROL_CENTER
                openPanelAnimated()
            }

            ACTION_OPEN_NOTIFICATIONS -> {
                if (canOpenNotificationCenterNow()) {
                    activeSheetModeState.value = IosSheetMode.NOTIFICATION_CENTER
                    IosNotificationListenerService.instance?.refreshAll()
                    openPanelAnimated()
                }
            }

            ACTION_CLOSE -> animatePanelTo(0f)

            ACTION_TOGGLE -> {
                if (openProgressState.floatValue > 0.5f) {
                    animatePanelTo(0f)
                } else {
                    activeSheetModeState.value = IosSheetMode.CONTROL_CENTER
                    openPanelAnimated()
                }
            }
        }
        return START_STICKY
    }

    private fun markSheetGestureActive(nowUptime: Long) {
        isSheetGestureActive = true
        lastGestureUptimeMs = nowUptime
        mainHandler.removeCallbacks(sheetGestureSafetyWatchdog)
        mainHandler.postDelayed(sheetGestureSafetyWatchdog, 1800L)
    }

    private fun clearSheetGestureActive() {
        isSheetGestureActive = false
        mainHandler.removeCallbacks(sheetGestureSafetyWatchdog)
    }

    private fun handleBouncerResultInternal(unlocked: Boolean) {
        if (activeInstance !== this) return
        mainHandler.removeCallbacks(postBouncerVerifyRunnable)
        bouncerVisualMaskHoldActive = false
        cancelBouncerRevealFade(resetHostAlpha = true)
        isLockCurtainUnlocking = false
        IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
        val nowUptime = SystemClock.uptimeMillis()
        val screenInteractive = getSystemService(PowerManager::class.java)?.isInteractive == true
        if (unlocked) {
            iosSwipeLockEngaged = false
            lastScreenOffUptimeMs = -10_000L
            clearSwipeLockBaselineTask()
            lastLockDismissUptimeMs = nowUptime
            val sysStillTransitioningUnlock = screenInteractive &&
                (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true)
            setBouncerPassthroughState(sysStillTransitioningUnlock)
            if (sysStillTransitioningUnlock) {
                mainHandler.postDelayed(postBouncerVerifyRunnable, 500L)
            }
        } else {
            lastBouncerCancelUptimeMs = nowUptime
            val km = getSystemService(KeyguardManager::class.java)
            val launchingOccludingApp = screenInteractive && km?.isKeyguardLocked == true && (
                queryKeyguardOccludingTopTask(nowUptime, isSystemLocked = true) ||
                    (a11yHasExternalFullscreenApp && !a11yHasFullscreenSystemShade)
                )
            setBouncerPassthroughState(launchingOccludingApp)
            if (screenInteractive) {
                // Schedule a follow-up check while interactive so EmergencyDialer / showWhenLocked
                // activities that enter the top task right after bouncer cancel are detected cleanly.
                mainHandler.postDelayed(postBouncerVerifyRunnable, 220L)
            }
        }
        secureCameraPassthroughActive = false
        secureCameraSawAppWindow = false
        lockCurtainAnimator?.cancel()
        lockCurtainProgressState.floatValue = 0f
        lockCurtainVelocityState.floatValue = 0f
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility(preArmWhileScreenOff = !screenInteractive)
        syncHomeBarVisibility(immediate = true)
        syncControllerPanelVisibility()
    }

    private fun handleHomeSwipeIntent(intent: Intent) {
        val nowUptime = SystemClock.uptimeMillis()
        val finished = intent.getBooleanExtra(EXTRA_FINISHED, false)
        val modeStr = intent.getStringExtra(EXTRA_MODE)
        val requestedMode = if (modeStr == MODE_NOTIFICATIONS) {
            IosSheetMode.NOTIFICATION_CENTER
        } else {
            IosSheetMode.CONTROL_CENTER
        }

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        if (sessionId != 0L && sessionId != lastHomeSwipeSessionId) {
            lastHomeSwipeSessionId = sessionId
            lastHomeSwipeSeq = -1L
        }

        val blockedByGuard = isIosLockScreenShowing() ||
            (requestedMode == IosSheetMode.NOTIFICATION_CENTER && !canOpenNotificationCenterNow()) ||
            (nowUptime - lastLockDismissUptimeMs) < 350L ||
            isTopBezelGestureActive ||
            (nowUptime - lastTopBezelGestureUptimeMs) < 350L ||
            isCriticalInterruptBlockingSheetNow(nowUptime)
        if (blockedByGuard) {
            if (finished && isSheetGestureActive && !isTopBezelGestureActive && !isBottomSpyClosingSheet) {
                clearSheetGestureActive()
                animatePanelTo(0f)
            }
            return
        }
        val seq = intent.getLongExtra(EXTRA_SEQ, -1L)
        if (seq > 0L) {
            if (seq < lastHomeSwipeSeq) {
                // Drop stale out-of-order frame
                return
            }
            lastHomeSwipeSeq = seq
        }

        val progress = intent.getFloatExtra(EXTRA_PROGRESS, -1f)
        val shouldOpen = intent.getBooleanExtra(EXTRA_SHOULD_OPEN, true)
        val velocityY = intent.getFloatExtra(EXTRA_VELOCITY_Y, 0f)

        lastGestureUptimeMs = nowUptime
        lastTopOpenGestureUptimeMs = nowUptime
        sheetVelocityState.floatValue = velocityY

        if (finished) {
            clearSheetGestureActive()
            if (shouldOpen) {
                if (panelHostView == null) {
                    tryAttachPanelWindow()
                    publishLiveDiagnosticStatus()
                }
                if (panelHostView == null) {
                    openProgressState.floatValue = 0f
                    sheetVelocityState.floatValue = 0f
                    syncControllerPanelVisibility()
                    return
                }
                val modeChanged = activeSheetModeState.value != requestedMode
                val startingFromClosed = openProgressState.floatValue <= 0.02f ||
                    panelHostView?.visibility != View.VISIBLE
                if (startingFromClosed || modeChanged) {
                    activeSheetModeState.value = requestedMode
                    if (requestedMode == IosSheetMode.NOTIFICATION_CENTER) {
                        IosNotificationListenerService.instance?.refreshAll()
                    }
                    syncAndroidShadeLockout()
                    controller?.onPanelOpening()
                }
            }
            animatePanelTo(if (shouldOpen) 1f else 0f, initialVelocityPxPerSec = velocityY)
        } else if (progress >= 0f) {
            markSheetGestureActive(nowUptime)
            if (panelHostView == null) {
                tryAttachPanelWindow()
            }
            if (panelHostView == null) {
                clearSheetGestureActive()
                return
            }
            progressAnimator?.cancel()
            val modeChanged = activeSheetModeState.value != requestedMode
            val startingFromClosed = openProgressState.floatValue <= 0.02f ||
                panelHostView?.visibility != View.VISIBLE
            if (startingFromClosed || modeChanged) {
                activeSheetModeState.value = requestedMode
                if (requestedMode == IosSheetMode.NOTIFICATION_CENTER) {
                    IosNotificationListenerService.instance?.refreshAll()
                }
                controller?.onPanelOpening()
            }
            openProgressState.floatValue = progress.coerceIn(0.002f, 1f)
            syncHomeBarVisibility(immediate = true)
            ensurePanelVisibleForDrag()
        } else {
            clearSheetGestureActive()
            activeSheetModeState.value = requestedMode
            if (requestedMode == IosSheetMode.NOTIFICATION_CENTER) {
                IosNotificationListenerService.instance?.refreshAll()
            }
            openPanelAnimated()
        }
    }

    private fun handleInteractiveSheetCloseDelta(dyPx: Float) {
        val nowUptime = SystemClock.uptimeMillis()
        if (isBottomSpyClosingSheet || (nowUptime - lastBottomSpySheetSettleUptimeMs) < 240L) return
        progressAnimator?.cancel()
        if (!isSheetGestureActive) {
            interactiveCloseStartProgress = openProgressState.floatValue.coerceIn(0.002f, 1f)
            lastInteractiveCloseMoveUptimeMs = nowUptime
        } else {
            val dtMs = (nowUptime - lastInteractiveCloseMoveUptimeMs).coerceAtLeast(4L)
            val instVel = (dyPx * 1000f) / dtMs.toFloat()
            sheetVelocityState.floatValue = sheetVelocityState.floatValue * 0.35f + instVel * 0.65f
            lastInteractiveCloseMoveUptimeMs = nowUptime
        }
        markSheetGestureActive(nowUptime)
        val screenH = readCurrentFullScreenHeightPx()
        val next = (openProgressState.floatValue + (dyPx / screenH)).coerceIn(0.002f, 1f)
        openProgressState.floatValue = next
        syncHomeBarVisibility(immediate = true)
    }

    private fun handleInteractiveSheetCloseEnd(velocityYPxPerSec: Float, fromBottomSpy: Boolean = false) {
        val nowUptime = SystemClock.uptimeMillis()
        if (!fromBottomSpy && (isBottomSpyClosingSheet || (nowUptime - lastBottomSpySheetSettleUptimeMs) < 240L)) {
            return
        }
        if (fromBottomSpy) {
            lastBottomSpySheetSettleUptimeMs = nowUptime
        }
        isBottomSpyClosingSheet = false
        clearSheetGestureActive()
        lastGestureUptimeMs = nowUptime
        lastInteractiveCloseMoveUptimeMs = 0L
        sheetVelocityState.floatValue = velocityYPxPerSec
        val density = readCurrentScreenMetrics().density.coerceAtLeast(1f)
        val cur = openProgressState.floatValue
        val startProg = interactiveCloseStartProgress.coerceIn(cur, 1f)
        val closeDragFrac = max(startProg - cur, 1f - cur)
        val shouldClose = closeDragFrac >= 0.18f || velocityYPxPerSec <= -650f * density
        interactiveCloseStartProgress = 1f
        animatePanelTo(if (shouldClose) 0f else 1f, initialVelocityPxPerSec = velocityYPxPerSec)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateHomeBarDimensionsForDisplay()
        updateTriggerDimensionsForDisplay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        IosSystemOverlayAccessibilityService.onConnectedCallback = null
        secureBouncerCoordinator?.cancelActiveSession()
        secureBouncerCoordinator = null
        bouncerVisualMaskHoldActive = false
        cancelBouncerRevealFade(resetHostAlpha = true)
        homeBarFadeAnimator?.cancel()
        homeBarSpringAnimator?.cancel()
        progressAnimator?.cancel()
        lockCurtainAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        disposeTopGestureMonitor()
        releaseSystemKeyguardSuppression()
        syncAndroidShadeLockout(forceRelease = true)
        unregisterTaskStackWatcher()
        unregisterKeyguardLockedStateListener()
        unregisterLockSettingsObserver()
        unregisterCloseReceiver()
        removeWindowsSafely()
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        controller?.destroy()
        controller = null
        super.onDestroy()
    }

    private fun readGesturePillEnabled(): Boolean {
        val cr = contentResolver
        try {
            val gVal = Settings.Global.getInt(cr, "samurai_gesture_pill_enabled", -1)
            if (gVal == 0 || gVal == 1) return gVal == 1
        } catch (_: Throwable) {
        }
        try {
            val sVal = Settings.Secure.getInt(cr, "samurai_gesture_pill_enabled", -1)
            if (sVal == 0 || sVal == 1) return sVal == 1
        } catch (_: Throwable) {
        }
        try {
            val iosVal = Settings.Secure.getInt(cr, "samurai_ios26_gesture_pill", -1)
            if (iosVal == 0 || iosVal == 1) return iosVal == 1
        } catch (_: Throwable) {
        }
        return true
    }

    private fun readLauncherBarState(): Int {
        return try {
            Settings.Global.getInt(contentResolver, "samurai_launcher_bar_state", 0).coerceIn(0, 2)
        } catch (_: Throwable) {
            0
        }
    }

    private fun isActiveImePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        try {
            val defIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.trim()
            if (!defIme.isNullOrEmpty() && defIme == pkg) {
                return true
            }
        } catch (_: Throwable) {
        }
        return try {
            val imm = getSystemService(InputMethodManager::class.java)
                ?: (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
        } catch (_: Throwable) {
            false
        }
    }

    fun onAccessibilityWindowSignals(
        imeVisible: Boolean,
        topWindowPkg: String?,
        hasExternalFullscreenApp: Boolean = false,
        hasFullscreenSystemShade: Boolean = false,
        alternateBouncerVisible: Boolean = false,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                onAccessibilityWindowSignals(
                    imeVisible = imeVisible,
                    topWindowPkg = topWindowPkg,
                    hasExternalFullscreenApp = hasExternalFullscreenApp,
                    hasFullscreenSystemShade = hasFullscreenSystemShade,
                    alternateBouncerVisible = alternateBouncerVisible,
                )
            }
            return
        }
        a11yImeVisible = imeVisible
        val prevExtApp = a11yHasExternalFullscreenApp
        val prevShade = a11yHasFullscreenSystemShade
        a11yHasExternalFullscreenApp = hasExternalFullscreenApp
        a11yHasFullscreenSystemShade = hasFullscreenSystemShade

        val nowUptime = SystemClock.uptimeMillis()
        if (imeVisible && bouncerPassthrough) {
            secureBouncerCoordinator?.notifyUserActivity(nowUptime)
        }
        if (alternateBouncerVisible && bouncerPassthrough && !secureCameraPassthroughActive) {
            val dm = readCurrentScreenMetrics()
            val screenW = dm.widthPixels.toFloat().coerceAtLeast(360f)
            val screenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
            secureBouncerCoordinator?.onAlternateBouncerDetected(screenW, screenH)
        }
        val km = getSystemService(KeyguardManager::class.java)
        val isSysLocked = km?.isKeyguardLocked == true
        dismissTopSheetIfCriticalInterrupt(nowUptime = nowUptime, isSystemLocked = isSysLocked)
        val taskOccluding = queryKeyguardOccludingTopTask(nowUptime, isSystemLocked = isSysLocked)
        if (secureCameraPassthroughActive) {
            if (hasExternalFullscreenApp || taskOccluding) {
                secureCameraSawAppWindow = true
            } else if (secureCameraSawAppWindow || (nowUptime - secureCameraLaunchUptimeMs) > 1500L) {
                secureCameraPassthroughActive = false
                secureCameraSawAppWindow = false
                setBouncerPassthroughState(false)
                syncLockScreenVisibility()
            }
        } else if (isSysLocked || iosSwipeLockEngaged) {
            val lockCurrentlyShown = lockHostView?.visibility == View.VISIBLE
            if (prevExtApp != hasExternalFullscreenApp ||
                prevShade != hasFullscreenSystemShade ||
                (taskOccluding && lockCurrentlyShown) ||
                (!taskOccluding && !lockCurrentlyShown)
            ) {
                syncLockScreenVisibility()
            }
        }

        if (topWindowPkg != null) {
            if (topWindowPkg == NEXUS_LAUNCHER_PKG) {
                lastObservedTopPkg = topWindowPkg
                mainHandler.removeCallbacks(homeBarPostTouchReconcileRunnable)
                launcherBarState = readLauncherBarState()
            } else if (topWindowPkg !in HOME_BAR_IGNORED_TOP_PKGS &&
                !isActiveImePackage(topWindowPkg)
            ) {
                lastObservedTopPkg = topWindowPkg
                mainHandler.removeCallbacks(homeBarPostTouchReconcileRunnable)
                if (launcherBarState == 1) {
                    launcherBarState = 0
                }
            }
        }
        syncHomeBarVisibility(immediate = false)
    }

    private fun isA11yOverlayHealthy(): Boolean {
        val svc = IosSystemOverlayAccessibilityService.instance ?: return false
        return System.identityHashCode(svc) != lastFailedA11yIdentity
    }

    private fun isLockSwipeOwnedBySpyOrUnlocking(): Boolean {
        return isBottomSpyUnlockingLockScreen ||
            isLockCurtainUnlocking ||
            bouncerPassthrough ||
            bouncerVisualMaskHoldActive ||
            (SystemClock.uptimeMillis() - lastBottomSpyLockSettleUptimeMs) < 240L
    }

    @Suppress("DEPRECATION")
    private fun captureSwipeLockBaselineTask() {
        try {
            val am = getSystemService(ActivityManager::class.java)
                ?: (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            val topTask = am?.getRunningTasks(1)?.firstOrNull()
            val comp = topTask?.topActivity ?: topTask?.baseActivity
            swipeLockInitialTaskId = topTask?.taskId ?: -1
            swipeLockInitialComp = comp?.flattenToShortString()
        } catch (_: Throwable) {
            swipeLockInitialTaskId = -1
            swipeLockInitialComp = null
        }
    }

    private fun clearSwipeLockBaselineTask() {
        swipeLockInitialTaskId = -1
        swipeLockInitialComp = null
        lastCameraLaunchDetectedUptimeMs = -10_000L
    }

    private fun isCameraComponent(pkg: String, cls: String): Boolean {
        return pkg == "com.google.android.GoogleCameraEng" ||
            cls.contains("Camera", ignoreCase = true)
    }

    fun onCameraLaunchDetected(reason: String) {
        val now = SystemClock.uptimeMillis()
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        val interactive = pm?.isInteractive == true
        if (!interactive ||
            iosSwipeLockEngaged ||
            km?.isKeyguardLocked == true ||
            (now - lastScreenOffUptimeMs) < 1500L
        ) {
            lastCameraLaunchDetectedUptimeMs = now
            Log.i(TAG, "onCameraLaunchDetected($reason) at uptime=$now (interactive=$interactive, swipeLock=$iosSwipeLockEngaged)")
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dismissTopSheetIfCriticalInterrupt()
                syncLockScreenVisibility()
            }
        } else {
            dismissTopSheetIfCriticalInterrupt()
            syncLockScreenVisibility()
        }
    }

    fun onNotificationInterruptChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dismissTopSheetIfCriticalInterrupt()
                syncLockScreenVisibility()
                syncControllerPanelVisibility()
            }
        } else {
            dismissTopSheetIfCriticalInterrupt()
            syncLockScreenVisibility()
            syncControllerPanelVisibility()
        }
    }

    private fun readCurrentAudioMode(): Int {
        return try {
            val am = getSystemService(AudioManager::class.java)
                ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            am?.mode ?: AudioManager.MODE_NORMAL
        } catch (_: Throwable) {
            AudioManager.MODE_NORMAL
        }
    }

    private fun isAudioRingtoneOrSimCallActive(): Boolean {
        val mode = readCurrentAudioMode()
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
    }

    private fun isAudioVoipCommunicationActive(): Boolean {
        val mode = readCurrentAudioMode()
        return mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                mode == AudioManager.MODE_COMMUNICATION_REDIRECT)
    }

    private fun isAudioRingtoneOrCallActive(): Boolean {
        return isAudioRingtoneOrSimCallActive() || isAudioVoipCommunicationActive()
    }

    private fun registerTaskStackWatcher() {
        if (taskStackListener != null) return
        try {
            val watcher = object : TaskStackListener() {
                override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
                    val comp = componentName ?: return
                    if (isCameraComponent(comp.packageName, comp.className)) {
                        onCameraLaunchDetected("taskCreated")
                    } else if (
                        isUnconditionallyCriticalComponent(comp.packageName, comp.className) ||
                        comp.packageName in IosNotificationRepository.activeInterruptPackages ||
                        isAudioRingtoneOrCallActive()
                    ) {
                        mainHandler.post {
                            dismissTopSheetIfCriticalInterrupt()
                            syncLockScreenVisibility()
                            syncControllerPanelVisibility()
                        }
                    }
                }

                override fun onActivityRestartAttempt(
                    task: ActivityManager.RunningTaskInfo,
                    homeTaskVisible: Boolean,
                    clearedTask: Boolean,
                    wasVisible: Boolean,
                ) {
                    val comp = task.topActivity ?: task.baseActivity ?: return
                    if (isCameraComponent(comp.packageName, comp.className)) {
                        onCameraLaunchDetected("restartAttempt")
                    } else if (
                        isUnconditionallyCriticalComponent(comp.packageName, comp.className) ||
                        comp.packageName in IosNotificationRepository.activeInterruptPackages ||
                        isAudioRingtoneOrCallActive()
                    ) {
                        mainHandler.post {
                            dismissTopSheetIfCriticalInterrupt()
                            syncLockScreenVisibility()
                            syncControllerPanelVisibility()
                        }
                    }
                }

                override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                    val comp = taskInfo.topActivity ?: taskInfo.baseActivity
                    mainHandler.post {
                        if (iosSwipeLockEngaged ||
                            lastKnownKeyguardLocked ||
                            isAudioRingtoneOrCallActive() ||
                            (comp != null && (
                                isUnconditionallyCriticalComponent(comp.packageName, comp.className) ||
                                    comp.packageName in IosNotificationRepository.activeInterruptPackages
                                ))
                        ) {
                            dismissTopSheetIfCriticalInterrupt()
                            syncLockScreenVisibility()
                            syncControllerPanelVisibility()
                        }
                    }
                }
            }
            ActivityTaskManager.getInstance().registerTaskStackListener(watcher)
            taskStackListener = watcher
        } catch (t: Throwable) {
            Log.w(TAG, "registerTaskStackWatcher failed: ${t.message}")
        }
    }

    private fun unregisterTaskStackWatcher() {
        val watcher = taskStackListener ?: return
        taskStackListener = null
        try {
            ActivityTaskManager.getInstance().unregisterTaskStackListener(watcher)
        } catch (_: Throwable) {
        }
    }

    private fun isUnconditionallyCriticalComponent(topPkg: String, topCls: String): Boolean {
        return topCls.contains("AlarmActivity", ignoreCase = true) ||
            topCls.contains("AlarmAlert", ignoreCase = true) ||
            topCls.contains("AlarmKlaxon", ignoreCase = true) ||
            topCls.contains("AlarmRing", ignoreCase = true) ||
            topCls.contains("AlarmDismiss", ignoreCase = true) ||
            topCls.contains("FullScreenAlarm", ignoreCase = true) ||
            topCls.contains("ReminderActivity", ignoreCase = true) ||
            topCls.contains("AlertActivity", ignoreCase = true) ||
            topCls.contains("ExpiredTimers", ignoreCase = true) ||
            topCls.contains("TimerAlert", ignoreCase = true) ||
            topPkg == "com.android.emergency" ||
            topPkg == "com.android.server.telecom" ||
            topPkg == "com.android.incallui" ||
            topPkg == "com.android.cellbroadcastreceiver" ||
            topPkg == "com.google.android.cellbroadcastreceiver" ||
            topCls.contains("CellBroadcastAlert", ignoreCase = true) ||
            topCls.contains("Emergency", ignoreCase = true) ||
            topCls.contains("InCall", ignoreCase = true) ||
            topCls.contains("CallActivity", ignoreCase = true) ||
            topCls.contains("IncomingCall", ignoreCase = true) ||
            topCls.contains("RingActivity", ignoreCase = true) ||
            topCls.contains("RtcActivity", ignoreCase = true) ||
            topCls.contains("Voip", ignoreCase = true) ||
            topCls.contains("SecureCamera", ignoreCase = true) ||
            topCls.contains("CameraImageActivity", ignoreCase = true) ||
            topCls.contains("CameraVideoShortcutActivity", ignoreCase = true) ||
            (topPkg == "com.google.android.apps.safetyhub" && (
                topCls.contains("Sos", ignoreCase = true) ||
                    topCls.contains("Countdown", ignoreCase = true) ||
                    topCls.contains("Slider", ignoreCase = true)
                ))
    }

    @Suppress("DEPRECATION")
    private fun queryCriticalTopSheetInterruptingTask(nowUptime: Long): Boolean {
        return try {
            val am = getSystemService(ActivityManager::class.java)
                ?: (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?: return false
            val topTask = am.getRunningTasks(2)?.firstOrNull() ?: return false
            val topComp = topTask.topActivity ?: topTask.baseActivity ?: return false
            val topPkg = topComp.packageName
            val topCls = topComp.className
            if (topPkg == packageName ||
                topPkg == NEXUS_LAUNCHER_PKG ||
                topPkg == "com.android.systemui"
            ) {
                return false
            }
            val isInterrupting = isUnconditionallyCriticalComponent(topPkg, topCls) ||
                topPkg in IosNotificationRepository.activeInterruptPackages ||
                isAudioRingtoneOrCallActive()
            if (!isInterrupting) {
                return false
            }
            topTask.isVisible ||
                isTaskVisibleRequested(topTask) ||
                (nowUptime - lastBouncerCancelUptimeMs) < 800L
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCriticalInterruptBlockingSheetNow(
        nowUptime: Long = SystemClock.uptimeMillis(),
        isSystemLocked: Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true,
    ): Boolean {
        if (isInPhoneCall()) return true
        if (queryCriticalTopSheetInterruptingTask(nowUptime)) return true
        if ((isSystemLocked || iosSwipeLockEngaged) &&
            queryKeyguardOccludingTopTask(nowUptime, isSystemLocked = isSystemLocked)
        ) {
            return true
        }
        return false
    }

    private fun dismissTopSheetIfCriticalInterrupt(
        nowUptime: Long = SystemClock.uptimeMillis(),
        isSystemLocked: Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true,
    ): Boolean {
        if (!isCriticalInterruptBlockingSheetNow(nowUptime = nowUptime, isSystemLocked = isSystemLocked)) {
            return false
        }
        if (openProgressState.floatValue > 0f ||
            panelHostView?.visibility == View.VISIBLE ||
            isSheetGestureActive
        ) {
            closePanelImmediate()
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun queryKeyguardOccludingTopTask(
        nowUptime: Long,
        isSystemLocked: Boolean,
    ): Boolean {
        return try {
            val am = getSystemService(ActivityManager::class.java)
                ?: (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?: return false
            val topTask = am.getRunningTasks(2)?.firstOrNull() ?: return false
            val topComp = topTask.topActivity ?: topTask.baseActivity ?: return false
            val topPkg = topComp.packageName
            val topCls = topComp.className
            if (topPkg == packageName ||
                topPkg == NEXUS_LAUNCHER_PKG ||
                topPkg == "com.android.systemui"
            ) {
                return false
            }

            val taskVisible = topTask.isVisible
            val taskVisRequested = isTaskVisibleRequested(topTask)
            val flatComp = topComp.flattenToShortString()
            val isCameraTask = isCameraComponent(topPkg, topCls)
            if (!isCameraTask &&
                (nowUptime - lastScreenOffUptimeMs) > 1200L &&
                (nowUptime - lastCameraLaunchDetectedUptimeMs) > 1200L
            ) {
                lastCameraLaunchDetectedUptimeMs = -10_000L
            }
            val cameraGestureForSession = isCameraTask &&
                lastCameraLaunchDetectedUptimeMs > 0L &&
                lastCameraLaunchDetectedUptimeMs >= (lastScreenOffUptimeMs - 600L)
            val newlyLaunchedAfterScreenOff = iosSwipeLockEngaged &&
                (flatComp != swipeLockInitialComp || topTask.taskId != swipeLockInitialTaskId || cameraGestureForSession)

            val isCriticalLockScreenTask =
                isUnconditionallyCriticalComponent(topPkg, topCls) ||
                    topPkg in IosNotificationRepository.activeInterruptPackages ||
                    isAudioRingtoneOrSimCallActive() ||
                    (isAudioVoipCommunicationActive() && (isSystemLocked || newlyLaunchedAfterScreenOff)) ||
                    (isSystemLocked && (
                        topPkg == "com.android.phone" ||
                            topPkg == "com.google.android.apps.safetyhub"
                        )) ||
                    (!isSystemLocked && newlyLaunchedAfterScreenOff && (
                        topPkg == "com.google.android.apps.safetyhub" ||
                            isCameraTask
                        ))

            if (isCriticalLockScreenTask) {
                return taskVisible ||
                    taskVisRequested ||
                    (isSystemLocked && (nowUptime - lastBouncerCancelUptimeMs) < 800L)
            }

            if (!isSystemLocked) {
                // In non-secure Swipe mode (`iosSwipeLockEngaged`), normal pre-existing apps remain visible
                // beneath our overlay for 0ms instant unlock without falsely occluding Swipe lock.
                return false
            }

            if ((nowUptime - lastScreenOffUptimeMs) >= 650L &&
                (nowUptime - lastKeyguardLockedTransitionUptimeMs) >= 650L &&
                taskVisible
            ) {
                return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isKeyguardOccludedByExternalApp(
        isSystemLocked: Boolean,
        preArmWhileScreenOff: Boolean,
    ): Boolean {
        if (preArmWhileScreenOff) return false
        if (!isSystemLocked && !iosSwipeLockEngaged) return false
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isInteractive != true) return false
        val nowUptime = SystemClock.uptimeMillis()
        if (queryKeyguardOccludingTopTask(nowUptime, isSystemLocked = isSystemLocked)) {
            return true
        }
        if (!isSystemLocked) return false
        if (!a11yHasFullscreenSystemShade && (nowUptime - lastScreenOffUptimeMs) < 650L) {
            return false
        }
        return a11yHasExternalFullscreenApp
    }

    private fun canOpenNotificationCenterNow(): Boolean {
        if (!isUserUnlocked() || isIosLockScreenShowing()) return false
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked != true
    }

    private fun isDeviceOrSwipeLocked(): Boolean {
        if (!isUserUnlocked()) return true
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true || iosSwipeLockEngaged || lockHostView != null
    }

    private fun isTopPullDownAllowedNow(): Boolean {
        if (!isUserUnlocked()) return false
        val nowUptime = SystemClock.uptimeMillis()
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isSysLocked = km?.isKeyguardLocked == true
        if ((isSysLocked || iosSwipeLockEngaged) && !isA11yOverlayHealthy()) {
            return false
        }
        if (bouncerPassthrough ||
            isCriticalInterruptBlockingSheetNow(nowUptime = nowUptime, isSystemLocked = isSysLocked) ||
            !isSwipeGestureAllowed()
        ) {
            return false
        }
        if (isKeyguardOccludedByExternalApp(isSysLocked, preArmWhileScreenOff = false)) {
            return false
        }
        return true
    }

    private fun registerKeyguardLockedStateListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || keyguardLockedListener != null) return
        try {
            val km = getSystemService(KeyguardManager::class.java) ?: return
            lastKnownKeyguardLocked = km.isKeyguardLocked
            val listener = KeyguardManager.KeyguardLockedStateListener { isLocked ->
                val nowUptime = SystemClock.uptimeMillis()
                if (isLocked && !lastKnownKeyguardLocked) {
                    lastKeyguardLockedTransitionUptimeMs = nowUptime
                    mainHandler.removeCallbacks(postKeyguardLockVerifyRunnable)
                    mainHandler.postDelayed(postKeyguardLockVerifyRunnable, 700L)
                } else if (!isLocked && !km.isKeyguardLocked) {
                    mainHandler.removeCallbacks(postKeyguardLockVerifyRunnable)
                    mainHandler.removeCallbacks(postBouncerVerifyRunnable)
                    secureBouncerCoordinator?.onUserPresentOrKeyguardUnlocked()
                    bouncerVisualMaskHoldActive = false
                    cancelBouncerRevealFade(resetHostAlpha = true)
                    isLockCurtainUnlocking = false
                    setBouncerPassthroughState(false)
                    secureCameraPassthroughActive = false
                    secureCameraSawAppWindow = false
                }
                lastKnownKeyguardLocked = km.isKeyguardLocked
                IosSystemOverlayAccessibilityService.instance?.resyncWindowSignals()
                syncSystemKeyguardSuppression()
                syncAndroidShadeLockout()
                syncLockScreenVisibility()
                syncHomeBarVisibility(immediate = false)
            }
            km.addKeyguardLockedStateListener(mainHandler::post, listener)
            keyguardLockedListener = listener
        } catch (t: Throwable) {
            Log.w(TAG, "registerKeyguardLockedStateListener failed: ${t.message}")
        }
    }

    private fun unregisterKeyguardLockedStateListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val listener = keyguardLockedListener ?: return
        keyguardLockedListener = null
        try {
            getSystemService(KeyguardManager::class.java)?.removeKeyguardLockedStateListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun readCurrentFullScreenHeightPx(): Float {
        return readCurrentScreenMetrics().heightPixels.toFloat().coerceAtLeast(640f)
    }

    private fun isUserUnlocked(): Boolean {
        return try {
            getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (_: Throwable) {
            true
        }
    }

    private fun isLockScreenEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, "samurai_ios26_lockscreen", 1) == 1
        } catch (_: Throwable) {
            true
        }
    }

    private fun isInPhoneCall(): Boolean {
        try {
            val tm = getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION")
            val callState = tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                return true
            }
        } catch (_: Throwable) {
        }
        return try {
            val telecom = getSystemService(TelecomManager::class.java)
                ?: (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            telecom?.isInCall == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Auto-enables our zero-overhead [IosSystemOverlayAccessibilityService] (for Z-layer 31
     * TYPE_ACCESSIBILITY_OVERLAY above StatusBar & NotificationShade) and [IosNotificationListenerService]
     * (for live notifications in the left-side iOS 26 Notification Center & Lock Screen).
     */
    private fun ensurePrivilegedServicesEnabled() {
        val cr = contentResolver
        try {
            val a11yComp = "$packageName/${IosSystemOverlayAccessibilityService::class.java.name}"
            val existing = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val parts = existing.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!parts.contains(a11yComp)) {
                parts.add(a11yComp)
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    parts.joinToString(":"),
                )
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensurePrivilegedServicesEnabled (a11y): ${t.message}")
        }

        if (isUserUnlocked()) {
            try {
                val nlComp = "$packageName/${IosNotificationListenerService::class.java.name}"
                val key = "enabled_notification_listeners"
                val existing = Settings.Secure.getString(cr, key).orEmpty()
                val parts = existing.split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                var changed = false
                if (!parts.contains(nlComp)) {
                    parts.add(nlComp)
                    Settings.Secure.putString(cr, key, parts.joinToString(":"))
                    changed = true
                }
                if (changed || !IosNotificationRepository.listenerConnected) {
                    NotificationListenerService.requestRebind(
                        ComponentName(this, IosNotificationListenerService::class.java)
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ensurePrivilegedServicesEnabled (notif): ${t.message}")
            }
        }
    }

    /**
     * Suppresses stock Android SystemUI's insecure `NotificationShade` keyguard ONLY when:
     *   - User storage is unlocked (`isUserUnlocked()`),
     *   - `samurai_ios26_lockscreen == 1` (`isLockScreenEnabled()`), AND
     *   - Device security is None/Swipe (`!km.isKeyguardSecure`).
     * If the user sets a PIN/Pattern/Password (`km.isKeyguardSecure == true`) or disables the iOS 26
     * Lock Screen in iOS Styler, native SystemUI Keyguard + Goodix UDFPS is immediately re-enabled.
     */
    @Suppress("DEPRECATION")
    private fun syncSystemKeyguardSuppression() {
        try {
            val km = getSystemService(KeyguardManager::class.java)
                ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            val secure = km?.isKeyguardSecure == true
            isSecureKeyguardState.value = secure
            hasEnrolledUdfpsState.value = if (secure) UdfpsSensorGeometry.hasEnrolledUdfps(this) else false
            syncLockUdfpsApertureIfChanged()

            val unlocked = isUserUnlocked()
            val lockEnabled = isLockScreenEnabled()
            val sheetIdle = !isSheetGestureActive &&
                openProgressState.floatValue <= 0.01f &&
                panelHostView?.visibility != View.VISIBLE
            if (unlocked && lockEnabled && !secure && isA11yOverlayHealthy() && lockHostView == null && sheetIdle) {
                tryAttachWindows()
            }
            val lockOverlayReady = lockHostView != null &&
                lockParams?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val shouldSuppress = unlocked && lockEnabled && !secure && isA11yOverlayHealthy() && lockOverlayReady

            if (shouldSuppress && !systemKeyguardSuppressed && km != null) {
                val wasLocked = km.isKeyguardLocked
                val lock = systemKeyguardLock ?: km.newKeyguardLock("SamuraiIos26LockScreen").also {
                    systemKeyguardLock = it
                }
                lock.disableKeyguard()
                systemKeyguardSuppressed = true
                if (wasLocked) {
                    captureSwipeLockBaselineTask()
                    iosSwipeLockEngaged = true
                }
                Log.i(TAG, "Suppressed stock insecure Android keyguard (SamuraiIos26LockScreen owns Swipe lock)")
                publishLiveDiagnosticStatus()
            } else if (!shouldSuppress) {
                iosSwipeLockEngaged = false
                clearSwipeLockBaselineTask()
                if (systemKeyguardSuppressed) {
                    systemKeyguardLock?.reenableKeyguard()
                    systemKeyguardSuppressed = false
                    Log.i(
                        TAG,
                        "Re-enabled stock Android keyguard (secure=$secure, enabled=$lockEnabled, lockOverlayReady=$lockOverlayReady)"
                    )
                    publishLiveDiagnosticStatus()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "syncSystemKeyguardSuppression failed: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun releaseSystemKeyguardSuppression() {
        iosSwipeLockEngaged = false
        clearSwipeLockBaselineTask()
        if (!systemKeyguardSuppressed) return
        systemKeyguardSuppressed = false
        try {
            systemKeyguardLock?.reenableKeyguard()
        } catch (_: Throwable) {
        }
    }

    /**
     * Prevents stock Android SystemUI Notification Shade & Quick Settings from fighting with
     * our left-side iOS 26 Notification Center, right-side iOS 26 Control Center, or non-secure
     * iOS 26 Lock Screen (`iosSwipeLockEngaged`).
     * Automatically releases (`disable(0)`) while a secure PIN/Pattern/Goodix UDFPS keyguard is locked.
     */
    @SuppressLint("WrongConstant")
    private fun syncAndroidShadeLockout(forceRelease: Boolean = false) {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val secureKeyguardLocked = (km?.isKeyguardLocked == true) && (km.isKeyguardSecure || bouncerPassthrough)
            val shouldLockOut = !forceRelease &&
                !secureKeyguardLocked &&
                (iosSwipeLockEngaged || isSwipeGestureAllowed())
            if (androidShadeLockedOut == shouldLockOut && statusBarManagerObj != null && !forceRelease) {
                return
            }

            val sbm = statusBarManagerObj ?: getSystemService("statusbar")?.also {
                statusBarManagerObj = it
            } ?: return

            val flags1 = if (shouldLockOut) STATUS_BAR_DISABLE_EXPAND else 0
            sbm.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(sbm, flags1)
            androidShadeLockedOut = shouldLockOut
        } catch (t: Throwable) {
            Log.w(TAG, "syncAndroidShadeLockout failed: ${t.message}")
        }
    }

    private fun areAllAttachedWindowsAccessibilityOverlay(): Boolean {
        if (activeWindowType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return false
        if (homeBarHostView != null && homeBarParams?.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            return false
        }
        if (panelHostView != null && panelParams?.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            return false
        }
        if (triggerView != null && triggerParams?.type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            return false
        }
        return true
    }

    private fun onAccessibilityServiceChanged() {
        if (activeInstance !== this) return
        val a11ySvc = IosSystemOverlayAccessibilityService.instance
        val a11yReady = a11ySvc != null
        val svcId = if (a11ySvc != null) System.identityHashCode(a11ySvc) else 0
        val tokenChanged = a11yReady && attachedA11yIdentity != 0 && svcId != attachedA11yIdentity
        Log.i(TAG, "onAccessibilityServiceChanged ready=$a11yReady activeWindowType=$activeWindowType tokenChanged=$tokenChanged")
        a11ySvc?.resyncWindowSignals()
        val needsUpgrade = a11yReady && (
            tokenChanged ||
                !areAllAttachedWindowsAccessibilityOverlay() ||
                (isUserUnlocked() && lockHostView == null)
            )
        if (needsUpgrade) {
            lastFailedA11yIdentity = 0
            upgradeToAccessibilityOverlayIfReady()
        } else if (!a11yReady && activeWindowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            removeWindowsSafely()
            tryAttachWindows()
        }
        syncSystemKeyguardSuppression()
        syncAndroidShadeLockout()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = false)
    }

    private fun upgradeToAccessibilityOverlayIfReady() {
        val a11ySvc = IosSystemOverlayAccessibilityService.instance ?: return
        val svcId = System.identityHashCode(a11ySvc)
        if (svcId == lastFailedA11yIdentity) {
            // Prevent window teardown loop if addView(2032) failed on this service instance
            return
        }
        val unlocked = isUserUnlocked()
        val tokenCurrent = attachedA11yIdentity == 0 || attachedA11yIdentity == svcId
        if (areAllAttachedWindowsAccessibilityOverlay() &&
            tokenCurrent &&
            (!unlocked || homeBarHostView != null) &&
            (triggerView != null || topGestureMonitor != null) &&
            (!unlocked || panelHostView != null) &&
            (!unlocked || lockHostView != null)
        ) {
            return
        }
        if (isSheetGestureActive || openProgressState.floatValue > 0.02f) {
            return
        }
        Log.i(TAG, "Upgrading windows to TYPE_ACCESSIBILITY_OVERLAY (2032, Z-layer 31)")
        removeWindowsSafely()
        tryAttachWindows()
        syncLockScreenVisibility()
        syncHomeBarVisibility(immediate = false)
    }

    private fun registerLockSettingsObserver() {
        if (settingsObserverRegistered) return
        try {
            val cr = contentResolver
            cr.registerContentObserver(
                Settings.Global.getUriFor("samurai_ios26_lockscreen"),
                false,
                lockSettingsObserver,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor("samurai_launcher_bar_state"),
                false,
                lockSettingsObserver,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor("samurai_gesture_pill_enabled"),
                false,
                lockSettingsObserver,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("samurai_gesture_pill_enabled"),
                false,
                lockSettingsObserver,
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("samurai_ios26_gesture_pill"),
                false,
                lockSettingsObserver,
            )
            settingsObserverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "registerLockSettingsObserver failed: ${t.message}")
        }
    }

    private fun unregisterLockSettingsObserver() {
        if (!settingsObserverRegistered) return
        settingsObserverRegistered = false
        try {
            contentResolver.unregisterContentObserver(lockSettingsObserver)
        } catch (_: Throwable) {
        }
    }

    private fun registerCloseReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemCloseReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(systemCloseReceiver, filter)
            }
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "registerCloseReceiver failed: ${t.message}")
        }
    }

    private fun unregisterCloseReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            unregisterReceiver(systemCloseReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun createWindowUiContext(windowType: Int): Pair<Context, WindowManager>? {
        if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            val a11ySvc = IosSystemOverlayAccessibilityService.instance ?: return null
            return try {
                // Use AccessibilityService directly (without ContextThemeWrapper) so Android 17's
                // WindowManager token association stays 100% intact!
                a11ySvc.setTheme(R.style.Theme_IosControlCenter)
                val wm = a11ySvc.getSystemService(WindowManager::class.java)
                    ?: (a11ySvc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?: return null
                a11ySvc to wm
            } catch (t: Throwable) {
                Log.w(TAG, "createWindowUiContext(TYPE_ACCESSIBILITY_OVERLAY) failed: ${t.message}")
                null
            }
        }

        return try {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val displayCtx = createDisplayContext(display)
            val baseCtx: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    displayCtx.createWindowContext(windowType, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "createWindowContext($windowType) fallback to displayCtx: ${t.message}")
                    displayCtx
                }
            } else {
                displayCtx
            }
            val themed = ContextThemeWrapper(baseCtx, R.style.Theme_IosControlCenter)
            val wm = themed.getSystemService(WindowManager::class.java)
                ?: (themed.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: displayCtx.getSystemService(WindowManager::class.java)
                ?: return null
            themed to wm
        } catch (t: Throwable) {
            Log.w(TAG, "createWindowUiContext($windowType) failed", t)
            null
        }
    }

    private fun readDisplayMetrics(uiCtx: Context, wm: WindowManager): DisplayMetrics {
        val dm = DisplayMetrics()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                dm.setTo(uiCtx.resources.displayMetrics)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    dm.widthPixels = bounds.width()
                    dm.heightPixels = bounds.height()
                }
                return dm
            }
        } catch (_: Throwable) {
        }
        dm.setTo(uiCtx.resources.displayMetrics)
        return dm
    }

    private fun readStatusBarHeightPx(uiCtx: Context, density: Float): Int {
        val fallback = (32f * density).roundToInt().coerceAtLeast(48)
        return try {
            val resId = uiCtx.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) {
                max(uiCtx.resources.getDimensionPixelSize(resId), fallback)
            } else {
                fallback
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun readFallbackTriggerHeightPx(uiCtx: Context, density: Float): Int {
        val sbH = readStatusBarHeightPx(uiCtx, density)
        // Cover full 92px status bar + 99px mandatorySystemGestures strip without encroaching on app toolbars
        return max(sbH, (40f * density).roundToInt())
    }

    private fun readCurrentScreenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        try {
            val dispMgr = getSystemService(DisplayManager::class.java)
            val disp = dispMgr?.getDisplay(Display.DEFAULT_DISPLAY)
            if (disp != null) {
                val dCtx = createDisplayContext(disp)
                val wm = dCtx.getSystemService(WindowManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                    val b = wm.currentWindowMetrics.bounds
                    dm.setTo(dCtx.resources.displayMetrics)
                    if (b.width() > 0 && b.height() > 0) {
                        dm.widthPixels = b.width()
                        dm.heightPixels = b.height()
                    }
                    return dm
                }
            }
        } catch (_: Throwable) {
        }
        dm.setTo(resources.displayMetrics)
        return dm
    }

    private fun tryAttachTopGestureMonitor() {
        if (topGestureMonitor != null && topGestureReceiver != null) return
        try {
            val im = getSystemService(InputManager::class.java)
                ?: (getSystemService(Context.INPUT_SERVICE) as? InputManager)
                ?: return
            val monitorMethod = im.javaClass.getMethod(
                "monitorGestureInput",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val monitor = monitorMethod.invoke(im, "samurai-top-swipe", Display.DEFAULT_DISPLAY) as? InputMonitor
                ?: return
            val channel: InputChannel = monitor.inputChannel ?: run {
                monitor.dispose()
                return
            }

            var receiverDisposed = false
            val receiver = object : InputEventReceiver(channel, Looper.getMainLooper()) {
                private var armed = false
                private var dragging = false
                private var topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                private var downRawX = 0f
                private var downRawY = 0f
                private var lastMoveDy = 0f
                private var lastMoveDx = 0f
                private var maxDownwardDy = 0f
                private var dragRangePx = 2400f
                private var topDensity = 1f
                private var startDragThresholdPx = 14f
                private var gestureMode = IosSheetMode.CONTROL_CENTER
                private var velocityTracker: VelocityTracker? = null

                private var bottomSpyPointerId = MotionEvent.INVALID_POINTER_ID
                private var bottomDownRawX = 0f
                private var bottomDownRawY = 0f
                private var bottomLastDx = 0f
                private var bottomLastDy = 0f
                private var bottomMaxUpwardDy = 0f
                private var bottomDensity = 1f
                private var bottomTouchSlopPx = 21f
                private var bottomScreenW = 1080f
                private var bottomScreenH = 2400f
                private var bottomMode = 0 // 0=NONE, 1=PASSIVE_HOME_BAR, 2=LOCK_UNLOCK, 3=SHEET_CLOSE, 4=UDFPS_HOLD_OR_SWIPE_UP
                private var bottomPilfered = false
                private var bottomOnCornerControl = false
                private var bottomSheetStartProg = 1f
                private var bottomVelocityTracker: VelocityTracker? = null

                init {
                    topGestureCleanupHook = {
                        receiverDisposed = true
                        val wasSheetDrag = dragging || isBottomSpyClosingSheet || isTopBezelGestureActive || isSheetGestureActive
                        val wasLockDrag = isBottomSpyUnlockingLockScreen
                        armed = false
                        dragging = false
                        bottomMode = 0
                        bottomPilfered = false
                        bottomOnCornerControl = false
                        isUdfpsFingerDownState.value = false
                        topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                        bottomSpyPointerId = MotionEvent.INVALID_POINTER_ID
                        velocityTracker?.recycle()
                        velocityTracker = null
                        bottomVelocityTracker?.recycle()
                        bottomVelocityTracker = null
                        if (wasSheetDrag) {
                            clearSheetGestureActive()
                        }
                        if (wasLockDrag) {
                            isBottomSpyUnlockingLockScreen = false
                        }
                    }
                }

                override fun onInputEvent(event: InputEvent) {
                    var handled = false
                    try {
                        if (receiverDisposed) {
                            return
                        }
                        val motion = event as? MotionEvent
                        if (motion != null && (motion.source and InputDevice.SOURCE_CLASS_POINTER) != 0) {
                            if (motion.actionMasked == MotionEvent.ACTION_DOWN) {
                                // Cleanly settle & reset any orphaned top/bottom SPY or unlocked sheet pointer stream on a fresh ACTION_DOWN
                                val nowDownUptime = SystemClock.uptimeMillis()
                                if (bouncerPassthrough) {
                                    secureBouncerCoordinator?.notifyUserActivity(nowDownUptime)
                                }
                                val hadOrphanSheetDrag = dragging ||
                                    isBottomSpyClosingSheet ||
                                    isTopBezelGestureActive ||
                                    (isSheetGestureActive && (nowDownUptime - lastGestureUptimeMs) > 350L)
                                val hadOrphanLockDrag = isBottomSpyUnlockingLockScreen
                                val hadActiveBottomTouch = bottomSpyPointerId != MotionEvent.INVALID_POINTER_ID

                                armed = false
                                dragging = false
                                isTopBezelGestureActive = false
                                topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                                velocityTracker?.recycle()
                                velocityTracker = null
                                bottomSpyPointerId = MotionEvent.INVALID_POINTER_ID
                                bottomMode = 0
                                bottomPilfered = false
                                bottomOnCornerControl = false
                                isUdfpsFingerDownState.value = false
                                isBottomSpyUnlockingLockScreen = false
                                isBottomSpyClosingSheet = false
                                bottomVelocityTracker?.recycle()
                                bottomVelocityTracker = null

                                if (hadActiveBottomTouch) {
                                    springHomeBarFractionsToZero()
                                }
                                if (hadOrphanSheetDrag) {
                                    clearSheetGestureActive()
                                    animatePanelTo(if (openProgressState.floatValue >= 0.5f) 1f else 0f)
                                }
                                if (hadOrphanLockDrag &&
                                    !isLockCurtainUnlocking &&
                                    lockCurtainProgressState.floatValue > 0.005f
                                ) {
                                    animateLockCurtainBackToZero()
                                }

                                val dm = readCurrentScreenMetrics()
                                val dens = dm.density.coerceAtLeast(1f)
                                val touchSlopPx = ViewConfiguration.get(this@ControlCenterOverlayService)
                                    .scaledTouchSlop.toFloat().coerceAtLeast(8f * dens)
                                val screenW = dm.widthPixels.toFloat().coerceAtLeast(360f)
                                val screenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
                                val lockScreenActive = isLockOverlayVisible() &&
                                    !bouncerPassthrough &&
                                    !isInPhoneCall() &&
                                    openProgressState.floatValue <= 0.05f &&
                                    !isSheetGestureActive
                                val topSheetOpen = openProgressState.floatValue >= 0.50f &&
                                    panelHostView?.visibility == View.VISIBLE &&
                                    !isTopBezelGestureActive
                                val udfpsSpec = UdfpsSensorGeometry.resolve(
                                    this@ControlCenterOverlayService,
                                    screenW,
                                    screenH,
                                )
                                val inUdfpsAperture = lockScreenActive &&
                                    shouldCarveUdfpsTouchAperture() &&
                                    udfpsSpec.contains(motion.rawX, motion.rawY, extraRadiusPx = 0f)
                                val bottomZonePx = when {
                                    lockScreenActive -> 76f * dens
                                    topSheetOpen -> 56f * dens
                                    else -> 42f * dens
                                }
                                if (inUdfpsAperture) {
                                    isUdfpsFingerDownState.value = true
                                    bottomSpyPointerId = motion.getPointerId(0)
                                    bottomDensity = dens
                                    bottomTouchSlopPx = touchSlopPx
                                    bottomScreenW = screenW
                                    bottomScreenH = screenH
                                    bottomMode = 4
                                    bottomOnCornerControl = false
                                    handleBottomHomeBarSpyMotion(motion)
                                } else if (motion.rawY >= screenH - bottomZonePx) {
                                    bottomSpyPointerId = motion.getPointerId(0)
                                    bottomDensity = dens
                                    bottomTouchSlopPx = touchSlopPx
                                    bottomScreenW = screenW
                                    bottomScreenH = screenH
                                    val inSafeHorizontalBand = motion.rawX >= 28f * dens &&
                                        motion.rawX <= (screenW - 28f * dens)
                                    bottomMode = when {
                                        lockScreenActive && inSafeHorizontalBand -> 2
                                        topSheetOpen && inSafeHorizontalBand -> 3
                                        else -> 1
                                    }
                                    bottomOnCornerControl = (bottomMode == 2) && (
                                        motion.rawX <= 102f * dens ||
                                            motion.rawX >= (screenW - 102f * dens)
                                        )
                                    handleBottomHomeBarSpyMotion(motion)
                                } else {
                                    handleTopSpyMotion(motion)
                                }
                            } else if (armed || dragging) {
                                handleTopSpyMotion(motion)
                            } else if (bottomSpyPointerId != MotionEvent.INVALID_POINTER_ID) {
                                handleBottomHomeBarSpyMotion(motion)
                            } else {
                                handleTopSpyMotion(motion)
                            }
                            handled = true
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "TopStatusGestureMonitor.onInputEvent error: ${t.message}")
                    } finally {
                        finishInputEvent(event, handled)
                    }
                }

                private fun handleBottomHomeBarSpyMotion(ev: MotionEvent) {
                    val nowUptime = SystemClock.uptimeMillis()
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            bottomDownRawX = ev.rawX
                            bottomDownRawY = ev.rawY
                            bottomLastDx = 0f
                            bottomLastDy = 0f
                            bottomMaxUpwardDy = 0f
                            bottomPilfered = false
                            bottomVelocityTracker?.recycle()
                            bottomVelocityTracker = if (bottomMode == 2 || bottomMode == 3 || bottomMode == 4) {
                                VelocityTracker.obtain()?.also { it.addMovement(ev) }
                            } else {
                                null
                            }
                            homeBarSpringAnimator?.cancel()
                            mainHandler.removeCallbacks(homeBarPostTouchReconcileRunnable)
                            touchHomeBarActivity()
                        }

                        MotionEvent.ACTION_POINTER_DOWN -> {
                            // Do not pilfer multi-finger gestures before single-finger swipe-up engages,
                            // while preserving `isUdfpsFingerDownState` so an accidental palm/edge brush
                            // never occludes SystemUI's Goodix HBM illumination spot mid-scan.
                            if (!bottomPilfered && (bottomMode == 2 || bottomMode == 3 || bottomMode == 4)) {
                                bottomMode = 1
                                bottomVelocityTracker?.recycle()
                                bottomVelocityTracker = null
                            }
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val idx = ev.findPointerIndex(bottomSpyPointerId)
                            if (idx < 0) {
                                finishBottomSpyGesture(ev, isCancel = true, nowUptime = nowUptime)
                                return
                            }
                            val rx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.getRawX(idx) else ev.rawX
                            val ry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.getRawY(idx) else ev.rawY
                            val dx = rx - bottomDownRawX
                            val dy = ry - bottomDownRawY
                            bottomLastDx = dx
                            bottomLastDy = dy
                            if (dy < bottomMaxUpwardDy) {
                                bottomMaxUpwardDy = dy
                            }
                            val d = bottomDensity.coerceAtLeast(1f)
                            homeBarScrubState.floatValue = (dx / (56f * d)).coerceIn(-1f, 1f)
                            homeBarStretchState.floatValue = (hypot(dx, dy) / (48f * d)).coerceIn(0f, 1f)
                            homeBarLiftState.floatValue = (-dy / (36f * d)).coerceIn(0f, 1f)
                            touchHomeBarActivity()
                            if (bottomMode == 1 &&
                                launcherBarState == 1 &&
                                bottomDownRawY >= bottomScreenH - 28f * d &&
                                dy < -28f * d &&
                                abs(dy) > abs(dx) * 1.15f
                            ) {
                                launcherBarState = 0
                                syncHomeBarVisibility(immediate = false)
                            }

                            if (bottomMode == 4) {
                                // Goodix G3 optical UDFPS aperture: NEVER pilfer stationary holds/rolls (<28dp) so
                                // SystemUI UdfpsControllerOverlay completes Press #1 optical authentication uninterrupted;
                                // transition cleanly to swipe-up unlock only if the user intentionally drags upward >=28dp
                                // while the Lock Screen is still visible (never pilfer Home Screen if Press #1 already unlocked!).
                                if (!isLockOverlayVisible() || bouncerPassthrough) {
                                    isUdfpsFingerDownState.value = false
                                    bottomMode = 1
                                    bottomVelocityTracker?.recycle()
                                    bottomVelocityTracker = null
                                } else {
                                    bottomVelocityTracker?.addMovement(ev)
                                    val udfpsSwipeUpThresh = max(bottomTouchSlopPx * 1.5f, 28f * d)
                                    val regularStartThresh = max(bottomTouchSlopPx, 10f * d)
                                    if (abs(dx) > udfpsSwipeUpThresh && abs(dx) > abs(dy)) {
                                        isUdfpsFingerDownState.value = false
                                        bottomMode = 1
                                        bottomVelocityTracker?.recycle()
                                        bottomVelocityTracker = null
                                    } else if (dy < -udfpsSwipeUpThresh && abs(dy) > abs(dx) * 1.12f) {
                                        isUdfpsFingerDownState.value = false
                                        bottomDownRawY -= (udfpsSwipeUpThresh - regularStartThresh)
                                        bottomMode = 2
                                        bottomPilfered = true
                                        isBottomSpyUnlockingLockScreen = true
                                        lastLockTouchUptimeMs = nowUptime
                                        try {
                                            monitor.pilferPointers()
                                        } catch (_: Throwable) {
                                        }
                                        lockCurtainAnimator?.cancel()
                                        bottomVelocityTracker?.computeCurrentVelocity(1000)
                                        lockCurtainVelocityState.floatValue = bottomVelocityTracker?.yVelocity ?: 0f
                                        val liftPx = (-dy - udfpsSwipeUpThresh).coerceAtLeast(0f)
                                        lockCurtainProgressState.floatValue = (liftPx / bottomScreenH).coerceIn(0f, 1f)
                                        syncLockUdfpsApertureIfChanged()
                                    }
                                }
                            } else if (bottomMode == 2) {
                                // Lock Screen bottom swipe-up unlock: preempt NexusLauncher `swipe-up` pilfer!
                                bottomVelocityTracker?.addMovement(ev)
                                val startThresh = if (bottomOnCornerControl) {
                                    max(bottomTouchSlopPx, 14f * d)
                                } else {
                                    max(bottomTouchSlopPx, 10f * d)
                                }
                                if (!bottomPilfered) {
                                    if (abs(dx) > max(bottomTouchSlopPx, 12f * d) && abs(dx) > abs(dy)) {
                                        bottomMode = 1
                                        bottomVelocityTracker?.recycle()
                                        bottomVelocityTracker = null
                                    } else if (dy < -startThresh && abs(dy) > abs(dx) * 1.12f) {
                                        bottomPilfered = true
                                        isBottomSpyUnlockingLockScreen = true
                                        lastLockTouchUptimeMs = nowUptime
                                        try {
                                            monitor.pilferPointers()
                                        } catch (_: Throwable) {
                                        }
                                        lockCurtainAnimator?.cancel()
                                        bottomVelocityTracker?.computeCurrentVelocity(1000)
                                        lockCurtainVelocityState.floatValue = bottomVelocityTracker?.yVelocity ?: 0f
                                        val liftPx = (-dy - startThresh).coerceAtLeast(0f)
                                        lockCurtainProgressState.floatValue = (liftPx / bottomScreenH).coerceIn(0f, 1f)
                                        syncLockUdfpsApertureIfChanged()
                                    }
                                } else {
                                    lastLockTouchUptimeMs = nowUptime
                                    bottomVelocityTracker?.computeCurrentVelocity(1000)
                                    lockCurtainVelocityState.floatValue = bottomVelocityTracker?.yVelocity ?: 0f
                                    val liftPx = (-dy - startThresh).coerceAtLeast(0f)
                                    lockCurtainProgressState.floatValue = (liftPx / bottomScreenH).coerceIn(0f, 1f)
                                    syncLockUdfpsApertureIfChanged()
                                }
                            } else if (bottomMode == 3) {
                                // TopSheet (Control Center / Notification Center) bottom swipe-up close
                                bottomVelocityTracker?.addMovement(ev)
                                val startThresh = 5f * d
                                if (!bottomPilfered) {
                                    if (abs(dx) > 12f * d && abs(dx) > abs(dy)) {
                                        bottomMode = 1
                                        bottomVelocityTracker?.recycle()
                                        bottomVelocityTracker = null
                                    } else if (dy < -startThresh && abs(dy) > abs(dx) * 1.12f) {
                                        bottomPilfered = true
                                        bottomSheetStartProg = openProgressState.floatValue.coerceIn(0.002f, 1f)
                                        interactiveCloseStartProgress = bottomSheetStartProg
                                        isBottomSpyClosingSheet = true
                                        markSheetGestureActive(nowUptime)
                                        try {
                                            monitor.pilferPointers()
                                        } catch (_: Throwable) {
                                        }
                                        progressAnimator?.cancel()
                                        bottomVelocityTracker?.computeCurrentVelocity(1000)
                                        sheetVelocityState.floatValue = bottomVelocityTracker?.yVelocity ?: 0f
                                        val deltaUp = (dy + startThresh).coerceAtMost(0f)
                                        openProgressState.floatValue =
                                            (bottomSheetStartProg + deltaUp / bottomScreenH).coerceIn(0.002f, 1f)
                                        syncHomeBarVisibility(immediate = true)
                                    }
                                } else {
                                    markSheetGestureActive(nowUptime)
                                    bottomVelocityTracker?.computeCurrentVelocity(1000)
                                    sheetVelocityState.floatValue = bottomVelocityTracker?.yVelocity ?: 0f
                                    val deltaUp = (dy + startThresh).coerceAtMost(0f)
                                    openProgressState.floatValue =
                                        (bottomSheetStartProg + deltaUp / bottomScreenH).coerceIn(0.002f, 1f)
                                    syncHomeBarVisibility(immediate = true)
                                }
                            }
                        }

                        MotionEvent.ACTION_POINTER_UP -> {
                            val upIdx = ev.actionIndex
                            if (ev.getPointerId(upIdx) == bottomSpyPointerId) {
                                var nextIdx = -1
                                for (i in 0 until ev.pointerCount) {
                                    if (i != upIdx) {
                                        nextIdx = i
                                        break
                                    }
                                }
                                if (nextIdx >= 0 && bottomPilfered) {
                                    bottomSpyPointerId = ev.getPointerId(nextIdx)
                                    val newRx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ev.getRawX(nextIdx)
                                    } else {
                                        ev.rawX + (ev.getX(nextIdx) - ev.x)
                                    }
                                    val newRy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ev.getRawY(nextIdx)
                                    } else {
                                        ev.rawY + (ev.getY(nextIdx) - ev.y)
                                    }
                                    bottomDownRawX = newRx - bottomLastDx
                                    bottomDownRawY = newRy - bottomLastDy
                                    bottomVelocityTracker?.clear()
                                    return
                                }
                                finishBottomSpyGesture(
                                    ev = ev,
                                    isCancel = false,
                                    nowUptime = nowUptime,
                                    isPointerUp = true,
                                )
                            }
                        }

                        MotionEvent.ACTION_UP -> {
                            finishBottomSpyGesture(
                                ev = ev,
                                isCancel = false,
                                nowUptime = nowUptime,
                                isPointerUp = false,
                            )
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            finishBottomSpyGesture(
                                ev = ev,
                                isCancel = true,
                                nowUptime = nowUptime,
                                isPointerUp = false,
                            )
                        }
                    }
                }

                private fun finishBottomSpyGesture(
                    ev: MotionEvent,
                    isCancel: Boolean,
                    nowUptime: Long,
                    isPointerUp: Boolean = false,
                ) {
                    isUdfpsFingerDownState.value = false
                    val trackedId = bottomSpyPointerId
                    if (!isCancel && !isPointerUp) {
                        bottomVelocityTracker?.addMovement(ev)
                    }
                    bottomVelocityTracker?.computeCurrentVelocity(1000)
                    val vy = if (trackedId != MotionEvent.INVALID_POINTER_ID) {
                        bottomVelocityTracker?.getYVelocity(trackedId) ?: 0f
                    } else {
                        bottomVelocityTracker?.yVelocity ?: 0f
                    }
                    bottomVelocityTracker?.recycle()
                    bottomVelocityTracker = null

                    val finishedMode = bottomMode
                    val wasPilfered = bottomPilfered
                    val maxUpDy = bottomMaxUpwardDy
                    val lastDx = bottomLastDx
                    val d = bottomDensity.coerceAtLeast(1f)

                    bottomSpyPointerId = MotionEvent.INVALID_POINTER_ID
                    bottomMode = 0
                    bottomPilfered = false
                    bottomOnCornerControl = false
                    springHomeBarFractionsToZero()
                    mainHandler.postDelayed(homeBarPostTouchReconcileRunnable, 260L)

                    val pm = getSystemService(PowerManager::class.java)
                    val screenInteractive = pm?.isInteractive == true

                    if (finishedMode == 2) {
                        isBottomSpyUnlockingLockScreen = false
                        lastLockTouchUptimeMs = nowUptime
                        lockCurtainVelocityState.floatValue = vy
                        val curProg = lockCurtainProgressState.floatValue
                        val shouldUnlock = if (!isCancel) {
                            wasPilfered && (curProg >= 0.20f || vy <= -460f * d)
                        } else {
                            // Cross-process pilfer-race safety: if NexusLauncher `swipe-up` pilfered a genuine
                            // upward unlock swipe from the bottom bar while the screen is interactive, still unlock!
                            screenInteractive && (
                                (wasPilfered && (curProg >= 0.18f || vy <= -320f * d)) ||
                                    (maxUpDy <= -10f * d && abs(maxUpDy) > abs(lastDx) * 1.20f && vy <= -140f * d)
                                )
                        }
                        if (shouldUnlock && isLockOverlayVisible() && !bouncerPassthrough) {
                            lastBottomSpyLockSettleUptimeMs = nowUptime
                            controller?.tickHaptic()
                            animateLockCurtainAndUnlock(null)
                        } else if (wasPilfered || curProg > 0.005f) {
                            lastBottomSpyLockSettleUptimeMs = nowUptime
                            animateLockCurtainBackToZero()
                        }
                    } else if (finishedMode == 3) {
                        if (!isCancel && wasPilfered) {
                            lastBottomSpySheetSettleUptimeMs = nowUptime
                            handleInteractiveSheetCloseEnd(vy, fromBottomSpy = true)
                        } else if (isCancel) {
                            isBottomSpyClosingSheet = false
                            clearSheetGestureActive()
                            lastGestureUptimeMs = nowUptime
                            val stolenUpSwipe = screenInteractive &&
                                maxUpDy <= -10f * d &&
                                abs(maxUpDy) > abs(lastDx) * 1.20f &&
                                vy <= -140f * d
                            if (stolenUpSwipe) {
                                lastBottomSpySheetSettleUptimeMs = nowUptime
                                animatePanelTo(0f, initialVelocityPxPerSec = vy)
                            } else if (wasPilfered) {
                                lastBottomSpySheetSettleUptimeMs = nowUptime
                                animatePanelTo(
                                    if (openProgressState.floatValue >= 0.5f) 1f else 0f,
                                    initialVelocityPxPerSec = vy,
                                )
                            }
                        }
                    }
                }

                private fun handleTopSpyMotion(ev: MotionEvent) {
                    val nowUptime = SystemClock.uptimeMillis()
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dragging = false
                            armed = false
                            topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                            lastMoveDy = 0f
                            lastMoveDx = 0f
                            maxDownwardDy = 0f
                            velocityTracker?.recycle()
                            velocityTracker = null

                            if (!isTopPullDownAllowedNow()) {
                                return
                            }
                            if (openProgressState.floatValue > 0.15f && !isSheetGestureActive) {
                                // Sheet is already open; let SamuraiIos26TopSheet handle touches directly
                                return
                            }

                            val dm = readCurrentScreenMetrics()
                            val density = dm.density.coerceAtLeast(1f)
                            val screenW = dm.widthPixels.coerceAtLeast(360)
                            val screenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
                            val sbH = readStatusBarHeightPx(this@ControlCenterOverlayService, density)
                            val topZoneHeightPx = max(
                                sbH + (18f * density).roundToInt(),
                                (44f * density).roundToInt(),
                            )
                            val rx = ev.rawX
                            val ry = ev.rawY
                            if (ry < 0f || ry > topZoneHeightPx.toFloat()) {
                                return
                            }

                            if (rx < screenW * 0.50f) {
                                if (!canOpenNotificationCenterNow()) {
                                    // Cover Sheet already visible or device/user still locked
                                    return
                                }
                                gestureMode = IosSheetMode.NOTIFICATION_CENTER
                            } else {
                                gestureMode = IosSheetMode.CONTROL_CENTER
                            }

                            val slop = ViewConfiguration.get(this@ControlCenterOverlayService).scaledTouchSlop
                            startDragThresholdPx = (slop * 0.48f).coerceAtLeast(4.5f * density)
                            dragRangePx = screenH
                            topDensity = density
                            topSpyPointerId = ev.getPointerId(0)
                            downRawX = rx
                            downRawY = ry
                            armed = true
                            velocityTracker = VelocityTracker.obtain()?.also { it.addMovement(ev) }
                        }

                        MotionEvent.ACTION_POINTER_DOWN -> {
                            // Ignore multi-finger pinch/zoom before pilfering
                            if (!dragging) {
                                armed = false
                                topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                                velocityTracker?.recycle()
                                velocityTracker = null
                            }
                        }

                        MotionEvent.ACTION_POINTER_UP -> {
                            if (armed && ev.getPointerId(ev.actionIndex) == topSpyPointerId) {
                                val trackedId = topSpyPointerId
                                velocityTracker?.computeCurrentVelocity(1000)
                                val vy = if (trackedId != MotionEvent.INVALID_POINTER_ID) {
                                    velocityTracker?.getYVelocity(trackedId) ?: 0f
                                } else {
                                    velocityTracker?.yVelocity ?: 0f
                                }
                                velocityTracker?.recycle()
                                velocityTracker = null
                                armed = false
                                topSpyPointerId = MotionEvent.INVALID_POINTER_ID
                                if (dragging) {
                                    dragging = false
                                    isTopBezelGestureActive = false
                                    clearSheetGestureActive()
                                    lastTopBezelGestureUptimeMs = nowUptime
                                    lastTopOpenGestureUptimeMs = nowUptime
                                    lastGestureUptimeMs = nowUptime
                                    sheetVelocityState.floatValue = vy
                                    val curProg = openProgressState.floatValue
                                    val d = topDensity.coerceAtLeast(1f)
                                    val shouldOpen = curProg >= 0.24f || vy >= 650f * d
                                    animatePanelTo(if (shouldOpen) 1f else 0f, initialVelocityPxPerSec = vy)
                                } else {
                                    isTopBezelGestureActive = false
                                }
                            }
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (!armed) return
                            val idx = ev.findPointerIndex(topSpyPointerId)
                            if (idx < 0) return
                            velocityTracker?.addMovement(ev)
                            val rx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.getRawX(idx) else ev.rawX
                            val ry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.getRawY(idx) else ev.rawY
                            val dy = ry - downRawY
                            val dx = rx - downRawX
                            lastMoveDy = dy
                            lastMoveDx = dx
                            if (dy > maxDownwardDy) {
                                maxDownwardDy = dy
                            }

                            if (!dragging) {
                                if (abs(dx) > startDragThresholdPx * 1.45f && abs(dx) > abs(dy) * 1.35f) {
                                    // Horizontal swipe in top area -> let app handle untouched
                                    armed = false
                                    velocityTracker?.recycle()
                                    velocityTracker = null
                                    return
                                }
                                if (dy > startDragThresholdPx && dy > abs(dx) * 0.62f) {
                                    if (panelHostView == null) {
                                        tryAttachPanelWindow()
                                        publishLiveDiagnosticStatus()
                                    }
                                    if (panelHostView == null) {
                                        armed = false
                                        velocityTracker?.recycle()
                                        velocityTracker = null
                                        return
                                    }
                                    try {
                                        monitor.pilferPointers()
                                    } catch (_: Throwable) {
                                    }
                                    dragging = true
                                    isTopBezelGestureActive = true
                                    markSheetGestureActive(nowUptime)
                                    lastTopBezelGestureUptimeMs = nowUptime
                                    lastTopOpenGestureUptimeMs = nowUptime
                                    progressAnimator?.cancel()
                                    activeSheetModeState.value = gestureMode
                                    if (gestureMode == IosSheetMode.NOTIFICATION_CENTER) {
                                        IosNotificationListenerService.instance?.refreshAll()
                                    }
                                    syncAndroidShadeLockout()
                                    controller?.onPanelOpening()
                                    velocityTracker?.computeCurrentVelocity(1000)
                                    sheetVelocityState.floatValue = velocityTracker?.yVelocity ?: 0f
                                    openProgressState.floatValue = (dy / dragRangePx).coerceIn(0.002f, 1f)
                                    syncLockUdfpsApertureIfChanged()
                                    syncHomeBarVisibility(immediate = true)
                                    ensurePanelVisibleForDrag()
                                }
                            } else {
                                lastTopBezelGestureUptimeMs = nowUptime
                                lastTopOpenGestureUptimeMs = nowUptime
                                markSheetGestureActive(nowUptime)
                                velocityTracker?.computeCurrentVelocity(1000)
                                sheetVelocityState.floatValue = velocityTracker?.yVelocity ?: 0f
                                openProgressState.floatValue = (dy / dragRangePx).coerceIn(0.002f, 1f)
                                syncLockUdfpsApertureIfChanged()
                                syncHomeBarVisibility(immediate = true)
                            }
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            if (!armed) return
                            val trackedId = topSpyPointerId
                            val isCancel = ev.actionMasked == MotionEvent.ACTION_CANCEL
                            if (!isCancel) {
                                velocityTracker?.addMovement(ev)
                            }
                            velocityTracker?.computeCurrentVelocity(1000)
                            val vy = if (trackedId != MotionEvent.INVALID_POINTER_ID) {
                                velocityTracker?.getYVelocity(trackedId) ?: 0f
                            } else {
                                velocityTracker?.yVelocity ?: 0f
                            }
                            velocityTracker?.recycle()
                            velocityTracker = null
                            armed = false
                            topSpyPointerId = MotionEvent.INVALID_POINTER_ID

                            if (dragging) {
                                dragging = false
                                isTopBezelGestureActive = false
                                clearSheetGestureActive()
                                lastTopBezelGestureUptimeMs = nowUptime
                                lastTopOpenGestureUptimeMs = nowUptime
                                lastGestureUptimeMs = nowUptime
                                sheetVelocityState.floatValue = vy
                                val curProg = openProgressState.floatValue
                                val d = topDensity.coerceAtLeast(1f)
                                val shouldOpen = if (isCancel) {
                                    lastMoveDy >= maxDownwardDy * 0.65f &&
                                        vy >= -120f * d &&
                                        (curProg >= 0.24f || vy >= 520f * d)
                                } else {
                                    curProg >= 0.24f || vy >= 650f * d
                                }
                                animatePanelTo(if (shouldOpen) 1f else 0f, initialVelocityPxPerSec = vy)
                            } else {
                                isTopBezelGestureActive = false
                            }
                        }
                    }
                }
            }

            topGestureMonitor = monitor
            topGestureReceiver = receiver
            // Hide legacy opaque top trigger window since SPY monitor handles top-bar swipes transparently
            triggerView?.visibility = View.GONE
            Log.i(TAG, "Attached TopStatusGestureMonitor SPY channel ('samurai-top-swipe') on DEFAULT_DISPLAY")
        } catch (t: Throwable) {
            Log.i(TAG, "TopStatusGestureMonitor unavailable (${t.javaClass.simpleName}: ${t.message}); using SamuraiIos26TopTrigger fallback")
        }
    }

    private fun disposeTopGestureMonitor() {
        try {
            topGestureCleanupHook?.invoke()
        } catch (_: Throwable) {
        }
        topGestureCleanupHook = null
        isTopBezelGestureActive = false
        isBottomSpyUnlockingLockScreen = false
        isBottomSpyClosingSheet = false
        try {
            topGestureReceiver?.dispose()
        } catch (_: Throwable) {
        }
        topGestureReceiver = null
        try {
            topGestureMonitor?.dispose()
        } catch (_: Throwable) {
        }
        topGestureMonitor = null
    }

    private fun applyTopZeroInsetsAndCutout(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun candidateWindowTypes(): IntArray = intArrayOf(
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // 2032: Z-layer 31 (above StatusBar 15 & Shade 17)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // 2038: early-boot fallback until a11y binds
    )

    /**
     * Attaches overlay windows in strict bottom-to-top Z-order on TYPE_ACCESSIBILITY_OVERLAY (2032):
     *   0. `SamuraiIos26HomeBar`    (Z = 31.0: non-touchable 220dp x 28dp Home Indicator in apps & Recents)
     *   1. `SamuraiIos26LockScreen` (Z = 31.1: covers stock Android Keyguard when locked)
     *   2. `SamuraiIos26TopSheet`   (Z = 31.2: Left Notification Center & Right Control Center, even over Lock Screen!)
     *   3. `SamuraiIos26TopTrigger` (Z = 31.3: Top status-bar pull-down strip)
     */
    private fun tryAttachWindows() {
        val a11yHealthy = isA11yOverlayHealthy()
        val unlocked = isUserUnlocked()

        // If homeBarHostView or lockHostView needs to attach now while higher-Z windows were already attached earlier,
        // rebuild the stack cleanly once so strict bottom-to-top Z-order (HomeBar < Lock < Sheet < Trigger) is preserved!
        val sheetBusy = isSheetGestureActive || openProgressState.floatValue > 0.01f
        val needHomeBarBelow = unlocked && homeBarHostView == null &&
            (lockHostView != null || triggerView != null || panelHostView != null)
        val needLockBelow = unlocked && a11yHealthy && lockHostView == null &&
            (triggerView != null || panelHostView != null)
        if (needHomeBarBelow || needLockBelow) {
            if (sheetBusy) {
                // Never attach HomeBar or LockScreen ABOVE an already-attached open/dragging TopSheet!
                // finalizePanelState(0f) / closePanelImmediate() will rebuild the full stack in strict
                // bottom-to-top Z-order the instant TopSheet closes.
                if (topGestureMonitor == null) {
                    tryAttachTopGestureMonitor()
                }
                syncHomeBarVisibility(immediate = true)
                publishLiveDiagnosticStatus()
                return
            }
            removeWindowsSafely()
        }

        // 0a. Display-level SPY InputMonitor for top status/notification bar pull-downs + passive bottom Home Bar scrub
        if (topGestureMonitor == null) {
            tryAttachTopGestureMonitor()
        }

        // 0b. Lowest Z (31.0): Non-touchable 220dp x 28dp iOS 26 Home Indicator Bar (apps & Recents)
        if (unlocked && homeBarHostView == null) {
            tryAttachHomeBarWindow()
        }

        // 1. Next Z (31.1): 1:1 iOS 26 Lock Screen
        if (unlocked && a11yHealthy && lockHostView == null) {
            tryAttachLockWindow()
        }

        // 2. Middle Z (31.2): Full-screen Compose sheet host (NC + CC)
        if (unlocked && panelHostView == null) {
            tryAttachPanelWindow()
        }

        // 3. Highest Z (31.3): Fallback 100%-width top status-bar gesture detector (only when SPY monitor unavailable)
        if (topGestureMonitor == null && triggerView == null) {
            tryAttachTriggerWindow()
        } else if (topGestureMonitor != null) {
            triggerView?.visibility = View.GONE
        }

        syncHomeBarVisibility(immediate = true)
        publishLiveDiagnosticStatus()

        val needRetry = (triggerView == null && topGestureMonitor == null) ||
            (unlocked && homeBarHostView == null) ||
            (unlocked && panelHostView == null) ||
            (unlocked && a11yHealthy && lockHostView == null)
        if (needRetry) {
            if (overlayAttachRetries < 90) {
                overlayAttachRetries++
                mainHandler.postDelayed({ tryAttachWindows() }, 800L)
            }
        } else {
            overlayAttachRetries = 0
        }
    }

    private fun tryAttachHomeBarWindow() {
        if (homeBarHostView != null || !isUserUnlocked()) return
        val owner = lifecycleOwner ?: return
        val windowTypes = if (isDeviceOrSwipeLocked()) {
            intArrayOf(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else {
            candidateWindowTypes()
        }

        for (winType in windowTypes) {
            if (winType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(this)
            ) {
                continue
            }
            val a11ySvc = IosSystemOverlayAccessibilityService.instance
            if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                a11ySvc != null &&
                System.identityHashCode(a11ySvc) == lastFailedA11yIdentity
            ) {
                continue
            }
            try {
                val (uiCtx, wm) = createWindowUiContext(winType) ?: continue
                val dm = readDisplayMetrics(uiCtx, wm)
                val density = dm.density.coerceAtLeast(1f)
                val barW = (220f * density).roundToInt().coerceAtLeast(120)
                val barH = (28f * density).roundToInt().coerceAtLeast(20)

                // Keep host View.VISIBLE permanently once attached so WindowState.mHasSurface stays
                // valid and InsetsStateController delivers navigationBars() visibility updates when
                // exiting fullscreen video / immersive games; show/hide strictly via host.alpha.
                val host = FrameLayout(uiCtx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    alpha = 0f
                    visibility = View.VISIBLE
                }
                owner.attachToView(host)

                ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
                    val vis = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                    if (navBarsVisible != vis) {
                        navBarsVisible = vis
                        syncHomeBarVisibility(immediate = false)
                    }
                    insets
                }

                val composeView = ComposeView(uiCtx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    owner.attachToView(this)
                    setContent {
                        IosControlCenterTheme {
                            Ios26HomeIndicatorBar(
                                scrubFraction = homeBarScrubState.floatValue,
                                stretchFraction = homeBarStretchState.floatValue,
                                liftFraction = homeBarLiftState.floatValue,
                                idleDimmed = homeBarIdleDimState.value,
                            )
                        }
                    }
                }
                host.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                )

                // Set fitInsetsTypes = 0 so WindowLayout.computeFrames positions SamuraiIos26HomeBar
                // flush with the physical bottom display edge (y = screenH, 8dp bottom inset), 1:1 with
                // Lock Screen, NC, CC, and Quickstep's fallback pill. ViewRootImpl still dispatches full
                // WindowInsets to OnApplyWindowInsetsListener so navigationBars() visibility updates cleanly!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        navBarsVisible = wm.currentWindowMetrics.windowInsets
                            .isVisible(android.view.WindowInsets.Type.navigationBars())
                    } catch (_: Throwable) {
                    }
                }

                @Suppress("DEPRECATION")
                val hFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

                val hParams = WindowManager.LayoutParams(
                    barW,
                    barH,
                    winType,
                    hFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        fitInsetsTypes = 0
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    title = "SamuraiIos26HomeBar"
                }

                wm.addView(host, hParams)
                homeBarHostView = host
                homeBarParams = hParams
                homeBarWindowManager = wm
                activeWindowType = winType
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    attachedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                touchHomeBarActivity()
                Log.i(TAG, "Attached SamuraiIos26HomeBar (${barW}x${barH}) at windowType=$winType")
                break
            } catch (t: Throwable) {
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    lastFailedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                Log.w(TAG, "Failed adding SamuraiIos26HomeBar type=$winType: ${t.message}")
            }
        }
    }

    private fun updateHomeBarDimensionsForDisplay() {
        val host = homeBarHostView ?: return
        val lp = homeBarParams ?: return
        val wm = homeBarWindowManager ?: return
        val dm = readDisplayMetrics(host.context, wm)
        val density = dm.density.coerceAtLeast(1f)
        val newW = (220f * density).roundToInt().coerceAtLeast(120)
        val newH = (28f * density).roundToInt().coerceAtLeast(20)
        if (lp.width != newW || lp.height != newH) {
            val prevW = lp.width
            val prevH = lp.height
            lp.width = newW
            lp.height = newH
            try {
                wm.updateViewLayout(host, lp)
            } catch (t: Throwable) {
                lp.width = prevW
                lp.height = prevH
                Log.w(TAG, "updateHomeBarDimensionsForDisplay failed: ${t.message}")
                mainHandler.removeCallbacks(windowStackRecoveryRunnable)
                mainHandler.postDelayed(windowStackRecoveryRunnable, 120L)
            }
        }
    }

    private fun touchHomeBarActivity() {
        homeBarIdleDimState.value = false
        mainHandler.removeCallbacks(homeBarIdleDimRunnable)
        if (homeBarTargetShown) {
            mainHandler.postDelayed(homeBarIdleDimRunnable, 2500L)
        }
    }

    private fun springHomeBarFractionsToZero() {
        homeBarSpringAnimator?.cancel()
        val s0 = homeBarScrubState.floatValue
        val st0 = homeBarStretchState.floatValue
        val l0 = homeBarLiftState.floatValue
        if (abs(s0) < 0.004f && abs(st0) < 0.004f && abs(l0) < 0.004f) {
            homeBarScrubState.floatValue = 0f
            homeBarStretchState.floatValue = 0f
            homeBarLiftState.floatValue = 0f
            return
        }
        homeBarSpringAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 165L
            interpolator = DecelerateInterpolator(1.9f)
            addUpdateListener { anim ->
                val k = (anim.animatedValue as Float).coerceIn(0f, 1f)
                homeBarScrubState.floatValue = s0 * k
                homeBarStretchState.floatValue = st0 * k
                homeBarLiftState.floatValue = l0 * k
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    homeBarScrubState.floatValue = 0f
                    homeBarStretchState.floatValue = 0f
                    homeBarLiftState.floatValue = 0f
                }
            })
            start()
        }
    }

    private fun syncHomeBarVisibility(immediate: Boolean) {
        val host = homeBarHostView ?: return
        val sheetOrLockActive = isLockOverlayVisible() ||
            openProgressState.floatValue > 0.02f ||
            (bouncerPassthrough && !secureCameraPassthroughActive)
        val shouldShow = gesturePillEnabledState.value &&
            launcherBarState != 1 &&
            !sheetOrLockActive &&
            navBarsVisible &&
            !a11yImeVisible

        val wasShown = homeBarTargetShown
        homeBarTargetShown = shouldShow
        if (shouldShow && !wasShown) {
            touchHomeBarActivity()
        } else if (!shouldShow) {
            mainHandler.removeCallbacks(homeBarIdleDimRunnable)
        }

        // Never set host.visibility = View.GONE: WindowManagerService withholds notifyInsetsChanged()
        // when WindowState.isReadyToDispatchInsetsState() is false (mHasSurface == false), which would
        // strand navBarsVisible == false after leaving fullscreen/immersive apps. With FLAG_NOT_TOUCHABLE
        // | FLAG_NOT_FOCUSABLE, alpha = 0f consumes zero input and skips HWUI layer composition.
        if (host.visibility != View.VISIBLE) {
            host.visibility = View.VISIBLE
        }
        val targetAlpha = if (shouldShow) 1f else 0f
        if (sheetOrLockActive || immediate) {
            homeBarFadeAnimator?.cancel()
            homeBarFadeAnimator = null
            host.alpha = targetAlpha
            return
        }

        if (abs(host.alpha - targetAlpha) < 0.01f && homeBarFadeAnimator?.isRunning != true) {
            host.alpha = targetAlpha
            return
        }

        homeBarFadeAnimator?.cancel()
        val startAlpha = host.alpha
        homeBarFadeAnimator = ValueAnimator.ofFloat(startAlpha, targetAlpha).apply {
            duration = 140L
            interpolator = DecelerateInterpolator(1.6f)
            var cancelled = false
            addUpdateListener { anim ->
                host.alpha = (anim.animatedValue as Float).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        host.alpha = targetAlpha
                    }
                }
            })
            start()
        }
    }

    /**
     * Analytical critically damped spring stepper (`x'' + 2 * omega * x' + omega^2 * x = 0`)
     * seeded with actual finger release velocity (`px/s`) in screen-Y pixel space and clamped
     * cleanly once `abs(pos - target) < 0.5px && abs(vel) < 12px/s`.
     */
    private fun startCriticallyDampedSpring(
        startPosPx: Float,
        targetPosPx: Float,
        initialVelocityPxPerSec: Float,
        omegaRadPerSec: Float = 26f,
        onFrame: (posPx: Float, velPxPerSec: Float) -> Unit,
        onSettled: () -> Unit,
    ): ValueAnimator {
        var pos = startPosPx
        var vel = initialVelocityPxPerSec
        var lastFrameNs = 0L
        var settled = false
        var cancelled = false

        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950L
            interpolator = null
            addUpdateListener { anim ->
                if (settled || cancelled) return@addUpdateListener
                val nowNs = System.nanoTime()
                val dt = if (lastFrameNs == 0L) {
                    1f / 90f
                } else {
                    ((nowNs - lastFrameNs) * 1e-9f).coerceIn(0.001f, 0.032f)
                }
                lastFrameNs = nowNs

                val x0 = pos - targetPosPx
                val v0 = vel
                val expTerm = exp((-omegaRadPerSec * dt).toDouble()).toFloat()
                val c2 = v0 + omegaRadPerSec * x0
                pos = targetPosPx + (x0 + c2 * dt) * expTerm
                vel = (v0 - omegaRadPerSec * c2 * dt) * expTerm

                if (abs(pos - targetPosPx) < 0.5f && abs(vel) < 12f) {
                    settled = true
                    pos = targetPosPx
                    vel = 0f
                    onFrame(pos, 0f)
                    anim.cancel()
                    onSettled()
                } else {
                    onFrame(pos, vel)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (!settled) {
                        cancelled = true
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && !settled) {
                        settled = true
                        onFrame(targetPosPx, 0f)
                        onSettled()
                    }
                }
            })
            start()
        }
    }

    private fun tryAttachLockWindow() {
        if (lockHostView != null) return
        val a11ySvc = IosSystemOverlayAccessibilityService.instance ?: return
        if (System.identityHashCode(a11ySvc) == lastFailedA11yIdentity) return
        val owner = lifecycleOwner ?: return
        val ctrl = controller ?: return
        val winType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        try {
            val (uiCtx, wm) = createWindowUiContext(winType) ?: return

            val host = object : FrameLayout(uiCtx) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                        visibility == View.VISIBLE &&
                        !bouncerPassthrough
                    ) {
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
            owner.attachToView(host)

            val composeView = ComposeView(uiCtx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                owner.attachToView(this)
                setContent {
                    val glassCfg = rememberCcGlassConfig(applicationContext)
                    IosControlCenterTheme {
                        IosLockScreenRoot(
                            controller = ctrl,
                            glassConfig = glassCfg,
                            curtainDismissProgress = lockCurtainProgressState.floatValue,
                            curtainVelocityPxPerSec = lockCurtainVelocityState.floatValue,
                            gesturePillEnabled = gesturePillEnabledState.value,
                            isSecureKeyguard = isSecureKeyguardState.value,
                            isUdfpsFingerDown = isUdfpsFingerDownState.value,
                            hasEnrolledUdfps = hasEnrolledUdfpsState.value,
                            isLockScreenVisible = isLockScreenVisibleState.value,
                            lockSessionEpoch = lockScreenSessionEpochState.intValue,
                            lockWakeTick = lockWakeTickState.intValue,
                            onSwipeStart = {
                                if (!isLockSwipeOwnedBySpyOrUnlocking()) {
                                    isComposeLockDragging = true
                                    lastLockTouchUptimeMs = SystemClock.uptimeMillis()
                                    lockCurtainAnimator?.cancel()
                                }
                            },
                            onSwipeProgressChange = { prog ->
                                if (!isLockSwipeOwnedBySpyOrUnlocking()) {
                                    lastLockTouchUptimeMs = SystemClock.uptimeMillis()
                                    lockCurtainProgressState.floatValue = prog.coerceIn(0f, 1f)
                                    syncLockUdfpsApertureIfChanged()
                                }
                            },
                            onSwipeVelocityChange = { vy ->
                                if (!isLockSwipeOwnedBySpyOrUnlocking()) {
                                    lockCurtainVelocityState.floatValue = vy
                                }
                            },
                            onSwipeCancelled = {
                                isComposeLockDragging = false
                                lastLockTouchUptimeMs = SystemClock.uptimeMillis()
                                if (!isLockSwipeOwnedBySpyOrUnlocking()) {
                                    animateLockCurtainBackToZero()
                                }
                            },
                            onUnlockRequested = { afterUnlock ->
                                isComposeLockDragging = false
                                lastLockTouchUptimeMs = SystemClock.uptimeMillis()
                                if (!isLockSwipeOwnedBySpyOrUnlocking()) {
                                    animateLockCurtainAndUnlock(afterUnlock)
                                }
                            },
                            onDirectUdfpsTouch = {
                                triggerDirectUdfpsPassthrough()
                            },
                            onOpenControlCenter = {
                                if (isSwipeGestureAllowed()) {
                                    activeSheetModeState.value = IosSheetMode.CONTROL_CENTER
                                    openPanelAnimated()
                                }
                            },
                            onOpenCameraSecure = {
                                launchSecureCameraFromLockScreen()
                            },
                        )
                    }
                }
            }
            host.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )

            @Suppress("DEPRECATION")
            val lockFlagsClosed = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            val lParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                winType,
                lockFlagsClosed,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                applyTopZeroInsetsAndCutout(this)
                title = "SamuraiIos26LockScreen"
            }

            wm.addView(host, lParams)
            lockUdfpsApertureCleanup?.invoke()
            lastCarvedUdfpsAperture = null
            lockUdfpsApertureCleanup = UdfpsSensorGeometry.installTouchAperture(
                view = host,
                shouldCarveAperture = {
                    shouldCarveUdfpsTouchAperture().also { carved ->
                        lastCarvedUdfpsAperture = carved
                    }
                },
            )
            lockHostView = host
            lockParams = lParams
            lockWindowManager = wm
            appliedLockWindowFlags = lockFlagsClosed
            activeWindowType = winType
            attachedA11yIdentity = System.identityHashCode(a11ySvc)
            Log.i(TAG, "Attached SamuraiIos26LockScreen at windowType=$winType")
        } catch (t: Throwable) {
            lastFailedA11yIdentity = System.identityHashCode(a11ySvc)
            Log.w(TAG, "Failed adding SamuraiIos26LockScreen: ${t.message}")
        }
    }

    private fun syncLockScreenVisibility(preArmWhileScreenOff: Boolean = false) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val secure = km?.isKeyguardSecure == true
        val prevSecure = isSecureKeyguardState.value
        isSecureKeyguardState.value = secure
        val enrolledUdfps = if (secure) UdfpsSensorGeometry.hasEnrolledUdfps(this) else false
        val prevEnrolled = hasEnrolledUdfpsState.value
        hasEnrolledUdfpsState.value = enrolledUdfps
        val isSystemLocked = km?.isKeyguardLocked == true

        if (!preArmWhileScreenOff) {
            dismissTopSheetIfCriticalInterrupt(isSystemLocked = isSystemLocked)
        }

        val host = lockHostView
        val lp = lockParams
        val wm = lockWindowManager
        if (host == null || lp == null || wm == null) {
            isLockScreenVisibleState.value = false
            isUdfpsFingerDownState.value = false
            setTriggerArmed(openProgressState.floatValue <= 0.02f)
            syncHomeBarVisibility(immediate = false)
            syncControllerPanelVisibility()
            return
        }

        val effectivelyLocked = isSystemLocked ||
            iosSwipeLockEngaged ||
            (preArmWhileScreenOff && isLockScreenEnabled())

        val baseShouldShow = isUserUnlocked() &&
            activeWindowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
            isLockScreenEnabled() &&
            effectivelyLocked &&
            !isKeyguardOccludedByExternalApp(isSystemLocked, preArmWhileScreenOff) &&
            !isInPhoneCall()

        val holdingBouncerMask = baseShouldShow &&
            bouncerPassthrough &&
            bouncerVisualMaskHoldActive &&
            !preArmWhileScreenOff

        val shouldShow = baseShouldShow && !bouncerPassthrough
        val shouldRenderVisual = shouldShow || holdingBouncerMask

        isLockScreenVisibleState.value = shouldRenderVisual
        if (!shouldRenderVisual) {
            isUdfpsFingerDownState.value = false
            isLockCurtainUnlocking = false
            lockCurtainAnimator?.cancel()
            lockCurtainProgressState.floatValue = 0f
            lockCurtainVelocityState.floatValue = 0f
        } else if (!shouldShow) {
            isUdfpsFingerDownState.value = false
        }

        @Suppress("DEPRECATION")
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        val desiredFlags = if (shouldShow) {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            baseFlags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val desiredVis = if (shouldRenderVisual) View.VISIBLE else View.GONE
        if (host.visibility != desiredVis) {
            if (shouldRenderVisual && (openProgressState.floatValue > 0f || panelHostView?.visibility == View.VISIBLE)) {
                closePanelImmediate()
            }
        }
        val liveHost = lockHostView
        val liveLp = lockParams
        val liveWm = lockWindowManager
        if (liveHost == null || liveLp == null || liveWm == null) {
            isLockScreenVisibleState.value = false
            isUdfpsFingerDownState.value = false
            setTriggerArmed(openProgressState.floatValue <= 0.02f)
            syncHomeBarVisibility(immediate = false)
            syncControllerPanelVisibility()
            return
        }
        val visChanged = liveHost.visibility != desiredVis
        if (visChanged) {
            if (desiredVis == View.VISIBLE && shouldShow) {
                liveHost.alpha = 1f
            }
            liveHost.visibility = desiredVis
            if (shouldShow) {
                lockScreenSessionEpochState.intValue += 1
                lockCurtainProgressState.floatValue = 0f
                lockCurtainVelocityState.floatValue = 0f
                IosNotificationListenerService.instance?.refreshAll()
            }
        } else if (shouldShow && liveHost.alpha < 0.999f && bouncerRevealFadeAnimator == null) {
            liveHost.alpha = 1f
        }
        if (visChanged || prevSecure != secure || prevEnrolled != enrolledUdfps) {
            invalidateLockUdfpsAperture()
        } else {
            syncLockUdfpsApertureIfChanged()
        }
        if (appliedLockWindowFlags != desiredFlags || liveLp.flags != desiredFlags) {
            liveLp.flags = desiredFlags
            try {
                liveWm.updateViewLayout(liveHost, liveLp)
                appliedLockWindowFlags = desiredFlags
            } catch (t: Throwable) {
                liveLp.flags = appliedLockWindowFlags
                Log.w(TAG, "syncLockScreenVisibility updateViewLayout failed: ${t.message}")
                mainHandler.removeCallbacks(windowStackRecoveryRunnable)
                mainHandler.postDelayed(windowStackRecoveryRunnable, 120L)
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        val shouldWatchdog = (
            (effectivelyLocked && !preArmWhileScreenOff) ||
                secureCameraPassthroughActive ||
                bouncerPassthrough
            ) && pm?.isInteractive == true
        if (shouldWatchdog) {
            mainHandler.removeCallbacks(lockSafetyWatchdog)
            mainHandler.postDelayed(lockSafetyWatchdog, 600L)
        } else {
            mainHandler.removeCallbacks(lockSafetyWatchdog)
        }
        setTriggerArmed(openProgressState.floatValue <= 0.02f)
        syncHomeBarVisibility(immediate = shouldShow)
        syncControllerPanelVisibility()
    }

    private fun animateLockCurtainBackToZero() {
        isLockCurtainUnlocking = false
        lockCurtainAnimator?.cancel()
        val screenH = readCurrentFullScreenHeightPx()
        val start = lockCurtainProgressState.floatValue.coerceIn(0f, 1f)
        val startEdgeY = (1f - start) * screenH
        val targetEdgeY = screenH
        val initVel = lockCurtainVelocityState.floatValue
        if (abs(startEdgeY - targetEdgeY) < 0.5f && abs(initVel) < 12f) {
            lockCurtainProgressState.floatValue = 0f
            lockCurtainVelocityState.floatValue = 0f
            syncLockUdfpsApertureIfChanged()
            return
        }
        lockCurtainAnimator = startCriticallyDampedSpring(
            startPosPx = startEdgeY,
            targetPosPx = targetEdgeY,
            initialVelocityPxPerSec = initVel,
            omegaRadPerSec = 26f,
            onFrame = { posPx, velPxPerSec ->
                lockCurtainProgressState.floatValue = (1f - posPx / screenH).coerceIn(0f, 1f)
                lockCurtainVelocityState.floatValue = velPxPerSec
                syncLockUdfpsApertureIfChanged()
            },
            onSettled = {
                lockCurtainProgressState.floatValue = 0f
                lockCurtainVelocityState.floatValue = 0f
                syncLockUdfpsApertureIfChanged()
            },
        )
    }

    private fun animateLockCurtainAndUnlock(afterUnlock: (() -> Unit)?) {
        isLockCurtainUnlocking = true
        syncLockUdfpsApertureIfChanged()
        lockCurtainAnimator?.cancel()
        if (openProgressState.floatValue > 0f || panelHostView?.visibility == View.VISIBLE) {
            closePanelImmediate()
        }
        val km = getSystemService(KeyguardManager::class.java)
            ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        val preWarmedSession = km?.isKeyguardLocked == true
        if (preWarmedSession) {
            beginKeyguardUnlockSession(
                holdVisualMask = km?.isKeyguardSecure == true,
                afterUnlock = afterUnlock,
            )
        }
        val screenH = readCurrentFullScreenHeightPx()
        val start = lockCurtainProgressState.floatValue.coerceIn(0f, 1f)
        val startEdgeY = (1f - start) * screenH
        val targetEdgeY = 0f
        val initVel = lockCurtainVelocityState.floatValue
        if (abs(startEdgeY - targetEdgeY) < 0.5f && abs(initVel) < 12f) {
            lockCurtainProgressState.floatValue = 1f
            lockCurtainVelocityState.floatValue = 0f
            completeLockCurtainUnlock(afterUnlock, preWarmedSession = preWarmedSession)
            return
        }
        lockCurtainAnimator = startCriticallyDampedSpring(
            startPosPx = startEdgeY,
            targetPosPx = targetEdgeY,
            initialVelocityPxPerSec = initVel,
            omegaRadPerSec = 27f,
            onFrame = { posPx, velPxPerSec ->
                val nextProg = (1f - posPx / screenH).coerceIn(0f, 1f)
                lockCurtainProgressState.floatValue = nextProg
                lockCurtainVelocityState.floatValue = velPxPerSec
                if (preWarmedSession && nextProg >= 0.965f) {
                    secureBouncerCoordinator?.notifyCurtainSettled()
                }
            },
            onSettled = {
                lockCurtainProgressState.floatValue = 1f
                lockCurtainVelocityState.floatValue = 0f
                completeLockCurtainUnlock(afterUnlock, preWarmedSession = preWarmedSession)
            },
        )
    }

    private fun completeLockCurtainUnlock(
        afterUnlock: (() -> Unit)?,
        preWarmedSession: Boolean = false,
    ) {
        isLockCurtainUnlocking = false
        isUdfpsFingerDownState.value = false
        lastLockDismissUptimeMs = SystemClock.uptimeMillis()
        lockCurtainVelocityState.floatValue = 0f
        val km = getSystemService(KeyguardManager::class.java)
            ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        if (km?.isKeyguardLocked == true) {
            val coord = secureBouncerCoordinator
            if (preWarmedSession && coord != null && coord.isSessionActive) {
                coord.updatePendingAfterUnlockIfActive(afterUnlock)
                coord.notifyCurtainSettled()
            } else {
                requestKeyguardUnlockAndRun(afterUnlock)
            }
        } else {
            iosSwipeLockEngaged = false
            lastScreenOffUptimeMs = -10_000L
            clearSwipeLockBaselineTask()
            bouncerVisualMaskHoldActive = false
            cancelBouncerRevealFade(resetHostAlpha = true)
            setBouncerPassthroughState(false)
            syncLockScreenVisibility()
            lockCurtainProgressState.floatValue = 0f
            syncAndroidShadeLockout()
            syncControllerPanelVisibility()
            if (preWarmedSession) {
                if (secureBouncerCoordinator?.isSessionActive == true) {
                    secureBouncerCoordinator?.onUserPresentOrKeyguardUnlocked()
                }
            } else {
                try {
                    afterUnlock?.invoke()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun beginKeyguardUnlockSession(
        holdVisualMask: Boolean,
        afterUnlock: (() -> Unit)?,
    ) {
        mainHandler.removeCallbacks(postBouncerVerifyRunnable)
        iosSwipeLockEngaged = false
        clearSwipeLockBaselineTask()
        lastLockDismissUptimeMs = SystemClock.uptimeMillis()
        val coord = secureBouncerCoordinator
        if (coord != null) {
            coord.startSession(
                holdVisualMask = holdVisualMask,
                afterUnlock = afterUnlock,
            )
            syncAndroidShadeLockout()
        } else {
            bouncerVisualMaskHoldActive = false
            cancelBouncerRevealFade(resetHostAlpha = true)
            setBouncerPassthroughState(true)
            syncAndroidShadeLockout()
            syncLockScreenVisibility()
            IosKeyguardDismissActivity.launch(applicationContext, afterUnlock)
        }
    }

    private fun requestKeyguardUnlockAndRun(afterUnlock: (() -> Unit)?) {
        beginKeyguardUnlockSession(
            holdVisualMask = false,
            afterUnlock = afterUnlock,
        )
    }

    private fun triggerDirectUdfpsPassthrough() {
        val km = getSystemService(KeyguardManager::class.java)
            ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        if (km?.isKeyguardSecure != true || !km.isKeyguardLocked) {
            return
        }
        animateLockCurtainAndUnlock(null)
    }

    private fun launchSecureCameraFromLockScreen() {
        val km = getSystemService(KeyguardManager::class.java)
            ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        if (km?.isKeyguardSecure != true || !km.isKeyguardLocked) {
            controller?.tickHaptic()
            animateLockCurtainAndUnlock {
                controller?.openCamera()
            }
            return
        }
        controller?.tickHaptic()
        mainHandler.removeCallbacks(postBouncerVerifyRunnable)
        secureBouncerCoordinator?.cancelActiveSession()
        bouncerVisualMaskHoldActive = false
        cancelBouncerRevealFade(resetHostAlpha = true)
        setBouncerPassthroughState(true)
        secureCameraPassthroughActive = true
        secureCameraSawAppWindow = false
        secureCameraLaunchUptimeMs = SystemClock.uptimeMillis()
        syncLockScreenVisibility()
        val launched = try {
            val secureIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply {
                setPackage("com.google.android.GoogleCameraEng")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(secureIntent)
            true
        } catch (_: Throwable) {
            false
        }
        if (!launched) {
            val fallbackLaunched = try {
                val fallback = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(fallback)
                true
            } catch (_: Throwable) {
                false
            }
            if (!fallbackLaunched) {
                secureCameraPassthroughActive = false
                requestKeyguardUnlockAndRun {
                    controller?.openCamera()
                }
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun tryAttachTriggerWindow() {
        if (triggerView != null || topGestureMonitor != null) return
        if (!isUserUnlocked()) return

        val windowTypes = if (isDeviceOrSwipeLocked()) {
            intArrayOf(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        } else {
            candidateWindowTypes()
        }

        for (winType in windowTypes) {
            if (winType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(this)
            ) {
                continue
            }
            val a11ySvc = IosSystemOverlayAccessibilityService.instance
            if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                a11ySvc != null &&
                System.identityHashCode(a11ySvc) == lastFailedA11yIdentity
            ) {
                continue
            }
            try {
                val (uiCtx, wm) = createWindowUiContext(winType) ?: continue
                val dm = readDisplayMetrics(uiCtx, wm)
                val density = dm.density.coerceAtLeast(1f)
                val dragRangePx = dm.heightPixels.toFloat().coerceAtLeast(640f)
                val touchSlop = ViewConfiguration.get(uiCtx).scaledTouchSlop
                val triggerH = readFallbackTriggerHeightPx(uiCtx, density)

                val detector = View(uiCtx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnTouchListener(
                        createTopBezelSwipeListener(
                            uiCtx = uiCtx,
                            wm = wm,
                            touchSlop = touchSlop,
                            density = density,
                            dragRangePx = dragRangePx,
                        )
                    )
                }

                @Suppress("DEPRECATION")
                val tFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

                // Full 100% top status-bar width:
                // Left 50% -> iOS 26 Notification Center | Right 50% -> iOS 26 Control Center
                val tParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    triggerH,
                    winType,
                    tFlags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    applyTopZeroInsetsAndCutout(this)
                    title = "SamuraiIos26TopTrigger"
                }

                wm.addView(detector, tParams)
                triggerView = detector
                triggerParams = tParams
                triggerWindowManager = wm
                activeWindowType = winType
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    attachedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                setTriggerArmed(openProgressState.floatValue <= 0.02f)
                Log.i(
                    TAG,
                    "Attached SamuraiIos26TopTrigger type=$winType fullWidth x ${triggerH}px (screen=${dm.widthPixels}x${dm.heightPixels})"
                )
                break
            } catch (t: Throwable) {
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    lastFailedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                Log.w(TAG, "Failed adding SamuraiIos26TopTrigger type=$winType: ${t.message}")
            }
        }
    }

    private fun tryAttachPanelWindow() {
        if (panelHostView != null) return
        if (!isUserUnlocked()) return

        val owner = lifecycleOwner ?: return
        val ctrl = controller ?: return

        // Never fall back to TYPE_APPLICATION_OVERLAY (2038, Z-layer 12) when device/Swipe-Lock is locked
        // or lockHostView (2032, Z-layer 31) is attached — doing so would place TopSheet underneath Keyguard/LockScreen!
        val orderedTypes = when {
            isDeviceOrSwipeLocked() -> intArrayOf(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            activeWindowType != 0 -> intArrayOf(
                activeWindowType,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            ).distinct().toIntArray()
            else -> candidateWindowTypes()
        }

        for (winType in orderedTypes) {
            if (winType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(this)
            ) {
                continue
            }
            val a11ySvc = IosSystemOverlayAccessibilityService.instance
            if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
                a11ySvc != null &&
                System.identityHashCode(a11ySvc) == lastFailedA11yIdentity
            ) {
                continue
            }
            try {
                val (uiCtx, wm) = createWindowUiContext(winType) ?: continue

                val host = object : FrameLayout(uiCtx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK && openProgressState.floatValue > 0.05f) {
                            if (event.action == KeyEvent.ACTION_UP) {
                                animatePanelTo(0f)
                            }
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    alpha = 0f
                    visibility = View.GONE
                }
                owner.attachToView(host)

                val composeView = ComposeView(uiCtx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    owner.attachToView(this)
                    setContent {
                        val currentMode = activeSheetModeState.value
                        val currentProgress = openProgressState.floatValue
                        SideEffect {
                            lastComposedSheetMode = currentMode
                            lastComposedOpenProgress = currentProgress
                        }
                        val glassCfg = rememberCcGlassConfig(applicationContext)
                        IosControlCenterTheme {
                            IosTopOverlayRootScreen(
                                mode = currentMode,
                                controller = ctrl,
                                glassConfig = glassCfg,
                                openProgress = currentProgress,
                                sheetVelocityPxPerSec = sheetVelocityState.floatValue,
                                gesturePillEnabled = gesturePillEnabledState.value,
                                onSwitchMode = { nextMode ->
                                    if (nextMode == IosSheetMode.NOTIFICATION_CENTER) {
                                        if (!canOpenNotificationCenterNow()) {
                                            return@IosTopOverlayRootScreen
                                        }
                                        IosNotificationListenerService.instance?.refreshAll()
                                    }
                                    activeSheetModeState.value = nextMode
                                },
                                onDragCloseDelta = { dyPx ->
                                    handleInteractiveSheetCloseDelta(dyPx)
                                },
                                onDragCloseEnd = { vyPxPerSec ->
                                    handleInteractiveSheetCloseEnd(vyPxPerSec)
                                },
                                onDismiss = { animatePanelTo(0f) },
                            )
                        }
                    }
                }
                host.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                )

                @Suppress("DEPRECATION")
                val pFlagsClosed = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

                val pParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    winType,
                    pFlagsClosed,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    applyTopZeroInsetsAndCutout(this)
                    title = "SamuraiIos26TopSheet"
                }

                wm.addView(host, pParams)
                panelHostView = host
                panelComposeView = composeView
                panelParams = pParams
                panelWindowManager = wm
                appliedPanelWindowFlags = pFlagsClosed
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    attachedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                if (triggerView == null && lockHostView == null && homeBarHostView == null) {
                    activeWindowType = winType
                }
                Log.i(TAG, "Attached SamuraiIos26TopSheet at windowType=$winType")
                break
            } catch (t: Throwable) {
                if (winType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && a11ySvc != null) {
                    lastFailedA11yIdentity = System.identityHashCode(a11ySvc)
                }
                Log.w(TAG, "Failed adding SamuraiIos26TopSheet type=$winType: ${t.message}")
                if (lockHostView != null) {
                    mainHandler.removeCallbacks(windowStackRecoveryRunnable)
                    mainHandler.postDelayed(windowStackRecoveryRunnable, 120L)
                }
            }
        }
    }

    private fun publishLiveDiagnosticStatus() {
        try {
            val lockStr = lockParams?.let { "ok@${it.type}" } ?: "none"
            val trigStr = if (topGestureMonitor != null) {
                "spy@input"
            } else {
                triggerParams?.let { "fullx${it.height}@${it.type}" } ?: "none"
            }
            val panelStr = panelParams?.let { "ok@${it.type}" } ?: "none"
            val a11yStr = IosSystemOverlayAccessibilityService.instance != null
            val hbarStr = if (homeBarHostView != null) "ok" else "detached"
            Settings.Global.putString(
                contentResolver,
                "samurai_cc_status",
                "lock=$lockStr,trig=$trigStr,panel=$panelStr,a11y=$a11yStr,sbLock=$androidShadeLockedOut,kgSupp=$systemKeyguardSuppressed,swipeLock=$iosSwipeLockEngaged,unlocked=${isUserUnlocked()},hbar=$hbarStr",
            )
        } catch (_: Throwable) {
        }
    }

    private fun updateTriggerDimensionsForDisplay() {
        val trig = triggerView ?: return
        val lp = triggerParams ?: return
        val wm = triggerWindowManager ?: return
        val dm = readDisplayMetrics(trig.context, wm)
        val density = dm.density.coerceAtLeast(1f)
        val newH = readFallbackTriggerHeightPx(trig.context, density)
        if (lp.width != WindowManager.LayoutParams.MATCH_PARENT || lp.height != newH) {
            val prevW = lp.width
            val prevH = lp.height
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = newH
            try {
                wm.updateViewLayout(trig, lp)
            } catch (t: Throwable) {
                lp.width = prevW
                lp.height = prevH
                Log.w(TAG, "updateTriggerDimensionsForDisplay failed: ${t.message}")
                mainHandler.removeCallbacks(windowStackRecoveryRunnable)
                mainHandler.postDelayed(windowStackRecoveryRunnable, 120L)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTopBezelSwipeListener(
        uiCtx: Context,
        wm: WindowManager,
        touchSlop: Int,
        density: Float,
        dragRangePx: Float,
    ): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var lastMoveDy = 0f
        var lastMoveDx = 0f
        var maxDownwardDy = 0f
        var liveScreenH = dragRangePx.coerceAtLeast(640f)
        var dragging = false
        var allowed = true
        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var gestureMode = IosSheetMode.CONTROL_CENTER
        var velocityTracker: VelocityTracker? = null
        val startDragThresholdPx = (touchSlop * 0.45f).coerceAtLeast(4f * density)

        return View.OnTouchListener { _, event ->
            val nowUptime = SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (dragging) {
                        dragging = false
                        isTopBezelGestureActive = false
                        clearSheetGestureActive()
                        animatePanelTo(if (openProgressState.floatValue >= 0.5f) 1f else 0f)
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    allowed = isTopPullDownAllowedNow()
                    if (!allowed) {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        setTriggerArmed(false)
                        return@OnTouchListener false
                    }
                    val dm = readDisplayMetrics(uiCtx, wm)
                    val screenW = dm.widthPixels.coerceAtLeast(360)
                    liveScreenH = dm.heightPixels.toFloat().coerceAtLeast(640f)
                    activePointerId = event.getPointerId(0)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastMoveDy = 0f
                    lastMoveDx = 0f
                    maxDownwardDy = 0f
                    // Left 50% -> iOS 26 Notification Center (when unlocked); Right 50% -> iOS 26 Control Center.
                    // When already on the iOS 26 Lock Screen (Cover Sheet) or locked, ignore left-half swipes!
                    if (downRawX < screenW * 0.50f) {
                        if (!canOpenNotificationCenterNow()) {
                            allowed = false
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            return@OnTouchListener false
                        }
                        gestureMode = IosSheetMode.NOTIFICATION_CENTER
                    } else {
                        gestureMode = IosSheetMode.CONTROL_CENTER
                    }
                    dragging = false
                    lastGestureUptimeMs = nowUptime
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()?.also { it.addMovement(event) }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (!allowed) return@OnTouchListener false
                    val upIdx = event.actionIndex
                    if (event.getPointerId(upIdx) == activePointerId) {
                        var nextIdx = -1
                        for (i in 0 until event.pointerCount) {
                            if (i != upIdx) {
                                nextIdx = i
                                break
                            }
                        }
                        if (nextIdx >= 0) {
                            activePointerId = event.getPointerId(nextIdx)
                            val newRawX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawX(nextIdx) else event.rawX
                            val newRawY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawY(nextIdx) else event.rawY
                            downRawX = newRawX - lastMoveDx
                            downRawY = newRawY - lastMoveDy
                            velocityTracker?.clear()
                        } else {
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                        }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!allowed) return@OnTouchListener false
                    val pIdx = event.findPointerIndex(activePointerId)
                    if (pIdx < 0) return@OnTouchListener true
                    velocityTracker?.addMovement(event)
                    lastGestureUptimeMs = nowUptime
                    val curRawX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawX(pIdx) else event.rawX
                    val curRawY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getRawY(pIdx) else event.rawY
                    val dy = curRawY - downRawY
                    val dx = curRawX - downRawX
                    lastMoveDy = dy
                    lastMoveDx = dx
                    if (dy > maxDownwardDy) {
                        maxDownwardDy = dy
                    }
                    if (!dragging && dy > startDragThresholdPx && dy > abs(dx) * 0.55f) {
                        if (panelHostView == null) {
                            tryAttachPanelWindow()
                            publishLiveDiagnosticStatus()
                        }
                        if (panelHostView == null) {
                            allowed = false
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            return@OnTouchListener false
                        }
                        dragging = true
                        isTopBezelGestureActive = true
                        markSheetGestureActive(nowUptime)
                        lastTopBezelGestureUptimeMs = nowUptime
                        lastTopOpenGestureUptimeMs = nowUptime
                        progressAnimator?.cancel()
                        activeSheetModeState.value = gestureMode
                        if (gestureMode == IosSheetMode.NOTIFICATION_CENTER) {
                            IosNotificationListenerService.instance?.refreshAll()
                        }
                        syncAndroidShadeLockout()
                        controller?.onPanelOpening()
                        velocityTracker?.computeCurrentVelocity(1000)
                        sheetVelocityState.floatValue = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                            velocityTracker?.getYVelocity(activePointerId) ?: 0f
                        } else {
                            velocityTracker?.yVelocity ?: 0f
                        }
                        openProgressState.floatValue = (dy / liveScreenH).coerceIn(0.002f, 1f)
                        syncHomeBarVisibility(immediate = true)
                        ensurePanelVisibleForDrag()
                    } else if (dragging) {
                        markSheetGestureActive(nowUptime)
                        lastTopBezelGestureUptimeMs = nowUptime
                        lastTopOpenGestureUptimeMs = nowUptime
                        velocityTracker?.computeCurrentVelocity(1000)
                        sheetVelocityState.floatValue = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                            velocityTracker?.getYVelocity(activePointerId) ?: 0f
                        } else {
                            velocityTracker?.yVelocity ?: 0f
                        }
                        openProgressState.floatValue = (dy / liveScreenH).coerceIn(0.002f, 1f)
                        syncHomeBarVisibility(immediate = true)
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    val trackedId = activePointerId
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (!isCancel) {
                        velocityTracker?.addMovement(event)
                    }
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = if (trackedId != MotionEvent.INVALID_POINTER_ID) {
                        velocityTracker?.getYVelocity(trackedId) ?: 0f
                    } else {
                        velocityTracker?.yVelocity ?: 0f
                    }
                    val totalDy = lastMoveDy
                    val totalDx = lastMoveDx
                    velocityTracker?.recycle()
                    velocityTracker = null
                    lastGestureUptimeMs = nowUptime

                    if (dragging) {
                        dragging = false
                        isTopBezelGestureActive = false
                        clearSheetGestureActive()
                        lastTopBezelGestureUptimeMs = nowUptime
                        lastTopOpenGestureUptimeMs = nowUptime
                        sheetVelocityState.floatValue = vy
                        val curProg = openProgressState.floatValue
                        // CRITICAL FIX: DisplayPolicy.requestTransientBars -> mStatusBar.transferTouch()
                        // synthesizes ACTION_CANCEL on non-SPY windows at ~120ms (dy ~ 30px, curProg ~ 0.013).
                        // Whenever the user was actively dragging downward on non-SPY trigger, glide open!
                        val shouldOpen = if (isCancel) {
                            lastMoveDy >= maxDownwardDy * 0.65f && vy >= -120f * density
                        } else {
                            curProg >= 0.24f || vy >= 650f * density
                        }
                        animatePanelTo(if (shouldOpen) 1f else 0f, initialVelocityPxPerSec = vy)
                    } else if (
                        allowed &&
                        maxDownwardDy > (3.5f * density) &&
                        maxDownwardDy > abs(totalDx) * 0.55f &&
                        ((!isCancel && totalDy > (4f * density) && vy > 260f * density) || (isCancel && vy >= -80f * density))
                    ) {
                        isTopBezelGestureActive = false
                        clearSheetGestureActive()
                        lastTopBezelGestureUptimeMs = nowUptime
                        lastTopOpenGestureUptimeMs = nowUptime
                        sheetVelocityState.floatValue = vy
                        activeSheetModeState.value = gestureMode
                        if (gestureMode == IosSheetMode.NOTIFICATION_CENTER) {
                            IosNotificationListenerService.instance?.refreshAll()
                        }
                        openPanelAnimated()
                    } else {
                        isTopBezelGestureActive = false
                        clearSheetGestureActive()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun isLockOverlayVisible(): Boolean {
        return lockHostView?.visibility == View.VISIBLE
    }

    private fun isIosLockScreenShowing(): Boolean {
        return lockHostView?.visibility == View.VISIBLE || iosSwipeLockEngaged
    }

    fun isLockScreenCurrentlyEngaged(): Boolean {
        return isIosLockScreenShowing()
    }

    private fun isSwipeGestureAllowed(): Boolean {
        val cr = contentResolver
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = (km?.isKeyguardLocked == true) || iosSwipeLockEngaged
        return if (locked) {
            try {
                Settings.Global.getInt(cr, "samurai_cc_on_lock", 1) == 1
            } catch (_: Throwable) {
                true
            }
        } else {
            try {
                Settings.Global.getInt(cr, "samurai_cc_in_apps", 1) == 1
            } catch (_: Throwable) {
                true
            }
        }
    }

    private fun openPanelAnimated() {
        if (!isTopPullDownAllowedNow()) {
            return
        }
        val nowUptime = SystemClock.uptimeMillis()
        if (isCriticalInterruptBlockingSheetNow(nowUptime)) {
            return
        }
        if (activeSheetModeState.value == IosSheetMode.NOTIFICATION_CENTER && !canOpenNotificationCenterNow()) {
            return
        }
        lastGestureUptimeMs = nowUptime
        lastTopOpenGestureUptimeMs = nowUptime
        if (topGestureMonitor == null && triggerView == null) {
            tryAttachTriggerWindow()
        }
        if (panelHostView == null) {
            tryAttachPanelWindow()
            publishLiveDiagnosticStatus()
        }
        syncAndroidShadeLockout()
        if (panelHostView == null) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (!isIosLockScreenShowing() && km?.isKeyguardLocked != true && isUserUnlocked()) {
                try {
                    val isNotif = activeSheetModeState.value == IosSheetMode.NOTIFICATION_CENTER
                    val fallback = Intent(this, ControlCenterActivity::class.java).apply {
                        action = if (isNotif) ACTION_OPEN_NOTIFICATIONS else "com.realme.samurai.controlcenter.ACTION_PREVIEW"
                        putExtra("mode", if (isNotif) MODE_NOTIFICATIONS else MODE_CONTROL_CENTER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(fallback)
                } catch (_: Throwable) {
                }
            }
            return
        }
        controller?.onPanelOpening()
        if (openProgressState.floatValue < 0.005f) {
            openProgressState.floatValue = 0.005f
        }
        syncHomeBarVisibility(immediate = true)
        ensurePanelVisibleForDrag()
        animatePanelTo(1f)
    }

    private fun clearPanelPreDrawGate(host: FrameLayout? = panelHostView) {
        val listener = panelPreDrawListener ?: return
        panelPreDrawListener = null
        if (host != null) {
            try {
                val vto = host.viewTreeObserver
                if (vto.isAlive) {
                    vto.removeOnPreDrawListener(listener)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun attachPanelRevealPreDrawGate(host: FrameLayout) {
        if (panelPreDrawListener != null) return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            private var skippedFrames = 0

            override fun onPreDraw(): Boolean {
                if (panelHostView !== host || host.visibility != View.VISIBLE) {
                    clearPanelPreDrawGate(host)
                    host.alpha = 0f
                    return true
                }
                if (openProgressState.floatValue <= 0.004f &&
                    progressAnimator?.isRunning != true &&
                    !isSheetGestureActive
                ) {
                    clearPanelPreDrawGate(host)
                    host.alpha = 0f
                    return true
                }
                val expectedMode = activeSheetModeState.value
                val ready = lastComposedSheetMode == expectedMode && lastComposedOpenProgress > 0.004f
                if (ready || skippedFrames >= 3) {
                    clearPanelPreDrawGate(host)
                    host.alpha = 1f
                    return true
                }
                skippedFrames++
                panelComposeView?.invalidate()
                host.invalidate()
                host.postOnAnimation {
                    panelComposeView?.invalidate()
                    host.invalidate()
                }
                return false
            }
        }
        panelPreDrawListener = listener
        try {
            val vto = host.viewTreeObserver
            if (vto.isAlive) {
                vto.addOnPreDrawListener(listener)
            } else {
                panelPreDrawListener = null
                host.alpha = 1f
            }
        } catch (_: Throwable) {
            panelPreDrawListener = null
            host.alpha = 1f
        }
    }

    private fun ensurePanelVisibleForDrag() {
        val host = panelHostView ?: return
        val targetMode = activeSheetModeState.value
        val modeMismatch = lastComposedSheetMode != targetMode || lastComposedOpenProgress <= 0.004f
        if (host.visibility != View.VISIBLE) {
            host.alpha = 0f
            host.visibility = View.VISIBLE
        } else if (modeMismatch) {
            host.alpha = 0f
        }
        if (host.alpha < 0.99f) {
            if (lastComposedSheetMode == targetMode && lastComposedOpenProgress > 0.004f) {
                clearPanelPreDrawGate(host)
                host.alpha = 1f
            } else {
                attachPanelRevealPreDrawGate(host)
            }
        }
        panelComposeView?.invalidate()
        host.invalidate()
    }

    private fun setTriggerArmed(armed: Boolean) {
        val trig = triggerView ?: return
        val shouldShow = armed &&
            topGestureMonitor == null &&
            openProgressState.floatValue <= 0.02f &&
            isTopPullDownAllowedNow()
        val desiredVis = if (shouldShow) View.VISIBLE else View.GONE
        if (trig.visibility != desiredVis) {
            trig.visibility = desiredVis
        }
    }

    private fun rebuildDeferredLowerWindowsIfNeeded() {
        if (rebuildingWindowStack || isSheetGestureActive || openProgressState.floatValue > 0.01f) {
            return
        }
        if (!isUserUnlocked()) return
        val a11yHealthy = isA11yOverlayHealthy()
        val missingHomeBarBelow = homeBarHostView == null &&
            (lockHostView != null || panelHostView != null || triggerView != null)
        val missingLockBelow = a11yHealthy &&
            lockHostView == null &&
            (panelHostView != null || triggerView != null)
        if (!missingHomeBarBelow && !missingLockBelow) {
            return
        }
        rebuildingWindowStack = true
        try {
            tryAttachWindows()
            syncLockScreenVisibility()
        } finally {
            rebuildingWindowStack = false
        }
    }

    private fun setPanelInteractive(interactive: Boolean) {
        val wm = panelWindowManager ?: return
        val host = panelHostView ?: return
        val lp = panelParams ?: return

        @Suppress("DEPRECATION")
        val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        val desiredFlags = if (interactive) {
            baseFlags
        } else {
            baseFlags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        if (appliedPanelWindowFlags != desiredFlags || lp.flags != desiredFlags) {
            lp.flags = desiredFlags
            try {
                wm.updateViewLayout(host, lp)
                appliedPanelWindowFlags = desiredFlags
            } catch (t: Throwable) {
                lp.flags = appliedPanelWindowFlags
                Log.w(TAG, "setPanelInteractive updateViewLayout failed: ${t.message}")
                mainHandler.removeCallbacks(windowStackRecoveryRunnable)
                mainHandler.postDelayed(windowStackRecoveryRunnable, 120L)
            }
        }
        if (interactive) {
            host.isFocusableInTouchMode = true
            host.requestFocus()
        }
    }

    private fun animatePanelTo(
        target: Float,
        initialVelocityPxPerSec: Float = sheetVelocityState.floatValue,
        onEnd: (() -> Unit)? = null,
    ) {
        progressAnimator?.cancel()
        val clampedTarget = target.coerceIn(0f, 1f)
        if (clampedTarget > 0f) {
            if (panelHostView == null) {
                tryAttachPanelWindow()
                publishLiveDiagnosticStatus()
            }
            if (panelHostView == null) {
                clearSheetGestureActive()
                openProgressState.floatValue = 0f
                sheetVelocityState.floatValue = 0f
                setTriggerArmed(true)
                syncControllerPanelVisibility()
                onEnd?.invoke()
                return
            }
            if (openProgressState.floatValue < 0.005f) {
                openProgressState.floatValue = 0.005f
            }
            invalidateLockUdfpsAperture()
            syncHomeBarVisibility(immediate = true)
            ensurePanelVisibleForDrag()
            syncControllerPanelVisibility()
            if (clampedTarget >= 0.99f) {
                // Make TopSheet interactive immediately upon release-to-open so taps during the final
                // spring convergence (~210ms) never pass through FLAG_NOT_TOUCHABLE to underlying apps!
                setPanelInteractive(true)
                setTriggerArmed(false)
            }
        } else {
            setPanelInteractive(false)
            setTriggerArmed(true)
        }

        val screenH = readCurrentFullScreenHeightPx()
        val start = openProgressState.floatValue.coerceIn(0f, 1f)
        val startPosPx = start * screenH
        val targetPosPx = clampedTarget * screenH
        if (abs(startPosPx - targetPosPx) < 0.5f && abs(initialVelocityPxPerSec) < 12f) {
            openProgressState.floatValue = clampedTarget
            sheetVelocityState.floatValue = 0f
            finalizePanelState(clampedTarget)
            onEnd?.invoke()
            return
        }

        progressAnimator = startCriticallyDampedSpring(
            startPosPx = startPosPx,
            targetPosPx = targetPosPx,
            initialVelocityPxPerSec = initialVelocityPxPerSec,
            omegaRadPerSec = 26f,
            onFrame = { posPx, velPxPerSec ->
                val v = (posPx / screenH).coerceIn(0f, 1f)
                openProgressState.floatValue = v
                sheetVelocityState.floatValue = velPxPerSec
                syncHomeBarVisibility(immediate = true)
                if (clampedTarget > 0f && v > 0.004f) {
                    val host = panelHostView
                    if (host != null && host.alpha < 0.99f && panelPreDrawListener == null) {
                        ensurePanelVisibleForDrag()
                    }
                }
            },
            onSettled = {
                openProgressState.floatValue = clampedTarget
                sheetVelocityState.floatValue = 0f
                finalizePanelState(clampedTarget)
                onEnd?.invoke()
            },
        )
    }

    private fun finalizePanelState(target: Float) {
        sheetVelocityState.floatValue = 0f
        if (target >= 0.99f) {
            val host = panelHostView
            if (host != null && host.alpha < 0.99f) {
                clearPanelPreDrawGate(host)
                host.alpha = 1f
            }
            setPanelInteractive(true)
            setTriggerArmed(false)
            syncHomeBarVisibility(immediate = true)
        } else if (target <= 0.01f) {
            clearPanelPreDrawGate(panelHostView)
            openProgressState.floatValue = 0f
            lastComposedOpenProgress = 0f
            setPanelInteractive(false)
            setTriggerArmed(true)
            panelHostView?.alpha = 0f
            panelHostView?.visibility = View.GONE
            invalidateLockUdfpsAperture()
            syncControllerPanelVisibility()
            syncHomeBarVisibility(immediate = false)
            upgradeToAccessibilityOverlayIfReady()
            rebuildDeferredLowerWindowsIfNeeded()
        }
    }

    private fun closePanelImmediate() {
        progressAnimator?.cancel()
        isTopBezelGestureActive = false
        clearSheetGestureActive()
        isBottomSpyClosingSheet = false
        clearPanelPreDrawGate(panelHostView)
        openProgressState.floatValue = 0f
        sheetVelocityState.floatValue = 0f
        lastComposedOpenProgress = 0f
        setPanelInteractive(false)
        setTriggerArmed(true)
        panelHostView?.alpha = 0f
        panelHostView?.visibility = View.GONE
        invalidateLockUdfpsAperture()
        syncControllerPanelVisibility()
        syncHomeBarVisibility(immediate = false)
        if (!rebuildingWindowStack) {
            upgradeToAccessibilityOverlayIfReady()
        }
        rebuildDeferredLowerWindowsIfNeeded()
    }

    private fun removeWindowsSafely() {
        progressAnimator?.cancel()
        progressAnimator = null
        lockCurtainAnimator?.cancel()
        lockCurtainAnimator = null
        bouncerVisualMaskHoldActive = false
        cancelBouncerRevealFade(resetHostAlpha = true)
        overlayAttachRetries = 0
        isLockCurtainUnlocking = false
        isUdfpsFingerDownState.value = false
        isTopBezelGestureActive = false
        clearSheetGestureActive()
        isBottomSpyUnlockingLockScreen = false
        isBottomSpyClosingSheet = false
        isComposeLockDragging = false
        openProgressState.floatValue = 0f
        lockCurtainProgressState.floatValue = 0f
        sheetVelocityState.floatValue = 0f
        lockCurtainVelocityState.floatValue = 0f
        isLockScreenVisibleState.value = false
        homeBarFadeAnimator?.cancel()
        homeBarFadeAnimator = null
        homeBarSpringAnimator?.cancel()
        homeBarSpringAnimator = null
        mainHandler.removeCallbacks(homeBarIdleDimRunnable)
        mainHandler.removeCallbacks(homeBarPostTouchReconcileRunnable)
        mainHandler.removeCallbacks(windowStackRecoveryRunnable)
        mainHandler.removeCallbacks(postBouncerVerifyRunnable)
        mainHandler.removeCallbacks(postKeyguardLockVerifyRunnable)
        homeBarTargetShown = false
        homeBarScrubState.floatValue = 0f
        homeBarStretchState.floatValue = 0f
        homeBarLiftState.floatValue = 0f
        homeBarIdleDimState.value = false

        triggerView?.let { v ->
            try {
                triggerWindowManager?.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        triggerView = null
        triggerParams = null
        triggerWindowManager = null

        clearPanelPreDrawGate(panelHostView)
        panelHostView?.let { v ->
            try {
                panelWindowManager?.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        panelHostView = null
        panelComposeView = null
        panelParams = null
        panelWindowManager = null
        appliedPanelWindowFlags = 0
        lastComposedSheetMode = null
        lastComposedOpenProgress = 0f

        lockUdfpsApertureCleanup?.invoke()
        lockUdfpsApertureCleanup = null
        lastCarvedUdfpsAperture = null
        lockHostView?.let { v ->
            try {
                lockWindowManager?.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        lockHostView = null
        lockParams = null
        lockWindowManager = null
        appliedLockWindowFlags = 0
        mainHandler.removeCallbacks(lockSafetyWatchdog)
        syncControllerPanelVisibility()

        homeBarHostView?.let { v ->
            try {
                homeBarWindowManager?.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        homeBarHostView = null
        homeBarParams = null
        homeBarWindowManager = null

        activeWindowType = 0
        attachedA11yIdentity = 0
        publishLiveDiagnosticStatus()
    }

    companion object {
        private const val TAG = "IosControlCenter"

        @Volatile
        var activeInstance: ControlCenterOverlayService? = null

        // StatusBarManager.DISABLE_EXPAND (0x00010000)
        private const val STATUS_BAR_DISABLE_EXPAND = 0x00010000

        private const val NEXUS_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"

        private val HOME_BAR_IGNORED_TOP_PKGS = setOf(
            "com.android.systemui",
            "com.realme.samurai.controlcenter",
        )

        const val ACTION_OPEN = "com.realme.samurai.controlcenter.ACTION_OPEN"
        const val ACTION_OPEN_NOTIFICATIONS = "com.realme.samurai.controlcenter.ACTION_OPEN_NOTIFICATIONS"
        const val ACTION_CLOSE = "com.realme.samurai.controlcenter.ACTION_CLOSE"
        const val ACTION_TOGGLE = "com.realme.samurai.controlcenter.ACTION_TOGGLE"
        const val ACTION_HOME_SWIPE = "com.realme.samurai.controlcenter.ACTION_HOME_SWIPE"
        const val ACTION_ENSURE_RUNNING = "com.realme.samurai.controlcenter.ACTION_ENSURE_RUNNING"
        const val ACTION_A11Y_CONNECTED = "com.realme.samurai.controlcenter.ACTION_A11Y_CONNECTED"
        const val ACTION_BOUNCER_RESULT = "com.realme.samurai.controlcenter.ACTION_BOUNCER_RESULT"

        const val EXTRA_MODE = "mode"
        const val MODE_NOTIFICATIONS = "notifications"
        const val MODE_CONTROL_CENTER = "control_center"

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_FINISHED = "finished"
        const val EXTRA_SHOULD_OPEN = "should_open"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_UNLOCKED = "unlocked"
        const val EXTRA_VELOCITY_Y = "velocity_y"

        private const val EXTRA_INTERNAL_TOKEN = "internal_token"
        private val LOCAL_IPC_TOKEN: String = UUID.randomUUID().toString()

        private val TASK_VISIBLE_REQUESTED_FIELD: Field? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            try {
                TaskInfo::class.java.getDeclaredField("isVisibleRequested").apply {
                    isAccessible = true
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun isTaskVisibleRequested(taskInfo: TaskInfo): Boolean {
            return try {
                TASK_VISIBLE_REQUESTED_FIELD?.getBoolean(taskInfo) ?: false
            } catch (_: Throwable) {
                false
            }
        }

        fun ensureRunning(context: Context) {
            try {
                val intent = Intent(context, ControlCenterOverlayService::class.java).apply {
                    action = ACTION_ENSURE_RUNNING
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "ensureRunning startService error: ${t.message}")
            }
        }

        fun notifyAccessibilityConnected(context: Context) {
            val live = activeInstance
            if (live != null) {
                live.mainHandler.post { live.onAccessibilityServiceChanged() }
                return
            }
            try {
                val intent = Intent(context, ControlCenterOverlayService::class.java).apply {
                    action = ACTION_A11Y_CONNECTED
                    putExtra(EXTRA_INTERNAL_TOKEN, LOCAL_IPC_TOKEN)
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "notifyAccessibilityConnected error: ${t.message}")
            }
        }

        fun notifyBouncerFinished(context: Context, unlocked: Boolean) {
            val live = activeInstance
            if (live != null) {
                live.mainHandler.post { live.handleBouncerResultInternal(unlocked) }
                return
            }
            try {
                val intent = Intent(context, ControlCenterOverlayService::class.java).apply {
                    action = ACTION_BOUNCER_RESULT
                    putExtra(EXTRA_UNLOCKED, unlocked)
                    putExtra(EXTRA_INTERNAL_TOKEN, LOCAL_IPC_TOKEN)
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "notifyBouncerFinished error: ${t.message}")
            }
        }
    }
}
