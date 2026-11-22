package io.github.lengsukq.opaldesk.daemon.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Surface;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.DaemonConfig.DisplayStatusSnapshot;
import io.github.lengsukq.opaldesk.daemon.display.CompatPolicyController;
import io.github.lengsukq.opaldesk.daemon.display.LocalDexController;
import io.github.lengsukq.opaldesk.daemon.display.NativeMirrorController;
import io.github.lengsukq.opaldesk.daemon.display.NativeMirrorController.MirrorGeom;
import io.github.lengsukq.opaldesk.daemon.display.PhysicalDisplayController;
import io.github.lengsukq.opaldesk.daemon.display.SystemControlService;
import io.github.lengsukq.opaldesk.daemon.input.InputInjector;
import io.github.lengsukq.opaldesk.daemon.input.LocalInputController;
import io.github.lengsukq.opaldesk.daemon.stream.RemoteSessionCoordinator;
import io.github.lengsukq.opaldesk.daemon.stream.RemoteSessionCoordinator.RemoteStatusSnapshot;
import io.github.lengsukq.opaldesk.daemon.stream.RemoteStreamServer;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager;
import io.github.lengsukq.opaldesk.daemon.window.DesktopWindowManager.TwoSlotSplitSnapshot;
import io.github.lengsukq.opaldesk.daemon.window.TaskWindowController;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine;

/**
 * Binder IPC service exposing {@code io.github.lengsukq.opaldesk.daemon.IBridgeControl} to trusted callers
 * (shell/root/system and the cert-pinned {@code io.github.lengsukq.opaldesk} app).
 *
 * <p>Preserves exact Parcel ABI layouts for all active transaction codes (10..56, 99; excluding
 * removed obsolete codes 13, 14, 19, 36) and reads consistent, lock-protected snapshots for
 * codes 15 and 35 so replies never contain torn state.
 */
public final class BridgeBinderService extends Binder {
    private final DaemonConfig config;
    private final HelperClientBridge helperBridge;
    private final PhysicalDisplayController physicalDisplay;
    private final LocalDexController localDex;
    private final NativeMirrorController mirrorDisplay;
    private final CompatPolicyController compatPolicy;
    private final SystemControlService systemControl;
    private final InputInjector injector;
    private final LocalInputController localInput;
    private final TaskWindowController taskController;
    private final RemoteSessionCoordinator remoteCoordinator;
    private final Runnable tickTrigger;
    private final Runnable shutdownTrigger;

    public BridgeBinderService(
            DaemonConfig config, HelperClientBridge helperBridge,
            PhysicalDisplayController physicalDisplay, LocalDexController localDex,
            NativeMirrorController mirrorDisplay,
            CompatPolicyController compatPolicy, SystemControlService systemControl,
            InputInjector injector, LocalInputController localInput,
            TaskWindowController taskController, RemoteSessionCoordinator remoteCoordinator,
            Runnable tickTrigger, Runnable shutdownTrigger) {
        this.config = config;
        this.helperBridge = helperBridge;
        this.physicalDisplay = physicalDisplay;
        this.localDex = localDex;
        this.mirrorDisplay = mirrorDisplay;
        this.compatPolicy = compatPolicy;
        this.systemControl = systemControl;
        this.injector = injector;
        this.localInput = localInput;
        this.taskController = taskController;
        this.remoteCoordinator = remoteCoordinator;
        this.tickTrigger = tickTrigger;
        this.shutdownTrigger = shutdownTrigger;
        attachInterface(null, BridgeProtocol.BINDER_DESC);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(BridgeProtocol.BINDER_DESC);
            return true;
        }
        if (!helperBridge.isTrustedCaller(Binder.getCallingUid())) {
            throw new SecurityException("Untrusted OpalDesk caller uid=" + Binder.getCallingUid());
        }
        if (code >= 10 && code <= 99) {
            if (data == null) return false;
            data.enforceInterface(BridgeProtocol.BINDER_DESC);
        }

        switch (code) {
            // ---- Display, Mirror & Rotation Controls ----
            case BridgeProtocol.TX_GET_STATUS:
                writeDisplayStatus(reply);
                return true;
            case BridgeProtocol.TX_APPLY_CONFIG:
                handleApplyConfig(data, reply);
                return true;
            case BridgeProtocol.TX_REBUILD:
                config.forceRebuild = true;
                mirrorDisplay.resetSideVdAttempt();
                tickTrigger.run();
                writeInt(reply, 1);
                return true;
            case BridgeProtocol.TX_SET_MIRROR_MODE:
                writeInt(reply, handleSetMirrorMode(data.readInt()));
                return true;
            case BridgeProtocol.TX_SET_FULLSCREEN_POLICY:
                writeInt(reply, handleSetFullscreenPolicy(data.readInt()));
                return true;
            case BridgeProtocol.TX_SET_ROTATION:
                writeInt(reply, (localDex != null && localDex.isActive()) ? 0 : physicalDisplay.setRotation(data.readInt()));
                return true;
            case BridgeProtocol.TX_SET_MIRROR_SIDE_MODE:
                writeInt(reply, (localDex != null && localDex.isActive()) ? config.mirrorSideMode : handleSetMirrorSideMode(data.readInt()));
                return true;

            // ---- Input & Mirror Sidebar Input ----
            case BridgeProtocol.TX_TOGGLE_INPUT:
                if (localDex != null && localDex.isActive()) {
                    writeInt(reply, config.inputToExt);
                    return true;
                }
                config.inputToExt = (config.inputToExt == 0) ? 1 : 0;
                injector.syncInput();
                writeInt(reply, config.inputToExt);
                return true;
            case BridgeProtocol.TX_SEND_BACK:
                localInput.injectKey(KeyEvent.KEYCODE_BACK, 0);
                return true;
            case BridgeProtocol.TX_SEND_MOUSE:
                localInput.injectMouseAction(data.readInt(), data.readFloat(), data.readFloat());
                if (reply != null) {
                    int[] cur = localInput.getCursorSnapshot();
                    reply.writeInt(cur[0]);
                    reply.writeInt(cur[1]);
                    reply.writeInt(injector.getLastInjectStatus());
                }
                return true;
            case BridgeProtocol.TX_SEND_KEY:
                localInput.handleKeyCommand(data.readInt(), data.readInt(), data.readInt(), data.readString());
                writeInt(reply, 1);
                return true;
            case BridgeProtocol.TX_SEND_SIDE_TOUCH:
                mirrorDisplay.handleSideDirectTouch(data.readInt(), data.readFloat(), data.readFloat());
                writeInt(reply, 1);
                return true;
            case BridgeProtocol.TX_SEND_CAPTURED_MIRROR_MOUSE:
                writeCursorBoolReply(reply, mirrorDisplay.handleCapturedMirrorMouse(
                        data.readInt(), data.readFloat(), data.readFloat(), data.readFloat()));
                return true;
            case BridgeProtocol.TX_NOTIFY_MIRROR_EDGE_HOVER:
                writeCursorBoolReply(reply, mirrorDisplay.triggerEdgeHoverEntry(data.readFloat(), data.readFloat()));
                return true;
            case BridgeProtocol.TX_SET_INPUT_BACKEND:
                writeInt(reply, injector.setInputBackendMode(data.readInt()));
                return true;

            // ---- Per-App Compat Policies ----
            case BridgeProtocol.TX_PREPARE_APP_LAUNCH: {
                String pkg = data.readString();
                int mode = config.getAppMode(pkg);
                if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR) compatPolicy.applyAppModeCompat(pkg, mode, false);
                writeInt(reply, mode);
                return true;
            }
            case BridgeProtocol.TX_GET_ALL_APP_MODES:
                writeString(reply, config.serializeAppModesSummary());
                return true;
            case BridgeProtocol.TX_SET_APP_MODE:
                writeBool(reply, taskController.setAppModeAndMaybeRestart(data.readString(), data.readInt(), data.readInt() == 1));
                return true;
            case BridgeProtocol.TX_RESET_ALL_APP_MODES:
                compatPolicy.resetAllConfiguredAndActiveCompat();
                helperBridge.notifyHelperSync();
                writeInt(reply, 1);
                return true;

            // ---- Tasks, Windows, Tiling & Legacy 2-Slot Split ----
            case BridgeProtocol.TX_RELAUNCH_LAUNCHER:
                taskController.relaunchLauncherOrMinimize();
                return true;
            case BridgeProtocol.TX_LAUNCH_COMPONENT:
                writeBool(reply, taskController.launchComponentOnVd(data.readString(), data.readString()));
                return true;
            case BridgeProtocol.TX_GET_TASKS:
                taskController.writeTasksToParcel(reply);
                return true;
            case BridgeProtocol.TX_SWITCH_TO_TASK:
                writeBool(reply, taskController.switchToTaskOnVd(data.readInt(), data.readString()));
                return true;
            case BridgeProtocol.TX_REMOVE_TASK:
                writeBool(reply, taskController.removeTaskById(data.readInt()));
                return true;
            case BridgeProtocol.TX_SPLIT_ACTION:
                handleSplitAction(data, reply);
                return true;
            case BridgeProtocol.TX_MINIMIZE_TO_EXT_HOME:
                writeBool(reply, taskController.minimizeToExtHome(data.readInt(), data.readString()));
                return true;
            case BridgeProtocol.TX_CAPTURE_TASK_SNAPSHOT:
                taskController.captureTaskSnapshotToParcel(data.readInt(), data.readString(), data.readInt(), reply);
                return true;
            case BridgeProtocol.TX_GET_MANAGED_WINDOWS:
                writeString(reply, taskController.getManagedWindowsJson());
                return true;
            case BridgeProtocol.TX_LAUNCH_IN_WINDOW_MODE:
                writeBool(reply, taskController.launchInWindowMode(
                        data.readString(), data.readString(), data.readInt(),
                        data.readInt(), data.readInt(), data.readInt(), data.readInt()));
                return true;
            case BridgeProtocol.TX_UPDATE_WINDOW_BOUNDS:
                writeBool(reply, taskController.updateWindowBounds(
                        data.readInt(), data.readString(), data.readInt(),
                        data.readInt(), data.readInt(), data.readInt(), data.readInt(), data.readInt() != 0));
                return true;
            case BridgeProtocol.TX_SET_TILING_LAYOUT:
                writeBool(reply, taskController.setTilingLayout(
                        data.readString(),
                        TilingLayoutEngine.parsePkgsCsv(data.readString()),
                        TilingLayoutEngine.parseRatiosCsv(data.readString())));
                return true;
            case BridgeProtocol.TX_WINDOW_LIFECYCLE_ACTION:
                writeBool(reply, taskController.windowLifecycleAction(data.readInt(), data.readString(), data.readInt()));
                return true;
            case BridgeProtocol.TX_SET_ADAPTIVE_SINGLE_CONFIG:
                taskController.setAdaptiveSingleConfig(data.readInt() != 0, data.readInt());
                writeInt(reply, 1);
                return true;
            case BridgeProtocol.TX_SET_DESKTOP_PREFS: {
                boolean taskbarAutoHide = data.readInt() != 0;
                String launchMode = (data.dataAvail() > 0) ? data.readString() : null;
                taskController.setDesktopPrefs(taskbarAutoHide, launchMode);
                writeInt(reply, 1);
                return true;
            }

            // ---- On-Device Standalone Local DeX Mode ----
            case BridgeProtocol.TX_START_LOCAL_DEX:
                handleStartLocalDex(data, reply);
                return true;
            case BridgeProtocol.TX_STOP_LOCAL_DEX: {
                boolean releaseDisplay = data.readInt() != 0;
                boolean ok = releaseDisplay ? localDex.stop() : localDex.detachSurface();
                writeBool(reply, ok);
                return true;
            }
            case BridgeProtocol.TX_SEND_LOCAL_TOUCH:
                handleSendLocalTouch(data, reply);
                return true;
            case BridgeProtocol.TX_MOVE_WINDOW_LIVE:
                writeBool(reply, taskController.moveWindowLive(
                        data.readInt(), data.readInt(), data.readInt(), data.readInt(), data.readInt()));
                return true;

            // ---- System Status, Controls, Wallpaper & Telemetry ----
            case BridgeProtocol.TX_GET_WALLPAPER:
                if (reply != null) reply.writeByteArray(systemControl.getWallpaperBytes(data.readInt() == 1));
                return true;
            case BridgeProtocol.TX_GET_SYS_STATUS:
                systemControl.writeSysStatusToParcel(reply);
                return true;
            case BridgeProtocol.TX_EXEC_SYS_ACTION: {
                int sysAct = data.readInt();
                int sysArg = data.readInt();
                String sysStr = data.readString();
                if (localDex != null && localDex.isActive() && sysAct == BridgeProtocol.SYS_ACTION_SCREEN_DIM) {
                    writeBool(reply, false);
                    return true;
                }
                writeBool(reply, systemControl.execSysAction(sysAct, sysArg, sysStr));
                return true;
            }
            case BridgeProtocol.TX_GET_MIRROR_TELEMETRY:
                systemControl.writeMirrorTelemetryToParcel(reply);
                return true;
            case BridgeProtocol.TX_ENSURE_APPWIDGET_PERMISSION:
                writeBool(reply, systemControl.grantAppWidgetBind());
                return true;

            // ---- Remote Streaming & Trust Store ----
            case BridgeProtocol.TX_SET_WIFI_REMOTE_CONFIG: {
                RemoteStreamServer.WifiConfigStatus st = remoteCoordinator.configureWifi(
                        data.readInt() != 0, data.readInt(), data.readInt() != 0);
                if (reply != null) {
                    reply.writeInt(st.enabled ? 1 : 0);
                    reply.writeInt(st.port);
                    reply.writeString(st.certFingerprint);
                    reply.writeString(st.activePin);
                }
                return true;
            }
            case BridgeProtocol.TX_LIST_TRUSTED_MAC_CLIENTS:
                writeString(reply, remoteCoordinator.listTrustedClientsSummary());
                return true;
            case BridgeProtocol.TX_REVOKE_TRUSTED_MAC_CLIENT:
                writeBool(reply, remoteCoordinator.revokeTrustedClient(data.readString()));
                return true;
            case BridgeProtocol.TX_DISCONNECT_REMOTE_CLIENT:
                remoteCoordinator.disconnectActiveClient("Disconnected via Helper");
                writeInt(reply, 1);
                return true;
            case BridgeProtocol.TX_SET_REMOTE_STREAM_PREFS:
                remoteCoordinator.applyRemoteStreamPrefs(data.readInt(), data.readInt(), data.readInt() != 0);
                writeInt(reply, 1);
                return true;

            // ---- Shutdown ----
            case BridgeProtocol.TX_SHUTDOWN:
                handleShutdown(reply);
                return true;

            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    private void handleApplyConfig(Parcel data, Parcel reply) {
        int w = data.readInt(), h = data.readInt(), dpi = data.readInt(), fpsX100 = data.readInt(), physModeId = data.readInt();
        int reqRot = (data.dataAvail() > 0) ? data.readInt() : -1;
        boolean ok = physicalDisplay.applyConfig(w, h, dpi, fpsX100, physModeId, reqRot);
        if (reply != null) {
            DisplayStatusSnapshot snap = config.snapshotDisplayStatus();
            reply.writeInt(ok ? 1 : 0);
            reply.writeInt(physicalDisplay.getLastPhysResult());
            reply.writeInt(snap.physW);
            reply.writeInt(snap.physH);
            reply.writeInt(Math.round(snap.physFps * 100f));
        }
    }

    private int handleSetMirrorMode(int req) {
        if (localDex != null && localDex.isActive()) {
            return BridgeProtocol.MIRROR_MODE_DEX;
        }
        int next;
        boolean changed;
        synchronized (config.displayLock) {
            int prev = config.mirrorMode;
            next = (req < 0)
                    ? (prev == BridgeProtocol.MIRROR_MODE_DEX ? BridgeProtocol.MIRROR_MODE_MIRROR : BridgeProtocol.MIRROR_MODE_DEX)
                    : (req == BridgeProtocol.MIRROR_MODE_MIRROR ? BridgeProtocol.MIRROR_MODE_MIRROR : BridgeProtocol.MIRROR_MODE_DEX);
            config.mirrorMode = next;
            changed = (prev != next);
        }
        if (changed) {
            mirrorDisplay.resetSideVdAttempt();
            if (taskController.getWindowManager().isTilingActive()) taskController.exitTilingAndRestore(false);
        }
        if (next != BridgeProtocol.MIRROR_MODE_MIRROR) config.saveConf();
        if (remoteCoordinator.isDesktopSessionActive()) remoteCoordinator.applyDesktopResizeFromConfig();
        else tickTrigger.run();
        helperBridge.notifyHelperSync();
        return next;
    }

    private int handleSetFullscreenPolicy(int req) {
        int next;
        boolean changed;
        synchronized (config.displayLock) {
            next = (req < 0) ? (config.fullscreenPolicy == 0 ? 1 : 0) : (req != 0 ? 1 : 0);
            changed = (next != config.fullscreenPolicy);
            if (changed) config.fullscreenPolicy = next;
        }
        if (changed) {
            config.saveConf();
            if (next == 0) compatPolicy.resetAppFullscreenCompatAll();
            int vdId = config.vdId;
            if (vdId > 0) compatPolicy.applyDisplayFullscreenPolicy(vdId, next == 1);
            if (config.mirrorMode != BridgeProtocol.MIRROR_MODE_MIRROR && vdId > 0) {
                config.forceRebuild = true;
                tickTrigger.run();
            }
            helperBridge.notifyHelperSync();
        }
        return next;
    }

    private int handleSetMirrorSideMode(int req) {
        int prev, next;
        synchronized (config.displayLock) {
            prev = config.mirrorSideMode;
            next = (req < 0) ? ((prev + 1) % 4)
                    : Math.max(BridgeProtocol.MIRROR_SIDE_OFF, Math.min(BridgeProtocol.MIRROR_SIDE_CENTER_DUAL, req));
            if (next != prev) config.mirrorSideMode = next;
        }
        if (next != prev) {
            mirrorDisplay.resetSideVdAttempt();
            config.saveConf();
            if (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) {
                if ((prev == 0) != (next == 0)) config.forceRebuild = true;
                tickTrigger.run();
                remoteCoordinator.requestDesktopKeyFrame();
            }
            helperBridge.notifyHelperSync();
        }
        return next;
    }

    private void handleSplitAction(Parcel data, Parcel reply) {
        boolean ok = taskController.handleSplitAction(data.readInt(), data.readInt(), data.readString(), data.readString());
        if (reply == null) return;
        TwoSlotSplitSnapshot sp = taskController.getTwoSlotSplitSnapshot();
        MirrorGeom g = mirrorDisplay.getMirrorGeom();
        reply.writeInt(ok ? 1 : 0);
        reply.writeInt(sp.active ? 1 : 0);
        reply.writeInt(sp.ratioPct);
        reply.writeString(sp.slot0Pkg);
        reply.writeString(sp.slot1Pkg);
        reply.writeInt(config.mirrorSideMode);
        reply.writeInt(mirrorDisplay.getSideVdId());
        reply.writeInt(g.d0W);
        reply.writeInt(g.d0H);
        reply.writeInt(g.mirX);
        reply.writeInt(g.mirY);
        reply.writeInt(g.mirW);
        reply.writeInt(g.mirH);
    }

    private void writeDisplayStatus(Parcel reply) {
        if (reply == null) return;
        DisplayStatusSnapshot d = config.snapshotDisplayStatus();
        int[] cursor = localInput.getCursorSnapshot();
        TwoSlotSplitSnapshot split = taskController.getTwoSlotSplitSnapshot();
        MirrorGeom g = mirrorDisplay.getMirrorGeom();
        RemoteStatusSnapshot rem = remoteCoordinator.snapshotStatus(physicalDisplay.findExternalDisplay() != null);
        DesktopWindowManager wm = taskController.getWindowManager();
        boolean mirrorCanvas = d.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR
                && (!remoteCoordinator.isDesktopSessionActive() || mirrorDisplay.hasActiveRemoteComposition());

        reply.writeInt(d.extId);
        reply.writeInt(d.vdId);
        reply.writeInt(d.physW);
        reply.writeInt(d.physH);
        reply.writeInt(Math.round(d.physFps * 100f));
        reply.writeInt(mirrorCanvas ? d.physW : d.virtW);
        reply.writeInt(mirrorCanvas ? d.physH : d.virtH);
        reply.writeInt(d.virtDpi);
        reply.writeInt(Math.round(d.virtFps * 100f));
        reply.writeInt(d.inputToExt);
        reply.writeInt(cursor[0]);
        reply.writeInt(cursor[1]);
        reply.writeInt(injector.getLastExtInputCount());
        reply.writeString(physicalDisplay.getModesSummary());
        reply.writeString(d.renderMode);
        reply.writeInt(d.mirrorMode);
        reply.writeInt(d.fullscreenPolicy);
        reply.writeInt(d.virtRot);
        reply.writeInt(injector.getLastInjectStatus());
        reply.writeInt(split.active ? 1 : 0);
        reply.writeInt(split.ratioPct);
        reply.writeString(split.slot0Pkg);
        reply.writeString(split.slot1Pkg);
        reply.writeInt(d.mirrorSideMode);
        reply.writeInt(mirrorDisplay.getSideVdId());
        reply.writeInt(g.d0W);
        reply.writeInt(g.d0H);
        reply.writeInt(g.mirX);
        reply.writeInt(g.mirY);
        reply.writeInt(g.mirW);
        reply.writeInt(g.mirH);
        // Extended OpalCast / Remote Display fields
        reply.writeInt(rem.activeSinkType);
        reply.writeString(rem.peerName);
        reply.writeInt(rem.bitrateKbps);
        reply.writeInt(rem.actualFpsX100);
        reply.writeInt(rem.wifiEnabled ? 1 : 0);
        reply.writeInt(rem.wifiPort);
        reply.writeString(rem.certFingerprint);
        reply.writeString(rem.activePin);
        // OpalDesk v4.1 desktop window management fields
        reply.writeInt(wm.isAdaptiveSingleEnabled() ? 1 : 0);
        reply.writeInt(wm.getDefaultSingleWidthDp());
        reply.writeString(wm.resolveActiveLayoutMode());
    }

    private void handleStartLocalDex(Parcel data, Parcel reply) {
        int reqW = data.readInt();
        int reqH = data.readInt();
        int reqDpi = data.readInt();
        int reqFpsX100 = data.readInt();
        Surface surf = (data.readInt() != 0) ? Surface.CREATOR.createFromParcel(data) : null;
        int vdId = (localDex != null) ? localDex.startOrAttach(reqW, reqH, reqDpi, reqFpsX100, surf) : -1;
        writeInt(reply, vdId);
    }

    private void handleSendLocalTouch(Parcel data, Parcel reply) {
        int action = data.readInt();
        long downTime = data.readLong();
        long eventTime = data.readLong();
        int source = data.readInt();
        int buttonState = data.readInt();
        int actionButton = data.readInt();
        float hScroll = data.readFloat();
        float vScroll = data.readFloat();
        int count = Math.max(0, Math.min(10, data.readInt()));
        if (count <= 0) {
            writeBool(reply, false);
            return;
        }
        int[] ids = new int[count];
        int[] toolTypes = new int[count];
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] pressures = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = data.readInt();
            toolTypes[i] = data.readInt();
            xs[i] = data.readFloat();
            ys[i] = data.readFloat();
            pressures[i] = data.readFloat();
        }
        boolean ok = localInput.injectLocalMotionEvent(
                action, downTime, eventTime, source, buttonState, actionButton,
                hScroll, vScroll, ids, toolTypes, xs, ys, pressures);
        writeBool(reply, ok);
    }

    private void handleShutdown(Parcel reply) {
        shutdownTrigger.run();
        writeInt(reply, 1);
        new Thread(() -> { SystemClock.sleep(80L); System.exit(0); }, "ExtBinderExit").start();
    }

    private void writeCursorBoolReply(Parcel reply, boolean flag) {
        if (reply == null) return;
        int[] cur = localInput.getCursorSnapshot();
        reply.writeInt(flag ? 1 : 0);
        reply.writeInt(cur[0]);
        reply.writeInt(cur[1]);
    }

    private static void writeBool(Parcel reply, boolean ok) { if (reply != null) reply.writeInt(ok ? 1 : 0); }
    private static void writeInt(Parcel reply, int v) { if (reply != null) reply.writeInt(v); }
    private static void writeString(Parcel reply, String s) { if (reply != null) reply.writeString(s); }
}
