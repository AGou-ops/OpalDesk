package io.github.lengsukq.opaldesk.daemon.input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalMouseRouterTest {

    @Test
    public void shouldRouteOnlyPhysicalExternalMice() {
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "BT5.0 Mouse", MouseRoutingPolicy.SOURCE_MOUSE, false, true));
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "Logitech MX Master 3S",
                MouseRoutingPolicy.SOURCE_MOUSE | MouseRoutingPolicy.SOURCE_KEYBOARD,
                false,
                true));

        // Reject virtual, internal, touchscreen, and OpalDesk's own virtual UHID pointer
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "OpalDesk Virtual Pointer 21", MouseRoutingPolicy.SOURCE_MOUSE, false, true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Virtual Mouse", MouseRoutingPolicy.SOURCE_MOUSE, true, true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "NVTCapacitiveTouchScreen", MouseRoutingPolicy.SOURCE_TOUCHSCREEN, false, false));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Built-in Trackpad", MouseRoutingPolicy.SOURCE_MOUSE, false, false));
    }

    @Test
    public void shouldRoutePhysicalExternalAlphabeticKeyboardsOnly() {
        // Real external Bluetooth / USB keyboard -> routed to external/casted display
        assertTrue(MouseRoutingPolicy.shouldRouteDevice(
                "Keychron K3 Pro",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true));

        // Reject Bluetooth headset media buttons (non-alphabetic), built-in gpio-keys, and virtual keyboards
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "AirPods Pro AVRCP",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                1 /* KEYBOARD_TYPE_NON_ALPHABETIC */,
                false,
                true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "gpio-keys",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                false));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "OpalDesk Virtual Keyboard 12",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                false,
                true));
        assertFalse(MouseRoutingPolicy.shouldRouteDevice(
                "Virtual KB",
                MouseRoutingPolicy.SOURCE_KEYBOARD,
                MouseRoutingPolicy.KEYBOARD_TYPE_ALPHABETIC,
                true,
                true));
    }

    @Test
    public void sensitivityMapsSmoothlyToAndroidPointerSpeedRange() {
        assertEquals(-7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.20f));
        assertEquals(-7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.50f));
        assertEquals(0, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(1.45f));
        assertEquals(7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(3.00f));
        assertEquals(7, MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(5.00f));

        int slower = MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(0.95f);
        int faster = MouseRoutingPolicy.mapSensitivityToAndroidPointerSpeed(2.25f);
        assertTrue("Expected negative speed below default, got " + slower, slower < 0 && slower > -7);
        assertTrue("Expected positive speed above default, got " + faster, faster > 0 && faster < 7);
    }

    @Test
    public void doubleTapWakeDetectorHandlesTouchAndHardwareWakeKey() {
        DoubleTapWakeDetector det = new DoubleTapWakeDetector();
        det.arm(1000L);

        // Inside arming grace window (350ms): must ignore residual touch-up
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 1100L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 1150L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 1200L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 1250L, true));

        // After grace window: two quick taps within 520ms -> wakes!
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 2000L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 2060L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 2220L, true));
        assertTrue(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 2290L, true));

        // Hardware KEY_WAKEUP from touch IC double-tap firmware wakes immediately
        det.arm(3000L);
        assertTrue(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.KEY_WAKEUP, 1, 3500L, true));

        // Long press (>360ms) must not trigger accidental wake
        det.arm(5000L);
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 5500L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 6200L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 1, 6300L, true));
        assertFalse(det.onEvent(DoubleTapWakeDetector.EV_KEY, DoubleTapWakeDetector.BTN_TOUCH, 0, 6350L, true));
    }
}
