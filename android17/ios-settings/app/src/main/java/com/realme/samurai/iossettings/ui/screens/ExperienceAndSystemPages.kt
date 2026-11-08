package com.realme.samurai.iossettings.ui.screens

import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.ui.components.DisplayAppearanceSelectorRow
import com.realme.samurai.iossettings.ui.components.IosGroupedSection
import com.realme.samurai.iossettings.ui.components.IosHeroPlacardCard
import com.realme.samurai.iossettings.ui.components.IosSettingsRow
import com.realme.samurai.iossettings.ui.components.IosSliderRow
import com.realme.samurai.iossettings.ui.components.IosSubPageScaffold
import com.realme.samurai.iossettings.ui.components.LiquidGlassPreviewCard
import com.realme.samurai.iossettings.ui.glass.SystemGlassConfig
import com.realme.samurai.iossettings.ui.icons.SettingsIcon
import com.realme.samurai.iossettings.ui.icons.SettingsIconBadge
import com.realme.samurai.iossettings.ui.icons.SpeakerSliderIcon
import com.realme.samurai.iossettings.ui.icons.SunSliderIcon
import com.realme.samurai.iossettings.ui.theme.HeroIconTileShape
import com.realme.samurai.iossettings.ui.theme.IconTileShape
import com.realme.samurai.iossettings.ui.theme.IosTheme
import com.realme.samurai.iossettings.ui.theme.IosType
import kotlin.math.roundToInt

@Composable
fun DisplayBrightnessScreen(
    controller: SystemSettingsController,
    onNavigate: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors

    IosSubPageScaffold(title = "Display & Brightness", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Appearance") {
                DisplayAppearanceSelectorRow(
                    isDarkSelected = state.darkMode,
                    onSelectDark = controller::setDarkMode,
                )
                IosSettingsRow(
                    title = "Automatic",
                    checked = state.autoAppearance,
                    onCheckedChange = controller::setAutoAppearance,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Brightness") {
                IosSliderRow(
                    value = state.brightness,
                    onValueChange = controller::setBrightness,
                    leadingIcon = { SunSliderIcon(color = colors.secondaryText, large = false) },
                    trailingIcon = { SunSliderIcon(color = colors.secondaryText, large = true) },
                    showDivider = true,
                )
                IosSettingsRow(
                    title = "Adaptive Brightness",
                    checked = state.adaptiveBrightness,
                    onCheckedChange = controller::setAdaptiveBrightness,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Liquid Glass",
                    value = state.liquidGlassLabel,
                    showChevron = true,
                    onClick = { onNavigate(SettingsRoute.LiquidGlass) },
                )
                IosSettingsRow(
                    title = "Night Shift",
                    checked = state.nightShift,
                    onCheckedChange = controller::setNightShift,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Auto-Lock",
                    value = state.autoLockLabel,
                    showChevron = true,
                    onClick = { onNavigate(SettingsRoute.AutoLock) },
                )
                IosSettingsRow(
                    title = "Raise to Wake",
                    checked = state.raiseToWake,
                    onCheckedChange = controller::setRaiseToWake,
                )
                IosSettingsRow(
                    title = "Tap to Wake",
                    checked = state.tapToWake,
                    onCheckedChange = controller::setTapToWake,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(
                header = "Super AMOLED Display",
                footer = "90 Hz ProMotion delivers fluid UIKit spring animations on Realme X2 Pro.",
            ) {
                IosSettingsRow(
                    title = "90 Hz Smooth Display",
                    checked = state.refreshRate90Hz,
                    onCheckedChange = controller::setRefreshRate90Hz,
                )
                IosSettingsRow(
                    title = "DC Dimming",
                    checked = state.dcDimming,
                    onCheckedChange = controller::setDcDimming,
                    showDivider = false,
                )
            }
        }
    }
}

/**
 * Exact 1:1 reproduction of iOS 26.1 `Liquid Glass` (`ios26_08_ios261_clear_vs_tinted.png`).
 */
@Composable
fun LiquidGlassSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val isTinted = state.liquidGlassMode == SystemGlassConfig.MODE_FROSTED_TINTED

    IosSubPageScaffold(title = "Liquid Glass", alwaysShowTitle = true, onBack = onBack) {
        item {
            Spacer(Modifier.height(6.dp))
            LiquidGlassPreviewCard(isTinted = isTinted)
            Spacer(Modifier.height(22.dp))
        }

        item {
            IosGroupedSection(
                footer = "Choose your preferred look for Liquid Glass. Clear is more transparent, revealing the content beneath. Tinted increases opacity and adds more contrast.",
            ) {
                IosSettingsRow(
                    title = "Clear",
                    selectedCheckmark = !isTinted,
                    onClick = { controller.setLiquidGlassMode(SystemGlassConfig.MODE_CLEAR_REFRACTIVE) },
                )
                IosSettingsRow(
                    title = "Tinted",
                    selectedCheckmark = isTinted,
                    showDivider = false,
                    onClick = { controller.setLiquidGlassMode(SystemGlassConfig.MODE_FROSTED_TINTED) },
                )
            }
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(
                header = "Optical Calibration",
                footer = "Synchronized live across Home Screen, Messages, Phone, and floating Settings bars.",
            ) {
                IosSettingsRow(
                    title = "Refraction Lens",
                    value = "${state.refractionPct}%",
                    showDivider = false,
                )
                IosSliderRow(
                    value = state.refractionPct / 150f,
                    onValueChange = { controller.setRefractionPct((it * 150f).roundToInt()) },
                    showDivider = true,
                )
                IosSettingsRow(
                    title = "Frost Diffusion",
                    value = "${state.frostPct}%",
                    showDivider = false,
                )
                IosSliderRow(
                    value = state.frostPct / 150f,
                    onValueChange = { controller.setFrostPct((it * 150f).roundToInt()) },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun AutoLockScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val options = listOf(
        15 to "15 Seconds",
        30 to "30 Seconds",
        60 to "1 Minute",
        120 to "2 Minutes",
        300 to "5 Minutes",
        600 to "10 Minutes",
        1800 to "Never",
    )

    IosSubPageScaffold(title = "Auto-Lock", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                options.forEachIndexed { idx, (sec, label) ->
                    IosSettingsRow(
                        title = label,
                        selectedCheckmark = state.autoLockSeconds == sec,
                        showDivider = idx < options.lastIndex,
                        onClick = { controller.setAutoLockSeconds(sec) },
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreenSettings(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val styles = listOf(0 to "Default", 1 to "Dark", 2 to "Clear Glass")

    IosSubPageScaffold(title = "Home Screen & App Library", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "App Icons") {
                styles.forEachIndexed { idx, (code, label) ->
                    IosSettingsRow(
                        title = label,
                        selectedCheckmark = state.iconStyle == code,
                        showDivider = idx < styles.lastIndex,
                        onClick = { controller.setIconStyle(code) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Search & Gestures") {
                IosSettingsRow(
                    title = "Show on Home Screen",
                    checked = state.showHomeSearchPill,
                    onCheckedChange = controller::setShowHomeSearchPill,
                    showDivider = true,
                )
                IosSettingsRow(
                    title = "Double-Tap Home Screen to Lock",
                    checked = state.doubleTapHomeToLock,
                    onCheckedChange = controller::setDoubleTapHomeToLock,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Notification Badges") {
                IosSettingsRow(
                    title = "Show in App Library",
                    checked = state.notificationBadges,
                    onCheckedChange = {},
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun WallpaperSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val colors = IosTheme.colors
    val context = LocalContext.current
    var iosLockScreenEnabled by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(context.contentResolver, "samurai_ios26_lockscreen", 1) == 1
            } catch (_: Throwable) {
                true
            }
        )
    }
    var lockClockStyle by remember {
        mutableStateOf(
            try {
                Settings.Global.getInt(context.contentResolver, "samurai_lockscreen_clock_style", 0).coerceIn(0, 1)
            } catch (_: Throwable) {
                0
            }
        )
    }

    IosSubPageScaffold(title = "Wallpaper & Lock Screen", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.WALLPAPER,
                title = "Wallpaper & Lock Screen",
                description = "Personalize your iOS 26 Liquid Glass Lock Screen, clock numerals, and curated backdrops.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(
                header = "iOS 26 Lock Screen",
                footer = "Long-press anywhere on the Lock Screen to switch between Liquid Glass and Solid numerals live.",
            ) {
                IosSettingsRow(
                    title = "iOS 26 Liquid Glass Lock Screen",
                    checked = iosLockScreenEnabled,
                    onCheckedChange = { enabled ->
                        iosLockScreenEnabled = enabled
                        try {
                            Settings.Global.putInt(
                                context.contentResolver,
                                "samurai_ios26_lockscreen",
                                if (enabled) 1 else 0,
                            )
                        } catch (_: Throwable) {
                        }
                    },
                )
                IosSettingsRow(
                    title = "Clock Numerals",
                    value = if (lockClockStyle == 0) "Liquid Glass" else "Solid",
                    showChevron = true,
                    showDivider = false,
                    onClick = {
                        val next = if (lockClockStyle == 0) 1 else 0
                        lockClockStyle = next
                        try {
                            Settings.Global.putInt(
                                context.contentResolver,
                                "samurai_lockscreen_clock_style",
                                next,
                            )
                        } catch (_: Throwable) {
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "iOS 26 Collections") {
                IosSettingsRow(
                    title = "iOS 26 Sky Daylight",
                    titleColor = colors.accent,
                    showChevron = true,
                    onClick = { controller.applyIos26WallpaperPreset(0) },
                )
                IosSettingsRow(
                    title = "iOS 26 Midnight Abyss",
                    titleColor = colors.accent,
                    showChevron = true,
                    onClick = { controller.applyIos26WallpaperPreset(1) },
                )
                IosSettingsRow(
                    title = "iOS 26 Sapphire Prism",
                    titleColor = colors.accent,
                    showChevron = true,
                    showDivider = false,
                    onClick = { controller.applyIos26WallpaperPreset(2) },
                )
            }
        }
    }
}

@Composable
fun SearchSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    var showRecent by remember { mutableStateOf(true) }
    var showRelated by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = "Search", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Search & Suggestions") {
                IosSettingsRow(
                    title = "Show Recent Searches",
                    checked = showRecent,
                    onCheckedChange = { showRecent = it },
                )
                IosSettingsRow(
                    title = "Show Related Content",
                    checked = showRelated,
                    onCheckedChange = { showRelated = it },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun AccessibilitySettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    var reduceMotion by remember { mutableStateOf(state.animationScale == 0f) }

    IosSubPageScaffold(title = "Accessibility", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.ACCESSIBILITY,
                title = "Accessibility",
                description = "Customize display text size, motion, Liquid Glass contrast, and touch accommodations.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(header = "Vision") {
                IosSettingsRow(
                    title = "Increase Contrast (Tinted Glass)",
                    checked = state.liquidGlassMode == SystemGlassConfig.MODE_FROSTED_TINTED,
                    onCheckedChange = {
                        controller.setLiquidGlassMode(
                            if (it) SystemGlassConfig.MODE_FROSTED_TINTED else SystemGlassConfig.MODE_CLEAR_REFRACTIVE
                        )
                    },
                )
                IosSettingsRow(
                    title = "Reduce Motion",
                    checked = reduceMotion,
                    onCheckedChange = {
                        reduceMotion = it
                        controller.setAnimationScale(if (it) 0f else 1f)
                    },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Text Size") {
                IosSliderRow(
                    value = ((state.fontScale - 0.85f) / 0.45f).coerceIn(0f, 1f),
                    onValueChange = { controller.setFontScale(0.85f + it * 0.45f) },
                )
            }
        }
    }
}

@Composable
fun CameraSettingsScreen(
    onBack: () -> Unit,
) {
    var grid by remember { mutableStateOf(true) }
    var mirrorFront by remember { mutableStateOf(true) }
    var record4k60 by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = "Camera", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Composition") {
                IosSettingsRow(title = "Grid", checked = grid, onCheckedChange = { grid = it })
                IosSettingsRow(
                    title = "Mirror Front Camera",
                    checked = mirrorFront,
                    onCheckedChange = { mirrorFront = it },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            IosGroupedSection(header = "Capture • 64MP GW1 Quad-Camera") {
                IosSettingsRow(
                    title = "Record Video",
                    value = if (record4k60) "4K at 60 fps" else "1080p at 60 fps",
                    checked = record4k60,
                    onCheckedChange = { record4k60 = it },
                )
                IosSettingsRow(title = "Formats", value = "High Efficiency", showDivider = false)
            }
        }
    }
}

@Composable
fun ControlCenterScreen(
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = IosTheme.colors

    var accessWithinApps by remember {
        mutableStateOf(
            try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "samurai_cc_in_apps", 1) == 1
            } catch (_: Throwable) {
                true
            }
        )
    }
    var accessOnLock by remember {
        mutableStateOf(
            try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "samurai_cc_on_lock", 1) == 1
            } catch (_: Throwable) {
                true
            }
        )
    }

    IosSubPageScaffold(title = "Control Center", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(
                footer = "Swipe down on the right side for iOS 26 Liquid Glass Control Center, or swipe down on the left side for iOS 26 Liquid Glass Notification Center.",
            ) {
                IosSettingsRow(
                    title = "Access Within Apps",
                    checked = accessWithinApps,
                    onCheckedChange = { enabled ->
                        accessWithinApps = enabled
                        try {
                            android.provider.Settings.Global.putInt(
                                ctx.contentResolver,
                                "samurai_cc_in_apps",
                                if (enabled) 1 else 0,
                            )
                        } catch (_: Throwable) {
                        }
                    },
                )
                IosSettingsRow(
                    title = "Access on Lock Screen",
                    checked = accessOnLock,
                    onCheckedChange = { enabled ->
                        accessOnLock = enabled
                        try {
                            android.provider.Settings.Global.putInt(
                                ctx.contentResolver,
                                "samurai_cc_on_lock",
                                if (enabled) 1 else 0,
                            )
                        } catch (_: Throwable) {
                        }
                    },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Open Control Center",
                    titleColor = colors.accent,
                    showDivider = false,
                    onClick = {
                        try {
                            val svc = android.content.Intent("com.realme.samurai.controlcenter.ACTION_OPEN").apply {
                                setClassName(
                                    "com.realme.samurai.controlcenter",
                                    "com.realme.samurai.controlcenter.service.ControlCenterOverlayService",
                                )
                            }
                            ctx.startService(svc)
                        } catch (_: Throwable) {
                            try {
                                val act = android.content.Intent("com.realme.samurai.controlcenter.ACTION_PREVIEW").apply {
                                    setClassName(
                                        "com.realme.samurai.controlcenter",
                                        "com.realme.samurai.controlcenter.ControlCenterActivity",
                                    )
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(act)
                            } catch (_: Throwable) {
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun NotificationsSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    var showPreviews by remember { mutableStateOf(true) }
    var screenSharingHide by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = "Notifications", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.NOTIFICATIONS,
                title = "Notifications",
                description = "Manage notification delivery style, Lock Screen previews, and per-app alert badges.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Show Previews",
                    value = if (showPreviews) "Always" else "When Unlocked",
                    checked = showPreviews,
                    onCheckedChange = { showPreviews = it },
                )
                IosSettingsRow(
                    title = "Hide Alerts When Screen Sharing",
                    checked = screenSharingHide,
                    onCheckedChange = { screenSharingHide = it },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun SoundsHapticsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors

    IosSubPageScaffold(title = "Sounds & Haptics", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Ringtone and Alerts") {
                IosSliderRow(
                    value = state.ringVolume,
                    onValueChange = { controller.setStreamVolume(AudioManager.STREAM_RING, it) },
                    leadingIcon = { SpeakerSliderIcon(color = colors.secondaryText, waves = 0) },
                    trailingIcon = { SpeakerSliderIcon(color = colors.secondaryText, waves = 2) },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Media & Alarm Volume") {
                IosSettingsRow(title = "Media", value = "${(state.mediaVolume * 100).roundToInt()}%", showDivider = false)
                IosSliderRow(
                    value = state.mediaVolume,
                    onValueChange = { controller.setStreamVolume(AudioManager.STREAM_MUSIC, it) },
                    showDivider = true,
                )
                IosSettingsRow(title = "Alarms", value = "${(state.alarmVolume * 100).roundToInt()}%", showDivider = false)
                IosSliderRow(
                    value = state.alarmVolume,
                    onValueChange = { controller.setStreamVolume(AudioManager.STREAM_ALARM, it) },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Sounds & Tactile Engine") {
                IosSettingsRow(
                    title = "System Haptics",
                    checked = state.systemHaptics,
                    onCheckedChange = controller::setSystemHaptics,
                )
                IosSettingsRow(
                    title = "Keyboard Feedback",
                    checked = state.keyboardClicks,
                    onCheckedChange = controller::setKeyboardClicks,
                )
                IosSettingsRow(
                    title = "Lock Sound",
                    checked = state.lockSounds,
                    onCheckedChange = controller::setLockSounds,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(
                header = "Dolby Stereo Speakers",
                footer = "Plays a calibrated 165 Hz acoustic wave to clear dust and moisture from the speaker grilles.",
            ) {
                IosSettingsRow(
                    title = if (state.speakerCleaning) "Ejecting Water..." else "Clear Speaker (165 Hz Pulse)",
                    titleColor = colors.accent,
                    showDivider = false,
                    onClick = controller::runSpeakerWaterEject,
                )
            }
        }
    }
}

@Composable
fun FocusSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    IosSubPageScaffold(title = "Focus", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.FOCUS,
                title = "Focus",
                description = "Focus helps you stay in the moment when you need to concentrate or step away from your device.",
                learnMoreText = "",
                switchLabel = "Do Not Disturb",
                switchChecked = state.dndEnabled,
                onSwitchChange = controller::setDndEnabled,
            )
        }
    }
}

@Composable
fun ScreenTimeScreen(
    onBack: () -> Unit,
) {
    var shareAcrossDevices by remember { mutableStateOf(false) }
    IosSubPageScaffold(title = "Screen Time", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.SCREEN_TIME,
                title = "Screen Time",
                description = "View daily app usage insights, schedule Downtime, and set healthy limits for categories of apps.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }
        item {
            IosGroupedSection {
                IosSettingsRow(title = "App & Website Activity", value = "Active")
                IosSettingsRow(
                    title = "Share Across Devices",
                    checked = shareAcrossDevices,
                    onCheckedChange = { shareAcrossDevices = it },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun PasscodeSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var unlockPhone by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = "Fingerprint & Passcode", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.PASSCODE,
                title = "Fingerprint & Passcode",
                description = "Use the Goodix optical in-display fingerprint sensor and screen passcode to unlock your Realme X2 Pro.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(header = "Use Fingerprint For") {
                IosSettingsRow(
                    title = "Phone Unlock",
                    checked = unlockPhone,
                    onCheckedChange = { unlockPhone = it },
                )
                IosSettingsRow(
                    title = "Biometric Status",
                    value = if (state.biometricEnrolled) "Enrolled" else "Not Set Up",
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Enroll Fingerprint or Change Passcode",
                    titleColor = colors.accent,
                    showChevron = true,
                    showDivider = false,
                    onClick = controller::launchBiometricEnrollment,
                )
            }
        }
    }
}

@Composable
fun EmergencySosScreen(
    onBack: () -> Unit,
) {
    var callWith5Presses by remember { mutableStateOf(true) }
    var callQuietly by remember { mutableStateOf(false) }

    IosSubPageScaffold(title = "Emergency SOS", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.SOS,
                title = "Emergency SOS",
                description = "Quickly call emergency services and alert your emergency contacts by pressing the Side button 5 times.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }
        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Call with 5 Button Presses",
                    checked = callWith5Presses,
                    onCheckedChange = { callWith5Presses = it },
                )
                IosSettingsRow(
                    title = "Call Quietly",
                    checked = callQuietly,
                    onCheckedChange = { callQuietly = it },
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun PrivacySecurityScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val dnsPresets = listOf("Automatic", "AdGuard DNS", "Cloudflare 1.1.1.1", "Off")

    IosSubPageScaffold(title = "Privacy & Security", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.PRIVACY,
                title = "Privacy & Security",
                description = "Control Location Services, microphone & camera indicators, and encrypted DNS ad-blocking.",
                learnMoreText = "",
                switchLabel = "Location Services",
                switchChecked = state.locationEnabled,
                onSwitchChange = controller::setLocationEnabled,
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(
                header = "Encrypted Private DNS & Ad-Blocking",
                footer = "AdGuard DNS blocks system-wide trackers and ads over DNS-over-TLS without VPN battery drain.",
            ) {
                dnsPresets.forEachIndexed { idx, preset ->
                    IosSettingsRow(
                        title = preset,
                        selectedCheckmark = state.privateDnsMode == preset ||
                            (preset == "AdGuard DNS" && state.privateDnsMode.contains("adguard", ignoreCase = true)) ||
                            (preset == "Cloudflare 1.1.1.1" && state.privateDnsMode.contains("one.one", ignoreCase = true)),
                        showDivider = idx < dnsPresets.lastIndex,
                        onClick = { controller.setPrivateDnsPreset(preset) },
                    )
                }
            }
        }
    }
}

@Composable
fun AppsSettingsScreen(
    controller: SystemSettingsController,
    onNavigate: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
) {
    val state = controller.state

    IosSubPageScaffold(title = "Apps", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Default Apps",
                    subtitle = "Phone, Messages, Browser & Home",
                    icon = SettingsIcon.APPS,
                    showChevron = true,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Installed Apps (${state.installedApps.size})") {
                if (state.installedApps.isEmpty()) {
                    IosSettingsRow(title = "Loading Applications...", showDivider = false)
                } else {
                    state.installedApps.forEachIndexed { idx, app ->
                        IosSettingsRow(
                            title = app.label,
                            customLeading = {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = app.label,
                                        modifier = Modifier
                                            .size(29.dp)
                                            .clip(IconTileShape),
                                    )
                                } else {
                                    SettingsIconBadge(icon = SettingsIcon.APPS, size = 29.dp)
                                }
                            },
                            showChevron = true,
                            showDivider = idx < state.installedApps.lastIndex,
                            onClick = {
                                onNavigate(
                                    SettingsRoute.AppDetail(
                                        packageName = app.packageName,
                                        label = app.label,
                                        versionName = app.versionName,
                                        isSystem = app.isSystemApp,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppDetailScreen(
    route: SettingsRoute.AppDetail,
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val colors = IosTheme.colors
    val matched = controller.state.installedApps.firstOrNull { it.packageName == route.packageName }
    var allowNotifs by remember { mutableStateOf(true) }
    var bgRefresh by remember { mutableStateOf(true) }
    var cellData by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = route.label, alwaysShowTitle = true, onBack = onBack) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (matched?.icon != null) {
                    Image(
                        bitmap = matched.icon,
                        contentDescription = route.label,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(HeroIconTileShape),
                    )
                } else {
                    SettingsIconBadge(icon = SettingsIcon.APPS, size = 64.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(route.label, style = IosType.title2, color = colors.primaryText)
                Text("Version ${route.versionName}", style = IosType.footnote, color = colors.secondaryText)
            }
            Spacer(Modifier.height(16.dp))
        }

        item {
            IosGroupedSection(header = "Allow ${route.label} to Access") {
                IosSettingsRow(
                    title = "Notifications",
                    icon = SettingsIcon.NOTIFICATIONS,
                    checked = allowNotifs,
                    onCheckedChange = { allowNotifs = it },
                )
                IosSettingsRow(
                    title = "Background App Refresh",
                    icon = SettingsIcon.GENERAL,
                    checked = bgRefresh,
                    onCheckedChange = { bgRefresh = it },
                )
                IosSettingsRow(
                    title = "Cellular Data",
                    icon = SettingsIcon.CELLULAR,
                    checked = cellData,
                    onCheckedChange = { cellData = it },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Open ${route.label}",
                    titleColor = colors.accent,
                    showDivider = true,
                    onClick = { controller.launchApp(route.packageName) },
                )
                IosSettingsRow(
                    title = "Bundle Identifier",
                    value = route.packageName,
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun RealmeHardwareScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val profiles = listOf(
        0 to "Balanced",
        1 to "Performance (2.96 GHz Prime)",
        2 to "Gaming Pro (Adreno 640 Boost)",
        3 to "Efficiency Saver",
    )

    IosSubPageScaffold(title = "Realme X2 Pro", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.REALME_HARDWARE,
                title = "Realme X2 Pro",
                description = "Configure Snapdragon 855+ thermal profiles, High Brightness Mode (1000 nits), and USB OTG power.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(header = "Thermal & CPU Governor") {
                profiles.forEachIndexed { idx, (code, label) ->
                    IosSettingsRow(
                        title = label,
                        selectedCheckmark = state.performanceProfile == code,
                        showDivider = idx < profiles.lastIndex,
                        onClick = { controller.setPerformanceProfile(code) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Hardware Controls") {
                IosSettingsRow(
                    title = "Sunlight High Brightness Mode",
                    checked = state.highBrightnessMode,
                    onCheckedChange = controller::setHighBrightnessMode,
                )
                IosSettingsRow(
                    title = "USB OTG Power Supply",
                    checked = state.otgEnabled,
                    onCheckedChange = controller::setOtgEnabled,
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun DeveloperOptionsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val scales = listOf(1.0f to "1x (Default)", 0.5f to "0.5x (Fast)", 0.0f to "Animation Off")

    IosSubPageScaffold(title = "Developer Options", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(
                header = "System Developer Dashboard",
                footer = "Opens the complete Android 17 Developer Options dashboard (OEM unlocking, logger buffer sizes, smallest width DPI, Bluetooth audio codecs, GPU rendering, and 80+ advanced toggles)."
            ) {
                IosSettingsRow(
                    title = "Open Full Android Developer Options",
                    subtitle = "Launch native Android 17 Developer Options",
                    value = "Open",
                    showChevron = true,
                    showDivider = false,
                    onClick = controller::openNativeDeveloperOptions,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Debugging") {
                IosSettingsRow(
                    title = "USB Debugging (ADB)",
                    checked = state.usbDebugging,
                    onCheckedChange = controller::setUsbDebugging,
                )
                IosSettingsRow(
                    title = "Wireless Debugging",
                    checked = state.wirelessDebugging,
                    onCheckedChange = controller::setWirelessDebugging,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Window & Transition Animation Scale") {
                scales.forEachIndexed { idx, (scale, label) ->
                    IosSettingsRow(
                        title = label,
                        selectedCheckmark = state.animationScale == scale,
                        showDivider = idx < scales.lastIndex,
                        onClick = { controller.setAnimationScale(scale) },
                    )
                }
            }
        }
    }
}
