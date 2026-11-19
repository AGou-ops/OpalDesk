package com.extbridge.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Self-contained on-device ADB helper bundling native arm64-v8a adb (libadb.so),
 * NsdManager mDNS discovery for Wireless Debugging pairing/connect endpoints,
 * and RemoteInput notification support for typing the 6-digit pairing code
 * without dismissing Android's pairing popup.
 */
object AdbBootstrap {
    private const val TAG = "ExtBridgeAdb"
    const val ACTION_PAIR_REPLY = "com.extbridge.helper.ACTION_PAIR_REPLY"
    const val ACTION_STOP_PAIR_NOTIF = "com.extbridge.helper.ACTION_STOP_PAIR_NOTIF"
    const val KEY_PAIR_INPUT = "pair_input"
    private const val PAIR_CHANNEL_ID = "extbridge_adb_pair"
    private const val PAIR_NOTIF_ID = 3901
    private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
    private const val SERVICE_CONNECT = "_adb-tls-connect._tcp."
    private const val PREFS_NAME = "extbridge_prefs"
    private const val KEY_AUTO_ROOT = "auto_root_enabled"
    private const val KEY_AUTO_CLOSE_WIFI_ADB = "auto_close_wifi_adb"

    private val ALLOWED_HOST_REGEX = Regex("^[0-9a-fA-F.:]+\$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}\$")
    private val PAIR_SPLIT_REGEX = Regex("[\\s:]+")

    private var sActivePairSvcName = ""
    private var sActiveConnSvcName = ""

    private val sExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val sMain = Handler(Looper.getMainLooper())

    class DiscoveryState {
        @JvmField var pairingHost: String = ""
        @JvmField var pairingPort: Int = 0
        @JvmField var connectHost: String = ""
        @JvmField var connectPort: Int = 0
    }

    fun interface DiscoveryListener {
        fun onDiscoveryUpdated(state: DiscoveryState?)
    }

    interface ProgressListener {
        fun onStep(message: String?)
        fun onResult(success: Boolean, message: String?)
    }

    private class CmdRes(val code: Int, val output: String?)

    private var sNsd: NsdManager? = null
    private var sPairDisc: NsdManager.DiscoveryListener? = null
    private var sConnDisc: NsdManager.DiscoveryListener? = null
    private var sMcastLock: WifiManager.MulticastLock? = null
    private val sDiscState = DiscoveryState()
    private val sResolveQueue = ArrayDeque<NsdServiceInfo>()
    private var sResolving = false
    private var sDiscListener: DiscoveryListener? = null

    @Volatile
    private var sPairNotifShowing = false

    @JvmStatic
    @Synchronized
    fun getDiscoveryState(): DiscoveryState = DiscoveryState().apply {
        pairingHost = sDiscState.pairingHost
        pairingPort = sDiscState.pairingPort
        connectHost = sDiscState.connectHost
        connectPort = sDiscState.connectPort
    }

    @JvmStatic
    @Synchronized
    fun startDiscovery(ctx: Context, listener: DiscoveryListener?) {
        sDiscListener = listener
        val appCtx = ctx.applicationContext
        if (sNsd != null) {
            notifyDiscListener()
            return
        }
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && sMcastLock == null) {
                sMcastLock = wm.createMulticastLock("extbridge_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock unavailable", t)
        }

        try {
            val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            sNsd = nsd
            val pairDisc = makeDiscoveryListener(true)
            val connDisc = makeDiscoveryListener(false)
            sPairDisc = pairDisc
            sConnDisc = connDisc
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairDisc)
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connDisc)
        } catch (t: Throwable) {
            Log.e(TAG, "startDiscovery failed", t)
        }
    }

    @JvmStatic
    @Synchronized
    fun stopDiscoveryIfIdle() {
        if (sPairNotifShowing) {
            sDiscListener = null
            return
        }
        stopDiscovery()
    }

    @JvmStatic
    @Synchronized
    fun stopDiscovery() {
        sDiscListener = null
        val nsd = sNsd
        if (nsd != null) {
            try { sPairDisc?.let { nsd.stopServiceDiscovery(it) } } catch (_: Throwable) {}
            try { sConnDisc?.let { nsd.stopServiceDiscovery(it) } } catch (_: Throwable) {}
        }
        sPairDisc = null
        sConnDisc = null
        sNsd = null
        synchronized(sResolveQueue) {
            sResolveQueue.clear()
            sResolving = false
        }
        val lock = sMcastLock
        if (lock != null) {
            try { if (lock.isHeld) lock.release() } catch (_: Throwable) {}
            sMcastLock = null
        }
    }

    private fun makeDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) {
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val lostName = serviceInfo?.serviceName ?: ""
                synchronized(AdbBootstrap) {
                    if (isPairing) {
                        if (lostName.isEmpty() || sActivePairSvcName.isEmpty() || lostName == sActivePairSvcName) {
                            sDiscState.pairingPort = 0
                            sActivePairSvcName = ""
                            notifyDiscListener()
                        }
                    } else {
                        if (lostName.isEmpty() || sActiveConnSvcName.isEmpty() || lostName == sActiveConnSvcName) {
                            sDiscState.connectPort = 0
                            sActiveConnSvcName = ""
                            notifyDiscListener()
                        }
                    }
                }
            }
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(sResolveQueue) {
            sResolveQueue.addLast(info)
            pumpResolveLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveLocked() {
        val nsd = sNsd
        if (sResolving || sResolveQueue.isEmpty() || nsd == null) return
        val next = sResolveQueue.pollFirst() ?: return
        sResolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(sResolveQueue) {
                        sResolving = false
                        pumpResolveLocked()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    if (resolved != null) {
                        handleResolved(resolved)
                    }
                    synchronized(sResolveQueue) {
                        sResolving = false
                        pumpResolveLocked()
                    }
                }
            })
        } catch (_: Throwable) {
            sResolving = false
            pumpResolveLocked()
        }
    }

    private fun isLocalDeviceAddress(addr: InetAddress?): Boolean {
        if (addr == null) return false
        if (addr.isLoopbackAddress) return true
        try {
            if (NetworkInterface.getByInetAddress(addr) != null) return true
        } catch (_: Throwable) {}
        var ip = addr.hostAddress
        if (ip != null && ip.contains('%')) {
            ip = ip.substring(0, ip.indexOf('%'))
        }
        return ip != null && ip == getLocalIpv4()
    }

    private fun isAllowedLocalHost(rawHost: String?): Boolean {
        if (rawHost == null) return false
        var h = rawHost.trim()
        if (h.startsWith("[") && h.endsWith("]") && h.length > 2) {
            h = h.substring(1, h.length - 1)
        }
        if (h.contains('%')) {
            h = h.substring(0, h.indexOf('%'))
        }
        if (h.isEmpty()) return false
        if ("127.0.0.1" == h || "localhost".equals(h, ignoreCase = true) || "::1" == h) {
            return true
        }
        // Only parse numeric IPv4/IPv6 literals so crafted strings never trigger external DNS resolution
        if (!ALLOWED_HOST_REGEX.matches(h)) {
            return false
        }
        return try {
            isLocalDeviceAddress(InetAddress.getByName(h))
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResolved(resolved: NsdServiceInfo) {
        val type = resolved.serviceType ?: ""
        val addr = resolved.host
        if (!isLocalDeviceAddress(addr)) return
        var host = addr?.hostAddress ?: ""
        if (host.contains('%')) {
            host = host.substring(0, host.indexOf('%'))
        }
        if (host.contains(':') && !host.startsWith("[")) {
            // Prefer local IPv4 for adb connect/pair when link-local IPv6 scope was stripped
            host = getLocalIpv4()
        }
        val port = resolved.port
        if (port <= 0) return
        val svcName = resolved.serviceName ?: ""

        synchronized(AdbBootstrap) {
            if (type.contains("pairing")) {
                sDiscState.pairingHost = host
                sDiscState.pairingPort = port
                sActivePairSvcName = svcName
            } else if (type.contains("connect")) {
                sDiscState.connectHost = host
                sDiscState.connectPort = port
                sActiveConnSvcName = svcName
            }
            notifyDiscListener()
        }
    }

    private fun notifyDiscListener() {
        val l = sDiscListener ?: return
        val snap = getDiscoveryState()
        sMain.post { l.onDiscoveryUpdated(snap) }
    }

    @JvmStatic
    fun getLocalIpv4(): String {
        var best: String? = null
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name?.lowercase(Locale.ROOT) ?: ""
                if (name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp") ||
                    name.startsWith("dummy")
                ) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                            return ip
                        }
                        if (!name.startsWith("rmnet") && !name.startsWith("ccmni")) {
                            if (best == null) best = ip
                        } else if (fallback == null) {
                            fallback = ip
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return best ?: fallback ?: "127.0.0.1"
    }

    @JvmStatic
    fun buildPcAdbCommand(): String {
        // Zero '$', zero backticks, zero escaped inner quotes: runs identically in Bash, Zsh, sh, PowerShell, and cmd.exe!
        return "adb shell \"rm -f /data/local/tmp/extb_src.apk /data/local/tmp/extbridge.jar.new && " +
            "pm path com.extbridge.helper | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/extb_src.apk && " +
            "test -s /data/local/tmp/extb_src.apk && " +
            "unzip -p /data/local/tmp/extb_src.apk assets/extbridge.jar > /data/local/tmp/extbridge.jar.new && " +
            "rm -f /data/local/tmp/extb_src.apk && " +
            "test -s /data/local/tmp/extbridge.jar.new && " +
            "unzip -tq /data/local/tmp/extbridge.jar.new >/dev/null 2>&1 && " +
            "chmod 644 /data/local/tmp/extbridge.jar.new && " +
            "timeout 6 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1 && " +
            "(timeout 8 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true) && " +
            "(pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1 && (pkill -f '^app_process.*ExtBridge' 2>/dev/null || true) && sleep 1 && (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null || true) || true) && " +
            "rm -f /data/local/tmp/extbridge.jar && " +
            "mv -f /data/local/tmp/extbridge.jar.new /data/local/tmp/extbridge.jar && " +
            "test -s /data/local/tmp/extbridge.jar && " +
            "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow && " +
            "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow && " +
            "(appops set com.extbridge.helper 10021 allow 2>/dev/null || true) && " +
            "(appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && " +
            "(pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && " +
            "(dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1 || true) && " +
            "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &)\""
    }

    private fun getPrivateAdbSocketFile(ctx: Context): File {
        val sockDir = File(ctx.cacheDir, "asrv")
        if (!sockDir.exists()) {
            sockDir.mkdirs()
        }
        sockDir.setReadable(false, false)
        sockDir.setReadable(true, true)
        sockDir.setWritable(false, false)
        sockDir.setWritable(true, true)
        sockDir.setExecutable(false, false)
        sockDir.setExecutable(true, true)
        return File(sockDir, "a.sock")
    }

    private fun stopLocalAdbServerSync(appCtx: Context?) {
        if (appCtx == null) return
        if (isNativeAdbAvailable(appCtx)) {
            try { runAdb(appCtx, 1500, "disconnect") } catch (_: Throwable) {}
            try { runAdb(appCtx, 2500, "kill-server") } catch (_: Throwable) {}
        }
        try {
            val sockFile = getPrivateAdbSocketFile(appCtx)
            if (sockFile.exists()) sockFile.delete()
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun killLocalAdbServerAsync(ctx: Context?) {
        if (ctx == null) return
        val appCtx = ctx.applicationContext
        Thread({ stopLocalAdbServerSync(appCtx) }, "ExtAdbStop").start()
    }

    @JvmStatic
    fun isNativeAdbAvailable(ctx: Context): Boolean {
        val f = getAdbFile(ctx)
        return f.exists() && f.canExecute()
    }

    private fun getAdbFile(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir, "libadb.so")

    @JvmStatic
    fun pairAndBootstrapAsync(
        ctx: Context,
        pairHost: String?,
        pairPort: Int,
        pairCode: String?,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) {
        val appCtx = ctx.applicationContext
        sExec.execute {
            try {
                pairAndBootstrapSync(appCtx, pairHost, pairPort, pairCode, connectHost, connectPort, listener)
            } catch (t: Throwable) {
                Log.e(TAG, "pairAndBootstrap failed", t)
                postResult(listener, false, "执行异常: ${t.message}")
            }
        }
    }

    @JvmStatic
    fun connectAndBootstrapAsync(
        ctx: Context,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) {
        val appCtx = ctx.applicationContext
        sExec.execute {
            try {
                connectAndBootstrapSync(appCtx, connectHost, connectPort, listener)
            } catch (t: Throwable) {
                Log.e(TAG, "connectAndBootstrap failed", t)
                postResult(listener, false, "执行异常: ${t.message}")
            }
        }
    }

    @JvmStatic
    fun pairFromNotificationAsync(
        ctx: Context,
        rawInput: String?,
        listener: ProgressListener?,
    ) {
        val appCtx = ctx.applicationContext
        sExec.execute {
            val ds = getDiscoveryState()
            val cleaned = rawInput?.trim()?.replace('：', ':') ?: ""
            var code = ""
            var host = ds.pairingHost.ifEmpty { getLocalIpv4() }
            var pairPort = ds.pairingPort

            val parts = cleaned.split(PAIR_SPLIT_REGEX).dropLastWhile { it.isEmpty() }
            if (parts.size == 1) {
                code = parts[0]
            } else if (parts.size == 2) {
                // Either "CODE PORT" or "PORT CODE"
                if (parts[0].length == 6 && parts[1].length != 6) {
                    code = parts[0]
                    pairPort = parseIntSafe(parts[1], pairPort)
                } else {
                    pairPort = parseIntSafe(parts[0], pairPort)
                    code = parts[1]
                }
            } else if (parts.size >= 3) {
                host = parts[0]
                pairPort = parseIntSafe(parts[1], pairPort)
                code = parts[2]
            }

            if (code.isEmpty()) {
                postResult(listener, false, "请输入 6 位配对码")
                return@execute
            }
            if (!isAllowedLocalHost(host)) {
                postResult(listener, false, "安全拦截：配对地址仅允许本机 IP（拒绝外部地址 $host）")
                return@execute
            }
            if (pairPort <= 0 || pairPort > 65535) {
                postResult(listener, false, "未自动发现配对端口，请按「配对码:端口」输入（例如 551857:36565）")
                return@execute
            }
            pairAndBootstrapSync(appCtx, host, pairPort, code, ds.connectHost, ds.connectPort, listener)
        }
    }

    private fun pairAndBootstrapSync(
        appCtx: Context,
        pairHost: String?,
        pairPort: Int,
        pairCode: String?,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用")
            return
        }
        val pHost = pairHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!isAllowedLocalHost(pHost)) {
            postResult(listener, false, "安全拦截：配对主机必须是本机回环或本机局域网 IP")
            return
        }
        val code = pairCode?.trim() ?: ""
        if (pairPort <= 0 || pairPort > 65535 || code.isEmpty()) {
            postResult(listener, false, "请填写完整的配对端口和 6 位配对码")
            return
        }

        try {
            postStep(listener, "正在启动内置 ADB 服务...")
            val startRes = runAdb(appCtx, 10000, "start-server")
            if (startRes.code != 0) {
                postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}")
                return
            }

            val pairTarget = "$pHost:$pairPort"
            postStep(listener, "正在与 $pairTarget 配对...")
            val pairRes = runAdb(appCtx, 15000, "pair", pairTarget, code)
            val pOut = pairRes.output?.trim() ?: ""
            val pOutLow = pOut.lowercase(Locale.ROOT)
            val pairOk = pairRes.code == 0 && !pOutLow.contains("failed") && !pOutLow.contains("error")
            if (!pairOk) {
                postResult(
                    listener,
                    false,
                    "配对失败 ($pairTarget):\n$pOut\n提示：请确保没有关闭系统的「与设备配对」弹窗导致配对码刷新。",
                )
                return
            }

            postStep(listener, "配对成功！正在等待无线调试连接端口...")
            var cHost = connectHost?.trim().orEmpty().ifEmpty { pHost }
            var cPort = connectPort
            if (cPort <= 0) {
                val deadline = System.currentTimeMillis() + 4500
                while (System.currentTimeMillis() < deadline) {
                    val ds = getDiscoveryState()
                    if (ds.connectPort > 0) {
                        cPort = ds.connectPort
                        if (ds.connectHost.isNotEmpty()) cHost = ds.connectHost
                        break
                    }
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            if (cPort <= 0) {
                postResult(
                    listener,
                    true,
                    "已成功完成设备配对！请关闭系统的配对弹窗，输入无线调试主界面显示的连接端口后点击「直接连接并启动」。",
                )
                return
            }
            connectAndBootstrapCore(appCtx, cHost, cPort, listener, false)
        } finally {
            stopLocalAdbServerSync(appCtx)
        }
    }

    private fun connectAndBootstrapSync(
        appCtx: Context,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
    ) {
        if (!isNativeAdbAvailable(appCtx)) {
            postResult(listener, false, "内置 ADB 模块 (libadb.so) 不可用")
            return
        }
        if (connectPort <= 0 || connectPort > 65535) {
            postResult(listener, false, "请填写无线调试主页显示的端口号")
            return
        }
        try {
            connectAndBootstrapCore(appCtx, connectHost, connectPort, listener, true)
        } finally {
            stopLocalAdbServerSync(appCtx)
        }
    }

    private fun connectAndBootstrapCore(
        appCtx: Context,
        connectHost: String?,
        connectPort: Int,
        listener: ProgressListener?,
        needStartServer: Boolean,
    ) {
        val host = connectHost?.trim().orEmpty().ifEmpty { getLocalIpv4() }
        if (!isAllowedLocalHost(host)) {
            postResult(listener, false, "安全拦截：无线调试连接地址仅允许本机 IP")
            return
        }
        var target = "$host:$connectPort"

        postStep(listener, "正在连接无线调试 $target...")
        if (needStartServer) {
            val startRes = runAdb(appCtx, 8000, "start-server")
            if (startRes.code != 0) {
                postResult(listener, false, "ADB 服务启动失败:\n${startRes.output}")
                return
            }
        }
        val connRes = runAdb(appCtx, 12000, "connect", target)
        val cOut = connRes.output?.trim() ?: ""
        val cLow = cOut.lowercase(Locale.ROOT)
        var connOk = (cLow.contains("connected to") || cLow.contains("already connected")) &&
            !cLow.contains("failed") && !cLow.contains("cannot connect") && !cLow.contains("unable to connect")
        if (!connOk && "127.0.0.1" != host) {
            val fbTarget = "127.0.0.1:$connectPort"
            val fbRes = runAdb(appCtx, 10000, "connect", fbTarget)
            val fbOut = fbRes.output?.trim() ?: ""
            val fbLow = fbOut.lowercase(Locale.ROOT)
            val fbOk = (fbLow.contains("connected to") || fbLow.contains("already connected")) &&
                !fbLow.contains("failed") && !fbLow.contains("cannot connect") && !fbLow.contains("unable to connect")
            if (fbOk) {
                target = fbTarget
                connOk = true
            }
        }
        if (!connOk) {
            postResult(
                listener,
                false,
                "连接无线调试失败 ($target):\n$cOut\n若为新设备，请先用「配对码」完成配对并确认连接端口已更新。",
            )
            return
        }

        postStep(listener, "已连接！正在释放 extbridge.jar 并授权启动守护进程...")
        val expectedSha = computeAssetJarSha256(appCtx)
        val bootstrapCmd = buildDaemonBootstrapScript(null, expectedSha)

        val shRes = runAdb(appCtx, 22000, "-s", target, "shell", bootstrapCmd)
        if (shRes.code != 0 || shRes.output == null || !shRes.output.contains("LAUNCH_DONE")) {
            postResult(listener, false, "远程 Shell 启动失败:\n${shRes.output}")
            return
        }

        if (isAutoCloseWifiAdbEnabled(appCtx)) {
            try {
                runAdb(appCtx, 2500, "-s", target, "shell", "settings put global adb_wifi_enabled 0")
            } catch (_: Throwable) {}
        }

        awaitDaemonBinderReady(appCtx, false, listener, "无线调试激活成功，ExtBridge 服务已就绪！")
    }

    @JvmStatic
    fun isAutoCloseWifiAdbEnabled(ctx: Context?): Boolean {
        if (ctx == null) return false
        val sp = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_AUTO_CLOSE_WIFI_ADB, false)
    }

    @JvmStatic
    fun setAutoCloseWifiAdbEnabled(ctx: Context?, enabled: Boolean) {
        if (ctx == null) return
        val sp = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_AUTO_CLOSE_WIFI_ADB, enabled).apply()
    }

    @JvmStatic
    fun isAutoRootEnabled(ctx: Context?): Boolean {
        if (ctx == null) return false
        val sp = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_AUTO_ROOT, false)
    }

    @JvmStatic
    fun setAutoRootEnabled(ctx: Context?, enabled: Boolean) {
        if (ctx == null) return
        val sp = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_AUTO_ROOT, enabled).apply()
    }

    @JvmStatic
    fun hasSuBinaryHint(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/vendor/bin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )
        for (p in paths) {
            try {
                if (File(p).exists()) return true
            } catch (_: Throwable) {}
        }
        val envPath = System.getenv("PATH")
        if (envPath != null) {
            for (dir in envPath.split(':')) {
                if (dir.isEmpty()) continue
                try {
                    if (File(dir, "su").exists()) return true
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    private fun computeAssetJarSha256(ctx: Context?): String {
        if (ctx == null) return ""
        return try {
            ctx.assets.open("extbridge.jar").use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } > 0) {
                    md.update(buf, 0, n)
                }
                val dig = md.digest()
                val hex = StringBuilder(64)
                for (b in dig) {
                    hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
                }
                hex.toString()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun rootBootstrapAsync(ctx: Context, markAutoRoot: Boolean, listener: ProgressListener?) {
        val appCtx = ctx.applicationContext
        sExec.execute {
            postStep(listener, "正在请求 Root (su) 权限...")
            val staged = stageBridgeJarWithSha256(appCtx)
            if (staged == null || staged.first.isEmpty() || staged.second.isEmpty()) {
                postResult(listener, false, "无法从 APK 释放并校验 extbridge.jar")
                return@execute
            }
            val qStage = staged.first.replace("'", "")
            val expSha = staged.second.lowercase(Locale.US)
            val rnd = ByteArray(8)
            SecureRandom().nextBytes(rnd)
            val nonce = StringBuilder(16)
            for (b in rnd) {
                nonce.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
            }
            // Literal cryptographic-nonce path avoids environment variable wiping across `su 2000`
            val rootTmp = "/data/local/tmp/extb_${nonce}.jar"
            val shellInner = buildShellSwapAndLaunchScript("'$rootTmp'", expSha)
            val qShellInner = shellInner.replace("'", "'\\''")
            // Root ONLY populates an O_CREAT|O_EXCL (`set -C`) temp jar verified against expSha
            // (with automatic `pm path` fallback when Magisk/KernelSU Mount Namespace is Isolated),
            // chowns it 2000:2000, and transitions to uid 2000 (`u:r:shell:s0`) before running app_process!
            val script = "UID_NOW=\$(id -u 2>/dev/null || echo -1); " +
                "if [ \"\$UID_NOW\" != \"0\" ]; then echo NO_ROOT:\$UID_NOW; exit 12; fi; " +
                "if ! (umask 022; set -C; : > '" + rootTmp + "') 2>/dev/null || [ -L '" + rootTmp + "' ]; then " +
                "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_MKTEMP; exit 21; fi; " +
                "if [ -f '" + qStage + "' ] && [ ! -L '" + qStage + "' ] && [ -s '" + qStage + "' ]; then " +
                "cat '" + qStage + "' > '" + rootTmp + "' 2>/dev/null; fi; " +
                "SRC_SHA=\$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); " +
                "if [ \"\$SRC_SHA\" != '" + expSha + "' ]; then " +
                "APK=\$(pm path com.extbridge.helper 2>/dev/null | head -n1 | cut -d: -f2); " +
                "if [ -n \"\$APK\" ] && [ -f \"\$APK\" ] && [ ! -L \"\$APK\" ]; then " +
                "unzip -p \"\$APK\" assets/extbridge.jar > '" + rootTmp + "' 2>/dev/null; fi; " +
                "SRC_SHA=\$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); " +
                "fi; " +
                "if [ \"\$SRC_SHA\" != '" + expSha + "' ] || ! unzip -tq '" + rootTmp + "' >/dev/null 2>&1; then " +
                "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_HASH:\$SRC_SHA; exit 21; fi; " +
                "chmod 644 '" + rootTmp + "' && chown -h 2000:2000 '" + rootTmp + "' " +
                "|| { rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_CHOWN; exit 21; }; " +
                "run_as_shell() { " +
                "su 2000 -c \"\$1\" && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
                "su shell -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
                "su 2000 /system/bin/sh -c \"\$1\" 2>/dev/null && return 0; rc=\$?; [ \"\$rc\" -ge 20 ] && [ \"\$rc\" -le 29 ] && return \"\$rc\"; " +
                "/system/bin/sh -c \"\$1\"; " +
                "}; " +
                "run_as_shell '" + qShellInner + "'; RC=\$?; " +
                "rm -f '" + rootTmp + "' 2>/dev/null; exit \$RC"

            val res = runSu(25000, script)
            val out = res.output?.trim() ?: ""
            if (res.code != 0 || !out.contains("LAUNCH_DONE")) {
                val hint = if (out.contains("NO_ROOT")) {
                    "未获取到 Root 权限（请在 Magisk / KernelSU / APatch 弹窗中允许授权，或改用无线调试/ADB激活）。"
                } else {
                    "Root 执行失败（设备可能未 Root 或未对 ExtBridge 授权 Root）：\n$out"
                }
                postResult(listener, false, hint)
                return@execute
            }

            if (markAutoRoot) {
                setAutoRootEnabled(appCtx, true)
            }
            awaitDaemonBinderReady(appCtx, true, listener, "Root 一键激活成功，ExtBridge 服务已就绪！")
        }
    }

    private fun stageBridgeJarWithSha256(ctx: Context): Pair<String, String>? {
        val outFile = File(ctx.filesDir, "extbridge_stage.jar")
        return try {
            ctx.assets.open("extbridge.jar").use { input ->
                FileOutputStream(outFile, false).use { out ->
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    outFile.setReadable(false, false)
                    outFile.setReadable(true, true)
                    outFile.setWritable(false, false)
                    outFile.setWritable(true, true)
                    val dig = md.digest()
                    val hex = StringBuilder(64)
                    for (b in dig) {
                        hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
                    }
                    Pair(outFile.absolutePath, hex.toString())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stageBridgeJar failed", t)
            null
        }
    }

    private fun buildShellSwapAndLaunchScript(tmpJarExpr: String, expectedSha256: String?): String {
        val cleanSha = expectedSha256?.trim()?.lowercase(Locale.US) ?: ""
        val shaGuard = if (SHA256_HEX_REGEX.matches(cleanSha)) {
            "ACT_SHA=\$(sha256sum " + tmpJarExpr + " 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$ACT_SHA\" ] && [ \"\$ACT_SHA\" != '" + cleanSha + "' ]; then " +
                "rm -f " + tmpJarExpr + "; echo ERR_JAR_SHA256; exit 21; fi; "
        } else {
            ""
        }
        val postSwapShaGuard = if (SHA256_HEX_REGEX.matches(cleanSha)) {
            "FINAL_SHA=\$(sha256sum /data/local/tmp/extbridge.jar 2>/dev/null | cut -d' ' -f1); " +
                "if [ -n \"\$FINAL_SHA\" ] && [ \"\$FINAL_SHA\" != '" + cleanSha + "' ]; then " +
                "echo ERR_FINAL_SHA256; exit 22; fi; "
        } else {
            ""
        }
        return "if [ ! -f " + tmpJarExpr + " ] || [ -L " + tmpJarExpr + " ] || [ ! -s " + tmpJarExpr + " ] " +
            "|| ! unzip -tq " + tmpJarExpr + " >/dev/null 2>&1; then " +
            "rm -f " + tmpJarExpr + "; echo ERR_STAGE_JAR; exit 21; fi; " +
            shaGuard +
            "chmod 644 " + tmpJarExpr + " || { rm -f " + tmpJarExpr + "; echo ERR_CHMOD_JAR; exit 21; }; " +
            "(CLASSPATH=" + tmpJarExpr + " /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true); " +
            "if pgrep -f '^(/.*/)?app_process.*ExtBridge' >/dev/null 2>&1; then " +
            "(pkill -f '^(/.*/)?app_process.*ExtBridge' 2>/dev/null || true); " +
            "sleep 1.0; " +
            "(pkill -f -l KILL '^(/.*/)?app_process.*ExtBridge' 2>/dev/null || true); fi; " +
            "rm -f /data/local/tmp/extbridge.jar; " +
            "if ! mv -f " + tmpJarExpr + " /data/local/tmp/extbridge.jar || [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ]; then " +
            "rm -f " + tmpJarExpr + "; echo ERR_SWAP_JAR; exit 22; fi; " +
            postSwapShaGuard +
            "chown -h 2000:2000 /data/local/tmp/extbridge.jar 2>/dev/null; " +
            "LOG_DST=/dev/null; " +
            "rm -f /data/local/tmp/extbridge.log 2>/dev/null; " +
            "if (umask 022; set -C; : > /data/local/tmp/extbridge.log) 2>/dev/null && [ -f /data/local/tmp/extbridge.log ] && [ ! -L /data/local/tmp/extbridge.log ]; then " +
            "chown -h 2000:2000 /data/local/tmp/extbridge.log 2>/dev/null; LOG_DST=/data/local/tmp/extbridge.log; fi; " +
            "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; " +
            "appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null; " +
            "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; " +
            "appops set com.extbridge.helper 10021 allow 2>/dev/null; " +
            "appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; " +
            "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; " +
            "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; " +
            "CUR_CTX=\$(id -Z 2>/dev/null || echo ''); " +
            "case \"\$CUR_CTX\" in " +
            "*:shell:*) " +
            "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5 ;; " +
            "*) " +
            "(CLASSPATH=/data/local/tmp/extbridge.jar setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5; " +
            "if ! pgrep -f '^(/.*/)?app_process.*ExtBridge' >/dev/null 2>&1; then " +
            "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.5; fi ;; " +
            "esac; " +
            "if ! pgrep -f '^(/.*/)?app_process.*ExtBridge' >/dev/null 2>&1; then " +
            "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"\$LOG_DST\" 2>&1 &); sleep 0.6; fi; " +
            "if pgrep -f '^(/.*/)?app_process.*ExtBridge' >/dev/null 2>&1; then echo LAUNCH_DONE; else echo ERR_DAEMON_DIED; [ \"\$LOG_DST\" != \"/dev/null\" ] && cat \"\$LOG_DST\" 2>/dev/null; exit 24; fi"
    }

    @JvmStatic
    @JvmOverloads
    fun buildDaemonBootstrapScript(stagedJarPath: String?, expectedSha256: String? = ""): String {
        val extractLogic = if (!stagedJarPath.isNullOrEmpty()) {
            val qStage = stagedJarPath.replace("'", "")
            "if [ -f '" + qStage + "' ] && [ ! -L '" + qStage + "' ] && [ -s '" + qStage + "' ]; then cp '" + qStage + "' \"\$TMP_JAR\"; " +
                "else APK=\$(pm path com.extbridge.helper | head -n1 | cut -d: -f2); " +
                "[ -n \"\$APK\" ] && unzip -p \"\$APK\" assets/extbridge.jar > \"\$TMP_JAR\"; fi"
        } else {
            "APK=\$(pm path com.extbridge.helper | head -n1 | cut -d: -f2); " +
                "[ -n \"\$APK\" ] && unzip -p \"\$APK\" assets/extbridge.jar > \"\$TMP_JAR\""
        }
        // Fail-closed BEFORE stopping any running daemon or touching /data/local/tmp/extbridge.jar
        return "TMP_JAR=\$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null || echo /data/local/tmp/extbridge.jar.tmp.\$\$); " +
            "if [ -L \"\$TMP_JAR\" ]; then rm -f \"\$TMP_JAR\"; echo ERR_TMP_SYMLINK; exit 21; fi; " +
            "if ! ( " + extractLogic + " ); then rm -f \"\$TMP_JAR\"; echo ERR_STAGE_JAR; exit 21; fi; " +
            buildShellSwapAndLaunchScript("\"\$TMP_JAR\"", expectedSha256)
    }

    private fun awaitDaemonBinderReady(
        appCtx: Context,
        fromRoot: Boolean,
        listener: ProgressListener?,
        successMsg: String,
    ) {
        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...")
        val deadline = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx)
            if (BridgeClient.getStatus().connected) {
                cancelPairingNotification(appCtx)
                postResult(listener, true, successMsg)
                return
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                break
            }
        }
        var tailDiag = ""
        if (fromRoot) {
            val diag = runSu(3000, "tail -n 8 /data/local/tmp/extbridge.log 2>/dev/null")
            val trimmed = diag.output?.trim()
            if (!trimmed.isNullOrEmpty()) {
                tailDiag = "\n日志摘要:\n$trimmed"
            }
        }
        postResult(
            listener,
            false,
            "守护进程命令已下发，但暂未收到 Binder 握手，请稍等几秒或查看 /data/local/tmp/extbridge.log$tailDiag",
        )
    }

    private fun runSu(timeoutMs: Long, shellCmd: String): CmdRes {
        var p: Process? = null
        return try {
            val proc = ProcessBuilder("su", "-c", shellCmd).redirectErrorStream(true).start()
            p = proc
            try { proc.outputStream.close() } catch (_: Throwable) {}
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (sb.length < 8192) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(line)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            reader.start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                return CmdRes(-1, "Root 命令执行超时 (${timeoutMs}ms)\n$sb")
            }
            reader.join(1000)
            CmdRes(proc.exitValue(), sb.toString())
        } catch (t: Throwable) {
            if (p != null) try { p.destroyForcibly() } catch (_: Throwable) {}
            CmdRes(-1, "未找到 su 或无 Root 权限: ${t.message}")
        }
    }

    private fun runAdb(ctx: Context, timeoutMs: Long, vararg args: String): CmdRes {
        var p: Process? = null
        return try {
            val adbBin = getAdbFile(ctx)
            val homeDir = File(ctx.filesDir, "adb_home")
            val dotAndroid = File(homeDir, ".android")
            val tmpDir = File(ctx.cacheDir, "adb_tmp")
            if (!dotAndroid.exists()) dotAndroid.mkdirs()
            if (!tmpDir.exists()) tmpDir.mkdirs()

            val cmd = ArrayList<String>(args.size + 1).apply {
                add(adbBin.absolutePath)
                addAll(args)
            }

            val pb = ProcessBuilder(cmd).apply {
                directory(homeDir)
                redirectErrorStream(true)
            }
            val sockFile = getPrivateAdbSocketFile(ctx)
            val env = HashMap<String, String>(pb.environment())
            env["HOME"] = homeDir.absolutePath
            env["ANDROID_USER_HOME"] = dotAndroid.absolutePath
            env["ANDROID_SDK_HOME"] = homeDir.absolutePath
            env["TMPDIR"] = tmpDir.absolutePath
            env.remove("ANDROID_ADB_SERVER_PORT")
            env["ADB_SERVER_SOCKET"] = "localfilesystem:${sockFile.absolutePath}"
            env["ADB_MDNS"] = "0"
            env["ADB_MDNS_OPENSCREEN"] = "0"
            pb.environment().clear()
            pb.environment().putAll(env)

            val proc = pb.start()
            p = proc
            try { proc.outputStream.close() } catch (_: Throwable) {}
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (sb.length < 8192) {
                                if (sb.isNotEmpty()) sb.append('\n')
                                sb.append(line)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            reader.start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(500)
                return CmdRes(-1, "命令执行超时 (${timeoutMs}ms)\n$sb")
            }
            reader.join(1000)
            CmdRes(proc.exitValue(), sb.toString())
        } catch (t: Throwable) {
            if (p != null) try { p.destroyForcibly() } catch (_: Throwable) {}
            CmdRes(-1, "执行出错: ${t.message}")
        }
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun showPairingNotification(ctx: Context, statusText: String?) {
        sPairNotifShowing = true
        if (sNsd == null) {
            startDiscovery(ctx.applicationContext, sDiscListener)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                PAIR_CHANNEL_ID, "无线调试快速配对", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "在系统配对码弹窗上方直接输入6位配对码完成激活"
            }
            nm.createNotificationChannel(ch)
        }

        val remoteInput = RemoteInput.Builder(KEY_PAIR_INPUT)
            .setLabel("输入6位配对码（或 配对码:端口）")
            .build()
        val replyIntent = Intent(ctx, AdbPairingReceiver::class.java).apply {
            action = ACTION_PAIR_REPLY
        }
        val mutFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(
            ctx, 2001, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutFlag,
        )
        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_send, "输入配对码", replyPi,
        ).addRemoteInput(remoteInput).build()

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPi = PendingIntent.getActivity(
            ctx, 2002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val ds = getDiscoveryState()
        val body = if (!statusText.isNullOrEmpty()) {
            statusText
        } else if (ds.pairingPort > 0) {
            "已发现配对端口 ${ds.pairingPort}，下拉后点「输入配对码」填6位数字即可"
        } else {
            "打开系统「使用配对码配对设备」后，点下方「输入配对码」直接输入（格式: 配对码 或 配对码:端口）"
        }

        val nb = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, PAIR_CHANNEL_ID)
        } else {
            Notification.Builder(ctx)
        }
        nb.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ExtBridge 无线调试配对助手")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi)
            .addAction(replyAction)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        nm.notify(PAIR_NOTIF_ID, nb.build())
    }

    @JvmStatic
    fun cancelPairingNotification(ctx: Context?) {
        sPairNotifShowing = false
        val nm = ctx?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(PAIR_NOTIF_ID)
    }

    private fun postStep(l: ProgressListener?, msg: String?) {
        if (l != null) sMain.post { l.onStep(msg) }
    }

    private fun postResult(l: ProgressListener?, ok: Boolean, msg: String?) {
        if (l != null) sMain.post { l.onResult(ok, msg) }
    }

    private fun parseIntSafe(s: String?, def: Int): Int =
        s?.trim()?.toIntOrNull() ?: def
}
