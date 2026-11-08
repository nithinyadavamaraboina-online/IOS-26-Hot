package com.realme.samurai.iossettings.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.ui.theme.IosSettingsTheme
import com.realme.samurai.iossettings.ui.theme.IosTheme
import com.realme.samurai.iossettings.ui.theme.IosType

private val UiKitPushEasing = CubicBezierEasing(0.20f, 0.90f, 0.30f, 1.0f)

@Composable
fun IosSettingsApp(
    controller: SystemSettingsController,
    initialRoute: SettingsRoute = SettingsRoute.Root,
    darkThemeOverride: Boolean? = null,
) {
    val backStack = remember(initialRoute) {
        if (initialRoute == SettingsRoute.Root) {
            mutableStateListOf<SettingsRoute>(SettingsRoute.Root)
        } else {
            mutableStateListOf(SettingsRoute.Root, initialRoute)
        }
    }

    val currentRoute = backStack.lastOrNull() ?: SettingsRoute.Root
    val canPop = backStack.size > 1

    BackHandler(enabled = canPop) {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    fun push(route: SettingsRoute) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    val resolvedDark = darkThemeOverride ?: controller.state.darkMode

    IosSettingsTheme(darkTheme = resolvedDark) {
        val colors = IosTheme.colors
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.groupedBackground),
        ) {
            AnimatedContent(
                targetState = backStack.size to currentRoute,
                transitionSpec = {
                    val pushing = targetState.first >= initialState.first
                    if (pushing) {
                        slideInHorizontally(
                            animationSpec = tween(320, easing = UiKitPushEasing),
                            initialOffsetX = { fullWidth -> fullWidth },
                        ) + fadeIn(tween(180)) togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(320, easing = UiKitPushEasing),
                                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                            ) + fadeOut(tween(240))
                    } else {
                        slideInHorizontally(
                            animationSpec = tween(300, easing = UiKitPushEasing),
                            initialOffsetX = { fullWidth -> -fullWidth / 3 },
                        ) + fadeIn(tween(180)) togetherWith
                            slideOutHorizontally(
                                animationSpec = tween(300, easing = UiKitPushEasing),
                                targetOffsetX = { fullWidth -> fullWidth },
                            ) + fadeOut(tween(200))
                    }
                },
                label = "iosSettingsNav",
            ) { (_, route) ->
                when (route) {
                    SettingsRoute.Root -> RootSettingsScreen(controller = controller, onNavigate = ::push)
                    SettingsRoute.Account -> AccountSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Wifi -> WifiSettingsScreen(controller = controller, onNavigate = ::push, onBack = ::pop)
                    SettingsRoute.WifiNetworkDetail -> WifiNetworkDetailScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Bluetooth -> BluetoothSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Cellular -> CellularSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Hotspot -> HotspotSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Battery -> BatterySettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.General -> GeneralSettingsScreen(controller = controller, onNavigate = ::push, onBack = ::pop)
                    SettingsRoute.About -> AboutSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.SoftwareUpdate -> SoftwareUpdateScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Storage -> StorageSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.DateTime -> DateTimeSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.LanguageRegion -> LanguageRegionScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Reset -> ResetSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Accessibility -> AccessibilitySettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Camera -> CameraSettingsScreen(onBack = ::pop)
                    SettingsRoute.ControlCenter -> ControlCenterScreen(onBack = ::pop)
                    SettingsRoute.DisplayBrightness -> DisplayBrightnessScreen(controller = controller, onNavigate = ::push, onBack = ::pop)
                    SettingsRoute.LiquidGlass -> LiquidGlassSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.AutoLock -> AutoLockScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.HomeScreen -> HomeScreenSettings(controller = controller, onBack = ::pop)
                    SettingsRoute.SearchSettings -> SearchSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Wallpaper -> WallpaperSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Notifications -> NotificationsSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.SoundsHaptics -> SoundsHapticsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Focus -> FocusSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.ScreenTime -> ScreenTimeScreen(onBack = ::pop)
                    SettingsRoute.Passcode -> PasscodeSettingsScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.EmergencySos -> EmergencySosScreen(onBack = ::pop)
                    SettingsRoute.PrivacySecurity -> PrivacySecurityScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.Apps -> AppsSettingsScreen(controller = controller, onNavigate = ::push, onBack = ::pop)
                    is SettingsRoute.AppDetail -> AppDetailScreen(route = route, controller = controller, onBack = ::pop)
                    SettingsRoute.RealmeHardware -> RealmeHardwareScreen(controller = controller, onBack = ::pop)
                    SettingsRoute.DeveloperOptions -> DeveloperOptionsScreen(controller = controller, onBack = ::pop)
                }
            }

            // Subtle floating iOS Dynamic Island-style status pill when a system action confirms
            val banner = controller.state.statusBanner
            AnimatedVisibility(
                visible = !banner.isNullOrBlank(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + 56.dp),
            ) {
                Box(
                    modifier = Modifier
                        .shadow(8.dp, CircleShape, ambientColor = Color(0x28000000), spotColor = Color(0x36000000))
                        .clip(CircleShape)
                        .background(if (colors.isDark) Color(0xF02C2C2E) else Color(0xF5FFFFFF))
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = banner.orEmpty(),
                        style = IosType.subheadline,
                        color = colors.primaryText,
                    )
                }
            }
        }
    }
}
