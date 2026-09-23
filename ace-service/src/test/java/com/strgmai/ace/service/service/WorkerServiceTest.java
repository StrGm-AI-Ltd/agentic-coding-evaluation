package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.runner.ContextProbe;
import com.strgmai.ace.service.runner.RunBench;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests for WorkerService.guard()'s three refusal paths (port of worker.py guard()): the
 *  treatment pin (#4/#5), the run lock, and a fatal preflight check. Dependencies are mocked so no
 *  Spring context or real Docker/model-server is needed. guard() hardcodes the lock path under
 *  user.home, so each lock-touching test points user.home at a private temp dir to stay hermetic
 *  and never contend with a real ~/.cache/agentbench/run.lock on this machine. */
class WorkerServiceTest {

    private static WorkerService worker(JobQueue queue, Preflight preflight, TreatmentPin pin) {
        when(queue.list()).thenReturn(List.of());   // reconcile() runs in the constructor
        final BenchProperties props = mock(BenchProperties.class);
        return new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class));
    }

    private static final UUID JOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static JobQueue.Job job(final String pinnedRunnerSha) {
        return new JobQueue.Job(JOB_1, null, "orch", 1, "run", "run-1", List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, null, null, pinnedRunnerSha, pinnedRunnerSha);
    }

    private static String withFakeHome(String tmpHome, final java.util.concurrent.Callable<String> body) throws Exception {
        final String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpHome);
        try { return body.call(); } finally { System.setProperty("user.home", realHome); }
    }

    @Test
    void treatmentPinMismatchIsBlockedBeforeTouchingTheRunLock() throws Exception {
        final JobQueue queue = mock(JobQueue.class);
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-xyz999");
        final WorkerService ws = worker(queue, mock(Preflight.class), pin);

        // pinnedRunnerSha differs from the running build's digest: the arms of an experiment must not
        // straddle a redeploy silently
        final String result = withFakeHome("/nonexistent-should-never-be-touched", () -> ws.guard(job("build-abc123")));

        assertNotNull(result);
        assertTrue(result.startsWith("treatment: job was enqueued against build build-abc123"), result);
        assertTrue(result.contains("build-xyz999"), result);
    }

    @Test
    void runLockHeldYieldsANonNullRefusalThatMapsToWaitingLock() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final Path lockPath = tmpHome.resolve(".cache/agentbench/run.lock");
        Files.createDirectories(lockPath.getParent());
        final FileChannel holder = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            assertNotNull(holder.tryLock(), "test setup: this test must hold the lock itself");

            final JobQueue queue = mock(JobQueue.class);
            final TreatmentPin pin = mock(TreatmentPin.class);
            when(pin.current()).thenReturn("build-abc123");
            final WorkerService ws = worker(queue, mock(Preflight.class), pin);

            // a second FileChannel on the SAME file within THIS JVM throws OverlappingFileLockException
            // rather than tryLock() returning null (that null path is what a genuinely different
            // process/JVM gets); guard()'s catch-all still turns either into a non-null refusal, and
            // poll() maps any non-"preflight"/non-"treatment" refusal to waiting_lock (see WorkerService.poll())
            final String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job(null)));

            assertNotNull(result);
            assertTrue(result.startsWith("run.lock"), result);
            assertFalse(result.startsWith("preflight") || result.startsWith("treatment"));   // -> waiting_lock, not blocked
        } finally {
            holder.close();
        }
    }

    /** The real bug behind job #1/#8 failing with a bare "java.lang.NullPointerException": the cfg's
     *  "review"/"trajectory_review" entries were built with Map.of(...), which throws on ANY null
     *  value - and "model" IS null whenever --self-review/--trajectory-review is set without an
     *  explicit --reviewer-model/--trajectory-reviewer-model (exactly what model_ab produces when no
     *  reviewer model is picked in the form). Drives the real poll() end-to-end with a job shaped
     *  exactly like the ones that crashed, and asserts it finishes "succeeded", not "failed". */
    @Test
    void reviewFlagsWithoutAnExplicitReviewerModelDoNotCrashTheWorker() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final var resultsDir = Files.createTempDirectory("results");
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());   // reconcile() runs in the constructor
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws").toString());
        when(props.model()).thenReturn("m");

        JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m", "--mode=orchestrated", "--self-review", "--trajectory-review"),
                "queued", null, 0, null, null, false, null, null, "build-abc123", "build-abc123");
        when(queue.claim()).thenReturn(job);
        when(queue.get(JOB_1)).thenReturn(Map.of("cancel_requested", false));

        WorkerService ws = new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class));
        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });

        verify(queue, timeout(3000)).finish(eq(JOB_1), eq("succeeded"), eq(0), anyString());
        verify(queue, never()).finish(eq(JOB_1), eq("failed"), anyInt(), anyString());
    }

    @Test
    void fatalPreflightCheckIsBlocked() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final JobQueue queue = mock(JobQueue.class);
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        final var fatal = new Preflight.Check("model server", false, "connection refused", true);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(fatal), true));
        final WorkerService ws = worker(queue, preflight, pin);

        final String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job(null)));

        assertEquals("preflight: connection refused", result);
    }
}
