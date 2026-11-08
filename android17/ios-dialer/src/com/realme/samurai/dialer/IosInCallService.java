package com.realme.samurai.dialer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.InCallService;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;

import java.util.List;

/**
 * Android Telecom `InCallService` binding full-screen `IosCallActivity`, `Notification.CallStyle`,
 * and live `SamuraiDynamicIslandBus` updates.
 */
public class IosInCallService extends InCallService {

    public static final String CHANNEL_CALLS = "ios26_phone_calls";
    public static final int NOTIF_ID_CALL = 2601;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        CallManager.get().attachService(this);
    }

    @Override
    public void onDestroy() {
        CallManager.get().detachService(this);
        stopForegroundSafe();
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        CallManager.get().onCallAdded(call);
        refreshCallNotification();
        int st = CallManager.callState(call);
        if (st == Call.STATE_RINGING
                || st == Call.STATE_DIALING
                || st == Call.STATE_CONNECTING
                || st == Call.STATE_SELECT_PHONE_ACCOUNT) {
            launchInCallScreen();
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        CallManager.get().onCallRemoved(call);
        if (CallManager.get().getCalls().isEmpty()) {
            stopForegroundSafe();
        } else {
            refreshCallNotification();
        }
        super.onCallRemoved(call);
    }

    @Override
    public void onBringToForeground(boolean showDialpad) {
        super.onBringToForeground(showDialpad);
        if (showDialpad) {
            CallManager.get().setInCallKeypadVisible(true);
        }
        launchInCallScreen();
    }

    @Override
    public void onCallEndpointChanged(CallEndpoint callEndpoint) {
        super.onCallEndpointChanged(callEndpoint);
        CallManager.get().onEndpointChanged(callEndpoint);
    }

    @Override
    public void onAvailableCallEndpointsChanged(List<CallEndpoint> availableEndpoints) {
        super.onAvailableCallEndpointsChanged(availableEndpoints);
        CallManager.get().onAvailableEndpointsChanged(availableEndpoints);
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        super.onMuteStateChanged(isMuted);
        CallManager.get().onMuteChanged(isMuted);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        if (Build.VERSION.SDK_INT < 34) {
            CallManager.get().onLegacyAudioState(audioState);
        }
    }

    private void launchInCallScreen() {
        try {
            Intent ui = new Intent(this, IosCallActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(ui);
        } catch (Throwable ignored) {
        }
    }

    public void refreshCallNotification() {
        Call primary = CallManager.get().getPrimaryCall();
        if (primary == null) {
            stopForegroundSafe();
            return;
        }
        int st = CallManager.callState(primary);
        if (st == Call.STATE_DISCONNECTED) {
            if (CallManager.get().getCalls().size() <= 1) {
                stopForegroundSafe();
                return;
            }
        }

        ensureNotificationChannel();
        CallManager.CallerProfile profile = CallManager.get().getCallerProfile(primary);

        if (st == Call.STATE_DISCONNECTED || st == Call.STATE_DISCONNECTING) {
            SamuraiDynamicIslandBus.clear(this, SamuraiDynamicIslandBus.KEY_DI_CALL);
        } else {
            long callStartEpochMs = 0L;
            Call.Details diDetails = primary.getDetails();
            if (st == Call.STATE_ACTIVE) {
                callStartEpochMs = (diDetails != null && diDetails.getConnectTimeMillis() > 0L)
                        ? diDetails.getConnectTimeMillis()
                        : System.currentTimeMillis();
            }
            SamuraiDynamicIslandBus.publishCall(
                    this,
                    true,
                    profile.displayName,
                    statusTextForState(st, profile),
                    callStartEpochMs,
                    getPackageName());
        }

        Person.Builder personBuilder = new Person.Builder()
                .setName(profile.displayName)
                .setImportant(true);
        if (profile.photoBitmap != null && !profile.photoBitmap.isRecycled()) {
            personBuilder.setIcon(Icon.createWithBitmap(profile.photoBitmap));
        }
        Person callerPerson = personBuilder.build();

        Intent openUiIntent = new Intent(this, IosCallActivity.class);
        openUiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 100, openUiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent answerPi = actionPendingIntent(
                CallNotificationActionReceiver.ACTION_ANSWER, 101);
        PendingIntent declinePi = actionPendingIntent(
                CallNotificationActionReceiver.ACTION_DECLINE, 102);
        PendingIntent hangupPi = actionPendingIntent(
                CallNotificationActionReceiver.ACTION_HANGUP, 103);
        PendingIntent mutePi = actionPendingIntent(
                CallNotificationActionReceiver.ACTION_MUTE_TOGGLE, 104);
        PendingIntent speakerPi = actionPendingIntent(
                CallNotificationActionReceiver.ACTION_SPEAKER_TOGGLE, 105);

        Notification.CallStyle callStyle;
        boolean incoming = (st == Call.STATE_RINGING || st == Call.STATE_SIMULATED_RINGING);
        if (incoming) {
            callStyle = Notification.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi)
                    .setAnswerButtonColorHint(DialerGlassEngine.IOS_GREEN)
                    .setDeclineButtonColorHint(DialerGlassEngine.IOS_RED);
        } else {
            callStyle = Notification.CallStyle.forOngoingCall(callerPerson, hangupPi);
        }

        Notification.Builder nb = new Notification.Builder(this, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_stat_phone_call)
                .setContentTitle(profile.displayName)
                .setContentText(statusTextForState(st, profile))
                .setContentIntent(contentPi)
                .setStyle(callStyle)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(incoming ? DialerGlassEngine.IOS_GREEN : DialerGlassEngine.IOS_BLUE_LIGHT);

        if (incoming) {
            nb.setFullScreenIntent(contentPi, true);
        } else {
            Call.Details d = primary.getDetails();
            if (st == Call.STATE_ACTIVE && d != null && d.getConnectTimeMillis() > 0L) {
                nb.setUsesChronometer(true);
                nb.setWhen(d.getConnectTimeMillis());
            }
            String muteTitle = CallManager.get().isMuted() ? "Unmute" : "Mute";
            String spkTitle = (CallManager.get().getAudioRoute() == CallManager.ROUTE_SPEAKER)
                    ? "Earpiece" : "Speaker";
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_phone_call),
                    muteTitle, mutePi).build());
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_phone_call),
                    spkTitle, speakerPi).build());
        }

        Notification notification = nb.build();
        try {
            startForeground(
                    NOTIF_ID_CALL,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } catch (Throwable ignored) {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIF_ID_CALL, notification);
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    private static String statusTextForState(int state, CallManager.CallerProfile profile) {
        switch (state) {
            case Call.STATE_RINGING:
                return !profile.simBadge.isEmpty()
                        ? ("Incoming Call · " + profile.simBadge)
                        : "Incoming Call";
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
                return "Calling…";
            case Call.STATE_HOLDING:
                return "On Hold";
            default:
                return "Ongoing Call";
        }
    }

    private PendingIntent actionPendingIntent(String action, int reqCode) {
        Intent i = new Intent(this, CallNotificationActionReceiver.class);
        i.setAction(action);
        return PendingIntent.getBroadcast(
                this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureNotificationChannel() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_CALLS);
            if (existing == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_CALLS,
                        getString(R.string.notif_channel_calls),
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setSound(null, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build());
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopForegroundSafe() {
        SamuraiDynamicIslandBus.clear(this, SamuraiDynamicIslandBus.KEY_DI_CALL);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIF_ID_CALL);
            }
        } catch (Throwable ignored) {
        }
    }
}
