package com.extbridge.helper.design.iosglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquircleGeometryTest {

    @Test
    fun clampRadiiCapsCornersToHalfShortestDimension() {
        val clamped = SquircleMath.clampRadii(
            width = 120f,
            height = 64f,
            raw = SquircleMath.CornerRadiiPx.uniform(999f),
        )
        assertEquals(32f, clamped.topLeft, 0.01f)
        assertEquals(32f, clamped.topRight, 0.01f)
        assertEquals(32f, clamped.bottomRight, 0.01f)
        assertEquals(32f, clamped.bottomLeft, 0.01f)
    }

    @Test
    fun adaptiveViewportBreakpointsV2ClassifiesPhoneTabletAndDesktop() {
        val phone = AdaptiveViewportInfo.fromDp(widthDp = 412, heightDp = 915)
        assertEquals(ViewportWidthTier.Compact, phone.widthTier)
        assertFalse(phone.isTwoPaneCapable)
        assertFalse(phone.isDesktopWide)

        val tablet = AdaptiveViewportInfo.fromDp(widthDp = 820, heightDp = 1180)
        assertEquals(ViewportWidthTier.Medium, tablet.widthTier)
        assertTrue(tablet.isTwoPaneCapable)
        assertFalse(tablet.isDesktopWide)

        val largeDesktop = AdaptiveViewportInfo.fromDp(widthDp = 1280, heightDp = 800)
        assertEquals(ViewportWidthTier.Large, largeDesktop.widthTier)
        assertTrue(largeDesktop.isDesktopWide)

        val extraLargeDesktop = AdaptiveViewportInfo.fromDp(widthDp = 1920, heightDp = 1080)
        assertEquals(ViewportWidthTier.ExtraLarge, extraLargeDesktop.widthTier)
        assertTrue(extraLargeDesktop.isDesktopWide)
    }
}
