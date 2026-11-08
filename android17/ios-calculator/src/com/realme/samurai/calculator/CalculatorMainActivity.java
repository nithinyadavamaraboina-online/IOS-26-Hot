package com.realme.samurai.calculator;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import com.realme.samurai.glass.SamuraiAgslEngine;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiSpring;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Clean-Room 90 Hz iOS 26 Liquid Glass Calculator (`CalculatorMainActivity`).
 *
 * Features:
 * - Top-left History Tape glass pill (sliding frosted sheet, tap to recall, Clear History)
 * - Top-right Basic / Scientific mode switch pill with Deg/Rad toggle
 * - Multi-line expression preview + auto-sizing tabular numerals + swipe-to-backspace gesture
 * - Refractive AGSL keypad (`CalcGlassButton`) with Gaussian touch-bulge & `#FF9F0A` operator glow
 */
public final class CalculatorMainActivity extends Activity {

    private final SamuraiGlassPrefs.Snapshot mPrefs = new SamuraiGlassPrefs.Snapshot();
    private ContentObserver mPrefsObserver;

    private final List<CalcGlassButton> mAllButtons = new ArrayList<>();
    private final List<SamuraiGlassCard> mGlassCards = new ArrayList<>();

    private BackdropFrameLayout mRoot;
    private LinearLayout mMainColumn;
    private SamuraiGlassCard mHistoryPill;
    private TextView mHistoryPillText;
    private SamuraiGlassCard mModePill;
    private TextView mModePillText;
    private TextView mAngleBadge;

    private TextView mExpressionView;
    private TextView mResultView;
    private LinearLayout mScientificDrawer;

    private CalcGlassButton mClearBtn;
    private CalcGlassButton mDegRadBtn;
    private CalcGlassButton mOpDiv;
    private CalcGlassButton mOpMul;
    private CalcGlassButton mOpSub;
    private CalcGlassButton mOpAdd;

    // Sliding Frosted History Tape Sheet
    private FrameLayout mHistoryOverlay;
    private SamuraiGlassCard mHistorySheet;
    private LinearLayout mHistoryListContainer;
    private TextView mHistoryTitleText;
    private TextView mClearHistoryBtn;
    private boolean mHistoryOpen = false;
    private SamuraiSpring mSheetSpring;
    private SamuraiSpring mDisplaySwipeSpring;

    // Calculator State
    private String mRawExpression = "";
    private String mLastEvaluatedExpr = "";
    private String mCurrentResult = "0";
    private boolean mJustEvaluated = false;
    private boolean mScientificMode = false;
    private boolean mDegreesMode = true;

    private final OnBackInvokedCallback mBackCallback = () -> {
        if (mHistoryOpen) {
            toggleHistorySheet(false);
        } else {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SamuraiGlassPrefs.configure90HzEdgeToEdge(this);
        SamuraiGlassPrefs.read(this, mPrefs);

        buildUi();
        applyThemeColors();
        syncDisplay(false);

        mPrefsObserver = SamuraiGlassPrefs.registerObserver(this, () -> {
            SamuraiGlassPrefs.read(this, mPrefs);
            SamuraiAgslEngine.invalidateWallpaperCache();
            applyThemeColors();
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        SamuraiGlassPrefs.read(this, mPrefs);
        applyThemeColors();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        SamuraiGlassPrefs.unregisterObserver(this, mPrefsObserver);
        if (mSheetSpring != null) mSheetSpring.cancel();
        if (mDisplaySwipeSpring != null) mDisplaySwipeSpring.cancel();
        super.onDestroy();
    }

    private void buildUi() {
        final float d = getResources().getDisplayMetrics().density;

        mRoot = new BackdropFrameLayout(this);
        mMainColumn = new LinearLayout(this);
        mMainColumn.setOrientation(LinearLayout.VERTICAL);
        mMainColumn.setClipChildren(false);
        mMainColumn.setClipToPadding(false);

        int sidePad = dp(16f);
        mMainColumn.setPadding(sidePad, dp(48f), sidePad, dp(22f));
        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            mMainColumn.setPadding(
                    sidePad,
                    Math.max(dp(40f), sys.top + dp(10f)),
                    sidePad,
                    Math.max(dp(16f), sys.bottom + dp(10f)));
            return insets;
        });

        // 1. Top Control Strip: History Tape Pill (left) + Deg/Rad badge + Scientific/Basic Pill (right)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);

        mHistoryPill = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(20f)
                .setInteractivePressScale(true);
        mHistoryPill.setOrientation(LinearLayout.HORIZONTAL);
        mHistoryPill.setGravity(Gravity.CENTER_VERTICAL);
        mHistoryPill.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        mHistoryPillText = new TextView(this);
        mHistoryPillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mHistoryPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mHistoryPill.addView(mHistoryPillText);
        mHistoryPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            toggleHistorySheet(true);
        });
        mGlassCards.add(mHistoryPill);
        topBar.addView(mHistoryPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        mAngleBadge = new TextView(this);
        mAngleBadge.setText("DEG");
        mAngleBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        mAngleBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mAngleBadge.setPadding(dp(10f), dp(5f), dp(10f), dp(5f));
        mAngleBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = dp(8f);
        topBar.addView(mAngleBadge, badgeLp);

        mModePill = new SamuraiGlassCard(this, mPrefs)
                .setCornerRadiusDp(20f)
                .setInteractivePressScale(true);
        mModePill.setOrientation(LinearLayout.HORIZONTAL);
        mModePill.setGravity(Gravity.CENTER_VERTICAL);
        mModePill.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        mModePillText = new TextView(this);
        mModePillText.setText("\u0192(x)  Scientific");
        mModePillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        mModePillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mModePill.addView(mModePillText);
        mModePill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            toggleScientificMode();
        });
        mGlassCards.add(mModePill);
        topBar.addView(mModePill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mMainColumn.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Multi-Line Display Area (expands cleanly above keypad + swipe-to-backspace)
        LinearLayout displayBox = new LinearLayout(this);
        displayBox.setOrientation(LinearLayout.VERTICAL);
        displayBox.setGravity(Gravity.BOTTOM | Gravity.END);
        displayBox.setPadding(dp(8f), dp(12f), dp(8f), dp(14f));

        mExpressionView = new TextView(this);
        mExpressionView.setGravity(Gravity.END);
        mExpressionView.setSingleLine(false);
        mExpressionView.setMaxLines(2);
        mExpressionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        mExpressionView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mExpressionView.setFontFeatureSettings("tnum");
        displayBox.addView(mExpressionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mResultView = new TextView(this);
        mResultView.setGravity(Gravity.END);
        mResultView.setSingleLine(true);
        mResultView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 68f);
        mResultView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        mResultView.setFontFeatureSettings("tnum");
        mResultView.setPadding(0, dp(2f), 0, 0);
        displayBox.addView(mResultView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView swipeHint = new TextView(this);
        swipeHint.setText("Swipe display to backspace");
        swipeHint.setGravity(Gravity.END);
        swipeHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        swipeHint.setAlpha(0.45f);
        displayBox.addView(swipeHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDisplaySwipeSpring = new SamuraiSpring(0f, (val, vel) -> mResultView.setTranslationX(val))
                .setTuning(32f, 0.75f);

        attachSwipeBackspaceGesture(displayBox, d);

        LinearLayout.LayoutParams dispLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mMainColumn.addView(displayBox, dispLp);

        // 3. Scientific Drawer Rows (3 rows x 4/5 compact capsule keys)
        mScientificDrawer = new LinearLayout(this);
        mScientificDrawer.setOrientation(LinearLayout.VERTICAL);
        mScientificDrawer.setClipChildren(false);
        mScientificDrawer.setClipToPadding(false);
        mScientificDrawer.setVisibility(View.GONE);
        buildScientificRows(mScientificDrawer);
        LinearLayout.LayoutParams sciLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sciLp.bottomMargin = dp(8f);
        mMainColumn.addView(mScientificDrawer, sciLp);

        // 4. Primary 5x4 Refractive Circular / Capsule Glass Keypad
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setClipChildren(false);
        keypad.setClipToPadding(false);
        buildPrimaryKeypad(keypad);
        mMainColumn.addView(keypad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mRoot.addView(mMainColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 5. Sliding Frosted History Tape Overlay Sheet
        buildHistoryOverlay();
        mRoot.addView(mHistoryOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(mRoot);
        refreshHistoryBadge();
    }

    private void attachSwipeBackspaceGesture(View target, float density) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean consumed;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = ev.getX();
                    downY = ev.getY();
                    consumed = false;
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE && !consumed) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > 28f * density && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        consumed = true;
                        SamuraiGlassPrefs.hapticTick(v);
                        mDisplaySwipeSpring.snapTo(dx < 0 ? -14f * density : 14f * density);
                        mDisplaySwipeSpring.animateTo(0f);
                        onBackspace();
                    }
                    return true;
                }
                return true;
            }
        });
    }

    private void buildScientificRows(LinearLayout container) {
        // 13 authentic scientific keys across 3 sleek capsule rows (5 + 4 + 4)
        LinearLayout r1 = createRow();
        mDegRadBtn = makeSciBtn("Rad", this::toggleDegreesRadians);
        addWeightedKey(r1, mDegRadBtn, 1f, dp(42f), dp(4f));
        addWeightedKey(r1, makeSciBtn("sin", () -> appendFunction("sin(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r1, makeSciBtn("cos", () -> appendFunction("cos(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r1, makeSciBtn("tan", () -> appendFunction("tan(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r1, makeSciBtn("\u03C0", () -> appendToken("\u03C0")), 1f, dp(42f), dp(4f));
        container.addView(r1);

        LinearLayout r2 = createRow();
        addWeightedKey(r2, makeSciBtn("ln", () -> appendFunction("ln(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r2, makeSciBtn("log\u2081\u2080", () -> appendFunction("log\u2081\u2080(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r2, makeSciBtn("x\u00B2", () -> appendPostfix("\u00B2")), 1f, dp(42f), dp(4f));
        addWeightedKey(r2, makeSciBtn("\u221Ax", () -> appendFunction("\u221A(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r2, makeSciBtn("e", () -> appendToken("e")), 1f, dp(42f), dp(4f));
        container.addView(r2);

        LinearLayout r3 = createRow();
        addWeightedKey(r3, makeSciBtn("(", () -> appendToken("(")), 1f, dp(42f), dp(4f));
        addWeightedKey(r3, makeSciBtn(")", () -> appendToken(")")), 1f, dp(42f), dp(4f));
        addWeightedKey(r3, makeSciBtn("x\u02B8", () -> appendOperator("^")), 1f, dp(42f), dp(4f));
        addWeightedKey(r3, makeSciBtn("1/x", this::applyReciprocal), 1f, dp(42f), dp(4f));
        container.addView(r3);
    }

    private void buildPrimaryKeypad(LinearLayout keypad) {
        int keyH = dp(72f);
        int gap = dp(6f);

        // Row 1: AC/⌫, +/-, %, ÷
        LinearLayout r1 = createRow();
        mClearBtn = new CalcGlassButton(this, mPrefs, "AC", CalcGlassButton.ROLE_UTILITY)
                .setOnTap(this::onClearOrBackspaceTap)
                .setOnLongTap(this::onAllClear);
        mAllButtons.add(mClearBtn);
        addWeightedKey(r1, mClearBtn, 1f, keyH, gap);

        CalcGlassButton signBtn = makeKey("+\u2044\u2212", CalcGlassButton.ROLE_UTILITY, this::onToggleSign);
        addWeightedKey(r1, signBtn, 1f, keyH, gap);

        CalcGlassButton pctBtn = makeKey("%", CalcGlassButton.ROLE_UTILITY, () -> appendPostfix("%"));
        addWeightedKey(r1, pctBtn, 1f, keyH, gap);

        mOpDiv = makeKey("\u00F7", CalcGlassButton.ROLE_OPERATOR, () -> appendOperator("\u00F7"));
        addWeightedKey(r1, mOpDiv, 1f, keyH, gap);
        keypad.addView(r1);

        // Row 2: 7, 8, 9, ×
        LinearLayout r2 = createRow();
        addWeightedKey(r2, makeKey("7", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("7")), 1f, keyH, gap);
        addWeightedKey(r2, makeKey("8", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("8")), 1f, keyH, gap);
        addWeightedKey(r2, makeKey("9", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("9")), 1f, keyH, gap);
        mOpMul = makeKey("\u00D7", CalcGlassButton.ROLE_OPERATOR, () -> appendOperator("\u00D7"));
        addWeightedKey(r2, mOpMul, 1f, keyH, gap);
        keypad.addView(r2);

        // Row 3: 4, 5, 6, −
        LinearLayout r3 = createRow();
        addWeightedKey(r3, makeKey("4", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("4")), 1f, keyH, gap);
        addWeightedKey(r3, makeKey("5", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("5")), 1f, keyH, gap);
        addWeightedKey(r3, makeKey("6", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("6")), 1f, keyH, gap);
        mOpSub = makeKey("\u2212", CalcGlassButton.ROLE_OPERATOR, () -> appendOperator("\u2212"));
        addWeightedKey(r3, mOpSub, 1f, keyH, gap);
        keypad.addView(r3);

        // Row 4: 1, 2, 3, +
        LinearLayout r4 = createRow();
        addWeightedKey(r4, makeKey("1", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("1")), 1f, keyH, gap);
        addWeightedKey(r4, makeKey("2", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("2")), 1f, keyH, gap);
        addWeightedKey(r4, makeKey("3", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("3")), 1f, keyH, gap);
        mOpAdd = makeKey("+", CalcGlassButton.ROLE_OPERATOR, () -> appendOperator("+"));
        addWeightedKey(r4, mOpAdd, 1f, keyH, gap);
        keypad.addView(r4);

        // Row 5: Wide 0 capsule (2x + margin compensation), ., =
        LinearLayout r5 = createRow();
        CalcGlassButton zeroBtn = makeKey("0", CalcGlassButton.ROLE_DIGIT, () -> appendDigit("0"))
                .setLeftAlignedCapsule(true);
        LinearLayout.LayoutParams zeroLp = new LinearLayout.LayoutParams(0, keyH, 2f);
        zeroLp.setMargins(gap, gap, gap, gap);
        r5.addView(zeroBtn, zeroLp);

        LinearLayout rightPair = new LinearLayout(this);
        rightPair.setOrientation(LinearLayout.HORIZONTAL);
        rightPair.setClipChildren(false);
        rightPair.setClipToPadding(false);
        addWeightedKey(rightPair, makeKey(".", CalcGlassButton.ROLE_DIGIT, this::appendDecimal), 1f, keyH, gap);
        addWeightedKey(rightPair, makeKey("=", CalcGlassButton.ROLE_OPERATOR, this::onEquals), 1f, keyH, gap);

        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        r5.addView(rightPair, pairLp);
        keypad.addView(r5);
    }

    private LinearLayout createRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private CalcGlassButton makeKey(String label, int role, Runnable action) {
        CalcGlassButton btn = new CalcGlassButton(this, mPrefs, label, role).setOnTap(action);
        mAllButtons.add(btn);
        return btn;
    }

    private CalcGlassButton makeSciBtn(String label, Runnable action) {
        return makeKey(label, CalcGlassButton.ROLE_SCIENTIFIC, action);
    }

    private void addWeightedKey(LinearLayout row, View child, float weight, int heightPx, int marginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, heightPx, weight);
        lp.setMargins(marginPx, marginPx, marginPx, marginPx);
        row.addView(child, lp);
    }

    private void buildHistoryOverlay() {
        mHistoryOverlay = new FrameLayout(this);
        mHistoryOverlay.setVisibility(View.GONE);
        mHistoryOverlay.setBackgroundColor(0x88000000);
        mHistoryOverlay.setOnClickListener(v -> toggleHistorySheet(false));

        mHistorySheet = new SamuraiGlassCard(this, mPrefs).setCornerRadiusDp(30f);
        mHistorySheet.setClickable(true);
        mHistorySheet.setPadding(dp(20f), dp(18f), dp(20f), dp(28f));
        mGlassCards.add(mHistorySheet);

        // Grab handle
        View grabber = new View(this);
        grabber.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(dp(40f), dp(4.5f));
        grabLp.gravity = Gravity.CENTER_HORIZONTAL;
        grabLp.bottomMargin = dp(14f);
        mHistorySheet.addView(grabber, grabLp);

        // Sheet header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        mHistoryTitleText = new TextView(this);
        mHistoryTitleText.setText("History Tape");
        mHistoryTitleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f);
        mHistoryTitleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(mHistoryTitleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mClearHistoryBtn = new TextView(this);
        mClearHistoryBtn.setText("Clear");
        mClearHistoryBtn.setTextColor(0xFFFF453A);
        mClearHistoryBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        mClearHistoryBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mClearHistoryBtn.setPadding(dp(12f), dp(6f), dp(12f), dp(6f));
        mClearHistoryBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticHeavySpring(this, v);
            HistoryStore.clear(this);
            populateHistoryEntries();
            refreshHistoryBadge();
        });
        header.addView(mClearHistoryBtn);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("Done");
        closeBtn.setTextColor(0xFFFF9F0A);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        closeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        closeBtn.setPadding(dp(12f), dp(6f), dp(4f), dp(6f));
        closeBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            toggleHistorySheet(false);
        });
        header.addView(closeBtn);
        mHistorySheet.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        mHistoryListContainer = new LinearLayout(this);
        mHistoryListContainer.setOrientation(LinearLayout.VERTICAL);
        mHistoryListContainer.setPadding(0, dp(12f), 0, dp(8f));
        scroll.addView(mHistoryListContainer);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360f));
        scrollLp.topMargin = dp(8f);
        mHistorySheet.addView(scroll, scrollLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sheetLp.setMargins(dp(10f), dp(60f), dp(10f), dp(14f));
        mHistoryOverlay.addView(mHistorySheet, sheetLp);

        mSheetSpring = new SamuraiSpring(1f, (val, vel) -> {
            float h = Math.max(dp(440f), mHistorySheet.getHeight());
            mHistorySheet.setTranslationY(val * h);
            mHistoryOverlay.getBackground().setAlpha(Math.round(145f * (1f - Math.min(1f, Math.max(0f, val)))));
            if (!mHistoryOpen && val >= 0.98f) {
                mHistoryOverlay.setVisibility(View.GONE);
            }
        }).setTuning(28f, 0.82f);
    }

    private void toggleHistorySheet(boolean open) {
        mHistoryOpen = open;
        if (open) {
            populateHistoryEntries();
            mHistoryOverlay.setVisibility(View.VISIBLE);
            mSheetSpring.snapTo(1f);
            mSheetSpring.animateTo(0f);
        } else {
            mSheetSpring.animateTo(1f);
        }
    }

    private void populateHistoryEntries() {
        mHistoryListContainer.removeAllViews();
        List<HistoryStore.Entry> list = HistoryStore.load(this);
        boolean dark = mPrefs.darkTheme;
        int primary = dark ? 0xFFF5F7FC : 0xFF141821;
        int muted = dark ? 0x99EBEBF5 : 0x8A3C3C43;

        mClearHistoryBtn.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No Calculations Yet\nTap = to record calculations on the tape.");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(muted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
            empty.setPadding(dp(16f), dp(54f), dp(16f), dp(54f));
            mHistoryListContainer.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int i = 0; i < list.size(); i++) {
            final HistoryStore.Entry entry = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.END);
            row.setPadding(dp(12f), dp(12f), dp(12f), dp(12f));

            TextView ts = new TextView(this);
            ts.setText(entry.epochMs > 0 ? fmt.format(new Date(entry.epochMs)) : "");
            ts.setTextColor(0xFFFF9F0A);
            ts.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            ts.setGravity(Gravity.START);
            row.addView(ts, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView exprTv = new TextView(this);
            exprTv.setText(entry.expression);
            exprTv.setTextColor(muted);
            exprTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            exprTv.setGravity(Gravity.END);
            exprTv.setFontFeatureSettings("tnum");
            row.addView(exprTv);

            TextView resTv = new TextView(this);
            resTv.setText(entry.result);
            resTv.setTextColor(primary);
            resTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f);
            resTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            resTv.setGravity(Gravity.END);
            resTv.setFontFeatureSettings("tnum");
            row.addView(resTv);

            row.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(this, v);
                String plainRes = entry.result.replace(",", "").replace("\u2212", "-");
                mRawExpression = plainRes;
                mLastEvaluatedExpr = entry.expression;
                mCurrentResult = entry.result;
                mJustEvaluated = true;
                syncDisplay(false);
                toggleHistorySheet(false);
            });
            mHistoryListContainer.addView(row);

            if (i < list.size() - 1) {
                View div = new View(this);
                div.setBackgroundColor(dark ? 0x22FFFFFF : 0x1E000000);
                mHistoryListContainer.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
    }

    private void refreshHistoryBadge() {
        int count = HistoryStore.load(this).size();
        mHistoryPillText.setText(count > 0 ? ("\u23F2  History \u00B7 " + count) : "\u23F2  History");
    }

    private void toggleScientificMode() {
        mScientificMode = !mScientificMode;
        mScientificDrawer.setVisibility(mScientificMode ? View.VISIBLE : View.GONE);
        mAngleBadge.setVisibility(mScientificMode ? View.VISIBLE : View.GONE);
        mModePillText.setText(mScientificMode ? "\u2328  Basic" : "\u0192(x)  Scientific");
        mModePill.setTint(mScientificMode ? 0xFFFF9F0A : 0x00000000, mScientificMode ? 0.35f : 0f);
        syncDisplay(true);
    }

    private void toggleDegreesRadians() {
        mDegreesMode = !mDegreesMode;
        mAngleBadge.setText(mDegreesMode ? "DEG" : "RAD");
        mDegRadBtn.setLabel(mDegreesMode ? "Rad" : "Deg");
        mDegRadBtn.setSelectedOperator(!mDegreesMode);
        syncDisplay(true);
    }

    private void appendDigit(String digit) {
        if (mJustEvaluated) {
            mRawExpression = "";
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
        }
        if ("0".equals(mRawExpression) && "0".equals(digit)) {
            syncDisplay(true);
            return;
        }
        if ("0".equals(mRawExpression) && !".".equals(digit)) {
            mRawExpression = digit;
        } else {
            mRawExpression += digit;
        }
        syncDisplay(true);
    }

    private void appendDecimal() {
        if (mJustEvaluated) {
            mRawExpression = "0.";
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
            syncDisplay(true);
            return;
        }
        int i = mRawExpression.length() - 1;
        while (i >= 0 && (Character.isDigit(mRawExpression.charAt(i)) || mRawExpression.charAt(i) == '.')) {
            if (mRawExpression.charAt(i) == '.') return; // Already has decimal in current number
            i--;
        }
        if (mRawExpression.isEmpty() || !Character.isDigit(mRawExpression.charAt(mRawExpression.length() - 1))) {
            mRawExpression += "0.";
        } else {
            mRawExpression += ".";
        }
        syncDisplay(true);
    }

    private void appendOperator(String op) {
        if (mJustEvaluated) {
            if ("Error".equals(mCurrentResult)) {
                mRawExpression = "0";
            } else {
                mRawExpression = mCurrentResult.replace(",", "").replace("\u2212", "-");
            }
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
        }
        if (mRawExpression.isEmpty()) {
            mRawExpression = "0" + op;
        } else {
            char last = mRawExpression.charAt(mRawExpression.length() - 1);
            if (isBinaryOperator(last)) {
                mRawExpression = mRawExpression.substring(0, mRawExpression.length() - 1) + op;
            } else {
                mRawExpression += op;
            }
        }
        syncDisplay(true);
    }

    private void appendPostfix(String sym) {
        if (mJustEvaluated) {
            if (!"Error".equals(mCurrentResult)) {
                mRawExpression = mCurrentResult.replace(",", "").replace("\u2212", "-");
            }
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
        }
        if (mRawExpression.isEmpty()) {
            mRawExpression = "0" + sym;
        } else {
            char last = mRawExpression.charAt(mRawExpression.length() - 1);
            if (!isBinaryOperator(last) && last != '(') {
                mRawExpression += sym;
            }
        }
        syncDisplay(true);
    }

    private void appendFunction(String fnToken) {
        if (mJustEvaluated) {
            mRawExpression = "";
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
        }
        mRawExpression += fnToken;
        syncDisplay(true);
    }

    private void appendToken(String tok) {
        if (mJustEvaluated && ("(".equals(tok) || "\u03C0".equals(tok) || "e".equals(tok))) {
            mRawExpression = "";
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
        }
        mRawExpression += tok;
        syncDisplay(true);
    }

    private void applyReciprocal() {
        if (mRawExpression.isEmpty() && !"0".equals(mCurrentResult) && !"Error".equals(mCurrentResult)) {
            mRawExpression = "1\u00F7(" + mCurrentResult.replace(",", "") + ")";
        } else if (!mRawExpression.isEmpty()) {
            mRawExpression = "1\u00F7(" + mRawExpression + ")";
        } else {
            return;
        }
        mJustEvaluated = false;
        onEquals();
    }

    private void onToggleSign() {
        if (mJustEvaluated) {
            if ("Error".equals(mCurrentResult) || "0".equals(mCurrentResult)) return;
            String raw = mCurrentResult.replace(",", "");
            if (raw.startsWith("\u2212") || raw.startsWith("-")) {
                mRawExpression = raw.substring(1);
            } else {
                mRawExpression = "\u2212" + raw;
            }
            mLastEvaluatedExpr = "";
            mJustEvaluated = false;
            syncDisplay(true);
            return;
        }
        if (mRawExpression.isEmpty()) {
            mRawExpression = "\u2212";
            syncDisplay(true);
            return;
        }
        // Toggle sign of trailing number segment
        int end = mRawExpression.length();
        int i = end - 1;
        while (i >= 0 && (Character.isDigit(mRawExpression.charAt(i)) || mRawExpression.charAt(i) == '.')) {
            i--;
        }
        if (i >= 0 && mRawExpression.charAt(i) == '\u2212'
                && (i == 0 || mRawExpression.charAt(i - 1) == '(' || isBinaryOperator(mRawExpression.charAt(i - 1)))) {
            mRawExpression = mRawExpression.substring(0, i) + mRawExpression.substring(i + 1);
        } else {
            mRawExpression = mRawExpression.substring(0, i + 1) + "\u2212" + mRawExpression.substring(i + 1);
        }
        syncDisplay(true);
    }

    private void onClearOrBackspaceTap() {
        if (mRawExpression.isEmpty() || mJustEvaluated) {
            onAllClear();
        } else {
            onBackspace();
        }
    }

    private void onAllClear() {
        mRawExpression = "";
        mLastEvaluatedExpr = "";
        mCurrentResult = "0";
        mJustEvaluated = false;
        syncDisplay(false);
    }

    private void onBackspace() {
        if (mJustEvaluated) {
            onAllClear();
            return;
        }
        if (mRawExpression.isEmpty()) return;
        String[] multiCharTokens = new String[] {
                "log\u2081\u2080(", "sin(", "cos(", "tan(", "ln(", "\u221A("
        };
        for (String tok : multiCharTokens) {
            if (mRawExpression.endsWith(tok)) {
                mRawExpression = mRawExpression.substring(0, mRawExpression.length() - tok.length());
                syncDisplay(true);
                return;
            }
        }
        mRawExpression = mRawExpression.substring(0, mRawExpression.length() - 1);
        syncDisplay(true);
    }

    private void onEquals() {
        if (mRawExpression.isEmpty()) return;
        String displayExpr = MathEngine.formatExpressionPreview(mRawExpression);
        MathEngine.EvalResult eval = MathEngine.evaluate(mRawExpression, mDegreesMode);
        mLastEvaluatedExpr = displayExpr;
        mCurrentResult = eval.formatted;
        mJustEvaluated = true;
        if (eval.valid) {
            HistoryStore.append(this, displayExpr, mRawExpression, eval.formatted);
            refreshHistoryBadge();
        }
        syncDisplay(false);
    }

    private void syncDisplay(boolean liveEvaluate) {
        if (mClearBtn != null) {
            boolean showBackspace = !mRawExpression.isEmpty() && !mJustEvaluated;
            mClearBtn.setLabel(showBackspace ? "\u232B" : "AC");
        }

        // Highlight active trailing operator button (iOS white-amber glow inversion!)
        char tail = (!mJustEvaluated && !mRawExpression.isEmpty())
                ? mRawExpression.charAt(mRawExpression.length() - 1) : '\0';
        if (mOpDiv != null) mOpDiv.setSelectedOperator(tail == '\u00F7');
        if (mOpMul != null) mOpMul.setSelectedOperator(tail == '\u00D7');
        if (mOpSub != null) mOpSub.setSelectedOperator(tail == '\u2212' && mRawExpression.length() > 1);
        if (mOpAdd != null) mOpAdd.setSelectedOperator(tail == '+');

        if (mJustEvaluated) {
            mExpressionView.setText(mLastEvaluatedExpr);
            setAutoSizedResultText(mCurrentResult);
            return;
        }

        if (mRawExpression.isEmpty()) {
            mExpressionView.setText("");
            setAutoSizedResultText("0");
            return;
        }

        String formattedExpr = MathEngine.formatExpressionPreview(mRawExpression);
        if (liveEvaluate) {
            MathEngine.EvalResult live = MathEngine.evaluate(mRawExpression, mDegreesMode);
            boolean hasOps = containsOperatorOrFunction(mRawExpression);
            if (hasOps && live.valid) {
                mExpressionView.setText(formattedExpr);
                mCurrentResult = live.formatted;
                setAutoSizedResultText(live.formatted);
            } else {
                mExpressionView.setText("");
                setAutoSizedResultText(formattedExpr);
            }
        } else {
            setAutoSizedResultText(formattedExpr);
        }
    }

    private void setAutoSizedResultText(String text) {
        int len = text != null ? text.length() : 0;
        float sizeDp;
        if (mScientificMode) {
            if (len <= 7) sizeDp = 54f;
            else if (len <= 10) sizeDp = 44f;
            else if (len <= 14) sizeDp = 35f;
            else sizeDp = 28f;
        } else {
            if (len <= 6) sizeDp = 68f;
            else if (len <= 9) sizeDp = 56f;
            else if (len <= 12) sizeDp = 44f;
            else if (len <= 16) sizeDp = 34f;
            else sizeDp = 27f;
        }
        mResultView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        mResultView.setText(text);
    }

    private static boolean isBinaryOperator(char c) {
        return c == '+' || c == '\u2212' || c == '\u00D7' || c == '\u00F7' || c == '^';
    }

    private static boolean containsOperatorOrFunction(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && isBinaryOperator(c)) return true;
            if (c == '%' || c == '\u00B2' || c == '\u03C0' || Character.isLetter(c)) return true;
        }
        return false;
    }

    private void applyThemeColors() {
        boolean dark = mPrefs.darkTheme;
        int primary = dark ? 0xFFF5F7FC : 0xFF141821;
        int muted = dark ? 0x9EEBEBF5 : 0x903C3C43;

        mExpressionView.setTextColor(muted);
        mResultView.setTextColor(primary);
        mHistoryPillText.setTextColor(primary);
        mModePillText.setTextColor(primary);
        mAngleBadge.setTextColor(0xFFFF9F0A);
        if (mHistoryTitleText != null) mHistoryTitleText.setTextColor(primary);

        for (SamuraiGlassCard card : mGlassCards) {
            card.updatePrefs(mPrefs);
        }
        for (CalcGlassButton btn : mAllButtons) {
            btn.updatePrefs(mPrefs);
        }
        if (mRoot != null) {
            mRoot.invalidate();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class BackdropFrameLayout extends FrameLayout {
        private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackdropFrameLayout(Context ctx) {
            super(ctx);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w > 2 && h > 2) {
                SamuraiAgslEngine.drawProceduralAppBackground(canvas, w, h, mPrefs.darkTheme);
                // Ambient warm-amber & sapphire spatial light pools behind keypad for rich AGSL refraction
                mGlowPaint.setShader(new RadialGradient(
                        w * 0.86f, h * 0.72f, w * 0.62f,
                        new int[] { mPrefs.darkTheme ? 0x36FF9F0A : 0x26FF9F0A, 0x00FF9F0A },
                        null, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, mGlowPaint);
            }
            super.onDraw(canvas);
        }
    }
}
