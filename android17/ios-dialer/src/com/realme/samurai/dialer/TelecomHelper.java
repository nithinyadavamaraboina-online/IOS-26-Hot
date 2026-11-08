package com.realme.samurai.dialer;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BlockedNumberContract;
import android.provider.ContactsContract;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Clean-room Telecom, Dual-SIM, Default Role, Call Filtering, Blocked Contacts,
 * Quick-Reply Templates, Voicemail, SMS, and Contact Intent bridge.
 */
public final class TelecomHelper {

    public static final int REQ_DEFAULT_DIALER_ROLE = 4101;
    public static final int REQ_RUNTIME_PERMS = 4102;
    public static final int REQ_PICK_FAVORITE_CONTACT = 4103;

    private static final String PREFS_NAME = "samurai_ios_dialer_prefs";
    private static final String KEY_LAST_OUTGOING = "last_outgoing_number";
    private static final String KEY_SELECTED_SIM_ID = "selected_sim_account_id";

    // Genuine Apple iOS 26.1 Phone Preferences (Zero Bloat)
    private static final String KEY_UNIFIED_CALLS_LAYOUT = "ios26_unified_calls_layout_v2";
    private static final String KEY_SILENCE_UNKNOWN = "ios26_silence_unknown_callers";
    private static final String KEY_FILTER_UNKNOWN_LIST = "ios26_filter_unknown_from_calls";
    private static final String KEY_KEYPAD_SOUNDS = "ios26_keypad_sounds_enabled";
    private static final String KEY_HAPTICS_ENABLED = "ios26_system_haptics_enabled";
    private static final String KEY_BLOCKED_NUMBERS = "ios26_blocked_numbers_set";
    private static final String KEY_QUICK_REPLY_0 = "ios26_quick_reply_0";
    private static final String KEY_QUICK_REPLY_1 = "ios26_quick_reply_1";
    private static final String KEY_QUICK_REPLY_2 = "ios26_quick_reply_2";

    public static final String DEFAULT_REPLY_0 = "Sorry, I can't talk right now.";
    public static final String DEFAULT_REPLY_1 = "I'm on my way.";
    public static final String DEFAULT_REPLY_2 = "Can I call you later?";

    public static final class SimSlotInfo {
        public final int slotIndex; // 1-based (1, 2)
        public final PhoneAccountHandle handle;
        public final String accountId;
        public final String carrierLabel;
        public final String shortBadge;
        public final int accentColor;

        SimSlotInfo(
                int slotIndex,
                PhoneAccountHandle handle,
                String accountId,
                String carrierLabel,
                String shortBadge,
                int accentColor) {
            this.slotIndex = slotIndex;
            this.handle = handle;
            this.accountId = accountId;
            this.carrierLabel = carrierLabel;
            this.shortBadge = shortBadge;
            this.accentColor = accentColor;
        }
    }

    private TelecomHelper() {}

    private static SharedPreferences prefs(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Context dp = app.createDeviceProtectedStorageContext();
        return dp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Genuine iOS 26.1 Phone Settings Accessors ---

    public static boolean isUnifiedCallsLayout(Context ctx) {
        if (ctx == null) return false;
        return prefs(ctx).getBoolean(KEY_UNIFIED_CALLS_LAYOUT, false);
    }

    public static void setUnifiedCallsLayout(Context ctx, boolean unified) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_UNIFIED_CALLS_LAYOUT, unified).apply();
    }

    public static boolean isSilenceUnknownCallersEnabled(Context ctx) {
        if (ctx == null) return false;
        return prefs(ctx).getBoolean(KEY_SILENCE_UNKNOWN, false);
    }

    public static void setSilenceUnknownCallersEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_SILENCE_UNKNOWN, enabled).apply();
    }

    public static boolean isFilterUnknownFromCallsEnabled(Context ctx) {
        if (ctx == null) return false;
        return prefs(ctx).getBoolean(KEY_FILTER_UNKNOWN_LIST, false);
    }

    public static void setFilterUnknownFromCallsEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_FILTER_UNKNOWN_LIST, enabled).apply();
    }

    public static boolean isKeypadSoundsEnabled(Context ctx) {
        if (ctx == null) return true;
        return prefs(ctx).getBoolean(KEY_KEYPAD_SOUNDS, true);
    }

    public static void setKeypadSoundsEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_KEYPAD_SOUNDS, enabled).apply();
    }

    public static boolean isHapticsEnabled(Context ctx) {
        if (ctx == null) return true;
        return prefs(ctx).getBoolean(KEY_HAPTICS_ENABLED, true);
    }

    public static void setHapticsEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply();
    }

    public static String[] getQuickReplies(Context ctx) {
        if (ctx == null) {
            return new String[]{DEFAULT_REPLY_0, DEFAULT_REPLY_1, DEFAULT_REPLY_2};
        }
        SharedPreferences sp = prefs(ctx);
        return new String[]{
                nonEmptyOr(sp.getString(KEY_QUICK_REPLY_0, DEFAULT_REPLY_0), DEFAULT_REPLY_0),
                nonEmptyOr(sp.getString(KEY_QUICK_REPLY_1, DEFAULT_REPLY_1), DEFAULT_REPLY_1),
                nonEmptyOr(sp.getString(KEY_QUICK_REPLY_2, DEFAULT_REPLY_2), DEFAULT_REPLY_2)
        };
    }

    public static void setQuickReply(Context ctx, int index, String text) {
        if (ctx == null || index < 0 || index > 2) return;
        String key = (index == 0) ? KEY_QUICK_REPLY_0 : (index == 1 ? KEY_QUICK_REPLY_1 : KEY_QUICK_REPLY_2);
        String def = (index == 0) ? DEFAULT_REPLY_0 : (index == 1 ? DEFAULT_REPLY_1 : DEFAULT_REPLY_2);
        prefs(ctx).edit().putString(key, nonEmptyOr(text, def)).apply();
    }

    private static String nonEmptyOr(String val, String fallback) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : fallback;
    }

    public static List<String> getBlockedNumbers(Context ctx) {
        if (ctx == null) return Collections.emptyList();
        Set<String> raw = prefs(ctx).getStringSet(KEY_BLOCKED_NUMBERS, Collections.emptySet());
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(raw);
        Collections.sort(out);
        return out;
    }

    public static boolean isLocallyBlocked(Context ctx, String rawNumber) {
        if (ctx == null || rawNumber == null) return false;
        String norm = normalizeDigitsKey(rawNumber);
        if (norm.isEmpty()) return false;
        for (String b : getBlockedNumbers(ctx)) {
            if (norm.equals(normalizeDigitsKey(b))) {
                return true;
            }
        }
        return false;
    }

    public static void setNumberBlocked(Context ctx, String rawNumber, boolean blocked) {
        if (ctx == null || rawNumber == null) return;
        String clean = sanitizeDialable(rawNumber);
        String norm = normalizeDigitsKey(clean);
        if (norm.isEmpty()) return;

        SharedPreferences sp = prefs(ctx);
        Set<String> cur = new LinkedHashSet<>(sp.getStringSet(KEY_BLOCKED_NUMBERS, Collections.emptySet()));
        cur.removeIf(existing -> norm.equals(normalizeDigitsKey(existing)));
        if (blocked) {
            cur.add(clean);
        }
        sp.edit().putStringSet(KEY_BLOCKED_NUMBERS, cur).apply();

        // Mirror into system BlockedNumberContract when held as Default Dialer
        try {
            if (BlockedNumberContract.canCurrentUserBlockNumbers(ctx)) {
                if (blocked) {
                    ContentValues cv = new ContentValues();
                    cv.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, clean);
                    ctx.getContentResolver().insert(
                            BlockedNumberContract.BlockedNumbers.CONTENT_URI, cv);
                } else {
                    BlockedNumberContract.unblock(ctx, clean);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // --- Runtime Permissions & Default Role ---

    public static boolean hasPermission(Context ctx, String perm) {
        if (ctx == null) return false;
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static void ensureRuntimePermissions(Activity activity) {
        if (activity == null) return;
        String[] wanted = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
        };
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            activity.requestPermissions(missing.toArray(new String[0]), REQ_RUNTIME_PERMS);
        }
    }

    public static boolean isDefaultDialer(Context ctx) {
        if (ctx == null) return false;
        try {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null) {
                return ctx.getPackageName().equals(tm.getDefaultDialerPackage());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void requestDefaultDialerRole(Activity activity) {
        if (activity == null) return;
        try {
            RoleManager rm = activity.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                Intent req = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                activity.startActivityForResult(req, REQ_DEFAULT_DIALER_ROLE);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent legacy = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            legacy.putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    activity.getPackageName());
            activity.startActivityForResult(legacy, REQ_DEFAULT_DIALER_ROLE);
        } catch (Throwable ignored) {
        }
    }

    public static List<SimSlotInfo> querySimAccounts(Context ctx) {
        List<SimSlotInfo> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm == null) return out;
            List<PhoneAccountHandle> handles = tm.getCallCapablePhoneAccounts();
            if (handles == null) return out;
            int idx = 1;
            for (PhoneAccountHandle h : handles) {
                if (h == null) continue;
                PhoneAccount pa = tm.getPhoneAccount(h);
                String label = (pa != null && pa.getLabel() != null && pa.getLabel().length() > 0)
                        ? pa.getLabel().toString()
                        : ("SIM " + idx);
                int color = (pa != null && pa.getHighlightColor() != 0)
                        ? pa.getHighlightColor()
                        : (idx == 1 ? DialerGlassEngine.IOS_BLUE_LIGHT : DialerGlassEngine.IOS_GREEN);
                String id = h.getId() != null ? h.getId() : String.valueOf(idx);
                out.add(new SimSlotInfo(idx, h, id, label, "S" + idx, color));
                idx++;
            }
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static int getPreferredSimIndex(Context ctx, List<SimSlotInfo> sims) {
        if (sims == null || sims.isEmpty()) return 0;
        String savedId = prefs(ctx).getString(KEY_SELECTED_SIM_ID, "");
        for (int i = 0; i < sims.size(); i++) {
            if (sims.get(i).accountId.equals(savedId)) {
                return i;
            }
        }
        return 0;
    }

    public static void setPreferredSimAccount(Context ctx, SimSlotInfo sim) {
        if (ctx == null || sim == null) return;
        prefs(ctx).edit().putString(KEY_SELECTED_SIM_ID, sim.accountId).apply();
    }

    public static void saveLastOutgoingNumber(Context ctx, String number) {
        if (ctx == null || number == null) return;
        String clean = sanitizeDialable(number);
        if (!clean.isEmpty()) {
            prefs(ctx).edit().putString(KEY_LAST_OUTGOING, clean).apply();
        }
    }

    public static String getSavedLastOutgoingNumber(Context ctx) {
        if (ctx == null) return "";
        return prefs(ctx).getString(KEY_LAST_OUTGOING, "");
    }

    public static void placeCall(
            Context ctx, String rawNumber, PhoneAccountHandle simHandle, boolean video) {
        if (ctx == null) return;
        String number = sanitizeDialable(rawNumber);
        if (number.isEmpty()) return;

        saveLastOutgoingNumber(ctx, number);

        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null && hasPermission(ctx, Manifest.permission.CALL_PHONE)) {
                if ((number.startsWith("*") || number.startsWith("#")) && number.endsWith("#")) {
                    boolean handled = (simHandle != null)
                            ? tm.handleMmi(number, simHandle)
                            : tm.handleMmi(number);
                    if (handled) {
                        return;
                    }
                }
                Uri uri = Uri.fromParts("tel", number, null);
                Bundle extras = new Bundle();
                if (simHandle != null) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle);
                }
                if (video) {
                    extras.putInt(
                            TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                            VideoProfile.STATE_BIDIRECTIONAL);
                }
                tm.placeCall(uri, extras);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Uri uri = Uri.fromParts("tel", number, null);
            String action = hasPermission(ctx, Manifest.permission.CALL_PHONE)
                    ? Intent.ACTION_CALL
                    : Intent.ACTION_DIAL;
            Intent intent = new Intent(action, uri);
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (simHandle != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle);
            }
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(ctx, "Unable to place call", Toast.LENGTH_SHORT).show();
        }
    }

    public static void callVoicemail(Context ctx, PhoneAccountHandle simHandle) {
        if (ctx == null) return;
        String vmNum = resolveVoicemailNumber(ctx, simHandle);
        if (vmNum != null && !vmNum.trim().isEmpty()) {
            placeCall(ctx, vmNum.trim(), simHandle, false);
            return;
        }
        try {
            Uri vmUri = Uri.fromParts("voicemail", "", null);
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null && hasPermission(ctx, Manifest.permission.CALL_PHONE)) {
                Bundle extras = new Bundle();
                if (simHandle != null) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle);
                }
                tm.placeCall(vmUri, extras);
                return;
            }
            Intent callVm = new Intent(Intent.ACTION_CALL, vmUri);
            if (!(ctx instanceof Activity)) {
                callVm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(callVm);
        } catch (Throwable ignored) {
            Toast.makeText(ctx, "Carrier voicemail number not configured on SIM", Toast.LENGTH_SHORT).show();
        }
    }

    public static String resolveVoicemailNumber(Context ctx, PhoneAccountHandle simHandle) {
        if (ctx == null) return "";
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null && simHandle != null) {
                String vm = tm.getVoiceMailNumber(simHandle);
                if (vm != null && !vm.trim().isEmpty()) {
                    return vm.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            TelephonyManager tel = ctx.getSystemService(TelephonyManager.class);
            if (tel != null && hasPermission(ctx, Manifest.permission.READ_PHONE_STATE)) {
                String vm = tel.getVoiceMailNumber();
                if (vm != null && !vm.trim().isEmpty()) {
                    return vm.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String resolveCarrierName(Context ctx, List<SimSlotInfo> sims, int simIdx) {
        if (sims != null && !sims.isEmpty()) {
            int idx = DialerGlassEngine.clamp(simIdx, 0, sims.size() - 1);
            String label = sims.get(idx).carrierLabel;
            if (label != null && !label.isEmpty()) return label;
        }
        try {
            TelephonyManager tel = ctx.getSystemService(TelephonyManager.class);
            if (tel != null) {
                String op = tel.getNetworkOperatorName();
                if (op != null && !op.trim().isEmpty()) {
                    return op.trim();
                }
                String simOp = tel.getSimOperatorName();
                if (simOp != null && !simOp.trim().isEmpty()) {
                    return simOp.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "Carrier Voicemail";
    }

    public static void openSmsComposer(Context ctx, String number, String bodyText) {
        if (ctx == null) return;
        String clean = sanitizeDialable(number);
        try {
            Uri uri = Uri.parse("smsto:" + Uri.encode(clean));
            Intent sms = new Intent(Intent.ACTION_SENDTO, uri);
            if (bodyText != null && !bodyText.isEmpty()) {
                sms.putExtra("sms_body", bodyText);
            }
            if (!(ctx instanceof Activity)) {
                sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(sms);
        } catch (Throwable ignored) {
            Toast.makeText(ctx, "Messages unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    public static void openInsertContact(Context ctx, String prefillNumber) {
        if (ctx == null) return;
        try {
            Intent ins = new Intent(ContactsContract.Intents.Insert.ACTION);
            ins.setType(ContactsContract.RawContacts.CONTENT_TYPE);
            if (prefillNumber != null && !prefillNumber.isEmpty()) {
                ins.putExtra(ContactsContract.Intents.Insert.PHONE, prefillNumber);
            }
            if (!(ctx instanceof Activity)) {
                ins.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(ins);
        } catch (Throwable ignored) {
            Toast.makeText(ctx, "Contacts editor unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    public static void copyToClipboard(Context ctx, String label, String text) {
        if (ctx == null || text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(ctx, "Copied " + text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String readDialableFromClipboard(Context ctx) {
        if (ctx == null) return "";
        try {
            ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).coerceToText(ctx);
                    if (cs != null) {
                        return sanitizeDialable(cs.toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String sanitizeDialable(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '+' || ch == '*' || ch == '#'
                    || ch == ',' || ch == ';') {
                sb.append(ch);
            } else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                char mapped = PhoneNumberUtils.convertKeypadLettersToDigits(String.valueOf(ch)).charAt(0);
                if (mapped >= '0' && mapped <= '9') {
                    sb.append(mapped);
                }
            }
        }
        return sb.toString();
    }

    public static String formatDisplayNumber(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.contains("*") || raw.contains("#")) {
            return raw;
        }
        try {
            String country = Locale.getDefault().getCountry();
            if (country == null || country.length() != 2) {
                country = "US";
            }
            String formatted = PhoneNumberUtils.formatNumber(raw, country);
            if (formatted != null && !formatted.isEmpty()) {
                return formatted;
            }
        } catch (Throwable ignored) {
        }
        return raw;
    }

    public static String normalizeDigitsKey(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        String digits = sb.toString();
        if (digits.length() > 10) {
            return digits.substring(digits.length() - 10);
        }
        return digits;
    }
}
