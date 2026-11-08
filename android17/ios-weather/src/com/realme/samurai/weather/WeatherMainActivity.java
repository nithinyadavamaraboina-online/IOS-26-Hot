package com.realme.samurai.weather;

import android.app.Activity;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
 * Clean-Room 90 Hz iOS 26 Animated Sky + Liquid Glass Weather (`WeatherMainActivity`).
 *
 * Features:
 * - 90 Hz `Choreographer` procedural atmospheric sky (`WeatherSkyView`)
 * - Zero-key live Open-Meteo HTTPS client + offline cache & realistic seed (`WeatherRepository`)
 * - City Switcher / Search Sheet (9 presets + worldwide Open-Meteo geocoding)
 * - Hero header + Hourly Forecast Glass Card + 10-Day Forecast Glass Card (multi-stop cyan->coral
 *   range bar & Today dot) + 2x2 Detail Squircle Grid (UV Index, Wind, Humidity & Dew Point,
 *   Feels Like & Pressure)
 */
public final class WeatherMainActivity extends Activity {

    private final SamuraiGlassPrefs.Snapshot mRawPrefs = new SamuraiGlassPrefs.Snapshot();
    private final SamuraiGlassPrefs.Snapshot mSkyGlassPrefs = new SamuraiGlassPrefs.Snapshot();
    private ContentObserver mPrefsObserver;
    private WeatherRepository mRepo;
    private final Handler mSearchDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingGeoRunnable;

    private FrameLayout mRoot;
    private WeatherSkyView mSkyView;
    private LinearLayout mContentColumn;

    // Top bar pills
    private SamuraiGlassCard mCitiesPill;
    private SamuraiGlassCard mStatusPill;
    private TextView mStatusPillText;

    // Hero Header
    private TextView mHeroCityText;
    private TextView mHeroTempText;
    private TextView mHeroConditionText;
    private TextView mHeroHiLoText;

    // Forecast Platters
    private SamuraiGlassCard mHourlyCard;
    private LinearLayout mHourlyStripRow;

    private SamuraiGlassCard mTenDayCard;
    private LinearLayout mTenDayRowsContainer;

    // 2x2 Detail Squircle Grid
    private SamuraiGlassCard mUvCard;
    private TextView mUvValueText;
    private TextView mUvCatText;
    private WeatherCanvasWidgets.UvSpectrumBarView mUvBar;
    private TextView mUvNoteText;

    private SamuraiGlassCard mWindCard;
    private TextView mWindSpeedText;
    private TextView mWindDirText;
    private WeatherCanvasWidgets.WindCompassDialView mWindCompass;

    private SamuraiGlassCard mHumidityCard;
    private TextView mHumidityValText;
    private View mHumidityFillBar;
    private TextView mDewPointText;

    private SamuraiGlassCard mFeelsPressureCard;
    private TextView mFeelsValText;
    private TextView mFeelsNoteText;
    private TextView mPressureVisText;

    // City Switcher / Geocoding Search Sheet
    private FrameLayout mCityOverlay;
    private SamuraiGlassCard mCitySheet;
    private SamuraiGlassSearchBar mCitySearchBar;
    private LinearLayout mCityResultsContainer;
    private boolean mCitySheetOpen = false;
    private SamuraiSpring mCitySheetSpring;

    private final List<SamuraiGlassCard> mGlassCards = new ArrayList<>();
    private WeatherModels.CitySpec mActiveCity;
    private WeatherModels.ForecastBundle mCurrentBundle;

    private final OnBackInvokedCallback mBackCallback = () -> {
        if (mCitySheetOpen) {
            toggleCitySheet(false);
        } else {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SamuraiGlassPrefs.configure90HzEdgeToEdge(this);
        syncGlassPrefsFromSystem();

        mRepo = new WeatherRepository(this);
        mActiveCity = mRepo.loadSelectedCity();

        buildUi();

        // Render instant cached/seed forecast in 0 ms, then refresh live over Open-Meteo HTTPS
        WeatherModels.ForecastBundle instant = mRepo.getInstantCachedOrSeed(mActiveCity);
        bindForecastBundle(instant);
        refreshLiveWeather();

        mPrefsObserver = SamuraiGlassPrefs.registerObserver(this, () -> {
            syncGlassPrefsFromSystem();
            SamuraiAgslEngine.invalidateWallpaperCache();
            refreshGlassSurfaces();
        });

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBackCallback);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        syncGlassPrefsFromSystem();
        refreshGlassSurfaces();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSkyView != null) mSkyView.setPaused(false);
    }

    @Override
    protected void onPause() {
        if (mSkyView != null) mSkyView.setPaused(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        SamuraiGlassPrefs.unregisterObserver(this, mPrefsObserver);
        if (mPendingGeoRunnable != null) {
            mSearchDebounceHandler.removeCallbacks(mPendingGeoRunnable);
        }
        if (mCitySheetSpring != null) mCitySheetSpring.cancel();
        if (mRepo != null) mRepo.shutdown();
        super.onDestroy();
    }

    private void syncGlassPrefsFromSystem() {
        SamuraiGlassPrefs.read(this, mRawPrefs);
        SamuraiGlassPrefs.read(this, mSkyGlassPrefs);
        // iOS 26 Weather always uses translucent dark-veil sky glass platters with crisp white text
        mSkyGlassPrefs.darkTheme = true;
    }

    private void buildUi() {
        mRoot = new FrameLayout(this);

        // 1. 90 Hz Procedural Animated Sky Layer
        mSkyView = new WeatherSkyView(this);
        mRoot.addView(mSkyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Scrollable Refractive Forecast Platters
        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setVerticalScrollBarEnabled(false);
        mainScroll.setClipToPadding(false);

        mContentColumn = new LinearLayout(this);
        mContentColumn.setOrientation(LinearLayout.VERTICAL);
        mContentColumn.setClipChildren(false);
        mContentColumn.setClipToPadding(false);
        mContentColumn.setPadding(dp(16f), dp(48f), dp(16f), dp(36f));

        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            mContentColumn.setPadding(
                    dp(16f),
                    Math.max(dp(42f), sys.top + dp(8f)),
                    dp(16f),
                    Math.max(dp(24f), sys.bottom + dp(16f)));
            return insets;
        });

        // Top Bar: Cities Switcher Pill (left) + Live Refresh Pill (right)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);

        mCitiesPill = makeSkyGlassCard(20f);
        mCitiesPill.setInteractivePressScale(true);
        mCitiesPill.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        TextView citiesTv = new TextView(this);
        citiesTv.setText("\u2630  Cities");
        citiesTv.setTextColor(0xFFFFFFFF);
        citiesTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        citiesTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mCitiesPill.addView(citiesTv);
        mCitiesPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(this, v);
            toggleCitySheet(true);
        });
        topBar.addView(mCitiesPill);

        View topSpacer = new View(this);
        topBar.addView(topSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        mStatusPill = makeSkyGlassCard(20f);
        mStatusPill.setInteractivePressScale(true);
        mStatusPill.setPadding(dp(13f), dp(8f), dp(13f), dp(8f));
        mStatusPillText = new TextView(this);
        mStatusPillText.setText("\u21BB  Updating\u2026");
        mStatusPillText.setTextColor(0xD8FFFFFF);
        mStatusPillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        mStatusPillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mStatusPill.addView(mStatusPillText);
        mStatusPill.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            refreshLiveWeather();
        });
        topBar.addView(mStatusPill);

        mContentColumn.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Hero Header (City, Giant Thin-Weight Numeral, Condition, H/L)
        LinearLayout heroBox = new LinearLayout(this);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        heroBox.setGravity(Gravity.CENTER_HORIZONTAL);
        heroBox.setPadding(dp(8f), dp(22f), dp(8f), dp(26f));

        mHeroCityText = new TextView(this);
        mHeroCityText.setGravity(Gravity.CENTER);
        mHeroCityText.setTextColor(0xFFFFFFFF);
        mHeroCityText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32f);
        mHeroCityText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mHeroCityText.setShadowLayer(8f, 0f, 2f, 0x44000000);
        heroBox.addView(mHeroCityText);

        mHeroTempText = new TextView(this);
        mHeroTempText.setGravity(Gravity.CENTER);
        mHeroTempText.setTextColor(0xFFFFFFFF);
        mHeroTempText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 86f);
        mHeroTempText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        mHeroTempText.setFontFeatureSettings("tnum");
        mHeroTempText.setIncludeFontPadding(false);
        mHeroTempText.setShadowLayer(10f, 0f, 3f, 0x38000000);
        heroBox.addView(mHeroTempText);

        mHeroConditionText = new TextView(this);
        mHeroConditionText.setGravity(Gravity.CENTER);
        mHeroConditionText.setTextColor(0xEEFFFFFF);
        mHeroConditionText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f);
        mHeroConditionText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroBox.addView(mHeroConditionText);

        mHeroHiLoText = new TextView(this);
        mHeroHiLoText.setGravity(Gravity.CENTER);
        mHeroHiLoText.setTextColor(0xE0FFFFFF);
        mHeroHiLoText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        mHeroHiLoText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mHeroHiLoText.setFontFeatureSettings("tnum");
        mHeroHiLoText.setPadding(0, dp(3f), 0, 0);
        heroBox.addView(mHeroHiLoText);

        mContentColumn.addView(heroBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Platter 1: 24-Hour Hourly Forecast Glass Card
        mHourlyCard = makeSkyGlassCard(24f);
        mHourlyCard.setPadding(dp(16f), dp(13f), dp(16f), dp(15f));
        mHourlyCard.addView(makePlatterCaption("\u23F2  HOURLY FORECAST \u00B7 NEXT 24 HOURS"));
        mHourlyCard.addView(makeHairline());

        HorizontalScrollView hourlyScroll = new HorizontalScrollView(this);
        hourlyScroll.setHorizontalScrollBarEnabled(false);
        mHourlyStripRow = new LinearLayout(this);
        mHourlyStripRow.setOrientation(LinearLayout.HORIZONTAL);
        mHourlyStripRow.setPadding(0, dp(10f), 0, dp(2f));
        hourlyScroll.addView(mHourlyStripRow);
        mHourlyCard.addView(hourlyScroll);

        LinearLayout.LayoutParams cardMarginLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardMarginLp.bottomMargin = dp(12f);
        mContentColumn.addView(mHourlyCard, cardMarginLp);

        // Platter 2: 10-Day Forecast Glass Card
        mTenDayCard = makeSkyGlassCard(24f);
        mTenDayCard.setPadding(dp(16f), dp(13f), dp(16f), dp(10f));
        mTenDayCard.addView(makePlatterCaption("\uD83D\uDCC5  10-DAY FORECAST"));
        mTenDayCard.addView(makeHairline());

        mTenDayRowsContainer = new LinearLayout(this);
        mTenDayRowsContainer.setOrientation(LinearLayout.VERTICAL);
        mTenDayCard.addView(mTenDayRowsContainer);

        LinearLayout.LayoutParams tenDayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tenDayLp.bottomMargin = dp(12f);
        mContentColumn.addView(mTenDayCard, tenDayLp);

        // Platter 3: 2x2 Detail Squircle Grid
        buildDetailSquircleGrid();

        mainScroll.addView(mContentColumn);
        mRoot.addView(mainScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Sliding City Switcher / Search Modal Sheet
        buildCitySwitcherSheet();
        mRoot.addView(mCityOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(mRoot);
    }

    private void buildDetailSquircleGrid() {
        int gap = dp(6f);

        // Row A: UV Index + Wind
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        rowA.setClipChildren(false);
        rowA.setClipToPadding(false);

        mUvCard = makeSkyGlassCard(22f);
        mUvCard.setPadding(dp(15f), dp(13f), dp(15f), dp(15f));
        mUvCard.addView(makePlatterCaption("\u2600  UV INDEX"));
        mUvValueText = makeBigMetricText();
        mUvCard.addView(mUvValueText);
        mUvCatText = new TextView(this);
        mUvCatText.setTextColor(0xEEFFFFFF);
        mUvCatText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        mUvCatText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mUvCard.addView(mUvCatText);
        mUvBar = new WeatherCanvasWidgets.UvSpectrumBarView(this, 5f);
        LinearLayout.LayoutParams uvBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18f));
        uvBarLp.topMargin = dp(8f);
        uvBarLp.bottomMargin = dp(8f);
        mUvCard.addView(mUvBar, uvBarLp);
        mUvNoteText = makeCardFooterText();
        mUvCard.addView(mUvNoteText);

        mWindCard = makeSkyGlassCard(22f);
        mWindCard.setPadding(dp(15f), dp(13f), dp(15f), dp(14f));
        mWindCard.addView(makePlatterCaption("\uD83C\uDF2C  WIND"));
        mWindSpeedText = new TextView(this);
        mWindSpeedText.setTextColor(0xFFFFFFFF);
        mWindSpeedText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        mWindSpeedText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mWindSpeedText.setFontFeatureSettings("tnum");
        mWindSpeedText.setPadding(0, dp(4f), 0, 0);
        mWindCard.addView(mWindSpeedText);
        mWindDirText = makeCardFooterText();
        mWindCard.addView(mWindDirText);
        mWindCompass = new WeatherCanvasWidgets.WindCompassDialView(this, 68);
        LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(dp(82f), dp(82f));
        compLp.gravity = Gravity.CENTER_HORIZONTAL;
        compLp.topMargin = dp(6f);
        mWindCard.addView(mWindCompass, compLp);

        LinearLayout.LayoutParams leftLpA = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLpA.rightMargin = gap;
        LinearLayout.LayoutParams rightLpA = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLpA.leftMargin = gap;
        rowA.addView(mUvCard, leftLpA);
        rowA.addView(mWindCard, rightLpA);

        LinearLayout.LayoutParams rowALp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowALp.bottomMargin = dp(12f);
        mContentColumn.addView(rowA, rowALp);

        // Row B: Humidity & Dew Point + Feels Like & Pressure
        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setClipChildren(false);
        rowB.setClipToPadding(false);

        mHumidityCard = makeSkyGlassCard(22f);
        mHumidityCard.setPadding(dp(15f), dp(13f), dp(15f), dp(15f));
        mHumidityCard.addView(makePlatterCaption("\uD83D\uDCA7  HUMIDITY"));
        mHumidityValText = makeBigMetricText();
        mHumidityCard.addView(mHumidityValText);

        FrameLayout humTrack = new FrameLayout(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(4f));
        trackBg.setColor(0x33FFFFFF);
        humTrack.setBackground(trackBg);
        mHumidityFillBar = new View(this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(4f));
        fillBg.setColor(0xFF5AC8FA);
        mHumidityFillBar.setBackground(fillBg);
        humTrack.addView(mHumidityFillBar, new FrameLayout.LayoutParams(dp(60f), dp(6f)));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6f));
        trackLp.topMargin = dp(10f);
        trackLp.bottomMargin = dp(12f);
        mHumidityCard.addView(humTrack, trackLp);

        mDewPointText = makeCardFooterText();
        mHumidityCard.addView(mDewPointText);

        mFeelsPressureCard = makeSkyGlassCard(22f);
        mFeelsPressureCard.setPadding(dp(15f), dp(13f), dp(15f), dp(15f));
        mFeelsPressureCard.addView(makePlatterCaption("\uD83C\uDF21  FEELS LIKE & PRESSURE"));
        mFeelsValText = makeBigMetricText();
        mFeelsPressureCard.addView(mFeelsValText);
        mFeelsNoteText = makeCardFooterText();
        mFeelsNoteText.setPadding(0, dp(4f), 0, dp(10f));
        mFeelsPressureCard.addView(mFeelsNoteText);
        mFeelsPressureCard.addView(makeHairline());
        mPressureVisText = makeCardFooterText();
        mPressureVisText.setPadding(0, dp(8f), 0, 0);
        mFeelsPressureCard.addView(mPressureVisText);

        LinearLayout.LayoutParams leftLpB = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLpB.rightMargin = gap;
        LinearLayout.LayoutParams rightLpB = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLpB.leftMargin = gap;
        rowB.addView(mHumidityCard, leftLpB);
        rowB.addView(mFeelsPressureCard, rightLpB);

        mContentColumn.addView(rowB, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildCitySwitcherSheet() {
        mCityOverlay = new FrameLayout(this);
        mCityOverlay.setVisibility(View.GONE);
        mCityOverlay.setBackgroundColor(0x90000000);
        mCityOverlay.setOnClickListener(v -> toggleCitySheet(false));

        mCitySheet = makeSkyGlassCard(30f);
        mCitySheet.setClickable(true);
        mCitySheet.setPadding(dp(18f), dp(18f), dp(18f), dp(24f));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Weather Cities");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(0xFF5AC8FA);
        done.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        done.setPadding(dp(10f), dp(6f), dp(4f), dp(6f));
        done.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            toggleCitySheet(false);
        });
        header.addView(done);
        mCitySheet.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mCitySearchBar = new SamuraiGlassSearchBar(
                this,
                mSkyGlassPrefs,
                "Search city (Open-Meteo Geocoding)",
                this::onCitySearchQueryChanged);
        mGlassCards.add(mCitySearchBar);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(12f);
        searchLp.bottomMargin = dp(10f);
        mCitySheet.addView(mCitySearchBar, searchLp);

        ScrollView cityScroll = new ScrollView(this);
        cityScroll.setVerticalScrollBarEnabled(false);
        mCityResultsContainer = new LinearLayout(this);
        mCityResultsContainer.setOrientation(LinearLayout.VERTICAL);
        cityScroll.addView(mCityResultsContainer);

        mCitySheet.addView(cityScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(390f)));

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sheetLp.setMargins(dp(10f), dp(54f), dp(10f), dp(14f));
        mCityOverlay.addView(mCitySheet, sheetLp);

        mCitySheetSpring = new SamuraiSpring(1f, (val, vel) -> {
            float h = Math.max(dp(480f), mCitySheet.getHeight());
            mCitySheet.setTranslationY(val * h);
            mCityOverlay.getBackground().setAlpha(Math.round(150f * (1f - Math.min(1f, Math.max(0f, val)))));
            if (!mCitySheetOpen && val >= 0.98f) {
                mCityOverlay.setVisibility(View.GONE);
            }
        }).setTuning(28f, 0.82f);
    }

    private void toggleCitySheet(boolean open) {
        mCitySheetOpen = open;
        if (open) {
            mCitySearchBar.getEditText().setText("");
            populateCityRows(WeatherModels.PRESET_CITIES);
            mCityOverlay.setVisibility(View.VISIBLE);
            mCitySheetSpring.snapTo(1f);
            mCitySheetSpring.animateTo(0f);
        } else {
            hideKeyboard();
            mCitySheetSpring.animateTo(1f);
        }
    }

    private void onCitySearchQueryChanged(String query) {
        if (mPendingGeoRunnable != null) {
            mSearchDebounceHandler.removeCallbacks(mPendingGeoRunnable);
        }
        mPendingGeoRunnable = () -> mRepo.searchCities(query, this::populateCityRows);
        mSearchDebounceHandler.postDelayed(mPendingGeoRunnable, 220L);
    }

    private void populateCityRows(List<WeatherModels.CitySpec> cities) {
        mCityResultsContainer.removeAllViews();
        if (cities == null || cities.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No matching cities found.");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(0x99FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
            empty.setPadding(dp(12f), dp(36f), dp(12f), dp(36f));
            mCityResultsContainer.addView(empty);
            return;
        }

        for (int i = 0; i < cities.size(); i++) {
            final WeatherModels.CitySpec spec = cities.get(i);
            WeatherModels.ForecastBundle preview = mRepo.getInstantCachedOrSeed(spec);
            boolean selected = (mActiveCity != null && mActiveCity.cacheKey().equals(spec.cacheKey()));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12f), dp(12f), dp(12f), dp(12f));
            if (selected) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setCornerRadius(dp(16f));
                selBg.setColor(0x285AC8FA);
                row.setBackground(selBg);
            }

            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            TextView nameTv = new TextView(this);
            nameTv.setText(spec.name);
            nameTv.setTextColor(0xFFFFFFFF);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f);
            nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            leftCol.addView(nameTv);

            TextView subTv = new TextView(this);
            subTv.setText(spec.region.isEmpty() ? preview.conditionText : (spec.region + " \u00B7 " + preview.conditionText));
            subTv.setTextColor(0xB3FFFFFF);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subTv.setSingleLine(true);
            leftCol.addView(subTv);
            row.addView(leftCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tempTv = new TextView(this);
            tempTv.setText(preview.currentTempC + "\u00B0");
            tempTv.setTextColor(0xFFFFFFFF);
            tempTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f);
            tempTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            tempTv.setFontFeatureSettings("tnum");
            row.addView(tempTv);

            row.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(this, v);
                mActiveCity = spec;
                mRepo.saveSelectedCity(spec);
                bindForecastBundle(mRepo.getInstantCachedOrSeed(spec));
                toggleCitySheet(false);
                refreshLiveWeather();
            });
            mCityResultsContainer.addView(row);

            if (i < cities.size() - 1) {
                mCityResultsContainer.addView(makeHairline());
            }
        }
    }

    private void refreshLiveWeather() {
        if (mActiveCity == null) return;
        mStatusPillText.setText("\u21BB  Syncing\u2026");
        mRepo.fetchLiveForecast(mActiveCity, this::bindForecastBundle);
    }

    private void bindForecastBundle(WeatherModels.ForecastBundle b) {
        if (b == null) return;
        mCurrentBundle = b;

        mSkyView.setWeatherCondition(b.weatherCode, b.isDay);
        refreshGlassSurfaces();

        mHeroCityText.setText(b.city.name);
        mHeroTempText.setText(b.currentTempC + "\u00B0");
        mHeroConditionText.setText(b.conditionText);
        mHeroHiLoText.setText("H:" + b.highTempC + "\u00B0   L:" + b.lowTempC + "\u00B0");

        if (b.fromLiveNetwork) {
            String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(b.fetchedEpochMs));
            mStatusPillText.setText("\u2726 Live \u00B7 " + time);
        } else {
            mStatusPillText.setText("\u21BB Offline Ready");
        }

        // Populate 24-Hour Hourly Strip
        mHourlyStripRow.removeAllViews();
        for (int i = 0; i < b.hourly.size(); i++) {
            WeatherModels.HourlyItem h = b.hourly.get(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(10f), dp(2f), dp(10f), dp(2f));

            TextView timeTv = new TextView(this);
            timeTv.setText(h.label);
            timeTv.setTextColor(i == 0 ? 0xFFFFFFFF : 0xCCFFFFFF);
            timeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            timeTv.setTypeface(Typeface.create("sans-serif-medium", i == 0 ? Typeface.BOLD : Typeface.NORMAL));
            col.addView(timeTv);

            WeatherCanvasWidgets.WeatherIconView icon = new WeatherCanvasWidgets.WeatherIconView(
                    this, WeatherModels.resolveIconKind(h.weatherCode, h.isDay));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28f), dp(28f));
            iconLp.topMargin = dp(8f);
            iconLp.bottomMargin = dp(8f);
            col.addView(icon, iconLp);

            TextView tempTv = new TextView(this);
            tempTv.setText(h.tempC + "\u00B0");
            tempTv.setTextColor(0xFFFFFFFF);
            tempTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
            tempTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tempTv.setFontFeatureSettings("tnum");
            col.addView(tempTv);

            mHourlyStripRow.addView(col);
        }

        // Populate 10-Day Forecast Platter with global range span
        mTenDayRowsContainer.removeAllViews();
        int weekMin = Integer.MAX_VALUE;
        int weekMax = Integer.MIN_VALUE;
        for (WeatherModels.DailyItem d : b.daily) {
            if (d.lowTempC < weekMin) weekMin = d.lowTempC;
            if (d.highTempC > weekMax) weekMax = d.highTempC;
        }
        if (weekMin == Integer.MAX_VALUE) {
            weekMin = 15;
            weekMax = 32;
        }

        for (int i = 0; i < b.daily.size(); i++) {
            WeatherModels.DailyItem day = b.daily.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10f), 0, dp(10f));

            TextView dayTv = new TextView(this);
            dayTv.setText(day.dayLabel);
            dayTv.setTextColor(0xFFFFFFFF);
            dayTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            dayTv.setTypeface(Typeface.create("sans-serif-medium", day.isToday ? Typeface.BOLD : Typeface.NORMAL));
            row.addView(dayTv, new LinearLayout.LayoutParams(dp(64f), ViewGroup.LayoutParams.WRAP_CONTENT));

            WeatherCanvasWidgets.WeatherIconView icon = new WeatherCanvasWidgets.WeatherIconView(
                    this, WeatherModels.resolveIconKind(day.weatherCode, true));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(26f), dp(26f));
            iconLp.rightMargin = dp(10f);
            row.addView(icon, iconLp);

            TextView lowTv = new TextView(this);
            lowTv.setText(day.lowTempC + "\u00B0");
            lowTv.setGravity(Gravity.END);
            lowTv.setTextColor(0x9EFFFFFF);
            lowTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            lowTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lowTv.setFontFeatureSettings("tnum");
            row.addView(lowTv, new LinearLayout.LayoutParams(dp(36f), ViewGroup.LayoutParams.WRAP_CONTENT));

            WeatherCanvasWidgets.TenDayRangeBarView rangeBar = new WeatherCanvasWidgets.TenDayRangeBarView(
                    this, weekMin, weekMax, day.lowTempC, day.highTempC, day.isToday, b.currentTempC);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(18f), 1f);
            barLp.leftMargin = dp(10f);
            barLp.rightMargin = dp(10f);
            row.addView(rangeBar, barLp);

            TextView highTv = new TextView(this);
            highTv.setText(day.highTempC + "\u00B0");
            highTv.setGravity(Gravity.END);
            highTv.setTextColor(0xFFFFFFFF);
            highTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            highTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            highTv.setFontFeatureSettings("tnum");
            row.addView(highTv, new LinearLayout.LayoutParams(dp(36f), ViewGroup.LayoutParams.WRAP_CONTENT));

            mTenDayRowsContainer.addView(row);
            if (i < b.daily.size() - 1) {
                mTenDayRowsContainer.addView(makeHairline());
            }
        }

        // Bind 2x2 Detail Squircle Cards
        int uvRounded = Math.round(b.uvIndex);
        mUvValueText.setText(String.valueOf(uvRounded));
        mUvCatText.setText(WeatherModels.uvCategory(b.uvIndex));
        mUvBar.setUvValue(b.uvIndex);
        mUvNoteText.setText(WeatherModels.uvAdvisory(b.uvIndex));

        mWindSpeedText.setText(Math.round(b.windKmh) + " km/h");
        mWindDirText.setText(WeatherModels.compassHeading(b.windDirectionDeg) + " \u00B7 " + b.windDirectionDeg + "\u00B0");
        mWindCompass.setDegrees(b.windDirectionDeg);

        mHumidityValText.setText(b.humidityPct + "%");
        mHumidityCard.post(() -> {
            View parent = (View) mHumidityFillBar.getParent();
            if (parent != null && parent.getWidth() > 0) {
                int targetW = Math.max(dp(8f), Math.round(parent.getWidth() * (b.humidityPct / 100f)));
                ViewGroup.LayoutParams lp = mHumidityFillBar.getLayoutParams();
                lp.width = targetW;
                mHumidityFillBar.setLayoutParams(lp);
            }
        });
        mDewPointText.setText("The dew point is " + b.dewPointC + "\u00B0 right now.");

        mFeelsValText.setText(b.feelsLikeC + "\u00B0");
        if (b.feelsLikeC > b.currentTempC) {
            mFeelsNoteText.setText("Humidity makes it feel warmer.");
        } else if (b.feelsLikeC < b.currentTempC) {
            mFeelsNoteText.setText("Breeze makes it feel cooler.");
        } else {
            mFeelsNoteText.setText("Similar to the actual temperature.");
        }
        mPressureVisText.setText(String.format(
                Locale.US,
                "Pressure: %d hPa\nVisibility: %.1f km",
                b.surfacePressureHpa,
                b.visibilityKm));
    }

    private void refreshGlassSurfaces() {
        SamuraiAgslEngine.BackdropPair skyBg = mSkyView.getSkyBackdropPair();
        int tint = mSkyView.getPlatterTintColor();
        for (SamuraiGlassCard card : mGlassCards) {
            card.setCustomBackdrop(skyBg);
            card.setTint(tint, 0.24f);
            card.updatePrefs(mSkyGlassPrefs);
        }
    }

    private SamuraiGlassCard makeSkyGlassCard(float cornerDp) {
        SamuraiGlassCard card = new SamuraiGlassCard(this, mSkyGlassPrefs).setCornerRadiusDp(cornerDp);
        mGlassCards.add(card);
        return card;
    }

    private TextView makePlatterCaption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0x9EFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.05f);
        tv.setPadding(0, 0, 0, dp(8f));
        return tv;
    }

    private TextView makeBigMetricText() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32f);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tv.setFontFeatureSettings("tnum");
        return tv;
    }

    private TextView makeCardFooterText() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setLineSpacing(dp(2f), 1.0f);
        return tv;
    }

    private View makeHairline() {
        View v = new View(this);
        v.setBackgroundColor(0x28FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
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

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
