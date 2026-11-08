package com.realme.samurai.controlcenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.android.internal.policy.IKeyguardDismissCallback
import com.github.takahirom.roborazzi.captureRoboImage
import com.realme.samurai.controlcenter.service.SecureBouncerHandoffCoordinator
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.LockScreenGlassSceneHost
import com.realme.samurai.controlcenter.ui.theme.IosControlCenterTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SecureBouncerHandoffTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeManualScheduler(
        var currentUptimeMs: Long = 1_000L,
    ) : SecureBouncerHandoffCoordinator.Scheduler {

        data class QueuedTask(
            val dueUptimeMs: Long,
            val order: Long,
            val action: Runnable,
        )

        private var nextOrder = 0L
        private val queue = mutableListOf<QueuedTask>()

        override fun nowUptimeMs(): Long = currentUptimeMs

        override fun postDelayed(delayMs: Long, action: Runnable) {
            queue.add(
                QueuedTask(
                    dueUptimeMs = currentUptimeMs + delayMs.coerceAtLeast(0L),
                    order = nextOrder++,
                    action = action,
                )
            )
        }

        override fun removeCallbacks(action: Runnable) {
            queue.removeAll { it.action === action }
        }

        fun advanceTo(targetUptimeMs: Long) {
            require(targetUptimeMs >= currentUptimeMs)
            while (true) {
                val next = queue
                    .filter { it.dueUptimeMs <= targetUptimeMs }
                    .minWithOrNull(compareBy<QueuedTask> { it.dueUptimeMs }.thenBy { it.order })
                    ?: break
                queue.remove(next)
                currentUptimeMs = next.dueUptimeMs
                next.action.run()
            }
            currentUptimeMs = targetUptimeMs
        }

        fun advanceBy(deltaMs: Long) {
            advanceTo(currentUptimeMs + deltaMs)
        }
    }

    private class FakeKeyguardBridge(
        var keyguardLocked: Boolean = true,
        var keyguardSecure: Boolean = true,
        var lockWindowFocused: Boolean = false,
        var confirmKeyResult: Boolean = true,
        var dismissCallResult: Boolean = true,
    ) : SecureBouncerHandoffCoordinator.KeyguardPlatformBridge {

        val confirmKeyTimes = mutableListOf<Long>()
        val injectedTaps = mutableListOf<Triple<Float, Float, Long>>()
        val dismissCallbacks = mutableListOf<IKeyguardDismissCallback>()
        val dismissTimes = mutableListOf<Long>()
        val dismissReasons = mutableListOf<String>()
        var currentTimeProvider: () -> Long = { 0L }

        override fun isKeyguardLocked(): Boolean = keyguardLocked

        override fun isKeyguardSecure(): Boolean = keyguardSecure

        override fun isLockWindowStillFocused(): Boolean = lockWindowFocused

        override fun injectConfirmKey(uptimeMs: Long): Boolean {
            confirmKeyTimes.add(uptimeMs)
            return confirmKeyResult
        }

        override fun injectTap(xPx: Float, yPx: Float, uptimeMs: Long): Boolean {
            injectedTaps.add(Triple(xPx, yPx, uptimeMs))
            return true
        }

        override fun dismissKeyguard(callback: IKeyguardDismissCallback, reason: String): Boolean {
            dismissCallbacks.add(callback)
            dismissTimes.add(currentTimeProvider())
            dismissReasons.add(reason)
            return dismissCallResult
        }
    }

    private class HandoffHarness(
        val scheduler: FakeManualScheduler = FakeManualScheduler(),
        val bridge: FakeKeyguardBridge = FakeKeyguardBridge(),
    ) {
        init {
            bridge.currentTimeProvider = { scheduler.nowUptimeMs() }
        }
        var passthroughActive = false
        var visualMaskHold = false
        val fadeOutRequests = mutableListOf<Pair<Long, Long>>()
        val finishedResults = mutableListOf<Pair<Boolean, Long>>()

        val coordinator = SecureBouncerHandoffCoordinator(
            scheduler = scheduler,
            bridge = bridge,
            onSyncPassthroughAndMask = { passthrough, holdMask ->
                passthroughActive = passthrough
                visualMaskHold = holdMask
            },
            onStartVisualMaskFadeOut = { seq ->
                fadeOutRequests.add(seq to scheduler.nowUptimeMs())
            },
            onSessionFinished = { unlocked ->
                passthroughActive = false
                visualMaskHold = false
                finishedResults.add(unlocked to scheduler.nowUptimeMs())
            },
        )
    }

    @Test
    fun secureSwipeUp_opensPrimaryBouncerInOneSwipe_without2500msSnapBack() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0

        // t = 1000ms: user releases swipe-up on SamuraiIos26LockScreen
        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        assertTrue("Expected passthrough active immediately at t=0ms", h.passthroughActive)
        assertTrue("Expected frosted visual mask held at t=0ms", h.visualMaskHold)
        assertEquals(0, h.bridge.confirmKeyTimes.size)
        assertEquals(0, h.bridge.dismissCallbacks.size)

        // Fast fling settles curtain early at +60ms -> mask MUST stay held until >= +115ms
        h.scheduler.advanceBy(20L)
        assertEquals("Expected KEYCODE_DPAD_CENTER at +20ms", listOf(1_020L), h.bridge.confirmKeyTimes)
        assertEquals(0, h.bridge.dismissCallbacks.size)

        h.scheduler.advanceBy(24L)
        assertEquals("Expected IWindowManager.dismissKeyguard at +44ms", 1, h.bridge.dismissCallbacks.size)
        assertEquals(listOf("SamuraiIos26Unlock"), h.bridge.dismissReasons)

        h.scheduler.advanceBy(16L) // t = 1060ms (+60ms)
        h.coordinator.notifyCurtainSettled()
        assertTrue("Mask must still be held at +60ms while Overlays.Bouncer composes", h.fadeOutRequests.isEmpty())

        h.scheduler.advanceBy(55L) // t = 1115ms (+115ms == MIN_BOUNCER_COMPOSE_MS)
        assertEquals("Expected visual mask fade-out at +115ms", 1, h.fadeOutRequests.size)
        val activeSeq = h.fadeOutRequests.single().first
        h.coordinator.notifyVisualMaskFadeCompleted(activeSeq)
        assertTrue("Passthrough stays open for PIN entry", h.passthroughActive)
        assertFalse("Visual mask released after crossfade onto PIN pad", h.visualMaskHold)

        // Critical regression check: at +2500ms and +30_000ms while user enters PIN, SamuraiIos26LockScreen MUST NOT snap back!
        h.scheduler.advanceBy(3_000L)
        h.coordinator.checkWatchdog(
            nowUptimeMs = h.scheduler.nowUptimeMs(),
            imeVisible = false,
            secureCameraActive = false,
            taskOccluding = false,
        )
        assertTrue("Session must stay open past 2.5s (no premature iOS Lock Screen snap-back)", h.coordinator.isSessionActive)
        assertTrue(h.finishedResults.isEmpty())

        h.scheduler.advanceBy(27_000L)
        h.coordinator.notifyUserActivity()
        h.scheduler.advanceBy(45_000L)
        h.coordinator.checkWatchdog(
            nowUptimeMs = h.scheduler.nowUptimeMs(),
            imeVisible = false,
            secureCameraActive = false,
            taskOccluding = false,
        )
        assertTrue("User touch activity extends bouncer session cleanly", h.coordinator.isSessionActive)

        // Only after > 60_000ms of total inactivity (no IME, no touch) does watchdog self-heal
        h.scheduler.advanceBy(20_000L)
        h.coordinator.checkWatchdog(
            nowUptimeMs = h.scheduler.nowUptimeMs(),
            imeVisible = false,
            secureCameraActive = false,
            taskOccluding = false,
        )
        assertFalse(h.coordinator.isSessionActive)
        assertEquals(1, h.finishedResults.size)
        assertFalse(h.finishedResults.single().first)
        assertEquals(0, afterUnlockRuns)
    }

    @Test
    fun pinUnlockSucceeded_executesAfterUnlockOnce_andClearsPassthrough() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0

        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        h.scheduler.advanceBy(50L)
        val cb = h.bridge.dismissCallbacks.single()

        h.bridge.keyguardLocked = false
        cb.onDismissSucceeded()
        h.scheduler.advanceBy(1L)

        assertFalse(h.coordinator.isSessionActive)
        assertFalse(h.passthroughActive)
        assertEquals(1, h.finishedResults.size)
        assertTrue(h.finishedResults.single().first)
        assertEquals(1, afterUnlockRuns)

        // Subsequent ACTION_USER_PRESENT / KeyguardLockedStateListener(!isLocked) is a safe no-op
        h.coordinator.onUserPresentOrKeyguardUnlocked()
        assertEquals(1, afterUnlockRuns)
        assertEquals(1, h.finishedResults.size)
    }

    @Test
    fun backOnBouncer_onDismissCancelled_restoresIosLockScreenImmediately_andDropsPendingAction() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0

        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        h.scheduler.advanceBy(50L)
        val cb = h.bridge.dismissCallbacks.single()

        // User presses/swipes Back on Overlays.Bouncer while still locked
        h.bridge.keyguardLocked = true
        cb.onDismissCancelled()
        h.scheduler.advanceBy(1L)

        assertFalse(h.coordinator.isSessionActive)
        assertFalse(h.passthroughActive)
        assertEquals(1, h.finishedResults.size)
        assertFalse("Expected unlocked=false so SamuraiIos26LockScreen restores immediately", h.finishedResults.single().first)
        assertEquals("Cancelled bouncer must never execute pending post-unlock action", 0, afterUnlockRuns)
    }

    @Test
    fun fingerprintUnlockBeforeDismissCallback_runsAfterUnlockAtMostOnce() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0

        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        h.scheduler.advanceBy(50L)
        val cb = h.bridge.dismissCallbacks.single()

        // KeyguardLockedStateListener(!isLocked) arrives 4ms before Binder onDismissSucceeded
        h.bridge.keyguardLocked = false
        h.coordinator.onUserPresentOrKeyguardUnlocked()
        assertEquals(1, afterUnlockRuns)
        assertEquals(1, h.finishedResults.size)
        assertTrue(h.finishedResults.single().first)

        // Late Binder onDismissSucceeded arrives -> must NOT fire afterUnlock a second time
        cb.onDismissSucceeded()
        h.scheduler.advanceBy(5L)
        assertEquals(1, afterUnlockRuns)
        assertEquals(1, h.finishedResults.size)
    }

    @Test
    fun screenOff_invalidatesSequence_ignoringLateBinderCallbacks() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0

        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        h.scheduler.advanceBy(50L)
        val staleCb = h.bridge.dismissCallbacks.single()

        // User presses Power to sleep while PIN pad is open
        h.coordinator.onScreenOff()
        assertFalse(h.coordinator.isSessionActive)

        // Late SystemUI SceneContainerStartable.handlePowerState -> notifyDismissCancelled arrives
        staleCb.onDismissCancelled()
        staleCb.onDismissSucceeded()
        h.scheduler.advanceBy(10L)

        assertTrue("Stale callbacks after screen-off must be ignored completely", h.finishedResults.isEmpty())
        assertEquals(0, afterUnlockRuns)
    }

    @Test
    fun alternateBouncerDetected_injectsSingleEscalationTapIntoUpperViewport() {
        val h = HandoffHarness()
        h.coordinator.startSession(holdVisualMask = true, afterUnlock = null)
        h.scheduler.advanceBy(50L)

        val first = h.coordinator.onAlternateBouncerDetected(1080f, 2400f)
        val second = h.coordinator.onAlternateBouncerDetected(1080f, 2400f)

        assertTrue(first)
        assertFalse("Escalation tap must fire at most once per session", second)
        assertEquals(1, h.bridge.injectedTaps.size)
        val (tapX, tapY, _) = h.bridge.injectedTaps.single()
        assertEquals(540f, tapX, 0.5f)
        assertEquals(720f, tapY, 0.5f)
    }

    @Test
    fun dismissError_retriesOnceAt120ms_thenFailsClosedIfStillErroring() {
        val h = HandoffHarness()
        h.coordinator.startSession(holdVisualMask = false, afterUnlock = null)
        h.scheduler.advanceBy(50L)
        assertEquals(1, h.bridge.dismissCallbacks.size)

        // First transient error -> schedules 1 retry at +120ms
        h.bridge.dismissCallbacks[0].onDismissError()
        h.scheduler.advanceBy(10L)
        assertTrue(h.coordinator.isSessionActive)
        assertEquals(1, h.bridge.dismissCallbacks.size)

        h.scheduler.advanceBy(120L)
        assertEquals("Expected single retry of dismissKeyguard", 2, h.bridge.dismissCallbacks.size)

        // Second error -> fails closed cleanly back to SamuraiIos26LockScreen
        h.bridge.dismissCallbacks[1].onDismissError()
        h.scheduler.advanceBy(10L)
        assertFalse(h.coordinator.isSessionActive)
        assertEquals(1, h.finishedResults.size)
        assertFalse(h.finishedResults.single().first)
    }

    @Test
    fun focusReleasePoll_waitsForWindowFocusRelease_andChainsDismissKeyguard24msAfterConfirmKey() {
        val h = HandoffHarness()
        h.bridge.lockWindowFocused = true
        h.coordinator.startSession(holdVisualMask = true, afterUnlock = null)

        // At +20ms and +32ms, SamuraiIos26LockScreen has not yet finished ViewRootImpl focus relayout
        h.scheduler.advanceBy(32L) // t = 1032ms
        assertTrue("Must wait for lockHostView focus release before injecting DPAD_CENTER", h.bridge.confirmKeyTimes.isEmpty())
        assertTrue("dismissKeyguard must NEVER fire ahead of DPAD_CENTER", h.bridge.dismissTimes.isEmpty())

        // Focus transfers to NotificationShade before +44ms poll
        h.bridge.lockWindowFocused = false
        h.scheduler.advanceBy(12L) // t = 1044ms
        assertEquals(listOf(1_044L), h.bridge.confirmKeyTimes)
        assertTrue("dismissKeyguard must be chained +24ms after DPAD_CENTER", h.bridge.dismissTimes.isEmpty())

        // Curtain crosses 0.965 early at t = 1060ms -> mask fade must still wait for 1044 + 95 = 1139ms
        h.scheduler.advanceBy(16L) // t = 1060ms
        h.coordinator.notifyCurtainSettled()
        assertEquals(listOf(1_068L).filter { it <= 1_060L }, h.bridge.dismissTimes)
        assertTrue(h.fadeOutRequests.isEmpty())

        h.scheduler.advanceBy(8L) // t = 1068ms (+24ms after 1044ms confirmKey!)
        assertEquals("Expected dismissKeyguard strictly +24ms after confirmKey", listOf(1_068L), h.bridge.dismissTimes)
        assertTrue("Mask still held while Overlays.Bouncer composes", h.fadeOutRequests.isEmpty())

        h.scheduler.advanceBy(71L) // t = 1139ms (1044 + 95ms)
        assertEquals(1, h.fadeOutRequests.size)
        assertEquals(1_139L, h.fadeOutRequests.single().second)
    }

    @Test
    fun staleUserPresentWhileStillLocked_isIgnoredWithoutCompletingSession() {
        val h = HandoffHarness()
        var afterUnlockRuns = 0
        h.coordinator.startSession(holdVisualMask = true) { afterUnlockRuns++ }
        h.scheduler.advanceBy(50L)

        // Queued ACTION_USER_PRESENT from an earlier unlock arrives after rapid re-lock (keyguardLocked == true)
        h.bridge.keyguardLocked = true
        h.coordinator.onUserPresentOrKeyguardUnlocked()

        assertTrue("Active bouncer session must remain open when keyguard is still locked", h.coordinator.isSessionActive)
        assertEquals(0, afterUnlockRuns)
        assertTrue(h.finishedResults.isEmpty())
    }

    @Test
    fun transientFalseDismissKeyguard_retriesAt120msEvenWhenConfirmKeySucceeded() {
        val h = HandoffHarness()
        h.bridge.dismissCallResult = false
        h.coordinator.startSession(holdVisualMask = true, afterUnlock = null)

        // t = 1044ms: first dismissKeyguard returns false even though DPAD_CENTER succeeded at 1020ms
        h.scheduler.advanceBy(50L)
        assertEquals(1, h.bridge.confirmKeyTimes.size)
        assertEquals(1, h.bridge.dismissCallbacks.size)
        assertTrue(h.coordinator.isSessionActive)

        // Retry at +120ms succeeds and registers callback so Back on Overlays.Bouncer works!
        h.bridge.dismissCallResult = true
        h.scheduler.advanceBy(120L)
        assertEquals(2, h.bridge.dismissCallbacks.size)

        h.bridge.dismissCallbacks.last().onDismissCancelled()
        h.scheduler.advanceBy(1L)
        assertFalse(h.coordinator.isSessionActive)
        assertEquals(1, h.finishedResults.size)
        assertFalse(h.finishedResults.single().first)
    }

    @Test
    fun notifyCurtainSettled_isIdempotentAcrossEarly0965ThresholdAndSpringTail() {
        val h = HandoffHarness()
        h.coordinator.startSession(holdVisualMask = true, afterUnlock = null)
        h.scheduler.advanceBy(185L) // >= 115ms & >= 20+95ms

        // Called first at nextProg >= 0.965f, then again when spring tail hits 1.0f
        h.coordinator.notifyCurtainSettled()
        h.coordinator.notifyCurtainSettled()

        assertEquals("Visual mask fade-out must start once and not restart at spring settlement", 1, h.fadeOutRequests.size)
    }

    @Test
    fun secureCurtainExteriorBackdrop_masksStockLockScreenAndFrostsSmoothly() {
        val wp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint()
            for (y in 0 until 640 step 16) {
                paint.color = if (((y / 16) and 1) == 0) {
                    Color.rgb(235, 195, 130)
                } else {
                    Color.rgb(35, 70, 145)
                }
                canvas.drawRect(0f, y.toFloat(), 360f, (y + 16).toFloat(), paint)
            }
        }
        val liftProgress = mutableFloatStateOf(0.42f)

        compose.setContent {
            val density = LocalDensity.current
            val screenHPx = with(density) { 844.dp.toPx() }
            IosControlCenterTheme {
                LockScreenGlassSceneHost(
                    wallpaperBitmap = wp,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 26, frostPct = 22),
                    blurBackdrop = false,
                    modifier = Modifier.fillMaxSize(),
                    sheetEdgeYPx = ((1f - liftProgress.floatValue) * screenHPx).coerceIn(0f, screenHPx),
                    sheetVelocityPxPerSec = -900f,
                    outsideBackdropAlpha = 1f,
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }

        val midBmp = captureAndDecode("test_secure_ls_backdrop_mid_42")
        val sampleX = (midBmp.width * 0.5f).roundToInt()
        val lowerExteriorY = (midBmp.height * 0.82f).roundToInt()

        assertEquals(
            "Secure Lock Screen exterior below lifting curtain MUST stay 100% opaque (alpha=255) so stock Android Lock Screen never flashes",
            255,
            Color.alpha(midBmp.getPixel(sampleX, lowerExteriorY)),
        )

        // Compare stripe contrast above curtain (sharp interior) vs below curtain (frosted passcode backdrop)
        fun stripeContrast(bmp: Bitmap, yStartFrac: Float, yEndFrac: Float): Float {
            var minL = 255f
            var maxL = 0f
            for (y in (bmp.height * yStartFrac).roundToInt()..(bmp.height * yEndFrac).roundToInt() step 2) {
                val px = bmp.getPixel(sampleX, y)
                val l = 0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)
                if (l < minL) minL = l
                if (l > maxL) maxL = l
            }
            return maxL - minL
        }

        val sharpInteriorContrast = stripeContrast(midBmp, 0.18f, 0.34f)
        val midExteriorContrast = stripeContrast(midBmp, 0.72f, 0.88f)
        assertTrue(
            "Expected frosted exterior contrast ($midExteriorContrast) to be softer than sharp interior ($sharpInteriorContrast)",
            midExteriorContrast < sharpInteriorContrast,
        )

        // At full lift (1.0f), entire viewport remains opaque frosted passcode backdrop ready for 85ms alpha crossfade
        liftProgress.floatValue = 1.0f
        val fullBmp = captureAndDecode("test_secure_ls_backdrop_full_100")
        assertEquals(
            "Full curtain lift (1.0f) must keep 100% opaque frosted backdrop until Overlays.Bouncer crossfades",
            255,
            Color.alpha(fullBmp.getPixel(sampleX, lowerExteriorY)),
        )
        val fullExteriorContrast = stripeContrast(fullBmp, 0.72f, 0.88f)
        assertTrue(
            "Full lift backdrop ($fullExteriorContrast) must be at least as frosted/dimmed as mid-lift ($midExteriorContrast)",
            fullExteriorContrast <= midExteriorContrast + 2f,
        )
    }

    private fun captureAndDecode(name: String): Bitmap {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        val path = "build/outputs/roborazzi/$name.png"
        compose.onRoot().captureRoboImage(path)
        val bmp = BitmapFactory.decodeFile(File(path).absolutePath)
        assertNotNull("Expected rendered bitmap at $path", bmp)
        return bmp!!
    }
}
