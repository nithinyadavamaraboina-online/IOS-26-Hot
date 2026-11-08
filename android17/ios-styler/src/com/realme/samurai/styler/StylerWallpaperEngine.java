package com.realme.samurai.styler;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * iOS 26 Liquid Glass Wallpaper Studio & Dual-Buffer Optical Backdrop Engine.
 *
 * Uses the verified 1:1 Apple iOS 26 "Sky" Default Light & Dark `1080x2400` wallpapers bundled in
 * `res/drawable-nodpi/` (`wp_ios26_sky_light.webp` & `wp_ios26_sky_dark.webp`), plus an iridescent
 * `Aurora Cyan` optical transform:
 *   0 = iOS 26 Default (Sky Glass)   — Verified 1:1 iOS 26 Sky Light (`1080x2400`)
 *   1 = iOS 26 Midnight (Dark Glass) — Verified 1:1 iOS 26 Sky Dark (`1080x2400`)
 *   2 = iOS 26 Aurora Cyan           — Iridescent bioluminescent emerald-cyan & royal indigo glass
 */
public final class StylerWallpaperEngine {
    public interface ApplyCallback {
        void onComplete(boolean success);
    }

    public static final class BackdropPair {
        public final int preset;
        public final int sharpW;
        public final int sharpH;
        public final int softW;
        public final int softH;
        public final int bufW;
        public final int bufH;
        public final Bitmap displayBitmap;
        public final Bitmap sharpBitmap;
        public final Bitmap softBitmap;
        public final BitmapShader sharpShader;
        public final BitmapShader softShader;
        public final float meanLuma;
        public final int avgTintRgb;

        BackdropPair(
                int preset,
                Bitmap display,
                Bitmap sharp,
                Bitmap soft,
                float luma,
                int avgRgb) {
            this.preset = preset;
            this.sharpW = sharp.getWidth();
            this.sharpH = sharp.getHeight();
            this.softW = soft.getWidth();
            this.softH = soft.getHeight();
            this.bufW = this.sharpW;
            this.bufH = this.sharpH;
            this.displayBitmap = display;
            this.sharpBitmap = sharp;
            this.softBitmap = soft;
            this.sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.meanLuma = luma;
            this.avgTintRgb = avgRgb;
        }
    }

    private static final ExecutorService BG_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final BackdropPair[] CACHE = new BackdropPair[3];

    private StylerWallpaperEngine() {}

    public static synchronized BackdropPair getBackdropPair(Context ctx, int preset) {
        int idx = StylerPrefsBridge.clamp(preset, 0, 2);
        if (CACHE[idx] != null) {
            return CACHE[idx];
        }

        // 1. Decode 540x1200 crisp optical crystal bitmap (0.0px blur) from 1080x2400 iOS 26 Sky artwork
        Bitmap displayBmp = loadScaledPresetBitmap(ctx, idx, 540, 1200);
        Bitmap sharpBmp = displayBmp;

        // 2. Build 270x600 silky frosted AGSL shader buffer for smooth 0% -> 150% Frost interpolation
        int softW = 270;
        int softH = 600;
        Bitmap softBase = Bitmap.createScaledBitmap(displayBmp, softW, softH, true);
        int[] basePx = new int[softW * softH];
        softBase.getPixels(basePx, 0, softW, 0, 0, softW, softH);
        if (softBase != displayBmp) {
            softBase.recycle();
        }

        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        for (int c : basePx) {
            sumR += (c >> 16) & 0xFF;
            sumG += (c >> 8) & 0xFF;
            sumB += c & 0xFF;
        }
        int total = softW * softH;
        int avgR = (int) (sumR / total);
        int avgG = (int) (sumG / total);
        int avgB = (int) (sumB / total);
        float meanLuma = (0.2126f * avgR + 0.7152f * avgG + 0.0722f * avgB) / 255f;
        int avgRgb = Color.rgb(avgR, avgG, avgB);

        int[] softPx = boxBlur3Pass(basePx, softW, softH, 14);
        Bitmap softBmp = Bitmap.createBitmap(softPx, softW, softH, Bitmap.Config.ARGB_8888);

        BackdropPair pair = new BackdropPair(idx, displayBmp, sharpBmp, softBmp, meanLuma, avgRgb);
        CACHE[idx] = pair;
        return pair;
    }

    public static Bitmap buildSwatchBitmap(Context ctx, int preset, int outW, int outH) {
        int idx = StylerPrefsBridge.clamp(preset, 0, 2);
        BackdropPair pair = getBackdropPair(ctx, idx);
        return Bitmap.createScaledBitmap(pair.displayBitmap, outW, outH, true);
    }

    public static Bitmap loadFullWallpaper(Context ctx, int preset, int outW, int outH) {
        return loadScaledPresetBitmap(ctx, StylerPrefsBridge.clamp(preset, 0, 2), outW, outH);
    }

    private static Bitmap loadScaledPresetBitmap(Context ctx, int preset, int targetW, int targetH) {
        if (ctx != null) {
            try {
                int resId = (preset == StylerPrefsBridge.WP_SKY_GLASS)
                        ? R.drawable.wp_ios26_sky_light
                        : R.drawable.wp_ios26_sky_dark;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inScaled = false;
                if (targetW <= 540 && targetH <= 1200) {
                    opts.inSampleSize = 2;
                }
                Bitmap raw = BitmapFactory.decodeResource(ctx.getResources(), resId, opts);
                if (raw != null) {
                    Bitmap sized = (raw.getWidth() == targetW && raw.getHeight() == targetH)
                            ? raw
                            : Bitmap.createScaledBitmap(raw, targetW, targetH, true);
                    if (sized != raw) {
                        raw.recycle();
                    }
                    if (preset == StylerPrefsBridge.WP_AURORA_CYAN) {
                        return transformToAuroraCyan(sized);
                    }
                    return sized;
                }
            } catch (Throwable ignored) {
            }
        }
        int[] px = synthesizeFallbackPixels(preset, targetW, targetH);
        return Bitmap.createBitmap(px, targetW, targetH, Bitmap.Config.ARGB_8888);
    }

    /**
     * Transforms the verified iOS 26 Sky Dark glass geometry into an iridescent Aurora Cyan
     * edition while preserving 100% of Apple's micro-pitted glass disc caustics & sweeping arch.
     */
    private static Bitmap transformToAuroraCyan(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        float invW = 1f / Math.max(1, w - 1);
        float invH = 1f / Math.max(1, h - 1);

        for (int y = 0; y < h; y++) {
            float yn = y * invH;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float xn = x * invW;
                int c = px[row + x];
                float r = (c >> 16) & 0xFF;
                float g = (c >> 8) & 0xFF;
                float b = c & 0xFF;

                // Spectral rotation toward bioluminescent emerald-cyan & royal violet-turquoise
                float auroraWave = 0.5f + 0.5f * (float) Math.sin(xn * 3.6f - yn * 2.4f + 0.8f);
                float nr = r * 0.48f + b * (0.14f + 0.16f * auroraWave);
                float ng = g * 0.88f + b * (0.46f - 0.12f * yn);
                float nb = b * 0.96f + g * 0.34f + 16f * auroraWave;
                px[row + x] = packRgb(nr, ng, nb);
            }
        }
        src.recycle();
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    public static String presetTitle(int preset) {
        if (preset == StylerPrefsBridge.WP_MIDNIGHT_GLASS) {
            return "iOS 26 Sky Dark";
        }
        if (preset == StylerPrefsBridge.WP_AURORA_CYAN) {
            return "iOS 26 Aurora Cyan";
        }
        return "iOS 26 Sky Light";
    }

    public static void applyWallpaperAsync(Context context, final int preset, final ApplyCallback cb) {
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final int cleanPreset = StylerPrefsBridge.clamp(preset, 0, 2);
        BG_EXEC.execute(() -> {
            boolean ok = false;
            try {
                Bitmap bmp = loadFullWallpaper(appCtx, cleanPreset, 1080, 2400);
                WallpaperManager wm = WallpaperManager.getInstance(appCtx);
                wm.setBitmap(bmp, null, true,
                        WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                bmp.recycle();
                StylerPrefsBridge.setWallpaperPresetAndBumpSeq(appCtx, cleanPreset);
                ok = true;
            } catch (Throwable ignored) {
            }
            final boolean result = ok;
            MAIN_HANDLER.post(() -> {
                Toast.makeText(
                        appCtx,
                        result
                                ? ("Applied " + presetTitle(cleanPreset) + " (Home & Lock)")
                                : "Unable to update system wallpaper",
                        Toast.LENGTH_SHORT
                ).show();
                if (cb != null) {
                    cb.onComplete(result);
                }
            });
        });
    }

    private static int[] synthesizeFallbackPixels(int preset, int w, int h) {
        int[] pixels = new int[w * h];
        float invW = 1f / Math.max(1, w - 1);
        float invH = 1f / Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            float yn = y * invH;
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                float xn = x * invW;
                if (preset == StylerPrefsBridge.WP_SKY_GLASS) {
                    float r = 178f - 110f * yn + 24f * xn;
                    float g = 228f - 62f * yn;
                    float b = 242f - 18f * yn;
                    pixels[rowOff + x] = packRgb(r, g, b);
                } else if (preset == StylerPrefsBridge.WP_MIDNIGHT_GLASS) {
                    float r = 10f + 22f * (1f - yn);
                    float g = 28f + 56f * (1f - yn);
                    float b = 76f + 110f * (1f - 0.5f * yn);
                    pixels[rowOff + x] = packRgb(r, g, b);
                } else {
                    float r = 12f + 42f * xn;
                    float g = 74f + 118f * (1f - yn);
                    float b = 128f + 98f * (1f - 0.4f * yn);
                    pixels[rowOff + x] = packRgb(r, g, b);
                }
            }
        }
        return pixels;
    }

    private static int packRgb(float r, float g, float b) {
        int ri = StylerPrefsBridge.clamp(Math.round(r), 0, 255);
        int gi = StylerPrefsBridge.clamp(Math.round(g), 0, 255);
        int bi = StylerPrefsBridge.clamp(Math.round(b), 0, 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int[] boxBlur3Pass(int[] src, int w, int h, int radius) {
        int[] a = src.clone();
        int[] b = new int[w * h];
        for (int pass = 0; pass < 3; pass++) {
            blurHorizontal(a, b, w, h, radius);
            blurVertical(b, a, w, h, radius);
        }
        return a;
    }

    private static void blurHorizontal(int[] src, int[] dst, int w, int h, int rad) {
        int win = rad * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int c = src[row + StylerPrefsBridge.clamp(k, 0, w - 1)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = 0xFF000000
                        | ((sr / win) << 16)
                        | ((sg / win) << 8)
                        | (sb / win);
                int outIdx = StylerPrefsBridge.clamp(x - rad, 0, w - 1);
                int inIdx = StylerPrefsBridge.clamp(x + rad + 1, 0, w - 1);
                int cOut = src[row + outIdx];
                int cIn = src[row + inIdx];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int w, int h, int rad) {
        int win = rad * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0;
            int sg = 0;
            int sb = 0;
            for (int k = -rad; k <= rad; k++) {
                int c = src[StylerPrefsBridge.clamp(k, 0, h - 1) * w + x];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((sr / win) << 16)
                        | ((sg / win) << 8)
                        | (sb / win);
                int outY = StylerPrefsBridge.clamp(y - rad, 0, h - 1);
                int inY = StylerPrefsBridge.clamp(y + rad + 1, 0, h - 1);
                int cOut = src[outY * w + x];
                int cIn = src[inY * w + x];
                sr += ((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF);
                sg += ((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF);
                sb += (cIn & 0xFF) - (cOut & 0xFF);
            }
        }
    }
}
