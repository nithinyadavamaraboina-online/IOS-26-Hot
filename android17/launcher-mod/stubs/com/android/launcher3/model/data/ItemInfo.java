package com.android.launcher3.model.data;

import android.content.ComponentName;
import android.os.UserHandle;

public class ItemInfo {
    public int cellX;
    public int cellY;
    public int container;
    public int id = -1;
    public int itemType;
    public int screenId;
    public int spanX = 1;
    public int spanY = 1;
    public CharSequence title;
    public UserHandle user;

    public ComponentName getTargetComponent() {
        return null;
    }

    public String getTargetPackage() {
        return null;
    }
}
