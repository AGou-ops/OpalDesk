package io.github.lengsukq.opaldesk.desktop.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.PreviewOverlayState
import io.github.lengsukq.opaldesk.desktop.model.ResizeEdge
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.OverlayConstants
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared global 8-way resize edge strips attached strictly around the currently focused
 * desktop window (only 3 pre-registered WM surfaces for the entire desktop: Left, Right, Bottom;
 * Top/NW/NE are handled by the top 6dp edge of the focused [WindowHeaderBarPool] slot).
 *
 * Strips protrude outward from the window bezel (only 2dp inner overlap) so they NEVER block
 * app scrollbars or edge-swipe navigation inside apps. Dragging shows a 60fps frosted preview
 * with dp dimensions and commits a single clean `resizeTask` on release to eliminate app flicker.
 */
@SuppressLint("ClickableViewAccessibility")
internal class GlobalResizeEdgeController(private val host: DesktopShellHost) {

    companion object {
        private const val KEY_LEFT = "dex_resize_left"
        private const val KEY_RIGHT = "dex_resize_right"
        private const val KEY_BOTTOM = "dex_resize_bottom"
        private const val STRIP_THICKNESS_DP = 11f
        private const val INNER_OVERLAP_DP = 2f
        private const val CORNER_ZONE_DP = 26f

        internal fun pointerIconFor(edge: ResizeEdge): Int = when (edge) {
            ResizeEdge.N, ResizeEdge.S -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            ResizeEdge.E, ResizeEdge.W -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            ResizeEdge.NW, ResizeEdge.SE -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
            ResizeEdge.NE, ResizeEdge.SW -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
        }
    }

    private enum class StripSide { LEFT, RIGHT, BOTTOM }

    private var leftView: View? = null
    private var leftLp: WindowManager.LayoutParams? = null
    private var rightView: View? = null
    private var rightLp: WindowManager.LayoutParams? = null
    private var bottomView: View? = null
    private var bottomLp: WindowManager.LayoutParams? = null

    private var boundWindow: DesktopWindow? = null
    var isResizing: Boolean = false
        private set

    fun build(ctx: Context) {
        detach()
        val thick = ctx.dp(STRIP_THICKNESS_DP)
        leftLp = OverlayWindowParams.floating(thick, 100, host.winType, touchModal = false)
        rightLp = OverlayWindowParams.floating(thick, 100, host.winType, touchModal = false)
        bottomLp = OverlayWindowParams.floating(100, thick, host.winType, touchModal = false)

        leftView = createStrip(ctx, StripSide.LEFT)
        rightView = createStrip(ctx, StripSide.RIGHT)
        bottomView = createStrip(ctx, StripSide.BOTTOM)

        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(KEY_LEFT, OverlayLayer.BASE_CHROME, leftView!!, leftLp!!))
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(KEY_RIGHT, OverlayLayer.BASE_CHROME, rightView!!, rightLp!!))
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(KEY_BOTTOM, OverlayLayer.BASE_CHROME, bottomView!!, bottomLp!!))
    }

    fun updateForFocusedWindow(ctx: Context, focused: DesktopWindow?, dpi: Int) {
        if (isResizing) return
        boundWindow = focused
        if (focused == null || !focused.isVisibleChromeWindow) {
            hideAll()
            return
        }
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.state.displayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.state.displayH)
        val thick = ctx.dp(STRIP_THICKNESS_DP)
        val inner = ctx.dp(INNER_OVERLAP_DP)
        val outerOut = thick - inner
        val outer = DesktopGeometry.computeOuterFrame(focused.bounds, dpi)

        val sideTop = outer.top.coerceIn(0, max(0, dispH - thick))
        val sideBottom = (outer.bottom + outerOut).coerceIn(sideTop + thick, dispH)
        val sideH = max(thick, sideBottom - sideTop)

        val lx = (outer.left - outerOut).coerceIn(0, max(0, dispW - thick))
        val rx = (outer.right - inner).coerceIn(0, max(0, dispW - thick))

        val botLeft = (outer.left - outerOut).coerceIn(0, max(0, dispW - thick))
        val botRight = (outer.right + outerOut).coerceIn(botLeft + thick, dispW)
        val botW = max(thick, botRight - botLeft)
        val by = (outer.bottom - inner).coerceIn(0, max(0, dispH - thick))

        updateStrip(leftView, leftLp, lx, sideTop, thick, sideH)
        updateStrip(rightView, rightLp, rx, sideTop, thick, sideH)
        updateStrip(bottomView, bottomLp, botLeft, by, botW, thick)
    }

    fun hideAll() {
        leftView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        rightView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        bottomView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    fun detach() {
        isResizing = false
        boundWindow = null
        host.surfaceHost.unregister(KEY_LEFT, host.extWm)
        host.surfaceHost.unregister(KEY_RIGHT, host.extWm)
        host.surfaceHost.unregister(KEY_BOTTOM, host.extWm)
        leftView = null; leftLp = null
        rightView = null; rightLp = null
        bottomView = null; bottomLp = null
    }

    private fun updateStrip(view: View?, lp: WindowManager.LayoutParams?, x: Int, y: Int, w: Int, h: Int) {
        if (view == null || lp == null) return
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        if (lp.x != x || lp.y != y || lp.width != w || lp.height != h) {
            lp.x = x; lp.y = y; lp.width = w; lp.height = h
            host.surfaceHost.updateWindowLayout(view, lp, host.extWm)
        }
    }

    private fun createStrip(ctx: Context, side: StripSide): View {
        val cornerPx = ctx.dp(CORNER_ZONE_DP)
        return View(ctx).apply {
            visibility = View.GONE
            setOnHoverListener { v, ev ->
                val edge = resolveEdge(side, ev.x, ev.y, v.width, v.height, cornerPx)
                v.pointerIcon = PointerIcon.getSystemIcon(ctx, pointerIconFor(edge))
                false
            }
            var downRawX = 0f
            var downRawY = 0f
            var activeEdge = ResizeEdge.E
            var baseRect = IntRect.EMPTY
            var liveRect = IntRect.EMPTY

            setOnTouchListener { v, ev ->
                val win = boundWindow ?: return@setOnTouchListener false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        isResizing = true
                        host.popovers.dismissAll()
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        activeEdge = resolveEdge(side, ev.x, ev.y, v.width, v.height, cornerPx)
                        v.pointerIcon = PointerIcon.getSystemIcon(ctx, pointerIconFor(activeEdge))
                        baseRect = win.bounds
                        liveRect = baseRect
                        showResizePreview(liveRect)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downRawX).roundToInt()
                        val dy = (ev.rawY - downRawY).roundToInt()
                        val st = host.state
                        liveRect = DesktopGeometry.computeResizedRect(
                            baseRect, activeEdge, dx, dy, st.displayW, st.displayH, st.dpi, host.isTaskbarAutoHide,
                        )
                        showResizePreview(liveRect)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isResizing = false
                        host.clearPreview()
                        if (ev.actionMasked == MotionEvent.ACTION_UP && liveRect != baseRect && !liveRect.isEmpty) {
                            host.commitWindowBounds(win.taskId, win.pkg, liveRect, rememberPref = true)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun showResizePreview(taskRect: IntRect) {
        val dpi = host.state.dpi
        val outer = DesktopGeometry.computeOuterFrame(taskRect, dpi)
        val wDp = DesktopGeometry.pxToDp(taskRect.width, dpi)
        val hDp = DesktopGeometry.pxToDp(taskRect.height, dpi)
        host.updatePreview(
            PreviewOverlayState(
                active = true,
                outerRect = outer,
                hudLabel = "$wDp × $hDp dp",
                isSnapped = false,
            ),
        )
    }

    private fun resolveEdge(side: StripSide, localX: Float, localY: Float, w: Int, h: Int, cornerPx: Int): ResizeEdge = when (side) {
        StripSide.LEFT -> when {
            localY <= cornerPx -> ResizeEdge.NW
            localY >= h - cornerPx -> ResizeEdge.SW
            else -> ResizeEdge.W
        }
        StripSide.RIGHT -> when {
            localY <= cornerPx -> ResizeEdge.NE
            localY >= h - cornerPx -> ResizeEdge.SE
            else -> ResizeEdge.E
        }
        StripSide.BOTTOM -> when {
            localX <= cornerPx -> ResizeEdge.SW
            localX >= w - cornerPx -> ResizeEdge.SE
            else -> ResizeEdge.S
        }
    }
}
