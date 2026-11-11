package com.realme.samurai.pm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * System-level Optical UDFPS Illumination Surface for Realme X2 Pro (RMX1931 / samurai).
 *
 * Dual-geometry collision-free architecture for Goodix G3 (GF9500) on 1080x2400 Samsung SOFEF03F-M:
 * - Physical sensor center: (540, 2156), optical radius 95px (190x190 UI icon in SystemUI/Settings).
 * - Unpressed active session (PREWARM_SIZE = 172 at (454, 2070)): keeps WindowManager, BLAST
 *   BufferQueue, and HWUI RenderThread warm across all 20 enrollment taps with a 2x2 1/255-alpha
 *   center pixel, 18px smaller than 190x190 UI layers and 32px smaller than the 204x204 lightspot.
 * - Pressed illumination (PRESSED_SIZE = 204 at (438, 2054)): renders the factory-calibrated
 *   warm-amber lightspot (/system/etc/udfps_icon_pressed.webp scaled to 204x204 ARGB_8888,
 *   r = 102px = 95px optical cone + 7px anti-vignetting margin) on TYPE_SECURE_SYSTEM_OVERLAY
 *   (2015, layer 33). libfod_zpos_hook.so matches ONLY 200..208px sensor planes, avoiding all
 *   190x190 transparent SystemUI/Settings planes and triggering GF_NET_EVENT_UI_READY cleanly.
 */
public final class SamuraiUdfpsOpticalOverlay {
    private static final String TAG = "SamuraiUdfpsOverlay";
    private static final String CALIBRATED_WEBP_PATH = "/system/etc/udfps_icon_pressed.webp";
    private static final String FP_ENABLE_PATH = "/proc/touchpanel/fp_enable";
    private static final String DIMLAYER_HBM_PATH = "/sys/kernel/oppo_display/dimlayer_hbm";
    private static final String NOTIFY_FPPRESS_PATH = "/sys/kernel/oppo_display/notify_fppress";

    // Native portrait 1080x2400 sensor center: (540, 2156)
    private static final int DISPLAY_W = 1080;
    private static final int DISPLAY_H = 2400;

    // Prewarm geometry (active session, unpressed): 172x172 centered at (540, 2156)
    private static final int PREWARM_SIZE = 172;
    private static final int PREWARM_LEFT = 454;
    private static final int PREWARM_TOP = 2070;

    // Pressed optical lightspot geometry: 204x204 centered at (540, 2156) (14px clear of 190x190 UI)
    private static final int PRESSED_SIZE = 204;
    private static final int PRESSED_LEFT = 438;
    private static final int PRESSED_TOP = 2054;

    private static final long MAX_PRESS_HOLD_MS = 3500L;

    private static final Object LOCK = new Object();
    private static volatile Context sContext;
    private static volatile Handler sMainHandler;
    private static WindowManager sWindowManager;
    private static WindowManager.LayoutParams sLayoutParams;
    private static LightspotView sView;
    private static boolean sViewAdded = false;
    private static int sLastAppliedX = Integer.MIN_VALUE;
    private static int sLastAppliedY = Integer.MIN_VALUE;
    private static int sLastAppliedSize = -1;
    private static volatile boolean sSessionActive = false;
    private static volatile boolean sPressed = false;
    private static Bitmap sPressedBitmap;

    private static final Runnable sAutoReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (sPressed) {
                Log.w(TAG, "Auto-releasing optical UDFPS lightspot after timeout");
                sPressed = false;
                writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
                syncWindowOnMainThread(sSessionActive, false);
            }
        }
    };

    private SamuraiUdfpsOpticalOverlay() {}

    public static void init(Context context) {
        if (context != null && sContext == null) {
            Context appCtx = context.getApplicationContext();
            sContext = (appCtx != null) ? appCtx : context;
        }
        ensureMainHandler();
    }

    private static Handler ensureMainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null) {
                h = new Handler(mainLooper);
                sMainHandler = h;
            }
        }
        return h;
    }

    private static Context resolveContext() {
        Context ctx = sContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method curApp = atClass.getDeclaredMethod("currentApplication");
            Object app = curApp.invoke(null);
            if (app instanceof Context) {
                ctx = (Context) app;
                sContext = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void onSessionStart() {
        sSessionActive = true;
        writeSysfsSafe(FP_ENABLE_PATH, "1");
        Handler h = ensureMainHandler();
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    syncWindowOnMainThread(sSessionActive, sPressed);
                }
            });
        }
    }

    public static void onFingerDown(int x, int y, float minor, float major) {
        sSessionActive = true;
        sPressed = true;
        writeSysfsSafe(FP_ENABLE_PATH, "1");
        writeSysfsSafe(DIMLAYER_HBM_PATH, "1");
        writeSysfsSafe(NOTIFY_FPPRESS_PATH, "1");
        Handler h = ensureMainHandler();
        if (h == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            h.removeCallbacks(sAutoReleaseRunnable);
            syncWindowOnMainThread(true, true);
            h.postDelayed(sAutoReleaseRunnable, MAX_PRESS_HOLD_MS);
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        h.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                try {
                    h.removeCallbacks(sAutoReleaseRunnable);
                    syncWindowOnMainThread(true, true);
                    h.postDelayed(sAutoReleaseRunnable, MAX_PRESS_HOLD_MS);
                    if (sView != null && sViewAdded) {
                        final ViewTreeObserver vto = sView.getViewTreeObserver();
                        if (vto != null && vto.isAlive()) {
                            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        ViewTreeObserver cur = (sView != null) ? sView.getViewTreeObserver() : null;
                                        if (cur != null && cur.isAlive()) {
                                            cur.removeOnPreDrawListener(this);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                    latch.countDown();
                                    return true;
                                }
                            });
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
                latch.countDown();
            }
        });
        try {
            latch.await(16L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static void onFingerUp() {
        sPressed = false;
        writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
        Handler h = ensureMainHandler();
        if (h != null) {
            h.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    h.removeCallbacks(sAutoReleaseRunnable);
                    syncWindowOnMainThread(sSessionActive, false);
                }
            });
        }
    }

    public static void onAcquired(int acquiredInfo, int vendorCode) {
        // Keep lightspot steady on GOOD (0), VENDOR (6), and START (7); release on terminal
        // per-tap retry hints PARTIAL..TOO_FAST (1..5) so the next press gets a fresh UI_READY.
        if (acquiredInfo >= 1 && acquiredInfo <= 5) {
            sPressed = false;
            writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
            Handler h = ensureMainHandler();
            if (h != null) {
                h.postAtFrontOfQueue(new Runnable() {
                    @Override
                    public void run() {
                        h.removeCallbacks(sAutoReleaseRunnable);
                        syncWindowOnMainThread(sSessionActive, false);
                    }
                });
            }
        }
    }

    public static void onEnrollResult(final int remaining) {
        sPressed = false;
        if (remaining <= 0) {
            sSessionActive = false;
        }
        writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
        Handler h = ensureMainHandler();
        if (h != null) {
            h.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    h.removeCallbacks(sAutoReleaseRunnable);
                    syncWindowOnMainThread(remaining > 0 && sSessionActive, false);
                }
            });
        }
    }

    public static void onAuthenticated(final int fingerId) {
        sPressed = false;
        if (fingerId != 0) {
            sSessionActive = false;
        }
        writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
        Handler h = ensureMainHandler();
        if (h != null) {
            h.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    h.removeCallbacks(sAutoReleaseRunnable);
                    syncWindowOnMainThread(fingerId == 0 && sSessionActive, false);
                }
            });
        }
    }

    public static void onError(int error, int vendorCode) {
        onSessionEnd();
    }

    public static void onSessionEnd() {
        sPressed = false;
        sSessionActive = false;
        writeSysfsSafe(NOTIFY_FPPRESS_PATH, "0");
        Handler h = ensureMainHandler();
        if (h != null) {
            h.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    h.removeCallbacks(sAutoReleaseRunnable);
                    syncWindowOnMainThread(false, false);
                }
            });
        }
    }

    private static void syncWindowOnMainThread(boolean sessionActive, boolean pressed) {
        synchronized (LOCK) {
            try {
                Context ctx = resolveContext();
                if (ctx == null) {
                    return;
                }
                if (sWindowManager == null) {
                    sWindowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                }
                if (sWindowManager == null) {
                    return;
                }
                if (sPressedBitmap == null) {
                    sPressedBitmap = loadOrCreateCalibratedLightspot();
                }
                if (sView == null) {
                    sView = new LightspotView(ctx);
                }
                if (sLayoutParams == null) {
                    sLayoutParams = createLayoutParams();
                }

                if (!sessionActive) {
                    if (sViewAdded && sView.getVisibility() != View.GONE) {
                        sView.setVisibility(View.GONE);
                        sView.invalidate();
                    }
                    return;
                }

                int boxLeft = pressed ? PRESSED_LEFT : PREWARM_LEFT;
                int boxTop = pressed ? PRESSED_TOP : PREWARM_TOP;
                int boxSize = pressed ? PRESSED_SIZE : PREWARM_SIZE;
                updateCoordinatesForRotation(ctx, sLayoutParams, boxLeft, boxTop, boxSize);

                if (!sViewAdded) {
                    sView.setVisibility(View.VISIBLE);
                    sWindowManager.addView(sView, sLayoutParams);
                    sViewAdded = true;
                    sLastAppliedX = sLayoutParams.x;
                    sLastAppliedY = sLayoutParams.y;
                    sLastAppliedSize = boxSize;
                } else {
                    boolean wasHidden = (sView.getVisibility() != View.VISIBLE);
                    if (wasHidden) {
                        sView.setVisibility(View.VISIBLE);
                    }
                    if (wasHidden
                            || sLastAppliedX != sLayoutParams.x
                            || sLastAppliedY != sLayoutParams.y
                            || sLastAppliedSize != boxSize) {
                        sWindowManager.updateViewLayout(sView, sLayoutParams);
                        sLastAppliedX = sLayoutParams.x;
                        sLastAppliedY = sLayoutParams.y;
                        sLastAppliedSize = boxSize;
                    }
                }
                sView.invalidate();
            } catch (Throwable t) {
                Log.e(TAG, "Failed to sync optical UDFPS lightspot window", t);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams createLayoutParams() {
        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                PREWARM_SIZE,
                PREWARM_SIZE,
                PREWARM_LEFT,
                PREWARM_TOP,
                2015, // WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY -> layer 33
                flags,
                PixelFormat.RGBA_8888
        );
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("SurfaceView[UdfpsControllerOverlay](BLAST)");

        try {
            Field pfField = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            pfField.setAccessible(true);
            int pf = pfField.getInt(lp);
            // PRIVATE_FLAG_SYSTEM_ERROR (0x10) | PRIVATE_FLAG_NO_MOVE_ANIMATION (0x40)
            // | PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY (0x100000) | PRIVATE_FLAG_TRUSTED_OVERLAY (0x20000000)
            pf |= 0x00000010 | 0x00000040 | 0x00100000 | 0x20000000;
            pfField.setInt(lp, pf);
        } catch (Throwable ignored) {
        }
        return lp;
    }

    private static void updateCoordinatesForRotation(
            Context ctx, WindowManager.LayoutParams lp, int boxLeft, int boxTop, int boxSize) {
        int rotation = Surface.ROTATION_0;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    rotation = d.getRotation();
                }
            }
        } catch (Throwable ignored) {
        }

        int newX = boxLeft;
        int newY = boxTop;
        switch (rotation) {
            case Surface.ROTATION_90:
                newX = boxTop;
                newY = DISPLAY_W - boxLeft - boxSize;
                break;
            case Surface.ROTATION_180:
                newX = DISPLAY_W - boxLeft - boxSize;
                newY = DISPLAY_H - boxTop - boxSize;
                break;
            case Surface.ROTATION_270:
                newX = DISPLAY_H - boxTop - boxSize;
                newY = boxLeft;
                break;
            case Surface.ROTATION_0:
            default:
                newX = boxLeft;
                newY = boxTop;
                break;
        }
        lp.x = newX;
        lp.y = newY;
        lp.width = boxSize;
        lp.height = boxSize;
    }

    private static Bitmap loadOrCreateCalibratedLightspot() {
        try {
            File f = new File(CALIBRATED_WEBP_PATH);
            if (f.isFile()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inScaled = false;
                Bitmap bmp = BitmapFactory.decodeFile(CALIBRATED_WEBP_PATH, opts);
                if (bmp != null) {
                    if (bmp.getWidth() == PRESSED_SIZE && bmp.getHeight() == PRESSED_SIZE) {
                        return bmp;
                    }
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, PRESSED_SIZE, PRESSED_SIZE, true);
                    if (scaled != null) {
                        return scaled;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Falling back to procedural Oppo/Realme calibrated lightspot", t);
        }

        // Exact Oppo/Realme factory Goodix G3 warm-amber radial gradient fallback (204x204):
        // center (255, 208, 188) -> mid (255, 223, 196) -> edge (255, 255, 230)
        Bitmap bmp = Bitmap.createBitmap(PRESSED_SIZE, PRESSED_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float radius = PRESSED_SIZE / 2.0f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(
                radius,
                radius,
                radius,
                new int[] { 0xFFFFD0BC, 0xFFFFDFC4, 0xFFFFFFE6 },
                new float[] { 0.0f, 0.72f, 1.0f },
                Shader.TileMode.CLAMP
        ));
        c.drawCircle(radius, radius, radius, p);
        return bmp;
    }

    private static void writeSysfsSafe(String path, String value) {
        try (FileWriter fw = new FileWriter(path)) {
            fw.write(value);
        } catch (Throwable ignored) {
        }
    }

    private static final class LightspotView extends View {
        private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint mPrewarmPaint = new Paint();

        LightspotView(Context context) {
            super(context);
            mBitmapPaint.setDither(true);
            // 1/255 alpha warm pixel keeps the 172x172 SurfaceFlinger BLAST layer warm during
            // active FOD sessions without colliding with 190x190 UI or 204x204 lightspot sizes.
            mPrewarmPaint.setColor(0x01FFD0BC);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (sPressed && sPressedBitmap != null) {
                canvas.drawBitmap(sPressedBitmap, 0.0f, 0.0f, mBitmapPaint);
            } else if (sSessionActive) {
                canvas.drawRect(85.0f, 85.0f, 87.0f, 87.0f, mPrewarmPaint);
            }
        }
    }
}
