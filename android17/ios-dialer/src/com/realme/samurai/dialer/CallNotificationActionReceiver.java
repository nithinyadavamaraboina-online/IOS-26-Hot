package com.realme.samurai.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles ongoing/incoming call notification actions (`Answer`, `Decline`, `Hangup`, `Mute`, `Speaker`).
 */
public class CallNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER = "com.realme.samurai.dialer.ACTION_ANSWER";
    public static final String ACTION_DECLINE = "com.realme.samurai.dialer.ACTION_DECLINE";
    public static final String ACTION_HANGUP = "com.realme.samurai.dialer.ACTION_HANGUP";
    public static final String ACTION_MUTE_TOGGLE = "com.realme.samurai.dialer.ACTION_MUTE_TOGGLE";
    public static final String ACTION_SPEAKER_TOGGLE = "com.realme.samurai.dialer.ACTION_SPEAKER_TOGGLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        switch (action) {
            case ACTION_ANSWER:
                CallManager.get().answerRinging();
                break;
            case ACTION_DECLINE:
            case ACTION_HANGUP:
                CallManager.get().rejectOrHangup();
                break;
            case ACTION_MUTE_TOGGLE:
                CallManager.get().toggleMute();
                break;
            case ACTION_SPEAKER_TOGGLE:
                CallManager.get().toggleSpeakerSimple();
                break;
            default:
                break;
        }
    }
}
