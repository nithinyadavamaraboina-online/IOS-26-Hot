package com.realme.samurai.messages.ui.glass

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Live system-wide Liquid Glass parameters synchronized with `iOS Styler` via
 * `Settings.Global` (`samurai_liquid_glass_mode`, `samurai_ios26_refraction_pct`,
 * `samurai_ios26_frost_pct`). All platform reads and observer hooks are guarded
 * with `runCatching` so Compose previews and Robolectric tests never fail.
 */
@Immutable
data class SystemGlassConfig(
    /** 0 = Clear Refractive, 1 = Frosted Tinted, 2 = Solid Matte (default 0). */
    val mode: Int = MODE_CLEAR_REFRACTIVE,
    /** 0..150, default 20 -> scale = pct / 100f (0% = 0dp amount & 0f chroma). */
    val refractionPct: Int = DEFAULT_PCT,
    /** 0..150, default 20 -> scale = pct / 100f (0% = 0dp blur water-clear crystal). */
    val frostPct: Int = DEFAULT_PCT,
) {
    val refractionScale: Float get() = refractionPct.coerceIn(0, MAX_PCT) / 100f
    val frostScale: Float get() = frostPct.coerceIn(0, MAX_PCT) / 100f
    val isClearRefractive: Boolean get() = mode == MODE_CLEAR_REFRACTIVE
    val isFrostedTinted: Boolean get() = mode == MODE_FROSTED_TINTED
    val isSolidMatte: Boolean get() = mode == MODE_SOLID_MATTE

    companion object {
        const val MODE_CLEAR_REFRACTIVE = 0
        const val MODE_FROSTED_TINTED = 1
        const val MODE_SOLID_MATTE = 2
        const val DEFAULT_PCT = 20
        const val MAX_PCT = 150

        const val KEY_LIQUID_GLASS_MODE = "samurai_liquid_glass_mode"
        const val KEY_REFRACTION_PCT = "samurai_ios26_refraction_pct"
        const val KEY_FROST_PCT = "samurai_ios26_frost_pct"

        fun read(context: Context): SystemGlassConfig = runCatching {
            val cr = context.contentResolver
            val mode = Settings.Global.getInt(cr, KEY_LIQUID_GLASS_MODE, MODE_CLEAR_REFRACTIVE)
                .coerceIn(MODE_CLEAR_REFRACTIVE, MODE_SOLID_MATTE)
            val refraction = Settings.Global.getInt(cr, KEY_REFRACTION_PCT, DEFAULT_PCT)
                .coerceIn(0, MAX_PCT)
            val frost = Settings.Global.getInt(cr, KEY_FROST_PCT, DEFAULT_PCT)
                .coerceIn(0, MAX_PCT)
            SystemGlassConfig(
                mode = mode,
                refractionPct = refraction,
                frostPct = frost,
            )
        }.getOrDefault(SystemGlassConfig())
    }
}

val LocalSystemGlassConfig = staticCompositionLocalOf { SystemGlassConfig() }

@Composable
fun rememberSystemGlassConfig(): SystemGlassConfig {
    if (LocalInspectionMode.current) return SystemGlassConfig()
    val context = LocalContext.current
    var config by remember(context) { mutableStateOf(SystemGlassConfig.read(context)) }

    DisposableEffect(context) {
        val resolver = runCatching { context.contentResolver }.getOrNull()
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        val observer = if (resolver != null) {
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    config = SystemGlassConfig.read(context)
                }
            }
        } else null

        if (resolver != null && observer != null) {
            runCatching {
                listOf(
                    SystemGlassConfig.KEY_LIQUID_GLASS_MODE,
                    SystemGlassConfig.KEY_REFRACTION_PCT,
                    SystemGlassConfig.KEY_FROST_PCT,
                ).forEach { key ->
                    Settings.Global.getUriFor(key)?.let { uri ->
                        resolver.registerContentObserver(uri, false, observer)
                    }
                }
                config = SystemGlassConfig.read(context)
            }
        }

        onDispose {
            if (resolver != null && observer != null) {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
        }
    }

    return config
}
