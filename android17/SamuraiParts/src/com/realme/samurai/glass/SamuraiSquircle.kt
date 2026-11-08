package com.realme.samurai.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

/**
 * Exact G2-continuous superellipse (UIKit kCACornerCurveContinuous, n ~ 4.6)
 * Path generator, concentric corner helper, OutlineProvider, and Drawable.
 */
object SamuraiSquircle {
    // UIKit continuous-corner Bezier shoulder ratios for zero curvature jump (G2 continuity)
    private const val P = 1.52866483f
    private const val A = 1.08849323f
    private const val B = 0.86840689f
    private const val C = 0.63149399f
    private const val D = 0.07491139f
    private const val E = 0.37282392f
    private const val F = 0.16906013f

    /**
     * Concentric inner corner radius matching iOS 26 nested Liquid Glass containers:
     * R_inner = max(R_outer - padding, minRadius).
     */
    fun concentricRadius(outerRadius: Float, padding: Float, minRadius: Float = 6f): Float {
        return (outerRadius - padding).coerceAtLeast(minRadius)
    }

    /**
     * Populates [out] with a closed G2-continuous superellipse rect [l, t, r, b].
     * Automatically falls back to a true stadium/circle arc when r >= min(w, h)/2.
     */
    fun buildPath(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        cornerRadius: Float,
        out: Path = Path()
    ): Path {
        out.reset()
        val w = r - l
        val h = b - t
        if (w <= 0f || h <= 0f) return out

        val halfMin = 0.5f * min(w, h)
        val cr = cornerRadius.coerceIn(0f, halfMin)
        if (cr <= 0.5f) {
            out.addRect(l, t, r, b, Path.Direction.CW)
            return out
        }

        // Stadium / capsule case: when radius is >= 96% of half-short-side, a true round-rect
        // capsule avoids flat pinch points on 2-axis semicircles while staying C2 smooth.
        if (cr >= halfMin * 0.96f) {
            out.addRoundRect(l, t, r, b, halfMin, halfMin, Path.Direction.CW)
            return out
        }

        val px = min(w * 0.5f, P * cr)
        val py = min(h * 0.5f, P * cr)
        val sx = px / (P * cr)
        val sy = py / (P * cr)

        val ax = A * cr * sx
        val bx = B * cr * sx
        val cx = C * cr * sx
        val dx = D * cr * sy
        val ex = E * cr * sx
        val fx = F * cr * sy
        val gx = F * cr * sx
        val hx = E * cr * sy
        val kx = D * cr * sx
        val mx = C * cr * sy
        val by = B * cr * sy
        val ay = A * cr * sy

        // Start on top straight segment
        out.moveTo(l + px, t)
        out.lineTo(r - px, t)

        // Top-right continuous corner
        out.cubicTo(r - ax, t, r - bx, t, r - cx, t + dx)
        out.cubicTo(r - ex, t + fx, r - gx, t + hx, r - kx, t + mx)
        out.cubicTo(r, t + by, r, t + ay, r, t + py)

        // Right edge
        out.lineTo(r, b - py)

        // Bottom-right continuous corner
        out.cubicTo(r, b - ay, r, b - by, r - kx, b - mx)
        out.cubicTo(r - gx, b - hx, r - ex, b - fx, r - cx, b - dx)
        out.cubicTo(r - bx, b, r - ax, b, r - px, b)

        // Bottom edge
        out.lineTo(l + px, b)

        // Bottom-left continuous corner
        out.cubicTo(l + ax, b, l + bx, b, l + cx, b - dx)
        out.cubicTo(l + ex, b - fx, l + gx, b - hx, l + kx, b - mx)
        out.cubicTo(l, b - by, l, b - ay, l, b - py)

        // Left edge
        out.lineTo(l, t + py)

        // Top-left continuous corner
        out.cubicTo(l, t + ay, l, t + by, l + kx, t + mx)
        out.cubicTo(l + gx, t + hx, l + ex, t + fx, l + cx, t + dx)
        out.cubicTo(l + bx, t, l + ax, t, l + px, t)

        out.close()
        return out
    }

    fun outlineProvider(radiusPx: Float): ViewOutlineProvider {
        return object : ViewOutlineProvider() {
            private val path = Path()
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                if (w <= 0f || h <= 0f) return
                buildPath(0f, 0f, w, h, radiusPx, path)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
        }
    }

    class SquircleDrawable(
        private var fillColor: Int,
        private val radiusPx: Float,
        private val strokeColor: Int = Color.TRANSPARENT,
        private val strokeWidthPx: Float = 0f
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = strokeColor
            strokeWidth = strokeWidthPx
        }
        private val fillPath = Path()
        private val strokePath = Path()

        fun setFillColor(color: Int) {
            if (fillColor != color) {
                fillColor = color
                fillPaint.color = color
                invalidateSelf()
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val l = bounds.left.toFloat()
            val t = bounds.top.toFloat()
            val r = bounds.right.toFloat()
            val b = bounds.bottom.toFloat()
            buildPath(l, t, r, b, radiusPx, fillPath)
            if (strokeColor != Color.TRANSPARENT && strokeWidthPx > 0f) {
                val inset = strokeWidthPx * 0.5f
                buildPath(l + inset, t + inset, r - inset, b - inset, (radiusPx - inset).coerceAtLeast(1f), strokePath)
            }
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(fillPath, fillPaint)
            if (strokeColor != Color.TRANSPARENT && strokeWidthPx > 0f) {
                canvas.drawPath(strokePath, strokePaint)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
