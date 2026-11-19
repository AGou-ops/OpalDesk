package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.max

/**
 * Self-contained Dock task-icon context bubble popover coordinated via [PopoverCoordinator]
 * and [OverlaySurfaceHost] ([OverlayLayer.POPOVER]).
 */
internal class DockTaskBubbleController(
    private val host: OverlayHost,
    private val dockRootProvider: () -> View?,
) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dock_task_bubble"
        private const val OPEN_DEBOUNCE_MS = 240L
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var card: LinearLayout? = null
    private var triangle: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var titleView: TextView? = null
    private var modeTagView: TextView? = null
    private var freeformBtn: TextView? = null
    private var split0Btn: TextView? = null
    private var split1Btn: TextView? = null
    private var closeBtn: TextView? = null

    private var task: BridgeClient.TaskEntry? = null
    private var label = ""
    private var anchorCenterX = 0
    private var lastOpenMs = 0L

    override fun dismiss() = setOpen(false)
    fun isPointInCard(winX: Float, winY: Float): Boolean = OverlayUiUtils.isPointInView(card, winX, winY)

    fun resetClosedVisuals() {
        if (!isOpen) {
            card?.animate()?.cancel()
            root?.let { it.resetPopoverVisuals(); it.visibility = View.GONE }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    fun build(ctx: Context) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.LEFT
            clipChildren = false; clipToPadding = false; visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) setOpen(false)
                false
            }
        }
        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadDp(8f); elevation = ctx.dpF(18f)
            background = roundedBg(0xF20F172A.toInt(), ctx.dpF(16f), 1, 0x55FFFFFF)
        }
        cardView.addView(buildHeader(ctx))
        cardView.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) }, ctx.llp(MATCH, 1, bottomDp = 5f))

        freeformBtn = makeRowBtn(ctx, "退出小窗（恢复全屏）", 0xFFE2E8F0.toInt(), HyperUi.CHIP_ACTIVE_BG).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_WINDOW, 0xFFE2E8F0.toInt(), 13f, 5f)
            visibility = View.GONE
            setOnClickListener { onExitFreeformClicked() }
        }.also(cardView::addView)

        split0Btn = makeSplitBtn(ctx, "放入左屏", BridgeClient.SPLIT_ASSIGN_LEFT_TOP)
        split1Btn = makeSplitBtn(ctx, "放入右屏", BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM)
        cardView.addView(OverlayUiUtils.makeChipRow(ctx, 5f, topMarginDp = 4f, items = arrayOf(split0Btn!!, split1Btn!!)))

        closeBtn = makeRowBtn(ctx, "关闭应用", 0xFFFCA5A5.toInt(), 0x36EF4444).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_CLOSE, 0xFFFCA5A5.toInt(), 12.5f, 5f)
            setOnClickListener { onCloseTaskClicked() }
        }.also { cardView.addView(it, ctx.llp(MATCH, WRAP, topDp = 4f)) }

        container.addView(cardView, ctx.llp(ctx.dp(214f), WRAP))
        triangle = BubblePointerView(ctx).also { container.addView(it, ctx.llp(ctx.dp(18f), ctx.dp(9f))) }

        val wmLp = OverlayWindowParams.popover(WRAP, WRAP, host.winType)
        root = container; card = cardView; lp = wmLp

        host.popovers.register(this)
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                POPOVER_ID, OverlayLayer.POPOVER, container, wmLp,
                launcherParamsProvider = { ctx.flp(WRAP, WRAP, Gravity.TOP or Gravity.LEFT) },
            ),
        )
    }

    private fun buildHeader(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadDp(6f, 3f, 6f, 7f)
        iconView = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            .also { addView(it, ctx.llp(ctx.dp(20f), ctx.dp(20f), rightDp = 8f)) }
        titleView = TextView(ctx).apply {
            setTextColor(0xFFF8FAFC.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true; OverlayUiUtils.enforceSingleLine(this); maxWidth = ctx.dp(130f)
        }.also { addView(it, ctx.llp(0, WRAP, 1f, rightDp = 6f)) }
        modeTagView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f); setTextColor(0xFFBAE6FD.toInt())
            setPadDp(6f, 2f); background = roundedBg(0x440284C7, ctx.dpF(8f))
        }.also(::addView)
    }

    private fun makeSplitBtn(ctx: Context, title: String, splitAction: Int): TextView =
        makeRowBtn(ctx, title, 0xFFBAE6FD.toInt(), HyperUi.CHIP_ACTIVE_BG).apply {
            gravity = Gravity.CENTER; setPadDp(8f, 6f)
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, 0xFFBAE6FD.toInt(), 12.5f, 4f)
            setOnClickListener {
                val t = task; setOpen(false)
                if (t != null) host.split.performEnterOrAssignSplitAnimated(splitAction, t.taskId, t.pkg, null)
            }
        }

    private fun makeRowBtn(ctx: Context, text: String, textColor: Int, hoverColor: Int): TextView =
        TextView(ctx).apply {
            this.text = text; setTextColor(textColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadDp(10f, 7f)
            background = OverlayUiUtils.makeStateListBg(ctx, 0x16FFFFFF, hoverColor, 10f)
        }

    private fun onExitFreeformClicked() {
        val target = task; val lbl = label; setOpen(false)
        if (target == null || !target.isFreeform()) return
        host.safeBgExecute {
            val ok = BridgeClient.updateWindowBounds(target.taskId, target.pkg, BridgeClient.WIN_MODE_FULLSCREEN, 0, 0, 0, 0, false)
            host.safePost {
                host.serviceContext.toast(if (ok) "已恢复全屏：$lbl" else "恢复全屏失败：$lbl")
                host.pollRemoteStateAsync()
            }
        }
    }

    private fun onCloseTaskClicked() {
        val target = task; val lbl = label; setOpen(false)
        if (target == null) return
        host.safeBgExecute {
            val ok = target.taskId > 0 && BridgeClient.removeTask(target.taskId)
            host.safePost {
                host.serviceContext.toast(if (ok) "已关闭：$lbl" else "暂无运行任务：$lbl")
                host.pollRemoteStateAsync()
            }
        }
    }

    fun showForTask(anchor: View?, entry: BridgeClient.TaskEntry, appLabel: String?, icon: Drawable?) {
        if (root == null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenMs < OPEN_DEBOUNCE_MS) return
        lastOpenMs = now
        task = entry
        label = appLabel?.takeIf { it.isNotEmpty() } ?: entry.pkg
        host.popovers.dismissAllExcept(POPOVER_ID)

        iconView?.setImageDrawable(host.cloneDrawable(icon))
        titleView?.text = label
        modeTagView?.text = when {
            entry.isFreeform() -> "小窗"; entry.isInSplit() -> "分屏"; entry.isRunning() -> "运行中"; else -> "后台"
        }
        freeformBtn?.visibility = if (entry.isFreeform()) View.VISIBLE else View.GONE
        val portrait = host.overlayH > host.overlayW
        split0Btn?.text = if (portrait) "放入上屏" else "放入左屏"
        split1Btn?.text = if (portrait) "放入下屏" else "放入右屏"
        closeBtn?.let { it.isEnabled = entry.taskId > 0; it.alpha = if (entry.taskId > 0) 1f else 0.45f }

        val surfW = host.surfaceWidth
        var anchorCx = surfW / 2
        val dock = dockRootProvider()
        if (anchor != null && dock != null && dock.width > 0) {
            val dockLoc = IntArray(2); val slotLoc = IntArray(2)
            dock.getLocationOnScreen(dockLoc); anchor.getLocationOnScreen(slotLoc)
            val dockLeft = if (host.inLauncherHost) dock.left else (surfW - dock.width) / 2
            anchorCx = dockLeft + (slotLoc[0] - dockLoc[0]) + anchor.width / 2
        }
        anchorCenterX = anchorCx
        setOpen(true)
    }

    fun setOpen(open: Boolean) {
        val wantOpen = open && task != null
        val container = root
        if (isOpen == wantOpen && (container == null || (wantOpen && container.visibility == View.VISIBLE))) return
        isOpen = wantOpen
        val cardView = card
        if (container != null && host.extCtx != null) {
            if (wantOpen) {
                container.visibility = View.VISIBLE
                reposition()
                host.dock.cancelAutoHideTimer()
                cardView?.animatePopover(show = true, shiftYDp = 10f, startScale = 0.92f, enterMs = 155L) {
                    host.cursor.bringCursorToFront()
                }
            } else {
                host.dock.evaluateDockAutoHide(false)
                if (cardView != null && container.visibility == View.VISIBLE) {
                    cardView.animatePopover(show = false, shiftYDp = 10f, startScale = 0.93f, exitMs = 125L) {
                        if (!isOpen) { root?.visibility = View.GONE; card?.resetPopoverVisuals() }
                    }
                } else {
                    container.visibility = View.GONE
                }
            }
        }
        host.cursor.bringCursorToFront()
    }

    fun reposition() {
        val container = root ?: return
        val wmLp = lp ?: return
        val ctx = host.extCtx ?: return
        val surfW = host.surfaceWidth; val surfH = host.surfaceHeight
        container.measureForMax(surfW, surfH)
        val bw = max(ctx.dp(214f), container.measuredWidth)
        val bh = max(ctx.dp(140f), container.measuredHeight)
        val dock = dockRootProvider()
        val dockH = dock?.height?.takeIf { it > 0 } ?: ctx.dp(46f)
        val anchorTop = if (host.inLauncherHost && dock != null && dock.height > 0) dock.top else surfH - ctx.dp(10f) - dockH
        val margin = ctx.dp(12f)
        val x = (anchorCenterX - bw / 2).coerceIn(margin, max(margin, surfW - bw - margin))
        val y = max(margin, anchorTop - bh - ctx.dp(4f))

        triangle?.let { tri ->
            val triW = ctx.dp(18f); val pad = ctx.dp(16f)
            tri.translationX = ((anchorCenterX - x) - triW / 2).coerceIn(pad, max(pad, bw - triW - pad)).toFloat()
        }
        host.surfaceHost.updatePosition(container, wmLp, host.inLauncherHost, host.extWm, x, y, wmLp.width, wmLp.height, Gravity.TOP or Gravity.LEFT)
    }

    fun detach() {
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; card = null; triangle = null; lp = null
        iconView = null; titleView = null; modeTagView = null
        freeformBtn = null; split0Btn = null; split1Btn = null; closeBtn = null
        task = null; isOpen = false
    }

    private class BubblePointerView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF20F172A.toInt(); style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            path.reset(); path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w * 0.5f, h); path.close()
            canvas.drawPath(path, fill)
            canvas.drawLine(0f, 0f, w * 0.5f, h, stroke)
            canvas.drawLine(w, 0f, w * 0.5f, h, stroke)
        }
    }
}
