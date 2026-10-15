package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
}
