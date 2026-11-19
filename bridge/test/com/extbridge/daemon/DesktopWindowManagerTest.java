package com.extbridge.daemon;

import com.extbridge.daemon.stream.SimpleJson;
import com.extbridge.daemon.window.DesktopWindowManager;
import com.extbridge.daemon.window.TilingLayoutEngine;
import com.extbridge.daemon.window.TilingLayoutEngine.WindowRect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DesktopWindowManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testDefaultsAndAdaptiveSingleCenteredBounds() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "extbridge_win_prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        assertTrue("Adaptive single must be enabled by default", wm.isAdaptiveSingleEnabled());
        assertEquals(460, wm.getDefaultSingleWidthDp());
        assertEquals(DesktopWindowManager.MODE_ADAPTIVE_SINGLE, wm.resolveActiveLayoutMode());

        // On 2560x1440 @ 240dpi: 460dp = 460 * 1.5 = 690px; min 28% = 717px -> centered around x=1280
        int dock = 108;
        WindowRect r = wm.computeAdaptiveSingleBounds(2560, 1440, 240, dock, "com.tencent.mm", 0, 0);
        assertTrue(r.left > 0 && r.right < 2560);
        assertEquals(1280, r.centerX(), 2);
        assertTrue("Must avoid bottom dock", r.bottom <= 1440 - dock);
        assertTrue("Width must respect 28% floor", r.width() >= Math.round(2560 * 0.28f));

        // Explicit widthPct = 55%
        WindowRect r55 = wm.computeAdaptiveSingleBounds(2560, 1440, 240, dock, "com.tencent.mm", 55, 0);
        assertEquals(Math.round(2560 * 0.55f), r55.width(), 2);
        assertEquals(1280, r55.centerX(), 2);

        // Explicit widthPct = 100% -> fullscreen width
        WindowRect rFull = wm.computeAdaptiveSingleBounds(2560, 1440, 240, dock, "com.tencent.mm", 100, 0);
        assertEquals(0, rFull.left);
        assertEquals(2560, rFull.right);
        assertEquals(0, rFull.top);
        assertEquals(1440 - dock, rFull.bottom);
    }

    @Test
    public void testFiveTierWidthSnapping() {
        assertEquals(32, DesktopWindowManager.snapWidthPct(31, 3));
        assertEquals(42, DesktopWindowManager.snapWidthPct(43, 3));
        assertEquals(55, DesktopWindowManager.snapWidthPct(54, 3));
        assertEquals(72, DesktopWindowManager.snapWidthPct(74, 3));
        assertEquals(100, DesktopWindowManager.snapWidthPct(98, 3));
        assertEquals(62, DesktopWindowManager.snapWidthPct(62, 3));
        assertEquals(28, DesktopWindowManager.snapWidthPct(10, 3));
    }

    @Test
    public void testFreeformCascadeAvoidsIdenticalOverlap() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        WindowRect r1 = wm.computeNextFreeformBounds(2560, 1440, 240, 100, "com.app.one");
        wm.putManagedWindow(101, "com.app.one", DesktopWindowManager.MODE_FREEFORM, r1, true, -1);

        WindowRect r2 = wm.computeNextFreeformBounds(2560, 1440, 240, 100, "com.app.two");
        wm.putManagedWindow(102, "com.app.two", DesktopWindowManager.MODE_FREEFORM, r2, true, -1);

        WindowRect r3 = wm.computeNextFreeformBounds(2560, 1440, 240, 100, "com.app.three");
        wm.putManagedWindow(103, "com.app.three", DesktopWindowManager.MODE_FREEFORM, r3, true, -1);

        assertNotEquals("Window 2 should cascade away from window 1", r1.left, r2.left);
        assertNotEquals("Window 3 should cascade away from window 2", r2.left, r3.left);
        assertTrue(r1.right <= 2560 && r1.bottom <= 1340);
        assertTrue(r2.right <= 2560 && r2.bottom <= 1340);
        assertTrue(r3.right <= 2560 && r3.bottom <= 1340);
        assertEquals(DesktopWindowManager.MODE_FREEFORM, wm.resolveActiveLayoutMode());
    }

    @Test
    public void testManagedExemptionAndPersistenceAndBinder50Json() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "extbridge_win_prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        wm.setAdaptiveSingleConfig(true, 520);
        wm.rememberAppPref("com.tencent.mm", DesktopWindowManager.MODE_ADAPTIVE_SINGLE, 42, 0, null);
        wm.rememberAppPref("com.quark.browser", DesktopWindowManager.MODE_FULLSCREEN, 100, 0, null);
        wm.rememberAppPref("com.demo.notes", DesktopWindowManager.MODE_FREEFORM, 0, 0,
                new WindowRect(120, 80, 920, 1000));

        // Reload fresh instance from disk
        DesktopWindowManager reloaded = new DesktopWindowManager(prefs);
        assertTrue(reloaded.isAdaptiveSingleEnabled());
        assertEquals(520, reloaded.getDefaultSingleWidthDp());
        DesktopWindowManager.AppWindowPref mmPref = reloaded.getAppPref("com.tencent.mm");
        assertNotNull(mmPref);
        assertEquals(42, mmPref.widthPct);
        assertEquals(DesktopWindowManager.MODE_ADAPTIVE_SINGLE,
                reloaded.resolveLaunchMode("com.tencent.mm", DesktopWindowManager.INT_MODE_AUTO));
        assertEquals(DesktopWindowManager.MODE_FULLSCREEN,
                reloaded.resolveLaunchMode("com.quark.browser", DesktopWindowManager.INT_MODE_AUTO));
        assertFalse(reloaded.shouldAutoApplyAdaptiveSingle(77, "com.quark.browser"));
        assertTrue(reloaded.shouldAutoApplyAdaptiveSingle(78, "com.tencent.mm"));

        // Managed window exemption checks
        WindowRect mmRect = new WindowRect(500, 20, 1420, 1000);
        reloaded.putManagedWindow(12, "com.tencent.mm", DesktopWindowManager.MODE_ADAPTIVE_SINGLE, mmRect, true, -1);
        assertTrue(reloaded.isTaskManagedInMultiWindow(12, "com.tencent.mm"));
        assertFalse(reloaded.shouldAutoApplyAdaptiveSingle(12, "com.tencent.mm"));

        // Serialize Binder code 50 JSON and validate every required key
        String json = reloaded.toManagedWindowsJson();
        SimpleJson.JsonObjectView obj = SimpleJson.parseObject(json);
        assertEquals("ADAPTIVE_SINGLE", obj.getString("layoutMode", ""));
        assertTrue(obj.getBoolean("adaptiveSingleEnabled", false));
        assertEquals(520, obj.getInt("defaultSingleWidthDp", 0));
        assertEquals("NONE", obj.getString("tilingTopology", ""));
        assertEquals(Arrays.asList(50, 50), obj.getIntList("ratios"));
        List<SimpleJson.JsonObjectView> wins = obj.getObjectList("windows");
        assertEquals(1, wins.size());
        SimpleJson.JsonObjectView w0 = wins.get(0);
        assertEquals(12, w0.getInt("taskId", -1));
        assertEquals("com.tencent.mm", w0.getString("pkg", ""));
        assertEquals("ADAPTIVE_SINGLE", w0.getString("mode", ""));
        assertEquals(500, w0.getInt("left", -1));
        assertEquals(20, w0.getInt("top", -1));
        assertEquals(1420, w0.getInt("right", -1));
        assertEquals(1000, w0.getInt("bottom", -1));
        assertTrue(w0.getBoolean("focused", false));
        assertFalse(w0.getBoolean("minimized", true));

        // Tiling mode updates layoutMode and exempts tiled tasks
        WindowRect[] slots = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER, 2560, 1440, 100, new int[]{55, 50});
        reloaded.setTilingState(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER,
                Arrays.asList("com.a.one", "com.b.two", "com.c.three"),
                new int[]{21, 22, 23},
                new int[]{55, 50},
                slots);
        assertEquals("SPLIT_3_MASTER", reloaded.resolveActiveLayoutMode());
        assertTrue(reloaded.isTaskManagedInMultiWindow(22, "com.b.two"));

        // Prune dead tasks
        Set<Integer> alive = new HashSet<>(Arrays.asList(21, 23));
        reloaded.pruneDeadTasks(alive);
        assertEquals(2, reloaded.getManagedWindowsSnapshot().size());
    }

    @Test
    public void testSwitchingSingleAppKeepsPreviousExemptFromFullscreenHijack() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "extbridge_win_prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        WindowRect rectA = new WindowRect(615, 0, 1305, 1080);
        WindowRect rectB = new WindowRect(615, 0, 1305, 1080);
        wm.putManagedWindow(8092, "com.tencent.mm", DesktopWindowManager.MODE_ADAPTIVE_SINGLE, rectA, true, -1);
        wm.putManagedWindow(8113, "com.coolapk.market", DesktopWindowManager.MODE_ADAPTIVE_SINGLE, rectB, true, -1);

        // Previous background single app must remain tracked (minimized) so fullscreen rescue never yanks it to front
        assertTrue(wm.isTaskManagedInMultiWindow(8092, "com.tencent.mm"));
        assertTrue(wm.isTaskManagedInMultiWindow(8113, "com.coolapk.market"));
        assertTrue(wm.getManagedWindow(8092).minimized);
        assertFalse(wm.getManagedWindow(8092).focused);
        assertFalse(wm.getManagedWindow(8113).minimized);
        assertTrue(wm.getManagedWindow(8113).focused);

        // Returning to desktop minimizes all single-app windows so edge handles hide cleanly
        wm.minimizeAllSingleWindows();
        assertTrue(wm.getManagedWindow(8113).minimized);
        assertFalse(wm.getManagedWindow(8113).focused);

        // Mutating a returned ManagedWindow must NOT leak into DesktopWindowManager internal state
        DesktopWindowManager.ManagedWindow copy = wm.getManagedWindow(8113);
        copy.minimized = false;
        copy.focused = true;
        copy.mode = DesktopWindowManager.MODE_FREEFORM;
        DesktopWindowManager.ManagedWindow fresh = wm.getManagedWindow(8113);
        assertTrue("Internal minimized state must be protected by defensive copy", fresh.minimized);
        assertFalse("Internal focused state must be protected by defensive copy", fresh.focused);
        assertEquals(DesktopWindowManager.MODE_ADAPTIVE_SINGLE, fresh.mode);
    }

    @Test
    public void testAdaptiveSingleVerticalBreathingMarginsWhenDockIsZero() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "extbridge_win_prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        // 240dpi -> 16dp = 24px symmetrical top/bottom breathing margin
        WindowRect r240 = wm.computeAdaptiveSingleBounds(2560, 1440, 240, 0, "com.tencent.mm", 0, 0);
        assertTrue(r240.left > 0 && r240.right < 2560);
        assertEquals(24, r240.top);
        assertEquals(1440 - 24, r240.bottom);

        // 160dpi -> 16px breathing margin
        WindowRect r160 = wm.computeAdaptiveSingleBounds(1920, 1080, 160, 0, "com.tencent.mm", 55, 0);
        assertEquals(16, r160.top);
        assertEquals(1080 - 16, r160.bottom);

        // 120dpi -> clamped to 12px floor
        WindowRect r120 = wm.computeAdaptiveSingleBounds(1920, 1080, 120, 0, "com.tencent.mm", 42, 0);
        assertEquals(12, r120.top);
        assertEquals(1080 - 12, r120.bottom);

        // 480dpi -> clamped to 32px ceiling
        WindowRect r480 = wm.computeAdaptiveSingleBounds(2560, 1440, 480, 0, "com.tencent.mm", 55, 0);
        assertEquals(32, r480.top);
        assertEquals(1440 - 32, r480.bottom);

        // Fullscreen width (100%) keeps exact 0..screenH without vertical margin
        WindowRect rFull = wm.computeAdaptiveSingleBounds(2560, 1440, 240, 0, "com.tencent.mm", 100, 0);
        assertEquals(0, rFull.left);
        assertEquals(0, rFull.top);
        assertEquals(2560, rFull.right);
        assertEquals(1440, rFull.bottom);
    }

    @Test
    public void testTilingRatiosAndLockConsistentSnapshot() throws Exception {
        File prefs = new File(tmpDir.newFolder(), "extbridge_win_prefs.conf");
        DesktopWindowManager wm = new DesktopWindowManager(prefs);

        // Inactive tiling returns empty snapshot lists
        DesktopWindowManager.TilingSnapshot idle = wm.getTilingSnapshot();
        assertFalse(idle.active);
        assertEquals(TilingLayoutEngine.TOPOLOGY_NONE, idle.topology);
        assertTrue(idle.slots.isEmpty());
        assertTrue(idle.ratios.isEmpty());

        // Active 2-slot tiling exposes exact 2 slots & ratios, with defensive clone on getTilingRatios()
        WindowRect[] slots2 = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_H, 2560, 1440, 0, new int[]{62, 38});
        wm.setTilingState(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_H,
                Arrays.asList("com.tencent.mm", "com.quark.browser"),
                new int[]{101, 102},
                new int[]{62, 38},
                slots2);
        int[] ratios = wm.getTilingRatios();
        assertArrayEquals(new int[]{62, 38}, ratios);
        ratios[0] = 99;
        assertArrayEquals("Mutating returned ratio array must not affect internal state",
                new int[]{62, 38}, wm.getTilingRatios());

        DesktopWindowManager.TilingSnapshot snap2 = wm.getTilingSnapshot();
        assertTrue(snap2.active);
        assertEquals(TilingLayoutEngine.TOPOLOGY_SPLIT_2_H, snap2.topology);
        assertEquals(Arrays.asList("com.tencent.mm", "com.quark.browser"), snap2.slots);
        assertEquals(Arrays.asList(62, 38), snap2.ratios);

        // Active 3-slot tiling slices slots to exact topology slotCount (3)
        WindowRect[] slots3 = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER, 2560, 1440, 0, new int[]{55, 45});
        wm.setTilingState(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER,
                Arrays.asList("com.a.one", "com.b.two", "com.c.three"),
                new int[]{21, 22, 23},
                new int[]{55, 45},
                slots3);
        DesktopWindowManager.TilingSnapshot snap3 = wm.getTilingSnapshot();
        assertTrue(snap3.active);
        assertEquals(3, snap3.slots.size());
        assertEquals(Arrays.asList("com.a.one", "com.b.two", "com.c.three"), snap3.slots);
        assertEquals(Arrays.asList(55, 45), snap3.ratios);

        wm.clearTilingState();
        DesktopWindowManager.TilingSnapshot cleared = wm.getTilingSnapshot();
        assertFalse(cleared.active);
        assertTrue(cleared.slots.isEmpty());
        assertTrue(cleared.ratios.isEmpty());
    }
}
