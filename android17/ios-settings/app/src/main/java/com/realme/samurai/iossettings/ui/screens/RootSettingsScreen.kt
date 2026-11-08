package com.realme.samurai.iossettings.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.ui.components.BottomFloatingSearchBar
import com.realme.samurai.iossettings.ui.components.IosAccountCard
import com.realme.samurai.iossettings.ui.components.IosGroupedSection
import com.realme.samurai.iossettings.ui.components.IosSettingsRow
import com.realme.samurai.iossettings.ui.glass.LocalBackdrop
import com.realme.samurai.iossettings.ui.glass.backdropSource
import com.realme.samurai.iossettings.ui.glass.rememberBackdrop
import com.realme.samurai.iossettings.ui.icons.SettingsIcon
import com.realme.samurai.iossettings.ui.icons.SettingsIconBadge
import com.realme.samurai.iossettings.ui.theme.IconTileShape
import com.realme.samurai.iossettings.ui.theme.IosTheme
import com.realme.samurai.iossettings.ui.theme.IosType

private data class SearchableSettingEntry(
    val title: String,
    val keywords: String,
    val breadcrumb: String,
    val icon: SettingsIcon,
    val route: SettingsRoute,
)

private val AllSearchEntries = listOf(
    SearchableSettingEntry("Airplane Mode", "flight wireless radio", "Settings", SettingsIcon.AIRPLANE, SettingsRoute.Root),
    SearchableSettingEntry("Wi-Fi", "wireless internet wlan network ssid", "Settings", SettingsIcon.WIFI, SettingsRoute.Wifi),
    SearchableSettingEntry("Bluetooth", "pair airpods headphones audio", "Settings", SettingsIcon.BLUETOOTH, SettingsRoute.Bluetooth),
    SearchableSettingEntry("Cellular", "mobile data sim volte roaming carrier", "Settings", SettingsIcon.CELLULAR, SettingsRoute.Cellular),
    SearchableSettingEntry("Personal Hotspot", "tethering share wifi password", "Settings", SettingsIcon.HOTSPOT, SettingsRoute.Hotspot),
    SearchableSettingEntry("Battery", "power supervooc charge 80% health", "Settings", SettingsIcon.BATTERY, SettingsRoute.Battery),
    SearchableSettingEntry("General", "about storage update date time reset", "Settings", SettingsIcon.GENERAL, SettingsRoute.General),
    SearchableSettingEntry("About", "device name model serial imei kernel build", "General", SettingsIcon.GENERAL, SettingsRoute.About),
    SearchableSettingEntry("Software Update", "ota samurai system upgrade", "General", SettingsIcon.GENERAL, SettingsRoute.SoftwareUpdate),
    SearchableSettingEntry("Phone Storage", "disk space apps gigabytes", "General", SettingsIcon.GENERAL, SettingsRoute.Storage),
    SearchableSettingEntry("Date & Time", "24 hour clock timezone", "General", SettingsIcon.GENERAL, SettingsRoute.DateTime),
    SearchableSettingEntry("Transfer or Reset Phone", "factory reset network settings", "General", SettingsIcon.GENERAL, SettingsRoute.Reset),
    SearchableSettingEntry("Accessibility", "text size motion contrast display", "Settings", SettingsIcon.ACCESSIBILITY, SettingsRoute.Accessibility),
    SearchableSettingEntry("Camera", "grid mirror hdr video", "Settings", SettingsIcon.CAMERA, SettingsRoute.Camera),
    SearchableSettingEntry("Control Center", "quick toggles swipe", "Settings", SettingsIcon.CONTROL_CENTER, SettingsRoute.ControlCenter),
    SearchableSettingEntry("Display & Brightness", "dark mode light mode 90hz dc dimming auto lock", "Settings", SettingsIcon.DISPLAY, SettingsRoute.DisplayBrightness),
    SearchableSettingEntry("Liquid Glass", "clear tinted frosted refraction blur", "Display & Brightness", SettingsIcon.DISPLAY, SettingsRoute.LiquidGlass),
    SearchableSettingEntry("Home Screen & App Library", "icons dark clear search pill badges", "Settings", SettingsIcon.HOME_SCREEN, SettingsRoute.HomeScreen),
    SearchableSettingEntry("Wallpaper", "lock screen background sky aurora", "Settings", SettingsIcon.WALLPAPER, SettingsRoute.Wallpaper),
    SearchableSettingEntry("Notifications", "alerts previews badges", "Settings", SettingsIcon.NOTIFICATIONS, SettingsRoute.Notifications),
    SearchableSettingEntry("Sounds & Haptics", "volume ringtone alarm speaker water eject", "Settings", SettingsIcon.SOUNDS, SettingsRoute.SoundsHaptics),
    SearchableSettingEntry("Focus", "do not disturb dnd sleep", "Settings", SettingsIcon.FOCUS, SettingsRoute.Focus),
    SearchableSettingEntry("Screen Time", "digital wellbeing app limits", "Settings", SettingsIcon.SCREEN_TIME, SettingsRoute.ScreenTime),
    SearchableSettingEntry("Fingerprint & Passcode", "biometric unlock screen lock security", "Settings", SettingsIcon.PASSCODE, SettingsRoute.Passcode),
    SearchableSettingEntry("Emergency SOS", "rescue emergency call", "Settings", SettingsIcon.SOS, SettingsRoute.EmergencySos),
    SearchableSettingEntry("Privacy & Security", "location permissions dns adguard", "Settings", SettingsIcon.PRIVACY, SettingsRoute.PrivacySecurity),
    SearchableSettingEntry("Apps", "installed applications default browser phone", "Settings", SettingsIcon.APPS, SettingsRoute.Apps),
    SearchableSettingEntry("Realme X2 Pro", "performance gaming thermal hbm otg snapdragon", "Settings", SettingsIcon.REALME_HARDWARE, SettingsRoute.RealmeHardware),
    SearchableSettingEntry("Developer Options", "adb usb wireless debugging animation", "Settings", SettingsIcon.DEVELOPER, SettingsRoute.DeveloperOptions),
)

@Composable
fun RootSettingsScreen(
    controller: SystemSettingsController,
    onNavigate: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = IosTheme.colors
    val state = controller.state
    val backdrop = rememberBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

    val showStickyHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 76
        }
    }

    val topTitlePadding = (statusTop + 56.dp).coerceAtLeast(88.dp)

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.groupedBackground),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .backdropSource(backdrop),
                contentPadding = PaddingValues(
                    top = topTitlePadding,
                    bottom = navBottom + 96.dp,
                ),
            ) {
                item(key = "large_title") {
                    Text(
                        text = "Settings",
                        style = IosType.largeTitle,
                        color = colors.primaryText,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                }

                if (searchQuery.isNotBlank()) {
                    val q = searchQuery.trim().lowercase()
                    val matchedSettings = AllSearchEntries.filter {
                        it.title.lowercase().contains(q) || it.keywords.contains(q)
                    }
                    val matchedApps = state.installedApps.filter {
                        it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                    }

                    if (matchedSettings.isNotEmpty()) {
                        item(key = "search_settings_results") {
                            IosGroupedSection(header = "Settings") {
                                matchedSettings.forEachIndexed { idx, entry ->
                                    IosSettingsRow(
                                        title = entry.title,
                                        subtitle = entry.breadcrumb,
                                        icon = entry.icon,
                                        showChevron = true,
                                        showDivider = idx < matchedSettings.lastIndex,
                                        onClick = { onNavigate(entry.route) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }

                    if (matchedApps.isNotEmpty()) {
                        item(key = "search_apps_results") {
                            IosGroupedSection(header = "Apps") {
                                matchedApps.forEachIndexed { idx, app ->
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
                                        showDivider = idx < matchedApps.lastIndex,
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

                    if (matchedSettings.isEmpty() && matchedApps.isEmpty()) {
                        item(key = "search_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No Results for \"$searchQuery\"",
                                    style = IosType.headline,
                                    color = colors.secondaryText,
                                )
                            }
                        }
                    }
                } else {
                    // 0. Top Account Card (1:1 with hot0151_settings.png)
                    item(key = "account_card") {
                        val accTitle = state.accountName ?: "Google Account"
                        val accSubtitle = if (state.accountEmail != null) {
                            "${state.accountEmail} • Backup, Play Store, Sync"
                        } else {
                            "Sign in to access your Google data, Play Store, cloud backup, and more."
                        }
                        IosAccountCard(
                            title = accTitle,
                            subtitle = accSubtitle,
                            signedInInitial = state.accountName?.take(2),
                            onClick = { onNavigate(SettingsRoute.Account) },
                        )
                        Spacer(Modifier.height(34.dp))
                    }

                    // 1. Connectivity Group (6 rows — exact order of hot0151_settings.png)
                    item(key = "group_connectivity") {
                        IosGroupedSection {
                            IosSettingsRow(
                                title = "Airplane Mode",
                                icon = SettingsIcon.AIRPLANE,
                                checked = state.airplaneMode,
                                onCheckedChange = controller::setAirplaneMode,
                            )
                            IosSettingsRow(
                                title = "Wi-Fi",
                                icon = SettingsIcon.WIFI,
                                value = state.wifiRootStatus,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Wifi) },
                            )
                            IosSettingsRow(
                                title = "Bluetooth",
                                icon = SettingsIcon.BLUETOOTH,
                                value = if (state.bluetoothEnabled) null else "Off",
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Bluetooth) },
                            )
                            IosSettingsRow(
                                title = "Cellular",
                                icon = SettingsIcon.CELLULAR,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Cellular) },
                            )
                            IosSettingsRow(
                                title = "Personal Hotspot",
                                icon = SettingsIcon.HOTSPOT,
                                value = if (state.hotspotEnabled) "On" else null,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Hotspot) },
                            )
                            IosSettingsRow(
                                title = "Battery",
                                icon = SettingsIcon.BATTERY,
                                showChevron = true,
                                showDivider = false,
                                onClick = { onNavigate(SettingsRoute.Battery) },
                            )
                        }
                        Spacer(Modifier.height(34.dp))
                    }

                    // 2. General, Accessibility, Camera, Control Center, Display, Home Screen, Search, Wallpaper
                    // (Exact row sequence from hot0151_settings.png!)
                    item(key = "group_general_display") {
                        IosGroupedSection {
                            IosSettingsRow(
                                title = "General",
                                icon = SettingsIcon.GENERAL,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.General) },
                            )
                            IosSettingsRow(
                                title = "Accessibility",
                                icon = SettingsIcon.ACCESSIBILITY,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Accessibility) },
                            )
                            IosSettingsRow(
                                title = "Camera",
                                icon = SettingsIcon.CAMERA,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Camera) },
                            )
                            IosSettingsRow(
                                title = "Control Center",
                                icon = SettingsIcon.CONTROL_CENTER,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.ControlCenter) },
                            )
                            IosSettingsRow(
                                title = "Display & Brightness",
                                icon = SettingsIcon.DISPLAY,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.DisplayBrightness) },
                            )
                            IosSettingsRow(
                                title = "Home Screen & App Library",
                                icon = SettingsIcon.HOME_SCREEN,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.HomeScreen) },
                            )
                            IosSettingsRow(
                                title = "Search",
                                icon = SettingsIcon.SEARCH_SETTINGS,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.SearchSettings) },
                            )
                            IosSettingsRow(
                                title = "Wallpaper",
                                icon = SettingsIcon.WALLPAPER,
                                showChevron = true,
                                showDivider = false,
                                onClick = { onNavigate(SettingsRoute.Wallpaper) },
                            )
                        }
                        Spacer(Modifier.height(34.dp))
                    }

                    // 3. Notifications, Sounds & Haptics, Focus, Screen Time
                    item(key = "group_notifications_sounds") {
                        IosGroupedSection {
                            IosSettingsRow(
                                title = "Notifications",
                                icon = SettingsIcon.NOTIFICATIONS,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Notifications) },
                            )
                            IosSettingsRow(
                                title = "Sounds & Haptics",
                                icon = SettingsIcon.SOUNDS,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.SoundsHaptics) },
                            )
                            IosSettingsRow(
                                title = "Focus",
                                icon = SettingsIcon.FOCUS,
                                value = if (state.dndEnabled) "On" else null,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Focus) },
                            )
                            IosSettingsRow(
                                title = "Screen Time",
                                icon = SettingsIcon.SCREEN_TIME,
                                showChevron = true,
                                showDivider = false,
                                onClick = { onNavigate(SettingsRoute.ScreenTime) },
                            )
                        }
                        Spacer(Modifier.height(34.dp))
                    }

                    // 4. Biometrics, Emergency SOS, Privacy & Security
                    item(key = "group_security_privacy") {
                        IosGroupedSection {
                            IosSettingsRow(
                                title = "Fingerprint & Passcode",
                                icon = SettingsIcon.PASSCODE,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Passcode) },
                            )
                            IosSettingsRow(
                                title = "Emergency SOS",
                                icon = SettingsIcon.SOS,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.EmergencySos) },
                            )
                            IosSettingsRow(
                                title = "Privacy & Security",
                                icon = SettingsIcon.PRIVACY,
                                showChevron = true,
                                showDivider = false,
                                onClick = { onNavigate(SettingsRoute.PrivacySecurity) },
                            )
                        }
                        Spacer(Modifier.height(34.dp))
                    }

                    // 5. Apps, Realme X2 Pro Hardware, Developer Options
                    item(key = "group_apps_hardware") {
                        IosGroupedSection {
                            IosSettingsRow(
                                title = "Apps",
                                icon = SettingsIcon.APPS,
                                showChevron = true,
                                onClick = { onNavigate(SettingsRoute.Apps) },
                            )
                            IosSettingsRow(
                                title = "Realme X2 Pro",
                                icon = SettingsIcon.REALME_HARDWARE,
                                value = state.performanceProfileLabel,
                                showChevron = true,
                                showDivider = state.developerOptionsEnabled,
                                onClick = { onNavigate(SettingsRoute.RealmeHardware) },
                            )
                            if (state.developerOptionsEnabled) {
                                IosSettingsRow(
                                    title = "Developer Options",
                                    icon = SettingsIcon.DEVELOPER,
                                    showChevron = true,
                                    showDivider = false,
                                    onClick = { onNavigate(SettingsRoute.DeveloperOptions) },
                                )
                            }
                        }
                    }
                }
            }

            // Collapsed centered sticky navigation header with smooth progressive fade when scrolled down
            AnimatedVisibility(
                visible = showStickyHeader,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(110)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.groupedBackground)
                            .padding(top = statusTop + 12.dp, bottom = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Settings",
                            style = IosType.headline,
                            color = colors.primaryText,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        colors.groupedBackground,
                                        colors.groupedBackground.copy(alpha = 0f),
                                    )
                                )
                            ),
                    )
                }
            }

            // Bottom floating iOS 26 Liquid Glass Search bar (exact match to hot0151_settings.png)
            BottomFloatingSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                focused = searchFocused,
                onFocusChange = { searchFocused = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + 24.dp),
            )
        }
    }
}
