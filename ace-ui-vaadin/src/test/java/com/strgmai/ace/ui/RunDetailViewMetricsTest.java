package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extracted metrics-row builders (T-7) and the comparison cards (M3). */
class RunDetailViewMetricsTest {

    private static final String METRICS = """
            {"per_task": {
               "T2": {"reported": "ok", "done_verified": true, "requests": 10,
                      "completion_tokens": 100, "max_prompt": 2000, "task_wall_sec": 61.0,
                      "files_changed": 2, "over_budget": false},
               "T1": {"done_verified": false, "requests": 3, "files_changed": "many"}
             },
             "steps": {
               "definition": {"score_pct": 100.0},
               "plan": {"score_pct": 66.7, "measured": false}
             }}""";

    @Test
    void perTaskRows_mapsAllFieldsSortedWithFallbacks() {
        final var rows =
                RunDetailView.perTaskRows(Json.MAPPER.readTree(METRICS));
        assertEquals(List.of("T1", "T2"), rows.stream().map(RunDetailView.PerTaskRow::tid).toList(),
                "tasks sort by id");

        final var t2 = rows.get(1);
        assertEquals("ok", t2.reported());
        assertEquals("true", t2.doneVerified());
        assertEquals(10L, t2.requests());
        assertEquals(100L, t2.tokens());
        assertEquals(2000L, t2.maxPrompt());
        assertEquals(61.0, t2.wall());
        assertEquals("2", t2.filesChanged());
        assertEquals("", t2.overBudget());

        final var t1 = rows.get(0);
        assertEquals("–", t1.reported(), "missing reported falls back");
        assertEquals("false", t1.doneVerified());
        assertEquals("–", t1.filesChanged(), "non-number files_changed falls back");
        assertEquals(null, t1.tokens());
        assertEquals(null, t1.maxPrompt());
    }

    @Test
    void perTaskRows_degenerateMetrics() {
        assertEquals(List.of(), RunDetailView.perTaskRows(null));
        assertEquals(List.of(), RunDetailView.perTaskRows(Json.MAPPER.readTree("{}")));
        assertEquals(List.of(), RunDetailView.perTaskRows(
                Json.MAPPER.readTree("{\"per_task\": {}}")));
    }

    @Test
    void stepRows_measureFlags() {
        final var rows = RunDetailView.stepRows(Json.MAPPER.readTree(METRICS));
        assertEquals(2, rows.size());
        assertEquals("definition", rows.get(0).sid());
        assertEquals(100.0, rows.get(0).scorePct());
        assertTrue(rows.get(0).measured(), "measured defaults to true");
        assertEquals(false, rows.get(1).measured());
        assertEquals(66.7, rows.get(1).scorePct());
    }

    @Test
    void stepRows_degenerateMetrics() {
        assertEquals(List.of(), RunDetailView.stepRows(null));
        assertEquals(List.of(), RunDetailView.stepRows(Json.MAPPER.readTree("{\"steps\": []}")));
    }
}
