package com.realme.samurai.controlcenter.service

import android.content.Context
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Region
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Physical pixel geometry & WindowManager touch-region aperture helper for the Realme X2 Pro
 * (`RMX1931`, `1080x2400` Samsung SOFEF03F-M 90Hz AMOLED) Goodix G3 (`GF9500` / `G_OPTICAL_G3`)
 * optical under-display fingerprint sensor.
 *
 * Source of truth:
 * - Reads `android:array/config_udfps_sensor_props` (`[x, y, radius]` in native display pixels,
 *   defined as `[540, 2156, 95]` in `SamuraiHardwareOverlay/res/values/config.xml` matching
 *   `persist.vendor.fingerprint.optical.iconlocation=244`, `iconsize=190` in `/vendor/build.prop`
 *   and `SystemUI` `AuthController` / `UdfpsController`).
 * - Scales strictly by physical viewport pixel dimensions (`viewportW / 1080f`, `viewportH / 2400f`),
 *   never by `dp`, so user Display Size / font scaling never shifts the hit-zone off the optical camera.
 */
data class UdfpsSensorSpec(
    val centerXPx: Float,
    val centerYPx: Float,
    val radiusPx: Float,
) {
    fun contains(xPx: Float, yPx: Float, extraRadiusPx: Float = 0f): Boolean {
        val r = (radiusPx + extraRadiusPx).coerceAtLeast(1f)
        val dx = xPx - centerXPx
        val dy = yPx - centerYPx
        return (dx * dx + dy * dy) <= (r * r)
    }
}

object UdfpsSensorGeometry {
    const val NATIVE_DISPLAY_WIDTH_PX = 1080f
    const val NATIVE_DISPLAY_HEIGHT_PX = 2400f
    const val RMX1931_UDFPS_CENTER_X_PX = 540f
    const val RMX1931_UDFPS_CENTER_Y_PX = 2156f
    const val RMX1931_UDFPS_RADIUS_PX = 95f

    private const val TOUCHABLE_INSETS_FRAME = 0
    private const val TOUCHABLE_INSETS_REGION = 3

    fun resolve(
        context: Context?,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): UdfpsSensorSpec {
        val safeW = viewportWidthPx.coerceAtLeast(320f)
        val safeH = viewportHeightPx.coerceAtLeast(640f)
        val rawProps = readFrameworkUdfpsSensorProps(context)
        val baseX = rawProps?.getOrNull(0)?.takeIf { it > 0 }?.toFloat() ?: RMX1931_UDFPS_CENTER_X_PX
        val baseY = rawProps?.getOrNull(1)?.takeIf { it > 0 }?.toFloat() ?: RMX1931_UDFPS_CENTER_Y_PX
        val baseR = rawProps?.getOrNull(2)?.takeIf { it > 0 }?.toFloat() ?: RMX1931_UDFPS_RADIUS_PX

        val scaleX = safeW / NATIVE_DISPLAY_WIDTH_PX
        val scaleY = safeH / NATIVE_DISPLAY_HEIGHT_PX
        val scaleR = min(scaleX, scaleY)
        return UdfpsSensorSpec(
            centerXPx = baseX * scaleX,
            centerYPx = baseY * scaleY,
            radiusPx = (baseR * scaleR).coerceAtLeast(24f),
        )
    }

    private fun readFrameworkUdfpsSensorProps(context: Context?): IntArray? {
        val candidates = listOfNotNull(context?.resources, Resources.getSystem())
        for (res in candidates) {
            try {
                val id = res.getIdentifier("config_udfps_sensor_props", "array", "android")
                if (id != 0) {
                    val arr = res.getIntArray(id)
                    if (arr.size >= 3 && arr[0] > 0 && arr[1] > 0 && arr[2] > 0) {
                        return arr
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun hasEnrolledUdfps(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val fm = context.getSystemService(FingerprintManager::class.java) ?: return false
            fm.isHardwareDetected && fm.hasEnrolledFingerprints()
        } catch (_: Throwable) {
            // If FingerprintManager query is unavailable on a custom binder state, err on the side
            // of keeping the hardware UDFPS aperture open on secure keyguard.
            true
        }
    }

    /**
     * Installs a `ViewTreeObserver.OnComputeInternalInsetsListener` (`TOUCHABLE_INSETS_REGION = 3`)
     * on `view` that carves out the circular Goodix UDFPS sensor region `(cx, cy, r)` whenever
     * [shouldCarveAperture] returns `true`.
     *
     * Because `SamuraiIos26LockScreen` uses `FLAG_NOT_TOUCH_MODAL`, touches inside the carved-out
     * circle bypass `SamuraiIos26LockScreen` at `InputDispatcher` (`t = 0ms`) and land directly on
     * SystemUI's `UdfpsControllerOverlay` on Press #1, eliminating any two-press requirement.
     *
     * Returns an idempotent cleanup lambda to detach the listener.
     */
    fun installTouchAperture(
        view: View,
        shouldCarveAperture: () -> Boolean,
        specProvider: (widthPx: Int, heightPx: Int) -> UdfpsSensorSpec = { w, h ->
            resolve(view.context, w.toFloat(), h.toFloat())
        },
    ): () -> Unit {
        return try {
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val setTouchableInsetsMethod: Method? = try {
                infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            } catch (_: Throwable) {
                null
            }
            val mTouchableInsetsField = if (setTouchableInsetsMethod == null) {
                infoCls.getDeclaredField("mTouchableInsets").apply { isAccessible = true }
            } else {
                null
            }
            val touchableRegionField = infoCls.getField("touchableRegion")
            val addMethod = ViewTreeObserver::class.java.getMethod(
                "addOnComputeInternalInsetsListener",
                listenerCls,
            )
            val removeMethod = ViewTreeObserver::class.java.getMethod(
                "removeOnComputeInternalInsetsListener",
                listenerCls,
            )

            val fullClipRegion = Region()
            val holeRegion = Region()
            val holePath = Path()

            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.firstOrNull() ?: return@InvocationHandler null
                        val w = view.width
                        val h = view.height
                        if (w > 0 && h > 0 && shouldCarveAperture()) {
                            val spec = specProvider(w, h)
                            val region = touchableRegionField.get(info) as? Region
                            if (region != null) {
                                fullClipRegion.set(0, 0, w, h)
                                holePath.reset()
                                holePath.addCircle(
                                    spec.centerXPx,
                                    spec.centerYPx,
                                    spec.radiusPx,
                                    Path.Direction.CW,
                                )
                                if (!holeRegion.setPath(holePath, fullClipRegion)) {
                                    val cx = spec.centerXPx.roundToInt()
                                    val cy = spec.centerYPx.roundToInt()
                                    val r = spec.radiusPx.roundToInt()
                                    holeRegion.set(cx - r, cy - r, cx + r, cy + r)
                                }
                                region.set(0, 0, w, h)
                                region.op(holeRegion, Region.Op.DIFFERENCE)
                                if (setTouchableInsetsMethod != null) {
                                    setTouchableInsetsMethod.invoke(info, TOUCHABLE_INSETS_REGION)
                                } else {
                                    mTouchableInsetsField?.setInt(info, TOUCHABLE_INSETS_REGION)
                                }
                            }
                        } else {
                            if (setTouchableInsetsMethod != null) {
                                setTouchableInsetsMethod.invoke(info, TOUCHABLE_INSETS_FRAME)
                            } else {
                                mTouchableInsetsField?.setInt(info, TOUCHABLE_INSETS_FRAME)
                            }
                        }
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "SamuraiUdfpsTouchApertureListener"
                    else -> null
                }
            }

            val listenerProxy = Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
                handler,
            )

            var currentVto: ViewTreeObserver? = null
            fun attachToCurrentVto() {
                val vto = view.viewTreeObserver
                if (vto != null && vto.isAlive && vto !== currentVto) {
                    try {
                        addMethod.invoke(vto, listenerProxy)
                        currentVto = vto
                    } catch (_: Throwable) {
                    }
                }
            }
            fun detachFromCurrentVto() {
                val vto = currentVto ?: view.viewTreeObserver
                currentVto = null
                if (vto != null && vto.isAlive) {
                    try {
                        removeMethod.invoke(vto, listenerProxy)
                    } catch (_: Throwable) {
                    }
                }
            }

            val attachStateListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    attachToCurrentVto()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    detachFromCurrentVto()
                }
            }
            view.addOnAttachStateChangeListener(attachStateListener)
            if (view.isAttachedToWindow) {
                attachToCurrentVto()
            }

            return {
                view.removeOnAttachStateChangeListener(attachStateListener)
                detachFromCurrentVto()
            }
        } catch (_: Throwable) {
            {}
        }
    }
}
