package com.extbridge.helper.desktop.model

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure JVM-safe integer rectangle mirroring [android.graphics.Rect] for deterministic unit testing. */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersects(other: IntRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
        fun fromAndroidRect(r: Rect): IntRect = IntRect(r.left, r.top, r.right, r.bottom)
    }
}

enum class SnapZone {
    NONE,
    MAXIMIZE,
    LEFT_HALF,
    RIGHT_HALF,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT_WIDE_65,
    RIGHT_NARROW_35,
    THIRD_LEFT,
    THIRD_CENTER,
    THIRD_RIGHT,
    IMMERSIVE,
}

enum class ResizeEdge {
    N, S, E, W, NW, NE, SW, SE,
}

data class VisibleHeaderSegment(
    val visibleLeft: Int,
    val visibleRight: Int,
    val contentOffsetX: Int,
) {
    val visibleWidth: Int get() = max(0, visibleRight - visibleLeft)
}

data class PairedSplitPair(
    val leftTaskId: Int,
    val leftPkg: String,
    val leftBounds: IntRect,
    val rightTaskId: Int,
    val rightPkg: String,
    val rightBounds: IntRect,
    val dividerCenterX: Int,
    val topY: Int,
    val bottomY: Int,
)

/**
 * Single source of truth for Samsung DeX desktop work-area metrics, 8-way resize math,
 * Aero Snap zones, horizontal header occlusion clipping, and paired side-by-side split detection.
 *
 * Constants mirror `com.extbridge.daemon.DisplayGeometryUtil` pixel-for-pixel.
 */
object DesktopGeometry {
    const val WORKAREA_TOP_INSET_DP = 8
    const val WORKAREA_SIDE_INSET_DP = 8
    const val TASKBAR_HEIGHT_DP = 56
    const val TASKBAR_BOTTOM_MARGIN_DP = 8
    const val TASKBAR_RESERVED_INSET_DP = 72
    const val TASKBAR_AUTOHIDE_INSET_DP = 8
    const val WINDOW_HEADER_HEIGHT_DP = 38
    const val WINDOW_CORNER_RADIUS_DP = 16f
    const val MIN_WINDOW_W_DP = 320
    const val MIN_WINDOW_H_DP = 280
    const val SNAP_GAP_DP = 8
    const val EDGE_SNAP_TRIGGER_DP = 22
    const val CORNER_SNAP_TRIGGER_DP = 52
    const val RESIZE_MAGNET_DP = 12
    const val MIN_VISIBLE_HEADER_DP = 88

    fun dpToPx(dp: Int, dpi: Int): Int {
        val effDpi = dpi.coerceIn(80, 640)
        return max(0, (dp * (effDpi / 160f)).roundToInt())
    }

    fun pxToDp(px: Int, dpi: Int): Int {
        val effDpi = dpi.coerceIn(80, 640)
        return max(0, (px * 160f / effDpi).roundToInt())
    }

    fun headerHeightPx(dpi: Int): Int = dpToPx(WINDOW_HEADER_HEIGHT_DP, dpi)

    fun computeWorkArea(displayW: Int, displayH: Int, dpi: Int, taskbarAutoHide: Boolean): IntRect {
        val w = max(320, displayW)
        val h = max(240, displayH)
        val topPx = min(h / 4, dpToPx(WORKAREA_TOP_INSET_DP, dpi))
        val bottomInsetPx = min(
            h / 3,
            dpToPx(if (taskbarAutoHide) TASKBAR_AUTOHIDE_INSET_DP else TASKBAR_RESERVED_INSET_DP, dpi),
        )
        val sidePx = min(w / 16, dpToPx(WORKAREA_SIDE_INSET_DP, dpi))
        return IntRect(sidePx, topPx, max(sidePx + 1, w - sidePx), max(topPx + 1, h - bottomInsetPx))
    }

    fun computeOuterFrame(taskRect: IntRect, dpi: Int): IntRect {
        val hdr = headerHeightPx(dpi)
        return IntRect(taskRect.left, max(0, taskRect.top - hdr), taskRect.right, taskRect.bottom)
    }

    fun sanitizeWindowRect(raw: IntRect, displayW: Int, displayH: Int): IntRect {
        val w = max(600, displayW)
        val h = max(480, displayH)
        if (raw.isEmpty) return IntRect(0, 0, w, h)
        val l = raw.left.coerceIn(0, w - 60)
        val t = raw.top.coerceIn(0, h - 60)
        return IntRect(l, t, raw.right.coerceIn(l + 60, w), raw.bottom.coerceIn(t + 60, h))
    }

    /**
     * Computes exact pixel bounds for a task surface in [zone], leaving room flush above `top`
     * for its 38dp DeX Liquid Glass header bar.
     */
    fun computeSnapRect(
        zone: SnapZone,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): IntRect {
        val w = max(480, displayW)
        val h = max(480, displayH)
        if (zone == SnapZone.IMMERSIVE) return IntRect(0, 0, w, h)

        val wa = computeWorkArea(w, h, dpi, taskbarAutoHide)
        val hdr = headerHeightPx(dpi)
        val top = min(max(0, wa.bottom - 240), wa.top + hdr)
        val bottom = max(top + 220, wa.bottom)
        val left = wa.left
        val right = wa.right
        val gap = max(4, dpToPx(SNAP_GAP_DP, dpi))
        val halfGap = gap / 2
        val midX = (left + right) / 2
        val midY = (top + bottom) / 2
        val quarterBottomTop = min(bottom - 180, midY + halfGap + hdr)
        val split65X = left + ((right - left) * 0.65f).roundToInt()
        val thirdW = max(200, (right - left - gap * 2) / 3)

        return when (zone) {
            SnapZone.MAXIMIZE, SnapZone.NONE -> IntRect(left, top, right, bottom)
            SnapZone.LEFT_HALF -> IntRect(left, top, max(left + 200, midX - halfGap), bottom)
            SnapZone.RIGHT_HALF -> IntRect(min(right - 200, midX + halfGap), top, right, bottom)
            SnapZone.TOP_LEFT -> IntRect(left, top, max(left + 200, midX - halfGap), max(top + 180, midY - halfGap))
            SnapZone.TOP_RIGHT -> IntRect(min(right - 200, midX + halfGap), top, right, max(top + 180, midY - halfGap))
            SnapZone.BOTTOM_LEFT -> IntRect(left, quarterBottomTop, max(left + 200, midX - halfGap), bottom)
            SnapZone.BOTTOM_RIGHT -> IntRect(min(right - 200, midX + halfGap), quarterBottomTop, right, bottom)
            SnapZone.LEFT_WIDE_65 -> IntRect(left, top, max(left + 220, split65X - halfGap), bottom)
            SnapZone.RIGHT_NARROW_35 -> IntRect(min(right - 200, split65X + halfGap), top, right, bottom)
            SnapZone.THIRD_LEFT -> IntRect(left, top, left + thirdW, bottom)
            SnapZone.THIRD_CENTER -> IntRect(left + thirdW + gap, top, min(right - thirdW - gap, left + thirdW * 2 + gap), bottom)
            SnapZone.THIRD_RIGHT -> IntRect(max(left + 200, right - thirdW), top, right, bottom)
            SnapZone.IMMERSIVE -> IntRect(0, 0, w, h)
        }
    }

    /** Computes a centered floating window sized to a phone/foldable/tablet width preset (dp). */
    fun computeCenteredPresetRect(
        widthDp: Int,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): IntRect {
        val wa = computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val hdr = headerHeightPx(dpi)
        val minTop = wa.top + hdr
        val availH = max(240, wa.bottom - minTop)
        val targetW = dpToPx(widthDp.coerceIn(MIN_WINDOW_W_DP, 1280), dpi).coerceIn(dpToPx(MIN_WINDOW_W_DP, dpi), wa.width)
        val targetH = (availH * 0.90f).roundToInt().coerceIn(min(availH, dpToPx(MIN_WINDOW_H_DP, dpi)), availH)
        val left = (wa.left + (wa.width - targetW) / 2).coerceIn(wa.left, max(wa.left, wa.right - targetW))
        val top = (minTop + (availH - targetH) / 2).coerceIn(minTop, max(minTop, wa.bottom - targetH))
        return IntRect(left, top, left + targetW, top + targetH)
    }

    fun detectSnapZone(pointerX: Int, pointerY: Int, displayW: Int, displayH: Int, dpi: Int): SnapZone {
        val edge = dpToPx(EDGE_SNAP_TRIGGER_DP, dpi)
        val corner = dpToPx(CORNER_SNAP_TRIGGER_DP, dpi)
        return when {
            pointerX <= corner && pointerY <= corner -> SnapZone.TOP_LEFT
            pointerX >= displayW - corner && pointerY <= corner -> SnapZone.TOP_RIGHT
            pointerX <= corner && pointerY >= displayH - corner -> SnapZone.BOTTOM_LEFT
            pointerX >= displayW - corner && pointerY >= displayH - corner -> SnapZone.BOTTOM_RIGHT
            pointerY <= edge -> SnapZone.MAXIMIZE
            pointerX <= edge -> SnapZone.LEFT_HALF
            pointerX >= displayW - edge -> SnapZone.RIGHT_HALF
            else -> SnapZone.NONE
        }
    }

    fun computeMovedRect(
        baseRect: IntRect,
        dx: Int,
        dy: Int,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): IntRect {
        val wa = computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val hdr = headerHeightPx(dpi)
        val minTop = wa.top + hdr
        val w = baseRect.width.coerceIn(dpToPx(MIN_WINDOW_W_DP, dpi), max(dpToPx(MIN_WINDOW_W_DP, dpi), displayW))
        val h = baseRect.height.coerceIn(dpToPx(MIN_WINDOW_H_DP, dpi), max(dpToPx(MIN_WINDOW_H_DP, dpi), wa.bottom - minTop))
        val minAllowLeft = -(w / 2)
        val maxAllowLeft = displayW - max(dpToPx(120, dpi), w / 2)
        val nl = (baseRect.left + dx).coerceIn(minAllowLeft, max(minAllowLeft, maxAllowLeft))
        val maxTop = max(minTop, wa.bottom - dpToPx(80, dpi))
        val nt = (baseRect.top + dy).coerceIn(minTop, maxTop)
        return IntRect(nl, nt, nl + w, nt + h)
    }

    /** Full 8-direction resize with minimum dimension enforcement and work-area edge magnetism. */
    fun computeResizedRect(
        baseRect: IntRect,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): IntRect {
        val wa = computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val hdr = headerHeightPx(dpi)
        val minTop = wa.top + hdr
        val minW = min(wa.width, dpToPx(MIN_WINDOW_W_DP, dpi))
        val minH = min(max(160, wa.bottom - minTop), dpToPx(MIN_WINDOW_H_DP, dpi))
        val magnet = dpToPx(RESIZE_MAGNET_DP, dpi)

        var l = baseRect.left
        var t = baseRect.top
        var r = baseRect.right
        var b = baseRect.bottom

        if (edge == ResizeEdge.W || edge == ResizeEdge.NW || edge == ResizeEdge.SW) {
            l = (baseRect.left + dx).coerceIn(0, max(0, baseRect.right - minW))
            if (abs(l - wa.left) <= magnet) l = wa.left
        }
        if (edge == ResizeEdge.E || edge == ResizeEdge.NE || edge == ResizeEdge.SE) {
            r = (baseRect.right + dx).coerceIn(min(displayW, baseRect.left + minW), displayW)
            if (abs(r - wa.right) <= magnet) r = wa.right
        }
        if (edge == ResizeEdge.N || edge == ResizeEdge.NW || edge == ResizeEdge.NE) {
            t = (baseRect.top + dy).coerceIn(minTop, max(minTop, baseRect.bottom - minH))
            if (abs(t - minTop) <= magnet) t = minTop
        }
        if (edge == ResizeEdge.S || edge == ResizeEdge.SW || edge == ResizeEdge.SE) {
            b = (baseRect.bottom + dy).coerceIn(min(wa.bottom, baseRect.top + minH), wa.bottom)
            if (abs(b - wa.bottom) <= magnet) b = wa.bottom
        }
        return IntRect(l, t, max(l + minW, r), max(t + minH, b))
    }

    /**
     * Exact 1D horizontal interval subtraction for a lower window's header bar occluded by
     * higher windows. Returns the largest unobstructed horizontal slice so the WindowManager
     * touch surface NEVER overlaps an upper window while keeping inner controls unshifted via
     * [VisibleHeaderSegment.contentOffsetX].
     */
    fun computeVisibleHeaderSegment(
        fullHeaderLeft: Int,
        fullHeaderRight: Int,
        headerTop: Int,
        headerBottom: Int,
        upperOuterRects: List<IntRect>,
        minVisibleWidthPx: Int,
    ): VisibleHeaderSegment? {
        if (fullHeaderRight <= fullHeaderLeft) return null
        var segments = listOf(fullHeaderLeft to fullHeaderRight)
        for (upper in upperOuterRects) {
            if (upper.bottom <= headerTop || upper.top >= headerBottom) continue
            if (upper.right <= fullHeaderLeft || upper.left >= fullHeaderRight) continue
            val next = ArrayList<Pair<Int, Int>>(segments.size + 1)
            for ((sL, sR) in segments) {
                if (upper.right <= sL || upper.left >= sR) {
                    next.add(sL to sR)
                } else {
                    if (upper.left > sL) next.add(sL to upper.left)
                    if (upper.right < sR) next.add(upper.right to sR)
                }
            }
            segments = next
            if (segments.isEmpty()) return null
        }
        val best = segments.maxByOrNull { it.second - it.first } ?: return null
        if (best.second - best.first < minVisibleWidthPx) return null
        return VisibleHeaderSegment(
            visibleLeft = best.first,
            visibleRight = best.second,
            contentOffsetX = fullHeaderLeft - best.first,
        )
    }

    /**
     * Detects when two visible windows are snapped side-by-side so a shared frosted glass
     * divider handle can resize both simultaneously without switching to a separate tiling mode.
     */
    fun detectPairedHorizontalSplit(
        visibleWindows: List<DesktopWindow>,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): PairedSplitPair? {
        if (visibleWindows.size < 2) return null
        val wa = computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val maxGap = dpToPx(24, dpi)
        val edgeTol = dpToPx(28, dpi)
        val topTol = dpToPx(28, dpi)

        // Check most recently focused pairs first (list is bottom -> top)
        val candidates = visibleWindows.asReversed().filter { !it.isImmersive }
        for (i in candidates.indices) {
            val a = candidates[i]
            for (j in i + 1 until candidates.size) {
                val b = candidates[j]
                val (leftW, rightW) = if (a.bounds.left <= b.bounds.left) a to b else b to a
                val gap = rightW.bounds.left - leftW.bounds.right
                if (gap < -edgeTol || gap > maxGap) continue
                if (abs(leftW.bounds.top - rightW.bounds.top) > topTol) continue
                if (abs(leftW.bounds.bottom - rightW.bounds.bottom) > topTol) continue
                if (leftW.bounds.left > wa.left + edgeTol) continue
                if (rightW.bounds.right < wa.right - edgeTol) continue
                val divX = (leftW.bounds.right + rightW.bounds.left) / 2
                val topY = min(leftW.bounds.top, rightW.bounds.top)
                val botY = max(leftW.bounds.bottom, rightW.bounds.bottom)
                return PairedSplitPair(
                    leftTaskId = leftW.taskId,
                    leftPkg = leftW.pkg,
                    leftBounds = leftW.bounds,
                    rightTaskId = rightW.taskId,
                    rightPkg = rightW.pkg,
                    rightBounds = rightW.bounds,
                    dividerCenterX = divX,
                    topY = topY,
                    bottomY = botY,
                )
            }
        }
        return null
    }

    fun computePairedSplitResizedBounds(
        pair: PairedSplitPair,
        newDividerCenterX: Int,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
    ): Pair<IntRect, IntRect> {
        val wa = computeWorkArea(displayW, displayH, dpi, taskbarAutoHide)
        val gap = max(4, dpToPx(SNAP_GAP_DP, dpi))
        val halfGap = gap / 2
        val minW = dpToPx(MIN_WINDOW_W_DP, dpi)
        val minDivX = wa.left + minW + halfGap
        val maxDivX = max(minDivX, wa.right - minW - halfGap)
        val clampedX = newDividerCenterX.coerceIn(minDivX, maxDivX)
        val leftRect = IntRect(pair.leftBounds.left, pair.leftBounds.top, clampedX - halfGap, pair.leftBounds.bottom)
        val rightRect = IntRect(clampedX + halfGap, pair.rightBounds.top, pair.rightBounds.right, pair.rightBounds.bottom)
        return leftRect to rightRect
    }

    fun snapZoneLabel(zone: SnapZone): String = when (zone) {
        SnapZone.MAXIMIZE -> "松手填满工作区"
        SnapZone.LEFT_HALF -> "松手贴靠左半屏"
        SnapZone.RIGHT_HALF -> "松手贴靠右半屏"
        SnapZone.TOP_LEFT -> "松手贴靠左上四分屏"
        SnapZone.TOP_RIGHT -> "松手贴靠右上四分屏"
        SnapZone.BOTTOM_LEFT -> "松手贴靠左下四分屏"
        SnapZone.BOTTOM_RIGHT -> "松手贴靠右下四分屏"
        SnapZone.LEFT_WIDE_65 -> "左侧宽屏 65%"
        SnapZone.RIGHT_NARROW_35 -> "右侧窄屏 35%"
        SnapZone.THIRD_LEFT -> "左三分屏"
        SnapZone.THIRD_CENTER -> "中三分屏"
        SnapZone.THIRD_RIGHT -> "右三分屏"
        SnapZone.IMMERSIVE -> "沉浸全屏"
        SnapZone.NONE -> ""
    }
}
