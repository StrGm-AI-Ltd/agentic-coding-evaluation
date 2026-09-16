package com.agentbench.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live wire-mapping tests against a running agentbench-trading-service. Tagged "live" and
 * run by the separate testLive Gradle task; the default test task stays deterministic.
 * The base URL can be overridden with -Dagentbench.service.base-url=…
 */
@Tag("live")
class ServiceClientMappingTest {

    private final ServiceClient client = new ServiceClient(
            new ServiceProperties(
                    System.getProperty("agentbench.service.base-url", "http://127.0.0.1:8765"),
                    Duration.ofSeconds(2), Duration.ofSeconds(15)),
            RestClient.builder());

    private List<Api.Run> runsOrSkip() {
        try {
            List<Api.Run> runs = client.runs(null, null, null, null, null);
            Assumptions.assumeTrue(runs != null, "no runs payload");
            return runs;
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "agentbench-service not running: " + e.getMessage());
            return List.of();
        }
    }

    @Test
    void runsListMapsAndUnknownPropertiesAreIgnored() {
        List<Api.Run> runs = runsOrSkip();
        assertFalse(runs.isEmpty(), "expected at least one imported run");
        Api.Run first = runs.get(0);
        assertNotNull(first.run_id());
        assertNotNull(first.task());
        assertNotNull(first.started());

        List<Api.Run> filtered = client.runs(first.task(), first.model(), null, null, null);
        assertTrue(filtered.size() <= runs.size(), "filters must narrow, not widen");
    }

    @Test
    void runDetailCarriesChecksManifestAndOracle() {
        List<Api.Run> runs = runsOrSkip();
        Api.Run run = client.run(runs.get(0).run_id());
        assertNotNull(run.checks());
        if (!run.checks().isEmpty()) {
            assertNotNull(run.checks().get(0).check_id());
            assertNotNull(run.checks().get(0).status());
        }
        assertNotNull(run.manifest());
        assertNotNull(run.oracle());
    }

    @Test
    void groupsJobsExperimentsPreflightMap() {
        runsOrSkip(); // skip everything when the service is down

        Api.GroupResponse groups = client.groups();
        assertNotNull(groups);
        int allGroups = (groups.ranked() == null ? 0 : groups.ranked().size())
                + (groups.indicative() == null ? 0 : groups.indicative().size());
        for (Api.Group group : groups.ranked() == null ? List.<Api.Group>of() : groups.ranked()) {
            assertNotNull(group.summary().k());
            assertNotNull(group.run_ids());
            assertFalse(group.run_ids().isEmpty());
        }

        List<Api.Job> jobs = client.jobs();
        for (Api.Job job : jobs) {
            assertNotNull(job.status());
            assertNotNull(job.argv());
        }

        List<Api.Experiment> experiments = client.experiments();
        for (Api.Experiment experiment : experiments) {
            assertNotNull(experiment.name());
        }

        Api.PreflightState preflight = client.preflight();
        assertNotNull(preflight);
        assertNotNull(preflight.running());
    }
}
