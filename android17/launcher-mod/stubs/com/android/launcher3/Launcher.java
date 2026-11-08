package com.android.launcher3;
import android.app.Activity;
import com.android.launcher3.views.ActivityContext;
public class Launcher extends Activity implements ActivityContext {
    public Workspace mWorkspace;
    @Override
    public DeviceProfile getDeviceProfile() {
        return null;
    }
    public final void toggleAllApps(boolean animate, boolean focusSearch) {}
}
