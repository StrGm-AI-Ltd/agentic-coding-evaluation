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
 * request count and last completion tokens, every request seen so far (newest first),
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

    /** One row of the sessions table: its short label, a human-readable description of what the
     *  session actually is (its stage/task, from the session file's own "label" field - "T2", "T2
     *  (handoff)", "Definition", "Self review", ...), the reason it finished (its own transcript's
     *  trailing {"type":"end","finish":...} line - null while still open), and its average
     *  prefill/decode speed across the requests attributed to it (RecordingProxy tags each with the
     *  session's full id via the X-Ace-Session-Id header; both null until a request with real
     *  oMLX-reported usage.*_tokens_per_second lands). id is the full session id (correlation key) -
     *  label is the truncated display form. */
    public record SessionRow(String id, String label, String description, String endedStage,
            Double avgPrefillTokPerSec, Double avgDecodeTokPerSec) implements Serializable {
    }

    private static final long serialVersionUID = 1L;

    private final List<String> steps = new ArrayList<>();
    private final List<SessionRow> sessions = new ArrayList<>();
    // session id -> [prefillSum, prefillN, decodeSum, decodeN], for the running averages in SessionRow
    private final Map<String, double[]> speedSums = new LinkedHashMap<>();
    private final Deque<RequestRow> recentRequests = new ArrayDeque<>();
    private long requestCount;
    private Long lastTokens;
    private String logTail;
    // the run's declared task waves (RunBench writes execution_order.json before any task session
    // starts) - one entry per wave, each a list of task ids; empty until that one-shot event lands
    private List<List<String>> executionOrder = List.of();

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
                    Fmt.textOr(data.path("session_id"), ""), sessionLabel(data), sessionDescription(data), null, null, null));
            case "session_done" -> markSessionDone(data);
            case "execution_order" -> executionOrder = parseExecutionOrder(data);
            case "request" -> {
                requestCount += 1;
                if (data.hasNonNull("budget_spent_completion_tokens")) {
                    lastTokens = data.get("budget_spent_completion_tokens").longValue();
                }
                // every request for the life of the job's live view - #new bug: this used to cap at the
                // 20 most recent and silently drop the rest, with no indication in the grid that it was
                // truncated and no way to scroll to see more (the "requests: N" summary line was never
                // capped, so it visibly disagreed with what the grid showed underneath it)
                recentRequests.addFirst(new RequestRow(
                        Fmt.textOr(data.path("ts"), null),
                        data.path("status").isNumber() ? data.path("status").intValue() : null,
                        data.path("latency_sec").isNumber() ? data.path("latency_sec").doubleValue() : null,
                        data.path("ttft_sec").isNumber() ? data.path("ttft_sec").doubleValue() : null,
                        data.hasNonNull("budget_spent_completion_tokens")
                                ? data.get("budget_spent_completion_tokens").longValue() : null,
                        data.path("client_aborted").asBoolean(false)));
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

    /** waves is a JSON array of arrays of task ids, e.g. [["T1"],["T2","T4"],["T3"]] - a wave with
     *  more than one task ran one at a time (not concurrently) unless the job's argv actually enabled
     *  --parallel, but they're still a declared wave: neither depends on the other. */
    static List<List<String>> parseExecutionOrder(final JsonNode data) {
        final var waves = data.path("waves");
        if (!waves.isArray()) return List.of();
        final List<List<String>> out = new ArrayList<>();
        for (final var wave : waves) {
            final List<String> ids = new ArrayList<>();
            for (final var id : wave) if (id.isTextual()) ids.add(id.asText());
            out.add(ids);
        }
        return out;
    }

    private static String sessionLabel(final JsonNode data) {
        final var id = Fmt.textOr(data.path("session_id"), "");
        final var effort = Fmt.textOr(data.path("reasoning_effort"), null);
        final var shortId = id.isBlank() ? "?" : (id.length() > 8 ? id.substring(0, 8) : id);
        return effort == null || effort.isBlank() ? shortId : shortId + " (" + effort + ")";
    }

    private static final List<String> LABEL_SUFFIXES = List.of("-continue", "-wrapup", "-handoff", "-fix");

    /** RunBench names every session with the stage/task it's actually for - a plan phase
     *  ("p0_definition", "p1_plan"), a plan task id ("T1".."T4"), "PARALLEL_PLAN"/"INTEGRATION", a
     *  reviewer ("REVIEW"/"TRAJECTORY_REVIEW"), or one of those with a continuation suffix - turned
     *  human-readable here rather than showing the raw internal name verbatim. */
    static String sessionDescription(final JsonNode data) {
        // package-private (not private): tested directly against the full label taxonomy, like
        // Tailer's own pure static helpers, rather than only indirectly via apply()
        final var label = Fmt.textOr(data.path("label"), "");
        if (label.isBlank()) return "";
        var base = label;
        var suffix = "";
        for (final var s : LABEL_SUFFIXES) {
            if (base.endsWith(s)) { suffix = " (" + s.substring(1) + ")"; base = base.substring(0, base.length() - s.length()); break; }
        }
        return switch (base) {
            case "p0_definition" -> "Definition" + suffix;
            case "p1_plan" -> "Plan" + suffix;
            case "p2_implementation" -> "Implementation" + suffix;
            case "PARALLEL_PLAN" -> "Parallel plan" + suffix;
            case "INTEGRATION" -> "Integration" + suffix;
            case "REVIEW" -> "Self review" + suffix;
            case "TRAJECTORY_REVIEW" -> "Trajectory review" + suffix;
            default -> "Task " + base + suffix;   // a plain plan-task id, e.g. "T2"
        };
    }

    /** Matched by the session's own real id (Tailer reads it straight from the session file's
     *  header) - correct even when sessions finish out of start order, unlike the earlier "guess the
     *  oldest still-open session" approach this replaced, which relied on a *.log file / DONE-regex
     *  mechanism that nothing in the Java runner ever actually wrote (every session stayed "running"
     *  forever in the live view, regardless of how long ago it had actually finished). */
    private void markSessionDone(final JsonNode data) {
        final var sessionId = Fmt.textOr(data.path("session_id"), null);
        if (sessionId == null || sessionId.isBlank()) return;
        final var finish = Fmt.textOr(data.path("finish"), "?");
        for (int i = 0; i < sessions.size(); i++) {
            final var s = sessions.get(i);
            if (!sessionId.equals(s.id())) continue;
            sessions.set(i, new SessionRow(s.id(), s.label(), s.description(), finish, s.avgPrefillTokPerSec(), s.avgDecodeTokPerSec()));
            return;
        }
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
            sessions.set(i, new SessionRow(s.id(), s.label(), s.description(), s.endedStage(), avgPrefill, avgDecode));
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

    /** The declared task waves - each entry replaced wholesale by the one-shot execution_order
     *  event, never mutated in place, so (unlike steps()/sessions()) no defensive copy is needed. */
    public synchronized List<List<String>> executionOrder() {
        return executionOrder;
    }
}
