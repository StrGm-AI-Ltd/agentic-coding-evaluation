package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.service.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** An operator's request to stop a RUNNING job gracefully - once the task currently in progress
 *  actually finishes, rather than the existing /pause endpoint's immediate interrupt, which can
 *  discard real work: found live, repeatedly, this session (aborting a task mid-tool-call loses
 *  whatever turns it had already made, even though the job's own "paused" status looked clean).
 *
 *  Deliberately thin: this does not reimplement pausing. It just calls the EXISTING
 *  JobQueue.pause() at a safe, between-tasks checkpoint instead of at an operator-chosen,
 *  arbitrary moment - everything downstream of that call (the cancel-watch thread noticing
 *  pause_requested, WorkerService's own pauseCurrent handling, landing on "paused") is the exact
 *  same machinery the immediate-pause button already exercises, unchanged.
 *
 *  A plain in-memory mailbox, keyed by runId - same reasoning as Intervene (this class's own
 *  closest sibling): a graceful-pause request is live state about a job that is ACTIVELY RUNNING
 *  right now, not state that must survive an ace-service restart - if the service restarts, the
 *  current task (and the request to stop after it) are moot either way. */
@Component
public class PauseAfterTask {
    private static final Logger log = LoggerFactory.getLogger(PauseAfterTask.class);
    private final JobQueue queue;
    private final Map<String, UUID> requested = new ConcurrentHashMap<>();

    public PauseAfterTask(final JobQueue queue) { this.queue = queue; }

    /** called from the HTTP endpoint - jobId is resolved there (from the path variable) since
     *  RunBench's own orchestration loop only ever knows its run's string id, not the job's UUID. */
    public void request(final String runId, final UUID jobId) { requested.put(runId, jobId); }

    public boolean isRequested(final String runId) { return requested.containsKey(runId); }

    /** Called by RunBench's own orchestration loop at a safe, between-tasks checkpoint - never
     *  mid-task, so nothing already in flight is ever interrupted by this. Consumes the request
     *  (removes it) whether or not the pause() call itself succeeds, so a transient failure here
     *  doesn't retry forever on every later task boundary too. */
    public void checkAndPauseIfRequested(final String runId) {
        final UUID jobId = requested.remove(runId);
        if (jobId == null) return;
        try { queue.pause(jobId); }
        catch (Exception e) { log.warn("could not pause job {} at its own requested graceful checkpoint: {}", jobId, e.toString()); }
    }
}
