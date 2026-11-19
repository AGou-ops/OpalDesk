package com.extbridge.helper

import com.extbridge.helper.launcher.DesktopPresentationMode
import com.extbridge.helper.launcher.LauncherDesktopPolicy
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.SinkType
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.ManagedWindowInfo
import com.extbridge.helper.protocol.WindowStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherDesktopPolicyTest {

    @Test
    fun remoteStreamingAlwaysUsesPureRemoteMacPresentation() {
        val remoteIdle = DaemonStatus(
            connected = true,
            vdId = 2,
            virtW = 2560,
            virtH = 1440,
            sinkType = SinkType.REMOTE_USB,
        )
        val idleMode = LauncherDesktopPolicy.computeDesktopLayoutMode(remoteIdle, 2560, 1440)
        assertEquals(DesktopPresentationMode.REMOTE_MAC, idleMode.presentation)
        assertTrue(idleMode.isRemoteMac)
        assertFalse(idleMode.singleEmptySlot)
        assertFalse(idleMode.compact)

        // Even if split has 1 empty slot during Remote Mac streaming, Mac App Drawer handles picking.
        val remoteHalfSplit = remoteIdle.copy(
            sinkType = SinkType.REMOTE_WIFI,
            splitActive = 1,
            splitSlot0Pkg = "com.tencent.mm",
            splitSlot1Pkg = "",
        )
        val splitMode = LauncherDesktopPolicy.computeDesktopLayoutMode(remoteHalfSplit, 1280, 1440)
        assertEquals(DesktopPresentationMode.REMOTE_MAC, splitMode.presentation)
        assertFalse(splitMode.singleEmptySlot)
    }

    @Test
    fun physicalMonitorDistinguishesDesktopCompactAndSplitEmptySlotPicker() {
        val physStatus = DaemonStatus(
            connected = true,
            vdId = 3,
            virtW = 1920,
            virtH = 1080,
            sinkType = SinkType.PHYSICAL,
        )

        val fullDesktop = LauncherDesktopPolicy.computeDesktopLayoutMode(physStatus, 1920, 1080)
        assertEquals(DesktopPresentationMode.PHYSICAL_DESKTOP, fullDesktop.presentation)
        assertFalse(fullDesktop.compact)
        assertFalse(fullDesktop.singleEmptySlot)

        // Window smaller than 88% of physical monitor switches grid to compact density.
        val compactWindow = LauncherDesktopPolicy.computeDesktopLayoutMode(physStatus, 960, 1080)
        assertEquals(DesktopPresentationMode.PHYSICAL_DESKTOP, compactWindow.presentation)
        assertTrue(compactWindow.compact)
        assertFalse(compactWindow.singleEmptySlot)

        // Physical split with exactly 1 empty slot activates dedicated split picker.
        val oneEmptySlot = physStatus.copy(splitActive = 1, splitSlot0Pkg = "com.tencent.mm", splitSlot1Pkg = "")
        val pickerMode = LauncherDesktopPolicy.computeDesktopLayoutMode(oneEmptySlot, 960, 1080)
        assertEquals(DesktopPresentationMode.PHYSICAL_SPLIT_PICKER, pickerMode.presentation)
        assertTrue(pickerMode.singleEmptySlot)
        assertTrue(pickerMode.compact)

        // Once both split slots are filled, it is no longer a split picker.
        val bothFilled = oneEmptySlot.copy(splitSlot1Pkg = "com.xingin.xhs")
        val fullSplitMode = LauncherDesktopPolicy.computeDesktopLayoutMode(bothFilled, 1920, 1080)
        assertEquals(DesktopPresentationMode.PHYSICAL_DESKTOP, fullSplitMode.presentation)
        assertFalse(fullSplitMode.singleEmptySlot)
    }

    @Test
    fun backdropFocusModeDimsWhenCoveredAndReturnsCleanlyOnHome() {
        val physStatus = DaemonStatus(
            connected = true,
            vdId = 3,
            virtW = 1920,
            virtH = 1080,
            sinkType = SinkType.PHYSICAL,
            activeLayoutMode = LayoutMode.ADAPTIVE_SINGLE,
        )
        val physLayout = LauncherDesktopPolicy.computeDesktopLayoutMode(physStatus, 1920, 1080)
        val emptySnap = WindowStateSnapshot()
        val activeAppSnap = WindowStateSnapshot(
            layoutMode = LayoutMode.ADAPTIVE_SINGLE,
            windows = listOf(
                ManagedWindowInfo(12, "com.tencent.mm", LayoutMode.ADAPTIVE_SINGLE, 500, 0, 1420, 1080, focused = true, minimized = false),
            ),
        )
        val minimizedSnap = WindowStateSnapshot(
            layoutMode = LayoutMode.ADAPTIVE_SINGLE,
            windows = listOf(
                ManagedWindowInfo(12, "com.tencent.mm", LayoutMode.ADAPTIVE_SINGLE, 500, 0, 1420, 1080, focused = false, minimized = true),
            ),
        )

        // Idle on Home (resumed + top-resumed) -> crisp desktop, never dimmed even if adaptiveSingleEnabled reports ADAPTIVE_SINGLE.
        assertFalse(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, emptySnap, isResumed = true, isTopResumed = true, layoutMode = physLayout))
        assertFalse(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, minimizedSnap, isResumed = true, isTopResumed = true, layoutMode = physLayout))

        // Standard onPause() when an app covers desktop -> immediately dimmed!
        assertTrue(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, emptySnap, isResumed = false, isTopResumed = false, layoutMode = physLayout))

        // Android 11+ multi-resume: Launcher stays resumed behind a centered/freeform window, loses top-resumed -> dimmed!
        assertTrue(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, activeAppSnap, isResumed = true, isTopResumed = false, layoutMode = physLayout))

        // Multi-display safeguard: tapping Display 0 while external desktop is idle (no visible managed app) must NOT dim desktop.
        assertFalse(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, minimizedSnap, isResumed = true, isTopResumed = false, layoutMode = physLayout))

        // Returning Home (onResume + top-resumed) restores desktop cleanly.
        assertFalse(LauncherDesktopPolicy.shouldDimBackdrop(physStatus, activeAppSnap, isResumed = true, isTopResumed = true, layoutMode = physLayout))

        // Physical split empty-slot picker MUST stay visible even while paused (other split slot holds focus).
        val pickerStatus = physStatus.copy(splitActive = 1, splitSlot0Pkg = "com.tencent.mm", splitSlot1Pkg = "")
        val pickerLayout = LauncherDesktopPolicy.computeDesktopLayoutMode(pickerStatus, 960, 1080)
        assertFalse(LauncherDesktopPolicy.shouldDimBackdrop(pickerStatus, activeAppSnap, isResumed = false, isTopResumed = false, layoutMode = pickerLayout))

        // Remote Mac split (even with 1 slot empty) DOES dim when paused/covered so nothing bleeds through 8px seam.
        val remoteSplit = pickerStatus.copy(sinkType = SinkType.REMOTE_USB)
        val remoteLayout = LauncherDesktopPolicy.computeDesktopLayoutMode(remoteSplit, 1920, 1080)
        assertTrue(LauncherDesktopPolicy.shouldDimBackdrop(remoteSplit, activeAppSnap, isResumed = false, isTopResumed = false, layoutMode = remoteLayout))
        assertTrue(LauncherDesktopPolicy.shouldDimBackdrop(remoteSplit, emptySnap, isResumed = true, isTopResumed = false, layoutMode = remoteLayout))
    }

    @Test
    fun coveringLayoutModeAndGridColumnsRespectBounds() {
        assertTrue(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(activeLayoutMode = LayoutMode.ADAPTIVE_SINGLE)))
        assertTrue(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(activeLayoutMode = LayoutMode.FREEFORM)))
        assertTrue(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(activeLayoutMode = LayoutMode.SPLIT_2_H)))
        assertTrue(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(activeLayoutMode = LayoutMode.GRID_2X2)))
        assertTrue(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(splitActive = 1, activeLayoutMode = LayoutMode.FULLSCREEN)))
        assertFalse(LauncherDesktopPolicy.isCoveringLayoutMode(DaemonStatus(splitActive = 0, activeLayoutMode = LayoutMode.FULLSCREEN)))

        // Responsive column counts within centered desktop and compact split picker.
        assertEquals(7, LauncherDesktopPolicy.computeGridColumns(920f, compact = false))
        assertEquals(5, LauncherDesktopPolicy.computeGridColumns(640f, compact = false))
        assertEquals(4, LauncherDesktopPolicy.computeGridColumns(320f, compact = false))
        assertEquals(6, LauncherDesktopPolicy.computeGridColumns(640f, compact = true))
        assertEquals(3, LauncherDesktopPolicy.computeGridColumns(240f, compact = true))
    }
}
