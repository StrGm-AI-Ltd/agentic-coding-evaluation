package com.agentbench.ui;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

/** Shared test fixtures for the wire records and HTTP error wrappers. */
final class ApiFixtures {

    private ApiFixtures() {
    }

    /** A run with everything null except the id and poolable flag (the list-endpoint minimum). */
    static Api.Run run(String runId, boolean poolable) {
        return new Api.Run(runId, null, null, null, null, null, null, null, poolable,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static Api.Run run(String runId) {
        return run(runId, true);
    }

    static RestClientResponseException http(int code) {
        return new RestClientResponseException("boom", code, "status", new HttpHeaders(),
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
