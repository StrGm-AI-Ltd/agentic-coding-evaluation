package com.agentbench.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void queueJobsMayBeUnimportedRuns404NotImported() {
        List<Api.Job> jobs;
        try {
            jobs = client.jobs();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "agentbench-service not running: " + e.getMessage());
            return;
        }
        Api.Job unimported = null;
        for (Api.Job job : jobs) {
            try {
                client.run(job.run_id());
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404) {
                    unimported = job;
                    break;
                }
                throw e; // 5xx / other status: surface it, don't call it "unimported"
            }
        }
        Assumptions.assumeTrue(unimported != null,
                "no unimported queue job present right now (the 2026-09-16 dead-end bug needs one)");
        final Api.Job job = unimported;
        RestClientResponseException e = assertThrows(RestClientResponseException.class,
                () -> client.run(job.run_id()));
        assertEquals(404, e.getStatusCode().value());
        assertTrue(client.errorText(e).endsWith("is not imported"),
                "the exact message the queue rows hit");
    }

    /** The 2026-09-16 runs-page crash on real manifests: provenance.quantization is an
     *  11-property object; rendering a real run's provenance must not throw. */
    @Test
    void runDetailProvenanceRendersFromRealManifest() {
        List<Api.Run> runs;
        try {
            runs = client.runs(null, null, null, null, null);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "agentbench-service not running: " + e.getMessage());
            return;
        }
        Assumptions.assumeTrue(!runs.isEmpty(), "no runs imported");
        Api.Run run = client.run(runs.get(0).run_id());
        List<String> lines = RunDetailView.provenanceLines(run.manifest(), run.results_dir());
        assertFalse(lines.isEmpty(), "a real manifest produces provenance lines");
        for (String line : lines) {
            assertTrue(line.length() < 10_000, "no runaway line: "
                    + line.substring(0, Math.min(40, line.length())) + "…");
        }
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
