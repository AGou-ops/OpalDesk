package com.extbridge.helper;

import android.app.ActivityOptions;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Handles inline 6-digit Wireless Debugging pairing code input from the notification shade,
 * allowing users to pair without dismissing Android's pairing code popup.
 */
public class AdbPairingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (AdbBootstrap.ACTION_STOP_PAIR_NOTIF.equals(action)) {
            AdbBootstrap.cancelPairingNotification(context);
            return;
        }
        if (!AdbBootstrap.ACTION_PAIR_REPLY.equals(action)) return;

        Bundle input = RemoteInput.getResultsFromIntent(intent);
        if (input == null) return;
        CharSequence raw = input.getCharSequence(AdbBootstrap.KEY_PAIR_INPUT);
        String text = raw == null ? "" : raw.toString().trim();
        if (text.isEmpty()) {
            AdbBootstrap.showPairingNotification(context, "未输入配对码，请重新输入 6 位数字");
            return;
        }

        final Context appCtx = context.getApplicationContext();
        // Keep app process alive via ForegroundService instead of holding PendingResult >10s (ANR)
        BridgeService.ensureStarted(appCtx);
        AdbBootstrap.showPairingNotification(appCtx, "正在验证配对码并启动 ExtBridge...");
        AdbBootstrap.pairFromNotificationAsync(appCtx, text, new AdbBootstrap.ProgressListener() {
            @Override
            public void onStep(String message) {
                AdbBootstrap.showPairingNotification(appCtx, message);
            }

            @Override
            public void onResult(boolean success, String message) {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
                if (!success) {
                    AdbBootstrap.showPairingNotification(appCtx, message);
                    return;
                }
                new Thread(() -> {
                    boolean connected = BridgeClient.getStatus().connected;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (connected) {
                            AdbBootstrap.cancelPairingNotification(appCtx);
                            try {
                                Intent launch = new Intent(appCtx, MainActivity.class);
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                if (Build.VERSION.SDK_INT >= 26) {
                                    ActivityOptions ao = ActivityOptions.makeBasic();
                                    ao.setLaunchDisplayId(0);
                                    appCtx.startActivity(launch, ao.toBundle());
                                } else {
                                    appCtx.startActivity(launch);
                                }
                            } catch (Throwable ignored) {}
                        } else {
                            AdbBootstrap.showPairingNotification(appCtx, message);
                        }
                    });
                }, "ExtPairCheck").start();
            }
        });
    }
}
