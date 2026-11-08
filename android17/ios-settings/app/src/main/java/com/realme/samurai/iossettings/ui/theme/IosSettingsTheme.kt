package com.realme.samurai.iossettings.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.realme.samurai.iossettings.R
import com.realme.samurai.iossettings.ui.glass.LocalSystemGlassConfig
import com.realme.samurai.iossettings.ui.glass.rememberSystemGlassConfig
import kotlin.math.min

val IosBlueLight = Color(0xFF007AFF)
val IosBlueDark = Color(0xFF0A84FF)
val IosGreenLight = Color(0xFF34C759)
val IosGreenDark = Color(0xFF30D158)
val IosOrange = Color(0xFFFF9500)
val IosRedLight = Color(0xFFFF3B30)
val IosRedDark = Color(0xFFFF453A)
val IosPink = Color(0xFFFF2D55)
val IosIndigo = Color(0xFF5856D6)
val IosPurple = Color(0xFFAF52DE)
val IosCyan = Color(0xFF32ADE6)
val IosTeal = Color(0xFF30B0C7)
val IosGray = Color(0xFF8E8E93)

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Exact Apple UIKit Dynamic Type scale (Default "Large" stop) with SF Pro letter-spacing.
 */
object IosType {
    val largeTitle = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.4).sp,
    )
    val title2 = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.26).sp,
    )
    val title3 = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.38).sp,
    )
    val headline = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.43).sp,
    )
    val body = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.43).sp,
    )
    val sectionHeader = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.23).sp,
    )
    val subheadline = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.23).sp,
    )
    val footnote = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.08).sp,
    )
    val caption1 = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    )
}

private val IosMaterialTypography = Typography(
    displaySmall = IosType.largeTitle,
    headlineLarge = IosType.largeTitle,
    headlineMedium = IosType.title2,
    titleLarge = IosType.headline,
    bodyLarge = IosType.body,
    bodyMedium = IosType.subheadline,
    bodySmall = IosType.footnote,
)

@Immutable
data class IosColors(
    val isDark: Boolean,
    val groupedBackground: Color,
    val groupedCell: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val accent: Color,
    val destructive: Color,
    val divider: Color,
    val switchOn: Color,
    val switchOff: Color,
    val fieldBackground: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassShadow: Color,
    val glassTinted: Boolean,
)

fun lightIosColors(tinted: Boolean = false) = IosColors(
    isDark = false,
    groupedBackground = Color(0xFFF2F2F7),
    groupedCell = Color(0xFFFFFFFF),
    primaryText = Color(0xFF000000),
    secondaryText = Color(0x993C3C43),
    tertiaryText = Color(0x4D3C3C43),
    accent = IosBlueLight,
    destructive = IosRedLight,
    divider = Color(0x363C3C43),
    switchOn = IosGreenLight,
    switchOff = Color(0xFFC6C6CC),
    fieldBackground = Color(0x1F767680),
    glassFill = Color(0xD4F8F8FA),
    glassFillStrong = Color(0xF7FFFFFF),
    glassShadow = Color(0x1E000000),
    glassTinted = tinted,
)

fun darkIosColors(tinted: Boolean = false) = IosColors(
    isDark = true,
    groupedBackground = Color(0xFF000000),
    groupedCell = Color(0xFF1C1C1E),
    primaryText = Color(0xFFFFFFFF),
    secondaryText = Color(0x99EBEBF5),
    tertiaryText = Color(0x4DEBEBF5),
    accent = IosBlueDark,
    destructive = IosRedDark,
    divider = Color(0x66545458),
    switchOn = IosGreenDark,
    switchOff = Color(0xFF39393D),
    fieldBackground = Color(0x3D767680),
    glassFill = Color(0xC228282C),
    glassFillStrong = Color(0xF52C2C2E),
    glassShadow = Color(0x59000000),
    glassTinted = tinted,
)

val LocalIosColors = staticCompositionLocalOf { lightIosColors() }

object IosTheme {
    val colors: IosColors
        @Composable
        @ReadOnlyComposable
        get() = LocalIosColors.current
}

/**
 * Exact Apple UIKit `UIBezierPath(roundedRect:cornerRadius:)` / `CALayer.cornerCurve = .continuous`
 * G2-continuous clothoid-Bezier corner shape.
 *
 * - When `cornerRadius >= min(w, h) / 2` (e.g., a 1-row `52dp` card with `26dp` corner radius,
 *   like `"Ask to Join Networks"` in `wave5_03e_wifi.png`), produces an exact semicircular pill.
 * - On multi-row cards (`26dp`) and Settings icon tiles (`7dp` on `29dp`, `14.5dp` on `60dp`),
 *   uses Apple's 3-cubic-segment continuous corner (`p = 1.528665 * r`) with smooth blend clamping.
 */
class ContinuousSquircleShape(
    private val cornerRadius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect.Zero)
        val maxR = min(w, h) * 0.5f
        val r = with(density) { cornerRadius.toPx() }.coerceIn(0f, maxR)
        if (r <= 0.5f) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        }
        if (r >= maxR * 0.96f) {
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

        // Blend factor xi in [0, 1]: 1 = full UIKit continuous corner, 0 = circular arc limit at r == maxR
        val maxReach = 1.5286648f * r
        val p = min(maxReach, maxR)
        val xi = if (maxReach > r) ((p - r) / (maxReach - r)).coerceIn(0f, 1f) else 0f

        // Interpolate between circular 2-segment / kappa=0.55228475 and UIKit 3-cubic continuous profile
        val a = (1f + 0.0884932f * xi) * r
        val b = (1f - 0.131593f * xi) * r
        val c = (1f - 0.368506f * xi) * r
        val d = (0.0749114f * xi) * r
        val kCirc = 0.55228475f * r
        // When xi == 0, collapse to exact 1-cubic quarter circle; when xi > 0, use full 3-cubic UIKit curve
        val path = Path()
        if (xi <= 0.02f) {
            return Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = w,
                    bottom = h,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            )
        }

        val e = (0.3728238f * xi + (1f - kCirc / r) * (1f - xi)) * r
        val f = (0.1690596f * xi + (1f - kCirc / r) * (1f - xi)) * r

        path.moveTo(p, 0f)
        // Top edge -> Top-Right corner
        path.lineTo(w - p, 0f)
        path.cubicTo(w - a, 0f, w - b, 0f, w - c, d)
        path.cubicTo(w - e, f, w - f, e, w - d, c)
        path.cubicTo(w, b, w, a, w, p)

        // Right edge -> Bottom-Right corner
        path.lineTo(w, h - p)
        path.cubicTo(w, h - a, w, h - b, w - d, h - c)
        path.cubicTo(w - f, h - e, w - e, h - f, w - c, h - d)
        path.cubicTo(w - b, h, w - a, h, w - p, h)

        // Bottom edge -> Bottom-Left corner
        path.lineTo(p, h)
        path.cubicTo(a, h, b, h, c, h - d)
        path.cubicTo(e, h - f, f, h - e, d, h - c)
        path.cubicTo(0f, h - b, 0f, h - a, 0f, h - p)

        // Left edge -> Top-Left corner
        path.lineTo(0f, p)
        path.cubicTo(0f, a, 0f, b, d, c)
        path.cubicTo(f, e, e, f, c, d)
        path.cubicTo(b, 0f, a, 0f, p, 0f)

        path.close()
        return Outline.Generic(path)
    }
}

val GroupCardShape = ContinuousSquircleShape(26.dp)
val IconTileShape = ContinuousSquircleShape(7.dp)
val HeroIconTileShape = ContinuousSquircleShape(14.5.dp)

@Composable
fun IosSettingsTheme(
    darkTheme: Boolean? = null,
    tintedOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val sysGlass = rememberSystemGlassConfig()
    val systemDark = isSystemInDarkTheme()
    val resolvedDark = darkTheme ?: systemDark
    val resolvedTinted = tintedOverride ?: (sysGlass.isFrostedTinted || sysGlass.isSolidMatte)
    val iosColors = remember(resolvedDark, resolvedTinted) {
        if (resolvedDark) darkIosColors(resolvedTinted) else lightIosColors(resolvedTinted)
    }
    val materialScheme = remember(resolvedDark) {
        if (resolvedDark) {
            darkColorScheme(
                primary = IosBlueDark,
                background = Color.Black,
                surface = Color(0xFF1C1C1E),
                onBackground = Color.White,
                onSurface = Color.White,
                error = IosRedDark,
            )
        } else {
            lightColorScheme(
                primary = IosBlueLight,
                background = Color(0xFFF2F2F7),
                surface = Color.White,
                onBackground = Color.Black,
                onSurface = Color.Black,
                error = IosRedLight,
            )
        }
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !resolvedDark
                isAppearanceLightNavigationBars = !resolvedDark
            }
        }
    }

    CompositionLocalProvider(
        LocalIosColors provides iosColors,
        LocalSystemGlassConfig provides sysGlass,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = IosMaterialTypography,
            content = content,
        )
    }
}
