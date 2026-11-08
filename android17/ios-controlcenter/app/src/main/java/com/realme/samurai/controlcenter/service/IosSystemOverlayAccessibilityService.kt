package com.realme.samurai.controlcenter.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.realme.samurai.controlcenter.IosKeyguardDismissActivity

/**
 * Lightweight AccessibilityService providing:
 * 1. Genuine TYPE_ACCESSIBILITY_OVERLAY (2032, Z-layer 31) window context so the 1:1 iOS 26
 *    Notification Center + Control Center sheet, Lock Screen curtain, and SamuraiIos26HomeBar
 *    render cleanly in system overlay space.
 * 2. Low-latency (40ms) interactive window-list signals (`TYPE_INPUT_METHOD` IME visibility,
 *    foreground window package transitions, and secure-keyguard app occlusion / shade presence)
 *    with zero view-tree / AccessibilityNodeInfo queries.
 */
class IosSystemOverlayAccessibilityService : AccessibilityService() {

    private val windowPkgById = SparseArray<String>()
    private val tmpBoundsRect = Rect()
    private var lastImeVisible: Boolean? = null
    private var lastHasExternalFullscreenApp: Boolean? = null
    private var lastHasFullscreenSystemShade: Boolean? = null
    private var lastAlternateBouncerVisible: Boolean? = null
    private var lastTopAppWindowId: Int = -1
    private var lastTopPkg: String? = null
    private var lastDeliveredTopPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 40L
            serviceInfo = info
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update AccessibilityServiceInfo window flags", t)
        }
        Log.i(TAG, "IosSystemOverlayAccessibilityService connected (TYPE_ACCESSIBILITY_OVERLAY + 40ms window signals ready)")
        val cb = onConnectedCallback
        if (cb != null) {
            cb.invoke(this)
        } else {
            ControlCenterOverlayService.notifyAccessibilityConnected(applicationContext)
        }
        dispatchWindowSignals(topWindowPkg = null, force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val topPkg = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val evWinId = event.windowId
            val evPkg = event.packageName?.toString()?.takeIf { it.isNotBlank() }
            val evCls = event.className?.toString().orEmpty()
            if (evWinId >= 0 && evPkg != null) {
                windowPkgById.put(evWinId, evPkg)
            }
            if (evCls.contains("CameraImageActivity", ignoreCase = true) ||
                evCls.contains("SecureCamera", ignoreCase = true) ||
                evCls.contains("CameraVideoShortcutActivity", ignoreCase = true)
            ) {
                ControlCenterOverlayService.activeInstance?.onCameraLaunchDetected("a11y:$evCls")
            }
            val ownPkg = packageName
            val isActiveAppWindow = try {
                val curWindows = windows
                val frontAppWinId = curWindows?.firstOrNull { w ->
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        windowPkgById.get(w.id) != ownPkg
                }?.id ?: -1
                evWinId >= 0 && curWindows?.any { w ->
                    w.id == evWinId &&
                        w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (w.isFocused || w.isActive || w.id == frontAppWinId)
                } == true
            } catch (_: Throwable) {
                false
            }
            if (isActiveAppWindow) evPkg else null
        } else {
            null
        }
        dispatchWindowSignals(topWindowPkg = topPkg, force = false)
    }

    fun resyncWindowSignals() {
        dispatchWindowSignals(topWindowPkg = null, force = true)
    }

    private fun dispatchWindowSignals(topWindowPkg: String?, force: Boolean) {
        // Inspect interactive window metadata ONLY — never query getRootInActiveWindow() or window.root.
        val winList = try {
            windows
        } catch (_: Throwable) {
            null
        }.orEmpty()

        for (i in windowPkgById.size() - 1 downTo 0) {
            val trackedId = windowPkgById.keyAt(i)
            if (winList.none { it.id == trackedId }) {
                windowPkgById.removeAt(i)
            }
        }

        val minHalfScreenH = try {
            (resources.displayMetrics.heightPixels / 2).coerceAtLeast(320)
        } catch (_: Throwable) {
            640
        }
        val ownPkg = packageName

        val imeVisible = winList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val hasFullscreenSystemShade = winList.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(tmpBoundsRect)
            tmpBoundsRect.height() >= minHalfScreenH
        }
        val alternateBouncerVisible = winList.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || !w.isFocused) return@any false
            w.getBoundsInScreen(tmpBoundsRect)
            if (tmpBoundsRect.height() < minHalfScreenH) return@any false
            val wTitle = w.title?.toString()
            wTitle == " " || wTitle == "AlternateBouncerView"
        }
        val hasExternalFullscreenApp = winList.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@any false
            w.getBoundsInScreen(tmpBoundsRect)
            if (tmpBoundsRect.height() < minHalfScreenH) return@any false
            val knownPkg = windowPkgById.get(w.id)
            if (knownPkg == ownPkg) return@any false
            if (knownPkg == null && IosKeyguardDismissActivity.isDismissActivityAlive) return@any false
            true
        }

        val topExternalAppWin = winList.firstOrNull { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@firstOrNull false
            val knownPkg = windowPkgById.get(w.id)
            if (knownPkg == ownPkg) return@firstOrNull false
            if (knownPkg == null && IosKeyguardDismissActivity.isDismissActivityAlive) return@firstOrNull false
            true
        }
        val topAppWindowId = topExternalAppWin?.id ?: -1

        val liveFocusedAppPkg = winList.firstOrNull { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (w.isFocused || w.isActive) &&
                windowPkgById.get(w.id)?.let { it != ownPkg } == true
        }?.let { windowPkgById.get(it.id) }
            ?: topExternalAppWin?.let { windowPkgById.get(it.id) }

        if (topWindowPkg != null) {
            lastTopPkg = topWindowPkg
        } else if (liveFocusedAppPkg != null) {
            lastTopPkg = liveFocusedAppPkg
        }
        // Resolve live overlay service BEFORE updating delivered dedup cache so early-boot signals are never dropped.
        val svc = ControlCenterOverlayService.activeInstance ?: return
        val effectiveTopPkg = topWindowPkg ?: if (force) liveFocusedAppPkg else null
        val imeChanged = lastImeVisible != imeVisible
        val extAppChanged = lastHasExternalFullscreenApp != hasExternalFullscreenApp
        val shadeChanged = lastHasFullscreenSystemShade != hasFullscreenSystemShade
        val altBouncerChanged = lastAlternateBouncerVisible != alternateBouncerVisible
        val topAppWinChanged = lastTopAppWindowId != topAppWindowId
        val pkgChanged = effectiveTopPkg != null &&
            (effectiveTopPkg != lastDeliveredTopPkg || effectiveTopPkg == NEXUS_LAUNCHER_PKG)
        if (!force && !imeChanged && !extAppChanged && !shadeChanged && !altBouncerChanged && !pkgChanged && !topAppWinChanged) {
            return
        }
        lastImeVisible = imeVisible
        lastHasExternalFullscreenApp = hasExternalFullscreenApp
        lastHasFullscreenSystemShade = hasFullscreenSystemShade
        lastAlternateBouncerVisible = alternateBouncerVisible
        lastTopAppWindowId = topAppWindowId
        if (effectiveTopPkg != null) {
            lastDeliveredTopPkg = effectiveTopPkg
        }
        svc.onAccessibilityWindowSignals(
            imeVisible = imeVisible,
            topWindowPkg = effectiveTopPkg,
            hasExternalFullscreenApp = hasExternalFullscreenApp,
            hasFullscreenSystemShade = hasFullscreenSystemShade,
            alternateBouncerVisible = alternateBouncerVisible,
        )
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        windowPkgById.clear()
        lastImeVisible = null
        lastHasExternalFullscreenApp = null
        lastHasFullscreenSystemShade = null
        lastAlternateBouncerVisible = null
        lastTopAppWindowId = -1
        lastTopPkg = null
        lastDeliveredTopPkg = null
        ControlCenterOverlayService.activeInstance?.onAccessibilityWindowSignals(
            imeVisible = false,
            topWindowPkg = null,
            hasExternalFullscreenApp = false,
            hasFullscreenSystemShade = false,
            alternateBouncerVisible = false,
        )
        if (instance === this) {
            instance = null
        }
        onConnectedCallback?.invoke(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        windowPkgById.clear()
        lastImeVisible = null
        lastHasExternalFullscreenApp = null
        lastHasFullscreenSystemShade = null
        lastAlternateBouncerVisible = null
        lastTopAppWindowId = -1
        lastTopPkg = null
        lastDeliveredTopPkg = null
        if (instance === this) {
            instance = null
            ControlCenterOverlayService.activeInstance?.onAccessibilityWindowSignals(
                imeVisible = false,
                topWindowPkg = null,
                hasExternalFullscreenApp = false,
                hasFullscreenSystemShade = false,
                alternateBouncerVisible = false,
            )
            onConnectedCallback?.invoke(null)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IosA11yOverlay"
        private const val NEXUS_LAUNCHER_PKG = "com.google.android.apps.nexuslauncher"

        @Volatile
        var instance: IosSystemOverlayAccessibilityService? = null
            private set

        @Volatile
        var onConnectedCallback: ((IosSystemOverlayAccessibilityService?) -> Unit)? = null
    }
}
