package io.github.lengsukq.opaldesk.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Standard AOSP [NotificationListenerService] streaming phone app notifications into OpalDesk's
 * DeX desktop notification center and heads-up glass banners (100% rootless & OEM-agnostic).
 */
class OpalNotificationListenerService : NotificationListenerService() {

    private val appLabelCache = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        OpalNotificationStore.bindService(this)
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        OpalNotificationStore.bindService(null)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        OpalNotificationStore.bindService(null)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val item = toDesktopItem(sbn) ?: return
        OpalNotificationStore.onPosted(item, showBanner = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        OpalNotificationStore.onRemoved(key)
    }

    fun syncActiveNotifications() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val items = active.mapNotNull { toDesktopItem(it) }
        OpalNotificationStore.updateAll(items)
    }

    fun cancelNotificationSafely(key: String) {
        runCatching { cancelNotification(key) }
    }

    fun cancelNotificationsSafely(keys: List<String>) {
        if (keys.isEmpty()) return
        runCatching { cancelNotifications(keys.toTypedArray()) }
    }

    private fun toDesktopItem(sbn: StatusBarNotification): DesktopNotificationItem? {
        val pkg = sbn.packageName.orEmpty()
        val notif = sbn.notification ?: return null
        val flags = notif.flags
        val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isFgs = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val plainText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifEmpty { plainText }

        if (!OpalNotificationFilter.shouldInclude(
                packageName = pkg,
                title = title,
                text = body,
                isOngoing = isOngoing,
                isGroupSummary = isGroupSummary,
                isForegroundService = isFgs,
            )
        ) {
            return null
        }

        return DesktopNotificationItem(
            key = sbn.key,
            packageName = pkg,
            appLabel = resolveAppLabel(pkg),
            title = title.ifEmpty { resolveAppLabel(pkg) },
            text = body,
            postTimeMs = sbn.postTime,
            clearable = sbn.isClearable,
            isOngoing = isOngoing,
        )
    }

    private fun resolveAppLabel(pkg: String): String {
        return appLabelCache.getOrPut(pkg) {
            try {
                val pm = packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            } catch (_: Throwable) {
                pkg
            }
        }
    }
}
