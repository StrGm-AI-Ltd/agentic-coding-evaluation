package com.strgmai.ace.service.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The trajectory analyzer's measurable signals: thrash, ping-pong, the docker-window exclusion,
 *  and the objective index's penalties (and what it deliberately does NOT penalise). */
class TrajectoryTest {

    private static Trajectory.Turn fileTurn(final int seq, final String tool, final double promptTokens) {
        return new Trajectory.Turn(seq, "2026-09-16T10:00:%02d.000Z".formatted(seq % 60), 200, 15.0, null, false, false, false,
                null, (int) promptTokens, 10, null, 5.0, 0.5, "tool_calls", 0, 0,
                List.of(new Trajectory.ToolCall(tool, "{\"path\":\"src/A.java\"}")), false);
    }

    private static Trajectory.Turn turn(final int seq, final String cmd, final Double promptTokens, final Double genTps, final Object status) {
        return new Trajectory.Turn(seq, "2026-09-16T10:00:%02d.000Z".formatted(seq % 60), status, genTps, null, false, false, false,
                null, promptTokens == null ? null : promptTokens.intValue(), 10, null, 5.0, 0.5, "tool_calls", 0, 0,
                List.of(new Trajectory.ToolCall("bash", "{\"command\":\"" + cmd + "\"}")), false);
    }

    @Test
    void identicalNonTestCallsAreThrashButTestReRunsAreDiscipline() {
        List<Trajectory.Turn> turns = List.of(
                turn(1, "cat pom.properties", 100.0, 15.0, 200),
                turn(2, "cat pom.properties", 200.0, 15.0, 200),   // identical non-test call: THRASH
                turn(3, "sed -n 5p Foo.java", 300.0, 15.0, 200),
                turn(4, "./gradlew test -q", 400.0, 15.0, 200),
                turn(5, "./gradlew test -q", 500.0, 15.0, 200));  // a test re-run is NOT thrash (R4 C-13)
        final Map<String, Object> s = Trajectory.analyze(turns, Map.of(), null);
        assertEquals(1, ((Number) s.get("identical_calls_repeated")).intValue());
        assertEquals(2, ((Number) s.get("test_runs")).intValue());
        assertTrue(((List<String>) s.get("flags")).stream().noneMatch(f -> f.startsWith("THRASH")), "one repeat does not flag");
    }

    @Test
    void pingPongOnOneFileIsCounted() {
        List<Trajectory.Turn> turns = List.of(
                turn(1, "sed -n 1p", 100.0, 15.0, 200),
                fileTurn(2, "read", 200.0),
                fileTurn(3, "edit", 300.0),
                fileTurn(4, "read", 400.0));
        final Map<String, Object> s = Trajectory.analyze(turns, Map.of(), null);
        assertTrue(((Number) s.get("edit_read_pingpong")).intValue() >= 1, "edit/read alternation on one file is counted");
        assertEquals(1, ((Map<String, Integer>) s.get("hot_files")).size());   // bash is not a file tool: only src/A.java
    }

    @Test
    void turnsInDockerWindowsAreExcludedFromTheDecodeFloorStatistic() {
        final var inWindow = new Trajectory.Turn(1, "2026-09-16T10:00:00.000Z", 200, 5.0, null, false, false, false, null, 100, 10, null, 1.0, 0.1, "tool_calls", 0, 0, List.of(), false);
        final List<Map<String, Object>> windows = List.of(Map.of("start_iso", "2026-09-16T09:00:00Z", "end_iso", "2026-09-16T11:00:00Z"));
        final Map<String, Object> s = Trajectory.analyze(List.of(inWindow), Map.of("min_decode_tps", 10.0), windows);
        final Map<?, ?> byCtx = (Map<?, ?>) s.get("decode_tps_by_context");
        assertNull(byCtx.get("lt16k"), "the in-window turn is reported apart, not in the outside statistic");
        assertNotNull(s.get("docker_window"));
        assertEquals(1, ((Map<?, ?>) s.get("docker_window")).get("turns"));
    }

    /** A real crash (NullPointerException in java.util.Map.of, live in production): when every turn
     *  inside a docker window errored or was budget-refused before oMLX ever reported a decode speed,
     *  medGenTps legitimately returns null - Map.of is null-hostile and threw. docker_window must
     *  build the same way byCtx (the structurally identical, un-crashed case two lines above it in
     *  Trajectory.analyze) already does. */
    @Test
    void dockerWindowWithNoDecodeSpeedInAnyInWindowTurnDoesNotThrow() {
        final var inWindow = new Trajectory.Turn(1, "2026-09-16T10:00:00.000Z", 429, null, null, true, false, false,
                null, 100, 0, null, 0.0, null, null, 0, 0, List.of(), false);
        final List<Map<String, Object>> windows = List.of(Map.of("start_iso", "2026-09-16T09:00:00Z", "end_iso", "2026-09-16T11:00:00Z"));
        final Map<String, Object> s = Trajectory.analyze(List.of(inWindow), Map.of(), windows);
        final Map<?, ?> dockerWindow = (Map<?, ?>) s.get("docker_window");
        assertNotNull(dockerWindow);
        assertEquals(1, dockerWindow.get("turns"));
        assertNull(dockerWindow.get("decode_tps_median"), "no turn in the window had a genTps to median");
    }

    @Test
    void slowShortContextDecodeFlagsContention() {
        final var slow = List.of(turn(1, "ls", 100.0, 5.0, 200), turn(2, "ls", 200.0, 5.1, 200));
        final Map<String, Object> s = Trajectory.analyze(slow, Map.of("min_decode_tps", 10.0, "usable_context", 65536), null);
        assertTrue(((List<String>) s.get("flags")).stream().anyMatch(f -> f.startsWith("SLOW_DECODE")));
    }

    @Test
    void theObjectiveIndexPenalisesThrashButNotTestReRunsOrBudgetRefusals() {
        final Map<String, Object> s = Map.of("identical_calls_repeated", 6, "edit_read_pingpong", 3, "agent_http_errors", 2);
        Map<String, Object> t3v = new java.util.LinkedHashMap<>();   // green=null: inconclusive, never the agent's fault (Map.of is null-hostile)
        t3v.put("ran", true); t3v.put("green", null); t3v.put("executed", 0); t3v.put("rc", List.of(1));
        Map<String, Object> manifest = Map.of("tasks", List.of(
                Map.of("id", "T1", "over_budget", true),
                Map.of("id", "T2", "reported", "done", "verification", Map.of("ran", true, "green", false, "executed", 5, "rc", List.of(1))),
                Map.of("id", "T3", "reported", "done", "verification", t3v)));
        final var idx = Trajectory.objectiveIndex(s, manifest);
        final Map<String, Integer> pen = idx.getValue();
        assertEquals(12, pen.get("thrash"));   // min(20, 2x6)
        assertEquals(6, pen.get("pingpong"));  // min(10, 2x3)
        assertEquals(2, pen.get("errors"));
        assertEquals(10, pen.get("over_budget_tasks"));
        assertEquals(10, pen.get("unverified_done"));   // T2 claimed done, harness RED; T3 inconclusive
        assertEquals(60, idx.getKey());   // 100 - 40
    }

    @Test
    void toolResultsPairByTaskAcrossInterleavedParallelJournals() {
        // seq->results mapping by task tag: parallel tasks interleave in the merged journal
        List<Map<String, Object>> recs = List.of(
                rec(1, "T1", "out1"),
                rec(2, "T2", "out2"),
                rec(3, "T1", "out3"));
        final Map<Object, List<String>> r = Trajectory.toolResults(recs);
        // a turn's results are visible in the NEXT request of the SAME task: seq1's results arrive with seq3
        assertEquals(List.of("out3"), r.get(1));
        assertNull(r.get(2), "no later T2 request ever carried seq2's results");
    }

    private static Map<String, Object> rec(final int seq, final String task, final String result) {
        return Map.of("seq", seq, "task", task, "path", "/v1/chat/completions",
                "request", Map.of("messages", List.of(Map.of("role", "tool", "content", result))));
    }
}
