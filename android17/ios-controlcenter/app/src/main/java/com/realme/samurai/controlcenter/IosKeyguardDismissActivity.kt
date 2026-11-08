package com.realme.samurai.controlcenter

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.realme.samurai.controlcenter.service.ControlCenterOverlayService
import java.util.concurrent.atomic.AtomicLong

/**
 * Zero-UI translucent trampoline Activity that triggers Android 17 SystemUI's native
 * `KeyguardManager.requestDismissKeyguard(...)` when secure PIN/Pattern/Password + Goodix G3 UDFPS
 * is enabled:
 * - Brings up the native secure bouncer and optical fingerprint sensor cleanly with zero window
 *   open/close transition frames, and runs [pendingAction] on success or restores the iOS 26
 *   Lock Screen if cancelled.
 */
class IosKeyguardDismissActivity : Activity() {

    private var resultNotified: Boolean = false
    private var activeSeq: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        activeInstance = this
        isDismissActivityAlive = true
        suppressAllWindowTransitions()
        super.onCreate(savedInstanceState)
        suppressAllWindowTransitions()

        try {
            window?.setWindowAnimations(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        } catch (_: Throwable) {
        }

        requestDismissOrFinish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeInstance = this
        isDismissActivityAlive = true
        resultNotified = false
        suppressAllWindowTransitions()
        requestDismissOrFinish()
    }

    private fun requestDismissOrFinish() {
        val mySeq = dismissRequestSeq.incrementAndGet()
        activeSeq = mySeq
        val km = getSystemService(KeyguardManager::class.java)
            ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)

        if (km == null || !km.isKeyguardLocked) {
            consumeAndRunPendingAction()
            notifyResultOnce(unlocked = true)
            finishSilently()
            return
        }

        try {
            km.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        if (mySeq != dismissRequestSeq.get()) return
                        Log.i(TAG, "Keyguard dismiss succeeded")
                        consumeAndRunPendingAction()
                        notifyResultOnce(unlocked = true)
                        finishSilently()
                    }

                    override fun onDismissCancelled() {
                        if (mySeq != dismissRequestSeq.get()) return
                        Log.i(TAG, "Keyguard dismiss cancelled -> restoring iOS 26 Lock Screen")
                        clearPendingAction()
                        notifyResultOnce(unlocked = false)
                        finishSilently()
                    }

                    override fun onDismissError() {
                        if (mySeq != dismissRequestSeq.get()) return
                        Log.w(TAG, "Keyguard dismiss error -> restoring state")
                        val unlockedNow = km.isKeyguardLocked.not()
                        if (unlockedNow) {
                            consumeAndRunPendingAction()
                        } else {
                            clearPendingAction()
                        }
                        notifyResultOnce(unlocked = unlockedNow)
                        finishSilently()
                    }
                }
            )
        } catch (t: Throwable) {
            if (mySeq != dismissRequestSeq.get()) return
            Log.w(TAG, "requestDismissKeyguard failed: ${t.message}")
            val unlockedNow = km.isKeyguardLocked.not()
            if (unlockedNow) {
                consumeAndRunPendingAction()
            } else {
                clearPendingAction()
            }
            notifyResultOnce(unlocked = unlockedNow)
            finishSilently()
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
            isDismissActivityAlive = false
        }
        if (!resultNotified && !isChangingConfigurations && activeSeq == dismissRequestSeq.get()) {
            val km = getSystemService(KeyguardManager::class.java)
                ?: (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            val unlockedNow = km?.isKeyguardLocked == false
            if (unlockedNow) {
                consumeAndRunPendingAction()
            } else {
                clearPendingAction()
            }
            notifyResultOnce(unlocked = unlockedNow)
        }
        super.onDestroy()
    }

    private fun notifyResultOnce(unlocked: Boolean) {
        if (resultNotified) return
        resultNotified = true
        ControlCenterOverlayService.notifyBouncerFinished(applicationContext, unlocked = unlocked)
    }

    private fun suppressAllWindowTransitions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            }
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Throwable) {
        }
    }

    private fun finishSilently() {
        try {
            suppressAllWindowTransitions()
            finish()
            suppressAllWindowTransitions()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "IosKeyguardDismiss"
        private val dismissRequestSeq = AtomicLong(0L)

        @Volatile
        private var activeInstance: IosKeyguardDismissActivity? = null

        @Volatile
        var isDismissActivityAlive: Boolean = false
            private set

        @Volatile
        private var pendingAction: (() -> Unit)? = null

        fun launch(context: Context, afterUnlock: (() -> Unit)? = null) {
            pendingAction = afterUnlock
            try {
                val intent = Intent(context, IosKeyguardDismissActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
                val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
                context.startActivity(intent, opts)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed launching IosKeyguardDismissActivity: ${t.message}")
                val km = context.getSystemService(KeyguardManager::class.java)
                    ?: (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                val unlockedNow = km?.isKeyguardLocked == false
                if (unlockedNow) {
                    consumeAndRunPendingAction()
                } else {
                    clearPendingAction()
                }
                ControlCenterOverlayService.notifyBouncerFinished(context.applicationContext, unlocked = unlockedNow)
            }
        }

        fun cancelOnScreenOff() {
            dismissRequestSeq.incrementAndGet()
            clearPendingAction()
            val inst = activeInstance
            if (inst != null) {
                inst.resultNotified = true
                activeInstance = null
                isDismissActivityAlive = false
                inst.finishSilently()
            }
        }

        private fun consumeAndRunPendingAction() {
            val act = pendingAction
            pendingAction = null
            try {
                act?.invoke()
            } catch (_: Throwable) {
            }
        }

        private fun clearPendingAction() {
            pendingAction = null
        }
    }
}
