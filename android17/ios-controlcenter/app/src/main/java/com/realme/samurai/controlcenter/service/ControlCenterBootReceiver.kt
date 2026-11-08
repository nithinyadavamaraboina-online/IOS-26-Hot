package com.realme.samurai.controlcenter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ControlCenterBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        val serviceAction = when (action) {
            ControlCenterOverlayService.ACTION_OPEN -> ControlCenterOverlayService.ACTION_OPEN
            ControlCenterOverlayService.ACTION_OPEN_NOTIFICATIONS -> ControlCenterOverlayService.ACTION_OPEN_NOTIFICATIONS
            ControlCenterOverlayService.ACTION_TOGGLE -> ControlCenterOverlayService.ACTION_TOGGLE
            else -> ControlCenterOverlayService.ACTION_ENSURE_RUNNING
        }
        try {
            val svc = Intent(ctx, ControlCenterOverlayService::class.java).apply {
                this.action = serviceAction
            }
            ctx.startService(svc)
        } catch (_: Throwable) {
        }
    }
}
