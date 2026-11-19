package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Top-level freeform window chrome slot registered with [OverlaySurfaceHost] ([OverlayLayer.BASE_CHROME])
 * and dirty-checked on [bindAndShow] to skip redundant drawable allocations and WM IPCs every 2.2s.
 */
@SuppressLint("ClickableViewAccessibility")
internal class FreeformWindowChromeSlot(
    ctx: Context,
    slotIndex: Int,
    private val host: OverlayHost,
    private val onDraggingChanged: (Boolean) -> Unit,
    private val onShowPreview: (Rect, String, SnapZone) -> Unit,
    private val onHidePreview: () -> Unit,
    private val onOpenMenu: (BridgeClient.ManagedWindowInfo, Int, Int) -> Unit,
    private val onCommitBounds: (Int, String, Int, Rect, Boolean) -> Unit,
    private val onWindowAction: (BridgeClient.ManagedWindowInfo, Int) -> Unit,
) {
    companion object {
        private const val EDGE_SNAP_DP = 24f
        private const val MIN_FREEFORM_W_DP = 320
        private const val MIN_FREEFORM_H_DP = 320
    }

    private val keyTitle = "wc_ff_${slotIndex}_title"
    private val keyCorner = "wc_ff_${slotIndex}_corner"
    private val keyLeft = "wc_ff_${slotIndex}_left"
    private val keyRight = "wc_ff_${slotIndex}_right"

    var boundWindow: BridgeClient.ManagedWindowInfo? = null
        private set
    var isVisible: Boolean = false
        private set

    private var lastBoundPkg = ""
    private var lastBoundFocused: Boolean? = null
    private var lastPositionedRect: Rect? = null
    private var lastDispW = -1
    private var lastDispH = -1

    private val titleBarH = ctx.dp(34f)
    private val gripSize = ctx.dp(26f)
    private val sideGripW = ctx.dp(16f)
    private val sideGripH = ctx.dp(64f)

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

    init {
        titleBarRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadDp(10f, 4f, 6f, 4f); elevation = ctx.dpF(14f); visibility = View.GONE
            background = makeTitleBarBg(ctx, false)
        }
        focusDot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(HyperUi.HYPER_BLUE) } }
            .also { titleBarRoot.addView(it, ctx.llp(ctx.dp(6f), ctx.dp(6f), rightDp = 6f)) }
        iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            .also { titleBarRoot.addView(it, ctx.llp(ctx.dp(17f), ctx.dp(17f), rightDp = 6f)) }
        labelView = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            paint.isFakeBoldText = true; OverlayUiUtils.enforceSingleLine(this)
        }.also { titleBarRoot.addView(it, ctx.llp(0, WRAP, 1f)) }

        titleBarRoot.addView(makeActionBtn(ctx, "⋯", 0xFFE2E8F0.toInt()) {
            boundWindow?.let { w -> onOpenMenu(w, titleBarLp.x + titleBarLp.width - ctx.dp(232f), titleBarLp.y + titleBarH + 6) }
        })
        titleBarRoot.addView(makeActionBtn(ctx, "−", 0xFFE2E8F0.toInt()) { boundWindow?.let { onWindowAction(it, BridgeClient.WIN_ACTION_MINIMIZE) } })
        titleBarRoot.addView(makeActionBtn(ctx, "□", 0xFFE2E8F0.toInt()) {
            boundWindow?.let { w -> onCommitBounds(w.taskId, w.pkg, BridgeClient.WIN_MODE_FULLSCREEN, OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH), false) }
        })
        titleBarRoot.addView(makeActionBtn(ctx, "×", 0xFFFCA5A5.toInt()) { boundWindow?.let { onWindowAction(it, BridgeClient.WIN_ACTION_CLOSE) } })
        attachTitleDragListener(ctx)

        titleBarLp = OverlayWindowParams.floating(ctx.dp(280f), titleBarH, host.winType, touchModal = true)
        cornerGrip = CornerGripView(ctx).apply { visibility = View.GONE }
        cornerLp = OverlayWindowParams.floating(gripSize, gripSize, host.winType, touchModal = true)
        attachCornerResizeListener()
        leftResizeGrip = buildSideGrip(ctx, isLeft = true)
        leftResizeLp = OverlayWindowParams.floating(sideGripW, sideGripH, host.winType, touchModal = true)
        rightResizeGrip = buildSideGrip(ctx, isLeft = false)
        rightResizeLp = OverlayWindowParams.floating(sideGripW, sideGripH, host.winType, touchModal = true)

        host.surfaceHost.registerAndAttachBatch(
            listOf(
                OverlaySurfaceHost.SurfaceEntry(keyTitle, OverlayLayer.BASE_CHROME, titleBarRoot, titleBarLp),
                OverlaySurfaceHost.SurfaceEntry(keyCorner, OverlayLayer.BASE_CHROME, cornerGrip, cornerLp),
                OverlaySurfaceHost.SurfaceEntry(keyLeft, OverlayLayer.BASE_CHROME, leftResizeGrip, leftResizeLp),
                OverlaySurfaceHost.SurfaceEntry(keyRight, OverlayLayer.BASE_CHROME, rightResizeGrip, rightResizeLp),
            ),
            host.inLauncherHost, host.launcherHost, host.extWm,
        )
    }

    private fun makeTitleBarBg(ctx: Context, focused: Boolean): GradientDrawable = roundedBg(
        if (focused) 0xEE181D27.toInt() else 0xD8151921.toInt(), ctx.dpF(14f), ctx.dp(1f),
        if (focused) 0x8838BDF8.toInt() else 0x33FFFFFF,
    )

    private fun makeActionBtn(ctx: Context, glyph: String, fgColor: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = glyph; setTextColor(fgColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true; gravity = Gravity.CENTER
            val sz = ctx.dp(24f); layoutParams = ctx.llp(sz, sz, leftDp = 2f)
            background = roundedBg(0x1EFFFFFF, ctx.dpF(8f))
            setOnClickListener { onClick() }
        }

    private fun attachTitleDragListener(ctx: Context) {
        val touchSlop = ctx.dp(5f); val snapEdgePx = ctx.dp(EDGE_SNAP_DP)
        var downRawX = 0f; var downRawY = 0f; var moved = false
        var baseRect = Rect(); var liveRect = Rect(); var liveSnap = SnapZone.NONE

        titleBarRoot.setOnGenericMotionListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && ev.actionButton == MotionEvent.BUTTON_SECONDARY) {
                boundWindow?.let { onOpenMenu(it, titleBarLp.x, titleBarLp.y + titleBarH + 6) }; true
            } else false
        }
        titleBarRoot.setOnTouchListener { _, ev ->
            val win = boundWindow ?: return@setOnTouchListener false
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && (ev.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                onOpenMenu(win, titleBarLp.x, titleBarLp.y + titleBarH + 6); return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDraggingChanged(true); downRawX = ev.rawX; downRawY = ev.rawY; moved = false
                    baseRect = WindowChromeGeometry.sanitizeWindowRect(host, win); liveRect = Rect(baseRect); liveSnap = SnapZone.NONE; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX; val dy = ev.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) { moved = true; host.popovers.dismissAll() }
                    if (moved) {
                        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
                        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
                        val rx = ev.rawX.roundToInt(); val ry = ev.rawY.roundToInt()
                        liveSnap = when {
                            ry <= snapEdgePx -> SnapZone.FULLSCREEN; rx <= snapEdgePx -> SnapZone.LEFT_HALF
                            rx >= dispW - snapEdgePx -> SnapZone.RIGHT_HALF; else -> SnapZone.NONE
                        }
                        val (rect, hud) = when (liveSnap) {
                            SnapZone.FULLSCREEN -> Rect(0, 0, dispW, dispH) to "松手最大化全屏"
                            SnapZone.LEFT_HALF -> Rect(0, 0, max(320, dispW / 2 - 4), dispH) to "松手吸附左半屏"
                            SnapZone.RIGHT_HALF -> Rect(min(dispW - 320, dispW / 2 + 4), 0, dispW, dispH) to "松手吸附右半屏"
                            SnapZone.NONE -> {
                                val w = baseRect.width(); val h = baseRect.height()
                                val nl = (baseRect.left + dx.roundToInt()).coerceIn(0, max(0, dispW - w))
                                val nt = (baseRect.top + dy.roundToInt()).coerceIn(titleBarH, max(titleBarH, dispH - h))
                                Rect(nl, nt, min(dispW, nl + w), min(dispH, nt + h)) to "移动窗口 ($nl, $nt)"
                            }
                        }
                        liveRect = rect; onShowPreview(liveRect, hud, liveSnap)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDraggingChanged(false); onHidePreview()
                    if (moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        onCommitBounds(win.taskId, win.pkg, if (liveSnap == SnapZone.FULLSCREEN) BridgeClient.WIN_MODE_FULLSCREEN else BridgeClient.WIN_MODE_FREEFORM, liveRect, false)
                    } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        onWindowAction(win, BridgeClient.WIN_ACTION_FOCUS)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachCornerResizeListener() {
        var downRawX = 0f; var downRawY = 0f; var baseRect = Rect(); var liveRect = Rect()
        cornerGrip.setOnTouchListener { _, ev ->
            val win = boundWindow ?: return@setOnTouchListener false
            val dpi = WindowChromeGeometry.effectiveDpi(host)
            val minW = BridgeClient.dpToPx(MIN_FREEFORM_W_DP, dpi); val minH = BridgeClient.dpToPx(MIN_FREEFORM_H_DP, dpi)
            val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW); val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDraggingChanged(true); downRawX = ev.rawX; downRawY = ev.rawY
                    baseRect = WindowChromeGeometry.sanitizeWindowRect(host, win); liveRect = Rect(baseRect)
                    cornerGrip.setActive(true); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nr = (baseRect.right + (ev.rawX - downRawX).roundToInt()).coerceIn(min(dispW, baseRect.left + minW), dispW)
                    val nb = (baseRect.bottom + (ev.rawY - downRawY).roundToInt()).coerceIn(min(dispH, baseRect.top + minH), dispH)
                    liveRect = Rect(baseRect.left, baseRect.top, nr, nb)
                    onShowPreview(liveRect, "${BridgeClient.pxToDp(liveRect.width(), dpi)}dp × ${BridgeClient.pxToDp(liveRect.height(), dpi)}dp", SnapZone.NONE)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDraggingChanged(false); cornerGrip.setActive(false); onHidePreview()
                    if (ev.actionMasked == MotionEvent.ACTION_UP) onCommitBounds(win.taskId, win.pkg, BridgeClient.WIN_MODE_FREEFORM, liveRect, false)
                    true
                }
                else -> false
            }
        }
    }

    private fun buildSideGrip(ctx: Context, isLeft: Boolean): FrameLayout {
        val root = FrameLayout(ctx).apply { visibility = View.GONE; alpha = 0.35f }
        val pill = View(ctx).apply { background = WindowChromeGeometry.makeHandlePillBg(ctx, false) }
        root.addView(pill, ctx.flp(ctx.dp(4f), ctx.dp(42f), Gravity.CENTER))

        var downRawX = 0f; var baseRect = Rect(); var liveRect = Rect()
        root.setOnTouchListener { _, ev ->
            val win = boundWindow ?: return@setOnTouchListener false
            val dpi = WindowChromeGeometry.effectiveDpi(host)
            val minW = BridgeClient.dpToPx(MIN_FREEFORM_W_DP, dpi); val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDraggingChanged(true); downRawX = ev.rawX
                    baseRect = WindowChromeGeometry.sanitizeWindowRect(host, win); liveRect = Rect(baseRect)
                    WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, true); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downRawX).roundToInt()
                    liveRect = if (isLeft) Rect((baseRect.left + dx).coerceIn(0, max(0, baseRect.right - minW)), baseRect.top, baseRect.right, baseRect.bottom)
                    else Rect(baseRect.left, baseRect.top, (baseRect.right + dx).coerceIn(min(dispW, baseRect.left + minW), dispW), baseRect.bottom)
                    onShowPreview(liveRect, "${BridgeClient.pxToDp(liveRect.width(), dpi)}dp × ${BridgeClient.pxToDp(liveRect.height(), dpi)}dp", SnapZone.NONE)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDraggingChanged(false); WindowChromeGeometry.animateHandleHighlight(root, pill, ctx, false); onHidePreview()
                    if (ev.actionMasked == MotionEvent.ACTION_UP) onCommitBounds(win.taskId, win.pkg, BridgeClient.WIN_MODE_FREEFORM, liveRect, false)
                    true
                }
                else -> false
            }
        }
        return root
    }

    fun bindAndShow(ctx: Context, win: BridgeClient.ManagedWindowInfo) {
        val wasVisible = isVisible
        boundWindow = win; isVisible = true
        if (win.pkg != lastBoundPkg) {
            lastBoundPkg = win.pkg
            val pm = host.serviceContext.packageManager
            iconView.setImageDrawable(host.getCachedIcon(pm, win.pkg))
            labelView.text = host.getCachedLabel(pm, win.pkg)
        }
        if (lastBoundFocused != win.focused) {
            lastBoundFocused = win.focused
            focusDot.visibility = if (win.focused) View.VISIBLE else View.GONE
            titleBarRoot.background = makeTitleBarBg(ctx, win.focused)
        }
        positionForWindow(ctx, win, force = !wasVisible)
    }

    fun positionForWindow(ctx: Context, win: BridgeClient.ManagedWindowInfo, force: Boolean = true) {
        val r = WindowChromeGeometry.sanitizeWindowRect(host, win)
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        if (!force && lastPositionedRect == r && lastDispW == dispW && lastDispH == dispH) return
        lastPositionedRect = Rect(r); lastDispW = dispW; lastDispH = dispH

        val barW = r.width().coerceIn(ctx.dp(220f), dispW)
        val barY = if (r.top >= titleBarH) r.top - titleBarH else r.top.coerceIn(0, max(0, dispH - titleBarH))
        updatePart(titleBarRoot, titleBarLp, r.left.coerceIn(0, max(0, dispW - barW)), barY, barW, titleBarH)
        updatePart(cornerGrip, cornerLp, (r.right - gripSize + ctx.dp(4f)).coerceIn(0, max(0, dispW - gripSize)), (r.bottom - gripSize + ctx.dp(4f)).coerceIn(0, max(0, dispH - gripSize)), gripSize, gripSize)
        val midY = ((r.top + r.bottom) / 2 - sideGripH / 2).coerceIn(0, max(0, dispH - sideGripH))
        updatePart(leftResizeGrip, leftResizeLp, (r.left - sideGripW / 2).coerceIn(0, max(0, dispW - sideGripW)), midY, sideGripW, sideGripH)
        updatePart(rightResizeGrip, rightResizeLp, (r.right - sideGripW / 2).coerceIn(0, max(0, dispW - sideGripW)), midY, sideGripW, sideGripH)
    }

    private fun updatePart(view: View, lp: WindowManager.LayoutParams, x: Int, y: Int, w: Int, h: Int) {
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        if (lp.x != x || lp.y != y || lp.width != w || lp.height != h) {
            lp.x = x; lp.y = y; lp.width = w; lp.height = h
            host.surfaceHost.updateWindowLayout(view, lp, host.extWm)
        }
    }

    fun hide() {
        isVisible = false; boundWindow = null; lastPositionedRect = null
        listOf(titleBarRoot, cornerGrip, leftResizeGrip, rightResizeGrip).forEach { if (it.visibility != View.GONE) it.visibility = View.GONE }
    }

    fun detach() {
        hide()
        listOf(keyTitle, keyCorner, keyLeft, keyRight).forEach { host.surfaceHost.unregister(it, host.extWm) }
    }
}
