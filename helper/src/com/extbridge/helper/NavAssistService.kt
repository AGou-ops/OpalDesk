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

        fun isRunning(): Boolean = sInstance != null

        fun getInstance(): NavAssistService? = sInstance

        fun setOnStateChanged(r: Runnable?) {
            sOnStateChanged = r
        }

        fun performHome() {
            sInstance?.performGlobalAction(GLOBAL_ACTION_HOME)
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
