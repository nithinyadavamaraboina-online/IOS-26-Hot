package com.realme.samurai.iossettings.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realme.samurai.iossettings.ui.theme.HeroIconTileShape
import com.realme.samurai.iossettings.ui.theme.IconTileShape
import com.realme.samurai.iossettings.ui.theme.InterFontFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class SettingsIcon(
    val topColor: Color,
    val bottomColor: Color,
) {
    AIRPLANE(Color(0xFFFFAE34), Color(0xFFFF9500)),
    WIFI(Color(0xFF1F90FF), Color(0xFF007AFF)),
    BLUETOOTH(Color(0xFF1F90FF), Color(0xFF007AFF)),
    CELLULAR(Color(0xFF42D668), Color(0xFF34C759)),
    HOTSPOT(Color(0xFF42D668), Color(0xFF34C759)),
    BATTERY(Color(0xFF42D668), Color(0xFF34C759)),
    GENERAL(Color(0xFF9A9AA1), Color(0xFF8E8E93)),
    ACCESSIBILITY(Color(0xFF1F90FF), Color(0xFF007AFF)),
    CAMERA(Color(0xFF9A9AA1), Color(0xFF8E8E93)),
    CONTROL_CENTER(Color(0xFF9A9AA1), Color(0xFF8E8E93)),
    DISPLAY(Color(0xFF1F90FF), Color(0xFF007AFF)),
    HOME_SCREEN(Color(0xFF3D88FF), Color(0xFF236BE8)),
    SEARCH_SETTINGS(Color(0xFF9A9AA1), Color(0xFF8E8E93)),
    WALLPAPER(Color(0xFF46C0FA), Color(0xFF289CE0)),
    NOTIFICATIONS(Color(0xFFFF5348), Color(0xFFFF3B30)),
    SOUNDS(Color(0xFFFF496E), Color(0xFFFF2D55)),
    FOCUS(Color(0xFF6B69E6), Color(0xFF5856D6)),
    SCREEN_TIME(Color(0xFF6B69E6), Color(0xFF5856D6)),
    PASSCODE(Color(0xFF42D668), Color(0xFF34C759)),
    SOS(Color(0xFFFF5348), Color(0xFFFF3B30)),
    PRIVACY(Color(0xFF1F90FF), Color(0xFF007AFF)),
    LOCATION(Color(0xFF1F90FF), Color(0xFF007AFF)),
    APPS(Color(0xFF6B69E6), Color(0xFF5856D6)),
    REALME_HARDWARE(Color(0xFFBF68EC), Color(0xFFAF52DE)),
    DEVELOPER(Color(0xFF9A9AA1), Color(0xFF8E8E93)),
}

@Composable
fun SettingsIconBadge(
    icon: SettingsIcon,
    modifier: Modifier = Modifier,
    size: Dp = 29.dp,
) {
    val tileShape = if (size > 38.dp) HeroIconTileShape else IconTileShape
    Box(
        modifier = modifier
            .size(size)
            .clip(tileShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(icon.topColor, icon.bottomColor),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == SettingsIcon.SOS) {
            Text(
                text = "SOS",
                color = Color.White,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.34f).sp,
                letterSpacing = (-0.3).sp,
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSettingsGlyph(icon)
            }
        }
    }
}

private fun DrawScope.drawSettingsGlyph(icon: SettingsIcon) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val u = w / 29f

    when (icon) {
        SettingsIcon.AIRPLANE -> {
            // Exact iOS 26 horizontal right-pointing airplane silhouette (`0 deg`)
            val jet = Path().apply {
                // Fuselage nose at right (23.6, 14.5)
                moveTo(23.6f * u, 14.5f * u)
                cubicTo(23.6f * u, 13.5f * u, 22.3f * u, 13.1f * u, 20.8f * u, 13.1f * u)
                lineTo(16.6f * u, 13.1f * u)
                // Upper swept main wing
                lineTo(11.6f * u, 6.2f * u)
                lineTo(9.7f * u, 6.2f * u)
                lineTo(12.2f * u, 13.1f * u)
                lineTo(8.3f * u, 13.3f * u)
                // Upper tail stabilizer
                lineTo(6.3f * u, 10.6f * u)
                lineTo(5.0f * u, 10.6f * u)
                lineTo(6.1f * u, 14.5f * u)
                // Lower tail stabilizer (symmetric)
                lineTo(5.0f * u, 18.4f * u)
                lineTo(6.3f * u, 18.4f * u)
                lineTo(8.3f * u, 15.7f * u)
                lineTo(12.2f * u, 15.9f * u)
                // Lower swept main wing
                lineTo(9.7f * u, 22.8f * u)
                lineTo(11.6f * u, 22.8f * u)
                lineTo(16.6f * u, 15.9f * u)
                lineTo(20.8f * u, 15.9f * u)
                cubicTo(22.3f * u, 15.9f * u, 23.6f * u, 15.5f * u, 23.6f * u, 14.5f * u)
                close()
            }
            drawPath(jet, Color.White)
        }

        SettingsIcon.WIFI -> {
            // Exact Apple SF `wifi` filled annular fan bands + bottom wedge (matching wave5_03e_wifi.png)
            drawAppleWifiFan(
                apex = Offset(cx, 21.6f * u),
                unit = u,
                activeBars = 3,
                activeColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.3f),
            )
        }

        SettingsIcon.BLUETOOTH -> {
            val stroke = Stroke(width = 1.95f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val bt = Path().apply {
                moveTo(cx, 6.0f * u)
                lineTo(19.2f * u, 10.5f * u)
                lineTo(9.8f * u, 18.5f * u)
                moveTo(cx, 23.0f * u)
                lineTo(19.2f * u, 18.5f * u)
                lineTo(9.8f * u, 10.5f * u)
                moveTo(cx, 6.0f * u)
                lineTo(cx, 23.0f * u)
            }
            drawPath(bt, Color.White, style = stroke)
        }

        SettingsIcon.CELLULAR -> {
            // Exact iOS 26 `antenna.radiowaves.left.and.right` (`((•))` + vertical mast)
            drawCircle(Color.White, radius = 1.95f * u, center = Offset(cx, 12.6f * u))
            drawLine(
                color = Color.White,
                start = Offset(cx, 13.8f * u),
                end = Offset(cx, 22.2f * u),
                strokeWidth = 2.0f * u,
                cap = StrokeCap.Round,
            )
            val arcStroke = Stroke(width = 1.75f * u, cap = StrokeCap.Round)
            val innerR = 4.9f * u
            val outerR = 8.3f * u
            val ac = Offset(cx, 12.6f * u)
            // Left inner & outer arcs
            drawArc(
                color = Color.White,
                startAngle = 136f,
                sweepAngle = 88f,
                useCenter = false,
                topLeft = Offset(ac.x - innerR, ac.y - innerR),
                size = Size(innerR * 2, innerR * 2),
                style = arcStroke,
            )
            drawArc(
                color = Color.White,
                startAngle = 134f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = Offset(ac.x - outerR, ac.y - outerR),
                size = Size(outerR * 2, outerR * 2),
                style = arcStroke,
            )
            // Right inner & outer arcs
            drawArc(
                color = Color.White,
                startAngle = -44f,
                sweepAngle = 88f,
                useCenter = false,
                topLeft = Offset(ac.x - innerR, ac.y - innerR),
                size = Size(innerR * 2, innerR * 2),
                style = arcStroke,
            )
            drawArc(
                color = Color.White,
                startAngle = -46f,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = Offset(ac.x - outerR, ac.y - outerR),
                size = Size(outerR * 2, outerR * 2),
                style = arcStroke,
            )
        }

        SettingsIcon.HOTSPOT -> {
            // Exact iOS 26 `personalhotspot`: two interlocked horizontal oval links
            val linkStroke = Stroke(width = 2.05f * u, cap = StrokeCap.Round)
            val linkW = 10.8f * u
            val linkH = 6.6f * u
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(5.4f * u, cy - linkH * 0.62f),
                size = Size(linkW, linkH),
                cornerRadius = CornerRadius(linkH / 2, linkH / 2),
                style = linkStroke,
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(12.8f * u, cy - linkH * 0.38f),
                size = Size(linkW, linkH),
                cornerRadius = CornerRadius(linkH / 2, linkH / 2),
                style = linkStroke,
            )
        }

        SettingsIcon.BATTERY -> {
            val bodyLeft = 5.4f * u
            val bodyTop = 9.6f * u
            val bodyW = 16.2f * u
            val bodyH = 9.8f * u
            drawRoundRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = Offset(bodyLeft, bodyTop),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(2.6f * u, 2.6f * u),
                style = Stroke(width = 1.55f * u),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(bodyLeft + 1.9f * u, bodyTop + 1.9f * u),
                size = Size((bodyW - 3.8f * u) * 0.82f, bodyH - 3.8f * u),
                cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = Offset(bodyLeft + bodyW + 1.1f * u, cy - 2.1f * u),
                size = Size(1.5f * u, 4.2f * u),
                cornerRadius = CornerRadius(0.8f * u, 0.8f * u),
            )
        }

        SettingsIcon.GENERAL -> {
            // Exact iOS Settings spoked precision gear (hot0151_settings.png)
            val outerRingR = 8.4f * u
            val toothOuterR = 10.2f * u
            for (i in 0 until 14) {
                val angle = (i * 360.0 / 14.0) * PI / 180.0
                val sx = cx + (outerRingR - 0.5f * u) * cos(angle).toFloat()
                val sy = cy + (outerRingR - 0.5f * u) * sin(angle).toFloat()
                val ex = cx + toothOuterR * cos(angle).toFloat()
                val ey = cy + toothOuterR * sin(angle).toFloat()
                drawLine(Color.White, Offset(sx, sy), Offset(ex, ey), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
            }
            drawCircle(Color.White, radius = outerRingR, center = Offset(cx, cy), style = Stroke(width = 1.85f * u))
            for (i in 0 until 6) {
                val angle = (i * 60.0 - 90.0) * PI / 180.0
                val sx = cx + 2.8f * u * cos(angle).toFloat()
                val sy = cy + 2.8f * u * sin(angle).toFloat()
                val ex = cx + (outerRingR - 0.3f * u) * cos(angle).toFloat()
                val ey = cy + (outerRingR - 0.3f * u) * sin(angle).toFloat()
                drawLine(Color.White, Offset(sx, sy), Offset(ex, ey), strokeWidth = 1.45f * u)
            }
            drawCircle(Color.White, radius = 2.9f * u, center = Offset(cx, cy), style = Stroke(width = 1.55f * u))
        }

        SettingsIcon.ACCESSIBILITY -> {
            // Exact iOS Accessibility circle + Vitruvian figure (hot0151_settings.png)
            drawCircle(Color.White, radius = 9.5f * u, center = Offset(cx, cy), style = Stroke(width = 1.65f * u))
            drawCircle(Color.White, radius = 1.65f * u, center = Offset(cx, 9.2f * u))
            // Outstretched arms
            drawLine(Color.White, Offset(9.5f * u, 12.2f * u), Offset(19.5f * u, 12.2f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
            // Torso
            drawLine(Color.White, Offset(cx, 12.0f * u), Offset(cx, 16.0f * u), strokeWidth = 1.85f * u, cap = StrokeCap.Round)
            // Legs
            drawLine(Color.White, Offset(cx, 15.8f * u), Offset(11.6f * u, 20.6f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx, 15.8f * u), Offset(17.4f * u, 20.6f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
        }

        SettingsIcon.CAMERA -> {
            val camBody = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 5.6f * u,
                        top = 9.4f * u,
                        right = 23.4f * u,
                        bottom = 21.4f * u,
                        cornerRadius = CornerRadius(2.8f * u, 2.8f * u),
                    )
                )
                // Top prism hump
                moveTo(10.2f * u, 9.5f * u)
                lineTo(11.6f * u, 7.2f * u)
                lineTo(17.4f * u, 7.2f * u)
                lineTo(18.8f * u, 9.5f * u)
                close()
            }
            val lensCut = Path().apply {
                addOval(Rect(center = Offset(cx, 15.4f * u), radius = 4.0f * u))
            }
            val combined = Path.combine(PathOperation.Difference, camBody, lensCut)
            drawPath(combined, Color.White)
            drawCircle(Color.White, radius = 2.3f * u, center = Offset(cx, 15.4f * u))
        }

        SettingsIcon.CONTROL_CENTER -> {
            // Two stacked pill switches (hot0151_settings.png)
            val pillW = 15.6f * u
            val pillH = 6.2f * u
            val left = cx - pillW / 2f
            // Top pill (knob left)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, 7.4f * u),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillH / 2, pillH / 2),
                style = Stroke(width = 1.55f * u),
            )
            drawCircle(Color.White, radius = 2.15f * u, center = Offset(left + 3.3f * u, 7.4f * u + pillH / 2))
            // Bottom pill (knob right)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, 15.4f * u),
                size = Size(pillW, pillH),
                cornerRadius = CornerRadius(pillH / 2, pillH / 2),
                style = Stroke(width = 1.55f * u),
            )
            drawCircle(Color.White, radius = 2.15f * u, center = Offset(left + pillW - 3.3f * u, 15.4f * u + pillH / 2))
        }

        SettingsIcon.DISPLAY -> {
            drawCircle(Color.White, radius = 4.3f * u, center = Offset(cx, cy))
            for (i in 0 until 8) {
                val angle = (i * 45.0) * PI / 180.0
                val sx = cx + 6.4f * u * cos(angle).toFloat()
                val sy = cy + 6.4f * u * sin(angle).toFloat()
                val ex = cx + 9.4f * u * cos(angle).toFloat()
                val ey = cy + 9.4f * u * sin(angle).toFloat()
                drawLine(Color.White, Offset(sx, sy), Offset(ex, ey), strokeWidth = 1.85f * u, cap = StrokeCap.Round)
            }
        }

        SettingsIcon.HOME_SCREEN -> {
            val cell = 3.5f * u
            val gap = 1.6f * u
            val startX = cx - (cell * 3 + gap * 2) / 2f
            val startY = 6.6f * u
            for (r in 0..1) {
                for (c in 0..2) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(startX + c * (cell + gap), startY + r * (cell + gap)),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(0.95f * u, 0.95f * u),
                    )
                }
            }
            drawRoundRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = Offset(startX, 18.8f * u),
                size = Size(cell * 3 + gap * 2, 3.2f * u),
                cornerRadius = CornerRadius(1.6f * u, 1.6f * u),
            )
        }

        SettingsIcon.SEARCH_SETTINGS -> {
            drawCircle(
                color = Color.White,
                radius = 5.4f * u,
                center = Offset(13.0f * u, 13.0f * u),
                style = Stroke(width = 2.15f * u),
            )
            drawLine(
                color = Color.White,
                start = Offset(17.0f * u, 17.0f * u),
                end = Offset(21.6f * u, 21.6f * u),
                strokeWidth = 2.35f * u,
                cap = StrokeCap.Round,
            )
        }

        SettingsIcon.WALLPAPER -> {
            for (i in 0 until 8) {
                rotate(degrees = i * 45f, pivot = Offset(cx, cy)) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.56f),
                        topLeft = Offset(cx - 2.35f * u, cy - 9.2f * u),
                        size = Size(4.7f * u, 7.6f * u),
                        cornerRadius = CornerRadius(2.35f * u, 2.35f * u),
                    )
                }
            }
        }

        SettingsIcon.NOTIFICATIONS -> {
            val bell = Path().apply {
                moveTo(cx, 7.2f * u)
                cubicTo(10.8f * u, 7.2f * u, 9.2f * u, 9.8f * u, 9.2f * u, 13.4f * u)
                lineTo(9.2f * u, 16.2f * u)
                lineTo(7.4f * u, 18.4f * u)
                lineTo(21.6f * u, 18.4f * u)
                lineTo(19.8f * u, 16.2f * u)
                lineTo(19.8f * u, 13.4f * u)
                cubicTo(19.8f * u, 9.8f * u, 18.2f * u, 7.2f * u, cx, 7.2f * u)
                close()
            }
            drawPath(bell, Color.White)
            drawCircle(Color.White, radius = 1.9f * u, center = Offset(cx, 20.2f * u))
        }

        SettingsIcon.SOUNDS -> {
            val speaker = Path().apply {
                moveTo(6.8f * u, 12.0f * u)
                lineTo(10.2f * u, 12.0f * u)
                lineTo(14.6f * u, 8.2f * u)
                lineTo(14.6f * u, 20.8f * u)
                lineTo(10.2f * u, 17.0f * u)
                lineTo(6.8f * u, 17.0f * u)
                close()
            }
            drawPath(speaker, Color.White)
            val waveStroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
            drawArc(
                color = Color.White,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(14.6f * u - 3.6f * u, cy - 3.6f * u),
                size = Size(7.2f * u, 7.2f * u),
                style = waveStroke,
            )
            drawArc(
                color = Color.White,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(14.6f * u - 6.8f * u, cy - 6.8f * u),
                size = Size(13.6f * u, 13.6f * u),
                style = waveStroke,
            )
        }

        SettingsIcon.FOCUS -> {
            val outer = Path().apply {
                addOval(Rect(center = Offset(cx, cy), radius = 7.8f * u))
            }
            val cut = Path().apply {
                addOval(Rect(center = Offset(cx + 3.6f * u, cy - 3.0f * u), radius = 6.9f * u))
            }
            drawPath(Path.combine(PathOperation.Difference, outer, cut), Color.White)
        }

        SettingsIcon.SCREEN_TIME -> {
            val glass = Path().apply {
                moveTo(9.2f * u, 7.2f * u)
                lineTo(19.8f * u, 7.2f * u)
                lineTo(15.6f * u, cy)
                lineTo(19.8f * u, 21.8f * u)
                lineTo(9.2f * u, 21.8f * u)
                lineTo(13.4f * u, cy)
                close()
            }
            drawPath(glass, Color.White, style = Stroke(width = 1.75f * u, join = StrokeJoin.Round))
            drawLine(Color.White, Offset(8.4f * u, 7.2f * u), Offset(20.6f * u, 7.2f * u), strokeWidth = 2.0f * u, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(8.4f * u, 21.8f * u), Offset(20.6f * u, 21.8f * u), strokeWidth = 2.0f * u, cap = StrokeCap.Round)
        }

        SettingsIcon.PASSCODE -> {
            // Clean Touch ID concentric biometric ridges
            val stroke = Stroke(width = 1.65f * u, cap = StrokeCap.Round)
            for (r in listOf(3.0f, 5.6f, 8.2f)) {
                drawArc(
                    color = Color.White,
                    startAngle = 195f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = Offset(cx - r * u, cy + 1.5f * u - r * u),
                    size = Size(r * 2 * u, r * 2 * u),
                    style = stroke,
                )
            }
            drawLine(Color.White, Offset(cx, cy - 0.5f * u), Offset(cx, 20.8f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx - 2.9f * u, cy + 1.2f * u), Offset(cx - 2.9f * u, 19.6f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx + 2.9f * u, cy + 1.2f * u), Offset(cx + 2.9f * u, 19.6f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
        }

        SettingsIcon.SOS -> Unit

        SettingsIcon.PRIVACY -> {
            // Raised open palm (`hand.raised.fill`)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(9.6f * u, 13.0f * u),
                size = Size(9.8f * u, 8.6f * u),
                cornerRadius = CornerRadius(4.2f * u, 4.2f * u),
            )
            // 4 vertical fingers + thumb
            val fingers = listOf(
                Triple(10.5f, 8.6f, 5.6f),
                Triple(12.9f, 7.2f, 6.8f),
                Triple(15.3f, 7.6f, 6.4f),
                Triple(17.7f, 9.2f, 5.0f),
            )
            for ((fx, fy, fh) in fingers) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(fx * u, fy * u),
                    size = Size(1.75f * u, fh * u),
                    cornerRadius = CornerRadius(0.9f * u, 0.9f * u),
                )
            }
            rotate(-32f, pivot = Offset(9.8f * u, 16.5f * u)) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(7.4f * u, 13.4f * u),
                    size = Size(2.0f * u, 5.2f * u),
                    cornerRadius = CornerRadius(1.0f * u, 1.0f * u),
                )
            }
        }

        SettingsIcon.LOCATION -> {
            val arrow = Path().apply {
                moveTo(21.2f * u, 7.8f * u)
                lineTo(7.4f * u, 14.0f * u)
                lineTo(13.6f * u, 15.4f * u)
                lineTo(15.0f * u, 21.6f * u)
                close()
            }
            drawPath(arrow, Color.White)
        }

        SettingsIcon.APPS -> {
            val s = 6.2f * u
            val r = CornerRadius(1.6f * u, 1.6f * u)
            drawRoundRect(Color.White, Offset(7.4f * u, 7.4f * u), Size(s, s), r)
            drawRoundRect(Color.White, Offset(7.4f * u, 15.4f * u), Size(s, s), r)
            drawRoundRect(Color.White, Offset(15.4f * u, 15.4f * u), Size(s, s), r)
            rotate(18f, pivot = Offset(18.5f * u, 10.5f * u)) {
                drawRoundRect(Color.White, Offset(15.4f * u, 7.4f * u), Size(s, s), r)
            }
        }

        SettingsIcon.REALME_HARDWARE -> {
            // Silicon die (`cpu`)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(9.2f * u, 9.2f * u),
                size = Size(10.6f * u, 10.6f * u),
                cornerRadius = CornerRadius(2.2f * u, 2.2f * u),
                style = Stroke(width = 1.65f * u),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(11.8f * u, 11.8f * u),
                size = Size(5.4f * u, 5.4f * u),
                cornerRadius = CornerRadius(1.0f * u, 1.0f * u),
            )
            for (p in listOf(11.6f, 14.5f, 17.4f)) {
                drawLine(Color.White, Offset(p * u, 6.4f * u), Offset(p * u, 9.0f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(p * u, 20.0f * u), Offset(p * u, 22.6f * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(6.4f * u, p * u), Offset(9.0f * u, p * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(20.0f * u, p * u), Offset(22.6f * u, p * u), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
            }
        }

        SettingsIcon.DEVELOPER -> {
            rotate(-45f, pivot = Offset(cx, cy)) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(cx - 1.5f * u, 11.5f * u),
                    size = Size(3.0f * u, 11.5f * u),
                    cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(cx - 5.8f * u, 7.2f * u),
                    size = Size(11.6f * u, 4.6f * u),
                    cornerRadius = CornerRadius(1.4f * u, 1.4f * u),
                )
            }
        }
    }
}

private fun DrawScope.drawAppleWifiFan(
    apex: Offset,
    unit: Float,
    activeBars: Int,
    activeColor: Color,
    inactiveColor: Color,
) {
    val startDeg = -136f
    val sweepDeg = 92f

    fun bandPath(innerR: Float, outerR: Float): Path = Path().apply {
        arcTo(
            rect = Rect(apex.x - outerR, apex.y - outerR, apex.x + outerR, apex.y + outerR),
            startAngleDegrees = startDeg,
            sweepAngleDegrees = sweepDeg,
            forceMoveTo = true,
        )
        arcTo(
            rect = Rect(apex.x - innerR, apex.y - innerR, apex.x + innerR, apex.y + innerR),
            startAngleDegrees = startDeg + sweepDeg,
            sweepAngleDegrees = -sweepDeg,
            forceMoveTo = false,
        )
        close()
    }

    // Band 3 (outermost)
    drawPath(
        path = bandPath(11.4f * unit, 14.0f * unit),
        color = if (activeBars >= 3) activeColor else inactiveColor,
    )
    // Band 2 (middle)
    drawPath(
        path = bandPath(6.7f * unit, 9.2f * unit),
        color = if (activeBars >= 2) activeColor else inactiveColor,
    )
    // Band 1 (bottom fan sector with soft apex)
    val wedge = Path().apply {
        val r = 4.4f * unit
        moveTo(apex.x, apex.y)
        arcTo(
            rect = Rect(apex.x - r, apex.y - r, apex.x + r, apex.y + r),
            startAngleDegrees = startDeg,
            sweepAngleDegrees = sweepDeg,
            forceMoveTo = false,
        )
        close()
    }
    drawPath(wedge, if (activeBars >= 1) activeColor else inactiveColor)
    drawCircle(
        color = if (activeBars >= 1) activeColor else inactiveColor,
        radius = 1.05f * unit,
        center = Offset(apex.x, apex.y - 0.85f * unit),
    )
}

/**
 * Exact 3-ring concentric graduated blue dotted Account Halo from `hot0151_settings.png`.
 */
@Composable
fun AccountDottedHaloAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 62.dp,
    signedInInitial: String? = null,
) {
    if (!signedInInitial.isNullOrBlank()) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF42A1FF), Color(0xFF007AFF))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = signedInInitial.take(2).uppercase(),
                color = Color.White,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
        return
    }

    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val u = this.size.width / 62f

        val rings = listOf(
            Triple(16.2f * u, 14, Color(0xFF1F87FF) to (1.65f * u)),
            Triple(21.4f * u, 18, Color(0xFF53A4FF) to (1.50f * u)),
            Triple(26.5f * u, 22, Color(0xFF7EC0FF) to (1.30f * u)),
        )
        rings.forEachIndexed { idx, (ringR, count, spec) ->
            val (dotColor, dotR) = spec
            val phaseOffset = idx * (PI / count)
            for (i in 0 until count) {
                val theta = phaseOffset + (2.0 * PI * i.toDouble() / count)
                val dx = cx + ringR * cos(theta).toFloat()
                val dy = cy + ringR * sin(theta).toFloat()
                drawCircle(color = dotColor, radius = dotR, center = Offset(dx, dy))
            }
        }

        // Crisp central `#007AFF` account person glyph inside the dotted halo
        val accent = Color(0xFF007AFF)
        drawCircle(accent, radius = 3.6f * u, center = Offset(cx, cy - 3.2f * u))
        val shoulders = Path().apply {
            moveTo(cx - 7.0f * u, cy + 6.4f * u)
            cubicTo(
                cx - 7.0f * u, cy + 1.8f * u,
                cx + 7.0f * u, cy + 1.8f * u,
                cx + 7.0f * u, cy + 6.4f * u,
            )
            cubicTo(
                cx + 4.8f * u, cy + 8.2f * u,
                cx - 4.8f * u, cy + 8.2f * u,
                cx - 7.0f * u, cy + 6.4f * u,
            )
            close()
        }
        drawPath(shoulders, accent)
    }
}

@Composable
fun ChevronRightIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 8.dp, height = 13.5.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.12f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.88f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.95.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun ChevronLeftIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 10.5.dp, height = 17.5.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.80f, h * 0.12f)
            lineTo(w * 0.20f, h * 0.50f)
            lineTo(w * 0.80f, h * 0.88f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun MagnifyingGlassIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 18f
        drawCircle(
            color = color,
            radius = 5.6f * u,
            center = Offset(7.8f * u, 7.8f * u),
            style = Stroke(width = 1.65f * u),
        )
        drawLine(
            color = color,
            start = Offset(12.0f * u, 12.0f * u),
            end = Offset(16.0f * u, 16.0f * u),
            strokeWidth = 1.85f * u,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun MicrophoneIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 19.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 19f
        val cx = this.size.width / 2f
        // Capsule mic head (outline matching hot0151_settings.png)
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - 2.6f * u, 2.0f * u),
            size = Size(5.2f * u, 8.8f * u),
            cornerRadius = CornerRadius(2.6f * u, 2.6f * u),
            style = Stroke(width = 1.55f * u),
        )
        // U-cradle
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - 5.2f * u, 5.6f * u),
            size = Size(10.4f * u, 8.2f * u),
            style = Stroke(width = 1.55f * u, cap = StrokeCap.Round),
        )
        // Stem + base
        drawLine(color, Offset(cx, 13.8f * u), Offset(cx, 16.8f * u), strokeWidth = 1.55f * u, cap = StrokeCap.Round)
        drawLine(color, Offset(cx - 3.0f * u, 16.8f * u), Offset(cx + 3.0f * u, 16.8f * u), strokeWidth = 1.55f * u, cap = StrokeCap.Round)
    }
}

@Composable
fun CheckmarkIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.14f, h * 0.54f)
            lineTo(w * 0.40f, h * 0.82f)
            lineTo(w * 0.88f, h * 0.18f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.05.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun InfoCircleIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 22f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        drawCircle(
            color = color,
            radius = 9.8f * u,
            center = Offset(cx, cy),
            style = Stroke(width = 1.45f * u),
        )
        drawCircle(color = color, radius = 1.15f * u, center = Offset(cx, 6.9f * u))
        drawLine(
            color = color,
            start = Offset(cx, 10.1f * u),
            end = Offset(cx, 15.8f * u),
            strokeWidth = 1.75f * u,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun LockFillIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 13.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 13f
        val cx = this.size.width / 2f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - 2.7f * u, 1.5f * u),
            size = Size(5.4f * u, 5.8f * u),
            style = Stroke(width = 1.45f * u, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - 4.3f * u, 5.6f * u),
            size = Size(8.6f * u, 6.2f * u),
            cornerRadius = CornerRadius(1.5f * u, 1.5f * u),
        )
    }
}

@Composable
fun WifiSignalIcon(
    bars: Int,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 20f
        drawAppleWifiFan(
            apex = Offset(this.size.width / 2f, 16.2f * u),
            unit = u,
            activeBars = bars.coerceIn(1, 3),
            activeColor = color,
            inactiveColor = color.copy(alpha = 0.25f),
        )
    }
}

@Composable
fun SunSliderIcon(
    color: Color,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (large) 22.dp else 16.dp
    Canvas(modifier = modifier.size(iconSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val u = size.width / 22f
        drawCircle(color, radius = 4.2f * u, center = Offset(cx, cy))
        for (i in 0 until 8) {
            val angle = (i * 45.0) * PI / 180.0
            val sx = cx + 6.5f * u * cos(angle).toFloat()
            val sy = cy + 6.5f * u * sin(angle).toFloat()
            val ex = cx + 9.4f * u * cos(angle).toFloat()
            val ey = cy + 9.4f * u * sin(angle).toFloat()
            drawLine(color, Offset(sx, sy), Offset(ex, ey), strokeWidth = 1.7f * u, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun SpeakerSliderIcon(
    color: Color,
    waves: Int,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val u = size.width / 20f
        val cy = size.height / 2f
        val cone = Path().apply {
            moveTo(2.5f * u, 7.6f * u)
            lineTo(5.6f * u, 7.6f * u)
            lineTo(9.8f * u, 4.2f * u)
            lineTo(9.8f * u, 15.8f * u)
            lineTo(5.6f * u, 12.4f * u)
            lineTo(2.5f * u, 12.4f * u)
            close()
        }
        drawPath(cone, color)
        if (waves >= 1) {
            drawArc(
                color = color,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(9.8f * u - 3.4f * u, cy - 3.4f * u),
                size = Size(6.8f * u, 6.8f * u),
                style = Stroke(width = 1.55f * u, cap = StrokeCap.Round),
            )
        }
        if (waves >= 2) {
            drawArc(
                color = color,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(9.8f * u - 6.6f * u, cy - 6.6f * u),
                size = Size(13.2f * u, 13.2f * u),
                style = Stroke(width = 1.55f * u, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Exact 3 toolbar glyphs inside the iOS 26.1 `Liquid Glass` preview card (`ios26_08_ios261_clear_vs_tinted.png`).
 */
@Composable
fun TrashOutlineIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val u = size.width / 24f
        val s = Stroke(width = 1.65f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(5.2f * u, 6.5f * u), Offset(18.8f * u, 6.5f * u), strokeWidth = 1.65f * u, cap = StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(9.4f * u, 3.8f * u),
            size = Size(5.2f * u, 2.7f * u),
            cornerRadius = CornerRadius(1.2f * u, 1.2f * u),
            style = s,
        )
        val bin = Path().apply {
            moveTo(6.8f * u, 6.6f * u)
            lineTo(7.7f * u, 19.2f * u)
            cubicTo(7.8f * u, 20.1f * u, 8.4f * u, 20.6f * u, 9.3f * u, 20.6f * u)
            lineTo(14.7f * u, 20.6f * u)
            cubicTo(15.6f * u, 20.6f * u, 16.2f * u, 20.1f * u, 16.3f * u, 19.2f * u)
            lineTo(17.2f * u, 6.6f * u)
        }
        drawPath(bin, color, style = s)
        for (x in listOf(10.0f, 12.0f, 14.0f)) {
            drawLine(color, Offset(x * u, 9.8f * u), Offset(x * u, 17.6f * u), strokeWidth = 1.45f * u, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun FolderOutlineIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val u = size.width / 24f
        val folder = Path().apply {
            moveTo(3.4f * u, 7.6f * u)
            cubicTo(3.4f * u, 6.3f * u, 4.3f * u, 5.4f * u, 5.6f * u, 5.4f * u)
            lineTo(9.4f * u, 5.4f * u)
            lineTo(11.4f * u, 7.6f * u)
            lineTo(18.4f * u, 7.6f * u)
            cubicTo(19.7f * u, 7.6f * u, 20.6f * u, 8.5f * u, 20.6f * u, 9.8f * u)
            lineTo(20.6f * u, 17.2f * u)
            cubicTo(20.6f * u, 18.5f * u, 19.7f * u, 19.4f * u, 18.4f * u, 19.4f * u)
            lineTo(5.6f * u, 19.4f * u)
            cubicTo(4.3f * u, 19.4f * u, 3.4f * u, 18.5f * u, 3.4f * u, 17.2f * u)
            close()
        }
        drawPath(folder, color, style = Stroke(width = 1.65f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(3.6f * u, 9.6f * u), Offset(20.4f * u, 9.6f * u), strokeWidth = 1.35f * u)
    }
}

@Composable
fun ReplyOutlineIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val u = size.width / 24f
        val reply = Path().apply {
            moveTo(10.6f * u, 5.2f * u)
            lineTo(4.2f * u, 11.2f * u)
            lineTo(10.6f * u, 17.2f * u)
            lineTo(10.6f * u, 13.6f * u)
            cubicTo(15.2f * u, 13.6f * u, 18.2f * u, 15.2f * u, 20.0f * u, 18.8f * u)
            cubicTo(19.4f * u, 12.6f * u, 16.2f * u, 8.8f * u, 10.6f * u, 8.8f * u)
            close()
        }
        drawPath(reply, color, style = Stroke(width = 1.65f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
