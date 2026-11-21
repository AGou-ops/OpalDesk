package com.extbridge.helper.desktop.popovers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.HyperUi
import com.extbridge.helper.desktop.model.GlassOverlayTokens
import com.extbridge.helper.desktop.state.DesktopShellHost
import com.extbridge.helper.overlay.MATCH
import com.extbridge.helper.overlay.OverlayLayer
import com.extbridge.helper.overlay.OverlayPopover
import com.extbridge.helper.overlay.OverlaySurfaceHost
import com.extbridge.helper.overlay.OverlayUiUtils
import com.extbridge.helper.overlay.OverlayWindowParams
import com.extbridge.helper.overlay.WRAP
import com.extbridge.helper.overlay.animatePopover
import com.extbridge.helper.overlay.dp
import com.extbridge.helper.overlay.dpF
import com.extbridge.helper.overlay.llp
import com.extbridge.helper.overlay.measureForMax
import com.extbridge.helper.overlay.resetPopoverVisuals
import com.extbridge.helper.overlay.setPadDp
import kotlin.math.max

sealed class GlassMenuRow {
    data class Action(
        val label: String,
        val iconKind: Int = 0,
        val badge: String? = null,
        val danger: Boolean = false,
        val onClick: () -> Unit,
    ) : GlassMenuRow()

    data class ChipPair(val items: List<Action>) : GlassMenuRow()

    data object Divider : GlassMenuRow()
}

/**
 * Single unified Light/Dark Liquid Glass context menu shared across Window Headers,
 * Taskbar items, App Drawer icons, and Desktop workspace.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXGlassContextMenu(private val host: DesktopShellHost) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dex_glass_context_menu"
        private const val DEFAULT_WIDTH_DP = 252f
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    fun build(ctx: Context) {
        detach()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(10f, 10f, 10f, 10f)
            elevation = ctx.dpF(24f)
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) dismiss()
                false
            }
        }
        val wmLp = OverlayWindowParams.popover(ctx.dp(DEFAULT_WIDTH_DP), WRAP, host.winType, ctx = ctx, wm = host.extWm)
        root = card; lp = wmLp
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.POPOVER, card, wmLp))
    }

    fun show(
        title: String,
        subtitle: String? = null,
        icon: Drawable? = null,
        rows: List<GlassMenuRow>,
        anchorX: Int,
        anchorY: Int,
        preferAboveAnchor: Boolean = false,
        widthDp: Float = DEFAULT_WIDTH_DP,
    ) {
        val ctx = host.extCtx ?: return
        val card = root ?: return
        val wmLp = lp ?: return
        val tokens = host.tokens

        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        card.removeAllViews()
        tokens.applyGlassBackground(card, tokens.panelFill, cornerRadiusDp = 22f, strokeWidthDp = 1.2f)

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(6f, 2f, 6f, 8f)
        }
        if (icon != null) {
            header.addView(
                ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(icon) },
                ctx.llp(ctx.dp(20f), ctx.dp(20f), rightDp = 8f),
            )
        }
        val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(ctx).apply {
            text = title
            setTextColor(tokens.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            paint.isFakeBoldText = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(tokens.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        header.addView(titleCol, ctx.llp(0, WRAP, 1f))
        card.addView(header, ctx.llp(MATCH, WRAP))
        card.addView(View(ctx).apply { setBackgroundColor(tokens.divider) }, ctx.llp(MATCH, 1, bottomDp = 6f))

        for (row in rows) {
            when (row) {
                GlassMenuRow.Divider -> {
                    card.addView(View(ctx).apply { setBackgroundColor(tokens.divider) }, ctx.llp(MATCH, 1, topDp = 5f, bottomDp = 5f))
                }
                is GlassMenuRow.Action -> {
                    card.addView(buildActionRow(ctx, row, tokens), ctx.llp(MATCH, WRAP, topDp = 2f, bottomDp = 2f))
                }
                is GlassMenuRow.ChipPair -> {
                    val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    row.items.forEachIndexed { idx, act ->
                        val chip = buildChipItem(ctx, act, tokens)
                        chipRow.addView(chip, ctx.llp(0, WRAP, 1f, rightDp = if (idx < row.items.lastIndex) 6f else 0f))
                    }
                    card.addView(chipRow, ctx.llp(MATCH, WRAP, topDp = 3f, bottomDp = 3f))
                }
            }
        }

        val menuW = ctx.dp(widthDp)
        val dispW = max(600, host.state.displayW)
        val dispH = max(480, host.state.displayH)
        card.measureForMax(menuW, dispH, exactW = true)
        val menuH = max(ctx.dp(120f), card.measuredHeight)
        val margin = ctx.dp(12f)
        val rawX = if (preferAboveAnchor) anchorX - menuW / 2 else anchorX
        val rawY = if (preferAboveAnchor) anchorY - menuH else anchorY
        wmLp.width = menuW
        wmLp.x = rawX.coerceIn(margin, max(margin, dispW - menuW - margin))
        wmLp.y = rawY.coerceIn(margin, max(margin, dispH - menuH - ctx.dp(72f)))

        card.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(card, wmLp, host.extWm)
        card.animatePopover(show = true, shiftYDp = if (preferAboveAnchor) 8f else -6f, startScale = 0.94f, enterMs = 140L) {
            host.bringCursorToFront()
        }
    }

    private fun buildActionRow(ctx: Context, act: GlassMenuRow.Action, tokens: GlassOverlayTokens): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadDp(10f, 8f, 10f, 8f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            val hover = if (act.danger) 0x26FF3B30 else tokens.activeChipFill
            background = OverlayUiUtils.makeStateListBg(ctx, Color.TRANSPARENT, hover, 12f)
            OverlayUiUtils.attachTactilePress(this, 0.96f)
            val fg = if (act.danger) tokens.accentRed else tokens.textPrimary
            if (act.iconKind > 0) {
                addView(
                    ImageView(ctx).apply { setImageDrawable(HyperUi.icon(ctx, act.iconKind, fg, 14f)) },
                    ctx.llp(ctx.dp(15f), ctx.dp(15f), rightDp = 8f),
                )
            }
            addView(
                TextView(ctx).apply {
                    text = act.label
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                },
                ctx.llp(0, WRAP, 1f),
            )
            if (!act.badge.isNullOrBlank()) {
                addView(
                    TextView(ctx).apply {
                        text = act.badge
                        setTextColor(tokens.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f)
                    },
                    ctx.llp(leftDp = 6f),
                )
            }
            setOnClickListener {
                dismiss()
                act.onClick()
            }
        }

    private fun buildChipItem(ctx: Context, act: GlassMenuRow.Action, tokens: GlassOverlayTokens): TextView =
        TextView(ctx).apply {
            text = act.label
            val fg = if (act.danger) tokens.accentRed else tokens.textPrimary
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadDp(8f, 7f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            background = OverlayUiUtils.makeStateListBg(ctx, tokens.cardInnerFill, tokens.activeChipFill, 12f)
            if (act.iconKind > 0) HyperUi.setStartIcon(this, act.iconKind, fg, 12.5f, 4f)
            OverlayUiUtils.attachTactilePress(this, 0.94f)
            setOnClickListener {
                dismiss()
                act.onClick()
            }
        }

    override fun dismiss() {
        val card = root
        isOpen = false
        if (card != null && card.visibility == View.VISIBLE) {
            card.animatePopover(show = false, shiftYDp = 0f, startScale = 0.95f, exitMs = 105L) {
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
        isOpen = false
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null
    }
}
