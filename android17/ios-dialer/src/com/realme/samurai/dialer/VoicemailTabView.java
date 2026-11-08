package com.realme.samurai.dialer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.telecom.PhoneAccountHandle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Tab 5 — Authentic iOS 26 `Voicemail` (`➿`) Tab:
 *  - Bold `34sp` `Voicemail` title
 *  - Centered Liquid Glass Carrier Voicemail Card showing SIM carrier name + `Call Voicemail` pill button
 */
public final class VoicemailTabView extends LinearLayout {

    private final DialerGlassEngine.LiveOpticalState mOpt;
    private final float mDensity;

    private final TextView mLargeTitle;
    private final DialerGlassWidgets.GlassPanelFrame mCardFrame;
    private final TextView mCarrierTv;
    private final TextView mDescTv;
    private final DialerGlassWidgets.GlassPillButton mSimSwitchBtn;
    private final DialerGlassWidgets.GlassPillButton mCallVmBtn;

    private List<TelecomHelper.SimSlotInfo> mSims = Collections.emptyList();
    private int mSimIdx = 0;

    public VoicemailTabView(Context ctx, DialerGlassEngine.LiveOpticalState opt) {
        super(ctx);
        mOpt = opt;
        mDensity = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        int padH = Math.round(18f * mDensity);

        // Top spacer matching sibling tabs
        View topSpacer = new View(ctx);
        addView(topSpacer, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * mDensity)));

        // Large 34sp Title
        mLargeTitle = new TextView(ctx);
        mLargeTitle.setText("Voicemail");
        mLargeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        mLargeTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mLargeTitle.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(10f * mDensity));
        addView(mLargeTitle, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Centered Carrier Voicemail Liquid Glass Card
        FrameLayout centerWrap = new FrameLayout(ctx);
        mCardFrame = new DialerGlassWidgets.GlassPanelFrame(ctx, opt, 26f);

        LinearLayout cardInner = new LinearLayout(ctx);
        cardInner.setOrientation(VERTICAL);
        cardInner.setGravity(Gravity.CENTER_HORIZONTAL);
        int cp = Math.round(26f * mDensity);
        cardInner.setPadding(cp, cp, cp, cp);

        View tapeIcon = new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                boolean dark = mOpt != null && mOpt.darkTheme;
                IosVectorIcons.drawVoicemailTape(
                        c, getWidth() * 0.5f, getHeight() * 0.5f,
                        48f * mDensity, DialerGlassEngine.accentBlue(dark), 2.8f * mDensity, mP);
            }
        };
        cardInner.addView(tapeIcon, new LayoutParams(
                Math.round(58f * mDensity), Math.round(58f * mDensity)));

        mCarrierTv = new TextView(ctx);
        mCarrierTv.setGravity(Gravity.CENTER);
        mCarrierTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        mCarrierTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LayoutParams ctLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctLp.topMargin = Math.round(10f * mDensity);
        cardInner.addView(mCarrierTv, ctLp);

        mDescTv = new TextView(ctx);
        mDescTv.setGravity(Gravity.CENTER);
        mDescTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        LayoutParams dtLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = Math.round(4f * mDensity);
        cardInner.addView(mDescTv, dtLp);

        mSimSwitchBtn = new DialerGlassWidgets.GlassPillButton(ctx, opt, "Switch SIM");
        mSimSwitchBtn.setPillMetrics(30f, 12.5f, false);
        mSimSwitchBtn.setVisibility(GONE);
        mSimSwitchBtn.setOnClickListener(v -> {
            if (mSims.size() >= 2) {
                mSimIdx = (mSimIdx + 1) % mSims.size();
                updateCarrierDetails();
            }
        });
        LayoutParams ssLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssLp.topMargin = Math.round(10f * mDensity);
        cardInner.addView(mSimSwitchBtn, ssLp);

        mCallVmBtn = new DialerGlassWidgets.GlassPillButton(ctx, opt, "Call Voicemail");
        mCallVmBtn.setPillMetrics(44f, 16f, true);
        mCallVmBtn.setSolidFillColor(DialerGlassEngine.IOS_BLUE_LIGHT);
        mCallVmBtn.setOnClickListener(v -> TelecomHelper.callVoicemail(getContext(), currentSimHandle()));
        LayoutParams btnLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = Math.round(18f * mDensity);
        cardInner.addView(mCallVmBtn, btnLp);

        mCardFrame.addView(cardInner);
        FrameLayout.LayoutParams cfLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cfLp.leftMargin = Math.round(28f * mDensity);
        cfLp.rightMargin = Math.round(28f * mDensity);
        cfLp.bottomMargin = Math.round(64f * mDensity);
        centerWrap.addView(mCardFrame, cfLp);

        addView(centerWrap, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateCarrierDetails();
        refreshTheme();
    }

    public void bindSims(List<TelecomHelper.SimSlotInfo> sims) {
        mSims = (sims != null) ? sims : Collections.emptyList();
        mSimIdx = TelecomHelper.getPreferredSimIndex(getContext(), mSims);
        mSimSwitchBtn.setVisibility(mSims.size() >= 2 ? VISIBLE : GONE);
        updateCarrierDetails();
    }

    private PhoneAccountHandle currentSimHandle() {
        if (mSims == null || mSims.isEmpty()) return null;
        int idx = DialerGlassEngine.clamp(mSimIdx, 0, mSims.size() - 1);
        return mSims.get(idx).handle;
    }

    private void updateCarrierDetails() {
        String carrier = TelecomHelper.resolveCarrierName(getContext(), mSims, mSimIdx);
        mCarrierTv.setText(carrier);
        String vmNum = TelecomHelper.resolveVoicemailNumber(getContext(), currentSimHandle());
        if (vmNum != null && !vmNum.isEmpty()) {
            mDescTv.setText("Carrier Voicemail · " + TelecomHelper.formatDisplayNumber(vmNum));
        } else {
            mDescTv.setText("Tap below to dial your carrier voicemail box");
        }
    }

    public void refreshTheme() {
        boolean dark = mOpt != null && mOpt.darkTheme;
        mLargeTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mCarrierTv.setTextColor(DialerGlassEngine.primaryInk(dark));
        mDescTv.setTextColor(DialerGlassEngine.secondaryInk(dark));
        mCallVmBtn.setSolidFillColor(DialerGlassEngine.accentBlue(dark));
        mCardFrame.invalidate();
        mSimSwitchBtn.invalidate();
        mCallVmBtn.invalidate();
    }
}
