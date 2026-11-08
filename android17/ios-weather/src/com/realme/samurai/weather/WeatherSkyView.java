package com.realme.samurai.weather;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;
import com.realme.samurai.glass.SamuraiAgslEngine;

/**
 * 90 Hz `Choreographer` Procedural Atmospheric Sky Canvas (`WeatherSkyView`).
 *
 * Renders smooth atmospheric sky gradients plus:
 * - Clear / Partly Cloudy Day: Volumetric sun rays & parallax drifting translucent cumulus clouds
 * - Rain / Thunderstorm: Stormy slate clouds, 90 Hz diagonal falling rain streaks, sheet lightning
 * - Night: Twinkling starfield + undulating emerald-violet aurora borealis ribbon glow
 *
 * Also synthesizes matched `SamuraiAgslEngine.BackdropPair` buffers so every `SamuraiGlassCard`
 * platter refracts the active sky palette cleanly.
 */
public final class WeatherSkyView extends View {

    private static final int STAR_COUNT = 64;
    private static final int RAIN_COUNT = 72;

    private final Paint mSkyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mAuroraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mAuroraPath = new Path();

    private final float[] mStarX = new float[STAR_COUNT];
    private final float[] mStarY = new float[STAR_COUNT];
    private final float[] mStarPhase = new float[STAR_COUNT];
    private final float[] mStarRadius = new float[STAR_COUNT];

    private final float[] mRainX = new float[RAIN_COUNT];
    private final float[] mRainY = new float[RAIN_COUNT];
    private final float[] mRainSpeed = new float[RAIN_COUNT];
    private final float[] mRainLen = new float[RAIN_COUNT];

    private final SamuraiAgslEngine.BackdropPair[] mSkyBackdrops = new SamuraiAgslEngine.BackdropPair[5];

    private int mSkyMode = WeatherModels.SKY_PARTLY_CLOUDY_DAY;
    private boolean mThunderstorm = false;
    private boolean mAnimating = false;
    private long mLastFrameNanos = 0L;
    private float mTimeSec = 0f;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mAnimating) return;
            float dt = (mLastFrameNanos == 0L)
                    ? (1f / 90f)
                    : ((frameTimeNanos - mLastFrameNanos) / 1_000_000_000f);
            mLastFrameNanos = frameTimeNanos;
            dt = Math.max(0.001f, Math.min(dt, 0.05f));
            mTimeSec += dt;
            stepParticles(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public WeatherSkyView(Context context) {
        super(context);
        mRainPaint.setStrokeCap(Paint.Cap.ROUND);
        seedParticles();
    }

    private void seedParticles() {
        long seed = 0x5A17EA26L;
        for (int i = 0; i < STAR_COUNT; i++) {
            seed = nextRand(seed);
            mStarX[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            seed = nextRand(seed);
            mStarY[i] = (((seed >>> 16) & 0xFFFF) / 65535f) * 0.62f;
            seed = nextRand(seed);
            mStarPhase[i] = (((seed >>> 16) & 0xFFFF) / 65535f) * 6.283f;
            seed = nextRand(seed);
            mStarRadius[i] = 0.8f + (((seed >>> 16) & 0xFFFF) / 65535f) * 1.5f;
        }
        for (int i = 0; i < RAIN_COUNT; i++) {
            seed = nextRand(seed);
            mRainX[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            seed = nextRand(seed);
            mRainY[i] = ((seed >>> 16) & 0xFFFF) / 65535f;
            seed = nextRand(seed);
            mRainSpeed[i] = 0.68f + (((seed >>> 16) & 0xFFFF) / 65535f) * 0.65f;
            seed = nextRand(seed);
            mRainLen[i] = 16f + (((seed >>> 16) & 0xFFFF) / 65535f) * 20f;
        }
    }

    private static long nextRand(long s) {
        return s * 6364136223846793005L + 1442695040888963407L;
    }

    private void stepParticles(float dt) {
        if (mSkyMode == WeatherModels.SKY_RAIN_STORM) {
            for (int i = 0; i < RAIN_COUNT; i++) {
                mRainY[i] += mRainSpeed[i] * dt * 1.15f;
                mRainX[i] -= mRainSpeed[i] * dt * 0.22f;
                if (mRainY[i] > 1.08f) {
                    mRainY[i] -= 1.16f;
                    mRainX[i] = (mRainX[i] + 1.23f) % 1.0f;
                }
                if (mRainX[i] < -0.08f) {
                    mRainX[i] += 1.12f;
                }
            }
        }
    }

    public void setWeatherCondition(int weatherCode, boolean isDay) {
        mSkyMode = WeatherModels.resolveSkyMode(weatherCode, isDay);
        mThunderstorm = weatherCode >= 95;
        invalidate();
    }

    public int getSkyMode() {
        return mSkyMode;
    }

    public int getPlatterTintColor() {
        switch (mSkyMode) {
            case WeatherModels.SKY_CLEAR_DAY:
                return 0xFF1A6ED8;
            case WeatherModels.SKY_PARTLY_CLOUDY_DAY:
                return 0xFF285F9E;
            case WeatherModels.SKY_RAIN_STORM:
                return 0xFF1D2C3F;
            case WeatherModels.SKY_CLEAR_NIGHT:
            case WeatherModels.SKY_CLOUDY_NIGHT:
            default:
                return 0xFF0E1734;
        }
    }

    public SamuraiAgslEngine.BackdropPair getSkyBackdropPair() {
        int idx = Math.max(0, Math.min(mSkyBackdrops.length - 1, mSkyMode));
        if (mSkyBackdrops[idx] == null) {
            mSkyBackdrops[idx] = buildBackdropForMode(idx);
        }
        return mSkyBackdrops[idx];
    }

    private static SamuraiAgslEngine.BackdropPair buildBackdropForMode(int mode) {
        int w = 240;
        int h = 520;
        Bitmap sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sharp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] grad = gradientColorsForMode(mode);
        p.setShader(new LinearGradient(0, 0, 0, h, grad, new float[] { 0f, 0.52f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
        int glowCol = (mode == WeatherModels.SKY_CLEAR_DAY || mode == WeatherModels.SKY_PARTLY_CLOUDY_DAY)
                ? 0x55FFE082
                : (mode == WeatherModels.SKY_RAIN_STORM ? 0x4464B5F6 : 0x4400E5B8);
        orb.setShader(new RadialGradient(w * 0.76f, h * 0.22f, w * 0.65f,
                new int[] { glowCol, glowCol & 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, orb);

        Bitmap soft = SamuraiAgslEngine.boxBlurFast(sharp, 10);
        return new SamuraiAgslEngine.BackdropPair(sharp, soft);
    }

    private static int[] gradientColorsForMode(int mode) {
        switch (mode) {
            case WeatherModels.SKY_CLEAR_DAY:
                return new int[] { 0xFF0E5AC4, 0xFF2B8AE8, 0xFF6EC1FF };
            case WeatherModels.SKY_PARTLY_CLOUDY_DAY:
                return new int[] { 0xFF1D5296, 0xFF3A76B8, 0xFF79A7D8 };
            case WeatherModels.SKY_RAIN_STORM:
                return new int[] { 0xFF15202E, 0xFF243447, 0xFF3A4F66 };
            case WeatherModels.SKY_CLOUDY_NIGHT:
                return new int[] { 0xFF080E1E, 0xFF131E38, 0xFF202E4C };
            case WeatherModels.SKY_CLEAR_NIGHT:
            default:
                return new int[] { 0xFF050916, 0xFF0D1938, 0xFF1A2A52 };
        }
    }

    public void setPaused(boolean paused) {
        if (paused) {
            stopLoop();
        } else if (isAttachedToWindow()) {
            startLoop();
        }
    }

    private void startLoop() {
        if (!mAnimating) {
            mAnimating = true;
            mLastFrameNanos = 0L;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    private void stopLoop() {
        if (mAnimating) {
            mAnimating = false;
            mLastFrameNanos = 0L;
            try {
                Choreographer.getInstance().removeFrameCallback(mFrameCallback);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 2) return;
        float d = getResources().getDisplayMetrics().density;

        // 1. Base atmospheric sky gradient
        int[] cols = gradientColorsForMode(mSkyMode);
        mSkyPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h, cols, new float[] { 0f, 0.54f, 1f }, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h, mSkyPaint);

        if (mSkyMode == WeatherModels.SKY_CLEAR_DAY || mSkyMode == WeatherModels.SKY_PARTLY_CLOUDY_DAY) {
            drawSunAndVolumetricRays(canvas, w, h, d);
            int cloudCount = (mSkyMode == WeatherModels.SKY_PARTLY_CLOUDY_DAY) ? 4 : 2;
            drawDriftingClouds(canvas, w, h, d, cloudCount, 0x42FFFFFF);
        } else if (mSkyMode == WeatherModels.SKY_RAIN_STORM) {
            drawDriftingClouds(canvas, w, h, d, 5, 0x3690A4AE);
            drawRainAndLightning(canvas, w, h, d);
        } else {
            drawNightStarsAndAurora(canvas, w, h, d);
            if (mSkyMode == WeatherModels.SKY_CLOUDY_NIGHT) {
                drawDriftingClouds(canvas, w, h, d, 3, 0x26B0BEC5);
            }
        }
    }

    private void drawSunAndVolumetricRays(Canvas canvas, int w, int h, float d) {
        float sunX = w * 0.82f;
        float sunY = h * 0.13f;
        float sunR = w * 0.42f;

        // Rotating volumetric sun shafts
        canvas.save();
        canvas.translate(sunX, sunY);
        canvas.rotate(mTimeSec * 3.5f);
        mRayPaint.setShader(new RadialGradient(
                0f, 0f, w * 0.78f,
                new int[] { 0x33FFF8D6, 0x12FFE082, 0x00FFFFFF },
                new float[] { 0f, 0.45f, 1f },
                Shader.TileMode.CLAMP));
        for (int i = 0; i < 6; i++) {
            canvas.rotate(30f);
            canvas.drawOval(-w * 0.11f, -w * 0.78f, w * 0.11f, w * 0.78f, mRayPaint);
        }
        canvas.restore();

        // Warm solar corona
        float pulse = 1.0f + 0.04f * (float) Math.sin(mTimeSec * 1.4f);
        mSunPaint.setShader(new RadialGradient(
                sunX, sunY, sunR * pulse,
                new int[] { 0xAAFFFBE6, 0x55FFD54F, 0x00FFB300 },
                new float[] { 0f, 0.35f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawCircle(sunX, sunY, sunR * pulse, mSunPaint);
    }

    private void drawDriftingClouds(Canvas canvas, int w, int h, float d, int layers, int tintArgb) {
        for (int i = 0; i < layers; i++) {
            float speed = (10f + i * 5.5f) * d;
            float span = w + 260f * d;
            float cx = ((i * 210f * d + mTimeSec * speed) % span) - 130f * d;
            float cy = h * (0.12f + i * 0.085f);
            float rx = (95f + (i % 3) * 28f) * d;
            float ry = rx * 0.42f;

            mCloudPaint.setShader(new RadialGradient(
                    cx, cy, rx,
                    new int[] { tintArgb, tintArgb & 0x00FFFFFF },
                    new float[] { 0.15f, 1f },
                    Shader.TileMode.CLAMP));
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, mCloudPaint);
            canvas.drawOval(cx - rx * 0.55f, cy - ry * 1.35f, cx + rx * 0.65f, cy + ry * 0.7f, mCloudPaint);
        }
    }

    private void drawRainAndLightning(Canvas canvas, int w, int h, float d) {
        if (mThunderstorm) {
            float cycle = mTimeSec % 6.5f;
            if ((cycle > 0.10f && cycle < 0.18f) || (cycle > 0.24f && cycle < 0.30f)) {
                mCloudPaint.setShader(null);
                mCloudPaint.setColor(0x28E1F5FE);
                canvas.drawRect(0f, 0f, w, h, mCloudPaint);
            }
        }

        mRainPaint.setStrokeWidth(1.35f * d);
        for (int i = 0; i < RAIN_COUNT; i++) {
            float x = mRainX[i] * w;
            float y = mRainY[i] * h;
            float len = mRainLen[i] * d;
            int alpha = 70 + (i % 4) * 28;
            mRainPaint.setColor((alpha << 24) | 0x00CFE8FF);
            canvas.drawLine(x, y, x - len * 0.21f, y + len, mRainPaint);
        }
    }

    private void drawNightStarsAndAurora(Canvas canvas, int w, int h, float d) {
        // Twinkling starfield
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(mTimeSec * 2.1f + mStarPhase[i]));
            int alpha = Math.round(215f * twinkle);
            mStarPaint.setColor((alpha << 24) | 0x00F0F6FF);
            canvas.drawCircle(mStarX[i] * w, mStarY[i] * h, mStarRadius[i] * d, mStarPaint);
        }

        // Undulating Aurora Borealis ribbon glow
        mAuroraPath.reset();
        float baseY = h * 0.22f;
        mAuroraPath.moveTo(0f, baseY);
        int steps = 8;
        for (int s = 1; s <= steps; s++) {
            float fx = (w * s) / (float) steps;
            float wave = (float) Math.sin(mTimeSec * 0.75f + s * 0.85f) * (24f * d);
            mAuroraPath.lineTo(fx, baseY + wave);
        }
        mAuroraPath.lineTo(w, 0f);
        mAuroraPath.lineTo(0f, 0f);
        mAuroraPath.close();

        mAuroraPaint.setShader(new LinearGradient(
                0f, 0f, w, h * 0.34f,
                new int[] { 0x0000E5B8, 0x3000E5B8, 0x2A7C4DFF, 0x007C4DFF },
                new float[] { 0f, 0.38f, 0.72f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawPath(mAuroraPath, mAuroraPaint);
    }
}
