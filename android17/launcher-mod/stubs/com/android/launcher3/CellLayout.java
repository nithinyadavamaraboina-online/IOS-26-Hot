package com.android.launcher3;
import android.content.Context;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.ViewGroup;
import com.android.launcher3.views.ActivityContext;
public class CellLayout extends ViewGroup {
    public ActivityContext mActivity;
    public ShortcutAndWidgetContainer mShortcutsAndWidgets;
    public Point mBorderSpace;
    public int mContainerType;
    public CellLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public final void resetCellSizeInternal(DeviceProfile dp) {}
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
}
