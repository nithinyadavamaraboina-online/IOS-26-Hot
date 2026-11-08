package com.android.launcher3;

import com.android.launcher3.deviceprofile.DeviceProperties;
import com.android.launcher3.deviceprofile.HotseatProfile;
import com.android.launcher3.deviceprofile.WorkspaceProfile;
import com.android.launcher3.display.LauncherDisplayInfo;

public final class DeviceProfile {
    public InvariantDeviceProfile inv;
    public LauncherDisplayInfo displayInfo;
    public DeviceProperties deviceProperties;
    public WorkspaceProfile workspaceProfile;
    public HotseatProfile hotseatProfile;
    public boolean isScalableGrid;
}
