package io.github.lengsukq.opaldesk.daemon.ipc;

/**
 * Single source of truth for the OpalDesk daemon Binder IPC wire protocol,
 * LocalSocket control channel, Helper IPC contracts, Android windowing modes,
 * and action constants.
 *
 * <p>Must remain ABI-aligned with {@code io.github.lengsukq.opaldesk.protocol.BridgeProtocol}.
 */
public final class BridgeProtocol {
    // ---- Binder, LocalSocket & Helper IPC identifiers ----
    public static final String BINDER_DESC = "io.github.lengsukq.opaldesk.daemon.IBridgeControl";
    public static final String CTL_SOCKET_NAME = "io.github.lengsukq.opaldesk.daemon.ctl";
    public static final String ACTION_NEED_BINDER = "io.github.lengsukq.opaldesk.daemon.NEED_BINDER";

    public static final String HELPER_PROVIDER_AUTHORITY = "io.github.lengsukq.opaldesk.provider";
    public static final String HELPER_ACTION_BINDER_PUSH = "io.github.lengsukq.opaldesk.BINDER_PUSH";
    public static final String HELPER_LAUNCHER_CLASS = "io.github.lengsukq.opaldesk.LauncherActivity";
    public static final String HELPER_SERVICE_CLASS = "io.github.lengsukq.opaldesk.BridgeService";
    public static final String HELPER_CONTROL_RECEIVER_CLASS = "io.github.lengsukq.opaldesk.ControlReceiver";

    public static final String PROVIDER_METHOD_SET_BINDER = "setBinder";
    public static final String PROVIDER_METHOD_SYNC_STATE = "syncState";
    public static final String PROVIDER_METHOD_MIR_EDGE_CAPTURE = "mirEdgeCapture";

    public static final String CTL_CMD_SHUTDOWN = "SHUTDOWN";
    public static final String CTL_CMD_NEED_BINDER = "NEED_BINDER";
    public static final String CTL_REPLY_DONE = "DONE";
    public static final String CTL_REPLY_OK = "OK";

    // ---- Binder Transaction Codes (active ABI; obsolete codes 13, 14, 19, 36 removed) ----
    public static final int TX_TOGGLE_INPUT = 10;
    public static final int TX_SEND_BACK = 11;
    public static final int TX_RELAUNCH_LAUNCHER = 12;
    public static final int TX_GET_STATUS = 15;
    public static final int TX_APPLY_CONFIG = 16;
    public static final int TX_SEND_MOUSE = 17;
    public static final int TX_SEND_KEY = 18;
    public static final int TX_LAUNCH_COMPONENT = 20;
    public static final int TX_REBUILD = 21;
    public static final int TX_GET_WALLPAPER = 22;
    public static final int TX_GET_TASKS = 23;
    public static final int TX_SWITCH_TO_TASK = 24;
    public static final int TX_REMOVE_TASK = 25;
    public static final int TX_GET_SYS_STATUS = 26;
    public static final int TX_EXEC_SYS_ACTION = 27;
    public static final int TX_SET_MIRROR_MODE = 28;
    public static final int TX_SET_FULLSCREEN_POLICY = 29;
    public static final int TX_PREPARE_APP_LAUNCH = 30;
    public static final int TX_GET_ALL_APP_MODES = 31;
    public static final int TX_SET_APP_MODE = 32;
    public static final int TX_RESET_ALL_APP_MODES = 33;
    public static final int TX_SET_ROTATION = 34;
    public static final int TX_SPLIT_ACTION = 35;
    public static final int TX_MINIMIZE_TO_EXT_HOME = 37;
    public static final int TX_SET_MIRROR_SIDE_MODE = 38;
    public static final int TX_GET_MIRROR_TELEMETRY = 39;
    public static final int TX_ENSURE_APPWIDGET_PERMISSION = 40;
    public static final int TX_SEND_SIDE_TOUCH = 41;
    public static final int TX_SEND_CAPTURED_MIRROR_MOUSE = 42;
    public static final int TX_NOTIFY_MIRROR_EDGE_HOVER = 43;
    public static final int TX_CAPTURE_TASK_SNAPSHOT = 44;
    public static final int TX_SET_WIFI_REMOTE_CONFIG = 45;
    public static final int TX_LIST_TRUSTED_MAC_CLIENTS = 46;
    public static final int TX_REVOKE_TRUSTED_MAC_CLIENT = 47;
    public static final int TX_DISCONNECT_REMOTE_CLIENT = 48;
    public static final int TX_SET_REMOTE_STREAM_PREFS = 49;
    public static final int TX_GET_MANAGED_WINDOWS = 50;
    public static final int TX_LAUNCH_IN_WINDOW_MODE = 51;
    public static final int TX_UPDATE_WINDOW_BOUNDS = 52;
    public static final int TX_SET_TILING_LAYOUT = 53;
    public static final int TX_WINDOW_LIFECYCLE_ACTION = 54;
    public static final int TX_SET_ADAPTIVE_SINGLE_CONFIG = 55;
    public static final int TX_SET_INPUT_BACKEND = 56;
    public static final int TX_SET_DESKTOP_PREFS = 57;
    public static final int TX_START_LOCAL_DEX = 58;
    public static final int TX_STOP_LOCAL_DEX = 59;
    public static final int TX_SEND_LOCAL_TOUCH = 60;
    public static final int TX_MOVE_WINDOW_LIVE = 61;
    public static final int TX_SHUTDOWN = 99;

    // ---- Push-Sync Bundle Extras (PROVIDER_METHOD_SYNC_STATE) ----
    public static final String EXTRA_SYNC_WIN_JSON = "winJson";
    public static final String EXTRA_SYNC_STATE_SEQ = "stateSeq";
    public static final String EXTRA_SYNC_SHORTCUT = "desktopShortcut";

    // ---- Global Desktop Shortcut Action Tokens ----
    public static final String SHORTCUT_WINDOW_SWITCHER = "window_switcher";
    public static final String SHORTCUT_APP_DRAWER = "app_drawer";
    public static final String SHORTCUT_SHOW_DESKTOP = "show_desktop";
    public static final String SHORTCUT_SNAP_LEFT = "snap_left";
    public static final String SHORTCUT_SNAP_RIGHT = "snap_right";
    public static final String SHORTCUT_MAXIMIZE_TOGGLE = "maximize_toggle";
    public static final String SHORTCUT_MINIMIZE_OR_RESTORE = "minimize_or_restore";
    public static final String SHORTCUT_CLOSE_FOCUSED = "close_focused";
    public static final String SHORTCUT_DISMISS_OVERLAY = "dismiss_overlay";

    // ---- Android WindowConfiguration Windowing Modes ----
    public static final int WINDOWING_MODE_UNDEFINED = 0;
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    public static final int WINDOWING_MODE_FREEFORM = 5;
    public static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    // ---- VirtualDisplay Flag Bitmasks ----
    /**
     * Legacy {@code VIRTUAL_DISPLAY_FLAG_PRESENTATION} bit ({@code 0x0002}); stripped by default so
     * video apps running in standalone windows do not auto-cast Presentation ("扩展播放中") onto
     * the main desktop display.
     */
    public static final int VD_FLAG_PRESENTATION = 0x0002;
    /** Includes ROTATES_WITH_CONTENT (0x0080) for compatibility mode; excludes PRESENTATION. */
    public static final int VD_FLAGS_COMPAT = 0x44C9;
    /** Strips ROTATES_WITH_CONTENT and PRESENTATION so DeX desktop remains locked landscape. */
    public static final int VD_FLAGS_DEX_FIXED = 0x4449;

    // ---- Mirror Modes & Side Modes ----
    public static final int MIRROR_MODE_DEX = 0;
    public static final int MIRROR_MODE_MIRROR = 1;

    public static final int MIRROR_SIDE_OFF = 0;
    public static final int MIRROR_SIDE_ALIGN_LEFT = 1;
    public static final int MIRROR_SIDE_ALIGN_RIGHT = 2;
    public static final int MIRROR_SIDE_CENTER_DUAL = 3;

    // ---- Per-App Compat Modes ----
    public static final int APP_MODE_DEFAULT = 0;
    public static final int APP_MODE_LAND_FULL = 1;
    public static final int APP_MODE_PORT_COMPAT = 2;

    // ---- Input Backend & Inject Status ----
    public static final int INPUT_BACKEND_AUTO = 0;
    public static final int INPUT_BACKEND_INJECT = 1;
    public static final int INPUT_BACKEND_UHID = 2;

    public static final int INJECT_STATUS_SYSTEM = 1;
    public static final int INJECT_STATUS_UHID = 2;
    public static final int INJECT_STATUS_BLOCKED = -1;
    public static final int INJECT_STATUS_FAILED = -2;

    // ---- Binder Code 17 Mouse Actions ----
    public static final int MOUSE_MOVE_REL = 0;
    public static final int MOUSE_LEFT_CLICK = 1;
    public static final int MOUSE_RIGHT_CLICK = 2;
    public static final int MOUSE_LEFT_DOWN = 3;
    public static final int MOUSE_LEFT_UP = 4;
    public static final int MOUSE_SCROLL = 5;
    public static final int MOUSE_MOVE_ABS = 7;

    // ---- Binder Code 18 Key Sub-Actions ----
    public static final int KEY_ACTION_EVENT = 0;
    public static final int KEY_ACTION_ASCII = 1;
    public static final int KEY_ACTION_PASTE = 2;

    // ---- Binder Code 42 Captured Mirror Mouse Actions ----
    public static final int CAPTURED_MOVE_REL = 0;
    public static final int CAPTURED_LEFT_DOWN = 1;
    public static final int CAPTURED_LEFT_UP = 2;
    public static final int CAPTURED_SCROLL = 4;
    public static final int CAPTURED_EXIT = 5;
    public static final int CAPTURED_EXTRA_BTN_DOWN = 6;
    public static final int CAPTURED_EXTRA_BTN_UP = 7;
    public static final int CAPTURED_REASSERT_D0_FOCUS = 9;

    // ---- Binder Code 35 Legacy 2-Slot Split Actions ----
    public static final int SPLIT_ASSIGN_LEFT_TOP = 1;
    public static final int SPLIT_ASSIGN_RIGHT_BOTTOM = 2;
    public static final int SPLIT_TOGGLE_QUICK = 3;
    public static final int SPLIT_SET_RATIO = 4;
    public static final int SPLIT_SWAP = 5;
    public static final int SPLIT_EXIT = 6;

    // ---- Binder Code 27 System Actions ----
    public static final int SYS_ACTION_WIFI = 1;
    public static final int SYS_ACTION_BT = 2;
    public static final int SYS_ACTION_AIRPLANE = 3;
    public static final int SYS_ACTION_POWER_SAVE = 4;
    public static final int SYS_ACTION_OPEN_SETTINGS = 5;
    public static final int SYS_ACTION_SCREEN_DIM = 6;

    // ---- Binder Code 54 Window Lifecycle Actions ----
    public static final int WIN_ACTION_FOCUS = 1;
    public static final int WIN_ACTION_MINIMIZE = 2;
    public static final int WIN_ACTION_CLOSE = 3;
    public static final int WIN_ACTION_RELOAD_DPI = 4;
    public static final int WIN_ACTION_SEND_BACK = 5;
    public static final int WIN_ACTION_TOGGLE_MAXIMIZE = 6;

    private BridgeProtocol() {}
}
