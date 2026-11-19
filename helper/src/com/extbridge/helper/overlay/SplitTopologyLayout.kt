package com.extbridge.helper.overlay

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.extbridge.helper.BridgeClient
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class DividerAxis { PRIMARY, SECONDARY }

internal data class DividerPlacement(val x: Int, val y: Int, val width: Int, val height: Int, val label: String)

internal data class SplitDividerState(
    var topology: String = BridgeClient.LAYOUT_NONE,
    var primaryRatio: Float = 0.50f,
    var secondaryRatio: Float = 0.50f,
    var col0Pct: Int = 33,
    var col1Pct: Int = 34,
)

/** Pure geometry, ratio clamping, and unified divider drag handling for all 5 tiling topologies. */
internal object SplitTopologyLayout {

    val PRESET_PCTS = intArrayOf(30, 40, 50, 60, 70)

    private class DividerDragState {
        var downRawX = 0f; var downRawY = 0f; var startRatio = 0.5f; var moved = false
    }

    fun resolveActiveTopology(st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot, portrait: Boolean): String = when {
        BridgeClient.isTilingTopology(snap.tilingTopology) -> snap.tilingTopology.uppercase()
        BridgeClient.isTilingTopology(st.activeLayoutMode) -> st.activeLayoutMode.uppercase()
        st.splitActive == 1 -> if (portrait) BridgeClient.LAYOUT_SPLIT_2_V else BridgeClient.LAYOUT_SPLIT_2_H
        else -> BridgeClient.LAYOUT_NONE
    }

    fun isMultiAppTopology(topo: String): Boolean =
        topo == BridgeClient.LAYOUT_SPLIT_3_MASTER || topo == BridgeClient.LAYOUT_SPLIT_3_COLS || topo == BridgeClient.LAYOUT_GRID_2X2

    fun syncStateFromDaemon(state: SplitDividerState, st: BridgeClient.Status, snap: BridgeClient.WindowStateSnapshot) {
        val raw = snap.ratios.ifEmpty { listOf(if (st.splitRatioPct > 0) st.splitRatioPct else 50) }
        val norm = BridgeClient.normalizeTilingRatios(state.topology, raw)
        when (state.topology) {
            BridgeClient.LAYOUT_SPLIT_3_COLS -> { state.col0Pct = norm[0]; state.col1Pct = norm[1]; state.primaryRatio = norm[0] / 100f }
            BridgeClient.LAYOUT_SPLIT_3_MASTER, BridgeClient.LAYOUT_GRID_2X2 -> { state.primaryRatio = norm[0] / 100f; state.secondaryRatio = norm[1] / 100f }
            else -> state.primaryRatio = (if (snap.ratios.isNotEmpty()) norm[0] else if (st.splitRatioPct > 0) st.splitRatioPct else 50).coerceIn(25, 75) / 100f
        }
    }

    fun computePlacements(
        state: SplitDividerState,
        surfW: Int,
        surfH: Int,
        vW: Int,
        vH: Int,
        hW: Int,
        hH: Int,
    ): Pair<DividerPlacement, DividerPlacement?> = when (state.topology) {
        BridgeClient.LAYOUT_SPLIT_3_MASTER -> {
            val r0 = state.primaryRatio.coerceIn(0.25f, 0.75f); val r1 = state.secondaryRatio.coerceIn(0.25f, 0.75f)
            val divX = (surfW * r0).roundToInt(); val divY = (surfH * r1).roundToInt()
            val rightCx = divX + (surfW - divX) / 2
            DividerPlacement((divX - vW / 2).coerceIn(0, surfW - vW), (surfH - vH) / 2, vW, vH, "⋮\n${(r0 * 100f).roundToInt()}") to
                DividerPlacement((rightCx - hW / 2).coerceIn(divX, max(divX, surfW - hW)), (divY - hH / 2).coerceIn(0, surfH - hH), hW, hH, "⋯ ${(r1 * 100f).roundToInt()}%")
        }
        BridgeClient.LAYOUT_SPLIT_3_COLS -> {
            val c0 = state.col0Pct.coerceIn(20, 60); val c1 = state.col1Pct.coerceIn(20, 80 - c0); val c2 = (100 - c0 - c1).coerceAtLeast(20)
            val x1 = (surfW * (c0 / 100f)).roundToInt(); val x2 = (surfW * ((c0 + c1) / 100f)).roundToInt()
            DividerPlacement((x1 - vW / 2).coerceIn(0, surfW - vW), (surfH - vH) / 2, vW, vH, "⋮\n$c0") to
                DividerPlacement((x2 - vW / 2).coerceIn(0, surfW - vW), (surfH - vH) / 2, vW, vH, "⋮\n$c2")
        }
        BridgeClient.LAYOUT_GRID_2X2 -> {
            val rx = state.primaryRatio.coerceIn(0.25f, 0.75f); val ry = state.secondaryRatio.coerceIn(0.25f, 0.75f)
            val divX = (surfW * rx).roundToInt(); val divY = (surfH * ry).roundToInt()
            DividerPlacement((divX - vW / 2).coerceIn(0, surfW - vW), ((divY / 2) - vH / 2).coerceIn(0, surfH - vH), vW, vH, "⋮\n${(rx * 100f).roundToInt()}") to
                DividerPlacement(((divX / 2) - hW / 2).coerceIn(0, surfW - hW), (divY - hH / 2).coerceIn(0, surfH - hH), hW, hH, "⋯ ${(ry * 100f).roundToInt()}%")
        }
        else -> {
            val portrait = state.topology == BridgeClient.LAYOUT_SPLIT_2_V || (state.topology != BridgeClient.LAYOUT_SPLIT_2_H && surfH > surfW)
            val w = if (portrait) hW else vW; val h = if (portrait) hH else vH
            val r = state.primaryRatio.coerceIn(0.25f, 0.75f); val pct = (r * 100f).roundToInt()
            val x = if (portrait) (surfW - w) / 2 else ((surfW * r).roundToInt() - w / 2).coerceIn(0, surfW - w)
            val y = if (portrait) ((surfH * r).roundToInt() - h / 2).coerceIn(0, surfH - h) else (surfH - h) / 2
            DividerPlacement(x, y, w, h, if (portrait) "⋯ $pct%" else "⋮\n$pct") to null
        }
    }

    fun computeMenuPosition(
        isActive: Boolean,
        state: SplitDividerState,
        surfW: Int,
        surfH: Int,
        menuW: Int,
        menuH: Int,
        margin: Int,
        inactiveBottomPad: Int,
        portraitGapAboveDivider: Int,
        portraitBottomPad: Int,
        landscapeVerticalShift: Int,
    ): Pair<Int, Int> {
        if (!isActive) {
            return ((surfW - menuW) / 2).coerceIn(margin, max(margin, surfW - menuW - margin)) to
                (surfH - menuH - inactiveBottomPad).coerceIn(margin, max(margin, surfH - menuH - margin))
        }
        val r = state.primaryRatio.coerceIn(0.25f, 0.75f)
        val portrait = state.topology == BridgeClient.LAYOUT_SPLIT_2_V || (state.topology == BridgeClient.LAYOUT_NONE && surfH > surfW)
        val x = if (portrait) (surfW - menuW) / 2 else ((surfW * r).roundToInt() - menuW / 2).coerceIn(margin, max(margin, surfW - menuW - margin))
        val y = if (portrait) {
            ((surfH * r).roundToInt() - menuH - portraitGapAboveDivider).coerceIn(margin, max(margin, surfH - menuH - portraitBottomPad))
        } else {
            max(margin, (surfH - menuH) / 2 - landscapeVerticalShift)
        }
        return x to y
    }

    fun offsetSlideInRect(target: Rect, portrait: Boolean, firstSlot: Boolean, shiftPx: Int): Rect {
        val iw = (target.width() * 0.04f).roundToInt(); val ih = (target.height() * 0.04f).roundToInt()
        return Rect(target.left + iw, target.top + ih, target.right - iw, target.bottom - ih).apply {
            if (!portrait) offset(if (firstSlot) -shiftPx else shiftPx, 0) else offset(0, if (firstSlot) -shiftPx else shiftPx)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachDividerDragListener(
        view: View,
        axis: DividerAxis,
        touchSlopPx: Int,
        state: SplitDividerState,
        overlaySizeProvider: () -> Pair<Int, Int>,
        onDragStateChanged: (DividerAxis, Boolean) -> Unit,
        onLiveUpdate: () -> Unit,
        onTap: () -> Unit,
        onDragCommit: () -> Unit,
    ) {
        val drag = DividerDragState()
        view.setOnTouchListener { _, ev ->
            val (dispW, dispH) = overlaySizeProvider()
            val isCols = state.topology == BridgeClient.LAYOUT_SPLIT_3_COLS
            val dragsY = when (axis) {
                DividerAxis.PRIMARY -> state.topology == BridgeClient.LAYOUT_SPLIT_2_V || (state.topology == BridgeClient.LAYOUT_NONE && dispH > dispW)
                DividerAxis.SECONDARY -> !isCols
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onDragStateChanged(axis, true)
                    drag.downRawX = ev.rawX; drag.downRawY = ev.rawY; drag.moved = false
                    drag.startRatio = when (axis) {
                        DividerAxis.PRIMARY -> if (isCols) (state.col0Pct / 100f).coerceIn(0.20f, 0.60f) else state.primaryRatio.coerceIn(0.25f, 0.75f)
                        DividerAxis.SECONDARY -> if (isCols) ((state.col0Pct + state.col1Pct) / 100f).coerceIn(0.40f, 0.80f) else state.secondaryRatio.coerceIn(0.25f, 0.75f)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - drag.downRawX; val dy = ev.rawY - drag.downRawY
                    if (!drag.moved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) drag.moved = true
                    val axisLen = max(OverlayConstants.MIN_SURFACE_PX, if (dragsY) dispH else dispW)
                    applyDragDelta(state, axis, isCols, drag.startRatio + (if (dragsY) dy else dx) / axisLen.toFloat())
                    onLiveUpdate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDragStateChanged(axis, false)
                    if (!drag.moved && ev.actionMasked == MotionEvent.ACTION_UP) onTap() else onDragCommit()
                    true
                }
                else -> false
            }
        }
    }

    internal fun applyDragDelta(state: SplitDividerState, axis: DividerAxis, isCols: Boolean, rawRatio: Float) {
        when (axis) {
            DividerAxis.PRIMARY -> if (isCols) {
                val oldSplit2 = state.col0Pct + state.col1Pct
                val newC0 = (rawRatio.coerceIn(0.20f, max(0.20f, (oldSplit2 - 20).coerceIn(20, 60) / 100f)) * 100f).roundToInt().coerceIn(20, 60)
                state.col0Pct = newC0; state.col1Pct = (oldSplit2 - newC0).coerceIn(20, 80 - newC0)
            } else {
                state.primaryRatio = rawRatio.coerceIn(0.25f, 0.75f)
            }
            DividerAxis.SECONDARY -> if (isCols) {
                val minSplit2 = (state.col0Pct + 20).coerceIn(40, 80) / 100f
                val split2Pct = (rawRatio.coerceIn(minSplit2, 0.80f) * 100f).roundToInt().coerceIn(state.col0Pct + 20, 80)
                state.col1Pct = (split2Pct - state.col0Pct).coerceAtLeast(20)
            } else {
                state.secondaryRatio = rawRatio.coerceIn(0.25f, 0.75f)
            }
        }
    }
}
