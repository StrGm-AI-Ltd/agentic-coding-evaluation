package com.strgmai.ace.ui;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared test fixtures for the wire records and HTTP error wrappers. */
final class ApiFixtures {

    private ApiFixtures() {
    }

    /** A run with everything null except the id and poolable flag (the list-endpoint minimum). */
    static Api.Run run(final String runId, final boolean poolable) {
        return new Api.Run(runId, null, null, null, null, null, null, null, poolable,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static Api.Run run(final String runId) {
        return run(runId, true);
    }

    /** The same minimal run with weighted/partial scores set for the composite-sort helper. */
    static Api.Run withScores(final Api.Run base, final Double weighted, final Double partial) {
        return new Api.Run(base.run_id(), null, null, null, null, null, null, null, true,
                null, null, null, null, weighted, null, null, partial, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static RestClientResponseException http(final int code) {
        return new RestClientResponseException("boom", code, "status", new HttpHeaders(),
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** An experiment-arm job as the experiments API returns it (no blocked_reason there). Takes a
     *  plain numeric id (not a real UUID) - only equality matters for these tests. */
    static Api.ExperimentJob experimentJob(final long id, final String arm, final int repeat, final String runId, final String status) {
        return new Api.ExperimentJob(String.valueOf(id), arm, repeat, runId, status, null);
    }

    /** A full job row, minimal — for blocked-reason fetching and requeue tests. */
    static Api.Job job(final long id, final String status, final String blockedReason) {
        return new Api.Job(String.valueOf(id), null, null, null, "run", "r-" + id, List.of(), status, blockedReason,
                0, null, null, false, null, null, null, null, null);
    }
}
