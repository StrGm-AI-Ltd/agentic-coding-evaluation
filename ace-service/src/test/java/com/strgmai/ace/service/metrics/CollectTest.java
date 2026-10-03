package com.strgmai.ace.service.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** total_wall_sec: an orchestrated run's real execution time lives in manifest.tasks/waves, not
 *  manifest.phases (that only ever holds p0_definition/p1_plan) - every orchestrated run reported
 *  0 wall seconds until this summed the other two lists too. */
class CollectTest {

    private static void writeOracle(final Path runDir) throws Exception {
        Files.writeString(runDir.resolve("oracle.json"),
                "{\"functional_score_pct\":100.0,\"weighted_score_pct\":90.0,\"functional_points_got\":1,\"results\":[]}");
    }

    @Test
    void sequentialTaskSecondsAreSummedIntoTotalWallSec(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(Map.of("id", "p1_plan", "seconds", 0.0)),
                "tasks", List.of(Map.of("id", "T1", "seconds", 630.4), Map.of("id", "T2", "seconds", 419.1)),
                "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(1049L, ((Number) leaderboard.get("total_wall_sec")).longValue());
    }

    @Test
    void aWaveTasksIndividualSecondsAreSkippedInFavourOfTheWavesOwnWallClockSeconds(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        // two tasks ran CONCURRENTLY in a 100s wave: summing their individual durations (60s + 90s)
        // would double-count real time, since they overlapped rather than ran back-to-back
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(),
                "tasks", List.of(
                        Map.of("id", "T1", "seconds", 60.0, "parallel_wave", List.of("T1", "T2")),
                        Map.of("id", "T2", "seconds", 90.0, "parallel_wave", List.of("T1", "T2"))),
                "waves", List.of(Map.of("tasks", List.of("T1", "T2"), "seconds", 100.0)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(100L, ((Number) leaderboard.get("total_wall_sec")).longValue());
    }

    /** trajectory_use=direct: the reviewer's own score IS the blended term, not agreement with the
     *  harness's objective trajectory index (issue #30 - this mode had no implementation at all). */
    @Test
    void trajectoryUseDirectBlendsTheReviewersRawScore(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);   // weighted_score_pct: 90.0
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(), "tasks", List.of(), "waves", List.of(),
                "trajectory_review_config", Map.of("weight", 0.5, "use", "direct"),
                "trajectory_review", Map.of("score", 80.0, "objective_index_pct", 65.0));   // objective_index ignored in direct mode
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        // base = 90 x (1 - 0.1(review default) - 0.5(traj) - 0.1(parallel default)) = 27.0; trajTerm = 0.5 x 80 = 40.0
        assertEquals(67.0, ((Number) leaderboard.get("agent_result_pct")).doubleValue(), 0.001);
    }

    /** #32: journal_facts' averages must reach the leaderboard - they used to be computed by
     *  JournalFacts and then silently dropped (only completion_tokens was ever read out of it). */
    @Test
    void avgLatencyAndFirstByteFlowFromJournalFactsIntoTheLeaderboard(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(), "tasks", List.of(), "waves", List.of(),
                "journal_facts", Map.of("completion_tokens", 5L, "avg_latency_sec", 12.34, "avg_first_byte_ms", 250L));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(12.34, ((Number) leaderboard.get("avg_latency_sec")).doubleValue(), 0.001);
        assertEquals(250L, ((Number) leaderboard.get("avg_first_byte_ms")).longValue());
    }

    /** New metric: speed_by_context is a chart data blob, not a leaderboard scalar, so it lives
     *  alongside "steps" at the top level - forwarded straight from journal_facts. */
    @Test
    void speedByContextFlowsFromJournalFactsToTheTopLevel(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final List<Map<String, Object>> buckets = List.of(
                Map.of("context_lo", 0L, "context_hi", 8192L, "requests", 2L,
                        "avg_prefill_tok_per_sec", 150.0, "avg_decode_tok_per_sec", 30.0));
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(), "tasks", List.of(), "waves", List.of(),
                "journal_facts", Map.of("speed_by_context", buckets));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        assertEquals(buckets, out.get("speed_by_context"));
    }

    /** No journal_facts.speed_by_context (e.g. a run with no chat completions carrying
     *  prompt_tokens) - an empty list, not a missing key or a null. */
    @Test
    void speedByContextIsAnEmptyListWhenJournalFactsHasNone(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("phases", List.of(), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        assertEquals(List.of(), out.get("speed_by_context"));
    }

    /** the default (unset trajectory_use, or "calibration"): unchanged from before #30 - rewarded
     *  for agreeing with the harness's own objective trajectory index, not for the raw score. */
    @Test
    void trajectoryUseCalibrationIsTheUnchangedDefault(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(), "tasks", List.of(), "waves", List.of(),
                "trajectory_review_config", Map.of("weight", 0.5),   // no "use" - defaults to calibration
                "trajectory_review", Map.of("score", 80.0, "objective_index_pct", 65.0));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        // trajTerm = 0.5 x (100 - |80-65|) = 42.5; base unchanged at 27.0
        assertEquals(69.5, ((Number) leaderboard.get("agent_result_pct")).doubleValue(), 0.001);
    }

    /** efficiency_pct: how much of the rung's own declared wall-clock budget (ladder.json's
     *  budget_sec) was left unused - L1_migration_entity's is 2700s. */
    @Test
    void efficiencyPctReflectsHowMuchOfTheRungSOwnBudgetWasLeftUnused(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("task", "L1_migration_entity",
                "phases", List.of(Map.of("id", "implement", "seconds", 1350.0)), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        // 1350/2700 = 50% used -> 50% left unused
        assertEquals(50.0, ((Number) leaderboard.get("efficiency_pct")).doubleValue(), 0.001);
    }

    @Test
    void efficiencyPctClampsAtZeroWhenOverBudgetRatherThanGoingNegative(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("task", "L1_migration_entity",
                "phases", List.of(Map.of("id", "implement", "seconds", 5400.0)), "tasks", List.of(), "waves", List.of());   // 2x the 2700s budget
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(0.0, ((Number) leaderboard.get("efficiency_pct")).doubleValue(), 0.001);
    }

    @Test
    void efficiencyPctIsAbsentWhenTheRungDeclaresNoBudgetSec(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("task", "no-such-rung",
                "phases", List.of(Map.of("id", "implement", "seconds", 100.0)), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertFalse(leaderboard.containsKey("efficiency_pct"));
    }

    /** --efficiency-weight is opt-in only (default 0, unlike review/trajectory/parallel's 0.1
     *  default) - every test above already pins agent_result_pct assuming it contributes nothing
     *  unless explicitly set. This confirms it DOES apply once an operator asks for it. */
    @Test
    void efficiencyWeightBlendsIntoAgentResultPctOnlyWhenExplicitlySet(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);   // weighted_score_pct: 90.0
        final Map<String, Object> manifest = Map.of("task", "L1_migration_entity",
                "phases", List.of(Map.of("id", "implement", "seconds", 1350.0)), "tasks", List.of(), "waves", List.of(),
                "efficiency_config", Map.of("weight", 0.2));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        // base = 90 x (1 - 0.1(review default) - 0.1(traj default) - 0.1(parallel default) - 0.2(efficiency)) = 45.0
        // effTerm = 0.2 x 50.0(efficiency_pct) = 10.0
        assertEquals(55.0, ((Number) leaderboard.get("agent_result_pct")).doubleValue(), 0.001);
    }

    @Test
    void efficiencyWeightContributesNothingWhenUnsetEvenWithAComputableEfficiencyPct(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);   // weighted_score_pct: 90.0
        final Map<String, Object> manifest = Map.of("task", "L1_migration_entity",
                "phases", List.of(Map.of("id", "implement", "seconds", 1350.0)), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(50.0, ((Number) leaderboard.get("efficiency_pct")).doubleValue(), 0.001, "the figure is still reported");
        // base = 90 x (1 - 0.1 - 0.1 - 0.1 - 0) = 63.0, no efficiency term added
        assertEquals(63.0, ((Number) leaderboard.get("agent_result_pct")).doubleValue(), 0.001);
    }
}
