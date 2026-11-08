package com.realme.ota

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArraySet

class OtaDownloadService : Service() {

    enum class Status {
        IDLE,
        RUNNING,
        COMPLETED,
        PAUSED,
        FAILED
    }

    data class Snapshot(
        val status: Status = Status.IDLE,
        val release: OtaRelease? = null,
        val localStaging: Boolean = false,
        val localSha: String? = null,
        val localSize: Long = 0L,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBps: Long = 0L,
        val phaseText: String = "",
        val errorMessage: String? = null
    )

    companion object {
        const val ACTION_START_DOWNLOAD = "com.realme.ota.action.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.realme.ota.action.PAUSE_DOWNLOAD"
        const val ACTION_STAGE_LOCAL_URI = "com.realme.ota.action.STAGE_LOCAL_URI"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()

        @Volatile
        private var currentSnap: Snapshot = Snapshot()

        @Volatile
        private var cancelRequested: Boolean = false

        fun snapshot(): Snapshot = currentSnap

        fun addListener(listener: (Snapshot) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (Snapshot) -> Unit) {
            listeners.remove(listener)
        }

        fun resetIdle() {
            publish(Snapshot(status = Status.IDLE))
        }

        private fun publish(next: Snapshot) {
            currentSnap = next
            mainHandler.post {
                for (l in listeners) {
                    try {
                        l(next)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        fun startDownload(ctx: Context, rel: OtaRelease) {
            val appCtx = ctx.applicationContext
            OtaEngine.saveCachedRelease(appCtx, rel)
            val resumed = OtaEngine.resumableBytesOnDisk(appCtx, rel)
            cancelRequested = false
            publish(
                Snapshot(
                    status = Status.RUNNING,
                    release = rel,
                    localStaging = false,
                    downloadedBytes = resumed,
                    totalBytes = rel.sizeBytes,
                    speedBps = 0L,
                    phaseText = if (resumed > 0L) "Resuming system update…" else "Connecting to release server…"
                )
            )
            val intent = Intent(appCtx, OtaDownloadService::class.java).setAction(ACTION_START_DOWNLOAD)
            appCtx.startForegroundService(intent)
        }

        fun startLocalStage(ctx: Context, uri: Uri) {
            val appCtx = ctx.applicationContext
            cancelRequested = false
            publish(
                Snapshot(
                    status = Status.RUNNING,
                    release = null,
                    localStaging = true,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    speedBps = 0L,
                    phaseText = "Staging & verifying local OTA ZIP…"
                )
            )
            val intent = Intent(appCtx, OtaDownloadService::class.java).apply {
                action = ACTION_STAGE_LOCAL_URI
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appCtx.startForegroundService(intent)
        }

        fun pauseDownload(ctx: Context) {
            val appCtx = ctx.applicationContext
            cancelRequested = true
            try {
                appCtx.startService(
                    Intent(appCtx, OtaDownloadService::class.java).setAction(ACTION_PAUSE_DOWNLOAD)
                )
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile
    private var workerThread: Thread? = null

    private var lastNotifUpdateMs: Long = 0L
    private var lastNotifPhase: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_DOWNLOAD

        if (action == ACTION_PAUSE_DOWNLOAD) {
            cancelRequested = true
            workerThread?.interrupt()
            if (workerThread?.isAlive != true) {
                if (currentSnap.status == Status.RUNNING) {
                    publish(currentSnap.copy(status = Status.PAUSED, speedBps = 0L, phaseText = "Download paused"))
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        OtaEngine.ensureNotificationChannel(this)
        promoteToForeground(currentSnap)

        if (workerThread?.isAlive == true) {
            return START_REDELIVER_INTENT
        }

        if (action == ACTION_STAGE_LOCAL_URI) {
            val uri = intent?.data
            if (uri == null) {
                publish(Snapshot(status = Status.FAILED, errorMessage = "Invalid local ZIP URI"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            launchLocalStageWorker(uri)
            return START_REDELIVER_INTENT
        }

        val rel = currentSnap.release ?: OtaEngine.loadCachedRelease(this)
        if (rel == null) {
            publish(Snapshot(status = Status.IDLE))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        launchRemoteDownloadWorker(rel)
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep downloading cleanly in the background even when System update is swiped from Recents
        super.onTaskRemoved(rootIntent)
    }

    private fun promoteToForeground(snap: Snapshot) {
        val notif = buildProgressNotification(snap)
        startForeground(
            OtaEngine.NOTIF_ID_DOWNLOADING,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        lastNotifUpdateMs = System.currentTimeMillis()
        lastNotifPhase = snap.phaseText
    }

    private fun maybeUpdateNotification(snap: Snapshot, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && snap.phaseText == lastNotifPhase && now - lastNotifUpdateMs < 750L) {
            return
        }
        lastNotifUpdateMs = now
        lastNotifPhase = snap.phaseText
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(OtaEngine.NOTIF_ID_DOWNLOADING, buildProgressNotification(snap))
    }

    private fun buildProgressNotification(snap: Snapshot): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            10,
            Intent(this, SystemUpdateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val total = snap.totalBytes
        val done = snap.downloadedBytes
        val indeterminate = total <= 0L
        val permille = if (!indeterminate) ((done * 1000L) / total).toInt().coerceIn(0, 1000) else 0
        val pct = if (!indeterminate) ((done * 100L) / total).coerceIn(0L, 100L) else 0L

        val title = when {
            snap.localStaging -> "Staging local Android 17 OTA…"
            snap.phaseText.startsWith("Verifying") -> "Verifying Android 17 update ($pct%)"
            !indeterminate -> "Downloading Android 17 update ($pct%)"
            else -> "Downloading Android 17 update…"
        }

        val subtitle = buildString {
            if (!indeterminate) {
                append(OtaEngine.formatBytes(done))
                append(" / ")
                append(OtaEngine.formatBytes(total))
                if (snap.speedBps > 0L) {
                    append(" • ")
                    append(OtaEngine.formatBytes(snap.speedBps))
                    append("/s")
                }
            } else if (done > 0L) {
                append(snap.phaseText)
                append(" (")
                append(OtaEngine.formatBytes(done))
                append(")")
            } else {
                append(snap.phaseText.ifEmpty { "Preparing background download…" })
            }
        }

        val builder = Notification.Builder(this, OtaEngine.PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, permille, indeterminate)
            .setContentIntent(openPi)

        if (!snap.localStaging) {
            val pausePi = PendingIntent.getService(
                this,
                11,
                Intent(this, OtaDownloadService::class.java).setAction(ACTION_PAUSE_DOWNLOAD),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "Pause", pausePi).build())
        }

        return builder.build()
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SamuraiOTA::DownloadWakeLock")
            ?.apply {
                setReferenceCounted(false)
                acquire(60L * 60L * 1000L)
            }
    } catch (_: Throwable) {
        null
    }

    private fun acquireWifiLock(): WifiManager.WifiLock? = try {
        applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SamuraiOTA::DownloadWifiLock")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    } catch (_: Throwable) {
        null
    }

    private fun releaseLocks(wl: PowerManager.WakeLock?, wifiLock: WifiManager.WifiLock?) {
        try {
            if (wl?.isHeld == true) wl.release()
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock.release()
        } catch (_: Throwable) {
        }
    }

    private fun launchRemoteDownloadWorker(rel: OtaRelease) {
        cancelRequested = false
        val t = Thread({
            val wl = acquireWakeLock()
            val wifiLock = acquireWifiLock()
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.cancel(OtaEngine.NOTIF_ID_AVAILABLE)

                val verifiedFile = OtaEngine.downloadAndVerify(
                    ctx = applicationContext,
                    rel = rel,
                    isCancelled = { cancelRequested },
                    onProgress = { done, total, speed, phase ->
                        val next = Snapshot(
                            status = Status.RUNNING,
                            release = rel,
                            localStaging = false,
                            downloadedBytes = done,
                            totalBytes = total,
                            speedBps = speed,
                            phaseText = phase
                        )
                        publish(next)
                        maybeUpdateNotification(next)
                    }
                )
                val finalSize = verifiedFile.length()
                publish(
                    Snapshot(
                        status = Status.COMPLETED,
                        release = rel,
                        localStaging = false,
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        speedBps = 0L,
                        phaseText = "Ready to install"
                    )
                )
                OtaEngine.postReadyToInstallNotification(applicationContext, rel.version, finalSize)
            } catch (_: InterruptedException) {
                val partLen = OtaEngine.resumableBytesOnDisk(applicationContext, rel)
                publish(
                    Snapshot(
                        status = Status.PAUSED,
                        release = rel,
                        localStaging = false,
                        downloadedBytes = partLen,
                        totalBytes = rel.sizeBytes,
                        speedBps = 0L,
                        phaseText = "Download paused"
                    )
                )
            } catch (err: Throwable) {
                val partLen = OtaEngine.resumableBytesOnDisk(applicationContext, rel)
                val msg = err.message ?: "Network connection interrupted"
                publish(
                    Snapshot(
                        status = Status.FAILED,
                        release = rel,
                        localStaging = false,
                        downloadedBytes = partLen,
                        totalBytes = rel.sizeBytes,
                        speedBps = 0L,
                        phaseText = "Download interrupted",
                        errorMessage = msg
                    )
                )
                OtaEngine.postDownloadInterruptedNotification(applicationContext, rel.version, msg)
            } finally {
                releaseLocks(wl, wifiLock)
                workerThread = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "SamuraiOtaDownloadWorker")
        workerThread = t
        t.start()
    }

    private fun launchLocalStageWorker(uri: Uri) {
        cancelRequested = false
        val t = Thread({
            val wl = acquireWakeLock()
            try {
                val (file, sha) = OtaEngine.stageLocalUri(applicationContext, uri) { done, _, phase ->
                    val next = Snapshot(
                        status = Status.RUNNING,
                        release = null,
                        localStaging = true,
                        downloadedBytes = done,
                        totalBytes = -1L,
                        speedBps = 0L,
                        phaseText = phase
                    )
                    publish(next)
                    maybeUpdateNotification(next)
                }
                val sz = file.length()
                publish(
                    Snapshot(
                        status = Status.COMPLETED,
                        release = null,
                        localStaging = true,
                        localSha = sha,
                        localSize = sz,
                        downloadedBytes = sz,
                        totalBytes = sz,
                        speedBps = 0L,
                        phaseText = "Ready to install"
                    )
                )
                OtaEngine.postReadyToInstallNotification(applicationContext, "Local OTA ZIP", sz)
            } catch (err: Throwable) {
                publish(
                    Snapshot(
                        status = Status.FAILED,
                        release = null,
                        localStaging = true,
                        errorMessage = err.message ?: "Failed to stage local ZIP"
                    )
                )
            } finally {
                releaseLocks(wl, null)
                workerThread = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "SamuraiOtaLocalStageWorker")
        workerThread = t
        t.start()
    }
}
