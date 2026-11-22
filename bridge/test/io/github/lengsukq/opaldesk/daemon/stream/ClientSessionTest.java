package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.sink.DisplaySink;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientSessionTest {

    @Test
    public void testOverflowClosesTransportOutsideQueueLock() throws Exception {
        AtomicInteger closeCount = new AtomicInteger(0);
        AtomicBoolean concurrentEnqueueBlockedOnClose = new AtomicBoolean(false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ClientSession[] sessionRef = new ClientSession[1];

        Closeable transport = () -> {
            closeCount.incrementAndGet();
            // Verify another thread calling enqueue() while transport.close() is running
            // does NOT deadlock waiting on queueLock (closed flag is already set outside lock).
            CountDownLatch done = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                sessionRef[0].enqueue(OpalCastProtocol.TYPE_PONG, 0, 0L, new byte[8]);
                done.countDown();
            });
            probe.start();
            try {
                if (!done.await(500L, TimeUnit.MILLISECONDS)) {
                    concurrentEnqueueBlockedOnClose.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ClientSession session = new ClientSession(
                1, DisplaySink.SINK_REMOTE_USB, true, OpalCastProtocol.SESSION_ROLE_DESKTOP,
                "", "cid", "Mac", transport, out, null);
        sessionRef[0] = session;

        // Fill queue with MAX_TOTAL_QUEUED_FRAMES non-video frames (sender thread intentionally not started)
        for (int i = 0; i < ClientSession.MAX_TOTAL_QUEUED_FRAMES; i++) {
            session.enqueue(OpalCastProtocol.TYPE_PONG, 0, i, new byte[8]);
            assertFalse(session.isClosed());
        }

        // Next non-video frame overflows and must close outside queueLock
        session.enqueue(OpalCastProtocol.TYPE_PONG, 0, 999L, new byte[8]);
        assertTrue(session.isClosed());
        assertEquals(1, closeCount.get());
        assertFalse("close() must never run while holding queueLock", concurrentEnqueueBlockedOnClose.get());

        // Subsequent close calls are idempotent
        session.close("duplicate");
        assertEquals(1, closeCount.get());
    }
}
