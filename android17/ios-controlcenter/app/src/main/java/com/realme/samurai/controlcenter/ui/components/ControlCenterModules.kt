package com.realme.samurai.controlcenter.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realme.samurai.controlcenter.data.ControlCenterState
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.ccGlassPlatter
import com.realme.samurai.controlcenter.ui.icons.AirDropGlyph
import com.realme.samurai.controlcenter.ui.icons.AirPlayRouteGlyph
import com.realme.samurai.controlcenter.ui.icons.AirplaneGlyph
import com.realme.samurai.controlcenter.ui.icons.AlarmMiniGlyph
import com.realme.samurai.controlcenter.ui.icons.BluetoothRuneGlyph
import com.realme.samurai.controlcenter.ui.icons.BulbPillGlyph
import com.realme.samurai.controlcenter.ui.icons.FocusPersonGlyph
import com.realme.samurai.controlcenter.ui.icons.GlobeNetworkGlyph
import com.realme.samurai.controlcenter.ui.icons.HotspotLinksGlyph
import com.realme.samurai.controlcenter.ui.icons.IosBatteryStatusGlyph
import com.realme.samurai.controlcenter.ui.icons.OrientationLockGlyph
import com.realme.samurai.controlcenter.ui.icons.PlusGlyph
import com.realme.samurai.controlcenter.ui.icons.PowerGlyph
import com.realme.samurai.controlcenter.ui.icons.RailConnectivityGlyph
import com.realme.samurai.controlcenter.ui.icons.RailHeartGlyph
import com.realme.samurai.controlcenter.ui.icons.RailHomeGlyph
import com.realme.samurai.controlcenter.ui.icons.RailMusicGlyph
import com.realme.samurai.controlcenter.ui.icons.RainCloudArtCanvas
import com.realme.samurai.controlcenter.ui.icons.SignalBarsGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportFastForwardGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportPlayPauseGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportRewindGlyph
import com.realme.samurai.controlcenter.ui.icons.UpDownChevronsGlyph
import com.realme.samurai.controlcenter.ui.icons.WifiFanGlyph
import com.realme.samurai.controlcenter.ui.theme.CapsuleShape
import com.realme.samurai.controlcenter.ui.theme.CcPalette
import com.realme.samurai.controlcenter.ui.theme.CcType
import com.realme.samurai.controlcenter.ui.theme.ContinuousSquircleShape

@Composable
fun TopUtilityChromeBar(
    state: ControlCenterState,
    onAddTap: () -> Unit,
    onPowerTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Row A: Top-left '+' and Top-right Power glyph
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAddTap),
                contentAlignment = Alignment.Center,
            ) {
                PlusGlyph(color = Color.White, size = 15.dp)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPowerTap),
                contentAlignment = Alignment.Center,
            ) {
                PowerGlyph(color = Color.White, size = 16.dp)
            }
        }

        Spacer(Modifier.height(18.dp))

        // Row B: Status bar summary directly above 2x2 platters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SignalBarsGlyph(activeBars = state.signalBars, size = 15.dp)
                Text(
                    text = state.carrierLabel,
                    style = CcType.StatusCarrier,
                    color = Color.White,
                    maxLines = 1,
                )
                if (state.wifiEnabled) {
                    WifiFanGlyph(color = Color.White, size = 15.dp)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (state.hasAlarm) {
                    AlarmMiniGlyph(color = Color.White, size = 12.dp)
                }
                if (state.orientationLocked) {
                    OrientationLockGlyph(color = Color.White, size = 13.dp)
                }
                if (state.focusActive) {
                    FocusPersonGlyph(color = Color.White, size = 12.dp)
                }
                IosBatteryStatusGlyph(
                    levelPct = state.batteryPct,
                    charging = state.isCharging,
                )
            }
        }
    }
}

@Composable
fun RightVerticalPageRail(
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        RailIconButton(active = selectedPage == 0, onClick = { onSelectPage(0) }) { c ->
            RailHeartGlyph(color = c, size = 13.dp)
        }
        RailIconButton(active = false, onClick = { onSelectPage(0) }) { c ->
            RailHomeGlyph(color = c, size = 13.5.dp)
        }
        RailIconButton(active = selectedPage == 2, onClick = { onSelectPage(2) }) { c ->
            RailConnectivityGlyph(color = c, size = 13.5.dp)
        }
        RailIconButton(active = selectedPage == 1, onClick = { onSelectPage(1) }) { c ->
            RailMusicGlyph(color = c, size = 13.dp)
        }
        // Subtle page dots below icons matching ref_cc_clear_crop.png
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.36f))
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.36f))
        )
    }
}

@Composable
private fun RailIconButton(
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (active) Color.White else Color.White.copy(alpha = 0.42f)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

/**
 * 2x2 Connectivity Liquid Glass Platter (Airplane, Quick Share/AirDrop, Wi-Fi, and 2x2 Mini Cluster).
 */
@Composable
fun ConnectivityPlatter2x2(
    size: Dp,
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onToggleAirplane: () -> Unit,
    onToggleQuickShare: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCellular: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleHotspot: () -> Unit,
    onTogglePrivateDns: () -> Unit,
    onExpandConnectivity: () -> Unit,
) {
    val platterShape = ContinuousSquircleShape(size * 0.205f)
    val innerPad = size * 0.082f
    val innerGap = size * 0.068f
    val slotSize = (size - innerPad * 2f - innerGap) / 2f
    val miniGap = slotSize * 0.10f
    val miniSize = (slotSize - miniGap) / 2f
    val latestOnExpandConnectivity by rememberUpdatedState(onExpandConnectivity)

    Box(
        modifier = Modifier
            .size(size)
            .ccGlassPlatter(platterShape, glassConfig, isLargePlatter = true)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { latestOnExpandConnectivity() },
                    onTap = { latestOnExpandConnectivity() },
                )
            }
            .padding(innerPad),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(innerGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(innerGap)) {
                // [0,0] Airplane Mode
                PlatterCircleToggle(
                    size = slotSize,
                    active = state.airplaneMode,
                    activeColor = CcPalette.AirplaneOrange,
                    inactiveColor = Color(0xFF4B92F7).copy(alpha = if (glassConfig.isTinted) 0.46f else 0.56f),
                    onClick = onToggleAirplane,
                ) {
                    AirplaneGlyph(color = Color.White, size = slotSize * 0.44f)
                }

                // [0,1] Quick Share / AirDrop
                PlatterCircleToggle(
                    size = slotSize,
                    active = state.quickShareActive,
                    activeColor = CcPalette.ElectricBlue,
                    inactiveColor = Color.White.copy(alpha = 0.20f),
                    onClick = onToggleQuickShare,
                ) {
                    AirDropGlyph(color = Color.White, size = slotSize * 0.46f)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(innerGap)) {
                // [1,0] Wi-Fi
                PlatterCircleToggle(
                    size = slotSize,
                    active = state.wifiEnabled,
                    activeColor = CcPalette.ElectricBlue,
                    inactiveColor = Color.White.copy(alpha = 0.20f),
                    onClick = onToggleWifi,
                ) {
                    WifiFanGlyph(color = Color.White, size = slotSize * 0.42f)
                }

                // [1,1] 2x2 Mini Cluster (Cellular, Bluetooth, Hotspot, Private DNS)
                Column(verticalArrangement = Arrangement.spacedBy(miniGap)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(miniGap)) {
                        PlatterCircleToggle(
                            size = miniSize,
                            active = state.cellularEnabled,
                            activeColor = CcPalette.CellularGreen,
                            inactiveColor = Color.White.copy(alpha = 0.22f),
                            onClick = onToggleCellular,
                        ) {
                            SignalBarsGlyph(
                                activeBars = if (state.cellularEnabled) 4 else 1,
                                size = miniSize * 0.50f,
                            )
                        }
                        PlatterCircleToggle(
                            size = miniSize,
                            active = state.bluetoothEnabled,
                            activeColor = CcPalette.ElectricBlue,
                            inactiveColor = Color.White.copy(alpha = 0.22f),
                            onClick = onToggleBluetooth,
                        ) {
                            BluetoothRuneGlyph(color = Color.White, size = miniSize * 0.56f)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(miniGap)) {
                        PlatterCircleToggle(
                            size = miniSize,
                            active = state.hotspotEnabled,
                            activeColor = CcPalette.CellularGreen,
                            inactiveColor = Color(0xFF4CA2F8).copy(alpha = 0.46f),
                            onClick = onToggleHotspot,
                        ) {
                            HotspotLinksGlyph(color = Color.White, size = miniSize * 0.58f)
                        }
                        PlatterCircleToggle(
                            size = miniSize,
                            active = state.privateDnsEnabled,
                            activeColor = CcPalette.ElectricBlue,
                            inactiveColor = Color(0xFF4CA2F8).copy(alpha = 0.46f),
                            onClick = onTogglePrivateDns,
                        ) {
                            GlobeNetworkGlyph(color = Color.White, size = miniSize * 0.58f)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Translucent jewel-tinted Liquid Glass droplet badge used inside platters and capsule pills:
 * - Never renders as flat opaque paint; lets parent glass refraction breathe through (`alpha ~ 0.62..0.72`)
 * - Adds a delicate top-left inner meniscus sheen + hair-fine 0.6dp 34° crystal specular rim.
 */
@Composable
fun LiquidGlassDropletBadge(
    size: Dp,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color = Color.White.copy(alpha = 0.16f),
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit,
) {
    val baseColor by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        label = "glassDropletTint",
    )
    val activeAnim by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "glassDropletActive",
    )

    val mod = Modifier
        .size(size)
        .clip(CircleShape)
        .drawBehind {
            val r = this.size.minDimension * 0.5f
            val center = Offset(this.size.width * 0.5f, this.size.height * 0.5f)
            if (activeAnim > 0.01f) {
                // Translucent jewel-glass body (blended with underlying refracted platter, never flat 1.0 opaque!)
                val topAlpha = (0.72f * activeAnim + baseColor.alpha * (1f - activeAnim)).coerceIn(0f, 0.78f)
                val midAlpha = (0.54f * activeAnim + baseColor.alpha * (1f - activeAnim)).coerceIn(0f, 0.62f)
                val botAlpha = (0.64f * activeAnim + baseColor.alpha * (1f - activeAnim)).coerceIn(0f, 0.70f)
                drawCircle(
                    brush = Brush.linearGradient(
                        0.00f to baseColor.copy(alpha = topAlpha),
                        0.55f to baseColor.copy(alpha = midAlpha),
                        1.00f to baseColor.copy(alpha = botAlpha),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    ),
                    radius = r,
                    center = center,
                )
                // Inner top-left meniscus glow
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.White.copy(alpha = 0.24f * activeAnim),
                        0.68f to Color.Transparent,
                        center = Offset(this.size.width * 0.32f, this.size.height * 0.24f),
                        radius = r * 0.95f,
                    ),
                    radius = r,
                    center = center,
                )
            } else {
                drawCircle(color = baseColor, radius = r, center = center)
            }

            // Hair-fine 34-deg crystal specular rim so droplet blends naturally with Liquid Glass
            val rimAlphaTop = 0.26f + 0.18f * activeAnim
            val rimAlphaBot = 0.11f + 0.10f * activeAnim
            drawCircle(
                brush = Brush.linearGradient(
                    0.00f to Color.White.copy(alpha = rimAlphaTop),
                    0.50f to Color.White.copy(alpha = 0.06f),
                    1.00f to Color.White.copy(alpha = rimAlphaBot),
                    start = Offset.Zero,
                    end = Offset(this.size.width, this.size.height),
                ),
                radius = (r - 0.35.dp.toPx()).coerceAtLeast(1f),
                center = center,
                style = Stroke(width = 0.65.dp.toPx()),
            )
        }
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }

    Box(
        modifier = mod,
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

@Composable
private fun PlatterCircleToggle(
    size: Dp,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    LiquidGlassDropletBadge(
        size = size,
        active = active,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        onClick = onClick,
        icon = icon,
    )
}

/**
 * 2x2 Now Playing Media Platter matching both active playback ("Rain Classic") and idle ("Not Playing").
 */
@Composable
fun NowPlayingPlatter2x2(
    size: Dp,
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpandMedia: () -> Unit,
) {
    val platterShape = ContinuousSquircleShape(size * 0.205f)
    val pad = size * 0.095f
    val artSize = size * 0.305f
    val artShape = ContinuousSquircleShape(artSize * 0.24f)
    val hasTrack = state.mediaTitle != "Not Playing"

    Column(
        modifier = Modifier
            .size(size)
            .ccGlassPlatter(platterShape, glassConfig, isLargePlatter = true)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExpandMedia,
            )
            .padding(pad),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(artSize)
                    .clip(artShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp: Bitmap? = state.mediaAlbumArt
                when {
                    bmp != null && !bmp.isRecycled -> {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    state.mediaUseRainArt -> {
                        RainCloudArtCanvas(size = artSize)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(size * 0.205f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                AirPlayRouteGlyph(
                    color = Color.White,
                    isPhoneIcon = state.audioRoutePhone,
                    size = size * 0.115f,
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = state.mediaTitle,
                style = CcType.MediaTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sideTint = if (hasTrack) Color.White else Color.White.copy(alpha = 0.38f)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                TransportRewindGlyph(color = sideTint, size = 19.dp)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                TransportPlayPauseGlyph(
                    playing = state.mediaPlaying && !state.mediaUseRainArt,
                    color = Color.White,
                    size = 22.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                TransportFastForwardGlyph(color = sideTint, size = 19.dp)
            }
        }
    }
}

/**
 * 1x1 Circular Control Center Toggle:
 * ALWAYS rendered via [ccGlassPlatter] so OFF and ON share the exact same circle geometry, shadow,
 * meniscus refraction, caustic, and 34° crystal rim — illuminating smoothly from within when active.
 */
@Composable
fun CircularControlTile1x1(
    size: Dp,
    active: Boolean,
    glassConfig: CcGlassConfig,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable (isActive: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "circleScale",
    )
    val activeFrac by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "circleActiveGlass",
    )
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .ccGlassPlatter(
                shape = CircleShape,
                glassConfig = glassConfig,
                activeFraction = activeFrac,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(it)
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        interactionSource.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
                    },
                    onTap = { latestOnClick() },
                    onLongPress = { latestOnLongClick?.invoke() ?: latestOnClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        icon(active)
    }
}

/**
 * 1x2 Vertical Capsule Slider (Brightness & Volume):
 * Liquid column is rendered directly INSIDE [ccGlassPlatter] (`sliderFill = clamped`) so the fill
 * refracts the wallpaper and sits seamlessly inside the curved glass capsule walls with zero overlay box!
 */
@Composable
fun VerticalCapsuleSlider1x2(
    width: Dp,
    height: Dp,
    value: Float,
    glassConfig: CcGlassConfig,
    onValueChange: (Float) -> Unit,
    onLongPress: (() -> Unit)? = null,
    bottomIcon: @Composable () -> Unit,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val clamped = value.coerceIn(0f, 1f)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnLongPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .ccGlassPlatter(
                shape = CapsuleShape,
                glassConfig = glassConfig,
                sliderFill = clamped,
            )
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val frac = 1f - (offset.y / heightPx)
                        latestOnValueChange(frac.coerceIn(0f, 1f))
                    },
                    onLongPress = { latestOnLongPress?.invoke() },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val frac = 1f - (change.position.y / heightPx)
                    latestOnValueChange(frac.coerceIn(0f, 1f))
                }
            },
    ) {
        // Colored glyph anchored near bottom semicircular cap
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = width * 0.25f),
            contentAlignment = Alignment.Center,
        ) {
            bottomIcon()
        }
    }
}

/**
 * 2x1 Focus / Do Not Disturb Capsule Pill:
 * Concentrically aligned with [HardwareStatusPill2x1] (`circleSize = height * 0.62f`, exact concentric
 * left inset `(height - circleSize) / 2`) and blended Liquid Glass illumination when active.
 */
@Composable
fun FocusCapsulePill2x1(
    width: Dp,
    height: Dp,
    active: Boolean,
    label: String,
    glassConfig: CcGlassConfig,
    onClick: () -> Unit,
) {
    val circleSize = height * 0.62f
    val concentricInset = (height - circleSize) * 0.5f
    val activeFrac by animateFloatAsState(
        targetValue = if (active) 0.28f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "focusActiveGlass",
    )

    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .ccGlassPlatter(
                shape = CapsuleShape,
                glassConfig = glassConfig,
                activeFraction = activeFrac,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = concentricInset, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlassDropletBadge(
            size = circleSize,
            active = active,
            activeColor = Color.White,
            inactiveColor = Color.White.copy(alpha = 0.18f),
        ) {
            FocusPersonGlyph(
                color = if (active) CcPalette.FocusPurple else Color.White,
                size = circleSize * 0.48f,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = CcType.FocusLabel,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UpDownChevronsGlyph(color = Color.White.copy(alpha = 0.72f), size = 10.dp)
        }
    }
}

/**
 * 2x1 Hardware Feature Capsule Pill ("Category / Name / On|Off"):
 * ALWAYS rendered via [ccGlassPlatter] with exact Euclidean capsule geometry and concentric left droplet
 * alignment matching [FocusCapsulePill2x1] (`0.00 px` shape/size/alignment difference between Off and On!).
 */
@Composable
fun HardwareStatusPill2x1(
    width: Dp,
    height: Dp,
    category: String,
    title: String,
    active: Boolean,
    statusText: String = if (active) "On" else "Off",
    glassConfig: CcGlassConfig,
    onClick: () -> Unit,
) {
    val circleSize = height * 0.62f
    val concentricInset = (height - circleSize) * 0.5f
    val activeFrac by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pillActiveGlass",
    )

    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .ccGlassPlatter(
                shape = CapsuleShape,
                glassConfig = glassConfig,
                activeFraction = activeFrac,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = concentricInset, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlassDropletBadge(
            size = circleSize,
            active = active,
            activeColor = CcPalette.BulbYellow,
            inactiveColor = Color(0xFFB8A258).copy(alpha = 0.28f),
        ) {
            BulbPillGlyph(
                color = if (active) Color.White else CcPalette.BulbYellow,
                size = circleSize * 0.50f,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = category,
                style = CcType.PillCategory,
                color = if (active) Color(0xCC18202B) else CcPalette.InactiveSubText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = CcType.PillTitle,
                color = if (active) Color(0xFF0E131B) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText,
                style = CcType.PillState,
                color = if (active) Color(0xCC18202B) else CcPalette.InactiveSubText,
                maxLines = 1,
            )
        }
    }
}
