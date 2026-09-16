package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run page's not-imported gate: only a real 404 opens the self-service panel. */
class RunDetailViewTest {

    @Test
    void notImportedOn404Only() {
        assertTrue(RunDetailView.isNotImported(ApiFixtures.http(404)),
                "404 = the run is not imported (yet)");
    }

    @Test
    void otherFailuresAreNotTheImportGate() {
        assertFalse(RunDetailView.isNotImported(ApiFixtures.http(422)));
        assertFalse(RunDetailView.isNotImported(ApiFixtures.http(500)));
        assertFalse(RunDetailView.isNotImported(new ResourceAccessException("service down")));
        assertFalse(RunDetailView.isNotImported(new IllegalStateException("unrelated")));
        assertFalse(RunDetailView.isNotImported(null));
    }
}
