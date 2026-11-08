package com.realme.samurai.launcher;

import android.annotation.SuppressLint;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Off-frame True-Crystal + Deep-Frost Dual-Buffer Wallpaper & Dock Icon Bloom Cache.
 *
 * - `crystalShader` (`540x1200`, 0.0px blur): 100% unblurred razor-sharp optical wallpaper buffer
 *   so setting Glass Blur / Frost to 0% yields pure water-clear refractive crystal glass.
 * - `softShader` (`270x600`, sigma = 14.5f = ~22dp): deep silky Gaussian frost buffer blended in
 *   proportionally as Frost increases from 0% -> 150% (0.0ms per-frame blur cost at 90Hz).
 * - Synchronously captures real wallpaper on initial call so Frame 0 never flashes a fallback tint.
 */
public final class SamuraiWallpaperBlurCache {
    public static final int CRYSTAL_BUF_W = 540;
    public static final int CRYSTAL_BUF_H = 1200;
    public static final int SOFT_BUF_W = 270;
    public static final int SOFT_BUF_H = 600;

    private static final ExecutorService BG_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SamuraiGlassBlur");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static final class Snapshot {
        public final BitmapShader crystalShader;
        public final BitmapShader sharpShader;
        public final BitmapShader softShader;
        public final int crystalW;
        public final int crystalH;
        public final int softW;
        public final int softH;
        public final int bufW;
        public final int bufH;
        public final float meanLuma;
        public final int avgTintRgb;
        public final int version;

        Snapshot(Bitmap crystal, Bitmap soft, float meanLuma, int avgTintRgb, int version) {
            BitmapShader cs = new BitmapShader(crystal, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.crystalShader = cs;
            this.sharpShader = cs;
            this.softShader = new BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.crystalW = crystal.getWidth();
            this.crystalH = crystal.getHeight();
            this.softW = soft.getWidth();
            this.softH = soft.getHeight();
            this.bufW = this.crystalW;
            this.bufH = this.crystalH;
            this.meanLuma = meanLuma;
            this.avgTintRgb = avgTintRgb;
            this.version = version;
        }
    }

    private static volatile Snapshot sSnapshot;
    private static volatile boolean sListenerRegistered = false;
    private static final AtomicBoolean sRefreshQueued = new AtomicBoolean(false);
    private static volatile int sVersionCounter = 1;
    private static volatile Runnable sInvalidator;
    private static volatile float sCachedWpZoom = -1f;

    private SamuraiWallpaperBlurCache() {}

    @SuppressLint("DiscouragedApi")
    public static float getSystemWallpaperZoomScale() {
        float cached = sCachedWpZoom;
        if (cached >= 1.0f) {
            return cached;
        }
        float zoom = 1.10f;
        try {
            Resources sys = Resources.getSystem();
            int id = sys.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
            if (id != 0) {
                float v = sys.getFloat(id);
                if (v >= 1.0f && v <= 2.0f) {
                    zoom = v;
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedWpZoom = zoom;
        return zoom;
    }

    public static void setInvalidator(Runnable invalidator) {
        sInvalidator = invalidator;
    }

    public static Snapshot getOrCreate(Context context) {
        ensureListener(context);
        Snapshot snap = sSnapshot;
        if (snap != null) {
            return snap;
        }
        synchronized (SamuraiWallpaperBlurCache.class) {
            if (sSnapshot == null) {
                // Capture actual system wallpaper synchronously for crystal buffer (~3-5ms)
                // and fast 1/8-res initial soft frost (~2ms) so Frame 0 has zero color pop!
                Bitmap crystal = captureSystemWallpaper(context, CRYSTAL_BUF_W, CRYSTAL_BUF_H);
                Bitmap quickLow = Bitmap.createScaledBitmap(crystal, 135, 300, true);
                float[] stats = computeLuminanceAndTint(quickLow);
                Bitmap quickSoft = gaussianBlurSeparable(quickLow, 7.2f);
                if (quickSoft != quickLow) {
                    quickLow.recycle();
                }
                int tintRgb = Color.rgb(
                        Math.round(stats[1] * 255f),
                        Math.round(stats[2] * 255f),
                        Math.round(stats[3] * 255f));
                sSnapshot = new Snapshot(crystal, quickSoft, stats[0], tintRgb, sVersionCounter++);
                requestRefresh(context);
            }
            return sSnapshot;
        }
    }

    private static void ensureListener(Context context) {
        if (sListenerRegistered || context == null) return;
        synchronized (SamuraiWallpaperBlurCache.class) {
            if (sListenerRegistered) return;
            try {
                final Context appCtx = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                WallpaperManager wm = WallpaperManager.getInstance(appCtx);
                if (wm != null) {
                    wm.addOnColorsChangedListener((colors, which) -> requestRefresh(appCtx), MAIN_HANDLER);
                }
                sListenerRegistered = true;
            } catch (Throwable ignored) {
                sListenerRegistered = true;
            }
        }
    }

    public static void requestRefresh(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        if (!sRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        BG_EXEC.execute(() -> {
            try {
                // Full native 1080x2400 crystal buffer for 1:1 pixel sharpness at Frost=0% / Refraction=0%
                Bitmap crystal = captureSystemWallpaper(appCtx, 1080, 2400);
                Bitmap softBase = Bitmap.createScaledBitmap(crystal, SOFT_BUF_W, SOFT_BUF_H, true);
                float[] stats = computeLuminanceAndTint(softBase);
                Bitmap soft = gaussianBlurSeparable(softBase, 14.5f);
                if (soft != softBase) {
                    softBase.recycle();
                }
                int tintRgb = Color.rgb(
                        Math.round(stats[1] * 255f),
                        Math.round(stats[2] * 255f),
                        Math.round(stats[3] * 255f));
                sSnapshot = new Snapshot(crystal, soft, stats[0], tintRgb, ++sVersionCounter);
                Runnable inv = sInvalidator;
                if (inv != null) {
                    MAIN_HANDLER.post(inv);
                }
            } catch (Throwable ignored) {
            } finally {
                sRefreshQueued.set(false);
            }
        });
    }

    private static Bitmap captureSystemWallpaper(Context context, int outW, int outH) {
        if (context != null) {
            try {
                WallpaperManager wm = WallpaperManager.getInstance(context);
                if (wm != null) {
                    WallpaperInfo liveInfo = wm.getWallpaperInfo();
                    if (liveInfo == null) {
                        Drawable d = wm.getDrawable();
                        if (d != null) {
                            return rasterizeCenterCrop(d, outW, outH);
                        }
                    } else {
                        try {
                            Drawable thumb = liveInfo.loadThumbnail(context.getPackageManager());
                            if (thumb != null) {
                                return rasterizeCenterCrop(thumb, outW, outH);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    WallpaperColors wc = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                    if (wc != null) {
                        return buildColorsFallback(wc, outW, outH);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return buildFallbackWallpaper(outW, outH);
    }

    private static Bitmap rasterizeCenterCrop(Drawable d, int outW, int outH) {
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (d instanceof BitmapDrawable) {
            Bitmap src = ((BitmapDrawable) d).getBitmap();
            if (src != null && !src.isRecycled() && src.getWidth() > 0 && src.getHeight() > 0) {
                boolean copiedHw = false;
                if (src.getConfig() == Bitmap.Config.HARDWARE) {
                    Bitmap swSrc = src.copy(Bitmap.Config.ARGB_8888, false);
                    if (swSrc != null) {
                        src = swSrc;
                        copiedHw = true;
                    }
                }
                if (src.getConfig() != Bitmap.Config.HARDWARE) {
                    int srcW = src.getWidth();
                    int srcH = src.getHeight();
                    float targetAspect = (float) outW / (float) outH;
                    int cropW = srcW;
                    if (srcW > Math.round(srcH * (targetAspect * 1.30f))) {
                        cropW = Math.min(srcW, Math.max(1, Math.round(srcH * targetAspect)));
                    }
                    float scale = Math.max((float) outW / cropW, (float) outH / srcH);
                    float dw = cropW * scale;
                    float dh = srcH * scale;
                    float dx = (outW - dw) * 0.5f;
                    float dy = (outH - dh) * 0.5f;
                    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                    c.save();
                    c.translate(dx, dy);
                    c.scale(scale, scale);
                    c.drawBitmap(src, 0f, 0f, p);
                    c.restore();
                    if (copiedHw) {
                        src.recycle();
                    }
                    return out;
                }
            }
        }
        d.setBounds(0, 0, outW, outH);
        try {
            d.draw(c);
        } catch (IllegalArgumentException hwEx) {
            try {
                Picture pic = new Picture();
                Canvas rec = pic.beginRecording(outW, outH);
                d.draw(rec);
                pic.endRecording();
                Bitmap hw = Bitmap.createBitmap(pic, outW, outH, Bitmap.Config.HARDWARE);
                if (hw != null) {
                    Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hw.recycle();
                    if (sw != null) {
                        out.recycle();
                        return sw;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Bitmap buildColorsFallback(WallpaperColors wc, int w, int h) {
        int c1 = wc.getPrimaryColor().toArgb();
        int c2 = wc.getSecondaryColor() != null ? wc.getSecondaryColor().toArgb() : c1;
        int c3 = wc.getTertiaryColor() != null ? wc.getTertiaryColor().toArgb() : c2;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0f, 0f, w, h,
                new int[]{c1 | 0xFF000000, c2 | 0xFF000000, c3 | 0xFF000000},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        return bmp;
    }

    private static Bitmap buildFallbackWallpaper(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0f, 0f, 0f, h,
                new int[]{0xFF6FE4FF, 0xFF3FA8F8, 0xFF2B6DE8, 0xFF56C9FA},
                new float[]{0f, 0.36f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bloom.setShader(new RadialGradient(w * 0.5f, h * 0.85f, w * 0.72f,
                new int[]{0xCC8CF2FF, 0x553680F6, 0x001D4FB8},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bloom);
        return bmp;
    }

    private static float[] computeLuminanceAndTint(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int startY = (int) (h * 0.84f);
        double sumL = 0, sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int y = startY; y < h; y += 2) {
            int row = y * w;
            for (int x = 0; x < w; x += 2) {
                int c = px[row + x];
                float r = ((c >> 16) & 0xFF) / 255f;
                float g = ((c >> 8) & 0xFF) / 255f;
                float b = (c & 0xFF) / 255f;
                sumL += 0.2126f * r + 0.7152f * g + 0.0722f * b;
                sumR += r;
                sumG += g;
                sumB += b;
                count++;
            }
        }
        if (count == 0) return new float[]{0.5f, 0.24f, 0.52f, 0.88f};
        return new float[]{
                (float) (sumL / count),
                (float) (sumR / count),
                (float) (sumG / count),
                (float) (sumB / count)
        };
    }

    public static BitmapShader buildDockIconBloomShader(
            ViewGroup shortcutsContainer,
            float dockLeftInHotseat,
            float dockTopInHotseat,
            float dockW,
            float dockH) {
        int bw = Math.max(24, Math.round(dockW / 6f));
        int bh = Math.max(12, Math.round(dockH / 6f));
        Bitmap bmp = null;
        if (shortcutsContainer != null && shortcutsContainer.getChildCount() > 0 && dockW > 1f && dockH > 1f) {
            try {
                Picture pic = new Picture();
                Canvas rec = pic.beginRecording(bw, bh);
                float sx = bw / dockW;
                float sy = bh / dockH;
                rec.scale(sx, sy);
                float offX = shortcutsContainer.getLeft() - dockLeftInHotseat;
                float offY = shortcutsContainer.getTop() - dockTopInHotseat;
                rec.translate(offX, offY);
                int count = shortcutsContainer.getChildCount();
                int drawn = 0;
                for (int i = 0; i < count; i++) {
                    View child = shortcutsContainer.getChildAt(i);
                    if (child == null || child.getVisibility() != View.VISIBLE) continue;
                    int cw = child.getWidth();
                    int ch = child.getHeight();
                    if (cw <= 0 || ch <= 0) continue;
                    rec.save();
                    rec.translate(child.getLeft(), child.getTop());
                    int clipH = Math.min(ch, cw);
                    rec.clipRect(0, 0, cw, clipH);
                    child.draw(rec);
                    rec.restore();
                    drawn++;
                }
                pic.endRecording();
                if (drawn > 0) {
                    Bitmap hw = Bitmap.createBitmap(pic, bw, bh, Bitmap.Config.HARDWARE);
                    if (hw != null) {
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                        if (sw != null) {
                            bmp = gaussianBlurSeparable(sw, 4.4f);
                            if (sw != bmp) {
                                sw.recycle();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (bmp == null) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        }
        return new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    public static Bitmap gaussianBlurSeparable(Bitmap src, float sigma) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (sigma <= 0.25f || w <= 1 || h <= 1) {
            return src.copy(Bitmap.Config.ARGB_8888, false);
        }
        int radius = Math.min(48, Math.max(1, (int) Math.ceil(sigma * 2.6f)));
        int kLen = radius * 2 + 1;
        float[] kernel = new float[kLen];
        float sum = 0f;
        float inv2s2 = 1f / (2f * sigma * sigma);
        for (int i = -radius; i <= radius; i++) {
            float wt = (float) Math.exp(-(i * i) * inv2s2);
            kernel[i + radius] = wt;
            sum += wt;
        }
        for (int i = 0; i < kLen; i++) {
            kernel[i] /= sum;
        }

        int[] inPx = new int[w * h];
        int[] tmpPx = new int[w * h];
        int[] outPx = new int[w * h];
        src.getPixels(inPx, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float a = 0f, r = 0f, g = 0f, b = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sx = x + k;
                    if (sx < 0) sx = 0;
                    else if (sx >= w) sx = w - 1;
                    int c = inPx[row + sx];
                    float wt = kernel[k + radius];
                    a += ((c >>> 24) & 0xFF) * wt;
                    r += ((c >> 16) & 0xFF) * wt;
                    g += ((c >> 8) & 0xFF) * wt;
                    b += (c & 0xFF) * wt;
                }
                int ia = clampByte(a);
                int ir = clampByte(r);
                int ig = clampByte(g);
                int ib = clampByte(b);
                tmpPx[row + x] = (ia << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float a = 0f, r = 0f, g = 0f, b = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sy = y + k;
                    if (sy < 0) sy = 0;
                    else if (sy >= h) sy = h - 1;
                    int c = tmpPx[sy * w + x];
                    float wt = kernel[k + radius];
                    a += ((c >>> 24) & 0xFF) * wt;
                    r += ((c >> 16) & 0xFF) * wt;
                    g += ((c >> 8) & 0xFF) * wt;
                    b += (c & 0xFF) * wt;
                }
                int ia = clampByte(a);
                int ir = clampByte(r);
                int ig = clampByte(g);
                int ib = clampByte(b);
                outPx[y * w + x] = (ia << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(outPx, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clampByte(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
