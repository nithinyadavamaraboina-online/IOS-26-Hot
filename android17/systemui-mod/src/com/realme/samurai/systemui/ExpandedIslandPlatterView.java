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
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;

/**
 * Expanded Interactive Dynamic Island Platter (`362dp x 148dp`) hosted on `IosShadeOverlayHost`
 * so it renders completely unclipped below the `42dp` status bar over any app or Home Screen.
 */
public final class ExpandedIslandPlatterView extends View {

    private final DynamicIslandController mController;
    private final RuntimeShader mGlassShader = SamuraiAgslEngine.createSurfaceShader();
    private final Paint mShaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint mTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mSubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mPlatterBounds = new RectF();
    private final RectF mArtBounds = new RectF();
    private final RectF mBtn1Bounds = new RectF();
    private final RectF mBtn2Bounds = new RectF();
    private final RectF mBtn3Bounds = new RectF();
    private final RectF mBtnCollapseBounds = new RectF();
    private final int[] mTmpLoc = new int[2];

    private final SamuraiSpring mProgressSpring;
    private SamuraiGlassPrefs.Snapshot mPrefs;
    private float mProgress = 0f;
    private float mTouchDownX;
    private float mTouchDownY;
    private float mTouchX;
    private float mTouchY;
    private boolean mConsumingInsidePlatter = false;

    public ExpandedIslandPlatterView(
            Context context,
            DynamicIslandController controller,
            SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mController = controller;
        mPrefs = prefs;

        mRimPaint.setStyle(Paint.Style.STROKE);
        mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mBadgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        mProgressSpring = new SamuraiSpring(0f, (val, vel) -> {
            mProgress = SamuraiGlassPrefs.clampF(val, 0f, 1.08f);
            setVisibility(mProgress > 0.01f ? VISIBLE : GONE);
            SamuraiSystemUiHooks.notifyOverlayTouchabilityChanged();
            invalidate();
        }).setTuning(26f, 0.78f);

        mController.addListener(this::syncState);
        syncState();
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        syncState();
    }

    private void syncState() {
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(getContext(), null);
        boolean show = snap.dynamicIslandEnabled && mController.isExpanded();
        if (show) {
            setVisibility(VISIBLE);
            mProgressSpring.animateTo(1f);
        } else {
            mProgressSpring.animateTo(0f);
        }
        invalidate();
    }

    public boolean isShowingOrAnimating() {
        return mProgress > 0.01f || mController.isExpanded();
    }

    private void updatePlatterGeometry() {
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        float w = SamuraiGlassPrefs.clampF(mProgress, 0f, 1.05f);
        float curW = (216f + (362f - 216f) * w) * d;
        float curH = (36f + (148f - 36f) * w) * d;
        float cx = getWidth() * 0.5f;
        float top = 6f * d;
        mPlatterBounds.set(cx - curW * 0.5f, top, cx + curW * 0.5f, top + curH);
    }

    public boolean handleTouch(MotionEvent ev) {
        if (!isShowingOrAnimating()) return false;
        updatePlatterGeometry();
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        mTouchX = x;
        mTouchY = y;

        if (action == MotionEvent.ACTION_DOWN) {
            mTouchDownX = x;
            mTouchDownY = y;
            if (!mPlatterBounds.contains(x, y)) {
                // Outside tap collapses Expanded Platter cleanly
                SamuraiGlassPrefs.hapticTick(this);
                mController.setExpanded(false);
                mConsumingInsidePlatter = false;
                return true;
            }
            mConsumingInsidePlatter = true;
            invalidate();
            return true;
        }

        if (!mConsumingInsidePlatter) {
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            float dy = y - mTouchDownY;
            if (dy < -SystemUiGlassHelper.dp(getContext(), 22f)) {
                mConsumingInsidePlatter = false;
                SamuraiGlassPrefs.hapticTick(this);
                mController.setExpanded(false);
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            mConsumingInsidePlatter = false;
            float dist = (float) Math.hypot(x - mTouchDownX, y - mTouchDownY);
            if (dist < SystemUiGlassHelper.dp(getContext(), 18f)) {
                onPlatterTap(x, y);
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            mConsumingInsidePlatter = false;
            invalidate();
            return true;
        }
        return true;
    }

    private void onPlatterTap(float x, float y) {
        DynamicIslandController.IslandState st = mController.getState();
        SamuraiGlassPrefs.hapticClick(getContext(), this);

        if (mBtnCollapseBounds.contains(x, y)) {
            mController.setExpanded(false);
            return;
        }

        if (st.mode == DynamicIslandController.MODE_MEDIA) {
            if (mBtn1Bounds.contains(x, y)) {
                mController.mediaPrevious();
            } else if (mBtn2Bounds.contains(x, y)) {
                mController.mediaPlayPause();
            } else if (mBtn3Bounds.contains(x, y)) {
                mController.mediaNext();
            } else {
                mController.openActiveIslandDestination();
            }
        } else if (st.mode == DynamicIslandController.MODE_CALL) {
            if (mBtn1Bounds.contains(x, y)) {
                mController.openActiveIslandDestination();
            } else if (mBtn2Bounds.contains(x, y)) {
                mController.setExpanded(false);
            } else {
                mController.openActiveIslandDestination();
            }
        } else if (st.mode == DynamicIslandController.MODE_TIMER) {
            if (mBtn1Bounds.contains(x, y)) {
                mController.openActiveIslandDestination();
            } else if (mBtn2Bounds.contains(x, y)) {
                mController.dismissCurrentActivity();
            } else {
                mController.openActiveIslandDestination();
            }
        } else {
            mController.setExpanded(false);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mProgress <= 0.01f || getWidth() <= 2 || getHeight() <= 2) return;
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        SamuraiGlassPrefs.Snapshot snap = (mPrefs != null)
                ? mPrefs
                : SamuraiGlassPrefs.read(ctx, null);

        updatePlatterGeometry();
        float radius = (18f + 20f * SamuraiGlassPrefs.clampF(mProgress, 0f, 1f)) * d;

        // Subtle backdrop dim while expanded
        int scrimAlpha = Math.round(64f * SamuraiGlassPrefs.clampF(mProgress, 0f, 1f));
        mCorePaint.setColor(scrimAlpha << 24);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), mCorePaint);

        // AGSL Liquid Glass Platter refraction clipped to platter rect
        canvas.save();
        canvas.translate(mPlatterBounds.left, mPlatterBounds.top);
        SamuraiAgslEngine.BackdropPair bg = SamuraiAgslEngine.getSystemOrProceduralBackdrop(ctx, true);
        getLocationOnScreen(mTmpLoc);
        float screenW = Math.max(1f, ctx.getResources().getDisplayMetrics().widthPixels);
        float screenH = Math.max(1f, ctx.getResources().getDisplayMetrics().heightPixels);
        mGlassShader.setInputShader("uSharpBg", bg.sharpShader);
        mGlassShader.setInputShader("uSoftBg", bg.softShader);
        mGlassShader.setFloatUniform("uSize", mPlatterBounds.width(), mPlatterBounds.height());
        mGlassShader.setFloatUniform(
                "uScreenOrigin", mTmpLoc[0] + mPlatterBounds.left, mTmpLoc[1] + mPlatterBounds.top);
        mGlassShader.setFloatUniform("uScreenSize", screenW, screenH);
        mGlassShader.setFloatUniform("uSharpBufSize", (float) bg.sharpW, (float) bg.sharpH);
        mGlassShader.setFloatUniform("uSoftBufSize", (float) bg.softW, (float) bg.softH);
        mGlassShader.setFloatUniform("uRadius", radius);
        mGlassShader.setFloatUniform("uDensity", d);
        mGlassShader.setFloatUniform("uGlassMode", (float) snap.glassMode);
        mGlassShader.setFloatUniform("uRefractScale", snap.refractScale());
        mGlassShader.setFloatUniform("uFrostScale", Math.max(0.35f, snap.frostScale()));
        mGlassShader.setFloatUniform("uDarkTheme", 1.0f);
        mGlassShader.setFloatUniform(
                "uTouchPos", mTouchX - mPlatterBounds.left, mTouchY - mPlatterBounds.top);
        mGlassShader.setFloatUniform("uTouchAmp", mConsumingInsidePlatter ? 0.8f : 0f);
        mGlassShader.setFloatUniform("uTintColorR", 0.03f);
        mGlassShader.setFloatUniform("uTintColorG", 0.05f);
        mGlassShader.setFloatUniform("uTintColorB", 0.09f);
        mGlassShader.setFloatUniform("uTintStrength", 0.62f);

        mShaderPaint.setShader(mGlassShader);
        canvas.drawRect(0f, 0f, mPlatterBounds.width(), mPlatterBounds.height(), mShaderPaint);
        canvas.restore();

        // Deep obsidian tint & specular Fresnel border
        mCorePaint.setColor(0xBC070A12);
        canvas.drawRoundRect(mPlatterBounds, radius, radius, mCorePaint);
        mRimPaint.setStrokeWidth(1.15f * d);
        mRimPaint.setColor(0x52FFFFFF);
        canvas.drawRoundRect(mPlatterBounds, radius, radius, mRimPaint);

        float contentFade = SamuraiGlassPrefs.clampF((mProgress - 0.38f) / 0.52f, 0f, 1f);
        if (contentFade <= 0.02f) return;
        int alpha = Math.round(contentFade * 255f);

        DynamicIslandController.IslandState st = mController.getState();
        int accent = (st.accentColor & 0x00FFFFFF) | (alpha << 24);
        int white = (alpha << 24) | 0x00FFFFFF;
        int subWhite = (Math.round(contentFade * 176f) << 24) | 0x00DCE6F8;

        // Top-right collapse button
        float colSize = 28f * d;
        mBtnCollapseBounds.set(
                mPlatterBounds.right - 16f * d - colSize,
                mPlatterBounds.top + 14f * d,
                mPlatterBounds.right - 16f * d,
                mPlatterBounds.top + 14f * d + colSize);
        mBtnPaint.setColor((Math.round(contentFade * 42f) << 24) | 0x00FFFFFF);
        canvas.drawOval(mBtnCollapseBounds, mBtnPaint);
        SystemUiGlassHelper.drawGlyph(
                canvas,
                SystemUiGlassHelper.GLYPH_CHEVRON_UP,
                mBtnCollapseBounds.centerX(),
                mBtnCollapseBounds.centerY(),
                13f * d,
                white);

        // Left artwork / icon badge
        float artSize = 54f * d;
        mArtBounds.set(
                mPlatterBounds.left + 18f * d,
                mPlatterBounds.top + 18f * d,
                mPlatterBounds.left + 18f * d + artSize,
                mPlatterBounds.top + 18f * d + artSize);
        Bitmap art = st.albumArt;
        if (st.mode == DynamicIslandController.MODE_MEDIA && art != null && !art.isRecycled()) {
            BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(
                    mArtBounds.width() / Math.max(1f, art.getWidth()),
                    mArtBounds.height() / Math.max(1f, art.getHeight()));
            m.postTranslate(mArtBounds.left, mArtBounds.top);
            bs.setLocalMatrix(m);
            mArtPaint.setShader(bs);
            mArtPaint.setAlpha(alpha);
            canvas.drawRoundRect(mArtBounds, 14f * d, 14f * d, mArtPaint);
            mArtPaint.setShader(null);
        } else {
            mBtnPaint.setColor(((st.accentColor & 0x00FFFFFF) | (Math.round(contentFade * 54f) << 24)));
            canvas.drawRoundRect(mArtBounds, 16f * d, 16f * d, mBtnPaint);
            int glyph = SystemUiGlassHelper.GLYPH_MUSIC;
            if (st.mode == DynamicIslandController.MODE_CALL) glyph = SystemUiGlassHelper.GLYPH_PHONE;
            else if (st.mode == DynamicIslandController.MODE_TIMER) glyph = SystemUiGlassHelper.GLYPH_TIMER;
            else if (st.mode == DynamicIslandController.MODE_CHARGING) glyph = SystemUiGlassHelper.GLYPH_VOOC_BOLT;
            else if (st.mode == DynamicIslandController.MODE_RINGER) glyph = SystemUiGlassHelper.GLYPH_BELL;
            SystemUiGlassHelper.drawGlyph(
                    canvas, glyph, mArtBounds.centerX(), mArtBounds.centerY(), 26f * d, accent);
        }

        // Title & Subtitle
        float textLeft = mArtBounds.right + 14f * d;
        float maxTextW = Math.max(40f, mBtnCollapseBounds.left - textLeft - 10f * d);
        mTitlePaint.setTextSize(16.5f * d);
        mTitlePaint.setColor(white);
        String ellipTitle = TextUtils.ellipsize(
                st.title.isEmpty() ? "Live Activity" : st.title,
                mTitlePaint,
                maxTextW,
                TextUtils.TruncateAt.END).toString();
        canvas.drawText(ellipTitle, textLeft, mArtBounds.top + 22f * d, mTitlePaint);

        mSubPaint.setTextSize(13f * d);
        mSubPaint.setColor(subWhite);
        String subStr = (st.mode == DynamicIslandController.MODE_CALL
                || st.mode == DynamicIslandController.MODE_TIMER)
                ? (st.subtitle + "  •  " + st.rightBadge)
                : st.subtitle;
        String ellipSub = TextUtils.ellipsize(
                subStr, mSubPaint, maxTextW, TextUtils.TruncateAt.END).toString();
        canvas.drawText(ellipSub, textLeft, mArtBounds.top + 42f * d, mSubPaint);

        // Bottom Interactive Controls Row
        float btnRowCy = mPlatterBounds.bottom - 31f * d;
        mBtn1Bounds.setEmpty();
        mBtn2Bounds.setEmpty();
        mBtn3Bounds.setEmpty();

        if (st.mode == DynamicIslandController.MODE_MEDIA) {
            float btnR = 21f * d;
            float cxMid = mPlatterBounds.centerX();
            mBtn1Bounds.set(cxMid - 76f * d - btnR, btnRowCy - btnR, cxMid - 76f * d + btnR, btnRowCy + btnR);
            mBtn2Bounds.set(cxMid - btnR * 1.15f, btnRowCy - btnR * 1.15f, cxMid + btnR * 1.15f, btnRowCy + btnR * 1.15f);
            mBtn3Bounds.set(cxMid + 76f * d - btnR, btnRowCy - btnR, cxMid + 76f * d + btnR, btnRowCy + btnR);

            mBtnPaint.setColor((Math.round(contentFade * 36f) << 24) | 0x00FFFFFF);
            canvas.drawOval(mBtn1Bounds, mBtnPaint);
            canvas.drawOval(mBtn3Bounds, mBtnPaint);
            mBtnPaint.setColor((Math.round(contentFade * 68f) << 24) | 0x00FFFFFF);
            canvas.drawOval(mBtn2Bounds, mBtnPaint);

            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_PREV, mBtn1Bounds.centerX(), btnRowCy, 18f * d, white);
            SystemUiGlassHelper.drawGlyph(
                    canvas,
                    st.mediaPlaying ? SystemUiGlassHelper.GLYPH_PAUSE : SystemUiGlassHelper.GLYPH_PLAY,
                    mBtn2Bounds.centerX(),
                    btnRowCy,
                    21f * d,
                    white);
            SystemUiGlassHelper.drawGlyph(
                    canvas, SystemUiGlassHelper.GLYPH_NEXT, mBtn3Bounds.centerX(), btnRowCy, 18f * d, white);
        } else if (st.mode == DynamicIslandController.MODE_CALL
                || st.mode == DynamicIslandController.MODE_TIMER) {
            float pillH = 38f * d;
            float gap = 12f * d;
            float totalW = mPlatterBounds.width() - 36f * d;
            float eachW = (totalW - gap) * 0.5f;
            float left0 = mPlatterBounds.left + 18f * d;
            mBtn1Bounds.set(left0, btnRowCy - pillH * 0.5f, left0 + eachW, btnRowCy + pillH * 0.5f);
            mBtn2Bounds.set(
                    mBtn1Bounds.right + gap,
                    btnRowCy - pillH * 0.5f,
                    mBtn1Bounds.right + gap + eachW,
                    btnRowCy + pillH * 0.5f);

            mBtnPaint.setColor(((st.accentColor & 0x00FFFFFF) | (Math.round(contentFade * 76f) << 24)));
            canvas.drawRoundRect(mBtn1Bounds, pillH * 0.5f, pillH * 0.5f, mBtnPaint);
            mBtnPaint.setColor((Math.round(contentFade * 42f) << 24) | 0x00FFFFFF);
            canvas.drawRoundRect(mBtn2Bounds, pillH * 0.5f, pillH * 0.5f, mBtnPaint);

            mBadgePaint.setTextSize(13.5f * d);
            mBadgePaint.setTextAlign(Paint.Align.CENTER);
            mBadgePaint.setColor(white);
            Paint.FontMetrics fm = mBadgePaint.getFontMetrics();
            float base = btnRowCy - (fm.ascent + fm.descent) * 0.5f;
            String lbl1 = (st.mode == DynamicIslandController.MODE_CALL) ? "Open Phone" : "Open Clock";
            String lbl2 = (st.mode == DynamicIslandController.MODE_CALL) ? "Collapse" : "Clear Timer";
            canvas.drawText(lbl1, mBtn1Bounds.centerX(), base, mBadgePaint);
            canvas.drawText(lbl2, mBtn2Bounds.centerX(), base, mBadgePaint);
        } else {
            // Charging / Ringer progress pill
            float barLeft = mPlatterBounds.left + 20f * d;
            float barRight = mPlatterBounds.right - 20f * d;
            float barH = 10f * d;
            RectF track = new RectF(barLeft, btnRowCy - barH * 0.5f, barRight, btnRowCy + barH * 0.5f);
            mBtnPaint.setColor((Math.round(contentFade * 36f) << 24) | 0x00FFFFFF);
            canvas.drawRoundRect(track, barH * 0.5f, barH * 0.5f, mBtnPaint);
            RectF fill = new RectF(
                    barLeft,
                    track.top,
                    barLeft + track.width() * SamuraiGlassPrefs.clampF(st.progress01, 0.08f, 1f),
                    track.bottom);
            mBtnPaint.setColor(accent);
            canvas.drawRoundRect(fill, barH * 0.5f, barH * 0.5f, mBtnPaint);
        }
    }
}
