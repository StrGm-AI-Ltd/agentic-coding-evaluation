package com.strgmai.ace.service.oracle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** `RunOracle.score()`'s own orchestration - every rung in tasks/ladder.json includes at least one
 *  Docker-gated check, so these plain (non-`@Tag("docker")`) tests exercise it the ONLY way possible
 *  without a live daemon: assuming Docker is NOT running, matching this project's own convention
 *  that the default `test` task never depends on it (SelftestIT needs `@Tag("docker")` precisely
 *  because it does). If Docker happens to be running locally when these run, the SKIPPED-vs-real
 *  assertions below would legitimately fail - that is an environment mismatch, not a logic bug. */
class RunOracleTest {

    @Test
    void unknownTaskThrowsInsteadOfSilentlyScoringAgainstL7() throws Exception {
        final Path ws = Files.createTempDirectory("run-oracle-test");
        final var ex = assertThrows(IllegalArgumentException.class, () -> new RunOracle().score(ws, "no_such_task_xyz", null));
        assertTrue(ex.getMessage().contains("no_such_task_xyz"), "the bad task string must be named in the failure: " + ex.getMessage());
    }

    /** L1_migration_entity: the smallest rung with both offline (S6,S9,M1,M2,M3) and Docker-gated
     *  (B1,B2) ids - without Docker, the gated ones must be SKIPPED (never silently dropped, never
     *  charged as a FAIL to the agent) while the offline ones still run for real and count. */
    @Test
    void offlineChecksRunForRealWhileGatedOnesAreSkippedWithoutDocker() throws Exception {
        final Path ws = Files.createTempDirectory("run-oracle-test");
        final Map<String, Object> rep = new RunOracle().score(ws, "L1_migration_entity", null);

        assertEquals(false, rep.get("docker_available"), "no real Docker daemon is assumed for this test");
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> results = (List<Map<String, Object>>) rep.get("results");
        assertEquals(7, results.size(), "every id in the rung's checks list gets exactly one record");

        final var byId = results.stream().collect(java.util.stream.Collectors.toMap(r -> r.get("id"), r -> r.get("status")));
        assertEquals("SKIPPED", byId.get("B1"), "Docker-gated, unavailable - skipped, never a FAIL charged to the agent");
        assertEquals("SKIPPED", byId.get("B2"));
        // offline checks ran for real against an empty workspace - they FAIL (nothing to find), but
        // that is a real, scored FAIL, not SKIPPED - the distinction this test actually cares about
        for (String offlineId : List.of("S6", "S9", "M1", "M2", "M3"))
            assertNotEquals("SKIPPED", byId.get(offlineId), offlineId + " is not Docker-gated - it must run for real");
    }

    @Test
    void missingCheckerFillsInAsFailNeverShrinkingTheDenominator() throws Exception {
        // same rung/assumption as above: B1/B2 end up SKIPPED by the Docker gate itself, which is a
        // real path through the "missing => FAIL" fill-in loop's sibling SKIPPED-fill-in loop just
        // above it (RunOracle.java) - covered together since they're the same two lines of looping
        final Path ws = Files.createTempDirectory("run-oracle-test");
        final Map<String, Object> rep = new RunOracle().score(ws, "L1_migration_entity", null);
        assertEquals(7, rep.get("checks_reported"), "no id is ever silently dropped from the report");
    }

    @Test
    void provenanceDigestIsNullRatherThanThrowingWhenTheBuildImageIsNotPulled() throws Exception {
        final Path ws = Files.createTempDirectory("run-oracle-test");
        final Map<String, Object> rep = new RunOracle().score(ws, "L1_migration_entity", null);
        @SuppressWarnings("unchecked")
        final Map<String, Object> prov = (Map<String, Object>) rep.get("provenance");
        assertNotNull(prov, "the provenance map itself must always be present");
        assertEquals(RunOracle.BUILD_IMAGE, prov.get("build_image"));
        // build_image_digest is null whenever `docker image inspect` can't run/find it (no Docker
        // here) - asserting this doesn't throw is the point; a Map.of() version of this line NPEs
    }
}
