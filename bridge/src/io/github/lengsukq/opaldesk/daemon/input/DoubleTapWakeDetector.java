package io.github.lengsukq.opaldesk.daemon.input;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure JVM-testable Linux {@code input_event} double-tap-to-wake detector used while Display 0 is
 * physically powered off ({@code SurfaceControl.setDisplayPowerMode(POWER_MODE_OFF)}).
 *
 * <p>Handles BOTH touchscreen hardware modes seamlessly:
 * <ul>
 *   <li>Active digitizer mode: detects two quick touch taps ({@code BTN_TOUCH} or {@code ABS_MT_TRACKING_ID})
 *       within {@link #MAX_DOUBLE_TAP_INTERVAL_MS} after a short arming grace window.</li>
 *   <li>Low-power touch IC gesture-wakeup mode: detects hardware {@code KEY_WAKEUP} (or touch-panel
 *       {@code KEY_POWER}) emitted natively by the OEM double-tap-to-wake firmware.</li>
 * </ul>
 */
public final class DoubleTapWakeDetector {
    public static final int INPUT_EVENT_SIZE_64 = 24;

    public static final int EV_KEY = 0x01;
    public static final int EV_ABS = 0x03;

    public static final int KEY_POWER = 116;
    public static final int KEY_WAKEUP = 143;
    public static final int BTN_TOUCH = 330;
    public static final int ABS_MT_TRACKING_ID = 57;

    public static final long GRACE_AFTER_ARM_MS = 350L;
    public static final long MAX_SINGLE_TAP_HOLD_MS = 360L;
    public static final long MAX_DOUBLE_TAP_INTERVAL_MS = 520L;

    private long armedAtMs = 0L;
    private boolean hasSeenBtnTouch = false;
    private boolean touchDown = false;
    private long currentDownMs = 0L;
    private long lastTapUpMs = 0L;

    public synchronized void arm(long nowMs) {
        armedAtMs = nowMs;
        hasSeenBtnTouch = false;
        touchDown = false;
        currentDownMs = 0L;
        lastTapUpMs = 0L;
    }

    /**
     * Parses a 64-bit Linux {@code struct input_event} (24 bytes: sec(8), usec(8), type(2), code(2), value(4))
     * and feeds it into {@link #onEvent(int, int, int, long, boolean)}.
     */
    public boolean onRawEvent64(byte[] buf, int offset, long nowMs, boolean isTouchDevice) {
        if (buf == null || offset < 0 || offset + INPUT_EVENT_SIZE_64 > buf.length) return false;
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, INPUT_EVENT_SIZE_64).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.getShort(16) & 0xFFFF;
        int code = bb.getShort(18) & 0xFFFF;
        int value = bb.getInt(20);
        return onEvent(type, code, value, nowMs, isTouchDevice);
    }

    /**
     * Processes one Linux input event and returns {@code true} when Display 0 should immediately wake.
     */
    public synchronized boolean onEvent(int type, int code, int value, long nowMs, boolean isTouchDevice) {
        if (nowMs - armedAtMs < GRACE_AFTER_ARM_MS) {
            return false;
        }

        // 1. Hardware gesture wake key (e.g. touch IC native double-tap-to-wake emitting KEY_WAKEUP)
        if (type == EV_KEY && value == 1) {
            if (code == KEY_WAKEUP || (isTouchDevice && code == KEY_POWER)) {
                resetTaps();
                return true;
            }
        }

        if (!isTouchDevice) {
            return false;
        }

        // 2. Software double-tap detection when digitizer remains active during POWER_MODE_OFF
        if (type == EV_KEY && code == BTN_TOUCH) {
            hasSeenBtnTouch = true;
            return handleTouchTransition(value != 0, nowMs);
        }
        if (!hasSeenBtnTouch && type == EV_ABS && code == ABS_MT_TRACKING_ID) {
            return handleTouchTransition(value >= 0, nowMs);
        }
        return false;
    }

    private boolean handleTouchTransition(boolean pressed, long nowMs) {
        if (pressed) {
            if (!touchDown) {
                touchDown = true;
                currentDownMs = nowMs;
            }
            return false;
        }
        if (!touchDown) {
            return false;
        }
        touchDown = false;
        long holdMs = nowMs - currentDownMs;
        currentDownMs = 0L;
        if (holdMs < 0L || holdMs > MAX_SINGLE_TAP_HOLD_MS) {
            lastTapUpMs = 0L;
            return false;
        }
        if (lastTapUpMs > 0L && (nowMs - lastTapUpMs) <= MAX_DOUBLE_TAP_INTERVAL_MS) {
            resetTaps();
            return true;
        }
        lastTapUpMs = nowMs;
        return false;
    }

    private void resetTaps() {
        touchDown = false;
        currentDownMs = 0L;
        lastTapUpMs = 0L;
    }
}
