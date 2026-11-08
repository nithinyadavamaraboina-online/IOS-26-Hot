package com.realme.samurai.controlcenter.data

import android.app.NotificationManager
import android.app.UiModeManager
import android.app.WallpaperManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random

data class ControlCenterState(
    val carrierLabel: String = "Jio Wi-Fi",
    val signalBars: Int = 4,
    val batteryPct: Int = 68,
    val isCharging: Boolean = false,
    val hasAlarm: Boolean = true,

    val airplaneMode: Boolean = false,
    val quickShareActive: Boolean = true,
    val wifiEnabled: Boolean = true,
    val wifiSsid: String = "Home 5G",
    val cellularEnabled: Boolean = true,
    val cellularLabel: String = "5G / VoLTE",
    val bluetoothEnabled: Boolean = true,
    val bluetoothLabel: String = "On",
    val hotspotEnabled: Boolean = false,
    val privateDnsEnabled: Boolean = false,

    val orientationLocked: Boolean = true,
    val silentMode: Boolean = true,
    val focusActive: Boolean = false,
    val focusLabel: String = "Focus",

    val brightness: Float = 0.44f,
    val volume: Float = 0.78f,

    val smooth90Hz: Boolean = true,
    val dcDimming: Boolean = false,
    val hbmSunlight: Boolean = false,
    val flashlightOn: Boolean = false,
    val lowPowerMode: Boolean = false,
    val darkMode: Boolean = false,
    val ambientRainPlaying: Boolean = false,

    val mediaPlaying: Boolean = false,
    val mediaTitle: String = "Not Playing",
    val mediaArtist: String = "",
    val mediaAlbumArt: Bitmap? = null,
    val mediaUseRainArt: Boolean = false,
    val audioRoutePhone: Boolean = false,

    val timerRemainingSec: Int = 0,
    val wallpaperBitmap: Bitmap? = null,
)

@Stable
class ControlCenterController(
    private val context: Context?,
    val previewOnly: Boolean = false,
    private val deferInitialLoad: Boolean = false,
    initialState: ControlCenterState = ControlCenterState(),
) {
    var state by mutableStateOf(initialState)
        private set

    var onRequestDismissAndRun: ((() -> Unit) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var activeMediaController: MediaController? = null
    @Volatile
    private var hardwareInitialized = false
    @Volatile
    private var wallpaperLoadedOnce = false
    @Volatile
    var isPanelVisible: Boolean = !deferInitialLoad
    @Volatile
    private var rainThreadRunning = false
    private var rainTrack: AudioTrack? = null
    private var timerRunnable: Runnable? = null

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                state = state.copy(flashlightOn = enabled)
            }
        }
    }

    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (isPanelVisible) {
                refreshFromSystem()
            }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_WALLPAPER_CHANGED) {
                wallpaperLoadedOnce = false
                if (isPanelVisible) {
                    loadWallpaperAsync()
                }
            } else if (isPanelVisible) {
                refreshFromSystem()
            }
        }
    }

    init {
        if (!previewOnly && context != null && !deferInitialLoad) {
            ensureHardwareListeners()
            refreshFromSystem()
            loadWallpaperAsync()
        }
    }

    fun ensureHardwareListeners() {
        val ctx = if (previewOnly) null else context
        if (ctx == null || hardwareInitialized) return
        hardwareInitialized = true
        setupHardwareListeners(ctx)
    }

    fun onPanelOpening() {
        if (previewOnly || context == null) return
        isPanelVisible = true
        ensureHardwareListeners()
        refreshFromSystem()
        if (!wallpaperLoadedOnce || state.wallpaperBitmap == null) {
            loadWallpaperAsync()
        }
    }

    fun onPanelClosed() {
        isPanelVisible = false
    }

    private fun setupHardwareListeners(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager = cm
            torchCameraId = cm?.cameraIdList?.firstOrNull { id ->
                try {
                    cm.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Throwable) {
                    false
                }
            }
            cm?.registerTorchCallback(torchCallback, mainHandler)
        } catch (_: Throwable) {
        }

        try {
            val cr = ctx.contentResolver
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, settingsObserver)
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsObserver)
            cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, settingsObserver)
            cr.registerContentObserver(Settings.Global.getUriFor("low_power"), false, settingsObserver)
            cr.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), false, settingsObserver)
        } catch (_: Throwable) {
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(Intent.ACTION_WALLPAPER_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(systemReceiver, filter)
            }
        } catch (_: Throwable) {
        }

        refreshMediaSessions(ctx)
    }

    fun destroy() {
        stopRainAudio()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        val ctx = if (previewOnly) null else context
        if (ctx != null) {
            try {
                cameraManager?.unregisterTorchCallback(torchCallback)
            } catch (_: Throwable) {
            }
            try {
                ctx.contentResolver.unregisterContentObserver(settingsObserver)
            } catch (_: Throwable) {
            }
            try {
                ctx.unregisterReceiver(systemReceiver)
            } catch (_: Throwable) {
            }
        }
    }

    fun refreshFromSystem() {
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        val cr = ctx.contentResolver

        val airplane = try {
            Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Throwable) {
            false
        }

        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiOn = !airplane && (wm?.isWifiEnabled == true)

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simCarrier = try {
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
                ?: "Samurai"
        } catch (_: Throwable) {
            "Samurai"
        }
        val dataOn = try {
            !airplane && (tm?.isDataEnabled == true)
        } catch (_: Throwable) {
            !airplane
        }

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btOn = try {
            bm?.adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeCaps = try {
            cm?.getNetworkCapabilities(cm.activeNetwork)
        } catch (_: Throwable) {
            null
        }
        val wifiConnected = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wifiLabel = when {
            !wifiOn -> "Off"
            wifiConnected -> "Connected"
            else -> "On"
        }

        val dnsMode = try {
            Settings.Global.getString(cr, "private_dns_mode") ?: ""
        } catch (_: Throwable) {
            ""
        }
        val privateDns = dnsMode.equals("hostname", ignoreCase = true)

        val rotLocked = try {
            Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        } catch (_: Throwable) {
            false
        }

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val silent = (am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL) != AudioManager.RINGER_MODE_NORMAL
        val maxVol = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
        val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
        val volFrac = (curVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dnd = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) !=
            NotificationManager.INTERRUPTION_FILTER_ALL

        val rawBri = try {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Throwable) {
            128
        }
        val briFrac = (rawBri / 255f).coerceIn(0f, 1f)

        val peakHz = try {
            Settings.System.getFloat(cr, "peak_refresh_rate", 90f)
        } catch (_: Throwable) {
            90f
        }
        val dcDim = try {
            Settings.Global.getInt(cr, "samurai_dc_dimming", 0) == 1
        } catch (_: Throwable) {
            false
        }
        val hbm = try {
            Settings.Global.getInt(cr, "samurai_hbm", 0) == 1
        } catch (_: Throwable) {
            false
        }

        val lowPower = try {
            Settings.Global.getInt(cr, "low_power", 0) == 1
        } catch (_: Throwable) {
            false
        }

        val uiMode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isDark = uiMode?.nightMode == UiModeManager.MODE_NIGHT_YES

        val bmBat = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batPct = (bmBat?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 78).coerceIn(1, 100)
        val charging = bmBat?.isCharging == true

        val nextAlarm = try {
            Settings.System.getString(cr, Settings.System.NEXT_ALARM_FORMATTED)?.isNotBlank() == true
        } catch (_: Throwable) {
            false
        }

        val headerCarrier = when {
            airplane -> "Airplane Mode"
            wifiConnected -> "$simCarrier Wi-Fi"
            else -> simCarrier
        }

        refreshMediaSessions(ctx)

        state = state.copy(
            carrierLabel = headerCarrier,
            signalBars = if (airplane) 0 else 4,
            batteryPct = batPct,
            isCharging = charging,
            hasAlarm = nextAlarm,
            airplaneMode = airplane,
            wifiEnabled = wifiOn,
            wifiSsid = wifiLabel,
            cellularEnabled = dataOn,
            cellularLabel = if (dataOn) simCarrier else "Off",
            bluetoothEnabled = btOn,
            bluetoothLabel = if (btOn) "On" else "Off",
            privateDnsEnabled = privateDns,
            orientationLocked = rotLocked,
            silentMode = silent,
            focusActive = dnd,
            focusLabel = if (dnd) "Personal" else "Focus",
            brightness = briFrac,
            volume = volFrac,
            smooth90Hz = peakHz >= 85f,
            dcDimming = dcDim,
            hbmSunlight = hbm,
            lowPowerMode = lowPower,
            darkMode = isDark,
        )
    }

    private fun refreshMediaSessions(ctx: Context) {
        if (state.ambientRainPlaying) {
            state = state.copy(
                mediaPlaying = true,
                mediaTitle = "Rain Classic",
                mediaArtist = "Background Sounds",
                mediaUseRainArt = true,
            )
            return
        }
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val controllers = msm.getActiveSessions(null)
            val chosen = controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()
            activeMediaController = chosen
            if (chosen != null) {
                val meta = chosen.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }.orEmpty()
                val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                val playing = chosen.playbackState?.state == PlaybackState.STATE_PLAYING
                if (title != null) {
                    state = state.copy(
                        mediaPlaying = playing,
                        mediaTitle = title,
                        mediaArtist = artist,
                        mediaAlbumArt = art,
                        mediaUseRainArt = false,
                    )
                    return
                }
            }
        } catch (_: Throwable) {
        }
        state = state.copy(
            mediaPlaying = false,
            mediaTitle = "Not Playing",
            mediaArtist = "",
            mediaAlbumArt = null,
            mediaUseRainArt = false,
        )
    }

    fun loadWallpaperAsync() {
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        thread(name = "cc-wallpaper-loader", isDaemon = true) {
            try {
                val wm = WallpaperManager.getInstance(ctx)
                var drawable = try {
                    wm.drawable
                } catch (_: Throwable) {
                    null
                }
                if (drawable == null) {
                    drawable = try {
                        wm.wallpaperInfo?.loadThumbnail(ctx.packageManager)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (drawable == null) {
                    drawable = try {
                        wm.builtInDrawable
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (drawable != null) {
                    val targetW = 540
                    val targetH = 1200
                    val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(scaled)
                    val rawBmp = (drawable as? BitmapDrawable)?.bitmap
                    if (rawBmp != null && !rawBmp.isRecycled && rawBmp.width > 0 && rawBmp.height > 0) {
                        val sw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            rawBmp.config == Bitmap.Config.HARDWARE
                        ) {
                            rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            rawBmp
                        }
                        if (sw != null) {
                            val scale = kotlin.math.max(
                                targetW.toFloat() / sw.width.coerceAtLeast(1),
                                targetH.toFloat() / sw.height.coerceAtLeast(1),
                            )
                            val dw = sw.width * scale
                            val dh = sw.height * scale
                            val dx = (targetW - dw) * 0.5f
                            val dy = (targetH - dh) * 0.5f
                            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                            canvas.save()
                            canvas.translate(dx, dy)
                            canvas.scale(scale, scale)
                            canvas.drawBitmap(sw, 0f, 0f, paint)
                            canvas.restore()
                            if (sw !== rawBmp) sw.recycle()
                        }
                    } else {
                        drawable.setBounds(0, 0, targetW, targetH)
                        drawable.draw(canvas)
                    }
                    wallpaperLoadedOnce = true
                    mainHandler.post {
                        state = state.copy(wallpaperBitmap = scaled)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun tickHaptic() {
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        try {
            @Suppress("DEPRECATION")
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        } catch (_: Throwable) {
        }
    }

    private fun runShellAsync(cmd: String) {
        thread(isDaemon = true) {
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd)).waitFor()
                mainHandler.postDelayed({ refreshFromSystem() }, 220L)
            } catch (_: Throwable) {
            }
        }
    }

    fun toggleAirplaneMode() {
        val next = !state.airplaneMode
        state = state.copy(airplaneMode = next, wifiEnabled = if (next) false else state.wifiEnabled)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (next) 1 else 0)
            ctx.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", next))
        } catch (_: Throwable) {
            runShellAsync("cmd connectivity airplane-mode ${if (next) "enable" else "disable"}")
        }
    }

    fun toggleQuickShare() {
        val next = !state.quickShareActive
        state = state.copy(quickShareActive = next)
        tickHaptic()
    }

    fun toggleWifi() {
        val next = !state.wifiEnabled
        state = state.copy(wifiEnabled = next, wifiSsid = if (next) "Searching…" else "Off")
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            @Suppress("DEPRECATION")
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled = next
        } catch (_: Throwable) {
        }
        runShellAsync("svc wifi ${if (next) "enable" else "disable"}")
    }

    fun toggleCellularData() {
        val next = !state.cellularEnabled
        state = state.copy(cellularEnabled = next)
        tickHaptic()
        if (!previewOnly) {
            runShellAsync("svc data ${if (next) "enable" else "disable"}")
        }
    }

    fun toggleBluetooth() {
        val next = !state.bluetoothEnabled
        state = state.copy(bluetoothEnabled = next, bluetoothLabel = if (next) "On" else "Off")
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            @Suppress("DEPRECATION")
            if (next) adapter?.enable() else adapter?.disable()
        } catch (_: Throwable) {
            runShellAsync("svc bluetooth ${if (next) "enable" else "disable"}")
        }
    }

    fun toggleHotspot() {
        val next = !state.hotspotEnabled
        state = state.copy(hotspotEnabled = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        dismissThenLaunch(ctx) {
            Intent().apply {
                component = ComponentName("com.realme.samurai.iossettings", "com.realme.samurai.iossettings.IosSettingsActivity")
                putExtra("start_route", "hotspot")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun togglePrivateDns() {
        val next = !state.privateDnsEnabled
        state = state.copy(privateDnsEnabled = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val cr = ctx.contentResolver
            if (next) {
                Settings.Global.putString(cr, "private_dns_specifier", "dns.adguard-dns.com")
                Settings.Global.putString(cr, "private_dns_mode", "hostname")
            } else {
                Settings.Global.putString(cr, "private_dns_mode", "opportunistic")
            }
        } catch (_: Throwable) {
        }
    }

    fun toggleOrientationLock() {
        val nextLocked = !state.orientationLocked
        state = state.copy(orientationLocked = nextLocked)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (nextLocked) 0 else 1)
        } catch (_: Throwable) {
        }
    }

    fun toggleSilentMode() {
        val nextSilent = !state.silentMode
        state = state.copy(silentMode = nextSilent)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.ringerMode = if (nextSilent) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
        } catch (_: Throwable) {
        }
    }

    fun toggleFocusDnd() {
        val nextFocus = !state.focusActive
        state = state.copy(focusActive = nextFocus, focusLabel = if (nextFocus) "Personal" else "Focus")
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.setInterruptionFilter(
                if (nextFocus) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        } catch (_: Throwable) {
        }
    }

    fun setBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0.02f, 1f)
        state = state.copy(brightness = clamped)
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val cr = ctx.contentResolver
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (clamped * 255f).roundToInt().coerceIn(4, 255))
        } catch (_: Throwable) {
        }
    }

    fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        state = state.copy(volume = clamped)
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (clamped * maxV).roundToInt().coerceIn(0, maxV), 0)
        } catch (_: Throwable) {
        }
    }

    fun toggleSmooth90Hz() {
        val next = !state.smooth90Hz
        state = state.copy(smooth90Hz = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val hz = if (next) 90f else 60f
            Settings.System.putFloat(ctx.contentResolver, "peak_refresh_rate", hz)
            Settings.System.putFloat(ctx.contentResolver, "min_refresh_rate", hz)
        } catch (_: Throwable) {
        }
    }

    fun toggleDcDimming() {
        val next = !state.dcDimming
        state = state.copy(dcDimming = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            Settings.Global.putInt(ctx.contentResolver, "samurai_dc_dimming", if (next) 1 else 0)
            File("/sys/kernel/oppo_display/dimlayer_bl_en").writeText(if (next) "1" else "0")
        } catch (_: Throwable) {
        }
    }

    fun toggleFlashlight() {
        val next = !state.flashlightOn
        state = state.copy(flashlightOn = next)
        tickHaptic()
        if (previewOnly) return
        try {
            val id = torchCameraId
            if (id != null) {
                cameraManager?.setTorchMode(id, next)
            }
        } catch (_: Throwable) {
        }
    }

    fun toggleLowPowerMode() {
        val next = !state.lowPowerMode
        state = state.copy(lowPowerMode = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            Settings.Global.putInt(ctx.contentResolver, "low_power", if (next) 1 else 0)
        } catch (_: Throwable) {
        }
    }

    fun toggleDarkMode() {
        val next = !state.darkMode
        state = state.copy(darkMode = next)
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val uiMode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiMode?.nightMode = if (next) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        } catch (_: Throwable) {
            runShellAsync("cmd uimode night ${if (next) "yes" else "no"}")
        }
    }

    fun toggleLiquidGlassTintMode() {
        val ctx = (if (previewOnly) null else context) ?: return
        tickHaptic()
        try {
            val cur = Settings.Global.getInt(ctx.contentResolver, "samurai_liquid_glass_mode", 0)
            Settings.Global.putInt(ctx.contentResolver, "samurai_liquid_glass_mode", if (cur == 1) 0 else 1)
        } catch (_: Throwable) {
        }
    }

    fun toggleAmbientRain() {
        val next = !state.ambientRainPlaying
        tickHaptic()
        if (next) {
            state = state.copy(
                ambientRainPlaying = true,
                mediaPlaying = true,
                mediaTitle = "Rain Classic",
                mediaArtist = "Background Sounds",
                mediaUseRainArt = true,
            )
            if (!previewOnly) startRainAudio()
        } else {
            state = state.copy(ambientRainPlaying = false)
            stopRainAudio()
            if (!previewOnly && context != null) refreshMediaSessions(context)
        }
    }

    private fun startRainAudio() {
        if (rainThreadRunning) return
        rainThreadRunning = true
        thread(name = "cc-rain-synth", isDaemon = true) {
            try {
                val sampleRate = 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(4800)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                rainTrack = track
                track.play()
                val pcm = ShortArray(2048)
                var b0 = 0.0
                var b1 = 0.0
                var b2 = 0.0
                while (rainThreadRunning) {
                    for (i in pcm.indices) {
                        val white = Random.nextDouble(-1.0, 1.0)
                        b0 = 0.99765 * b0 + white * 0.0990460
                        b1 = 0.96300 * b1 + white * 0.2965164
                        b2 = 0.57000 * b2 + white * 1.0526913
                        val pink = (b0 + b1 + b2 + white * 0.1848) * 0.045
                        pcm[i] = (pink.coerceIn(-0.6, 0.6) * Short.MAX_VALUE).toInt().toShort()
                    }
                    track.write(pcm, 0, pcm.size)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    rainTrack?.stop()
                    rainTrack?.release()
                } catch (_: Throwable) {
                }
                rainTrack = null
            }
        }
    }

    private fun stopRainAudio() {
        rainThreadRunning = false
    }

    fun mediaPlayPause() {
        tickHaptic()
        if (state.ambientRainPlaying) {
            toggleAmbientRain()
            return
        }
        if (previewOnly) {
            state = state.copy(mediaPlaying = !state.mediaPlaying)
            return
        }
        val ctrl = activeMediaController
        if (ctrl != null) {
            val pb = ctrl.playbackState?.state
            if (pb == PlaybackState.STATE_PLAYING) {
                ctrl.transportControls.pause()
                state = state.copy(mediaPlaying = false)
            } else {
                ctrl.transportControls.play()
                state = state.copy(mediaPlaying = true)
            }
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun mediaPrevious() {
        tickHaptic()
        if (previewOnly) return
        val ctrl = activeMediaController
        if (ctrl != null) ctrl.transportControls.skipToPrevious() else dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun mediaNext() {
        tickHaptic()
        if (previewOnly) return
        val ctrl = activeMediaController
        if (ctrl != null) ctrl.transportControls.skipToNext() else dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Throwable) {
        }
    }

    fun startQuickTimer(seconds: Int) {
        tickHaptic()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        state = state.copy(timerRemainingSec = seconds.coerceAtLeast(0))
        if (seconds <= 0 || previewOnly) return
        val tick = object : Runnable {
            override fun run() {
                val next = state.timerRemainingSec - 1
                if (next <= 0) {
                    state = state.copy(timerRemainingSec = 0)
                    tickHaptic()
                } else {
                    state = state.copy(timerRemainingSec = next)
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        timerRunnable = tick
        mainHandler.postDelayed(tick, 1000L)
    }

    fun openCamera() {
        val ctx = (if (previewOnly) null else context) ?: return
        tickHaptic()
        dismissThenLaunch(ctx) {
            ctx.packageManager.getLaunchIntentForPackage("com.google.android.GoogleCameraEng")
                ?: Intent("android.media.action.STILL_IMAGE_CAMERA").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openIosSettings(route: String? = null) {
        val ctx = (if (previewOnly) null else context) ?: return
        tickHaptic()
        dismissThenLaunch(ctx) {
            Intent().apply {
                component = ComponentName("com.realme.samurai.iossettings", "com.realme.samurai.iossettings.IosSettingsActivity")
                if (route != null) putExtra("start_route", route)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun openScreenMirroring() {
        val ctx = (if (previewOnly) null else context) ?: return
        tickHaptic()
        dismissThenLaunch(ctx) {
            Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun triggerScreenshot() {
        tickHaptic()
        if (previewOnly) return
        val runner = onRequestDismissAndRun
        if (runner != null) {
            runner {
                mainHandler.postDelayed({ runShellAsync("input keyevent 120") }, 260L)
            }
        } else {
            runShellAsync("input keyevent 120")
        }
    }

    fun performPowerAction(mode: String) {
        tickHaptic()
        val ctx = (if (previewOnly) null else context) ?: return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (mode) {
                "reboot" -> pm?.reboot(null)
                "recovery" -> pm?.reboot("recovery")
                "shutdown" -> runShellAsync("svc power shutdown")
            }
        } catch (_: Throwable) {
            when (mode) {
                "reboot" -> runShellAsync("svc power reboot")
                "recovery" -> runShellAsync("svc power reboot recovery")
                "shutdown" -> runShellAsync("svc power shutdown")
            }
        }
    }

    private fun dismissThenLaunch(ctx: Context, intentBuilder: () -> Intent) {
        val launchBlock = {
            try {
                ctx.startActivity(intentBuilder().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
        val runner = onRequestDismissAndRun
        if (runner != null) runner(launchBlock) else launchBlock()
    }

    companion object {
        fun previewClearPlaying(): ControlCenterController = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = ControlCenterState(
                carrierLabel = "Jio Wi-Fi",
                signalBars = 4,
                batteryPct = 68,
                isCharging = false,
                hasAlarm = true,
                airplaneMode = false,
                quickShareActive = true,
                wifiEnabled = true,
                wifiSsid = "Airtel_5G",
                cellularEnabled = true,
                bluetoothEnabled = true,
                hotspotEnabled = false,
                privateDnsEnabled = false,
                orientationLocked = true,
                silentMode = true,
                focusActive = true,
                focusLabel = "Personal",
                brightness = 0.44f,
                volume = 0.78f,
                smooth90Hz = false,
                dcDimming = false,
                flashlightOn = false,
                lowPowerMode = false,
                darkMode = false,
                ambientRainPlaying = true,
                mediaPlaying = true,
                mediaTitle = "Rain Classic",
                mediaArtist = "Background Sounds",
                mediaUseRainArt = true,
                audioRoutePhone = true,
            ),
        )

        fun previewTintedIdle(): ControlCenterController = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = ControlCenterState(
                carrierLabel = "Airtel",
                signalBars = 2,
                batteryPct = 65,
                isCharging = false,
                hasAlarm = true,
                airplaneMode = false,
                quickShareActive = true,
                wifiEnabled = true,
                wifiSsid = "Airtel_5G",
                cellularEnabled = true,
                bluetoothEnabled = true,
                hotspotEnabled = false,
                privateDnsEnabled = false,
                orientationLocked = true,
                silentMode = true,
                focusActive = false,
                focusLabel = "Focus",
                brightness = 0.42f,
                volume = 0.62f,
                smooth90Hz = false,
                dcDimming = true,
                flashlightOn = false,
                lowPowerMode = false,
                darkMode = false,
                ambientRainPlaying = false,
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                mediaUseRainArt = false,
                audioRoutePhone = false,
            ),
        )
    }
}
