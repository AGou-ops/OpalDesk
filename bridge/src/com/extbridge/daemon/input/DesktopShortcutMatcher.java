package com.extbridge.daemon.input;

import android.view.KeyEvent;

import com.extbridge.daemon.ipc.BridgeProtocol;

/**
 * Pure key-chord classifier mapping Samsung DeX / modern desktop global shortcuts to
 * {@link BridgeProtocol} shortcut tokens. Shared across local and remote keyboard bridges.
 */
public final class DesktopShortcutMatcher {
    private DesktopShortcutMatcher() {}

    /**
     * Returns a {@code BridgeProtocol.SHORTCUT_*} token when {@code (keyCode, metaState)} matches
     * a global DeX desktop shortcut, or {@code null} when the key should be forwarded normally.
     */
    public static String match(int keyCode, int metaState) {
        boolean meta = (metaState & (KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON)) != 0;
        boolean alt = (metaState & (KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON)) != 0;
        boolean ctrl = (metaState & (KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON)) != 0;

        if (keyCode == KeyEvent.KEYCODE_TAB && (alt || meta) && !ctrl) {
            return BridgeProtocol.SHORTCUT_WINDOW_SWITCHER;
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE && (meta || (alt && !ctrl))) {
            return BridgeProtocol.SHORTCUT_APP_DRAWER;
        }
        if (keyCode == KeyEvent.KEYCODE_D && meta && !alt && !ctrl) {
            return BridgeProtocol.SHORTCUT_SHOW_DESKTOP;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && meta && !ctrl) {
            return BridgeProtocol.SHORTCUT_SNAP_LEFT;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && meta && !ctrl) {
            return BridgeProtocol.SHORTCUT_SNAP_RIGHT;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && meta && !ctrl) {
            return BridgeProtocol.SHORTCUT_MAXIMIZE_TOGGLE;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && meta && !ctrl) {
            return BridgeProtocol.SHORTCUT_MINIMIZE_OR_RESTORE;
        }
        if ((keyCode == KeyEvent.KEYCODE_F4 && alt && !ctrl) || (keyCode == KeyEvent.KEYCODE_W && meta && !alt && !ctrl)) {
            return BridgeProtocol.SHORTCUT_CLOSE_FOCUSED;
        }
        return null;
    }
}
