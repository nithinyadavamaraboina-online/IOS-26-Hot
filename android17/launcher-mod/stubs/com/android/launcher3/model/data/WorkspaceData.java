package com.android.launcher3.model.data;

import android.util.SparseArray;

public interface WorkspaceData {
    final class MutableWorkspaceData {
        public SparseArray<ItemInfo> itemsIdMap;
    }
}
