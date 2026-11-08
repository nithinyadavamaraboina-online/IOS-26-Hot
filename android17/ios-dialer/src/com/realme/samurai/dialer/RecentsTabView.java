package com.realme.samurai.dialer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.provider.CallLog;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Authentic iOS 26.1 `Calls` (Unified) & `Recents` (Classic) View:
 *  - Top bar: `Edit` Liquid Glass pill (left), `All | Missed` `GlassSegmentedCapsule` or active filter pill
 *    (center), circular `≡` Filter & Settings Liquid Glass Orb (`GlassCircleButton`) or `Clear` pill (right)
 *  - Unified Mode: `"Calls"` `34sp` bold title + Pinned Favourites Horizontal Liquid Glass Contact Cards +
 *    `"Recents"` section + inline Voicemail filter card
 *  - Classic Mode: `"Recents"` `34sp` bold title + `All | Missed` capsule + `IosSearchFieldBar`
 *  - Row micro-UI: `32dp` circular `GlassSurfaceInstance` Liquid Glass `(i)` micro-orb on every row
 */
public final class RecentsTabView extends LinearLayout {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_MISSED = 1;
    public static final int FILTER_VOICEMAIL = 2;
    public static final int FILTER_UNKNOWN = 3;

    public interface HostCallbacks {
        PhoneAccountHandle getActiveSimHandle();
        default void onLayoutModeChanged(boolean unified) {}
        default void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {}
        default void onPickFavoriteRequested() {}
    }

    private final Activity mActivity;
    private final DialerGlassEngine.LiveOpticalState mOpt;
    private final HostCallbacks mHost;
    private final float mDensity;
    private final DialerGlassEngine.AnchorListScrollTracker mScrollTracker =
            new DialerGlassEngine.AnchorListScrollTracker();
    private final int[] mListLoc = new int[2];

    private final DialerGlassWidgets.GlassPillButton mEditBtn;
    private final DialerGlassWidgets.GlassSegmentedCapsule mSegmented;
    private final DialerGlassWidgets.GlassPillButton mActiveFilterChip;
    private final DialerGlassWidgets.GlassCircleButton mFilterOrbBtn;
    private final DialerGlassWidgets.GlassPillButton mClearBtn;
    private final DialerGlassWidgets.GlassPanelFrame mRoleBanner;
    private final TextView mRoleTitle;
    private final TextView mRoleSub;
    private final TextView mLargeTitle;
    private final DialerGlassWidgets.IosSearchFieldBar mSearchBar;

    private final LinearLayout mPinnedFavSection;
    private final LinearLayout mPinnedFavRow;
    private final TextView mRecentsSubHeader;
    private final DialerGlassWidgets.GlassPanelFrame mInlineVoicemailCard;
    private final TextView mVmCarrierTv;

    private final ListView mListView;
    private final TextView mEmptyView;
    private final RecentsAdapter mAdapter = new RecentsAdapter();

    private boolean mUnifiedMode = true;
    private boolean mEditMode = false;
    private int mFilterMode = FILTER_ALL;
    private String mQuery = "";
    private int mLastTrackerFilter = FILTER_ALL;
    private String mLastTrackerQuery = "";
    private List<DialerDataRepository.FavoriteItem> mFavorites = Collections.emptyList();
    private List<DialerDataRepository.RecentCallGroup> mAllGroups = Collections.emptyList();
    private List<DialerDataRepository.RecentCallGroup> mFiltered = Collections.emptyList();
    private List<TelecomHelper.SimSlotInfo> mSims = Collections.emptyList();

    public RecentsTabView(
            Activity activity,
            DialerGlassEngine.LiveOpticalState opt,
            HostCallbacks host) {
        super(activity);
        mActivity = activity;
        mOpt = opt;
        mHost = host;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mUnifiedMode = TelecomHelper.isUnifiedCallsLayout(activity);
        setOrientation(VERTICAL);
        setClipChildren(false);

        int padH = Math.round(18f * mDensity);

        // Top Bar: Edit (left), All|Missed or Filter Chip (center), ≡ Filter Orb / Clear (right)
        FrameLayout topBar = new FrameLayout(activity);
        topBar.setClipChildren(false);
        topBar.setPadding(padH, Math.round(4f * mDensity), padH, Math.round(2f * mDensity));

        mEditBtn = new DialerGlassWidgets.GlassPillButton(activity, opt, "Edit");
        mEditBtn.setPillMetrics(34f, 14.5f, false);
        mEditBtn.setOnClickListener(v -> toggleEditMode());
        topBar.addView(mEditBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        mSegmented = new DialerGlassWidgets.GlassSegmentedCapsule(
                activity, opt, new String[]{"All", "Missed"});
        mSegmented.setOnSegmentListener(idx -> {
            mFilterMode = (idx == 1) ? FILTER_MISSED : FILTER_ALL;
            rebuildFiltered();
        });
        topBar.addView(mSegmented, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mActiveFilterChip = new DialerGlassWidgets.GlassPillButton(activity, opt, "Missed ✕");
        mActiveFilterChip.setPillMetrics(32f, 13.5f, true);
        mActiveFilterChip.setVisibility(GONE);
        mActiveFilterChip.setOnClickListener(v -> setFilterMode(FILTER_ALL));
        topBar.addView(mActiveFilterChip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mFilterOrbBtn = new DialerGlassWidgets.GlassCircleButton(
                activity, opt, 36f,
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawFilterLines(c, cx, cy, sz, col, p));
        mFilterOrbBtn.setOnClickListener(v -> openFilterMenu());
        topBar.addView(mFilterOrbBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        mClearBtn = new DialerGlassWidgets.GlassPillButton(activity, opt, "Clear");
        mClearBtn.setPillMetrics(34f, 14.5f, false);
        mClearBtn.setCustomTextColor(DialerGlassEngine.IOS_RED);
        mClearBtn.setVisibility(GONE);
        mClearBtn.setOnClickListener(v -> IosSheetsAndDialogs.showClearRecentsSheet(
                mActivity, mOpt,
                () -> {
                    DialerDataRepository.get(mActivity).clearAllCallLogAsync();
                    mEditMode = false;
                    mEditBtn.setText("Edit");
                    syncTopBarActions();
                }));
        topBar.addView(mClearBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        addView(topBar, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * mDensity)));

        // Large 34sp Title ("Calls" in Unified mode, "Recents" in Classic mode)
        mLargeTitle = new TextView(activity);
        mLargeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        mLargeTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mLargeTitle.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(6f * mDensity));
        addView(mLargeTitle, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Search Pill (shown inline in Classic mode; Unified mode uses bottom Search Orb)
        mSearchBar = new DialerGlassWidgets.IosSearchFieldBar(activity, opt, "Search");
        mSearchBar.setOnQueryChangeListener(q -> {
            mQuery = q != null ? q.trim() : "";
            rebuildFiltered();
        });
        LayoutParams sbLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(40f * mDensity));
        sbLp.leftMargin = padH;
        sbLp.rightMargin = padH;
        sbLp.bottomMargin = Math.round(8f * mDensity);
        addView(mSearchBar, sbLp);

        // Subtle Default Dialer Role Glass Banner (shown only if ROLE_DIALER not yet held)
        mRoleBanner = new DialerGlassWidgets.GlassPanelFrame(activity, opt, 18f);
        LinearLayout roleInner = new LinearLayout(activity);
        roleInner.setOrientation(HORIZONTAL);
        roleInner.setGravity(Gravity.CENTER_VERTICAL);
        int rPad = Math.round(14f * mDensity);
        roleInner.setPadding(rPad, Math.round(10f * mDensity), rPad, Math.round(10f * mDensity));

        LinearLayout roleTextCol = new LinearLayout(activity);
        roleTextCol.setOrientation(VERTICAL);
        mRoleTitle = new TextView(activity);
        mRoleTitle.setText("Set as Default Phone App");
        mRoleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        mRoleTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        roleTextCol.addView(mRoleTitle);

        mRoleSub = new TextView(activity);
        mRoleSub.setText("Enable full-screen iOS 26 incoming & active calls");
        mRoleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        roleTextCol.addView(mRoleSub);

        roleInner.addView(roleTextCol, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        DialerGlassWidgets.GlassPillButton enableRoleBtn =
                new DialerGlassWidgets.GlassPillButton(activity, opt, "Enable");
        enableRoleBtn.setPillMetrics(32f, 13.5f, true);
        enableRoleBtn.setSolidFillColor(DialerGlassEngine.IOS_BLUE_LIGHT);
        enableRoleBtn.setOnClickListener(v -> TelecomHelper.requestDefaultDialerRole(mActivity));
        roleInner.addView(enableRoleBtn);

        mRoleBanner.addView(roleInner);
        LayoutParams rbLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbLp.leftMargin = padH;
        rbLp.rightMargin = padH;
        rbLp.bottomMargin = Math.round(8f * mDensity);
        addView(mRoleBanner, rbLp);

        // Unified Mode Pinned Favourites Horizontal Strip + "Recents" Section Subheader
        mPinnedFavSection = new LinearLayout(activity);
        mPinnedFavSection.setOrientation(VERTICAL);

        HorizontalScrollView favScroll = new HorizontalScrollView(activity);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setClipToPadding(false);
        favScroll.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(8f * mDensity));
        mPinnedFavRow = new LinearLayout(activity);
        mPinnedFavRow.setOrientation(HORIZONTAL);
        favScroll.addView(mPinnedFavRow);
        mPinnedFavSection.addView(favScroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRecentsSubHeader = new TextView(activity);
        mRecentsSubHeader.setText("Recents");
        mRecentsSubHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        mRecentsSubHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mRecentsSubHeader.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(4f * mDensity));
        mPinnedFavSection.addView(mRecentsSubHeader);

        addView(mPinnedFavSection, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Inline Carrier Voicemail Liquid Glass Card (when Unified Filter == Voicemail)
        mInlineVoicemailCard = new DialerGlassWidgets.GlassPanelFrame(activity, opt, 22f);
        mInlineVoicemailCard.setVisibility(GONE);
        LinearLayout vmBox = new LinearLayout(activity);
        vmBox.setOrientation(HORIZONTAL);
        vmBox.setGravity(Gravity.CENTER_VERTICAL);
        int vp = Math.round(16f * mDensity);
        vmBox.setPadding(vp, vp, vp, vp);

        mVmCarrierTv = new TextView(activity);
        mVmCarrierTv.setText("Carrier Voicemail");
        mVmCarrierTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        mVmCarrierTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        vmBox.addView(mVmCarrierTv, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        DialerGlassWidgets.GlassPillButton callVmBtn =
                new DialerGlassWidgets.GlassPillButton(activity, opt, "Call");
        callVmBtn.setPillMetrics(34f, 14f, true);
        callVmBtn.setSolidFillColor(DialerGlassEngine.IOS_BLUE_LIGHT);
        callVmBtn.setOnClickListener(v -> TelecomHelper.callVoicemail(
                mActivity, mHost != null ? mHost.getActiveSimHandle() : null));
        vmBox.addView(callVmBtn);
        mInlineVoicemailCard.addView(vmBox);
        LayoutParams vmLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vmLp.leftMargin = padH;
        vmLp.rightMargin = padH;
        vmLp.bottomMargin = Math.round(8f * mDensity);
        addView(mInlineVoicemailCard, vmLp);

        // Content Frame
        FrameLayout contentFrame = new FrameLayout(activity);
        mListView = new ListView(activity);
        mListView.setDivider(null);
        mListView.setDividerHeight(0);
        mListView.setClipToPadding(false);
        mListView.setPadding(0, 0, 0, Math.round(114f * mDensity));
        mListView.setAdapter(mAdapter);
        mListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                        && getVisibility() == VISIBLE && mHost != null) {
                    int sy = mScrollTracker.onListScrolled(mListView);
                    mListView.getLocationOnScreen(mListLoc);
                    mHost.onSceneScrolled(sy, mListLoc[1], true);
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                int sy = mScrollTracker.onListScrolled(mListView);
                if (getVisibility() == VISIBLE && mHost != null) {
                    mListView.getLocationOnScreen(mListLoc);
                    mHost.onSceneScrolled(sy, mListLoc[1], false);
                }
            }
        });
        contentFrame.addView(mListView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mEmptyView = new TextView(activity);
        mEmptyView.setText("No Recents");
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        mEmptyView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mEmptyView.setPadding(padH, 0, padH, Math.round(80f * mDensity));
        contentFrame.addView(mEmptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addView(contentFrame, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        applyLayoutModeUi();
        refreshRoleBannerVisibility();
        refreshTheme();
    }

    private void openFilterMenu() {
        IosSheetsAndDialogs.showFilterPopover(
                mActivity,
                mOpt,
                mUnifiedMode,
                mFilterMode,
                new IosSheetsAndDialogs.FilterPopoverCallbacks() {
                    @Override
                    public void onSelectLayout(boolean unified) {
                        TelecomHelper.setUnifiedCallsLayout(mActivity, unified);
                        setUnifiedMode(unified);
                        if (mHost != null) {
                            mHost.onLayoutModeChanged(unified);
                        }
                    }

                    @Override
                    public void onSelectFilter(int filterMode) {
                        setFilterMode(filterMode);
                    }

                    @Override
                    public void onOpenPhoneSettings() {
                        IosSheetsAndDialogs.showPhoneSettingsSheet(
                                mActivity, mOpt, mSims,
                                () -> {
                                    boolean uni = TelecomHelper.isUnifiedCallsLayout(mActivity);
                                    setUnifiedMode(uni);
                                    if (mHost != null) {
                                        mHost.onLayoutModeChanged(uni);
                                    }
                                    rebuildFiltered();
                                });
                    }
                });
    }

    public void setUnifiedMode(boolean unified) {
        mUnifiedMode = unified;
        if (!unified && mFilterMode > FILTER_MISSED) {
            mFilterMode = FILTER_ALL;
        }
        applyLayoutModeUi();
        rebuildPinnedFavoritesStrip();
        rebuildFiltered();
    }

    public void setFilterMode(int mode) {
        mFilterMode = DialerGlassEngine.clamp(mode, FILTER_ALL, FILTER_UNKNOWN);
        if (!mUnifiedMode) {
            mSegmented.setSelectedIndex(mFilterMode == FILTER_MISSED ? 1 : 0, false);
        }
        applyLayoutModeUi();
        rebuildFiltered();
    }

    private void applyLayoutModeUi() {
        mLargeTitle.setText(mUnifiedMode ? "Calls" : "Recents");
        mSearchBar.setVisibility(mUnifiedMode ? GONE : VISIBLE);
        if (!mUnifiedMode) {
            mSegmented.setVisibility(VISIBLE);
            mActiveFilterChip.setVisibility(GONE);
            mSegmented.setSelectedIndex(mFilterMode == FILTER_MISSED ? 1 : 0, false);
        } else {
            mSegmented.setVisibility(GONE);
            if (mFilterMode == FILTER_ALL) {
                mActiveFilterChip.setVisibility(GONE);
            } else {
                String lbl = (mFilterMode == FILTER_MISSED) ? "Missed ✕"
                        : (mFilterMode == FILTER_VOICEMAIL ? "Voicemail ✕" : "Unknown Callers ✕");
                mActiveFilterChip.setText(lbl);
                mActiveFilterChip.setVisibility(VISIBLE);
            }
        }
        mInlineVoicemailCard.setVisibility(
                (mUnifiedMode && mFilterMode == FILTER_VOICEMAIL) ? VISIBLE : GONE);
        syncTopBarActions();
    }

    private void syncTopBarActions() {
        if (mEditMode) {
            mFilterOrbBtn.setVisibility(GONE);
            mClearBtn.setVisibility(mFiltered.isEmpty() ? GONE : VISIBLE);
        } else {
            mClearBtn.setVisibility(GONE);
            mFilterOrbBtn.setVisibility(VISIBLE);
        }
    }

    private void toggleEditMode() {
        mEditMode = !mEditMode;
        mEditBtn.setText(mEditMode ? "Done" : "Edit");
        syncTopBarActions();
        mAdapter.notifyDataSetChanged();
    }

    public void refreshRoleBannerVisibility() {
        boolean isDef = TelecomHelper.isDefaultDialer(mActivity);
        mRoleBanner.setVisibility(isDef ? GONE : VISIBLE);
    }

    public void bindFavorites(List<DialerDataRepository.FavoriteItem> favorites) {
        mFavorites = (favorites != null) ? favorites : Collections.emptyList();
        rebuildPinnedFavoritesStrip();
    }

    public void bindRecents(
            List<DialerDataRepository.RecentCallGroup> groups,
            List<TelecomHelper.SimSlotInfo> sims) {
        mAllGroups = (groups != null) ? groups : Collections.emptyList();
        mSims = (sims != null) ? sims : Collections.emptyList();
        mVmCarrierTv.setText(TelecomHelper.resolveCarrierName(mActivity, mSims, 0) + " Voicemail");
        rebuildFiltered();
    }

    private void rebuildPinnedFavoritesStrip() {
        mPinnedFavRow.removeAllViews();
        if (!mUnifiedMode || mFilterMode != FILTER_ALL) {
            mPinnedFavSection.setVisibility(GONE);
            return;
        }
        mPinnedFavSection.setVisibility(VISIBLE);
        boolean dark = mOpt != null && mOpt.darkTheme;
        int tileW = Math.round(88f * mDensity);
        int tileH = Math.round(98f * mDensity);
        int gap = Math.round(10f * mDensity);

        for (int i = 0; i < mFavorites.size(); i++) {
            final DialerDataRepository.FavoriteItem fav = mFavorites.get(i);
            DialerGlassWidgets.GlassPanelFrame card =
                    new DialerGlassWidgets.GlassPanelFrame(mActivity, mOpt, 20f);
            card.setTintBoost(0.20f);

            LinearLayout col = new LinearLayout(mActivity);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.CENTER);
            int cp = Math.round(8f * mDensity);
            col.setPadding(cp, cp, cp, cp);

            View av = new View(mActivity) {
                private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Runnable mInvalidateSelf = this::invalidate;
                @Override
                protected void onDraw(Canvas c) {
                    DialerGlassEngine.drawZeroAllocAvatar(
                            c, DialerDataRepository.get(getContext()),
                            getWidth() * 0.5f, getHeight() * 0.5f,
                            getWidth() * 0.48f, fav.displayName, fav.photoUri, mP, mInvalidateSelf);
                }
            };
            col.addView(av, new LayoutParams(Math.round(46f * mDensity), Math.round(46f * mDensity)));

            TextView nm = new TextView(mActivity);
            String first = fav.displayName != null ? fav.displayName.split("\\s+")[0] : "";
            nm.setText(first);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            nm.setTextColor(DialerGlassEngine.primaryInk(dark));
            LayoutParams nLp = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nLp.topMargin = Math.round(6f * mDensity);
            col.addView(nm, nLp);

            card.addView(col);
            card.setOnClickListener(v -> {
                DialerGlassEngine.performKeyboardTap(v);
                TelecomHelper.placeCall(
                        mActivity, fav.number,
                        mHost != null ? mHost.getActiveSimHandle() : null, false);
            });
            card.setOnLongClickListener(v -> {
                DialerGlassEngine.performTick(v, true);
                DialerDataRepository.ContactItem c =
                        DialerDataRepository.get(mActivity).findContactForNumber(fav.number);
                IosSheetsAndDialogs.showContactAndCallInfoSheet(
                        mActivity, mOpt, c, null,
                        mHost != null ? mHost.getActiveSimHandle() : null);
                return true;
            });

            LayoutParams lp = new LayoutParams(tileW, tileH);
            lp.rightMargin = gap;
            mPinnedFavRow.addView(card, lp);
        }

        // Always provide a refractive "+ Favourite" Liquid Glass card at end (or as starter tile)
        DialerGlassWidgets.GlassPanelFrame addCard =
                new DialerGlassWidgets.GlassPanelFrame(mActivity, mOpt, 20f);
        LinearLayout addCol = new LinearLayout(mActivity);
        addCol.setOrientation(VERTICAL);
        addCol.setGravity(Gravity.CENTER);
        View plusV = new View(mActivity) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                boolean dk = mOpt != null && mOpt.darkTheme;
                IosVectorIcons.drawPlus(
                        c, getWidth() * 0.5f, getHeight() * 0.5f,
                        24f * mDensity, DialerGlassEngine.accentBlue(dk), 2.3f * mDensity, mP);
            }
        };
        addCol.addView(plusV, new LayoutParams(Math.round(42f * mDensity), Math.round(42f * mDensity)));
        TextView addLbl = new TextView(mActivity);
        addLbl.setText("Add");
        addLbl.setGravity(Gravity.CENTER);
        addLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        addLbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        addLbl.setTextColor(DialerGlassEngine.accentBlue(dark));
        LayoutParams alLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alLp.topMargin = Math.round(6f * mDensity);
        addCol.addView(addLbl, alLp);
        addCard.addView(addCol);
        addCard.setOnClickListener(v -> {
            DialerGlassEngine.performKeyboardTap(v);
            if (mHost != null) {
                mHost.onPickFavoriteRequested();
            }
        });
        mPinnedFavRow.addView(addCard, new LayoutParams(tileW, tileH));
    }

    private void rebuildFiltered() {
        String qLower = mUnifiedMode ? "" : mQuery.toLowerCase(Locale.getDefault());
        boolean hideUnknownInCalls = TelecomHelper.isFilterUnknownFromCallsEnabled(mActivity);

        List<DialerDataRepository.RecentCallGroup> out = new ArrayList<>();
        for (DialerDataRepository.RecentCallGroup g : mAllGroups) {
            boolean isUnknown = g.cachedName == null || g.cachedName.isEmpty();
            if (mFilterMode == FILTER_MISSED && !g.isMissedOrRejected()) {
                continue;
            }
            if (mFilterMode == FILTER_VOICEMAIL && g.latestType != CallLog.Calls.VOICEMAIL_TYPE) {
                continue;
            }
            if (mFilterMode == FILTER_UNKNOWN && !isUnknown) {
                continue;
            }
            if (mFilterMode == FILTER_ALL && hideUnknownInCalls && isUnknown) {
                continue;
            }
            if (!qLower.isEmpty()) {
                boolean matchName = g.cachedName.toLowerCase(Locale.getDefault()).contains(qLower);
                boolean matchNum = g.rawNumber.contains(qLower)
                        || g.formattedNumber.toLowerCase(Locale.getDefault()).contains(qLower);
                if (!matchName && !matchNum) {
                    continue;
                }
            }
            out.add(g);
        }
        mFiltered = out;
        mPinnedFavSection.setVisibility(
                (mUnifiedMode && mFilterMode == FILTER_ALL) ? VISIBLE : GONE);
        mInlineVoicemailCard.setVisibility(
                (mUnifiedMode && mFilterMode == FILTER_VOICEMAIL) ? VISIBLE : GONE);

        if (mFiltered.isEmpty()) {
            String emptyStr = (mFilterMode == FILTER_MISSED) ? "No Missed Calls"
                    : (mFilterMode == FILTER_VOICEMAIL ? "No Voicemail Calls"
                    : (mFilterMode == FILTER_UNKNOWN ? "No Unknown Callers" : "No Recents"));
            mEmptyView.setText(emptyStr);
            mEmptyView.setVisibility(VISIBLE);
            mListView.setVisibility(GONE);
        } else {
            mEmptyView.setVisibility(GONE);
            mListView.setVisibility(VISIBLE);
        }
        mEditBtn.setVisibility(mAllGroups.isEmpty() ? INVISIBLE : VISIBLE);
        syncTopBarActions();
        mAdapter.notifyDataSetChanged();
        boolean filterOrQueryChanged = (mFilterMode != mLastTrackerFilter)
                || !java.util.Objects.equals(mQuery, mLastTrackerQuery);
        mLastTrackerFilter = mFilterMode;
        mLastTrackerQuery = mQuery;
        if (mFiltered.isEmpty() || filterOrQueryChanged) {
            mScrollTracker.reset();
        }
        if (getVisibility() == VISIBLE && mHost != null) {
            mListView.post(() -> {
                if (getVisibility() == VISIBLE && mHost != null) {
                    int sy = mFiltered.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
                    mListView.getLocationOnScreen(mListLoc);
                    mHost.onSceneScrolled(sy, mListLoc[1], true);
                }
            });
        }
    }

    public void syncLiveScrollState() {
        if (mOpt == null) return;
        int sy = mFiltered.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
        mListView.getLocationOnScreen(mListLoc);
        mOpt.updateLiveScroll(sy, mListLoc[1]);
    }

    public void invalidateGlassChrome() {
        mEditBtn.invalidate();
        mSegmented.invalidate();
        mActiveFilterChip.invalidate();
        mFilterOrbBtn.invalidate();
        mClearBtn.invalidate();
        mSearchBar.invalidate();
        DialerGlassEngine.invalidateGlassSubtree(mRoleBanner);
        DialerGlassEngine.invalidateGlassSubtree(mInlineVoicemailCard);
        int favCount = mPinnedFavRow.getChildCount();
        for (int i = 0; i < favCount; i++) {
            View fc = mPinnedFavRow.getChildAt(i);
            if (fc != null) fc.invalidate();
        }
        int childCount = mListView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View c = mListView.getChildAt(i);
            if (c != null) c.invalidate();
        }
    }

    public void refreshTheme() {
        boolean dark = mOpt != null && mOpt.darkTheme;
        mLargeTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mRecentsSubHeader.setTextColor(DialerGlassEngine.primaryInk(dark));
        mVmCarrierTv.setTextColor(DialerGlassEngine.primaryInk(dark));
        mRoleTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mRoleSub.setTextColor(DialerGlassEngine.secondaryInk(dark));
        mEmptyView.setTextColor(DialerGlassEngine.secondaryInk(dark));
        mSearchBar.refreshTheme();
        mEditBtn.invalidate();
        mSegmented.invalidate();
        mActiveFilterChip.invalidate();
        mFilterOrbBtn.invalidate();
        mClearBtn.invalidate();
        mRoleBanner.invalidate();
        mInlineVoicemailCard.invalidate();
        rebuildPinnedFavoritesStrip();
        mAdapter.notifyDataSetChanged();
    }

    private String resolveSimShortBadge(String accountId) {
        if (mSims.size() < 2 || accountId == null || accountId.isEmpty()) return "";
        for (TelecomHelper.SimSlotInfo s : mSims) {
            if (accountId.equals(s.accountId)) {
                return s.shortBadge;
            }
        }
        return "";
    }

    private final class RecentsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mFiltered.size();
        }

        @Override
        public DialerDataRepository.RecentCallGroup getItem(int position) {
            return mFiltered.get(position);
        }

        @Override
        public long getItemId(int position) {
            List<Long> ids = mFiltered.get(position).allIds();
            return ids.isEmpty() ? position : ids.get(0);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RecentRowView row = (convertView instanceof RecentRowView)
                    ? (RecentRowView) convertView
                    : new RecentRowView(getContext());
            row.bind(mFiltered.get(position), position == mFiltered.size() - 1);
            return row;
        }
    }

    private final class RecentRowView extends View {
        private final DialerGlassEngine.GlassSurfaceInstance mMicroOrbGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mOrbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mDatePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final RectF mSimRect = new RectF();
        private final int[] mLoc = new int[2];
        private final Runnable mInvalidateSelf = this::invalidate;
        private DialerDataRepository.RecentCallGroup mGroup;
        private boolean mLast = false;
        private DialerDataRepository.RecentCallGroup mCachedGroup;
        private int mCachedGroupEntries = -1;
        private int mCachedLayoutW = -1;
        private boolean mCachedEditMode = false;
        private String mCachedAvatarName = "";
        private String mCachedClippedTitle = "";
        private String mCachedSimBadge = "";
        private String mCachedClippedSub = "";

        RecentRowView(Context ctx) {
            super(ctx);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(0.95f * mDensity);
            mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mTitlePaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16.8f, getResources().getDisplayMetrics()));
            mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mSubPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.5f, getResources().getDisplayMetrics()));
            mDatePaint.setTextAlign(Paint.Align.RIGHT);
            mDatePaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mDatePaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.8f, getResources().getDisplayMetrics()));
            mBadgeTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mBadgeTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 10f, getResources().getDisplayMetrics()));

            setOnClickListener(v -> {
                if (mGroup == null) return;
                DialerGlassEngine.performKeyboardTap(this);
                if (mEditMode) {
                    DialerDataRepository.get(getContext()).deleteCallLogEntriesAsync(mGroup.allIds());
                    return;
                }
                if (!mGroup.rawNumber.isEmpty()) {
                    TelecomHelper.placeCall(
                            getContext(), mGroup.rawNumber,
                            mHost != null ? mHost.getActiveSimHandle() : null, false);
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event != null && event.getActionMasked() == MotionEvent.ACTION_UP && mGroup != null) {
                float infoLeft = getWidth() - 56f * mDensity;
                if (!mEditMode && event.getX() >= infoLeft) {
                    DialerGlassEngine.performKeyboardTap(this);
                    DialerDataRepository.ContactItem c =
                            DialerDataRepository.get(getContext()).findContactForNumber(mGroup.rawNumber);
                    IosSheetsAndDialogs.showContactAndCallInfoSheet(
                            mActivity, mOpt, c, mGroup,
                            mHost != null ? mHost.getActiveSimHandle() : null);
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        void bind(DialerDataRepository.RecentCallGroup group, boolean isLast) {
            mGroup = group;
            mLast = isLast;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(68f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mGroup == null) return;
            boolean dark = mOpt != null && mOpt.darkTheme;
            int w = getWidth();
            int h = getHeight();
            float cy = h * 0.5f;

            float x = 16f * mDensity;
            if (mEditMode) {
                float mr = 10.5f * mDensity;
                float mcx = x + mr;
                mPaint.reset();
                mPaint.setAntiAlias(true);
                mPaint.setColor(DialerGlassEngine.IOS_RED);
                canvas.drawCircle(mcx, cy, mr, mPaint);
                mPaint.setColor(Color.WHITE);
                mPaint.setStrokeWidth(2.0f * mDensity);
                canvas.drawLine(mcx - 5f * mDensity, cy, mcx + 5f * mDensity, cy, mPaint);
                x += 30f * mDensity;
            }

            // 42dp Circular Avatar
            float avR = 21f * mDensity;
            float avCx = x + avR;
            int entryCount = mGroup.entries.size();
            if (mGroup != mCachedGroup
                    || entryCount != mCachedGroupEntries
                    || w != mCachedLayoutW
                    || mEditMode != mCachedEditMode) {
                mCachedGroup = mGroup;
                mCachedGroupEntries = entryCount;
                mCachedLayoutW = w;
                mCachedEditMode = mEditMode;
                mCachedAvatarName = mGroup.baseNameOrNumber();

                float textStartTmp = avCx + avR + 12f * mDensity;
                float titleXTmp = textStartTmp + (mGroup.isOutgoing() ? 15f * mDensity : 0f);
                float dateRightTmp = w - (mEditMode ? 18f : 56f) * mDensity;
                float maxTitleWTmp = Math.max(60f * mDensity, dateRightTmp - titleXTmp - 74f * mDensity);
                mCachedClippedTitle = ellipsize(mGroup.displayTitle(), mTitlePaint, maxTitleWTmp);

                mCachedSimBadge = resolveSimShortBadge(mGroup.accountId);
                float subXTmp = textStartTmp;
                if (!mCachedSimBadge.isEmpty()) {
                    subXTmp += mBadgeTextPaint.measureText(mCachedSimBadge) + 13f * mDensity;
                }
                String rawSub = !mGroup.cachedName.isEmpty() && !mGroup.formattedNumber.isEmpty()
                        ? ("mobile · " + mGroup.formattedNumber)
                        : "mobile";
                mCachedClippedSub = ellipsize(
                        rawSub, mSubPaint, Math.max(60f * mDensity, dateRightTmp - subXTmp - 12f * mDensity));
            }
            DialerGlassEngine.drawZeroAllocAvatar(
                    canvas, DialerDataRepository.get(getContext()),
                    avCx, cy, avR, mCachedAvatarName,
                    mGroup.photoUri, mPaint, mInvalidateSelf);

            float textStart = avCx + avR + 12f * mDensity;
            float titleX = textStart;

            if (mGroup.isOutgoing()) {
                IosVectorIcons.drawOutgoingArrow(
                        canvas, titleX + 5.5f * mDensity, cy - 9.5f * mDensity,
                        11.5f * mDensity, DialerGlassEngine.secondaryInk(dark), mPaint);
                titleX += 15f * mDensity;
            }

            mTitlePaint.setColor(mGroup.isMissedOrRejected()
                    ? DialerGlassEngine.IOS_RED
                    : DialerGlassEngine.primaryInk(dark));
            float dateRight = w - (mEditMode ? 18f : 56f) * mDensity;
            canvas.drawText(mCachedClippedTitle, titleX, cy - 3.5f * mDensity, mTitlePaint);

            // Subtitle + optional Dual-SIM [S1]/[S2] badge
            float subX = textStart;
            if (!mCachedSimBadge.isEmpty()) {
                float bw = mBadgeTextPaint.measureText(mCachedSimBadge) + 7f * mDensity;
                float bh = 13.5f * mDensity;
                mSimRect.set(subX, cy + 4.5f * mDensity, subX + bw, cy + 4.5f * mDensity + bh);
                mPaint.reset();
                mPaint.setAntiAlias(true);
                mPaint.setColor(dark ? 0xFF2C2C2E : 0xFFE3E3E8);
                canvas.drawRoundRect(mSimRect, 4.0f * mDensity, 4.0f * mDensity, mPaint);
                mBadgeTextPaint.setColor(DialerGlassEngine.secondaryInk(dark));
                canvas.drawText(mCachedSimBadge, subX + 3.5f * mDensity, cy + 14.8f * mDensity, mBadgeTextPaint);
                subX += bw + 6f * mDensity;
            }

            mSubPaint.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mCachedClippedSub, subX, cy + 15.5f * mDensity, mSubPaint);

            // Relative Date + Circular 32dp Refractive Liquid Glass `(i)` Micro-Orb
            mDatePaint.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mGroup.relativeDateText, dateRight, cy + 4.5f * mDensity, mDatePaint);

            if (!mEditMode && (mOpt == null || !mOpt.isCapturingScene())) {
                float orbCx = w - 28f * mDensity;
                float orbSize = 32f * mDensity;
                float orbR = 16f * mDensity;
                float orbLeft = orbCx - orbR;
                float orbTop = cy - orbR;
                getLocationOnScreen(mLoc);
                if (mMicroOrbGlass.bindToPaint(
                        mOrbPaint, mOpt, orbSize, orbSize,
                        mLoc[0] + orbLeft, mLoc[1] + orbTop,
                        orbR, mDensity, orbR, orbR, 0f, 0.14f)) {
                    DialerGlassEngine.GlassSurfaceInstance.drawTranslated(
                            canvas, orbLeft, orbTop, orbSize, orbSize, mOrbPaint);
                } else {
                    mOrbPaint.setShader(null);
                    mOrbPaint.setColor(dark ? 0xFF242428 : 0xFFEFF1F7);
                    canvas.drawCircle(orbCx, cy, orbR, mOrbPaint);
                }
                mRimPaint.setShader(null);
                mRimPaint.setColor(dark ? 0x26FFFFFF : 0x14000000);
                canvas.drawCircle(orbCx, cy, orbR - 0.35f * mDensity, mRimPaint);
                IosVectorIcons.drawInfoCircle(
                        canvas, orbCx, cy, 10.5f * mDensity, DialerGlassEngine.accentBlue(dark), mPaint);
            }

            if (!mLast) {
                mPaint.reset();
                mPaint.setColor(DialerGlassEngine.separatorColor(dark));
                canvas.drawRect(textStart, h - Math.max(1f, 0.65f * mDensity), w, h, mPaint);
            }
        }

        private String ellipsize(String s, Paint p, float maxW) {
            if (p.measureText(s) <= maxW) return s;
            int n = s.length();
            while (n > 3 && p.measureText(s, 0, n) + p.measureText("…") > maxW) {
                n--;
            }
            return s.substring(0, Math.max(1, n)) + "…";
        }
    }
}
