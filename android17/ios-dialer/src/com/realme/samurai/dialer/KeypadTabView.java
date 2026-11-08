package com.realme.samurai.dialer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.telecom.PhoneAccountHandle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Authentic iOS 26.1 Liquid Glass `Keypad` (`⠿`) Tab:
 *  - Top bar: Dual-SIM Liquid Glass capsule + top-right circular `👤+` Add-Contact Liquid Glass orb
 *  - `38sp` numeral readout (auto-shrinking, long-press Paste)
 *  - Refractive Smart-Match Liquid Glass capsule (`[👤 Name · Number]` or `Add Number`)
 *  - 3x4 Circular 90Hz AGSL Liquid Glass Number Pad (`78dp` lenses) with unified `SpringFloat` bloom,
 *    localized finger touch bulge, and neighbor-key caustic light coupling
 *  - `78dp` Emerald (`#34C759`) Refractive Liquid Glass Call Lens + `54dp` Circular Liquid Glass `⌫` Orb
 */
public final class KeypadTabView extends LinearLayout {

    public interface SimChangeListener {
        void onSelectedSimChanged(TelecomHelper.SimSlotInfo sim);
    }

    private static final String[] KEY_MAIN = {
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "*", "0", "#"
    };

    private static final String[] KEY_SUB = {
            "", "ABC", "DEF",
            "GHI", "JKL", "MNO",
            "PQRS", "TUV", "WXYZ",
            "", "+", ""
    };

    private static final int[] DTMF_TONES = {
            ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
            ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6,
            ToneGenerator.TONE_DTMF_7, ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9,
            ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0, ToneGenerator.TONE_DTMF_P
    };

    private final DialerGlassEngine.LiveOpticalState mOpt;
    private final float mDensity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mRawDigits = new StringBuilder(32);

    private final DialerGlassWidgets.GlassPillButton mSimPill;
    private final DialerGlassWidgets.GlassCircleButton mAddContactOrb;
    private final TextView mReadoutTv;
    private final DialerGlassWidgets.GlassPanelFrame mMatchPill;
    private final View mMatchAvatarView;
    private final TextView mSubActionTv;
    private final KeypadSurfaceView mKeypadSurface;

    private List<TelecomHelper.SimSlotInfo> mSims = Collections.emptyList();
    private int mSelectedSimIdx = 0;
    private DialerDataRepository.ContactItem mMatchedContact = null;
    private ToneGenerator mToneGen;
    private SimChangeListener mSimListener;

    public KeypadTabView(Context ctx, DialerGlassEngine.LiveOpticalState opt) {
        super(ctx);
        mOpt = opt;
        mDensity = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);

        int padH = Math.round(18f * mDensity);

        // Top Bar: Centered Dual-SIM Liquid Glass Pill + Right Circular `👤+` Add-Contact Liquid Glass Orb
        FrameLayout topBar = new FrameLayout(ctx);
        topBar.setClipChildren(false);
        topBar.setPadding(padH, Math.round(4f * mDensity), padH, 0);

        mSimPill = new DialerGlassWidgets.GlassPillButton(ctx, opt, "SIM 1");
        mSimPill.setPillMetrics(30f, 12.5f, true);
        mSimPill.setVisibility(INVISIBLE);
        mSimPill.setOnClickListener(v -> cycleSelectedSim());
        topBar.addView(mSimPill, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mAddContactOrb = new DialerGlassWidgets.GlassCircleButton(
                ctx, opt, 36f,
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawPersonBadgePlus(c, cx, cy, sz, col, p));
        mAddContactOrb.setVisibility(INVISIBLE);
        mAddContactOrb.setScaleX(0.6f);
        mAddContactOrb.setScaleY(0.6f);
        mAddContactOrb.setAlpha(0f);
        mAddContactOrb.setOnClickListener(v -> {
            if (mRawDigits.length() > 0) {
                TelecomHelper.openInsertContact(getContext(), mRawDigits.toString());
            }
        });
        topBar.addView(mAddContactOrb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        addView(topBar, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44f * mDensity)));

        // Live Phone Number Readout (38sp SF-style slender numerals, auto-shrinking, long-press Paste)
        mReadoutTv = new TextView(ctx);
        mReadoutTv.setGravity(Gravity.CENTER);
        mReadoutTv.setSingleLine(true);
        mReadoutTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        mReadoutTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 39f);
        int hp = Math.round(24f * mDensity);
        mReadoutTv.setPadding(hp, Math.round(2f * mDensity), hp, 0);
        mReadoutTv.setMinimumHeight(Math.round(52f * mDensity));
        mReadoutTv.setOnLongClickListener(v -> {
            showPastePopup(v);
            return true;
        });
        addView(mReadoutTv, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Smart-Match / Add-Number Action under Readout (frameless blue "Add Number" or Contact Pill)
        mMatchPill = new DialerGlassWidgets.GlassPanelFrame(ctx, opt, 17f);
        mMatchPill.setTintBoost(0.0f);
        mMatchPill.setVisibility(INVISIBLE);

        LinearLayout matchInner = new LinearLayout(ctx);
        matchInner.setOrientation(HORIZONTAL);
        matchInner.setGravity(Gravity.CENTER_VERTICAL);
        int mpH = Math.round(13f * mDensity);
        int mpV = Math.round(5.5f * mDensity);
        matchInner.setPadding(mpH, mpV, mpH, mpV);

        mMatchAvatarView = new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Runnable mInvalidateSelf = this::invalidate;
            @Override
            protected void onDraw(Canvas c) {
                if (mMatchedContact == null) return;
                DialerGlassEngine.drawZeroAllocAvatar(
                        c, DialerDataRepository.get(getContext()),
                        getWidth() * 0.5f, getHeight() * 0.5f,
                        getWidth() * 0.48f, mMatchedContact.displayName,
                        mMatchedContact.photoUri, mP, mInvalidateSelf);
            }
        };
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                Math.round(20f * mDensity), Math.round(20f * mDensity));
        avLp.rightMargin = Math.round(7f * mDensity);
        matchInner.addView(mMatchAvatarView, avLp);

        mSubActionTv = new TextView(ctx);
        mSubActionTv.setGravity(Gravity.CENTER);
        mSubActionTv.setSingleLine(true);
        mSubActionTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSubActionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        matchInner.addView(mSubActionTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mMatchPill.addView(matchInner);
        mMatchPill.setOnClickListener(v -> {
            if (mRawDigits.length() == 0) return;
            DialerGlassEngine.performKeyboardTap(v);
            if (mMatchedContact != null) {
                String num = mMatchedContact.primaryNumber();
                if (!num.isEmpty()) {
                    TelecomHelper.placeCall(getContext(), num, getActiveSimHandle(), false);
                }
            } else {
                TelecomHelper.openInsertContact(getContext(), mRawDigits.toString());
            }
        });
        LayoutParams subLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(33f * mDensity));
        subLp.topMargin = Math.round(2f * mDensity);
        subLp.bottomMargin = Math.round(4f * mDensity);
        addView(mMatchPill, subLp);

        // 3x4 Circular Liquid Glass Keypad + Bottom Emerald Call & Backspace Lenses
        mKeypadSurface = new KeypadSurfaceView(ctx);
        FrameLayout padWrap = new FrameLayout(ctx);
        padWrap.setClipChildren(false);
        padWrap.addView(mKeypadSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LayoutParams kwLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        kwLp.bottomMargin = Math.round(92f * mDensity);
        addView(padWrap, kwLp);

        refreshReadoutUi();
        refreshTheme();
    }

    public void setSimChangeListener(SimChangeListener listener) {
        mSimListener = listener;
    }

    public void bindSims(List<TelecomHelper.SimSlotInfo> sims) {
        mSims = (sims != null) ? sims : Collections.emptyList();
        mSelectedSimIdx = TelecomHelper.getPreferredSimIndex(getContext(), mSims);
        updateSimPill();
    }

    private void cycleSelectedSim() {
        if (mSims.size() < 2) return;
        mSelectedSimIdx = (mSelectedSimIdx + 1) % mSims.size();
        TelecomHelper.SimSlotInfo sim = mSims.get(mSelectedSimIdx);
        TelecomHelper.setPreferredSimAccount(getContext(), sim);
        updateSimPill();
        if (mSimListener != null) {
            mSimListener.onSelectedSimChanged(sim);
        }
    }

    private void updateSimPill() {
        if (mSims.size() >= 2) {
            int idx = DialerGlassEngine.clamp(mSelectedSimIdx, 0, mSims.size() - 1);
            TelecomHelper.SimSlotInfo sim = mSims.get(idx);
            mSimPill.setText(sim.shortBadge + " · " + sim.carrierLabel);
            mSimPill.setVisibility(VISIBLE);
        } else {
            mSimPill.setVisibility(INVISIBLE);
        }
    }

    public PhoneAccountHandle getActiveSimHandle() {
        if (mSims == null || mSims.isEmpty()) return null;
        int idx = DialerGlassEngine.clamp(mSelectedSimIdx, 0, mSims.size() - 1);
        return mSims.get(idx).handle;
    }

    public void setDigits(String raw) {
        mRawDigits.setLength(0);
        if (raw != null) {
            mRawDigits.append(TelecomHelper.sanitizeDialable(raw));
        }
        refreshReadoutUi();
    }

    public String getDigits() {
        return mRawDigits.toString();
    }

    public void onContactsChanged() {
        refreshReadoutUi();
    }

    public void releaseAudio() {
        if (mKeypadSurface != null) {
            mKeypadSurface.cancelPendingCallbacks();
        }
        if (mToneGen != null) {
            try {
                mToneGen.release();
            } catch (Throwable ignored) {
            }
            mToneGen = null;
        }
    }

    private void playKeyDtmf(int keyIdx) {
        if (keyIdx < 0 || keyIdx >= DTMF_TONES.length) return;
        if (!TelecomHelper.isKeypadSoundsEnabled(getContext())) return;
        try {
            AudioManager am = getContext().getSystemService(AudioManager.class);
            if (am != null) {
                int ringer = am.getRingerMode();
                if (ringer == AudioManager.RINGER_MODE_SILENT
                        || ringer == AudioManager.RINGER_MODE_VIBRATE) {
                    return;
                }
            }
            if (mToneGen == null) {
                mToneGen = new ToneGenerator(AudioManager.STREAM_DTMF, 75);
            }
            mToneGen.startTone(DTMF_TONES[keyIdx], 120);
        } catch (Throwable ignored) {
        }
    }

    private void appendDigit(char c) {
        if (mRawDigits.length() >= 32) return;
        mRawDigits.append(c);
        refreshReadoutUi();
    }

    private void backspaceOne() {
        int len = mRawDigits.length();
        if (len > 0) {
            mRawDigits.deleteCharAt(len - 1);
            refreshReadoutUi();
        }
    }

    private void clearAllDigits() {
        if (mRawDigits.length() > 0) {
            mRawDigits.setLength(0);
            refreshReadoutUi();
        }
    }

    private void onGreenCallTapped() {
        if (mRawDigits.length() == 0) {
            // Authentic iOS behavior: tapping Call when readout is empty loads last dialed number!
            String last = DialerDataRepository.get(getContext()).getLastOutgoingNumber();
            if (last != null && !last.isEmpty()) {
                mRawDigits.append(TelecomHelper.sanitizeDialable(last));
                refreshReadoutUi();
            }
            return;
        }
        TelecomHelper.placeCall(getContext(), mRawDigits.toString(), getActiveSimHandle(), false);
    }

    private void showPastePopup(View anchor) {
        final String clip = TelecomHelper.readDialableFromClipboard(getContext());
        if (clip.isEmpty()) return;
        PopupMenu pm = new PopupMenu(getContext(), anchor, Gravity.CENTER);
        pm.getMenu().add(0, 1, 0, "Paste " + TelecomHelper.formatDisplayNumber(clip));
        pm.setOnMenuItemClickListener(item -> {
            setDigits(clip);
            return true;
        });
        pm.show();
    }

    private void refreshReadoutUi() {
        String raw = mRawDigits.toString();
        String formatted = TelecomHelper.formatDisplayNumber(raw);
        mReadoutTv.setText(formatted);

        int len = formatted.length();
        float sp;
        if (len <= 13) sp = 38f;
        else if (len <= 17) sp = 31f;
        else if (len <= 22) sp = 25f;
        else sp = 21f;
        mReadoutTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);

        boolean dark = mOpt != null && mOpt.darkTheme;
        boolean hasDigits = !raw.isEmpty();

        mAddContactOrb.setVisibility(INVISIBLE);

        if (!hasDigits) {
            mMatchedContact = null;
            mSubActionTv.setText("");
            mMatchPill.setVisibility(INVISIBLE);
        } else {
            mMatchedContact = DialerDataRepository.get(getContext()).matchBestContactByKeypad(raw);
            mMatchPill.setVisibility(VISIBLE);
            if (mMatchedContact != null) {
                mMatchPill.setFrameVisible(true);
                mMatchAvatarView.setVisibility(VISIBLE);
                mMatchAvatarView.invalidate();
                String cStr = mMatchedContact.displayName + " · "
                        + TelecomHelper.formatDisplayNumber(mMatchedContact.primaryNumber());
                mSubActionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                mSubActionTv.setText(cStr);
                mSubActionTv.setTextColor(DialerGlassEngine.primaryInk(dark));
            } else {
                mMatchPill.setFrameVisible(false);
                mMatchAvatarView.setVisibility(GONE);
                mSubActionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                mSubActionTv.setText("Add Number");
                mSubActionTv.setTextColor(DialerGlassEngine.accentBlue(dark));
            }
            mMatchPill.invalidate();
        }
        mKeypadSurface.setHasDigits(hasDigits);
    }

    public void refreshTheme() {
        boolean dark = mOpt != null && mOpt.darkTheme;
        setBackground(null);
        mReadoutTv.setTextColor(DialerGlassEngine.primaryInk(dark));
        refreshReadoutUi();
        mSimPill.invalidate();
        mAddContactOrb.invalidate();
        mMatchPill.invalidate();
        mKeypadSurface.invalidate();
    }

    /**
     * Custom 90Hz AGSL Liquid Glass 3x4 Number Pad + Emerald Call Lens + Circular Backspace Lens.
     */
    private final class KeypadSurfaceView extends View {
        private final DialerGlassEngine.GlassSurfaceInstance[] mKeyShaders =
                new DialerGlassEngine.GlassSurfaceInstance[12];
        private final DialerGlassEngine.SpringFloat[] mKeySprings =
                new DialerGlassEngine.SpringFloat[12];
        private final float[] mKeyTouchX = new float[12];
        private final float[] mKeyTouchY = new float[12];
        private final RectF[] mKeyBounds = new RectF[12];

        private final DialerGlassEngine.GlassSurfaceInstance mCallGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.GlassSurfaceInstance mBackGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final DialerGlassEngine.SpringFloat mCallSpring =
                DialerGlassEngine.SpringFloat.pressSpring();
        private final DialerGlassEngine.SpringFloat mBackShowSpring =
                DialerGlassEngine.SpringFloat.morphSpring(0f, 0f, 1.08f);
        private final DialerGlassEngine.SpringFloat mBackPressSpring =
                DialerGlassEngine.SpringFloat.pressSpring();

        private final RectF mCallBounds = new RectF();
        private final RectF mBackBounds = new RectF();
        private float mCallTouchX = 0f;
        private float mCallTouchY = 0f;
        private float mBackTouchX = 0f;
        private float mBackTouchY = 0f;

        private final Paint mKeyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDigitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] mLoc = new int[2];

        private int mActiveKey = -1;
        private boolean mCallDown = false;
        private boolean mBackDown = false;
        private boolean mHasDigits = false;
        private boolean mLongPressConsumed = false;
        private long mLastNanos = 0L;

        private final Runnable mLongPressRunnable = () -> {
            mLongPressConsumed = true;
            if (mActiveKey == 10) {
                // Long-press 0 -> '+'
                DialerGlassEngine.performTick(this, true);
                appendDigit('+');
            } else if (mActiveKey == 0 && mRawDigits.length() == 0) {
                // Long-press 1 -> Voicemail
                DialerGlassEngine.performTick(this, true);
                TelecomHelper.callVoicemail(getContext(), getActiveSimHandle());
            } else if (mActiveKey == 9 && mRawDigits.length() > 0) {
                DialerGlassEngine.performTick(this, true);
                appendDigit(',');
            } else if (mActiveKey == 11 && mRawDigits.length() > 0) {
                DialerGlassEngine.performTick(this, true);
                appendDigit(';');
            } else if (mBackDown) {
                DialerGlassEngine.performTick(this, true);
                clearAllDigits();
            }
        };

        KeypadSurfaceView(Context ctx) {
            super(ctx);
            for (int i = 0; i < 12; i++) {
                mKeyShaders[i] = new DialerGlassEngine.GlassSurfaceInstance();
                mKeySprings[i] = DialerGlassEngine.SpringFloat.pressSpring();
                mKeyBounds[i] = new RectF();
            }
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(0.55f * mDensity);
            mDigitPaint.setTextAlign(Paint.Align.CENTER);
            mDigitPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            mSubPaint.setTextAlign(Paint.Align.CENTER);
            mSubPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mSubPaint.setLetterSpacing(0.16f);
        }

        void cancelPendingCallbacks() {
            mHandler.removeCallbacks(mLongPressRunnable);
        }

        @Override
        protected void onDetachedFromWindow() {
            mHandler.removeCallbacks(mLongPressRunnable);
            super.onDetachedFromWindow();
        }

        void setHasDigits(boolean has) {
            if (mHasDigits != has) {
                mHasDigits = has;
                mBackShowSpring.target = has ? 1f : 0f;
                kickSpring();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            computeGeometry(w, h);
        }

        private void computeGeometry(int w, int h) {
            if (w <= 0 || h <= 0) return;
            float targetDia = 80f * mDensity;
            float targetGapX = 22f * mDensity;
            float targetGapY = 14f * mDensity;

            float maxDiaByH = (h - 4f * 10f * mDensity - 8f * mDensity) / 5.0f;
            float maxDiaByW = (w - 44f * mDensity - 2f * targetGapX) / 3.0f;
            float dia = Math.min(targetDia, Math.min(maxDiaByH, maxDiaByW));
            dia = Math.max(54f * mDensity, dia);
            float gapX = Math.min(targetGapX, (w - 3f * dia) * 0.25f);
            float gapY = DialerGlassEngine.clampF(
                    (h - 5f * dia) / 5.2f, 8f * mDensity, targetGapY);

            float totalW = 3f * dia + 2f * gapX;
            float totalH = 5f * dia + 4f * gapY;
            float startX = (w - totalW) * 0.5f;
            float startY = Math.max(2f * mDensity, (h - totalH) * 0.45f);
            float rad = dia * 0.5f;

            for (int i = 0; i < 12; i++) {
                int row = i / 3;
                int col = i % 3;
                float left = startX + col * (dia + gapX);
                float top = startY + row * (dia + gapY);
                mKeyBounds[i].set(left, top, left + dia, top + dia);
                mKeyTouchX[i] = rad;
                mKeyTouchY[i] = rad;
            }

            float callLeft = startX + (dia + gapX);
            float callTop = startY + 4f * (dia + gapY);
            mCallBounds.set(callLeft, callTop, callLeft + dia, callTop + dia);
            mCallTouchX = rad;
            mCallTouchY = rad;

            float backOrbDia = Math.min(dia * 0.72f, 56f * mDensity);
            float slotCx = startX + 2f * (dia + gapX) + rad;
            float slotCy = callTop + rad;
            mBackBounds.set(
                    slotCx - backOrbDia * 0.5f, slotCy - backOrbDia * 0.5f,
                    slotCx + backOrbDia * 0.5f, slotCy + backOrbDia * 0.5f);
            mBackTouchX = backOrbDia * 0.5f;
            mBackTouchY = backOrbDia * 0.5f;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            if (action == MotionEvent.ACTION_DOWN) {
                mLongPressConsumed = false;
                mActiveKey = hitTestKey(x, y);
                mCallDown = mActiveKey < 0 && hitCircle(mCallBounds, x, y, 0.54f);
                mBackDown = mActiveKey < 0 && !mCallDown && mHasDigits && hitCircle(mBackBounds, x, y, 0.68f);

                if (mActiveKey >= 0) {
                    RectF kb = mKeyBounds[mActiveKey];
                    mKeyTouchX[mActiveKey] = DialerGlassEngine.clampF(x - kb.left, 0f, kb.width());
                    mKeyTouchY[mActiveKey] = DialerGlassEngine.clampF(y - kb.top, 0f, kb.height());
                    mKeySprings[mActiveKey].target = 1f;
                    DialerGlassEngine.performKeyboardTap(this);
                    playKeyDtmf(mActiveKey);
                    mHandler.postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                    kickSpring();
                    return true;
                }
                if (mCallDown) {
                    mCallTouchX = DialerGlassEngine.clampF(x - mCallBounds.left, 0f, mCallBounds.width());
                    mCallTouchY = DialerGlassEngine.clampF(y - mCallBounds.top, 0f, mCallBounds.height());
                    mCallSpring.target = 1f;
                    DialerGlassEngine.performKeyboardTap(this);
                    kickSpring();
                    return true;
                }
                if (mBackDown) {
                    mBackTouchX = DialerGlassEngine.clampF(x - mBackBounds.left, 0f, mBackBounds.width());
                    mBackTouchY = DialerGlassEngine.clampF(y - mBackBounds.top, 0f, mBackBounds.height());
                    mBackPressSpring.target = 1f;
                    DialerGlassEngine.performKeyboardTap(this);
                    mHandler.postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                    kickSpring();
                    return true;
                }
                return false;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (mActiveKey >= 0) {
                    RectF kb = mKeyBounds[mActiveKey];
                    mKeyTouchX[mActiveKey] = DialerGlassEngine.clampF(x - kb.left, 0f, kb.width());
                    mKeyTouchY[mActiveKey] = DialerGlassEngine.clampF(y - kb.top, 0f, kb.height());
                    invalidate();
                } else if (mCallDown) {
                    mCallTouchX = DialerGlassEngine.clampF(x - mCallBounds.left, 0f, mCallBounds.width());
                    mCallTouchY = DialerGlassEngine.clampF(y - mCallBounds.top, 0f, mCallBounds.height());
                    invalidate();
                } else if (mBackDown) {
                    mBackTouchX = DialerGlassEngine.clampF(x - mBackBounds.left, 0f, mBackBounds.width());
                    mBackTouchY = DialerGlassEngine.clampF(y - mBackBounds.top, 0f, mBackBounds.height());
                    invalidate();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP) {
                mHandler.removeCallbacks(mLongPressRunnable);
                if (!mLongPressConsumed) {
                    if (mActiveKey >= 0 && hitTestKey(x, y) == mActiveKey) {
                        appendDigit(KEY_MAIN[mActiveKey].charAt(0));
                    } else if (mCallDown && hitCircle(mCallBounds, x, y, 0.58f)) {
                        DialerGlassEngine.performTick(this, true);
                        onGreenCallTapped();
                    } else if (mBackDown && hitCircle(mBackBounds, x, y, 0.72f)) {
                        backspaceOne();
                    }
                }
                releaseAllTargets();
                kickSpring();
                return true;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                mHandler.removeCallbacks(mLongPressRunnable);
                releaseAllTargets();
                kickSpring();
                return true;
            }
            return true;
        }

        private void releaseAllTargets() {
            if (mActiveKey >= 0 && mActiveKey < 12) {
                mKeySprings[mActiveKey].target = 0f;
            }
            mActiveKey = -1;
            mCallDown = false;
            mBackDown = false;
            mCallSpring.target = 0f;
            mBackPressSpring.target = 0f;
        }

        private int hitTestKey(float x, float y) {
            for (int i = 0; i < 12; i++) {
                if (hitCircle(mKeyBounds[i], x, y, 0.54f)) return i;
            }
            return -1;
        }

        private boolean hitCircle(RectF b, float x, float y, float radiusMul) {
            float r = b.width() * radiusMul;
            float dx = x - b.centerX();
            float dy = y - b.centerY();
            return dx * dx + dy * dy <= r * r;
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
            boolean moving = false;

            for (int i = 0; i < 12; i++) {
                if (mKeySprings[i].step(dt)) moving = true;
            }
            if (mCallSpring.step(dt)) moving = true;
            if (mBackShowSpring.step(dt)) moving = true;
            if (mBackPressSpring.step(dt)) moving = true;

            if (!moving && mActiveKey < 0 && !mCallDown && !mBackDown) {
                mLastNanos = 0L;
            }
            return moving;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOpt != null && mOpt.isCapturingScene()) return;
            super.onDraw(canvas);
            if (getWidth() <= 10 || getHeight() <= 10) return;
            boolean anim = stepSpring();
            boolean dark = mOpt != null && mOpt.darkTheme;
            getLocationOnScreen(mLoc);

            float dia = mKeyBounds[0].width();
            float r = dia * 0.5f;
            mDigitPaint.setTextSize(dia * 0.45f);
            mSubPaint.setTextSize(dia * 0.122f);

            int restFill = dark ? 0xFF2C2C2E : 0xFFE5E5EA;
            int pressFill = dark ? 0xFF636368 : 0xFFBCBCC2;
            int keyRimCol = dark ? 0x26FFFFFF : 0x16000000;
            mRimPaint.setShader(null);
            mRimPaint.setStrokeWidth(0.55f * mDensity);
            float invCausticDenom = 1f / Math.max(1f, (1.35f * dia) * (1.35f * dia));

            // 1. Render 12 Refractive 90Hz AGSL Liquid Glass Keypad Orbs + Neighbor Caustics
            for (int i = 0; i < 12; i++) {
                RectF b = mKeyBounds[i];
                float press = DialerGlassEngine.clampF(mKeySprings[i].value, 0f, 1.15f);
                float cx = b.centerX();
                float cy = b.centerY();

                float sumDx = 0f;
                float sumDy = 0f;
                float sumW = 0f;
                for (int j = 0; j < 12; j++) {
                    if (j == i) continue;
                    float pj = mKeySprings[j].value;
                    if (pj > 0.005f) {
                        float vx = mKeyBounds[j].centerX() - cx;
                        float vy = mKeyBounds[j].centerY() - cy;
                        float distSq = vx * vx + vy * vy;
                        float dist = (float) Math.sqrt(Math.max(distSq, 1f));
                        float w = pj * (float) Math.exp(-distSq * invCausticDenom);
                        sumDx += (vx / dist) * w;
                        sumDy += (vy / dist) * w;
                        sumW += w;
                    }
                }
                float callP = mCallSpring.value;
                if (callP > 0.005f) {
                    float vx = mCallBounds.centerX() - cx;
                    float vy = mCallBounds.centerY() - cy;
                    float distSq = vx * vx + vy * vy;
                    float dist = (float) Math.sqrt(Math.max(distSq, 1f));
                    float w = callP * (float) Math.exp(-distSq * invCausticDenom);
                    sumDx += (vx / dist) * w;
                    sumDy += (vy / dist) * w;
                    sumW += w;
                }
                float nLen = (float) Math.sqrt(sumDx * sumDx + sumDy * sumDy);
                float ndx = nLen > 0.0001f ? (sumDx / nLen) : 0f;
                float ndy = nLen > 0.0001f ? (sumDy / nLen) : -1f;
                float nGlow = DialerGlassEngine.clampF(sumW, 0f, 1f);

                float keyScale = 1.0f + 0.032f * DialerGlassEngine.clampF(press, 0f, 1f);
                canvas.save();
                canvas.scale(keyScale, keyScale, cx, cy);

                mShadowPaint.setColor(Color.argb(dark ? 54 : 18, 0, 8, 22));
                canvas.drawCircle(cx, cy + 1.8f * mDensity, r * 0.97f, mShadowPaint);

                if (mKeyShaders[i].bindToPaintEx(
                        mKeyPaint, mOpt, dia, dia,
                        mLoc[0] + b.left, mLoc[1] + b.top,
                        r, mDensity, mKeyTouchX[i], mKeyTouchY[i], press,
                        0.08f + 0.22f * DialerGlassEngine.clampF(press, 0f, 1f),
                        0, 0f, ndx, ndy, nGlow)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, b.left, b.top, dia, dia, mKeyPaint);
                } else {
                    mKeyPaint.setShader(null);
                    mKeyPaint.setColor(DialerGlassEngine.blendArgb(
                            restFill, pressFill, DialerGlassEngine.clampF(press, 0f, 1f)));
                    canvas.drawCircle(cx, cy, r, mKeyPaint);
                }

                mRimPaint.setColor(keyRimCol);
                canvas.drawCircle(cx, cy, r - 0.3f * mDensity, mRimPaint);

                // Digit + Compact Letters / Voicemail Glyph
                mDigitPaint.setColor(DialerGlassEngine.primaryInk(dark));
                String mainStr = KEY_MAIN[i];
                String subStr = KEY_SUB[i];
                boolean centerSingle = (i == 9 || i == 11);
                float digitBaseY = centerSingle ? (cy + dia * 0.16f) : (cy + dia * 0.08f);
                canvas.drawText(mainStr, cx, digitBaseY, mDigitPaint);

                if (i == 0) {
                    IosVectorIcons.drawVoicemailTape(
                            canvas, cx, cy + dia * 0.255f, dia * 0.21f,
                            DialerGlassEngine.secondaryInk(dark), 1.4f * mDensity, mGlyphPaint);
                } else if (!subStr.isEmpty()) {
                    mSubPaint.setColor(DialerGlassEngine.primaryInk(dark));
                    canvas.drawText(subStr, cx, cy + dia * 0.285f, mSubPaint);
                }
                canvas.restore();
            }

            // 2. Render 80dp Emerald Tinted Liquid Glass (#34C759) Call Lens
            float callPress = DialerGlassEngine.clampF(mCallSpring.value, 0f, 1.15f);
            float callCx = mCallBounds.centerX();
            float callCy = mCallBounds.centerY();
            float callScale = 1.0f + 0.036f * DialerGlassEngine.clampF(callPress, 0f, 1f);
            canvas.save();
            canvas.scale(callScale, callScale, callCx, callCy);

            mShadowPaint.setColor(Color.argb(dark ? 72 : 40, 16, 130, 48));
            canvas.drawCircle(callCx, callCy + 2.2f * mDensity, r * 0.96f, mShadowPaint);

            if (mCallGlass.bindToPaintTinted(
                    mKeyPaint, mOpt, dia, dia,
                    mLoc[0] + mCallBounds.left, mLoc[1] + mCallBounds.top,
                    r, mDensity, mCallTouchX, mCallTouchY, callPress,
                    DialerGlassEngine.IOS_GREEN, 0.76f)) {
                DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                        canvas, mCallBounds.left, mCallBounds.top, dia, dia, mKeyPaint);
            } else {
                mKeyPaint.setShader(null);
                mKeyPaint.setColor(DialerGlassEngine.blendArgb(
                        DialerGlassEngine.IOS_GREEN, 0xFF28A748,
                        DialerGlassEngine.clampF(callPress, 0f, 1f)));
                canvas.drawCircle(callCx, callCy, r, mKeyPaint);
            }

            mRimPaint.setColor(0x38FFFFFF);
            canvas.drawCircle(callCx, callCy, r - 0.3f * mDensity, mRimPaint);

            IosVectorIcons.drawPhoneHandset(
                    canvas, callCx, callCy, dia * 0.44f, Color.WHITE, false, mGlyphPaint);
            canvas.restore();

            // 3. Render Circular Liquid Glass Backspace Lens + Apple `delete.left.fill` (`⌫`) Tag
            float backShow = mBackShowSpring.value;
            if (backShow > 0.01f) {
                float backPress = DialerGlassEngine.clampF(mBackPressSpring.value, 0f, 1.15f);
                float bx = mBackBounds.centerX();
                float by = mBackBounds.centerY();
                float backDia = mBackBounds.width();
                float backR = backDia * 0.5f;
                float bs = (0.72f + 0.28f * backShow)
                        * (1.0f + 0.04f * DialerGlassEngine.clampF(backPress, 0f, 1f));

                canvas.save();
                canvas.scale(bs, bs, bx, by);

                int alpha = Math.round(255f * DialerGlassEngine.clampF(backShow, 0f, 1f));
                if (mBackGlass.bindToPaint(
                        mKeyPaint, mOpt, backDia, backDia,
                        mLoc[0] + mBackBounds.left, mLoc[1] + mBackBounds.top,
                        backR, mDensity, mBackTouchX, mBackTouchY, backPress,
                        0.14f + 0.24f * DialerGlassEngine.clampF(backPress, 0f, 1f))) {
                    mKeyPaint.setAlpha(alpha);
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, mBackBounds.left, mBackBounds.top, backDia, backDia, mKeyPaint);
                    mKeyPaint.setAlpha(255);
                } else {
                    mKeyPaint.setShader(null);
                    int backBaseFill = dark ? 0x26FFFFFF : 0x18000000;
                    int backPressFill = dark ? 0x44FFFFFF : 0x2E000000;
                    int fallbackCol = DialerGlassEngine.blendArgb(
                            backBaseFill, backPressFill, DialerGlassEngine.clampF(backPress, 0f, 1f));
                    int fbAlpha = Math.round(
                            Color.alpha(fallbackCol) * DialerGlassEngine.clampF(backShow, 0f, 1f));
                    mKeyPaint.setColor((fbAlpha << 24) | (fallbackCol & 0x00FFFFFF));
                    canvas.drawCircle(bx, by, backR, mKeyPaint);
                }

                int tagRest = dark ? 0xFF3A3A3C : 0xFFD1D1D6;
                int tagPress = dark ? 0xFF636368 : 0xFFAEAEB2;
                int tagFill = DialerGlassEngine.blendArgb(
                        tagRest, tagPress, DialerGlassEngine.clampF(backPress, 0f, 1f));
                tagFill = (alpha << 24) | (tagFill & 0x00FFFFFF);
                int crossCol = (alpha << 24) | (dark ? 0x00FFFFFF : 0x001C1C1E);
                DialerGlassEngine.drawZeroAllocBackspaceTag(
                        canvas, bx, by, dia * 0.34f, tagFill, crossCol, mGlyphPaint);
                canvas.restore();
            }

            if (anim) {
                postInvalidateOnAnimation();
            }
        }
    }
}
