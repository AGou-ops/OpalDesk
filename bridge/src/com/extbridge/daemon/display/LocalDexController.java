package com.extbridge.daemon.display;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;

import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DisplayGeometryUtil;
import com.extbridge.daemon.input.InputInjector;
import com.extbridge.daemon.input.LocalInputController;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.platform.VirtualDisplayFactory;
import com.extbridge.daemon.sink.DisplaySink;
import com.extbridge.daemon.window.TaskWindowController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Manages the on-device standalone DeX {@link VirtualDisplay} rendered directly onto a
 * Helper-provided {@link Surface} (zero-copy SurfaceFlinger composition, no external display
 * or remote Mac required).
 *
 * <p>Preserves the user's saved external-monitor {@link DaemonConfig} geometry while active and
 * supports surface detach/re-attach across Activity background/foreground transitions without
 * tearing down running DeX windows.
 */
public final class LocalDexController {
    private static final String VD_NAME = "ExtBridge-LocalDeX";

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompatPolicyController compatPolicy;
    private final HelperClientBridge helperBridge;
    private final InputInjector injector;
    private final LocalInputController localInput;
    private final TaskApiAdapter taskApi;
    private final Handler mainHandler;
    private final ExecutorService bgIoExec;

    private volatile TaskWindowController taskController;
    private volatile BooleanSupplier isRemoteDesktopActive;
    private volatile Runnable teardownPhysicalForLocal;

    private volatile VirtualDisplay localVd;
    private volatile Surface attachedSurface;
    private volatile int createdVdW = 0;
    private volatile int createdVdH = 0;
    private volatile boolean active = false;

    // Saved external-monitor config restored cleanly when Local DeX exits.
    private boolean hasSavedExtConfig = false;
    private int savedVirtW = 2560;
    private int savedVirtH = 1440;
    private int savedVirtDpi = 240;
    private float savedVirtFps = 60.0f;
    private int savedVirtRot = 0;

    public LocalDexController(
            AndroidServices services,
            DaemonConfig config,
            CompatPolicyController compatPolicy,
            HelperClientBridge helperBridge,
            InputInjector injector,
            LocalInputController localInput,
            TaskApiAdapter taskApi,
            Handler mainHandler,
            ExecutorService bgIoExec) {
        this.services = services;
        this.config = config;
        this.compatPolicy = compatPolicy;
        this.helperBridge = helperBridge;
        this.injector = injector;
        this.localInput = localInput;
        this.taskApi = taskApi;
        this.mainHandler = mainHandler;
        this.bgIoExec = bgIoExec;
    }

    public void wireCollaborators(
            TaskWindowController taskController,
            BooleanSupplier isRemoteDesktopActive,
            Runnable teardownPhysicalForLocal) {
        this.taskController = taskController;
        this.isRemoteDesktopActive = isRemoteDesktopActive;
        this.teardownPhysicalForLocal = teardownPhysicalForLocal;
    }

    public boolean isActive() {
        return active && localVd != null && config.vdId > 0
                && config.activeSinkType == DisplaySink.SINK_LOCAL;
    }

    /**
     * Creates or re-attaches the standalone on-device DeX VirtualDisplay onto {@code surface}.
     *
     * @return positive {@code vdId} on success, {@code -2} if a Remote Mac desktop session is
     *         currently active, or {@code -1} on invalid surface/creation failure.
     */
    public int startOrAttach(int reqW, int reqH, int reqDpi, int reqFpsX100, Surface surface) {
        if (surface == null || !surface.isValid() || services.displayManager == null) {
            releaseSurfaceQuietly(surface);
            return -1;
        }
        int w = DisplayGeometryUtil.alignDimension(reqW, 2, DisplayGeometryUtil.MIN_SHORT_EDGE, DisplayGeometryUtil.MAX_LONG_EDGE);
        int h = DisplayGeometryUtil.alignDimension(reqH, 2, DisplayGeometryUtil.MIN_SHORT_EDGE, DisplayGeometryUtil.MAX_LONG_EDGE);
        if (!DisplayGeometryUtil.isValidDims(w, h)) {
            releaseSurfaceQuietly(surface);
            return -1;
        }
        int dpi = DisplayGeometryUtil.clampDpi(reqDpi, w, h);
        float fps = (reqFpsX100 > 0)
                ? DisplayGeometryUtil.clampFps(reqFpsX100 / 100.0f, 60.0f)
                : 60.0f;

        BooleanSupplier remoteCheck = isRemoteDesktopActive;
        if (remoteCheck != null && remoteCheck.getAsBoolean()) {
            releaseSurfaceQuietly(surface);
            return -2;
        }

        synchronized (config.displayLock) {
            if (config.shuttingDown) {
                releaseSurfaceQuietly(surface);
                return -1;
            }
            if (remoteCheck != null && remoteCheck.getAsBoolean()) {
                releaseSurfaceQuietly(surface);
                return -2;
            }

            if (localVd != null && active) {
                return reattachOrResizeLocked(w, h, dpi, fps, surface);
            }

            // Tear down any physical external display / mirror resources before claiming config.vdId.
            Runnable teardownPhys = teardownPhysicalForLocal;
            if (teardownPhys != null) teardownPhys.run();

            if (!hasSavedExtConfig) {
                savedVirtW = config.virtW;
                savedVirtH = config.virtH;
                savedVirtDpi = config.virtDpi;
                savedVirtFps = config.virtFps;
                savedVirtRot = config.virtRot;
                hasSavedExtConfig = true;
            }

            return createFreshDisplayLocked(w, h, dpi, fps, surface, null);
        }
    }

    /**
     * Detaches the Activity surface without releasing the VirtualDisplay so DeX apps remain
     * alive while {@code LocalDexActivity} is backgrounded or the screen turns off.
     */
    public boolean detachSurface() {
        synchronized (config.displayLock) {
            VirtualDisplay vd = localVd;
            if (vd == null) return false;
            try {
                vd.setSurface(null);
            } catch (RuntimeException e) {
                System.err.println("EXTBRIDGE_LOCAL_DEX_DETACH_SURF_WARN: " + e.getMessage());
            }
            releaseSurfaceQuietly(attachedSurface);
            attachedSurface = null;
            return true;
        }
    }

    public boolean stop() {
        synchronized (config.displayLock) {
            return stopLocked(true);
        }
    }

    /** Stops and releases the Local DeX VirtualDisplay. Must hold {@link DaemonConfig#displayLock}. */
    public boolean stopLocked(boolean notifyHelper) {
        boolean wasActive = active || localVd != null || config.activeSinkType == DisplaySink.SINK_LOCAL;
        int prevVdId = config.vdId;
        int prevW = config.virtW;
        int prevH = config.virtH;
        active = false;

        if (prevVdId > 0 && localVd != null) {
            injector.releaseUhidForDisplay(prevVdId);
        }
        VirtualDisplay vd = localVd;
        localVd = null;
        if (vd != null) {
            try {
                vd.setSurface(null);
            } catch (RuntimeException ignored) {
            }
        }
        // Restore every DeX user task back to FULLSCREEN with cleared bounds BEFORE releasing
        // the VirtualDisplay so apps never carry half-screen / cropped freeform bounds onto Display 0.
        List<Integer> restoredTids = Collections.emptyList();
        if (taskController != null && prevVdId > 0) {
            if (taskController.getWindowManager().isTilingActive()) {
                taskController.exitTilingAndRestore(false);
            }
            restoredTids = taskController.restoreDisplayTasksForTeardown(prevVdId, prevW, prevH);
        }
        if (vd != null) {
            try {
                vd.release();
            } catch (RuntimeException e) {
                System.err.println("EXTBRIDGE_LOCAL_DEX_REL_WARN: " + e.getMessage());
            }
        }
        releaseSurfaceQuietly(attachedSurface);
        attachedSurface = null;
        createdVdW = 0;
        createdVdH = 0;

        if (config.activeSinkType == DisplaySink.SINK_LOCAL || "LOCAL_DEX".equals(config.renderMode)) {
            config.vdId = -1;
            config.vdUniqueId = null;
            config.extId = -1;
            config.renderMode = "NONE";
            config.activeSinkType = DisplaySink.SINK_NONE;
            restoreSavedExtConfigLocked();
            if (taskController != null) {
                taskController.getWindowManager().clearAllManagedWindows();
            }
            compatPolicy.resetAppFullscreenCompatAll();
        }

        if (taskController != null && !restoredTids.isEmpty()) {
            taskController.schedulePostTeardownDisplay0Sanitize(restoredTids);
        }

        if (wasActive) {
            System.out.println("EXTBRIDGE_LOCAL_DEX_STOPPED prevVdId=" + prevVdId);
            if (notifyHelper) helperBridge.notifyHelperSync();
        }
        return wasActive;
    }

    /** Periodic daemon reconciliation tick while Local DeX is active. Must hold {@link DaemonConfig#displayLock}. */
    public void tickLocalLocked() {
        VirtualDisplay vd = localVd;
        if (vd == null || vd.getDisplay() == null) {
            stopLocked(true);
            return;
        }
        if (config.forceRebuild) {
            config.forceRebuild = false;
            if (taskController != null) taskController.scheduleRelayoutAfterResize();
            helperBridge.notifyHelperSync();
        }
        if (taskController != null) {
            taskController.enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1);
        }
    }

    /** Handles live DPI/config edits from MainActivity while Local DeX is active. Must hold {@link DaemonConfig#displayLock}. */
    public void resizeFromConfigLocked() {
        VirtualDisplay vd = localVd;
        if (!isActive() || vd == null) return;
        int w = DisplayGeometryUtil.alignDimension(config.virtW, 2, DisplayGeometryUtil.MIN_SHORT_EDGE, DisplayGeometryUtil.MAX_LONG_EDGE);
        int h = DisplayGeometryUtil.alignDimension(config.virtH, 2, DisplayGeometryUtil.MIN_SHORT_EDGE, DisplayGeometryUtil.MAX_LONG_EDGE);
        int dpi = DisplayGeometryUtil.clampDpi(config.virtDpi, w, h);
        try {
            vd.resize(w, h, dpi);
            config.virtW = w;
            config.virtH = h;
            config.virtDpi = dpi;
            localInput.clampCursor(w, h);
            if (taskController != null) taskController.scheduleRelayoutAfterResize();
            helperBridge.notifyHelperSync();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_LOCAL_DEX_RESIZE_CFG_WARN: " + e.getMessage());
        }
    }

    private int reattachOrResizeLocked(int w, int h, int dpi, float fps, Surface surface) {
        VirtualDisplay vd = localVd;
        if (vd == null || vd.getDisplay() == null) {
            return createFreshDisplayLocked(w, h, dpi, fps, surface, null);
        }

        if (createdVdW > 0 && createdVdH > 0
                && DisplayGeometryUtil.isOrientationAspectFlipped(createdVdW, createdVdH, w, h)) {
            ArrayList<Integer> prevTasks = (taskController != null)
                    ? taskController.collectExtUserTaskIds(config.vdId)
                    : new ArrayList<>();
            releaseVdOnlyLocked();
            return createFreshDisplayLocked(w, h, dpi, fps, surface, prevTasks);
        }

        boolean geomChanged = (w != config.virtW || h != config.virtH || dpi != config.virtDpi);
        if (Build.VERSION.SDK_INT >= 30) setFrameRateQuietly(surface, fps);
        try {
            if (geomChanged) {
                vd.resize(w, h, dpi);
                config.virtW = w;
                config.virtH = h;
                config.virtDpi = dpi;
                createdVdW = w;
                createdVdH = h;
            }
            config.virtFps = fps;
            vd.setSurface(surface);
            Surface prevSurf = attachedSurface;
            attachedSurface = surface;
            if (prevSurf != surface) releaseSurfaceQuietly(prevSurf);
            if (geomChanged) {
                localInput.clampCursor(w, h);
                if (taskController != null) taskController.scheduleRelayoutAfterResize();
            }
            helperBridge.notifyHelperSync();
            System.out.println("EXTBRIDGE_LOCAL_DEX_REATTACHED vdId=" + config.vdId
                    + " size=" + w + "x" + h + "@" + dpi + "dpi");
            return config.vdId;
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_LOCAL_DEX_REATTACH_ERR: " + e.getMessage());
            ArrayList<Integer> prevTasks = (taskController != null)
                    ? taskController.collectExtUserTaskIds(config.vdId)
                    : new ArrayList<>();
            releaseVdOnlyLocked();
            return createFreshDisplayLocked(w, h, dpi, fps, surface, prevTasks);
        }
    }

    private int createFreshDisplayLocked(
            int w, int h, int dpi, float fps, Surface surface, ArrayList<Integer> migrateTasks) {
        if (Build.VERSION.SDK_INT >= 30) setFrameRateQuietly(surface, fps);
        int baseFlags = config.getVirtualDisplayFlags();
        int[] flagCandidates = new int[]{
                baseFlags,
                baseFlags | BridgeProtocol.VD_FLAG_PRESENTATION
        };
        VirtualDisplay created = VirtualDisplayFactory.createWithFlagFallback(
                services.displayManager, VD_NAME, w, h, dpi, flagCandidates, fps, surface, null);
        if (created == null || created.getDisplay() == null) {
            System.err.println("EXTBRIDGE_LOCAL_DEX_CREATE_FAILED size=" + w + "x" + h + "@" + dpi);
            releaseSurfaceQuietly(surface);
            return -1;
        }

        Display disp = created.getDisplay();
        int newVdId = disp.getDisplayId();
        localVd = created;
        attachedSurface = surface;
        createdVdW = w;
        createdVdH = h;
        active = true;

        config.extId = -1;
        config.vdId = newVdId;
        config.vdUniqueId = VirtualDisplayFactory.getUniqueId(disp);
        config.mirrorMode = BridgeProtocol.MIRROR_MODE_DEX;
        config.virtW = w;
        config.virtH = h;
        config.virtDpi = dpi;
        config.virtFps = fps;
        config.virtRot = 0;
        config.renderMode = "LOCAL_DEX";
        config.activeSinkType = DisplaySink.SINK_LOCAL;
        config.forceRebuild = false;

        compatPolicy.applyDisplayFullscreenPolicy(newVdId, config.fullscreenPolicy == 1);
        localInput.resetCursorCenter(w, h);
        if (taskController != null) {
            taskController.launchLauncher();
            if (migrateTasks != null && !migrateTasks.isEmpty()) {
                for (int tid : migrateTasks) taskApi.moveRootTaskToDisplay(tid, newVdId);
            }
            if (mainHandler != null) mainHandler.postDelayed(taskController::postAttachLayoutSync, 220L);
        }
        bgIoExec.execute(() -> compatPolicy.sanitizeSystemHomeTasks(false, newVdId, -1, -1));
        helperBridge.enqueuePushBinder(true);
        helperBridge.startHelperService();
        helperBridge.notifyHelperSync();
        System.out.println("EXTBRIDGE_LOCAL_DEX_STARTED vdId=" + newVdId
                + " size=" + w + "x" + h + "@" + dpi + "dpi," + fps + "fps");
        return newVdId;
    }

    private void releaseVdOnlyLocked() {
        if (config.vdId > 0 && localVd != null) injector.releaseUhidForDisplay(config.vdId);
        VirtualDisplay vd = localVd;
        localVd = null;
        if (vd != null) {
            try { vd.setSurface(null); } catch (RuntimeException ignored) {}
            try { vd.release(); } catch (RuntimeException ignored) {}
        }
        releaseSurfaceQuietly(attachedSurface);
        attachedSurface = null;
        createdVdW = 0;
        createdVdH = 0;
    }

    private void restoreSavedExtConfigLocked() {
        if (!hasSavedExtConfig) return;
        config.virtW = savedVirtW;
        config.virtH = savedVirtH;
        config.virtDpi = savedVirtDpi;
        config.virtFps = savedVirtFps;
        config.virtRot = savedVirtRot;
        hasSavedExtConfig = false;
    }

    private static void releaseSurfaceQuietly(Surface s) {
        if (s == null) return;
        try { s.release(); } catch (RuntimeException ignored) {}
    }

    private static void setFrameRateQuietly(Surface s, float fps) {
        try { s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE); }
        catch (RuntimeException e) { System.err.println("EXTBRIDGE_LOCAL_DEX_FPS_WARN: " + e.getMessage()); }
    }
}
