package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.web.LocalTlsCert
import io.github.lengsukq.opaldesk.web.MiniWebSocketServer
import io.github.lengsukq.opaldesk.web.WebActivationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebActivationPolicyTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun originAllowlistAndReturnUrlSanitization() {
        assertTrue(WebActivationPolicy.isAllowedOrigin("https://lengsukq.github.io"))
        assertTrue(WebActivationPolicy.isAllowedOrigin("http://localhost:8080"))
        assertTrue(WebActivationPolicy.isAllowedOrigin("http://127.0.0.1:4173"))

        assertFalse(WebActivationPolicy.isAllowedOrigin("http://lengsukq.github.io"))
        assertFalse(WebActivationPolicy.isAllowedOrigin("https://lengsukq.github.io.attacker.test"))
        assertFalse(WebActivationPolicy.isAllowedOrigin("https://attacker.test"))
        assertFalse(WebActivationPolicy.isAllowedOrigin("null"))

        assertEquals(
            "https://lengsukq.github.io/OpalDesk/cast/?mode=activate",
            WebActivationPolicy.sanitizeReturnUrl("https://lengsukq.github.io/OpalDesk/cast/?mode=activate"),
        )
        assertEquals(
            WebActivationPolicy.DEFAULT_WEB_CAST_URL,
            WebActivationPolicy.sanitizeReturnUrl("https://evil.test/steal"),
        )
        assertEquals(
            WebActivationPolicy.DEFAULT_WEB_CAST_URL,
            WebActivationPolicy.sanitizeReturnUrl("javascript:alert(1)"),
        )
    }

    @Test
    fun activationUrlPlacesSessionCodeStrictlyInFragment() {
        val url = WebActivationPolicy.buildWebActivationUrl(
            lanIp = "192.168.31.88",
            actPort = 19879,
            castPort = 19878,
            sessionCode = "482915",
        )
        val beforeHash = url.substringBefore('#')
        val afterHash = url.substringAfter('#')
        assertTrue(beforeHash.startsWith("https://lengsukq.github.io/OpalDesk/cast/?"))
        assertTrue(beforeHash.contains("mode=activate"))
        assertTrue(beforeHash.contains("host=192.168.31.88"))
        assertFalse("Session code must never leak into query string", beforeHash.contains("482915"))
        assertEquals("code=482915", afterHash)
    }

    @Test
    fun sessionCodeGateRotatesAfterMaxFailures() {
        val gate = WebActivationPolicy.SessionCodeGate(initialCode = "123456", maxFailures = 3)
        assertFalse(gate.verify("000000").ok)
        assertFalse(gate.verify("111111").ok)
        val third = gate.verify("222222")
        assertFalse(third.ok)
        assertTrue(third.rotated)
        assertNotEquals("123456", third.newCode)
        assertTrue(WebActivationPolicy.isValidSixDigitCode(third.newCode))

        val ok = gate.verify(third.newCode)
        assertTrue(ok.ok)
        assertFalse(ok.rotated)
    }

    @Test
    fun localTlsCertEmbedsLanIpSanAndStaysUnder398Days() {
        val store = File(tmpDir.newFolder(), "web_act.p12")
        val id = LocalTlsCert.loadOrCreate(store, "Xiaomi 14 Pro", "192.168.31.88")
        assertTrue(id.sha256Fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        val validityDays = (id.certificate.notAfter.time - id.certificate.notBefore.time) / (24L * 60L * 60L * 1000L)
        assertTrue("Browser cert validity should be <= 398 days, got $validityDays", validityDays in 360..398)

        val sans = id.certificate.subjectAlternativeNames.orEmpty()
        assertTrue(sans.any { it.size >= 2 && it[0] == 7 && it[1] == "192.168.31.88" })

        // Same IP reloads exact same fingerprint; changed IP regenerates cert with new SAN
        val reloaded = LocalTlsCert.loadOrCreate(store, "Xiaomi 14 Pro", "192.168.31.88")
        assertEquals(id.sha256Fingerprint, reloaded.sha256Fingerprint)

        val rotated = LocalTlsCert.loadOrCreate(store, "Xiaomi 14 Pro", "192.168.31.99")
        assertNotEquals(id.sha256Fingerprint, rotated.sha256Fingerprint)
        assertTrue(rotated.certificate.subjectAlternativeNames.orEmpty().any { it.size >= 2 && it[0] == 7 && it[1] == "192.168.31.99" })
    }

    @Test
    fun miniWebSocketAcceptKeyMatchesRfcVector() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            MiniWebSocketServer.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }
}
