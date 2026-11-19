package com.extbridge.daemon.stream;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.extbridge.daemon.DisplayGeometryUtil;
import com.extbridge.daemon.input.InputInjector;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates ExtCast v1.1 uplink pointer, multi-touch, keyboard, and system-action frames
 * into Android {@link MotionEvent} / {@link KeyEvent} injections and host display controls.
 */
public final class RemoteInputBridge {

    public interface HostBridge {
        int getTargetDisplayId();
        boolean isMirrorMode();
        int getStreamWidth();
        int getStreamHeight();
        int getTargetDisplayWidth();
        int getTargetDisplayHeight();
        boolean injectInputEvent(InputEvent ev, int targetDisplayId, int mode);
        void focusDisplay(int displayId);
        void updateCursorPosition(float x, float y);
        void performHome();
        void toggleDisplayMode(int arg);
        void toggleBlackout(int arg);
        void rotateDisplay(int arg);
        void setAudioEnabled(boolean enabled);
        void requestDisconnect();
    }

    private final HostBridge host;
    private final Object inputLock = new Object();

    // Pointer / key state
    private int activeMouseButtons = 0;
    private long mouseDownTime = 0L;
    private float lastMouseX = 0f;
    private float lastMouseY = 0f;
    private boolean mouseHovering = false;
    private long touchDownTime = 0L;
    private final Map<Integer, Long> keyDownTimes = new HashMap<>();

    public RemoteInputBridge(HostBridge host) {
        this.host = host;
    }

    public void onSessionStarted() {
        resetInputState();
    }

    public void onSessionEnded() {
        releaseStuckInputAndReset();
    }

    // ---- Mouse ----

    public void handleMouse(ExtCastProtocol.MouseEventMsg msg) {
        if (msg == null || !Float.isFinite(msg.x) || !Float.isFinite(msg.y)) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;

            float[] mapped = mapCoords(msg.x, msg.y);
            float tx = mapped[0];
            float ty = mapped[1];
            lastMouseX = tx;
            lastMouseY = ty;
            host.updateCursorPosition(tx, ty);

            long now = SystemClock.uptimeMillis();
            int buttons = msg.buttons & (ExtCastProtocol.BUTTON_PRIMARY
                    | ExtCastProtocol.BUTTON_SECONDARY
                    | ExtCastProtocol.BUTTON_TERTIARY
                    | ExtCastProtocol.BUTTON_BACK
                    | ExtCastProtocol.BUTTON_FORWARD);
            int actBtn = msg.actionButton;

            switch (msg.action) {
                case ExtCastProtocol.MOUSE_MOVE_ABS: {
                    if (buttons != 0 || activeMouseButtons != 0) {
                        int effButtons = (buttons != 0) ? buttons : activeMouseButtons;
                        if (mouseDownTime <= 0L) mouseDownTime = now;
                        dispatchMouse(MotionEvent.ACTION_MOVE, mouseDownTime, now, tx, ty, effButtons, 0, 0f, 0f, dispId);
                    } else {
                        if (!mouseHovering) {
                            mouseHovering = true;
                            dispatchMouse(MotionEvent.ACTION_HOVER_ENTER, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        }
                        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                case ExtCastProtocol.MOUSE_BUTTON_DOWN: {
                    if (dispId > 0) host.focusDisplay(dispId);
                    int pressBtn = (actBtn != 0) ? actBtn : ExtCastProtocol.BUTTON_PRIMARY;
                    int nextButtons = buttons | pressBtn;
                    boolean firstButton = (activeMouseButtons == 0);
                    activeMouseButtons = nextButtons;
                    if (firstButton) {
                        mouseDownTime = now;
                        if (!mouseHovering) {
                            mouseHovering = true;
                            dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        }
                        dispatchMouse(MotionEvent.ACTION_DOWN, mouseDownTime, now, tx, ty, nextButtons, 0, 0f, 0f, dispId);
                    }
                    dispatchMouse(MotionEvent.ACTION_BUTTON_PRESS, mouseDownTime, now, tx, ty, nextButtons, pressBtn, 0f, 0f, dispId);
                    break;
                }
                case ExtCastProtocol.MOUSE_BUTTON_UP: {
                    int relBtn = (actBtn != 0) ? actBtn
                            : (activeMouseButtons != 0 ? activeMouseButtons : ExtCastProtocol.BUTTON_PRIMARY);
                    int nextButtons = buttons & ~relBtn;
                    long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                    dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, tx, ty, nextButtons, relBtn, 0f, 0f, dispId);
                    activeMouseButtons = nextButtons;
                    if (activeMouseButtons == 0) {
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, tx, ty, 0, 0, 0f, 0f, dispId);
                        mouseDownTime = 0L;
                        dispatchMouse(MotionEvent.ACTION_HOVER_MOVE, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                case ExtCastProtocol.MOUSE_SCROLL: {
                    float hs = Float.isFinite(msg.hScroll) ? msg.hScroll : 0f;
                    float vs = Float.isFinite(msg.vScroll) ? msg.vScroll : 0f;
                    dispatchMouse(MotionEvent.ACTION_SCROLL, now, now, tx, ty, activeMouseButtons, 0, hs, vs, dispId);
                    break;
                }
                case ExtCastProtocol.MOUSE_HOVER_EXIT: {
                    if (activeMouseButtons != 0) {
                        int prev = activeMouseButtons;
                        long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                        activeMouseButtons = 0;
                        mouseDownTime = 0L;
                        dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, tx, ty, 0, prev, 0f, 0f, dispId);
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    if (mouseHovering) {
                        mouseHovering = false;
                        dispatchMouse(MotionEvent.ACTION_HOVER_EXIT, now, now, tx, ty, 0, 0, 0f, 0f, dispId);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void dispatchMouse(
            int action, long downTime, long eventTime,
            float x, float y, int buttonState, int actionButton,
            float hScroll, float vScroll, int dispId) {
        MotionEvent ev = InputInjector.obtainSingleMouseEvent(
                action, downTime, eventTime, x, y, buttonState, actionButton, hScroll, vScroll);
        try {
            host.injectInputEvent(ev, dispId, 0);
        } finally {
            ev.recycle();
        }
    }

    // ---- Multi-touch ----

    public void handleTouch(ExtCastProtocol.TouchEventMsg msg) {
        if (msg == null || msg.pointers == null || msg.pointers.length == 0) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;

            int count = Math.min(msg.pointers.length, ExtCastProtocol.MAX_TOUCH_POINTERS);
            int actionIndex = Math.max(0, Math.min(count - 1, msg.actionIndex));
            long now = SystemClock.uptimeMillis();

            int androidAction;
            switch (msg.action) {
                case ExtCastProtocol.TOUCH_DOWN:
                    touchDownTime = now;
                    if (dispId > 0) host.focusDisplay(dispId);
                    androidAction = MotionEvent.ACTION_DOWN;
                    break;
                case ExtCastProtocol.TOUCH_UP:
                    androidAction = MotionEvent.ACTION_UP;
                    break;
                case ExtCastProtocol.TOUCH_MOVE:
                    androidAction = MotionEvent.ACTION_MOVE;
                    break;
                case ExtCastProtocol.TOUCH_CANCEL:
                    androidAction = MotionEvent.ACTION_CANCEL;
                    break;
                case ExtCastProtocol.TOUCH_POINTER_DOWN:
                    androidAction = MotionEvent.ACTION_POINTER_DOWN
                            | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    break;
                case ExtCastProtocol.TOUCH_POINTER_UP:
                    androidAction = MotionEvent.ACTION_POINTER_UP
                            | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                    break;
                default:
                    return;
            }
            if (touchDownTime <= 0L) {
                touchDownTime = now;
            }

            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                ExtCastProtocol.TouchPointer p = msg.pointers[i];
                float[] mapped = mapCoords(p.x, p.y);
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = p.pointerId;
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = mapped[0];
                coords[i].y = mapped[1];
                float pressure = Float.isFinite(p.pressure) ? Math.max(0f, Math.min(1f, p.pressure)) : 1f;
                if (msg.action == ExtCastProtocol.TOUCH_UP || msg.action == ExtCastProtocol.TOUCH_CANCEL) {
                    pressure = 0f;
                } else if (pressure <= 0f) {
                    pressure = 1f;
                }
                coords[i].pressure = pressure;
                coords[i].size = 1.0f;
            }

            MotionEvent ev = MotionEvent.obtain(
                    touchDownTime, now, androidAction,
                    count, props, coords,
                    0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                host.injectInputEvent(ev, dispId, 0);
            } finally {
                ev.recycle();
            }

            if (msg.action == ExtCastProtocol.TOUCH_UP || msg.action == ExtCastProtocol.TOUCH_CANCEL) {
                touchDownTime = 0L;
            }
        }
    }

    // ---- Keyboard ----

    public void handleKey(ExtCastProtocol.KeyEventMsg msg) {
        if (msg == null || msg.androidKeyCode <= 0) return;
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            if (dispId < 0) return;
            long now = SystemClock.uptimeMillis();
            boolean isDown = (msg.action == ExtCastProtocol.KEY_DOWN);
            int androidAction = isDown ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            long downTime;
            if (isDown) {
                if (dispId > 0 && msg.repeatCount == 0) {
                    host.focusDisplay(dispId);
                }
                Long existing = keyDownTimes.get(msg.androidKeyCode);
                if (msg.repeatCount > 0 && existing != null) {
                    downTime = existing;
                } else {
                    downTime = now;
                    keyDownTimes.put(msg.androidKeyCode, now);
                }
            } else {
                Long existing = keyDownTimes.remove(msg.androidKeyCode);
                downTime = (existing != null) ? existing : now;
            }

            KeyEvent ev = new KeyEvent(
                    downTime, now, androidAction,
                    msg.androidKeyCode,
                    Math.max(0, msg.repeatCount),
                    msg.metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0,
                    KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            host.injectInputEvent(ev, dispId, 0);
        }
    }

    public void injectKeyTap(int keyCode, int metaState) {
        int dispId = host.getTargetDisplayId();
        if (dispId < 0) return;
        if (dispId > 0) host.focusDisplay(dispId);
        long tDown = SystemClock.uptimeMillis();
        KeyEvent kd = new KeyEvent(tDown, tDown, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        boolean ok = host.injectInputEvent(kd, dispId, 2);
        long tUp = Math.max(tDown, SystemClock.uptimeMillis());
        KeyEvent ku = new KeyEvent(tDown, tUp, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        host.injectInputEvent(ku, dispId, 2);
        if (!ok && keyCode == KeyEvent.KEYCODE_PASTE) {
            // Fallback to Ctrl+V when KEYCODE_PASTE is not handled
            long t2 = SystemClock.uptimeMillis();
            int ctrlMeta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            KeyEvent cd = new KeyEvent(t2, t2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrlMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            KeyEvent cu = new KeyEvent(t2, t2 + 5, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, ctrlMeta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            host.injectInputEvent(cd, dispId, 2);
            host.injectInputEvent(cu, dispId, 2);
        }
    }

    // ---- System Actions ----

    public void handleSystemAction(ExtCastProtocol.SystemActionMsg msg) {
        if (msg == null) return;
        switch (msg.action) {
            case ExtCastProtocol.SYS_HOME:
                host.performHome();
                break;
            case ExtCastProtocol.SYS_BACK:
                injectKeyTap(KeyEvent.KEYCODE_BACK, 0);
                break;
            case ExtCastProtocol.SYS_RECENTS:
                injectKeyTap(KeyEvent.KEYCODE_APP_SWITCH, 0);
                break;
            case ExtCastProtocol.SYS_TOGGLE_DISPLAY_MODE:
                host.toggleDisplayMode(msg.arg);
                break;
            case ExtCastProtocol.SYS_TOGGLE_BLACKOUT:
                host.toggleBlackout(msg.arg);
                break;
            case ExtCastProtocol.SYS_ROTATE_CW:
                host.rotateDisplay(msg.arg);
                break;
            case ExtCastProtocol.SYS_SET_AUDIO_ENABLED:
                host.setAudioEnabled(msg.arg != 0);
                break;
            case ExtCastProtocol.SYS_DISCONNECT:
                host.requestDisconnect();
                break;
            default:
                break;
        }
    }

    // ---- Helpers ----

    private float[] mapCoords(float streamX, float streamY) {
        return DisplayGeometryUtil.mapStreamPointToDisplay(
                streamX, streamY,
                host.getStreamWidth(), host.getStreamHeight(),
                host.getTargetDisplayWidth(), host.getTargetDisplayHeight(),
                host.isMirrorMode());
    }

    private void resetInputState() {
        synchronized (inputLock) {
            activeMouseButtons = 0;
            mouseDownTime = 0L;
            mouseHovering = false;
            touchDownTime = 0L;
            keyDownTimes.clear();
        }
    }

    private void releaseStuckInputAndReset() {
        synchronized (inputLock) {
            int dispId = host.getTargetDisplayId();
            long now = SystemClock.uptimeMillis();
            if (dispId >= 0) {
                if (activeMouseButtons != 0) {
                    int prev = activeMouseButtons;
                    long downT = (mouseDownTime > 0L) ? mouseDownTime : now;
                    try {
                        dispatchMouse(MotionEvent.ACTION_BUTTON_RELEASE, downT, now, lastMouseX, lastMouseY, 0, prev, 0f, 0f, dispId);
                        dispatchMouse(MotionEvent.ACTION_UP, downT, now, lastMouseX, lastMouseY, 0, 0, 0f, 0f, dispId);
                    } catch (Throwable t) {
                        System.err.println("EXTBRIDGE_INPUT_RELEASE_MOUSE_WARN: " + t.getMessage());
                    }
                }
                for (Map.Entry<Integer, Long> e : keyDownTimes.entrySet()) {
                    try {
                        KeyEvent ku = new KeyEvent(e.getValue(), now, KeyEvent.ACTION_UP,
                                e.getKey(), 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                        host.injectInputEvent(ku, dispId, 0);
                    } catch (Throwable t) {
                        System.err.println("EXTBRIDGE_INPUT_RELEASE_KEY_WARN key=" + e.getKey() + ": " + t.getMessage());
                    }
                }
            }
            activeMouseButtons = 0;
            mouseDownTime = 0L;
            mouseHovering = false;
            touchDownTime = 0L;
            keyDownTimes.clear();
        }
    }
}
