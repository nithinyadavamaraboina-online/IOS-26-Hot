package com.realme.samurai.dialer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.provider.ContactsContract;
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

import java.util.Collections;
import java.util.List;

/**
 * Tab 1 — Authentic iOS 26 `Favourites` (`★`) Tab:
 *  - Top bar: `Edit` pill on left (when non-empty), `+` circular Liquid Glass button on right
 *  - Bold `34sp` `Favourites` title
 *  - Starred contacts rows (`44dp` circular avatar / monogram, bold name, `mobile` subtitle, `(i)` detail button)
 *  - Clean iOS empty state (`"No Favourites"`)
 */
public final class FavoritesTabView extends LinearLayout {

    public interface HostCallbacks {
        PhoneAccountHandle getActiveSimHandle();
        default void onSceneScrolled(int scrollYPx, int viewportTopPx, boolean idle) {}
    }

    private final Activity mActivity;
    private final DialerGlassEngine.LiveOpticalState mOpt;
    private final HostCallbacks mHost;
    private final float mDensity;
    private final DialerGlassEngine.AnchorListScrollTracker mScrollTracker =
            new DialerGlassEngine.AnchorListScrollTracker();
    private final int[] mListLoc = new int[2];

    private final DialerGlassWidgets.GlassPillButton mEditBtn;
    private final DialerGlassWidgets.GlassCircleButton mAddBtn;
    private final TextView mLargeTitle;
    private final ListView mListView;
    private final LinearLayout mEmptyState;
    private final TextView mEmptyTitle;
    private final TextView mEmptySub;
    private final FavoritesAdapter mAdapter = new FavoritesAdapter();

    private boolean mEditMode = false;
    private List<DialerDataRepository.FavoriteItem> mItems = Collections.emptyList();

    public FavoritesTabView(
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

        // Top action row
        FrameLayout topBar = new FrameLayout(activity);
        topBar.setPadding(padH, Math.round(6f * mDensity), padH, Math.round(2f * mDensity));

        mEditBtn = new DialerGlassWidgets.GlassPillButton(activity, opt, "Edit");
        mEditBtn.setPillMetrics(34f, 14.5f, false);
        mEditBtn.setOnClickListener(v -> {
            mEditMode = !mEditMode;
            mEditBtn.setText(mEditMode ? "Done" : "Edit");
            mAdapter.notifyDataSetChanged();
        });
        FrameLayout.LayoutParams editLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        topBar.addView(mEditBtn, editLp);

        mAddBtn = new DialerGlassWidgets.GlassCircleButton(
                activity, opt, 36f,
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawPlus(
                        c, cx, cy, sz, col, 2.2f * mDensity, p));
        mAddBtn.setOnClickListener(v -> openPickContactToStar());
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.addView(mAddBtn, addLp);

        addView(topBar, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(46f * mDensity)));

        // Large 34sp iOS Title
        mLargeTitle = new TextView(activity);
        mLargeTitle.setText("Favorites");
        mLargeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        mLargeTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mLargeTitle.setPadding(padH, Math.round(2f * mDensity), padH, Math.round(10f * mDensity));
        addView(mLargeTitle, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Body frame (ListView + Empty state)
        FrameLayout contentFrame = new FrameLayout(activity);
        mListView = new ListView(activity);
        mListView.setDivider(null);
        mListView.setDividerHeight(0);
        mListView.setClipToPadding(false);
        mListView.setPadding(0, 0, 0, Math.round(112f * mDensity));
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

        mEmptyState = new LinearLayout(activity);
        mEmptyState.setOrientation(VERTICAL);
        mEmptyState.setGravity(Gravity.CENTER);
        mEmptyState.setPadding(padH * 2, 0, padH * 2, Math.round(80f * mDensity));

        View starGlyph = new View(activity) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                boolean dark = mOpt != null && mOpt.darkTheme;
                DialerGlassEngine.drawZeroAllocTabIcon(
                        c, IosVectorIcons.TAB_FAVORITES,
                        getWidth() * 0.5f, getHeight() * 0.5f,
                        48f * mDensity, DialerGlassEngine.tertiaryInk(dark), mP);
            }
        };
        mEmptyState.addView(starGlyph, new LayoutParams(
                Math.round(56f * mDensity), Math.round(56f * mDensity)));

        mEmptyTitle = new TextView(activity);
        mEmptyTitle.setText("No Favorites");
        mEmptyTitle.setGravity(Gravity.CENTER);
        mEmptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        mEmptyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LayoutParams etLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = Math.round(10f * mDensity);
        mEmptyState.addView(mEmptyTitle, etLp);

        mEmptySub = new TextView(activity);
        mEmptySub.setText("Tap + to add contacts you call most often.");
        mEmptySub.setGravity(Gravity.CENTER);
        mEmptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        LayoutParams esLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        esLp.topMargin = Math.round(4f * mDensity);
        mEmptyState.addView(mEmptySub, esLp);

        contentFrame.addView(mEmptyState, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addView(contentFrame, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshTheme();
    }

    private void openPickContactToStar() {
        try {
            Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
            mActivity.startActivityForResult(pick, TelecomHelper.REQ_PICK_FAVORITE_CONTACT);
        } catch (Throwable ignored) {
            TelecomHelper.openInsertContact(mActivity, null);
        }
    }

    public void bindFavorites(List<DialerDataRepository.FavoriteItem> items) {
        mItems = (items != null) ? items : Collections.emptyList();
        if (mItems.isEmpty()) {
            mEditMode = false;
            mEditBtn.setText("Edit");
            mScrollTracker.reset();
        }
        mEditBtn.setVisibility(mItems.isEmpty() ? INVISIBLE : VISIBLE);
        mEmptyState.setVisibility(mItems.isEmpty() ? VISIBLE : GONE);
        mListView.setVisibility(mItems.isEmpty() ? GONE : VISIBLE);
        mAdapter.notifyDataSetChanged();
        if (getVisibility() == VISIBLE && mHost != null) {
            mListView.post(() -> {
                if (getVisibility() == VISIBLE && mHost != null) {
                    int sy = mItems.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
                    mListView.getLocationOnScreen(mListLoc);
                    mHost.onSceneScrolled(sy, mListLoc[1], true);
                }
            });
        }
    }

    public void syncLiveScrollState() {
        if (mOpt == null) return;
        int sy = mItems.isEmpty() ? 0 : mScrollTracker.onListScrolled(mListView);
        mListView.getLocationOnScreen(mListLoc);
        mOpt.updateLiveScroll(sy, mListLoc[1]);
    }

    public void invalidateGlassChrome() {
        mEditBtn.invalidate();
        mAddBtn.invalidate();
        int childCount = mListView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View c = mListView.getChildAt(i);
            if (c != null) c.invalidate();
        }
    }

    public void refreshTheme() {
        boolean dark = mOpt != null && mOpt.darkTheme;
        mLargeTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mEmptyTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
        mEmptySub.setTextColor(DialerGlassEngine.secondaryInk(dark));
        mEditBtn.invalidate();
        mAddBtn.invalidate();
        mAdapter.notifyDataSetChanged();
    }

    private final class FavoritesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public DialerDataRepository.FavoriteItem getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mItems.get(position).contactId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FavoriteRowView row = (convertView instanceof FavoriteRowView)
                    ? (FavoriteRowView) convertView
                    : new FavoriteRowView(getContext());
            row.bind(mItems.get(position), position == mItems.size() - 1);
            return row;
        }
    }

    private final class FavoriteRowView extends View {
        private final DialerGlassEngine.GlassSurfaceInstance mMicroOrbGlass =
                new DialerGlassEngine.GlassSurfaceInstance();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mOrbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mNamePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final int[] mLoc = new int[2];
        private final Runnable mInvalidateSelf = this::invalidate;
        private DialerDataRepository.FavoriteItem mItem;
        private boolean mLast = false;

        FavoriteRowView(Context ctx) {
            super(ctx);
            mRimPaint.setStyle(Paint.Style.STROKE);
            mRimPaint.setStrokeWidth(0.95f * mDensity);
            mNamePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mNamePaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 17f, getResources().getDisplayMetrics()));
            mSubPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mSubPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 13.5f, getResources().getDisplayMetrics()));

            setOnClickListener(v -> {
                if (mItem == null) return;
                DialerGlassEngine.performKeyboardTap(this);
                if (mEditMode) {
                    DialerDataRepository.get(getContext()).setContactStarredAsync(mItem.contactId, false);
                    return;
                }
                TelecomHelper.placeCall(
                        getContext(), mItem.number,
                        mHost != null ? mHost.getActiveSimHandle() : null, false);
            });
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_UP && mItem != null) {
                float infoLeft = getWidth() - 54f * mDensity;
                if (!mEditMode && event.getX() >= infoLeft) {
                    DialerGlassEngine.performKeyboardTap(this);
                    DialerDataRepository.ContactItem c =
                            DialerDataRepository.get(getContext()).findContactForNumber(mItem.number);
                    IosSheetsAndDialogs.showContactAndCallInfoSheet(
                            mActivity, mOpt, c, null,
                            mHost != null ? mHost.getActiveSimHandle() : null);
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        void bind(DialerDataRepository.FavoriteItem item, boolean isLast) {
            mItem = item;
            mLast = isLast;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec),
                    Math.round(66f * mDensity));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mItem == null) return;
            boolean dark = mOpt != null && mOpt.darkTheme;
            int w = getWidth();
            int h = getHeight();
            float cy = h * 0.5f;

            float xOff = 18f * mDensity;
            if (mEditMode) {
                float mr = 10.5f * mDensity;
                float mcx = xOff + mr;
                mPaint.reset();
                mPaint.setAntiAlias(true);
                mPaint.setColor(DialerGlassEngine.IOS_RED);
                canvas.drawCircle(mcx, cy, mr, mPaint);
                mPaint.setColor(0xFFFFFFFF);
                mPaint.setStrokeWidth(2.0f * mDensity);
                canvas.drawLine(mcx - 5f * mDensity, cy, mcx + 5f * mDensity, cy, mPaint);
                xOff += 32f * mDensity;
            }

            // 44dp circular avatar
            float avR = 22f * mDensity;
            float avCx = xOff + avR;
            DialerGlassEngine.drawZeroAllocAvatar(
                    canvas, DialerDataRepository.get(getContext()),
                    avCx, cy, avR, mItem.displayName, mItem.photoUri, mPaint, mInvalidateSelf);

            float textX = avCx + avR + 13f * mDensity;
            mNamePaint.setColor(DialerGlassEngine.primaryInk(dark));
            canvas.drawText(mItem.displayName, textX, cy - 3.5f * mDensity, mNamePaint);

            IosVectorIcons.drawPhoneHandset(
                    canvas, textX + 5.5f * mDensity, cy + 12f * mDensity,
                    11f * mDensity, DialerGlassEngine.secondaryInk(dark), false, mPaint);
            mSubPaint.setColor(DialerGlassEngine.secondaryInk(dark));
            canvas.drawText(mItem.typeLabel, textX + 15f * mDensity, cy + 16f * mDensity, mSubPaint);

            // Right 32dp Refractive Liquid Glass (i) micro-orb
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
                        canvas, orbCx, cy,
                        10.5f * mDensity, DialerGlassEngine.accentBlue(dark), mPaint);
            }

            if (!mLast) {
                mPaint.reset();
                mPaint.setColor(DialerGlassEngine.separatorColor(dark));
                canvas.drawRect(textX, h - Math.max(1f, 0.65f * mDensity), w, h, mPaint);
            }
        }
    }
}
