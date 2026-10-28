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
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /** #472/#475: RunDetailView's own "Plan tasks" table (and the per-task dialog it opens) needs
     *  a real per_task row for every manifest task - until now this port of collect.py never wrote
     *  the block at all, so the table had nothing to show on any real run. reported/done_verified/
     *  requests/task_wall_sec come straight off the manifest entry; completion_tokens/max_prompt are
     *  read from the journal within the task's own [start_iso, end_iso] window, the same windowed
     *  mechanism this class already uses for phases. */
    @Test
    void perTaskReflectsManifestFieldsAndTheTasksOwnJournalWindow(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"ts\": \"2026-01-01T00:00:30Z\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"request\": {}, \"response\": {\"usage\": {\"completion_tokens\": 40, \"prompt_tokens\": 500}}}\n"
                        + "{\"ts\": \"2026-01-01T00:00:45Z\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"request\": {}, \"response\": {\"usage\": {\"completion_tokens\": 60, \"prompt_tokens\": 9000}}}\n");
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "reported", "done", "done_verified", true, "requests", 2,
                        "seconds", 61.0, "rc", 0, "start_iso", "2026-01-01T00:00:00Z", "end_iso", "2026-01-01T00:01:00Z")));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertEquals("done", t1.get("reported"));
        assertEquals(true, t1.get("done_verified"));
        assertEquals(2, t1.get("requests"));
        assertEquals(61.0, ((Number) t1.get("task_wall_sec")).doubleValue(), 0.001);
        assertEquals(100L, ((Number) t1.get("completion_tokens")).longValue(), "40 + 60, both requests fall inside the task's own window");
        assertEquals(9000L, ((Number) t1.get("max_prompt")).longValue());
        assertEquals(false, t1.get("over_budget"));
    }

    @Test
    void perTaskOverBudgetReflectsTheTaskWallTimeoutExitCode(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 124)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertEquals(true, t1.get("over_budget"));
    }

    /** A task resumed past on a later attempt (RunBench finds its phase/&lt;id&gt; tag already
     *  committed and skips re-running it) gets a bare {id, rc, resumed} manifest entry - see
     *  BenchController.taskStatsForRun()'s own javadoc. Its per_task row must degrade gracefully
     *  (every other field simply absent), never crash on the missing start_iso/end_iso/seconds. */
    @Test
    void perTaskDegradesGracefullyForABareResumedTaskEntry(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 0, "resumed", true)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertNull(t1.get("reported"));
        assertNull(t1.get("completion_tokens"));
        assertNull(t1.get("max_prompt"));
        assertEquals(false, t1.get("over_budget"));
    }

    /** #501, found live: a run resumed past most of its own tasks (l2-gran-fine-4) left most of its
     *  own Plan tasks grid blank, not just one or two rows - a bare manifest entry has nothing but
     *  id/rc, but the task's own LATEST task_attempts.jsonl record (written for every attempt
     *  regardless of which invocation actually ran it) still carries its real reported outcome,
     *  wall time, and attempt window - recovered from there instead. */
    @Test
    void perTaskFallsBackToTaskAttemptsJsonlForABareResumedEntry(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        Files.writeString(runDir.resolve("task_attempts.jsonl"),
                "{\"task_id\":\"T1\",\"attempt_number\":1,\"attempt_started\":\"2026-01-01T00:00:00Z\","
                        + "\"attempt_ended\":\"2026-01-01T00:01:00Z\",\"rc\":0,\"seconds\":55.0,"
                        + "\"reported_status\":\"done\",\"reported_detail\":\"ok\"}\n");
        Files.writeString(runDir.resolve("interactions.jsonl"),
                "{\"ts\": \"2026-01-01T00:00:30Z\", \"path\": \"/v1/chat/completions\", \"status\": 200, "
                        + "\"request\": {}, \"response\": {\"usage\": {\"completion_tokens\": 40, \"prompt_tokens\": 500}}}\n");
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 0, "resumed", true)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertEquals("done", t1.get("reported"));
        assertEquals(55.0, ((Number) t1.get("task_wall_sec")).doubleValue(), 0.001);
        assertEquals(1, ((Number) t1.get("requests")).intValue());
        assertEquals(40L, ((Number) t1.get("completion_tokens")).longValue());
        assertEquals(500L, ((Number) t1.get("max_prompt")).longValue());
        assertNull(t1.get("done_verified"), "this fixture's own task_attempts.jsonl line predates RunBench.logTaskAttempt() carrying it - absent in, absent out");
    }

    /** found live: logTaskAttempt() now carries done_verified too (it didn't when the test above
     *  was written) - a run resumed AFTER that fix now recovers it the same way every other
     *  task_attempts.jsonl-backed field already does. */
    @Test
    void perTaskRecoversDoneVerifiedWhenTheTaskAttemptsJsonlLineCarriesIt(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        Files.writeString(runDir.resolve("task_attempts.jsonl"),
                "{\"task_id\":\"T1\",\"attempt_number\":1,\"attempt_started\":\"2026-01-01T00:00:00Z\","
                        + "\"attempt_ended\":\"2026-01-01T00:01:00Z\",\"rc\":0,\"seconds\":55.0,"
                        + "\"reported_status\":\"done\",\"done_verified\":true}\n");
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 0, "resumed", true)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertEquals(true, t1.get("done_verified"));
    }

    /** #501: files_changed comes from a real `git diff` against the task's own phase/&lt;id&gt; tag,
     *  run against a throwaway clone of workspace.bundle - the durable git-history copy
     *  bundleWorkspace() already writes into the results directory, with no dependency on the run's
     *  live (possibly long-gone) workspace. */
    @Test
    void perTaskComputesFilesChangedFromTheWorkspaceBundle(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final var ws = Files.createTempDirectory("ws-for-bundle");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App {}");
        Files.writeString(ws.resolve("Util.java"), "class Util {}");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        assertEquals(0, new ProcessBuilder("git", "-C", ws.toString(), "bundle", "create",
                runDir.resolve("workspace.bundle").toString(), "--all").start().waitFor());
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 0)));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertEquals(2, ((Number) t1.get("files_changed")).intValue());
    }

    @Test
    void perTaskFilesChangedIsAbsentWithNoWorkspaceBundle(@TempDir final Path runDir) throws Exception {
        writeOracle(runDir);
        final Map<String, Object> manifest = Map.of("phases", List.of(), "waves", List.of(),
                "tasks", List.of(Map.of("id", "T1", "rc", 0, "seconds", 10.0, "reported", "done")));
        final Map<String, Object> out = Collect.collect(runDir, manifest);
        final Map<String, Object> t1 = (Map<String, Object>) ((Map<String, Object>) out.get("per_task")).get("T1");
        assertFalse(t1.containsKey("files_changed"));
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

    /** #355-followup: a task that got a false start ends up with two session files sharing its
     *  label (the real redo keeps the SAME label, since it's the same task). When BOTH files
     *  independently have real compaction overhead - not just the usual empty false-start case,
     *  which is filtered out before ever reaching the per-task map - the per-task breakdown must
     *  be the SUM of both files' own overhead/events, not whichever file happened to be processed
     *  last silently overwriting the other's contribution. The run-level total_sec already summed
     *  correctly across files even before this fix; only the per-task entry was wrong. */
    @Test
    void perTaskOverheadAccumulatesAcrossMultipleSessionFilesSharingTheSameLabel(@TempDir final Path runDir) throws Exception {
        writeSession(runDir, "a.jsonl", "T16",
                turn("2026-01-01T00:00:00Z"), turn("2026-01-01T00:00:10Z"),
                turn("2026-01-01T00:00:20Z"), turn("2026-01-01T00:00:30Z"),
                COMPACTION, turn("2026-01-01T00:01:20Z"));   // overhead = 50 - 10 = 40s
        writeSession(runDir, "b.jsonl", "T16",
                turn("2026-01-01T02:00:00Z"), turn("2026-01-01T02:00:10Z"),
                turn("2026-01-01T02:00:20Z"), turn("2026-01-01T02:00:30Z"),
                COMPACTION, turn("2026-01-01T02:01:00Z"));   // overhead = 30 - 10 = 20s
        final Map<String, Object> out = Collect.compactionOverhead(runDir);
        assertEquals(60.0, ((Number) out.get("total_sec")).doubleValue(), 0.001, "40 (a) + 20 (b)");
        final Map<String, Object> tasks = (Map<String, Object>) out.get("tasks");
        final Map<String, Object> t16 = (Map<String, Object>) tasks.get("T16");
        assertEquals(60.0, ((Number) t16.get("overhead_sec")).doubleValue(), 0.001, "must be the sum, not just the last file processed");
        assertEquals(2, t16.get("events"));
    }
}
