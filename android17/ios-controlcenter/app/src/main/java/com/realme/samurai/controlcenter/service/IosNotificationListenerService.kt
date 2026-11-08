package com.realme.samurai.controlcenter.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Immutable
data class IosNotificationAction(
    val title: String,
    val intent: PendingIntent?,
)

@Immutable
data class IosNotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val postTimeMs: Long,
    val isClearable: Boolean,
    val autoCancel: Boolean,
    val appIcon: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val actions: List<IosNotificationAction> = emptyList(),
)

object IosNotificationRepository {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Bitmap>()

    var notifications by mutableStateOf<List<IosNotificationItem>>(emptyList())
        private set

    var listenerConnected by mutableStateOf(false)
        private set

    @Volatile
    var activeInterruptPackages: Set<String> = emptySet()
        private set

    @Volatile
    var hasActiveCallOrAlarmInterrupt: Boolean = false
        private set

    internal fun updateFromActive(
        context: Context,
        rawList: Array<StatusBarNotification>?,
    ) {
        if (rawList == null) {
            val hadInterrupts = activeInterruptPackages.isNotEmpty()
            activeInterruptPackages = emptySet()
            hasActiveCallOrAlarmInterrupt = false
            mainHandler.post {
                notifications = emptyList()
                if (hadInterrupts) {
                    ControlCenterOverlayService.activeInstance?.onNotificationInterruptChanged()
                }
            }
            return
        }
        val pm = context.packageManager
        val mapped = ArrayList<IosNotificationItem>(rawList.size)
        val interruptPkgs = LinkedHashSet<String>()

        for (sbn in rawList) {
            try {
                val n = sbn.notification ?: continue
                val pkg = sbn.packageName
                if (pkg != context.packageName &&
                    pkg != "android" &&
                    pkg != "com.android.systemui"
                ) {
                    val isCallOrAlarmCat = n.category == Notification.CATEGORY_CALL ||
                        n.category == Notification.CATEGORY_ALARM
                    val isOngoingFullScreenInterrupt = n.fullScreenIntent != null && (
                        (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                            (n.flags and Notification.FLAG_INSISTENT) != 0 ||
                            isCallOrAlarmCat
                        )
                    if (isCallOrAlarmCat || isOngoingFullScreenInterrupt) {
                        interruptPkgs.add(pkg)
                    }
                }
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue

                val extras = n.extras
                val rawTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }

                val rawBody = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: ""

                // Hide silent empty foreground-service placeholder notifications (e.g. our own overlay FGS)
                if (sbn.packageName == context.packageName) continue
                if (rawTitle.isNullOrEmpty() && rawBody.isEmpty()) continue
                val isFgs = (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                if (isFgs && rawBody.isEmpty()) continue

                val isTelecomPkg = sbn.packageName == "com.android.server.telecom" ||
                    sbn.packageName == "com.android.phone"
                val iconPkg = if (isTelecomPkg) "com.realme.samurai.dialer" else sbn.packageName

                val appLabel = if (isTelecomPkg) {
                    "Phone"
                } else {
                    try {
                        val ai = pm.getApplicationInfo(sbn.packageName, 0)
                        pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: sbn.packageName
                    } catch (_: Throwable) {
                        sbn.packageName
                    }
                }

                val iconBmp = synchronized(iconCache) {
                    iconCache[iconPkg]
                } ?: loadAppIconBitmap(context, iconPkg, n)?.also { bmp ->
                    synchronized(iconCache) {
                        if (iconCache.size > 48) iconCache.clear()
                        iconCache[iconPkg] = bmp
                    }
                }

                val acts = n.actions?.take(2)?.mapNotNull { act ->
                    val label = act.title?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    IosNotificationAction(title = label, intent = act.actionIntent)
                }.orEmpty()

                mapped.add(
                    IosNotificationItem(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        appName = appLabel,
                        title = rawTitle ?: appLabel,
                        body = rawBody,
                        postTimeMs = sbn.postTime,
                        isClearable = sbn.isClearable,
                        autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
                        appIcon = iconBmp,
                        contentIntent = n.contentIntent,
                        actions = acts,
                    )
                )
            } catch (_: Throwable) {
            }
        }

        mapped.sortByDescending { it.postTimeMs }
        val capped = if (mapped.size > 80) mapped.take(80) else mapped.toList()
        val prevInterrupts = activeInterruptPackages
        val nextInterrupts: Set<String> = interruptPkgs.toSet()
        val interruptsChanged = prevInterrupts != nextInterrupts
        activeInterruptPackages = nextInterrupts
        hasActiveCallOrAlarmInterrupt = nextInterrupts.isNotEmpty()
        mainHandler.post {
            notifications = capped
            if (interruptsChanged) {
                ControlCenterOverlayService.activeInstance?.onNotificationInterruptChanged()
            }
        }
    }

    internal fun setConnected(connected: Boolean) {
        if (!connected) {
            val hadInterrupts = activeInterruptPackages.isNotEmpty()
            activeInterruptPackages = emptySet()
            hasActiveCallOrAlarmInterrupt = false
            mainHandler.post {
                listenerConnected = false
                if (hadInterrupts) {
                    ControlCenterOverlayService.activeInstance?.onNotificationInterruptChanged()
                }
            }
        } else {
            mainHandler.post { listenerConnected = true }
        }
    }

    fun setPreviewNotifications(items: List<IosNotificationItem>) {
        notifications = items
    }

    fun dismissOne(key: String) {
        notifications = notifications.filterNot { it.key == key && it.isClearable }
        IosNotificationListenerService.instance?.cancelSafe(key)
    }

    fun clearAll() {
        notifications = notifications.filterNot { it.isClearable }
        IosNotificationListenerService.instance?.cancelAllSafe()
    }

    fun fireNotificationIntent(item: IosNotificationItem, actionIntent: PendingIntent? = null): Boolean {
        val pi = actionIntent ?: item.contentIntent ?: return false
        val sent = sendPendingIntentAllowBg(pi)
        if (sent && actionIntent == null && item.autoCancel) {
            dismissOne(item.key)
        }
        return sent
    }

    private fun sendPendingIntentAllowBg(pi: PendingIntent): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                try {
                    val m = ActivityOptions::class.java.getMethod(
                        "setPendingIntentBackgroundActivityStartMode",
                        Int::class.javaPrimitiveType,
                    )
                    // MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
                    m.invoke(opts, 1)
                } catch (_: Throwable) {
                }
                pi.send(null, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
            true
        } catch (t: Throwable) {
            Log.w("IosNotifRepo", "PendingIntent send failed: ${t.message}")
            false
        }
    }

    private fun loadAppIconBitmap(context: Context, pkg: String, notification: Notification): Bitmap? {
        return try {
            val d: Drawable? = try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (_: Throwable) {
                notification.smallIcon?.loadDrawable(context)
            }
            if (d == null) return null
            rasterizeDrawable(d, 96)
        } catch (_: Throwable) {
            null
        }
    }

    private fun rasterizeDrawable(d: Drawable, sizePx: Int): Bitmap? {
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null && !b.isRecycled && b.config != Bitmap.Config.HARDWARE) {
                return Bitmap.createScaledBitmap(b, sizePx, sizePx, true)
            }
        }
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(c)
        return out
    }
}

class IosNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        IosNotificationRepository.setConnected(true)
        Log.i(TAG, "IosNotificationListenerService connected")
        refreshAll()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        IosNotificationRepository.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshAll()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshAll()
    }

    fun refreshAll() {
        try {
            IosNotificationRepository.updateFromActive(applicationContext, activeNotifications)
        } catch (t: Throwable) {
            Log.w(TAG, "refreshAll error: ${t.message}")
        }
    }

    fun cancelSafe(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
        }
    }

    fun cancelAllSafe() {
        try {
            cancelAllNotifications()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "IosNotifListener"

        @Volatile
        var instance: IosNotificationListenerService? = null
            private set
    }
}
