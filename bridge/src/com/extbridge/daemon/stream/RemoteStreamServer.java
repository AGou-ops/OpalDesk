package com.extbridge.daemon.stream;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.SystemClock;

import com.extbridge.daemon.security.SelfSignedCertGenerator;
import com.extbridge.daemon.security.TrustStoreManager;
import com.extbridge.daemon.sink.DisplaySink;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Dual-transport, multi-session ExtCast v1.1 stream server:
 * 1) Always-on ADB LocalServerSocket("extbridge_stream") with strict peer UID (0/2000) check,
 *    supporting both wired USB (SINK_REMOTE_USB) and wireless ADB (SINK_REMOTE_WIFI_ADB) via
 *    HELLO.transportHint, plus authenticated Wi-Fi TLS zero-PIN upgrade (REQUEST_WIFI_UPGRADE).
 * 2) On-demand wireless SSLServerSocket (OFF by default; TLS 1.2/1.3, TCP_NODELAY, ECDSA P-256,
 *    one-time 6-digit PIN + 32-byte hex authToken trust store).
 * 3) Concurrent multi-session isolation: 1 main "desktop" session + up to 3 independent
 *    "standalone_app" pop-out sessions without kicking each other off.
 */
public final class RemoteStreamServer {
    public static final String USB_SOCKET_NAME = "extbridge_stream";
    public static final int DEFAULT_WIFI_PORT = 19876;
    public static final int MAX_STANDALONE_SESSIONS = 3;

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int SOCKET_IO_BUFFER_BYTES = 131_072;
    private static final int SOCKET_SEND_BUF_BYTES = 512 * 1024;

    public interface SessionListener {
        void onSessionConnected(
                int sessionId,
                int sinkType,
                String clientId,
                String clientName,
                ExtCastProtocol.HelloMsg hello);
        void onSessionDisconnected(
                int sessionId,
                int sinkType,
                String sessionRole,
                String reason);
        void onResizeConfig(int sessionId, ExtCastProtocol.ResizeConfig config);
        void onRequestIdr(int sessionId);
        void onMouseEvent(int sessionId, ExtCastProtocol.MouseEventMsg msg);
        void onTouchEvent(int sessionId, ExtCastProtocol.TouchEventMsg msg);
        void onKeyEvent(int sessionId, ExtCastProtocol.KeyEventMsg msg);
        void onTextEvent(int sessionId, ExtCastProtocol.TextEventMsg msg);
        void onImageClipboardEvent(int sessionId, ExtCastProtocol.ImageClipboardEventMsg msg);
        void onSystemAction(int sessionId, ExtCastProtocol.SystemActionMsg msg);
        void onFileTransferChunk(int sessionId, ExtCastProtocol.FileTransferChunkMsg msg);
        void onListApps(int sessionId);
        void onWindowAction(int sessionId, ExtCastProtocol.WindowActionMsg msg);
        void onWifiUpgradeEnabled();
        void onVideoCongestion(int sessionId);
        void onVideoQueueHealthy(int sessionId);
    }

    public static final class WifiConfigStatus {
        public final boolean enabled;
        public final int port;
        public final String certFingerprint;
        public final String activePin;

        public WifiConfigStatus(boolean enabled, int port, String certFingerprint, String activePin) {
            this.enabled = enabled;
            this.port = port;
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.activePin = (activePin != null) ? activePin : "";
        }
    }

    private interface TimeoutClearer {
        void clear() throws IOException;
    }

    private final SessionListener listener;
    private final TrustStoreManager trustStore;
    private final File certStoreFile;
    private final String deviceName;

    private final Object lifecycleLock = new Object();
    private volatile boolean stopped = false;

    // ADB LocalServerSocket (handles both wired USB and wireless ADB)
    private LocalServerSocket usbServerSocket;
    private Thread usbAcceptThread;

    // Wireless TLS server (OFF by default!)
    private volatile boolean wifiEnabled = false;
    private volatile int wifiPort = DEFAULT_WIFI_PORT;
    private volatile SelfSignedCertGenerator.ServerIdentity tlsIdentity;
    private SSLServerSocket wifiServerSocket;
    private Thread wifiAcceptThread;

    // Multi-session registry: 1 desktop session + up to 3 standalone_app sessions
    private final AtomicInteger nextSessionId = new AtomicInteger(0);
    private final Object sessionLock = new Object();
    private volatile ClientSession desktopSession;
    private final LinkedHashMap<Integer, ClientSession> standaloneSessions = new LinkedHashMap<>();
    private final ConcurrentHashMap<Integer, ClientSession> allSessions = new ConcurrentHashMap<>();

    public RemoteStreamServer(SessionListener listener, TrustStoreManager trustStore) {
        this(listener, trustStore, new File(SelfSignedCertGenerator.DEFAULT_KEYSTORE_PATH), resolveDeviceName());
    }

    public RemoteStreamServer(
            SessionListener listener,
            TrustStoreManager trustStore,
            File certStoreFile,
            String deviceName) {
        this.listener = listener;
        this.trustStore = (trustStore != null) ? trustStore : new TrustStoreManager();
        this.certStoreFile = certStoreFile;
        this.deviceName = (deviceName != null && !deviceName.trim().isEmpty()) ? deviceName.trim() : "Android";
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isWifiEnabled() {
        return wifiEnabled;
    }

    public int getWifiPort() {
        return wifiPort;
    }

    public String getCertFingerprint() {
        SelfSignedCertGenerator.ServerIdentity id = tlsIdentity;
        return (id != null && id.certFingerprintSha256 != null) ? id.certFingerprintSha256 : "";
    }

    public String getActivePairingPin() {
        return wifiEnabled ? trustStore.getActivePin() : "";
    }

    public boolean hasDesktopSession() {
        ClientSession s = desktopSession;
        return s != null && !s.isClosed();
    }

    public boolean hasActiveSession() {
        if (hasDesktopSession()) return true;
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return true;
            }
        }
        return false;
    }

    public int getActiveSinkType() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed()) {
            return d.sinkType;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed()) return s.sinkType;
            }
        }
        return DisplaySink.SINK_NONE;
    }

    public String getActivePeerName() {
        ClientSession d = desktopSession;
        if (d != null && !d.isClosed() && d.clientName != null) {
            return d.clientName;
        }
        synchronized (sessionLock) {
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && !s.isClosed() && s.clientName != null) return s.clientName;
            }
        }
        return "";
    }

    /**
     * Starts the always-on ADB abstract socket listener ("extbridge_stream").
     * Does NOT start Wi-Fi TLS server (wireless remains off by default until explicitly enabled
     * via Binder code 45 or authenticated ADB REQUEST_WIFI_UPGRADE).
     */
    public void startUsbListener() {
        synchronized (lifecycleLock) {
            if (stopped || usbServerSocket != null) return;
            try {
                usbServerSocket = new LocalServerSocket(USB_SOCKET_NAME);
                usbAcceptThread = new Thread(this::usbAcceptLoop, "ExtUsbStreamAccept");
                usbAcceptThread.setDaemon(true);
                usbAcceptThread.start();
                System.out.println("EXTBRIDGE_USB_STREAM_LISTENING @" + USB_SOCKET_NAME);
            } catch (Throwable t) {
                System.err.println("EXTBRIDGE_USB_STREAM_BIND_FAILED: " + t);
            }
        }
    }

    /**
     * Configures the on-demand wireless TLS 1.2/1.3 server (Binder code 45 or ADB Wi-Fi upgrade).
     */
    public WifiConfigStatus configureWifi(boolean enable, int requestedPort, boolean regeneratePin) {
        synchronized (lifecycleLock) {
            if (requestedPort >= 1024 && requestedPort <= 65535) {
                wifiPort = requestedPort;
            }
            if (regeneratePin) {
                trustStore.regeneratePin();
            }
            if (!enable) {
                stopWifiListenerLocked();
                disconnectMatchingSessions(s -> s.sinkType == DisplaySink.SINK_REMOTE_WIFI, "Wi-Fi server disabled");
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), "");
            }

            if (stopped) {
                return new WifiConfigStatus(false, wifiPort, getCertFingerprint(), "");
            }

            if (wifiEnabled && wifiServerSocket != null && !wifiServerSocket.isClosed()
                    && wifiServerSocket.getLocalPort() == wifiPort) {
                return new WifiConfigStatus(true, wifiPort, getCertFingerprint(), trustStore.getActivePin());
            }

            stopWifiListenerLocked();
            try {
                if (tlsIdentity == null) {
                    tlsIdentity = SelfSignedCertGenerator.loadOrCreate(certStoreFile, deviceName);
                }
                SSLContext sslCtx = SelfSignedCertGenerator.createServerSSLContext(tlsIdentity);
                SSLServerSocket ss = (SSLServerSocket) sslCtx.getServerSocketFactory().createServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(wifiPort), 8);
                applySecureTlsProtocols(ss.getSupportedProtocols(), ss::setEnabledProtocols);

                wifiServerSocket = ss;
                wifiEnabled = true;
                wifiAcceptThread = new Thread(() -> wifiAcceptLoop(ss), "ExtWifiStreamAccept");
                wifiAcceptThread.setDaemon(true);
                wifiAcceptThread.start();
                System.out.println("EXTBRIDGE_WIFI_TLS_LISTENING port=" + wifiPort
                        + " fp=" + tlsIdentity.certFingerprintSha256.substring(0, 12));
            } catch (Throwable t) {
                System.err.println("EXTBRIDGE_WIFI_TLS_START_FAILED: " + t);
                stopWifiListenerLocked();
            }
            return new WifiConfigStatus(wifiEnabled, wifiPort, getCertFingerprint(), getActivePairingPin());
        }
    }

    public boolean revokeTrustedClient(String clientId) {
        boolean ok = trustStore.revokeClient(clientId);
        disconnectMatchingSessions(
                s -> s.sinkType == DisplaySink.SINK_REMOTE_WIFI
                        && ("*".equals(clientId) || (s.clientId != null && s.clientId.equals(clientId))),
                "Trusted client revoked");
        return ok;
    }

    public void disconnectSession(int sessionId, String reason) {
        ClientSession target = allSessions.get(sessionId);
        if (target != null) {
            target.close(reason);
        }
    }

    public void disconnectActiveClient(String reason) {
        List<ClientSession> toClose = new ArrayList<>();
        synchronized (sessionLock) {
            if (desktopSession != null) toClose.add(desktopSession);
            toClose.addAll(standaloneSessions.values());
        }
        for (ClientSession s : toClose) {
            s.close(reason);
        }
    }

    private void disconnectMatchingSessions(Predicate<ClientSession> pred, String reason) {
        List<ClientSession> toClose = new ArrayList<>();
        synchronized (sessionLock) {
            if (desktopSession != null && pred.test(desktopSession)) {
                toClose.add(desktopSession);
            }
            for (ClientSession s : standaloneSessions.values()) {
                if (s != null && pred.test(s)) {
                    toClose.add(s);
                }
            }
        }
        for (ClientSession s : toClose) {
            s.close(reason);
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            stopped = true;
            stopWifiListenerLocked();
            if (usbServerSocket != null) {
                ClientSession.closeTransportQuietly(usbServerSocket);
                usbServerSocket = null;
            }
        }
        disconnectActiveClient("Daemon shutting down");
    }

    // ---- Session-addressed outbound frame senders ----

    public void sendVideoConfig(int sessionId, ExtCastProtocol.VideoConfig config, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(ExtCastProtocol.TYPE_VIDEO_CONFIG, 0, timestampUs, ExtCastProtocol.encodeVideoConfig(config));
        }
    }

    public void sendVideoFrame(int sessionId, byte[] annexB, int flags, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(ExtCastProtocol.TYPE_VIDEO_FRAME, flags, timestampUs, annexB);
        }
    }

    public void sendAudioConfig(int sessionId, ExtCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && config != null) {
            s.enqueue(ExtCastProtocol.TYPE_AUDIO_CONFIG, 0, timestampUs, ExtCastProtocol.encodeAudioConfig(config));
        }
    }

    public void sendAudioFrame(int sessionId, byte[] aacAu, long timestampUs) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            s.enqueue(ExtCastProtocol.TYPE_AUDIO_FRAME, 0, timestampUs, aacAu);
        }
    }

    public void sendDeviceState(int sessionId, ExtCastProtocol.DeviceState state) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && state != null) {
            long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
            s.enqueue(ExtCastProtocol.TYPE_DEVICE_STATE, 0, ts, ExtCastProtocol.encodeDeviceState(state));
        }
    }

    public void sendClipboardPush(int sessionId, long seq, String text) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
            s.enqueue(ExtCastProtocol.TYPE_CLIPBOARD_PUSH, 0, ts, ExtCastProtocol.encodeClipboardPush(seq, text));
        }
    }

    public void sendClipboardImagePush(int sessionId, long seq, byte[] pngData) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            try {
                byte[] payload = ExtCastProtocol.encodeClipboardImagePush(seq, pngData);
                long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
                s.enqueue(ExtCastProtocol.TYPE_CLIPBOARD_PUSH, ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, ts, payload);
            } catch (Throwable t) {
                System.err.println("EXTBRIDGE_CLIP_IMG_SEND_ERR: " + t.getMessage());
            }
        }
    }

    public void sendFileTransferAck(int sessionId, int transferId, int status, String pathOrError) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed()) {
            long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
            s.enqueue(ExtCastProtocol.TYPE_FILE_TRANSFER_ACK, 0, ts,
                    ExtCastProtocol.encodeFileTransferAck(transferId, status, pathOrError));
        }
    }

    public void sendAppList(int sessionId, ExtCastProtocol.AppListMsg appList) {
        ClientSession s = allSessions.get(sessionId);
        if (s != null && !s.isClosed() && appList != null) {
            long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
            s.enqueue(ExtCastProtocol.TYPE_APP_LIST, 0, ts, ExtCastProtocol.encodeAppList(appList));
        }
    }

    // ---- Desktop-session convenience senders ----

    public void sendAudioConfig(ExtCastProtocol.AudioConfig config, long timestampUs) {
        ClientSession s = desktopSession;
        if (s != null) sendAudioConfig(s.sessionId, config, timestampUs);
    }

    public void sendAudioFrame(byte[] aacAu, long timestampUs) {
        ClientSession s = desktopSession;
        if (s != null) sendAudioFrame(s.sessionId, aacAu, timestampUs);
    }

    public void sendDeviceState(ExtCastProtocol.DeviceState state) {
        ClientSession s = desktopSession;
        if (s != null) sendDeviceState(s.sessionId, state);
    }

    public void sendClipboardPush(long seq, String text) {
        ClientSession s = desktopSession;
        if (s != null) sendClipboardPush(s.sessionId, seq, text);
    }

    public void sendClipboardImagePush(long seq, byte[] pngData) {
        ClientSession s = desktopSession;
        if (s != null) sendClipboardImagePush(s.sessionId, seq, pngData);
    }

    public void sendFileTransferAck(int transferId, int status, String pathOrError) {
        ClientSession s = desktopSession;
        if (s != null) sendFileTransferAck(s.sessionId, transferId, status, pathOrError);
    }

    // ---- Accept loops ----

    private void usbAcceptLoop() {
        while (!stopped) {
            LocalServerSocket srv = this.usbServerSocket;
            if (srv == null) break;
            try {
                LocalSocket client = srv.accept();
                if (stopped) {
                    ClientSession.closeTransportQuietly(client);
                    break;
                }
                Credentials cred = null;
                try {
                    cred = client.getPeerCredentials();
                } catch (IOException e) {
                    System.err.println("EXTBRIDGE_USB_CRED_ERR: " + e.getMessage());
                }
                int peerUid = (cred != null) ? cred.getUid() : -1;
                if (peerUid != 0 && peerUid != 2000) {
                    System.err.println("EXTBRIDGE_USB_STREAM_REJECT_UID uid=" + peerUid);
                    ClientSession.closeTransportQuietly(client);
                    continue;
                }
                Thread handler = new Thread(() -> handleUsbClient(client), "ExtUsbClient");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable t) {
                if (!stopped) {
                    System.err.println("EXTBRIDGE_USB_ACCEPT_ERR: " + t.getMessage());
                    SystemClock.sleep(200);
                }
            }
        }
    }

    private void wifiAcceptLoop(SSLServerSocket serverSock) {
        while (!stopped && wifiEnabled && !serverSock.isClosed()) {
            try {
                Socket raw = serverSock.accept();
                if (stopped || !wifiEnabled || !(raw instanceof SSLSocket)) {
                    ClientSession.closeTransportQuietly(raw);
                    continue;
                }
                SSLSocket ssl = (SSLSocket) raw;
                Thread handler = new Thread(() -> handleWifiClient(ssl), "ExtWifiClient");
                handler.setDaemon(true);
                handler.start();
            } catch (Throwable t) {
                if (!stopped && wifiEnabled && !serverSock.isClosed()) {
                    System.err.println("EXTBRIDGE_WIFI_ACCEPT_ERR: " + t.getMessage());
                    SystemClock.sleep(150);
                }
            }
        }
    }

    private void stopWifiListenerLocked() {
        wifiEnabled = false;
        SSLServerSocket ss = wifiServerSocket;
        wifiServerSocket = null;
        ClientSession.closeTransportQuietly(ss);
    }

    // ---- Shared Handshake & Transport Handlers ----

    private void handleUsbClient(LocalSocket sock) {
        try {
            try { sock.setSendBufferSize(SOCKET_SEND_BUF_BYTES); } catch (IOException ignored) {}
            try { sock.setReceiveBufferSize(SOCKET_IO_BUFFER_BYTES); } catch (IOException ignored) {}
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);

            InputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);

            ExtCastProtocol.HelloMsg hello = readAndValidateHello(in, out, sock);
            if (hello == null) return;

            int sinkType = ExtCastProtocol.TRANSPORT_HINT_WIFI_ADB.equalsIgnoreCase(hello.transportHint)
                    ? DisplaySink.SINK_REMOTE_WIFI_ADB
                    : DisplaySink.SINK_REMOTE_USB;
            String clientId = TrustStoreManager.sanitizeClientId(hello.clientId);
            if (clientId.isEmpty()) clientId = "usb-peer";
            String defaultName = (sinkType == DisplaySink.SINK_REMOTE_WIFI_ADB) ? "Mac (Wi-Fi ADB)" : "Mac (USB)";
            String clientName = TrustStoreManager.sanitizeClientName(hello.clientName, defaultName);
            String authMsg = (sinkType == DisplaySink.SINK_REMOTE_WIFI_ADB)
                    ? "Wireless ADB channel authenticated"
                    : "USB ADB channel authenticated";

            completeHandshakeAndRun(
                    sinkType, true, clientId, clientName, "", authMsg,
                    hello, sock, () -> sock.setSoTimeout(0), in, out);
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_USB_CLIENT_ERR: " + t.getMessage());
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private void handleWifiClient(SSLSocket sock) {
        try {
            sock.setTcpNoDelay(true);
            sock.setKeepAlive(true);
            try { sock.setSendBufferSize(SOCKET_SEND_BUF_BYTES); } catch (SocketException ignored) {}
            try { sock.setReceiveBufferSize(SOCKET_IO_BUFFER_BYTES); } catch (SocketException ignored) {}
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            applySecureTlsProtocols(sock.getSupportedProtocols(), sock::setEnabledProtocols);
            sock.startHandshake();

            String peerIp = (sock.getInetAddress() != null) ? sock.getInetAddress().getHostAddress() : "unknown";
            InputStream in = new BufferedInputStream(sock.getInputStream(), SOCKET_IO_BUFFER_BYTES);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), SOCKET_IO_BUFFER_BYTES);

            ExtCastProtocol.HelloMsg hello = readAndValidateHello(in, out, sock);
            if (hello == null) return;

            TrustStoreManager.AuthResult auth = trustStore.authenticate(
                    hello.clientId, hello.clientName, hello.authToken, hello.pinCode, peerIp);
            if (!auth.ok) {
                sendAuthFailureAndClose(out, sock, auth.message);
                return;
            }

            String clientId = TrustStoreManager.sanitizeClientId(hello.clientId);
            String clientName = TrustStoreManager.sanitizeClientName(hello.clientName, "Mac (Wi-Fi)");

            completeHandshakeAndRun(
                    DisplaySink.SINK_REMOTE_WIFI, false, clientId, clientName,
                    auth.issuedToken, auth.message,
                    hello, sock, () -> sock.setSoTimeout(0), in, out);
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_WIFI_CLIENT_ERR: " + t.getMessage());
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private ExtCastProtocol.HelloMsg readAndValidateHello(InputStream in, OutputStream out, Closeable sock)
            throws IOException {
        ExtCastProtocol.Frame first = ExtCastProtocol.readFrame(in);
        if (first == null || first.type != ExtCastProtocol.TYPE_HELLO) {
            sendAuthFailureAndClose(out, sock, "Expected HELLO frame");
            return null;
        }
        ExtCastProtocol.HelloMsg hello = ExtCastProtocol.decodeHello(first.payload);
        if (hello.protocolVersion != ExtCastProtocol.PROTOCOL_VERSION) {
            sendAuthFailureAndClose(out, sock, "Unsupported protocolVersion: " + hello.protocolVersion);
            return null;
        }
        if (hello.isStandaloneApp() && hello.appPackage.isEmpty()) {
            sendAuthFailureAndClose(out, sock, "Missing appPackage for standalone_app session");
            return null;
        }
        return hello;
    }

    private void completeHandshakeAndRun(
            int sinkType,
            boolean isAdbLocalSocket,
            String clientId,
            String clientName,
            String issuedToken,
            String authMsg,
            ExtCastProtocol.HelloMsg hello,
            Closeable sock,
            TimeoutClearer clearTimeout,
            InputStream in,
            OutputStream out) throws IOException {
        if (!hasCapacityForSession(hello)) {
            sendAuthFailureAndClose(out, sock,
                    "Maximum " + MAX_STANDALONE_SESSIONS + " standalone app sessions active");
            return;
        }

        ExtCastProtocol.AuthResultMsg okMsg = new ExtCastProtocol.AuthResultMsg(
                true, issuedToken, authMsg, deviceName, getCertFingerprint());
        writeAuthResultImmediate(out, okMsg);
        clearTimeout.clear();
        runAuthenticatedSession(sinkType, isAdbLocalSocket, clientId, clientName, hello, sock, in, out);
    }

    private boolean hasCapacityForSession(ExtCastProtocol.HelloMsg hello) {
        if (!hello.isStandaloneApp()) return true;
        synchronized (sessionLock) {
            for (ClientSession existing : standaloneSessions.values()) {
                if (existing != null && !existing.isClosed() && hello.appPackage.equals(existing.appPackage)) {
                    return true; // Replacing existing standalone window for the same app
                }
            }
            return standaloneSessions.size() < MAX_STANDALONE_SESSIONS;
        }
    }

    private void runAuthenticatedSession(
            int sinkType,
            boolean isAdbLocalSocket,
            String clientId,
            String clientName,
            ExtCastProtocol.HelloMsg hello,
            Closeable transport,
            InputStream in,
            OutputStream out) {
        int sessionId = nextSessionId.incrementAndGet();
        boolean isStandalone = hello.isStandaloneApp();
        ClientSession current = new ClientSession(
                sessionId, sinkType, isAdbLocalSocket, hello.sessionRole, hello.appPackage,
                clientId, clientName, transport, out, listener);

        ClientSession replaced = null;
        boolean capacityExceeded = false;
        synchronized (sessionLock) {
            if (isStandalone) {
                for (ClientSession existing : standaloneSessions.values()) {
                    if (existing != null && hello.appPackage.equals(existing.appPackage)) {
                        replaced = existing;
                        break;
                    }
                }
                if (replaced != null) {
                    standaloneSessions.remove(replaced.sessionId);
                } else if (standaloneSessions.size() >= MAX_STANDALONE_SESSIONS) {
                    capacityExceeded = true;
                }
                if (!capacityExceeded) {
                    standaloneSessions.put(sessionId, current);
                    allSessions.put(sessionId, current);
                }
            } else {
                replaced = desktopSession;
                desktopSession = current;
                allSessions.put(sessionId, current);
            }
        }
        if (capacityExceeded) {
            current.close("Maximum standalone app sessions reached");
            return;
        }
        if (replaced != null) {
            replaced.close("Replaced by new session");
        }

        current.startSenderThread();
        boolean notifiedConnect = false;
        try {
            if (listener != null) {
                listener.onSessionConnected(sessionId, sinkType, clientId, clientName, hello);
                notifiedConnect = true;
            }
            while (!stopped && !current.isClosed()) {
                ExtCastProtocol.Frame frame = ExtCastProtocol.readFrame(in);
                if (frame == null) {
                    break;
                }
                dispatchUplinkFrame(current, frame);
            }
        } catch (Throwable t) {
            if (!current.isClosed()) {
                System.out.println("EXTBRIDGE_REMOTE_SESSION_END id=" + sessionId
                        + " role=" + current.sessionRole + " peer=" + clientName + " reason=" + t.getMessage());
            }
        } finally {
            current.close("Session closed");
            allSessions.remove(sessionId, current);
            synchronized (sessionLock) {
                if (isStandalone) {
                    if (standaloneSessions.get(sessionId) == current) {
                        standaloneSessions.remove(sessionId);
                    }
                } else if (desktopSession == current) {
                    desktopSession = null;
                }
            }
            if (notifiedConnect && listener != null) {
                listener.onSessionDisconnected(sessionId, sinkType, current.sessionRole, current.closeReason);
            }
        }
    }

    private void dispatchUplinkFrame(ClientSession session, ExtCastProtocol.Frame f) throws IOException {
        int sid = session.sessionId;
        switch (f.type) {
            case ExtCastProtocol.TYPE_PING: {
                long pingId = ExtCastProtocol.decodePingPong(f.payload);
                session.enqueue(ExtCastProtocol.TYPE_PONG, 0,
                        SystemClock.elapsedRealtimeNanos() / 1000L,
                        ExtCastProtocol.encodePingPong(pingId));
                break;
            }
            case ExtCastProtocol.TYPE_REQUEST_IDR: {
                if (listener != null) listener.onRequestIdr(sid);
                break;
            }
            case ExtCastProtocol.TYPE_RESIZE_CONFIG: {
                ExtCastProtocol.ResizeConfig cfg = ExtCastProtocol.decodeResizeConfig(f.payload);
                if (listener != null) listener.onResizeConfig(sid, cfg);
                break;
            }
            case ExtCastProtocol.TYPE_INPUT_MOUSE: {
                ExtCastProtocol.MouseEventMsg msg = ExtCastProtocol.decodeMouseEvent(f.payload);
                if (listener != null) listener.onMouseEvent(sid, msg);
                break;
            }
            case ExtCastProtocol.TYPE_INPUT_TOUCH: {
                ExtCastProtocol.TouchEventMsg msg = ExtCastProtocol.decodeTouchEvent(f.payload);
                if (listener != null) listener.onTouchEvent(sid, msg);
                break;
            }
            case ExtCastProtocol.TYPE_INPUT_KEY: {
                ExtCastProtocol.KeyEventMsg msg = ExtCastProtocol.decodeKeyEvent(f.payload);
                if (listener != null) listener.onKeyEvent(sid, msg);
                break;
            }
            case ExtCastProtocol.TYPE_INPUT_TEXT: {
                if ((f.flags & ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG) != 0) {
                    ExtCastProtocol.ImageClipboardEventMsg imgMsg = ExtCastProtocol.decodeImageClipboardEvent(f.payload);
                    if (listener != null) listener.onImageClipboardEvent(sid, imgMsg);
                } else {
                    ExtCastProtocol.TextEventMsg msg = ExtCastProtocol.decodeTextEvent(f.payload);
                    if (listener != null) listener.onTextEvent(sid, msg);
                }
                break;
            }
            case ExtCastProtocol.TYPE_SYSTEM_ACTION: {
                ExtCastProtocol.SystemActionMsg msg = ExtCastProtocol.decodeSystemAction(f.payload);
                if (listener != null) listener.onSystemAction(sid, msg);
                break;
            }
            case ExtCastProtocol.TYPE_FILE_TRANSFER_CHUNK: {
                ExtCastProtocol.FileTransferChunkMsg msg = ExtCastProtocol.decodeFileTransferChunk(f.payload);
                if (listener != null) listener.onFileTransferChunk(sid, msg);
                break;
            }
            case ExtCastProtocol.TYPE_REQUEST_WIFI_UPGRADE: {
                handleWifiUpgradeRequest(session);
                break;
            }
            case ExtCastProtocol.TYPE_LIST_APPS: {
                if (listener != null) listener.onListApps(sid);
                break;
            }
            case ExtCastProtocol.TYPE_WINDOW_ACTION: {
                ExtCastProtocol.WindowActionMsg msg = ExtCastProtocol.decodeWindowAction(f.payload);
                if (listener != null) listener.onWindowAction(sid, msg);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Handles ExtCast v1.1 0x29 REQUEST_WIFI_UPGRADE.
     * SECURITY: Strictly gated to authenticated USB/ADB LocalSocket sessions ONLY!
     */
    private void handleWifiUpgradeRequest(ClientSession session) {
        long ts = SystemClock.elapsedRealtimeNanos() / 1000L;
        if (!session.isAdbLocalSocket) {
            System.err.println("EXTBRIDGE_WIFI_UPGRADE_DENIED non-ADB sessionId=" + session.sessionId);
            ExtCastProtocol.WifiUpgradeReadyMsg denied = new ExtCastProtocol.WifiUpgradeReadyMsg(
                    false, "", 0, "", "", "REQUEST_WIFI_UPGRADE is only allowed over authenticated ADB/USB sessions");
            session.enqueue(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    ExtCastProtocol.encodeWifiUpgradeReady(denied));
            return;
        }

        WifiConfigStatus st = configureWifi(true, wifiPort, false);
        if (!st.enabled) {
            ExtCastProtocol.WifiUpgradeReadyMsg err = new ExtCastProtocol.WifiUpgradeReadyMsg(
                    false, "", st.port, st.certFingerprint, "", "Failed to start Wi-Fi TLS listener");
            session.enqueue(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    ExtCastProtocol.encodeWifiUpgradeReady(err));
            return;
        }

        String lanIp = detectLanIpv4Address();
        if (lanIp.isEmpty()) {
            ExtCastProtocol.WifiUpgradeReadyMsg noIp = new ExtCastProtocol.WifiUpgradeReadyMsg(
                    false, "", st.port, st.certFingerprint, "", "No non-loopback LAN IPv4 address detected");
            session.enqueue(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    ExtCastProtocol.encodeWifiUpgradeReady(noIp));
            return;
        }

        String token = trustStore.issueTokenForAuthenticatedPeer(session.clientId, session.clientName);
        if (token.isEmpty()) {
            ExtCastProtocol.WifiUpgradeReadyMsg noTok = new ExtCastProtocol.WifiUpgradeReadyMsg(
                    false, lanIp, st.port, st.certFingerprint, "", "Failed to issue trusted authToken");
            session.enqueue(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                    ExtCastProtocol.encodeWifiUpgradeReady(noTok));
            return;
        }

        ExtCastProtocol.WifiUpgradeReadyMsg ready = new ExtCastProtocol.WifiUpgradeReadyMsg(
                true, lanIp, st.port, st.certFingerprint, token, "");
        session.enqueue(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, ts,
                ExtCastProtocol.encodeWifiUpgradeReady(ready));
        System.out.println("EXTBRIDGE_WIFI_UPGRADE_READY host=" + lanIp + ":" + st.port
                + " clientId=" + session.clientId);
        if (listener != null) {
            listener.onWifiUpgradeEnabled();
        }
    }

    /**
     * Detects the phone's best non-loopback LAN IPv4 address (strongly preferring wlan/ap/eth
     * RFC1918 addresses over cellular rmnet or VPN tun interfaces).
     */
    public static String detectLanIpv4Address() {
        String bestWlan = "";
        String bestEth = "";
        String bestSiteLocal = "";
        String bestOther = "";
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (nic == null) continue;
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                } catch (SocketException ignored) {
                    continue;
                }
                String name = (nic.getName() != null) ? nic.getName().toLowerCase(Locale.US) : "";
                if (name.startsWith("dummy") || name.startsWith("lo")) continue;

                boolean isWlan = name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap");
                boolean isEth = name.startsWith("eth") || name.startsWith("en")
                        || name.startsWith("rndis") || name.startsWith("usb");
                boolean isTunnelOrCell = name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp");

                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;

                    if (isWlan && addr.isSiteLocalAddress()) {
                        if (bestWlan.isEmpty()) bestWlan = ip;
                    } else if (isEth && addr.isSiteLocalAddress()) {
                        if (bestEth.isEmpty()) bestEth = ip;
                    } else if (!isTunnelOrCell && addr.isSiteLocalAddress()) {
                        if (bestSiteLocal.isEmpty()) bestSiteLocal = ip;
                    } else if (!isTunnelOrCell) {
                        if (bestOther.isEmpty()) bestOther = ip;
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("EXTBRIDGE_LAN_IP_DETECT_ERR: " + e.getMessage());
        }

        if (!bestWlan.isEmpty()) return bestWlan;
        if (!bestEth.isEmpty()) return bestEth;
        if (!bestSiteLocal.isEmpty()) return bestSiteLocal;
        return bestOther;
    }

    private void sendAuthFailureAndClose(OutputStream out, Closeable sock, String msg) {
        try {
            ExtCastProtocol.AuthResultMsg fail = new ExtCastProtocol.AuthResultMsg(
                    false, "", msg, deviceName, getCertFingerprint());
            writeAuthResultImmediate(out, fail);
        } catch (IOException e) {
            System.err.println("EXTBRIDGE_AUTH_FAIL_SEND_WARN: " + e.getMessage());
        } finally {
            ClientSession.closeTransportQuietly(sock);
        }
    }

    private static void writeAuthResultImmediate(OutputStream out, ExtCastProtocol.AuthResultMsg msg) throws IOException {
        byte[] payload = ExtCastProtocol.encodeAuthResult(msg);
        ExtCastProtocol.writeFrame(out, ExtCastProtocol.TYPE_AUTH_RESULT, 0,
                SystemClock.elapsedRealtimeNanos() / 1000L, payload, 0, payload.length);
        out.flush();
    }

    private static void applySecureTlsProtocols(String[] supported, Consumer<String[]> setter) {
        List<String> list = new ArrayList<>();
        if (supported != null) {
            for (String p : supported) {
                if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                    list.add(p);
                }
            }
        }
        if (!list.isEmpty()) {
            setter.accept(list.toArray(new String[0]));
        }
    }

    private static String resolveDeviceName() {
        try {
            String model = Build.MODEL;
            if (model != null && !model.trim().isEmpty()) {
                return model.trim();
            }
        } catch (Throwable ignored) {}
        return "Android";
    }
}
