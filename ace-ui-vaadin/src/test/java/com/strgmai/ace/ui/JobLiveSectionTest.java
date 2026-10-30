package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #528 (Phase 1): the REQUESTS_BY_ and SESSIONS_BY_ comparators moved here from JobDetailViewTest
 *  along with the comparators themselves - these two pin the same regressions they always did (the
 *  live-requests ClassCastException, and the sessions grid's own null-safety once it became
 *  sortable by every column), just against their new home. */
class JobLiveSectionTest {

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
        sortAll.accept(JobLiveSection.REQUESTS_BY_STATUS);
        sortAll.accept(JobLiveSection.REQUESTS_BY_LATENCY);
        sortAll.accept(JobLiveSection.REQUESTS_BY_TTFT);
        sortAll.accept(JobLiveSection.REQUESTS_BY_TOKENS);

        final var byTs = rows.stream().sorted(JobLiveSection.REQUESTS_BY_TS)
                .map(r -> r.ts() == null ? "null" : r.ts()).toList();
        assertEquals(List.of("2026-09-14T23:00:00+01:00", "2026-09-15T02:00:00Z", "null"),
                byTs, "chronological across formats, absent last");

        final var byStatusNullsLast = rows.stream().sorted(JobLiveSection.REQUESTS_BY_STATUS)
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
        sortAll.accept(JobLiveSection.SESSIONS_BY_LABEL);
        sortAll.accept(JobLiveSection.SESSIONS_BY_DESCRIPTION);
        sortAll.accept(JobLiveSection.SESSIONS_BY_ENDED_STAGE);
        sortAll.accept(JobLiveSection.SESSIONS_BY_WALL_SEC);
        sortAll.accept(JobLiveSection.SESSIONS_BY_PREFILL_WALL);
        sortAll.accept(JobLiveSection.SESSIONS_BY_DECODE_WALL);
        sortAll.accept(JobLiveSection.SESSIONS_BY_PREFILL_TPS);
        sortAll.accept(JobLiveSection.SESSIONS_BY_DECODE_TPS);
        sortAll.accept(JobLiveSection.SESSIONS_BY_TOTAL_TOKENS);

        final var byTs = rows.stream().sorted(JobLiveSection.SESSIONS_BY_TS)
                .map(r -> r.ts() == null ? "null" : r.ts()).toList();
        assertEquals(List.of("2026-09-14T23:00:00+01:00", "2026-09-15T02:00:00Z", "null"),
                byTs, "chronological across formats, absent last");
    }

    private static Api.PlanTask planTask(final String id, final boolean stage) {
        return new Api.PlanTask(0, id, "", "", List.of(), false, false, null, null, 0, false, stage);
    }

    private static JobLiveState.SessionRow sessionRow(final String rawLabel) {
        return new JobLiveState.SessionRow("sid", "sid", "description", null, null, null, null, null, null, null, null, rawLabel, null);
    }

    /** #630-followup, found live: every Sessions-grid row used to route to JobSessionDialog
     *  regardless of what it actually was - a real task's own row, or REVIEW/TRAJECTORY_REVIEW/
     *  INTEGRATION, all landed on JobSessionDialog's own dead-end "There is no detail view for
     *  this session" message, even though JobTaskDialog's Stats tab already has real, live data
     *  for every one of them (confirmed live: REVIEW's own PLAN-grid row already opens
     *  JobTaskDialog correctly - only the SESSIONS grid's own routing was wrong). matchingTask()
     *  is the routing decision itself: a real task's own raw label ("T5"), and a pipeline stage's
     *  ("REVIEW", "TRAJECTORY_REVIEW", "p0_definition", ...) all match their own PlanTask by id
     *  exactly, stage or not. */
    @Test
    void matchingTask_findsTheRealTaskAndEveryPipelineStageAlike() {
        final var planTasks = List.of(
                planTask("T5", false),
                planTask("REVIEW", true),
                planTask("TRAJECTORY_REVIEW", true),
                planTask("INTEGRATION", true));

        assertEquals("T5", JobLiveSection.matchingTask(planTasks, sessionRow("T5")).map(Api.PlanTask::id).orElse(null));
        assertEquals("REVIEW", JobLiveSection.matchingTask(planTasks, sessionRow("REVIEW")).map(Api.PlanTask::id).orElse(null));
        assertEquals("TRAJECTORY_REVIEW", JobLiveSection.matchingTask(planTasks, sessionRow("TRAJECTORY_REVIEW")).map(Api.PlanTask::id).orElse(null));
        assertEquals("INTEGRATION", JobLiveSection.matchingTask(planTasks, sessionRow("INTEGRATION")).map(Api.PlanTask::id).orElse(null));
        // a sub-session (judge/continuation) still matches its own base task, the same way the
        // Plan grid's own live-stats accumulation already does for a real task's judge turns
        assertEquals("T5", JobLiveSection.matchingTask(planTasks, sessionRow("T5-judge-turn5")).map(Api.PlanTask::id).orElse(null));
    }

    /** No PlanTask with a matching id (planTasks hasn't loaded yet, or a genuinely stray label) -
     *  must be empty, never throw, so the caller's own fallback to JobSessionDialog is reachable. */
    @Test
    void matchingTask_isEmptyWhenNothingMatches() {
        assertTrue(JobLiveSection.matchingTask(List.of(), sessionRow("REVIEW")).isEmpty());
        assertTrue(JobLiveSection.matchingTask(List.of(planTask("T5", false)), sessionRow("T50")).isEmpty(),
                "a task id must not prefix-match a DIFFERENT task's longer id with no separator");
    }

    /** #588, found live: job 44484604 (paused, 48+ sessions, hundreds of requests) threw a
     *  permanent client-side TypeError the moment the chart's first-ever redraw jumped straight
     *  from an empty series to a large backlog delivered by the SSE replay before the first poll
     *  tick. shouldRebuildChart() is the pure decision updateRequestLatencyChart() uses to tell
     *  that case apart from a genuinely live job's own small, single-digit-per-tick growth.
     *
     *  <p>#588-followup, found live on job cd4c11a8 right after #588 itself deployed: the SAME
     *  TypeError reproduced again, this time via a SMALL initial delta - see shouldRebuildChart()'s
     *  own javadoc for the real root cause (a chart instance's very first updateSeries() call can
     *  race its own async first render(), regardless of how small that first update is). */
    @Test
    void shouldRebuildChart_trueOnlyForALargeJumpInPointCount() {
        assertTrue(JobLiveSection.shouldRebuildChart(0, 300), "cold load with an already-large backlog - the exact #588 repro shape");
        assertTrue(JobLiveSection.shouldRebuildChart(40, 500), "a reconnect replaying a burst mid-session");
        assertTrue(JobLiveSection.shouldRebuildChart(0, 1), "#588-followup: a chart's very first-ever data, however small, still races its own first render()");
        assertFalse(JobLiveSection.shouldRebuildChart(0, 0), "nothing to chart yet - never a spurious rebuild");
        assertFalse(JobLiveSection.shouldRebuildChart(40, 42), "one real request landed since the last poll tick - the common, genuinely-live case");
        assertFalse(JobLiveSection.shouldRebuildChart(40, 40), "no change at all between polls");
    }
}
