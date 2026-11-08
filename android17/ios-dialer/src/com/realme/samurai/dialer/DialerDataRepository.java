package com.realme.samurai.dialer;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.LruCache;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean-room asynchronous CallLog + Contacts + Starred Favourites + T9 Matcher + Avatar Cache.
 */
public final class DialerDataRepository {

    public interface DataListener {
        void onDataUpdated();
    }

    private static String sanitizeNullableText(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty() || "null".equalsIgnoreCase(t)) return "";
        return t;
    }

    private static String sanitizeNullableUri(String s) {
        String t = sanitizeNullableText(s);
        return t.isEmpty() ? null : t;
    }

    public static final class PhoneEntry {
        public final String number;
        public final String formattedNumber;
        public final String digitsOnly;
        public final String label;

        PhoneEntry(String number, String label) {
            this.number = sanitizeNullableText(number);
            this.formattedNumber = TelecomHelper.formatDisplayNumber(this.number);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < this.number.length(); i++) {
                char c = this.number.charAt(i);
                if (c >= '0' && c <= '9') sb.append(c);
            }
            this.digitsOnly = sb.toString();
            String cleanLbl = sanitizeNullableText(label);
            this.label = !cleanLbl.isEmpty() ? cleanLbl : "mobile";
        }
    }

    public static final class ContactItem {
        public final long contactId;
        public final String lookupKey;
        public final String displayName;
        public final String sectionLetter;
        public final String photoUri;
        public final boolean starred;
        public final String t9Digits;
        public final List<PhoneEntry> phones;

        ContactItem(
                long contactId,
                String lookupKey,
                String displayName,
                String photoUri,
                boolean starred,
                List<PhoneEntry> phones) {
            this.contactId = contactId;
            this.lookupKey = sanitizeNullableText(lookupKey);
            String cleanName = sanitizeNullableText(displayName);
            this.displayName = !cleanName.isEmpty() ? cleanName : "No Name";
            this.sectionLetter = computeSectionLetter(this.displayName);
            this.photoUri = sanitizeNullableUri(photoUri);
            this.starred = starred;
            this.t9Digits = toT9Digits(this.displayName);
            this.phones = phones != null ? phones : Collections.emptyList();
        }

        public String primaryNumber() {
            return phones.isEmpty() ? "" : phones.get(0).number;
        }

        public String primaryLabel() {
            return phones.isEmpty() ? "mobile" : phones.get(0).label;
        }
    }

    public static final class FavoriteItem {
        public final long contactId;
        public final String lookupKey;
        public final String displayName;
        public final String number;
        public final String typeLabel;
        public final String photoUri;

        FavoriteItem(
                long contactId,
                String lookupKey,
                String displayName,
                String number,
                String typeLabel,
                String photoUri) {
            this.contactId = contactId;
            this.lookupKey = lookupKey != null ? lookupKey : "";
            this.displayName = displayName != null ? displayName : number;
            this.number = number != null ? number : "";
            this.typeLabel = (typeLabel != null && !typeLabel.isEmpty()) ? typeLabel : "mobile";
            this.photoUri = photoUri;
        }
    }

    public static final class SingleCallEntry {
        public final long id;
        public final int callType;
        public final long dateMillis;
        public final long durationSec;
        public final String accountId;

        SingleCallEntry(long id, int callType, long dateMillis, long durationSec, String accountId) {
            this.id = id;
            this.callType = callType;
            this.dateMillis = dateMillis;
            this.durationSec = durationSec;
            this.accountId = accountId != null ? accountId : "";
        }
    }

    public static final class RecentCallGroup {
        public final String rawNumber;
        public final String formattedNumber;
        public final String normKey;
        public final String cachedName;
        public final String photoUri;
        public final int latestType;
        public final long latestDateMillis;
        public final String relativeDateText;
        public final String accountId;
        public final List<SingleCallEntry> entries = new ArrayList<>();

        RecentCallGroup(
                String rawNumber,
                String cachedName,
                String photoUri,
                int latestType,
                long latestDateMillis,
                String accountId) {
            this.rawNumber = sanitizeNullableText(rawNumber);
            this.formattedNumber = TelecomHelper.formatDisplayNumber(this.rawNumber);
            this.normKey = TelecomHelper.normalizeDigitsKey(this.rawNumber);
            this.cachedName = sanitizeNullableText(cachedName);
            this.photoUri = sanitizeNullableUri(photoUri);
            this.latestType = latestType;
            this.latestDateMillis = latestDateMillis;
            this.relativeDateText = formatIosRelativeDate(latestDateMillis);
            this.accountId = sanitizeNullableText(accountId);
        }

        public String displayTitle() {
            String base = !cachedName.isEmpty()
                    ? cachedName
                    : (!formattedNumber.isEmpty() ? formattedNumber : "Unknown Caller");
            if (entries.size() > 1) {
                return base + " (" + entries.size() + ")";
            }
            return base;
        }

        public String baseNameOrNumber() {
            return !cachedName.isEmpty()
                    ? cachedName
                    : (!formattedNumber.isEmpty() ? formattedNumber : "Unknown Caller");
        }

        public boolean isMissedOrRejected() {
            return latestType == CallLog.Calls.MISSED_TYPE
                    || latestType == CallLog.Calls.REJECTED_TYPE;
        }

        public boolean isOutgoing() {
            return latestType == CallLog.Calls.OUTGOING_TYPE;
        }

        public List<Long> allIds() {
            List<Long> ids = new ArrayList<>(entries.size());
            for (SingleCallEntry e : entries) {
                ids.add(e.id);
            }
            return ids;
        }
    }

    private static DialerDataRepository sInstance;

    public static synchronized DialerDataRepository get(Context ctx) {
        if (sInstance == null) {
            sInstance = new DialerDataRepository(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mAppCtx;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<DataListener> mListeners = new CopyOnWriteArrayList<>();
    private final LruCache<String, Bitmap> mPhotoCache = new LruCache<>(96);
    private final ContentObserver mContentObserver;
    private boolean mObserverRegistered = false;

    private volatile List<FavoriteItem> mFavorites = Collections.emptyList();
    private volatile List<RecentCallGroup> mRecents = Collections.emptyList();
    private volatile List<ContactItem> mContacts = Collections.emptyList();
    private volatile List<TelecomHelper.SimSlotInfo> mSims = Collections.emptyList();
    private volatile String mMyCardName = "My Card";
    private volatile String mMyCardSubtitle = "Set up your card";
    private volatile String mLastOutgoingNumber = "";
    private volatile int mMissedCount = 0;

    private DialerDataRepository(Context appCtx) {
        mAppCtx = appCtx;
        mContentObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                refreshAllAsync();
            }
        };
    }

    public void addListener(DataListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
        registerObserversIfPermitted();
        refreshAllAsync();
    }

    public void removeListener(DataListener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
        if (mListeners.isEmpty() && mObserverRegistered) {
            try {
                mAppCtx.getContentResolver().unregisterContentObserver(mContentObserver);
            } catch (Throwable ignored) {
            }
            mObserverRegistered = false;
        }
    }

    public void registerObserversIfPermitted() {
        if (mObserverRegistered) return;
        ContentResolver cr = mAppCtx.getContentResolver();
        boolean any = false;
        try {
            if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.READ_CALL_LOG)) {
                cr.registerContentObserver(CallLog.Calls.CONTENT_URI, true, mContentObserver);
                any = true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.READ_CONTACTS)) {
                cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, mContentObserver);
                any = true;
            }
        } catch (Throwable ignored) {
        }
        mObserverRegistered = any;
    }

    public List<FavoriteItem> getFavorites() {
        return mFavorites;
    }

    public List<RecentCallGroup> getRecents() {
        return mRecents;
    }

    public List<ContactItem> getContacts() {
        return mContacts;
    }

    public List<TelecomHelper.SimSlotInfo> getSims() {
        return mSims;
    }

    public String getMyCardName() {
        return mMyCardName;
    }

    public String getMyCardSubtitle() {
        return mMyCardSubtitle;
    }

    public int getMissedCount() {
        return mMissedCount;
    }

    public String getLastOutgoingNumber() {
        if (mLastOutgoingNumber != null && !mLastOutgoingNumber.isEmpty()) {
            return mLastOutgoingNumber;
        }
        return TelecomHelper.getSavedLastOutgoingNumber(mAppCtx);
    }

    public void refreshAllAsync() {
        registerObserversIfPermitted();
        mExecutor.execute(() -> {
            List<TelecomHelper.SimSlotInfo> sims = TelecomHelper.querySimAccounts(mAppCtx);
            List<ContactItem> contacts = loadContactsBg();
            List<FavoriteItem> favs = buildFavoritesFromContacts(contacts);
            RecentsResult recRes = loadRecentsBg(contacts);
            loadMyProfileBg();

            mSims = sims;
            mContacts = contacts;
            mFavorites = favs;
            mRecents = recRes.groups;
            mMissedCount = recRes.unreadMissed;
            if (!recRes.lastOutgoing.isEmpty()) {
                mLastOutgoingNumber = recRes.lastOutgoing;
                TelecomHelper.saveLastOutgoingNumber(mAppCtx, recRes.lastOutgoing);
            }
            notifyListenersMain();
        });
    }

    private void notifyListenersMain() {
        mMainHandler.post(() -> {
            for (DataListener l : mListeners) {
                l.onDataUpdated();
            }
        });
    }

    public ContactItem findContactForNumber(String rawNumber) {
        String key = TelecomHelper.normalizeDigitsKey(rawNumber);
        if (key.isEmpty()) return null;
        for (ContactItem c : mContacts) {
            for (PhoneEntry pe : c.phones) {
                String pk = TelecomHelper.normalizeDigitsKey(pe.number);
                if (!pk.isEmpty() && pk.equals(key)) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Live Keypad digit & T9 contact matcher (`2=ABC..9=WXYZ` or raw phone digits).
     */
    public ContactItem matchBestContactByKeypad(String rawDigits) {
        if (rawDigits == null || rawDigits.isEmpty()) return null;
        StringBuilder onlyDigits = new StringBuilder();
        for (int i = 0; i < rawDigits.length(); i++) {
            char c = rawDigits.charAt(i);
            if (c >= '0' && c <= '9') onlyDigits.append(c);
        }
        String q = onlyDigits.toString();
        if (q.length() < 2) return null;

        ContactItem prefixMatch = null;
        ContactItem substringMatch = null;
        for (ContactItem item : mContacts) {
            for (PhoneEntry pe : item.phones) {
                if (pe.digitsOnly.equals(q)) {
                    return item;
                }
                if (prefixMatch == null && pe.digitsOnly.startsWith(q)) {
                    prefixMatch = item;
                } else if (substringMatch == null && pe.digitsOnly.contains(q)) {
                    substringMatch = item;
                }
            }
            if (prefixMatch == null && item.t9Digits.startsWith(q)) {
                prefixMatch = item;
            } else if (substringMatch == null && item.t9Digits.contains(q)) {
                substringMatch = item;
            }
        }
        return prefixMatch != null ? prefixMatch : substringMatch;
    }

    public void setContactStarredAsync(final long contactId, final boolean starred) {
        if (contactId <= 0) return;
        mExecutor.execute(() -> {
            try {
                if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.WRITE_CONTACTS)) {
                    ContentValues cv = new ContentValues();
                    cv.put(ContactsContract.Contacts.STARRED, starred ? 1 : 0);
                    Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId);
                    mAppCtx.getContentResolver().update(uri, cv, null, null);
                }
            } catch (Throwable ignored) {
            }
            refreshAllAsync();
        });
    }

    public void starPickedContactUriAsync(final Uri contactUri) {
        if (contactUri == null) return;
        mExecutor.execute(() -> {
            try {
                if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.WRITE_CONTACTS)) {
                    ContentValues cv = new ContentValues();
                    cv.put(ContactsContract.Contacts.STARRED, 1);
                    mAppCtx.getContentResolver().update(contactUri, cv, null, null);
                }
            } catch (Throwable ignored) {
            }
            refreshAllAsync();
        });
    }

    public void deleteCallLogEntriesAsync(final List<Long> callIds) {
        if (callIds == null || callIds.isEmpty()) return;
        mExecutor.execute(() -> {
            try {
                if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.WRITE_CALL_LOG)) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < callIds.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(callIds.get(i));
                    }
                    mAppCtx.getContentResolver().delete(
                            CallLog.Calls.CONTENT_URI,
                            CallLog.Calls._ID + " IN (" + sb + ")",
                            null);
                }
            } catch (Throwable ignored) {
            }
            refreshAllAsync();
        });
    }

    public void clearAllCallLogAsync() {
        mExecutor.execute(() -> {
            try {
                if (TelecomHelper.hasPermission(mAppCtx, Manifest.permission.WRITE_CALL_LOG)) {
                    mAppCtx.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
                }
            } catch (Throwable ignored) {
            }
            refreshAllAsync();
        });
    }

    private List<ContactItem> loadContactsBg() {
        if (!TelecomHelper.hasPermission(mAppCtx, Manifest.permission.READ_CONTACTS)) {
            return Collections.emptyList();
        }
        Map<Long, ContactBuilder> map = new LinkedHashMap<>();
        String[] proj = {
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        };
        try (Cursor c = mAppCtx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    long cid = c.getLong(0);
                    String lookup = c.getString(1);
                    String name = c.getString(2);
                    String num = c.getString(3);
                    int type = c.getInt(4);
                    String customLabel = c.getString(5);
                    boolean starred = c.getInt(6) == 1;
                    String photo = c.getString(7);
                    boolean superPrimary = c.getInt(8) != 0;

                    if (num == null || num.trim().isEmpty()) continue;
                    String typeStr = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            mAppCtx.getResources(), type, customLabel).toString().toLowerCase(Locale.US);

                    ContactBuilder b = map.get(cid);
                    if (b == null) {
                        b = new ContactBuilder(cid, lookup, name, photo, starred);
                        map.put(cid, b);
                    }
                    b.addPhone(num.trim(), typeStr, superPrimary);
                }
            }
        } catch (Throwable ignored) {
        }

        List<ContactItem> list = new ArrayList<>(map.size());
        for (ContactBuilder b : map.values()) {
            list.add(b.build());
        }
        return list;
    }

    private static List<FavoriteItem> buildFavoritesFromContacts(List<ContactItem> contacts) {
        List<FavoriteItem> out = new ArrayList<>();
        for (ContactItem c : contacts) {
            if (c.starred && !c.phones.isEmpty()) {
                out.add(new FavoriteItem(
                        c.contactId,
                        c.lookupKey,
                        c.displayName,
                        c.primaryNumber(),
                        c.primaryLabel(),
                        c.photoUri));
            }
        }
        return out;
    }

    private void loadMyProfileBg() {
        if (!TelecomHelper.hasPermission(mAppCtx, Manifest.permission.READ_CONTACTS)) {
            return;
        }
        String[] proj = {
                ContactsContract.Profile.DISPLAY_NAME_PRIMARY
        };
        try (Cursor c = mAppCtx.getContentResolver().query(
                ContactsContract.Profile.CONTENT_URI, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    mMyCardName = name.trim();
                    mMyCardSubtitle = "My Card";
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        mMyCardName = "My Card";
        mMyCardSubtitle = "Set up your card";
    }

    private static final class RecentsResult {
        final List<RecentCallGroup> groups;
        final String lastOutgoing;
        final int unreadMissed;

        RecentsResult(List<RecentCallGroup> groups, String lastOutgoing, int unreadMissed) {
            this.groups = groups;
            this.lastOutgoing = lastOutgoing;
            this.unreadMissed = unreadMissed;
        }
    }

    private RecentsResult loadRecentsBg(List<ContactItem> contacts) {
        if (!TelecomHelper.hasPermission(mAppCtx, Manifest.permission.READ_CALL_LOG)) {
            return new RecentsResult(Collections.emptyList(), "", 0);
        }
        Map<String, ContactItem> byKey = new LinkedHashMap<>();
        for (ContactItem ci : contacts) {
            for (PhoneEntry pe : ci.phones) {
                String k = TelecomHelper.normalizeDigitsKey(pe.number);
                if (!k.isEmpty() && !byKey.containsKey(k)) {
                    byKey.put(k, ci);
                }
            }
        }

        List<RecentCallGroup> groups = new ArrayList<>();
        String lastOutgoing = "";
        int unreadMissed = 0;

        String[] proj = {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID,
                CallLog.Calls.CACHED_PHOTO_URI,
                CallLog.Calls.IS_READ
        };

        try (Cursor c = mAppCtx.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                proj,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (c != null) {
                int count = 0;
                RecentCallGroup prevGroup = null;
                while (c.moveToNext() && count < 250) {
                    count++;
                    long id = c.getLong(0);
                    String num = c.getString(1);
                    String cachedName = c.getString(2);
                    int type = c.getInt(3);
                    long date = c.getLong(4);
                    long dur = c.getLong(5);
                    String accId = c.getString(6);
                    String photoUri = c.getString(7);
                    int isRead = c.isNull(8) ? 1 : c.getInt(8);

                    if (type == CallLog.Calls.OUTGOING_TYPE && lastOutgoing.isEmpty()
                            && num != null && !num.trim().isEmpty()) {
                        lastOutgoing = num.trim();
                    }
                    if (type == CallLog.Calls.MISSED_TYPE && isRead == 0) {
                        unreadMissed++;
                    }

                    String normKey = TelecomHelper.normalizeDigitsKey(num);
                    ContactItem matched = !normKey.isEmpty() ? byKey.get(normKey) : null;
                    if (matched != null) {
                        cachedName = matched.displayName;
                        if (matched.photoUri != null) {
                            photoUri = matched.photoUri;
                        }
                    }

                    boolean missedBucket = (type == CallLog.Calls.MISSED_TYPE
                            || type == CallLog.Calls.REJECTED_TYPE);
                    if (prevGroup != null
                            && !normKey.isEmpty()
                            && normKey.equals(prevGroup.normKey)
                            && missedBucket == prevGroup.isMissedOrRejected()) {
                        prevGroup.entries.add(new SingleCallEntry(id, type, date, dur, accId));
                    } else {
                        RecentCallGroup g = new RecentCallGroup(
                                num, cachedName, photoUri, type, date, accId);
                        g.entries.add(new SingleCallEntry(id, type, date, dur, accId));
                        groups.add(g);
                        prevGroup = g;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new RecentsResult(groups, lastOutgoing, unreadMissed);
    }

    // Avatar & Gradient Monogram Renderer
    private static final int[][] MONOGRAM_GRADIENTS = {
            {0xFF5AC8FA, 0xFF007AFF},
            {0xFF34C759, 0xFF248A3D},
            {0xFFFF9F0A, 0xFFFF6B00},
            {0xFFBF5AF2, 0xFF8944AB},
            {0xFFFF375F, 0xFFD70015},
            {0xFF64D2FF, 0xFF0A84FF},
            {0xFF8E8E93, 0xFF636366}
    };

    public Bitmap getCachedOrLoadPhoto(final String photoUri, final Runnable onLoadedUi) {
        if (photoUri == null || photoUri.isEmpty()) return null;
        Bitmap cached = mPhotoCache.get(photoUri);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        mExecutor.execute(() -> {
            if (mPhotoCache.get(photoUri) != null) return;
            try (InputStream is = mAppCtx.getContentResolver().openInputStream(Uri.parse(photoUri))) {
                if (is != null) {
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    if (bmp != null) {
                        mPhotoCache.put(photoUri, bmp);
                        if (onLoadedUi != null) {
                            mMainHandler.post(onLoadedUi);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        });
        return null;
    }

    public void drawAvatar(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            String name,
            String photoUri,
            Paint workPaint,
            Runnable onPhotoLoaded) {
        Bitmap photo = getCachedOrLoadPhoto(photoUri, onPhotoLoaded);
        if (photo != null && !photo.isRecycled()) {
            workPaint.reset();
            workPaint.setAntiAlias(true);
            BitmapShader bs = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float scale = (radius * 2f) / Math.min(photo.getWidth(), photo.getHeight());
            m.setScale(scale, scale);
            m.postTranslate(cx - photo.getWidth() * scale * 0.5f, cy - photo.getHeight() * scale * 0.5f);
            bs.setLocalMatrix(m);
            workPaint.setShader(bs);
            canvas.drawCircle(cx, cy, radius, workPaint);
            workPaint.setShader(null);
            return;
        }

        String initials = extractInitials(name);
        int hash = name != null ? Math.abs(name.hashCode()) : 0;
        int[] grad = initials.isEmpty()
                ? MONOGRAM_GRADIENTS[6]
                : MONOGRAM_GRADIENTS[hash % (MONOGRAM_GRADIENTS.length - 1)];

        workPaint.reset();
        workPaint.setAntiAlias(true);
        workPaint.setShader(new LinearGradient(
                cx, cy - radius, cx, cy + radius,
                grad[0], grad[1], Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, workPaint);
        workPaint.setShader(null);

        if (!initials.isEmpty()) {
            workPaint.setColor(Color.WHITE);
            workPaint.setTextAlign(Paint.Align.CENTER);
            workPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            workPaint.setTextSize(radius * 0.84f);
            Paint.FontMetrics fm = workPaint.getFontMetrics();
            canvas.drawText(initials, cx, cy - (fm.ascent + fm.descent) * 0.5f, workPaint);
        } else {
            IosVectorIcons.drawTabIcon(
                    canvas, IosVectorIcons.TAB_CONTACTS, cx, cy, radius * 1.35f,
                    Color.argb(225, 255, 255, 255), true, workPaint);
        }
    }

    public static String extractInitials(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "";
        StringBuilder letters = new StringBuilder();
        String[] parts = trimmed.split("\\s+");
        for (String p : parts) {
            if (!p.isEmpty()) {
                char c = p.charAt(0);
                if (Character.isLetter(c)) {
                    letters.append(Character.toUpperCase(c));
                    if (letters.length() == 2) break;
                }
            }
        }
        return letters.toString();
    }

    private static String computeSectionLetter(String name) {
        if (name != null && !name.isEmpty()) {
            char c = Character.toUpperCase(name.charAt(0));
            if (c >= 'A' && c <= 'Z') {
                return String.valueOf(c);
            }
        }
        return "#";
    }

    private static String toT9Digits(String text) {
        if (text == null || text.isEmpty()) return "";
        String mapped = PhoneNumberUtils.convertKeypadLettersToDigits(text);
        StringBuilder sb = new StringBuilder(mapped.length());
        for (int i = 0; i < mapped.length(); i++) {
            char c = mapped.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String formatIosRelativeDate(long millis) {
        if (millis <= 0L) return "";
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(millis);

        if (sameDay(now, target)) {
            return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(millis));
        }
        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(yesterday, target)) {
            return "Yesterday";
        }
        long diffMs = now.getTimeInMillis() - millis;
        if (diffMs > 0L && diffMs < 6L * 24L * 3600L * 1000L) {
            return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(millis));
        }
        return new SimpleDateFormat("M/d/yy", Locale.getDefault()).format(new Date(millis));
    }

    public static String formatDurationHuman(long seconds) {
        if (seconds <= 0L) return "0 sec";
        long hrs = seconds / 3600L;
        long mins = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hrs > 0L) {
            return hrs + " hr " + mins + " min";
        }
        if (mins > 0L) {
            return mins + " min " + secs + " sec";
        }
        return secs + " sec";
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static final class ContactBuilder {
        final long id;
        final String lookup;
        final String name;
        String photo;
        boolean starred;
        final List<PhoneEntry> phones = new ArrayList<>();

        ContactBuilder(long id, String lookup, String name, String photo, boolean starred) {
            this.id = id;
            this.lookup = lookup;
            this.name = name;
            this.photo = photo;
            this.starred = starred;
        }

        void addPhone(String num, String label, boolean superPrimary) {
            String key = TelecomHelper.normalizeDigitsKey(num);
            for (PhoneEntry existing : phones) {
                if (!key.isEmpty() && key.equals(TelecomHelper.normalizeDigitsKey(existing.number))) {
                    return;
                }
            }
            PhoneEntry pe = new PhoneEntry(num, label);
            if (superPrimary) {
                phones.add(0, pe);
            } else {
                phones.add(pe);
            }
        }

        ContactItem build() {
            return new ContactItem(id, lookup, name, photo, starred, phones);
        }
    }
}
