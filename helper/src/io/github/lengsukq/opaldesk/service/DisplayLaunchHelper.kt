package io.github.lengsukq.opaldesk.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.Display

/**
 * Helper for launching Activities onto Display 0 or a target external physical Display.
 */
object DisplayLaunchHelper {
    fun launchOnDisplay0(ctx: Context, intent: Intent): Boolean =
        launchOnDisplay(ctx, intent, Display.DEFAULT_DISPLAY)

    fun launchOnDisplay(ctx: Context, intent: Intent, displayId: Int): Boolean {
        return try {
            val flags = if (ctx !is Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0
            intent.addFlags(flags)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            ctx.startActivity(intent, opts.toBundle())
            true
        } catch (_: Throwable) {
            try {
                if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
