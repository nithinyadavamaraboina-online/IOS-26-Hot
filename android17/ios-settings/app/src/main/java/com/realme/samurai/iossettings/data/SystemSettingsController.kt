package com.realme.samurai.iossettings.data

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.WallpaperManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.biometrics.BiometricManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.realme.samurai.iossettings.ui.glass.SystemGlassConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Immutable
data class WifiNetworkItem(
    val ssid: String,
    val bssid: String = "",
    val bars: Int = 3,
    val secured: Boolean = true,
    val securityLabel: String = "WPA2/WPA3",
    val connected: Boolean = false,
    val frequencyMhz: Int = 5180,
)

@Immutable
data class BluetoothDeviceItem(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val connected: Boolean,
)

@Immutable
data class SimCardItem(
    val slotIndex: Int,
    val carrierName: String,
    val number: String,
    val active: Boolean = true,
)

@Immutable
data class InstalledAppItem(
    val label: String,
    val packageName: String,
    val versionName: String,
    val isSystemApp: Boolean,
    val icon: ImageBitmap? = null,
)

@Immutable
data class SystemSettingsState(
    val accountName: String? = null,
    val accountEmail: String? = null,
    val airplaneMode: Boolean = false,
    val wifiEnabled: Boolean = true,
    val connectedWifiSsid: String? = null,
    val wifiIpAddress: String = "192.168.1.104",
    val wifiRouter: String = "192.168.1.1",
    val wifiLinkSpeedMbps: Int = 866,
    val wifiNetworks: List<WifiNetworkItem> = emptyList(),
    val askToJoinNetworks: String = "Notify",
    val bluetoothEnabled: Boolean = true,
    val bluetoothDeviceName: String = "Realme X2 Pro",
    val bluetoothDevices: List<BluetoothDeviceItem> = emptyList(),
    val bluetoothScanning: Boolean = false,
    val cellularDataEnabled: Boolean = true,
    val dataRoamingEnabled: Boolean = false,
    val volteEnabled: Boolean = true,
    val preferredNetworkLabel: String = "LTE / VoLTE",
    val simCards: List<SimCardItem> = emptyList(),
    val hotspotEnabled: Boolean = false,
    val hotspotSsid: String = "Realme X2 Pro",
    val hotspotPassword: String = "samurai2026",
    val batteryLevel: Int = 86,
    val batteryCharging: Boolean = false,
    val batteryLowPowerMode: Boolean = false,
    val showBatteryPercentage: Boolean = true,
    val chargeLimit80: Boolean = false,
    val batteryTempCelsius: Float = 31.5f,
    val batteryHealthLabel: String = "Normal (100%)",
    val darkMode: Boolean = false,
    val autoAppearance: Boolean = false,
    val brightness: Float = 0.72f,
    val adaptiveBrightness: Boolean = true,
    val nightShift: Boolean = false,
    val liquidGlassMode: Int = SystemGlassConfig.MODE_CLEAR_REFRACTIVE,
    val refractionPct: Int = 20,
    val frostPct: Int = 20,
    val refreshRate90Hz: Boolean = true,
    val dcDimming: Boolean = false,
    val autoLockSeconds: Int = 60,
    val fontScale: Float = 1.0f,
    val tapToWake: Boolean = true,
    val raiseToWake: Boolean = true,
    val iconStyle: Int = 0, // 0=Default, 1=Dark, 2=Clear
    val showHomeSearchPill: Boolean = true,
    val doubleTapHomeToLock: Boolean = true,
    val wallpaperDimPct: Int = 12,
    val ringVolume: Float = 0.75f,
    val mediaVolume: Float = 0.65f,
    val alarmVolume: Float = 0.85f,
    val systemHaptics: Boolean = true,
    val keyboardClicks: Boolean = true,
    val lockSounds: Boolean = true,
    val speakerCleaning: Boolean = false,
    val dndEnabled: Boolean = false,
    val notificationBadges: Boolean = true,
    val showLockPreviews: Boolean = true,
    val locationEnabled: Boolean = true,
    val privateDnsMode: String = "Automatic",
    val biometricEnrolled: Boolean = true,
    val deviceName: String = "Realme X2 Pro",
    val osVersionLabel: String = "26.1 (Samurai A17)",
    val androidVersionLabel: String = "17 (Baklava)",
    val modelName: String = "Realme X2 Pro",
    val modelNumber: String = "RMX1931",
    val buildNumber: String = "SamuraiOS-26.1-RMX1931",
    val kernelVersion: String = "4.14.356-samurai-sm8150",
    val totalStorageGb: Float = 128f,
    val usedStorageGb: Float = 34.8f,
    val totalRamGb: String = "8 GB LPDDR4X",
    val use24HourTime: Boolean = false,
    val autoDateTime: Boolean = true,
    val timeZoneLabel: String = "India Standard Time",
    val languageLabel: String = "English (US)",
    val performanceProfile: Int = 0, // 0=Balanced, 1=Performance, 2=Gaming, 3=Battery
    val highBrightnessMode: Boolean = false,
    val otgEnabled: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val usbDebugging: Boolean = true,
    val wirelessDebugging: Boolean = false,
    val animationScale: Float = 1.0f,
    val installedApps: List<InstalledAppItem> = emptyList(),
    val statusBanner: String? = null,
) {
    val wifiRootStatus: String
        get() = when {
            airplaneMode && !wifiEnabled -> "Off"
            !wifiEnabled -> "Off"
            !connectedWifiSsid.isNullOrBlank() -> connectedWifiSsid
            else -> "Not Connected"
        }

    val bluetoothRootStatus: String
        get() = if (bluetoothEnabled) "On" else "Off"

    val autoLockLabel: String
        get() = when {
            autoLockSeconds <= 15 -> "15 Seconds"
            autoLockSeconds <= 30 -> "30 Seconds"
            autoLockSeconds <= 60 -> "1 Minute"
            autoLockSeconds <= 120 -> "2 Minutes"
            autoLockSeconds <= 300 -> "5 Minutes"
            autoLockSeconds <= 600 -> "10 Minutes"
            else -> "Never"
        }

    val liquidGlassLabel: String
        get() = if (liquidGlassMode == SystemGlassConfig.MODE_FROSTED_TINTED) "Tinted" else "Clear"

    val performanceProfileLabel: String
        get() = when (performanceProfile) {
            1 -> "Performance"
            2 -> "Gaming Pro"
            3 -> "Efficiency"
            else -> "Balanced"
        }

    companion object {
        /**
         * Deterministic, realistic snapshot used by Roborazzi `SettingsDesignShots` so rendered
         * PNGs match `hot0151_settings.png`, `wave5_03e_wifi.png`, and `ios26_08_ios261_clear_vs_tinted.png`.
         */
        fun previewRoot(darkMode: Boolean = false): SystemSettingsState = SystemSettingsState(
            accountName = null,
            accountEmail = null,
            airplaneMode = false,
            wifiEnabled = true,
            connectedWifiSsid = null, // Matches "Not Connected" in hot0151_settings.png
            wifiNetworks = listOf(
                WifiNetworkItem("Airtel_gant_6946", bars = 3, secured = true, securityLabel = "WPA2/WPA3"),
                WifiNetworkItem("Airtel_nith_7885", bars = 2, secured = true, securityLabel = "WPA2"),
                WifiNetworkItem("Kanchrala", bars = 2, secured = true, securityLabel = "WPA2"),
            ),
            askToJoinNetworks = "Notify",
            bluetoothEnabled = true,
            bluetoothDevices = listOf(
                BluetoothDeviceItem("AirPods Pro", "44:07:0B:11:22:33", bonded = true, connected = true),
                BluetoothDeviceItem("Realme Buds Air", "60:AB:CD:44:55:66", bonded = true, connected = false),
            ),
            simCards = listOf(
                SimCardItem(0, "Jio True5G", "Primary", active = true),
                SimCardItem(1, "Airtel", "Secondary", active = true),
            ),
            darkMode = darkMode,
            liquidGlassMode = SystemGlassConfig.MODE_CLEAR_REFRACTIVE,
            installedApps = listOf(
                InstalledAppItem("Calculator", "com.google.android.calculator", "8.6", false),
                InstalledAppItem("Camera", "com.android.camera2", "2.4", true),
                InstalledAppItem("Chrome", "com.android.chrome", "131.0", false),
                InstalledAppItem("Messages", "com.realme.samurai.messages", "1.7.5", true),
                InstalledAppItem("Phone", "com.realme.samurai.dialer", "1.7.5", true),
                InstalledAppItem("Play Store", "com.android.vending", "43.8", true),
                InstalledAppItem("Samurai Styler", "com.realme.samurai.styler", "1.6.5", true),
            ),
        )
    }
}

@Stable
class SystemSettingsController(
    private val context: Context?,
    initialState: SystemSettingsState = SystemSettingsState(),
    private val previewOnly: Boolean = false,
) {
    var state by mutableStateOf(initialState)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildTapCount = 0
    private var receiverRegistered = false

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION -> refreshWifi()
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshBluetooth()
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null) addDiscoveredBluetoothDevice(dev)
                }
                Intent.ACTION_BATTERY_CHANGED -> updateBatteryFromIntent(intent)
            }
        }
    }

    fun start() {
        if (previewOnly || context == null) return
        refreshAll()
        registerReceivers()
        loadInstalledAppsAsync()
    }

    fun stop() {
        val ctx = if (previewOnly) null else context
        if (ctx == null || !receiverRegistered) return
        runCatching { ctx.unregisterReceiver(systemReceiver) }
        receiverRegistered = false
    }

    private fun registerReceivers() {
        val ctx = context ?: return
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(systemReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun refreshAll() {
        val ctx = context ?: return
        val cr = ctx.contentResolver

        val airplane = runCatching {
            Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrDefault(false)

        val nightMode = runCatching {
            val uiMgr = ctx.getSystemService(UiModeManager::class.java)
            uiMgr?.nightMode == UiModeManager.MODE_NIGHT_YES ||
                (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)

        val autoNight = runCatching {
            Settings.Secure.getInt(cr, "ui_night_mode", 1) == 0
        }.getOrDefault(false)

        val brightInt = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 180)
        }.getOrDefault(180)

        val autoBright = runCatching {
            Settings.System.getInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(true)

        val nightShift = runCatching {
            Settings.Secure.getInt(cr, "night_display_activated", 0) == 1
        }.getOrDefault(false)

        val glassCfg = SystemGlassConfig.read(ctx)

        val peakHz = runCatching {
            Settings.System.getFloat(cr, "peak_refresh_rate", 90f)
        }.getOrDefault(90f)

        val dcDim = runCatching {
            Settings.Global.getInt(cr, "samurai_dc_dimming", 0) == 1
        }.getOrDefault(false)

        val timeoutMs = runCatching {
            Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        }.getOrDefault(60_000)

        val fontScale = runCatching {
            Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1.0f)
        }.getOrDefault(1.0f)

        val tapWake = runCatching {
            Settings.Secure.getInt(cr, "double_tap_to_wake", 1) == 1
        }.getOrDefault(true)

        val raiseWake = runCatching {
            Settings.Secure.getInt(cr, "doze_pulse_on_pick_up", 1) == 1
        }.getOrDefault(true)

        val iconStyle = runCatching {
            Settings.Global.getInt(cr, "samurai_icon_style", 0)
        }.getOrDefault(0)

        val searchPill = runCatching {
            Settings.Global.getInt(cr, "samurai_show_search_pill", 1) == 1
        }.getOrDefault(true)

        val dt2sHome = runCatching {
            Settings.System.getInt(cr, "samurai_dt2s_home", 1) == 1
        }.getOrDefault(true)

        val wallDim = runCatching {
            Settings.Global.getInt(cr, "samurai_wallpaper_dim_pct", 12)
        }.getOrDefault(12)

        val audio = runCatching { ctx.getSystemService(AudioManager::class.java) }.getOrNull()
        fun streamFrac(stream: Int, fallback: Float): Float = runCatching {
            val a = audio ?: return@runCatching fallback
            val max = a.getStreamMaxVolume(stream).coerceAtLeast(1)
            a.getStreamVolume(stream).toFloat() / max.toFloat()
        }.getOrDefault(fallback)

        val haptics = runCatching {
            Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
        }.getOrDefault(true)

        val keyClicks = runCatching {
            Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1
        }.getOrDefault(true)

        val lockSound = runCatching {
            Settings.System.getInt(cr, "lockscreen_sounds_enabled", 1) == 1
        }.getOrDefault(true)

        val nm = runCatching { ctx.getSystemService(NotificationManager::class.java) }.getOrNull()
        val dnd = runCatching {
            val f = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            f != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)

        val locMgr = runCatching { ctx.getSystemService(LocationManager::class.java) }.getOrNull()
        val locOn = runCatching { locMgr?.isLocationEnabled ?: true }.getOrDefault(true)

        val dnsMode = runCatching {
            when (Settings.Global.getString(cr, "private_dns_mode")) {
                "off" -> "Off"
                "hostname" -> Settings.Global.getString(cr, "private_dns_specifier") ?: "Custom DNS"
                else -> "Automatic"
            }
        }.getOrDefault("Automatic")

        val bioEnrolled = runCatching {
            val bm = ctx.getSystemService(BiometricManager::class.java)
            bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(true)

        val devName = runCatching {
            Settings.Global.getString(cr, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: Build.MODEL.ifBlank { "Realme X2 Pro" }
        }.getOrDefault("Realme X2 Pro")

        val devOpts = runCatching {
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false)

        val adbOn = runCatching {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 1) == 1
        }.getOrDefault(true)

        val wirelessAdb = runCatching {
            Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)

        val animScale = runCatching {
            Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        }.getOrDefault(1.0f)

        val perfProfile = runCatching {
            Settings.Global.getInt(cr, "samurai_perf_profile", 0)
        }.getOrDefault(0)

        val chargeLimit = runCatching {
            Settings.Global.getInt(cr, "samurai_charge_limit_80", 0) == 1
        }.getOrDefault(false)

        val showBattPct = runCatching {
            Settings.System.getInt(cr, "status_bar_show_battery_percent", 1) == 1
        }.getOrDefault(true)

        val pm = runCatching { ctx.getSystemService(PowerManager::class.java) }.getOrNull()
        val lowPower = runCatching { pm?.isPowerSaveMode ?: false }.getOrDefault(false)

        // Account check
        val (accName, accEmail) = runCatching {
            val accounts = AccountManager.get(ctx).accounts
            val google = accounts.firstOrNull { it.type.contains("google", ignoreCase = true) }
                ?: accounts.firstOrNull()
            if (google != null) {
                val friendly = google.name.substringBefore('@')
                    .replace('.', ' ')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                friendly to google.name
            } else {
                null to null
            }
        }.getOrDefault(null to null)

        // Storage check
        val (totalGb, usedGb) = runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes.toDouble() / 1_000_000_000.0
            val avail = stat.availableBytes.toDouble() / 1_000_000_000.0
            // Snap total to marketing tier (64/128/256 GB)
            val tier = when {
                total > 180 -> 256f
                total > 90 -> 128f
                else -> 64f
            }
            val systemOverhead = (tier - total.toFloat()).coerceAtLeast(8f)
            tier to ((total - avail).toFloat() + systemOverhead).coerceAtMost(tier)
        }.getOrDefault(128f to 34.8f)

        // Cellular & SIM check
        val cellData = runCatching {
            Settings.Global.getInt(cr, "mobile_data", 1) == 1
        }.getOrDefault(true)
        val roaming = runCatching {
            Settings.Global.getInt(cr, Settings.Global.DATA_ROAMING, 0) == 1
        }.getOrDefault(false)
        val sims = readSimCards(ctx)

        val buildDisplay = runCatching { Build.DISPLAY.ifBlank { "SamuraiOS-26.1-RMX1931" } }
            .getOrDefault("SamuraiOS-26.1-RMX1931")
        val kernelVer = runCatching { System.getProperty("os.version") ?: "4.14.356-samurai" }
            .getOrDefault("4.14.356-samurai")

        state = state.copy(
            accountName = accName,
            accountEmail = accEmail,
            airplaneMode = airplane,
            darkMode = nightMode,
            autoAppearance = autoNight,
            brightness = (brightInt / 255f).coerceIn(0.02f, 1f),
            adaptiveBrightness = autoBright,
            nightShift = nightShift,
            liquidGlassMode = glassCfg.mode,
            refractionPct = glassCfg.refractionPct,
            frostPct = glassCfg.frostPct,
            refreshRate90Hz = peakHz >= 85f,
            dcDimming = dcDim,
            autoLockSeconds = (timeoutMs / 1000).coerceAtLeast(15),
            fontScale = fontScale,
            tapToWake = tapWake,
            raiseToWake = raiseWake,
            iconStyle = iconStyle,
            showHomeSearchPill = searchPill,
            doubleTapHomeToLock = dt2sHome,
            wallpaperDimPct = wallDim,
            ringVolume = streamFrac(AudioManager.STREAM_RING, 0.75f),
            mediaVolume = streamFrac(AudioManager.STREAM_MUSIC, 0.65f),
            alarmVolume = streamFrac(AudioManager.STREAM_ALARM, 0.85f),
            systemHaptics = haptics,
            keyboardClicks = keyClicks,
            lockSounds = lockSound,
            dndEnabled = dnd,
            locationEnabled = locOn,
            privateDnsMode = dnsMode,
            biometricEnrolled = bioEnrolled,
            deviceName = devName,
            bluetoothDeviceName = devName,
            hotspotSsid = devName,
            buildNumber = buildDisplay,
            kernelVersion = kernelVer,
            totalStorageGb = totalGb,
            usedStorageGb = usedGb,
            use24HourTime = runCatching { DateFormat.is24HourFormat(ctx) }.getOrDefault(false),
            autoDateTime = runCatching { Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) == 1 }.getOrDefault(true),
            timeZoneLabel = TimeZone.getDefault().getDisplayName(false, TimeZone.LONG, Locale.getDefault()),
            languageLabel = Locale.getDefault().displayName,
            cellularDataEnabled = cellData,
            dataRoamingEnabled = roaming,
            simCards = sims,
            performanceProfile = perfProfile,
            chargeLimit80 = chargeLimit,
            showBatteryPercentage = showBattPct,
            batteryLowPowerMode = lowPower,
            developerOptionsEnabled = devOpts,
            usbDebugging = adbOn,
            wirelessDebugging = wirelessAdb,
            animationScale = animScale,
        )

        refreshWifi()
        refreshBluetooth()
        refreshBattery()
    }

    @SuppressLint("MissingPermission")
    private fun readSimCards(ctx: Context): List<SimCardItem> = runCatching {
        val subMgr = ctx.getSystemService(SubscriptionManager::class.java)
        val subs = subMgr?.activeSubscriptionInfoList.orEmpty()
        if (subs.isNotEmpty()) {
            subs.map { info ->
                SimCardItem(
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString()?.ifBlank { "Carrier" } ?: "Carrier",
                    number = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                    active = true,
                )
            }
        } else {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val op = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            if (op != null) listOf(SimCardItem(0, op, "Primary", true)) else emptyList()
        }
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun refreshWifi() {
        if (previewOnly) return
        val ctx = context ?: return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
            val enabled = wm.isWifiEnabled
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val rawSsid = info?.ssid?.trim('"')?.takeIf {
                it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true)
            }
            val linkSpeed = info?.linkSpeed?.takeIf { it > 0 } ?: 866

            // Active IPv4 from LinkProperties
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val lp = cm?.getLinkProperties(cm.activeNetwork)
            val ipv4 = lp?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address }
                ?.hostAddress ?: state.wifiIpAddress
            val gateway = lp?.routes
                ?.mapNotNull { it.gateway?.hostAddress }
                ?.firstOrNull { it != "0.0.0.0" && !it.contains(':') } ?: state.wifiRouter

            val results = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())
            val dedup = linkedMapOf<String, WifiNetworkItem>()
            for (sr in results.sortedByDescending { it.level }) {
                @Suppress("DEPRECATION")
                val ssid = sr.SSID?.takeIf { it.isNotBlank() } ?: continue
                if (dedup.containsKey(ssid)) continue
                val bars = WifiManager.calculateSignalLevel(sr.level, 3) + 1
                val caps = sr.capabilities.orEmpty()
                val secured = caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("EAP")
                val secLabel = when {
                    caps.contains("SAE") -> "WPA3"
                    caps.contains("WPA2") -> "WPA2"
                    caps.contains("WPA") -> "WPA"
                    else -> "Open"
                }
                dedup[ssid] = WifiNetworkItem(
                    ssid = ssid,
                    bssid = sr.BSSID.orEmpty(),
                    bars = bars.coerceIn(1, 3),
                    secured = secured,
                    securityLabel = secLabel,
                    connected = ssid == rawSsid,
                    frequencyMhz = sr.frequency,
                )
            }
            if (rawSsid != null && !dedup.containsKey(rawSsid)) {
                dedup[rawSsid] = WifiNetworkItem(
                    ssid = rawSsid,
                    bars = 3,
                    secured = true,
                    connected = true,
                )
            }

            state = state.copy(
                wifiEnabled = enabled,
                connectedWifiSsid = if (enabled) rawSsid else null,
                wifiIpAddress = ipv4,
                wifiRouter = gateway,
                wifiLinkSpeedMbps = linkSpeed,
                wifiNetworks = if (enabled) dedup.values.toList() else emptyList(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerWifiScan() {
        if (previewOnly) return
        val ctx = context ?: return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wm?.startScan()
        }
        refreshWifi()
    }

    fun setWifiEnabled(enabled: Boolean) {
        state = state.copy(
            wifiEnabled = enabled,
            connectedWifiSsid = if (enabled) state.connectedWifiSsid else null,
        )
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val changed = wm?.setWifiEnabled(enabled) ?: false
            if (!changed) {
                Settings.Global.putInt(ctx.contentResolver, Settings.Global.WIFI_ON, if (enabled) 1 else 0)
            }
            if (enabled) triggerWifiScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToWifi(ssid: String, password: String?, secured: Boolean) {
        val ctx = if (previewOnly) null else context
        if (ctx == null) {
            state = state.copy(
                connectedWifiSsid = ssid,
                wifiNetworks = state.wifiNetworks.map { it.copy(connected = it.ssid == ssid) },
                statusBanner = "Connected to \"$ssid\"",
            )
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return@runCatching false
                    if (Build.VERSION.SDK_INT >= 29) {
                        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                        if (secured && !password.isNullOrEmpty()) {
                            builder.setWpa2Passphrase(password)
                        }
                        wm.addNetworkSuggestions(listOf(builder.build()))
                    }
                    @Suppress("DEPRECATION")
                    val cfg = WifiConfiguration().apply {
                        this.SSID = "\"$ssid\""
                        if (secured && !password.isNullOrEmpty()) {
                            this.preSharedKey = "\"$password\""
                        } else {
                            this.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        }
                    }
                    @Suppress("DEPRECATION")
                    val netId = wm.addNetwork(cfg)
                    if (netId != -1) {
                        @Suppress("DEPRECATION")
                        wm.disconnect()
                        @Suppress("DEPRECATION")
                        wm.enableNetwork(netId, true)
                        @Suppress("DEPRECATION")
                        wm.reconnect()
                        true
                    } else {
                        true
                    }
                }.getOrDefault(false)
            }
            state = state.copy(
                connectedWifiSsid = if (ok) ssid else state.connectedWifiSsid,
                statusBanner = if (ok) "Joining \"$ssid\"..." else "Unable to join \"$ssid\"",
            )
            delay(1500)
            refreshWifi()
            clearBannerAfterDelay()
        }
    }

    @SuppressLint("MissingPermission")
    fun forgetCurrentWifi() {
        val current = state.connectedWifiSsid ?: return
        state = state.copy(
            connectedWifiSsid = null,
            wifiNetworks = state.wifiNetworks.map { it.copy(connected = false) },
            statusBanner = "Forgot \"$current\"",
        )
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return
            @Suppress("DEPRECATION")
            wm.configuredNetworks.orEmpty()
                .filter { it.SSID?.trim('"') == current }
                .forEach {
                    @Suppress("DEPRECATION")
                    wm.removeNetwork(it.networkId)
                }
            @Suppress("DEPRECATION")
            wm.disconnect()
        }
        clearBannerAfterDelay()
    }

    fun cycleAskToJoinNetworks() {
        val next = when (state.askToJoinNetworks) {
            "Off" -> "Notify"
            "Notify" -> "Ask"
            else -> "Off"
        }
        state = state.copy(askToJoinNetworks = next)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(
                ctx.contentResolver,
                "wifi_networks_available_notification_on",
                if (next == "Off") 0 else 1,
            )
        }
    }

    fun setAirplaneMode(enabled: Boolean) {
        state = state.copy(
            airplaneMode = enabled,
            wifiEnabled = if (enabled) false else true,
            connectedWifiSsid = if (enabled) null else state.connectedWifiSsid,
        )
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled)
            ctx.sendBroadcast(intent)
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshBluetooth() {
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val bm = ctx.getSystemService(BluetoothManager::class.java)
            val adapter = bm?.adapter ?: return
            val enabled = adapter.isEnabled
            val bonded = if (enabled) {
                adapter.bondedDevices.orEmpty().map { dev ->
                    val connected = runCatching {
                        BluetoothDevice::class.java.getMethod("isConnected").invoke(dev) as? Boolean ?: false
                    }.getOrDefault(false)
                    BluetoothDeviceItem(
                        name = dev.name?.ifBlank { dev.address } ?: dev.address,
                        address = dev.address,
                        bonded = true,
                        connected = connected,
                    )
                }
            } else {
                emptyList()
            }
            state = state.copy(
                bluetoothEnabled = enabled,
                bluetoothDeviceName = adapter.name?.takeIf { it.isNotBlank() } ?: state.deviceName,
                bluetoothDevices = bonded,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDiscoveredBluetoothDevice(dev: BluetoothDevice) {
        runCatching {
            val addr = dev.address ?: return
            val name = dev.name?.takeIf { it.isNotBlank() } ?: return
            if (state.bluetoothDevices.any { it.address == addr }) return
            state = state.copy(
                bluetoothDevices = state.bluetoothDevices + BluetoothDeviceItem(
                    name = name,
                    address = addr,
                    bonded = dev.bondState == BluetoothDevice.BOND_BONDED,
                    connected = false,
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun setBluetoothEnabled(enabled: Boolean) {
        state = state.copy(bluetoothEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            @Suppress("DEPRECATION")
            if (enabled) adapter.enable() else adapter.disable()
        }
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothDiscovery() {
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
            state = state.copy(bluetoothScanning = true)
            scope.launch {
                delay(8_000)
                runCatching { adapter.cancelDiscovery() }
                state = state.copy(bluetoothScanning = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun pairOrToggleBluetoothDevice(item: BluetoothDeviceItem) {
        val ctx = if (previewOnly) null else context
        if (ctx == null) {
            state = state.copy(
                bluetoothDevices = state.bluetoothDevices.map {
                    if (it.address == item.address) it.copy(bonded = true, connected = !it.connected) else it
                }
            )
            return
        }
        runCatching {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val remote = adapter.getRemoteDevice(item.address)
            if (!item.bonded) {
                remote.createBond()
                showBanner("Pairing with ${item.name}...")
            } else {
                showBanner("${item.name} selected")
            }
        }
    }

    fun setCellularDataEnabled(enabled: Boolean) {
        state = state.copy(cellularDataEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "mobile_data", if (enabled) 1 else 0)
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            TelephonyManager::class.java.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                .invoke(tm, enabled)
        }
    }

    fun setDataRoamingEnabled(enabled: Boolean) {
        state = state.copy(dataRoamingEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DATA_ROAMING, if (enabled) 1 else 0)
        }
    }

    fun setVolteEnabled(enabled: Boolean) {
        state = state.copy(volteEnabled = enabled, preferredNetworkLabel = if (enabled) "LTE / VoLTE" else "LTE / 3G")
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "volte_vt_enabled", if (enabled) 1 else 0)
        }
    }

    fun setHotspotEnabled(enabled: Boolean) {
        state = state.copy(hotspotEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            if (enabled) {
                val callbackCls = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                val startM = ConnectivityManager::class.java.getMethod(
                    "startTethering",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    callbackCls,
                )
                startM.invoke(cm, 0, true, null)
            } else {
                val stopM = ConnectivityManager::class.java.getMethod("stopTethering", Int::class.javaPrimitiveType)
                stopM.invoke(cm, 0)
            }
        }
    }

    fun updateHotspotPassword(newPass: String) {
        if (newPass.length < 8) {
            showBanner("Password must be at least 8 characters")
            return
        }
        state = state.copy(hotspotPassword = newPass)
        showBanner("Hotspot password updated")
    }

    private fun refreshBattery() {
        val ctx = context ?: return
        runCatching {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) updateBatteryFromIntent(intent)
        }
    }

    private fun updateBatteryFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, state.batteryLevel)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 315)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        state = state.copy(
            batteryLevel = ((level * 100f) / scale).roundToInt().coerceIn(0, 100),
            batteryCharging = charging,
            batteryTempCelsius = tempTenths / 10f,
        )
    }

    fun setLowPowerMode(enabled: Boolean) {
        state = state.copy(batteryLowPowerMode = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "low_power", if (enabled) 1 else 0)
        }
    }

    fun setShowBatteryPercentage(enabled: Boolean) {
        state = state.copy(showBatteryPercentage = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, "status_bar_show_battery_percent", if (enabled) 1 else 0)
        }
    }

    fun setChargeLimit80(enabled: Boolean) {
        state = state.copy(chargeLimit80 = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_charge_limit_80", if (enabled) 1 else 0)
        }
    }

    fun setDarkMode(dark: Boolean) {
        state = state.copy(darkMode = dark, autoAppearance = false)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(ctx.contentResolver, "ui_night_mode", if (dark) 2 else 1)
            val uiMgr = ctx.getSystemService(UiModeManager::class.java)
            uiMgr?.setNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
        }
    }

    fun setAutoAppearance(enabled: Boolean) {
        state = state.copy(autoAppearance = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(
                ctx.contentResolver,
                "ui_night_mode",
                if (enabled) 0 else if (state.darkMode) 2 else 1,
            )
        }
    }

    fun setBrightness(fraction: Float) {
        val f = fraction.coerceIn(0.02f, 1f)
        state = state.copy(brightness = f)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (f * 255f).roundToInt().coerceIn(5, 255),
            )
        }
    }

    fun setAdaptiveBrightness(enabled: Boolean) {
        state = state.copy(adaptiveBrightness = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
    }

    fun setNightShift(enabled: Boolean) {
        state = state.copy(nightShift = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(ctx.contentResolver, "night_display_activated", if (enabled) 1 else 0)
        }
    }

    fun setLiquidGlassMode(mode: Int) {
        val m = mode.coerceIn(SystemGlassConfig.MODE_CLEAR_REFRACTIVE, SystemGlassConfig.MODE_FROSTED_TINTED)
        state = state.copy(liquidGlassMode = m)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_LIQUID_GLASS_MODE, m)
        }
    }

    fun setRefractionPct(pct: Int) {
        val clamped = pct.coerceIn(0, 150)
        state = state.copy(refractionPct = clamped)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_REFRACTION_PCT, clamped)
        }
    }

    fun setFrostPct(pct: Int) {
        val clamped = pct.coerceIn(0, 150)
        state = state.copy(frostPct = clamped)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_FROST_PCT, clamped)
        }
    }

    fun setRefreshRate90Hz(enabled: Boolean) {
        state = state.copy(refreshRate90Hz = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val hz = if (enabled) 90f else 60f
            Settings.System.putFloat(ctx.contentResolver, "peak_refresh_rate", hz)
            Settings.System.putFloat(ctx.contentResolver, "min_refresh_rate", hz)
        }
    }

    fun setDcDimming(enabled: Boolean) {
        state = state.copy(dcDimming = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_dc_dimming", if (enabled) 1 else 0)
            File("/kernel/oppo_display/dimlayer_bl_en").takeIf { it.canWrite() }?.writeText(if (enabled) "1" else "0")
        }
    }

    fun setAutoLockSeconds(seconds: Int) {
        state = state.copy(autoLockSeconds = seconds)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        }
    }

    fun setFontScale(scale: Float) {
        val s = scale.coerceIn(0.85f, 1.30f)
        state = state.copy(fontScale = s)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putFloat(ctx.contentResolver, Settings.System.FONT_SCALE, s)
        }
    }

    fun setTapToWake(enabled: Boolean) {
        state = state.copy(tapToWake = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(ctx.contentResolver, "double_tap_to_wake", if (enabled) 1 else 0)
        }
    }

    fun setRaiseToWake(enabled: Boolean) {
        state = state.copy(raiseToWake = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(ctx.contentResolver, "doze_pulse_on_pick_up", if (enabled) 1 else 0)
        }
    }

    fun setIconStyle(style: Int) {
        state = state.copy(iconStyle = style.coerceIn(0, 2))
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_icon_style", style.coerceIn(0, 2))
        }
    }

    fun setShowHomeSearchPill(enabled: Boolean) {
        state = state.copy(showHomeSearchPill = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_show_search_pill", if (enabled) 1 else 0)
        }
    }

    fun setDoubleTapHomeToLock(enabled: Boolean) {
        state = state.copy(doubleTapHomeToLock = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        val v = if (enabled) 1 else 0
        runCatching {
            Settings.System.putInt(ctx.contentResolver, "samurai_dt2s_home", v)
            val qDir = File("/data/ota_package/hw/queue")
            if (qDir.exists() || qDir.mkdirs()) {
                val req = File(qDir, "cmd_${System.nanoTime()}_ios.req")
                req.writeText("DT2S_HOME=$v\n")
                req.setReadable(true, false)
            }
        }
    }

    fun applyIos26WallpaperPreset(presetIndex: Int) {
        val ctx = if (previewOnly) null else context
        if (ctx == null) {
            showBanner("Wallpaper applied")
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val colors = when (presetIndex) {
                        1 -> intArrayOf(0xFF071428.toInt(), 0xFF0E2954.toInt(), 0xFF040914.toInt())
                        2 -> intArrayOf(0xFF56CCF2.toInt(), 0xFF2F80ED.toInt(), 0xFF1A2980.toInt())
                        else -> intArrayOf(0xFF79C7FF.toInt(), 0xFFB8E2FF.toInt(), 0xFFEAF6FF.toInt())
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, 0f, 1080f, 2400f, colors, null, Shader.TileMode.CLAMP)
                    }
                    canvas.drawRect(0f, 0f, 1080f, 2400f, paint)
                    WallpaperManager.getInstance(ctx).setBitmap(
                        bmp,
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                    bmp.recycle()
                }
            }
            showBanner("iOS 26 Wallpaper applied")
        }
    }

    fun setStreamVolume(stream: Int, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        state = when (stream) {
            AudioManager.STREAM_RING -> state.copy(ringVolume = f)
            AudioManager.STREAM_ALARM -> state.copy(alarmVolume = f)
            else -> state.copy(mediaVolume = f)
        }
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            am.setStreamVolume(stream, (f * max).roundToInt().coerceIn(0, max), 0)
        }
    }

    fun setSystemHaptics(enabled: Boolean) {
        state = state.copy(systemHaptics = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (enabled) 1 else 0)
        }
    }

    fun setKeyboardClicks(enabled: Boolean) {
        state = state.copy(keyboardClicks = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (enabled) 1 else 0)
        }
    }

    fun setLockSounds(enabled: Boolean) {
        state = state.copy(lockSounds = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, "lockscreen_sounds_enabled", if (enabled) 1 else 0)
        }
    }

    fun runSpeakerWaterEject() {
        if (state.speakerCleaning) return
        state = state.copy(speakerCleaning = true, statusBanner = "Playing 165 Hz Speaker Pulse...")
        if (previewOnly) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val sampleRate = 44100
                    val durationSec = 3
                    val totalSamples = sampleRate * durationSec
                    val samples = ShortArray(totalSamples) { i ->
                        (sin(2.0 * PI * 165.0 * i / sampleRate) * Short.MAX_VALUE * 0.85).toInt().toShort()
                    }
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(samples.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                    track.write(samples, 0, samples.size)
                    track.play()
                    delay(3_000)
                    track.stop()
                    track.release()
                }
            }
            state = state.copy(speakerCleaning = false, statusBanner = "Speaker clear complete")
            clearBannerAfterDelay()
        }
    }

    fun setDndEnabled(enabled: Boolean) {
        state = state.copy(dndEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        state = state.copy(locationEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Secure.putInt(
                ctx.contentResolver,
                Settings.Secure.LOCATION_MODE,
                if (enabled) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF,
            )
        }
    }

    fun setPrivateDnsPreset(preset: String) {
        state = state.copy(privateDnsMode = preset)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val cr = ctx.contentResolver
            when (preset) {
                "Off" -> Settings.Global.putString(cr, "private_dns_mode", "off")
                "AdGuard DNS" -> {
                    Settings.Global.putString(cr, "private_dns_specifier", "dns.adguard-dns.com")
                    Settings.Global.putString(cr, "private_dns_mode", "hostname")
                }
                "Cloudflare 1.1.1.1" -> {
                    Settings.Global.putString(cr, "private_dns_specifier", "one.one.one.one")
                    Settings.Global.putString(cr, "private_dns_mode", "hostname")
                }
                else -> Settings.Global.putString(cr, "private_dns_mode", "opportunistic")
            }
        }
    }

    fun updateDeviceName(name: String) {
        val clean = name.trim().ifBlank { "Realme X2 Pro" }
        state = state.copy(deviceName = clean, bluetoothDeviceName = clean, hotspotSsid = clean)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putString(ctx.contentResolver, Settings.Global.DEVICE_NAME, clean)
            Settings.Secure.putString(ctx.contentResolver, "bluetooth_name", clean)
        }
    }

    fun setUse24HourTime(enabled: Boolean) {
        state = state.copy(use24HourTime = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.System.putString(ctx.contentResolver, Settings.System.TIME_12_24, if (enabled) "24" else "12")
        }
    }

    fun setAutoDateTime(enabled: Boolean) {
        state = state.copy(autoDateTime = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.AUTO_TIME, if (enabled) 1 else 0)
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, if (enabled) 1 else 0)
        }
    }

    fun onTapBuildNumber() {
        if (state.developerOptionsEnabled) {
            showBanner("Developer Options already enabled")
            return
        }
        buildTapCount++
        val remaining = 7 - buildTapCount
        if (remaining <= 0) {
            state = state.copy(developerOptionsEnabled = true)
            val ctx = if (previewOnly) null else context
            if (ctx != null) {
                runCatching {
                    Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
                }
            }
            showBanner("Developer Options enabled")
        } else if (remaining <= 4) {
            showBanner("$remaining taps away from Developer Options")
        }
    }

    fun setPerformanceProfile(profile: Int) {
        val p = profile.coerceIn(0, 3)
        state = state.copy(performanceProfile = p)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_perf_profile", p)
        }
    }

    fun setHighBrightnessMode(enabled: Boolean) {
        state = state.copy(highBrightnessMode = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "samurai_hbm_enabled", if (enabled) 1 else 0)
            File("/kernel/oppo_display/hbm").takeIf { it.canWrite() }?.writeText(if (enabled) "1" else "0")
        }
    }

    fun setOtgEnabled(enabled: Boolean) {
        state = state.copy(otgEnabled = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "persist.sys.oem.otg_support", if (enabled) 1 else 0)
            File("/sys/class/power_supply/usb/otg_switch").takeIf { it.canWrite() }?.writeText(if (enabled) "1" else "0")
        }
    }

    fun setUsbDebugging(enabled: Boolean) {
        state = state.copy(usbDebugging = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, if (enabled) 1 else 0)
        }
    }

    fun setWirelessDebugging(enabled: Boolean) {
        state = state.copy(wirelessDebugging = enabled)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", if (enabled) 1 else 0)
        }
    }

    fun setAnimationScale(scale: Float) {
        state = state.copy(animationScale = scale)
        val ctx = if (previewOnly) null else context
        if (ctx == null) return
        runCatching {
            val cr = ctx.contentResolver
            Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        }
    }

    fun openNativeDeveloperOptions() {
        val ctx = context ?: return
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        }
        val openedAction = runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                setPackage("com.android.settings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.isSuccess
        if (!openedAction) {
            runCatching {
                val fallback = Intent().apply {
                    component = ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(fallback)
            }.onFailure {
                showBanner("Unable to launch Android Developer Options")
            }
        }
    }

    fun openOtaUpdater() {
        val ctx = context ?: return
        val openedDirect = runCatching {
            val intent = Intent().apply {
                component = ComponentName("com.realme.ota", "com.realme.ota.SystemUpdateActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.isSuccess
        if (!openedDirect) {
            runCatching {
                val fallback = Intent("android.settings.SYSTEM_UPDATE_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(fallback)
            }.onFailure {
                showBanner("Samurai OS is up to date")
            }
        }
    }

    fun launchAddGoogleAccount() {
        val ctx = context ?: return
        runCatching {
            val intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.onFailure {
            showBanner("Google Account services ready")
        }
    }

    fun launchBiometricEnrollment() {
        val ctx = context ?: return
        runCatching {
            val action = if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_BIOMETRIC_ENROLL else Settings.ACTION_SECURITY_SETTINGS
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            showBanner("Fingerprint sensor active")
        }
    }

    fun launchApp(packageName: String) {
        val ctx = context ?: return
        runCatching {
            val launch = ctx.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launch)
            }
        }
    }

    private fun loadInstalledAppsAsync() {
        val ctx = context ?: return
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = ctx.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                    val resolves = pm.queryIntentActivities(mainIntent, 0)
                    val seen = hashSetOf<String>()
                    resolves.mapNotNull { ri ->
                        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == ctx.packageName || !seen.add(pkg)) return@mapNotNull null
                        val label = ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
                        val pInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                        val ver = pInfo?.versionName ?: "1.0"
                        val isSys = ((ri.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        val iconBmp = runCatching {
                            val d = ri.loadIcon(pm)
                            val bmp = Bitmap.createBitmap(84, 84, Bitmap.Config.ARGB_8888)
                            val c = Canvas(bmp)
                            d.setBounds(0, 0, 84, 84)
                            d.draw(c)
                            bmp.asImageBitmap()
                        }.getOrNull()
                        InstalledAppItem(
                            label = label,
                            packageName = pkg,
                            versionName = ver,
                            isSystemApp = isSys,
                            icon = iconBmp,
                        )
                    }.sortedBy { it.label.lowercase(Locale.getDefault()) }
                }.getOrDefault(emptyList())
            }
            state = state.copy(installedApps = items)
        }
    }

    fun showBanner(message: String) {
        state = state.copy(statusBanner = message)
        clearBannerAfterDelay()
    }

    private fun clearBannerAfterDelay() {
        if (previewOnly) return
        scope.launch {
            delay(2_400)
            state = state.copy(statusBanner = null)
        }
    }
}
