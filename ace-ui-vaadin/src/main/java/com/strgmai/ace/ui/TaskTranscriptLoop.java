package com.strgmai.ace.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The task dialog's "Live" tab SSE loop: streams /jobs/{id}/tasks/{taskId}/transcript until the
 * server ends the stream (terminal job), retrying dropped connections up to {@link #MAX_ATTEMPTS}
 * times. A sibling of {@link JobEventLoop} (same retry/backoff/stop-flag shape), not a
 * generalization of it: duplicated rather than cross-wired, so a future change to one loop's
 * retry behavior doesn't have to be re-justified against the other's - the same reasoning
 * Judge.java's own class javadoc gives for duplicating Reviews.java's local/external convention
 * rather than sharing it.
 */
final class TaskTranscriptLoop implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(TaskTranscriptLoop.class);

    static final int MAX_ATTEMPTS = 3;

    private final ServiceClient client;
    private final String jobId;
    private final String taskId;
    private final Supplier<Boolean> stopped;
    private final Consumer<SseEvent> onEvent;
    private final Runnable onConnectionLost;
    private final long backoffMs;

    private volatile boolean connectionLost;

    TaskTranscriptLoop(final ServiceClient client, final String jobId, final String taskId, final Supplier<Boolean> stopped,
            final Consumer<SseEvent> onEvent, final Runnable onConnectionLost) {
        this(client, jobId, taskId, stopped, onEvent, onConnectionLost, 2000);
    }

    /** Package-private for tests: a short backoff keeps the retry cases fast. */
    TaskTranscriptLoop(final ServiceClient client, final String jobId, final String taskId, final Supplier<Boolean> stopped,
            final Consumer<SseEvent> onEvent, final Runnable onConnectionLost, final long backoffMs) {
        this.client = client;
        this.jobId = jobId;
        this.taskId = taskId;
        this.stopped = stopped;
        this.onEvent = onEvent;
        this.onConnectionLost = onConnectionLost;
        this.backoffMs = backoffMs;
    }

    boolean isConnectionLost() {
        return connectionLost;
    }

    @Override
    public void run() {
        int attempts = 0;
        while (!stopped.get() && attempts < MAX_ATTEMPTS) {
            try {
                client.streamTaskTranscript(jobId, taskId, event -> {
                    if (stopped.get()) {
                        throw new IllegalStateException("dialog closed"); // aborts the connection
                    }
                    connectionLost = false;
                    onEvent.accept(event);
                });
                return; // the server ends the stream when the job is terminal
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                if (stopped.get()) {
                    return;
                }
                attempts += 1;
                log.warn("task transcript SSE stream for job {} task {} failed (attempt {}/{}): {}",
                        jobId, taskId, attempts, MAX_ATTEMPTS, e.toString());
                connectionLost = true;
                try {
                    Thread.sleep(backoffMs);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (connectionLost && !stopped.get()) {
            onConnectionLost.run(); // gave up - the tab shows a "connection lost" notice
        }
    }
}
