package com.realme.samurai.controlcenter.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PlusGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w / 2f
        val arm = w * 0.34f
        val sw = w * 0.105f
        drawLine(color, Offset(c - arm, c), Offset(c + arm, c), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(c, c - arm), Offset(c, c + arm), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun PowerGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.105f
        val pad = w * 0.16f
        drawArc(
            color = color,
            startAngle = -54f,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = Offset(pad, pad + w * 0.04f),
            size = Size(w - pad * 2f, w - pad * 2f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.5f, w * 0.10f),
            end = Offset(w * 0.5f, w * 0.48f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SignalBarsGlyph(
    activeBars: Int = 4,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.32f),
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.145f
        val gap = w * 0.085f
        val totalW = barW * 4f + gap * 3f
        val startX = (w - totalW) / 2f
        val heights = floatArrayOf(0.32f, 0.50f, 0.70f, 0.90f)
        for (i in 0..3) {
            val bh = h * heights[i]
            val x = startX + i * (barW + gap)
            drawRoundRect(
                color = if (i < activeBars) activeColor else inactiveColor,
                topLeft = Offset(x, h * 0.92f - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
fun WifiFanGlyph(color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.78f
        val sw = w * 0.105f
        drawCircle(color = color, radius = sw * 0.68f, center = Offset(cx, cy))
        val radii = floatArrayOf(w * 0.25f, w * 0.44f)
        for (r in radii) {
            drawArc(
                color = color,
                startAngle = 222f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun IosBatteryStatusGlyph(
    levelPct: Int,
    charging: Boolean,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(width = 25.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.88f
        val stroke = h * 0.095f
        drawRoundRect(
            color = color.copy(alpha = 0.42f),
            topLeft = Offset(0f, stroke),
            size = Size(bodyW, h - stroke * 2f),
            cornerRadius = CornerRadius(h * 0.30f, h * 0.30f),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.45f),
            topLeft = Offset(bodyW + w * 0.03f, h * 0.32f),
            size = Size(w * 0.06f, h * 0.36f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        )
        val inset = h * 0.19f
        val fillFrac = (levelPct.coerceIn(5, 100)) / 100f
        val fillColor = if (charging) Color(0xFF34C759) else color
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(inset, inset),
            size = Size((bodyW - inset * 2f) * fillFrac, h - inset * 2f),
            cornerRadius = CornerRadius(h * 0.18f, h * 0.18f),
        )
    }
}

@Composable
fun AlarmMiniGlyph(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.11f
        drawCircle(color, radius = w * 0.36f, center = Offset(w * 0.5f, w * 0.54f))
        drawLine(color, Offset(w * 0.16f, w * 0.24f), Offset(w * 0.32f, w * 0.12f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.84f, w * 0.24f), Offset(w * 0.68f, w * 0.12f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun AirplaneGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            // Horizontal right-pointing iOS 26 airplane silhouette
            moveTo(w * 0.90f, h * 0.50f)
            cubicTo(w * 0.90f, h * 0.44f, w * 0.83f, h * 0.42f, w * 0.74f, h * 0.42f)
            lineTo(w * 0.57f, h * 0.42f)
            lineTo(w * 0.37f, h * 0.13f)
            lineTo(w * 0.28f, h * 0.13f)
            lineTo(w * 0.39f, h * 0.42f)
            lineTo(w * 0.22f, h * 0.43f)
            lineTo(w * 0.14f, h * 0.31f)
            lineTo(w * 0.08f, h * 0.31f)
            lineTo(w * 0.13f, h * 0.50f)
            lineTo(w * 0.08f, h * 0.69f)
            lineTo(w * 0.14f, h * 0.69f)
            lineTo(w * 0.22f, h * 0.57f)
            lineTo(w * 0.39f, h * 0.58f)
            lineTo(w * 0.28f, h * 0.87f)
            lineTo(w * 0.37f, h * 0.87f)
            lineTo(w * 0.57f, h * 0.58f)
            lineTo(w * 0.74f, h * 0.58f)
            cubicTo(w * 0.83f, h * 0.58f, w * 0.90f, h * 0.56f, w * 0.90f, h * 0.50f)
            close()
        }
        drawPath(p, color = color)
    }
}

@Composable
fun AirDropGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val cx = w * 0.5f
        val cy = w * 0.48f
        val sw = w * 0.072f
        val radii = floatArrayOf(w * 0.14f, w * 0.26f, w * 0.38f)
        for (r in radii) {
            drawArc(
                color = color,
                startAngle = 132f,
                sweepAngle = 276f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
        val wedge = Path().apply {
            moveTo(cx, cy + w * 0.05f)
            lineTo(cx - w * 0.18f, w * 0.88f)
            lineTo(cx + w * 0.18f, w * 0.88f)
            close()
        }
        drawPath(wedge, color = color)
    }
}

@Composable
fun BluetoothRuneGlyph(color: Color, size: Dp = 15.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.105f
        val p = Path().apply {
            moveTo(w * 0.28f, h * 0.28f)
            lineTo(w * 0.72f, h * 0.72f)
            lineTo(w * 0.50f, h * 0.90f)
            lineTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.72f, h * 0.28f)
            lineTo(w * 0.28f, h * 0.72f)
        }
        drawPath(p, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun HotspotLinksGlyph(color: Color, size: Dp = 15.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.10f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.10f, h * 0.30f),
            size = Size(w * 0.52f, h * 0.40f),
            cornerRadius = CornerRadius(h * 0.20f, h * 0.20f),
            style = Stroke(width = sw),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.38f, h * 0.30f),
            size = Size(w * 0.52f, h * 0.40f),
            cornerRadius = CornerRadius(h * 0.20f, h * 0.20f),
            style = Stroke(width = sw),
        )
    }
}

@Composable
fun GlobeNetworkGlyph(color: Color, size: Dp = 15.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val r = w * 0.38f
        val c = Offset(w * 0.5f, w * 0.5f)
        val sw = w * 0.085f
        drawCircle(color = color, radius = r, center = c, style = Stroke(width = sw))
        drawOval(
            color = color,
            topLeft = Offset(c.x - r * 0.52f, c.y - r),
            size = Size(r * 1.04f, r * 2f),
            style = Stroke(width = sw * 0.9f),
        )
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = sw * 0.9f)
    }
}

@Composable
fun OrientationLockGlyph(color: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w * 0.5f
        val r = w * 0.36f
        val sw = w * 0.075f
        // Circular rotation arrow around padlock
        drawArc(
            color = color,
            startAngle = 35f,
            sweepAngle = 295f,
            useCenter = false,
            topLeft = Offset(c - r, c - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Arrowhead at ~330 deg (upper-right)
        val tipX = c + r * cos(Math.toRadians(335.0)).toFloat()
        val tipY = c + r * sin(Math.toRadians(335.0)).toFloat()
        val arrow = Path().apply {
            moveTo(tipX + w * 0.06f, tipY + w * 0.07f)
            lineTo(tipX - w * 0.10f, tipY + w * 0.02f)
            lineTo(tipX + w * 0.01f, tipY - w * 0.11f)
            close()
        }
        drawPath(arrow, color = color)

        // Inner padlock body + shackle
        val bodyW = w * 0.30f
        val bodyH = w * 0.24f
        drawRoundRect(
            color = color,
            topLeft = Offset(c - bodyW / 2f, c - bodyH * 0.15f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
        val shackleW = w * 0.18f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(c - shackleW / 2f, c - bodyH * 0.72f),
            size = Size(shackleW, shackleW),
            style = Stroke(width = sw * 0.90f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun SilentBellGlyph(
    slashed: Boolean,
    color: Color,
    size: Dp = 26.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        val w = this.size.width
        val h = this.size.height
        val bell = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            cubicTo(w * 0.33f, h * 0.14f, w * 0.24f, h * 0.27f, w * 0.24f, h * 0.46f)
            lineTo(w * 0.24f, h * 0.58f)
            lineTo(w * 0.15f, h * 0.70f)
            lineTo(w * 0.85f, h * 0.70f)
            lineTo(w * 0.76f, h * 0.58f)
            lineTo(w * 0.76f, h * 0.46f)
            cubicTo(w * 0.76f, h * 0.27f, w * 0.67f, h * 0.14f, w * 0.50f, h * 0.14f)
            close()
        }
        drawPath(bell, color = color)
        drawCircle(color = color, radius = w * 0.065f, center = Offset(w * 0.50f, h * 0.13f))
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(w * 0.40f, h * 0.68f),
            size = Size(w * 0.20f, h * 0.18f),
        )
        if (slashed) {
            drawLine(
                color = Color.Transparent,
                start = Offset(w * 0.20f, h * 0.16f),
                end = Offset(w * 0.82f, h * 0.82f),
                strokeWidth = w * 0.155f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Clear,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.18f, h * 0.15f),
                end = Offset(w * 0.83f, h * 0.83f),
                strokeWidth = w * 0.088f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun FocusPersonGlyph(color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color = color, radius = w * 0.19f, center = Offset(w * 0.50f, h * 0.31f))
        val shoulders = Path().apply {
            moveTo(w * 0.18f, h * 0.84f)
            cubicTo(w * 0.18f, h * 0.61f, w * 0.33f, h * 0.55f, w * 0.50f, h * 0.55f)
            cubicTo(w * 0.67f, h * 0.55f, w * 0.82f, h * 0.61f, w * 0.82f, h * 0.84f)
            close()
        }
        drawPath(shoulders, color = color)
    }
}

@Composable
fun UpDownChevronsGlyph(color: Color, size: Dp = 11.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.14f
        val up = Path().apply {
            moveTo(w * 0.22f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.78f, h * 0.38f)
        }
        val down = Path().apply {
            moveTo(w * 0.22f, h * 0.62f)
            lineTo(w * 0.50f, h * 0.86f)
            lineTo(w * 0.78f, h * 0.62f)
        }
        drawPath(up, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(down, color = color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun SunRaysGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w * 0.5f
        drawCircle(color = color, radius = w * 0.21f, center = Offset(c, c))
        val rInner = w * 0.31f
        val rOuter = w * 0.43f
        val sw = w * 0.085f
        for (i in 0 until 8) {
            val rad = (i * 45.0) * PI / 180.0
            val cs = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            drawLine(
                color = color,
                start = Offset(c + rInner * cs, c + rInner * sn),
                end = Offset(c + rOuter * cs, c + rOuter * sn),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun SpeakerWaveGlyph(
    level: Float,
    color: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cone = Path().apply {
            moveTo(w * 0.12f, h * 0.39f)
            lineTo(w * 0.27f, h * 0.39f)
            lineTo(w * 0.46f, h * 0.21f)
            lineTo(w * 0.46f, h * 0.79f)
            lineTo(w * 0.27f, h * 0.61f)
            lineTo(w * 0.12f, h * 0.61f)
            close()
        }
        drawPath(cone, color = color)
        val sw = w * 0.075f
        val cy = h * 0.50f
        if (level <= 0.02f) {
            drawLine(color, Offset(w * 0.58f, h * 0.36f), Offset(w * 0.84f, h * 0.64f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.84f, h * 0.36f), Offset(w * 0.58f, h * 0.64f), strokeWidth = sw, cap = StrokeCap.Round)
        } else {
            val r1 = w * 0.19f
            drawArc(
                color = color,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(w * 0.44f - r1 * 0.3f, cy - r1),
                size = Size(r1 * 2f, r1 * 2f),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            if (level > 0.38f) {
                val r2 = w * 0.33f
                drawArc(
                    color = color.copy(alpha = 0.78f),
                    startAngle = -44f,
                    sweepAngle = 88f,
                    useCenter = false,
                    topLeft = Offset(w * 0.44f - r2 * 0.3f, cy - r2),
                    size = Size(r2 * 2f, r2 * 2f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
fun BulbPillGlyph(color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bulb = Path().apply {
            moveTo(w * 0.36f, h * 0.68f)
            cubicTo(w * 0.34f, h * 0.55f, w * 0.22f, h * 0.48f, w * 0.22f, h * 0.34f)
            cubicTo(w * 0.22f, h * 0.17f, w * 0.35f, h * 0.08f, w * 0.50f, h * 0.08f)
            cubicTo(w * 0.65f, h * 0.08f, w * 0.78f, h * 0.17f, w * 0.78f, h * 0.34f)
            cubicTo(w * 0.78f, h * 0.48f, w * 0.66f, h * 0.55f, w * 0.64f, h * 0.68f)
            close()
        }
        drawPath(bulb, color = color)
        drawRoundRect(
            color = color.copy(alpha = 0.85f),
            topLeft = Offset(w * 0.37f, h * 0.73f),
            size = Size(w * 0.26f, h * 0.15f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
        )
    }
}

@Composable
fun FlashlightGlyph(color: Color, isOn: Boolean = false, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Top lens cap
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.32f, h * 0.11f),
            size = Size(w * 0.36f, h * 0.09f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        // Flared neck + vertical handle
        val body = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(w * 0.33f, h * 0.23f)
            lineTo(w * 0.67f, h * 0.23f)
            lineTo(w * 0.60f, h * 0.36f)
            lineTo(w * 0.60f, h * 0.85f)
            cubicTo(w * 0.60f, h * 0.89f, w * 0.56f, h * 0.91f, w * 0.50f, h * 0.91f)
            cubicTo(w * 0.44f, h * 0.91f, w * 0.40f, h * 0.89f, w * 0.40f, h * 0.85f)
            lineTo(w * 0.40f, h * 0.36f)
            close()

            // Center switch slot cutout
            addRoundRect(
                RoundRect(
                    rect = Rect(w * 0.455f, h * 0.46f, w * 0.545f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.045f, w * 0.045f),
                )
            )
        }
        drawPath(body, color = color)
        // Switch dot inside cutout (up when ON, down when OFF)
        drawCircle(
            color = color,
            radius = w * 0.032f,
            center = Offset(w * 0.50f, if (isOn) h * 0.51f else h * 0.59f),
        )
    }
}

@Composable
fun TimerDialGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w * 0.5f
        val cy = w * 0.53f
        val r = w * 0.36f
        val sw = w * 0.085f
        drawLine(color, Offset(c, w * 0.10f), Offset(c, w * 0.22f), strokeWidth = sw, cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = -52f,
            sweepAngle = 292f,
            useCenter = false,
            topLeft = Offset(c - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(c - r * 0.56f, cy - r * 0.56f),
            end = Offset(c, cy),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = sw * 0.65f, center = Offset(c, cy))
    }
}

@Composable
fun ScreenRecordGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w * 0.5f, w * 0.5f)
        drawCircle(color = color, radius = w * 0.37f, center = c, style = Stroke(width = w * 0.075f))
        drawCircle(color = color, radius = w * 0.18f, center = c)
    }
}

@Composable
fun QuickNoteControlGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.075f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, w * 0.16f),
            size = Size(w * 0.62f, w * 0.56f),
            cornerRadius = CornerRadius(w * 0.11f, w * 0.11f),
            style = Stroke(width = sw),
        )
        drawLine(color, Offset(w * 0.26f, w * 0.32f), Offset(w * 0.64f, w * 0.32f), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.26f, w * 0.45f), Offset(w * 0.56f, w * 0.45f), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.26f, w * 0.58f), Offset(w * 0.48f, w * 0.58f), strokeWidth = sw * 0.85f, cap = StrokeCap.Round)
        // Bottom-right + circle badge
        val bc = Offset(w * 0.72f, w * 0.72f)
        drawCircle(color = color, radius = w * 0.17f, center = bc)
        drawLine(Color(0xFF3E4552), Offset(bc.x - w * 0.08f, bc.y), Offset(bc.x + w * 0.08f, bc.y), strokeWidth = sw * 0.75f, cap = StrokeCap.Round)
        drawLine(Color(0xFF3E4552), Offset(bc.x, bc.y - w * 0.08f), Offset(bc.x, bc.y + w * 0.08f), strokeWidth = sw * 0.75f, cap = StrokeCap.Round)
    }
}

@Composable
fun LowPowerBatteryGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.07f
        drawRoundRect(
            color = color.copy(alpha = 0.72f),
            topLeft = Offset(w * 0.12f, h * 0.32f),
            size = Size(w * 0.68f, h * 0.36f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = sw),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.72f),
            topLeft = Offset(w * 0.84f, h * 0.42f),
            size = Size(w * 0.055f, h * 0.16f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.19f, h * 0.39f),
            size = Size(w * 0.24f, h * 0.22f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
    }
}

@Composable
fun CameraControlGlyph(color: Color, size: Dp = 25.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val body = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(
                RoundRect(
                    rect = Rect(w * 0.12f, h * 0.28f, w * 0.88f, h * 0.80f),
                    cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
                )
            )
            addOval(Rect(w * 0.33f, h * 0.37f, w * 0.67f, h * 0.71f))
        }
        drawPath(body, color = color)
        val hump = Path().apply {
            moveTo(w * 0.33f, h * 0.28f)
            lineTo(w * 0.40f, h * 0.18f)
            lineTo(w * 0.60f, h * 0.18f)
            lineTo(w * 0.67f, h * 0.28f)
            close()
        }
        drawPath(hump, color = color)
        drawCircle(color = color, radius = w * 0.09f, center = Offset(w * 0.50f, h * 0.54f))
    }
}

@Composable
fun DarkModeContrastGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w * 0.5f, w * 0.5f)
        val rOuter = w * 0.38f
        drawCircle(color = color, radius = rOuter, center = c, style = Stroke(width = w * 0.095f))
        val rInner = w * 0.22f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(c.x - rInner, c.y - rInner),
            size = Size(rInner * 2f, rInner * 2f),
        )
    }
}

@Composable
fun ScreenMirrorGlyph(color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.075f
        drawRoundRect(
            color = color.copy(alpha = 0.78f),
            topLeft = Offset(w * 0.14f, h * 0.22f),
            size = Size(w * 0.54f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(width = sw),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.30f, h * 0.36f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(width = sw),
        )
    }
}

@Composable
fun AirPlayRouteGlyph(color: Color, isPhoneIcon: Boolean = false, size: Dp = 17.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.09f
        if (isPhoneIcon) {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.30f, h * 0.14f),
                size = Size(w * 0.40f, h * 0.72f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                style = Stroke(width = sw),
            )
        } else {
            val cx = w * 0.5f
            val cy = h * 0.48f
            for (r in floatArrayOf(w * 0.18f, w * 0.34f)) {
                drawArc(
                    color = color,
                    startAngle = 138f,
                    sweepAngle = 264f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
            val tri = Path().apply {
                moveTo(cx, h * 0.58f)
                lineTo(cx - w * 0.20f, h * 0.88f)
                lineTo(cx + w * 0.20f, h * 0.88f)
                close()
            }
            drawPath(tri, color = color)
        }
    }
}

@Composable
fun TransportRewindGlyph(color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawLeftTriangle(color, w * 0.08f, w * 0.50f, h)
        drawLeftTriangle(color, w * 0.48f, w * 0.90f, h)
    }
}

@Composable
fun TransportFastForwardGlyph(color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRightTriangle(color, w * 0.10f, w * 0.52f, h)
        drawRightTriangle(color, w * 0.50f, w * 0.92f, h)
    }
}

@Composable
fun TransportPlayPauseGlyph(playing: Boolean, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (playing) {
            val bw = w * 0.20f
            drawRoundRect(color, Offset(w * 0.22f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw * 0.35f, bw * 0.35f))
            drawRoundRect(color, Offset(w * 0.58f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw * 0.35f, bw * 0.35f))
        } else {
            val p = Path().apply {
                moveTo(w * 0.24f, h * 0.14f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.86f)
                close()
            }
            drawPath(p, color = color)
        }
    }
}

private fun DrawScope.drawLeftTriangle(color: Color, xLeft: Float, xRight: Float, h: Float) {
    val p = Path().apply {
        moveTo(xLeft, h * 0.50f)
        lineTo(xRight, h * 0.22f)
        lineTo(xRight, h * 0.78f)
        close()
    }
    drawPath(p, color = color)
}

private fun DrawScope.drawRightTriangle(color: Color, xLeft: Float, xRight: Float, h: Float) {
    val p = Path().apply {
        moveTo(xRight, h * 0.50f)
        lineTo(xLeft, h * 0.22f)
        lineTo(xLeft, h * 0.78f)
        close()
    }
    drawPath(p, color = color)
}

@Composable
fun RainCloudArtCanvas(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(Color(0xFF0E081A))
        val sw = w * 0.042f
        val cloud = Path().apply {
            moveTo(w * 0.24f, h * 0.52f)
            cubicTo(w * 0.15f, h * 0.52f, w * 0.14f, h * 0.37f, w * 0.25f, h * 0.35f)
            cubicTo(w * 0.28f, h * 0.21f, w * 0.48f, h * 0.19f, w * 0.54f, h * 0.31f)
            cubicTo(w * 0.62f, h * 0.27f, w * 0.74f, h * 0.32f, w * 0.73f, h * 0.42f)
            cubicTo(w * 0.83f, h * 0.43f, w * 0.83f, h * 0.52f, w * 0.74f, h * 0.52f)
            close()
        }
        drawPath(cloud, color = Color.White, style = Stroke(width = sw, join = StrokeJoin.Round))
        val xs = floatArrayOf(0.28f, 0.39f, 0.50f, 0.61f, 0.71f)
        val lens = floatArrayOf(0.76f, 0.69f, 0.78f, 0.70f, 0.75f)
        for (i in xs.indices) {
            drawLine(
                color = Color.White.copy(alpha = 0.86f),
                start = Offset(w * xs[i], h * 0.58f),
                end = Offset(w * xs[i], h * lens[i]),
                strokeWidth = sw * 0.82f,
                cap = StrokeCap.Round,
            )
        }
    }
}

// Right-rail page icons
@Composable
fun RailHeartGlyph(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.50f, h * 0.88f)
            cubicTo(w * 0.14f, h * 0.62f, w * 0.08f, h * 0.34f, w * 0.26f, h * 0.20f)
            cubicTo(w * 0.38f, h * 0.12f, w * 0.50f, h * 0.22f, w * 0.50f, h * 0.31f)
            cubicTo(w * 0.50f, h * 0.22f, w * 0.62f, h * 0.12f, w * 0.74f, h * 0.20f)
            cubicTo(w * 0.92f, h * 0.34f, w * 0.86f, h * 0.62f, w * 0.50f, h * 0.88f)
            close()
        }
        drawPath(p, color = color)
    }
}

@Composable
fun RailHomeGlyph(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.10f
        val p = Path().apply {
            moveTo(w * 0.14f, h * 0.48f)
            lineTo(w * 0.50f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.48f)
            lineTo(w * 0.78f, h * 0.48f)
            lineTo(w * 0.78f, h * 0.84f)
            lineTo(w * 0.22f, h * 0.84f)
            lineTo(w * 0.22f, h * 0.48f)
            close()
        }
        drawPath(p, color = color, style = Stroke(width = sw, join = StrokeJoin.Round))
    }
}

@Composable
fun RailConnectivityGlyph(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w * 0.5f, w * 0.45f)
        val sw = w * 0.095f
        drawCircle(color = color, radius = w * 0.09f, center = c)
        drawLine(color, Offset(c.x, c.y + w * 0.08f), Offset(c.x, w * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
        for (r in floatArrayOf(w * 0.24f, w * 0.40f)) {
            drawArc(color, 145f, 70f, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, -35f, 70f, false, Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun RailMusicGlyph(color: Color, size: Dp = 13.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.10f
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.28f, h * 0.76f))
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.72f, h * 0.68f))
        drawLine(color, Offset(w * 0.39f, h * 0.74f), Offset(w * 0.39f, h * 0.26f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.83f, h * 0.66f), Offset(w * 0.83f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.39f, h * 0.26f), Offset(w * 0.83f, h * 0.18f), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
    }
}

// Bottom utility row glyphs matching ref_cc_clear_crop.png Row 7 for optional full 7-row parity
@Composable
fun AmbientRainGlyph(color: Color, size: Dp = 25.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.072f
        val cloud = Path().apply {
            moveTo(w * 0.24f, h * 0.54f)
            cubicTo(w * 0.13f, h * 0.54f, w * 0.12f, h * 0.37f, w * 0.25f, h * 0.35f)
            cubicTo(w * 0.29f, h * 0.20f, w * 0.50f, h * 0.18f, w * 0.56f, h * 0.31f)
            cubicTo(w * 0.65f, h * 0.27f, w * 0.78f, h * 0.33f, w * 0.76f, h * 0.43f)
            cubicTo(w * 0.86f, h * 0.44f, w * 0.86f, h * 0.54f, w * 0.76f, h * 0.54f)
            close()
        }
        drawPath(cloud, color = color, style = Stroke(width = sw, join = StrokeJoin.Round))
        for (i in 0..4) {
            val x = w * (0.26f + i * 0.12f)
            val endY = h * (if (i % 2 == 0) 0.80f else 0.73f)
            drawLine(color, Offset(x, h * 0.60f), Offset(x, endY), strokeWidth = sw * 0.82f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun CalculatorControlGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.075f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.12f),
            size = Size(w * 0.60f, h * 0.76f),
            cornerRadius = CornerRadius(w * 0.11f, w * 0.11f),
            style = Stroke(width = sw),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.30f, h * 0.22f),
            size = Size(w * 0.40f, h * 0.14f),
            cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
        )
        for (r in 0..1) {
            for (c in 0..1) {
                drawCircle(
                    color = color,
                    radius = w * 0.055f,
                    center = Offset(w * (0.37f + c * 0.26f), h * (0.50f + r * 0.20f)),
                )
            }
        }
    }
}

@Composable
fun SuperVoocBoltGlyph(color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bolt = Path().apply {
            moveTo(w * 0.56f, h * 0.12f)
            lineTo(w * 0.24f, h * 0.54f)
            lineTo(w * 0.48f, h * 0.54f)
            lineTo(w * 0.42f, h * 0.88f)
            lineTo(w * 0.76f, h * 0.46f)
            lineTo(w * 0.52f, h * 0.46f)
            close()
        }
        drawPath(bolt, color = color)
    }
}

@Composable
fun LiquidGlassPrismGlyph(
    color: Color,
    tinted: Boolean = false,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.082f

        // Back glass pane (filled softly when Tinted mode is active)
        drawRoundRect(
            color = if (tinted) color.copy(alpha = 0.36f) else color.copy(alpha = 0.14f),
            topLeft = Offset(w * 0.14f, h * 0.24f),
            size = Size(w * 0.50f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
        drawRoundRect(
            color = color.copy(alpha = 0.72f),
            topLeft = Offset(w * 0.14f, h * 0.24f),
            size = Size(w * 0.50f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
            style = Stroke(width = sw),
        )

        // Front refractive squircle lens + 4-point specular star glint
        drawRoundRect(
            color = if (tinted) color.copy(alpha = 0.52f) else color.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.34f, h * 0.14f),
            size = Size(w * 0.52f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.15f, w * 0.15f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.14f),
            size = Size(w * 0.52f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.15f, w * 0.15f),
            style = Stroke(width = sw),
        )

        val sx = w * 0.60f
        val sy = h * 0.41f
        val sr = w * 0.13f
        val star = Path().apply {
            moveTo(sx, sy - sr)
            quadraticBezierTo(sx, sy, sx + sr, sy)
            quadraticBezierTo(sx, sy, sx, sy + sr)
            quadraticBezierTo(sx, sy, sx - sr, sy)
            quadraticBezierTo(sx, sy, sx, sy - sr)
            close()
        }
        drawPath(star, color = color)
    }
}
