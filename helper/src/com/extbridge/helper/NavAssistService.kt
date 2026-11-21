package com.extbridge.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.extbridge.helper.protocol.BridgeProtocol.ShortcutAction

/**
 * Accessibility companion providing:
 * 1. `TYPE_ACCESSIBILITY_OVERLAY` window token for external display chrome above system bars.
 * 2. Real-time focus/window-change fallback notifications (`TYPE_WINDOW_STATE_CHANGED`).
 * 3. Physical USB/Bluetooth keyboard global DeX shortcut filtering when DeX desktop is active.
 */
class NavAssistService : AccessibilityService() {

    companion object {
        @Volatile
        private var sInstance: NavAssistService? = null

        @Volatile
        private var sOnStateChanged: Runnable? = null

        fun isRunning(): Boolean = sInstance != null

        fun getInstance(): NavAssistService? = sInstance

        fun setOnStateChanged(r: Runnable?) {
            sOnStateChanged = r
        }

        fun performHome() {
            sInstance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Pure matcher mirroring daemon `DesktopShortcutMatcher` for physical keyboards. */
        internal fun matchPhysicalShortcut(keyCode: Int, metaState: Int): String? {
            val alt = (metaState and KeyEvent.META_ALT_ON) != 0
            val meta = (metaState and KeyEvent.META_META_ON) != 0
            val ctrl = (metaState and KeyEvent.META_CTRL_ON) != 0

            if ((alt || meta) && keyCode == KeyEvent.KEYCODE_TAB) return ShortcutAction.WINDOW_SWITCHER
            if (keyCode == KeyEvent.KEYCODE_F3 && !ctrl) return ShortcutAction.WINDOW_SWITCHER
            if (alt && keyCode == KeyEvent.KEYCODE_F4) return ShortcutAction.CLOSE_FOCUSED
            if (meta && !alt && !ctrl) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_SPACE -> ShortcutAction.APP_DRAWER
                    KeyEvent.KEYCODE_D -> ShortcutAction.SHOW_DESKTOP
                    KeyEvent.KEYCODE_DPAD_LEFT -> ShortcutAction.SNAP_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> ShortcutAction.SNAP_RIGHT
                    KeyEvent.KEYCODE_DPAD_UP -> ShortcutAction.MAXIMIZE_TOGGLE
                    KeyEvent.KEYCODE_DPAD_DOWN -> ShortcutAction.MINIMIZE_OR_RESTORE
                    KeyEvent.KEYCODE_W -> ShortcutAction.CLOSE_FOCUSED
                    else -> null
                }
            }
            if (alt && !meta && !ctrl && keyCode == KeyEvent.KEYCODE_SPACE) {
                return ShortcutAction.APP_DRAWER
            }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sInstance = this
        sOnStateChanged?.run()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            BridgeService.onA11yWindowStateChanged()
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return super.onKeyEvent(event)
        }
        if (!BridgeService.isDesktopActive()) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (BridgeService.dispatchPhysicalShortcutFromA11y(ShortcutAction.DISMISS_OVERLAY)) {
                return true
            }
            return super.onKeyEvent(event)
        }
        val shortcut = matchPhysicalShortcut(event.keyCode, event.metaState) ?: return super.onKeyEvent(event)
        return if (BridgeService.dispatchPhysicalShortcutFromA11y(shortcut)) true else super.onKeyEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        sInstance = null
        sOnStateChanged?.run()
        return super.onUnbind(intent)
    }
}
