package com.extbridge.helper.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.extbridge.helper.AdbBootstrap
import com.extbridge.helper.AdbPairingReceiver
import com.extbridge.helper.MainActivity

/**
 * Manages the inline RemoteInput notification allowing users to submit the 6-digit Wireless Debugging
 * pairing code from the notification shade without dismissing Android's pairing code popup.
 */
internal object AdbPairingNotifier {
    const val ACTION_PAIR_REPLY = "com.extbridge.helper.ACTION_PAIR_REPLY"
    const val KEY_PAIR_INPUT = "pair_input"

    private const val PAIR_CHANNEL_ID = "extbridge_adb_pair"
    private const val PAIR_NOTIF_ID = 3901

    @Volatile
    var isShowing: Boolean = false
        private set

    fun show(ctx: Context, statusText: String?, discoveryState: AdbBootstrap.DiscoveryState) {
        isShowing = true
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            PAIR_CHANNEL_ID,
            "无线调试快速配对",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在系统配对码弹窗上方直接输入6位配对码完成激活"
        }
        nm.createNotificationChannel(channel)

        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel("输入6位配对码（或 配对码:端口）")
            .build()
        val replyIntent = Intent(ctx, AdbPairingReceiver::class.java).apply { action = ACTION_PAIR_REPLY }
        val mutFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(
            ctx,
            2001,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutFlag,
        )
        val replyAction = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_send),
            "输入配对码",
            replyPi,
        ).addRemoteInput(remoteInput).build()

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPi = PendingIntent.getActivity(
            ctx,
            2002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = when {
            !statusText.isNullOrEmpty() -> statusText
            discoveryState.pairingPort > 0 ->
                "已发现配对端口 ${discoveryState.pairingPort}，下拉后点「输入配对码」填6位数字即可"
            else ->
                "打开系统「使用配对码配对设备」后，点下方「输入配对码」直接输入（格式: 配对码 或 配对码:端口）"
        }

        val notif = Notification.Builder(ctx, PAIR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ExtBridge 无线调试配对助手")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .addAction(replyAction)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()

        nm.notify(PAIR_NOTIF_ID, notif)
    }

    fun cancel(ctx: Context?) {
        isShowing = false
        val nm = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(PAIR_NOTIF_ID)
    }
}
