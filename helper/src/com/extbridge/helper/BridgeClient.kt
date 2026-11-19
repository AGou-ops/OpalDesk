package com.extbridge.helper

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BridgeClient {
    const val BINDER_DESC = "com.extbridge.daemon.IBridgeControl"

    const val SYS_WIFI = 1
    const val SYS_BT = 2
    const val SYS_AIRPLANE = 3
    const val SYS_POWER_SAVE = 4
    const val SYS_OPEN_SETTINGS = 5
    const val SYS_SCREEN_DIM = 6

    const val SINK_NONE = 0
    const val SINK_PHYSICAL = 1
    const val SINK_REMOTE_USB = 2
    const val SINK_REMOTE_WIFI = 3
    const val SINK_REMOTE_WIFI_ADB = 4

    const val WIN_MODE_AUTO = 0
    const val WIN_MODE_FULLSCREEN = 1
    const val WIN_MODE_ADAPTIVE_SINGLE = 2
    const val WIN_MODE_FREEFORM = 3

    const val WIN_ACTION_FOCUS = 1
    const val WIN_ACTION_MINIMIZE = 2
    const val WIN_ACTION_CLOSE = 3
    const val WIN_ACTION_RELOAD_DPI = 4

    const val INPUT_BACKEND_AUTO = 0
    const val INPUT_BACKEND_INJECT_EVENTS = 1
    const val INPUT_BACKEND_UHID = 2

    const val INJECT_STATUS_BLOCKED = -1
    const val INJECT_STATUS_SYSTEM = 1
    const val INJECT_STATUS_UHID = 2

    const val LAYOUT_NONE = "NONE"
    const val LAYOUT_FULLSCREEN = "FULLSCREEN"
    const val LAYOUT_ADAPTIVE_SINGLE = "ADAPTIVE_SINGLE"
    const val LAYOUT_FREEFORM = "FREEFORM"
    const val LAYOUT_SPLIT_2_H = "SPLIT_2_H"
    const val LAYOUT_SPLIT_2_V = "SPLIT_2_V"
    const val LAYOUT_SPLIT_3_MASTER = "SPLIT_3_MASTER"
    const val LAYOUT_SPLIT_3_COLS = "SPLIT_3_COLS"
    const val LAYOUT_GRID_2X2 = "GRID_2X2"
    const val LAYOUT_EXIT = "EXIT"

    const val DEFAULT_SINGLE_WIDTH_DP = 460
    const val MIN_SINGLE_WIDTH_DP = 320
    const val MAX_SINGLE_WIDTH_DP = 1200

    const val CODEC_PREF_AUTO_HEVC = 0
    const val CODEC_PREF_H264 = 1
    const val DEFAULT_WIFI_REMOTE_PORT = 19876

    const val MIRROR_MODE_DEX = 0
    const val MIRROR_MODE_MIRROR = 1

    const val APP_MODE_DEFAULT = 0
    const val APP_MODE_LAND_FULL = 1
    const val APP_MODE_PORT_COMPAT = 2
    const val APP_MODE_FREEFORM = 3

    const val SPLIT_ASSIGN_LEFT_TOP = 1
    const val SPLIT_ASSIGN_RIGHT_BOTTOM = 2
    const val SPLIT_TOGGLE_QUICK = 3
    const val SPLIT_SET_RATIO = 4
    const val SPLIT_SWAP = 5
    const val SPLIT_EXIT = 6

    const val MIRROR_SIDE_OFF = 0
    const val MIRROR_SIDE_ALIGN_LEFT = 1
    const val MIRROR_SIDE_ALIGN_RIGHT = 2
    const val MIRROR_SIDE_CENTER_DUAL = 3

    const val DEFAULT_MIRROR_PRESETS = "clock,battery,wifi,bt,audio,media,screendim"

    @JvmField
    val MIRROR_THEME_KEYS = arrayOf(
        "night", "oled", "frost", "cyber", "emerald", "amber", "custom",
    )

    @JvmField
    val MIRROR_THEME_TITLES = arrayOf(
        "极夜深蓝", "OLED纯黑", "晨雾浅白", "赛博紫青", "翡翠墨绿", "暖暮琥珀", "自定义颜色",
    )

    @JvmField
    val MIRROR_SPEED_LABELS = arrayOf(
        "0.9x 精细", "1.4x 标准", "1.9x 流畅", "2.6x 极速", "3.4x 飞快",
    )

    @JvmField
    val MIRROR_SPEED_MULTS = floatArrayOf(0.9f, 1.4f, 1.9f, 2.6f, 3.4f)

    @JvmField
    val MIRROR_WIDGET_RATIO_LABELS = arrayOf(
        "自动原比例", "方形 1:1", "竖屏 3:4", "宽屏 16:9", "铺满槽位",
    )

    @JvmField
    val MIRROR_PRESET_KEYS = arrayOf(
        "clock", "battery", "wifi", "bt", "audio", "media", "calendar",
        "perf", "netspeed", "storage", "pomodoro", "memo", "quickctrl", "screendim",
    )

    @JvmField
    val MIRROR_PRESET_TITLES = arrayOf(
        "大数字时钟",
        "电池温压详情",
        "Wi-Fi 与信号",
        "蓝牙与外设",
        "音频通道与音量",
        "媒体播放与切歌",
        "日历与近期日程",
        "内存与系统负载",
        "实时网速监控",
        "存储与亮度信息",
        "专注番茄钟",
        "快捷桌面便签",
        "镜像快捷控制台",
        "主屏最低亮度",
    )

    @JvmField
    val MIRROR_PRESET_DESCS = arrayOf(
        "超大时间 + 日期星期（高对比醒目字号）",
        "电量百分比、快充功耗、电池温度与电压",
        "当前 Wi-Fi 名称、连接速率与信号强度",
        "蓝牙开关状态与已连接耳机/键鼠名称",
        "实时输出通道 + 触控音量加减与静音",
        "当前媒体曲目/App + 上一曲/播放暂停/下一曲",
        "读取系统日历近两日日程提醒",
        "实时可用 RAM 内存占比条 + 系统运行负载",
        "每秒实时下行 ↓ / 上行 ↑ 网络吞吐速率",
        "机身存储可用空间 GB + 系统当前亮度状态",
        "25 分钟倒计时专注钟，支持边栏直接启停/重置",
        "自定义文字待办/会议备忘，随时在控制台修改",
        "边栏直达快捷按钮：切回 DeX / 切换对齐方位 / 切换键鼠 / 最低亮度",
        "一键把平板/手机主屏亮度压至硬件最低（省电护眼），再点恢复",
    )

    class TaskEntry {
        @JvmField var taskId: Int = 0
        @JvmField var pkg: String = ""
        @JvmField var flags: Int = 0

        fun onExt(): Boolean = (flags and 1) != 0
        fun isRunning(): Boolean = (flags and 2) != 0
        fun isTopExt(): Boolean = (flags and 4) != 0
        fun isFreeform(): Boolean = (flags and 8) != 0
        fun isInSplit(): Boolean = (flags and 16) != 0
    }

    class SysStatus {
        @JvmField var valid: Boolean = false
        @JvmField var wifiOn: Boolean = false
        @JvmField var wifiSsid: String = ""
        @JvmField var btOn: Boolean = false
        @JvmField var btName: String = ""
        @JvmField var airplaneOn: Boolean = false
        @JvmField var powerSaveOn: Boolean = false
        @JvmField var screenDimmed: Boolean = false
    }

    class Status {
        @JvmField var connected: Boolean = false
        @JvmField var extId: Int = -1
        @JvmField var vdId: Int = -1
        @JvmField var physW: Int = 0
        @JvmField var physH: Int = 0
        @JvmField var physFps: Float = 60f
        @JvmField var virtW: Int = 2560
        @JvmField var virtH: Int = 1440
        @JvmField var virtDpi: Int = 240
        @JvmField var virtFps: Float = 60f
        @JvmField var inputToExt: Int = 1
        @JvmField var cursorX: Int = 1280
        @JvmField var cursorY: Int = 720
        @JvmField var extInputCnt: Int = 0
        @JvmField var modesRaw: String = ""
        @JvmField var renderMode: String = "NONE"
        @JvmField var mirrorMode: Int = 0
        @JvmField var fullscreenPolicy: Int = 1
        @JvmField var virtRot: Int = 0
        @JvmField var injectStatus: Int = 1
        @JvmField var splitActive: Int = 0
        @JvmField var splitRatioPct: Int = 50
        @JvmField var splitSlot0Pkg: String = ""
        @JvmField var splitSlot1Pkg: String = ""
        @JvmField var mirrorSideMode: Int = 1
        @JvmField var sideVdId: Int = -1
        @JvmField var d0W: Int = 0
        @JvmField var d0H: Int = 0
        @JvmField var mirX: Int = 0
        @JvmField var mirY: Int = 0
        @JvmField var mirW: Int = 0
        @JvmField var mirH: Int = 0

        // Remote / Wireless Streaming Extension Fields (Binder code 15 tail)
        @JvmField var sinkType: Int = SINK_NONE
        @JvmField var remotePeerName: String = ""
        @JvmField var streamBitrateKbps: Int = 0
        @JvmField var streamFpsX100: Int = 0
        @JvmField var wifiServerEnabled: Int = 0
        @JvmField var wifiServerPort: Int = 0
        @JvmField var wifiCertFingerprint: String = ""
        @JvmField var activePairingPin: String = ""

        // v4.1 Adaptive Window & Multi-Tiling Extension Fields (Binder code 15 tail)
        @JvmField var adaptiveSingleEnabled: Int = 1
        @JvmField var defaultSingleWidthDp: Int = DEFAULT_SINGLE_WIDTH_DP
        @JvmField var activeLayoutMode: String = LAYOUT_FULLSCREEN

        val isRemoteDisplayActive: Boolean
            get() = isRemoteDisplayActive(sinkType)

        val isRemoteSink: Boolean
            get() = isRemoteDisplayActive

        val isAdaptiveSingleEnabled: Boolean
            get() = adaptiveSingleEnabled != 0

        val isUhidInputActive: Boolean
            get() = injectStatus == INJECT_STATUS_UHID

        val isMultiTilingActive: Boolean
            get() = splitActive == 1 || isTilingTopology(activeLayoutMode)

        val isWifiServerEnabled: Boolean
            get() = wifiServerEnabled != 0

        val streamFps: Float
            get() = streamFpsX100 / 100f

        val shortCertFingerprint: String
            get() = formatShortFingerprint(wifiCertFingerprint)
    }

    data class ManagedWindowInfo(
        val taskId: Int,
        val pkg: String,
        val mode: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val focused: Boolean,
        val minimized: Boolean,
    ) {
        val widthPx: Int get() = max(0, right - left)
        val heightPx: Int get() = max(0, bottom - top)
        fun toRect(): Rect = Rect(left, top, right, bottom)
    }

    data class WindowStateSnapshot(
        val layoutMode: String = LAYOUT_FULLSCREEN,
        val adaptiveSingleEnabled: Boolean = true,
        val defaultSingleWidthDp: Int = DEFAULT_SINGLE_WIDTH_DP,
        val tilingTopology: String = LAYOUT_NONE,
        val ratios: List<Int> = emptyList(),
        val windows: List<ManagedWindowInfo> = emptyList(),
    )

    data class WifiRemoteConfigResult(
        val ok: Boolean = false,
        val enabled: Boolean = false,
        val port: Int = 0,
        val certFingerprint: String = "",
        val pin: String = "",
    )

    data class TrustedMacClient(
        val clientId: String,
        val clientName: String,
    )

    data class EdidMode(
        val id: Int,
        val width: Int,
        val height: Int,
        val fps: Float,
    )

    class MirrorTelemetry {
        @JvmField var valid: Boolean = false
        @JvmField var audioRoute: String = "平板外放"
        @JvmField var audioDetail: String = ""
        @JvmField var mediaPkg: String = ""
        @JvmField var mediaState: Int = 0 // 0=none, 1=stopped, 2=paused, 3=playing
        @JvmField var mediaTitle: String = ""
        @JvmField var mediaSubtitle: String = ""
        @JvmField var calendarText: String = ""
        @JvmField var battDetail: String = ""
        @JvmField var wifiDetail: String = ""
    }

    class ApplyResult {
        @JvmField var ok: Boolean = false
        @JvmField var physResult: Int = 0 // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked physical mode
        @JvmField var physW: Int = 0
        @JvmField var physH: Int = 0
        @JvmField var physFps: Float = 0f
    }

    class SplitResult {
        @JvmField var ok: Boolean = false
        @JvmField var splitActive: Boolean = false
        @JvmField var ratioPct: Int = 50
        @JvmField var slot0Pkg: String = ""
        @JvmField var slot1Pkg: String = ""
    }

    class TaskSnapshotData {
        @JvmField var bitmap: Bitmap? = null
        @JvmField var bounds: Rect? = null
        @JvmField var ownBitmap: Boolean = false
    }

    class MirrorTheme(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val bgColor: Int,
        @JvmField val headerBgColor: Int,
        @JvmField val cardBgColor: Int,
        @JvmField val cardStrokeColor: Int,
        @JvmField val widgetBgColor: Int,
        @JvmField val pillBgColor: Int,
        @JvmField val accentBtnColor: Int,
        @JvmField val titleColor: Int,
        @JvmField val subColor: Int,
        @JvmField val accentColor: Int,
        @JvmField val lightMode: Boolean,
    )

    @Volatile
    private var sCachedWpBitmap: Bitmap? = null

    @Volatile
    private var sBinder: IBinder? = null

    @Volatile
    private var sLinkedBinder: IBinder? = null

    @Volatile
    private var sHwBufSnapshotBlocked = false

    private val sBinderListeners = CopyOnWriteArraySet<Runnable>()
    private val _statusFlow = MutableStateFlow(Status())

    @JvmStatic
    val statusFlow: StateFlow<Status> = _statusFlow.asStateFlow()

    @JvmStatic
    fun setOnBinderReady(r: Runnable?) {
        if (r != null) sBinderListeners.add(r)
    }

    @JvmStatic
    fun removeOnBinderReady(r: Runnable?) {
        if (r != null) sBinderListeners.remove(r)
    }

    @Synchronized
    private fun linkBinderDeath(b: IBinder?) {
        if (b == null || b === sLinkedBinder) return
        sLinkedBinder = b
        try {
            b.linkToDeath({
                if (sBinder === b) {
                    sBinder = null
                    _statusFlow.value = Status()
                }
                if (sLinkedBinder === b) sLinkedBinder = null
            }, 0)
        } catch (_: Throwable) {}
    }

    private fun obtainInterfaceParcel(): Parcel {
        val p = Parcel.obtain()
        p.writeInterfaceToken(BINDER_DESC)
        return p
    }

    @JvmStatic
    fun setBinder(b: IBinder?) {
        if (b != null && b.pingBinder()) {
            try {
                if (BINDER_DESC != b.interfaceDescriptor) {
                    return
                }
            } catch (_: Throwable) {
                return
            }
            sBinder = b
            linkBinderDeath(b)
            for (r in sBinderListeners) {
                try { r.run() } catch (_: Throwable) {}
            }
        }
    }

    private fun sendCtlSocketCmdAsync(cmd: String) {
        Thread({
            var sock: LocalSocket? = null
            try {
                sock = LocalSocket()
                sock.connect(
                    LocalSocketAddress(
                        "com.extbridge.daemon.ctl",
                        LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                val peer = sock.peerCredentials
                if (peer == null || (peer.uid != 0 && peer.uid != 1000 && peer.uid != 2000)) {
                    return@Thread
                }
                sock.soTimeout = 2500
                sock.outputStream.write("$cmd\n".toByteArray(StandardCharsets.UTF_8))
                sock.outputStream.flush()
                sock.inputStream.read()
            } catch (_: Throwable) {
            } finally {
                try { sock?.close() } catch (_: Throwable) {}
            }
        }, "ExtCtlPing").start()
    }

    @JvmStatic
    fun requestBinder(ctx: Context?) {
        sendCtlSocketCmdAsync("NEED_BINDER")
        if (ctx == null) return
        try {
            var opts: Bundle? = null
            if (Build.VERSION.SDK_INT >= 34) {
                opts = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
            }
            val i1 = Intent("com.extbridge.daemon.NEED_BINDER").setPackage("android")
            if (opts != null) ctx.sendBroadcast(i1, null, opts) else ctx.sendBroadcast(i1)

            val i2 = Intent("com.extbridge.daemon.NEED_BINDER").setPackage("com.android.shell")
            if (opts != null) ctx.sendBroadcast(i2, null, opts) else ctx.sendBroadcast(i2)
        } catch (_: Throwable) {}
    }

    @JvmStatic
    fun getService(): IBinder? {
        val b = sBinder
        if (b != null) {
            if (b.pingBinder()) return b
            sBinder = null
            _statusFlow.value = Status()
        }
        return null
    }

    @JvmStatic
    fun getStatus(): Status {
        val st = Status()
        val b = getService()
        if (b == null) {
            _statusFlow.value = st
            return st
        }
        val data = obtainInterfaceParcel()
        val reply = Parcel.obtain()
        try {
            if (b.transact(15, data, reply, 0)) {
                st.connected = true
                st.extId = reply.readInt()
                st.vdId = reply.readInt()
                st.physW = reply.readInt()
                st.physH = reply.readInt()
                st.physFps = reply.readInt() / 100f
                st.virtW = reply.readInt()
                st.virtH = reply.readInt()
                st.virtDpi = reply.readInt()
                st.virtFps = reply.readInt() / 100f
                st.inputToExt = reply.readInt()
                st.cursorX = reply.readInt()
                st.cursorY = reply.readInt()
                st.extInputCnt = reply.readInt()
                st.modesRaw = reply.readString() ?: ""
                val rm = reply.readString()
                st.renderMode = if (!rm.isNullOrEmpty()) rm else "NONE"
                if (reply.dataAvail() >= 8) {
                    st.mirrorMode = reply.readInt()
                    st.fullscreenPolicy = reply.readInt()
                }
                if (reply.dataAvail() >= 4) {
                    st.virtRot = reply.readInt()
                }
                if (reply.dataAvail() >= 4) {
                    st.injectStatus = reply.readInt()
                }
                if (reply.dataAvail() >= 8) {
                    st.splitActive = reply.readInt()
                    st.splitRatioPct = reply.readInt()
                    st.splitSlot0Pkg = reply.readString() ?: ""
                    st.splitSlot1Pkg = reply.readString() ?: ""
                }
                if (reply.dataAvail() >= 32) {
                    st.mirrorSideMode = reply.readInt()
                    st.sideVdId = reply.readInt()
                    st.d0W = reply.readInt()
                    st.d0H = reply.readInt()
                    st.mirX = reply.readInt()
                    st.mirY = reply.readInt()
                    st.mirW = reply.readInt()
                    st.mirH = reply.readInt()
                }
                // Remote / Wireless Streaming Extension Fields (strictly aligned with daemon code 15)
                if (reply.dataAvail() >= 4) {
                    st.sinkType = reply.readInt()
                }
                if (reply.dataAvail() >= 4) {
                    st.remotePeerName = reply.readString() ?: ""
                }
                if (reply.dataAvail() >= 16) {
                    st.streamBitrateKbps = reply.readInt()
                    st.streamFpsX100 = reply.readInt()
                    st.wifiServerEnabled = reply.readInt()
                    st.wifiServerPort = reply.readInt()
                }
                if (reply.dataAvail() >= 4) {
                    st.wifiCertFingerprint = reply.readString() ?: ""
                }
                if (reply.dataAvail() >= 4) {
                    st.activePairingPin = reply.readString() ?: ""
                }
                // v4.1 Adaptive Window & Multi-Tiling Extension Fields (strictly aligned with daemon code 15)
                if (reply.dataAvail() >= 4) {
                    st.adaptiveSingleEnabled = reply.readInt()
                }
                if (reply.dataAvail() >= 4) {
                    val wDp = reply.readInt()
                    if (wDp in MIN_SINGLE_WIDTH_DP..MAX_SINGLE_WIDTH_DP) {
                        st.defaultSingleWidthDp = wDp
                    }
                }
                if (reply.dataAvail() >= 4) {
                    val lm = reply.readString()
                    if (!lm.isNullOrBlank()) {
                        st.activeLayoutMode = lm
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            data.recycle()
            reply.recycle()
        }
        _statusFlow.value = st
        return st
    }

    // --- Remote / Wireless Streaming Binder IPC (codes 45..49) ---

    @JvmStatic
    @JvmOverloads
    fun setWifiRemoteConfig(
        enable: Boolean,
        port: Int = 0,
        regeneratePin: Boolean = false,
    ): WifiRemoteConfigResult {
        val b = getService() ?: return WifiRemoteConfigResult()
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(if (enable) 1 else 0)
            d.writeInt(port)
            d.writeInt(if (regeneratePin) 1 else 0)
            if (b.transact(45, d, r, 0) && r.dataAvail() >= 8) {
                val en = r.readInt() != 0
                val p = r.readInt()
                val fp = if (r.dataAvail() >= 4) r.readString() ?: "" else ""
                val pin = if (r.dataAvail() >= 4) r.readString() ?: "" else ""
                return WifiRemoteConfigResult(
                    ok = true,
                    enabled = en,
                    port = p,
                    certFingerprint = fp,
                    pin = pin,
                )
            }
            return WifiRemoteConfigResult()
        } catch (_: Throwable) {
            return WifiRemoteConfigResult()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun listTrustedMacClients(): List<TrustedMacClient> {
        val b = getService() ?: return emptyList()
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            if (b.transact(46, d, r, 0) && r.dataAvail() >= 4) {
                return parseTrustedMacClients(r.readString())
            }
            return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun revokeTrustedMacClient(clientId: String): Boolean {
        val trimmed = clientId.trim()
        if (trimmed.isEmpty()) return false
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeString(trimmed)
            return b.transact(47, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            return false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun disconnectRemoteClient(): Boolean = simpleCall(48) == 1

    @JvmStatic
    fun setRemoteStreamPrefs(
        codecPref: Int,
        maxBitrateKbps: Int,
        audioEnabled: Boolean,
    ): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(codecPref.coerceIn(CODEC_PREF_AUTO_HEVC, CODEC_PREF_H264))
            d.writeInt(maxBitrateKbps.coerceIn(2000, 50000))
            d.writeInt(if (audioEnabled) 1 else 0)
            return b.transact(49, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            return false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    // --- v4.1 Adaptive Window, Multi-Tiling & Input Backend Binder IPC (codes 50..56) ---

    @JvmStatic
    fun getManagedWindows(): WindowStateSnapshot {
        val b = getService() ?: return WindowStateSnapshot()
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            if (b.transact(50, d, r, 0) && r.dataAvail() >= 4) {
                parseWindowStateSnapshot(r.readString())
            } else {
                WindowStateSnapshot()
            }
        } catch (_: Throwable) {
            WindowStateSnapshot()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun launchInWindowMode(
        pkg: String?,
        cls: String?,
        mode: Int = WIN_MODE_AUTO,
        bounds: Rect? = null,
    ): Boolean = launchInWindowMode(
        pkg = pkg,
        cls = cls,
        mode = mode,
        left = bounds?.left ?: 0,
        top = bounds?.top ?: 0,
        right = bounds?.right ?: 0,
        bottom = bounds?.bottom ?: 0,
    )

    @JvmStatic
    fun launchInWindowMode(
        pkg: String?,
        cls: String?,
        mode: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        if (pkg.isNullOrBlank()) return false
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeString(pkg)
            d.writeString(cls ?: "")
            d.writeInt(mode.coerceIn(WIN_MODE_AUTO, WIN_MODE_FREEFORM))
            d.writeInt(left)
            d.writeInt(top)
            d.writeInt(right)
            d.writeInt(bottom)
            b.transact(51, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun updateWindowBounds(
        taskId: Int,
        pkg: String?,
        mode: Int,
        bounds: Rect,
        rememberPref: Boolean,
    ): Boolean = updateWindowBounds(
        taskId = taskId,
        pkg = pkg,
        mode = mode,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        rememberPref = rememberPref,
    )

    @JvmStatic
    fun updateWindowBounds(
        taskId: Int,
        pkg: String?,
        mode: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rememberPref: Boolean,
    ): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            d.writeString(pkg ?: "")
            d.writeInt(mode.coerceIn(WIN_MODE_FULLSCREEN, WIN_MODE_FREEFORM))
            d.writeInt(left)
            d.writeInt(top)
            d.writeInt(right)
            d.writeInt(bottom)
            d.writeInt(if (rememberPref) 1 else 0)
            b.transact(52, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun setTilingLayout(
        topology: String,
        pkgs: List<String>,
        ratios: List<Int>,
    ): Boolean = setTilingLayout(
        topology = topology,
        pkgsCsv = pkgs.filter { it.isNotBlank() }.joinToString(","),
        ratiosCsv = ratios.joinToString(","),
    )

    @JvmStatic
    fun setTilingLayout(
        topology: String,
        pkgsCsv: String,
        ratiosCsv: String,
    ): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeString(topology)
            d.writeString(pkgsCsv)
            d.writeString(ratiosCsv)
            b.transact(53, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun windowLifecycleAction(taskId: Int, pkg: String?, action: Int): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            d.writeString(pkg ?: "")
            d.writeInt(action.coerceIn(WIN_ACTION_FOCUS, WIN_ACTION_RELOAD_DPI))
            b.transact(54, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun setAdaptiveSingleConfig(enabled: Boolean, defaultWidthDp: Int): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(if (enabled) 1 else 0)
            d.writeInt(defaultWidthDp.coerceIn(MIN_SINGLE_WIDTH_DP, MAX_SINGLE_WIDTH_DP))
            b.transact(55, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun setInputBackend(mode: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(mode.coerceIn(INPUT_BACKEND_AUTO, INPUT_BACKEND_UHID))
            if (b.transact(56, d, r, 0) && r.dataAvail() >= 4) r.readInt() else -1
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    // --- Pure JVM-Testable Parsers & Display Helpers ---

    @JvmStatic
    fun isRemoteDisplayActive(sinkType: Int): Boolean =
        sinkType == SINK_REMOTE_USB ||
            sinkType == SINK_REMOTE_WIFI ||
            sinkType == SINK_REMOTE_WIFI_ADB

    @JvmStatic
    fun isTilingTopology(topology: String?): Boolean = when (topology?.uppercase(Locale.ROOT)) {
        LAYOUT_SPLIT_2_H,
        LAYOUT_SPLIT_2_V,
        LAYOUT_SPLIT_3_MASTER,
        LAYOUT_SPLIT_3_COLS,
        LAYOUT_GRID_2X2,
        -> true
        else -> false
    }

    @JvmStatic
    fun layoutModeLabel(mode: String?): String = when (mode?.uppercase(Locale.ROOT)) {
        LAYOUT_ADAPTIVE_SINGLE -> "居中宽幅"
        LAYOUT_FREEFORM -> "自由多窗口"
        LAYOUT_SPLIT_2_H -> "左右 2 分屏"
        LAYOUT_SPLIT_2_V -> "上下 2 分屏"
        LAYOUT_SPLIT_3_MASTER -> "1+2 三分屏"
        LAYOUT_SPLIT_3_COLS -> "三列分屏"
        LAYOUT_GRID_2X2 -> "2×2 四宫格"
        LAYOUT_FULLSCREEN -> "单应用全屏"
        else -> "默认桌面"
    }

    @JvmStatic
    fun injectStatusLabel(injectStatus: Int): String = when (injectStatus) {
        INJECT_STATUS_UHID -> "UHID 硬件外设注入已激活"
        INJECT_STATUS_BLOCKED -> "被系统拦截 (-1)"
        else -> "系统事件注入正常"
    }

    @JvmStatic
    fun inputBackendLabel(mode: Int): String = when (mode) {
        INPUT_BACKEND_UHID -> "强制 UHID 虚拟键鼠"
        INPUT_BACKEND_INJECT_EVENTS -> "仅系统注入"
        else -> "自动 (优先系统/UHID 兜底)"
    }

    @JvmStatic
    fun pxToDp(px: Int, dpi: Int): Int =
        (px * 160f / max(80, dpi)).roundToInt()

    @JvmStatic
    fun dpToPx(dpVal: Int, dpi: Int): Int =
        (dpVal * max(80, dpi) / 160f).roundToInt()

    @JvmStatic
    fun classifyWidthPresetLabel(widthDp: Int, isFullscreen: Boolean): String {
        if (isFullscreen) return "全屏"
        return when {
            widthDp <= 520 -> "${widthDp}dp · 手机竖屏"
            widthDp <= 780 -> "${widthDp}dp · 折叠屏"
            else -> "${widthDp}dp · 平板双栏"
        }
    }

    /**
     * Snaps a raw dragged width in dp to magnetic presets (460/680/900dp) or fullscreen
     * when within >= 94% of the full display width in dp.
     * Returns `(snappedWidthDp, isFullscreen)`.
     */
    @JvmStatic
    fun snapWidthDp(rawWidthDp: Int, fullWidthDp: Int): Pair<Int, Boolean> {
        val safeFull = max(MIN_SINGLE_WIDTH_DP, fullWidthDp)
        val clamped = rawWidthDp.coerceIn(MIN_SINGLE_WIDTH_DP, safeFull)
        if (clamped >= (safeFull * 0.94f).roundToInt()) {
            return safeFull to true
        }
        val presets = intArrayOf(460, 680, 900)
        for (p in presets) {
            if (p < safeFull && kotlin.math.abs(clamped - p) <= 14) {
                return p to false
            }
        }
        return clamped to false
    }

    /**
     * Computes a horizontally centered single-app bounds `[left, top, right, bottom]` in pixels
     * for a requested dp width at `dpi`, respecting display bounds and optional bottom dock reserve.
     */
    @JvmStatic
    fun computeCenteredSingleBoundsPx(
        displayW: Int,
        displayH: Int,
        dpi: Int,
        widthDp: Int,
        dockReservePx: Int = 0,
    ): IntArray {
        val safeW = max(320, displayW)
        val safeH = max(320, displayH)
        val availH = max(240, safeH - max(0, dockReservePx))
        val targetW = dpToPx(widthDp.coerceIn(MIN_SINGLE_WIDTH_DP, MAX_SINGLE_WIDTH_DP), dpi)
            .coerceIn(min(safeW, dpToPx(MIN_SINGLE_WIDTH_DP, dpi)), safeW)
        val left = max(0, (safeW - targetW) / 2)
        val right = min(safeW, left + targetW)
        return intArrayOf(left, 0, right, availH)
    }

    /**
     * Normalizes tiling ratios for the requested topology so overlay dividers have clean,
     * bounded percentages matching `TilingLayoutEngine`.
     */
    @JvmStatic
    fun normalizeTilingRatios(topology: String?, rawRatios: List<Int>): List<Int> =
        when (topology?.uppercase(Locale.ROOT)) {
            LAYOUT_SPLIT_3_COLS -> {
                if (rawRatios.size >= 3) {
                    val c0 = rawRatios[0].coerceIn(20, 60)
                    val c1 = rawRatios[1].coerceIn(20, 80 - c0)
                    val c2 = (100 - c0 - c1).coerceAtLeast(20)
                    listOf(c0, c1, c2)
                } else if (rawRatios.size == 2) {
                    val c0 = rawRatios[0].coerceIn(20, 60)
                    val second = rawRatios[1]
                    val c1 = if (second > c0 && second <= 80 && c0 + second >= 95) {
                        (second - c0).coerceIn(20, 80 - c0)
                    } else {
                        second.coerceIn(20, 80 - c0)
                    }
                    val c2 = (100 - c0 - c1).coerceAtLeast(20)
                    listOf(c0, c1, c2)
                } else {
                    listOf(33, 34, 33)
                }
            }
            LAYOUT_SPLIT_3_MASTER, LAYOUT_GRID_2X2 -> {
                val r0 = (rawRatios.getOrNull(0) ?: 50).coerceIn(25, 75)
                val r1 = (rawRatios.getOrNull(1) ?: 50).coerceIn(25, 75)
                listOf(r0, r1)
            }
            else -> {
                val r0 = (rawRatios.getOrNull(0) ?: 50).coerceIn(25, 75)
                listOf(r0, 100 - r0)
            }
        }

    /**
     * Pure-Kotlin parser for Binder code 50 (`getManagedWindows`) JSON snapshots so that both
     * Android runtime and plain JVM unit tests parse identically without `org.json` stub issues.
     */
    @JvmStatic
    fun parseWindowStateSnapshot(raw: String?): WindowStateSnapshot {
        if (raw.isNullOrBlank()) return WindowStateSnapshot()
        val parsed = MiniJsonParser(raw).parseRootObject() ?: return WindowStateSnapshot()
        val layoutMode = (parsed["layoutMode"] as? String)?.takeIf { it.isNotBlank() } ?: LAYOUT_FULLSCREEN
        val adaptiveEnabled = when (val v = parsed["adaptiveSingleEnabled"]) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> true
        }
        val defaultWidthDp = ((parsed["defaultSingleWidthDp"] as? Number)?.toInt() ?: DEFAULT_SINGLE_WIDTH_DP)
            .coerceIn(MIN_SINGLE_WIDTH_DP, MAX_SINGLE_WIDTH_DP)
        val tilingTopology = (parsed["tilingTopology"] as? String)?.takeIf { it.isNotBlank() } ?: LAYOUT_NONE

        val ratios = ArrayList<Int>()
        val rawRatios = parsed["ratios"] as? List<*>
        if (rawRatios != null) {
            for (elem in rawRatios) {
                if (elem is Number) ratios.add(elem.toInt())
            }
        }

        val windows = ArrayList<ManagedWindowInfo>()
        val rawWins = parsed["windows"] as? List<*>
        if (rawWins != null) {
            for (elem in rawWins) {
                val obj = elem as? Map<*, *> ?: continue
                val pkg = (obj["pkg"] as? String).orEmpty()
                if (pkg.isEmpty()) continue
                val taskId = (obj["taskId"] as? Number)?.toInt() ?: -1
                val mode = (obj["mode"] as? String)?.takeIf { it.isNotBlank() } ?: LAYOUT_FULLSCREEN
                val left = (obj["left"] as? Number)?.toInt() ?: 0
                val top = (obj["top"] as? Number)?.toInt() ?: 0
                val right = (obj["right"] as? Number)?.toInt() ?: 0
                val bottom = (obj["bottom"] as? Number)?.toInt() ?: 0
                val focused = when (val f = obj["focused"]) {
                    is Boolean -> f
                    is Number -> f.toInt() != 0
                    else -> false
                }
                val minimized = when (val m = obj["minimized"]) {
                    is Boolean -> m
                    is Number -> m.toInt() != 0
                    else -> false
                }
                windows.add(
                    ManagedWindowInfo(
                        taskId = taskId,
                        pkg = pkg,
                        mode = mode,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        focused = focused,
                        minimized = minimized,
                    ),
                )
            }
        }

        return WindowStateSnapshot(
            layoutMode = layoutMode,
            adaptiveSingleEnabled = adaptiveEnabled,
            defaultSingleWidthDp = defaultWidthDp,
            tilingTopology = tilingTopology,
            ratios = ratios,
            windows = windows,
        )
    }

    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseRootObject(): Map<String, Any?>? {
            skipWs()
            if (pos >= src.length || src[pos] != '{') return null
            return try {
                parseObject()
            } catch (_: Throwable) {
                null
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> if (src.startsWith("true", pos)) { pos += 4; true } else null
                'f' -> if (src.startsWith("false", pos)) { pos += 5; false } else null
                'n' -> if (src.startsWith("null", pos)) { pos += 4; null } else null
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < src.length) {
                skipWs()
                if (pos >= src.length || src[pos] != '"') break
                val key = parseString()
                skipWs()
                if (pos >= src.length || src[pos] != ':') break
                pos++ // ':'
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                }
                break
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < src.length) {
                list.add(parseValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                }
                break
            }
            return list
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (pos + 4 <= src.length) {
                            val code = src.substring(pos, pos + 4).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number? {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos == start) return null
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDoubleOrNull()
            } else {
                token.toIntOrNull() ?: token.toLongOrNull() ?: token.toDoubleOrNull()
            }
        }
    }

    @JvmStatic
    fun parseTrustedMacClients(raw: String?): List<TrustedMacClient> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<TrustedMacClient>()
        val seen = LinkedHashSet<String>()
        for (entry in raw.split(';')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val id = trimmed.substring(0, eq).trim()
            val name = trimmed.substring(eq + 1).trim().ifEmpty { id }
            if (id.isNotEmpty() && seen.add(id)) {
                out.add(TrustedMacClient(clientId = id, clientName = name))
            }
        }
        return out
    }

    @JvmStatic
    fun parseAppModes(raw: String?): Map<String, Int> {
        val map = HashMap<String, Int>()
        if (raw.isNullOrBlank()) return map
        for (item in raw.split(';')) {
            val eq = item.indexOf('=')
            if (eq > 0 && eq < item.length - 1) {
                val pkg = item.substring(0, eq).trim()
                val mode = item.substring(eq + 1).trim().toIntOrNull() ?: continue
                if (pkg.isNotEmpty() && mode in 0..3) {
                    map[pkg] = mode
                }
            }
        }
        return map
    }

    @JvmStatic
    fun parseEdidModes(modesRaw: String?): List<EdidMode> {
        if (modesRaw.isNullOrBlank()) return emptyList()
        val out = ArrayList<EdidMode>()
        for (entry in modesRaw.split(';')) {
            val kv = entry.trim().split(':')
            if (kv.size != 2) continue
            val id = kv[0].trim().toIntOrNull() ?: continue
            val rf = kv[1].trim().split('@')
            if (rf.size != 2) continue
            val wh = rf[0].trim().split('x')
            if (wh.size != 2) continue
            val pw = wh[0].trim().toIntOrNull() ?: continue
            val ph = wh[1].trim().toIntOrNull() ?: continue
            val pf = rf[1].trim().toFloatOrNull() ?: 60f
            if (pw > 0 && ph > 0 && pf > 0f) {
                out.add(EdidMode(id = id, width = pw, height = ph, fps = pf))
            }
        }
        return out
    }

    @JvmStatic
    fun sinkTypeLabel(sinkType: Int): String = when (sinkType) {
        SINK_PHYSICAL -> "物理外接显示器"
        SINK_REMOTE_USB -> "Mac 有线大屏 (USB)"
        SINK_REMOTE_WIFI_ADB -> "Mac 无线 ADB 大屏"
        SINK_REMOTE_WIFI -> "Mac 无线 Wi-Fi 大屏"
        else -> "未连接输出"
    }

    @JvmStatic
    fun formatShortFingerprint(fp: String?): String {
        val clean = fp?.replace(":", "")?.replace("-", "")?.trim()?.uppercase(Locale.ROOT) ?: ""
        if (clean.isEmpty()) return ""
        val prefix = clean.take(16)
        return prefix.chunked(4).joinToString("-")
    }

    @JvmStatic
    fun formatBitrateMbps(bitrateKbps: Int): String {
        if (bitrateKbps <= 0) return "--"
        val mbps = bitrateKbps / 1000.0
        return String.format(Locale.US, "%.1f Mbps", mbps)
    }

    @JvmStatic
    fun formatStreamFps(fpsX100: Int): String {
        if (fpsX100 <= 0) return "--"
        val fps = fpsX100 / 100.0
        return if (fpsX100 % 100 == 0) {
            "${fpsX100 / 100} fps"
        } else {
            String.format(Locale.US, "%.1f fps", fps)
        }
    }

    @JvmStatic
    fun computeOverlayDpi(virtW: Int, virtH: Int, reqDpi: Int): Int {
        val safeReq = if (reqDpi > 0) reqDpi else 240
        val safeW = if (virtW > 0) virtW else 2560
        val safeH = if (virtH > 0) virtH else 1440
        val minEdge = min(safeW, safeH)
        val maxDpiForEdge = max(80, (minEdge * 160f / 720f).roundToInt())
        return max(80, min(safeReq, maxDpiForEdge))
    }

    @JvmStatic
    fun computeSmallestWidthDp(virtW: Int, virtH: Int, dpi: Int): Int {
        val minEdge = min(max(1, virtW), max(1, virtH))
        val safeDpi = max(80, dpi)
        return (minEdge * 160f / safeDpi).roundToInt()
    }

    @JvmStatic
    fun recommendDpiForResolution(width: Int, height: Int): Int {
        val minEdge = min(width, height)
        return when {
            minEdge >= 2000 -> 320
            minEdge >= 1360 -> 240
            minEdge >= 1000 -> 200
            else -> 160
        }
    }

    @JvmStatic
    fun alignResolution16(value: Int, minVal: Int = 640, maxVal: Int = 7680): Int {
        val clamped = value.coerceIn(minVal, maxVal)
        return ((clamped + 8) / 16) * 16
    }

    // --- Core Daemon Control IPC ---

    @JvmStatic
    fun toggleInput(): Int = simpleCall(10)

    @JvmStatic
    fun setInputMode(mode: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(mode)
            b.transact(19, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun sendBack() = simpleOneWay(11)

    @JvmStatic
    fun relaunchLauncher() = simpleOneWay(12)

    @JvmStatic
    fun sendHome() = simpleOneWay(13)

    @JvmStatic
    fun rebuild() = simpleOneWay(21)

    @JvmStatic
    fun applyConfig(w: Int, h: Int, dpi: Int, fps: Float, physModeId: Int): Boolean =
        applyConfig(w, h, dpi, fps, physModeId, -1)

    @JvmStatic
    fun applyConfig(w: Int, h: Int, dpi: Int, fps: Float, physModeId: Int, rot: Int): Boolean =
        applyConfigDetailed(w, h, dpi, fps, physModeId, rot).ok

    @JvmStatic
    fun applyConfigDetailed(
        w: Int,
        h: Int,
        dpi: Int,
        fps: Float,
        physModeId: Int,
        rot: Int,
    ): ApplyResult {
        val res = ApplyResult()
        val b = getService() ?: return res
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(w)
            d.writeInt(h)
            d.writeInt(dpi)
            d.writeInt((fps * 100f).roundToInt())
            d.writeInt(physModeId)
            d.writeInt(rot)
            b.transact(16, d, r, 0)
            res.ok = r.readInt() == 1
            if (r.dataAvail() >= 16) {
                res.physResult = r.readInt()
                res.physW = r.readInt()
                res.physH = r.readInt()
                res.physFps = r.readInt() / 100f
            }
            return res
        } catch (_: Throwable) {
            return res
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun shutdownDaemon(ctx: Context?) {
        sendCtlSocketCmdAsync("SHUTDOWN")
        val b = getService()
        if (b != null) {
            val d = obtainInterfaceParcel()
            val r = Parcel.obtain()
            try {
                b.transact(99, d, r, 0)
            } catch (_: Throwable) {
            } finally {
                d.recycle()
                r.recycle()
            }
        }
        sBinder = null
        _statusFlow.value = Status()
    }

    @JvmStatic
    fun sendMouse(actionType: Int, a1: Float, a2: Float): IntArray? {
        val b = getService() ?: return null
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(actionType)
            d.writeFloat(a1)
            d.writeFloat(a2)
            if (!b.transact(17, d, r, 0) || r.dataAvail() < 8) return null
            val cx = r.readInt()
            val cy = r.readInt()
            val inj = if (r.dataAvail() >= 4) r.readInt() else 1
            intArrayOf(cx, cy, inj)
        } catch (_: Throwable) {
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun sendKey(keyCode: Int, metaState: Int) {
        val b = getService() ?: return
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(0)
            d.writeInt(keyCode)
            d.writeInt(metaState)
            d.writeString(null)
            b.transact(18, d, r, 0)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun sendAscii(txt: String?) {
        val b = getService() ?: return
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(1)
            d.writeInt(0)
            d.writeInt(0)
            d.writeString(txt)
            b.transact(18, d, r, 0)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun sendPaste(useCtrlV: Boolean) {
        val b = getService() ?: return
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(2)
            d.writeInt(if (useCtrlV) 1 else 0)
            d.writeInt(0)
            d.writeString(null)
            b.transact(18, d, r, 0)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun launchComponent(pkg: String?, cls: String?): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeString(pkg)
            d.writeString(cls)
            b.transact(20, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun getWallpaperBitmap(force: Boolean): Bitmap? {
        val cached = sCachedWpBitmap
        if (!force && cached != null && !cached.isRecycled) {
            return cached
        }
        val b = getService() ?: return sCachedWpBitmap
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(if (force) 1 else 0)
            if (b.transact(22, d, r, 0)) {
                val bytes = r.createByteArray()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        sCachedWpBitmap = bmp
                        return bmp
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return sCachedWpBitmap
    }

    @JvmStatic
    fun getTasks(): List<TaskEntry> {
        val out = ArrayList<TaskEntry>()
        val b = getService() ?: return out
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            if (b.transact(23, d, r, 0)) {
                val n = r.readInt()
                var i = 0
                while (i < n && i < 20) {
                    val te = TaskEntry()
                    te.taskId = r.readInt()
                    te.pkg = r.readString() ?: ""
                    te.flags = r.readInt()
                    if (te.pkg.isNotEmpty()) out.add(te)
                    i++
                }
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return out
    }

    @JvmStatic
    fun switchToTask(taskId: Int, pkg: String?): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            d.writeString(pkg)
            b.transact(24, d, r, 0)
            r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun minimizeToExtHome(taskId: Int, pkg: String?): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            d.writeString(pkg)
            b.transact(37, d, r, 0)
            r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun removeTask(taskId: Int): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            b.transact(25, d, r, 0)
            r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun getSysStatus(): SysStatus {
        val st = SysStatus()
        val b = getService() ?: return st
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            if (b.transact(26, d, r, 0)) {
                st.valid = true
                st.wifiOn = r.readInt() == 1
                st.wifiSsid = r.readString() ?: ""
                st.btOn = r.readInt() == 1
                st.btName = r.readString() ?: ""
                st.airplaneOn = r.readInt() == 1
                st.powerSaveOn = r.readInt() == 1
                st.screenDimmed = (r.dataAvail() >= 4) && (r.readInt() == 1)
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return st
    }

    @JvmStatic
    fun execSysAction(act: Int, arg: Int, str: String?): Boolean {
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(act)
            d.writeInt(arg)
            d.writeString(str)
            b.transact(27, d, r, 0)
            r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun mirrorModeLabel(mode: Int): String =
        if (mode == MIRROR_MODE_MIRROR) "原生镜像" else "DeX 扩展桌面"

    @JvmStatic
    fun setMirrorMode(mode: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(mode)
            b.transact(28, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun toggleMirrorMode(): Int = setMirrorMode(-1)

    @JvmStatic
    fun setFullscreenPolicy(enable: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(enable)
            b.transact(29, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun toggleFullscreenPolicy(): Int = setFullscreenPolicy(-1)

    @JvmStatic
    fun setRotation(rot: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(rot)
            b.transact(34, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun cycleRotation(): Int = setRotation(-1)

    @JvmStatic
    fun rotationLabel(rot: Int): String {
        val r = ((rot % 4) + 4) % 4
        return when (r) {
            1 -> "90° 竖屏"
            2 -> "180° 倒置"
            3 -> "270° 竖屏"
            else -> "0° 横屏"
        }
    }

    @JvmStatic
    fun appModeLabel(mode: Int): String = when (mode) {
        APP_MODE_LAND_FULL -> "强制横屏全屏"
        APP_MODE_PORT_COMPAT -> "竖屏居中兼容"
        else -> "跟随系统默认"
    }

    @JvmStatic
    fun appModeBadge(mode: Int): String = when (mode) {
        APP_MODE_LAND_FULL -> "横屏全屏"
        APP_MODE_PORT_COMPAT -> "竖屏兼容"
        else -> ""
    }

    @JvmStatic
    fun prepareAppLaunch(pkg: String?): Int {
        if (pkg.isNullOrEmpty()) return 0
        val b = getService() ?: return 0
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeString(pkg)
            b.transact(30, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            0
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun getAllAppModes(): Map<String, Int> {
        val b = getService() ?: return HashMap()
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            b.transact(31, d, r, 0)
            parseAppModes(r.readString())
        } catch (_: Throwable) {
            HashMap()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun setAppMode(pkg: String?, mode: Int, restart: Boolean): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val b = getService() ?: return false
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeString(pkg)
            d.writeInt(mode)
            d.writeInt(if (restart) 1 else 0)
            b.transact(32, d, r, 0)
            r.readInt() == 1
        } catch (_: Throwable) {
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun resetAllAppModes(): Boolean = simpleCall(33) == 1

    @JvmStatic
    fun splitActionDetailed(action: Int, arg: Int, pkg: String?, cls: String?): SplitResult {
        val res = SplitResult()
        val b = getService() ?: return res
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(action)
            d.writeInt(arg)
            d.writeString(pkg ?: "")
            d.writeString(cls ?: "")
            if (b.transact(35, d, r, 0)) {
                res.ok = r.readInt() == 1
                if (r.dataAvail() >= 8) {
                    res.splitActive = r.readInt() == 1
                    res.ratioPct = r.readInt()
                    res.slot0Pkg = r.readString() ?: ""
                    res.slot1Pkg = r.readString() ?: ""
                }
            }
            return res
        } catch (_: Throwable) {
            return res
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun splitAction(action: Int, arg: Int, pkg: String?, cls: String?): Boolean =
        splitActionDetailed(action, arg, pkg, cls).ok

    @JvmStatic
    fun captureTaskSnapshot(taskId: Int, pkg: String?, live: Boolean): TaskSnapshotData {
        if (!sHwBufSnapshotBlocked) {
            val hw = captureTaskSnapshotInternal(taskId, pkg, live, false)
            if (hw != null) return hw
        }
        return captureTaskSnapshotInternal(taskId, pkg, live, true) ?: TaskSnapshotData()
    }

    private fun captureTaskSnapshotInternal(
        taskId: Int,
        pkg: String?,
        live: Boolean,
        forceJpeg: Boolean,
    ): TaskSnapshotData? {
        val b = getService() ?: return TaskSnapshotData()
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        var hb: HardwareBuffer? = null
        try {
            val flags = (if (live) 1 else 0) or (if (forceJpeg) 2 else 0)
            d.writeInt(taskId)
            d.writeString(pkg ?: "")
            d.writeInt(flags)
            if (!b.transact(44, d, r, 0) || r.dataAvail() < 4) {
                if (!forceJpeg) {
                    sHwBufSnapshotBlocked = true
                    return null
                }
                return TaskSnapshotData()
            }
            val out = TaskSnapshotData()
            val hasBounds = r.readInt()
            if (hasBounds == 1 && r.dataAvail() >= 16) {
                out.bounds = Rect(r.readInt(), r.readInt(), r.readInt(), r.readInt())
            }
            if (r.dataAvail() < 4) return out
            val snapType = r.readInt()
            if (snapType == 1) {
                val csOrd = r.readInt()
                hb = HardwareBuffer.CREATOR.createFromParcel(r)
                if (hb != null && !hb.isClosed && hb.width > 0 && hb.height > 0) {
                    val names = ColorSpace.Named.values()
                    val cs = ColorSpace.get(
                        if (csOrd in names.indices) names[csOrd] else ColorSpace.Named.SRGB,
                    )
                    val bmp = Bitmap.wrapHardwareBuffer(hb, cs)
                    if (bmp != null) {
                        out.bitmap = bmp
                        out.ownBitmap = true
                        return out
                    }
                }
                if (!forceJpeg) {
                    sHwBufSnapshotBlocked = true
                    return null
                }
            } else if (snapType == 2) {
                val bytes = r.createByteArray()
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        out.bitmap = bmp
                        out.ownBitmap = true
                    }
                }
            }
            return out
        } catch (_: Throwable) {
            if (!forceJpeg) {
                sHwBufSnapshotBlocked = true
                return null
            }
            return TaskSnapshotData()
        } finally {
            if (hb != null && !hb.isClosed) {
                try { hb.close() } catch (_: Throwable) {}
            }
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun assignSplitSlot(slot: Int, pkg: String?, cls: String?): Boolean =
        splitAction(if (slot == 0) SPLIT_ASSIGN_LEFT_TOP else SPLIT_ASSIGN_RIGHT_BOTTOM, -1, pkg, cls)

    @JvmStatic
    fun toggleQuickSplit(): Boolean = splitAction(SPLIT_TOGGLE_QUICK, 0, null, null)

    @JvmStatic
    fun setSplitRatio(ratioPct: Int): Boolean =
        splitAction(SPLIT_SET_RATIO, ratioPct.coerceIn(25, 75), null, null)

    @JvmStatic
    fun swapSplitSlots(): Boolean = splitAction(SPLIT_SWAP, 0, null, null)

    @JvmStatic
    fun exitSplitMode(): Boolean = splitAction(SPLIT_EXIT, 0, null, null)

    /** Switches a task between floating freeform window (1) and fullscreen (0); -1 toggles. */
    @JvmStatic
    fun toggleTaskFreeform(taskId: Int, pkg: String?, enableFreeform: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(taskId)
            d.writeString(pkg ?: "")
            d.writeInt(enableFreeform)
            b.transact(36, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun resolveMirrorTheme(id: String?, customBg: Int): MirrorTheme {
        if ("oled" == id) {
            return MirrorTheme(
                "oled", "OLED纯黑",
                0xFF000000.toInt(), 0xFF0A0D14.toInt(), 0xE60D111A.toInt(), 0x38475569,
                0xB3090D14.toInt(), 0xFF181E29.toInt(), 0xFF1D4ED8.toInt(), 0xFFFFFFFF.toInt(),
                0xFF94A3B8.toInt(), 0xFF60A5FA.toInt(), false,
            )
        }
        if ("frost" == id) {
            return MirrorTheme(
                "frost", "晨雾浅白",
                0xFFF1F5F9.toInt(), 0xFFE2E8F0.toInt(), 0xF2FFFFFF.toInt(), 0x3864748B,
                0xE6FFFFFF.toInt(), 0xFFE2E8F0.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt(),
                0xFF475569.toInt(), 0xFF0284C7.toInt(), true,
            )
        }
        if ("cyber" == id) {
            return MirrorTheme(
                "cyber", "赛博紫青",
                0xFF110722.toInt(), 0xFF190C33.toInt(), 0xDE1E1038.toInt(), 0x4CA855F7,
                0x99190D30.toInt(), 0xFF2E1065.toInt(), 0xFF7C3AED.toInt(), 0xFFFDF4FF.toInt(),
                0xFFD8B4FE.toInt(), 0xFF22D3EE.toInt(), false,
            )
        }
        if ("emerald" == id) {
            return MirrorTheme(
                "emerald", "翡翠墨绿",
                0xFF031410.toInt(), 0xFF062019.toInt(), 0xDE0A241E.toInt(), 0x4C10B981,
                0x99071C17.toInt(), 0xFF134E4A.toInt(), 0xFF059669.toInt(), 0xFFECFDF5.toInt(),
                0xFFA7F3D0.toInt(), 0xFF34D399.toInt(), false,
            )
        }
        if ("amber" == id) {
            return MirrorTheme(
                "amber", "暖暮琥珀",
                0xFF180E06.toInt(), 0xFF241509.toInt(), 0xDE29180B.toInt(), 0x4CF59E0B,
                0x99201207.toInt(), 0xFF451A03.toInt(), 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(),
                0xFFFDE68A.toInt(), 0xFFFBBF24.toInt(), false,
            )
        }
        if ("custom" == id) {
            val opaqueBg = 0xFF000000.toInt() or (customBg and 0x00FFFFFF)
            val r = (opaqueBg shr 16) and 0xFF
            val g = (opaqueBg shr 8) and 0xFF
            val b = opaqueBg and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val light = luma > 0.58f
            return if (light) {
                val hdr = blendColor(opaqueBg, 0xFFCBD5E1.toInt(), 0.22f)
                MirrorTheme(
                    "custom", "自定义浅色",
                    opaqueBg, hdr, 0xEAFFFFFF.toInt(), 0x3864748B, 0xDEFFFFFF.toInt(),
                    0xFFE2E8F0.toInt(), 0xFF2563EB.toInt(), 0xFF0F172A.toInt(),
                    0xFF475569.toInt(), 0xFF0284C7.toInt(), true,
                )
            } else {
                val hdr = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.08f)
                val cardRgb = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.11f) and 0x00FFFFFF
                val wRgb = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.07f) and 0x00FFFFFF
                val pill = blendColor(opaqueBg, 0xFFFFFFFF.toInt(), 0.18f)
                MirrorTheme(
                    "custom", "自定义深色",
                    opaqueBg, hdr, 0xDC000000.toInt() or cardRgb, 0x44FFFFFF,
                    0x9E000000.toInt() or wRgb, pill, 0xFF2563EB.toInt(),
                    0xFFF8FAFC.toInt(), 0xFFCBD5E1.toInt(), 0xFF38BDF8.toInt(), false,
                )
            }
        }
        return MirrorTheme(
            "night", "极夜深蓝",
            0xFF060A14.toInt(), 0xFF0D1526.toInt(), 0xDE0F172A.toInt(), 0x4294A3B8,
            0x990F172A.toInt(), 0xFF1E293B.toInt(), 0xFF312E81.toInt(), 0xFFF8FAFC.toInt(),
            0xFFCBD5E1.toInt(), 0xFF38BDF8.toInt(), false,
        )
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val r = (r1 + (r2 - r1) * ratio).roundToInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * ratio).roundToInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * ratio).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    @JvmStatic
    fun getOemInjectHint(): String {
        val brand = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            return "Xiaomi HyperOS 请前往「开发者选项」开启「USB 调试（安全设置）」以允许外接屏模拟点击与键鼠注入。"
        }
        if (brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) {
            return "ColorOS 请前往「开发者选项」底部开启「禁止权限监控」以允许外接屏输入注入。"
        }
        if (brand.contains("vivo") || brand.contains("iqoo")) {
            return "OriginOS 请前往「开发者选项」开启「USB 调试（安全设置）/ 模拟点击」权限。"
        }
        return "请前往系统「开发者选项」开启允许模拟点击 / USB 安全调试设置。"
    }

    @JvmStatic
    fun mirrorPresetIndex(key: String?): Int {
        if (key == null) return -1
        return MIRROR_PRESET_KEYS.indexOf(key)
    }

    @JvmStatic
    fun mirrorPresetTitle(key: String?): String {
        val idx = mirrorPresetIndex(key)
        return if (idx >= 0) MIRROR_PRESET_TITLES[idx] else key.toString()
    }

    @JvmStatic
    fun mirrorSideModeLabel(sideMode: Int): String = when (sideMode) {
        MIRROR_SIDE_OFF -> "纯黑居中"
        MIRROR_SIDE_ALIGN_RIGHT -> "靠右/靠下 · 融合大边栏"
        MIRROR_SIDE_CENTER_DUAL -> "主屏居中 · 双信息条"
        else -> "靠左/靠上 · 融合大边栏"
    }

    @JvmStatic
    fun setMirrorSideMode(mode: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            d.writeInt(mode)
            b.transact(38, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun getMirrorTelemetry(): MirrorTelemetry {
        val mt = MirrorTelemetry()
        val b = getService() ?: return mt
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            if (b.transact(39, d, r, 0)) {
                mt.valid = true
                val ar = r.readString()
                val ad = r.readString()
                val mp = r.readString()
                val ms = r.readInt()
                val ttl = r.readString()
                val sub = r.readString()
                val cal = r.readString()
                val bat = r.readString()
                val wif = r.readString()
                if (!ar.isNullOrEmpty()) mt.audioRoute = ar
                if (ad != null) mt.audioDetail = ad
                if (mp != null) mt.mediaPkg = mp
                mt.mediaState = ms
                if (ttl != null) mt.mediaTitle = ttl
                if (sub != null) mt.mediaSubtitle = sub
                if (cal != null) mt.calendarText = cal
                if (bat != null) mt.battDetail = bat
                if (wif != null) mt.wifiDetail = wif
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return mt
    }

    @JvmStatic
    fun ensureAppWidgetPermission(): Boolean = simpleCall(40) == 1

    @JvmStatic
    fun sendSideTouch(actionMasked: Int, sx: Float, sy: Float) {
        val b = getService() ?: return
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(actionMasked)
            d.writeFloat(sx)
            d.writeFloat(sy)
            b.transact(41, d, r, 0)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    @JvmStatic
    fun sendCapturedMirrorMouse(actionType: Int, a1: Float, a2: Float, a3: Float): IntArray {
        val b = getService() ?: return intArrayOf(0, 0, 0)
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeInt(actionType)
            d.writeFloat(a1)
            d.writeFloat(a2)
            d.writeFloat(a3)
            if (b.transact(42, d, r, 0)) {
                return intArrayOf(r.readInt(), r.readInt(), r.readInt())
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return intArrayOf(0, 0, 0)
    }

    @JvmStatic
    fun notifyMirrorEdgeHover(px: Float, py: Float): IntArray {
        val b = getService() ?: return intArrayOf(0, 0, 0)
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        try {
            d.writeFloat(px)
            d.writeFloat(py)
            if (b.transact(43, d, r, 0)) {
                return intArrayOf(r.readInt(), r.readInt(), r.readInt())
            }
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
        return intArrayOf(0, 0, 0)
    }

    private fun simpleCall(code: Int): Int {
        val b = getService() ?: return -1
        val d = obtainInterfaceParcel()
        val r = Parcel.obtain()
        return try {
            b.transact(code, d, r, 0)
            r.readInt()
        } catch (_: Throwable) {
            -1
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun simpleOneWay(code: Int) {
        val b = getService() ?: return
        val d = obtainInterfaceParcel()
        try {
            b.transact(code, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
        } finally {
            d.recycle()
        }
    }
}
