package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.compositor.VirtualDisplayPool;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.compositor.WindowSession;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CompositorEngineTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static final class RecordingDriver implements VirtualDisplayPool.DisplayDriver {
        final AtomicInteger nextDisplayId = new AtomicInteger(200);
        int resizeCount = 0;

        @Override
        public CreatedDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure) {
            int id = nextDisplayId.getAndIncrement();
            return new CreatedDisplay("vd-" + id, id, "uid-" + id);
        }

        @Override
        public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
            resizeCount++;
        }

        @Override
        public void setVirtualDisplaySurface(Object handle, Object surface) {}

        @Override
        public void releaseVirtualDisplay(Object handle, int displayId) {}

        @Override
        public byte[] captureTaskSnapshotBytes(int taskId) {
            return ("jpeg-" + taskId).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeRuntime implements CompositorEngine.AppRuntimeBridge {
        final AtomicInteger nextTaskId = new AtomicInteger(900);
        final Set<Integer> aliveTasks = new HashSet<>();
        final Map<Integer, Integer> topTaskByDisplay = new HashMap<>();
        final Set<Integer> removedTasks = new HashSet<>();

        @Override
        public String resolveAppLabel(String pkg) {
            return "Label:" + pkg;
        }

        @Override
        public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
            int tid = (existingTaskId > 0) ? existingTaskId : nextTaskId.getAndIncrement();
            aliveTasks.add(tid);
            topTaskByDisplay.put(displayId, tid);
            return tid;
        }

        @Override
        public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
            return topTaskByDisplay.getOrDefault(displayId, -1);
        }

        @Override
        public Set<Integer> collectAliveTaskIds() {
            return new HashSet<>(aliveTasks);
        }

        @Override
        public void removeTask(int taskId) {
            aliveTasks.remove(taskId);
            removedTasks.add(taskId);
        }

        @Override
        public void forceStopPackage(String pkg) {}

        @Override
        public void onDisplayReleased(int displayId) {
            topTaskByDisplay.remove(displayId);
        }

        @Override
        public void releaseSurfaceHandle(Object surface) {}
    }

    @Test
    public void testMoveWithoutSizeChangePerformsZeroVirtualDisplayResizes() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c.conf"), new File(tmpDir.getRoot(), "p.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 1920, 1200, 240);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime();
        AtomicInteger dirtyEvents = new AtomicInteger();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, dirtyEvents::incrementAndGet);

        int w1 = engine.openOrFocusWindow("org.mozilla.firefox", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        assertTrue(w1 > 0);
        int dispId = engine.attachWindowSurface(w1, new Object(), 1000, 700, 200, 120f);
        assertTrue(dispId >= 200);
        assertEquals(0, driver.resizeCount);

        WindowSession before = engine.getWindowSnapshot(w1);
        int width = before.frame.width();
        int height = before.frame.height();

        // Pure translation move (same width & height): MUST NOT resize the underlying VirtualDisplay!
        assertTrue(engine.updateWindowFrame(w1, 240, 160, 240 + width, 160 + height, BridgeProtocol.PRES_FLOATING));
        assertEquals("Pure window translation must trigger 0 VirtualDisplay resizes", 0, driver.resizeCount);

        // Snap left & toggle maximize: MUST resize VirtualDisplay and preserve restoreFrame
        IntRect savedFloating = engine.getWindowSnapshot(w1).frame;
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE, null));
        assertEquals(BridgeProtocol.PRES_MAXIMIZED, engine.getWindowSnapshot(w1).presentation);
        assertEquals(1, driver.resizeCount);

        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE, null));
        assertEquals(BridgeProtocol.PRES_FLOATING, engine.getWindowSnapshot(w1).presentation);
        assertEquals(savedFloating, engine.getWindowSnapshot(w1).frame);
    }

    @Test
    public void testProfilePersistenceLruHibernationWakeAndDeadTaskCleanup() throws Exception {
        File confF = new File(tmpDir.getRoot(), "main.conf");
        File profF = new File(tmpDir.getRoot(), "profiles.conf");
        DaemonConfig cfg = new DaemonConfig(confF, profF);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 2, false);
        FakeRuntime runtime = new FakeRuntime();
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // Reject blocked system packages
        assertEquals(-1, engine.openOrFocusWindow("com.android.systemui", "", 0, 0, 0, 0, 0));
        assertEquals(-1, engine.openOrFocusWindow(BridgeBuildConfig.HELPER_PACKAGE, "", 0, 0, 0, 0, 0));

        int w1 = engine.openOrFocusWindow("com.tiktok.lite", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int w2 = engine.openOrFocusWindow("com.Slack", "", BridgeProtocol.PROFILE_DESKTOP, 0, 0, 0, 0);
        int w3 = engine.openOrFocusWindow("org.telegram.messenger", "", BridgeProtocol.PROFILE_TABLET, 0, 0, 0, 0);

        assertEquals(WindowProfile.PORTRAIT, cfg.getSavedProfile("com.tiktok.lite"));
        assertTrue(engine.attachWindowSurface(w1, "surf1", 540, 1080, 320, 60f) > 0);
        Thread.sleep(5L);
        assertTrue(engine.attachWindowSurface(w2, "surf2", 1200, 800, 200, 60f) > 0);
        Thread.sleep(5L);

        // Attaching 3rd window on maxVd=2 pool hibernates w1 with a cached JPEG snapshot
        assertTrue(engine.attachWindowSurface(w3, "surf3", 1024, 768, 220, 60f) > 0);
        WindowSession s1 = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.STATE_HIBERNATED, s1.state);
        assertNotNull(engine.getHibernatedSnapshotBytes(w1));

        String json = engine.toSceneJson();
        assertTrue(json.contains("\"pkg\":\"com.tiktok.lite\""));
        assertTrue(json.contains("\"state\":\"HIBERNATED\""));
        assertTrue(json.contains("\"activeVdCount\":2"));

        // Waking hibernated w1 must transition state back to LIVE so client mounts TextureView
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_WAKE, null));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);

        // Minimizing and restoring w2 via openOrFocusWindow must also transition back to LIVE
        assertTrue(engine.performWindowAction(w2, BridgeProtocol.WIN_ACTION_MINIMIZE, null));
        assertEquals(BridgeProtocol.STATE_MINIMIZED, engine.getWindowSnapshot(w2).state);
        assertEquals(w2, engine.openOrFocusWindow("com.Slack", "", BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w2).state);

        // Simulate w3's underlying Android task exiting on its own -> reconcile cleans up window w3
        WindowSession s3 = engine.getWindowSnapshot(w3);
        s3.lastActiveAtMs = System.currentTimeMillis() - 5000L;
        runtime.aliveTasks.remove(s3.taskId);
        runtime.topTaskByDisplay.remove(s3.displayId);
        engine.reconcileWithSystemTasks();
        assertNull("Exited task window should be auto-removed during reconciliation", engine.getWindowSnapshot(w3));
    }
}
