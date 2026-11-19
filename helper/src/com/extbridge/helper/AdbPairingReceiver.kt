package com.extbridge.helper

import android.app.ActivityOptions
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Handles inline 6-digit Wireless Debugging pairing code input from the notification shade,
 * allowing users to pair without dismissing Android's pairing code popup.
 */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (AdbBootstrap.ACTION_STOP_PAIR_NOTIF == action) {
            AdbBootstrap.cancelPairingNotification(context)
            return
        }
        if (AdbBootstrap.ACTION_PAIR_REPLY != action) return

        val input = RemoteInput.getResultsFromIntent(intent) ?: return
        val raw = input.getCharSequence(AdbBootstrap.KEY_PAIR_INPUT)
        val text = raw?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            AdbBootstrap.showPairingNotification(context, "未输入配对码，请重新输入 6 位数字")
            return
        }

        val appCtx = context.applicationContext
        // Keep app process alive via ForegroundService instead of holding PendingResult >10s (ANR)
        BridgeService.ensureStarted(appCtx)
        AdbBootstrap.showPairingNotification(appCtx, "正在验证配对码并启动 ExtBridge...")
        AdbBootstrap.pairFromNotificationAsync(appCtx, text, object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                AdbBootstrap.showPairingNotification(appCtx, message)
            }

            override fun onResult(success: Boolean, message: String?) {
                Toast.makeText(appCtx, message ?: "", Toast.LENGTH_LONG).show()
                if (!success) {
                    AdbBootstrap.showPairingNotification(appCtx, message)
                    return
                }
                Thread({
                    val connected = BridgeClient.getStatus().connected
                    Handler(Looper.getMainLooper()).post {
                        if (connected) {
                            AdbBootstrap.cancelPairingNotification(appCtx)
                            try {
                                val launch = Intent(appCtx, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                if (Build.VERSION.SDK_INT >= 26) {
                                    val ao = ActivityOptions.makeBasic()
                                    ao.setLaunchDisplayId(0)
                                    appCtx.startActivity(launch, ao.toBundle())
                                } else {
                                    appCtx.startActivity(launch)
                                }
                            } catch (_: Throwable) {}
                        } else {
                            AdbBootstrap.showPairingNotification(appCtx, message)
                        }
                    }
                }, "ExtPairCheck").start()
            }
        })
    }
}
