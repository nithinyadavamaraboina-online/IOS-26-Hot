package com.android.launcher3;

import com.android.launcher3.model.AllAppsList;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.ModelTaskController;
import java.util.concurrent.CompletionStage;

public final class LauncherModel {
    public interface ModelUpdateTask {
        void execute(ModelTaskController taskController, BgDataModel dataModel, AllAppsList apps);
    }

    public final CompletionStage<?> reloadIfActive(String reason) {
        return null;
    }

    public final void enqueueModelUpdateTask(ModelUpdateTask task) {}
}
