package com.realme.samurai.styler;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone 90Hz AGSL Liquid Glass Customization Studio (`iOS Styler` — `com.realme.samurai.styler`).
 *
 * Every container, segmented selector, slider droplet, toggle switch, wallpaper swatch, and action
 * pill in this Activity is rendered via real-time GPU AGSL refraction (`StylerAgslEngine`) over a
 * live screen-space iOS 26 petal backdrop — zero flat Material cards.
 */
public class IosStylerActivity extends Activity {
    private final StylerAgslEngine.OpticalContext mOpt = new StylerAgslEngine.OpticalContext();
    private final List<View> mGlassViews = new ArrayList<>();
    private final List<TextView> mPrimaryTexts = new ArrayList<>();
    private final List<TextView> mSecondaryTexts = new ArrayList<>();
    private final List<View> mHairlineDividers = new ArrayList<>();

    private float mDensity;
    private LiveBackdropView mBackdropView;
    private LiveHomePreviewView mPreviewView;

    private LiquidGlassSegmentedControl mIconSegment;
    private LiquidGlassSegmentedControl mGlassSegment;
    private LiquidGlassDropletSlider mRefractSlider;
    private LiquidGlassDropletSlider mFrostSlider;
    private LiquidGlassDropletSlider mWpBrightnessSlider;
    private TextView mRefractValueBadge;
    private TextView mFrostValueBadge;
    private TextView mWpBrightnessValueBadge;

    private LiquidGlassDropletSwitch mSearchPillSwitch;
    private LiquidGlassDropletSwitch mGesturePillSwitch;
    private LiquidGlassDropletSwitch mLock90Switch;
    private LiquidGlassDropletSwitch mDcDimSwitch;

    private final LiquidGlassCardView[] mWpCards = new LiquidGlassCardView[3];
    private final TextView[] mWpStatusBadges = new TextView[3];
    private int mActiveWallpaperPreset = StylerPrefsBridge.WP_SKY_GLASS;

    private boolean mResumed = false;
    private long mStartNanos = 0L;

    private final Choreographer.FrameCallback mAuroraFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mResumed) {
                return;
            }
            if (mStartNanos == 0L) {
                mStartNanos = frameTimeNanos;
            }
            float elapsed = (frameTimeNanos - mStartNanos) / 1_000_000_000f;
            mOpt.phase = elapsed * 0.65f;
            if (mBackdropView != null) {
                mBackdropView.invalidate();
            }
            invalidateAllGlassSurfaces();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final ContentObserver mSettingsObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    syncControlsFromSystem();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDensity = getResources().getDisplayMetrics().density;

        configureEdgeToEdge90HzWindow();
        loadInitialOptics();
        setContentView(buildStudioHierarchy());
        syncTypographyAndSystemBars();
        registerObservers();
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdge90HzWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setDecorFitsSystemWindows(false);

        try {
            Display display = getDisplay();
            if (display != null) {
                Display.Mode[] modes = display.getSupportedModes();
                Display.Mode best90 = null;
                for (Display.Mode m : modes) {
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

    private void loadInitialOptics() {
        Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        mOpt.screenW = Math.max(1, bounds.width());
        mOpt.screenH = Math.max(1, bounds.height());

        mActiveWallpaperPreset = StylerPrefsBridge.getWallpaperPreset(this);
        mOpt.backdrop = StylerWallpaperEngine.getBackdropPair(this, mActiveWallpaperPreset);
        mOpt.iconMode = StylerPrefsBridge.getIconMode(this);
        mOpt.glassMode = StylerPrefsBridge.getGlassMode(this);
        mOpt.refractScale = StylerPrefsBridge.getRefractionPct(this) / 100f;
        mOpt.frostScale = StylerPrefsBridge.getFrostPct(this) / 100f;
        mOpt.wpBrightnessScale = StylerPrefsBridge.getWallpaperBrightnessPct(this) / 100f;
        mOpt.searchPillEnabled = StylerPrefsBridge.isSearchPillEnabled(this);
        mOpt.gesturePillEnabled = StylerPrefsBridge.isGesturePillEnabled(this);
    }

    private View buildStudioHierarchy() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Live full-bleed iOS 26 Petal Backdrop
        mBackdropView = new LiveBackdropView();
        root.addView(mBackdropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Smooth 90Hz ScrollView refracting screen-space petals on every scroll delta
        ScrollView scrollView = new ScrollView(this);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scrollView.setClipToPadding(false);
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                invalidateAllGlassSurfaces());

        final LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        final int padX = dp(16f);
        final int baseTop = dp(18f);
        final int baseBot = dp(34f);
        column.setPadding(padX, dp(52f), padX, dp(48f));

        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            column.setPadding(padX, sys.top + baseTop, padX, sys.bottom + baseBot);
            Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
            mOpt.screenW = Math.max(1, bounds.width());
            mOpt.screenH = Math.max(1, bounds.height());
            invalidateAllGlassSurfaces();
            return insets;
        });

        // Header Row (Back to Settings pill if launched from Settings + Title + 90Hz badge)
        column.addView(buildHeroHeader());

        // Card 1: Live Interactive iOS 26 Home Preview Card
        mPreviewView = new LiveHomePreviewView(this, mOpt);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(268f));
        prevLp.topMargin = dp(14f);
        column.addView(mPreviewView, prevLp);
        mGlassViews.add(mPreviewView);

        // Card 2: Icon Appearance Studio
        column.addView(buildIconAppearanceCard(), cardLayoutParams());

        // Card 3: Liquid Glass Material & Optical Engine
        column.addView(buildOpticalEngineCard(), cardLayoutParams());

        // Card 4: Home Screen & Navigation Bar Controls
        column.addView(buildHomeNavigationCard(), cardLayoutParams());

        // Card 5: iOS 26 Liquid Glass Wallpapers
        column.addView(buildWallpapersCard(), cardLayoutParams());

        // Card 6: 90Hz ProMotion Smoothness & OLED Hardware
        column.addView(buildHardwareSmoothnessCard(), cardLayoutParams());

        scrollView.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private View buildHeroHeader() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipChildren(false);

        Intent launchIntent = getIntent();
        String act = (launchIntent != null) ? launchIntent.getAction() : null;
        boolean fromSettings = act != null && !Intent.ACTION_MAIN.equals(act);
        if (fromSettings) {
            LiquidGlassCardView backPill = createGlassCard(18f, 0.16f);
            backPill.setOrientation(LinearLayout.HORIZONTAL);
            backPill.setGravity(Gravity.CENTER_VERTICAL);
            backPill.setPadding(dp(14f), dp(7f), dp(16f), dp(7f));
            TextView backTv = makePrimaryText("‹  Settings", 13.5f, true);
            backPill.addView(backTv);
            backPill.setOnClickListener(v -> {
                StylerPrefsBridge.performTick(v, false);
                finish();
            });
            LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bpLp.bottomMargin = dp(10f);
            wrap.addView(backPill, bpLp);
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setClipChildren(false);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = makePrimaryText("iOS Styler", 30f, true);
        title.setLetterSpacing(-0.02f);
        TextView sub = makeSecondaryText("iOS 26 Liquid Glass Optical Studio • RMX1931", 12.8f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        leftCol.addView(title);
        leftCol.addView(sub, subLp);

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(leftCol, leftLp);

        LiquidGlassCardView badgeCard = createGlassCard(16f, 0.22f);
        badgeCard.setPadding(dp(12f), dp(6f), dp(12f), dp(6f));
        TextView badgeTv = makePrimaryText("90Hz AGSL", 11.5f, true);
        badgeCard.addView(badgeTv);
        titleRow.addView(badgeCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wrap.addView(titleRow);
        return wrap;
    }

    private View buildIconAppearanceCard() {
        LiquidGlassCardView card = createGlassCard(26f, 0.0f);
        int p = dp(18f);
        card.setPadding(p, dp(16f), p, dp(18f));

        card.addView(makePrimaryText("Icon Appearance Studio", 17f, true));
        TextView sub = makeSecondaryText(
                "Live G2 squircle multi-plane appearance across Home Screen & Dock", 12.4f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3f);
        subLp.bottomMargin = dp(13f);
        card.addView(sub, subLp);

        mIconSegment = new LiquidGlassSegmentedControl(
                this,
                mOpt,
                new String[]{"Default", "Dark", "Clear"},
                new int[]{
                        StylerPrefsBridge.ICON_DEFAULT,
                        StylerPrefsBridge.ICON_DARK,
                        StylerPrefsBridge.ICON_CLEAR
                },
                mOpt.iconMode);
        mIconSegment.setOnSegmentSelectedListener((index, value) -> {
            mOpt.iconMode = value;
            StylerPrefsBridge.setIconMode(IosStylerActivity.this, value);
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
            invalidateAllGlassSurfaces();
        });
        mGlassViews.add(mIconSegment);
        card.addView(mIconSegment, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)));

        return card;
    }

    private View buildOpticalEngineCard() {
        LiquidGlassCardView card = createGlassCard(26f, 0.0f);
        int p = dp(18f);
        card.setPadding(p, dp(16f), p, dp(18f));

        // Header + Reset 100% action pill
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setClipChildren(false);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.addView(makePrimaryText("Liquid Glass Material", 17f, true));
        TextView sub = makeSecondaryText(
                "Spherical Snell meniscus, 7-band ROYGCBP dispersion & frost", 12.2f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        titleWrap.addView(sub, subLp);
        topRow.addView(titleWrap, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LiquidGlassCardView resetPill = createGlassCard(14f, 0.20f);
        resetPill.setPadding(dp(11f), dp(6f), dp(11f), dp(6f));
        resetPill.addView(makePrimaryText("Reset Defaults", 11.4f, true));
        resetPill.setOnClickListener(v -> {
            StylerPrefsBridge.performTick(v, true);
            mOpt.refractScale = StylerPrefsBridge.DEFAULT_REFRACTION_PCT / 100f;
            mOpt.frostScale = StylerPrefsBridge.DEFAULT_FROST_PCT / 100f;
            mOpt.wpBrightnessScale = StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT / 100f;
            StylerPrefsBridge.setRefractionPct(
                    IosStylerActivity.this, StylerPrefsBridge.DEFAULT_REFRACTION_PCT);
            StylerPrefsBridge.setFrostPct(
                    IosStylerActivity.this, StylerPrefsBridge.DEFAULT_FROST_PCT);
            StylerPrefsBridge.setWallpaperBrightnessPct(
                    IosStylerActivity.this, StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT);
            if (mRefractSlider != null) {
                mRefractSlider.setPercent(StylerPrefsBridge.DEFAULT_REFRACTION_PCT, false);
            }
            if (mFrostSlider != null) {
                mFrostSlider.setPercent(StylerPrefsBridge.DEFAULT_FROST_PCT, false);
            }
            if (mWpBrightnessSlider != null) {
                mWpBrightnessSlider.setPercent(StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT, false);
            }
            if (mRefractValueBadge != null) {
                mRefractValueBadge.setText(
                        String.format(Locale.US, "%d%%", StylerPrefsBridge.DEFAULT_REFRACTION_PCT));
            }
            if (mFrostValueBadge != null) {
                mFrostValueBadge.setText(
                        String.format(Locale.US, "%d%%", StylerPrefsBridge.DEFAULT_FROST_PCT));
            }
            if (mWpBrightnessValueBadge != null) {
                mWpBrightnessValueBadge.setText(
                        String.format(Locale.US, "%d%%", StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT));
            }
            syncTypographyAndSystemBars();
            if (mBackdropView != null) mBackdropView.invalidate();
            if (mPreviewView != null) mPreviewView.notifyOpticsChanged();
            invalidateAllGlassSurfaces();
        });
        topRow.addView(resetPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(topRow);

        // Glass Style Segmented Control: Clear Refractive (0), Frosted Tinted (1), Solid Matte (2)
        mGlassSegment = new LiquidGlassSegmentedControl(
                this,
                mOpt,
                new String[]{"Clear Refractive", "Frosted Tinted", "Solid Matte"},
                new int[]{
                        StylerPrefsBridge.GLASS_CLEAR_REFRACTIVE,
                        StylerPrefsBridge.GLASS_FROSTED_TINTED,
                        StylerPrefsBridge.GLASS_SOLID_MATTE
                },
                mOpt.glassMode);
        mGlassSegment.setOnSegmentSelectedListener((index, value) -> {
            mOpt.glassMode = value;
            StylerPrefsBridge.setGlassMode(IosStylerActivity.this, value);
            syncTypographyAndSystemBars();
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
            invalidateAllGlassSurfaces();
        });
        mGlassViews.add(mGlassSegment);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48f));
        segLp.topMargin = dp(13f);
        card.addView(mGlassSegment, segLp);

        // Slider 1: Refraction Strength (0..150%)
        int initRefract = Math.round(mOpt.refractScale * 100f);
        LinearLayout refHeader = new LinearLayout(this);
        refHeader.setOrientation(LinearLayout.HORIZONTAL);
        refHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView refLabel = makePrimaryText("Refraction Strength", 14.4f, true);
        mRefractValueBadge = makePrimaryText(
                String.format(Locale.US, "%d%%", initRefract), 13.5f, true);
        refHeader.addView(refLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refHeader.addView(mRefractValueBadge);
        LinearLayout.LayoutParams rhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rhLp.topMargin = dp(16f);
        card.addView(refHeader, rhLp);

        mRefractSlider = new LiquidGlassDropletSlider(
                this, mOpt, 0, 150, initRefract, StylerPrefsBridge.DEFAULT_REFRACTION_PCT);
        mRefractSlider.setOnPercentChangedListener(percent -> {
            mOpt.refractScale = percent / 100f;
            if (mRefractValueBadge != null) {
                mRefractValueBadge.setText(String.format(Locale.US, "%d%%", percent));
            }
            StylerPrefsBridge.setRefractionPct(IosStylerActivity.this, percent);
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
            invalidateAllGlassSurfaces();
        });
        mGlassViews.add(mRefractSlider);
        card.addView(mRefractSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)));

        // Slider 2: Glass Blur / Frost (0..150%, default 20%)
        int initFrost = Math.round(mOpt.frostScale * 100f);
        LinearLayout frostHeader = new LinearLayout(this);
        frostHeader.setOrientation(LinearLayout.HORIZONTAL);
        frostHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView frostLabel = makePrimaryText("Glass Blur / Frost", 14.4f, true);
        mFrostValueBadge = makePrimaryText(
                String.format(Locale.US, "%d%%", initFrost), 13.5f, true);
        frostHeader.addView(frostLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        frostHeader.addView(mFrostValueBadge);
        LinearLayout.LayoutParams fhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fhLp.topMargin = dp(8f);
        card.addView(frostHeader, fhLp);

        mFrostSlider = new LiquidGlassDropletSlider(
                this, mOpt, 0, 150, initFrost, StylerPrefsBridge.DEFAULT_FROST_PCT);
        mFrostSlider.setOnPercentChangedListener(percent -> {
            mOpt.frostScale = percent / 100f;
            if (mFrostValueBadge != null) {
                mFrostValueBadge.setText(String.format(Locale.US, "%d%%", percent));
            }
            StylerPrefsBridge.setFrostPct(IosStylerActivity.this, percent);
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
            invalidateAllGlassSurfaces();
        });
        mGlassViews.add(mFrostSlider);
        card.addView(mFrostSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)));

        // Slider 3: Wallpaper Depth Brightness (0..100%, default 70% vs 100% icons)
        int initWpBright = Math.round(mOpt.wpBrightnessScale * 100f);
        LinearLayout wbHeader = new LinearLayout(this);
        wbHeader.setOrientation(LinearLayout.HORIZONTAL);
        wbHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView wbLabel = makePrimaryText("Wallpaper Brightness (Depth)", 14.4f, true);
        mWpBrightnessValueBadge = makePrimaryText(
                String.format(Locale.US, "%d%%", initWpBright), 13.5f, true);
        wbHeader.addView(wbLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wbHeader.addView(mWpBrightnessValueBadge);
        LinearLayout.LayoutParams wbhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wbhLp.topMargin = dp(8f);
        card.addView(wbHeader, wbhLp);

        mWpBrightnessSlider = new LiquidGlassDropletSlider(
                this, mOpt, 0, 100, initWpBright, StylerPrefsBridge.DEFAULT_WP_BRIGHTNESS_PCT);
        mWpBrightnessSlider.setOnPercentChangedListener(percent -> {
            mOpt.wpBrightnessScale = percent / 100f;
            if (mWpBrightnessValueBadge != null) {
                mWpBrightnessValueBadge.setText(String.format(Locale.US, "%d%%", percent));
            }
            StylerPrefsBridge.setWallpaperBrightnessPct(IosStylerActivity.this, percent);
            syncTypographyAndSystemBars();
            if (mBackdropView != null) {
                mBackdropView.invalidate();
            }
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
            invalidateAllGlassSurfaces();
        });
        mGlassViews.add(mWpBrightnessSlider);
        card.addView(mWpBrightnessSlider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)));

        return card;
    }

    private View buildHomeNavigationCard() {
        LiquidGlassCardView card = createGlassCard(26f, 0.0f);
        int p = dp(18f);
        card.setPadding(p, dp(16f), p, dp(14f));

        card.addView(makePrimaryText("Home Screen & Navigation", 17f, true));
        TextView sub = makeSecondaryText(
                "Instant live synchronization with Pixel Launcher & SystemUI", 12.2f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        subLp.bottomMargin = dp(8f);
        card.addView(sub, subLp);

        mSearchPillSwitch = new LiquidGlassDropletSwitch(this, mOpt, mOpt.searchPillEnabled);
        mSearchPillSwitch.setOnCheckedChangeListener(checked -> {
            mOpt.searchPillEnabled = checked;
            StylerPrefsBridge.setSearchPillEnabled(IosStylerActivity.this, checked);
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
        });
        mGlassViews.add(mSearchPillSwitch);
        card.addView(buildSwitchRow(
                "\"Q Search\" Spotlight Capsule",
                "Floating refractive Liquid Glass search pill above Dock",
                mSearchPillSwitch));

        card.addView(makeHairlineDivider());

        mGesturePillSwitch = new LiquidGlassDropletSwitch(this, mOpt, mOpt.gesturePillEnabled);
        mGesturePillSwitch.setOnCheckedChangeListener(checked -> {
            mOpt.gesturePillEnabled = checked;
            StylerPrefsBridge.setGesturePillEnabled(IosStylerActivity.this, checked);
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
        });
        mGlassViews.add(mGesturePillSwitch);
        card.addView(buildSwitchRow(
                "Bottom Home Bar Gesture Line",
                "Show iOS bottom gesture indicator bar across apps",
                mGesturePillSwitch));

        return card;
    }

    private View buildWallpapersCard() {
        LiquidGlassCardView card = createGlassCard(26f, 0.0f);
        int p = dp(18f);
        card.setPadding(p, dp(16f), p, dp(18f));

        card.addView(makePrimaryText("iOS 26 Liquid Glass Wallpapers", 17f, true));
        TextView sub = makeSecondaryText(
                "1-tap apply to Home & Lock Screen • Tuned for refractive caustics", 12.2f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        subLp.bottomMargin = dp(14f);
        card.addView(sub, subLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        String[] names = {"Sky Light", "Sky Dark", "Aurora Cyan"};
        String[] tags = {"iOS 26 Default", "Dark Glass", "Iridescent"};

        for (int i = 0; i < 3; i++) {
            final int preset = i;
            boolean active = (preset == mActiveWallpaperPreset);
            LiquidGlassCardView item = createGlassCard(20f, active ? 0.30f : 0.06f);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(8f), dp(8f), dp(8f), dp(10f));

            ImageView swatchIv = new ImageView(this);
            swatchIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap swatchBmp = StylerWallpaperEngine.buildSwatchBitmap(this, preset, 150, 210);
            swatchIv.setImageBitmap(swatchBmp);
            swatchIv.setClipToOutline(true);
            swatchIv.setOutlineProvider(StylerSquircle.outlineProvider(dp(13f)));
            item.addView(swatchIv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(104f)));

            TextView nameTv = makePrimaryText(names[i], 12.6f, true);
            nameTv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nLp.topMargin = dp(8f);
            item.addView(nameTv, nLp);

            TextView tagTv = makeSecondaryText(tags[i], 10.6f);
            tagTv.setGravity(Gravity.CENTER);
            item.addView(tagTv);

            TextView statusTv = new TextView(this);
            statusTv.setGravity(Gravity.CENTER);
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.4f);
            statusTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            statusTv.setPadding(dp(10f), dp(3f), dp(10f), dp(3f));
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = dp(6f);
            item.addView(statusTv, stLp);

            mWpCards[i] = item;
            mWpStatusBadges[i] = statusTv;

            item.setOnClickListener(v -> applyWallpaperPreset(preset));

            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                colLp.leftMargin = dp(9f);
            }
            row.addView(item, colLp);
        }

        refreshWallpaperSelectionBadges();
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void applyWallpaperPreset(int preset) {
        StylerPrefsBridge.performTick(mWpCards[preset], true);
        mActiveWallpaperPreset = preset;
        mOpt.backdrop = StylerWallpaperEngine.getBackdropPair(this, preset);
        refreshWallpaperSelectionBadges();
        syncTypographyAndSystemBars();
        if (mBackdropView != null) {
            mBackdropView.invalidate();
        }
        if (mPreviewView != null) {
            mPreviewView.notifyOpticsChanged();
        }
        syncTypographyAndSystemBars();
        if (mBackdropView != null) {
            mBackdropView.invalidate();
        }
        invalidateAllGlassSurfaces();

        StylerWallpaperEngine.applyWallpaperAsync(this, preset, success -> {
            if (mPreviewView != null) {
                mPreviewView.notifyOpticsChanged();
            }
        });
    }

    private void refreshWallpaperSelectionBadges() {
        boolean dark = isSurfaceDarkForText();
        for (int i = 0; i < 3; i++) {
            boolean active = (i == mActiveWallpaperPreset);
            if (mWpCards[i] != null) {
                mWpCards[i].setTintBoost(active ? 0.32f : 0.05f);
            }
            TextView badge = mWpStatusBadges[i];
            if (badge != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(10f));
                if (active) {
                    badge.setText("ACTIVE");
                    badge.setTextColor(Color.WHITE);
                    bg.setColor(Color.argb(225, 24, 148, 255));
                } else {
                    badge.setText("Apply");
                    badge.setTextColor(dark
                            ? Color.argb(230, 224, 240, 255)
                            : Color.argb(220, 18, 44, 84));
                    bg.setColor(dark
                            ? Color.argb(72, 255, 255, 255)
                            : Color.argb(46, 16, 44, 88));
                }
                badge.setBackground(bg);
            }
        }
    }

    private View buildHardwareSmoothnessCard() {
        LiquidGlassCardView card = createGlassCard(26f, 0.0f);
        int p = dp(18f);
        card.setPadding(p, dp(16f), p, dp(14f));

        card.addView(makePrimaryText("90Hz ProMotion & OLED Hardware", 17f, true));
        TextView sub = makeSecondaryText(
                "Super AMOLED display cadence & hardware anti-flicker engine", 12.2f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2f);
        subLp.bottomMargin = dp(8f);
        card.addView(sub, subLp);

        boolean init90 = StylerPrefsBridge.isLock90HzEnabled(this);
        mLock90Switch = new LiquidGlassDropletSwitch(this, mOpt, init90);
        mLock90Switch.setOnCheckedChangeListener(checked ->
                StylerPrefsBridge.setLock90HzEnabled(IosStylerActivity.this, checked));
        mGlassViews.add(mLock90Switch);
        card.addView(buildSwitchRow(
                "Lock 90Hz Smoothness",
                "Pin minimum & peak refresh rate to 90Hz fluid motion",
                mLock90Switch));

        card.addView(makeHairlineDivider());

        boolean initDc = StylerPrefsBridge.isDcDimmingEnabled(this);
        mDcDimSwitch = new LiquidGlassDropletSwitch(this, mOpt, initDc);
        mDcDimSwitch.setOnCheckedChangeListener(checked ->
                StylerPrefsBridge.setDcDimmingEnabled(IosStylerActivity.this, checked));
        mGlassViews.add(mDcDimSwitch);
        card.addView(buildSwitchRow(
                "Hardware OLED DC Dimming",
                "Eliminate low-brightness PWM flicker via samurai_hw_daemon",
                mDcDimSwitch));

        return card;
    }

    private View buildSwitchRow(String titleText, String subText, LiquidGlassDropletSwitch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setPadding(0, dp(8f), 0, dp(8f));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(makePrimaryText(titleText, 14.6f, true));
        TextView sub = makeSecondaryText(subText, 11.8f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2f);
        textCol.addView(sub, sLp);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(10f);
        row.addView(textCol, colLp);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> toggle.setCheckedAnimated(!toggle.isChecked(), true));
        return row;
    }

    private LiquidGlassCardView createGlassCard(float radiusDp, float tintBoost) {
        LiquidGlassCardView card = new LiquidGlassCardView(this, mOpt, radiusDp, tintBoost);
        mGlassViews.add(card);
        return card;
    }

    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14f);
        return lp;
    }

    private TextView makePrimaryText(String content, float sp, boolean mediumBold) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.create(
                mediumBold ? "sans-serif-medium" : "sans-serif",
                sp >= 24f ? Typeface.BOLD : Typeface.NORMAL));
        mPrimaryTexts.add(tv);
        return tv;
    }

    private TextView makeSecondaryText(String content, float sp) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mSecondaryTexts.add(tv);
        return tv;
    }

    private View makeHairlineDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.9f * mDensity)));
        lp.topMargin = dp(3f);
        lp.bottomMargin = dp(3f);
        v.setLayoutParams(lp);
        mHairlineDividers.add(v);
        return v;
    }

    private boolean isSurfaceDarkForText() {
        return mOpt.isDarkBackdrop();
    }

    private void syncTypographyAndSystemBars() {
        boolean dark = isSurfaceDarkForText();
        int priColor = dark ? Color.rgb(248, 252, 255) : Color.rgb(10, 26, 56);
        int secColor = dark
                ? Color.argb(206, 206, 228, 255)
                : Color.argb(198, 28, 52, 92);
        int divColor = dark
                ? Color.argb(46, 255, 255, 255)
                : Color.argb(38, 12, 34, 72);

        for (TextView tv : mPrimaryTexts) {
            tv.setTextColor(priColor);
            tv.setShadowLayer(
                    1.6f * mDensity, 0f, 0.6f * mDensity,
                    dark ? Color.argb(95, 0, 8, 24) : Color.argb(115, 245, 251, 255));
        }
        for (TextView tv : mSecondaryTexts) {
            tv.setTextColor(secColor);
            tv.setShadowLayer(
                    1.4f * mDensity, 0f, 0.5f * mDensity,
                    dark ? Color.argb(80, 0, 8, 24) : Color.argb(120, 245, 251, 255));
        }
        for (View d : mHairlineDividers) {
            d.setBackgroundColor(divColor);
        }
        refreshWallpaperSelectionBadges();

        Window w = getWindow();
        if (w != null) {
            WindowInsetsController wic = w.getInsetsController();
            if (wic != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                wic.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
    }

    private void invalidateAllGlassSurfaces() {
        for (int i = 0, n = mGlassViews.size(); i < n; i++) {
            mGlassViews.get(i).invalidate();
        }
    }

    private void registerObservers() {
        try {
            String[] secureKeys = {
                    StylerPrefsBridge.SECURE_ICON_MODE,
                    StylerPrefsBridge.SECURE_GLASS_MODE,
                    StylerPrefsBridge.SECURE_REFRACTION_PCT,
                    StylerPrefsBridge.SECURE_FROST_PCT,
                    StylerPrefsBridge.SECURE_WP_BRIGHTNESS_PCT,
                    StylerPrefsBridge.SECURE_SEARCH_PILL,
                    StylerPrefsBridge.SECURE_GESTURE_PILL,
                    StylerPrefsBridge.SECURE_DC_DIMMING
            };
            for (String k : secureKeys) {
                getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(k), false, mSettingsObserver);
            }
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(StylerPrefsBridge.GLOBAL_GLASS_MODE),
                    false, mSettingsObserver);
        } catch (Throwable ignored) {
        }
    }

    private void syncControlsFromSystem() {
        int im = StylerPrefsBridge.getIconMode(this);
        int gm = StylerPrefsBridge.getGlassMode(this);
        int rp = StylerPrefsBridge.getRefractionPct(this);
        int fp = StylerPrefsBridge.getFrostPct(this);
        int wb = StylerPrefsBridge.getWallpaperBrightnessPct(this);
        boolean sp = StylerPrefsBridge.isSearchPillEnabled(this);
        boolean gp = StylerPrefsBridge.isGesturePillEnabled(this);

        mOpt.iconMode = im;
        mOpt.glassMode = gm;
        mOpt.refractScale = rp / 100f;
        mOpt.frostScale = fp / 100f;
        mOpt.wpBrightnessScale = wb / 100f;
        mOpt.searchPillEnabled = sp;
        mOpt.gesturePillEnabled = gp;

        if (mIconSegment != null) mIconSegment.setSelectedValue(im, false);
        if (mGlassSegment != null) mGlassSegment.setSelectedValue(gm, false);
        if (mRefractSlider != null && mRefractSlider.getPercent() != rp) {
            mRefractSlider.setPercent(rp, false);
        }
        if (mFrostSlider != null && mFrostSlider.getPercent() != fp) {
            mFrostSlider.setPercent(fp, false);
        }
        if (mWpBrightnessSlider != null && mWpBrightnessSlider.getPercent() != wb) {
            mWpBrightnessSlider.setPercent(wb, false);
        }
        if (mRefractValueBadge != null) {
            mRefractValueBadge.setText(String.format(Locale.US, "%d%%", rp));
        }
        if (mFrostValueBadge != null) {
            mFrostValueBadge.setText(String.format(Locale.US, "%d%%", fp));
        }
        if (mWpBrightnessValueBadge != null) {
            mWpBrightnessValueBadge.setText(String.format(Locale.US, "%d%%", wb));
        }
        if (mSearchPillSwitch != null) mSearchPillSwitch.setCheckedAnimated(sp, false);
        if (mGesturePillSwitch != null) mGesturePillSwitch.setCheckedAnimated(gp, false);
        if (mLock90Switch != null) {
            mLock90Switch.setCheckedAnimated(StylerPrefsBridge.isLock90HzEnabled(this), false);
        }
        if (mDcDimSwitch != null) {
            mDcDimSwitch.setCheckedAnimated(StylerPrefsBridge.isDcDimmingEnabled(this), false);
        }
        if (mPreviewView != null) {
            mPreviewView.notifyOpticsChanged();
        }
        syncTypographyAndSystemBars();
        if (mBackdropView != null) {
            mBackdropView.invalidate();
        }
        invalidateAllGlassSurfaces();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncControlsFromSystem();
        mResumed = true;
        mStartNanos = 0L;
        Choreographer.getInstance().removeFrameCallback(mAuroraFrameCallback);
        Choreographer.getInstance().postFrameCallback(mAuroraFrameCallback);
    }

    @Override
    protected void onPause() {
        mResumed = false;
        Choreographer.getInstance().removeFrameCallback(mAuroraFrameCallback);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            getContentResolver().unregisterContentObserver(mSettingsObserver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(v * mDensity);
    }

    /**
     * Full-bleed live iOS 26 backdrop canvas behind the `ScrollView`.
     */
    private final class LiveBackdropView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect mSrc = new Rect();
        private final RectF mDst = new RectF();
        private final int[] mLoc = new int[2];

        LiveBackdropView() {
            super(IosStylerActivity.this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            getLocationOnScreen(mLoc);
            mOpt.screenW = w;
            mOpt.screenH = h;
            mOpt.bgScreenX = mLoc[0];
            mOpt.bgScreenY = mLoc[1];

            Bitmap bmp = (mOpt.backdrop != null) ? mOpt.backdrop.displayBitmap : null;
            if (bmp != null && !bmp.isRecycled()) {
                mSrc.set(0, 0, bmp.getWidth(), bmp.getHeight());
                float dx = (float) Math.sin(mOpt.phase * 0.7f) * 6f * mDensity;
                float dy = (float) Math.cos(mOpt.phase * 0.5f) * 5f * mDensity;
                float bleed = 10f * mDensity;
                mDst.set(-bleed + dx, -bleed + dy, w + bleed + dx, h + bleed + dy);
                canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
            } else {
                canvas.drawColor(Color.rgb(18, 86, 162));
            }
            int wpPct = Math.round(mOpt.wpBrightnessScale * 100f);
            int dimAlpha = StylerPrefsBridge.computeWallpaperDimAlpha(wpPct);
            if (dimAlpha > 0) {
                canvas.drawColor(dimAlpha << 24);
            }
        }
    }
}
