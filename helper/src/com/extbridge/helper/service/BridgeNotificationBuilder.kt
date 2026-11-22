package com.extbridge.helper.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.Display
import com.extbridge.helper.ControlReceiver
import com.extbridge.helper.LocalDexActivity
import com.extbridge.helper.MainActivity
import com.extbridge.helper.R
import com.extbridge.helper.SideControlActivity
import com.extbridge.helper.TouchpadActivity
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeProtocol
import com.extbridge.helper.protocol.ConnectionKind
import com.extbridge.helper.protocol.DaemonStatus
import kotlin.math.roundToInt

/**
 * Foreground notification factory + signature diff tracker for [com.extbridge.helper.BridgeService].
 * Derives all 5 notification states from [BridgeLabels.classifyConnectionSummary].
 */
class BridgeNotificationBuilder(
    private val ctx: Context,
    private val notificationManager: NotificationManager?,
) {
    companion object {
        const val CHANNEL_ID = "extbridge_ctrl_v3"
        const val NOTIFICATION_ID = 301

        private const val REQ_MAIN = 1
        private const val REQ_TOUCHPAD = 2
        private const val REQ_TOGGLE_INPUT = 3
        private const val REQ_EXT_HOME = 4
        private const val REQ_EXIT_MIRROR = 5
        private const val REQ_EXIT_ALL = 6
        private const val REQ_SIDE_CTRL = 7
        private const val REQ_DISCONNECT_REMOTE = 8
        private const val REQ_LOCAL_DEX = 9
        private const val REQ_STOP_LOCAL_DEX = 10

        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private var lastSignature: String = ""

    fun ensureChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ExtBridge 外接屏控制", NotificationManager.IMPORTANCE_LOW).apply {
            description = "通知栏一键切换平板/扩展屏键鼠、进入大触摸板及 Mac 无线大屏状态"
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(ch)
    }

    fun resetSignature() {
        lastSignature = ""
    }

    fun notifyIfChanged(status: DaemonStatus, usingA11yOverlay: Boolean) {
        val mgr = notificationManager ?: return
        val sig = computeSignature(status, usingA11yOverlay)
        if (sig != lastSignature) {
            lastSignature = sig
            mgr.notify(NOTIFICATION_ID, build(status, usingA11yOverlay))
        }
    }

    fun computeSignature(status: DaemonStatus, usingA11yOverlay: Boolean): String {
        val brBucket = status.streamBitrateKbps / 500
        val fpsBucket = status.streamFpsX100 / 100
        return "${if (status.connected) 1 else 0}:${status.sinkType}:${status.remotePeerName}:$brBucket:$fpsBucket" +
            ":${status.wifiServerEnabled}:${status.wifiServerPort}:${status.mirrorMode}:${status.extId}:${status.vdId}" +
            ":${status.virtW}x${status.virtH}@${status.virtDpi}:${status.virtFps.roundToInt()}:${status.inputToExt}" +
            ":${if (usingA11yOverlay) 1 else 0}"
    }

    fun build(status: DaemonStatus, usingA11yOverlay: Boolean): Notification {
        val summary = BridgeLabels.classifyConnectionSummary(
            status = status,
            usingA11yOverlay = usingA11yOverlay,
        )
        val piMain = activityPendingIntent(REQ_MAIN, MainActivity::class.java)
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_extbridge)
            .setContentTitle(summary.notificationTitle)
            .setContentText(summary.notificationBody)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(piMain)

        val actions = resolveActions(status, summary.kind, summary.toggleActionLabel, piMain)
        for ((label, pi) in actions) {
            builder.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        return builder.build()
    }

    private fun resolveActions(
        status: DaemonStatus,
        kind: ConnectionKind,
        toggleLabel: String,
        piMain: PendingIntent,
    ): List<Pair<String, PendingIntent>> = when (kind) {
        ConnectionKind.DISCONNECTED,
        ConnectionKind.WIFI_LISTENING,
        ConnectionKind.STANDBY,
        -> listOf(
            "打开控制台" to piMain,
            "停止并退出" to internalBroadcastPendingIntent(REQ_EXIT_ALL, BridgeProtocol.HelperActions.EXIT_ALL),
        )
        ConnectionKind.REMOTE_USB,
        ConnectionKind.REMOTE_WIFI_ADB,
        ConnectionKind.REMOTE_WIFI,
        -> {
            val isMirror = status.mirrorMode == BridgeProtocol.MirrorMode.MIRROR
            val third = if (isMirror) {
                "切回 DeX" to exitMirrorPendingIntent()
            } else {
                "回扩展桌面" to internalBroadcastPendingIntent(REQ_EXT_HOME, BridgeProtocol.HelperActions.LAUNCH_EXT_HOME)
            }
            listOf(
                "断开 Mac" to internalBroadcastPendingIntent(
                    REQ_DISCONNECT_REMOTE,
                    BridgeProtocol.HelperActions.DISCONNECT_REMOTE,
                ),
                "控制台" to piMain,
                third,
            )
        }
        ConnectionKind.LOCAL_DEX -> listOf(
            "返回 DeX" to activityPendingIntent(REQ_LOCAL_DEX, LocalDexActivity::class.java),
            "回扩展桌面" to internalBroadcastPendingIntent(REQ_EXT_HOME, BridgeProtocol.HelperActions.LAUNCH_EXT_HOME),
            "退出本机 DeX" to internalBroadcastPendingIntent(REQ_STOP_LOCAL_DEX, BridgeProtocol.HelperActions.STOP_LOCAL_DEX),
        )
        ConnectionKind.PHYSICAL_MIRROR -> listOf(
            "切回 DeX" to exitMirrorPendingIntent(),
            "大触摸板" to activityPendingIntent(REQ_TOUCHPAD, TouchpadActivity::class.java),
            "副屏直控" to activityPendingIntent(REQ_SIDE_CTRL, SideControlActivity::class.java),
        )
        ConnectionKind.DEX_ACTIVE -> listOf(
            toggleLabel to internalBroadcastPendingIntent(REQ_TOGGLE_INPUT, BridgeProtocol.HelperActions.TOGGLE_INPUT),
            "大触摸板" to activityPendingIntent(REQ_TOUCHPAD, TouchpadActivity::class.java),
            "回扩展桌面" to internalBroadcastPendingIntent(REQ_EXT_HOME, BridgeProtocol.HelperActions.LAUNCH_EXT_HOME),
        )
    }

    private fun activityPendingIntent(reqCode: Int, activityClass: Class<*>): PendingIntent {
        val intent = Intent(ctx, activityClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val opts = ActivityOptions.makeBasic()
            .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            .toBundle()
        return PendingIntent.getActivity(ctx, reqCode, intent, PI_FLAGS, opts)
    }

    private fun internalBroadcastPendingIntent(
        reqCode: Int,
        action: String,
        configure: (Intent.() -> Unit)? = null,
    ): PendingIntent {
        val intent = Intent(ctx, ControlReceiver.Internal::class.java).setAction(action)
        configure?.invoke(intent)
        return PendingIntent.getBroadcast(ctx, reqCode, intent, PI_FLAGS)
    }

    private fun exitMirrorPendingIntent(): PendingIntent =
        internalBroadcastPendingIntent(REQ_EXIT_MIRROR, BridgeProtocol.HelperActions.TOGGLE_MIRROR) {
            putExtra("mode", BridgeProtocol.MirrorMode.DEX)
        }
}
