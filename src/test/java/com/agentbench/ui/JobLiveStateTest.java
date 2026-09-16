package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        JobLiveState state = new JobLiveState();
        SseParser.parseAll(STEP + "\n\n" + STEP_CONT + "\n\n").forEach(state::apply);
        assertEquals(List.of("T1", "T1 (continued)"), state.steps());
        assertEquals("T1 (continued)", state.currentStep());
    }

    @Test
    void sessionsShowShortIdAndEffort() {
        JobLiveState state = new JobLiveState();
        SseParser.parseAll("""
                event: session_started
                data: {"session_id": "755478fc-f323-565e-92f8-1a5b10584e41", "reasoning_effort": "high"}

                """).forEach(state::apply);
        assertEquals(List.of("755478fc (high)"), state.sessions());
    }

    @Test
    void requestsCountTokensAndCapAt20NewestFirst() {
        JobLiveState state = new JobLiveState();
        StringBuilder stream = new StringBuilder();
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

        List<JobLiveState.RequestRow> recent = state.recentRequests();
        assertEquals(JobLiveState.MAX_RECENT_REQUESTS, recent.size());
        assertEquals("t25", recent.get(0).ts(), "newest first");
        assertEquals("t6", recent.get(recent.size() - 1).ts(), "the 20 newest survive the cap");
        assertEquals(250L, recent.get(0).tokens());
        assertEquals(2.5, recent.get(0).latencySec(), "fixture latency for seq 25 is 25/10.0");
    }

    @Test
    void statusEventsCarryTheLogTail() {
        JobLiveState state = new JobLiveState();
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
        JobLiveState state = new JobLiveState();
        SseParser.parseAll("""
                event: verification_ran
                data: {"type": "verification_ran", "task": "T1"}

                """).forEach(state::apply);
        assertEquals(0, state.requestCount());
        assertNull(state.currentStep());
        assertNull(state.logTail());
    }
}
