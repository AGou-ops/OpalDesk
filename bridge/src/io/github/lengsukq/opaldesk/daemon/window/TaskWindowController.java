package io.github.lengsukq.opaldesk.daemon.window;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.Display;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.display.CompatPolicyController;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.TaskStackObserver;
import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;
import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager.ManagedWindow;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager.TwoSlotSplitSnapshot;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine.WindowRect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Android-side task & window orchestrator using {@link TaskApiAdapter}, {@link DesktopWindowManager},
 * {@link TaskQueryService}, and {@link TilingSessionController}. Shares one
 * {@link #applySingleWindowMode} engine across launch, switch, bounds update, adaptive toggle,
 * resize, and fullscreen enforcement paths.
 */
public final class TaskWindowController {
    private static final int FULLSCREEN_WIDTH_TOLERANCE_PX = 16;
    private static final long PENDING_FULLSCREEN_GRACE_MS = 2500L;

    private static volatile Method sSetLaunchWindowingModeMethod;
    private static volatile boolean sSetLaunchWindowingModeChecked;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final TaskApiAdapter taskApi;
    private final DesktopWindowManager windowManager;
    private final CompatPolicyController compatPolicy;
    private final HelperClientBridge helperBridge;
    private final TaskQueryService queries;
    private final TilingSessionController tiling;
    private final Handler mainHandler;
    private final ExecutorService bgIoExec;
    private final TaskStackObserver taskStackObserver;
    private final Runnable fastFocusSyncTask = this::syncFocusFastNow;

    private volatile Runnable onRemoteStateDirty;
    private volatile Runnable onSendBackKey;
    private volatile String pendingFullscreenPkg = "";
    private volatile long pendingFullscreenUntilMs = 0L;
    private volatile long displayMigrationUntilMs = 0L;
    private volatile int lastLiveMoveTaskId = -1;
    private volatile long lastLiveMoveMs = 0L;

    public TaskWindowController(
            AndroidServices services, DaemonConfig config, TaskApiAdapter taskApi,
            DesktopWindowManager windowManager, CompatPolicyController compatPolicy,
            HelperClientBridge helperBridge, Handler mainHandler, ExecutorService bgIoExec) {
        this.services = services;
        this.config = config;
        this.taskApi = taskApi;
        this.windowManager = windowManager;
        this.compatPolicy = compatPolicy;
        this.helperBridge = helperBridge;
        this.queries = new TaskQueryService(services, config, taskApi, windowManager, helperBridge);
        this.tiling = new TilingSessionController(
                services, config, taskApi, windowManager, compatPolicy, helperBridge, queries, this, mainHandler);
        this.mainHandler = mainHandler;
        this.bgIoExec = bgIoExec;
        this.taskStackObserver = new TaskStackObserver(services.iActivityTaskManager, mainHandler, this::onTaskStackDirty);
    }

    public void setOnRemoteStateDirty(Runnable cb) { this.onRemoteStateDirty = cb; }
    public void setOnSendBackKey(Runnable cb) { this.onSendBackKey = cb; }
    public void startTaskStackObserver() { taskStackObserver.start(); }
    public void stopTaskStackObserver() {
        taskStackObserver.stop();
        if (mainHandler != null) mainHandler.removeCallbacks(fastFocusSyncTask);
    }

    /** Schedules a 35ms debounced focus & Z-order sync (used on pointer down/up fallback). */
    public void scheduleFastFocusSync() {
        if (config.shuttingDown || config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(fastFocusSyncTask);
            mainHandler.postDelayed(fastFocusSyncTask, 35L);
        } else {
            syncFocusFastNow();
        }
    }

    private void onTaskStackDirty() {
        if (config.shuttingDown || config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX || isDisplayMigrating()) {
            return;
        }
        syncFocusFastNow();
    }

    private void syncFocusFastNow() {
        if (config.shuttingDown || config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX || isDisplayMigrating()) {
            return;
        }
        long beforeSeq = windowManager.getStateSeq();
        syncManagedWindowsWithLiveTasks();
        long afterSeq = windowManager.getStateSeq();
        if (afterSeq != beforeSeq) {
            notifyStateChanged();
        }
    }

    public void setDesktopPrefs(boolean taskbarAutoHide, String defaultLaunchMode) {
        boolean prevAutoHide = windowManager.isTaskbarAutoHide();
        windowManager.setTaskbarAutoHide(taskbarAutoHide);
        if (defaultLaunchMode != null && !defaultLaunchMode.trim().isEmpty()) {
            windowManager.setDefaultLaunchMode(defaultLaunchMode);
        }
        if (prevAutoHide != taskbarAutoHide) {
            relayoutManagedWindowsOnResize();
        } else {
            notifyStateChanged();
        }
    }

    /** Handles a global DeX desktop keyboard shortcut intercepted by local or remote input bridges. */
    public boolean handleDesktopShortcut(String action) {
        if (action == null || action.isEmpty() || config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) {
            return false;
        }
        switch (action) {
            case BridgeProtocol.SHORTCUT_WINDOW_SWITCHER:
            case BridgeProtocol.SHORTCUT_APP_DRAWER:
            case BridgeProtocol.SHORTCUT_SHOW_DESKTOP:
            case BridgeProtocol.SHORTCUT_DISMISS_OVERLAY:
                helperBridge.pushDesktopShortcut(action);
                return true;
            case BridgeProtocol.SHORTCUT_SNAP_LEFT:
                return snapFocusedWindowToZone(DesktopWindowManager.SNAP_LEFT_HALF);
            case BridgeProtocol.SHORTCUT_SNAP_RIGHT:
                return snapFocusedWindowToZone(DesktopWindowManager.SNAP_RIGHT_HALF);
            case BridgeProtocol.SHORTCUT_MAXIMIZE_TOGGLE: {
                ManagedWindow fg = findFocusedNonMinimizedUserWindow();
                return fg != null && windowLifecycleAction(fg.taskId, fg.pkg, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE);
            }
            case BridgeProtocol.SHORTCUT_MINIMIZE_OR_RESTORE: {
                ManagedWindow fg = findFocusedNonMinimizedUserWindow();
                if (fg == null) return false;
                if (DesktopWindowManager.PRES_MAXIMIZED.equals(fg.presentation)
                        || !DesktopWindowManager.PRES_FLOATING.equals(fg.presentation)) {
                    return windowLifecycleAction(fg.taskId, fg.pkg, BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE);
                }
                return windowLifecycleAction(fg.taskId, fg.pkg, BridgeProtocol.WIN_ACTION_MINIMIZE);
            }
            case BridgeProtocol.SHORTCUT_CLOSE_FOCUSED: {
                ManagedWindow fg = findFocusedNonMinimizedUserWindow();
                return fg != null && windowLifecycleAction(fg.taskId, fg.pkg, BridgeProtocol.WIN_ACTION_CLOSE);
            }
            default:
                return false;
        }
    }

    private boolean snapFocusedWindowToZone(int snapZone) {
        ManagedWindow fg = findFocusedNonMinimizedUserWindow();
        if (fg == null) return false;
        WindowRect rect = DesktopWindowManager.computeSnapBounds(
                snapZone, config.virtW, config.virtH, config.virtDpi, windowManager.isTaskbarAutoHide());
        return updateWindowBounds(fg.taskId, fg.pkg, DesktopWindowManager.INT_MODE_FREEFORM,
                rect.left, rect.top, rect.right, rect.bottom, false);
    }
    public DesktopWindowManager getWindowManager() { return windowManager; }
    public TwoSlotSplitSnapshot getTwoSlotSplitSnapshot() { return tiling.getTwoSlotSplitSnapshot(); }

    // Delegated read-only task/app queries & tiling commands
    public boolean isTaskAllowedForExtControl(int tid, String pkg, boolean blockSettings) { return queries.isTaskAllowedForExtControl(tid, pkg, blockSettings); }
    public Intent resolveSafeLaunchIntent(String pkg, String cls) { return queries.resolveSafeLaunchIntent(pkg, cls); }
    public int findTaskIdForPkg(String pkg, boolean includeRecents) { return queries.findTaskIdForPkg(pkg, includeRecents); }
    public ArrayList<Integer> collectExtUserTaskIds(int displayId) { return queries.collectExtUserTaskIds(displayId); }
    public String resolveAppLabel(String pkg) { return queries.resolveAppLabel(pkg); }
    public String[] findTopUserAppOnDisplay(int displayId) { return queries.findTopUserAppOnDisplay(displayId); }
    public void writeTasksToParcel(Parcel reply) { queries.writeTasksToParcel(reply); }
    public void captureTaskSnapshotToParcel(int reqTid, String pkg, int modeFlag, Parcel reply) { queries.captureTaskSnapshotToParcel(reqTid, pkg, modeFlag, reply); }
    public OpalCastProtocol.AppListMsg collectAppList(Set<Integer> extDisplayIds) { return queries.collectAppList(extDisplayIds); }
    public boolean setTilingLayout(String topologyRaw, List<String> reqPkgs, int[] reqRatios) { return tiling.setTilingLayout(topologyRaw, reqPkgs, reqRatios); }
    public boolean handleSplitAction(int action, int arg, String pkg, String cls) { return tiling.handleSplitAction(action, arg, pkg, cls); }
    public void exitTilingAndRestore(boolean focusSlot0) { tiling.exitTilingAndRestore(focusSlot0); }

    public boolean moveTaskToDisplayIfNeeded(int taskId, int targetDisplayId) {
        if (taskId <= 0 || targetDisplayId < 0) return false;
        return queries.getRunningTaskDisplayId(taskId) == targetDisplayId
                || taskApi.moveRootTaskToDisplay(taskId, targetDisplayId);
    }

    public static ActivityOptions makeLaunchOptions(int displayId, int windowingMode, Rect bounds) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        if (displayId > 0) {
            opts.setLaunchBounds(bounds);
            applyLaunchWindowingMode(opts, windowingMode);
        }
        return opts;
    }

    // ---- Shared Single-Window Transition Engine ----

    private int desktopDockInsetPx() {
        return windowManager.computeActiveTaskbarInsetPx(config.virtH, config.virtDpi);
    }

    void applySingleWindowMode(
            int taskId, String pkg, String requestedMode, WindowRect explicitBounds,
            boolean bringToFront, boolean focused, boolean rememberPref) {
        int vdId = config.vdId;
        if (taskId <= 0 || vdId <= 0) return;
        int virtW = config.virtW, virtH = config.virtH, virtDpi = config.virtDpi;
        String safePkg = (pkg != null) ? pkg : "";

        ManagedWindow prevWindow = windowManager.getManagedWindow(taskId);
        String mode = requestedMode;
        WindowRect rect;
        if (DesktopWindowManager.MODE_FULLSCREEN.equals(mode)) {
            rect = new WindowRect(0, 0, virtW, virtH);
        } else if (explicitBounds != null && !explicitBounds.isEmpty()) {
            int effDpi = Math.max(120, virtDpi > 0 ? virtDpi : 240);
            int minTop = DesktopWindowManager.MODE_FREEFORM.equals(mode)
                    ? DisplayGeometryUtil.dpToPx(
                            DisplayGeometryUtil.WORKAREA_TOP_INSET_DP + DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, effDpi)
                    : 0;
            int workBottom = Math.max(360, virtH - desktopDockInsetPx());
            rect = DesktopWindowManager.clampBoundsToWorkArea(explicitBounds, virtW, workBottom, minTop);
        } else if (DesktopWindowManager.MODE_FREEFORM.equals(mode)) {
            rect = windowManager.computeNextFreeformBounds(virtW, virtH, virtDpi, desktopDockInsetPx(), safePkg);
        } else {
            mode = DesktopWindowManager.MODE_ADAPTIVE_SINGLE;
            rect = windowManager.computeAdaptiveSingleBounds(virtW, virtH, virtDpi, desktopDockInsetPx(), safePkg, 0, 0);
        }
        if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(mode) && rect.width() >= virtW - FULLSCREEN_WIDTH_TOLERANCE_PX) {
            mode = DesktopWindowManager.MODE_FULLSCREEN;
            rect = new WindowRect(0, 0, virtW, virtH);
        }

        String presentation = DesktopWindowManager.classifyPresentation(
                mode, rect, virtW, virtH, virtDpi, windowManager.isTaskbarAutoHide());
        windowManager.putManagedWindow(taskId, safePkg, mode, presentation, rect, focused, -1);
        if (DesktopWindowManager.MODE_FULLSCREEN.equals(mode)) {
            if (!safePkg.isEmpty() && config.fullscreenPolicy == 1
                    && config.getAppMode(safePkg) == BridgeProtocol.APP_MODE_DEFAULT
                    && PackagePolicy.shouldCompatOverridePkg(safePkg)) {
                bgIoExec.execute(() -> compatPolicy.applyAppModeCompat(safePkg, BridgeProtocol.APP_MODE_LAND_FULL, false));
            }
            if (bringToFront) moveTaskToDisplayIfNeeded(taskId, vdId);
            taskApi.detachFreeformAndEnsureFullscreen(taskId, vdId, virtW, virtH, true, mainHandler);
            if (bringToFront) {
                taskApi.startActivityFromRecents(taskId, makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
            }
            if (rememberPref && !safePkg.isEmpty()) {
                windowManager.rememberAppPref(safePkg, DesktopWindowManager.MODE_FULLSCREEN, 100, 0, null);
            }
        } else {
            Rect ar = rect.toAndroidRect();
            // Fast-path pure translation moves (width/height unchanged in active MULTI_WINDOW)
            // to keep live dragging responsive without redundant WCT/resizeable roundtrips.
            boolean pureTranslation = !bringToFront
                    && prevWindow != null
                    && !prevWindow.minimized
                    && DesktopWindowManager.MODE_FREEFORM.equals(prevWindow.mode)
                    && DesktopWindowManager.MODE_FREEFORM.equals(mode)
                    && prevWindow.bounds != null
                    && prevWindow.bounds.width() == rect.width()
                    && prevWindow.bounds.height() == rect.height();
            if (pureTranslation && taskApi.getTaskWindowingMode(taskId) == BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW) {
                taskApi.resizeTask(taskId, ar, TaskApiAdapter.RESIZE_MODE_USER);
            } else {
                taskApi.ensureTaskResizeableAndMultiWindow(taskId, vdId, ar, true);
                if (bringToFront) {
                    bringMultiWindowTaskFront(taskId, ar);
                    taskApi.ensureTaskResizeableAndMultiWindow(taskId, vdId, ar, true);
                    if (mainHandler != null) {
                        final int fTid = taskId;
                        final int fVd = vdId;
                        final Rect fAr = new Rect(ar);
                        for (long delay : new long[]{80L, 240L}) {
                            mainHandler.postDelayed(() -> {
                                if (config.vdId != fVd || config.shuttingDown) return;
                                ManagedWindow curMw = windowManager.getManagedWindow(fTid);
                                if (curMw == null || curMw.minimized || DesktopWindowManager.MODE_FULLSCREEN.equals(curMw.mode)) return;
                                int curWm = taskApi.getTaskWindowingMode(fTid);
                                Rect curB = taskApi.getTaskBounds(fTid, fVd);
                                if (curWm != BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW || curB == null || !curB.equals(fAr)) {
                                    taskApi.ensureTaskResizeableAndMultiWindow(fTid, fVd, fAr, true);
                                    notifyStateChanged();
                                }
                            }, delay);
                        }
                    }
                }
            }
            if (rememberPref && !safePkg.isEmpty()) {
                if (DesktopWindowManager.MODE_FREEFORM.equals(mode)) {
                    windowManager.rememberAppPref(safePkg, DesktopWindowManager.MODE_FREEFORM, 0, 0, rect);
                } else {
                    int pct = DesktopWindowManager.clampWidthPct(Math.round(rect.width() * 100f / Math.max(1, virtW)));
                    int dp = Math.round(rect.width() * 160f / Math.max(120, virtDpi));
                    windowManager.rememberAppPref(safePkg, DesktopWindowManager.MODE_ADAPTIVE_SINGLE, pct, dp, null);
                }
            }
        }
    }

    // ---- Launch, Switch & Window Lifecycle ----

    public void launchLauncher() { launchComponentOnVd(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_LAUNCHER_CLASS); }

    public boolean relaunchLauncherOrMinimize() {
        TwoSlotSplitSnapshot snap = getTwoSlotSplitSnapshot();
        return (snap.active && snap.emptySlotIndex() >= 0)
                ? launchComponentOnVd(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_LAUNCHER_CLASS)
                : minimizeToExtHome(-1, null);
    }

    public boolean launchComponentOnVd(String pkg, String cls) {
        int vdId = config.vdId;
        if (vdId <= 0 || !queries.isAllowedLaunchTarget(pkg, cls)) return false;
        boolean isLauncher = PackagePolicy.isHelperLauncherComponent(pkg, cls);
        TwoSlotSplitSnapshot split = getTwoSlotSplitSnapshot();

        if (!isLauncher && !windowManager.isTilingActive()) {
            String rm = windowManager.resolveLaunchMode(pkg, DesktopWindowManager.INT_MODE_AUTO);
            if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(rm) || DesktopWindowManager.MODE_FREEFORM.equals(rm)) {
                return launchInWindowMode(pkg, cls, DesktopWindowManager.MODE_FREEFORM.equals(rm)
                        ? DesktopWindowManager.INT_MODE_FREEFORM : DesktopWindowManager.INT_MODE_ADAPTIVE_SINGLE, 0, 0, 0, 0);
            }
        }
        if (split.active) {
            if (isLauncher) {
                int empty = split.emptySlotIndex();
                if (empty >= 0) { tiling.syncLauncherForEmptyTwoSlot(empty, true); return true; }
                tiling.ensureLauncherTaskFullscreenInBg();
            } else if (!BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)) {
                int slot = TilingSessionController.matchOrAutoFillTwoSlot(split, pkg);
                if (slot >= 0) return handleSplitAction(slot == 0 ? BridgeProtocol.SPLIT_ASSIGN_LEFT_TOP : BridgeProtocol.SPLIT_ASSIGN_RIGHT_BOTTOM, -1, pkg, cls);
                exitTilingAndRestore(false);
            }
        } else if (!isLauncher && windowManager.isTilingActive()) {
            setTilingLayout(TilingLayoutEngine.TOPOLOGY_NONE, Collections.emptyList(), new int[0]);
        }

        int preTid = isLauncher ? queries.findExtLauncherTaskId() : findTaskIdForPkg(pkg, true);
        if (preTid > 0) {
            int wm = taskApi.getTaskWindowingMode(preTid);
            if (wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && wm != BridgeProtocol.WINDOWING_MODE_UNDEFINED) {
                taskApi.detachFreeformAndEnsureFullscreen(preTid, vdId, config.virtW, config.virtH, true, mainHandler);
            }
        }
        compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
        Intent intent = new Intent().setComponent(new ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)) intent.putExtra("extId", vdId);
        boolean ok = services.startActivityAsShell(intent, makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
        if (mainHandler != null) mainHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1), 180L);
        return ok;
    }

    public boolean launchInWindowMode(String pkg, String cls, int modeInt, int left, int top, int right, int bottom) {
        int vdId = config.vdId;
        if (vdId <= 0) return false;
        Intent intent = resolveSafeLaunchIntent(pkg, cls);
        if (intent == null) return false;
        if (windowManager.isTilingActive()) exitTilingAndRestore(false);

        String targetMode = windowManager.resolveLaunchMode(pkg, modeInt);
        WindowRect explicit = (right - left >= 200 && bottom - top >= 200)
                ? DesktopWindowManager.clampBoundsToWorkArea(new WindowRect(left, top, right, bottom), config.virtW, Math.max(360, config.virtH))
                : null;
        int existingTid = findTaskIdForPkg(pkg, true);
        if (existingTid > 0 && isTaskAllowedForExtControl(existingTid, pkg, false)) {
            applySingleWindowMode(existingTid, pkg, targetMode, explicit, true, true, false);
            notifyStateChanged();
            return true;
        }
        WindowRect rect = DesktopWindowManager.MODE_FULLSCREEN.equals(targetMode) ? new WindowRect(0, 0, config.virtW, config.virtH)
                : (explicit != null ? explicit : (DesktopWindowManager.MODE_FREEFORM.equals(targetMode)
                        ? windowManager.computeNextFreeformBounds(config.virtW, config.virtH, config.virtDpi, desktopDockInsetPx(), pkg)
                        : windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, desktopDockInsetPx(), pkg, 0, 0)));
        if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(targetMode) && rect.width() >= config.virtW - FULLSCREEN_WIDTH_TOLERANCE_PX) {
            targetMode = DesktopWindowManager.MODE_FULLSCREEN;
            rect = new WindowRect(0, 0, config.virtW, config.virtH);
        }
        boolean useMw = !DesktopWindowManager.MODE_FULLSCREEN.equals(targetMode);
        if (!useMw) {
            pendingFullscreenPkg = pkg;
            pendingFullscreenUntilMs = SystemClock.uptimeMillis() + PENDING_FULLSCREEN_GRACE_MS;
            if (config.fullscreenPolicy == 1 && config.getAppMode(pkg) == BridgeProtocol.APP_MODE_DEFAULT
                    && PackagePolicy.shouldCompatOverridePkg(pkg)) {
                compatPolicy.applyAppModeCompat(pkg, BridgeProtocol.APP_MODE_LAND_FULL, false);
            } else {
                compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
            }
        } else {
            if (pkg.equals(pendingFullscreenPkg)) {
                pendingFullscreenPkg = "";
                pendingFullscreenUntilMs = 0L;
            }
            compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
        }
        if (useMw) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        // Launch directly in WINDOWING_MODE_MULTI_WINDOW (6) + setLaunchBounds(rect) so OEM freeform
        // managers (such as Xiaomi HyperOS MiuiFreeFormManagerService on mode 5) never hijack task
        // scale, leash position, or inject native OEM white caption headers.
        int launchWindowingMode = useMw ? BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW : BridgeProtocol.WINDOWING_MODE_FULLSCREEN;
        boolean ok = services.startActivityAsShell(intent, makeLaunchOptions(
                vdId, launchWindowingMode,
                useMw ? rect.toAndroidRect() : null).toBundle());
        if (ok) {
            final String fMode = targetMode;
            final WindowRect fRect = rect;
            if (useMw) {
                verifyLaunchedWindowAsync(pkg, fMode, fRect, true, true);
            }
            if (mainHandler != null) {
                long[] delays = useMw ? new long[]{20L, 70L, 180L, 380L} : new long[]{200L, 600L};
                for (int i = 0; i < delays.length; i++) {
                    final boolean forceSync = (i <= 1);
                    mainHandler.postDelayed(() -> verifyLaunchedWindowAsync(pkg, fMode, fRect, useMw, forceSync), delays[i]);
                }
            }
        }
        notifyStateChanged();
        return ok;
    }

    /**
     * Ultra-low-latency live drag path: updates managed bounds in RAM and invokes cached
     * {@code ATMS.resizeTask(..., RESIZE_MODE_USER)} directly with zero {@code getTasks()},
     * zero {@code WCT.reorder}, and zero state-push IPC roundtrips per frame.
     */
    public boolean moveWindowLive(int taskId, int left, int top, int right, int bottom) {
        int vdId = config.vdId;
        if (vdId <= 0 || taskId <= 0 || right - left < 200 || bottom - top < 200) return false;
        ManagedWindow prev = windowManager.getManagedWindow(taskId);
        if (prev == null || prev.minimized) return false;

        int virtW = config.virtW, virtH = config.virtH, virtDpi = config.virtDpi;
        int effDpi = Math.max(120, virtDpi > 0 ? virtDpi : 240);
        int minTop = DisplayGeometryUtil.dpToPx(
                DisplayGeometryUtil.WORKAREA_TOP_INSET_DP + DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, effDpi);
        int workBottom = Math.max(360, virtH - desktopDockInsetPx());
        WindowRect rect = DesktopWindowManager.clampBoundsToWorkArea(
                new WindowRect(left, top, right, bottom), virtW, workBottom, minTop);

        lastLiveMoveTaskId = taskId;
        lastLiveMoveMs = SystemClock.uptimeMillis();

        // If the task wasn't in FREEFORM yet (e.g. first drag frame out of ADAPTIVE_SINGLE/FULLSCREEN),
        // transition it cleanly once; all subsequent drag frames stay on the 1-IPC fast path.
        if (!DesktopWindowManager.MODE_FREEFORM.equals(prev.mode)) {
            tiling.clearTilingIfActive();
            applySingleWindowMode(taskId, prev.pkg, DesktopWindowManager.MODE_FREEFORM, rect, false, true, false);
            return true;
        }

        String presentation = DesktopWindowManager.classifyPresentation(
                DesktopWindowManager.MODE_FREEFORM, rect, virtW, virtH, virtDpi, windowManager.isTaskbarAutoHide());
        windowManager.putManagedWindow(taskId, prev.pkg, DesktopWindowManager.MODE_FREEFORM, presentation, rect, true, -1);
        taskApi.resizeTask(taskId, rect.toAndroidRect(), TaskApiAdapter.RESIZE_MODE_USER);
        return true;
    }

    public boolean updateWindowBounds(
            int taskId, String pkg, int modeInt, int left, int top, int right, int bottom, boolean rememberPref) {
        int vdId = config.vdId;
        if (vdId <= 0) return false;
        String safePkg = PackagePolicy.isValidPackageName(pkg) ? pkg : null;
        boolean directAllowed = taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false);
        int tid = directAllowed ? taskId : (safePkg != null ? findTaskIdForPkg(safePkg, true) : -1);
        if (tid <= 0) {
            String topPkg = findTopUserAppOnDisplay(vdId)[0];
            if (!topPkg.isEmpty()) { safePkg = topPkg; tid = findTaskIdForPkg(safePkg, true); }
        }
        if (tid <= 0 || (!directAllowed && !isTaskAllowedForExtControl(tid, safePkg, false))) return false;
        if (safePkg == null) {
            ManagedWindow mw = windowManager.getManagedWindow(tid);
            if (mw != null && !mw.pkg.isEmpty()) safePkg = mw.pkg;
        }
        tiling.clearTilingIfActive();
        String mode = (modeInt == DesktopWindowManager.INT_MODE_FULLSCREEN) ? DesktopWindowManager.MODE_FULLSCREEN
                : (modeInt == DesktopWindowManager.INT_MODE_FREEFORM ? DesktopWindowManager.MODE_FREEFORM : DesktopWindowManager.MODE_ADAPTIVE_SINGLE);
        WindowRect explicit = (right - left >= 200 && bottom - top >= 200) ? new WindowRect(left, top, right, bottom) : null;
        boolean bringFront = DesktopWindowManager.MODE_FULLSCREEN.equals(mode);
        ManagedWindow beforeCommit = windowManager.getManagedWindow(tid);
        boolean alreadyFocusedFreeform = beforeCommit != null
                && !beforeCommit.minimized
                && beforeCommit.focused
                && DesktopWindowManager.MODE_FREEFORM.equals(beforeCommit.mode);
        applySingleWindowMode(tid, safePkg, mode, explicit, bringFront, true, rememberPref);
        if (DesktopWindowManager.MODE_FREEFORM.equals(mode) && !alreadyFocusedFreeform) {
            taskApi.reorderTask(tid, vdId, true);
        }
        notifyStateChanged();
        return true;
    }

    public boolean setSingleWidthPct(String pkg, int widthPct, boolean remember) {
        if (config.vdId <= 0) return false;
        String target = PackagePolicy.isValidPackageName(pkg) ? pkg : findTopUserAppOnDisplay(config.vdId)[0];
        int tid = target.isEmpty() ? -1 : findTaskIdForPkg(target, true);
        if (tid <= 0) return false;
        int pct = DesktopWindowManager.clampWidthPct(widthPct);
        if (pct >= 98) return updateWindowBounds(tid, target, DesktopWindowManager.INT_MODE_FULLSCREEN, 0, 0, config.virtW, config.virtH, remember);
        WindowRect r = windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, desktopDockInsetPx(), target, pct, 0);
        return updateWindowBounds(tid, target, DesktopWindowManager.INT_MODE_ADAPTIVE_SINGLE, r.left, r.top, r.right, r.bottom, remember);
    }

    public boolean switchToTaskOnVd(int taskId, String pkg) {
        int vdId = config.vdId;
        if (vdId <= 0) return false;
        String safePkg = PackagePolicy.isValidPackageName(pkg) ? pkg : null;
        if (safePkg != null && PackagePolicy.isProtectedSystemTaskPkg(safePkg) && !"com.android.settings".equals(safePkg)) return false;
        int safeTid = (taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false)) ? taskId : -1;
        if (safeTid <= 0 && safePkg == null) return false;

        TwoSlotSplitSnapshot split = getTwoSlotSplitSnapshot();
        if (split.active && safePkg != null && !BridgeBuildConfig.HELPER_PACKAGE.equals(safePkg)) {
            int slot = TilingSessionController.matchOrAutoFillTwoSlot(split, safePkg);
            if (slot >= 0) return handleSplitAction(slot == 0 ? BridgeProtocol.SPLIT_ASSIGN_LEFT_TOP : BridgeProtocol.SPLIT_ASSIGN_RIGHT_BOTTOM, safeTid, safePkg, null);
            exitTilingAndRestore(false);
        } else if (windowManager.isTilingActive() && safePkg != null
                && !BridgeBuildConfig.HELPER_PACKAGE.equals(safePkg)
                && !windowManager.isPkgInActiveTiling(safePkg)) {
            exitTilingAndRestore(false);
        }
        compatPolicy.applyAppModeCompat(safePkg, config.getAppMode(safePkg), false);

        if (safeTid > 0) {
            ManagedWindow mw = windowManager.getManagedWindow(safeTid);
            if (windowManager.isTaskManagedInMultiWindow(safeTid, safePkg)
                    && !shouldPromoteAdaptiveToFullscreen(mw, safePkg)) {
                WindowRect b = (mw != null && mw.bounds != null && !mw.bounds.isEmpty()) ? mw.bounds
                        : windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, 0, safePkg, 0, 0);
                Rect ar = b.toAndroidRect();
                taskApi.ensureTaskResizeableAndMultiWindow(safeTid, vdId, ar, true);
                bringMultiWindowTaskFront(safeTid, ar);
                windowManager.setFocusedTask(safeTid);
                notifyStateChanged();
                return true;
            }
            String mode = (safePkg != null && !BridgeBuildConfig.HELPER_PACKAGE.equals(safePkg) && !windowManager.isTilingActive())
                    ? windowManager.resolveLaunchMode(safePkg, DesktopWindowManager.INT_MODE_AUTO)
                    : DesktopWindowManager.MODE_FULLSCREEN;
            applySingleWindowMode(safeTid, safePkg, mode, null, true, true, false);
            if (mainHandler != null && DesktopWindowManager.MODE_FULLSCREEN.equals(mode)) {
                mainHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1), 180L);
            }
            notifyStateChanged();
            return true;
        }
        if (safePkg != null && !BridgeBuildConfig.HELPER_PACKAGE.equals(safePkg) && !windowManager.isTilingActive()) {
            return launchInWindowMode(safePkg, "", DesktopWindowManager.INT_MODE_AUTO, 0, 0, 0, 0);
        }
        Intent li = (safePkg != null && services.context != null) ? services.context.getPackageManager().getLaunchIntentForPackage(safePkg) : null;
        if (li == null) return false;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return services.startActivityAsShell(li, makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
    }

    public boolean minimizeToExtHome(int taskId, String pkg) {
        int vdId = config.vdId;
        if (vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return false;
        if (windowManager.isTilingActive()) exitTilingAndRestore(false);
        windowManager.minimizeAllWindows();
        int lTid = queries.findExtLauncherTaskId();
        if (lTid > 0) {
            taskApi.detachFreeformAndEnsureFullscreen(lTid, vdId, config.virtW, config.virtH,
                    taskApi.getTaskWindowingMode(lTid) != BridgeProtocol.WINDOWING_MODE_FULLSCREEN, mainHandler);
            if (taskApi.startActivityFromRecents(lTid, makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle())) {
                if (mainHandler != null) mainHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1), 180L);
                notifyStateChanged();
                return true;
            }
        }
        boolean ok = launchComponentOnVd(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_LAUNCHER_CLASS);
        if (ok) notifyStateChanged();
        return ok;
    }

    public boolean removeTaskById(int taskId) {
        if (!isTaskAllowedForExtControl(taskId, null, true) || !taskApi.removeTask(taskId)) return false;
        windowManager.removeManagedWindow(taskId);
        tiling.onTaskRemovedAfterPrune();
        notifyStateChanged();
        return true;
    }

    public boolean windowLifecycleAction(int taskId, String pkg, int action) {
        int vdId = config.vdId;
        if (vdId <= 0) return false;
        String safePkg = PackagePolicy.isValidPackageName(pkg) ? pkg : null;
        int tid = (taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false))
                ? taskId : (safePkg != null ? findTaskIdForPkg(safePkg, true) : -1);
        if (safePkg == null && tid > 0) {
            ManagedWindow mw = windowManager.getManagedWindow(tid);
            if (mw != null && !mw.pkg.isEmpty()) safePkg = mw.pkg;
        }
        switch (action) {
            case BridgeProtocol.WIN_ACTION_FOCUS:
                if (tid <= 0) return false;
                ManagedWindow mw = windowManager.getManagedWindow(tid);
                if (mw != null && !DesktopWindowManager.MODE_FULLSCREEN.equals(mw.mode)
                        && mw.bounds != null && !mw.bounds.isEmpty()
                        && !shouldPromoteAdaptiveToFullscreen(mw, safePkg)) {
                    Rect r = mw.bounds.toAndroidRect();
                    taskApi.ensureTaskResizeableAndMultiWindow(tid, vdId, r, true);
                    bringMultiWindowTaskFront(tid, r);
                    windowManager.setFocusedTask(tid);
                } else {
                    switchToTaskOnVd(tid, safePkg);
                }
                notifyStateChanged();
                return true;
            case BridgeProtocol.WIN_ACTION_MINIMIZE:
                if (tid > 0) {
                    windowManager.setMinimizedTask(tid, true);
                    taskApi.reorderTask(tid, vdId, false);
                }
                ManagedWindow nextFocus = null;
                for (ManagedWindow m : windowManager.getManagedWindowsSnapshot()) {
                    if (!m.minimized && m.taskId != tid && DesktopWindowManager.MODE_FREEFORM.equals(m.mode)) {
                        nextFocus = m;
                    }
                }
                if (nextFocus == null) {
                    minimizeToExtHome(tid, safePkg);
                } else {
                    // Only focus the next top non-minimized window without thrashing Launcher or
                    // reordering every remaining task (prevents desktop flash & Z-order scramble).
                    taskApi.reorderTask(nextFocus.taskId, vdId, true);
                    windowManager.setFocusedTask(nextFocus.taskId);
                }
                notifyStateChanged();
                return true;
            case BridgeProtocol.WIN_ACTION_CLOSE:
                return tid > 0 && removeTaskById(tid);
            case BridgeProtocol.WIN_ACTION_RELOAD_DPI:
                if (safePkg == null || safePkg.isEmpty()) safePkg = findTopUserAppOnDisplay(vdId)[0];
                if (safePkg == null || safePkg.isEmpty()) return false;
                reloadAppDpiOnDesktop(safePkg);
                return true;
            case BridgeProtocol.WIN_ACTION_SEND_BACK: {
                if (tid > 0) {
                    ManagedWindow target = windowManager.getManagedWindow(tid);
                    if (target != null && !target.focused) {
                        windowLifecycleAction(tid, safePkg, BridgeProtocol.WIN_ACTION_FOCUS);
                        SystemClock.sleep(35L);
                    }
                }
                Runnable sendBack = onSendBackKey;
                if (sendBack != null) {
                    sendBack.run();
                    return true;
                }
                return false;
            }
            case BridgeProtocol.WIN_ACTION_TOGGLE_MAXIMIZE: {
                if (tid <= 0) return false;
                WindowRect nextRect = windowManager.resolveToggleMaximizeTarget(
                        tid, safePkg, config.virtW, config.virtH, config.virtDpi);
                applySingleWindowMode(tid, safePkg, DesktopWindowManager.MODE_FREEFORM, nextRect, false, true, false);
                taskApi.reorderTask(tid, vdId, true);
                notifyStateChanged();
                return true;
            }
            default:
                return false;
        }
    }

    public void setAdaptiveSingleConfig(boolean enabled, int defaultWidthDp) {
        windowManager.setAdaptiveSingleConfig(enabled, defaultWidthDp);
        if (config.vdId > 0 && config.mirrorMode == BridgeProtocol.MIRROR_MODE_DEX && !windowManager.isTilingActive()) {
            String topPkg = findTopUserAppOnDisplay(config.vdId)[0];
            int tid = topPkg.isEmpty() ? -1 : findTaskIdForPkg(topPkg, true);
            if (tid <= 0) {
                ManagedWindow focused = findFocusedNonMinimizedUserWindow();
                if (focused != null) {
                    tid = focused.taskId;
                    topPkg = focused.pkg;
                }
            }
            ManagedWindow cur = (tid > 0) ? windowManager.getManagedWindow(tid) : null;
            if (tid > 0) {
                if (!enabled) {
                    // Bring to front in FULLSCREEN so MIUI/HyperOS cleanly removes the multi-window caption handle.
                    applySingleWindowMode(tid, topPkg, DesktopWindowManager.MODE_FULLSCREEN, null, true, true, false);
                } else if (cur == null || !DesktopWindowManager.MODE_FREEFORM.equals(cur.mode)) {
                    applySingleWindowMode(tid, topPkg, DesktopWindowManager.MODE_ADAPTIVE_SINGLE, null, false, true, false);
                }
            }
        }
        notifyStateChanged();
    }

    public void reloadAppDpiOnDesktop(String pkg) {
        reloadAppDpiWithCallback(pkg, () -> {
            if (config.vdId > 0) launchInWindowMode(pkg, "", DesktopWindowManager.INT_MODE_AUTO, 0, 0, 0, 0);
        });
    }

    public void reloadAppDpiWithCallback(String pkg, Runnable relaunchAction) {
        if (!PackagePolicy.isValidPackageName(pkg) || PackagePolicy.isProtectedSystemTaskPkg(pkg)
                || BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)) return;
        bgIoExec.execute(() -> {
            ShellCommand.runOutput("am", "force-stop", pkg);
            SystemClock.sleep(150L);
            if (relaunchAction != null) relaunchAction.run();
        });
    }

    public boolean returnTaskToDesktop(String pkg, int taskId) {
        if (config.vdId <= 0) return false;
        String safePkg = PackagePolicy.isValidPackageName(pkg) ? pkg : "";
        int tid = (taskId > 0) ? taskId : findTaskIdForPkg(safePkg, true);
        if (tid <= 0) return !safePkg.isEmpty() && launchInWindowMode(safePkg, "", DesktopWindowManager.INT_MODE_AUTO, 0, 0, 0, 0);
        moveTaskToDisplayIfNeeded(tid, config.vdId);
        applySingleWindowMode(tid, safePkg, windowManager.resolveLaunchMode(safePkg, DesktopWindowManager.INT_MODE_AUTO), null, true, true, false);
        notifyStateChanged();
        return true;
    }

    public boolean setAppModeAndMaybeRestart(String pkg, int mode, boolean restart) {
        int clamped = Math.max(BridgeProtocol.APP_MODE_DEFAULT, Math.min(BridgeProtocol.APP_MODE_PORT_COMPAT, mode));
        if (!config.setAppMode(pkg, clamped)) return false;
        Intent li = (services.context != null) ? services.context.getPackageManager().getLaunchIntentForPackage(pkg) : null;
        if (restart && li != null) ShellCommand.runOutput("am", "force-stop", pkg);
        compatPolicy.applyAppModeCompat(pkg, clamped, true);
        if (restart && li != null && config.vdId > 0 && config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR) {
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            services.startActivityAsShell(li, makeLaunchOptions(config.vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
        }
        helperBridge.notifyHelperSync();
        return true;
    }

    // ---- Periodic Enforcement, Resize Relayout & Managed Windows JSON ----

    public void beginDisplayMigration() {
        displayMigrationUntilMs = SystemClock.uptimeMillis() + 1800L;
    }

    public void endDisplayMigration() {
        displayMigrationUntilMs = 0L;
    }

    public boolean isDisplayMigrating() {
        return SystemClock.uptimeMillis() < displayMigrationUntilMs;
    }

    /**
     * Restores every user task on {@code displayId} back to fullscreen with cleared freeform bounds
     * while paused behind {@code LauncherActivity} BEFORE {@code vd.release()}, preventing active
     * multi-window SurfaceFlinger leashes or half-screen bounds from carrying over onto Display 0.
     *
     * <p>Returns the restored user task IDs (plus any virtual launcher task ID encoded as negative
     * {@code -launcherTid} at the tail so post-teardown cleanup removes it after {@code vd.release()}).
     */
    public List<Integer> restoreDisplayTasksForTeardown(int displayId, int dispW, int dispH) {
        if (displayId <= 0) return Collections.emptyList();
        LinkedHashSet<Integer> userTids = new LinkedHashSet<>(collectExtUserTaskIds(displayId));
        for (ManagedWindow mw : windowManager.getManagedWindowsSnapshot()) {
            if (mw != null && mw.taskId > 0
                    && !BridgeBuildConfig.HELPER_PACKAGE.equals(mw.pkg)
                    && !PackagePolicy.isProtectedSystemTaskPkg(mw.pkg)) {
                userTids.add(mw.taskId);
            }
        }
        int launcherTid = queries.findExtLauncherTaskId();
        if (launcherTid > 0) {
            userTids.remove(launcherTid);
        }
        if (userTids.isEmpty()) {
            if (launcherTid > 0) taskApi.removeTask(launcherTid);
            return Collections.emptyList();
        }

        // Step 1: Bring LauncherActivity to the front of displayId so every visible user task
        // genuinely transitions to visible=false / paused BEFORE vd.release() (destroying HyperOS
        // active multi-window SurfaceFlinger animation leashes & letterbox crops).
        boolean hadVisibleUserTask = false;
        List<?> runningBefore = taskApi.getTasks();
        if (runningBefore != null) {
            for (Object t : runningBefore) {
                if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != displayId) continue;
                int tid = TaskApiAdapter.extractTaskId(t);
                if (userTids.contains(tid) && TaskApiAdapter.readBoolField(t, "isVisible", false)) {
                    hadVisibleUserTask = true;
                    break;
                }
            }
        }
        if (hadVisibleUserTask) {
            if (launcherTid > 0) {
                taskApi.startActivityFromRecents(
                        launcherTid, makeLaunchOptions(displayId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
                taskApi.reorderTask(launcherTid, displayId, true);
            }
            for (int tid : userTids) {
                taskApi.reorderTask(tid, displayId, false);
            }
            SystemClock.sleep(180L);
        }

        // Step 2: Reset every paused user task's SurfaceFlinger position to (0, 0) via full-screen
        // resizeTask while invisible (no 350ms spring animation when hidden!), then switch to FULLSCREEN + empty bounds.
        int[] d0 = readDefaultDisplaySize(dispW, dispH);
        Rect fullBounds = new Rect(0, 0, Math.max(480, Math.max(dispW, d0[0])), Math.max(480, Math.max(dispH, d0[1])));
        for (int tid : userTids) {
            taskApi.resizeTask(tid, fullBounds);
        }
        SystemClock.sleep(60L);
        Rect emptyBounds = new Rect();
        for (int tid : userTids) {
            if (!taskApi.applyWctForTask(tid, displayId, Boolean.FALSE, TaskApiAdapter.WINDOWING_MODE_FULLSCREEN, emptyBounds)) {
                taskApi.clearTaskAlwaysOnTop(tid, displayId);
                taskApi.forceTaskWindowingMode(tid, displayId, TaskApiAdapter.WINDOWING_MODE_FULLSCREEN);
            }
        }
        SystemClock.sleep(60L);

        // Keep launcherTid alive on top during vd.release() so migrated user tasks land behind the
        // foreground on Display 0; post-teardown cleanup removes launcherTid immediately afterwards.
        ArrayList<Integer> out = new ArrayList<>(userTids);
        if (launcherTid > 0) {
            out.add(-launcherTid);
        }
        return out;
    }

    /**
     * Post-teardown safety sweep on Display 0: removes any leftover virtual Launcher task and
     * ensures migrated user tasks did not retain stale MULTI_WINDOW / FREEFORM bounds on Display 0.
     */
    public void schedulePostTeardownDisplay0Sanitize(List<Integer> userTaskIds) {
        if (userTaskIds == null || userTaskIds.isEmpty()) return;
        int launcherToRemove = -1;
        ArrayList<Integer> snapshot = new ArrayList<>();
        for (int v : userTaskIds) {
            if (v < 0) launcherToRemove = -v;
            else if (v > 0) snapshot.add(v);
        }
        final int fLauncherTid = launcherToRemove;
        Runnable removeLauncher = () -> {
            if (fLauncherTid > 0) taskApi.removeTask(fLauncherTid);
        };
        Runnable sweep = () -> {
            if (fLauncherTid > 0) taskApi.removeTask(fLauncherTid);
            sanitizeMigratedTasksOnDisplay0(snapshot);
        };
        if (mainHandler != null) {
            if (fLauncherTid > 0) mainHandler.postDelayed(removeLauncher, 60L);
            mainHandler.postDelayed(sweep, 240L);
            mainHandler.postDelayed(sweep, 700L);
        } else {
            bgIoExec.execute(sweep);
        }
    }

    private void sanitizeMigratedTasksOnDisplay0(List<Integer> userTaskIds) {
        if (userTaskIds == null || userTaskIds.isEmpty() || config.shuttingDown) return;
        List<?> running = taskApi.getTasks();
        if (running == null || running.isEmpty()) return;
        int[] d0 = readDefaultDisplaySize(config.virtW, config.virtH);
        int d0W = d0[0], d0H = d0[1];
        Set<Integer> targetSet = new HashSet<>(userTaskIds);
        for (Object t : running) {
            if (t == null) continue;
            int tid = TaskApiAdapter.extractTaskId(t);
            if (!targetSet.contains(tid)) continue;
            if (TaskApiAdapter.readIntField(t, "displayId", -1) != Display.DEFAULT_DISPLAY) continue;
            int wm = TaskApiAdapter.extractTaskWindowingMode(t);
            Rect curB = taskApi.getTaskBounds(tid, Display.DEFAULT_DISPLAY);
            boolean staleBounds = curB != null && !curB.isEmpty()
                    && (curB.left != 0 || curB.top != 0
                    || curB.width() < Math.min(d0W, d0H) - FULLSCREEN_WIDTH_TOLERANCE_PX
                    || curB.height() < Math.min(d0W, d0H) - FULLSCREEN_WIDTH_TOLERANCE_PX);
            if ((wm != TaskApiAdapter.WINDOWING_MODE_FULLSCREEN && wm != 0) || staleBounds) {
                taskApi.detachFreeformAndEnsureFullscreen(tid, Display.DEFAULT_DISPLAY, d0W, d0H, true, mainHandler);
            }
        }
    }

    private int[] readDefaultDisplaySize(int fallbackW, int fallbackH) {
        int w = Math.max(480, fallbackW);
        int h = Math.max(480, fallbackH);
        try {
            if (services.displayManager != null) {
                Display d0 = services.displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (d0 != null) {
                    Display.Mode mode = d0.getMode();
                    if (mode != null && mode.getPhysicalWidth() > 0 && mode.getPhysicalHeight() > 0) {
                        w = mode.getPhysicalWidth();
                        h = mode.getPhysicalHeight();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return new int[]{w, h};
    }

    public void finishDisplayMigrationAndRelayout(List<ManagedWindow> preserved) {
        if (preserved != null && !preserved.isEmpty()) {
            for (ManagedWindow mw : preserved) {
                if (mw == null || mw.taskId <= 0) continue;
                windowManager.putManagedWindow(mw.taskId, mw.pkg, mw.mode, mw.bounds, mw.focused, mw.slotIndex);
                if (mw.minimized) windowManager.setMinimizedTask(mw.taskId, true);
            }
        }
        if (mainHandler == null) {
            relayoutManagedWindowsOnResize();
            displayMigrationUntilMs = 0L;
            return;
        }
        mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 100L);
        mainHandler.postDelayed(() -> {
            relayoutManagedWindowsOnResize();
            displayMigrationUntilMs = 0L;
            notifyStateChanged();
        }, 360L);
    }

    public void postAttachLayoutSync() {
        if (isDisplayMigrating()) return;
        if (windowManager.isTilingActive()) relayoutManagedWindowsOnResize();
        else tiling.ensureLauncherTaskFullscreenInBg();
    }

    public void scheduleRelayoutAfterResize() {
        if (mainHandler == null) { relayoutManagedWindowsOnResize(); return; }
        mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 140L);
        mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 380L);
        if (getTwoSlotSplitSnapshot().active) {
            mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 900L);
            mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 1850L);
        }
    }

    public void relayoutManagedWindowsOnResize() {
        if (config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return;
        if (windowManager.isTilingActive()) { tiling.relayoutTilingOnResize(); return; }
        int effDpi = Math.max(120, config.virtDpi > 0 ? config.virtDpi : 240);
        boolean autoHide = windowManager.isTaskbarAutoHide();
        int workBottom = Math.max(360, config.virtH - desktopDockInsetPx());
        int headerTop = DisplayGeometryUtil.dpToPx(
                DisplayGeometryUtil.WORKAREA_TOP_INSET_DP + DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, effDpi);
        int minTitleTop = Math.min(Math.max(0, workBottom - 280), headerTop);
        for (ManagedWindow mw : windowManager.getManagedWindowsSnapshot()) {
            if (mw.minimized || mw.taskId <= 0) continue;
            if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(mw.mode)) {
                applySingleWindowMode(mw.taskId, mw.pkg, DesktopWindowManager.MODE_ADAPTIVE_SINGLE, null, false, mw.focused, false);
            } else if (DesktopWindowManager.MODE_FREEFORM.equals(mw.mode) && mw.bounds != null) {
                WindowRect target = recomputeBoundsForPresentation(mw.presentation, mw.bounds, config.virtW, config.virtH, effDpi, autoHide, workBottom, minTitleTop);
                applySingleWindowMode(mw.taskId, mw.pkg, DesktopWindowManager.MODE_FREEFORM, target, false, mw.focused, false);
            }
        }
        notifyStateChanged();
    }

    private static WindowRect recomputeBoundsForPresentation(
            String pres, WindowRect cur, int virtW, int virtH, int dpi, boolean autoHide, int workBottom, int minTitleTop) {
        if (DesktopWindowManager.PRES_MAXIMIZED.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_MAXIMIZE_WORKAREA, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_LEFT.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_LEFT_HALF, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_RIGHT.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_RIGHT_HALF, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_TL.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_TOP_LEFT, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_TR.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_TOP_RIGHT, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_BL.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_BOTTOM_LEFT, virtW, virtH, dpi, autoHide);
        }
        if (DesktopWindowManager.PRES_SNAPPED_BR.equals(pres)) {
            return DesktopWindowManager.computeSnapBounds(DesktopWindowManager.SNAP_BOTTOM_RIGHT, virtW, virtH, dpi, autoHide);
        }
        return DesktopWindowManager.clampBoundsToWorkArea(cur, virtW, workBottom, minTitleTop);
    }

    public void enforceRunningExtTasksFullscreen(boolean enforceCompat) {
        int vdId = config.vdId;
        if (vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX || isDisplayMigrating()) return;
        List<?> running = taskApi.getTasks();
        if (running == null) return;
        Set<Integer> liveTids = new HashSet<>();
        for (Object t : running) {
            if (t != null && TaskApiAdapter.readIntField(t, "displayId", -1) == vdId) {
                int tid = TaskApiAdapter.extractTaskId(t);
                if (tid > 0) liveTids.add(tid);
            }
        }
        windowManager.pruneDeadTasks(liveTids, 1800L);

        TwoSlotSplitSnapshot split = getTwoSlotSplitSnapshot();
        if (tiling.maybeDetachHiddenTwoSlotTasks(running, vdId, split)) return;
        int emptyTwoSlot = split.emptySlotIndex();

        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != vdId) continue;
            String pkg = TaskApiAdapter.extractTaskPkg(t);
            int tid = TaskApiAdapter.extractTaskId(t);
            boolean isVis = TaskApiAdapter.readBoolField(t, "isVisible", false);
            int wm = TaskApiAdapter.extractTaskWindowingMode(t);
            if (TaskQueryService.isLauncherTaskInfo(t)) {
                if (emptyTwoSlot < 0 && isVis && tid > 0 && wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && wm != 0) {
                    taskApi.detachFreeformAndEnsureFullscreen(tid, vdId, config.virtW, config.virtH, true, mainHandler);
                }
                continue;
            }
            if (!isVis) continue;
            if (tid > 0 && windowManager.isTaskManagedInMultiWindow(tid, pkg)) {
                if (tid == lastLiveMoveTaskId && (SystemClock.uptimeMillis() - lastLiveMoveMs) < 600L) {
                    continue;
                }
                ManagedWindow mw = windowManager.getManagedWindow(tid);
                Rect target = (mw != null && mw.bounds != null && !mw.bounds.isEmpty()) ? mw.bounds.toAndroidRect()
                        : windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, 0, pkg, 0, 0).toAndroidRect();
                Rect liveBounds = taskApi.getTaskBounds(tid, vdId);
                boolean boundsDrifted = liveBounds == null
                        || Math.abs(liveBounds.left - target.left) > 2
                        || Math.abs(liveBounds.top - target.top) > 2
                        || Math.abs(liveBounds.right - target.right) > 2
                        || Math.abs(liveBounds.bottom - target.bottom) > 2;
                if (wm != BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW || boundsDrifted) {
                    taskApi.ensureTaskResizeableAndMultiWindow(tid, vdId, target, true);
                }
                continue;
            }
            boolean isPendingFullscreen = pkg != null && pkg.equals(pendingFullscreenPkg)
                    && SystemClock.uptimeMillis() <= pendingFullscreenUntilMs;
            if (tid > 0 && isPendingFullscreen) {
                windowManager.putManagedWindow(tid, pkg, DesktopWindowManager.MODE_FULLSCREEN,
                        new WindowRect(0, 0, config.virtW, config.virtH), true, -1);
            } else if (tid > 0 && !PackagePolicy.isProtectedSystemTaskPkg(pkg)
                    && windowManager.shouldAutoApplyAdaptiveSingle(tid, pkg)) {
                String adoptedMode = windowManager.resolveLaunchMode(pkg, DesktopWindowManager.INT_MODE_AUTO);
                applySingleWindowMode(tid, pkg, adoptedMode, null, false, true, false);
                continue;
            }
            if (tid > 0 && wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && wm != 0) {
                taskApi.detachFreeformAndEnsureFullscreen(tid, vdId, config.virtW, config.virtH, true, mainHandler);
            }
            if (enforceCompat && PackagePolicy.shouldCompatOverridePkg(pkg)) {
                compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
            }
        }
        // Keep focused window synchronized with live top Z-order so clicking anywhere inside a
        // background desktop window updates Menu Bar & Window Chrome focus state automatically.
        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != vdId) continue;
            if (!TaskApiAdapter.readBoolField(t, "isVisible", false)) continue;
            String p = TaskApiAdapter.extractTaskPkg(t);
            if (PackagePolicy.shouldSkipTaskPkg(p) || PackagePolicy.isProtectedSystemTaskPkg(p)) continue;
            int topTid = TaskApiAdapter.extractTaskId(t);
            ManagedWindow cur = (topTid > 0) ? windowManager.getManagedWindow(topTid) : null;
            if (cur != null && (!cur.focused || cur.minimized)) {
                windowManager.setFocusedTask(topTid);
                notifyStateChanged();
            }
            break;
        }
    }

    public String getManagedWindowsJson() {
        syncManagedWindowsWithLiveTasks();
        return windowManager.toManagedWindowsJson();
    }

    // ---- Package-Private & Internal Helpers ----

    private ManagedWindow findFocusedNonMinimizedUserWindow() {
        List<ManagedWindow> snapshot = windowManager.getManagedWindowsSnapshot();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            ManagedWindow mw = snapshot.get(i);
            if (mw == null || mw.minimized || !mw.focused || mw.taskId <= 0) continue;
            if (mw.pkg.isEmpty() || BridgeBuildConfig.HELPER_PACKAGE.equals(mw.pkg)
                    || PackagePolicy.isProtectedSystemTaskPkg(mw.pkg)) continue;
            return mw;
        }
        return null;
    }

    private boolean shouldPromoteAdaptiveToFullscreen(ManagedWindow mw, String pkg) {
        return mw != null
                && DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(mw.mode)
                && !windowManager.isTilingActive()
                && DesktopWindowManager.MODE_FULLSCREEN.equals(
                        windowManager.resolveLaunchMode(pkg, DesktopWindowManager.INT_MODE_AUTO));
    }

    boolean bringMultiWindowTaskFront(int taskId, Rect bounds) {
        if (taskId <= 0 || config.vdId <= 0) return false;
        boolean ok = taskApi.startActivityFromRecents(
                taskId, makeLaunchOptions(config.vdId, BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW, bounds).toBundle());
        taskApi.reorderTask(taskId, config.vdId, true);
        return ok;
    }


    void notifyStateChanged() {
        helperBridge.pushWindowState(windowManager.toManagedWindowsJson(), windowManager.getStateSeq());
        Runnable cb = onRemoteStateDirty;
        if (cb != null) cb.run();
    }

    private void verifyLaunchedWindowAsync(String pkg, String mode, WindowRect bounds, boolean useMw, boolean forceSync) {
        if (config.vdId <= 0 || pkg == null || pkg.isEmpty() || windowManager.isTilingActive()) return;
        int tid = findTaskIdForPkg(pkg, false);
        if (tid <= 0) return;
        windowManager.putManagedWindow(tid, pkg, mode, bounds, true, -1);
        if (useMw) {
            Rect targetRect = bounds.toAndroidRect();
            int curWm = taskApi.getTaskWindowingMode(tid);
            Rect curBounds = taskApi.getTaskBounds(tid, config.vdId);
            boolean alreadyMatched = (curWm == BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW)
                    && curBounds != null && curBounds.equals(targetRect);
            if (!alreadyMatched || forceSync) {
                taskApi.ensureTaskResizeableAndMultiWindow(tid, config.vdId, targetRect, forceSync);
            }
        } else {
            int wm = taskApi.getTaskWindowingMode(tid);
            if (wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN) {
                taskApi.detachFreeformAndEnsureFullscreen(tid, config.vdId, config.virtW, config.virtH, true, mainHandler);
                taskApi.startActivityFromRecents(tid,
                        makeLaunchOptions(config.vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
            }
        }
        notifyStateChanged();
    }

    private void syncManagedWindowsWithLiveTasks() {
        int vdId = config.vdId;
        if (vdId <= 0 || isDisplayMigrating()) return;
        List<?> running = taskApi.getTasks();
        if (running == null) return;
        Set<Integer> liveTids = new HashSet<>();
        Set<Integer> occludedBehindLauncher = new HashSet<>();
        boolean seenVisibleLauncher = false;
        int topTid = -1, topWm = 0;
        String topPkg = null;
        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != vdId) continue;
            int tid = TaskApiAdapter.extractTaskId(t);
            if (tid <= 0) continue;
            liveTids.add(tid);
            boolean isVis = TaskApiAdapter.readBoolField(t, "isVisible", false);
            if (TaskQueryService.isLauncherTaskInfo(t)) {
                if (isVis) seenVisibleLauncher = true;
                continue;
            }
            String p = TaskApiAdapter.extractTaskPkg(t);
            if (PackagePolicy.shouldSkipTaskPkg(p) || PackagePolicy.isProtectedSystemTaskPkg(p)) continue;
            if (isVis) {
                if (topTid <= 0) {
                    topTid = tid; topPkg = p; topWm = TaskApiAdapter.extractTaskWindowingMode(t);
                }
            } else if (seenVisibleLauncher) {
                occludedBehindLauncher.add(tid);
            }
        }
        windowManager.pruneDeadTasks(liveTids, 1800L);
        if (!occludedBehindLauncher.isEmpty()) {
            long nowMs = System.currentTimeMillis();
            for (int tid : occludedBehindLauncher) {
                ManagedWindow mw = windowManager.getManagedWindow(tid);
                if (mw != null && !mw.minimized && (nowMs - mw.updatedAtMs) >= 800L) {
                    windowManager.setMinimizedTask(tid, true);
                }
            }
        }
        // Never mark a live freeform window as minimized just because another floating window covers it;
        // only tasks physically pushed behind a visible LauncherActivity above are marked minimized.
        if (topTid > 0) {
            ManagedWindow existingTop = windowManager.getManagedWindow(topTid);
            if (existingTop != null) {
                if (!existingTop.focused || existingTop.minimized) {
                    windowManager.setFocusedTask(topTid);
                }
            } else if (!windowManager.isTilingActive() && topPkg != null) {
                boolean isPendingFullscreen = topPkg.equals(pendingFullscreenPkg)
                        && SystemClock.uptimeMillis() <= pendingFullscreenUntilMs;
                if (!isPendingFullscreen && windowManager.shouldAutoApplyAdaptiveSingle(topTid, topPkg)) {
                    String adoptedMode = windowManager.resolveLaunchMode(topPkg, DesktopWindowManager.INT_MODE_AUTO);
                    applySingleWindowMode(topTid, topPkg, adoptedMode, null, false, true, false);
                } else {
                    Rect curB = taskApi.getTaskBounds(topTid, vdId);
                    WindowRect wr = (curB != null && !curB.isEmpty()) ? WindowRect.fromAndroidRect(curB) : new WindowRect(0, 0, config.virtW, config.virtH);
                    String mode;
                    if (topWm == BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW && wr.width() < config.virtW - FULLSCREEN_WIDTH_TOLERANCE_PX) {
                        String prefMode = windowManager.resolveLaunchMode(topPkg, DesktopWindowManager.INT_MODE_AUTO);
                        mode = DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(prefMode)
                                ? DesktopWindowManager.MODE_ADAPTIVE_SINGLE : DesktopWindowManager.MODE_FREEFORM;
                    } else {
                        mode = DesktopWindowManager.MODE_FULLSCREEN;
                    }
                    String pres = DesktopWindowManager.classifyPresentation(
                            mode, wr, config.virtW, config.virtH, config.virtDpi, windowManager.isTaskbarAutoHide());
                    windowManager.putManagedWindow(topTid, topPkg, mode, pres, wr, true, -1);
                }
            }
        } else if (!windowManager.isTilingActive()) {
            // Only minimize all when the Launcher is actually topmost on the virtual display
            // and zero user tasks remain visible.
            windowManager.minimizeAllWindows();
        }
    }

    private static void applyLaunchWindowingMode(ActivityOptions opts, int windowingMode) {
        if (!sSetLaunchWindowingModeChecked) {
            try { sSetLaunchWindowingModeMethod = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class); }
            catch (NoSuchMethodException ignored) { sSetLaunchWindowingModeMethod = null; }
            finally { sSetLaunchWindowingModeChecked = true; }
        }
        Method m = sSetLaunchWindowingModeMethod;
        if (m == null) return;
        try { m.invoke(opts, windowingMode); }
        catch (ReflectiveOperationException | RuntimeException e) { System.err.println("OPALDESK_LAUNCH_WM_WARN: " + e.getMessage()); }
    }
}
