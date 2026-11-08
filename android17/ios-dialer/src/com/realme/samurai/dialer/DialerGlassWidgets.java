package com.realme.samurai.dialer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Reusable 90Hz AGSL iOS 26.1 Liquid Glass Controls with Unified Hooke Spring Physics:
 *  - GlassCircleButton (Small UI circular Liquid Glass orb with outward droplet bloom)
 *  - GlassPillButton (Small UI & Action refractive capsule pill, supporting colored glass lenses)
 *  - GlassPanelFrame (Continuous G2 superellipse refractive card / floating capsule)
 *  - GlassSegmentedCapsule (Velocity-stretching Liquid Glass droplet indicator)
 *  - IosSwitchView (Authentic 52x31dp iOS 26.1 Liquid Glass toggle switch)
 *  - IosBottomTabCapsuleBar (Unified 3-Tab + Detached Search Orb AND Classic 5-Tab modes)
 *  - IosSearchFieldBar (Refractive AGSL Liquid Glass search capsule + clear micro-orb)
 *  - AlphabetIndexScrubberView (Refractive A-Z track + floating Liquid Glass Magnifier Loupe)
 */
public final class DialerGlassWidgets {

    private DialerGlassWidgets() {}

    private static DialerGlassEngine.BackdropBuffers selectBackdrop(
            View view, DialerGlassEngine.LiveOpticalState opt) {
        if (opt == null) return null;
        View root = view != null ? view.getRootView() : null;
        View capturedRoot = opt.getCapturedRootView();
        if (root != null && root == capturedRoot && opt.activeBackdrop != null) {
            return opt.activeBackdrop;
        }
        return opt.getNeutralBackdrop();
    }

    public interface GlyphDrawer {
        void draw(Canvas canvas, float cx, float cy, float size, int color, Paint paint);
    }

    /**
     * Circular 90Hz AGSL Liquid Glass orb (`+`, `≡` Filter, `🔍` Search, `👤+` Add Contact, `✕` Close)
     * with unified `SpringFloat` outward droplet bloom & localized Gaussian touch refraction.
     */
    public static final class GlassCircleButton extends View {
        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mPressSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final int mSizePx;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] mLoc = new int[2];

        private GlyphDrawer mDrawer;
        private int mCustomTintColor = 0; // 0 = auto accent blue
        private int mGlassTintColor = 0;
        private float mGlassTintMix = 0f;
        private float mTouchX;
        private float mTouchY;
        private long mLastNanos = 0L;

        public GlassCircleButton(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                float diameterDp,
                GlyphDrawer drawer) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mSizePx = Math.round(diameterDp * mDensity);
            mDrawer = drawer;
            mTouchX = mSizePx * 0.5f;
            mTouchY = mSizePx * 0.5f;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.1f * mDensity);
            setClickable(true);
        }

        public void setGlyphColor(int color) {
            mCustomTintColor = color;
            invalidate();
        }

        public void setGlassTint(int colorArgb, float mix) {
            mGlassTintColor = colorArgb;
            mGlassTintMix = DialerGlassEngine.clampF(mix, 0f, 1f);
            invalidate();
        }

        public void setGlyphDrawer(GlyphDrawer drawer) {
            mDrawer = drawer;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Add subtle room for outward Liquid Glass droplet bloom without clipping
            int padRoom = Math.round(4f * mDensity);
            setMeasuredDimension(
                    resolveSize(mSizePx + padRoom, widthMeasureSpec),
                    resolveSize(mSizePx + padRoom, heightMeasureSpec));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null || !isEnabled()) return false;
            int action = event.getActionMasked();
            mTouchX = event.getX();
            mTouchY = event.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                mPressSpring.target = 1f;
                DialerGlassEngine.performKeyboardTap(this);
                kickSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                mPressSpring.target = 0f;
                kickSpring();
                if (inside) {
                    performClick();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                mPressSpring.target = 0f;
                kickSpring();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void kickSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSpring() {
            if (mLastNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastNanos = now;
            boolean active = mPressSpring.step(dt);
            if (!active) {
                mLastNanos = 0L;
            }
            return active;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 2 || h <= 2) return;
            boolean anim = stepSpring();

            float press = mPressSpring.value;
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float pad = 3.2f * mDensity;
            float baseR = Math.min(w, h) * 0.5f - pad;
            // Authentic iOS 26 Liquid Glass outward droplet bloom on touch
            float scale = 1.0f + 0.052f * press;
            boolean dark = mOpt != null && mOpt.darkTheme;

            canvas.save();
            canvas.scale(scale, scale, cx, cy);

            // Soft dual-layer optical elevation shadow
            mShadowPaint.setColor(Color.argb(dark ? 52 : 16, 0, 8, 24));
            canvas.drawCircle(cx, cy + 3.0f * mDensity, baseR * 1.02f, mShadowPaint);
            mShadowPaint.setColor(Color.argb(dark ? 78 : 28, 0, 8, 24));
            canvas.drawCircle(cx, cy + 1.5f * mDensity, baseR * 0.96f, mShadowPaint);

            getLocationOnScreen(mLoc);
            float d = baseR * 2f;
            float left = cx - baseR;
            float top = cy - baseR;
            float localTx = DialerGlassEngine.clampF(mTouchX - left, 0f, d);
            float localTy = DialerGlassEngine.clampF(mTouchY - top, 0f, d);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);

            mRimPaint.setStrokeWidth(0.6f * mDensity);
            boolean bound = mGlass.bindToPaintWithBuffers(
                    mPaint, mOpt, bd, d, d,
                    mLoc[0] + left, mLoc[1] + top,
                    baseR, mDensity, localTx, localTy, press,
                    (mGlassTintMix > 0.01f) ? 0.14f : (0.14f + 0.22f * press),
                    mGlassTintColor, mGlassTintMix, 0f, -1f, 0f);

            if (bound) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(canvas, left, top, d, d, mPaint);
            } else {
                mPaint.setShader(null);
                mPaint.setColor(dark ? Color.argb(210, 44, 44, 46) : Color.argb(235, 255, 255, 255));
                canvas.drawCircle(cx, cy, baseR, mPaint);
            }

            mRimPaint.setShader(null);
            mRimPaint.setColor(dark ? Color.argb(46, 255, 255, 255) : Color.argb(22, 0, 0, 0));
            canvas.drawCircle(cx, cy, baseR - 0.35f * mDensity, mRimPaint);

            if (mDrawer != null) {
                int glyphCol = (mCustomTintColor != 0)
                        ? mCustomTintColor
                        : ((mGlassTintMix > 0.45f) ? Color.WHITE : DialerGlassEngine.accentBlue(dark));
                mDrawer.draw(canvas, cx, cy, baseR * 0.96f, glyphCol, mGlyphPaint);
            }
            canvas.restore();

            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Rounded iOS 26.1 Liquid Glass Capsule Pill Button (`Edit`, `Done`, `Clear`, `SIM 1`,
     * `Call Voicemail`, `Touch to return to call`). Colored pills render as genuine tinted
     * AGSL Liquid Glass lenses rather than flat opaque fills!
     */
    public static final class GlassPillButton extends View {
        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mPressSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint.FontMetrics mFm = new Paint.FontMetrics();
        private final RectF mRect = new RectF();
        private final int[] mLoc = new int[2];

        private String mText;
        private int mCustomTextColor = 0;
        private int mSolidFillColor = 0;
        private int mGlassTintColor = 0;
        private float mGlassTintMix = 0f;
        private float mHeightDp = 36f;
        private float mTextSp = 15f;
        private boolean mBold = false;
        private float mTouchX = 0f;
        private float mTouchY = 0f;
        private long mLastNanos = 0L;

        public GlassPillButton(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                String text) {
            super(ctx);
            mOpt = opt;
            mText = text != null ? text : "";
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.05f * mDensity);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            updateTypography();
            setClickable(true);
        }

        public void setText(String text) {
            mText = text != null ? text : "";
            requestLayout();
            invalidate();
        }

        public String getText() {
            return mText;
        }

        public void setCustomTextColor(int color) {
            mCustomTextColor = color;
            invalidate();
        }

        public void setSolidFillColor(int color) {
            mSolidFillColor = color;
            invalidate();
        }

        public void setGlassTint(int colorArgb, float mix) {
            mGlassTintColor = colorArgb;
            mGlassTintMix = DialerGlassEngine.clampF(mix, 0f, 1f);
            invalidate();
        }

        public void setPillMetrics(float heightDp, float textSp, boolean bold) {
            mHeightDp = heightDp;
            mTextSp = textSp;
            mBold = bold;
            updateTypography();
            requestLayout();
            invalidate();
        }

        private void updateTypography() {
            mTextPaint.setTypeface(Typeface.create(
                    mBold ? "sans-serif-medium" : "sans-serif",
                    mBold ? Typeface.BOLD : Typeface.NORMAL));
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, mTextSp, getResources().getDisplayMetrics()));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float textW = mTextPaint.measureText(mText);
            int minW = Math.round((mHeightDp + 22f) * mDensity);
            int desiredW = Math.max(minW, Math.round(textW + 32f * mDensity));
            int desiredH = Math.round((mHeightDp + 4f) * mDensity);
            setMeasuredDimension(
                    resolveSize(desiredW, widthMeasureSpec),
                    resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null || !isEnabled()) return false;
            int action = event.getActionMasked();
            mTouchX = event.getX();
            mTouchY = event.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                mPressSpring.target = 1f;
                DialerGlassEngine.performKeyboardTap(this);
                kickSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                mPressSpring.target = 0f;
                kickSpring();
                if (inside) {
                    performClick();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                mPressSpring.target = 0f;
                kickSpring();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void kickSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSpring() {
            if (mLastNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastNanos = now;
            boolean active = mPressSpring.step(dt);
            if (!active) {
                mLastNanos = 0L;
            }
            return active;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;
            boolean anim = stepSpring();

            float press = mPressSpring.value;
            float pad = 2.6f * mDensity;
            mRect.set(pad, pad, w - pad, h - pad);
            float r = mRect.height() * 0.5f;
            boolean dark = mOpt != null && mOpt.darkTheme;
            boolean hasExplicitTint = mGlassTintMix > 0.01f;
            boolean hasColoredLens = hasExplicitTint || mSolidFillColor != 0;
            int tintCol = hasExplicitTint ? mGlassTintColor : mSolidFillColor;
            float tintMix = hasExplicitTint ? mGlassTintMix : (mSolidFillColor != 0 ? 0.74f : 0f);

            canvas.save();
            float sc = 1.0f + 0.042f * press;
            canvas.scale(sc, sc, w * 0.5f, h * 0.5f);

            int shadowCol = hasColoredLens
                    ? Color.argb(dark ? 88 : 50,
                            (tintCol >> 16) & 0x7F,
                            (tintCol >> 8) & 0x7F,
                            tintCol & 0x7F)
                    : Color.argb(dark ? 66 : 24, 0, 8, 22);
            mShadowPaint.setColor(shadowCol);
            canvas.drawRoundRect(
                    mRect.left, mRect.top + 1.8f * mDensity,
                    mRect.right, mRect.bottom + 1.8f * mDensity,
                    r, r, mShadowPaint);

            getLocationOnScreen(mLoc);
            float rw = mRect.width();
            float rh = mRect.height();
            float ltx = DialerGlassEngine.clampF(mTouchX - mRect.left, 0f, rw);
            float lty = DialerGlassEngine.clampF(mTouchY - mRect.top, 0f, rh);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);

            mRimPaint.setStrokeWidth(0.6f * mDensity);
            if (mGlass.bindToPaintWithBuffers(
                    mPaint, mOpt, bd, rw, rh,
                    mLoc[0] + mRect.left, mLoc[1] + mRect.top,
                    r, mDensity, ltx, lty, press,
                    hasColoredLens ? 0.18f : (0.14f + 0.20f * press),
                    tintCol, tintMix, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mRect.left, mRect.top, rw, rh, mPaint);
            } else {
                mPaint.setShader(null);
                mPaint.setColor(hasColoredLens
                        ? tintCol
                        : (dark ? Color.argb(210, 44, 44, 46) : Color.argb(238, 255, 255, 255)));
                canvas.drawRoundRect(mRect, r, r, mPaint);
            }

            mRimPaint.setShader(null);
            mRimPaint.setColor(hasColoredLens
                    ? Color.argb(54, 255, 255, 255)
                    : (dark ? Color.argb(46, 255, 255, 255) : Color.argb(22, 0, 0, 0)));
            canvas.drawRoundRect(mRect, r, r, mRimPaint);

            int txtCol = (mCustomTextColor != 0)
                    ? mCustomTextColor
                    : ((tintMix > 0.45f) ? Color.WHITE : DialerGlassEngine.accentBlue(dark));
            mTextPaint.setColor(txtCol);
            mTextPaint.getFontMetrics(mFm);
            float baseline = h * 0.5f - (mFm.ascent + mFm.descent) * 0.5f;
            canvas.drawText(mText, w * 0.5f, baseline, mTextPaint);

            canvas.restore();
            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Continuous-corner G2 superellipse AGSL Liquid Glass Card / Capsule FrameLayout.
     */
    public static class GlassPanelFrame extends FrameLayout {
        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final float mDensity;
        private final float mCornerRadiusPx;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mSquirclePath = new Path();
        private final int[] mLoc = new int[2];
        private float mTintBoost = 0.16f;

        public GlassPanelFrame(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                float cornerRadiusDp) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mCornerRadiusPx = cornerRadiusDp * mDensity;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.1f * mDensity);
            setWillNotDraw(false);
            setOutlineProvider(DialerGlassEngine.Squircle.outlineProvider(mCornerRadiusPx));
            setClipToOutline(true);
        }

        private boolean mFrameVisible = true;

        public void setTintBoost(float boost) {
            mTintBoost = boost;
            invalidate();
        }

        public void setFrameVisible(boolean visible) {
            if (mFrameVisible != visible) {
                mFrameVisible = visible;
                setClipToOutline(visible);
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (!mFrameVisible || w <= 4 || h <= 4 || (mOpt != null && mOpt.isCapturingScene())) {
                super.onDraw(canvas);
                return;
            }
            mRimPaint.setStrokeWidth(0.6f * mDensity);
            boolean dark = mOpt != null && mOpt.darkTheme;
            getLocationOnScreen(mLoc);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);
            if (mGlass.bindToPaintWithBuffers(
                    mPaint, mOpt, bd, (float) w, (float) h,
                    (float) mLoc[0], (float) mLoc[1],
                    mCornerRadiusPx, mDensity, w * 0.5f, h * 0.5f, 0f,
                    mTintBoost, 0, 0f, 0f, -1f, 0f)) {
                canvas.drawRect(0f, 0f, w, h, mPaint);
            } else {
                mPaint.setShader(null);
                mPaint.setColor(DialerGlassEngine.cardBg(dark));
                DialerGlassEngine.Squircle.buildPath(0f, 0f, w, h, mCornerRadiusPx, mSquirclePath);
                canvas.drawPath(mSquirclePath, mPaint);
            }
            DialerGlassEngine.Squircle.buildPath(
                    0.4f * mDensity, 0.4f * mDensity,
                    w - 0.4f * mDensity, h - 0.4f * mDensity,
                    mCornerRadiusPx, mSquirclePath);
            mRimPaint.setShader(null);
            mRimPaint.setColor(dark ? Color.argb(42, 255, 255, 255) : Color.argb(20, 0, 0, 0));
            canvas.drawPath(mSquirclePath, mRimPaint);
            super.onDraw(canvas);
        }
    }

    /**
     * Authentic iOS 26.1 Segmented Capsule (`All | Missed`, `Unified | Classic`) with
     * velocity-stretching Liquid Glass droplet indicator.
     */
    public static final class GlassSegmentedCapsule extends View {
        public interface OnSegmentListener {
            void onSelected(int index);
        }

        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final String[] mLabels;
        private final DialerGlassEngine.GlassSurfaceInstance mTrackGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mThumbGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mPosSpring;
        private final DialerGlassEngine.SpringFloat mLiftSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint.FontMetrics mFm = new Paint.FontMetrics();
        private final RectF mThumbRect = new RectF();
        private final int[] mLoc = new int[2];

        private int mSelected = 0;
        private boolean mTouchActive = false;
        private boolean mDragging = false;
        private float mDownX = 0f;
        private long mLastNanos = 0L;
        private OnSegmentListener mListener;

        public GlassSegmentedCapsule(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                String[] labels) {
            super(ctx);
            mOpt = opt;
            mLabels = labels;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mPosSpring = DialerGlassEngine.SpringFloat.morphSpring(0f, -0.15f, labels.length - 0.85f);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.05f * mDensity);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.5f, getResources().getDisplayMetrics()));
        }

        public void setOnSegmentListener(OnSegmentListener listener) {
            mListener = listener;
        }

        public int getSelectedIndex() {
            return mSelected;
        }

        public void setSelectedIndex(int idx, boolean notify) {
            int clean = DialerGlassEngine.clamp(idx, 0, mLabels.length - 1);
            if (clean != mSelected) {
                mSelected = clean;
                mPosSpring.target = clean;
                if (notify && mListener != null) {
                    mListener.onSelected(mSelected);
                }
                startSpring();
            } else if (!notify) {
                mPosSpring.snapTo(clean);
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredW = Math.round(174f * mDensity);
            int desiredH = Math.round(36f * mDensity);
            setMeasuredDimension(
                    resolveSize(desiredW, widthMeasureSpec),
                    resolveSize(desiredH, heightMeasureSpec));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                mTouchActive = true;
                mDragging = false;
                mDownX = event.getX();
                mLiftSpring.target = 1f;
                DialerGlassEngine.performTick(this, false);
                startSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (mTouchActive) {
                    if (!mDragging && Math.abs(event.getX() - mDownX) > 7f * mDensity) {
                        mDragging = true;
                    }
                    if (mDragging) {
                        scrubTo(event.getX(), false);
                        startSpring();
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (mTouchActive) {
                    mTouchActive = false;
                    mLiftSpring.target = 0f;
                    if (action == MotionEvent.ACTION_UP && !mDragging) {
                        scrubTo(event.getX(), true);
                    } else {
                        int snap = DialerGlassEngine.clamp(Math.round(mPosSpring.target), 0, mLabels.length - 1);
                        if (snap != mSelected) {
                            mSelected = snap;
                            if (mListener != null) {
                                mListener.onSelected(mSelected);
                            }
                        }
                        mPosSpring.target = mSelected;
                    }
                    mDragging = false;
                    DialerGlassEngine.performTick(this, true);
                    startSpring();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void scrubTo(float x, boolean snapSlotTarget) {
            float pad = 3.2f * mDensity;
            float slotW = Math.max(1f, (getWidth() - pad * 2f) / mLabels.length);
            float raw = DialerGlassEngine.clampF((x - pad) / slotW - 0.5f, 0f, mLabels.length - 1);
            int near = DialerGlassEngine.clamp(Math.round(raw), 0, mLabels.length - 1);
            if (near != mSelected) {
                mSelected = near;
                DialerGlassEngine.performTick(this, true);
                if (mListener != null) {
                    mListener.onSelected(mSelected);
                }
            }
            mPosSpring.target = snapSlotTarget ? near : raw;
        }

        private void startSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSpring() {
            if (mLastNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastNanos = now;
            boolean pMove = mPosSpring.step(dt);
            boolean lMove = mLiftSpring.step(dt);
            if (!mTouchActive && !pMove && !lMove) {
                mLastNanos = 0L;
                return false;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;
            boolean anim = stepSpring();

            float wf = (float) w;
            float hf = (float) h;
            float r = hf * 0.5f;
            boolean dark = mOpt != null && mOpt.darkTheme;
            float lift = mLiftSpring.value;
            float pillPos = mPosSpring.value;
            getLocationOnScreen(mLoc);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);

            if (mTrackGlass.bindToPaintWithBuffers(
                    mTrackPaint, mOpt, bd, wf, hf,
                    (float) mLoc[0], (float) mLoc[1],
                    r, mDensity, wf * 0.5f, hf * 0.5f, 0f,
                    dark ? -0.08f : -0.10f, 0, 0f, 0f, -1f, 0f)) {
                canvas.drawRect(0f, 0f, wf, hf, mTrackPaint);
            } else {
                mTrackPaint.setShader(null);
                mTrackPaint.setColor(dark ? 0xFF1C1C1E : 0xFFE3E3E8);
                canvas.drawRoundRect(0f, 0f, wf, hf, r, r, mTrackPaint);
            }

            float pad = 2.6f * mDensity;
            float slotW = (wf - pad * 2f) / mLabels.length;
            // Velocity-coupled viscous Liquid Glass droplet stretch
            float velStretch = Math.min(7.5f * mDensity, Math.abs(mPosSpring.velocity) * 1.45f * mDensity);
            float expX = 1.4f * mDensity * lift + velStretch;
            float expY = 0.8f * mDensity * lift - velStretch * 0.18f;
            mThumbRect.set(
                    pad + pillPos * slotW - expX,
                    pad - expY,
                    pad + (pillPos + 1f) * slotW + expX,
                    hf - pad + expY);
            float tr = mThumbRect.height() * 0.5f;

            mShadowPaint.setColor(Color.argb(dark ? 65 : 26, 0, 0, 0));
            canvas.drawRoundRect(
                    mThumbRect.left, mThumbRect.top + 1.2f * mDensity,
                    mThumbRect.right, mThumbRect.bottom + 1.2f * mDensity,
                    tr, tr, mShadowPaint);

            float tw = mThumbRect.width();
            float th = mThumbRect.height();
            if (mThumbGlass.bindToPaintWithBuffers(
                    mThumbPaint, mOpt, bd, tw, th,
                    mLoc[0] + mThumbRect.left, mLoc[1] + mThumbRect.top,
                    tr, mDensity, tw * 0.5f, th * 0.5f, 0.24f + 0.55f * lift,
                    0.36f, 0, 0f, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mThumbRect.left, mThumbRect.top, tw, th, mThumbPaint);
            } else {
                mThumbPaint.setShader(null);
                mThumbPaint.setColor(dark ? 0xFF3A3A3C : Color.WHITE);
                canvas.drawRoundRect(mThumbRect, tr, tr, mThumbPaint);
            }

            mRimPaint.setStrokeWidth(0.55f * mDensity);
            mRimPaint.setShader(null);
            mRimPaint.setColor(dark ? Color.argb(56, 255, 255, 255) : Color.argb(20, 0, 0, 0));
            canvas.drawRoundRect(mThumbRect, tr, tr, mRimPaint);

            mTextPaint.getFontMetrics(mFm);
            float baseline = hf * 0.5f - (mFm.ascent + mFm.descent) * 0.5f;
            for (int i = 0; i < mLabels.length; i++) {
                boolean active = Math.abs(i - pillPos) < 0.52f;
                mTextPaint.setColor(active
                        ? DialerGlassEngine.primaryInk(dark)
                        : DialerGlassEngine.secondaryInk(dark));
                canvas.drawText(mLabels[i], pad + (i + 0.5f) * slotW, baseline, mTextPaint);
            }

            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Authentic `52dp × 31dp` iOS 26.1 Liquid Glass Toggle Switch (`#34C759` track + refractive droplet thumb).
     */
    public static final class IosSwitchView extends View {
        public interface OnCheckedChangeListener {
            void onCheckedChanged(boolean checked);
        }

        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mThumbGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mPosSpring;
        private final DialerGlassEngine.SpringFloat mPressSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LinearGradient mThumbRimGrad = new LinearGradient(
                0f, 0f, 1f, 1f,
                new int[]{0xEEFFFFFF, 0x33FFFFFF, 0xAAFFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        private final Matrix mGradMatrix = new Matrix();
        private final RectF mTrackRect = new RectF();
        private final RectF mThumbRect = new RectF();
        private final int[] mLoc = new int[2];

        private boolean mChecked;
        private long mLastNanos = 0L;
        private OnCheckedChangeListener mListener;

        public IosSwitchView(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                boolean initialChecked) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mChecked = initialChecked;
            mPosSpring = DialerGlassEngine.SpringFloat.morphSpring(initialChecked ? 1f : 0f, 0f, 1f);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.0f * mDensity);
            setClickable(true);
        }

        public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
            mListener = listener;
        }

        public boolean isChecked() {
            return mChecked;
        }

        public void setChecked(boolean checked, boolean notify) {
            if (mChecked != checked) {
                mChecked = checked;
                mPosSpring.target = checked ? 1f : 0f;
                kickSpring();
                if (notify && mListener != null) {
                    mListener.onCheckedChanged(mChecked);
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    resolveSize(Math.round(54f * mDensity), widthMeasureSpec),
                    resolveSize(Math.round(33f * mDensity), heightMeasureSpec));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null || !isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mPressSpring.target = 1f;
                DialerGlassEngine.performTick(this, false);
                kickSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                mPressSpring.target = 0f;
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= 0 && event.getY() <= getHeight();
                if (inside) {
                    DialerGlassEngine.performTick(this, true);
                    setChecked(!mChecked, true);
                    performClick();
                } else {
                    kickSpring();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                mPressSpring.target = 0f;
                kickSpring();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void kickSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSpring() {
            if (mLastNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastNanos = now;
            boolean a = mPosSpring.step(dt);
            boolean b = mPressSpring.step(dt);
            if (!a && !b) {
                mLastNanos = 0L;
                return false;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) return;
            boolean anim = stepSpring();

            boolean dark = mOpt != null && mOpt.darkTheme;
            float t = DialerGlassEngine.clampF(mPosSpring.value, 0f, 1f);
            float press = mPressSpring.value;

            float pad = 1.0f * mDensity;
            mTrackRect.set(pad, pad, w - pad, h - pad);
            float tr = mTrackRect.height() * 0.5f;

            int offCol = dark ? 0xFF39393D : 0xFFD8D8DF;
            int onCol = DialerGlassEngine.IOS_GREEN;
            mTrackPaint.setColor(blendArgb(offCol, onCol, t));
            canvas.drawRoundRect(mTrackRect, tr, tr, mTrackPaint);

            float inset = 2.2f * mDensity;
            float baseKnobW = mTrackRect.height() - inset * 2f;
            float stretch = 5.5f * mDensity * press + Math.min(3.5f * mDensity, Math.abs(mPosSpring.velocity) * 0.7f * mDensity);
            float knobW = baseKnobW + stretch;
            float knobH = baseKnobW;
            float minLeft = mTrackRect.left + inset;
            float maxLeft = mTrackRect.right - inset - knobW;
            float left = minLeft + t * Math.max(0f, maxLeft - minLeft);
            float top = mTrackRect.centerY() - knobH * 0.5f;
            mThumbRect.set(left, top, left + knobW, top + knobH);
            float kr = knobH * 0.5f;

            mShadowPaint.setColor(Color.argb(66, 0, 8, 20));
            canvas.drawRoundRect(
                    mThumbRect.left, mThumbRect.top + 1.5f * mDensity,
                    mThumbRect.right, mThumbRect.bottom + 1.5f * mDensity,
                    kr, kr, mShadowPaint);

            getLocationOnScreen(mLoc);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);
            boolean dropletActive = press > 0.04f || Math.abs(mPosSpring.velocity) > 0.05f;
            if (dropletActive && mThumbGlass.bindToPaintWithBuffers(
                    mThumbPaint, mOpt, bd, knobW, knobH,
                    mLoc[0] + mThumbRect.left, mLoc[1] + mThumbRect.top,
                    kr, mDensity, knobW * 0.5f, knobH * 0.5f, press,
                    0.45f, 0, 0f, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mThumbRect.left, mThumbRect.top, knobW, knobH, mThumbPaint);
            } else {
                mThumbPaint.setShader(null);
                mThumbPaint.setColor(Color.WHITE);
                canvas.drawRoundRect(mThumbRect, kr, kr, mThumbPaint);
            }

            mGradMatrix.setScale(Math.max(1f, knobW), Math.max(1f, knobH));
            mGradMatrix.postTranslate(mThumbRect.left, mThumbRect.top);
            mThumbRimGrad.setLocalMatrix(mGradMatrix);
            mRimPaint.setShader(mThumbRimGrad);
            canvas.drawRoundRect(mThumbRect, kr, kr, mRimPaint);
            mRimPaint.setShader(null);

            if (anim) {
                postInvalidateOnAnimation();
            }
        }

        private static int blendArgb(int c1, int c2, float f) {
            int a = Math.round(Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * f);
            int r = Math.round(Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f);
            int g = Math.round(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f);
            int b = Math.round(Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f);
            return Color.argb(a, r, g, b);
        }
    }

    /**
     * Floating iOS 26.1 Liquid Glass Bottom Tab Bar supporting BOTH:
     *  1. **Unified Mode (Default)**: `3`-tab Liquid Glass capsule (`Calls | Contacts | Keypad`) on left
     *     + **detached circular `🔍` Search Liquid Glass Orb** on right!
     *  2. **Classic Mode**: Full-width `5`-tab Liquid Glass capsule (`Favourites | Recents | Contacts | Keypad | Voicemail`).
     */
    public static final class IosBottomTabCapsuleBar extends View {
        public interface OnTabSelectedListener {
            void onTabSelected(int canonicalTabIndex);
        }

        public interface OnSearchOrbClickListener {
            void onSearchOrbClicked();
        }

        private static final String[] CLASSIC_LABELS = {
                "Favorites", "Recents", "Contacts", "Keypad", "Voicemail"
        };
        private static final int[] CLASSIC_CANONICAL = {
                IosVectorIcons.TAB_FAVORITES,
                IosVectorIcons.TAB_RECENTS,
                IosVectorIcons.TAB_CONTACTS,
                IosVectorIcons.TAB_KEYPAD,
                IosVectorIcons.TAB_VOICEMAIL
        };
        private static final int[] CLASSIC_ICONS = {
                IosVectorIcons.TAB_FAVORITES,
                IosVectorIcons.TAB_RECENTS,
                IosVectorIcons.TAB_CONTACTS,
                IosVectorIcons.TAB_KEYPAD,
                IosVectorIcons.TAB_VOICEMAIL
        };

        private static final String[] UNIFIED_LABELS = {
                "Calls", "Contacts", "Keypad"
        };
        private static final int[] UNIFIED_CANONICAL = {
                IosVectorIcons.TAB_RECENTS,
                IosVectorIcons.TAB_CONTACTS,
                IosVectorIcons.TAB_KEYPAD
        };
        private static final int[] UNIFIED_ICONS = {
                IosVectorIcons.TAB_UNIFIED_CALLS,
                IosVectorIcons.TAB_CONTACTS,
                IosVectorIcons.TAB_KEYPAD
        };

        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mBarGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mPillGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mSearchOrbGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mPillSpring =
                new DialerGlassEngine.SpringFloat(3f, 330f, 24.5f, -0.25f, 4.25f);
        private final DialerGlassEngine.SpringFloat mLiftSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mSearchOrbSpring =
                DialerGlassEngine.SpringFloat.pressSpring();

        private final float mDensity;
        private final Paint mShellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint.FontMetrics mBadgeFm = new Paint.FontMetrics();
        private final LinearGradient mShellRimGradDark = new LinearGradient(
                0f, 0f, 0f, 1f, Color.argb(62, 255, 255, 255), Color.argb(18, 0, 0, 0), Shader.TileMode.CLAMP);
        private final LinearGradient mShellRimGradLight = new LinearGradient(
                0f, 0f, 0f, 1f, Color.argb(200, 255, 255, 255), Color.argb(22, 0, 0, 0), Shader.TileMode.CLAMP);
        private final LinearGradient mPillRimGradDark = new LinearGradient(
                0f, 0f, 0f, 1f, Color.argb(95, 255, 255, 255), Color.argb(24, 0, 0, 0), Shader.TileMode.CLAMP);
        private final LinearGradient mPillRimGradLight = new LinearGradient(
                0f, 0f, 0f, 1f, Color.argb(235, 255, 255, 255), Color.argb(18, 0, 0, 0), Shader.TileMode.CLAMP);
        private final Matrix mGradMatrix = new Matrix();
        private final RectF mShellRect = new RectF();
        private final RectF mPillRect = new RectF();
        private final RectF mSearchOrbRect = new RectF();
        private final RectF mBadgeRect = new RectF();
        private final int[] mLoc = new int[2];

        private boolean mUnifiedMode = false;
        private int mSelectedCanonicalTab = IosVectorIcons.TAB_KEYPAD;
        private boolean mTouchOnBar = false;
        private boolean mScrubbingBar = false;
        private boolean mSearchOrbDown = false;
        private float mDownX = 0f;
        private float mTouchX = 0f;
        private float mTouchY = 0f;
        private int mMissedBadgeCount = 0;
        private String mMissedBadgeText = "";
        private long mLastNanos = 0L;
        private OnTabSelectedListener mListener;
        private OnSearchOrbClickListener mSearchListener;

        public IosBottomTabCapsuleBar(Context ctx, DialerGlassEngine.LiveOpticalState opt) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.15f * mDensity);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            mLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mLabelPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 10.2f, getResources().getDisplayMetrics()));
            mBadgePaint.setColor(DialerGlassEngine.IOS_RED);
            mBadgeTextPaint.setColor(Color.WHITE);
            mBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
            mBadgeTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mBadgeTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 9.5f, getResources().getDisplayMetrics()));
            syncSlotFromCanonical(false);
        }

        public void setOnTabSelectedListener(OnTabSelectedListener listener) {
            mListener = listener;
        }

        public void setOnSearchOrbClickListener(OnSearchOrbClickListener listener) {
            mSearchListener = listener;
        }

        public boolean isUnifiedMode() {
            return mUnifiedMode;
        }

        public void setUnifiedMode(boolean unified) {
            if (mUnifiedMode != unified) {
                mUnifiedMode = unified;
                if (unified && (mSelectedCanonicalTab == IosVectorIcons.TAB_FAVORITES
                        || mSelectedCanonicalTab == IosVectorIcons.TAB_VOICEMAIL)) {
                    mSelectedCanonicalTab = IosVectorIcons.TAB_RECENTS;
                }
                syncSlotFromCanonical(false);
                invalidate();
            }
        }

        public int getSelectedTab() {
            return mSelectedCanonicalTab;
        }

        public void setSelectedTab(int canonicalTab, boolean animate) {
            mSelectedCanonicalTab = DialerGlassEngine.clamp(canonicalTab, 0, 4);
            syncSlotFromCanonical(animate);
        }

        private int slotCount() {
            return mUnifiedMode ? UNIFIED_LABELS.length : CLASSIC_LABELS.length;
        }

        private int canonicalToSlot(int canonical) {
            if (!mUnifiedMode) {
                return DialerGlassEngine.clamp(canonical, 0, CLASSIC_LABELS.length - 1);
            }
            for (int i = 0; i < UNIFIED_CANONICAL.length; i++) {
                if (UNIFIED_CANONICAL[i] == canonical) return i;
            }
            return 0; // Favorites / Voicemail collapse under Calls in Unified mode
        }

        private int slotToCanonical(int slot) {
            int clean = DialerGlassEngine.clamp(slot, 0, slotCount() - 1);
            return mUnifiedMode ? UNIFIED_CANONICAL[clean] : CLASSIC_CANONICAL[clean];
        }

        private void syncSlotFromCanonical(boolean animate) {
            int slot = canonicalToSlot(mSelectedCanonicalTab);
            if (!animate) {
                mPillSpring.snapTo(slot);
                invalidate();
            } else {
                mPillSpring.target = slot;
                startSpring();
            }
        }

        public void setMissedBadgeCount(int count) {
            int clean = Math.max(0, count);
            if (mMissedBadgeCount != clean) {
                mMissedBadgeCount = clean;
                mMissedBadgeText = clean > 99 ? "99+" : (clean > 0 ? String.valueOf(clean) : "");
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = resolveSize(Math.round(70f * mDensity), heightMeasureSpec);
            setMeasuredDimension(w, h);
        }

        private void updateBounds(int w, int h) {
            float mx = 4f * mDensity;
            float my = 4f * mDensity;
            float barH = h - my * 2f - 2f * mDensity;
            if (mUnifiedMode) {
                float orbDia = barH;
                float gap = 10f * mDensity;
                float orbRight = w - mx;
                float orbLeft = orbRight - orbDia;
                mSearchOrbRect.set(orbLeft, my, orbRight, my + barH);
                mShellRect.set(mx, my, orbLeft - gap, my + barH);
            } else {
                mSearchOrbRect.setEmpty();
                mShellRect.set(mx, my, w - mx, my + barH);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            updateBounds(getWidth(), getHeight());
            int action = event.getActionMasked();
            mTouchX = event.getX();
            mTouchY = event.getY();

            if (action == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mUnifiedMode && hitSearchOrb(mTouchX, mTouchY)) {
                    mSearchOrbDown = true;
                    mSearchOrbSpring.target = 1f;
                    DialerGlassEngine.performKeyboardTap(this);
                    startSpring();
                    return true;
                }
                mTouchOnBar = true;
                mScrubbingBar = false;
                mDownX = mTouchX;
                mLiftSpring.target = 1f;
                DialerGlassEngine.performTick(this, false);
                startSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (mSearchOrbDown) {
                    invalidate();
                    return true;
                }
                if (mTouchOnBar) {
                    if (!mScrubbingBar && Math.abs(mTouchX - mDownX) > 6f * mDensity) {
                        mScrubbingBar = true;
                    }
                    if (mScrubbingBar) {
                        scrubToBarTouch(mTouchX);
                        startSpring();
                    } else {
                        invalidate();
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (mSearchOrbDown) {
                    boolean fire = (action == MotionEvent.ACTION_UP) && hitSearchOrb(mTouchX, mTouchY);
                    mSearchOrbDown = false;
                    mSearchOrbSpring.target = 0f;
                    startSpring();
                    if (fire && mSearchListener != null) {
                        DialerGlassEngine.performTick(this, true);
                        mSearchListener.onSearchOrbClicked();
                    }
                    return true;
                }
                if (mTouchOnBar) {
                    mTouchOnBar = false;
                    mLiftSpring.target = 0f;
                    if (action == MotionEvent.ACTION_UP) {
                        int targetSlot = mScrubbingBar
                                ? DialerGlassEngine.clamp(Math.round(mPillSpring.target), 0, slotCount() - 1)
                                : computeSlotAtX(mTouchX);
                        int canonical = slotToCanonical(targetSlot);
                        if (canonical != mSelectedCanonicalTab) {
                            mSelectedCanonicalTab = canonical;
                            DialerGlassEngine.performTick(this, true);
                            if (mListener != null) {
                                mListener.onTabSelected(mSelectedCanonicalTab);
                            }
                        }
                        mPillSpring.target = targetSlot;
                    } else {
                        mPillSpring.target = canonicalToSlot(mSelectedCanonicalTab);
                    }
                    mScrubbingBar = false;
                    startSpring();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        private boolean hitSearchOrb(float x, float y) {
            if (mSearchOrbRect.isEmpty()) return false;
            float r = mSearchOrbRect.width() * 0.56f;
            float dx = x - mSearchOrbRect.centerX();
            float dy = y - mSearchOrbRect.centerY();
            return dx * dx + dy * dy <= r * r;
        }

        private float computeRawSlot(float x) {
            int n = slotCount();
            float innerPadX = 6.5f * mDensity;
            float usableW = Math.max(1f, mShellRect.width() - innerPadX * 2f);
            float slotW = usableW / n;
            return DialerGlassEngine.clampF(
                    (x - mShellRect.left - innerPadX) / slotW - 0.5f, 0f, n - 1);
        }

        private int computeSlotAtX(float x) {
            return DialerGlassEngine.clamp(Math.round(computeRawSlot(x)), 0, slotCount() - 1);
        }

        private void scrubToBarTouch(float x) {
            float raw = computeRawSlot(x);
            int nearSlot = DialerGlassEngine.clamp(Math.round(raw), 0, slotCount() - 1);
            int nearCanonical = slotToCanonical(nearSlot);
            if (nearCanonical != mSelectedCanonicalTab) {
                mSelectedCanonicalTab = nearCanonical;
                DialerGlassEngine.performTick(this, true);
                if (mListener != null) {
                    mListener.onTabSelected(mSelectedCanonicalTab);
                }
            }
            mPillSpring.target = raw;
        }

        private void startSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean stepSpring() {
            if (mLastNanos == 0L) return false;
            long now = System.nanoTime();
            float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
            mLastNanos = now;

            boolean pMove = mPillSpring.step(dt);
            boolean lMove = mLiftSpring.step(dt);
            boolean sMove = mSearchOrbSpring.step(dt);
            if (!mTouchOnBar && !mSearchOrbDown && !pMove && !lMove && !sMove) {
                mLastNanos = 0L;
                return false;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 10 || h <= 10) return;

            updateBounds(w, h);
            boolean anim = stepSpring();
            boolean dark = mOpt != null && mOpt.darkTheme;
            float lift = mLiftSpring.value;
            float pillIndex = mPillSpring.value;
            float shellR = mShellRect.height() * 0.5f;
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);

            mRimPaint.setStrokeWidth(0.65f * mDensity);

            // 1. Soft optical elevation shadow beneath floating Liquid Glass capsule
            mShadowPaint.setColor(Color.argb(dark ? 95 : 26, 0, 6, 18));
            canvas.drawRoundRect(
                    mShellRect.left + 2f * mDensity,
                    mShellRect.top + 3.2f * mDensity,
                    mShellRect.right - 2f * mDensity,
                    mShellRect.bottom + 3.2f * mDensity,
                    shellR, shellR, mShadowPaint);

            getLocationOnScreen(mLoc);
            float sw = mShellRect.width();
            float sh = mShellRect.height();
            if (mBarGlass.bindToPaintWithBuffers(
                    mShellPaint, mOpt, bd, sw, sh,
                    mLoc[0] + mShellRect.left, mLoc[1] + mShellRect.top,
                    shellR, mDensity, mTouchX - mShellRect.left, mTouchY - mShellRect.top,
                    lift * 0.45f, 0.12f, 0, 0f, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mShellRect.left, mShellRect.top, sw, sh, mShellPaint);
            } else {
                mShellPaint.setShader(null);
                mShellPaint.setColor(dark ? Color.argb(228, 28, 28, 30) : Color.argb(238, 246, 247, 251));
                canvas.drawRoundRect(mShellRect, shellR, shellR, mShellPaint);
            }

            // Subtle neutral specular edge on outer capsule (zero cartoon cyan tint)
            LinearGradient shellGrad = dark ? mShellRimGradDark : mShellRimGradLight;
            mGradMatrix.setScale(1f, Math.max(1f, sh));
            mGradMatrix.postTranslate(mShellRect.left, mShellRect.top);
            shellGrad.setLocalMatrix(mGradMatrix);
            mRimPaint.setShader(shellGrad);
            canvas.drawRoundRect(mShellRect, shellR, shellR, mRimPaint);

            // 2. Sliding Velocity-Stretching Liquid Glass Active Tab Droplet
            int n = slotCount();
            String[] labels = mUnifiedMode ? UNIFIED_LABELS : CLASSIC_LABELS;
            int[] iconIds = mUnifiedMode ? UNIFIED_ICONS : CLASSIC_ICONS;
            float innerPadX = 6.0f * mDensity;
            float innerPadY = 4.2f * mDensity;
            float slotW = (sw - innerPadX * 2f) / n;
            float velStretch = Math.min(13.5f * mDensity, Math.abs(mPillSpring.velocity) * 2.15f * mDensity);
            float expX = 3.0f * mDensity * lift + velStretch;
            float expY = 1.8f * mDensity * lift - velStretch * 0.18f;
            mPillRect.set(
                    mShellRect.left + innerPadX + pillIndex * slotW - expX,
                    mShellRect.top + innerPadY - expY,
                    mShellRect.left + innerPadX + (pillIndex + 1f) * slotW + expX,
                    mShellRect.bottom - innerPadY + expY);
            float pillR = mPillRect.height() * 0.5f;

            float pw = mPillRect.width();
            float ph = mPillRect.height();
            int activeBlue = DialerGlassEngine.accentBlue(dark);
            float pillBoost = dark ? 0.26f : 0.34f;
            if (mOpt != null && mPillGlass.bindToPaintWithBuffers(
                    mPillPaint, mOpt, bd, pw, ph,
                    mLoc[0] + mPillRect.left, mLoc[1] + mPillRect.top,
                    pillR, mDensity, pw * 0.5f, ph * 0.5f, 0.30f + 0.70f * lift,
                    pillBoost, 0, 0f, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mPillRect.left, mPillRect.top, pw, ph, mPillPaint);
            } else {
                mPillPaint.setShader(null);
                mPillPaint.setColor(dark ? Color.argb(195, 58, 58, 64) : Color.argb(225, 232, 238, 248));
                canvas.drawRoundRect(mPillRect, pillR, pillR, mPillPaint);
            }

            LinearGradient pillGrad = dark ? mPillRimGradDark : mPillRimGradLight;
            mGradMatrix.setScale(1f, Math.max(1f, ph));
            mGradMatrix.postTranslate(mPillRect.left, mPillRect.top);
            pillGrad.setLocalMatrix(mGradMatrix);
            mRimPaint.setShader(pillGrad);
            canvas.drawRoundRect(mPillRect, pillR, pillR, mRimPaint);

            // 3. Draw Solid SF-Symbols Tab Icons + Labels with smooth Liquid Glass color crossfade
            int inactiveCol = dark ? Color.argb(225, 174, 174, 178) : Color.argb(235, 24, 24, 28);
            float iconSize = 21.0f * mDensity;
            float iconCy = mShellRect.top + sh * 0.36f;
            float labelBaseline = mShellRect.bottom - 10.8f * mDensity;

            for (int i = 0; i < n; i++) {
                float cx = mShellRect.left + innerPadX + (i + 0.5f) * slotW;
                float focusFrac = DialerGlassEngine.clampF(1f - Math.abs(i - pillIndex), 0f, 1f);
                int col = DialerGlassEngine.blendArgb(inactiveCol, activeBlue, focusFrac);
                DialerGlassEngine.drawZeroAllocTabIcon(
                        canvas, iconIds[i], cx, iconCy, iconSize, col, mIconPaint);
                mLabelPaint.setColor(col);
                canvas.drawText(labels[i], cx, labelBaseline, mLabelPaint);

                boolean isCallsSlot = mUnifiedMode ? (i == 0) : (i == IosVectorIcons.TAB_RECENTS);
                if (isCallsSlot && mMissedBadgeCount > 0) {
                    String bTxt = mMissedBadgeText;
                    float bw = Math.max(15f * mDensity, mBadgeTextPaint.measureText(bTxt) + 8f * mDensity);
                    float bh = 15f * mDensity;
                    float bx = cx + 9f * mDensity;
                    float by = iconCy - 10.5f * mDensity;
                    mBadgeRect.set(bx - bw * 0.5f, by - bh * 0.5f, bx + bw * 0.5f, by + bh * 0.5f);
                    canvas.drawRoundRect(mBadgeRect, bh * 0.5f, bh * 0.5f, mBadgePaint);
                    mBadgeTextPaint.getFontMetrics(mBadgeFm);
                    canvas.drawText(bTxt, bx, by - (mBadgeFm.ascent + mBadgeFm.descent) * 0.5f, mBadgeTextPaint);
                }
            }

            // 4. Detached Circular iOS 26.1 `🔍` Search Liquid Glass Orb (in Unified Mode)
            if (mUnifiedMode && !mSearchOrbRect.isEmpty()) {
                float sPress = mSearchOrbSpring.value;
                float ocx = mSearchOrbRect.centerX();
                float ocy = mSearchOrbRect.centerY();
                float or = mSearchOrbRect.width() * 0.5f;
                float osc = 1.0f + 0.055f * sPress;

                canvas.save();
                canvas.scale(osc, osc, ocx, ocy);

                mShadowPaint.setColor(Color.argb(dark ? 95 : 26, 0, 6, 18));
                canvas.drawCircle(ocx, ocy + 3.0f * mDensity, or * 0.96f, mShadowPaint);

                float od = mSearchOrbRect.width();
                if (mSearchOrbGlass.bindToPaintWithBuffers(
                        mShellPaint, mOpt, bd, od, od,
                        mLoc[0] + mSearchOrbRect.left, mLoc[1] + mSearchOrbRect.top,
                        or, mDensity,
                        DialerGlassEngine.clampF(mTouchX - mSearchOrbRect.left, 0f, od),
                        DialerGlassEngine.clampF(mTouchY - mSearchOrbRect.top, 0f, od),
                        sPress, 0.16f + 0.24f * sPress, 0, 0f, 0f, -1f, 0f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mSearchOrbRect.left, mSearchOrbRect.top, od, od, mShellPaint);
                } else {
                    mShellPaint.setShader(null);
                    mShellPaint.setColor(dark ? Color.argb(228, 28, 28, 30) : Color.argb(238, 246, 247, 251));
                    canvas.drawCircle(ocx, ocy, or, mShellPaint);
                }

                mGradMatrix.setScale(1f, Math.max(1f, mSearchOrbRect.height()));
                mGradMatrix.postTranslate(mSearchOrbRect.left, mSearchOrbRect.top);
                shellGrad.setLocalMatrix(mGradMatrix);
                mRimPaint.setShader(shellGrad);
                canvas.drawCircle(ocx, ocy, or - 0.35f * mDensity, mRimPaint);

                IosVectorIcons.drawSearchMagnifier(
                        canvas, ocx, ocy, 20f * mDensity,
                        sPress > 0.2f ? activeBlue : DialerGlassEngine.primaryInk(dark),
                        mIconPaint);
                canvas.restore();
            }

            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Refractive 90Hz AGSL iOS 26.1 Liquid Glass Search Field Bar (`🔍 Search` + `⊗` clear orb).
     */
    public static final class IosSearchFieldBar extends LinearLayout {
        public interface OnQueryChangeListener {
            void onQueryChanged(String query);
        }

        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mFocusSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final EditText mEditText;
        private final View mClearBtn;
        private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int[] mLoc = new int[2];
        private long mLastNanos = 0L;
        private OnQueryChangeListener mListener;

        public IosSearchFieldBar(
                Context ctx,
                DialerGlassEngine.LiveOpticalState opt,
                String hintText) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setWillNotDraw(false);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.05f * mDensity);
            int padH = Math.round(13f * mDensity);
            setPadding(padH, 0, padH, 0);

            View magView = new View(ctx) {
                private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    boolean dark = mOpt != null && mOpt.darkTheme;
                    IosVectorIcons.drawSearchMagnifier(
                            c, getWidth() * 0.5f, getHeight() * 0.5f,
                            16.5f * mDensity, DialerGlassEngine.secondaryInk(dark), mP);
                }
            };
            addView(magView, new LayoutParams(Math.round(22f * mDensity), Math.round(22f * mDensity)));

            mEditText = new EditText(ctx);
            mEditText.setBackground(null);
            mEditText.setHint(hintText != null ? hintText : "Search");
            mEditText.setSingleLine(true);
            mEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            mEditText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            mEditText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
            int padText = Math.round(7f * mDensity);
            mEditText.setPadding(padText, 0, padText, 0);
            mEditText.setOnFocusChangeListener((v, hasFocus) -> {
                mFocusSpring.target = hasFocus ? 1f : 0f;
                if (mLastNanos == 0L) {
                    mLastNanos = System.nanoTime();
                    postInvalidateOnAnimation();
                } else {
                    invalidate();
                }
            });
            LayoutParams etLp = new LayoutParams(0, Math.round(38f * mDensity), 1f);
            addView(mEditText, etLp);

            mClearBtn = new View(ctx) {
                private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    boolean dark = mOpt != null && mOpt.darkTheme;
                    float cx = getWidth() * 0.5f;
                    float cy = getHeight() * 0.5f;
                    float r = 8.4f * mDensity;
                    mP.setStyle(Paint.Style.FILL);
                    mP.setColor(DialerGlassEngine.secondaryInk(dark));
                    c.drawCircle(cx, cy, r, mP);
                    mP.setColor(dark ? 0xFF1C1C1E : Color.WHITE);
                    mP.setStyle(Paint.Style.STROKE);
                    mP.setStrokeWidth(1.65f * mDensity);
                    mP.setStrokeCap(Paint.Cap.ROUND);
                    float d = 3.4f * mDensity;
                    c.drawLine(cx - d, cy - d, cx + d, cy + d, mP);
                    c.drawLine(cx + d, cy - d, cx - d, cy + d, mP);
                }
            };
            mClearBtn.setVisibility(GONE);
            mClearBtn.setOnClickListener(v -> {
                DialerGlassEngine.performKeyboardTap(v);
                mEditText.setText("");
                hideKeyboard();
            });
            addView(mClearBtn, new LayoutParams(Math.round(24f * mDensity), Math.round(24f * mDensity)));

            mEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    String q = s != null ? s.toString() : "";
                    mClearBtn.setVisibility(q.isEmpty() ? GONE : VISIBLE);
                    if (mListener != null) {
                        mListener.onQueryChanged(q);
                    }
                }
            });

            refreshTheme();
        }

        public void setOnQueryChangeListener(OnQueryChangeListener listener) {
            mListener = listener;
        }

        public String getQuery() {
            CharSequence cs = mEditText.getText();
            return cs != null ? cs.toString() : "";
        }

        public void setQuery(String text) {
            mEditText.setText(text != null ? text : "");
            if (text != null) {
                mEditText.setSelection(mEditText.getText().length());
            }
        }

        public void focusAndShowKeyboard() {
            mEditText.requestFocus();
            mEditText.postDelayed(() -> {
                try {
                    InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
                    if (imm != null) {
                        imm.showSoftInput(mEditText, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) {
                }
            }, 60L);
        }

        public void clearAndHideKeyboard() {
            mEditText.setText("");
            hideKeyboard();
        }

        public void refreshTheme() {
            boolean dark = mOpt != null && mOpt.darkTheme;
            mEditText.setTextColor(DialerGlassEngine.primaryInk(dark));
            mEditText.setHintTextColor(DialerGlassEngine.secondaryInk(dark));
            invalidate();
        }

        private void hideKeyboard() {
            try {
                InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
                }
                mEditText.clearFocus();
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) {
                return;
            }
            super.dispatchDraw(canvas);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 4 || h <= 4) {
                super.onDraw(canvas);
                return;
            }
            boolean anim = false;
            if (mLastNanos != 0L) {
                long now = System.nanoTime();
                float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
                mLastNanos = now;
                anim = mFocusSpring.step(dt);
                if (!anim) mLastNanos = 0L;
            }

            boolean dark = mOpt != null && mOpt.darkTheme;
            float focus = mFocusSpring.value;
            float pad = 1.2f * mDensity;
            mRect.set(pad, pad, w - pad, h - pad);
            float r = mRect.height() * 0.5f;

            mShadowPaint.setColor(Color.argb(dark ? 58 : 20, 0, 8, 22));
            canvas.drawRoundRect(
                    mRect.left, mRect.top + 1.4f * mDensity,
                    mRect.right, mRect.bottom + 1.4f * mDensity,
                    r, r, mShadowPaint);

            mRimPaint.setStrokeWidth(0.55f * mDensity);
            getLocationOnScreen(mLoc);
            float rw = mRect.width();
            float rh = mRect.height();
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);
            if (mGlass.bindToPaintWithBuffers(
                    mBgPaint, mOpt, bd, rw, rh,
                    mLoc[0] + mRect.left, mLoc[1] + mRect.top,
                    r, mDensity, rw * 0.5f, rh * 0.5f, focus * 0.35f,
                    0.14f + 0.18f * focus, 0, 0f, 0f, -1f, 0f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mRect.left, mRect.top, rw, rh, mBgPaint);
            } else {
                mBgPaint.setShader(null);
                mBgPaint.setColor(DialerGlassEngine.searchBarFill(dark));
                canvas.drawRoundRect(mRect, r, r, mBgPaint);
            }

            mRimPaint.setShader(null);
            mRimPaint.setColor(dark ? Color.argb(38, 255, 255, 255) : Color.argb(14, 0, 0, 0));
            canvas.drawRoundRect(mRect, r, r, mRimPaint);

            super.onDraw(canvas);
            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }

    /**
     * Interactive right-edge iOS 26.1 `A–Z #` Alphabet Index Scrubber with AGSL Liquid Glass
     * track pill AND floating circular Liquid Glass Letter Loupe orb!
     */
    public static final class AlphabetIndexScrubberView extends View {
        public interface OnLetterScrubbedListener {
            void onLetterScrubbed(String letter);
        }

        private static final String[] LETTERS = {
                "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
                "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
        };

        private final DialerGlassEngine.LiveOpticalState mOpt;
        private final DialerGlassEngine.GlassSurfaceInstance mTrackGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mLoupeGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mShowSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final float mDensity;
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mLoupeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint.FontMetrics mFm = new Paint.FontMetrics();
        private final Paint.FontMetrics mLoupeFm = new Paint.FontMetrics();
        private final Paint mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LinearGradient mLoupeRimGrad = new LinearGradient(
                0f, 0f, 1f, 1f,
                new int[]{0xDDFFFFFF, 0x26FFFFFF, 0x99C4E4FF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        private final Matrix mGradMatrix = new Matrix();
        private final RectF mRect = new RectF();
        private final int[] mLoc = new int[2];

        private int mLastIdx = -1;
        private int mDisplayIdx = 0;
        private float mTouchY = 0f;
        private long mLastNanos = 0L;
        private OnLetterScrubbedListener mListener;

        public AlphabetIndexScrubberView(Context ctx, DialerGlassEngine.LiveOpticalState opt) {
            super(ctx);
            mOpt = opt;
            mDensity = ctx.getResources().getDisplayMetrics().density;
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(1.0f * mDensity);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 10.2f, getResources().getDisplayMetrics()));
            mLoupeTextPaint.setTextAlign(Paint.Align.CENTER);
            mLoupeTextPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            mLoupeTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 21f, getResources().getDisplayMetrics()));
        }

        public void setOnLetterScrubbedListener(OnLetterScrubbedListener listener) {
            mListener = listener;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = resolveSize(Math.round(24f * mDensity), widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(w, h);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                mShowSpring.target = 1f;
                float padV = 6f * mDensity;
                float usableH = Math.max(1f, getHeight() - padV * 2f);
                mTouchY = DialerGlassEngine.clampF(event.getY(), padV, getHeight() - padV);
                int idx = DialerGlassEngine.clamp(
                        (int) (((mTouchY - padV) / usableH) * LETTERS.length),
                        0, LETTERS.length - 1);
                mDisplayIdx = idx;
                if (idx != mLastIdx) {
                    mLastIdx = idx;
                    DialerGlassEngine.performKeyboardTap(this);
                    if (mListener != null) {
                        mListener.onLetterScrubbed(LETTERS[idx]);
                    }
                }
                kickSpring();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mShowSpring.target = 0f;
                mLastIdx = -1;
                kickSpring();
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void kickSpring() {
            if (mLastNanos == 0L) {
                mLastNanos = System.nanoTime();
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 2 || h <= 20) return;

            boolean anim = false;
            if (mLastNanos != 0L) {
                long now = System.nanoTime();
                float dt = DialerGlassEngine.clampF((now - mLastNanos) / 1_000_000_000f, 0.001f, 0.032f);
                mLastNanos = now;
                anim = mShowSpring.step(dt);
                if (!anim) mLastNanos = 0L;
            }

            boolean dark = mOpt != null && mOpt.darkTheme;
            float show = mShowSpring.value;
            getLocationOnScreen(mLoc);
            DialerGlassEngine.BackdropBuffers bd = selectBackdrop(this, mOpt);

            if (show > 0.01f) {
                mRect.set(1.5f * mDensity, 2f * mDensity, w - 1.5f * mDensity, h - 2f * mDensity);
                float tr = mRect.width() * 0.5f;
                float rw = mRect.width();
                float rh = mRect.height();
                if (mTrackGlass.bindToPaintWithBuffers(
                        mPillPaint, mOpt, bd, rw, rh,
                        mLoc[0] + mRect.left, mLoc[1] + mRect.top,
                        tr, mDensity, rw * 0.5f, mTouchY - mRect.top, show * 0.7f,
                        0.24f * show, 0, 0f, 0f, -1f, 0f)) {
                    mPillPaint.setAlpha(Math.round(255f * DialerGlassEngine.clampF(show, 0f, 1f)));
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mRect.left, mRect.top, rw, rh, mPillPaint);
                    mPillPaint.setAlpha(255);
                } else {
                    mPillPaint.setShader(null);
                    mPillPaint.setColor(dark ? Color.argb(120, 60, 60, 68) : Color.argb(140, 225, 228, 238));
                    canvas.drawRoundRect(mRect, tr, tr, mPillPaint);
                }
            }

            int blue = DialerGlassEngine.accentBlue(dark);
            mTextPaint.setColor(blue);
            float padV = 6f * mDensity;
            float step = (h - padV * 2f) / LETTERS.length;
            mTextPaint.getFontMetrics(mFm);
            float textCenterOff = -(mFm.ascent + mFm.descent) * 0.5f;

            for (int i = 0; i < LETTERS.length; i++) {
                float cy = padV + (i + 0.5f) * step;
                canvas.drawText(LETTERS[i], w * 0.5f, cy + textCenterOff, mTextPaint);
            }

            // Floating circular Liquid Glass Letter Loupe to the left of finger (parent clips disabled where needed)
            if (show > 0.04f && getParent() instanceof FrameLayout) {
                ((FrameLayout) getParent()).setClipChildren(false);
                float loupeR = 23f * mDensity * DialerGlassEngine.clampF(show, 0.4f, 1.05f);
                float lcx = -32f * mDensity;
                float lcy = DialerGlassEngine.clampF(mTouchY, loupeR + 4f * mDensity, h - loupeR - 4f * mDensity);
                float ld = loupeR * 2f;

                mShadowPaint.setColor(Color.argb(Math.round(70f * Math.min(1f, show)), 0, 8, 24));
                canvas.drawCircle(lcx, lcy + 2.5f * mDensity, loupeR, mShadowPaint);

                if (mLoupeGlass.bindToPaintWithBuffers(
                        mPillPaint, mOpt, bd, ld, ld,
                        mLoc[0] + lcx - loupeR, mLoc[1] + lcy - loupeR,
                        loupeR, mDensity, loupeR, loupeR, 0.65f,
                        0.36f, 0, 0f, 0f, -1f, 0f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, lcx - loupeR, lcy - loupeR, ld, ld, mPillPaint);
                } else {
                    mPillPaint.setShader(null);
                    mPillPaint.setColor(dark ? 0xEE2C2C2E : 0xF2FFFFFF);
                    canvas.drawCircle(lcx, lcy, loupeR, mPillPaint);
                }

                mGradMatrix.setScale(Math.max(1f, ld), Math.max(1f, ld));
                mGradMatrix.postTranslate(lcx - loupeR, lcy - loupeR);
                mLoupeRimGrad.setLocalMatrix(mGradMatrix);
                mRimPaint.setShader(mLoupeRimGrad);
                canvas.drawCircle(lcx, lcy, loupeR - 0.5f * mDensity, mRimPaint);
                mRimPaint.setShader(null);

                mLoupeTextPaint.setColor(blue);
                mLoupeTextPaint.getFontMetrics(mLoupeFm);
                canvas.drawText(LETTERS[mDisplayIdx], lcx, lcy - (mLoupeFm.ascent + mLoupeFm.descent) * 0.5f, mLoupeTextPaint);
            }

            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }
}
