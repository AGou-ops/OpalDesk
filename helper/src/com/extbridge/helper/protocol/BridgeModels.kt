package com.extbridge.helper.protocol

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max

/**
 * Immutable snapshot of ExtBridge daemon status (Binder code 15).
 * Immutability guarantees reliable structural equality for `StateFlow<DaemonStatus>`.
 */
data class DaemonStatus(
    val connected: Boolean = false,
    val extId: Int = -1,
    val vdId: Int = -1,
    val physW: Int = 0,
    val physH: Int = 0,
    val physFps: Float = 60f,
    val virtW: Int = 2560,
    val virtH: Int = 1440,
    val virtDpi: Int = 240,
    val virtFps: Float = 60f,
    val inputToExt: Int = 1,
    val cursorX: Int = 1280,
    val cursorY: Int = 720,
    val extInputCnt: Int = 0,
    val modesRaw: String = "",
    val renderMode: String = BridgeProtocol.LayoutMode.NONE,
    val mirrorMode: Int = BridgeProtocol.MirrorMode.DEX,
    val fullscreenPolicy: Int = 1,
    val virtRot: Int = 0,
    val injectStatus: Int = BridgeProtocol.InputBackend.INJECT_STATUS_SYSTEM,
    val splitActive: Int = 0,
    val splitRatioPct: Int = 50,
    val splitSlot0Pkg: String = "",
    val splitSlot1Pkg: String = "",
    val mirrorSideMode: Int = BridgeProtocol.MirrorSideMode.ALIGN_LEFT,
    val sideVdId: Int = -1,
    val d0W: Int = 0,
    val d0H: Int = 0,
    val mirX: Int = 0,
    val mirY: Int = 0,
    val mirW: Int = 0,
    val mirH: Int = 0,
    // Remote / Wireless Streaming Extension Fields (Binder code 15 tail)
    val sinkType: Int = BridgeProtocol.SinkType.NONE,
    val remotePeerName: String = "",
    val streamBitrateKbps: Int = 0,
    val streamFpsX100: Int = 0,
    val wifiServerEnabled: Int = 0,
    val wifiServerPort: Int = 0,
    val wifiCertFingerprint: String = "",
    val activePairingPin: String = "",
    // v4.1 Adaptive Window & Multi-Tiling Extension Fields (Binder code 15 tail)
    val adaptiveSingleEnabled: Int = 1,
    val defaultSingleWidthDp: Int = DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP,
    val activeLayoutMode: String = BridgeProtocol.LayoutMode.FULLSCREEN,
) {
    val isRemoteDisplayActive: Boolean
        get() = BridgeProtocol.SinkType.isRemote(sinkType)

    val isRemoteSink: Boolean
        get() = isRemoteDisplayActive

    val isRemoteStreaming: Boolean
        get() = connected && isRemoteDisplayActive

    val isAdaptiveSingleEnabled: Boolean
        get() = adaptiveSingleEnabled != 0

    val isUhidInputActive: Boolean
        get() = injectStatus == BridgeProtocol.InputBackend.INJECT_STATUS_UHID

    val isMultiTilingActive: Boolean
        get() = splitActive == 1 || BridgeProtocol.LayoutMode.isTilingTopology(activeLayoutMode)

    val isWifiServerEnabled: Boolean
        get() = wifiServerEnabled != 0

    val streamFps: Float
        get() = streamFpsX100 / 100f

    val shortCertFingerprint: String
        get() = BridgeLabels.formatShortFingerprint(wifiCertFingerprint)
}

typealias Status = DaemonStatus

data class TaskEntry(
    val taskId: Int = 0,
    val pkg: String = "",
    val flags: Int = 0,
) {
    fun onExt(): Boolean = (flags and 1) != 0
    fun isRunning(): Boolean = (flags and 2) != 0
    fun isTopExt(): Boolean = (flags and 4) != 0
    fun isFreeform(): Boolean = (flags and 8) != 0
    fun isInSplit(): Boolean = (flags and 16) != 0
}

data class SysStatus(
    val valid: Boolean = false,
    val wifiOn: Boolean = false,
    val wifiSsid: String = "",
    val btOn: Boolean = false,
    val btName: String = "",
    val airplaneOn: Boolean = false,
    val powerSaveOn: Boolean = false,
    val screenDimmed: Boolean = false,
)

data class MirrorTelemetry(
    val valid: Boolean = false,
    val audioRoute: String = "平板外放",
    val audioDetail: String = "",
    val mediaPkg: String = "",
    val mediaState: Int = 0, // 0=none, 1=stopped, 2=paused, 3=playing
    val mediaTitle: String = "",
    val mediaSubtitle: String = "",
    val calendarText: String = "",
    val battDetail: String = "",
    val wifiDetail: String = "",
)

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
    val presentation: String = if (mode.equals(BridgeProtocol.LayoutMode.FULLSCREEN, ignoreCase = true)) {
        BridgeProtocol.WindowPresentation.IMMERSIVE
    } else {
        BridgeProtocol.WindowPresentation.FLOATING
    },
    val restoreLeft: Int = 0,
    val restoreTop: Int = 0,
    val restoreRight: Int = 0,
    val restoreBottom: Int = 0,
) {
    val widthPx: Int get() = max(0, right - left)
    val heightPx: Int get() = max(0, bottom - top)
    val hasRestoreBounds: Boolean get() = restoreRight > restoreLeft + 120 && restoreBottom > restoreTop + 120
    fun toRect(): Rect = Rect(left, top, right, bottom)
    fun toRestoreRectOrNull(): Rect? = if (hasRestoreBounds) Rect(restoreLeft, restoreTop, restoreRight, restoreBottom) else null
}

data class WindowStateSnapshot(
    val layoutMode: String = BridgeProtocol.LayoutMode.FULLSCREEN,
    val adaptiveSingleEnabled: Boolean = true,
    val defaultSingleWidthDp: Int = DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP,
    val tilingTopology: String = BridgeProtocol.LayoutMode.NONE,
    val ratios: List<Int> = emptyList(),
    val windows: List<ManagedWindowInfo> = emptyList(),
    val stateSeq: Long = 0L,
    val taskbarAutoHide: Boolean = false,
)

data class ApplyResult(
    val ok: Boolean = false,
    val physResult: Int = 0, // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked
    val physW: Int = 0,
    val physH: Int = 0,
    val physFps: Float = 0f,
)

data class SplitResult(
    val ok: Boolean = false,
    val splitActive: Boolean = false,
    val ratioPct: Int = 50,
    val slot0Pkg: String = "",
    val slot1Pkg: String = "",
)

data class TaskSnapshotData(
    val bitmap: Bitmap? = null,
    val bounds: Rect? = null,
    val ownBitmap: Boolean = false,
)

data class EdidMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val fps: Float,
)

data class TrustedMacClient(
    val clientId: String,
    val clientName: String,
)

data class WifiRemoteConfigResult(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val port: Int = 0,
    val certFingerprint: String = "",
    val pin: String = "",
)

/**
 * Strongly typed mirror sidebar wing geometry replacing legacy 9-element raw `IntArray`.
 */
data class MirrorWingMeta(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val physWidth: Int,
    val physHeight: Int,
    val sideVdId: Int,
    val hasDual: Boolean,
    val sideMode: Int,
)
