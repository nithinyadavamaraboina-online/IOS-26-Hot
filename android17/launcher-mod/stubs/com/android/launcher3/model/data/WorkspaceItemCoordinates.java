package com.android.launcher3.model.data;

public final class WorkspaceItemCoordinates {
    public final int screenId;
    public final int cellX;
    public final int cellY;
    public final int container;

    public WorkspaceItemCoordinates(int screenId, int cellX, int cellY) {
        this.screenId = screenId;
        this.cellX = cellX;
        this.cellY = cellY;
        this.container = -100;
    }

    public final void applyTo(ItemInfo itemInfo) {}
}
