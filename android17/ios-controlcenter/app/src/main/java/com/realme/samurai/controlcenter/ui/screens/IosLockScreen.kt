package com.realme.samurai.controlcenter.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker as ComposeVelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.data.ControlCenterState
import com.realme.samurai.controlcenter.service.IosNotificationAction
import com.realme.samurai.controlcenter.service.IosNotificationItem
import com.realme.samurai.controlcenter.service.IosNotificationRepository
import com.realme.samurai.controlcenter.service.UdfpsSensorGeometry
import com.realme.samurai.controlcenter.ui.components.CircularControlTile1x1
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.Ios26HomeIndicatorBar
import com.realme.samurai.controlcenter.ui.glass.Ios26RefractiveClockGlass
import com.realme.samurai.controlcenter.ui.glass.LockScreenGlassSceneHost
import com.realme.samurai.controlcenter.ui.glass.ccGlassPlatter
import com.realme.samurai.controlcenter.ui.glass.clipWithMovingGlassRim
import com.realme.samurai.controlcenter.ui.icons.AirPlayRouteGlyph
import com.realme.samurai.controlcenter.ui.icons.CameraControlGlyph
import com.realme.samurai.controlcenter.ui.icons.FlashlightGlyph
import com.realme.samurai.controlcenter.ui.icons.FocusPersonGlyph
import com.realme.samurai.controlcenter.ui.icons.IosBatteryStatusGlyph
import com.realme.samurai.controlcenter.ui.icons.RainCloudArtCanvas
import com.realme.samurai.controlcenter.ui.icons.SignalBarsGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportFastForwardGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportPlayPauseGlyph
import com.realme.samurai.controlcenter.ui.icons.TransportRewindGlyph
import com.realme.samurai.controlcenter.ui.icons.WifiFanGlyph
import com.realme.samurai.controlcenter.ui.theme.CapsuleShape
import com.realme.samurai.controlcenter.ui.theme.CcPalette
import com.realme.samurai.controlcenter.ui.theme.CcType
import com.realme.samurai.controlcenter.ui.theme.ContinuousSquircleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 1:1 Apple iOS 26 Liquid Glass Lock Screen (`ref_ios26_ls_9to5mac.jpg` & `ref_apple_wwdc25_hero.jpg`):
 * - 3D cylindrical wallpaper-refracting Liquid Glass numerals (`0 = Glass`, `1 = Solid`) with circular glass colon droplets
 * - Top status bar + carrier + right Control Center grabber pill + animated iOS Padlock shackle
 * - Compact Now Playing platter + 1-tap expandable Full-Screen Apple Music Lock Screen player
 * - Bottom-up stacked Dock-grade Liquid Glass notifications with stacked card peek & swipe-to-clear
 * - Bottom 50dp Liquid Glass Flashlight & Camera droplets + RMX1931 Goodix G3 optical UDFPS instant touch target
 *   + 90Hz finger-tracking Swipe-Up-to-Unlock curtain.
 */
@Composable
fun IosLockScreenRoot(
    controller: ControlCenterController,
    glassConfig: CcGlassConfig,
    curtainDismissProgress: Float = 0f,
    curtainVelocityPxPerSec: Float = 0f,
    gesturePillEnabled: Boolean = true,
    isSecureKeyguard: Boolean = false,
    isUdfpsFingerDown: Boolean = false,
    hasEnrolledUdfps: Boolean = true,
    isLockScreenVisible: Boolean = true,
    lockSessionEpoch: Int = 0,
    lockWakeTick: Int = 0,
    previewClockText: String? = null,
    previewDateText: String? = null,
    previewClockStyle: Int? = null,
    previewExpandedMediaArt: Boolean = false,
    previewNotifications: List<IosNotificationItem>? = null,
    onSwipeStart: () -> Unit = {},
    onSwipeProgressChange: (Float) -> Unit = {},
    onSwipeVelocityChange: (Float) -> Unit = {},
    onSwipeCancelled: () -> Unit = {},
    onUnlockRequested: ((() -> Unit)?) -> Unit = {},
    onDirectUdfpsTouch: () -> Unit = {},
    onOpenControlCenter: () -> Unit = {},
    onOpenCameraSecure: () -> Unit = controller::openCamera,
    onDismissNotification: (String) -> Unit = IosNotificationRepository::dismissOne,
) {
    var expandedMediaArt by remember(previewExpandedMediaArt) { mutableStateOf(previewExpandedMediaArt) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var showCustomizeBar by remember { mutableStateOf(false) }
    var localDragProgress by remember { mutableFloatStateOf(0f) }
    var hostRootTopPx by remember { mutableFloatStateOf(0f) }
    var notifColumnTopPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var notifColumnBottomPx by remember { mutableFloatStateOf(0f) }
    val udfpsHoldConsumedRef = remember { BooleanArray(1) }
    val notifLazyListState = rememberLazyListState()

    // Reset transient Lock Screen UI state (expanded notification scroll deck, customize bar, expanded art)
    // across every lock/screen-off cycle so accumulated notifications never trap swipe-to-unlock.
    LaunchedEffect(isLockScreenVisible, lockSessionEpoch) {
        notificationsExpanded = false
        showCustomizeBar = false
        expandedMediaArt = previewExpandedMediaArt
        localDragProgress = 0f
        notifColumnTopPx = Float.MAX_VALUE
        notifColumnBottomPx = 0f
        if (notifLazyListState.firstVisibleItemIndex != 0 || notifLazyListState.firstVisibleItemScrollOffset != 0) {
            notifLazyListState.scrollToItem(0)
        }
    }

    // Complete background quiescence while unlocked & hidden: return BEFORE reading `controller.state`,
    // `glassConfig`, or `IosNotificationRepository.notifications` so zero Snapshot subscriptions remain active.
    if (!isLockScreenVisible && curtainDismissProgress <= 0.001f) {
        return
    }

    val state = controller.state
    val context = LocalContext.current
    val density = LocalDensity.current

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (!controller.previewOnly && previewClockText == null && isLockScreenVisible) {
        DisposableEffect(context, isLockScreenVisible) {
            nowMillis = System.currentTimeMillis()
            val recv = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    nowMillis = System.currentTimeMillis()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(recv, filter)
                }
            } catch (_: Throwable) {
            }
            onDispose {
                try {
                    context.unregisterReceiver(recv)
                } catch (_: Throwable) {
                }
            }
        }
    }

    var clockStyle by remember(previewClockStyle) {
        mutableIntStateOf(
            previewClockStyle ?: try {
                Settings.Global.getInt(context.contentResolver, "samurai_lockscreen_clock_style", 0).coerceIn(0, 1)
            } catch (_: Throwable) {
                0
            }
        )
    }

    if (!controller.previewOnly && previewClockStyle == null && isLockScreenVisible) {
        DisposableEffect(context, isLockScreenVisible) {
            val cr = context.contentResolver
            val refreshClockStyle = {
                clockStyle = try {
                    Settings.Global.getInt(cr, "samurai_lockscreen_clock_style", 0).coerceIn(0, 1)
                } catch (_: Throwable) {
                    0
                }
            }
            refreshClockStyle()
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refreshClockStyle()
                }
            }
            try {
                cr.registerContentObserver(Settings.Global.getUriFor("samurai_lockscreen_clock_style"), false, obs)
            } catch (_: Throwable) {
            }
            onDispose {
                try {
                    cr.unregisterContentObserver(obs)
                } catch (_: Throwable) {
                }
            }
        }
    }

    val is24h = remember(context, nowMillis, controller.previewOnly) {
        if (controller.previewOnly) false else DateFormat.is24HourFormat(context)
    }
    val clockText = previewClockText ?: remember(nowMillis, is24h) {
        val pattern = if (is24h) "HH:mm" else "h:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(nowMillis))
    }
    val shortDateText = previewDateText ?: remember(nowMillis) {
        SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(Date(nowMillis))
    }

    val notifications = when {
        previewNotifications != null -> previewNotifications
        isLockScreenVisible || controller.previewOnly -> IosNotificationRepository.notifications
        else -> emptyList()
    }
    val hasMedia = state.mediaPlaying || state.mediaTitle != "Not Playing"

    LaunchedEffect(lockWakeTick, isLockScreenVisible) {
        if (previewClockText == null) {
            nowMillis = System.currentTimeMillis()
        }
    }

    LaunchedEffect(notificationsExpanded, notifications.isEmpty(), hasMedia && expandedMediaArt) {
        if (!notificationsExpanded || notifications.isEmpty() || (hasMedia && expandedMediaArt)) {
            notifColumnTopPx = Float.MAX_VALUE
            notifColumnBottomPx = 0f
        }
    }

    val effectiveLift = (if (controller.previewOnly) {
        max(curtainDismissProgress, localDragProgress)
    } else {
        curtainDismissProgress
    }).coerceIn(0f, 1f)
    val contentAlpha = if (effectiveLift <= 0.65f) {
        1f
    } else {
        ((1f - effectiveLift) / 0.35f).coerceIn(0f, 1f)
    }
    val outsideBackdropAlpha = if (isSecureKeyguard && !controller.previewOnly) 1f else 0f

    val sysStatusInsetDp = remember(context, density, controller.previewOnly) {
        if (controller.previewOnly) {
            0.dp
        } else {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val px = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
            val rawDp = with(density) { px.toDp() }
            if (rawDp > 30.dp) rawDp else 30.dp
        }
    }

    // Dynamic clock height matching real iOS 26 (`ref_ios26_ls_9to5mac.jpg` tall 348dp hero clock reaching across
    // the wallpaper horizon when open, compact 168dp when notifications or media are stacked at the bottom)
    val targetClockHeightDp: Dp = when {
        hasMedia && notifications.isNotEmpty() -> 128.dp
        notifications.size >= 2 || hasMedia -> 168.dp
        notifications.size == 1 -> 218.dp
        else -> 348.dp
    }
    val animatedClockHeightDp by animateDpAsState(
        targetValue = targetClockHeightDp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ios26DynamicClockHeight",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = (if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
            constraints.maxWidth.toFloat()
        } else {
            with(density) { 412.dp.toPx() }
        }).coerceAtLeast(320f)
        val screenH = (if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            with(density) { 800.dp.toPx() }
        }).coerceAtLeast(640f)
        val sheetEdgeYPx = ((1f - effectiveLift) * screenH).coerceIn(0f, screenH)
        val liftTranslationYPx = -effectiveLift * screenH
        val udfpsSpec = remember(context, screenW, screenH) {
            UdfpsSensorGeometry.resolve(
                context = if (controller.previewOnly) null else context,
                viewportWidthPx = screenW,
                viewportHeightPx = screenH,
            )
        }
        val lowerStackBottomPaddingDp = if (isSecureKeyguard && hasEnrolledUdfps) {
            val minBottomClearancePx = (screenH - (udfpsSpec.centerYPx - udfpsSpec.radiusPx - 12f)).coerceAtLeast(0f)
            val clearanceDp = with(density) { minBottomClearancePx.toDp() }
            if (clearanceDp > 104.dp) clearanceDp else 104.dp
        } else {
            104.dp
        }
        val currentScreenW by rememberUpdatedState(screenW)
        val currentScreenH by rememberUpdatedState(screenH)
        val currentUdfpsSpec by rememberUpdatedState(udfpsSpec)
        val currentIsSecureKeyguard by rememberUpdatedState(isSecureKeyguard)
        val currentEffectiveLift by rememberUpdatedState(effectiveLift)
        val currentNotificationsExpanded by rememberUpdatedState(notificationsExpanded)
        val currentNotifTopPx by rememberUpdatedState(notifColumnTopPx)
        val currentNotifBottomPx by rememberUpdatedState(notifColumnBottomPx)
        val currentExpandedMediaOverlay by rememberUpdatedState(hasMedia && expandedMediaArt)
        val latestOnSwipeStart by rememberUpdatedState(onSwipeStart)
        val latestOnSwipeProgressChange by rememberUpdatedState(onSwipeProgressChange)
        val latestOnSwipeVelocityChange by rememberUpdatedState(onSwipeVelocityChange)
        val latestOnSwipeCancelled by rememberUpdatedState(onSwipeCancelled)
        val latestOnUnlockRequested by rememberUpdatedState(onUnlockRequested)
        val latestOnDirectUdfpsTouch by rememberUpdatedState(onDirectUdfpsTouch)

        LockScreenGlassSceneHost(
            wallpaperBitmap = state.wallpaperBitmap,
            glassConfig = glassConfig,
            blurBackdrop = hasMedia && expandedMediaArt,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isUdfpsFingerDown && isSecureKeyguard && hasEnrolledUdfps) {
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawCircle(
                                    color = Color.Transparent,
                                    radius = udfpsSpec.radiusPx + 4f,
                                    center = Offset(udfpsSpec.centerXPx, udfpsSpec.centerYPx),
                                    blendMode = BlendMode.Clear,
                                )
                            }
                    } else {
                        Modifier
                    }
                )
                .onGloballyPositioned { coords ->
                    hostRootTopPx = coords.positionInRoot().y
                }
                .pointerInput(isSecureKeyguard, hasEnrolledUdfps) {
                    // RMX1931 Goodix G3 Optical UDFPS Stationary Circular Sensor Zone
                    // (`config_udfps_sensor_props = [540, 2156, 95]` in physical pixels, zero dp drift):
                    // On live Android 17 hardware, `UdfpsSensorGeometry.installTouchAperture` carves out
                    // this exact circle via `TOUCHABLE_INSETS_REGION` so Press #1 lands at 0ms directly on
                    // SystemUI's `UdfpsControllerOverlay`. When Compose receives touches here (e.g. unit tests
                    // or fallback mode), stationary holds >=220ms invoke `onDirectUdfpsTouch()` while swipes
                    // starting inside the zone still unlock cleanly!
                    if (!isSecureKeyguard || !hasEnrolledUdfps) {
                        udfpsHoldConsumedRef[0] = false
                        return@pointerInput
                    }
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        udfpsHoldConsumedRef[0] = false
                        val downPos = down.position
                        if (!currentUdfpsSpec.contains(downPos.x, downPos.y)) return@awaitEachGesture
                        val holdCompleted = withTimeoutOrNull(220L) {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (!ch.pressed) {
                                    return@withTimeoutOrNull false
                                }
                                val dx = ch.position.x - downPos.x
                                val dy = ch.position.y - downPos.y
                                if (dx * dx + dy * dy > slop * slop) {
                                    return@withTimeoutOrNull false
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                        if (holdCompleted == null) {
                            udfpsHoldConsumedRef[0] = true
                            controller.tickHaptic()
                            latestOnDirectUdfpsTouch()
                            while (true) {
                                val postEv = awaitPointerEvent(PointerEventPass.Initial)
                                postEv.changes.forEach { it.consume() }
                                if (postEv.changes.none { it.pressed }) break
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Untranslated PointerEventPass.Initial vertical-unlock gesture handler with multi-pointer handoff:
                    // - Preempts `LazyColumn` notification scroll whenever swiping up from the bottom 108dp lip,
                    //   when notifications are collapsed, when touching ABOVE/OUTSIDE the expanded notification box,
                    //   OR when `notifLazyListState` is already at the bottom (`!notifLazyListState.canScrollForward`) at
                    //   `ACTION_DOWN`, while letting scroll gestures that begin inside the expanded notification box
                    //   with `canScrollForward == true` scroll cleanly to the bottom without accidental mid-flick unlock.
                    val slop = viewConfiguration.touchSlop
                    val bottomLipPx = 108.dp.toPx()
                    val vt = ComposeVelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        if (!currentIsSecureKeyguard) {
                            udfpsHoldConsumedRef[0] = false
                        }
                        vt.resetTracking()
                        vt.addPointerInputChange(down)
                        var activePointerId = down.id
                        var startPos = down.position
                        val startedInBottomLip = startPos.y >= (currentScreenH - bottomLipPx)
                        val startedInsideNotifScroll = currentNotificationsExpanded &&
                            currentNotifBottomPx > currentNotifTopPx &&
                            startPos.y >= currentNotifTopPx &&
                            startPos.y <= currentNotifBottomPx
                        val deferUpwardToNotifScroll = startedInsideNotifScroll &&
                            !startedInBottomLip &&
                            notifLazyListState.canScrollForward
                        if (deferUpwardToNotifScroll) {
                            return@awaitEachGesture
                        }
                        var anchorX = startPos.x
                        var anchorY = startPos.y
                        var curtainDragging = false
                        var activeProg = 0f

                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (udfpsHoldConsumedRef[0]) {
                                    if (curtainDragging) {
                                        curtainDragging = false
                                        vt.resetTracking()
                                        latestOnSwipeVelocityChange(0f)
                                        if (controller.previewOnly) {
                                            localDragProgress = 0f
                                        }
                                        latestOnSwipeCancelled()
                                    }
                                    break
                                }
                                val ch = ev.changes.firstOrNull { it.id == activePointerId } ?: ev.changes.firstOrNull()
                                if (ch == null) break
                                if (ch.id != activePointerId) {
                                    val surviving = ev.changes.firstOrNull { it.pressed } ?: break
                                    activePointerId = surviving.id
                                    startPos = surviving.position
                                    anchorX = surviving.position.x
                                    anchorY = surviving.position.y + activeProg * currentScreenH
                                    vt.resetTracking()
                                    vt.addPointerInputChange(surviving)
                                    continue
                                }
                                vt.addPointerInputChange(ch)
                                if (!ch.pressed) {
                                    val surviving = ev.changes.firstOrNull { it.pressed }
                                    if (surviving != null) {
                                        if (curtainDragging) ch.consume()
                                        activePointerId = surviving.id
                                        startPos = surviving.position
                                        anchorX = surviving.position.x
                                        anchorY = surviving.position.y + activeProg * currentScreenH
                                        vt.resetTracking()
                                        vt.addPointerInputChange(surviving)
                                        continue
                                    }
                                    if (curtainDragging) {
                                        val wasCancelled = ch.isConsumed
                                        curtainDragging = false
                                        ch.consume()
                                        val vy = vt.calculateVelocity().y
                                        vt.resetTracking()
                                        if (wasCancelled) {
                                            latestOnSwipeVelocityChange(0f)
                                            if (controller.previewOnly) {
                                                localDragProgress = 0f
                                            }
                                            latestOnSwipeCancelled()
                                        } else {
                                            latestOnSwipeVelocityChange(vy)
                                            if (activeProg >= 0.22f || vy <= -480f * this.density) {
                                                controller.tickHaptic()
                                                if (controller.previewOnly) {
                                                    localDragProgress = 0f
                                                }
                                                latestOnUnlockRequested(null)
                                            } else {
                                                if (controller.previewOnly) {
                                                    localDragProgress = 0f
                                                }
                                                latestOnSwipeCancelled()
                                            }
                                        }
                                    }
                                    break
                                }

                                if (!curtainDragging) {
                                    val totalDx = ch.position.x - startPos.x
                                    val totalDy = ch.position.y - startPos.y
                                    if ((abs(totalDx) > slop * 1.15f && abs(totalDx) > abs(totalDy)) ||
                                        totalDy > slop * 1.25f
                                    ) {
                                        break
                                    }
                                    val dxFromAnchor = ch.position.x - anchorX
                                    val dyFromAnchor = ch.position.y - anchorY
                                    if (dyFromAnchor < -slop && abs(dyFromAnchor) > abs(dxFromAnchor) * 1.05f) {
                                        curtainDragging = true
                                        ch.consume()
                                        latestOnSwipeVelocityChange(0f)
                                        latestOnSwipeStart()
                                        val postSlopUp = (-(dyFromAnchor + slop)).coerceAtLeast(0f)
                                        anchorY = ch.position.y + postSlopUp
                                        val prog = (postSlopUp / currentScreenH).coerceIn(0f, 1f)
                                        activeProg = prog
                                        if (controller.previewOnly) {
                                            localDragProgress = prog
                                        }
                                        latestOnSwipeVelocityChange(vt.calculateVelocity().y)
                                        latestOnSwipeProgressChange(prog)
                                    }
                                } else {
                                    ch.consume()
                                    val liftPx = (anchorY - ch.position.y).coerceAtLeast(0f)
                                    val prog = (liftPx / currentScreenH).coerceIn(0f, 1f)
                                    activeProg = prog
                                    if (controller.previewOnly) {
                                        localDragProgress = prog
                                    }
                                    latestOnSwipeVelocityChange(vt.calculateVelocity().y)
                                    latestOnSwipeProgressChange(prog)
                                }
                            }
                        } finally {
                            if (curtainDragging) {
                                vt.resetTracking()
                                latestOnSwipeVelocityChange(0f)
                                if (controller.previewOnly) {
                                    localDragProgress = 0f
                                }
                                latestOnSwipeCancelled()
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (!udfpsHoldConsumedRef[0] && showCustomizeBar) {
                                showCustomizeBar = false
                            }
                        },
                        onLongPress = {
                            if (!udfpsHoldConsumedRef[0] && !currentExpandedMediaOverlay) {
                                controller.tickHaptic()
                                showCustomizeBar = !showCustomizeBar
                            }
                        },
                    )
                },
            curtainLiftOffsetProvider = { liftTranslationYPx },
            sheetEdgeYPx = sheetEdgeYPx,
            sheetVelocityPxPerSec = curtainVelocityPxPerSec,
            outsideBackdropAlpha = outsideBackdropAlpha,
            motionTickProvider = {
                notifLazyListState.firstVisibleItemIndex * 1000 + notifLazyListState.firstVisibleItemScrollOffset
            },
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
                            translationY = liftTranslationYPx
                            alpha = contentAlpha
                        },
                ) {
                    // 1. Top iOS 26 Lock Screen Status Bar (flanks waterdrop cutout) + Padlock + Hero Clock
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, top = if (controller.previewOnly) 12.dp else 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LockScreenStatusHeader(
                            state = state,
                            unlockProgress = effectiveLift,
                            padlockTopClearance = if (controller.previewOnly) 4.dp else (sysStatusInsetDp - 8.dp).coerceAtLeast(18.dp),
                            onOpenControlCenter = onOpenControlCenter,
                        )

                        if (hasMedia && expandedMediaArt) {
                            // Phone #2 in Apple WWDC25 Hero: Compact single-line Time + Date above Full-Screen Album Art
                            Spacer(Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = clockText,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        letterSpacing = (-0.3).sp,
                                    ),
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = shortDateText,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 18.sp,
                                        letterSpacing = (-0.2).sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.84f),
                                )
                            }
                            Spacer(Modifier.height(22.dp))
                            // Centered Square Squircle Album Art (tap to collapse back to tall Liquid Glass Clock)
                            Box(
                                modifier = Modifier
                                    .size(276.dp)
                                    .clip(ContinuousSquircleShape(26.dp))
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            controller.tickHaptic()
                                            expandedMediaArt = false
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = state.mediaAlbumArt
                                when {
                                    bmp != null && !bmp.isRecycled -> {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = state.mediaTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    else -> RainCloudArtCanvas(size = 276.dp)
                                }
                            }
                        } else {
                            // Standard / Hero Tall iOS 26 Liquid Glass Lock Screen Clock (Phone #3 in WWDC25 Hero & 9to5Mac)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = shortDateText,
                                style = TextStyle(
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 20.sp,
                                    letterSpacing = (-0.2).sp,
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.28f),
                                        offset = Offset(0f, 2f),
                                        blurRadius = 8f,
                                    ),
                                ),
                                color = Color.White.copy(alpha = 0.95f),
                            )

                            Ios26LiquidGlassClockDisplay(
                                clockText = clockText,
                                isLiquidGlass = clockStyle == 0,
                                glassConfig = glassConfig,
                                targetHeight = animatedClockHeightDp,
                                enableSpatialDepth = !hasMedia && notifications.isEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                            )

                            // Optional Inline Long-Press Lock Screen Customization Pill
                            AnimatedVisibility(
                                visible = showCustomizeBar,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                LockScreenCustomizeBar(
                                    clockStyle = clockStyle,
                                    isTinted = glassConfig.isTinted,
                                    glassConfig = glassConfig,
                                    onSelectClockStyle = { next ->
                                        controller.tickHaptic()
                                        clockStyle = next
                                        if (!controller.previewOnly) {
                                            try {
                                                Settings.Global.putInt(context.contentResolver, "samurai_lockscreen_clock_style", next)
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    },
                                    onToggleGlassTint = controller::toggleLiquidGlassTintMode,
                                )
                            }
                        }
                    }

                    // 2. Lower Stack: Now Playing Platter + Bottom-Up Stacked Notifications (zero circular widget clutter)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = lowerStackBottomPaddingDp)
                            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Live Now Playing Platter (Compact OR Full-Art Expanded Player Card)
                        if (hasMedia) {
                            if (expandedMediaArt) {
                                LockScreenExpandedMusicCard(
                                    state = state,
                                    glassConfig = glassConfig,
                                    onPrev = controller::mediaPrevious,
                                    onPlayPause = controller::mediaPlayPause,
                                    onNext = controller::mediaNext,
                                )
                            } else {
                                LockScreenCompactMediaPlatter(
                                    state = state,
                                    glassConfig = glassConfig,
                                    onTapAlbumArt = {
                                        controller.tickHaptic()
                                        showCustomizeBar = false
                                        expandedMediaArt = true
                                    },
                                    onPrev = controller::mediaPrevious,
                                    onPlayPause = controller::mediaPlayPause,
                                    onNext = controller::mediaNext,
                                )
                            }
                        }

                        // Bottom-Up Stacked iOS 26 Notifications (matching Phone #3 in ref_apple_wwdc25_hero.jpg)
                        if (notifications.isNotEmpty() && !(hasMedia && expandedMediaArt)) {
                            val visibleCount = if (notificationsExpanded) notifications.size else minOf(2, notifications.size)
                            val hiddenCount = (notifications.size - visibleCount).coerceAtLeast(0)

                            if (notificationsExpanded) {
                                LazyColumn(
                                    state = notifLazyListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 310.dp)
                                        .onGloballyPositioned { coords ->
                                            if (currentEffectiveLift <= 0.02f) {
                                                val relTop = coords.positionInRoot().y - hostRootTopPx
                                                notifColumnTopPx = relTop
                                                notifColumnBottomPx = relTop + coords.size.height
                                            }
                                        },
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    items(
                                        items = notifications,
                                        key = { it.key },
                                    ) { item ->
                                        LockScreenNotificationBanner(
                                            item = item,
                                            nowMillis = nowMillis,
                                            glassConfig = glassConfig,
                                            lockSessionEpoch = lockSessionEpoch,
                                            onTap = {
                                                controller.tickHaptic()
                                                onUnlockRequested {
                                                    IosNotificationRepository.fireNotificationIntent(item)
                                                }
                                            },
                                            onActionTap = { act ->
                                                controller.tickHaptic()
                                                onUnlockRequested {
                                                    IosNotificationRepository.fireNotificationIntent(item, act.intent)
                                                }
                                            },
                                            onDismissCard = {
                                                controller.tickHaptic()
                                                onDismissNotification(item.key)
                                            },
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    notifications.take(visibleCount).forEach { item ->
                                        key(item.key) {
                                            LockScreenNotificationBanner(
                                                item = item,
                                                nowMillis = nowMillis,
                                                glassConfig = glassConfig,
                                                lockSessionEpoch = lockSessionEpoch,
                                                onTap = {
                                                    controller.tickHaptic()
                                                    onUnlockRequested {
                                                        IosNotificationRepository.fireNotificationIntent(item)
                                                    }
                                                },
                                                onActionTap = { act ->
                                                    controller.tickHaptic()
                                                    onUnlockRequested {
                                                        IosNotificationRepository.fireNotificationIntent(item, act.intent)
                                                    }
                                                },
                                                onDismissCard = {
                                                    controller.tickHaptic()
                                                    onDismissNotification(item.key)
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            if (hiddenCount > 0) {
                                // Authentic iOS 26 stacked notification deck peek below bottom card
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.93f)
                                        .height(32.dp)
                                        .ccGlassPlatter(ContinuousSquircleShape(18.dp), glassConfig)
                                        .clickable {
                                            controller.tickHaptic()
                                            notificationsExpanded = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = if (hiddenCount == 1) "1 more notification" else "$hiddenCount more notifications",
                                        style = TextStyle(
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.5.sp,
                                        ),
                                        color = Color.White.copy(alpha = 0.86f),
                                    )
                                }
                            } else if (notificationsExpanded && notifications.size > 2) {
                                Text(
                                    text = "Show Less",
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.5.sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.76f),
                                    modifier = Modifier
                                        .clickable { notificationsExpanded = false }
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }

                    // 4. Bottom Quick Action Controls (50dp Flashlight & Camera)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(104.dp)
                            .padding(horizontal = 38.dp, vertical = 10.dp),
                    ) {
                        Box(modifier = Modifier.align(Alignment.CenterStart)) {
                            CircularControlTile1x1(
                                size = 50.dp,
                                active = state.flashlightOn,
                                glassConfig = glassConfig,
                                onClick = controller::toggleFlashlight,
                            ) { isActive ->
                                FlashlightGlyph(
                                    color = if (isActive) CcPalette.ElectricBlue else Color.White,
                                    isOn = isActive,
                                    size = 23.dp,
                                )
                            }
                        }

                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            CircularControlTile1x1(
                                size = 50.dp,
                                active = false,
                                glassConfig = glassConfig,
                                onClick = onOpenCameraSecure,
                            ) {
                                CameraControlGlyph(color = Color.White, size = 23.dp)
                            }
                        }
                    }

                    // 5. Flush iOS 26 Liquid Glass Home Indicator (8dp bottom capsule inset)
                    if (gesturePillEnabled) {
                        Ios26HomeIndicatorBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

/**
 * True 3D Cylindrical Refractive iOS 26 Liquid Glass Numerals (`ref_ios26_ls_9to5mac.jpg` &
 * `ref_apple_wwdc25_hero.jpg`), shared by Lock Screen & Notification Center Cover Sheet.
 */
@Composable
fun Ios26LiquidGlassClockDisplay(
    clockText: String,
    isLiquidGlass: Boolean,
    glassConfig: CcGlassConfig,
    targetHeight: Dp,
    enableSpatialDepth: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Ios26RefractiveClockGlass(
        clockText = clockText,
        isLiquidGlass = isLiquidGlass,
        glassConfig = glassConfig,
        targetHeight = targetHeight,
        enableSpatialDepth = enableSpatialDepth,
        modifier = modifier,
    )
}

@Composable
private fun LockScreenStatusHeader(
    state: ControlCenterState,
    unlockProgress: Float,
    padlockTopClearance: Dp,
    onOpenControlCenter: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        // Left: Carrier Name (exact match to "Google Fi" in ref_ios26_ls_9to5mac.jpg)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = state.carrierLabel,
                style = CcType.StatusCarrier,
                color = Color.White,
                maxLines = 1,
            )
        }

        // Center: Animated iOS Padlock Glyph below camera cutout
        IosPadlockGlyph(
            openFraction = (unlockProgress * 2.2f).coerceIn(0f, 1f),
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = padlockTopClearance),
        )

        // Right: Signal + Wi-Fi + Battery + iOS Control Center Grabber Pill
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(ContinuousSquircleShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenControlCenter,
                )
                .padding(horizontal = 2.dp, vertical = 2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SignalBarsGlyph(activeBars = state.signalBars, size = 14.dp)
                if (state.wifiEnabled) {
                    WifiFanGlyph(color = Color.White, size = 14.dp)
                }
                if (state.focusActive) {
                    FocusPersonGlyph(color = Color.White, size = 12.dp)
                }
                IosBatteryStatusGlyph(
                    levelPct = state.batteryPct,
                    charging = state.isCharging,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(2.4.dp)
                    .clip(CapsuleShape)
                    .background(Color.White.copy(alpha = 0.26f))
            )
        }
    }
}

@Composable
private fun IosPadlockGlyph(
    openFraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 13.dp, height = 17.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.55.dp.toPx()
        val bodyW = w * 0.86f
        val bodyH = h * 0.52f
        val bodyLeft = (w - bodyW) * 0.5f
        val bodyTop = h - bodyH

        drawRoundRect(
            color = color,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(2.6.dp.toPx(), 2.6.dp.toPx()),
        )

        val shackleW = w * 0.50f
        val shackleLeft = (w - shackleW) * 0.5f
        val liftY = -openFraction * 2.4.dp.toPx()
        val shacklePath = Path().apply {
            moveTo(shackleLeft, bodyTop + 0.5.dp.toPx())
            lineTo(shackleLeft, h * 0.28f + liftY)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = shackleLeft,
                    top = stroke * 0.6f + liftY,
                    right = shackleLeft + shackleW,
                    bottom = h * 0.48f + liftY,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            if (openFraction < 0.35f) {
                lineTo(shackleLeft + shackleW, bodyTop + 0.5.dp.toPx())
            } else {
                lineTo(shackleLeft + shackleW, h * 0.32f + liftY)
            }
        }
        drawPath(
            path = shacklePath,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun LockScreenCompactMediaPlatter(
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onTapAlbumArt: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val cardShape = ContinuousSquircleShape(25.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(ContinuousSquircleShape(12.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onTapAlbumArt),
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
                else -> RainCloudArtCanvas(size = 48.dp)
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.mediaTitle,
                style = CcType.MediaTitle,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.mediaArtist.isNotBlank()) {
                Text(
                    text = state.mediaArtist,
                    style = CcType.PillState,
                    color = Color.White.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                TransportRewindGlyph(color = Color.White, size = 18.dp)
            }
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                TransportPlayPauseGlyph(
                    playing = state.mediaPlaying,
                    color = Color.White,
                    size = 21.dp,
                )
            }
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                TransportFastForwardGlyph(color = Color.White, size = 18.dp)
            }
            AirPlayRouteGlyph(
                color = Color.White.copy(alpha = 0.86f),
                isPhoneIcon = state.audioRoutePhone,
                size = 17.dp,
            )
        }
    }
}

/**
 * Full-Art Apple Music Lock Screen Player Card (matching Phone #2 in `ref_apple_wwdc25_hero.jpg`).
 */
@Composable
private fun LockScreenExpandedMusicCard(
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ccGlassPlatter(ContinuousSquircleShape(28.dp), glassConfig, isLargePlatter = true)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.mediaTitle,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                letterSpacing = (-0.2).sp,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (state.mediaArtist.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.mediaArtist,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                ),
                color = Color.White.copy(alpha = 0.74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "1:42",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.66f),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CapsuleShape)
                    .background(Color.White.copy(alpha = 0.24f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.56f)
                        .height(5.dp)
                        .clip(CapsuleShape)
                        .background(Color.White.copy(alpha = 0.90f))
                )
            }
            Text(
                text = "-1:28",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.66f),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                TransportRewindGlyph(color = Color.White, size = 24.dp)
            }
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                TransportPlayPauseGlyph(
                    playing = state.mediaPlaying,
                    color = Color.White,
                    size = 28.dp,
                )
            }
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                TransportFastForwardGlyph(color = Color.White, size = 24.dp)
            }
            AirPlayRouteGlyph(
                color = Color.White.copy(alpha = 0.88f),
                isPhoneIcon = state.audioRoutePhone,
                size = 20.dp,
            )
        }
    }
}

@Composable
private fun LockScreenNotificationBanner(
    item: IosNotificationItem,
    nowMillis: Long,
    glassConfig: CcGlassConfig,
    lockSessionEpoch: Int = 0,
    onTap: () -> Unit,
    onActionTap: (IosNotificationAction) -> Unit,
    onDismissCard: () -> Unit,
) {
    val cardShape = ContinuousSquircleShape(24.dp)
    val density = LocalDensity.current
    val revealThresholdPx = with(density) { -16.dp.toPx() }
    val tapResetThresholdPx = with(density) { -20.dp.toPx() }
    val snapOpenThresholdPx = with(density) { -42.dp.toPx() }
    val snappedOpenOffsetPx = -with(density) { max(96.dp.toPx(), 48.dp.toPx() + 48.sp.toPx()) }
    val fullSwipeDismissPx = min(with(density) { -188.dp.toPx() }, snappedOpenOffsetPx - with(density) { 64.dp.toPx() })
    val maxLeftDragPx = min(with(density) { -260.dp.toPx() }, fullSwipeDismissPx - with(density) { 48.dp.toPx() })
    val maxRightOverscrollPx = with(density) { 12.dp.toPx() }
    var dragOffsetX by remember(item.key) { mutableFloatStateOf(0f) }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnActionTap by rememberUpdatedState(onActionTap)
    val latestOnDismissCard by rememberUpdatedState(onDismissCard)
    LaunchedEffect(lockSessionEpoch) {
        dragOffsetX = 0f
    }
    LaunchedEffect(item.isClearable) {
        if (!item.isClearable && dragOffsetX != 0f) {
            dragOffsetX = 0f
        }
    }
    val animatedX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lockNotifSwipeX",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        if (item.isClearable && animatedX < revealThresholdPx) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .height(42.dp)
                    .ccGlassPlatter(CapsuleShape, glassConfig)
                    .clickable(onClick = {
                        dragOffsetX = 0f
                        latestOnDismissCard()
                    })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Clear", style = CcType.FocusLabel, color = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedX.roundToInt(), 0) }
                .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
                .pointerInput(item.key) {
                    detectTapGestures(onTap = {
                        if (dragOffsetX < tapResetThresholdPx) dragOffsetX = 0f else latestOnTap()
                    })
                }
                .pointerInput(item.key, item.isClearable) {
                    if (!item.isClearable) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetX = (dragOffsetX + dragAmount).coerceIn(maxLeftDragPx, maxRightOverscrollPx)
                        },
                        onDragEnd = {
                            when {
                                dragOffsetX < fullSwipeDismissPx -> {
                                    dragOffsetX = 0f
                                    latestOnDismissCard()
                                }
                                dragOffsetX < snapOpenThresholdPx -> dragOffsetX = snappedOpenOffsetPx
                                else -> dragOffsetX = 0f
                            }
                        },
                        onDragCancel = { dragOffsetX = 0f },
                    )
                }
                .padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LockNotifAppBadge(item = item)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.title,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            letterSpacing = (-0.15).sp,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatLockRelativeTime(item.postTimeMs, nowMillis),
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.5.sp,
                        ),
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
                if (item.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.body,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.8.sp,
                            lineHeight = 18.sp,
                        ),
                        color = Color.White.copy(alpha = 0.90f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.actions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.actions.forEach { act ->
                            Box(
                                modifier = Modifier
                                    .clip(CapsuleShape)
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable { latestOnActionTap(act) }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = act.title,
                                    style = TextStyle(
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                    ),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockNotifAppBadge(item: IosNotificationItem) {
    val iconShape = ContinuousSquircleShape(10.dp)
    val bmp = item.appIcon
    if (bmp != null && !bmp.isRecycled) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = item.appName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(38.dp).clip(iconShape),
        )
    } else {
        val pkgLow = item.packageName.lowercase(Locale.US)
        val badgeColor = when {
            pkgLow.contains("message") || pkgLow.contains("whatsapp") -> Color(0xFF30D158)
            pkgLow.contains("ota") || pkgLow.contains("settings") -> Color(0xFF0A84FF)
            pkgLow.contains("contact") || pkgLow.contains("dialer") -> Color(0xFF34C759)
            pkgLow.contains("mail") || pkgLow.contains("gm") -> Color(0xFF1A73E8)
            else -> Color(0xFF5E5CE6)
        }
        val initial = item.appName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(iconShape)
                .background(Brush.verticalGradient(listOf(badgeColor, badgeColor.copy(alpha = 0.80f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 17.sp),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun LockScreenCustomizeBar(
    clockStyle: Int,
    isTinted: Boolean,
    glassConfig: CcGlassConfig,
    onSelectClockStyle: (Int) -> Unit,
    onToggleGlassTint: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .ccGlassPlatter(CapsuleShape, glassConfig)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CustomizeChip(
            label = "Glass",
            selected = clockStyle == 0,
            onClick = { onSelectClockStyle(0) },
        )
        CustomizeChip(
            label = "Solid",
            selected = clockStyle == 1,
            onClick = { onSelectClockStyle(1) },
        )
        CustomizeChip(
            label = if (isTinted) "Tinted" else "Clear",
            selected = isTinted,
            onClick = onToggleGlassTint,
        )
    }
}

@Composable
private fun CustomizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CapsuleShape)
            .background(if (selected) Color.White.copy(alpha = 0.28f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
            ),
            color = Color.White,
        )
    }
}

private fun formatLockRelativeTime(postTimeMs: Long, nowMs: Long): String {
    val diffSec = ((nowMs - postTimeMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "now"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86_400L -> "${diffSec / 3600L}h ago"
        else -> "${diffSec / 86_400L}d ago"
    }
}
