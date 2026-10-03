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
                null, null, "/results", "/workspace", null, null, null, null, null, null, null);
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

    /** Found live 2026-10-03: --phases was collected by the UI and emitted by RunSpec.argv(), but
     *  nothing downstream ever read it back out of argv into cfg - same class of bug as #173 below. */
    @Test
    void phasesIsParsedWhenPresentAndAbsentOtherwise() {
        assertEquals("p1_plan,p2_implementation", JobCfgFactory.build(List.of("--phases=p1_plan,p2_implementation"), props()).get("phases"));
        assertFalse(JobCfgFactory.build(List.of(), props()).containsKey("phases"));
    }

    /** #228: 8 more RunSpec flags had nothing downstream reading them back out of argv into cfg either. */
    @Test
    void planTokensAndWallBudgetAreParsedWhenPresentAndAbsentOtherwise() {
        final var with = JobCfgFactory.build(List.of("--plan-tokens=5000", "--wall-budget=300"), props());
        assertEquals(5000L, with.get("plan_tokens"));
        assertEquals(300, with.get("wall_budget_override"));
        final var without = JobCfgFactory.build(List.of(), props());
        assertFalse(without.containsKey("plan_tokens"));
        assertFalse(without.containsKey("wall_budget_override"));
    }

    @Test
    void javaHomeIsParsedWhenPresentAndAbsentOtherwise() {
        assertEquals("/opt/homebrew/opt/openjdk@21", JobCfgFactory.build(List.of("--java-home=/opt/homebrew/opt/openjdk@21"), props()).get("java_home"));
        assertFalse(JobCfgFactory.build(List.of(), props()).containsKey("java_home"));
    }

    @Test
    void keepWorkspaceAndSkipDockerReflectTheFlagsPresence() {
        assertEquals(true, JobCfgFactory.build(List.of("--keep-workspace", "--skip-docker"), props()).get("keep_workspace"));
        assertEquals(true, JobCfgFactory.build(List.of("--keep-workspace", "--skip-docker"), props()).get("skip_docker"));
        assertEquals(false, JobCfgFactory.build(List.of(), props()).get("keep_workspace"));
        assertEquals(false, JobCfgFactory.build(List.of(), props()).get("skip_docker"));
    }

    @Test
    void parallelPlanFlagMapsOnToEnabledAndOffToDisabled() {
        assertEquals(true, JobCfgFactory.build(List.of("--parallel-plan=on"), props()).get("parallel_plan_enabled"));
        assertEquals(false, JobCfgFactory.build(List.of("--parallel-plan=off"), props()).get("parallel_plan_enabled"));
        assertFalse(JobCfgFactory.build(List.of(), props()).containsKey("parallel_plan_enabled"), "unset must stay absent so RunBench's own default (true) applies");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parallelWeightIsCarriedInAParallelPlanMap() {
        final var cfg = JobCfgFactory.build(List.of("--parallel-weight=0.2"), props());
        final var parallelPlan = (Map<String, Object>) cfg.get("parallel_plan");
        assertEquals(0.2, parallelPlan.get("weight"));
        assertFalse(JobCfgFactory.build(List.of(), props()).containsKey("parallel_plan"));
    }

    /** #173: --impl-wall/--impl-tokens were computed by ExperimentsService and placed on the job's
     *  own argv by RunSpec, but nothing downstream ever read them back out of argv into cfg - this
     *  pins that JobCfgFactory.build() now does. */
    @Test
    void implWallAndImplTokensAreParsedWhenPresentAndAbsentOtherwise() {
        final var withImpl = JobCfgFactory.build(List.of("--impl-wall=3600", "--impl-tokens=50000"), props());
        assertEquals(3600, withImpl.get("impl_wall_sec"));
        assertEquals(50000L, withImpl.get("impl_tokens"));

        final var withoutImpl = JobCfgFactory.build(List.of("--run-id=r1"), props());
        assertFalse(withoutImpl.containsKey("impl_wall_sec"), "must be absent, not merely null, when unset");
        assertFalse(withoutImpl.containsKey("impl_tokens"));
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

    /** Found live 2026-09-27: the FIX step (merge-conflict repair after a broken parallel wave) was
     *  a fixed literal (900s/20000 tokens) with no run-level control at all. */
    @Test
    void fixBudgetFlagsAreParsedWhenPresentAndAbsentOtherwise() {
        final var cfg = JobCfgFactory.build(List.of("--fix-wall=1200", "--fix-tokens=30000"), props());
        assertEquals(1200, cfg.get("fix_wall_sec"));
        assertEquals(30000, cfg.get("fix_tokens"));

        final var without = JobCfgFactory.build(List.of(), props());
        assertFalse(without.containsKey("fix_wall_sec"));
        assertFalse(without.containsKey("fix_tokens"));
    }

    /** Found live 2026-09-27: the PARALLEL_PLAN/handoff/wrap-up TOKEN budgets (unlike their walls,
     *  fixed in PR #153) were still fixed literals (8000/3000/2000) with no run-level control. */
    @Test
    void newTokenBudgetFlagsAreParsedWhenPresentAndAbsentOtherwise() {
        final var cfg = JobCfgFactory.build(List.of(
                "--parallel-plan-tokens=9000", "--handoff-tokens=4000", "--wrapup-tokens=2500"), props());
        assertEquals(9000, cfg.get("parallel_plan_tokens"));
        assertEquals(4000, cfg.get("handoff_tokens"));
        assertEquals(2500, cfg.get("wrapup_tokens"));

        final var without = JobCfgFactory.build(List.of(), props());
        assertFalse(without.containsKey("parallel_plan_tokens"));
        assertFalse(without.containsKey("handoff_tokens"));
        assertFalse(without.containsKey("wrapup_tokens"));
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
                "--review-wall-sec=900", "--review-tokens=5000", "--review-weight=0.2"), props());
        final var review = (Map<String, Object>) cfg.get("review");
        assertEquals(true, review.get("enabled"));
        assertEquals("judge", review.get("model"));
        assertEquals(true, review.get("blind"));
        assertEquals(900, review.get("wall_sec"));
        assertEquals(5000, review.get("tokens"));
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
        assertFalse(review.containsKey("tokens"));
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

    /** Found live 2026-09-28: capping Docker Desktop's VM memory and keeping it warm for the whole
     *  run had no run-level control at all. */
    @Test
    void dockerMemoryCapAndKeepWarmAreParsedWhenPresentAndAbsentOtherwise() {
        final var cfg = JobCfgFactory.build(List.of("--docker-memory-mib=6144", "--docker-keep-warm"), props());
        assertEquals(6144, cfg.get("docker_memory_mib"));
        assertEquals(true, cfg.get("docker_keep_warm"));

        final var without = JobCfgFactory.build(List.of(), props());
        assertFalse(without.containsKey("docker_memory_mib"));
        assertEquals(false, without.get("docker_keep_warm"));
    }
}
