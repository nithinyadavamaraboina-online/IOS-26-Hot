package com.realme.samurai.iossettings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.ui.screens.IosSettingsApp
import com.realme.samurai.iossettings.ui.screens.SettingsRoute

class IosSettingsActivity : ComponentActivity() {

    private var initialRoute by mutableStateOf<SettingsRoute>(SettingsRoute.Root)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        initialRoute = routeForIntent(intent)

        setContent {
            val controller = remember { SystemSettingsController(applicationContext) }
            DisposableEffect(controller) {
                controller.start()
                onDispose { controller.stop() }
            }
            IosSettingsApp(
                controller = controller,
                initialRoute = initialRoute,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialRoute = routeForIntent(intent)
    }

    private fun routeForIntent(intent: Intent?): SettingsRoute {
        return when (intent?.action) {
            Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIFI_IP_SETTINGS -> SettingsRoute.Wifi
            Settings.ACTION_BLUETOOTH_SETTINGS -> SettingsRoute.Bluetooth
            Settings.ACTION_DATA_ROAMING_SETTINGS,
            Settings.ACTION_NETWORK_OPERATOR_SETTINGS -> SettingsRoute.Cellular
            Settings.ACTION_DISPLAY_SETTINGS,
            Settings.ACTION_NIGHT_DISPLAY_SETTINGS -> SettingsRoute.DisplayBrightness
            Settings.ACTION_SOUND_SETTINGS -> SettingsRoute.SoundsHaptics
            Settings.ACTION_BATTERY_SAVER_SETTINGS,
            Intent.ACTION_POWER_USAGE_SUMMARY -> SettingsRoute.Battery
            Settings.ACTION_DEVICE_INFO_SETTINGS -> SettingsRoute.About
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> SettingsRoute.Storage
            Settings.ACTION_DATE_SETTINGS -> SettingsRoute.DateTime
            Settings.ACTION_LOCALE_SETTINGS -> SettingsRoute.LanguageRegion
            Settings.ACTION_APPLICATION_SETTINGS,
            Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS -> SettingsRoute.Apps
            Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            Settings.ACTION_PRIVACY_SETTINGS -> SettingsRoute.PrivacySecurity
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS -> SettingsRoute.DeveloperOptions
            else -> {
                val pageKey = intent?.getStringExtra("start_route")
                    ?: intent?.getStringExtra("samurai_page")
                when (pageKey) {
                    "wifi" -> SettingsRoute.Wifi
                    "bluetooth" -> SettingsRoute.Bluetooth
                    "cellular" -> SettingsRoute.Cellular
                    "hotspot" -> SettingsRoute.Hotspot
                    "display" -> SettingsRoute.DisplayBrightness
                    "sounds" -> SettingsRoute.SoundsHaptics
                    "battery" -> SettingsRoute.Battery
                    "apps" -> SettingsRoute.Apps
                    "control_center" -> SettingsRoute.ControlCenter
                    "liquid_glass" -> SettingsRoute.LiquidGlass
                    "about" -> SettingsRoute.About
                    "hardware" -> SettingsRoute.RealmeHardware
                    else -> SettingsRoute.Root
                }
            }
        }
    }
}
