package com.realme.samurai.systemui;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import com.realme.samurai.glass.SamuraiDynamicIslandBus;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified reactive state controller for Dynamic Island & Control Center Media Platter.
 */
public final class DynamicIslandController {

    public static final int MODE_IDLE = 0;
    public static final int MODE_CALL = 1;
    public static final int MODE_TIMER = 2;
    public static final int MODE_MEDIA = 3;
    public static final int MODE_CHARGING = 4;
    public static final int MODE_RINGER = 5;

    public static final class IslandState {
        public int mode = MODE_IDLE;
        public boolean active = false;
        public boolean isStopwatch = false;
        public boolean mediaPlaying = false;
        public String title = "";
        public String subtitle = "";
        public String rightBadge = "";
        public int accentColor = 0xFF30D158;
        public Bitmap albumArt = null;
        public String packageName = "";
        public long targetOrStartEpochMs = 0L;
        public float progress01 = 0f;
    }

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> mListeners = new CopyOnWriteArrayList<>();
    private final IslandState mState = new IslandState();

    private ContentObserver mBusObserver;
    private MediaController mActiveMediaController;
    private Bitmap mCachedArt;
    private String mMediaTitle = "";
    private String mMediaArtist = "";
    private boolean mMediaPlaying = false;
    private boolean mExpanded = false;
    private int mLastBatteryPct = 85;
    private long mBootReceiverStampMs = 0L;
    private boolean mTelecomInCall = false;
    private boolean mTelecomRinging = false;
    private long mTelecomCallStartMs = 0L;

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refreshMediaMetadata();
            recomputeState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshMediaMetadata();
            recomputeState();
        }

        @Override
        public void onSessionDestroyed() {
            rebindMediaSessions();
        }
    };

    private final Runnable mSecondTicker = new Runnable() {
        @Override
        public void run() {
            mMainHandler.removeCallbacks(this);
            recomputeState();
            if (mState.active && (mState.mode == MODE_CALL
                    || mState.mode == MODE_TIMER
                    || mState.mode == MODE_CHARGING
                    || mState.mode == MODE_RINGER)) {
                mMainHandler.postDelayed(this, 500L);
            }
        }
    };

    public DynamicIslandController(Context context) {
        mContext = context.getApplicationContext();
        init();
    }

    private void init() {
        mBootReceiverStampMs = System.currentTimeMillis();
        mBusObserver = SamuraiDynamicIslandBus.observe(mContext, this::recomputeState);

        try {
            MediaSessionManager msm = mContext.getSystemService(MediaSessionManager.class);
            if (msm != null) {
                msm.addOnActiveSessionsChangedListener(
                        controllers -> bindControllers(controllers),
                        null,
                        mMainHandler);
                bindControllers(msm.getActiveSessions(null));
            }
        } catch (Throwable ignored) {
        }

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            f.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String act = intent.getAction();
                    if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                        int lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        if (lvl >= 0 && scale > 0) {
                            mLastBatteryPct = Math.max(0, Math.min(100, (lvl * 100) / scale));
                        }
                    } else if (Intent.ACTION_POWER_CONNECTED.equals(act)) {
                        if (isInitialStickyBroadcast()) return;
                        mMainHandler.postDelayed(() -> {
                            boolean vooc = SystemUiGlassHelper.isVoocFastChargeActive();
                            SamuraiDynamicIslandBus.publishToast(
                                    mContext,
                                    SamuraiDynamicIslandBus.TYPE_CHARGING,
                                    vooc ? "50W SuperVOOC" : "Charging",
                                    mLastBatteryPct + "% Charged",
                                    vooc ? 0xFF00E5B0 : 0xFF30D158,
                                    3800L);
                        }, 320L);
                    } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(act)) {
                        if (isInitialStickyBroadcast()
                                || (System.currentTimeMillis() - mBootReceiverStampMs) < 1500L) {
                            return;
                        }
                        int rm = intent.getIntExtra(
                                AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL);
                        String title;
                        String sub;
                        int accent;
                        if (rm == AudioManager.RINGER_MODE_SILENT) {
                            title = "Silent";
                            sub = "Silent Mode On";
                            accent = 0xFFFF453A;
                        } else if (rm == AudioManager.RINGER_MODE_VIBRATE) {
                            title = "Vibrate";
                            sub = "Haptics Active";
                            accent = 0xFFFF9F0A;
                        } else {
                            title = "Ring";
                            sub = "Sound On";
                            accent = 0xFF30D158;
                        }
                        SamuraiDynamicIslandBus.publishToast(
                                mContext,
                                SamuraiDynamicIslandBus.TYPE_RINGER,
                                title,
                                sub,
                                accent,
                                2600L);
                    } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(act)) {
                        String st = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st)) {
                            if (!mTelecomInCall) {
                                mTelecomCallStartMs = System.currentTimeMillis();
                            }
                            mTelecomInCall = true;
                            mTelecomRinging = false;
                        } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(st)) {
                            mTelecomInCall = true;
                            mTelecomRinging = true;
                            if (mTelecomCallStartMs <= 0L) {
                                mTelecomCallStartMs = System.currentTimeMillis();
                            }
                        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(st)) {
                            mTelecomInCall = false;
                            mTelecomRinging = false;
                            mTelecomCallStartMs = 0L;
                        }
                        recomputeState();
                    } else if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                        setExpanded(false);
                    }
                }
            }, f, null, mMainHandler);
        } catch (Throwable ignored) {
        }

        recomputeState();
    }

    public void addListener(Runnable listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    private void notifyListeners() {
        for (Runnable r : mListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public IslandState getState() {
        return mState;
    }

    public boolean isExpanded() {
        return mExpanded && mState.active;
    }

    public void setExpanded(boolean expanded) {
        boolean next = expanded && mState.active;
        if (mExpanded != next) {
            mExpanded = next;
            notifyListeners();
        }
    }

    public int getBatteryPct() {
        return mLastBatteryPct;
    }

    // -------------------------------------------------------------------------
    // Media Session Binding & Transport Controls
    // -------------------------------------------------------------------------

    public void rebindMediaSessions() {
        try {
            MediaSessionManager msm = mContext.getSystemService(MediaSessionManager.class);
            if (msm != null) {
                bindControllers(msm.getActiveSessions(null));
            }
        } catch (Throwable ignored) {
        }
    }

    private void bindControllers(List<MediaController> list) {
        if (mActiveMediaController != null) {
            try {
                mActiveMediaController.unregisterCallback(mMediaCallback);
            } catch (Throwable ignored) {
            }
            mActiveMediaController = null;
        }
        if (list != null && !list.isEmpty()) {
            MediaController chosen = list.get(0);
            for (MediaController mc : list) {
                PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = mc;
                    break;
                }
            }
            mActiveMediaController = chosen;
            try {
                mActiveMediaController.registerCallback(mMediaCallback, mMainHandler);
            } catch (Throwable ignored) {
            }
        }
        refreshMediaMetadata();
        recomputeState();
    }

    private void refreshMediaMetadata() {
        MediaController mc = mActiveMediaController;
        if (mc == null) {
            mMediaPlaying = false;
            mMediaTitle = "";
            mMediaArtist = "";
            mCachedArt = null;
            return;
        }
        PlaybackState ps = mc.getPlaybackState();
        mMediaPlaying = ps != null && (ps.getState() == PlaybackState.STATE_PLAYING
                || ps.getState() == PlaybackState.STATE_BUFFERING);
        MediaMetadata meta = mc.getMetadata();
        if (meta != null) {
            CharSequence t = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null || a.length() == 0) {
                a = meta.getText(MediaMetadata.METADATA_KEY_ALBUM);
            }
            mMediaTitle = (t != null) ? t.toString() : "";
            mMediaArtist = (a != null) ? a.toString() : "";
            Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) {
                bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (bmp == null) {
                bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            if (bmp != null && !bmp.isRecycled() && bmp.getWidth() > 4) {
                if (bmp.getWidth() > 144 || bmp.getHeight() > 144) {
                    mCachedArt = Bitmap.createScaledBitmap(bmp, 128, 128, true);
                } else {
                    mCachedArt = bmp;
                }
            } else {
                mCachedArt = null;
            }
        } else {
            mMediaTitle = "";
            mMediaArtist = "";
            mCachedArt = null;
        }
    }

    public String getMediaTitle() {
        return mMediaTitle.isEmpty() ? "Not Playing" : mMediaTitle;
    }

    public String getMediaArtist() {
        return mMediaArtist.isEmpty() ? "iPhone Audio" : mMediaArtist;
    }

    public boolean isMediaPlaying() {
        return mMediaPlaying;
    }

    public Bitmap getMediaAlbumArt() {
        return mCachedArt;
    }

    public void mediaPlayPause() {
        MediaController mc = mActiveMediaController;
        if (mc == null) rebindMediaSessions();
        mc = mActiveMediaController;
        if (mc != null) {
            try {
                if (mMediaPlaying) {
                    mc.getTransportControls().pause();
                } else {
                    mc.getTransportControls().play();
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void mediaPrevious() {
        MediaController mc = mActiveMediaController;
        if (mc != null) {
            try {
                mc.getTransportControls().skipToPrevious();
                return;
            } catch (Throwable ignored) {
            }
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void mediaNext() {
        MediaController mc = mActiveMediaController;
        if (mc != null) {
            try {
                mc.getTransportControls().skipToNext();
                return;
            } catch (Throwable ignored) {
            }
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private void dispatchMediaKey(int keyCode) {
        try {
            AudioManager am = mContext.getSystemService(AudioManager.class);
            if (am != null) {
                long now = android.os.SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Throwable ignored) {
        }
    }

    public void openActiveIslandDestination() {
        setExpanded(false);
        SamuraiSystemUiHooks.collapseControlCenter(true);
        if (mState.mode == MODE_CALL) {
            try {
                TelecomManager tm = mContext.getSystemService(TelecomManager.class);
                if (tm != null && tm.isInCall()) {
                    tm.showInCallScreen(false);
                    return;
                }
            } catch (Throwable ignored) {
            }
            SystemUiGlassHelper.launchDialer(mContext);
            return;
        }
        if (mState.mode == MODE_TIMER) {
            SystemUiGlassHelper.launchClock(mContext);
            return;
        }
        MediaController mc = mActiveMediaController;
        if (mc != null) {
            try {
                PendingIntent pi = mc.getSessionActivity();
                if (pi != null) {
                    pi.send();
                    return;
                }
            } catch (Throwable ignored) {
            }
            String pkg = mc.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                SystemUiGlassHelper.launchPackageOrFallback(mContext, pkg, null, null);
            }
        }
    }

    public void dismissCurrentActivity() {
        setExpanded(false);
        if (mState.mode == MODE_TIMER) {
            SamuraiDynamicIslandBus.clear(mContext, SamuraiDynamicIslandBus.KEY_DI_TIMER);
        } else if (mState.mode == MODE_CHARGING || mState.mode == MODE_RINGER) {
            SamuraiDynamicIslandBus.clear(mContext, SamuraiDynamicIslandBus.KEY_DI_TOAST);
        }
        recomputeState();
    }

    // -------------------------------------------------------------------------
    // State Priority Evaluation
    // -------------------------------------------------------------------------

    public void recomputeState() {
        long now = System.currentTimeMillis();
        SamuraiDynamicIslandBus.ActivityRecord toast =
                SamuraiDynamicIslandBus.read(mContext, SamuraiDynamicIslandBus.KEY_DI_TOAST);
        SamuraiDynamicIslandBus.ActivityRecord call =
                SamuraiDynamicIslandBus.read(mContext, SamuraiDynamicIslandBus.KEY_DI_CALL);
        SamuraiDynamicIslandBus.ActivityRecord timer =
                SamuraiDynamicIslandBus.read(mContext, SamuraiDynamicIslandBus.KEY_DI_TIMER);

        mState.albumArt = null;
        mState.isStopwatch = false;
        mState.mediaPlaying = mMediaPlaying;
        mState.progress01 = 0f;

        if (toast.active && now < toast.targetEpochMs) {
            mState.active = true;
            mState.mode = SamuraiDynamicIslandBus.TYPE_RINGER.equals(toast.type)
                    ? MODE_RINGER
                    : MODE_CHARGING;
            mState.title = toast.title.isEmpty() ? "Status" : toast.title;
            mState.subtitle = toast.subtitle;
            mState.rightBadge = (mState.mode == MODE_CHARGING)
                    ? (mLastBatteryPct + "%")
                    : mState.title;
            mState.accentColor = toast.accentColor;
            mState.packageName = toast.packageName;
            mState.targetOrStartEpochMs = toast.targetEpochMs;
            mState.progress01 = (mState.mode == MODE_CHARGING) ? (mLastBatteryPct / 100f) : 1f;
            scheduleTicker();
        } else if (call.active || mTelecomInCall) {
            long start = call.active
                    ? (call.targetEpochMs > 0L ? call.targetEpochMs : call.updatedEpochMs)
                    : (mTelecomCallStartMs > 0L ? mTelecomCallStartMs : now);
            long sec = Math.max(0L, (now - start) / 1000L);
            mState.active = true;
            mState.mode = MODE_CALL;
            mState.title = call.active && !call.title.isEmpty()
                    ? call.title
                    : (mTelecomRinging ? "Incoming Call" : "Ongoing Call");
            mState.subtitle = call.active && !call.subtitle.isEmpty()
                    ? call.subtitle
                    : (mTelecomRinging ? "Tap to View" : "Phone Audio");
            mState.rightBadge = mTelecomRinging ? "RING" : formatMinSec(sec);
            mState.accentColor = 0xFF30D158;
            mState.packageName = call.active ? call.packageName : "com.mayoh.contact.icontact";
            mState.targetOrStartEpochMs = start;
            scheduleTicker();
        } else if (timer.active) {
            boolean sw = SamuraiDynamicIslandBus.TYPE_STOPWATCH.equals(timer.type);
            long diffMs = sw
                    ? Math.max(0L, now - timer.targetEpochMs)
                    : Math.max(0L, timer.targetEpochMs - now);
            if (!sw && timer.targetEpochMs > 0L && now > timer.targetEpochMs + 5000L) {
                SamuraiDynamicIslandBus.clear(mContext, SamuraiDynamicIslandBus.KEY_DI_TIMER);
                mState.active = false;
                mState.mode = MODE_IDLE;
            } else {
                mState.active = true;
                mState.mode = MODE_TIMER;
                mState.isStopwatch = sw;
                mState.title = timer.title.isEmpty() ? (sw ? "Stopwatch" : "Timer") : timer.title;
                mState.subtitle = sw ? "Counting Up" : "Remaining";
                mState.rightBadge = formatMinSec(diffMs / 1000L);
                mState.accentColor = 0xFFFF9F0A;
                mState.packageName = timer.packageName;
                mState.targetOrStartEpochMs = timer.targetEpochMs;
                scheduleTicker();
            }
        } else if (mMediaPlaying || (mExpanded && !mMediaTitle.isEmpty())) {
            mState.active = true;
            mState.mode = MODE_MEDIA;
            mState.title = getMediaTitle();
            mState.subtitle = getMediaArtist();
            mState.rightBadge = "LIVE";
            mState.accentColor = 0xFF5AC8FA;
            mState.albumArt = mCachedArt;
            mState.packageName = (mActiveMediaController != null)
                    ? mActiveMediaController.getPackageName()
                    : "";
        } else {
            mState.active = false;
            mState.mode = MODE_IDLE;
            mState.title = "";
            mState.subtitle = "";
            mState.rightBadge = "";
            mExpanded = false;
        }

        notifyListeners();
    }

    private void scheduleTicker() {
        mMainHandler.removeCallbacks(mSecondTicker);
        mMainHandler.postDelayed(mSecondTicker, 500L);
    }

    private static String formatMinSec(long totalSec) {
        long hrs = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hrs > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d", mins, secs);
    }
}
