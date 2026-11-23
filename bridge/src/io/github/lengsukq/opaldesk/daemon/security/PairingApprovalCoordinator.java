package io.github.lengsukq.opaldesk.daemon.security;

import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates interactive host-side approval for incoming wireless cast pairing requests
 * (replacing static 6-digit PINs).
 *
 * <p>Holds each unauthenticated Wi-Fi TLS handshake on a bounded wait until the host user chooses
 * {@link #DECISION_APPROVE_ONCE}, {@link #DECISION_APPROVE_PERMANENT}, or {@link #DECISION_REJECT},
 * or until the request times out or is cancelled.
 */
public final class PairingApprovalCoordinator {
    public static final int DECISION_REJECT = 0;
    public static final int DECISION_APPROVE_ONCE = 1;
    public static final int DECISION_APPROVE_PERMANENT = 2;
    public static final int DECISION_TIMED_OUT = -1;
    public static final int DECISION_CANCELLED = -2;
    public static final int DECISION_BUSY = -3;

    public static final long DEFAULT_TIMEOUT_MS = 60_000L;
    public static final int MAX_PENDING_REQUESTS = 4;

    public interface Clock {
        long nowMs();
    }

    public interface ChangeListener {
        /** Invoked whenever the front-most pending request changes (or becomes {@code null}). */
        void onActiveRequestChanged(PendingRequest activeRequest);
    }

    public static final class PendingRequest {
        public final String requestId;
        public final String clientId;
        public final String clientName;
        public final String peerIp;
        public final long createdAtMs;
        public final long expiresAtMs;

        public PendingRequest(
                String requestId,
                String clientId,
                String clientName,
                String peerIp,
                long createdAtMs,
                long expiresAtMs) {
            this.requestId = requestId;
            this.clientId = clientId;
            this.clientName = clientName;
            this.peerIp = peerIp;
            this.createdAtMs = createdAtMs;
            this.expiresAtMs = expiresAtMs;
        }

        public String toJson() {
            return new SimpleJson.ObjectBuilder()
                    .put("requestId", requestId)
                    .put("clientId", clientId)
                    .put("clientName", clientName)
                    .put("peerIp", peerIp)
                    .put("createdAtMs", createdAtMs)
                    .put("expiresAtMs", expiresAtMs)
                    .build();
        }
    }

    private static final class Entry {
        final PendingRequest request;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int decision = DECISION_TIMED_OUT;
        volatile boolean resolved = false;

        Entry(PendingRequest request) {
            this.request = request;
        }

        void resolve(int d) {
            if (!resolved) {
                resolved = true;
                decision = d;
                latch.countDown();
            }
        }
    }

    private final Clock clock;
    private final SecureRandom rng;
    private final Object lock = new Object();
    private final Deque<Entry> queue = new ArrayDeque<>();
    private volatile ChangeListener listener;

    public PairingApprovalCoordinator() {
        this(System::currentTimeMillis, new SecureRandom());
    }

    public PairingApprovalCoordinator(Clock clock, SecureRandom rng) {
        this.clock = (clock != null) ? clock : System::currentTimeMillis;
        this.rng = (rng != null) ? rng : new SecureRandom();
    }

    public void setChangeListener(ChangeListener listener) {
        this.listener = listener;
    }

    public PendingRequest getActivePendingRequest() {
        synchronized (lock) {
            Entry head = queue.peekFirst();
            return (head != null) ? head.request : null;
        }
    }

    public String getActivePendingRequestJson() {
        PendingRequest req = getActivePendingRequest();
        return (req != null) ? req.toJson() : "";
    }

    /**
     * Enqueues a pairing approval request and blocks the calling handshake thread until the host
     * responds, cancels, or {@code timeoutMs} elapses.
     */
    public int requestAndAwait(
            String rawClientId,
            String rawClientName,
            String rawPeerIp,
            long timeoutMs) {
        String clientId = TrustStoreManager.sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) return DECISION_REJECT;
        String clientName = TrustStoreManager.sanitizeClientName(rawClientName, clientId);
        String peerIp = (rawPeerIp != null && !rawPeerIp.trim().isEmpty()) ? rawPeerIp.trim() : "unknown";
        long waitMs = Math.max(200L, timeoutMs);

        Entry entry;
        PendingRequest activeSnapshot;
        synchronized (lock) {
            // Replace any stale pending request from the same clientId so retries stay crisp.
            List<Entry> replaced = new ArrayList<>();
            for (Entry existing : queue) {
                if (existing.request.clientId.equals(clientId)) {
                    replaced.add(existing);
                }
            }
            for (Entry old : replaced) {
                queue.remove(old);
                old.resolve(DECISION_CANCELLED);
            }
            if (queue.size() >= MAX_PENDING_REQUESTS) {
                return DECISION_BUSY;
            }
            long now = clock.nowMs();
            String reqId = generateRequestId();
            entry = new Entry(new PendingRequest(reqId, clientId, clientName, peerIp, now, now + waitMs));
            queue.addLast(entry);
            activeSnapshot = queue.peekFirst().request;
        }
        notifyListener(activeSnapshot);

        try {
            boolean completed = entry.latch.await(waitMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                entry.resolve(DECISION_TIMED_OUT);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            entry.resolve(DECISION_CANCELLED);
        } finally {
            PendingRequest nextActive = null;
            boolean removed;
            synchronized (lock) {
                removed = queue.remove(entry);
                Entry head = queue.peekFirst();
                nextActive = (head != null) ? head.request : null;
            }
            if (removed) {
                notifyListener(nextActive);
            }
        }
        return entry.decision;
    }

    /**
     * Resolves a pending request by {@code requestId} with {@link #DECISION_REJECT},
     * {@link #DECISION_APPROVE_ONCE}, or {@link #DECISION_APPROVE_PERMANENT}.
     */
    public boolean respond(String requestId, int decision) {
        if (requestId == null || requestId.trim().isEmpty()) return false;
        int normalizedDecision;
        if (decision == DECISION_APPROVE_ONCE) {
            normalizedDecision = DECISION_APPROVE_ONCE;
        } else if (decision == DECISION_APPROVE_PERMANENT) {
            normalizedDecision = DECISION_APPROVE_PERMANENT;
        } else {
            normalizedDecision = DECISION_REJECT;
        }

        Entry target = null;
        PendingRequest nextActive;
        synchronized (lock) {
            for (Entry e : queue) {
                if (e.request.requestId.equals(requestId.trim())) {
                    target = e;
                    break;
                }
            }
            if (target == null) return false;
            queue.remove(target);
            target.resolve(normalizedDecision);
            Entry head = queue.peekFirst();
            nextActive = (head != null) ? head.request : null;
        }
        notifyListener(nextActive);
        return true;
    }

    /** Cancels all pending requests (e.g. when Wi-Fi server stops or daemon shuts down). */
    public void cancelAll() {
        List<Entry> all;
        synchronized (lock) {
            if (queue.isEmpty()) return;
            all = new ArrayList<>(queue);
            queue.clear();
        }
        for (Entry e : all) {
            e.resolve(DECISION_CANCELLED);
        }
        notifyListener(null);
    }

    private void notifyListener(PendingRequest active) {
        ChangeListener l = this.listener;
        if (l != null) {
            try {
                l.onActiveRequestChanged(active);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private String generateRequestId() {
        byte[] buf = new byte[8];
        rng.nextBytes(buf);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : buf) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
