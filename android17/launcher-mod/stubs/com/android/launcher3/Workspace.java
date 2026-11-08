package com.android.launcher3;

import android.content.Context;

public class Workspace extends PagedView {
    public Launcher mLauncher;

    public Workspace(Context context) {
        super(context);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
}
