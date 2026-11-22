package io.github.lengsukq.opaldesk.desktop.popovers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
import io.github.lengsukq.opaldesk.desktop.model.SnapZone
import io.github.lengsukq.opaldesk.desktop.state.DesktopShellHost
import io.github.lengsukq.opaldesk.overlay.MATCH
import io.github.lengsukq.opaldesk.overlay.OverlayLayer
import io.github.lengsukq.opaldesk.overlay.OverlayPopover
import io.github.lengsukq.opaldesk.overlay.OverlaySurfaceHost
import io.github.lengsukq.opaldesk.overlay.OverlayUiUtils
import io.github.lengsukq.opaldesk.overlay.OverlayWindowParams
import io.github.lengsukq.opaldesk.overlay.WRAP
import io.github.lengsukq.opaldesk.overlay.animatePopover
import io.github.lengsukq.opaldesk.overlay.dp
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.overlay.llp
import io.github.lengsukq.opaldesk.overlay.resetPopoverVisuals
import io.github.lengsukq.opaldesk.overlay.roundedBg
import io.github.lengsukq.opaldesk.overlay.setPadDp
import kotlin.math.max

/**
 * Visual Liquid Glass Snap Layouts popover triggered when hovering a window header's
 * Maximize button for 380ms (4 interactive templates: 50:50, 65:35, 3-Columns, 2×2 Grid).
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXSnapLayoutsPopover(private val host: DesktopShellHost) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dex_snap_layouts"
        private const val CARD_WIDTH_DP = 276f
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var targetWindow: DesktopWindow? = null

    fun build(ctx: Context) {
        detach()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(12f, 10f, 12f, 12f)
            elevation = ctx.dpF(24f)
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) dismiss()
                false
            }
        }
        val wmLp = OverlayWindowParams.popover(ctx.dp(CARD_WIDTH_DP), WRAP, host.winType, ctx = ctx, wm = host.extWm)
        root = card; lp = wmLp
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.POPOVER, card, wmLp))
    }

    fun show(win: DesktopWindow, anchorCenterX: Int, anchorY: Int) {
        val ctx = host.extCtx ?: return
        val card = root ?: return
        val wmLp = lp ?: return
        val tokens = host.tokens

        targetWindow = win
        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        card.removeAllViews()
        tokens.applyGlassBackground(card, tokens.panelFill, cornerRadiusDp = 22f, strokeWidthDp = 1.2f)

        card.addView(TextView(ctx).apply {
            text = "快速分屏贴靠模板"
            setTextColor(tokens.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
            paint.isFakeBoldText = true
        }, ctx.llp(MATCH, WRAP, bottomDp = 8f))

        // Row 1: [50:50] + [65:35]
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(buildHalfTemplate(ctx, 1f, 1f, SnapZone.LEFT_HALF, SnapZone.RIGHT_HALF, tokens), ctx.llp(0, ctx.dp(56f), 1f, rightDp = 8f))
        row1.addView(buildHalfTemplate(ctx, 65f, 35f, SnapZone.LEFT_WIDE_65, SnapZone.RIGHT_NARROW_35, tokens), ctx.llp(0, ctx.dp(56f), 1f))
        card.addView(row1, ctx.llp(MATCH, WRAP, bottomDp = 8f))

        // Row 2: [3-Columns] + [2x2 Grid]
        val row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(buildThreeColTemplate(ctx, tokens), ctx.llp(0, ctx.dp(56f), 1f, rightDp = 8f))
        row2.addView(buildQuadTemplate(ctx, tokens), ctx.llp(0, ctx.dp(56f), 1f))
        card.addView(row2, ctx.llp(MATCH, WRAP))

        val cardW = ctx.dp(CARD_WIDTH_DP)
        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        val margin = ctx.dp(12f)
        wmLp.x = (anchorCenterX - cardW / 2).coerceIn(margin, max(margin, dispW - cardW - margin))
        wmLp.y = anchorY.coerceIn(margin, max(margin, dispH - ctx.dp(220f)))

        card.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(card, wmLp, host.extWm)
        card.animatePopover(show = true, shiftYDp = -6f, startScale = 0.94f, enterMs = 135L) {
            host.bringCursorToFront()
        }
    }

    private fun buildHalfTemplate(
        ctx: Context,
        w1: Float,
        w2: Float,
        z1: SnapZone,
        z2: SnapZone,
        tokens: GlassOverlayTokens,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadDp(4f)
        background = roundedBg(tokens.cardInnerFill, ctx.dpF(12f))
        addView(makeZoneTile(ctx, z1, tokens), ctx.llp(0, MATCH, w1, rightDp = 3f))
        addView(makeZoneTile(ctx, z2, tokens), ctx.llp(0, MATCH, w2))
    }

    private fun buildThreeColTemplate(ctx: Context, tokens: GlassOverlayTokens): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadDp(4f)
        background = roundedBg(tokens.cardInnerFill, ctx.dpF(12f))
        addView(makeZoneTile(ctx, SnapZone.THIRD_LEFT, tokens), ctx.llp(0, MATCH, 1f, rightDp = 3f))
        addView(makeZoneTile(ctx, SnapZone.THIRD_CENTER, tokens), ctx.llp(0, MATCH, 1f, rightDp = 3f))
        addView(makeZoneTile(ctx, SnapZone.THIRD_RIGHT, tokens), ctx.llp(0, MATCH, 1f))
    }

    private fun buildQuadTemplate(ctx: Context, tokens: GlassOverlayTokens): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadDp(4f)
        background = roundedBg(tokens.cardInnerFill, ctx.dpF(12f))
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeZoneTile(ctx, SnapZone.TOP_LEFT, tokens), ctx.llp(0, MATCH, 1f, rightDp = 3f))
            addView(makeZoneTile(ctx, SnapZone.TOP_RIGHT, tokens), ctx.llp(0, MATCH, 1f))
        }
        val bot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeZoneTile(ctx, SnapZone.BOTTOM_LEFT, tokens), ctx.llp(0, MATCH, 1f, rightDp = 3f))
            addView(makeZoneTile(ctx, SnapZone.BOTTOM_RIGHT, tokens), ctx.llp(0, MATCH, 1f))
        }
        addView(top, ctx.llp(MATCH, 0, 1f, bottomDp = 3f))
        addView(bot, ctx.llp(MATCH, 0, 1f))
    }

    private fun makeZoneTile(ctx: Context, zone: SnapZone, tokens: GlassOverlayTokens): View = FrameLayout(ctx).apply {
        pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
        val idle = if (tokens.isDark) 0x33FFFFFF else 0x200F172A
        background = OverlayUiUtils.makeStateListBg(ctx, idle, tokens.accentBlue, 7f)
        OverlayUiUtils.attachTactilePress(this, 0.92f)
        setOnClickListener {
            val win = targetWindow
            dismiss()
            if (win != null) host.snapWindow(win.taskId, win.pkg, zone)
        }
    }

    override fun dismiss() {
        val card = root
        isOpen = false
        targetWindow = null
        if (card != null && card.visibility == View.VISIBLE) {
            card.animatePopover(show = false, shiftYDp = -4f, startScale = 0.95f, exitMs = 100L) {
                if (!isOpen) {
                    card.visibility = View.GONE
                    card.resetPopoverVisuals()
                }
            }
        } else {
            card?.let { it.animate().cancel(); it.visibility = View.GONE }
        }
    }

    fun detach() {
        isOpen = false; targetWindow = null
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null
    }
}
