package com.realme.samurai.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiGlassSearchBar;
import com.realme.samurai.glass.SamuraiSpring;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Clean-Room 90 Hz iOS 26 Liquid Glass Notes (`NotesMainActivity`).
 *
 * Features:
 * - SQLite-backed persistence (`NotesDatabase`) with rich pre-seeded notes & checklists
 * - Folders & Notes List View: Bold "Notes" header, Folder filter pills (`All Notes`, `Pinned`,
 *   `Personal`, `Work`, `Ideas` + `+ Folder`), Pinned & Recent grouped `SamuraiGlassCard` platters
 * - Bottom Floating `SamuraiGlassSearchBar` + Warm Gold (`#FFB800`/`#FFD60A`) `WarmGoldComposeOrb`
 * - Rich Note Editor View: Title, interactive Checklists, Body, and Floating Liquid Glass
 *   Formatting Bar (`Pin ★`, `Checklist ☑`, `Heading/Bullet`, `Share`, `Delete`)
 */
public final class NotesMainActivity extends Activity {

    private static final int GOLD_DARK = 0xFFFFD60A;
    private static final int GOLD_LIGHT = 0xFFE09F00;

    private final SamuraiGlassPrefs.Snapshot mPrefs = new SamuraiGlassPrefs.Snapshot();
    private ContentObserver mPrefsObserver;
    private NotesDatabase mDb;

    private NotesBackdropFrame mRoot;

    // Screen 1: List View
    private FrameLayout mListScreen;
    private LinearLayout mListContentColumn;
    private TextView mHeaderTitle;
    private TextView mHeaderCount;
    private LinearLayout mFolderPillRow;
    private LinearLayout mNotesSectionsContainer;
    private LinearLayout mBottomBarRow;
    private SamuraiGlassSearchBar mSearchBar;
    private WarmGoldComposeOrb mComposeOrb;

    // Screen 2: Rich Note Editor View
    private FrameLayout mEditorScreen;
    private LinearLayout mEditorTopBar;
    private SamuraiGlassCard mBackPill;
    private TextView mBackPillText;
    private SamuraiGlassCard mFolderAssignPill;
    private TextView mFolderAssignText;
    private SamuraiGlassCard mDonePill;
    private TextView mDonePillText;

    private ScrollView mEditorScroll;
    private SamuraiGlassCard mEditorPaperCard;
    private TextView mEditorTimestamp;
    private EditText mEditorTitle;
    private LinearLayout mChecklistContainer;
    private TextView mAddChecklistInlineBtn;
    private EditText mEditorBody;

    private SamuraiGlassCard mFormattingBar;
    private TextView mFmtPinBtn;
    private TextView mFmtCheckBtn;
    private TextView mFmtHeadingBtn;
    private TextView mFmtShareBtn;
    private TextView mFmtDeleteBtn;

    private final List<SamuraiGlassCard> mPersistentGlassCards = new ArrayList<>();
    private final List<SamuraiGlassCard> mDynamicListCards = new ArrayList<>();
    private final List<ChecklistRowView> mActiveChecklistViews = new ArrayList<>();

    private String mSelectedFolder = NotesDatabase.FILTER_ALL;
    private String mSearchQuery = "";
    private NotesDatabase.NoteRecord mActiveNote = null;
    private boolean mInEditor = false;
    private boolean mSuppressAutoSave = false;
    private SamuraiSpring mScreenSpring;

    private final OnBackInvokedCallback mBackCallback = () -> {
        if (mInEditor) {
            closeEditor(true);
        } else {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SamuraiGlassPrefs.configure90HzEdgeToEdge(this);
        SamuraiGlassPrefs.read(this, mPrefs);

        mDb = new NotesDatabase(this);
        buildUi();
        applyThemeColors();
        rebuildFolderPills();
        refreshNotesList();
        handleIncomingShareIntent(getIntent());

        mPrefsObserver = SamuraiGlassPrefs.registerObserver(this, () -> {
            SamuraiGlassPrefs.read(this, mPrefs);
            SamuraiAgslEngine.invalidateWallpaperCache();
            applyThemeColors();
            rebuildFolderPills();
            refreshNotesList();
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            String sharedSubj = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                NotesDatabase.NoteRecord rec = new NotesDatabase.NoteRecord();
                rec.title = (sharedSubj != null && !sharedSubj.trim().isEmpty()) ? sharedSubj.trim() : "Shared Note";
                rec.body = sharedText.trim();
                rec.folder = "Personal";
                mDb.saveNote(rec);
                refreshNotesList();
                openEditor(rec);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        SamuraiGlassPrefs.read(this, mPrefs);
        applyThemeColors();
        rebuildFolderPills();
        refreshNotesList();
    }

    @Override
    protected void onPause() {
        if (mInEditor && mActiveNote != null) {
            persistActiveEditorNote();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        SamuraiGlassPrefs.unregisterObserver(this, mPrefsObserver);
        if (mScreenSpring != null) mScreenSpring.cancel();
        if (mDb != null) mDb.close();
        super.onDestroy();
    }

    private void buildUi() {
        mRoot = new NotesBackdropFrame(this);

        buildListScreen();
        buildEditorScreen();

        mRoot.addView(mListScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRoot.addView(mEditorScreen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mScreenSpring = new SamuraiSpring(0f, (val, vel) -> {
            float w = Math.max(dp(360f), mRoot.getWidth());
            mListScreen.setTranslationX(-0.26f * w * val);
            mListScreen.setAlpha(1f - 0.45f * Math.min(1f, Math.max(0f, val)));
            mEditorScreen.setTranslationX((1f - val) * w);
            if (!mInEditor && val <= 0.01f) {
                mEditorScreen.setVisibility(View.GONE);
            }
        }).setTuning(30f, 0.84f);

        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            int topInset = Math.max(dp(42f), sys.top + dp(10f));
            int bottomBarInset = Math.max(dp(16f), Math.max(sys.bottom, ime.bottom) + dp(10f));

            mListContentColumn.setPadding(dp(16f), topInset, dp(16f), dp(98f) + sys.bottom);
            FrameLayout.LayoutParams bLp = (FrameLayout.LayoutParams) mBottomBarRow.getLayoutParams();
            bLp.bottomMargin = bottomBarInset;
            mBottomBarRow.setLayoutParams(bLp);

            mEditorTopBar.setPadding(dp(16f), topInset, dp(16f), dp(8f));
            mEditorScroll.setPadding(dp(16f), dp(4f), dp(16f), dp(92f) + Math.max(sys.bottom, ime.bottom));
            FrameLayout.LayoutParams fmtLp = (FrameLayout.LayoutParams) mFormattingBar.getLayoutParams();
            fmtLp.bottomMargin = bottomBarInset;
            mFormattingBar.setLayoutParams(fmtLp);
            return insets;
        });

        setContentView(mRoot);
    }

    private void buildListScreen() {
        mListScreen = new FrameLayout(this);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipToPadding(false);

        mListContentColumn = new LinearLayout(this);
        mListContentColumn.setOrientation(LinearLayout.VERTICAL);
        mListContentColumn.setClipChildren(false);
        mListContentColumn.setClipToPadding(false);
        mListContentColumn.setPadding(dp(16f), dp(48f), dp(16f), dp(110f));

        // Bold iOS "Notes" large title + count subtitle
        mHeaderTitle = new TextView(this);
        mHeaderTitle.setText("Notes");
        mHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f);
        mHeaderTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mListContentColumn.addView(mHeaderTitle);

        mHeaderCount = new TextView(this);
        mHeaderCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mHeaderCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mHeaderCount.setPadding(dp(2f), dp(2f), 0, dp(12f));
        mListContentColumn.addView(mHeaderCount);

        // Folder Filter Pills Horizontal Strip
        HorizontalScrollView folderScroll = new HorizontalScrollView(this);
        folderScroll.setHorizontalScrollBarEnabled(false);
        folderScroll.setClipToPadding(false);
        mFolderPillRow = new LinearLayout(this);
        mFolderPillRow.setOrientation(LinearLayout.HORIZONTAL);
        mFolderPillRow.setGravity(Gravity.CENTER_VERTICAL);
        mFolderPillRow.setPadding(0, dp(2f), dp(8f), dp(10f));
        folderScroll.addView(mFolderPillRow);
        mListContentColumn.addView(folderScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pinned & Recent grouped sections container
        mNotesSectionsContainer = new LinearLayout(this);
        mNotesSectionsContainer.setOrientation(LinearLayout.VERTICAL);
        mNotesSectionsContainer.setClipChildren(false);
        mNotesSectionsContainer.setClipToPadding(false);
        mListContentColumn.addView(mNotesSectionsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listScroll.addView(mListContentColumn);
        mListScreen.addView(listScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom Floating SamuraiGlassSearchBar + Warm Gold Compose Orb
        mBottomBarRow = new LinearLayout(this);
        mBottomBarRow.setOrientation(LinearLayout.HORIZONTAL);
        mBottomBarRow.setGravity(Gravity.CENTER_VERTICAL);
        mBottomBarRow.setClipChildren(false);
        mBottomBarRow.setClipToPadding(false);

        mSearchBar = new SamuraiGlassSearchBar(
                this,
                mPrefs,
                "Search notes & checklists",
                query -> {
                    mSearchQuery = query != null ? query : "";
                    refreshNotesList();
                });
        mPersistentGlassCards.add(mSearchBar);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchLp.rightMargin = dp(10f);
        mBottomBarRow.addView(mSearchBar, searchLp);

        mComposeOrb = new WarmGoldComposeOrb(this, mPrefs, this::createNewNote);
        mBottomBarRow.addView(mComposeOrb, new LinearLayout.LayoutParams(dp(50f), dp(50f)));

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomLp.setMargins(dp(16f), 0, dp(16f), dp(22f));
        mListScreen.addView(mBottomBarRow, bottomLp);
    }

    private void buildEditorScreen() {
        mEditorScreen = new FrameLayout(this);
        mEditorScreen.setVisibility(View.GONE);

        LinearLayout editorRootCol = new LinearLayout(this);
        editorRootCol.setOrientation(LinearLayout.VERTICAL);
        editorRootCol.setClipChildren(false);

        // Top Navigation Bar
        mEditorTopBar = new LinearLayout(this);
        mEditorTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mEditorTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mEditorTopBar.setPadding(dp(16f), dp(48f), dp(16f), dp(8f));

        mBackPill = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(20f).setInteractivePressScale(true);
        mBackPill.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        mBackPillText = new TextView(this);
        mBackPillText.setText("\u2039  Notes");
        mBackPillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        mBackPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mBackPill.addView(mBackPillText);
        mBackPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            closeEditor(true);
        });
        mPersistentGlassCards.add(mBackPill);
        mEditorTopBar.addView(mBackPill);

        View topSpacer = new View(this);
        mEditorTopBar.addView(topSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        mFolderAssignPill = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(18f).setInteractivePressScale(true);
        mFolderAssignPill.setPadding(dp(12f), dp(7f), dp(12f), dp(7f));
        mFolderAssignText = new TextView(this);
        mFolderAssignText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        mFolderAssignText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mFolderAssignPill.addView(mFolderAssignText);
        mFolderAssignPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            showFolderAssignmentPicker();
        });
        mPersistentGlassCards.add(mFolderAssignPill);
        LinearLayout.LayoutParams folderAssignLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        folderAssignLp.rightMargin = dp(8f);
        mEditorTopBar.addView(mFolderAssignPill, folderAssignLp);

        mDonePill = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(20f)
                .setTint(0xFFFFB800, 0.58f)
                .setInteractivePressScale(true);
        mDonePill.setPadding(dp(15f), dp(8f), dp(15f), dp(8f));
        mDonePillText = new TextView(this);
        mDonePillText.setText("Done");
        mDonePillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        mDonePillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mDonePill.addView(mDonePillText);
        mDonePill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            hideKeyboard();
            persistActiveEditorNote();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        mPersistentGlassCards.add(mDonePill);
        mEditorTopBar.addView(mDonePill);

        editorRootCol.addView(mEditorTopBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Scrollable Rich Note Glass Card
        mEditorScroll = new ScrollView(this);
        mEditorScroll.setVerticalScrollBarEnabled(false);
        mEditorScroll.setClipToPadding(false);
        mEditorScroll.setPadding(dp(16f), dp(6f), dp(16f), dp(110f));

        mEditorPaperCard = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(26f);
        mEditorPaperCard.setPadding(dp(18f), dp(18f), dp(18f), dp(24f));
        mPersistentGlassCards.add(mEditorPaperCard);

        mEditorTimestamp = new TextView(this);
        mEditorTimestamp.setGravity(Gravity.CENTER_HORIZONTAL);
        mEditorTimestamp.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        mEditorTimestamp.setPadding(0, 0, 0, dp(12f));
        mEditorPaperCard.addView(mEditorTimestamp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mEditorTitle = new EditText(this);
        mEditorTitle.setBackground(null);
        mEditorTitle.setHint("Title");
        mEditorTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f);
        mEditorTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mEditorTitle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        mEditorTitle.setPadding(0, dp(2f), 0, dp(10f));
        mEditorTitle.addTextChangedListener(new SimpleWatcher(this::onEditorContentMutated));
        mEditorPaperCard.addView(mEditorTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mChecklistContainer = new LinearLayout(this);
        mChecklistContainer.setOrientation(LinearLayout.VERTICAL);
        mEditorPaperCard.addView(mChecklistContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mAddChecklistInlineBtn = new TextView(this);
        mAddChecklistInlineBtn.setText("+  Add checklist item");
        mAddChecklistInlineBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        mAddChecklistInlineBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mAddChecklistInlineBtn.setPadding(dp(4f), dp(6f), dp(8f), dp(12f));
        mAddChecklistInlineBtn.setVisibility(View.GONE);
        mAddChecklistInlineBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            appendNewChecklistItem(null, true);
        });
        mEditorPaperCard.addView(mAddChecklistInlineBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mEditorBody = new EditText(this);
        mEditorBody.setBackground(null);
        mEditorBody.setHint("Start writing, add bullets, or tap \u2611 below for checklists\u2026");
        mEditorBody.setGravity(Gravity.TOP | Gravity.START);
        mEditorBody.setMinHeight(dp(220f));
        mEditorBody.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
        mEditorBody.setLineSpacing(dp(4f), 1.0f);
        mEditorBody.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        mEditorBody.setPadding(0, dp(6f), 0, dp(12f));
        mEditorBody.addTextChangedListener(new SimpleWatcher(this::onEditorContentMutated));
        mEditorPaperCard.addView(mEditorBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mEditorScroll.addView(mEditorPaperCard);
        editorRootCol.addView(mEditorScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mEditorScreen.addView(editorRootCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Floating Bottom Liquid Glass Formatting Bar
        mFormattingBar = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(26f);
        mFormattingBar.setOrientation(LinearLayout.HORIZONTAL);
        mFormattingBar.setGravity(Gravity.CENTER_VERTICAL);
        mFormattingBar.setPadding(dp(10f), dp(8f), dp(10f), dp(8f));
        mPersistentGlassCards.add(mFormattingBar);

        mFmtPinBtn = makeFormatAction("\u2606 Pin", this::toggleActiveNotePin);
        mFmtCheckBtn = makeFormatAction("\u2611 Check", () -> appendNewChecklistItem(null, true));
        mFmtHeadingBtn = makeFormatAction("\u2022 Heading", this::insertHeadingOrBulletAtCursor);
        mFmtShareBtn = makeFormatAction("\u2197 Share", this::shareActiveNote);
        mFmtDeleteBtn = makeFormatAction("\u2715 Delete", this::confirmDeleteActiveNote);

        mFormattingBar.addView(mFmtPinBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mFormattingBar.addView(mFmtCheckBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f));
        mFormattingBar.addView(mFmtHeadingBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        mFormattingBar.addView(mFmtShareBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mFormattingBar.addView(mFmtDeleteBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f));

        FrameLayout.LayoutParams fmtLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fmtLp.setMargins(dp(14f), 0, dp(14f), dp(20f));
        mEditorScreen.addView(mFormattingBar, fmtLp);
    }

    private TextView makeFormatAction(String label, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setPadding(dp(4f), dp(8f), dp(4f), dp(8f));
        tv.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            action.run();
        });
        return tv;
    }

    private void rebuildFolderPills() {
        mFolderPillRow.removeAllViews();
        List<String> pills = new ArrayList<>();
        pills.add(NotesDatabase.FILTER_ALL);
        pills.add(NotesDatabase.FILTER_PINNED);
        pills.addAll(mDb.getCustomFolders());

        boolean dark = mPrefs.darkTheme;
        for (String folder : pills) {
            boolean active = folder.equals(mSelectedFolder);
            SamuraiGlassCard pill = new SamuraiGlassCard(this, mPrefs)
                    .setCornerRadiusDp(18f)
                    .setInteractivePressScale(true);
            if (active) {
                pill.setTint(0xFFFFB800, dark ? 0.62f : 0.72f);
            }
            pill.setPadding(dp(14f), dp(7f), dp(14f), dp(7f));
            TextView tv = new TextView(this);
            String label = NotesDatabase.FILTER_PINNED.equals(folder) ? ("\u2605 " + folder) : folder;
            tv.setText(label);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            tv.setTypeface(Typeface.create("sans-serif-medium", active ? Typeface.BOLD : Typeface.NORMAL));
            tv.setTextColor(active ? 0xFF1F1600 : (dark ? 0xFFF2F4F8 : 0xFF1A1C24));
            pill.addView(tv);
            pill.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticTick(v);
                mSelectedFolder = folder;
                rebuildFolderPills();
                refreshNotesList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8f);
            mFolderPillRow.addView(pill, lp);
        }

        // "+ Folder" pill
        SamuraiGlassCard addFolderPill = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(18f)
                .setInteractivePressScale(true);
        addFolderPill.setPadding(dp(13f), dp(7f), dp(13f), dp(7f));
        TextView addTv = new TextView(this);
        addTv.setText("+ Folder");
        addTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        addTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        addTv.setTextColor(dark ? GOLD_DARK : GOLD_LIGHT);
        addFolderPill.addView(addTv);
        addFolderPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            promptNewFolderDialog();
        });
        mFolderPillRow.addView(addFolderPill);
    }

    private void promptNewFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g. Travel, Recipes, Projects");
        input.setSingleLine(true);
        int pad = dp(18f);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, dp(8f), pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("New Folder")
                .setView(wrap)
                .setPositiveButton("Create", (d, which) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (mDb.createFolder(name)) {
                        mSelectedFolder = name;
                        rebuildFolderPills();
                        refreshNotesList();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshNotesList() {
        mNotesSectionsContainer.removeAllViews();
        mDynamicListCards.clear();

        List<NotesDatabase.NoteRecord> all = mDb.queryNotes(mSelectedFolder, mSearchQuery);
        mHeaderCount.setText(all.size() == 1 ? "1 Note" : (all.size() + " Notes"));

        List<NotesDatabase.NoteRecord> pinned = new ArrayList<>();
        List<NotesDatabase.NoteRecord> recent = new ArrayList<>();
        for (NotesDatabase.NoteRecord r : all) {
            if (r.pinned) pinned.add(r);
            else recent.add(r);
        }

        boolean dark = mPrefs.darkTheme;
        if (all.isEmpty()) {
            SamuraiGlassCard emptyCard = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(24f);
            emptyCard.setPadding(dp(20f), dp(36f), dp(20f), dp(36f));
            TextView tv = new TextView(this);
            tv.setText(mSearchQuery.isEmpty()
                    ? "No notes in \"" + mSelectedFolder + "\" yet.\nTap the warm gold \u270E orb below to compose one."
                    : "No matches for \"" + mSearchQuery + "\".");
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            tv.setTextColor(dark ? 0x99EBEBF5 : 0x8A3C3C43);
            emptyCard.addView(tv);
            mDynamicListCards.add(emptyCard);
            mNotesSectionsContainer.addView(emptyCard);
            return;
        }

        if (!pinned.isEmpty()) {
            addSectionHeader("\u2605  PINNED");
            addNoteGroupPlatter(pinned);
        }
        if (!recent.isEmpty()) {
            addSectionHeader("RECENT");
            addNoteGroupPlatter(recent);
        }
    }

    private void addSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.06f);
        tv.setTextColor(mPrefs.darkTheme ? 0xA6EBEBF5 : 0x993C3C43);
        tv.setPadding(dp(6f), dp(10f), dp(6f), dp(6f));
        mNotesSectionsContainer.addView(tv);
    }

    private void addNoteGroupPlatter(List<NotesDatabase.NoteRecord> notes) {
        boolean dark = mPrefs.darkTheme;
        int primary = dark ? 0xFFF5F7FC : 0xFF141720;
        int secondary = dark ? 0x9EEBEBF5 : 0x8F3C3C43;
        int gold = dark ? GOLD_DARK : GOLD_LIGHT;

        SamuraiGlassCard platter = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(24f);
        platter.setPadding(dp(16f), dp(6f), dp(16f), dp(6f));
        mDynamicListCards.add(platter);

        for (int i = 0; i < notes.size(); i++) {
            final NotesDatabase.NoteRecord note = notes.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(2f), dp(12f), dp(2f), dp(12f));

            // Title row + Pin star
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleTv = new TextView(this);
            titleTv.setText(note.title);
            titleTv.setSingleLine(true);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16.5f);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titleTv.setTextColor(primary);
            titleRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (note.pinned) {
                TextView star = new TextView(this);
                star.setText("\u2605");
                star.setTextColor(gold);
                star.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
                star.setPadding(dp(8f), 0, 0, 0);
                titleRow.addView(star);
            }
            row.addView(titleRow);

            // Timestamp + preview snippet
            TextView snippetTv = new TextView(this);
            snippetTv.setText(formatRelativeTime(note.updatedAt) + "   " + note.buildPreviewSnippet());
            snippetTv.setSingleLine(true);
            snippetTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            snippetTv.setTextColor(secondary);
            snippetTv.setPadding(0, dp(3f), 0, dp(6f));
            row.addView(snippetTv);

            // Folder badge capsule
            TextView folderBadge = new TextView(this);
            folderBadge.setText("\uD83D\uDCC1 " + note.folder);
            folderBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            folderBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            folderBadge.setTextColor(gold);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(10f));
            badgeBg.setColor(dark ? 0x24FFD60A : 0x22FFB800);
            folderBadge.setBackground(badgeBg);
            folderBadge.setPadding(dp(8f), dp(2.5f), dp(8f), dp(2.5f));
            row.addView(folderBadge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(this, v);
                NotesDatabase.NoteRecord fresh = mDb.getNoteById(note.id);
                openEditor(fresh != null ? fresh : note);
            });
            platter.addView(row);

            if (i < notes.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(dark ? 0x22FFFFFF : 0x1A000000);
                platter.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10f);
        mNotesSectionsContainer.addView(platter, cardLp);
    }

    private void createNewNote() {
        NotesDatabase.NoteRecord rec = new NotesDatabase.NoteRecord();
        if (!NotesDatabase.FILTER_ALL.equals(mSelectedFolder)
                && !NotesDatabase.FILTER_PINNED.equals(mSelectedFolder)) {
            rec.folder = mSelectedFolder;
        } else {
            rec.folder = "Personal";
        }
        rec.pinned = NotesDatabase.FILTER_PINNED.equals(mSelectedFolder);
        openEditor(rec);
        mEditorTitle.requestFocus();
    }

    private void openEditor(NotesDatabase.NoteRecord note) {
        mActiveNote = note;
        mInEditor = true;
        mSuppressAutoSave = true;

        mEditorTitle.setText(note.title);
        mEditorBody.setText(note.body);
        mFolderAssignText.setText("\uD83D\uDCC1 " + note.folder + " \u25BE");
        updateEditorTimestampAndPinUi();
        rebuildEditorChecklistViews();

        mSuppressAutoSave = false;
        hideKeyboard();
        mEditorScreen.setVisibility(View.VISIBLE);
        mScreenSpring.animateTo(1f);
    }

    private void closeEditor(boolean saveChanges) {
        if (!mInEditor) return;
        hideKeyboard();
        if (saveChanges && mActiveNote != null) {
            boolean hasAnyContent = !safeText(mEditorTitle).trim().isEmpty()
                    || !safeText(mEditorBody).trim().isEmpty()
                    || !mActiveNote.checklist.isEmpty();
            if (hasAnyContent || mActiveNote.id > 0L) {
                persistActiveEditorNote();
            }
        }
        mInEditor = false;
        mActiveNote = null;
        refreshNotesList();
        mScreenSpring.animateTo(0f);
    }

    private void onEditorContentMutated() {
        if (mSuppressAutoSave || mActiveNote == null) return;
        mActiveNote.title = safeText(mEditorTitle);
        mActiveNote.body = safeText(mEditorBody);
    }

    private void persistActiveEditorNote() {
        if (mActiveNote == null) return;
        mActiveNote.title = safeText(mEditorTitle);
        mActiveNote.body = safeText(mEditorBody);
        mDb.saveNote(mActiveNote);
        updateEditorTimestampAndPinUi();
    }

    private void rebuildEditorChecklistViews() {
        mChecklistContainer.removeAllViews();
        mActiveChecklistViews.clear();
        if (mActiveNote == null) return;

        for (NotesDatabase.ChecklistItem item : mActiveNote.checklist) {
            ChecklistRowView rowView = createChecklistRowView(item);
            mActiveChecklistViews.add(rowView);
            mChecklistContainer.addView(rowView);
        }
        mAddChecklistInlineBtn.setVisibility(mActiveNote.checklist.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private ChecklistRowView createChecklistRowView(NotesDatabase.ChecklistItem item) {
        return new ChecklistRowView(this, item, mPrefs.darkTheme, new ChecklistRowView.RowCallbacks() {
            @Override
            public void onChanged() {
                if (mActiveNote != null && !mSuppressAutoSave) {
                    mActiveNote.updatedAt = System.currentTimeMillis();
                }
            }

            @Override
            public void onEnterInsertAfter(ChecklistRowView currentRow) {
                appendNewChecklistItem(currentRow, true);
            }

            @Override
            public void onDeleteRow(ChecklistRowView currentRow) {
                if (mActiveNote == null) return;
                mActiveNote.checklist.remove(currentRow.getItem());
                mActiveChecklistViews.remove(currentRow);
                mChecklistContainer.removeView(currentRow);
                mAddChecklistInlineBtn.setVisibility(
                        mActiveNote.checklist.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void appendNewChecklistItem(ChecklistRowView afterRow, boolean requestFocus) {
        if (mActiveNote == null) return;
        NotesDatabase.ChecklistItem newItem = new NotesDatabase.ChecklistItem("", false);
        int insertIdx = mActiveNote.checklist.size();
        if (afterRow != null) {
            int found = mActiveChecklistViews.indexOf(afterRow);
            if (found >= 0) insertIdx = found + 1;
        }
        mActiveNote.checklist.add(insertIdx, newItem);
        ChecklistRowView view = createChecklistRowView(newItem);
        mActiveChecklistViews.add(insertIdx, view);
        mChecklistContainer.addView(view, insertIdx);
        mAddChecklistInlineBtn.setVisibility(View.VISIBLE);
        if (requestFocus) {
            view.getEditText().requestFocus();
        }
    }

    private void toggleActiveNotePin() {
        if (mActiveNote == null) return;
        mActiveNote.pinned = !mActiveNote.pinned;
        persistActiveEditorNote();
        Toast.makeText(this, mActiveNote.pinned ? "Pinned to top" : "Unpinned", Toast.LENGTH_SHORT).show();
    }

    private void insertHeadingOrBulletAtCursor() {
        if (mActiveNote == null) return;
        mEditorBody.requestFocus();
        Editable ed = mEditorBody.getText();
        if (ed == null) return;
        int pos = Math.max(0, mEditorBody.getSelectionStart());
        boolean atLineStart = (pos == 0) || (ed.charAt(pos - 1) == '\n');
        // Cycle cleanly: if line starts with "• ", convert to "▸ " heading; else insert "• "
        int lineStart = pos;
        while (lineStart > 0 && ed.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String currentLine = ed.subSequence(lineStart, pos).toString();
        if (currentLine.startsWith("\u2022 ")) {
            ed.replace(lineStart, lineStart + 2, "\u25B8 ");
        } else if (atLineStart) {
            ed.insert(pos, "\u2022 ");
        } else {
            ed.insert(pos, "\n\u2022 ");
        }
    }

    private void shareActiveNote() {
        if (mActiveNote == null) return;
        persistActiveEditorNote();
        String payload = mActiveNote.toShareablePlainText();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, mActiveNote.title);
        send.putExtra(Intent.EXTRA_TEXT, payload);
        startActivity(Intent.createChooser(send, "Share Note"));
    }

    private void confirmDeleteActiveNote() {
        if (mActiveNote == null) return;
        if (mActiveNote.id <= 0L) {
            closeEditor(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete Note?")
                .setMessage("This note will be permanently removed.")
                .setPositiveButton("Delete", (d, w) -> {
                    SamuraiGlassPrefs.hapticHeavySpring(this, mFormattingBar);
                    mDb.deleteNote(mActiveNote.id);
                    closeEditor(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFolderAssignmentPicker() {
        if (mActiveNote == null) return;
        List<String> folders = mDb.getCustomFolders();
        String[] arr = folders.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Move to Folder")
                .setItems(arr, (d, idx) -> {
                    mActiveNote.folder = arr[idx];
                    mFolderAssignText.setText("\uD83D\uDCC1 " + mActiveNote.folder + " \u25BE");
                    persistActiveEditorNote();
                })
                .show();
    }

    private void updateEditorTimestampAndPinUi() {
        if (mActiveNote == null) return;
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault());
        mEditorTimestamp.setText("Edited " + fmt.format(new Date(mActiveNote.updatedAt))
                + "  \u00B7  " + mActiveNote.folder);
        int gold = mPrefs.darkTheme ? GOLD_DARK : GOLD_LIGHT;
        int primary = mPrefs.darkTheme ? 0xFFF2F4F8 : 0xFF1A1C24;
        mFmtPinBtn.setText(mActiveNote.pinned ? "\u2605 Pinned" : "\u2606 Pin");
        mFmtPinBtn.setTextColor(mActiveNote.pinned ? gold : primary);
    }

    private void applyThemeColors() {
        boolean dark = mPrefs.darkTheme;
        int primary = dark ? 0xFFF5F7FC : 0xFF141720;
        int secondary = dark ? 0x99EBEBF5 : 0x8A3C3C43;
        int gold = dark ? GOLD_DARK : GOLD_LIGHT;

        mHeaderTitle.setTextColor(primary);
        mHeaderCount.setTextColor(secondary);
        mBackPillText.setTextColor(gold);
        mFolderAssignText.setTextColor(primary);
        mDonePillText.setTextColor(0xFF1F1600);

        mEditorTimestamp.setTextColor(secondary);
        mEditorTitle.setTextColor(primary);
        mEditorTitle.setHintTextColor(secondary);
        mEditorBody.setTextColor(primary);
        mEditorBody.setHintTextColor(secondary);
        mAddChecklistInlineBtn.setTextColor(gold);

        mFmtCheckBtn.setTextColor(primary);
        mFmtHeadingBtn.setTextColor(primary);
        mFmtShareBtn.setTextColor(primary);
        mFmtDeleteBtn.setTextColor(0xFFFF453A);
        if (mActiveNote != null) {
            updateEditorTimestampAndPinUi();
        }

        for (SamuraiGlassCard c : mPersistentGlassCards) {
            c.updatePrefs(mPrefs);
        }
        for (SamuraiGlassCard c : mDynamicListCards) {
            c.updatePrefs(mPrefs);
        }
        for (ChecklistRowView row : mActiveChecklistViews) {
            row.updateTheme(dark);
        }
        if (mComposeOrb != null) {
            mComposeOrb.updatePrefs(mPrefs);
        }
        if (mRoot != null) {
            mRoot.invalidate();
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                focus.clearFocus();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safeText(EditText et) {
        return (et != null && et.getText() != null) ? et.getText().toString() : "";
    }

    private static String formatRelativeTime(long epochMs) {
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60_000L) return "Just now";
        if (diff < 86_400_000L) {
            return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(epochMs));
        }
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(epochMs));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable mCallback;

        SimpleWatcher(Runnable cb) {
            mCallback = cb;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (mCallback != null) mCallback.run();
        }

        @Override
        public void afterTextChanged(Editable s) {}
    }

    private final class NotesBackdropFrame extends FrameLayout {
        private final Paint mAmbientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        NotesBackdropFrame(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w > 2 && h > 2) {
                SamuraiAgslEngine.drawProceduralAppBackground(canvas, w, h, mPrefs.darkTheme);
                // Subtle warm iOS Notes gold ambient glow in top-right & bottom-right
                mAmbientPaint.setShader(new RadialGradient(
                        w * 0.85f, h * 0.16f, w * 0.65f,
                        new int[] { mPrefs.darkTheme ? 0x26FFD60A : 0x28FFB800, 0x00FFD60A },
                        null, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, mAmbientPaint);
            }
            super.onDraw(canvas);
        }
    }
}
