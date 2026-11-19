package com.extbridge.daemon.display;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DisplayGeometryUtil;
import com.extbridge.daemon.input.InputInjector;
import com.extbridge.daemon.input.LocalInputController;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.SurfaceLayerHelper;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.platform.VirtualDisplayFactory;
import com.extbridge.daemon.sink.DisplaySink;
import com.extbridge.daemon.util.ShellCommand;
import com.extbridge.daemon.window.TaskWindowController;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Physical external display detection, EDID selection, DeX VirtualDisplay attachment via
 * {@link VirtualDisplayFactory} and {@link SurfaceLayerHelper}, hardware surface transform,
 * live resize/rebuild, and physical DeX/mirror reconciliation tick.
 *
 * <p>Guarantees {@link SystemClock#sleep} is NEVER invoked while holding {@link DaemonConfig#displayLock}.
 */
public final class PhysicalDisplayController {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final long PHYS_MODE_SETTLE_MS = 120L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompatPolicyController compatPolicy;
    private final SystemControlService systemControl;
    private final NativeMirrorController mirrorDisplay;
    private final HelperClientBridge helperBridge;
    private final InputInjector injector;
    private final LocalInputController localInput;
    private final TaskApiAdapter taskApi;
    private final Handler mainHandler;
    private final ExecutorService bgIoExec;

    private volatile VirtualDisplay physVd;
    private volatile SurfaceLayerHelper.BlastSurface physBlast;
    private volatile SurfaceTexture fallbackSt;
    private volatile Surface fallbackSurf;
    private volatile Object rootSc, bgSc, scaleSc, mirrorSc;
    private volatile int createdVdW = 0, createdVdH = 0;
    private volatile int lastPhysResult = 0;
    private volatile int lastPushedExtId = -1;

    private volatile TaskWindowController taskController;
    private volatile Runnable tickTrigger;
    private volatile Runnable remoteResizeTrigger;

    public PhysicalDisplayController(
            AndroidServices services, DaemonConfig config, CompatPolicyController compatPolicy,
            SystemControlService systemControl, NativeMirrorController mirrorDisplay,
            HelperClientBridge helperBridge, InputInjector injector, LocalInputController localInput,
            TaskApiAdapter taskApi, Handler mainHandler, ExecutorService bgIoExec) {
        this.services = services;
        this.config = config;
        this.compatPolicy = compatPolicy;
        this.systemControl = systemControl;
        this.mirrorDisplay = mirrorDisplay;
        this.helperBridge = helperBridge;
        this.injector = injector;
        this.localInput = localInput;
        this.taskApi = taskApi;
        this.mainHandler = mainHandler;
        this.bgIoExec = bgIoExec;
    }

    public void wireCollaborators(TaskWindowController tc, Runnable tickTrigger, Runnable remoteResizeTrigger) {
        this.taskController = tc;
        this.tickTrigger = tickTrigger;
        this.remoteResizeTrigger = remoteResizeTrigger;
    }

    public int getLastPhysResult() { return lastPhysResult; }
    public boolean hasActiveDisplay() { return physVd != null || rootSc != null; }
    public void resetLastPushedExtId() { lastPushedExtId = -1; }

    public Display findExternalDisplay() {
        if (services.displayManager == null) return null;
        Display[] displays = services.displayManager.getDisplays();
        if (displays == null) return null;
        for (Display d : displays) {
            if (d != null && services.getDisplayType(d) == DISPLAY_TYPE_EXTERNAL) return d;
        }
        return null;
    }

    public Display.Mode pickBestNativeEdidMode(Display ext) {
        if (ext == null) return null;
        try {
            Display.Mode[] modes = ext.getSupportedModes();
            if (modes == null || modes.length == 0) return null;
            boolean has50Plus = false;
            for (Display.Mode m : modes) {
                if (m != null && m.getRefreshRate() >= 49.5f
                        && DisplayGeometryUtil.isValidDims(m.getPhysicalWidth(), m.getPhysicalHeight())) {
                    has50Plus = true;
                    break;
                }
            }
            Display.Mode best = null;
            long bestArea = -1L;
            float bestFps = -1f;
            for (Display.Mode m : modes) {
                if (m == null) continue;
                int mw = m.getPhysicalWidth(), mh = m.getPhysicalHeight();
                float mf = m.getRefreshRate();
                if (!DisplayGeometryUtil.isValidDims(mw, mh) || (has50Plus && mf < 49.5f)) continue;
                long area = (long) mw * (long) mh;
                float fpsScore = Math.min(mf, 120f);
                if (area > bestArea || (area == bestArea && fpsScore > bestFps + 0.2f)) {
                    best = m; bestArea = area; bestFps = fpsScore;
                }
            }
            return best;
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_EDID_PICK_WARN: " + e.getMessage());
            return null;
        }
    }

    public String getModesSummary() {
        try {
            Display ext = findExternalDisplay();
            Display.Mode[] modes = (ext != null) ? ext.getSupportedModes() : null;
            if (modes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Display.Mode m : modes) {
                if (sb.length() > 0) sb.append(';');
                sb.append(m.getModeId()).append(':').append(m.getPhysicalWidth()).append('x')
                        .append(m.getPhysicalHeight()).append('@')
                        .append(String.format(Locale.US, "%.1f", m.getRefreshRate()));
            }
            return sb.toString();
        } catch (RuntimeException e) { return ""; }
    }

    /** Promotes an unconfigured monitor to best native EDID mode strictly OUTSIDE {@link DaemonConfig#displayLock}. */
    public void maybePromoteNativeEdidBeforeLock(Display ext) {
        if (ext == null || config.hasUserConf || config.mirrorMode != BridgeProtocol.MIRROR_MODE_DEX) return;
        if (ext.getDisplayId() == config.extId && physVd != null && !config.forceRebuild) return;
        Display.Mode cur = ext.getMode();
        int cw = (cur != null) ? cur.getPhysicalWidth() : config.physW;
        int ch = (cur != null) ? cur.getPhysicalHeight() : config.physH;
        float cf = (cur != null) ? cur.getRefreshRate() : config.physFps;
        Display.Mode best = pickBestNativeEdidMode(ext);
        if (best == null) return;
        long curArea = (long) Math.max(0, cw) * (long) Math.max(0, ch);
        long bestArea = (long) best.getPhysicalWidth() * (long) best.getPhysicalHeight();
        if ((bestArea > curArea || (bestArea == curArea && cf < 49.5f && best.getRefreshRate() >= 49.5f))
                && invokeSetUserPreferredMode(ext.getDisplayId(), best)) {
            SystemClock.sleep(PHYS_MODE_SETTLE_MS);
        }
    }

    /** Reconciles physical external display (DeX or Native Mirror). Must hold {@link DaemonConfig#displayLock}. */
    public void tickPhysicalLocked() {
        Display ext = findExternalDisplay();
        if (ext == null) {
            config.activeSinkType = DisplaySink.SINK_NONE;
            lastPushedExtId = -1;
            if (config.extId >= 0 || hasActiveDisplay() || mirrorDisplay.hasActiveResources()) {
                System.out.println("EXTBRIDGE_DETACHED");
                cleanupDisplayLocked(false, false);
                helperBridge.notifyHelperSync();
            }
            return;
        }
        config.activeSinkType = DisplaySink.SINK_PHYSICAL;
        int extId = ext.getDisplayId();
        int prevW = config.physW, prevH = config.physH;
        float prevFps = config.physFps;
        Display.Mode curMode = ext.getMode();
        if (curMode != null) {
            config.physW = curMode.getPhysicalWidth();
            config.physH = curMode.getPhysicalHeight();
            config.physFps = curMode.getRefreshRate();
        }
        boolean sizeChanged = (prevW != config.physW || prevH != config.physH);
        boolean fpsChanged = Math.abs(prevFps - config.physFps) > 0.2f;

        if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) {
            if (hasActiveDisplay() || config.forceRebuild || (sizeChanged && mirrorDisplay.hasActiveResources())) {
                config.forceRebuild = false;
                mirrorDisplay.resetSideVdAttempt();
                cleanupDisplayLocked(false, false);
            }
            if (mirrorDisplay.needsAttachOrRebuild(extId)) {
                cleanupDisplayLocked(false, false);
                mirrorDisplay.attachMirrorDisplay(extId);
                lastPushedExtId = extId;
                return;
            }
            boolean geomUpdated = false;
            if (!"NATIVE_MIRROR_STOCK".equals(config.renderMode)) {
                geomUpdated = mirrorDisplay.updateMirrorGeometry(true);
                mirrorDisplay.reassert();
                mirrorDisplay.scheduleStrayTaskRescueIfActive();
            }
            if ((sizeChanged || fpsChanged) && !geomUpdated) helperBridge.notifyHelperSync();
        } else {
            if (mirrorDisplay.hasActiveResources()) {
                mirrorDisplay.resetSideVdAttempt();
                cleanupDisplayLocked(false, false);
            }
            if (config.forceRebuild && hasActiveDisplay()) {
                config.forceRebuild = false;
                cleanupDisplayLocked(false, false);
            }
            if (extId != config.extId || !hasActiveDisplay()) {
                cleanupDisplayLocked(false, false);
                attachDisplay(extId);
                lastPushedExtId = extId;
                return;
            }
            if (sizeChanged) applySurfaceTransform();
            if (sizeChanged || fpsChanged) helperBridge.notifyHelperSync();
            reassert();
            injector.checkInput();
            if (taskController != null) taskController.enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1);
        }
        if (extId != lastPushedExtId) {
            lastPushedExtId = extId;
            if (!helperBridge.isHelperAlive()) helperBridge.enqueuePushBinder(true);
        }
    }

    /** Unified 4-step physical/mirror display cleanup. Must hold {@link DaemonConfig#displayLock}. */
    public void cleanupDisplayLocked(boolean unbindWm, boolean keepRemoteSession) {
        boolean noActiveSink = (findExternalDisplay() == null && !keepRemoteSession);
        if (noActiveSink) mirrorDisplay.resetSideVdAttempt();

        // 1. Restore main screen brightness FIRST so persistent low brightness can never survive exit.
        if (unbindWm || config.shuttingDown || noActiveSink) systemControl.restoreDimIfActive();
        // 2. Unbind external input associations before VirtualDisplay release.
        injector.unbindInput();
        // 3. Release physical DeX and native mirror SurfaceControls & VirtualDisplays.
        int prevVdId = config.vdId;
        teardownDexLayers(unbindWm);
        mirrorDisplay.teardown(unbindWm);
        config.extId = -1;
        if (!keepRemoteSession) {
            config.vdId = -1;
            config.vdUniqueId = null;
            config.renderMode = "NONE";
        }
        // 4. Exit tiling, clear managed windows & reset compat overrides last.
        if (unbindWm || config.shuttingDown || config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR || noActiveSink) {
            if (taskController != null) {
                if (taskController.getWindowManager().isTilingActive() && prevVdId > 0) {
                    taskController.exitTilingAndRestore(false);
                }
                taskController.getWindowManager().clearAllManagedWindows();
            }
        }
        if (unbindWm || config.shuttingDown || noActiveSink) {
            compatPolicy.resetAppFullscreenCompatAll();
            if (!keepRemoteSession && findExternalDisplay() == null) config.activeSinkType = DisplaySink.SINK_NONE;
        }
    }

    public void attachDisplay(int extId) {
        config.forceRebuild = false;
        try {
            if (!config.hasUserConf && DisplayGeometryUtil.isValidDims(config.physW, config.physH)) {
                boolean swap = DisplayGeometryUtil.isPortraitRotation(config.virtRot);
                config.virtW = swap ? config.physH : config.physW;
                config.virtH = swap ? config.physW : config.physH;
                config.virtDpi = DisplayGeometryUtil.computeAutoDpi(config.virtW, config.virtH);
                config.virtFps = (config.physFps >= 20f && config.physFps <= 240f) ? config.physFps : 60f;
            }
            System.out.println("EXTBRIDGE_ATTACHING extId=" + extId + " phys=" + config.physW + "x" + config.physH
                    + "@" + config.physFps + " virt=" + config.virtW + "x" + config.virtH + "@" + config.virtDpi);

            rootSc = SurfaceLayerHelper.buildContainerLayer("ExtRoot", null, false);
            bgSc = SurfaceLayerHelper.buildColorLayer("ExtBg", rootSc, false);
            scaleSc = SurfaceLayerHelper.buildContainerLayer("ExtScale", rootSc, false);

            Surface vdSurf = initOutputSurface();
            if (vdSurf != null && Build.VERSION.SDK_INT >= 30) setFrameRateQuietly(vdSurf, config.virtFps);

            physVd = VirtualDisplayFactory.create(services.displayManager, "RedmiKPad-Extended",
                    config.virtW, config.virtH, config.virtDpi, config.getVirtualDisplayFlags(), config.virtFps, vdSurf);
            if (physVd == null || physVd.getDisplay() == null) {
                System.err.println("EXTBRIDGE_CREATE_VD_FAILED");
                teardownDexLayers(false);
                return;
            }
            Display disp = physVd.getDisplay();
            int newVdId = disp.getDisplayId();
            config.vdId = newVdId;
            config.vdUniqueId = VirtualDisplayFactory.getUniqueId(disp);
            createdVdW = config.virtW;
            createdVdH = config.virtH;
            compatPolicy.applyDisplayFullscreenPolicy(newVdId, config.fullscreenPolicy == 1);

            if ("MIRROR".equals(config.renderMode)) {
                mirrorSc = services.mirrorDisplay(newVdId);
                if (mirrorSc != null) {
                    try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
                        tx.reparent(mirrorSc, scaleSc).apply();
                    }
                }
            }
            config.extId = extId;
            applySurfaceTransform();
            reassert();
            localInput.resetCursorCenter(config.virtW, config.virtH);
            injector.syncInput();
            if (taskController != null) {
                taskController.launchLauncher();
                if (mainHandler != null) mainHandler.postDelayed(taskController::postAttachLayoutSync, 220L);
            }
            bgIoExec.execute(() -> compatPolicy.sanitizeSystemHomeTasks(false, newVdId, -1, extId));
            helperBridge.enqueuePushBinder(true);
            helperBridge.notifyHelperSync();
            System.out.println("EXTBRIDGE_ATTACHED_OK vdId=" + newVdId + " render=" + config.renderMode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_ATTACH_ERR: " + e.getMessage());
            teardownDexLayers(false);
        }
    }

    public void applySurfaceTransform() {
        if (mirrorSc == null || bgSc == null || rootSc == null) return;
        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            int physW = config.physW, physH = config.physH, virtW = config.virtW, virtH = config.virtH;
            int rot = DisplayGeometryUtil.normalizeRotation(config.virtRot);
            int rotW = ((rot & 1) == 1) ? virtH : virtW;
            int rotH = ((rot & 1) == 1) ? virtW : virtH;
            float scale = Math.min(physW / (float) Math.max(1, rotW), physH / (float) Math.max(1, rotH));

            Matrix mat = new Matrix();
            mat.setRotate(rot * 90f);
            mat.postScale(scale, scale);
            RectF bounds = new RectF(0f, 0f, virtW, virtH);
            mat.mapRect(bounds);
            float[] mv = new float[9];
            mat.getValues(mv);

            tx.setLayer(bgSc, 0).setColor(bgSc, new float[]{0f, 0f, 0f}).setAlpha(bgSc, 1.0f).setWindowCrop(bgSc, physW, physH).show(bgSc)
                    .setLayer(scaleSc, 1).setMatrix(scaleSc, mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y], mv[Matrix.MSKEW_X], mv[Matrix.MSCALE_Y])
                    .setPosition(scaleSc, (physW - bounds.width()) * 0.5f - bounds.left, (physH - bounds.height()) * 0.5f - bounds.top).show(scaleSc)
                    .setLayer(mirrorSc, 0).setAlpha(mirrorSc, 1.0f).setWindowCrop(mirrorSc, virtW, virtH).setPosition(mirrorSc, 0f, 0f).show(mirrorSc)
                    .setWindowCrop(rootSc, physW, physH).apply();
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_TRANSFORM_ERR: " + e.getMessage());
        }
    }

    public void reassert() {
        Object root = rootSc;
        if (config.extId <= 0 || root == null) return;
        services.replaceContentOnDisplay(config.extId, root);
        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            tx.setLayer(root, Integer.MAX_VALUE).setPosition(root, 0f, 0f).show(root).apply();
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_REASSERT_WARN: " + e.getMessage());
        }
    }

    public boolean invokeSetUserPreferredMode(int extDisplayId, Display.Mode mode) {
        if (mode == null) return false;
        if (services.setUserPreferredModeMethod != null && services.setUserPreferredModeTarget != null) {
            try {
                if (services.setUserPreferredModeArgCount == 3) {
                    services.setUserPreferredModeMethod.invoke(services.setUserPreferredModeTarget, extDisplayId, mode, false);
                } else {
                    services.setUserPreferredModeMethod.invoke(services.setUserPreferredModeTarget, extDisplayId, mode);
                }
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("EXTBRIDGE_PHYS_INVOKE_FAILED: " + e.getMessage());
            }
        }
        String w = String.valueOf(mode.getPhysicalWidth()), h = String.valueOf(mode.getPhysicalHeight());
        String fps = String.format(Locale.US, "%.2f", mode.getRefreshRate()), did = String.valueOf(extDisplayId);
        String out = ShellCommand.runOutput("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did, "false");
        if (out.toLowerCase(Locale.US).contains("error")) {
            out = ShellCommand.runOutput("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did);
        }
        return !out.toLowerCase(Locale.US).contains("error");
    }

    public boolean applyConfig(int reqW, int reqH, int reqDpi, int reqFpsX100, int physModeId, int reqRot) {
        if (!DisplayGeometryUtil.isValidDims(reqW, reqH)
                || reqDpi < DisplayGeometryUtil.MIN_DPI || reqDpi > DisplayGeometryUtil.MAX_DPI) return false;
        Display.Mode chosenMode = null;
        int targetExtId = -1;
        synchronized (config.displayLock) {
            lastPhysResult = 0;
            float fps = (reqFpsX100 > 0) ? DisplayGeometryUtil.clampFps(reqFpsX100 / 100.0f, config.virtFps) : config.virtFps;
            config.virtW = reqW; config.virtH = reqH; config.virtDpi = reqDpi; config.virtFps = fps;
            if (reqRot >= 0) config.virtRot = DisplayGeometryUtil.normalizeRotation(reqRot);
            config.hasUserConf = true;
            config.saveConf();
            Display ext = findExternalDisplay();
            if (ext != null) {
                targetExtId = ext.getDisplayId();
                chosenMode = selectRequestedEdidMode(ext, reqW, reqH, fps, reqFpsX100, physModeId);
            }
        }
        // Physical EDID mode switch + settle sleep strictly OUTSIDE config.displayLock.
        if (chosenMode != null && targetExtId > 0) {
            boolean invoked = invokeSetUserPreferredMode(targetExtId, chosenMode);
            Display.Mode actual = null;
            if (invoked) {
                SystemClock.sleep(PHYS_MODE_SETTLE_MS);
                Display extNow = findExternalDisplay();
                actual = (extNow != null) ? extNow.getMode() : null;
            }
            synchronized (config.displayLock) {
                if (invoked) {
                    if (actual != null) {
                        config.physW = actual.getPhysicalWidth();
                        config.physH = actual.getPhysicalHeight();
                        config.physFps = actual.getRefreshRate();
                    }
                    lastPhysResult = isPhysicalModeMatched(actual, chosenMode) ? 1 : -3;
                    if (mainHandler != null) {
                        mainHandler.postDelayed(this::syncPhysicalModeIfChanged, 250L);
                        mainHandler.postDelayed(this::syncPhysicalModeIfChanged, 700L);
                    }
                } else {
                    lastPhysResult = -2;
                }
            }
        }
        synchronized (config.displayLock) { liveResizeOrRebuild(); }
        return true;
    }

    public int setRotation(int reqRot) {
        synchronized (config.displayLock) {
            int next = (reqRot < 0) ? ((config.virtRot + 1) % 4) : DisplayGeometryUtil.normalizeRotation(reqRot);
            int[] oriented = DisplayGeometryUtil.orientDimensionsForRotation(config.virtW, config.virtH, next);
            config.virtW = oriented[0]; config.virtH = oriented[1]; config.virtRot = next;
            config.hasUserConf = true;
            config.saveConf();
            liveResizeOrRebuild();
            return config.virtRot;
        }
    }

    public void syncPhysicalModeIfChanged() {
        synchronized (config.displayLock) {
            Display ext = findExternalDisplay();
            if (ext == null || ext.getDisplayId() != config.extId) return;
            Display.Mode cur = ext.getMode();
            if (cur == null) return;
            boolean sz = (cur.getPhysicalWidth() != config.physW || cur.getPhysicalHeight() != config.physH);
            boolean fp = Math.abs(cur.getRefreshRate() - config.physFps) > 0.2f;
            if (!sz && !fp) return;
            config.physW = cur.getPhysicalWidth(); config.physH = cur.getPhysicalHeight(); config.physFps = cur.getRefreshRate();
            if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR && sz) {
                config.forceRebuild = true;
                if (tickTrigger != null) tickTrigger.run();
                return;
            }
            if (sz) applySurfaceTransform();
            reassert();
            helperBridge.notifyHelperSync();
        }
    }

    private void liveResizeOrRebuild() {
        if (remoteResizeTrigger != null && (config.renderMode.startsWith("REMOTE_") || (physVd == null && config.extId < 0))) {
            remoteResizeTrigger.run();
            return;
        }
        if (physVd != null && createdVdW > 0 && createdVdH > 0
                && DisplayGeometryUtil.isOrientationAspectFlipped(createdVdW, createdVdH, config.virtW, config.virtH)) {
            ArrayList<Integer> prevTasks = (taskController != null) ? taskController.collectExtUserTaskIds(config.vdId) : new ArrayList<>();
            config.forceRebuild = true;
            if (tickTrigger != null) tickTrigger.run();
            if (config.vdId > 0) for (int tid : prevTasks) taskApi.moveRootTaskToDisplay(tid, config.vdId);
            helperBridge.notifyHelperSync();
            return;
        }
        if (physVd != null && "BBQ".equals(config.renderMode) && mirrorSc != null && tryHotSwapBlastSurface()) return;
        config.forceRebuild = true;
        helperBridge.notifyHelperSync();
    }

    private boolean tryHotSwapBlastSurface() {
        SurfaceLayerHelper.BlastSurface nextBlast = null;
        boolean swapped = false;
        try {
            SurfaceLayerHelper.BlastSurface oldBlast = physBlast;
            nextBlast = SurfaceLayerHelper.createBlastSurface("ExtBBQ", mirrorSc, config.virtW, config.virtH, 1);
            if (nextBlast.surface != null && Build.VERSION.SDK_INT >= 30) setFrameRateQuietly(nextBlast.surface, config.virtFps);
            physVd.resize(config.virtW, config.virtH, config.virtDpi);
            physVd.setSurface(nextBlast.surface);
            physBlast = nextBlast;
            swapped = true;
            if (oldBlast != null) oldBlast.close();
            applySurfaceTransform();
            if (mainHandler != null) mainHandler.postDelayed(this::applySurfaceTransform, 150L);
            localInput.clampCursor(config.virtW, config.virtH);
            reassert();
            if (taskController != null) taskController.scheduleRelayoutAfterResize();
            helperBridge.notifyHelperSync();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_RESIZE_ERR: " + e.getMessage());
            if (!swapped && nextBlast != null) nextBlast.close();
            return false;
        }
    }

    private void teardownDexLayers(boolean unbindWm) {
        if (config.vdId > 0 && physVd != null) injector.releaseUhidForDisplay(config.vdId);
        if (unbindWm && config.extId > 0) services.replaceContentOnDisplay(config.extId, null);
        VirtualDisplay vd = physVd; physVd = null;
        if (vd != null) try { vd.release(); } catch (RuntimeException e) { System.err.println("EXTBRIDGE_PHYS_VD_REL_WARN: " + e.getMessage()); }
        createdVdW = createdVdH = 0;
        SurfaceLayerHelper.BlastSurface b = physBlast; physBlast = null;
        if (b != null) b.close();
        Surface s = fallbackSurf; fallbackSurf = null; if (s != null) s.release();
        SurfaceTexture st = fallbackSt; fallbackSt = null; if (st != null) st.release();
        Object[] scs = {mirrorSc, scaleSc, bgSc, rootSc};
        mirrorSc = scaleSc = bgSc = rootSc = null;
        try (SurfaceLayerHelper.Transaction tx = SurfaceLayerHelper.openTransaction()) {
            for (Object sc : scs) if (sc != null) tx.reparent(sc, null).remove(sc);
            tx.apply(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_PHYS_CLEANUP_TX_WARN: " + e.getMessage());
        } finally {
            for (Object sc : scs) if (sc != null) SurfaceLayerHelper.releaseSurfaceControl(sc);
        }
    }

    private Surface initOutputSurface() {
        try {
            mirrorSc = SurfaceLayerHelper.buildBlastLayer("ExtDisplay", scaleSc, false);
            physBlast = SurfaceLayerHelper.createBlastSurface("ExtBBQ", mirrorSc, config.virtW, config.virtH, 1);
            if (physBlast.surface != null) { config.renderMode = "BBQ"; return physBlast.surface; }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_BBQ_FALLBACK_WARN: " + e.getMessage());
        }
        if (physBlast != null) { physBlast.close(); physBlast = null; }
        if (mirrorSc != null) { SurfaceLayerHelper.removeAndReleaseLayer(mirrorSc); mirrorSc = null; }
        config.renderMode = "MIRROR";
        fallbackSt = new SurfaceTexture(0);
        fallbackSt.setDefaultBufferSize(config.virtW, config.virtH);
        fallbackSurf = new Surface(fallbackSt);
        return fallbackSurf;
    }

    private Display.Mode selectRequestedEdidMode(Display ext, int reqW, int reqH, float fps, int reqFpsX100, int physModeId) {
        Display.Mode[] modes = ext.getSupportedModes();
        if (modes == null || modes.length == 0) return null;
        if (physModeId > 0) {
            for (Display.Mode m : modes) if (m.getModeId() == physModeId) return m;
            return null;
        }
        if (reqFpsX100 <= 0) return null;
        int matchW = DisplayGeometryUtil.isPortraitRotation(config.virtRot) ? reqH : reqW;
        int matchH = DisplayGeometryUtil.isPortraitRotation(config.virtRot) ? reqW : reqH;
        Display.Mode best = null;
        float bestScore = Float.MAX_VALUE;
        for (Display.Mode m : modes) {
            float diff = Math.abs(m.getRefreshRate() - fps);
            if (diff > 2.5f) continue;
            int mw = m.getPhysicalWidth(), mh = m.getPhysicalHeight();
            boolean resMatch = (mw == matchW && mh == matchH) || (mw == matchH && mh == matchW);
            float score = diff + (resMatch ? 0f : (5.0f + Math.max(0f, 4.0f - ((long) mw * mh / 2073600f))));
            if (score < bestScore) { bestScore = score; best = m; }
        }
        if (best == null) lastPhysResult = -1;
        return best;
    }

    private static boolean isPhysicalModeMatched(Display.Mode actual, Display.Mode chosen) {
        if (actual == null) return true;
        boolean dims = (actual.getPhysicalWidth() == chosen.getPhysicalWidth() && actual.getPhysicalHeight() == chosen.getPhysicalHeight())
                || (actual.getPhysicalWidth() == chosen.getPhysicalHeight() && actual.getPhysicalHeight() == chosen.getPhysicalWidth());
        return dims && Math.abs(actual.getRefreshRate() - chosen.getRefreshRate()) <= 2.5f;
    }

    private static void setFrameRateQuietly(Surface s, float fps) {
        try { s.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE); }
        catch (RuntimeException e) { System.err.println("EXTBRIDGE_SET_FPS_WARN: " + e.getMessage()); }
    }
}
