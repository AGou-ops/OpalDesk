package io.github.lengsukq.opaldesk.daemon.input;

/**
 * Pure JVM-testable policy rules for physical external mouse routing & stepless pointer speed mapping.
 *
 * <p>Kept free of {@code android.*} imports so deterministic JUnit 4 unit tests execute on standard JVM.
 */
public final class MouseRoutingPolicy {
    /** Matches {@code android.view.InputDevice.SOURCE_MOUSE} (0x00002002). */
    public static final int SOURCE_MOUSE = 0x00002002;
    /** Matches {@code android.view.InputDevice.SOURCE_KEYBOARD} (0x00000101). */
    public static final int SOURCE_KEYBOARD = 0x00000101;
    /** Matches {@code android.view.InputDevice.SOURCE_TOUCHSCREEN} (0x00001002). */
    public static final int SOURCE_TOUCHSCREEN = 0x00001002;
    /** Matches {@code android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC} (2). */
    public static final int KEYBOARD_TYPE_ALPHABETIC = 2;

    public static final float MIN_SENSITIVITY = 0.50f;
    public static final float DEFAULT_SENSITIVITY = 1.45f;
    public static final float MAX_SENSITIVITY = 3.00f;

    // Standard android.view.MotionEvent action & button constants
    public static final int ACTION_MASK = 0xff;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_SCROLL = 8;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;
    public static final int ACTION_BUTTON_PRESS = 11;
    public static final int ACTION_BUTTON_RELEASE = 12;
    public static final int BUTTON_PRIMARY = 1;

    private MouseRoutingPolicy() {}

    /**
     * Returns {@code true} only for generic motion actions that are valid to inject into
     * {@code IInputManager.injectInputEvent} ({@code HOVER_ENTER}, {@code HOVER_MOVE},
     * {@code HOVER_EXIT}, {@code SCROLL}).
     *
     * <p>Explicitly rejects {@code ACTION_BUTTON_PRESS (11)} and {@code ACTION_BUTTON_RELEASE (12)},
     * which Android's {@code InputDispatcher} synthesizes internally from {@code ACTION_DOWN}/{@code UP};
     * injecting them directly corrupts button state tracking and causes first-click drops.
     */
    public static boolean isInjectableGenericMotionAction(int actionMasked) {
        return actionMasked == ACTION_HOVER_MOVE
                || actionMasked == ACTION_HOVER_ENTER
                || actionMasked == ACTION_HOVER_EXIT
                || actionMasked == ACTION_SCROLL;
    }

    /**
     * Normalizes mouse {@code buttonState} so composite USB / 2.4GHz OTG HID receivers that emit
     * {@code ACTION_DOWN} or {@code ACTION_MOVE} with {@code buttonState == 0} still report
     * {@link #BUTTON_PRIMARY}.
     */
    public static int normalizeMouseButtonState(int actionMasked, boolean isMouseSource, int rawButtonState) {
        if (isMouseSource
                && (actionMasked == ACTION_DOWN || actionMasked == ACTION_MOVE)
                && rawButtonState == 0) {
            return BUTTON_PRIMARY;
        }
        return rawButtonState;
    }

    /**
     * Returns {@code true} when a virtual UHID pointer is transitioning from released ({@code 0})
     * to pressed ({@code != 0}) at a new coordinate {@code (nextAbsX, nextAbsY)} different from
     * {@code (lastAbsX, lastAbsY)}. Sending a 0-button position report first prevents Android's
     * {@code InputReader} from interpreting a single click at a new spot as a drag from the old spot.
     */
    public static boolean needsPrePressPositionReport(
            int lastButtons,
            int nextButtons,
            int lastAbsX,
            int lastAbsY,
            int nextAbsX,
            int nextAbsY) {
        return lastButtons == 0
                && nextButtons != 0
                && (nextAbsX != lastAbsX || nextAbsY != lastAbsY);
    }

    /** Decides whether an InputDevice represents a routable physical external mouse. */
    public static boolean shouldRouteDevice(String name, int sources, boolean isVirtual, boolean isExternal) {
        return shouldRouteDevice(name, sources, 0, isVirtual, isExternal);
    }

    /**
     * Decides whether an InputDevice represents a routable physical external mouse OR alphabetic
     * external physical keyboard (excluding built-in keys, virtual UHID devices, and Bluetooth
     * headphone non-alphabetic media-key devices).
     */
    public static boolean shouldRouteDevice(
            String name, int sources, int keyboardType, boolean isVirtual, boolean isExternal) {
        if (isVirtual || !isExternal) return false;
        if (name != null && name.startsWith("OpalDesk Virtual")) return false;
        boolean isMouse = (sources & SOURCE_MOUSE) == SOURCE_MOUSE;
        boolean isAlphaKeyboard = (sources & SOURCE_KEYBOARD) == SOURCE_KEYBOARD
                && keyboardType == KEYBOARD_TYPE_ALPHABETIC;
        return isMouse || isAlphaKeyboard;
    }

    /**
     * Returns {@code true} when exiting/unpinning a local desktop or detaching client surfaces
     * must explicitly restore Android's {@code mTopFocusedDisplayId} back to {@code Display 0}
     * so hardware USB/Bluetooth mice never get stranded on an invisible {@code OWN_FOCUS}
     * {@code OpalWin-*} VirtualDisplay.
     */
    public static boolean shouldRestoreDefaultDisplayFocusOnLocalExit(
            boolean localDesktopPinned,
            int boundExternalDisplayId,
            boolean remoteDesktopActive) {
        return !localDesktopPinned && boundExternalDisplayId <= 0 && !remoteDesktopActive;
    }

    /**
     * Maps OpalDesk's continuous stepless pointer sensitivity ({@code 0.50f..3.00f}, default {@code 1.45f})
     * smoothly onto Android's hardware pointer speed integer scale ({@code -7..+7}, default {@code 0}).
     */
    public static int mapSensitivityToAndroidPointerSpeed(float sensitivity) {
        float clamped = Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, sensitivity));
        float norm;
        if (clamped <= DEFAULT_SENSITIVITY) {
            norm = (clamped - DEFAULT_SENSITIVITY) / (DEFAULT_SENSITIVITY - MIN_SENSITIVITY);
        } else {
            norm = (clamped - DEFAULT_SENSITIVITY) / (MAX_SENSITIVITY - DEFAULT_SENSITIVITY);
        }
        return Math.max(-7, Math.min(7, Math.round(norm * 7.0f)));
    }
}
