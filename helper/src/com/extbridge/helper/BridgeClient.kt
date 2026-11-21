package com.extbridge.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.IBinder
import com.extbridge.helper.ipc.BinderTransport
import com.extbridge.helper.ipc.BridgeParcelCodecs
import com.extbridge.helper.ipc.DaemonStateStore
import com.extbridge.helper.prefs.MirrorThemeCatalog
import com.extbridge.helper.protocol.ApplyResult
import com.extbridge.helper.protocol.BridgeLabels
import com.extbridge.helper.protocol.BridgeParsers
import com.extbridge.helper.protocol.BridgeProtocol.AppMode
import com.extbridge.helper.protocol.BridgeProtocol.InputBackend
import com.extbridge.helper.protocol.BridgeProtocol.KeyAction
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.MirrorMode
import com.extbridge.helper.protocol.BridgeProtocol.RemoteStream
import com.extbridge.helper.protocol.BridgeProtocol.SinkType
import com.extbridge.helper.protocol.BridgeProtocol.SplitAction
import com.extbridge.helper.protocol.BridgeProtocol.SysAction
import com.extbridge.helper.protocol.BridgeProtocol.TxCode
import com.extbridge.helper.protocol.BridgeProtocol.WindowAction
import com.extbridge.helper.protocol.BridgeProtocol.WindowMode
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.DisplayGeometry
import com.extbridge.helper.protocol.EdidMode
import com.extbridge.helper.protocol.ManagedWindowInfo
import com.extbridge.helper.protocol.MirrorTelemetry
import com.extbridge.helper.protocol.SplitResult
import com.extbridge.helper.protocol.SysStatus
import com.extbridge.helper.protocol.TaskEntry
import com.extbridge.helper.protocol.TaskSnapshotData
import com.extbridge.helper.protocol.TrustedMacClient
import com.extbridge.helper.protocol.WifiRemoteConfigResult
import com.extbridge.helper.protocol.WindowStateSnapshot
import kotlin.math.roundToInt

/**
 * Concise IPC facade over [BinderTransport], [DaemonStateStore], `com.extbridge.helper.protocol`,
 * and [MirrorThemeCatalog]. Preserves stable call-sites across Activities and overlay controllers.
 */
object BridgeClient {

    // --- Re-exported Protocol Constants (SSOT in BridgeProtocol / DisplayGeometry) ---
    const val SYS_WIFI = SysAction.WIFI
    const val SYS_BT = SysAction.BT
    const val SYS_AIRPLANE = SysAction.AIRPLANE
    const val SYS_POWER_SAVE = SysAction.POWER_SAVE
    const val SYS_OPEN_SETTINGS = SysAction.OPEN_SETTINGS
    const val SYS_SCREEN_DIM = SysAction.SCREEN_DIM

    const val SINK_NONE = SinkType.NONE
    const val SINK_PHYSICAL = SinkType.PHYSICAL
    const val SINK_REMOTE_USB = SinkType.REMOTE_USB
    const val SINK_REMOTE_WIFI = SinkType.REMOTE_WIFI
    const val SINK_REMOTE_WIFI_ADB = SinkType.REMOTE_WIFI_ADB

    const val WIN_MODE_AUTO = WindowMode.AUTO
    const val WIN_MODE_FULLSCREEN = WindowMode.FULLSCREEN
    const val WIN_MODE_ADAPTIVE_SINGLE = WindowMode.ADAPTIVE_SINGLE
    const val WIN_MODE_FREEFORM = WindowMode.FREEFORM

    const val WIN_ACTION_FOCUS = WindowAction.FOCUS
    const val WIN_ACTION_MINIMIZE = WindowAction.MINIMIZE
    const val WIN_ACTION_CLOSE = WindowAction.CLOSE
    const val WIN_ACTION_RELOAD_DPI = WindowAction.RELOAD_DPI
    const val WIN_ACTION_SEND_BACK = WindowAction.SEND_BACK
    const val WIN_ACTION_TOGGLE_MAXIMIZE = WindowAction.TOGGLE_MAXIMIZE

    const val INPUT_BACKEND_UHID = InputBackend.UHID
    const val INJECT_STATUS_UHID = InputBackend.INJECT_STATUS_UHID

    const val LAYOUT_NONE = LayoutMode.NONE
    const val LAYOUT_FULLSCREEN = LayoutMode.FULLSCREEN
    const val LAYOUT_ADAPTIVE_SINGLE = LayoutMode.ADAPTIVE_SINGLE
    const val LAYOUT_FREEFORM = LayoutMode.FREEFORM
    const val LAYOUT_SPLIT_2_H = LayoutMode.SPLIT_2_H
    const val LAYOUT_SPLIT_2_V = LayoutMode.SPLIT_2_V
    const val LAYOUT_SPLIT_3_MASTER = LayoutMode.SPLIT_3_MASTER
    const val LAYOUT_SPLIT_3_COLS = LayoutMode.SPLIT_3_COLS
    const val LAYOUT_GRID_2X2 = LayoutMode.GRID_2X2
    const val LAYOUT_EXIT = LayoutMode.EXIT

    const val MIN_SINGLE_WIDTH_DP = DisplayGeometry.MIN_SINGLE_WIDTH_DP
    const val MAX_SINGLE_WIDTH_DP = DisplayGeometry.MAX_SINGLE_WIDTH_DP

    const val DEFAULT_WIFI_REMOTE_PORT = RemoteStream.DEFAULT_WIFI_PORT

    const val MIRROR_MODE_DEX = MirrorMode.DEX
    const val MIRROR_MODE_MIRROR = MirrorMode.MIRROR

    const val APP_MODE_LAND_FULL = AppMode.LAND_FULL
    const val APP_MODE_PORT_COMPAT = AppMode.PORT_COMPAT

    const val SPLIT_ASSIGN_LEFT_TOP = SplitAction.ASSIGN_LEFT_TOP
    const val SPLIT_ASSIGN_RIGHT_BOTTOM = SplitAction.ASSIGN_RIGHT_BOTTOM
    const val SPLIT_TOGGLE_QUICK = SplitAction.TOGGLE_QUICK

    // --- Type Aliases for Seamless Source Compatibility ---
    typealias Status = DaemonStatus
    typealias TaskEntry = com.extbridge.helper.protocol.TaskEntry
    typealias SysStatus = com.extbridge.helper.protocol.SysStatus
    typealias MirrorTelemetry = com.extbridge.helper.protocol.MirrorTelemetry
    typealias ManagedWindowInfo = com.extbridge.helper.protocol.ManagedWindowInfo
    typealias WindowStateSnapshot = com.extbridge.helper.protocol.WindowStateSnapshot
    typealias TaskSnapshotData = com.extbridge.helper.protocol.TaskSnapshotData
    typealias EdidMode = com.extbridge.helper.protocol.EdidMode
    typealias TrustedMacClient = com.extbridge.helper.protocol.TrustedMacClient
    typealias MirrorTheme = com.extbridge.helper.prefs.MirrorTheme

    @Volatile
    private var cachedWallpaper: Bitmap? = null

    // --- Binder Lifecycle ---

    fun setOnBinderReady(r: Runnable?) = BinderTransport.addOnBinderReady(r)

    fun removeOnBinderReady(r: Runnable?) = BinderTransport.removeOnBinderReady(r)

    fun setBinder(b: IBinder?) = BinderTransport.setBinder(b)

    fun requestBinder(ctx: Context?) = BinderTransport.requestBinder(ctx)

    fun getService(): IBinder? = BinderTransport.getService()

    // --- Status Polling ---

    fun getStatus(): DaemonStatus {
        val polled = BinderTransport.transactOrNull(TxCode.GET_STATUS) {
            BridgeParcelCodecs.readDaemonStatus(this)
        } ?: DaemonStatus()
        DaemonStateStore.updateStatus(polled)
        return polled
    }

    // --- Remote / Wireless Streaming Binder IPC (codes 45..49) ---

    fun setWifiRemoteConfig(
        enable: Boolean,
        port: Int = 0,
        regeneratePin: Boolean = false,
    ): WifiRemoteConfigResult = BinderTransport.transactOrNull(
        code = TxCode.SET_WIFI_REMOTE_CONFIG,
        write = {
            writeInt(if (enable) 1 else 0)
            writeInt(port)
            writeInt(if (regeneratePin) 1 else 0)
        },
        read = {
            if (dataAvail() < 8) return@transactOrNull null
            WifiRemoteConfigResult(
                ok = true,
                enabled = readInt() != 0,
                port = readInt(),
                certFingerprint = if (dataAvail() >= 4) readString().orEmpty() else "",
                pin = if (dataAvail() >= 4) readString().orEmpty() else "",
            )
        },
    ) ?: WifiRemoteConfigResult()

    fun listTrustedMacClients(): List<TrustedMacClient> = BinderTransport.transactOrNull(
        code = TxCode.LIST_TRUSTED_MAC_CLIENTS,
        read = { if (dataAvail() >= 4) BridgeParsers.parseTrustedMacClients(readString()) else emptyList() },
    ).orEmpty()

    fun revokeTrustedMacClient(clientId: String): Boolean {
        val trimmed = clientId.trim()
        if (trimmed.isEmpty()) return false
        return BinderTransport.transactBool(TxCode.REVOKE_TRUSTED_MAC_CLIENT) { writeString(trimmed) }
    }

    fun disconnectRemoteClient(): Boolean = BinderTransport.transactBool(TxCode.DISCONNECT_REMOTE_CLIENT)

    fun setRemoteStreamPrefs(codecPref: Int, maxBitrateKbps: Int, audioEnabled: Boolean): Boolean =
        BinderTransport.transactBool(TxCode.SET_REMOTE_STREAM_PREFS) {
            writeInt(codecPref.coerceIn(RemoteStream.CODEC_AUTO_HEVC, RemoteStream.CODEC_H264))
            writeInt(maxBitrateKbps.coerceIn(RemoteStream.MIN_BITRATE_KBPS, RemoteStream.MAX_BITRATE_KBPS))
            writeInt(if (audioEnabled) 1 else 0)
        }

    // --- v4.1 Adaptive Window, Multi-Tiling & Input Backend Binder IPC (codes 50..56) ---

    fun getManagedWindows(): WindowStateSnapshot {
        val snap = BinderTransport.transactOrNull(
            code = TxCode.GET_MANAGED_WINDOWS,
            read = { if (dataAvail() >= 4) BridgeParsers.parseWindowStateSnapshot(readString()) else WindowStateSnapshot() },
        ) ?: WindowStateSnapshot()
        DaemonStateStore.updateWindowSnapshot(snap)
        return snap
    }

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
        return BinderTransport.transactBool(TxCode.LAUNCH_IN_WINDOW_MODE) {
            writeString(pkg)
            writeString(cls.orEmpty())
            writeInt(mode.coerceIn(WindowMode.AUTO, WIN_MODE_FREEFORM))
            writeInt(left)
            writeInt(top)
            writeInt(right)
            writeInt(bottom)
        }
    }

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

    fun updateWindowBounds(
        taskId: Int,
        pkg: String?,
        mode: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rememberPref: Boolean,
    ): Boolean = BinderTransport.transactBool(TxCode.UPDATE_WINDOW_BOUNDS) {
        writeInt(taskId)
        writeString(pkg.orEmpty())
        writeInt(mode.coerceIn(WIN_MODE_FULLSCREEN, WIN_MODE_FREEFORM))
        writeInt(left)
        writeInt(top)
        writeInt(right)
        writeInt(bottom)
        writeInt(if (rememberPref) 1 else 0)
    }

    fun setTilingLayout(topology: String, pkgs: List<String>, ratios: List<Int>): Boolean =
        setTilingLayout(
            topology = topology,
            pkgsCsv = pkgs.filter { it.isNotBlank() }.joinToString(","),
            ratiosCsv = ratios.joinToString(","),
        )

    fun setTilingLayout(topology: String, pkgsCsv: String, ratiosCsv: String): Boolean =
        BinderTransport.transactBool(TxCode.SET_TILING_LAYOUT) {
            writeString(topology)
            writeString(pkgsCsv)
            writeString(ratiosCsv)
        }

    fun windowLifecycleAction(taskId: Int, pkg: String?, action: Int): Boolean =
        BinderTransport.transactBool(TxCode.WINDOW_LIFECYCLE_ACTION) {
            writeInt(taskId)
            writeString(pkg.orEmpty())
            writeInt(action.coerceIn(WIN_ACTION_FOCUS, WIN_ACTION_TOGGLE_MAXIMIZE))
        }

    fun setDesktopPrefs(taskbarAutoHide: Boolean, defaultLaunchMode: String? = null): Boolean =
        BinderTransport.transactBool(TxCode.SET_DESKTOP_PREFS) {
            writeInt(if (taskbarAutoHide) 1 else 0)
            if (defaultLaunchMode != null) writeString(defaultLaunchMode)
        }

    fun setAdaptiveSingleConfig(enabled: Boolean, defaultWidthDp: Int): Boolean =
        BinderTransport.transactBool(TxCode.SET_ADAPTIVE_SINGLE_CONFIG) {
            writeInt(if (enabled) 1 else 0)
            writeInt(DisplayGeometry.clampSingleWidthDp(defaultWidthDp))
        }

    fun setInputBackend(mode: Int): Int =
        BinderTransport.transactInt(TxCode.SET_INPUT_BACKEND) {
            writeInt(mode.coerceIn(InputBackend.AUTO, INPUT_BACKEND_UHID))
        }

    // --- Core Daemon Control IPC ---

    fun toggleInput(): Int = BinderTransport.transactInt(TxCode.TOGGLE_INPUT)

    fun sendBack() = BinderTransport.transactOneWay(TxCode.SEND_BACK)

    fun relaunchLauncher() = BinderTransport.transactOneWay(TxCode.RELAUNCH_LAUNCHER)

    fun rebuild() = BinderTransport.transactOneWay(TxCode.REBUILD)

    fun applyConfigDetailed(
        w: Int,
        h: Int,
        dpi: Int,
        fps: Float,
        physModeId: Int,
        rot: Int,
    ): ApplyResult = BinderTransport.transactOrNull(
        code = TxCode.APPLY_CONFIG,
        write = {
            writeInt(w)
            writeInt(h)
            writeInt(dpi)
            writeInt((fps * 100f).roundToInt())
            writeInt(physModeId)
            writeInt(rot)
        },
        read = {
            val ok = readInt() == 1
            if (dataAvail() >= 16) {
                ApplyResult(
                    ok = ok,
                    physResult = readInt(),
                    physW = readInt(),
                    physH = readInt(),
                    physFps = readInt() / 100f,
                )
            } else {
                ApplyResult(ok = ok)
            }
        },
    ) ?: ApplyResult()

    fun shutdownDaemon(@Suppress("UNUSED_PARAMETER") ctx: Context?) {
        BinderTransport.sendCtlSocketCmdAsync("SHUTDOWN")
        BinderTransport.transactVoid(TxCode.SHUTDOWN)
        BinderTransport.clearBinder()
    }

    fun sendMouse(actionType: Int, a1: Float, a2: Float): IntArray? = BinderTransport.transactOrNull(
        code = TxCode.SEND_MOUSE,
        write = {
            writeInt(actionType)
            writeFloat(a1)
            writeFloat(a2)
        },
        read = {
            if (dataAvail() < 8) return@transactOrNull null
            val cx = readInt()
            val cy = readInt()
            val inj = if (dataAvail() >= 4) readInt() else 1
            intArrayOf(cx, cy, inj)
        },
    )

    fun sendKey(keyCode: Int, metaState: Int) = BinderTransport.transactVoid(TxCode.SEND_KEY) {
        writeInt(KeyAction.KEY_EVENT)
        writeInt(keyCode)
        writeInt(metaState)
        writeString(null)
    }

    fun sendAscii(txt: String?) = BinderTransport.transactVoid(TxCode.SEND_KEY) {
        writeInt(KeyAction.ASCII_TEXT)
        writeInt(0)
        writeInt(0)
        writeString(txt)
    }

    fun sendPaste(useCtrlV: Boolean) = BinderTransport.transactVoid(TxCode.SEND_KEY) {
        writeInt(KeyAction.PASTE)
        writeInt(if (useCtrlV) 1 else 0)
        writeInt(0)
        writeString(null)
    }

    fun launchComponent(pkg: String?, cls: String?): Boolean =
        BinderTransport.transactBool(TxCode.LAUNCH_COMPONENT) {
            writeString(pkg)
            writeString(cls)
        }

    fun getWallpaperBitmap(force: Boolean): Bitmap? {
        val cached = cachedWallpaper
        if (!force && cached != null && !cached.isRecycled) return cached
        val decoded = BinderTransport.transactOrNull(
            code = TxCode.GET_WALLPAPER,
            write = { writeInt(if (force) 1 else 0) },
            read = {
                val bytes = createByteArray()
                if (bytes != null && bytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
            },
        )
        if (decoded != null) {
            cachedWallpaper = decoded
            return decoded
        }
        return cachedWallpaper
    }

    fun getTasks(): List<TaskEntry> = BinderTransport.transactOrNull(
        code = TxCode.GET_TASKS,
        read = {
            val n = readInt().coerceIn(0, 20)
            buildList(n) {
                repeat(n) {
                    val taskId = readInt()
                    val pkg = readString().orEmpty()
                    val flags = readInt()
                    if (pkg.isNotEmpty()) add(TaskEntry(taskId = taskId, pkg = pkg, flags = flags))
                }
            }
        },
    ).orEmpty()

    fun switchToTask(taskId: Int, pkg: String?): Boolean =
        BinderTransport.transactBool(TxCode.SWITCH_TO_TASK) {
            writeInt(taskId)
            writeString(pkg)
        }

    fun minimizeToExtHome(taskId: Int, pkg: String?): Boolean =
        BinderTransport.transactBool(TxCode.MINIMIZE_TO_EXT_HOME) {
            writeInt(taskId)
            writeString(pkg)
        }

    fun removeTask(taskId: Int): Boolean =
        BinderTransport.transactBool(TxCode.REMOVE_TASK) { writeInt(taskId) }

    fun getSysStatus(): SysStatus {
        val sys = BinderTransport.transactOrNull(
            code = TxCode.GET_SYS_STATUS,
            read = {
                SysStatus(
                    valid = true,
                    wifiOn = readInt() == 1,
                    wifiSsid = readString().orEmpty(),
                    btOn = readInt() == 1,
                    btName = readString().orEmpty(),
                    airplaneOn = readInt() == 1,
                    powerSaveOn = readInt() == 1,
                    screenDimmed = dataAvail() >= 4 && readInt() == 1,
                )
            },
        ) ?: SysStatus()
        if (sys.valid) DaemonStateStore.updateSysStatus(sys)
        return sys
    }

    fun execSysAction(act: Int, arg: Int, str: String?): Boolean =
        BinderTransport.transactBool(TxCode.EXEC_SYS_ACTION) {
            writeInt(act)
            writeInt(arg)
            writeString(str)
        }

    fun setMirrorMode(mode: Int): Int =
        BinderTransport.transactInt(TxCode.SET_MIRROR_MODE) { writeInt(mode) }

    fun setFullscreenPolicy(enable: Int): Int =
        BinderTransport.transactInt(TxCode.SET_FULLSCREEN_POLICY) { writeInt(enable) }

    fun toggleFullscreenPolicy(): Int = setFullscreenPolicy(-1)

    fun setRotation(rot: Int): Int =
        BinderTransport.transactInt(TxCode.SET_ROTATION) { writeInt(rot) }

    fun cycleRotation(): Int = setRotation(-1)

    fun prepareAppLaunch(pkg: String?): Int {
        if (pkg.isNullOrEmpty()) return 0
        return BinderTransport.transactInt(TxCode.PREPARE_APP_LAUNCH, defaultValue = 0) { writeString(pkg) }
    }

    fun getAllAppModes(): Map<String, Int> = BinderTransport.transactOrNull(
        code = TxCode.GET_ALL_APP_MODES,
        read = { BridgeParsers.parseAppModes(readString()) },
    ).orEmpty()

    fun setAppMode(pkg: String?, mode: Int, restart: Boolean): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return BinderTransport.transactBool(TxCode.SET_APP_MODE) {
            writeString(pkg)
            writeInt(mode)
            writeInt(if (restart) 1 else 0)
        }
    }

    fun resetAllAppModes(): Boolean = BinderTransport.transactBool(TxCode.RESET_ALL_APP_MODES)

    fun splitActionDetailed(action: Int, arg: Int, pkg: String?, cls: String?): SplitResult =
        BinderTransport.transactOrNull(
            code = TxCode.SPLIT_ACTION,
            write = {
                writeInt(action)
                writeInt(arg)
                writeString(pkg.orEmpty())
                writeString(cls.orEmpty())
            },
            read = {
                val ok = readInt() == 1
                if (dataAvail() >= 8) {
                    SplitResult(
                        ok = ok,
                        splitActive = readInt() == 1,
                        ratioPct = readInt(),
                        slot0Pkg = readString().orEmpty(),
                        slot1Pkg = readString().orEmpty(),
                    )
                } else {
                    SplitResult(ok = ok)
                }
            },
        ) ?: SplitResult()

    fun splitAction(action: Int, arg: Int, pkg: String?, cls: String?): Boolean =
        splitActionDetailed(action, arg, pkg, cls).ok

    fun assignSplitSlot(slot: Int, pkg: String?, cls: String?): Boolean =
        splitAction(if (slot == 0) SPLIT_ASSIGN_LEFT_TOP else SPLIT_ASSIGN_RIGHT_BOTTOM, -1, pkg, cls)

    fun setSplitRatio(ratioPct: Int): Boolean =
        splitAction(SplitAction.SET_RATIO, ratioPct.coerceIn(25, 75), null, null)

    fun swapSplitSlots(): Boolean = splitAction(SplitAction.SWAP, 0, null, null)

    fun exitSplitMode(): Boolean = splitAction(SplitAction.EXIT, 0, null, null)

    fun setMirrorSideMode(mode: Int): Int =
        BinderTransport.transactInt(TxCode.SET_MIRROR_SIDE_MODE) { writeInt(mode) }

    fun getMirrorTelemetry(): MirrorTelemetry = BinderTransport.transactOrNull(
        code = TxCode.GET_MIRROR_TELEMETRY,
        read = {
            val ar = readString()
            val ad = readString().orEmpty()
            val mp = readString().orEmpty()
            val ms = readInt()
            MirrorTelemetry(
                valid = true,
                audioRoute = if (!ar.isNullOrEmpty()) ar else "平板外放",
                audioDetail = ad,
                mediaPkg = mp,
                mediaState = ms,
                mediaTitle = readString().orEmpty(),
                mediaSubtitle = readString().orEmpty(),
                calendarText = readString().orEmpty(),
                battDetail = readString().orEmpty(),
                wifiDetail = readString().orEmpty(),
            )
        },
    ) ?: MirrorTelemetry()

    fun ensureAppWidgetPermission(): Boolean =
        BinderTransport.transactBool(TxCode.ENSURE_APPWIDGET_PERMISSION)

    fun sendSideTouch(actionMasked: Int, sx: Float, sy: Float) =
        BinderTransport.transactVoid(TxCode.SEND_SIDE_TOUCH) {
            writeInt(actionMasked)
            writeFloat(sx)
            writeFloat(sy)
        }

    fun sendCapturedMirrorMouse(actionType: Int, a1: Float, a2: Float, a3: Float): IntArray =
        BinderTransport.transactOrNull(
            code = TxCode.SEND_CAPTURED_MIRROR_MOUSE,
            write = {
                writeInt(actionType)
                writeFloat(a1)
                writeFloat(a2)
                writeFloat(a3)
            },
            read = { intArrayOf(readInt(), readInt(), readInt()) },
        ) ?: intArrayOf(0, 0, 0)

    fun notifyMirrorEdgeHover(px: Float, py: Float): IntArray =
        BinderTransport.transactOrNull(
            code = TxCode.NOTIFY_MIRROR_EDGE_HOVER,
            write = {
                writeFloat(px)
                writeFloat(py)
            },
            read = { intArrayOf(readInt(), readInt(), readInt()) },
        ) ?: intArrayOf(0, 0, 0)

    fun captureTaskSnapshot(taskId: Int, pkg: String?, live: Boolean): TaskSnapshotData =
        BridgeParcelCodecs.captureTaskSnapshot(taskId, pkg, live)

    // --- Pure Delegators to protocol/ & prefs/ (Zero Duplicated Logic) ---

    fun isRemoteDisplayActive(sinkType: Int): Boolean = SinkType.isRemote(sinkType)
    fun isTilingTopology(topology: String?): Boolean = LayoutMode.isTilingTopology(topology)
    fun layoutModeLabel(mode: String?): String = BridgeLabels.layoutModeLabel(mode)
    fun injectStatusLabel(injectStatus: Int): String = BridgeLabels.injectStatusLabel(injectStatus)
    fun inputBackendLabel(mode: Int): String = BridgeLabels.inputBackendLabel(mode)
    fun pxToDp(px: Int, dpi: Int): Int = DisplayGeometry.pxToDp(px, dpi)
    fun dpToPx(dpVal: Int, dpi: Int): Int = DisplayGeometry.dpToPx(dpVal, dpi)
    fun classifyWidthPresetLabel(widthDp: Int, isFullscreen: Boolean): String =
        BridgeLabels.classifyWidthPresetLabel(widthDp, isFullscreen)
    fun snapWidthDp(rawWidthDp: Int, fullWidthDp: Int): Pair<Int, Boolean> =
        DisplayGeometry.snapWidthDp(rawWidthDp, fullWidthDp)
    fun computeCenteredSingleBoundsPx(
        displayW: Int,
        displayH: Int,
        dpi: Int,
        widthDp: Int,
        dockReservePx: Int = 0,
    ): IntArray = DisplayGeometry.computeCenteredSingleBoundsPx(displayW, displayH, dpi, widthDp, dockReservePx)
    fun normalizeTilingRatios(topology: String?, rawRatios: List<Int>): List<Int> =
        DisplayGeometry.normalizeTilingRatios(topology, rawRatios)
    fun parseWindowStateSnapshot(raw: String?): WindowStateSnapshot =
        BridgeParsers.parseWindowStateSnapshot(raw)
    fun parseTrustedMacClients(raw: String?): List<TrustedMacClient> =
        BridgeParsers.parseTrustedMacClients(raw)
    fun parseAppModes(raw: String?): Map<String, Int> = BridgeParsers.parseAppModes(raw)
    fun parseEdidModes(modesRaw: String?): List<EdidMode> = BridgeParsers.parseEdidModes(modesRaw)
    fun sinkTypeLabel(sinkType: Int): String = BridgeLabels.sinkTypeLabel(sinkType)
    fun formatShortFingerprint(fp: String?): String = BridgeLabels.formatShortFingerprint(fp)
    fun formatBitrateMbps(bitrateKbps: Int): String = BridgeLabels.formatBitrateMbps(bitrateKbps)
    fun formatStreamFps(fpsX100: Int): String = BridgeLabels.formatStreamFps(fpsX100)
    fun computeOverlayDpi(virtW: Int, virtH: Int, reqDpi: Int): Int =
        DisplayGeometry.computeOverlayDpi(virtW, virtH, reqDpi)
    fun computeSmallestWidthDp(virtW: Int, virtH: Int, dpi: Int): Int =
        DisplayGeometry.computeSmallestWidthDp(virtW, virtH, dpi)
    fun recommendDpiForResolution(width: Int, height: Int): Int =
        DisplayGeometry.recommendDpiForResolution(width, height)
    fun alignResolution16(value: Int, minVal: Int = 640, maxVal: Int = 7680): Int =
        DisplayGeometry.alignResolution16(value, minVal, maxVal)
    fun mirrorModeLabel(mode: Int): String = BridgeLabels.mirrorModeLabel(mode)
    fun rotationLabel(rot: Int): String = BridgeLabels.rotationLabel(rot)
    fun appModeLabel(mode: Int): String = BridgeLabels.appModeLabel(mode)
    fun appModeBadge(mode: Int): String = BridgeLabels.appModeBadge(mode)
    fun resolveMirrorTheme(id: String?, customBg: Int): MirrorTheme =
        MirrorThemeCatalog.resolveMirrorTheme(id, customBg)
    fun mirrorPresetIndex(key: String?): Int = MirrorThemeCatalog.mirrorPresetIndex(key)
    fun mirrorPresetTitle(key: String?): String = MirrorThemeCatalog.mirrorPresetTitle(key)
    fun mirrorSideModeLabel(sideMode: Int): String = BridgeLabels.mirrorSideModeLabel(sideMode)
}
