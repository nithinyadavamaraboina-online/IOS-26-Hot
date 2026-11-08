package com.realme.samurai.systemui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

/**
 * Status-bar compact `sminPoly` Liquid Glass Dynamic Island View (`PhoneStatusBarView` child).
 *
 * Centered at `y = 6dp` around RMX1931's waterdrop cutout:
 * - Idle mask: minimal `74dp x 26dp` zero-distraction obsidian-glass capsule around cutout.
 * - Compact active pill (`216dp x 36dp`): morphs via `SamuraiSpring` + `sminPoly` detached orb
 *   union (`SamuraiAgslEngine.createMorphPillBarShader()`) for Call, Timer/Stopwatch, Media,
 *   50W SuperVOOC Charging toast, and Ringer/Silent mode toast.
 * - Tapping an active pill expands the `362dp x 148dp` interactive platter on `IosShadeOverlayHost`.
 */
public final class DynamicIslandView extends View {

    private final DynamicIslandController mController;
    private final RuntimeShader mMorphShader = SamuraiAgslEngine.createMorphPillBarShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mObsidianPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF mCapsuleRect = new RectF();
    private final RectF mTmpRect = new RectF();
    private final int[] mTmpLoc = new int[2];

    private final SamuraiSpring mWidthDpSpring;
    private final SamuraiSpring mHeightDpSpring;
    private final SamuraiSpring mOrbSplitSpring;
    private final SamuraiSpring mPressSpring;

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private int mLastMode = DynamicIslandController.MODE_IDLE;
    private float mWidthDp = 74f;
    private float mHeightDp = 26f;
    private float mOrbSplit = 0f;
    private float mPressScale = 1f;
    private float mWavePhase = 0f;
    private boolean mWaveRunning = false;

    private float mDownX;
    private float mDownY;
    private boolean mTrackingTap = false;

    private final Choreographer.FrameCallback mWaveFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mWaveRunning || !isAttachedToWindow()) {
                mWaveRunning = false;
                return;
            }
            mWavePhase = (frameTimeNanos / 1_000_000_000f) * 6.8f;
            invalidate();
            DynamicIslandController.IslandState st = mController.getState();
            if (st.active && (st.mode == DynamicIslandController.MODE_MEDIA
                    || st.mode == DynamicIslandController.MODE_CALL)) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                mWaveRunning = false;
            }
        }
    };

    public DynamicIslandView(
            Context context,
            DynamicIslandController controller,
            SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mController = controller;
        mPrefs = prefs;

        mRimPaint.setStyle(Paint.Style.STROKE);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        mWidthDpSpring = new SamuraiSpring(74f, (val, vel) -> {
            mWidthDp = val;
            invalidate();
        }).setTuning(28f, 0.76f);

        mHeightDpSpring = new SamuraiSpring(26f, (val, vel) -> {
            mHeightDp = val;
            invalidate();
        }).setTuning(28f, 0.78f);

        mOrbSplitSpring = new SamuraiSpring(0f, (val, vel) -> {
            mOrbSplit = Math.max(0f, val);
            invalidate();
        }).setTuning(24f, 0.70f);

        mPressSpring = new SamuraiSpring(1f, (val, vel) -> {
            mPressScale = val;
            invalidate();
        }).setTuning(34f, 0.75f);

        mController.addListener(this::syncFromController);
        syncFromController();
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        syncFromController();
    }

    private void syncFromController() {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        if (!snap.dynamicIslandEnabled) {
            setVisibility(GONE);
            mWaveRunning = false;
            return;
        }
        setVisibility(VISIBLE);
        DynamicIslandController.IslandState st = mController.getState();
        if (mController.isExpanded()) {
            // While expanded platter (362dp x 148dp) is active on IosShadeOverlayHost, match width smoothly
            mWidthDpSpring.animateTo(232f);
            mHeightDpSpring.animateTo(36f);
        } else if (st.active) {
            mWidthDpSpring.animateTo(216f);
            mHeightDpSpring.animateTo(36f);
            if (st.mode != mLastMode) {
                mOrbSplitSpring.snapTo(1.0f);
                mOrbSplitSpring.animateTo(0.0f);
            }
        } else {
            mWidthDpSpring.animateTo(74f);
            mHeightDpSpring.animateTo(26f);
            mOrbSplitSpring.animateTo(0f);
        }
        mLastMode = st.mode;

        boolean needWave = st.active && (st.mode == DynamicIslandController.MODE_MEDIA
                || st.mode == DynamicIslandController.MODE_CALL);
        if (needWave && !mWaveRunning && isAttachedToWindow()) {
            mWaveRunning = true;
            Choreographer.getInstance().postFrameCallback(mWaveFrame);
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncFromController();
    }

    @Override
    protected void onDetachedFromWindow() {
        mWaveRunning = false;
        mWidthDpSpring.cancel();
        mHeightDpSpring.cancel();
        mOrbSplitSpring.cancel();
        mPressSpring.cancel();
        super.onDetachedFromWindow();
    }

    public boolean hitTestPill(float statusBarX, float statusBarY) {
        if (getVisibility() != VISIBLE) return false;
        DynamicIslandController.IslandState st = mController.getState();
        if (!st.active) return false;
        float cx = getLeft() + getWidth() * 0.5f;
        float halfW = SystemUiGlassHelper.dp(getContext(), Math.max(108f, mWidthDp * 0.5f + 6f));
        float maxY = getTop() + SystemUiGlassHelper.dp(getContext(), 40f);
        return statusBarX >= (cx - halfW)
                && statusBarX <= (cx + halfW)
                && statusBarY >= 0f
                && statusBarY <= maxY;
    }

    public boolean handleStatusBarTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        if (action == MotionEvent.ACTION_DOWN) {
            if (!hitTestPill(x, y)) {
                mTrackingTap = false;
                return false;
            }
            mTrackingTap = true;
            mDownX = x;
            mDownY = y;
            mPressSpring.animateTo(1.055f);
            return true;
        }
        if (!mTrackingTap) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(x - mDownX, y - mDownY) > slop * 1.4f) {
                mTrackingTap = false;
                mPressSpring.animateTo(1.0f);
                return false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            mTrackingTap = false;
            mPressSpring.animateTo(1.0f);
            if (Math.hypot(x - mDownX, y - mDownY) <= slop * 1.6f) {
                SamuraiGlassPrefs.hapticClick(getContext(), this);
                mController.setExpanded(!mController.isExpanded());
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            mTrackingTap = false;
            mPressSpring.animateTo(1.0f);
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 2 || getHeight() <= 2) return;
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        if (!snap.dynamicIslandEnabled || mController.isExpanded()) {
            return;
        }

        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        float viewW = getWidth();
        float viewH = getHeight();
        float cx = viewW * 0.5f;
        float pillW = mWidthDp * density * mPressScale;
        float pillH = mHeightDp * density * mPressScale;
        float cy = pillH * 0.5f;

        // 1. Bind AGSL sminPoly liquid-metaball capsule shader
        SamuraiAgslEngine.BackdropPair bg = SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, true);
        getLocationOnScreen(mTmpLoc);
        float screenW = Math.max(1f, ctx.getResources().getDisplayMetrics().widthPixels);
        float screenH = Math.max(1f, ctx.getResources().getDisplayMetrics().heightPixels);

        float splitOffset = mOrbSplit * (26f * density);
        float mainHalfW = Math.max(14f, (pillW * 0.5f) - splitOffset * 0.35f);
        float mainHalfH = Math.max(8f, pillH * 0.5f);
        float orbHalf = (mOrbSplit > 0.02f) ? (pillH * 0.46f) : 0f;
        float orbCx = cx + mainHalfW + splitOffset * 0.65f;

        mMorphShader.setInputShader("uSharpBg", bg.sharpShader);
        mMorphShader.setInputShader("uSoftBg", bg.softShader);
        mMorphShader.setFloatUniform("uSize", viewW, viewH);
        mMorphShader.setFloatUniform("uScreenOrigin", (float) mTmpLoc[0], (float) mTmpLoc[1]);
        mMorphShader.setFloatUniform("uScreenSize", screenW, screenH);
        mMorphShader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        mMorphShader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        mMorphShader.setFloatUniform("uDensity", density);
        mMorphShader.setFloatUniform("uDarkTheme", 1.0f);
        mMorphShader.setFloatUniform("uRefractScale", snap.refractScale());
        mMorphShader.setFloatUniform("uFrostScale", snap.frostScale());
        mMorphShader.setFloatUniform("uMainRect", cx, cy, mainHalfW, mainHalfH);
        mMorphShader.setFloatUniform("uOrbRect", orbCx, cy, orbHalf, orbHalf);
        mMorphShader.setFloatUniform("uIndicatorRect", 0f, 0f, 0f, 0f);
        mMorphShader.setFloatUniform("uSminK", 14.0f * density);

        mShaderPaint.setShader(mMorphShader);
        canvas.drawRect(0f, 0f, viewW, viewH, mShaderPaint);

        // Deep OLED obsidian core mask so waterdrop cutout blends seamlessly into capsule
        mCapsuleRect.set(cx - mainHalfW, cy - mainHalfH, cx + mainHalfW, cy + mainHalfH);
        mObsidianPaint.setColor(0xD805070C);
        canvas.drawRoundRect(mCapsuleRect, mainHalfH, mainHalfH, mObsidianPaint);
        if (orbHalf > 1f) {
            mTmpRect.set(orbCx - orbHalf, cy - orbHalf, orbCx + orbHalf, cy + orbHalf);
            canvas.drawOval(mTmpRect, mObsidianPaint);
        }

        // Specular obsidian-crystal rim
        mRimPaint.setStrokeWidth(Math.max(1f, 0.9f * density));
        mRimPaint.setColor(0x42FFFFFF);
        canvas.drawRoundRect(mCapsuleRect, mainHalfH, mainHalfH, mRimPaint);

        // 2. Render compact active content when expanded past 150dp
        DynamicIslandController.IslandState st = mController.getState();
        float contentAlpha = SamuraiGlassPrefs.clampF((mWidthDp - 148f) / 56f, 0f, 1f);
        if (!st.active || contentAlpha <= 0.02f) {
            return;
        }
        int alphaInt = Math.round(contentAlpha * 255f);
        int accent = (st.accentColor & 0x00FFFFFF) | (alphaInt << 24);
        int white = (alphaInt << 24) | 0x00FFFFFF;

        float leftIconCx = mCapsuleRect.left + 20f * density;
        float rightEdge = mCapsuleRect.right - 13f * density;

        if (st.mode == DynamicIslandController.MODE_MEDIA) {
            // Left circular/squircle album art
            float artR = 11.5f * density;
            mTmpRect.set(leftIconCx - artR, cy - artR, leftIconCx + artR, cy + artR);
            Bitmap art = st.albumArt;
            if (art != null && !art.isRecycled()) {
                BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(
                        mTmpRect.width() / Math.max(1f, art.getWidth()),
                        mTmpRect.height() / Math.max(1f, art.getHeight()));
                m.postTranslate(mTmpRect.left, mTmpRect.top);
                bs.setLocalMatrix(m);
                mArtPaint.setShader(bs);
                mArtPaint.setAlpha(alphaInt);
                canvas.drawRoundRect(mTmpRect, 7f * density, 7f * density, mArtPaint);
                mArtPaint.setShader(null);
            } else {
                mBarPaint.setColor(0x335AC8FA);
                canvas.drawRoundRect(mTmpRect, 7f * density, 7f * density, mBarPaint);
                SystemUiGlassHelper.drawGlyph(
                        canvas, SystemUiGlassHelper.GLYPH_MUSIC, leftIconCx, cy, 13f * density, accent);
            }
            // Right animated 4-bar equalizer
            drawEqualizer(canvas, rightEdge - 18f * density, cy, density, accent, st.mediaPlaying);
        } else if (st.mode == DynamicIslandController.MODE_CALL) {
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_PHONE, leftIconCx - 3f * density, cy, 15f * density, accent);
            drawEqualizer(canvas, leftIconCx + 15f * density, cy, density * 0.82f, accent, true);
            drawRightText(canvas, st.rightBadge, rightEdge, cy, 12.5f * density, accent);
        } else if (st.mode == DynamicIslandController.MODE_TIMER) {
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_TIMER, leftIconCx, cy, 16f * density, accent);
            drawRightText(canvas, st.rightBadge, rightEdge, cy, 12.5f * density, accent);
        } else if (st.mode == DynamicIslandController.MODE_CHARGING) {
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_VOOC_BOLT, leftIconCx, cy, 16f * density, accent);
            drawRightText(canvas, st.rightBadge, rightEdge, cy, 12.5f * density, accent);
        } else if (st.mode == DynamicIslandController.MODE_RINGER) {
            boolean silent = "Silent".equalsIgnoreCase(st.title);
            mBarPaint.setColor(silent ? accent : 0x30FFFFFF);
            mTmpRect.set(leftIconCx - 11f * density, cy - 9f * density,
                    leftIconCx + 11f * density, cy + 9f * density);
            canvas.drawRoundRect(mTmpRect, 9f * density, 9f * density, mBarPaint);
            int g = silent ? SystemUiGlassHelper.GLYPH_BELL_SLASH
                    : ("Vibrate".equalsIgnoreCase(st.title)
                            ? SystemUiGlassHelper.GLYPH_VIBRATE
                            : SystemUiGlassHelper.GLYPH_BELL);
            SystemUiGlassHelper.drawGlyph(
                    canvas, g, leftIconCx, cy, 13f * density, silent ? white : accent);
            drawRightText(canvas, st.title, rightEdge, cy, 12.2f * density, silent ? accent : white);
        }
    }

    private void drawRightText(Canvas c, String text, float rightX, float cy, float sizePx, int color) {
        if (text == null || text.isEmpty()) return;
        mTextPaint.setTextSize(sizePx);
        mTextPaint.setColor(color);
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(text, rightX, baseline, mTextPaint);
    }

    private void drawEqualizer(Canvas c, float startX, float cy, float density, int color, boolean anim) {
        mBarPaint.setColor(color);
        float barW = 2.6f * density;
        float gap = 4.6f * density;
        for (int i = 0; i < 4; i++) {
            float phase = mWavePhase + i * 1.15f;
            float amp = anim ? (0.32f + 0.68f * (0.5f + 0.5f * (float) Math.sin(phase))) : 0.25f;
            float h = (4.5f + 11.5f * amp) * density;
            float bx = startX + i * gap;
            mTmpRect.set(bx, cy - h * 0.5f, bx + barW, cy + h * 0.5f);
            c.drawRoundRect(mTmpRect, barW * 0.5f, barW * 0.5f, mBarPaint);
        }
    }
}
