package io.github.lengsukq.opaldesk.daemon.display;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.input.InputInjector;
import io.github.lengsukq.opaldesk.daemon.input.LocalInputController;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.SurfaceLayerHelper;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Physical native Display 0 mirror compositing, SideBar VirtualDisplay via
 * {@link VirtualDisplayFactory} and {@link SurfaceLayerHelper}, single {@link MirrorGeom} SSOT,
 * {@link DisplayManager.DisplayListener}, and stray sidebar task rescue.
 * Delegates cursor overlay & edge-capture input routing to {@link MirrorCursorController}.
 */
public final class NativeMirrorController {
    static final int EDGE_MARGIN_MIN_PX = 48;
    private static final int[] SIDE_VD_CANDIDATE_FLAGS = {0x1040B, 0x40B};

    /** Single source of truth for Display 0 logical size and its mapped rectangle on external screen. */
    public static final class MirrorGeom {
        public static final MirrorGeom EMPTY = new MirrorGeom(0, 0, 0, 0, 0, 0);
        public final int d0W, d0H, mirX, mirY, mirW, mirH;

        public MirrorGeom(int d0W, int d0H, int mirX, int mirY, int mirW, int mirH) {
            this.d0W = d0W; this.d0H = d0H;
            this.mirX = mirX; this.mirY = mirY;
            this.mirW = mirW; this.mirH = mirH;
        }
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompatPolicyController compatPolicy;
    private final SystemControlService systemControl;
    private final HelperClientBridge helperBridge;
    private final LocalInputController localInput;
    private final TaskApiAdapter taskApi;
    private final Handler mainHandler;
    private final ExecutorService bgIoExec;
    private final ScheduledExecutorService bgSchedExec;
    private final MirrorCursorController cursorController;

    private volatile Object rootSc, bgSc, mir0WrapSc, mir0BgSc, mir0Sc, sideMirSc;
    private volatile SurfaceLayerHelper.BlastSurface sideBlast;
    private volatile VirtualDisplay sideVd;
    private volatile int sideVdId = -1;
    private volatile int targetDisplayId = -1;
    private volatile boolean remoteTarget = false;
    private volatile MirrorGeom mirGeom = MirrorGeom.EMPTY;
    private volatile int lastAppliedMirSideMode = -1;
    private volatile boolean sideVdAttempted = false;
    private volatile int lastSideVdAttemptMode = -1;
    private volatile DisplayManager.DisplayListener displayListener;

    public NativeMirrorController(
            AndroidServices services, DaemonConfig config, CompatPolicyController compatPolicy,
            SystemControlService systemControl, HelperClientBridge helperBridge,
            InputInjector injector, LocalInputController localInput, TaskApiAdapter taskApi,
            Handler mainHandler, ExecutorService bgIoExec, ScheduledExecutorService bgSchedExec) {
        this.services = services;
        this.config = config;
        this.compatPolicy = compatPolicy;
        this.systemControl = systemControl;
        this.helperBridge = helperBridge;
        this.localInput = localInput;
        this.taskApi = taskApi;
        this.mainHandler = mainHandler;
        this.bgIoExec = bgIoExec;
        this.bgSchedExec = bgSchedExec;
        this.cursorController = new MirrorCursorController(
                services, config, helperBridge, injector, localInput, this, mainHandler);
    }

    public MirrorGeom getMirrorGeom() { return mirGeom; }
    public int getSideVdId() { return sideVdId; }
    public void resetSideVdAttempt() { sideVdAttempted = false; }

    public boolean hasActiveResources() {
        return rootSc != null || mir0Sc != null || sideVd != null || "NATIVE_MIRROR_STOCK".equals(config.renderMode);
    }

    public boolean hasActiveRemoteComposition() {
        return remoteTarget && targetDisplayId > 0 && rootSc != null && mir0Sc != null;
    }

    public int getTargetDisplayId() {
        return targetDisplayId;
    }

    public boolean needsAttachOrRebuild(int extId) {
        return needsAttachOrRebuildForTarget(extId, false);
    }

    public boolean needsAttachOrRebuildForTarget(int displayId, boolean isRemote) {
        boolean needSide = config.mirrorSideMode > BridgeProtocol.MIRROR_SIDE_OFF;
        boolean stock = !isRemote && "NATIVE_MIRROR_STOCK".equals(config.renderMode);
        boolean sideRetry = needSide && sideVd == null
                && (!sideVdAttempted || lastSideVdAttemptMode != config.mirrorSideMode);
        return displayId != targetDisplayId
                || isRemote != remoteTarget
                || (!isRemote && displayId != config.extId)
                || (!stock && (rootSc == null || mir0Sc == null || sideRetry || (!needSide && sideVd != null)));
    }

    public void attachMirrorDisplay(int extId) {
        attachMirrorTargetInternal(extId, false, config.physW, config.physH, config.physFps);
    }

    /**
     * Attaches the unified Display 0 + SideBar VirtualDisplay composition onto a remote stream
     * container VirtualDisplay so Mac Mirror Mode gets pixel-identical layout and 14-card/AppWidget
     * sidebars as a USB-C DisplayPort monitor.
     */
    public boolean attachRemoteMirrorDisplay(int remoteVdId, int streamW, int streamH, float streamFps) {
        if (remoteVdId <= 0 || streamW <= 0 || streamH <= 0) return false;
        return attachMirrorTargetInternal(remoteVdId, true, streamW, streamH, streamFps);
    }

    private boolean attachMirrorTargetInternal(int dispId, boolean isRemote, int outW, int outH, float outFps) {
        config.forceRebuild = false;
        try {
            Object mir0 = services.mirrorDisplay(0);
            if (mir0 == null) {
                if (isRemote) return false;
                config.extId = dispId;
                targetDisplayId = dispId;
                remoteTarget = false;
                config.vdId = -1;
                config.vdUniqueId = null;
                sideVdId = -1;
                config.renderMode = "NATIVE_MIRROR_STOCK";
                services.replaceContentOnDisplay(dispId, null);
                helperBridge.enqueuePushBinder(true);
                helperBridge.notifyHelperSync();
                System.out.println("OPALDESK_MIRROR_FALLBACK_STOCK extId=" + dispId);
                return true;
            }
            config.physW = outW;
            config.physH = outH;
            config.physFps = outFps;
            rootSc = SurfaceLayerHelper.buildContainerLayer("ExtRoot", null, false);
            bgSc = SurfaceLayerHelper.buildColorLayer("ExtBg", rootSc, false);
            mir0WrapSc = SurfaceLayerHelper.buildContainerLayer("ExtMir0Clip", rootSc, false);
            mir0BgSc = SurfaceLayerHelper.buildColorLayer("ExtMir0Bg", mir0WrapSc, false);
            mir0Sc = mir0;
            try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
                tx.reparent(mir0Sc, mir0WrapSc).apply();
            }

            boolean sideReady = (config.mirrorSideMode > BridgeProtocol.MIRROR_SIDE_OFF) && attachSideBarVd();
            targetDisplayId = dispId;
            remoteTarget = isRemote;
            config.extId = isRemote ? -1 : dispId;
            config.vdId = -1;
            config.vdUniqueId = null;
            config.renderMode = isRemote
                    ? (sideReady ? "REMOTE_MIRROR_SIDEBAR" : "REMOTE_MIRROR_COMP")
                    : (sideReady ? "MIRROR_SIDEBAR" : "MIRROR_COMP");
            localInput.resetCursorCenter(config.physW, config.physH);
            lastAppliedMirSideMode = -1;

            cursorController.initOverlay(rootSc);
            registerDisplayListener();
            updateMirrorGeometry(false);
            if (!reassert() && isRemote) {
                teardown(false);
                return false;
            }
            systemControl.grantAppWidgetBind();
            cursorController.startPoller();
            helperBridge.enqueuePushBinder(true);
            helperBridge.notifyHelperSync();
            MirrorGeom g = mirGeom;
            System.out.println("OPALDESK_MIRROR_ATTACHED dispId=" + dispId + " remote=" + isRemote + " sideVdId=" + sideVdId
                    + " d0=" + g.d0W + "x" + g.d0H + " mir=" + g.mirW + "x" + g.mirH + "@" + g.mirX + "," + g.mirY);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MIRROR_ATTACH_ERR: " + e.getMessage());
            teardown(false);
            return false;
        }
    }

    public boolean reassert() {
        Object root = rootSc;
        int tid = targetDisplayId;
        if (tid <= 0 || root == null) return false;
        boolean ok = services.replaceContentOnDisplay(tid, root);
        if (!ok) return false;
        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            tx.setLayer(root, Integer.MAX_VALUE).setPosition(root, 0f, 0f).show(root).apply();
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MIRROR_REASSERT_WARN: " + e.getMessage());
        }
        return true;
    }

    public void teardownForDisplay(int expectedDisplayId) {
        if (expectedDisplayId > 0 && targetDisplayId == expectedDisplayId) {
            teardown(true);
        }
    }

    public void teardown(boolean unbindWm) {
        Object[] curLayers = cursorController.detachOverlayLayers();
        unregisterDisplayListener();
        int prevTarget = targetDisplayId;
        targetDisplayId = -1;
        remoteTarget = false;
        if (unbindWm && prevTarget > 0) services.replaceContentOnDisplay(prevTarget, null);
        releaseSideResources();

        Object[] all = {curLayers[0], curLayers[1], mir0Sc, mir0BgSc, mir0WrapSc, bgSc, rootSc};
        mir0Sc = mir0BgSc = mir0WrapSc = bgSc = rootSc = null;
        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            for (Object sc : all) if (sc != null) tx.reparent(sc, null).remove(sc);
            tx.apply(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MIR_CLEANUP_TX_WARN: " + e.getMessage());
        } finally {
            for (Object sc : all) if (sc != null) SurfaceLayerHelper.releaseSurfaceControl(sc);
        }
        sideVdId = -1;
        mirGeom = MirrorGeom.EMPTY;
        lastAppliedMirSideMode = -1;
    }

    public boolean updateMirrorGeometry(boolean notifyOnChange) {
        if (rootSc == null || bgSc == null || mir0Sc == null || mir0WrapSc == null || services.displayManager == null) return false;
        try {
            Point d0 = services.readDisplayRealSize(0);
            int curD0W = (d0.x > 0 && d0.y > 0) ? d0.x : 1920;
            int curD0H = (d0.x > 0 && d0.y > 0) ? d0.y : 1200;
            int physW = config.physW, physH = config.physH, sideMode = config.mirrorSideMode;
            float fit = Math.min(physW / (float) curD0W, physH / (float) curD0H);
            int[] placed = DisplayGeometryUtil.computeMirrorPlacement(curD0W, curD0H, physW, physH, sideMode);
            int mirX = placed[0], mirY = placed[1], mirW = placed[2], mirH = placed[3];

            MirrorGeom prev = mirGeom;
            boolean changed = curD0W != prev.d0W || curD0H != prev.d0H
                    || mirW != prev.mirW || mirH != prev.mirH || mirX != prev.mirX || mirY != prev.mirY
                    || sideMode != lastAppliedMirSideMode;
            if (!changed && notifyOnChange) return false;

            mirGeom = new MirrorGeom(curD0W, curD0H, mirX, mirY, mirW, mirH);
            lastAppliedMirSideMode = sideMode;
            applyGeometryTx(physW, physH, sideMode, curD0W, curD0H, mirX, mirY, mirW, mirH, fit);
            if (notifyOnChange && changed) helperBridge.notifyHelperSync();
            return changed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_MIRROR_GEOM_ERR: " + e.getMessage());
            return false;
        }
    }

    // ---- Coordinate Mapping & Delegated Input / Cursor Operations ----

    public boolean isPointInMirrorMain(float cx, float cy) {
        MirrorGeom g = mirGeom;
        return g.mirW > 0 && g.mirH > 0 && cx >= g.mirX && cx < g.mirX + g.mirW && cy >= g.mirY && cy < g.mirY + g.mirH;
    }

    public int resolveMirrorTargetDisplay(float cx, float cy) {
        return isPointInMirrorMain(cx, cy) ? 0 : (sideVdId > 0 ? sideVdId : 0);
    }

    public float mapMirrorX(int targetDisp, float cx) {
        MirrorGeom g = mirGeom;
        return (targetDisp == 0 && g.mirW > 0 && g.d0W > 0)
                ? Math.max(0f, Math.min(g.d0W - 1f, (cx - g.mirX) * g.d0W / (float) g.mirW))
                : Math.max(0f, Math.min(config.physW - 1f, cx));
    }

    public float mapMirrorY(int targetDisp, float cy) {
        MirrorGeom g = mirGeom;
        return (targetDisp == 0 && g.mirH > 0 && g.d0H > 0)
                ? Math.max(0f, Math.min(g.d0H - 1f, (cy - g.mirY) * g.d0H / (float) g.mirH))
                : Math.max(0f, Math.min(config.physH - 1f, cy));
    }

    public void updateMirrorCursorSurface(float cx, float cy, boolean pressing, boolean show) {
        cursorController.updateSurface(cx, cy, pressing, show);
    }

    public void handleSideDirectTouch(int action, float sx, float sy) {
        cursorController.handleSideDirectTouch(action, sx, sy);
    }

    public boolean handleCapturedMirrorMouse(int actionType, float a1, float a2, float a3) {
        return cursorController.handleCapturedMirrorMouse(actionType, a1, a2, a3);
    }

    public boolean triggerEdgeHoverEntry(float ptX, float ptY) {
        return cursorController.triggerEdgeHoverEntry(ptX, ptY);
    }

    public void onHelperDeathLocked(long now, float cx, float cy) {
        cursorController.onHelperDeathLocked(now, cx, cy);
    }

    public void scheduleMirrorSideClickPostChecks() {
        if (mainHandler == null || config.shuttingDown) return;
        mainHandler.postDelayed(cursorController::reassertDisplay0Focus, 22L);
        mainHandler.postDelayed(cursorController::reassertDisplay0Focus, 85L);
        int svd = sideVdId;
        if (svd > 0) {
            for (long delayMs : new long[]{35L, 110L, 280L}) {
                bgSchedExec.schedule(() -> rescueStrayTasksOnSideVd(svd), delayMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void scheduleStrayTaskRescueIfActive() {
        int svd = sideVdId;
        if (svd > 0) bgIoExec.execute(() -> rescueStrayTasksOnSideVd(svd));
    }

    public void rescueStrayTasksOnSideVd(int targetSideVdId) {
        if (targetSideVdId <= 0 || config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR) return;
        List<?> infos = taskApi.getAllRootTaskInfosOnDisplay(targetSideVdId);
        if (infos == null || infos.isEmpty()) return;
        for (Object ti : infos) {
            int tid = (ti != null) ? TaskApiAdapter.extractTaskId(ti) : -1;
            if (tid > 0) taskApi.moveRootTaskToDisplay(tid, 0);
        }
        cursorController.reassertDisplay0Focus();
    }

    private boolean attachSideBarVd() {
        sideVdAttempted = true;
        lastSideVdAttemptMode = config.mirrorSideMode;
        try {
            sideMirSc = SurfaceLayerHelper.buildBlastLayer("ExtMirrorSide", rootSc, false);
            sideBlast = SurfaceLayerHelper.createBlastSurface("ExtSideBBQ", sideMirSc, config.physW, config.physH, 1);
            if (sideBlast.surface == null) { releaseSideResources(); return false; }
            int sideDpi = Math.max(120, Math.min(360, Math.round(Math.min(config.physW, config.physH) * 200f / 1440f)));
            int[] chosen = new int[1];
            sideVd = VirtualDisplayFactory.createWithFlagFallback(
                    services.displayManager, "RedmiKPad-MirrorSide", config.physW, config.physH, sideDpi,
                    SIDE_VD_CANDIDATE_FLAGS, DisplayGeometryUtil.clampFps(config.physFps, 60f), sideBlast.surface, chosen);
            if (sideVd != null && sideVd.getDisplay() != null) {
                sideVdId = sideVd.getDisplay().getDisplayId();
                compatPolicy.applyDisplayFullscreenPolicy(sideVdId, false);
                System.out.println("OPALDESK_SIDE_VD_OK id=" + sideVdId + " flags=0x" + Integer.toHexString(chosen[0]));
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("OPALDESK_SIDE_VD_ERR: " + e.getMessage());
        }
        releaseSideResources();
        return false;
    }

    private void releaseSideResources() {
        VirtualDisplay vd = sideVd;
        sideVd = null;
        if (vd != null) try { vd.release(); } catch (RuntimeException e) { System.err.println("OPALDESK_SIDE_VD_REL_WARN: " + e.getMessage()); }
        SurfaceLayerHelper.BlastSurface b = sideBlast;
        sideBlast = null;
        if (b != null) b.close();
        Object sc = sideMirSc;
        sideMirSc = null;
        if (sc != null) SurfaceLayerHelper.removeAndReleaseLayer(sc);
        sideVdId = -1;
    }

    private void applyGeometryTx(
            int physW, int physH, int sideMode, int d0W, int d0H, int mirX, int mirY, int mirW, int mirH, float fit)
            throws ReflectiveOperationException {
        int leftW = Math.max(0, mirX), rightW = Math.max(0, physW - (mirX + mirW));
        int topH = Math.max(0, mirY), bottomH = Math.max(0, physH - (mirY + mirH));
        boolean hasH = (leftW >= EDGE_MARGIN_MIN_PX || rightW >= EDGE_MARGIN_MIN_PX);
        boolean hasV = !hasH && (topH >= EDGE_MARGIN_MIN_PX || bottomH >= EDGE_MARGIN_MIN_PX);
        boolean singleWing = hasH
                ? ((leftW < EDGE_MARGIN_MIN_PX) != (rightW < EDGE_MARGIN_MIN_PX))
                : ((topH < EDGE_MARGIN_MIN_PX) != (bottomH < EDGE_MARGIN_MIN_PX));

        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            float[] bgRgb = (sideMode > 0) ? new float[]{0.025f, 0.035f, 0.065f} : new float[]{0f, 0f, 0f};
            tx.setLayer(bgSc, -1).setColor(bgSc, bgRgb).setAlpha(bgSc, 1.0f).setWindowCrop(bgSc, physW, physH).show(bgSc);
            tx.setLayer(mir0WrapSc, 1).setAlpha(mir0WrapSc, 1.0f).setPosition(mir0WrapSc, mirX, mirY).setWindowCrop(mir0WrapSc, mirW, mirH).show(mir0WrapSc);
            if (mir0BgSc != null) {
                tx.setLayer(mir0BgSc, -1).setColor(mir0BgSc, new float[]{0f, 0f, 0f}).setAlpha(mir0BgSc, 1.0f).setWindowCrop(mir0BgSc, mirW, mirH).show(mir0BgSc);
            }
            tx.setLayer(mir0Sc, 0).setAlpha(mir0Sc, 1.0f).setScale(mir0Sc, fit, fit).setWindowCrop(mir0Sc, d0W, d0H).setPosition(mir0Sc, 0f, 0f).show(mir0Sc);
            if (sideMirSc != null) {
                if (sideMode <= 0 || (!hasH && !hasV)) {
                    tx.hide(sideMirSc);
                } else {
                    tx.setAlpha(sideMirSc, 1.0f).setPosition(sideMirSc, 0f, 0f);
                    if (singleWing) {
                        Rect crop = hasH
                                ? (leftW < EDGE_MARGIN_MIN_PX ? new Rect(mirX + mirW, 0, physW, physH) : new Rect(0, 0, leftW, physH))
                                : (topH < EDGE_MARGIN_MIN_PX ? new Rect(0, mirY + mirH, physW, physH) : new Rect(0, 0, physW, topH));
                        tx.setLayer(sideMirSc, 2).setCrop(sideMirSc, crop);
                    } else {
                        tx.setLayer(sideMirSc, 0).setCrop(sideMirSc, new Rect(0, 0, physW, physH)).setWindowCrop(sideMirSc, physW, physH);
                    }
                    tx.show(sideMirSc);
                }
            }
            tx.setWindowCrop(rootSc, physW, physH).apply();
        }
    }

    private void registerDisplayListener() {
        if (services.displayManager == null || mainHandler == null || displayListener != null) return;
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) {
                if (id == 0 && config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && !config.shuttingDown) {
                    Runnable sync = () -> { if (!config.shuttingDown && config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && mir0Sc != null) updateMirrorGeometry(true); };
                    mainHandler.post(sync);
                    mainHandler.postDelayed(sync, 120L);
                }
            }
        };
        try { services.displayManager.registerDisplayListener(displayListener, mainHandler); }
        catch (RuntimeException e) { displayListener = null; System.err.println("OPALDESK_MIR_LISTENER_ERR: " + e.getMessage()); }
    }

    private void unregisterDisplayListener() {
        DisplayManager.DisplayListener l = displayListener;
        displayListener = null;
        if (services.displayManager != null && l != null) {
            try { services.displayManager.unregisterDisplayListener(l); }
            catch (RuntimeException e) { System.err.println("OPALDESK_MIR_UNREG_WARN: " + e.getMessage()); }
        }
    }
}
