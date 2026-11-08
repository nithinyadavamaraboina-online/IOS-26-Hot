package com.realme.samurai.glass

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean-room procedural iOS 26 Cyan/Cobalt Petals & Midnight Cobalt wallpaper synthesizer (0 KB APK bloat).
 * Matches `build_ios26_cyan_cobalt_petals_wallpaper` 1:1 so diagonal turquoise/cerulean/sapphire petals
 * refract across the floating Home Screen "Q Search" dewdrop and G2 Liquid Glass Dock lip.
 */
object SamuraiWallpaperGenerator {
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val denom = max(edge1 - edge0, 1e-5f)
        val t = ((x - edge0) / denom).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun synthesizeBitmap(darkVariant: Boolean, outW: Int = 1080, outH: Int = 2400): Bitmap {
        val w = 540
        val h = 1200
        val pixels = IntArray(w * h)
        val invW = 1f / (w - 1).toFloat()
        val invH = 1f / (h - 1).toFloat()

        if (!darkVariant) {
            for (y in 0 until h) {
                val yn = y * invH
                val tMid = smoothstep(0.68f, 0.86f, yn)
                val ynDeep = smoothstep(0.85f, 0.945f, yn)

                val ynP1 = (yn / 0.57f).coerceIn(0f, 1f)
                val sinArg = (yn / 0.58f).coerceIn(0f, 1f) * PI.toFloat()
                val xP1 = 0.44f - 0.09f * sin(sinArg) + 0.62f * ynP1.pow(1.85f)
                val fadeP1Y = 1f - smoothstep(0.54f, 0.60f, yn)
                val liftP1Y = smoothstep(0.22f, 0.55f, yn)
                val p1ActiveY = if (yn < 0.62f) 1f else 0f

                val edgeW = 0.016f * (1f - smoothstep(0.81f, 0.85f, yn)) + 0.0042f
                val tCeru = smoothstep(0.790f, 0.852f, yn)

                val xP3 = 0.862f + 0.052f * max(1f - yn, 0f).pow(1.3f)
                val fadeP3Y = smoothstep(0.56f, 0.74f, yn)
                val tP3Low = smoothstep(0.76f, 0.98f, yn)
                val p3R = 154f * (1f - tP3Low) + 78f * tP3Low
                val p3G = 206f * (1f - tP3Low) + 185f * tP3Low
                val p3B = 242f

                val baseR0 = 176f * (1f - tMid) + 95f * tMid
                val baseG0 = 224f * (1f - tMid) + 196f * tMid
                val baseB0 = 224f * (1f - tMid) + 197f * tMid

                val rowOff = y * w
                for (x in 0 until w) {
                    val xn = x * invW
                    val tDeepTeal = ynDeep * (1f - smoothstep(0.08f, 0.42f, xn))
                    var r = baseR0 * (1f - tDeepTeal) + 2f * tDeepTeal
                    var g = baseG0 * (1f - tDeepTeal) + 130f * tDeepTeal
                    var b = baseB0 * (1f - tDeepTeal) + 168f * tDeepTeal

                    val mP1 = smoothstep(-0.004f, 0.005f, xn - xP1) * fadeP1Y
                    if (mP1 > 0.0005f) {
                        val dP1 = max((xn - xP1) * p1ActiveY, 0f)
                        val u1 = (dP1 - 0.025f) / 0.045f
                        val rimP1 = exp(-(u1 * u1))
                        val p1R = 4f + 46f * rimP1 + 38f * liftP1Y
                        val p1G = 16f + 78f * rimP1 + 48f * liftP1Y
                        val p1B = 52f + 118f * rimP1 + 108f * liftP1Y
                        r = r * (1f - mP1) + p1R * mP1
                        g = g * (1f - mP1) + p1G * mP1
                        b = b * (1f - mP1) + p1B * mP1
                    }

                    val ynDiag = 0.938f - 0.205f * xn - 0.065f * xn * xn
                    val dDiag = yn - ynDiag
                    val mP2 = smoothstep(-edgeW, edgeW, dDiag)
                    if (mP2 > 0.0005f) {
                        val tSapp = smoothstep(0.885f, 0.990f, yn + 0.03f * (1f - xn))
                        val xRight = smoothstep(0.28f, 0.78f, xn)
                        val sappG = 68f + 36f * xRight
                        val sappB = 166f + 30f * xRight
                        var p2R = (92f * (1f - tCeru) + 36f * tCeru) * (1f - tSapp) + 28f * tSapp
                        var p2G = (196f * (1f - tCeru) + 162f * tCeru) * (1f - tSapp) + sappG * tSapp
                        var p2B = (216f * (1f - tCeru) + 201f * tCeru) * (1f - tSapp) + sappB * tSapp

                        val uDiag = max(dDiag, 0f) / 0.012f
                        val edgeGlow = exp(-(uDiag * uDiag)) * (1f - 0.55f * tSapp)
                        p2R += 12f * edgeGlow
                        p2G += 14f * edgeGlow
                        p2B += 20f * edgeGlow

                        r = r * (1f - mP2) + p2R * mP2
                        g = g * (1f - mP2) + p2G * mP2
                        b = b * (1f - mP2) + p2B * mP2
                    }

                    val mP3 = 0.90f * smoothstep(-0.032f, 0.068f, xn - xP3) * fadeP3Y
                    if (mP3 > 0.0005f) {
                        r = r * (1f - mP3) + p3R * mP3
                        g = g * (1f - mP3) + p3G * mP3
                        b = b * (1f - mP3) + p3B * mP3
                    }

                    val ri = r.roundToInt().coerceIn(0, 255)
                    val gi = g.roundToInt().coerceIn(0, 255)
                    val bi = b.roundToInt().coerceIn(0, 255)
                    pixels[rowOff + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        } else {
            for (y in 0 until h) {
                val yn = y * invH
                val baseR = 6f + 14f * (1f - yn)
                val baseG = 14f + 34f * (1f - 0.7f * yn)
                val baseB = 34f + 62f * (1f - 0.5f * yn)

                val ynP1 = (yn / 0.58f).coerceIn(0f, 1f)
                val sinArg = (yn / 0.60f).coerceIn(0f, 1f) * PI.toFloat()
                val xP1 = 0.42f - 0.08f * sin(sinArg) + 0.60f * ynP1.pow(1.8f)
                val fadeP1Y = 1f - smoothstep(0.54f, 0.62f, yn)

                val xP3 = 0.85f + 0.055f * max(1f - yn, 0f).pow(1.3f)
                val fadeP3Y = smoothstep(0.58f, 0.76f, yn)

                val rowOff = y * w
                for (x in 0 until w) {
                    val xn = x * invW
                    var r = baseR
                    var g = baseG
                    var b = baseB

                    val mP1 = smoothstep(-0.005f, 0.005f, xn - xP1) * fadeP1Y
                    if (mP1 > 0.0005f) {
                        val u1 = (max(xn - xP1, 0f) - 0.02f) / 0.045f
                        val rimP1 = exp(-(u1 * u1))
                        r = r * (1f - mP1) + (28f + 62f * rimP1) * mP1
                        g = g * (1f - mP1) + (42f + 96f * rimP1) * mP1
                        b = b * (1f - mP1) + (118f + 124f * rimP1) * mP1
                    }

                    val ynDiag = 0.940f - 0.195f * xn - 0.095f * xn * xn
                    val dDiag = yn - ynDiag
                    val mP2 = smoothstep(-0.004f, 0.004f, dDiag)
                    if (mP2 > 0.0005f) {
                        val tSapp = smoothstep(0.88f, 0.985f, yn + 0.03f * (1f - xn))
                        val uDiag = max(dDiag, 0f) / 0.013f
                        val edgeGlow = exp(-(uDiag * uDiag))
                        val p2R = (20f * (1f - tSapp) + 26f * tSapp) + 42f * edgeGlow
                        val p2G = (156f * (1f - tSapp) + 44f * tSapp) + 62f * edgeGlow
                        val p2B = (224f * (1f - tSapp) + 162f * tSapp) + 28f * edgeGlow
                        r = r * (1f - mP2) + p2R * mP2
                        g = g * (1f - mP2) + p2G * mP2
                        b = b * (1f - mP2) + p2B * mP2
                    }

                    val mP3 = 0.78f * smoothstep(-0.035f, 0.075f, xn - xP3) * fadeP3Y
                    if (mP3 > 0.0005f) {
                        r = r * (1f - mP3) + 74f * mP3
                        g = g * (1f - mP3) + 132f * mP3
                        b = b * (1f - mP3) + 238f * mP3
                    }

                    val ri = r.roundToInt().coerceIn(0, 255)
                    val gi = g.roundToInt().coerceIn(0, 255)
                    val bi = b.roundToInt().coerceIn(0, 255)
                    pixels[rowOff + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }

        val halfBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (outW == w && outH == h) return halfBmp
        val fullBmp = Bitmap.createScaledBitmap(halfBmp, outW, outH, true)
        if (fullBmp !== halfBmp) {
            halfBmp.recycle()
        }
        return fullBmp
    }

    fun applyWallpaperAsync(context: Context, darkVariant: Boolean, onDone: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
        bgExecutor.execute {
            var ok = false
            try {
                val bmp = synthesizeBitmap(darkVariant, 1080, 2400)
                val wm = WallpaperManager.getInstance(appCtx)
                wm.setBitmap(
                    bmp,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
                bmp.recycle()
                SamuraiGlassPrefs.bumpWallpaperSeq(appCtx)
                ok = true
            } catch (_: Throwable) {
            }
            mainHandler.post {
                val label = if (darkVariant) "iOS 26 Midnight Cobalt" else "iOS 26 Cyan/Cobalt Petals"
                Toast.makeText(
                    appCtx,
                    if (ok) "Applied $label wallpaper" else "Failed to set wallpaper",
                    Toast.LENGTH_SHORT
                ).show()
                onDone?.invoke(ok)
            }
        }
    }
}
