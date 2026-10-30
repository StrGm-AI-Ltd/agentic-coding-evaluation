package com.strgmai.ace.service.oracle.checks;

import com.strgmai.ace.service.oracle.CheckId;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #281, found live: each F-check scenario appends its own HTTP exchanges to the shared suite.calls
 *  list as it runs, and the dispatch loop in ComposeChecks.run() walks IDS in its own iteration
 *  order - so IDS's declared order IS the order the scenarios actually run in, not just a display
 *  concern. F6 (f6ContractConformance) grades every observed response so far against the contract,
 *  so it must run AFTER F7/F8/F9 - otherwise it silently grades an incomplete set of exchanges,
 *  never seeing F7's deposit-rounding call, F8's asOf-boundary call, or F9's idempotency-key-repeat
 *  call (the only call that can ever produce a "POST /orders 200" response). */
class ComposeChecksTest {

    @Test
    void f6RunsAfterF7F8AndF9SoItGradesTheirResponsesToo() {
        final List<CheckId> order = new ArrayList<>(ComposeChecks.IDS);
        final int f6 = order.indexOf(CheckId.F6);
        assertTrue(f6 > order.indexOf(CheckId.F7), "F6 must run after F7: " + order);
        assertTrue(f6 > order.indexOf(CheckId.F8), "F6 must run after F8: " + order);
        assertTrue(f6 > order.indexOf(CheckId.F9), "F6 must run after F9: " + order);
    }

    private static Path composeFile(final String yaml) throws Exception {
        final var dir = Files.createTempDirectory("compose-check");
        final var file = dir.resolve("docker-compose.yml");
        Files.writeString(file, yaml);
        return file;
    }

    /** #613, found live: reproduced the exact #612 "silently mounts empty instead of erroring"
     *  symptom with a RELATIVE bind mount specifically (a Compose bind-mount source is resolved
     *  inside the third-party docker-compose CLI itself, invisible to the daemon the same way
     *  BuildChecks' pre-#612 scratch copy was) - absoluteComposePaths() already caught the
     *  absolute-path flavor (R5 C-19); this pins that it now catches the relative flavor too. */
    @Test
    void absoluteComposePaths_flagsARelativeBindMountSource() throws Exception {
        final var cf = composeFile("""
                services:
                  app:
                    image: alpine
                    volumes:
                      - ./data:/mounted
                """);

        final var hits = ComposeChecks.absoluteComposePaths(cf);

        assertEquals(1, hits.size(), hits.toString());
        assertTrue(hits.get(0).contains("./data"), hits.toString());
    }

    @Test
    void absoluteComposePaths_flagsAnAbsoluteBindMountSource() throws Exception {
        final var cf = composeFile("""
                services:
                  app:
                    image: alpine
                    volumes:
                      - /etc/passwd:/mounted
                """);

        final var hits = ComposeChecks.absoluteComposePaths(cf);

        assertEquals(1, hits.size(), hits.toString());
    }

    /** A NAMED volume (bare `<name>:/path>`, Compose's own rule for "not a bind mount") is daemon-
     *  managed and never touches the scratch copy's own host-path-visibility problem - must never
     *  be flagged, or every spec-compliant T16 solution (which explicitly asks for a named volume)
     *  would be wrongly failed. */
    @Test
    void absoluteComposePaths_neverFlagsANamedVolume() throws Exception {
        final var cf = composeFile("""
                services:
                  db:
                    image: postgres:16
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                volumes:
                  pgdata:
                """);

        assertTrue(ComposeChecks.absoluteComposePaths(cf).isEmpty(), ComposeChecks.absoluteComposePaths(cf).toString());
    }

    /** A RELATIVE build context (the common, legitimate multi-service-subdirectory pattern) stays
     *  within the repository and must never be flagged - unlike a bind-mount source, it's read by
     *  the docker-compose CLI from its own local filesystem view and tar-streamed to the daemon's
     *  Build API, never daemon-resolved as a path string (confirmed live - see this method's own
     *  javadoc in ComposeChecks). Only an ABSOLUTE context/dockerfile escapes the copy. */
    @Test
    void absoluteComposePaths_neverFlagsARelativeBuildContext() throws Exception {
        final var cf = composeFile("""
                services:
                  app:
                    build:
                      context: ./backend
                      dockerfile: ./backend/Dockerfile
                """);

        assertTrue(ComposeChecks.absoluteComposePaths(cf).isEmpty());
    }

    @Test
    void absoluteComposePaths_flagsAnAbsoluteBuildContext() throws Exception {
        final var cf = composeFile("""
                services:
                  app:
                    build:
                      context: /home/agent/workspace/backend
                """);

        assertEquals(1, ComposeChecks.absoluteComposePaths(cf).size());
    }
}
