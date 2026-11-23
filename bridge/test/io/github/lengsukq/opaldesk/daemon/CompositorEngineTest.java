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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
        final List<String> events = new ArrayList<>();
        int resizeCount = 0;
        int detachSurfaceCount = 0;
        int releaseDisplayCount = 0;

        @Override
        public CreatedDisplay createVirtualDisplay(
                String name, int width, int height, int dpi, float fps, Object surface, boolean allowSecure) {
            int id = nextDisplayId.getAndIncrement();
            events.add("createVd:" + id);
            return new CreatedDisplay("vd-" + id, id, "uid-" + id);
        }

        @Override
        public void resizeVirtualDisplay(Object handle, int width, int height, int dpi) {
            resizeCount++;
        }

        @Override
        public void setVirtualDisplaySurface(Object handle, Object surface) {
            if (surface == null) {
                detachSurfaceCount++;
                events.add("detachSurface:" + handle);
            } else {
                events.add("attachSurface:" + handle);
            }
        }

        @Override
        public void releaseVirtualDisplay(Object handle, int displayId) {
            releaseDisplayCount++;
            events.add("releaseVd:" + displayId);
        }

        @Override
        public byte[] captureTaskSnapshotBytes(int taskId) {
            return ("jpeg-" + taskId).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeRuntime implements CompositorEngine.AppRuntimeBridge {
        final AtomicInteger nextTaskId = new AtomicInteger(900);
        final Set<Integer> aliveTasks = new HashSet<>();
        final Set<Integer> runningBgTasks = new HashSet<>();
        final Map<Integer, Integer> topTaskByDisplay = new HashMap<>();
        final Map<Integer, Integer> visibleTaskByDisplay = new HashMap<>();
        final Set<Integer> removedTasks = new HashSet<>();
        final List<String> events;
        int launchCount = 0;
        int focusDisplayCount = 0;

        FakeRuntime() {
            this(new ArrayList<>());
        }

        FakeRuntime(List<String> sharedEvents) {
            this.events = sharedEvents;
        }

        @Override
        public String resolveAppLabel(String pkg) {
            return "Label:" + pkg;
        }

        @Override
        public int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId) {
            launchCount++;
            int tid = (existingTaskId > 0) ? existingTaskId : nextTaskId.getAndIncrement();
            aliveTasks.add(tid);
            runningBgTasks.add(tid);
            topTaskByDisplay.put(displayId, tid);
            visibleTaskByDisplay.put(displayId, tid);
            events.add("launch:" + tid + "@d" + displayId);
            return tid;
        }

        @Override
        public int findTopTaskIdOnDisplay(int displayId, String expectedPkg) {
            return topTaskByDisplay.getOrDefault(displayId, -1);
        }

        @Override
        public int findTopVisibleTaskIdOnDisplay(int displayId, String expectedPkg) {
            Integer top = topTaskByDisplay.get(displayId);
            if (top == null || top <= 0) return -1;
            return visibleTaskByDisplay.getOrDefault(displayId, -1);
        }

        @Override
        public boolean isTaskRunningWithActivities(int taskId, int displayId) {
            return aliveTasks.contains(taskId) && runningBgTasks.contains(taskId);
        }

        @Override
        public Set<Integer> collectAliveTaskIds() {
            return new HashSet<>(aliveTasks);
        }

        @Override
        public void removeTask(int taskId) {
            aliveTasks.remove(taskId);
            removedTasks.add(taskId);
            events.add("removeTask:" + taskId);
        }

        @Override
        public void forceStopPackage(String pkg) {}

        @Override
        public void focusDisplay(int displayId) {
            focusDisplayCount++;
            events.add("focusDisplay:" + displayId);
        }

        @Override
        public void onDisplayReleased(int displayId) {
            topTaskByDisplay.remove(displayId);
            events.add("onDisplayReleased:" + displayId);
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

        // Toggle true app fullscreen: fills full canvas edge-to-edge (0, 0, 1920, 1200) with zero header subtraction
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
        WindowSession fsWin = engine.getWindowSnapshot(w1);
        assertEquals(BridgeProtocol.PRES_FULLSCREEN, fsWin.presentation);
        assertEquals(new IntRect(0, 0, 1920, 1200), fsWin.frame);
        assertEquals(1920, fsWin.contentWidth);
        assertEquals(1200, fsWin.contentHeight);

        // Toggle fullscreen off -> cleanly restores floating frame
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
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

    @Test
    public void testFullscreenMinimizeKeepsVirtualDisplayAliveAndCloseRemovesTaskBeforeDisplay() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c2.conf"), new File(tmpDir.getRoot(), "p2.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("tv.danmaku.bili", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int dispId = engine.attachWindowSurface(w1, new Object(), 788, 1078, 306, 60f);
        assertEquals(1, runtime.launchCount);
        WindowSession floating = engine.getWindowSnapshot(w1);
        IntRect savedFrame = floating.frame;
        int tid = floating.taskId;

        // Enter fullscreen, then click "Return to Desktop" (minimizeAllWindows)
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_TOGGLE_FULLSCREEN, null));
        assertEquals(BridgeProtocol.PRES_FULLSCREEN, engine.getWindowSnapshot(w1).presentation);

        engine.minimizeAllWindows();
        WindowSession minWin = engine.getWindowSnapshot(w1);
        assertNotNull("Minimizing from fullscreen must NOT close the window", minWin);
        assertEquals(BridgeProtocol.STATE_MINIMIZED, minWin.state);
        assertEquals(BridgeProtocol.PRES_FLOATING, minWin.presentation);
        assertEquals(savedFrame, minWin.frame);
        assertEquals("VirtualDisplay MUST stay leased on minimize so Android never bounces app to Phone Display 0",
                dispId, minWin.displayId);
        assertEquals(0, driver.releaseDisplayCount);
        assertTrue(driver.detachSurfaceCount >= 1);
        assertTrue(runtime.removedTasks.isEmpty());

        // Restore from Dock: reuses exact VirtualDisplay lease with 0 relaunch
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_FOCUS, null));
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);
        int resumedDispId = engine.attachWindowSurface(w1, new Object(), minWin.contentWidth, minWin.contentHeight, minWin.contentDpi, 60f);
        assertEquals(dispId, resumedDispId);
        assertEquals("Resuming minimized window must reuse task without relaunch", 1, runtime.launchCount);
        assertEquals(1, runtime.focusDisplayCount);

        // Close window: task MUST be removed BEFORE VirtualDisplay is released
        driver.events.clear();
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_CLOSE, null));
        assertNull(engine.getWindowSnapshot(w1));
        int rmIdx = driver.events.indexOf("removeTask:" + tid);
        int relIdx = driver.events.indexOf("releaseVd:" + dispId);
        assertTrue("removeTask must be called on close", rmIdx >= 0);
        assertTrue("releaseVd must be called on close", relIdx >= 0);
        assertTrue("removeTask MUST execute before releaseVd to prevent Display 0 bounce", rmIdx < relIdx);
    }

    @Test
    public void testBackAtRootPreventsBlankWindowOnMoveToBackAndFinish() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c3.conf"), new File(tmpDir.getRoot(), "p3.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        // 1. Sub-page Back: task remains visible -> window stays LIVE
        int w1 = engine.openOrFocusWindow("com.tencent.mm", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int d1 = engine.attachWindowSurface(w1, new Object(), 720, 1280, 280, 60f);
        engine.reconcileWindowAfterBack(w1);
        assertEquals(BridgeProtocol.STATE_LIVE, engine.getWindowSnapshot(w1).state);

        // 2. Root Back with moveTaskToBack(true): display loses visible task while task still runs in bg -> minimize cleanly
        runtime.visibleTaskByDisplay.remove(d1);
        engine.reconcileWindowAfterBack(w1);
        WindowSession minWs = engine.getWindowSnapshot(w1);
        assertNotNull("Backgrounded task should minimize to Dock instead of showing blank white screen", minWs);
        assertEquals(BridgeProtocol.STATE_MINIMIZED, minWs.state);

        // Reopening from Dock must bring the hidden task back via launchOrMoveFullscreenApp (not skip relaunch!)
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_FOCUS, null));
        int launchesBeforeWake = runtime.launchCount;
        engine.attachWindowSurface(w1, new Object(), 720, 1280, 280, 60f);
        assertEquals("Waking a moveTaskToBack window must re-surface it", launchesBeforeWake + 1, runtime.launchCount);

        // 3. Root Back with finish(): task no longer visible AND no running activities (even if still in recent tasks) -> close cleanly
        int tid1 = engine.getWindowSnapshot(w1).taskId;
        runtime.visibleTaskByDisplay.remove(d1);
        runtime.runningBgTasks.remove(tid1);
        engine.reconcileWindowAfterBack(w1);
        assertNull("Exited app must close window cleanly instead of leaving a blank white window",
                engine.getWindowSnapshot(w1));
    }

    @Test
    public void reloadWindowRefreshesLaunchGracePeriodAndPreservesDisplay() throws Exception {
        DaemonConfig cfg = new DaemonConfig(new File(tmpDir.getRoot(), "c4.conf"), new File(tmpDir.getRoot(), "p4.conf"));
        cfg.updateRuntimeConfig(BridgeProtocol.INPUT_BACKEND_AUTO, 4, 2560, 1600, 360);
        RecordingDriver driver = new RecordingDriver();
        VirtualDisplayPool pool = new VirtualDisplayPool(driver, 4, false);
        FakeRuntime runtime = new FakeRuntime(driver.events);
        CompositorEngine engine = new CompositorEngine(cfg, pool, runtime, null);

        int w1 = engine.openOrFocusWindow("com.tencent.mm", "", BridgeProtocol.PROFILE_PORTRAIT, 0, 0, 0, 0);
        int d1 = engine.attachWindowSurface(w1, new Object(), 1236, 1738, 480, 60f);
        WindowSession before = engine.getWindowSnapshot(w1);
        before.lastLaunchAtMs = 1L;
        before.emptyDisplayTicks = 2;

        int launchesBefore = runtime.launchCount;
        assertTrue(engine.performWindowAction(w1, BridgeProtocol.WIN_ACTION_RELOAD_APP, null));

        WindowSession after = engine.getWindowSnapshot(w1);
        assertNotNull("Reloading an app must never prematurely close the window", after);
        assertEquals("Reload must keep the same VirtualDisplay alive", d1, after.displayId);
        assertEquals(0, after.emptyDisplayTicks);
        assertTrue("Reload must refresh lastLaunchAtMs grace window", after.lastLaunchAtMs > 1L);
        assertEquals(launchesBefore + 1, runtime.launchCount);
    }
}
