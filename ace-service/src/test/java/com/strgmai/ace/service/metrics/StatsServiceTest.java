package com.strgmai.ace.service.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ports of the stats tests: bootstrap CI sanity, permutation-test determinism, the comparability
 *  refusals (a differing key component is named; the budget match is required for a harness A/B). */
class StatsServiceTest {
    private final StatsService stats = new StatsService();

    @Test
    void permutationTestIsDeterministicAndOneSided() {
        double[] a = {80.0, 82.0, 79.0, 81.0, 83.0}, b = {60.0, 62.0, 58.0, 61.0, 59.0};
        final double p1 = StatsService.permutationTest(a, b, 5000, 0);
        final double p2 = StatsService.permutationTest(a, b, 5000, 0);
        assertEquals(p1, p2);              // seeded: reproducible
        assertTrue(p1 < 0.05, "A consistently >> B must give a small p, got " + p1);
        final double pNull = StatsService.permutationTest(a, a.clone(), 5000, 0);
        assertTrue(pNull > 0.3, "A vs itself must give a large p, got " + pNull);
    }

    @Test
    void bootCiBracketsTheMeanAndStaysInsideTheDataRange() {
        final var xs = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0);
        final double[] ci = StatsService.bootCi(xs, 2000, 0.10, 0);
        final double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertTrue(ci[0] <= mean && ci[1] >= mean, "the CI must bracket the mean " + mean + ": [" + ci[0] + ", " + ci[1] + "]");
        assertTrue(ci[0] >= 10.0 && ci[1] <= 80.0, "a bootstrap mean cannot leave the data range");
        assertTrue(ci[0] < ci[1]);
    }

    private StatsService.RunSummary run(final String model, final String mode, final String registry, final int wall, final int tokens, double functional, final boolean valid) {
        return run(model, mode, registry, wall, tokens, functional, valid, Map.of());
    }

    private StatsService.RunSummary run(String model, String mode, String registry, int wall, int tokens, double functional, boolean valid,
                                        Map<String, String> checkStatus) {
        return new StatsService.RunSummary("results/x", "L7_full_platform", model, mode, functional, functional, null, null, valid,
                List.of(), false, true, wall, tokens,
                java.util.Map.ofEntries(
                        java.util.Map.entry("task", "L7_full_platform"), java.util.Map.entry("registry", registry),
                        java.util.Map.entry("run_oracle", "o1"), java.util.Map.entry("contract", "c1"),
                        java.util.Map.entry("task_prompt", "p1"), java.util.Map.entry("mode", mode),
                        java.util.Map.entry("plan_source", "agent"), java.util.Map.entry("harness_version", "jls-ref-1.0"),
                        java.util.Map.entry("budgets_wall", String.valueOf(wall)), java.util.Map.entry("budgets_tokens", String.valueOf(tokens)),
                        java.util.Map.entry("sampler", "s1"), java.util.Map.entry("system_prompt_sha", "sp1")), List.of(), checkStatus);
    }

    @Test
    void runsDifferingInAKeyComponentAreRefusedWithTheCulpritNamed() {
        List<StatsService.RunSummary> mixed = List.of(run("m", "monolithic", "r1", 100, 1000, 50.0, true),
                run("m", "orchestrated", "r1", 100, 1000, 60.0, true));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> stats.filterRuns(mixed, false, false, "A"));
        assertTrue(e.getMessage().contains("mode"), "the differing component must be named: " + e.getMessage());
        final var pool = List.of(run("m", "monolithic", "r1", 100, 1000, 50.0, true));
        assertDoesNotThrow(() -> stats.filterRuns(pool, false, false, "A"));   // identical keys pool fine
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> stats.filterRuns(java.util.List.of(run("m1", "monolithic", "r1", 100, 1000, 50.0, true),
                        run("m2", "monolithic", "r1", 100, 1000, 60.0, true)), false, false, "A"));
        assertTrue(e2.getMessage().contains("mix models"));
    }

    @Test
    void harnessEffectNeedsMatchedBudgets() {
        final StatsService.RunSummary a = run("m", "monolithic", "r1", 14400, 400000, 50.0, true);
        final StatsService.RunSummary b = run("m", "orchestrated", "r1", 14400, 400000, 60.0, true);
        assertDoesNotThrow(() -> StatsService.requireMatchedBudgets(a, b, false));      // 2% tolerance
        final StatsService.RunSummary c = run("m", "orchestrated", "r1", 14400, 200000, 60.0, true);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> StatsService.requireMatchedBudgets(a, c, false));
        assertTrue(e.getMessage().contains("not matched"));
        assertDoesNotThrow(() -> StatsService.requireMatchedBudgets(a, c, true));       // explicitly confounded
    }

    /** The /api/groups leaderboard's core math: k, mean/CI90/n per metric, and the pass^k matrix -
     *  # (passes every run) vs + (flaky) vs never, here as pass_rate/pass_k/col. */
    @Test
    void summarizeComputesKMeanCiAndThePassKMatrix() {
        List<StatsService.RunSummary> runs = List.of(
                run("m", "monolithic", "r1", 100, 1000, 80.0, true, Map.of("F1", "PASS", "F2", "PASS", "B1", "FAIL")),
                run("m", "monolithic", "r1", 100, 1000, 90.0, true, Map.of("F1", "PASS", "F2", "FAIL", "B1", "FAIL")),
                run("m", "monolithic", "r1", 100, 1000, 70.0, true, Map.of("F1", "PASS", "F2", "PASS", "B1", "FAIL")));

        final Map<String, Object> out = stats.summarize(runs);

        assertEquals(3, out.get("k"));
        assertEquals("L7_full_platform", out.get("task"));
        @SuppressWarnings("unchecked") Map<String, Object> functional = (Map<String, Object>) out.get("functional");
        assertEquals(80.0, ((Number) functional.get("mean")).doubleValue());
        assertEquals(3, functional.get("n"));
        @SuppressWarnings("unchecked") List<Double> ci = (List<Double>) functional.get("ci90");
        assertTrue(ci.get(0) <= 80.0 && ci.get(1) >= 80.0, "the CI must bracket the mean: " + ci);

        @SuppressWarnings("unchecked") Map<String, Object> matrix = (Map<String, Object>) out.get("matrix");
        @SuppressWarnings("unchecked") Map<String, Object> f1 = (Map<String, Object>) matrix.get("F1");
        assertEquals(true, f1.get("pass_k"), "F1 passed in all 3 runs");
        assertEquals(1.0, ((Number) f1.get("pass_rate")).doubleValue());
        @SuppressWarnings("unchecked") Map<String, Object> f2 = (Map<String, Object>) matrix.get("F2");
        assertEquals(false, f2.get("pass_k"), "F2 is flaky: passed 2 of 3");
        assertEquals(0.67, ((Number) f2.get("pass_rate")).doubleValue());
        @SuppressWarnings("unchecked") Map<String, Object> b1 = (Map<String, Object>) matrix.get("B1");
        assertEquals(false, b1.get("pass_k"));
        assertEquals(0.0, ((Number) b1.get("pass_rate")).doubleValue(), "never passed");
    }

    @Test
    void summarizeOfZeroRunsIsKZeroNotAnException() {
        assertEquals(0, stats.summarize(List.of()).get("k"));
    }

    @Test
    void compareReportsDiffCiPAndVerdict() {
        final Map<String, Object> out = stats.compare(List.of(80.0, 82.0, 79.0, 81.0, 83.0), List.of(60.0, 62.0, 58.0, 61.0, 59.0), "functional");
        assertEquals("functional", out.get("metric"));
        assertEquals(21.0, ((Number) out.get("diff")).doubleValue());
        assertTrue(((Number) out.get("p")).doubleValue() < 0.05);
        assertEquals("A > B is supported (p<0.10)", out.get("verdict"));
        assertEquals(true, out.get("one_sided"));
    }
}
