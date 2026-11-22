package io.github.lengsukq.opaldesk.daemon.window;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.PackagePolicy;
import io.github.lengsukq.opaldesk.daemon.display.CompatPolicyController;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager.TwoSlotSplitSnapshot;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine.WindowRect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Package-private multi-slot tiling & unified legacy v3 2-slot split (Binder code 35) controller,
 * backed directly by {@link DesktopWindowManager} tiling state and {@link TilingLayoutEngine}.
 */
final class TilingSessionController {
    private static final long SPLIT_AUTO_DETACH_GRACE_MS = 2600L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final TaskApiAdapter taskApi;
    private final DesktopWindowManager windowManager;
    private final CompatPolicyController compatPolicy;
    private final HelperClientBridge helperBridge;
    private final TaskQueryService queries;
    private final TaskWindowController owner;
    private final Handler mainHandler;

    private final Object tilingOpLock = new Object();
    private volatile int lastSplitRatioPct = 50;
    private volatile long lastSplitActionMs = 0L;
    private volatile boolean twoSlotAutoDetachEnabled = false;

    TilingSessionController(
            AndroidServices services, DaemonConfig config, TaskApiAdapter taskApi,
            DesktopWindowManager windowManager, CompatPolicyController compatPolicy,
            HelperClientBridge helperBridge, TaskQueryService queries,
            TaskWindowController owner, Handler mainHandler) {
        this.services = services;
        this.config = config;
        this.taskApi = taskApi;
        this.windowManager = windowManager;
        this.compatPolicy = compatPolicy;
        this.helperBridge = helperBridge;
        this.queries = queries;
        this.owner = owner;
        this.mainHandler = mainHandler;
    }

    TwoSlotSplitSnapshot getTwoSlotSplitSnapshot() {
        return windowManager.getTwoSlotSplitSnapshot(lastSplitRatioPct);
    }

    void clearTilingIfActive() {
        if (windowManager.isTilingActive()) {
            windowManager.clearTilingState();
            twoSlotAutoDetachEnabled = false;
        }
    }

    boolean setTilingLayout(String topologyRaw, List<String> reqPkgs, int[] reqRatios) {
        synchronized (tilingOpLock) {
            if (config.vdId <= 0) return false;
            String norm = TilingLayoutEngine.normalizeTopology(topologyRaw);
            if (TilingLayoutEngine.TOPOLOGY_NONE.equals(norm)) { exitTilingAndRestore(false); return true; }
            int slotCount = TilingLayoutEngine.slotCountForTopology(norm);
            if (slotCount <= 0) return false;
            int[] ratios;
            if (reqRatios != null && reqRatios.length > 0) {
                ratios = reqRatios;
            } else if (windowManager.isTilingActive() && norm.equals(windowManager.getTilingTopology())) {
                ratios = windowManager.getTilingRatios();
            } else {
                ratios = TilingLayoutEngine.defaultRatiosForTopology(norm);
            }
            if (slotCount == 2 && ratios.length > 0) lastSplitRatioPct = clampSplitPct(ratios[0]);
            boolean preserveEmptySlots = hasExplicitEmptySlot(reqPkgs);
            ArrayList<String> chosen = queries.pickTilingApps(reqPkgs, slotCount, preserveEmptySlots);
            if (chosen.isEmpty()) return false;
            twoSlotAutoDetachEnabled = false;
            lastSplitActionMs = SystemClock.uptimeMillis();
            applyTilingLayoutLocked(norm, chosen, ratios, null, -1, true);
            return true;
        }
    }

    private static boolean hasExplicitEmptySlot(List<String> reqPkgs) {
        if (reqPkgs == null || reqPkgs.isEmpty()) return false;
        for (String p : reqPkgs) {
            if (p == null || p.trim().isEmpty()) return true;
        }
        return false;
    }

    boolean handleSplitAction(int action, int arg, String pkg, String cls) {
        synchronized (tilingOpLock) {
            if (config.vdId <= 0) return false;
            lastSplitActionMs = SystemClock.uptimeMillis();
            TwoSlotSplitSnapshot cur = getTwoSlotSplitSnapshot();
            String topo = (config.virtH > config.virtW)
                    ? TilingLayoutEngine.TOPOLOGY_SPLIT_2_V : TilingLayoutEngine.TOPOLOGY_SPLIT_2_H;
            switch (action) {
                case BridgeProtocol.SPLIT_ASSIGN_LEFT_TOP:
                case BridgeProtocol.SPLIT_ASSIGN_RIGHT_BOTTOM: {
                    int slot = (action == BridgeProtocol.SPLIT_ASSIGN_LEFT_TOP) ? 0 : 1;
                    if (!PackagePolicy.shouldCompatOverridePkg(pkg)) return false;
                    String safeCls = (cls != null && !cls.isEmpty())
                            ? (queries.isAllowedLaunchTarget(pkg, cls) ? cls : null) : null;
                    if (cls != null && !cls.isEmpty() && safeCls == null) return false;
                    if (safeCls == null && (services.context == null
                            || services.context.getPackageManager().getLaunchIntentForPackage(pkg) == null)) return false;
                    String p0 = cur.active ? cur.slot0Pkg : "", p1 = cur.active ? cur.slot1Pkg : "";
                    if (slot == 0) { p0 = pkg; if (pkg.equals(p1)) p1 = ""; }
                    else { p1 = pkg; if (pkg.equals(p0)) p0 = ""; }
                    twoSlotAutoDetachEnabled = true;
                    applyTilingLayoutLocked(topo, Arrays.asList(p0, p1),
                            new int[]{lastSplitRatioPct, 100 - lastSplitRatioPct}, safeCls, slot, true);
                    return true;
                }
                case BridgeProtocol.SPLIT_TOGGLE_QUICK: {
                    if (cur.active && arg == 0) { exitTilingAndRestore(true); return true; }
                    ArrayList<String> cands = queries.pickTilingApps(null, 2);
                    if (cands.isEmpty()) return false;
                    while (cands.size() < 2) cands.add("");
                    twoSlotAutoDetachEnabled = true;
                    applyTilingLayoutLocked(topo, cands, new int[]{lastSplitRatioPct, 100 - lastSplitRatioPct}, null, -1, true);
                    return true;
                }
                case BridgeProtocol.SPLIT_SET_RATIO:
                    lastSplitRatioPct = clampSplitPct(arg);
                    if (cur.active) {
                        applyTilingLayoutLocked(cur.topology, Arrays.asList(cur.slot0Pkg, cur.slot1Pkg),
                                new int[]{lastSplitRatioPct, 100 - lastSplitRatioPct}, null, -1, false);
                    } else {
                        helperBridge.notifyHelperSync();
                    }
                    return true;
                case BridgeProtocol.SPLIT_SWAP:
                    if (cur.active) {
                        applyTilingLayoutLocked(cur.topology, Arrays.asList(cur.slot1Pkg, cur.slot0Pkg),
                                new int[]{cur.ratioPct, 100 - cur.ratioPct}, null, -1, true);
                    } else {
                        helperBridge.notifyHelperSync();
                    }
                    return true;
                case BridgeProtocol.SPLIT_EXIT:
                    exitTilingAndRestore(true);
                    return true;
                default:
                    return false;
            }
        }
    }

    void exitTilingAndRestore(boolean focusSlot0) {
        synchronized (tilingOpLock) {
            int[] prevTids = windowManager.getTilingSlotTaskIds();
            String[] prevPkgs = windowManager.getTilingSlotPkgs();
            windowManager.clearTilingState();
            twoSlotAutoDetachEnabled = false;
            ensureLauncherTaskFullscreenInBg();
            for (int i = prevPkgs.length - 1; i >= 0; i--) {
                restoreLeavingTilingTask(prevPkgs[i], i < prevTids.length ? prevTids[i] : -1);
            }
            if (prevPkgs.length > 0 && prevPkgs[0] != null && !prevPkgs[0].isEmpty()) {
                int tid0 = (prevTids.length > 0 && prevTids[0] > 0) ? prevTids[0] : queries.findTaskIdForPkg(prevPkgs[0], true);
                if (tid0 > 0) {
                    if (focusSlot0) owner.switchToTaskOnVd(tid0, prevPkgs[0]);
                    else if (windowManager.isAdaptiveSingleEnabled()) {
                        owner.applySingleWindowMode(tid0, prevPkgs[0], DesktopWindowManager.MODE_ADAPTIVE_SINGLE, null, false, true, false);
                    }
                }
            }
            owner.notifyStateChanged();
        }
    }

    void onTaskRemovedAfterPrune() {
        TwoSlotSplitSnapshot snap = getTwoSlotSplitSnapshot();
        if (snap.active && snap.emptySlotIndex() >= 0) syncLauncherForEmptyTwoSlot(snap.emptySlotIndex(), true);
        else if (!snap.active) { twoSlotAutoDetachEnabled = false; ensureLauncherTaskFullscreenInBg(); }
    }

    private int dockInsetPx() {
        return TilingLayoutEngine.computeDefaultDockInsetPx(config.virtH, config.virtDpi);
    }

    void relayoutTilingOnResize() {
        int vdId = config.vdId;
        if (vdId <= 0) return;
        synchronized (tilingOpLock) {
            WindowRect[] slots = TilingLayoutEngine.computeSlots(windowManager.getTilingTopology(),
                    config.virtW, config.virtH, dockInsetPx(), TilingLayoutEngine.DEFAULT_GAP_PX, windowManager.getTilingRatios());
            String[] pkgs = windowManager.getTilingSlotPkgs();
            int[] tids = windowManager.getTilingSlotTaskIds();
            int empty = getTwoSlotSplitSnapshot().emptySlotIndex();
            if (empty >= 0) syncLauncherForEmptyTwoSlot(empty, false);
            for (int i = 0; i < slots.length && i < pkgs.length; i++) {
                if (pkgs[i] == null || pkgs[i].isEmpty()) continue;
                int tid = (tids[i] > 0) ? tids[i] : queries.findTaskIdForPkg(pkgs[i], true);
                if (tid > 0) {
                    windowManager.updateTilingSlotTaskId(i, tid, pkgs[i], slots[i]);
                    taskApi.ensureTaskResizeableAndMultiWindow(tid, vdId, slots[i].toAndroidRect(), true);
                }
            }
        }
    }

    boolean maybeDetachHiddenTwoSlotTasks(List<?> running, int vdId, TwoSlotSplitSnapshot sp) {
        if (!twoSlotAutoDetachEnabled || !sp.active
                || (SystemClock.uptimeMillis() - lastSplitActionMs) <= SPLIT_AUTO_DETACH_GRACE_MS) {
            return false;
        }
        boolean s0 = sp.slot0Pkg.isEmpty(), s1 = sp.slot1Pkg.isEmpty();
        for (Object t : running) {
            if (t == null || TaskApiAdapter.readIntField(t, "displayId", -1) != vdId
                    || !TaskApiAdapter.readBoolField(t, "isVisible", false)) continue;
            String p = TaskApiAdapter.extractTaskPkg(t);
            int tid = TaskApiAdapter.extractTaskId(t);
            if (!sp.slot0Pkg.isEmpty() && (sp.slot0Pkg.equals(p) || (sp.slot0TaskId > 0 && tid == sp.slot0TaskId))) s0 = true;
            if (!sp.slot1Pkg.isEmpty() && (sp.slot1Pkg.equals(p) || (sp.slot1TaskId > 0 && tid == sp.slot1TaskId))) s1 = true;
        }
        if (s0 && s1) return false;
        if (!s0 && !s1) { exitTilingAndRestore(false); return true; }
        synchronized (tilingOpLock) {
            restoreLeavingTilingTask(!s0 ? sp.slot0Pkg : sp.slot1Pkg, !s0 ? sp.slot0TaskId : sp.slot1TaskId);
            String r0 = s0 ? sp.slot0Pkg : "", r1 = s1 ? sp.slot1Pkg : "";
            if (r0.isEmpty() && r1.isEmpty()) {
                windowManager.clearTilingState();
                twoSlotAutoDetachEnabled = false;
                ensureLauncherTaskFullscreenInBg();
            } else {
                applyTilingLayoutLocked(sp.topology, Arrays.asList(r0, r1),
                        new int[]{sp.ratioPct, 100 - sp.ratioPct}, null, -1, true);
            }
        }
        return true;
    }

    void syncLauncherForEmptyTwoSlot(int emptySlot, boolean bringFront) {
        TwoSlotSplitSnapshot snap = getTwoSlotSplitSnapshot();
        int vdId = config.vdId;
        if (!snap.active || emptySlot < 0 || vdId <= 0) return;
        WindowRect[] slots = TilingLayoutEngine.computeSlots(snap.topology, config.virtW, config.virtH,
                dockInsetPx(), TilingLayoutEngine.DEFAULT_GAP_PX, new int[]{snap.ratioPct, 100 - snap.ratioPct});
        if (emptySlot >= slots.length) return;
        Rect bounds = slots[emptySlot].toAndroidRect();
        int lTid = queries.findExtLauncherTaskId();
        if (lTid > 0) {
            taskApi.ensureTaskResizeableAndMultiWindow(lTid, vdId, bounds, bringFront);
            if (bringFront) {
                owner.bringMultiWindowTaskFront(lTid, bounds);
                taskApi.ensureTaskResizeableAndMultiWindow(lTid, vdId, bounds, true);
            }
            return;
        }
        if (!bringFront) return;
        Intent intent = new Intent().setComponent(new ComponentName(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_LAUNCHER_CLASS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED).putExtra("extId", vdId);
        services.startActivityAsShell(intent,
                TaskWindowController.makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW, bounds).toBundle());
    }

    void ensureLauncherTaskFullscreenInBg() {
        int lTid = queries.findExtLauncherTaskId();
        if (lTid <= 0) return;
        Rect b = taskApi.getTaskBounds(lTid, config.vdId);
        if (taskApi.getTaskWindowingMode(lTid) == BridgeProtocol.WINDOWING_MODE_FULLSCREEN
                && (b == null || b.isEmpty() || (b.left == 0 && b.top == 0 && b.width() == config.virtW && b.height() == config.virtH))) return;
        taskApi.detachFreeformAndEnsureFullscreen(lTid, config.vdId, config.virtW, config.virtH, true, mainHandler);
    }

    static int matchOrAutoFillTwoSlot(TwoSlotSplitSnapshot sp, String pkg) {
        if (!sp.active || pkg == null || pkg.isEmpty()) return -1;
        if (pkg.equals(sp.slot0Pkg)) return 0;
        if (pkg.equals(sp.slot1Pkg)) return 1;
        if (sp.slot0Pkg.isEmpty()) return 0;
        return sp.slot1Pkg.isEmpty() ? 1 : -1;
    }

    private void applyTilingLayoutLocked(
            String topology, List<String> slotPkgs, int[] ratios, String explicitCls, int explicitSlot, boolean bringFront) {
        int slotCount = TilingLayoutEngine.slotCountForTopology(topology);
        WindowRect[] slots = TilingLayoutEngine.computeSlots(topology, config.virtW, config.virtH, dockInsetPx(), TilingLayoutEngine.DEFAULT_GAP_PX, ratios);
        int[] tids = new int[slotCount];
        Arrays.fill(tids, -1);
        for (int i = 0; i < slotCount && i < slotPkgs.size(); i++) {
            if (slotPkgs.get(i) != null && !slotPkgs.get(i).isEmpty()) tids[i] = queries.findTaskIdForPkg(slotPkgs.get(i), true);
        }
        windowManager.setTilingState(topology, slotPkgs, tids, ratios, slots);
        int empty = getTwoSlotSplitSnapshot().emptySlotIndex();
        if (empty >= 0) syncLauncherForEmptyTwoSlot(empty, bringFront); else ensureLauncherTaskFullscreenInBg();
        for (int i = 0; i < slotCount && i < slotPkgs.size(); i++) {
            String p = slotPkgs.get(i);
            if (p != null && !p.isEmpty()) applyTilingSlot(i, p, i == explicitSlot ? explicitCls : null, tids[i], slots[i], bringFront);
        }
        owner.notifyStateChanged();
    }

    private void applyTilingSlot(int slotIdx, String pkg, String cls, int knownTid, WindowRect bounds, boolean bringFront) {
        int vdId = config.vdId;
        if (vdId <= 0 || bounds == null) return;
        compatPolicy.applyAppModeCompat(pkg, BridgeProtocol.APP_MODE_LAND_FULL, false);
        Rect r = bounds.toAndroidRect();
        int tid = (knownTid > 0) ? knownTid : queries.findTaskIdForPkg(pkg, true);
        if (tid > 0) {
            windowManager.updateTilingSlotTaskId(slotIdx, tid, pkg, bounds);
            taskApi.ensureTaskResizeableAndMultiWindow(tid, vdId, r, bringFront);
            if (!bringFront) return;
            if (owner.bringMultiWindowTaskFront(tid, r)) { taskApi.ensureTaskResizeableAndMultiWindow(tid, vdId, r, true); return; }
        }
        if (!bringFront) return;
        Intent intent = queries.resolveSafeLaunchIntent(pkg, cls);
        if (intent == null) return;
        services.startActivityAsShell(intent,
                TaskWindowController.makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_MULTI_WINDOW, r).toBundle());
        if (mainHandler != null) {
            for (long d : new long[]{240L, 700L}) {
                mainHandler.postDelayed(() -> {
                    if (!windowManager.isTilingActive() || config.vdId <= 0) return;
                    String[] curSlots = windowManager.getTilingSlotPkgs();
                    if (slotIdx >= curSlots.length || !pkg.equals(curSlots[slotIdx])) return;
                    int t = queries.findTaskIdForPkg(pkg, true);
                    if (t > 0) {
                        windowManager.updateTilingSlotTaskId(slotIdx, t, pkg, bounds);
                        taskApi.ensureTaskResizeableAndMultiWindow(t, config.vdId, r, true);
                    }
                }, d);
            }
        }
    }

    private void restoreLeavingTilingTask(String pkg, int knownTid) {
        if ((pkg == null || pkg.isEmpty()) && knownTid <= 0) return;
        int tid = (knownTid > 0) ? knownTid : queries.findTaskIdForPkg(pkg, true);
        if (tid > 0) taskApi.detachFreeformAndEnsureFullscreen(tid, config.vdId, config.virtW, config.virtH, true, mainHandler);
        if (pkg != null && !pkg.isEmpty() && config.getAppMode(pkg) != BridgeProtocol.APP_MODE_LAND_FULL) {
            compatPolicy.applyAppModeCompat(pkg, config.getAppMode(pkg), false);
        }
    }

    private static int clampSplitPct(int pct) { return Math.max(25, Math.min(75, pct)); }
}
