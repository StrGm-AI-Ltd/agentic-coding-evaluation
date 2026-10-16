package com.strgmai.ace.service.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    /** #337: a task retried within the run (2 logged attempts) and a task rescued mid-session both
     *  need to surface as a run-level effort signal, distinct from a run where every task one-shot
     *  cleanly. */
    @Test
    void retriedAndRescuedTasksAreCountedAtTheRunLevel(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        Files.writeString(runDir.resolve("task_attempts.jsonl"), String.join("\n",
                "{\"task_id\":\"T1\",\"attempt_number\":1,\"rc\":3}",
                "{\"task_id\":\"T1\",\"attempt_number\":2,\"rc\":0}",
                "{\"task_id\":\"T2\",\"attempt_number\":1,\"rc\":0,\"rescue_succeeded\":true}",
                "{\"task_id\":\"T3\",\"attempt_number\":1,\"rc\":0}") + "\n");
        final Map<String, Object> manifest = Map.of("phases", List.of(), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(1, leaderboard.get("tasks_with_retries"), "only T1 has more than one logged attempt");
        assertEquals(1, leaderboard.get("tasks_rescued"), "only T2 has a rescue_succeeded attempt");
    }

    @Test
    void aRunWithNoTaskAttemptsFileReportsZeroEffortCounts(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("phases", List.of(), "tasks", List.of(), "waves", List.of());
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> leaderboard = (Map<String, Object>) out.get("leaderboard");
        assertEquals(0, leaderboard.get("tasks_with_retries"));
        assertEquals(0, leaderboard.get("tasks_rescued"));
    }

    /** #250: found live - a resumed phase (R18: skipped this attempt, so it never recorded a real
     *  start/end timestamp) has start_iso/end_iso missing from the manifest. String.valueOf(null)
     *  turns that into the literal string "null", which sails past JournalFacts.facts()'s own
     *  "sinceIso == null" guard and crashes OffsetDateTime.parse("null") - but only once a REAL
     *  journal file exists (an empty @TempDir run short-circuits before ever reaching that parse,
     *  which is why the other tests in this file never caught it). */
    @Test
    void aResumedPhaseWithNoRecordedTimestampsDoesNotCrashFactsCollection(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        Files.writeString(runDir.resolve("interactions.jsonl"), "");   // must exist, even if empty
        final Map<String, Object> manifest = Map.of(
                "phases", List.of(Map.of("id", "p1_plan", "seconds", 0.0)),   // no start_iso/end_iso at all
                "tasks", List.of(), "waves", List.of());
        assertDoesNotThrow(() -> Collect.collect(runDir, manifest));
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

    private static void writeSession(final Path runDir, final String fileName, final String label, final String... lines) throws Exception {
        final Path sessions = runDir.resolve("sessions");
        Files.createDirectories(sessions);
        final var all = new java.util.ArrayList<String>();
        all.add("{\"label\":\"" + label + "\"}");
        all.addAll(List.of(lines));
        Files.writeString(sessions.resolve(fileName), String.join("\n", all) + "\n");
    }

    private static String turn(final String iso) {
        return "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"},\"ts\":\"" + iso + "\"}";
    }

    private static final String COMPACTION = "{\"type\":\"compaction\"}";

    /** #353: found live on T7 (#351) - compaction exists to keep decode speed up by keeping
     *  context size down, but a compaction's own re-prefill can cost more than it saves. "overhead"
     *  is the EXCESS wall-clock time the turn right after a compaction took, beyond this session's
     *  own baseline (median) gap between every OTHER turn - never the raw turn time itself, since
     *  even an uncompacted turn still takes real time to do real work. */
    @Test
    void overheadIsTheExcessBeyondTheSessionsOwnNormalGapNotTheRawTurnTime(@TempDir final Path runDir) throws Exception {
        // 3 normal gaps of 10s each (baseline = 10s median), then a compaction, then a turn 50s
        // later - overhead = 50 - 10 = 40s
        writeSession(runDir, "a.jsonl", "T1",
                turn("2026-01-01T00:00:00Z"), turn("2026-01-01T00:00:10Z"),
                turn("2026-01-01T00:00:20Z"), turn("2026-01-01T00:00:30Z"),
                COMPACTION, turn("2026-01-01T00:01:20Z"));
        final Map<String, Object> out = Collect.compactionOverhead(runDir);
        assertEquals(40.0, ((Number) out.get("total_sec")).doubleValue(), 0.001);
        final Map<String, Object> tasks = (Map<String, Object>) out.get("tasks");
        final Map<String, Object> t1 = (Map<String, Object>) tasks.get("T1");
        assertEquals(40.0, ((Number) t1.get("overhead_sec")).doubleValue(), 0.001);
        assertEquals(1, t1.get("events"));
        final List<Map<String, Object>> timeline = (List<Map<String, Object>>) out.get("timeline");
        assertEquals(1, timeline.size());
        assertEquals(40.0, ((Number) timeline.get(0).get("cumulative_sec")).doubleValue(), 0.001);
    }

    @Test
    void aSessionWithNoCompactionsAtAllReportsZeroOverhead(@TempDir final Path runDir) throws Exception {
        writeSession(runDir, "a.jsonl", "T1",
                turn("2026-01-01T00:00:00Z"), turn("2026-01-01T00:00:10Z"), turn("2026-01-01T00:00:20Z"));
        final Map<String, Object> out = Collect.compactionOverhead(runDir);
        assertEquals(0.0, ((Number) out.get("total_sec")).doubleValue(), 0.001);
        assertEquals(Map.of(), out.get("tasks"));
        assertEquals(List.of(), out.get("timeline"));
    }

    /** #353: a session that compacts on literally every turn has no "normal" gap of its own to
     *  compare against - must read as zero overhead, not crash or divide against an empty list. */
    @Test
    void aSessionCompactingOnEveryTurnHasNoBaselineAndReadsAsZeroRatherThanCrashing(@TempDir final Path runDir) throws Exception {
        writeSession(runDir, "a.jsonl", "T1",
                turn("2026-01-01T00:00:00Z"), COMPACTION, turn("2026-01-01T00:00:10Z"), COMPACTION, turn("2026-01-01T00:00:20Z"));
        assertDoesNotThrow(() -> {
            final Map<String, Object> out = Collect.compactionOverhead(runDir);
            assertEquals(0.0, ((Number) out.get("total_sec")).doubleValue(), 0.001);
        });
    }

    @Test
    void aRunWithNoSessionsDirReadsAsZeroNeverThrows(@TempDir final Path runDir) {
        assertDoesNotThrow(() -> {
            final Map<String, Object> out = Collect.compactionOverhead(runDir);
            assertEquals(0.0, ((Number) out.get("total_sec")).doubleValue(), 0.001);
            assertEquals(List.of(), out.get("timeline"));
        });
    }

    /** #353: the timeline pools EVERY task's own compaction events, sorted by absolute timestamp
     *  regardless of which task's session they came from - this is what lets the chart show one
     *  coherent "cumulative overhead over the whole run's wall-clock timeline" line even when
     *  tasks ran sequentially (or in parallel waves) rather than needing one line per task. */
    @Test
    void theTimelinePoolsEveryTasksOwnEventsSortedByAbsoluteTimestamp(@TempDir final Path runDir) throws Exception {
        // T2's own single compaction event happens chronologically BEFORE T1's, despite T1 being
        // written first and alphabetically first - the timeline must still come out T2-then-T1
        writeSession(runDir, "a.jsonl", "T1",
                turn("2026-01-01T01:00:00Z"), turn("2026-01-01T01:00:10Z"), turn("2026-01-01T01:00:20Z"),
                COMPACTION, turn("2026-01-01T01:01:20Z"));   // overhead = 60 - 10 = 50s, at 01:01:20
        writeSession(runDir, "b.jsonl", "T2",
                turn("2026-01-01T00:00:00Z"), turn("2026-01-01T00:00:10Z"), turn("2026-01-01T00:00:20Z"),
                COMPACTION, turn("2026-01-01T00:00:50Z"));    // overhead = 30 - 10 = 20s, at 00:00:50
        final Map<String, Object> out = Collect.compactionOverhead(runDir);
        assertEquals(70.0, ((Number) out.get("total_sec")).doubleValue(), 0.001, "20 (T2) + 50 (T1)");
        final List<Map<String, Object>> timeline = (List<Map<String, Object>>) out.get("timeline");
        assertEquals(2, timeline.size());
        assertEquals(0L, ((Number) timeline.get(0).get("elapsed_sec")).longValue(), "T2's event is the earliest - timeline starts at it");
        assertEquals(20.0, ((Number) timeline.get(0).get("cumulative_sec")).doubleValue(), 0.001);
        assertEquals(20.0 + 50.0, ((Number) timeline.get(1).get("cumulative_sec")).doubleValue(), 0.001, "T1's event adds on top, running total");
    }
}
