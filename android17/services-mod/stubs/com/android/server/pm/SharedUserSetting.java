package com.android.server.pm;

import android.content.pm.SigningDetails;

public final class SharedUserSetting {
    public String name;
    public SigningDetails getSigningDetails() { return null; }
    public int getFlags() { return 0; }
    public boolean isPrivileged() { return false; }
}
