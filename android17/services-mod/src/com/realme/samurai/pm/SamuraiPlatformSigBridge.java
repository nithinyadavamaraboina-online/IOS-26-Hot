package com.realme.samurai.pm;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.pkg.PackageState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/**
 * Strictly-scoped Android 17 platform-signature bridge for read-only `/system` and `/system_ext`
 * privileged packages signed with the pinned `samurai-ota-release.jks` certificate.
 *
 * Security invariants:
 * 1. NEVER trusts `/data/app` user apps or updated system apps (`!isUpdatedSystemApp()`).
 * 2. Requires `isSystem() && isPrivileged()` AND an exact 256-bit SHA-256 certificate match
 *    against `SAMURAI_OTA_SHA256`.
 * 3. Allows clean dirty-OTA transitions on `com.android.systemui` (`android.uid.systemui`) between
 *    the stock CP3A platform cert and `SAMURAI_OTA_SHA256` without affecting Play Integrity or GMS.
 */
public final class SamuraiPlatformSigBridge {

    // SHA-256 of samurai-ota-release.jks (alias: samuraiota)
    private static final byte[] SAMURAI_OTA_SHA256 = new byte[] {
            (byte) 0x10, (byte) 0x03, (byte) 0xa2, (byte) 0x72,
            (byte) 0xf8, (byte) 0x79, (byte) 0x9e, (byte) 0x74,
            (byte) 0x0c, (byte) 0x52, (byte) 0xd1, (byte) 0x1a,
            (byte) 0x35, (byte) 0xde, (byte) 0x7d, (byte) 0x7d,
            (byte) 0xfc, (byte) 0xb5, (byte) 0xca, (byte) 0xbd,
            (byte) 0xd2, (byte) 0xeb, (byte) 0xfb, (byte) 0x8c,
            (byte) 0xa3, (byte) 0x1c, (byte) 0x9d, (byte) 0x34,
            (byte) 0xa6, (byte) 0x0e, (byte) 0x86, (byte) 0x9a
    };

    // SHA-256 of stock Android 17 CP3A.260905.010 platform certificate
    private static final byte[] STOCK_PLATFORM_SHA256 = new byte[] {
            (byte) 0x4b, (byte) 0x92, (byte) 0xab, (byte) 0xd4,
            (byte) 0xc1, (byte) 0xbb, (byte) 0xa1, (byte) 0x63,
            (byte) 0xb3, (byte) 0xe2, (byte) 0xac, (byte) 0x76,
            (byte) 0x66, (byte) 0x99, (byte) 0x42, (byte) 0xb5,
            (byte) 0x1a, (byte) 0x2b, (byte) 0xbd, (byte) 0xb5,
            (byte) 0xd7, (byte) 0xab, (byte) 0x79, (byte) 0x14,
            (byte) 0xe4, (byte) 0xe3, (byte) 0x41, (byte) 0xb1,
            (byte) 0x4b, (byte) 0xe8, (byte) 0xb8, (byte) 0x27
    };

    // SHA-256 of official Google Retail NexusLauncherRelease.apk (CN=nexuslauncher)
    // Allows clean two-way switching between Stable PixelOS (stock Google-signed Pixel Launcher)
    // and iOS Style UI Beta (samurai-ota-release.jks-signed Liquid Glass Launcher).
    private static final byte[] STOCK_NEXUS_LAUNCHER_SHA256 = new byte[] {
            (byte) 0xa8, (byte) 0x6b, (byte) 0xdb, (byte) 0x05,
            (byte) 0x9f, (byte) 0x28, (byte) 0xf2, (byte) 0x65,
            (byte) 0x16, (byte) 0x2d, (byte) 0x64, (byte) 0xce,
            (byte) 0x6c, (byte) 0x8d, (byte) 0x97, (byte) 0x72,
            (byte) 0x90, (byte) 0x1d, (byte) 0x22, (byte) 0x7e,
            (byte) 0x74, (byte) 0x15, (byte) 0x81, (byte) 0xd1,
            (byte) 0xf0, (byte) 0x4a, (byte) 0x5e, (byte) 0xd1,
            (byte) 0x32, (byte) 0xa5, (byte) 0x74, (byte) 0xd0
    };

    private SamuraiPlatformSigBridge() {}

    private static boolean isTrustedStockSystemCert(SigningDetails details) {
        return matchesCertSha256(details, STOCK_PLATFORM_SHA256)
                || matchesCertSha256(details, STOCK_NEXUS_LAUNCHER_SHA256);
    }

    /**
     * Called from `AppIdPermissionPolicy.shouldGrantPermissionBySignature` only when standard
     * AOSP platform certificate lineage checks returned false.
     */
    public static boolean shouldTrustSystemPrivSignature(
            PackageState pkgState, SigningDetails pkgSig) {
        try {
            if (pkgState == null || pkgSig == null) {
                return false;
            }
            if (!pkgState.isSystem() || !pkgState.isPrivileged() || pkgState.isUpdatedSystemApp()) {
                return false;
            }
            String pathStr = pkgState.getPath() != null ? pkgState.getPath().getPath() : "";
            if (pathStr.startsWith("/data/")) {
                return false;
            }
            return matchesCertSha256(pkgSig, SAMURAI_OTA_SHA256);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns:
     *   -1 -> Not a Samurai system signature transition; run stock `verifySignatures` logic.
     *    0 -> Signatures are already trusted & identical (no compat rewrite needed).
     *    1 -> Trusted system signature transition (`STOCK_PLATFORM <-> SAMURAI_OTA`) on a read-only
     *         system package/sharedUser; return `true` from `verifySignatures` so PMS updates
     *         `/data/system/packages.xml` cleanly on dirty OTA boot.
     */
    public static int checkSystemSignatureTransition(
            PackageSetting pkgSetting,
            SharedUserSetting sharedUser,
            SigningDetails parsedSig) {
        try {
            if (pkgSetting == null || parsedSig == null) {
                return -1;
            }
            if (!pkgSetting.isSystem() || pkgSetting.isUpdatedSystemApp()) {
                return -1;
            }
            String pathStr = pkgSetting.getPathString();
            if (pathStr != null && pathStr.startsWith("/data/")) {
                return -1;
            }
            boolean newIsSamurai = matchesCertSha256(parsedSig, SAMURAI_OTA_SHA256);
            boolean newIsStock = isTrustedStockSystemCert(parsedSig);
            if (!newIsSamurai && !newIsStock) {
                return -1;
            }

            SigningDetails oldPkgSig = pkgSetting.getSigningDetails();
            boolean pkgDiffers = false;
            if (oldPkgSig != null && oldPkgSig.getSignatures() != null
                    && oldPkgSig.getSignatures().length > 0) {
                boolean oldIsSamurai = matchesCertSha256(oldPkgSig, SAMURAI_OTA_SHA256);
                boolean oldIsStock = isTrustedStockSystemCert(oldPkgSig);
                if (!oldIsSamurai && !oldIsStock) {
                    return -1;
                }
                pkgDiffers = (oldIsSamurai != newIsSamurai);
            }

            boolean sharedDiffers = false;
            if (sharedUser != null) {
                SigningDetails suSig = sharedUser.getSigningDetails();
                if (suSig != null && suSig.getSignatures() != null
                        && suSig.getSignatures().length > 0) {
                    boolean suIsSamurai = matchesCertSha256(suSig, SAMURAI_OTA_SHA256);
                    boolean suIsStock = isTrustedStockSystemCert(suSig);
                    if (!suIsSamurai && !suIsStock) {
                        return -1;
                    }
                    sharedDiffers = (suIsSamurai != newIsSamurai);
                }
            }

            if (pkgDiffers || sharedDiffers) {
                return 1;
            }
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static final Object TAP_LOCK = new Object();
    private static long sLastWallpaperTapMs = 0L;
    private static int sLastWallpaperTapX = 0;
    private static int sLastWallpaperTapY = 0;
    private static IBinder sLastWallpaperToken = null;
    private static Method sGoToSleepMethod = null;

    /**
     * Invoked from `com.android.server.wm.Session.sendWallpaperCommand` whenever a window
     * dispatches a wallpaper command (`"android.wallpaper.tap"`). Both retail Pixel Launcher
     * (`NexusLauncherRelease`) and iOS Style UI Beta Launcher emit `"android.wallpaper.tap"`
     * strictly when the user taps an empty area of the Home Screen workspace (never on icons,
     * widgets, folders, dock, search bar, App Drawer, Notification Shade, Control Center, or keyboard).
     */
    public static void onWallpaperCommand(
            Context context,
            IBinder windowToken,
            String action,
            int x,
            int y) {
        try {
            if (!"android.wallpaper.tap".equals(action) || context == null || windowToken == null) {
                return;
            }
            // Exclude top status bar (handled by dt2s_statusbar) & bottom gesture pill
            if (y <= 130 || y >= 2290) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            boolean isDoubleTap = false;
            synchronized (TAP_LOCK) {
                final long dt = now - sLastWallpaperTapMs;
                final int dx = Math.abs(x - sLastWallpaperTapX);
                final int dy = Math.abs(y - sLastWallpaperTapY);
                if (sLastWallpaperToken == windowToken && dt >= 45L && dt <= 380L && dx <= 108 && dy <= 108) {
                    sLastWallpaperTapMs = 0L;
                    sLastWallpaperToken = null;
                    isDoubleTap = true;
                } else {
                    sLastWallpaperTapMs = now;
                    sLastWallpaperTapX = x;
                    sLastWallpaperTapY = y;
                    sLastWallpaperToken = windowToken;
                }
            }
            if (!isDoubleTap || !isHomeDoubleTapToLockEnabled(context)) {
                return;
            }
            KeyguardManager km = context.getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                return;
            }
            PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null) {
                Method m = sGoToSleepMethod;
                if (m == null) {
                    m = PowerManager.class.getMethod("goToSleep", long.class);
                    sGoToSleepMethod = m;
                }
                m.invoke(pm, SystemClock.uptimeMillis());
                return;
            }
            File qDir = new File("/data/ota_package/hw/queue");
            if (qDir.isDirectory()) {
                File req = new File(qDir, "cmd_wms_sleep.req");
                try (FileWriter fw = new FileWriter(req)) {
                    fw.write("SLEEP=1\n");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isHomeDoubleTapToLockEnabled(Context context) {
        try {
            int sysVal = Settings.System.getInt(context.getContentResolver(), "samurai_dt2s_home", -1);
            if (sysVal == 0) {
                return false;
            }
            if (sysVal == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        File propFile = new File("/data/ota_package/hw/state.prop");
        if (propFile.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(propFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("dt2s_home=")) {
                        return !"0".equals(line.substring("dt2s_home=".length()).trim());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static boolean matchesCertSha256(SigningDetails details, byte[] expectedDigest) {
        Signature[] sigs = details.getSignatures();
        if (sigs == null || sigs.length == 0 || sigs[0] == null) {
            return false;
        }
        byte[] raw = sigs[0].toByteArray();
        if (raw == null || raw.length == 0) {
            return false;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] actual = md.digest(raw);
            return MessageDigest.isEqual(actual, expectedDigest);
        } catch (Throwable t) {
            return false;
        }
    }
}
