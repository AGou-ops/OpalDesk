package com.extbridge.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.extbridge.helper.protocol.BridgeProtocol.HelperActions
import com.extbridge.helper.protocol.IpcTrustVerifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

open class ControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ExtControlReceiver"
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
        if (this is Internal) return true
        // Exported ControlReceiver is enforced by android:permission="android.permission.INJECT_EVENTS"
        // (pure signature permission held by com.android.shell, impossible to grant to 3rd-party apps).
        // On Android 14+, when sender identity is explicitly shared (uid != INVALID_UID), also
        // enforce the strict UID allowlist.
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = sentFromUid
            if (uid != Process.INVALID_UID) {
                return IpcTrustVerifier.isTrustedCallerUid(uid)
            }
        }
        return true
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val act = intent.action ?: return
        if (!isTrustedBroadcastSender()) {
            Log.w(TAG, "Rejected untrusted broadcast action=$act")
            return
        }

        when (act) {
            HelperActions.BINDER_PUSH -> {
                handleBinderPush(context, intent)
                return
            }
            HelperActions.STATE_SYNC -> {
                BridgeService.refreshFromReceiver(context)
                return
            }
            HelperActions.EXIT_ALL -> {
                Toast.makeText(context, "正在停止 ExtBridge 后台服务并退出…", Toast.LENGTH_SHORT).show()
                BridgeService.stopAllAndExit(context)
                return
            }
            HelperActions.SET_DOCK_AUTO_HIDE -> {
                val defVal = !BridgeService.isDockAutoHideEnabled(context)
                val en = if (intent.hasExtra("auto_hide")) {
                    intent.getBooleanExtra("auto_hide", defVal)
                } else {
                    intent.getBooleanExtra("enable", defVal)
                }
                BridgeService.setDockAutoHideEnabled(context, en)
                return
            }
        }

        val appCtx = context.applicationContext
        val copy = Intent(intent)
        val pr = goAsync()
        sExec.execute {
            try {
                handleAsyncAction(appCtx, act, copy)
            } catch (t: Throwable) {
                Log.w(TAG, "Async control action failed: $act", t)
            } finally {
                pr?.finish()
            }
        }
    }

    private fun handleBinderPush(context: Context, intent: Intent) {
        if (this is Internal || BridgeService.sUserStopping) return
        if (Build.VERSION.SDK_INT >= 34) {
            val uid = sentFromUid
            if (uid != Process.INVALID_UID && !IpcTrustVerifier.isTrustedDaemonUid(uid)) {
                Log.w(TAG, "Rejected BINDER_PUSH from uid=$uid")
                return
            }
        }
        val cb = intent.extras?.getBinder(HelperActions.EXTRA_CALLBACK_BINDER)
        if (IpcTrustVerifier.isValidDaemonBinder(cb)) {
            BridgeClient.setBinder(cb)
            BridgeService.ensureStarted(context)
            BridgeService.refreshFromReceiver(context)
        }
    }

    private fun handleAsyncAction(appCtx: Context, act: String, copy: Intent) {
        when (act) {
            HelperActions.TOGGLE_INPUT -> {
                val mode = BridgeClient.toggleInput()
                if (mode >= 0) {
                    toastAsync(appCtx, if (mode == 1) "键鼠已切换至：扩展屏" else "键鼠已切换至：平板内屏")
                }
                BridgeService.refreshFromReceiver(appCtx)
            }
            HelperActions.REBUILD -> {
                BridgeClient.rebuild()
                toastAsync(appCtx, "正在刷新扩展屏输出...")
            }
            HelperActions.LAUNCH_EXT_HOME -> {
                BridgeClient.relaunchLauncher()
                toastAsync(appCtx, "已返回扩展屏桌面")
            }
            HelperActions.TOGGLE_MIRROR -> {
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
            HelperActions.SET_MIRROR_SIDE_MODE -> {
                val sideMode = if (copy.hasExtra("side_mode")) {
                    copy.getIntExtra("side_mode", -1)
                } else {
                    copy.getIntExtra("mode", -1)
                }
                val next = BridgeClient.setMirrorSideMode(sideMode)
                BridgeService.refreshFromReceiver(appCtx)
                toastAsync(appCtx, "镜像边栏：${BridgeClient.mirrorSideModeLabel(next)}")
            }
            HelperActions.SPLIT_ACTION -> {
                val splitAct = copy.getIntExtra("action", BridgeClient.SPLIT_TOGGLE_QUICK)
                val arg = copy.getIntExtra("arg", 0)
                val pkg = copy.getStringExtra("pkg")
                val cls = copy.getStringExtra("cls")
                BridgeClient.splitAction(splitAct, arg, pkg, cls)
                BridgeService.refreshFromReceiver(appCtx)
            }
            HelperActions.SET_ROTATION -> {
                val rawRot = if (copy.hasExtra("rot")) {
                    copy.getIntExtra("rot", -1)
                } else {
                    copy.getIntExtra("rotation", -1)
                }
                val rot = when (rawRot) {
                    90 -> 1
                    180 -> 2
                    270 -> 3
                    else -> rawRot
                }
                if (rot >= 0) BridgeClient.setRotation(rot) else BridgeClient.cycleRotation()
                BridgeService.refreshFromReceiver(appCtx)
            }
            HelperActions.DISCONNECT_REMOTE -> {
                val ok = BridgeClient.disconnectRemoteClient()
                BridgeService.refreshFromReceiver(appCtx)
                toastAsync(appCtx, if (ok) "已断开 Mac 大屏连接" else "当前无活跃的 Mac 连接")
            }
        }
    }
}
