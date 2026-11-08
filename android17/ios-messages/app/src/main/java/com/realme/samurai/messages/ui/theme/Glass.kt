package com.realme.samurai.messages.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.realme.samurai.messages.ui.glass.LocalSystemGlassConfig

/**
 * A "liquid glass" background: a translucent fill with a subtle top highlight
 * gradient and a hairline border, giving frosted-glass depth for top/bottom
 * bars and tapback capsules. Synchronized live with `iOS Styler` glass mode
 * and frost strength (`Settings.Global`).
 */
fun Modifier.liquidGlass(
    colors: LiquidColors,
    shape: Shape = RoundedCornerShape(0.dp),
    borderWidth: androidx.compose.ui.unit.Dp = 0.5.dp,
): Modifier = composed {
    val sysGlass = LocalSystemGlassConfig.current
    val fill = when {
        sysGlass.isSolidMatte -> colors.glassFillStrong
        sysGlass.isFrostedTinted || colors.glassTinted ->
            if (colors.isDark) Color(0xDB242426) else Color(0xE8F7F7F9)
        else -> {
            val fScale = sysGlass.frostScale
            val targetAlpha = (0.18f + (colors.glassFill.alpha - 0.18f) * fScale).coerceIn(0.18f, 0.96f)
            colors.glassFill.copy(alpha = targetAlpha)
        }
    }

    this
        .clip(shape)
        .background(fill, shape)
        .then(
            if (borderWidth > 0.dp) {
                // iOS 26 glass edge: a bright rim on top fading to a faint one below,
                // which is what gives the material its "lens" thickness.
                Modifier.border(
                    BorderStroke(
                        borderWidth,
                        Brush.verticalGradient(listOf(colors.glassHighlight, colors.glassBorder)),
                    ),
                    shape,
                )
            } else {
                Modifier
            }
        )
}

/**
 * Decorative ambient gradient wash for chat / list backgrounds — extremely
 * subtle tinted blobs that give the flat background a sense of depth, echoing
 * iOS's translucent material layers.
 */
@Composable
fun AmbientBackground(
    colors: LiquidColors,
    modifier: Modifier = Modifier,
) {
    val tint = if (colors.isDark) Color(0xFF0A84FF) else Color(0xFF0A84FF)
    Surface(color = colors.chatBackground, modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(280.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = if (colors.isDark) 0.10f else 0.06f), Color.Transparent)
                        )
                    )
            )
        }
    }
}
