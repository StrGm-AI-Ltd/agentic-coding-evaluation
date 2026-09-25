package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentParamsTest {

    private static Map<String, Object> common() {
        // returned as Map<String, Object>; empty-diamond under var would infer <Object, Object>
        final Map<String, Object> raw = new java.util.LinkedHashMap<>();
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
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        final var params = ExperimentParams.build("harness_effect", raw);
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

    /** #72: shared by every template, same as review_weight/trajectory_use above. */
    @Test
    void samplerParamsPassThroughWhenSet() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        raw.put("temperature", 0.7);
        raw.put("top_p", 0.9);
        raw.put("top_k", 40);
        raw.put("repetition_penalty", 1.1);
        raw.put("max_tokens", 2000);
        raw.put("reasoning_effort", "high");
        final var params = ExperimentParams.build("harness_effect", raw);
        assertEquals(0.7, params.get("temperature"));
        assertEquals(0.9, params.get("top_p"));
        assertEquals(40, params.get("top_k"));
        assertEquals(1.1, params.get("repetition_penalty"));
        assertEquals(2000, params.get("max_tokens"));
        assertEquals("high", params.get("reasoning_effort"));
    }

    @Test
    void samplerParamsOmittedWhenUnset() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        final var params = ExperimentParams.build("harness_effect", raw);
        for (final var key : List.of("temperature", "top_p", "top_k", "repetition_penalty", "max_tokens", "reasoning_effort")) {
            assertFalse(params.containsKey(key), key + " must be omitted, not sent as null");
        }
    }

    /** Found live 2026-09-25: PARALLEL_PLAN/handoff/wrap-up walls were fixed literals in
     *  RunBench.java with no run-level control - shared by every template, same as the sampler knobs. */
    @Test
    void newWallParamsPassThroughWhenSet() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        raw.put("parallel_plan_wall", 900);
        raw.put("handoff_wall", 450);
        raw.put("wrapup_wall", 600);
        final var params = ExperimentParams.build("harness_effect", raw);
        assertEquals(900, params.get("parallel_plan_wall"));
        assertEquals(450, params.get("handoff_wall"));
        assertEquals(600, params.get("wrapup_wall"));
    }

    @Test
    void newWallParamsOmittedWhenUnset() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of());
        raw.put("parallel", null);
        final var params = ExperimentParams.build("harness_effect", raw);
        for (final var key : List.of("parallel_plan_wall", "handoff_wall", "wrapup_wall")) {
            assertFalse(params.containsKey(key), key + " must be omitted, not sent as null");
        }
    }

    @Test
    void harnessEffect_keepsCheckedArms() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("arms", List.of("orch", "mono+rules", "par"));
        raw.put("parallel", 5);
        final var params = ExperimentParams.build("harness_effect", raw);
        assertEquals(List.of("orch", "mono+rules", "par"), params.get("arms"));
        assertEquals(5, params.get("parallel"));
    }

    @Test
    void modelAb_needsBothModels() {
        final var raw = common();
        raw.put("model_a", "a");
        raw.put("model_b", " ");
        final var e = assertThrows(IllegalArgumentException.class,
                () -> ExperimentParams.build("model_ab", raw));
        assertTrue(e.getMessage().contains("model_a and model_b"));
        raw.put("model_b", "b");
        final var params = ExperimentParams.build("model_ab", raw);
        assertEquals("a", params.get("model_a"));
        assertEquals("b", params.get("model_b"));
    }

    @Test
    void agentAb_defaultsModeAndAgents() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("mode", null);
        raw.put("agents", null);
        var params = ExperimentParams.build("agent_ab", raw);   // reassigned below - not final
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
        for (final var template : new String[]{"harness_effect", "agent_ab"}) {
            // model_ab is deliberately absent: it validates model_a/model_b instead of a
            // single model (see modelAb_needsBothModels)
            final var raw = common();
            raw.put("model", null);
            final var e = assertThrows(IllegalArgumentException.class,
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
        final var e = assertThrows(IllegalArgumentException.class,
                () -> ExperimentParams.taskTokens("12a"));
        assertTrue(e.getMessage().contains("task_tokens"));
    }

    @Test
    void taskWallDefaultsAndRejectsGarbage() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("task_wall", null);
        assertEquals(3600, ExperimentParams.build("harness_effect", raw).get("task_wall"));
        raw.put("task_wall", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }

    @Test
    void firstTokenTimeoutDefaultsAndRejectsGarbage() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("first_token_timeout", null);
        assertEquals(180, ExperimentParams.build("harness_effect", raw).get("first_token_timeout"));
        raw.put("first_token_timeout", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }

    @Test
    void compactionTriggerOmittedWhenUnsetZeroMeansDisabled() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("compaction_trigger", null);
        assertFalse(ExperimentParams.build("harness_effect", raw).containsKey("compaction_trigger"), "unset leaves the operator default in place");
        raw.put("compaction_trigger", 0);
        assertEquals(0, ExperimentParams.build("harness_effect", raw).get("compaction_trigger"), "0 disables compaction, not treated as blank");
        raw.put("compaction_trigger", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }

    @Test
    void unknownTemplateRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("nope", Map.of()));
    }

    @Test
    void sharedParams_parseStringValuesInsteadOfCasting() {
        final var raw = common();
        raw.put("model", "qwen");
        raw.put("review_weight", "0.25");
        raw.put("review_blind", "true");
        final var params = ExperimentParams.build("harness_effect", raw);
        assertEquals(0.25, params.get("review_weight"));
        assertTrue((Boolean) params.get("review_blind"));
        // unparseable values are bad input: IllegalArgumentException, not ClassCastException
        raw.put("review_weight", "abc");
        assertThrows(IllegalArgumentException.class, () -> ExperimentParams.build("harness_effect", raw));
    }
}
