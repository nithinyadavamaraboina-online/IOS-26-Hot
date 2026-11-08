package com.realme.samurai.iossettings.ui.screens

sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object Account : SettingsRoute
    data object Wifi : SettingsRoute
    data object WifiNetworkDetail : SettingsRoute
    data object Bluetooth : SettingsRoute
    data object Cellular : SettingsRoute
    data object Hotspot : SettingsRoute
    data object Battery : SettingsRoute
    data object General : SettingsRoute
    data object About : SettingsRoute
    data object SoftwareUpdate : SettingsRoute
    data object Storage : SettingsRoute
    data object DateTime : SettingsRoute
    data object LanguageRegion : SettingsRoute
    data object Reset : SettingsRoute
    data object Accessibility : SettingsRoute
    data object Camera : SettingsRoute
    data object ControlCenter : SettingsRoute
    data object DisplayBrightness : SettingsRoute
    data object LiquidGlass : SettingsRoute
    data object AutoLock : SettingsRoute
    data object HomeScreen : SettingsRoute
    data object SearchSettings : SettingsRoute
    data object Wallpaper : SettingsRoute
    data object Notifications : SettingsRoute
    data object SoundsHaptics : SettingsRoute
    data object Focus : SettingsRoute
    data object ScreenTime : SettingsRoute
    data object Passcode : SettingsRoute
    data object EmergencySos : SettingsRoute
    data object PrivacySecurity : SettingsRoute
    data object Apps : SettingsRoute
    data class AppDetail(val packageName: String, val label: String, val versionName: String, val isSystem: Boolean) : SettingsRoute
    data object RealmeHardware : SettingsRoute
    data object DeveloperOptions : SettingsRoute
}
