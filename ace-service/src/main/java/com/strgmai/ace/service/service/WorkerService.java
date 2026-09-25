package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.runner.ContextProbe;
import com.strgmai.ace.service.runner.RunBench;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Port of service/worker.py: runs queued benchmark jobs ONE AT A TIME (two would share the model
 *  server and fight over Docker), reconciles orphaned 'running' rows at startup, and imports the
 *  finished results. The Python original spawns run_bench.py as a subprocess; the Java port runs
 *  RunBench in-process on a single-threaded executor — the guard (one run at a time), the cancel
 *  flag and the post-run import carry over unchanged. */
@Service
public class WorkerService {
    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
    private final JobQueue queue;
    private final RunBench runBench;
    private final ImporterService importer;
    private final BenchProperties props;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        final var t = new Thread(r, "bench-runner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();

    private final ExperimentsService experiments;
    private final Preflight preflight;
    private final TreatmentPin pin;
    private final ContextProbe probe;

    public WorkerService(JobQueue queue, RunBench runBench, ImporterService importer, ExperimentsService experiments,
                         Preflight preflight, TreatmentPin pin, BenchProperties props, ContextProbe probe) {
        this.queue = queue; this.runBench = runBench; this.importer = importer; this.experiments = experiments;
        this.preflight = preflight; this.pin = pin; this.props = props; this.probe = probe;
        reconcile();
    }

    /** port of worker.finish()'s RESULT line: scored+exit-0 succeeds, oracle.json is the verdict */
    static String resultLine(Path runDir) {
        final boolean scored = Files.isRegularFile(runDir.resolve("oracle.json"));
        return "RESULT " + runDir.getFileName() + (scored ? " scored" : " unscored");
    }

    /** startup reconciliation: a 'running' row with no live process goes back to queued */
    void reconcile() {
        for (Map<String, Object> job : queue.list()) {
            if (!"running".equals(job.get("status"))) continue;
            final Integer pid = (Integer) job.get("pid");
            final boolean alive = pid != null && ProcessHandle.of(pid.longValue()).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) queue.setStatus((UUID) job.get("id"), "queued", null);
            else log.info("job {}: re-attaching to pid {}", job.get("id"), pid);
        }
    }

    private java.nio.channels.FileChannel runLock;
    private volatile boolean cancelCurrent;
    private volatile Future<?> currentJobFuture;

    /** port of worker.py guard(): the treatment pin (blocked), the run lock (waiting_lock), and
     *  preflight for the model THIS job will request - all before the budget is spent */
    String guard(JobQueue.Job job) {
        // the treatment pin: the build that enqueued this job must be the build about to run it, or
        // the arms of an experiment straddle a redeploy and stop being comparable
        if (job.pinnedRunnerSha() != null && !job.pinnedRunnerSha().equals(pin.current()))
            return "treatment: job was enqueued against build " + job.pinnedRunnerSha() + ", this build is "
                    + pin.current() + "; the service was rebuilt while the job was queued and its results would not be "
                    + "comparable with the arms already run. Redeploy that build, or requeue the job from this one";
        try {
            if (runLock == null) {
                // the SAME lock file the Python service uses: "one benchmark run at a time" is an invariant
                // over the shared model server at :9191, so both services must contend for one lock
                final java.nio.file.Path lockPath = java.nio.file.Path.of(System.getProperty("user.home"), ".cache/agentbench/run.lock");
                java.nio.file.Files.createDirectories(lockPath.getParent());
                runLock = java.nio.channels.FileChannel.open(lockPath,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                if (runLock.tryLock() == null) { runLock.close(); runLock = null; return "run.lock is held by another benchmark run"; }
            }
        } catch (Exception e) { return "run.lock: " + e; }
        final Preflight.Report pf = preflight.check(flag(job.argv(), "--model"));
        if (pf.blocked())
            return "preflight: " + pf.checks().stream().filter(c -> c.fatal() && !c.ok()).findFirst().map(Preflight.Check::detail).orElse("");
        return null;
    }

    @Scheduled(fixedDelayString = "${ace.poll-sec:10}000", initialDelay = 5000)
    public void poll() {
        if (busy.get()) return;
        final JobQueue.Job job = queue.claim();
        if (job == null) return;
        if (Boolean.TRUE.equals(queue.get(job.id()).get("cancel_requested"))) {
            queue.finish(job.id(), "cancelled", null, null);
            if (job.experimentId() != null) experiments.finalizeIfDone(job.experimentId());
            return;
        }
        final String refusal = guard(job);
        if (refusal != null) {   // waiting_lock/blocked: the job stays, it is retried when the cause clears
            // blocked = a cause the job cannot outwait (claim() only re-picks queued/waiting_lock rows):
            // a fatal preflight and a treatment mismatch both need an operator. The lock clears on its own.
            queue.setStatus(job.id(), refusal.startsWith("preflight") || refusal.startsWith("treatment") ? "blocked" : "waiting_lock", refusal);
            return;
        }
        busy.set(true);
        cancelCurrent = false;
        Thread cancelWatch = new Thread(() -> {   // worker.py supervise(): poll cancel_requested, kill the run
            while (busy.get() && !cancelCurrent) {
                if (Boolean.TRUE.equals(queue.get(job.id()).get("cancel_requested"))) {
                    cancelCurrent = true;
                    // interrupt the worker thread: a blocking model-server call or Thread.sleep must
                    // unwind NOW, not wait out the rest of the run's budget for the flag to be noticed.
                    // interrupt() alone does not reach a blocking HttpResponseInputStream.read() (R14,
                    // same class of bug as RecordingProxy pre-R12) - probe.abortInflight() closes it directly
                    if (currentJobFuture != null) currentJobFuture.cancel(true);
                    probe.abortInflight();
                    return;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "cancel-watch-" + job.id());
        cancelWatch.setDaemon(true);
        cancelWatch.start();
        currentJobFuture = runner.submit(() -> {
            try {
                queue.started(job.id(), (int) ProcessHandle.current().pid(), null);
                log.info("job {}: started {} ({})", job.id(), job.runId(), job.argv());
                final Map<String, Object> cfg = new LinkedHashMap<>();
                cfg.put("results_root", props.resultsDir());
                cfg.put("workspace_root", props.workspaceRoot());
                cfg.put("model", job.argv().stream().filter(a -> a.startsWith("--model=")).map(a -> a.substring(8)).findFirst().orElse(props.model()));
                cfg.put("system_base_url", null);
                final String mode = flag(job.argv(), "--mode") != null ? flag(job.argv(), "--mode") : "monolithic";
                final String planSource = flag(job.argv(), "--plan-source") != null ? flag(job.argv(), "--plan-source") : "agent";
                if (flag(job.argv(), "--task-wall") != null) cfg.put("task_wall_sec", Integer.parseInt(flag(job.argv(), "--task-wall")));
                if (flag(job.argv(), "--task-tokens") != null) cfg.put("task_tokens", Long.parseLong(flag(job.argv(), "--task-tokens")));
                if (flag(job.argv(), "--first-token-timeout") != null) cfg.put("first_token_timeout_sec", Integer.parseInt(flag(job.argv(), "--first-token-timeout")));
                if (flag(job.argv(), "--compaction-trigger") != null) cfg.put("compaction_trigger", Integer.parseInt(flag(job.argv(), "--compaction-trigger")));
                // sampler knobs (#72) - RunBench folds these into a SamplerOverrides pinned onto
                // every request by RecordingProxy; unset means "use the operator-wide default"
                if (flag(job.argv(), "--temperature") != null) cfg.put("temperature", Double.parseDouble(flag(job.argv(), "--temperature")));
                if (flag(job.argv(), "--top-p") != null) cfg.put("top_p", Double.parseDouble(flag(job.argv(), "--top-p")));
                if (flag(job.argv(), "--top-k") != null) cfg.put("top_k", Integer.parseInt(flag(job.argv(), "--top-k")));
                if (flag(job.argv(), "--repetition-penalty") != null) cfg.put("repetition_penalty", Double.parseDouble(flag(job.argv(), "--repetition-penalty")));
                if (flag(job.argv(), "--max-tokens") != null) cfg.put("max_tokens_override", Integer.parseInt(flag(job.argv(), "--max-tokens")));
                if (flag(job.argv(), "--reasoning-effort") != null) cfg.put("reasoning_effort", flag(job.argv(), "--reasoning-effort"));
                // PARALLEL_PLAN/handoff/wrap-up walls (found live 2026-09-25): were fixed literals
                // in RunBench.java (600/300/300*scale) with no run-level control at all
                if (flag(job.argv(), "--parallel-plan-wall") != null) cfg.put("parallel_plan_wall_sec", Integer.parseInt(flag(job.argv(), "--parallel-plan-wall")));
                if (flag(job.argv(), "--handoff-wall") != null) cfg.put("handoff_wall_sec", Integer.parseInt(flag(job.argv(), "--handoff-wall")));
                if (flag(job.argv(), "--wrapup-wall") != null) cfg.put("wrapup_wall_sec", Integer.parseInt(flag(job.argv(), "--wrapup-wall")));
                // a pinned --context-window IS the window: it skips step 0, whose whole job is to measure one
                final String window = flag(job.argv(), "--context-window");
                if (window != null) cfg.put("context_window", Integer.parseInt(window));
                // the context probe (step 0) is the default for direct runs; queue runs opt in via ACE_JLS_CONTEXT_PROBE;
                // --no-context-probe is a per-run override that skips it even when nothing is pinned
                cfg.put("context_probe", !job.argv().contains("--no-context-probe") && window == null
                        && Boolean.parseBoolean(System.getenv().getOrDefault("ACE_JLS_CONTEXT_PROBE", "true")));   // Python default: the probe runs
                cfg.put("context_probe_fresh", job.argv().contains("--context-probe-fresh"));
                if (flag(job.argv(), "--parallel") != null) {
                    if ("auto".equals(flag(job.argv(), "--parallel"))) cfg.put("parallel_auto", true);
                    else cfg.put("parallel", Integer.parseInt(flag(job.argv(), "--parallel")));
                }
                cfg.put("manage_docker", job.argv().contains("--manage-docker"));
                // Map.of() rejects a null value outright - "model" IS null whenever review is enabled
                // without an explicit --reviewer-model (self-review alone still needs a reviewer picked
                // downstream, but that is RunBench's decision to make, not a reason to crash the worker)
                final Map<String, Object> review = new LinkedHashMap<>();
                review.put("enabled", job.argv().contains("--self-review"));
                review.put("model", flag(job.argv(), "--reviewer-model"));
                review.put("blind", job.argv().contains("--review-blind"));
                // shared by both self-review and trajectory-review (Reviews.runReviewerSession reads
                // this same "review" map's wall_sec for either kind of reviewer session)
                if (flag(job.argv(), "--review-wall-sec") != null) review.put("wall_sec", Integer.parseInt(flag(job.argv(), "--review-wall-sec")));
                // unset -> Collect's own 0.1 default; only set when the run actually pinned one
                if (flag(job.argv(), "--review-weight") != null) review.put("weight", Double.parseDouble(flag(job.argv(), "--review-weight")));
                cfg.put("review", review);
                final Map<String, Object> trajectoryReview = new LinkedHashMap<>();
                trajectoryReview.put("enabled", job.argv().contains("--trajectory-review"));
                trajectoryReview.put("model", flag(job.argv(), "--trajectory-reviewer-model"));
                if (flag(job.argv(), "--trajectory-weight") != null) trajectoryReview.put("weight", Double.parseDouble(flag(job.argv(), "--trajectory-weight")));
                trajectoryReview.put("use", flag(job.argv(), "--trajectory-use"));
                cfg.put("trajectory_review", trajectoryReview);
                runBench.runOnce(cfg, job.runId(), flag(job.argv(), "--task") == null ? "L7_full_platform" : flag(job.argv(), "--task"), mode, planSource);
                queue.finish(job.id(), "succeeded", 0, resultLine(Path.of(props.resultsDir(), job.runId())));
                importer.importRun(Path.of(props.resultsDir(), job.runId()), job.kind().equals("run") ? job.id() : null);
                if (job.experimentId() != null) experiments.finalizeIfDone(job.experimentId());   // the missing call site
            } catch (Exception e) {
                log.error("job {} failed", job.id(), e);
                queue.finish(job.id(), "failed", 1, e.toString());
                if (job.experimentId() != null) experiments.finalizeIfDone(job.experimentId());   // a failed arm is still terminal
            } finally {
                busy.set(false);
                if (cancelCurrent) {   // move_aside: an aborted run's files must never mix into a re-run
                    final java.nio.file.Path runDir = java.nio.file.Path.of(props.resultsDir(), job.runId());
                    if (java.nio.file.Files.isDirectory(runDir) && !"score_only".equals(job.kind())) {
                        try {
                            final java.nio.file.Path aborted = java.nio.file.Path.of(props.resultsDir(), "_aborted");
                            java.nio.file.Files.createDirectories(aborted);
                            java.nio.file.Files.move(runDir, aborted.resolve(job.runId() + "-" + System.currentTimeMillis() / 1000));
                        } catch (Exception e) {
                            // a failed move here is exactly the "must never mix into a re-run" case
                            // this code exists to prevent - the run dir stays in place, unmoved
                            log.warn("could not move aside aborted run {}: {}", runDir, e.toString());
                        }
                    }
                    queue.finish(job.id(), "cancelled", null, "cancelled mid-run");
                    if (job.experimentId() != null) experiments.finalizeIfDone(job.experimentId());
                }
            }
        });
    }

    static String flag(final List<String> argv, final String name) {
        return argv.stream().filter(a -> a.startsWith(name + "=")).map(a -> a.substring(name.length() + 1)).findFirst().orElse(null);
    }

    public boolean busyNow() { return busy.get(); }
    public Instant idleSince() { return null; }
}
