package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-09-25: task-wall enforcement moved OUT of ReferenceAgent (which used to check its own
 *  deadline only between turns, and separately had a fixed mid-stream idle timeout that fired on
 *  oMLX's own legitimate memory-pressure throttling) and into the harness, preemptively - even
 *  mid-request - via RunBenchSupport.runBounded, the one place every direct agent.run() call site
 *  (RunBench's task/handoff/PARALLEL_PLAN sessions, Reviews' reviewer sessions) now goes through. */
class RunBenchSupportTest {

    private final Set<Path> temps = new HashSet<>();

    private Path track(Path p) { temps.add(p); return p; }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path p : temps)
            if (p != null && Files.exists(p)) {
                try (var s = Files.walk(p)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignore) {} });
                }
            }
        temps.clear();
    }

    @Test
    void runBoundedReturnsTheRealResultWhenItFinishesInTime() throws Exception {
        final Path sessionDir = track(Files.createTempDirectory("sessions"));
        final Instant start = Instant.now();
        final Callable<ReferenceAgent.SessionResult> call = () ->
                new ReferenceAgent.SessionResult("T1", 0, 1.2, "stop", 3, 0, 0, null, start, Instant.now());

        final var result = RunBenchSupport.runBounded(10, "T1", sessionDir, "sid",
                reason -> fail("abort must not run on a normal finish (reason: " + reason + ")"), call);

        assertEquals(0, result.rc());
        assertEquals(3, result.turns());
    }

    @Test
    void runBoundedTimesOutInterruptsAndAbortsWithAReasonWhenTheCallOutlivesTheWallBudget() throws Exception {
        final Path sessionDir = track(Files.createTempDirectory("sessions"));
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final String[] abortReason = new String[1];
        final Callable<ReferenceAgent.SessionResult> call = () -> {
            started.countDown();
            try {
                Thread.sleep(10_000);   // far longer than the 1s wall budget below
            } catch (InterruptedException ie) {
                interrupted.set(true);
                throw ie;
            }
            return new ReferenceAgent.SessionResult("T1", 0, 10.0, "stop", 1, 0, 0, null, Instant.now(), Instant.now());
        };

        final long t0 = System.currentTimeMillis();
        final var result = RunBenchSupport.runBounded(1, "T1", sessionDir, "sid", reason -> abortReason[0] = reason, call);
        final long elapsedMs = System.currentTimeMillis() - t0;

        assertEquals(124, result.rc(), "a call that outlives the wall budget must be reported as rc=124");
        assertNotNull(abortReason[0], "abortProxy must run on timeout with a reason, so the proxy's own relay thread isn't left blocked (R12)");
        assertTrue(abortReason[0].contains("task-wall"), "the reason should name the actual cause: " + abortReason[0]);
        assertTrue(elapsedMs < 5_000, "runBounded must return close to the 1s wall budget, not wait for the full 10s call: " + elapsedMs + "ms");
        assertTrue(started.await(2, TimeUnit.SECONDS), "the call must have actually started");
        // cancellation is async relative to runBounded's own return - give the interrupted flag a moment to land
        for (int i = 0; i < 20 && !interrupted.get(); i++) Thread.sleep(50);
        assertTrue(interrupted.get(), "the call's own thread must be genuinely interrupted, not just abandoned");
    }

    @Test
    void runBoundedOnTimeoutPointsAtTheRealPartialSessionFileIfOneExists() throws Exception {
        final Path sessionDir = track(Files.createTempDirectory("sessions"));
        final Path partial = sessionDir.resolve("2026-09-25T10-00-00.000Z_the-real-session-id.jsonl");
        Files.writeString(partial, "{\"type\":\"session\"}\n");
        final Callable<ReferenceAgent.SessionResult> call = () -> { Thread.sleep(5_000); return null; };

        final var result = RunBenchSupport.runBounded(1, "T1", sessionDir, "the-real-session-id", reason -> {}, call);

        assertEquals(partial, result.sessionFile(), "a timed-out attempt's manifest entry must still point at its real, partial session file");
    }

    @Test
    void runBoundedPropagatesARealExceptionFromTheCall() throws Exception {
        final Path sessionDir = track(Files.createTempDirectory("sessions"));
        final Callable<ReferenceAgent.SessionResult> call = () -> { throw new IOException("a real failure, not a timeout"); };

        final Exception e = assertThrows(IOException.class,
                () -> RunBenchSupport.runBounded(5, "T1", sessionDir, "sid", reason -> {}, call));
        assertEquals("a real failure, not a timeout", e.getMessage());
    }
}
