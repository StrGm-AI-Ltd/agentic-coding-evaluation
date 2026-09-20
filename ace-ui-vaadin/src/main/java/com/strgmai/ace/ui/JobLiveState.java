package com.strgmai.ace.ui;

import tools.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The job page's live progress, accumulated from /jobs/{id}/events — the server-side
 * twin of the Jinja page's streaming JS: step list (with continuation marks), sessions,
 * request count and last completion tokens, the 20 most recent requests (newest first),
 * and the log tail the status events carry.
 *
 * <p>Synchronized: events are applied on the SSE thread while the UI thread reads
 * snapshots, and the service emits request bursts — without the lock, a snapshot
 * mid-burst throws {@code ConcurrentModificationException} inside the UI render.
 */
public final class JobLiveState implements Serializable {

    /** One row of the recent-requests table. */
    public record RequestRow(String ts, Integer status, Double latencySec, Double ttftSec,
            Long tokens, boolean clientAborted) implements Serializable {
    }

    private static final long serialVersionUID = 1L;

    static final int MAX_RECENT_REQUESTS = 20;

    private final List<String> steps = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();
    private final Deque<RequestRow> recentRequests = new ArrayDeque<>();
    private long requestCount;
    private Long lastTokens;
    private String logTail;

    public synchronized void apply(SseEvent event) {
        JsonNode data = event.data();
        switch (event.payloadType()) {
            case "step_started" -> {
                String label = Fmt.textOr(data.path("step"), "?");
                if (data.path("continuation").asBoolean(false)) {
                    label += " (continued)";
                }
                steps.add(label);
            }
            case "session_started" -> sessions.add(sessionLabel(data));
            case "request" -> {
                requestCount += 1;
                if (data.hasNonNull("budget_spent_completion_tokens")) {
                    lastTokens = data.get("budget_spent_completion_tokens").longValue();
                }
                recentRequests.addFirst(new RequestRow(
                        Fmt.textOr(data.path("ts"), null),
                        data.path("status").isNumber() ? data.path("status").intValue() : null,
                        data.path("latency_sec").isNumber() ? data.path("latency_sec").doubleValue() : null,
                        data.path("ttft_sec").isNumber() ? data.path("ttft_sec").doubleValue() : null,
                        data.hasNonNull("budget_spent_completion_tokens")
                                ? data.get("budget_spent_completion_tokens").longValue() : null,
                        data.path("client_aborted").asBoolean(false)));
                while (recentRequests.size() > MAX_RECENT_REQUESTS) {
                    recentRequests.removeLast();
                }
            }
            case "status" -> {
                if (data.hasNonNull("result_line")) {
                    logTail = data.get("result_line").asText();
                }
            }
            default -> { // verification_ran etc. — not part of the live panel
            }
        }
    }

    private static String sessionLabel(JsonNode data) {
        String id = Fmt.textOr(data.path("session_id"), "");
        String effort = Fmt.textOr(data.path("reasoning_effort"), null);
        String shortId = id.isBlank() ? "?" : (id.length() > 8 ? id.substring(0, 8) : id);
        return effort == null || effort.isBlank() ? shortId : shortId + " (" + effort + ")";
    }

    /** A snapshot copy — never the live list. */
    public synchronized List<String> steps() {
        return new ArrayList<>(steps);
    }

    public synchronized String currentStep() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    /** A snapshot copy — never the live list. */
    public synchronized List<String> sessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized long requestCount() {
        return requestCount;
    }

    public synchronized Long lastTokens() {
        return lastTokens;
    }

    /** A snapshot copy — never the live deque. */
    public synchronized List<RequestRow> recentRequests() {
        return new ArrayList<>(recentRequests);
    }

    public synchronized String logTail() {
        return logTail;
    }
}
