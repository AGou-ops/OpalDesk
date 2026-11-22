package io.github.lengsukq.opaldesk.daemon.input;

import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import io.github.lengsukq.opaldesk.daemon.DaemonConfig;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import io.github.lengsukq.opaldesk.daemon.ipc.HelperClientBridge;
import io.github.lengsukq.opaldesk.daemon.platform.AndroidServices;
import io.github.lengsukq.opaldesk.daemon.platform.VirtualDisplayFactory;
import io.github.lengsukq.opaldesk.daemon.stream.UhidInputDevice;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Unified {@code IInputManager} + {@code /dev/uhid} auto-fallback input engine,
 * external input device uniqueId association manager, and shared {@link MotionEvent}/{@link KeyEvent} builders.
 */
public final class InputInjector {
    private static final long EXT_MOUSE_CHECK_CACHE_MS = 4000L;
    private static final long INJECT_DENY_LOG_INTERVAL_MS = 5000L;
    private static final long UHID_FIRST_PROBE_SETTLE_MS = 60L;

    private final AndroidServices services;
    private final DaemonConfig config;
    private final HelperClientBridge helperBridge;
    private final Object inputLock = new Object();
    private final Object uhidLock = new Object();
    private final ArrayList<String> boundDescs = new ArrayList<>();
    private final HashMap<Integer, UhidInputDevice> uhidDevices = new HashMap<>();

    private volatile int lastExtInputCnt = -1;
    private volatile String lastExtInputSig = "";
    private volatile int lastInjectStatus = BridgeProtocol.INJECT_STATUS_SYSTEM;
    private volatile boolean uhidAutoActivated = false;
    private volatile long lastInjectDenyLogMs = 0L;
    private volatile boolean cachedExtMousePresent = false;
    private volatile long lastExtMouseCheckMs = 0L;

    private volatile IntFunction<String> extraDisplayUniqueIdLookup;
    private volatile IntFunction<int[]> extraDisplayDimsLookup;
    private volatile BooleanSupplier extraDisplaysUhidOpener;
    private volatile Runnable onInjectStatusChanged;

    public InputInjector(AndroidServices services, DaemonConfig config, HelperClientBridge helperBridge) {
        this.services = services;
        this.config = config;
        this.helperBridge = helperBridge;
    }

    public void setExtraDisplayHooks(
            IntFunction<String> uniqueIdLookup, IntFunction<int[]> dimsLookup,
            BooleanSupplier uhidOpener, Runnable statusChangedCallback) {
        this.extraDisplayUniqueIdLookup = uniqueIdLookup;
        this.extraDisplayDimsLookup = dimsLookup;
        this.extraDisplaysUhidOpener = uhidOpener;
        this.onInjectStatusChanged = statusChangedCallback;
    }

    public int getLastExtInputCount() { return lastExtInputCnt; }
    public int getLastInjectStatus() { return lastInjectStatus; }
    public boolean canInject() { return services.iInputManager != null && services.injectInputEventMethod != null; }

    // ---- Shared MotionEvent & KeyEvent Builders ----

    public static MotionEvent obtainSingleMouseEvent(
            int action, long downTime, long eventTime, float x, float y,
            int buttonState, int actionButton, float hScroll, float vScroll) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = (buttonState != 0) ? 1.0f : 0.0f;
        c.size = 1.0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            c.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
            c.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        }
        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) AndroidServices.setActionButton(ev, actionButton);
        return ev;
    }

    public static MotionEvent obtainSingleTouchEvent(int action, long downTime, long eventTime, float x, float y) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0.0f : 1.0f;
        c.size = 1.0f;
        return MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
                0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    public static KeyEvent createSystemKeyEvent(
            long downTime, long eventTime, int action, int keyCode, int repeatCount, int metaState) {
        return new KeyEvent(downTime, eventTime, action, keyCode, Math.max(0, repeatCount), metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    public void dispatchMouse(
            int action, long downTime, long now, float x, float y,
            int buttonState, int actionButton, float hScroll, float vScroll, int dispId) {
        long effDown = (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) ? now : downTime;
        MotionEvent ev = obtainSingleMouseEvent(action, effDown, now, x, y, buttonState, actionButton, hScroll, vScroll);
        try { injectInput(ev, dispId); } finally { ev.recycle(); }
    }

    public void dispatchTouch(int action, long downTime, long now, float x, float y, int dispId) {
        MotionEvent ev = obtainSingleTouchEvent(action, downTime > 0L ? downTime : now, now, x, y);
        try { injectInput(ev, dispId); } finally { ev.recycle(); }
    }

    public void performMouseClick(long now, float x, float y, int btnMask, int dispId) {
        long down = now - 20L;
        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now - 25L, now - 25L, x, y, 0, 0, 0f, 0f, dispId);
        dispatchMouse(MotionEvent.ACTION_DOWN, down, down, x, y, btnMask, 0, 0f, 0f, dispId);
        dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, down, down, x, y, btnMask, btnMask, 0f, 0f, dispId);
        dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, down, now, x, y, 0, btnMask, 0f, 0f, dispId);
        dispatchMouse(MotionEvent.ACTION_UP, down, now, x, y, 0, 0, 0f, 0f, dispId);
        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, x, y, 0, 0, 0f, 0f, dispId);
    }

    public void releaseHeldButtons(
            long downTime, long now, float x, float y, int btnsMask, boolean asTouch, int dispId) {
        if (btnsMask == 0 || dispId < 0) return;
        if (asTouch && (btnsMask & MotionEvent.BUTTON_PRIMARY) != 0) {
            dispatchTouch(MotionEvent.ACTION_UP, downTime, now, x, y, dispId);
        } else {
            dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downTime, now, x, y, 0, btnsMask, 0f, 0f, dispId);
            dispatchMouse(MotionEvent.ACTION_UP, downTime, now, x, y, 0, 0, 0f, 0f, dispId);
        }
    }

    // ---- External Input Device UniqueId Association ----

    public void unbindInput() {
        synchronized (inputLock) {
            if (services.inputManager == null || services.removeInputAssocMethod == null) return;
            for (String desc : boundDescs) removeAssociationQuietly(desc);
            boundDescs.clear();
            for (String desc : scanExternalDeviceDescriptors()) removeAssociationQuietly(desc);
        }
    }

    public void syncInput() {
        synchronized (inputLock) {
            unbindInput();
            String vdUid = config.vdUniqueId;
            if (config.extId < 0 || vdUid == null || services.inputManager == null) return;
            ArrayList<String> descs = scanExternalDeviceDescriptors();
            for (String desc : descs) {
                if (config.inputToExt != 0 && services.addInputAssocMethod != null) {
                    removeAssociationQuietly(desc);
                    try {
                        services.addInputAssocMethod.invoke(services.inputManager, desc, vdUid);
                        boundDescs.add(desc);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        System.err.println("OPALDESK_ASSOC_ERR desc=" + desc + ": " + e.getMessage());
                    }
                }
            }
            lastExtInputSig = String.join("|", descs);
            lastExtInputCnt = descs.size();
            System.out.println("OPALDESK_INPUT_MODE=" + config.inputToExt + " count=" + descs.size());
            helperBridge.notifyHelperSync();
        }
    }

    public void checkInput() {
        if (config.inputToExt == 0 || services.inputManager == null) return;
        ArrayList<String> descs = scanExternalDeviceDescriptors();
        if (descs.size() != lastExtInputCnt || !String.join("|", descs).equals(lastExtInputSig)) {
            syncInput();
        }
    }

    public boolean hasExternalCursorDevice() {
        long now = SystemClock.uptimeMillis();
        if (now - lastExtMouseCheckMs < EXT_MOUSE_CHECK_CACHE_MS) return cachedExtMousePresent;
        lastExtMouseCheckMs = now;
        boolean found = false;
        if (services.inputManager != null) {
            try {
                for (int id : services.inputManager.getInputDeviceIds()) {
                    InputDevice dev = services.inputManager.getInputDevice(id);
                    if (dev != null && dev.isExternal() && !dev.isVirtual()) {
                        int src = dev.getSources();
                        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                                || (src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("OPALDESK_EXT_MOUSE_CHECK_WARN: " + e.getMessage());
            }
        }
        cachedExtMousePresent = found;
        return found;
    }

    // ---- Core Input Injection & UHID Auto-Fallback ----

    public boolean injectInput(InputEvent ev, int targetDisplayId) {
        return injectInputMode(ev, targetDisplayId, 0);
    }

    public boolean injectInputMode(InputEvent ev, int targetDisplayId, int mode) {
        if (ev == null || targetDisplayId < 0) return false;
        int[] dims = resolveDisplayDimensions(targetDisplayId);
        return injectInputForDisplay(ev, targetDisplayId, mode, resolveDisplayUniqueId(targetDisplayId), dims[0], dims[1]);
    }

    public boolean injectInputForDisplay(
            InputEvent ev, int targetDisplayId, int mode, String vdUniqueId, int dispW, int dispH) {
        if (ev == null || targetDisplayId < 0) return false;
        int backend = config.inputBackendMode;
        if (backend == BridgeProtocol.INPUT_BACKEND_UHID
                || (backend == BridgeProtocol.INPUT_BACKEND_AUTO && uhidAutoActivated)) {
            if (injectViaUhid(ev, targetDisplayId, vdUniqueId, dispW, dispH)) {
                updateInjectStatus(BridgeProtocol.INJECT_STATUS_UHID);
                return true;
            }
            if (backend == BridgeProtocol.INPUT_BACKEND_UHID) {
                updateInjectStatus(BridgeProtocol.INJECT_STATUS_BLOCKED);
                return false;
            }
        }
        if (!canInject()) return false;
        try {
            if (services.setDisplayIdMethod != null) services.setDisplayIdMethod.invoke(ev, targetDisplayId);
            Object res = services.injectInputEventMethod.invoke(services.iInputManager, ev, mode);
            boolean ok = !(res instanceof Boolean) || (Boolean) res;
            if (ok) updateInjectStatus(BridgeProtocol.INJECT_STATUS_SYSTEM);
            return ok;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (mode != 0) return injectInputForDisplay(ev, targetDisplayId, 0, vdUniqueId, dispW, dispH);
            Throwable root = (e instanceof InvocationTargetException && e.getCause() != null) ? e.getCause() : e;
            if (root instanceof SecurityException) {
                return handleSecurityDenied(ev, targetDisplayId, vdUniqueId, dispW, dispH, root);
            }
            lastInjectStatus = BridgeProtocol.INJECT_STATUS_FAILED;
            System.err.println("OPALDESK_INJECT_ERR displayId=" + targetDisplayId + ": " + root);
            return false;
        }
    }

    public void focusDisplayForKey(int dispId) {
        if (dispId <= 0) return;
        services.focusTopTask(dispId);
        services.moveDisplayToTop(dispId, "OpalDesk");
    }

    public UhidInputDevice ensureUhidForDisplay(int displayId, String vdUniqueId) {
        if (displayId < 0) return null;
        boolean newlyCreated = false;
        UhidInputDevice dev;
        synchronized (uhidLock) {
            dev = uhidDevices.get(displayId);
            if (dev != null && dev.isOpen()) {
                String cur = dev.getVdUniqueId();
                if ((vdUniqueId == null && cur == null) || (vdUniqueId != null && vdUniqueId.equals(cur))) return dev;
                dev.close();
                uhidDevices.remove(displayId);
            }
            try {
                dev = UhidInputDevice.open(displayId, vdUniqueId, services.inputManager, services.iInputManager);
                uhidDevices.put(displayId, dev);
                newlyCreated = true;
            } catch (Exception e) {
                long now = SystemClock.uptimeMillis();
                if (now - lastInjectDenyLogMs > INJECT_DENY_LOG_INTERVAL_MS) {
                    lastInjectDenyLogMs = now;
                    System.err.println("OPALDESK_UHID_OPEN_FAILED displayId=" + displayId + ": " + e.getMessage());
                }
                return null;
            }
        }
        if (newlyCreated && Looper.myLooper() != Looper.getMainLooper()) SystemClock.sleep(UHID_FIRST_PROBE_SETTLE_MS);
        return dev;
    }

    public void releaseUhidForDisplay(int displayId) {
        UhidInputDevice dev;
        synchronized (uhidLock) { dev = uhidDevices.remove(displayId); }
        if (dev != null) dev.close();
    }

    public void releaseAllUhidDevices() {
        ArrayList<UhidInputDevice> list;
        synchronized (uhidLock) {
            if (uhidDevices.isEmpty()) return;
            list = new ArrayList<>(uhidDevices.values());
            uhidDevices.clear();
        }
        for (UhidInputDevice d : list) d.close();
    }

    public int setInputBackendMode(int mode) {
        int clamped = (mode >= BridgeProtocol.INPUT_BACKEND_AUTO && mode <= BridgeProtocol.INPUT_BACKEND_UHID)
                ? mode : BridgeProtocol.INPUT_BACKEND_AUTO;
        config.inputBackendMode = clamped;
        if (clamped != BridgeProtocol.INPUT_BACKEND_UHID) {
            uhidAutoActivated = false;
            releaseAllUhidDevices();
            lastInjectStatus = BridgeProtocol.INJECT_STATUS_SYSTEM;
        } else {
            uhidAutoActivated = true;
            boolean anyOk = (config.vdId > 0) && (ensureUhidForDisplay(config.vdId, config.vdUniqueId) != null);
            BooleanSupplier extra = extraDisplaysUhidOpener;
            boolean openedExtra = (extra != null) && extra.getAsBoolean();
            anyOk |= openedExtra;
            if (!anyOk && config.vdId <= 0 && !openedExtra) {
                UhidInputDevice probe = ensureUhidForDisplay(0, null);
                if (probe != null) { anyOk = true; releaseUhidForDisplay(0); }
            }
            lastInjectStatus = anyOk ? BridgeProtocol.INJECT_STATUS_UHID : BridgeProtocol.INJECT_STATUS_BLOCKED;
        }
        broadcastInjectStatusChanged();
        return lastInjectStatus;
    }

    private boolean handleSecurityDenied(
            InputEvent ev, int dispId, String vdUniqueId, int dispW, int dispH, Throwable root) {
        long now = SystemClock.uptimeMillis();
        if (now - lastInjectDenyLogMs > INJECT_DENY_LOG_INTERVAL_MS) {
            lastInjectDenyLogMs = now;
            System.err.println("OPALDESK_INJECT_SECURITY_DENIED: " + root.getMessage());
        }
        if (config.inputBackendMode == BridgeProtocol.INPUT_BACKEND_AUTO
                && injectViaUhid(ev, dispId, vdUniqueId, dispW, dispH)) {
            uhidAutoActivated = true;
            lastInjectStatus = BridgeProtocol.INJECT_STATUS_UHID;
            System.out.println("OPALDESK_UHID_AUTO_ACTIVATED displayId=" + dispId);
            broadcastInjectStatusChanged();
            return true;
        }
        updateInjectStatus(BridgeProtocol.INJECT_STATUS_BLOCKED);
        return false;
    }

    private boolean injectViaUhid(InputEvent ev, int dispId, String vdUniqueId, int w, int h) {
        UhidInputDevice dev = ensureUhidForDisplay(dispId, vdUniqueId);
        return dev != null && dev.injectInputEvent(ev, w, h);
    }

    private void updateInjectStatus(int status) {
        if (lastInjectStatus != status) {
            lastInjectStatus = status;
            broadcastInjectStatusChanged();
        }
    }

    private void broadcastInjectStatusChanged() {
        helperBridge.notifyHelperSync();
        Runnable cb = onInjectStatusChanged;
        if (cb != null) cb.run();
    }

    private ArrayList<String> scanExternalDeviceDescriptors() {
        ArrayList<String> descs = new ArrayList<>();
        try {
            int[] ids = services.inputManager.getInputDeviceIds();
            if (ids != null) {
                for (int id : ids) {
                    InputDevice dev = services.inputManager.getInputDevice(id);
                    if (dev != null && dev.isExternal() && !dev.isVirtual() && dev.getDescriptor() != null) {
                        descs.add(dev.getDescriptor());
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("OPALDESK_SCAN_INPUT_WARN: " + e.getMessage());
        }
        Collections.sort(descs);
        return descs;
    }

    private String resolveDisplayUniqueId(int displayId) {
        if (displayId <= 0) return null;
        if (displayId == config.vdId && config.vdUniqueId != null) return config.vdUniqueId;
        IntFunction<String> extra = extraDisplayUniqueIdLookup;
        String found = (extra != null) ? extra.apply(displayId) : null;
        if (found != null) return found;
        DisplayManager dm = services.displayManager;
        Display d = (dm != null) ? dm.getDisplay(displayId) : null;
        return (d != null) ? VirtualDisplayFactory.getUniqueId(d) : null;
    }

    private int[] resolveDisplayDimensions(int displayId) {
        if (displayId == 0) {
            Point p = services.readDisplayRealSize(0);
            return new int[]{Math.max(1, p.x > 0 ? p.x : config.virtW), Math.max(1, p.y > 0 ? p.y : config.virtH)};
        }
        if (displayId == config.vdId) return new int[]{Math.max(1, config.virtW), Math.max(1, config.virtH)};
        IntFunction<int[]> extra = extraDisplayDimsLookup;
        int[] dims = (extra != null) ? extra.apply(displayId) : null;
        return (dims != null && dims.length >= 2) ? dims : new int[]{Math.max(1, config.virtW), Math.max(1, config.virtH)};
    }

    private void removeAssociationQuietly(String desc) {
        try {
            services.removeInputAssocMethod.invoke(services.inputManager, desc);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Expected when descriptor had no prior association.
        }
    }
}
