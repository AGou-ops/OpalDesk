package io.github.lengsukq.opaldesk.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.view.WindowManager
import io.github.lengsukq.opaldesk.BridgeClient

/**
 * Minimal service capability facade for shared non-DeX overlay controllers
 * ([BlackoutOverlayController], [CursorOverlayController], [MirrorSidebarController]).
 */
interface OverlayHost {
    val serviceContext: Context
    val handler: Handler
    val displayManager: DisplayManager?
    val audioManager: AudioManager?
    val isDestroyed: Boolean

    val surfaceHost: OverlaySurfaceHost
    val popovers: PopoverCoordinator

    val extCtx: Context?
    val extWm: WindowManager?
    val winType: Int

    val lastStatus: BridgeClient.Status
    val lastSysStatus: BridgeClient.SysStatus
    val lastMirrorTelemetry: BridgeClient.MirrorTelemetry
    val batteryPct: Int
    val batteryCharging: Boolean

    fun safeBgExecute(r: Runnable)
    fun safeInputExecute(r: Runnable)
    fun safePost(r: Runnable)
    fun pollRemoteStateAsync()

    fun getCachedIcon(pm: PackageManager, pkg: String): Drawable?
    fun getCachedLabel(pm: PackageManager, pkg: String): String
    fun execSysAndRefresh(act: Int, arg: Int, str: String?)
    fun onBlackoutStateChanged()
    fun onCursorMoved(x: Float, y: Float)
}

internal fun OverlayHost.runDaemonAndPoll(toastMsg: String? = null, rpc: () -> Unit) {
    safeBgExecute {
        rpc()
        safePost {
            if (toastMsg != null) serviceContext.toast(toastMsg)
            pollRemoteStateAsync()
        }
    }
}
