package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 2026-09-16 queue-page bug, pinned: jobs whose runs are not imported (queued,
 * running, cancelled, unscored) must not lead the UI into a run-detail dead end.
 * Covers the job-page gate: the import probe and its poll-friendly freshness rules.
 */
class JobDetailViewTest {

    @Test
    void runProbe_404MeansNotImported() {
        final var client = mock(ServiceClient.class);
        when(client.run("he-2026-…-cancelled-r1")).thenThrow(ApiFixtures.http(404));
        assertFalse(JobDetailView.isRunImported(client, "he-2026-…-cancelled-r1"),
                "the 404 the queue rows hit — the run was never imported");
    }

    @Test
    void runProbe_successAndNon404Errors() {
        final var client = mock(ServiceClient.class);
        when(client.run("imported")).thenReturn(ApiFixtures.run("imported"));
        assertTrue(JobDetailView.isRunImported(client, "imported"));

        when(client.run("broken")).thenThrow(ApiFixtures.http(500));
        assertTrue(JobDetailView.isRunImported(client, "broken"),
                "a 500 is a service problem, not the import gate");

        when(client.run("down")).thenThrow(new ResourceAccessException("down"));
        assertFalse(JobDetailView.isRunImported(client, "down"),
                "network trouble: do not offer a link we could not verify");
    }

    /**
     * The fixed live-requests ClassCastException, pinned as a regression: every
     * comparator must sort rows with null fields without throwing, nulls last.
     */
    @Test
    void requestRowComparators_sortNullFieldsWithoutThrowing() {
        final var rows = List.of(
                new JobLiveState.RequestRow(null, null, null, null, null, false, null),
                new JobLiveState.RequestRow("2026-09-15T02:00:00Z", 500, 1.5, 0.5, 10L, false, null),
                new JobLiveState.RequestRow("2026-09-14T23:00:00+01:00", 404, null, null, null, true, "task-wall budget exceeded (900s)"));
        final java.util.function.Consumer<Comparator<JobLiveState.RequestRow>> sortAll =
                by -> rows.stream().sorted(by).forEach(r -> r.status()); // any terminal op forces the sort
        sortAll.accept(JobDetailView.REQUESTS_BY_STATUS);
        sortAll.accept(JobDetailView.REQUESTS_BY_LATENCY);
        sortAll.accept(JobDetailView.REQUESTS_BY_TTFT);
        sortAll.accept(JobDetailView.REQUESTS_BY_TOKENS);

        final var byTs = rows.stream().sorted(JobDetailView.REQUESTS_BY_TS)
                .map(r -> r.ts() == null ? "null" : r.ts()).toList();
        assertEquals(List.of("2026-09-14T23:00:00+01:00", "2026-09-15T02:00:00Z", "null"),
                byTs, "chronological across formats, absent last");

        final var byStatusNullsLast = rows.stream().sorted(JobDetailView.REQUESTS_BY_STATUS)
                .map(JobLiveState.RequestRow::status).filter(Objects::nonNull).toList();
        assertEquals(List.of(404, 500), byStatusNullsLast);
    }

    /** The sessions grid became sortable by every column alongside the new "ts" one (2026-09-25) -
     *  same null-safety requirement as the requests grid above: a still-running session has most
     *  fields null (wall time, speeds, ended stage), and must not throw when sorted on any of them. */
    @Test
    void sessionRowComparators_sortNullFieldsWithoutThrowing() {
        final var rows = List.of(
                new JobLiveState.SessionRow(null, null, null, null, null, null, null, null, null, null, null, null, null),
                new JobLiveState.SessionRow("a", "T1", "task T1", "stop", 90.0, 20.0, 120.0, 500L, 30.0, 90.0, "2026-09-15T02:00:00Z", "T1", null),
                new JobLiveState.SessionRow("b", "T2", "task T2", null, null, null, null, null, null, null, "2026-09-14T23:00:00+01:00", "T2", null));
        final java.util.function.Consumer<Comparator<JobLiveState.SessionRow>> sortAll =
                by -> rows.stream().sorted(by).forEach(JobLiveState.SessionRow::id); // any terminal op forces the sort
        sortAll.accept(JobDetailView.SESSIONS_BY_LABEL);
        sortAll.accept(JobDetailView.SESSIONS_BY_DESCRIPTION);
        sortAll.accept(JobDetailView.SESSIONS_BY_ENDED_STAGE);
        sortAll.accept(JobDetailView.SESSIONS_BY_WALL_SEC);
        sortAll.accept(JobDetailView.SESSIONS_BY_PREFILL_WALL);
        sortAll.accept(JobDetailView.SESSIONS_BY_DECODE_WALL);
        sortAll.accept(JobDetailView.SESSIONS_BY_PREFILL_TPS);
        sortAll.accept(JobDetailView.SESSIONS_BY_DECODE_TPS);
        sortAll.accept(JobDetailView.SESSIONS_BY_TOTAL_TOKENS);

        final var byTs = rows.stream().sorted(JobDetailView.SESSIONS_BY_TS)
                .map(r -> r.ts() == null ? "null" : r.ts()).toList();
        assertEquals(List.of("2026-09-14T23:00:00+01:00", "2026-09-15T02:00:00Z", "null"),
                byTs, "chronological across formats, absent last");
    }

    /** The 2026-09-16 confusing-hint fix: the message explains the job's own state. */
    @Test
    void notImportedHint_isStateAware() {
        assertEquals("the run is still in progress — its results appear here automatically "
                        + "when the job finishes with a score",
                JobDetailView.runNotImportedHint("running"));
        assertEquals(JobDetailView.runNotImportedHint("running"),
                JobDetailView.runNotImportedHint("queued"));
        assertEquals(JobDetailView.runNotImportedHint("running"),
                JobDetailView.runNotImportedHint("waiting_lock"));
        assertTrue(JobDetailView.runNotImportedHint("blocked").startsWith("this job is blocked"),
                "blocked jobs need a requeue, not a wait — their own message since n20");
        assertTrue(JobDetailView.runNotImportedHint("paused").startsWith("this job is paused"),
                "paused jobs need a Resume, not a wait or an ended-without-score message");

        assertEquals("this job ended without a scored result — such runs are never imported",
                JobDetailView.runNotImportedHint("cancelled"));
        assertEquals(JobDetailView.runNotImportedHint("cancelled"),
                JobDetailView.runNotImportedHint("failed"));

        assertTrue(JobDetailView.runNotImportedHint("succeeded")
                .startsWith("the job succeeded but its results were not imported"));
        assertEquals("the run is not imported yet", JobDetailView.runNotImportedHint(null));
        assertEquals("the run is not imported yet", JobDetailView.runNotImportedHint("whatever"));
    }

    @Test
    void probeFreshness_matrix() {
        // never probed → probe (once per navigation)
        assertTrue(JobDetailView.shouldProbeRun(null, null, "running"));
        assertTrue(JobDetailView.shouldProbeRun(null, "", "queued"));

        // already probed, no terminal transition → no re-probe (poll-safe: one GET per navigation)
        assertFalse(JobDetailView.shouldProbeRun(Boolean.TRUE, "running", "running"));
        assertFalse(JobDetailView.shouldProbeRun(Boolean.FALSE, "running", "running"));

        // transition into a terminal state → re-probe, even when previously not imported:
        // the worker imports the run right when the job finishes scored
        assertTrue(JobDetailView.shouldProbeRun(Boolean.TRUE, "running", "succeeded"));
        assertTrue(JobDetailView.shouldProbeRun(Boolean.FALSE, "running", "failed"));
        assertTrue(JobDetailView.shouldProbeRun(Boolean.FALSE, "blocked", "cancelled"));

        // already terminal, still terminal → nothing new to learn
        assertFalse(JobDetailView.shouldProbeRun(Boolean.TRUE, "succeeded", "succeeded"));
        assertFalse(JobDetailView.shouldProbeRun(Boolean.FALSE, "succeeded", "failed"));
    }

    /** The T3-after-T4 confusion, pinned: T3 legitimately starts after T4 (both are wave 2, T3 only
     *  depends on T2) but looks like the runner went backwards without seeing the declared order. */
    @Test
    void plannedOrderText_joinsWavesWithArrowsAndTasksWithCommas() {
        assertEquals("T1 → T2, T4 → T3",
                JobDetailView.plannedOrderText(List.of(List.of("T1"), List.of("T2", "T4"), List.of("T3"))));
    }

    @Test
    void plannedOrderText_emptyWavesYieldsEmptyText() {
        assertEquals("", JobDetailView.plannedOrderText(List.of()));
    }

    private static JobLiveState.SessionRow sessionRow(final String description, final String endedStage) {
        return new JobLiveState.SessionRow("sid", "sid", description, endedStage, null, null, null, null, null, null, null, null, null);
    }

    /** taskStats() sums EVERY session matching the task id, not just the latest one - a task
     *  retried/continued across two sessions genuinely spent time/tokens in both. */
    @Test
    void taskStats_sumsWallTimeAndTokensAcrossEveryMatchingSession() {
        final var sessions = List.of(
                new JobLiveState.SessionRow("a", "a", "Task T5", "stop", null, null, 120.0, 100L, null, null, "2026-09-15T02:00:00Z", "T5", 2000L),
                new JobLiveState.SessionRow("b", "b", "Task T5 (continue)", "stop", null, null, 60.0, 50L, null, null, "2026-09-15T03:00:00Z", "T5-continue", 1000L));
        final var stats = JobDetailView.taskStats(sessions, "T5", null);
        assertEquals(180.0, stats.wallSec());
        assertEquals(150L, stats.completionTokens());
        assertEquals(3000L, stats.promptTokens());
    }

    /** Same anchored-prefix guard taskStatus() already has: "T1" must never pick up a session
     *  actually for "T10". */
    @Test
    void taskStats_doesNotFalseMatchATaskIdThatIsAPrefixOfAnothersId() {
        final var sessions = List.of(
                new JobLiveState.SessionRow("a", "a", "Task T10", "stop", null, null, 500.0, 999L, null, null, null, "T10", 9999L));
        final var stats = JobDetailView.taskStats(sessions, "T1", null);
        assertEquals(0.0, stats.wallSec());
        assertEquals(0L, stats.completionTokens());
        assertEquals(0L, stats.promptTokens());
    }

    /** A still-open session for the task that's actually the CURRENT step contributes its own
     *  elapsed-so-far wall time, so the figure ticks up live while the task is actually running -
     *  same reasoning JobLiveState.totalActiveSeconds() already applies at the whole-run level. */
    @Test
    void taskStats_countsAStillRunningSessionFromItsOwnStart() {
        final var start = java.time.OffsetDateTime.now().minusSeconds(30);
        final var sessions = List.of(
                new JobLiveState.SessionRow("a", "a", "Task T6", null, null, null, null, null, null, null, start.toString(), "T6", null));
        final var stats = JobDetailView.taskStats(sessions, "T6", "T6");
        assertTrue(stats.wallSec() >= 29 && stats.wallSec() < 60, "expected ~30s elapsed, got " + stats.wallSec());
    }

    /** #335, found live: T21's session was abandoned by a pause+reorder, never produced a
     *  session_done, and nothing retried T21 since - left alone, "now minus start" would grow its
     *  elapsed time forever even though it stopped running ~10 minutes in. Frozen instead at the
     *  moment T11's own session started - the next DIFFERENT task, proving T21 was no longer live
     *  by then. */
    @Test
    void taskStats_freezesAnAbandonedSessionsElapsedTimeAtTheNextOtherTasksStart() {
        final var t21Start = java.time.OffsetDateTime.now().minusHours(2);
        final var t11Start = t21Start.plusMinutes(10);
        final var sessions = List.of(
                new JobLiveState.SessionRow("a", "a", "Task T21", null, null, null, null, null, null, null, t21Start.toString(), "T21", null),
                new JobLiveState.SessionRow("b", "b", "Task T11", null, null, null, null, null, null, null, t11Start.toString(), "T11", null));
        final var stats = JobDetailView.taskStats(sessions, "T21", "T11");
        assertEquals(600.0, stats.wallSec(), 1.0, "frozen at ~10 minutes, not the ~2 hours since elapsed");
    }

    /** Same scenario, but T21 also has its own judge sub-session in between - that's still T21
     *  running, not evidence something else superseded it, so it must not be picked as the freeze
     *  point ahead of the genuinely different T11. */
    @Test
    void taskStats_ignoresTheTasksOwnSubSessionsWhenLookingForWhatSupersededIt() {
        final var t21Start = java.time.OffsetDateTime.now().minusHours(2);
        final var t21JudgeStart = t21Start.plusMinutes(3);
        final var t11Start = t21Start.plusMinutes(10);
        final var sessions = List.of(
                new JobLiveState.SessionRow("a", "a", "Task T21", null, null, null, null, null, null, null, t21Start.toString(), "T21", null),
                new JobLiveState.SessionRow("j", "j", "Task T21-judge-turn10", "stop", null, null, 1.0, null, null, null, t21JudgeStart.toString(), "T21-judge-turn10", null),
                new JobLiveState.SessionRow("b", "b", "Task T11", null, null, null, null, null, null, null, t11Start.toString(), "T11", null));
        final var stats = JobDetailView.taskStats(sessions, "T21", "T11");
        assertEquals(600.0, stats.wallSec(), 1.0, "T21's own judge turn must not be mistaken for the thing that superseded it");
    }

    private static Api.PlanTask planTask(final String id, final boolean implemented, final boolean handoffDone) {
        return planTask(id, implemented, handoffDone, null);
    }

    private static Api.PlanTask planTask(final String id, final boolean implemented, final boolean handoffDone, final String reportedStatus) {
        return planTask(id, implemented, handoffDone, reportedStatus, null);
    }

    private static Api.PlanTask planTask(final String id, final boolean implemented, final boolean handoffDone,
                                         final String reportedStatus, final String reportedDetail) {
        return planTask(id, implemented, handoffDone, reportedStatus, reportedDetail, 0, false);
    }

    private static Api.PlanTask planTask(final String id, final boolean implemented, final boolean handoffDone,
                                         final String reportedStatus, final String reportedDetail, final int attempts, final boolean rescued) {
        return new Api.PlanTask(0, id, "", "", List.of(), implemented, handoffDone, reportedStatus, reportedDetail, attempts, rescued);
    }

    @Test
    void taskStatus_noMatchingSessionIsNotStartedUnlessTerminal() {
        assertEquals("not started", JobDetailView.taskStatus(List.of(), planTask("T2", false, false), false, null));
        assertEquals("see Run detail", JobDetailView.taskStatus(List.of(), planTask("T2", false, false), true, null),
                "a terminal job with no live session for this task must not claim it was never started");
    }

    @Test
    void taskStatus_runningWhileItsSessionHasNoEndedStageYetAndItIsTheCurrentStep() {
        final var sessions = List.of(sessionRow("Task T2", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T2", false, false), false, "T2"));
    }

    /** #335, found live: an unclosed session for a task that is NOT the current step was
     *  abandoned (a pause+reorder, or any interruption that never produced a session_done) - it
     *  must not claim "running" once something else is demonstrably the live task instead. */
    @Test
    void taskStatus_anUnclosedSessionForANonCurrentTaskIsInterruptedNotRunning() {
        final var sessions = List.of(sessionRow("Task T21", null));
        assertEquals("interrupted", JobDetailView.taskStatus(sessions, planTask("T21", false, false), false, "T11"));
    }

    @Test
    void taskStatus_reportsTheSessionsOwnFinishReasonOnceEnded() {
        final var sessions = List.of(sessionRow("Task T2 — implement the API", "stop"));
        assertEquals("stop", JobDetailView.taskStatus(sessions, planTask("T2", false, false), false, null));
    }

    /** "T1" must never match a session actually for "T10"/"T11" - both share "Task T1" as a string
     *  prefix, but not as the exact anchored match taskStatus requires. */
    @Test
    void taskStatus_doesNotFalseMatchATaskIdThatIsAPrefixOfAnothersId() {
        final var sessions = List.of(sessionRow("Task T10 (handoff)", "stop"));
        assertEquals("not started", JobDetailView.taskStatus(sessions, planTask("T1", false, false), false, null));
    }

    /** a resumed/retried task gets a fresh session sharing the same description prefix - the LAST
     *  one (the current attempt) wins, not the abandoned first attempt. */
    @Test
    void taskStatus_theLastMatchingSessionWinsOverAnEarlierAbandonedAttempt() {
        final var sessions = List.of(sessionRow("Task T3 (handoff)", "interrupted"), sessionRow("Task T3 (handoff)", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T3", false, false), false, "T3"));
    }

    /** #254, found live: a task finished in a PRIOR attempt is resumed straight past (R18) and so
     *  never produces a live session event in a fresh page view - session data alone always read a
     *  genuinely-finished task as "not started". Falls back to the workspace's own git tags. */
    @Test
    void taskStatus_fallsBackToTheWorkspacesOwnTagsWhenThereIsNoLiveSessionAtAll() {
        assertEquals("done", JobDetailView.taskStatus(List.of(), planTask("T1", true, true), false, null));
        assertEquals("implemented (handoff pending)", JobDetailView.taskStatus(List.of(), planTask("T1", true, false), false, null));
        assertEquals("not started", JobDetailView.taskStatus(List.of(), planTask("T1", false, false), false, null));
    }

    /** A genuinely LIVE session (no endedStage yet) for the CURRENT step outranks the tag fallback
     *  unconditionally - nothing is more current than "it is running right now", even if the tags
     *  also say done (R18 guarantees this never actually happens together in practice, but the
     *  precedence itself must hold regardless). */
    @Test
    void taskStatus_aLiveRunningSessionOutranksTheTagFallback() {
        final var sessions = List.of(sessionRow("Task T2", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T2", true, true), false, "T2"),
                "a currently-running session must win even if the tags also say done");
    }

    /** #261, found live: implemented/handoff_done alone only say the task's own session ran to
     *  completion, not whether it actually succeeded - reported_status (the agent's own self-report,
     *  or the harness's "unfinished" fallback) is the real verdict and must be shown once a task is
     *  implemented, in preference to a flat "done". */
    @Test
    void taskStatus_showsTheReportedOutcomeOnceImplemented() {
        assertEquals("partial", JobDetailView.taskStatus(List.of(), planTask("T1", true, true, "partial"), false, null));
        assertEquals("blocked", JobDetailView.taskStatus(List.of(), planTask("T1", true, true, "blocked"), false, null));
        assertEquals("unfinished", JobDetailView.taskStatus(List.of(), planTask("T1", true, true, "unfinished"), false, null));
        assertEquals("partial (handoff pending)",
                JobDetailView.taskStatus(List.of(), planTask("T1", true, false, "partial"), false, null),
                "handoff pending is still worth flagging alongside the real self-reported outcome");
    }

    /** #261 regression, found live: T7's own session ran out of budget mid-tool-call, so the live
     *  session's last known endedStage was "tool_execution" - a snapshot of what the model was
     *  doing in that instant, not a verdict. The task had in fact already finished (its own tags
     *  say so) hours earlier; the UI must show its real reported outcome, not replay a stale,
     *  misleading "tool_execution" from a long-ended session row. */
    @Test
    void taskStatus_reportedOutcomeOutranksAStaleEndedSessionOnceImplemented() {
        final var sessions = List.of(sessionRow("Task T7", "tool_execution"));
        assertEquals("unfinished", JobDetailView.taskStatus(sessions, planTask("T7", true, true, "unfinished"), false, null),
                "the task's own real, persistent verdict must win over a stale ended-session row");
    }

    /** #262: the status word alone doesn't say WHY - taskStatusReason() is the tooltip half,
     *  mirroring taskStatus()'s own precedence exactly so it never shows detail left over from an
     *  unrelated state (still running, or not started at all). */
    @Test
    void taskStatusReason_mirrorsTaskStatussOwnPrecedence() {
        assertEquals("budget exhausted before a status was written",
                JobDetailView.taskStatusReason(List.of(), planTask("T7", true, true, "unfinished", "budget exhausted before a status was written")));
        assertNull(JobDetailView.taskStatusReason(List.of(), planTask("T1", false, false)),
                "nothing to explain yet for a task that hasn't even started");
        assertNull(JobDetailView.taskStatusReason(List.of(), planTask("T1", true, true, "done", null)),
                "no detail was ever written - nothing to show, not an empty tooltip");

        final var running = List.of(sessionRow("Task T7", null));
        assertNull(JobDetailView.taskStatusReason(running, planTask("T7", true, true, "unfinished", "stale - from a prior attempt")),
                "a genuinely running session must suppress a tooltip left over from a past attempt");
    }

    /** #355-followup: found live on T7's own real Stats tab - a bare "rc: 124" means nothing to
     *  an operator who hasn't read the harness's own source. rcText() is the one, pure source of
     *  truth every raw rc shown on the tab (the task's own, rescue_rc, wrapup_rc) now goes
     *  through, covering the full, fixed set of values ReferenceAgent/RunBenchSupport actually
     *  produce. */
    @Test
    void rcText_translatesEveryRealRcValueIntoPlainEnglish() {
        assertEquals("finished normally", JobDetailView.rcText(0, "stop"));
        assertEquals("finished normally", JobDetailView.rcText(0, null), "no finish reason at all still reads as a plain success");
        assertEquals("finished, but its last turn was cut off at the output limit", JobDetailView.rcText(0, "length"));
        assertTrue(JobDetailView.rcText(1, null).contains("turn limit"));
        assertTrue(JobDetailView.rcText(2, null).contains("infrastructure"));
        assertTrue(JobDetailView.rcText(3, null).contains("token budget"));
        assertTrue(JobDetailView.rcText(4, null).contains("context window"));
        assertTrue(JobDetailView.rcText(124, null).contains("wall-clock time") && JobDetailView.rcText(124, null).contains("rc=124"),
                "T7's own real case - the number alone was the whole bug report");
        assertTrue(JobDetailView.rcText(99, null).contains("rc=99"), "an unrecognized code still names itself, never silently blank");
    }

    /** #355-followup: found live on T7's own real Stats tab - "bash: 99 (5 errors)" names the
     *  tool but not what it does, the same gap rcText() closed for a bare rc number. Covers the
     *  model's entire real tool surface (ReferenceAgent.toolSpecs()'s own four tools). */
    @Test
    void toolLabel_describesEachOfTheModelsFourRealTools() {
        assertEquals("read (read a file)", JobDetailView.toolLabel("read"));
        assertEquals("write (create or overwrite a file)", JobDetailView.toolLabel("write"));
        assertEquals("edit (replace text within a file)", JobDetailView.toolLabel("edit"));
        assertEquals("bash (run a shell command)", JobDetailView.toolLabel("bash"));
        assertEquals("some_future_tool", JobDetailView.toolLabel("some_future_tool"), "an unrecognized name still shows itself, never blank");
    }

    private static final JobDetailView.TaskStats NO_LIVE_WALL_TIME = new JobDetailView.TaskStats(0.0, 0, 0);

    /** #355/#355-followup: the Stats tab's own rendering for a FINISHED task - pure, tested
     *  against the exact shape BenchController.taskStats() returns (manifest.tasks' own entry,
     *  plus whatever optional fields actually fired). */
    @Test
    void taskStatsLines_rendersTheCoreFieldsAlwaysPresent() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "rc": 0, "finish": "stop", "seconds": 120.5, "turns": 7,
                 "compactions": 1, "tool_errors": 0}""");

        final var lines = JobDetailView.taskStatsLines(stats, NO_LIVE_WALL_TIME);

        assertEquals("outcome: finished normally", lines.get(0));
        assertEquals("wall time: 120.5s   model turns: 7", lines.get(1));
        assertTrue(lines.get(2).contains("context compactions") && lines.get(2).contains(": 1")
                && lines.get(2).contains("failed tool calls") && lines.get(2).contains(": 0"), lines.get(2));
    }

    /** #355-followup: turns/compactions/tool_calls are now always populated LIVE by the backend
     *  (BenchController.taskStats()), even with no manifest.tasks entry yet - "running" shows
     *  them straight from the response, using the dialog's own frontend-side live wall-time
     *  aggregation only for the one figure the backend cannot report for an unfinished task. */
    @Test
    void taskStatsLines_rendersLiveCountsAndTheFrontendsOwnWallTimeWhenStillRunning() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "running", "turns": 42, "compactions": 3, "tool_errors": 1}""");

        final var lines = JobDetailView.taskStatsLines(stats, new JobDetailView.TaskStats(99.0, 1000, 500));

        assertEquals("outcome: still running", lines.get(0));
        assertEquals("wall time so far: 99s   model turns so far: 42", lines.get(1));
        assertTrue(lines.get(2).contains(": 3") && lines.get(2).contains(": 1"), lines.get(2));
    }

    @Test
    void taskStatsLines_showsTheHarnessVerificationVerdictWhenPresent() {
        final var greenStats = Json.MAPPER.readTree(
                "{\"state\":\"done\",\"rc\":0,\"finish\":\"stop\",\"seconds\":1,\"turns\":1,\"compactions\":0,\"tool_errors\":0,"
                        + "\"verification\":{\"green\":true,\"executed\":9,\"failed\":0}}");
        assertTrue(JobDetailView.taskStatsLines(greenStats, NO_LIVE_WALL_TIME).stream().anyMatch(l -> l.contains("GREEN") && l.contains("9 tests")));

        final var couldNotRun = Json.MAPPER.readTree(
                "{\"state\":\"done\",\"rc\":124,\"finish\":null,\"seconds\":1,\"turns\":1,\"compactions\":0,\"tool_errors\":0,"
                        + "\"verification\":{\"green\":null,\"executed\":0,\"failed\":0}}");
        assertTrue(JobDetailView.taskStatsLines(couldNotRun, NO_LIVE_WALL_TIME).stream().anyMatch(l -> l.contains("could not run")));
    }

    /** #351: a task rescued via the task-wall-timeout path, then the harness's own fallback
     *  handoff (no model turn available) - both must show, distinguishably from the model's own
     *  account, since an operator deciding whether to trust a dependent task's own handoff note
     *  needs to know which kind it's reading. */
    @Test
    void taskStatsLines_showsRescueAndHarnessAuthoredHandoffWhenTheyFired() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "rc": 0, "finish": "stop", "seconds": 1, "turns": 1, "compactions": 0, "tool_errors": 0,
                 "rescue_rc": 0, "rescue_seconds": 300.0, "rescue_succeeded": true,
                 "handoff_seconds": 0.0, "handoff_chars": 240, "handoff_by_harness": true}""");

        final var lines = JobDetailView.taskStatsLines(stats, NO_LIVE_WALL_TIME);

        assertTrue(lines.stream().anyMatch(l -> l.contains("rescue") && l.contains("succeeded")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("handoff") && l.contains("harness-authored fallback")));
    }

    @Test
    void taskStatsLines_showsToolCallBreakdownSortedByNameWithDescriptiveLabelsAndCorrectPluralization() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "rc": 0, "finish": "stop", "seconds": 1, "turns": 1, "compactions": 0, "tool_errors": 2,
                 "tool_calls": {"write": {"count": 3, "errors": 0}, "bash": {"count": 5, "errors": 1}, "edit": {"count": 2, "errors": 2}}}""");

        final var lines = JobDetailView.taskStatsLines(stats, NO_LIVE_WALL_TIME);

        final int bashIdx = indexOfContaining(lines, "bash (");
        final int editIdx = indexOfContaining(lines, "edit (");
        final int writeIdx = indexOfContaining(lines, "write (");
        assertTrue(bashIdx >= 0 && editIdx >= 0 && writeIdx >= 0 && bashIdx < editIdx && editIdx < writeIdx,
                "alphabetical: bash before edit before write");
        assertTrue(lines.get(bashIdx).contains("run a shell command") && lines.get(bashIdx).contains(": 5") && lines.get(bashIdx).contains("1 error)"),
                "a bare tool name is not enough - found live via T7's own Stats tab: " + lines.get(bashIdx));
        assertTrue(lines.get(editIdx).contains("2 errors)"), "plural when >1, singular when ==1: " + lines.get(editIdx));
    }

    /** #353/#354 + the oracle's own per-task check attribution - only ever present once the WHOLE
     *  run (not just this task) has finished scoring; shown when it's there, simply absent
     *  otherwise (checked by the other tests above never needing it). */
    @Test
    void taskStatsLines_showsCheckScoreAndCompactionOverheadWhenTheRunIsFullyScored() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "rc": 0, "finish": "stop", "seconds": 1, "turns": 1, "compactions": 0, "tool_errors": 0,
                 "check_score": {"score_pct": 66.7, "points": [2, 3]},
                 "compaction_overhead": {"overhead_sec": 40.0, "events": 1}}""");

        final var lines = JobDetailView.taskStatsLines(stats, NO_LIVE_WALL_TIME);

        assertTrue(lines.stream().anyMatch(l -> l.contains("check score") && l.contains("66.7") && l.contains("2/3")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("compaction overhead") && l.contains("40.0")));
    }

    private static int indexOfContaining(final List<String> lines, final String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        return -1;
    }

    /** #355-followup-2: the operator asked for every attempt (a false start plus the real redo,
     *  seen live on T3) to show up as its own section, not collapsed into one summary - mirrors
     *  BenchController.taskStats()'s own "attempts" array shape exactly, params/outcome included
     *  when task_attempts.jsonl had a matching row for that attempt. */
    @Test
    void attemptSections_buildsOneNumberedSectionPerAttemptWithItsOwnParamsAndOutcome() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "attempts": [
                    {"session_id": "a_falsestart.jsonl", "started_iso": "2026-10-14T10:00:00Z", "ended_iso": "2026-10-14T10:00:00Z",
                     "turns": 0, "compactions": 0, "tool_errors": 0, "tool_calls": {}, "seconds": 0.0,
                     "params": {"task_wall_sec": 32400}, "overridden": true, "attempt_rc": 2,
                     "reported_status": "crashed", "reported_detail": "transient infra error"},
                    {"session_id": "b_real.jsonl", "started_iso": "2026-10-14T12:00:00Z", "ended_iso": "2026-10-14T12:05:00Z",
                     "turns": 1, "compactions": 0, "tool_errors": 0, "tool_calls": {"bash": {"count": 2, "errors": 0}}, "seconds": 300.0,
                     "params": {"task_wall_sec": 32400, "task_tokens": 180000}, "overridden": true, "attempt_rc": 0,
                     "reported_status": "done"}
                ]}""");

        final var sections = JobDetailView.attemptSections(stats);

        assertEquals(2, sections.size());
        assertTrue(sections.get(0).header().startsWith("Attempt 1"));
        assertTrue(sections.get(0).header().contains("2026-10-14T10:00:00Z"));
        assertTrue(sections.get(0).body().contains("params (") && sections.get(0).body().contains("32400"), sections.get(0).body());
        assertTrue(sections.get(0).body().contains("overridden"), sections.get(0).body());
        assertTrue(sections.get(0).body().contains("wall-clock budget (s): 32400"), "raw key names must read as labels: " + sections.get(0).body());
        assertTrue(sections.get(0).body().contains("rc: 2") && sections.get(0).body().contains("outcome: crashed")
                && sections.get(0).body().contains("transient infra error"), sections.get(0).body());

        assertTrue(sections.get(1).header().startsWith("Attempt 2"));
        assertTrue(sections.get(1).body().contains("180000"), sections.get(1).body());
        assertTrue(sections.get(1).body().contains("wall time: 300.0s   model turns: 1"), sections.get(1).body());
        assertTrue(sections.get(1).body().contains("bash (run a shell command): 2"), sections.get(1).body());
    }

    /** #355-followup-2: a session cut off before RunBench.logTaskAttempt() ever ran leaves no
     *  matching task_attempts.jsonl row - that attempt still gets its own section (turns/wall
     *  time/compactions), just without the params/outcome lines, rather than showing nothing. */
    @Test
    void attemptSections_omitsParamsAndOutcomeLinesWhenTheBackendFoundNoMatchingAttemptRecord() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "running", "attempts": [
                    {"session_id": "a.jsonl", "started_iso": "2026-10-14T10:00:00Z", "ended_iso": "2026-10-14T10:00:00Z",
                     "turns": 3, "compactions": 0, "tool_errors": 0, "tool_calls": {}, "seconds": 5.0}
                ]}""");

        final var sections = JobDetailView.attemptSections(stats);

        assertEquals(1, sections.size());
        assertFalse(sections.get(0).body().contains("params ("), sections.get(0).body());
        assertFalse(sections.get(0).body().contains("outcome:"), sections.get(0).body());
        assertTrue(sections.get(0).body().contains("wall time: 5.0s   model turns: 3"), sections.get(0).body());
    }

    /** #355-followup-2: found live - a raw map dump ("{"task_wall_sec":32400,...}") next to this
     *  same section's own labeled "wall time"/"model turns" lines read as noise, and "outcome:
     *  done - <a long run-on paragraph>" crammed the harness's own free-text report onto the same
     *  line as the outcome. Params get one labeled line each (paramLabel(), same convention as
     *  toolLabel()); the free-text report gets its own "detail:" line. */
    @Test
    void attemptSections_rendersParamsAsLabeledLinesAndSeparatesOutcomeFromItsFreeTextDetail() {
        final var stats = Json.MAPPER.readTree("""
                {"state": "done", "attempts": [
                    {"session_id": "a.jsonl", "started_iso": "2026-10-14T22:29:55Z", "ended_iso": "2026-10-15T00:17:50Z",
                     "turns": 44, "compactions": 0, "tool_errors": 4, "tool_calls": {},
                     "seconds": 6121.46, "params": {"task_wall_sec": 32400, "compaction_trigger": 110000},
                     "overridden": true, "attempt_rc": 0, "reported_status": "done",
                     "reported_detail": "a long run-on paragraph describing everything the model did"}
                ]}""");

        final var body = JobDetailView.attemptSections(stats).get(0).body();

        assertFalse(body.contains("{\"task_wall_sec\""), "no raw JSON map dump: " + body);
        assertTrue(body.contains("wall-clock budget (s): 32400"), body);
        assertTrue(body.contains("compaction trigger (tokens): 110000"), "compaction_trigger must get a readable label: " + body);
        assertTrue(body.contains("rc: 0   outcome: done"), body);
        assertFalse(body.contains("outcome: done -"), "the free-text report must not be crammed onto the outcome line: " + body);
        assertTrue(body.contains("detail:\na long run-on paragraph"), "the free-text report gets its own line: " + body);
    }

    /** #355-followup-2: the exact T3 attempt-2 report, seen live - one dense run-on paragraph
     *  packed with parenthetical asides, then a verification sentence. detailLines() must split
     *  on sentence boundaries, then on top-level ("; "/", ") separators WITHIN each sentence -
     *  never on a separator sitting inside a parenthetical, like "(32-hex, low-16-bits zero, ...)"
     *  or "(ErrorCodes enum; allCodes() asserts exact frozen set)" - so each real top-level item
     *  (ErrorEnvelope, TraceId+Propagation, TraceContext, StructuredLog, the two verification
     *  clauses) lands on its own line instead of one unreadable wall of text. */
    private static final String T3_ATTEMPT_2_DETAIL = ":common kernel (kernel/ package, no Spring): ErrorEnvelope {code,message} with hand-rolled JSON "
            + "render/parse + all 13 §5.6 stable codes (ErrorCodes enum; allCodes() asserts exact frozen set), TraceId W3C "
            + "128-bit gen/parse/traceparent+flags (32-hex, low-16-bits zero, non-zero guard, case-insensitive) + Propagation "
            + "traceparent helper, TraceContext ThreadLocal scope stack (nested open/close restores outer, currentOrNull/current), "
            + "StructuredLog JSON line carrying service/traceId/accountId/level/operation/message with grammar-validated "
            + "service+operation+accountId and JSON-escaped message. 35 new :common tests all pass (19 ErrorEnvelope incl. "
            + "per-code @EnumSource + escape/unicode round-trip, 8 TracePropagation incl. 2000-distinct-ids + hop-to-hop "
            + "propagator, 8 StructuredLog asserting parsed values/quote-structure); :common:build green and full root "
            + "./gradlew build green (all 5 suites rerun under JDK21).";

    private static JsonNode statsWithOneAttemptDetail(final String detail) {
        return Json.MAPPER.valueToTree(Map.of("state", "done", "attempts", List.of(
                Map.of("turns", 35, "compactions", 0, "tool_errors", 0, "tool_calls", Map.of(), "seconds", 6475.164,
                        "attempt_rc", 0, "reported_status", "done", "reported_detail", detail))));
    }

    @Test
    void attemptSections_breaksTheModelsOwnFreeTextReportIntoOneLinePerTopLevelClause() {
        final var body = JobDetailView.attemptSections(statsWithOneAttemptDetail(T3_ATTEMPT_2_DETAIL)).get(0).body();

        assertTrue(body.contains("\n  TraceId W3C 128-bit"), "TraceId+Propagation is its own line, not split by the commas inside its own parens: " + body);
        assertFalse(body.contains("\n  low-16-bits zero"), "a comma INSIDE a parenthetical must not become its own line: " + body);
        assertFalse(body.contains("\n  allCodes() asserts exact frozen set"), "a semicolon inside a parenthetical must not split either: " + body);
        assertTrue(body.contains("\n  StructuredLog JSON line carrying"), body);
        assertTrue(body.contains("\n  35 new :common tests all pass"), "the verification sentence's two top-level clauses split on their semicolon: " + body);
        assertTrue(body.contains("\n  :common:build green and full root"), body);
    }

    /** the operator asked to lead with verification (did it work?) and keep implementation
     *  detail (how?) secondary - found live on T3: the test-count/build-green lines were buried
     *  at the bottom of the implementation narrative instead of answering that question first. */
    @Test
    void attemptSections_leadsWithVerificationLinesBeforeImplementationDetail() {
        final var body = JobDetailView.attemptSections(statsWithOneAttemptDetail(T3_ATTEMPT_2_DETAIL)).get(0).body();

        final int verificationLabelIdx = body.indexOf("verification:");
        final int implementationLabelIdx = body.indexOf("implementation:");
        final int testsLineIdx = body.indexOf("35 new :common tests all pass");
        final int buildLineIdx = body.indexOf(":common:build green");
        final int errorEnvelopeLineIdx = body.indexOf(":common kernel");
        final int traceIdLineIdx = body.indexOf("TraceId W3C 128-bit");
        assertTrue(verificationLabelIdx >= 0 && implementationLabelIdx >= 0, body);
        assertTrue(verificationLabelIdx < implementationLabelIdx, "verification section must lead: " + body);
        assertTrue(testsLineIdx > verificationLabelIdx && testsLineIdx < implementationLabelIdx, body);
        assertTrue(buildLineIdx > verificationLabelIdx && buildLineIdx < implementationLabelIdx, body);
        assertTrue(errorEnvelopeLineIdx > implementationLabelIdx, "implementation detail must come after verification: " + body);
        assertTrue(traceIdLineIdx > implementationLabelIdx, body);
    }

    /** a report with no verification signal at all (or, symmetrically, one that's entirely
     *  verification-flavored) has nothing meaningful to split - must render as a plain,
     *  unlabeled list rather than wrapping a single uniform group in a label that adds nothing. */
    @Test
    void attemptSections_doesNotLabelOrSplitWhenEveryLineIsTheSameKind() {
        final String allImplementation = "TraceId W3C 128-bit gen/parse. TraceContext ThreadLocal scope stack.";
        final var body = JobDetailView.attemptSections(statsWithOneAttemptDetail(allImplementation)).get(0).body();

        assertFalse(body.contains("verification:"), body);
        assertFalse(body.contains("implementation:"), body);
        assertTrue(body.contains("TraceId W3C 128-bit gen/parse"), body);
    }

    @Test
    void attemptSections_isEmptyWhenStatsHasNoAttemptsField() {
        assertTrue(JobDetailView.attemptSections(Json.MAPPER.readTree("{\"state\": \"not_started\"}")).isEmpty());
    }
}
