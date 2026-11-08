package com.realme.samurai.launcher;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot OTA & first-boot iOS 26 Default Liquid Glass ("Sky") Wallpaper applier
 * for Realme X2 Pro (RMX1931, 1080x2400 @ 420dpi).
 *
 * - Clean flash: SamuraiIos26WallpaperOverlay (priority=950) overrides android:drawable/default_wallpaper.
 * - OTA upgrade (dirty flash preserving /data): applies the 1080x2400 iOS 26 Sky Light wallpaper once
 *   via WallpaperManager (FLAG_SYSTEM | FLAG_LOCK) after credential-encrypted storage is unlocked,
 *   marks Settings.Secure "samurai_ios26_default_wp_v153" = 1, and never overwrites user choices afterward.
 */
public final class SamuraiWallpaperApplier {
    private static final String TAG = "SamuraiWpApplier";

    public static final String KEY_DEFAULT_WP_APPLIED = "samurai_ios26_default_wp_v153";
    public static final String KEY_WALLPAPER_SEQ = "samurai_wallpaper_seq";
    public static final String OVERLAY_PACKAGE = "com.realme.samurai.overlay.wallpaper.ios26";

    public static final int TARGET_WIDTH = 1080;
    public static final int TARGET_HEIGHT = 2400;

    private static final String[] LIGHT_ASSET_CANDIDATES = {
            "ios26_wallpapers/ios26_default_light_1080x2400.png",
            "ios26_wallpapers/ios26_default_light_1080x2400.webp",
            "ios26_default_light_1080x2400.png",
            "ios26_default_light_1080x2400.webp",
    };

    private static final String[] DARK_ASSET_CANDIDATES = {
            "ios26_wallpapers/ios26_default_dark_1080x2400.png",
            "ios26_wallpapers/ios26_default_dark_1080x2400.webp",
            "ios26_default_dark_1080x2400.png",
            "ios26_default_dark_1080x2400.webp",
    };

    private static final AtomicBoolean sInFlight = new AtomicBoolean(false);
    private static volatile boolean sConfirmedApplied = false;

    private SamuraiWallpaperApplier() {}

    public static boolean isConfirmedApplied() {
        return sConfirmedApplied;
    }

    public static void applyDefaultIfNeeded(final Context context) {
        if (sConfirmedApplied || context == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        try {
            UserManager um = appCtx.getSystemService(UserManager.class);
            if (um != null && !um.isUserUnlocked()) {
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            ContentResolver cr = appCtx.getContentResolver();
            if (Settings.Secure.getInt(cr, KEY_DEFAULT_WP_APPLIED, 0) != 0) {
                sConfirmedApplied = true;
                return;
            }
        } catch (Throwable ignored) {
        }

        if (!sInFlight.compareAndSet(false, true)) {
            return;
        }

        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                ContentResolver cr = appCtx.getContentResolver();
                if (Settings.Secure.getInt(cr, KEY_DEFAULT_WP_APPLIED, 0) != 0) {
                    sConfirmedApplied = true;
                    return;
                }

                boolean ok = applyWallpaperInternal(
                        appCtx,
                        false /* darkVariant */,
                        WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                if (ok) {
                    Settings.Secure.putInt(cr, KEY_DEFAULT_WP_APPLIED, 1);
                    sConfirmedApplied = true;
                    bumpWallpaperSeq(cr);
                    SamuraiWallpaperBlurCache.requestRefresh(appCtx);
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> SamuraiWallpaperBlurCache.requestRefresh(appCtx),
                            350L);
                    Log.i(TAG, "Applied iOS 26 Default Light 1080x2400 wallpaper (v1.5.3)");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to apply iOS 26 default wallpaper", t);
            } finally {
                sInFlight.set(false);
            }
        }, "SamuraiIos26WpApplier");
        worker.setDaemon(true);
        worker.start();
    }

    public static boolean applyVariantNow(Context context, boolean darkVariant, int whichFlags) {
        if (context == null) {
            return false;
        }
        Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        boolean ok = applyWallpaperInternal(appCtx, darkVariant, whichFlags);
        if (ok) {
            try {
                bumpWallpaperSeq(appCtx.getContentResolver());
                SamuraiWallpaperBlurCache.requestRefresh(appCtx);
            } catch (Throwable ignored) {
            }
        }
        return ok;
    }

    private static boolean applyWallpaperInternal(Context appCtx, boolean darkVariant, int whichFlags) {
        WallpaperManager wm = WallpaperManager.getInstance(appCtx);
        if (wm == null) {
            return false;
        }

        Bitmap decoded = decodeBundledOrOverlayWallpaper(appCtx, darkVariant);
        if (decoded == null) {
            return false;
        }

        Bitmap exact = ensureExactDimensions(decoded, TARGET_WIDTH, TARGET_HEIGHT);
        try {
            try {
                wm.suggestDesiredDimensions(TARGET_WIDTH, TARGET_HEIGHT);
            } catch (Throwable ignored) {
            }
            Rect cropHint = new Rect(0, 0, exact.getWidth(), exact.getHeight());
            int result = wm.setBitmap(exact, cropHint, true /* allowBackup */, whichFlags);
            return result != 0;
        } catch (Throwable t) {
            Log.w(TAG, "WallpaperManager.setBitmap failed", t);
            return false;
        } finally {
            if (exact != decoded && !decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private static Bitmap decodeBundledOrOverlayWallpaper(Context appCtx, boolean darkVariant) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        String[] candidates = darkVariant ? DARK_ASSET_CANDIDATES : LIGHT_ASSET_CANDIDATES;
        AssetManager assets = appCtx.getAssets();
        if (assets != null) {
            for (String path : candidates) {
                try (InputStream is = assets.open(path)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                        return bmp;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (darkVariant) {
            return null;
        }

        try {
            Context ovCtx = appCtx.createPackageContext(OVERLAY_PACKAGE, 0);
            Resources ovRes = ovCtx.getResources();
            int ovId = ovRes.getIdentifier("default_wallpaper", "drawable", OVERLAY_PACKAGE);
            if (ovId != 0) {
                try (InputStream is = ovRes.openRawResource(ovId)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                        return bmp;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Resources sysRes = Resources.getSystem();
            int fwId = sysRes.getIdentifier("default_wallpaper", "drawable", "android");
            if (fwId != 0) {
                try (InputStream is = sysRes.openRawResource(fwId)) {
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                        return bmp;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Bitmap ensureExactDimensions(Bitmap src, int targetW, int targetH) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw == targetW && sh == targetH) {
            return src;
        }
        float targetRatio = (float) targetW / (float) targetH;
        float srcRatio = (float) sw / (float) sh;
        Bitmap cropped = src;
        if (Math.abs(srcRatio - targetRatio) > 0.002f) {
            if (srcRatio > targetRatio) {
                int cropW = Math.max(1, Math.round(sh * targetRatio));
                int x0 = Math.max(0, (sw - cropW) / 2);
                cropped = Bitmap.createBitmap(src, x0, 0, Math.min(cropW, sw - x0), sh);
            } else {
                int cropH = Math.max(1, Math.round(sw / targetRatio));
                int y0 = Math.max(0, (sh - cropH) / 2);
                cropped = Bitmap.createBitmap(src, 0, y0, sw, Math.min(cropH, sh - y0));
            }
        }
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true);
        if (cropped != src && cropped != scaled && !cropped.isRecycled()) {
            cropped.recycle();
        }
        return scaled;
    }

    private static void bumpWallpaperSeq(ContentResolver cr) {
        try {
            int cur = Settings.Global.getInt(cr, KEY_WALLPAPER_SEQ, 0);
            Settings.Global.putInt(cr, KEY_WALLPAPER_SEQ, cur + 1);
        } catch (Throwable ignored) {
        }
    }
}
