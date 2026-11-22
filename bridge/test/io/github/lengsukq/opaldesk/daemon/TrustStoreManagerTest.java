package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TrustStoreManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testPinPairingIssuesTokenRotatesPinAndPersistsHashed() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "opaldesk_trusted_peers.conf");
        AtomicLong now = new AtomicLong(1_000_000L);
        TrustStoreManager mgr = new TrustStoreManager(storeFile, new SecureRandom(), now::get);

        String pin = mgr.getActivePin();
        assertTrue("PIN must be 6 digits", pin.matches("\\d{6}"));

        // Wrong PIN rejected
        TrustStoreManager.AuthResult wrong = mgr.authenticate("mac-01", "Leo Mac", "", "000000".equals(pin) ? "999999" : "000000", "192.168.1.10");
        assertFalse(wrong.ok);
        assertFalse(wrong.rateLimited);

        // Correct PIN pairs and issues 64-char hex token
        TrustStoreManager.AuthResult paired = mgr.authenticate("mac-01", "Leo's Mac=Pro;1", "", pin, "192.168.1.10");
        assertTrue(paired.ok);
        assertTrue(paired.pairedWithPin);
        assertTrue("Token must be 64-char lowercase hex", paired.issuedToken.matches("[0-9a-f]{64}"));

        // One-time PIN must rotate after pairing
        String rotatedPin = mgr.getActivePin();
        assertTrue(rotatedPin.matches("\\d{6}"));
        if (!rotatedPin.equals(pin)) {
            assertFalse(mgr.authenticate("mac-02", "Other", "", pin, "192.168.1.11").ok);
        }

        // Plaintext token must NOT be written directly to disk (only SHA-256 hash)
        String diskContent = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        assertFalse("Plaintext token must not appear on disk", diskContent.contains(paired.issuedToken));
        assertTrue(diskContent.contains("mac-01"));

        // Summary must sanitize '=' and ';' from clientName
        String summary = mgr.listTrustedClientsSummary();
        assertTrue(summary.startsWith("mac-01="));
        assertFalse(summary.contains(";1"));

        // Reload fresh TrustStoreManager from disk and authenticate via token without PIN
        TrustStoreManager reloaded = new TrustStoreManager(storeFile, new SecureRandom(), now::get);
        assertTrue(reloaded.isTrusted("mac-01"));
        assertEquals(1, reloaded.getTrustedCount());
        TrustStoreManager.AuthResult tokenAuth = reloaded.authenticate("mac-01", "Leo MacBook", paired.issuedToken, "", "192.168.1.10");
        assertTrue(tokenAuth.ok);
        assertFalse(tokenAuth.pairedWithPin);
        assertEquals("", tokenAuth.issuedToken);

        // Bad token rejected
        TrustStoreManager.AuthResult badTok = reloaded.authenticate("mac-01", "Leo MacBook", "deadbeef", "", "192.168.1.10");
        assertFalse(badTok.ok);
    }

    @Test
    public void testRateLimitingAndLockoutRecovery() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted.conf");
        AtomicLong now = new AtomicLong(5_000_000L);
        TrustStoreManager mgr = new TrustStoreManager(storeFile, new SecureRandom(), now::get);

        String validPin = mgr.getActivePin();
        String badPin = validPin.equals("123456") ? "654321" : "123456";

        for (int i = 0; i < TrustStoreManager.MAX_FAILURES_BEFORE_LOCKOUT - 1; i++) {
            TrustStoreManager.AuthResult r = mgr.authenticate("mac-x", "Attacker", "", badPin, "10.0.0.9");
            assertFalse(r.ok);
            assertFalse("Should not lock out before threshold", r.rateLimited);
        }

        // 5th failure triggers lockout
        TrustStoreManager.AuthResult locked = mgr.authenticate("mac-x", "Attacker", "", badPin, "10.0.0.9");
        assertFalse(locked.ok);
        assertTrue("5th failure must trigger rate-limit lockout", locked.rateLimited);

        // Even valid PIN must be rejected during lockout window
        now.addAndGet(5_000L);
        TrustStoreManager.AuthResult duringLock = mgr.authenticate("mac-x", "Legit", "", validPin, "10.0.0.9");
        assertFalse(duringLock.ok);
        assertTrue(duringLock.rateLimited);

        // Advance clock past BASE_LOCKOUT_MS -> valid PIN now succeeds
        now.addAndGet(TrustStoreManager.BASE_LOCKOUT_MS);
        TrustStoreManager.AuthResult afterLock = mgr.authenticate("mac-x", "Legit", "", mgr.getActivePin(), "10.0.0.9");
        assertTrue(afterLock.ok);
    }

    @Test
    public void testRevokeSingleAndRevokeAll() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_revoke.conf");
        TrustStoreManager mgr = new TrustStoreManager(storeFile);

        TrustStoreManager.AuthResult r1 = mgr.authenticate("mac-a", "Mac A", "", mgr.getActivePin(), "127.0.0.1");
        assertTrue(r1.ok);
        TrustStoreManager.AuthResult r2 = mgr.authenticate("mac-b", "Mac B", "", mgr.getActivePin(), "127.0.0.1");
        assertTrue(r2.ok);
        assertEquals(2, mgr.getTrustedCount());
        assertEquals("mac-a=Mac A;mac-b=Mac B", mgr.listTrustedClientsSummary());

        assertTrue(mgr.revokeClient("mac-a"));
        assertFalse(mgr.isTrusted("mac-a"));
        assertTrue(mgr.isTrusted("mac-b"));
        assertFalse(mgr.authenticate("mac-a", "Mac A", r1.issuedToken, "", "127.0.0.1").ok);

        assertTrue(mgr.revokeClient("*"));
        assertEquals(0, mgr.getTrustedCount());
        assertEquals("", mgr.listTrustedClientsSummary());

        // Verify persisted clear
        TrustStoreManager reloaded = new TrustStoreManager(storeFile);
        assertEquals(0, reloaded.getTrustedCount());
    }

    @Test
    public void testConstantTimeEqualsAndPinRegen() {
        assertTrue(TrustStoreManager.constantTimeEquals("123456", "123456"));
        assertFalse(TrustStoreManager.constantTimeEquals("123456", "123457"));
        assertFalse(TrustStoreManager.constantTimeEquals("123456", "12345"));
        assertFalse(TrustStoreManager.constantTimeEquals(null, "123456"));

        TrustStoreManager mgr = new TrustStoreManager(null);
        String p1 = mgr.getActivePin();
        boolean changed = false;
        for (int i = 0; i < 5; i++) {
            String next = mgr.regeneratePin();
            assertTrue(next.matches("\\d{6}"));
            if (!next.equals(p1)) changed = true;
        }
        assertTrue("Regenerated PIN should vary", changed);
        assertNotEquals("", mgr.getActivePin());
    }

    @Test
    public void testIssueTokenForAuthenticatedAdbPeer() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_upgrade.conf");
        TrustStoreManager mgr = new TrustStoreManager(storeFile);

        String tok = mgr.issueTokenForAuthenticatedPeer("mac-adb-1", "Leo MacBook");
        assertTrue("Token must be 64-char hex", tok.matches("[0-9a-f]{64}"));
        assertTrue(mgr.isTrusted("mac-adb-1"));

        String disk = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        assertFalse("Raw token must never be stored in plaintext", disk.contains(tok));

        TrustStoreManager reloaded = new TrustStoreManager(storeFile);
        TrustStoreManager.AuthResult auth = reloaded.authenticate("mac-adb-1", "Leo MacBook", tok, "", "10.10.10.50");
        assertTrue(auth.ok);
        assertFalse(auth.pairedWithPin);
    }
}
