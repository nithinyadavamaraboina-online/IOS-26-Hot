package com.android.launcher3.model;

import android.content.Context;
import com.android.launcher3.LauncherModel;

public final class LoaderTask implements Runnable {
    public AllAppsList mBgAllAppsList;
    public BgDataModel mBgDataModel;
    public Context mContext;
    public LauncherModel mModel;
    public boolean mStopped;
    public WorkspaceItemSpaceFinder mWorkspaceItemSpaceFinder;

    @Override
    public void run() {}
}
