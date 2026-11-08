package com.realme.samurai.messages

import android.provider.Settings
import androidx.compose.ui.unit.dp
import com.realme.samurai.messages.ui.glass.GlassStyle
import com.realme.samurai.messages.ui.glass.SystemGlassConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies live `Settings.Global` synchronization with `iOS Styler`:
 * - `samurai_liquid_glass_mode` (`0` = Clear Refractive, `1` = Frosted Tinted, `2` = Solid Matte)
 * - `samurai_ios26_refraction_pct` (`0..150`, `0%` -> true `0dp` amount & `0f` chroma)
 * - `samurai_ios26_frost_pct` (`0..150`, `0%` -> true `0dp` water-clear crystal blur)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemGlassSyncTest {

    @Test
    fun defaultsToClear20PctWhenUnset() {
        val ctx = RuntimeEnvironment.getApplication()
        val cfg = SystemGlassConfig.read(ctx)
        assertEquals(SystemGlassConfig.MODE_CLEAR_REFRACTIVE, cfg.mode)
        assertEquals(20, cfg.refractionPct)
        assertEquals(20, cfg.frostPct)
        assertEquals(0.20f, cfg.refractionScale, 0.0001f)
        assertEquals(0.20f, cfg.frostScale, 0.0001f)
    }

    @Test
    fun zeroPercentYieldsTrueZeroRefractionAndCrystalZeroBlur() {
        val ctx = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_LIQUID_GLASS_MODE, 0)
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_REFRACTION_PCT, 0)
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_FROST_PCT, 0)

        val cfg = SystemGlassConfig.read(ctx)
        val scaled = GlassStyle().scaledBy(cfg)
        assertEquals(0.dp, scaled.blur)
        assertEquals(0.dp, scaled.refractionAmount)
        assertEquals(0f, scaled.dispersion, 0.0001f)
    }

    @Test
    fun frostedTintedAndSolidMatteModesSyncCleanly() {
        val ctx = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_LIQUID_GLASS_MODE, 1)
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_REFRACTION_PCT, 150)
        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_FROST_PCT, 120)

        val frosted = SystemGlassConfig.read(ctx)
        assertTrue(frosted.isFrostedTinted)
        assertEquals(1.5f, frosted.refractionScale, 0.0001f)
        assertEquals(1.2f, frosted.frostScale, 0.0001f)

        Settings.Global.putInt(ctx.contentResolver, SystemGlassConfig.KEY_LIQUID_GLASS_MODE, 2)
        val matte = SystemGlassConfig.read(ctx)
        assertTrue(matte.isSolidMatte)
        val matteStyle = GlassStyle().scaledBy(matte)
        assertEquals(0.dp, matteStyle.blur)
        assertEquals(0.dp, matteStyle.refractionAmount)
        assertEquals(0f, matteStyle.dispersion, 0.0001f)
    }
}
