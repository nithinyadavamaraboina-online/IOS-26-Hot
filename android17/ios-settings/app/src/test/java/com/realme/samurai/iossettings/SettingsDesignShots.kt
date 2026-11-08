package com.realme.samurai.iossettings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.data.SystemSettingsState
import com.realme.samurai.iossettings.ui.glass.SystemGlassConfig
import com.realme.samurai.iossettings.ui.screens.IosSettingsApp
import com.realme.samurai.iossettings.ui.screens.SettingsRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w375dp-h833dp-xxhdpi")
class SettingsDesignShots {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        dark: Boolean = false,
        before: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.waitForIdle()
        // Trigger second frame so Backdrop GraphicsLayer records and refracts!
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        before()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun rootSettingsLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false),
            previewOnly = true,
        )
        capture("01_root_settings_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Root, darkThemeOverride = false)
        }
    }

    @Test
    fun rootSettingsDark() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = true),
            previewOnly = true,
        )
        capture("02_root_settings_dark", dark = true) {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Root, darkThemeOverride = true)
        }
    }

    @Test
    fun rootSettingsScrolledLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false),
            previewOnly = true,
        )
        capture(
            name = "03_root_settings_scrolled_light",
            before = {
                compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.78f, endY = top + bottom * 0.22f) }
            },
        ) {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Root, darkThemeOverride = false)
        }
    }

    @Test
    fun wifiSubPageLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false),
            previewOnly = true,
        )
        capture("04_wifi_subpage_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Wifi, darkThemeOverride = false)
        }
    }

    @Test
    fun wifiSubPageDark() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = true),
            previewOnly = true,
        )
        capture("05_wifi_subpage_dark", dark = true) {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Wifi, darkThemeOverride = true)
        }
    }

    @Test
    fun liquidGlassClearLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false).copy(
                liquidGlassMode = SystemGlassConfig.MODE_CLEAR_REFRACTIVE,
            ),
            previewOnly = true,
        )
        capture("06_liquid_glass_clear_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.LiquidGlass, darkThemeOverride = false)
        }
    }

    @Test
    fun liquidGlassTintedLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false).copy(
                liquidGlassMode = SystemGlassConfig.MODE_FROSTED_TINTED,
            ),
            previewOnly = true,
        )
        capture("07_liquid_glass_tinted_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.LiquidGlass, darkThemeOverride = false)
        }
    }

    @Test
    fun displayBrightnessLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false),
            previewOnly = true,
        )
        capture("08_display_brightness_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.DisplayBrightness, darkThemeOverride = false)
        }
    }

    @Test
    fun generalAboutLight() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = false),
            previewOnly = true,
        )
        capture("09_general_about_light") {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.About, darkThemeOverride = false)
        }
    }

    @Test
    fun batterySuperVoocDark() {
        val ctrl = SystemSettingsController(
            context = null,
            initialState = SystemSettingsState.previewRoot(darkMode = true).copy(
                batteryLevel = 86,
                batteryCharging = true,
                chargeLimit80 = true,
            ),
            previewOnly = true,
        )
        capture("10_battery_supervooc_dark", dark = true) {
            IosSettingsApp(controller = ctrl, initialRoute = SettingsRoute.Battery, darkThemeOverride = true)
        }
    }
}
