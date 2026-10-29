package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
