package com.realme.samurai.calculator;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent Calculation History Tape store (`HistoryStore`) backed by `SharedPreferences`.
 */
public final class HistoryStore {

    private static final String PREF_NAME = "samurai_ios26_calc_history";
    private static final String KEY_TAPE = "tape_json";
    private static final int MAX_ITEMS = 50;

    public static final class Entry {
        public final String expression;
        public final String rawExpression;
        public final String result;
        public final long epochMs;

        public Entry(String expression, String rawExpression, String result, long epochMs) {
            this.expression = expression;
            this.rawExpression = rawExpression;
            this.result = result;
            this.epochMs = epochMs;
        }
    }

    private HistoryStore() {}

    public static List<Entry> load(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_TAPE, null);
        if (raw == null || raw.isEmpty()) {
            // Seed one subtle example calculation on very first boot if never cleared
            if (!sp.getBoolean("seeded_v1", false)) {
                Entry seed = new Entry("1,240 \u00D7 18%", "1240\u00D718%", "223.2", System.currentTimeMillis());
                out.add(seed);
                saveInternal(sp, out);
                sp.edit().putBoolean("seeded_v1", true).apply();
            }
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(new Entry(
                            o.optString("expr", ""),
                            o.optString("raw", ""),
                            o.optString("res", "0"),
                            o.optLong("ts", 0L)));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void append(Context ctx, String displayExpr, String rawExpr, String result) {
        if (ctx == null) return;
        List<Entry> list = load(ctx);
        list.add(0, new Entry(displayExpr, rawExpr, result, System.currentTimeMillis()));
        while (list.size() > MAX_ITEMS) {
            list.remove(list.size() - 1);
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        saveInternal(sp, list);
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_TAPE, "[]").putBoolean("seeded_v1", true).apply();
    }

    private static void saveInternal(SharedPreferences sp, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("expr", e.expression);
                o.put("raw", e.rawExpression);
                o.put("res", e.result);
                o.put("ts", e.epochMs);
                arr.put(o);
            }
            sp.edit().putString(KEY_TAPE, arr.toString()).putBoolean("seeded_v1", true).apply();
        } catch (Throwable ignored) {
        }
    }
}
