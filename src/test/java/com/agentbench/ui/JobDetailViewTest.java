package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

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
        ServiceClient client = mock(ServiceClient.class);
        when(client.run("he-2026-…-cancelled-r1")).thenThrow(ApiFixtures.http(404));
        assertFalse(JobDetailView.isRunImported(client, "he-2026-…-cancelled-r1"),
                "the 404 the queue rows hit — the run was never imported");
    }

    @Test
    void runProbe_successAndNon404Errors() {
        ServiceClient client = mock(ServiceClient.class);
        when(client.run("imported")).thenReturn(ApiFixtures.run("imported"));
        assertTrue(JobDetailView.isRunImported(client, "imported"));

        when(client.run("broken")).thenThrow(ApiFixtures.http(500));
        assertTrue(JobDetailView.isRunImported(client, "broken"),
                "a 500 is a service problem, not the import gate");

        when(client.run("down")).thenThrow(new ResourceAccessException("down"));
        assertFalse(JobDetailView.isRunImported(client, "down"),
                "network trouble: do not offer a link we could not verify");
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
        assertEquals(JobDetailView.runNotImportedHint("running"),
                JobDetailView.runNotImportedHint("blocked"));

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
}
