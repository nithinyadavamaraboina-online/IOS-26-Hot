package com.realme.samurai.controlcenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.service.IosNotificationAction
import com.realme.samurai.controlcenter.service.IosNotificationItem
import com.realme.samurai.controlcenter.service.IosNotificationRepository
import com.realme.samurai.controlcenter.service.UdfpsSensorGeometry
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.Ios26HomeIndicatorBar
import com.realme.samurai.controlcenter.ui.screens.CcModalSheet
import com.realme.samurai.controlcenter.ui.screens.ControlCenterRootScreen
import com.realme.samurai.controlcenter.ui.screens.IosLockScreenRoot
import com.realme.samurai.controlcenter.ui.screens.NotificationCenterRootScreen
import com.realme.samurai.controlcenter.ui.theme.IosControlCenterTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ControlCenterDesignShots {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            IosControlCenterTheme {
                content()
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun cc01ClearPlaying() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("cc_01_clear_playing") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
            )
        }
    }

    @Test
    fun cc02TintedIdle() {
        val ctrl = ControlCenterController.previewTintedIdle()
        capture("cc_02_tinted_idle") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = true, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
            )
        }
    }

    @Test
    fun cc03ExpandedConnectivity() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("cc_03_expanded_connectivity") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
                initialPage = 2,
            )
        }
    }

    @Test
    fun cc04ExpandedMedia() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("cc_04_expanded_media") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
                initialPage = 1,
            )
        }
    }

    @Test
    fun cc05PowerMenu() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("cc_05_power_menu") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
                initialModal = CcModalSheet.POWER_MENU,
            )
        }
    }

    @Test
    fun cc06ActiveToggles90Hz() {
        val base = ControlCenterController.previewClearPlaying()
        val activeState = base.state.copy(
            smooth90Hz = true,
            dcDimming = true,
            flashlightOn = true,
            lowPowerMode = true,
            darkMode = true,
            timerRemainingSec = 300,
        )
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = activeState,
        )
        capture("cc_06_active_toggles_90hz") {
            ControlCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
            )
        }
    }

    private fun sampleNotifications(): List<IosNotificationItem> {
        val now = System.currentTimeMillis()
        return listOf(
            IosNotificationItem(
                key = "n1",
                packageName = "com.realme.ota",
                appName = "System Update",
                title = "System Update Complete",
                body = "Updated to Android 17 (v1.7.1) • Blended active Liquid Glass controls & 3D refractive clock active.",
                postTimeMs = now - 15_000L,
                isClearable = true,
                autoCancel = true,
            ),
            IosNotificationItem(
                key = "n2",
                packageName = "com.google.android.apps.messaging",
                appName = "Messages",
                title = "Nithin",
                body = "Left pull-down is now pure iOS 26 Cover Sheet and right is 3D refractive Liquid Glass!",
                postTimeMs = now - 3 * 60_000L,
                isClearable = true,
                autoCancel = true,
                actions = listOf(
                    IosNotificationAction("Reply", null),
                    IosNotificationAction("Mark Read", null),
                ),
            ),
            IosNotificationItem(
                key = "n3",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Realme X2 Pro 90Hz",
                body = "Zero Android shade glitching — StatusBarManager lockout + Z-layer 31 active.",
                postTimeMs = now - 14 * 60_000L,
                isClearable = true,
                autoCancel = true,
            ),
        )
    }

    @Test
    fun nc01ClearNotifications() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("nc_01_clear_notifications") {
            NotificationCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
                previewClockText = "9:41",
                previewDateText = "Tuesday, November 3",
                previewNotifications = sampleNotifications(),
            )
        }
    }

    @Test
    fun nc02TintedNotifications() {
        val ctrl = ControlCenterController.previewTintedIdle()
        capture("nc_02_tinted_notifications") {
            NotificationCenterRootScreen(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = true, refractionPct = 20, frostPct = 20),
                openProgress = 1f,
                previewClockText = "9:41",
                previewDateText = "Tuesday, November 3",
                previewNotifications = sampleNotifications(),
            )
        }
    }

    private fun loadShippedIos26Wallpaper(pngName: String): Bitmap? {
        val candidates = listOf(
            "../../work/a17/wallpapers/$pngName",
            "../../work/a17/wallpapers/default_wallpaper.png",
            "/mnt/c/Users/paink/.gemini/antigravity/worktrees/goofy-davinci/port_android_seventeen_realme/work/a17/wallpapers/default_wallpaper.png",
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.isFile) {
                val decoded = BitmapFactory.decodeFile(f.absolutePath)
                if (decoded != null) return decoded
            }
        }
        return null
    }

    @Test
    fun ls01LiquidGlassIdle() {
        val base = ControlCenterController.previewClearPlaying()
        val idleState = base.state.copy(
            mediaPlaying = false,
            mediaTitle = "Not Playing",
            mediaArtist = "",
            batteryPct = 86,
            isCharging = false,
            wallpaperBitmap = loadShippedIos26Wallpaper("wp_sky_dark.png"),
        )
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = idleState,
        )
        capture("ls_01_liquid_glass_idle") {
            IosLockScreenRoot(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                previewClockText = "2:05",
                previewDateText = "Tue Nov 4",
                previewClockStyle = 0,
                previewNotifications = emptyList(),
            )
        }
    }

    @Test
    fun ls02StackedNotifications() {
        val base = ControlCenterController.previewClearPlaying()
        val notifState = base.state.copy(
            mediaPlaying = false,
            mediaTitle = "Not Playing",
            mediaArtist = "",
            batteryPct = 92,
            wallpaperBitmap = loadShippedIos26Wallpaper("wp_sky_light.png"),
        )
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = notifState,
        )
        capture("ls_02_stacked_notifications") {
            IosLockScreenRoot(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                previewClockText = "9:41",
                previewDateText = "Tue Nov 4",
                previewClockStyle = 0,
                previewNotifications = sampleNotifications(),
            )
        }
    }

    @Test
    fun ls03ExpandedAppleMusic() {
        val ctrl = ControlCenterController.previewClearPlaying()
        capture("ls_03_expanded_apple_music") {
            IosLockScreenRoot(
                controller = ctrl,
                glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                previewClockText = "9:41",
                previewDateText = "Tue Nov 3",
                previewClockStyle = 0,
                previewExpandedMediaArt = true,
                previewNotifications = emptyList(),
            )
        }
    }

    private fun createStructuredTestWallpaper(width: Int = 540, height: Int = 1200): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(0xFF103478.toInt(), 0xFF1E78B4.toInt(), 0xFFE86F3A.toInt(), 0xFF281C54.toInt()),
                floatArrayOf(0f, 0.38f, 0.70f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Sharp high-contrast horizontal & vertical studio bars so meniscus displacement and unblurred sharpness are measurable
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 24) {
            val y0 = (i * height) / 24f
            stripePaint.color = if (i % 2 == 0) 0x44FFFFFF else 0x38081020
            canvas.drawRect(0f, y0, width.toFloat(), y0 + height / 48f, stripePaint)
        }
        for (j in 0 until 10) {
            val x0 = (j * width) / 10f
            stripePaint.color = if (j % 2 == 0) 0x30FFD480 else 0x28002448
            canvas.drawRect(x0, 0f, x0 + width / 22f, height.toFloat(), stripePaint)
        }
        return bmp
    }

    private fun saveAndDecodeShot(name: String): Bitmap {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val outPath = "build/outputs/roborazzi/$name.png"
        compose.onRoot().captureRoboImage(outPath)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(outPath, opts)
        assertNotNull("Expected Roborazzi PNG at $outPath", decoded)
        return decoded!!
    }

    private fun captureTransientBitmap(name: String): Bitmap {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val outPath = "build/tmp/roborazzi_check/$name.png"
        File(outPath).parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(outPath)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(outPath, opts)
        assertNotNull("Expected transient PNG at $outPath", decoded)
        return decoded!!
    }

    private fun pixelLuma(argb: Int): Float {
        return 0.2126f * Color.red(argb) + 0.7152f * Color.green(argb) + 0.0722f * Color.blue(argb)
    }

    @Test
    fun notificationCenter_midSlide55_rendersRefractiveGlassRim() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(wallpaperBitmap = createStructuredTestWallpaper()),
        )
        val progressState = mutableFloatStateOf(0.55f)
        val velocityState = mutableFloatStateOf(950f)
        val ncCfgState = mutableStateOf(CcGlassConfig(isTinted = false, refractionPct = 28, frostPct = 22))

        compose.setContent {
            IosControlCenterTheme {
                NotificationCenterRootScreen(
                    controller = ctrl,
                    glassConfig = ncCfgState.value,
                    openProgress = progressState.floatValue,
                    sheetVelocityPxPerSec = velocityState.floatValue,
                    gesturePillEnabled = true,
                    previewClockText = "9:41",
                    previewDateText = "Wednesday, November 5",
                    previewNotifications = sampleNotifications(),
                )
            }
        }

        val midBmp = saveAndDecodeShot("nc_03_mid_slide_55_rim")
        val dens = compose.density.density
        val edgeY = (0.55f * midBmp.height).roundToInt()
        val midX = midBmp.width / 2

        // 1. Interior above edgeY is rendered opaque through the sliding glass slab
        val interiorY = (edgeY - (56f * dens).roundToInt()).coerceIn(8, midBmp.height - 8)
        val interiorPx = midBmp.getPixel(midX, interiorY)
        assertTrue(
            "Expected opaque NC sliding sheet interior at y=$interiorY, got alpha=${Color.alpha(interiorPx)}",
            Color.alpha(interiorPx) >= 245,
        )

        // 2. Specular rim & meniscus band right above edgeY is brighter/modulated relative to deeper slab rows
        val sampleX = (midBmp.width * 0.22f).roundToInt() // Away from centered Home Indicator pill
        val deepRefPx = midBmp.getPixel(sampleX, (edgeY - (44f * dens).roundToInt()).coerceIn(4, midBmp.height - 4))
        var maxRimLuma = 0f
        val rimScanStart = (edgeY - (8f * dens).roundToInt()).coerceAtLeast(2)
        val rimScanEnd = (edgeY - 1).coerceAtLeast(rimScanStart)
        for (y in rimScanStart..rimScanEnd) {
            val l = pixelLuma(midBmp.getPixel(sampleX, y))
            if (l > maxRimLuma) maxRimLuma = l
        }
        assertTrue(
            "Expected specular rim brightening near edgeY=$edgeY (maxRimLuma=$maxRimLuma vs deepLuma=${pixelLuma(deepRefPx)})",
            maxRimLuma > pixelLuma(deepRefPx) + 6f,
        )

        // 3. Exterior below edgeY + 12dp is completely transparent (no unclipped backdrop bleed)
        val exteriorY = (edgeY + (12f * dens).roundToInt()).coerceAtMost(midBmp.height - 2)
        assertEquals(
            "Expected transparent exterior below moving NC rim at y=$exteriorY",
            0,
            Color.alpha(midBmp.getPixel(midX, exteriorY)),
        )

        // 4. Fully closed (`openProgress = 0f`) produces zero row-0 edge bleed
        progressState.floatValue = 0f
        velocityState.floatValue = 0f
        val closedBmp = saveAndDecodeShot("nc_04_closed_00")
        assertEquals("Expected zero alpha at row y=0 when openProgress=0f", 0, Color.alpha(closedBmp.getPixel(midX, 0)))

        // 5. Fully open (`openProgress = 1f`) relaxes bottom corner radius so bottom corners are covered
        progressState.floatValue = 1f
        val openBmp = saveAndDecodeShot("nc_05_full_open_100")
        assertTrue(
            "Expected bottom-left display corner coverage when fully open",
            Color.alpha(openBmp.getPixel(4, openBmp.height - 4)) >= 240,
        )

        // 6. Strict Frost=0% contract on Notification Center: unblurred sharp wallpaper stripe contrast
        ncCfgState.value = CcGlassConfig(isTinted = false, refractionPct = 0, frostPct = 0)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        val ncZeroFrostBmp = saveAndDecodeShot("nc_06_zero_frost_contract")
        var ncMinLuma = 255f
        var ncMaxLuma = 0f
        for (y in (ncZeroFrostBmp.height * 0.14f).roundToInt()..(ncZeroFrostBmp.height * 0.28f).roundToInt() step 3) {
            val l = pixelLuma(ncZeroFrostBmp.getPixel((ncZeroFrostBmp.width * 0.04f).roundToInt(), y))
            if (l < ncMinLuma) ncMinLuma = l
            if (l > ncMaxLuma) ncMaxLuma = l
        }
        assertTrue(
            "Expected crisp unblurred NC wallpaper stripes at frostPct=0 (span=${ncMaxLuma - ncMinLuma})",
            (ncMaxLuma - ncMinLuma) >= 14f,
        )
    }

    @Test
    fun controlCenter_midSlide55_rendersRefractiveGlassRim() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                wallpaperBitmap = createStructuredTestWallpaper(),
                brightness = 0.50f,
                volume = 0.45f,
            ),
        )
        val cfgState = mutableStateOf(
            CcGlassConfig(isTinted = false, isSolidMatte = false, refractionPct = 28, frostPct = 20)
        )
        val ccOpenState = mutableFloatStateOf(0.55f)

        compose.setContent {
            IosControlCenterTheme {
                ControlCenterRootScreen(
                    controller = ctrl,
                    glassConfig = cfgState.value,
                    openProgress = ccOpenState.floatValue,
                    sheetVelocityPxPerSec = 900f,
                    gesturePillEnabled = true,
                )
            }
        }

        val clearBmp = saveAndDecodeShot("cc_07_mid_slide_55_rim")
        val dens = compose.density.density
        val edgeY = (0.55f * clearBmp.height).roundToInt()
        val sampleX = (clearBmp.width * 0.18f).roundToInt()
        val exteriorY = (edgeY + (12f * dens).roundToInt()).coerceAtMost(clearBmp.height - 2)

        assertTrue(
            "Expected opaque CC sliding sheet interior above edgeY=$edgeY",
            Color.alpha(clearBmp.getPixel(sampleX, edgeY - (48f * dens).roundToInt())) >= 245,
        )
        assertEquals(
            "Expected transparent exterior below moving CC rim at y=$exteriorY",
            0,
            Color.alpha(clearBmp.getPixel(sampleX, exteriorY)),
        )

        // Verify zero-refraction + zero-frost (`refractionPct = 0, frostPct = 0`) renders crisp unblurred stripes
        cfgState.value = CcGlassConfig(isTinted = false, isSolidMatte = false, refractionPct = 0, frostPct = 0)
        val zeroRefrBmp = saveAndDecodeShot("cc_08_mid_slide_55_zero_refr")
        assertTrue(
            "Expected valid interior alpha at refractionPct=0, frostPct=0",
            Color.alpha(zeroRefrBmp.getPixel(sampleX, edgeY - (48f * dens).roundToInt())) >= 245,
        )
        var ccMinLuma = 255f
        var ccMaxLuma = 0f
        for (y in (zeroRefrBmp.height * 0.06f).roundToInt()..(zeroRefrBmp.height * 0.22f).roundToInt() step 3) {
            val l = pixelLuma(zeroRefrBmp.getPixel((zeroRefrBmp.width * 0.03f).roundToInt(), y))
            if (l < ccMinLuma) ccMinLuma = l
            if (l > ccMaxLuma) ccMaxLuma = l
        }
        assertTrue(
            "Expected crisp unblurred CC wallpaper stripes at frostPct=0 (span=${ccMaxLuma - ccMinLuma})",
            (ccMaxLuma - ccMinLuma) >= 14f,
        )

        // Verify Solid Matte (`isSolidMatte = true` -> 0xEB14171F dark matte slab, zero wallpaper bleed)
        cfgState.value = CcGlassConfig(isTinted = false, isSolidMatte = true, refractionPct = 20, frostPct = 20)
        val matteBmp = saveAndDecodeShot("cc_09_mid_slide_55_solid_matte")
        val matteSampleX = (8f * dens).roundToInt().coerceIn(4, matteBmp.width - 4)
        val matteSampleY = (20f * dens).roundToInt().coerceIn(4, edgeY - 8)
        val mattePx = matteBmp.getPixel(matteSampleX, matteSampleY)
        assertTrue(
            "Expected Solid Matte alpha ~0xEB (235), got ${Color.alpha(mattePx)}",
            Color.alpha(mattePx) in 225..246,
        )
        assertTrue(
            "Expected dark matte RGB near 0x14171F without orange/blue wallpaper bleed, got r=${Color.red(mattePx)} g=${Color.green(mattePx)} b=${Color.blue(mattePx)}",
            Color.red(mattePx) in 14..32 && Color.green(mattePx) in 16..36 && Color.blue(mattePx) in 22..44,
        )
        assertEquals(
            "Expected transparent exterior below Solid Matte CC rim",
            0,
            Color.alpha(matteBmp.getPixel(sampleX, exteriorY)),
        )

        // Verify Solid Matte renders high-contrast `sliderFill` inside the 1x2 Brightness & Volume capsule sliders
        ccOpenState.floatValue = 1.0f
        val matteOpenBmp = saveAndDecodeShot("cc_10_full_open_solid_matte_sliders")
        // Column 3 center (~61.5% X) crosses the 1x2 Brightness slider (`brightness = 0.50f`)
        val brColX = (matteOpenBmp.width * 0.615f).roundToInt()
        var sliderMinLuma = 255f
        var sliderMaxLuma = 0f
        for (y in (matteOpenBmp.height * 0.30f).roundToInt()..(matteOpenBmp.height * 0.50f).roundToInt() step 3) {
            val l = pixelLuma(matteOpenBmp.getPixel(brColX, y))
            if (l < sliderMinLuma) sliderMinLuma = l
            if (l > sliderMaxLuma) sliderMaxLuma = l
        }
        assertTrue(
            "Expected high-contrast Solid Matte Brightness slider fill column (min=$sliderMinLuma, max=$sliderMaxLuma)",
            (sliderMaxLuma - sliderMinLuma) >= 95f,
        )
    }

    @Test
    fun lockScreen_midCurtainLift42_rendersRisingGlassCurtain() {
        val wp = createStructuredTestWallpaper()
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                wallpaperBitmap = wp,
            ),
        )

        compose.setContent {
            IosControlCenterTheme {
                IosLockScreenRoot(
                    controller = ctrl,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 26, frostPct = 22),
                    curtainDismissProgress = 0.42f,
                    curtainVelocityPxPerSec = -1150f,
                    gesturePillEnabled = true,
                    isSecureKeyguard = false,
                    previewClockText = "9:41",
                    previewDateText = "Wed Nov 5",
                    previewClockStyle = 0,
                    previewNotifications = emptyList(),
                )
            }
        }

        val bmp = saveAndDecodeShot("ls_04_mid_curtain_lift_42")
        val dens = compose.density.density
        val edgeY = ((1f - 0.42f) * bmp.height).roundToInt()
        val sampleX = (bmp.width * 0.16f).roundToInt()

        // 1. Interior above the rising curtain lip remains sharp & opaque
        val interiorY = (edgeY - (64f * dens).roundToInt()).coerceIn(10, bmp.height - 10)
        assertTrue(
            "Expected opaque Lock Screen curtain interior at y=$interiorY",
            Color.alpha(bmp.getPixel(sampleX, interiorY)) >= 245,
        )
        // Verify sharp vertical contrast across adjacent wallpaper stripes in unblurred Lock Screen interior
        var minStripeLuma = 255f
        var maxStripeLuma = 0f
        val stripeSpanStart = (bmp.height * 0.28f).roundToInt()
        val stripeSpanEnd = (bmp.height * 0.42f).roundToInt()
        for (y in stripeSpanStart..stripeSpanEnd step 3) {
            val l = pixelLuma(bmp.getPixel((bmp.width * 0.06f).roundToInt(), y))
            if (l < minStripeLuma) minStripeLuma = l
            if (l > maxStripeLuma) maxStripeLuma = l
        }
        assertTrue(
            "Expected unblurred sharp wallpaper contrast inside Lock Screen curtain (span=${maxStripeLuma - minStripeLuma})",
            (maxStripeLuma - minStripeLuma) >= 14f,
        )

        // 2. Rising meniscus rim near edgeY = 0.58 * H shows specular highlight
        val refDeepLuma = pixelLuma(bmp.getPixel(sampleX, (edgeY - (42f * dens).roundToInt()).coerceAtLeast(4)))
        var maxRimLuma = 0f
        for (y in (edgeY - (8f * dens).roundToInt()).coerceAtLeast(2) until edgeY) {
            val l = pixelLuma(bmp.getPixel(sampleX, y))
            if (l > maxRimLuma) maxRimLuma = l
        }
        assertTrue(
            "Expected specular rim along rising Lock Screen curtain edgeY=$edgeY (maxRim=$maxRimLuma vs ref=$refDeepLuma)",
            maxRimLuma > refDeepLuma + 5f,
        )

        // 3. Exterior below rising curtain (`y > edgeY + 12dp`) is transparent (`outsideBackdropAlpha = 0f`)
        val exteriorY = (edgeY + (12f * dens).roundToInt()).coerceAtMost(bmp.height - 2)
        assertEquals(
            "Expected unoccluded transparent exterior below rising Lock Screen curtain at y=$exteriorY",
            0,
            Color.alpha(bmp.getPixel(sampleX, exteriorY)),
        )
    }

    @Test
    fun lockScreen_zeroRefractionAndFrost_honorsStrictOpticalContract() {
        val wp = createStructuredTestWallpaper()
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                wallpaperBitmap = wp,
            ),
        )
        val refrState = mutableStateOf(CcGlassConfig(isTinted = false, refractionPct = 0, frostPct = 0))
        val liftState = mutableFloatStateOf(0.42f)

        compose.setContent {
            IosControlCenterTheme {
                IosLockScreenRoot(
                    controller = ctrl,
                    glassConfig = refrState.value,
                    curtainDismissProgress = liftState.floatValue,
                    curtainVelocityPxPerSec = -900f,
                    gesturePillEnabled = true,
                    isSecureKeyguard = false,
                    previewClockText = "8:08",
                    previewDateText = "Thu Nov 6",
                    previewClockStyle = 0,
                    previewNotifications = sampleNotifications(),
                )
            }
        }

        val zeroBmp = saveAndDecodeShot("ls_05_zero_refr_frost_contract")
        val dens = compose.density.density
        val edgeY = ((1f - 0.42f) * zeroBmp.height).roundToInt()

        // 1. At Frost=0%, even with stacked notifications present, interior wallpaper stripes remain unblurred & crisp
        var minStripeLuma = 255f
        var maxStripeLuma = 0f
        val stripeStart = (zeroBmp.height * 0.24f).roundToInt()
        val stripeEnd = (zeroBmp.height * 0.36f).roundToInt()
        for (y in stripeStart..stripeEnd step 3) {
            val l = pixelLuma(zeroBmp.getPixel((zeroBmp.width * 0.05f).roundToInt(), y))
            if (l < minStripeLuma) minStripeLuma = l
            if (l > maxStripeLuma) maxStripeLuma = l
        }
        assertTrue(
            "Expected crisp unblurred Lock Screen stripes at frostPct=0 even with notifications (span=${maxStripeLuma - minStripeLuma})",
            (maxStripeLuma - minStripeLuma) >= 14f,
        )

        // 2. Compare Lock Screen bevel + Hero Clock numerals at refractionPct=0 vs refractionPct=45
        refrState.value = CcGlassConfig(isTinted = false, refractionPct = 45, frostPct = 0)
        val highRefrBmp = saveAndDecodeShot("ls_06_high_refr_zero_frost")

        var bevelDiffSum = 0.0
        var bevelCount = 0
        val bevelY = (edgeY - (10f * dens).roundToInt()).coerceIn(6, zeroBmp.height - 6)
        for (x in (zeroBmp.width * 0.15f).roundToInt()..(zeroBmp.width * 0.85f).roundToInt() step 4) {
            val p0 = zeroBmp.getPixel(x, bevelY)
            val p1 = highRefrBmp.getPixel(x, bevelY)
            bevelDiffSum += abs(Color.red(p0) - Color.red(p1)) +
                abs(Color.green(p0) - Color.green(p1)) +
                abs(Color.blue(p0) - Color.blue(p1))
            bevelCount++
        }
        assertTrue(
            "Expected measurable Lock Screen bevel refraction delta between refractionPct=0 and 45 (avgDiff=${bevelDiffSum / max(1, bevelCount)})",
            (bevelDiffSum / max(1, bevelCount)) > 4.0,
        )

        // 3. Reset curtain lift to idle (0f) so the "8:08" Hero Clock occupies 0.10H..0.26H,
        //    and assert measurable Hero Clock numeral refraction delta between refractionPct=0 and 45.
        liftState.floatValue = 0f
        refrState.value = CcGlassConfig(isTinted = false, refractionPct = 0, frostPct = 0)
        val idleZeroBmp = captureTransientBitmap("ls_idle_clock_refr0")
        refrState.value = CcGlassConfig(isTinted = false, refractionPct = 45, frostPct = 0)
        val idleHighBmp = captureTransientBitmap("ls_idle_clock_refr45")

        var clockDiffSum = 0.0
        var clockCount = 0
        val clockScanY0 = (idleZeroBmp.height * 0.10f).roundToInt()
        val clockScanY1 = (idleZeroBmp.height * 0.26f).roundToInt()
        for (y in clockScanY0..clockScanY1 step 4) {
            for (x in (idleZeroBmp.width * 0.20f).roundToInt()..(idleZeroBmp.width * 0.80f).roundToInt() step 6) {
                val p0 = idleZeroBmp.getPixel(x, y)
                val p1 = idleHighBmp.getPixel(x, y)
                clockDiffSum += abs(Color.red(p0) - Color.red(p1)) +
                    abs(Color.green(p0) - Color.green(p1)) +
                    abs(Color.blue(p0) - Color.blue(p1))
                clockCount++
            }
        }
        assertTrue(
            "Expected measurable Hero Clock refraction delta between refractionPct=0 and 45 (avgDiff=${clockDiffSum / max(1, clockCount)})",
            (clockDiffSum / max(1, clockCount)) > 2.5,
        )
    }

    @Test
    fun homeIndicatorBar_idleAndScrubStretch() {
        val scrubState = mutableFloatStateOf(0f)
        val stretchState = mutableFloatStateOf(0f)
        val liftState = mutableFloatStateOf(0f)
        val dimState = mutableStateOf(false)

        compose.setContent {
            IosControlCenterTheme {
                Ios26HomeIndicatorBar(
                    scrubFraction = scrubState.floatValue,
                    stretchFraction = stretchState.floatValue,
                    liftFraction = liftState.floatValue,
                    idleDimmed = dimState.value,
                )
            }
        }

        fun measureCapsuleBounds(bmp: Bitmap): FloatArray {
            var minX = bmp.width
            var maxX = -1
            var sumX = 0.0
            var sumY = 0.0
            var weightSum = 0.0
            var peakAlpha = 0
            for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    val a = Color.alpha(bmp.getPixel(x, y))
                    if (a > peakAlpha) peakAlpha = a
                    if (a >= 96) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        sumX += x * a
                        sumY += y * a
                        weightSum += a
                    }
                }
            }
            assertTrue("Expected visible Home Indicator pixels", maxX > minX && weightSum > 0.0)
            return floatArrayOf(
                (maxX - minX + 1).toFloat(),
                (sumX / weightSum).toFloat(),
                (sumY / weightSum).toFloat(),
                peakAlpha.toFloat(),
            )
        }

        val dens = compose.density.density
        val idleBmp = saveAndDecodeShot("hbar_01_idle")
        val idleMetrics = measureCapsuleBounds(idleBmp)
        val idleSpanPx = idleMetrics[0]
        val idleCenterX = idleMetrics[1]
        val idleCenterY = idleMetrics[2]
        val idlePeakAlpha = idleMetrics[3]

        assertTrue(
            "Expected ~138dp idle capsule width (got ${idleSpanPx / dens}dp)",
            abs(idleSpanPx / dens - 138f) <= 6f,
        )

        scrubState.floatValue = 0.85f
        stretchState.floatValue = 1.0f
        liftState.floatValue = 0.85f
        val activeBmp = saveAndDecodeShot("hbar_02_scrub_stretch")
        val activeMetrics = measureCapsuleBounds(activeBmp)

        assertTrue(
            "Expected stretched capsule width > idle + 12dp (idle=$idleSpanPx, stretched=${activeMetrics[0]})",
            activeMetrics[0] > idleSpanPx + 12f * dens,
        )
        assertTrue(
            "Expected scrubbed capsule centroid shifted +X (idle=$idleCenterX, scrubbed=${activeMetrics[1]})",
            activeMetrics[1] > idleCenterX + 8f * dens,
        )
        assertTrue(
            "Expected lifted capsule centroid shifted -Y upward (idle=$idleCenterY, lifted=${activeMetrics[2]})",
            activeMetrics[2] < idleCenterY - 2f * dens,
        )

        scrubState.floatValue = 0f
        stretchState.floatValue = 0f
        liftState.floatValue = 0f
        dimState.value = true
        val dimBmp = saveAndDecodeShot("hbar_03_dimmed")
        val dimPeakAlpha = (0 until dimBmp.height).maxOf { y ->
            (0 until dimBmp.width).maxOf { x -> Color.alpha(dimBmp.getPixel(x, y)) }
        }
        assertTrue(
            "Expected idleDimmed capsule peak alpha ($dimPeakAlpha) < active idle peak alpha ($idlePeakAlpha)",
            dimPeakAlpha < idlePeakAlpha - 80f,
        )
    }

    @Test
    fun lockScreen_expandedNotifications_unlockAndSessionReset() {
        val wp = createStructuredTestWallpaper()
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                wallpaperBitmap = wp,
            ),
        )
        val now = System.currentTimeMillis()
        val sevenNotifications = (1..7).map { idx ->
            IosNotificationItem(
                key = "notif_$idx",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Message #$idx",
                body = "Accumulated notification card #$idx testing Lock Screen scroll-and-unlock behavior.",
                postTimeMs = now - idx * 60_000L,
                isClearable = true,
                autoCancel = true,
            )
        }

        val liveNotifications = mutableStateListOf<IosNotificationItem>().apply {
            addAll(sevenNotifications)
        }
        val sessionEpochState = mutableIntStateOf(0)
        var unlockCount = 0
        var udfpsHoldCount = 0
        var maxObservedProgress = 0f

        compose.setContent {
            IosControlCenterTheme {
                IosLockScreenRoot(
                    controller = ctrl,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                    gesturePillEnabled = true,
                    isSecureKeyguard = true,
                    isLockScreenVisible = true,
                    lockSessionEpoch = sessionEpochState.intValue,
                    previewClockText = "9:41",
                    previewDateText = "Sat Nov 7",
                    previewClockStyle = 0,
                    previewNotifications = liveNotifications,
                    onSwipeProgressChange = { prog ->
                        if (prog > maxObservedProgress) maxObservedProgress = prog
                    },
                    onUnlockRequested = {
                        unlockCount++
                    },
                    onDirectUdfpsTouch = {
                        udfpsHoldCount++
                    },
                    onDismissNotification = { key ->
                        liveNotifications.removeAll { it.key == key }
                    },
                )
            }
        }
        compose.waitForIdle()

        // 1. Dead-center click on "5 more notifications" AND "Show Less" with `isSecureKeyguard = true`:
        //    proves the ancestor Goodix UDFPS stationary hold detector NEVER Z-occludes sibling buttons!
        compose.onNodeWithText("5 more notifications").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show Less").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("5 more notifications").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show Less").assertExists()
        assertEquals("Quick taps on Show Less / N more must NOT trigger UDFPS hold", 0, udfpsHoldCount)

        // 2a. Stationary >=220ms press at exact Goodix G3 UDFPS center AND outer ring (`0.85 * radiusPx`,
        //     aligned to `[540, 2156, 95]` on 1080x2400) MUST trigger `onDirectUdfpsTouch` without opening Customize bar!
        val dens = compose.density.density
        compose.onRoot().performTouchInput {
            val spec = UdfpsSensorGeometry.resolve(null, width.toFloat(), height.toFloat())
            down(Offset(spec.centerXPx, spec.centerYPx))
            advanceEventTime(450L)
            up()
        }
        compose.waitForIdle()
        assertEquals("Stationary hold at UDFPS center must trigger onDirectUdfpsTouch", 1, udfpsHoldCount)
        compose.onNodeWithText("Glass").assertDoesNotExist()
        compose.onNodeWithText("Solid").assertDoesNotExist()

        compose.onRoot().performTouchInput {
            val spec = UdfpsSensorGeometry.resolve(null, width.toFloat(), height.toFloat())
            val uxOuter = spec.centerXPx + spec.radiusPx * 0.85f
            val uyOuter = spec.centerYPx
            down(Offset(uxOuter, uyOuter))
            advanceEventTime(550L)
            up()
        }
        compose.waitForIdle()
        assertEquals("Stationary hold at outer UDFPS ring must trigger onDirectUdfpsTouch", 2, udfpsHoldCount)
        compose.onNodeWithText("Glass").assertDoesNotExist()
        compose.onNodeWithText("Solid").assertDoesNotExist()

        // 2a-ii. Fast (<220ms) upward swipe starting directly inside the UDFPS sensor zone MUST unlock cleanly
        //        without firing `onDirectUdfpsTouch`!
        maxObservedProgress = 0f
        compose.onRoot().performTouchInput {
            val spec = UdfpsSensorGeometry.resolve(null, width.toFloat(), height.toFloat())
            val startX = spec.centerXPx
            val startY = spec.centerYPx
            val endY = height * 0.40f
            down(Offset(startX, startY))
            for (step in 1..10) {
                val frac = step / 10f
                moveTo(Offset(startX, startY + (endY - startY) * frac), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("Fast upward swipe starting inside UDFPS sensor must not fire UDFPS hold", 2, udfpsHoldCount)
        assertEquals("Fast upward swipe starting inside UDFPS sensor must unlock", 1, unlockCount)
        assertTrue("Expected curtain lift >= 0.25f on UDFPS-origin swipe-up", maxObservedProgress >= 0.25f)
        unlockCount = 0

        // 2b. Verify non-vacuous Customize bar labels: stationary >=450ms long-press OUTSIDE the UDFPS zone
        //     DOES open Customize bar ("Glass" & "Solid"), and tapping outside dismisses it cleanly.
        compose.onRoot().performTouchInput {
            val hx = width * 0.5f
            val hy = height * 0.14f
            down(Offset(hx, hy))
            advanceEventTime(550L)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Glass").assertExists()
        compose.onNodeWithText("Solid").assertExists()
        compose.onRoot().performTouchInput {
            val tx = width * 0.5f
            val ty = height * 0.10f
            down(Offset(tx, ty))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Glass").assertDoesNotExist()
        compose.onNodeWithText("Solid").assertDoesNotExist()

        // 3a. Upper-screen upward swipe (above expanded notification Column while `canScrollForward == true`)
        //     MUST unlock immediately with zero dead zone over Clock/Date/Wallpaper!
        maxObservedProgress = 0f
        compose.onRoot().performTouchInput {
            val startX = width * 0.5f
            val startY = height * 0.30f
            val endY = height * 0.04f
            down(Offset(startX, startY))
            for (step in 1..12) {
                val frac = step / 12f
                moveTo(Offset(startX, startY + (endY - startY) * frac), delayMillis = 10L)
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("Upper-screen upward swipe above expanded notifications must unlock", 1, unlockCount)
        assertTrue("Expected curtain lift >= 0.22f on upper-screen swipe (got $maxObservedProgress)", maxObservedProgress >= 0.22f)

        // 3b. Bottom-lip upward swipe (even before scrolling the notification deck!) MUST unlock immediately
        maxObservedProgress = 0f
        compose.onRoot().performTouchInput {
            val startX = width * 0.5f
            val startY = height - 28f * dens
            val endY = height * 0.42f
            down(Offset(startX, startY))
            for (step in 1..12) {
                val frac = step / 12f
                moveTo(Offset(startX, startY + (endY - startY) * frac), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("Bottom-lip upward swipe must unlock even with expanded notifications", 2, unlockCount)
        assertTrue("Expected curtain lift >= 0.25f on bottom-lip swipe (got $maxObservedProgress)", maxObservedProgress >= 0.25f)

        // 4. First partial mid-screen upward scroll inside the notification Column (while `canScrollForward == true`)
        //    scrolls notifications WITHOUT prematurely unlocking or lifting the curtain.
        maxObservedProgress = 0f
        val preScrollUnlockCount = unlockCount
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            val sy = height * 0.72f
            val ey = height * 0.58f
            down(Offset(cx, sy))
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 16L)
            }
            up()
        }
        compose.waitForIdle()
        assertEquals("Intermediate upward scroll while canScrollForward==true must not unlock early", preScrollUnlockCount, unlockCount)
        assertEquals("Intermediate upward scroll while canScrollForward==true must not lift curtain", 0f, maxObservedProgress, 0.001f)

        // Scroll expanded notification list all the way to the bottom in one full scroll gesture starting inside
        // the expanded notification box (`canScrollForward == true` at ACTION_DOWN) — must stop cleanly at
        // Message #7 WITHOUT accidentally unlocking mid-scroll!
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            val sy = height * 0.76f
            val ey = height * 0.24f
            down(Offset(cx, sy))
            for (step in 1..14) {
                val frac = step / 14f
                moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 14L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Message #7").assertExists()
        assertEquals("Reaching bottom of expanded notifications mid-scroll must not accidentally unlock", preScrollUnlockCount, unlockCount)

        // Scroll downward substantially to bring Message #1 back into view and verify non-vacuous downward scroll
        repeat(2) {
            compose.onRoot().performTouchInput {
                val cx = width * 0.5f
                val sy = height * 0.52f
                val ey = height * 0.80f
                down(Offset(cx, sy))
                for (step in 1..10) {
                    val frac = step / 10f
                    moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 14L)
                }
                up()
            }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Message #1").assertExists()
        assertEquals("Downward scroll inside expanded notifications must not unlock", preScrollUnlockCount, unlockCount)

        // Scroll back to the bottom of expanded notifications in one clean scroll gesture (`canScrollForward == true`
        // at ACTION_DOWN -> `canScrollForward == false` at rest), then swipe up over the middle of the expanded
        // notification stack — must NOT be swallowed by `.verticalScroll(notifScrollState)`!
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            val sy = height * 0.78f
            val ey = height * 0.20f
            down(Offset(cx, sy))
            for (step in 1..16) {
                val frac = step / 16f
                moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 14L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Message #7").assertExists()
        assertEquals("Scrolling back to bottom of expanded notifications must not unlock", preScrollUnlockCount, unlockCount)

        maxObservedProgress = 0f
        val prevUnlockCount = unlockCount
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            val sy = height * 0.74f
            val ey = height * 0.34f
            down(Offset(cx, sy))
            for (step in 1..14) {
                val frac = step / 14f
                moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        assertTrue(
            "Mid-screen upward swipe after reaching end of expanded notifications must unlock (prev=$prevUnlockCount, now=$unlockCount)",
            unlockCount > prevUnlockCount,
        )
        assertTrue("Expected curtain lift >= 0.25f over expanded notifications (got $maxObservedProgress)", maxObservedProgress >= 0.25f)

        // 5. Screen-off / re-lock (`lockSessionEpoch` increment) must collapse `notificationsExpanded` back to stacked peek,
        //    reveal "Clear" on horizontal swipe of "Message #1", AND clicking "Clear" must dismiss "Message #1" without
        //    leaking the swiped "Clear" pill state onto "Message #2" (`key(item.key)`)!
        sessionEpochState.intValue += 1
        compose.waitForIdle()
        compose.onNodeWithText("5 more notifications").assertExists()
        compose.onNodeWithText("Message #1").performTouchInput {
            val startX = width * 0.85f
            val endX = startX - 110f * dens
            val cy = height * 0.5f
            down(Offset(startX, cy))
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(Offset(startX + (endX - startX) * frac, cy), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Clear").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Message #1").assertDoesNotExist()
        compose.onNodeWithText("Message #2").assertExists()
        compose.onNodeWithText("Clear").assertDoesNotExist()
        compose.onNodeWithText("4 more notifications").assertExists()
    }

    @Test
    fun controlCenter_closeDragAndDispose_alwaysSettlesDragEnd() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(wallpaperBitmap = createStructuredTestWallpaper()),
        )
        val mountedState = mutableStateOf(true)
        var deltaCount = 0
        var endCount = 0

        compose.setContent {
            IosControlCenterTheme {
                if (mountedState.value) {
                    ControlCenterRootScreen(
                        controller = ctrl,
                        glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                        openProgress = 0.85f,
                        initialModal = CcModalSheet.POWER_MENU,
                        onDragCloseDelta = { deltaCount++ },
                        onDragCloseEnd = { endCount++ },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 1. Upward swipe while a modal sheet (POWER_MENU) is open must dismiss ONLY the modal without
        //    leaking `onDragCloseDelta` / `onDragCloseEnd` to the root Control Center sheet in the same gesture!
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            down(Offset(cx, height * 0.80f))
            moveTo(Offset(cx, height * 0.65f), delayMillis = 16L)
            moveTo(Offset(cx, height * 0.48f), delayMillis = 16L)
            up()
        }
        compose.waitForIdle()
        assertEquals("Modal-dismiss upward swipe must not leak onDragCloseDelta to root CC", 0, deltaCount)
        assertEquals("Modal-dismiss upward swipe must not leak onDragCloseEnd to root CC", 0, endCount)

        // 2. Start an upward close drag on root CC, then unmount mid-drag to simulate abrupt overlay close/re-parenting:
        //    `onDragCloseEnd` MUST still fire so `isSheetGestureActive` never leaks!
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            down(Offset(cx, height * 0.75f))
            moveTo(Offset(cx, height * 0.60f), delayMillis = 16L)
            moveTo(Offset(cx, height * 0.50f), delayMillis = 16L)
        }
        compose.waitForIdle()
        assertTrue("Expected onDragCloseDelta during active upward drag", deltaCount > 0)
        assertEquals("Expected zero onDragCloseEnd while finger still down", 0, endCount)

        mountedState.value = false
        compose.waitForIdle()
        assertEquals("Unmounting mid-close-drag must settle via onDragCloseEnd exactly once", 1, endCount)
    }

    @Test
    fun notificationCenter_nestedScrollUnlatchAndClose_alwaysSettlesDragEnd() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(wallpaperBitmap = createStructuredTestWallpaper()),
        )
        val now = System.currentTimeMillis()
        val manyNotifications = (1..12).map { idx ->
            IosNotificationItem(
                key = "nc_item_$idx",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "NC Thread #$idx",
                body = "Notification Center nested scroll-to-close + unlatch regression card #$idx.",
                postTimeMs = now - idx * 45_000L,
                isClearable = true,
                autoCancel = true,
            )
        }

        val ncListState = LazyListState()
        val progressState = mutableFloatStateOf(1f)
        var minProgressObserved = 1f
        var deltaCalls = 0
        var endCalls = 0

        compose.setContent {
            IosControlCenterTheme {
                NotificationCenterRootScreen(
                    controller = ctrl,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                    openProgress = progressState.floatValue,
                    previewClockText = "9:41",
                    previewDateText = "Sat Nov 7",
                    previewNotifications = manyNotifications,
                    externalListState = ncListState,
                    onDragCloseDelta = { dy ->
                        deltaCalls++
                        val screenH = 844f * 3f
                        val next = (progressState.floatValue + dy / screenH).coerceIn(0.01f, 1f)
                        progressState.floatValue = next
                        if (next < minProgressObserved) {
                            minProgressObserved = next
                        }
                    },
                    onDragCloseEnd = {
                        endCalls++
                    },
                )
            }
        }
        compose.waitForIdle()

        // Scroll LazyColumn all the way to the bottom (`canScrollForward == false && canScrollBackward == true`)
        repeat(6) {
            compose.onRoot().performTouchInput {
                val cx = width * 0.5f
                val sy = height * 0.80f
                val ey = height * 0.28f
                down(Offset(cx, sy))
                for (step in 1..8) {
                    val frac = step / 8f
                    moveTo(Offset(cx, sy + (ey - sy) * frac), delayMillis = 12L)
                }
                up()
            }
            compose.waitForIdle()
        }
        assertTrue("Expected LazyColumn at bottom (!canScrollForward)", !ncListState.canScrollForward)
        assertTrue("Expected LazyColumn scrollable backward (canScrollBackward)", ncListState.canScrollBackward)
        val bottomIndexBefore = ncListState.firstVisibleItemIndex
        val bottomOffsetBefore = ncListState.firstVisibleItemScrollOffset

        deltaCalls = 0
        endCalls = 0
        minProgressObserved = 1f
        progressState.floatValue = 1f
        compose.waitForIdle()

        // Single continuous mid-screen gesture:
        //   1. Drag UP past bottom -> `sheetCloseNestedScroll.onPostScroll` latches sheet-close (`minProgressObserved < 0.94f`),
        //   2. Drag DOWN past 1.0f -> `sheetCloseNestedScroll.onPreScroll` unlatches `sheetClosingActive = false` (`progressState == 1.0f`)
        //      AND remaining downward movement scrolls `LazyColumn` backward in the SAME gesture,
        //   3. Release finger -> `onDragCloseEnd` fires ONCE to settle overlay state AND `LazyColumn` keeps backward scroll!
        compose.onRoot().performTouchInput {
            val cx = width * 0.5f
            val y0 = height * 0.62f
            val yUp = height * 0.42f
            val yDown = height * 0.94f
            down(Offset(cx, y0))
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(Offset(cx, y0 + (yUp - y0) * frac), delayMillis = 14L)
            }
            for (step in 1..14) {
                val frac = step / 14f
                moveTo(Offset(cx, yUp + (yDown - yUp) * frac), delayMillis = 14L)
            }
            up()
        }
        compose.waitForIdle()
        val afterIndex = ncListState.firstVisibleItemIndex
        val afterOffset = ncListState.firstVisibleItemScrollOffset
        val scrolledBackwardInSameGesture = (afterIndex < bottomIndexBefore) ||
            (afterIndex == bottomIndexBefore && afterOffset < bottomOffsetBefore)
        assertTrue("Expected onDragCloseDelta during upward overscroll close", deltaCalls > 0)
        assertTrue("Expected sheet to lift (< 0.94f) before downward unlatch, got $minProgressObserved", minProgressObserved < 0.94f)
        assertTrue("Expected sheet to unlatch back to ~1.0f on downward reversal, got ${progressState.floatValue}", progressState.floatValue >= 0.99f)
        assertTrue(
            "Expected continuous downward reversal after unlatch to scroll LazyColumn backward (before=$bottomIndexBefore:$bottomOffsetBefore, after=$afterIndex:$afterOffset)",
            scrolledBackwardInSameGesture,
        )
        assertEquals("Expected onDragCloseEnd on release even after downward unlatch back to 1.0f", 1, endCalls)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
    fun lockScreen_nativeRmx1931_420dpi_udfpsGeometryAndOpticalAperture() {
        val exact1080 = UdfpsSensorGeometry.resolve(null, 1080f, 2400f)
        assertEquals("Exact RMX1931 Goodix G3 centerX on 1080px width", 540f, exact1080.centerXPx, 0.01f)
        assertEquals("Exact RMX1931 Goodix G3 centerY on 2400px height", 2156f, exact1080.centerYPx, 0.01f)
        assertEquals("Exact RMX1931 Goodix G3 radius on 1080x2400", 95f, exact1080.radiusPx, 0.01f)

        val wp = createStructuredTestWallpaper(width = 540, height = 1200)
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                wallpaperBitmap = wp,
            ),
        )
        val now = System.currentTimeMillis()
        val fiveNotifs = (1..5).map { idx ->
            IosNotificationItem(
                key = "rmx_n_$idx",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "RMX1931 Alert #$idx",
                body = "Verifying 420dpi Goodix G3 [540, 2156, 95] UDFPS clearance & optical HBM cutout.",
                postTimeMs = now - idx * 30_000L,
                isClearable = true,
                autoCancel = true,
            )
        }
        val fingerDownState = mutableStateOf(false)

        compose.setContent {
            IosControlCenterTheme {
                IosLockScreenRoot(
                    controller = ctrl,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                    gesturePillEnabled = true,
                    isSecureKeyguard = true,
                    hasEnrolledUdfps = true,
                    isUdfpsFingerDown = fingerDownState.value,
                    isLockScreenVisible = true,
                    previewClockText = "9:41",
                    previewDateText = "Sun Nov 8",
                    previewClockStyle = 0,
                    previewNotifications = fiveNotifs,
                )
            }
        }
        compose.waitForIdle()

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val spec = UdfpsSensorGeometry.resolve(null, rootBounds.width, rootBounds.height)
        val udfpsTopPx = spec.centerYPx - spec.radiusPx

        // 1. Verify "3 more notifications" pill sits strictly above the physical Goodix G3 UDFPS circle (`buttonBottom < udfpsTop`)
        val moreNodeBounds = compose.onNodeWithText("3 more notifications").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected '3 more notifications' bottom (${moreNodeBounds.bottom}) strictly above UDFPS top ($udfpsTopPx) on 420dpi RMX1931",
            moreNodeBounds.bottom <= udfpsTopPx - 4f,
        )
        compose.onNodeWithText("3 more notifications").performClick()
        compose.waitForIdle()
        val showLessBounds = compose.onNodeWithText("Show Less").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected 'Show Less' bottom (${showLessBounds.bottom}) strictly above UDFPS top ($udfpsTopPx) on 420dpi RMX1931",
            showLessBounds.bottom <= udfpsTopPx - 4f,
        )

        // 2. Verify optical HBM cutout: opaque when finger is up (`isUdfpsFingerDown = false`),
        //    punched `BlendMode.Clear` (`alpha == 0`) at `(spec.centerXPx, spec.centerYPx)` when finger is down!
        val idleBmp = captureTransientBitmap("ls_rmx1931_udfps_up")
        val cx = spec.centerXPx.roundToInt().coerceIn(4, idleBmp.width - 4)
        val cy = spec.centerYPx.roundToInt().coerceIn(4, idleBmp.height - 4)
        assertTrue(
            "Expected opaque Lock Screen wallpaper at UDFPS center while finger is up, got alpha=${Color.alpha(idleBmp.getPixel(cx, cy))}",
            Color.alpha(idleBmp.getPixel(cx, cy)) >= 245,
        )

        fingerDownState.value = true
        val pressedBmp = captureTransientBitmap("ls_rmx1931_udfps_down_clear")
        assertEquals(
            "Expected BlendMode.Clear transparent optical HBM aperture (alpha=0) at UDFPS center while finger is down",
            0,
            Color.alpha(pressedBmp.getPixel(cx, cy)),
        )
        // Wallpaper outside the UDFPS circle remains opaque
        val outsideY = (spec.centerYPx - spec.radiusPx * 1.45f).roundToInt().coerceIn(8, pressedBmp.height - 8)
        assertTrue(
            "Expected opaque Lock Screen pixels outside optical UDFPS circle at y=$outsideY",
            Color.alpha(pressedBmp.getPixel(cx, outsideY)) >= 245,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun setRepoNotificationsForTest(items: List<IosNotificationItem>) {
        val f = IosNotificationRepository::class.java.getDeclaredField("notifications\$delegate")
        f.isAccessible = true
        (f.get(IosNotificationRepository) as MutableState<List<IosNotificationItem>>).value = items
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
    fun notificationCenter_mdpi1x_swipeClearOngoingImmunityAndClearAllPreservation() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                mediaPlaying = false,
                mediaTitle = "Not Playing",
                mediaArtist = "",
                wallpaperBitmap = createStructuredTestWallpaper(),
            ),
        )
        val now = System.currentTimeMillis()
        val repoItems = mutableStateListOf(
            IosNotificationItem(
                key = "nc_clear_1",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Clearable One",
                body = "First clearable notification card.",
                postTimeMs = now - 20_000L,
                isClearable = true,
                autoCancel = true,
            ),
            IosNotificationItem(
                key = "nc_clear_2",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Clearable Two",
                body = "Second clearable notification card for full-swipe dismiss.",
                postTimeMs = now - 40_000L,
                isClearable = true,
                autoCancel = true,
            ),
            IosNotificationItem(
                key = "nc_live_flip",
                packageName = "com.android.deskclock",
                appName = "Clock",
                title = "Live Flip Item",
                body = "Transitions from clearable to ongoing while swiped open.",
                postTimeMs = now - 60_000L,
                isClearable = true,
                autoCancel = false,
            ),
            IosNotificationItem(
                key = "nc_ongoing",
                packageName = "com.spotify.music",
                appName = "Spotify",
                title = "Ongoing Playback",
                body = "Foreground service notification must never be dismissed by swipe or Clear All.",
                postTimeMs = now - 80_000L,
                isClearable = false,
                autoCancel = false,
            ),
        )
        setRepoNotificationsForTest(repoItems.toList())

        compose.setContent {
            IosControlCenterTheme {
                NotificationCenterRootScreen(
                    controller = ctrl,
                    glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                    openProgress = 1f,
                    previewClockText = "9:41",
                    previewDateText = "Sun Nov 8",
                )
            }
        }
        compose.waitForIdle()
        val dens = compose.density.density

        // 1. Partial swipe (-105dp) on "Clearable One" snaps open "Clear" pill without overlapping card text,
        //    and clicking "Clear" dismisses ONLY "Clearable One".
        compose.onNodeWithText("Clearable One").performTouchInput {
            val sx = width * 0.85f
            val ex = sx - 105f * dens
            val cy = height * 0.5f
            down(Offset(sx, cy))
            for (step in 1..8) {
                moveTo(Offset(sx + (ex - sx) * (step / 8f), cy), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Clear").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clearable One").assertDoesNotExist()

        // 2. Full left swipe (-240dp) on "Clearable Two" dismisses directly without needing a second tap.
        compose.onNodeWithText("Clearable Two").performTouchInput {
            val sx = width * 0.90f
            val ex = sx - 240f * dens
            val cy = height * 0.5f
            down(Offset(sx, cy))
            for (step in 1..10) {
                moveTo(Offset(sx + (ex - sx) * (step / 10f), cy), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Clearable Two").assertDoesNotExist()

        // 3. Ongoing (`isClearable = false`) card ignores horizontal swipe completely.
        compose.onNodeWithText("Ongoing Playback").performTouchInput {
            val sx = width * 0.90f
            val ex = sx - 220f * dens
            val cy = height * 0.5f
            down(Offset(sx, cy))
            for (step in 1..8) {
                moveTo(Offset(sx + (ex - sx) * (step / 8f), cy), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ongoing Playback").assertExists()
        compose.onNodeWithText("Clear").assertDoesNotExist()

        // 4. Swipe open "Live Flip Item" so "Clear" pill appears, then update it in-place to `isClearable = false`:
        //    `dragOffsetX` MUST reset to 0f and hide "Clear" automatically, AND because zero clearable items remain,
        //    `IosNotificationRepository.clearAll()` preserves ongoing items!
        compose.onNodeWithText("Live Flip Item").performTouchInput {
            val sx = width * 0.85f
            val ex = sx - 105f * dens
            val cy = height * 0.5f
            down(Offset(sx, cy))
            for (step in 1..8) {
                moveTo(Offset(sx + (ex - sx) * (step / 8f), cy), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Clear").assertExists()

        setRepoNotificationsForTest(
            IosNotificationRepository.notifications.map {
                if (it.key == "nc_live_flip") it.copy(isClearable = false) else it
            }
        )
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithText("Clear").assertDoesNotExist()

        IosNotificationRepository.clearAll()
        compose.waitForIdle()
        assertEquals("clearAll() must preserve non-clearable ongoing items", 2, IosNotificationRepository.notifications.size)
        setRepoNotificationsForTest(emptyList())
    }

    @Test
    fun multiFingerHandoff_lockScreenAndNotificationCenter_completesWithoutCancel() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(wallpaperBitmap = createStructuredTestWallpaper()),
        )
        val showLockState = mutableStateOf(true)
        var lockUnlocks = 0
        var lockCancels = 0
        var ncEndCalls = 0
        var lastNcEndVy = 0f
        var ncDismissed = false

        compose.setContent {
            IosControlCenterTheme {
                if (showLockState.value) {
                    IosLockScreenRoot(
                        controller = ctrl,
                        glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                        isSecureKeyguard = false,
                        isLockScreenVisible = true,
                        previewClockText = "9:41",
                        previewDateText = "Sun Nov 8",
                        previewNotifications = emptyList(),
                        onSwipeCancelled = { lockCancels++ },
                        onUnlockRequested = { lockUnlocks++ },
                    )
                } else {
                    NotificationCenterRootScreen(
                        controller = ctrl,
                        glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                        openProgress = 1f,
                        previewClockText = "9:41",
                        previewDateText = "Sun Nov 8",
                        previewNotifications = emptyList(),
                        onDragCloseDelta = {},
                        onDragCloseEnd = { vy ->
                            ncEndCalls++
                            lastNcEndVy = vy
                        },
                        onDismiss = { ncDismissed = true },
                    )
                }
            }
        }
        compose.waitForIdle()

        // 1. Lock Screen multi-pointer handoff: Pointer 0 starts upward drag -> Pointer 1 goes down ->
        //    Pointer 0 lifts (`ACTION_POINTER_UP`) -> Pointer 1 finishes upward drag -> MUST unlock with zero cancel!
        compose.onRoot().performTouchInput {
            val x0 = width * 0.44f
            val x1 = width * 0.58f
            val yStart = height * 0.85f
            val yMid = height * 0.68f
            val yEnd = height * 0.30f
            down(0, Offset(x0, yStart))
            moveTo(0, Offset(x0, (yStart + yMid) * 0.5f), delayMillis = 12L)
            moveTo(0, Offset(x0, yMid), delayMillis = 12L)
            down(1, Offset(x1, yMid))
            up(0)
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(1, Offset(x1, yMid + (yEnd - yMid) * frac), delayMillis = 12L)
            }
            up(1)
        }
        compose.waitForIdle()
        assertEquals("Multi-finger handoff on Lock Screen must not cancel", 0, lockCancels)
        assertEquals("Multi-finger handoff on Lock Screen must complete unlock", 1, lockUnlocks)

        // 2. Notification Center bottom-lip multi-pointer handoff: Pointer 0 starts in bottom lip ->
        //    Pointer 1 joins -> Pointer 0 lifts -> Pointer 1 flings upward -> settles cleanly with upward velocity!
        showLockState.value = false
        compose.waitForIdle()
        val dens = compose.density.density
        compose.onRoot().performTouchInput {
            val x0 = width * 0.42f
            val x1 = width * 0.58f
            val yStart = height - 24f * dens
            val yMid = height - 64f * dens
            val yEnd = height * 0.36f
            down(0, Offset(x0, yStart))
            moveTo(0, Offset(x0, yMid), delayMillis = 12L)
            down(1, Offset(x1, yMid))
            up(0)
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(1, Offset(x1, yMid + (yEnd - yMid) * frac), delayMillis = 10L)
            }
            up(1)
        }
        compose.waitForIdle()
        assertEquals("Multi-finger handoff on NC bottom lip must settle once", 1, ncEndCalls)
        assertTrue("Expected upward negative release velocity on surviving pointer (got $lastNcEndVy), dismissed=$ncDismissed", lastNcEndVy < -200f)
    }

    @Test
    fun lockScreen_hiddenWhileUnlocked_zeroBackgroundRecompositionAndExpandedMediaVolumeScrub() {
        val base = ControlCenterController.previewClearPlaying()
        val ctrl = ControlCenterController(
            context = null,
            previewOnly = true,
            initialState = base.state.copy(
                brightness = 0.35f,
                volume = 0.20f,
                wallpaperBitmap = createStructuredTestWallpaper(),
            ),
        )
        val lockVisibleState = mutableStateOf(false)
        val showCcInstead = mutableStateOf(false)
        var closeDragDeltaCount = 0

        compose.setContent {
            IosControlCenterTheme {
                if (showCcInstead.value) {
                    ControlCenterRootScreen(
                        controller = ctrl,
                        glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                        openProgress = 1f,
                        initialPage = 1,
                        onDragCloseDelta = { closeDragDeltaCount++ },
                    )
                } else {
                    IosLockScreenRoot(
                        controller = ctrl,
                        glassConfig = CcGlassConfig(isTinted = false, refractionPct = 20, frostPct = 20),
                        isLockScreenVisible = lockVisibleState.value,
                        curtainDismissProgress = 0f,
                        previewClockText = "9:41",
                        previewDateText = "Sun Nov 8",
                        previewNotifications = emptyList(),
                    )
                }
            }
        }
        compose.waitForIdle()

        // 1. While `isLockScreenVisible == false`, Lock Screen exits before subscribing to `ctrl.state`
        compose.onNodeWithText("Sun Nov 8").assertDoesNotExist()
        ctrl.setBrightness(0.82f)
        ctrl.setVolume(0.65f)
        compose.waitForIdle()
        compose.onNodeWithText("Sun Nov 8").assertDoesNotExist()

        // Flipping `isLockScreenVisible = true` immediately renders Lock Screen (`Sun Nov 8` header text)
        lockVisibleState.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Sun Nov 8").assertExists()

        // 2. Switch to Expanded Now Playing page in Control Center and scrub the horizontal volume capsule:
        //    volume updates smoothly across drag without leaking vertical close-drag events!
        showCcInstead.value = true
        compose.waitForIdle()
        val doneBounds = compose.onNodeWithText("Done").assertExists().fetchSemanticsNode().boundsInRoot
        val dens = compose.density.density
        // Volume capsule sits directly above the 22dp spacer + "Done" pill (`~doneTop - 36dp`)
        val volY = doneBounds.top - 36f * dens
        compose.onRoot().performTouchInput {
            val sx = width * 0.28f
            val ex = width * 0.78f
            down(Offset(sx, volY))
            for (step in 1..8) {
                val frac = step / 8f
                moveTo(Offset(sx + (ex - sx) * frac, volY), delayMillis = 12L)
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("Expected horizontal scrub on Expanded Media volume bar to raise volume > 0.72f (got ${ctrl.state.volume})", ctrl.state.volume > 0.72f)
        assertEquals("Horizontal volume scrub must not leak close drag delta to Control Center root", 0, closeDragDeltaCount)
    }
}
