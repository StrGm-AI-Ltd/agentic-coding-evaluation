package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentParamsTest {

    private static Map<String, Object> common() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("task_wall", 7200);
        raw.put("task_tokens", "auto");
        raw.put("context_window", null);
        raw.put("no_context_probe", true);
        raw.put("reviewer_model", "openai/gpt-5");
        raw.put("review_weight", 0.5);
        raw.put("review_blind", false);
        raw.put("trajectory_reviewer_model", "");
        raw.put("trajectory_weight", null);
        raw.put("trajectory_use", "calibration");
        return raw;
    }

    @Test
    void harnessEffect_defaultsArmsAndParallel() {
        Map<String, Object> raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        Map<String, Object> params = ExperimentParams.build("harness_effect", raw);
        assertEquals("qwen", params.get("model"));
        assertEquals(List.of("orch", "mono"), params.get("arms"), "empty arms default like the service form");
        assertEquals(3, params.get("parallel"));
        assertEquals(7200, params.get("task_wall"));
        assertEquals("auto", params.get("task_tokens"));
        assertTrue((Boolean) params.get("no_context_probe"));
        assertFalse((Boolean) params.get("review_blind"));
        assertEquals("openai/gpt-5", params.get("reviewer_model"));
        assertEquals(0.5, params.get("review_weight"));
        assertEquals("calibration", params.get("trajectory_use"));
        assertFalse(params.containsKey("context_window"), "null context_window is omitted");
        assertFalse(params.containsKey("trajectory_reviewer_model"), "blank model is omitted");
        assertFalse(params.containsKey("trajectory_weight"), "null weight is omitted");
    }

    @Test
    void harnessEffect_keepsCheckedArms() {
        Map<String, Object> raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of("orch", "mono+rules", "par"));
        raw.put("parallel", 5);
        Map<String, Object> params = ExperimentParams.build("harness_effect", raw);
        assertEquals(List.of("orch", "mono+rules", "par"), params.get("arms"));
        assertEquals(5, params.get("parallel"));
    }

    @Test
    void modelAb_needsBothModels() {
        Map<String, Object> raw = common();
        raw.put("model_a", "a");
        raw.put("model_b", " ");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExperimentParams.build("model_ab", raw));
        assertTrue(e.getMessage().contains("model_a and model_b"));
        raw.put("model_b", "b");
        Map<String, Object> params = ExperimentParams.build("model_ab", raw);
        assertEquals("a", params.get("model_a"));
        assertEquals("b", params.get("model_b"));
    }

    @Test
    void agentAb_defaultsModeAndAgents() {
        Map<String, Object> raw = common();
        raw.put("model", "qwen");
        raw.put("mode", null);
        raw.put("agents", null);
        Map<String, Object> params = ExperimentParams.build("agent_ab", raw);
        assertEquals("orchestrated", params.get("mode"));
        assertEquals(List.of("ref", "pi"), params.get("agents"));
        raw.put("mode", "monolithic");
        raw.put("agents", List.of("pi", "ref"));
        params = ExperimentParams.build("agent_ab", raw);
        assertEquals("monolithic", params.get("mode"));
        assertEquals(List.of("pi", "ref"), params.get("agents"));
    }

    @Test
    void modelRequiredForEveryTemplate() {
        for (String template : new String[]{"harness_effect", "agent_ab"}) {
            // model_ab is deliberately absent: it validates model_a/model_b instead of a
            // single model (see modelAb_needsBothModels)
            Map<String, Object> raw = common();
            raw.put("model", null);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ExperimentParams.build(template, raw));
            assertTrue(e.getMessage().contains("model is required"), template);
        }
    }

    @Test
    void taskTokens_autoOrIntOrRejected() {
        assertEquals("auto", ExperimentParams.taskTokens(null));
        assertEquals("auto", ExperimentParams.taskTokens("  "));
        assertEquals("auto", ExperimentParams.taskTokens("auto"));
        assertEquals(5000, ExperimentParams.taskTokens("5000"));
        assertEquals(5000, ExperimentParams.taskTokens(5000));
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.taskTokens("99999999999"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExperimentParams.taskTokens("12a"));
        assertTrue(e.getMessage().contains("task_tokens"));
    }

    @Test
    void taskWallDefaultsAndRejectsGarbage() {
        Map<String, Object> raw = common();
        raw.put("model", "qwen");
        raw.put("task_wall", null);
        assertEquals(3600, ExperimentParams.build("harness_effect", raw).get("task_wall"));
        raw.put("task_wall", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }

    @Test
    void unknownTemplateRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("nope", Map.of()));
    }

    @Test
    void sharedParams_parseStringValuesInsteadOfCasting() {
        Map<String, Object> raw = common();
        raw.put("model", "qwen");
        raw.put("review_weight", "0.25");
        raw.put("review_blind", "true");
        Map<String, Object> params = ExperimentParams.build("harness_effect", raw);
        assertEquals(0.25, params.get("review_weight"));
        assertTrue((Boolean) params.get("review_blind"));
        // unparseable values are bad input: IllegalArgumentException, not ClassCastException
        raw.put("review_weight", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }
}
