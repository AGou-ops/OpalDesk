package io.github.lengsukq.opaldesk.protocol

import android.os.IBinder
import android.os.Process
import android.util.Log
import java.util.Locale

/**
 * Single source of truth for the OpalDesk daemon Binder/socket/broadcast wire protocol
 * and IPC caller trust verification.
 */
object BridgeProtocol {
    const val BINDER_DESC = "io.github.lengsukq.opaldesk.daemon.IBridgeControl"
    const val CTL_SOCKET_NAME = "io.github.lengsukq.opaldesk.daemon.ctl"
    const val ACTION_NEED_BINDER = "io.github.lengsukq.opaldesk.daemon.NEED_BINDER"

    object TxCode {
        const val TOGGLE_INPUT = 10
        const val SEND_BACK = 11
        const val RELAUNCH_LAUNCHER = 12
        const val GET_STATUS = 15
        const val APPLY_CONFIG = 16
        const val SEND_MOUSE = 17
        const val SEND_KEY = 18
        const val LAUNCH_COMPONENT = 20
        const val REBUILD = 21
        const val GET_WALLPAPER = 22
        const val GET_TASKS = 23
        const val SWITCH_TO_TASK = 24
        const val REMOVE_TASK = 25
        const val GET_SYS_STATUS = 26
        const val EXEC_SYS_ACTION = 27
        const val SET_MIRROR_MODE = 28
        const val SET_FULLSCREEN_POLICY = 29
        const val PREPARE_APP_LAUNCH = 30
        const val GET_ALL_APP_MODES = 31
        const val SET_APP_MODE = 32
        const val RESET_ALL_APP_MODES = 33
        const val SET_ROTATION = 34
        const val SPLIT_ACTION = 35
        const val MINIMIZE_TO_EXT_HOME = 37
        const val SET_MIRROR_SIDE_MODE = 38
        const val GET_MIRROR_TELEMETRY = 39
        const val ENSURE_APPWIDGET_PERMISSION = 40
        const val SEND_SIDE_TOUCH = 41
        const val SEND_CAPTURED_MIRROR_MOUSE = 42
        const val NOTIFY_MIRROR_EDGE_HOVER = 43
        const val CAPTURE_TASK_SNAPSHOT = 44
        const val SET_WIFI_REMOTE_CONFIG = 45
        const val LIST_TRUSTED_MAC_CLIENTS = 46
        const val REVOKE_TRUSTED_MAC_CLIENT = 47
        const val DISCONNECT_REMOTE_CLIENT = 48
        const val SET_REMOTE_STREAM_PREFS = 49
        const val GET_MANAGED_WINDOWS = 50
        const val LAUNCH_IN_WINDOW_MODE = 51
        const val UPDATE_WINDOW_BOUNDS = 52
        const val SET_TILING_LAYOUT = 53
        const val WINDOW_LIFECYCLE_ACTION = 54
        const val SET_ADAPTIVE_SINGLE_CONFIG = 55
        const val SET_INPUT_BACKEND = 56
        const val SET_DESKTOP_PREFS = 57
        const val START_LOCAL_DEX = 58
        const val STOP_LOCAL_DEX = 59
        const val SEND_LOCAL_TOUCH = 60
        const val MOVE_WINDOW_LIVE = 61
        const val SHUTDOWN = 99
    }

    object MouseAction {
        const val MOVE_REL = 0
        const val LEFT_CLICK = 1
        const val RIGHT_CLICK = 2
        const val LEFT_DOWN = 3
        const val LEFT_UP = 4
        const val SCROLL = 5
        const val MOVE_ABS = 7
    }

    object KeyAction {
        const val KEY_EVENT = 0
        const val ASCII_TEXT = 1
        const val PASTE = 2
    }

    object CapturedMouseAction {
        const val MOVE_REL = 0
        const val LEFT_DOWN = 1
        const val LEFT_UP = 2
        const val SCROLL = 4
        const val EXIT_CAPTURE = 5
        const val EXTRA_BUTTON_DOWN = 6
        const val EXTRA_BUTTON_UP = 7
        const val REASSERT_D0_FOCUS = 9
    }

    object SysAction {
        const val WIFI = 1
        const val BT = 2
        const val AIRPLANE = 3
        const val POWER_SAVE = 4
        const val OPEN_SETTINGS = 5
        const val SCREEN_DIM = 6
    }

    object SinkType {
        const val NONE = 0
        const val PHYSICAL = 1
        const val REMOTE_USB = 2
        const val REMOTE_WIFI = 3
        const val REMOTE_WIFI_ADB = 4
        const val LOCAL = 5

        fun isRemote(sinkType: Int): Boolean =
            sinkType == REMOTE_USB || sinkType == REMOTE_WIFI || sinkType == REMOTE_WIFI_ADB

        fun isLocal(sinkType: Int): Boolean = sinkType == LOCAL
    }

    object WindowMode {
        const val AUTO = 0
        const val FULLSCREEN = 1
        const val ADAPTIVE_SINGLE = 2
        const val FREEFORM = 3
    }

    object WindowAction {
        const val FOCUS = 1
        const val MINIMIZE = 2
        const val CLOSE = 3
        const val RELOAD_DPI = 4
        const val SEND_BACK = 5
        const val TOGGLE_MAXIMIZE = 6
    }

    object WindowPresentation {
        const val FLOATING = "FLOATING"
        const val MAXIMIZED = "MAXIMIZED"
        const val SNAPPED_LEFT = "SNAPPED_LEFT"
        const val SNAPPED_RIGHT = "SNAPPED_RIGHT"
        const val SNAPPED_TL = "SNAPPED_TL"
        const val SNAPPED_TR = "SNAPPED_TR"
        const val SNAPPED_BL = "SNAPPED_BL"
        const val SNAPPED_BR = "SNAPPED_BR"
        const val IMMERSIVE = "IMMERSIVE"
    }

    object ShortcutAction {
        const val WINDOW_SWITCHER = "window_switcher"
        const val APP_DRAWER = "app_drawer"
        const val SHOW_DESKTOP = "show_desktop"
        const val SNAP_LEFT = "snap_left"
        const val SNAP_RIGHT = "snap_right"
        const val MAXIMIZE_TOGGLE = "maximize_toggle"
        const val MINIMIZE_OR_RESTORE = "minimize_or_restore"
        const val CLOSE_FOCUSED = "close_focused"
        const val DISMISS_OVERLAY = "dismiss_overlay"
    }

    object InputBackend {
        const val AUTO = 0
        const val INJECT_EVENTS = 1
        const val UHID = 2

        const val INJECT_STATUS_BLOCKED = -1
        const val INJECT_STATUS_SYSTEM = 1
        const val INJECT_STATUS_UHID = 2
    }

    object LayoutMode {
        const val NONE = "NONE"
        const val FULLSCREEN = "FULLSCREEN"
        const val ADAPTIVE_SINGLE = "ADAPTIVE_SINGLE"
        const val FREEFORM = "FREEFORM"
        const val SPLIT_2_H = "SPLIT_2_H"
        const val SPLIT_2_V = "SPLIT_2_V"
        const val SPLIT_3_MASTER = "SPLIT_3_MASTER"
        const val SPLIT_3_COLS = "SPLIT_3_COLS"
        const val GRID_2X2 = "GRID_2X2"
        const val EXIT = "EXIT"

        fun isTilingTopology(topology: String?): Boolean = when (topology?.uppercase(Locale.ROOT)) {
            SPLIT_2_H,
            SPLIT_2_V,
            SPLIT_3_MASTER,
            SPLIT_3_COLS,
            GRID_2X2,
            -> true
            else -> false
        }
    }

    object MirrorMode {
        const val DEX = 0
        const val MIRROR = 1
    }

    object MirrorSideMode {
        const val OFF = 0
        const val ALIGN_LEFT = 1
        const val ALIGN_RIGHT = 2
        const val CENTER_DUAL = 3
    }

    object AppMode {
        const val DEFAULT = 0
        const val LAND_FULL = 1
        const val PORT_COMPAT = 2
    }

    object SplitAction {
        const val ASSIGN_LEFT_TOP = 1
        const val ASSIGN_RIGHT_BOTTOM = 2
        const val TOGGLE_QUICK = 3
        const val SET_RATIO = 4
        const val SWAP = 5
        const val EXIT = 6
    }

    object RemoteStream {
        const val CODEC_AUTO_HEVC = 0
        const val CODEC_H264 = 1
        const val DEFAULT_WIFI_PORT = 19878
        const val MIN_BITRATE_KBPS = 2000
        const val MAX_BITRATE_KBPS = 50000
    }

    /**
     * All documented ADB/daemon broadcast actions handled by [io.github.lengsukq.opaldesk.ControlReceiver]
     * and ContentProvider IPC contracts exposed by [io.github.lengsukq.opaldesk.BridgeProvider].
     */
    object HelperActions {
        const val BINDER_PUSH = "io.github.lengsukq.opaldesk.BINDER_PUSH"
        const val TOGGLE_INPUT = "io.github.lengsukq.opaldesk.TOGGLE_INPUT"
        const val STATE_SYNC = "io.github.lengsukq.opaldesk.STATE_SYNC"
        const val REBUILD = "io.github.lengsukq.opaldesk.REBUILD"
        const val LAUNCH_EXT_HOME = "io.github.lengsukq.opaldesk.LAUNCH_EXT_HOME"
        const val TOGGLE_MIRROR = "io.github.lengsukq.opaldesk.TOGGLE_MIRROR"
        const val SET_MIRROR_SIDE_MODE = "io.github.lengsukq.opaldesk.SET_MIRROR_SIDE_MODE"
        const val SPLIT_ACTION = "io.github.lengsukq.opaldesk.SPLIT_ACTION"
        const val SET_ROTATION = "io.github.lengsukq.opaldesk.SET_ROTATION"
        const val SET_DOCK_AUTO_HIDE = "io.github.lengsukq.opaldesk.SET_DOCK_AUTO_HIDE"
        const val EXIT_ALL = "io.github.lengsukq.opaldesk.EXIT_ALL"
        const val DISCONNECT_REMOTE = "io.github.lengsukq.opaldesk.DISCONNECT_REMOTE"
        const val STOP_LOCAL_DEX = "io.github.lengsukq.opaldesk.STOP_LOCAL_DEX"

        const val PROVIDER_AUTHORITY = "io.github.lengsukq.opaldesk.provider"
        const val PROVIDER_METHOD_SET_BINDER = "setBinder"
        const val PROVIDER_METHOD_SYNC_STATE = "syncState"
        const val PROVIDER_METHOD_MIR_EDGE_CAPTURE = "mirEdgeCapture"
        const val EXTRA_CALLBACK_BINDER = "cb"
        const val EXTRA_PROC_TOKEN = "proc"
        const val EXTRA_EDGE_ENTER = "enter"
        const val EXTRA_SYNC_WIN_JSON = "winJson"
        const val EXTRA_SYNC_STATE_SEQ = "stateSeq"
        const val EXTRA_SYNC_SHORTCUT = "desktopShortcut"
    }
}

/**
 * Single source of truth for UID allowlisting (root/system/shell/self) and daemon IBinder validation.
 */
object IpcTrustVerifier {
    private const val TAG = "ExtIpcTrust"

    const val UID_ROOT = 0
    const val UID_SYSTEM = 1000
    const val UID_SHELL = 2000

    /** Privileged daemon peer check (root, system_server, or adb shell). */
    fun isTrustedDaemonUid(uid: Int): Boolean =
        uid == UID_ROOT || uid == UID_SYSTEM || uid == UID_SHELL

    /** Privileged daemon or same-app caller check for ContentProvider & broadcasts. */
    fun isTrustedCallerUid(uid: Int, selfUid: Int = Process.myUid()): Boolean =
        isTrustedDaemonUid(uid) || (selfUid >= 0 && uid == selfUid)

    /** Verifies liveness and exact Binder interface descriptor before accepting a daemon handle. */
    fun isValidDaemonBinder(binder: IBinder?): Boolean {
        if (binder == null || !binder.pingBinder()) return false
        return try {
            binder.interfaceDescriptor == BridgeProtocol.BINDER_DESC
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to verify daemon binder interfaceDescriptor", t)
            false
        }
    }
}
