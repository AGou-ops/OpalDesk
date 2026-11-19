package com.extbridge.helper

import com.extbridge.helper.overlay.DividerAxis
import com.extbridge.helper.overlay.DividerPlacement
import com.extbridge.helper.overlay.OverlayConstants
import com.extbridge.helper.overlay.SplitDividerState
import com.extbridge.helper.overlay.SplitTopologyLayout
import com.extbridge.helper.overlay.mirror.MirrorMetricsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun resolveActiveTopologyRespectsPrecedenceAndOrientation() {
        val idle = BridgeClient.Status()
        val emptySnap = BridgeClient.WindowStateSnapshot()

        // Snapshot tilingTopology takes precedence (and uppercases).
        assertEquals(
            BridgeClient.LAYOUT_GRID_2X2,
            SplitTopologyLayout.resolveActiveTopology(
                idle.copy(activeLayoutMode = BridgeClient.LAYOUT_SPLIT_2_H, splitActive = 1),
                emptySnap.copy(tilingTopology = "grid_2x2"),
                portrait = true,
            ),
        )

        // Falls back to status activeLayoutMode when snapshot has NONE.
        assertEquals(
            BridgeClient.LAYOUT_SPLIT_3_MASTER,
            SplitTopologyLayout.resolveActiveTopology(
                idle.copy(activeLayoutMode = "split_3_master"),
                emptySnap,
                portrait = false,
            ),
        )

        // Falls back to splitActive with orientation when no tiling topology is set.
        assertEquals(
            BridgeClient.LAYOUT_SPLIT_2_H,
            SplitTopologyLayout.resolveActiveTopology(idle.copy(splitActive = 1), emptySnap, portrait = false),
        )
        assertEquals(
            BridgeClient.LAYOUT_SPLIT_2_V,
            SplitTopologyLayout.resolveActiveTopology(idle.copy(splitActive = 1), emptySnap, portrait = true),
        )

        // Otherwise NONE.
        assertEquals(
            BridgeClient.LAYOUT_NONE,
            SplitTopologyLayout.resolveActiveTopology(idle, emptySnap, portrait = false),
        )

        // Multi-app topology classifier.
        assertTrue(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_SPLIT_3_MASTER))
        assertTrue(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_SPLIT_3_COLS))
        assertTrue(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_GRID_2X2))
        assertFalse(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_SPLIT_2_H))
        assertFalse(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_SPLIT_2_V))
        assertFalse(SplitTopologyLayout.isMultiAppTopology(BridgeClient.LAYOUT_NONE))
    }

    @Test
    fun syncStateFromDaemonNormalizesAndClampsRatiosAcrossAllTopologies() {
        // 3 columns with empty snapshot ratios -> 33 / 34 / 33.
        val colsState = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_COLS)
        SplitTopologyLayout.syncStateFromDaemon(colsState, BridgeClient.Status(), BridgeClient.WindowStateSnapshot())
        assertEquals(33, colsState.col0Pct)
        assertEquals(34, colsState.col1Pct)
        assertEquals(0.33f, colsState.primaryRatio, 0.001f)

        // 3 columns with explicit out-of-range ratios -> clamped safely.
        SplitTopologyLayout.syncStateFromDaemon(
            colsState,
            BridgeClient.Status(),
            BridgeClient.WindowStateSnapshot(ratios = listOf(10, 85, 5)),
        )
        assertEquals(20, colsState.col0Pct)
        assertEquals(60, colsState.col1Pct)

        // 1+2 Master & 2x2 Grid -> primary & secondary clamped to [0.25, 0.75].
        val masterState = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_MASTER)
        SplitTopologyLayout.syncStateFromDaemon(
            masterState,
            BridgeClient.Status(),
            BridgeClient.WindowStateSnapshot(ratios = listOf(90, 10)),
        )
        assertEquals(0.75f, masterState.primaryRatio, 0.001f)
        assertEquals(0.25f, masterState.secondaryRatio, 0.001f)

        val gridState = SplitDividerState(topology = BridgeClient.LAYOUT_GRID_2X2)
        SplitTopologyLayout.syncStateFromDaemon(
            gridState,
            BridgeClient.Status(),
            BridgeClient.WindowStateSnapshot(ratios = listOf(60, 40)),
        )
        assertEquals(0.60f, gridState.primaryRatio, 0.001f)
        assertEquals(0.40f, gridState.secondaryRatio, 0.001f)

        // 2-split falls back to Status.splitRatioPct when snapshot ratios are empty.
        val twoState = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_H)
        SplitTopologyLayout.syncStateFromDaemon(
            twoState,
            BridgeClient.Status(splitRatioPct = 65),
            BridgeClient.WindowStateSnapshot(),
        )
        assertEquals(0.65f, twoState.primaryRatio, 0.001f)

        // 2-split prefers snapshot ratios when present and clamps extremes.
        SplitTopologyLayout.syncStateFromDaemon(
            twoState,
            BridgeClient.Status(splitRatioPct = 65),
            BridgeClient.WindowStateSnapshot(ratios = listOf(15)),
        )
        assertEquals(0.25f, twoState.primaryRatio, 0.001f)
    }

    @Test
    fun computePlacementsHandlesAllFiveTopologiesAndBoundsClamping() {
        // 1. SPLIT_2_H (landscape horizontal split -> single vertical divider).
        val (p2h, s2h) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_H, primaryRatio = 0.50f),
            surfW = 1920, surfH = 1080, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(DividerPlacement(948, 492, 24, 96, "⋮\n50"), p2h)
        assertNull(s2h)

        // Clamping extreme ratios for SPLIT_2_H.
        val (p2hLow, _) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_H, primaryRatio = 0.05f),
            surfW = 1920, surfH = 1080, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(468, p2hLow.x)
        assertEquals("⋮\n25", p2hLow.label)

        // 2. SPLIT_2_V (portrait vertical split -> single horizontal divider).
        val (p2v, s2v) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_V, primaryRatio = 0.60f),
            surfW = 1080, surfH = 1920, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(DividerPlacement(492, 1140, 96, 24, "⋯ 60%"), p2v)
        assertNull(s2v)

        // 3. SPLIT_3_MASTER (left master + right top/bottom stack).
        val (p3m, s3m) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_MASTER, primaryRatio = 0.60f, secondaryRatio = 0.40f),
            surfW = 2000, surfH = 1000, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(DividerPlacement(1188, 452, 24, 96, "⋮\n60"), p3m)
        assertNotNull(s3m)
        assertEquals(DividerPlacement(1552, 388, 96, 24, "⋯ 40%"), s3m)

        // 4. SPLIT_3_COLS (three columns -> two vertical dividers, clamped min 20% each).
        val (p3c, s3c) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_COLS, col0Pct = 25, col1Pct = 45),
            surfW = 2000, surfH = 1000, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(DividerPlacement(488, 452, 24, 96, "⋮\n25"), p3c)
        assertEquals(DividerPlacement(1388, 452, 24, 96, "⋮\n30"), s3c)

        val (p3cClamped, s3cClamped) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_COLS, col0Pct = 5, col1Pct = 95),
            surfW = 2000, surfH = 1000, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals("⋮\n20", p3cClamped.label)
        assertEquals("⋮\n20", s3cClamped!!.label)
        assertTrue("Second divider must remain strictly to the right of first", s3cClamped.x > p3cClamped.x)

        // 5. GRID_2X2 (vertical divider in top-center + horizontal divider in left-center).
        val (pGrid, sGrid) = SplitTopologyLayout.computePlacements(
            SplitDividerState(topology = BridgeClient.LAYOUT_GRID_2X2, primaryRatio = 0.50f, secondaryRatio = 0.60f),
            surfW = 2000, surfH = 1000, vW = 24, vH = 96, hW = 96, hH = 24,
        )
        assertEquals(DividerPlacement(988, 252, 24, 96, "⋮\n50"), pGrid)
        assertEquals(DividerPlacement(452, 588, 96, 24, "⋯ 60%"), sGrid)
    }

    @Test
    fun computeMenuPositionAndApplyDragDeltaClampCleanly() {
        // Inactive menu sits centered near bottom.
        val inactivePos = SplitTopologyLayout.computeMenuPosition(
            isActive = false,
            state = SplitDividerState(),
            surfW = 1920, surfH = 1080,
            menuW = 400, menuH = 200,
            margin = 20, inactiveBottomPad = 80,
            portraitGapAboveDivider = 30, portraitBottomPad = 60, landscapeVerticalShift = 40,
        )
        assertEquals(760 to 800, inactivePos)

        // Active landscape menu tracks primary divider X with margin clamping.
        val landPos = SplitTopologyLayout.computeMenuPosition(
            isActive = true,
            state = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_H, primaryRatio = 0.25f),
            surfW = 1000, surfH = 800,
            menuW = 600, menuH = 200,
            margin = 24, inactiveBottomPad = 80,
            portraitGapAboveDivider = 30, portraitBottomPad = 60, landscapeVerticalShift = 40,
        )
        // divX = 250, 250 - 300 = -50 -> clamped to margin 24; y = (800 - 200)/2 - 40 = 260
        assertEquals(24 to 260, landPos)

        // Active portrait menu centers X and stays above horizontal divider.
        val portPos = SplitTopologyLayout.computeMenuPosition(
            isActive = true,
            state = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_2_V, primaryRatio = 0.50f),
            surfW = 1000, surfH = 1600,
            menuW = 400, menuH = 200,
            margin = 24, inactiveBottomPad = 80,
            portraitGapAboveDivider = 30, portraitBottomPad = 60, landscapeVerticalShift = 40,
        )
        assertEquals(300 to 570, portPos)

        // Drag delta clamping in 3-column mode preserves divider 2 position when dragging primary.
        val colState = SplitDividerState(topology = BridgeClient.LAYOUT_SPLIT_3_COLS, col0Pct = 33, col1Pct = 34)
        SplitTopologyLayout.applyDragDelta(colState, DividerAxis.PRIMARY, isCols = true, rawRatio = 0.25f)
        assertEquals(25, colState.col0Pct)
        assertEquals(42, colState.col1Pct) // 25 + 42 == 67 preserved

        // Dragging primary too far right still leaves >= 20% for col1.
        SplitTopologyLayout.applyDragDelta(colState, DividerAxis.PRIMARY, isCols = true, rawRatio = 0.90f)
        assertEquals(47, colState.col0Pct)
        assertEquals(20, colState.col1Pct)

        // Dragging secondary respects [col0 + 20, 80].
        SplitTopologyLayout.applyDragDelta(colState, DividerAxis.SECONDARY, isCols = true, rawRatio = 0.95f)
        assertEquals(47, colState.col0Pct)
        assertEquals(33, colState.col1Pct) // 47 + 33 == 80 max

        // Non-3-col drags clamp both axes strictly to [0.25f, 0.75f].
        val gridState = SplitDividerState(topology = BridgeClient.LAYOUT_GRID_2X2)
        SplitTopologyLayout.applyDragDelta(gridState, DividerAxis.PRIMARY, isCols = false, rawRatio = 0.05f)
        SplitTopologyLayout.applyDragDelta(gridState, DividerAxis.SECONDARY, isCols = false, rawRatio = 0.99f)
        assertEquals(0.25f, gridState.primaryRatio, 0.001f)
        assertEquals(0.75f, gridState.secondaryRatio, 0.001f)
    }

    @Test
    fun overlayConstantsIdentifiesSystemAndLauncherPackagesOnly() {
        assertTrue(OverlayConstants.isSystemOrLauncherPkg(null))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg(""))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("   "))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("com.extbridge.helper"))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("android"))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("com.android.systemui"))

        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.tencent.mm"))
        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.android.settings"))
        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.extbridge.helper.other"))
    }

    @Test
    fun mirrorMetricsSamplerFormatsSpeedRatesAndCountdowns() {
        // Bytes / sec tier (including negative clamp).
        assertEquals("0 B/s", MirrorMetricsSampler.formatSpeedRate(-100.0))
        assertEquals("0 B/s", MirrorMetricsSampler.formatSpeedRate(0.0))
        assertEquals("512 B/s", MirrorMetricsSampler.formatSpeedRate(512.4))

        // Kilobytes / sec tier.
        assertEquals("1.0 KB/s", MirrorMetricsSampler.formatSpeedRate(1024.0))
        assertEquals("1.5 KB/s", MirrorMetricsSampler.formatSpeedRate(1536.0))
        assertEquals("512.0 KB/s", MirrorMetricsSampler.formatSpeedRate(512.0 * 1024.0))

        // Megabytes / sec tier.
        assertEquals("1.00 MB/s", MirrorMetricsSampler.formatSpeedRate(1024.0 * 1024.0))
        assertEquals("2.50 MB/s", MirrorMetricsSampler.formatSpeedRate(2.5 * 1024.0 * 1024.0))

        // Countdown timer formatting.
        assertEquals("00:00", MirrorMetricsSampler.formatCountdown(-15L))
        assertEquals("00:00", MirrorMetricsSampler.formatCountdown(0L))
        assertEquals("00:05", MirrorMetricsSampler.formatCountdown(5L))
        assertEquals("00:59", MirrorMetricsSampler.formatCountdown(59L))
        assertEquals("01:00", MirrorMetricsSampler.formatCountdown(60L))
        assertEquals("25:00", MirrorMetricsSampler.formatCountdown(1500L))
        assertEquals("61:01", MirrorMetricsSampler.formatCountdown(3661L))
    }
}
