package com.android.launcher3.model.data;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class AppInfo extends ItemInfoWithIcon implements WorkspaceItemFactory {
    public ComponentName componentName;
    public Intent intent;

    @Override
    public WorkspaceItemInfo makeWorkspaceItem(Context context) {
        return null;
    }
}
