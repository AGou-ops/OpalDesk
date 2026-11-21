package com.extbridge.daemon;

import android.view.KeyEvent;

import com.extbridge.daemon.input.DesktopShortcutMatcher;
import com.extbridge.daemon.ipc.BridgeProtocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DesktopShortcutMatcherTest {

    @Test
    public void testGlobalDeXShortcuts() {
        assertEquals(BridgeProtocol.SHORTCUT_WINDOW_SWITCHER,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON));
        assertEquals(BridgeProtocol.SHORTCUT_WINDOW_SWITCHER,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_TAB, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_APP_DRAWER,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_SPACE, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_SHOW_DESKTOP,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_SNAP_LEFT,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_SNAP_RIGHT,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_MAXIMIZE_TOGGLE,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_MINIMIZE_OR_RESTORE,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_META_ON));
        assertEquals(BridgeProtocol.SHORTCUT_CLOSE_FOCUSED,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON));
        assertEquals(BridgeProtocol.SHORTCUT_CLOSE_FOCUSED,
                DesktopShortcutMatcher.match(KeyEvent.KEYCODE_W, KeyEvent.META_META_ON));

        // Regular app shortcuts like Ctrl+W, Ctrl+D, plain Space must pass through untouched
        assertNull(DesktopShortcutMatcher.match(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_ON));
        assertNull(DesktopShortcutMatcher.match(KeyEvent.KEYCODE_D, KeyEvent.META_CTRL_ON));
        assertNull(DesktopShortcutMatcher.match(KeyEvent.KEYCODE_SPACE, 0));
        assertNull(DesktopShortcutMatcher.match(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON));
    }
}
