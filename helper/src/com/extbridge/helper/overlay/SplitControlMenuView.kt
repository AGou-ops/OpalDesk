package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Multi-topology split-screen popover menu coordinated via [PopoverCoordinator] and [OverlaySurfaceHost].
 */
internal class SplitControlMenuView(
    private val host: OverlayHost,
    private val state: SplitDividerState,
    private val onSelectTopology: (String) -> Unit,
    private val onSelectRatioPreset: (Int) -> Unit,
    private val onSwap: () -> Unit,
    private val onExit: () -> Unit,
) : OverlayPopover {

    companion object {
        private const val POPOVER_ID = "split_menu"
        private const val MENU_WIDTH_DP = 312f
    }

    override val id: String = POPOVER_ID
    override var isOpen: Boolean = false
        private set

    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var btnTopo2: TextView? = null
    private var btnTopo3Master: TextView? = null
    private var btnTopo3Cols: TextView? = null
    private var btnTopoGrid2x2: TextView? = null
    private var btnSwap: TextView? = null
    private val ratioBtns = arrayOfNulls<TextView>(SplitTopologyLayout.PRESET_PCTS.size)
    private var lastSwapLabel = ""

    override fun dismiss() = setOpen(false)
    fun toggle() = setOpen(!isOpen)

    fun resetClosedVisuals() {
        if (!isOpen) root?.let { it.resetPopoverVisuals(); it.visibility = View.GONE }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun build(ctx: Context) {
        val menuRoot = OverlayUiUtils.makeFlyoutCard(ctx, cornerDp = 20f, elevationDp = 16f).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isOpen) setOpen(false)
                false
            }
        }
        menuRoot.addView(TextView(ctx).apply {
            text = "多应用分屏与比例"; setTextColor(0xFFF1F5F9.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true; gravity = Gravity.CENTER_VERTICAL
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, HyperUi.HYPER_BLUE, 13.5f, 5f)
        }, ctx.llp(bottomDp = 8f))

        btnTopo2 = makeTopoChip(ctx, "2 分屏") {
            onSelectTopology(if (host.overlayH > host.overlayW) BridgeClient.LAYOUT_SPLIT_2_V else BridgeClient.LAYOUT_SPLIT_2_H)
        }
        btnTopo3Master = makeTopoChip(ctx, "1+2 三分屏") { onSelectTopology(BridgeClient.LAYOUT_SPLIT_3_MASTER) }
        menuRoot.addView(OverlayUiUtils.makeChipRow(ctx, 6f, items = arrayOf(btnTopo2!!, btnTopo3Master!!)))

        btnTopo3Cols = makeTopoChip(ctx, "三列分屏") { onSelectTopology(BridgeClient.LAYOUT_SPLIT_3_COLS) }
        btnTopoGrid2x2 = makeTopoChip(ctx, "2×2 四宫格") { onSelectTopology(BridgeClient.LAYOUT_GRID_2X2) }
        menuRoot.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 6f, bottomMarginDp = 8f, items = arrayOf(btnTopo3Cols!!, btnTopoGrid2x2!!)))

        val ratios = Array(SplitTopologyLayout.PRESET_PCTS.size) { i ->
            val pct = SplitTopologyLayout.PRESET_PCTS[i]
            OverlayUiUtils.makeFooterChip(ctx, "${pct / 10}:${(100 - pct) / 10}", centered = true).apply {
                setPadDp(6f)
                setOnClickListener { setOpen(false); onSelectRatioPreset(pct) }
            }.also { ratioBtns[i] = it }
        }
        menuRoot.addView(OverlayUiUtils.makeChipRow(ctx, 5f, items = ratios))

        val swap = OverlayUiUtils.makeFooterChip(ctx, if (host.overlayH > host.overlayW) "上下互换" else "左右互换", centered = true).apply {
            HyperUi.setStartIcon(this, HyperUi.ICON_SWAP, 0xFFE2E8F0.toInt(), 12.5f, 4f)
            setOnClickListener { setOpen(false); onSwap() }
        }
        btnSwap = swap; lastSwapLabel = swap.text.toString()

        val exit = OverlayUiUtils.makeFooterChip(ctx, "退出分屏", centered = true).apply {
            setTextColor(0xFFFCA5A5.toInt())
            HyperUi.setStartIcon(this, HyperUi.ICON_CLOSE, 0xFFFCA5A5.toInt(), 12.5f, 4f)
            setOnClickListener { setOpen(false); onExit() }
        }
        menuRoot.addView(OverlayUiUtils.makeChipRow(ctx, 6f, topMarginDp = 8f, items = arrayOf(swap, exit)))

        val wmLp = OverlayWindowParams.popover(ctx.dp(MENU_WIDTH_DP), WRAP, host.winType)
        root = menuRoot; lp = wmLp

        host.popovers.register(this)
        host.surfaceHost.register(
            OverlaySurfaceHost.SurfaceEntry(
                POPOVER_ID, OverlayLayer.POPOVER, menuRoot, wmLp,
                launcherParamsProvider = { ctx.flp(ctx.dp(MENU_WIDTH_DP), WRAP, Gravity.TOP or Gravity.LEFT) },
            ),
        )
    }

    private fun makeTopoChip(ctx: Context, label: String, onClick: () -> Unit): TextView =
        OverlayUiUtils.makeFooterChip(ctx, label, centered = true).apply {
            setPadDp(8f, 7f)
            setOnClickListener { setOpen(false); onClick() }
        }

    fun updateSelection(active: Boolean) {
        val topo = state.topology
        OverlayUiUtils.styleChipWithIcon(btnTopo2, topo == BridgeClient.LAYOUT_SPLIT_2_H || topo == BridgeClient.LAYOUT_SPLIT_2_V, 0)
        OverlayUiUtils.styleChipWithIcon(btnTopo3Master, topo == BridgeClient.LAYOUT_SPLIT_3_MASTER, 0)
        OverlayUiUtils.styleChipWithIcon(btnTopo3Cols, topo == BridgeClient.LAYOUT_SPLIT_3_COLS, 0)
        OverlayUiUtils.styleChipWithIcon(btnTopoGrid2x2, topo == BridgeClient.LAYOUT_GRID_2X2, 0)

        val swapText = if (topo == BridgeClient.LAYOUT_SPLIT_2_V || host.overlayH > host.overlayW) "上下互换" else "左右互换"
        if (swapText != lastSwapLabel) { lastSwapLabel = swapText; btnSwap?.text = swapText }

        val curPct = (state.primaryRatio * 100f).roundToInt()
        SplitTopologyLayout.PRESET_PCTS.forEachIndexed { i, pct ->
            OverlayUiUtils.styleChipWithIcon(ratioBtns[i], active && abs(curPct - pct) <= 3, 0)
        }
    }

    fun setOpen(open: Boolean) {
        val st = host.lastStatus
        val wantOpen = open && st.connected && st.vdId > 0 && st.mirrorMode != 1
        val menu = root
        if (isOpen == wantOpen && (menu == null || (wantOpen && menu.visibility == View.VISIBLE))) return
        isOpen = wantOpen
        if (wantOpen) host.popovers.dismissAllExcept(POPOVER_ID)

        if (menu != null && host.extCtx != null) {
            if (wantOpen) {
                menu.visibility = View.VISIBLE; reposition(); host.dock.cancelAutoHideTimer()
                menu.animatePopover(show = true, shiftYDp = 10f, startScale = 0.92f, enterMs = 180L) {
                    host.cursor.bringCursorToFront()
                }
            } else {
                host.dock.evaluateDockAutoHide(false)
                if (menu.visibility == View.VISIBLE) {
                    menu.animatePopover(show = false, shiftYDp = 10f, startScale = 0.94f, exitMs = 135L) {
                        if (!isOpen) resetClosedVisuals()
                    }
                } else {
                    menu.visibility = View.GONE
                }
            }
        }
        host.cursor.bringCursorToFront()
    }

    fun reposition() {
        val menu = root ?: return; val wmLp = lp ?: return; val ctx = host.extCtx ?: return
        val surfW = host.surfaceWidth; val surfH = host.surfaceHeight
        val fixedW = ctx.dp(MENU_WIDTH_DP)
        menu.measureForMax(fixedW, surfH, exactW = true)
        val mh = max(ctx.dp(160f), menu.measuredHeight)
        val isActive = BridgeClient.isTilingTopology(state.topology) || host.lastStatus.splitActive == 1
        val (x, y) = SplitTopologyLayout.computeMenuPosition(
            isActive, state, surfW, surfH, fixedW, mh,
            ctx.dp(12f), ctx.dp(84f), ctx.dp(22f), ctx.dp(80f), ctx.dp(90f),
        )
        host.surfaceHost.updatePosition(menu, wmLp, host.inLauncherHost, host.extWm, x, y, wmLp.width, wmLp.height, Gravity.TOP or Gravity.LEFT)
    }

    fun detach() {
        host.popovers.unregister(POPOVER_ID)
        host.surfaceHost.unregister(POPOVER_ID, host.extWm)
        root = null; lp = null; isOpen = false
        btnTopo2 = null; btnTopo3Master = null; btnTopo3Cols = null; btnTopoGrid2x2 = null; btnSwap = null
        ratioBtns.fill(null)
    }
}
