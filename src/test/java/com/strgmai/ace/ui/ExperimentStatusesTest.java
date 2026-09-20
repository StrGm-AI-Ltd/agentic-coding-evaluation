package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The 2026-09-16 "still queued while a job runs" report, pinned. */
class ExperimentStatusesTest {

    @Test
    void queuedTableStatusIsDerivedFromJobs() {
        assertEquals("running", ExperimentStatuses.effective("queued", List.of("running", "queued", "queued")),
                "the reported case: one job running");
        assertEquals("running", ExperimentStatuses.effective("queued", List.of("waiting_lock", "queued")),
                "waiting for the lock is still in flight");
        assertEquals("blocked", ExperimentStatuses.effective("queued", List.of("blocked", "blocked")),
                "only blocked jobs: nothing progresses until a requeue");
        assertEquals("blocked", ExperimentStatuses.effective("queued", List.of("blocked", "queued")));
        assertEquals("queued", ExperimentStatuses.effective("queued", List.of("queued", "queued")),
                "all waiting their turn");
    }

    @Test
    void nonQueuedTableStatusesPassThrough() {
        assertEquals("finished", ExperimentStatuses.effective("finished", List.of("succeeded")));
        assertEquals("cancelled", ExperimentStatuses.effective("cancelled", List.of("cancelled")));
    }

    @Test
    void degenerateJobListsFallBackToTheTableStatus() {
        assertEquals("queued", ExperimentStatuses.effective("queued", null));
        assertEquals("queued", ExperimentStatuses.effective("queued", List.of()));
    }
}
