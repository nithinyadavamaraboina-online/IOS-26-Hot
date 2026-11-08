package com.android.quickstep.views;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.quickstep.task.thumbnail.TaskContentView;

public class TaskView extends FrameLayout {
    public float fullscreenProgress;
    public float modalness;
    public boolean isBeingDismissed;
    public boolean isBeingDraggedForDismissal;
    public Rect thumbnailBounds;

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public final RecentsView getRecentsView() {
        return null;
    }

    public final boolean isRunningTask() {
        return false;
    }

    public final TaskContentView[] getTaskContentViews() {
        return new TaskContentView[0];
    }

    public final void getThumbnailBounds(Rect bounds) {}

    public void updateFullscreenParams() {}
}
