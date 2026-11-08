package com.realme.samurai.glass;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Unified 0-latency Settings & Hardware Haptics Bridge for the iOS 26 Liquid Glass Full OS (`v2.0.0`).
 *
 * Synchronizes optical parameters (`Refraction`, `Frost`, `Glass Mode`, `Wallpaper Depth`, `Icon Mode`)
 * and Full-OS surface switches (`Full OS Mode`, `Lock Screen`, `Control Center`, `Dynamic Island`,
 * `Stacked Recents`, `Lock Screen Clock Style`) across `SystemUI`, `QuickStep`, `iOS Styler`,
 * and all native Liquid Glass apps.
 */
public final class SamuraiGlassPrefs {

    // Optical & Icon Modes (100% synced with StylerPrefsBridge)
    public static final int ICON_DEFAULT = 1;
    public static final int ICON_DARK = 2;
    public static final int ICON_CLEAR = 3;

    public static final int GLASS_CLEAR_REFRACTIVE = 0;
    public static final int GLASS_FROSTED_TINTED = 1;
    public static final int GLASS_SOLID_MATTE = 2;

    public static final int CLOCK_TALL_SPATIAL = 0;
    public static final int CLOCK_ROUNDED_BOLD = 1;
    public static final int CLOCK_SLIM_STENCIL = 2;

    // Existing v1.6.4 Optical Settings Keys
    public static final String KEY_ICON_MODE = "samurai_ios26_icon_mode";
    public static final String KEY_GLASS_MODE_SEC = "samurai_ios26_glass_mode";
    public static final String KEY_GLASS_MODE_GLB = "samurai_liquid_glass_mode";
    public static final String KEY_REFRACTION_PCT = "samurai_ios26_refraction_pct";
    public static final String KEY_FROST_PCT = "samurai_ios26_frost_pct";
    public static final String KEY_WP_BRIGHTNESS_PCT = "samurai_ios26_wp_brightness_pct";
    public static final String KEY_WP_PRESET = "samurai_ios26_wallpaper_preset";
    public static final String KEY_WP_SEQ = "samurai_wallpaper_seq";

    // New v2.0.0 Full-OS Surface Keys (all default to 1 = ON out of the box)
    public static final String KEY_FULL_OS_MODE = "samurai_ios26_full_os_mode";
    public static final String KEY_LOCKSCREEN_ENABLED = "samurai_ios26_lockscreen_enabled";
    public static final String KEY_CC_ENABLED = "samurai_ios26_cc_enabled";
    public static final String KEY_DYNAMIC_ISLAND_ENABLED = "samurai_ios26_dynamic_island_enabled";
    public static final String KEY_STACKED_RECENTS_ENABLED = "samurai_ios26_stacked_recents_enabled";
    public static final String KEY_LS_CLOCK_STYLE = "samurai_ios26_ls_clock_style";

    public static final int DEFAULT_REFRACTION_PCT = 20;
    public static final int DEFAULT_FROST_PCT = 20;
    public static final int DEFAULT_WP_BRIGHTNESS_PCT = 70;

    private SamuraiGlassPrefs() {}

    public static final class Snapshot {
        public volatile int iconMode = ICON_DEFAULT;
        public volatile int glassMode = GLASS_CLEAR_REFRACTIVE;
        public volatile int refractionPct = DEFAULT_REFRACTION_PCT;
        public volatile int frostPct = DEFAULT_FROST_PCT;
        public volatile int wpBrightnessPct = DEFAULT_WP_BRIGHTNESS_PCT;
        public volatile int wpPreset = 0;
        public volatile boolean fullOsMode = true;
        public volatile boolean lockScreenEnabled = true;
        public volatile boolean controlCenterEnabled = true;
        public volatile boolean dynamicIslandEnabled = true;
        public volatile boolean stackedRecentsEnabled = true;
        public volatile int lockClockStyle = CLOCK_TALL_SPATIAL;
        public volatile boolean darkTheme = false;

        public float refractScale() {
            return clamp(refractionPct, 0, 150) / 100f;
        }

        public float frostScale() {
            return clamp(frostPct, 0, 150) / 100f;
        }

        public float wpBrightnessScale() {
            float linear = clamp(wpBrightnessPct, 0, 100) / 100f;
            return (float) Math.pow(linear, 1.65);
        }
    }

    public static Snapshot read(Context ctx, Snapshot target) {
        Snapshot out = (target != null) ? target : new Snapshot();
        if (ctx == null) {
            return out;
        }
        ContentResolver cr = ctx.getContentResolver();
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        out.darkTheme = night;

        out.iconMode = getInt(cr, KEY_ICON_MODE, night ? ICON_DARK : ICON_DEFAULT, 1, 3);
        int gm = getGlobalOrSecure(cr, KEY_GLASS_MODE_GLB, KEY_GLASS_MODE_SEC, GLASS_CLEAR_REFRACTIVE);
        out.glassMode = clamp(gm, GLASS_CLEAR_REFRACTIVE, GLASS_SOLID_MATTE);
        out.refractionPct = getInt(cr, KEY_REFRACTION_PCT, DEFAULT_REFRACTION_PCT, 0, 150);
        out.frostPct = getInt(cr, KEY_FROST_PCT, DEFAULT_FROST_PCT, 0, 150);
        out.wpBrightnessPct = getInt(cr, KEY_WP_BRIGHTNESS_PCT, DEFAULT_WP_BRIGHTNESS_PCT, 0, 100);
        out.wpPreset = getInt(cr, KEY_WP_PRESET, 0, 0, 2);

        out.fullOsMode = getInt(cr, KEY_FULL_OS_MODE, 1, 0, 1) == 1;
        out.lockScreenEnabled = out.fullOsMode
                && (getInt(cr, KEY_LOCKSCREEN_ENABLED, 1, 0, 1) == 1);
        out.controlCenterEnabled = out.fullOsMode
                && (getInt(cr, KEY_CC_ENABLED, 1, 0, 1) == 1);
        out.dynamicIslandEnabled = out.fullOsMode
                && (getInt(cr, KEY_DYNAMIC_ISLAND_ENABLED, 1, 0, 1) == 1);
        out.stackedRecentsEnabled = out.fullOsMode
                && (getInt(cr, KEY_STACKED_RECENTS_ENABLED, 1, 0, 1) == 1);
        out.lockClockStyle = getInt(cr, KEY_LS_CLOCK_STYLE, CLOCK_TALL_SPATIAL, 0, 2);
        return out;
    }

    public static ContentObserver registerObserver(Context ctx, Runnable onChanged) {
        if (ctx == null || onChanged == null) {
            return null;
        }
        ContentResolver cr = ctx.getContentResolver();
        ContentObserver obs = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                onChanged.run();
            }
        };
        String[] keys = new String[] {
                KEY_ICON_MODE,
                KEY_GLASS_MODE_SEC,
                KEY_GLASS_MODE_GLB,
                KEY_REFRACTION_PCT,
                KEY_FROST_PCT,
                KEY_WP_BRIGHTNESS_PCT,
                KEY_WP_PRESET,
                KEY_WP_SEQ,
                KEY_FULL_OS_MODE,
                KEY_LOCKSCREEN_ENABLED,
                KEY_CC_ENABLED,
                KEY_DYNAMIC_ISLAND_ENABLED,
                KEY_STACKED_RECENTS_ENABLED,
                KEY_LS_CLOCK_STYLE
        };
        try {
            for (String k : keys) {
                cr.registerContentObserver(Settings.Global.getUriFor(k), false, obs);
                cr.registerContentObserver(Settings.Secure.getUriFor(k), false, obs);
            }
        } catch (Throwable ignored) {
        }
        return obs;
    }

    public static void unregisterObserver(Context ctx, ContentObserver obs) {
        if (ctx == null || obs == null) return;
        try {
            ctx.getContentResolver().unregisterContentObserver(obs);
        } catch (Throwable ignored) {
        }
    }

    public static void putSystemToggle(Context ctx, String key, int value) {
        if (ctx == null) return;
        ContentResolver cr = ctx.getContentResolver();
        try {
            Settings.Global.putInt(cr, key, value);
        } catch (Throwable ignored) {
        }
        try {
            Settings.Secure.putInt(cr, key, value);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public static void configure90HzEdgeToEdge(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(0x00000000);
        window.setNavigationBarColor(0x00000000);
        try {
            Display display = activity.getDisplay();
            if (display != null) {
                Display.Mode best90 = null;
                for (Display.Mode m : display.getSupportedModes()) {
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

    public static void hapticTick(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    public static void hapticClick(Context ctx, View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        } else if (ctx != null) {
            vibrateEffect(ctx, VibrationEffect.EFFECT_CLICK);
        }
    }

    public static void hapticHeavySpring(Context ctx, View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (ctx != null) {
            vibrateEffect(ctx, VibrationEffect.EFFECT_HEAVY_CLICK);
        }
    }

    private static void vibrateEffect(Context ctx, int effectId) {
        try {
            VibratorManager vm = ctx.getSystemService(VibratorManager.class);
            Vibrator vib = vm != null ? vm.getDefaultVibrator() : ctx.getSystemService(Vibrator.class);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createPredefined(effectId));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int getInt(ContentResolver cr, String key, int def, int min, int max) {
        int val = getGlobalOrSecure(cr, key, key, def);
        return clamp(val, min, max);
    }

    private static int getGlobalOrSecure(ContentResolver cr, String gKey, String sKey, int def) {
        try {
            int s = Settings.Secure.getInt(cr, sKey, Integer.MIN_VALUE);
            if (s != Integer.MIN_VALUE) return s;
        } catch (Throwable ignored) {
        }
        try {
            int g = Settings.Global.getInt(cr, gKey, Integer.MIN_VALUE);
            if (g != Integer.MIN_VALUE) return g;
        } catch (Throwable ignored) {
        }
        return def;
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
