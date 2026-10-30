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
 *  takes a human to notice. */
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
        final var reference = lastWriteTime(runDir.resolve("interactions.jsonl")).orElse(startedAt);
        if (isStuck(Instant.now(), reference, STUCK_THRESHOLD)) {
            log.error("bench-runner: no new request logged under {} in over {} - presumed wedged (see #593); "
                    + "forcing a restart so reconcile() requeues it cleanly on the next startup", runDir, STUCK_THRESHOLD);
            Runtime.getRuntime().halt(1);   // halt(), not exit(): a shutdown hook doing its own I/O could hang too
        }
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
