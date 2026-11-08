package com.realme.samurai.parts

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Display
import android.view.Surface
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class SamuraiMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    @Volatile private var screenInteractive = true
    private var lastLockscreen = false
    private var lastHomeIdle = false
    private var lastTpDir = -1
    private var lastAutoModePkg = ""
    private var activeTempOverride: Int = -1 // -1=none, 0=gaming90, 2=video60
    private var autoHbmEngaged = false
    private var lightSensorRegistered = false
    private var uidListenerRegistered = false
    private var uidImportanceProxy: Any? = null
    private val singlePkgUidCache = ConcurrentHashMap<Int, String>()

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val p = SamuraiHardwareBridge.prefs(this@SamuraiMonitorService)
            if (p.getBoolean(SamuraiHardwareBridge.KEY_HBM, false) || autoHbmEngaged) {
                autoHbmEngaged = false
                p.edit().putBoolean(SamuraiHardwareBridge.KEY_HBM, false).apply()
                SamuraiHardwareBridge.sendCommand("HBM", false)
                try {
                    TileService.requestListeningState(
                        this@SamuraiMonitorService,
                        ComponentName(this@SamuraiMonitorService, HbmTileService::class.java)
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val lux = event?.values?.firstOrNull() ?: return
            val p = SamuraiHardwareBridge.prefs(this@SamuraiMonitorService)
            if (!p.getBoolean(SamuraiHardwareBridge.KEY_AUTO_HBM, false)) return
            if (p.getBoolean(SamuraiHardwareBridge.KEY_HBM, false)) return // Manual HBM takes priority
            if (!autoHbmEngaged && lux >= 12000f) {
                autoHbmEngaged = true
                SamuraiHardwareBridge.sendCommand("HBM", true)
            } else if (autoHbmEngaged && lux <= 6000f) {
                autoHbmEngaged = false
                SamuraiHardwareBridge.sendCommand("HBM", false)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val videoPackages = setOf(
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "in.startv.hotstar",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
    )

    private val gamePackages = setOf(
        "com.pubg.imobile",
        "com.tencent.ig",
        "com.activision.callofduty.shooter",
        "com.miHoYo.GenshinImpact",
        "com.HoYoverse.hkrpgoversea",
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.supercell.brawlstars",
        "com.mobile.legends",
        "com.roblox.client",
    )

    private val launcherPackages = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
    )

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                syncRotationAndLockState()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    SamuraiHardwareBridge.sendCommand("INTERACTIVE", true)
                    updateLightSensorSubscription(true)
                    updateUidListenerSubscription(true)
                    syncRotationAndLockState()
                    scheduleOneShotForegroundSync(150L)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    lastHomeIdle = false
                    updateLightSensorSubscription(false)
                    updateUidListenerSubscription(false)
                    val p = SamuraiHardwareBridge.prefs(this@SamuraiMonitorService)
                    if (autoHbmEngaged || p.getBoolean(SamuraiHardwareBridge.KEY_HBM, false)) {
                        autoHbmEngaged = false
                        p.edit().putBoolean(SamuraiHardwareBridge.KEY_HBM, false).apply()
                        SamuraiHardwareBridge.sendCommand("HBM", false)
                        try {
                            TileService.requestListeningState(
                                this@SamuraiMonitorService,
                                ComponentName(this@SamuraiMonitorService, HbmTileService::class.java)
                            )
                        } catch (_: Throwable) {
                        }
                    }
                    SamuraiHardwareBridge.sendCommands(
                        listOf("INTERACTIVE=0", "HOME_IDLE=0", "UDFPS_RESET=1")
                    )
                    bgHandler?.removeCallbacks(oneShotSyncRunnable)
                }
                Intent.ACTION_USER_PRESENT -> {
                    updateUidListenerSubscription(true)
                    syncRotationAndLockState()
                    scheduleOneShotForegroundSync(120L)
                }
            }
        }
    }

    private val oneShotSyncRunnable = Runnable {
        if (screenInteractive) {
            syncForegroundStateOneShot()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val ht = HandlerThread("SamuraiMonitorBg", Process.THREAD_PRIORITY_BACKGROUND)
        ht.start()
        bgThread = ht
        bgHandler = Handler(ht.looper)

        val pm = getSystemService(PowerManager::class.java)
        screenInteractive = pm?.isInteractive ?: true

        val dm = getSystemService(DisplayManager::class.java)
        dm?.registerDisplayListener(displayListener, handler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        try {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor("screen_brightness_float"),
                false,
                brightnessObserver
            )
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                brightnessObserver
            )
        } catch (_: Throwable) {
        }

        SamuraiHardwareBridge.syncAllSavedPreferences(this)
        activeTempOverride = -1
        SamuraiAudioEngine.initialize(this)
        syncRotationAndLockState()
        if (screenInteractive) {
            updateLightSensorSubscription(true)
            updateUidListenerSubscription(true)
            scheduleOneShotForegroundSync(250L)
        }
    }

    private fun updateUidListenerSubscription(enable: Boolean) {
        val am = getSystemService(ActivityManager::class.java) ?: return
        if (enable && !uidListenerRegistered) {
            try {
                val listenerCls = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val proxy = uidImportanceProxy ?: Proxy.newProxyInstance(
                    listenerCls.classLoader,
                    arrayOf(listenerCls)
                ) { p, method, args ->
                    when (method.name) {
                        "equals" -> p === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(p)
                        "toString" -> "SamuraiUidImportanceListener"
                        "onUidImportance" -> {
                            val uid = (args?.getOrNull(0) as? Int) ?: -1
                            val importance = (args?.getOrNull(1) as? Int) ?: Int.MAX_VALUE
                            if (screenInteractive && uid > 0 &&
                                importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                            ) {
                                bgHandler?.post { onUidBecameForeground(uid) }
                            }
                            null
                        }
                        else -> null
                    }
                }.also { uidImportanceProxy = it }

                am.javaClass
                    .getMethod("addOnUidImportanceListener", listenerCls, Int::class.javaPrimitiveType)
                    .invoke(am, proxy, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                uidListenerRegistered = true
            } catch (_: Throwable) {
            }
        } else if (!enable && uidListenerRegistered) {
            try {
                val listenerCls = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val proxy = uidImportanceProxy
                if (proxy != null) {
                    am.javaClass
                        .getMethod("removeOnUidImportanceListener", listenerCls)
                        .invoke(am, proxy)
                }
            } catch (_: Throwable) {
            }
            uidListenerRegistered = false
        }
    }

    private fun updateLightSensorSubscription(enable: Boolean) {
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (enable && !lightSensorRegistered) {
            val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (light != null) {
                sm.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL)
                lightSensorRegistered = true
            }
        } else if (!enable && lightSensorRegistered) {
            sm.unregisterListener(lightListener)
            lightSensorRegistered = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncRotationAndLockState()
        if (screenInteractive) {
            updateUidListenerSubscription(true)
            scheduleOneShotForegroundSync(150L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        updateUidListenerSubscription(false)
        bgHandler?.removeCallbacks(oneShotSyncRunnable)
        bgThread?.quitSafely()
        bgHandler = null
        bgThread = null
        val p = SamuraiHardwareBridge.prefs(this)
        if (activeTempOverride != -1 || p.getInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1) != -1) {
            activeTempOverride = -1
            p.edit().putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1).apply()
            SamuraiHardwareBridge.applyRefreshRateMode(
                this,
                p.getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0)
            )
        }
        try {
            contentResolver.unregisterContentObserver(brightnessObserver)
        } catch (_: Throwable) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        try {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleOneShotForegroundSync(delayMs: Long) {
        val h = bgHandler ?: return
        h.removeCallbacks(oneShotSyncRunnable)
        h.postDelayed(oneShotSyncRunnable, delayMs)
    }

    private fun syncRotationAndLockState() {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val rot = display?.rotation ?: Surface.ROTATION_0
        val tpDir = when (rot) {
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_270 -> 2
            else -> 0
        }
        if (tpDir != lastTpDir) {
            lastTpDir = tpDir
            SamuraiHardwareBridge.sendCommand("TP_DIR", tpDir)
        }

        val km = getSystemService(KeyguardManager::class.java)
        val locked = km?.isKeyguardLocked ?: false
        if (locked != lastLockscreen) {
            lastLockscreen = locked
            SamuraiHardwareBridge.sendCommand("LOCKSCREEN", locked)
        }
    }

    private fun onUidBecameForeground(uid: Int) {
        if (!screenInteractive) return
        var pkg = singlePkgUidCache[uid]
        if (pkg == null && uid >= Process.FIRST_APPLICATION_UID) {
            val pkgs = try {
                packageManager.getPackagesForUid(uid)
            } catch (_: Throwable) {
                null
            }
            if (pkgs != null && pkgs.size == 1 && !pkgs[0].isNullOrEmpty()) {
                pkg = pkgs[0]
                singlePkgUidCache[uid] = pkg
            }
        }
        if (!pkg.isNullOrEmpty()) {
            // SystemUI shade/biometric/volume overlays also run at IMPORTANCE_FOREGROUND; ignore SystemUI
            if (pkg == "com.android.systemui") return
            handleForegroundPackage(pkg)
        } else {
            syncForegroundStateOneShot()
        }
    }

    @Suppress("DEPRECATION")
    private fun syncForegroundStateOneShot() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        val topComp = try {
            am.getRunningTasks(1)?.firstOrNull()?.topActivity
        } catch (_: Throwable) {
            null
        }
        val topPkg = topComp?.packageName ?: return
        handleForegroundPackage(topPkg)
    }

    private fun handleForegroundPackage(topPkg: String) {
        val km = getSystemService(KeyguardManager::class.java)
        val locked = km?.isKeyguardLocked ?: false
        if (locked != lastLockscreen) {
            lastLockscreen = locked
            SamuraiHardwareBridge.sendCommand("LOCKSCREEN", locked)
        }

        val isHome = !locked && topPkg in launcherPackages
        if (isHome != lastHomeIdle) {
            lastHomeIdle = isHome
            SamuraiHardwareBridge.sendCommand("HOME_IDLE", isHome)
        }

        if (topPkg.isNotEmpty() && topPkg != lastAutoModePkg) {
            lastAutoModePkg = topPkg
            applyPerAppIntelligence(topPkg)
        }
    }

    private fun applyPerAppIntelligence(pkg: String) {
        val p = SamuraiHardwareBridge.prefs(this)
        val autoVideo = p.getBoolean(SamuraiHardwareBridge.KEY_VIDEO_60HZ_AUTO, false)
        val autoGame = p.getBoolean(SamuraiHardwareBridge.KEY_GAMING_AUTO, true)
        val baseRefresh = p.getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0)
        val basePerf = p.getInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, 0)
        val baseGameTouch = p.getBoolean(SamuraiHardwareBridge.KEY_TP_GAME, false)
        val persistedTemp = p.getInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1)

        when {
            autoGame && pkg in gamePackages -> {
                activeTempOverride = 0
                p.edit().putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, 0).apply()
                SamuraiHardwareBridge.applyRefreshRateMode(this, 0) // Smooth Locked 90Hz
                SamuraiHardwareBridge.sendCommands(listOf("PERF=1", "TP_GAME=1"))
            }
            autoVideo && pkg in videoPackages -> {
                activeTempOverride = 2
                p.edit().putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, 2).apply()
                SamuraiHardwareBridge.applyRefreshRateMode(this, 2) // 60Hz exact 24/30/60 cinema cadence
                SamuraiHardwareBridge.sendCommands(
                    listOf("PERF=$basePerf", "TP_GAME=${if (baseGameTouch) 1 else 0}")
                )
            }
            activeTempOverride != -1 || persistedTemp != -1 -> {
                activeTempOverride = -1
                p.edit().putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1).apply()
                SamuraiHardwareBridge.applyRefreshRateMode(this, baseRefresh)
                SamuraiHardwareBridge.sendCommands(
                    listOf("PERF=$basePerf", "TP_GAME=${if (baseGameTouch) 1 else 0}")
                )
            }
        }
    }
}
