package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Owns Dock auto-hide scheduling, bottom-edge wake trigger strip, hover/tray hold state,
 * and cursor bottom-edge wake detection.
 */
internal class DockAutoHidePolicy(
    private val host: OverlayHost,
    private val dockRootProvider: () -> View?,
    private val onRepositionDock: () -> Unit,
) {
    companion object {
        private const val SURFACE_KEY_EDGE = "dock_edge_trigger"
        private const val AUTO_HIDE_DELAY_MS = 2600L
        private const val TRAY_DEBOUNCE_MS = 220L
        private const val EDGE_WAKE_ZONE_DP = 18f
        private const val DOCK_HOLD_ZONE_DP = 82f
        private const val EDGE_WAKE_HALF_WIDTH_DP = 190f
    }

    var isDockHidden: Boolean = false
        private set
    var isDockAutoHide: Boolean = true
    var isOnLauncherHome: Boolean = true

    private var autoHideScheduled = false
    private var dockHovered = false
    private var trayPressed = false
    private var lastTrayUpMs = 0L
    private var lastTrayToggleMs = 0L
    private var edgeTrigger: FrameLayout? = null

    private val autoHideTask = Runnable {
        autoHideScheduled = false
        if (isDockAutoHide && !isOnLauncherHome && !host.popovers.isAnyOpen() && !dockHovered && !trayPressed) {
            applyHiddenAnimated(true)
        }
    }

    fun isTrayInteractionRecent(): Boolean {
        val now = SystemClock.uptimeMillis()
        return trayPressed || (now - lastTrayUpMs) <= TRAY_DEBOUNCE_MS || (now - lastTrayToggleMs) <= TRAY_DEBOUNCE_MS
    }

    fun onDockHoverChanged(hovered: Boolean) {
        dockHovered = hovered
        if (hovered) cancelTimer() else evaluate(false)
    }

    fun onTrayPressedChanged(pressed: Boolean) {
        trayPressed = pressed
        if (!pressed) lastTrayUpMs = SystemClock.uptimeMillis()
    }

    fun markTrayToggled() { lastTrayToggleMs = SystemClock.uptimeMillis() }

    fun onCursorMoved(x: Float, y: Float) {
        val ctx = host.extCtx ?: return
        if (!isDockAutoHide || isOnLauncherHome || host.overlayH <= 0) return
        if (isDockHidden && y >= host.overlayH - ctx.dp(EDGE_WAKE_ZONE_DP) &&
            abs(x - host.overlayW * 0.5f) <= ctx.dp(EDGE_WAKE_HALF_WIDTH_DP)
        ) {
            wakeFromEdge()
        } else if (!isDockHidden && y >= host.overlayH - ctx.dp(DOCK_HOLD_ZONE_DP)) {
            cancelTimer()
            evaluate(true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildEdgeTrigger(ctx: Context) {
        val trigger = FrameLayout(ctx).apply {
            isClickable = true; isFocusable = false; visibility = View.GONE
            addView(
                View(ctx).apply { background = roundedBg(0x88FFFFFF.toInt(), ctx.dpF(2.5f), 1, 0x44000000) },
                ctx.flp(ctx.dp(108f), ctx.dp(4f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomDp = 4f),
            )
            setOnHoverListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER || ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) wakeFromEdge()
                false
            }
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { wakeFromEdge(); true } else false
            }
        }
        val lp = OverlayWindowParams.edgeStrip(ctx.dp(220f), ctx.dp(16f), host.winType, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        edgeTrigger = trigger
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                SURFACE_KEY_EDGE, OverlayLayer.DOCK, trigger, lp,
                launcherParamsProvider = { ctx.flp(ctx.dp(220f), ctx.dp(16f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) },
            ),
        )
    }

    fun cancelTimer() { host.handler.removeCallbacks(autoHideTask); autoHideScheduled = false }
    fun syncEdgeVisibility() { edgeTrigger?.visibility = if (isDockHidden) View.VISIBLE else View.GONE }

    fun wakeFromEdge() {
        if (isDockHidden) applyHiddenAnimated(false)
        evaluate(true)
    }

    fun evaluate(forceDelay: Boolean) {
        if (!isDockAutoHide || isOnLauncherHome) {
            cancelTimer()
            if (isDockHidden) applyHiddenAnimated(false)
            return
        }
        if (host.popovers.isAnyOpen() || dockHovered || trayPressed) { cancelTimer(); return }
        if ((isDockHidden && !forceDelay) || (!forceDelay && autoHideScheduled)) return
        host.handler.removeCallbacks(autoHideTask)
        autoHideScheduled = true
        host.handler.postDelayed(autoHideTask, AUTO_HIDE_DELAY_MS)
    }

    fun applyHidden(hidden: Boolean) {
        cancelTimer()
        isDockHidden = hidden
        if (hidden) host.popovers.dismissAll()
        dockRootProvider()?.let { dock ->
            dock.resetPopoverVisuals()
            dock.visibility = if (hidden) View.GONE else View.VISIBLE
            if (!hidden) onRepositionDock()
        }
        syncEdgeVisibility()
        host.cursor.bringCursorToFront()
    }

    fun applyHiddenAnimated(hidden: Boolean) {
        val dock = dockRootProvider()
        val ctx = host.extCtx
        if (dock == null || ctx == null) { applyHidden(hidden); return }
        if (hidden == isDockHidden && ((hidden && dock.visibility != View.VISIBLE) || (!hidden && dock.visibility == View.VISIBLE))) {
            syncEdgeVisibility(); return
        }
        cancelTimer()
        isDockHidden = hidden
        dock.animate().cancel()
        if (hidden) {
            host.popovers.dismissAll()
            syncEdgeVisibility()
            dock.animate().translationY(ctx.dpF(74f)).alpha(0f).setDuration(200L).setInterpolator(AccelerateInterpolator(1.3f))
                .withEndAction {
                    if (isDockHidden) dockRootProvider()?.let { it.visibility = View.GONE; it.resetPopoverVisuals() }
                    host.cursor.bringCursorToFront()
                }.start()
        } else {
            syncEdgeVisibility()
            dock.visibility = View.VISIBLE
            onRepositionDock()
            dock.translationY = ctx.dpF(64f); dock.alpha = 0f
            dock.animate().translationY(0f).alpha(1f).setDuration(200L).setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { host.cursor.bringCursorToFront() }.start()
        }
    }

    fun resetClosedVisuals() {
        if (isDockHidden) dockRootProvider()?.let { it.resetPopoverVisuals(); it.visibility = View.GONE }
    }

    fun detach() {
        cancelTimer()
        host.surfaceHost.unregister(SURFACE_KEY_EDGE, host.extWm)
        edgeTrigger = null; isDockHidden = false; dockHovered = false; trayPressed = false
    }
}
