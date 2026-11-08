package com.realme.samurai.systemui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.TypedValue;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clean-room iOS 26 SF-geometric vector glyph renderer & RMX1931 hardware/system action bridge.
 */
public final class SystemUiGlassHelper {

    public static final int GLYPH_AIRPLANE = 1;
    public static final int GLYPH_CELLULAR = 2;
    public static final int GLYPH_WIFI = 3;
    public static final int GLYPH_BLUETOOTH = 4;
    public static final int GLYPH_PREV = 5;
    public static final int GLYPH_PLAY = 6;
    public static final int GLYPH_PAUSE = 7;
    public static final int GLYPH_NEXT = 8;
    public static final int GLYPH_MUSIC = 9;
    public static final int GLYPH_SUN = 10;
    public static final int GLYPH_SPEAKER_MUTE = 11;
    public static final int GLYPH_SPEAKER_LOW = 12;
    public static final int GLYPH_SPEAKER_HIGH = 13;
    public static final int GLYPH_FLASHLIGHT = 14;
    public static final int GLYPH_ROTATION = 15;
    public static final int GLYPH_BELL = 16;
    public static final int GLYPH_BELL_SLASH = 17;
    public static final int GLYPH_VIBRATE = 18;
    public static final int GLYPH_CALC = 19;
    public static final int GLYPH_TIMER = 20;
    public static final int GLYPH_CAMERA = 21;
    public static final int GLYPH_PHONE = 22;
    public static final int GLYPH_PROMOTION_90 = 23;
    public static final int GLYPH_DC_DIM = 24;
    public static final int GLYPH_VOOC_BOLT = 25;
    public static final int GLYPH_PADLOCK_CLOSED = 26;
    public static final int GLYPH_PADLOCK_OPEN = 27;
    public static final int GLYPH_FINGERPRINT = 28;
    public static final int GLYPH_POWER = 29;
    public static final int GLYPH_REBOOT = 30;
    public static final int GLYPH_RECOVERY = 31;
    public static final int GLYPH_CLOSE = 32;
    public static final int GLYPH_CHEVRON_UP = 33;

    private static final String HW_QUEUE_DIR = "/data/ota_package/hw/queue";
    private static final String HW_TELEMETRY_FILE = "/data/ota_package/hw/telemetry.prop";
    private static final String KEY_CC_90HZ = "samurai_cc_90hz";
    private static final String KEY_CC_DC_DIM = "samurai_cc_dc_dim";
    private static final String KEY_CC_CHG_LIMIT = "samurai_cc_chg_limit";

    private static final AtomicLong sSeq = new AtomicLong(1L);
    private static final CopyOnWriteArrayList<Runnable> sTorchListeners = new CopyOnWriteArrayList<>();
    private static volatile boolean sTorchRegistered = false;
    private static volatile boolean sTorchEnabled = false;
    private static volatile String sTorchCameraId = null;

    private SystemUiGlassHelper() {}

    public static float dp(Context ctx, float dp) {
        return dp * ctx.getResources().getDisplayMetrics().density;
    }

    public static int dpInt(Context ctx, float dp) {
        return Math.round(dp(ctx, dp));
    }

    public static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, ctx.getResources().getDisplayMetrics());
    }

    // -------------------------------------------------------------------------
    // Hardware Torch (CameraManager.setTorchMode + live TorchCallback)
    // -------------------------------------------------------------------------

    public static void ensureTorchMonitor(Context ctx) {
        if (sTorchRegistered || ctx == null) return;
        synchronized (SystemUiGlassHelper.class) {
            if (sTorchRegistered) return;
            try {
                CameraManager cm = ctx.getSystemService(CameraManager.class);
                if (cm == null) return;
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                    Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash)
                            && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                        sTorchCameraId = id;
                        break;
                    }
                }
                if (sTorchCameraId == null) {
                    for (String id : cm.getCameraIdList()) {
                        CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                        if (Boolean.TRUE.equals(cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                            sTorchCameraId = id;
                            break;
                        }
                    }
                }
                cm.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String cameraId, boolean enabled) {
                        if (sTorchCameraId == null || sTorchCameraId.equals(cameraId)) {
                            sTorchEnabled = enabled;
                            notifyTorchListeners();
                        }
                    }
                }, new Handler(Looper.getMainLooper()));
                sTorchRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void addTorchListener(Runnable listener) {
        if (listener != null && !sTorchListeners.contains(listener)) {
            sTorchListeners.add(listener);
        }
    }

    private static void notifyTorchListeners() {
        for (Runnable r : sTorchListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isTorchEnabled(Context ctx) {
        ensureTorchMonitor(ctx);
        return sTorchEnabled;
    }

    public static boolean toggleTorch(Context ctx) {
        if (ctx == null) return false;
        ensureTorchMonitor(ctx);
        try {
            CameraManager cm = ctx.getSystemService(CameraManager.class);
            if (cm != null && sTorchCameraId != null) {
                boolean next = !sTorchEnabled;
                cm.setTorchMode(sTorchCameraId, next);
                sTorchEnabled = next;
                notifyTorchListeners();
                return next;
            }
        } catch (Throwable ignored) {
        }
        return sTorchEnabled;
    }

    // -------------------------------------------------------------------------
    // Connectivity & Audio / System State Helpers
    // -------------------------------------------------------------------------

    public static boolean isAirplaneModeOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean toggleAirplaneMode(Context ctx) {
        boolean next = !isAirplaneModeOn(ctx);
        try {
            Settings.Global.putInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, next ? 1 : 0);
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", next);
            ctx.sendBroadcast(intent);
            return next;
        } catch (Throwable ignored) {
            openSettingsAction(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS);
            return isAirplaneModeOn(ctx);
        }
    }

    public static boolean isMobileDataEnabled(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null) {
                return tm.isDataEnabled();
            }
        } catch (Throwable ignored) {
        }
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "mobile_data", 1) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean toggleMobileData(Context ctx) {
        boolean cur = isMobileDataEnabled(ctx);
        boolean next = !cur;
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null) {
                Method m = TelephonyManager.class.getMethod("setDataEnabled", boolean.class);
                m.invoke(tm, next);
                return next;
            }
        } catch (Throwable ignored) {
        }
        openSettingsAction(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        return cur;
    }

    public static boolean isWifiEnabled(Context ctx) {
        try {
            WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
            return wm != null && wm.isWifiEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static String getConnectedWifiSsid(Context ctx) {
        try {
            WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
            if (wm == null || !wm.isWifiEnabled()) return "Off";
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                String ssid = info.getSSID();
                if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                    if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 2) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    return ssid;
                }
                return "Connected";
            }
            return "On";
        } catch (Throwable ignored) {
            return isWifiEnabled(ctx) ? "On" : "Off";
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean toggleWifi(Context ctx) {
        boolean cur = isWifiEnabled(ctx);
        try {
            WifiManager wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
            if (wm != null) {
                if (wm.setWifiEnabled(!cur)) {
                    return !cur;
                }
            }
        } catch (Throwable ignored) {
        }
        openSettingsAction(ctx, Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
        return cur;
    }

    public static boolean isBluetoothEnabled(Context ctx) {
        try {
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter ba = (bm != null) ? bm.getAdapter() : null;
            return ba != null && ba.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean toggleBluetooth(Context ctx) {
        try {
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter ba = (bm != null) ? bm.getAdapter() : null;
            if (ba != null) {
                if (ba.isEnabled()) {
                    ba.disable();
                    return false;
                } else {
                    ba.enable();
                    return true;
                }
            }
        } catch (Throwable ignored) {
            openSettingsAction(ctx, Settings.ACTION_BLUETOOTH_SETTINGS);
        }
        return isBluetoothEnabled(ctx);
    }

    public static boolean isRotationLocked(Context ctx) {
        try {
            return Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean toggleRotationLock(Context ctx) {
        boolean locked = isRotationLocked(ctx);
        boolean nextLocked = !locked;
        try {
            Settings.System.putInt(
                    ctx.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    nextLocked ? 0 : 1);
        } catch (Throwable ignored) {
        }
        return nextLocked;
    }

    public static int getRingerMode(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            return (am != null) ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
        } catch (Throwable ignored) {
            return AudioManager.RINGER_MODE_NORMAL;
        }
    }

    public static int cycleRingerMode(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return AudioManager.RINGER_MODE_NORMAL;
            int cur = am.getRingerMode();
            int next;
            if (cur == AudioManager.RINGER_MODE_NORMAL) {
                next = AudioManager.RINGER_MODE_VIBRATE;
            } else if (cur == AudioManager.RINGER_MODE_VIBRATE) {
                next = AudioManager.RINGER_MODE_SILENT;
            } else {
                next = AudioManager.RINGER_MODE_NORMAL;
            }
            try {
                am.setRingerMode(next);
            } catch (SecurityException se) {
                // If Zen policy blocks RINGER_MODE_SILENT, toggle Normal <-> Vibrate cleanly
                next = (cur == AudioManager.RINGER_MODE_VIBRATE)
                        ? AudioManager.RINGER_MODE_NORMAL
                        : AudioManager.RINGER_MODE_VIBRATE;
                am.setRingerMode(next);
            }
            return next;
        } catch (Throwable ignored) {
            return getRingerMode(ctx);
        }
    }

    public static int getScreenBrightness(Context ctx) {
        try {
            return Math.max(1, Math.min(255, Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128)));
        } catch (Throwable ignored) {
            return 128;
        }
    }

    public static void setScreenBrightness(Context ctx, int value255) {
        int clamped = Math.max(1, Math.min(255, value255));
        try {
            ContentResolver cr = ctx.getContentResolver();
            Settings.System.putInt(
                    cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, clamped);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // RMX1931 Exclusive Hardware Bridge (90Hz ProMotion, DC Dimming, SuperVOOC 80%)
    // -------------------------------------------------------------------------

    public static boolean isProMotion90Hz(Context ctx) {
        try {
            float peak = Settings.System.getFloat(ctx.getContentResolver(), "peak_refresh_rate", 90.0f);
            return peak >= 85.0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean toggleProMotion90Hz(Context ctx) {
        boolean next90 = !isProMotion90Hz(ctx);
        float rate = next90 ? 90.0f : 60.0f;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Settings.System.putFloat(cr, "peak_refresh_rate", rate);
            Settings.System.putFloat(cr, "min_refresh_rate", rate);
            Settings.Secure.putInt(cr, KEY_CC_90HZ, next90 ? 1 : 0);
        } catch (Throwable ignored) {
        }
        return next90;
    }

    public static boolean isDcDimmingEnabled(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), KEY_CC_DC_DIM, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean toggleDcDimming(Context ctx) {
        boolean next = !isDcDimmingEnabled(ctx);
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_CC_DC_DIM, next ? 1 : 0);
        } catch (Throwable ignored) {
        }
        writeHwQueueCommand("DC_DIM=" + (next ? 1 : 0));
        return next;
    }

    public static boolean isVooc80LimitEnabled(Context ctx) {
        try {
            int limit = Settings.Secure.getInt(ctx.getContentResolver(), KEY_CC_CHG_LIMIT, 100);
            return limit <= 80;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean toggleVooc80Limit(Context ctx) {
        boolean next80 = !isVooc80LimitEnabled(ctx);
        int limit = next80 ? 80 : 100;
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_CC_CHG_LIMIT, limit);
        } catch (Throwable ignored) {
        }
        writeHwQueueCommand("CHG_LIMIT=" + limit);
        return next80;
    }

    public static void writeHwQueueCommand(String line) {
        if (line == null || line.isEmpty()) return;
        try {
            File qDir = new File(HW_QUEUE_DIR);
            if (!qDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                qDir.mkdirs();
            }
            String id = System.nanoTime() + "_" + sSeq.getAndIncrement();
            File tmp = new File(qDir, ".tmp_" + id);
            File req = new File(qDir, "cmd_" + id + ".req");
            byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(payload);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, false);
            if (!tmp.renameTo(req)) {
                try (FileOutputStream fos = new FileOutputStream(req)) {
                    fos.write(payload);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isVoocFastChargeActive() {
        File f = new File(HW_TELEMETRY_FILE);
        if (!f.isFile()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("vooc_active=")) {
                    String val = line.substring("vooc_active=".length()).trim();
                    return !"0".equals(val) && !val.isEmpty();
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // App Launchers & Power Execution
    // -------------------------------------------------------------------------

    public static void openSettingsAction(Context ctx, String action) {
        if (ctx == null || action == null) return;
        try {
            SamuraiSystemUiHooks.collapseControlCenter(true);
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    public static void launchPackageOrFallback(Context ctx, String primaryPkg, String fallbackPkg, String fallbackAction) {
        if (ctx == null) return;
        SamuraiSystemUiHooks.collapseControlCenter(true);
        SamuraiSystemUiHooks.collapseDynamicIsland();
        for (String pkg : new String[] { primaryPkg, fallbackPkg }) {
            if (pkg == null || pkg.isEmpty()) continue;
            try {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    ctx.startActivity(launch);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        if (fallbackAction != null) {
            openSettingsAction(ctx, fallbackAction);
        }
    }

    public static void launchCalculator(Context ctx) {
        launchPackageOrFallback(ctx, "com.realme.samurai.calculator", "com.google.android.calculator", null);
    }

    public static void launchClock(Context ctx) {
        launchPackageOrFallback(ctx, "com.realme.samurai.clock", "com.google.android.deskclock", null);
    }

    public static void launchDialer(Context ctx) {
        launchPackageOrFallback(ctx, "com.mayoh.contact.icontact", "com.google.android.dialer", Intent.ACTION_DIAL);
    }

    public static void launchCamera(Context ctx, boolean fromLockScreen) {
        if (ctx == null) return;
        SamuraiSystemUiHooks.collapseControlCenter(true);
        SamuraiSystemUiHooks.collapseDynamicIsland();
        if (fromLockScreen) {
            try {
                Intent secureCam = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                secureCam.setPackage("com.google.android.GoogleCameraEng");
                secureCam.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                ctx.startActivity(secureCam);
                return;
            } catch (Throwable ignored) {
            }
            try {
                Intent secureGeneric = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                secureGeneric.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                ctx.startActivity(secureGeneric);
                return;
            } catch (Throwable ignored) {
            }
        }
        launchPackageOrFallback(
                ctx,
                "com.google.android.GoogleCameraEng",
                "com.android.camera2",
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
    }

    public static void executeShutdown(Object wmFuncs) {
        if (wmFuncs != null) {
            try {
                Method m = wmFuncs.getClass().getMethod("shutdown");
                m.setAccessible(true);
                m.invoke(wmFuncs);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, Context.POWER_SERVICE);
            Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
            Object ipm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            ipm.getClass()
                    .getMethod("shutdown", boolean.class, String.class, boolean.class)
                    .invoke(ipm, false, "userrequested", false);
        } catch (Throwable ignored) {
            writeHwQueueCommand("POWER_ACTION=shutdown");
        }
    }

    public static void executeReboot(Context ctx, Object wmFuncs, String reason) {
        if (reason == null && wmFuncs != null) {
            try {
                Method m = wmFuncs.getClass().getMethod("reboot", boolean.class);
                m.setAccessible(true);
                m.invoke(wmFuncs, false);
                return;
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            try {
                PowerManager pm = ctx.getSystemService(PowerManager.class);
                if (pm != null) {
                    pm.reboot(reason);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, Context.POWER_SERVICE);
            Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
            Object ipm = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            ipm.getClass()
                    .getMethod("reboot", boolean.class, String.class, boolean.class)
                    .invoke(ipm, false, reason, false);
            return;
        } catch (Throwable ignored) {
        }
        if ("recovery".equals(reason)) {
            writeHwQueueCommand("REBOOT_RECOVERY=1");
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sp.getMethod("set", String.class, String.class).invoke(null, "sys.powerctl", "reboot,recovery");
                return;
            } catch (Throwable ignored) {
            }
        }
        if (wmFuncs != null) {
            try {
                Method m = wmFuncs.getClass().getMethod("reboot", boolean.class);
                m.setAccessible(true);
                m.invoke(wmFuncs, false);
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Crisp SF-Style Vector Glyph Renderer (Zero Emoji, Sub-pixel Anti-Aliased)
    // -------------------------------------------------------------------------

    public static void drawGlyph(Canvas c, int glyph, float cx, float cy, float size, int color) {
        float r = size * 0.5f;
        float sw = Math.max(1.8f, size * 0.105f);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(sw);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);

        Path p = new Path();
        RectF rf = new RectF();

        switch (glyph) {
            case GLYPH_AIRPLANE: {
                c.save();
                c.translate(cx, cy);
                c.rotate(-45f);
                c.drawLine(0f, -r * 0.82f, 0f, r * 0.82f, stroke);
                p.moveTo(-r * 0.82f, r * 0.08f);
                p.lineTo(0f, -r * 0.25f);
                p.lineTo(r * 0.82f, r * 0.08f);
                c.drawPath(p, stroke);
                p.reset();
                p.moveTo(-r * 0.36f, r * 0.75f);
                p.lineTo(0f, r * 0.52f);
                p.lineTo(r * 0.36f, r * 0.75f);
                c.drawPath(p, stroke);
                c.restore();
                break;
            }
            case GLYPH_CELLULAR: {
                c.drawCircle(cx, cy - r * 0.20f, sw * 0.75f, fill);
                c.drawLine(cx, cy - r * 0.05f, cx, cy + r * 0.78f, stroke);
                rf.set(cx - r * 0.45f, cy - r * 0.65f, cx + r * 0.45f, cy + r * 0.25f);
                c.drawArc(rf, 145f, 70f, false, stroke);
                c.drawArc(rf, -35f, 70f, false, stroke);
                rf.set(cx - r * 0.80f, cy - r * 1.00f, cx + r * 0.80f, cy + r * 0.60f);
                c.drawArc(rf, 145f, 70f, false, stroke);
                c.drawArc(rf, -35f, 70f, false, stroke);
                break;
            }
            case GLYPH_WIFI: {
                c.drawCircle(cx, cy + r * 0.56f, sw * 0.85f, fill);
                rf.set(cx - r * 0.45f, cy + r * 0.08f, cx + r * 0.45f, cy + r * 0.98f);
                c.drawArc(rf, 222f, 96f, false, stroke);
                rf.set(cx - r * 0.82f, cy - r * 0.30f, cx + r * 0.82f, cy + r * 1.34f);
                c.drawArc(rf, 222f, 96f, false, stroke);
                rf.set(cx - r * 1.16f, cy - r * 0.66f, cx + r * 1.16f, cy + r * 1.66f);
                c.drawArc(rf, 225f, 90f, false, stroke);
                break;
            }
            case GLYPH_BLUETOOTH: {
                p.moveTo(cx - r * 0.38f, cy - r * 0.38f);
                p.lineTo(cx + r * 0.42f, cy + r * 0.40f);
                p.lineTo(cx, cy + r * 0.82f);
                p.lineTo(cx, cy - r * 0.82f);
                p.lineTo(cx + r * 0.42f, cy - r * 0.40f);
                p.lineTo(cx - r * 0.38f, cy + r * 0.38f);
                c.drawPath(p, stroke);
                break;
            }
            case GLYPH_PLAY: {
                p.moveTo(cx - r * 0.38f, cy - r * 0.62f);
                p.lineTo(cx + r * 0.62f, cy);
                p.lineTo(cx - r * 0.38f, cy + r * 0.62f);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case GLYPH_PAUSE: {
                float bw = r * 0.28f;
                rf.set(cx - r * 0.52f, cy - r * 0.62f, cx - r * 0.52f + bw, cy + r * 0.62f);
                c.drawRoundRect(rf, bw * 0.4f, bw * 0.4f, fill);
                rf.set(cx + r * 0.52f - bw, cy - r * 0.62f, cx + r * 0.52f, cy + r * 0.62f);
                c.drawRoundRect(rf, bw * 0.4f, bw * 0.4f, fill);
                break;
            }
            case GLYPH_PREV: {
                c.drawLine(cx - r * 0.56f, cy - r * 0.52f, cx - r * 0.56f, cy + r * 0.52f, stroke);
                p.moveTo(cx + r * 0.52f, cy - r * 0.54f);
                p.lineTo(cx - r * 0.36f, cy);
                p.lineTo(cx + r * 0.52f, cy + r * 0.54f);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case GLYPH_NEXT: {
                c.drawLine(cx + r * 0.56f, cy - r * 0.52f, cx + r * 0.56f, cy + r * 0.52f, stroke);
                p.moveTo(cx - r * 0.52f, cy - r * 0.54f);
                p.lineTo(cx + r * 0.36f, cy);
                p.lineTo(cx - r * 0.52f, cy + r * 0.54f);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case GLYPH_MUSIC: {
                c.drawCircle(cx - r * 0.36f, cy + r * 0.46f, r * 0.25f, fill);
                c.drawCircle(cx + r * 0.34f, cy + r * 0.32f, r * 0.25f, fill);
                p.moveTo(cx - r * 0.14f, cy + r * 0.44f);
                p.lineTo(cx - r * 0.14f, cy - r * 0.54f);
                p.lineTo(cx + r * 0.56f, cy - r * 0.70f);
                p.lineTo(cx + r * 0.56f, cy + r * 0.30f);
                c.drawPath(p, stroke);
                break;
            }
            case GLYPH_SUN: {
                c.drawCircle(cx, cy, r * 0.38f, fill);
                for (int i = 0; i < 8; i++) {
                    double a = i * (Math.PI / 4.0);
                    float x0 = cx + (float) Math.cos(a) * r * 0.58f;
                    float y0 = cy + (float) Math.sin(a) * r * 0.58f;
                    float x1 = cx + (float) Math.cos(a) * r * 0.86f;
                    float y1 = cy + (float) Math.sin(a) * r * 0.86f;
                    c.drawLine(x0, y0, x1, y1, stroke);
                }
                break;
            }
            case GLYPH_SPEAKER_MUTE:
            case GLYPH_SPEAKER_LOW:
            case GLYPH_SPEAKER_HIGH: {
                p.moveTo(cx - r * 0.68f, cy - r * 0.24f);
                p.lineTo(cx - r * 0.36f, cy - r * 0.24f);
                p.lineTo(cx + r * 0.04f, cy - r * 0.60f);
                p.lineTo(cx + r * 0.04f, cy + r * 0.60f);
                p.lineTo(cx - r * 0.36f, cy + r * 0.24f);
                p.lineTo(cx - r * 0.68f, cy + r * 0.24f);
                p.close();
                c.drawPath(p, fill);
                if (glyph == GLYPH_SPEAKER_MUTE) {
                    c.drawLine(cx + r * 0.28f, cy - r * 0.28f, cx + r * 0.74f, cy + r * 0.28f, stroke);
                    c.drawLine(cx + r * 0.74f, cy - r * 0.28f, cx + r * 0.28f, cy + r * 0.28f, stroke);
                } else {
                    rf.set(cx - r * 0.10f, cy - r * 0.42f, cx + r * 0.44f, cy + r * 0.42f);
                    c.drawArc(rf, -45f, 90f, false, stroke);
                    if (glyph == GLYPH_SPEAKER_HIGH) {
                        rf.set(cx - r * 0.22f, cy - r * 0.74f, cx + r * 0.78f, cy + r * 0.74f);
                        c.drawArc(rf, -45f, 90f, false, stroke);
                    }
                }
                break;
            }
            case GLYPH_FLASHLIGHT: {
                rf.set(cx - r * 0.34f, cy - r * 0.72f, cx + r * 0.34f, cy - r * 0.34f);
                c.drawRoundRect(rf, sw, sw, stroke);
                rf.set(cx - r * 0.22f, cy - r * 0.34f, cx + r * 0.22f, cy + r * 0.76f);
                c.drawRoundRect(rf, sw, sw, stroke);
                c.drawCircle(cx, cy + r * 0.14f, sw * 0.75f, fill);
                break;
            }
            case GLYPH_ROTATION: {
                rf.set(cx - r * 0.68f, cy - r * 0.68f, cx + r * 0.68f, cy + r * 0.68f);
                c.drawArc(rf, -55f, 285f, false, stroke);
                p.moveTo(cx + r * 0.16f, cy - r * 0.76f);
                p.lineTo(cx + r * 0.56f, cy - r * 0.58f);
                p.lineTo(cx + r * 0.34f, cy - r * 0.20f);
                c.drawPath(p, stroke);
                rf.set(cx - r * 0.24f, cy - r * 0.14f, cx + r * 0.24f, cy + r * 0.30f);
                c.drawRoundRect(rf, sw * 0.5f, sw * 0.5f, fill);
                break;
            }
            case GLYPH_BELL:
            case GLYPH_BELL_SLASH: {
                p.moveTo(cx - r * 0.54f, cy + r * 0.34f);
                p.lineTo(cx + r * 0.54f, cy + r * 0.34f);
                p.lineTo(cx + r * 0.42f, cy - r * 0.16f);
                p.quadTo(cx + r * 0.40f, cy - r * 0.66f, cx, cy - r * 0.66f);
                p.quadTo(cx - r * 0.40f, cy - r * 0.66f, cx - r * 0.42f, cy - r * 0.16f);
                p.close();
                c.drawPath(p, stroke);
                c.drawCircle(cx, cy + r * 0.58f, r * 0.15f, fill);
                if (glyph == GLYPH_BELL_SLASH) {
                    c.drawLine(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f, stroke);
                }
                break;
            }
            case GLYPH_VIBRATE: {
                rf.set(cx - r * 0.36f, cy - r * 0.56f, cx + r * 0.36f, cy + r * 0.56f);
                c.drawRoundRect(rf, sw, sw, stroke);
                c.drawLine(cx - r * 0.68f, cy - r * 0.34f, cx - r * 0.68f, cy + r * 0.34f, stroke);
                c.drawLine(cx + r * 0.68f, cy - r * 0.34f, cx + r * 0.68f, cy + r * 0.34f, stroke);
                break;
            }
            case GLYPH_CALC: {
                rf.set(cx - r * 0.58f, cy - r * 0.72f, cx + r * 0.58f, cy + r * 0.72f);
                c.drawRoundRect(rf, sw * 1.4f, sw * 1.4f, stroke);
                c.drawLine(cx - r * 0.34f, cy - r * 0.36f, cx + r * 0.34f, cy - r * 0.36f, stroke);
                for (int gx = -1; gx <= 1; gx += 2) {
                    for (int gy = 0; gy <= 1; gy++) {
                        c.drawCircle(cx + gx * r * 0.24f, cy + r * (0.06f + gy * 0.34f), sw * 0.65f, fill);
                    }
                }
                break;
            }
            case GLYPH_TIMER: {
                c.drawCircle(cx, cy + r * 0.08f, r * 0.66f, stroke);
                c.drawLine(cx - r * 0.24f, cy - r * 0.78f, cx + r * 0.24f, cy - r * 0.78f, stroke);
                c.drawLine(cx, cy + r * 0.08f, cx + r * 0.32f, cy - r * 0.24f, stroke);
                break;
            }
            case GLYPH_CAMERA: {
                rf.set(cx - r * 0.74f, cy - r * 0.42f, cx + r * 0.74f, cy + r * 0.58f);
                c.drawRoundRect(rf, sw * 1.5f, sw * 1.5f, stroke);
                p.moveTo(cx - r * 0.34f, cy - r * 0.42f);
                p.lineTo(cx - r * 0.20f, cy - r * 0.66f);
                p.lineTo(cx + r * 0.20f, cy - r * 0.66f);
                p.lineTo(cx + r * 0.34f, cy - r * 0.42f);
                c.drawPath(p, stroke);
                c.drawCircle(cx, cy + r * 0.08f, r * 0.28f, stroke);
                break;
            }
            case GLYPH_PHONE: {
                rf.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f);
                c.drawArc(rf, 118f, 124f, false, stroke);
                c.drawCircle(cx - r * 0.36f, cy - r * 0.48f, sw * 0.9f, fill);
                c.drawCircle(cx + r * 0.36f, cy + r * 0.48f, sw * 0.9f, fill);
                break;
            }
            case GLYPH_PROMOTION_90: {
                rf.set(cx - r * 0.74f, cy - r * 0.66f, cx + r * 0.74f, cy + r * 0.74f);
                c.drawArc(rf, 155f, 230f, false, stroke);
                c.drawLine(cx, cy + r * 0.08f, cx + r * 0.44f, cy - r * 0.34f, stroke);
                c.drawCircle(cx, cy + r * 0.08f, sw * 0.85f, fill);
                break;
            }
            case GLYPH_DC_DIM: {
                p.moveTo(cx - r * 0.78f, cy);
                p.quadTo(cx, cy - r * 0.68f, cx + r * 0.78f, cy);
                p.quadTo(cx, cy + r * 0.68f, cx - r * 0.78f, cy);
                c.drawPath(p, stroke);
                c.drawCircle(cx, cy, r * 0.24f, fill);
                break;
            }
            case GLYPH_VOOC_BOLT: {
                p.moveTo(cx + r * 0.14f, cy - r * 0.82f);
                p.lineTo(cx - r * 0.48f, cy + r * 0.06f);
                p.lineTo(cx + r * 0.04f, cy + r * 0.06f);
                p.lineTo(cx - r * 0.14f, cy + r * 0.82f);
                p.lineTo(cx + r * 0.48f, cy - r * 0.06f);
                p.lineTo(cx - r * 0.04f, cy - r * 0.06f);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case GLYPH_PADLOCK_CLOSED:
            case GLYPH_PADLOCK_OPEN: {
                rf.set(cx - r * 0.52f, cy - r * 0.08f, cx + r * 0.52f, cy + r * 0.74f);
                c.drawRoundRect(rf, sw * 1.4f, sw * 1.4f, fill);
                rf.set(cx - r * 0.34f, cy - r * 0.72f, cx + r * 0.34f, cy + r * 0.08f);
                if (glyph == GLYPH_PADLOCK_CLOSED) {
                    c.drawArc(rf, 180f, 180f, false, stroke);
                } else {
                    c.drawArc(rf, 205f, 155f, false, stroke);
                }
                break;
            }
            case GLYPH_FINGERPRINT: {
                rf.set(cx - r * 0.26f, cy - r * 0.36f, cx + r * 0.26f, cy + r * 0.42f);
                c.drawArc(rf, 190f, 220f, false, stroke);
                rf.set(cx - r * 0.54f, cy - r * 0.62f, cx + r * 0.54f, cy + r * 0.64f);
                c.drawArc(rf, 195f, 195f, false, stroke);
                rf.set(cx - r * 0.80f, cy - r * 0.86f, cx + r * 0.80f, cy + r * 0.84f);
                c.drawArc(rf, 210f, 150f, false, stroke);
                c.drawLine(cx, cy - r * 0.10f, cx, cy + r * 0.48f, stroke);
                break;
            }
            case GLYPH_POWER: {
                rf.set(cx - r * 0.66f, cy - r * 0.62f, cx + r * 0.66f, cy + r * 0.70f);
                c.drawArc(rf, -58f, 296f, false, stroke);
                c.drawLine(cx, cy - r * 0.76f, cx, cy - r * 0.04f, stroke);
                break;
            }
            case GLYPH_REBOOT: {
                rf.set(cx - r * 0.66f, cy - r * 0.66f, cx + r * 0.66f, cy + r * 0.66f);
                c.drawArc(rf, -45f, 285f, false, stroke);
                p.moveTo(cx + r * 0.18f, cy - r * 0.74f);
                p.lineTo(cx + r * 0.58f, cy - r * 0.54f);
                p.lineTo(cx + r * 0.34f, cy - r * 0.16f);
                c.drawPath(p, stroke);
                break;
            }
            case GLYPH_RECOVERY: {
                c.drawCircle(cx, cy, r * 0.34f, stroke);
                for (int i = 0; i < 6; i++) {
                    double a = i * (Math.PI / 3.0);
                    float x0 = cx + (float) Math.cos(a) * r * 0.46f;
                    float y0 = cy + (float) Math.sin(a) * r * 0.46f;
                    float x1 = cx + (float) Math.cos(a) * r * 0.76f;
                    float y1 = cy + (float) Math.sin(a) * r * 0.76f;
                    c.drawLine(x0, y0, x1, y1, stroke);
                }
                break;
            }
            case GLYPH_CLOSE: {
                c.drawLine(cx - r * 0.48f, cy - r * 0.48f, cx + r * 0.48f, cy + r * 0.48f, stroke);
                c.drawLine(cx + r * 0.48f, cy - r * 0.48f, cx - r * 0.48f, cy + r * 0.48f, stroke);
                break;
            }
            case GLYPH_CHEVRON_UP: {
                p.moveTo(cx - r * 0.56f, cy + r * 0.22f);
                p.lineTo(cx, cy - r * 0.28f);
                p.lineTo(cx + r * 0.56f, cy + r * 0.22f);
                c.drawPath(p, stroke);
                break;
            }
            default:
                c.drawCircle(cx, cy, r * 0.35f, fill);
                break;
        }
    }
}
