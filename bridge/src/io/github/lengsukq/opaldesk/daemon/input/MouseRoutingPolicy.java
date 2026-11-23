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

    private MouseRoutingPolicy() {}

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
