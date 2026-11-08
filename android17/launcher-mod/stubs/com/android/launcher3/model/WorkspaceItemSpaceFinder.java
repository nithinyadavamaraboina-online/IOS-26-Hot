package com.android.launcher3.model;

import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemCoordinates;
import com.android.launcher3.util.IntSet;
import java.util.ArrayList;

public final class WorkspaceItemSpaceFinder {
    public BgDataModel mDataModel;
    public InvariantDeviceProfile mIDP;
    public LauncherModel mModel;

    public final WorkspaceItemCoordinates findSpaceForItem(
            ArrayList<ItemInfo> workspaceApps,
            int spanX,
            int spanY,
            IntSet screenIdsToIgnore,
            WorkspaceItemCoordinates preferredCoordinates) {
        return null;
    }
}
