package com.extbridge.daemon.ipc;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.util.CryptoHex;
import com.extbridge.daemon.util.ShellCommand;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cert-pinned IPC bridge to {@code com.extbridge.helper}: SHA-256 certificate verification,
 * caller trust checks, ContentProvider/Broadcast Binder push, death linking, and state-sync pings.
 */
public final class HelperClientBridge {
    private static final long HELPER_UID_CACHE_MS = 5000L;
    private static final long PUSH_RATE_LIMIT_MS = 700L;
    private static final long TICK_PUSH_INTERVAL_MS = 2500L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final ExecutorService bgIoExec;
    private final ExecutorService binderPushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtBinderPush");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService statePushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtStatePush");
        t.setDaemon(true);
        return t;
    });
    private final Object pendingPushLock = new Object();
    private String pendingWinJson;
    private long pendingStateSeq = -1L;
    private boolean statePushScheduled;

    private volatile IBinder ctlBinder;
    private volatile IBinder helperProcToken;
    private volatile Runnable onHelperDeath;
    private volatile int verifiedHelperUid = -1;
    private volatile long lastHelperUidVerifyMs = 0L;
    private volatile boolean preferExternalProviderCall = false;
    private volatile boolean skipDirectStartService = false;
    private final AtomicLong lastBinderPushAttemptMs = new AtomicLong(0L);

    public HelperClientBridge(AndroidServices services, DaemonConfig config, ExecutorService bgIoExec) {
        this.services = services;
        this.config = config;
        this.bgIoExec = bgIoExec;
    }

    public static boolean isTrustedDaemonServerUid(int uid) {
        return uid == 0 || uid == 1000 || uid == 2000;
    }

    public static int verifyHelperUid(Context ctx) {
        if (ctx == null) return -1;
        try {
            PackageManager pm = ctx.getPackageManager();
            int curUid = pm.getPackageUid(BridgeBuildConfig.HELPER_PACKAGE, 0);
            if (curUid <= 0) return -1;
            PackageInfo pi = pm.getPackageInfo(
                    BridgeBuildConfig.HELPER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = (pi != null) ? pi.signingInfo : null;
            if (si == null || si.hasMultipleSigners()) return -1;
            Signature[] sigs = si.getApkContentsSigners();
            if (sigs == null || sigs.length != 1 || sigs[0] == null) return -1;
            String hex = CryptoHex.sha256Hex(sigs[0].toByteArray());
            if (!BridgeBuildConfig.EXPECTED_HELPER_CERT_SHA256.equalsIgnoreCase(hex)) {
                System.err.println("EXTBRIDGE_SECURITY_REJECT untrusted "
                        + BridgeBuildConfig.HELPER_PACKAGE + " cert=" + hex);
                return -1;
            }
            return curUid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_VERIFY_HELPER_ERR: " + e.getMessage());
            return -1;
        }
    }

    public void setControlBinder(IBinder binder) { this.ctlBinder = binder; }
    public void setOnHelperDeath(Runnable callback) { this.onHelperDeath = callback; }

    public int getVerifiedHelperUid() {
        if (services.context == null) return -1;
        try {
            int curUid = services.context.getPackageManager().getPackageUid(BridgeBuildConfig.HELPER_PACKAGE, 0);
            if (curUid <= 0) {
                verifiedHelperUid = -1;
                return -1;
            }
            long now = SystemClock.uptimeMillis();
            if (curUid == verifiedHelperUid && (now - lastHelperUidVerifyMs) < HELPER_UID_CACHE_MS) {
                return curUid;
            }
            verifiedHelperUid = verifyHelperUid(services.context);
            lastHelperUidVerifyMs = now;
            return verifiedHelperUid;
        } catch (PackageManager.NameNotFoundException e) {
            verifiedHelperUid = -1;
            return -1;
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_HELPER_UID_ERR: " + e.getMessage());
            verifiedHelperUid = -1;
            return -1;
        }
    }

    public boolean isTrustedCaller(int callingUid) {
        if (callingUid < 0) return false;
        if (callingUid == android.os.Process.myUid() || isTrustedDaemonServerUid(callingUid)) return true;
        int verifiedUid = getVerifiedHelperUid();
        return verifiedUid > 0 && callingUid == verifiedUid;
    }

    public boolean isHelperAlive() {
        IBinder tok = helperProcToken;
        return tok != null && tok.pingBinder();
    }

    public boolean isHelperProcessRunning() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) return false;
        List<ActivityManager.RunningAppProcessInfo> procs = services.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo info : procs) {
            if (info != null && info.uid == helperUid
                    && BridgeBuildConfig.HELPER_PACKAGE.equals(info.processName)) {
                return true;
            }
        }
        return false;
    }

    public boolean isVerifiedHelperProviderOwner() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0 || services.context == null) return false;
        try {
            ProviderInfo pi = services.context.getPackageManager()
                    .resolveContentProvider(BridgeProtocol.HELPER_PROVIDER_AUTHORITY, 0);
            return pi != null && pi.applicationInfo != null
                    && pi.applicationInfo.uid == helperUid
                    && BridgeBuildConfig.HELPER_PACKAGE.equals(pi.packageName);
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_PROVIDER_OWNER_ERR: " + e.getMessage());
            return false;
        }
    }

    public Bundle callHelperProvider(String method, Bundle extras) {
        if (services.context == null || method == null || !isVerifiedHelperProviderOwner()) return null;
        if (preferExternalProviderCall) {
            return AndroidServices.callContentProviderExternal(
                    BridgeProtocol.HELPER_PROVIDER_AUTHORITY, method, null, extras);
        }
        Bundle res = null;
        try {
            ContentProviderClient cpc = services.context.getContentResolver()
                    .acquireUnstableContentProviderClient(BridgeProtocol.HELPER_PROVIDER_AUTHORITY);
            if (cpc != null) {
                try { res = cpc.call(method, null, extras); }
                finally { cpc.close(); }
            }
            if (res == null) {
                res = services.context.getContentResolver().call(
                        Uri.parse("content://" + BridgeProtocol.HELPER_PROVIDER_AUTHORITY), method, null, extras);
            }
        } catch (RemoteException | RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unable to find app for caller")) {
                preferExternalProviderCall = true;
            }
            System.err.println("EXTBRIDGE_PROVIDER_CALL_WARN method=" + method + ": " + msg);
        }
        if ((res == null || !res.getBoolean("ok", false)) && isVerifiedHelperProviderOwner()) {
            res = AndroidServices.callContentProviderExternal(
                    BridgeProtocol.HELPER_PROVIDER_AUTHORITY, method, null, extras);
        }
        return res;
    }

    public void enqueuePushBinder(boolean forceImmediate) {
        long now = SystemClock.uptimeMillis();
        if (!forceImmediate && (now - lastBinderPushAttemptMs.get()) < PUSH_RATE_LIMIT_MS) return;
        lastBinderPushAttemptMs.set(now);
        binderPushExec.execute(this::pushBinderToHelper);
    }

    public void maybePushBinderOnTick(long nowMs) {
        if (!isHelperAlive() && (nowMs - lastBinderPushAttemptMs.get() >= TICK_PUSH_INTERVAL_MS)
                && isHelperProcessRunning()) {
            enqueuePushBinder(false);
        }
    }

    public void startHelperService() {
        if (skipDirectStartService || services.context == null || getVerifiedHelperUid() <= 0) return;
        try {
            Intent svc = new Intent().setComponent(new ComponentName(
                    BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_SERVICE_CLASS));
            if (Build.VERSION.SDK_INT >= 26) services.context.startForegroundService(svc);
            else services.context.startService(svc);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unable to find app for caller")) {
                skipDirectStartService = true;
            }
            System.err.println("EXTBRIDGE_START_HELPER_SVC_WARN: " + msg);
        }
    }

    public void notifyHelperSync() {
        if (config.shuttingDown || !isHelperAlive()) return;
        statePushExec.execute(() -> {
            if (!config.shuttingDown && isHelperAlive()) {
                callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SYNC_STATE, null);
            }
        });
    }

    /**
     * Coalescing zero-roundtrip push of the latest window snapshot JSON and monotonic {@code stateSeq}
     * directly inside {@code PROVIDER_METHOD_SYNC_STATE}, giving the Helper sub-30ms focus/bounds updates.
     */
    public void pushWindowState(String winJson, long stateSeq) {
        if (config.shuttingDown || !isHelperAlive() || winJson == null) return;
        boolean shouldSchedule;
        synchronized (pendingPushLock) {
            pendingWinJson = winJson;
            pendingStateSeq = stateSeq;
            shouldSchedule = !statePushScheduled;
            statePushScheduled = true;
        }
        if (!shouldSchedule) return;
        statePushExec.execute(() -> {
            while (!config.shuttingDown && isHelperAlive()) {
                String json;
                long seq;
                synchronized (pendingPushLock) {
                    json = pendingWinJson;
                    seq = pendingStateSeq;
                    pendingWinJson = null;
                    if (json == null) {
                        statePushScheduled = false;
                        return;
                    }
                }
                Bundle extras = new Bundle();
                extras.putString(BridgeProtocol.EXTRA_SYNC_WIN_JSON, json);
                extras.putLong(BridgeProtocol.EXTRA_SYNC_STATE_SEQ, seq);
                callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SYNC_STATE, extras);
            }
            synchronized (pendingPushLock) {
                pendingWinJson = null;
                statePushScheduled = false;
            }
        });
    }

    /** Pushes a global DeX desktop keyboard shortcut action directly to the Helper overlay shell. */
    public void pushDesktopShortcut(String shortcutAction) {
        if (config.shuttingDown || !isHelperAlive() || shortcutAction == null || shortcutAction.isEmpty()) return;
        statePushExec.execute(() -> {
            if (config.shuttingDown || !isHelperAlive()) return;
            Bundle extras = new Bundle();
            extras.putString(BridgeProtocol.EXTRA_SYNC_SHORTCUT, shortcutAction);
            callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SYNC_STATE, extras);
        });
    }

    public void notifyMirrorEdgeCapture(boolean enter, float cx, float cy) {
        if (services.context == null || config.shuttingDown) return;
        Bundle b = new Bundle();
        b.putBoolean("enter", enter);
        b.putFloat("cx", cx);
        b.putFloat("cy", cy);
        callHelperProvider(BridgeProtocol.PROVIDER_METHOD_MIR_EDGE_CAPTURE, b);
    }

    public void grantHelperPrivileges() {
        if (getVerifiedHelperUid() <= 0) return;
        ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "10021", "allow");
        ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "ACCESS_RESTRICTED_SETTINGS", "allow");
        ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "SYSTEM_ALERT_WINDOW", "allow");
        if (Build.VERSION.SDK_INT >= 37) {
            ShellCommand.runOutput("pm", "grant", BridgeBuildConfig.HELPER_PACKAGE, "android.permission.ACCESS_LOCAL_NETWORK");
            ShellCommand.runOutput("appops", "set", BridgeBuildConfig.HELPER_PACKAGE, "ACCESS_LOCAL_NETWORK", "allow");
        }
        AndroidServices.grantBindAppWidgetPermission(BridgeBuildConfig.HELPER_PACKAGE, 0);
    }

    private void pushBinderToHelper() {
        IBinder binder = ctlBinder;
        if (services.context == null || binder == null || config.shuttingDown || getVerifiedHelperUid() <= 0) return;
        try {
            Bundle b = new Bundle();
            b.putBinder("cb", binder);
            Bundle res = isVerifiedHelperProviderOwner()
                    ? callHelperProvider(BridgeProtocol.PROVIDER_METHOD_SET_BINDER, b) : null;
            if (res != null && res.getBoolean("ok", false)) {
                linkHelperProcessToken(res.getBinder("proc"));
            } else {
                sendBinderFallbackBroadcast(b);
            }
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_PUSH_BINDER_ERR: " + e.getMessage());
        }
        startHelperService();
    }

    private void linkHelperProcessToken(IBinder tok) {
        if (tok == null || tok == helperProcToken) return;
        helperProcToken = tok;
        try {
            tok.linkToDeath(() -> {
                if (helperProcToken == tok) {
                    helperProcToken = null;
                    Runnable cb = onHelperDeath;
                    if (cb != null) cb.run();
                }
            }, 0);
        } catch (RemoteException e) {
            System.err.println("EXTBRIDGE_LINK_DEATH_WARN: " + e.getMessage());
            if (helperProcToken == tok) helperProcToken = null;
        }
    }

    private void sendBinderFallbackBroadcast(Bundle extras) {
        if (services.context == null || extras == null || getVerifiedHelperUid() <= 0) return;
        try {
            Intent bi = new Intent(BridgeProtocol.HELPER_ACTION_BINDER_PUSH)
                    .setComponent(new ComponentName(BridgeBuildConfig.HELPER_PACKAGE, BridgeProtocol.HELPER_CONTROL_RECEIVER_CLASS))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                services.context.sendBroadcast(bi, "android.permission.INJECT_EVENTS", opts);
            } else {
                services.context.sendBroadcast(bi, "android.permission.INJECT_EVENTS");
            }
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_BINDER_BCAST_ERR: " + e.getMessage());
        }
    }
}
