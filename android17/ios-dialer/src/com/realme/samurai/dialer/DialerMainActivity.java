package com.realme.samurai.dialer;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.PhoneAccountHandle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Primary Clean-Room iOS 26.1 Phone Activity (`com.realme.samurai.dialer.DialerMainActivity`):
 *  - Zero mod screens, zero bloat settings — pure Apple iOS 26.1 Phone experience
 *  - Edge-to-edge Light Mode (`#F2F2F7`) & Dark Mode (`#000000`) at 90Hz
 *  - Live 90Hz AGSL `DialerGlassEngine` synced to `20%` Refraction, `20%` Frost, `70%` Wallpaper Depth
 *  - Supports BOTH iOS 26.1 Unified Mode (`Calls | Contacts | Keypad` + Detached `🔍` Search Orb)
 *    AND Classic 5-Tab Mode (`Favourites | Recents | Contacts | Keypad | Voicemail`)
 *  - Bottom-anchored Floating Liquid Glass Universal Search Overlay
 *  - Emerald Refractive Liquid Glass Ongoing-Call Return Pill
 */
public class DialerMainActivity extends Activity
        implements DialerGlassEngine.SettingsChangeListener,
        DialerDataRepository.DataListener,
        CallManager.Listener {

    private DialerGlassEngine.LiveOpticalState mOpt;
    private DialerDataRepository mRepo;
    private float mDensity;

    private FrameLayout mRootFrame;
    private FrameLayout mPageContainer;
    private DialerGlassWidgets.GlassPillButton mOngoingCallPill;
    private DialerGlassWidgets.IosBottomTabCapsuleBar mBottomCapsule;

    // Universal Bottom-Anchored Liquid Glass Search Overlay
    private FrameLayout mSearchOverlay;
    private LinearLayout mSearchBottomRow;
    private DialerGlassWidgets.IosSearchFieldBar mUniversalSearchBar;
    private DialerGlassWidgets.GlassCircleButton mUniversalCloseOrb;
    private ListView mSearchResultsList;
    private TextView mSearchEmptyTv;
    private final UniversalSearchAdapter mSearchAdapter = new UniversalSearchAdapter();
    private final DialerGlassEngine.AnchorListScrollTracker mSearchScrollTracker =
            new DialerGlassEngine.AnchorListScrollTracker();
    private final int[] mSearchListLoc = new int[2];
    private List<SearchHit> mSearchHits = Collections.emptyList();
    private boolean mSearchClosing = false;
    private boolean mCapturePostPending = false;
    private boolean mCaptureForceRequested = false;
    private int mLastImeBottom = 0;

    private FavoritesTabView mFavoritesView;
    private RecentsTabView mRecentsView;
    private ContactsTabView mContactsView;
    private KeypadTabView mKeypadView;
    private VoicemailTabView mVoicemailView;
    private View[] mPages;

    private static final class SearchHit {
        final String title;
        final String subtitle;
        final String number;
        final String photoUri;
        final DialerDataRepository.ContactItem contact;
        final DialerDataRepository.RecentCallGroup recent;

        SearchHit(
                String title,
                String subtitle,
                String number,
                String photoUri,
                DialerDataRepository.ContactItem contact,
                DialerDataRepository.RecentCallGroup recent) {
            this.title = title;
            this.subtitle = subtitle;
            this.number = number;
            this.photoUri = photoUri;
            this.contact = contact;
            this.recent = recent;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDensity = getResources().getDisplayMetrics().density;

        configureEdgeToEdge90HzWindow();

        mOpt = new DialerGlassEngine.LiveOpticalState(this, false);
        mOpt.setSoftBlurReadyCallback(this::invalidateFloatingGlassOverlays);
        mRepo = DialerDataRepository.get(this);

        buildUi();
        applyThemeAndSystemBars();
        TelecomHelper.ensureRuntimePermissions(this);
        handleIncomingIntent(getIntent());
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdge90HzWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        try {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode best90 = null;
                for (Display.Mode m : display.getSupportedModes()) {
                    if (m.getRefreshRate() >= 88f) {
                        if (best90 == null || m.getPhysicalWidth() >= best90.getPhysicalWidth()) {
                            best90 = m;
                        }
                    }
                }
                if (best90 != null) {
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.preferredDisplayModeId = best90.getModeId();
                    window.setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void buildUi() {
        mRootFrame = new FrameLayout(this);
        mRootFrame.setClipChildren(false);

        mPageContainer = new FrameLayout(this);
        mPageContainer.setClipChildren(false);
        mRootFrame.addView(mPageContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mKeypadView = new KeypadTabView(this, mOpt);
        mKeypadView.setSimChangeListener(sim -> mVoicemailView.bindSims(mRepo.getSims()));

        FavoritesTabView.HostCallbacks favHost = new FavoritesTabView.HostCallbacks() {
            @Override
            public PhoneAccountHandle getActiveSimHandle() {
                return mKeypadView.getActiveSimHandle();
            }

            @Override
            public void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {
                if (mFavoritesView != null
                        && mFavoritesView.getVisibility() == View.VISIBLE
                        && !isSearchOverlayActive()) {
                    onActiveSceneScrolled(scrollYPx, viewportTopPx, idle);
                }
            }
        };
        RecentsTabView.HostCallbacks recHost = new RecentsTabView.HostCallbacks() {
            @Override
            public PhoneAccountHandle getActiveSimHandle() {
                return mKeypadView.getActiveSimHandle();
            }

            @Override
            public void onLayoutModeChanged(boolean unified) {
                mBottomCapsule.setUnifiedMode(unified);
                int sel = mBottomCapsule.getSelectedTab();
                selectTab(sel);
                scheduleSceneCapture(true);
            }

            @Override
            public void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {
                if (mRecentsView != null
                        && mRecentsView.getVisibility() == View.VISIBLE
                        && !isSearchOverlayActive()) {
                    onActiveSceneScrolled(scrollYPx, viewportTopPx, idle);
                }
            }

            @Override
            public void onPickFavoriteRequested() {
                try {
                    Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
                    startActivityForResult(pick, TelecomHelper.REQ_PICK_FAVORITE_CONTACT);
                } catch (Throwable ignored) {
                    TelecomHelper.openInsertContact(DialerMainActivity.this, null);
                }
            }
        };
        ContactsTabView.HostCallbacks conHost = new ContactsTabView.HostCallbacks() {
            @Override
            public PhoneAccountHandle getActiveSimHandle() {
                return mKeypadView.getActiveSimHandle();
            }

            @Override
            public void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {
                if (mContactsView != null
                        && mContactsView.getVisibility() == View.VISIBLE
                        && !isSearchOverlayActive()) {
                    onActiveSceneScrolled(scrollYPx, viewportTopPx, idle);
                }
            }
        };

        mFavoritesView = new FavoritesTabView(this, mOpt, favHost);
        mRecentsView = new RecentsTabView(this, mOpt, recHost);
        mContactsView = new ContactsTabView(this, mOpt, conHost);
        mVoicemailView = new VoicemailTabView(this, mOpt);

        mPages = new View[]{
                mFavoritesView, mRecentsView, mContactsView, mKeypadView, mVoicemailView
        };
        for (View page : mPages) {
            page.setVisibility(View.GONE);
            mPageContainer.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Capture live scrolled scene on scroll/layout so floating Liquid Glass bars refract real rows
        mPageContainer.getViewTreeObserver().addOnScrollChangedListener(() -> scheduleSceneCapture(false));

        // Emerald Refractive Liquid Glass Ongoing-Call Return Pill at top
        mOngoingCallPill = new DialerGlassWidgets.GlassPillButton(this, mOpt, "Touch to return to call");
        mOngoingCallPill.setPillMetrics(34f, 13.5f, true);
        mOngoingCallPill.setGlassTint(DialerGlassEngine.IOS_GREEN, 0.76f);
        mOngoingCallPill.setVisibility(View.GONE);
        mOngoingCallPill.setOnClickListener(v -> {
            Intent ui = new Intent(this, IosCallActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(ui);
        });
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pillLp.topMargin = Math.round(42f * mDensity);
        mRootFrame.addView(mOngoingCallPill, pillLp);

        // Floating iOS 26.1 Liquid Glass Bottom Capsule Bar (+ Detached Search Orb in Unified Mode)
        boolean unified = TelecomHelper.isUnifiedCallsLayout(this);
        mBottomCapsule = new DialerGlassWidgets.IosBottomTabCapsuleBar(this, mOpt);
        mBottomCapsule.setUnifiedMode(unified);
        mBottomCapsule.setOnTabSelectedListener(this::selectTab);
        mBottomCapsule.setOnSearchOrbClickListener(this::openUniversalSearchOverlay);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(70f * mDensity),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.leftMargin = Math.round(14f * mDensity);
        barLp.rightMargin = Math.round(14f * mDensity);
        barLp.bottomMargin = Math.round(14f * mDensity);
        mRootFrame.addView(mBottomCapsule, barLp);

        // Universal Bottom-Anchored Liquid Glass Search Overlay
        buildUniversalSearchOverlay();

        // Edge-to-Edge WindowInsets + IME handling
        mRootFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int navBot = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int imeBot = insets.getInsets(WindowInsets.Type.ime()).bottom;
            mPageContainer.setPadding(0, topInset, 0, 0);

            FrameLayout.LayoutParams bLp = (FrameLayout.LayoutParams) mBottomCapsule.getLayoutParams();
            bLp.bottomMargin = Math.max(Math.round(10f * mDensity), navBot + Math.round(6f * mDensity));
            mBottomCapsule.setLayoutParams(bLp);

            FrameLayout.LayoutParams oLp = (FrameLayout.LayoutParams) mOngoingCallPill.getLayoutParams();
            oLp.topMargin = topInset + Math.round(6f * mDensity);
            mOngoingCallPill.setLayoutParams(oLp);

            if (mSearchOverlay != null && mSearchBottomRow != null) {
                mSearchOverlay.setPadding(0, topInset + Math.round(8f * mDensity), 0, 0);
                FrameLayout.LayoutParams sLp = (FrameLayout.LayoutParams) mSearchBottomRow.getLayoutParams();
                sLp.bottomMargin = Math.max(navBot, imeBot) + Math.round(12f * mDensity);
                mSearchBottomRow.setLayoutParams(sLp);
            }
            if (imeBot != mLastImeBottom) {
                mLastImeBottom = imeBot;
                scheduleSceneCapture(true);
            }
            return insets;
        });

        setContentView(mRootFrame);
        selectTab(IosVectorIcons.TAB_KEYPAD);
        mBottomCapsule.setSelectedTab(IosVectorIcons.TAB_KEYPAD, false);
    }

    private void buildUniversalSearchOverlay() {
        mSearchOverlay = new FrameLayout(this);
        mSearchOverlay.setVisibility(View.GONE);
        mSearchOverlay.setClickable(true);

        mSearchResultsList = new ListView(this);
        mSearchResultsList.setDivider(null);
        mSearchResultsList.setDividerHeight(0);
        mSearchResultsList.setClipToPadding(false);
        mSearchResultsList.setPadding(0, Math.round(12f * mDensity), 0, Math.round(96f * mDensity));
        mSearchResultsList.setAdapter(mSearchAdapter);
        mSearchResultsList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                        && mSearchOverlay != null
                        && mSearchOverlay.getVisibility() == View.VISIBLE
                        && !mSearchClosing) {
                    int sy = mSearchScrollTracker.onListScrolled(mSearchResultsList);
                    mSearchResultsList.getLocationOnScreen(mSearchListLoc);
                    onActiveSceneScrolled(sy, mSearchListLoc[1], true);
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (mSearchOverlay != null
                        && mSearchOverlay.getVisibility() == View.VISIBLE
                        && !mSearchClosing) {
                    int sy = mSearchScrollTracker.onListScrolled(mSearchResultsList);
                    mSearchResultsList.getLocationOnScreen(mSearchListLoc);
                    onActiveSceneScrolled(sy, mSearchListLoc[1], false);
                }
            }
        });
        mSearchOverlay.addView(mSearchResultsList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mSearchEmptyTv = new TextView(this);
        mSearchEmptyTv.setText("Search Contacts, Numbers & Calls");
        mSearchEmptyTv.setGravity(Gravity.CENTER);
        mSearchEmptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        mSearchEmptyTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSearchEmptyTv.setPadding(Math.round(28f * mDensity), 0, Math.round(28f * mDensity), Math.round(90f * mDensity));
        mSearchOverlay.addView(mSearchEmptyTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom-Anchored Floating Search Capsule + Circular `✕` Liquid Glass Orb
        mSearchBottomRow = new LinearLayout(this);
        mSearchBottomRow.setOrientation(LinearLayout.HORIZONTAL);
        mSearchBottomRow.setGravity(Gravity.CENTER_VERTICAL);
        mSearchBottomRow.setClipChildren(false);
        int side = Math.round(16f * mDensity);
        mSearchBottomRow.setPadding(side, 0, side, 0);

        mUniversalSearchBar = new DialerGlassWidgets.IosSearchFieldBar(
                this, mOpt, "Search Calls & Contacts");
        mUniversalSearchBar.setOnQueryChangeListener(this::updateUniversalSearchResults);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, Math.round(48f * mDensity), 1f);
        barLp.rightMargin = Math.round(10f * mDensity);
        mSearchBottomRow.addView(mUniversalSearchBar, barLp);

        mUniversalCloseOrb = new DialerGlassWidgets.GlassCircleButton(
                this, mOpt, 46f,
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawCloseCross(
                        c, cx, cy, sz, DialerGlassEngine.primaryInk(mOpt.darkTheme), p));
        mUniversalCloseOrb.setOnClickListener(v -> closeUniversalSearchOverlay());
        mSearchBottomRow.addView(mUniversalCloseOrb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        rowLp.bottomMargin = Math.round(18f * mDensity);
        mSearchOverlay.addView(mSearchBottomRow, rowLp);

        mRootFrame.addView(mSearchOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openUniversalSearchOverlay() {
        mSearchClosing = false;
        mSearchScrollTracker.reset();
        mOpt.resetLiveScroll();
        boolean dark = mOpt.darkTheme;
        mSearchOverlay.setBackgroundColor(dark ? 0xEB08090C : 0xECF2F3F8);
        mSearchOverlay.setAlpha(0f);
        mSearchOverlay.setVisibility(View.VISIBLE);
        mSearchOverlay.animate().alpha(1f).setDuration(180L).start();
        mBottomCapsule.setVisibility(View.INVISIBLE);
        updateUniversalSearchResults(mUniversalSearchBar.getQuery());
        mUniversalSearchBar.focusAndShowKeyboard();
        scheduleSceneCapture(true);
    }

    private boolean isSearchOverlayActive() {
        return mSearchOverlay != null
                && mSearchOverlay.getVisibility() == View.VISIBLE
                && !mSearchClosing;
    }

    private void syncVisibleTabLiveScroll() {
        if (mFavoritesView != null && mFavoritesView.getVisibility() == View.VISIBLE) {
            mFavoritesView.syncLiveScrollState();
        } else if (mRecentsView != null && mRecentsView.getVisibility() == View.VISIBLE) {
            mRecentsView.syncLiveScrollState();
        } else if (mContactsView != null && mContactsView.getVisibility() == View.VISIBLE) {
            mContactsView.syncLiveScrollState();
        }
    }

    private void closeUniversalSearchOverlay() {
        if (mSearchOverlay.getVisibility() != View.VISIBLE) return;
        mSearchClosing = true;
        mSearchScrollTracker.reset();
        mOpt.resetLiveScroll();
        syncVisibleTabLiveScroll();
        mUniversalSearchBar.clearAndHideKeyboard();
        mBottomCapsule.setVisibility(View.VISIBLE);
        scheduleSceneCapture(true);
        mSearchOverlay.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction(() -> {
                    mSearchOverlay.setVisibility(View.GONE);
                    mSearchClosing = false;
                    syncVisibleTabLiveScroll();
                    scheduleSceneCapture(true);
                })
                .start();
    }

    private void updateUniversalSearchResults(String rawQuery) {
        String q = rawQuery != null ? rawQuery.trim().toLowerCase(Locale.getDefault()) : "";
        if (q.isEmpty()) {
            mSearchHits = Collections.emptyList();
            mSearchEmptyTv.setText("Search Contacts, Numbers & Calls");
            mSearchEmptyTv.setVisibility(View.VISIBLE);
            mSearchAdapter.notifyDataSetChanged();
            scheduleSceneCapture(true);
            return;
        }
        List<SearchHit> out = new ArrayList<>();
        for (DialerDataRepository.ContactItem c : mRepo.getContacts()) {
            boolean match = c.displayName.toLowerCase(Locale.getDefault()).contains(q);
            String matchedNum = c.primaryNumber();
            if (!match) {
                for (DialerDataRepository.PhoneEntry pe : c.phones) {
                    if (pe.number.contains(q) || pe.formattedNumber.toLowerCase(Locale.getDefault()).contains(q)) {
                        match = true;
                        matchedNum = pe.number;
                        break;
                    }
                }
            }
            if (match) {
                out.add(new SearchHit(
                        c.displayName,
                        "Contact · " + TelecomHelper.formatDisplayNumber(matchedNum),
                        matchedNum,
                        c.photoUri,
                        c,
                        null));
                if (out.size() >= 30) break;
            }
        }
        for (DialerDataRepository.RecentCallGroup g : mRepo.getRecents()) {
            if (out.size() >= 45) break;
            boolean match = g.cachedName.toLowerCase(Locale.getDefault()).contains(q)
                    || g.rawNumber.contains(q)
                    || g.formattedNumber.toLowerCase(Locale.getDefault()).contains(q);
            if (match) {
                out.add(new SearchHit(
                        g.displayTitle(),
                        "Recent · " + g.relativeDateText,
                        g.rawNumber,
                        g.photoUri,
                        null,
                        g));
            }
        }
        mSearchHits = out;
        if (out.isEmpty()) {
            mSearchEmptyTv.setText("No Results for \"" + rawQuery.trim() + "\"");
            mSearchEmptyTv.setVisibility(View.VISIBLE);
        } else {
            mSearchEmptyTv.setVisibility(View.GONE);
        }
        mSearchAdapter.notifyDataSetChanged();
        scheduleSceneCapture(true);
    }

    private void onActiveSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {
        if (mOpt == null) return;
        mOpt.updateLiveScroll(scrollYPx, viewportTopPx);
        invalidateFloatingGlassOverlays();
        scheduleSceneCapture(idle);
    }

    private void invalidateFloatingGlassOverlays() {
        if (mBottomCapsule != null && mBottomCapsule.getVisibility() == View.VISIBLE) {
            mBottomCapsule.invalidate();
        }
        if (mOngoingCallPill != null && mOngoingCallPill.getVisibility() == View.VISIBLE) {
            mOngoingCallPill.invalidate();
        }
        if (mSearchOverlay != null && mSearchOverlay.getVisibility() == View.VISIBLE) {
            if (mUniversalSearchBar != null) mUniversalSearchBar.invalidate();
            if (mUniversalCloseOrb != null) mUniversalCloseOrb.invalidate();
        }
        if (mRecentsView != null && mRecentsView.getVisibility() == View.VISIBLE) {
            mRecentsView.invalidateGlassChrome();
        } else if (mFavoritesView != null && mFavoritesView.getVisibility() == View.VISIBLE) {
            mFavoritesView.invalidateGlassChrome();
        } else if (mContactsView != null && mContactsView.getVisibility() == View.VISIBLE) {
            mContactsView.invalidateGlassChrome();
        } else if (mVoicemailView != null && mVoicemailView.getVisibility() == View.VISIBLE) {
            DialerGlassEngine.invalidateGlassSubtree(mVoicemailView);
        } else if (mKeypadView != null && mKeypadView.getVisibility() == View.VISIBLE) {
            DialerGlassEngine.invalidateGlassSubtree(mKeypadView);
        }
    }

    private void scheduleSceneCapture(final boolean forceNow) {
        if (mPageContainer == null || mOpt == null) return;
        if (forceNow) {
            mCaptureForceRequested = true;
        }
        if (mCapturePostPending) {
            return;
        }
        mCapturePostPending = true;
        mPageContainer.post(() -> {
            mCapturePostPending = false;
            boolean force = mCaptureForceRequested;
            mCaptureForceRequested = false;
            if (isSearchOverlayActive()) {
                mOpt.captureLiveScene(mSearchOverlay, force);
            } else if (mKeypadView != null && mKeypadView.getVisibility() == View.VISIBLE) {
                mOpt.resetToNeutralBackdrop();
            } else {
                mOpt.captureLiveScene(mPageContainer, force);
            }
            invalidateFloatingGlassOverlays();
        });
    }

    private void selectTab(int tabIdx) {
        int clean = DialerGlassEngine.clamp(tabIdx, 0, mPages.length - 1);
        for (int i = 0; i < mPages.length; i++) {
            mPages[i].setVisibility(i == clean ? View.VISIBLE : View.GONE);
        }
        if (mOpt != null) {
            mOpt.resetLiveScroll();
            if (clean == IosVectorIcons.TAB_KEYPAD) {
                mOpt.resetToNeutralBackdrop();
                invalidateFloatingGlassOverlays();
            } else {
                syncVisibleTabLiveScroll();
            }
        }
        boolean dark = mOpt != null && mOpt.darkTheme;
        mRootFrame.setBackgroundColor(clean == IosVectorIcons.TAB_KEYPAD
                ? (dark ? Color.BLACK : Color.WHITE)
                : DialerGlassEngine.canvasBg(dark));
        if (clean == IosVectorIcons.TAB_RECENTS) {
            mRecentsView.refreshRoleBannerVisibility();
        }
        scheduleSceneCapture(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (mSearchOverlay != null && mSearchOverlay.getVisibility() == View.VISIBLE) {
            closeUniversalSearchOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri data = intent.getData();
        String type = intent.getType();

        if ("com.android.phone.action.RECENT_CALLS".equals(action)
                || "vnd.android.cursor.dir/calls".equals(type)) {
            selectTab(IosVectorIcons.TAB_RECENTS);
            mBottomCapsule.setSelectedTab(IosVectorIcons.TAB_RECENTS, false);
            return;
        }

        if (data != null && "voicemail".equalsIgnoreCase(data.getScheme())) {
            if (mBottomCapsule.isUnifiedMode()) {
                selectTab(IosVectorIcons.TAB_RECENTS);
                mBottomCapsule.setSelectedTab(IosVectorIcons.TAB_RECENTS, false);
                mRecentsView.setFilterMode(RecentsTabView.FILTER_VOICEMAIL);
            } else {
                selectTab(IosVectorIcons.TAB_VOICEMAIL);
                mBottomCapsule.setSelectedTab(IosVectorIcons.TAB_VOICEMAIL, false);
            }
            return;
        }

        if (Intent.ACTION_DIAL.equals(action)
                || Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_CALL_BUTTON.equals(action)) {
            selectTab(IosVectorIcons.TAB_KEYPAD);
            mBottomCapsule.setSelectedTab(IosVectorIcons.TAB_KEYPAD, false);
            if (data != null && "tel".equalsIgnoreCase(data.getScheme())) {
                String num = data.getSchemeSpecificPart();
                if (num != null && !num.isEmpty()) {
                    mKeypadView.setDigits(num);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOpt.register(this);
        mRepo.addListener(this);
        CallManager.get().addListener(this);
        boolean uni = TelecomHelper.isUnifiedCallsLayout(this);
        mBottomCapsule.setUnifiedMode(uni);
        mRecentsView.setUnifiedMode(uni);
        mRecentsView.refreshRoleBannerVisibility();
        onDataUpdated();
        onCallsUpdated();
        scheduleSceneCapture(true);
    }

    @Override
    protected void onPause() {
        CallManager.get().removeListener(this);
        mRepo.removeListener(this);
        mOpt.unregister(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mKeypadView != null) {
            mKeypadView.releaseAudio();
        }
        if (mOpt != null) {
            mOpt.setSoftBlurReadyCallback(null);
            mOpt.resetToNeutralBackdrop();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mOpt.updateThemeFromConfig(newConfig);
        applyThemeAndSystemBars();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TelecomHelper.REQ_RUNTIME_PERMS) {
            mRepo.refreshAllAsync();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == TelecomHelper.REQ_PICK_FAVORITE_CONTACT && resultCode == RESULT_OK && data != null) {
            Uri contactUri = data.getData();
            if (contactUri != null) {
                mRepo.starPickedContactUriAsync(contactUri);
            }
        } else if (requestCode == TelecomHelper.REQ_DEFAULT_DIALER_ROLE) {
            mRecentsView.refreshRoleBannerVisibility();
        }
    }

    @Override
    public void onGlassSettingsChanged() {
        applyThemeAndSystemBars();
    }

    @Override
    public void onDataUpdated() {
        mFavoritesView.bindFavorites(mRepo.getFavorites());
        mRecentsView.bindFavorites(mRepo.getFavorites());
        mRecentsView.bindRecents(mRepo.getRecents(), mRepo.getSims());
        mContactsView.bindContacts(
                mRepo.getContacts(), mRepo.getMyCardName(), mRepo.getMyCardSubtitle());
        mKeypadView.bindSims(mRepo.getSims());
        mKeypadView.onContactsChanged();
        mVoicemailView.bindSims(mRepo.getSims());
        mBottomCapsule.setMissedBadgeCount(mRepo.getMissedCount());
        scheduleSceneCapture(true);
    }

    @Override
    public void onCallsUpdated() {
        Call primary = CallManager.get().getPrimaryCall();
        int st = CallManager.callState(primary);
        boolean hasOngoing = primary != null
                && st != Call.STATE_DISCONNECTED
                && st != Call.STATE_DISCONNECTING;
        mOngoingCallPill.setVisibility(hasOngoing ? View.VISIBLE : View.GONE);
    }

    private void applyThemeAndSystemBars() {
        boolean dark = mOpt.darkTheme;
        boolean onKeypad = mKeypadView != null && mKeypadView.getVisibility() == View.VISIBLE;
        mRootFrame.setBackgroundColor(onKeypad
                ? (dark ? Color.BLACK : Color.WHITE)
                : DialerGlassEngine.canvasBg(dark));

        Window w = getWindow();
        if (w != null) {
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }

        mFavoritesView.refreshTheme();
        mRecentsView.refreshTheme();
        mContactsView.refreshTheme();
        mKeypadView.refreshTheme();
        mVoicemailView.refreshTheme();
        mOngoingCallPill.invalidate();
        mBottomCapsule.invalidate();
        if (mUniversalSearchBar != null) {
            mUniversalSearchBar.refreshTheme();
            mUniversalCloseOrb.invalidate();
            mSearchEmptyTv.setTextColor(DialerGlassEngine.secondaryInk(dark));
            if (mSearchOverlay.getVisibility() == View.VISIBLE) {
                mSearchOverlay.setBackgroundColor(dark ? 0xEB08090C : 0xECF2F3F8);
            }
            mSearchAdapter.notifyDataSetChanged();
        }
        syncVisibleTabLiveScroll();
        scheduleSceneCapture(true);
    }

    private final class UniversalSearchAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mSearchHits.size();
        }

        @Override
        public SearchHit getItem(int position) {
            return mSearchHits.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final SearchHit hit = mSearchHits.get(position);
            SearchHitRowView row = (convertView instanceof SearchHitRowView)
                    ? (SearchHitRowView) convertView
                    : new SearchHitRowView();
            row.bind(hit);
            return row;
        }
    }

    private final class SearchHitRowView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTitleP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Runnable mInvalidateSelf = this::invalidate;
        private SearchHit mHit;

        SearchHitRowView() {
            super(DialerMainActivity.this);
            mTitleP.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mTitleP.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16.5f, getResources().getDisplayMetrics()));
            mSubP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mSubP.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.5f, getResources().getDisplayMetrics()));
            setOnClickListener(v -> {
                if (mHit == null) return;
                DialerGlassEngine.performKeyboardTap(this);
                closeUniversalSearchOverlay();
                if (mHit.contact != null || mHit.recent != null) {
                    IosSheetsAndDialogs.showContactAndCallInfoSheet(
                            DialerMainActivity.this, mOpt,
                            mHit.contact, mHit.recent,
                            mKeypadView.getActiveSimHandle());
                } else if (mHit.number != null && !mHit.number.isEmpty()) {
                    TelecomHelper.placeCall(
                            DialerMainActivity.this, mHit.number,
                            mKeypadView.getActiveSimHandle(), false);
                }
            });
        }

        void bind(SearchHit hit) {
            mHit = hit;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(64f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mHit == null) return;
            boolean dark = mOpt.darkTheme;
            float cy = getHeight() * 0.5f;
            float avR = 20f * mDensity;
            float avCx = 18f * mDensity + avR;
            DialerGlassEngine.drawZeroAllocAvatar(
                    canvas, mRepo, avCx, cy, avR, mHit.title, mHit.photoUri, mP, mInvalidateSelf);

            float tx = avCx + avR + 13f * mDensity;
            mTitleP.setColor(DialerGlassEngine.primaryInk(dark));
            canvas.drawText(mHit.title, tx, cy - 3f * mDensity, mTitleP);
            mSubP.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mHit.subtitle, tx, cy + 15f * mDensity, mSubP);

            IosVectorIcons.drawPhoneHandset(
                    canvas, getWidth() - 28f * mDensity, cy,
                    18f * mDensity, DialerGlassEngine.accentBlue(dark), false, mP);
        }
    }
}
