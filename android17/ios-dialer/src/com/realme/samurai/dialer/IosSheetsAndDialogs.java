package com.realme.samurai.dialer;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.CallEndpoint;
import android.telecom.PhoneAccountHandle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Authentic iOS 26.1 Refractive Liquid Glass Modal Sheets, Top-Right `≡` Filter Popover,
 * and Clean Phone Settings Sheet (Zero Mod Bloat):
 *  - Contact & Call Info Sheet (Avatar, 4 Liquid Glass Action Tiles, History, Block/Unblock, Star)
 *  - Top-Right `≡` Filter Popover (`Classic` vs `Unified` visual wireframes + `Calls / Missed / Voicemail / Unknown Callers`)
 *  - Clean iOS 26.1 Phone Settings Sheet (`Calls Layout`, `Default Voice Line`, `Silence Unknown Callers`,
 *    `Hide Unknown Callers`, `Blocked Contacts`, `Respond with Text`, `Keypad Sounds`, `System Haptics`)
 *  - Clear All Recents, Quick-Reply Message, Remind Me, and Audio Route Sheets
 */
public final class IosSheetsAndDialogs {

    public interface FilterPopoverCallbacks {
        void onSelectLayout(boolean unified);
        void onSelectFilter(int filterMode);
        void onOpenPhoneSettings();
    }

    private static final String CHANNEL_REMINDERS = "ios26_call_reminders";

    private IosSheetsAndDialogs() {}

    public static void showContactAndCallInfoSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final DialerDataRepository.ContactItem contactOpt,
            final DialerDataRepository.RecentCallGroup recentGroupOpt,
            final PhoneAccountHandle preferredSim) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;
        final DialerDataRepository repo = DialerDataRepository.get(ctx);

        final DialerDataRepository.ContactItem resolvedContact = (contactOpt != null)
                ? contactOpt
                : (recentGroupOpt != null ? repo.findContactForNumber(recentGroupOpt.rawNumber) : null);

        final String primaryNum = (resolvedContact != null && !resolvedContact.primaryNumber().isEmpty())
                ? resolvedContact.primaryNumber()
                : (recentGroupOpt != null ? recentGroupOpt.rawNumber : "");
        final String displayTitle = (resolvedContact != null)
                ? resolvedContact.displayName
                : (recentGroupOpt != null ? recentGroupOpt.baseNameOrNumber() : TelecomHelper.formatDisplayNumber(primaryNum));
        final String photoUri = (resolvedContact != null)
                ? resolvedContact.photoUri
                : (recentGroupOpt != null ? recentGroupOpt.photoUri : null);

        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout sheetRoot = createSheetContainer(ctx, opt, dark, d);

        // Top Bar: Grabber + Refractive "Done" Liquid Glass Pill
        sheetRoot.addView(createSheetHeaderBar(ctx, opt, dark, d, null, dlg::dismiss),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d)));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int sidePad = Math.round(18f * d);
        body.setPadding(sidePad, Math.round(4f * d), sidePad, Math.round(28f * d));

        // 78dp Avatar
        View avatarView = new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                repo.drawAvatar(
                        c, getWidth() * 0.5f, getHeight() * 0.5f,
                        getWidth() * 0.48f, displayTitle, photoUri, mP, this::invalidate);
            }
        };
        body.addView(avatarView, new LinearLayout.LayoutParams(Math.round(78f * d), Math.round(78f * d)));

        // Name & Subtitle
        TextView nameTv = new TextView(ctx);
        nameTv.setText(displayTitle);
        nameTv.setGravity(Gravity.CENTER);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        nameTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        nameTv.setTextColor(DialerGlassEngine.primaryInk(dark));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = Math.round(10f * d);
        body.addView(nameTv, nLp);

        if (resolvedContact != null && !primaryNum.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(TelecomHelper.formatDisplayNumber(primaryNum));
            subTv.setGravity(Gravity.CENTER);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            subTv.setTextColor(DialerGlassEngine.secondaryInk(dark));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = Math.round(2f * d);
            body.addView(subTv, sLp);
        }

        // 4 iOS Liquid Glass Action Tiles: message / call / video / mail
        LinearLayout tilesRow = new LinearLayout(ctx);
        tilesRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = Math.round(16f * d);
        trLp.bottomMargin = Math.round(14f * d);

        tilesRow.addView(buildActionTile(ctx, opt, dark, d, "message",
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawMessageBubble(c, cx, cy, sz, col, p),
                v -> {
                    dlg.dismiss();
                    if (!primaryNum.isEmpty()) {
                        TelecomHelper.openSmsComposer(ctx, primaryNum, null);
                    }
                }), tileLp(d, true));

        tilesRow.addView(buildActionTile(ctx, opt, dark, d, "call",
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawPhoneHandset(c, cx, cy, sz, col, false, p),
                v -> {
                    dlg.dismiss();
                    if (!primaryNum.isEmpty()) {
                        TelecomHelper.placeCall(ctx, primaryNum, preferredSim, false);
                    }
                }), tileLp(d, true));

        tilesRow.addView(buildActionTile(ctx, opt, dark, d, "video",
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawVideoCamera(c, cx, cy, sz, col, p),
                v -> {
                    dlg.dismiss();
                    if (!primaryNum.isEmpty()) {
                        TelecomHelper.placeCall(ctx, primaryNum, preferredSim, true);
                    }
                }), tileLp(d, true));

        tilesRow.addView(buildActionTile(ctx, opt, dark, d, "mail",
                (c, cx, cy, sz, col, p) -> IosVectorIcons.drawEnvelope(c, cx, cy, sz, col, p),
                v -> {
                    dlg.dismiss();
                    try {
                        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                        ctx.startActivity(mail);
                    } catch (Throwable ignored) {
                        Toast.makeText(ctx, "No Mail app configured", Toast.LENGTH_SHORT).show();
                    }
                }), tileLp(d, false));

        body.addView(tilesRow, trLp);

        // Recent Call History Card
        if (recentGroupOpt != null && !recentGroupOpt.entries.isEmpty()) {
            CardHolder hist = createGroupedCard(ctx, opt, dark, d);
            SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());
            SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

            TextView hdr = new TextView(ctx);
            hdr.setText(dayFmt.format(new Date(recentGroupOpt.latestDateMillis)));
            hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            hdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            hdr.setTextColor(DialerGlassEngine.secondaryInk(dark));
            hdr.setPadding(Math.round(16f * d), Math.round(12f * d), Math.round(16f * d), Math.round(6f * d));
            hist.content.addView(hdr);

            int maxShow = Math.min(recentGroupOpt.entries.size(), 6);
            for (int i = 0; i < maxShow; i++) {
                DialerDataRepository.SingleCallEntry e = recentGroupOpt.entries.get(i);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Math.round(16f * d), Math.round(7f * d), Math.round(16f * d), Math.round(7f * d));

                TextView tTv = new TextView(ctx);
                tTv.setText(timeFmt.format(new Date(e.dateMillis)));
                tTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                tTv.setTextColor(DialerGlassEngine.primaryInk(dark));
                row.addView(tTv, new LinearLayout.LayoutParams(Math.round(86f * d), ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView kindTv = new TextView(ctx);
                kindTv.setText(callTypeLabel(e.callType));
                kindTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                kindTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                boolean isMissed = e.callType == CallLog.Calls.MISSED_TYPE || e.callType == CallLog.Calls.REJECTED_TYPE;
                kindTv.setTextColor(isMissed ? DialerGlassEngine.IOS_RED : DialerGlassEngine.primaryInk(dark));
                row.addView(kindTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView durTv = new TextView(ctx);
                String durStr = (e.durationSec > 0L)
                        ? DialerDataRepository.formatDurationHuman(e.durationSec)
                        : (e.callType == CallLog.Calls.OUTGOING_TYPE ? "Canceled" : "");
                durTv.setText(durStr);
                durTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                durTv.setTextColor(DialerGlassEngine.secondaryInk(dark));
                row.addView(durTv);

                hist.content.addView(row);
            }
            LinearLayout.LayoutParams hcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hcLp.bottomMargin = Math.round(12f * d);
            body.addView(hist.frame, hcLp);
        }

        // Phone Numbers Card
        List<DialerDataRepository.PhoneEntry> phones = new ArrayList<>();
        if (resolvedContact != null && !resolvedContact.phones.isEmpty()) {
            phones.addAll(resolvedContact.phones);
        } else if (!primaryNum.isEmpty()) {
            phones.add(new DialerDataRepository.PhoneEntry(primaryNum, "mobile"));
        }
        if (!phones.isEmpty()) {
            CardHolder phoneCard = createGroupedCard(ctx, opt, dark, d);
            for (int i = 0; i < phones.size(); i++) {
                final DialerDataRepository.PhoneEntry pe = phones.get(i);
                LinearLayout pRow = new LinearLayout(ctx);
                pRow.setOrientation(LinearLayout.VERTICAL);
                int rp = Math.round(16f * d);
                pRow.setPadding(rp, Math.round(11f * d), rp, Math.round(11f * d));
                pRow.setOnClickListener(v -> {
                    DialerGlassEngine.performKeyboardTap(v);
                    dlg.dismiss();
                    TelecomHelper.placeCall(ctx, pe.number, preferredSim, false);
                });
                pRow.setOnLongClickListener(v -> {
                    TelecomHelper.copyToClipboard(ctx, "Phone Number", pe.number);
                    return true;
                });

                TextView lbl = new TextView(ctx);
                lbl.setText(pe.label);
                lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                lbl.setTextColor(DialerGlassEngine.primaryInk(dark));
                pRow.addView(lbl);

                TextView num = new TextView(ctx);
                num.setText(pe.formattedNumber);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
                num.setTextColor(DialerGlassEngine.accentBlue(dark));
                pRow.addView(num);

                phoneCard.content.addView(pRow);
                if (i < phones.size() - 1) {
                    phoneCard.content.addView(createHairline(ctx, dark, d));
                }
            }
            LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pcLp.bottomMargin = Math.round(12f * d);
            body.addView(phoneCard.frame, pcLp);
        }

        // Actions Card (Favourites, Copy, Edit/Create Contact, Block/Unblock, Delete Call Entry)
        CardHolder actCard = createGroupedCard(ctx, opt, dark, d);
        if (resolvedContact != null && resolvedContact.contactId > 0) {
            final boolean isStarred = resolvedContact.starred;
            actCard.content.addView(createSheetActionRow(
                    ctx, dark, d,
                    isStarred ? "Remove from Favorites" : "Add to Favorites",
                    DialerGlassEngine.accentBlue(dark),
                    v -> {
                        DialerGlassEngine.performKeyboardTap(v);
                        repo.setContactStarredAsync(resolvedContact.contactId, !isStarred);
                        dlg.dismiss();
                    }));
            actCard.content.addView(createHairline(ctx, dark, d));
        }

        if (!primaryNum.isEmpty()) {
            actCard.content.addView(createSheetActionRow(
                    ctx, dark, d,
                    "Copy Number",
                    DialerGlassEngine.accentBlue(dark),
                    v -> {
                        DialerGlassEngine.performKeyboardTap(v);
                        TelecomHelper.copyToClipboard(ctx, "Phone Number", primaryNum);
                        dlg.dismiss();
                    }));
            actCard.content.addView(createHairline(ctx, dark, d));
        }

        actCard.content.addView(createSheetActionRow(
                ctx, dark, d,
                (resolvedContact != null && resolvedContact.contactId > 0)
                        ? "Edit Contact" : "Create New Contact",
                DialerGlassEngine.accentBlue(dark),
                v -> {
                    DialerGlassEngine.performKeyboardTap(v);
                    dlg.dismiss();
                    if (resolvedContact != null && resolvedContact.contactId > 0) {
                        try {
                            Uri cUri = ContentUris.withAppendedId(
                                    ContactsContract.Contacts.CONTENT_URI, resolvedContact.contactId);
                            Intent edit = new Intent(Intent.ACTION_EDIT, cUri);
                            ctx.startActivity(edit);
                        } catch (Throwable ignored) {
                            TelecomHelper.openInsertContact(ctx, primaryNum);
                        }
                    } else {
                        TelecomHelper.openInsertContact(ctx, primaryNum);
                    }
                }));

        if (!primaryNum.isEmpty()) {
            final boolean currentlyBlocked = TelecomHelper.isLocallyBlocked(ctx, primaryNum);
            actCard.content.addView(createHairline(ctx, dark, d));
            actCard.content.addView(createSheetActionRow(
                    ctx, dark, d,
                    currentlyBlocked ? "Unblock this Caller" : "Block this Caller",
                    currentlyBlocked ? DialerGlassEngine.accentBlue(dark) : DialerGlassEngine.IOS_RED,
                    v -> {
                        DialerGlassEngine.performTick(v, true);
                        TelecomHelper.setNumberBlocked(ctx, primaryNum, !currentlyBlocked);
                        Toast.makeText(ctx,
                                !currentlyBlocked ? "Caller blocked" : "Caller unblocked",
                                Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                    }));
        }

        if (recentGroupOpt != null) {
            actCard.content.addView(createHairline(ctx, dark, d));
            actCard.content.addView(createSheetActionRow(
                    ctx, dark, d,
                    "Delete Call Entry",
                    DialerGlassEngine.IOS_RED,
                    v -> {
                        DialerGlassEngine.performTick(v, true);
                        repo.deleteCallLogEntriesAsync(recentGroupOpt.allIds());
                        dlg.dismiss();
                    }));
        }

        body.addView(actCard.frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(body);
        sheetRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(sheetRoot);
        dlg.show();
    }

    /**
     * Genuine Apple iOS 26.1 Top-Right `≡` Filter Popover Sheet (`04_phone_classic_vs_unified_tidbits.jpg`):
     *  - Side-by-side `Classic` vs `Unified` visual wireframe Liquid Glass selector tiles
     *  - Filter rows with checkmarks: `Calls`, `Missed`, `Voicemail`, `Unknown Callers`
     *  - `Phone Settings & Call Filtering…`
     */
    public static void showFilterPopover(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final boolean currentUnified,
            final int currentFilterMode,
            final FilterPopoverCallbacks callbacks) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;

        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, dark, d);
        root.addView(createSheetHeaderBar(ctx, opt, dark, d, "Calls View", dlg::dismiss),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d)));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18f * d);
        body.setPadding(pad, Math.round(4f * d), pad, Math.round(28f * d));

        // 1. Classic vs Unified Visual Wireframe Liquid Glass Selector Row
        LinearLayout layoutRow = new LinearLayout(ctx);
        layoutRow.setOrientation(LinearLayout.HORIZONTAL);
        layoutRow.addView(buildLayoutChoiceTile(ctx, opt, dark, d, "Classic", false, !currentUnified, v -> {
            dlg.dismiss();
            if (callbacks != null) callbacks.onSelectLayout(false);
        }), tileLp(d, true));
        layoutRow.addView(buildLayoutChoiceTile(ctx, opt, dark, d, "Unified", true, currentUnified, v -> {
            dlg.dismiss();
            if (callbacks != null) callbacks.onSelectLayout(true);
        }), tileLp(d, false));

        LinearLayout.LayoutParams lrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrLp.bottomMargin = Math.round(14f * d);
        body.addView(layoutRow, lrLp);

        // 2. Filter Checkmark Rows (`Calls`, `Missed`, `Voicemail`, `Unknown Callers`)
        CardHolder filterCard = createGroupedCard(ctx, opt, dark, d);
        final String[] filterNames = {"Calls", "Missed", "Voicemail", "Unknown Callers"};
        for (int i = 0; i < filterNames.length; i++) {
            final int fIdx = i;
            boolean checked = (currentFilterMode == fIdx);
            filterCard.content.addView(createCheckmarkRow(
                    ctx, dark, d, filterNames[i], checked,
                    v -> {
                        DialerGlassEngine.performKeyboardTap(v);
                        dlg.dismiss();
                        if (callbacks != null) callbacks.onSelectFilter(fIdx);
                    }));
            if (i < filterNames.length - 1) {
                filterCard.content.addView(createHairline(ctx, dark, d));
            }
        }
        LinearLayout.LayoutParams fcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fcLp.bottomMargin = Math.round(12f * d);
        body.addView(filterCard.frame, fcLp);

        // 3. Clean Phone Settings & Call Filtering Entry
        CardHolder settingsCard = createGroupedCard(ctx, opt, dark, d);
        settingsCard.content.addView(createSheetActionRow(
                ctx, dark, d, "Phone Settings & Call Filtering…",
                DialerGlassEngine.accentBlue(dark),
                v -> {
                    DialerGlassEngine.performKeyboardTap(v);
                    dlg.dismiss();
                    if (callbacks != null) callbacks.onOpenPhoneSettings();
                }));
        body.addView(settingsCard.frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(body);
        dlg.setContentView(root);
        dlg.show();
    }

    private static View buildLayoutChoiceTile(
            Context ctx,
            DialerGlassEngine.LiveOpticalState opt,
            final boolean dark,
            final float d,
            String title,
            final boolean unifiedWireframe,
            final boolean selected,
            View.OnClickListener click) {
        DialerGlassWidgets.GlassPanelFrame card = new DialerGlassWidgets.GlassPanelFrame(ctx, opt, 18f);
        card.setTintBoost(selected ? 0.34f : 0.14f);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int vp = Math.round(12f * d);
        col.setPadding(vp, vp, vp, vp);

        final int activeBlue = DialerGlassEngine.accentBlue(dark);
        View wf = new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                int col = selected ? activeBlue : DialerGlassEngine.secondaryInk(dark);
                IosVectorIcons.drawLayoutWireframe(
                        c, getWidth() * 0.5f, getHeight() * 0.5f,
                        46f * d, col, unifiedWireframe, mP);
            }
        };
        col.addView(wf, new LinearLayout.LayoutParams(Math.round(52f * d), Math.round(52f * d)));

        TextView tv = new TextView(ctx);
        tv.setText(selected ? ("✓ " + title) : title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(selected ? activeBlue : DialerGlassEngine.primaryInk(dark));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = Math.round(6f * d);
        col.addView(tv, tLp);

        card.addView(col);
        card.setOnClickListener(v -> {
            DialerGlassEngine.performTick(v, true);
            click.onClick(v);
        });
        return card;
    }

    /**
     * 100% Clean Apple iOS 26.1 Phone Settings Sheet — ZERO Mod Screen, ZERO Fake Call,
     * ZERO Flash Blinkers, ZERO Call Announcer, ZERO Ads/VIP Bloat!
     */
    public static void showPhoneSettingsSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final List<TelecomHelper.SimSlotInfo> sims,
            final Runnable onSettingsUpdated) {
        if (ctx == null) return;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;

        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, dark, d);
        root.addView(createSheetHeaderBar(ctx, opt, dark, d, "Phone Settings", () -> {
            dlg.dismiss();
            if (onSettingsUpdated != null) onSettingsUpdated.run();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d)));

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int sidePad = Math.round(18f * d);
        body.setPadding(sidePad, Math.round(4f * d), sidePad, Math.round(30f * d));

        // Section 1: Calls Layout & Voice Line
        body.addView(createSectionCaption(ctx, dark, d, "PREFERRED VIEW & LINE"));
        CardHolder layoutCard = createGroupedCard(ctx, opt, dark, d);

        LinearLayout layoutRow = new LinearLayout(ctx);
        layoutRow.setOrientation(LinearLayout.HORIZONTAL);
        layoutRow.setGravity(Gravity.CENTER_VERTICAL);
        layoutRow.setPadding(Math.round(16f * d), Math.round(10f * d), Math.round(14f * d), Math.round(10f * d));
        TextView layLbl = new TextView(ctx);
        layLbl.setText("Calls Layout");
        layLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        layLbl.setTextColor(DialerGlassEngine.primaryInk(dark));
        layoutRow.addView(layLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        DialerGlassWidgets.GlassSegmentedCapsule laySeg =
                new DialerGlassWidgets.GlassSegmentedCapsule(ctx, opt, new String[]{"Unified", "Classic"});
        laySeg.setSelectedIndex(TelecomHelper.isUnifiedCallsLayout(ctx) ? 0 : 1, false);
        laySeg.setOnSegmentListener(idx -> {
            TelecomHelper.setUnifiedCallsLayout(ctx, idx == 0);
            if (onSettingsUpdated != null) onSettingsUpdated.run();
        });
        layoutRow.addView(laySeg, new LinearLayout.LayoutParams(Math.round(162f * d), Math.round(34f * d)));
        layoutCard.content.addView(layoutRow);

        if (sims != null && !sims.isEmpty()) {
            layoutCard.content.addView(createHairline(ctx, dark, d));
            final int[] curSim = {TelecomHelper.getPreferredSimIndex(ctx, sims)};
            final TextView simValTv = new TextView(ctx);
            simValTv.setText(sims.get(curSim[0]).carrierLabel);
            simValTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            simValTv.setTextColor(DialerGlassEngine.secondaryInk(dark));

            LinearLayout simRow = new LinearLayout(ctx);
            simRow.setOrientation(LinearLayout.HORIZONTAL);
            simRow.setGravity(Gravity.CENTER_VERTICAL);
            simRow.setPadding(Math.round(16f * d), Math.round(13f * d), Math.round(16f * d), Math.round(13f * d));
            TextView simTitle = new TextView(ctx);
            simTitle.setText("Default Voice Line");
            simTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            simTitle.setTextColor(DialerGlassEngine.primaryInk(dark));
            simRow.addView(simTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            simRow.addView(simValTv);
            simRow.setOnClickListener(v -> {
                if (sims.size() >= 2) {
                    DialerGlassEngine.performKeyboardTap(v);
                    curSim[0] = (curSim[0] + 1) % sims.size();
                    TelecomHelper.setPreferredSimAccount(ctx, sims.get(curSim[0]));
                    simValTv.setText(sims.get(curSim[0]).carrierLabel);
                    if (onSettingsUpdated != null) onSettingsUpdated.run();
                }
            });
            layoutCard.content.addView(simRow);
        }
        body.addView(layoutCard.frame, cardMarginLp(d));

        // Section 2: Call Screening & Filtering
        body.addView(createSectionCaption(ctx, dark, d, "CALL SCREENING & FILTERING"));
        CardHolder screenCard = createGroupedCard(ctx, opt, dark, d);
        screenCard.content.addView(createSwitchRow(
                ctx, opt, dark, d,
                "Silence Unknown Callers",
                "Calls from unknown numbers are silenced and sent to Recents",
                TelecomHelper.isSilenceUnknownCallersEnabled(ctx),
                checked -> TelecomHelper.setSilenceUnknownCallersEnabled(ctx, checked)));
        screenCard.content.addView(createHairline(ctx, dark, d));
        screenCard.content.addView(createSwitchRow(
                ctx, opt, dark, d,
                "Hide Unknown Callers",
                "Move calls from unsaved numbers into the Unknown Callers filter",
                TelecomHelper.isFilterUnknownFromCallsEnabled(ctx),
                checked -> {
                    TelecomHelper.setFilterUnknownFromCallsEnabled(ctx, checked);
                    if (onSettingsUpdated != null) onSettingsUpdated.run();
                }));
        body.addView(screenCard.frame, cardMarginLp(d));

        // Section 3: Calls (Blocked Contacts & Respond with Text)
        body.addView(createSectionCaption(ctx, dark, d, "CALLS"));
        CardHolder callsCard = createGroupedCard(ctx, opt, dark, d);
        final TextView blockedCountTv = new TextView(ctx);
        int bCount = TelecomHelper.getBlockedNumbers(ctx).size();
        blockedCountTv.setText(bCount == 0 ? "None ›" : (bCount + " ›"));
        blockedCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        blockedCountTv.setTextColor(DialerGlassEngine.secondaryInk(dark));

        LinearLayout blockedRow = new LinearLayout(ctx);
        blockedRow.setOrientation(LinearLayout.HORIZONTAL);
        blockedRow.setGravity(Gravity.CENTER_VERTICAL);
        blockedRow.setPadding(Math.round(16f * d), Math.round(14f * d), Math.round(16f * d), Math.round(14f * d));
        TextView blockedLbl = new TextView(ctx);
        blockedLbl.setText("Blocked Contacts");
        blockedLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        blockedLbl.setTextColor(DialerGlassEngine.primaryInk(dark));
        blockedRow.addView(blockedLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        blockedRow.addView(blockedCountTv);
        blockedRow.setOnClickListener(v -> {
            DialerGlassEngine.performKeyboardTap(v);
            showBlockedContactsSheet(ctx, opt, () -> {
                int c = TelecomHelper.getBlockedNumbers(ctx).size();
                blockedCountTv.setText(c == 0 ? "None ›" : (c + " ›"));
            });
        });
        callsCard.content.addView(blockedRow);
        callsCard.content.addView(createHairline(ctx, dark, d));

        LinearLayout replyRow = new LinearLayout(ctx);
        replyRow.setOrientation(LinearLayout.HORIZONTAL);
        replyRow.setGravity(Gravity.CENTER_VERTICAL);
        replyRow.setPadding(Math.round(16f * d), Math.round(14f * d), Math.round(16f * d), Math.round(14f * d));
        TextView replyLbl = new TextView(ctx);
        replyLbl.setText("Respond with Text");
        replyLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        replyLbl.setTextColor(DialerGlassEngine.primaryInk(dark));
        replyRow.addView(replyLbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = new TextView(ctx);
        chev.setText("3 replies ›");
        chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        chev.setTextColor(DialerGlassEngine.secondaryInk(dark));
        replyRow.addView(chev);
        replyRow.setOnClickListener(v -> {
            DialerGlassEngine.performKeyboardTap(v);
            showRespondWithTextEditorSheet(ctx, opt);
        });
        callsCard.content.addView(replyRow);
        body.addView(callsCard.frame, cardMarginLp(d));

        // Section 4: Sounds & Haptics
        body.addView(createSectionCaption(ctx, dark, d, "SOUNDS & HAPTICS"));
        CardHolder soundCard = createGroupedCard(ctx, opt, dark, d);
        soundCard.content.addView(createSwitchRow(
                ctx, opt, dark, d,
                "Keypad Sounds",
                null,
                TelecomHelper.isKeypadSoundsEnabled(ctx),
                checked -> TelecomHelper.setKeypadSoundsEnabled(ctx, checked)));
        soundCard.content.addView(createHairline(ctx, dark, d));
        soundCard.content.addView(createSwitchRow(
                ctx, opt, dark, d,
                "System Haptics",
                null,
                TelecomHelper.isHapticsEnabled(ctx),
                checked -> TelecomHelper.setHapticsEnabled(ctx, checked)));
        body.addView(soundCard.frame, cardMarginLp(d));

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg.setContentView(root);
        dlg.show();
    }

    private static void showBlockedContactsSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final Runnable onChanged) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, dark, d);
        root.addView(createSheetHeaderBar(ctx, opt, dark, d, "Blocked Contacts", dlg::dismiss),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d)));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18f * d);
        body.setPadding(pad, Math.round(6f * d), pad, Math.round(28f * d));

        // Add number input capsule
        CardHolder addCard = createGroupedCard(ctx, opt, dark, d);
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(Math.round(14f * d), Math.round(6f * d), Math.round(10f * d), Math.round(6f * d));

        final EditText input = new EditText(ctx);
        input.setBackground(null);
        input.setHint("Enter phone number to block…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        input.setTextColor(DialerGlassEngine.primaryInk(dark));
        input.setHintTextColor(DialerGlassEngine.secondaryInk(dark));
        addRow.addView(input, new LinearLayout.LayoutParams(0, Math.round(38f * d), 1f));

        final LinearLayout listWrap = new LinearLayout(ctx);
        listWrap.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = () -> {
            listWrap.removeAllViews();
            List<String> nums = TelecomHelper.getBlockedNumbers(ctx);
            if (nums.isEmpty()) {
                TextView empty = new TextView(ctx);
                empty.setText("No Blocked Numbers");
                empty.setGravity(Gravity.CENTER);
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                empty.setTextColor(DialerGlassEngine.secondaryInk(dark));
                empty.setPadding(0, Math.round(20f * d), 0, Math.round(12f * d));
                listWrap.addView(empty);
                return;
            }
            CardHolder card = createGroupedCard(ctx, opt, dark, d);
            for (int i = 0; i < nums.size(); i++) {
                final String n = nums.get(i);
                LinearLayout r = new LinearLayout(ctx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(Math.round(16f * d), Math.round(12f * d), Math.round(16f * d), Math.round(12f * d));
                TextView tv = new TextView(ctx);
                tv.setText(TelecomHelper.formatDisplayNumber(n));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                tv.setTextColor(DialerGlassEngine.primaryInk(dark));
                r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView unb = new TextView(ctx);
                unb.setText("Unblock");
                unb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
                unb.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                unb.setTextColor(DialerGlassEngine.IOS_RED);
                unb.setOnClickListener(v -> {
                    DialerGlassEngine.performTick(v, true);
                    TelecomHelper.setNumberBlocked(ctx, n, false);
                    refreshList[0].run();
                    if (onChanged != null) onChanged.run();
                });
                r.addView(unb);
                card.content.addView(r);
                if (i < nums.size() - 1) {
                    card.content.addView(createHairline(ctx, dark, d));
                }
            }
            listWrap.addView(card.frame);
        };

        DialerGlassWidgets.GlassPillButton blockBtn =
                new DialerGlassWidgets.GlassPillButton(ctx, opt, "Block");
        blockBtn.setPillMetrics(32f, 13.5f, true);
        blockBtn.setSolidFillColor(DialerGlassEngine.IOS_RED);
        blockBtn.setOnClickListener(v -> {
            String raw = input.getText() != null ? input.getText().toString().trim() : "";
            if (!raw.isEmpty()) {
                TelecomHelper.setNumberBlocked(ctx, raw, true);
                input.setText("");
                refreshList[0].run();
                if (onChanged != null) onChanged.run();
            }
        });
        addRow.addView(blockBtn);
        addCard.content.addView(addRow);
        body.addView(addCard.frame, cardMarginLp(d));

        refreshList[0].run();
        body.addView(listWrap);

        root.addView(body);
        dlg.setContentView(root);
        dlg.show();
    }

    private static void showRespondWithTextEditorSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, dark, d);

        final EditText[] edits = new EditText[3];
        String[] cur = TelecomHelper.getQuickReplies(ctx);

        root.addView(createSheetHeaderBar(ctx, opt, dark, d, "Respond with Text", () -> {
            for (int i = 0; i < 3; i++) {
                String txt = edits[i].getText() != null ? edits[i].getText().toString() : "";
                TelecomHelper.setQuickReply(ctx, i, txt);
            }
            dlg.dismiss();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48f * d)));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18f * d);
        body.setPadding(pad, Math.round(6f * d), pad, Math.round(28f * d));

        CardHolder card = createGroupedCard(ctx, opt, dark, d);
        for (int i = 0; i < 3; i++) {
            EditText et = new EditText(ctx);
            et.setBackground(null);
            et.setText(cur[i]);
            et.setSingleLine(true);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            et.setTextColor(DialerGlassEngine.primaryInk(dark));
            et.setHintTextColor(DialerGlassEngine.secondaryInk(dark));
            et.setPadding(Math.round(16f * d), Math.round(14f * d), Math.round(16f * d), Math.round(14f * d));
            edits[i] = et;
            card.content.addView(et);
            if (i < 2) {
                card.content.addView(createHairline(ctx, dark, d));
            }
        }
        body.addView(card.frame);
        root.addView(body);
        dlg.setContentView(root);
        dlg.show();
    }

    public static void showClearRecentsSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final Runnable onConfirmClear) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = opt != null && opt.darkTheme;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, dark, d);
        int pad = Math.round(18f * d);
        root.setPadding(pad, Math.round(14f * d), pad, Math.round(28f * d));

        CardHolder card = createGroupedCard(ctx, opt, dark, d);
        card.content.addView(createSheetActionRow(
                ctx, dark, d, "Clear All Recents", DialerGlassEngine.IOS_RED,
                v -> {
                    DialerGlassEngine.performTick(v, true);
                    dlg.dismiss();
                    if (onConfirmClear != null) onConfirmClear.run();
                }));
        card.content.addView(createHairline(ctx, dark, d));
        card.content.addView(createSheetActionRow(
                ctx, dark, d, "Cancel", DialerGlassEngine.accentBlue(dark),
                v -> dlg.dismiss()));
        root.addView(card.frame);
        dlg.setContentView(root);
        dlg.show();
    }

    public static void showQuickReplyMessageSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final String rawNumber) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, true, d);
        int pad = Math.round(18f * d);
        root.setPadding(pad, Math.round(16f * d), pad, Math.round(28f * d));

        String[] replies = TelecomHelper.getQuickReplies(ctx);
        CardHolder card = createGroupedCard(ctx, opt, true, d);
        for (String msg : replies) {
            final String m = msg;
            card.content.addView(createSheetActionRow(
                    ctx, true, d, m, DialerGlassEngine.IOS_BLUE_DARK,
                    v -> {
                        DialerGlassEngine.performKeyboardTap(v);
                        dlg.dismiss();
                        CallManager.get().rejectWithSms(m);
                        if (rawNumber != null && !rawNumber.isEmpty()) {
                            TelecomHelper.openSmsComposer(ctx, rawNumber, m);
                        }
                    }));
            card.content.addView(createHairline(ctx, true, d));
        }
        card.content.addView(createSheetActionRow(
                ctx, true, d, "Custom…", DialerGlassEngine.IOS_BLUE_DARK,
                v -> {
                    DialerGlassEngine.performKeyboardTap(v);
                    dlg.dismiss();
                    CallManager.get().rejectOrHangup();
                    TelecomHelper.openSmsComposer(ctx, rawNumber, "");
                }));
        card.content.addView(createHairline(ctx, true, d));
        card.content.addView(createSheetActionRow(
                ctx, true, d, "Cancel", Color.WHITE,
                v -> dlg.dismiss()));

        root.addView(card.frame);
        dlg.setContentView(root);
        dlg.show();
    }

    public static void showRemindMeSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final String callerTitle,
            final String rawNumber) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, true, d);
        int pad = Math.round(18f * d);
        root.setPadding(pad, Math.round(16f * d), pad, Math.round(28f * d));

        CardHolder card = createGroupedCard(ctx, opt, true, d);
        card.content.addView(createSheetActionRow(
                ctx, true, d, "In 1 hour", DialerGlassEngine.IOS_BLUE_DARK,
                v -> {
                    dlg.dismiss();
                    scheduleCallbackReminder(ctx, callerTitle, rawNumber, "in 1 hour", 3600_000L);
                    CallManager.get().rejectOrHangup();
                }));
        card.content.addView(createHairline(ctx, true, d));
        card.content.addView(createSheetActionRow(
                ctx, true, d, "Later Today", DialerGlassEngine.IOS_BLUE_DARK,
                v -> {
                    dlg.dismiss();
                    scheduleCallbackReminder(ctx, callerTitle, rawNumber, "later today", 3 * 3600_000L);
                    CallManager.get().rejectOrHangup();
                }));
        card.content.addView(createHairline(ctx, true, d));
        card.content.addView(createSheetActionRow(
                ctx, true, d, "Cancel", Color.WHITE,
                v -> dlg.dismiss()));

        root.addView(card.frame);
        dlg.setContentView(root);
        dlg.show();
    }

    public static void showAudioRouteSheet(
            final Context ctx,
            final DialerGlassEngine.LiveOpticalState opt) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        final Dialog dlg = createBottomSheetDialog(ctx);
        LinearLayout root = createSheetContainer(ctx, opt, true, d);
        int pad = Math.round(18f * d);
        root.setPadding(pad, Math.round(16f * d), pad, Math.round(28f * d));

        CardHolder card = createGroupedCard(ctx, opt, true, d);
        List<CallEndpoint> endpoints = CallManager.get().getAvailableEndpoints();
        int curRoute = CallManager.get().getAudioRoute();

        if (!endpoints.isEmpty()) {
            for (final CallEndpoint ep : endpoints) {
                CharSequence nm = ep.getEndpointName();
                String label = (nm != null && nm.length() > 0) ? nm.toString() : "Audio";
                boolean active = (ep.getEndpointType() == CallEndpoint.TYPE_SPEAKER && curRoute == CallManager.ROUTE_SPEAKER)
                        || (ep.getEndpointType() == CallEndpoint.TYPE_BLUETOOTH && curRoute == CallManager.ROUTE_BLUETOOTH)
                        || (ep.getEndpointType() == CallEndpoint.TYPE_WIRED_HEADSET && curRoute == CallManager.ROUTE_WIRED_HEADSET)
                        || (ep.getEndpointType() == CallEndpoint.TYPE_EARPIECE && curRoute == CallManager.ROUTE_EARPIECE);
                if (active) {
                    label = "✓  " + label;
                }
                card.content.addView(createSheetActionRow(
                        ctx, true, d, label,
                        active ? DialerGlassEngine.IOS_BLUE_DARK : Color.WHITE,
                        v -> {
                            DialerGlassEngine.performKeyboardTap(v);
                            CallManager.get().selectSpecificEndpoint(ep);
                            dlg.dismiss();
                        }));
                card.content.addView(createHairline(ctx, true, d));
            }
        } else {
            card.content.addView(createSheetActionRow(
                    ctx, true, d,
                    (curRoute == CallManager.ROUTE_EARPIECE ? "✓  Phone Earpiece" : "Phone Earpiece"),
                    Color.WHITE,
                    v -> {
                        CallManager.get().selectEndpointByType(CallEndpoint.TYPE_EARPIECE);
                        dlg.dismiss();
                    }));
            card.content.addView(createHairline(ctx, true, d));
            card.content.addView(createSheetActionRow(
                    ctx, true, d,
                    (curRoute == CallManager.ROUTE_SPEAKER ? "✓  Speaker" : "Speaker"),
                    Color.WHITE,
                    v -> {
                        CallManager.get().selectEndpointByType(CallEndpoint.TYPE_SPEAKER);
                        dlg.dismiss();
                    }));
            card.content.addView(createHairline(ctx, true, d));
        }

        card.content.addView(createSheetActionRow(ctx, true, d, "Cancel", DialerGlassEngine.IOS_BLUE_DARK, v -> dlg.dismiss()));
        root.addView(card.frame);
        dlg.setContentView(root);
        dlg.show();
    }

    private static void scheduleCallbackReminder(
            final Context ctx,
            final String title,
            final String number,
            final String whenDesc,
            final long delayMs) {
        Toast.makeText(ctx, "Reminder set to call " + title + " " + whenDesc, Toast.LENGTH_SHORT).show();
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                NotificationManager nm = app.getSystemService(NotificationManager.class);
                if (nm == null) return;
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_REMINDERS,
                        app.getString(R.string.notif_channel_reminders),
                        NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(ch);

                Intent dial = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number != null ? number : "", null));
                dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(
                        app, 3201, dial,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                Notification n = new Notification.Builder(app, CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_stat_phone_call)
                        .setContentTitle("Call Back: " + title)
                        .setContentText("Tap to return call")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify((int) (System.currentTimeMillis() & 0x7FFF), n);
            } catch (Throwable ignored) {
            }
        }, Math.min(delayMs, 3600_000L));
    }

    private static Dialog createBottomSheetDialog(Context ctx) {
        Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.42f);
        }
        return dlg;
    }

    /**
     * Refractive 90Hz AGSL Liquid Glass Bottom Sheet Container (`28dp` top G2 continuous squircle).
     */
    private static LinearLayout createSheetContainer(
            Context ctx,
            final DialerGlassEngine.LiveOpticalState opt,
            final boolean dark,
            final float d) {
        LinearLayout root = new LinearLayout(ctx) {
            private final DialerGlassEngine.GlassSurfaceInstance mGlass =
                    new DialerGlassEngine.GlassSurfaceInstance();
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path mPath = new Path();
            private final int[] mLoc = new int[2];

            {
                mRim.setStyle(Paint.Style.STROKE);
                mRim.setStrokeWidth(1.15f * d);
            }

            @Override
            protected void onDraw(Canvas c) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 4 || h <= 4) {
                    super.onDraw(c);
                    return;
                }
                DialerGlassEngine.Squircle.buildPath(0f, 0f, w, h + 32f * d, 28f * d, mPath);
                getLocationOnScreen(mLoc);
                if (mGlass.bindToPaintNeutral(
                        mP, opt, (float) w, h + 32f * d,
                        (float) mLoc[0], (float) mLoc[1],
                        28f * d, d, w * 0.5f, 24f * d, 0f, 0.16f)) {
                    c.save();
                    c.clipPath(mPath);
                    c.drawRect(0f, 0f, w, h + 32f * d, mP);
                    c.restore();
                } else {
                    mP.setShader(null);
                    mP.setColor(dark ? 0xF51C1C1E : 0xF7F2F2F7);
                    c.drawPath(mPath, mP);
                }
                mRim.setShader(null);
                mRim.setStrokeWidth(0.6f * d);
                mRim.setColor(dark ? Color.argb(46, 255, 255, 255) : Color.argb(20, 0, 0, 0));
                c.drawPath(mPath, mRim);
                super.onDraw(c);
            }
        };
        root.setWillNotDraw(false);
        root.setOrientation(LinearLayout.VERTICAL);
        return root;
    }

    private static FrameLayout createSheetHeaderBar(
            Context ctx,
            DialerGlassEngine.LiveOpticalState opt,
            boolean dark,
            float d,
            String centerTitle,
            Runnable onDone) {
        FrameLayout headerBar = new FrameLayout(ctx);
        headerBar.setClipChildren(false);
        headerBar.setPadding(Math.round(18f * d), Math.round(6f * d), Math.round(18f * d), 0);

        View grabber = createGrabberView(ctx, dark, d);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                Math.round(38f * d), Math.round(5f * d), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        glp.topMargin = Math.round(3f * d);
        headerBar.addView(grabber, glp);

        if (centerTitle != null && !centerTitle.isEmpty()) {
            TextView titleTv = new TextView(ctx);
            titleTv.setText(centerTitle);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titleTv.setTextColor(DialerGlassEngine.primaryInk(dark));
            FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            tLp.topMargin = Math.round(4f * d);
            headerBar.addView(titleTv, tLp);
        }

        DialerGlassWidgets.GlassPillButton donePill =
                new DialerGlassWidgets.GlassPillButton(ctx, opt, "Done");
        donePill.setPillMetrics(32f, 14.5f, true);
        donePill.setOnClickListener(v -> {
            if (onDone != null) onDone.run();
        });
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        dlp.topMargin = Math.round(3f * d);
        headerBar.addView(donePill, dlp);
        return headerBar;
    }

    private static View createGrabberView(Context ctx, boolean dark, float d) {
        return new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                mP.setColor(dark ? 0x66EBEBF5 : 0x4D3C3C43);
                float r = getHeight() * 0.5f;
                c.drawRoundRect(0f, 0f, getWidth(), getHeight(), r, r, mP);
            }
        };
    }

    private static final class CardHolder {
        final DialerGlassWidgets.GlassPanelFrame frame;
        final LinearLayout content;

        CardHolder(DialerGlassWidgets.GlassPanelFrame frame, LinearLayout content) {
            this.frame = frame;
            this.content = content;
        }
    }

    private static CardHolder createGroupedCard(
            Context ctx,
            DialerGlassEngine.LiveOpticalState opt,
            boolean dark,
            float d) {
        DialerGlassWidgets.GlassPanelFrame frame = new DialerGlassWidgets.GlassPanelFrame(ctx, opt, 18f);
        frame.setTintBoost(dark ? 0.12f : 0.30f);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new CardHolder(frame, content);
    }

    private static LinearLayout.LayoutParams cardMarginLp(float d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(14f * d);
        return lp;
    }

    private static TextView createSectionCaption(Context ctx, boolean dark, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextColor(DialerGlassEngine.secondaryInk(dark));
        tv.setPadding(Math.round(12f * d), Math.round(4f * d), Math.round(12f * d), Math.round(6f * d));
        return tv;
    }

    private static View createSwitchRow(
            Context ctx,
            DialerGlassEngine.LiveOpticalState opt,
            boolean dark,
            float d,
            String title,
            String subtitle,
            boolean initialChecked,
            DialerGlassWidgets.IosSwitchView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Math.round(16f * d);
        int vp = Math.round(11f * d);
        row.setPadding(hp, vp, hp, vp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTextColor(DialerGlassEngine.primaryInk(dark));
        textCol.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
            s.setTextColor(DialerGlassEngine.secondaryInk(dark));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = Math.round(2f * d);
            sLp.rightMargin = Math.round(10f * d);
            textCol.addView(s, sLp);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final DialerGlassWidgets.IosSwitchView sw =
                new DialerGlassWidgets.IosSwitchView(ctx, opt, initialChecked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked(), true));
        return row;
    }

    private static View createCheckmarkRow(
            Context ctx,
            final boolean dark,
            final float d,
            String label,
            final boolean checked,
            View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hp = Math.round(16f * d);
        int vp = Math.round(13f * d);
        row.setPadding(hp, vp, hp, vp);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tv.setTypeface(Typeface.create(checked ? "sans-serif-medium" : "sans-serif",
                checked ? Typeface.BOLD : Typeface.NORMAL));
        tv.setTextColor(DialerGlassEngine.primaryInk(dark));
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (checked) {
            View chk = new View(ctx) {
                private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override
                protected void onDraw(Canvas c) {
                    IosVectorIcons.drawCheckmark(
                            c, getWidth() * 0.5f, getHeight() * 0.5f,
                            16f * d, DialerGlassEngine.accentBlue(dark), mP);
                }
            };
            row.addView(chk, new LinearLayout.LayoutParams(Math.round(22f * d), Math.round(22f * d)));
        }
        row.setOnClickListener(click);
        return row;
    }

    private static LinearLayout buildActionTile(
            Context ctx,
            DialerGlassEngine.LiveOpticalState opt,
            boolean dark,
            float d,
            String label,
            final DialerGlassWidgets.GlyphDrawer drawer,
            View.OnClickListener click) {
        DialerGlassWidgets.GlassPanelFrame frame = new DialerGlassWidgets.GlassPanelFrame(ctx, opt, 16f);
        frame.setTintBoost(0.24f);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int vp = Math.round(10f * d);
        box.setPadding(0, vp, 0, vp);

        final int blue = DialerGlassEngine.accentBlue(dark);
        View icon = new View(ctx) {
            private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                drawer.draw(c, getWidth() * 0.5f, getHeight() * 0.5f, 22f * d, blue, mP);
            }
        };
        box.addView(icon, new LinearLayout.LayoutParams(Math.round(26f * d), Math.round(26f * d)));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(blue);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = Math.round(4f * d);
        box.addView(tv, tLp);

        frame.addView(box);
        frame.setOnClickListener(v -> {
            DialerGlassEngine.performKeyboardTap(v);
            click.onClick(v);
        });

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(62f * d)));
        return wrap;
    }

    private static LinearLayout.LayoutParams tileLp(float d, boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (rightGap) {
            lp.rightMargin = Math.round(8f * d);
        }
        return lp;
    }

    private static View createSheetActionRow(
            Context ctx,
            boolean dark,
            float d,
            String text,
            int textColor,
            View.OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tv.setTextColor(textColor);
        int hp = Math.round(16f * d);
        int vp = Math.round(14f * d);
        tv.setPadding(hp, vp, hp, vp);
        tv.setOnClickListener(click);
        return tv;
    }

    private static View createHairline(Context ctx, boolean dark, float d) {
        View line = new View(ctx);
        line.setBackgroundColor(DialerGlassEngine.separatorColor(dark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.65f * d)));
        lp.leftMargin = Math.round(16f * d);
        line.setLayoutParams(lp);
        return line;
    }

    private static String callTypeLabel(int type) {
        switch (type) {
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing Call";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed Call";
            case CallLog.Calls.REJECTED_TYPE:
                return "Declined Call";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            default:
                return "Incoming Call";
        }
    }
}
