package com.android.internal.policy;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Compile-only Android framework stub matching AOSP / Android 17 `framework.jar`.
 * Never packaged into the APK (`compileOnly` + `testCompileOnly`).
 */
public interface IKeyguardDismissCallback extends IInterface {
    void onDismissError() throws RemoteException;

    void onDismissSucceeded() throws RemoteException;

    void onDismissCancelled() throws RemoteException;

    abstract class Stub extends Binder implements IKeyguardDismissCallback {
        public static final String DESCRIPTOR = "com.android.internal.policy.IKeyguardDismissCallback";

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IKeyguardDismissCallback asInterface(IBinder obj) {
            throw new UnsupportedOperationException("Stub!");
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return super.onTransact(code, data, reply, flags);
        }
    }
}
