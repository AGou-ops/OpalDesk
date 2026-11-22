package io.github.lengsukq.opaldesk.protocol

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM-testable display geometry, DPI scaling, adaptive window bounds,
 * tiling ratio normalization, and EDID mode scoring utilities.
 */
object DisplayGeometry {
    const val DEFAULT_SINGLE_WIDTH_DP = 460
    const val MIN_SINGLE_WIDTH_DP = 320
    const val MAX_SINGLE_WIDTH_DP = 1200
    const val UI_MIN_SINGLE_WIDTH_DP = 360
    const val UI_MAX_SINGLE_WIDTH_DP = 1100

    private val MAGNETIC_WIDTH_PRESETS_DP = intArrayOf(460, 680, 900)
    private const val SNAP_TOLERANCE_DP = 14
    private const val FULLSCREEN_SNAP_RATIO = 0.94f

    fun clampSingleWidthDp(widthDp: Int): Int =
        widthDp.coerceIn(MIN_SINGLE_WIDTH_DP, MAX_SINGLE_WIDTH_DP)

    fun clampUiSingleWidthDp(widthDp: Int): Int =
        widthDp.coerceIn(UI_MIN_SINGLE_WIDTH_DP, UI_MAX_SINGLE_WIDTH_DP)

    fun pxToDp(px: Int, dpi: Int): Int =
        (px * 160f / max(80, dpi)).roundToInt()

    fun dpToPx(dpVal: Int, dpi: Int): Int =
        (dpVal * max(80, dpi) / 160f).roundToInt()

    /**
     * Snaps a raw dragged width in dp to magnetic presets (460/680/900dp) or fullscreen
     * when within >= 94% of the full display width in dp.
     * Returns `(snappedWidthDp, isFullscreen)`.
     */
    fun snapWidthDp(rawWidthDp: Int, fullWidthDp: Int): Pair<Int, Boolean> {
        val safeFull = max(MIN_SINGLE_WIDTH_DP, fullWidthDp)
        val clamped = rawWidthDp.coerceIn(MIN_SINGLE_WIDTH_DP, safeFull)
        if (clamped >= (safeFull * FULLSCREEN_SNAP_RATIO).roundToInt()) {
            return safeFull to true
        }
        for (preset in MAGNETIC_WIDTH_PRESETS_DP) {
            if (preset < safeFull && abs(clamped - preset) <= SNAP_TOLERANCE_DP) {
                return preset to false
            }
        }
        return clamped to false
    }

    /**
     * Computes a horizontally centered single-app bounds `[left, top, right, bottom]` in pixels
     * for a requested dp width at `dpi`, respecting display bounds and optional bottom dock reserve.
     */
    fun computeCenteredSingleBoundsPx(
        displayW: Int,
        displayH: Int,
        dpi: Int,
        widthDp: Int,
        dockReservePx: Int = 0,
    ): IntArray {
        val safeW = max(MIN_SINGLE_WIDTH_DP, displayW)
        val safeH = max(MIN_SINGLE_WIDTH_DP, displayH)
        val availH = max(240, safeH - max(0, dockReservePx))
        val targetW = dpToPx(clampSingleWidthDp(widthDp), dpi)
            .coerceIn(min(safeW, dpToPx(MIN_SINGLE_WIDTH_DP, dpi)), safeW)
        val left = max(0, (safeW - targetW) / 2)
        val right = min(safeW, left + targetW)
        return intArrayOf(left, 0, right, availH)
    }

    /**
     * Normalizes tiling ratios for the requested topology so overlay dividers have clean,
     * bounded percentages matching `TilingLayoutEngine`.
     */
    fun normalizeTilingRatios(topology: String?, rawRatios: List<Int>): List<Int> =
        when (topology?.uppercase(Locale.ROOT)) {
            BridgeProtocol.LayoutMode.SPLIT_3_COLS -> {
                when {
                    rawRatios.size >= 3 -> {
                        val c0 = rawRatios[0].coerceIn(20, 60)
                        val c1 = rawRatios[1].coerceIn(20, 80 - c0)
                        val c2 = (100 - c0 - c1).coerceAtLeast(20)
                        listOf(c0, c1, c2)
                    }
                    rawRatios.size == 2 -> {
                        val c0 = rawRatios[0].coerceIn(20, 60)
                        val second = rawRatios[1]
                        val c1 = if (second > c0 && second <= 80 && c0 + second >= 95) {
                            (second - c0).coerceIn(20, 80 - c0)
                        } else {
                            second.coerceIn(20, 80 - c0)
                        }
                        val c2 = (100 - c0 - c1).coerceAtLeast(20)
                        listOf(c0, c1, c2)
                    }
                    else -> listOf(33, 34, 33)
                }
            }
            BridgeProtocol.LayoutMode.SPLIT_3_MASTER,
            BridgeProtocol.LayoutMode.GRID_2X2,
            -> {
                val r0 = (rawRatios.getOrNull(0) ?: 50).coerceIn(25, 75)
                val r1 = (rawRatios.getOrNull(1) ?: 50).coerceIn(25, 75)
                listOf(r0, r1)
            }
            else -> {
                val r0 = (rawRatios.getOrNull(0) ?: 50).coerceIn(25, 75)
                listOf(r0, 100 - r0)
            }
        }

    fun computeOverlayDpi(virtW: Int, virtH: Int, reqDpi: Int): Int {
        val safeReq = if (reqDpi > 0) reqDpi else 240
        val safeW = if (virtW > 0) virtW else 2560
        val safeH = if (virtH > 0) virtH else 1440
        val minEdge = min(safeW, safeH)
        val maxDpiForEdge = max(80, (minEdge * 160f / 720f).roundToInt())
        return max(80, min(safeReq, maxDpiForEdge))
    }

    fun computeSmallestWidthDp(virtW: Int, virtH: Int, dpi: Int): Int {
        val minEdge = min(max(1, virtW), max(1, virtH))
        val safeDpi = max(80, dpi)
        return (minEdge * 160f / safeDpi).roundToInt()
    }

    fun recommendDpiForResolution(width: Int, height: Int): Int {
        val minEdge = min(width, height)
        return when {
            minEdge >= 2000 -> 320
            minEdge >= 1360 -> 240
            minEdge >= 1000 -> 200
            else -> 160
        }
    }

    /**
     * Computes a finger-friendly DeX DPI for on-device standalone Local DeX mode so the
     * virtual display always crosses the tablet/desktop threshold (sw >= 720dp) while
     * remaining comfortable to tap on phone and tablet screens alike.
     */
    fun computeLocalDexDpi(width: Int, height: Int, deviceSmallestWidthDp: Int = 411): Int {
        val minEdge = min(max(480, width), max(480, height))
        val targetShortDp = if (deviceSmallestWidthDp >= 600) 840f else 720f
        val raw = (minEdge * 160f / targetShortDp).toInt()
        val snapped = (raw / 4) * 4
        return snapped.coerceIn(120, 360)
    }

    fun alignResolution16(value: Int, minVal: Int = 640, maxVal: Int = 7680): Int {
        val clamped = value.coerceIn(minVal, maxVal)
        return ((clamped + 8) / 16) * 16
    }

    /**
     * Picks the best physical monitor EDID mode: requires >= 640x480, prefers >= 50Hz if available,
     * maximizes pixel area, and breaks ties by refresh rate (capped at 120Hz).
     */
    fun pickBestEdidMode(modes: List<EdidMode>): EdidMode? {
        val valid = modes.filter { max(it.width, it.height) >= 640 && min(it.width, it.height) >= 480 }
        if (valid.isEmpty()) return null
        val has50Plus = valid.any { it.fps >= 49.5f }
        var best: EdidMode? = null
        var bestArea = -1L
        var bestFpsScore = -1f
        for (mode in valid) {
            if (has50Plus && mode.fps < 49.5f) continue
            val area = mode.width.toLong() * mode.height.toLong()
            val fpsScore = mode.fps.coerceAtMost(120f)
            if (area > bestArea || (area == bestArea && fpsScore > bestFpsScore + 0.2f)) {
                bestArea = area
                bestFpsScore = fpsScore
                best = mode
            }
        }
        return best
    }
}
