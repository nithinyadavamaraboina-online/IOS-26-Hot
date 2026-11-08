package com.realme.samurai.parts

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.realme.samurai.glass.LiquidGlassLabActivity
import com.realme.samurai.glass.SamuraiGlassPrefs
import com.realme.samurai.glass.SamuraiWallpaperGenerator
import java.util.Locale

class SamuraiPartsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var telemetrySubtitle: TextView? = null
    private var telemetryDetail: TextView? = null
    private var clearSpeakerBtn: TextView? = null
    private var adblockModsView: TextView? = null
    private var adblockProgressView: TextView? = null
    private var cromiteChip: TextView? = null
    private var otaChannelStatusView: TextView? = null
    private var searchPillSwitch: Switch? = null
    private var gesturePillSwitch: Switch? = null
    private var syncingGlassSwitches = false

    private fun readSystemProp(key: String, fallback: String = ""): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, fallback) as? String)?.trim() ?: fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun isIosBetaInstalled(): Boolean =
        readSystemProp("ro.ota.channel", "stable").equals("ios_beta", ignoreCase = true)

    private fun refreshOtaChannelSection() {
        val installedBeta = isIosBetaInstalled()
        val optIn = try {
            Settings.Global.getInt(
                contentResolver,
                "samurai_ios_style_ui_beta",
                if (installedBeta) 1 else 0
            ) == 1
        } catch (_: Throwable) {
            installedBeta
        }
        otaChannelStatusView?.text = when {
            installedBeta && optIn ->
                "Active channel: iOS Style UI Beta • Receiving active Liquid Glass UI, Notification Shade & Lock Screen beta updates."
            installedBeta && !optIn ->
                "Installed: iOS Style UI Beta (Opted out) • Open System Update to download Clean Stable PixelOS."
            !installedBeta && optIn ->
                "Installed: Clean PixelOS Stable (Beta opted in) • Open System Update to install iOS Style UI Beta."
            else ->
                "Active channel: Clean PixelOS Stable • Updates paused to verified core stability fixes. Toggle 'iOS Style UI Beta' in System Update anytime to try experimental iOS 26 UI."
        }
    }

    private var isDark = false
    private var bgSurface = 0
    private var cardSurface = 0
    private var chipInactiveBg = 0
    private var chipActiveBg = 0
    private var chipActiveText = 0
    private var textPrimary = 0
    private var textSecondary = 0
    private var accentColor = 0

    private val telemetryTick = object : Runnable {
        override fun run() {
            SamuraiHardwareBridge.requestTelemetryWindow(15)
            refreshTelemetryHeader()
            refreshAdblockSection()
            handler.postDelayed(this, 1800L)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveMaterialYouPalette()
        window.setDecorFitsSystemWindows(false)

        try {
            startService(Intent(this, SamuraiMonitorService::class.java))
        } catch (_: Throwable) {
        }

        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        syncingGlassSwitches = true
        try {
            searchPillSwitch?.let { sw ->
                val want = SamuraiGlassPrefs.isSearchPillEnabled(this)
                if (sw.isChecked != want) sw.isChecked = want
            }
            gesturePillSwitch?.let { sw ->
                val want = SamuraiGlassPrefs.isGesturePillEnabled(this)
                if (sw.isChecked != want) sw.isChecked = want
            }
        } finally {
            syncingGlassSwitches = false
        }
        refreshOtaChannelSection()
        handler.post(telemetryTick)
    }

    override fun onPause() {
        handler.removeCallbacks(telemetryTick)
        super.onPause()
    }

    private fun resolveMaterialYouPalette() {
        val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDark = nightFlags == Configuration.UI_MODE_NIGHT_YES

        bgSurface = getColor(
            if (isDark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50
        )
        cardSurface = getColor(
            if (isDark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_10
        )
        chipInactiveBg = getColor(
            if (isDark) android.R.color.system_neutral2_800 else android.R.color.system_neutral2_100
        )
        chipActiveBg = getColor(
            if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        )
        chipActiveText = getColor(
            if (isDark) android.R.color.system_accent1_900 else android.R.color.system_neutral1_0
        )
        textPrimary = getColor(
            if (isDark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900
        )
        textSecondary = getColor(
            if (isDark) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_600
        )
        accentColor = getColor(
            if (isDark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, radiusDp: Float, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1).coerceAtLeast(1), strokeColor)
            }
        }
    }

    private fun buildContentView(): View {
        val prefs = SamuraiHardwareBridge.prefs(this)

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(bgSurface)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(52), dp(20), dp(40))
        }

        rootScroll.setOnApplyWindowInsetsListener { _, insets ->
            val sysBars = insets.getInsets(WindowInsets.Type.systemBars())
            container.setPadding(
                dp(20),
                sysBars.top + dp(18),
                dp(20),
                sysBars.bottom + dp(28)
            )
            insets
        }

        // Top Navigation Back + Large Expressive Header
        val backRow = TextView(this).apply {
            text = "← Settings"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(4), dp(12), dp(12))
            setOnClickListener { finish() }
        }
        container.addView(backRow)

        val pageTitle = TextView(this).apply {
            text = "Realme X2 Pro hardware"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(pageTitle)

        val pageSubtitle = TextView(this).apply {
            text = "Snapdragon 855+ • 90Hz Samsung AMOLED • 50W SuperVOOC"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(18))
        }
        container.addView(pageSubtitle)

        // Live Hardware Status Hero Card
        val heroCard = makeCard()
        val heroBadge = TextView(this).apply {
            text = "LIVE HARDWARE TELEMETRY"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        heroCard.addView(heroBadge)

        telemetrySubtitle = TextView(this).apply {
            text = "Reading panel refresh rate, Kryo 485 Prime & SuperVOOC sensors…"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(4))
        }
        heroCard.addView(telemetrySubtitle)

        telemetryDetail = TextView(this).apply {
            text = "Tap to Wake & Tap to Close active"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        heroCard.addView(telemetryDetail)
        container.addView(heroCard, cardLayoutParams())

        val iosBetaActive = isIosBetaInstalled()

        // =========================================================================
        // SECTION -2: SYSTEM UPDATES & iOS STYLE UI BETA CHANNEL SHORTCUT
        // =========================================================================
        container.addView(makeSectionHeader("System Updates & iOS Style UI Beta"))
        val otaCard = makeCard()
        val otaCardTitle = TextView(this).apply {
            text = if (iosBetaActive) "iOS Style UI Beta (Active)" else "Clean PixelOS Stable & Optional iOS UI Beta"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        otaCard.addView(otaCardTitle)

        otaChannelStatusView = TextView(this).apply {
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(6), 0, dp(10))
        }
        otaCard.addView(otaChannelStatusView)
        refreshOtaChannelSection()

        val openSystemUpdateBtn = TextView(this).apply {
            text = "Open System Update"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = roundedBg(chipActiveBg, 20f)
            setTextColor(chipActiveText)
            setOnClickListener {
                try {
                    startActivity(
                        Intent().setClassName("com.realme.ota", "com.realme.ota.SystemUpdateActivity")
                    )
                } catch (_: Throwable) {
                }
            }
        }
        otaCard.addView(
            openSystemUpdateBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(otaCard, cardLayoutParams())

        // =========================================================================
        // SECTION -1: iOS 26 LIQUID GLASS DOCK, UNIVERSAL ICONS & WALLPAPERS (iOS Beta Only)
        // =========================================================================
        if (iosBetaActive) {
        container.addView(makeSectionHeader("iOS 26 Liquid Glass & Home Dock"))
        val glassCard = makeCard()
        glassCard.addView(
            makeChipSelectorRow(
                title = "Liquid Glass dock & pill material",
                summary = "Clear toroidal Snell refraction (iOS 26.0) or higher-contrast frosted glass (iOS 26.1) — zero global SurfaceFlinger blur",
                options = listOf("Clear (iOS 26.0)" to SamuraiGlassPrefs.MODE_CLEAR, "Tinted (iOS 26.1)" to SamuraiGlassPrefs.MODE_TINTED),
                selectedValue = SamuraiGlassPrefs.getMode(this)
            ) { mode ->
                SamuraiGlassPrefs.setMode(this, mode)
                SamuraiGlassPrefs.performCrispTick(glassCard, strong = true)
            }
        )
        glassCard.addView(makeDivider())

        glassCard.addView(
            makeChipSelectorRow(
                title = "iOS 26 universal icon appearance",
                summary = "Synthesize multi-plane G2 squircle icons across all Google, system & Play Store apps with live Launcher reload",
                options = listOf(
                    "Auto (Day/Night)" to SamuraiGlassPrefs.ICON_MODE_AUTO,
                    "Default" to SamuraiGlassPrefs.ICON_MODE_DEFAULT,
                    "Dark" to SamuraiGlassPrefs.ICON_MODE_DARK,
                    "Clear Glass" to SamuraiGlassPrefs.ICON_MODE_CLEAR
                ),
                selectedValue = SamuraiGlassPrefs.getIconMode(this)
            ) { iconMode ->
                SamuraiGlassPrefs.setIconMode(this, iconMode)
                SamuraiGlassPrefs.performCrispTick(glassCard, strong = true)
            }
        )
        glassCard.addView(makeDivider())

        glassCard.addView(
            makeSwitchRow(
                title = "Home screen floating 'Q Search' dewdrop",
                summary = "Show compact iOS 26 water-droplet Search pill above the Liquid Glass dock (replaces bottom Google QSB bar)",
                checked = SamuraiGlassPrefs.isSearchPillEnabled(this),
                onSwitchCreated = { searchPillSwitch = it }
            ) { enabled ->
                SamuraiGlassPrefs.setSearchPillEnabled(this, enabled)
                SamuraiGlassPrefs.performCrispTick(glassCard, strong = false)
            }
        )
        glassCard.addView(makeDivider())

        glassCard.addView(
            makeSwitchRow(
                title = "Show Bottom Navigation Bar",
                summary = "Display bottom gesture indicator pill across apps and Home Screen (hidden by default)",
                checked = SamuraiGlassPrefs.isGesturePillEnabled(this),
                onSwitchCreated = { gesturePillSwitch = it }
            ) { isChecked ->
                SamuraiGlassPrefs.setGesturePillEnabled(this, isChecked)
                SamuraiGlassPrefs.performCrispTick(glassCard, strong = false)
            }
        )
        glassCard.addView(makeDivider())

        val wpTitle = TextView(this).apply {
            text = "Signature iOS 26 procedural wallpapers"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(2))
        }
        glassCard.addView(wpTitle)

        val wpSummary = TextView(this).apply {
            text = "1080×2400 overlapping cyan, electric-cerulean & royal-sapphire glass petals calibrated for dock lip refraction"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(10))
        }
        glassCard.addView(wpSummary)

        val wpBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val wpDayBtn = TextView(this).apply {
            text = "Apply Cyan/Cobalt Petals"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = roundedBg(chipActiveBg, 18f)
            setTextColor(chipActiveText)
            setOnClickListener {
                SamuraiGlassPrefs.performCrispTick(this, strong = true)
                SamuraiWallpaperGenerator.applyWallpaperAsync(this@SamuraiPartsActivity, darkVariant = false)
            }
        }
        val wpDarkBtn = TextView(this).apply {
            text = "Apply Midnight Cobalt"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = roundedBg(chipInactiveBg, 18f)
            setTextColor(textPrimary)
            setOnClickListener {
                SamuraiGlassPrefs.performCrispTick(this, strong = true)
                SamuraiWallpaperGenerator.applyWallpaperAsync(this@SamuraiPartsActivity, darkVariant = true)
            }
        }
        wpBtnRow.addView(
            wpDayBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        )
        wpBtnRow.addView(
            wpDarkBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        glassCard.addView(wpBtnRow)
        glassCard.addView(makeDivider())

        val openStudioBtn = TextView(this).apply {
            text = "Open Interactive Liquid Glass Preview"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = roundedBg(chipActiveBg, 20f)
            setTextColor(chipActiveText)
            setOnClickListener {
                SamuraiGlassPrefs.performCrispTick(this, strong = false)
                startActivity(Intent(this@SamuraiPartsActivity, LiquidGlassLabActivity::class.java))
            }
        }
        glassCard.addView(
            openStudioBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        container.addView(glassCard, cardLayoutParams())
        } // end if (iosBetaActive)

        // =========================================================================
        // SECTION 0: PIXEL PHOTOS BACKUP & OPTIONAL CROMITE BROWSER
        // =========================================================================
        container.addView(makeSectionHeader("Pixel Backup & Optional Browser"))
        val adCard = makeCard()

        val backupTitle = TextView(this).apply {
            text = "Original Quality Google Photos Backup"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        adCard.addView(backupTitle)

        adblockModsView = TextView(this).apply {
            text = "Checking Google Photos Pixel backup & Cromite status…"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.22f)
            setPadding(0, dp(6), 0, dp(8))
        }
        adCard.addView(adblockModsView)

        adblockProgressView = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(8))
        }
        adCard.addView(adblockProgressView)

        fun makeActionPill(label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = roundedBg(if (primary) chipActiveBg else chipInactiveBg, 18f)
                setTextColor(if (primary) chipActiveText else textPrimary)
                setOnClickListener { onClick() }
            }
        }

        val crChip = makeActionPill("Install Cromite Browser", primary = true) {
            SamuraiAdblockModManager.installOrOpenCromite(this)
            refreshAdblockSection()
        }
        cromiteChip = crChip
        adCard.addView(
            crChip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        container.addView(adCard, cardLayoutParams())

        // =========================================================================
        // SECTION 1: TAP TO WAKE & TAP TO CLOSE (GESTURES)
        // =========================================================================
        container.addView(makeSectionHeader("Tap to wake & tap to close"))
        val gestureCard = makeCard()

        gestureCard.addView(
            makeSwitchRow(
                title = "Tap to wake",
                summary = "Double-tap the turned-off screen to wake Realme X2 Pro immediately",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_DT2W, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_DT2W, enabled).apply()
                SamuraiHardwareBridge.syncTapToWakeSettings(this, enabled)
            }
        )
        gestureCard.addView(makeDivider())

        gestureCard.addView(
            makeSwitchRow(
                title = "Tap to close (Status bar & Lockscreen)",
                summary = "Double-tap the status bar in any app or empty lockscreen space to sleep (keeps fingerprint unlock active)",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_DT2S_STATUSBAR, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_DT2S_STATUSBAR, enabled).apply()
                SamuraiHardwareBridge.sendCommand("DT2S", enabled)
            }
        )
        gestureCard.addView(makeDivider())

        gestureCard.addView(
            makeSwitchRow(
                title = "Tap to close on Home screen",
                summary = "Double-tap empty wallpaper on Home screen to turn off the display",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_DT2S_HOME, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_DT2S_HOME, enabled).apply()
                SamuraiHardwareBridge.syncHomeDoubleTapSettings(this, enabled)
            }
        )
        gestureCard.addView(makeDivider())

        gestureCard.addView(
            makeSwitchRow(
                title = "Draw V for flashlight",
                summary = "Draw a V on the off screen to toggle the dual-LED rear torch",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_GESTURE_TORCH_V, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_GESTURE_TORCH_V, enabled).apply()
                SamuraiHardwareBridge.sendCommand("GESTURE_TORCH_V", enabled)
            }
        )
        gestureCard.addView(makeDivider())

        gestureCard.addView(
            makeSwitchRow(
                title = "Draw O for camera",
                summary = "Draw an O on the off screen to wake and launch Camera",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_GESTURE_CAMERA_O, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_GESTURE_CAMERA_O, enabled).apply()
                SamuraiHardwareBridge.sendCommand("GESTURE_CAMERA_O", enabled)
            }
        )
        gestureCard.addView(makeDivider())

        gestureCard.addView(
            makeSwitchRow(
                title = "Off-screen music gestures",
                summary = "Two-finger swipe (||) to play/pause, < or > to skip tracks while screen is off",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_GESTURE_MUSIC, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_GESTURE_MUSIC, enabled).apply()
                SamuraiHardwareBridge.sendCommand("GESTURE_MUSIC", enabled)
            }
        )
        if (!iosBetaActive) {
            gestureCard.addView(makeDivider())
            gestureCard.addView(
                makeSwitchRow(
                    title = "Show Bottom Navigation Bar",
                    summary = "Display the Pixel bottom gesture indicator bar across apps and Home Screen",
                    checked = SamuraiGlassPrefs.isGesturePillEnabled(this),
                    onSwitchCreated = { gesturePillSwitch = it }
                ) { isChecked ->
                    SamuraiGlassPrefs.setGesturePillEnabled(this, isChecked)
                    SamuraiGlassPrefs.performCrispTick(gestureCard, strong = false)
                }
            )
        }
        container.addView(gestureCard, cardLayoutParams())

        // =========================================================================
        // SECTION 2: SAMSUNG SOFEF03F-M 90HZ AMOLED DISPLAY
        // =========================================================================
        container.addView(makeSectionHeader("90Hz Samsung AMOLED display"))
        val displayCard = makeCard()

        displayCard.addView(
            makeChipSelectorRow(
                title = "Refresh rate mode",
                summary = "Hardware-calibrated 90Hz fluid motion or 60Hz battery endurance",
                options = listOf("Smooth 90Hz" to 0, "Adaptive 90Hz" to 1, "60Hz Saver" to 2),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0)
            ) { mode ->
                prefs.edit()
                    .putInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, mode)
                    .putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1)
                    .apply()
                SamuraiHardwareBridge.applyRefreshRateMode(this, mode)
            }
        )
        displayCard.addView(makeDivider())

        displayCard.addView(
            makeSwitchRow(
                title = "Smart 60Hz cinema cadence",
                summary = "Match 24/30/60fps movie playback in Netflix, Prime Video & VLC (keeps YouTube feed & Shorts at 90Hz)",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_VIDEO_60HZ_AUTO, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_VIDEO_60HZ_AUTO, enabled).apply()
                if (!enabled) {
                    prefs.edit().putInt(SamuraiHardwareBridge.KEY_TEMP_REFRESH_OVERRIDE, -1).apply()
                    SamuraiHardwareBridge.applyRefreshRateMode(
                        this,
                        prefs.getInt(SamuraiHardwareBridge.KEY_REFRESH_MODE, 0)
                    )
                }
            }
        )
        displayCard.addView(makeDivider())

        displayCard.addView(
            makeSwitchRow(
                title = "One-Pulse anti-flicker DC dimming",
                summary = "Eliminate low-brightness OLED PWM flicker at night using hardware dimlayer blending",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_DC_DIM, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_DC_DIM, enabled).apply()
                SamuraiHardwareBridge.sendCommand("DC_DIM", enabled)
            }
        )
        displayCard.addView(makeDivider())

        displayCard.addView(
            makeSwitchRow(
                title = "Sunlight High Brightness Mode (1000 nits)",
                summary = "Temporary peak outdoor boost (moving the brightness slider returns to manual control)",
                checked = SamuraiHardwareBridge.isHbmHardwareActive(this)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_HBM, enabled).apply()
                SamuraiHardwareBridge.sendCommand("HBM", enabled)
            }
        )
        displayCard.addView(makeDivider())

        displayCard.addView(
            makeSwitchRow(
                title = "Auto outdoor Sunlight HBM (> 12,000 lux)",
                summary = "Engage 1000-nit HBM automatically in direct sunlight and restore indoors",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_AUTO_HBM, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_AUTO_HBM, enabled).apply()
            }
        )
        displayCard.addView(makeDivider())

        displayCard.addView(
            makeChipSelectorRow(
                title = "AMOLED color gamut (SEED)",
                summary = "Hardware panel color Lut calibration",
                options = listOf("DCI-P3 Vivid" to 2, "sRGB Natural" to 1, "Panel Wide" to 0),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_SEED, 2)
            ) { seed ->
                prefs.edit().putInt(SamuraiHardwareBridge.KEY_SEED, seed).apply()
                SamuraiHardwareBridge.sendCommand("SEED", seed)
            }
        )
        container.addView(displayCard, cardLayoutParams())

        // =========================================================================
        // SECTION 3: SNAPDRAGON 855+ PERFORMANCE & GAMING TOUCH
        // =========================================================================
        container.addView(makeSectionHeader("Snapdragon 855+ & Synaptics touch"))
        val perfCard = makeCard()

        perfCard.addView(
            makeChipSelectorRow(
                title = "Thermal & CPU/GPU profile",
                summary = "Kryo 485 Prime (2.96 GHz) & Adreno 640 (675 MHz) scheduler policy",
                options = listOf("Balanced 855+" to 0, "Gaming 2.96GHz" to 1, "Cool Efficiency" to 2),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, 0)
            ) { perf ->
                prefs.edit().putInt(SamuraiHardwareBridge.KEY_PERF_PROFILE, perf).apply()
                SamuraiHardwareBridge.sendCommand("PERF", perf)
            }
        )
        perfCard.addView(makeDivider())

        perfCard.addView(
            makeSwitchRow(
                title = "Automatic game boost",
                summary = "Engage 90Hz, 2.96 GHz Prime boost & high touch sampling automatically in games",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_GAMING_AUTO, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_GAMING_AUTO, enabled).apply()
            }
        )
        perfCard.addView(makeDivider())

        perfCard.addView(
            makeSwitchRow(
                title = "High-rate gaming touch sampling",
                summary = "Enable Synaptics S3706 game_switch_enable low-latency touch filter",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_TP_GAME, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_TP_GAME, enabled).apply()
                SamuraiHardwareBridge.sendCommand("TP_GAME", enabled)
            }
        )
        perfCard.addView(makeDivider())

        perfCard.addView(
            makeSwitchRow(
                title = "Edge grip palm rejection",
                summary = "Prevent accidental palm touches along screen borders",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_TP_LIMIT, true)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_TP_LIMIT, enabled).apply()
                SamuraiHardwareBridge.sendCommand("TP_LIMIT", enabled)
            }
        )
        perfCard.addView(makeDivider())

        perfCard.addView(
            makeSwitchRow(
                title = "Floating FPS & VOOC HUD pill",
                summary = "Show compact real-time FPS, Prime/GPU clock, wattage & temperature overlay",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_HUD_ENABLED, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_HUD_ENABLED, enabled).apply()
                val svc = Intent(this, SamuraiHudService::class.java)
                if (enabled) startService(svc) else stopService(svc)
            }
        )
        container.addView(perfCard, cardLayoutParams())

        // =========================================================================
        // SECTION 4: 50W SUPERVOOC BATTERY & USB OTG
        // =========================================================================
        container.addView(makeSectionHeader("50W SuperVOOC battery & OTG"))
        val batteryCard = makeCard()

        batteryCard.addView(
            makeChipSelectorRow(
                title = "Smart charge protection",
                summary = "Hardware charge cutoff with 3% hysteresis to extend dual-cell battery lifespan",
                options = listOf("100% Full" to 100, "90% Limit" to 90, "85% Limit" to 85, "80% Limit" to 80),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_CHG_LIMIT, 100)
            ) { limit ->
                prefs.edit().putInt(SamuraiHardwareBridge.KEY_CHG_LIMIT, limit).apply()
                SamuraiHardwareBridge.sendCommand("CHG_LIMIT", limit)
            }
        )
        batteryCard.addView(makeDivider())

        batteryCard.addView(
            makeChipSelectorRow(
                title = "SuperVOOC thermal speed",
                summary = "Control dual-cell fast-charge current ceiling when gaming or charging overnight",
                options = listOf("Max 50W VOOC" to 0, "Cool 30W" to 2, "Night Trickle" to 5),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_COOL_DOWN, 0)
            ) { cd ->
                prefs.edit().putInt(SamuraiHardwareBridge.KEY_COOL_DOWN, cd).apply()
                SamuraiHardwareBridge.sendCommand("COOL_DOWN", cd)
            }
        )
        batteryCard.addView(makeDivider())

        batteryCard.addView(
            makeSwitchRow(
                title = "USB OTG power supply",
                summary = "Enable 5V host power for external USB drives, DACs & controllers",
                checked = prefs.getBoolean(SamuraiHardwareBridge.KEY_OTG, false)
            ) { enabled ->
                prefs.edit().putBoolean(SamuraiHardwareBridge.KEY_OTG, enabled).apply()
                SamuraiHardwareBridge.sendCommand("OTG", enabled)
            }
        )
        container.addView(batteryCard, cardLayoutParams())

        // =========================================================================
        // SECTION 5: DUAL TFA9894 STEREO AUDIO, CLEAR SPEAKER & AW8697 LRA HAPTICS
        // =========================================================================
        container.addView(makeSectionHeader("Dual stereo speakers & LRA haptics"))
        val audioCard = makeCard()

        audioCard.addView(
            makeChipSelectorRow(
                title = "Dual TFA9894 stereo soundstage",
                summary = "5-band hardware DynamicsProcessing EQ & smart limiter for earpiece + bottom speakers",
                options = listOf(
                    "Balanced Stereo" to 1,
                    "Rich Bass" to 2,
                    "Clear Voice" to 3,
                    "Flat" to 0
                ),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_AUDIO_PRESET, 1)
            ) { preset ->
                SamuraiAudioEngine.setPreset(this, preset)
            }
        )
        audioCard.addView(makeDivider())

        audioCard.addView(
            makeChipSelectorRow(
                title = "Awinic AW8697 X-axis haptics",
                summary = "Linear resonant actuator tactile click intensity",
                options = listOf("Gentle (55%)" to 70, "Crisp (80%)" to 102, "Strong (100%)" to 128),
                selectedValue = prefs.getInt(SamuraiHardwareBridge.KEY_VIB_GAIN, 128)
            ) { gain ->
                prefs.edit().putInt(SamuraiHardwareBridge.KEY_VIB_GAIN, gain).apply()
                SamuraiHardwareBridge.sendCommand("VIB_GAIN", gain)
            }
        )
        audioCard.addView(makeDivider())

        val speakerRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val spkTitle = TextView(this).apply {
            text = "Clear speaker (165 Hz water & dust ejector)"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val spkDesc = TextView(this).apply {
            text = "Plays a 30-second high-excursion 165 Hz acoustic wave to dislodge dust and moisture from both speaker grilles."
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(3), 0, dp(12))
        }
        val actionBtn = TextView(this).apply {
            text = "Run 30s speaker clean"
            setTextColor(chipActiveText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(11), dp(20), dp(11))
            background = roundedBg(chipActiveBg, 22f)
            setOnClickListener {
                if (SamuraiAudioEngine.isSpeakerCleanActive) {
                    SamuraiAudioEngine.stopClearSpeakerPulse()
                    text = "Run 30s speaker clean"
                } else {
                    SamuraiAudioEngine.startClearSpeakerPulse(
                        onProgress = { rem ->
                            clearSpeakerBtn?.text = "Ejecting dust… ${rem}s (Tap to stop)"
                        },
                        onDone = {
                            clearSpeakerBtn?.text = "Run 30s speaker clean"
                        }
                    )
                }
            }
        }
        clearSpeakerBtn = actionBtn
        speakerRow.addView(spkTitle)
        speakerRow.addView(spkDesc)
        speakerRow.addView(
            actionBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        audioCard.addView(speakerRow)

        container.addView(audioCard, cardLayoutParams())

        rootScroll.addView(container)
        return rootScroll
    }

    private fun refreshTelemetryHeader() {
        val t = SamuraiHardwareBridge.readTelemetry()
        val chgBadge = when {
            t.voocActive -> String.format(Locale.US, "⚡ SuperVOOC %.1fW (%+d mA)", t.watts, t.currentMa)
            !t.chgEnabled -> "Charge paused by Smart Limit"
            t.battCapacity >= 0 -> String.format(Locale.US, "Battery %d%% (%+d mA)", t.battCapacity, t.currentMa)
            else -> "Battery ready"
        }
        telemetrySubtitle?.text = String.format(
            Locale.US,
            "%s Hz • %s • %.1f°C",
            t.measuredFps,
            chgBadge,
            t.battTempC
        )
        val cycStr = if (t.cycleCount > 0) " • ${t.cycleCount} cycles" else ""
        telemetryDetail?.text = String.format(
            Locale.US,
            "Prime %d MHz • Gold %d MHz • Adreno %d MHz (%d%%)%s",
            t.cpu7Mhz,
            t.cpu4Mhz,
            t.gpuMhz,
            t.gpuBusyPct,
            cycStr
        )
    }

    private fun refreshAdblockSection() {
        val st = SamuraiAdblockModManager.readStatus()
        val photosLabel = if (st.modPhotos.startsWith("unlimited_active")) {
            st.modPhotos.replace("unlimited_active", "Unlimited Original Quality ✓")
        } else {
            "Pixel XL Backup Active ✓"
        }
        val cromiteInstalled = packageManager.getLaunchIntentForPackage("org.cromite.cromite") != null
        val cromiteLabel = if (cromiteInstalled) {
            st.modCromite.replace("installed", "Installed ✓")
        } else {
            "Optional open-source browser (GPLv3)"
        }

        adblockModsView?.text = buildString {
            append("• Google Photos: ").append(photosLabel).append('\n')
            append("• Cromite Browser: ").append(cromiteLabel).append('\n')
            append("• Tip: For zero-overhead DNS ad-blocking, set Private DNS in Settings → Network & internet")
        }

        val prog = st.progressMessage
        adblockProgressView?.let { tv ->
            if (prog.isNotEmpty()) {
                tv.text = prog
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }

        cromiteChip?.text = if (cromiteInstalled) "Open Cromite Browser" else "Install Cromite Browser"
    }

    private fun makeSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(6), dp(14), dp(6), dp(8))
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardSurface, 24f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
    }

    private fun makeDivider(): View {
        return View(this).apply {
            setBackgroundColor(if (isDark) Color.parseColor("#1FFFFFFF") else Color.parseColor("#14000000"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        }
    }

    private fun makeSwitchRow(
        title: String,
        summary: String,
        checked: Boolean,
        onSwitchCreated: ((Switch) -> Unit)? = null,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(14)
            }
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val tvSummary = TextView(this).apply {
            text = summary
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(tvTitle)
        textCol.addView(tvSummary)

        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                if (!syncingGlassSwitches) {
                    onToggle(isChecked)
                }
            }
        }
        onSwitchCreated?.invoke(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        row.addView(textCol)
        row.addView(sw)
        return row
    }

    private fun makeChipSelectorRow(
        title: String,
        summary: String,
        options: List<Pair<String, Int>>,
        selectedValue: Int,
        onSelect: (Int) -> Unit
    ): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val tvSummary = TextView(this).apply {
            text = summary
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, dp(10))
        }
        wrapper.addView(tvTitle)
        wrapper.addView(tvSummary)

        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chipViews = ArrayList<Pair<Int, TextView>>()
        fun updateChipStyles(activeVal: Int) {
            for ((v, tv) in chipViews) {
                val active = (v == activeVal)
                tv.background = roundedBg(if (active) chipActiveBg else chipInactiveBg, 18f)
                tv.setTextColor(if (active) chipActiveText else textPrimary)
            }
        }

        for ((label, value) in options) {
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    updateChipStyles(value)
                    onSelect(value)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            chipRow.addView(chip, lp)
            chipViews.add(value to chip)
        }
        updateChipStyles(selectedValue)
        hsv.addView(chipRow)
        wrapper.addView(hsv)
        return wrapper
    }
}
