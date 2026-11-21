package com.extbridge.helper.desktop.state

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.view.View
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.desktop.model.DesktopState
import com.extbridge.helper.desktop.model.DesktopWindow
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.model.IntRect
import com.extbridge.helper.desktop.model.PreviewOverlayState
import com.extbridge.helper.desktop.model.SnapZone
import com.extbridge.helper.desktop.model.TaskbarItem
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.PopoverCoordinator

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
    fun notifyHardwareChanged()
    fun requestStateRefresh()
    fun updateTaskbarPinnedApps()
}
