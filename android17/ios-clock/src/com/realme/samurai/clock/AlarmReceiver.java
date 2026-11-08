package com.realme.samurai.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;

import java.util.List;

/**
 * BroadcastReceiver (`AlarmReceiver`) handling Exact Alarms, Timer Completion, Snooze, Dismiss, and
 * Boot Restoration.
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALARM_TRIGGER = "com.realme.samurai.clock.ACTION_ALARM_TRIGGER";
    public static final String ACTION_TIMER_EXPIRED = "com.realme.samurai.clock.ACTION_TIMER_EXPIRED";
    public static final String ACTION_ALARM_SNOOZE = "com.realme.samurai.clock.ACTION_ALARM_SNOOZE";
    public static final String ACTION_ALARM_DISMISS = "com.realme.samurai.clock.ACTION_ALARM_DISMISS";

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_SNOOZE_ALLOWED = "snooze_allowed";
    public static final String EXTRA_IS_SNOOZE = "is_snooze";
    public static final String EXTRA_IS_TIMER = "is_timer";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.rescheduleAllEnabled(context);
            ClockDataStore.TimerState ts = ClockDataStore.loadTimerState(context);
            if (ts.running && ts.endEpochMs > System.currentTimeMillis()) {
                AlarmScheduler.scheduleTimerCompletion(context, ts.endEpochMs, ts.label);
                SamuraiDynamicIslandBus.publishTimer(
                        context, true, false, ts.label, ts.endEpochMs, context.getPackageName());
            }
            return;
        }

        if (ACTION_ALARM_TRIGGER.equals(action)) {
            int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);
            String label = intent.getStringExtra(EXTRA_LABEL);
            boolean snoozeAllowed = intent.getBooleanExtra(EXTRA_SNOOZE_ALLOWED, true);
            boolean isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false);

            if (!isSnooze && alarmId > 0) {
                List<ClockDataStore.AlarmItem> alarms = ClockDataStore.loadAlarms(context);
                for (ClockDataStore.AlarmItem item : alarms) {
                    if (item.id == alarmId) {
                        if (item.daysMask == 0) {
                            item.enabled = false;
                            ClockDataStore.saveAlarms(context, alarms);
                        } else {
                            AlarmScheduler.scheduleAlarm(context, item);
                        }
                        break;
                    }
                }
            }
            startRinging(context, alarmId, label != null ? label : "Alarm", snoozeAllowed, false);
            return;
        }

        if (ACTION_TIMER_EXPIRED.equals(action)) {
            String label = intent.getStringExtra(EXTRA_LABEL);
            ClockDataStore.TimerState ts = ClockDataStore.loadTimerState(context);
            ts.running = false;
            ts.pausedRemainingMs = 0L;
            ClockDataStore.saveTimerState(context, ts);

            if (ClockDataStore.isStopwatchActive()) {
                SamuraiDynamicIslandBus.publishTimer(
                        context,
                        true,
                        true,
                        "Stopwatch",
                        ClockDataStore.getStopwatchVirtualStartEpochMs(),
                        context.getPackageName());
            } else {
                SamuraiDynamicIslandBus.clear(context, SamuraiDynamicIslandBus.KEY_DI_TIMER);
            }
            startRinging(
                    context,
                    0,
                    (label != null && !label.isEmpty()) ? label : "Timer Done",
                    false,
                    true);
            return;
        }

        if (ACTION_ALARM_SNOOZE.equals(action)) {
            int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, 1001);
            String label = intent.getStringExtra(EXTRA_LABEL);
            AlarmScheduler.scheduleSnooze9Min(context, alarmId, label);
            AlarmRingService.stopRinging(context);
            SamuraiDynamicIslandBus.publishToast(
                    context,
                    SamuraiDynamicIslandBus.TYPE_TIMER,
                    "Snoozing 9 min",
                    label != null ? label : "Alarm",
                    0xFFFF9F0A,
                    2600L);
            return;
        }

        if (ACTION_ALARM_DISMISS.equals(action)) {
            AlarmRingService.stopRinging(context);
        }
    }

    private static void startRinging(
            Context ctx, int alarmId, String label, boolean snoozeAllowed, boolean isTimer) {
        Intent svc = new Intent(ctx, AlarmRingService.class);
        svc.putExtra(EXTRA_ALARM_ID, alarmId);
        svc.putExtra(EXTRA_LABEL, label);
        svc.putExtra(EXTRA_SNOOZE_ALLOWED, snoozeAllowed);
        svc.putExtra(EXTRA_IS_TIMER, isTimer);
        try {
            ctx.startForegroundService(svc);
        } catch (Throwable ignored) {
        }

        Intent ui = new Intent(ctx, AlarmRingActivity.class);
        ui.putExtra(EXTRA_ALARM_ID, alarmId);
        ui.putExtra(EXTRA_LABEL, label);
        ui.putExtra(EXTRA_SNOOZE_ALLOWED, snoozeAllowed);
        ui.putExtra(EXTRA_IS_TIMER, isTimer);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            ctx.startActivity(ui);
        } catch (Throwable ignored) {
        }
    }
}
