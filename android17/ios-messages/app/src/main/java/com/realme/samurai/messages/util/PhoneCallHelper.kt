package com.realme.samurai.messages.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/**
 * Clean helper for distinguishing real dialable phone numbers from alphanumeric
 * carrier/bank sender IDs (e.g. `BA-TSBSNL-P`, `AD-HDFCBK`) and placing audio
 * calls directly via Android Telecom when `CALL_PHONE` is granted.
 */
object PhoneCallHelper {

    fun isDialableAddress(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty() || s.contains('@') || s.any { it.isLetter() }) return false
        return s.count { it.isDigit() } >= 3
    }

    fun placeOrDialCall(context: Context, rawNumber: String) {
        val num = rawNumber.trim()
        if (!isDialableAddress(num)) return
        val uri = Uri.fromParts("tel", num, null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val placed = runCatching {
                val tm = context.getSystemService(TelecomManager::class.java)
                if (tm != null) {
                    tm.placeCall(uri, Bundle())
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (placed) return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
