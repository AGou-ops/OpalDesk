package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DisplayGeometryUtilTest {

    @Test
    public void testAlignForEncoder8And16() {
        // 1920x1080 stays exact under 8-byte alignment
        assertArrayEquals(new int[]{1920, 1080}, DisplayGeometryUtil.alignForEncoder(1920, 1080, 8));
        // Under strict 16-byte alignment, 1080 rounds cleanly to 1088 (16 * 68)
        int[] fhd16 = DisplayGeometryUtil.alignForEncoder(1920, 1080, 16);
        assertEquals(1920, fhd16[0]);
        assertEquals(0, fhd16[0] % 16);
        assertEquals(0, fhd16[1] % 16);

        // Odd MacBook logical dimensions (1728x1117) align cleanly to multiples of 8 / 16
        int[] mbp8 = DisplayGeometryUtil.alignForEncoder(1728, 1117, 8);
        assertEquals(0, mbp8[0] % 8);
        assertEquals(0, mbp8[1] % 8);
        assertEquals(1728, mbp8[0]);
        assertEquals(1120, mbp8[1]);

        int[] mbp16 = DisplayGeometryUtil.alignForEncoder(1729, 1117, 16);
        assertEquals(0, mbp16[0] % 16);
        assertEquals(0, mbp16[1] % 16);

        // Undersized clamps up to valid encoder floor
        int[] tiny = DisplayGeometryUtil.alignForEncoder(200, 100, 16);
        assertTrue(tiny[0] >= DisplayGeometryUtil.MIN_LONG_EDGE);
        assertTrue(tiny[1] >= DisplayGeometryUtil.MIN_SHORT_EDGE);
        assertEquals(0, tiny[0] % 16);
        assertEquals(0, tiny[1] % 16);

        // Oversized scales down preserving aspect within encoder bounds
        int[] huge = DisplayGeometryUtil.alignForEncoder(7680, 4320, 16);
        assertTrue(huge[0] <= DisplayGeometryUtil.MAX_ENCODER_LONG_EDGE);
        assertTrue(huge[1] <= DisplayGeometryUtil.MAX_ENCODER_SHORT_EDGE);
        assertEquals(0, huge[0] % 16);
        assertEquals(0, huge[1] % 16);

        // Portrait dimensions align with swapped min/max bounds
        int[] port = DisplayGeometryUtil.alignForEncoder(1080, 1920, 8);
        assertArrayEquals(new int[]{1080, 1920}, port);
    }

    @Test
    public void testRotationAndAspectFlip() {
        assertEquals(0, DisplayGeometryUtil.normalizeRotation(0));
        assertEquals(3, DisplayGeometryUtil.normalizeRotation(-1));
        assertEquals(1, DisplayGeometryUtil.normalizeRotation(5));
        assertEquals(2, DisplayGeometryUtil.normalizeRotation(-6));

        assertFalse(DisplayGeometryUtil.isPortraitRotation(0));
        assertTrue(DisplayGeometryUtil.isPortraitRotation(1));
        assertFalse(DisplayGeometryUtil.isPortraitRotation(2));
        assertTrue(DisplayGeometryUtil.isPortraitRotation(3));

        assertArrayEquals(new int[]{1080, 1920},
                DisplayGeometryUtil.orientDimensionsForRotation(1920, 1080, 1));
        assertArrayEquals(new int[]{1920, 1080},
                DisplayGeometryUtil.orientDimensionsForRotation(1080, 1920, 0));

        assertTrue(DisplayGeometryUtil.isOrientationAspectFlipped(1920, 1080, 1080, 1920));
        assertTrue(DisplayGeometryUtil.isOrientationAspectFlipped(1080, 1920, 2560, 1440));
        assertFalse(DisplayGeometryUtil.isOrientationAspectFlipped(1920, 1080, 2560, 1440));
        assertFalse(DisplayGeometryUtil.isOrientationAspectFlipped(1080, 1920, 720, 1280));
        assertFalse(DisplayGeometryUtil.isOrientationAspectFlipped(1000, 1000, 1920, 1080));
    }

    @Test
    public void testAutoDpiFpsBitrateAndMirrorLetterboxMapping() {
        assertTrue(DisplayGeometryUtil.isValidDims(1920, 1080));
        assertFalse(DisplayGeometryUtil.isValidDims(320, 240));
        assertEquals(160, DisplayGeometryUtil.computeAutoDpi(2560, 1440));
        assertEquals(120, DisplayGeometryUtil.computeAutoDpi(1280, 720));
        assertEquals(240, DisplayGeometryUtil.clampDpi(240, 1920, 1080));
        assertEquals(160, DisplayGeometryUtil.clampDpi(10, 2560, 1440));

        assertEquals(60f, DisplayGeometryUtil.clampFps(Float.NaN, 60f), 0.001f);
        assertEquals(60f, DisplayGeometryUtil.clampFps(5f, 60f), 0.001f);
        assertEquals(360f, DisplayGeometryUtil.clampFps(999f, 60f), 0.001f);
        assertEquals(120f, DisplayGeometryUtil.clampFps(120f, 60f), 0.001f);

        assertEquals(2000, DisplayGeometryUtil.clampBitrateKbps(500, 2000, 32000));
        assertEquals(32000, DisplayGeometryUtil.clampBitrateKbps(99000, 2000, 32000));

        // Portrait phone (1000x2000) letterboxed inside landscape stream (1920x1000)
        // scale = 0.5 -> scaled 500x1000 centered at x = (1920-500)/2 = 710, y = 0
        int[] lb = DisplayGeometryUtil.computeLetterboxRect(1000, 2000, 1920, 1000);
        assertArrayEquals(new int[]{710, 0, 500, 1000}, lb);

        // Center of stream (960, 500) maps to center of phone (500, 1000)
        float[] center = DisplayGeometryUtil.mapStreamPointToDisplay(960f, 500f, 1920, 1000, 1000, 2000, true);
        assertEquals(500f, center[0], 0.5f);
        assertEquals(1000f, center[1], 0.5f);

        // Non-letterboxed 1:1 DeX mapping clamps cleanly
        float[] dexPt = DisplayGeometryUtil.mapStreamPointToDisplay(2000f, -10f, 1920, 1080, 1920, 1080, false);
        assertEquals(1919f, dexPt[0], 0.01f);
        assertEquals(0f, dexPt[1], 0.01f);
    }

    @Test
    public void testUnifiedWorkAreaAndMirrorPlacementModes() {
        DisplayGeometryUtil.WorkArea waPinned = DisplayGeometryUtil.computeDesktopWorkArea(2560, 1440, 160, false);
        assertEquals(8, waPinned.left);
        assertEquals(8, waPinned.top);
        assertEquals(2552, waPinned.right);
        assertEquals(1440 - 72, waPinned.bottom);

        DisplayGeometryUtil.WorkArea waAutoHide = DisplayGeometryUtil.computeDesktopWorkArea(2560, 1440, 160, true);
        assertEquals(1440 - 8, waAutoHide.bottom);

        // Portrait 1080x2400 phone mirrored onto 1920x1080 canvas -> fit = 0.45 -> mirW = 486, mirH = 1080
        int[] centerDual = DisplayGeometryUtil.computeMirrorPlacement(
                1080, 2400, 1920, 1080, BridgeProtocol.MIRROR_SIDE_CENTER_DUAL);
        assertArrayEquals(new int[]{(1920 - 486) / 2, 0, 486, 1080}, centerDual);

        int[] alignLeft = DisplayGeometryUtil.computeMirrorPlacement(
                1080, 2400, 1920, 1080, BridgeProtocol.MIRROR_SIDE_ALIGN_LEFT);
        assertArrayEquals(new int[]{0, 0, 486, 1080}, alignLeft);

        int[] alignRight = DisplayGeometryUtil.computeMirrorPlacement(
                1080, 2400, 1920, 1080, BridgeProtocol.MIRROR_SIDE_ALIGN_RIGHT);
        assertArrayEquals(new int[]{1920 - 486, 0, 486, 1080}, alignRight);
    }
}
