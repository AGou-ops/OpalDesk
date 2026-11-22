package io.github.lengsukq.opaldesk.desktop.state

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.view.View
import android.view.WindowManager
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.desktop.model.DesktopState
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.PreviewOverlayState
import io.github.lengsukq.opaldesk.desktop.model.SnapZone
import io.github.lengsukq.opaldesk.desktop.model.TaskbarItem
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.PopoverCoordinator

/**
 * Clean facade contract between `DesktopShellCoordinator` and every modular Samsung DeX
 * desktop surface (`chrome/`, `taskbar/`, `popovers/`).
 */
interface DesktopShellHost {
    val serviceContext: Context
    val extCtx: Context?
    val extWm: WindowManager?
    val winType: Int
    val handler: Handler
    val surfaceHost: OverlaySurfaceHost
    val popovers: PopoverCoordinator
    val audioManager: AudioManager?
    val displayManager: DisplayManager?

    val state: DesktopState
    val tokens: GlassOverlayTokens
    val lastStatus: BridgeClient.Status
    val lastSysStatus: BridgeClient.SysStatus
    val batteryPct: Int
    val batteryCharging: Boolean
    val isBlackoutActive: Boolean
    val isTaskbarAutoHide: Boolean
    val isWorkbenchEnabled: Boolean
    val workbenchPosition: Int

    fun safeBgExecute(r: Runnable)
    fun safeInputExecute(r: Runnable)
    fun safePost(r: Runnable)
    fun bringCursorToFront()
    fun getCachedIcon(pm: PackageManager, pkg: String): Drawable?
    fun getCachedLabel(pm: PackageManager, pkg: String): String

    // Live preview canvas coordination
    fun updatePreview(preview: PreviewOverlayState)
    fun clearPreview()
    fun setDraggingWindow(taskId: Int, liveRect: IntRect?)

    // Window lifecycle & geometry mutations
    fun focusWindow(taskId: Int, pkg: String)
    fun minimizeWindow(taskId: Int, pkg: String)
    fun closeWindow(taskId: Int, pkg: String)
    fun sendBackToWindow(taskId: Int, pkg: String)
    fun toggleMaximizeWindow(win: DesktopWindow)
    fun reloadWindowDpi(taskId: Int, pkg: String)
    fun moveWindowLive(taskId: Int, pkg: String, rect: IntRect)
    fun commitWindowBounds(taskId: Int, pkg: String, rect: IntRect, rememberPref: Boolean)
    fun commitPairedSplitBounds(leftTaskId: Int, leftPkg: String, leftRect: IntRect, rightTaskId: Int, rightPkg: String, rightRect: IntRect)
    fun snapWindow(taskId: Int, pkg: String, zone: SnapZone)
    fun enterImmersive(taskId: Int, pkg: String)
    fun exitImmersiveToFloating(win: DesktopWindow)
    fun applyWindowPresetDp(taskId: Int, pkg: String, widthDp: Int)
    fun launchApp(pkg: String, cls: String = "", zone: SnapZone = SnapZone.NONE)

    // Shell popovers & system actions
    fun toggleAppDrawer(focusSearch: Boolean = false)
    fun toggleQuickSettings()
    fun toggleWindowSwitcher()
    fun toggleShowDesktop()
    fun openSnapLayouts(win: DesktopWindow, anchorX: Int, anchorY: Int)
    fun openWindowMenu(win: DesktopWindow, anchorX: Int, anchorY: Int)
    fun openTaskbarItemMenu(item: TaskbarItem, anchorView: View?, anchorX: Int, anchorY: Int)
    fun setTaskbarAutoHide(enabled: Boolean)
    fun cycleGlassTheme()
    fun toggleBlackout()
    fun toggleInputRouting()
    fun sendGlobalBack()
    fun execSysAction(act: Int, arg: Int = -1, str: String? = null)
    fun switchMirrorMode(mode: Int)
    fun cycleRotation()
    fun openTabletMain()
    fun openTabletTouchpad()
    fun openExtSettings(action: String)
    fun exitLocalDex()
    fun notifyHardwareChanged()
    fun requestStateRefresh()
    fun updateTaskbarPinnedApps()

    // Workbench Mode (Stage Manager / App Card Rail)
    fun toggleWorkbench()
    fun setWorkbenchEnabled(enabled: Boolean)
    fun setWorkbenchPosition(position: Int)
    fun openWorkbenchAppPicker()
    fun switchToWorkbenchApp(pkg: String, taskId: Int)
    fun openWorkbenchCardMenu(pkg: String, taskId: Int, pinned: Boolean, anchorX: Int, anchorY: Int)
    fun updateWorkbenchApps()
}
