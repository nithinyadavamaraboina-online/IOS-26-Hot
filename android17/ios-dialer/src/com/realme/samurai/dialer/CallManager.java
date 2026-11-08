package com.realme.samurai.dialer;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central Telecom Call & Audio Endpoint state manager + Proximity Screen-Off WakeLock controller.
 */
public final class CallManager {

    public static final int ROUTE_EARPIECE = 1;
    public static final int ROUTE_WIRED_HEADSET = 2;
    public static final int ROUTE_BLUETOOTH = 3;
    public static final int ROUTE_SPEAKER = 4;

    public interface Listener {
        void onCallsUpdated();
    }

    public static final class CallerProfile {
        public final String displayName;
        public final String formattedNumber;
        public final String typeLabel;
        public final String simBadge;
        public final Bitmap photoBitmap;

        CallerProfile(
                String displayName,
                String formattedNumber,
                String typeLabel,
                String simBadge,
                Bitmap photoBitmap) {
            this.displayName = displayName;
            this.formattedNumber = formattedNumber;
            this.typeLabel = typeLabel;
            this.simBadge = simBadge;
            this.photoBitmap = photoBitmap;
        }
    }

    private static final CallManager INSTANCE = new CallManager();

    public static CallManager get() {
        return INSTANCE;
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mLookupExec = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Call> mCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Map<String, CallerProfile> mProfileCache =
            Collections.synchronizedMap(new HashMap<>());

    private volatile IosInCallService mService;
    private volatile Context mAppCtx;
    private volatile boolean mMuted = false;
    private volatile int mAudioRoute = ROUTE_EARPIECE;
    private volatile String mActiveEndpointName = "Earpiece";
    private volatile List<CallEndpoint> mEndpoints = Collections.emptyList();
    private volatile boolean mInCallKeypadVisible = false;
    private PowerManager.WakeLock mProximityLock;

    private final Call.Callback mCallCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            onCallChangedInternal(call);
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            onCallChangedInternal(call);
        }

        @Override
        public void onConferenceableCallsChanged(Call call, List<Call> conferenceableCalls) {
            onCallChangedInternal(call);
        }
    };

    private CallManager() {}

    public void attachService(IosInCallService service) {
        mService = service;
        if (service != null) {
            mAppCtx = service.getApplicationContext();
        }
        updateProximityWakeLock();
        notifyListeners();
    }

    public void detachService(IosInCallService service) {
        if (mService == service) {
            mService = null;
        }
        for (Call c : mCalls) {
            try {
                c.unregisterCallback(mCallCallback);
            } catch (Throwable ignored) {
            }
        }
        mCalls.clear();
        mInCallKeypadVisible = false;
        if (mAppCtx != null) {
            try {
                SamuraiDynamicIslandBus.clear(mAppCtx, SamuraiDynamicIslandBus.KEY_DI_CALL);
            } catch (Throwable ignored) {
            }
        }
        releaseProximityWakeLock();
        notifyListeners();
    }

    public void addListener(Listener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    public void onCallAdded(Call call) {
        if (call == null) return;
        if (!mCalls.contains(call)) {
            mCalls.add(call);
            call.registerCallback(mCallCallback, mMainHandler);
        }
        resolveCallerProfileAsync(call);
        updateProximityWakeLock();
        notifyServiceAndListeners();
    }

    public void onCallRemoved(Call call) {
        if (call != null) {
            call.unregisterCallback(mCallCallback);
            mCalls.remove(call);
        }
        if (mCalls.isEmpty()) {
            mInCallKeypadVisible = false;
        }
        updateProximityWakeLock();
        notifyServiceAndListeners();
    }

    private void onCallChangedInternal(Call call) {
        resolveCallerProfileAsync(call);
        updateProximityWakeLock();
        notifyServiceAndListeners();
    }

    public void onEndpointChanged(CallEndpoint endpoint) {
        if (endpoint == null) return;
        int type = endpoint.getEndpointType();
        if (type == CallEndpoint.TYPE_SPEAKER) {
            mAudioRoute = ROUTE_SPEAKER;
        } else if (type == CallEndpoint.TYPE_BLUETOOTH) {
            mAudioRoute = ROUTE_BLUETOOTH;
        } else if (type == CallEndpoint.TYPE_WIRED_HEADSET) {
            mAudioRoute = ROUTE_WIRED_HEADSET;
        } else {
            mAudioRoute = ROUTE_EARPIECE;
        }
        CharSequence nm = endpoint.getEndpointName();
        mActiveEndpointName = (nm != null && nm.length() > 0) ? nm.toString() : routeLabel(mAudioRoute);
        updateProximityWakeLock();
        notifyServiceAndListeners();
    }

    public void onAvailableEndpointsChanged(List<CallEndpoint> endpoints) {
        mEndpoints = (endpoints != null) ? new ArrayList<>(endpoints) : Collections.emptyList();
        notifyListeners();
    }

    public void onMuteChanged(boolean isMuted) {
        mMuted = isMuted;
        notifyServiceAndListeners();
    }

    public void onLegacyAudioState(CallAudioState state) {
        if (state == null) return;
        mMuted = state.isMuted();
        int r = state.getRoute();
        if (r == CallAudioState.ROUTE_SPEAKER) {
            mAudioRoute = ROUTE_SPEAKER;
        } else if (r == CallAudioState.ROUTE_BLUETOOTH) {
            mAudioRoute = ROUTE_BLUETOOTH;
        } else if (r == CallAudioState.ROUTE_WIRED_HEADSET) {
            mAudioRoute = ROUTE_WIRED_HEADSET;
        } else {
            mAudioRoute = ROUTE_EARPIECE;
        }
        mActiveEndpointName = routeLabel(mAudioRoute);
        updateProximityWakeLock();
        notifyServiceAndListeners();
    }

    public List<Call> getCalls() {
        return new ArrayList<>(mCalls);
    }

    public static int callState(Call call) {
        if (call == null) return Call.STATE_DISCONNECTED;
        Call.Details d = call.getDetails();
        return d != null ? d.getState() : Call.STATE_DISCONNECTED;
    }

    public Call getPrimaryCall() {
        Call ringing = null;
        Call dialing = null;
        Call active = null;
        Call holding = null;
        Call disconnecting = null;
        for (Call c : mCalls) {
            if (c.getParent() != null) continue;
            int st = callState(c);
            if (st == Call.STATE_RINGING || st == Call.STATE_SIMULATED_RINGING) {
                if (ringing == null) ringing = c;
            } else if (st == Call.STATE_DIALING || st == Call.STATE_CONNECTING || st == Call.STATE_SELECT_PHONE_ACCOUNT) {
                if (dialing == null) dialing = c;
            } else if (st == Call.STATE_ACTIVE) {
                if (active == null) active = c;
            } else if (st == Call.STATE_HOLDING) {
                if (holding == null) holding = c;
            } else if (st == Call.STATE_DISCONNECTING || st == Call.STATE_DISCONNECTED) {
                if (disconnecting == null) disconnecting = c;
            }
        }
        if (ringing != null) return ringing;
        if (dialing != null) return dialing;
        if (active != null) return active;
        if (holding != null) return holding;
        return disconnecting;
    }

    public Call getSecondaryCall(Call primary) {
        for (Call c : mCalls) {
            if (c != primary && c.getParent() == null) {
                int st = callState(c);
                if (st != Call.STATE_DISCONNECTED && st != Call.STATE_DISCONNECTING) {
                    return c;
                }
            }
        }
        return null;
    }

    public boolean isMuted() {
        return mMuted;
    }

    public int getAudioRoute() {
        return mAudioRoute;
    }

    public String getActiveEndpointName() {
        return mActiveEndpointName;
    }

    public List<CallEndpoint> getAvailableEndpoints() {
        return mEndpoints;
    }

    public boolean hasBluetoothOrHeadsetEndpoint() {
        for (CallEndpoint ep : mEndpoints) {
            int t = ep.getEndpointType();
            if (t == CallEndpoint.TYPE_BLUETOOTH || t == CallEndpoint.TYPE_WIRED_HEADSET) {
                return true;
            }
        }
        return mAudioRoute == ROUTE_BLUETOOTH || mAudioRoute == ROUTE_WIRED_HEADSET;
    }

    public void setInCallKeypadVisible(boolean visible) {
        if (mInCallKeypadVisible != visible) {
            mInCallKeypadVisible = visible;
            updateProximityWakeLock();
            notifyListeners();
        }
    }

    public boolean isInCallKeypadVisible() {
        return mInCallKeypadVisible;
    }

    // Call Actions
    public void answerRinging() {
        Call p = getPrimaryCall();
        if (p != null && callState(p) == Call.STATE_RINGING) {
            p.answer(VideoProfile.STATE_AUDIO_ONLY);
        }
    }

    public void rejectOrHangup() {
        Call p = getPrimaryCall();
        if (p == null) return;
        int st = callState(p);
        if (st == Call.STATE_RINGING) {
            p.reject(Call.REJECT_REASON_DECLINED);
        } else {
            p.disconnect();
        }
    }

    public void rejectWithSms(String message) {
        Call p = getPrimaryCall();
        if (p == null) return;
        if (callState(p) == Call.STATE_RINGING) {
            if (message != null && !message.isEmpty()) {
                p.reject(true, message);
            } else {
                p.reject(Call.REJECT_REASON_DECLINED);
            }
        }
    }

    public void toggleMute() {
        IosInCallService svc = mService;
        if (svc == null) return;
        boolean next = !mMuted;
        svc.setMuted(next);
        mMuted = next;
        notifyServiceAndListeners();
    }

    public void toggleHold() {
        Call p = getPrimaryCall();
        if (p == null) return;
        int st = callState(p);
        if (st == Call.STATE_HOLDING) {
            p.unhold();
        } else if (st == Call.STATE_ACTIVE) {
            p.hold();
        }
    }

    public void swapOrMergeCalls() {
        Call p = getPrimaryCall();
        Call sec = getSecondaryCall(p);
        if (p == null || sec == null) return;
        List<Call> conf = p.getConferenceableCalls();
        if (conf != null && conf.contains(sec)) {
            p.conference(sec);
            return;
        }
        if (callState(p) == Call.STATE_ACTIVE && callState(sec) == Call.STATE_HOLDING) {
            sec.unhold();
        } else if (callState(p) == Call.STATE_HOLDING) {
            p.unhold();
        }
    }

    public void toggleSpeakerSimple() {
        int targetType = (mAudioRoute == ROUTE_SPEAKER)
                ? CallEndpoint.TYPE_EARPIECE
                : CallEndpoint.TYPE_SPEAKER;
        selectEndpointByType(targetType);
    }

    public void selectEndpointByType(int endpointType) {
        IosInCallService svc = mService;
        if (svc == null) return;
        if (Build.VERSION.SDK_INT >= 34 && !mEndpoints.isEmpty()) {
            for (CallEndpoint ep : mEndpoints) {
                if (ep.getEndpointType() == endpointType) {
                    requestEndpointApi34(svc, ep);
                    return;
                }
            }
            if (endpointType == CallEndpoint.TYPE_EARPIECE) {
                for (CallEndpoint ep : mEndpoints) {
                    if (ep.getEndpointType() == CallEndpoint.TYPE_WIRED_HEADSET) {
                        requestEndpointApi34(svc, ep);
                        return;
                    }
                }
            }
        }
        int legacyRoute = (endpointType == CallEndpoint.TYPE_SPEAKER)
                ? CallAudioState.ROUTE_SPEAKER
                : (endpointType == CallEndpoint.TYPE_BLUETOOTH
                        ? CallAudioState.ROUTE_BLUETOOTH
                        : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
        setLegacyRouteFallback(svc, legacyRoute);
    }

    public void selectSpecificEndpoint(CallEndpoint ep) {
        IosInCallService svc = mService;
        if (svc == null || ep == null) return;
        if (Build.VERSION.SDK_INT >= 34) {
            requestEndpointApi34(svc, ep);
        }
    }

    private void requestEndpointApi34(IosInCallService svc, CallEndpoint ep) {
        try {
            svc.requestCallEndpointChange(ep, mMainHandler::post, result -> {});
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyRouteFallback(IosInCallService svc, int route) {
        try {
            svc.setAudioRoute(route);
        } catch (Throwable ignored) {
        }
    }

    public void playDtmf(final char digit) {
        final Call p = getPrimaryCall();
        if (p == null) return;
        try {
            p.playDtmfTone(digit);
            mMainHandler.postDelayed(() -> {
                try {
                    p.stopDtmfTone();
                } catch (Throwable ignored) {
                }
            }, 160L);
        } catch (Throwable ignored) {
        }
    }

    // Proximity Screen-Off WakeLock Management
    private synchronized void updateProximityWakeLock() {
        Context ctx = mAppCtx;
        if (ctx == null) return;
        Call p = getPrimaryCall();
        int st = callState(p);
        boolean activeVoice = (st == Call.STATE_DIALING
                || st == Call.STATE_CONNECTING
                || st == Call.STATE_ACTIVE);
        boolean shouldHold = activeVoice
                && mAudioRoute == ROUTE_EARPIECE
                && !mInCallKeypadVisible;

        try {
            if (shouldHold) {
                if (mProximityLock == null) {
                    PowerManager pm = ctx.getSystemService(PowerManager.class);
                    if (pm != null && pm.isWakeLockLevelSupported(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                        mProximityLock = pm.newWakeLock(
                                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                                "com.realme.samurai.dialer:ProximityCallLock");
                        mProximityLock.setReferenceCounted(false);
                    }
                }
                if (mProximityLock != null && !mProximityLock.isHeld()) {
                    mProximityLock.acquire(4 * 3600 * 1000L);
                }
            } else {
                releaseProximityWakeLock();
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void releaseProximityWakeLock() {
        try {
            if (mProximityLock != null && mProximityLock.isHeld()) {
                mProximityLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            }
        } catch (Throwable ignored) {
        }
    }

    // Caller Profile Resolution
    public static String rawNumberOf(Call call) {
        if (call == null || call.getDetails() == null) return "";
        Uri handle = call.getDetails().getHandle();
        return (handle != null && handle.getSchemeSpecificPart() != null)
                ? handle.getSchemeSpecificPart() : "";
    }

    private static String cleanLabel(String s) {
        if (s == null) return "";
        String t = s.trim();
        return (t.isEmpty() || "null".equalsIgnoreCase(t)) ? "" : t;
    }

    public CallerProfile getCallerProfile(Call call) {
        if (call == null) {
            return new CallerProfile("Unknown Caller", "", "mobile", "", null);
        }
        String raw = rawNumberOf(call);
        CallerProfile cached = mProfileCache.get(raw);
        if (cached != null) {
            return cached;
        }
        Call.Details d = call.getDetails();
        String dispName = d != null ? cleanLabel(d.getContactDisplayName()) : "";
        String callerName = d != null ? cleanLabel(d.getCallerDisplayName()) : "";
        String cName = !dispName.isEmpty() ? dispName : callerName;
        String fmt = TelecomHelper.formatDisplayNumber(raw);
        if (d != null && d.hasProperty(Call.Details.PROPERTY_CONFERENCE)) {
            cName = "Conference Call";
        }
        String title = !cName.isEmpty() ? cName : (!fmt.isEmpty() ? fmt : "Unknown Caller");
        return new CallerProfile(title, fmt, "mobile", resolveSimBadge(d), null);
    }

    private void resolveCallerProfileAsync(final Call call) {
        final Context ctx = mAppCtx;
        if (ctx == null || call == null) return;
        final String raw = rawNumberOf(call);
        if (raw.isEmpty() || mProfileCache.containsKey(raw)) return;

        final Call.Details details = call.getDetails();
        final String simBadge = resolveSimBadge(details);

        mLookupExec.execute(() -> {
            String name = "";
            String typeLabel = "mobile";
            Bitmap photoBmp = null;

            if (TelecomHelper.hasPermission(ctx, Manifest.permission.READ_CONTACTS)) {
                Uri uri = Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(raw));
                String[] proj = {
                        ContactsContract.PhoneLookup.CONTACT_ID,
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.TYPE,
                        ContactsContract.PhoneLookup.LABEL
                };
                try (Cursor c = ctx.getContentResolver().query(uri, proj, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        long cid = c.getLong(0);
                        name = c.getString(1);
                        int type = c.getInt(2);
                        String custom = c.getString(3);
                        typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                ctx.getResources(), type, custom).toString().toLowerCase();
                        Uri contactUri = ContentUris.withAppendedId(
                                ContactsContract.Contacts.CONTENT_URI, cid);
                        try (InputStream is = ContactsContract.Contacts.openContactPhotoInputStream(
                                ctx.getContentResolver(), contactUri, true)) {
                            if (is != null) {
                                photoBmp = BitmapFactory.decodeStream(is);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            String fmt = TelecomHelper.formatDisplayNumber(raw);
            String title = (name != null && !name.trim().isEmpty())
                    ? name.trim()
                    : (!fmt.isEmpty() ? fmt : "Unknown Caller");
            mProfileCache.put(raw, new CallerProfile(title, fmt, typeLabel, simBadge, photoBmp));
            mMainHandler.post(this::notifyServiceAndListeners);
        });
    }

    private String resolveSimBadge(Call.Details details) {
        Context ctx = mAppCtx;
        if (ctx == null || details == null) return "";
        PhoneAccountHandle handle = details.getAccountHandle();
        if (handle == null) return "";
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            if (tm != null) {
                List<PhoneAccountHandle> all = tm.getCallCapablePhoneAccounts();
                if (all != null && all.size() >= 2) {
                    for (int i = 0; i < all.size(); i++) {
                        if (handle.equals(all.get(i))) {
                            PhoneAccount pa = tm.getPhoneAccount(handle);
                            String lbl = (pa != null && pa.getLabel() != null)
                                    ? pa.getLabel().toString() : ("SIM " + (i + 1));
                            return "S" + (i + 1) + " · " + lbl;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String routeLabel(int route) {
        switch (route) {
            case ROUTE_SPEAKER:
                return "Speaker";
            case ROUTE_BLUETOOTH:
                return "Bluetooth";
            case ROUTE_WIRED_HEADSET:
                return "Headphones";
            default:
                return "Audio";
        }
    }

    private void notifyServiceAndListeners() {
        IosInCallService svc = mService;
        if (svc != null) {
            svc.refreshCallNotification();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        mMainHandler.post(() -> {
            for (Listener l : mListeners) {
                l.onCallsUpdated();
            }
        });
    }
}
