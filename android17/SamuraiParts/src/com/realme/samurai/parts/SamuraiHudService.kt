package com.realme.samurai.parts

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

class SamuraiHudService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var hudView: TextView? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val view = hudView ?: return
            SamuraiHardwareBridge.requestTelemetryWindow(10)
            val t = SamuraiHardwareBridge.readTelemetry()
            val pwrStr = if (t.voocActive) {
                String.format(Locale.US, "⚡ %.1fW VOOC", t.watts)
            } else {
                String.format(Locale.US, "%+.0fmA", t.currentMa.toDouble())
            }
            view.text = String.format(
                Locale.US,
                "%s fps • P:%dMHz • G:%dMHz (%d%%) • %s • %.1f°C",
                t.measuredFps,
                t.cpu7Mhz,
                t.gpuMhz,
                t.gpuBusyPct,
                pwrStr,
                t.battTempC
            )
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        val dp = resources.displayMetrics.density
        val pill = TextView(this).apply {
            setTextColor(Color.parseColor("#F2FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * dp
                setColor(Color.parseColor("#CC181A1F"))
                setStroke((1f * dp).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
            }
            text = "90.0 fps • RMX1931 Telemetry"
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * dp).toInt()
            y = (38 * dp).toInt()
        }

        try {
            wm?.addView(pill, lp)
            hudView = pill
            handler.post(tickRunnable)
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        hudView?.let { v ->
            try {
                wm?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        hudView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
