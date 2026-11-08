package com.realme.samurai.controlcenter.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker as ComposeVelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.realme.samurai.controlcenter.data.ControlCenterController
import com.realme.samurai.controlcenter.data.ControlCenterState
import com.realme.samurai.controlcenter.service.IosNotificationItem
import com.realme.samurai.controlcenter.service.IosNotificationRepository
import com.realme.samurai.controlcenter.ui.components.CircularControlTile1x1
import com.realme.samurai.controlcenter.ui.glass.CcGlassConfig
import com.realme.samurai.controlcenter.ui.glass.ControlCenterGlassSceneHost
import com.realme.samurai.controlcenter.ui.glass.Ios26HomeIndicatorBar
import com.realme.samurai.controlcenter.ui.glass.ccGlassPlatter
import com.realme.samurai.controlcenter.ui.glass.clipWithMovingGlassRim
import com.realme.samurai.controlcenter.ui.icons.AirPlayRouteGlyph
import com.realme.samurai.controlcenter.ui.icons.AlarmMiniGlyph
import com.realme.samurai.controlcenter.ui.icons.CameraControlGlyph
import com.realme.samurai.controlcenter.ui.icons.FlashlightGlyph
import com.realme.samurai.controlcenter.ui.icons.FocusPersonGlyph
import com.realme.samurai.controlcenter.ui.icons.IosBatteryStatusGlyph
import com.realme.samurai.controlcenter.ui.icons.OrientationLockGlyph
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

enum class IosSheetMode {
    NOTIFICATION_CENTER,
    CONTROL_CENTER,
}

@Composable
fun IosTopOverlayRootScreen(
    mode: IosSheetMode,
    controller: ControlCenterController,
    glassConfig: CcGlassConfig,
    openProgress: Float = 1f,
    sheetVelocityPxPerSec: Float = 0f,
    gesturePillEnabled: Boolean = true,
    onSwitchMode: (IosSheetMode) -> Unit = {},
    onDragCloseDelta: ((Float) -> Unit)? = null,
    onDragCloseEnd: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    if (openProgress <= 0.001f) {
        return
    }
    key(mode) {
        when (mode) {
            IosSheetMode.NOTIFICATION_CENTER -> NotificationCenterRootScreen(
                controller = controller,
                glassConfig = glassConfig,
                openProgress = openProgress,
                sheetVelocityPxPerSec = sheetVelocityPxPerSec,
                gesturePillEnabled = gesturePillEnabled,
                onOpenControlCenter = { onSwitchMode(IosSheetMode.CONTROL_CENTER) },
                onDragCloseDelta = onDragCloseDelta,
                onDragCloseEnd = onDragCloseEnd,
                onDismiss = onDismiss,
            )
            IosSheetMode.CONTROL_CENTER -> ControlCenterRootScreen(
                controller = controller,
                glassConfig = glassConfig,
                openProgress = openProgress,
                sheetVelocityPxPerSec = sheetVelocityPxPerSec,
                gesturePillEnabled = gesturePillEnabled,
                onDragCloseDelta = onDragCloseDelta,
                onDragCloseEnd = onDragCloseEnd,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
fun NotificationCenterRootScreen(
    controller: ControlCenterController,
    glassConfig: CcGlassConfig,
    openProgress: Float = 1f,
    sheetVelocityPxPerSec: Float = 0f,
    gesturePillEnabled: Boolean = true,
    previewClockText: String? = null,
    previewDateText: String? = null,
    previewNotifications: List<IosNotificationItem>? = null,
    externalListState: LazyListState? = null,
    onOpenControlCenter: () -> Unit = {},
    onDragCloseDelta: ((Float) -> Unit)? = null,
    onDragCloseEnd: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit = {},
) {
    val state = controller.state
    val context = LocalContext.current
    val density = LocalDensity.current
    val clampedProgress = openProgress.coerceIn(0f, 1f)

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (!controller.previewOnly && previewClockText == null) {
        DisposableEffect(context) {
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

    val is24h = remember(context, nowMillis, controller.previewOnly) {
        if (controller.previewOnly) false else DateFormat.is24HourFormat(context)
    }
    val clockText = previewClockText ?: remember(nowMillis, is24h) {
        val pattern = if (is24h) "HH:mm" else "h:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(nowMillis))
    }
    val dateText = previewDateText ?: remember(nowMillis) {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(nowMillis))
    }

    val notifications = previewNotifications ?: IosNotificationRepository.notifications
    val hasClearableNotifications = remember(notifications) { notifications.any { it.isClearable } }
    var clearConfirmExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(hasClearableNotifications) {
        if (!hasClearableNotifications) {
            clearConfirmExpanded = false
        }
    }
    var bottomSwipeAccum by remember { mutableFloatStateOf(0f) }
    val listState = externalListState ?: rememberLazyListState()

    var clockStyle by remember(context, controller.previewOnly) {
        mutableIntStateOf(
            if (controller.previewOnly) {
                0
            } else {
                try {
                    Settings.Global.getInt(context.contentResolver, "samurai_lockscreen_clock_style", 0).coerceIn(0, 1)
                } catch (_: Throwable) {
                    0
                }
            }
        )
    }
    if (!controller.previewOnly) {
        DisposableEffect(context) {
            val cr = context.contentResolver
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    clockStyle = try {
                        Settings.Global.getInt(cr, "samurai_lockscreen_clock_style", 0).coerceIn(0, 1)
                    } catch (_: Throwable) {
                        0
                    }
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

    // Interactive 90Hz upward sheet drag-to-close across the entire Notification Center list!
    // Whenever LazyColumn has no further downward content (`!listState.canScrollForward`) and the user
    // swipes UP (`available.y < 0f`), we smoothly pull the sheet upward with the finger and close on release.
    var sheetClosingActive by remember { mutableStateOf(false) }
    var nestedCloseDragDispatched by remember { mutableStateOf(false) }
    var lipClosingActive by remember { mutableStateOf(false) }
    var nestedAccumUpPx by remember { mutableFloatStateOf(0f) }
    var lastOuterReleaseVy by remember { mutableFloatStateOf(0f) }
    var hasOuterReleaseVy by remember { mutableStateOf(false) }
    val closeThresholdPx = with(density) { 46.dp.toPx() }
    val latestOnOpenControlCenter by rememberUpdatedState(onOpenControlCenter)
    val latestOnDragCloseDelta by rememberUpdatedState(onDragCloseDelta)
    val latestOnDragCloseEnd by rememberUpdatedState(onDragCloseEnd)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val sheetCloseNestedScroll = remember(closeThresholdPx, listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && sheetClosingActive) {
                    val deltaCb = latestOnDragCloseDelta
                    if (available.y > 0f && nestedAccumUpPx + available.y >= 0f) {
                        val consumeDown = -nestedAccumUpPx
                        nestedAccumUpPx = 0f
                        sheetClosingActive = false
                        if (consumeDown > 0f && deltaCb != null) {
                            nestedCloseDragDispatched = true
                            deltaCb.invoke(consumeDown)
                        }
                        return Offset(0f, consumeDown)
                    }
                    nestedAccumUpPx = (nestedAccumUpPx + available.y).coerceAtMost(0f)
                    if (deltaCb != null) {
                        nestedCloseDragDispatched = true
                        deltaCb.invoke(available.y)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y < -0.5f && !listState.canScrollForward) {
                    sheetClosingActive = true
                    nestedAccumUpPx += available.y
                    val deltaCb = latestOnDragCloseDelta
                    if (deltaCb != null) {
                        nestedCloseDragDispatched = true
                        deltaCb.invoke(available.y)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val flingThresholdPxPerSec = -650f * density.density
                val effectiveVy = if (hasOuterReleaseVy) lastOuterReleaseVy else available.y
                hasOuterReleaseVy = false
                lastOuterReleaseVy = 0f
                val endCb = latestOnDragCloseEnd
                if (sheetClosingActive || nestedCloseDragDispatched) {
                    val wasStillLifted = sheetClosingActive
                    val accum = nestedAccumUpPx
                    sheetClosingActive = false
                    nestedCloseDragDispatched = false
                    nestedAccumUpPx = 0f
                    if (wasStillLifted) {
                        if (endCb != null) {
                            endCb.invoke(effectiveVy)
                        } else if (accum < -closeThresholdPx || effectiveVy <= flingThresholdPxPerSec) {
                            latestOnDismiss()
                        }
                        return available
                    }
                    // Sheet already unlatched back to 1.0f mid-gesture: settle overlay gesture flag
                    // and return Velocity.Zero so LazyColumn flings naturally on downward release!
                    if (!listState.canScrollForward && effectiveVy <= flingThresholdPxPerSec) {
                        if (endCb != null) {
                            endCb.invoke(effectiveVy)
                        } else {
                            latestOnDismiss()
                        }
                        return available
                    }
                    endCb?.invoke(0f)
                    return Velocity.Zero
                } else if (!listState.canScrollForward && effectiveVy <= flingThresholdPxPerSec) {
                    if (endCb != null) {
                        endCb.invoke(effectiveVy)
                    } else {
                        latestOnDismiss()
                    }
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    DisposableEffect(sheetCloseNestedScroll) {
        onDispose {
            val wasActive = sheetClosingActive || nestedCloseDragDispatched || lipClosingActive
            sheetClosingActive = false
            nestedCloseDragDispatched = false
            lipClosingActive = false
            nestedAccumUpPx = 0f
            bottomSwipeAccum = 0f
            if (wasActive) {
                latestOnDragCloseEnd?.invoke(0f)
            }
        }
    }

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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenH = (if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
            constraints.maxHeight.toFloat()
        } else {
            with(density) { 800.dp.toPx() }
        }).coerceAtLeast(640f)
        val sheetEdgeYPx = (clampedProgress * screenH).coerceIn(0f, screenH)
        val pullOffsetPx = -(screenH - sheetEdgeYPx)
        val currentSheetEdgeY by rememberUpdatedState(sheetEdgeYPx)
        val currentScreenH by rememberUpdatedState(screenH)

        ControlCenterGlassSceneHost(
            wallpaperBitmap = state.wallpaperBitmap,
            glassConfig = glassConfig,
            openProgress = clampedProgress,
            modifier = Modifier.fillMaxSize(),
            pullOffsetProvider = { pullOffsetPx },
            sheetEdgeYPx = sheetEdgeYPx,
            sheetVelocityPxPerSec = sheetVelocityPxPerSec,
            motionTickProvider = {
                listState.firstVisibleItemScrollOffset xor (listState.firstVisibleItemIndex shl 16)
            },
        ) {
            // Untranslated outer gesture host: prevents local-coordinate drag oscillation and preempts
            // LazyColumn scrolling when swiping up from the moving 108dp bottom glass lip.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(sheetCloseNestedScroll)
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        val lipHeightPx = 108.dp.toPx()
                        val lipPadBelowPx = 12.dp.toPx()
                        val vt = ComposeVelocityTracker()
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val edgeAtDown = currentSheetEdgeY
                            if (down.position.y > edgeAtDown + lipPadBelowPx) {
                                return@awaitEachGesture
                            }
                            val startedInBottomLip = down.position.y >= edgeAtDown - lipHeightPx
                            // Whenever LazyColumn has scrollable range in either direction (`canScrollForward`
                            // OR `canScrollBackward`), let ScrollableNode + sheetCloseNestedScroll handle
                            // mid-screen drags so overscroll-close AND mid-gesture downward unlatch back into
                            // LazyColumn work seamlessly; only preempt mid-screen when list is non-scrollable!
                            if (!startedInBottomLip && (listState.canScrollForward || listState.canScrollBackward)) {
                                return@awaitEachGesture
                            }
                            vt.resetTracking()
                            vt.addPointerInputChange(down)
                            var activePointerId = down.id
                            var startPos = down.position
                            var prevY = down.position.y
                            var lipDragging = false
                            bottomSwipeAccum = 0f

                            try {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull { it.id == activePointerId }
                                    if (ch != null) {
                                        vt.addPointerInputChange(ch)
                                    }
                                    if (ch == null || !ch.pressed) {
                                        val surviving = ev.changes.firstOrNull { it.pressed }
                                        val canHandoff = surviving != null && (
                                            lipDragging ||
                                                surviving.position.y >= edgeAtDown - lipHeightPx ||
                                                (!listState.canScrollForward && !listState.canScrollBackward)
                                            )
                                        if (canHandoff && surviving != null) {
                                            activePointerId = surviving.id
                                            prevY = surviving.position.y
                                            if (!lipDragging) {
                                                startPos = surviving.position
                                            }
                                            vt.resetTracking()
                                            vt.addPointerInputChange(surviving)
                                            continue
                                        }
                                        if (lipDragging) {
                                            val wasCancelled = ch?.isConsumed == true
                                            lipDragging = false
                                            lipClosingActive = false
                                            ch?.consume()
                                            val vy = vt.calculateVelocity().y
                                            vt.resetTracking()
                                            val endCb = latestOnDragCloseEnd
                                            if (wasCancelled) {
                                                endCb?.invoke(0f)
                                            } else if (endCb != null) {
                                                endCb.invoke(vy)
                                            } else if (bottomSwipeAccum <= -0.18f * currentScreenH ||
                                                vy <= -650f * this.density
                                            ) {
                                                latestOnDismiss()
                                            }
                                            bottomSwipeAccum = 0f
                                        }
                                        break
                                    }

                                    val dx = ch.position.x - startPos.x
                                    val dy = ch.position.y - startPos.y
                                    if (!lipDragging) {
                                        val canPreemptClose = startedInBottomLip ||
                                            (!listState.canScrollForward && !listState.canScrollBackward)
                                        if (canPreemptClose && dy < -slop && abs(dy) > abs(dx)) {
                                            lipDragging = true
                                            lipClosingActive = true
                                            if (sheetClosingActive || nestedCloseDragDispatched) {
                                                sheetClosingActive = false
                                                nestedCloseDragDispatched = false
                                                nestedAccumUpPx = 0f
                                            }
                                            prevY = ch.position.y
                                            ch.consume()
                                            val postSlopDy = (dy + slop).coerceAtMost(0f)
                                            bottomSwipeAccum = (bottomSwipeAccum + postSlopDy).coerceAtMost(0f)
                                            val deltaCb = latestOnDragCloseDelta
                                            if (deltaCb != null) {
                                                deltaCb.invoke(postSlopDy)
                                            } else if (bottomSwipeAccum <= -0.18f * currentScreenH) {
                                                lipDragging = false
                                                lipClosingActive = false
                                                bottomSwipeAccum = 0f
                                                latestOnDismiss()
                                                break
                                            }
                                        } else if ((abs(dx) > slop * 1.3f && abs(dx) > abs(dy)) || dy > slop * 1.3f) {
                                            break
                                        }
                                    } else {
                                        val stepDy = ch.position.y - prevY
                                        prevY = ch.position.y
                                        ch.consume()
                                        bottomSwipeAccum = (bottomSwipeAccum + stepDy).coerceAtMost(0f)
                                        val deltaCb = latestOnDragCloseDelta
                                        if (deltaCb != null) {
                                            deltaCb.invoke(stepDy)
                                        } else if (bottomSwipeAccum <= -0.18f * currentScreenH) {
                                            lipDragging = false
                                            lipClosingActive = false
                                            bottomSwipeAccum = 0f
                                            latestOnDismiss()
                                            break
                                        }
                                    }
                                }
                            } finally {
                                if (lipDragging && lipClosingActive) {
                                    val finalAccum = bottomSwipeAccum
                                    lipClosingActive = false
                                    bottomSwipeAccum = 0f
                                    vt.resetTracking()
                                    val endCb = latestOnDragCloseEnd
                                    if (endCb != null) {
                                        endCb.invoke(0f)
                                    } else if (finalAccum <= -0.18f * currentScreenH) {
                                        latestOnDismiss()
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Safety net + untranslated outer VelocityTracker sampled on PointerEventPass.Initial
                        // (BEFORE ScrollableNode's PointerEventPass.Main) across ALL pressed pointers in the
                        // gesture so multi-finger handoffs never prematurely fire the safety-net settle or spike velocity!
                        val outerVt = ComposeVelocityTracker()
                        coroutineScope {
                            awaitEachGesture {
                                val primaryDown = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                var trackedId = primaryDown.id
                                outerVt.resetTracking()
                                outerVt.addPointerInputChange(primaryDown)
                                lastOuterReleaseVy = 0f
                                hasOuterReleaseVy = false
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val trackedCh = ev.changes.firstOrNull { it.id == trackedId }
                                        if (trackedCh == null || !trackedCh.pressed) {
                                            if (trackedCh != null) {
                                                outerVt.addPointerInputChange(trackedCh)
                                            }
                                            val surviving = ev.changes.firstOrNull { it.pressed }
                                            if (surviving != null) {
                                                trackedId = surviving.id
                                                outerVt.resetTracking()
                                                outerVt.addPointerInputChange(surviving)
                                                continue
                                            }
                                            break
                                        }
                                        outerVt.addPointerInputChange(trackedCh)
                                    }
                                    lastOuterReleaseVy = outerVt.calculateVelocity().y
                                    hasOuterReleaseVy = true
                                } finally {
                                    outerVt.resetTracking()
                                    if (sheetClosingActive || nestedCloseDragDispatched) {
                                        if (isActive) {
                                            launch {
                                                yield()
                                                yield()
                                                if (sheetClosingActive || nestedCloseDragDispatched) {
                                                    val accum = nestedAccumUpPx
                                                    sheetClosingActive = false
                                                    nestedCloseDragDispatched = false
                                                    nestedAccumUpPx = 0f
                                                    val endCb = latestOnDragCloseEnd
                                                    if (endCb != null) {
                                                        endCb.invoke(0f)
                                                    } else if (accum < -closeThresholdPx) {
                                                        latestOnDismiss()
                                                    }
                                                }
                                            }
                                        } else {
                                            val accum = nestedAccumUpPx
                                            sheetClosingActive = false
                                            nestedCloseDragDispatched = false
                                            nestedAccumUpPx = 0f
                                            val endCb = latestOnDragCloseEnd
                                            if (endCb != null) {
                                                endCb.invoke(0f)
                                            } else if (accum < -closeThresholdPx) {
                                                latestOnDismiss()
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                                translationY = pullOffsetPx
                                alpha = (clampedProgress * 5.5f).coerceIn(0f, 1f)
                            },
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (controller.previewOnly) Modifier.statusBarsPadding()
                                    else Modifier.padding(top = sysStatusInsetDp)
                                ),
                            contentPadding = PaddingValues(
                                start = 18.dp,
                                end = 18.dp,
                                top = 6.dp,
                                bottom = 116.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 1. iOS 26 Cover Sheet Status Bar + Right-Side Control Center Hint Bar
                            item(key = "nc_top_bar") {
                                CoverSheetStatusHeader(
                                    state = state,
                                    onOpenControlCenter = { latestOnOpenControlCenter() },
                                )
                            }

                            // 2. Hero iOS 26 Liquid Glass Date & Lock-Screen Clock
                            item(key = "nc_hero_clock") {
                                LiquidGlassCoverClockHeader(
                                    dateText = dateText,
                                    clockText = clockText,
                                    isLiquidGlass = clockStyle == 0,
                                    glassConfig = glassConfig,
                                    onBackgroundTap = {
                                        if (clearConfirmExpanded) clearConfirmExpanded = false
                                    },
                                )
                            }

                            // 3. Optional Active Media Platter (matches iOS 26 Cover Sheet Now Playing)
                            if (state.mediaPlaying || state.mediaTitle != "Not Playing") {
                                item(key = "nc_media_banner") {
                                    CoverSheetMediaPlatter(
                                        state = state,
                                        glassConfig = glassConfig,
                                        onPrev = controller::mediaPrevious,
                                        onPlayPause = controller::mediaPlayPause,
                                        onNext = controller::mediaNext,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            }

                            // 4. Notification Center Section Header + Morphing Liquid Glass 'X' / 'Clear' Pill
                            if (notifications.isNotEmpty()) {
                                item(key = "nc_section_header") {
                                    NotificationSectionHeader(
                                        showClearAction = hasClearableNotifications,
                                        clearExpanded = clearConfirmExpanded,
                                        glassConfig = glassConfig,
                                        onToggleOrClear = {
                                            if (!hasClearableNotifications) {
                                                clearConfirmExpanded = false
                                            } else if (clearConfirmExpanded) {
                                                clearConfirmExpanded = false
                                                controller.tickHaptic()
                                                IosNotificationRepository.clearAll()
                                            } else {
                                                controller.tickHaptic()
                                                clearConfirmExpanded = true
                                            }
                                        },
                                    )
                                }

                                items(
                                    items = notifications,
                                    key = { it.key },
                                ) { item ->
                                    IosLiquidGlassNotificationCard(
                                        item = item,
                                        nowMillis = nowMillis,
                                        glassConfig = glassConfig,
                                        onTap = {
                                            controller.tickHaptic()
                                            val dismissAndLaunch = controller.onRequestDismissAndRun
                                            if (dismissAndLaunch != null && !controller.previewOnly) {
                                                dismissAndLaunch {
                                                    IosNotificationRepository.fireNotificationIntent(item)
                                                }
                                            } else {
                                                val launched = IosNotificationRepository.fireNotificationIntent(item)
                                                if (launched || controller.previewOnly) {
                                                    latestOnDismiss()
                                                }
                                            }
                                        },
                                        onActionTap = { act ->
                                            controller.tickHaptic()
                                            val dismissAndLaunch = controller.onRequestDismissAndRun
                                            if (dismissAndLaunch != null && !controller.previewOnly) {
                                                dismissAndLaunch {
                                                    IosNotificationRepository.fireNotificationIntent(item, act.intent)
                                                }
                                            } else {
                                                IosNotificationRepository.fireNotificationIntent(item, act.intent)
                                                latestOnDismiss()
                                            }
                                        },
                                        onDismissCard = {
                                            controller.tickHaptic()
                                            IosNotificationRepository.dismissOne(item.key)
                                        },
                                    )
                                }
                            } else {
                                item(key = "nc_empty_state") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(220.dp),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        Text(
                                            text = "No Older Notifications",
                                            style = CcType.StatusCarrier.copy(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                            ),
                                            color = Color.White.copy(alpha = 0.68f),
                                        )
                                    }
                                }
                            }
                        }

                        // 5. Bottom iOS 26 Cover Sheet Quick Actions (Flashlight & Camera)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(108.dp)
                                .padding(horizontal = 36.dp, vertical = 14.dp),
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
                                    onClick = controller::openCamera,
                                ) {
                                    CameraControlGlyph(color = Color.White, size = 23.dp)
                                }
                            }
                        }

                        // 6. Flush iOS 26 Liquid Glass Home Indicator (8dp bottom capsule inset)
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
}

@Composable
private fun CoverSheetStatusHeader(
    state: ControlCenterState,
    onOpenControlCenter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(top = 2.dp),
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

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .clip(ContinuousSquircleShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenControlCenter,
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
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
            Spacer(Modifier.height(5.dp))
            // Iconic iOS top-right Control Center grabber pill (matching wave5_01_lockscreen.png)
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(2.6.dp)
                    .clip(CapsuleShape)
                    .background(Color.White.copy(alpha = 0.26f))
            )
        }
    }
}

@Composable
private fun LiquidGlassCoverClockHeader(
    dateText: String,
    clockText: String,
    isLiquidGlass: Boolean,
    glassConfig: CcGlassConfig,
    onBackgroundTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackgroundTap,
            )
            .padding(top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dateText,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                letterSpacing = (-0.2).sp,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.28f),
                    offset = Offset(0f, 3f),
                    blurRadius = 8f,
                ),
            ),
            color = Color.White.copy(alpha = 0.94f),
        )
        Ios26LiquidGlassClockDisplay(
            clockText = clockText,
            isLiquidGlass = isLiquidGlass,
            glassConfig = glassConfig,
            targetHeight = 160.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun CoverSheetMediaPlatter(
    state: ControlCenterState,
    glassConfig: CcGlassConfig,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val cardShape = ContinuousSquircleShape(26.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(ContinuousSquircleShape(12.dp))
                .background(Color.White.copy(alpha = 0.16f)),
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
                state.mediaUseRainArt -> RainCloudArtCanvas(size = 48.dp)
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
                    color = Color.White.copy(alpha = 0.72f),
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
                    playing = state.mediaPlaying && !state.mediaUseRainArt,
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
            AirPlayRouteGlyph(color = Color.White.copy(alpha = 0.85f), isPhoneIcon = state.audioRoutePhone, size = 17.dp)
        }
    }
}

@Composable
private fun NotificationSectionHeader(
    showClearAction: Boolean,
    clearExpanded: Boolean,
    glassConfig: CcGlassConfig,
    onToggleOrClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Notification Center",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = (-0.3).sp,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.24f),
                    offset = Offset(0f, 2f),
                    blurRadius = 6f,
                ),
            ),
            color = Color.White,
        )

        if (showClearAction) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .then(
                        if (clearExpanded) Modifier.defaultMinSize(minWidth = 72.dp)
                        else Modifier.width(34.dp)
                    )
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    .ccGlassPlatter(if (clearExpanded) CapsuleShape else CircleShape, glassConfig)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleOrClear,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (clearExpanded) {
                    Text(
                        text = "Clear",
                        style = CcType.FocusLabel.copy(fontSize = 13.5.sp),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                } else {
                    CloseXGlyph(color = Color.White.copy(alpha = 0.90f), sizeDp = 11f)
                }
            }
        }
    }
}

@Composable
private fun IosLiquidGlassNotificationCard(
    item: IosNotificationItem,
    nowMillis: Long,
    glassConfig: CcGlassConfig,
    onTap: () -> Unit,
    onActionTap: (com.realme.samurai.controlcenter.service.IosNotificationAction) -> Unit,
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
    LaunchedEffect(item.isClearable) {
        if (!item.isClearable && dragOffsetX != 0f) {
            dragOffsetX = 0f
        }
    }
    val animatedX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "notifSwipeX",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        // Reveal 'Clear' pill behind when swiped left
        if (item.isClearable && animatedX < revealThresholdPx) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .height(44.dp)
                    .ccGlassPlatter(CapsuleShape, glassConfig)
                    .clickable(onClick = {
                        dragOffsetX = 0f
                        latestOnDismissCard()
                    })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Clear", style = CcType.FocusLabel, color = Color.White, maxLines = 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedX.roundToInt(), 0) }
                .ccGlassPlatter(cardShape, glassConfig, isLargePlatter = true)
                .pointerInput(item.key) {
                    detectTapGestures(onTap = {
                        if (dragOffsetX < tapResetThresholdPx) {
                            dragOffsetX = 0f
                        } else {
                            latestOnTap()
                        }
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
                .padding(horizontal = 15.dp, vertical = 13.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                // App Squircle Icon (38dp)
                NotificationAppIconBadge(item = item)

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
                            text = formatRelativeTime(item.postTimeMs, nowMillis),
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.5.sp,
                            ),
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }

                    if (item.body.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.body,
                            style = TextStyle(
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 18.5.sp,
                            ),
                            color = Color.White.copy(alpha = 0.90f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (item.actions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.actions.forEach { act ->
                                Box(
                                    modifier = Modifier
                                        .clip(CapsuleShape)
                                        .background(Color.White.copy(alpha = 0.18f))
                                        .clickable { latestOnActionTap(act) }
                                        .padding(horizontal = 13.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = act.title,
                                        style = TextStyle(
                                            fontFamily = FontFamily.SansSerif,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
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
}

@Composable
private fun NotificationAppIconBadge(item: IosNotificationItem) {
    val iconShape = ContinuousSquircleShape(10.dp)
    val bmp = item.appIcon
    if (bmp != null && !bmp.isRecycled) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = item.appName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(38.dp)
                .clip(iconShape),
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
                .background(
                    Brush.verticalGradient(
                        listOf(badgeColor, badgeColor.copy(alpha = 0.80f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                ),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun CloseXGlyph(color: Color, sizeDp: Float) {
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

private fun formatRelativeTime(postTimeMs: Long, nowMs: Long): String {
    val diffSec = ((nowMs - postTimeMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "now"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86_400L -> "${diffSec / 3600L}h ago"
        else -> "${diffSec / 86_400L}d ago"
    }
}
