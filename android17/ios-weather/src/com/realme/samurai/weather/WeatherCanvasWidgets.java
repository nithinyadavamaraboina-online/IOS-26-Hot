package com.realme.samurai.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Custom hardware-accelerated Canvas widgets (`WeatherCanvasWidgets`) for iOS 26 Weather:
 * - `WeatherIconView`: Razor-sharp vector-style sun, cloud, rain, storm, and moon icons
 * - `TenDayRangeBarView`: Multi-stop cyan -> lime -> amber -> coral temperature bar with Today dot
 * - `UvSpectrumBarView`: Multi-stop UV Index spectrum track with live marker dot
 * - `WindCompassDialView`: Circular tick-marked compass dial with directional wind arrow
 */
public final class WeatherCanvasWidgets {

    private WeatherCanvasWidgets() {}

    /**
     * Crisp Canvas weather condition glyph (`WeatherIconView`).
     */
    public static final class WeatherIconView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private int mIconKind;

        public WeatherIconView(Context context, int iconKind) {
            super(context);
            mIconKind = iconKind;
        }

        public void setIconKind(int kind) {
            mIconKind = kind;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 2f || h <= 2f) return;
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = Math.min(w, h) * 0.42f;

            switch (mIconKind) {
                case WeatherModels.ICON_SUN:
                    drawSun(canvas, cx, cy, r * 0.58f);
                    break;
                case WeatherModels.ICON_PARTLY_CLOUDY:
                    drawSun(canvas, cx - r * 0.25f, cy - r * 0.22f, r * 0.44f);
                    drawCloud(canvas, cx + r * 0.08f, cy + r * 0.16f, r * 0.78f, 0xF2FFFFFF);
                    break;
                case WeatherModels.ICON_CLOUD:
                    drawCloud(canvas, cx, cy + r * 0.08f, r * 0.86f, 0xEBFFFFFF);
                    break;
                case WeatherModels.ICON_RAIN:
                    drawCloud(canvas, cx, cy - r * 0.10f, r * 0.80f, 0xDCE3F2FD);
                    drawRainDrops(canvas, cx, cy + r * 0.46f, r);
                    break;
                case WeatherModels.ICON_STORM:
                    drawCloud(canvas, cx, cy - r * 0.12f, r * 0.80f, 0xCFD8DCFF);
                    drawLightning(canvas, cx, cy + r * 0.36f, r);
                    break;
                case WeatherModels.ICON_MOON_CLOUD:
                    drawCrescentMoon(canvas, cx - r * 0.24f, cy - r * 0.22f, r * 0.44f);
                    drawCloud(canvas, cx + r * 0.10f, cy + r * 0.18f, r * 0.76f, 0xDCE8F0FE);
                    break;
                case WeatherModels.ICON_MOON:
                default:
                    drawCrescentMoon(canvas, cx, cy, r * 0.60f);
                    break;
            }
        }

        private void drawSun(Canvas c, float cx, float cy, float coreR) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(Math.max(1.8f, coreR * 0.22f));
            mPaint.setColor(0xFFFFD60A);
            for (int i = 0; i < 8; i++) {
                double a = i * (Math.PI / 4.0);
                float x0 = cx + (float) Math.cos(a) * (coreR * 1.32f);
                float y0 = cy + (float) Math.sin(a) * (coreR * 1.32f);
                float x1 = cx + (float) Math.cos(a) * (coreR * 1.72f);
                float y1 = cy + (float) Math.sin(a) * (coreR * 1.72f);
                c.drawLine(x0, y0, x1, y1, mPaint);
            }
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFFFFCA28);
            c.drawCircle(cx, cy, coreR, mPaint);
        }

        private void drawCloud(Canvas c, float cx, float cy, float size, int color) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            RectF base = new RectF(cx - size * 0.75f, cy - size * 0.20f, cx + size * 0.75f, cy + size * 0.38f);
            c.drawRoundRect(base, size * 0.30f, size * 0.30f, mPaint);
            c.drawCircle(cx - size * 0.26f, cy - size * 0.14f, size * 0.34f, mPaint);
            c.drawCircle(cx + size * 0.16f, cy - size * 0.24f, size * 0.42f, mPaint);
        }

        private void drawRainDrops(Canvas c, float cx, float topY, float r) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(Math.max(1.8f, r * 0.16f));
            mPaint.setColor(0xFF5AC8FA);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * r * 0.38f;
                c.drawLine(x, topY - r * 0.12f, x - r * 0.12f, topY + r * 0.28f, mPaint);
            }
        }

        private void drawLightning(Canvas c, float cx, float cy, float r) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFFFFD60A);
            mPath.reset();
            mPath.moveTo(cx + r * 0.08f, cy - r * 0.26f);
            mPath.lineTo(cx - r * 0.24f, cy + r * 0.10f);
            mPath.lineTo(cx + r * 0.02f, cy + r * 0.10f);
            mPath.lineTo(cx - r * 0.10f, cy + r * 0.48f);
            mPath.lineTo(cx + r * 0.26f, cy + r * 0.04f);
            mPath.lineTo(cx + r * 0.02f, cy + r * 0.04f);
            mPath.close();
            c.drawPath(mPath, mPaint);
        }

        private void drawCrescentMoon(Canvas c, float cx, float cy, float r) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFFFFE082);
            mPath.reset();
            RectF outer = new RectF(cx - r, cy - r, cx + r, cy + r);
            mPath.addArc(outer, 35f, 290f);
            RectF inner = new RectF(cx - r * 0.42f, cy - r * 1.05f, cx + r * 1.25f, cy + r * 0.65f);
            mPath.arcTo(inner, 290f, -240f);
            mPath.close();
            c.drawCircle(cx, cy, r, mPaint);
            // Cut crescent shadow cleanly with alpha-safe offset circle in sky-indigo tint
            mPaint.setColor(0xFF1A2950);
            c.drawCircle(cx + r * 0.42f, cy - r * 0.30f, r * 0.82f, mPaint);
        }
    }

    /**
     * Signature iOS 26 10-Day Forecast multi-stop temperature range bar (`TenDayRangeBarView`)
     * spanning cyan -> lime -> amber -> coral (`#32ADE6 -> #30D158 -> #FFD60A -> #FF9F0A -> #FF5E3A`),
     * with a glowing current-temperature dot on `"Today"`.
     */
    public static final class TenDayRangeBarView extends View {
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        private final int mGlobalMin;
        private final int mGlobalMax;
        private final int mDayMin;
        private final int mDayMax;
        private final boolean mShowCurrentDot;
        private final int mCurrentTemp;

        public TenDayRangeBarView(
                Context context,
                int globalMin,
                int globalMax,
                int dayMin,
                int dayMax,
                boolean showCurrentDot,
                int currentTemp) {
            super(context);
            mGlobalMin = globalMin;
            mGlobalMax = Math.max(globalMin + 1, globalMax);
            mDayMin = dayMin;
            mDayMax = Math.max(dayMin, dayMax);
            mShowCurrentDot = showCurrentDot;
            mCurrentTemp = currentTemp;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 4f || h <= 2f) return;
            float d = getResources().getDisplayMetrics().density;

            float barH = 5.2f * d;
            float top = (h - barH) * 0.5f;
            float r = barH * 0.5f;

            mTrackPaint.setColor(0x36000000);
            mRect.set(0f, top, w, top + barH);
            canvas.drawRoundRect(mRect, r, r, mTrackPaint);

            float span = (float) (mGlobalMax - mGlobalMin);
            float fStart = clamp01((mDayMin - mGlobalMin) / span);
            float fEnd = clamp01((mDayMax - mGlobalMin) / span);
            float left = fStart * w;
            float right = Math.max(left + barH, fEnd * w);

            // Multi-stop iOS 26 cyan -> lime -> amber -> coral spectrum mapped across full week width
            mBarPaint.setShader(new LinearGradient(
                    0f, 0f, w, 0f,
                    new int[] { 0xFF32ADE6, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF5E3A },
                    new float[] { 0f, 0.30f, 0.58f, 0.80f, 1f },
                    Shader.TileMode.CLAMP));
            mRect.set(left, top, right, top + barH);
            canvas.drawRoundRect(mRect, r, r, mBarPaint);

            if (mShowCurrentDot) {
                float fCur = clamp01((mCurrentTemp - mGlobalMin) / span);
                float dotX = Math.max(left + r, Math.min(right - r, fCur * w));
                float cy = h * 0.5f;
                mDotHaloPaint.setColor(0xAA0C1526);
                canvas.drawCircle(dotX, cy, 4.8f * d, mDotHaloPaint);
                mDotPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(dotX, cy, 3.2f * d, mDotPaint);
            }
        }
    }

    /**
     * UV Index multi-stop spectrum bar (`UvSpectrumBarView`).
     */
    public static final class UvSpectrumBarView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mUvValue;

        public UvSpectrumBarView(Context context, float uvValue) {
            super(context);
            mUvValue = uvValue;
        }

        public void setUvValue(float uv) {
            mUvValue = uv;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 4f || h <= 2f) return;
            float d = getResources().getDisplayMetrics().density;

            float barH = 5.5f * d;
            float top = (h - barH) * 0.5f;
            float r = barH * 0.5f;

            mPaint.setShader(new LinearGradient(
                    0f, 0f, w, 0f,
                    new int[] { 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2 },
                    new float[] { 0f, 0.25f, 0.50f, 0.75f, 1f },
                    Shader.TileMode.CLAMP));
            mRect.set(0f, top, w, top + barH);
            canvas.drawRoundRect(mRect, r, r, mPaint);

            float frac = clamp01(mUvValue / 11f);
            float dotX = r + frac * (w - barH);
            float cy = h * 0.5f;
            mHaloPaint.setColor(0xAA0C1526);
            canvas.drawCircle(dotX, cy, 5.0f * d, mHaloPaint);
            mDotPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(dotX, cy, 3.4f * d, mDotPaint);
        }
    }

    /**
     * Wind Compass Dial (`WindCompassDialView`) with 36 tick marks, N/E/S/W labels, and wind arrow.
     */
    public static final class WindCompassDialView extends View {
        private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrowPath = new Path();
        private int mDegrees;

        public WindCompassDialView(Context context, int degrees) {
            super(context);
            mDegrees = degrees;
            mRingPaint.setStyle(Paint.Style.STROKE);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }

        public void setDegrees(int deg) {
            mDegrees = deg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 4f || h <= 4f) return;
            float d = getResources().getDisplayMetrics().density;
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float radius = Math.min(w, h) * 0.45f;

            // Tick marks around compass circle
            mRingPaint.setStrokeWidth(1.1f * d);
            for (int i = 0; i < 36; i++) {
                double rad = Math.toRadians(i * 10.0);
                boolean cardinal = (i % 9 == 0);
                mRingPaint.setColor(cardinal ? 0xCCFFFFFF : 0x50FFFFFF);
                float inner = radius - (cardinal ? 5.5f * d : 3.2f * d);
                canvas.drawLine(
                        cx + (float) Math.sin(rad) * inner,
                        cy - (float) Math.cos(rad) * inner,
                        cx + (float) Math.sin(rad) * radius,
                        cy - (float) Math.cos(rad) * radius,
                        mRingPaint);
            }

            mLabelPaint.setTextSize(9.5f * d);
            mLabelPaint.setColor(0xFFFF453A);
            canvas.drawText("N", cx, cy - radius + 14f * d, mLabelPaint);
            mLabelPaint.setColor(0xB8FFFFFF);
            canvas.drawText("S", cx, cy + radius - 7f * d, mLabelPaint);
            canvas.drawText("E", cx + radius - 10f * d, cy + 3.5f * d, mLabelPaint);
            canvas.drawText("W", cx - radius + 10f * d, cy + 3.5f * d, mLabelPaint);

            // Directional needle rotated to mDegrees
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(mDegrees);
            mArrowPaint.setStyle(Paint.Style.STROKE);
            mArrowPaint.setStrokeWidth(2.0f * d);
            mArrowPaint.setColor(0xFFFFFFFF);
            canvas.drawLine(0f, radius * 0.52f, 0f, -radius * 0.52f, mArrowPaint);

            mArrowPaint.setStyle(Paint.Style.FILL);
            mArrowPath.reset();
            mArrowPath.moveTo(0f, -radius * 0.66f);
            mArrowPath.lineTo(-4.2f * d, -radius * 0.40f);
            mArrowPath.lineTo(4.2f * d, -radius * 0.40f);
            mArrowPath.close();
            canvas.drawPath(mArrowPath, mArrowPaint);
            canvas.drawCircle(0f, radius * 0.52f, 2.8f * d, mArrowPaint);
            canvas.restore();
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
