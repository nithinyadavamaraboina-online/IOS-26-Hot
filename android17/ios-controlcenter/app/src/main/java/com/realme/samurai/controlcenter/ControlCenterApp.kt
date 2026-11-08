package com.realme.samurai.controlcenter

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.realme.samurai.controlcenter.service.ControlCenterOverlayService

class ControlCenterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Log.i("IosControlCenter", "ControlCenterApp.onCreate -> starting ControlCenterOverlayService")
            ControlCenterOverlayService.ensureRunning(this@ControlCenterApp)
        }
        handler.postDelayed({
            ControlCenterOverlayService.ensureRunning(this@ControlCenterApp)
        }, 2500L)
    }
}
