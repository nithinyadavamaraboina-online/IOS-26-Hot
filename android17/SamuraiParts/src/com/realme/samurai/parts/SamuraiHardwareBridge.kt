package com.realme.samurai.parts

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class SamuraiTelemetry(
    val battCapacity: Int = -1,
    val battCurrentUa: Long = 0L,
    val battVoltUv: Long = 0L,
    val battTempDc: Int = 300,
    val voocActive: Boolean = false,
    val cycleCount: Int = 0,
    val chgEnabled: Boolean = true,
    val cpu0Mhz: Int = 0,
    val cpu4Mhz: Int = 0,
    val cpu7Mhz: Int = 0,
    val gpuMhz: Int = 0,
    val gpuBusyPct: Int = 0,
    val measuredFps: String = "90.0",
    val skinTempMc: Int = 32000,
) {
    val watts: Double
        get() {
            val v = battVoltUv / 1_000_000.0
            val a = kotlin.math.abs(battCurrentUa) / 1_000_000.0
            // Note: on RMX1931 dual-series / high-current VOOC, current_now can be reported in mA on some kernels
            val amps = if (kotlin.math.abs(battCurrentUa) in 1..15000) {
                kotlin.math.abs(battCurrentUa) / 1000.0
            } else {
                a
            }
            return v * amps
        }

    val currentMa: Int
        get() = if (kotlin.math.abs(battCurrentUa) > 20000) {
            (battCurrentUa / 1000L).toInt()
        } else {
            battCurrentUa.toInt()
        }

    val battTempC: Double
        get() = battTempDc / 10.0
}

object SamuraiHardwareBridge {
    private const val PREFS_NAME = "samurai_parts_prefs"
    private const val HW_DIR = "/data/ota_package/hw"
    private const val QUEUE_DIR = "/data/ota_package/hw/queue"
    private const val TELEMETRY_FILE = "/data/ota_package/hw/telemetry.prop"
    private val seq = AtomicLong(1L)

    // Preference Keys
    const val KEY_DT2W = "dt2w_enabled"
    const val KEY_DT2S_STATUSBAR = "dt2s_statusbar"
    const val KEY_DT2S_HOME = "dt2s_home"
    const val KEY_GESTURE_TORCH_V = "gesture_torch_v"
    const val KEY_GESTURE_CAMERA_O = "gesture_camera_o"
    const val KEY_GESTURE_MUSIC = "gesture_music"

    const val KEY_REFRESH_MODE = "refresh_mode" // 0=Smooth 90Hz (locked), 1=Adaptive 60-90Hz, 2=60Hz Saver
    const val KEY_DC_DIM = "dc_dimming"
    const val KEY_HBM = "hbm_mode"
    const val KEY_AUTO_HBM = "auto_hbm_sunlight"
    const val KEY_SEED = "seed_mode" // 2=DCI-P3 Vivid, 1=sRGB Natural, 0=Panel Native
    const val KEY_CABC = "cabc_mode"

    const val KEY_TP_GAME = "tp_game_mode"
    const val KEY_TP_LIMIT = "tp_edge_limit"
    const val KEY_PERF_PROFILE = "perf_profile" // 0=Balanced, 1=Gaming 855+, 2=Efficiency
    const val KEY_HUD_ENABLED = "hud_overlay_enabled"

    const val KEY_CHG_LIMIT = "chg_limit_pct" // 100, 90, 85, 80
    const val KEY_COOL_DOWN = "cool_down_level" // 0=Full 50W, 2=Warm Cap, 5=Silent Trickle
    const val KEY_OTG = "otg_enabled"

    const val KEY_VIB_GAIN = "vib_gain" // 32..128
    const val KEY_AUDIO_PRESET = "audio_preset" // 0=Flat, 1=Balanced Stereo, 2=Rich Bass, 3=Clear Voice

    const val KEY_VIDEO_60HZ_AUTO = "video_60hz_auto_v2"
    const val KEY_TEMP_REFRESH_OVERRIDE = "temp_refresh_override"
    const val KEY_GAMING_AUTO = "gaming_auto_boost"

    fun prefs(context: Context): SharedPreferences {
        val dpCtx = context.applicationContext.createDeviceProtectedStorageContext()
        return dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun sendCommands(lines: List<String>) {
        if (lines.isEmpty()) return
        try {
            val qDir = File(QUEUE_DIR)
            if (!qDir.exists()) {
                qDir.mkdirs()
            }
            val id = "${System.nanoTime()}_${seq.getAndIncrement()}"
            val tmp = File(qDir, ".tmp_$id")
            val req = File(qDir, "cmd_$id.req")
            tmp.writeText(lines.joinToString("\n") + "\n")
            tmp.setReadable(true, false)
            if (!tmp.renameTo(req)) {
                req.writeText(lines.joinToString("\n") + "\n")
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun sendCommand(key: String, value: Int) {
        sendCommands(listOf("$key=$value"))
    }

    fun sendCommand(key: String, value: Boolean) {
        sendCommand(key, if (value) 1 else 0)
    }

    fun applyRefreshRateMode(context: Context, mode: Int) {
        val cr = context.contentResolver
        try {
            when (mode) {
                1 -> { // Adaptive 60-90Hz
                    Settings.System.putFloat(cr, "peak_refresh_rate", 90.0f)
                    Settings.System.putFloat(cr, "min_refresh_rate", 60.0f)
                }
                2 -> { // 60Hz Battery Saver
                    Settings.System.putFloat(cr, "peak_refresh_rate", 60.0f)
                    Settings.System.putFloat(cr, "min_refresh_rate", 60.0f)
                }
                else -> { // Smooth 90Hz Default (eliminates non-LTPO Samsung SOFEF03F-M mode-switch stalls)
                    Settings.System.putFloat(cr, "peak_refresh_rate", 90.0f)
                    Settings.System.putFloat(cr, "min_refresh_rate", 90.0f)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun syncTapToWakeSettings(context: Context, enabled: Boolean) {
        val cr = context.contentResolver
        val v = if (enabled) 1 else 0
        try {
            Settings.Secure.putInt(cr, "double_tap_to_wake", v)
            Settings.Secure.putInt(cr, "doze_double_tap_gesture", v)
            Settings.Secure.putInt(cr, "doze_tap_screen_gesture", v)
        } catch (_: Throwable) {
        }
        sendCommand("DT2W", enabled)
    }

    fun syncHomeDoubleTapSettings(context: Context, enabled: Boolean) {
        val cr = context.contentResolver
        val v = if (enabled) 1 else 0
        try {
            Settings.System.putInt(cr, "samurai_dt2s_home", v)
        } catch (_: Throwable) {
        }
        sendCommand("DT2S_HOME", enabled)
    }

    fun syncAllSavedPreferences(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_TEMP_REFRESH_OVERRIDE, -1) != -1) {
            p.edit().putInt(KEY_TEMP_REFRESH_OVERRIDE, -1).apply()
        }
        val dt2w = p.getBoolean(KEY_DT2W, true)
        val dt2sBar = p.getBoolean(KEY_DT2S_STATUSBAR, true)
        val dt2sHome = p.getBoolean(KEY_DT2S_HOME, true)
        val torchV = p.getBoolean(KEY_GESTURE_TORCH_V, true)
        val camO = p.getBoolean(KEY_GESTURE_CAMERA_O, false)
        val music = p.getBoolean(KEY_GESTURE_MUSIC, false)

        val refreshMode = p.getInt(KEY_REFRESH_MODE, 0)
        val dcDim = p.getBoolean(KEY_DC_DIM, false)
        // Never re-lock /sys/kernel/oppo_display/hbm=1 across reboots (blocks MIPI DCS brightness slider)
        if (p.getBoolean(KEY_HBM, false)) {
            p.edit().putBoolean(KEY_HBM, false).apply()
        }
        val hbm = false
        val seed = p.getInt(KEY_SEED, 2)
        val cabc = p.getInt(KEY_CABC, 1)

        val tpGame = p.getBoolean(KEY_TP_GAME, false)
        val tpLimit = p.getBoolean(KEY_TP_LIMIT, true)
        val perf = p.getInt(KEY_PERF_PROFILE, 0)

        val chgLimit = p.getInt(KEY_CHG_LIMIT, 100)
        val coolDown = p.getInt(KEY_COOL_DOWN, 0)
        val otg = p.getBoolean(KEY_OTG, false)
        val vibGain = p.getInt(KEY_VIB_GAIN, 128)

        applyRefreshRateMode(context, refreshMode)
        syncTapToWakeSettings(context, dt2w)
        syncHomeDoubleTapSettings(context, dt2sHome)

        sendCommands(
            listOf(
                "DT2W=${if (dt2w) 1 else 0}",
                "DT2S=${if (dt2sBar) 1 else 0}",
                "DT2S_HOME=${if (dt2sHome) 1 else 0}",
                "GESTURE_TORCH_V=${if (torchV) 1 else 0}",
                "GESTURE_CAMERA_O=${if (camO) 1 else 0}",
                "GESTURE_MUSIC=${if (music) 1 else 0}",
                "DC_DIM=${if (dcDim) 1 else 0}",
                "HBM=${if (hbm) 1 else 0}",
                "SEED=$seed",
                "CABC=$cabc",
                "TP_GAME=${if (tpGame) 1 else 0}",
                "TP_LIMIT=${if (tpLimit) 1 else 0}",
                "PERF=$perf",
                "CHG_LIMIT=$chgLimit",
                "COOL_DOWN=$coolDown",
                "OTG=${if (otg) 1 else 0}",
                "VIB_GAIN=$vibGain",
            )
        )
    }

    fun requestTelemetryWindow(seconds: Int = 20) {
        sendCommand("TELEMETRY", seconds)
    }

    fun isHbmHardwareActive(context: Context): Boolean {
        val f = File(TELEMETRY_FILE)
        if (f.isFile) {
            try {
                for (line in f.readLines()) {
                    if (line.startsWith("hbm_mode=")) {
                        return line.substringAfter('=').trim() == "1"
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return prefs(context).getBoolean(KEY_HBM, false)
    }

    fun readTelemetry(): SamuraiTelemetry {
        val f = File(TELEMETRY_FILE)
        if (!f.isFile) return SamuraiTelemetry()
        return try {
            val map = HashMap<String, String>()
            f.forEachLine { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
            SamuraiTelemetry(
                battCapacity = map["batt_capacity"]?.toIntOrNull() ?: -1,
                battCurrentUa = map["batt_current_ua"]?.toLongOrNull() ?: 0L,
                battVoltUv = map["batt_volt_uv"]?.toLongOrNull() ?: 0L,
                battTempDc = map["batt_temp_dc"]?.toIntOrNull() ?: 300,
                voocActive = (map["vooc_active"]?.toLongOrNull() ?: 0L) != 0L,
                cycleCount = map["cycle_count"]?.toIntOrNull() ?: 0,
                chgEnabled = (map["chg_enabled"]?.toLongOrNull() ?: 1L) != 0L,
                cpu0Mhz = map["cpu0_mhz"]?.toIntOrNull() ?: 0,
                cpu4Mhz = map["cpu4_mhz"]?.toIntOrNull() ?: 0,
                cpu7Mhz = map["cpu7_mhz"]?.toIntOrNull() ?: 0,
                gpuMhz = map["gpu_mhz"]?.toIntOrNull() ?: 0,
                gpuBusyPct = map["gpu_busy_pct"]?.toIntOrNull() ?: 0,
                measuredFps = map["measured_fps"] ?: "90.0",
                skinTempMc = map["skin_temp_mc"]?.toIntOrNull() ?: 32000,
            )
        } catch (_: Throwable) {
            SamuraiTelemetry()
        }
    }
}
