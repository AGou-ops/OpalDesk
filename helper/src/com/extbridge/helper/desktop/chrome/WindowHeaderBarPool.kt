package com.extbridge.helper.desktop.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.HyperUi
import com.extbridge.helper.desktop.model.DesktopGeometry
import com.extbridge.helper.desktop.model.DesktopWindow
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.model.IntRect
import com.extbridge.helper.desktop.model.PreviewOverlayState
import com.extbridge.helper.desktop.model.ResizeEdge
import com.extbridge.helper.desktop.model.SnapZone
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.MATCH
import com.extbridge.helper.overlay.OverlayConstants
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.flp
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.roundedBg
import com.extbridge.helper.overlay.setPadDp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pre-allocated pool of Samsung DeX 38dp Liquid Glass window header bars.
 *
 * Registered ONCE at display attach (`MAX_POOL_SIZE` fixed slots, idle slots `GONE`) so opening
 * new windows NEVER triggers `OverlaySurfaceHost.raiseHigherWmLayers()` full-desktop flicker.
 *
 * Layout (Samsung DeX):
 * - Left:  `‹` Window Back button + App Icon + Title
 * - Mid:   Drag to move / Double-tap to Maximize-Restore / Drag down when maximized to Restore /
 *          Top 6dp edge triggers North / NW / NE 8-way resize
 * - Right: `⋯` More & Size Presets | `−` Minimize | `□`/`❐` Maximize-Restore (hover 380ms -> Snap Layouts) | `×` Close
 */
@SuppressLint("ClickableViewAccessibility")
internal class WindowHeaderBarPool(private val host: DesktopShellHost) {

    companion object {
        private const val MAX_POOL_SIZE = 8
        private const val KEY_PREFIX = "dex_hdr_"
        private const val DOUBLE_TAP_MS = 280L
        private const val SNAP_HOVER_DELAY_MS = 380L
        private const val TOP_RESIZE_EDGE_DP = 6f
        private const val TOP_CORNER_ZONE_DP = 24f
        private const val DRAG_UNMAX_THRESHOLD_DP = 14f
    }

    private val slots = ArrayList<HeaderSlot>(MAX_POOL_SIZE)
    private var activeDragSlotIdx = -1
    var isDraggingOrResizing: Boolean = false
        private set

    fun build(ctx: Context) {
        detach()
        val entries = ArrayList<OverlaySurfaceHost.SurfaceEntry>(MAX_POOL_SIZE)
        for (i in 0 until MAX_POOL_SIZE) {
            val slot = HeaderSlot(ctx, i)
            slots.add(slot)
            entries.add(OverlaySurfaceHost.SurfaceEntry("$KEY_PREFIX$i", OverlayLayer.BASE_CHROME, slot.root, slot.lp))
        }
        entries.forEach { host.surfaceHost.register(it) }
    }

    fun render(ctx: Context, visibleWindows: List<DesktopWindow>, dpi: Int, tokens: GlassOverlayTokens) {
        if (isDraggingOrResizing) return
        val capped = visibleWindows.takeLast(MAX_POOL_SIZE)
        val outerRects = capped.map { DesktopGeometry.computeOuterFrame(it.bounds, dpi) }
        val minVisPx = DesktopGeometry.dpToPx(DesktopGeometry.MIN_VISIBLE_HEADER_DP, dpi)

        for (i in 0 until MAX_POOL_SIZE) {
            val slot = slots.getOrNull(i) ?: continue
            if (i < capped.size) {
                val win = capped[i]
                val upperOuters = if (i + 1 < outerRects.size) outerRects.subList(i + 1, outerRects.size) else emptyList()
                slot.bind(ctx, win, upperOuters, dpi, minVisPx, tokens)
            } else {
                slot.hide()
            }
        }
    }

    fun isPointInAnyHeader(screenX: Float, screenY: Float): Boolean =
        slots.any { it.isVisible && OverlayUiUtils.isPointInViewOnScreen(it.root, screenX, screenY) }

    fun handleRightClickAt(screenX: Int, screenY: Int): Boolean {
        val hit = slots.lastOrNull { it.isVisible && OverlayUiUtils.isPointInViewOnScreen(it.root, screenX.toFloat(), screenY.toFloat()) }
            ?: return false
        val win = hit.boundWindow ?: return false
        host.openWindowMenu(win, screenX, hit.lp.y + hit.lp.height + 6)
        return true
    }

    fun detach() {
        isDraggingOrResizing = false
        activeDragSlotIdx = -1
        for (slot in slots) {
            slot.cancelPendingHover()
            host.surfaceHost.unregister("$KEY_PREFIX${slot.index}", host.extWm)
        }
        slots.clear()
    }

    private inner class HeaderSlot(ctx: Context, val index: Int) {
        val headerH = ctx.dp(DesktopGeometry.WINDOW_HEADER_HEIGHT_DP.toFloat())
        val root: FrameLayout
        val innerRow: LinearLayout
        val lp: WindowManager.LayoutParams

        private val btnBack: ImageView
        private val iconView: ImageView
        private val titleView: TextView
        private val btnMore: ImageView
        private val btnMin: ImageView
        private val btnMax: ImageView
        private val btnClose: ImageView

        var boundWindow: DesktopWindow? = null
        var isVisible: Boolean = false
        private var lastPkg = ""
        private var lastFocused: Boolean? = null
        private var lastMaximized: Boolean? = null
        private var lastDark: Boolean? = null
        private var lastTapMs = 0L
        private var snapHoverTask: Runnable? = null

        init {
            root = FrameLayout(ctx).apply {
                visibility = View.GONE
                clipChildren = true
                clipToPadding = true
                elevation = ctx.dpF(8f)
            }
            innerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadDp(6f, 0f, 6f, 0f)
            }
            btnBack = makeCtrlBtn(ctx, "返回上一页") {
                boundWindow?.let { host.sendBackToWindow(it.taskId, it.pkg) }
            }
            innerRow.addView(btnBack, ctx.llp(ctx.dp(28f), ctx.dp(28f), rightDp = 4f))

            iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            innerRow.addView(iconView, ctx.llp(ctx.dp(17f), ctx.dp(17f), rightDp = 7f))

            titleView = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                paint.isFakeBoldText = true
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            innerRow.addView(titleView, ctx.llp(0, WRAP, 1f, rightDp = 8f))

            btnMore = makeCtrlBtn(ctx, "窗口布局与选项") {
                boundWindow?.let { w ->
                    val loc = IntArray(2)
                    btnMore.getLocationOnScreen(loc)
                    host.openWindowMenu(w, loc[0] - ctx.dp(180f), lp.y + headerH + 6)
                }
            }
            btnMin = makeCtrlBtn(ctx, "最小化") {
                boundWindow?.let { host.minimizeWindow(it.taskId, it.pkg) }
            }
            btnMax = makeCtrlBtn(ctx, "最大化 / 还原（悬停选分屏模板）") {
                cancelPendingHover()
                boundWindow?.let { host.toggleMaximizeWindow(it) }
            }.apply {
                setOnHoverListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_HOVER_ENTER -> scheduleSnapLayoutsHover(v)
                        MotionEvent.ACTION_HOVER_EXIT -> cancelPendingHover()
                    }
                    false
                }
            }
            btnClose = makeCtrlBtn(ctx, "关闭窗口", isDanger = true) {
                boundWindow?.let { host.closeWindow(it.taskId, it.pkg) }
            }

            innerRow.addView(btnMore, ctx.llp(ctx.dp(28f), ctx.dp(28f), rightDp = 2f))
            innerRow.addView(btnMin, ctx.llp(ctx.dp(28f), ctx.dp(28f), rightDp = 2f))
            innerRow.addView(btnMax, ctx.llp(ctx.dp(28f), ctx.dp(28f), rightDp = 2f))
            innerRow.addView(btnClose, ctx.llp(ctx.dp(28f), ctx.dp(28f)))

            root.addView(innerRow, ctx.flp(MATCH, headerH, Gravity.TOP or Gravity.LEFT))
            lp = OverlayWindowParams.floating(ctx.dp(420f), headerH, host.winType, touchModal = false)

            attachDragAndTopResizeListener(ctx)
        }

        private fun makeCtrlBtn(ctx: Context, tooltip: String, isDanger: Boolean = false, onClick: () -> Unit): ImageView =
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = tooltip
                tag = isDanger
                OverlayUiUtils.attachTactilePress(this, 0.86f)
                setOnClickListener { onClick() }
            }

        private fun scheduleSnapLayoutsHover(anchor: View) {
            cancelPendingHover()
            val task = Runnable {
                val win = boundWindow ?: return@Runnable
                if (!isVisible || isDraggingOrResizing) return@Runnable
                val loc = IntArray(2)
                anchor.getLocationOnScreen(loc)
                host.openSnapLayouts(win, loc[0] + anchor.width / 2, lp.y + headerH + 6)
            }
            snapHoverTask = task
            host.handler.postDelayed(task, SNAP_HOVER_DELAY_MS)
        }

        fun cancelPendingHover() {
            snapHoverTask?.let { host.handler.removeCallbacks(it) }
            snapHoverTask = null
        }

        fun bind(
            ctx: Context,
            win: DesktopWindow,
            upperOuters: List<IntRect>,
            dpi: Int,
            minVisPx: Int,
            tokens: GlassOverlayTokens,
        ) {
            boundWindow = win
            val fullW = max(ctx.dp(200f), win.bounds.width)
            val fullLeft = win.bounds.left
            val hdrTop = max(0, win.bounds.top - headerH)
            val hdrBottom = hdrTop + headerH

            val seg = if (win.focused || upperOuters.isEmpty()) {
                com.extbridge.helper.desktop.model.VisibleHeaderSegment(fullLeft, fullLeft + fullW, 0)
            } else {
                DesktopGeometry.computeVisibleHeaderSegment(fullLeft, fullLeft + fullW, hdrTop, hdrBottom, upperOuters, minVisPx)
            }
            if (seg == null) {
                hide()
                return
            }

            if (win.pkg != lastPkg) {
                lastPkg = win.pkg
                val pm = host.serviceContext.packageManager
                iconView.setImageDrawable(host.getCachedIcon(pm, win.pkg))
                titleView.text = host.getCachedLabel(pm, win.pkg)
            }
            val styleDirty = lastFocused != win.focused || lastDark != tokens.isDark || lastMaximized != win.isMaximized
            if (styleDirty) {
                lastFocused = win.focused
                lastDark = tokens.isDark
                lastMaximized = win.isMaximized
                applyVisualTheme(ctx, win.focused, win.isMaximized, tokens)
            }

            // Inner row keeps full window width & shifts horizontally so clipped occlusion never compresses buttons
            val rowLp = innerRow.layoutParams as FrameLayout.LayoutParams
            if (rowLp.width != fullW) {
                rowLp.width = fullW
                innerRow.layoutParams = rowLp
            }
            innerRow.translationX = seg.contentOffsetX.toFloat()

            isVisible = true
            if (root.visibility != View.VISIBLE) root.visibility = View.VISIBLE
            if (lp.x != seg.visibleLeft || lp.y != hdrTop || lp.width != seg.visibleWidth || lp.height != headerH) {
                lp.x = seg.visibleLeft
                lp.y = hdrTop
                lp.width = seg.visibleWidth
                lp.height = headerH
                host.surfaceHost.updateWindowLayout(root, lp, host.extWm)
            }
        }

        fun applyLivePosition(rect: IntRect) {
            val fullW = max(200, rect.width)
            val hdrTop = max(0, rect.top - headerH)
            val rowLp = innerRow.layoutParams as FrameLayout.LayoutParams
            if (rowLp.width != fullW) {
                rowLp.width = fullW
                innerRow.layoutParams = rowLp
            }
            innerRow.translationX = 0f
            if (root.visibility != View.VISIBLE) root.visibility = View.VISIBLE
            if (lp.x != rect.left || lp.y != hdrTop || lp.width != fullW || lp.height != headerH) {
                lp.x = rect.left; lp.y = hdrTop; lp.width = fullW; lp.height = headerH
                host.surfaceHost.updateWindowLayout(root, lp, host.extWm)
            }
        }

        private fun applyVisualTheme(ctx: Context, focused: Boolean, maximized: Boolean, tokens: GlassOverlayTokens) {
            root.background = tokens.makeWindowHeaderBackground(ctx, focused)
            titleView.setTextColor(if (focused) tokens.textPrimary else tokens.textSecondary)
            val iconColor = if (focused) tokens.textPrimary else tokens.textSecondary
            val hoverBg = tokens.hoverChipFill
            btnBack.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CHEVRON_LEFT, iconColor, 14f))
            btnBack.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, hoverBg, 10f)

            btnMore.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_MORE, iconColor, 14f))
            btnMore.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, hoverBg, 10f)

            btnMin.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_MINUS, iconColor, 13.5f))
            btnMin.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, hoverBg, 10f)

            btnMax.setImageDrawable(HyperUi.icon(ctx, if (maximized) HyperUi.ICON_RESTORE else HyperUi.ICON_MAXIMIZE, iconColor, 13f))
            btnMax.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, hoverBg, 10f)

            btnClose.setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CLOSE, iconColor, 13f))
            btnClose.background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, tokens.dangerHoverFill, 10f)
        }

        private fun attachDragAndTopResizeListener(ctx: Context) {
            val touchSlop = ctx.dp(5f)
            val topEdgePx = ctx.dp(TOP_RESIZE_EDGE_DP)
            val cornerPx = ctx.dp(TOP_CORNER_ZONE_DP)
            val unmaxThresholdPx = ctx.dp(DRAG_UNMAX_THRESHOLD_DP)

            root.setOnHoverListener { v, ev ->
                val edge = resolveTopResizeEdge(ev.x, ev.y, v.width, topEdgePx, cornerPx)
                v.pointerIcon = PointerIcon.getSystemIcon(
                    ctx,
                    if (edge != null) GlobalResizeEdgeController.pointerIconFor(edge) else PointerIcon.TYPE_GRAB,
                )
                false
            }
            root.setOnGenericMotionListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    boundWindow?.let { host.openWindowMenu(it, ev.rawX.roundToInt(), lp.y + headerH + 6) }
                    true
                } else {
                    false
                }
            }

            var downRawX = 0f
            var downRawY = 0f
            var moved = false
            var baseRect = IntRect.EMPTY
            var liveRect = IntRect.EMPTY
            var activeResizeEdge: ResizeEdge? = null
            var liveSnap = SnapZone.NONE
            var dragRestoredFromMax = false

            root.setOnTouchListener { v, ev ->
                val win = boundWindow ?: return@setOnTouchListener false
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    host.openWindowMenu(win, ev.rawX.roundToInt(), lp.y + headerH + 6)
                    return@setOnTouchListener true
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelPendingHover()
                        downRawX = ev.rawX; downRawY = ev.rawY
                        moved = false
                        baseRect = win.bounds
                        liveRect = baseRect
                        liveSnap = SnapZone.NONE
                        dragRestoredFromMax = false
                        activeResizeEdge = resolveTopResizeEdge(ev.x, ev.y, v.width, topEdgePx, cornerPx)
                        isDraggingOrResizing = true
                        activeDragSlotIdx = index
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downRawX).roundToInt()
                        val dy = (ev.rawY - downRawY).roundToInt()
                        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            moved = true
                            host.popovers.dismissAll()
                            if (!win.focused) host.focusWindow(win.taskId, win.pkg)
                        }
                        if (!moved) return@setOnTouchListener true

                        val st = host.state
                        val rEdge = activeResizeEdge
                        if (rEdge != null) {
                            liveRect = DesktopGeometry.computeResizedRect(
                                baseRect, rEdge, dx, dy, st.displayW, st.displayH, st.dpi, host.isTaskbarAutoHide,
                            )
                            val wDp = DesktopGeometry.pxToDp(liveRect.width, st.dpi)
                            val hDp = DesktopGeometry.pxToDp(liveRect.height, st.dpi)
                            host.updatePreview(
                                PreviewOverlayState(
                                    active = true,
                                    outerRect = DesktopGeometry.computeOuterFrame(liveRect, st.dpi),
                                    hudLabel = "$wDp × $hDp dp",
                                    isSnapped = false,
                                ),
                            )
                            return@setOnTouchListener true
                        }

                        // Drag-to-Unmaximize: pulling down a Maximized or Snapped header smoothly restores floating frame
                        if (!dragRestoredFromMax && (win.isMaximized || win.isSnapped) && dy >= unmaxThresholdPx) {
                            dragRestoredFromMax = true
                            val targetW = win.restoreBounds?.width
                                ?: DesktopGeometry.dpToPx(680, st.dpi).coerceIn(DesktopGeometry.dpToPx(360, st.dpi), (st.displayW * 0.6f).roundToInt())
                            val targetH = win.restoreBounds?.height
                                ?: (st.displayH * 0.72f).roundToInt().coerceAtLeast(DesktopGeometry.dpToPx(360, st.dpi))
                            val ratioX = ((downRawX - baseRect.left) / max(1, baseRect.width).toFloat()).coerceIn(0.15f, 0.85f)
                            val nl = (ev.rawX - targetW * ratioX).roundToInt().coerceIn(0, max(0, st.displayW - targetW))
                            val nt = max(DesktopGeometry.computeWorkArea(st.displayW, st.displayH, st.dpi, host.isTaskbarAutoHide).top + headerH, ev.rawY.roundToInt() + headerH / 2)
                            baseRect = IntRect(nl, nt, nl + targetW, nt + targetH)
                            downRawX = ev.rawX; downRawY = ev.rawY
                        }

                        val snap = DesktopGeometry.detectSnapZone(ev.rawX.roundToInt(), ev.rawY.roundToInt(), st.displayW, st.displayH, st.dpi)
                        liveSnap = snap
                        if (snap != SnapZone.NONE) {
                            val snapTaskRect = DesktopGeometry.computeSnapRect(snap, st.displayW, st.displayH, st.dpi, host.isTaskbarAutoHide)
                            liveRect = snapTaskRect
                            host.updatePreview(
                                PreviewOverlayState(
                                    active = true,
                                    outerRect = DesktopGeometry.computeOuterFrame(snapTaskRect, st.dpi),
                                    hudLabel = DesktopGeometry.snapZoneLabel(snap),
                                    isSnapped = true,
                                ),
                            )
                        } else {
                            // Draw a 120fps Liquid Glass ghost frame while keeping the real header
                            // attached to the live app window, then commit ONE single resizeTask on
                            // release so Android 16 / HyperOS Shell Transitions never queue a 2s
                            // backlog of 350ms animations or visually separate header from content.
                            val curDx = (ev.rawX - downRawX).roundToInt()
                            val curDy = (ev.rawY - downRawY).roundToInt()
                            liveRect = DesktopGeometry.computeMovedRect(
                                baseRect, curDx, curDy, st.displayW, st.displayH, st.dpi, host.isTaskbarAutoHide,
                            )
                            host.updatePreview(
                                PreviewOverlayState(
                                    active = true,
                                    outerRect = DesktopGeometry.computeOuterFrame(liveRect, st.dpi),
                                    hudLabel = "",
                                    isSnapped = false,
                                ),
                            )
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasResize = activeResizeEdge != null
                        isDraggingOrResizing = false
                        activeDragSlotIdx = -1
                        activeResizeEdge = null
                        host.clearPreview()
                        host.setDraggingWindow(-1, null)

                        if (moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                            if (wasResize) {
                                host.commitWindowBounds(win.taskId, win.pkg, liveRect, rememberPref = true)
                            } else if (liveSnap != SnapZone.NONE) {
                                host.snapWindow(win.taskId, win.pkg, liveSnap)
                            } else {
                                host.commitWindowBounds(win.taskId, win.pkg, liveRect, rememberPref = true)
                            }
                        } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTapMs <= DOUBLE_TAP_MS) {
                                lastTapMs = 0L
                                host.toggleMaximizeWindow(win)
                            } else {
                                lastTapMs = now
                                host.focusWindow(win.taskId, win.pkg)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun resolveTopResizeEdge(x: Float, y: Float, w: Int, topEdgePx: Int, cornerPx: Int): ResizeEdge? {
            if (y > topEdgePx) return null
            return when {
                x <= cornerPx -> ResizeEdge.NW
                x >= w - cornerPx -> ResizeEdge.NE
                else -> ResizeEdge.N
            }
        }

        fun hide() {
            cancelPendingHover()
            isVisible = false
            boundWindow = null
            if (root.visibility != View.GONE) root.visibility = View.GONE
        }
    }
}
