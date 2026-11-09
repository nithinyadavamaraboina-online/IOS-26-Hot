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
 * Ports Project Infinity-X 4.1 (lineage-24.0) optical UDFPS illumination architecture:
 * - Renders the factory-calibrated 190x190 warm-amber lightspot (/system/etc/udfps_icon_pressed.webp,
 *   RGB (255,208,188) center -> (255,255,230) edge) at physical sensor coordinates (445, 2061)
 *   (center (540, 2156), radius 95px on the 1080x2400 Samsung SOFEF03F-M 90Hz AMOLED panel).
 * - Uses TYPE_SECURE_SYSTEM_OVERLAY (2015, WindowManagerPolicy layer 33) with FLAG_NOT_TOUCHABLE
 *   and PRIVATE_FLAG_TRUSTED_OVERLAY so the 190x190 surface sits above all apps, Settings,
 *   Keyguard/LockScreen, NotificationShade, UdfpsTouchOverlay (layer 25), and ControlCenter,
 *   while passing 100% of touch events straight through.
 * - Pre-warms the 190x190 hardware layer on session start so HWC/DRM assigns a dedicated 190x190
 *   SDE plane before finger-down; libfod_zpos_hook.so then tags that plane's PLANE_PROP_ZPOS
 *   with FOD_PRESSED_LAYER_ZORDER (0x20000000u) so sde_crtc.c places the hardware black dim layer
 *   directly below the 190x190 lightspot and triggers GF_NET_EVENT_UI_READY to libgf_hal_G3.so.
 */
public final class SamuraiUdfpsOpticalOverlay {
    private static final String TAG = "SamuraiUdfpsOverlay";
    private static final String CALIBRATED_WEBP_PATH = "/system/etc/udfps_icon_pressed.webp";
    private static final String FP_ENABLE_PATH = "/proc/touchpanel/fp_enable";
    private static final String NOTIFY_FPPRESS_PATH = "/sys/kernel/oppo_display/notify_fppress";

    // Native portrait 1080x2400 sensor geometry: center (540, 2156), radius 95 -> 190x190 at (445, 2061)
    private static final int DISPLAY_W = 1080;
    private static final int DISPLAY_H = 2400;
    private static final int SENSOR_SIZE = 190;
    private static final int SENSOR_LEFT = 445;
    private static final int SENSOR_TOP = 2061;

    private static final long MAX_PRESS_HOLD_MS = 2200L;

    private static final Object LOCK = new Object();
    private static volatile Context sContext;
    private static volatile Handler sMainHandler;
    private static WindowManager sWindowManager;
    private static WindowManager.LayoutParams sLayoutParams;
    private static LightspotView sView;
    private static boolean sViewAdded = false;
    private static volatile boolean sSessionActive = false;
    private static volatile boolean sPressed = false;
    private static Bitmap sPressedBitmap;

    private static final Runnable sAutoReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (sPressed) {
                Log.w(TAG, "Auto-releasing optical UDFPS lightspot after timeout");
                sPressed = false;
                if (sView != null) {
                    sView.invalidate();
                }
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
                    syncWindowOnMainThread(true, sPressed);
                }
            });
        }
    }

    public static void onFingerDown(int x, int y, float minor, float major) {
        sSessionActive = true;
        sPressed = true;
        writeSysfsSafe(FP_ENABLE_PATH, "1");
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
        // Keep lightspot steady during capture; release occurs on enroll/auth result or finger up.
    }

    public static void onEnrollResult(final int remaining) {
        sPressed = false;
        if (remaining <= 0) {
            sSessionActive = false;
        }
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

                updateCoordinatesForRotation(ctx, sLayoutParams);

                if (!pressed) {
                    if (sViewAdded) {
                        sView.setVisibility(View.GONE);
                        sView.invalidate();
                    }
                    return;
                }

                if (!sViewAdded) {
                    sView.setVisibility(View.VISIBLE);
                    sWindowManager.addView(sView, sLayoutParams);
                    sViewAdded = true;
                } else {
                    if (sView.getVisibility() != View.VISIBLE) {
                        sView.setVisibility(View.VISIBLE);
                    }
                    sWindowManager.updateViewLayout(sView, sLayoutParams);
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
                SENSOR_SIZE,
                SENSOR_SIZE,
                SENSOR_LEFT,
                SENSOR_TOP,
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

    private static void updateCoordinatesForRotation(Context ctx, WindowManager.LayoutParams lp) {
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

        int newX = SENSOR_LEFT;
        int newY = SENSOR_TOP;
        switch (rotation) {
            case Surface.ROTATION_90:
                newX = SENSOR_TOP;
                newY = DISPLAY_W - SENSOR_LEFT - SENSOR_SIZE;
                break;
            case Surface.ROTATION_180:
                newX = DISPLAY_W - SENSOR_LEFT - SENSOR_SIZE;
                newY = DISPLAY_H - SENSOR_TOP - SENSOR_SIZE;
                break;
            case Surface.ROTATION_270:
                newX = DISPLAY_H - SENSOR_TOP - SENSOR_SIZE;
                newY = SENSOR_LEFT;
                break;
            case Surface.ROTATION_0:
            default:
                newX = SENSOR_LEFT;
                newY = SENSOR_TOP;
                break;
        }
        lp.x = newX;
        lp.y = newY;
        lp.width = SENSOR_SIZE;
        lp.height = SENSOR_SIZE;
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
                    if (bmp.getWidth() == SENSOR_SIZE && bmp.getHeight() == SENSOR_SIZE) {
                        return bmp;
                    }
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, SENSOR_SIZE, SENSOR_SIZE, true);
                    if (scaled != null) {
                        return scaled;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Falling back to procedural Oppo/Realme calibrated lightspot", t);
        }

        // Exact Oppo/Realme factory Goodix G3 warm-amber radial gradient fallback:
        // center (255, 208, 188) -> mid (255, 223, 196) -> edge (255, 255, 230)
        Bitmap bmp = Bitmap.createBitmap(SENSOR_SIZE, SENSOR_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float radius = SENSOR_SIZE / 2.0f;
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
            // 1/255 alpha warm pixel keeps the 190x190 SurfaceFlinger layer & HWC pipe allocated
            // during active FOD sessions while remaining 100% invisible when unpressed.
            mPrewarmPaint.setColor(0x01FFD0BC);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (sPressed && sPressedBitmap != null) {
                canvas.drawBitmap(sPressedBitmap, 0.0f, 0.0f, mBitmapPaint);
            } else if (sSessionActive) {
                canvas.drawRect(94.0f, 94.0f, 96.0f, 96.0f, mPrewarmPaint);
            }
        }
    }
}
