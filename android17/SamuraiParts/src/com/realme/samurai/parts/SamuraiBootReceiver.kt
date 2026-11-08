package com.realme.samurai.parts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.realme.samurai.glass.SamuraiGlassPrefs

class SamuraiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            SamuraiHardwareBridge.syncAllSavedPreferences(ctx)
            SamuraiGlassPrefs.syncGesturePillState(ctx)
            try {
                ctx.startService(Intent(ctx, SamuraiMonitorService::class.java))
            } catch (_: Throwable) {
            }
            if (action == Intent.ACTION_BOOT_COMPLETED) {
                val hudOn = SamuraiHardwareBridge.prefs(ctx)
                    .getBoolean(SamuraiHardwareBridge.KEY_HUD_ENABLED, false)
                if (hudOn) {
                    try {
                        ctx.startService(Intent(ctx, SamuraiHudService::class.java))
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }
}
