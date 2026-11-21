package com.extbridge.daemon;

import com.extbridge.daemon.DaemonConfig.DisplayStatusSnapshot;
import com.extbridge.daemon.DaemonConfig.PackagePolicy;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.window.DesktopWindowManager;
import com.extbridge.daemon.window.DesktopWindowManager.TwoSlotSplitSnapshot;
import com.extbridge.daemon.window.TilingLayoutEngine;
import com.extbridge.daemon.window.TilingLayoutEngine.WindowRect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DaemonConfigTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testDisplayConfSaveLoadRoundTripAndNeverBootsInMirrorMode() throws Exception {
        File conf = new File(tmp.getRoot(), "extbridge.conf");
        File apps = new File(tmp.getRoot(), "extbridge_apps.conf");

        DaemonConfig writer = new DaemonConfig(conf, apps);
        writer.virtW = 3840;
        writer.virtH = 2160;
        writer.virtDpi = 320;
        writer.virtFps = 120.0f;
        writer.fullscreenPolicy = 0;
        writer.virtRot = 1;
        writer.mirrorMode = BridgeProtocol.MIRROR_MODE_MIRROR;
        writer.mirrorSideMode = BridgeProtocol.MIRROR_SIDE_CENTER_DUAL;
        writer.saveConf();

        DaemonConfig reader = new DaemonConfig(conf, apps);
        reader.loadConf();
        assertTrue(reader.hasUserConf);
        assertEquals(3840, reader.virtW);
        assertEquals(2160, reader.virtH);
        assertEquals(320, reader.virtDpi);
        assertEquals(120.0f, reader.virtFps, 0.01f);
        assertEquals(0, reader.fullscreenPolicy);
        assertEquals(1, reader.virtRot);
        assertEquals(BridgeProtocol.MIRROR_MODE_DEX, reader.mirrorMode);
        assertEquals(BridgeProtocol.MIRROR_SIDE_CENTER_DUAL, reader.mirrorSideMode);

        // Even if someone manually edits column 7 to 1, loadConf never boots into mirror lock.
        Files.write(conf.toPath(), "1920,1080,240,60.0,1,2,1,2\n".getBytes(StandardCharsets.UTF_8));
        DaemonConfig manual = new DaemonConfig(conf, apps);
        manual.loadConf();
        assertEquals(1920, manual.virtW);
        assertEquals(1080, manual.virtH);
        assertEquals(2, manual.virtRot);
        assertEquals(BridgeProtocol.MIRROR_MODE_DEX, manual.mirrorMode);
        assertEquals(BridgeProtocol.MIRROR_SIDE_ALIGN_RIGHT, manual.mirrorSideMode);

        // Invalid dimensions are ignored and do not mark hasUserConf.
        Files.write(conf.toPath(), "100,100,240,60.0\n".getBytes(StandardCharsets.UTF_8));
        DaemonConfig bad = new DaemonConfig(conf, apps);
        bad.loadConf();
        assertFalse(bad.hasUserConf);
        assertEquals(2560, bad.virtW);
    }

    @Test
    public void testAppModesFirstRunSeedLegacyPruningAndCrud() throws Exception {
        File conf = new File(tmp.getRoot(), "extbridge.conf");
        File apps = new File(tmp.getRoot(), "extbridge_apps.conf");

        DaemonConfig first = new DaemonConfig(conf, apps);
        assertTrue("First run must seed default app conf", first.loadAppModes());
        assertEquals(BridgeProtocol.APP_MODE_LAND_FULL, first.getAppMode(DaemonConfig.DEFAULT_FIRST_APP_PKG));

        DaemonConfig second = new DaemonConfig(conf, apps);
        assertFalse("Subsequent run must not report firstConf", second.loadAppModes());
        assertEquals(BridgeProtocol.APP_MODE_LAND_FULL, second.getAppMode(DaemonConfig.DEFAULT_FIRST_APP_PKG));

        assertTrue(second.setAppMode("com.example.video", BridgeProtocol.APP_MODE_PORT_COMPAT));
        assertFalse(second.setAppMode("android", BridgeProtocol.APP_MODE_LAND_FULL));
        assertFalse(second.setAppMode(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.APP_MODE_LAND_FULL));
        assertFalse(second.setAppMode("com.android.settings", BridgeProtocol.APP_MODE_LAND_FULL));
        assertFalse(second.setAppMode("bad;pkg", BridgeProtocol.APP_MODE_LAND_FULL));

        assertEquals(BridgeProtocol.APP_MODE_PORT_COMPAT, second.getAppMode("com.example.video"));
        String summary = second.serializeAppModesSummary();
        assertTrue(summary.contains("com.example.video=2"));
        assertTrue(summary.contains(DaemonConfig.DEFAULT_FIRST_APP_PKG + "=1"));

        // Setting APP_MODE_DEFAULT removes the package cleanly.
        assertTrue(second.setAppMode("com.example.video", BridgeProtocol.APP_MODE_DEFAULT));
        assertEquals(BridgeProtocol.APP_MODE_DEFAULT, second.getAppMode("com.example.video"));

        // Legacy mode=3 lines are pruned and rewritten cleanly on load.
        String legacy = "com.example.keep=1\ncom.example.legacy=3\ncom.example.port=2\ninvalid=1\n";
        Files.write(apps.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
        DaemonConfig pruned = new DaemonConfig(conf, apps);
        assertFalse(pruned.loadAppModes());
        assertEquals(BridgeProtocol.APP_MODE_LAND_FULL, pruned.getAppMode("com.example.keep"));
        assertEquals(BridgeProtocol.APP_MODE_PORT_COMPAT, pruned.getAppMode("com.example.port"));
        assertEquals(BridgeProtocol.APP_MODE_DEFAULT, pruned.getAppMode("com.example.legacy"));
        String rewritten = new String(Files.readAllBytes(apps.toPath()), StandardCharsets.UTF_8);
        assertFalse(rewritten.contains("com.example.legacy"));

        Set<String> cleared = pruned.clearAllAppModes();
        assertTrue(cleared.contains("com.example.keep"));
        assertTrue(pruned.getConfiguredAppModePackages().isEmpty());
    }

    @Test
    public void testDisplayStatusSnapshotConsistency() {
        DaemonConfig cfg = new DaemonConfig(new File(tmp.getRoot(), "c"), new File(tmp.getRoot(), "a"));
        cfg.extId = 2;
        cfg.vdId = 7;
        cfg.physW = 3840;
        cfg.physH = 2160;
        cfg.physFps = 60.0f;
        cfg.virtW = 2560;
        cfg.virtH = 1440;
        cfg.virtDpi = 240;
        cfg.virtFps = 60.0f;
        cfg.renderMode = "BBQ";
        DisplayStatusSnapshot snap = cfg.snapshotDisplayStatus();
        assertEquals(2, snap.extId);
        assertEquals(7, snap.vdId);
        assertEquals(3840, snap.physW);
        assertEquals("BBQ", snap.renderMode);
        assertEquals(BridgeProtocol.VD_FLAGS_DEX_FIXED, cfg.getVirtualDisplayFlags());
        assertEquals(0, cfg.getVirtualDisplayFlags() & BridgeProtocol.VD_FLAG_PRESENTATION);
        cfg.fullscreenPolicy = 0;
        assertEquals(BridgeProtocol.VD_FLAGS_COMPAT, cfg.getVirtualDisplayFlags());
        assertEquals(0, cfg.getVirtualDisplayFlags() & BridgeProtocol.VD_FLAG_PRESENTATION);
    }

    @Test
    public void testPackagePolicyUnifiedRules() {
        assertTrue(PackagePolicy.isValidPackageName("com.example.app_1"));
        assertFalse(PackagePolicy.isValidPackageName(null));
        assertFalse(PackagePolicy.isValidPackageName("ab"));
        assertFalse(PackagePolicy.isValidPackageName("single"));
        assertFalse(PackagePolicy.isValidPackageName("com.example;rm"));

        assertTrue(PackagePolicy.isValidClassName("com.example.MainActivity$Inner"));
        assertFalse(PackagePolicy.isValidClassName("MainActivity"));

        assertTrue(PackagePolicy.shouldSkipTaskPkg(BridgeBuildConfig.HELPER_PACKAGE));
        assertTrue(PackagePolicy.shouldSkipTaskPkg("com.miui.home"));
        assertFalse(PackagePolicy.shouldSkipTaskPkg("com.example.app"));

        assertTrue(PackagePolicy.isProtectedSystemTaskPkg("com.android.systemui"));
        assertTrue(PackagePolicy.isProtectedSystemTaskPkg("com.google.android.permissioncontroller"));
        assertTrue(PackagePolicy.isProtectedSystemTaskPkg("com.android.packageinstaller"));
        assertTrue(PackagePolicy.isProtectedSystemTaskPkg("com.android.keyguard"));
        assertFalse(PackagePolicy.isProtectedSystemTaskPkg("com.example.app"));

        assertTrue(PackagePolicy.shouldCompatOverridePkg("com.example.app"));
        assertFalse(PackagePolicy.shouldCompatOverridePkg("com.android.settings"));
        assertFalse(PackagePolicy.shouldCompatOverridePkg(BridgeBuildConfig.HELPER_PACKAGE));

        assertTrue(PackagePolicy.isBlockedLaunchPkg("com.miui.home"));
        assertFalse(PackagePolicy.isBlockedLaunchPkg("com.example.app"));

        assertTrue(PackagePolicy.isHelperLauncherComponent(
                BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_LAUNCHER_CLASS));
        assertFalse(PackagePolicy.isHelperLauncherComponent("com.example.app", BridgeProtocol.HELPER_LAUNCHER_CLASS));

        assertTrue(PackagePolicy.isAllowedSettingsAction("android.settings.WIFI_SETTINGS"));
        assertFalse(PackagePolicy.isAllowedSettingsAction("android.intent.action.FACTORY_RESET"));
    }

    @Test
    public void testDesktopWindowManagerUnifiedTwoSlotSplitSnapshot() {
        DesktopWindowManager wm = new DesktopWindowManager();
        TwoSlotSplitSnapshot idle = wm.getTwoSlotSplitSnapshot(55);
        assertFalse(idle.active);
        assertEquals(55, idle.ratioPct);
        assertEquals("", idle.slot0Pkg);
        assertEquals("", idle.slot1Pkg);
        assertEquals(-1, idle.emptySlotIndex());

        // 2-slot horizontal tiling maps directly to legacy code 35 split snapshot.
        WindowRect[] rects2 = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_2_H, 2560, 1440, 0, 8, new int[]{60, 40});
        wm.setTilingState(TilingLayoutEngine.TOPOLOGY_SPLIT_2_H,
                Arrays.asList("com.example.left", ""), new int[]{101, -1}, new int[]{60, 40}, rects2);
        TwoSlotSplitSnapshot oneEmpty = wm.getTwoSlotSplitSnapshot(50);
        assertTrue(oneEmpty.active);
        assertEquals(60, oneEmpty.ratioPct);
        assertEquals("com.example.left", oneEmpty.slot0Pkg);
        assertEquals("", oneEmpty.slot1Pkg);
        assertEquals(101, oneEmpty.slot0TaskId);
        assertEquals(1, oneEmpty.emptySlotIndex());
        assertTrue(wm.isPkgInActiveTiling("com.example.left"));
        assertFalse(wm.isPkgInActiveTiling("com.example.other"));

        // 3-slot tiling is NOT reported as a legacy 2-slot split.
        WindowRect[] rects3 = TilingLayoutEngine.computeSlots(
                TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER, 2560, 1440, 0, 8, new int[]{50, 50, 50});
        wm.setTilingState(TilingLayoutEngine.TOPOLOGY_SPLIT_3_MASTER,
                Arrays.asList("com.a.one", "com.b.two", "com.c.three"),
                new int[]{11, 12, 13}, new int[]{50, 50, 50}, rects3);
        TwoSlotSplitSnapshot threeSlot = wm.getTwoSlotSplitSnapshot(50);
        assertFalse(threeSlot.active);
        assertTrue(wm.isPkgInActiveTiling("com.c.three"));
    }
}
