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
        return new RunSpec("L3p_point_in_time", "m", null, "monolithic", "agent", 3600, null, null, null, null, false, false, false, null, false, true, false, false, null, null, null, null, false, null, null, null, null, "r1", temperature, topP, topK, repetitionPenalty, maxTokens, reasoningEffort, parallelPlanWall, handoffWall, wrapupWall, null, null, null, null, null, null, null, null, false);
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
    void negativeNewWallsAreRejectedButZeroIsUnlimited() {
        // 0 means unlimited (the system-wide "0 = no budget" convention) - only negative is an error
        assertDoesNotThrow(() -> walls(null, null, null, null, null, null, 0, null, null));
        assertDoesNotThrow(() -> walls(null, null, null, null, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, null, -1, null));
        assertThrows(IllegalArgumentException.class, () -> walls(null, null, null, null, null, null, null, null, -1));
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

    /** #90: self-review's token budget was a Reviews.java-local hardcoded 12000 with no
     *  --review-tokens flag at all; 0 means unlimited (the system-wide "0 = no budget" convention). */
    @Test
    void argvIncludesReviewTokensWhenSet() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").reviewTokens(5000).build().argv("r1");
        assertTrue(argv.contains("--review-tokens=5000"));
    }

    @Test
    void argvOmitsReviewTokensWhenUnset() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").build().argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--review-tokens")));
    }

    @Test
    void negativeReviewTokensIsRejectedButZeroIsUnlimited() {
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").reviewTokens(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> RunSpec.builder().task("t").model("m").runId("r1").reviewTokens(-1).build());
    }

    /** Found live 2026-09-27: the FIX step (merge-conflict repair after a broken parallel wave) was
     *  a fixed literal (900s/20000 tokens) with no run-level control at all. */
    @Test
    void argvIncludesFixBudgetWhenSet() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").fixWall(1200).fixTokens(30000).build().argv("r1");
        assertTrue(argv.contains("--fix-wall=1200"));
        assertTrue(argv.contains("--fix-tokens=30000"));
    }

    @Test
    void argvOmitsFixBudgetWhenUnset() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").build().argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--fix-wall") || a.startsWith("--fix-tokens")));
    }

    @Test
    void negativeFixBudgetIsRejectedButZeroIsUnlimited() {
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").fixWall(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").fixTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").fixWall(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").fixTokens(-1).build());
    }

    /** Found live 2026-09-27: the PARALLEL_PLAN/handoff/wrap-up TOKEN budgets (unlike their walls,
     *  fixed in PR #153) were still fixed literals (8000/3000/2000) with no run-level control. */
    @Test
    void argvIncludesTheNewTokenBudgetsWhenSet() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1")
                .parallelPlanTokens(9000).handoffTokens(4000).wrapupTokens(2500).build().argv("r1");
        assertTrue(argv.contains("--parallel-plan-tokens=9000"));
        assertTrue(argv.contains("--handoff-tokens=4000"));
        assertTrue(argv.contains("--wrapup-tokens=2500"));
    }

    @Test
    void argvOmitsTheNewTokenBudgetsWhenUnset() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").build().argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--parallel-plan-tokens")
                || a.startsWith("--handoff-tokens") || a.startsWith("--wrapup-tokens")));
    }

    @Test
    void negativeNewTokenBudgetsAreRejectedButZeroIsUnlimited() {
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").parallelPlanTokens(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").handoffTokens(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").wrapupTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").parallelPlanTokens(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").handoffTokens(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").wrapupTokens(-1).build());
    }

    /** every wall/token budget - not just the ones with their own dedicated tests above - accepts 0
     *  as unlimited (the system-wide "0 = no budget" convention) and rejects only negative. */
    @Test
    void everyBudgetFieldAcceptsZeroAndRejectsNegative() {
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").taskWall(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").taskTokens(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").firstTokenTimeout(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").reviewWallSec(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").fixWall(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").fixTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").taskWall(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").taskTokens(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").firstTokenTimeout(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").reviewWallSec(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").fixWall(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").fixTokens(-1).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").parallelPlanTokens(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").handoffTokens(0).build());
        assertDoesNotThrow(() -> RunSpec.builder().task("t").model("m").runId("r1").wrapupTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").parallelPlanTokens(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").handoffTokens(-1).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").wrapupTokens(-1).build());
    }

    /** implWall/implTokens/contextWindow/maxTokens are capacities, not spending budgets - a 0
     *  generation or a 0-wide window is meaningless, so they still reject 0 (unlike every field above). */
    @Test
    void capacityFieldsStillRejectZero() {
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").implWall(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").implTokens(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").contextWindow(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").maxTokens(0).build());
        // dockerMemoryMib is a capacity too - a 0-MiB VM is just as meaningless
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").dockerMemoryMib(0).build());
        assertThrows(IllegalArgumentException.class, () -> RunSpec.builder().task("t").model("m").runId("r1").dockerMemoryMib(-1).build());
    }

    /** Found live 2026-09-28: capping Docker Desktop's VM memory (its VM competing with the model
     *  for memory at the worst possible moment is the actual root cause behind "Docker Desktop
     *  unreliable during the benchmark window") and keeping it warm for the whole run (skipping the
     *  idle monitor's mid-run stop/restart cycling) had no run-level control at all. */
    @Test
    void argvIncludesDockerMemoryCapAndKeepWarmWhenSet() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").dockerMemoryMib(4096).dockerKeepWarm(true).build().argv("r1");
        assertTrue(argv.contains("--docker-memory-mib=4096"));
        assertTrue(argv.contains("--docker-keep-warm"));
    }

    @Test
    void argvOmitsDockerMemoryCapAndKeepWarmWhenUnset() {
        final var argv = RunSpec.builder().task("t").model("m").runId("r1").build().argv("r1");
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("--docker-memory-mib") || a.equals("--docker-keep-warm")));
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
        spec.put("compaction_trigger", 777); spec.put("review_wall_sec", 888); spec.put("review_tokens", 8880);
        spec.put("review_blind", true); spec.put("trajectory_reviewer_model", "traj-reviewer-m");
        spec.put("review_weight", 0.11); spec.put("trajectory_weight", 0.22);
        spec.put("trajectory_use", "direct"); spec.put("run_id", "run-xyz");
        spec.put("temperature", 0.33); spec.put("top_p", 0.44); spec.put("top_k", 12);
        spec.put("repetition_penalty", 1.23); spec.put("max_tokens", 999);
        spec.put("reasoning_effort", "high");
        spec.put("parallel_plan_wall", 100); spec.put("handoff_wall", 200); spec.put("wrapup_wall", 300);
        spec.put("fix_wall", 1200); spec.put("fix_tokens", 30000);
        spec.put("parallel_plan_tokens", 9000); spec.put("handoff_tokens", 4000); spec.put("wrapup_tokens", 2500);
        spec.put("docker_memory_mib", 6144); spec.put("docker_keep_warm", true);

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
        assertEquals(8880, rs.reviewTokens());
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
        assertEquals(1200, rs.fixWall());
        assertEquals(30000, rs.fixTokens());
        assertEquals(9000, rs.parallelPlanTokens());
        assertEquals(4000, rs.handoffTokens());
        assertEquals(2500, rs.wrapupTokens());
        assertEquals(6144, rs.dockerMemoryMib());
        assertTrue(rs.dockerKeepWarm());
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
        final var viaConstructor = new RunSpec("t", "m", null, null, null, null, null, null, null, null, false, false, false, null, false, false, false, false, null, null, null, null, false, null, null, null, null, "r1", 0.5, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
        assertEquals(viaConstructor, viaBuilder);
    }
}
