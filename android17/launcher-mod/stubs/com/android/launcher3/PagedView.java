package com.android.launcher3;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public abstract class PagedView extends ViewGroup {
    public PagedView(Context context) {
        super(context);
    }

    public int computeMaxScroll() {
        return 0;
    }

    public int getCurrentPage() {
        return 0;
    }

    public int getPageCount() {
        return 0;
    }

    public View getPageIndicator() {
        return null;
    }

    public boolean snapToPage(int whichPage) {
        return false;
    }
}
