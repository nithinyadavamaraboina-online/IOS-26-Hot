package com.android.launcher3.views;
import android.content.Context;
import com.android.launcher3.DeviceProfile;
public interface ActivityContext {
    DeviceProfile getDeviceProfile();
    static Context lookupContextNoThrow(Context context) {
        return null;
    }
}
