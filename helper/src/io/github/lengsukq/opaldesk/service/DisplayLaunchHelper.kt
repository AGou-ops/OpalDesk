package io.github.lengsukq.opaldesk.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display

/**
 * Shared Display-0 launch guard eliminating duplicated `ensureOnDisplay0()` and
 * `ActivityOptions.setLaunchDisplayId(0)` boilerplate across Activities and Receivers.
 */
object DisplayLaunchHelper {
    private const val TAG = "ExtDisplayLaunch"

    fun launchOnDisplay0(
        context: Context,
        intent: Intent,
        windowingMode: Int? = null,
    ): Boolean = try {
        val opts = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        }
        if (windowingMode != null) {
            try {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.java)
                    .invoke(opts, windowingMode)
            } catch (t: Throwable) {
                Log.d(TAG, "setLaunchWindowingMode($windowingMode) unavailable: ${t.message}")
            }
        }
        context.startActivity(intent, opts.toBundle())
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to launch ${intent.component?.shortClassName ?: intent.action} on Display 0", t)
        false
    }

    /**
     * If [activity] was mistakenly launched onto an external/virtual display, relaunches it on
     * Display 0 and finishes the current instance. Returns `true` if relaunched.
     */
    fun ensureOnDisplay0(activity: Activity): Boolean {
        val disp = activity.display ?: return false
        if (disp.displayId == Display.DEFAULT_DISPLAY) return false
        val relaunch = Intent(activity, activity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (!launchOnDisplay0(activity, relaunch)) return false
        activity.finishAndRemoveTask()
        return true
    }
}
