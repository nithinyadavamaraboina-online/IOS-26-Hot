package com.realme.samurai.controlcenter.service

import android.app.KeyguardManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManagerGlobal
import com.android.internal.policy.IKeyguardDismissCallback
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Coordinates direct, single-swipe handoff from `SamuraiIos26LockScreen` into Android 17's
 * Primary Bouncer (`Overlays.Bouncer` — PIN / Pattern / Password pad) with zero intermediate
 * stock Android Lock Screen flash, zero second swipe, and zero premature snap-back.
 *
 * Verified Android 17 (`CP3A.260905.010`) contracts:
 * 1. Releasing `SamuraiIos26LockScreen` window focus (`FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`)
 *    transfers focus to SystemUI's `WindowRootView` (`NotificationShade`) even while our frosted
 *    wallpaper mask remains `View.VISIBLE`.
 * 2. Once `isLockWindowStillFocused()` clears, injecting `KEYCODE_DPAD_CENTER` (`23`) reaches
 *    `SysUIKeyEventHandler.dispatchKeyEvent` ->
 *    `DeviceEntryInteractor.attemptDeviceEntry("Confirm key", skipShowingAlternateBouncer = true)`,
 *    opening `Overlays.Bouncer` directly on Frame 1 instead of `AlternateBouncerView`.
 * 3. Chaining `IWindowManager.dismissKeyguard(callbackStub, ...)` `+24ms` after `KEYCODE_DPAD_CENTER`
 *    registers `callbackStub` in `DismissCallbackRegistry`, which fires `onDismissSucceeded()` on
 *    PIN/Pattern/Password/UDFPS unlock (`KeyguardStateCallbackInteractor`) and `onDismissCancelled()`
 *    when the user presses Back on `Overlays.Bouncer`
 *    (`SceneContainerStartable.notifyKeyguardDismissCancelledCallbacks`) or turns the screen off
 *    (`SceneContainerStartable.handlePowerState`).
 */
class SecureBouncerHandoffCoordinator(
    private val scheduler: Scheduler,
    private val bridge: KeyguardPlatformBridge,
    private val onSyncPassthroughAndMask: (passthroughActive: Boolean, holdVisualMask: Boolean) -> Unit,
    private val onStartVisualMaskFadeOut: (seq: Long) -> Unit,
    private val onSessionFinished: (unlocked: Boolean) -> Unit,
) {

    interface Scheduler {
        fun nowUptimeMs(): Long
        fun postDelayed(delayMs: Long, action: Runnable)
        fun removeCallbacks(action: Runnable)
    }

    interface KeyguardPlatformBridge {
        fun isKeyguardLocked(): Boolean
        fun isKeyguardSecure(): Boolean
        fun isLockWindowStillFocused(): Boolean = false
        fun injectConfirmKey(uptimeMs: Long): Boolean
        fun injectTap(xPx: Float, yPx: Float, uptimeMs: Long): Boolean
        fun dismissKeyguard(callback: IKeyguardDismissCallback, reason: String): Boolean
    }

    private val sessionSeqGen = AtomicLong(0L)

    var activeSessionSeq: Long = 0L
        private set

    val isSessionActive: Boolean
        get() = activeSessionSeq != 0L

    var sessionStartUptimeMs: Long = 0L
        private set

    var lastActivityUptimeMs: Long = 0L
        private set

    var visualMaskHoldActive: Boolean = false
        private set

    private var curtainSettledForSession: Boolean = false
    private var confirmInjectedUptimeMs: Long = 0L
    private var retriedDismissError: Boolean = false
    private var confirmKeyInjectedOk: Boolean = false
    private var alternateBouncerEscalated: Boolean = false
    private var pendingAfterUnlock: (() -> Unit)? = null

    private var scheduledConfirmRunnable: Runnable? = null
    private var scheduledDismissRunnable: Runnable? = null
    private var scheduledRetryRunnable: Runnable? = null
    private var scheduledMaskFadeRunnable: Runnable? = null

    fun startSession(
        holdVisualMask: Boolean,
        afterUnlock: (() -> Unit)?,
    ) {
        cancelScheduledTasks()
        val now = scheduler.nowUptimeMs()

        if (!bridge.isKeyguardLocked()) {
            sessionSeqGen.incrementAndGet()
            activeSessionSeq = 0L
            visualMaskHoldActive = false
            curtainSettledForSession = false
            confirmInjectedUptimeMs = 0L
            pendingAfterUnlock = null
            onSyncPassthroughAndMask(false, false)
            onSessionFinished(true)
            runSafely(afterUnlock)
            return
        }

        val secure = bridge.isKeyguardSecure()
        val seq = sessionSeqGen.incrementAndGet()
        activeSessionSeq = seq
        sessionStartUptimeMs = now
        lastActivityUptimeMs = now
        confirmInjectedUptimeMs = 0L
        retriedDismissError = false
        confirmKeyInjectedOk = false
        alternateBouncerEscalated = false
        pendingAfterUnlock = afterUnlock
        visualMaskHoldActive = holdVisualMask && secure
        curtainSettledForSession = false

        onSyncPassthroughAndMask(true, visualMaskHoldActive)

        if (secure) {
            val confirmTask = object : Runnable {
                override fun run() {
                    if (activeSessionSeq != seq) return
                    if (!bridge.isKeyguardLocked()) {
                        completeSession(seq, unlocked = true)
                        return
                    }
                    val nowConfirm = scheduler.nowUptimeMs()
                    if (bridge.isLockWindowStillFocused() &&
                        (nowConfirm - sessionStartUptimeMs) < MAX_FOCUS_RELEASE_WAIT_MS
                    ) {
                        scheduler.postDelayed(FOCUS_RELEASE_POLL_MS, this)
                        return
                    }
                    scheduledConfirmRunnable = null
                    confirmInjectedUptimeMs = nowConfirm
                    confirmKeyInjectedOk = bridge.injectConfirmKey(nowConfirm)

                    // Reschedule mask fade if curtain already crossed the settle threshold while polling focus
                    if (curtainSettledForSession && visualMaskHoldActive) {
                        scheduleOrRunMaskFade(seq)
                    }

                    // Strictly chain dismissKeyguard +24ms AFTER confirmKey so a main-thread frame hitch
                    // can never invert their arrival order in SystemUI!
                    val dismissTask = Runnable {
                        if (activeSessionSeq != seq) return@Runnable
                        scheduledDismissRunnable = null
                        if (!bridge.isKeyguardLocked()) {
                            completeSession(seq, unlocked = true)
                            return@Runnable
                        }
                        invokeDismissKeyguard(seq)
                    }
                    scheduledDismissRunnable = dismissTask
                    scheduler.postDelayed(POST_CONFIRM_DISMISS_DELAY_MS, dismissTask)
                }
            }
            scheduledConfirmRunnable = confirmTask
            scheduler.postDelayed(CONFIRM_KEY_DELAY_MS, confirmTask)
        } else {
            invokeDismissKeyguard(seq)
        }
    }

    /**
     * Updates the post-unlock action if a session was already pre-warmed at the start of the
     * Lock Screen curtain lift.
     */
    fun updatePendingAfterUnlockIfActive(afterUnlock: (() -> Unit)?) {
        if (isSessionActive && afterUnlock != null) {
            pendingAfterUnlock = afterUnlock
        }
    }

    /**
     * Idempotently called once the Lock Screen curtain crosses `0.965` (or reaches `1.0`).
     * Releases the frosted visual mask once `Overlays.Bouncer` has had both [MIN_BOUNCER_COMPOSE_MS]
     * from session start and [POST_CONFIRM_COMPOSE_MS] after `KEYCODE_DPAD_CENTER` injection.
     */
    fun notifyCurtainSettled() {
        if (!isSessionActive || curtainSettledForSession) return
        curtainSettledForSession = true
        if (!visualMaskHoldActive) return
        scheduleOrRunMaskFade(activeSessionSeq)
    }

    private fun scheduleOrRunMaskFade(seq: Long) {
        if (activeSessionSeq != seq || !visualMaskHoldActive) return
        scheduledMaskFadeRunnable?.let { scheduler.removeCallbacks(it) }
        scheduledMaskFadeRunnable = null

        val now = scheduler.nowUptimeMs()
        val minFromStartUptime = sessionStartUptimeMs + MIN_BOUNCER_COMPOSE_MS
        val minFromConfirmUptime = if (confirmInjectedUptimeMs > 0L) {
            confirmInjectedUptimeMs + POST_CONFIRM_COMPOSE_MS
        } else {
            now + POST_CONFIRM_COMPOSE_MS
        }
        val targetFadeUptime = max(minFromStartUptime, minFromConfirmUptime)
        val remainingMs = (targetFadeUptime - now).coerceAtLeast(0L)
        if (remainingMs <= 0L) {
            onStartVisualMaskFadeOut(seq)
        } else {
            val maskTask = Runnable {
                scheduledMaskFadeRunnable = null
                if (activeSessionSeq == seq && visualMaskHoldActive) {
                    onStartVisualMaskFadeOut(seq)
                }
            }
            scheduledMaskFadeRunnable = maskTask
            scheduler.postDelayed(remainingMs, maskTask)
        }
    }

    fun notifyVisualMaskFadeCompleted(seq: Long) {
        if (activeSessionSeq != seq) return
        visualMaskHoldActive = false
        onSyncPassthroughAndMask(true, false)
    }

    fun notifyUserActivity(nowUptimeMs: Long = scheduler.nowUptimeMs()) {
        if (isSessionActive) {
            lastActivityUptimeMs = nowUptimeMs
        }
    }

    /**
     * Belt-and-suspenders escalation: if SystemUI ever opens `AlternateBouncerView` (UDFPS prompt
     * over stock Android Lock Screen) during an active swipe-up bouncer session, inject a single
     * tap at `(0.5 * W, 0.30 * H)` to trigger `AlternateBouncerViewModel.onTapped()` ->
     * `PrimaryBouncer` (`Overlays.Bouncer`) in `<16ms`.
     */
    fun onAlternateBouncerDetected(screenWidthPx: Float, screenHeightPx: Float): Boolean {
        if (!isSessionActive || alternateBouncerEscalated) return false
        alternateBouncerEscalated = true
        val now = scheduler.nowUptimeMs()
        lastActivityUptimeMs = now
        val tapX = (screenWidthPx * 0.5f).coerceAtLeast(1f)
        val tapY = (screenHeightPx * 0.30f).coerceAtLeast(1f)
        return bridge.injectTap(tapX, tapY, now)
    }

    fun onUserPresentOrKeyguardUnlocked() {
        if (!isSessionActive || bridge.isKeyguardLocked()) return
        completeSession(activeSessionSeq, unlocked = true)
    }

    fun onScreenOff() {
        cancelActiveSession()
    }

    fun cancelActiveSession() {
        sessionSeqGen.incrementAndGet()
        activeSessionSeq = 0L
        visualMaskHoldActive = false
        curtainSettledForSession = false
        confirmInjectedUptimeMs = 0L
        pendingAfterUnlock = null
        cancelScheduledTasks()
    }

    fun checkWatchdog(
        nowUptimeMs: Long,
        imeVisible: Boolean,
        secureCameraActive: Boolean,
        taskOccluding: Boolean,
    ) {
        if (!isSessionActive) return
        if (!bridge.isKeyguardLocked()) {
            completeSession(activeSessionSeq, unlocked = true)
            return
        }
        if (imeVisible) {
            lastActivityUptimeMs = nowUptimeMs
            return
        }
        if (!secureCameraActive &&
            !taskOccluding &&
            (nowUptimeMs - lastActivityUptimeMs) > IDLE_WATCHDOG_TIMEOUT_MS
        ) {
            Log.w(TAG, "Watchdog: bouncer session idle > ${IDLE_WATCHDOG_TIMEOUT_MS}ms; restoring Lock Screen")
            completeSession(activeSessionSeq, unlocked = false)
        }
    }

    private fun invokeDismissKeyguard(seq: Long) {
        val callback = object : IKeyguardDismissCallback.Stub() {
            override fun onDismissSucceeded() {
                scheduler.postDelayed(0L, Runnable { onCallbackSucceeded(seq) })
            }

            override fun onDismissCancelled() {
                scheduler.postDelayed(0L, Runnable { onCallbackCancelled(seq) })
            }

            override fun onDismissError() {
                scheduler.postDelayed(0L, Runnable { onCallbackError(seq) })
            }
        }
        val dispatched = bridge.dismissKeyguard(callback, DISMISS_REASON)
        if (!dispatched) {
            if (!retriedDismissError) {
                onCallbackError(seq)
            } else if (!confirmKeyInjectedOk) {
                val unlockedNow = !bridge.isKeyguardLocked()
                completeSession(seq, unlocked = unlockedNow)
            }
        }
    }

    internal fun onCallbackSucceeded(seq: Long) {
        if (activeSessionSeq != seq) return
        completeSession(seq, unlocked = true)
    }

    internal fun onCallbackCancelled(seq: Long) {
        if (activeSessionSeq != seq) return
        val unlockedNow = !bridge.isKeyguardLocked()
        completeSession(seq, unlocked = unlockedNow)
    }

    internal fun onCallbackError(seq: Long) {
        if (activeSessionSeq != seq) return
        if (!bridge.isKeyguardLocked()) {
            completeSession(seq, unlocked = true)
            return
        }
        if (!retriedDismissError) {
            retriedDismissError = true
            val retryTask = Runnable {
                scheduledRetryRunnable = null
                if (activeSessionSeq != seq) return@Runnable
                if (!bridge.isKeyguardLocked()) {
                    completeSession(seq, unlocked = true)
                } else {
                    invokeDismissKeyguard(seq)
                }
            }
            scheduledRetryRunnable = retryTask
            scheduler.postDelayed(ERROR_RETRY_DELAY_MS, retryTask)
        } else {
            completeSession(seq, unlocked = false)
        }
    }

    private fun completeSession(seq: Long, unlocked: Boolean) {
        if (activeSessionSeq != seq || seq == 0L) return
        sessionSeqGen.incrementAndGet()
        activeSessionSeq = 0L
        visualMaskHoldActive = false
        curtainSettledForSession = false
        confirmInjectedUptimeMs = 0L
        cancelScheduledTasks()
        val action = if (unlocked) pendingAfterUnlock else null
        pendingAfterUnlock = null
        onSessionFinished(unlocked)
        if (unlocked && action != null) {
            runSafely(action)
        }
    }

    private fun cancelScheduledTasks() {
        scheduledConfirmRunnable?.let { scheduler.removeCallbacks(it) }
        scheduledConfirmRunnable = null
        scheduledDismissRunnable?.let { scheduler.removeCallbacks(it) }
        scheduledDismissRunnable = null
        scheduledRetryRunnable?.let { scheduler.removeCallbacks(it) }
        scheduledRetryRunnable = null
        scheduledMaskFadeRunnable?.let { scheduler.removeCallbacks(it) }
        scheduledMaskFadeRunnable = null
    }

    private fun runSafely(block: (() -> Unit)?) {
        if (block == null) return
        try {
            block.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "Post-unlock action failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SecureBouncerHandoff"
        private const val DISMISS_REASON = "SamuraiIos26Unlock"

        const val CONFIRM_KEY_DELAY_MS = 20L
        const val FOCUS_RELEASE_POLL_MS = 12L
        const val MAX_FOCUS_RELEASE_WAIT_MS = 96L
        const val POST_CONFIRM_DISMISS_DELAY_MS = 24L
        const val DISMISS_KEYGUARD_DELAY_MS = CONFIRM_KEY_DELAY_MS + POST_CONFIRM_DISMISS_DELAY_MS
        const val ERROR_RETRY_DELAY_MS = 120L
        const val MIN_BOUNCER_COMPOSE_MS = 115L
        const val POST_CONFIRM_COMPOSE_MS = 95L
        const val IDLE_WATCHDOG_TIMEOUT_MS = 60_000L
    }
}

class HandlerBouncerScheduler(
    private val handler: Handler,
) : SecureBouncerHandoffCoordinator.Scheduler {
    override fun nowUptimeMs(): Long = SystemClock.uptimeMillis()

    override fun postDelayed(delayMs: Long, action: Runnable) {
        if (delayMs <= 0L) {
            handler.post(action)
        } else {
            handler.postDelayed(action, delayMs)
        }
    }

    override fun removeCallbacks(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

class AndroidKeyguardPlatformBridge(
    private val context: Context,
    private val isLockWindowFocusedProvider: () -> Boolean = { false },
) : SecureBouncerHandoffCoordinator.KeyguardPlatformBridge {

    @Volatile
    private var cachedInjectMethod: Method? = null

    override fun isKeyguardLocked(): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
            ?: (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        return km?.isKeyguardLocked == true
    }

    override fun isKeyguardSecure(): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
            ?: (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
        return km?.isKeyguardSecure == true
    }

    override fun isLockWindowStillFocused(): Boolean {
        return try {
            isLockWindowFocusedProvider()
        } catch (_: Throwable) {
            false
        }
    }

    override fun injectConfirmKey(uptimeMs: Long): Boolean {
        return try {
            val down = KeyEvent(
                uptimeMs,
                uptimeMs,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD,
            )
            val downOk = injectInputEventInternal(down)
            val upEventTime = SystemClock.uptimeMillis().coerceAtLeast(uptimeMs)
            val up = KeyEvent(
                uptimeMs,
                upEventTime,
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_CENTER,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD,
            )
            val upOk = injectInputEventInternal(up)
            downOk && upOk
        } catch (t: Throwable) {
            Log.w(TAG, "injectConfirmKey failed: ${t.message}")
            false
        }
    }

    override fun injectTap(xPx: Float, yPx: Float, uptimeMs: Long): Boolean {
        var down: MotionEvent? = null
        var up: MotionEvent? = null
        return try {
            down = MotionEvent.obtain(uptimeMs, uptimeMs, MotionEvent.ACTION_DOWN, xPx, yPx, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val downOk = injectInputEventInternal(down)
            val upEventTime = SystemClock.uptimeMillis().coerceAtLeast(uptimeMs)
            up = MotionEvent.obtain(uptimeMs, upEventTime, MotionEvent.ACTION_UP, xPx, yPx, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val upOk = injectInputEventInternal(up)
            downOk && upOk
        } catch (t: Throwable) {
            Log.w(TAG, "injectTap failed: ${t.message}")
            false
        } finally {
            down?.recycle()
            up?.recycle()
        }
    }

    override fun dismissKeyguard(callback: IKeyguardDismissCallback, reason: String): Boolean {
        return try {
            val wms = WindowManagerGlobal.getWindowManagerService() ?: return false
            wms.dismissKeyguard(callback, reason)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "IWindowManager.dismissKeyguard failed: ${t.message}")
            false
        }
    }

    private fun injectInputEventInternal(event: InputEvent): Boolean {
        val im = context.getSystemService(InputManager::class.java)
            ?: (context.getSystemService(Context.INPUT_SERVICE) as? InputManager)
            ?: return false
        val method = cachedInjectMethod ?: im.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        ).also {
            cachedInjectMethod = it
        }
        return (method.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean) == true
    }

    companion object {
        private const val TAG = "KeyguardPlatformBridge"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
