package com.extbridge.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.extbridge.helper.adb.AdbMdnsDiscovery
import com.extbridge.helper.adb.AdbPairingNotifier
import com.extbridge.helper.adb.AdbProcessRunner
import com.extbridge.helper.adb.DaemonBootstrapScripts
import com.extbridge.helper.prefs.AppPrefs
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Thin orchestrator for on-device ADB and Root daemon activation, delegating mDNS discovery,
 * process execution, shell script construction, and RemoteInput notifications to package
 * `com.extbridge.helper.adb`.
 */
object AdbBootstrap {
    private const val TAG = "ExtBridgeAdb"
    const val ACTION_PAIR_REPLY = AdbPairingNotifier.ACTION_PAIR_REPLY
    const val KEY_PAIR_INPUT = AdbPairingNotifier.KEY_PAIR_INPUT

    private val PAIR_SPLIT_REGEX = Regex("[\\s:]+")
    private val exec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    class DiscoveryState {
        var pairingHost: String = ""
        var pairingPort: Int = 0
        var connectHost: String = ""
        var connectPort: Int = 0
    }

    fun interface DiscoveryListener {
        fun onDiscoveryUpdated(state: DiscoveryState?)
    }

    interface ProgressListener {
        fun onStep(message: String?)
        fun onResult(success: Boolean, message: String?)
    }

    fun getDiscoveryState(): DiscoveryState = AdbMdnsDiscovery.getState()
    fun startDiscovery(ctx: Context, listener: DiscoveryListener?) = AdbMdnsDiscovery.start(ctx, listener)
    fun stopDiscovery() = AdbMdnsDiscovery.stop()
    fun stopDiscoveryIfIdle() {
        if (AdbPairingNotifier.isShowing) AdbMdnsDiscovery.clearListener() else AdbMdnsDiscovery.stop()
    }

    fun showPairingNotification(ctx: Context, statusText: String?) {
        AdbMdnsDiscovery.ensureStarted(ctx.applicationContext)
        AdbPairingNotifier.show(ctx, statusText, AdbMdnsDiscovery.getState())
    }

    fun cancelPairingNotification(ctx: Context?) = AdbPairingNotifier.cancel(ctx)

    fun getLocalIpv4(): String = AdbProcessRunner.getLocalIpv4()
    fun buildPcAdbCommand(): String = DaemonBootstrapScripts.buildPcAdbCommand()
    fun isNativeAdbAvailable(ctx: Context): Boolean = AdbProcessRunner.isNativeAdbAvailable(ctx)
    fun hasSuBinaryHint(): Boolean = AdbProcessRunner.hasSuBinaryHint()
    fun isAutoCloseWifiAdbEnabled(ctx: Context?): Boolean = AppPrefs.isAutoCloseWifiAdbEnabled(ctx)
    fun setAutoCloseWifiAdbEnabled(ctx: Context?, enabled: Boolean) = AppPrefs.setAutoCloseWifiAdbEnabled(ctx, enabled)
    fun isAutoRootEnabled(ctx: Context?): Boolean = AppPrefs.isAutoRootEnabled(ctx)
    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) = AppPrefs.setAutoRootEnabled(ctx, enabled)

    fun killLocalAdbServerAsync(ctx: Context?) {
        val appCtx = ctx?.applicationContext ?: return
        Thread({ AdbProcessRunner.stopLocalAdbServerSync(appCtx) }, "ExtAdbStop").start()
    }

    fun pairAndBootstrapAsync(
        ctx: Context,
        pairHost: String?,
        pairPort: Int,
        pairCode: String?,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) = submitBootstrap(ctx, listener, "pairAndBootstrap") { appCtx ->
        pairAndBootstrapSync(appCtx, pairHost, pairPort, pairCode, connectHost, connectPort, listener)
    }

    fun connectAndBootstrapAsync(
        ctx: Context,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) = submitBootstrap(ctx, listener, "connectAndBootstrap") { appCtx ->
        if (!AdbProcessRunner.isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用"); return@submitBootstrap
        }
        if (connectPort !in 1..65535) {
            postResult(listener, false, "请填写无线调试主页显示的端口号"); return@submitBootstrap
        }
        try { connectAndBootstrapCore(appCtx, connectHost, connectPort, listener, needStartServer = true) }
        finally { AdbProcessRunner.stopLocalAdbServerSync(appCtx) }
    }

    fun pairFromNotificationAsync(ctx: Context, rawInput: String?, listener: ProgressListener?) =
        submitBootstrap(ctx, listener, "pairFromNotification") { appCtx ->
            val ds = getDiscoveryState()
            val parts = rawInput?.trim()?.replace('：', ':').orEmpty().split(PAIR_SPLIT_REGEX).dropLastWhile { it.isEmpty() }
            var host = ds.pairingHost.ifEmpty { getLocalIpv4() }
            var pairPort = ds.pairingPort
            var code = ""
            when {
                parts.size == 1 -> code = parts[0]
                parts.size == 2 && parts[0].length == 6 && parts[1].length != 6 -> {
                    code = parts[0]; pairPort = parts[1].toIntOrNull() ?: pairPort
                }
                parts.size == 2 -> {
                    pairPort = parts[0].toIntOrNull() ?: pairPort; code = parts[1]
                }
                parts.size >= 3 -> {
                    host = parts[0]; pairPort = parts[1].toIntOrNull() ?: pairPort; code = parts[2]
                }
            }
            when {
                code.isEmpty() -> postResult(listener, false, "请输入 6 位配对码")
                !AdbProcessRunner.isAllowedLocalHost(host) -> postResult(listener, false, "安全拦截：配对地址仅允许本机 IP（拒绝外部地址 $host）")
                pairPort !in 1..65535 -> postResult(listener, false, "未自动发现配对端口，请按「配对码:端口」输入（例如 551857:36565）")
                else -> pairAndBootstrapSync(appCtx, host, pairPort, code, ds.connectHost, ds.connectPort, listener)
            }
        }

    fun rootBootstrapAsync(ctx: Context, markAutoRoot: Boolean, listener: ProgressListener?) =
        submitBootstrap(ctx, listener, "rootBootstrap") { appCtx ->
            postStep(listener, "正在请求 Root (su) 权限...")
            val staged = DaemonBootstrapScripts.stageBridgeJarWithSha256(appCtx)
            if (staged == null || staged.first.isEmpty() || staged.second.isEmpty()) {
                postResult(listener, false, "无法从 APK 释放并校验 extbridge.jar"); return@submitBootstrap
            }
            val res = AdbProcessRunner.runSu(25000, DaemonBootstrapScripts.buildRootBootstrapScript(staged.first, staged.second))
            val out = res.output.trim()
            if (res.code != 0 || !out.contains("LAUNCH_DONE")) {
                val hint = if (out.contains("NO_ROOT")) {
                    "未获取到 Root 权限（请在 Magisk / KernelSU / APatch 弹窗中允许授权，或改用无线调试/ADB激活）。"
                } else "Root 执行失败（设备可能未 Root 或未对 ExtBridge 授权 Root）：\n$out"
                postResult(listener, false, hint); return@submitBootstrap
            }
            if (markAutoRoot) setAutoRootEnabled(appCtx, true)
            awaitDaemonBinderReady(appCtx, fromRoot = true, listener, "Root 一键激活成功，ExtBridge 服务已就绪！")
        }

    private fun submitBootstrap(ctx: Context, listener: ProgressListener?, label: String, block: (Context) -> Unit) {
        val appCtx = ctx.applicationContext
        exec.execute {
            try { block(appCtx) }
            catch (t: Throwable) { Log.e(TAG, "$label failed", t); postResult(listener, false, "执行异常: ${t.message}") }
        }
    }

    private fun pairAndBootstrapSync(
        appCtx: Context, pairHost: String?, pairPort: Int, pairCode: String?,
        connectHost: String?, connectPort: Int, listener: ProgressListener?,
    ) {
        if (!AdbProcessRunner.isNativeAdbAvailable(appCtx)) { postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用"); return }
        val pHost = pairHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!AdbProcessRunner.isAllowedLocalHost(pHost)) { postResult(listener, false, "安全拦截：配对主机必须是本机回环或本机局域网 IP"); return }
        val code = pairCode?.trim().orEmpty()
        if (pairPort !in 1..65535 || code.isEmpty()) { postResult(listener, false, "请填写完整的配对端口和 6 位配对码"); return }

        try {
            postStep(listener, "正在启动内置 ADB 服务...")
            val startRes = AdbProcessRunner.runAdb(appCtx, 10000, "start-server")
            if (startRes.code != 0) { postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}"); return }

            val pairTarget = "$pHost:$pairPort"
            postStep(listener, "正在与 $pairTarget 配对...")
            val pairRes = AdbProcessRunner.runAdb(appCtx, 15000, "pair", pairTarget, code)
            val pOut = pairRes.output.trim()
            val pLow = pOut.lowercase(Locale.ROOT)
            if (pairRes.code != 0 || pLow.contains("failed") || pLow.contains("error")) {
                postResult(listener, false, "配对失败 ($pairTarget):\n$pOut\n提示：请确保没有关闭系统的「与设备配对」弹窗导致配对码刷新。")
                return
            }

            postStep(listener, "配对成功！正在等待无线调试连接端口...")
            val (cHost, cPort) = awaitConnectEndpoint(connectHost?.trim().orEmpty().ifEmpty { pHost }, connectPort)
            if (cPort <= 0) {
                postResult(listener, true, "已成功完成设备配对！请关闭系统的配对弹窗，输入无线调试主界面显示的连接端口后点击「直接连接并启动」。")
                return
            }
            connectAndBootstrapCore(appCtx, cHost, cPort, listener, needStartServer = false)
        } finally {
            AdbProcessRunner.stopLocalAdbServerSync(appCtx)
        }
    }

    private fun awaitConnectEndpoint(initialHost: String, initialPort: Int): Pair<String, Int> {
        if (initialPort > 0) return initialHost to initialPort
        val deadline = System.currentTimeMillis() + 4500
        while (System.currentTimeMillis() < deadline) {
            val ds = getDiscoveryState()
            if (ds.connectPort > 0) return ds.connectHost.ifEmpty { initialHost } to ds.connectPort
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return initialHost to 0
    }

    private fun connectAndBootstrapCore(
        appCtx: Context, connectHost: String?, connectPort: Int, listener: ProgressListener?, needStartServer: Boolean,
    ) {
        val host = connectHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!AdbProcessRunner.isAllowedLocalHost(host)) { postResult(listener, false, "安全拦截：无线调试连接地址仅允许本机 IP"); return }
        postStep(listener, "正在连接无线调试 $host:$connectPort...")
        if (needStartServer) {
            val startRes = AdbProcessRunner.runAdb(appCtx, 8000, "start-server")
            if (startRes.code != 0) { postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}"); return }
        }
        val (target, errOut) = AdbProcessRunner.connectAdbTarget(appCtx, host, connectPort)
        if (target == null) {
            postResult(listener, false, "连接无线调试失败 ($host:$connectPort):\n$errOut\n若为新设备，请先用「配对码」完成配对并确认连接端口已更新。")
            return
        }

        postStep(listener, "已连接！正在释放 extbridge.jar 并授权启动守护进程...")
        val script = DaemonBootstrapScripts.buildWirelessAdbBootstrapScript(DaemonBootstrapScripts.computeAssetJarSha256(appCtx))
        val shRes = AdbProcessRunner.runAdb(appCtx, 22000, "-s", target, "shell", script)
        if (shRes.code != 0 || !shRes.output.contains("LAUNCH_DONE")) {
            postResult(listener, false, "远程 Shell 启动失败:\n${shRes.output}"); return
        }
        if (isAutoCloseWifiAdbEnabled(appCtx)) {
            try { AdbProcessRunner.runAdb(appCtx, 2500, "-s", target, "shell", "settings put global adb_wifi_enabled 0") }
            catch (t: Throwable) { Log.w(TAG, "Auto-close wireless ADB failed", t) }
        }
        awaitDaemonBinderReady(appCtx, fromRoot = false, listener, "无线调试激活成功，ExtBridge 服务已就绪！")
    }

    private fun awaitDaemonBinderReady(appCtx: Context, fromRoot: Boolean, listener: ProgressListener?, successMsg: String) {
        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...")
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx)
            if (BridgeClient.getStatus().connected) {
                cancelPairingNotification(appCtx); postResult(listener, true, successMsg); return
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        val tailDiag = if (fromRoot) {
            AdbProcessRunner.runSu(3000, "tail -n 8 /data/local/tmp/extbridge.log 2>/dev/null")
                .output.trim().takeIf { it.isNotEmpty() }?.let { "\n日志摘要:\n$it" }.orEmpty()
        } else ""
        postResult(listener, false, "守护进程命令已下发，但暂未收到 Binder 握手，请稍等几秒或查看 /data/local/tmp/extbridge.log$tailDiag")
    }

    private fun postStep(l: ProgressListener?, msg: String?) { l?.let { mainHandler.post { it.onStep(msg) } } }
    private fun postResult(l: ProgressListener?, ok: Boolean, msg: String?) { l?.let { mainHandler.post { it.onResult(ok, msg) } } }
}
