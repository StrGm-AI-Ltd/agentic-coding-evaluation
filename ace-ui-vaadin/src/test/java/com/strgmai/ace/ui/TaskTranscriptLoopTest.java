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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The "Live" tab's own SSE loop lifecycle - a sibling of JobEventLoopTest, same shape, scoped
 *  to one task's own transcript stream instead of the whole job's events. */
class TaskTranscriptLoopTest {

    private static final SseEvent EVENT = new SseEvent(null, Json.MAPPER.readTree("{\"type\": \"line\", \"text\": \"USER:\\nhi\"}"));

    @Test
    void givesUpAfterThreeFailedConnectionsAndReportsIt() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        Mockito.doThrow(new IOException("connection reset"))
                .when(client).streamTaskTranscript(anyString(), anyString(), any());
        AtomicInteger lost = new AtomicInteger();
        List<SseEvent> received = new java.util.ArrayList<>();

        TaskTranscriptLoop loop = new TaskTranscriptLoop(client, "30", "T13", () -> false, received::add,
                lost::incrementAndGet, 5);
        loop.run();

        verify(client, times(TaskTranscriptLoop.MAX_ATTEMPTS)).streamTaskTranscript(anyString(), anyString(), any());
        assertEquals(0, received.size());
        assertEquals(1, lost.get(), "the give-up is reported exactly once");
        assertTrue(loop.isConnectionLost());
    }

    @Test
    void stopFlagAbortsOnTheFirstDeliveredEventWithoutRetry() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        List<SseEvent> received = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<SseEvent> consumer =
                    (java.util.function.Consumer<SseEvent>) invocation.getArgument(2);
            consumer.accept(EVENT);
            consumer.accept(EVENT); // the second accept hits the stop flag
            return null;
        }).when(client).streamTaskTranscript(anyString(), anyString(), any());
        AtomicInteger lost = new AtomicInteger();

        TaskTranscriptLoop loop = new TaskTranscriptLoop(client, "30", "T13", closed::get, event -> {
            received.add(event);
            closed.set(true); // the dialog closes after this event
        }, lost::incrementAndGet, 5);
        loop.run();

        assertEquals(1, received.size(), "events before the stop are delivered, then the stream aborts");
        assertEquals(0, lost.get(), "a dialog close is not a lost connection");
    }

    @Test
    void interruptionExitsQuietly() throws Exception {
        ServiceClient client = mock(ServiceClient.class);
        Mockito.doThrow(new InterruptedException("interrupted"))
                .when(client).streamTaskTranscript(anyString(), anyString(), any());
        AtomicReference<Runnable> lost = new AtomicReference<>();
        TaskTranscriptLoop loop = new TaskTranscriptLoop(client, "30", "T13", () -> false, event -> { },
                () -> lost.set(() -> { }), 5);
        loop.run();
        assertNull(lost.get(), "interruption is not a lost connection");
        assertTrue(Thread.interrupted(), "the interrupt flag is restored for the caller");
    }
}
