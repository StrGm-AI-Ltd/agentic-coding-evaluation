package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #82: the argv -> cfg translation WorkerService.poll() used to build inline, now directly
 *  testable via JobCfgFactory.build() without a live job/worker/DB. */
class JobCfgFactoryTest {

    private static BenchProperties props() {
        return new BenchProperties("default-model", "http://127.0.0.1:9191/v1", "", null, null, null, null, null,
                null, null, "/results", "/workspace", null, null, null, null, null);
    }

    @Test
    void resultsAndWorkspaceRootsAndModelComeFromPropsByDefault() {
        final var cfg = JobCfgFactory.build(List.of("--run-id=r1", "--task=T1"), props());
        assertEquals("/results", cfg.get("results_root"));
        assertEquals("/workspace", cfg.get("workspace_root"));
        assertEquals("default-model", cfg.get("model"));
        assertNull(cfg.get("system_base_url"));
    }

    @Test
    void modelFlagOverridesThePropsDefault() {
        final var cfg = JobCfgFactory.build(List.of("--model=qwen"), props());
        assertEquals("qwen", cfg.get("model"));
    }

    @Test
    void samplerFlagsAreParsedWhenPresentAndAbsentOtherwise() {
        final var withSampler = JobCfgFactory.build(List.of(
                "--temperature=0.7", "--top-p=0.9", "--top-k=40", "--repetition-penalty=1.1",
                "--max-tokens=2000", "--reasoning-effort=high"), props());
        assertEquals(0.7, withSampler.get("temperature"));
        assertEquals(0.9, withSampler.get("top_p"));
        assertEquals(40, withSampler.get("top_k"));
        assertEquals(1.1, withSampler.get("repetition_penalty"));
        assertEquals(2000, withSampler.get("max_tokens_override"));
        assertEquals("high", withSampler.get("reasoning_effort"));

        final var withoutSampler = JobCfgFactory.build(List.of("--run-id=r1"), props());
        for (final var key : List.of("temperature", "top_p", "top_k", "repetition_penalty", "max_tokens_override", "reasoning_effort"))
            assertFalse(withoutSampler.containsKey(key), key + " must be absent, not merely null, when unset");
    }

    @Test
    void wallBudgetFlagsAreParsedWhenPresent() {
        final var cfg = JobCfgFactory.build(List.of(
                "--parallel-plan-wall=100", "--handoff-wall=200", "--wrapup-wall=300"), props());
        assertEquals(100, cfg.get("parallel_plan_wall_sec"));
        assertEquals(200, cfg.get("handoff_wall_sec"));
        assertEquals(300, cfg.get("wrapup_wall_sec"));
    }

    @Test
    void maxTurnsIsParsedWhenPresentAndAbsentOtherwise() {
        assertEquals(50, JobCfgFactory.build(List.of("--max-turns=50"), props()).get("max_turns"));
        assertNull(JobCfgFactory.build(List.of(), props()).get("max_turns"));
    }

    @Test
    void contextProbeDefaultsOnUnlessNoContextProbeOrAPinnedWindowIsGiven() {
        assertEquals(true, JobCfgFactory.build(List.of(), props()).get("context_probe"));
        assertEquals(false, JobCfgFactory.build(List.of("--no-context-probe"), props()).get("context_probe"));
        assertEquals(false, JobCfgFactory.build(List.of("--context-window=32000"), props()).get("context_probe"));
        assertEquals(32000, JobCfgFactory.build(List.of("--context-window=32000"), props()).get("context_window"));
        assertEquals(true, JobCfgFactory.build(List.of("--context-probe-fresh"), props()).get("context_probe_fresh"));
    }

    @Test
    void parallelFlagDistinguishesAutoFromAFixedCount() {
        assertEquals(true, JobCfgFactory.build(List.of("--parallel=auto"), props()).get("parallel_auto"));
        assertEquals(3, JobCfgFactory.build(List.of("--parallel=3"), props()).get("parallel"));
        assertFalse(JobCfgFactory.build(List.of(), props()).containsKey("parallel"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewMapCarriesEnabledModelBlindAndOptionalWallAndWeight() {
        final var cfg = JobCfgFactory.build(List.of(
                "--self-review", "--reviewer-model=judge", "--review-blind",
                "--review-wall-sec=900", "--review-weight=0.2"), props());
        final var review = (Map<String, Object>) cfg.get("review");
        assertEquals(true, review.get("enabled"));
        assertEquals("judge", review.get("model"));
        assertEquals(true, review.get("blind"));
        assertEquals(900, review.get("wall_sec"));
        assertEquals(0.2, review.get("weight"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewMapDefaultsWhenNoReviewFlagsAreGiven() {
        final var review = (Map<String, Object>) JobCfgFactory.build(List.of(), props()).get("review");
        assertEquals(false, review.get("enabled"));
        assertNull(review.get("model"));
        assertEquals(false, review.get("blind"));
        assertFalse(review.containsKey("wall_sec"));
        assertFalse(review.containsKey("weight"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trajectoryReviewMapCarriesEnabledModelWeightAndUse() {
        final var cfg = JobCfgFactory.build(List.of(
                "--trajectory-review", "--trajectory-reviewer-model=judge2",
                "--trajectory-weight=0.3", "--trajectory-use=direct"), props());
        final var trajReview = (Map<String, Object>) cfg.get("trajectory_review");
        assertEquals(true, trajReview.get("enabled"));
        assertEquals("judge2", trajReview.get("model"));
        assertEquals(0.3, trajReview.get("weight"));
        assertEquals("direct", trajReview.get("use"));
    }

    @Test
    void manageDockerReflectsTheFlagsPresence() {
        assertEquals(true, JobCfgFactory.build(List.of("--manage-docker"), props()).get("manage_docker"));
        assertEquals(false, JobCfgFactory.build(List.of(), props()).get("manage_docker"));
    }
}
