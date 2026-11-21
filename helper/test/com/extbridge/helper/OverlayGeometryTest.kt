package com.extbridge.helper

import com.extbridge.helper.overlay.OverlayConstants
import com.extbridge.helper.overlay.mirror.MirrorMetricsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun overlayConstantsIdentifiesSystemAndLauncherPackagesOnly() {
        assertTrue(OverlayConstants.isSystemOrLauncherPkg(null))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg(""))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("   "))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("com.extbridge.helper"))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("android"))
        assertTrue(OverlayConstants.isSystemOrLauncherPkg("com.android.systemui"))

        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.tencent.mm"))
        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.android.settings"))
        assertFalse(OverlayConstants.isSystemOrLauncherPkg("com.extbridge.helper.other"))
    }

    @Test
    fun mirrorMetricsSamplerFormatsSpeedRatesAndCountdowns() {
        assertEquals("0 B/s", MirrorMetricsSampler.formatSpeedRate(-100.0))
        assertEquals("0 B/s", MirrorMetricsSampler.formatSpeedRate(0.0))
        assertEquals("512 B/s", MirrorMetricsSampler.formatSpeedRate(512.4))

        assertEquals("1.0 KB/s", MirrorMetricsSampler.formatSpeedRate(1024.0))
        assertEquals("1.5 KB/s", MirrorMetricsSampler.formatSpeedRate(1536.0))
        assertEquals("512.0 KB/s", MirrorMetricsSampler.formatSpeedRate(512.0 * 1024.0))

        assertEquals("1.00 MB/s", MirrorMetricsSampler.formatSpeedRate(1024.0 * 1024.0))
        assertEquals("2.50 MB/s", MirrorMetricsSampler.formatSpeedRate(2.5 * 1024.0 * 1024.0))

        assertEquals("00:00", MirrorMetricsSampler.formatCountdown(-15L))
        assertEquals("00:00", MirrorMetricsSampler.formatCountdown(0L))
        assertEquals("00:05", MirrorMetricsSampler.formatCountdown(5L))
        assertEquals("00:59", MirrorMetricsSampler.formatCountdown(59L))
        assertEquals("01:00", MirrorMetricsSampler.formatCountdown(60L))
        assertEquals("25:00", MirrorMetricsSampler.formatCountdown(1500L))
        assertEquals("61:01", MirrorMetricsSampler.formatCountdown(3661L))
    }
}
