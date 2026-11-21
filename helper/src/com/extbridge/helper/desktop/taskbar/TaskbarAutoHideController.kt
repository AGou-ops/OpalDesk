package com.extbridge.helper.desktop.taskbar

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.flp
import com.extbridge.helper.overlay.resetPopoverVisuals
import com.extbridge.helper.overlay.roundedBg

/**
 * Clean state machine governing Samsung DeX Taskbar visibility:
 * - Pinned mode: stays visible on desktop and alongside floating/maximized windows,
 *   auto-hides strictly when an `IMMERSIVE` fullscreen app is active.
 * - Auto-hide mode: collapses to a subtle bottom edge handle after idle delay whenever
 *   a window is open; wakes smoothly on bottom-edge cursor hover or upward touch swipe.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TaskbarAutoHideController(
    private val host: DesktopShellHost,
    private val taskbarViewProvider: () -> View?,
) {
    companion object {
        private const val SURFACE_KEY_EDGE = "dex_taskbar_wake_strip"
        private const val AUTO_HIDE_DELAY_MS = 2200L
        private const val TRAY_DEBOUNCE_MS = 240L
        private const val WAKE_EDGE_DP = 14f
        private const val HOLD_ZONE_DP = 76f
    }

    var isHidden: Boolean = false
        private set
    var autoHidePreference: Boolean = false
    var isDesktopClear: Boolean = true
    var isImmersiveActive: Boolean = false

    private var taskbarHovered = false
    private var trayPressed = false
    private var lastTrayUpMs = 0L
    private var lastTrayToggleMs = 0L
    private var edgeRoot: FrameLayout? = null
    private var edgePill: View? = null

    private val shouldAutoHideNow: Boolean
        get() = isImmersiveActive || (autoHidePreference && !isDesktopClear)

    private val autoHideTask = Runnable {
        if (shouldAutoHideNow && !host.popovers.isAnyOpen() && !taskbarHovered && !trayPressed) {
            setHiddenAnimated(true)
        }
    }

    fun isTrayInteractionRecent(): Boolean {
        val now = SystemClock.uptimeMillis()
        return trayPressed || (now - lastTrayUpMs) <= TRAY_DEBOUNCE_MS || (now - lastTrayToggleMs) <= TRAY_DEBOUNCE_MS
    }

    fun onTaskbarHoverChanged(hovered: Boolean) {
        taskbarHovered = hovered
        if (hovered) cancelTimer() else evaluate(forceDelay = false)
    }

    fun onTrayPressedChanged(pressed: Boolean) {
        trayPressed = pressed
        if (!pressed) lastTrayUpMs = SystemClock.uptimeMillis()
    }

    fun markTrayToggled() {
        lastTrayToggleMs = SystemClock.uptimeMillis()
    }

    fun buildWakeStrip(ctx: Context) {
        detach()
        var downY = 0f
        var woken = false
        val pill = View(ctx)
        val strip = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            addView(pill, ctx.flp(ctx.dp(132f), ctx.dp(4f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottomDp = 4f))
            setOnHoverListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER || ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                    wakeFromEdge()
                }
                false
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downY = ev.rawY; woken = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        if (!woken && downY - ev.rawY >= ctx.dp(6f)) {
                            woken = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            wakeFromEdge()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!woken) {
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            wakeFromEdge()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val lp = OverlayWindowParams.edgeStrip(
            ctx.dp(320f), ctx.dp(20f), host.winType, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )
        edgeRoot = strip
        edgePill = pill
        applyTheme(ctx, host.tokens)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY_EDGE, OverlayLayer.DOCK, strip, lp))
    }

    fun applyTheme(ctx: Context, tokens: GlassOverlayTokens) {
        edgePill?.background = roundedBg(tokens.handlePillIdle, ctx.dpF(2.5f), ctx.dp(1f), tokens.handlePillStroke)
    }

    fun onCursorMoved(y: Float) {
        val ctx = host.extCtx ?: return
        val dispH = host.state.displayH
        if (!shouldAutoHideNow || dispH <= 0) return
        if (isHidden && y >= dispH - ctx.dp(WAKE_EDGE_DP)) {
            wakeFromEdge()
        } else if (!isHidden && y >= dispH - ctx.dp(HOLD_ZONE_DP)) {
            cancelTimer()
            evaluate(forceDelay = true)
        }
    }

    fun wakeFromEdge() {
        if (isHidden) setHiddenAnimated(false)
        evaluate(forceDelay = true)
    }

    fun evaluate(forceDelay: Boolean) {
        if (!shouldAutoHideNow) {
            cancelTimer()
            if (isHidden) setHiddenAnimated(false)
            return
        }
        if (host.popovers.isAnyOpen() || taskbarHovered || trayPressed) {
            cancelTimer()
            return
        }
        if (isHidden && !forceDelay) return
        host.handler.removeCallbacks(autoHideTask)
        host.handler.postDelayed(autoHideTask, AUTO_HIDE_DELAY_MS)
    }

    fun cancelTimer() {
        host.handler.removeCallbacks(autoHideTask)
    }

    fun setHiddenAnimated(hidden: Boolean) {
        val bar = taskbarViewProvider()
        val ctx = host.extCtx
        cancelTimer()
        if (bar == null || ctx == null) {
            isHidden = hidden
            syncEdgeVisibility()
            return
        }
        if (isHidden == hidden && ((hidden && bar.visibility != View.VISIBLE) || (!hidden && bar.visibility == View.VISIBLE))) {
            syncEdgeVisibility()
            return
        }
        isHidden = hidden
        bar.animate().cancel()
        if (hidden) {
            host.popovers.dismissAll()
            syncEdgeVisibility()
            bar.animate()
                .translationY(ctx.dpF(72f))
                .alpha(0f)
                .setDuration(190L)
                .setInterpolator(AccelerateInterpolator(1.3f))
                .withEndAction {
                    if (isHidden) {
                        bar.visibility = View.GONE
                        bar.resetPopoverVisuals()
                    }
                    host.bringCursorToFront()
                }
                .start()
        } else {
            syncEdgeVisibility()
            bar.visibility = View.VISIBLE
            bar.translationY = ctx.dpF(64f)
            bar.alpha = 0f
            bar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { host.bringCursorToFront() }
                .start()
        }
    }

    private fun syncEdgeVisibility() {
        edgeRoot?.visibility = if (isHidden) View.VISIBLE else View.GONE
    }

    fun detach() {
        cancelTimer()
        host.surfaceHost.unregister(SURFACE_KEY_EDGE, host.extWm)
        edgeRoot = null; edgePill = null
        isHidden = false; taskbarHovered = false; trayPressed = false
    }
}
