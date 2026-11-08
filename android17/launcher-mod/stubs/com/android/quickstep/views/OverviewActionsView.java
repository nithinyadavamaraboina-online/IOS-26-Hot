package com.android.quickstep.views;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public abstract class OverviewActionsView extends FrameLayout {
    public LinearLayout mActionButtons;

    public OverviewActionsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
