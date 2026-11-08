package com.realme.samurai.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Authentic iOS 26 Liquid Droplet Switch (90Hz velocity-preserving Hooke spring).
 *
 * - Resting state: vibrant iOS #34C759 stadium capsule when ON, translucent frosted capsule when OFF,
 *   with a wider iOS 26 pill-shaped white thumb.
 * - Touch / Drag state: thumb lifts (1.30x scale) and morphs smoothly from opaque white into a
 *   clear refractive Liquid Glass droplet (showing the track rim & specular refraction underneath)
 *   with crisp AW8697 haptic ticks on press and state-threshold crossing.
 */
class LiquidGlassSwitchView(
    context: Context,
    initialChecked: Boolean = true,
    private val isDarkTheme: Boolean = false
) : View(context) {

    var isChecked: Boolean = initialChecked
        private set

    var onCheckedChanged: ((Boolean) -> Unit)? = null
    var onDropletLensChanged: ((screenCenterX: Float, screenCenterY: Float, halfW: Float, halfH: Float, radius: Float, weight: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // Track is 58dp x 32dp inside a padded 72dp x 46dp canvas so the 1.30x lifted droplet never clips!
    private val padX = dp(7f)
    private val padY = dp(7f)
    private val trackW = dp(58f)
    private val trackH = dp(32f)
    private val thumbRestW = dp(34f)
    private val thumbRestH = dp(26f)

    // Spring state: pos in [0..1], lift in [0..1]
    private var pos = if (initialChecked) 1f else 0f
    private var posVel = 0f
    private var lift = 0f
    private var liftVel = 0f

    private var dragging = false
    private var movedSignificantly = false
    private var downX = 0f
    private var downPos = 0f
    private var lastHapticSide = initialChecked
    private var velocityTracker: VelocityTracker? = null
    private var lastFrameNanos = 0L

    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val innerTrackClipRect = RectF()
    private val screenLoc = IntArray(2)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
    }

    fun setCheckedAnimated(checked: Boolean, notify: Boolean = false) {
        if (isChecked == checked) return
        isChecked = checked
        lastHapticSide = checked
        if (notify) onCheckedChanged?.invoke(checked)
        startPhysicsLoop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredW = (trackW + padX * 2f).toInt()
        val desiredH = (trackH + padY * 2f).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                dragging = true
                movedSignificantly = false
                downX = event.x
                downPos = pos
                lastHapticSide = pos >= 0.5f
                SamuraiGlassPrefs.performCrispTick(this, strong = false)
                startPhysicsLoop()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                if (abs(dx) > dp(4f)) {
                    movedSignificantly = true
                }
                val travel = max(trackW - thumbRestW - dp(6f), 1f)
                // Subtle rubber-band overshoot past [0, 1]
                val raw = downPos + dx / travel
                pos = when {
                    raw < 0f -> -0.08f * (1f - 1f / (1f - raw * 3f))
                    raw > 1f -> 1f + 0.08f * (1f - 1f / (1f + (raw - 1f) * 3f))
                    else -> raw
                }
                val curSide = pos >= 0.5f
                if (curSide != lastHapticSide) {
                    lastHapticSide = curSide
                    SamuraiGlassPrefs.performCrispTick(this, strong = true)
                }
                startPhysicsLoop()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val travel = max(trackW - thumbRestW - dp(6f), 1f)
                val normVel = (vx / travel).coerceIn(-12f, 12f)
                val nextChecked = if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isChecked
                } else if (!movedSignificantly) {
                    !isChecked
                } else if (abs(normVel) > 2.2f) {
                    normVel > 0f
                } else {
                    pos >= 0.5f
                }

                dragging = false
                posVel = normVel
                val changed = (nextChecked != isChecked)
                isChecked = nextChecked
                if (changed || !movedSignificantly) {
                    SamuraiGlassPrefs.performCrispTick(this, strong = true)
                }
                if (changed) {
                    onCheckedChanged?.invoke(isChecked)
                }
                startPhysicsLoop()
                return true
            }
        }
        return super.onTouchEvent(event)
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
        val targetPos = if (isChecked) 1f else 0f
        val targetLift = if (dragging) 1f else 0f

        if (!dragging) {
            // Critically damped iOS 26 spring (stiffness=480, damping=36)
            val forcePos = 480f * (targetPos - pos) - 36f * posVel
            posVel += forcePos * dt
            pos += posVel * dt
        }

        val forceLift = 540f * (targetLift - lift) - 38f * liftVel
        liftVel += forceLift * dt
        lift = (lift + liftVel * dt).coerceIn(0f, 1.18f)

        val posSettled = !dragging && abs(targetPos - pos) < 0.002f && abs(posVel) < 0.01f
        val liftSettled = !dragging && abs(targetLift - lift) < 0.003f && abs(liftVel) < 0.01f
        if (posSettled && liftSettled) {
            pos = targetPos
            posVel = 0f
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

        val tl = (width - trackW) * 0.5f
        val tt = (height - trackH) * 0.5f
        trackRect.set(tl, tt, tl + trackW, tt + trackH)
        val trackRadius = trackH * 0.5f

        // Interpolate track color between translucent glass OFF and vibrant iOS #34C759 green ON
        val pClamped = pos.coerceIn(0f, 1f)
        val offCol = if (isDarkTheme) Color.argb(120, 95, 104, 122) else Color.argb(72, 120, 128, 142)
        val onCol = Color.rgb(52, 199, 89) // #34C759
        trackPaint.color = blendArgb(offCol, onCol, pClamped)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        trackRimPaint.shader = LinearGradient(
            trackRect.left,
            trackRect.top,
            trackRect.right,
            trackRect.bottom,
            intArrayOf(
                Color.argb(if (isDarkTheme) 190 else 225, 255, 255, 255),
                Color.argb(if (isDarkTheme) 18 else 26, 255, 255, 255),
                Color.argb(if (isDarkTheme) 115 else 145, 225, 245, 255)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackRimPaint)

        // Thumb geometry: morphs from 34x26 white pill -> 1.30x lifted clear Liquid Glass droplet!
        val scaleX = 1f + 0.30f * lift
        val scaleY = 1f + 0.28f * lift
        val curThumbW = thumbRestW * scaleX
        val curThumbH = thumbRestH * scaleY

        val inset = dp(3f)
        val minCx = tl + inset + thumbRestW * 0.5f
        val maxCx = tl + trackW - inset - thumbRestW * 0.5f
        val thumbCx = minCx + (maxCx - minCx) * pos
        val thumbCy = tt + trackH * 0.5f - dp(1.5f) * lift

        thumbRect.set(
            thumbCx - curThumbW * 0.5f,
            thumbCy - curThumbH * 0.5f,
            thumbCx + curThumbW * 0.5f,
            thumbCy + curThumbH * 0.5f
        )
        val thumbR = curThumbH * 0.5f

        // Soft contact + lifted droplet caustic shadow
        shadowPaint.color = Color.argb((48 + 30 * lift).toInt().coerceIn(0, 120), 0, 18, 8)
        canvas.drawRoundRect(
            thumbRect.left,
            thumbRect.top + dp(2f + 3f * lift),
            thumbRect.right,
            thumbRect.bottom + dp(2f + 3f * lift),
            thumbR,
            thumbR,
            shadowPaint
        )

        // Solid white -> Clear refractive Liquid Glass lens morph!
        val glassMorph = lift.coerceIn(0f, 1f)
        val whiteAlpha = ((1f - 0.80f * glassMorph) * 255f).toInt().coerceIn(38, 255)
        thumbBodyPaint.shader = LinearGradient(
            thumbRect.left,
            thumbRect.top,
            thumbRect.right,
            thumbRect.bottom,
            Color.argb(whiteAlpha, 255, 255, 255),
            Color.argb((whiteAlpha * 0.88f).toInt(), 240, 248, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumbRect, thumbR, thumbR, thumbBodyPaint)

        // When lifted into a clear droplet, render refracted track edge highlight inside the lens
        // AND report exact lens bounds to any parent AGSL scene container!
        if (glassMorph > 0.04f) {
            innerTrackClipRect.set(
                thumbRect.left + dp(2f),
                thumbRect.top + dp(2f),
                thumbRect.right - dp(2f),
                thumbRect.bottom - dp(2f)
            )
            thumbRimPaint.shader = LinearGradient(
                thumbRect.left,
                thumbRect.top,
                thumbRect.right,
                thumbRect.bottom,
                Color.argb((235 * glassMorph).toInt(), 255, 255, 255),
                Color.argb((95 * glassMorph).toInt(), 195, 255, 215),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(thumbRect, thumbR, thumbR, thumbRimPaint)
        }

        onDropletLensChanged?.let { cb ->
            getLocationInWindow(screenLoc)
            cb(
                screenLoc[0] + thumbCx,
                screenLoc[1] + thumbCy,
                curThumbW * 0.5f,
                curThumbH * 0.5f,
                thumbR,
                glassMorph
            )
        }

        if (keepAnimating) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    private fun blendArgb(c0: Int, c1: Int, t: Float): Int {
        val u = 1f - t
        val a = (Color.alpha(c0) * u + Color.alpha(c1) * t).toInt()
        val r = (Color.red(c0) * u + Color.red(c1) * t).toInt()
        val g = (Color.green(c0) * u + Color.green(c1) * t).toInt()
        val b = (Color.blue(c0) * u + Color.blue(c1) * t).toInt()
        return Color.argb(a, r, g, b)
    }
}
