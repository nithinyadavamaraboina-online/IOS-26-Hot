package com.realme.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OtaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (act == Intent.ACTION_BOOT_COMPLETED || act == Intent.ACTION_MY_PACKAGE_REPLACED) {
            OtaEngine.postUpdateCompletedIfUpgraded(context)
            OtaEngine.schedulePeriodicCheck(context)
        }
    }
}
