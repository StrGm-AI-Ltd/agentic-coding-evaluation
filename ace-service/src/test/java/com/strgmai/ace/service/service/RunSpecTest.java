package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #72: temperature/top_p/top_k/repetition_penalty/max_tokens/reasoning_effort as adjustable
 *  per-run params, threaded through argv() the same way every other RunSpec flag already is. */
class RunSpecTest {

    private static RunSpec base(final Double temperature, final Double topP, final Integer topK,
                                 final Double repetitionPenalty, final Integer maxTokens, final String reasoningEffort) {
        return walls(temperature, topP, topK, repetitionPenalty, maxTokens, reasoningEffort, null, null, null);
    }

    private static RunSpec walls(final Double temperature, final Double topP, final Integer topK,
                                  final Double repetitionPenalty, final Integer maxTokens, final String reasoningEffort,
                                  final Integer parallelPlanWall, final Integer handoffWall, final Integer wrapupWall) {
        return new RunSpec("L3p_point_in_time", "m", null, "monolithic", "agent", 3600, null, null, null, null,
                false, false, false, null, false, true, false, false, null, null, null, null, false, null, null, null,
                null, "r1", temperature, topP, topK, repetitionPenalty, maxTokens, reasoningEffort,
                parallelPlanWall, handoffWall, wrapupWall, null);
    }

    @Test
    void argvIncludesEverySamplerFlagWhenSet() {
        final var spec = base(0.7, 0.9, 40, 1.1, 2000, "high");
        final var argv = spec.argv("r1");
        assertTrue(argv.contains("--temperature=0.7"));
        assertTrue(argv.contains("--top-p=0.9"));
        assertTrue(argv.contains("--top-k=40"));
        assertTrue(argv.contains("--repetition-penalty=1.1"));
        assertTrue(argv.contains("--max-tokens=2000"));
        assertTrue(argv.contains("--reasoning-effort=high"));
    }

    @Test
    void argvOmitsEverySamplerFlagWhenUnset() {
        final var argv = base(null, null, null, null, null, null).argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--temperature")
                || a.startsWith("--top-p") || a.startsWith("--top-k") || a.startsWith("--repetition-penalty")
                || a.startsWith("--max-tokens") || a.startsWith("--reasoning-effort")));
    }

    @Test
    void reasoningEffortSupportsDisablingThinkingViaNone() {
        assertDoesNotThrow(() -> base(null, null, null, null, null, "none"));
        assertTrue(base(null, null, null, null, null, "none").argv("r1").contains("--reasoning-effort=none"));
    }

    @Test
    void negativeTemperatureIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(-0.1, null, null, null, null, null));
    }

    @Test
    void topPOutsideZeroToOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(null, 0.0, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> base(null, 1.1, null, null, null, null));
        assertDoesNotThrow(() -> base(null, 1.0, null, null, null, null));
    }

    @Test
    void topKBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(null, null, 0, null, null, null));
    }

    @Test
    void negativeRepetitionPenaltyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(null, null, null, -0.5, null, null));
    }

    @Test
    void nonPositiveMaxTokensIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> base(null, null, null, null, -100, null));
    }

    @Test
    void unrecognisedReasoningEffortIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> base(null, null, null, null, null, "extreme"));
    }

    /** Found live 2026-09-25: PARALLEL_PLAN/handoff/wrap-up walls were fixed literals in
     *  RunBench.java (600/300/300*scale) with no run-level control at all. */
    @Test
    void argvIncludesTheNewWallFlagsWhenSet() {
        final var argv = walls(null, null, null, null, null, null, 900, 450, 600).argv("r1");
        assertTrue(argv.contains("--parallel-plan-wall=900"));
        assertTrue(argv.contains("--handoff-wall=450"));
        assertTrue(argv.contains("--wrapup-wall=600"));
    }

    @Test
    void argvOmitsTheNewWallFlagsWhenUnset() {
        final var argv = base(null, null, null, null, null, null).argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--parallel-plan-wall")
                || a.startsWith("--handoff-wall") || a.startsWith("--wrapup-wall")));
    }

    @Test
    void nonPositiveNewWallsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, null, -1, null));
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, null, null, 0));
    }

    /** #95: MAX_TURNS was a ReferenceAgent-local hardcoded constant with no run-level override at all. */
    @Test
    void argvIncludesMaxTurnsWhenSet() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").maxTurns(50).build().argv("r1");
        assertTrue(argv.contains("--max-turns=50"));
    }

    @Test
    void argvOmitsMaxTurnsWhenUnset() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").build().argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--max-turns")));
    }

    @Test
    void nonPositiveMaxTurnsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RunSpec.builder().task("t").model("m").runId("r1").maxTurns(0).build());
    }

    /** #77: every field set to a DISTINCT, recognizable value via the map, so a from()/Builder bug
     *  that transposes two same-typed fields (the exact class of bug from() exists to make impossible)
     *  fails this test on the specific field it mixed up, not just on "something changed". */
    @Test
    void fromMapPutsEveryFieldInItsOwnNamedSlot() {
        final var spec = new java.util.LinkedHashMap<String, Object>();
        spec.put("task", "L3p_point_in_time"); spec.put("model", "m1"); spec.put("harness", "ref");
        spec.put("mode", "orchestrated"); spec.put("plan_source", "agent");
        spec.put("task_wall", 111); spec.put("task_tokens", 222); spec.put("impl_wall", 333); spec.put("impl_tokens", 444);
        spec.put("parallel", "3"); spec.put("system_rules", true); spec.put("self_review", true);
        spec.put("trajectory_review", true); spec.put("reviewer_model", "reviewer-m");
        spec.put("handoff_notes", true); spec.put("manage_docker", false);
        spec.put("no_context_probe", true); spec.put("context_probe_fresh", true);
        spec.put("context_window", 555); spec.put("first_token_timeout", 666);
        spec.put("compaction_trigger", 777); spec.put("review_wall_sec", 888);
        spec.put("review_blind", true); spec.put("trajectory_reviewer_model", "traj-reviewer-m");
        spec.put("review_weight", 0.11); spec.put("trajectory_weight", 0.22);
        spec.put("trajectory_use", "direct"); spec.put("run_id", "run-xyz");
        spec.put("temperature", 0.33); spec.put("top_p", 0.44); spec.put("top_k", 12);
        spec.put("repetition_penalty", 1.23); spec.put("max_tokens", 999);
        spec.put("reasoning_effort", "high");
        spec.put("parallel_plan_wall", 100); spec.put("handoff_wall", 200); spec.put("wrapup_wall", 300);

        final var rs = RunSpec.from(spec);

        assertEquals("L3p_point_in_time", rs.task());
        assertEquals("m1", rs.model());
        assertEquals("ref", rs.harness());
        assertEquals("orchestrated", rs.mode());
        assertEquals("agent", rs.planSource());
        assertEquals(111, rs.taskWall());
        assertEquals(222, rs.taskTokens());
        assertEquals(333, rs.implWall());
        assertEquals(444, rs.implTokens());
        assertEquals("3", rs.parallel());
        assertTrue(rs.systemRules());
        assertTrue(rs.selfReview());
        assertTrue(rs.trajectoryReview());
        assertEquals("reviewer-m", rs.reviewerModel());
        assertTrue(rs.handoffNotes());
        assertFalse(rs.manageDocker());
        assertTrue(rs.noContextProbe());
        assertTrue(rs.contextProbeFresh());
        assertEquals(555, rs.contextWindow());
        assertEquals(666, rs.firstTokenTimeout());
        assertEquals(777, rs.compactionTrigger());
        assertEquals(888, rs.reviewWallSec());
        assertTrue(rs.reviewBlind());
        assertEquals("traj-reviewer-m", rs.trajectoryReviewerModel());
        assertEquals(0.11, rs.reviewWeight());
        assertEquals(0.22, rs.trajectoryWeight());
        assertEquals("direct", rs.trajectoryUse());
        assertEquals("run-xyz", rs.runId());
        assertEquals(0.33, rs.temperature());
        assertEquals(0.44, rs.topP());
        assertEquals(12, rs.topK());
        assertEquals(1.23, rs.repetitionPenalty());
        assertEquals(999, rs.maxTokens());
        assertEquals("high", rs.reasoningEffort());
        assertEquals(100, rs.parallelPlanWall());
        assertEquals(200, rs.handoffWall());
        assertEquals(300, rs.wrapupWall());
    }

    @Test
    void fromMapAppliesTheSameDefaultsBenchControllerUsedToApplyInline() {
        final var spec = java.util.Map.<String, Object>of("task", "L3p_point_in_time");
        final var rs = RunSpec.from(spec);
        assertTrue(rs.manageDocker(), "manage_docker defaults to true, same as the old inline extraction");
        assertFalse(rs.systemRules());
        assertFalse(rs.selfReview());
        assertNull(rs.model());
        assertNull(rs.taskWall());
    }

    @Test
    void builderProducesTheSameRunSpecAsThePositionalConstructor() {
        final var viaBuilder = RunSpec.builder().task("t").model("m").runId("r1").temperature(0.5).build();
        final var viaConstructor = new RunSpec("t", "m", null, null, null, null, null, null, null, null,
                false, false, false, null, false, false, false, false, null, null, null, null, false, null, null, null,
                null, "r1", 0.5, null, null, null, null, null, null, null, null, null);
        assertEquals(viaConstructor, viaBuilder);
    }
}
