package com.strgmai.ace.service.oracle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The gaming-resistance gate this port was missing: a known-good "positive control" that must
 *  score near-perfectly (it finds the oracle's own false-negative bugs, design rule 3) and a set of
 *  adversarial "cheat" fixtures that must each stay well below a passing score (they find the
 *  oracle's false-positive bugs). Fixtures are the REAL, already-verified Java/Spring Boot source
 *  trees from the original Python harness's fixtures/ (fixtures/positive, fixtures/adversarial/cheat*)
 *  copied verbatim into this repo: the frozen contract mandates the fixtures be Java/Spring Boot
 *  regardless of which harness scores them, so nothing about them is Python-specific.
 *
 *  One deliberate adaptation from the original design: the original's `--selftest` runs OFFLINE
 *  (--skip-docker, partial denominator) with a separate `--selftest --with-docker` pass for the
 *  gated checks, and EXPECTATIONS.json's max/min_partial_pct ceilings are calibrated against that
 *  offline-only denominator. This class instead calls the FULL RunOracle.score() in one pass (Docker
 *  is available in this environment, and RunOracle already auto-detects and includes the build/
 *  compose/black-box checks when it is) — a stronger, not weaker, test. Because the denominator
 *  differs from the original's offline-only one, the exact max/min_partial_pct numbers are not
 *  reused; instead this asserts the same underlying invariants EXPECTATIONS.json encodes (which
 *  specific ids must PASS or FAIL, and for cheats, that the specific detail text names the offence)
 *  plus a coarse score sanity bound. Needs a live Docker daemon; excluded from the default `test`
 *  task like DockerApplicationIT. */
@Tag("docker")
class SelftestIT {

    private static final Path FIXTURES = Path.of("fixtures");

    private static Path copyFixture(final String name) throws IOException {
        final Path src = FIXTURES.resolve(name);
        assertTrue(Files.isDirectory(src), "fixture missing: " + src.toAbsolutePath());
        final var dst = Files.createTempDirectory("selftest-" + name.replace('/', '-'));
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                final Path rel = src.relativize(p);
                final Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES); }
            }
        }
        return dst;
    }

    private static void deleteRecursive(final Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Test
    void positiveControlPassesEveryRequiredCheck() throws Exception {
        final Path ws = copyFixture("positive");
        try {
            final Map<String, Object> rep = new RunOracle().score(ws, "L7_full_platform", null);
            final Map<String, CheckStatus> status = statusById(rep);
            // offline must_pass (EXPECTATIONS.json positive_control.must_pass)
            for (CheckId id : new CheckId[]{CheckId.S1, CheckId.S2, CheckId.S3, CheckId.S4, CheckId.S5, CheckId.S6, CheckId.S7,
                    CheckId.S8, CheckId.S9, CheckId.P1, CheckId.P2, CheckId.M1, CheckId.M2, CheckId.M3, CheckId.M4})
                assertEquals(CheckStatus.PASS, status.get(id.name()), id + " should PASS on the positive control: " + detailOf(rep, id));
            // docker-gated must_pass (EXPECTATIONS.json positive_control.docker.must_pass)
            for (CheckId id : new CheckId[]{CheckId.B1, CheckId.B2, CheckId.B3, CheckId.C1, CheckId.C2,
                    CheckId.F1, CheckId.F2, CheckId.F3, CheckId.F4, CheckId.F5, CheckId.F6, CheckId.F7, CheckId.F8, CheckId.F9})
                assertEquals(CheckStatus.PASS, status.get(id.name()), id + " should PASS on the positive control: " + detailOf(rep, id));
            // P3 is deliberately excluded (no runner manifest here - see class javadoc and RunOracle.p3Scan)
            final Object pct = rep.get("weighted_score_pct");
            assertNotNull(pct, "expected a full (non-partial) score - got: " + rep.get("note"));
            assertTrue(((Number) pct).doubleValue() >= 95.0, "positive control scored only " + pct + "% - " + rep.get("by_category"));
        } finally { deleteRecursive(ws); }
    }

    @Test
    void cheatStubsWithNoJavaStaysLow() throws Exception {
        assertCheatFails("adversarial/cheatE_stubs_no_java", "L7_full_platform", Map.of());
    }

    @Test
    void cheatCommentOnlyStaysLow() throws Exception {
        assertCheatFails("adversarial/cheatA_comment_only", "L7_full_platform", Map.of());
    }

    @Test
    void cheatBugsMovedToTestPackageAreStillCaught() throws Exception {
        assertCheatFails("adversarial/cheatB_bugs_in_test_pkg", "L7_full_platform", Map.of(CheckId.M1, CheckStatus.FAIL));
    }

    @Test
    void cheatRenamedMoneyFieldsAreStillCaught() throws Exception {
        assertCheatFails("adversarial/cheatC_renamed_fields", "L7_full_platform", Map.of(CheckId.M4, CheckStatus.FAIL));
    }

    @Test
    void cheatDirNamedJavaDoesNotCrashTheCheckerOrFalselyPass() throws Exception {
        final Path ws = copyFixture("adversarial/cheatD_dir_named_java");
        try {
            final Map<String, Object> rep = new RunOracle().score(ws, "L7_full_platform", null);
            final Map<String, CheckStatus> status = statusById(rep);
            assertNotEquals(CheckStatus.PASS, status.get("M1"), "M1 must not silently PASS on this fixture: " + detailOf(rep, CheckId.M1));
            assertLowScore(rep);
        } finally { deleteRecursive(ws); }
    }

    @Test
    void cheatPrebuiltJarFailsComposeOnHostBuiltArtifact() throws Exception {
        final Path ws = copyFixture("adversarial/cheatF_prebuilt_jar");
        try {
            final Map<String, Object> rep = new RunOracle().score(ws, "L6_compose_health", null);
            final Map<String, CheckStatus> status = statusById(rep);
            assertEquals(CheckStatus.FAIL, status.get("C2"), "C2 should FAIL a compose build that ships a host-built jar: " + detailOf(rep, CheckId.C2));
            final String detail = detailOf(rep, CheckId.C2);
            assertTrue(detail != null && detail.contains("host-built artefact"), "C2's detail should name the offence, was: " + detail);
        } finally { deleteRecursive(ws); }
    }

    private static void assertCheatFails(final String fixture, final String task, final Map<CheckId, CheckStatus> mustHave) throws Exception {
        final Path ws = copyFixture(fixture);
        try {
            final Map<String, Object> rep = new RunOracle().score(ws, task, null);
            final Map<String, CheckStatus> status = statusById(rep);
            mustHave.forEach((id, expected) -> assertEquals(expected, status.get(id.name()), id + ": " + detailOf(rep, id)));
            assertLowScore(rep);
        } finally { deleteRecursive(ws); }
    }

    @SuppressWarnings("unchecked")
    private static void assertLowScore(final Map<String, Object> rep) {
        final Object weighted = rep.get("weighted_score_pct");
        final Object partial = rep.get("partial_score_pct");
        final double pct = weighted != null ? ((Number) weighted).doubleValue() : partial != null ? ((Number) partial).doubleValue() : 0.0;
        assertTrue(pct < 70.0, "a cheat fixture should not score anywhere near a passing implementation, got " + pct + "%: " + rep.get("by_category"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CheckStatus> statusById(final Map<String, Object> rep) {
        final var out = new java.util.LinkedHashMap<String, CheckStatus>();
        for (Object o : (Iterable<Object>) rep.get("results")) {
            final Map<String, Object> r = (Map<String, Object>) o;
            out.put((String) r.get("id"), CheckStatus.valueOf((String) r.get("status")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String detailOf(final Map<String, Object> rep, final CheckId id) {
        for (Object o : (Iterable<Object>) rep.get("results")) {
            final Map<String, Object> r = (Map<String, Object>) o;
            if (id.name().equals(r.get("id"))) return String.valueOf(r.get("detail"));
        }
        return null;
    }
}
