package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSpecsTest {

    @Test
    void blankTaskRejected() {
        final var e = assertThrows(IllegalArgumentException.class,
                () -> JobSpecs.build("  ", Map.of()));
        assertTrue(e.getMessage().contains("task is required"));
    }

    @Test
    void taskFirstAndValuePreserved() {
        final var spec = JobSpecs.build("L7_full_platform", Map.of("model", "m-1"));
        assertEquals("L7_full_platform", spec.get("task"));
        assertEquals("m-1", spec.get("model"));
        assertEquals("task", spec.keySet().iterator().next(), "task must be the first key");
    }

    @Test
    void blanksAndNullsDropped() {
        final var raw = new LinkedHashMap<String, Object>();
        raw.put("model", "   ");
        raw.put("reasoning", "");
        raw.put("task_wall", null);
        raw.put("harness", "ref");
        final var spec = JobSpecs.build("L1", raw);
        assertFalse(spec.containsKey("model"));
        assertFalse(spec.containsKey("reasoning"));
        assertFalse(spec.containsKey("task_wall"));
        assertEquals("ref", spec.get("harness"));
    }

    @Test
    void booleanFlagsAlwaysPresent() {
        final var raw = new LinkedHashMap<String, Object>();
        raw.put("handoff_notes", true);
        raw.put("system_rules", false);
        raw.put("self_review", null); // a null boolean still yields an explicit false, like the service form
        raw.put("review_blind", true);
        raw.put("judge_enabled", false);
        final var spec = JobSpecs.build("L1", raw);
        assertEquals(Boolean.TRUE, spec.get("handoff_notes"));
        assertEquals(Boolean.FALSE, spec.get("system_rules"));
        assertEquals(Boolean.FALSE, spec.get("self_review"));
        assertEquals(Boolean.TRUE, spec.get("review_blind"));
        assertEquals(Boolean.FALSE, spec.get("judge_enabled"));
    }

    /** #269: judge_* exist on RunSpec/JobCfgFactory, but JobNewView had no fields for them at all -
     *  a fresh job could never have the mid-task judge enabled from turn 1 of its first task. */
    @Test
    void judgeFieldsFlowThrough() {
        final var raw = new LinkedHashMap<String, Object>();
        raw.put("judge_enabled", true);
        raw.put("judge_every_n_turns", 7);
        raw.put("judge_model", "openai/gpt-4");
        raw.put("judge_wall_sec", 120);
        raw.put("judge_tokens", 4000);
        final var spec = JobSpecs.build("L1", raw);
        assertEquals(Boolean.TRUE, spec.get("judge_enabled"));
        assertEquals(Integer.valueOf(7), spec.get("judge_every_n_turns"));
        assertEquals("openai/gpt-4", spec.get("judge_model"));
        assertEquals(Integer.valueOf(120), spec.get("judge_wall_sec"));
        assertEquals(Integer.valueOf(4000), spec.get("judge_tokens"));
    }

    @Test
    void typesPreserved() {
        final Map<String, Object> raw = Map.of(
                "task_wall", 3600,
                "parallel_weight", 0.5,
                "model", "qwen");
        final var spec = JobSpecs.build("L1", raw);
        assertEquals(Integer.valueOf(3600), spec.get("task_wall"));
        assertEquals(Double.valueOf(0.5), spec.get("parallel_weight"));
        assertEquals("qwen", spec.get("model"));
    }
}
