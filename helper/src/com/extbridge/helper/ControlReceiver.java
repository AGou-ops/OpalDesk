package com.extbridge.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ControlReceiver extends BroadcastReceiver {
    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static void toastAsync(Context appCtx, String msg) {
        if (appCtx == null || msg == null) return;
        sMain.post(() -> Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        final String act = intent.getAction();
        if (act == null) return;

        if ("com.extbridge.helper.STATE_SYNC".equals(act)) {
            BridgeService.refreshFromReceiver(context);
            return;
        }
        if (BridgeService.ACTION_EXIT_ALL.equals(act)) {
            Toast.makeText(context, "正在停止 ExtBridge 后台服务并退出…", Toast.LENGTH_SHORT).show();
            BridgeService.stopAllAndExit(context);
            return;
        }
        if ("com.extbridge.helper.SET_DOCK_AUTO_HIDE".equals(act)) {
            boolean en = intent.getBooleanExtra("enable", !BridgeService.isDockAutoHideEnabled(context));
            BridgeService.setDockAutoHideEnabled(context, en);
            return;
        }
        if ("com.extbridge.helper.MIR_EDGE_CAPTURE".equals(act)) {
            boolean enter = intent.getBooleanExtra("enter", true);
            BridgeService.onEdgeCaptureFromDaemon(enter);
            return;
        }

        final Context appCtx = context.getApplicationContext();
        final Intent copy = new Intent(intent);
        final PendingResult pr = goAsync();
        sExec.execute(() -> {
            try {
                if ("com.extbridge.helper.TOGGLE_INPUT".equals(act)) {
                    int mode = BridgeClient.toggleInput();
                    if (mode >= 0) {
                        toastAsync(appCtx, mode == 1 ? "键鼠已切换至：扩展屏" : "键鼠已切换至：平板内屏");
                    }
                    BridgeService.refreshFromReceiver(appCtx);
                } else if ("com.extbridge.helper.REBUILD".equals(act)) {
                    BridgeClient.rebuild();
                    toastAsync(appCtx, "正在刷新扩展屏输出...");
                } else if ("com.extbridge.helper.LAUNCH_EXT_HOME".equals(act)) {
                    BridgeClient.relaunchLauncher();
                    toastAsync(appCtx, "已返回扩展屏桌面");
                } else if ("com.extbridge.helper.TOGGLE_MIRROR".equals(act)) {
                    int sideMode = copy.getIntExtra("side_mode", -1);
                    if (sideMode >= 0) {
                        BridgeClient.setMirrorSideMode(sideMode);
                    }
                    int req = copy.getIntExtra("mode", -1);
                    int next = BridgeClient.setMirrorMode(req);
                    BridgeService.refreshFromReceiver(appCtx);
                    toastAsync(appCtx, next == 1 ? "已切换为【原生镜像模式】" : "已切换为【ExtBridge DeX 扩展模式】");
                } else if ("com.extbridge.helper.SET_MIRROR_SIDE_MODE".equals(act)) {
                    int sideMode = copy.getIntExtra("side_mode", -1);
                    int next = BridgeClient.setMirrorSideMode(sideMode);
                    BridgeService.refreshFromReceiver(appCtx);
                    toastAsync(appCtx, "镜像边栏：" + BridgeClient.mirrorSideModeLabel(next));
                } else if ("com.extbridge.helper.SPLIT_ACTION".equals(act)) {
                    int splitAct = copy.getIntExtra("action", BridgeClient.SPLIT_TOGGLE_QUICK);
                    int arg = copy.getIntExtra("arg", 0);
                    String pkg = copy.getStringExtra("pkg");
                    String cls = copy.getStringExtra("cls");
                    BridgeClient.splitAction(splitAct, arg, pkg, cls);
                    BridgeService.refreshFromReceiver(appCtx);
                } else if ("com.extbridge.helper.SET_ROTATION".equals(act)) {
                    int rot = copy.getIntExtra("rot", -1);
                    if (rot >= 0) BridgeClient.setRotation(rot);
                    else BridgeClient.cycleRotation();
                    BridgeService.refreshFromReceiver(appCtx);
                }
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        });
    }
}
