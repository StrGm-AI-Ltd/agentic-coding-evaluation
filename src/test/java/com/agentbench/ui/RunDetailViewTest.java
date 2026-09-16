package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run page's not-imported gate: only a real 404 opens the self-service panel. */
class RunDetailViewTest {

    private static RestClientResponseException status(int code) {
        return new RestClientResponseException("boom", code, "status", new HttpHeaders(),
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void notImportedOn404Only() {
        assertTrue(RunDetailView.isNotImported(status(404)), "404 = the run is not imported (yet)");
    }

    @Test
    void otherFailuresAreNotTheImportGate() {
        assertFalse(RunDetailView.isNotImported(status(422)));
        assertFalse(RunDetailView.isNotImported(status(500)));
        assertFalse(RunDetailView.isNotImported(new ResourceAccessException("service down")));
        assertFalse(RunDetailView.isNotImported(new IllegalStateException("unrelated")));
        assertFalse(RunDetailView.isNotImported(null));
    }
}
