package com.realme.samurai.glass

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.realme.samurai.parts.SamuraiHardwareBridge
import java.io.File

/**
 * System-wide iOS 26 Liquid Glass & Universal Icon preference store & haptic helper.
 * Persists user selections across:
 *  - Settings.Global ("samurai_liquid_glass_mode", "samurai_ios26_icon_mode",
 *    "samurai_search_pill_enabled", "samurai_gesture_pill_enabled", "samurai_wallpaper_seq")
 *    for instant cross-process Launcher updates
 *  - Device-protected SharedPreferences
 *  - /data/ota_package/hw/glass.prop for early boot / native / system consumers
 *  - OverlayManager ("com.realme.samurai.overlay.hidepill") for global navigation gesture pill toggle
 */
object SamuraiGlassPrefs {
    const val MODE_CLEAR = 0
    const val MODE_TINTED = 1
    const val MODE_SOLID_MATTE = 2

    const val ICON_MODE_AUTO = 0
    const val ICON_MODE_DEFAULT = 1
    const val ICON_MODE_DARK = 2
    const val ICON_MODE_CLEAR = 3

    const val PREF_KEY_GLASS_MODE = "liquid_glass_mode"
    const val PREF_KEY_ICON_MODE = "ios26_icon_mode"
    const val PREF_KEY_SEARCH_PILL = "ios26_search_pill_enabled"
    const val PREF_KEY_GESTURE_PILL = "ios26_gesture_pill_enabled"
    const val PREF_KEY_V215_HOMEBAR_MIGRATED = "v215_ios_homebar_migrated"

    const val SETTINGS_GLOBAL_KEY = "samurai_liquid_glass_mode"
    const val SETTINGS_SECURE_GLASS_MODE_KEY = "samurai_ios26_glass_mode"
    const val SETTINGS_ICON_MODE_KEY = "samurai_ios26_icon_mode"
    const val SETTINGS_SEARCH_PILL_KEY = "samurai_search_pill_enabled"
    const val SETTINGS_SECURE_SEARCH_PILL_KEY = "samurai_ios26_search_pill"
    const val SETTINGS_GESTURE_PILL_KEY = "samurai_gesture_pill_enabled"
    const val SETTINGS_SECURE_GESTURE_PILL_KEY = "samurai_ios26_gesture_pill"
    const val SETTINGS_V215_HOMEBAR_MIGRATED_KEY = "samurai_v215_homebar_migrated"
    const val SETTINGS_WALLPAPER_SEQ_KEY = "samurai_wallpaper_seq"
    const val SETTINGS_REFRACTION_PCT_KEY = "samurai_ios26_refraction_pct"
    const val SETTINGS_FROST_PCT_KEY = "samurai_ios26_frost_pct"
    const val SETTINGS_WP_BRIGHTNESS_PCT_KEY = "samurai_ios26_wp_brightness_pct"
    const val DEFAULT_REFRACTION_PCT = 20
    const val DEFAULT_FROST_PCT = 20
    const val DEFAULT_WP_BRIGHTNESS_PCT = 70

    const val HIDE_PILL_OVERLAY_PKG = "com.realme.samurai.overlay.hidepill"

    private const val GLASS_PROP_PATH = "/data/ota_package/hw/glass.prop"

    fun getOtaChannel(): String {
        return try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, "ro.ota.channel", "stable") as? String)?.trim().orEmpty()
        } catch (_: Throwable) {
            "stable"
        }
    }

    fun isIosBeta(): Boolean = getOtaChannel() == "ios_beta"

    fun getMode(context: Context): Int {
        try {
            val globalVal = Settings.Global.getInt(context.contentResolver, SETTINGS_GLOBAL_KEY, -1)
            if (globalVal in MODE_CLEAR..MODE_SOLID_MATTE) {
                return globalVal
            }
        } catch (_: Throwable) {
        }

        try {
            val secVal = Settings.Secure.getInt(context.contentResolver, SETTINGS_SECURE_GLASS_MODE_KEY, -1)
            if (secVal in MODE_CLEAR..MODE_SOLID_MATTE) {
                return secVal
            }
        } catch (_: Throwable) {
        }

        try {
            val propFile = File(GLASS_PROP_PATH)
            if (propFile.isFile) {
                for (line in propFile.readLines()) {
                    if (line.startsWith("glass_mode=")) {
                        val parsed = line.substringAfter('=').trim().toIntOrNull()
                        if (parsed != null && parsed in MODE_CLEAR..MODE_SOLID_MATTE) {
                            return parsed
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return try {
            SamuraiHardwareBridge.prefs(context)
                .getInt(PREF_KEY_GLASS_MODE, MODE_CLEAR)
                .coerceIn(MODE_CLEAR, MODE_SOLID_MATTE)
        } catch (_: Throwable) {
            MODE_CLEAR
        }
    }

    fun isTinted(context: Context): Boolean = getMode(context) == MODE_TINTED

    fun setMode(context: Context, mode: Int) {
        val cleanMode = mode.coerceIn(MODE_CLEAR, MODE_SOLID_MATTE)
        val iosBeta = isIosBeta()
        try {
            SamuraiHardwareBridge.prefs(context).edit()
                .putInt(PREF_KEY_GLASS_MODE, cleanMode)
                .apply()
        } catch (_: Throwable) {
        }

        try {
            Settings.Global.putInt(context.contentResolver, SETTINGS_GLOBAL_KEY, cleanMode)
            if (iosBeta) {
                Settings.Global.putInt(context.contentResolver, SETTINGS_SECURE_GLASS_MODE_KEY, cleanMode)
            }
        } catch (_: Throwable) {
        }

        if (iosBeta) {
            try {
                Settings.Secure.putInt(context.contentResolver, SETTINGS_SECURE_GLASS_MODE_KEY, cleanMode)
                Settings.Secure.putInt(context.contentResolver, SETTINGS_GLOBAL_KEY, cleanMode)
            } catch (_: Throwable) {
            }
        }

        writeGlassProp(context)
    }

    fun getIconMode(context: Context): Int {
        try {
            val globalVal = Settings.Global.getInt(context.contentResolver, SETTINGS_ICON_MODE_KEY, -1)
            if (globalVal in ICON_MODE_AUTO..ICON_MODE_CLEAR) {
                return globalVal
            }
        } catch (_: Throwable) {
        }
        return try {
            SamuraiHardwareBridge.prefs(context)
                .getInt(PREF_KEY_ICON_MODE, ICON_MODE_AUTO)
                .coerceIn(ICON_MODE_AUTO, ICON_MODE_CLEAR)
        } catch (_: Throwable) {
            ICON_MODE_AUTO
        }
    }

    fun setIconMode(context: Context, iconMode: Int) {
        val clean = iconMode.coerceIn(ICON_MODE_AUTO, ICON_MODE_CLEAR)
        try {
            SamuraiHardwareBridge.prefs(context).edit()
                .putInt(PREF_KEY_ICON_MODE, clean)
                .apply()
        } catch (_: Throwable) {
        }
        try {
            Settings.Global.putInt(context.contentResolver, SETTINGS_ICON_MODE_KEY, clean)
        } catch (_: Throwable) {
        }
        writeGlassProp(context)
    }

    fun isSearchPillEnabled(context: Context): Boolean {
        try {
            val globalVal = Settings.Global.getInt(context.contentResolver, SETTINGS_SEARCH_PILL_KEY, -1)
            if (globalVal == 0 || globalVal == 1) {
                return globalVal == 1
            }
        } catch (_: Throwable) {
        }
        return try {
            SamuraiHardwareBridge.prefs(context).getBoolean(PREF_KEY_SEARCH_PILL, true)
        } catch (_: Throwable) {
            true
        }
    }

    fun setSearchPillEnabled(context: Context, enabled: Boolean) {
        val intVal = if (enabled) 1 else 0
        try {
            SamuraiHardwareBridge.prefs(context).edit()
                .putBoolean(PREF_KEY_SEARCH_PILL, enabled)
                .apply()
        } catch (_: Throwable) {
        }
        try {
            Settings.Global.putInt(context.contentResolver, SETTINGS_SEARCH_PILL_KEY, intVal)
        } catch (_: Throwable) {
        }
        writeGlassProp(context)
    }

    fun isGesturePillEnabled(context: Context): Boolean {
        val iosBeta = isIosBeta()
        try {
            val globalVal = Settings.Global.getInt(context.contentResolver, SETTINGS_GESTURE_PILL_KEY, -1)
            if (globalVal == 0 || globalVal == 1) {
                return globalVal == 1
            }
            if (iosBeta) {
                val globalAlt = Settings.Global.getInt(context.contentResolver, SETTINGS_SECURE_GESTURE_PILL_KEY, -1)
                if (globalAlt == 0 || globalAlt == 1) {
                    return globalAlt == 1
                }
            }
        } catch (_: Throwable) {
        }

        if (iosBeta) {
            try {
                val secVal = Settings.Secure.getInt(context.contentResolver, SETTINGS_SECURE_GESTURE_PILL_KEY, -1)
                if (secVal == 0 || secVal == 1) {
                    return secVal == 1
                }
                val secAlt = Settings.Secure.getInt(context.contentResolver, SETTINGS_GESTURE_PILL_KEY, -1)
                if (secAlt == 0 || secAlt == 1) {
                    return secAlt == 1
                }
            } catch (_: Throwable) {
            }

            try {
                val propFile = File(GLASS_PROP_PATH)
                if (propFile.isFile) {
                    for (line in propFile.readLines()) {
                        if (line.startsWith("gesture_pill=")) {
                            val parsed = line.substringAfter('=').trim().toIntOrNull()
                            if (parsed == 0 || parsed == 1) {
                                return parsed == 1
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Both Pure PixelOS Stable and iOS Beta v2.1.5+ enable the bottom navigation bar by default.
        return try {
            SamuraiHardwareBridge.prefs(context).getBoolean(PREF_KEY_GESTURE_PILL, true)
        } catch (_: Throwable) {
            true
        }
    }

    fun setGesturePillEnabled(context: Context, enabled: Boolean) {
        val intVal = if (enabled) 1 else 0
        val iosBeta = isIosBeta()
        try {
            SamuraiHardwareBridge.prefs(context).edit()
                .putBoolean(PREF_KEY_GESTURE_PILL, enabled)
                .apply()
        } catch (_: Throwable) {
        }
        try {
            Settings.Global.putInt(context.contentResolver, SETTINGS_GESTURE_PILL_KEY, intVal)
            if (iosBeta) {
                Settings.Global.putInt(context.contentResolver, SETTINGS_SECURE_GESTURE_PILL_KEY, intVal)
            }
        } catch (_: Throwable) {
        }
        if (iosBeta) {
            try {
                Settings.Secure.putInt(context.contentResolver, SETTINGS_SECURE_GESTURE_PILL_KEY, intVal)
                Settings.Secure.putInt(context.contentResolver, SETTINGS_GESTURE_PILL_KEY, intVal)
            } catch (_: Throwable) {
            }
        }
        writeGlassProp(context, gesturePillOverride = enabled)
        applyHidePillOverlay(context, hidePill = if (iosBeta) true else !enabled)
    }

    fun syncGesturePillState(context: Context) {
        if (isIosBeta()) {
            val prefs = runCatching { SamuraiHardwareBridge.prefs(context) }.getOrNull()
            val globalMigrated = runCatching {
                Settings.Global.getInt(context.contentResolver, SETTINGS_V215_HOMEBAR_MIGRATED_KEY, 0) == 1
            }.getOrDefault(false)
            val prefMigrated = prefs?.getBoolean(PREF_KEY_V215_HOMEBAR_MIGRATED, false) ?: false
            if (!globalMigrated && !prefMigrated) {
                runCatching {
                    prefs?.edit()?.putBoolean(PREF_KEY_V215_HOMEBAR_MIGRATED, true)?.apply()
                }
                runCatching {
                    Settings.Global.putInt(context.contentResolver, SETTINGS_V215_HOMEBAR_MIGRATED_KEY, 1)
                }
                setGesturePillEnabled(context, true)
                return
            } else if (!prefMigrated) {
                runCatching {
                    prefs?.edit()?.putBoolean(PREF_KEY_V215_HOMEBAR_MIGRATED, true)?.apply()
                }
            }
        }
        val enabled = isGesturePillEnabled(context)
        setGesturePillEnabled(context, enabled)
    }

    private fun applyHidePillOverlay(context: Context, hidePill: Boolean) {
        try {
            val om = context.getSystemService("overlay") ?: return
            val setEnabledMethod = om.javaClass.getMethod(
                "setEnabled",
                String::class.java,
                Boolean::class.javaPrimitiveType ?: Boolean::class.java,
                UserHandle::class.java
            )
            setEnabledMethod.invoke(om, HIDE_PILL_OVERLAY_PKG, hidePill, Process.myUserHandle())
        } catch (_: Throwable) {
        }
    }

    fun bumpWallpaperSeq(context: Context) {
        try {
            val next = Settings.Global.getInt(context.contentResolver, SETTINGS_WALLPAPER_SEQ_KEY, 0) + 1
            Settings.Global.putInt(context.contentResolver, SETTINGS_WALLPAPER_SEQ_KEY, next)
        } catch (_: Throwable) {
        }
    }

    private fun writeGlassProp(context: Context, gesturePillOverride: Boolean? = null) {
        if (!isIosBeta()) return
        try {
            val cr = context.contentResolver
            val gm = getMode(context)
            val im = getIconMode(context)
            val sp = if (isSearchPillEnabled(context)) 1 else 0
            val gp = if (gesturePillOverride ?: isGesturePillEnabled(context)) 1 else 0
            fun readPct(key: String, def: Int, maxVal: Int): Int {
                val g = runCatching { Settings.Global.getInt(cr, key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
                if (g in 0..maxVal) return g
                val s = runCatching { Settings.Secure.getInt(cr, key, Int.MIN_VALUE) }.getOrDefault(Int.MIN_VALUE)
                if (s in 0..maxVal) return s
                return def
            }
            val rp = readPct(SETTINGS_REFRACTION_PCT_KEY, DEFAULT_REFRACTION_PCT, 150)
            val fp = readPct(SETTINGS_FROST_PCT_KEY, DEFAULT_FROST_PCT, 150)
            val wb = readPct(SETTINGS_WP_BRIGHTNESS_PCT_KEY, DEFAULT_WP_BRIGHTNESS_PCT, 100)
            val content = "glass_mode=$gm\nicon_mode=$im\nsearch_pill=$sp\ngesture_pill=$gp\n" +
                "refraction_pct=$rp\nfrost_pct=$fp\nwp_brightness_pct=$wb\n"
            val f = File(GLASS_PROP_PATH)
            f.parentFile?.let { dir ->
                if (!dir.exists()) dir.mkdirs()
            }
            val tmp = File("${GLASS_PROP_PATH}.tmp")
            tmp.writeText(content)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(f)) {
                f.writeText(content)
                f.setReadable(true, false)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun performCrispTick(view: View, strong: Boolean = false) {
        try {
            val feedback = if (strong) {
                HapticFeedbackConstants.CONTEXT_CLICK
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            if (view.performHapticFeedback(feedback)) return
        } catch (_: Throwable) {
        }
        try {
            val vib = view.context.getSystemService(Vibrator::class.java)
            if (vib != null && vib.hasVibrator()) {
                val fx = VibrationEffect.createPredefined(
                    if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
                )
                vib.vibrate(fx)
            }
        } catch (_: Throwable) {
        }
    }
}
