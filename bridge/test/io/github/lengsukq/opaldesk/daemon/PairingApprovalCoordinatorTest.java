package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.security.PairingApprovalCoordinator;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PairingApprovalCoordinatorTest {

    @Test
    public void testApproveOncePermanentAndRejectFlow() throws Exception {
        PairingApprovalCoordinator coord = new PairingApprovalCoordinator(() -> 10_000L, new SecureRandom());
        AtomicReference<PairingApprovalCoordinator.PendingRequest> active = new AtomicReference<>();
        CountDownLatch notified = new CountDownLatch(1);
        coord.setChangeListener(req -> {
            active.set(req);
            if (req != null) notified.countDown();
        });

        AtomicInteger decisionOut = new AtomicInteger(-99);
        Thread client = new Thread(() -> decisionOut.set(
                coord.requestAndAwait("tab-1", "Xiaomi Pad", "192.168.31.105", 5_000L)));
        client.start();

        assertTrue(notified.await(2, TimeUnit.SECONDS));
        PairingApprovalCoordinator.PendingRequest pending = active.get();
        assertNotNull(pending);
        assertEquals("tab-1", pending.clientId);
        assertEquals("Xiaomi Pad", pending.clientName);
        assertEquals("192.168.31.105", pending.peerIp);
        assertTrue(pending.toJson().contains("\"requestId\":\"" + pending.requestId + "\""));

        assertTrue(coord.respond(pending.requestId, PairingApprovalCoordinator.DECISION_APPROVE_PERMANENT));
        client.join(2_000L);
        assertEquals(PairingApprovalCoordinator.DECISION_APPROVE_PERMANENT, decisionOut.get());
        assertNull(coord.getActivePendingRequest());
        assertEquals("", coord.getActivePendingRequestJson());
        assertFalse("Responding twice to same requestId must fail",
                coord.respond(pending.requestId, PairingApprovalCoordinator.DECISION_REJECT));
    }

    @Test
    public void testTimeoutAndCancelAll() throws Exception {
        PairingApprovalCoordinator coord = new PairingApprovalCoordinator();
        int timedOut = coord.requestAndAwait("tab-slow", "Slow Pad", "192.168.31.88", 220L);
        assertEquals(PairingApprovalCoordinator.DECISION_TIMED_OUT, timedOut);
        assertNull(coord.getActivePendingRequest());

        CountDownLatch ready = new CountDownLatch(1);
        coord.setChangeListener(req -> {
            if (req != null) ready.countDown();
        });
        AtomicInteger cancelledOut = new AtomicInteger(-99);
        Thread waiting = new Thread(() -> cancelledOut.set(
                coord.requestAndAwait("tab-cancel", "Pad C", "192.168.31.89", 5_000L)));
        waiting.start();

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        coord.cancelAll();
        waiting.join(2_000L);
        assertEquals(PairingApprovalCoordinator.DECISION_CANCELLED, cancelledOut.get());
        assertNull(coord.getActivePendingRequest());
    }
}
