package com.strgmai.ace.service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** #593, found live twice (also rediscovered as #622): a disk-full event mid-write can wedge the
 *  single in-process bench-runner thread permanently - SIGQUIT produced zero thread-dump output
 *  during the real incident, consistent with a blocking native I/O call (or the container's own
 *  stdout pipe to Docker's log driver, also disk-backed) that never recovers even after disk space
 *  is freed. WorkerService.reconcile() already requeues a stuck job correctly on startup (#576/
 *  #577) - both real incidents were recovered by nothing more than a manual `docker compose up -d
 *  ace-service`. This automates exactly that proven recovery: if the one job the worker is
 *  supposed to be running hasn't logged a single new request in an abnormally long time, the
 *  worker is presumed wedged and the JVM force-exits so the container's own `restart: unless-
 *  stopped` policy (already set in docker-compose.yml) restarts it and reconcile() requeues the
 *  job cleanly - instead of needing an operator to notice and restart it by hand.
 *
 *  <p>Watches interactions.jsonl specifically (not a full recursive scan of the run dir, which can
 *  contain a full agent-written source tree) - every single LLM request appends a line to it
 *  (confirmed throughout this project's own harness-signature investigations), making it the
 *  single cheapest, most reliably-updated progress signal available without any deeper wiring into
 *  RunBench. A long but genuine pause between requests (a slow model, a long oracle-scoring build)
 *  is expected to be minutes, not the 30-minute threshold this uses - chosen to be comfortably
 *  longer than any normal gap while still recovering in a bounded time instead of however long it
 *  takes a human to notice.
 *
 *  <p>#593-followup, found live on job cd4c11a8's own INTEGRATION phase: that "minutes, not 30"
 *  assumption was wrong for a genuinely substantial local verification step - a multi-service
 *  docker-compose stack build + startup + a real integration-test pass is ALL local tool execution
 *  (AgentTools.bash(), own timeout up to 1800s per call), touching interactions.jsonl not at all,
 *  since that file only ever logs LLM proxy traffic. Confirmed live via docker top across several
 *  manual checks: two gradle daemons with steadily increasing CPU time, genuinely still working,
 *  while interactions.jsonl sat untouched for 30+ minutes - the watchdog fired and force-restarted
 *  a perfectly healthy worker. WorkerService.reconcile()'s own self-heal meant no data was lost
 *  (the job resumed cleanly from its last completed phase), but the restart still threw away real,
 *  in-progress work for no reason. hasLiveDescendantProcess() closes this gap: any live child
 *  process of this JVM (shelled out by AgentTools.bash() via DockerService.proc()) is direct,
 *  authoritative proof real local work is still happening, independent of how long it's been
 *  running or whether it's touched interactions.jsonl - cheaper and more precise than a recursive
 *  mtime scan of the run's own workspace tree (which can be gigabytes: build output, .git objects,
 *  node_modules-shaped trees) would be. A subprocess that's ACTUALLY wedged forever (bypassing even
 *  bash()'s own 1800s max timeout) is a different, deeper bug than this watchdog's own job - this
 *  check only needs to tell "genuinely still working" apart from "nothing is happening at all",
 *  not second-guess DockerService.proc()'s own timeout enforcement.
 *
 *  <p>#593-followup (2): the SAME live incident exposed a second, more urgent bug - a genuine
 *  restart-loop risk. interactions.jsonl's own mtime does not reset when the watchdog restarts the
 *  JVM; it is still whatever it was from the attempt BEFORE the restart. A resumed run first fast-
 *  forwards past every already-completed task (RunBench's own replay-skip, taking anywhere from
 *  seconds to several minutes depending on plan size - confirmed live, repeatedly, this session),
 *  which logs RunBench's own INFO lines but makes zero LLM calls and so never touches
 *  interactions.jsonl. Reproduced live immediately after the first restart on cd4c11a8: the watchdog
 *  fired A SECOND TIME, just 2 minutes later (its own 120s poll interval), because
 *  lastWriteTime(interactions.jsonl) was still the ORIGINAL pre-restart timestamp - the staleness
 *  clock for a job that just restarted must never read as "already the full backlog of stale time"
 *  the instant it starts again. currentJobStartedAt is now a FLOOR on the reference time (via max()),
 *  not merely a fallback for a file that doesn't exist yet - a freshly-restarted attempt's own
 *  staleness clock now genuinely starts at ITS OWN start, giving replay-skip (and any following
 *  real work) the full STUCK_THRESHOLD before the watchdog can fire again, closing the loop. */
@Component
public class StuckWorkerWatchdog {
    private static final Logger log = LoggerFactory.getLogger(StuckWorkerWatchdog.class);
    static final Duration STUCK_THRESHOLD = Duration.ofMinutes(30);

    private final WorkerService worker;

    public StuckWorkerWatchdog(final WorkerService worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ace.stuck-worker-check-sec:120}000", initialDelay = 120000)
    public void check() {
        if (!worker.busyNow()) return;
        final var runDir = worker.currentRunDir();
        final var startedAt = worker.currentJobStartedAt();
        if (runDir == null || startedAt == null) return;   // raced a job starting/finishing - try again next tick
        final var fileTime = lastWriteTime(runDir.resolve("interactions.jsonl")).orElse(startedAt);
        final var reference = fileTime.isAfter(startedAt) ? fileTime : startedAt;
        if (!isStuck(Instant.now(), reference, STUCK_THRESHOLD)) return;
        if (hasLiveDescendantProcess()) {
            log.debug("bench-runner: no new request logged under {} in over {}, but a live child process "
                    + "proves real local work is still happening (a long build/verification step) - not wedged", runDir, STUCK_THRESHOLD);
            return;
        }
        log.error("bench-runner: no new request logged under {} in over {}, and no live child process either - "
                + "presumed wedged (see #593); forcing a restart so reconcile() requeues it cleanly on the next startup", runDir, STUCK_THRESHOLD);
        Runtime.getRuntime().halt(1);   // halt(), not exit(): a shutdown hook doing its own I/O could hang too
    }

    /** #593-followup: true when this JVM has at least one live descendant process (a shelled-out
     *  bash tool call, its own child docker/gradle/etc. processes, ...) - direct, authoritative
     *  evidence of genuine ongoing local work, independent of interactions.jsonl. */
    static boolean hasLiveDescendantProcess() {
        return ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive);
    }

    /** Pure decision, tested directly: has more than threshold elapsed since the last observed
     *  progress. */
    static boolean isStuck(final Instant now, final Instant reference, final Duration threshold) {
        return Duration.between(reference, now).compareTo(threshold) > 0;
    }

    /** Empty when the file doesn't exist yet (a job's very first request hasn't landed) rather than
     *  throwing - the caller falls back to the job's own start time in that case. */
    static Optional<Instant> lastWriteTime(final Path file) {
        try {
            return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (final IOException e) {
            return Optional.empty();
        }
    }
}
