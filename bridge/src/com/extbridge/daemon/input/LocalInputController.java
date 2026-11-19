package com.extbridge.daemon.input;

import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.extbridge.daemon.BridgeBuildConfig;
import com.extbridge.daemon.DaemonConfig;
import com.extbridge.daemon.display.NativeMirrorController;
import com.extbridge.daemon.ipc.BridgeProtocol;
import com.extbridge.daemon.platform.TaskApiAdapter;
import com.extbridge.daemon.util.ShellCommand;

import java.util.List;

/**
 * Local Helper virtual touchpad / mouse / keyboard injection, shared cursor state,
 * and stale-drag recovery on Helper process death.
 */
public final class LocalInputController {
    private final DaemonConfig config;
    private final InputInjector injector;
    private final TaskApiAdapter taskApi;
    private final Object cursorLock = new Object();

    private volatile NativeMirrorController mirrorController;
    private volatile float cursorX = 1280f, cursorY = 720f;
    private volatile int mouseButtons = 0;
    private long mouseDownTime = 0L;
    private volatile int mirDragDisplayId = -1;
    private volatile long lastTouchpadMs = 0L;

    public LocalInputController(DaemonConfig config, InputInjector injector, TaskApiAdapter taskApi) {
        this.config = config;
        this.injector = injector;
        this.taskApi = taskApi;
    }

    public void setMirrorController(NativeMirrorController mc) { this.mirrorController = mc; }
    public Object getCursorLock() { return cursorLock; }
    public float getCursorX() { return cursorX; }
    public float getCursorY() { return cursorY; }
    public boolean isAnyMouseButtonDown() { return mouseButtons != 0; }
    public long getLastTouchpadMs() { return lastTouchpadMs; }
    public int getMouseButtonsLocked() { return mouseButtons; }
    public long getMouseDownTimeLocked() { return mouseDownTime; }
    public void setCursorLocked(float x, float y) { cursorX = x; cursorY = y; }
    public void setMouseButtonsLocked(int btns, long downTime) { mouseButtons = btns; mouseDownTime = downTime; }
    public void clearDragStateLocked() { mouseButtons = 0; mouseDownTime = 0L; mirDragDisplayId = -1; }

    public int[] getCursorSnapshot() {
        synchronized (cursorLock) { return new int[]{Math.round(cursorX), Math.round(cursorY)}; }
    }

    public void setCursorPosition(float x, float y) {
        synchronized (cursorLock) { cursorX = x; cursorY = y; }
    }

    public void resetCursorCenter(float w, float h) {
        synchronized (cursorLock) { cursorX = w * 0.5f; cursorY = h * 0.5f; }
    }

    public void clampCursor(float maxW, float maxH) {
        synchronized (cursorLock) {
            cursorX = clamp(cursorX, maxW - 1f);
            cursorY = clamp(cursorY, maxH - 1f);
        }
    }

    public boolean isHelperTopOnDisplay0() {
        List<?> tasks = taskApi.getTasks();
        if (tasks == null) return false;
        for (Object t : tasks) {
            if (t != null && TaskApiAdapter.readIntField(t, "displayId", 0) == 0
                    && TaskApiAdapter.readBoolField(t, "isVisible", true)) {
                return BridgeBuildConfig.HELPER_PACKAGE.equals(TaskApiAdapter.extractTaskPkg(t));
            }
        }
        return false;
    }

    public void handleKeyCommand(int sub, int arg1, int arg2, String txt) {
        if (sub == BridgeProtocol.KEY_ACTION_EVENT) injectKey(arg1, arg2);
        else if (sub == BridgeProtocol.KEY_ACTION_ASCII) injectAscii(txt);
        else if (sub == BridgeProtocol.KEY_ACTION_PASTE) {
            if (arg1 == 1) injectKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
            else injectKey(KeyEvent.KEYCODE_PASTE, 0);
        }
    }

    public void injectKey(int keyCode, int metaState) {
        int dispId = (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) ? 0 : config.vdId;
        if (dispId < 0 || !injector.canInject()) return;
        if (dispId == 0 && keyCode == KeyEvent.KEYCODE_BACK && metaState == 0 && isHelperTopOnDisplay0()) return;
        if (dispId > 0) injector.focusDisplayForKey(dispId);
        long tDown = SystemClock.uptimeMillis();
        boolean okDown = injector.injectInputMode(
                InputInjector.createSystemKeyEvent(tDown, tDown, KeyEvent.ACTION_DOWN, keyCode, 0, metaState), dispId, 2);
        long tUp = Math.max(tDown, SystemClock.uptimeMillis());
        injector.injectInputMode(
                InputInjector.createSystemKeyEvent(tDown, tUp, KeyEvent.ACTION_UP, keyCode, 0, metaState), dispId, 2);
        if (!okDown && keyCode == KeyEvent.KEYCODE_BACK && metaState == 0) {
            ShellCommand.runOutput("cmd", "input", "-d", String.valueOf(dispId), "keyevent", "4");
        }
    }

    public void injectAscii(String text) {
        int dispId = (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR) ? 0 : config.vdId;
        if (dispId < 0 || !injector.canInject() || text == null || text.isEmpty()) return;
        try {
            KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if (evs != null) for (KeyEvent e : evs) injector.injectInput(e, dispId);
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_INJECT_ASCII_ERR: " + e.getMessage());
        }
    }

    public void releaseStaleMouseDragOnHelperDeath() {
        NativeMirrorController mir = mirrorController;
        synchronized (cursorLock) {
            long now = SystemClock.uptimeMillis();
            if (mouseButtons != 0) {
                boolean mirror = (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR);
                int disp = mirror ? (mirDragDisplayId >= 0 ? mirDragDisplayId : resolveDisp(mir, true)) : config.vdId;
                int prevBtns = mouseButtons;
                long prevDown = mouseDownTime;
                clearDragStateLocked();
                if (disp >= 0 && injector.canInject()) {
                    boolean onSide = isSide(mir, mirror, disp);
                    injector.releaseHeldButtons(prevDown, now, mapX(mir, mirror, disp), mapY(mir, mirror, disp), prevBtns, onSide, disp);
                }
            }
            if (mir != null) mir.onHelperDeathLocked(now, cursorX, cursorY);
        }
    }

    public void injectMouseAction(int type, float a1, float a2) {
        if (!Float.isFinite(a1) || !Float.isFinite(a2)) return;
        boolean triggerBack = false;
        NativeMirrorController mir = mirrorController;
        synchronized (cursorLock) {
            boolean mirror = (config.mirrorMode == BridgeProtocol.MIRROR_MODE_MIRROR);
            if ((!mirror && config.vdId <= 0) || (mirror && (config.physW <= 0 || config.physH <= 0)) || !injector.canInject()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            lastTouchpadMs = now;
            float maxX = mirror ? (config.physW - 1f) : (config.virtW - 1f);
            float maxY = mirror ? (config.physH - 1f) : (config.virtH - 1f);
            if (!Float.isFinite(cursorX)) cursorX = maxX * 0.5f;
            if (!Float.isFinite(cursorY)) cursorY = maxY * 0.5f;

            switch (type) {
                case BridgeProtocol.MOUSE_MOVE_REL:
                    cursorX = clamp(cursorX + a1, maxX);
                    cursorY = clamp(cursorY + a2, maxY);
                    dispatchMoveLocked(mir, mirror, now);
                    break;
                case BridgeProtocol.MOUSE_MOVE_ABS:
                    cursorX = clamp(a1, maxX);
                    cursorY = clamp(a2, maxY);
                    dispatchMoveLocked(mir, mirror, now);
                    break;
                case BridgeProtocol.MOUSE_LEFT_CLICK: {
                    int d = resolveDisp(mir, mirror);
                    float tx = mapX(mir, mirror, d), ty = mapY(mir, mirror, d);
                    mouseDownTime = now - 20L;
                    if (isSide(mir, mirror, d)) {
                        injector.dispatchTouch(MotionEvent.ACTION_DOWN, mouseDownTime, mouseDownTime, tx, ty, d);
                        injector.dispatchTouch(MotionEvent.ACTION_UP, mouseDownTime, now, tx, ty, d);
                        mir.scheduleMirrorSideClickPostChecks();
                    } else {
                        injector.performMouseClick(now, tx, ty, MotionEvent.BUTTON_PRIMARY, d);
                    }
                    break;
                }
                case BridgeProtocol.MOUSE_RIGHT_CLICK: {
                    int d = resolveDisp(mir, mirror);
                    if (a1 > 0.5f || isSide(mir, mirror, d) || (mirror && d == 0 && isHelperTopOnDisplay0())) {
                        mouseDownTime = now - 20L;
                        injector.performMouseClick(now, mapX(mir, mirror, d), mapY(mir, mirror, d), MotionEvent.BUTTON_SECONDARY, d);
                    } else {
                        triggerBack = true;
                    }
                    break;
                }
                case BridgeProtocol.MOUSE_LEFT_DOWN: {
                    mouseDownTime = now;
                    mouseButtons = MotionEvent.BUTTON_PRIMARY;
                    int d = resolveDisp(mir, mirror);
                    mirDragDisplayId = mirror ? d : -1;
                    float tx = mapX(mir, mirror, d), ty = mapY(mir, mirror, d);
                    if (isSide(mir, mirror, d)) {
                        injector.dispatchTouch(MotionEvent.ACTION_DOWN, now, now, tx, ty, d);
                    } else {
                        injector.dispatchMouse(MotionEvent.ACTION_DOWN, now, now, tx, ty, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f, d);
                        injector.dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, now, now, tx, ty,
                                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f, d);
                    }
                    break;
                }
                case BridgeProtocol.MOUSE_LEFT_UP: {
                    int d = (mirror && mirDragDisplayId >= 0) ? mirDragDisplayId : resolveDisp(mir, mirror);
                    mirDragDisplayId = -1;
                    mouseButtons = 0;
                    float tx = mapX(mir, mirror, d), ty = mapY(mir, mirror, d);
                    if (isSide(mir, mirror, d)) {
                        injector.dispatchTouch(MotionEvent.ACTION_UP, mouseDownTime, now, tx, ty, d);
                        mir.scheduleMirrorSideClickPostChecks();
                    } else {
                        injector.dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, mouseDownTime, now, tx, ty,
                                0, MotionEvent.BUTTON_PRIMARY, 0f, 0f, d);
                        injector.dispatchMouse(MotionEvent.ACTION_UP, mouseDownTime, now, tx, ty, 0, 0, 0f, 0f, d);
                        injector.dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now + 5L, now + 5L, tx, ty, 0, 0, 0f, 0f, d);
                    }
                    break;
                }
                case BridgeProtocol.MOUSE_SCROLL: {
                    int d = resolveDisp(mir, mirror);
                    injector.dispatchMouse(MotionEvent.ACTION_SCROLL, now, now,
                            mapX(mir, mirror, d), mapY(mir, mirror, d), mouseButtons, 0, a1, a2, d);
                    break;
                }
                default:
                    break;
            }
            if (mirror && mir != null) {
                mir.updateMirrorCursorSurface(cursorX, cursorY, mouseButtons != 0 || type == BridgeProtocol.MOUSE_LEFT_DOWN, true);
            }
        }
        if (triggerBack) injectKey(KeyEvent.KEYCODE_BACK, 0);
    }

    private void dispatchMoveLocked(NativeMirrorController mir, boolean mirror, long now) {
        int d = (mirror && mirDragDisplayId >= 0) ? mirDragDisplayId : resolveDisp(mir, mirror);
        float tx = mapX(mir, mirror, d), ty = mapY(mir, mirror, d);
        if (isSide(mir, mirror, d) && mouseButtons != 0) {
            injector.dispatchTouch(MotionEvent.ACTION_MOVE, mouseDownTime, now, tx, ty, d);
        } else {
            int act = (mouseButtons != 0) ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
            injector.dispatchMouse(act, mouseDownTime, now, tx, ty, mouseButtons, 0, 0f, 0f, d);
        }
    }

    private int resolveDisp(NativeMirrorController mir, boolean mirror) {
        return mirror ? (mir != null ? mir.resolveMirrorTargetDisplay(cursorX, cursorY) : 0) : config.vdId;
    }

    private static boolean isSide(NativeMirrorController mir, boolean mirror, int d) {
        return mirror && mir != null && d > 0 && d == mir.getSideVdId();
    }

    private float mapX(NativeMirrorController mir, boolean mirror, int d) {
        return (mirror && mir != null) ? mir.mapMirrorX(d, cursorX) : cursorX;
    }

    private float mapY(NativeMirrorController mir, boolean mirror, int d) {
        return (mirror && mir != null) ? mir.mapMirrorY(d, cursorY) : cursorY;
    }

    private static float clamp(float v, float maxVal) {
        return Math.max(0f, Math.min(Math.max(0f, maxVal), v));
    }
}
