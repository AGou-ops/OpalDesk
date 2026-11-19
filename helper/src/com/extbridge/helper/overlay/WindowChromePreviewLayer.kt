package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SnapZone { NONE, FULLSCREEN, LEFT_HALF, RIGHT_HALF }

internal data class MenuChipSpec(val label: String, val iconKind: Int = 0, val danger: Boolean = false, val onClick: () -> Unit)

internal object WindowChromeGeometry {
    const val HANDLE_IDLE_ALPHA = 0.38f

    fun sanitizeWindowRect(host: OverlayHost, win: BridgeClient.ManagedWindowInfo): Rect {
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        if (win.right <= win.left || win.bottom <= win.top) return Rect(0, 0, dispW, dispH)
        val l = win.left.coerceIn(0, dispW - 60); val t = win.top.coerceIn(0, dispH - 60)
        return Rect(l, t, win.right.coerceIn(l + 60, dispW), win.bottom.coerceIn(t + 60, dispH))
    }

    fun isRectFullscreen(host: OverlayHost, r: Rect): Boolean =
        r.width() >= (max(OverlayConstants.MIN_SURFACE_PX, host.overlayW) * 0.94f).roundToInt()

    fun effectiveDpi(host: OverlayHost): Int =
        host.lastStatus.virtDpi.takeIf { it > 0 } ?: max(160, host.overlayDpi)

    fun makeHandlePillBg(ctx: Context, highlighted: Boolean): GradientDrawable = roundedBg(
        if (highlighted) HyperUi.HYPER_BLUE else 0xFFE2E8F0.toInt(), ctx.dpF(4f), ctx.dp(1f),
        if (highlighted) 0xCCFFFFFF.toInt() else 0x660F172A,
    )

    fun animateHandleHighlight(root: View, pill: View, ctx: Context, highlighted: Boolean) {
        root.animate().cancel()
        root.animate().alpha(if (highlighted) 1f else HANDLE_IDLE_ALPHA).setDuration(110L).start()
        pill.animate().cancel()
        pill.animate().scaleX(if (highlighted) 1.35f else 1f).scaleY(if (highlighted) 1.08f else 1f).setDuration(110L).start()
        pill.background = makeHandlePillBg(ctx, highlighted)
    }
}

/** Shared compact chip-menu popover unifying Single-App & Freeform window action menus. */
internal class WindowActionMenu(
    private val host: OverlayHost,
    override val id: String,
    private val widthDp: Float,
    private val hasSubtitle: Boolean,
    private val onDismissed: (() -> Unit)? = null,
) : OverlayPopover {

    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var headerView: TextView? = null
    private var subView: TextView? = null

    override fun dismiss() = close()

    @SuppressLint("ClickableViewAccessibility")
    fun build(ctx: Context, rows: List<List<MenuChipSpec>>) {
        val container = OverlayUiUtils.makeFlyoutCard(ctx).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) close()
                false
            }
        }
        headerView = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }.also { container.addView(it, ctx.llp(MATCH, WRAP, bottomDp = if (hasSubtitle) 0f else 8f)) }

        if (hasSubtitle) {
            subView = TextView(ctx).apply {
                setTextColor(0xFF94A3B8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); isSingleLine = true
            }.also { container.addView(it, ctx.llp(MATCH, WRAP, topDp = 2f, bottomDp = 8f)) }
        }

        rows.forEachIndexed { idx, rowSpecs ->
            val chips = rowSpecs.map { spec ->
                OverlayUiUtils.makeFooterChip(ctx, spec.label, centered = true).apply {
                    setPadDp(8f, 7f)
                    if (spec.danger) setTextColor(0xFFFCA5A5.toInt())
                    if (spec.iconKind > 0) HyperUi.setStartIcon(this, spec.iconKind, if (spec.danger) 0xFFFCA5A5.toInt() else 0xFFE2E8F0.toInt(), 12f, 4f)
                    setOnClickListener { spec.onClick() }
                }
            }.toTypedArray()
            container.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = if (idx > 0) 6f else 0f, items = chips))
        }

        val wmLp = OverlayWindowParams.popover(ctx.dp(widthDp), WRAP, host.winType)
        root = container; lp = wmLp
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(id, OverlayLayer.POPOVER, container, wmLp))
    }

    fun show(title: String, subtitle: String? = null, anchorProvider: (menuW: Int, menuH: Int) -> Pair<Int, Int>) {
        val menu = root ?: return; val wmLp = lp ?: return; val ctx = host.extCtx ?: return
        isOpen = true
        host.popovers.dismissAllExcept(id)
        headerView?.text = title
        if (hasSubtitle && subtitle != null) subView?.text = subtitle

        val menuW = wmLp.width
        val dispW = max(OverlayConstants.MIN_SURFACE_PX, host.overlayW)
        val dispH = max(OverlayConstants.MIN_SURFACE_PX, host.overlayH)
        menu.measureForMax(menuW, dispH, exactW = true)
        val menuH = max(ctx.dp(130f), menu.measuredHeight)
        val margin = ctx.dp(12f)
        val (rawX, rawY) = anchorProvider(menuW, menuH)
        wmLp.x = rawX.coerceIn(margin, max(margin, dispW - menuW - margin))
        wmLp.y = rawY.coerceIn(margin, max(margin, dispH - menuH - ctx.dp(64f)))

        menu.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(menu, wmLp, host.extWm)
        menu.animatePopover(show = true, shiftYDp = 0f, startScale = 0.94f, enterMs = 150L) {
            host.cursor.bringCursorToFront()
        }
    }

    fun close() {
        val wasOpen = isOpen; isOpen = false
        root?.let { it.animate().cancel(); it.visibility = View.GONE }
        if (wasOpen) onDismissed?.invoke()
    }

    fun detach() {
        close()
        host.popovers.unregister(id)
        host.surfaceHost.unregister(id, host.extWm)
        root = null; lp = null; headerView = null; subView = null
    }
}

/** Hardware-accelerated 60fps translucent frosted preview frame & dp-width HUD overlay. */
internal class PreviewOverlayView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val previewRect = RectF()
    private val hudRect = RectF()
    private var hudText = ""

    private val scrimPaint = Paint().apply { color = 0x26000000; style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2B0D84FF; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.6f * density
    }
    private val hudBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEC0F172A.toInt(); style = Paint.Style.FILL }
    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9938BDF8.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }

    fun updatePreview(rect: Rect, label: String, snap: SnapZone) {
        previewRect.set(rect); hudText = label
        val snapped = snap != SnapZone.NONE
        fillPaint.color = if (snapped) 0x3A30D158 else 0x2B0D84FF
        strokePaint.color = if (snapped) HyperUi.HYPER_GREEN else 0xFF38BDF8.toInt()
        hudStrokePaint.color = if (snapped) 0xCC30D158.toInt() else 0x9938BDF8.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (previewRect.isEmpty) return
        val corner = 16f * density
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRoundRect(previewRect, corner, corner, fillPaint)
        canvas.drawRoundRect(previewRect, corner, corner, strokePaint)
        if (hudText.isNotEmpty()) {
            val pillH = 34f * density; val pillW = textPaint.measureText(hudText) + 32f * density
            val cx = previewRect.centerX().coerceIn(pillW * 0.5f + 12f * density, width - pillW * 0.5f - 12f * density)
            val topY = (previewRect.top + 22f * density).coerceIn(18f * density, max(18f * density, height - pillH - 24f * density))
            hudRect.set(cx - pillW * 0.5f, topY, cx + pillW * 0.5f, topY + pillH)
            canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudBgPaint)
            canvas.drawRoundRect(hudRect, pillH * 0.5f, pillH * 0.5f, hudStrokePaint)
            val fm = textPaint.fontMetrics
            canvas.drawText(hudText, hudRect.centerX(), hudRect.centerY() - (fm.ascent + fm.descent) * 0.5f, textPaint)
        }
    }
}

/** Minimalist bottom-right curved corner resize grip for FREEFORM windows. */
internal class CornerGripView(ctx: Context) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBBCBD5E1.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun setActive(active: Boolean) { paint.color = if (active) HyperUi.HYPER_BLUE else 0xBBCBD5E1.toInt(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 5f * density; val r = 6f * density
        path.reset()
        path.moveTo(w - pad, h * 0.34f); path.lineTo(w - pad, h - pad - r)
        path.quadTo(w - pad, h - pad, w - pad - r, h - pad); path.lineTo(w * 0.34f, h - pad)
        canvas.drawPath(path, paint)
    }
}
