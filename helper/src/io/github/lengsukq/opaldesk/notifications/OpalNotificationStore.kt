package io.github.lengsukq.opaldesk.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DesktopNotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val clearable: Boolean,
    val isOngoing: Boolean,
) {
    fun formattedTime(): String {
        if (postTimeMs <= 0L) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postTimeMs))
    }
}

/**
 * Pure JVM-testable filtering rules deciding which Android notifications belong in the OpalDesk
 * DeX Notification Center & live heads-up banners (filtering out self-package, ongoing foreground
 * services, group summaries, and empty system placeholders).
 */
object OpalNotificationFilter {
    private const val SELF_PKG = "io.github.lengsukq.opaldesk"
    private val IGNORED_PACKAGES = setOf(
        SELF_PKG,
        "android",
        "com.android.systemui",
        "com.android.providers.downloads",
    )

    fun shouldInclude(
        packageName: String,
        title: String,
        text: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isForegroundService: Boolean,
    ): Boolean {
        if (packageName.isBlank() || packageName in IGNORED_PACKAGES) return false
        if (isOngoing || isGroupSummary || isForegroundService) return false
        return title.isNotBlank() || text.isNotBlank()
    }
}

/**
 * In-process reactive store bridging [OpalNotificationListenerService] and the DeX desktop UI
 * (both Local DeX on Display 0 and External Monitor / Remote Cast workstations).
 */
object OpalNotificationStore {
    private val _notificationsFlow = MutableStateFlow<List<DesktopNotificationItem>>(emptyList())
    val notificationsFlow: StateFlow<List<DesktopNotificationItem>> = _notificationsFlow.asStateFlow()

    private val _bannerFlow = MutableStateFlow<DesktopNotificationItem?>(null)
    val bannerFlow: StateFlow<DesktopNotificationItem?> = _bannerFlow.asStateFlow()

    private val _listenerConnectedFlow = MutableStateFlow(false)
    val listenerConnectedFlow: StateFlow<Boolean> = _listenerConnectedFlow.asStateFlow()

    private val _accessGrantedFlow = MutableStateFlow(false)
    val accessGrantedFlow: StateFlow<Boolean> = _accessGrantedFlow.asStateFlow()

    private val _doNotDisturbFlow = MutableStateFlow(false)
    val doNotDisturbFlow: StateFlow<Boolean> = _doNotDisturbFlow.asStateFlow()

    @Volatile
    private var activeService: OpalNotificationListenerService? = null

    fun bindService(service: OpalNotificationListenerService?) {
        activeService = service
        _listenerConnectedFlow.value = service != null
        if (service != null) {
            _accessGrantedFlow.value = true
        }
        if (service == null) {
            _bannerFlow.value = null
        }
    }

    fun updateAll(items: List<DesktopNotificationItem>) {
        val sorted = items.sortedByDescending { it.postTimeMs }.take(40)
        _notificationsFlow.value = sorted
        val curBanner = _bannerFlow.value
        if (curBanner != null && sorted.none { it.key == curBanner.key }) {
            _bannerFlow.value = null
        }
    }

    fun onPosted(item: DesktopNotificationItem, showBanner: Boolean) {
        val next = (_notificationsFlow.value.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postTimeMs }
            .take(40)
        _notificationsFlow.value = next
        if (showBanner && !_doNotDisturbFlow.value) {
            _bannerFlow.value = item
        }
    }

    fun setDoNotDisturb(enabled: Boolean) {
        _doNotDisturbFlow.value = enabled
        if (enabled) {
            _bannerFlow.value = null
        }
    }

    fun toggleDoNotDisturb(): Boolean {
        val next = !_doNotDisturbFlow.value
        setDoNotDisturb(next)
        return next
    }

    fun onRemoved(key: String) {
        _notificationsFlow.value = _notificationsFlow.value.filterNot { it.key == key }
        if (_bannerFlow.value?.key == key) {
            _bannerFlow.value = null
        }
    }

    fun dismissBanner(key: String? = null) {
        if (key == null || _bannerFlow.value?.key == key) {
            _bannerFlow.value = null
        }
    }

    fun cancelNotification(key: String) {
        onRemoved(key)
        activeService?.cancelNotificationSafely(key)
    }

    fun clearAllNotifications() {
        val keys = _notificationsFlow.value.filter { it.clearable }.map { it.key }
        _notificationsFlow.value = _notificationsFlow.value.filterNot { it.clearable }
        _bannerFlow.value = null
        activeService?.cancelNotificationsSafely(keys)
    }

    fun requestSync() {
        activeService?.syncActiveNotifications()
    }

    fun refreshAccessState(ctx: Context): Boolean {
        val granted = isAccessGranted(ctx)
        _accessGrantedFlow.value = granted
        return granted
    }

    fun isAccessGranted(ctx: Context): Boolean {
        if (_listenerConnectedFlow.value) return true
        val cn = ComponentName(ctx.applicationContext, OpalNotificationListenerService::class.java)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val nmGranted = runCatching { nm?.isNotificationListenerAccessGranted(cn) == true }.getOrDefault(false)
        if (nmGranted) return true
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(cn.flattenToString()) ||
            enabled.contains(cn.flattenToShortString()) ||
            enabled.contains(ctx.packageName)
    }

    fun openNotificationAccessSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }
}
