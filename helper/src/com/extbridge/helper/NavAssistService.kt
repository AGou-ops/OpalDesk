package com.extbridge.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class NavAssistService : AccessibilityService() {

    companion object {
        @Volatile
        private var sInstance: NavAssistService? = null

        @Volatile
        private var sOnStateChanged: Runnable? = null

        @JvmStatic
        fun isRunning(): Boolean = sInstance != null

        @JvmStatic
        fun getInstance(): NavAssistService? = sInstance

        @JvmStatic
        fun setOnStateChanged(r: Runnable?) {
            sOnStateChanged = r
        }

        @JvmStatic
        fun performBack() {
            sInstance?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        @JvmStatic
        fun performHome() {
            sInstance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        @JvmStatic
        fun performRecents() {
            sInstance?.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sInstance = this
        sOnStateChanged?.run()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        sInstance = null
        sOnStateChanged?.run()
        return super.onUnbind(intent)
    }
}
