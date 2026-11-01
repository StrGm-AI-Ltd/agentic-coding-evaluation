package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.runner.ContextProbe;
import com.strgmai.ace.service.runner.JobDockerSidecar;
import com.strgmai.ace.service.runner.RecordingProxyFactory;
import com.strgmai.ace.service.runner.RunBench;
import com.strgmai.ace.service.runner.RunRetention;
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

/** Unit tests for WorkerService.guard()'s refusal paths (port of worker.py guard()): the run lock
 *  and a fatal preflight check, plus the treatment-pin mismatch which (#247) now only logs a
 *  warning rather than refusing. Dependencies are mocked so no Spring context or real
 *  Docker/model-server is needed. guard() hardcodes the lock path under user.home, so each
 *  lock-touching test points user.home at a private temp dir to stay hermetic and never contend
 *  with a real ~/.cache/agentbench/run.lock on this machine. */
class WorkerServiceTest {

    private static WorkerService worker(JobQueue queue, Preflight preflight, TreatmentPin pin) {
        when(queue.list()).thenReturn(List.of());   // reconcile() runs in the constructor
        final BenchProperties props = mock(BenchProperties.class);
        return new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));
    }

    private static final UUID JOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static JobQueue.Job job(final String pinnedRunnerSha) {
        return new JobQueue.Job(JOB_1, null, "orch", 1, "run", "run-1", List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, pinnedRunnerSha, pinnedRunnerSha, java.util.Map.of());
    }

    private static String withFakeHome(String tmpHome, final java.util.concurrent.Callable<String> body) throws Exception {
        final String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpHome);
        try { return body.call(); } finally { System.setProperty("user.home", realHome); }
    }

    /** #576, found live: reconcile() used to check ProcessHandle.of(pid).isAlive() - meaningless in
     *  this in-process architecture, since "pid" is always the SERVICE's own pid (WorkerService.
     *  started() writes ProcessHandle.current().pid()), conventionally always 1 inside a container -
     *  trivially "alive" from the very process asking the question, on every restart. A row still
     *  "running" when reconcile() runs (once, at startup, before anything has been claimed) is BY
     *  DEFINITION orphaned; it must always go back to "queued" regardless of whatever pid happens to
     *  be stored, or claim() (which only ever re-picks "queued"/"waiting_lock" rows) can never pick
     *  it up again - confirmed live, this stranded a real mid-run job in "running" forever across a
     *  routine redeploy. */
    @Test
    void reconcileAlwaysRequeuesARowStillMarkedRunningAtStartupRegardlessOfItsStoredPid() {
        final JobQueue queue = mock(JobQueue.class);
        // pid=1 - the exact value every in-process job gets (ProcessHandle.current().pid() inside a
        // container, conventionally always 1) - the old, broken check would have found THIS pid
        // "alive" (it's literally the calling process itself) and wrongly left the row as "running".
        when(queue.list()).thenReturn(List.of(Map.of("id", JOB_1, "status", "running", "pid", 1)));
        final BenchProperties props = mock(BenchProperties.class);

        new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class), mock(ExperimentsService.class),
                mock(Preflight.class), mock(TreatmentPin.class), props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));

        verify(queue).setStatus(JOB_1, "queued", null);
    }

    @Test
    void reconcileLeavesNonRunningRowsAlone() {
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of(Map.of("id", JOB_1, "status", "queued", "pid", 1)));
        final BenchProperties props = mock(BenchProperties.class);

        new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class), mock(ExperimentsService.class),
                mock(Preflight.class), mock(TreatmentPin.class), props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));

        verify(queue, never()).setStatus(eq(JOB_1), anyString(), any());
    }

    /** issue #18: a job whose cancel_requested flag was already set before it was ever claimed (still
     *  "queued") is cancelled right here in poll() - the worker never runs it, so this is the only
     *  place that call can notice the experiment is now fully done. */
    @Test
    void pollFinalizesTheExperimentWhenAPreCancelledJobIsClaimed() throws Exception {
        final UUID experimentId = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
        final JobQueue.Job job = new JobQueue.Job(JOB_1, experimentId, "orch", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"), "queued", null, 0, null, null, false, false, null, null, null, null, java.util.Map.of());
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());
        when(queue.claim()).thenReturn(job);
        when(queue.get(JOB_1)).thenReturn(Map.of("cancel_requested", true));
        final ExperimentsService experiments = mock(ExperimentsService.class);
        final BenchProperties props = mock(BenchProperties.class);
        final WorkerService ws = new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                experiments, mock(Preflight.class), mock(TreatmentPin.class), props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));

        ws.poll();

        verify(queue).finish(JOB_1, "cancelled", null, null);
        verify(experiments).finalizeIfDone(experimentId);
    }

    /** #247: a treatment-pin mismatch (the build that enqueued the job differs from the build about
     *  to run it) used to block the job outright. It's now just a logged warning - an experiment
     *  straddling a redeploy still gets to run, instead of stalling until an operator requeues it. */
    @Test
    void treatmentPinMismatchWarnsButDoesNotBlock() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final JobQueue queue = mock(JobQueue.class);
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-xyz999");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final WorkerService ws = worker(queue, preflight, pin);

        final String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job("build-abc123")));

        assertNull(result, "a treatment-pin mismatch must not refuse the job");
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
            // poll() maps any non-"preflight" refusal to waiting_lock (see WorkerService.poll())
            final String result = withFakeHome(tmpHome.toString(), () -> ws.guard(job(null)));

            assertNotNull(result);
            assertTrue(result.startsWith("run.lock"), result);
            assertFalse(result.startsWith("preflight"));   // -> waiting_lock, not blocked
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
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        when(queue.get(JOB_1)).thenReturn(Map.of("cancel_requested", false));

        WorkerService ws = new WorkerService(queue, mock(RunBench.class), mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));
        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });

        verify(queue, timeout(3000)).finish(eq(JOB_1), eq("succeeded"), eq(0), anyString());
        verify(queue, never()).finish(eq(JOB_1), eq("failed"), anyInt(), anyString());
    }

    /** Found live 2026-10-19 (job 25b8828c, T21): a plain `catch (Exception e)` here let an Error
     *  (OutOfMemoryError was the live suspect - interactions.jsonl had grown to 454MB by then)
     *  escape uncaught, past every catch clause - the job's own status never left "running",
     *  nothing was ever logged, and nothing was left running it either, orphaning it forever with
     *  no trace. `catch (Throwable e)` closes that gap: an Error must land on "failed" exactly
     *  like an Exception already does, not vanish silently. */
    @Test
    void anErrorNotJustAnExceptionStillLandsTheJobOnFailed() throws Exception {
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
        final RunBench runBench = mock(RunBench.class);
        when(runBench.runOnce(any(), any(), any(), any(), any())).thenThrow(new OutOfMemoryError("test"));

        final JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        when(queue.get(JOB_1)).thenReturn(Map.of("cancel_requested", false, "pause_requested", false));

        final WorkerService ws = new WorkerService(queue, runBench, mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));
        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });

        verify(queue, timeout(3000)).finish(eq(JOB_1), eq("failed"), eq(1), anyString());
        assertFalse(ws.busyNow(), "busy must clear so poll() can pick up the next job, even after an Error");
    }

    /** Live bug found 2026-09-27: the cancel-watch thread called probe.abortInflight() exactly once
     *  (its old loop condition, `!cancelCurrent`, went false right after that first reaction) - but
     *  ContextProbe.askRetry() opens a BRAND NEW request right after an aborted one fails, and
     *  probe()'s own loop across context sizes does too. Neither of those later requests was ever
     *  aborted, so a cancelled job could sit "running" indefinitely past cancellation. This drives a
     *  real cancel-watch thread against a runOnce() that "runs" for several seconds and asserts the
     *  watch keeps re-aborting every poll cycle, not just the first time. */
    @Test
    void cancelWatchKeepsAbortingTheProbeUntilTheJobActuallyStops() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final var resultsDir = Files.createTempDirectory("results");
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws").toString());
        when(props.model()).thenReturn("m");
        final RunBench runBench = mock(RunBench.class);
        final ContextProbe probe = mock(ContextProbe.class);
        // stands in for a probe stuck retrying after each abort: deliberately UNINTERRUPTIBLE, the
        // same way a blocking HttpResponseInputStream.read() ignores Thread.interrupt() (R14) - a
        // sleep()-based stand-in would exit the instant currentJobFuture.cancel(true) interrupts it,
        // masking exactly the bug this test exists to catch. Long enough to observe more than one
        // 2-second cancel-watch poll cycle once cancellation is requested mid-run.
        when(runBench.runOnce(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            final long deadline = System.currentTimeMillis() + 7000;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { /* uninterruptible, like the real stuck read */ }
            }
            return Map.of();
        });

        final JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        final var cancelRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(queue.get(JOB_1)).thenAnswer(inv -> Map.of("cancel_requested", cancelRequested.get()));

        final WorkerService ws = new WorkerService(queue, runBench, mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, probe, mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));

        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });
        Thread.sleep(500);           // let the job actually start running first
        cancelRequested.set(true);   // simulate the user clicking cancel mid-run

        verify(probe, timeout(6000).atLeast(2)).abortInflight();
    }

    /** Pause: the same interrupt mechanism as cancel (abortInflight + Future.cancel), but the
     *  finally block must land on 'paused' via queue.paused(), never move the results dir aside,
     *  and never call finish() at all - a paused job is not a terminal outcome. */
    @Test
    void pauseAbortsTheInFlightCallAndLandsOnPausedWithoutTouchingResults() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("marker.txt"), "still here");
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws").toString());
        when(props.model()).thenReturn("m");
        final RunBench runBench = mock(RunBench.class);
        final ContextProbe probe = mock(ContextProbe.class);
        final RecordingProxyFactory proxies = mock(RecordingProxyFactory.class);
        // interruptible, unlike the R14 stand-in in the cancel-watch test above: a real aborted HTTP
        // call unblocks promptly (proven live, repeatedly, elsewhere this session), so this models
        // THAT common case - Future.cancel(true) delivers the interrupt straight into this sleep.
        when(runBench.runOnce(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(7000);
            return Map.of();
        });

        final JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        final var pauseRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(queue.get(JOB_1)).thenAnswer(inv -> Map.of("cancel_requested", false, "pause_requested", pauseRequested.get()));

        final WorkerService ws = new WorkerService(queue, runBench, mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, probe, proxies, mock(RunRetention.class), mock(JobDockerSidecar.class));

        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });
        Thread.sleep(500);
        pauseRequested.set(true);   // simulate the user clicking Pause mid-run

        verify(probe, timeout(6000).atLeast(1)).abortInflight();
        // found live on T17: Future.cancel(true) alone only interrupts the WAITING thread blocked
        // in runBounded()'s future.get() - the WORKING thread actually streaming the model's
        // response can still be stuck in a non-interruptible read. abortAllInflight() must be
        // called too, the same proactive way probe.abortInflight() already is.
        verify(proxies, timeout(6000).atLeast(1)).abortAllInflight("paused");
        verify(queue, timeout(6000)).paused(JOB_1);
        // #289: the interrupted runOnce() throws, but the catch block now recognises pauseCurrent
        // and stays out of finish()'s way entirely (it used to call finish(..., "failed", ...)
        // unconditionally here, which both mislabelled a deliberate pause as a crash in the logs
        // and - after #273's own finish() guard - could have won the terminal-status race against
        // paused() winning correctly) - what must never happen is landing on "cancelled" OR
        // "failed" instead of paused() actually winning as the job's final state.
        verify(queue, never()).finish(eq(JOB_1), eq("cancelled"), any(), any());
        verify(queue, never()).finish(eq(JOB_1), eq("failed"), any(), any());
        assertTrue(Files.isRegularFile(runDir.resolve("marker.txt")), "a paused run's results must never move aside");
        assertFalse(Files.isDirectory(resultsDir.resolve("_aborted")), "pause must never create an _aborted/ move");
    }

    /** #289/#273: a genuinely cancelled (not merely paused) job must still land on "cancelled",
     *  never "failed" - the catch block used to call finish(..., "failed", ...) unconditionally on
     *  the way through, which (after JobQueue.finish() started refusing to overwrite an already-
     *  terminal status, #273) won the race against the finally block's own correct "cancelled"
     *  write, leaving a genuinely cancelled job stuck on "failed" instead. */
    @Test
    void cancelAbortsTheInFlightCallAndLandsOnCancelledNeverFailed() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws").toString());
        when(props.model()).thenReturn("m");
        final RunBench runBench = mock(RunBench.class);
        final ContextProbe probe = mock(ContextProbe.class);
        final RecordingProxyFactory proxies = mock(RecordingProxyFactory.class);
        when(runBench.runOnce(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(7000);   // interruptible, same reasoning as the pause test above
            return Map.of();
        });

        final JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        final var cancelRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(queue.get(JOB_1)).thenAnswer(inv -> Map.of("cancel_requested", cancelRequested.get(), "pause_requested", false, "status", "running"));

        final WorkerService ws = new WorkerService(queue, runBench, mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, probe, proxies, mock(RunRetention.class), mock(JobDockerSidecar.class));

        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });
        Thread.sleep(500);
        cancelRequested.set(true);   // simulate the user clicking Cancel mid-run

        verify(probe, timeout(6000).atLeast(1)).abortInflight();
        verify(proxies, timeout(6000).atLeast(1)).abortAllInflight("cancelled");
        verify(queue, timeout(6000)).finish(eq(JOB_1), eq("cancelled"), any(), any());
        verify(queue, never()).finish(eq(JOB_1), eq("failed"), any(), any());
    }

    /** #593: StuckWorkerWatchdog has no way to know what the single worker thread is currently
     *  supposed to be writing to other than these two fields - this pins that WorkerService
     *  actually populates them while a job is running and clears them once it's done, the same way
     *  busyNow() already does for the plain busy flag. */
    @Test
    void currentRunDirAndStartedAtAreSetWhileRunningAndClearedAfter() throws Exception {
        final var tmpHome = Files.createTempDirectory("fake-home");
        final var resultsDir = Files.createTempDirectory("results");
        final JobQueue queue = mock(JobQueue.class);
        when(queue.list()).thenReturn(List.of());
        final TreatmentPin pin = mock(TreatmentPin.class);
        when(pin.current()).thenReturn("build-abc123");
        final Preflight preflight = mock(Preflight.class);
        when(preflight.check(any())).thenReturn(new Preflight.Report(List.of(), false));
        final BenchProperties props = mock(BenchProperties.class);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws").toString());
        when(props.model()).thenReturn("m");
        final RunBench runBench = mock(RunBench.class);
        when(runBench.runOnce(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(1500);
            return Map.of();
        });

        final JobQueue.Job job = new JobQueue.Job(JOB_1, null, "A", 1, "run", "run-1",
                List.of("--task=L3p_point_in_time", "--model=m"),
                "queued", null, 0, null, null, false, false, null, null, "build-abc123", "build-abc123", java.util.Map.of());
        when(queue.claim()).thenReturn(job);
        when(queue.get(JOB_1)).thenReturn(Map.of("cancel_requested", false, "pause_requested", false));

        final WorkerService ws = new WorkerService(queue, runBench, mock(ImporterService.class),
                mock(ExperimentsService.class), preflight, pin, props, mock(ContextProbe.class), mock(RecordingProxyFactory.class), mock(RunRetention.class), mock(JobDockerSidecar.class));
        withFakeHome(tmpHome.toString(), () -> { ws.poll(); return "done"; });

        Thread.sleep(500);   // let the job actually start running first
        assertEquals(resultsDir.resolve("run-1"), ws.currentRunDir(), "must point at the running job's own results dir");
        assertNotNull(ws.currentJobStartedAt());

        verify(queue, timeout(3000)).finish(eq(JOB_1), eq("succeeded"), eq(0), anyString());
        assertNull(ws.currentRunDir(), "must clear once the job is no longer running, same as busyNow()");
        assertNull(ws.currentJobStartedAt());
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
