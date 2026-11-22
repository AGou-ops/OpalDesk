package io.github.lengsukq.opaldesk.desktop.popovers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.HyperUi
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.GlassOverlayTokens
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
import kotlin.math.min

/**
 * Keyboard-focusable (`Alt+Tab` / `←` / `→` / `Enter` / `Delete` / `Esc`) Liquid Glass
 * Samsung DeX Window Switcher displaying live `TaskSnapshot` cards above all windows.
 */
@SuppressLint("ClickableViewAccessibility")
internal class DeXWindowSwitcherOverlay(private val host: DesktopShellHost) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "dex_window_switcher"
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var titleLabel: TextView? = null
    private var hintLabel: TextView? = null
    private var btnShowDesktop: TextView? = null
    private var emptyLabel: TextView? = null
    private var cardsRow: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null

    private var listedWindows: List<DesktopWindow> = emptyList()
    private var selectedIndex: Int = 0
    private val cardViews = ArrayList<LinearLayout>()
    private val activeBitmaps = ArrayList<Bitmap>()

    fun build(ctx: Context) {
        detach()
        val container = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) {
                    if (handleKey(event.keyCode)) return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            setPadDp(20f)
            setOnTouchListener { _, ev ->
                if (ev != null && (ev.actionMasked == MotionEvent.ACTION_OUTSIDE || ev.actionMasked == MotionEvent.ACTION_DOWN)) {
                    if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE || !OverlayUiUtils.isPointInView(panel, ev.x, ev.y)) {
                        dismiss()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        val cardPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadDp(20f, 16f, 20f, 18f)
            elevation = ctx.dpF(28f)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLabel = TextView(ctx).apply {
            text = "窗口总览"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            paint.isFakeBoldText = true
        }.also { header.addView(it, ctx.llp(0, WRAP, 1f)) }

        hintLabel = TextView(ctx).apply {
            text = "Tab / 方向键切换 · Enter 聚焦 · Esc 退出"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
        }.also { header.addView(it, ctx.llp(rightDp = 12f)) }

        btnShowDesktop = TextView(ctx).apply {
            text = "显示桌面"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f)
            paint.isFakeBoldText = true
            setPadDp(10f, 5f)
            pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
            setOnClickListener {
                dismiss()
                host.toggleShowDesktop()
            }
        }.also { header.addView(it) }
        cardPanel.addView(header, ctx.llp(MATCH, WRAP, bottomDp = 12f))

        emptyLabel = TextView(ctx).apply {
            text = "当前暂无打开的窗口，可从左下角应用抽屉或桌面开启应用"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
            gravity = Gravity.CENTER
            setPadDp(24f, 28f)
            visibility = View.GONE
        }.also { cardPanel.addView(it, ctx.llp(MATCH, WRAP)) }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cardsRow = row
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        }
        cardPanel.addView(scroll, ctx.llp(MATCH, WRAP))
        container.addView(cardPanel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        val wmLp = OverlayWindowParams.popover(MATCH, MATCH, host.winType, gravity = Gravity.CENTER, ctx = ctx, wm = host.extWm)
        root = container; panel = cardPanel; lp = wmLp
        host.popovers.register(this)
        host.surfaceHost.register(OverlaySurfaceHost.SurfaceEntry(POPOVER_ID, OverlayLayer.SWITCHER, container, wmLp))
    }

    /** Toggles switcher, or cycles selection forward if already open (true Alt+Tab cadence). */
    fun toggleOrCycle() {
        if (isOpen) {
            if (listedWindows.size > 1) {
                selectedIndex = (selectedIndex + 1) % listedWindows.size
                refreshSelectionHighlight()
            } else {
                dismiss()
            }
        } else {
            open()
        }
    }

    fun open() {
        val ctx = host.extCtx ?: return
        val container = root ?: return
        val cardPanel = panel ?: return
        val wmLp = lp ?: return

        host.popovers.dismissAllExcept(POPOVER_ID)
        isOpen = true
        val tokens = host.tokens
        tokens.applyGlassBackground(cardPanel, tokens.panelFill, cornerRadiusDp = 30f, strokeWidthDp = 1.25f)
        titleLabel?.setTextColor(tokens.textPrimary)
        hintLabel?.setTextColor(tokens.textSecondary)
        emptyLabel?.setTextColor(tokens.textSecondary)
        btnShowDesktop?.setTextColor(tokens.accentBlue)
        btnShowDesktop?.background = roundedBg(tokens.activeChipFill, ctx.dpF(12f))

        populateCards(ctx, tokens)
        wmLp.flags = wmLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        container.visibility = View.VISIBLE
        host.surfaceHost.updateWindowLayout(container, wmLp, host.extWm)
        container.requestFocus()
        cardPanel.animatePopover(show = true, shiftYDp = 8f, startScale = 0.94f, enterMs = 165L) {
            host.bringCursorToFront()
        }
    }

    override fun dismiss() {
        val container = root
        val cardPanel = panel
        val wmLp = lp
        isOpen = false
        if (wmLp != null) {
            wmLp.flags = wmLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (container != null && cardPanel != null && container.visibility == View.VISIBLE) {
            if (wmLp != null) host.surfaceHost.updateWindowLayout(container, wmLp, host.extWm)
            cardPanel.animatePopover(show = false, shiftYDp = 8f, startScale = 0.95f, exitMs = 120L) {
                if (!isOpen) {
                    container.visibility = View.GONE
                    cardPanel.resetPopoverVisuals()
                    recycleSnapshots()
                }
            }
        } else {
            container?.visibility = View.GONE
            recycleSnapshots()
        }
    }

    private fun handleKey(keyCode: Int): Boolean {
        if (!isOpen) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> { dismiss(); true }
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (listedWindows.isNotEmpty()) {
                    selectedIndex = (selectedIndex + 1) % listedWindows.size
                    refreshSelectionHighlight()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (listedWindows.isNotEmpty()) {
                    selectedIndex = (selectedIndex - 1 + listedWindows.size) % listedWindows.size
                    refreshSelectionHighlight()
                }
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                val target = listedWindows.getOrNull(selectedIndex)
                dismiss()
                if (target != null) host.focusWindow(target.taskId, target.pkg)
                true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                val target = listedWindows.getOrNull(selectedIndex)
                if (target != null) {
                    host.closeWindow(target.taskId, target.pkg)
                    dismiss()
                }
                true
            }
            else -> false
        }
    }

    private fun populateCards(ctx: Context, tokens: GlassOverlayTokens) {
        val row = cardsRow ?: return
        recycleSnapshots()
        row.removeAllViews()
        cardViews.clear()

        // Order top -> bottom so index 0 is current, index 1 is previous app (standard Alt+Tab UX)
        val wins = host.state.windows.asReversed()
        listedWindows = wins
        selectedIndex = if (wins.size > 1) 1 else 0

        if (wins.isEmpty()) {
            emptyLabel?.visibility = View.VISIBLE
            return
        }
        emptyLabel?.visibility = View.GONE
        val pm = host.serviceContext.packageManager
        val cardW = min(ctx.dp(228f), max(ctx.dp(176f), host.state.displayW / 5))
        val thumbH = (cardW * 0.62f).toInt()

        wins.forEachIndexed { idx, w ->
            val label = host.getCachedLabel(pm, w.pkg)
            val icon = host.getCachedIcon(pm, w.pkg)
            val itemCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadDp(10f, 10f, 10f, 10f)
                pointerIcon = PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_HAND)
                OverlayUiUtils.attachTactilePress(this, 0.95f)
                setOnClickListener {
                    dismiss()
                    host.focusWindow(w.taskId, w.pkg)
                }
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(icon) },
                    ctx.llp(ctx.dp(18f), ctx.dp(18f), rightDp = 6f))
                addView(TextView(ctx).apply {
                    text = label
                    setTextColor(tokens.textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                    paint.isFakeBoldText = true
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }, ctx.llp(0, WRAP, 1f))
                addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageDrawable(HyperUi.icon(ctx, HyperUi.ICON_CLOSE, tokens.accentRed, 11.5f))
                    background = OverlayUiUtils.makeStateListBg(ctx, 0x16FF3B30, 0x33FF3B30, 9f)
                    setOnClickListener {
                        host.closeWindow(w.taskId, w.pkg)
                        dismiss()
                    }
                }, ctx.llp(ctx.dp(22f), ctx.dp(22f), leftDp = 4f))
            }
            itemCard.addView(topRow, ctx.llp(MATCH, WRAP, bottomDp = 8f))

            val thumbFrame = FrameLayout(ctx).apply {
                tokens.applyGlassBackground(this, tokens.cardInnerFill, cornerRadiusDp = 14f, strokeWidthDp = 0.8f, clipOutline = true)
            }
            val thumbImg = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumbFrame.addView(thumbImg, FrameLayout.LayoutParams(MATCH, MATCH))

            val badge = when {
                w.minimized -> "已最小化"
                w.isImmersive -> "全屏"
                w.isMaximized -> "最大化"
                w.focused -> "当前窗口"
                else -> "后台小窗"
            }
            thumbFrame.addView(TextView(ctx).apply {
                text = badge
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f)
                paint.isFakeBoldText = true
                setPadDp(7f, 2.5f)
                background = roundedBg(if (w.focused && !w.minimized) tokens.accentBlue else 0x99111827.toInt(), ctx.dpF(9f))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = ctx.dp(6f); bottomMargin = ctx.dp(6f)
            })
            itemCard.addView(thumbFrame, ctx.llp(cardW - ctx.dp(20f), thumbH))

            cardViews.add(itemCard)
            row.addView(itemCard, ctx.llp(cardW, WRAP, rightDp = if (idx < wins.lastIndex) 12f else 0f))

            host.safeBgExecute {
                val snap = BridgeClient.captureTaskSnapshot(w.taskId, w.pkg, true)
                val bmp = snap.bitmap ?: return@safeBgExecute
                host.safePost {
                    if (isOpen && thumbImg.isAttachedToWindow && !bmp.isRecycled) {
                        if (snap.ownBitmap) activeBitmaps.add(bmp)
                        thumbImg.setImageBitmap(bmp)
                    } else if (snap.ownBitmap && !bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
            }
        }
        refreshSelectionHighlight()
    }

    private fun refreshSelectionHighlight() {
        val tokens = host.tokens
        cardViews.forEachIndexed { idx, view ->
            val sel = idx == selectedIndex
            tokens.applyGlassBackground(
                view,
                if (sel) tokens.activeChipFill else tokens.cardInnerFill,
                cornerRadiusDp = 22f,
                strokeWidthDp = if (sel) 2.0f else 1.0f,
            )
        }
    }

    private fun recycleSnapshots() {
        cardsRow?.let { row ->
            for (i in 0 until row.childCount) {
                val card = row.getChildAt(i) as? LinearLayout ?: continue
                val frame = card.getChildAt(1) as? FrameLayout ?: continue
                (frame.getChildAt(0) as? ImageView)?.setImageDrawable(null)
            }
        }
        activeBitmaps.forEach { if (!it.isRecycled) runCatching { it.recycle() } }
        activeBitmaps.clear()
    }

    fun detach() {
        isOpen = false
        recycleSnapshots()
        cardViews.clear()
        listedWindows = emptyList()
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; panel = null; titleLabel = null; hintLabel = null; btnShowDesktop = null
        emptyLabel = null; cardsRow = null; lp = null
    }
}
