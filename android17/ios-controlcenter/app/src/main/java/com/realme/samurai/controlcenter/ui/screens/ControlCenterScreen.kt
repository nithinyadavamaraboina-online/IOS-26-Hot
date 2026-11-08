package com.realme.samurai.controlcenter.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker as ComposeVelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.data.ControlCenterState
import com.realme.samurai.controlcenter.ui.components.CircularControlTile1x1
import com.realme.samurai.controlcenter.ui.components.ConnectivityPlatter2x2
import com.realme.samurai.controlcenter.ui.components.FocusCapsulePill2x1
import com.realme.samurai.controlcenter.ui.components.HardwareStatusPill2x1
import com.realme.samurai.controlcenter.ui.components.LiquidGlassDropletBadge
import com.realme.samurai.controlcenter.ui.components.NowPlayingPlatter2x2
import com.realme.samurai.controlcenter.ui.components.RightVerticalPageRail
import com.realme.samurai.controlcenter.ui.components.TopUtilityChromeBar
import com.realme.samurai.controlcenter.ui.components.VerticalCapsuleSlider1x2
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.ControlCenterGlassSceneHost
import com.realme.samurai.controlcenter.ui.glass.Ios26HomeIndicatorBar
import com.realme.samurai.controlcenter.ui.glass.ccGlassPlatter
import com.realme.samurai.controlcenter.ui.glass.clipWithMovingGlassRim
import com.realme.samurai.controlcenter.ui.icons.AirDropGlyph
import com.realme.samurai.controlcenter.ui.icons.AirplaneGlyph
import com.realme.samurai.controlcenter.ui.icons.AmbientRainGlyph
import com.realme.samurai.controlcenter.ui.icons.BluetoothRuneGlyph
import com.realme.samurai.controlcenter.ui.icons.CalculatorControlGlyph
import com.realme.samurai.controlcenter.ui.icons.CameraControlGlyph
import com.realme.samurai.controlcenter.ui.icons.DarkModeContrastGlyph
import com.realme.samurai.controlcenter.ui.icons.FlashlightGlyph
import com.realme.samurai.controlcenter.ui.icons.GlobeNetworkGlyph
import com.realme.samurai.controlcenter.ui.icons.HotspotLinksGlyph
import com.realme.samurai.controlcenter.ui.icons.LiquidGlassPrismGlyph
import com.realme.samurai.controlcenter.ui.icons.LowPowerBatteryGlyph
import com.realme.samurai.controlcenter.ui.icons.OrientationLockGlyph
import com.realme.samurai.controlcenter.ui.icons.PowerGlyph
import com.realme.samurai.controlcenter.ui.icons.QuickNoteControlGlyph
import com.realme.samurai.controlcenter.ui.icons.RainCloudArtCanvas
import com.realme.samurai.controlcenter.ui.icons.ScreenMirrorGlyph
import com.realme.samurai.controlcenter.ui.icons.ScreenRecordGlyph
import com.realme.samurai.controlcenter.ui.icons.SignalBarsGlyph
import com.realme.samurai.controlcenter.ui.icons.SilentBellGlyph
import com.realme.samurai.controlcenter.ui.icons.SpeakerWaveGlyph
import com.realme.samurai.controlcenter.ui.icons.SunRaysGlyph
import com.realme.samurai.controlcenter.ui.icons.SuperVoocBoltGlyph
import com.realme.samurai.controlcenter.ui.icons.TimerDialGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportFastForwardGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportPlayPauseGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportRewindGlyph
import com.realme.samurai.controlcenter.ui.icons.WifiFanGlyph
import com.realme.samurai.controlcenter.ui.theme.CapsuleShape
import com.realme.samurai.controlcenter.ui.theme.CcPalette
import com.realme.samurai.controlcenter.ui.theme.CcType
import com.realme.samurai.controlcenter.ui.theme.ContinuousSquircleShape

enum class CcModalSheet {
    NONE,
    POWER_MENU,
    QUICK_TIMER,
}

@Composable
fun ControlCenterRootScreen(
    controller: ControlCenterController,
    glassConfig: CcGlassConfig,
    openProgress: Float = 1f,
    sheetVelocityPxPerSec: Float = 0f,
    gesturePillEnabled: Boolean = true,
    initialPage: Int = 0,
    initialModal: CcModalSheet = CcModalSheet.NONE,
    onDragCloseDelta: ((Float) -> Unit)? = null,
    onDragCloseEnd: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    val state = controller.state
    var selectedPage by remember { mutableIntStateOf(initialPage) }
    var activeModal by remember { mutableStateOf(initialModal) }
    var dragAccumUp by remember { mutableFloatStateOf(0f) }
    var closeDragDispatched by remember { mutableStateOf(false) }
    val latestOnDragCloseDelta by rememberUpdatedState(onDragCloseDelta)
    val latestOnDragCloseEnd by rememberUpdatedState(onDragCloseEnd)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val clampedProgress = openProgress.coerceIn(0f, 1f)
    LaunchedEffect(clampedProgress <= 0.02f) {
        if (clampedProgress <= 0.02f && !controller.previewOnly) {
            selectedPage = initialPage
            activeModal = initialModal
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (closeDragDispatched) {
                closeDragDispatched = false
                latestOnDragCloseEnd?.invoke(0f)
            }
        }
    }
    val localCtx = LocalContext.current
    val localDensity = LocalDensity.current
    val sysStatusInsetDp = remember(localCtx, localDensity, controller.previewOnly) {
        if (controller.previewOnly) {
            0.dp
        } else {
            val resId = localCtx.resources.getIdentifier("status_bar_height", "dimen", "android")
            val px = if (resId > 0) localCtx.resources.getDimensionPixelSize(resId) else 0
            val rawDp = with(localDensity) { px.toDp() }
            if (rawDp > 34.dp) rawDp else 34.dp
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenH = (if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            with(localDensity) { 800.dp.toPx() }
        }).coerceAtLeast(640f)
        val sheetEdgeYPx = (clampedProgress * screenH).coerceIn(0f, screenH)
        val pullOffsetPx = -(screenH - sheetEdgeYPx)
        val currentScreenH by rememberUpdatedState(screenH)

        ControlCenterGlassSceneHost(
            wallpaperBitmap = state.wallpaperBitmap,
            glassConfig = glassConfig,
            openProgress = clampedProgress,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (activeModal != CcModalSheet.NONE) {
                            activeModal = CcModalSheet.NONE
                        } else if (selectedPage != 0) {
                            selectedPage = 0
                        } else {
                            latestOnDismiss()
                        }
                    })
                }
                .pointerInput(Unit) {
                    val vt = ComposeVelocityTracker()
                    val modalDismissThresholdPx = -28.dp.toPx()
                    var modalConsumedThisGesture = false
                    var standaloneDismissed = false
                    var lastPointerId: PointerId? = null
                    try {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragAccumUp = 0f
                                closeDragDispatched = false
                                modalConsumedThisGesture = false
                                standaloneDismissed = false
                                lastPointerId = null
                                vt.resetTracking()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (lastPointerId != null && change.id != lastPointerId) {
                                    vt.resetTracking()
                                }
                                lastPointerId = change.id
                                vt.addPointerInputChange(change)
                                val deltaCb = latestOnDragCloseDelta
                                if ((activeModal != CcModalSheet.NONE || modalConsumedThisGesture) && !closeDragDispatched) {
                                    modalConsumedThisGesture = true
                                    dragAccumUp += dragAmount
                                    if (activeModal != CcModalSheet.NONE && dragAccumUp < modalDismissThresholdPx) {
                                        dragAccumUp = 0f
                                        activeModal = CcModalSheet.NONE
                                    }
                                } else {
                                    dragAccumUp = (dragAccumUp + dragAmount).coerceAtMost(0f)
                                    if (deltaCb != null) {
                                        closeDragDispatched = true
                                        deltaCb.invoke(dragAmount)
                                    } else if (!standaloneDismissed && dragAccumUp <= -0.18f * currentScreenH) {
                                        standaloneDismissed = true
                                        dragAccumUp = 0f
                                        latestOnDismiss()
                                    }
                                }
                            },
                            onDragEnd = {
                                val vy = vt.calculateVelocity().y
                                vt.resetTracking()
                                lastPointerId = null
                                val endCb = latestOnDragCloseEnd
                                if (closeDragDispatched) {
                                    closeDragDispatched = false
                                    endCb?.invoke(vy)
                                } else if (!modalConsumedThisGesture && activeModal == CcModalSheet.NONE) {
                                    if (endCb != null) {
                                        endCb.invoke(vy)
                                    } else if (!standaloneDismissed &&
                                        (dragAccumUp <= -0.18f * currentScreenH || vy <= -650f * this.density)
                                    ) {
                                        standaloneDismissed = true
                                        latestOnDismiss()
                                    }
                                }
                                modalConsumedThisGesture = false
                                standaloneDismissed = false
                                dragAccumUp = 0f
                            },
                            onDragCancel = {
                                vt.resetTracking()
                                lastPointerId = null
                                if (closeDragDispatched || (!modalConsumedThisGesture && activeModal == CcModalSheet.NONE)) {
                                    closeDragDispatched = false
                                    latestOnDragCloseEnd?.invoke(0f)
                                }
                                modalConsumedThisGesture = false
                                standaloneDismissed = false
                                dragAccumUp = 0f
                            },
                        )
                    } finally {
                        if (closeDragDispatched) {
                            closeDragDispatched = false
                            latestOnDragCloseEnd?.invoke(0f)
                        }
                    }
                },
            pullOffsetProvider = { pullOffsetPx },
            sheetEdgeYPx = sheetEdgeYPx,
            sheetVelocityPxPerSec = sheetVelocityPxPerSec,
            motionTickProvider = { selectedPage },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipWithMovingGlassRim(sheetEdgeYPx, 28.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = pullOffsetPx
                            alpha = (clampedProgress * 5.5f).coerceIn(0f, 1f)
                        },
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (controller.previewOnly) Modifier.statusBarsPadding()
                                else Modifier.padding(top = sysStatusInsetDp)
                            ),
                    ) {
                        val maxW = maxWidth
                        val maxH = maxHeight

                        val startInset = 24.dp
                        val endInset = 34.dp
                        val gap = 12.dp
                        val topHeaderHeight = 86.dp
                        val bottomSafeMargin = 28.dp

                        val availGridW = (maxW - startInset - endInset).coerceAtLeast(240.dp)
                        val availGridH = (maxH - topHeaderHeight - bottomSafeMargin).coerceAtLeast(420.dp)

                        val cellFromW = (availGridW - gap * 3f) / 4f
                        val cellFromH = (availGridH - gap * 7f) / 8f
                        val cellSize: Dp = min(cellFromW, cellFromH)
                        val span2Size: Dp = cellSize * 2f + gap

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (activeModal != CcModalSheet.NONE) 0.10f else 1f
                                }
                                .padding(top = 10.dp),
                        ) {
                            // Top '+' / Power + Carrier / Battery bar
                            TopUtilityChromeBar(
                                state = state,
                                onAddTap = { controller.toggleLiquidGlassTintMode() },
                                onPowerTap = { activeModal = CcModalSheet.POWER_MENU },
                                modifier = Modifier.padding(start = startInset - 4.dp, end = 16.dp),
                            )

                            Spacer(Modifier.height(14.dp))

                            Box(modifier = Modifier.fillMaxWidth()) {
                                // Left/Center 4-Column Page Content
                                Box(
                                    modifier = Modifier
                                        .padding(start = startInset, end = endInset)
                                ) {
                                    AnimatedContent(
                                        targetState = selectedPage,
                                        transitionSpec = {
                                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                        },
                                        label = "ccPageSwitch",
                                    ) { page ->
                                        when (page) {
                                            1 -> ExpandedNowPlayingPage(
                                                width = cellSize * 4f + gap * 3f,
                                                state = state,
                                                glassConfig = glassConfig,
                                                onPrev = controller::mediaPrevious,
                                                onPlayPause = controller::mediaPlayPause,
                                                onNext = controller::mediaNext,
                                                onVolumeChange = controller::setVolume,
                                                onBackToGrid = { selectedPage = 0 },
                                            )
                                            2 -> ExpandedConnectivityPage(
                                                width = cellSize * 4f + gap * 3f,
                                                state = state,
                                                glassConfig = glassConfig,
                                                controller = controller,
                                                onBackToGrid = { selectedPage = 0 },
                                            )
                                            else -> FavoritesGridPage(
                                                cellSize = cellSize,
                                                span2Size = span2Size,
                                                gap = gap,
                                                state = state,
                                                glassConfig = glassConfig,
                                                controller = controller,
                                                onExpandConnectivity = { selectedPage = 2 },
                                                onExpandMedia = { selectedPage = 1 },
                                                onOpenTimerModal = { activeModal = CcModalSheet.QUICK_TIMER },
                                            )
                                        }
                                    }
                                }

                                // Right-Edge Vertical Page Icon Strip
                                RightVerticalPageRail(
                                    selectedPage = selectedPage,
                                    onSelectPage = { selectedPage = it },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = span2Size * 0.86f, end = 7.dp),
                                )
                            }
                        }

                        // Optional Modal Sheets (Power Menu / Quick Timer)
                        if (activeModal == CcModalSheet.POWER_MENU) {
                            PowerMenuSheetOverlay(
                                glassConfig = glassConfig,
                                onAction = { mode ->
                                    activeModal = CcModalSheet.NONE
                                    controller.performPowerAction(mode)
                                },
                                onCancel = { activeModal = CcModalSheet.NONE },
                            )
                        } else if (activeModal == CcModalSheet.QUICK_TIMER) {
                            QuickTimerSheetOverlay(
                                remainingSec = state.timerRemainingSec,
                                glassConfig = glassConfig,
                                onSelectSeconds = { sec ->
                                    controller.startQuickTimer(sec)
                                    activeModal = CcModalSheet.NONE
                                },
                                onCancel = { activeModal = CcModalSheet.NONE },
                            )
                        }
                    }

                    if (gesturePillEnabled) {
                        Ios26HomeIndicatorBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { latestOnDismiss() },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesGridPage(
    cellSize: Dp,
    span2Size: Dp,
    gap: Dp,
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    controller: ControlCenterController,
    onExpandConnectivity: () -> Unit,
    onExpandMedia: () -> Unit,
    onOpenTimerModal: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        // Rows 0..1: 2x2 Connectivity Platter + 2x2 Now Playing Platter
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            ConnectivityPlatter2x2(
                size = span2Size,
                state = state,
                glassConfig = glassConfig,
                onToggleAirplane = controller::toggleAirplaneMode,
                onToggleQuickShare = controller::toggleQuickShare,
                onToggleWifi = controller::toggleWifi,
                onToggleCellular = controller::toggleCellularData,
                onToggleBluetooth = controller::toggleBluetooth,
                onToggleHotspot = controller::toggleHotspot,
                onTogglePrivateDns = controller::togglePrivateDns,
                onExpandConnectivity = onExpandConnectivity,
            )

            NowPlayingPlatter2x2(
                size = span2Size,
                state = state,
                glassConfig = glassConfig,
                onPrev = controller::mediaPrevious,
                onPlayPause = controller::mediaPlayPause,
                onNext = controller::mediaNext,
                onExpandMedia = onExpandMedia,
            )
        }

        // Rows 2..3: (Orientation Lock + Silent + Focus 2x1 Pill) on Left, Brightness & Volume 1x2 Sliders on Right
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CircularControlTile1x1(
                        size = cellSize,
                        active = state.orientationLocked,
                        glassConfig = glassConfig,
                        onClick = controller::toggleOrientationLock,
                    ) { isActive ->
                        OrientationLockGlyph(
                            color = if (isActive) CcPalette.SilentCoral else Color.White,
                            size = cellSize * 0.44f,
                        )
                    }

                    CircularControlTile1x1(
                        size = cellSize,
                        active = state.silentMode,
                        glassConfig = glassConfig,
                        onClick = controller::toggleSilentMode,
                    ) { isActive ->
                        SilentBellGlyph(
                            slashed = isActive,
                            color = if (isActive) CcPalette.SilentCoral else Color.White,
                            size = cellSize * 0.42f,
                        )
                    }
                }

                FocusCapsulePill2x1(
                    width = span2Size,
                    height = cellSize,
                    active = state.focusActive,
                    label = state.focusLabel,
                    glassConfig = glassConfig,
                    onClick = controller::toggleFocusDnd,
                )
            }

            VerticalCapsuleSlider1x2(
                width = cellSize,
                height = span2Size,
                value = state.brightness,
                glassConfig = glassConfig,
                onValueChange = controller::setBrightness,
                onLongPress = { controller.openIosSettings("display") },
            ) {
                SunRaysGlyph(color = CcPalette.SunGold, size = cellSize * 0.30f)
            }

            VerticalCapsuleSlider1x2(
                width = cellSize,
                height = span2Size,
                value = state.volume,
                glassConfig = glassConfig,
                onValueChange = controller::setVolume,
                onLongPress = { controller.openIosSettings("sounds") },
            ) {
                SpeakerWaveGlyph(
                    level = state.volume,
                    color = CcPalette.VolumeBlue,
                    size = cellSize * 0.30f,
                )
            }
        }

        // Row 4: 2x1 Display 90Hz Pill + Flashlight Circle + Timer Circle
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            HardwareStatusPill2x1(
                width = span2Size,
                height = cellSize,
                category = "Display",
                title = "Smooth 90Hz",
                active = state.smooth90Hz,
                glassConfig = glassConfig,
                onClick = controller::toggleSmooth90Hz,
            )

            CircularControlTile1x1(
                size = cellSize,
                active = state.flashlightOn,
                glassConfig = glassConfig,
                onClick = controller::toggleFlashlight,
            ) { isActive ->
                FlashlightGlyph(
                    color = if (isActive) CcPalette.ElectricBlue else Color.White,
                    isOn = isActive,
                    size = cellSize * 0.42f,
                )
            }

            CircularControlTile1x1(
                size = cellSize,
                active = state.timerRemainingSec > 0,
                glassConfig = glassConfig,
                onClick = onOpenTimerModal,
            ) { isActive ->
                if (isActive && state.timerRemainingSec > 0) {
                    val mins = (state.timerRemainingSec + 59) / 60
                    Text(
                        text = "${mins}m",
                        style = CcType.PillTitle.copy(fontSize = 14.sp),
                        color = CcPalette.AirplaneOrange,
                    )
                } else {
                    TimerDialGlyph(
                        color = Color.White,
                        size = cellSize * 0.40f,
                    )
                }
            }
        }

        // Row 5: 2x1 AMOLED DC Dimming Pill + Screen Capture Circle + Quick Settings Circle
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            HardwareStatusPill2x1(
                width = span2Size,
                height = cellSize,
                category = "AMOLED",
                title = "DC Dimming",
                active = state.dcDimming,
                glassConfig = glassConfig,
                onClick = controller::toggleDcDimming,
            )

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = controller::triggerScreenshot,
            ) {
                ScreenRecordGlyph(color = Color.White, size = cellSize * 0.40f)
            }

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = { controller.openIosSettings() },
            ) {
                QuickNoteControlGlyph(color = Color.White, size = cellSize * 0.40f)
            }
        }

        // Row 6: Low Power Mode + Camera + Dark Mode + Screen Mirroring
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            CircularControlTile1x1(
                size = cellSize,
                active = state.lowPowerMode,
                glassConfig = glassConfig,
                onClick = controller::toggleLowPowerMode,
            ) { isActive ->
                LowPowerBatteryGlyph(
                    color = if (isActive) CcPalette.AirplaneOrange else Color.White,
                    size = cellSize * 0.40f,
                )
            }

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = controller::openCamera,
            ) {
                CameraControlGlyph(color = Color.White, size = cellSize * 0.38f)
            }

            CircularControlTile1x1(
                size = cellSize,
                active = state.darkMode,
                glassConfig = glassConfig,
                onClick = controller::toggleDarkMode,
            ) { isActive ->
                DarkModeContrastGlyph(
                    color = if (isActive) CcPalette.ActiveDarkText else Color.White,
                    size = cellSize * 0.40f,
                )
            }

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = controller::openScreenMirroring,
            ) {
                ScreenMirrorGlyph(color = Color.White, size = cellSize * 0.40f)
            }
        }

        // Row 7: Background Rain Sound + Calculator + SuperVOOC Battery + Liquid Glass Clear/Tinted
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            CircularControlTile1x1(
                size = cellSize,
                active = state.ambientRainPlaying,
                glassConfig = glassConfig,
                onClick = controller::toggleAmbientRain,
            ) { isActive ->
                AmbientRainGlyph(
                    color = if (isActive) CcPalette.VolumeBlue else Color.White.copy(alpha = 0.90f),
                    size = cellSize * 0.40f,
                )
            }

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = { controller.openIosSettings("apps") },
            ) {
                CalculatorControlGlyph(color = Color.White, size = cellSize * 0.38f)
            }

            CircularControlTile1x1(
                size = cellSize,
                active = state.isCharging,
                glassConfig = glassConfig,
                onClick = { controller.openIosSettings("battery") },
            ) { isActive ->
                SuperVoocBoltGlyph(
                    color = if (isActive) CcPalette.CellularGreen else Color.White,
                    size = cellSize * 0.38f,
                )
            }

            CircularControlTile1x1(
                size = cellSize,
                active = false,
                glassConfig = glassConfig,
                onClick = controller::toggleLiquidGlassTintMode,
            ) {
                LiquidGlassPrismGlyph(
                    color = Color.White,
                    tinted = glassConfig.isTinted,
                    size = cellSize * 0.40f,
                )
            }
        }
    }
}

@Composable
private fun ExpandedConnectivityPage(
    width: Dp,
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    controller: ControlCenterController,
    onBackToGrid: () -> Unit,
) {
    val cardShape = ContinuousSquircleShape(34.dp)
    val consumeTap = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(width)
            .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
            .clickable(interactionSource = consumeTap, indication = null) { }
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        ConnectivityDetailRow(
            title = "Airplane Mode",
            subtitle = if (state.airplaneMode) "On" else "Off",
            active = state.airplaneMode,
            activeColor = CcPalette.AirplaneOrange,
            onClick = controller::toggleAirplaneMode,
        ) { AirplaneGlyph(Color.White, 22.dp) }

        ConnectivityDetailRow(
            title = "Quick Share",
            subtitle = if (state.quickShareActive) "Contacts Only" else "Receiving Off",
            active = state.quickShareActive,
            activeColor = CcPalette.ElectricBlue,
            onClick = controller::toggleQuickShare,
        ) { AirDropGlyph(Color.White, 22.dp) }

        ConnectivityDetailRow(
            title = "Wi-Fi",
            subtitle = state.wifiSsid,
            active = state.wifiEnabled,
            activeColor = CcPalette.ElectricBlue,
            onClick = controller::toggleWifi,
            onSubtitleClick = { controller.openIosSettings("wifi") },
        ) { WifiFanGlyph(Color.White, 21.dp) }

        ConnectivityDetailRow(
            title = "Cellular Data",
            subtitle = state.cellularLabel,
            active = state.cellularEnabled,
            activeColor = CcPalette.CellularGreen,
            onClick = controller::toggleCellularData,
        ) { SignalBarsGlyph(activeBars = if (state.cellularEnabled) 4 else 1, size = 19.dp) }

        ConnectivityDetailRow(
            title = "Bluetooth",
            subtitle = state.bluetoothLabel,
            active = state.bluetoothEnabled,
            activeColor = CcPalette.ElectricBlue,
            onClick = controller::toggleBluetooth,
        ) { BluetoothRuneGlyph(Color.White, 20.dp) }

        ConnectivityDetailRow(
            title = "Personal Hotspot",
            subtitle = if (state.hotspotEnabled) "Discoverable" else "Off",
            active = state.hotspotEnabled,
            activeColor = CcPalette.CellularGreen,
            onClick = controller::toggleHotspot,
        ) { HotspotLinksGlyph(Color.White, 20.dp) }

        ConnectivityDetailRow(
            title = "Private DNS Shield",
            subtitle = if (state.privateDnsEnabled) "AdGuard DoT Active" else "Automatic",
            active = state.privateDnsEnabled,
            activeColor = CcPalette.ElectricBlue,
            onClick = controller::togglePrivateDns,
        ) { GlobeNetworkGlyph(Color.White, 20.dp) }

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(CapsuleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onBackToGrid)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Done", style = CcType.FocusLabel, color = Color.White)
        }
    }
}

@Composable
private fun ConnectivityDetailRow(
    title: String,
    subtitle: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSubtitleClick ?: onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlassDropletBadge(
            size = 44.dp,
            active = active,
            activeColor = activeColor,
            inactiveColor = Color.White.copy(alpha = 0.18f),
            onClick = onClick,
            icon = icon,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = CcType.SheetRowTitle, color = Color.White)
            Text(subtitle, style = CcType.SheetRowSub, color = Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun ExpandedNowPlayingPage(
    width: Dp,
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onBackToGrid: () -> Unit,
) {
    val cardShape = ContinuousSquircleShape(36.dp)
    val consumeTap = remember { MutableInteractionSource() }
    val latestOnVolumeChange by rememberUpdatedState(onVolumeChange)

    Column(
        modifier = Modifier
            .width(width)
            .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
            .clickable(interactionSource = consumeTap, indication = null) { }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(196.dp)
                .clip(ContinuousSquircleShape(28.dp))
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = state.mediaAlbumArt
            when {
                bmp != null && !bmp.isRecycled -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.mediaUseRainArt -> RainCloudArtCanvas(size = 196.dp)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(state.mediaTitle, style = CcType.SheetTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.mediaArtist.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(state.mediaArtist, style = CcType.SheetRowSub, color = Color.White.copy(alpha = 0.72f))
        }

        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier.fillMaxWidth(0.76f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onPrev), contentAlignment = Alignment.Center) {
                TransportRewindGlyph(Color.White, 28.dp)
            }
            Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                TransportPlayPauseGlyph(playing = state.mediaPlaying, color = Color.White, size = 34.dp)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onNext), contentAlignment = Alignment.Center) {
                TransportFastForwardGlyph(Color.White, 28.dp)
            }
        }

        Spacer(Modifier.height(26.dp))
        // Horizontal Volume capsule (28dp touch target around 10dp iOS capsule track; supports tap + horizontal scrub)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SpeakerWaveGlyph(level = 0f, color = Color.White.copy(alpha = 0.65f), size = 18.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            latestOnVolumeChange((off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { off ->
                                latestOnVolumeChange((off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                latestOnVolumeChange((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CapsuleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.volume.coerceIn(0f, 1f))
                            .height(10.dp)
                            .clip(CapsuleShape)
                            .background(Color.White),
                    )
                }
            }
            SpeakerWaveGlyph(level = 1f, color = Color.White, size = 20.dp)
        }

        Spacer(Modifier.height(22.dp))
        Box(
            modifier = Modifier
                .clip(CapsuleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onBackToGrid)
                .padding(horizontal = 22.dp, vertical = 8.dp),
        ) {
            Text("Done", style = CcType.FocusLabel, color = Color.White)
        }
    }
}

@Composable
private fun PowerMenuSheetOverlay(
    glassConfig: CcGlassConfig,
    onAction: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val consumeTap = remember { MutableInteractionSource() }
    val sheetShape = ContinuousSquircleShape(32.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(304.dp)
                .ccGlassPlatter(sheetShape, glassConfig, isLargePlatter = true)
                .clip(sheetShape)
                .background(Color(0x52101828))
                .clickable(interactionSource = consumeTap, indication = null) { }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerGlyph(color = CcPalette.SilentCoral, size = 28.dp)
            Text("System Power", style = CcType.SheetTitle, color = Color.White)
            Spacer(Modifier.height(4.dp))
            PowerActionPill("Power Off", CcPalette.SilentCoral) { onAction("shutdown") }
            PowerActionPill("Restart Android 17", CcPalette.ElectricBlue) { onAction("reboot") }
            PowerActionPill("Reboot to TWRP Recovery", CcPalette.AirplaneOrange) { onAction("recovery") }
            PowerActionPill("Cancel", Color.White.copy(alpha = 0.18f), onClick = onCancel)
        }
    }
}

@Composable
private fun PowerActionPill(label: String, bg: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CapsuleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CcType.SheetRowTitle, color = Color.White)
    }
}

@Composable
private fun QuickTimerSheetOverlay(
    remainingSec: Int,
    glassConfig: CcGlassConfig,
    onSelectSeconds: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val consumeTap = remember { MutableInteractionSource() }
    val sheetShape = ContinuousSquircleShape(32.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.54f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(296.dp)
                .ccGlassPlatter(sheetShape, glassConfig, isLargePlatter = true)
                .clip(sheetShape)
                .background(Color(0x52101828))
                .clickable(interactionSource = consumeTap, indication = null) { }
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimerDialGlyph(color = CcPalette.AirplaneOrange, size = 28.dp)
            Text(
                text = if (remainingSec > 0) "Timer • ${remainingSec / 60}m ${remainingSec % 60}s" else "Quick Timer",
                style = CcType.SheetTitle,
                color = Color.White,
            )
            for ((label, secs) in listOf("1 Minute" to 60, "3 Minutes" to 180, "5 Minutes" to 300, "10 Minutes" to 600, "15 Minutes" to 900)) {
                PowerActionPill(label, Color.White.copy(alpha = 0.18f)) { onSelectSeconds(secs) }
            }
            if (remainingSec > 0) {
                PowerActionPill("Stop Timer", CcPalette.SilentCoral) { onSelectSeconds(0) }
            }
        }
    }
}
