package com.extbridge.daemon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.extbridge.daemon.display.CompatPolicyController;
import com.extbridge.daemon.display.LocalDexController;
import com.extbridge.daemon.display.NativeMirrorController;
import com.extbridge.daemon.display.PhysicalDisplayController;
import com.extbridge.daemon.display.SystemControlService;
import com.extbridge.daemon.input.InputInjector;
import com.extbridge.daemon.input.LocalInputController;
import com.extbridge.daemon.ipc.BridgeBinderService;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.ipc.DaemonControlSocket;
import com.extbridge.daemon.ipc.HelperClientBridge;
import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.stream.RemoteSessionCoordinator;
import com.extbridge.daemon.window.DesktopWindowManager;
import com.extbridge.daemon.window.TaskWindowController;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin entry point and lifecycle coordinator for the ExtBridge shell daemon.
 *
 * <p>Preserves exact CLI ({@code --verify-helper}, {@code --stop}), fail-closed root drop,
 * single-instance abstract LocalSocket ({@code com.extbridge.daemon.ctl}), {@code NEED_BINDER}
 * broadcast receiver, shutdown hook, concrete module wiring, and 1.5s reconciliation tick.
 */
public final class ExtBridge implements Runnable {
    private static final long TICK_INTERVAL_MS = 1500L;
    private static final long NEED_BINDER_DEBOUNCE_MS = 500L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final Handler mainHandler;
    private final ExecutorService bgIoExec;
    private final HelperClientBridge helperBridge;
    private final CompatPolicyController compatPolicy;
    private final SystemControlService systemControl;
    private final InputInjector injector;
    private final LocalInputController localInput;
    private final TaskWindowController taskController;
    private final NativeMirrorController mirrorDisplay;
    private final PhysicalDisplayController physicalDisplay;
    private final LocalDexController localDex;
    private final RemoteSessionCoordinator remoteCoordinator;

    private final AtomicBoolean shutdownCleanupDone = new AtomicBoolean(false);
    private volatile long lastNeedBinderRxMs = 0L;

    private ExtBridge(AndroidServices services) {
        this.services = services;
        this.config = new DaemonConfig();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.bgIoExec = Executors.newSingleThreadExecutor(r -> newDaemonThread(r, "ExtBgIo"));
        ScheduledExecutorService bgSchedExec = Executors.newSingleThreadScheduledExecutor(r -> newDaemonThread(r, "ExtBgSched"));

        TaskApiAdapter taskApi = new TaskApiAdapter(services.iActivityTaskManager);
        DesktopWindowManager windowManager = new DesktopWindowManager();

        this.helperBridge = new HelperClientBridge(services, config, bgIoExec);
        this.compatPolicy = new CompatPolicyController(services, taskApi, config, bgIoExec);
        this.systemControl = new SystemControlService(services, config, helperBridge);
        this.injector = new InputInjector(services, config, helperBridge);
        this.localInput = new LocalInputController(config, injector, taskApi);
        this.mirrorDisplay = new NativeMirrorController(
                services, config, compatPolicy, systemControl, helperBridge,
                injector, localInput, taskApi, mainHandler, bgIoExec, bgSchedExec);
        this.physicalDisplay = new PhysicalDisplayController(
                services, config, compatPolicy, systemControl, mirrorDisplay,
                helperBridge, injector, localInput, taskApi, mainHandler, bgIoExec);
        this.localDex = new LocalDexController(
                services, config, compatPolicy, helperBridge, injector, localInput,
                taskApi, mainHandler, bgIoExec);
        TaskWindowController taskController = new TaskWindowController(
                services, config, taskApi, windowManager, compatPolicy,
                helperBridge, mainHandler, bgIoExec);
        this.taskController = taskController;
        this.remoteCoordinator = new RemoteSessionCoordinator(
                services, config, compatPolicy, systemControl, helperBridge,
                injector, localInput, physicalDisplay, mirrorDisplay, taskController,
                taskApi, mainHandler, bgIoExec);
        BridgeBinderService binderService = new BridgeBinderService(
                config, helperBridge, physicalDisplay, localDex, mirrorDisplay, compatPolicy,
                systemControl, injector, localInput, taskController, remoteCoordinator,
                this::tick, this::performShutdownCleanup);

        localInput.setMirrorController(mirrorDisplay);
        localInput.setDesktopHooks(taskController::scheduleFastFocusSync, taskController::handleDesktopShortcut);
        helperBridge.setControlBinder(binderService);
        helperBridge.setOnHelperDeath(() -> {
            localInput.releaseStaleMouseDragOnHelperDeath();
            if (localDex.isActive()) localDex.stop();
        });
        systemControl.setOnDimStateChanged(() -> remoteCoordinator.pushDesktopDeviceState(true));
        taskController.setOnRemoteStateDirty(() -> remoteCoordinator.pushDesktopDeviceState(true));
        taskController.setOnSendBackKey(() -> localInput.injectKey(android.view.KeyEvent.KEYCODE_BACK, 0));
        physicalDisplay.wireCollaborators(
                taskController, this::tick,
                remoteCoordinator::applyDesktopResizeFromConfig,
                localDex::resizeFromConfigLocked);
        localDex.wireCollaborators(
                taskController,
                remoteCoordinator::isDesktopSessionActive,
                this::teardownPhysicalForLocalLocked);
        remoteCoordinator.wireCallbacks(
                this::teardownPhysicalForRemoteLocked,
                () -> physicalDisplay.cleanupDisplayLocked(false, false),
                this::tick);
        injector.setExtraDisplayHooks(
                remoteCoordinator::resolveStandaloneUniqueId,
                remoteCoordinator::resolveStandaloneDims,
                remoteCoordinator::openUhidForStandaloneDisplays,
                remoteCoordinator::pushAllDeviceStates);
    }

    @SuppressWarnings("deprecation") // Looper.prepareMainLooper() is required in standalone app_process daemons.
    public static void main(String[] args) {
        try {
            dropRootFailClosed();
            if (args != null && args.length > 0) {
                if ("--verify-helper".equals(args[0])) {
                    Looper.prepareMainLooper();
                    int vUid = HelperClientBridge.verifyHelperUid(AndroidServices.createSystemContext());
                    System.exit(vUid > 0 ? 0 : 3);
                    return;
                }
                if ("--stop".equals(args[0])) {
                    DaemonControlSocket.sendShutdown(6500);
                    System.exit(0);
                    return;
                }
            }
            if (!DaemonControlSocket.acquireAndStart()) {
                System.err.println("EXTBRIDGE_LOCK_BUSY another daemon instance holds @" + BridgeProtocol.CTL_SOCKET_NAME);
                System.exit(2);
                return;
            }
            Looper.prepareMainLooper();
            installUncaughtExceptionHandler();

            ExtBridge daemon = new ExtBridge(AndroidServices.createForDaemon());
            DaemonControlSocket.bindHandlers(
                    () -> daemon.config.shuttingDown,
                    daemon.helperBridge::isTrustedCaller,
                    daemon::performShutdownCleanup,
                    () -> daemon.helperBridge.enqueuePushBinder(false));
            daemon.start();
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private void start() {
        injector.unbindInput();
        systemControl.restoreOrphanScreenDimBackup();
        boolean firstConf = config.loadAll();
        localInput.resetCursorCenter(config.virtW, config.virtH);
        compatPolicy.maybeScheduleInitialHeal(firstConf);

        remoteCoordinator.start();
        taskController.startTaskStackObserver();
        helperBridge.enqueuePushBinder(true);
        for (long delayMs : new long[]{400L, 1100L, 2400L}) {
            mainHandler.postDelayed(() -> {
                if (!config.shuttingDown && !helperBridge.isHelperAlive()) helperBridge.enqueuePushBinder(true);
            }, delayMs);
        }

        registerNeedBinderReceiver();
        Runtime.getRuntime().addShutdownHook(new Thread(this::performShutdownCleanup, "ExtShutdownHook"));

        System.out.println("EXTBRIDGE_V3_DAEMON_STARTED cfg=" + config.virtW + "x" + config.virtH
                + "@" + config.virtDpi + "dpi," + config.virtFps + "fps");
        System.out.flush();

        bgIoExec.execute(() -> {
            helperBridge.grantHelperPrivileges();
            compatPolicy.healStrayCompatOverrides(false);
            compatPolicy.sanitizeSystemHomeTasks(true, config.vdId, mirrorDisplay.getSideVdId(), config.extId);
        });
        mainHandler.post(this);
    }

    @Override
    public void run() {
        if (config.shuttingDown) return;
        try {
            tick();
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_TICK_ERR: " + t);
            t.printStackTrace();
        }
        if (!config.shuttingDown) mainHandler.postDelayed(this, TICK_INTERVAL_MS);
    }

    private void tick() {
        long nowMs = SystemClock.uptimeMillis();
        helperBridge.maybePushBinderOnTick(nowMs);
        compatPolicy.maybeScheduleHomeSanitize(nowMs, config.vdId, mirrorDisplay.getSideVdId(), config.extId);

        if (!config.shuttingDown && !remoteCoordinator.isDesktopSessionActive() && !localDex.isActive()) {
            // Promote native EDID mode strictly OUTSIDE displayLock (avoids sleeping under lock).
            physicalDisplay.maybePromoteNativeEdidBeforeLock(physicalDisplay.findExternalDisplay());
        }

        synchronized (config.displayLock) {
            if (config.shuttingDown) return;
            if (remoteCoordinator.isDesktopSessionActive()) {
                physicalDisplay.resetLastPushedExtId();
                remoteCoordinator.tickRemoteLocked();
            } else if (localDex.isActive()) {
                physicalDisplay.resetLastPushedExtId();
                localDex.tickLocalLocked();
            } else {
                physicalDisplay.tickPhysicalLocked();
            }
        }
    }

    private void teardownPhysicalForLocalLocked() {
        if (config.extId >= 0 || physicalDisplay.hasActiveDisplay() || mirrorDisplay.hasActiveResources()) {
            physicalDisplay.cleanupDisplayLocked(false, true);
        }
    }

    private void teardownPhysicalForRemoteLocked() {
        if (localDex.isActive()) {
            localDex.stopLocked(false);
        }
        if (config.extId >= 0 || physicalDisplay.hasActiveDisplay() || mirrorDisplay.hasActiveResources()) {
            physicalDisplay.cleanupDisplayLocked(false, true);
        }
    }

    private void performShutdownCleanup() {
        config.shuttingDown = true;
        if (!shutdownCleanupDone.compareAndSet(false, true)) return;
        try {
            taskController.stopTaskStackObserver();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_SHUTDOWN_OBSERVER_ERR: " + e.getMessage());
        }
        try {
            remoteCoordinator.shutdown();
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_SHUTDOWN_REMOTE_ERR: " + e.getMessage());
        }
        synchronized (config.displayLock) {
            localDex.stopLocked(false);
            physicalDisplay.cleanupDisplayLocked(true, false);
        }
    }

    private void registerNeedBinderReceiver() {
        IntentFilter flt = new IntentFilter(BridgeProtocol.ACTION_NEED_BINDER);
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i == null || !BridgeProtocol.ACTION_NEED_BINDER.equals(i.getAction())) return;
                if (Build.VERSION.SDK_INT >= 34 && !helperBridge.isTrustedCaller(getSentFromUid())) return;
                long now = SystemClock.uptimeMillis();
                if (now - lastNeedBinderRxMs < NEED_BINDER_DEBOUNCE_MS) return;
                lastNeedBinderRxMs = now;
                helperBridge.enqueuePushBinder(false);
            }
        };
        registerReceiverOnContext(services.context, rx, flt);
        if (services.systemContext != null && services.systemContext != services.context) {
            registerReceiverOnContext(services.systemContext, rx, flt);
        }
    }

    private static void registerReceiverOnContext(Context ctx, BroadcastReceiver rx, IntentFilter flt) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, flt, Context.RECEIVER_EXPORTED);
            else ctx.registerReceiver(rx, flt);
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_REG_RECEIVER_WARN: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation") // android.system.Os.setgid/setuid are the only JDK-level root-drop syscalls.
    private static void dropRootFailClosed() {
        int initUid = android.os.Process.myUid();
        int osUid = -1;
        try { osUid = android.system.Os.getuid(); } catch (RuntimeException ignored) {}
        if (initUid != 0 && osUid != 0) return;
        try {
            dropSupplementaryGroupsQuietly();
            android.system.Os.setgid(2000);
            android.system.Os.setuid(2000);
            int curUid = android.system.Os.getuid(), curEuid = android.system.Os.geteuid();
            int curGid = android.system.Os.getgid(), curEgid = android.system.Os.getegid();
            if (android.os.Process.myUid() != 2000 || curUid != 2000 || curEuid != 2000 || curGid != 2000 || curEgid != 2000) {
                System.err.println("EXTBRIDGE_FATAL_DROP_ROOT_INCOMPLETE uid=" + curUid + " gid=" + curGid);
                System.exit(1);
                return;
            }
            try (FileOutputStream out = new FileOutputStream("/proc/thread-self/attr/current")) {
                out.write("u:r:shell:s0\0".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
                // Best-effort SELinux domain transition when launched without runcon.
            }
            System.out.println("EXTBRIDGE_ROOT_DROPPED_TO_SHELL uid=2000 gid=2000");
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_FATAL_DROP_ROOT_FAILED: " + t);
            System.exit(1);
        }
    }

    private static void dropSupplementaryGroupsQuietly() {
        try {
            Object osObj = Class.forName("libcore.io.Libcore").getField("os").get(null);
            if (osObj == null) return;
            for (Method m : osObj.getClass().getMethods()) {
                if ("setgroups".equals(m.getName()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == int[].class) {
                    m.invoke(osObj, (Object) new int[]{2000});
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("EXTBRIDGE_SETGROUPS_WARN: " + e.getMessage());
        }
    }

    private static void installUncaughtExceptionHandler() {
        Thread mainThread = Thread.currentThread();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("EXTBRIDGE_UNCAUGHT_EXCEPTION thread=" + (thread != null ? thread.getName() : "unknown") + ": " + throwable);
            if (throwable != null) throwable.printStackTrace();
            if (thread == mainThread && prev != null) prev.uncaughtException(thread, throwable);
        });
    }

    private static Thread newDaemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
