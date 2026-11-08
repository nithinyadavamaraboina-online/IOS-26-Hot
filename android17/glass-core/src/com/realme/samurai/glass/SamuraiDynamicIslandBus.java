package com.realme.samurai.glass;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Lock-free cross-process Live Activity bus for the iOS 26 Dynamic Island (`SystemUI`).
 *
 * Privileged/system apps (`IosClock`, `IosDialer`, `IosSettings`, `SystemUI`) publish structured
 * pipe-delimited state records into `Settings.Global` (`samurai_di_*`), waking `DynamicIslandView`
 * via `ContentObserver` in `< 1.5 ms` with automatic TTL/epoch expiration.
 *
 * Format: `active(1/0)|type|title|subtitle|targetEpochMs|accentArgb|packageName|updatedEpochMs`
 */
public final class SamuraiDynamicIslandBus {

    public static final String KEY_DI_CALL = "samurai_di_call_state";
    public static final String KEY_DI_TIMER = "samurai_di_timer_state";
    public static final String KEY_DI_TOAST = "samurai_di_toast_state";

    public static final String TYPE_CALL = "CALL";
    public static final String TYPE_TIMER = "TIMER";
    public static final String TYPE_STOPWATCH = "STOPWATCH";
    public static final String TYPE_CHARGING = "CHARGING";
    public static final String TYPE_RINGER = "RINGER";
    public static final String TYPE_MEDIA = "MEDIA";

    private SamuraiDynamicIslandBus() {}

    public static final class ActivityRecord {
        public boolean active;
        public String type = "";
        public String title = "";
        public String subtitle = "";
        public long targetEpochMs;
        public int accentColor = 0xFF34C759;
        public String packageName = "";
        public long updatedEpochMs;
    }

    public static void publishCall(
            Context ctx,
            boolean active,
            String callerName,
            String stateSubtitle,
            long callStartEpochMs,
            String pkg) {
        publish(
                ctx,
                KEY_DI_CALL,
                active,
                TYPE_CALL,
                callerName,
                stateSubtitle,
                callStartEpochMs,
                0xFF30D158,
                pkg);
    }

    public static void publishTimer(
            Context ctx,
            boolean active,
            boolean isStopwatch,
            String label,
            long targetOrStartEpochMs,
            String pkg) {
        publish(
                ctx,
                KEY_DI_TIMER,
                active,
                isStopwatch ? TYPE_STOPWATCH : TYPE_TIMER,
                label,
                isStopwatch ? "Stopwatch" : "Timer",
                targetOrStartEpochMs,
                0xFFFF9F0A,
                pkg);
    }

    public static void publishToast(
            Context ctx,
            String type,
            String title,
            String subtitle,
            int accentColor,
            long durationMs) {
        long expireAt = System.currentTimeMillis() + Math.max(1200L, durationMs);
        publish(ctx, KEY_DI_TOAST, true, type, title, subtitle, expireAt, accentColor, "android");
    }

    public static void clear(Context ctx, String key) {
        if (ctx == null) return;
        try {
            Settings.Global.putString(ctx.getContentResolver(), key, "0||||0|0||0");
        } catch (Throwable ignored) {
        }
    }

    private static void publish(
            Context ctx,
            String key,
            boolean active,
            String type,
            String title,
            String subtitle,
            long targetEpochMs,
            int accentColor,
            String pkg) {
        if (ctx == null) return;
        String payload = (active ? "1" : "0")
                + "|" + sanitize(type)
                + "|" + sanitize(title)
                + "|" + sanitize(subtitle)
                + "|" + targetEpochMs
                + "|" + accentColor
                + "|" + sanitize(pkg)
                + "|" + System.currentTimeMillis();
        try {
            Settings.Global.putString(ctx.getContentResolver(), key, payload);
        } catch (Throwable ignored) {
        }
    }

    public static ActivityRecord read(Context ctx, String key) {
        ActivityRecord rec = new ActivityRecord();
        if (ctx == null) return rec;
        try {
            String raw = Settings.Global.getString(ctx.getContentResolver(), key);
            if (raw == null || raw.isEmpty()) return rec;
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 8) return rec;
            rec.active = "1".equals(parts[0]);
            rec.type = parts[1];
            rec.title = parts[2];
            rec.subtitle = parts[3];
            rec.targetEpochMs = Long.parseLong(parts[4]);
            rec.accentColor = Integer.parseInt(parts[5]);
            rec.packageName = parts[6];
            rec.updatedEpochMs = Long.parseLong(parts[7]);

            long now = System.currentTimeMillis();
            if (KEY_DI_TOAST.equals(key) && now > rec.targetEpochMs) {
                rec.active = false;
            } else if (now - rec.updatedEpochMs > 12L * 3600_000L) {
                rec.active = false;
            }
        } catch (Throwable ignored) {
            rec.active = false;
        }
        return rec;
    }

    public static ContentObserver observe(Context ctx, Runnable onChanged) {
        if (ctx == null || onChanged == null) return null;
        ContentResolver cr = ctx.getContentResolver();
        ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                onChanged.run();
            }
        };
        try {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_DI_CALL), false, obs);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_DI_TIMER), false, obs);
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_DI_TOAST), false, obs);
        } catch (Throwable ignored) {
        }
        return obs;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('|', ' ').replace('\n', ' ').trim();
    }
}
