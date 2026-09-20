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
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JobSpecs.build("  ", Map.of()));
        assertTrue(e.getMessage().contains("task is required"));
    }

    @Test
    void taskFirstAndValuePreserved() {
        Map<String, Object> spec = JobSpecs.build("L7_full_platform", Map.of("model", "m-1"));
        assertEquals("L7_full_platform", spec.get("task"));
        assertEquals("m-1", spec.get("model"));
        assertEquals("task", spec.keySet().iterator().next(), "task must be the first key");
    }

    @Test
    void blanksAndNullsDropped() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("model", "   ");
        raw.put("reasoning", "");
        raw.put("task_wall", null);
        raw.put("harness", "ref");
        Map<String, Object> spec = JobSpecs.build("L1", raw);
        assertFalse(spec.containsKey("model"));
        assertFalse(spec.containsKey("reasoning"));
        assertFalse(spec.containsKey("task_wall"));
        assertEquals("ref", spec.get("harness"));
    }

    @Test
    void booleanFlagsAlwaysPresent() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("manage_docker", true);
        raw.put("skip_docker", false);
        raw.put("self_review", null); // a null boolean still yields an explicit false, like the service form
        Map<String, Object> spec = JobSpecs.build("L1", raw);
        assertEquals(Boolean.TRUE, spec.get("manage_docker"));
        assertEquals(Boolean.FALSE, spec.get("skip_docker"));
        assertEquals(Boolean.FALSE, spec.get("self_review"));
    }

    @Test
    void typesPreserved() {
        Map<String, Object> raw = Map.of(
                "task_wall", 3600,
                "parallel_weight", 0.5,
                "model", "qwen");
        Map<String, Object> spec = JobSpecs.build("L1", raw);
        assertEquals(Integer.valueOf(3600), spec.get("task_wall"));
        assertEquals(Double.valueOf(0.5), spec.get("parallel_weight"));
        assertEquals("qwen", spec.get("model"));
    }
}
