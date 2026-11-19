package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages v4.1 adaptive desktop window chrome on the external VirtualDisplay:
 * 1. Single-app left/right minimalist edge capsule handles for 60fps drag-to-resize,
 *    double-tap toggle (centered adaptive width <-> fullscreen), and compact ratio menu.
 * 2. Up to 4 concurrent FREEFORM managed window frosted titlebars with drag-move,
 *    desktop edge snap previews (left/right half & fullscreen), corner/side resize handles,
 *    and quick lifecycle actions (focus / centered / maximize / minimize / reload DPI / close).
 */
class WindowChromeOverlayController(private val host: OverlayHost) {

    companion object {
        private const val MAX_FREEFORM_SLOTS = 4
        private const val DOUBLE_TAP_WINDOW_MS = 320L
        private const val SINGLE_TAP_MENU_DELAY_MS = 230L
        private const val EDGE_SNAP_DP = 24f
        private const val MIN_FREEFORM_W_DP = 320
        private const val MIN_FREEFORM_H_DP = 320
        private const val HANDLE_IDLE_ALPHA = 0.38f

        private const val SNAP_NONE = 0
        private const val SNAP_FULLSCREEN = 1
        private const val SNAP_LEFT_HALF = 2
        private const val SNAP_RIGHT_HALF = 3
    }

    private data class ActiveSingleTarget(
        val taskId: Int,
        val pkg: String,
        val mode: String,
        val bounds: Rect,
    )

    private var previewView: PreviewOverlayView? = null
    private var previewLp: WindowManager.LayoutParams? = null

    private var leftHandleRoot: FrameLayout? = null
    private var leftHandlePill: View? = null
    private var leftHandleLp: WindowManager.LayoutParams? = null

    private var rightHandleRoot: FrameLayout? = null
    private var rightHandlePill: View? = null
    private var rightHandleLp: WindowManager.LayoutParams? = null

    private var singleMenuRoot: LinearLayout? = null
    private var singleMenuLp: WindowManager.LayoutParams? = null
    private var singleMenuHeader: TextView? = null
    private var singleMenuSub: TextView? = null
    private var singleMenuOpen: Boolean = false

    private var freeformMenuRoot: LinearLayout? = null
    private var freeformMenuLp: WindowManager.LayoutParams? = null
    private var freeformMenuHeader: TextView? = null
    private var freeformMenuOpen: Boolean = false
    private var freeformMenuTarget: BridgeClient.ManagedWindowInfo? = null

    private val freeformSlots = arrayOfNulls<FreeformChromeSlot>(MAX_FREEFORM_SLOTS)

    private var activeSingle: ActiveSingleTarget? = null
    private var activeSingleDragging: Boolean = false
    private var activeFreeformDragging: Boolean = false
    private var lastHandleTapMs: Long = 0L
    private var pendingSingleTapMenu: Runnable? = null

    val isAnyMenuOpen: Boolean
        get() = singleMenuOpen || freeformMenuOpen

    val hasVisibleChrome: Boolean
        get() = activeSingle != null || freeformSlots.any { it?.isVisible == true }

    @SuppressLint("ClickableViewAccessibility")
    fun buildOverlays(ctx: Context, st: BridgeClient.Status) {
        detach()
        val wm = host.extWm ?: return

        // 1. Fullscreen hardware-accelerated 60fps drag preview layer (non-touchable)
        val preview = PreviewOverlayView(ctx).apply { visibility = View.GONE }
        val pLp = WindowManager.LayoutParams(
            max(600, host.overlayW),
            max(600, host.overlayH),
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        previewView = preview
        previewLp = pLp
        safeAddWmView(wm, preview, pLp)

        // 2. Left & Right single-app drag-to-resize capsule handles
        val (lRoot, lPill, lLp) = buildSingleEdgeHandle(ctx, isLeft = true)
        leftHandleRoot = lRoot
        leftHandlePill = lPill
        leftHandleLp = lLp
        safeAddWmView(wm, lRoot, lLp)

        val (rRoot, rPill, rLp) = buildSingleEdgeHandle(ctx, isLeft = false)
        rightHandleRoot = rRoot
        rightHandlePill = rPill
        rightHandleLp = rLp
        safeAddWmView(wm, rRoot, rLp)

        // 3. Single-app compact width ratio popover menu
        buildSingleMenu(ctx, wm)

        // 4. Freeform window context action popover menu
        buildFreeformMenu(ctx, wm)

        // Initial state sync with default snapshot
        updateFromState(st, host.lastWindowSnapshot, null)
    }

    fun updateFromState(
        st: BridgeClient.Status,
        snap: BridgeClient.WindowStateSnapshot,
        tasks: List<BridgeClient.TaskEntry>?,
    ) {
        val ctx = host.extCtx ?: return
        val wm = host.extWm ?: return

        val dexActive = st.connected && st.vdId > 0 && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX
        val tilingActive = st.isMultiTilingActive || BridgeClient.isTilingTopology(snap.tilingTopology)

        if (!dexActive || tilingActive) {
            hideSingleHandles()
            hideAllFreeformSlots()
            dismissMenus()
            return
        }

        // Collect active visible FREEFORM windows (max 4)
        val freeforms = snap.windows
            .filter {
                !it.minimized &&
                    it.mode.equals(BridgeClient.LAYOUT_FREEFORM, ignoreCase = true) &&
                    !isSystemOrLauncherPkg(it.pkg)
            }
            .take(MAX_FREEFORM_SLOTS)

        if (!activeFreeformDragging) {
            syncFreeformSlots(ctx, wm, freeforms)
        }

        // Resolve single-app target (FULLSCREEN or ADAPTIVE_SINGLE) when not purely in freeform mode
        if (!activeSingleDragging) {
            val singleWin = resolveSingleTarget(st, snap, tasks, freeforms.isNotEmpty())
            activeSingle = singleWin
            if (singleWin != null) {
                showAndPositionSingleHandles(ctx, wm, singleWin.bounds)
            } else {
                hideSingleHandles()
                if (singleMenuOpen) setSingleMenuOpen(false, anchorLeft = true)
            }
        }
    }

    fun onOverlayBoundsChanged() {
        val ctx = host.extCtx ?: return
        val wm = host.extWm ?: return
        previewView?.let { pv ->
            previewLp?.let { lp ->
                lp.width = max(600, host.overlayW)
                lp.height = max(600, host.overlayH)
                safeUpdateWmLayout(wm, pv, lp)
            }
        }
        activeSingle?.let { showAndPositionSingleHandles(ctx, wm, it.bounds) }
        for (slot in freeformSlots) {
            val win = slot?.boundWindow ?: continue
            if (slot.isVisible) {
                slot.positionForWindow(ctx, wm, win)
            }
        }
    }

    fun dismissMenus() {
        cancelPendingSingleTapMenu()
        if (singleMenuOpen) setSingleMenuOpen(false, anchorLeft = true)
        if (freeformMenuOpen) setFreeformMenuOpen(false, null, 0, 0)
    }

    fun detach() {
        cancelPendingSingleTapMenu()
        activeSingleDragging = false
        activeFreeformDragging = false
        activeSingle = null
        singleMenuOpen = false
        freeformMenuOpen = false
        freeformMenuTarget = null

        host.removeOverlayView(previewView)
        host.removeOverlayView(leftHandleRoot)
        host.removeOverlayView(rightHandleRoot)
        host.removeOverlayView(singleMenuRoot)
        host.removeOverlayView(freeformMenuRoot)

        previewView = null
        previewLp = null
        leftHandleRoot = null
        leftHandlePill = null
        leftHandleLp = null
        rightHandleRoot = null
        rightHandlePill = null
        rightHandleLp = null
        singleMenuRoot = null
        singleMenuLp = null
        singleMenuHeader = null
        singleMenuSub = null
        freeformMenuRoot = null
        freeformMenuLp = null
        freeformMenuHeader = null

        for (i in freeformSlots.indices) {
            freeformSlots[i]?.detach()
            freeformSlots[i] = null
        }
    }

    // --- Single-App Left/Right Edge Handles ---

    private fun resolveSingleTarget(
        st: BridgeClient.Status,
        snap: BridgeClient.WindowStateSnapshot,
        tasks: List<BridgeClient.TaskEntry>?,
        hasFreeforms: Boolean,
    ): ActiveSingleTarget? {
        // Hide single-app drag handles whenever the ExtBridge desktop launcher itself is in the foreground.
        val topExtTask = tasks?.firstOrNull { it.isTopExt() }
        if (topExtTask != null && isSystemOrLauncherPkg(topExtTask.pkg)) {
            return null
        }

        val singleCandidates = snap.windows.filter {
            !it.minimized &&
                !isSystemOrLauncherPkg(it.pkg) &&
                (it.mode.equals(BridgeClient.LAYOUT_ADAPTIVE_SINGLE, ignoreCase = true) ||
                    it.mode.equals(BridgeClient.LAYOUT_FULLSCREEN, ignoreCase = true))
        }
        val preferred = singleCandidates.firstOrNull { it.focused } ?: singleCandidates.firstOrNull()
        if (preferred != null) {
            val bounds = sanitizeWindowRect(preferred)
            return ActiveSingleTarget(preferred.taskId, preferred.pkg, preferred.mode, bounds)
        }

        // Fallback: if daemon hasn't pushed a ManagedWindowInfo entry yet for a fullscreen foreground app,
        // synthesize a fullscreen target from top running non-launcher task so user can drag inward anytime.
        if (hasFreeforms) return null
        val modeUpper = (snap.layoutMode.ifBlank { st.activeLayoutMode }).uppercase()
        if (modeUpper != BridgeClient.LAYOUT_FULLSCREEN && modeUpper != BridgeClient.LAYOUT_ADAPTIVE_SINGLE) {
            return null
        }
        val topTask = tasks?.firstOrNull { it.isTopExt() && !isSystemOrLauncherPkg(it.pkg) }
            ?: return null
        val fullW = max(600, host.overlayW)
        val fullH = max(600, host.overlayH)
        return ActiveSingleTarget(
            taskId = topTask.taskId,
            pkg = topTask.pkg,
            mode = modeUpper,
            bounds = Rect(0, 0, fullW, fullH),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSingleEdgeHandle(
        ctx: Context,
        isLeft: Boolean,
    ): Triple<FrameLayout, View, WindowManager.LayoutParams> {
        val touchW = OverlayUiUtils.dp(ctx, 24f)
        val touchH = OverlayUiUtils.dp(ctx, 108f)
        val pillW = OverlayUiUtils.dp(ctx, 5f)
        val pillH = OverlayUiUtils.dp(ctx, 64f)

        val root = FrameLayout(ctx).apply {
            visibility = View.GONE
            alpha = HANDLE_IDLE_ALPHA
        }
        val pill = View(ctx).apply {
            background = makeHandlePillBg(ctx, highlighted = false)
            elevation = OverlayUiUtils.dp(ctx, 6f).toFloat()
        }
        root.addView(
            pill,
            FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER),
        )

        root.setOnHoverListener { _, ev ->
            when (ev?.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> setHandleHighlighted(root, pill, ctx, true)
                MotionEvent.ACTION_HOVER_EXIT -> if (!activeSingleDragging) {
                    setHandleHighlighted(root, pill, ctx, false)
                }
            }
            false
        }

        val touchSlop = OverlayUiUtils.dp(ctx, 6f)
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var liveRect = Rect()
        var liveFull = false

        root.setOnTouchListener { _, ev ->
            val target = activeSingle ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeSingleDragging = true
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    moved = false
                    liveRect = Rect(target.bounds)
                    liveFull = isRectFullscreen(liveRect)
                    setHandleHighlighted(root, pill, ctx, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        cancelPendingSingleTapMenu()
                        if (singleMenuOpen) setSingleMenuOpen(false, isLeft)
                    }
                    if (moved) {
                        val (previewRect, widthDp, isFull, pct) = computeDraggedSingleGeometry(
                            rawX = ev.rawX,
                            isLeft = isLeft,
                            baseBounds = target.bounds,
                        )
                        liveRect = previewRect
                        liveFull = isFull
                        val hudLabel = if (isFull) {
                            "全屏 · 100%"
                        } else {
                            "${BridgeClient.classifyWidthPresetLabel(widthDp, false)} · $pct%"
                        }
                        showPreview(previewRect, hudLabel, snapMode = if (isFull) SNAP_FULLSCREEN else SNAP_NONE)
                        host.extWm?.let { wm -> showAndPositionSingleHandles(ctx, wm, previewRect) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeSingleDragging = false
                    setHandleHighlighted(root, pill, ctx, false)
                    hidePreview()
                    if (moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val finalMode = if (liveFull) {
                            BridgeClient.WIN_MODE_FULLSCREEN
                        } else {
                            BridgeClient.WIN_MODE_ADAPTIVE_SINGLE
                        }
                        activeSingle = target.copy(
                            mode = if (liveFull) BridgeClient.LAYOUT_FULLSCREEN else BridgeClient.LAYOUT_ADAPTIVE_SINGLE,
                            bounds = Rect(liveRect),
                        )
                        commitSingleWindowBounds(target.taskId, target.pkg, finalMode, liveRect, rememberPref = true)
                    } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        handleSingleEdgeTap(target, isLeft)
                    }
                    true
                }
                else -> false
            }
        }

        val lp = WindowManager.LayoutParams(
            touchW,
            touchH,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }
        return Triple(root, pill, lp)
    }

    private data class DragGeometry(
        val rect: Rect,
        val widthDp: Int,
        val isFullscreen: Boolean,
        val widthPct: Int,
    )

    private fun computeDraggedSingleGeometry(
        rawX: Float,
        isLeft: Boolean,
        baseBounds: Rect,
    ): DragGeometry {
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val dpi = effectiveDpi()
        val fullWidthDp = BridgeClient.pxToDp(dispW, dpi)
        val centerX = dispW * 0.5f
        val halfPx = if (isLeft) (centerX - rawX) else (rawX - centerX)
        val rawWidthPx = (halfPx * 2f).roundToInt().coerceIn(BridgeClient.dpToPx(BridgeClient.MIN_SINGLE_WIDTH_DP, dpi), dispW)
        val rawWidthDp = BridgeClient.pxToDp(rawWidthPx, dpi)
        val (snappedDp, isFull) = BridgeClient.snapWidthDp(rawWidthDp, fullWidthDp)

        val top = baseBounds.top.coerceIn(0, dispH / 4)
        val bottom = if (baseBounds.bottom > top + 200) min(dispH, baseBounds.bottom) else dispH
        val rect = if (isFull) {
            Rect(0, 0, dispW, dispH)
        } else {
            val wPx = BridgeClient.dpToPx(snappedDp, dpi).coerceIn(min(dispW, BridgeClient.dpToPx(BridgeClient.MIN_SINGLE_WIDTH_DP, dpi)), dispW)
            val l = max(0, (dispW - wPx) / 2)
            Rect(l, top, min(dispW, l + wPx), bottom)
        }
        val pct = ((rect.width() * 100f) / dispW.toFloat()).roundToInt().coerceIn(20, 100)
        return DragGeometry(rect, snappedDp, isFull, pct)
    }

    private fun handleSingleEdgeTap(target: ActiveSingleTarget, isLeft: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandleTapMs <= DOUBLE_TAP_WINDOW_MS) {
            lastHandleTapMs = 0L
            cancelPendingSingleTapMenu()
            setSingleMenuOpen(false, isLeft)
            toggleSingleCenteredVsFullscreen(target)
        } else {
            lastHandleTapMs = now
            cancelPendingSingleTapMenu()
            val task = Runnable {
                pendingSingleTapMenu = null
                setSingleMenuOpen(!singleMenuOpen, isLeft)
            }
            pendingSingleTapMenu = task
            host.handler.postDelayed(task, SINGLE_TAP_MENU_DELAY_MS)
        }
    }

    private fun toggleSingleCenteredVsFullscreen(target: ActiveSingleTarget) {
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val currentlyFull = target.mode.equals(BridgeClient.LAYOUT_FULLSCREEN, ignoreCase = true) ||
            isRectFullscreen(target.bounds)
        if (currentlyFull) {
            val targetDp = host.lastStatus.defaultSingleWidthDp
                .coerceIn(BridgeClient.MIN_SINGLE_WIDTH_DP, BridgeClient.MAX_SINGLE_WIDTH_DP)
            val b = BridgeClient.computeCenteredSingleBoundsPx(dispW, dispH, effectiveDpi(), targetDp)
            val rect = Rect(b[0], b[1], b[2], b[3])
            commitSingleWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, rect, rememberPref = true)
            Toast.makeText(host.serviceContext, "已切换居中宽幅 · ${targetDp}dp", Toast.LENGTH_SHORT).show()
        } else {
            val full = Rect(0, 0, dispW, dispH)
            commitSingleWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_FULLSCREEN, full, rememberPref = true)
            Toast.makeText(host.serviceContext, "已切换全屏显示", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAndPositionSingleHandles(ctx: Context, wm: WindowManager, bounds: Rect) {
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val lRoot = leftHandleRoot ?: return
        val lLp = leftHandleLp ?: return
        val rRoot = rightHandleRoot ?: return
        val rLp = rightHandleLp ?: return

        val midY = ((bounds.top + bounds.bottom) / 2 - lLp.height / 2)
            .coerceIn(OverlayUiUtils.dp(ctx, 24f), max(0, dispH - lLp.height - OverlayUiUtils.dp(ctx, 64f)))

        val leftX = if (bounds.left <= lLp.width / 2) {
            0
        } else {
            (bounds.left - lLp.width / 2).coerceIn(0, dispW - lLp.width)
        }
        val rightX = if (bounds.right >= dispW - rLp.width / 2) {
            dispW - rLp.width
        } else {
            (bounds.right - rLp.width / 2).coerceIn(0, dispW - rLp.width)
        }

        lLp.x = leftX
        lLp.y = midY
        rLp.x = rightX
        rLp.y = midY

        if (lRoot.visibility != View.VISIBLE) lRoot.visibility = View.VISIBLE
        if (rRoot.visibility != View.VISIBLE) rRoot.visibility = View.VISIBLE
        safeUpdateWmLayout(wm, lRoot, lLp)
        safeUpdateWmLayout(wm, rRoot, rLp)
    }

    private fun hideSingleHandles() {
        leftHandleRoot?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        rightHandleRoot?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    private fun setHandleHighlighted(root: View, pill: View, ctx: Context, highlighted: Boolean) {
        root.animate().cancel()
        root.animate()
            .alpha(if (highlighted) 1f else HANDLE_IDLE_ALPHA)
            .setDuration(110L)
            .start()
        pill.animate().cancel()
        pill.animate()
            .scaleX(if (highlighted) 1.35f else 1f)
            .scaleY(if (highlighted) 1.08f else 1f)
            .setDuration(110L)
            .start()
        pill.background = makeHandlePillBg(ctx, highlighted)
    }

    private fun makeHandlePillBg(ctx: Context, highlighted: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (highlighted) HyperUi.HYPER_BLUE else 0xFFE2E8F0.toInt())
            cornerRadius = OverlayUiUtils.dp(ctx, 4f).toFloat()
            setStroke(
                OverlayUiUtils.dp(ctx, 1f),
                if (highlighted) 0xCCFFFFFF.toInt() else 0x660F172A,
            )
        }

    // --- Single-App Compact Width Ratio Menu ---

    @SuppressLint("ClickableViewAccessibility")
    private fun buildSingleMenu(ctx: Context, wm: WindowManager) {
        val menuW = OverlayUiUtils.dp(ctx, 244f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
            )
            elevation = OverlayUiUtils.dp(ctx, 18f).toFloat()
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(HyperUi.FLYOUT_BG)
                cornerRadius = OverlayUiUtils.dp(ctx, 18f).toFloat()
                setStroke(1, HyperUi.FLYOUT_STROKE)
            }
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && singleMenuOpen) {
                    setSingleMenuOpen(false, anchorLeft = true)
                }
                false
            }
        }

        val header = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        singleMenuHeader = header
        root.addView(header)

        val sub = TextView(ctx).apply {
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            isSingleLine = true
        }
        singleMenuSub = sub
        root.addView(
            sub,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 2f)
                bottomMargin = OverlayUiUtils.dp(ctx, 8f)
            },
        )

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeMenuChip(ctx, "460dp 手机") { applySinglePresetDp(460) }, chipLp(ctx, true))
        row1.addView(makeMenuChip(ctx, "680dp 折叠") { applySinglePresetDp(680) }, chipLp(ctx, false))
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeMenuChip(ctx, "900dp 平板") { applySinglePresetDp(900) }, chipLp(ctx, true))
        row2.addView(makeMenuChip(ctx, "全屏") { applySingleFullscreen() }, chipLp(ctx, false))
        root.addView(
            row2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val row3 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(
            makeMenuChip(ctx, "重载 DPI", iconKind = HyperUi.ICON_ROTATE) { reloadActiveSingleDpi() },
            chipLp(ctx, true),
        )
        row3.addView(
            makeMenuChip(ctx, "转为自由窗口", iconKind = HyperUi.ICON_WINDOW) { convertActiveSingleToFreeform() },
            chipLp(ctx, false),
        )
        root.addView(
            row3,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val lp = WindowManager.LayoutParams(
            menuW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        singleMenuRoot = root
        singleMenuLp = lp
        safeAddWmView(wm, root, lp)
    }

    private fun setSingleMenuOpen(open: Boolean, anchorLeft: Boolean) {
        val menu = singleMenuRoot ?: return
        val lp = singleMenuLp ?: return
        val ctx = host.extCtx ?: return
        val wm = host.extWm ?: return
        val target = activeSingle
        val wantOpen = open && target != null
        singleMenuOpen = wantOpen

        menu.animate().cancel()
        if (!wantOpen) {
            menu.visibility = View.GONE
            return
        }
        if (freeformMenuOpen) setFreeformMenuOpen(false, null, 0, 0)
        host.setPanelOpen(false)
        host.setSplitMenuOpen(false)
        host.setTaskBubbleOpen(false)

        val label = host.getCachedLabel(host.serviceContext.packageManager, target.pkg)
        val wDp = BridgeClient.pxToDp(target.bounds.width(), effectiveDpi())
        val isFull = isRectFullscreen(target.bounds)
        singleMenuHeader?.text = "$label · 窗口宽幅"
        singleMenuSub?.text = "当前：${BridgeClient.classifyWidthPresetLabel(wDp, isFull)}（双击手柄一键切全屏）"

        val menuW = lp.width
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(max(600, host.overlayH), View.MeasureSpec.AT_MOST),
        )
        val menuH = max(OverlayUiUtils.dp(ctx, 136f), menu.measuredHeight)
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val margin = OverlayUiUtils.dp(ctx, 12f)
        val handleLp = if (anchorLeft) leftHandleLp else rightHandleLp
        val anchorX = if (anchorLeft) {
            (handleLp?.x ?: 0) + (handleLp?.width ?: 0) + OverlayUiUtils.dp(ctx, 8f)
        } else {
            (handleLp?.x ?: (dispW - menuW)) - menuW - OverlayUiUtils.dp(ctx, 8f)
        }
        lp.x = anchorX.coerceIn(margin, max(margin, dispW - menuW - margin))
        lp.y = ((handleLp?.y ?: (dispH / 2)) - menuH / 2 + OverlayUiUtils.dp(ctx, 36f))
            .coerceIn(margin, max(margin, dispH - menuH - OverlayUiUtils.dp(ctx, 72f)))

        menu.alpha = 0f
        menu.scaleX = 0.94f
        menu.scaleY = 0.94f
        menu.visibility = View.VISIBLE
        safeUpdateWmLayout(wm, menu, lp)
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { host.bringCursorToFront() }
            .start()
    }

    private fun applySinglePresetDp(widthDp: Int) {
        val target = activeSingle ?: return
        setSingleMenuOpen(false, anchorLeft = true)
        val b = BridgeClient.computeCenteredSingleBoundsPx(
            max(600, host.overlayW),
            max(600, host.overlayH),
            effectiveDpi(),
            widthDp,
        )
        val rect = Rect(b[0], b[1], b[2], b[3])
        commitSingleWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, rect, rememberPref = true)
    }

    private fun applySingleFullscreen() {
        val target = activeSingle ?: return
        setSingleMenuOpen(false, anchorLeft = true)
        val full = Rect(0, 0, max(600, host.overlayW), max(600, host.overlayH))
        commitSingleWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_FULLSCREEN, full, rememberPref = true)
    }

    private fun reloadActiveSingleDpi() {
        val target = activeSingle ?: return
        setSingleMenuOpen(false, anchorLeft = true)
        Toast.makeText(host.serviceContext, "正在重载应用 DPI 并重启…", Toast.LENGTH_SHORT).show()
        host.safeBgExecute {
            BridgeClient.windowLifecycleAction(target.taskId, target.pkg, BridgeClient.WIN_ACTION_RELOAD_DPI)
            host.safePost { host.handler.postDelayed({ host.pollRemoteStateAsync() }, 250L) }
        }
    }

    private fun convertActiveSingleToFreeform() {
        val target = activeSingle ?: return
        setSingleMenuOpen(false, anchorLeft = true)
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val dpi = effectiveDpi()
        val w = BridgeClient.dpToPx(560, dpi).coerceIn(min(dispW - 80, 480), (dispW * 0.72f).roundToInt())
        val h = (dispH * 0.76f).roundToInt().coerceAtLeast(min(dispH - 80, 480))
        val l = max(24, (dispW - w) / 2)
        val t = max(48, (dispH - h) / 2)
        val rect = Rect(l, t, min(dispW - 16, l + w), min(dispH - 24, t + h))
        commitSingleWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_FREEFORM, rect, rememberPref = false)
    }

    private fun commitSingleWindowBounds(
        taskId: Int,
        pkg: String,
        mode: Int,
        rect: Rect,
        rememberPref: Boolean,
    ) {
        host.safeBgExecute {
            BridgeClient.updateWindowBounds(taskId, pkg, mode, rect, rememberPref)
            host.safePost {
                host.handler.postDelayed({ host.pollRemoteStateAsync() }, 120L)
            }
        }
    }

    // --- Freeform Multi-Window Chrome (up to 4 windows) ---

    private fun syncFreeformSlots(
        ctx: Context,
        wm: WindowManager,
        windows: List<BridgeClient.ManagedWindowInfo>,
    ) {
        for (i in 0 until MAX_FREEFORM_SLOTS) {
            if (i < windows.size) {
                val slot = freeformSlots[i] ?: FreeformChromeSlot(ctx, wm).also { freeformSlots[i] = it }
                slot.bindAndShow(ctx, wm, windows[i])
            } else {
                freeformSlots[i]?.hide()
            }
        }
    }

    private fun hideAllFreeformSlots() {
        for (slot in freeformSlots) {
            slot?.hide()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFreeformMenu(ctx: Context, wm: WindowManager) {
        val menuW = OverlayUiUtils.dp(ctx, 232f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
            )
            elevation = OverlayUiUtils.dp(ctx, 18f).toFloat()
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(HyperUi.FLYOUT_BG)
                cornerRadius = OverlayUiUtils.dp(ctx, 18f).toFloat()
                setStroke(1, HyperUi.FLYOUT_STROKE)
            }
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && freeformMenuOpen) {
                    setFreeformMenuOpen(false, null, 0, 0)
                }
                false
            }
        }

        val header = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        freeformMenuHeader = header
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = OverlayUiUtils.dp(ctx, 8f)
            },
        )

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makeMenuChip(ctx, "置顶聚焦", iconKind = HyperUi.ICON_PIN) {
            freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_FOCUS) }
        }, chipLp(ctx, true))
        row1.addView(makeMenuChip(ctx, "居中宽幅", iconKind = HyperUi.ICON_RATIO) {
            freeformMenuTarget?.let { w ->
                val targetDp = host.lastStatus.defaultSingleWidthDp
                val b = BridgeClient.computeCenteredSingleBoundsPx(max(600, host.overlayW), max(600, host.overlayH), effectiveDpi(), targetDp)
                commitSingleWindowBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_ADAPTIVE_SINGLE, Rect(b[0], b[1], b[2], b[3]), true)
            }
            setFreeformMenuOpen(false, null, 0, 0)
        }, chipLp(ctx, false))
        root.addView(row1)

        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(makeMenuChip(ctx, "最大化", iconKind = HyperUi.ICON_FULLSCREEN) {
            freeformMenuTarget?.let { w ->
                val full = Rect(0, 0, max(600, host.overlayW), max(600, host.overlayH))
                commitSingleWindowBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_FULLSCREEN, full, false)
            }
            setFreeformMenuOpen(false, null, 0, 0)
        }, chipLp(ctx, true))
        row2.addView(makeMenuChip(ctx, "最小化", iconKind = HyperUi.ICON_MINUS) {
            freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_MINIMIZE) }
        }, chipLp(ctx, false))
        root.addView(
            row2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val row3 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(makeMenuChip(ctx, "重载 DPI", iconKind = HyperUi.ICON_ROTATE) {
            freeformMenuTarget?.let {
                Toast.makeText(host.serviceContext, "正在重载应用 DPI 并重启…", Toast.LENGTH_SHORT).show()
                performWindowAction(it, BridgeClient.WIN_ACTION_RELOAD_DPI)
            }
        }, chipLp(ctx, true))
        row3.addView(makeMenuChip(ctx, "关闭", iconKind = HyperUi.ICON_CLOSE, danger = true) {
            freeformMenuTarget?.let { performWindowAction(it, BridgeClient.WIN_ACTION_CLOSE) }
        }, chipLp(ctx, false))
        root.addView(
            row3,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 6f)
            },
        )

        val lp = WindowManager.LayoutParams(
            menuW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        freeformMenuRoot = root
        freeformMenuLp = lp
        safeAddWmView(wm, root, lp)
    }

    private fun setFreeformMenuOpen(
        open: Boolean,
        target: BridgeClient.ManagedWindowInfo?,
        anchorX: Int,
        anchorY: Int,
    ) {
        val menu = freeformMenuRoot ?: return
        val lp = freeformMenuLp ?: return
        val ctx = host.extCtx ?: return
        val wm = host.extWm ?: return

        val wantOpen = open && target != null
        freeformMenuOpen = wantOpen
        freeformMenuTarget = if (wantOpen) target else null

        menu.animate().cancel()
        if (!wantOpen) {
            menu.visibility = View.GONE
            return
        }
        if (singleMenuOpen) setSingleMenuOpen(false, anchorLeft = true)
        host.setPanelOpen(false)
        host.setSplitMenuOpen(false)
        host.setTaskBubbleOpen(false)

        val label = host.getCachedLabel(host.serviceContext.packageManager, target.pkg)
        freeformMenuHeader?.text = "$label · 窗口控制"

        val menuW = lp.width
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(max(600, host.overlayH), View.MeasureSpec.AT_MOST),
        )
        val menuH = max(OverlayUiUtils.dp(ctx, 130f), menu.measuredHeight)
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        val margin = OverlayUiUtils.dp(ctx, 12f)
        lp.x = anchorX.coerceIn(margin, max(margin, dispW - menuW - margin))
        lp.y = anchorY.coerceIn(margin, max(margin, dispH - menuH - OverlayUiUtils.dp(ctx, 64f)))

        menu.alpha = 0f
        menu.scaleX = 0.94f
        menu.scaleY = 0.94f
        menu.visibility = View.VISIBLE
        safeUpdateWmLayout(wm, menu, lp)
        menu.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(145L)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { host.bringCursorToFront() }
            .start()
    }

    private fun performWindowAction(win: BridgeClient.ManagedWindowInfo, action: Int) {
        setFreeformMenuOpen(false, null, 0, 0)
        host.safeBgExecute {
            BridgeClient.windowLifecycleAction(win.taskId, win.pkg, action)
            host.safePost {
                host.handler.postDelayed({ host.pollRemoteStateAsync() }, 150L)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class FreeformChromeSlot(
        ctx: Context,
        wm: WindowManager,
    ) {
        var boundWindow: BridgeClient.ManagedWindowInfo? = null
        var isVisible: Boolean = false

        private val titleBarRoot: LinearLayout
        private val titleBarLp: WindowManager.LayoutParams
        private val iconView: ImageView
        private val labelView: TextView
        private val focusDot: View

        private val cornerGrip: CornerGripView
        private val cornerLp: WindowManager.LayoutParams

        private val leftResizeGrip: FrameLayout
        private val leftResizeLp: WindowManager.LayoutParams
        private val rightResizeGrip: FrameLayout
        private val rightResizeLp: WindowManager.LayoutParams

        private val titleBarH = OverlayUiUtils.dp(ctx, 34f)
        private val gripSize = OverlayUiUtils.dp(ctx, 26f)
        private val sideGripW = OverlayUiUtils.dp(ctx, 16f)
        private val sideGripH = OverlayUiUtils.dp(ctx, 64f)

        init {
            titleBarRoot = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    OverlayUiUtils.dp(ctx, 10f),
                    OverlayUiUtils.dp(ctx, 4f),
                    OverlayUiUtils.dp(ctx, 6f),
                    OverlayUiUtils.dp(ctx, 4f),
                )
                elevation = OverlayUiUtils.dp(ctx, 14f).toFloat()
                visibility = View.GONE
                background = makeTitleBarBg(ctx, focused = false)
            }

            focusDot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(HyperUi.HYPER_BLUE)
                }
            }
            titleBarRoot.addView(
                focusDot,
                LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 6f), OverlayUiUtils.dp(ctx, 6f)).apply {
                    rightMargin = OverlayUiUtils.dp(ctx, 6f)
                },
            )

            iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            titleBarRoot.addView(
                iconView,
                LinearLayout.LayoutParams(OverlayUiUtils.dp(ctx, 17f), OverlayUiUtils.dp(ctx, 17f)).apply {
                    rightMargin = OverlayUiUtils.dp(ctx, 6f)
                },
            )

            labelView = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                paint.isFakeBoldText = true
                OverlayUiUtils.enforceSingleLine(this)
            }
            titleBarRoot.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // Quick capsule buttons: Menu / Minimize / Maximize / Close
            val btnMore = makeTitleActionBtn(ctx, "⋯", 0xFFE2E8F0.toInt()) {
                boundWindow?.let { w ->
                    setFreeformMenuOpen(true, w, titleBarLp.x + titleBarLp.width - OverlayUiUtils.dp(ctx, 232f), titleBarLp.y + titleBarH + 6)
                }
            }
            val btnMin = makeTitleActionBtn(ctx, "−", 0xFFE2E8F0.toInt()) {
                boundWindow?.let { performWindowAction(it, BridgeClient.WIN_ACTION_MINIMIZE) }
            }
            val btnMax = makeTitleActionBtn(ctx, "□", 0xFFE2E8F0.toInt()) {
                boundWindow?.let { w ->
                    val full = Rect(0, 0, max(600, host.overlayW), max(600, host.overlayH))
                    commitSingleWindowBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_FULLSCREEN, full, false)
                }
            }
            val btnClose = makeTitleActionBtn(ctx, "×", 0xFFFCA5A5.toInt()) {
                boundWindow?.let { performWindowAction(it, BridgeClient.WIN_ACTION_CLOSE) }
            }
            titleBarRoot.addView(btnMore)
            titleBarRoot.addView(btnMin)
            titleBarRoot.addView(btnMax)
            titleBarRoot.addView(btnClose)

            attachTitleDragListener(ctx)

            titleBarLp = WindowManager.LayoutParams(
                OverlayUiUtils.dp(ctx, 280f),
                titleBarH,
                host.winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
            safeAddWmView(wm, titleBarRoot, titleBarLp)

            // Bottom-right corner resize grip
            cornerGrip = CornerGripView(ctx).apply { visibility = View.GONE }
            cornerLp = WindowManager.LayoutParams(
                gripSize,
                gripSize,
                host.winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
            attachCornerResizeListener(ctx)
            safeAddWmView(wm, cornerGrip, cornerLp)

            // Left & Right edge resize grips
            leftResizeGrip = buildFreeformSideGrip(ctx, isLeft = true)
            leftResizeLp = WindowManager.LayoutParams(
                sideGripW,
                sideGripH,
                host.winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
            safeAddWmView(wm, leftResizeGrip, leftResizeLp)

            rightResizeGrip = buildFreeformSideGrip(ctx, isLeft = false)
            rightResizeLp = WindowManager.LayoutParams(
                sideGripW,
                sideGripH,
                host.winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.LEFT }
            safeAddWmView(wm, rightResizeGrip, rightResizeLp)
        }

        private fun makeTitleBarBg(ctx: Context, focused: Boolean): GradientDrawable =
            GradientDrawable().apply {
                setColor(if (focused) 0xEE181D27.toInt() else 0xD8151921.toInt())
                cornerRadius = OverlayUiUtils.dp(ctx, 14f).toFloat()
                setStroke(
                    OverlayUiUtils.dp(ctx, 1f),
                    if (focused) 0x8838BDF8.toInt() else 0x33FFFFFF,
                )
            }

        private fun makeTitleActionBtn(
            ctx: Context,
            glyph: String,
            fgColor: Int,
            onClick: () -> Unit,
        ): TextView = TextView(ctx).apply {
            text = glyph
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            val size = OverlayUiUtils.dp(ctx, 24f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = OverlayUiUtils.dp(ctx, 2f)
            }
            background = GradientDrawable().apply {
                setColor(0x1EFFFFFF)
                cornerRadius = OverlayUiUtils.dp(ctx, 8f).toFloat()
            }
            setOnClickListener { onClick() }
        }

        private fun attachTitleDragListener(ctx: Context) {
            val touchSlop = OverlayUiUtils.dp(ctx, 5f)
            val snapEdgePx = OverlayUiUtils.dp(ctx, EDGE_SNAP_DP)
            var downRawX = 0f
            var downRawY = 0f
            var moved = false
            var baseRect = Rect()
            var liveRect = Rect()
            var liveSnap = SNAP_NONE

            titleBarRoot.setOnGenericMotionListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                    ev.actionButton == MotionEvent.BUTTON_SECONDARY
                ) {
                    boundWindow?.let { w ->
                        setFreeformMenuOpen(true, w, titleBarLp.x, titleBarLp.y + titleBarH + 6)
                    }
                    true
                } else {
                    false
                }
            }

            titleBarRoot.setOnTouchListener { _, ev ->
                val win = boundWindow ?: return@setOnTouchListener false
                if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                    (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
                ) {
                    setFreeformMenuOpen(true, win, titleBarLp.x, titleBarLp.y + titleBarH + 6)
                    return@setOnTouchListener true
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeFreeformDragging = true
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        moved = false
                        baseRect = sanitizeWindowRect(win)
                        liveRect = Rect(baseRect)
                        liveSnap = SNAP_NONE
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            moved = true
                            if (freeformMenuOpen) setFreeformMenuOpen(false, null, 0, 0)
                        }
                        if (moved) {
                            val dispW = max(600, host.overlayW)
                            val dispH = max(600, host.overlayH)
                            val rx = ev.rawX.roundToInt()
                            val ry = ev.rawY.roundToInt()
                            liveSnap = when {
                                ry <= snapEdgePx -> SNAP_FULLSCREEN
                                rx <= snapEdgePx -> SNAP_LEFT_HALF
                                rx >= dispW - snapEdgePx -> SNAP_RIGHT_HALF
                                else -> SNAP_NONE
                            }
                            val hud: String
                            liveRect = when (liveSnap) {
                                SNAP_FULLSCREEN -> {
                                    hud = "松手最大化全屏"
                                    Rect(0, 0, dispW, dispH)
                                }
                                SNAP_LEFT_HALF -> {
                                    hud = "松手吸附左半屏"
                                    Rect(0, 0, max(320, dispW / 2 - 4), dispH)
                                }
                                SNAP_RIGHT_HALF -> {
                                    hud = "松手吸附右半屏"
                                    Rect(min(dispW - 320, dispW / 2 + 4), 0, dispW, dispH)
                                }
                                else -> {
                                    val w = baseRect.width()
                                    val h = baseRect.height()
                                    val nl = (baseRect.left + dx.roundToInt()).coerceIn(0, max(0, dispW - w))
                                    val nt = (baseRect.top + dy.roundToInt()).coerceIn(titleBarH, max(titleBarH, dispH - h))
                                    hud = "移动窗口 (${nl}, ${nt})"
                                    Rect(nl, nt, min(dispW, nl + w), min(dispH, nt + h))
                                }
                            }
                            showPreview(liveRect, hud, liveSnap)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeFreeformDragging = false
                        hidePreview()
                        if (moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                            val targetMode = if (liveSnap == SNAP_FULLSCREEN) {
                                BridgeClient.WIN_MODE_FULLSCREEN
                            } else {
                                BridgeClient.WIN_MODE_FREEFORM
                            }
                            commitSingleWindowBounds(win.taskId, win.pkg, targetMode, liveRect, rememberPref = false)
                        } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                            performWindowAction(win, BridgeClient.WIN_ACTION_FOCUS)
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun attachCornerResizeListener(ctx: Context) {
            var downRawX = 0f
            var downRawY = 0f
            var baseRect = Rect()
            var liveRect = Rect()

            cornerGrip.setOnTouchListener { _, ev ->
                val win = boundWindow ?: return@setOnTouchListener false
                val dpi = effectiveDpi()
                val minW = BridgeClient.dpToPx(MIN_FREEFORM_W_DP, dpi)
                val minH = BridgeClient.dpToPx(MIN_FREEFORM_H_DP, dpi)
                val dispW = max(600, host.overlayW)
                val dispH = max(600, host.overlayH)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeFreeformDragging = true
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        baseRect = sanitizeWindowRect(win)
                        liveRect = Rect(baseRect)
                        cornerGrip.setActive(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val nr = (baseRect.right + (ev.rawX - downRawX).roundToInt())
                            .coerceIn(min(dispW, baseRect.left + minW), dispW)
                        val nb = (baseRect.bottom + (ev.rawY - downRawY).roundToInt())
                            .coerceIn(min(dispH, baseRect.top + minH), dispH)
                        liveRect = Rect(baseRect.left, baseRect.top, nr, nb)
                        val wDp = BridgeClient.pxToDp(liveRect.width(), dpi)
                        val hDp = BridgeClient.pxToDp(liveRect.height(), dpi)
                        showPreview(liveRect, "${wDp}dp × ${hDp}dp", SNAP_NONE)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeFreeformDragging = false
                        cornerGrip.setActive(false)
                        hidePreview()
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            commitSingleWindowBounds(win.taskId, win.pkg, BridgeClient.WIN_MODE_FREEFORM, liveRect, rememberPref = false)
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        private fun buildFreeformSideGrip(ctx: Context, isLeft: Boolean): FrameLayout {
            val root = FrameLayout(ctx).apply {
                visibility = View.GONE
                alpha = 0.35f
            }
            val pill = View(ctx).apply {
                background = makeHandlePillBg(ctx, highlighted = false)
            }
            root.addView(
                pill,
                FrameLayout.LayoutParams(OverlayUiUtils.dp(ctx, 4f), OverlayUiUtils.dp(ctx, 42f), Gravity.CENTER),
            )

            var downRawX = 0f
            var baseRect = Rect()
            var liveRect = Rect()

            root.setOnTouchListener { _, ev ->
                val win = boundWindow ?: return@setOnTouchListener false
                val dpi = effectiveDpi()
                val minW = BridgeClient.dpToPx(MIN_FREEFORM_W_DP, dpi)
                val dispW = max(600, host.overlayW)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeFreeformDragging = true
                        downRawX = ev.rawX
                        baseRect = sanitizeWindowRect(win)
                        liveRect = Rect(baseRect)
                        setHandleHighlighted(root, pill, ctx, true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downRawX).roundToInt()
                        liveRect = if (isLeft) {
                            val nl = (baseRect.left + dx).coerceIn(0, max(0, baseRect.right - minW))
                            Rect(nl, baseRect.top, baseRect.right, baseRect.bottom)
                        } else {
                            val nr = (baseRect.right + dx).coerceIn(min(dispW, baseRect.left + minW), dispW)
                            Rect(baseRect.left, baseRect.top, nr, baseRect.bottom)
                        }
                        val wDp = BridgeClient.pxToDp(liveRect.width(), dpi)
                        val hDp = BridgeClient.pxToDp(liveRect.height(), dpi)
                        showPreview(liveRect, "${wDp}dp × ${hDp}dp", SNAP_NONE)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        activeFreeformDragging = false
                        setHandleHighlighted(root, pill, ctx, false)
                        hidePreview()
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            commitSingleWindowBounds(win.taskId, win.pkg, BridgeClient.WIN_MODE_FREEFORM, liveRect, rememberPref = false)
                        }
                        true
                    }
                    else -> false
                }
            }
            return root
        }

        fun bindAndShow(ctx: Context, wm: WindowManager, win: BridgeClient.ManagedWindowInfo) {
            boundWindow = win
            isVisible = true
            val pm = host.serviceContext.packageManager
            iconView.setImageDrawable(host.getCachedIcon(pm, win.pkg))
            labelView.text = host.getCachedLabel(pm, win.pkg)
            focusDot.visibility = if (win.focused) View.VISIBLE else View.GONE
            titleBarRoot.background = makeTitleBarBg(ctx, win.focused)
            positionForWindow(ctx, wm, win)
        }

        fun positionForWindow(ctx: Context, wm: WindowManager, win: BridgeClient.ManagedWindowInfo) {
            val r = sanitizeWindowRect(win)
            val dispW = max(600, host.overlayW)
            val dispH = max(600, host.overlayH)

            val barW = r.width().coerceIn(OverlayUiUtils.dp(ctx, 220f), dispW)
            val barX = r.left.coerceIn(0, max(0, dispW - barW))
            val barY = if (r.top >= titleBarH) r.top - titleBarH else r.top.coerceIn(0, max(0, dispH - titleBarH))
            titleBarLp.width = barW
            titleBarLp.height = titleBarH
            titleBarLp.x = barX
            titleBarLp.y = barY
            if (titleBarRoot.visibility != View.VISIBLE) titleBarRoot.visibility = View.VISIBLE
            safeUpdateWmLayout(wm, titleBarRoot, titleBarLp)

            cornerLp.x = (r.right - gripSize + OverlayUiUtils.dp(ctx, 4f)).coerceIn(0, max(0, dispW - gripSize))
            cornerLp.y = (r.bottom - gripSize + OverlayUiUtils.dp(ctx, 4f)).coerceIn(0, max(0, dispH - gripSize))
            if (cornerGrip.visibility != View.VISIBLE) cornerGrip.visibility = View.VISIBLE
            safeUpdateWmLayout(wm, cornerGrip, cornerLp)

            val midY = ((r.top + r.bottom) / 2 - sideGripH / 2).coerceIn(0, max(0, dispH - sideGripH))
            leftResizeLp.x = (r.left - sideGripW / 2).coerceIn(0, max(0, dispW - sideGripW))
            leftResizeLp.y = midY
            if (leftResizeGrip.visibility != View.VISIBLE) leftResizeGrip.visibility = View.VISIBLE
            safeUpdateWmLayout(wm, leftResizeGrip, leftResizeLp)

            rightResizeLp.x = (r.right - sideGripW / 2).coerceIn(0, max(0, dispW - sideGripW))
            rightResizeLp.y = midY
            if (rightResizeGrip.visibility != View.VISIBLE) rightResizeGrip.visibility = View.VISIBLE
            safeUpdateWmLayout(wm, rightResizeGrip, rightResizeLp)
        }

        fun hide() {
            isVisible = false
            boundWindow = null
            if (titleBarRoot.visibility != View.GONE) titleBarRoot.visibility = View.GONE
            if (cornerGrip.visibility != View.GONE) cornerGrip.visibility = View.GONE
            if (leftResizeGrip.visibility != View.GONE) leftResizeGrip.visibility = View.GONE
            if (rightResizeGrip.visibility != View.GONE) rightResizeGrip.visibility = View.GONE
        }

        fun detach() {
            hide()
            host.removeOverlayView(titleBarRoot)
            host.removeOverlayView(cornerGrip)
            host.removeOverlayView(leftResizeGrip)
            host.removeOverlayView(rightResizeGrip)
        }
    }

    // --- Shared Preview Overlay & Helper Views ---

    private fun showPreview(rect: Rect, label: String, snapMode: Int) {
        val pv = previewView ?: return
        pv.updatePreview(rect, label, snapMode)
        if (pv.visibility != View.VISIBLE) {
            pv.visibility = View.VISIBLE
        }
    }

    private fun hidePreview() {
        previewView?.let {
            if (it.visibility != View.GONE) it.visibility = View.GONE
        }
    }

    private fun makeMenuChip(
        ctx: Context,
        text: String,
        iconKind: Int = 0,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): TextView = OverlayUiUtils.makeFooterChip(ctx, text).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        gravity = Gravity.CENTER
        isSingleLine = true
        val padH = OverlayUiUtils.dp(ctx, 8f)
        val padV = OverlayUiUtils.dp(ctx, 7f)
        setPadding(padH, padV, padH, padV)
        if (danger) {
            setTextColor(0xFFFCA5A5.toInt())
        }
        if (iconKind > 0) {
            HyperUi.setStartIcon(
                this,
                iconKind,
                if (danger) 0xFFFCA5A5.toInt() else 0xFFE2E8F0.toInt(),
                12f,
                4f,
            )
        }
        setOnClickListener { onClick() }
    }

    private fun chipLp(ctx: Context, hasRightMargin: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (hasRightMargin) rightMargin = OverlayUiUtils.dp(ctx, 6f)
        }

    private fun sanitizeWindowRect(win: BridgeClient.ManagedWindowInfo): Rect {
        val dispW = max(600, host.overlayW)
        val dispH = max(600, host.overlayH)
        if (win.right <= win.left || win.bottom <= win.top) {
            return Rect(0, 0, dispW, dispH)
        }
        val l = win.left.coerceIn(0, dispW - 60)
        val t = win.top.coerceIn(0, dispH - 60)
        val r = win.right.coerceIn(l + 60, dispW)
        val b = win.bottom.coerceIn(t + 60, dispH)
        return Rect(l, t, r, b)
    }

    private fun isRectFullscreen(r: Rect): Boolean {
        val dispW = max(600, host.overlayW)
        return r.width() >= (dispW * 0.94f).roundToInt()
    }

    private fun effectiveDpi(): Int {
        val d = host.lastStatus.virtDpi
        return if (d > 0) d else max(160, host.overlayDpi)
    }

    private fun isSystemOrLauncherPkg(pkg: String): Boolean =
        pkg.isBlank() || pkg == "com.extbridge.helper" || pkg == "android"

    private fun cancelPendingSingleTapMenu() {
        pendingSingleTapMenu?.let { host.handler.removeCallbacks(it) }
        pendingSingleTapMenu = null
    }

    private fun safeAddWmView(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(view, lp)
        } catch (_: Throwable) {}
    }

    private fun safeUpdateWmLayout(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        try {
            if (view.parent != null) {
                wm.updateViewLayout(view, lp)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Hardware-accelerated 60fps translucent frosted preview frame & dp-width HUD overlay.
     */
    private class PreviewOverlayView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val previewRect = RectF()
        private val hudRect = RectF()
        private var hudText = ""
        private var snapMode = SNAP_NONE

        private val scrimPaint = Paint().apply {
            color = 0x26000000
            style = Paint.Style.FILL
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2B0D84FF
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF38BDF8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * density
        }
        private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEC0F172A.toInt()
            style = Paint.Style.FILL
        }
        private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x9938BDF8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13.5f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        fun updatePreview(rect: Rect, label: String, snap: Int) {
            previewRect.set(rect)
            hudText = label
            snapMode = snap
            fillPaint.color = if (snap != SNAP_NONE) 0x3A30D158 else 0x2B0D84FF
            strokePaint.color = if (snap != SNAP_NONE) HyperUi.HYPER_GREEN else 0xFF38BDF8.toInt()
            hudStrokePaint.color = if (snap != SNAP_NONE) 0xCC30D158.toInt() else 0x9938BDF8.toInt()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (previewRect.isEmpty) return
            val corner = 16f * density
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            canvas.drawRoundRect(previewRect, corner, corner, fillPaint)
            canvas.drawRoundRect(previewRect, corner, corner, strokePaint)

            if (hudText.isNotEmpty()) {
                val padH = 16f * density
                val pillH = 34f * density
                val textW = textPaint.measureText(hudText)
                val pillW = textW + padH * 2f
                val cx = previewRect.centerX().coerceIn(pillW * 0.5f + 12f * density, width - pillW * 0.5f - 12f * density)
                val topY = (previewRect.top + 22f * density).coerceIn(18f * density, max(18f * density, height - pillH - 24f * density))
                hudRect.set(cx - pillW * 0.5f, topY, cx + pillW * 0.5f, topY + pillH)
                canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudBgPaint)
                canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudStrokePaint)
                val fm = textPaint.fontMetrics
                val baseY = hudRect.centerY() - (fm.ascent + fm.descent) * 0.5f
                canvas.drawText(hudText, hudRect.centerX(), baseY, textPaint)
            }
        }
    }

    /**
     * Minimalist bottom-right curved corner resize grip for FREEFORM windows.
     */
    private class CornerGripView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBBCBD5E1.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun setActive(active: Boolean) {
            paint.color = if (active) HyperUi.HYPER_BLUE else 0xBBCBD5E1.toInt()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = 5f * density
            val r = 6f * density
            path.reset()
            path.moveTo(w - pad, h * 0.34f)
            path.lineTo(w - pad, h - pad - r)
            path.quadTo(w - pad, h - pad, w - pad - r, h - pad)
            path.lineTo(w * 0.34f, h - pad)
            canvas.drawPath(path, paint)
        }
    }
}
