package com.realme.samurai.styler;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified system-wide synchronization bridge for iOS Styler (`com.realme.samurai.styler`).
 *
 * Writes simultaneously to BOTH `Settings.Secure` and `Settings.Global` (plus `Settings.System` for
 * 90Hz display cadence, `OverlayManager` for the navigation bar pill, `/data/ota_package/hw/glass.prop`,
 * and `/data/ota_package/hw/queue/` for `samurai_hw_daemon` OLED DC Dimming) so Pixel Launcher
 * (`SamuraiLauncherHooks`), `SamuraiParts`, and SystemUI respond in real time with zero lag.
 *
 * Canonical Settings Keys:
 * - Icon Mode (`1`=Default, `2`=Dark, `3`=Clear):
 *     Secure & Global: `samurai_ios26_icon_mode`
 * - Liquid Glass Style (`0`=Clear Refractive, `1`=Frosted Tinted, `2`=Solid Matte):
 *     Secure: `samurai_ios26_glass_mode` (+ `samurai_liquid_glass_mode`)
 *     Global: `samurai_liquid_glass_mode` (+ `samurai_ios26_glass_mode`)
 * - Refraction Strength (`0..150`, default `20`):
 *     Secure & Global: `samurai_ios26_refraction_pct`
 * - Glass Blur / Frost (`0..150`, default `20`):
 *     Secure & Global: `samurai_ios26_frost_pct`
 * - Wallpaper Brightness / Depth (`0..100`, default `70`):
 *     Secure & Global: `samurai_ios26_wp_brightness_pct`
 * - "Q Search" Spotlight Capsule (`1`=ON default, `0`=OFF):
 *     Secure: `samurai_ios26_search_pill` (+ `samurai_search_pill_enabled`)
 *     Global: `samurai_search_pill_enabled` (+ `samurai_ios26_search_pill`)
 * - Bottom Home Bar Gesture Line (`1`=ON default on `ios_beta`, `0`=OFF):
 *     Secure: `samurai_ios26_gesture_pill` (+ `samurai_gesture_pill_enabled`)
 *     Global: `samurai_gesture_pill_enabled` (+ `samurai_ios26_gesture_pill`)
 * - Wallpaper Change Sequence:
 *     Secure: `samurai_ios26_wallpaper_seq` (+ `samurai_wallpaper_seq`)
 *     Global: `samurai_wallpaper_seq` (+ `samurai_ios26_wallpaper_seq`)
 * - 90Hz ProMotion Lock:
 *     System: `peak_refresh_rate`, `min_refresh_rate`
 *     Secure: `samurai_lock_90hz`
 * - OLED Hardware DC Dimming:
 *     Secure & Global: `samurai_dc_dimming` + `/data/ota_package/hw/queue` `DC_DIM=1/0`
 */
public final class StylerPrefsBridge {
    public static final int ICON_DEFAULT = 1;
    public static final int ICON_DARK = 2;
    public static final int ICON_CLEAR = 3;

    public static final int GLASS_CLEAR_REFRACTIVE = 0;
    public static final int GLASS_FROSTED_TINTED = 1;
    public static final int GLASS_SOLID_MATTE = 2;

    public static final int WP_SKY_GLASS = 0;
    public static final int WP_MIDNIGHT_GLASS = 1;
    public static final int WP_AURORA_CYAN = 2;

    // Primary Settings.Secure keys
    public static final String SECURE_ICON_MODE = "samurai_ios26_icon_mode";
    public static final String SECURE_GLASS_MODE = "samurai_ios26_glass_mode";
    public static final String SECURE_REFRACTION_PCT = "samurai_ios26_refraction_pct";
    public static final String SECURE_FROST_PCT = "samurai_ios26_frost_pct";
    public static final String SECURE_WP_BRIGHTNESS_PCT = "samurai_ios26_wp_brightness_pct";
    public static final int DEFAULT_REFRACTION_PCT = 20;
    public static final int DEFAULT_FROST_PCT = 20;
    public static final int DEFAULT_WP_BRIGHTNESS_PCT = 70;
    public static final String SECURE_SEARCH_PILL = "samurai_ios26_search_pill";
    public static final String SECURE_GESTURE_PILL = "samurai_ios26_gesture_pill";
    public static final String SECURE_WALLPAPER_SEQ = "samurai_ios26_wallpaper_seq";
    public static final String SECURE_WALLPAPER_PRESET = "samurai_ios26_wallpaper_preset";
    public static final String SECURE_DC_DIMMING = "samurai_dc_dimming";
    public static final String SECURE_LOCK_90HZ = "samurai_lock_90hz";

    // Cross-process Settings.Global keys consumed by SamuraiLauncherHooks & SamuraiGlassPrefs
    public static final String GLOBAL_GLASS_MODE = "samurai_liquid_glass_mode";
    public static final String GLOBAL_ICON_MODE = "samurai_ios26_icon_mode";
    public static final String GLOBAL_SEARCH_PILL = "samurai_search_pill_enabled";
    public static final String GLOBAL_GESTURE_PILL = "samurai_gesture_pill_enabled";
    public static final String GLOBAL_WALLPAPER_SEQ = "samurai_wallpaper_seq";

    private static final String HIDE_PILL_OVERLAY_PKG = "com.realme.samurai.overlay.hidepill";
    private static final String PREFS_NAME = "samurai_ios_styler_prefs";
    private static final String GLASS_PROP_PATH = "/data/ota_package/hw/glass.prop";
    private static final String HW_QUEUE_DIR = "/data/ota_package/hw/queue";
    private static final String TELEMETRY_PATH = "/data/ota_package/hw/telemetry.prop";
    private static final AtomicLong CMD_SEQ = new AtomicLong(1L);

    private StylerPrefsBridge() {}

    private static SharedPreferences prefs(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Context dp = app.createDeviceProtectedStorageContext();
        return dp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getIconMode(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, GLOBAL_ICON_MODE, -1);
        if (glb >= ICON_DEFAULT && glb <= ICON_CLEAR) {
            return glb;
        }
        int sec = getSecureInt(cr, SECURE_ICON_MODE, -1);
        if (sec >= ICON_DEFAULT && sec <= ICON_CLEAR) {
            return sec;
        }
        int saved = prefs(ctx).getInt(SECURE_ICON_MODE, -1);
        if (saved >= ICON_DEFAULT && saved <= ICON_CLEAR) {
            return saved;
        }
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? ICON_DARK : ICON_DEFAULT;
    }

    public static void setIconMode(Context ctx, int mode) {
        int clean = clamp(mode, ICON_DEFAULT, ICON_CLEAR);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_ICON_MODE, clean);
        putGlobalInt(cr, GLOBAL_ICON_MODE, clean);
        prefs(ctx).edit().putInt(SECURE_ICON_MODE, clean).apply();
        writeGlassProp(ctx);
    }

    public static int getGlassMode(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, GLOBAL_GLASS_MODE, -1);
        if (glb >= GLASS_CLEAR_REFRACTIVE && glb <= GLASS_SOLID_MATTE) {
            return glb;
        }
        int glbAlt = getGlobalInt(cr, SECURE_GLASS_MODE, -1);
        if (glbAlt >= GLASS_CLEAR_REFRACTIVE && glbAlt <= GLASS_SOLID_MATTE) {
            return glbAlt;
        }
        int sec = getSecureInt(cr, SECURE_GLASS_MODE, -1);
        if (sec >= GLASS_CLEAR_REFRACTIVE && sec <= GLASS_SOLID_MATTE) {
            return sec;
        }
        int secAlt = getSecureInt(cr, GLOBAL_GLASS_MODE, -1);
        if (secAlt >= GLASS_CLEAR_REFRACTIVE && secAlt <= GLASS_SOLID_MATTE) {
            return secAlt;
        }
        return clamp(prefs(ctx).getInt(SECURE_GLASS_MODE, GLASS_CLEAR_REFRACTIVE),
                GLASS_CLEAR_REFRACTIVE, GLASS_SOLID_MATTE);
    }

    public static void setGlassMode(Context ctx, int mode) {
        int clean = clamp(mode, GLASS_CLEAR_REFRACTIVE, GLASS_SOLID_MATTE);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_GLASS_MODE, clean);
        putSecureInt(cr, GLOBAL_GLASS_MODE, clean);
        putGlobalInt(cr, GLOBAL_GLASS_MODE, clean);
        putGlobalInt(cr, SECURE_GLASS_MODE, clean);
        prefs(ctx).edit().putInt(SECURE_GLASS_MODE, clean).apply();
        writeGlassProp(ctx);
    }

    public static int getRefractionPct(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, SECURE_REFRACTION_PCT, -1);
        if (glb >= 0 && glb <= 150) {
            return glb;
        }
        int sec = getSecureInt(cr, SECURE_REFRACTION_PCT, -1);
        if (sec >= 0 && sec <= 150) {
            return sec;
        }
        return clamp(prefs(ctx).getInt(SECURE_REFRACTION_PCT, DEFAULT_REFRACTION_PCT), 0, 150);
    }

    public static void setRefractionPct(Context ctx, int pct) {
        int clean = clamp(pct, 0, 150);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_REFRACTION_PCT, clean);
        putGlobalInt(cr, SECURE_REFRACTION_PCT, clean);
        prefs(ctx).edit().putInt(SECURE_REFRACTION_PCT, clean).apply();
        writeGlassProp(ctx);
    }

    public static int getFrostPct(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, SECURE_FROST_PCT, -1);
        if (glb >= 0 && glb <= 150) {
            return glb;
        }
        int sec = getSecureInt(cr, SECURE_FROST_PCT, -1);
        if (sec >= 0 && sec <= 150) {
            return sec;
        }
        return clamp(prefs(ctx).getInt(SECURE_FROST_PCT, DEFAULT_FROST_PCT), 0, 150);
    }

    public static void setFrostPct(Context ctx, int pct) {
        int clean = clamp(pct, 0, 150);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_FROST_PCT, clean);
        putGlobalInt(cr, SECURE_FROST_PCT, clean);
        prefs(ctx).edit().putInt(SECURE_FROST_PCT, clean).apply();
        writeGlassProp(ctx);
    }

    public static int getWallpaperBrightnessPct(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, SECURE_WP_BRIGHTNESS_PCT, -1);
        if (glb >= 0 && glb <= 100) {
            return glb;
        }
        int sec = getSecureInt(cr, SECURE_WP_BRIGHTNESS_PCT, -1);
        if (sec >= 0 && sec <= 100) {
            return sec;
        }
        return clamp(prefs(ctx).getInt(SECURE_WP_BRIGHTNESS_PCT, DEFAULT_WP_BRIGHTNESS_PCT), 0, 100);
    }

    public static void setWallpaperBrightnessPct(Context ctx, int pct) {
        int clean = clamp(pct, 0, 100);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_WP_BRIGHTNESS_PCT, clean);
        putGlobalInt(cr, SECURE_WP_BRIGHTNESS_PCT, clean);
        prefs(ctx).edit().putInt(SECURE_WP_BRIGHTNESS_PCT, clean).apply();
        writeGlassProp(ctx);
    }

    public static int computeWallpaperDimAlpha(int pct) {
        int clean = clamp(pct, 0, 100);
        if (clean >= 100) return 0;
        if (clean <= 0) return 255;
        float s = clean / 100.0f;
        float trans = (float) Math.pow(s, 1.65);
        int keepByte = clamp(Math.round(trans * 255.0f), 0, 255);
        return 255 - keepByte;
    }

    public static float getEffectiveWallpaperTransmission(int pct) {
        return (255 - computeWallpaperDimAlpha(pct)) / 255.0f;
    }

    public static boolean isSearchPillEnabled(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, GLOBAL_SEARCH_PILL, -1);
        if (glb == 0 || glb == 1) {
            return glb == 1;
        }
        int sec = getSecureInt(cr, SECURE_SEARCH_PILL, -1);
        if (sec == 0 || sec == 1) {
            return sec == 1;
        }
        return prefs(ctx).getBoolean(SECURE_SEARCH_PILL, true);
    }

    public static void setSearchPillEnabled(Context ctx, boolean enabled) {
        int v = enabled ? 1 : 0;
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_SEARCH_PILL, v);
        putSecureInt(cr, GLOBAL_SEARCH_PILL, v);
        putGlobalInt(cr, GLOBAL_SEARCH_PILL, v);
        putGlobalInt(cr, SECURE_SEARCH_PILL, v);
        prefs(ctx).edit().putBoolean(SECURE_SEARCH_PILL, enabled).apply();
        writeGlassProp(ctx);
    }

    public static boolean isGesturePillEnabled(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int glb = getGlobalInt(cr, GLOBAL_GESTURE_PILL, -1);
        if (glb == 0 || glb == 1) {
            return glb == 1;
        }
        int glbAlt = getGlobalInt(cr, SECURE_GESTURE_PILL, -1);
        if (glbAlt == 0 || glbAlt == 1) {
            return glbAlt == 1;
        }
        int sec = getSecureInt(cr, SECURE_GESTURE_PILL, -1);
        if (sec == 0 || sec == 1) {
            return sec == 1;
        }
        int secAlt = getSecureInt(cr, GLOBAL_GESTURE_PILL, -1);
        if (secAlt == 0 || secAlt == 1) {
            return secAlt == 1;
        }
        return prefs(ctx).getBoolean(SECURE_GESTURE_PILL, true);
    }

    public static void setGesturePillEnabled(Context ctx, boolean enabled) {
        int v = enabled ? 1 : 0;
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_GESTURE_PILL, v);
        putSecureInt(cr, GLOBAL_GESTURE_PILL, v);
        putGlobalInt(cr, GLOBAL_GESTURE_PILL, v);
        putGlobalInt(cr, SECURE_GESTURE_PILL, v);
        prefs(ctx).edit().putBoolean(SECURE_GESTURE_PILL, enabled).apply();
        // IosStyler ships exclusively on ios_beta where SamuraiIos26HomeBar renders the 138dpx5dp
        // iOS Home Indicator; always keep stock Android's 72dpx4dp SystemUI handle hidden.
        applyHidePillOverlay(ctx, true);
        writeGlassProp(ctx);
    }

    public static int getWallpaperPreset(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int sec = getSecureInt(cr, SECURE_WALLPAPER_PRESET, -1);
        if (sec >= WP_SKY_GLASS && sec <= WP_AURORA_CYAN) {
            return sec;
        }
        return clamp(prefs(ctx).getInt(SECURE_WALLPAPER_PRESET, WP_SKY_GLASS),
                WP_SKY_GLASS, WP_AURORA_CYAN);
    }

    public static void setWallpaperPresetAndBumpSeq(Context ctx, int preset) {
        int clean = clamp(preset, WP_SKY_GLASS, WP_AURORA_CYAN);
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_WALLPAPER_PRESET, clean);
        prefs(ctx).edit().putInt(SECURE_WALLPAPER_PRESET, clean).apply();

        int curGlobal = getGlobalInt(cr, GLOBAL_WALLPAPER_SEQ, 0);
        int curSecure = getSecureInt(cr, SECURE_WALLPAPER_SEQ, 0);
        int next = Math.max(curGlobal, curSecure) + 1;
        putSecureInt(cr, SECURE_WALLPAPER_SEQ, next);
        putSecureInt(cr, GLOBAL_WALLPAPER_SEQ, next);
        putGlobalInt(cr, GLOBAL_WALLPAPER_SEQ, next);
        putGlobalInt(cr, SECURE_WALLPAPER_SEQ, next);
    }

    public static boolean isLock90HzEnabled(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            float minRate = Settings.System.getFloat(cr, "min_refresh_rate", 90.0f);
            float peakRate = Settings.System.getFloat(cr, "peak_refresh_rate", 90.0f);
            return minRate >= 88.0f && peakRate >= 88.0f;
        } catch (Throwable ignored) {
        }
        int sec = getSecureInt(cr, SECURE_LOCK_90HZ, -1);
        if (sec == 0 || sec == 1) {
            return sec == 1;
        }
        return prefs(ctx).getBoolean(SECURE_LOCK_90HZ, true);
    }

    public static void setLock90HzEnabled(Context ctx, boolean locked90Hz) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            Settings.System.putFloat(cr, "peak_refresh_rate", 90.0f);
            Settings.System.putFloat(cr, "min_refresh_rate", locked90Hz ? 90.0f : 60.0f);
        } catch (Throwable ignored) {
        }
        putSecureInt(cr, SECURE_LOCK_90HZ, locked90Hz ? 1 : 0);
        prefs(ctx).edit().putBoolean(SECURE_LOCK_90HZ, locked90Hz).apply();
    }

    public static boolean isDcDimmingEnabled(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        int sec = getSecureInt(cr, SECURE_DC_DIMMING, -1);
        if (sec == 0 || sec == 1) {
            return sec == 1;
        }
        int glb = getGlobalInt(cr, SECURE_DC_DIMMING, -1);
        if (glb == 0 || glb == 1) {
            return glb == 1;
        }
        File tf = new File(TELEMETRY_PATH);
        if (tf.isFile()) {
            try (Scanner sc = new Scanner(tf, StandardCharsets.UTF_8.name())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.startsWith("dc_dim=")) {
                        return "1".equals(line.substring(7).trim());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return prefs(ctx).getBoolean(SECURE_DC_DIMMING, false);
    }

    public static void setDcDimmingEnabled(Context ctx, boolean enabled) {
        int v = enabled ? 1 : 0;
        ContentResolver cr = ctx.getContentResolver();
        putSecureInt(cr, SECURE_DC_DIMMING, v);
        putGlobalInt(cr, SECURE_DC_DIMMING, v);
        prefs(ctx).edit().putBoolean(SECURE_DC_DIMMING, enabled).apply();
        enqueueHardwareCommand("DC_DIM=" + v);
    }

    private static void enqueueHardwareCommand(String cmdLine) {
        try {
            File qDir = new File(HW_QUEUE_DIR);
            if (!qDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                qDir.mkdirs();
            }
            String id = System.nanoTime() + "_styler_" + CMD_SEQ.getAndIncrement();
            File tmp = new File(qDir, ".tmp_" + id);
            File req = new File(qDir, "cmd_" + id + ".req");
            writeUtf8(tmp, cmdLine + "\n");
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, false);
            if (!tmp.renameTo(req)) {
                writeUtf8(req, cmdLine + "\n");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyHidePillOverlay(Context ctx, boolean hidePill) {
        try {
            Object om = ctx.getSystemService("overlay");
            if (om == null) return;
            Method setEnabled = om.getClass().getMethod(
                    "setEnabled",
                    String.class,
                    boolean.class,
                    UserHandle.class);
            setEnabled.invoke(om, HIDE_PILL_OVERLAY_PKG, hidePill, Process.myUserHandle());
        } catch (Throwable ignored) {
        }
    }

    private static void writeGlassProp(Context ctx) {
        try {
            int gm = getGlassMode(ctx);
            int im = getIconMode(ctx);
            int sp = isSearchPillEnabled(ctx) ? 1 : 0;
            int gp = isGesturePillEnabled(ctx) ? 1 : 0;
            int rp = getRefractionPct(ctx);
            int fp = getFrostPct(ctx);
            int wb = getWallpaperBrightnessPct(ctx);
            String body = "glass_mode=" + gm
                    + "\nicon_mode=" + im
                    + "\nsearch_pill=" + sp
                    + "\ngesture_pill=" + gp
                    + "\nrefraction_pct=" + rp
                    + "\nfrost_pct=" + fp
                    + "\nwp_brightness_pct=" + wb + "\n";
            File dst = new File(GLASS_PROP_PATH);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            File tmp = new File(GLASS_PROP_PATH + ".styler.tmp");
            writeUtf8(tmp, body);
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, false);
            if (!tmp.renameTo(dst)) {
                writeUtf8(dst, body);
                //noinspection ResultOfMethodCallIgnored
                dst.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeUtf8(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void performTick(View view, boolean strong) {
        if (view == null) return;
        try {
            int c = strong
                    ? HapticFeedbackConstants.CONTEXT_CLICK
                    : HapticFeedbackConstants.CLOCK_TICK;
            if (view.performHapticFeedback(c)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            VibratorManager vm = view.getContext().getSystemService(VibratorManager.class);
            if (vm != null) {
                Vibrator vib = vm.getDefaultVibrator();
                if (vib.hasVibrator()) {
                    vib.vibrate(VibrationEffect.createPredefined(
                            strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int getSecureInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.Secure.getInt(cr, key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void putSecureInt(ContentResolver cr, String key, int val) {
        try {
            Settings.Secure.putInt(cr, key, val);
        } catch (Throwable ignored) {
        }
    }

    private static int getGlobalInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.Global.getInt(cr, key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void putGlobalInt(ContentResolver cr, String key, int val) {
        try {
            Settings.Global.putInt(cr, key, val);
        } catch (Throwable ignored) {
        }
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
