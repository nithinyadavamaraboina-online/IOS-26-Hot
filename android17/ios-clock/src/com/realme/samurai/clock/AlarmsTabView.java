package com.realme.samurai.clock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;
import com.realme.samurai.glass.SamuraiGlassCard;
import com.realme.samurai.glass.SamuraiGlassPrefs;
import com.realme.samurai.glass.SamuraiGlassSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tab 1 — iOS 26 Alarms (`AlarmsTabView`) with JSON persistence, `SamuraiGlassSwitch`, wheel/stepper
 * creation sheet, repeat day pills, and real `AlarmManager.setAlarmClock(...)` scheduling.
 */
public final class AlarmsTabView extends FrameLayout {

    private SamuraiGlassPrefs.Snapshot mPrefs;
    private final List<ClockDataStore.AlarmItem> mAlarms = new ArrayList<>();
    private final List<SamuraiGlassCard> mRenderedCards = new ArrayList<>();

    private final LinearLayout mMainContent;
    private final LinearLayout mAlarmsListCol;
    private final TextView mHeaderTitle;

    // Create / Edit Alarm Sheet
    private final FrameLayout mEditorOverlay;
    private final SamuraiGlassCard mEditorCard;
    private final TextView mEditorTitleTv;
    private final TextView mHourValTv;
    private final TextView mMinValTv;
    private final EditText mLabelInput;
    private final TextView[] mDayPills = new TextView[7];
    private final SamuraiGlassSwitch mSnoozeSwitch;
    private final TextView mDeleteBtnTv;

    private int mEditingId = -1;
    private int mDraftHour = 7;
    private int mDraftMin = 0;
    private int mDraftDaysMask = 0;

    public AlarmsTabView(Context context, SamuraiGlassPrefs.Snapshot prefs) {
        super(context);
        mPrefs = prefs;
        float d = context.getResources().getDisplayMetrics().density;
        int side = Math.round(16f * d);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            for (SamuraiGlassCard c : mRenderedCards) c.invalidate();
        });
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mMainContent = new LinearLayout(context);
        mMainContent.setOrientation(LinearLayout.VERTICAL);
        mMainContent.setPadding(side, Math.round(52f * d), side, Math.round(130f * d));
        scroll.addView(mMainContent, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Top Bar: Spacer + `+` Orb
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));

        SamuraiGlassCard plusOrb = new SamuraiGlassCard(context, prefs)
                .setCornerRadiusDp(20f)
                .setInteractivePressScale(true);
        plusOrb.setGravity(Gravity.CENTER);
        int orbPx = Math.round(38f * d);
        TextView plusTv = new TextView(context);
        plusTv.setText("+");
        plusTv.setTextColor(0xFFFF9F0A);
        plusTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f);
        plusTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        plusTv.setGravity(Gravity.CENTER);
        plusOrb.addView(plusTv, new LinearLayout.LayoutParams(orbPx, orbPx));
        plusOrb.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            openCreateAlarmSheet();
        });
        mRenderedCards.add(plusOrb);
        topRow.addView(plusOrb);
        mMainContent.addView(topRow);

        mHeaderTitle = new TextView(context);
        mHeaderTitle.setText("Alarms");
        mHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 33f);
        mHeaderTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mHeaderTitle.setLetterSpacing(-0.02f);
        mHeaderTitle.setPadding(Math.round(4f * d), Math.round(8f * d), 0, Math.round(12f * d));
        mMainContent.addView(mHeaderTitle);

        mAlarmsListCol = new LinearLayout(context);
        mAlarmsListCol.setOrientation(LinearLayout.VERTICAL);
        mMainContent.addView(mAlarmsListCol);

        // Build Create / Edit Alarm Sheet Overlay
        mEditorOverlay = new FrameLayout(context);
        mEditorOverlay.setBackgroundColor(0x88000000);
        mEditorOverlay.setClickable(true);
        mEditorOverlay.setVisibility(View.GONE);

        mEditorCard = new SamuraiGlassCard(context, prefs).setCornerRadiusDp(28f);
        int ep = Math.round(20f * d);
        mEditorCard.setPadding(ep, ep, ep, ep);
        mRenderedCards.add(mEditorCard);

        // Sheet Header: Cancel | Title | Save
        LinearLayout sheetHdr = new LinearLayout(context);
        sheetHdr.setOrientation(LinearLayout.HORIZONTAL);
        sheetHdr.setGravity(Gravity.CENTER_VERTICAL);

        TextView cancelTv = new TextView(context);
        cancelTv.setText("Cancel");
        cancelTv.setTextColor(0xFFFF9F0A);
        cancelTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        cancelTv.setOnClickListener(v -> mEditorOverlay.setVisibility(View.GONE));
        sheetHdr.addView(cancelTv);

        mEditorTitleTv = new TextView(context);
        mEditorTitleTv.setText("Add Alarm");
        mEditorTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17.5f);
        mEditorTitleTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        mEditorTitleTv.setGravity(Gravity.CENTER);
        sheetHdr.addView(mEditorTitleTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView saveTv = new TextView(context);
        saveTv.setText("Save");
        saveTv.setTextColor(0xFFFF9F0A);
        saveTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
        saveTv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        saveTv.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            commitEditorSave();
        });
        sheetHdr.addView(saveTv);
        mEditorCard.addView(sheetHdr);

        // Hour : Minute Tactile Wheel Stepper Row
        LinearLayout wheelRow = new LinearLayout(context);
        wheelRow.setOrientation(LinearLayout.HORIZONTAL);
        wheelRow.setGravity(Gravity.CENTER);
        wheelRow.setPadding(0, Math.round(18f * d), 0, Math.round(16f * d));

        mHourValTv = new TextView(context);
        wheelRow.addView(buildStepperColumn(context, "HOUR", mHourValTv,
                () -> stepHour(1), () -> stepHour(-1), d));

        TextView colon = new TextView(context);
        colon.setText(":");
        colon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 42f);
        colon.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        colon.setTextColor(0xFFFF9F0A);
        colon.setPadding(Math.round(14f * d), 0, Math.round(14f * d), Math.round(6f * d));
        wheelRow.addView(colon);

        mMinValTv = new TextView(context);
        wheelRow.addView(buildStepperColumn(context, "MIN", mMinValTv,
                () -> stepMinute(1), () -> stepMinute(-1), d));

        mEditorCard.addView(wheelRow);

        // Repeat Days Row: S M T W T F S
        String[] dayChars = { "S", "M", "T", "W", "T", "F", "S" };
        LinearLayout daysRow = new LinearLayout(context);
        daysRow.setOrientation(LinearLayout.HORIZONTAL);
        daysRow.setGravity(Gravity.CENTER);
        daysRow.setPadding(0, Math.round(4f * d), 0, Math.round(14f * d));
        for (int i = 0; i < 7; i++) {
            final int dayBit = i;
            TextView pill = new TextView(context);
            pill.setText(dayChars[i]);
            pill.setGravity(Gravity.CENTER);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            int sz = Math.round(35f * d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) dlp.leftMargin = Math.round(6f * d);
            pill.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticTick(v);
                mDraftDaysMask ^= (1 << dayBit);
                refreshDayPillStyles();
            });
            mDayPills[i] = pill;
            daysRow.addView(pill, dlp);
        }
        mEditorCard.addView(daysRow);

        // Label Input
        mLabelInput = new EditText(context);
        mLabelInput.setHint("Alarm Label (e.g. Wake Up)");
        mLabelInput.setSingleLine(true);
        mLabelInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(14f * d);
        inputBg.setColor(0x28787880);
        mLabelInput.setBackground(inputBg);
        int ip = Math.round(12f * d);
        mLabelInput.setPadding(ip, ip, ip, ip);
        mEditorCard.addView(mLabelInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Snooze 9 min Switch Row
        LinearLayout snoozeRow = new LinearLayout(context);
        snoozeRow.setOrientation(LinearLayout.HORIZONTAL);
        snoozeRow.setGravity(Gravity.CENTER_VERTICAL);
        snoozeRow.setPadding(Math.round(4f * d), Math.round(14f * d), Math.round(4f * d), Math.round(4f * d));
        TextView snLbl = new TextView(context);
        snLbl.setText("Snooze (9 min)");
        snLbl.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        snLbl.setTag("pri");
        snoozeRow.addView(snLbl, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mSnoozeSwitch = new SamuraiGlassSwitch(context, true, prefs.darkTheme);
        snoozeRow.addView(mSnoozeSwitch);
        mEditorCard.addView(snoozeRow);

        // Delete Alarm Button (when editing existing)
        mDeleteBtnTv = new TextView(context);
        mDeleteBtnTv.setText("Delete Alarm");
        mDeleteBtnTv.setTextColor(0xFFFF453A);
        mDeleteBtnTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        mDeleteBtnTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mDeleteBtnTv.setGravity(Gravity.CENTER);
        mDeleteBtnTv.setPadding(0, Math.round(16f * d), 0, Math.round(4f * d));
        mDeleteBtnTv.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticClick(context, v);
            deleteEditingAlarm();
        });
        mEditorCard.addView(mDeleteBtnTv);

        LayoutParams edLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        edLp.leftMargin = side;
        edLp.rightMargin = side;
        mEditorOverlay.addView(mEditorCard, edLp);
        addView(mEditorOverlay, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        reloadAndRenderAlarms();
        applyTheme(prefs.darkTheme);
    }

    public void openCreateAlarmSheet() {
        openCreateAlarmSheetWithPreset(-1, -1, null);
    }

    public void openCreateAlarmSheetWithPreset(int hour24, int minute, String presetLabel) {
        mEditingId = -1;
        mDraftHour = (hour24 >= 0 && hour24 <= 23) ? hour24 : 7;
        mDraftMin = (minute >= 0 && minute <= 59) ? minute : 30;
        mDraftDaysMask = 0;
        mEditorTitleTv.setText("Add Alarm");
        mLabelInput.setText(presetLabel != null ? presetLabel : "Alarm");
        mSnoozeSwitch.setChecked(true, false);
        mDeleteBtnTv.setVisibility(View.GONE);
        refreshDraftTimeTexts();
        refreshDayPillStyles();
        mEditorOverlay.setVisibility(View.VISIBLE);
    }

    private void openEditAlarmSheet(ClockDataStore.AlarmItem alarm) {
        mEditingId = alarm.id;
        mDraftHour = alarm.hour24;
        mDraftMin = alarm.minute;
        mDraftDaysMask = alarm.daysMask;
        mEditorTitleTv.setText("Edit Alarm");
        mLabelInput.setText(alarm.label);
        mSnoozeSwitch.setChecked(alarm.snoozeEnabled, false);
        mDeleteBtnTv.setVisibility(View.VISIBLE);
        refreshDraftTimeTexts();
        refreshDayPillStyles();
        mEditorOverlay.setVisibility(View.VISIBLE);
    }

    public boolean closeOverlayIfOpen() {
        if (mEditorOverlay.getVisibility() == View.VISIBLE) {
            mEditorOverlay.setVisibility(View.GONE);
            return true;
        }
        return false;
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

    public void reloadAndRenderAlarms() {
        mAlarms.clear();
        mAlarms.addAll(ClockDataStore.loadAlarms(getContext()));
        mAlarmsListCol.removeAllViews();

        Context ctx = getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int pri = mPrefs.darkTheme ? 0xFFF5F5FA : 0xFF14161F;
        int sec = mPrefs.darkTheme ? 0x99EBEBF5 : 0x8A3C3C43;

        for (int i = 0; i < mAlarms.size(); i++) {
            final ClockDataStore.AlarmItem item = mAlarms.get(i);
            SamuraiGlassCard card = new SamuraiGlassCard(ctx, mPrefs)
                    .setCornerRadiusDp(24f)
                    .setInteractivePressScale(true);
            int pH = Math.round(18f * d);
            int pV = Math.round(15f * d);
            card.setPadding(pH, pV, pH, pV);
            mRenderedCards.add(card);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout left = new LinearLayout(ctx);
            left.setOrientation(LinearLayout.VERTICAL);

            final TextView timeTv = new TextView(ctx);
            timeTv.setText(String.format(Locale.US, "%02d:%02d", item.hour24, item.minute));
            timeTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40f);
            timeTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            timeTv.setFontFeatureSettings("tnum");
            timeTv.setTextColor(item.enabled ? pri : sec);
            left.addView(timeTv);

            TextView metaTv = new TextView(ctx);
            metaTv.setText(item.label + " \u2022 " + item.repeatSummary());
            metaTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            metaTv.setTextColor(sec);
            left.addView(metaTv);

            row.addView(left, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            SamuraiGlassSwitch sw = new SamuraiGlassSwitch(ctx, item.enabled, mPrefs.darkTheme);
            sw.setOnCheckedChangeListener(checked -> {
                item.enabled = checked;
                ClockDataStore.saveAlarms(ctx, mAlarms);
                timeTv.setTextColor(checked
                        ? (mPrefs.darkTheme ? 0xFFF5F5FA : 0xFF14161F)
                        : (mPrefs.darkTheme ? 0x99EBEBF5 : 0x8A3C3C43));
                if (checked) {
                    AlarmScheduler.scheduleAlarm(ctx, item);
                    SamuraiDynamicIslandBus.publishToast(
                            ctx,
                            SamuraiDynamicIslandBus.TYPE_TIMER,
                            String.format(Locale.US, "Alarm %02d:%02d Set", item.hour24, item.minute),
                            item.label,
                            0xFFFF9F0A,
                            2200L);
                } else {
                    AlarmScheduler.cancelAlarm(ctx, item.id);
                }
            });
            row.addView(sw);

            card.addView(row);
            card.setOnClickListener(v -> {
                SamuraiGlassPrefs.hapticClick(ctx, v);
                openEditAlarmSheet(item);
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = Math.round(11f * d);
            mAlarmsListCol.addView(card, lp);
        }
    }

    public void updatePrefs(SamuraiGlassPrefs.Snapshot prefs) {
        mPrefs = prefs;
        for (SamuraiGlassCard c : mRenderedCards) c.updatePrefs(prefs);
        mSnoozeSwitch.setDarkTheme(prefs.darkTheme);
        applyTheme(prefs.darkTheme);
        reloadAndRenderAlarms();
    }

    private void applyTheme(boolean dark) {
        int pri = dark ? 0xFFF5F5FA : 0xFF14161F;
        int sec = dark ? 0x99EBEBF5 : 0x8A3C3C43;
        mHeaderTitle.setTextColor(pri);
        mEditorTitleTv.setTextColor(pri);
        mHourValTv.setTextColor(pri);
        mMinValTv.setTextColor(pri);
        mLabelInput.setTextColor(pri);
        mLabelInput.setHintTextColor(sec);
        refreshDayPillStyles();
    }

    private LinearLayout buildStepperColumn(
            Context ctx,
            String caption,
            TextView valueTv,
            Runnable onInc,
            Runnable onDec,
            float d) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView upBtn = createStepOrb(ctx, "\u25B2", d);
        upBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            onInc.run();
        });
        col.addView(upBtn);

        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44f);
        valueTv.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        valueTv.setFontFeatureSettings("tnum");
        valueTv.setGravity(Gravity.CENTER);
        valueTv.setPadding(Math.round(12f * d), Math.round(4f * d), Math.round(12f * d), Math.round(2f * d));
        col.addView(valueTv);

        TextView downBtn = createStepOrb(ctx, "\u25BC", d);
        downBtn.setOnClickListener(v -> {
            SamuraiGlassPrefs.hapticTick(v);
            onDec.run();
        });
        col.addView(downBtn);

        TextView cap = new TextView(ctx);
        cap.setText(caption);
        cap.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        cap.setTextColor(0x998E8E93);
        cap.setPadding(0, Math.round(4f * d), 0, 0);
        col.addView(cap);

        return col;
    }

    private static TextView createStepOrb(Context ctx, String symbol, float d) {
        TextView tv = new TextView(ctx);
        tv.setText(symbol);
        tv.setTextColor(0xFFFF9F0A);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f);
        bg.setColor(0x26FF9F0A);
        tv.setBackground(bg);
        int w = Math.round(52f * d);
        int h = Math.round(30f * d);
        tv.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return tv;
    }

    private void stepHour(int delta) {
        mDraftHour = (mDraftHour + delta + 24) % 24;
        refreshDraftTimeTexts();
    }

    private void stepMinute(int delta) {
        mDraftMin = (mDraftMin + delta + 60) % 60;
        refreshDraftTimeTexts();
    }

    private void refreshDraftTimeTexts() {
        mHourValTv.setText(String.format(Locale.US, "%02d", mDraftHour));
        mMinValTv.setText(String.format(Locale.US, "%02d", mDraftMin));
    }

    private void refreshDayPillStyles() {
        boolean dark = mPrefs != null && mPrefs.darkTheme;
        for (int i = 0; i < 7; i++) {
            boolean on = (mDraftDaysMask & (1 << i)) != 0;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(on ? 0xFFFF9F0A : (dark ? 0x33787880 : 0x24787880));
            mDayPills[i].setBackground(bg);
            mDayPills[i].setTextColor(on ? 0xFFFFFFFF : (dark ? 0xFFEBEBF5 : 0xFF3C3C43));
        }
    }

    private void commitEditorSave() {
        Context ctx = getContext();
        String label = mLabelInput.getText() != null ? mLabelInput.getText().toString().trim() : "";
        if (label.isEmpty()) label = "Alarm";

        if (mEditingId < 0) {
            int newId = ClockDataStore.allocateAlarmId(ctx);
            ClockDataStore.AlarmItem created = new ClockDataStore.AlarmItem(
                    newId,
                    mDraftHour,
                    mDraftMin,
                    label,
                    mDraftDaysMask,
                    mSnoozeSwitch.isChecked(),
                    true);
            mAlarms.add(created);
            ClockDataStore.saveAlarms(ctx, mAlarms);
            AlarmScheduler.scheduleAlarm(ctx, created);
            SamuraiDynamicIslandBus.publishToast(
                    ctx,
                    SamuraiDynamicIslandBus.TYPE_TIMER,
                    String.format(Locale.US, "Alarm %02d:%02d Set", created.hour24, created.minute),
                    created.label,
                    0xFFFF9F0A,
                    2200L);
        } else {
            for (ClockDataStore.AlarmItem item : mAlarms) {
                if (item.id == mEditingId) {
                    item.hour24 = mDraftHour;
                    item.minute = mDraftMin;
                    item.label = label;
                    item.daysMask = mDraftDaysMask;
                    item.snoozeEnabled = mSnoozeSwitch.isChecked();
                    item.enabled = true;
                    ClockDataStore.saveAlarms(ctx, mAlarms);
                    AlarmScheduler.scheduleAlarm(ctx, item);
                    break;
                }
            }
        }
        mEditorOverlay.setVisibility(View.GONE);
        reloadAndRenderAlarms();
    }

    private void deleteEditingAlarm() {
        if (mEditingId > 0) {
            AlarmScheduler.cancelAlarm(getContext(), mEditingId);
            mAlarms.removeIf(a -> a.id == mEditingId);
            ClockDataStore.saveAlarms(getContext(), mAlarms);
        }
        mEditorOverlay.setVisibility(View.GONE);
        reloadAndRenderAlarms();
    }
}
