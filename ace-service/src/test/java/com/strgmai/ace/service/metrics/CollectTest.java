package com.strgmai.ace.service.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
