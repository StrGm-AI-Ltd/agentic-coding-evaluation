package com.strgmai.ace.ui;

import tools.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One row of the sessions table: its short label, the stage (the *.log file's stem) it ended
     *  at once that log reported done (null while still open), and its average prefill/decode
     *  speed across the requests attributed to it (RecordingProxy tags each with the session's
     *  full id via the X-Ace-Session-Id header; both null until a request with real oMLX-reported
     *  usage.*_tokens_per_second lands). id is the full session id (correlation key) - label is the
     *  truncated display form. */
    public record SessionRow(String id, String label, String endedStage,
            Double avgPrefillTokPerSec, Double avgDecodeTokPerSec) implements Serializable {
    }

    private static final long serialVersionUID = 1L;

    static final int MAX_RECENT_REQUESTS = 20;

    private final List<String> steps = new ArrayList<>();
    private final List<SessionRow> sessions = new ArrayList<>();
    // session id -> [prefillSum, prefillN, decodeSum, decodeN], for the running averages in SessionRow
    private final Map<String, double[]> speedSums = new LinkedHashMap<>();
    private final Deque<RequestRow> recentRequests = new ArrayDeque<>();
    private long requestCount;
    private Long lastTokens;
    private String logTail;

    public synchronized void apply(final SseEvent event) {
        final var data = event.data();
        switch (event.payloadType()) {
            case "step_started" -> {
                var label = Fmt.textOr(data.path("step"), "?");   // reassigned below - not final
                if (data.path("continuation").asBoolean(false)) {
                    label += " (continued)";
                }
                steps.add(label);
            }
            case "session_started" -> sessions.add(new SessionRow(
                    Fmt.textOr(data.path("session_id"), ""), sessionLabel(data), null, null, null));
            case "session_done" -> markSessionDone(data);
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
                accumulateSpeed(data);
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

    private static String sessionLabel(final JsonNode data) {
        final var id = Fmt.textOr(data.path("session_id"), "");
        final var effort = Fmt.textOr(data.path("reasoning_effort"), null);
        final var shortId = id.isBlank() ? "?" : (id.length() > 8 ? id.substring(0, 8) : id);
        return effort == null || effort.isBlank() ? shortId : shortId + " (" + effort + ")";
    }

    /** session_done carries no session_id, only the stage (log file) that just reported done - the
     *  oldest still-open session is taken as the one that just ended. Holds as long as sessions
     *  complete in the order they were started, true for this harness's mostly-sequential phases. */
    private void markSessionDone(final JsonNode data) {
        final var stage = stageOf(data);
        for (int i = 0; i < sessions.size(); i++) {
            final var s = sessions.get(i);
            if (s.endedStage() == null) {
                sessions.set(i, new SessionRow(s.id(), s.label(), stage, s.avgPrefillTokPerSec(), s.avgDecodeTokPerSec()));
                return;
            }
        }
    }

    private static String stageOf(final JsonNode data) {
        return Fmt.textOr(data.path("log"), "?").replaceFirst("\\.[^.]+$", "");
    }

    /** Accumulates a request's prefill/decode speed (when it has them - not every request does,
     *  e.g. errors) into its session's running average, keyed by the full session id Tailer/
     *  RecordingProxy attach via X-Ace-Session-Id. A request with no session_id (no session open
     *  yet, or the agent path that predates this header) is silently not attributed to any session. */
    private void accumulateSpeed(final JsonNode data) {
        final var sessionId = Fmt.textOr(data.path("session_id"), null);
        if (sessionId == null || sessionId.isBlank()) return;
        for (int i = 0; i < sessions.size(); i++) {
            final var s = sessions.get(i);
            if (!sessionId.equals(s.id())) continue;
            final var sums = speedSums.computeIfAbsent(sessionId, k -> new double[4]);
            if (data.path("prefill_tok_per_sec").isNumber()) { sums[0] += data.path("prefill_tok_per_sec").asDouble(); sums[1]++; }
            if (data.path("decode_tok_per_sec").isNumber()) { sums[2] += data.path("decode_tok_per_sec").asDouble(); sums[3]++; }
            final Double avgPrefill = sums[1] > 0 ? sums[0] / sums[1] : null;
            final Double avgDecode = sums[3] > 0 ? sums[2] / sums[3] : null;
            sessions.set(i, new SessionRow(s.id(), s.label(), s.endedStage(), avgPrefill, avgDecode));
            return;
        }
    }

    /** A snapshot copy — never the live list. */
    public synchronized List<String> steps() {
        return new ArrayList<>(steps);
    }

    public synchronized String currentStep() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    /** A snapshot copy — never the live list. */
    public synchronized List<SessionRow> sessions() {
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
