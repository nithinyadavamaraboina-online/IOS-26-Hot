package android.view;

import android.os.IInterface;
import android.os.RemoteException;
import com.android.internal.policy.IKeyguardDismissCallback;

/**
 * Compile-only Android framework stub matching AOSP / Android 17 `framework.jar`.
 * Never packaged into the APK (`compileOnly` + `testCompileOnly`).
 */
public interface IWindowManager extends IInterface {
    void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message) throws RemoteException;
}
