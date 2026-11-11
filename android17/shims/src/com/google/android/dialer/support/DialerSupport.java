package com.google.android.dialer.support;

/**
 * Shared-library stub for Google Phone (com.google.android.dialer) compatibility check.
 * Paired with /product/etc/permissions/com.google.android.dialer.support.xml declaring:
 *   <library name="com.google.android.dialer.support"
 *            file="/product/framework/com.google.android.dialer.support.jar" />
 *   <feature name="com.google.android.apps.dialer.SUPPORTED" />
 *
 * GoogleDeviceCompatibilityChecker in GoogleDialer.apk requires BOTH the shared
 * library in PackageManager.getSystemSharedLibraryNames() AND the system feature
 * com.google.android.apps.dialer.SUPPORTED to suppress the "This device isn't
 * supported and phone calls may not work correctly" banner on every restart.
 */
public final class DialerSupport {
    private DialerSupport() {}
}
