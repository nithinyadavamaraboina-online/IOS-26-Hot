package com.android.server.pm.pkg;

import java.io.File;

public interface PackageState {
    AndroidPackage getAndroidPackage();
    String getPackageName();
    File getPath();
    boolean isPrivileged();
    boolean isSystem();
    boolean isSystemExt();
    boolean isUpdatedSystemApp();
}
