package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.extbridge.helper.BridgeClient
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinates the Control Center flyout lifecycle, [PopoverCoordinator] mutual exclusion,
 * dock-anchored positioning via [OverlaySurfaceHost], and open/close animation.
 */
class ControlPanelOverlayController(private val host: OverlayHost) : OverlayPopover {

    companion object {
        private const val TAG = "ExtControlPanel"
        private const val POPOVER_ID = "control_panel"
        private const val OUTSIDE_DISMISS_DEBOUNCE_MS = 70L
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var panelView: ControlPanelView? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private val outsideDismissTask = Runnable {
        if (isOpen && !host.dock.isTrayInteractionRecent()) setPanelOpen(false)
    }

    override fun dismiss() = setPanelOpen(false)
    fun toggle() = setPanelOpen(!isOpen)
    fun cancelOutsideDismiss() = host.handler.removeCallbacks(outsideDismissTask)
    fun isPointInCard(winX: Float, winY: Float): Boolean = OverlayUiUtils.isPointInView(panelView?.card, winX, winY)
    fun repositionIfOpen() { if (isOpen) repositionPanel() }

    fun resetClosedVisualsBeforeHostSwap() {
        if (!isOpen) {
            panelView?.card?.resetPopoverVisuals()
            panelView?.root?.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildPanelOverlay(ctx: Context, st: BridgeClient.Status) {
        val view = ControlPanelView(ctx, host, st) { setPanelOpen(false) }
        val lp = OverlayWindowParams.popover(
            ctx.dp(ControlPanelView.ROOT_WIDTH_DP), WRAP, host.winType,
            x = max(16, st.virtW - ctx.dp(366f)), y = max(16, st.virtH - ctx.dp(410f)),
        )
        view.root.setOnTouchListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen && !host.dock.isTrayInteractionRecent()) {
                host.handler.removeCallbacks(outsideDismissTask)
                host.handler.postDelayed(outsideDismissTask, OUTSIDE_DISMISS_DEBOUNCE_MS)
            }
            false
        }
        view.root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if ((r - l) != (or2 - ol) || (b - t) != (ob - ot)) repositionPanel()
        }
        panelView = view; panelLp = lp; isOpen = false

        host.popovers.register(this)
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                key = POPOVER_ID,
                layer = OverlayLayer.POPOVER,
                view = view.root,
                wmParams = lp,
                launcherParamsProvider = { ctx.flp(ctx.dp(ControlPanelView.ROOT_WIDTH_DP), WRAP, Gravity.TOP or Gravity.LEFT, lp.x, lp.y) },
            ),
        )
    }

    fun updateDockAutoHideUi() { panelView?.updateDockAutoHide() }
    fun updateBlackoutUi() { panelView?.updateBlackout() }
    fun updateSplitButton(splitActive: Boolean) { panelView?.updateSplitButton(splitActive) }
    fun updateStatusChips(st: BridgeClient.Status) { panelView?.updateStatusChips(st) }
    fun updateInputToggleUi(inputToExt: Int) { panelView?.updateInputToggle(inputToExt) }
    fun refreshLocalHardwareViews(volLevel: Int) { panelView?.refreshLocalHardware(volLevel) }
    fun refreshVolumeIconOnly(volLevel: Int) { panelView?.refreshVolumeIconOnly(volLevel) }
    fun refreshSysViews(sys: BridgeClient.SysStatus) { panelView?.refreshSysStatus(sys) }

    fun setPanelOpen(open: Boolean) {
        host.handler.removeCallbacks(outsideDismissTask)
        val pv = panelView
        if (isOpen == open && (pv == null || (open && pv.root.visibility == View.VISIBLE))) return
        isOpen = open
        host.dock.updateTrayPillBackground(open)
        if (open) {
            host.popovers.dismissAllExcept(POPOVER_ID)
            host.dock.cancelAutoHideTimer()
            if (host.dock.isDockHidden) host.dock.applyDockHiddenAnimated(false)
        } else {
            host.dock.evaluateDockAutoHide(false)
        }
        if (pv == null || host.extCtx == null) return

        val card = pv.card
        card.animate().cancel()
        if (open) {
            pv.root.visibility = View.VISIBLE
            repositionPanel()
            host.onLocalHardwareRefreshRequested()
            host.pollRemoteStateAsync()
            card.alpha = 0f
            card.post {
                if (!isOpen) return@post
                if (card.width > 0 && card.height > 0) { card.pivotX = card.width * 0.85f; card.pivotY = card.height.toFloat() }
                card.animatePopover(show = true, shiftYDp = 18f, startScale = 0.94f, enterMs = 200L) {
                    host.cursor.bringCursorToFront()
                }
            }
        } else {
            if (card.width > 0 && card.height > 0) { card.pivotX = card.width * 0.85f; card.pivotY = card.height.toFloat() }
            card.animatePopover(show = false, shiftYDp = 18f, startScale = 0.95f, exitMs = 150L) {
                if (!isOpen) panelView?.root?.visibility = View.GONE
            }
        }
    }

    fun repositionPanel() {
        val root = panelView?.root ?: return
        val lp = panelLp ?: return
        val ctx = host.extCtx ?: return
        try {
            val surfW = host.surfaceWidth; val surfH = host.surfaceHeight
            val pw = root.width.takeIf { it > 0 } ?: ctx.dp(ControlPanelView.CARD_WIDTH_DP)
            val ph = root.height.takeIf { it > 0 } ?: ctx.dp(320f)
            val dock = host.dock.dockRoot
            val dockW = dock?.width?.takeIf { it > 0 } ?: ctx.dp(520f)
            val dockH = dock?.height?.takeIf { it > 0 } ?: ctx.dp(46f)
            val anchorRight = if (host.inLauncherHost && dock != null && dock.width > 0) dock.right else (surfW + dockW) / 2
            val anchorTop = if (host.inLauncherHost && dock != null && dock.height > 0) dock.top else surfH - ctx.dp(10f) - dockH
            val margin = ctx.dp(14f)
            host.surfaceHost.updatePosition(
                root, lp, host.inLauncherHost, host.extWm,
                x = min(surfW - pw - margin, max(margin, anchorRight - pw)),
                y = max(margin, anchorTop - ph - ctx.dp(10f)),
                width = lp.width, height = lp.height, gravity = Gravity.TOP or Gravity.LEFT,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "repositionPanel failed", t)
        }
    }

    fun detach() {
        host.handler.removeCallbacks(outsideDismissTask)
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        panelView = null; panelLp = null; isOpen = false
    }
}
