package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(List.of("755478fc (high)"), state.sessions());
    }

    @Test
    void requestsCountTokensAndCapAt20NewestFirst() {
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
        assertEquals(JobLiveState.MAX_RECENT_REQUESTS, recent.size());
        assertEquals("t25", recent.get(0).ts(), "newest first");
        assertEquals("t6", recent.get(recent.size() - 1).ts(), "the 20 newest survive the cap");
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
        assertEquals(JobLiveState.MAX_RECENT_REQUESTS, state.recentRequests().size());
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
        assertNull(state.lastTokens());
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
        assertEquals(List.of("?"), state.sessions(), "missing ids render as ? without throwing");
    }

    private static SseEvent event(final String json) {
        return new SseEvent(null, Json.MAPPER.readTree(json));
    }
}
