package com.android.quickstep.task.thumbnail;

import android.content.Context;
import android.view.ViewGroup;

public final class TaskContentView extends ViewGroup {
    public TaskContentView(Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}

    public final void setCornerRadius(float cornerRadius) {}
}
