package io.github.lengsukq.opaldesk.daemon.stream;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.display.NativeMirrorController;
import io.github.lengsukq.opaldesk.daemon.input.InputInjector;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager;
import io.github.lengsukq.opaldesk.daemon.window.TaskWindowController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private unified session holder for either the main remote desktop session
 * or a standalone pop-out app session. Owns its encoder sink, VirtualDisplays, live
 * resize/aspect-flip task migration, peripherals, and {@link RemoteInputBridge.HostBridge}.
 */
final class RemoteDisplaySession {
    private static final int[] REMOTE_MIRROR_COMP_VD_FLAGS = {
            BridgeProtocol.VD_FLAGS_DEX_FIXED,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    };
    private static final int[] REMOTE_MIRROR_VD_FLAGS = {
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
    };

    final int sessionId;
    final int sinkType;
    final boolean standalone;
    final String appPackage;
    final String appActivity;
    final Object lock;

    volatile VirtualDisplay vd;
    volatile VirtualDisplay mirrorVd;
    volatile int mirrorCompDisplayId = -1;
    volatile int vdId = -1;
    volatile String vdUniqueId;
    volatile int width;
    volatile int height;
    volatile int createdVdW;
    volatile int createdVdH;
    volatile int dpi;
    volatile float fps;
    volatile int rotation;
    volatile int displayMode;
    volatile int codecPref;
    volatile int requestedBitrateKbps;
    volatile int appTaskId = -1;
    volatile RemoteStreamSink sink;
    volatile RemoteInputBridge inputBridge;
    volatile ClipboardSyncService clipboardSync;
    volatile FileTransferReceiver fileTransferReceiver;
    volatile boolean closed = false;

    RemoteDisplaySession(
            int sessionId, int sinkType, boolean standalone,
            String appPackage, String appActivity, Object lock) {
        this.sessionId = sessionId;
        this.sinkType = sinkType;
        this.standalone = standalone;
        this.appPackage = (appPackage != null) ? appPackage : "";
        this.appActivity = (appActivity != null) ? appActivity : "";
        this.lock = lock;
    }

    Surface startEncoder(
            OpalCastProtocol.HelloMsg hello, int defaultRot, int maxBitrateKbps,
            int effectiveCodecPref, RemoteStreamSink.FrameListener frameListener) {
        int[] aligned = DisplayGeometryUtil.alignForEncoder(hello.width, hello.height, 8);
        this.width = aligned[0];
        this.height = aligned[1];
        this.dpi = DisplayGeometryUtil.clampDpi(hello.dpi, this.width, this.height);
        this.fps = DisplayGeometryUtil.clampFps(hello.fps, 60f);
        this.rotation = standalone ? 0 : defaultRot;
        this.displayMode = (!standalone && hello.displayMode == OpalCastProtocol.DISPLAY_MODE_MIRROR)
                ? OpalCastProtocol.DISPLAY_MODE_MIRROR : OpalCastProtocol.DISPLAY_MODE_DEX;
        this.requestedBitrateKbps = DisplayGeometryUtil.clampBitrateKbps(hello.bitrateKbps, 1500, maxBitrateKbps);
        this.codecPref = effectiveCodecPref;

        RemoteStreamSink s = new RemoteStreamSink(sinkType, frameListener);
        s.setMaxBitrateKbps(maxBitrateKbps);
        Surface inputSurface = s.startOrReconfigure(
                width, height, dpi, fps, requestedBitrateKbps, codecPref, displayMode, rotation);
        if (inputSurface == null) return null;

        this.sink = s;
        this.width = s.getWidth();
        this.height = s.getHeight();
        this.dpi = s.getDpi();
        return inputSurface;
    }

    void wirePeripherals(RemoteSessionCoordinator coord) {
        this.inputBridge = new RemoteInputBridge(new SessionHostBridge(this, coord));
        this.clipboardSync = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
            @Override public void sendClipboardPush(long seq, String text) {
                RemoteStreamServer srv = coord.getServer();
                if (srv == null) return;
                if (standalone) srv.sendClipboardPush(sessionId, seq, text); else srv.sendClipboardPush(seq, text);
            }
            @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                RemoteStreamServer srv = coord.getServer();
                if (srv == null) return;
                if (standalone) srv.sendClipboardImagePush(sessionId, seq, pngData); else srv.sendClipboardImagePush(seq, pngData);
            }
            @Override public void triggerPasteKey() {
                RemoteInputBridge b = inputBridge; if (b != null) b.injectKeyTap(KeyEvent.KEYCODE_PASTE, 0);
            }
        }, null, !standalone);
        this.fileTransferReceiver = new FileTransferReceiver((transferId, status, pathOrError) -> {
            RemoteStreamServer srv = coord.getServer();
            if (srv == null) return;
            if (standalone) srv.sendFileTransferAck(sessionId, transferId, status, pathOrError);
            else srv.sendFileTransferAck(transferId, status, pathOrError);
        });
    }

    void startPeripherals() {
        if (inputBridge != null) inputBridge.onSessionStarted();
        if (clipboardSync != null) clipboardSync.onSessionStarted();
    }

    boolean attachDexVd(RemoteSessionCoordinator coord, Surface codecSurf) {
        if (codecSurf == null || coord.services.displayManager == null) return false;
        releaseDisplays(coord.config, coord.injector, coord.mirrorDisplay);
        try {
            String vdName = standalone ? ("OpalDesk-App-" + appPackage) : "OpalDesk-Remote";
            int vdFlags = standalone ? BridgeProtocol.VD_FLAGS_DEX_FIXED : coord.config.getVirtualDisplayFlags();
            int[] flagCandidates = new int[]{
                    vdFlags,
                    vdFlags | BridgeProtocol.VD_FLAG_PRESENTATION
            };
            VirtualDisplay created = VirtualDisplayFactory.createWithFlagFallback(
                    coord.services.displayManager, vdName, width, height, dpi,
                    flagCandidates, fps, codecSurf, null);
            if (created == null || created.getDisplay() == null) {
                System.err.println("OPALDESK_REMOTE_CREATE_VD_FAILED name=" + vdName);
                return false;
            }
            Display disp = created.getDisplay();
            this.vd = created;
            this.vdId = disp.getDisplayId();
            this.vdUniqueId = VirtualDisplayFactory.getUniqueId(disp);
            this.createdVdW = width;
            this.createdVdH = height;
            coord.compatPolicy.applyDisplayFullscreenPolicy(vdId, standalone || coord.config.fullscreenPolicy == 1, !standalone);

            if (!standalone) {
                syncDesktopConfig(coord.config, vdId, vdUniqueId, "REMOTE_DEX");
                coord.localInput.resetCursorCenter(width, height);
                coord.taskController.launchLauncher();
                final int attachedVdId = vdId;
                coord.bgIoExec.execute(() -> coord.compatPolicy.sanitizeSystemHomeTasks(false, attachedVdId, -1, -1));
                coord.helperBridge.enqueuePushBinder(true);
                coord.helperBridge.startHelperService();
                if (coord.mainHandler != null) coord.mainHandler.postDelayed(coord.taskController::postAttachLayoutSync, 220L);
                coord.helperBridge.notifyHelperSync();
            }
            coord.pushDeviceState(this);
            if (sink != null) sink.requestKeyFrame();
            System.out.println("OPALDESK_REMOTE_VD_ATTACHED sid=" + sessionId + " standalone=" + standalone
                    + " vdId=" + vdId + " size=" + width + "x" + height + "@" + dpi + "dpi");
            return true;
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_REMOTE_VD_ERR sid=" + sessionId + ": " + e.getMessage());
            return false;
        }
    }

    void attachMirrorVdLocked(RemoteSessionCoordinator coord, Surface codecSurf) {
        if (codecSurf == null || coord.services.displayManager == null) return;
        releaseDisplays(coord.config, coord.injector, coord.mirrorDisplay);
        int[] chosenFlags = new int[1];
        if (coord.mirrorDisplay != null) {
            coord.mirrorDisplay.resetSideVdAttempt();
            VirtualDisplay compVd = VirtualDisplayFactory.createWithFlagFallback(
                    coord.services.displayManager, "OpalDesk-RemoteMirrorComp",
                    width, height, dpi, REMOTE_MIRROR_COMP_VD_FLAGS, fps, codecSurf, chosenFlags);
            if (compVd != null && compVd.getDisplay() != null) {
                int compId = compVd.getDisplay().getDisplayId();
                coord.compatPolicy.applyDisplayFullscreenPolicy(compId, false, false);
                if (coord.mirrorDisplay.attachRemoteMirrorDisplay(compId, width, height, fps)) {
                    this.mirrorVd = compVd;
                    this.mirrorCompDisplayId = compId;
                    String modeTag = (coord.mirrorDisplay.getSideVdId() > 0) ? "REMOTE_MIRROR_SIDEBAR" : "REMOTE_MIRROR_COMP";
                    syncDesktopConfig(coord.config, -1, null, modeTag);
                    coord.helperBridge.startHelperService();
                    coord.pushDeviceState(this);
                    if (sink != null) sink.requestKeyFrame();
                    System.out.println("OPALDESK_REMOTE_MIRROR_COMP_ATTACHED dispId=" + compId
                            + " sideVdId=" + coord.mirrorDisplay.getSideVdId()
                            + " size=" + width + "x" + height + "@" + dpi + "dpi");
                    return;
                }
                try { compVd.release(); }
                catch (RuntimeException e) { System.err.println("OPALDESK_REMOTE_MIRROR_COMP_REL_WARN: " + e.getMessage()); }
            }
        }
        VirtualDisplay mirVd = VirtualDisplayFactory.createWithFlagFallback(
                coord.services.displayManager, "OpalDesk-RemoteMirror",
                width, height, dpi, REMOTE_MIRROR_VD_FLAGS, fps, codecSurf, chosenFlags);
        if (mirVd == null) { System.err.println("OPALDESK_REMOTE_MIRROR_CREATE_FAILED"); return; }
        this.mirrorVd = mirVd;
        syncDesktopConfig(coord.config, -1, null, "REMOTE_MIRROR");
        coord.helperBridge.enqueuePushBinder(true);
        coord.helperBridge.startHelperService();
        coord.helperBridge.notifyHelperSync();
        coord.pushDeviceState(this);
        if (sink != null) sink.requestKeyFrame();
        System.out.println("OPALDESK_REMOTE_MIRROR_ATTACHED flags=0x" + Integer.toHexString(chosenFlags[0])
                + " size=" + width + "x" + height + "@" + dpi + "dpi");
    }

    void resizeLocked(
            RemoteSessionCoordinator coord, int reqW, int reqH, int reqDpi, float reqFps,
            int reqBitrateKbps, int nextMode, int reqRotation, int maxBitrateKbps) {
        RemoteStreamSink s = sink;
        if (closed || s == null || !s.isActive()) return;
        DaemonConfig cfg = coord.config;

        int[] aligned = DisplayGeometryUtil.alignForEncoder(reqW > 0 ? reqW : width, reqH > 0 ? reqH : height, 8);
        int nextW = aligned[0], nextH = aligned[1];
        int nextDpi = (reqDpi >= DisplayGeometryUtil.MIN_DPI && reqDpi <= DisplayGeometryUtil.MAX_DPI)
                ? reqDpi : DisplayGeometryUtil.computeAutoDpi(nextW, nextH);
        float nextFps = (reqFps >= DisplayGeometryUtil.MIN_FPS) ? DisplayGeometryUtil.clampFps(reqFps, fps) : fps;
        int nextBr = (reqBitrateKbps > 0)
                ? DisplayGeometryUtil.clampBitrateKbps(reqBitrateKbps, 1500, maxBitrateKbps)
                : Math.min(requestedBitrateKbps, maxBitrateKbps);
        int nextRot = (reqRotation >= 0 && reqRotation <= 3) ? DisplayGeometryUtil.normalizeRotation(reqRotation) : rotation;
        requestedBitrateKbps = nextBr;

        boolean modeChanged = !standalone && (nextMode != s.getDisplayMode() || nextMode != cfg.mirrorMode);
        boolean geomChanged = nextW != s.getWidth() || nextH != s.getHeight() || nextDpi != s.getDpi()
                || Math.abs(nextFps - s.getFps()) > 0.5f || nextRot != s.getRotation();
        boolean codecMismatch = (codecPref == RemoteStreamSink.CODEC_PREF_H264 && s.getCodecId() != OpalCastProtocol.VIDEO_CODEC_H264)
                || (codecPref == RemoteStreamSink.CODEC_PREF_AUTO_HEVC && s.getCodecId() != OpalCastProtocol.VIDEO_CODEC_HEVC && cfg.forceRebuild);
        if (!modeChanged && !geomChanged && !codecMismatch && (standalone || !cfg.forceRebuild)) {
            s.updateBitrateKbps(nextBr);
            coord.pushDeviceState(this);
            return;
        }
        if (!standalone) cfg.forceRebuild = false;
        if (modeChanged) {
            if (coord.taskController.getWindowManager().isTilingActive()) coord.taskController.exitTilingAndRestore(false);
            coord.taskController.getWindowManager().clearAllManagedWindows();
        }
        displayMode = nextMode; width = nextW; height = nextH; dpi = nextDpi; fps = nextFps; rotation = nextRot;
        if (!standalone) {
            cfg.mirrorMode = nextMode; cfg.virtRot = nextRot;
            cfg.virtW = nextW; cfg.virtH = nextH; cfg.virtDpi = nextDpi; cfg.virtFps = nextFps;
        }

        if (!standalone && nextMode == OpalCastProtocol.DISPLAY_MODE_MIRROR) {
            releaseDexVd(cfg, coord.injector);
            if (!s.reconfigureAndSwap(nextW, nextH, nextDpi, nextFps, nextBr, codecPref,
                    OpalCastProtocol.DISPLAY_MODE_MIRROR, nextRot, (newSurf, aw, ah, aDpi) -> {
                        width = cfg.virtW = aw; height = cfg.virtH = ah; dpi = cfg.virtDpi = aDpi;
                        cfg.physW = aw; cfg.physH = ah; cfg.physFps = nextFps;
                        if (mirrorVd != null) {
                            if (mirrorCompDisplayId > 0 && coord.mirrorDisplay != null) {
                                releaseMirrorVd(coord.mirrorDisplay);
                            } else {
                                try { mirrorVd.resize(aw, ah, aDpi); mirrorVd.setSurface(newSurf); return; }
                                catch (RuntimeException e) { System.err.println("OPALDESK_REMOTE_MIRROR_SWAP_WARN: " + e.getMessage()); }
                            }
                        }
                        attachMirrorVdLocked(coord, newSurf);
                    })) {
                System.err.println("OPALDESK_REMOTE_MIRROR_RECONFIG_FAILED");
            }
            coord.pushDeviceState(this);
            return;
        }

        releaseMirrorVd(coord.mirrorDisplay);
        boolean aspectFlipped = vd != null && createdVdW > 0 && createdVdH > 0
                && DisplayGeometryUtil.isOrientationAspectFlipped(createdVdW, createdVdH, nextW, nextH);
        if (vd != null && !aspectFlipped && !modeChanged
                && s.reconfigureAndSwap(nextW, nextH, nextDpi, nextFps, nextBr, codecPref,
                        OpalCastProtocol.DISPLAY_MODE_DEX, nextRot, (newSurf, aw, ah, aDpi) -> {
                            width = aw; height = ah; dpi = aDpi;
                            if (!standalone) { cfg.virtW = aw; cfg.virtH = ah; cfg.virtDpi = aDpi; }
                            vd.resize(aw, ah, aDpi);
                            vd.setSurface(newSurf);
                        })) {
            if (!standalone) {
                coord.localInput.clampCursor(cfg.virtW, cfg.virtH);
                coord.taskController.scheduleRelayoutAfterResize();
            }
            coord.pushDeviceState(this);
            return;
        }

        ArrayList<Integer> prevTasks = (vdId > 0) ? coord.taskController.collectExtUserTaskIds(vdId) : new ArrayList<>();
        List<DesktopWindowManager.ManagedWindow> prevManaged = (!standalone && !modeChanged)
                ? coord.taskController.getWindowManager().getManagedWindowsSnapshot()
                : Collections.emptyList();
        if (!standalone && !prevManaged.isEmpty()) {
            coord.taskController.beginDisplayMigration();
        }
        Surface nextSurf = s.startOrReconfigure(
                nextW, nextH, nextDpi, nextFps, nextBr, codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, nextRot);
        if (nextSurf != null) {
            width = s.getWidth(); height = s.getHeight(); dpi = s.getDpi();
            if (attachDexVd(coord, nextSurf)) {
                for (int tid : prevTasks) {
                    coord.taskController.moveTaskToDisplayIfNeeded(tid, vdId);
                    if (standalone) coord.taskApi.detachFreeformAndEnsureFullscreen(tid, vdId, width, height, true, null);
                }
                if (standalone && prevTasks.isEmpty()) launchOrMoveStandaloneApp(coord);
                else if (!standalone) {
                    coord.taskController.finishDisplayMigrationAndRelayout(prevManaged);
                }
            } else if (!standalone) {
                coord.taskController.endDisplayMigration();
            }
        } else if (!standalone) {
            coord.taskController.endDisplayMigration();
        }
        coord.pushDeviceState(this);
    }

    void launchOrMoveStandaloneApp(RemoteSessionCoordinator coord) {
        if (closed || vdId <= 0) return;
        coord.taskController.getWindowManager().removeManagedWindowByPkg(appPackage);
        int runningTid = coord.taskController.findTaskIdForPkg(appPackage, false);
        if (runningTid > 0 && coord.taskController.isTaskAllowedForExtControl(runningTid, appPackage, false)
                && coord.taskController.moveTaskToDisplayIfNeeded(runningTid, vdId)) {
            coord.taskApi.detachFreeformAndEnsureFullscreen(runningTid, vdId, width, height, true, null);
            appTaskId = runningTid;
            ActivityOptions opts = TaskWindowController.makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null);
            if (coord.taskApi.startActivityFromRecents(runningTid, opts.toBundle())) {
                scheduleStandalonePostLaunchVerify(coord);
                return;
            }
        }
        Intent intent = coord.taskController.resolveSafeLaunchIntent(appPackage, appActivity);
        if (intent == null && !appActivity.isEmpty()) intent = coord.taskController.resolveSafeLaunchIntent(appPackage, null);
        if (intent == null) { System.err.println("OPALDESK_STANDALONE_NO_LAUNCH_INTENT pkg=" + appPackage); return; }
        ActivityOptions opts = TaskWindowController.makeLaunchOptions(vdId, BridgeProtocol.WINDOWING_MODE_FULLSCREEN, null);
        if (coord.services.startActivityAsShell(intent, opts.toBundle())) scheduleStandalonePostLaunchVerify(coord);
    }

    private void scheduleStandalonePostLaunchVerify(RemoteSessionCoordinator coord) {
        if (coord.mainHandler == null) return;
        Runnable verify = () -> {
            if (closed || vdId <= 0) return;
            int tid = coord.taskController.findTaskIdForPkg(appPackage, false);
            if (tid > 0) {
                appTaskId = tid;
                coord.taskController.moveTaskToDisplayIfNeeded(tid, vdId);
                int wm = coord.taskApi.getTaskWindowingMode(tid);
                if (wm != BridgeProtocol.WINDOWING_MODE_FULLSCREEN && wm != BridgeProtocol.WINDOWING_MODE_UNDEFINED) {
                    coord.taskApi.detachFreeformAndEnsureFullscreen(tid, vdId, width, height, true, coord.mainHandler);
                }
            }
            if (sink != null && sink.isActive()) sink.requestKeyFrame();
            coord.pushDeviceState(this);
            coord.pushDesktopDeviceState(true);
        };
        coord.mainHandler.postDelayed(verify, 260L);
        coord.mainHandler.postDelayed(verify, 780L);
    }

    void close(DaemonConfig config, InputInjector injector, NativeMirrorController mirrorDisplay) {
        synchronized (lock) {
            if (closed && sink == null && vd == null && mirrorVd == null) return;
            closed = true;
            if (clipboardSync != null) { clipboardSync.onSessionEnded(); clipboardSync = null; }
            if (fileTransferReceiver != null) { fileTransferReceiver.onSessionEnded(); fileTransferReceiver = null; }
            if (inputBridge != null) { inputBridge.onSessionEnded(); inputBridge = null; }
            if (sink != null) { sink.stop(); sink = null; }
            releaseDisplays(config, injector, mirrorDisplay);
        }
    }

    void releaseDisplays(DaemonConfig config, InputInjector injector, NativeMirrorController mirrorDisplay) {
        releaseDexVd(config, injector);
        releaseMirrorVd(mirrorDisplay);
    }

    void releaseDexVd(DaemonConfig config, InputInjector injector) {
        int prevVdId = vdId;
        if (prevVdId > 0) injector.releaseUhidForDisplay(prevVdId);
        VirtualDisplay curVd = vd;
        vd = null; vdId = -1; vdUniqueId = null; createdVdW = 0; createdVdH = 0;
        if (curVd != null) {
            try { curVd.release(); }
            catch (RuntimeException e) { System.err.println("OPALDESK_REMOTE_VD_RELEASE_WARN: " + e.getMessage()); }
        }
        if (!standalone && config.vdId == prevVdId) { config.vdId = -1; config.vdUniqueId = null; }
    }

    void releaseMirrorVd(NativeMirrorController mirrorDisplay) {
        int compId = mirrorCompDisplayId;
        mirrorCompDisplayId = -1;
        if (compId > 0 && mirrorDisplay != null) {
            mirrorDisplay.teardownForDisplay(compId);
        }
        VirtualDisplay curMir = mirrorVd;
        mirrorVd = null;
        if (curMir != null) {
            try { curMir.release(); }
            catch (RuntimeException e) { System.err.println("OPALDESK_REMOTE_MIRROR_RELEASE_WARN: " + e.getMessage()); }
        }
    }

    private void syncDesktopConfig(DaemonConfig cfg, int newVdId, String newUniqueId, String mode) {
        cfg.vdId = newVdId; cfg.vdUniqueId = newUniqueId; cfg.extId = -1;
        cfg.virtW = width; cfg.virtH = height; cfg.virtDpi = dpi; cfg.virtFps = fps;
        cfg.renderMode = mode;
    }

    OpalCastProtocol.DeviceState buildDeviceState(
            String deviceName, int activeSinkType, int mirrorMode, int virtRot,
            int batteryPct, boolean screenDimmed, boolean audioOn, int injectStatus,
            DesktopWindowManager wm, String topPkg, String topLabel) {
        RemoteStreamSink s = sink;
        int w = (s != null && s.getWidth() > 0) ? s.getWidth() : width;
        int h = (s != null && s.getHeight() > 0) ? s.getHeight() : height;
        int d = (s != null && s.getDpi() > 0) ? s.getDpi() : dpi;
        double actualFps = (s != null && s.getActualFpsX100() > 0)
                ? (s.getActualFpsX100() / 100.0) : ((s != null && s.getFps() > 0f) ? s.getFps() : fps);
        int bitrate = (s != null && s.isActive()) ? s.getBitrateKbps() : requestedBitrateKbps;
        String layoutMode;
        List<String> tilingSlots;
        List<Integer> tilingRatios;
        if (standalone) {
            layoutMode = DesktopWindowManager.MODE_STANDALONE_APP;
            tilingSlots = Collections.emptyList();
            tilingRatios = Collections.emptyList();
        } else {
            DesktopWindowManager.TilingSnapshot tiling = wm.getTilingSnapshot();
            layoutMode = tiling.active ? tiling.topology : wm.resolveActiveLayoutMode();
            tilingSlots = tiling.active ? tiling.slots : Collections.emptyList();
            tilingRatios = tiling.active ? tiling.ratios : Collections.emptyList();
        }
        return new OpalCastProtocol.DeviceState(
                deviceName, standalone ? sinkType : activeSinkType,
                standalone ? OpalCastProtocol.DISPLAY_MODE_DEX : mirrorMode,
                w, h, d, actualFps, standalone ? rotation : virtRot,
                bitrate, batteryPct, screenDimmed, audioOn, injectStatus,
                wm.isAdaptiveSingleEnabled(), wm.getDefaultSingleWidthDp(),
                layoutMode, topPkg, topLabel, tilingSlots, tilingRatios);
    }

    private static final class SessionHostBridge implements RemoteInputBridge.HostBridge {
        private final RemoteDisplaySession session;
        private final RemoteSessionCoordinator coord;

        SessionHostBridge(RemoteDisplaySession session, RemoteSessionCoordinator coord) {
            this.session = session;
            this.coord = coord;
        }

        @Override public int getTargetDisplayId() {
            if (session.closed) return -1;
            return session.standalone ? session.vdId
                    : ((coord.config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) ? 0 : coord.config.vdId);
        }
        @Override public int resolveTargetDisplayId(float streamX, float streamY) {
            if (session.closed) return -1;
            if (isMirrorMode() && session.mirrorCompDisplayId > 0
                    && coord.mirrorDisplay != null && coord.mirrorDisplay.hasActiveRemoteComposition()) {
                return coord.mirrorDisplay.resolveMirrorTargetDisplay(streamX, streamY);
            }
            return getTargetDisplayId();
        }
        @Override public float[] mapStreamPoint(int targetDisplayId, float streamX, float streamY) {
            if (isMirrorMode() && session.mirrorCompDisplayId > 0
                    && coord.mirrorDisplay != null && coord.mirrorDisplay.hasActiveRemoteComposition()) {
                return new float[]{
                        coord.mirrorDisplay.mapMirrorX(targetDisplayId, streamX),
                        coord.mirrorDisplay.mapMirrorY(targetDisplayId, streamY)
                };
            }
            return null;
        }
        @Override public void onPointerDown(int targetDisplayId) {
            if (!session.standalone && !isMirrorMode() && targetDisplayId > 0) {
                coord.taskController.scheduleFastFocusSync();
            }
        }
        @Override public void onPointerRelease(int targetDisplayId) {
            if (isMirrorMode() && coord.mirrorDisplay != null && targetDisplayId > 0
                    && targetDisplayId == coord.mirrorDisplay.getSideVdId()) {
                coord.mirrorDisplay.scheduleMirrorSideClickPostChecks();
            } else if (!session.standalone && !isMirrorMode() && targetDisplayId > 0) {
                coord.taskController.scheduleFastFocusSync();
            }
        }
        @Override public boolean onDesktopShortcut(String shortcutAction) {
            if (session.standalone || isMirrorMode()) return false;
            return coord.taskController.handleDesktopShortcut(shortcutAction);
        }
        @Override public boolean isMirrorMode() { return !session.standalone && coord.config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR; }
        @Override public int getStreamWidth() { RemoteStreamSink s = session.sink; return (s != null && s.getWidth() > 0) ? s.getWidth() : session.width; }
        @Override public int getStreamHeight() { RemoteStreamSink s = session.sink; return (s != null && s.getHeight() > 0) ? s.getHeight() : session.height; }
        @Override public int getTargetDisplayWidth() {
            if (isMirrorMode()) { Point p = coord.services.readDisplayRealSize(0); return (p.x > 0) ? p.x : coord.config.virtW; }
            return session.standalone ? session.width : coord.config.virtW;
        }
        @Override public int getTargetDisplayHeight() {
            if (isMirrorMode()) { Point p = coord.services.readDisplayRealSize(0); return (p.y > 0) ? p.y : coord.config.virtH; }
            return session.standalone ? session.height : coord.config.virtH;
        }
        @Override public boolean injectInputEvent(InputEvent ev, int targetDisplayId, int mode) {
            return session.standalone
                    ? coord.injector.injectInputForDisplay(ev, targetDisplayId, mode, session.vdUniqueId, session.width, session.height)
                    : coord.injector.injectInputMode(ev, targetDisplayId, mode);
        }
        @Override public void focusDisplay(int displayId) { coord.injector.focusDisplayForKey(displayId); }
        @Override public void updateCursorPosition(float x, float y) { if (!session.standalone) coord.localInput.setCursorPosition(x, y); }
        @Override public void performHome() {
            if (session.standalone) {
                if (!session.closed && session.vdId > 0) {
                    long now = SystemClock.uptimeMillis();
                    injectInputEvent(InputInjector.createSystemKeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0), session.vdId, 2);
                    injectInputEvent(InputInjector.createSystemKeyEvent(now, now + 5L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0), session.vdId, 2);
                }
            } else if (coord.config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) {
                coord.localInput.injectKey(KeyEvent.KEYCODE_HOME, 0);
            } else {
                coord.taskController.minimizeToExtHome(-1, null);
            }
        }
        @Override public void toggleDisplayMode(int arg) {
            if (session.standalone) return;
            int next = (arg == 0 || arg == 1) ? arg : (coord.config.mirrorMode == 0 ? 1 : 0);
            coord.applyDesktopResize(coord.config.virtW, coord.config.virtH, coord.config.virtDpi,
                    coord.config.virtFps, session.requestedBitrateKbps, next, coord.config.virtRot);
        }
        @Override public void toggleBlackout(int arg) { coord.systemControl.setMainScreenDim(arg < 0 ? -1 : (arg != 0 ? 1 : 0)); }
        @Override public void rotateDisplay(int arg) { if (!session.standalone) coord.physicalDisplay.setRotation(arg); }
        @Override public void setAudioEnabled(boolean enabled) { if (!session.standalone) coord.setAudioPrefAndApply(enabled); }
        @Override public void requestDisconnect() {
            RemoteStreamServer srv = coord.getServer();
            if (srv != null && session.sessionId > 0) {
                srv.disconnectSession(session.sessionId,
                        session.standalone ? "Standalone client requested disconnect" : "Client requested disconnect");
            }
        }
    }
}
