package com.extbridge.helper.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.view.ViewGroup
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.StageTransitionView
import kotlin.math.max

/**
 * Slim coordinator contract between [com.extbridge.helper.BridgeService] and the modular
 * overlay controllers. Eliminates 35+ pass-through RPC wrappers by exposing shared coordinators
 * ([surfaceHost], [popovers], [stageChoreographer]) and direct peer controller handles.
 */
interface OverlayHost {
    val serviceContext: Context
    val handler: Handler
    val displayManager: DisplayManager?
    val audioManager: AudioManager?
    val isDestroyed: Boolean

    val surfaceHost: OverlaySurfaceHost
    val popovers: PopoverCoordinator
    val stageChoreographer: StageChoreographer

    val dock: DockOverlayController
    val panel: ControlPanelOverlayController
    val split: SplitScreenOverlayController
    val blackout: BlackoutOverlayController
    val cursor: CursorOverlayController

    val extCtx: Context?
    val extWm: WindowManager?
    val winType: Int
    val inLauncherHost: Boolean
    val overlayW: Int
    val overlayH: Int
    val overlayDpi: Int
    val stageAnimView: StageTransitionView?
    val launcherHost: ViewGroup?

    val surfaceWidth: Int
        get() = launcherHost?.takeIf { inLauncherHost && it.width > 0 }?.width
            ?: max(OverlayConstants.MIN_SURFACE_PX, overlayW)

    val surfaceHeight: Int
        get() = launcherHost?.takeIf { inLauncherHost && it.height > 0 }?.height
            ?: max(OverlayConstants.MIN_SURFACE_PX, overlayH)

    val lastStatus: BridgeClient.Status
    val lastWindowSnapshot: BridgeClient.WindowStateSnapshot
    val lastSysStatus: BridgeClient.SysStatus
    val lastMirrorTelemetry: BridgeClient.MirrorTelemetry
    val batteryPct: Int
    val batteryCharging: Boolean

    fun safeBgExecute(r: Runnable)
    fun safeInputExecute(r: Runnable)
    fun safePost(r: Runnable)
    fun pollRemoteStateAsync()
    fun beginWindowOverlayStage()
    fun endWindowOverlayStage()
    fun syncOverlayHostSurface(forceAttach: Boolean)

    fun getCachedIcon(pm: PackageManager, pkg: String): Drawable?
    fun getCachedLabel(pm: PackageManager, pkg: String): String
    fun cloneDrawable(d: Drawable?): Drawable?
    fun buildCardSpec(pkg: String?, snap: BridgeClient.TaskSnapshotData?, startR: Rect, endR: Rect): StageTransitionView.CardSpec

    fun setDockAutoHideEnabled(enabled: Boolean)
    fun updateInputToggleUi(inputToExt: Int)
    fun updateSplitButtons(splitActive: Boolean)
    fun openExtSettings(action: String)
    fun openTabletMain()
    fun openTabletTouchpad()
    fun execSysAndRefresh(act: Int, arg: Int, str: String?)
    fun onLocalHardwareRefreshRequested()
    fun refreshTrayIconsOnly()
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
