package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.oracle.RunOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The bug behind job #14 (and every other real task) failing with
 *  "java.nio.file.NoSuchFileException: .../task/PROMPT.md": the port read task/PROMPT.md as a
 *  filesystem path relative to the repo root, a file that was never vendored into this repo at all
 *  (nor would a single generic file have been correct - run_bench.py prefers each task's OWN prompt,
 *  tasks/&lt;task&gt;/PROMPT.md, first). Fixed by vendoring the prompts as classpath resources
 *  (matching how tasks/ladder.json already works) and preferring the task-specific one. */
class RunBenchTest {

    // the temp workspace dir(s) leak across runs - track and delete them recursively
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


    private static RunBench runBench() {
        return runBench(mock(BenchProperties.class));
    }

    private static RunBench runBench(BenchProperties props) {
        return new RunBench(props, mock(ReferenceAgent.class), mock(RecordingProxyFactory.class),
                mock(RunOracle.class), mock(Reviews.class), mock(ContextProbe.class));
    }

    @Test
    void promptResourcePrefersTheTaskSOwnPrompt() throws Exception {
        try (InputStream in = runBench().promptResource("L3p_point_in_time")) {
            assertNotNull(in, "tasks/L3p_point_in_time/PROMPT.md must be vendored as a resource");
            final var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("point-in-time") || text.contains("point in time") || text.length() > 100,
                    "a real, task-specific prompt, not empty: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    @Test
    void promptResourceFallsBackToTheGenericDefaultForAnUnknownTask() throws Exception {
        try (InputStream in = runBench().promptResource("no-such-task")) {
            assertNotNull(in, "tasks/PROMPT.md (the generic default) must be vendored as a resource");
        }
    }

    /** Job #26's failure, hidden behind #14's until that one was fixed: Files.copy's TARGET
     *  directory (ws/task) must exist too, not just its parent (ws) -
     *  Files.createDirectories(ws.resolve("task").getParent()) created only ws. */
    @Test
    void setUpTaskPromptCreatesTheTaskDirectoryItselfNotJustItsParent() throws Exception {
        Path ws = track(Files.createTempDirectory("ws"));   // a bare, empty workspace dir - nothing pre-created under it

        final String text = runBench().setUpTaskPrompt(ws, "L3p_point_in_time");

        assertTrue(Files.isRegularFile(ws.resolve("task/PROMPT.md")), "task/PROMPT.md must exist under the workspace");
        assertFalse(text.isBlank());
    }

    @Test
    void everyLadderTaskWithAKnownPromptResolvesToItsOwnFile() throws Exception {
        // every rung except L7_full_platform has its own PROMPT.md in the Python original (verified
        // against ~/Documents/repo/agentbench-trading-service/tasks/*/PROMPT.md) - each must resolve
        // to ITS OWN content here too, not silently share the generic default
        for (String task : new String[]{"L1_migration_entity", "L2_one_endpoint", "L3_point_in_time",
                "L3p_point_in_time", "L4_state_machine", "L5_second_service", "L6_compose_health"}) {
            try (InputStream perTask = runBench().promptResource(task);
                 InputStream generic = RunBenchTest.class.getResourceAsStream("/tasks/PROMPT.md")) {
                assertNotNull(perTask, task + " must resolve to a vendored prompt");
                assertNotNull(generic, "the generic tasks/PROMPT.md must be vendored");   // a missing resource is a clear message, not an NPE
                final var taskText = new String(perTask.readAllBytes(), StandardCharsets.UTF_8);
                final var genericText = new String(generic.readAllBytes(), StandardCharsets.UTF_8);
                assertNotEquals(genericText, taskText, task + " must not silently fall back to the generic prompt");
            }
        }
    }

    /** Found live 2026-09-25: oMLX's own memory-pressure throttling was slow enough to trip the
     *  agent's 180s stall detector, and oMLX's log - the only place that explained why - spans
     *  every run on the machine and keeps growing. Each run now copies its own slice out. */
    @Test
    void captureOmlxLogDoesNothingWhenNotConfigured() throws Exception {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn(null);
        final Path rd = track(Files.createTempDirectory("rd"));

        runBench(props).captureOmlxLog(rd, Map.of("started", "2026-09-25T07:00:00Z", "ended", "2026-09-25T08:00:00Z"));

        assertFalse(Files.exists(rd.resolve("omlx_server.log")), "no config = no capture, not a failed capture");
    }

    @Test
    void captureOmlxLogDoesNothingWhenTheConfiguredFileIsMissing() throws Exception {
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn("/no/such/omlx-log-for-this-test.log");
        final Path rd = track(Files.createTempDirectory("rd"));

        assertDoesNotThrow(() -> runBench(props).captureOmlxLog(rd,
                Map.of("started", "2026-09-25T07:00:00Z", "ended", "2026-09-25T08:00:00Z")));
        assertFalse(Files.exists(rd.resolve("omlx_server.log")));
    }

    @Test
    void captureOmlxLogKeepsOnlyLinesWithinTheRunsOwnWindowPlusTheirContinuations() throws Exception {
        final ZoneId zone = ZoneId.systemDefault();   // oMLX's own log has no zone offset - it's local time
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
        final Instant start = Instant.parse("2026-09-25T07:00:00Z"), end = Instant.parse("2026-09-25T08:00:00Z");
        final String before = fmt.format(LocalDateTime.ofInstant(start.minusSeconds(120), zone));
        final String firstInRange = fmt.format(LocalDateTime.ofInstant(start.plusSeconds(60), zone));
        final String lastInRange = fmt.format(LocalDateTime.ofInstant(end.minusSeconds(60), zone));
        final String after = fmt.format(LocalDateTime.ofInstant(end.plusSeconds(120), zone));

        final Path src = track(Files.createTempFile("omlx-server", ".log"));
        Files.write(src, List.of(
                before + " - omlx.server - INFO - before the run, must be dropped",
                firstInRange + " - omlx.server - INFO - first line inside the run",
                "    a continuation line with no timestamp, belongs to the entry above",
                lastInRange + " - omlx.server - INFO - last line inside the run",
                after + " - omlx.server - INFO - after the run, must be dropped"));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.omlxServerLog()).thenReturn(src.toString());
        final Path rd = track(Files.createTempDirectory("rd"));

        runBench(props).captureOmlxLog(rd, Map.of("started", start.toString(), "ended", end.toString()));

        final List<String> kept = Files.readAllLines(rd.resolve("omlx_server.log"));
        assertEquals(3, kept.size(), "the two in-range dated lines plus the continuation, nothing outside the window: " + kept);
        assertTrue(kept.get(0).contains("first line inside the run"));
        assertTrue(kept.get(1).contains("continuation line"));
        assertTrue(kept.get(2).contains("last line inside the run"));
    }

    /** #99: a cancelled job interrupts the thread blocked in joinAll() - that interrupt must reach
     *  every parallel-wave thread (not just get swallowed here while they keep running to completion
     *  unattended), and joinAll() must actually wait for them to stop before returning. */
    @Test
    void joinAllInterruptsAndWaitsForEveryWaveThreadWhenCancelled() throws Exception {
        final var interrupted = new java.util.concurrent.atomic.AtomicInteger();
        final var started = new java.util.concurrent.CountDownLatch(2);
        final List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Thread w = new Thread(() -> {
                started.countDown();
                try { Thread.sleep(60_000); }   // stands in for a still-running agent session
                catch (InterruptedException ie) { interrupted.incrementAndGet(); }
            });
            workers.add(w);
            w.start();
        }
        started.await();

        final Thread caller = new Thread(() -> {
            try { RunBench.joinAll(workers); }
            catch (InterruptedException expected) { /* correctly propagated to the caller */ }
        });
        caller.start();
        Thread.sleep(200);   // let joinAll() actually block in th.join() before interrupting it
        caller.interrupt();
        caller.join(5000);

        assertFalse(caller.isAlive(), "joinAll() must return once its own interrupt is handled");
        assertEquals(2, interrupted.get(), "every wave thread must receive its own interrupt, not be abandoned running");
        for (Thread w : workers) assertFalse(w.isAlive(), "joinAll() must wait for the wave threads to actually stop, not just signal and move on");
    }
}
