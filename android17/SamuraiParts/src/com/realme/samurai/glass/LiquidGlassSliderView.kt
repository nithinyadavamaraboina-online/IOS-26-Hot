package com.realme.samurai.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Authentic iOS 26 Elastic Overshoot Capsule Slider (90Hz spring physics).
 *
 * - Scrubbing past 0% or 100% rubber-bands horizontally (+/- 12dp) while subtly thinning
 *   the track vertically to conserve visual volume, snapping back smoothly on release.
 * - Thumb lifts (1.32x) and morphs into a clear refractive Liquid Glass droplet while scrubbing.
 */
class LiquidGlassSliderView(
    context: Context,
    initialProgress: Float = 0.58f,
    private val isDarkTheme: Boolean = false
) : View(context) {

    var progress: Float = initialProgress.coerceIn(0f, 1f)
        private set

    var onProgressChanged: ((Float) -> Unit)? = null
    var onDropletLensChanged: ((screenCenterX: Float, screenCenterY: Float, halfW: Float, halfH: Float, radius: Float, weight: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var overscroll = 0f     // in [-1..+1] normalized rubber-band units
    private var overscrollVel = 0f
    private var lift = 0f
    private var liftVel = 0f
    private var scrubbing = false
    private var hitEdgeHaptic = false
    private var lastFrameNanos = 0L

    private val trackRect = RectF()
    private val fillRect = RectF()
    private val thumbRect = RectF()
    private val screenLoc = IntArray(2)

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(180f).toInt())
        val h = resolveSize(dp(48f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sidePad = dp(22f)
        val usableW = max(width - sidePad * 2f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                hitEdgeHaptic = false
                updateFromTouchX(event.x, sidePad, usableW)
                SamuraiGlassPrefs.performCrispTick(this, strong = false)
                startPhysicsLoop()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouchX(event.x, sidePad, usableW)
                startPhysicsLoop()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                SamuraiGlassPrefs.performCrispTick(this, strong = false)
                startPhysicsLoop()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouchX(x: Float, sidePad: Float, usableW: Float) {
        val raw = (x - sidePad) / usableW
        val clamped = raw.coerceIn(0f, 1f)
        if (abs(clamped - progress) > 0.001f) {
            progress = clamped
            onProgressChanged?.invoke(progress)
        }
        overscroll = when {
            raw < 0f -> -rubberBand(-raw)
            raw > 1f -> rubberBand(raw - 1f)
            else -> 0f
        }
        if ((raw <= 0f || raw >= 1f) && !hitEdgeHaptic) {
            hitEdgeHaptic = true
            SamuraiGlassPrefs.performCrispTick(this, strong = true)
        } else if (raw > 0.03f && raw < 0.97f) {
            hitEdgeHaptic = false
        }
    }

    private fun rubberBand(excess: Float): Float {
        return (1f - 1f / (1f + excess * 2.8f)).coerceIn(0f, 1f)
    }

    private fun startPhysicsLoop() {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = System.nanoTime()
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    private fun stepSpring(dt: Float): Boolean {
        if (!scrubbing) {
            val fOver = -520f * overscroll - 36f * overscrollVel
            overscrollVel += fOver * dt
            overscroll += overscrollVel * dt
        }
        val targetLift = if (scrubbing) 1f else 0f
        val fLift = 540f * (targetLift - lift) - 38f * liftVel
        liftVel += fLift * dt
        lift = (lift + liftVel * dt).coerceIn(0f, 1.15f)

        val overDone = !scrubbing && abs(overscroll) < 0.002f && abs(overscrollVel) < 0.01f
        val liftDone = !scrubbing && abs(targetLift - lift) < 0.003f && abs(liftVel) < 0.01f
        if (overDone && liftDone) {
            overscroll = 0f
            overscrollVel = 0f
            lift = targetLift
            liftVel = 0f
            return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val keepAnimating = if (lastFrameNanos != 0L) {
            val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
            lastFrameNanos = now
            stepSpring(dt)
        } else {
            false
        }

        val sidePad = dp(22f)
        val stretchPx = overscroll * dp(12f)
        // Conserve visual volume during rubber-band stretch
        val thinFactor = 1f - 0.18f * abs(overscroll)
        val barH = dp(10f) * thinFactor
        val cy = height * 0.5f

        val leftX = sidePad + if (stretchPx < 0f) stretchPx else 0f
        val rightX = (width - sidePad) + if (stretchPx > 0f) stretchPx else 0f
        trackRect.set(leftX, cy - barH * 0.5f, rightX, cy + barH * 0.5f)
        val barR = barH * 0.5f

        trackBgPaint.color = if (isDarkTheme) {
            Color.argb(95, 255, 255, 255)
        } else {
            Color.argb(65, 20, 32, 56)
        }
        canvas.drawRoundRect(trackRect, barR, barR, trackBgPaint)

        val thumbCx = leftX + (rightX - leftX) * progress
        fillRect.set(leftX, trackRect.top, max(thumbCx, leftX + barH), trackRect.bottom)
        fillPaint.shader = LinearGradient(
            fillRect.left,
            fillRect.top,
            fillRect.right,
            fillRect.bottom,
            Color.rgb(56, 152, 255),
            Color.rgb(94, 218, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(fillRect, barR, barR, fillPaint)

        // Pill thumb: morphs from crisp white pill -> 1.32x clear refractive droplet while scrubbing
        val morph = lift.coerceIn(0f, 1f)
        val tw = dp(28f) * (1f + 0.34f * morph)
        val th = dp(20f) * (1f + 0.30f * morph)
        thumbRect.set(thumbCx - tw * 0.5f, cy - th * 0.5f, thumbCx + tw * 0.5f, cy + th * 0.5f)
        val tr = th * 0.5f

        shadowPaint.color = Color.argb((46 + 26 * morph).toInt().coerceIn(0, 110), 0, 12, 28)
        canvas.drawRoundRect(
            thumbRect.left,
            thumbRect.top + dp(2.5f + 2.5f * morph),
            thumbRect.right,
            thumbRect.bottom + dp(2.5f + 2.5f * morph),
            tr,
            tr,
            shadowPaint
        )

        val bodyAlpha = ((1f - 0.80f * morph) * 255f).toInt().coerceIn(36, 255)
        thumbPaint.shader = LinearGradient(
            thumbRect.left,
            thumbRect.top,
            thumbRect.right,
            thumbRect.bottom,
            Color.argb(bodyAlpha, 255, 255, 255),
            Color.argb((bodyAlpha * 0.86f).toInt(), 232, 245, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumbRect, tr, tr, thumbPaint)

        val tlAlpha = (165 + 90 * morph).toInt().coerceIn(0, 255)
        val midAlpha = (14 + 18 * morph).toInt().coerceIn(0, 255)
        val brAlpha = (95 + 75 * morph).toInt().coerceIn(0, 255)
        rimPaint.shader = LinearGradient(
            thumbRect.left,
            thumbRect.top,
            thumbRect.right,
            thumbRect.bottom,
            intArrayOf(
                Color.argb(tlAlpha, 255, 255, 255),
                Color.argb(midAlpha, 255, 255, 255),
                Color.argb(brAlpha, 210, 242, 255)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumbRect, tr, tr, rimPaint)

        onDropletLensChanged?.let { cb ->
            getLocationInWindow(screenLoc)
            cb(screenLoc[0] + thumbCx, screenLoc[1] + cy, tw * 0.5f, th * 0.5f, tr, morph)
        }

        if (keepAnimating) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }
}
