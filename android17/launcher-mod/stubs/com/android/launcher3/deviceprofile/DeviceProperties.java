package com.android.launcher3.deviceprofile;

import android.graphics.Rect;

public final class DeviceProperties {
    public int widthPx;
    public int heightPx;
    public int availableWidthPx;
    public int availableHeightPx;
    public Rect insets;
    public boolean isLandscape;
    public boolean isTwoPanels;

    public final boolean isVerticalBarLayout() {
        return false;
    }
}
