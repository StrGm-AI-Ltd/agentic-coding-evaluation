package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live panel's accumulation rules — the Jinja page's JS, in Java. */
class JobLiveStateTest {

    private static final String STEP = """
            event: step_started
            data: {"type": "step_started", "step": "T1", "continuation": false, "source": "packs"}""";
    private static final String STEP_CONT = """
            event: step_started
            data: {"type": "step_started", "step": "T1", "continuation": true, "source": "instructions"}""";

    @Test
    void stepsAccumulateWithContinuationMarks() {
        final var state = new JobLiveState();
        SseParser.parseAll(STEP + "\n\n" + STEP_CONT + "\n\n").forEach(state::apply);
        assertEquals(List.of("T1", "T1 (continued)"), state.steps());
        assertEquals("T1 (continued)", state.currentStep());
    }

    @Test
    void sessionsShowShortIdAndEffort() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "755478fc-f323-565e-92f8-1a5b10584e41", "reasoning_effort": "high"}

                """).forEach(state::apply);
        assertEquals(List.of(new JobLiveState.SessionRow(
                "755478fc-f323-565e-92f8-1a5b10584e41", "755478fc (high)", "", null, null, null, null, null, null, null)), state.sessions());
    }

    /** RunBench names every session with the stage/task it's actually for (AgentSession.header's
     *  "label" field); this turns that raw internal name into what the sessions grid's new
     *  "description" column shows - what the session actually is, not just its id. */
    @Test
    void sessionDescription_mapsEveryRealLabelToHumanText() {
        assertEquals("Definition", JobLiveState.sessionDescription(labelNode("p0_definition")));
        assertEquals("Plan", JobLiveState.sessionDescription(labelNode("p1_plan")));
        assertEquals("Parallel plan", JobLiveState.sessionDescription(labelNode("PARALLEL_PLAN")));
        assertEquals("Integration", JobLiveState.sessionDescription(labelNode("INTEGRATION")));
        assertEquals("Self review", JobLiveState.sessionDescription(labelNode("REVIEW")));
        assertEquals("Trajectory review", JobLiveState.sessionDescription(labelNode("TRAJECTORY_REVIEW")));
        assertEquals("Task T2", JobLiveState.sessionDescription(labelNode("T2")));
    }

    @Test
    void sessionDescription_showsTheContinuationKindOnASuffixedLabel() {
        assertEquals("Task T3 (continue)", JobLiveState.sessionDescription(labelNode("T3-continue")));
        assertEquals("Task T3 (wrapup)", JobLiveState.sessionDescription(labelNode("T3-wrapup")));
        assertEquals("Task T3 (handoff)", JobLiveState.sessionDescription(labelNode("T3-handoff")));
    }

    @Test
    void sessionDescription_emptyWhenTheSessionHasNoLabel() {
        assertEquals("", JobLiveState.sessionDescription(Json.MAPPER.readTree("{}")));
    }

    private static JsonNode labelNode(final String label) {
        return Json.MAPPER.readTree("{\"label\": \"" + label + "\"}");
    }

    /** RunBench writes execution_order.json (the real planned task waves) before any task session
     *  starts, so the live page can show it - explaining e.g. T4 legitimately starting before T3 as
     *  the declared plan, not the runner going backwards. */
    @Test
    void executionOrderAccumulatesFromTheOneShotEvent() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: execution_order
                data: {"type": "execution_order", "waves": [["T1"], ["T2", "T4"], ["T3"]]}

                """).forEach(state::apply);
        assertEquals(List.of(List.of("T1"), List.of("T2", "T4"), List.of("T3")), state.executionOrder());
    }

    @Test
    void executionOrderIsEmptyBeforeTheEventArrives() {
        assertEquals(List.of(), new JobLiveState().executionOrder());
    }

    @Test
    void parseExecutionOrder_ignoresNonArrayWaves() {
        assertEquals(List.of(), JobLiveState.parseExecutionOrder(Json.MAPPER.readTree("{}")));
        assertEquals(List.of(), JobLiveState.parseExecutionOrder(Json.MAPPER.readTree("{\"waves\": \"bogus\"}")));
    }

    /** session_done now carries the real session_id (Tailer reads it from the session file's own
     *  header) and the finish reason from its trailing {"type":"end"} line - matched exactly, not
     *  guessed as "the oldest still-open session". */
    @Test
    void sessionDoneMarksTheMatchingSessionByIdWithItsFinishReason() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "755478fc-f323-565e-92f8-1a5b10584e41"}

                event: session_done
                data: {"type": "session_done", "session_id": "755478fc-f323-565e-92f8-1a5b10584e41", "finish": "tool_calls"}

                """).forEach(state::apply);
        assertEquals(List.of(new JobLiveState.SessionRow(
                "755478fc-f323-565e-92f8-1a5b10584e41", "755478fc", "", "tool_calls", null, null, null, null, null, null)), state.sessions());
    }

    /** The whole point of matching by real id: a run's sessions need not finish in the order they
     *  started (true even outside genuine parallel waves - the second-started session here is a
     *  short reviewer pass that completes first). Exact id matching gets this right where "oldest
     *  still-open session" guessing would have marked the wrong row done. */
    @Test
    void sessionDoneMatchesByIdEvenWhenSessionsFinishOutOfStartOrder() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: session_started
                data: {"session_id": "bbbbbbbb-0000-0000-0000-000000000000"}

                event: session_done
                data: {"type": "session_done", "session_id": "bbbbbbbb-0000-0000-0000-000000000000", "finish": "stop"}

                """).forEach(state::apply);
        assertEquals(List.of(
                new JobLiveState.SessionRow("aaaaaaaa-0000-0000-0000-000000000000", "aaaaaaaa", "", null, null, null, null, null, null, null),
                new JobLiveState.SessionRow("bbbbbbbb-0000-0000-0000-000000000000", "bbbbbbbb", "", "stop", null, null, null, null, null, null)), state.sessions(),
                "the second-started session is the one marked done; the first (still open) is unaffected");
    }

    @Test
    void sessionDoneWithNoMatchingSessionIsIgnored() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_done
                data: {"type": "session_done", "session_id": "no-such-session", "finish": "stop"}

                """).forEach(state::apply);
        assertEquals(List.of(), state.sessions(), "no session to match - dropped, not a crash");
    }

    /** RecordingProxy tags every request with the session's full id (X-Ace-Session-Id, unambiguous
     *  even under concurrent parallel-wave sessions); oMLX reports prefill/decode speed itself per
     *  response - this averages those across the requests attributed to each session. */
    @Test
    void requestsWithASessionIdAccumulateIntoThatSessionsAverageSpeed() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "prefill_tok_per_sec": 100.0, "decode_tok_per_sec": 20.0}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "prefill_tok_per_sec": 120.0, "decode_tok_per_sec": 30.0}

                """).forEach(state::apply);
        final var session = state.sessions().get(0);
        assertEquals(110.0, session.avgPrefillTokPerSec(), "(100+120)/2");
        assertEquals(25.0, session.avgDecodeTokPerSec(), "(20+30)/2");
    }

    @Test
    void requestsWithNoSessionIdOrAnUnknownOneAreNotAttributed() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "prefill_tok_per_sec": 100.0, "decode_tok_per_sec": 20.0}

                event: request
                data: {"type": "request", "session_id": "no-such-session", "prefill_tok_per_sec": 100.0, "decode_tok_per_sec": 20.0}

                """).forEach(state::apply);
        final var session = state.sessions().get(0);
        assertNull(session.avgPrefillTokPerSec(), "neither request named this session's id");
        assertNull(session.avgDecodeTokPerSec());
    }

    /** A request can carry one speed field without the other (e.g. an estimated-usage entry) -
     *  the metric it's missing must not be dragged toward it (no implicit zero). */
    @Test
    void aRequestMissingOneSpeedFieldDoesNotPolluteTheOtherAverage() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "decode_tok_per_sec": 20.0}

                """).forEach(state::apply);
        final var session = state.sessions().get(0);
        assertNull(session.avgPrefillTokPerSec());
        assertEquals(20.0, session.avgDecodeTokPerSec());
    }

    /** Wall time is the session's own transcript's start ts to its "end" ts - computed once, on
     *  session_done, from timestamps Tailer now forwards on both events. */
    @Test
    void sessionDoneComputesWallTimeFromStartAndEndTimestamps() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000", "ts": "2026-09-25T00:00:00Z"}

                event: session_done
                data: {"type": "session_done", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "finish": "stop", "ts": "2026-09-25T00:12:30Z"}

                """).forEach(state::apply);
        assertEquals(750.0, state.sessions().get(0).wallSec(), "12m30s");
    }

    @Test
    void wallTimeIsNullWhileASessionIsStillRunning() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000", "ts": "2026-09-25T00:00:00Z"}

                """).forEach(state::apply);
        assertNull(state.sessions().get(0).wallSec());
    }

    @Test
    void wallTimeIsNullWhenEitherTimestampIsMissing() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: session_done
                data: {"type": "session_done", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "finish": "stop", "ts": "2026-09-25T00:12:30Z"}

                """).forEach(state::apply);
        assertNull(state.sessions().get(0).wallSec(), "no start ts to compute a duration from");
    }

    /** Total tokens is a running sum across the session's own requests, visible live - unlike wall
     *  time it doesn't need the session to have ended. */
    @Test
    void requestsWithCompletionTokensSumIntoTheSessionsTotal() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "completion_tokens": 120}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "completion_tokens": 340}

                """).forEach(state::apply);
        assertEquals(460L, state.sessions().get(0).totalTokens());
    }

    @Test
    void aRequestWithNoCompletionTokensDoesNotResetTheRunningTotal() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "completion_tokens": 120}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "status": 429}

                """).forEach(state::apply);
        assertEquals(120L, state.sessions().get(0).totalTokens());
    }

    /** ttft_sec IS the prefill wall time for a request; latency_sec - ttft_sec is the decode wall time
     *  for everything after the first token. Both are running sums across the session's own requests. */
    @Test
    void requestsSumIntoPrefillAndDecodeWallTime() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "latency_sec": 30.0, "ttft_sec": 0.05}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "latency_sec": 90.0, "ttft_sec": 0.15}

                """).forEach(state::apply);
        final var session = state.sessions().get(0);
        assertEquals(0.2, session.prefillWallSec(), 0.001, "0.05+0.15");
        assertEquals(119.8, session.decodeWallSec(), 0.001, "(30-0.05)+(90-0.15)");
    }

    /** A request with no ttft_sec (not streamed, or a degenerate payload) contributes to neither sum
     *  rather than guessing the prefill/decode split from its total latency alone. */
    @Test
    void aRequestWithNoTtftDoesNotPolluteEitherWallTimeSum() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "aaaaaaaa-0000-0000-0000-000000000000"}

                event: request
                data: {"type": "request", "session_id": "aaaaaaaa-0000-0000-0000-000000000000", "latency_sec": 5.0}

                """).forEach(state::apply);
        final var session = state.sessions().get(0);
        assertNull(session.prefillWallSec());
        assertNull(session.decodeWallSec());
    }

    /** The live requests grid used to cap at 20 and silently drop the rest, with no indication in the
     *  grid that it was truncated and no way to scroll to see more, while the "requests: N" summary
     *  line was never capped - visibly disagreeing with what the grid showed underneath it. Now every
     *  request survives for the life of the job's live view. */
    @Test
    void requestsAccumulateAllNewestFirst() {
        final var state = new JobLiveState();
        final var stream = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            stream.append("event: request\ndata: {\"type\": \"request\", \"seq\": ").append(i)
                    .append(", \"ts\": \"t").append(i)
                    .append("\", \"status\": 200, \"latency_sec\": ").append(i / 10.0)
                    .append(", \"ttft_sec\": 1.0, \"budget_spent_completion_tokens\": ").append(i * 10)
                    .append(", \"client_aborted\": false}\n\n");
        }
        SseParser.parseAll(stream.toString()).forEach(state::apply);

        assertEquals(25, state.requestCount());
        assertEquals(250L, state.lastTokens());

        final var recent = state.recentRequests();
        assertEquals(25, recent.size(), "every request is kept, not just the most recent 20");
        assertEquals("t25", recent.get(0).ts(), "newest first");
        assertEquals("t1", recent.get(recent.size() - 1).ts(), "the oldest request is still present");
        assertEquals(250L, recent.get(0).tokens());
        assertEquals(2.5, recent.get(0).latencySec(), "fixture latency for seq 25 is 25/10.0");
    }

    @Test
    void statusEventsCarryTheLogTail() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: status
                data: {"status": "running", "pid": 82544, "result_line": "step 2 running"}

                """).forEach(state::apply);
        assertEquals("step 2 running", state.logTail());

        SseParser.parseAll("""
                event: status
                data: {"status": "running", "pid": 82544, "result_line": "step 3 running"}

                """).forEach(state::apply);
        assertEquals("step 3 running", state.logTail(), "the tail is the latest line");
    }

    @Test
    void unknownEventsAreIgnored() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: verification_ran
                data: {"type": "verification_ran", "task": "T1"}

                """).forEach(state::apply);
        assertEquals(0, state.requestCount());
        assertNull(state.currentStep());
        assertNull(state.logTail());
    }

    /** The M1 race, pinned: concurrent bursts + snapshots must never throw, and no event is lost. */
    @Test
    void concurrentApplyAndSnapshotNeverThrows() throws Exception {
        final var state = new JobLiveState();
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final int events = 5_000;

        final var writer = new Thread(() -> {
            try {
                for (int i = 0; i < events; i++) {
                    if (i % 10 == 0) {
                        state.apply(event("{\"type\": \"step_started\", \"step\": \"T" + (i / 10) + "\"}"));
                    }
                    if (i % 7 == 0) {
                        state.apply(event("{\"type\": \"session_started\", \"session_id\": \"s-0000000-" + i
                                + "\", \"reasoning_effort\": \"high\"}"));
                    }
                    if (i % 11 == 0) {   // mutates an existing sessions entry (not just appends) under the same lock -
                                          // "s-0000000-0" is created at i=0 (i % 7 == 0 too), before any of these fire
                        state.apply(event("{\"type\": \"session_done\", \"session_id\": \"s-0000000-0\", \"finish\": \"stop\"}"));
                    }
                    state.apply(event("{\"type\": \"request\", \"seq\": " + i + ", \"ts\": \"t" + i
                            + "\", \"status\": 200, \"latency_sec\": " + (i / 10.0)
                            + ", \"ttft_sec\": 1.0, \"budget_spent_completion_tokens\": " + (i * 10) + "}"));
                    state.apply(event("{\"type\": \"status\", \"status\": \"running\", \"result_line\": \"line " + i + "\"}"));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "live-state-writer");

        final var reader = new Thread(() -> {
            try {
                for (int i = 0; i < 5_000; i++) {
                    state.currentStep();
                    state.sessions();
                    state.requestCount();
                    state.lastTokens();
                    state.recentRequests();
                    state.logTail();
                    state.steps();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "live-state-reader");

        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        // join(timeout) only times out — verify the threads actually finished, or the final
        // assertions below would race a live mutator and fail for misleading reasons
        assertFalse(writer.isAlive(), "writer outlived the join timeout");
        assertFalse(reader.isAlive(), "reader outlived the join timeout");
        assertNull(failure.get(), "no ConcurrentModificationException or other failure");
        assertEquals(events, state.requestCount(), "every request event is applied exactly once");
        assertEquals(events, state.recentRequests().size(), "every request is retained, not just the most recent 20");
        assertEquals("line " + (events - 1), state.logTail());
    }

    /** Degenerate payloads the tailer can deliver mid-write — no field is guaranteed. */
    @Test
    void requestEventWithMissingOrNullFieldsBuildsNullRow() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: request
                data: {"type": "request", "seq": 1}

                """).forEach(state::apply);
        assertEquals(1, state.requestCount());
        final var row = state.recentRequests().get(0);
        assertNull(row.ts());
        assertNull(row.status());
        assertNull(row.latencySec());
        assertNull(row.ttftSec());
        assertNull(row.tokens());
        assertFalse(row.clientAborted());
        assertNull(row.abortReason());
        assertNull(state.lastTokens());
    }

    /** RecordingProxy journals abort_reason alongside client_aborted (2026-09-25) so the requests
     *  grid can show WHY, not just that, a request was aborted. */
    @Test
    void requestEventCarriesTheAbortReasonIntoTheRow() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: request
                data: {"type": "request", "seq": 1, "client_aborted": true, "abort_reason": "task-wall budget exceeded (900s)"}

                """).forEach(state::apply);
        final var row = state.recentRequests().get(0);
        assertTrue(row.clientAborted());
        assertEquals("task-wall budget exceeded (900s)", row.abortReason());
    }

    @Test
    void statusEventWithNullResultLineKeepsPreviousTail() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: status
                data: {"status": "running", "result_line": "step 2 running"}

                """).forEach(state::apply);
        SseParser.parseAll("""
                event: status
                data: {"status": "running", "result_line": null}

                """).forEach(state::apply);
        assertEquals("step 2 running", state.logTail(), "a null tail does not wipe the previous one");
    }

    @Test
    void sessionEventWithoutIdRendersBare() {
        final var state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"reasoning_effort": null}

                """).forEach(state::apply);
        assertEquals(List.of(new JobLiveState.SessionRow("", "?", "", null, null, null, null, null, null, null)), state.sessions(),
                "missing ids render as ? without throwing");
    }

    private static SseEvent event(final String json) {
        return new SseEvent(null, Json.MAPPER.readTree(json));
    }
}
