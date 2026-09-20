package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The SSE loop's lifecycle: retries, give-up, stop-flag abort, interruption. */
class JobEventLoopTest {

    private static final SseEvent EVENT = new SseEvent(null, Json.MAPPER.readTree("{\"type\": \"status\"}"));

    @Test
    void givesUpAfterThreeFailedConnectionsAndReportsIt() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        Mockito.doThrow(new IOException("connection reset"))
                .when(client).streamJobEvents(anyLong(), any());
        AtomicInteger lost = new AtomicInteger();
        List<SseEvent> received = new java.util.ArrayList<>();

        JobEventLoop loop = new JobEventLoop(client, 30, () -> false, received::add,
                lost::incrementAndGet, 5);
        loop.run();

        verify(client, times(JobEventLoop.MAX_ATTEMPTS)).streamJobEvents(Mockito.anyLong(), any());
        assertEquals(0, received.size());
        assertEquals(1, lost.get(), "the give-up is reported exactly once");
        assertTrue(loop.isConnectionLost());
    }

    @Test
    void stopFlagAbortsOnTheFirstDeliveredEventWithoutRetry() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        List<SseEvent> received = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean detached = new java.util.concurrent.atomic.AtomicBoolean(false);
        // deliver two events back-to-back; the view detaches between them
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<SseEvent> consumer =
                    (java.util.function.Consumer<SseEvent>) invocation.getArgument(1);
            consumer.accept(EVENT);
            consumer.accept(EVENT); // the second accept hits the stop flag
            return null;
        }).when(client).streamJobEvents(anyLong(), any());
        AtomicInteger lost = new AtomicInteger();

        JobEventLoop loop = new JobEventLoop(client, 30, detached::get, event -> {
            received.add(event);
            detached.set(true); // the view navigates away after this event
        }, lost::incrementAndGet, 5);
        loop.run();

        assertEquals(1, received.size(), "events before the stop are delivered, then the stream aborts");
        assertEquals(0, lost.get(), "a detach is not a lost connection");
    }

    @Test
    void interruptionExitsQuietly() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        Mockito.doThrow(new InterruptedException("interrupted"))
                .when(client).streamJobEvents(anyLong(), any());
        AtomicReference<Runnable> lost = new AtomicReference<>();
        JobEventLoop loop = new JobEventLoop(client, 30, () -> false, event -> { },
                () -> lost.set(() -> { }), 5);
        loop.run();
        assertNull(lost.get(), "interruption is not a lost connection");
        assertTrue(Thread.interrupted(), "the interrupt flag is restored for the caller");
    }
}
