package com.extbridge.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDiscoveryManagerTest {

    @Test
    fun buildTxtAttributesProducesExpectedSpecFields() {
        val fullFp = "AB:CD:EF:01:23:45:67:89:AA:BB:CC:DD:EE:FF:00:11"
        val attrs = RemoteDiscoveryManager.buildTxtAttributes(
            deviceName = "Xiaomi Pad 7 Pro",
            model = "2410CRP4CC",
            certFingerprint = fullFp,
        )
        assertEquals("Xiaomi Pad 7 Pro", attrs["name"])
        assertEquals("2410CRP4CC", attrs["model"])
        assertEquals("abcdef0123456789", attrs["fp"])
        assertEquals("1", attrs["ver"])
    }

    @Test
    fun sanitizeServiceNamePrefixesAndLimitsLength() {
        assertEquals("ExtBridge-Xiaomi Pad 7", RemoteDiscoveryManager.sanitizeServiceName("Xiaomi Pad 7"))
        assertEquals("ExtBridge-MyPhone", RemoteDiscoveryManager.sanitizeServiceName("ExtBridge-MyPhone"))
        assertEquals("ExtBridge", RemoteDiscoveryManager.sanitizeServiceName("!!!"))
        val veryLong = RemoteDiscoveryManager.sanitizeServiceName("A".repeat(80))
        assertTrue(veryLong.length <= 48)
    }

    @Test
    fun registrationSignatureChangesWhenPortOrFingerprintChanges() {
        val s1 = RemoteDiscoveryManager.buildRegistrationSignature(19876, "0123456789abcdef9999", "Pad", "M1")
        val s2 = RemoteDiscoveryManager.buildRegistrationSignature(19876, "0123456789abcdef0000", "Pad", "M1")
        val s3 = RemoteDiscoveryManager.buildRegistrationSignature(19877, "0123456789abcdef9999", "Pad", "M1")
        assertEquals(s1, s2) // Only first 16 hex chars of fp are advertised
        assertNotEquals(s1, s3)
    }
}
