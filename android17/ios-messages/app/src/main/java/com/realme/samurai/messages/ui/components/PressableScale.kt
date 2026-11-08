package com.realme.samurai.messages.ui.components

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * Critically-damped iOS 26 Liquid Glass spring parameters for small UI pills,
 * circular orbs, and popovers (consistent with IosDialer and IosStyler).
 */
const val IOS26_PRESS_DAMPING = 0.78f
const val IOS26_PRESS_STIFFNESS = 500f

fun <T> ios26GlassSpring(
    dampingRatio: Float = IOS26_PRESS_DAMPING,
    stiffness: Float = IOS26_PRESS_STIFFNESS,
): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

/**
 * Reusable press-feedback modifier.
 *
 * While the element is pressed it springs down to [pressedScale] and back,
 * giving a tactile iOS-like response. This self-contained variant detects
 * presses on its own via [pointerInput] + [PressInteraction], so it works on
 * any element without needing a `clickable`. For elements that already own a
 * [MutableInteractionSource] (e.g. a Button), use the overload that accepts it
 * to avoid duplicate gesture handling.
 */
fun Modifier.pressableScale(
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = ios26GlassSpring(),
        label = "pressableScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(interactionSource) {
            awaitPointerEventScope {
                while (true) {
                    // Wait for a press, emit Press, then release/cancel.
                    val down = awaitPointerEvent()
                    if (down.changes.any { it.pressed }) {
                        val press = PressInteraction.Press(
                            down.changes.first().position
                        )
                        scope.launch { interactionSource.emit(press) }
                        // Wait until all pointers are up or the gesture cancels.
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) {
                                released = true
                                scope.launch {
                                    interactionSource.emit(PressInteraction.Release(press))
                                }
                            }
                        }
                    }
                }
            }
        }
}

/**
 * Variant of [pressableScale] that tracks an existing [MutableInteractionSource]
 * (e.g. the one passed to `clickable`/a Button) so the scale animation reflects
 * real pointer presses without installing a second gesture detector.
 */
fun Modifier.pressableScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = ios26GlassSpring(),
        label = "pressableScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
