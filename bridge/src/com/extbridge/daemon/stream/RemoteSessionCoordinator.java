package com.extbridge.daemon.stream;

import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.DaemonConfig.PackagePolicy;
import com.extbridge.daemon.DisplayGeometryUtil;
import com.extbridge.daemon.audio.AudioStreamCapturer;
import com.extbridge.daemon.display.CompatPolicyController;
import com.extbridge.daemon.display.NativeMirrorController;
import com.extbridge.daemon.display.PhysicalDisplayController;
import com.extbridge.daemon.display.SystemControlService;
import com.extbridge.daemon.input.InputInjector;
import com.extbridge.daemon.input.LocalInputController;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.security.TrustStoreManager;
import com.extbridge.daemon.sink.DisplaySink;
import com.extbridge.daemon.sink.RemoteStreamSink;
import com.extbridge.daemon.window.DesktopWindowManager;
import com.extbridge.daemon.window.TaskWindowController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates dual-path ExtCast v1.1 remote streaming (ADB USB/Wi-Fi + on-demand Wi-Fi TLS)
 * across one desktop session and up to 3 concurrent standalone pop-out app sessions.
 */
public final class RemoteSessionCoordinator {
    private static final long REMOTE_STATE_PUSH_INTERVAL_MS = 4500L;
    private static final long WINDOW_ACTION_STATE_SETTLE_MS = 260L;

    /** Consistent snapshot of remote stream state for Binder code 15. */
    public static final class RemoteStatusSnapshot {
        public final int activeSinkType;
        public final String peerName;
        public final int bitrateKbps;
        public final int actualFpsX100;
        public final boolean wifiEnabled;
        public final int wifiPort;
        public final String certFingerprint;
        public final String activePin;

        RemoteStatusSnapshot(
                int activeSinkType, String peerName, int bitrateKbps, int actualFpsX100,
                boolean wifiEnabled, int wifiPort, String certFingerprint, String activePin) {
            this.activeSinkType = activeSinkType;
            this.peerName = peerName;
            this.bitrateKbps = bitrateKbps;
            this.actualFpsX100 = actualFpsX100;
            this.wifiEnabled = wifiEnabled;
            this.wifiPort = wifiPort;
            this.certFingerprint = certFingerprint;
            this.activePin = activePin;
        }
    }

    final AndroidServices services;
    final DaemonConfig config;
    final CompatPolicyController compatPolicy;
    final SystemControlService systemControl;
    final HelperClientBridge helperBridge;
    final InputInjector injector;
    final LocalInputController localInput;
    final PhysicalDisplayController physicalDisplay;
    final NativeMirrorController mirrorDisplay;
    final TaskWindowController taskController;
    final TaskApiAdapter taskApi;
    final Handler mainHandler;
    final ExecutorService bgIoExec;

    private final ConcurrentHashMap<Integer, RemoteDisplaySession> standaloneSessions = new ConcurrentHashMap<>();
    private final AtomicLong lastDesktopStatePushMs = new AtomicLong(0L);

    private volatile TrustStoreManager trustStore;
    private volatile RemoteStreamServer server;
    private volatile RemoteDisplaySession desktopSession;
    private volatile AudioStreamCapturer audioCapturer;

    private volatile int remoteCodecPref = RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
    private volatile int remoteMaxBitrateKbps = 20_000;
    private volatile boolean remoteAudioPref = true;

    private volatile Runnable teardownPhysicalForRemote;
    private volatile Runnable cleanupAfterRemoteDisconnect;
    private volatile Runnable tickTrigger;

    public RemoteSessionCoordinator(
            AndroidServices services, DaemonConfig config, CompatPolicyController compatPolicy,
            SystemControlService systemControl, HelperClientBridge helperBridge,
            InputInjector injector, LocalInputController localInput,
            PhysicalDisplayController physicalDisplay, NativeMirrorController mirrorDisplay,
            TaskWindowController taskController,
            TaskApiAdapter taskApi, Handler mainHandler, ExecutorService bgIoExec) {
        this.services = services;
        this.config = config;
        this.compatPolicy = compatPolicy;
        this.systemControl = systemControl;
        this.helperBridge = helperBridge;
        this.injector = injector;
        this.localInput = localInput;
        this.physicalDisplay = physicalDisplay;
        this.mirrorDisplay = mirrorDisplay;
        this.taskController = taskController;
        this.taskApi = taskApi;
        this.mainHandler = mainHandler;
        this.bgIoExec = bgIoExec;
    }

    public void wireCallbacks(
            Runnable teardownPhysicalForRemote, Runnable cleanupAfterRemoteDisconnect, Runnable tickTrigger) {
        this.teardownPhysicalForRemote = teardownPhysicalForRemote;
        this.cleanupAfterRemoteDisconnect = cleanupAfterRemoteDisconnect;
        this.tickTrigger = tickTrigger;
    }

    RemoteStreamServer getServer() { return server; }

    public void start() {
        try {
            trustStore = new TrustStoreManager();
            server = new RemoteStreamServer(new SessionListenerImpl(), trustStore);
            server.startUsbListener();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_REMOTE_INIT_ERR: " + e.getMessage());
        }
    }

    public void shutdown() {
        for (RemoteDisplaySession sas : new ArrayList<>(standaloneSessions.values())) sas.close(config, injector, mirrorDisplay);
        standaloneSessions.clear();
        RemoteDisplaySession desk;
        synchronized (config.displayLock) {
            desk = desktopSession;
            desktopSession = null;
        }
        stopAudioQuietly();
        if (desk != null) desk.close(config, injector, mirrorDisplay);
        injector.releaseAllUhidDevices();
        RemoteStreamServer srv = server;
        if (srv != null) srv.shutdown();
    }

    // ---- Queries & Binder Code 45..49 Controls ----

    public boolean isDesktopSessionActive() {
        RemoteStreamServer srv = server;
        RemoteDisplaySession desk = desktopSession;
        return srv != null && srv.hasDesktopSession()
                && desk != null && !desk.closed && desk.sink != null && desk.sink.isActive();
    }

    public int getActiveSinkType(boolean hasPhysicalDisplay) {
        RemoteStreamServer srv = server;
        if (isDesktopSessionActive() && srv != null) return srv.getActiveSinkType();
        if (srv != null && srv.hasActiveSession()) return srv.getActiveSinkType();
        if (config.extId >= 0 || hasPhysicalDisplay) return DisplaySink.SINK_PHYSICAL;
        return config.activeSinkType;
    }

    public RemoteStatusSnapshot snapshotStatus(boolean hasPhysicalDisplay) {
        RemoteStreamServer srv = server;
        RemoteDisplaySession desk = desktopSession;
        RemoteStreamSink sink = (desk != null && !desk.closed) ? desk.sink : null;
        return new RemoteStatusSnapshot(
                getActiveSinkType(hasPhysicalDisplay),
                srv != null ? srv.getActivePeerName() : "",
                (sink != null && sink.isActive()) ? sink.getBitrateKbps() : 0,
                (sink != null && sink.isActive()) ? sink.getActualFpsX100() : 0,
                srv != null && srv.isWifiEnabled(),
                srv != null ? srv.getWifiPort() : RemoteStreamServer.DEFAULT_WIFI_PORT,
                srv != null ? srv.getCertFingerprint() : "",
                srv != null ? srv.getActivePairingPin() : "");
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port, boolean regeneratePin) {
        RemoteStreamServer srv = server;
        RemoteStreamServer.WifiConfigStatus st = (srv != null)
                ? srv.configureWifi(enable, port, regeneratePin)
                : new RemoteStreamServer.WifiConfigStatus(false, RemoteStreamServer.DEFAULT_WIFI_PORT, "", "");
        helperBridge.notifyHelperSync();
        return st;
    }

    public String listTrustedClientsSummary() {
        TrustStoreManager ts = trustStore;
        return (ts != null) ? ts.listTrustedClientsSummary() : "";
    }

    public boolean revokeTrustedClient(String clientId) {
        RemoteStreamServer srv = server;
        boolean ok = (srv != null) && srv.revokeTrustedClient(clientId);
        helperBridge.notifyHelperSync();
        return ok;
    }

    public void disconnectActiveClient(String reason) {
        RemoteStreamServer srv = server;
        if (srv != null) srv.disconnectActiveClient(reason);
    }

    public void applyRemoteStreamPrefs(int codecPref, int maxBitrateKbps, boolean audioEnabled) {
        int nextCodec = (codecPref == RemoteStreamSink.CODEC_PREF_H264)
                ? RemoteStreamSink.CODEC_PREF_H264 : RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
        int nextMaxBr = DisplayGeometryUtil.clampBitrateKbps(maxBitrateKbps, 2000, 40_000);
        boolean codecChanged = (nextCodec != remoteCodecPref);
        remoteCodecPref = nextCodec;
        remoteMaxBitrateKbps = nextMaxBr;
        remoteAudioPref = audioEnabled;

        RemoteDisplaySession desk = desktopSession;
        if (desk != null && !desk.closed && desk.sink != null && desk.sink.isActive()) {
            desk.codecPref = nextCodec;
            desk.sink.setMaxBitrateKbps(nextMaxBr);
            if (codecChanged) {
                config.forceRebuild = true;
                applyDesktopResize(config.virtW, config.virtH, config.virtDpi, config.virtFps,
                        Math.min(desk.requestedBitrateKbps, nextMaxBr), config.mirrorMode, config.virtRot);
            }
            setRemoteAudioActive(audioEnabled);
            pushDesktopDeviceState(true);
        }
        helperBridge.notifyHelperSync();
    }

    void setAudioPrefAndApply(boolean enabled) {
        remoteAudioPref = enabled;
        setRemoteAudioActive(enabled);
    }

    // ---- Tick & Display Reconciliation (called under config.displayLock) ----

    public void tickRemoteLocked() {
        RemoteDisplaySession desk = desktopSession;
        RemoteStreamServer srv = server;
        if (desk == null || desk.closed || srv == null) return;
        config.activeSinkType = srv.getActiveSinkType();
        if (teardownPhysicalForRemote != null) teardownPhysicalForRemote.run();

        if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) {
            if (desk.vd != null || config.forceRebuild) {
                config.forceRebuild = false;
                desk.releaseDisplays(config, injector, mirrorDisplay);
            }
            if (desk.mirrorVd == null && desk.sink != null) {
                desk.attachMirrorVdLocked(this, desk.sink.getInputSurface());
            } else if (desk.mirrorCompDisplayId > 0 && mirrorDisplay != null) {
                if (mirrorDisplay.needsAttachOrRebuildForTarget(desk.mirrorCompDisplayId, true) && desk.sink != null) {
                    desk.attachMirrorVdLocked(this, desk.sink.getInputSurface());
                } else {
                    mirrorDisplay.updateMirrorGeometry(true);
                    mirrorDisplay.reassert();
                    mirrorDisplay.scheduleStrayTaskRescueIfActive();
                }
            }
        } else {
            desk.releaseMirrorVd(mirrorDisplay);
            if (config.forceRebuild && desk.vd != null) {
                config.forceRebuild = false;
                desk.releaseDisplays(config, injector, mirrorDisplay);
            }
            if (desk.vd == null && desk.sink != null) desk.attachDexVd(this, desk.sink.getInputSurface());
            else taskController.enforceRunningExtTasksFullscreen(config.fullscreenPolicy == 1);
        }
        pushDesktopDeviceState(false);
    }

    public void requestDesktopKeyFrame() {
        RemoteDisplaySession desk = desktopSession;
        if (desk != null && !desk.closed && desk.sink != null && desk.sink.isActive()) {
            desk.sink.requestKeyFrame();
        }
    }

    public void applyDesktopResizeFromConfig() {
        RemoteDisplaySession desk = desktopSession;
        int br = (desk != null) ? desk.requestedBitrateKbps : 12_000;
        applyDesktopResize(config.virtW, config.virtH, config.virtDpi, config.virtFps, br, config.mirrorMode, config.virtRot);
    }

    public void applyDesktopResize(
            int reqW, int reqH, int reqDpi, float reqFps, int reqBitrateKbps, int reqDisplayMode, int reqRotation) {
        synchronized (config.displayLock) {
            RemoteDisplaySession desk = desktopSession;
            if (!isDesktopSessionActive() || desk == null) return;
            int nextMode = (reqDisplayMode == ExtCastProtocol.DISPLAY_MODE_DEX || reqDisplayMode == ExtCastProtocol.DISPLAY_MODE_MIRROR)
                    ? reqDisplayMode : config.mirrorMode;
            desk.resizeLocked(this, reqW, reqH, reqDpi, reqFps, reqBitrateKbps, nextMode, reqRotation, remoteMaxBitrateKbps);
            helperBridge.notifyHelperSync();
        }
    }

    // ---- Standalone Input Hooks & Device State Pushes ----

    public String resolveStandaloneUniqueId(int displayId) {
        for (RemoteDisplaySession sas : standaloneSessions.values()) {
            if (sas != null && !sas.closed && sas.vdId == displayId && sas.vdUniqueId != null) return sas.vdUniqueId;
        }
        return null;
    }

    public int[] resolveStandaloneDims(int displayId) {
        for (RemoteDisplaySession sas : standaloneSessions.values()) {
            if (sas != null && !sas.closed && sas.vdId == displayId) return new int[]{Math.max(1, sas.width), Math.max(1, sas.height)};
        }
        return null;
    }

    public boolean openUhidForStandaloneDisplays() {
        boolean anyOk = false;
        for (RemoteDisplaySession sas : standaloneSessions.values()) {
            if (sas != null && !sas.closed && sas.vdId > 0) anyOk |= (injector.ensureUhidForDisplay(sas.vdId, sas.vdUniqueId) != null);
        }
        return anyOk;
    }

    public void pushDesktopDeviceState(boolean force) {
        RemoteDisplaySession desk = desktopSession;
        RemoteStreamServer srv = server;
        if (srv == null || !srv.hasDesktopSession() || desk == null || desk.closed) return;
        long now = SystemClock.uptimeMillis();
        if (!force && (now - lastDesktopStatePushMs.get()) < REMOTE_STATE_PUSH_INTERVAL_MS) return;
        lastDesktopStatePushMs.set(now);
        pushDeviceState(desk);
    }

    public void pushAllDeviceStates() {
        pushDesktopDeviceState(true);
        for (RemoteDisplaySession sas : standaloneSessions.values()) {
            if (sas != null && !sas.closed) pushDeviceState(sas);
        }
    }

    void pushDeviceState(RemoteDisplaySession session) {
        RemoteStreamServer srv = server;
        if (srv == null || session == null || session.closed) return;
        String topPkg, topLabel;
        if (session.standalone) {
            topPkg = session.appPackage;
            topLabel = taskController.resolveAppLabel(session.appPackage);
        } else {
            String[] topApp = (config.mirrorMode == BridgeProtocol.MIRROR_MODE_DEX && config.vdId > 0)
                    ? taskController.findTopUserAppOnDisplay(config.vdId) : new String[]{"", ""};
            topPkg = topApp[0]; topLabel = topApp[1];
        }
        ExtCastProtocol.DeviceState state = session.buildDeviceState(
                srv.getDeviceName(), srv.getActiveSinkType(), config.mirrorMode, config.virtRot,
                systemControl.readBatteryPct(), systemControl.isScreenDimmed(),
                !session.standalone && audioCapturer != null && audioCapturer.isActive(),
                injector.getLastInjectStatus(), taskController.getWindowManager(), topPkg, topLabel);
        if (session.standalone) srv.sendDeviceState(session.sessionId, state);
        else srv.sendDeviceState(state);
    }

    // ---- Internal Helpers ----

    private RemoteDisplaySession createAndStartSession(
            int sessionId, int sinkType, boolean standalone, ExtCastProtocol.HelloMsg hello, Object lock) {
        RemoteDisplaySession session = new RemoteDisplaySession(
                sessionId, sinkType, standalone, hello.appPackage, hello.appActivity, lock);
        Surface surf = session.startEncoder(
                hello, config.virtRot, remoteMaxBitrateKbps,
                resolveEffectiveCodecPref(hello.preferredCodec), createFrameListener(sessionId, standalone));
        if (surf == null) return null;
        session.wirePeripherals(this);
        return session;
    }

    private RemoteStreamSink.FrameListener createFrameListener(int sessionId, boolean standalone) {
        final String tag = standalone ? "EXTBRIDGE_STANDALONE_ENCODER_ERR" : "EXTBRIDGE_REMOTE_ENCODER_ERR";
        return new RemoteStreamSink.FrameListener() {
            @Override public void onVideoConfig(ExtCastProtocol.VideoConfig vc, long tsUs) {
                RemoteStreamServer srv = server; if (srv != null) srv.sendVideoConfig(sessionId, vc, tsUs);
            }
            @Override public void onVideoFrame(byte[] annexBData, int flags, long tsUs) {
                RemoteStreamServer srv = server; if (srv != null) srv.sendVideoFrame(sessionId, annexBData, flags, tsUs);
            }
            @Override public void onEncoderError(String reason) {
                System.err.println(tag + " sid=" + sessionId + ": " + reason);
                RemoteStreamServer srv = server; if (srv != null) srv.disconnectSession(sessionId, reason);
            }
        };
    }

    private void setRemoteAudioActive(boolean enable) {
        if (!isDesktopSessionActive()) { stopAudioQuietly(); return; }
        if (enable) {
            AudioStreamCapturer cap = audioCapturer;
            if (cap == null) {
                cap = new AudioStreamCapturer(services.context, new AudioStreamCapturer.AudioListener() {
                    @Override public void onAudioConfig(ExtCastProtocol.AudioConfig ac, long tsUs) {
                        RemoteStreamServer srv = server; if (srv != null) srv.sendAudioConfig(ac, tsUs);
                    }
                    @Override public void onAudioFrame(byte[] aacAu, long tsUs) {
                        RemoteStreamServer srv = server; if (srv != null) srv.sendAudioFrame(aacAu, tsUs);
                    }
                    @Override public void onAudioStateChanged(boolean active) { pushDesktopDeviceState(true); }
                });
                audioCapturer = cap;
            }
            cap.start();
        } else {
            if (audioCapturer != null) audioCapturer.stop();
            pushDesktopDeviceState(true);
        }
    }

    private void stopAudioQuietly() {
        AudioStreamCapturer cap = audioCapturer;
        audioCapturer = null;
        if (cap != null) cap.stop();
    }

    private int resolveEffectiveCodecPref(String helloPreferredCodec) {
        if (remoteCodecPref == RemoteStreamSink.CODEC_PREF_H264) return RemoteStreamSink.CODEC_PREF_H264;
        if (helloPreferredCodec != null) {
            String lower = helloPreferredCodec.trim().toLowerCase(Locale.US);
            if ("h264".equals(lower) || "avc".equals(lower)) return RemoteStreamSink.CODEC_PREF_H264;
        }
        return RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
    }

    private void pushDesktopStateNowAndSoon() {
        pushDesktopDeviceState(true);
        if (mainHandler != null) {
            mainHandler.postDelayed(() -> pushDesktopDeviceState(true), WINDOW_ACTION_STATE_SETTLE_MS);
        }
    }

    private void handleWindowAction(int sessionId, ExtCastProtocol.WindowActionMsg msg) {
        if (msg == null || msg.action.isEmpty()) return;
        RemoteDisplaySession sas = standaloneSessions.get(sessionId);
        DesktopWindowManager wm = taskController.getWindowManager();
        switch (msg.action.toLowerCase(Locale.US)) {
            case "set_adaptive_single": {
                boolean enabled = msg.hasEnabled ? msg.enabled : wm.isAdaptiveSingleEnabled();
                int widthDp = msg.defaultWidthDp > 0 ? msg.defaultWidthDp : wm.getDefaultSingleWidthDp();
                taskController.setAdaptiveSingleConfig(enabled, widthDp);
                pushDesktopStateNowAndSoon();
                break;
            }
            case "set_single_width":
                taskController.setSingleWidthPct(msg.pkg, msg.widthPct, msg.remember);
                pushDesktopStateNowAndSoon();
                break;
            case "launch_app":
                if ("split".equalsIgnoreCase(msg.mode)) {
                    if (PackagePolicy.isValidPackageName(msg.pkg)) {
                        taskController.handleSplitAction(msg.slot == 1 ? BridgeProtocol.SPLIT_ASSIGN_RIGHT_BOTTOM : BridgeProtocol.SPLIT_ASSIGN_LEFT_TOP, -1, msg.pkg, msg.cls);
                    }
                } else {
                    taskController.launchInWindowMode(msg.pkg, msg.cls, DesktopWindowManager.parseModeString(msg.mode),
                            msg.hasBounds ? msg.left : 0, msg.hasBounds ? msg.top : 0, msg.hasBounds ? msg.right : 0, msg.hasBounds ? msg.bottom : 0);
                }
                pushDesktopStateNowAndSoon();
                break;
            case "update_window": {
                int m = DesktopWindowManager.parseModeString(msg.mode);
                if (m == DesktopWindowManager.INT_MODE_AUTO) m = DesktopWindowManager.INT_MODE_FREEFORM;
                taskController.updateWindowBounds(msg.taskId, msg.pkg, m, msg.left, msg.top, msg.right, msg.bottom, msg.remember);
                pushDesktopStateNowAndSoon();
                break;
            }
            case "focus_window": {
                int tid = (msg.taskId > 0) ? msg.taskId : taskController.findTaskIdForPkg(msg.pkg, false);
                if (tid > 0) {
                    taskController.windowLifecycleAction(tid, msg.pkg, BridgeProtocol.WIN_ACTION_FOCUS);
                } else if (PackagePolicy.isValidPackageName(msg.pkg)) {
                    taskController.switchToTaskOnVd(-1, msg.pkg);
                }
                pushDesktopStateNowAndSoon();
                break;
            }
            case "minimize_window": {
                int tid = (msg.taskId > 0) ? msg.taskId : taskController.findTaskIdForPkg(msg.pkg, false);
                if (tid > 0) {
                    wm.setMinimizedTask(tid, true);
                }
                taskController.minimizeToExtHome(tid, msg.pkg);
                pushDesktopStateNowAndSoon();
                break;
            }
            case "close_window": {
                int tid = (msg.taskId > 0) ? msg.taskId : taskController.findTaskIdForPkg(msg.pkg, false);
                if (tid <= 0 && PackagePolicy.isValidPackageName(msg.pkg)) {
                    tid = taskController.findTaskIdForPkg(msg.pkg, true);
                }
                taskController.windowLifecycleAction(tid, msg.pkg, BridgeProtocol.WIN_ACTION_CLOSE);
                pushDesktopStateNowAndSoon();
                break;
            }
            case "set_tiling": {
                int[] ratios = new int[msg.ratios.size()];
                for (int i = 0; i < msg.ratios.size(); i++) ratios[i] = msg.ratios.get(i);
                taskController.setTilingLayout(msg.topology, msg.pkgs, ratios);
                pushDesktopStateNowAndSoon();
                break;
            }
            case "reload_app_dpi":
                if (sas != null && !sas.closed) {
                    String pkg = !msg.pkg.isEmpty() ? msg.pkg : sas.appPackage;
                    taskController.reloadAppDpiWithCallback(pkg, () -> { if (!sas.closed && sas.vdId > 0) sas.launchOrMoveStandaloneApp(this); });
                } else {
                    String pkg = !msg.pkg.isEmpty() ? msg.pkg : (config.vdId > 0 ? taskController.findTopUserAppOnDisplay(config.vdId)[0] : "");
                    if (!pkg.isEmpty()) taskController.reloadAppDpiOnDesktop(pkg);
                }
                break;
            case "return_to_desktop": {
                String pkg = PackagePolicy.isValidPackageName(msg.pkg) ? msg.pkg : (sas != null ? sas.appPackage : "");
                int tid = (msg.taskId > 0) ? msg.taskId : (sas != null ? sas.appTaskId : -1);
                taskController.returnTaskToDesktop(pkg, tid);
                break;
            }
            case "set_input_backend": {
                String b = msg.backend.toLowerCase(Locale.US);
                int mode = ("inject".equals(b) || "inject_events".equals(b)) ? BridgeProtocol.INPUT_BACKEND_INJECT
                        : ("uhid".equals(b) ? BridgeProtocol.INPUT_BACKEND_UHID : BridgeProtocol.INPUT_BACKEND_AUTO);
                injector.setInputBackendMode(mode);
                break;
            }
            default: break;
        }
    }

    private RemoteDisplaySession resolveSession(int sessionId) {
        RemoteDisplaySession sas = standaloneSessions.get(sessionId);
        if (sas != null) return sas;
        RemoteDisplaySession desk = desktopSession;
        return (desk != null && desk.sessionId == sessionId) ? desk : null;
    }

    private final class SessionListenerImpl implements RemoteStreamServer.SessionListener {
        @Override
        public void onSessionConnected(int sessionId, int sinkType, String clientId, String clientName, ExtCastProtocol.HelloMsg hello) {
            if (hello != null && hello.isStandaloneApp()) { connectStandalone(sessionId, sinkType, clientName, hello); return; }
            System.out.println("EXTBRIDGE_REMOTE_CONNECTED sid=" + sessionId + " sink=" + sinkType
                    + " peer=" + clientName + " req=" + hello.width + "x" + hello.height + "@" + hello.dpi + "dpi");
            RemoteDisplaySession nextDesk;
            synchronized (config.displayLock) {
                if (config.shuttingDown) return;
                RemoteDisplaySession prevDesk = desktopSession;
                desktopSession = null;
                if (teardownPhysicalForRemote != null) teardownPhysicalForRemote.run();
                if (audioCapturer != null) audioCapturer.stop();
                if (prevDesk != null) prevDesk.close(config, injector, mirrorDisplay);

                config.activeSinkType = sinkType;
                config.mirrorMode = (hello.displayMode == ExtCastProtocol.DISPLAY_MODE_MIRROR)
                        ? BridgeProtocol.MIRROR_MODE_MIRROR : BridgeProtocol.MIRROR_MODE_DEX;
                nextDesk = createAndStartSession(sessionId, sinkType, false, hello, config.displayLock);
                if (nextDesk == null) {
                    if (server != null) server.disconnectSession(sessionId, "Video encoder unavailable");
                    return;
                }
                desktopSession = nextDesk;
                if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) nextDesk.attachMirrorVdLocked(RemoteSessionCoordinator.this, nextDesk.sink.getInputSurface());
                else nextDesk.attachDexVd(RemoteSessionCoordinator.this, nextDesk.sink.getInputSurface());
            }
            nextDesk.startPeripherals();
            if (remoteAudioPref && hello.audioEnabled) setRemoteAudioActive(true);
            pushDesktopDeviceState(true);
            helperBridge.notifyHelperSync();
        }

        private void connectStandalone(int sessionId, int sinkType, String clientName, ExtCastProtocol.HelloMsg hello) {
            String pkg = hello.appPackage;
            if (!PackagePolicy.isValidPackageName(pkg) || PackagePolicy.isProtectedSystemTaskPkg(pkg)
                    || BridgeBuildConfig.HELPER_PACKAGE.equals(pkg)) {
                if (server != null) server.disconnectSession(sessionId, "Invalid or protected appPackage: " + pkg);
                return;
            }
            System.out.println("EXTBRIDGE_STANDALONE_CONNECTED sid=" + sessionId + " sink=" + sinkType
                    + " peer=" + clientName + " pkg=" + pkg + " req=" + hello.width + "x" + hello.height);
            RemoteDisplaySession sas = createAndStartSession(sessionId, sinkType, true, hello, new Object());
            if (sas == null) { if (server != null) server.disconnectSession(sessionId, "Standalone video encoder unavailable"); return; }
            if (!sas.attachDexVd(RemoteSessionCoordinator.this, sas.sink.getInputSurface())) {
                sas.close(config, injector, mirrorDisplay);
                if (server != null) server.disconnectSession(sessionId, "Standalone VirtualDisplay failed");
                return;
            }
            sas.startPeripherals();
            standaloneSessions.put(sessionId, sas);
            sas.launchOrMoveStandaloneApp(RemoteSessionCoordinator.this);
            pushDeviceState(sas);
            pushDesktopDeviceState(true);
            helperBridge.notifyHelperSync();
        }

        @Override
        public void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason) {
            if (ExtCastProtocol.SESSION_ROLE_STANDALONE_APP.equalsIgnoreCase(sessionRole)) {
                System.out.println("EXTBRIDGE_STANDALONE_DISCONNECTED sid=" + sessionId + " reason=" + reason);
                RemoteDisplaySession removed = standaloneSessions.remove(sessionId);
                if (removed != null) removed.close(config, injector, mirrorDisplay);
                helperBridge.notifyHelperSync();
                return;
            }
            System.out.println("EXTBRIDGE_REMOTE_DISCONNECTED sid=" + sessionId + " sink=" + sinkType + " reason=" + reason);
            // Atomic check-and-clear under displayLock eliminates disconnect/reconnect races.
            RemoteDisplaySession closing;
            synchronized (config.displayLock) {
                if (desktopSession == null || desktopSession.sessionId != sessionId) return;
                closing = desktopSession;
                desktopSession = null;
                closing.closed = true;
            }
            if (audioCapturer != null) audioCapturer.stop();
            closing.close(config, injector, mirrorDisplay);
            synchronized (config.displayLock) {
                if (desktopSession == null) {
                    if (cleanupAfterRemoteDisconnect != null) cleanupAfterRemoteDisconnect.run();
                    config.renderMode = "NONE";
                    config.activeSinkType = DisplaySink.SINK_NONE;
                }
            }
            helperBridge.notifyHelperSync();
            if (!config.shuttingDown && mainHandler != null && tickTrigger != null) mainHandler.post(tickTrigger);
        }

        @Override
        public void onResizeConfig(int sessionId, ExtCastProtocol.ResizeConfig cfg) {
            if (cfg == null) return;
            RemoteDisplaySession s = resolveSession(sessionId);
            if (s == null || s.closed) return;
            float fps = (cfg.fpsX100 > 0) ? (cfg.fpsX100 / 100.0f) : s.fps;
            int nextMode = s.standalone ? ExtCastProtocol.DISPLAY_MODE_DEX
                    : ((cfg.displayMode == ExtCastProtocol.DISPLAY_MODE_DEX || cfg.displayMode == ExtCastProtocol.DISPLAY_MODE_MIRROR)
                            ? cfg.displayMode : config.mirrorMode);
            synchronized (s.lock) { s.resizeLocked(RemoteSessionCoordinator.this, cfg.width, cfg.height, cfg.dpi, fps, cfg.bitrateKbps, nextMode, cfg.rotation, remoteMaxBitrateKbps); }
            if (!s.standalone) helperBridge.notifyHelperSync();
        }

        @Override public void onRequestIdr(int sid) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.sink != null) s.sink.requestKeyFrame(); }
        @Override public void onMouseEvent(int sid, ExtCastProtocol.MouseEventMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.inputBridge != null) s.inputBridge.handleMouse(m); }
        @Override public void onTouchEvent(int sid, ExtCastProtocol.TouchEventMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.inputBridge != null) s.inputBridge.handleTouch(m); }
        @Override public void onKeyEvent(int sid, ExtCastProtocol.KeyEventMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.inputBridge != null) s.inputBridge.handleKey(m); }
        @Override public void onTextEvent(int sid, ExtCastProtocol.TextEventMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.clipboardSync != null) s.clipboardSync.handleText(m); }
        @Override public void onImageClipboardEvent(int sid, ExtCastProtocol.ImageClipboardEventMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.clipboardSync != null) s.clipboardSync.handleImageClipboard(m); }
        @Override public void onSystemAction(int sid, ExtCastProtocol.SystemActionMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.inputBridge != null) s.inputBridge.handleSystemAction(m); }
        @Override public void onFileTransferChunk(int sid, ExtCastProtocol.FileTransferChunkMsg m) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.fileTransferReceiver != null) s.fileTransferReceiver.handleFileTransferChunk(m); }
        @Override public void onWindowAction(int sid, ExtCastProtocol.WindowActionMsg m) { handleWindowAction(sid, m); }
        @Override public void onWifiUpgradeEnabled() { helperBridge.notifyHelperSync(); }
        @Override public void onVideoCongestion(int sid) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.sink != null) s.sink.onNetworkCongestion(); }
        @Override public void onVideoQueueHealthy(int sid) { RemoteDisplaySession s = resolveSession(sid); if (s != null && s.sink != null) s.sink.maybeRecoverBitrate(); }

        @Override
        public void onListApps(int sessionId) {
            bgIoExec.execute(() -> {
                RemoteStreamServer srv = server;
                if (srv == null) return;
                Set<Integer> extIds = new HashSet<>();
                if (config.vdId > 0) extIds.add(config.vdId);
                RemoteDisplaySession desk = desktopSession;
                boolean isDesktopQuery = (desk != null && desk.sessionId == sessionId);
                if (!isDesktopQuery) {
                    for (RemoteDisplaySession sas : standaloneSessions.values()) {
                        if (sas != null && !sas.closed && sas.vdId > 0) extIds.add(sas.vdId);
                    }
                }
                srv.sendAppList(sessionId, taskController.collectAppList(extIds));
            });
        }
    }
}
