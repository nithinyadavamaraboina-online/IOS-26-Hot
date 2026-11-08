package com.realme.samurai.clock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

/**
 * Exact `AlarmManager.setAlarmClock(...)` Scheduler (`AlarmScheduler`) for Alarms, 9-minute Snooze,
 * and Background Countdown Timers.
 */
public final class AlarmScheduler {

    private static final int TIMER_REQ_CODE = 990_001;
    private static final int SNOOZE_REQ_OFFSET = 500_000;

    private AlarmScheduler() {}

    public static long computeNextTriggerEpochMs(ClockDataStore.AlarmItem alarm, long afterMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(afterMs);

        Calendar candidate = (Calendar) now.clone();
        candidate.set(Calendar.HOUR_OF_DAY, alarm.hour24);
        candidate.set(Calendar.MINUTE, alarm.minute);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);

        if (alarm.daysMask == 0) {
            if (candidate.getTimeInMillis() <= afterMs) {
                candidate.add(Calendar.DAY_OF_YEAR, 1);
            }
            return candidate.getTimeInMillis();
        }

        for (int addDays = 0; addDays < 8; addDays++) {
            Calendar test = (Calendar) candidate.clone();
            test.add(Calendar.DAY_OF_YEAR, addDays);
            int calDow = test.get(Calendar.DAY_OF_WEEK); // 1=Sun .. 7=Sat
            int bitIdx = calDow - 1;
            if ((alarm.daysMask & (1 << bitIdx)) != 0 && test.getTimeInMillis() > afterMs) {
                return test.getTimeInMillis();
            }
        }
        candidate.add(Calendar.DAY_OF_YEAR, 1);
        return candidate.getTimeInMillis();
    }

    public static long scheduleAlarm(Context ctx, ClockDataStore.AlarmItem alarm) {
        cancelAlarm(ctx, alarm.id);
        if (!alarm.enabled) return 0L;

        long triggerMs = computeNextTriggerEpochMs(alarm, System.currentTimeMillis());
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return triggerMs;

        Intent fire = new Intent(ctx, AlarmReceiver.class);
        fire.setAction(AlarmReceiver.ACTION_ALARM_TRIGGER);
        fire.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id);
        fire.putExtra(AlarmReceiver.EXTRA_LABEL, alarm.label);
        fire.putExtra(AlarmReceiver.EXTRA_SNOOZE_ALLOWED, alarm.snoozeEnabled);

        PendingIntent firePi = PendingIntent.getBroadcast(
                ctx,
                alarm.id,
                fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent showUi = new Intent(ctx, IosClockActivity.class);
        showUi.putExtra(IosClockActivity.EXTRA_INITIAL_TAB, 1);
        PendingIntent showPi = PendingIntent.getActivity(
                ctx,
                alarm.id,
                showUi,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), firePi);
        } catch (Throwable ignored) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, firePi);
            } catch (Throwable ignored2) {
            }
        }
        return triggerMs;
    }

    public static void scheduleSnooze9Min(Context ctx, int alarmId, String label) {
        long triggerMs = System.currentTimeMillis() + 9L * 60_000L;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        Intent fire = new Intent(ctx, AlarmReceiver.class);
        fire.setAction(AlarmReceiver.ACTION_ALARM_TRIGGER);
        fire.putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        fire.putExtra(AlarmReceiver.EXTRA_LABEL, label != null ? label : "Snoozed Alarm");
        fire.putExtra(AlarmReceiver.EXTRA_SNOOZE_ALLOWED, true);
        fire.putExtra(AlarmReceiver.EXTRA_IS_SNOOZE, true);

        PendingIntent firePi = PendingIntent.getBroadcast(
                ctx,
                SNOOZE_REQ_OFFSET + Math.max(1, alarmId),
                fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent showUi = new Intent(ctx, IosClockActivity.class);
        showUi.putExtra(IosClockActivity.EXTRA_INITIAL_TAB, 1);
        PendingIntent showPi = PendingIntent.getActivity(
                ctx,
                SNOOZE_REQ_OFFSET + Math.max(1, alarmId),
                showUi,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerMs, showPi), firePi);
        } catch (Throwable ignored) {
        }
    }

    public static void cancelAlarm(Context ctx, int alarmId) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        for (int req : new int[] { alarmId, SNOOZE_REQ_OFFSET + Math.max(1, alarmId) }) {
            Intent fire = new Intent(ctx, AlarmReceiver.class);
            fire.setAction(AlarmReceiver.ACTION_ALARM_TRIGGER);
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx,
                    req,
                    fire,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                try {
                    am.cancel(pi);
                    pi.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void rescheduleAllEnabled(Context ctx) {
        List<ClockDataStore.AlarmItem> all = ClockDataStore.loadAlarms(ctx);
        for (ClockDataStore.AlarmItem item : all) {
            if (item.enabled) {
                scheduleAlarm(ctx, item);
            }
        }
    }

    public static void scheduleTimerCompletion(Context ctx, long endEpochMs, String label) {
        cancelTimerCompletion(ctx);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        Intent fire = new Intent(ctx, AlarmReceiver.class);
        fire.setAction(AlarmReceiver.ACTION_TIMER_EXPIRED);
        fire.putExtra(AlarmReceiver.EXTRA_LABEL, label != null ? label : "Timer");

        PendingIntent firePi = PendingIntent.getBroadcast(
                ctx,
                TIMER_REQ_CODE,
                fire,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endEpochMs, firePi);
        } catch (Throwable ignored) {
        }
    }

    public static void cancelTimerCompletion(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent fire = new Intent(ctx, AlarmReceiver.class);
        fire.setAction(AlarmReceiver.ACTION_TIMER_EXPIRED);
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx,
                TIMER_REQ_CODE,
                fire,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            try {
                am.cancel(pi);
                pi.cancel();
            } catch (Throwable ignored) {
            }
        }
    }
}
