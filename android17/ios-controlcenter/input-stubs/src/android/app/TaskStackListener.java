package android.app;

import android.content.ComponentName;

/**
 * Clean-room compile-time stub for Android framework {@code android.app.TaskStackListener}.
 * Packaged as {@code compileOnly}; resolved against {@code /system/framework/framework.jar} at runtime.
 */
public class TaskStackListener {
    public TaskStackListener() {
    }

    public void onTaskStackChanged() {
    }

    public void onTaskCreated(int taskId, ComponentName componentName) {
    }

    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
    }

    public void onActivityRestartAttempt(
            ActivityManager.RunningTaskInfo task,
            boolean homeTaskVisible,
            boolean clearedTask,
            boolean wasVisible) {
    }
}
