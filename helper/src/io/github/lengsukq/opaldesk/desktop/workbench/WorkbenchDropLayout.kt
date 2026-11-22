package io.github.lengsukq.opaldesk.desktop.workbench

import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.IntRect

internal data class WorkbenchDropLayout(
    val bounds: IntRect,
    val companionBounds: IntRect? = null,
    val label: String = "替换主工作区",
) {
    companion object {
        // The daemon only accepts explicit window bounds that are at least 200px wide.
        private const val MIN_SPLIT_WIDTH_PX = 200

        fun at(stage: IntRect, dpi: Int, x: Float, y: Float): WorkbenchDropLayout? {
            val outer = DesktopGeometry.computeOuterFrame(stage, dpi)
            if (stage.isEmpty || x < outer.left || x >= outer.right || y < outer.top || y >= outer.bottom) return null

            val gap = DesktopGeometry.dpToPx(DesktopGeometry.SNAP_GAP_DP, dpi).coerceAtLeast(4)
            val left = stage.copy(right = stage.centerX - gap / 2)
            val right = stage.copy(left = left.right + gap)
            if (left.width < MIN_SPLIT_WIDTH_PX || right.width < MIN_SPLIT_WIDTH_PX) return WorkbenchDropLayout(stage)

            val offset = x - stage.left
            return when {
                offset < stage.width / 3f -> WorkbenchDropLayout(left, right, "放在左半屏")
                offset >= stage.width * (2f / 3f) -> WorkbenchDropLayout(right, left, "放在右半屏")
                else -> WorkbenchDropLayout(stage)
            }
        }
    }
}
