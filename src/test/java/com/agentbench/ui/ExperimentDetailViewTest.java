package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The experiment page's blocked-job recovery: reason surfacing and bulk requeue. */
class ExperimentDetailViewTest {

    @Test
    void blockedReasonsFetchOnlyBlockedJobs() {
        ServiceClient client = mock(ServiceClient.class);
        List<Api.ExperimentJob> jobs = List.of(
                ApiFixtures.experimentJob(31, "A", 1, "ab-r1", "blocked"),
                ApiFixtures.experimentJob(32, "B", 1, "ab-r1", "succeeded"),
                ApiFixtures.experimentJob(33, "A", 2, "ab-r2", "blocked"));
        when(client.job(31L)).thenReturn(ApiFixtures.job(31, "blocked",
                "runner/ or oracle/ has uncommitted changes:\n M runner/agent_loop.py"));
        when(client.job(33L)).thenReturn(ApiFixtures.job(33, "blocked", null));
        when(client.job(35L)).thenThrow(new RuntimeException("fetch failed"));

        Map<Long, String> reasons = ExperimentDetailView.blockedReasons(client, jobs);

        assertEquals(Map.of(31L, "runner/ or oracle/ has uncommitted changes:\n M runner/agent_loop.py"),
                reasons, "only the blocked job with a reason is surfaced");
        verify(client, never()).job(32L);
    }

    @Test
    void blockedReasonsTolerateFetchFailures() {
        ServiceClient client = mock(ServiceClient.class);
        when(client.job(anyLong())).thenThrow(new RuntimeException("service hiccup"));
        Map<Long, String> reasons = ExperimentDetailView.blockedReasons(client,
                List.of(ApiFixtures.experimentJob(31, "A", 1, "ab-r1", "blocked")));
        assertTrue(reasons.isEmpty(), "a failed fetch just leaves the tooltip absent");
    }

    @Test
    void requeueAllBlockedRequeuesOnlyBlockedAndReportsFailures() {
        ServiceClient client = mock(ServiceClient.class);
        List<Api.ExperimentJob> jobs = List.of(
                ApiFixtures.experimentJob(31, "A", 1, "ab-r1", "blocked"),
                ApiFixtures.experimentJob(32, "B", 1, "ab-r1", "blocked"),
                ApiFixtures.experimentJob(33, "A", 2, "ab-r2", "succeeded"));
        when(client.requeue(31L)).thenReturn(ApiFixtures.job(31, "queued", null));
        when(client.requeue(32L)).thenThrow(ApiFixtures.http(409));
        when(client.errorText(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation ->
                        "results/ab-r1 exists and a re-run would mix its files");

        String failures = ExperimentDetailView.requeueAllBlocked(client, jobs);

        assertTrue(failures.contains("job #32"), failures);
        assertTrue(failures.contains("mix its files"), "the per-job error text is included");
        verify(client, never()).requeue(33L);
    }

    @Test
    void requeueAllBlockedReturnsNullOnFullSuccess() {
        ServiceClient client = mock(ServiceClient.class);
        List<Api.ExperimentJob> jobs = List.of(
                ApiFixtures.experimentJob(31, "A", 1, "ab-r1", "blocked"),
                ApiFixtures.experimentJob(35, "A", 3, "ab-r3", "blocked"));
        when(client.requeue(anyLong())).thenReturn(ApiFixtures.job(31, "queued", null));
        when(client.requeue(35L)).thenReturn(ApiFixtures.job(35, "queued", null));
        assertNull(ExperimentDetailView.requeueAllBlocked(client, jobs));
    }
}
