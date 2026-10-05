package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new JobLiveState.SessionRow(null, null, null, null, null, null, null, null, null, null, null),
                new JobLiveState.SessionRow("a", "T1", "task T1", "stop", 90.0, 20.0, 120.0, 500L, 30.0, 90.0, "2026-09-15T02:00:00Z"),
                new JobLiveState.SessionRow("b", "T2", "task T2", null, null, null, null, null, null, null, "2026-09-14T23:00:00+01:00"));
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
        return new JobLiveState.SessionRow("sid", "sid", description, endedStage, null, null, null, null, null, null, null);
    }

    private static Api.PlanTask planTask(final String id, final boolean implemented, final boolean handoffDone) {
        return new Api.PlanTask(0, id, "", "", List.of(), implemented, handoffDone);
    }

    @Test
    void taskStatus_noMatchingSessionIsNotStartedUnlessTerminal() {
        assertEquals("not started", JobDetailView.taskStatus(List.of(), planTask("T2", false, false), false));
        assertEquals("see Run detail", JobDetailView.taskStatus(List.of(), planTask("T2", false, false), true),
                "a terminal job with no live session for this task must not claim it was never started");
    }

    @Test
    void taskStatus_runningWhileItsSessionHasNoEndedStageYet() {
        final var sessions = List.of(sessionRow("Task T2", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T2", false, false), false));
    }

    @Test
    void taskStatus_reportsTheSessionsOwnFinishReasonOnceEnded() {
        final var sessions = List.of(sessionRow("Task T2 — implement the API", "stop"));
        assertEquals("stop", JobDetailView.taskStatus(sessions, planTask("T2", false, false), false));
    }

    /** "T1" must never match a session actually for "T10"/"T11" - both share "Task T1" as a string
     *  prefix, but not as the exact anchored match taskStatus requires. */
    @Test
    void taskStatus_doesNotFalseMatchATaskIdThatIsAPrefixOfAnothersId() {
        final var sessions = List.of(sessionRow("Task T10 (handoff)", "stop"));
        assertEquals("not started", JobDetailView.taskStatus(sessions, planTask("T1", false, false), false));
    }

    /** a resumed/retried task gets a fresh session sharing the same description prefix - the LAST
     *  one (the current attempt) wins, not the abandoned first attempt. */
    @Test
    void taskStatus_theLastMatchingSessionWinsOverAnEarlierAbandonedAttempt() {
        final var sessions = List.of(sessionRow("Task T3 (handoff)", "interrupted"), sessionRow("Task T3 (handoff)", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T3", false, false), false));
    }

    /** #254, found live: a task finished in a PRIOR attempt is resumed straight past (R18) and so
     *  never produces a live session event in a fresh page view - session data alone always read a
     *  genuinely-finished task as "not started". Falls back to the workspace's own git tags. */
    @Test
    void taskStatus_fallsBackToTheWorkspacesOwnTagsWhenThereIsNoLiveSessionAtAll() {
        assertEquals("done", JobDetailView.taskStatus(List.of(), planTask("T1", true, true), false));
        assertEquals("implemented (handoff pending)", JobDetailView.taskStatus(List.of(), planTask("T1", true, false), false));
        assertEquals("not started", JobDetailView.taskStatus(List.of(), planTask("T1", false, false), false));
    }

    /** a live session, even a long-abandoned/interrupted one, still outranks the tag fallback - the
     *  tags are only consulted when there is NO live session data for this task at all. */
    @Test
    void taskStatus_aLiveSessionOutranksTheTagFallback() {
        final var sessions = List.of(sessionRow("Task T2", null));
        assertEquals("running", JobDetailView.taskStatus(sessions, planTask("T2", true, true), false),
                "a currently-running session must win even if the tags also say done");
    }
}
