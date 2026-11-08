package com.android.quickstep.views;

import android.content.Context;
import android.util.AttributeSet;

import com.android.launcher3.PagedView;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;

public abstract class RecentsView extends PagedView {
    public float mFullscreenProgress;
    public float mTaskModalness;
    public boolean mEnableDrawingLiveTile;

    public RecentsView(Context context, AttributeSet attrs, int defStyle) {
        super(context);
    }

    public int getTaskViewCount() {
        return 0;
    }

    public TaskView getTaskViewAt(int index) {
        return null;
    }

    public boolean isRtl() {
        return true;
    }

    public boolean showAsGrid() {
        return false;
    }

    public boolean isSplitSelectionActive() {
        return false;
    }

    public OverviewActionsView getActionsView() {
        return null;
    }

    public RecentsPagedOrientationHandler getPagedOrientationHandler() {
        return null;
    }

    public void updateCurveProperties() {}
}
