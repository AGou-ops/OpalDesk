package com.extbridge.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class ControlReceiver : BroadcastReceiver() {
    companion object {
        private val sExec: ExecutorService = Executors.newSingleThreadExecutor()
        private val sMain = Handler(Looper.getMainLooper())

        private fun toastAsync(appCtx: Context?, msg: String?) {
            if (appCtx == null || msg == null) return
            sMain.post { Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Non-exported receiver used exclusively by in-app Notification PendingIntents so internal
     * actions never need exported broadcast permissions.
     */
    class Internal : ControlReceiver()

    private fun isTrustedBroadcastSender(): Boolean {
        if (this is Internal) {
            return true
        }
        // Exported ControlReceiver is enforced by android:permission="android.permission.INJECT_EVENTS"
        // (pure signature permission held by com.android.shell, impossible to grant to 3rd-party apps).
        // On Android 14+, when sender identity is explicitly shared (uid != INVALID_UID), also
        // enforce the strict UID allowlist.
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = sentFromUid
            if (uid != Process.INVALID_UID) {
                return uid == 0 || uid == 1000 || uid == 2000 || uid == Process.myUid()
            }
        }
        return true
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val act = intent.action ?: return
        if (!isTrustedBroadcastSender()) {
            return
        }

        if ("com.extbridge.helper.BINDER_PUSH" == act) {
            if (this is Internal || BridgeService.sUserStopping) return
            if (Build.VERSION.SDK_INT >= 34) {
                val uid = sentFromUid
                if (uid != Process.INVALID_UID && uid != 0 && uid != 1000 && uid != 2000) return
            }
            val cb = intent.extras?.getBinder("cb")
            if (cb != null && cb.pingBinder()) {
                try {
                    if (BridgeClient.BINDER_DESC == cb.interfaceDescriptor) {
                        BridgeClient.setBinder(cb)
                        BridgeService.ensureStarted(context)
                        BridgeService.refreshFromReceiver(context)
                    }
                } catch (_: Throwable) {}
            }
            return
        }

        if ("com.extbridge.helper.STATE_SYNC" == act) {
            BridgeService.refreshFromReceiver(context)
            return
        }
        if (BridgeService.ACTION_EXIT_ALL == act) {
            Toast.makeText(context, "正在停止 ExtBridge 后台服务并退出…", Toast.LENGTH_SHORT).show()
            BridgeService.stopAllAndExit(context)
            return
        }
        if ("com.extbridge.helper.SET_DOCK_AUTO_HIDE" == act) {
            val defVal = !BridgeService.isDockAutoHideEnabled(context)
            val en = if (intent.hasExtra("auto_hide")) {
                intent.getBooleanExtra("auto_hide", defVal)
            } else {
                intent.getBooleanExtra("enable", defVal)
            }
            BridgeService.setDockAutoHideEnabled(context, en)
            return
        }

        val appCtx = context.applicationContext
        val copy = Intent(intent)
        val pr = goAsync()
        sExec.execute {
            try {
                when (act) {
                    "com.extbridge.helper.TOGGLE_INPUT" -> {
                        val mode = BridgeClient.toggleInput()
                        if (mode >= 0) {
                            toastAsync(appCtx, if (mode == 1) "键鼠已切换至：扩展屏" else "键鼠已切换至：平板内屏")
                        }
                        BridgeService.refreshFromReceiver(appCtx)
                    }
                    "com.extbridge.helper.REBUILD" -> {
                        BridgeClient.rebuild()
                        toastAsync(appCtx, "正在刷新扩展屏输出...")
                    }
                    "com.extbridge.helper.LAUNCH_EXT_HOME" -> {
                        BridgeClient.relaunchLauncher()
                        toastAsync(appCtx, "已返回扩展屏桌面")
                    }
                    "com.extbridge.helper.TOGGLE_MIRROR" -> {
                        val sideMode = copy.getIntExtra("side_mode", -1)
                        if (sideMode >= 0) {
                            BridgeClient.setMirrorSideMode(sideMode)
                        }
                        val req = copy.getIntExtra("mode", -1)
                        val next = BridgeClient.setMirrorMode(req)
                        BridgeService.refreshFromReceiver(appCtx)
                        toastAsync(
                            appCtx,
                            if (next == 1) "已切换为【原生镜像模式】" else "已切换为【ExtBridge DeX 扩展模式】",
                        )
                    }
                    "com.extbridge.helper.SET_MIRROR_SIDE_MODE" -> {
                        val sideMode = if (copy.hasExtra("side_mode")) {
                            copy.getIntExtra("side_mode", -1)
                        } else {
                            copy.getIntExtra("mode", -1)
                        }
                        val next = BridgeClient.setMirrorSideMode(sideMode)
                        BridgeService.refreshFromReceiver(appCtx)
                        toastAsync(appCtx, "镜像边栏：${BridgeClient.mirrorSideModeLabel(next)}")
                    }
                    "com.extbridge.helper.SPLIT_ACTION" -> {
                        val splitAct = copy.getIntExtra("action", BridgeClient.SPLIT_TOGGLE_QUICK)
                        val arg = copy.getIntExtra("arg", 0)
                        val pkg = copy.getStringExtra("pkg")
                        val cls = copy.getStringExtra("cls")
                        BridgeClient.splitAction(splitAct, arg, pkg, cls)
                        BridgeService.refreshFromReceiver(appCtx)
                    }
                    "com.extbridge.helper.SET_ROTATION" -> {
                        var rot = if (copy.hasExtra("rot")) {
                            copy.getIntExtra("rot", -1)
                        } else {
                            copy.getIntExtra("rotation", -1)
                        }
                        rot = when (rot) {
                            90 -> 1
                            180 -> 2
                            270 -> 3
                            else -> rot
                        }
                        if (rot >= 0) BridgeClient.setRotation(rot) else BridgeClient.cycleRotation()
                        BridgeService.refreshFromReceiver(appCtx)
                    }
                    BridgeService.ACTION_DISCONNECT_REMOTE -> {
                        val ok = BridgeClient.disconnectRemoteClient()
                        BridgeService.refreshFromReceiver(appCtx)
                        toastAsync(appCtx, if (ok) "已断开 Mac 大屏连接" else "当前无活跃的 Mac 连接")
                    }
                }
            } catch (_: Throwable) {
            } finally {
                pr?.finish()
            }
        }
    }
}
