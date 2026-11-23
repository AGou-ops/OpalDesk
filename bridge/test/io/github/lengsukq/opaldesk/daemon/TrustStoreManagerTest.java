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
import static org.junit.Assert.assertTrue;

public class TrustStoreManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testGrantPermanentTrustPersistsHashedAndAuthenticatesAcrossReload() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "opaldesk_trusted_peers.conf");
        AtomicLong now = new AtomicLong(1_000_000L);
        TrustStoreManager mgr = new TrustStoreManager(storeFile, new SecureRandom(), now::get);

        // Unknown client without token needs host approval
        TrustStoreManager.TokenVerifyResult initial = mgr.verifyTokenOrCheckRateLimit(
                "tab-01", "Xiaomi Pad", "", "192.168.1.10");
        assertEquals(TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL, initial.status);
        assertFalse(initial.isAuthenticated());

        // Grant permanent trust issues 64-char hex token & persists only SHA-256 hash
        String token = mgr.grantPermanentTrust("tab-01", "Leo's Pad=Pro;1", "192.168.1.10");
        assertTrue("Token must be 64-char lowercase hex", token.matches("[0-9a-f]{64}"));

        String diskContent = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        assertFalse("Plaintext token must not appear on disk", diskContent.contains(token));
        assertTrue(diskContent.contains("tab-01"));

        // Summary must sanitize '=' and ';' from clientName
        String summary = mgr.listTrustedClientsSummary();
        assertTrue(summary.startsWith("tab-01="));
        assertFalse(summary.contains(";1"));

        // Reload fresh TrustStoreManager from disk and authenticate silently with permanent token
        TrustStoreManager reloaded = new TrustStoreManager(storeFile, new SecureRandom(), now::get);
        assertTrue(reloaded.isTrusted("tab-01"));
        assertEquals(1, reloaded.getTrustedCount());
        assertTrue(reloaded.toTrustedPeersJson().contains("\"clientId\":\"tab-01\""));

        TrustStoreManager.TokenVerifyResult tokenAuth = reloaded.verifyTokenOrCheckRateLimit(
                "tab-01", "Xiaomi Pad 7", token, "192.168.1.10");
        assertTrue(tokenAuth.isAuthenticated());
        assertTrue(tokenAuth.isPermanent());

        // Bad/stale token falls back to NEEDS_APPROVAL instead of hard-locking out revoked clients
        TrustStoreManager.TokenVerifyResult badTok = reloaded.verifyTokenOrCheckRateLimit(
                "tab-01", "Xiaomi Pad 7", "deadbeef", "192.168.1.10");
        assertEquals(TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL, badTok.status);
        assertFalse(badTok.isAuthenticated());
    }

    @Test
    public void testSingleUseHandoverTokenIsInMemoryOnlyAndConsumedOnce() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_ephemeral.conf");
        AtomicLong now = new AtomicLong(2_000_000L);
        TrustStoreManager mgr = new TrustStoreManager(storeFile, new SecureRandom(), now::get);

        String eph = mgr.issueSingleUseHandoverToken("tab-02", "192.168.1.20");
        assertTrue(eph.matches("[0-9a-f]{64}"));
        assertEquals("Handover token must not increase permanent trusted count", 0, mgr.getTrustedCount());
        assertFalse("Handover token must not persist to disk", storeFile.exists());

        // First verification succeeds as ephemeral (not permanent)
        TrustStoreManager.TokenVerifyResult first = mgr.verifyTokenOrCheckRateLimit(
                "tab-02", "Pad", eph, "192.168.1.20");
        assertTrue(first.isAuthenticated());
        assertFalse(first.isPermanent());

        // Replay must fail and require fresh host approval
        TrustStoreManager.TokenVerifyResult replay = mgr.verifyTokenOrCheckRateLimit(
                "tab-02", "Pad", eph, "192.168.1.20");
        assertEquals(TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL, replay.status);

        // Expiry check
        String eph2 = mgr.issueSingleUseHandoverToken("tab-02", "192.168.1.20");
        now.addAndGet(TrustStoreManager.EPHEMERAL_HANDOVER_TTL_MS + 1000L);
        TrustStoreManager.TokenVerifyResult expired = mgr.verifyTokenOrCheckRateLimit(
                "tab-02", "Pad", eph2, "192.168.1.20");
        assertEquals(TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL, expired.status);
    }

    @Test
    public void testRejectionRateLimitingAndLockoutRecovery() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_lockout.conf");
        AtomicLong now = new AtomicLong(5_000_000L);
        TrustStoreManager mgr = new TrustStoreManager(storeFile, new SecureRandom(), now::get);

        for (int i = 0; i < TrustStoreManager.MAX_FAILURES_BEFORE_LOCKOUT - 1; i++) {
            mgr.recordRejection("10.0.0.9");
            TrustStoreManager.TokenVerifyResult r = mgr.verifyTokenOrCheckRateLimit(
                    "tab-x", "Spammer", "", "10.0.0.9");
            assertFalse("Should not lock out before threshold", r.isRateLimited());
        }

        // 5th rejection triggers lockout
        mgr.recordRejection("10.0.0.9");
        TrustStoreManager.TokenVerifyResult locked = mgr.verifyTokenOrCheckRateLimit(
                "tab-x", "Spammer", "", "10.0.0.9");
        assertTrue("5th rejection must trigger rate-limit lockout", locked.isRateLimited());

        // Still locked during lockout window
        now.addAndGet(5_000L);
        assertTrue(mgr.verifyTokenOrCheckRateLimit("tab-x", "Legit", "", "10.0.0.9").isRateLimited());

        // Advance clock past BASE_LOCKOUT_MS -> allowed to ask host again
        now.addAndGet(TrustStoreManager.BASE_LOCKOUT_MS);
        TrustStoreManager.TokenVerifyResult afterLock = mgr.verifyTokenOrCheckRateLimit(
                "tab-x", "Legit", "", "10.0.0.9");
        assertEquals(TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL, afterLock.status);
    }

    @Test
    public void testRevokeSingleAndRevokeAll() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_revoke.conf");
        TrustStoreManager mgr = new TrustStoreManager(storeFile);

        String t1 = mgr.grantPermanentTrust("mac-a", "Mac A", "127.0.0.1");
        String t2 = mgr.grantPermanentTrust("mac-b", "Mac B", "127.0.0.1");
        assertFalse(t1.isEmpty());
        assertFalse(t2.isEmpty());
        assertEquals(2, mgr.getTrustedCount());
        assertEquals("mac-a=Mac A;mac-b=Mac B", mgr.listTrustedClientsSummary());

        assertTrue(mgr.revokeClient("mac-a"));
        assertFalse(mgr.isTrusted("mac-a"));
        assertTrue(mgr.isTrusted("mac-b"));
        assertEquals(
                TrustStoreManager.TokenVerifyResult.STATUS_NEEDS_APPROVAL,
                mgr.verifyTokenOrCheckRateLimit("mac-a", "Mac A", t1, "127.0.0.1").status);

        assertTrue(mgr.revokeClient("*"));
        assertEquals(0, mgr.getTrustedCount());
        assertEquals("", mgr.listTrustedClientsSummary());

        // Verify persisted clear
        TrustStoreManager reloaded = new TrustStoreManager(storeFile);
        assertEquals(0, reloaded.getTrustedCount());
    }

    @Test
    public void testConstantTimeEqualsAndAdbUpgradeToken() throws Exception {
        assertTrue(TrustStoreManager.constantTimeEquals("abcdef", "abcdef"));
        assertFalse(TrustStoreManager.constantTimeEquals("abcdef", "abcdeg"));
        assertFalse(TrustStoreManager.constantTimeEquals("abcdef", "abcde"));
        assertFalse(TrustStoreManager.constantTimeEquals(null, "abcdef"));

        File storeFile = new File(tmpDir.newFolder(), "trusted_upgrade.conf");
        TrustStoreManager mgr = new TrustStoreManager(storeFile);

        String tok = mgr.issueTokenForAuthenticatedPeer("mac-adb-1", "Leo MacBook");
        assertTrue("Token must be 64-char hex", tok.matches("[0-9a-f]{64}"));
        assertTrue(mgr.isTrusted("mac-adb-1"));

        String disk = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        assertFalse("Raw token must never be stored in plaintext", disk.contains(tok));

        TrustStoreManager reloaded = new TrustStoreManager(storeFile);
        TrustStoreManager.TokenVerifyResult auth = reloaded.verifyTokenOrCheckRateLimit(
                "mac-adb-1", "Leo MacBook", tok, "10.10.10.50");
        assertTrue(auth.isAuthenticated());
        assertTrue(auth.isPermanent());
    }
}
