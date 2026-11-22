package io.github.lengsukq.opaldesk.daemon.security;

import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manages 6-digit one-time pairing PINs, anti-bruteforce exponential backoff,
 * constant-time credential verification, 32-byte hex authToken issuance, and
 * atomic trusted-client persistence at /data/local/tmp/opaldesk_trusted_peers.conf.
 */
public final class TrustStoreManager {
    public static final String DEFAULT_STORE_PATH = "/data/local/tmp/opaldesk_trusted_peers.conf";
    public static final int MAX_TRUSTED_CLIENTS = 64;
    public static final int MAX_FAILURES_BEFORE_LOCKOUT = 5;
    public static final long BASE_LOCKOUT_MS = 30_000L;
    public static final long MAX_LOCKOUT_MS = 300_000L;
    public static final long FAILURE_WINDOW_RESET_MS = 10L * 60_000L;

    public interface Clock {
        long nowMs();
    }

    public static final class TrustedPeer {
        public final String clientId;
        public final String clientName;
        public final String tokenSha256;
        public final long createdAtMs;

        public TrustedPeer(String clientId, String clientName, String tokenSha256, long createdAtMs) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.tokenSha256 = tokenSha256;
            this.createdAtMs = createdAtMs;
        }
    }

    public static final class AuthResult {
        public final boolean ok;
        public final boolean pairedWithPin;
        public final boolean rateLimited;
        public final String issuedToken;
        public final String message;

        private AuthResult(boolean ok, boolean pairedWithPin, boolean rateLimited, String issuedToken, String message) {
            this.ok = ok;
            this.pairedWithPin = pairedWithPin;
            this.rateLimited = rateLimited;
            this.issuedToken = issuedToken != null ? issuedToken : "";
            this.message = message != null ? message : "";
        }

        public static AuthResult okToken() {
            return new AuthResult(true, false, false, "", "Authenticated with trusted token");
        }

        public static AuthResult okPaired(String newToken) {
            return new AuthResult(true, true, false, newToken, "Paired with one-time PIN");
        }

        public static AuthResult rejected(String message) {
            return new AuthResult(false, false, false, "", message);
        }

        public static AuthResult rateLimited(long retryAfterMs) {
            long sec = Math.max(1L, (retryAfterMs + 999L) / 1000L);
            return new AuthResult(false, false, true, "", "Too many failed attempts; retry in " + sec + "s");
        }
    }

    private static final class FailureBucket {
        int consecutiveFailures;
        long lastFailureMs;
        long lockedUntilMs;
    }

    private final File storeFile;
    private final SecureRandom rng;
    private final Clock clock;
    private final Object lock = new Object();
    private final LinkedHashMap<String, TrustedPeer> peers = new LinkedHashMap<>();
    private final Map<String, FailureBucket> failureBuckets = new HashMap<>();
    private final FailureBucket globalFailures = new FailureBucket();
    private String activePin;

    public TrustStoreManager() {
        this(new File(DEFAULT_STORE_PATH), new SecureRandom(), System::currentTimeMillis);
    }

    public TrustStoreManager(File storeFile) {
        this(storeFile, new SecureRandom(), System::currentTimeMillis);
    }

    public TrustStoreManager(File storeFile, SecureRandom rng, Clock clock) {
        this.storeFile = storeFile;
        this.rng = (rng != null) ? rng : new SecureRandom();
        this.clock = (clock != null) ? clock : System::currentTimeMillis;
        this.activePin = generateRandomPin();
        loadFromDisk();
    }

    public String getActivePin() {
        synchronized (lock) {
            if (activePin == null || activePin.length() != 6) {
                activePin = generateRandomPin();
            }
            return activePin;
        }
    }

    public String regeneratePin() {
        synchronized (lock) {
            activePin = generateRandomPin();
            return activePin;
        }
    }

    /**
     * Authenticates a Wi-Fi client using either an existing trusted authToken or the active 6-digit PIN.
     */
    public AuthResult authenticate(
            String rawClientId,
            String rawClientName,
            String providedToken,
            String providedPin,
            String peerRateLimitKey) {
        String clientId = sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) {
            return AuthResult.rejected("Missing or invalid clientId");
        }
        String clientName = sanitizeClientName(rawClientName, clientId);
        String bucketKey = (peerRateLimitKey == null || peerRateLimitKey.trim().isEmpty())
                ? "unknown" : peerRateLimitKey.trim();

        synchronized (lock) {
            long now = clock.nowMs();

            // 1. Check trusted token first (allowed when client already paired).
            if (providedToken != null && !providedToken.isEmpty()) {
                TrustedPeer existing = peers.get(clientId);
                if (existing != null) {
                    String providedHash = CryptoHex.sha256Hex(providedToken);
                    if (constantTimeEquals(existing.tokenSha256, providedHash)) {
                        clearFailuresLocked(bucketKey);
                        if (!existing.clientName.equals(clientName)) {
                            peers.put(clientId, new TrustedPeer(
                                    clientId, clientName, existing.tokenSha256, existing.createdAtMs));
                            saveToDiskLocked();
                        }
                        return AuthResult.okToken();
                    }
                }
                // If token is invalid and no PIN was provided, reject & count failure to deter token probing.
                if (providedPin == null || providedPin.isEmpty()) {
                    long lockWait = checkLockoutLocked(bucketKey, now);
                    if (lockWait > 0) {
                        return AuthResult.rateLimited(lockWait);
                    }
                    recordFailureLocked(bucketKey, now);
                    return AuthResult.rejected("Invalid or revoked authToken");
                }
            }

            // 2. Check lockout before PIN verification.
            long lockWait = checkLockoutLocked(bucketKey, now);
            if (lockWait > 0) {
                return AuthResult.rateLimited(lockWait);
            }

            // 3. Verify 6-digit one-time PIN in constant time.
            String pin = (providedPin == null) ? "" : providedPin.trim();
            if (pin.isEmpty()) {
                return AuthResult.rejected("Pairing PIN required");
            }
            if (!constantTimeEquals(activePin, pin)) {
                recordFailureLocked(bucketKey, now);
                long postWait = checkLockoutLocked(bucketKey, now);
                if (postWait > 0) {
                    return AuthResult.rateLimited(postWait);
                }
                return AuthResult.rejected("Invalid pairing PIN");
            }

            // 4. PIN accepted: issue fresh 32-byte random hex token, persist, and rotate one-time PIN!
            clearFailuresLocked(bucketKey);
            String newToken = generateRandomTokenHex(32);
            String tokenHash = CryptoHex.sha256Hex(newToken);
            if (!peers.containsKey(clientId) && peers.size() >= MAX_TRUSTED_CLIENTS) {
                // Evict oldest trusted client to keep store bounded.
                String oldestKey = peers.keySet().iterator().next();
                peers.remove(oldestKey);
            }
            peers.put(clientId, new TrustedPeer(clientId, clientName, tokenHash, now));
            saveToDiskLocked();
            activePin = generateRandomPin();
            return AuthResult.okPaired(newToken);
        }
    }

    /**
     * Issues and persists a fresh trusted 32-byte hex authToken for a peer that was already
     * authenticated over a trusted USB/ADB LocalSocket channel (used by REQUEST_WIFI_UPGRADE).
     */
    public String issueTokenForAuthenticatedPeer(String rawClientId, String rawClientName) {
        String clientId = sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) {
            return "";
        }
        String clientName = sanitizeClientName(rawClientName, clientId);
        synchronized (lock) {
            long now = clock.nowMs();
            String newToken = generateRandomTokenHex(32);
            String tokenHash = CryptoHex.sha256Hex(newToken);
            if (!peers.containsKey(clientId) && peers.size() >= MAX_TRUSTED_CLIENTS) {
                String oldestKey = peers.keySet().iterator().next();
                peers.remove(oldestKey);
            }
            peers.put(clientId, new TrustedPeer(clientId, clientName, tokenHash, now));
            saveToDiskLocked();
            return newToken;
        }
    }

    public boolean isTrusted(String rawClientId) {
        String cid = sanitizeClientId(rawClientId);
        if (cid.isEmpty()) return false;
        synchronized (lock) {
            return peers.containsKey(cid);
        }
    }

    public int getTrustedCount() {
        synchronized (lock) {
            return peers.size();
        }
    }

    /**
     * Formats trusted clients per Binder code 46 contract: {@code clientId=clientName;...}
     */
    public String listTrustedClientsSummary() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (TrustedPeer p : peers.values()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(p.clientId).append('=').append(p.clientName);
            }
            return sb.toString();
        }
    }

    /**
     * Revokes a single clientId or clears all trusted clients when {@code "*"} is passed.
     */
    public boolean revokeClient(String rawClientId) {
        if (rawClientId == null) return false;
        String trimmed = rawClientId.trim();
        synchronized (lock) {
            if ("*".equals(trimmed)) {
                peers.clear();
                saveToDiskLocked();
                return true;
            }
            String cid = sanitizeClientId(trimmed);
            if (cid.isEmpty()) return false;
            boolean removed = (peers.remove(cid) != null);
            if (removed) {
                saveToDiskLocked();
            }
            return removed;
        }
    }

    /**
     * Constant-time string equality via SHA-256 digest comparison to eliminate
     * both character-position and string-length timing side channels.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] da = md.digest(a.getBytes(StandardCharsets.UTF_8));
            md.reset();
            byte[] db = md.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(da, db);
        } catch (Throwable t) {
            byte[] ba = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            int diff = ba.length ^ bb.length;
            int max = Math.max(ba.length, bb.length);
            for (int i = 0; i < max; i++) {
                byte x = (i < ba.length) ? ba[i] : 0;
                byte y = (i < bb.length) ? bb[i] : 0;
                diff |= (x ^ y);
            }
            return diff == 0;
        }
    }

    public static String sanitizeClientId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 96));
        for (int i = 0; i < raw.length() && sb.length() < 96; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String sanitizeClientName(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback != null ? fallback : "Mac";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 64));
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '=' || c == ';' || c == '\t' || c == '\r' || c == '\n') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? (fallback != null ? fallback : "Mac") : cleaned;
    }

    private String generateRandomPin() {
        int val = rng.nextInt(1_000_000);
        return String.format(Locale.US, "%06d", val);
    }

    private String generateRandomTokenHex(int numBytes) {
        byte[] buf = new byte[numBytes];
        rng.nextBytes(buf);
        return CryptoHex.toHex(buf);
    }

    private long checkLockoutLocked(String bucketKey, long now) {
        long globalRem = Math.max(0L, globalFailures.lockedUntilMs - now);
        FailureBucket peer = failureBuckets.get(bucketKey);
        long peerRem = (peer != null) ? Math.max(0L, peer.lockedUntilMs - now) : 0L;
        return Math.max(globalRem, peerRem);
    }

    private void recordFailureLocked(String bucketKey, long now) {
        FailureBucket peer = failureBuckets.computeIfAbsent(bucketKey, k -> new FailureBucket());
        incrementBucket(peer, now);
        incrementBucket(globalFailures, now);
        // Prevent unbounded map growth from spoofed keys
        if (failureBuckets.size() > 128) {
            failureBuckets.entrySet().removeIf(e ->
                    e.getValue().lockedUntilMs <= now && (now - e.getValue().lastFailureMs) > FAILURE_WINDOW_RESET_MS);
        }
        // Rotate PIN after severe global brute-force pressure
        if (globalFailures.consecutiveFailures >= MAX_FAILURES_BEFORE_LOCKOUT * 2) {
            activePin = generateRandomPin();
        }
    }

    private static void incrementBucket(FailureBucket b, long now) {
        if (b.lastFailureMs > 0 && (now - b.lastFailureMs) > FAILURE_WINDOW_RESET_MS) {
            b.consecutiveFailures = 0;
            b.lockedUntilMs = 0L;
        }
        b.consecutiveFailures++;
        b.lastFailureMs = now;
        if (b.consecutiveFailures >= MAX_FAILURES_BEFORE_LOCKOUT) {
            int shift = Math.min(4, b.consecutiveFailures - MAX_FAILURES_BEFORE_LOCKOUT);
            long dur = Math.min(MAX_LOCKOUT_MS, BASE_LOCKOUT_MS << shift);
            b.lockedUntilMs = Math.max(b.lockedUntilMs, now + dur);
        }
    }

    private void clearFailuresLocked(String bucketKey) {
        failureBuckets.remove(bucketKey);
        globalFailures.consecutiveFailures = 0;
        globalFailures.lockedUntilMs = 0L;
    }

    private void loadFromDisk() {
        if (storeFile == null || !storeFile.isFile()) return;
        synchronized (lock) {
            peers.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    String[] parts = trimmed.split("\t", -1);
                    if (parts.length < 3) continue;
                    String cid = sanitizeClientId(parts[0]);
                    String name = sanitizeClientName(parts[1], cid);
                    String tokenHash = parts[2].trim().toLowerCase(Locale.US);
                    long created = 0L;
                    if (parts.length >= 4) {
                        try {
                            created = Long.parseLong(parts[3].trim());
                        } catch (NumberFormatException nfe) {
                            System.err.println("OPALDESK_TRUSTSTORE_BAD_TS clientId=" + cid
                                    + " raw=" + parts[3].trim());
                        }
                    }
                    if (!cid.isEmpty() && tokenHash.length() == 64 && peers.size() < MAX_TRUSTED_CLIENTS) {
                        peers.put(cid, new TrustedPeer(cid, name, tokenHash, created));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("OPALDESK_TRUSTSTORE_LOAD_ERR path=" + storeFile.getAbsolutePath()
                        + ": " + e.getMessage());
            }
        }
    }

    private void saveToDiskLocked() {
        if (storeFile == null) return;
        StringBuilder sb = new StringBuilder();
        for (TrustedPeer p : peers.values()) {
            sb.append(p.clientId).append('\t')
              .append(p.clientName).append('\t')
              .append(p.tokenSha256).append('\t')
              .append(p.createdAtMs).append('\n');
        }
        try {
            AtomicFiles.writeAtomicUtf8(storeFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
        } catch (IOException | RuntimeException e) {
            System.err.println("OPALDESK_TRUSTSTORE_SAVE_ERR path=" + storeFile.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }
}
