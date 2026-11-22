package io.github.lengsukq.opaldesk.desktop

import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.model.ResizeEdge
import io.github.lengsukq.opaldesk.desktop.model.SnapZone
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.LayoutMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.WindowPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGeometryTest {

    @Test
    fun workAreaMatchesDaemonSingleSourceOfTruthPixelForPixel() {
        // 2560x1440 @ 240dpi (scale = 1.5x):
        // top = 8dp -> 12px, side = 8dp -> 12px, bottom pinned = 72dp -> 108px, auto-hide = 8dp -> 12px
        val pinned = DesktopGeometry.computeWorkArea(2560, 1440, 240, taskbarAutoHide = false)
        assertEquals(IntRect(12, 12, 2548, 1332), pinned)

        val autoHide = DesktopGeometry.computeWorkArea(2560, 1440, 240, taskbarAutoHide = true)
        assertEquals(IntRect(12, 12, 2548, 1428), autoHide)

        // Header height = 38dp @ 240dpi = 57px
        assertEquals(57, DesktopGeometry.headerHeightPx(240))
    }

    @Test
    fun snapRectLeavesExactHeaderRoomAndNeverOverlapsTaskbar() {
        val wa = DesktopGeometry.computeWorkArea(1920, 1080, 160, taskbarAutoHide = false)
        val hdr = DesktopGeometry.headerHeightPx(160) // 38px

        val maxRect = DesktopGeometry.computeSnapRect(SnapZone.MAXIMIZE, 1920, 1080, 160, taskbarAutoHide = false)
        assertEquals(wa.left, maxRect.left)
        assertEquals(wa.top + hdr, maxRect.top)
        assertEquals(wa.right, maxRect.right)
        assertEquals(wa.bottom, maxRect.bottom)

        val leftHalf = DesktopGeometry.computeSnapRect(SnapZone.LEFT_HALF, 1920, 1080, 160, taskbarAutoHide = false)
        val rightHalf = DesktopGeometry.computeSnapRect(SnapZone.RIGHT_HALF, 1920, 1080, 160, taskbarAutoHide = false)
        assertEquals(wa.left, leftHalf.left)
        assertEquals(wa.right, rightHalf.right)
        assertTrue(rightHalf.left - leftHalf.right in 6..10) // 8dp gap

        val imm = DesktopGeometry.computeSnapRect(SnapZone.IMMERSIVE, 1920, 1080, 160, taskbarAutoHide = false)
        assertEquals(IntRect(0, 0, 1920, 1080), imm)
    }

    @Test
    fun detectSnapZoneDistinguishesCornersAndEdges() {
        assertEquals(SnapZone.TOP_LEFT, DesktopGeometry.detectSnapZone(10, 10, 1920, 1080, 160))
        assertEquals(SnapZone.TOP_RIGHT, DesktopGeometry.detectSnapZone(1910, 20, 1920, 1080, 160))
        assertEquals(SnapZone.BOTTOM_LEFT, DesktopGeometry.detectSnapZone(20, 1070, 1920, 1080, 160))
        assertEquals(SnapZone.BOTTOM_RIGHT, DesktopGeometry.detectSnapZone(1900, 1060, 1920, 1080, 160))
        assertEquals(SnapZone.MAXIMIZE, DesktopGeometry.detectSnapZone(960, 5, 1920, 1080, 160))
        assertEquals(SnapZone.LEFT_HALF, DesktopGeometry.detectSnapZone(5, 500, 1920, 1080, 160))
        assertEquals(SnapZone.RIGHT_HALF, DesktopGeometry.detectSnapZone(1915, 500, 1920, 1080, 160))
        assertEquals(SnapZone.NONE, DesktopGeometry.detectSnapZone(960, 500, 1920, 1080, 160))
    }

    @Test
    fun eightWayResizeEnforcesMinDimensionsAndEdgeMagnetism() {
        val base = IntRect(400, 200, 1200, 800)
        // Shrink W past minimum 320dp (@160dpi = 320px)
        val clampedW = DesktopGeometry.computeResizedRect(base, ResizeEdge.W, dx = 700, dy = 0, 1920, 1080, 160, false)
        assertEquals(320, clampedW.width)
        assertEquals(1200, clampedW.right)

        // Expand N past header ceiling -> clamps to workArea.top + headerHeight (8 + 38 = 46)
        val clampedN = DesktopGeometry.computeResizedRect(base, ResizeEdge.N, dx = 0, dy = -300, 1920, 1080, 160, false)
        assertEquals(46, clampedN.top)
        assertEquals(800, clampedN.bottom)

        // SE diagonal near bottom-right workArea snaps magnetically (within 12dp)
        // workArea.bottom = 1080 - 72 = 1008; workArea.right = 1912
        val snappedSE = DesktopGeometry.computeResizedRect(base, ResizeEdge.SE, dx = 706, dy = 202, 1920, 1080, 160, false)
        assertEquals(1912, snappedSE.right)
        assertEquals(1008, snappedSE.bottom)

        // NW diagonal respects both min width & min height simultaneously
        val clampedNW = DesktopGeometry.computeResizedRect(base, ResizeEdge.NW, dx = 900, dy = 900, 1920, 1080, 160, false)
        assertEquals(320, clampedNW.width)
        assertEquals(280, clampedNW.height)
    }

    @Test
    fun horizontalHeaderOcclusionClipsCleanlyAndComputesContentOffset() {
        // Unobstructed header [100..900]
        val full = DesktopGeometry.computeVisibleHeaderSegment(100, 900, 50, 88, emptyList(), minVisibleWidthPx = 88)
        assertEquals(100, full?.visibleLeft)
        assertEquals(900, full?.visibleRight)
        assertEquals(0, full?.contentOffsetX)

        // Upper window covers left portion [0..400] -> remaining [400..900], contentOffsetX = 100 - 400 = -300
        val rightSlice = DesktopGeometry.computeVisibleHeaderSegment(
            100, 900, 50, 88,
            upperOuterRects = listOf(IntRect(0, 40, 400, 700)),
            minVisibleWidthPx = 88,
        )
        assertNotNull(rightSlice)
        assertEquals(400, rightSlice!!.visibleLeft)
        assertEquals(900, rightSlice.visibleRight)
        assertEquals(-300, rightSlice.contentOffsetX)

        // Upper window covers middle [300..750] -> left slice [100..300] (200px) beats right slice [750..900] (150px)
        val splitSlice = DesktopGeometry.computeVisibleHeaderSegment(
            100, 900, 50, 88,
            upperOuterRects = listOf(IntRect(300, 20, 750, 500)),
            minVisibleWidthPx = 88,
        )
        assertNotNull(splitSlice)
        assertEquals(100, splitSlice!!.visibleLeft)
        assertEquals(300, splitSlice.visibleRight)
        assertEquals(0, splitSlice.contentOffsetX)

        // Upper window completely covers header -> null
        val fullyCovered = DesktopGeometry.computeVisibleHeaderSegment(
            100, 900, 50, 88,
            upperOuterRects = listOf(IntRect(80, 20, 920, 600)),
            minVisibleWidthPx = 88,
        )
        assertNull(fullyCovered)

        // Upper window vertically disjoint (below header) -> full header stays visible
        val disjoint = DesktopGeometry.computeVisibleHeaderSegment(
            100, 900, 50, 88,
            upperOuterRects = listOf(IntRect(80, 100, 920, 600)),
            minVisibleWidthPx = 88,
        )
        assertEquals(100, disjoint?.visibleLeft)
        assertEquals(900, disjoint?.visibleRight)
    }

    @Test
    fun pairedHorizontalSplitDetectionAndCoupledResize() {
        val leftRect = DesktopGeometry.computeSnapRect(SnapZone.LEFT_HALF, 1920, 1080, 160, false)
        val rightRect = DesktopGeometry.computeSnapRect(SnapZone.RIGHT_HALF, 1920, 1080, 160, false)
        val leftWin = DesktopWindow(1, "com.a", LayoutMode.FREEFORM, WindowPresentation.SNAPPED_LEFT, leftRect, null, false, false)
        val rightWin = DesktopWindow(2, "com.b", LayoutMode.FREEFORM, WindowPresentation.SNAPPED_RIGHT, rightRect, null, true, false)

        val pair = DesktopGeometry.detectPairedHorizontalSplit(listOf(leftWin, rightWin), 1920, 1080, 160, false)
        assertNotNull(pair)
        assertEquals(1, pair!!.leftTaskId)
        assertEquals(2, pair.rightTaskId)

        // Drag divider to 60% width (1152px)
        val (newL, newR) = DesktopGeometry.computePairedSplitResizedBounds(pair, 1152, 160)
        assertEquals(leftRect.left, newL.left)
        assertEquals(rightRect.right, newR.right)
        assertEquals(8, newR.left - newL.right)
        assertEquals(1148, newL.right)
        assertEquals(1156, newR.left)

        // Dragging divider all the way to left edge clamps left window to MIN_WINDOW_W_DP (320px)
        val (minL, _) = DesktopGeometry.computePairedSplitResizedBounds(pair, 100, 160)
        assertEquals(320, minL.width)
    }
}
