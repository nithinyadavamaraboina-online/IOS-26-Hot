package com.android.launcher3;

import com.android.launcher3.util.DaggerSingletonObject;

public final class InvariantDeviceProfile {
    public static final DaggerSingletonObject INSTANCE = new DaggerSingletonObject();
    public int numColumns;
    public int numRows;
}
