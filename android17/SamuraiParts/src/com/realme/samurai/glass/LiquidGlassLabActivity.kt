package com.realme.samurai.glass

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.realme.samurai.parts.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Interactive 90Hz iOS 26 Liquid Glass Studio inside SamuraiParts.
 *
 * Demonstrates and persists system-wide iOS 26 Liquid Glass preferences:
 *  - Live AGSL Snell's Law superellipse lens refraction over vivid multi-color silk ribbons
 *  - Clear (iOS 26.0) vs. Tinted (iOS 26.1) optical mode toggle
 *  - Interactive Liquid Droplet Switch, Elastic Overshoot Slider & Concentric Capsule Tab Bar
 *  - Clean-room G2 superellipse (n=4.6) Phone, Messages & Settings icon showcase
 */
class LiquidGlassLabActivity : Activity() {

    private var isDark = false
    private var isTinted = false
    private var refractionScale = 1.00f

    private var stageContainer: FrameLayout? = null
    private var opticalBackdrop: OpticalRibbonBackdropView? = null
    private var lensLabelOverlay: LensReadoutOverlayView? = null
    private var clearChip: TextView? = null
    private var tintedChip: TextView? = null
    private var sliderValueView: TextView? = null
    private var searchPillSwitch: LiquidGlassSwitchView? = null
    private var gesturePillSwitch: LiquidGlassSwitchView? = null
    private var styleModeButtonsFn: (() -> Unit)? = null

    private val glassShader = SamuraiLiquidGlassShader()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDark = nightFlags == Configuration.UI_MODE_NIGHT_YES
        isTinted = SamuraiGlassPrefs.isTinted(this)

        window.setDecorFitsSystemWindows(false)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        val curTinted = SamuraiGlassPrefs.isTinted(this)
        if (curTinted != isTinted) {
            isTinted = curTinted
            styleModeButtonsFn?.invoke()
            opticalBackdrop?.syncShader()
            lensLabelOverlay?.invalidate()
        }
        searchPillSwitch?.setCheckedAnimated(SamuraiGlassPrefs.isSearchPillEnabled(this), notify = false)
        gesturePillSwitch?.setCheckedAnimated(SamuraiGlassPrefs.isGesturePillEnabled(this), notify = false)
    }

    override fun onDestroy() {
        opticalBackdrop?.let { glassShader.detach(it) }
        super.onDestroy()
    }

    private fun pillDrawable(fill: Int, radiusDp: Float, strokeCol: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fill)
            if (strokeCol != Color.TRANSPARENT) {
                setStroke(dp(1).coerceAtLeast(1), strokeCol)
            }
        }
    }

    private fun buildUi(): View {
        val bgCol = if (isDark) Color.rgb(8, 13, 24) else Color.rgb(240, 245, 252)
        val cardCol = if (isDark) Color.rgb(16, 23, 38) else Color.rgb(255, 255, 255)
        val textPri = if (isDark) Color.rgb(245, 249, 255) else Color.rgb(14, 24, 42)
        val textSec = if (isDark) Color.rgb(168, 184, 210) else Color.rgb(82, 98, 124)
        val accent = if (isDark) Color.rgb(98, 178, 255) else Color.rgb(22, 112, 236)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgCol)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(48), dp(18), dp(36))
        }

        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(dp(18), bars.top + dp(14), dp(18), bars.bottom + dp(28))
            insets
        }

        // Back button
        val backTv = TextView(this).apply {
            text = "← Realme X2 Pro hardware"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(4), dp(12), dp(10))
            setOnClickListener { finish() }
        }
        root.addView(backTv)

        val titleTv = TextView(this).apply {
            text = "iOS 26 Liquid Glass"
            setTextColor(textPri)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(titleTv)

        val subTv = TextView(this).apply {
            text = "90Hz AGSL Snell bevel refraction • 34° Schlick Fresnel catchlights • G2 superellipse (n=4.6)"
            setTextColor(textSec)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(14))
        }
        root.addView(subTv)

        // Clear vs. Tinted Segmented Bar
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(
                if (isDark) Color.rgb(22, 31, 50) else Color.rgb(226, 234, 246),
                22f
            )
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        fun styleModeButtons() {
            val activeBg = accent
            val activeFg = Color.WHITE
            val idleFg = textPri
            clearChip?.background = pillDrawable(if (!isTinted) activeBg else Color.TRANSPARENT, 18f)
            clearChip?.setTextColor(if (!isTinted) activeFg else idleFg)
            tintedChip?.background = pillDrawable(if (isTinted) activeBg else Color.TRANSPARENT, 18f)
            tintedChip?.setTextColor(if (isTinted) activeFg else idleFg)
        }
        styleModeButtonsFn = ::styleModeButtons

        clearChip = TextView(this).apply {
            text = "Clear (iOS 26.0 Optical)"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (isTinted) {
                    isTinted = false
                    SamuraiGlassPrefs.setMode(this@LiquidGlassLabActivity, SamuraiGlassPrefs.MODE_CLEAR)
                    SamuraiGlassPrefs.performCrispTick(this, strong = true)
                    styleModeButtons()
                    opticalBackdrop?.syncShader()
                    lensLabelOverlay?.invalidate()
                }
            }
        }
        tintedChip = TextView(this).apply {
            text = "Tinted (iOS 26.1 Contrast)"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                if (!isTinted) {
                    isTinted = true
                    SamuraiGlassPrefs.setMode(this@LiquidGlassLabActivity, SamuraiGlassPrefs.MODE_TINTED)
                    SamuraiGlassPrefs.performCrispTick(this, strong = true)
                    styleModeButtons()
                    opticalBackdrop?.syncShader()
                    lensLabelOverlay?.invalidate()
                }
            }
        }
        modeRow.addView(clearChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(tintedChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        styleModeButtons()
        root.addView(
            modeRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        )

        // =========================================================================
        // 1. LIVE INTERACTIVE 90HZ AGSL OPTICAL REFRACTION STAGE
        // =========================================================================
        val stageFrame = object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, w, h, dpF(28f))
                    }
                }
            }
        }
        stageContainer = stageFrame

        val backdrop = OpticalRibbonBackdropView(this)
        val readout = LensReadoutOverlayView(this)
        opticalBackdrop = backdrop
        lensLabelOverlay = readout

        stageFrame.addView(
            backdrop,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290))
        )
        stageFrame.addView(
            readout,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290))
        )
        root.addView(
            stageFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290)).apply {
                bottomMargin = dp(16)
            }
        )

        // =========================================================================
        // 2. INTERACTIVE LIQUID DROPLET CONTROLS CARD
        // =========================================================================
        val controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillDrawable(
                cardCol,
                26f,
                if (isDark) Color.argb(38, 255, 255, 255) else Color.argb(24, 0, 0, 0)
            )
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }

        fun makeDropletSwitchRow(
            title: String,
            subtitle: String,
            initialChecked: Boolean,
            onToggle: (Boolean) -> Unit
        ): Pair<LinearLayout, LiquidGlassSwitchView> {
            val swRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val swTextCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(12)
                }
            }
            swTextCol.addView(TextView(this).apply {
                text = title
                setTextColor(textPri)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            swTextCol.addView(TextView(this).apply {
                text = subtitle
                setTextColor(textSec)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(2), 0, 0)
            })
            val glassSwitch = LiquidGlassSwitchView(
                this,
                initialChecked = initialChecked,
                isDarkTheme = isDark
            ).apply {
                onCheckedChanged = { isChecked -> onToggle(isChecked) }
            }
            swTextCol.setOnClickListener {
                SamuraiGlassPrefs.performCrispTick(glassSwitch, strong = true)
                glassSwitch.setCheckedAnimated(!glassSwitch.isChecked, notify = true)
            }
            swRow.addView(swTextCol)
            swRow.addView(glassSwitch)
            return swRow to glassSwitch
        }

        val (searchPillRow, spSwitch) = makeDropletSwitchRow(
            title = "Show Search Pill",
            subtitle = "Press & drag droplet • Floating 'Q Search' pill above Home Screen dock",
            initialChecked = SamuraiGlassPrefs.isSearchPillEnabled(this)
        ) { isChecked ->
            SamuraiGlassPrefs.setSearchPillEnabled(this, isChecked)
        }
        searchPillSwitch = spSwitch
        controlsCard.addView(searchPillRow)

        controlsCard.addView(View(this).apply {
            setBackgroundColor(if (isDark) Color.argb(32, 255, 255, 255) else Color.argb(20, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
        })

        val (gesturePillRow, gpSwitch) = makeDropletSwitchRow(
            title = "Show Bottom Navigation Bar",
            subtitle = "Display bottom gesture indicator pill across apps and Home Screen (hidden by default)",
            initialChecked = SamuraiGlassPrefs.isGesturePillEnabled(this)
        ) { isChecked ->
            SamuraiGlassPrefs.setGesturePillEnabled(this, isChecked)
        }
        gesturePillSwitch = gpSwitch
        controlsCard.addView(gesturePillRow)

        // Refraction Scale Elastic Slider Row
        val slHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }
        slHeader.addView(
            TextView(this).apply {
                text = "Snell Bevel Refraction Power"
                setTextColor(textPri)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valBadge = TextView(this).apply {
            text = String.format(Locale.US, "%.2f×", refractionScale)
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        sliderValueView = valBadge
        slHeader.addView(valBadge)
        controlsCard.addView(slHeader)

        val glassSlider = LiquidGlassSliderView(
            this,
            initialProgress = ((refractionScale - 0.4f) / 1.6f).coerceIn(0f, 1f),
            isDarkTheme = isDark
        ).apply {
            onProgressChanged = { p ->
                refractionScale = 0.4f + p * 1.6f
                sliderValueView?.text = String.format(Locale.US, "%.2f×", refractionScale)
                opticalBackdrop?.syncShader()
            }
        }
        controlsCard.addView(
            glassSlider,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )

        // Concentric Capsule Tab Bar + Compact Shrink Orb
        controlsCard.addView(TextView(this).apply {
            text = "Concentric Capsule Tab Bar (Tap ⌕ orb to shrink/expand)"
            setTextColor(textSec)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, dp(6))
        })
        val tabBar = LiquidGlassTabBarView(
            this,
            tabs = listOf("Lens", "Prism", "Dock", "Specs"),
            isDarkTheme = isDark
        ).apply {
            onTabSelected = { idx ->
                opticalBackdrop?.setLensPreset(idx)
            }
        }
        controlsCard.addView(
            tabBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66))
        )

        root.addView(
            controlsCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        )

        // =========================================================================
        // 3. iOS 26 MULTI-PLANE G2 SUPERELLIPSE ICON SHOWCASE DOCK
        // =========================================================================
        val dockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillDrawable(
                cardCol,
                26f,
                if (isDark) Color.argb(38, 255, 255, 255) else Color.argb(24, 0, 0, 0)
            )
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        dockCard.addView(TextView(this).apply {
            text = "System-Wide iOS 26 Bespoke Squircle Icons (6 Core Apps)"
            setTextColor(textPri)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        dockCard.addView(TextView(this).apply {
            text = "Clean-room multi-plane glass bevels + universal parametric synthesizer across all installed apps"
            setTextColor(textSec)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(3), 0, dp(14))
        })

        val iconRows = listOf(
            listOf(
                Triple("Phone", R.drawable.ic_showcase_ios26_phone, "2-Plane Glass"),
                Triple("Messages", R.drawable.ic_showcase_ios26_messages, "2-Plane Bubble"),
                Triple("Settings", R.drawable.ic_showcase_ios26_settings, "36/24-Tooth Gear")
            ),
            listOf(
                Triple("Gmail", R.drawable.ic_showcase_ios26_gmail, "5-Piece Folded M"),
                Triple("Photos", R.drawable.ic_showcase_ios26_photos, "4-Petal Pinwheel"),
                Triple("Chrome", R.drawable.ic_showcase_ios26_chrome, "3-Sector Iris")
            )
        )
        for ((rowIdx, rowItems) in iconRows.withIndex()) {
            val iconRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = pillDrawable(
                    if (isDark) Color.rgb(11, 18, 32) else Color.rgb(228, 237, 250),
                    28f,
                    if (isDark) Color.argb(55, 255, 255, 255) else Color.argb(140, 255, 255, 255)
                )
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            for ((name, resId, badge) in rowItems) {
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val iv = ImageView(this).apply {
                    setImageResource(resId)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { v ->
                        SamuraiGlassPrefs.performCrispTick(v, strong = false)
                        v.animate()
                            .scaleX(1.12f)
                            .scaleY(1.12f)
                            .setDuration(110L)
                            .withEndAction {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
                            }
                            .start()
                    }
                }
                col.addView(iv, LinearLayout.LayoutParams(dp(68), dp(68)))
                col.addView(TextView(this).apply {
                    text = name
                    setTextColor(textPri)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, dp(7), 0, dp(1))
                })
                col.addView(TextView(this).apply {
                    text = badge
                    setTextColor(textSec)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                })
                iconRow.addView(col)
            }
            dockCard.addView(
                iconRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (rowIdx == 0) bottomMargin = dp(10)
                }
            )
        }
        root.addView(dockCard)

        scroll.addView(root)
        return scroll
    }

    /**
     * Draws vivid high-contrast diagonal silk ribbons, botanical orange petals & crisp reference bars,
     * and hosts the real-time AGSL RuntimeShader RenderEffect so `content` refracts this exact canvas!
     */
    private inner class OpticalRibbonBackdropView(context: Context) : View(context) {
        var lensCx = 0f
        var lensCy = 0f
        var lensHalfW = dpF(112f)
        var lensHalfH = dpF(66f)
        var lensCornerR = dpF(46f)

        private var targetCx = 0f
        private var targetCy = 0f
        private var velX = 0f
        private var velY = 0f
        private var energize = 0f
        private var energizeVel = 0f
        private var touching = false
        private var lastFrameNanos = 0L

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val ribbonPath = Path()

        fun setLensPreset(preset: Int) {
            when (preset) {
                0 -> {
                    lensHalfW = dpF(112f)
                    lensHalfH = dpF(66f)
                    lensCornerR = dpF(46f)
                }
                1 -> {
                    // Circular optical loupe
                    lensHalfW = dpF(82f)
                    lensHalfH = dpF(82f)
                    lensCornerR = dpF(82f)
                }
                2 -> {
                    // Wide floating dock bar
                    lensHalfW = max(width * 0.42f, dpF(132f))
                    lensHalfH = dpF(50f)
                    lensCornerR = dpF(36f)
                }
                else -> {
                    // Squircle app tile (exact iOS 26 r = 0.2250 * W)
                    lensHalfW = dpF(76f)
                    lensHalfH = dpF(76f)
                    lensCornerR = dpF(34.2f)
                }
            }
            syncShader()
            lensLabelOverlay?.invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (lensCx == 0f && w > 0 && h > 0) {
                lensCx = w * 0.5f
                lensCy = h * 0.52f
                targetCx = lensCx
                targetCy = lensCy
            }
            bgPaint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.rgb(174, 236, 232),
                    Color.rgb(92, 168, 236),
                    Color.rgb(18, 58, 168)
                ),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            syncShader()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    touching = true
                    targetCx = event.x.coerceIn(dpF(54f), max(width - dpF(54f), dpF(55f)))
                    targetCy = event.y.coerceIn(dpF(48f), max(height - dpF(48f), dpF(49f)))
                    SamuraiGlassPrefs.performCrispTick(this, strong = false)
                    startPhysics()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetCx = event.x.coerceIn(dpF(54f), max(width - dpF(54f), dpF(55f)))
                    targetCy = event.y.coerceIn(dpF(48f), max(height - dpF(48f), dpF(49f)))
                    startPhysics()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    SamuraiGlassPrefs.performCrispTick(this, strong = false)
                    startPhysics()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun startPhysics() {
            if (lastFrameNanos == 0L) {
                lastFrameNanos = System.nanoTime()
                postInvalidateOnAnimation()
            } else {
                invalidate()
            }
        }

        fun syncShader() {
            if (width <= 0 || height <= 0) return
            glassShader.applyToScene(
                sceneView = this,
                widthPx = width.toFloat(),
                heightPx = height.toFloat(),
                lensCenterX = lensCx,
                lensCenterY = lensCy,
                lensHalfW = lensHalfW,
                lensHalfH = lensHalfH,
                lensCornerRadius = lensCornerR,
                isTintedMode = isTinted,
                isDarkTheme = true,
                refractionScale = refractionScale,
                energize = energize
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1f || h <= 1f) return

            val now = System.nanoTime()
            var keepGoing = false
            if (lastFrameNanos != 0L) {
                val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                lastFrameNanos = now

                val fx = 380f * (targetCx - lensCx) - 32f * velX
                val fy = 380f * (targetCy - lensCy) - 32f * velY
                velX += fx * dt
                velY += fy * dt
                lensCx += velX * dt
                lensCy += velY * dt

                val targetE = if (touching) 1f else 0f
                val fe = 420f * (targetE - energize) - 34f * energizeVel
                energizeVel += fe * dt
                energize = (energize + energizeVel * dt).coerceIn(0f, 1f)

                val settled = !touching &&
                    abs(targetCx - lensCx) < 0.35f &&
                    abs(targetCy - lensCy) < 0.35f &&
                    abs(velX) < 2f &&
                    abs(velY) < 2f &&
                    abs(energize) < 0.005f
                if (!settled) {
                    keepGoing = true
                } else {
                    lensCx = targetCx
                    lensCy = targetCy
                    velX = 0f
                    velY = 0f
                    energize = 0f
                    energizeVel = 0f
                    lastFrameNanos = 0L
                }
                syncShader()
                lensLabelOverlay?.invalidate()
            }

            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Refined iOS 26 architectural mint-aqua, ice-white & deep-cobalt silk folds (matching Settings -> Liquid Glass)
            val foldColors = intArrayOf(
                Color.argb(195, 132, 236, 224),
                Color.argb(210, 236, 246, 255),
                Color.argb(205, 68, 132, 228),
                Color.argb(225, 10, 44, 148)
            )
            for (i in foldColors.indices) {
                ribbonPaint.color = foldColors[i]
                ribbonPaint.strokeWidth = dpF(44f)
                val xStart = w * (0.14f + i * 0.24f)
                canvas.drawLine(xStart, -dpF(20f), xStart + w * 0.20f, h + dpF(20f), ribbonPaint)

                ribbonPaint.color = Color.argb(115, 255, 255, 255)
                ribbonPaint.strokeWidth = dpF(2.2f)
                canvas.drawLine(
                    xStart - dpF(21f),
                    -dpF(20f),
                    xStart + w * 0.20f - dpF(21f),
                    h + dpF(20f),
                    ribbonPaint
                )
            }

            // Gentle S-curve horizon wave
            ribbonPath.reset()
            val steps = 32
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val px = t * w
                val py = h * (0.54f + 0.16f * sin(t * 4.2f) - 0.06f * cos(t * 2.6f))
                if (s == 0) ribbonPath.moveTo(px, py) else ribbonPath.lineTo(px, py)
            }
            ribbonPaint.color = Color.argb(150, 255, 255, 255)
            ribbonPaint.strokeWidth = dpF(16f)
            canvas.drawPath(ribbonPath, ribbonPaint)

            // Crisp background time ("9:41") so dragging the glass pill gently refracts the numerals
            textBgPaint.color = Color.argb(215, 14, 32, 68)
            textBgPaint.textSize = dpF(68f)
            val fm = textBgPaint.fontMetrics
            val cyText = h * 0.52f - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText("9:41", w * 0.5f, cyText, textBgPaint)

            if (keepGoing) {
                postInvalidateOnAnimation()
            }
        }
    }

    /**
     * Un-refracted crisp HUD caption rendered above the optical stage (just like UIKit foreground labels
     * floating cleanly on top of a UIVisualEffectView Liquid Glass lens).
     */
    private inner class LensReadoutOverlayView(context: Context) : View(context) {
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 6, 14, 28)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 220, 240, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val fallbackRect = RectF()
        private val fallbackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.6f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stage = opticalBackdrop ?: return
            val w = width.toFloat()
            val h = height.toFloat()

            // Top instruction pill
            labelPaint.textSize = dpF(12.5f)
            val pillW = dpF(276f)
            val pillH = dpF(30f)
            val px = (w - pillW) * 0.5f
            val py = dpF(12f)
            canvas.drawRoundRect(px, py, px + pillW, py + pillH, pillH * 0.5f, pillH * 0.5f, badgePaint)
            canvas.drawText(
                if (isTinted) "Drag Lens • Tinted Mode (iOS 26.1)" else "Drag Lens • Clear Snell Refraction",
                w * 0.5f,
                py + dpF(19.5f),
                labelPaint
            )

            // If RuntimeShader isn't active for any reason, render clean G2 fallback rim
            if (!glassShader.isSupported) {
                fallbackRect.set(
                    stage.lensCx - stage.lensHalfW,
                    stage.lensCy - stage.lensHalfH,
                    stage.lensCx + stage.lensHalfW,
                    stage.lensCy + stage.lensHalfH
                )
                fallbackRimPaint.shader = LinearGradient(
                    fallbackRect.left, fallbackRect.top, fallbackRect.right, fallbackRect.bottom,
                    intArrayOf(Color.WHITE, Color.argb(24, 255, 255, 255), Color.argb(180, 210, 242, 255)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                val path = SamuraiSquircle.buildPath(
                    fallbackRect.left, fallbackRect.top, fallbackRect.right, fallbackRect.bottom, stage.lensCornerR
                )
                canvas.drawPath(path, fallbackRimPaint)
            }

            // Bottom subtle telemetry caption
            subPaint.textSize = dpF(11.5f)
            canvas.drawText(
                "Adreno 640 Single-Pass AGSL • Zero Global SurfaceFlinger Blur",
                w * 0.5f,
                h - dpF(14f),
                subPaint
            )
        }
    }
}
