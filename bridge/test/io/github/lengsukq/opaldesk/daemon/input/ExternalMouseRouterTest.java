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
}
