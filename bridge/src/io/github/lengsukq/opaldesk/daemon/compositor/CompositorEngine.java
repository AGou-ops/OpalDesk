package io.github.lengsukq.opaldesk.daemon.compositor;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil.IntRect;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core Wayland-style Universal Window Compositor Engine.
 *
 * <p>Each managed window owns an isolated {@code VirtualDisplay} running standard
 * {@code WINDOWING_MODE_FULLSCREEN (1)}, completely bypassing OEM freeform/multi-window APIs.
 * Pure Java 11 with injected {@link AppRuntimeBridge} and {@link VirtualDisplayPool} so 100% of
 * window lifecycle, Z-order, snap zones, and LRU snapshot hibernation are JVM unit-testable.
 */
public final class CompositorEngine {

    /** Platform bridge for launching/moving/removing fullscreen Android tasks on a displayId. */
    public interface AppRuntimeBridge {
        String resolveAppLabel(String pkg);

        /**
         * Launches or moves {@code pkg}/{@code cls} onto {@code displayId} in standard
         * {@code WINDOWING_MODE_FULLSCREEN (1)} and returns the resolved {@code taskId} (or {@code -1}).
         */
        int launchOrMoveFullscreenApp(String pkg, String cls, int displayId, int existingTaskId);

        /** Queries the active user taskId currently running on {@code displayId} (or {@code -1}). */
        int findTopTaskIdOnDisplay(int displayId, String expectedPkg);

        /** Returns the set of all currently alive Android taskIds across the system. */
        Set<Integer> collectAliveTaskIds();

        /** Removes a task completely when the user closes its desktop window. */
        void removeTask(int taskId);

        /** Force-stops and relaunches an app cleanly inside its VirtualDisplay. */
        void forceStopPackage(String pkg);

        /** Releases any per-display UHID or input resources when a VirtualDisplay is torn down. */
        void onDisplayReleased(int displayId);

        /** Releases an Android Surface handle when no longer referenced. */
        void releaseSurfaceHandle(Object surface);
    }

    private final Object lock = new Object();
    private final DaemonConfig config;
    private final VirtualDisplayPool vdPool;
    private final AppRuntimeBridge runtimeBridge;
    private final Runnable onSceneDirty;

    private final Map<Integer, WindowSession> sessionsById = new LinkedHashMap<>();
    private final Map<Integer, Object> attachedSurfaces = new LinkedHashMap<>();
    private final AtomicInteger nextWindowId = new AtomicInteger(1);
    private final AtomicLong stateSeq = new AtomicLong(1L);
    private int nextZOrder = 1;

    public CompositorEngine(
            DaemonConfig config,
            VirtualDisplayPool vdPool,
            AppRuntimeBridge runtimeBridge,
            Runnable onSceneDirty) {
        this.config = config;
        this.vdPool = vdPool;
        this.runtimeBridge = runtimeBridge;
        this.onSceneDirty = onSceneDirty;
    }

    public long getStateSeq() {
        return stateSeq.get();
    }

    public int getActiveDisplayCount() {
        return vdPool.getActiveCount();
    }

    public int getEffectiveMaxDisplays() {
        return vdPool.getEffectiveMaxDisplays();
    }

    public void setConfiguredMaxDisplays(int maxDisplays) {
        vdPool.setConfiguredMaxDisplays(maxDisplays);
    }

    /** Recomputes DPI for all open windows after the user changes global DPI scale preference. */
    public void reapplyWindowDpiScale() {
        synchronized (lock) {
            int dpiPct = config.getWindowDpiScalePct();
            boolean changed = false;
            for (WindowSession ws : sessionsById.values()) {
                int nextDpi = ws.profile.resolveContentDpi(ws.contentWidth, ws.contentHeight, dpiPct);
                if (nextDpi != ws.contentDpi) {
                    ws.contentDpi = nextDpi;
                    vdPool.resizeIfActive(ws.windowId, ws.contentWidth, ws.contentHeight, nextDpi);
                    changed = true;
                }
            }
            if (changed) {
                markDirtyAndNotifyLocked();
            }
        }
    }

    public int getWindowCount() {
        synchronized (lock) {
            return sessionsById.size();
        }
    }

    public WindowSession getWindowSnapshot(int windowId) {
        synchronized (lock) {
            return sessionsById.get(windowId);
        }
    }

    /**
     * Opens a new window for {@code pkg} or focuses/restores an existing window for {@code pkg}.
     * Returns positive {@code windowId}, or {@code -1} if {@code pkg} is invalid/blocked.
     */
    public int openOrFocusWindow(
            String pkg,
            String activityClass,
            int requestedProfileCode,
            int reqLeft,
            int reqTop,
            int reqRight,
            int reqBottom) {
        if (PackagePolicy.isBlockedWindowPkg(pkg)) return -1;
        String safeCls = PackagePolicy.isValidClassName(activityClass) ? activityClass : "";
        long now = System.currentTimeMillis();

        synchronized (lock) {
            WindowSession existing = findByPackageLocked(pkg);
            if (existing != null) {
                if (requestedProfileCode != BridgeProtocol.PROFILE_AUTO) {
                    WindowProfile nextProf = WindowProfile.fromCode(requestedProfileCode, existing.profile);
                    if (nextProf != existing.profile) {
                        applyProfileChangeLocked(existing, nextProf);
                    }
                }
                if (existing.isMinimized() || existing.isHibernated()) {
                    existing.state = BridgeProtocol.STATE_LIVE;
                }
                focusWindowLocked(existing, now);
                markDirtyAndNotifyLocked();
                return existing.windowId;
            }

            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();
            WindowProfile saved = config.getSavedProfile(pkg);
            WindowProfile profile = (requestedProfileCode == BridgeProtocol.PROFILE_AUTO)
                    ? (saved != null ? saved : WindowProfile.DESKTOP)
                    : WindowProfile.fromCode(requestedProfileCode, WindowProfile.DESKTOP);

            IntRect initialFrame;
            if (reqRight - reqLeft >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX
                    && reqBottom - reqTop >= DisplayGeometryUtil.MIN_WINDOW_EDGE_PX) {
                initialFrame = DisplayGeometryUtil.clampWindowFrame(
                        new IntRect(reqLeft, reqTop, reqRight, reqBottom), canvasW, canvasH, canvasDpi);
            } else {
                initialFrame = DisplayGeometryUtil.computeInitialWindowFrame(
                        profile, sessionsById.size(), canvasW, canvasH, canvasDpi);
            }

            int[] contentDims = DisplayGeometryUtil.computeContentBufferSize(initialFrame, canvasDpi);
            int contentDpi = profile.resolveContentDpi(contentDims[0], contentDims[1], config.getWindowDpiScalePct());
            int winId = nextWindowId.getAndIncrement();
            String label = runtimeBridge.resolveAppLabel(pkg);

            WindowSession created = new WindowSession(
                    winId, pkg, safeCls, label, profile, initialFrame,
                    contentDims[0], contentDims[1], contentDpi, now);
            sessionsById.put(winId, created);
            focusWindowLocked(created, now);
            config.rememberProfile(pkg, profile);
            markDirtyAndNotifyLocked();
            return winId;
        }
    }

    /**
     * Binds a client compositor {@code TextureView} {@code Surface} to {@code windowId},
     * acquiring an isolated {@code VirtualDisplay} from {@link VirtualDisplayPool} (evicting &
     * snapshot-hibernating the oldest background window if needed) and launching/resuming the app
     * in standard {@code WINDOWING_MODE_FULLSCREEN (1)}.
     *
     * @return positive {@code displayId} on success, or {@code -1} on failure.
     */
    public int attachWindowSurface(
            int windowId,
            Object surface,
            int reqContentW,
            int reqContentH,
            int reqDpi,
            float fps) {
        if (surface == null) return -1;
        String pkgToLaunch;
        String clsToLaunch;
        int prevTaskId;
        int assignedDisplayId;
        boolean reusedExistingLease;

        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) {
                runtimeBridge.releaseSurfaceHandle(surface);
                return -1;
            }

            int cw = DisplayGeometryUtil.alignEven(
                    reqContentW > 0 ? reqContentW : ws.contentWidth, 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int ch = DisplayGeometryUtil.alignEven(
                    reqContentH > 0 ? reqContentH : ws.contentHeight, 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int dpi = (reqDpi > 0)
                    ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct());
            int cfgFps = config.getTargetFps();
            float effFps = DisplayGeometryUtil.clampFps(cfgFps > 0 ? cfgFps : fps, 60f);

            reusedExistingLease = vdPool.getLease(windowId) != null;
            VirtualDisplayPool.Lease lease = vdPool.acquireOrBind(
                    ws,
                    surface,
                    cw,
                    ch,
                    dpi,
                    effFps,
                    sessionsById.values(),
                    this::handleWindowEvictedLocked);
            if (lease == null) {
                runtimeBridge.releaseSurfaceHandle(surface);
                return -1;
            }

            Object prevSurf = attachedSurfaces.put(windowId, surface);
            if (prevSurf != null && prevSurf != surface) {
                runtimeBridge.releaseSurfaceHandle(prevSurf);
            }

            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            ws.displayId = lease.displayId;
            ws.vdUniqueId = lease.uniqueId;
            ws.state = BridgeProtocol.STATE_LIVE;
            ws.lastActiveAtMs = System.currentTimeMillis();

            pkgToLaunch = ws.pkg;
            clsToLaunch = ws.activityClass;
            prevTaskId = ws.taskId;
            assignedDisplayId = lease.displayId;
            markDirtyAndNotifyLocked();
        }

        // If re-attaching a TextureView to an already-active VirtualDisplay whose app is still on top,
        // skip relaunching to prevent activity flicker.
        int existingTopTid = reusedExistingLease
                ? runtimeBridge.findTopTaskIdOnDisplay(assignedDisplayId, pkgToLaunch)
                : -1;
        int launchedTid = (existingTopTid > 0)
                ? existingTopTid
                : runtimeBridge.launchOrMoveFullscreenApp(
                        pkgToLaunch, clsToLaunch, assignedDisplayId, prevTaskId);
        if (launchedTid > 0) {
            synchronized (lock) {
                WindowSession ws = sessionsById.get(windowId);
                if (ws != null && ws.displayId == assignedDisplayId && ws.taskId != launchedTid) {
                    ws.taskId = launchedTid;
                    markDirtyAndNotifyLocked();
                }
            }
        }
        return assignedDisplayId;
    }

    /**
     * Detaches the client Surface when a TextureView is destroyed (e.g. minimized or screen off).
     * If {@code releaseDisplay} is true, returns the VirtualDisplay to the pool immediately while
     * caching a snapshot so the session can wake later.
     */
    public boolean detachWindowSurface(int windowId, boolean releaseDisplay) {
        int releasedDisplayId = -1;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;

            Object surf = attachedSurfaces.remove(windowId);
            if (releaseDisplay && ws.displayId > 0) {
                releasedDisplayId = ws.displayId;
                vdPool.releaseLease(windowId);
                ws.displayId = -1;
                ws.vdUniqueId = "";
                if (!ws.isMinimized()) {
                    ws.state = BridgeProtocol.STATE_HIBERNATED;
                }
            } else {
                vdPool.detachSurfaceOnly(windowId);
            }
            if (surf != null) {
                runtimeBridge.releaseSurfaceHandle(surf);
            }
            markDirtyAndNotifyLocked();
        }
        if (releasedDisplayId > 0) {
            runtimeBridge.onDisplayReleased(releasedDisplayId);
        }
        return true;
    }

    /**
     * Commits final outer window frame and presentation (FLOATING / MAXIMIZED / SNAP_*) after drag/snap.
     * Pure translation changes require zero VirtualDisplay mutations!
     */
    public boolean updateWindowFrame(
            int windowId, int left, int top, int right, int bottom, String presentation) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            int canvasW = config.getCanvasWidth();
            int canvasH = config.getCanvasHeight();
            int canvasDpi = config.getCanvasDpi();

            String nextPres = (presentation != null && !presentation.isEmpty())
                    ? presentation : BridgeProtocol.PRES_FLOATING;
            if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)
                    && !BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
                ws.restoreFrame = ws.frame;
            }

            IntRect nextFrame;
            if (!BridgeProtocol.PRES_FLOATING.equals(nextPres)) {
                nextFrame = DisplayGeometryUtil.computeSnapFrame(nextPres, canvasW, canvasH, canvasDpi);
            } else {
                nextFrame = DisplayGeometryUtil.clampWindowFrame(
                        new IntRect(left, top, right, bottom), canvasW, canvasH, canvasDpi);
                ws.restoreFrame = nextFrame;
            }

            boolean sizeChanged = nextFrame.width() != ws.frame.width()
                    || nextFrame.height() != ws.frame.height();
            ws.frame = nextFrame;
            ws.presentation = nextPres;
            focusWindowLocked(ws, System.currentTimeMillis());

            if (sizeChanged) {
                int[] dims = DisplayGeometryUtil.computeContentBufferSize(nextFrame, canvasDpi);
                int dpi = ws.profile.resolveContentDpi(dims[0], dims[1], config.getWindowDpiScalePct());
                ws.contentWidth = dims[0];
                ws.contentHeight = dims[1];
                ws.contentDpi = dpi;
                vdPool.resizeIfActive(windowId, dims[0], dims[1], dpi);
            }
            markDirtyAndNotifyLocked();
            return true;
        }
    }

    /**
     * Fast-path throttled VirtualDisplay resize during live edge/corner dragging.
     */
    public boolean resizeWindowDisplay(int windowId, int contentW, int contentH, int reqDpi) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            int cw = DisplayGeometryUtil.alignEven(contentW, 240, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int ch = DisplayGeometryUtil.alignEven(contentH, 200, DisplayGeometryUtil.MAX_WINDOW_EDGE_PX);
            int dpi = (reqDpi > 0)
                    ? DisplayGeometryUtil.clampDpi(reqDpi)
                    : ws.profile.resolveContentDpi(cw, ch, config.getWindowDpiScalePct());
            if (cw == ws.contentWidth && ch == ws.contentHeight && dpi == ws.contentDpi) {
                return true;
            }
            ws.contentWidth = cw;
            ws.contentHeight = ch;
            ws.contentDpi = dpi;
            vdPool.resizeIfActive(windowId, cw, ch, dpi);
            return true;
        }
    }

    /**
     * Executes lifecycle & profile actions on {@code windowId}.
     */
    public boolean performWindowAction(int windowId, int action, byte[] optionalPreMinimizeSnapshot) {
        int taskToRemove = -1;
        int displayToClean = -1;
        String pkgToReload = null;
        int reloadDisplayId = -1;
        String reloadCls = "";

        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws == null) return false;
            long now = System.currentTimeMillis();

            switch (action) {
                case BridgeProtocol.WIN_ACTION_FOCUS:
                case BridgeProtocol.WIN_ACTION_WAKE: {
                    if (ws.isMinimized() || ws.isHibernated()) {
                        ws.state = BridgeProtocol.STATE_LIVE;
                    }
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_MINIMIZE: {
                    if (optionalPreMinimizeSnapshot != null && optionalPreMinimizeSnapshot.length > 0) {
                        ws.hibernatedSnapshotBytes = optionalPreMinimizeSnapshot;
                        ws.snapshotVersion++;
                    }
                    if (ws.displayId > 0) {
                        displayToClean = ws.displayId;
                        vdPool.releaseLease(windowId);
                        ws.displayId = -1;
                        ws.vdUniqueId = "";
                    }
                    Object surf = attachedSurfaces.remove(windowId);
                    if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
                    ws.state = BridgeProtocol.STATE_MINIMIZED;
                    ws.focused = false;
                    promoteTopVisibleFocusLocked(now);
                    markDirtyAndNotifyLocked();
                    break;
                }
                case BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE: {
                    int canvasW = config.getCanvasWidth();
                    int canvasH = config.getCanvasHeight();
                    int canvasDpi = config.getCanvasDpi();
                    if (BridgeProtocol.PRES_MAXIMIZED.equals(ws.presentation)) {
                        ws.presentation = BridgeProtocol.PRES_FLOATING;
                        ws.frame = (ws.restoreFrame != null && !ws.restoreFrame.isEmpty())
                                ? DisplayGeometryUtil.clampWindowFrame(ws.restoreFrame, canvasW, canvasH, canvasDpi)
                                : DisplayGeometryUtil.computeInitialWindowFrame(ws.profile, 0, canvasW, canvasH, canvasDpi);
                    } else {
                        if (BridgeProtocol.PRES_FLOATING.equals(ws.presentation)) {
                            ws.restoreFrame = ws.frame;
                        }
                        ws.presentation = BridgeProtocol.PRES_MAXIMIZED;
                        ws.frame = DisplayGeometryUtil.computeSnapFrame(
                                BridgeProtocol.PRES_MAXIMIZED, canvasW, canvasH, canvasDpi);
                    }
                    int[] dims = DisplayGeometryUtil.computeContentBufferSize(ws.frame, canvasDpi);
                    ws.contentWidth = dims[0];
                    ws.contentHeight = dims[1];
                    ws.contentDpi = ws.profile.resolveContentDpi(dims[0], dims[1], config.getWindowDpiScalePct());
                    vdPool.resizeIfActive(windowId, dims[0], dims[1], ws.contentDpi);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                }
                case BridgeProtocol.WIN_ACTION_CLOSE: {
                    sessionsById.remove(windowId);
                    Object surf = attachedSurfaces.remove(windowId);
                    if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
                    if (ws.displayId > 0) {
                        displayToClean = ws.displayId;
                        vdPool.releaseLease(windowId);
                    }
                    taskToRemove = ws.taskId;
                    promoteTopVisibleFocusLocked(now);
                    markDirtyAndNotifyLocked();
                    break;
                }
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_DESKTOP:
                    applyProfileChangeLocked(ws, WindowProfile.DESKTOP);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_PORTRAIT:
                    applyProfileChangeLocked(ws, WindowProfile.PORTRAIT);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_SET_PROFILE_TABLET:
                    applyProfileChangeLocked(ws, WindowProfile.TABLET);
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    return true;
                case BridgeProtocol.WIN_ACTION_RELOAD_APP:
                    pkgToReload = ws.pkg;
                    reloadCls = ws.activityClass;
                    reloadDisplayId = ws.displayId;
                    ws.taskId = -1;
                    focusWindowLocked(ws, now);
                    markDirtyAndNotifyLocked();
                    break;
                default:
                    return false;
            }
        }

        if (displayToClean > 0) {
            runtimeBridge.onDisplayReleased(displayToClean);
        }
        if (taskToRemove > 0) {
            runtimeBridge.removeTask(taskToRemove);
        }
        if (pkgToReload != null && !pkgToReload.isEmpty()) {
            runtimeBridge.forceStopPackage(pkgToReload);
            if (reloadDisplayId > 0) {
                int newTid = runtimeBridge.launchOrMoveFullscreenApp(pkgToReload, reloadCls, reloadDisplayId, -1);
                if (newTid > 0) {
                    synchronized (lock) {
                        WindowSession ws = sessionsById.get(windowId);
                        if (ws != null) {
                            ws.taskId = newTid;
                            markDirtyAndNotifyLocked();
                        }
                    }
                }
            }
        }
        return true;
    }

    public byte[] getHibernatedSnapshotBytes(int windowId) {
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            return (ws != null) ? ws.hibernatedSnapshotBytes : null;
        }
    }

    public void updateCachedSnapshot(int windowId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        synchronized (lock) {
            WindowSession ws = sessionsById.get(windowId);
            if (ws != null) {
                ws.hibernatedSnapshotBytes = bytes;
                ws.snapshotVersion++;
            }
        }
    }

    /**
     * Reconciles live Android tasks with managed window sessions: binds newly spawned taskIds on
     * active VirtualDisplays and auto-closes windows whose underlying task exited cleanly.
     */
    public void reconcileWithSystemTasks() {
        List<Integer> checkWindowIds = new ArrayList<>();
        List<Integer> checkDisplayIds = new ArrayList<>();
        List<String> checkPkgs = new ArrayList<>();

        synchronized (lock) {
            if (sessionsById.isEmpty()) return;
            for (WindowSession ws : sessionsById.values()) {
                if (ws.isLive() && ws.displayId > 0) {
                    checkWindowIds.add(ws.windowId);
                    checkDisplayIds.add(ws.displayId);
                    checkPkgs.add(ws.pkg);
                }
            }
        }

        Set<Integer> aliveTids = runtimeBridge.collectAliveTaskIds();
        Map<Integer, Integer> discoveredDisplayTasks = new LinkedHashMap<>();
        for (int i = 0; i < checkWindowIds.size(); i++) {
            int topTid = runtimeBridge.findTopTaskIdOnDisplay(checkDisplayIds.get(i), checkPkgs.get(i));
            if (topTid > 0) {
                discoveredDisplayTasks.put(checkWindowIds.get(i), topTid);
            }
        }

        List<Integer> releasedDisplays = new ArrayList<>();
        synchronized (lock) {
            boolean dirty = false;
            long now = System.currentTimeMillis();
            List<Integer> deadWindows = new ArrayList<>();

            for (WindowSession ws : sessionsById.values()) {
                Integer liveTid = discoveredDisplayTasks.get(ws.windowId);
                if (liveTid != null && liveTid > 0 && ws.taskId != liveTid) {
                    ws.taskId = liveTid;
                    dirty = true;
                } else if (ws.taskId > 0
                        && aliveTids != null
                        && !aliveTids.isEmpty()
                        && !aliveTids.contains(ws.taskId)
                        && (now - ws.lastActiveAtMs) > 2200L) {
                    deadWindows.add(ws.windowId);
                }
            }

            for (int winId : deadWindows) {
                WindowSession removed = sessionsById.remove(winId);
                if (removed != null) {
                    Object surf = attachedSurfaces.remove(winId);
                    if (surf != null) runtimeBridge.releaseSurfaceHandle(surf);
                    if (removed.displayId > 0) {
                        releasedDisplays.add(removed.displayId);
                        vdPool.releaseLease(winId);
                    }
                    dirty = true;
                }
            }

            if (!deadWindows.isEmpty()) {
                promoteTopVisibleFocusLocked(now);
            }
            if (dirty) {
                markDirtyAndNotifyLocked();
            }
        }

        for (int dispId : releasedDisplays) {
            runtimeBridge.onDisplayReleased(dispId);
        }
    }

    public void closeAllWindows(boolean removeUnderlyingTasks) {
        List<Integer> tasksToRemove = new ArrayList<>();
        List<Integer> displaysToClean = new ArrayList<>();
        List<Object> surfacesToRelease = new ArrayList<>();

        synchronized (lock) {
            for (WindowSession ws : sessionsById.values()) {
                if (ws.displayId > 0) displaysToClean.add(ws.displayId);
                if (removeUnderlyingTasks && ws.taskId > 0) tasksToRemove.add(ws.taskId);
            }
            surfacesToRelease.addAll(attachedSurfaces.values());
            attachedSurfaces.clear();
            sessionsById.clear();
            vdPool.releaseAll();
            markDirtyAndNotifyLocked();
        }

        for (Object s : surfacesToRelease) runtimeBridge.releaseSurfaceHandle(s);
        for (int d : displaysToClean) runtimeBridge.onDisplayReleased(d);
        for (int t : tasksToRemove) runtimeBridge.removeTask(t);
    }

    public String toSceneJson() {
        synchronized (lock) {
            List<WindowSession> sorted = new ArrayList<>(sessionsById.values());
            sorted.sort(Comparator.comparingInt(w -> w.zOrder));
            SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
            for (WindowSession ws : sorted) {
                arr.addRaw(ws.toJson());
            }
            return new SimpleJson.ObjectBuilder()
                    .put("seq", stateSeq.get())
                    .put("canvasW", config.getCanvasWidth())
                    .put("canvasH", config.getCanvasHeight())
                    .put("canvasDpi", config.getCanvasDpi())
                    .put("activeVdCount", vdPool.getActiveCount())
                    .put("maxVdCount", vdPool.getEffectiveMaxDisplays())
                    .putRaw("windows", arr.build())
                    .build();
        }
    }

    private void applyProfileChangeLocked(WindowSession ws, WindowProfile nextProfile) {
        ws.profile = nextProfile;
        config.rememberProfile(ws.pkg, nextProfile);
        int canvasW = config.getCanvasWidth();
        int canvasH = config.getCanvasHeight();
        int canvasDpi = config.getCanvasDpi();
        ws.presentation = BridgeProtocol.PRES_FLOATING;
        ws.frame = DisplayGeometryUtil.computeInitialWindowFrame(nextProfile, 0, canvasW, canvasH, canvasDpi);
        ws.restoreFrame = ws.frame;
        int[] dims = DisplayGeometryUtil.computeContentBufferSize(ws.frame, canvasDpi);
        ws.contentWidth = dims[0];
        ws.contentHeight = dims[1];
        ws.contentDpi = nextProfile.resolveContentDpi(dims[0], dims[1], config.getWindowDpiScalePct());
        vdPool.resizeIfActive(ws.windowId, dims[0], dims[1], ws.contentDpi);
    }

    private void handleWindowEvictedLocked(WindowSession victim, byte[] snapshotBytes) {
        int oldDisplayId = victim.displayId;
        victim.displayId = -1;
        victim.vdUniqueId = "";
        if (!victim.isMinimized()) {
            victim.state = BridgeProtocol.STATE_HIBERNATED;
        }
        victim.focused = false;
        if (snapshotBytes != null && snapshotBytes.length > 0) {
            victim.hibernatedSnapshotBytes = snapshotBytes;
            victim.snapshotVersion++;
        }
        Object surf = attachedSurfaces.remove(victim.windowId);
        if (surf != null) {
            runtimeBridge.releaseSurfaceHandle(surf);
        }
        if (oldDisplayId > 0) {
            runtimeBridge.onDisplayReleased(oldDisplayId);
        }
    }

    private WindowSession findByPackageLocked(String pkg) {
        for (WindowSession ws : sessionsById.values()) {
            if (pkg.equals(ws.pkg)) return ws;
        }
        return null;
    }

    private void focusWindowLocked(WindowSession target, long nowMs) {
        for (WindowSession ws : sessionsById.values()) {
            ws.focused = (ws == target);
        }
        target.zOrder = nextZOrder++;
        target.lastActiveAtMs = nowMs;
    }

    private void promoteTopVisibleFocusLocked(long nowMs) {
        WindowSession top = null;
        for (WindowSession ws : sessionsById.values()) {
            if (ws.isMinimized()) {
                ws.focused = false;
                continue;
            }
            if (top == null || ws.zOrder > top.zOrder) {
                top = ws;
            }
        }
        for (WindowSession ws : sessionsById.values()) {
            ws.focused = (top != null && ws == top);
        }
        if (top != null) {
            top.lastActiveAtMs = nowMs;
        }
    }

    private void markDirtyAndNotifyLocked() {
        stateSeq.incrementAndGet();
        if (onSceneDirty != null) {
            onSceneDirty.run();
        }
    }
}
