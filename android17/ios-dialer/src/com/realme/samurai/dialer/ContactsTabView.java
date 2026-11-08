package com.realme.samurai.dialer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.telecom.PhoneAccountHandle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab 3 — Authentic iOS 26 `Contacts` (`👤`) Tab:
 *  - Top bar: circular `+` Liquid Glass button on right (`ContactsContract.Intents.Insert.ACTION`)
 *  - Bold `34sp` `Contacts` title + rounded iOS Search bar (`🔍 Search` + clear button)
 *  - `"My Card"` header at top + clean `A–Z #` section headers
 *  - Interactive right-edge iOS `A–Z #` Alphabet Index Scrubber with `KEYBOARD_TAP` haptic tick
 *  - Tapping a contact opens the iOS Contact Sheet (`call` immediately or `message` -> `smsto:`)
 */
public final class ContactsTabView extends LinearLayout {

    public interface HostCallbacks {
        PhoneAccountHandle getActiveSimHandle();
        default void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {}
    }

    private static final int ROW_MY_CARD = 0;
    private static final int ROW_SECTION_HEADER = 1;
    private static final int ROW_CONTACT = 2;

    private static final class DisplayRow {
        final int kind;
        final String sectionTitle;
        final DialerDataRepository.ContactItem contact;
        final boolean lastInSection;

        DisplayRow(int kind, String sectionTitle, DialerDataRepository.ContactItem contact, boolean lastInSection) {
            this.kind = kind;
            this.sectionTitle = sectionTitle;
            this.contact = contact;
            this.lastInSection = lastInSection;
        }
    }

    private final Activity mActivity;
    private final DialerGlassEngine.LiveOpticalState mOpt;
    private final HostCallbacks mHost;
    private final float mDensity;

    private final DialerGlassWidgets.GlassCircleButton mAddBtn;
    private final TextView mLargeTitle;
    private final DialerGlassWidgets.IosSearchFieldBar mSearchBar;
    private final ListView mListView;
    private final DialerGlassWidgets.AlphabetIndexScrubberView mScrubber;
    private final TextView mEmptyView;
    private final ContactsAdapter mAdapter = new ContactsAdapter();
    private final Map<String, Integer> mSectionPositions = new HashMap<>();
    private final DialerGlassEngine.AnchorListScrollTracker mScrollTracker =
            new DialerGlassEngine.AnchorListScrollTracker();
    private final int[] mListLoc = new int[2];

    private String mQuery = "";
    private String mLastTrackerQuery = "";
    private String mMyCardName = "My Card";
    private String mMyCardSub = "Set up your card";
    private List<DialerDataRepository.ContactItem> mAllContacts = Collections.emptyList();
    private List<DisplayRow> mRows = Collections.emptyList();

    public ContactsTabView(
            Activity activity,
            DialerGlassEngine.LiveOpticalState opt,
            HostCallbacks host) {
        super(activity);
        mActivity = activity;
        mOpt = opt;
        mHost = host;
        mDensity = activity.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        int padH = Math.round(18f * mDensity);

        // Top Bar with + button on right
        FrameLayout topBar = new FrameLayout(activity);
        topBar.setPadding(padH, Math.round(6f * mDensity), padH, Math.round(2f * mDensity));

        mAddBtn = new DialerGlassWidgets.GlassCircleButton(
                activity, opt, 36f,
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawPlus(
                        c, cx, cy, sz, col, 2.2f * mDensity, p));
        mAddBtn.setOnClickListener(v -> TelecomHelper.openInsertContact(mActivity, null));
        topBar.addView(mAddBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        addView(topBar, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * mDensity)));

        // Large 34sp Title
        mLargeTitle = new TextView(activity);
        mLargeTitle.setText("Contacts");
        mLargeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        mLargeTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mLargeTitle.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(6f * mDensity));
        addView(mLargeTitle, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Search Bar
        mSearchBar = new DialerGlassWidgets.IosSearchFieldBar(activity, opt, "Search");
        mSearchBar.setOnQueryChangeListener(q -> {
            mQuery = q != null ? q.trim() : "";
            rebuildRows();
        });
        LayoutParams sbLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(38f * mDensity));
        sbLp.leftMargin = padH;
        sbLp.rightMargin = padH;
        sbLp.bottomMargin = Math.round(6f * mDensity);
        addView(mSearchBar, sbLp);

        // List + Right-edge A-Z Scrubber
        FrameLayout contentFrame = new FrameLayout(activity);

        mListView = new ListView(activity);
        mListView.setDivider(null);
        mListView.setDividerHeight(0);
        mListView.setVerticalScrollBarEnabled(false);
        mListView.setClipToPadding(false);
        mListView.setPadding(0, 0, Math.round(22f * mDensity), Math.round(112f * mDensity));
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

        mScrubber = new DialerGlassWidgets.AlphabetIndexScrubberView(activity, opt);
        mScrubber.setOnLetterScrubbedListener(letter -> {
            Integer pos = mSectionPositions.get(letter);
            if (pos != null && pos >= 0 && pos < mRows.size()) {
                mListView.setSelection(pos);
                mListView.post(() -> {
                    int sy = mScrollTracker.onListScrolled(mListView);
                    if (getVisibility() == VISIBLE && mHost != null) {
                        mListView.getLocationOnScreen(mListLoc);
                        mHost.onSceneScrolled(sy, mListLoc[1], true);
                    }
                });
            }
        });
        FrameLayout.LayoutParams scLp = new FrameLayout.LayoutParams(
                Math.round(24f * mDensity),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        scLp.topMargin = Math.round(12f * mDensity);
        scLp.bottomMargin = Math.round(110f * mDensity);
        scLp.rightMargin = Math.round(2f * mDensity);
        contentFrame.addView(mScrubber, scLp);

        mEmptyView = new TextView(activity);
        mEmptyView.setText("No Contacts");
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        mEmptyView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mEmptyView.setPadding(padH, 0, padH, Math.round(80f * mDensity));
        contentFrame.addView(mEmptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addView(contentFrame, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rebuildRows();
        refreshTheme();
    }

    public void bindContacts(
            List<DialerDataRepository.ContactItem> contacts,
            String myCardName,
            String myCardSubtitle) {
        mAllContacts = (contacts != null) ? contacts : Collections.emptyList();
        mMyCardName = (myCardName != null) ? myCardName : "My Card";
        mMyCardSub = (myCardSubtitle != null) ? myCardSubtitle : "Set up your card";
        rebuildRows();
    }

    private void rebuildRows() {
        mSectionPositions.clear();
        List<DisplayRow> rows = new ArrayList<>();
        String qLower = mQuery.toLowerCase(Locale.getDefault());

        if (qLower.isEmpty()) {
            rows.add(new DisplayRow(ROW_MY_CARD, null, null, true));
        }

        List<DialerDataRepository.ContactItem> filtered = new ArrayList<>();
        for (DialerDataRepository.ContactItem c : mAllContacts) {
            if (!qLower.isEmpty()) {
                boolean m = c.displayName.toLowerCase(Locale.getDefault()).contains(qLower);
                if (!m) {
                    for (DialerDataRepository.PhoneEntry pe : c.phones) {
                        if (pe.number.contains(qLower) || pe.formattedNumber.toLowerCase(Locale.getDefault()).contains(qLower)) {
                            m = true;
                            break;
                        }
                    }
                }
                if (!m) continue;
            }
            filtered.add(c);
        }

        String curSec = null;
        for (int i = 0; i < filtered.size(); i++) {
            DialerDataRepository.ContactItem c = filtered.get(i);
            if (!c.sectionLetter.equals(curSec)) {
                curSec = c.sectionLetter;
                mSectionPositions.put(curSec, rows.size());
                rows.add(new DisplayRow(ROW_SECTION_HEADER, curSec, null, false));
            }
            boolean lastInSec = (i == filtered.size() - 1)
                    || !filtered.get(i + 1).sectionLetter.equals(curSec);
            rows.add(new DisplayRow(ROW_CONTACT, null, c, lastInSec));
        }

        mRows = rows;
        boolean showEmpty = filtered.isEmpty() && !qLower.isEmpty();
        mEmptyView.setText("No Results for \"" + mQuery + "\"");
        mEmptyView.setVisibility(showEmpty ? VISIBLE : GONE);
        mScrubber.setVisibility(mAllContacts.isEmpty() || !qLower.isEmpty() ? GONE : VISIBLE);
        mAdapter.notifyDataSetChanged();
        boolean queryChanged = !java.util.Objects.equals(qLower, mLastTrackerQuery);
        mLastTrackerQuery = qLower;
        if (mRows.isEmpty() || queryChanged) {
            mScrollTracker.reset();
        }
        if (getVisibility() == VISIBLE && mHost != null) {
            mListView.post(() -> {
                if (getVisibility() == VISIBLE && mHost != null) {
                    int sy = mRows.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
                    mListView.getLocationOnScreen(mListLoc);
                    mHost.onSceneScrolled(sy, mListLoc[1], true);
                }
            });
        }
    }

    public void syncLiveScrollState() {
        if (mOpt == null) return;
        int sy = mRows.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
        mListView.getLocationOnScreen(mListLoc);
        mOpt.updateLiveScroll(sy, mListLoc[1]);
    }

    public void invalidateGlassChrome() {
        mAddBtn.invalidate();
        mSearchBar.invalidate();
        mScrubber.invalidate();
    }

    public void refreshTheme() {
        boolean dark = mOpt != null && mOpt.darkTheme;
        mLargeTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mEmptyView.setTextColor(DialerGlassEngine.secondaryInk(dark));
        mSearchBar.refreshTheme();
        mAddBtn.invalidate();
        mScrubber.invalidate();
        mAdapter.notifyDataSetChanged();
    }

    private final class ContactsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public DisplayRow getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).kind;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DisplayRow r = mRows.get(position);
            if (r.kind == ROW_MY_CARD) {
                MyCardRowView v = (convertView instanceof MyCardRowView)
                        ? (MyCardRowView) convertView
                        : new MyCardRowView(getContext());
                v.invalidate();
                return v;
            }
            if (r.kind == ROW_SECTION_HEADER) {
                SectionHeaderView v = (convertView instanceof SectionHeaderView)
                        ? (SectionHeaderView) convertView
                        : new SectionHeaderView(getContext());
                v.bind(r.sectionTitle);
                return v;
            }
            ContactRowView v = (convertView instanceof ContactRowView)
                    ? (ContactRowView) convertView
                    : new ContactRowView(getContext());
            v.bind(r.contact, r.lastInSection);
            return v;
        }
    }

    private final class MyCardRowView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

        MyCardRowView(Context ctx) {
            super(ctx);
            mTitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mTitlePaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 19f, getResources().getDisplayMetrics()));
            mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mSubPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.5f, getResources().getDisplayMetrics()));
            setOnClickListener(v -> {
                DialerGlassEngine.performKeyboardTap(this);
                TelecomHelper.openInsertContact(mActivity, null);
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(74f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean dark = mOpt != null && mOpt.darkTheme;
            float cy = getHeight() * 0.5f;
            float avR = 26f * mDensity;
            float avCx = 18f * mDensity + avR;
            DialerGlassEngine.drawZeroAllocAvatar(
                    canvas, DialerDataRepository.get(getContext()),
                    avCx, cy, avR, mMyCardName, null, mPaint, null);

            float tx = avCx + avR + 14f * mDensity;
            mTitlePaint.setColor(DialerGlassEngine.primaryInk(dark));
            canvas.drawText(mMyCardName, tx, cy - 3f * mDensity, mTitlePaint);

            mSubPaint.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mMyCardSub, tx, cy + 16f * mDensity, mSubPaint);
        }
    }

    private final class SectionHeaderView extends View {
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String mLetter = "";

        SectionHeaderView(Context ctx) {
            super(ctx);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14.5f, getResources().getDisplayMetrics()));
        }

        void bind(String letter) {
            mLetter = letter != null ? letter : "";
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(30f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            boolean dark = mOpt != null && mOpt.darkTheme;
            mTextPaint.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mLetter, 18f * mDensity, getHeight() - 7.5f * mDensity, mTextPaint);
            mLinePaint.setColor(DialerGlassEngine.separatorColor(dark));
            canvas.drawRect(
                    18f * mDensity,
                    getHeight() - Math.max(1f, 0.65f * mDensity),
                    getWidth(),
                    getHeight(),
                    mLinePaint);
        }
    }

    private final class ContactRowView extends View {
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint.FontMetrics mFm = new Paint.FontMetrics();
        private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private DialerDataRepository.ContactItem mContact;
        private boolean mLast = false;

        ContactRowView(Context ctx) {
            super(ctx);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 17f, getResources().getDisplayMetrics()));
            setOnClickListener(v -> {
                if (mContact == null) return;
                DialerGlassEngine.performKeyboardTap(this);
                IosSheetsAndDialogs.showContactAndCallInfoSheet(
                        mActivity, mOpt, mContact, null,
                        mHost != null ? mHost.getActiveSimHandle() : null);
            });
        }

        void bind(DialerDataRepository.ContactItem item, boolean lastInSection) {
            mContact = item;
            mLast = lastInSection;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(46f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mContact == null) return;
            boolean dark = mOpt != null && mOpt.darkTheme;
            mTextPaint.setColor(DialerGlassEngine.primaryInk(dark));
            mTextPaint.getFontMetrics(mFm);
            float baseline = getHeight() * 0.5f - (mFm.ascent + mFm.descent) * 0.5f;
            canvas.drawText(mContact.displayName, 18f * mDensity, baseline, mTextPaint);

            if (!mLast) {
                mLinePaint.setColor(DialerGlassEngine.separatorColor(dark));
                canvas.drawRect(
                        18f * mDensity,
                        getHeight() - Math.max(1f, 0.65f * mDensity),
                        getWidth(),
                        getHeight(),
                        mLinePaint);
            }
        }
    }
}
