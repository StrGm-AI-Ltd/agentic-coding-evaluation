package com.agentbench.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The job page's SSE loop: streams /jobs/{id}/events until the server ends the stream
 * (terminal job), retrying dropped connections up to {@link #MAX_ATTEMPTS} times; a
 * stop flag (checked on each delivered event) aborts within one server ping (~2 s).
 * Extracted from the view so the lifecycle is testable without Vaadin.
 */
final class JobEventLoop implements Runnable {

    static final int MAX_ATTEMPTS = 3;

    private final ServiceClient client;
    private final long jobId;
    private final Supplier<Boolean> stopped;
    private final Consumer<SseEvent> onEvent;
    private final Runnable onConnectionLost;
    private final long backoffMs;

    private volatile boolean connectionLost;

    JobEventLoop(ServiceClient client, long jobId, Supplier<Boolean> stopped,
            Consumer<SseEvent> onEvent, Runnable onConnectionLost) {
        this(client, jobId, stopped, onEvent, onConnectionLost, 2000);
    }

    /** Package-private for tests: a short backoff keeps the retry cases fast. */
    JobEventLoop(ServiceClient client, long jobId, Supplier<Boolean> stopped,
            Consumer<SseEvent> onEvent, Runnable onConnectionLost, long backoffMs) {
        this.client = client;
        this.jobId = jobId;
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
                client.streamJobEvents(jobId, event -> {
                    if (stopped.get()) {
                        throw new IllegalStateException("view detached"); // aborts the connection
                    }
                    connectionLost = false;
                    onEvent.accept(event);
                });
                return; // the server ends the stream when the job is terminal
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (stopped.get()) {
                    return;
                }
                attempts += 1;
                connectionLost = true;
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (connectionLost && !stopped.get()) {
            onConnectionLost.run(); // the loop gave up — the view keeps polling as fallback
        }
    }
}
