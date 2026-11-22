package com.extbridge.helper.protocol

import android.os.Build
import java.util.Locale
import kotlin.math.roundToInt

enum class ConnectionKind {
    DISCONNECTED,
    REMOTE_USB,
    REMOTE_WIFI_ADB,
    REMOTE_WIFI,
    LOCAL_DEX,
    PHYSICAL_MIRROR,
    DEX_ACTIVE,
    WIFI_LISTENING,
    STANDBY,
}

enum class ConnectionTone {
    ERROR,
    SUCCESS,
    PRIMARY,
    ACCENT,
}

data class ConnectionSummary(
    val kind: ConnectionKind,
    val tone: ConnectionTone,
    val badgeLabel: String,
    val headline: String,
    val detail: String,
    val shortSubtitle: String,
    val notificationTitle: String,
    val notificationBody: String,
    val toggleActionLabel: String,
)

/**
 * Pure JVM-testable user-facing label formatters and single-source connection status classifier.
 */
object BridgeLabels {

    fun sinkTypeLabel(sinkType: Int): String = when (sinkType) {
        BridgeProtocol.SinkType.PHYSICAL -> "物理外接显示器"
        BridgeProtocol.SinkType.REMOTE_USB -> "Mac 有线大屏 (USB)"
        BridgeProtocol.SinkType.REMOTE_WIFI_ADB -> "Mac 无线 ADB 大屏"
        BridgeProtocol.SinkType.REMOTE_WIFI -> "Mac 无线 Wi-Fi 大屏"
        BridgeProtocol.SinkType.LOCAL -> "本机 DeX 桌面"
        else -> "未连接输出"
    }

    fun layoutModeLabel(mode: String?): String = when (mode?.uppercase(Locale.ROOT)) {
        BridgeProtocol.LayoutMode.ADAPTIVE_SINGLE -> "居中宽幅"
        BridgeProtocol.LayoutMode.FREEFORM -> "自由多窗口"
        BridgeProtocol.LayoutMode.SPLIT_2_H -> "左右 2 分屏"
        BridgeProtocol.LayoutMode.SPLIT_2_V -> "上下 2 分屏"
        BridgeProtocol.LayoutMode.SPLIT_3_MASTER -> "1+2 三分屏"
        BridgeProtocol.LayoutMode.SPLIT_3_COLS -> "三列分屏"
        BridgeProtocol.LayoutMode.GRID_2X2 -> "2×2 四宫格"
        BridgeProtocol.LayoutMode.FULLSCREEN -> "单应用全屏"
        else -> "默认桌面"
    }

    fun mirrorModeLabel(mode: Int): String =
        if (mode == BridgeProtocol.MirrorMode.MIRROR) "原生镜像" else "DeX 扩展桌面"

    fun mirrorSideModeLabel(sideMode: Int): String = when (sideMode) {
        BridgeProtocol.MirrorSideMode.OFF -> "纯黑居中"
        BridgeProtocol.MirrorSideMode.ALIGN_RIGHT -> "靠右/靠下 · 融合大边栏"
        BridgeProtocol.MirrorSideMode.CENTER_DUAL -> "主屏居中 · 双信息条"
        else -> "靠左/靠上 · 融合大边栏"
    }

    fun rotationLabel(rot: Int): String = when (((rot % 4) + 4) % 4) {
        1 -> "90° 竖屏"
        2 -> "180° 倒置"
        3 -> "270° 竖屏"
        else -> "0° 横屏"
    }

    fun appModeLabel(mode: Int): String = when (mode) {
        BridgeProtocol.AppMode.LAND_FULL -> "强制横屏全屏"
        BridgeProtocol.AppMode.PORT_COMPAT -> "竖屏居中兼容"
        else -> "跟随系统默认"
    }

    fun appModeBadge(mode: Int): String = when (mode) {
        BridgeProtocol.AppMode.LAND_FULL -> "横屏全屏"
        BridgeProtocol.AppMode.PORT_COMPAT -> "竖屏兼容"
        else -> ""
    }

    fun injectStatusLabel(injectStatus: Int): String = when (injectStatus) {
        BridgeProtocol.InputBackend.INJECT_STATUS_UHID -> "UHID 硬件外设注入已激活"
        BridgeProtocol.InputBackend.INJECT_STATUS_BLOCKED -> "被系统拦截 (-1)"
        else -> "系统事件注入正常"
    }

    fun inputBackendLabel(mode: Int): String = when (mode) {
        BridgeProtocol.InputBackend.UHID -> "强制 UHID 虚拟键鼠"
        BridgeProtocol.InputBackend.INJECT_EVENTS -> "仅系统注入"
        else -> "自动 (优先系统/UHID 兜底)"
    }

    fun classifyWidthPresetLabel(widthDp: Int, isFullscreen: Boolean): String {
        if (isFullscreen) return "全屏"
        return when {
            widthDp <= 520 -> "${widthDp}dp · 手机竖屏"
            widthDp <= 780 -> "${widthDp}dp · 折叠屏"
            else -> "${widthDp}dp · 平板双栏"
        }
    }

    fun formatShortFingerprint(fp: String?): String {
        val clean = fp?.replace(":", "")?.replace("-", "")?.trim()?.uppercase(Locale.ROOT) ?: ""
        if (clean.isEmpty()) return ""
        return clean.take(16).chunked(4).joinToString("-")
    }

    fun formatBitrateMbps(bitrateKbps: Int): String {
        if (bitrateKbps <= 0) return "--"
        return String.format(Locale.US, "%.1f Mbps", bitrateKbps / 1000.0)
    }

    fun formatStreamFps(fpsX100: Int): String {
        if (fpsX100 <= 0) return "--"
        return if (fpsX100 % 100 == 0) {
            "${fpsX100 / 100} fps"
        } else {
            String.format(Locale.US, "%.1f fps", fpsX100 / 100.0)
        }
    }

    fun classifyOemInjectHint(brandAndManufacturer: String): String {
        val brand = brandAndManufacturer.lowercase(Locale.ROOT)
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "Xiaomi HyperOS 请前往「开发者选项」开启「USB 调试（安全设置）」以允许外接屏模拟点击与键鼠注入。"
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "ColorOS 请前往「开发者选项」底部开启「禁止权限监控」以允许外接屏输入注入。"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "OriginOS 请前往「开发者选项」开启「USB 调试（安全设置）/ 模拟点击」权限。"
            else ->
                "请前往系统「开发者选项」开启允许模拟点击 / USB 安全调试设置。"
        }
    }

    fun getOemInjectHint(): String =
        classifyOemInjectHint("${Build.MANUFACTURER} ${Build.BRAND}")

    /**
     * Single source of truth classifying daemon connection state for MainActivity Hero/Header,
     * Remote Stream badges, and BridgeService foreground notification copy.
     */
    fun classifyConnectionSummary(
        status: DaemonStatus,
        localLanIp: String = "",
        usingA11yOverlay: Boolean = false,
    ): ConnectionSummary {
        if (!status.connected) {
            return ConnectionSummary(
                kind = ConnectionKind.DISCONNECTED,
                tone = ConnectionTone.ERROR,
                badgeLabel = "未激活",
                headline = "守护进程尚未运行",
                detail = "支持 Root 一键拉起、本机无线调试自激活或电脑单行 ADB 命令启动。",
                shortSubtitle = "核心服务未激活",
                notificationTitle = "ExtBridge 未连接后台服务",
                notificationBody = "点击打开控制台激活，或点下方「停止并退出」关闭常驻",
                toggleActionLabel = "⇄ 切换键鼠",
            )
        }

        if (status.isRemoteSink) {
            val peerHero = status.remotePeerName.ifBlank { "Mac 客户端" }
            val peerShort = status.remotePeerName.ifBlank { "Mac" }
            val fpsStr = formatStreamFps(status.streamFpsX100)
            val brStr = formatBitrateMbps(status.streamBitrateKbps)
            val modeLbl = mirrorModeLabel(status.mirrorMode)
            val channel = sinkTypeLabel(status.sinkType)
            val (kind, tone, badge, transportDesc, shortSub) = when (status.sinkType) {
                BridgeProtocol.SinkType.REMOTE_USB -> RemoteDisplayMeta(
                    ConnectionKind.REMOTE_USB,
                    ConnectionTone.SUCCESS,
                    "USB 有线",
                    "USB 有线直连",
                    "Mac 有线大屏 (USB · $peerShort)",
                )
                BridgeProtocol.SinkType.REMOTE_WIFI_ADB -> RemoteDisplayMeta(
                    ConnectionKind.REMOTE_WIFI_ADB,
                    ConnectionTone.PRIMARY,
                    "无线 ADB",
                    "无线 ADB 调试隧道",
                    "Mac 无线 ADB 大屏 ($peerShort)",
                )
                else -> RemoteDisplayMeta(
                    ConnectionKind.REMOTE_WIFI,
                    ConnectionTone.ACCENT,
                    "Wi-Fi 直连",
                    "Wi-Fi TLS 直连",
                    "Mac 无线 Wi-Fi 大屏 ($peerShort)",
                )
            }
            return ConnectionSummary(
                kind = kind,
                tone = tone,
                badgeLabel = badge,
                headline = "$channel · $peerHero",
                detail = "$transportDesc · $modeLbl ${status.virtW}×${status.virtH} · $fpsStr · $brStr",
                shortSubtitle = shortSub,
                notificationTitle = "ExtBridge $channel · $peerShort",
                notificationBody = "$peerShort · $fpsStr · $brStr | $modeLbl ${status.virtW}×${status.virtH}",
                toggleActionLabel = "断开 Mac",
            )
        }

        if (status.isLocalDexActive) {
            return ConnectionSummary(
                kind = ConnectionKind.LOCAL_DEX,
                tone = ConnectionTone.SUCCESS,
                badgeLabel = "本机 DeX",
                headline = "本机 DeX 桌面 · Display #${status.vdId}",
                detail = "免外接屏运行 · ${status.virtW}×${status.virtH} @ ${status.virtDpi}dpi · ${status.virtFps.roundToInt()}Hz",
                shortSubtitle = "本机 DeX ${status.virtW}×${status.virtH}",
                notificationTitle = "ExtBridge 本机 DeX 运行中 · ${status.virtW}×${status.virtH}",
                notificationBody = "无需外接显示器 · 点击返回本机 DeX 桌面，或点右侧按钮退出",
                toggleActionLabel = "退出本机 DeX",
            )
        }

        if (status.mirrorMode == BridgeProtocol.MirrorMode.MIRROR && status.extId > 0) {
            return ConnectionSummary(
                kind = ConnectionKind.PHYSICAL_MIRROR,
                tone = ConnectionTone.ACCENT,
                badgeLabel = "镜像投屏",
                headline = "原生镜像 · Display #${status.extId}",
                detail = "外接物理输出 ${status.physW}×${status.physH} @ ${status.physFps.roundToInt()}Hz · ${mirrorSideModeLabel(status.mirrorSideMode)}",
                shortSubtitle = "原生镜像 Display #${status.extId}",
                notificationTitle = "ExtBridge 原生镜像模式 · 物理 Display #${status.extId}",
                notificationBody = "外接屏正在同步手机/平板主屏 | 点击右侧「切回 DeX」恢复独立大屏桌面",
                toggleActionLabel = if (status.inputToExt == 1) "⇄ 切到本机" else "⇄ 切回外接",
            )
        }

        if (status.vdId > 0) {
            val target = if (status.inputToExt == 1) "外接扩展屏" else "平板本机"
            val modeTag = if (usingA11yOverlay) "无障碍B" else "兼容A"
            return ConnectionSummary(
                kind = ConnectionKind.DEX_ACTIVE,
                tone = ConnectionTone.SUCCESS,
                badgeLabel = "扩展在线",
                headline = "DeX 扩展桌面 · Display #${status.vdId}",
                detail = "渲染 ${status.virtW}×${status.virtH} (${rotationLabel(status.virtRot)}) @ ${status.virtDpi}dpi · 物理 ${status.physW}×${status.physH}@${status.physFps.roundToInt()}Hz",
                shortSubtitle = "扩展桌面 ${status.virtW}×${status.virtH}",
                notificationTitle = "ExtBridge 已挂载($modeTag) · ${status.virtW}×${status.virtH} @${status.virtFps.roundToInt()}Hz",
                notificationBody = "键鼠控制：【$target】 | 点通知本体进控制台，右侧按钮一键回桌面",
                toggleActionLabel = if (status.inputToExt == 1) "键鼠切回本机" else "键鼠切至扩展屏",
            )
        }

        if (status.isWifiServerEnabled) {
            val port = if (status.wifiServerPort > 0) status.wifiServerPort else BridgeProtocol.RemoteStream.DEFAULT_WIFI_PORT
            val ipPrefix = localLanIp.ifBlank { "LAN" }
            return ConnectionSummary(
                kind = ConnectionKind.WIFI_LISTENING,
                tone = ConnectionTone.PRIMARY,
                badgeLabel = "无线待机",
                headline = "核心服务在线 · 无线大屏监听中",
                detail = "局域网 $ipPrefix:$port · 亦可接入 USB / Type-C 显示器自动点亮",
                shortSubtitle = "无线大屏监听中 :$port",
                notificationTitle = "ExtBridge 无线大屏监听中 · :$port",
                notificationBody = "局域网 mDNS 广播就绪，亦支持接入 Type-C / USB 连接 Mac",
                toggleActionLabel = if (status.inputToExt == 1) "⇄ 切到平板" else "⇄ 切回扩展屏",
            )
        }

        return ConnectionSummary(
            kind = ConnectionKind.STANDBY,
            tone = ConnectionTone.PRIMARY,
            badgeLabel = "待机就绪",
            headline = "核心服务在线 · 待接入显示器",
            detail = "已存预设 ${status.virtW}×${status.virtH} @ ${status.virtDpi}dpi · 支持 Type-C 外接屏或 Mac 有线/无线大屏",
            shortSubtitle = "服务就绪，待接入显示器",
            notificationTitle = "ExtBridge 待机中 · 等待外接屏或 Mac 连接",
            notificationBody = "预设 ${status.virtW}×${status.virtH} (${status.virtDpi}dpi) | 不用时可点「停止并退出」",
            toggleActionLabel = if (status.inputToExt == 1) "⇄ 切到平板" else "⇄ 切回扩展屏",
        )
    }

    private data class RemoteDisplayMeta(
        val kind: ConnectionKind,
        val tone: ConnectionTone,
        val badge: String,
        val transportDesc: String,
        val shortSub: String,
    )
}
