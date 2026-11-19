package com.extbridge.daemon.window;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DaemonConfig.PackagePolicy;
import com.extbridge.daemon.display.CompatPolicyController;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.stream.ExtCastProtocol;
import com.extbridge.daemon.util.ShellCommand;
import com.extbridge.daemon.window.DesktopWindowManager.ManagedWindow;
import com.extbridge.daemon.window.DesktopWindowManager.TwoSlotSplitSnapshot;
import com.extbridge.daemon.window.TilingLayoutEngine.WindowRect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    private volatile Runnable onRemoteStateDirty;
    private volatile String pendingFullscreenPkg = "";
    private volatile long pendingFullscreenUntilMs = 0L;

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
    }

    public void setOnRemoteStateDirty(Runnable cb) { this.onRemoteStateDirty = cb; }
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
    public ExtCastProtocol.AppListMsg collectAppList(Set<Integer> extDisplayIds) { return queries.collectAppList(extDisplayIds); }
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

    void applySingleWindowMode(
            int taskId, String pkg, String requestedMode, WindowRect explicitBounds,
            boolean bringToFront, boolean focused, boolean rememberPref) {
        int vdId = config.vdId;
        if (taskId <= 0 || vdId <= 0) return;
        int virtW = config.virtW, virtH = config.virtH, virtDpi = config.virtDpi;
        String safePkg = (pkg != null) ? pkg : "";

        String mode = requestedMode;
        WindowRect rect;
        if (DesktopWindowManager.MODE_FULLSCREEN.equals(mode)) {
            rect = new WindowRect(0, 0, virtW, virtH);
        } else if (explicitBounds != null && !explicitBounds.isEmpty()) {
            rect = DesktopWindowManager.clampBoundsToWorkArea(explicitBounds, virtW, Math.max(360, virtH));
        } else if (DesktopWindowManager.MODE_FREEFORM.equals(mode)) {
            rect = windowManager.computeNextFreeformBounds(virtW, virtH, virtDpi, 0, safePkg);
        } else {
            mode = DesktopWindowManager.MODE_ADAPTIVE_SINGLE;
            rect = windowManager.computeAdaptiveSingleBounds(virtW, virtH, virtDpi, 0, safePkg, 0, 0);
        }
        if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(mode) && rect.width() >= virtW - FULLSCREEN_WIDTH_TOLERANCE_PX) {
            mode = DesktopWindowManager.MODE_FULLSCREEN;
            rect = new WindowRect(0, 0, virtW, virtH);
        }

        windowManager.putManagedWindow(taskId, safePkg, mode, rect, focused, -1);
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
            taskApi.ensureTaskResizeableAndMultiWindow(taskId, vdId, ar, true);
            if (bringToFront) {
                bringMultiWindowTaskFront(taskId, ar);
                taskApi.ensureTaskResizeableAndMultiWindow(taskId, vdId, ar, true);
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
        int existingTid = findTaskIdForPkg(pkg, false);
        if (existingTid > 0 && isTaskAllowedForExtControl(existingTid, pkg, false)) {
            applySingleWindowMode(existingTid, pkg, targetMode, explicit, true, true, false);
            notifyStateChanged();
            return true;
        }
        WindowRect rect = DesktopWindowManager.MODE_FULLSCREEN.equals(targetMode) ? new WindowRect(0, 0, config.virtW, config.virtH)
                : (explicit != null ? explicit : (DesktopWindowManager.MODE_FREEFORM.equals(targetMode)
                        ? windowManager.computeNextFreeformBounds(config.virtW, config.virtH, config.virtDpi, 0, pkg)
                        : windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, 0, pkg, 0, 0)));
        if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(targetMode) && rect.width() >= config.virtW - FULLSCREEN_WIDTH_TOLERANCE_PX) {
            targetMode = DesktopWindowManager.MODE_FULLSCREEN;
            rect = new WindowRect(0, 0, config.virtW, config.virtH);
        }
        boolean useMw = !DesktopWindowManager.MODE_FULLSCREEN.equals(targetMode);
        if (!useMw) {
            pendingFullscreenPkg = pkg;
            pendingFullscreenUntilMs = SystemClock.uptimeMillis() + PENDING_FULLSCREEN_GRACE_MS;
            int preTid = findTaskIdForPkg(pkg, true);
            if (preTid > 0) {
                int preWm = taskApi.getTaskWindowingMode(preTid);
                if (preWm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && preWm != BridgeProtocol.WINDOWING_MODE_UNDEFINED) {
                    taskApi.detachFreeformAndEnsureFullscreen(preTid, vdId, config.virtW, config.virtH, true, mainHandler);
                }
            }
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
        boolean ok = services.startActivityAsShell(intent, makeLaunchOptions(
                vdId, useMw ? BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW : BridgeProtocol.WINDOWING_MODE_FULLSCREEN,
                useMw ? rect.toAndroidRect() : null).toBundle());
        if (ok && mainHandler != null) {
            final String fMode = targetMode;
            final WindowRect fRect = rect;
            for (long delayMs : new long[]{220L, 650L}) {
                mainHandler.postDelayed(() -> verifyLaunchedWindowAsync(pkg, fMode, fRect, useMw), delayMs);
            }
        }
        notifyStateChanged();
        return ok;
    }

    public boolean updateWindowBounds(
            int taskId, String pkg, int modeInt, int left, int top, int right, int bottom, boolean rememberPref) {
        int vdId = config.vdId;
        if (vdId <= 0) return false;
        String safePkg = PackagePolicy.isValidPackageName(pkg) ? pkg : null;
        int tid = (taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false))
                ? taskId : (safePkg != null ? findTaskIdForPkg(safePkg, true) : -1);
        if (tid <= 0) {
            String topPkg = findTopUserAppOnDisplay(vdId)[0];
            if (!topPkg.isEmpty()) { safePkg = topPkg; tid = findTaskIdForPkg(safePkg, true); }
        }
        if (tid <= 0 || !isTaskAllowedForExtControl(tid, safePkg, false)) return false;
        if (safePkg == null) {
            ManagedWindow mw = windowManager.getManagedWindow(tid);
            if (mw != null && !mw.pkg.isEmpty()) safePkg = mw.pkg;
        }
        tiling.clearTilingIfActive();
        String mode = (modeInt == DesktopWindowManager.INT_MODE_FULLSCREEN) ? DesktopWindowManager.MODE_FULLSCREEN
                : (modeInt == DesktopWindowManager.INT_MODE_FREEFORM ? DesktopWindowManager.MODE_FREEFORM : DesktopWindowManager.MODE_ADAPTIVE_SINGLE);
        WindowRect explicit = (right - left >= 200 && bottom - top >= 200) ? new WindowRect(left, top, right, bottom) : null;
        boolean bringFront = DesktopWindowManager.MODE_FULLSCREEN.equals(mode);
        applySingleWindowMode(tid, safePkg, mode, explicit, bringFront, true, rememberPref);
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
        WindowRect r = windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, 0, target, pct, 0);
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
        Intent li = (safePkg != null && services.context != null) ? services.context.getPackageManager().getLaunchIntentForPackage(safePkg) : null;
        if (li == null) return false;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return services.startActivityAsShell(li, makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null).toBundle());
    }

    public boolean minimizeToExtHome(int taskId, String pkg) {
        int vdId = config.vdId;
        if (vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return false;
        if (windowManager.isTilingActive()) exitTilingAndRestore(false);
        windowManager.minimizeAllSingleWindows();
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
                if (tid > 0) windowManager.setMinimizedTask(tid, true);
                boolean hasOtherFf = false;
                for (ManagedWindow m : windowManager.getManagedWindowsSnapshot()) {
                    if (!m.minimized && m.taskId != tid && DesktopWindowManager.MODE_FREEFORM.equals(m.mode)) { hasOtherFf = true; break; }
                }
                if (!hasOtherFf) minimizeToExtHome(tid, safePkg);
                notifyStateChanged();
                return true;
            case BridgeProtocol.WIN_ACTION_CLOSE:
                return tid > 0 && removeTaskById(tid);
            case BridgeProtocol.WIN_ACTION_RELOAD_DPI:
                if (safePkg == null || safePkg.isEmpty()) safePkg = findTopUserAppOnDisplay(vdId)[0];
                if (safePkg == null || safePkg.isEmpty()) return false;
                reloadAppDpiOnDesktop(safePkg);
                return true;
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

    public void postAttachLayoutSync() {
        if (windowManager.isTilingActive()) relayoutManagedWindowsOnResize();
        else tiling.ensureLauncherTaskFullscreenInBg();
    }

    public void scheduleRelayoutAfterResize() {
        if (mainHandler == null) { relayoutManagedWindowsOnResize(); return; }
        mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 300L);
        if (getTwoSlotSplitSnapshot().active) {
            mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 900L);
            mainHandler.postDelayed(this::relayoutManagedWindowsOnResize, 1850L);
        }
    }

    public void relayoutManagedWindowsOnResize() {
        if (config.vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return;
        if (windowManager.isTilingActive()) { tiling.relayoutTilingOnResize(); return; }
        int workBottom = Math.max(360, config.virtH);
        for (ManagedWindow mw : windowManager.getManagedWindowsSnapshot()) {
            if (mw.minimized || mw.taskId <= 0) continue;
            if (DesktopWindowManager.MODE_ADAPTIVE_SINGLE.equals(mw.mode)) {
                applySingleWindowMode(mw.taskId, mw.pkg, DesktopWindowManager.MODE_ADAPTIVE_SINGLE, null, false, mw.focused, false);
            } else if (DesktopWindowManager.MODE_FREEFORM.equals(mw.mode) && mw.bounds != null) {
                applySingleWindowMode(mw.taskId, mw.pkg, DesktopWindowManager.MODE_FREEFORM,
                        DesktopWindowManager.clampBoundsToWorkArea(mw.bounds, config.virtW, workBottom), false, mw.focused, false);
            }
        }
    }

    public void enforceRunningExtTasksFullscreen(boolean enforceCompat) {
        int vdId = config.vdId;
        if (vdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return;
        List<?> running = taskApi.getTasks();
        if (running == null) return;
        Set<Integer> liveTids = new HashSet<>();
        for (Object t : running) {
            if (t != null && TaskApiAdapter.readIntField(t, "displayId", -1) == vdId) {
                int tid = TaskApiAdapter.extractTaskId(t);
                if (tid > 0) liveTids.add(tid);
            }
        }
        windowManager.pruneDeadTasks(liveTids);

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
                if (wm == BridgeProtocol.WINDOWING_MODE_FREEFORM) {
                    ManagedWindow mw = windowManager.getManagedWindow(tid);
                    Rect target = (mw != null && mw.bounds != null && !mw.bounds.isEmpty()) ? mw.bounds.toAndroidRect()
                            : windowManager.computeAdaptiveSingleBounds(config.virtW, config.virtH, config.virtDpi, 0, pkg, 0, 0).toAndroidRect();
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
                applySingleWindowMode(tid, pkg, DesktopWindowManager.MODE_ADAPTIVE_SINGLE, null, false, true, false);
                continue;
            }
            if (tid > 0 && wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && wm != 0) {
                taskApi.detachFreeformAndEnsureFullscreen(tid, vdId, config.virtW, config.virtH, true, mainHandler);
            }
            if (enforceCompat && PackagePolicy.shouldCompatOverridePkg(pkg)) {
                compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
            }
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
        return taskId > 0 && config.vdId > 0
                && taskApi.startActivityFromRecents(taskId, makeLaunchOptions(config.vdId, BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW, bounds).toBundle());
    }

    void notifyStateChanged() {
        helperBridge.notifyHelperSync();
        Runnable cb = onRemoteStateDirty;
        if (cb != null) cb.run();
    }

    private void verifyLaunchedWindowAsync(String pkg, String mode, WindowRect bounds, boolean useMw) {
        if (config.vdId <= 0 || pkg == null || pkg.isEmpty() || windowManager.isTilingActive()) return;
        int tid = findTaskIdForPkg(pkg, false);
        if (tid <= 0) return;
        windowManager.putManagedWindow(tid, pkg, mode, bounds, true, -1);
        if (useMw) {
            taskApi.ensureTaskResizeableAndMultiWindow(tid, config.vdId, bounds.toAndroidRect(), true);
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
        if (vdId <= 0) return;
        List<?> running = taskApi.getTasks();
        if (running == null) return;
        Set<Integer> liveTids = new HashSet<>();
        int topTid = -1, topWm = 0;
        String topPkg = null;
        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != vdId) continue;
            int tid = TaskApiAdapter.extractTaskId(t);
            if (tid <= 0) continue;
            liveTids.add(tid);
            if (topTid <= 0 && TaskApiAdapter.readBoolField(t, "isVisible", false)) {
                String p = TaskApiAdapter.extractTaskPkg(t);
                if (!PackagePolicy.shouldSkipTaskPkg(p) && !PackagePolicy.isProtectedSystemTaskPkg(p)) {
                    topTid = tid; topPkg = p; topWm = TaskApiAdapter.extractTaskWindowingMode(t);
                }
            }
        }
        windowManager.pruneDeadTasks(liveTids);
        if (topTid > 0) {
            if (windowManager.getManagedWindow(topTid) != null) {
                windowManager.setFocusedTask(topTid);
            } else if (!windowManager.isTilingActive() && topPkg != null) {
                Rect curB = taskApi.getTaskBounds(topTid, vdId);
                WindowRect wr = (curB != null && !curB.isEmpty()) ? WindowRect.fromAndroidRect(curB) : new WindowRect(0, 0, config.virtW, config.virtH);
                String mode = (topWm == BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW && wr.width() < config.virtW - FULLSCREEN_WIDTH_TOLERANCE_PX)
                        ? DesktopWindowManager.MODE_ADAPTIVE_SINGLE : DesktopWindowManager.MODE_FULLSCREEN;
                windowManager.putManagedWindow(topTid, topPkg, mode, wr, true, -1);
            }
        } else if (!windowManager.isTilingActive()) {
            windowManager.minimizeAllSingleWindows();
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
        catch (ReflectiveOperationException | RuntimeException e) { System.err.println("EXTBRIDGE_LAUNCH_WM_WARN: " + e.getMessage()); }
    }
}
