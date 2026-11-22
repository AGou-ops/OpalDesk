package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine.WindowRect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TilingLayoutEngineTest {

    private static void assertValidNonOverlapping(WindowRect[] slots, int screenW, int screenH, int dockPx) {
        int workBottom = screenH - dockPx;
        for (int i = 0; i < slots.length; i++) {
            WindowRect a = slots[i];
            assertFalse("Slot " + i + " must not be empty", a.isEmpty());
            assertTrue("Slot " + i + " left >= 0", a.left >= 0);
            assertTrue("Slot " + i + " top >= 0", a.top >= 0);
            assertTrue("Slot " + i + " right <= screenW", a.right <= screenW);
            assertTrue("Slot " + i + " bottom <= workBottom (dock avoided)", a.bottom <= workBottom);
            for (int j = i + 1; j < slots.length; j++) {
                assertFalse("Slots " + i + " and " + j + " must not overlap", a.intersects(slots[j]));
            }
        }
    }

    @Test
    public void testSplit2HDefaultAndCustomRatio() {
        int w = 2560, h = 1440, dock = 108, gap = 8;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_H, w, h, dock, gap, new int[]{50, 50});
        assertEquals(2, slots.length);
        assertValidNonOverlapping(slots, w, h, dock);
        assertEquals(0, slots[0].left);
        assertEquals(w, slots[1].right);
        assertEquals(gap, slots[1].left - slots[0].right);
        assertEquals(h - dock, slots[0].bottom);
        assertEquals(h - dock, slots[1].bottom);
        assertEquals(slots[0].width(), slots[1].width());

        // Custom 35 / 65 split
        WindowRect[] asym = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_H, w, h, dock, gap, new int[]{35, 65});
        assertValidNonOverlapping(asym, w, h, dock);
        assertEquals(gap, asym[1].left - asym[0].right);
        assertTrue(asym[0].width() < asym[1].width());
        assertEquals(Math.round((w - gap) * 0.35f), asym[0].width(), 2);
    }

    @Test
    public void testSplit2V() {
        int w = 1920, h = 1080, dock = 80, gap = 8;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_V, w, h, dock, gap, new int[]{40, 60});
        assertEquals(2, slots.length);
        assertValidNonOverlapping(slots, w, h, dock);
        assertEquals(0, slots[0].left);
        assertEquals(w, slots[0].right);
        assertEquals(0, slots[1].left);
        assertEquals(w, slots[1].right);
        assertEquals(gap, slots[1].top - slots[0].bottom);
        assertEquals(h - dock, slots[1].bottom);
        assertTrue(slots[0].height() < slots[1].height());
    }

    @Test
    public void testSplit3MasterTwoAndThreeRatios() {
        int w = 2560, h = 1440, dock = 96, gap = 8;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER, w, h, dock, gap, new int[]{60, 40});
        assertEquals(3, slots.length);
        assertValidNonOverlapping(slots, w, h, dock);
        // Slot 0 spans full work height on left
        assertEquals(0, slots[0].left);
        assertEquals(0, slots[0].top);
        assertEquals(h - dock, slots[0].bottom);
        // Exact 8px horizontal gap between master and right stack
        assertEquals(gap, slots[1].left - slots[0].right);
        assertEquals(gap, slots[2].left - slots[0].right);
        // Exact 8px vertical gap between top-right and bottom-right
        assertEquals(gap, slots[2].top - slots[1].bottom);
        assertEquals(w, slots[1].right);
        assertEquals(w, slots[2].right);
        assertEquals(h - dock, slots[2].bottom);
        assertTrue("Master should be wider than right pane at 60%", slots[0].width() > slots[1].width());
        assertTrue("Top-right should be shorter than bottom-right at 40%", slots[1].height() < slots[2].height());

        // 3-element ratios [50, 25, 25]
        WindowRect[] balanced = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER, w, h, dock, gap, new int[]{50, 25, 25});
        assertValidNonOverlapping(balanced, w, h, dock);
        assertEquals(balanced[0].width(), balanced[1].width(), 2);
        assertEquals(balanced[1].height(), balanced[2].height(), 2);
    }

    @Test
    public void testSplit3Cols() {
        int w = 2560, h = 1440, dock = 100, gap = 8;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_COLS, w, h, dock, gap, new int[]{25, 50, 25});
        assertEquals(3, slots.length);
        assertValidNonOverlapping(slots, w, h, dock);
        assertEquals(0, slots[0].left);
        assertEquals(gap, slots[1].left - slots[0].right);
        assertEquals(gap, slots[2].left - slots[1].right);
        assertEquals(w, slots[2].right);
        assertTrue("Center column at 50% must be wider than side columns", slots[1].width() > slots[0].width());
        assertEquals(slots[0].width(), slots[2].width(), 2);
    }

    @Test
    public void testGrid2x2MultiAxisAndExtremeRatios() {
        int w = 2560, h = 1440, dock = 90, gap = 8;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_GRID_2X2, w, h, dock, gap, new int[]{40, 60});
        assertEquals(4, slots.length);
        assertValidNonOverlapping(slots, w, h, dock);
        // Horizontal gaps
        assertEquals(gap, slots[1].left - slots[0].right);
        assertEquals(gap, slots[3].left - slots[2].right);
        // Vertical gaps
        assertEquals(gap, slots[2].top - slots[0].bottom);
        assertEquals(gap, slots[3].top - slots[1].bottom);
        // Exact alignment across columns & rows
        assertEquals(slots[0].right, slots[2].right);
        assertEquals(slots[1].left, slots[3].left);
        assertEquals(slots[0].bottom, slots[1].bottom);
        assertEquals(slots[2].top, slots[3].top);
        assertEquals(h - dock, slots[2].bottom);
        assertEquals(h - dock, slots[3].bottom);

        // Extreme ratios must clamp safely without inverting coordinates
        WindowRect[] clamped = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_GRID_2X2, 800, 600, 60, gap, new int[]{1, 99});
        assertEquals(4, clamped.length);
        assertValidNonOverlapping(clamped, 800, 600, 60);
    }

    @Test
    public void testHelpersAndTopologyNormalization() {
        assertEquals(TilingLayoutEngine.TOPOLOGY_NONE, TilingLayoutEngine.normalizeTopology("EXIT"));
        assertEquals(TilingLayoutEngine.TOPOLOGY_NONE, TilingLayoutEngine.normalizeTopology(null));
        assertEquals(TilingLayoutEngine.TOPOLOGY_GRID_2X2, TilingLayoutEngine.normalizeTopology(" grid_2x2 "));
        assertEquals(0, TilingLayoutEngine.computeSlots("EXIT", 1920, 1080, 80, null).length);

        assertEquals(2, TilingLayoutEngine.slotCountForTopology("SPLIT_2_H"));
        assertEquals(3, TilingLayoutEngine.slotCountForTopology("SPLIT_3_MASTER"));
        assertEquals(4, TilingLayoutEngine.slotCountForTopology("GRID_2X2"));

        assertArrayEquals(new int[]{40, 60}, TilingLayoutEngine.parseRatiosCsv("40, 60"));
        assertArrayEquals(new int[]{33, 34, 33}, TilingLayoutEngine.parseRatiosCsv("33;34;33"));
        List<String> pkgs = TilingLayoutEngine.parsePkgsCsv("com.tencent.mm, com.quark.browser ; com.ss.android.ugc.aweme");
        assertEquals(3, pkgs.size());
        assertEquals("com.tencent.mm", pkgs.get(0));
        assertEquals("com.quark.browser", pkgs.get(1));
        assertEquals("com.ss.android.ugc.aweme", pkgs.get(2));

        int dock = TilingLayoutEngine.computeDefaultDockInsetPx(1440, 240);
        assertTrue("Dock inset should be reasonable", dock >= 48 && dock <= 128);
    }

    @Test
    public void testResolveSlotPackagesPreservesSwapReplaceAndRatioOrder() {
        Predicate<String> eligible = p -> PackagePolicy.isValidPackageName(p)
                && !PackagePolicy.isProtectedSystemTaskPkg(p)
                && !BridgeBuildConfig.HELPER_PACKAGE.equals(p);
        String[] active2 = new String[]{"com.alpha.app", "com.beta.app", "", ""};
        List<String> runningFallback = Arrays.asList("com.beta.app", "com.alpha.app", "com.delta.app");

        // Explicit [B, A] swap must preserve exact order instead of re-sorting
        assertEquals(Arrays.asList("com.beta.app", "com.alpha.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("com.beta.app", "com.alpha.app"), active2, runningFallback, 2, eligible));

        // Ratio-only update (empty reqPkgs) preserves existing active slot order even when running tasks differ
        assertEquals(Arrays.asList("com.alpha.app", "com.beta.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Collections.emptyList(), active2, runningFallback, 2, eligible));

        // Single-slot replacement preserves the untouched slot in its original index
        assertEquals(Arrays.asList("com.alpha.app", "com.gamma.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("", "com.gamma.app"), active2, runningFallback, 2, eligible));
        assertEquals(Arrays.asList("com.gamma.app", "com.beta.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("com.gamma.app", ""), active2, runningFallback, 2, eligible));

        // Assigning slot 1's existing app to slot 0 swaps cleanly with slot 0's previous app
        assertEquals(Arrays.asList("com.beta.app", "com.alpha.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("com.beta.app", ""), active2, runningFallback, 2, eligible));

        // Single app requested with no other running tasks keeps slot 0 and leaves slot 1 empty
        assertEquals(Arrays.asList("com.tencent.mm", ""),
                TilingLayoutEngine.resolveSlotPackages(
                        Collections.singletonList("com.tencent.mm"), null, Collections.emptyList(), 2, eligible));

        // Backfills missing slot from fallback running tasks while preserving explicit slot 0
        assertEquals(Arrays.asList("com.gamma.app", "com.beta.app"),
                TilingLayoutEngine.resolveSlotPackages(
                        Collections.singletonList("com.gamma.app"), null, runningFallback, 2, eligible));

        // Empty state with zero eligible apps returns empty list
        assertTrue(TilingLayoutEngine.resolveSlotPackages(
                Collections.emptyList(), null, Collections.emptyList(), 2, eligible).isEmpty());

        // Filters out protected/helper packages and duplicates
        assertEquals(Arrays.asList("", "com.tencent.mm", ""),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList(BridgeBuildConfig.HELPER_PACKAGE, "com.tencent.mm", "com.tencent.mm"),
                        null, Collections.emptyList(), 3, eligible));

        // Explicit empty-slot preservation mode keeps unassigned slots empty for candidate pickers
        assertEquals(Arrays.asList("com.alpha.app", "", ""),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("com.alpha.app", "", ""),
                        active2, runningFallback, 3, eligible, false));
        assertEquals(Arrays.asList("", ""),
                TilingLayoutEngine.resolveSlotPackages(
                        Arrays.asList("", ""),
                        active2, runningFallback, 2, eligible, false));
    }
}
