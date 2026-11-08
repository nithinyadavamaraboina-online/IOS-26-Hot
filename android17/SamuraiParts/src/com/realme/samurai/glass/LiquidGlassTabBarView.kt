package com.realme.samurai.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Authentic iOS 26 Floating Concentric Capsule Tab Bar + Detached Trailing Search Droplet.
 *
 * - Supports both tap selection and continuous horizontal scrub with a lifted active glass lens
 * - Supports animated scroll-shrink compact mode (shrinking into a minimal floating pill just like iOS 26 Safari/Music/Photos)
 */
class LiquidGlassTabBarView(
    context: Context,
    private val tabs: List<String> = listOf("Lens", "Controls", "Dock", "Specs"),
    private val isDarkTheme: Boolean = false
) : View(context) {

    var selectedIndex: Int = 0
        private set

    var isCompactMinimized: Boolean = false
        private set

    var onTabSelected: ((Int) -> Unit)? = null
    var onSearchTapped: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var pillIndex = 0f
    private var pillVel = 0f
    private var lift = 0f
    private var liftVel = 0f
    private var compactFrac = 0f
    private var compactVel = 0f
    private var scrubbing = false
    private var lastFrameNanos = 0L

    private val mainBarRect = RectF()
    private val activePillRect = RectF()
    private val searchOrbRect = RectF()

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun setCompactMode(compact: Boolean) {
        if (isCompactMinimized == compact) return
        isCompactMinimized = compact
        SamuraiGlassPrefs.performCrispTick(this, strong = false)
        startPhysicsLoop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(260f).toInt())
        val h = resolveSize(dp(66f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (searchOrbRect.contains(event.x, event.y)) {
                    SamuraiGlassPrefs.performCrispTick(this, strong = true)
                    setCompactMode(!isCompactMinimized)
                    onSearchTapped?.invoke()
                    return true
                }
                if (isCompactMinimized && mainBarRect.contains(event.x, event.y)) {
                    setCompactMode(false)
                    return true
                }
                scrubbing = true
                updateTabFromTouch(event.x)
                startPhysicsLoop()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    updateTabFromTouch(event.x)
                    startPhysicsLoop()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubbing) {
                    scrubbing = false
                    val snapped = pillIndex.roundToInt().coerceIn(0, tabs.lastIndex)
                    if (snapped != selectedIndex) {
                        selectedIndex = snapped
                        onTabSelected?.invoke(selectedIndex)
                    }
                    SamuraiGlassPrefs.performCrispTick(this, strong = false)
                    startPhysicsLoop()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateTabFromTouch(x: Float) {
        val tabW = max(mainBarRect.width() / tabs.size.coerceAtLeast(1), 1f)
        val raw = ((x - mainBarRect.left) / tabW - 0.5f).coerceIn(0f, tabs.lastIndex.toFloat())
        val nearest = raw.roundToInt().coerceIn(0, tabs.lastIndex)
        if (nearest != selectedIndex) {
            selectedIndex = nearest
            SamuraiGlassPrefs.performCrispTick(this, strong = false)
            onTabSelected?.invoke(selectedIndex)
        }
        pillIndex = raw
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
            val fIdx = 460f * (selectedIndex.toFloat() - pillIndex) - 34f * pillVel
            pillVel += fIdx * dt
            pillIndex += pillVel * dt
        }
        val targetLift = if (scrubbing) 1f else 0f
        val fLift = 520f * (targetLift - lift) - 36f * liftVel
        liftVel += fLift * dt
        lift = (lift + liftVel * dt).coerceIn(0f, 1.15f)

        val targetCompact = if (isCompactMinimized) 1f else 0f
        val fComp = 420f * (targetCompact - compactFrac) - 34f * compactVel
        compactVel += fComp * dt
        compactFrac = (compactFrac + compactVel * dt).coerceIn(0f, 1f)

        val idxDone = !scrubbing && abs(selectedIndex - pillIndex) < 0.002f && abs(pillVel) < 0.01f
        val liftDone = !scrubbing && abs(targetLift - lift) < 0.003f && abs(liftVel) < 0.01f
        val compDone = abs(targetCompact - compactFrac) < 0.002f && abs(compactVel) < 0.01f
        if (idxDone && liftDone && compDone) {
            pillIndex = selectedIndex.toFloat()
            pillVel = 0f
            lift = targetLift
            liftVel = 0f
            compactFrac = targetCompact
            compactVel = 0f
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

        val orbSize = dp(52f)
        val gap = dp(10f)
        val fullBarW = max(width - orbSize - gap - dp(4f), dp(140f))
        val compactBarW = dp(124f)
        val curBarW = fullBarW * (1f - compactFrac) + compactBarW * compactFrac
        val curBarH = dp(56f) * (1f - 0.14f * compactFrac)
        val cy = height * 0.5f

        val totalGroupW = curBarW + gap + orbSize
        val startX = (width - totalGroupW) * 0.5f

        mainBarRect.set(startX, cy - curBarH * 0.5f, startX + curBarW, cy + curBarH * 0.5f)
        val barR = curBarH * 0.5f

        searchOrbRect.set(
            mainBarRect.right + gap,
            cy - orbSize * 0.5f,
            mainBarRect.right + gap + orbSize,
            cy + orbSize * 0.5f
        )

        // Outer detached capsule glass shell
        shellPaint.shader = LinearGradient(
            mainBarRect.left,
            mainBarRect.top,
            mainBarRect.left,
            mainBarRect.bottom,
            if (isDarkTheme) Color.argb(145, 32, 42, 62) else Color.argb(135, 255, 255, 255),
            if (isDarkTheme) Color.argb(175, 18, 25, 40) else Color.argb(105, 232, 242, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(mainBarRect, barR, barR, shellPaint)
        canvas.drawRoundRect(searchOrbRect, orbSize * 0.5f, orbSize * 0.5f, shellPaint)

        shellRimPaint.shader = LinearGradient(
            mainBarRect.left,
            mainBarRect.top,
            mainBarRect.right,
            mainBarRect.bottom,
            intArrayOf(
                Color.argb(if (isDarkTheme) 205 else 245, 255, 255, 255),
                Color.argb(if (isDarkTheme) 16 else 28, 255, 255, 255),
                Color.argb(if (isDarkTheme) 125 else 165, 220, 240, 255)
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(mainBarRect, barR, barR, shellRimPaint)
        shellRimPaint.shader = LinearGradient(
            searchOrbRect.left,
            searchOrbRect.top,
            searchOrbRect.right,
            searchOrbRect.bottom,
            intArrayOf(
                Color.argb(if (isDarkTheme) 210 else 245, 255, 255, 255),
                Color.argb(if (isDarkTheme) 16 else 28, 255, 255, 255),
                Color.argb(if (isDarkTheme) 125 else 165, 220, 240, 255)
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(searchOrbRect, orbSize * 0.5f, orbSize * 0.5f, shellRimPaint)

        // Concentric active tab glass pill inside main capsule
        val pad = dp(5f)
        val innerR = SamuraiSquircle.concentricRadius(barR, pad, dp(16f))
        if (compactFrac < 0.75f) {
            val slotW = (mainBarRect.width() - pad * 2f) / tabs.size.coerceAtLeast(1)
            val pillLeft = mainBarRect.left + pad + pillIndex * slotW - dp(3f) * lift
            val pillRight = pillLeft + slotW + dp(6f) * lift
            val pillTop = mainBarRect.top + pad - dp(2.5f) * lift
            val pillBot = mainBarRect.bottom - pad + dp(2.5f) * lift
            activePillRect.set(pillLeft, pillTop, pillRight, pillBot)

            activePillPaint.shader = LinearGradient(
                activePillRect.left,
                activePillRect.top,
                activePillRect.right,
                activePillRect.bottom,
                if (isDarkTheme) Color.argb(150, 95, 165, 255) else Color.argb(215, 255, 255, 255),
                if (isDarkTheme) Color.argb(95, 58, 118, 225) else Color.argb(165, 225, 240, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(activePillRect, innerR, innerR, activePillPaint)
            shellRimPaint.shader = LinearGradient(
                activePillRect.left,
                activePillRect.top,
                activePillRect.right,
                activePillRect.bottom,
                intArrayOf(
                    Color.argb(if (isDarkTheme) 220 else 255, 255, 255, 255),
                    Color.argb(if (isDarkTheme) 22 else 36, 255, 255, 255),
                    Color.argb(if (isDarkTheme) 140 else 180, 215, 240, 255)
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(activePillRect, innerR, innerR, shellRimPaint)

            textPaint.textSize = sp(13f)
            val textAlpha = ((1f - compactFrac / 0.75f) * 255f).toInt().coerceIn(0, 255)
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) * 0.5f
            for (i in tabs.indices) {
                val tx = mainBarRect.left + pad + (i + 0.5f) * slotW
                val active = abs(i - pillIndex) < 0.55f
                textPaint.color = if (active) {
                    if (isDarkTheme) Color.argb(textAlpha, 255, 255, 255) else Color.argb(textAlpha, 12, 32, 68)
                } else {
                    if (isDarkTheme) Color.argb((textAlpha * 0.72f).toInt(), 210, 224, 245)
                    else Color.argb((textAlpha * 0.68f).toInt(), 36, 54, 82)
                }
                canvas.drawText(tabs[i], tx, baseline, textPaint)
            }
        } else {
            textPaint.textSize = sp(13f)
            textPaint.color = if (isDarkTheme) Color.WHITE else Color.rgb(16, 36, 72)
            val fm = textPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText("• ${tabs[selectedIndex]}", mainBarRect.centerX(), baseline, textPaint)
        }

        // Detached trailing droplet icon ("⌕" / shrink-expand indicator)
        textPaint.textSize = sp(16f)
        textPaint.color = if (isDarkTheme) Color.WHITE else Color.rgb(16, 36, 72)
        val fm = textPaint.fontMetrics
        val orbBaseline = cy - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(if (isCompactMinimized) "⤢" else "⌕", searchOrbRect.centerX(), orbBaseline, textPaint)

        if (keepAnimating) {
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }
}
