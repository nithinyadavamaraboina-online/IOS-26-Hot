package com.realme.samurai.clock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Direct-Boot-Safe Persistence Store (`ClockDataStore`) for World Clock cities, Alarms, and Timers.
 */
public final class ClockDataStore {

    private static final String PREFS_NAME = "ios26_clock_prefs";
    private static final String KEY_WORLD_CITIES = "world_cities_json";
    private static final String KEY_ALARMS = "alarms_json";
    private static final String KEY_NEXT_ALARM_ID = "next_alarm_id";

    private static final String KEY_TIMER_RUNNING = "timer_running";
    private static final String KEY_TIMER_END_EPOCH = "timer_end_epoch_ms";
    private static final String KEY_TIMER_PAUSED_REM = "timer_paused_rem_ms";
    private static final String KEY_TIMER_TOTAL_MS = "timer_total_ms";
    private static final String KEY_TIMER_LABEL = "timer_label";

    private static volatile boolean sStopwatchActive = false;
    private static volatile long sStopwatchVirtualStartEpochMs = 0L;

    private ClockDataStore() {}

    public static final class WorldCity {
        public final String cityName;
        public final String tzId;

        public WorldCity(String cityName, String tzId) {
            this.cityName = cityName;
            this.tzId = tzId;
        }
    }

    public static final class AlarmItem {
        public int id;
        public int hour24;
        public int minute;
        public String label;
        public int daysMask; // bit 0=Sun .. bit 6=Sat
        public boolean snoozeEnabled;
        public boolean enabled;

        public AlarmItem(
                int id,
                int hour24,
                int minute,
                String label,
                int daysMask,
                boolean snoozeEnabled,
                boolean enabled) {
            this.id = id;
            this.hour24 = hour24;
            this.minute = minute;
            this.label = (label != null && !label.trim().isEmpty()) ? label.trim() : "Alarm";
            this.daysMask = daysMask & 0x7F;
            this.snoozeEnabled = snoozeEnabled;
            this.enabled = enabled;
        }

        public String repeatSummary() {
            if (daysMask == 0) return "One-time";
            if (daysMask == 0x7F) return "Every day";
            if (daysMask == 0x3E) return "Weekdays";
            if (daysMask == 0x41) return "Weekends";
            String[] names = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if ((daysMask & (1 << i)) != 0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(names[i]);
                }
            }
            return sb.toString();
        }
    }

    public static final class TimerState {
        public boolean running;
        public long endEpochMs;
        public long pausedRemainingMs;
        public long totalDurationMs;
        public String label = "Timer";
    }

    private static SharedPreferences prefs(Context ctx) {
        Context storageCtx = ctx.isDeviceProtectedStorage()
                ? ctx
                : ctx.createDeviceProtectedStorageContext();
        return storageCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- World Clock ---

    public static List<WorldCity> loadWorldCities(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String raw = sp.getString(KEY_WORLD_CITIES, null);
        List<WorldCity> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new WorldCity(o.optString("city", "London"), o.optString("tz", "UTC")));
                }
                return out;
            } catch (Throwable ignored) {
            }
        }
        // Pre-populated flagship global cities
        out.add(new WorldCity("New Delhi", "Asia/Kolkata"));
        out.add(new WorldCity("Hyderabad", "Asia/Kolkata"));
        out.add(new WorldCity("Cupertino", "America/Los_Angeles"));
        out.add(new WorldCity("London", "Europe/London"));
        out.add(new WorldCity("Tokyo", "Asia/Tokyo"));
        out.add(new WorldCity("New York", "America/New_York"));
        out.add(new WorldCity("Dubai", "Asia/Dubai"));
        out.add(new WorldCity("Sydney", "Australia/Sydney"));
        saveWorldCities(ctx, out);
        return out;
    }

    public static void saveWorldCities(Context ctx, List<WorldCity> cities) {
        JSONArray arr = new JSONArray();
        try {
            for (WorldCity c : cities) {
                JSONObject o = new JSONObject();
                o.put("city", c.cityName);
                o.put("tz", c.tzId);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        prefs(ctx).edit().putString(KEY_WORLD_CITIES, arr.toString()).apply();
    }

    // --- Alarms ---

    public static int allocateAlarmId(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        int next = sp.getInt(KEY_NEXT_ALARM_ID, 1001);
        sp.edit().putInt(KEY_NEXT_ALARM_ID, next + 1).apply();
        return next;
    }

    public static List<AlarmItem> loadAlarms(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String raw = sp.getString(KEY_ALARMS, null);
        List<AlarmItem> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new AlarmItem(
                            o.optInt("id", 1001 + i),
                            o.optInt("h", 7),
                            o.optInt("m", 0),
                            o.optString("label", "Alarm"),
                            o.optInt("days", 0),
                            o.optBoolean("snooze", true),
                            o.optBoolean("enabled", false)));
                }
                sortAlarms(out);
                return out;
            } catch (Throwable ignored) {
            }
        }
        out.add(new AlarmItem(1001, 7, 0, "Wake Up", 0x3E, true, false));
        out.add(new AlarmItem(1002, 8, 30, "Morning Standup", 0x3E, true, false));
        sp.edit().putInt(KEY_NEXT_ALARM_ID, 1003).apply();
        saveAlarms(ctx, out);
        return out;
    }

    public static void saveAlarms(Context ctx, List<AlarmItem> alarms) {
        sortAlarms(alarms);
        JSONArray arr = new JSONArray();
        try {
            for (AlarmItem a : alarms) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("h", a.hour24);
                o.put("m", a.minute);
                o.put("label", a.label);
                o.put("days", a.daysMask);
                o.put("snooze", a.snoozeEnabled);
                o.put("enabled", a.enabled);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        prefs(ctx).edit().putString(KEY_ALARMS, arr.toString()).apply();
    }

    public static AlarmItem findAlarmById(Context ctx, int id) {
        for (AlarmItem a : loadAlarms(ctx)) {
            if (a.id == id) return a;
        }
        return null;
    }

    private static void sortAlarms(List<AlarmItem> list) {
        Collections.sort(list, Comparator.comparingInt(a -> a.hour24 * 60 + a.minute));
    }

    // --- Timer & Stopwatch Coordination ---

    public static TimerState loadTimerState(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        TimerState st = new TimerState();
        st.running = sp.getBoolean(KEY_TIMER_RUNNING, false);
        st.endEpochMs = sp.getLong(KEY_TIMER_END_EPOCH, 0L);
        st.pausedRemainingMs = sp.getLong(KEY_TIMER_PAUSED_REM, 0L);
        st.totalDurationMs = sp.getLong(KEY_TIMER_TOTAL_MS, 5 * 60_000L);
        st.label = sp.getString(KEY_TIMER_LABEL, "Timer");
        if (st.running && st.endEpochMs <= System.currentTimeMillis()) {
            st.running = false;
            st.pausedRemainingMs = 0L;
        }
        return st;
    }

    public static void saveTimerState(Context ctx, TimerState st) {
        prefs(ctx).edit()
                .putBoolean(KEY_TIMER_RUNNING, st.running)
                .putLong(KEY_TIMER_END_EPOCH, st.endEpochMs)
                .putLong(KEY_TIMER_PAUSED_REM, st.pausedRemainingMs)
                .putLong(KEY_TIMER_TOTAL_MS, st.totalDurationMs)
                .putString(KEY_TIMER_LABEL, st.label != null ? st.label : "Timer")
                .apply();
    }

    public static void setStopwatchBusState(boolean active, long virtualStartEpochMs) {
        sStopwatchActive = active;
        sStopwatchVirtualStartEpochMs = virtualStartEpochMs;
    }

    public static boolean isStopwatchActive() {
        return sStopwatchActive;
    }

    public static long getStopwatchVirtualStartEpochMs() {
        return sStopwatchVirtualStartEpochMs;
    }
}
