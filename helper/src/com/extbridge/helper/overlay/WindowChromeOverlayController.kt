package com.extbridge.helper.overlay

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.max

/**
 * Thin coordinator for v4.1 adaptive desktop window chrome registered with [OverlaySurfaceHost]
 * and [PopoverCoordinator].
 */
class WindowChromeOverlayController(private val host: OverlayHost) {

    companion object {
        private const val MAX_FREEFORM_SLOTS = 4
        private const val SURFACE_KEY_PREVIEW = "wc_preview"
        private const val FREEFORM_MENU_ID = "wc_freeform_menu"
    }

    private var previewView: PreviewOverlayView? = null
    private var previewLp: WindowManager.LayoutParams? = null
    private var freeformMenuTarget: BridgeClient.ManagedWindowInfo? = null
    private var activeFreeformDragging = false
    private val freeformSlots = arrayOfNulls<FreeformWindowChromeSlot>(MAX_FREEFORM_SLOTS)
    private val delayedPollTask = Runnable { host.pollRemoteStateAsync() }

    private val singleHandleController = SingleWindowHandleController(
        host,
        onShowPreview = { r, lbl, snap -> showPreview(r, lbl, snap) },
        onHidePreview = { hidePreview() },
        onCommitBounds = { tid, pkg, mode, rect, rem -> commitWindowBounds(tid, pkg, mode, rect, rem) },
        onReloadDpi = { tid, pkg -> reloadWindowDpi(tid, pkg) },
    )
    private val freeformMenu = WindowActionMenu(host, FREEFORM_MENU_ID, widthDp = 232f, hasSubtitle = false) {
        freeformMenuTarget = null
    }

    fun buildOverlays(ctx: Context, st: BridgeClient.Status) {
        detach()
        if (st.isRemoteStreaming) return
        val preview = PreviewOverlayView(ctx).apply { visibility = View.GONE }
        val pLp = OverlayWindowParams.fullscreenNonTouchable(
            max(OverlayConstants.MIN_SURFACE_PX, host.overlayW), max(OverlayConstants.MIN_SURFACE_PX, host.overlayH), host.winType,
        )
        previewView = preview; previewLp = pLp
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY_PREVIEW, OverlayLayer.POPOVER, preview, pLp))
        singleHandleController.build(ctx)

        freeformMenu.build(ctx, listOf(
            listOf(
                MenuChipSpec("置顶聚焦", HyperUi.ICON_PIN) { freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_FOCUS) } },
                MenuChipSpec("居中宽幅", HyperUi.ICON_RATIO) {
                    freeformMenuTarget?.let { w ->
                        val b = BridgeClient.computeCenteredSingleBoundsPx(
                            max(OverlayConstants.MIN_SURFACE_PX, host.overlayW), max(OverlayConstants.MIN_SURFACE_PX, host.overlayH),
                            WindowChromeGeometry.effectiveDpi(host), host.lastStatus.defaultSingleWidthDp,
                        )
                        commitWindowBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, Rect(b[0], b[1], b[2], b[3]), true)
                    }
                    freeformMenu.close()
                },
            ),
            listOf(
                MenuChipSpec("最大化", HyperUi.ICON_FULLSCREEN) {
                    freeformMenuTarget?.let { w ->
                        commitWindowBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_FULLSCREEN, OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH), false)
                    }
                    freeformMenu.close()
                },
                MenuChipSpec("最小化", HyperUi.ICON_MINUS) { freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_MINIMIZE) } },
            ),
            listOf(
                MenuChipSpec("重载 DPI", HyperUi.ICON_ROTATE) {
                    freeformMenuTarget?.let { host.serviceContext.toast("正在重载应用 DPI 并重启…"); performWindowAction(it, BridgeClient.WIN_ACTION_RELOAD_DPI) }
                },
                MenuChipSpec("关闭", HyperUi.ICON_CLOSE, danger = true) { freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_CLOSE) } },
            ),
        ))
        updateFromState(st, host.lastWindowSnapshot, null)
    }

    fun updateFromState(st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot, tasks: List<BridgeClient.TaskEntry>?) {
        val ctx = host.extCtx ?: return
        val dexActive = st.connected && st.vdId > 0 && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX && !st.isRemoteStreaming
        if (!dexActive || st.isMultiTilingActive || BridgeClient.isTilingTopology(snap.tilingTopology)) {
            singleHandleController.hideAll()
            freeformSlots.forEach { it?.hide() }
            freeformMenu.close()
            return
        }
        val freeforms = snap.windows.filter {
            !it.minimized && it.mode.equals(BridgeClient.LAYOUT_FREEFORM, ignoreCase = true) && !OverlayConstants.isSystemOrLauncherPkg(it.pkg)
        }.take(MAX_FREEFORM_SLOTS)

        if (!activeFreeformDragging) {
            for (i in 0 until MAX_FREEFORM_SLOTS) {
                if (i < freeforms.size) {
                    val slot = freeformSlots[i] ?: createFreeformSlot(ctx, i).also { freeformSlots[i] = it }
                    slot.bindAndShow(ctx, freeforms[i])
                } else {
                    freeformSlots[i]?.hide()
                }
            }
        }
        singleHandleController.updateFromState(ctx, st, snap, tasks, freeforms.isNotEmpty())
    }

    fun onOverlayBoundsChanged() {
        val ctx = host.extCtx ?: return
        previewView?.let { pv ->
            previewLp?.let { lp ->
                lp.width = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
                lp.height = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
                host.surfaceHost.updateWindowLayout(pv, lp, host.extWm)
            }
        }
        singleHandleController.onOverlayBoundsChanged(ctx)
        freeformSlots.forEach { slot -> slot?.boundWindow?.takeIf { slot.isVisible }?.let { slot.positionForWindow(ctx, it, force = true) } }
    }

    fun dismissMenus() { singleHandleController.dismissMenu(); freeformMenu.close() }

    fun detach() {
        host.handler.removeCallbacks(delayedPollTask)
        activeFreeformDragging = false; freeformMenuTarget = null
        singleHandleController.detach(); freeformMenu.detach()
        host.surfaceHost.unregister(SURFACE_KEY_PREVIEW, host.extWm)
        previewView = null; previewLp = null
        for (i in freeformSlots.indices) { freeformSlots[i]?.detach(); freeformSlots[i] = null }
    }

    private fun createFreeformSlot(ctx: Context, i: Int) = FreeformWindowChromeSlot(
        ctx, i, host,
        onDraggingChanged = { activeFreeformDragging = it },
        onShowPreview = { r, lbl, snap -> showPreview(r, lbl, snap) },
        onHidePreview = { hidePreview() },
        onOpenMenu = { win, ax, ay ->
            freeformMenuTarget = win
            freeformMenu.show("${host.getCachedLabel(host.serviceContext.packageManager, win.pkg)} · 窗口控制") { _, _ -> ax to ay }
        },
        onCommitBounds = { tid, pkg, mode, rect, rem -> commitWindowBounds(tid, pkg, mode, rect, rem) },
        onWindowAction = { win, act -> performWindowAction(win, act) },
    )

    private fun showPreview(rect: Rect, label: String, snap: SnapZone) {
        previewView?.let { it.updatePreview(rect, label, snap); if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
    }

    private fun hidePreview() { previewView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE } }

    private fun commitWindowBounds(taskId: Int, pkg: String, mode: Int, rect: Rect, rememberPref: Boolean) =
        host.safeBgExecute { BridgeClient.updateWindowBounds(taskId, pkg, mode, rect, rememberPref); scheduleDelayedPoll(120L) }

    private fun reloadWindowDpi(taskId: Int, pkg: String) =
        host.safeBgExecute { BridgeClient.windowLifecycleAction(taskId, pkg, BridgeClient.WIN_ACTION_RELOAD_DPI); scheduleDelayedPoll(250L) }

    private fun performWindowAction(win: BridgeClient.ManagedWindowInfo, action: Int) {
        freeformMenu.close()
        host.safeBgExecute { BridgeClient.windowLifecycleAction(win.taskId, win.pkg, action); scheduleDelayedPoll(150L) }
    }

    private fun scheduleDelayedPoll(delayMs: Long) = host.safePost {
        host.handler.removeCallbacks(delayedPollTask); host.handler.postDelayed(delayedPollTask, delayMs)
    }
}
