package io.github.lengsukq.opaldesk.desktop.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.PairedSplitPair
import io.github.lengsukq.opaldesk.desktop.model.PreviewOverlayState
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.dp
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.overlay.flp
import io.github.lengsukq.opaldesk.overlay.roundedBg
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Automatic frosted glass shared divider pill that appears strictly when two windows are snapped
 * side-by-side (`Left` + `Right`). Dragging previews both windows at 60fps and atomically commits
 * their coupled bounds on release — eliminating the need for a separate rigid "tiling mode".
 */
@SuppressLint("ClickableViewAccessibility")
internal class PairedSnapDividerController(
    private val host: DesktopShellHost,
    private val onDualPreview: (PreviewOverlayState, IntRect?) -> Unit,
) {
    companion object {
        private const val SURFACE_KEY = "dex_paired_divider"
        private const val HIT_WIDTH_DP = 22f
        private const val HIT_HEIGHT_DP = 88f
        private const val PILL_W_DP = 5f
        private const val PILL_H_DP = 52f
    }

    private var root: FrameLayout? = null
    private var pill: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var activePair: PairedSplitPair? = null
    private var dragging = false

    fun build(ctx: Context) {
        detach()
        val hitW = ctx.dp(HIT_WIDTH_DP)
        val hitH = ctx.dp(HIT_HEIGHT_DP)
        val pillView = View(ctx).apply {
            elevation = ctx.dpF(6f)
        }
        val container = FrameLayout(ctx).apply {
            visibility = View.GONE
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
            addView(pillView, ctx.flp(ctx.dp(PILL_W_DP), ctx.dp(PILL_H_DP), Gravity.CENTER))
            setOnHoverListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> stylePill(ctx, pillView, highlighted = true)
                    MotionEvent.ACTION_HOVER_EXIT -> if (!dragging) stylePill(ctx, pillView, highlighted = false)
                }
                false
            }
        }
        val wmLp = OverlayWindowParams.floating(hitW, hitH, host.winType, touchModal = false)

        var downRawX = 0f
        var startDivX = 0
        var liveLeft = IntRect.EMPTY
        var liveRight = IntRect.EMPTY

        container.setOnTouchListener { _, ev ->
            val pair = activePair ?: return@setOnTouchListener false
            val st = host.state
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    host.popovers.dismissAll()
                    downRawX = ev.rawX
                    startDivX = pair.dividerCenterX
                    liveLeft = pair.leftBounds
                    liveRight = pair.rightBounds
                    stylePill(ctx, pillView, highlighted = true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val targetX = startDivX + (ev.rawX - downRawX).roundToInt()
                    val (lRect, rRect) = DesktopGeometry.computePairedSplitResizedBounds(
                        pair, targetX, st.dpi,
                    )
                    liveLeft = lRect
                    liveRight = rRect
                    val pct = ((lRect.width * 100f) / max(1, lRect.width + rRect.width)).roundToInt().coerceIn(20, 80)
                    val newDivX = (lRect.right + rRect.left) / 2
                    wmLp.x = (newDivX - wmLp.width / 2).coerceIn(0, max(0, st.displayW - wmLp.width))
                    host.surfaceHost.updateWindowLayout(container, wmLp, host.extWm)
                    onDualPreview(
                        PreviewOverlayState(
                            active = true,
                            outerRect = DesktopGeometry.computeOuterFrame(lRect, st.dpi),
                            hudLabel = "分屏比例 $pct% : ${100 - pct}%",
                            isSnapped = true,
                        ),
                        DesktopGeometry.computeOuterFrame(rRect, st.dpi),
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    stylePill(ctx, pillView, highlighted = false)
                    onDualPreview(PreviewOverlayState(), null)
                    if (ev.actionMasked == MotionEvent.ACTION_UP && liveLeft != pair.leftBounds) {
                        host.commitPairedSplitBounds(
                            pair.leftTaskId, pair.leftPkg, liveLeft,
                            pair.rightTaskId, pair.rightPkg, liveRight,
                        )
                    }
                    true
                }
                else -> false
            }
        }

        root = container; pill = pillView; lp = wmLp
        stylePill(ctx, pillView, highlighted = false)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(SURFACE_KEY, OverlayLayer.SPLIT_DIVIDER, container, wmLp))
    }

    fun update(ctx: Context, pair: PairedSplitPair?, tokens: GlassOverlayTokens) {
        if (dragging) return
        activePair = pair
        val view = root ?: return
        val wmLp = lp ?: return
        val pv = pill ?: return
        if (pair == null) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            return
        }
        stylePill(ctx, pv, highlighted = false, tokens = tokens)
        val x = (pair.dividerCenterX - wmLp.width / 2).coerceIn(0, max(0, host.state.displayW - wmLp.width))
        val y = ((pair.topY + pair.bottomY) / 2 - wmLp.height / 2).coerceIn(0, max(0, host.state.displayH - wmLp.height))
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        if (wmLp.x != x || wmLp.y != y) {
            wmLp.x = x; wmLp.y = y
            host.surfaceHost.updateWindowLayout(view, wmLp, host.extWm)
        }
    }

    private fun stylePill(ctx: Context, pv: View, highlighted: Boolean, tokens: GlassOverlayTokens = host.tokens) {
        pv.background = roundedBg(
            fillColor = if (highlighted) tokens.accentBlue else tokens.handlePillIdle,
            radiusPx = ctx.dpF(3f),
            strokeWidthPx = ctx.dp(1f),
            strokeColor = if (highlighted) 0xCCFFFFFF.toInt() else tokens.handlePillStroke,
        )
        pv.animate().cancel()
        pv.animate()
            .scaleX(if (highlighted) 1.45f else 1f)
            .scaleY(if (highlighted) 1.12f else 1f)
            .alpha(if (highlighted) 1f else 0.72f)
            .setDuration(100L)
            .start()
    }

    fun detach() {
        dragging = false
        activePair = null
        host.surfaceHost.unregister(SURFACE_KEY, host.extWm)
        root = null; pill = null; lp = null
    }
}
