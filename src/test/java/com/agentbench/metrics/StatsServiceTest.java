package com.agentbench.metrics;

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
        double p1 = StatsService.permutationTest(a, b, 5000, 0);
        double p2 = StatsService.permutationTest(a, b, 5000, 0);
        assertEquals(p1, p2);              // seeded: reproducible
        assertTrue(p1 < 0.05, "A consistently >> B must give a small p, got " + p1);
        double pNull = StatsService.permutationTest(a, a.clone(), 5000, 0);
        assertTrue(pNull > 0.3, "A vs itself must give a large p, got " + pNull);
    }

    @Test
    void bootCiBracketsTheMeanAndStaysInsideTheDataRange() {
        List<Double> xs = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0);
        double[] ci = StatsService.bootCi(xs, 2000, 0.10, 0);
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertTrue(ci[0] <= mean && ci[1] >= mean, "the CI must bracket the mean " + mean + ": [" + ci[0] + ", " + ci[1] + "]");
        assertTrue(ci[0] >= 10.0 && ci[1] <= 80.0, "a bootstrap mean cannot leave the data range");
        assertTrue(ci[0] < ci[1]);
    }

    private StatsService.RunSummary run(String model, String mode, String registry, int wall, int tokens, double functional, boolean valid) {
        return new StatsService.RunSummary("results/x", "L7_full_platform", model, mode, functional, functional, null, null, valid,
                List.of(), false, true, wall, tokens,
                java.util.Map.ofEntries(
                        java.util.Map.entry("task", "L7_full_platform"), java.util.Map.entry("registry", registry),
                        java.util.Map.entry("run_oracle", "o1"), java.util.Map.entry("contract", "c1"),
                        java.util.Map.entry("task_prompt", "p1"), java.util.Map.entry("mode", mode),
                        java.util.Map.entry("plan_source", "agent"), java.util.Map.entry("harness_version", "jls-ref-1.0"),
                        java.util.Map.entry("budgets_wall", String.valueOf(wall)), java.util.Map.entry("budgets_tokens", String.valueOf(tokens)),
                        java.util.Map.entry("sampler", "s1"), java.util.Map.entry("system_prompt_sha", "sp1")), List.of());
    }

    @Test
    void runsDifferingInAKeyComponentAreRefusedWithTheCulpritNamed() {
        List<StatsService.RunSummary> mixed = List.of(run("m", "monolithic", "r1", 100, 1000, 50.0, true),
                run("m", "orchestrated", "r1", 100, 1000, 60.0, true));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> stats.filterRuns(mixed, false, false, "A"));
        assertTrue(e.getMessage().contains("mode"), "the differing component must be named: " + e.getMessage());
        List<StatsService.RunSummary> pool = List.of(run("m", "monolithic", "r1", 100, 1000, 50.0, true));
        assertDoesNotThrow(() -> stats.filterRuns(pool, false, false, "A"));   // identical keys pool fine
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> stats.filterRuns(java.util.List.of(run("m1", "monolithic", "r1", 100, 1000, 50.0, true),
                        run("m2", "monolithic", "r1", 100, 1000, 60.0, true)), false, false, "A"));
        assertTrue(e2.getMessage().contains("mix models"));
    }

    @Test
    void harnessEffectNeedsMatchedBudgets() {
        StatsService.RunSummary a = run("m", "monolithic", "r1", 14400, 400000, 50.0, true);
        StatsService.RunSummary b = run("m", "orchestrated", "r1", 14400, 400000, 60.0, true);
        assertDoesNotThrow(() -> StatsService.requireMatchedBudgets(a, b, false));      // 2% tolerance
        StatsService.RunSummary c = run("m", "orchestrated", "r1", 14400, 200000, 60.0, true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> StatsService.requireMatchedBudgets(a, c, false));
        assertTrue(e.getMessage().contains("not matched"));
        assertDoesNotThrow(() -> StatsService.requireMatchedBudgets(a, c, true));       // explicitly confounded
    }

    @Test
    void compareReportsDiffCiPAndVerdict() {
        Map<String, Object> out = stats.compare(List.of(80.0, 82.0, 79.0, 81.0, 83.0), List.of(60.0, 62.0, 58.0, 61.0, 59.0), "functional");
        assertEquals("functional", out.get("metric"));
        assertEquals(21.0, ((Number) out.get("diff")).doubleValue());
        assertTrue(((Number) out.get("p")).doubleValue() < 0.05);
        assertEquals("A > B is supported (p<0.10)", out.get("verdict"));
        assertEquals(true, out.get("one_sided"));
    }
}
