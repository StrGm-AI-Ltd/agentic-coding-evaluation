package com.agentbench.service;

import com.agentbench.config.BenchProperties;
import com.agentbench.runner.RunBench;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for WorkerService.guard()'s three refusal paths (port of worker.py guard()): the
 *  treatment pin (#4/#5), the run lock, and a fatal preflight check. Dependencies are mocked so no
 *  Spring context or real Docker/model-server is needed. guard() hardcodes the lock path under
 *  user.home, so each lock-touching test points user.home at a private temp dir to stay hermetic
 *  and never contend with a real ~/.cache/agentbench/run.lock on this machine. */
class WorkerServiceTest {

    private static WorkerService worker(JobQueue queue, Preflight preflight, TreatmentPin pin) {
        when(queue.list()).thenReturn(List.of());   // reconcile() runs in the constructor
        BenchProperties props = mock(BenchProperties.class);
        return new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props);
    }

    private static JobQueue.Job job(String pinnedRunnerSha) {
        return new JobQueue.Job(1L, null, "orch", 1, "run", "run-1", List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, null, null, pinnedRunnerSha, pinnedRunnerSha);
    }

    private static String withFakeHome(String tmpHome, java.util.concurrent.Callable<String> body) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpHome);
        try { return body.call(); } finally { System.setProperty("user.home", realHome); }
    }

    @Test
    void treatmentPinMismatchIsBlockedBeforeTouchingTheRunLock() throws Exception {
        JobQueue queue = mock(JobQueue.class);
        TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-xyz999");
        WorkerService ws = worker(queue, mock(Preflight.class), pin);

        // pinnedRunnerSha differs from the running build's digest: the arms of an experiment must not
        // straddle a redeploy silently
        String result = withFakeHome("/nonexistent-should-never-be-touched", () -> ws.guard(job("build-abc123")));

        assertNotNull(result);
        assertTrue(result.startsWith("treatment: job was enqueued against build build-abc123"), result);
        assertTrue(result.contains("build-xyz999"), result);
    }

    @Test
    void runLockHeldYieldsANonNullRefusalThatMapsToWaitingLock() throws Exception {
        Path tmpHome = Files.createTempDirectory("fake-home");
        Path lockPath = tmpHome.resolve(".cache/agentbench/run.lock");
        Files.createDirectories(lockPath.getParent());
        FileChannel holder = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            assertNotNull(holder.tryLock(), "test setup: this test must hold the lock itself");

            JobQueue queue = mock(JobQueue.class);
            TreatmentPin pin = mock(TreatmentPin.class);
            when(pin.current()).thenReturn("build-abc123");
            WorkerService ws = worker(queue, mock(Preflight.class), pin);

            // a second FileChannel on the SAME file within THIS JVM throws OverlappingFileLockException
            // rather than tryLock() returning null (that null path is what a genuinely different
            // process/JVM gets); guard()'s catch-all still turns either into a non-null refusal, and
            // poll() maps any non-"preflight"/non-"treatment" refusal to waiting_lock (see WorkerService.poll())
            String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job(null)));

            assertNotNull(result);
            assertTrue(result.startsWith("run.lock"), result);
            assertFalse(result.startsWith("preflight") || result.startsWith("treatment"));   // -> waiting_lock, not blocked
        } finally {
            holder.close();
        }
    }

    @Test
    void fatalPreflightCheckIsBlocked() throws Exception {
        Path tmpHome = Files.createTempDirectory("fake-home");
        JobQueue queue = mock(JobQueue.class);
        TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        Preflight preflight = mock(Preflight.class);
        Preflight.Check fatal = new Preflight.Check("model server", false, "connection refused", true);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(fatal), true));
        WorkerService ws = worker(queue, preflight, pin);

        String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job(null)));

        assertEquals("preflight: connection refused", result);
    }
}
