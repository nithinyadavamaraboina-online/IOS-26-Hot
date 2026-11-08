package android.view;

/**
 * Compile-only Android framework stub matching AOSP / Android 17 `framework.jar`.
 * Never packaged into the APK (`compileOnly` + `testCompileOnly`).
 */
public final class WindowManagerGlobal {
    private WindowManagerGlobal() {
    }

    public static IWindowManager getWindowManagerService() {
        return null;
    }
}
