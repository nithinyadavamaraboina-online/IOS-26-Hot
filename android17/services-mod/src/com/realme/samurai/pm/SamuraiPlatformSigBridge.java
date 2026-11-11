package com.realme.samurai.pm;

import android.app.ContentProviderHolder;
import android.app.KeyguardManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;

import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.pkg.PackageState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;

/**
 * Strictly-scoped Android 17 platform-signature bridge for read-only `/system` and `/system_ext`
 * system packages signed with the pinned `samurai-ota-release.jks` certificate, plus Home empty-space
 * double-tap-to-sleep, Pixel Photos sysconfig feature routing, and per-UID Settings isolation.
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

    private static final String PHOTOS_PKG = "com.google.android.apps.photos";
    private static final String ICONTENT_PROVIDER_DESCRIPTOR = "android.content.IContentProvider";

    private static final Object SETTINGS_WRAP_LOCK = new Object();
    private static IBinder sCachedRawSettingsBinder = null;
    private static SamuraiSettingsBinderWrapper sCachedWrapperBinder = null;
    private static IContentProvider sCachedWrappedProvider = null;

    private static volatile int sCachedPhotosUid = -1;

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
            if (!pkgState.isSystem() || pkgState.isUpdatedSystemApp()) {
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
     *    0 -> Trusted system/sharedUser signature bridge where the package itself did NOT change
     *         existing signatures (`!pkgDiffers && sharedDiffers`, e.g. brand-new or steady-state
     *         `vendor.qti.imsrcs` signed with `SAMURAI_OTA` joining `android.uid.phone` signed with
     *         `STOCK_PLATFORM`). Returns `false` (`compatMatch = false`) from `verifySignatures` so
     *         `commitReconciledScanResultLocked` does NOT call `KeySetManagerService.removeAppKeySetDataLPw`
     *         on a package not yet in `mSettings.mPackages`.
     *    1 -> Trusted system signature transition (`STOCK_PLATFORM <-> SAMURAI_OTA`) on an existing
     *         package in `mSettings.mPackages` (`pkgDiffers == true`); returns `true` from
     *         `verifySignatures` so `KSMS` decrements the prior signing keyset cleanly on dirty OTA.
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

            if (pkgDiffers) {
                return 1;
            }
            if (sharedDiffers) {
                return 0;
            }
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Scopes Pixel 2016 (`NEXUS_PRELOAD`) feature visibility strictly to Google Photos without
     * altering Pixel 2021-2025 feature flags for Pixel Launcher, GCam, SystemUI, or GMS, and
     * without requiring any APK bind-mounts in Zygote.
     */
    public static boolean hasSystemFeature(PackageManagerService service, String name, int version) {
        if (service == null) {
            return false;
        }
        try {
            if (name != null
                    && (name.startsWith("com.google.android.apps.photos.")
                            || name.startsWith("com.google.android.feature."))) {
                final int callingUid = Binder.getCallingUid();
                if ((callingUid % 100000) >= 10000 && isPhotosCallerUid(service.mContext, callingUid)) {
                    if ("com.google.android.apps.photos.NEXUS_PRELOAD".equals(name)
                            || "com.google.android.apps.photos.nexus_preload".equals(name)
                            || "com.google.android.feature.PIXEL_EXPERIENCE".equals(name)
                            || "com.google.android.feature.GOOGLE_BUILD".equals(name)
                            || "com.google.android.feature.GOOGLE_EXPERIENCE".equals(name)) {
                        return true;
                    }
                    if (name.startsWith("com.google.android.apps.photos.PIXEL_20")
                            || name.startsWith("com.google.android.feature.PIXEL_20")) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return service.hasSystemFeature(name, version);
    }

    private static boolean isPhotosCallerUid(Context context, int uid) {
        if (uid == sCachedPhotosUid) {
            return true;
        }
        if (context == null) {
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return false;
            }
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    if (PHOTOS_PKG.equals(pkg)) {
                        sCachedPhotosUid = uid;
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    /**
     * Called from `ContentProviderRecord.newHolder` so remote non-system `/data/app` clients
     * observe user-build defaults for developer-toggle keys while `Settings`, `adb shell`,
     * `system_server`, `GMS`, and `SamuraiParts` retain the user's real toggle values.
     */
    public static ContentProviderHolder maybeWrapSettingsHolder(
            ContentProviderHolder holder, boolean local, Context context) {
        try {
            if (local
                    || holder == null
                    || holder.info == null
                    || holder.provider == null
                    || context == null
                    || !"settings".equals(holder.info.authority)) {
                return holder;
            }
            final int callingUid = Binder.getCallingUid();
            if (!isStandardUserAppUid(context, callingUid)) {
                return holder;
            }
            IContentProvider wrapped = getOrCreateWrappedSettingsProvider(holder.provider, context);
            if (wrapped != null) {
                holder.provider = wrapped;
            }
        } catch (Throwable ignored) {
        }
        return holder;
    }

    private static IContentProvider getOrCreateWrappedSettingsProvider(
            IContentProvider rawProvider, Context context) {
        IBinder rawBinder = rawProvider.asBinder();
        if (rawBinder == null) {
            return null;
        }
        synchronized (SETTINGS_WRAP_LOCK) {
            if (sCachedRawSettingsBinder == rawBinder && sCachedWrappedProvider != null) {
                return sCachedWrappedProvider;
            }
            final SamuraiSettingsBinderWrapper wrapperBinder =
                    new SamuraiSettingsBinderWrapper(rawBinder, context.getApplicationContext() != null
                            ? context.getApplicationContext()
                            : context);
            sCachedRawSettingsBinder = rawBinder;
            sCachedWrapperBinder = wrapperBinder;
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    if ("asBinder".equals(method.getName())) {
                        return sCachedWrapperBinder;
                    }
                    return method.invoke(rawProvider, args);
                }
            };
            IContentProvider proxyProvider = (IContentProvider) Proxy.newProxyInstance(
                    IContentProvider.class.getClassLoader(),
                    new Class<?>[] { IContentProvider.class },
                    handler);
            sCachedWrappedProvider = proxyProvider;
            return proxyProvider;
        }
    }

    private static boolean isStandardUserAppUid(Context context, int uid) {
        int appId = uid % 100000;
        if (appId < 10000 || appId > 19999 || context == null) {
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return false;
            }
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) {
                return false;
            }
            PackageManager.ApplicationInfoFlags zeroFlags =
                    PackageManager.ApplicationInfoFlags.of(0L);
            for (String pkg : pkgs) {
                if (pkg == null
                        || pkg.startsWith("com.android.")
                        || pkg.startsWith("com.realme.")
                        || "com.google.android.gms".equals(pkg)) {
                    return false;
                }
                ApplicationInfo ai = pm.getApplicationInfo(pkg, zeroFlags);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isIsolatedDevSettingKey(String key) {
        return "adb_enabled".equals(key)
                || "adb_wifi_enabled".equals(key)
                || "development_settings_enabled".equals(key)
                || "mock_location".equals(key)
                || "install_non_market_apps".equals(key);
    }

    private static final class SamuraiSettingsBinderWrapper extends Binder {
        private final IBinder mBase;
        private final Context mContext;

        SamuraiSettingsBinderWrapper(IBinder base, Context context) {
            mBase = base;
            mContext = context;
            attachInterface(null, ICONTENT_PROVIDER_DESCRIPTOR);
        }

        @Override
        public String getInterfaceDescriptor() {
            try {
                String desc = mBase.getInterfaceDescriptor();
                if (desc != null) {
                    return desc;
                }
            } catch (RemoteException ignored) {
            }
            return ICONTENT_PROVIDER_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IContentProvider.CALL_TRANSACTION && data != null && reply != null) {
                final int callerUid = Binder.getCallingUid();
                if (isStandardUserAppUid(mContext, callerUid)) {
                    final int startPos = data.dataPosition();
                    try {
                        data.enforceInterface(ICONTENT_PROVIDER_DESCRIPTOR);
                        AttributionSource.CREATOR.createFromParcel(data);
                        String authority = data.readString();
                        String method = data.readString();
                        String key = data.readString();
                        if (("settings".equals(authority) || authority == null)
                                && ("GET_global".equals(method) || "GET_secure".equals(method))
                                && isIsolatedDevSettingKey(key)) {
                            reply.writeNoException();
                            Bundle out = new Bundle(1);
                            out.putString("value", "0");
                            reply.writeBundle(out);
                            return true;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        data.setDataPosition(startPos);
                    }
                }
            }
            return mBase.transact(code, data, reply, flags);
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
     * dispatches a wallpaper command (`"android.wallpaper.tap"`).
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
                if (dt >= 25L && dt <= 460L && dx <= 148 && dy <= 148) {
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
            if (!isDoubleTap) {
                return;
            }
            final long callingIdent = Binder.clearCallingIdentity();
            try {
                if (!isHomeDoubleTapToLockEnabled(context)) {
                    return;
                }
                KeyguardManager km = context.getSystemService(KeyguardManager.class);
                if (km != null && km.isKeyguardLocked()) {
                    return;
                }
                PowerManager pm = context.getSystemService(PowerManager.class);
                if (pm != null) {
                    try {
                        Method m = sGoToSleepMethod;
                        if (m == null) {
                            m = PowerManager.class.getMethod("goToSleep", long.class);
                            sGoToSleepMethod = m;
                        }
                        m.invoke(pm, SystemClock.uptimeMillis());
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                File qDir = new File("/data/ota_package/hw/queue");
                if (qDir.isDirectory()) {
                    File req = new File(qDir, "cmd_wms_sleep.req");
                    try (FileWriter fw = new FileWriter(req)) {
                        fw.write("SLEEP=1\n");
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdent);
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
