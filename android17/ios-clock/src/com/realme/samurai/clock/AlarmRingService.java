package com.realme.samurai.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Foreground Alarm & Timer Audio/Haptics Service (`AlarmRingService`).
 */
public class AlarmRingService extends Service {

    public static final String CHANNEL_ALARMS = "ios26_clock_alarms";
    public static final int NOTIF_ID_ALARM = 2620;
    public static final String ACTION_CLOSE_RING_UI = "com.realme.samurai.clock.CLOSE_RING_UI";

    private MediaPlayer mMediaPlayer;
    private Vibrator mVibrator;
    private PowerManager.WakeLock mWakeLock;

    public static void stopRinging(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.stopService(new Intent(ctx, AlarmRingService.class));
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIF_ID_ALARM);
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent closeUi = new Intent(ACTION_CLOSE_RING_UI).setPackage(ctx.getPackageName());
            ctx.sendBroadcast(closeUi);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        acquireWakeLock();
        ensureChannel();

        int alarmId = (intent != null) ? intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, 1001) : 1001;
        String label = (intent != null && intent.getStringExtra(AlarmReceiver.EXTRA_LABEL) != null)
                ? intent.getStringExtra(AlarmReceiver.EXTRA_LABEL)
                : "Alarm";
        boolean snoozeAllowed = intent == null
                || intent.getBooleanExtra(AlarmReceiver.EXTRA_SNOOZE_ALLOWED, true);
        boolean isTimer = intent != null
                && intent.getBooleanExtra(AlarmReceiver.EXTRA_IS_TIMER, false);

        Notification notif = buildAlarmNotification(alarmId, label, snoozeAllowed, isTimer);
        try {
            startForeground(
                    NOTIF_ID_ALARM,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } catch (Throwable ignored) {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIF_ID_ALARM, notif);
            } catch (Throwable ignored2) {
            }
        }

        startAlarmAudio();
        startAlarmVibration();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAudioAndVibration();
        releaseWakeLock();
        super.onDestroy();
    }

    private Notification buildAlarmNotification(
            int alarmId, String label, boolean snoozeAllowed, boolean isTimer) {
        Intent fullUi = new Intent(this, AlarmRingActivity.class);
        fullUi.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        fullUi.putExtra(AlarmReceiver.EXTRA_LABEL, label);
        fullUi.putExtra(AlarmReceiver.EXTRA_SNOOZE_ALLOWED, snoozeAllowed);
        fullUi.putExtra(AlarmReceiver.EXTRA_IS_TIMER, isTimer);
        fullUi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent fullPi = PendingIntent.getActivity(
                this,
                301,
                fullUi,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, AlarmReceiver.class);
        stopIntent.setAction(AlarmReceiver.ACTION_ALARM_DISMISS);
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this,
                302,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = new Notification.Builder(this, CHANNEL_ALARMS)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(isTimer ? "Timer Completed" : "Alarm")
                .setContentText(label)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setColor(0xFFFF9F0A)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true);

        if (snoozeAllowed && !isTimer) {
            Intent snoozeIntent = new Intent(this, AlarmReceiver.class);
            snoozeIntent.setAction(AlarmReceiver.ACTION_ALARM_SNOOZE);
            snoozeIntent.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
            snoozeIntent.putExtra(AlarmReceiver.EXTRA_LABEL, label);
            PendingIntent snoozePi = PendingIntent.getBroadcast(
                    this,
                    303,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_alarm),
                    "Snooze (9 min)",
                    snoozePi).build());
        }

        nb.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_alarm),
                "Stop",
                stopPi).build());

        return nb.build();
    }

    private void startAlarmAudio() {
        stopAudioOnly();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            if (uri == null) return;

            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            mMediaPlayer = mp;
        } catch (Throwable ignored) {
        }
    }

    private void startAlarmVibration() {
        try {
            VibratorManager vm = getSystemService(VibratorManager.class);
            Vibrator vib = (vm != null) ? vm.getDefaultVibrator() : getSystemService(Vibrator.class);
            if (vib != null && vib.hasVibrator()) {
                mVibrator = vib;
                long[] timings = { 0L, 480L, 380L, 480L, 620L };
                VibrationEffect wf = VibrationEffect.createWaveform(timings, 0);
                VibrationAttributes attrs = new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build();
                vib.vibrate(wf, attrs);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopAudioOnly() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                mMediaPlayer.release();
            } catch (Throwable ignored) {
            }
            mMediaPlayer = null;
        }
    }

    private void stopAudioAndVibration() {
        stopAudioOnly();
        if (mVibrator != null) {
            try {
                mVibrator.cancel();
            } catch (Throwable ignored) {
            }
            mVibrator = null;
        }
    }

    private void ensureChannel() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ALARMS) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ALARMS,
                        getString(R.string.notif_channel_alarms),
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && mWakeLock == null) {
                mWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "com.realme.samurai.clock:AlarmRingWakeLock");
                mWakeLock.setReferenceCounted(false);
                mWakeLock.acquire(10 * 60_000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
