package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.extbridge.helper.BridgeClient
import com.extbridge.helper.HyperUi
import com.extbridge.helper.StageTransitionView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages the external VirtualDisplay 2/3/4-app tiling split-screen draggable divider handles
 * (`SPLIT_2_H`, `SPLIT_2_V`, `SPLIT_3_MASTER`, `SPLIT_3_COLS`, `GRID_2X2`),
 * topology/ratio/swap/exit popover menu, and HyperOS window split choreography animations.
 */
class SplitScreenOverlayController(private val host: OverlayHost) {

    companion object {
        private val SPLIT_PRESET_PCTS = intArrayOf(30, 40, 50, 60, 70)
    }

    var splitDividerRoot: FrameLayout? = null
        private set
    var splitDividerLp: WindowManager.LayoutParams? = null
        private set
    var secondaryDividerRoot: FrameLayout? = null
        private set
    var secondaryDividerLp: WindowManager.LayoutParams? = null
        private set
    var splitMenuRoot: LinearLayout? = null
        private set
    var splitMenuLp: WindowManager.LayoutParams? = null
        private set
    var isSplitMenuOpen: Boolean = false
        private set
    var displayedSplitRatio: Float = 0.50f
        private set

    private var activeTopology: String = BridgeClient.LAYOUT_NONE
    private var displayedSecondaryRatio: Float = 0.50f
    private var displayedCol0Pct: Int = 33
    private var displayedCol1Pct: Int = 34

    private var splitDividerAnimator: ValueAnimator? = null
    private var splitDividerPill: TextView? = null
    private var secondaryDividerPill: TextView? = null
    private var splitDividerPortrait: Boolean = false
    private var splitDragging: Boolean = false
    private var secondaryDragging: Boolean = false
    private var splitSwapBtn: TextView? = null
    private val splitRatioBtns = arrayOfNulls<TextView>(5)
    private var btnTopo2: TextView? = null
    private var btnTopo3Master: TextView? = null
    private var btnTopo3Cols: TextView? = null
    private var btnTopoGrid2x2: TextView? = null

    fun resetClosedVisualsBeforeHostSwap() {
        if (!isSplitMenuOpen) {
            splitMenuRoot?.let { menu ->
                menu.animate().cancel()
                menu.visibility = View.GONE
                menu.alpha = 1f
                menu.translationY = 0f
                menu.scaleX = 1f
                menu.scaleY = 1f
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun buildSplitOverlays(ctx: Context, st: BridgeClient.Status) {
        splitDividerPortrait = host.overlayH > host.overlayW

        // Primary divider (axis 0)
        val dividerRoot = FrameLayout(ctx).apply { visibility = View.GONE }
        splitDividerRoot = dividerRoot
        val pill = makeDividerPill(ctx)
        splitDividerPill = pill
        dividerRoot.addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        attachPrimaryDividerTouch(ctx, dividerRoot)

        splitDividerLp = WindowManager.LayoutParams(
            if (splitDividerPortrait) OverlayUiUtils.dp(ctx, 76f) else OverlayUiUtils.dp(ctx, 24f),
            if (splitDividerPortrait) OverlayUiUtils.dp(ctx, 24f) else OverlayUiUtils.dp(ctx, 76f),
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = max(0, host.overlayW / 2 - OverlayUiUtils.dp(ctx, 12f))
            y = max(0, host.overlayH / 2 - OverlayUiUtils.dp(ctx, 38f))
        }

        // Secondary divider (axis 1: right horizontal for SPLIT_3_MASTER, 2nd vertical for SPLIT_3_COLS, horizontal for GRID_2X2)
        val secRoot = FrameLayout(ctx).apply { visibility = View.GONE }
        secondaryDividerRoot = secRoot
        val secPill = makeDividerPill(ctx)
        secondaryDividerPill = secPill
        secRoot.addView(
            secPill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        attachSecondaryDividerTouch(ctx, secRoot)

        secondaryDividerLp = WindowManager.LayoutParams(
            OverlayUiUtils.dp(ctx, 76f),
            OverlayUiUtils.dp(ctx, 24f),
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = max(0, host.overlayW / 2 - OverlayUiUtils.dp(ctx, 38f))
            y = max(0, host.overlayH / 2 - OverlayUiUtils.dp(ctx, 12f))
        }

        // Compact Multi-Topology Split Control Popover Menu
        val menuRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
                OverlayUiUtils.dp(ctx, 12f),
                OverlayUiUtils.dp(ctx, 10f),
            )
            elevation = OverlayUiUtils.dp(ctx, 16f).toFloat()
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(HyperUi.FLYOUT_BG)
                cornerRadius = OverlayUiUtils.dp(ctx, 20f).toFloat()
                setStroke(1, HyperUi.FLYOUT_STROKE)
            }
        }
        splitMenuRoot = menuRoot

        val title = TextView(ctx).apply {
            text = "多应用分屏与比例"
            setTextColor(0xFFF1F5F9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER_VERTICAL
            HyperUi.setStartIcon(this, HyperUi.ICON_SPLIT, HyperUi.HYPER_BLUE, 13.5f, 5f)
        }
        val tLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = OverlayUiUtils.dp(ctx, 8f) }
        menuRoot.addView(title, tLp)

        // Topology Switcher Rows: 2分屏 / 1+2三分屏 / 三列分屏 / 2×2四宫格
        val topoRow1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnTopo2 = makeTopoChip(ctx, "2 分屏") {
            setSplitMenuOpen(false)
            val topo = if (host.overlayH > host.overlayW) BridgeClient.LAYOUT_SPLIT_2_V else BridgeClient.LAYOUT_SPLIT_2_H
            applyTopologyChoice(topo)
        }
        btnTopo3Master = makeTopoChip(ctx, "1+2 三分屏") {
            setSplitMenuOpen(false)
            applyTopologyChoice(BridgeClient.LAYOUT_SPLIT_3_MASTER)
        }
        topoRow1.addView(btnTopo2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        })
        topoRow1.addView(btnTopo3Master, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        menuRoot.addView(
            topoRow1,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val topoRow2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btnTopo3Cols = makeTopoChip(ctx, "三列分屏") {
            setSplitMenuOpen(false)
            applyTopologyChoice(BridgeClient.LAYOUT_SPLIT_3_COLS)
        }
        btnTopoGrid2x2 = makeTopoChip(ctx, "2×2 四宫格") {
            setSplitMenuOpen(false)
            applyTopologyChoice(BridgeClient.LAYOUT_GRID_2X2)
        }
        topoRow2.addView(btnTopo3Cols, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        })
        topoRow2.addView(btnTopoGrid2x2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        menuRoot.addView(
            topoRow2,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = OverlayUiUtils.dp(ctx, 6f)
                bottomMargin = OverlayUiUtils.dp(ctx, 8f)
            },
        )

        val ratiosRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in SPLIT_PRESET_PCTS.indices) {
            val pct = SPLIT_PRESET_PCTS[i]
            val lbl = "${pct / 10}:${(100 - pct) / 10}"
            val btn = OverlayUiUtils.makeFooterChip(ctx, lbl).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                isSingleLine = true
                gravity = Gravity.CENTER
                val p = OverlayUiUtils.dp(ctx, 6f)
                setPadding(p, p, p, p)
                setOnClickListener {
                    setSplitMenuOpen(false)
                    applyPrimaryRatioPreset(pct)
                }
            }
            val bLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i < SPLIT_PRESET_PCTS.lastIndex) bLp.rightMargin = OverlayUiUtils.dp(ctx, 5f)
            ratiosRow.addView(btn, bLp)
            splitRatioBtns[i] = btn
        }
        menuRoot.addView(
            ratiosRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val actRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val aLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = OverlayUiUtils.dp(ctx, 8f) }

        val swapBtn = OverlayUiUtils.makeFooterChip(ctx, if (splitDividerPortrait) "上下互换" else "左右互换").apply {
            gravity = Gravity.CENTER
            HyperUi.setStartIcon(this, HyperUi.ICON_SWAP, 0xFFE2E8F0.toInt(), 12.5f, 4f)
            setOnClickListener {
                setSplitMenuOpen(false)
                performSplitSwapAnimated()
            }
        }
        splitSwapBtn = swapBtn
        val swLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = OverlayUiUtils.dp(ctx, 6f)
        }
        actRow.addView(swapBtn, swLp)

        val exitBtn = OverlayUiUtils.makeFooterChip(ctx, "退出分屏").apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFCA5A5.toInt())
            HyperUi.setStartIcon(this, HyperUi.ICON_CLOSE, 0xFFFCA5A5.toInt(), 12.5f, 4f)
            setOnClickListener {
                setSplitMenuOpen(false)
                performExitTilingOrSplit()
            }
        }
        actRow.addView(exitBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        menuRoot.addView(actRow, aLp)

        splitMenuLp = WindowManager.LayoutParams(
            OverlayUiUtils.dp(ctx, 312f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        menuRoot.setOnTouchListener { _, ev ->
            if (ev != null && ev.actionMasked == MotionEvent.ACTION_OUTSIDE && isSplitMenuOpen) {
                setSplitMenuOpen(false)
            }
            false
        }
    }

    private fun makeDividerPill(ctx: Context): TextView = TextView(ctx).apply {
        setTextColor(0xFFF8FAFC.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        elevation = OverlayUiUtils.dp(ctx, 12f).toFloat()
        background = GradientDrawable().apply {
            setColor(0xE00F172A.toInt())
            cornerRadius = OverlayUiUtils.dp(ctx, 12f).toFloat()
            setStroke(OverlayUiUtils.dp(ctx, 1f), 0x8838BDF8.toInt())
        }
    }

    private fun makeTopoChip(ctx: Context, label: String, onClick: () -> Unit): TextView =
        OverlayUiUtils.makeFooterChip(ctx, label).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            gravity = Gravity.CENTER
            val pH = OverlayUiUtils.dp(ctx, 8f)
            val pV = OverlayUiUtils.dp(ctx, 7f)
            setPadding(pH, pV, pH, pV)
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPrimaryDividerTouch(ctx: Context, dividerRoot: FrameLayout) {
        val dragState = FloatArray(5) // [downRawX, downRawY, startRatio, curRatio, moved]
        val touchSlop = OverlayUiUtils.dp(ctx, 6f)
        dividerRoot.setOnTouchListener { _, ev ->
            val act = ev.actionMasked
            val isVerticalSplitAxis = activeTopology == BridgeClient.LAYOUT_SPLIT_2_V ||
                (activeTopology == BridgeClient.LAYOUT_NONE && host.overlayH > host.overlayW)
            when (act) {
                MotionEvent.ACTION_DOWN -> {
                    splitDragging = true
                    dragState[0] = ev.rawX
                    dragState[1] = ev.rawY
                    dragState[2] = if (activeTopology == BridgeClient.LAYOUT_SPLIT_3_COLS) {
                        (displayedCol0Pct / 100f).coerceIn(0.20f, 0.60f)
                    } else {
                        displayedSplitRatio.coerceIn(0.25f, 0.75f)
                    }
                    dragState[3] = dragState[2]
                    dragState[4] = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragState[0]
                    val dy = ev.rawY - dragState[1]
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragState[4] = 1f
                    }
                    val axis = max(600, if (isVerticalSplitAxis) host.overlayH else host.overlayW)
                    val deltaRatio = (if (isVerticalSplitAxis) dy else dx) / axis.toFloat()
                    if (activeTopology == BridgeClient.LAYOUT_SPLIT_3_COLS) {
                        val rightBound = ((displayedCol0Pct + displayedCol1Pct - 20).coerceIn(20, 60)) / 100f
                        val r = (dragState[2] + deltaRatio).coerceIn(0.20f, max(0.20f, rightBound))
                        dragState[3] = r
                        val newC0 = (r * 100f).roundToInt().coerceIn(20, 60)
                        val oldSplit2 = displayedCol0Pct + displayedCol1Pct
                        displayedCol0Pct = newC0
                        displayedCol1Pct = (oldSplit2 - newC0).coerceIn(20, 80 - newC0)
                        positionAllDividers(draggingPrimary = true, draggingSecondary = false)
                    } else {
                        val r = (dragState[2] + deltaRatio).coerceIn(0.25f, 0.75f)
                        dragState[3] = r
                        displayedSplitRatio = r
                        positionAllDividers(draggingPrimary = true, draggingSecondary = false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    splitDragging = false
                    if (dragState[4] < 0.5f && act == MotionEvent.ACTION_UP) {
                        setSplitMenuOpen(!isSplitMenuOpen)
                    } else {
                        commitCurrentDividerRatios(fromDragRelease = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSecondaryDividerTouch(ctx: Context, secRoot: FrameLayout) {
        val dragState = FloatArray(5) // [downRawX, downRawY, startRatio, curRatio, moved]
        val touchSlop = OverlayUiUtils.dp(ctx, 6f)
        secRoot.setOnTouchListener { _, ev ->
            val act = ev.actionMasked
            val isCols = activeTopology == BridgeClient.LAYOUT_SPLIT_3_COLS
            when (act) {
                MotionEvent.ACTION_DOWN -> {
                    secondaryDragging = true
                    dragState[0] = ev.rawX
                    dragState[1] = ev.rawY
                    dragState[2] = if (isCols) {
                        ((displayedCol0Pct + displayedCol1Pct) / 100f).coerceIn(0.40f, 0.80f)
                    } else {
                        displayedSecondaryRatio.coerceIn(0.25f, 0.75f)
                    }
                    dragState[3] = dragState[2]
                    dragState[4] = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragState[0]
                    val dy = ev.rawY - dragState[1]
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragState[4] = 1f
                    }
                    val axis = max(600, if (isCols) host.overlayW else host.overlayH)
                    val deltaRatio = (if (isCols) dx else dy) / axis.toFloat()
                    if (isCols) {
                        val minSplit2 = (displayedCol0Pct + 20).coerceIn(40, 80) / 100f
                        val r = (dragState[2] + deltaRatio).coerceIn(minSplit2, 0.80f)
                        dragState[3] = r
                        val split2Pct = (r * 100f).roundToInt().coerceIn(displayedCol0Pct + 20, 80)
                        displayedCol1Pct = (split2Pct - displayedCol0Pct).coerceAtLeast(20)
                        positionAllDividers(draggingPrimary = false, draggingSecondary = true)
                    } else {
                        val r = (dragState[2] + deltaRatio).coerceIn(0.25f, 0.75f)
                        dragState[3] = r
                        displayedSecondaryRatio = r
                        positionAllDividers(draggingPrimary = false, draggingSecondary = true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    secondaryDragging = false
                    if (dragState[4] < 0.5f && act == MotionEvent.ACTION_UP) {
                        setSplitMenuOpen(!isSplitMenuOpen)
                    } else {
                        commitCurrentDividerRatios(fromDragRelease = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun resolveActiveTopology(st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot): String {
        if (BridgeClient.isTilingTopology(snap.tilingTopology)) {
            return snap.tilingTopology.uppercase()
        }
        if (BridgeClient.isTilingTopology(st.activeLayoutMode)) {
            return st.activeLayoutMode.uppercase()
        }
        if (st.splitActive == 1) {
            return if (host.overlayH > host.overlayW) BridgeClient.LAYOUT_SPLIT_2_V else BridgeClient.LAYOUT_SPLIT_2_H
        }
        return BridgeClient.LAYOUT_NONE
    }

    private fun isMultiAppTopology(topo: String): Boolean =
        topo == BridgeClient.LAYOUT_SPLIT_3_MASTER ||
            topo == BridgeClient.LAYOUT_SPLIT_3_COLS ||
            topo == BridgeClient.LAYOUT_GRID_2X2

    private fun currentTilingPkgsList(): List<String> {
        val out = LinkedHashSet<String>()
        for (w in host.lastWindowSnapshot.windows) {
            if (!w.minimized && w.pkg.isNotBlank() && w.pkg != "com.extbridge.helper" && w.pkg != "android") {
                out.add(w.pkg)
            }
        }
        val st = host.lastStatus
        if (st.splitSlot0Pkg.isNotBlank() && st.splitSlot0Pkg != "com.extbridge.helper") out.add(st.splitSlot0Pkg)
        if (st.splitSlot1Pkg.isNotBlank() && st.splitSlot1Pkg != "com.extbridge.helper") out.add(st.splitSlot1Pkg)
        return out.toList()
    }

    private fun applyTopologyChoice(topology: String) {
        val slotLimit = when (topology) {
            BridgeClient.LAYOUT_GRID_2X2 -> 4
            BridgeClient.LAYOUT_SPLIT_3_MASTER, BridgeClient.LAYOUT_SPLIT_3_COLS -> 3
            else -> 2
        }
        val defaultRatios = when (topology) {
            BridgeClient.LAYOUT_SPLIT_3_COLS -> listOf(33, 34, 33)
            else -> listOf(50, 50)
        }
        host.safeBgExecute {
            val pkgs = LinkedHashSet<String>()
            pkgs.addAll(currentTilingPkgsList())
            for (t in BridgeClient.getTasks()) {
                if (t.pkg.isNotBlank() && t.pkg != "com.extbridge.helper" && t.pkg != "android") {
                    pkgs.add(t.pkg)
                }
            }
            val chosen = pkgs.take(slotLimit)
            val ok = BridgeClient.setTilingLayout(topology, chosen, defaultRatios)
            host.safePost {
                if (ok) {
                    Toast.makeText(
                        host.serviceContext,
                        "已切换：${BridgeClient.layoutModeLabel(topology)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (slotLimit == 2) {
                    performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_TOGGLE_QUICK, -1, 0, null, null)
                    return@safePost
                } else {
                    Toast.makeText(
                        host.serviceContext,
                        "请先打开应用，或在桌面长按图标选择「加入 2/3/4 分屏」",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                host.pollRemoteStateAsync()
            }
        }
    }

    private fun applyPrimaryRatioPreset(pct: Int) {
        val clamped = pct.coerceIn(25, 75)
        if (activeTopology == BridgeClient.LAYOUT_SPLIT_3_COLS) {
            val c0 = clamped.coerceIn(20, 60)
            val rem = 100 - c0
            val c1 = (rem / 2).coerceIn(20, 80 - c0)
            displayedCol0Pct = c0
            displayedCol1Pct = c1
            positionAllDividers(draggingPrimary = false, draggingSecondary = false)
            commitCurrentDividerRatios(fromDragRelease = false)
        } else if (isMultiAppTopology(activeTopology)) {
            displayedSplitRatio = clamped / 100f
            positionAllDividers(draggingPrimary = false, draggingSecondary = false)
            commitCurrentDividerRatios(fromDragRelease = false)
        } else {
            performSplitRatioAnimated(clamped, false)
        }
    }

    private fun commitCurrentDividerRatios(fromDragRelease: Boolean) {
        val topo = activeTopology
        if (topo == BridgeClient.LAYOUT_SPLIT_3_COLS) {
            val c0 = displayedCol0Pct.coerceIn(20, 60)
            val c1 = displayedCol1Pct.coerceIn(20, 80 - c0)
            val c2 = (100 - c0 - c1).coerceAtLeast(20)
            val pkgs = currentTilingPkgsList()
            host.safeBgExecute {
                BridgeClient.setTilingLayout(topo, pkgs, listOf(c0, c1, c2))
                host.safePost {
                    Toast.makeText(host.serviceContext, "三列比例：$c0% : $c1% : $c2%", Toast.LENGTH_SHORT).show()
                    host.pollRemoteStateAsync()
                }
            }
            return
        }
        if (topo == BridgeClient.LAYOUT_SPLIT_3_MASTER || topo == BridgeClient.LAYOUT_GRID_2X2) {
            val p0 = (displayedSplitRatio * 100f).roundToInt().coerceIn(25, 75)
            val p1 = (displayedSecondaryRatio * 100f).roundToInt().coerceIn(25, 75)
            val pkgs = currentTilingPkgsList()
            host.safeBgExecute {
                BridgeClient.setTilingLayout(topo, pkgs, listOf(p0, p1))
                host.safePost {
                    Toast.makeText(
                        host.serviceContext,
                        "${BridgeClient.layoutModeLabel(topo)}比例：$p0% / $p1%",
                        Toast.LENGTH_SHORT,
                    ).show()
                    host.pollRemoteStateAsync()
                }
            }
            return
        }
        val pct = (displayedSplitRatio * 100f).roundToInt().coerceIn(25, 75)
        if (topo == BridgeClient.LAYOUT_SPLIT_2_H || topo == BridgeClient.LAYOUT_SPLIT_2_V) {
            val pkgs = currentTilingPkgsList()
            host.safeBgExecute {
                val ok = BridgeClient.setTilingLayout(topo, pkgs, listOf(pct, 100 - pct))
                if (!ok) BridgeClient.setSplitRatio(pct)
                host.safePost {
                    Toast.makeText(host.serviceContext, "分屏比例：$pct% : ${100 - pct}%", Toast.LENGTH_SHORT).show()
                    host.pollRemoteStateAsync()
                }
            }
        } else {
            performSplitRatioAnimated(pct, fromDragRelease)
        }
    }

    private fun performExitTilingOrSplit() {
        if (isMultiAppTopology(activeTopology)) {
            host.safeBgExecute {
                BridgeClient.setTilingLayout(BridgeClient.LAYOUT_EXIT, "", "")
                BridgeClient.exitSplitMode()
                host.safePost {
                    Toast.makeText(host.serviceContext, "已退出多应用分屏", Toast.LENGTH_SHORT).show()
                    host.pollRemoteStateAsync()
                }
            }
        } else {
            performExitSplitAnimated()
        }
    }

    fun triggerQuickSplitOrMenu() {
        host.setPanelOpen(false)
        if (host.lastStatus.isMultiTilingActive || BridgeClient.isTilingTopology(activeTopology)) {
            setSplitMenuOpen(!isSplitMenuOpen)
            return
        }
        performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_TOGGLE_QUICK, -1, 0, null, null)
    }

    fun setSplitMenuOpen(open: Boolean) {
        val st = host.lastStatus
        val canOpen = st.connected && st.vdId > 0 && st.mirrorMode != 1
        val wantOpen = open && canOpen
        val menu = splitMenuRoot
        if (isSplitMenuOpen == wantOpen && (menu == null || (wantOpen && menu.visibility == View.VISIBLE))) {
            return
        }
        isSplitMenuOpen = wantOpen
        if (wantOpen) host.setTaskBubbleOpen(false)
        val ctx = host.extCtx
        if (menu != null && ctx != null) {
            menu.animate().cancel()
            if (wantOpen) {
                menu.visibility = View.VISIBLE
                repositionSplitMenu()
                host.cancelAutoHideTimer()
                menu.alpha = 0f
                menu.translationY = OverlayUiUtils.dp(ctx, 10f).toFloat()
                menu.scaleX = 0.92f
                menu.scaleY = 0.92f
                menu.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .withEndAction { host.bringCursorToFront() }
                    .start()
            } else {
                host.evaluateDockAutoHide(false)
                if (menu.visibility == View.VISIBLE) {
                    menu.animate()
                        .alpha(0f)
                        .translationY(OverlayUiUtils.dp(ctx, 8f).toFloat())
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .setDuration(135L)
                        .setInterpolator(AccelerateInterpolator(1.3f))
                        .withEndAction {
                            if (!isSplitMenuOpen) {
                                splitMenuRoot?.let {
                                    it.visibility = View.GONE
                                    it.alpha = 1f
                                    it.translationY = 0f
                                    it.scaleX = 1f
                                    it.scaleY = 1f
                                }
                            }
                        }
                        .start()
                } else {
                    menu.visibility = View.GONE
                }
            }
        }
        host.bringCursorToFront()
    }

    fun updateSplitUi(st: BridgeClient.Status) {
        val snap = host.lastWindowSnapshot
        activeTopology = resolveActiveTopology(st, snap)
        val active = st.connected && st.vdId > 0 && st.mirrorMode != 1 &&
            (st.splitActive == 1 || BridgeClient.isTilingTopology(activeTopology))
        host.syncOverlayHostSurface(false)
        host.updateDockSplitButton(active)
        host.updatePanelSplitButton(active)

        val is2Split = activeTopology == BridgeClient.LAYOUT_SPLIT_2_H || activeTopology == BridgeClient.LAYOUT_SPLIT_2_V
        HyperUi.styleHyperChip(btnTopo2, is2Split)
        HyperUi.styleHyperChip(btnTopo3Master, activeTopology == BridgeClient.LAYOUT_SPLIT_3_MASTER)
        HyperUi.styleHyperChip(btnTopo3Cols, activeTopology == BridgeClient.LAYOUT_SPLIT_3_COLS)
        HyperUi.styleHyperChip(btnTopoGrid2x2, activeTopology == BridgeClient.LAYOUT_GRID_2X2)

        splitSwapBtn?.let {
            it.text = if (activeTopology == BridgeClient.LAYOUT_SPLIT_2_V || host.overlayH > host.overlayW) "上下互换" else "左右互换"
            HyperUi.setStartIcon(it, HyperUi.ICON_SWAP, 0xFFE2E8F0.toInt(), 12.5f, 4f)
        }

        // Sync ratios from daemon snapshot / status when not actively dragging
        if (!splitDragging && !secondaryDragging && (splitDividerAnimator == null || !splitDividerAnimator!!.isRunning)) {
            val norm = BridgeClient.normalizeTilingRatios(
                activeTopology,
                if (snap.ratios.isNotEmpty()) snap.ratios else listOf(if (st.splitRatioPct > 0) st.splitRatioPct else 50),
            )
            when (activeTopology) {
                BridgeClient.LAYOUT_SPLIT_3_COLS -> {
                    displayedCol0Pct = norm[0]
                    displayedCol1Pct = norm[1]
                    displayedSplitRatio = displayedCol0Pct / 100f
                }
                BridgeClient.LAYOUT_SPLIT_3_MASTER, BridgeClient.LAYOUT_GRID_2X2 -> {
                    displayedSplitRatio = norm[0] / 100f
                    displayedSecondaryRatio = norm[1] / 100f
                }
                else -> {
                    val curPct = if (snap.ratios.isNotEmpty()) norm[0] else if (st.splitRatioPct > 0) st.splitRatioPct else 50
                    displayedSplitRatio = curPct.coerceIn(25, 75) / 100f
                }
            }
        }

        val curPct = (displayedSplitRatio * 100f).roundToInt()
        for (i in SPLIT_PRESET_PCTS.indices) {
            val tv = splitRatioBtns[i] ?: continue
            val sel = active && abs(curPct - SPLIT_PRESET_PCTS[i]) <= 3
            HyperUi.styleHyperChip(tv, sel)
        }

        val singleEmpty = st.splitSlot0Pkg.isEmpty() != st.splitSlot1Pkg.isEmpty()
        val showPrimary = active && (!host.isOnLauncherHome() || singleEmpty || isMultiAppTopology(activeTopology))
        val showSecondary = showPrimary && isMultiAppTopology(activeTopology)

        updateDividerVisibility(splitDividerRoot, showPrimary)
        updateDividerVisibility(secondaryDividerRoot, showSecondary)

        if (showPrimary) {
            positionAllDividers(draggingPrimary = splitDragging, draggingSecondary = secondaryDragging)
        }
        host.bringCursorToFront()
    }

    private fun updateDividerVisibility(divider: FrameLayout?, show: Boolean) {
        if (divider == null) return
        if (show) {
            if (divider.visibility != View.VISIBLE) {
                divider.animate().cancel()
                divider.visibility = View.VISIBLE
                divider.alpha = 0f
                divider.scaleX = 0.55f
                divider.scaleY = 0.55f
                divider.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        } else if (divider.visibility == View.VISIBLE) {
            divider.animate().cancel()
            divider.visibility = View.GONE
            divider.alpha = 1f
            divider.scaleX = 1f
            divider.scaleY = 1f
        }
    }

    fun repositionSplitDivider() {
        positionAllDividers(draggingPrimary = splitDragging, draggingSecondary = secondaryDragging)
    }

    private fun positionAllDividers(draggingPrimary: Boolean, draggingSecondary: Boolean) {
        val ctx = host.extCtx ?: return
        var surfW = max(600, host.overlayW)
        var surfH = max(600, host.overlayH)
        val lHost = host.launcherHost
        if (host.inLauncherHost && lHost != null && lHost.width > 0 && lHost.height > 0) {
            surfW = lHost.width
            surfH = lHost.height
        }

        val vW = OverlayUiUtils.dp(ctx, 26f)
        val vH = OverlayUiUtils.dp(ctx, 80f)
        val hW = OverlayUiUtils.dp(ctx, 80f)
        val hH = OverlayUiUtils.dp(ctx, 26f)

        when (activeTopology) {
            BridgeClient.LAYOUT_SPLIT_3_MASTER -> {
                val r0 = displayedSplitRatio.coerceIn(0.25f, 0.75f)
                val r1 = displayedSecondaryRatio.coerceIn(0.25f, 0.75f)
                val divX = (surfW * r0).roundToInt()
                val divY = (surfH * r1).roundToInt()
                val p0 = (r0 * 100f).roundToInt()
                val p1 = (r1 * 100f).roundToInt()
                applyDividerLayout(
                    splitDividerRoot, splitDividerLp, splitDividerPill, ctx,
                    x = (divX - vW / 2).coerceIn(0, surfW - vW),
                    y = (surfH - vH) / 2,
                    w = vW, h = vH,
                    label = "⋮\n$p0",
                    dragging = draggingPrimary,
                )
                val rightCenterX = divX + (surfW - divX) / 2
                applyDividerLayout(
                    secondaryDividerRoot, secondaryDividerLp, secondaryDividerPill, ctx,
                    x = (rightCenterX - hW / 2).coerceIn(divX, max(divX, surfW - hW)),
                    y = (divY - hH / 2).coerceIn(0, surfH - hH),
                    w = hW, h = hH,
                    label = "⋯ $p1%",
                    dragging = draggingSecondary,
                )
            }
            BridgeClient.LAYOUT_SPLIT_3_COLS -> {
                val c0 = displayedCol0Pct.coerceIn(20, 60)
                val c1 = displayedCol1Pct.coerceIn(20, 80 - c0)
                val c2 = (100 - c0 - c1).coerceAtLeast(20)
                val x1 = (surfW * (c0 / 100f)).roundToInt()
                val x2 = (surfW * ((c0 + c1) / 100f)).roundToInt()
                applyDividerLayout(
                    splitDividerRoot, splitDividerLp, splitDividerPill, ctx,
                    x = (x1 - vW / 2).coerceIn(0, surfW - vW),
                    y = (surfH - vH) / 2,
                    w = vW, h = vH,
                    label = "⋮\n$c0",
                    dragging = draggingPrimary,
                )
                applyDividerLayout(
                    secondaryDividerRoot, secondaryDividerLp, secondaryDividerPill, ctx,
                    x = (x2 - vW / 2).coerceIn(0, surfW - vW),
                    y = (surfH - vH) / 2,
                    w = vW, h = vH,
                    label = "⋮\n$c2",
                    dragging = draggingSecondary,
                )
            }
            BridgeClient.LAYOUT_GRID_2X2 -> {
                val rx = displayedSplitRatio.coerceIn(0.25f, 0.75f)
                val ry = displayedSecondaryRatio.coerceIn(0.25f, 0.75f)
                val divX = (surfW * rx).roundToInt()
                val divY = (surfH * ry).roundToInt()
                val px = (rx * 100f).roundToInt()
                val py = (ry * 100f).roundToInt()
                // Offset pills cleanly into top-vertical and left-horizontal arms so they never overlap
                applyDividerLayout(
                    splitDividerRoot, splitDividerLp, splitDividerPill, ctx,
                    x = (divX - vW / 2).coerceIn(0, surfW - vW),
                    y = ((divY / 2) - vH / 2).coerceIn(0, surfH - vH),
                    w = vW, h = vH,
                    label = "⋮\n$px",
                    dragging = draggingPrimary,
                )
                applyDividerLayout(
                    secondaryDividerRoot, secondaryDividerLp, secondaryDividerPill, ctx,
                    x = ((divX / 2) - hW / 2).coerceIn(0, surfW - hW),
                    y = (divY - hH / 2).coerceIn(0, surfH - hH),
                    w = hW, h = hH,
                    label = "⋯ $py%",
                    dragging = draggingSecondary,
                )
            }
            else -> {
                val port = activeTopology == BridgeClient.LAYOUT_SPLIT_2_V ||
                    (activeTopology != BridgeClient.LAYOUT_SPLIT_2_H && surfH > surfW)
                val w = if (port) hW else vW
                val h = if (port) hH else vH
                val r = displayedSplitRatio.coerceIn(0.25f, 0.75f)
                val pct = (r * 100f).roundToInt()
                val x = if (port) (surfW - w) / 2 else ((surfW * r).roundToInt() - w / 2).coerceIn(0, surfW - w)
                val y = if (port) ((surfH * r).roundToInt() - h / 2).coerceIn(0, surfH - h) else (surfH - h) / 2
                splitDividerPortrait = port
                applyDividerLayout(
                    splitDividerRoot, splitDividerLp, splitDividerPill, ctx,
                    x = x, y = y, w = w, h = h,
                    label = if (port) "⋯ $pct%" else "⋮\n$pct",
                    dragging = draggingPrimary,
                )
            }
        }
    }

    private fun applyDividerLayout(
        divider: FrameLayout?,
        lp: WindowManager.LayoutParams?,
        pill: TextView?,
        ctx: Context,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String,
        dragging: Boolean,
    ) {
        if (divider == null || lp == null) return
        pill?.let { p ->
            p.text = label
            val bg = p.background as? GradientDrawable
            bg?.setStroke(
                OverlayUiUtils.dp(ctx, if (dragging) 2f else 1f),
                if (dragging) 0xFF38BDF8.toInt() else 0x8838BDF8.toInt(),
            )
            bg?.setColor(if (dragging) 0xF50284C7.toInt() else 0xE00F172A.toInt())
        }
        lp.width = w
        lp.height = h
        lp.x = x
        lp.y = y
        if (host.inLauncherHost) {
            val flp = divider.layoutParams as? FrameLayout.LayoutParams
            if (flp != null) {
                flp.gravity = Gravity.TOP or Gravity.LEFT
                flp.width = w
                flp.height = h
                flp.leftMargin = x
                flp.topMargin = y
                divider.layoutParams = flp
            }
        } else {
            try { host.extWm?.updateViewLayout(divider, lp) } catch (_: Throwable) {}
        }
    }

    private fun positionSplitDividerAt(ratio: Float, dragging: Boolean) {
        displayedSplitRatio = ratio.coerceIn(0.25f, 0.75f)
        positionAllDividers(draggingPrimary = dragging, draggingSecondary = false)
    }

    fun repositionSplitMenu() {
        val menu = splitMenuRoot ?: return
        val lp = splitMenuLp ?: return
        val ctx = host.extCtx ?: return
        var surfW = max(600, host.overlayW)
        var surfH = max(600, host.overlayH)
        val lHost = host.launcherHost
        if (host.inLauncherHost && lHost != null && lHost.width > 0 && lHost.height > 0) {
            surfW = lHost.width
            surfH = lHost.height
        }
        val fixedMenuW = OverlayUiUtils.dp(ctx, 312f)
        menu.measure(
            View.MeasureSpec.makeMeasureSpec(fixedMenuW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST),
        )
        val mw = fixedMenuW
        val mh = max(OverlayUiUtils.dp(ctx, 160f), menu.measuredHeight)
        val margin = OverlayUiUtils.dp(ctx, 12f)
        val isActive = BridgeClient.isTilingTopology(activeTopology) || host.lastStatus.splitActive == 1
        val x: Int
        val y: Int
        if (!isActive) {
            x = ((surfW - mw) / 2).coerceIn(margin, max(margin, surfW - mw - margin))
            y = (surfH - mh - OverlayUiUtils.dp(ctx, 84f)).coerceIn(margin, max(margin, surfH - mh - margin))
        } else {
            val r = displayedSplitRatio.coerceIn(0.25f, 0.75f)
            val port = activeTopology == BridgeClient.LAYOUT_SPLIT_2_V ||
                (activeTopology == BridgeClient.LAYOUT_NONE && surfH > surfW)
            x = if (port) {
                (surfW - mw) / 2
            } else {
                ((surfW * r).roundToInt() - mw / 2).coerceIn(margin, max(margin, surfW - mw - margin))
            }
            y = if (port) {
                ((surfH * r).roundToInt() - mh - OverlayUiUtils.dp(ctx, 22f))
                    .coerceIn(margin, max(margin, surfH - mh - OverlayUiUtils.dp(ctx, 80f)))
            } else {
                max(margin, (surfH - mh) / 2 - OverlayUiUtils.dp(ctx, 90f))
            }
        }
        lp.x = x
        lp.y = y
        if (host.inLauncherHost) {
            val flp = menu.layoutParams as? FrameLayout.LayoutParams
            if (flp != null) {
                flp.gravity = Gravity.TOP or Gravity.LEFT
                flp.leftMargin = x
                flp.topMargin = y
                menu.layoutParams = flp
            }
        } else {
            try { host.extWm?.updateViewLayout(menu, lp) } catch (_: Throwable) {}
        }
    }

    private fun animateSplitDividerTo(targetRatio: Float, durationMs: Long) {
        splitDividerAnimator?.cancel()
        splitDividerAnimator = null
        val startRatio = displayedSplitRatio
        val clamped = targetRatio.coerceIn(0.25f, 0.75f)
        if (abs(clamped - startRatio) < 0.005f || splitDividerRoot == null) {
            positionSplitDividerAt(clamped, false)
            return
        }
        val va = ValueAnimator.ofFloat(startRatio, clamped).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.20f, 0.96f, 0.28f, 1.0f)
            addUpdateListener { anim -> positionSplitDividerAt(anim.animatedValue as Float, false) }
        }
        splitDividerAnimator = va
        va.start()
    }

    fun performEnterOrAssignSplitAnimated(
        action: Int,
        slotHint: Int,
        taskId: Int,
        pkg: String?,
        cls: String?,
    ) {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.safeBgExecute {
                val sr = BridgeClient.splitActionDetailed(action, taskId, pkg, cls)
                host.safePost {
                    if (action == BridgeClient.SPLIT_TOGGLE_QUICK && !sr.ok) {
                        Toast.makeText(
                            host.serviceContext,
                            "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    host.pollRemoteStateAsync()
                }
            }
            return
        }

        val dp16 = OverlayUiUtils.dp(extCtx, 16f)
        val dp8 = OverlayUiUtils.dp(extCtx, 8f)
        val portrait = host.overlayH > host.overlayW
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val preSt = host.lastStatus
        val wasSplit = preSt.splitActive == 1
        val curRatio = if (preSt.splitRatioPct > 0) preSt.splitRatioPct / 100f else 0.50f
        val preSlot0 = preSt.splitSlot0Pkg
        val preSlot1 = preSt.splitSlot1Pkg

        host.safeBgExecute {
            var topVisiblePkg = ""
            var topVisibleTid = -1
            var secondCandPkg = ""
            var secondCandTid = -1
            val preTasks = BridgeClient.getTasks()
            for (te in preTasks) {
                if ("com.extbridge.helper" == te.pkg || "android" == te.pkg) continue
                if (topVisiblePkg.isEmpty() && te.isTopExt()) {
                    topVisiblePkg = te.pkg
                    topVisibleTid = te.taskId
                } else if (secondCandPkg.isEmpty()) {
                    secondCandPkg = te.pkg
                    secondCandTid = te.taskId
                }
            }
            if (topVisiblePkg.isEmpty()) {
                for (te in preTasks) {
                    if ("com.extbridge.helper" == te.pkg || "android" == te.pkg) continue
                    topVisiblePkg = te.pkg
                    topVisibleTid = te.taskId
                    break
                }
            }

            if (action == BridgeClient.SPLIT_TOGGLE_QUICK && topVisiblePkg.isEmpty() && secondCandPkg.isEmpty()) {
                host.safePost {
                    Toast.makeText(
                        host.serviceContext,
                        "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@safeBgExecute
            }

            val pred0: String
            val pred1: String
            when (action) {
                BridgeClient.SPLIT_TOGGLE_QUICK -> {
                    pred0 = topVisiblePkg
                    pred1 = secondCandPkg
                }
                BridgeClient.SPLIT_ASSIGN_LEFT_TOP -> {
                    pred0 = pkg ?: ""
                    pred1 = if (wasSplit && pred0 != preSlot1) preSlot1 else ""
                }
                else -> {
                    pred1 = pkg ?: ""
                    pred0 = if (wasSplit && pred1 != preSlot0) preSlot0 else ""
                }
            }

            var snap0: BridgeClient.TaskSnapshotData? = null
            if (pred0.isNotEmpty()) {
                val isLive0 = pred0 == topVisiblePkg || (wasSplit && pred0 == preSlot0)
                snap0 = BridgeClient.captureTaskSnapshot(if (pred0 == topVisiblePkg) topVisibleTid else -1, pred0, isLive0)
            }
            var snap1: BridgeClient.TaskSnapshotData? = null
            if (pred1.isNotEmpty()) {
                val isLive1 = pred1 == topVisiblePkg || (wasSplit && pred1 == preSlot1)
                snap1 = BridgeClient.captureTaskSnapshot(
                    if (pred1 == topVisiblePkg) topVisibleTid else secondCandTid,
                    pred1,
                    isLive1,
                )
            }

            val targetRatio = curRatio.coerceIn(0.25f, 0.75f)
            val end0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, targetRatio)
            val end1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, targetRatio)

            val start0 = when {
                wasSplit && pred0 == preSlot0 -> OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, curRatio)
                pred0.isNotEmpty() && pred0 == topVisiblePkg -> fullRect
                else -> offsetSlideInRect(end0, portrait, true)
            }
            val start1 = when {
                wasSplit && pred1 == preSlot1 -> OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, curRatio)
                pred1.isNotEmpty() && pred1 == topVisiblePkg -> fullRect
                else -> offsetSlideInRect(end1, portrait, false)
            }

            val c0 = host.buildCardSpec(pred0, snap0, start0, end0).apply {
                startCornerPx = if (start0 == fullRect) 0f else dp16.toFloat()
                endCornerPx = dp8.toFloat()
                startAlpha = if (start0 == fullRect || (wasSplit && pred0 == preSlot0)) 1f else 0.30f
            }
            val c1 = host.buildCardSpec(pred1, snap1, start1, end1).apply {
                startCornerPx = if (start1 == fullRect) 0f else dp16.toFloat()
                endCornerPx = dp8.toFloat()
                startAlpha = if (start1 == fullRect || (wasSplit && pred1 == preSlot1)) 1f else 0.30f
            }

            val tokenBox = intArrayOf(-1)
            val frame0Latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(c0)
                    OverlayUiUtils.recycleUnusedCard(c1)
                    frame0Latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playSplitTransition(c0, c1, portrait, 285L, false) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                tokenBox[0] = anim.getActiveToken()
                frame0Latch.countDown()
            }
            try { frame0Latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            val sr = BridgeClient.splitActionDetailed(action, taskId, pkg, cls)
            val expectedToken = tokenBox[0]
            host.safePost {
                if (sr.ok && action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                    Toast.makeText(
                        host.serviceContext,
                        if (portrait) "已开启上下分屏（拖动分割条可调比例）" else "已开启左右分屏（拖动分割条可调比例）",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (!sr.ok && action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                    Toast.makeText(
                        host.serviceContext,
                        "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                host.pollRemoteStateAsync()
                host.handler.postDelayed({
                    val animNow = host.stageAnimView
                    if (animNow != null && expectedToken > 0) animNow.notifyBackendReady(expectedToken)
                }, 130L)
            }
        }
    }

    private fun offsetSlideInRect(target: Rect, portrait: Boolean, firstSlot: Boolean): Rect {
        val extCtx = host.extCtx
        val shift = if (extCtx != null) OverlayUiUtils.dp(extCtx, 56f) else 72
        val insetW = (target.width() * 0.04f).roundToInt()
        val insetH = (target.height() * 0.04f).roundToInt()
        val r = Rect(
            target.left + insetW,
            target.top + insetH,
            target.right - insetW,
            target.bottom - insetH,
        )
        if (!portrait) {
            r.offset(if (firstSlot) -shift else shift, 0)
        } else {
            r.offset(0, if (firstSlot) -shift else shift)
        }
        return r
    }

    private fun performSplitRatioAnimated(pct: Int, fromDragRelease: Boolean) {
        val clampedPct = pct.coerceIn(25, 75)
        val oldRatio = displayedSplitRatio
        val newRatio = clampedPct / 100f
        host.lastStatus.splitRatioPct = clampedPct

        val dur = if (fromDragRelease) 180L else 240L
        animateSplitDividerTo(newRatio, dur)

        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null || abs(newRatio - oldRatio) < 0.015f || fromDragRelease) {
            host.safeBgExecute {
                BridgeClient.setSplitRatio(clampedPct)
                host.safePost {
                    Toast.makeText(
                        host.serviceContext,
                        "分屏比例：$clampedPct% : ${100 - clampedPct}%",
                        Toast.LENGTH_SHORT,
                    ).show()
                    host.pollRemoteStateAsync()
                }
            }
            return
        }

        val dp8 = OverlayUiUtils.dp(extCtx, 8f)
        val dp6 = OverlayUiUtils.dp(extCtx, 6f)
        val p0 = host.lastStatus.splitSlot0Pkg
        val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW
        val old0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, oldRatio)
        val old1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, oldRatio)
        val new0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, newRatio)
        val new1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, newRatio)

        host.safeBgExecute {
            val s0 = if (p0.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p0, true) else null
            val s1 = if (p1.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p1, true) else null

            val c0 = host.buildCardSpec(p0, s0, old0, new0).apply {
                startCornerPx = dp8.toFloat()
                endCornerPx = dp6.toFloat()
            }
            val c1 = host.buildCardSpec(p1, s1, old1, new1).apply {
                startCornerPx = dp8.toFloat()
                endCornerPx = dp6.toFloat()
            }

            val tokenBox = intArrayOf(-1)
            val latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(c0)
                    OverlayUiUtils.recycleUnusedCard(c1)
                    latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playSplitTransition(c0, c1, portrait, dur, false) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                tokenBox[0] = anim.getActiveToken()
                latch.countDown()
            }
            try { latch.await(40L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            BridgeClient.setSplitRatio(clampedPct)
            val animNow = host.stageAnimView
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0])
            host.safePost {
                Toast.makeText(
                    host.serviceContext,
                    "分屏比例：$clampedPct% : ${100 - clampedPct}%",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun performSplitSwapAnimated() {
        val extCtx = host.extCtx
        if (host.stageAnimView == null || extCtx == null) {
            host.safeBgExecute {
                BridgeClient.swapSplitSlots()
                host.safePost { host.pollRemoteStateAsync() }
            }
            return
        }
        val dp8 = OverlayUiUtils.dp(extCtx, 8f)
        val p0 = host.lastStatus.splitSlot0Pkg
        val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW
        val ratio = displayedSplitRatio
        val r0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, ratio)
        val r1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, ratio)

        splitDividerPill?.let { pill ->
            pill.animate().cancel()
            pill.animate()
                .scaleX(1.16f).scaleY(1.16f).setDuration(130L)
                .withEndAction {
                    splitDividerPill?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(170L)
                        ?.setInterpolator(OvershootInterpolator(1.6f))?.start()
                }.start()
        }

        host.safeBgExecute {
            val s0 = if (p0.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p0, true) else null
            val s1 = if (p1.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p1, true) else null

            val cOver = host.buildCardSpec(p0, s0, r0, r1).apply {
                mode = StageTransitionView.MODE_SWAP_OVER
                startCornerPx = dp8.toFloat()
                endCornerPx = dp8.toFloat()
            }
            val cUnder = host.buildCardSpec(p1, s1, r1, r0).apply {
                mode = StageTransitionView.MODE_SWAP_UNDER
                startCornerPx = dp8.toFloat()
                endCornerPx = dp8.toFloat()
            }

            val tokenBox = intArrayOf(-1)
            val latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(cOver)
                    OverlayUiUtils.recycleUnusedCard(cUnder)
                    latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playSplitTransition(cOver, cUnder, portrait, 300L, true) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                tokenBox[0] = anim.getActiveToken()
                latch.countDown()
            }
            try { latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            BridgeClient.swapSplitSlots()
            val animNow = host.stageAnimView
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0])
        }
    }

    fun performExitSplitAnimated() {
        if (isMultiAppTopology(activeTopology) || host.stageAnimView == null || host.extCtx == null) {
            host.safeBgExecute {
                BridgeClient.setTilingLayout(BridgeClient.LAYOUT_EXIT, "", "")
                BridgeClient.exitSplitMode()
                host.safePost {
                    Toast.makeText(host.serviceContext, "已退出分屏", Toast.LENGTH_SHORT).show()
                    host.pollRemoteStateAsync()
                }
            }
            return
        }
        val p0 = host.lastStatus.splitSlot0Pkg
        val p1 = host.lastStatus.splitSlot1Pkg
        val portrait = host.overlayH > host.overlayW
        val ratio = displayedSplitRatio
        val r0 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 0, ratio)
        val r1 = OverlayUiUtils.computeLocalSplitRect(host.overlayW, host.overlayH, 1, ratio)
        val fullRect = OverlayUiUtils.getFullStageRect(host.overlayW, host.overlayH)
        val expandSlot0 = p0.isNotEmpty() || p1.isEmpty()

        splitDividerRoot?.let { div ->
            if (div.visibility == View.VISIBLE) {
                div.animate().cancel()
                div.animate()
                    .alpha(0f).scaleX(0.55f).scaleY(0.55f).setDuration(150L)
                    .withEndAction {
                        splitDividerRoot?.let {
                            it.visibility = View.GONE
                            it.alpha = 1f
                            it.scaleX = 1f
                            it.scaleY = 1f
                        }
                    }.start()
            }
        }

        host.safeBgExecute {
            val s0 = if (p0.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p0, true) else null
            val s1 = if (p1.isNotEmpty()) BridgeClient.captureTaskSnapshot(-1, p1, true) else null

            val expCard: StageTransitionView.CardSpec
            val recCard: StageTransitionView.CardSpec?
            if (expandSlot0) {
                expCard = host.buildCardSpec(p0, s0, r0, fullRect)
                val recEnd = offsetSlideInRect(r1, portrait, false)
                recCard = if (!TextUtils.isEmpty(p1)) host.buildCardSpec(p1, s1, r1, recEnd) else null
            } else {
                expCard = host.buildCardSpec(p1, s1, r1, fullRect)
                val recEnd = offsetSlideInRect(r0, portrait, true)
                recCard = if (!TextUtils.isEmpty(p0)) host.buildCardSpec(p0, s0, r0, recEnd) else null
            }

            val tokenBox = intArrayOf(-1)
            val latch = CountDownLatch(1)
            host.safePost {
                val anim = host.stageAnimView
                if (anim == null || !anim.isAttachedToWindow || host.extCtx == null) {
                    OverlayUiUtils.recycleUnusedCard(expCard)
                    OverlayUiUtils.recycleUnusedCard(recCard)
                    latch.countDown()
                    return@safePost
                }
                host.beginWindowOverlayStage()
                anim.playSplitExit(expCard, recCard) {
                    host.endWindowOverlayStage()
                    host.pollRemoteStateAsync()
                }
                tokenBox[0] = anim.getActiveToken()
                latch.countDown()
            }
            try { latch.await(45L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}

            BridgeClient.exitSplitMode()
            val animNow = host.stageAnimView
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0])
            host.safePost {
                Toast.makeText(host.serviceContext, "已退出分屏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun detach() {
        splitDividerAnimator?.cancel()
        splitDividerAnimator = null
        host.removeOverlayView(splitDividerRoot)
        host.removeOverlayView(secondaryDividerRoot)
        host.removeOverlayView(splitMenuRoot)
        splitDividerRoot = null
        splitDividerPill = null
        splitDividerLp = null
        secondaryDividerRoot = null
        secondaryDividerPill = null
        secondaryDividerLp = null
        splitDragging = false
        secondaryDragging = false
        activeTopology = BridgeClient.LAYOUT_NONE
        splitMenuRoot = null
        splitMenuLp = null
        isSplitMenuOpen = false
        splitSwapBtn = null
        btnTopo2 = null
        btnTopo3Master = null
        btnTopo3Cols = null
        btnTopoGrid2x2 = null
        splitRatioBtns.fill(null)
    }
}
