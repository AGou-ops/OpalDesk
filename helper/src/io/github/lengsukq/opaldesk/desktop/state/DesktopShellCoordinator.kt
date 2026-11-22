package io.github.lengsukq.opaldesk.desktop.state

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.LauncherActivity
import io.github.lengsukq.opaldesk.desktop.chrome.DesktopBezelCanvasController
import io.github.lengsukq.opaldesk.desktop.chrome.GlobalResizeEdgeController
import io.github.lengsukq.opaldesk.desktop.chrome.ImmersiveTopHandleController
import io.github.lengsukq.opaldesk.desktop.chrome.PairedSnapDividerController
import io.github.lengsukq.opaldesk.desktop.chrome.WindowHeaderBarPool
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopState
import io.github.lengsukq.opaldesk.desktop.model.DesktopStateReducer
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.PreviewOverlayState
import io.github.lengsukq.opaldesk.desktop.model.SnapZone
import io.github.lengsukq.opaldesk.desktop.model.TaskbarItem
import io.github.lengsukq.opaldesk.desktop.popovers.DeXAppDrawerOverlay
import io.github.lengsukq.opaldesk.desktop.popovers.DeXGlassContextMenu
import io.github.lengsukq.opaldesk.desktop.popovers.DeXQuickSettingsOverlay
import io.github.lengsukq.opaldesk.desktop.popovers.DeXSnapLayoutsPopover
import io.github.lengsukq.opaldesk.desktop.popovers.DeXWindowSwitcherOverlay
import io.github.lengsukq.opaldesk.desktop.popovers.GlassMenuRow
import io.github.lengsukq.opaldesk.desktop.taskbar.DeXTaskbarController
import io.github.lengsukq.opaldesk.desktop.workbench.DeXWorkbenchPickerOverlay
import io.github.lengsukq.opaldesk.desktop.workbench.DeXWorkbenchRailController
import io.github.lengsukq.opaldesk.ipc.DaemonStateStore
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.PopoverCoordinator
import io.github.lengsukq.opaldesk.overlay.toast
import io.github.lengsukq.opaldesk.prefs.AppPrefs
import io.github.lengsukq.opaldesk.protocol.BridgeParsers
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.ShortcutAction
import io.github.lengsukq.opaldesk.ui.ExtThemePrefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Callback bridge provided by [io.github.lengsukq.opaldesk.BridgeService] so [DesktopShellCoordinator]
 * stays cleanly decoupled from Service lifecycle, blackout mask, cursor, and mirror sidebar.
 */
interface ServiceBridgeCallbacks {
    val serviceContext: Context
    val extCtx: Context?
    val extWm: WindowManager?
    val winType: Int
    val handler: Handler
    val surfaceHost: OverlaySurfaceHost
    val popovers: PopoverCoordinator
    val audioManager: AudioManager?
    val displayManager: DisplayManager?
    val lastStatus: BridgeClient.Status
    val lastSysStatus: BridgeClient.SysStatus
    val batteryPct: Int
    val batteryCharging: Boolean
    val isBlackoutActive: Boolean

    fun safeBgExecute(r: Runnable)
    fun safeInputExecute(r: Runnable)
    fun safePost(r: Runnable)
    fun bringCursorToFront()
    fun getCachedIcon(pm: PackageManager, pkg: String): Drawable?
    fun getCachedLabel(pm: PackageManager, pkg: String): String
    fun toggleBlackout()
    fun openTabletMain()
    fun openTabletTouchpad()
    fun openExtSettings(action: String)
    fun exitLocalDex()
    fun execSysAndRefresh(act: Int, arg: Int, str: String?)
    fun onHardwareRefreshRequested()
    fun requestFullPoll()
    fun computeVolumeLevel(): Int
}

/**
 * Single authoritative coordinator for the Liquid Glass × Samsung DeX desktop shell.
 *
 * Owns immutable [DesktopState], optimistic bounds leases, single-flight coalesced live move
 * dispatch, zero-roundtrip pushed window snapshot ingestion, global shortcut handling, and
 * all modular chrome/taskbar/popover controllers.
 */
class DesktopShellCoordinator(private val svc: ServiceBridgeCallbacks) : DesktopShellHost {

    companion object {
        private const val TAG = "ExtDeXShell"
    }

    override val serviceContext: Context get() = svc.serviceContext
    override val extCtx: Context? get() = svc.extCtx
    override val extWm: WindowManager? get() = svc.extWm
    override val winType: Int get() = svc.winType
    override val handler: Handler get() = svc.handler
    override val surfaceHost: OverlaySurfaceHost get() = svc.surfaceHost
    override val popovers: PopoverCoordinator get() = svc.popovers
    override val audioManager: AudioManager? get() = svc.audioManager
    override val displayManager: DisplayManager? get() = svc.displayManager
    override val lastStatus: BridgeClient.Status get() = svc.lastStatus
    override val lastSysStatus: BridgeClient.SysStatus get() = svc.lastSysStatus
    override val batteryPct: Int get() = svc.batteryPct
    override val batteryCharging: Boolean get() = svc.batteryCharging
    override val isBlackoutActive: Boolean get() = svc.isBlackoutActive

    override var isTaskbarAutoHide: Boolean = AppPrefs.isDockAutoHide(svc.serviceContext)
        private set

    override var isWorkbenchEnabled: Boolean = AppPrefs.isWorkbenchEnabled(svc.serviceContext)
        private set

    override var workbenchPosition: Int = AppPrefs.getWorkbenchPosition(svc.serviceContext)
        private set

    override var state: DesktopState = DesktopState(taskbarAutoHide = isTaskbarAutoHide)
        private set

    override var tokens: GlassOverlayTokens = GlassOverlayTokens.resolve(svc.serviceContext)
        private set

    private var attached = false
    private var lastTasks: List<BridgeClient.TaskEntry>? = null
    private var lastSnapshot = BridgeClient.WindowStateSnapshot()
    private var previewState = PreviewOverlayState()
    private var secondaryPreviewRect: IntRect? = null
    private var draggingTaskId = -1
    private var draggingRect: IntRect? = null

    // Dedicated single-flight live-move executor completely isolated from periodic polling/snapshots
    private val moveLock = Any()
    private val liveMoveExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ExtLiveMove").apply { isDaemon = true }
    }
    private var moveInFlight = false
    private var pendingMoveTaskId = -1
    private var pendingMovePkg = ""
    private var pendingMoveRect: IntRect? = null

    fun isActivelyDragging(): Boolean = draggingTaskId > 0

    private val bezelCanvas = DesktopBezelCanvasController(this)
    private val headerPool = WindowHeaderBarPool(this)
    private val resizeEdges = GlobalResizeEdgeController(this)
    private val pairedDivider = PairedSnapDividerController(this) { primary, secondary ->
        previewState = primary
        secondaryPreviewRect = secondary
        renderBezelCanvasOnly()
    }
    private val immersiveHandle = ImmersiveTopHandleController(this)
    private val taskbar = DeXTaskbarController(this)
    private val appDrawer = DeXAppDrawerOverlay(this)
    private val quickSettings = DeXQuickSettingsOverlay(
        this,
        isTrayInteractionRecent = { taskbar.autoHide.isTrayInteractionRecent() },
        onVisibilityChanged = { open ->
            taskbar.setQuickSettingsHighlighted(open)
            if (open) taskbar.autoHide.cancelTimer() else taskbar.autoHide.evaluate(false)
        },
    )
    private val windowSwitcher = DeXWindowSwitcherOverlay(this)
    private val snapLayouts = DeXSnapLayoutsPopover(this)
    private val contextMenu = DeXGlassContextMenu(this)
    private val workbenchRail = DeXWorkbenchRailController(this)
    private val workbenchPicker = DeXWorkbenchPickerOverlay(this)

    // --- Lifecycle ---

    fun attach(ctx: Context, st: BridgeClient.Status) {
        detach()
        tokens = GlassOverlayTokens.resolve(serviceContext)
        isTaskbarAutoHide = AppPrefs.isDockAutoHide(serviceContext)
        isWorkbenchEnabled = AppPrefs.isWorkbenchEnabled(serviceContext)
        workbenchPosition = AppPrefs.getWorkbenchPosition(serviceContext)
        state = state.copy(
            displayW = max(600, st.virtW),
            displayH = max(480, st.virtH),
            dpi = effectiveDpi(st),
            taskbarAutoHide = isTaskbarAutoHide,
        )

        bezelCanvas.build(ctx)
        headerPool.build(ctx)
        resizeEdges.build(ctx)
        pairedDivider.build(ctx)
        immersiveHandle.build(ctx)
        workbenchRail.build(ctx)
        taskbar.build(ctx)
        appDrawer.build(ctx)
        quickSettings.build(ctx)
        windowSwitcher.build(ctx)
        workbenchPicker.build(ctx)
        snapLayouts.build(ctx)
        contextMenu.build(ctx)

        appDrawer.onOpenAppMenu = { entry, rows, ax, ay ->
            contextMenu.show(entry.label, entry.pkg, entry.icon, rows, ax, ay)
        }

        attached = true
        // Sync taskbar auto-hide & default launch mode to daemon so WorkArea matches immediately
        val defaultModeToken = defaultLaunchModeToken(AppPrefs.getDexDefaultLaunchMode(serviceContext))
        safeBgExecute { BridgeClient.setDesktopPrefs(isTaskbarAutoHide, defaultModeToken) }
    }

    fun detach() {
        attached = false
        clearPendingMoves()
        previewState = PreviewOverlayState()
        secondaryPreviewRect = null
        draggingTaskId = -1; draggingRect = null

        contextMenu.detach()
        snapLayouts.detach()
        workbenchPicker.detach()
        windowSwitcher.detach()
        quickSettings.detach()
        appDrawer.detach()
        taskbar.detach()
        workbenchRail.detach()
        immersiveHandle.detach()
        pairedDivider.detach()
        resizeEdges.detach()
        headerPool.detach()
        bezelCanvas.detach()
    }

    fun onDisplayBoundsChanged(w: Int, h: Int, dpi: Int) {
        if (!attached) return
        val ctx = extCtx ?: return
        state = state.copy(displayW = max(600, w), displayH = max(480, h), dpi = dpi.coerceIn(80, 640))
        bezelCanvas.onDisplayBoundsChanged(state.displayW, state.displayH)
        taskbar.onDisplayBoundsChanged(ctx, state.displayW)
        workbenchRail.onDisplayBoundsChanged(ctx)
        renderAll()
    }

    // --- State Ingestion (Fast Push + Periodic Poll) ---

    fun onPushedWindowSnapshot(winJson: String, pushedSeq: Long) {
        if (!attached) return
        val snap = BridgeParsers.parseWindowStateSnapshot(winJson)
        if (pushedSeq > 0L && snap.stateSeq == 0L) {
            applySnapshotOnMain(snap.copy(stateSeq = pushedSeq), lastTasks)
        } else {
            applySnapshotOnMain(snap, lastTasks)
        }
    }

    fun onFastWindowSnapshot(snap: BridgeClient.WindowStateSnapshot) {
        if (!attached) return
        applySnapshotOnMain(snap, lastTasks)
    }

    fun onDaemonPollUpdate(
        st: BridgeClient.Status,
        snap: BridgeClient.WindowStateSnapshot,
        tasks: List<BridgeClient.TaskEntry>?,
        sys: BridgeClient.SysStatus,
        volLevel: Int,
    ) {
        if (!attached) return
        lastTasks = tasks
        val dpi = effectiveDpi(st)
        state = state.copy(displayW = max(600, st.virtW), displayH = max(480, st.virtH), dpi = dpi)
        applySnapshotOnMain(snap, tasks, volLevel, sys)
    }

    private fun applySnapshotOnMain(
        snap: BridgeClient.WindowStateSnapshot,
        tasks: List<BridgeClient.TaskEntry>?,
        volLevel: Int = svc.computeVolumeLevel(),
        sys: BridgeClient.SysStatus = lastSysStatus,
    ) {
        if (!attached) return
        lastSnapshot = snap
        DaemonStateStore.updateWindowSnapshot(snap)
        tokens = GlassOverlayTokens.resolve(serviceContext)
        state = DesktopStateReducer.reconcile(
            prev = state,
            snap = snap,
            tasks = tasks,
            pinnedPkgs = AppPrefs.getDockPinnedApps(serviceContext),
            displayW = state.displayW,
            displayH = state.displayH,
            dpi = state.dpi,
            taskbarAutoHide = isTaskbarAutoHide,
            nowUptimeMs = SystemClock.uptimeMillis(),
        )
        renderAll(volLevel, sys)
        LauncherActivity.notifyOverlayStateChanged()
    }

    fun refreshHardware(volLevel: Int, sys: BridgeClient.SysStatus) {
        if (!attached) return
        val ctx = extCtx ?: return
        taskbar.refreshHardware(volLevel, sys, tokens)
        if (quickSettings.isOpen) quickSettings.syncState(ctx)
    }

    fun refreshTheme() {
        tokens = GlassOverlayTokens.resolve(serviceContext)
        if (!attached) return
        renderAll()
        extCtx?.let { if (quickSettings.isOpen) quickSettings.syncState(it) }
    }

    private fun renderAll(volLevel: Int = svc.computeVolumeLevel(), sys: BridgeClient.SysStatus = lastSysStatus) {
        val ctx = extCtx ?: return
        val immersive = state.activeImmersiveWindow
        val visibleChrome = if (immersive != null) emptyList() else state.visibleChromeWindows
        val focused = if (immersive != null) null else state.focusedVisibleWindow

        renderBezelCanvasOnly()
        headerPool.render(ctx, visibleChrome, state.dpi, tokens)
        resizeEdges.updateForFocusedWindow(ctx, if (focused?.isVisibleChromeWindow == true) focused else null, state.dpi)
        val paired = DesktopGeometry.detectPairedHorizontalSplit(visibleChrome, state.displayW, state.displayH, state.dpi, isTaskbarAutoHide)
        pairedDivider.update(ctx, paired, tokens)
        immersiveHandle.update(ctx, immersive, tokens)
        workbenchRail.render(ctx, state, tokens, lastStatus.isLocalDexActive)
        taskbar.render(ctx, state, lastStatus, sys, volLevel, tokens)
        if (quickSettings.isOpen) quickSettings.syncState(ctx)
    }

    private fun renderBezelCanvasOnly() {
        val chromeWindows = if (state.activeImmersiveWindow != null) emptyList() else state.visibleChromeWindows
        bezelCanvas.render(
            windows = chromeWindows,
            dpi = state.dpi,
            draggingTaskId = draggingTaskId,
            draggingRect = draggingRect,
            preview = previewState,
            secondaryPreviewRect = secondaryPreviewRect,
            tokens = tokens,
        )
    }

    // --- Cursor, Touch, Right-Click & Global Shortcuts ---

    fun onCursorMoved(x: Float, y: Float) {
        if (!attached) return
        taskbar.onCursorMoved(y)
        immersiveHandle.onCursorMoved(y)
    }

    fun onHostTouch(x: Float, y: Float) {
        if (!attached) return
        if (quickSettings.isOpen && !taskbar.isPointInTray(x, y)) quickSettings.dismiss()
        if (contextMenu.isOpen) contextMenu.dismiss()
        if (snapLayouts.isOpen) snapLayouts.dismiss()
    }

    /**
     * Thread-safe right-click classifier called on main thread:
     * returns `1` if consumed locally, `2` to forward secondary button click, `0` for Back key.
     */
    fun classifyRightClick(curX: Int, curY: Int): Int {
        if (!attached) return 0
        if (popovers.isAnyOpen()) {
            if (appDrawer.isOpen) return 2
            popovers.dismissAll()
            return 1
        }
        if (headerPool.handleRightClickAt(curX, curY)) return 1
        if (taskbar.isPointInTaskStrip(curX.toFloat(), curY.toFloat())) return 2
        if (state.isDesktopClear && LauncherActivity.shouldConsumeRightClickAt(curX, curY)) return 2
        // Inside regular Android apps: respect user preference (true = native secondary click, false = Back)
        return if (AppPrefs.isRightClickPassthroughInApps(serviceContext)) 2 else 0
    }

    fun onDesktopShortcut(action: String): Boolean {
        if (!attached) return false
        when (action) {
            ShortcutAction.WINDOW_SWITCHER -> { windowSwitcher.toggleOrCycle(); return true }
            ShortcutAction.APP_DRAWER -> { appDrawer.toggle(focusSearch = true); return true }
            ShortcutAction.SHOW_DESKTOP -> { toggleShowDesktop(); return true }
            ShortcutAction.DISMISS_OVERLAY -> {
                if (popovers.isAnyOpen()) { popovers.dismissAll(); return true }
                return false
            }
            ShortcutAction.CLOSE_FOCUSED -> {
                val w = state.focusedVisibleWindow ?: return false
                closeWindow(w.taskId, w.pkg); return true
            }
            ShortcutAction.MINIMIZE_OR_RESTORE -> {
                val w = state.focusedVisibleWindow ?: return false
                if (w.isMaximized || w.isSnapped) toggleMaximizeWindow(w) else minimizeWindow(w.taskId, w.pkg)
                return true
            }
            ShortcutAction.MAXIMIZE_TOGGLE -> {
                val w = state.focusedVisibleWindow ?: return false
                toggleMaximizeWindow(w); return true
            }
            ShortcutAction.SNAP_LEFT -> {
                val w = state.focusedVisibleWindow ?: return false
                snapWindow(w.taskId, w.pkg, SnapZone.LEFT_HALF); return true
            }
            ShortcutAction.SNAP_RIGHT -> {
                val w = state.focusedVisibleWindow ?: return false
                snapWindow(w.taskId, w.pkg, SnapZone.RIGHT_HALF); return true
            }
            else -> return false
        }
    }

    fun syncDefaultLaunchMode(modeIdx: Int) {
        val token = defaultLaunchModeToken(modeIdx)
        safeBgExecute { BridgeClient.setDesktopPrefs(isTaskbarAutoHide, token) }
    }

    private fun defaultLaunchModeToken(modeIdx: Int): String = when (modeIdx) {
        1 -> BridgeClient.LAYOUT_ADAPTIVE_SINGLE
        2 -> BridgeClient.LAYOUT_FULLSCREEN
        else -> BridgeClient.LAYOUT_FREEFORM
    }

    // --- DesktopShellHost Implementation ---

    override fun safeBgExecute(r: Runnable) = svc.safeBgExecute(r)
    override fun safeInputExecute(r: Runnable) = svc.safeInputExecute(r)
    override fun safePost(r: Runnable) = svc.safePost(r)
    override fun bringCursorToFront() = svc.bringCursorToFront()
    override fun getCachedIcon(pm: PackageManager, pkg: String): Drawable? = svc.getCachedIcon(pm, pkg)
    override fun getCachedLabel(pm: PackageManager, pkg: String): String = svc.getCachedLabel(pm, pkg)

    override fun updatePreview(preview: PreviewOverlayState) {
        previewState = preview
        secondaryPreviewRect = null
        renderBezelCanvasOnly()
    }

    override fun clearPreview() {
        if (!previewState.active && secondaryPreviewRect == null) return
        previewState = PreviewOverlayState()
        secondaryPreviewRect = null
        renderBezelCanvasOnly()
    }

    override fun setDraggingWindow(taskId: Int, liveRect: IntRect?) {
        draggingTaskId = taskId
        draggingRect = liveRect
        renderBezelCanvasOnly()
    }

    override fun focusWindow(taskId: Int, pkg: String) {
        safeBgExecute {
            BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_FOCUS)
        }
    }

    override fun minimizeWindow(taskId: Int, pkg: String) {
        safeBgExecute {
            BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_MINIMIZE)
        }
    }

    override fun closeWindow(taskId: Int, pkg: String) {
        safeBgExecute {
            BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_CLOSE)
        }
    }

    override fun sendBackToWindow(taskId: Int, pkg: String) {
        safeBgExecute {
            BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_SEND_BACK)
        }
    }

    override fun toggleMaximizeWindow(win: DesktopWindow) {
        safeBgExecute {
            BridgeClient.windowLifecycleAction(win.taskId, win.pkg, BridgeClient.WIN_ACTION_TOGGLE_MAXIMIZE)
        }
    }

    override fun reloadWindowDpi(taskId: Int, pkg: String) {
        serviceContext.toast("正在重载应用 DPI 并重启…")
        safeBgExecute {
            BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_RELOAD_DPI)
        }
    }

    override fun moveWindowLive(taskId: Int, pkg: String, rect: IntRect) {
        val shouldStart: Boolean
        synchronized(moveLock) {
            pendingMoveTaskId = taskId
            pendingMovePkg = pkg
            pendingMoveRect = rect
            shouldStart = !moveInFlight
            if (shouldStart) moveInFlight = true
        }
        if (shouldStart) drainLiveMoveQueue()
    }

    private fun drainLiveMoveQueue() {
        if (liveMoveExec.isShutdown) return
        try {
            liveMoveExec.execute {
                while (true) {
                    val tid: Int
                    val pkg: String
                    val r: IntRect
                    synchronized(moveLock) {
                        val next = pendingMoveRect
                        if (next == null) {
                            moveInFlight = false
                            return@execute
                        }
                        tid = pendingMoveTaskId
                        pkg = pendingMovePkg
                        r = next
                        pendingMoveRect = null
                    }
                    val androidRect = r.toAndroidRect()
                    if (!BridgeClient.moveWindowLive(tid, androidRect)) {
                        BridgeClient.updateWindowBounds(tid, pkg, BridgeClient.WIN_MODE_FREEFORM, androidRect, false)
                    }
                }
            }
        } catch (_: Throwable) {
            synchronized(moveLock) { moveInFlight = false }
        }
    }

    private fun clearPendingMoves() {
        synchronized(moveLock) {
            pendingMoveRect = null
            moveInFlight = false
        }
    }

    override fun commitWindowBounds(taskId: Int, pkg: String, rect: IntRect, rememberPref: Boolean) {
        clearPendingMoves()
        state = DesktopStateReducer.applyOptimisticLease(state, taskId, rect, SystemClock.uptimeMillis())
        renderAll()
        val commitWork = Runnable {
            BridgeClient.updateWindowBounds(taskId, pkg, BridgeClient.WIN_MODE_FREEFORM, rect.toAndroidRect(), rememberPref)
        }
        if (!liveMoveExec.isShutdown) {
            try { liveMoveExec.execute(commitWork); return } catch (_: Throwable) {}
        }
        safeBgExecute(commitWork)
    }

    fun destroy() {
        detach()
        liveMoveExec.shutdownNow()
    }

    override fun commitPairedSplitBounds(
        leftTaskId: Int, leftPkg: String, leftRect: IntRect,
        rightTaskId: Int, rightPkg: String, rightRect: IntRect,
    ) {
        val now = SystemClock.uptimeMillis()
        state = DesktopStateReducer.applyOptimisticLease(state, leftTaskId, leftRect, now)
        state = DesktopStateReducer.applyOptimisticLease(state, rightTaskId, rightRect, now)
        renderAll()
        safeBgExecute {
            BridgeClient.updateWindowBounds(leftTaskId, leftPkg, BridgeClient.WIN_MODE_FREEFORM, leftRect.toAndroidRect(), false)
            BridgeClient.updateWindowBounds(rightTaskId, rightPkg, BridgeClient.WIN_MODE_FREEFORM, rightRect.toAndroidRect(), false)
        }
    }

    override fun snapWindow(taskId: Int, pkg: String, zone: SnapZone) {
        if (zone == SnapZone.IMMERSIVE) {
            enterImmersive(taskId, pkg)
            return
        }
        val target = DesktopGeometry.computeSnapRect(zone, state.displayW, state.displayH, state.dpi, isTaskbarAutoHide)
        commitWindowBounds(taskId, pkg, target, rememberPref = false)
    }

    override fun enterImmersive(taskId: Int, pkg: String) {
        val full = IntRect(0, 0, state.displayW, state.displayH)
        state = DesktopStateReducer.applyOptimisticLease(state, taskId, full, SystemClock.uptimeMillis())
        renderAll()
        safeBgExecute {
            BridgeClient.updateWindowBounds(taskId, pkg, BridgeClient.WIN_MODE_FULLSCREEN, full.toAndroidRect(), false)
        }
    }

    override fun exitImmersiveToFloating(win: DesktopWindow) {
        val target = win.restoreBounds ?: DesktopGeometry.computeCenteredPresetRect(
            680, state.displayW, state.displayH, state.dpi, isTaskbarAutoHide,
        )
        commitWindowBounds(win.taskId, win.pkg, target, rememberPref = false)
    }

    override fun applyWindowPresetDp(taskId: Int, pkg: String, widthDp: Int) {
        val rect = DesktopGeometry.computeCenteredPresetRect(widthDp, state.displayW, state.displayH, state.dpi, isTaskbarAutoHide)
        commitWindowBounds(taskId, pkg, rect, rememberPref = true)
    }

    override fun launchApp(pkg: String, cls: String, zone: SnapZone) {
        if (pkg.isBlank()) return
        safeBgExecute {
            if (zone == SnapZone.IMMERSIVE) {
                BridgeClient.launchInWindowMode(pkg, cls, BridgeClient.WIN_MODE_FULLSCREEN, 0, 0, state.displayW, state.displayH)
            } else if (zone != SnapZone.NONE) {
                val r = DesktopGeometry.computeSnapRect(zone, state.displayW, state.displayH, state.dpi, isTaskbarAutoHide)
                BridgeClient.launchInWindowMode(pkg, cls, BridgeClient.WIN_MODE_FREEFORM, r.left, r.top, r.right, r.bottom)
            } else {
                BridgeClient.launchInWindowMode(pkg, cls, BridgeClient.WIN_MODE_AUTO, 0, 0, 0, 0)
            }
        }
    }

    override fun toggleAppDrawer(focusSearch: Boolean) = appDrawer.toggle(focusSearch)
    override fun toggleQuickSettings() = quickSettings.toggle()
    override fun toggleWindowSwitcher() = windowSwitcher.toggleOrCycle()

    fun wakeTaskbar() {
        if (!attached) return
        taskbar.autoHide.wakeFromEdge()
    }

    override fun toggleShowDesktop() {
        popovers.dismissAll()
        val (minimizeAll, _) = DesktopStateReducer.planShowDesktopToggle(state)
        state = state.copy(showDesktopRestoreIds = emptyList())
        if (minimizeAll) {
            safeBgExecute { BridgeClient.minimizeToExtHome(-1, null) }
        }
    }

    override fun openSnapLayouts(win: DesktopWindow, anchorX: Int, anchorY: Int) {
        snapLayouts.show(win, anchorX, anchorY)
    }

    override fun openWindowMenu(win: DesktopWindow, anchorX: Int, anchorY: Int) {
        val pm = serviceContext.packageManager
        val label = getCachedLabel(pm, win.pkg)
        val icon = getCachedIcon(pm, win.pkg)
        val rows = listOf(
            GlassMenuRow.ChipPair(
                listOf(
                    GlassMenuRow.Action("左半屏", HyperUi.ICON_CHEVRON_LEFT) { snapWindow(win.taskId, win.pkg, SnapZone.LEFT_HALF) },
                    GlassMenuRow.Action("右半屏", HyperUi.ICON_CHEVRON_RIGHT) { snapWindow(win.taskId, win.pkg, SnapZone.RIGHT_HALF) },
                ),
            ),
            GlassMenuRow.ChipPair(
                listOf(
                    GlassMenuRow.Action("420 手机") { applyWindowPresetDp(win.taskId, win.pkg, 420) },
                    GlassMenuRow.Action("680 折叠") { applyWindowPresetDp(win.taskId, win.pkg, 680) },
                    GlassMenuRow.Action("960 平板") { applyWindowPresetDp(win.taskId, win.pkg, 960) },
                ),
            ),
            GlassMenuRow.Divider,
            GlassMenuRow.Action(
                if (win.isMaximized) "还原小窗尺寸" else "最大化填满工作区",
                if (win.isMaximized) HyperUi.ICON_RESTORE else HyperUi.ICON_MAXIMIZE,
            ) { toggleMaximizeWindow(win) },
            GlassMenuRow.Action("沉浸全屏（隐藏标题栏与底栏）", HyperUi.ICON_FULLSCREEN) { enterImmersive(win.taskId, win.pkg) },
            GlassMenuRow.Action("重载应用 DPI 并重启", HyperUi.ICON_ROTATE) { reloadWindowDpi(win.taskId, win.pkg) },
            GlassMenuRow.Divider,
            GlassMenuRow.Action("关闭窗口", HyperUi.ICON_CLOSE, danger = true) { closeWindow(win.taskId, win.pkg) },
        )
        contextMenu.show(label, "DeX 窗口控制与比例预设", icon, rows, anchorX, anchorY)
    }

    override fun openTaskbarItemMenu(item: TaskbarItem, anchorView: View?, anchorX: Int, anchorY: Int) {
        val pm = serviceContext.packageManager
        val label = getCachedLabel(pm, item.pkg)
        val icon = getCachedIcon(pm, item.pkg)
        val win = state.windows.firstOrNull { it.taskId == item.taskId || it.pkg == item.pkg }
        val isPinned = AppPrefs.isDockAppPinned(serviceContext, item.pkg)
        val rows = buildList {
            add(
                GlassMenuRow.ChipPair(
                    listOf(
                        GlassMenuRow.Action("左半屏", HyperUi.ICON_CHEVRON_LEFT) {
                            if (win != null) snapWindow(win.taskId, win.pkg, SnapZone.LEFT_HALF)
                            else launchApp(item.pkg, zone = SnapZone.LEFT_HALF)
                        },
                        GlassMenuRow.Action("右半屏", HyperUi.ICON_CHEVRON_RIGHT) {
                            if (win != null) snapWindow(win.taskId, win.pkg, SnapZone.RIGHT_HALF)
                            else launchApp(item.pkg, zone = SnapZone.RIGHT_HALF)
                        },
                    ),
                ),
            )
            if (win != null) {
                add(
                    GlassMenuRow.Action(
                        if (win.isMaximized) "还原小窗" else "最大化工作区",
                        if (win.isMaximized) HyperUi.ICON_RESTORE else HyperUi.ICON_MAXIMIZE,
                    ) { toggleMaximizeWindow(win) },
                )
                add(
                    GlassMenuRow.Action(
                        if (win.minimized) "恢复显示窗口" else "最小化到任务栏",
                        if (win.minimized) HyperUi.ICON_WINDOW else HyperUi.ICON_MINUS,
                    ) {
                        if (win.minimized) focusWindow(win.taskId, win.pkg) else minimizeWindow(win.taskId, win.pkg)
                    },
                )
            } else {
                add(GlassMenuRow.Action("以小窗启动", HyperUi.ICON_WINDOW) { launchApp(item.pkg) })
            }
            add(GlassMenuRow.Divider)
            add(
                GlassMenuRow.Action(if (isPinned) "从任务栏取消固定" else "常驻固定到任务栏", HyperUi.ICON_PIN) {
                    val nowPinned = AppPrefs.toggleDockPinnedApp(serviceContext, item.pkg)
                    serviceContext.toast(if (nowPinned) "已固定到任务栏：$label" else "已取消固定：$label")
                    updateTaskbarPinnedApps()
                },
            )
            val inWorkbench = AppPrefs.isWorkbenchApp(serviceContext, item.pkg)
            add(
                GlassMenuRow.Action(if (inWorkbench) "从工作台移出" else "添加到工作台卡片栏", HyperUi.ICON_SIDEBAR) {
                    if (inWorkbench) {
                        AppPrefs.removeWorkbenchApp(serviceContext, item.pkg)
                        serviceContext.toast("已移出工作台：$label")
                    } else if (AppPrefs.addWorkbenchApp(serviceContext, item.pkg)) {
                        if (!isWorkbenchEnabled) setWorkbenchEnabled(true)
                        serviceContext.toast("已添加到工作台：$label")
                    } else {
                        serviceContext.toast("工作台最多添加 ${AppPrefs.MAX_WORKBENCH_APPS} 个应用")
                    }
                    updateWorkbenchApps()
                },
            )
            if (win != null) {
                add(GlassMenuRow.Action("重载应用 DPI", HyperUi.ICON_ROTATE) { reloadWindowDpi(win.taskId, win.pkg) })
                add(GlassMenuRow.Divider)
                add(GlassMenuRow.Action("关闭应用", HyperUi.ICON_CLOSE, danger = true) { closeWindow(win.taskId, win.pkg) })
            }
        }
        contextMenu.show(
            title = label,
            subtitle = when {
                win == null -> "已固定快捷方式"
                win.minimized -> "已最小化"
                win.focused -> "当前激活窗口"
                else -> "后台运行中"
            },
            icon = icon,
            rows = rows,
            anchorX = anchorX,
            anchorY = anchorY,
            preferAboveAnchor = true,
        )
    }

    override fun setTaskbarAutoHide(enabled: Boolean) {
        isTaskbarAutoHide = enabled
        AppPrefs.setDockAutoHide(serviceContext, enabled)
        state = state.copy(taskbarAutoHide = enabled)
        taskbar.autoHide.autoHidePreference = enabled
        if (!enabled) {
            taskbar.autoHide.cancelTimer()
            taskbar.autoHide.setHiddenAnimated(false)
        } else {
            taskbar.autoHide.evaluate(false)
        }
        serviceContext.toast(if (enabled) "任务栏已设为自动隐藏（移至底边唤醒）" else "任务栏已固定显示")
        safeBgExecute { BridgeClient.setDesktopPrefs(enabled) }
        extCtx?.let { if (quickSettings.isOpen) quickSettings.syncState(it) }
    }

    override fun cycleGlassTheme() {
        val next = (ExtThemePrefs.getThemeModeIndex(serviceContext) + 1) % 3
        ExtThemePrefs.setThemeModeIndex(serviceContext, next)
        refreshTheme()
        val lbl = when (next) {
            ExtThemePrefs.MODE_LIGHT -> "冰透浅色毛玻璃"
            ExtThemePrefs.MODE_DARK -> "黑曜深色毛玻璃"
            else -> "毛玻璃跟随系统"
        }
        serviceContext.toast("桌面外观：$lbl")
    }

    override fun toggleBlackout() {
        if (lastStatus.isLocalDexActive) return
        svc.toggleBlackout()
    }

    override fun toggleInputRouting() {
        if (lastStatus.isLocalDexActive) return
        safeInputExecute {
            val m = BridgeClient.toggleInput()
            safePost {
                serviceContext.toast(if (m == 1) "键鼠 → 扩展屏" else "键鼠 → 本机")
                requestStateRefresh()
            }
        }
    }

    override fun sendGlobalBack() {
        safeInputExecute { BridgeClient.sendBack() }
    }

    override fun execSysAction(act: Int, arg: Int, str: String?) {
        if (lastStatus.isLocalDexActive && act == BridgeClient.SYS_SCREEN_DIM) return
        svc.execSysAndRefresh(act, arg, str)
    }

    override fun switchMirrorMode(mode: Int) {
        if (lastStatus.isLocalDexActive) return
        serviceContext.toast("正在切换为【${BridgeClient.mirrorModeLabel(mode)}】…")
        safeBgExecute {
            BridgeClient.setMirrorMode(mode)
            safePost { requestStateRefresh() }
        }
    }

    override fun cycleRotation() {
        if (lastStatus.isLocalDexActive) return
        safeBgExecute {
            val next = BridgeClient.cycleRotation()
            safePost {
                serviceContext.toast("输出旋转：${BridgeClient.rotationLabel(next)}")
                requestStateRefresh()
            }
        }
    }

    override fun openTabletMain() = svc.openTabletMain()
    override fun openTabletTouchpad() {
        if (lastStatus.isLocalDexActive) return
        svc.openTabletTouchpad()
    }
    override fun openExtSettings(action: String) = svc.openExtSettings(action)
    override fun exitLocalDex() = svc.exitLocalDex()
    override fun notifyHardwareChanged() = svc.onHardwareRefreshRequested()
    override fun requestStateRefresh() = svc.requestFullPoll()

    override fun updateTaskbarPinnedApps() {
        if (!attached) return
        applySnapshotOnMain(lastSnapshot, lastTasks)
    }

    // --- Workbench Mode (Stage Manager / Live App Card Rail) ---

    override fun toggleWorkbench() = setWorkbenchEnabled(!isWorkbenchEnabled)

    override fun setWorkbenchEnabled(enabled: Boolean) {
        isWorkbenchEnabled = enabled
        AppPrefs.setWorkbenchEnabled(serviceContext, enabled)
        if (enabled) seedWorkbenchStarterAppsIfEmpty()
        if (!attached) return
        workbenchRail.invalidateCards()
        renderAll()
        if (enabled) {
            val sideLabel = if (workbenchPosition == AppPrefs.WORKBENCH_POS_TOP) "顶部" else "左侧"
            serviceContext.toast("工作台模式已开启（${sideLabel}卡片栏）")
            // Gently shift currently focused floating window into the unobstructed Stage area
            state.focusedVisibleWindow?.let { fg ->
                if (!fg.isImmersive && fg.taskId > 0) {
                    val stage = workbenchRail.computeStageRect(
                        state.displayW, state.displayH, state.dpi, isTaskbarAutoHide, lastStatus.isLocalDexActive,
                    )
                    commitWindowBounds(fg.taskId, fg.pkg, stage, rememberPref = false)
                }
            }
        } else {
            serviceContext.toast("工作台模式已关闭")
        }
    }

    override fun setWorkbenchPosition(position: Int) {
        val normalized = if (position == AppPrefs.WORKBENCH_POS_TOP) AppPrefs.WORKBENCH_POS_TOP else AppPrefs.WORKBENCH_POS_LEFT
        if (workbenchPosition == normalized && isWorkbenchEnabled) return
        workbenchPosition = normalized
        AppPrefs.setWorkbenchPosition(serviceContext, normalized)
        if (!attached) return
        workbenchRail.invalidateCards()
        renderAll()
        if (isWorkbenchEnabled) {
            serviceContext.toast(if (normalized == AppPrefs.WORKBENCH_POS_TOP) "工作台已切至顶部栏" else "工作台已切至左侧栏")
            val fg = state.focusedVisibleWindow
            if (fg != null && !fg.isImmersive && fg.taskId > 0) {
                val stage = workbenchRail.computeStageRect(
                    state.displayW, state.displayH, state.dpi, isTaskbarAutoHide, lastStatus.isLocalDexActive,
                )
                commitWindowBounds(fg.taskId, fg.pkg, stage, rememberPref = false)
                val others = state.windows.filter { !it.minimized && it.taskId > 0 && it.taskId != fg.taskId }
                if (others.isNotEmpty()) {
                    safeBgExecute {
                        for (o in others) BridgeClient.windowLifecycleAction(o.taskId, o.pkg, BridgeClient.WIN_ACTION_MINIMIZE)
                    }
                }
            }
        }
    }

    override fun openWorkbenchAppPicker() {
        if (!attached) return
        workbenchPicker.open()
    }

    override fun switchToWorkbenchApp(pkg: String, taskId: Int) {
        if (!attached || pkg.isBlank()) return
        popovers.dismissAll()
        // Snapshot the current foreground window right before leaving so its card stays visually up-to-date
        val prevVisible = state.windows.filter { !it.minimized && it.pkg != pkg && it.taskId > 0 }
        state.focusedVisibleWindow?.let { cur ->
            if (cur.pkg != pkg && cur.taskId > 0) {
                workbenchRail.captureSnapshotNow(cur.taskId, cur.pkg)
            }
        }
        val stage = workbenchRail.computeStageRect(
            state.displayW, state.displayH, state.dpi, isTaskbarAutoHide, lastStatus.isLocalDexActive,
        )
        val liveWin = state.windows.firstOrNull { (taskId > 0 && it.taskId == taskId) || it.pkg == pkg }
        if (liveWin != null && liveWin.taskId > 0) {
            if (liveWin.focused && !liveWin.minimized) {
                // Already focused on stage -> refresh stage fit if obscured by rail, otherwise minimize
                val obscured = if (workbenchPosition == AppPrefs.WORKBENCH_POS_TOP) {
                    liveWin.bounds.top < stage.top - 12
                } else {
                    liveWin.bounds.left < stage.left - 12
                }
                if (obscured) {
                    commitWindowBounds(liveWin.taskId, liveWin.pkg, stage, rememberPref = false)
                } else {
                    minimizeWindow(liveWin.taskId, liveWin.pkg)
                }
            } else {
                val tid = liveWin.taskId
                state = DesktopStateReducer.applyOptimisticLease(state, tid, stage, SystemClock.uptimeMillis(), leaseDurationMs = 900L)
                renderAll()
                safeBgExecute {
                    BridgeClient.updateWindowBounds(
                        tid, liveWin.pkg, BridgeClient.WIN_MODE_FREEFORM,
                        stage.left, stage.top, stage.right, stage.bottom, false,
                    )
                    BridgeClient.windowLifecycleAction(tid, liveWin.pkg, BridgeClient.WIN_ACTION_FOCUS)
                    // Stage Manager semantics: cleanly sideline previous Stage windows so nothing peeks behind Top/Left rail
                    for (prev in prevVisible) {
                        BridgeClient.windowLifecycleAction(prev.taskId, prev.pkg, BridgeClient.WIN_ACTION_MINIMIZE)
                    }
                }
                workbenchRail.captureSnapshotNow(tid, liveWin.pkg, followUpDelayMs = 1100L)
            }
        } else {
            safeBgExecute {
                BridgeClient.launchInWindowMode(
                    pkg, "", BridgeClient.WIN_MODE_FREEFORM,
                    stage.left, stage.top, stage.right, stage.bottom,
                )
                for (prev in prevVisible) {
                    BridgeClient.windowLifecycleAction(prev.taskId, prev.pkg, BridgeClient.WIN_ACTION_MINIMIZE)
                }
            }
            // Refresh newly launched app's live card thumbnail once its first frame settles past splash screen
            handler.postDelayed({
                val launched = state.windows.firstOrNull { it.pkg == pkg && it.taskId > 0 }
                if (launched != null) workbenchRail.captureSnapshotNow(launched.taskId, launched.pkg)
            }, 1300L)
        }
    }

    override fun openWorkbenchCardMenu(pkg: String, taskId: Int, pinned: Boolean, anchorX: Int, anchorY: Int) {
        if (!attached || pkg.isBlank()) return
        val pm = serviceContext.packageManager
        val label = getCachedLabel(pm, pkg)
        val icon = getCachedIcon(pm, pkg)
        val liveWin = state.windows.firstOrNull { (taskId > 0 && it.taskId == taskId) || it.pkg == pkg }
        val rows = ArrayList<GlassMenuRow>()
        rows.add(
            GlassMenuRow.Action("切换到工作台主区", HyperUi.ICON_WINDOW) {
                switchToWorkbenchApp(pkg, liveWin?.taskId ?: taskId)
            },
        )
        rows.add(
            GlassMenuRow.Action("居中自由小窗打开", HyperUi.ICON_MAXIMIZE) {
                launchApp(pkg, "", SnapZone.NONE)
            },
        )
        rows.add(GlassMenuRow.Divider)
        if (pinned) {
            rows.add(
                GlassMenuRow.Action("移出工作台栏", HyperUi.ICON_PIN) {
                    AppPrefs.removeWorkbenchApp(serviceContext, pkg)
                    updateWorkbenchApps()
                    serviceContext.toast("已将 $label 移出工作台")
                },
            )
        } else {
            rows.add(
                GlassMenuRow.Action("常驻固定到工作台", HyperUi.ICON_PIN) {
                    if (AppPrefs.addWorkbenchApp(serviceContext, pkg)) {
                        updateWorkbenchApps()
                        serviceContext.toast("已固定 $label 到工作台")
                    } else {
                        serviceContext.toast("工作台最多添加 ${AppPrefs.MAX_WORKBENCH_APPS} 个应用")
                    }
                },
            )
        }
        if (liveWin != null && liveWin.taskId > 0) {
            rows.add(
                GlassMenuRow.Action("关闭应用窗口", HyperUi.ICON_CLOSE, danger = true) {
                    closeWindow(liveWin.taskId, liveWin.pkg)
                },
            )
        }
        contextMenu.show(label, if (liveWin != null) "工作台 · 运行中" else "工作台 · 待命", icon, rows, anchorX, anchorY)
    }

    override fun updateWorkbenchApps() {
        if (!attached) return
        workbenchRail.invalidateCards()
        renderAll()
    }

    private fun seedWorkbenchStarterAppsIfEmpty() {
        if (AppPrefs.isWorkbenchAppsInitialized(serviceContext) || AppPrefs.getWorkbenchApps(serviceContext).isNotEmpty()) return
        val seeds = LinkedHashSet<String>()
        for (w in state.windows) {
            if (w.pkg.isNotBlank() && w.pkg != serviceContext.packageName) seeds.add(w.pkg)
        }
        for (pkg in AppPrefs.getDockPinnedApps(serviceContext)) {
            if (pkg.isNotBlank() && pkg != serviceContext.packageName) seeds.add(pkg)
        }
        for (pkg in AppPrefs.getDesktopPinnedApps(serviceContext)) {
            if (seeds.size >= 4) break
            if (pkg.isNotBlank() && pkg != serviceContext.packageName) seeds.add(pkg)
        }
        if (seeds.isNotEmpty()) {
            AppPrefs.setWorkbenchApps(serviceContext, seeds.take(5))
        }
    }

    fun setTaskbarHiddenDirect(hidden: Boolean) {
        if (!attached) return
        taskbar.autoHide.setHiddenAnimated(hidden)
    }

    private fun effectiveDpi(st: BridgeClient.Status): Int =
        st.virtDpi.takeIf { it > 0 } ?: 240
}
