package com.realme.samurai.clock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiGlassSearchBar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Tab 0 — iOS 26 World Clock (`WorldClockTabView`) with live timezone offsets, Day/Night glass
 * badges, and `TimeZone.getAvailableIDs()` picker sheet.
 */
public final class WorldClockTabView extends FrameLayout {

    private static final class CityRowBinding {
        final ClockDataStore.WorldCity city;
        final SamuraiGlassCard card;
        final TextView offsetTv;
        final TextView nameTv;
        final TextView timeTv;
        final TextView dayNightBadge;
        final TextView removeBtn;

        CityRowBinding(
                ClockDataStore.WorldCity city,
                SamuraiGlassCard card,
                TextView offsetTv,
                TextView nameTv,
                TextView timeTv,
                TextView dayNightBadge,
                TextView removeBtn) {
            this.city = city;
            this.card = card;
            this.offsetTv = offsetTv;
            this.nameTv = nameTv;
            this.timeTv = timeTv;
            this.dayNightBadge = dayNightBadge;
            this.removeBtn = removeBtn;
        }
    }

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final List<ClockDataStore.WorldCity> mCities = new ArrayList<>();
    private final List<CityRowBinding> mBindings = new ArrayList<>();
    private final List<SamuraiGlassCard> mAuxCards = new ArrayList<>();

    private final LinearLayout mMainContent;
    private final LinearLayout mCitiesListCol;
    private final TextView mHeaderTitle;
    private final TextView mEditBtnTv;
    private final TextView mPlusBtnTv;
    private boolean mEditMode = false;

    // Add City Picker Overlay
    private final FrameLayout mPickerOverlay;
    private final SamuraiGlassCard mPickerSheetCard;
    private final SamuraiGlassSearchBar mPickerSearch;
    private final LinearLayout mPickerResultsCol;
    private final TextView mPickerTitleTv;
    private final List<ClockDataStore.WorldCity> mAvailableCatalog = new ArrayList<>();

    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private boolean mActive = false;
    private final Runnable mSecondTicker = new Runnable() {
        @Override
        public void run() {
            if (!mActive) return;
            refreshLiveTimes();
            mTickHandler.postDelayed(this, 1000L);
        }
    };

    public WorldClockTabView(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = prefs;
        float d = context.getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            for (CityRowBinding b : mBindings) b.card.invalidate();
            for (SamuraiGlassCard c : mAuxCards) c.invalidate();
        });
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mMainContent = new LinearLayout(context);
        mMainContent.setOrientation(LinearLayout.VERTICAL);
        int side = Math.round(16f * d);
        mMainContent.setPadding(side, Math.round(52f * d), side, Math.round(130f * d));
        scroll.addView(mMainContent, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Top Bar: Edit Pill | Spacer | `+` Orb
        LinearLayout topActions = new LinearLayout(context);
        topActions.setOrientation(LinearLayout.HORIZONTAL);
        topActions.setGravity(Gravity.CENTER_VERTICAL);

        SamuraiGlassCard editOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(19f)
                .setInteractivePressScale(true);
        editOrb.setPadding(Math.round(15f * d), Math.round(7f * d), Math.round(15f * d), Math.round(7f * d));
        mEditBtnTv = new TextView(context);
        mEditBtnTv.setText("Edit");
        mEditBtnTv.setTextColor(0xFFFF9F0A);
        mEditBtnTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        mEditBtnTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        editOrb.addView(mEditBtnTv);
        editOrb.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            mEditMode = !mEditMode;
            mEditBtnTv.setText(mEditMode ? "Done" : "Edit");
            for (CityRowBinding b : mBindings) {
                b.removeBtn.setVisibility(mEditMode ? View.VISIBLE : View.GONE);
            }
        });
        mAuxCards.add(editOrb);
        topActions.addView(editOrb);

        topActions.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        SamuraiGlassCard plusOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(20f)
                .setInteractivePressScale(true);
        plusOrb.setGravity(Gravity.CENTER);
        int orbPx = Math.round(38f * d);
        mPlusBtnTv = new TextView(context);
        mPlusBtnTv.setText("+");
        mPlusBtnTv.setTextColor(0xFFFF9F0A);
        mPlusBtnTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        mPlusBtnTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mPlusBtnTv.setGravity(Gravity.CENTER);
        plusOrb.addView(mPlusBtnTv, new LinearLayout.LayoutParams(orbPx, orbPx));
        plusOrb.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            openAddCitySheet();
        });
        mAuxCards.add(plusOrb);
        topActions.addView(plusOrb);
        mMainContent.addView(topActions);

        mHeaderTitle = new TextView(context);
        mHeaderTitle.setText("World Clock");
        mHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 33f);
        mHeaderTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mHeaderTitle.setLetterSpacing(-0.02f);
        mHeaderTitle.setPadding(Math.round(4f * d), Math.round(8f * d), 0, Math.round(12f * d));
        mMainContent.addView(mHeaderTitle);

        mCitiesListCol = new LinearLayout(context);
        mCitiesListCol.setOrientation(LinearLayout.VERTICAL);
        mMainContent.addView(mCitiesListCol);

        // Build Add City Overlay Sheet
        mPickerOverlay = new FrameLayout(context);
        mPickerOverlay.setBackgroundColor(0x88000000);
        mPickerOverlay.setClickable(true);
        mPickerOverlay.setVisibility(View.GONE);

        mPickerSheetCard = new SamuraiGlassCard(context, prefs).setCornerRadiusDp(28f);
        int sp = Math.round(18f * d);
        mPickerSheetCard.setPadding(sp, sp, sp, sp);
        mAuxCards.add(mPickerSheetCard);

        LinearLayout pickerHdr = new LinearLayout(context);
        pickerHdr.setOrientation(LinearLayout.HORIZONTAL);
        pickerHdr.setGravity(Gravity.CENTER_VERTICAL);

        mPickerTitleTv = new TextView(context);
        mPickerTitleTv.setText("Choose a City");
        mPickerTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f);
        mPickerTitleTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pickerHdr.addView(mPickerTitleTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancelTv = new TextView(context);
        cancelTv.setText("Cancel");
        cancelTv.setTextColor(0xFFFF9F0A);
        cancelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        cancelTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancelTv.setOnClickListener(v -> closeAddCitySheet());
        pickerHdr.addView(cancelTv);
        mPickerSheetCard.addView(pickerHdr);

        mPickerSearch = new SamuraiGlassSearchBar(
                context, prefs, "Search City or Timezone", this::populatePickerResults);
        mAuxCards.add(mPickerSearch);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = Math.round(12f * d);
        searchLp.bottomMargin = Math.round(10f * d);
        mPickerSheetCard.addView(mPickerSearch, searchLp);

        ScrollView pickerScroll = new ScrollView(context);
        pickerScroll.setVerticalScrollBarEnabled(false);
        mPickerResultsCol = new LinearLayout(context);
        mPickerResultsCol.setOrientation(LinearLayout.VERTICAL);
        pickerScroll.addView(mPickerResultsCol);
        mPickerSheetCard.addView(pickerScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(340f * d)));

        LayoutParams sheetLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        sheetLp.leftMargin = side;
        sheetLp.rightMargin = side;
        mPickerOverlay.addView(mPickerSheetCard, sheetLp);
        addView(mPickerOverlay, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        buildTimeZoneCatalog();
        mCities.addAll(ClockDataStore.loadWorldCities(context));
        rebuildCityCards();
        applyTheme(prefs.darkTheme);
    }

    public void openAddCitySheet() {
        populatePickerResults("");
        mPickerSearch.getEditText().setText("");
        mPickerOverlay.setVisibility(View.VISIBLE);
    }

    public boolean closeOverlayIfOpen() {
        if (mPickerOverlay.getVisibility() == View.VISIBLE) {
            closeAddCitySheet();
            return true;
        }
        return false;
    }

    private void closeAddCitySheet() {
        mPickerOverlay.setVisibility(View.GONE);
    }

    public void setWindowInsetsPadding(int topInset, int bottomInset) {
        float d = getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);
        mMainContent.setPadding(
                side,
                topInset + Math.round(14f * d),
                side,
                bottomInset + Math.round(106f * d));
    }

    public void setTabVisible(boolean visible) {
        mActive = visible;
        mTickHandler.removeCallbacks(mSecondTicker);
        if (visible) {
            refreshLiveTimes();
            mTickHandler.postDelayed(mSecondTicker, 1000L);
        }
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        for (CityRowBinding b : mBindings) b.card.updatePrefs(prefs);
        for (SamuraiGlassCard c : mAuxCards) c.updatePrefs(prefs);
        applyTheme(prefs.darkTheme);
        refreshLiveTimes();
    }

    private void applyTheme(boolean dark) {
        int pri = dark ? 0xFFF5F5FA : 0xFF14161F;
        int sec = dark ? 0xA6EBEBF5 : 0x993C3C43;
        mHeaderTitle.setTextColor(pri);
        mPickerTitleTv.setTextColor(pri);
        for (CityRowBinding b : mBindings) {
            b.nameTv.setTextColor(pri);
            b.timeTv.setTextColor(pri);
            b.offsetTv.setTextColor(sec);
        }
    }

    private void rebuildCityCards() {
        mCitiesListCol.removeAllViews();
        mBindings.clear();
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        for (int i = 0; i < mCities.size(); i++) {
            final ClockDataStore.WorldCity city = mCities.get(i);
            SamuraiGlassCard card = new SamuraiGlassCard(ctx, mPrefs)
                    .setCornerRadiusDp(24f)
                    .setInteractivePressScale(true);
            int pH = Math.round(18f * d);
            int pV = Math.round(15f * d);
            card.setPadding(pH, pV, pH, pV);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView removeTv = new TextView(ctx);
            removeTv.setText("\u2212");
            removeTv.setTextColor(0xFFFFFFFF);
            removeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
            removeTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            removeTv.setGravity(Gravity.CENTER);
            GradientDrawable redDot = new GradientDrawable();
            redDot.setShape(GradientDrawable.OVAL);
            redDot.setColor(0xFFFF453A);
            removeTv.setBackground(redDot);
            int dotPx = Math.round(24f * d);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotPx, dotPx);
            dotLp.rightMargin = Math.round(12f * d);
            removeTv.setVisibility(mEditMode ? View.VISIBLE : View.GONE);
            removeTv.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(ctx, v);
                mCities.remove(city);
                ClockDataStore.saveWorldCities(ctx, mCities);
                rebuildCityCards();
                applyTheme(mPrefs.darkTheme);
                refreshLiveTimes();
            });
            row.addView(removeTv, dotLp);

            LinearLayout leftCol = new LinearLayout(ctx);
            leftCol.setOrientation(LinearLayout.VERTICAL);

            LinearLayout metaRow = new LinearLayout(ctx);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView offsetTv = new TextView(ctx);
            offsetTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            metaRow.addView(offsetTv);

            TextView badgeTv = new TextView(ctx);
            badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
            badgeTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badgeTv.setPadding(
                    Math.round(8f * d), Math.round(2.5f * d),
                    Math.round(8f * d), Math.round(2.5f * d));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin = Math.round(8f * d);
            metaRow.addView(badgeTv, bLp);

            leftCol.addView(metaRow);

            TextView nameTv = new TextView(ctx);
            nameTv.setText(city.cityName);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21f);
            nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            nameTv.setPadding(0, Math.round(2f * d), 0, 0);
            leftCol.addView(nameTv);

            row.addView(leftCol, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView timeTv = new TextView(ctx);
            timeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 36f);
            timeTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            timeTv.setFontFeatureSettings("tnum");
            row.addView(timeTv);

            card.addView(row);
            card.setOnLongClickListener(v -> {
                SamuraiGlassPrefs.hapticHeavySpring(ctx, v);
                mEditMode = !mEditMode;
                mEditBtnTv.setText(mEditMode ? "Done" : "Edit");
                for (CityRowBinding b : mBindings) {
                    b.removeBtn.setVisibility(mEditMode ? View.VISIBLE : View.GONE);
                }
                return true;
            });

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                cardLp.topMargin = Math.round(11f * d);
            }
            mCitiesListCol.addView(card, cardLp);
            mBindings.add(new CityRowBinding(
                    city, card, offsetTv, nameTv, timeTv, badgeTv, removeTv));
        }
    }

    private void refreshLiveTimes() {
        long nowMs = System.currentTimeMillis();
        TimeZone localTz = TimeZone.getDefault();
        Calendar localCal = Calendar.getInstance(localTz);
        localCal.setTimeInMillis(nowMs);
        int localOffsetMin = localTz.getOffset(nowMs) / 60_000;
        int localDayKey = localCal.get(Calendar.YEAR) * 400 + localCal.get(Calendar.DAY_OF_YEAR);

        float d = getResources().getDisplayMetrics().density;

        for (CityRowBinding b : mBindings) {
            TimeZone tz = TimeZone.getTimeZone(b.city.tzId);
            Calendar cal = Calendar.getInstance(tz);
            cal.setTimeInMillis(nowMs);

            int h24 = cal.get(Calendar.HOUR_OF_DAY);
            int min = cal.get(Calendar.MINUTE);
            b.timeTv.setText(String.format(Locale.US, "%02d:%02d", h24, min));

            int cityDayKey = cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR);
            String dayRel = (cityDayKey > localDayKey)
                    ? "Tomorrow"
                    : ((cityDayKey < localDayKey) ? "Yesterday" : "Today");

            int diffMin = (tz.getOffset(nowMs) / 60_000) - localOffsetMin;
            String diffStr;
            if (diffMin == 0) {
                diffStr = "+0HRS";
            } else {
                String sign = diffMin > 0 ? "+" : "-";
                int abs = Math.abs(diffMin);
                int hrs = abs / 60;
                int remM = abs % 60;
                diffStr = (remM == 0)
                        ? (sign + hrs + "HRS")
                        : String.format(Locale.US, "%s%d:%02dHRS", sign, hrs, remM);
            }
            b.offsetTv.setText(dayRel + ", " + diffStr);

            boolean isDay = (h24 >= 6 && h24 < 18);
            b.dayNightBadge.setText(isDay ? "\u2600 Day" : "\u263E Night");
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(999f);
            int accent = isDay ? 0xFFFF9F0A : 0xFF5E5CE6;
            pill.setColor((accent & 0x00FFFFFF) | (mPrefs.darkTheme ? 0x38000000 : 0x24000000));
            pill.setStroke(Math.max(1, Math.round(0.8f * d)), (accent & 0x00FFFFFF) | 0x66000000);
            b.dayNightBadge.setBackground(pill);
            b.dayNightBadge.setTextColor(isDay
                    ? (mPrefs.darkTheme ? 0xFFFFD60A : 0xFFB25E00)
                    : (mPrefs.darkTheme ? 0xFF9E9CFF : 0xFF4240B8));
        }
    }

    private void buildTimeZoneCatalog() {
        Map<String, ClockDataStore.WorldCity> map = new LinkedHashMap<>();
        String[][] featured = {
                { "New Delhi (IST)", "Asia/Kolkata" },
                { "Mumbai (IST)", "Asia/Kolkata" },
                { "Bengaluru (IST)", "Asia/Kolkata" },
                { "Hyderabad (IST)", "Asia/Kolkata" },
                { "Cupertino", "America/Los_Angeles" },
                { "San Francisco", "America/Los_Angeles" },
                { "New York", "America/New_York" },
                { "London", "Europe/London" },
                { "Paris", "Europe/Paris" },
                { "Berlin", "Europe/Berlin" },
                { "Zurich", "Europe/Zurich" },
                { "Dubai", "Asia/Dubai" },
                { "Singapore", "Asia/Singapore" },
                { "Tokyo", "Asia/Tokyo" },
                { "Seoul", "Asia/Seoul" },
                { "Hong Kong", "Asia/Hong_Kong" },
                { "Sydney", "Australia/Sydney" },
                { "Toronto", "America/Toronto" },
                { "Sao Paulo", "America/Sao_Paulo" }
        };
        for (String[] f : featured) {
            map.put(f[0], new ClockDataStore.WorldCity(f[0], f[1]));
        }

        String[] ids = TimeZone.getAvailableIDs();
        List<String> sortedIds = new ArrayList<>();
        Collections.addAll(sortedIds, ids);
        Collections.sort(sortedIds);

        for (String id : sortedIds) {
            if (!id.contains("/") || id.startsWith("Etc/") || id.startsWith("SystemV/")) {
                continue;
            }
            int slash = id.lastIndexOf('/');
            String pretty = id.substring(slash + 1).replace('_', ' ');
            if (!map.containsKey(pretty)) {
                map.put(pretty, new ClockDataStore.WorldCity(pretty, id));
            }
        }
        mAvailableCatalog.addAll(map.values());
    }

    private void populatePickerResults(String query) {
        mPickerResultsCol.removeAllViews();
        String q = (query != null) ? query.trim().toLowerCase(Locale.US) : "";
        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int pri = mPrefs.darkTheme ? 0xFFF5F5FA : 0xFF14161F;
        int sec = mPrefs.darkTheme ? 0x99EBEBF5 : 0x8A3C3C43;

        int added = 0;
        for (final ClockDataStore.WorldCity candidate : mAvailableCatalog) {
            if (!q.isEmpty()
                    && !candidate.cityName.toLowerCase(Locale.US).contains(q)
                    && !candidate.tzId.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            int padV = Math.round(10f * d);
            row.setPadding(Math.round(8f * d), padV, Math.round(8f * d), padV);

            TextView cTv = new TextView(ctx);
            cTv.setText(candidate.cityName);
            cTv.setTextColor(pri);
            cTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            cTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(cTv);

            TextView tzTv = new TextView(ctx);
            tzTv.setText(candidate.tzId);
            tzTv.setTextColor(sec);
            tzTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f);
            row.addView(tzTv);

            row.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(ctx, v);
                mCities.add(new ClockDataStore.WorldCity(candidate.cityName, candidate.tzId));
                ClockDataStore.saveWorldCities(ctx, mCities);
                rebuildCityCards();
                applyTheme(mPrefs.darkTheme);
                refreshLiveTimes();
                closeAddCitySheet();
            });
            mPickerResultsCol.addView(row);
            added++;
            if (added >= 60) break; // keep scroll butter-smooth
        }
    }
}
