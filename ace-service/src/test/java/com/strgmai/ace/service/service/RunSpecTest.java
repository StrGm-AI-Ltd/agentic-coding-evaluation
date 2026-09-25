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
                parallelPlanWall, handoffWall, wrapupWall);
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
}
