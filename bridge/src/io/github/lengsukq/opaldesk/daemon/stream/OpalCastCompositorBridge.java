package io.github.lengsukq.opaldesk.daemon.stream;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.BridgeBuildConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.audio.AudioStreamCapturer;
import io.github.lengsukq.opaldesk.daemon.compositor.CompositorEngine;
import io.github.lengsukq.opaldesk.daemon.input.PerDisplayInputRouter;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidDisplayDriver;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.TaskApiAdapter;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;
import io.github.lengsukq.opaldesk.daemon.sink.RemoteStreamSink;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean OpalCast v1.1 MacOS Streaming Bridge wired directly onto the v1.0.0 Universal Compositor.
 *
 * <p>Supports USB ADB ({@code @opaldesk_stream}) and on-demand Wi-Fi TLS ({@code :19878}) with
 * 6-digit PIN + ECDSA P-256 trust store, H.264/HEVC hardware encoding, live resolution/FPS/DPI
 * resize, system audio, bidirectional clipboard, file transfer, and standalone app pop-outs.
 */
public final class OpalCastCompositorBridge implements RemoteStreamServer.SessionListener {
    private static final int DEFAULT_MAX_BITRATE_KBPS = 28_000;
    private static final File TRUST_FILE = new File("/data/local/tmp/opaldesk_trusted_clients.conf");
    private static final File CERT_FILE = new File("/data/local/tmp/opaldesk_tls_cert.p12");

    public static final class RemoteStatusSnapshot {
        public final boolean connected;
        public final int sinkType;
        public final String peerName;
        public final int width;
        public final int height;
        public final int fpsX100;
        public final int bitrateKbps;
        public final boolean wifiEnabled;
        public final int wifiPort;
        public final String certFingerprint;
        public final String activePin;
        public final String lanIp;

        public RemoteStatusSnapshot(
                boolean connected, int sinkType, String peerName,
                int width, int height, int fpsX100, int bitrateKbps,
                boolean wifiEnabled, int wifiPort, String certFingerprint,
                String activePin, String lanIp) {
            this.connected = connected;
            this.sinkType = sinkType;
            this.peerName = peerName != null ? peerName : "";
            this.width = width;
            this.height = height;
            this.fpsX100 = fpsX100;
            this.bitrateKbps = bitrateKbps;
            this.wifiEnabled = wifiEnabled;
            this.wifiPort = wifiPort;
            this.certFingerprint = certFingerprint != null ? certFingerprint : "";
            this.activePin = activePin != null ? activePin : "";
            this.lanIp = lanIp != null ? lanIp : "";
        }
    }

    private static final class ActiveRemoteSession {
        final int sessionId;
        final int sinkType;
        final boolean standalone;
        final String appPackage;
        final String appActivity;
        final Object lock = new Object();

        volatile int width = 1920;
        volatile int height = 1080;
        volatile int dpi = 220;
        volatile float fps = 60f;
        volatile int bitrateKbps = 12_000;
        volatile int codecPref = RemoteStreamSink.CODEC_PREF_AUTO_HEVC;
        volatile int displayId = -1;
        volatile String vdUniqueId = "";
        volatile VirtualDisplay vd;
        volatile RemoteStreamSink sink;
        volatile ClipboardSyncService clipboard;
        volatile FileTransferReceiver fileReceiver;
        volatile long mouseDownTimeMs = 0L;
        volatile int mouseButtons = 0;
        volatile boolean closed = false;

        ActiveRemoteSession(int sessionId, int sinkType, boolean standalone, String pkg, String cls) {
            this.sessionId = sessionId;
            this.sinkType = sinkType;
            this.standalone = standalone;
            this.appPackage = pkg != null ? pkg : "";
            this.appActivity = cls != null ? cls : "";
        }
    }

    private final AndroidServices services;
    private final DaemonConfig config;
    private final CompositorEngine compositor;
    private final AndroidDisplayDriver displayDriver;
    private final PerDisplayInputRouter inputRouter;
    private final TrustStoreManager trustStore;
    private final RemoteStreamServer server;
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "OpalCastWorker");
        t.setDaemon(true);
        return t;
    });

    private final Map<Integer, ActiveRemoteSession> sessions = new ConcurrentHashMap<>();
    private volatile int desktopSessionId = -1;
    private volatile AudioStreamCapturer audioCapturer;

    public OpalCastCompositorBridge(
            AndroidServices services,
            DaemonConfig config,
            CompositorEngine compositor,
            AndroidDisplayDriver displayDriver,
            PerDisplayInputRouter inputRouter) {
        this.services = services;
        this.config = config;
        this.compositor = compositor;
        this.displayDriver = displayDriver;
        this.inputRouter = inputRouter;
        this.trustStore = new TrustStoreManager(TRUST_FILE);
        String devName = (Build.MODEL != null && !Build.MODEL.trim().isEmpty())
                ? Build.MODEL.trim() : "Android Device";
        this.server = new RemoteStreamServer(this, trustStore, CERT_FILE, devName);
    }

    public void start() {
        server.startUsbListener();
    }

    public void shutdown() {
        stopAudio();
        for (ActiveRemoteSession s : new ArrayList<>(sessions.values())) {
            closeSessionResources(s);
        }
        sessions.clear();
        desktopSessionId = -1;
        server.shutdown();
    }

    public RemoteStatusSnapshot getStatusSnapshot() {
        ActiveRemoteSession main = (desktopSessionId > 0) ? sessions.get(desktopSessionId) : null;
        if (main == null && !sessions.isEmpty()) {
            main = sessions.values().iterator().next();
        }
        boolean active = server.hasActiveSession() && main != null;
        int w = active ? main.width : config.getCanvasWidth();
        int h = active ? main.height : config.getCanvasHeight();
        int fpsX100 = active ? Math.round(main.fps * 100f) : Math.round(config.getTargetFps() * 100f);
        int kbps = (active && main.sink != null) ? main.sink.getBitrateKbps() : 0;
        String lanIp = RemoteStreamServer.detectLanIpv4Address();
        return new RemoteStatusSnapshot(
                active,
                active ? server.getActiveSinkType() : DisplaySink.SINK_NONE,
                active ? server.getActivePeerName() : "",
                w, h, fpsX100, kbps,
                server.isWifiEnabled(),
                server.getWifiPort(),
                server.getCertFingerprint(),
                server.getActivePairingPin(),
                lanIp != null ? lanIp : "");
    }

    public RemoteStreamServer.WifiConfigStatus configureWifi(boolean enable, int port, boolean regeneratePin) {
        return server.configureWifi(enable, port > 0 ? port : RemoteStreamServer.DEFAULT_WIFI_PORT, regeneratePin);
    }

    public void disconnectActiveClient() {
        server.disconnectActiveClient("user_requested");
    }

    // ---- RemoteStreamServer.SessionListener ----

    @Override
    public void onSessionConnected(
            int sessionId, int sinkType, String clientId, String clientName, OpalCastProtocol.HelloMsg hello) {
        worker.execute(() -> setupSession(sessionId, sinkType, hello));
    }

    @Override
    public void onSessionDisconnected(int sessionId, int sinkType, String sessionRole, String reason) {
        ActiveRemoteSession s = sessions.remove(sessionId);
        if (sessionId == desktopSessionId) {
            desktopSessionId = -1;
            stopAudio();
        }
        if (s != null) {
            closeSessionResources(s);
        }
    }

    @Override
    public void onResizeConfig(int sessionId, OpalCastProtocol.ResizeConfig cfg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || cfg == null) return;
        worker.execute(() -> reconfigureSession(s, cfg));
    }

    @Override
    public void onRequestIdr(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) {
            s.sink.requestKeyFrame();
        }
    }

    @Override
    public void onMouseEvent(int sessionId, OpalCastProtocol.MouseEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.displayId <= 0) return;
        float x = Math.max(0f, Math.min(s.width - 1f, msg.x));
        float y = Math.max(0f, Math.min(s.height - 1f, msg.y));
        long now = SystemClock.uptimeMillis();

        if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN
                && (msg.actionButton == OpalCastProtocol.BUTTON_SECONDARY
                || msg.actionButton == OpalCastProtocol.BUTTON_BACK)) {
            inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
            inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
            return;
        }

        int androidAction;
        int source = InputDevice.SOURCE_MOUSE;
        if (msg.action == OpalCastProtocol.MOUSE_SCROLL) {
            androidAction = MotionEvent.ACTION_SCROLL;
        } else if (msg.action == OpalCastProtocol.MOUSE_BUTTON_DOWN) {
            s.mouseButtons = msg.buttons != 0 ? msg.buttons : MotionEvent.BUTTON_PRIMARY;
            s.mouseDownTimeMs = now;
            androidAction = MotionEvent.ACTION_DOWN;
        } else if (msg.action == OpalCastProtocol.MOUSE_BUTTON_UP) {
            s.mouseButtons = msg.buttons;
            androidAction = MotionEvent.ACTION_UP;
        } else if (msg.action == OpalCastProtocol.MOUSE_HOVER_EXIT) {
            androidAction = MotionEvent.ACTION_HOVER_EXIT;
        } else {
            androidAction = (s.mouseButtons != 0) ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        }

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[]{new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[]{new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (s.mouseButtons != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (androidAction == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, msg.hScroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, msg.vScroll);
        }

        MotionEvent ev = MotionEvent.obtain(
                s.mouseDownTimeMs > 0L ? s.mouseDownTimeMs : now,
                now, androidAction, 1, props, coords,
                0, s.mouseButtons, 1f, 1f, 0, 0, source, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, s.displayId, s.vdUniqueId, s.width, s.height);
        } finally {
            ev.recycle();
        }
    }

    @Override
    public void onTouchEvent(int sessionId, OpalCastProtocol.TouchEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || msg.pointers.length == 0 || s.displayId <= 0) return;
        int count = Math.min(10, msg.pointers.length);
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = msg.pointers[i].pointerId;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = Math.max(0f, Math.min(s.width - 1f, msg.pointers[i].x));
            coords[i].y = Math.max(0f, Math.min(s.height - 1f, msg.pointers[i].y));
            coords[i].pressure = msg.pointers[i].pressure > 0f ? msg.pointers[i].pressure : 1f;
            coords[i].size = 1f;
        }
        long now = SystemClock.uptimeMillis();
        int action = msg.action;
        if (action == OpalCastProtocol.TOUCH_POINTER_DOWN) {
            action = MotionEvent.ACTION_POINTER_DOWN | (msg.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == OpalCastProtocol.TOUCH_POINTER_UP) {
            action = MotionEvent.ACTION_POINTER_UP | (msg.actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        if (msg.action == OpalCastProtocol.TOUCH_DOWN) s.mouseDownTimeMs = now;
        MotionEvent ev = MotionEvent.obtain(
                s.mouseDownTimeMs > 0L ? s.mouseDownTimeMs : now,
                now, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inputRouter.injectRawEventToDisplay(ev, s.displayId, s.vdUniqueId, s.width, s.height);
        } finally {
            ev.recycle();
        }
    }

    @Override
    public void onKeyEvent(int sessionId, OpalCastProtocol.KeyEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.displayId <= 0) return;
        int action = (msg.action == OpalCastProtocol.KEY_DOWN) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        inputRouter.injectRawKeyToDisplay(
                s.displayId, s.vdUniqueId, s.width, s.height, action, msg.androidKeyCode, msg.metaState);
    }

    @Override
    public void onTextEvent(int sessionId, OpalCastProtocol.TextEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.clipboard == null) return;
        s.clipboard.handleText(msg);
    }

    @Override
    public void onImageClipboardEvent(int sessionId, OpalCastProtocol.ImageClipboardEventMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null || s.clipboard == null) return;
        s.clipboard.handleImageClipboard(msg);
    }

    @Override
    public void onSystemAction(int sessionId, OpalCastProtocol.SystemActionMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s == null || msg == null) return;
        switch (msg.action) {
            case OpalCastProtocol.SYS_BACK:
                inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
                inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
                break;
            case OpalCastProtocol.SYS_HOME:
                if (!s.standalone) {
                    compositor.closeAllWindows(false);
                }
                break;
            case OpalCastProtocol.SYS_SET_AUDIO_ENABLED:
                if (!s.standalone) {
                    if (msg.arg != 0) startAudioForDesktop(sessionId); else stopAudio();
                }
                break;
            case OpalCastProtocol.SYS_DISCONNECT:
                server.disconnectSession(sessionId, "client_system_disconnect");
                break;
            default:
                break;
        }
    }

    @Override
    public void onFileTransferChunk(int sessionId, OpalCastProtocol.FileTransferChunkMsg msg) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.fileReceiver != null) {
            s.fileReceiver.handleFileTransferChunk(msg);
        }
    }

    @Override
    public void onListApps(int sessionId) {
        worker.execute(() -> {
            List<OpalCastProtocol.AppEntry> entries = queryInstalledApps();
            server.sendAppList(sessionId, new OpalCastProtocol.AppListMsg(entries));
        });
    }

    @Override
    public void onWindowAction(int sessionId, OpalCastProtocol.WindowActionMsg msg) {
        if (msg == null || msg.action.isEmpty()) return;
        String act = msg.action.toLowerCase(Locale.ROOT);
        if ("launch".equals(act) || "focus".equals(act) || "open".equals(act)) {
            if (!msg.pkg.isEmpty()) {
                compositor.openOrFocusWindow(msg.pkg, msg.cls, BridgeProtocol.PROFILE_AUTO, 0, 0, 0, 0);
            }
        } else if ("close_all".equals(act)) {
            compositor.closeAllWindows(true);
        }
    }

    @Override
    public void onWifiUpgradeEnabled() {
        System.out.println("OPALDESK_REMOTE_WIFI_UPGRADE_ENABLED port=" + server.getWifiPort());
    }

    @Override
    public void onVideoCongestion(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.onNetworkCongestion();
    }

    @Override
    public void onVideoQueueHealthy(int sessionId) {
        ActiveRemoteSession s = sessions.get(sessionId);
        if (s != null && s.sink != null) s.sink.maybeRecoverBitrate();
    }

    // ---- Internal lifecycle ----

    private void setupSession(int sessionId, int sinkType, OpalCastProtocol.HelloMsg hello) {
        boolean standalone = hello.isStandaloneApp();
        ActiveRemoteSession s = new ActiveRemoteSession(
                sessionId, sinkType, standalone, hello.appPackage, hello.appActivity);
        synchronized (s.lock) {
            int[] aligned = DisplayGeometryUtil.alignForEncoder(hello.width, hello.height, 8);
            s.width = aligned[0];
            s.height = aligned[1];
            s.dpi = DisplayGeometryUtil.clampDpi(hello.dpi, s.width, s.height);
            float reqFps = (hello.fps > 0) ? hello.fps : config.getTargetFps();
            s.fps = DisplayGeometryUtil.clampFps(reqFps, 60f);
            s.bitrateKbps = DisplayGeometryUtil.clampBitrateKbps(hello.bitrateKbps, 1500, DEFAULT_MAX_BITRATE_KBPS);
            s.codecPref = "h264".equalsIgnoreCase(hello.preferredCodec)
                    ? RemoteStreamSink.CODEC_PREF_H264 : RemoteStreamSink.CODEC_PREF_AUTO_HEVC;

            RemoteStreamSink sink = new RemoteStreamSink(sinkType, new RemoteStreamSink.FrameListener() {
                @Override
                public void onVideoConfig(OpalCastProtocol.VideoConfig cfg, long tsUs) {
                    server.sendVideoConfig(sessionId, cfg, tsUs);
                }

                @Override
                public void onVideoFrame(byte[] annexB, int flags, long tsUs) {
                    server.sendVideoFrame(sessionId, annexB, flags, tsUs);
                }

                @Override
                public void onEncoderError(String reason) {
                    System.err.println("OPALDESK_REMOTE_ENC_ERR sid=" + sessionId + ": " + reason);
                }
            });
            sink.setMaxBitrateKbps(DEFAULT_MAX_BITRATE_KBPS);
            Surface codecSurf = sink.startOrReconfigure(
                    s.width, s.height, s.dpi, s.fps, s.bitrateKbps,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0);
            if (codecSurf == null) {
                server.disconnectSession(sessionId, "encoder_start_failed");
                return;
            }
            s.sink = sink;
            s.width = sink.getWidth();
            s.height = sink.getHeight();
            s.dpi = sink.getDpi();

            String vdName = standalone ? ("OpalDesk-App-" + s.appPackage) : "OpalDesk-Remote";
            int[] candidates = new int[]{
                    BridgeProtocol.VD_FLAGS_TRUSTED_FOCUS,
                    BridgeProtocol.VD_FLAGS_BASE_PUBLIC
            };
            VirtualDisplay vd = VirtualDisplayFactory.createWithFlagFallback(
                    services.displayManager, vdName, s.width, s.height, s.dpi, candidates, s.fps, codecSurf, null);
            if (vd == null || vd.getDisplay() == null) {
                sink.stop();
                server.disconnectSession(sessionId, "virtual_display_failed");
                return;
            }
            Display disp = vd.getDisplay();
            s.vd = vd;
            s.displayId = disp.getDisplayId();
            String uid = VirtualDisplayFactory.getUniqueId(disp);
            s.vdUniqueId = uid != null ? uid : "";
            services.configureVirtualDisplayWindowing(s.displayId, BridgeProtocol.DISPLAY_IME_POLICY_FALLBACK_DISPLAY);

            s.clipboard = new ClipboardSyncService(new ClipboardSyncService.ClipboardHost() {
                @Override public void sendClipboardPush(long seq, String text) {
                    server.sendClipboardPush(sessionId, seq, text);
                }
                @Override public void sendClipboardImagePush(long seq, byte[] pngData) {
                    server.sendClipboardImagePush(sessionId, seq, pngData);
                }
                @Override public void triggerPasteKey() {
                    int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, ctrl);
                    inputRouter.injectRawKeyToDisplay(s.displayId, s.vdUniqueId, s.width, s.height,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, ctrl);
                }
            }, null, !standalone);
            s.fileReceiver = new FileTransferReceiver((transferId, status, pathOrError) ->
                    server.sendFileTransferAck(sessionId, transferId, status, pathOrError));

            sessions.put(sessionId, s);
            if (!standalone) {
                desktopSessionId = sessionId;
                launchRemoteDesktopHost(s.displayId);
                if (hello.audioEnabled) {
                    startAudioForDesktop(sessionId);
                }
            } else if (!s.appPackage.isEmpty()) {
                displayDriver.launchOrMoveFullscreenApp(s.appPackage, s.appActivity, s.displayId, -1);
            }

            s.clipboard.onSessionStarted();
            pushDeviceState(s);
            System.out.println("OPALDESK_REMOTE_SESSION_READY sid=" + sessionId
                    + " role=" + (standalone ? "standalone" : "desktop")
                    + " displayId=" + s.displayId + " " + s.width + "x" + s.height + "@" + s.fps);
        }
    }

    private void reconfigureSession(ActiveRemoteSession s, OpalCastProtocol.ResizeConfig cfg) {
        synchronized (s.lock) {
            if (s.closed || s.sink == null || s.vd == null) return;
            int[] aligned = DisplayGeometryUtil.alignForEncoder(
                    cfg.width > 0 ? cfg.width : s.width,
                    cfg.height > 0 ? cfg.height : s.height,
                    8);
            int nextW = aligned[0];
            int nextH = aligned[1];
            int nextDpi = (cfg.dpi > 0) ? DisplayGeometryUtil.clampDpi(cfg.dpi, nextW, nextH) : s.dpi;
            float nextFps = (cfg.fpsX100 > 0) ? DisplayGeometryUtil.clampFps(cfg.fpsX100 / 100f, s.fps) : s.fps;
            int nextBitrate = (cfg.bitrateKbps > 0)
                    ? DisplayGeometryUtil.clampBitrateKbps(cfg.bitrateKbps, 1500, DEFAULT_MAX_BITRATE_KBPS)
                    : s.bitrateKbps;

            Surface nextSurf = s.sink.startOrReconfigure(
                    nextW, nextH, nextDpi, nextFps, nextBitrate,
                    s.codecPref, OpalCastProtocol.DISPLAY_MODE_DEX, 0);
            if (nextSurf == null) return;

            s.width = s.sink.getWidth();
            s.height = s.sink.getHeight();
            s.dpi = s.sink.getDpi();
            s.fps = nextFps;
            s.bitrateKbps = nextBitrate;
            try {
                s.vd.resize(s.width, s.height, s.dpi);
                s.vd.setSurface(nextSurf);
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_REMOTE_RESIZE_WARN: " + e.getMessage());
            }
            pushDeviceState(s);
        }
    }

    private void launchRemoteDesktopHost(int displayId) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                BridgeBuildConfig.HELPER_PACKAGE,
                BridgeBuildConfig.HELPER_PACKAGE + ".ExternalDesktopActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra("targetDisplayId", displayId);
        Bundle opts = TaskApiAdapter.makeFullscreenLaunchOptions(displayId).toBundle();
        services.startActivityAsShell(intent, opts);
    }

    private void startAudioForDesktop(int sessionId) {
        stopAudio();
        AudioStreamCapturer cap = new AudioStreamCapturer(services.context, new AudioStreamCapturer.AudioListener() {
            @Override
            public void onAudioConfig(OpalCastProtocol.AudioConfig cfg, long tsUs) {
                server.sendAudioConfig(sessionId, cfg, tsUs);
            }

            @Override
            public void onAudioFrame(byte[] aacAu, long tsUs) {
                server.sendAudioFrame(sessionId, aacAu, tsUs);
            }

            @Override
            public void onAudioStateChanged(boolean active) {
                System.out.println("OPALDESK_REMOTE_AUDIO_STATE active=" + active);
            }
        });
        if (cap.start()) {
            audioCapturer = cap;
        }
    }

    private void stopAudio() {
        AudioStreamCapturer cap = audioCapturer;
        audioCapturer = null;
        if (cap != null) {
            try { cap.stop(); } catch (RuntimeException ignored) {}
        }
    }

    private void pushDeviceState(ActiveRemoteSession s) {
        OpalCastProtocol.DeviceState state = new OpalCastProtocol.DeviceState(
                server.getDeviceName(),
                s.sinkType,
                OpalCastProtocol.DISPLAY_MODE_DEX,
                s.width,
                s.height,
                s.dpi,
                s.fps,
                0,
                s.bitrateKbps,
                100,
                false,
                audioCapturer != null,
                inputRouter.getLastInjectStatus(),
                false,
                0,
                "DEX",
                "",
                "");
        server.sendDeviceState(s.sessionId, state);
    }

    private void closeSessionResources(ActiveRemoteSession s) {
        synchronized (s.lock) {
            if (s.closed) return;
            s.closed = true;
            if (s.clipboard != null) {
                try { s.clipboard.onSessionEnded(); } catch (RuntimeException ignored) {}
                s.clipboard = null;
            }
            if (s.fileReceiver != null) {
                try { s.fileReceiver.abortAllTransfers(); } catch (RuntimeException ignored) {}
                s.fileReceiver = null;
            }
            if (s.vd != null) {
                int oldId = s.displayId;
                try { s.vd.setSurface(null); } catch (RuntimeException ignored) {}
                try { s.vd.release(); } catch (RuntimeException ignored) {}
                s.vd = null;
                if (oldId > 0) inputRouter.releaseDisplayResources(oldId);
            }
            if (s.sink != null) {
                try { s.sink.stop(); } catch (RuntimeException ignored) {}
                s.sink = null;
            }
        }
    }

    private List<OpalCastProtocol.AppEntry> queryInstalledApps() {
        List<OpalCastProtocol.AppEntry> out = new ArrayList<>();
        if (services.context == null) return out;
        try {
            PackageManager pm = services.context.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (DaemonConfig.PackagePolicy.isBlockedWindowPkg(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                String label = (lbl != null && lbl.length() > 0) ? lbl.toString().trim() : pkg;
                out.add(new OpalCastProtocol.AppEntry(pkg, ri.activityInfo.name, label, false, -1));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }
}
