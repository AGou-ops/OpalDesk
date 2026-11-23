package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.ui.QrCodeMatrix
import io.github.lengsukq.opaldesk.web.WebActivationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeMatrixTest {

    @Test
    fun encodesShortAndFullActivationUrlsWithValidFinderPatterns() {
        val url = WebActivationPolicy.buildWebActivationUrl(
            lanIp = "192.168.31.88",
            actPort = 19879,
            castPort = 19878,
            sessionCode = "482915",
        )
        val matrix = QrCodeMatrix.encode(url)
        assertTrue("Expected version 5..8 for ~115-char URL, got ${matrix.version}", matrix.version in 5..8)
        assertEquals(matrix.version * 4 + 17, matrix.size)
        assertEquals(matrix.size * matrix.size, matrix.modules.size)

        // Verify 7x7 outer ring and 3x3 center of Top-Left finder pattern
        for (i in 0..6) {
            assertTrue(matrix.get(i, 0))
            assertTrue(matrix.get(i, 6))
            assertTrue(matrix.get(0, i))
            assertTrue(matrix.get(6, i))
        }
        assertFalse(matrix.get(1, 1))
        assertTrue(matrix.get(3, 3))
        // White separator around Top-Left finder
        assertFalse(matrix.get(7, 0))
        assertFalse(matrix.get(0, 7))
        // Always-dark module at (8, size - 8)
        assertTrue(matrix.get(8, matrix.size - 8))
    }

    @Test
    fun encodesVersion1MinimumPayload() {
        val m = QrCodeMatrix.encode("HELLO")
        assertEquals(1, m.version)
        assertEquals(21, m.size)
        assertTrue(m.get(3, 3))
        assertTrue(m.get(8, 13))
    }
}
