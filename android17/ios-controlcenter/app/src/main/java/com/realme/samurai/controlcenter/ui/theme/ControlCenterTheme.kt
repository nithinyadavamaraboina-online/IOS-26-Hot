package com.realme.samurai.controlcenter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realme.samurai.controlcenter.R
import kotlin.math.min

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object CcPalette {
    val ElectricBlue = Color(0xFF0051FF)
    val CellularGreen = Color(0xFF1CB848)
    val SilentCoral = Color(0xFFFF3E33)
    val AirplaneOrange = Color(0xFFFF9F0A)
    val FocusPurple = Color(0xFFA044E8)
    val SunGold = Color(0xFFFFBE00)
    val VolumeBlue = Color(0xFF28A4F4)
    val BulbYellow = Color(0xFFFFC800)
    val ActiveWhite = Color(0xFFF7F8FA)
    val ActiveDarkText = Color(0xFF14161A)
    val ActiveSubText = Color(0xFF6C7078)
    val InactiveSubText = Color(0xB8FFFFFF)
}

object CcType {
    val StatusCarrier = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        letterSpacing = (-0.2).sp,
    )
    val MediaTitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        letterSpacing = (-0.2).sp,
    )
    val MediaSubtitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        letterSpacing = (-0.1).sp,
    )
    val PillCategory = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = (-0.1).sp,
    )
    val PillTitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 14.5.sp,
        letterSpacing = (-0.25).sp,
    )
    val PillState = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = (-0.1).sp,
    )
    val FocusLabel = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = (-0.15).sp,
    )
    val SheetTitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = (-0.35).sp,
    )
    val SheetRowTitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = (-0.2).sp,
    )
    val SheetRowSub = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        letterSpacing = (-0.1).sp,
    )
}

/**
 * Apple UIBezierPath continuous-corner squircle (G2 clothoid-cubic approximation).
 * Automatically forms a true semicircular capsule pill when cornerRadius >= min(w,h)/2 * 0.96f.
 */
class ContinuousSquircleShape(val cornerRadius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path())

        val rPx = with(density) { cornerRadius.toPx() }
        val maxR = min(w, h) / 2f
        if (rPx >= maxR * 0.96f) {
            return Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = w,
                    bottom = h,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(maxR, maxR),
                )
            )
        }

        val r = min(rPx, maxR / 1.528665f)
        val p = min(maxR, r * 1.528665f)

        val a = r * 1.088493f
        val b = r * 0.868407f
        val c = r * 0.631494f
        val d = r * 0.074911f
        val e = r * 0.169060f
        val f = r * 0.372824f

        val path = Path().apply {
            moveTo(p, 0f)
            lineTo(w - p, 0f)
            cubicTo(w - a, 0f, w - b, 0f, w - c, d)
            cubicTo(w - f, e, w - e, f, w - d, c)
            cubicTo(w, b, w, a, w, p)

            lineTo(w, h - p)
            cubicTo(w, h - a, w, h - b, w - d, h - c)
            cubicTo(w - e, h - f, w - f, h - e, w - c, h - d)
            cubicTo(w - b, h, w - a, h, w - p, h)

            lineTo(p, h)
            cubicTo(a, h, b, h, c, h - d)
            cubicTo(f, h - e, e, h - f, d, h - c)
            cubicTo(0f, h - b, 0f, h - a, 0f, h - p)

            lineTo(0f, p)
            cubicTo(0f, a, 0f, b, d, c)
            cubicTo(e, f, f, e, c, d)
            cubicTo(b, 0f, a, 0f, p, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

val CapsuleShape: Shape = ContinuousSquircleShape(999.dp)

@Composable
fun IosControlCenterTheme(content: @Composable () -> Unit) {
    content()
}
