package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages single-app left/right edge capsule handles (60fps drag-to-resize, double-tap
 * centered <-> fullscreen toggle, and compact width-ratio popover menu).
 */
internal class SingleWindowHandleController(
    private val host: OverlayHost,
    private val onShowPreview: (Rect, String, SnapZone) -> Unit,
    private val onHidePreview: () -> Unit,
    private val onCommitBounds: (Int, String, Int, Rect, Boolean) -> Unit,
    private val onReloadDpi: (Int, String) -> Unit,
) {
    companion object {
        private const val SURFACE_KEY_LEFT = "wc_single_left"
        private const val SURFACE_KEY_RIGHT = "wc_single_right"
        private const val MENU_POPOVER_ID = "wc_single_menu"
        private const val DOUBLE_TAP_WINDOW_MS = 320L
        private const val SINGLE_TAP_MENU_DELAY_MS = 230L
    }

    private data class ActiveSingleTarget(val taskId: Int, val pkg: String, val mode: String, val bounds: Rect)
    private data class DragGeometry(val rect: Rect, val widthDp: Int, val isFullscreen: Boolean, val widthPct: Int)

    private var leftHandleRoot: FrameLayout? = null
    private var leftHandleLp: WindowManager.LayoutParams? = null
    private var rightHandleRoot: FrameLayout? = null
    private var rightHandleLp: WindowManager.LayoutParams? = null
    private val singleMenu = WindowActionMenu(host, MENU_POPOVER_ID, widthDp = 244f, hasSubtitle = true)

    private var activeTarget: ActiveSingleTarget? = null
    private var dragging = false
    private var lastTapMs = 0L
    private var pendingSingleTapTask: Runnable? = null

    fun build(ctx: Context) {
        val (lRoot, lLp) = buildEdgeHandle(ctx, isLeft = true)
        leftHandleRoot = lRoot; leftHandleLp = lLp
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY_LEFT, OverlayLayer.BASE_CHROME, lRoot, lLp))

        val (rRoot, rLp) = buildEdgeHandle(ctx, isLeft = false)
        rightHandleRoot = rRoot; rightHandleLp = rLp
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY_RIGHT, OverlayLayer.BASE_CHROME, rRoot, rLp))

        singleMenu.build(ctx, listOf(
            listOf(MenuChipSpec("460dp 手机") { applyPresetDp(460) }, MenuChipSpec("680dp 折叠") { applyPresetDp(680) }),
            listOf(MenuChipSpec("900dp 平板") { applyPresetDp(900) }, MenuChipSpec("全屏") { applyFullscreen() }),
            listOf(
                MenuChipSpec("重载 DPI", HyperUi.ICON_ROTATE) { reloadActiveDpi() },
                MenuChipSpec("转为自由窗口", HyperUi.ICON_WINDOW) { convertActiveToFreeform() },
            ),
        ))
    }

    fun updateFromState(ctx: Context, st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot, tasks: List<BridgeClient.TaskEntry>?, hasFreeforms: Boolean) {
        if (dragging) return
        val target = resolveTarget(st, snap, tasks, hasFreeforms)
        activeTarget = target
        if (target != null) showAndPositionHandles(ctx, target.bounds)
        else { hideHandles(); singleMenu.close() }
    }

    fun onOverlayBoundsChanged(ctx: Context) { activeTarget?.let { showAndPositionHandles(ctx, it.bounds) } }
    fun hideAll() { hideHandles(); dismissMenu() }
    fun dismissMenu() { cancelPendingTap(); singleMenu.close() }

    fun detach() {
        cancelPendingTap(); dragging = false; activeTarget = null
        singleMenu.detach()
        host.surfaceHost.unregister(SURFACE_KEY_LEFT, host.extWm)
        host.surfaceHost.unregister(SURFACE_KEY_RIGHT, host.extWm)
        leftHandleRoot = null; leftHandleLp = null; rightHandleRoot = null; rightHandleLp = null
    }

    private fun resolveTarget(st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot, tasks: List<BridgeClient.TaskEntry>?, hasFreeforms: Boolean): ActiveSingleTarget? {
        val topExtTask = tasks?.firstOrNull { it.isTopExt() }
        if (topExtTask != null && OverlayConstants.isSystemOrLauncherPkg(topExtTask.pkg)) return null

        val candidates = snap.windows.filter {
            !it.minimized && !OverlayConstants.isSystemOrLauncherPkg(it.pkg) &&
                (it.mode.equals(BridgeClient.LAYOUT_ADAPTIVE_SINGLE, ignoreCase = true) || it.mode.equals(BridgeClient.LAYOUT_FULLSCREEN, ignoreCase = true))
        }
        val preferred = candidates.firstOrNull { it.focused } ?: candidates.firstOrNull()
        if (preferred != null) {
            return ActiveSingleTarget(preferred.taskId, preferred.pkg, preferred.mode, WindowChromeGeometry.sanitizeWindowRect(host, preferred))
        }
        if (hasFreeforms) return null
        val modeUpper = snap.layoutMode.ifBlank { st.activeLayoutMode }.uppercase()
        if (modeUpper != BridgeClient.LAYOUT_FULLSCREEN && modeUpper != BridgeClient.LAYOUT_ADAPTIVE_SINGLE) return null
        val topTask = tasks?.firstOrNull { it.isTopExt() && !OverlayConstants.isSystemOrLauncherPkg(it.pkg) } ?: return null
        return ActiveSingleTarget(topTask.taskId, topTask.pkg, modeUpper, OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildEdgeHandle(ctx: Context, isLeft: Boolean): Pair<FrameLayout, WindowManager.LayoutParams> {
        val root = FrameLayout(ctx).apply { visibility = View.GONE; alpha = WindowChromeGeometry.HANDLE_IDLE_ALPHA }
        val pill = View(ctx).apply { background = WindowChromeGeometry.makeHandlePillBg(ctx, false); elevation = ctx.dpF(6f) }
        root.addView(pill, ctx.flp(ctx.dp(5f), ctx.dp(64f), Gravity.CENTER))

        root.setOnHoverListener { _, ev ->
            when (ev?.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, true)
                MotionEvent.ACTION_HOVER_EXIT -> if (!dragging) WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, false)
            }
            false
        }

        val touchSlop = ctx.dp(6f)
        var downRawX = 0f; var downRawY = 0f; var moved = false; var liveRect = Rect(); var liveFull = false

        root.setOnTouchListener { _, ev ->
            val target = activeTarget ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true; downRawX = ev.rawX; downRawY = ev.rawY; moved = false
                    liveRect = Rect(target.bounds); liveFull = WindowChromeGeometry.isRectFullscreen(host, liveRect)
                    WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, true); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                        moved = true; cancelPendingTap(); singleMenu.close()
                    }
                    if (moved) {
                        val g = computeDraggedGeometry(ev.rawX, isLeft, target.bounds)
                        liveRect = g.rect; liveFull = g.isFullscreen
                        val hud = if (g.isFullscreen) "全屏 · 100%" else "${BridgeClient.classifyWidthPresetLabel(g.widthDp, false)} · ${g.widthPct}%"
                        onShowPreview(g.rect, hud, if (g.isFullscreen) SnapZone.FULLSCREEN else SnapZone.NONE)
                        showAndPositionHandles(ctx, g.rect)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false; WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, false); onHidePreview()
                    if (moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val mode = if (liveFull) BridgeClient.WIN_MODE_FULLSCREEN else BridgeClient.WIN_MODE_ADAPTIVE_SINGLE
                        activeTarget = target.copy(mode = if (liveFull) BridgeClient.LAYOUT_FULLSCREEN else BridgeClient.LAYOUT_ADAPTIVE_SINGLE, bounds = Rect(liveRect))
                        onCommitBounds(target.taskId, target.pkg, mode, liveRect, true)
                    } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        handleEdgeTap(target, isLeft)
                    }
                    true
                }
                else -> false
            }
        }
        return root to OverlayWindowParams.floating(ctx.dp(24f), ctx.dp(108f), host.winType, touchModal = true)
    }

    private fun computeDraggedGeometry(rawX: Float, isLeft: Boolean, baseBounds: Rect): DragGeometry {
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        val dpi = WindowChromeGeometry.effectiveDpi(host)
        val minWidthPx = BridgeClient.dpToPx(BridgeClient.MIN_SINGLE_WIDTH_DP, dpi)
        val rawWidthPx = ((if (isLeft) dispW * 0.5f - rawX else rawX - dispW * 0.5f) * 2f).roundToInt().coerceIn(minWidthPx, dispW)
        val (snappedDp, isFull) = BridgeClient.snapWidthDp(BridgeClient.pxToDp(rawWidthPx, dpi), BridgeClient.pxToDp(dispW, dpi))

        val top = baseBounds.top.coerceIn(0, dispH / 4)
        val bottom = if (baseBounds.bottom > top + 200) min(dispH, baseBounds.bottom) else dispH
        val rect = if (isFull) Rect(0, 0, dispW, dispH) else {
            val wPx = BridgeClient.dpToPx(snappedDp, dpi).coerceIn(min(dispW, minWidthPx), dispW)
            val l = max(0, (dispW - wPx) / 2)
            Rect(l, top, min(dispW, l + wPx), bottom)
        }
        return DragGeometry(rect, snappedDp, isFull, ((rect.width() * 100f) / dispW.toFloat()).roundToInt().coerceIn(20, 100))
    }

    private fun handleEdgeTap(target: ActiveSingleTarget, isLeft: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs <= DOUBLE_TAP_WINDOW_MS) {
            lastTapMs = 0L; cancelPendingTap(); singleMenu.close()
            toggleCenteredVsFullscreen(target)
        } else {
            lastTapMs = now; cancelPendingTap()
            val task = Runnable {
                pendingSingleTapTask = null
                if (singleMenu.isOpen) singleMenu.close() else openSingleMenu(isLeft)
            }
            pendingSingleTapTask = task
            host.handler.postDelayed(task, SINGLE_TAP_MENU_DELAY_MS)
        }
    }

    private fun openSingleMenu(anchorLeft: Boolean) {
        val target = activeTarget ?: return; val ctx = host.extCtx ?: return
        val label = host.getCachedLabel(host.serviceContext.packageManager, target.pkg)
        val wDp = BridgeClient.pxToDp(target.bounds.width(), WindowChromeGeometry.effectiveDpi(host))
        val isFull = WindowChromeGeometry.isRectFullscreen(host, target.bounds)
        val handleLp = if (anchorLeft) leftHandleLp else rightHandleLp
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)

        singleMenu.show("$label · 窗口宽幅", "当前：${BridgeClient.classifyWidthPresetLabel(wDp, isFull)}（双击手柄一键切全屏）") { menuW, menuH ->
            val ax = if (anchorLeft) (handleLp?.x ?: 0) + (handleLp?.width ?: 0) + ctx.dp(8f)
            else (handleLp?.x ?: (dispW - menuW)) - menuW - ctx.dp(8f)
            ax to ((handleLp?.y ?: (dispH / 2)) - menuH / 2 + ctx.dp(36f))
        }
    }

    private fun toggleCenteredVsFullscreen(target: ActiveSingleTarget) {
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        if (target.mode.equals(BridgeClient.LAYOUT_FULLSCREEN, ignoreCase = true) || WindowChromeGeometry.isRectFullscreen(host, target.bounds)) {
            val targetDp = host.lastStatus.defaultSingleWidthDp.coerceIn(BridgeClient.MIN_SINGLE_WIDTH_DP, BridgeClient.MAX_SINGLE_WIDTH_DP)
            val b = BridgeClient.computeCenteredSingleBoundsPx(dispW, dispH, WindowChromeGeometry.effectiveDpi(host), targetDp)
            onCommitBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, Rect(b[0], b[1], b[2], b[3]), true)
            host.serviceContext.toast("已切换居中宽幅 · ${targetDp}dp")
        } else {
            onCommitBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_FULLSCREEN, Rect(0, 0, dispW, dispH), true)
            host.serviceContext.toast("已切换全屏显示")
        }
    }

    private fun showAndPositionHandles(ctx: Context, bounds: Rect) {
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        val lRoot = leftHandleRoot ?: return; val lLp = leftHandleLp ?: return
        val rRoot = rightHandleRoot ?: return; val rLp = rightHandleLp ?: return

        val midY = ((bounds.top + bounds.bottom) / 2 - lLp.height / 2).coerceIn(ctx.dp(24f), max(0, dispH - lLp.height - ctx.dp(64f)))
        val lx = if (bounds.left <= lLp.width / 2) 0 else (bounds.left - lLp.width / 2).coerceIn(0, dispW - lLp.width)
        val rx = if (bounds.right >= dispW - rLp.width / 2) dispW - rLp.width else (bounds.right - rLp.width / 2).coerceIn(0, dispW - rLp.width)
        if (lRoot.visibility != View.VISIBLE) lRoot.visibility = View.VISIBLE
        if (rRoot.visibility != View.VISIBLE) rRoot.visibility = View.VISIBLE
        if (lLp.x != lx || lLp.y != midY) { lLp.x = lx; lLp.y = midY; host.surfaceHost.updateWindowLayout(lRoot, lLp, host.extWm) }
        if (rLp.x != rx || rLp.y != midY) { rLp.x = rx; rLp.y = midY; host.surfaceHost.updateWindowLayout(rRoot, rLp, host.extWm) }
    }

    private fun hideHandles() {
        leftHandleRoot?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        rightHandleRoot?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    private fun applyPresetDp(widthDp: Int) {
        val t = activeTarget ?: return; singleMenu.close()
        val b = BridgeClient.computeCenteredSingleBoundsPx(
            max(OverlayConstants.MIN_SURFACE_PX, host.overlayW), max(OverlayConstants.MIN_SURFACE_PX, host.overlayH),
            WindowChromeGeometry.effectiveDpi(host), widthDp,
        )
        onCommitBounds(t.taskId, t.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, Rect(b[0], b[1], b[2], b[3]), true)
    }

    private fun applyFullscreen() {
        val t = activeTarget ?: return; singleMenu.close()
        onCommitBounds(t.taskId, t.pkg, BridgeClient.WIN_MODE_FULLSCREEN, OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH), true)
    }

    private fun reloadActiveDpi() {
        val t = activeTarget ?: return; singleMenu.close()
        host.serviceContext.toast("正在重载应用 DPI 并重启…")
        onReloadDpi(t.taskId, t.pkg)
    }

    private fun convertActiveToFreeform() {
        val t = activeTarget ?: return; singleMenu.close()
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        val w = BridgeClient.dpToPx(560, WindowChromeGeometry.effectiveDpi(host)).coerceIn(min(dispW - 80, 480), (dispW * 0.72f).roundToInt())
        val h = (dispH * 0.76f).roundToInt().coerceAtLeast(min(dispH - 80, 480))
        val l = max(24, (dispW - w) / 2); val top = max(48, (dispH - h) / 2)
        onCommitBounds(t.taskId, t.pkg, BridgeClient.WIN_MODE_FREEFORM, Rect(l, top, min(dispW - 16, l + w), min(dispH - 24, top + h)), false)
    }

    private fun cancelPendingTap() { pendingSingleTapTask?.let { host.handler.removeCallbacks(it) }; pendingSingleTapTask = null }
}
