package com.android.server.pm;

import android.content.pm.SigningDetails;
import com.android.server.pm.pkg.AndroidPackage;
import java.io.File;

public class PackageSetting {
    public AndroidPackage getAndroidPackage() { return null; }
    public String getPackageName() { return null; }
    public File getPath() { return null; }
    public String getPathString() { return null; }
    public SigningDetails getSigningDetails() { return null; }
    public boolean isPrivileged() { return false; }
    public boolean isSystem() { return false; }
    public boolean isSystemExt() { return false; }
    public boolean isUpdatedSystemApp() { return false; }
}
