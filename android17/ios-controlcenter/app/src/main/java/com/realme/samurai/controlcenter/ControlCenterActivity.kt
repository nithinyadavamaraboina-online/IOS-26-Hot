package com.realme.samurai.controlcenter

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.service.ControlCenterOverlayService
import com.realme.samurai.controlcenter.ui.glass.rememberCcGlassConfig
import com.realme.samurai.controlcenter.ui.screens.IosSheetMode
import com.realme.samurai.controlcenter.ui.screens.IosTopOverlayRootScreen
import com.realme.samurai.controlcenter.ui.theme.IosControlCenterTheme

class ControlCenterActivity : ComponentActivity() {

    private val requestedModeState = mutableStateOf(IosSheetMode.CONTROL_CENTER)

    private fun canShowNotifications(): Boolean {
        val unlocked = try {
            getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (_: Throwable) {
            false
        }
        val locked = try {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        } catch (_: Throwable) {
            true
        }
        val overlayLockShowing = ControlCenterOverlayService.activeInstance?.isLockScreenCurrentlyEngaged() == true
        return unlocked && !locked && !overlayLockShowing
    }

    private fun resolveModeFromIntent(src: Intent?): IosSheetMode {
        val isNotif = src?.action == ControlCenterOverlayService.ACTION_OPEN_NOTIFICATIONS ||
            src?.getStringExtra("mode") == "notifications"
        return if (isNotif && canShowNotifications()) {
            IosSheetMode.NOTIFICATION_CENTER
        } else {
            IosSheetMode.CONTROL_CENTER
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedModeState.value = resolveModeFromIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        ControlCenterOverlayService.ensureRunning(applicationContext)
        requestedModeState.value = resolveModeFromIntent(intent)

        setContent {
            val controller = remember {
                ControlCenterController(applicationContext).also { ctrl ->
                    ctrl.onRequestDismissAndRun = { afterDismiss ->
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                        afterDismiss()
                    }
                }
            }
            DisposableEffect(controller) {
                onDispose { controller.destroy() }
            }

            val glassConfig = rememberCcGlassConfig(applicationContext)
            var sheetMode by requestedModeState

            IosControlCenterTheme {
                IosTopOverlayRootScreen(
                    mode = sheetMode,
                    controller = controller,
                    glassConfig = glassConfig,
                    openProgress = 1f,
                    onSwitchMode = { target ->
                        if (target != IosSheetMode.NOTIFICATION_CENTER || canShowNotifications()) {
                            sheetMode = target
                        }
                    },
                    onDismiss = {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    },
                )
            }
        }
    }
}
