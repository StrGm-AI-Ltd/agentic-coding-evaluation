package com.strgmai.ace.service.oracle.checks;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ports of test_build_logic.py's offline mutation-logic tests plus the neutered-build-script and
 *  XML-stats logic: the seeded mutation must hit SELL-side arithmetic NEAREST an anchor, on MASKED
 *  source (comments and strings never count), and the offsets must survive the masking. */
class BuildChecksTest {

    @Test
    void maskPreservesOffsetsSoMatchesApplyToTheOriginal() {
        final String src = "class A {\n  // sell: .subtract(x)\n  BigDecimal b = x.subtract(y); // -qty\n  String s = \".negate()\";\n}";
        final String masked = BuildChecks.mask(src, true);
        assertEquals(src.length(), masked.length(), "masking must preserve offsets");
        final int i = masked.indexOf(".subtract(");
        assertTrue(i > 0 && src.startsWith(".subtract(", i), "a match in the masked text must apply to the original at the same index");
        assertFalse(masked.contains("sell: .subtract"), "comments must be blanked");
        assertFalse(BuildChecks.mask(src, true).substring(src.indexOf("String s")).contains(".negate()"), "string literals must be blanked");
        assertTrue(BuildChecks.mask(src, false).contains(".negate()"), "with strings=false only comments are blanked");
    }

    @Test
    void textBlocksAreMaskedAsStrings() {
        final String src = "class A { String s = \"\"\"\n  .subtract(inside a text block)\n\"\"\"; int x = 1; }";
        assertEquals(src.length(), BuildChecks.mask(src, true).length());
        assertFalse(BuildChecks.mask(src, true).contains(".subtract(inside"));
    }

    @Test
    void pickMutationsPrefersTheNearestAnchorAndSkipsNonSellCode() throws Exception {
        final var ws = Files.createTempDirectory("mut");
        final Path src = ws.resolve("src/main/java/app/Ledger.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package app;
                public class Ledger {
                    public int far(final String side) {
                        if (side.equals("SELL")) return 0;   // anchor at the top
                        return 1;
                    }
                    public java.math.BigDecimal holdingsAt(final java.time.Instant asOf, final java.math.BigDecimal qty) {
                        return qty.subtract(java.math.BigDecimal.TEN);   // the target, near the PIT method
                    }
                }
                """);
        final Path far = ws.resolve("src/main/java/app/Far.java");
        Files.writeString(far, "package app;\npublic class Far { int x = 1 - quantityHolder; }\n");   // no anchor, no hint
        final List<BuildChecks.Candidate> cands = BuildChecks.pickMutations(ws, 4);
        assertFalse(cands.isEmpty(), "the SELL-anchored .subtract must be found");
        assertEquals("subtract->add", cands.get(0).name());
        assertTrue(cands.get(0).anchor().contains("holdingsAt"), "the PIT method anchors it: " + cands.get(0).anchor());
        assertTrue(cands.stream().noneMatch(c -> c.file().endsWith("Far.java")), "unanchored, unhinted files are not mutated");
        // applying the candidate to the ORIGINAL text must produce the mutated source
        final BuildChecks.Candidate c = cands.get(0);
        final String original = Files.readString(c.file());
        final String mutated = original.substring(0, c.start()) + c.replacement() + original.substring(c.end());
        assertTrue(mutated.contains("qty.add(") || mutated.contains(".add(java.math.BigDecimal.TEN)"), mutated);
    }

    @Test
    void aHoldingsNamedFileIsItsOwnAnchor() throws Exception {
        final var ws = Files.createTempDirectory("mut2");
        final Path f = ws.resolve("src/main/java/app/PositionService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\npublic class PositionService {\n  double net(double qty) { return scale(-qty); }\n}\n");
        final List<BuildChecks.Candidate> cands = BuildChecks.pickMutations(ws, 4);
        assertFalse(cands.isEmpty());
        assertTrue(cands.get(0).anchor().startsWith("file:"), "a holdings-shaped file name anchors its own mutants");
    }

    @Test
    void commentsNeverCountAsAnchors() throws Exception {
        final var ws = Files.createTempDirectory("mut3");
        final Path f = ws.resolve("src/main/java/app/Thing.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\n// SELL SELL SELL\npublic class Thing { int x = 1; }\n");
        assertTrue(BuildChecks.pickMutations(ws, 4).isEmpty(), "a SELL only in a comment anchors nothing");
    }

    @Test
    void aNeuteredTestTaskIsCaught() throws Exception {
        final var ws = Files.createTempDirectory("neut");
        final Path root = ws.resolve("svc");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\ntasks.test { ignoreFailures = true }\n");
        final List<String> hits = BuildChecks.neutered(List.of(root), ws);
        assertEquals(List.of(ws.relativize(root.resolve("build.gradle")).toString()), hits);

        final var ws2 = Files.createTempDirectory("neut2");
        final Path root2 = ws2.resolve("svc");
        Files.createDirectories(root2.resolve("src/main"));
        Files.writeString(root2.resolve("build.gradle"), "plugins { id 'java' }\n\ntest { useJUnitPlatform() }\n");   // a normal test block
        assertTrue(BuildChecks.neutered(List.of(root2), ws2).isEmpty(), "a healthy test task is not neutered");
    }

    @Test
    void infraReasonSeparatesMachineFromAgent() {
        assertTrue(!BuildChecks.infraReason("Error response from daemon: registry connection reset").isEmpty());
        assertEquals("", BuildChecks.infraReason("src/A.java:10: error: cannot find symbol Foo"));
    }

    @Test
    void testStatsCountsJUnitXml() throws Exception {
        final var root = Files.createTempDirectory("stats");
        final Path results = root.resolve("build/test-results/test");
        Files.createDirectories(results);
        Files.writeString(results.resolve("A.xml"), "<?xml version=\"1.0\"?><testsuite tests=\"10\" failures=\"2\" errors=\"1\"/>");
        Files.writeString(results.resolve("B.xml"), "<?xml version=\"1.0\"?><testsuite tests=\"5\" failures=\"0\" errors=\"0\"/>");
        final int[] st = BuildChecks.testStats(root);
        assertEquals(15, st[0]);
        assertEquals(3, st[1]);
    }

    /** #612, found live: run()'s own scratch-copy temp dir used to be created via the no-arg
     *  Files.createTempDirectory("ab-build-"), which defaults to java.io.tmpdir - ace-service's own
     *  CONTAINER-PRIVATE /tmp when deployed via docker-compose.yml's Docker-outside-of-Docker setup,
     *  never visible to the sibling containers gradle()'s own "docker run -v <that path>:/w" spawns
     *  over the shared host daemon. A nonexistent host path in a bind mount is not an error - Docker
     *  silently auto-creates an EMPTY directory there (confirmed live: `docker run -v
     *  /tmp/nonexistent:/w alpine ls /w` exits 0, empty) - so every real build-check run silently
     *  scored against an empty scratch copy regardless of what the agent actually wrote, surfacing
     *  as a misleading "Directory '/w' does not contain a Gradle build" FAIL charged to the agent.
     *  scratchCopy() itself already took the destination as an explicit parameter - this locks in
     *  that a destination created as a SIBLING of ws (ws.getParent(), the one real host-backed path
     *  every deployment has) still faithfully copies the agent's real files, the exact relationship
     *  run() now uses instead of the system default temp dir. */
    @Test
    void scratchCopyWorksWhenItsDestinationIsASiblingOfWsInsteadOfTheSystemTempDir() throws Exception {
        final var parent = Files.createTempDirectory("scratch-parent");
        final var ws = parent.resolve("workspace");
        Files.createDirectories(ws.resolve("src/main/java/app"));
        Files.writeString(ws.resolve("src/main/java/app/App.java"), "class App {}");
        Files.writeString(ws.resolve("build.gradle"), "// real build file");
        Files.createDirectories(ws.resolve("build/classes"));   // SKIP_DIRS: must never be copied
        Files.writeString(ws.resolve("build/classes/App.class"), "not real bytecode");

        final var tmp = Files.createTempDirectory(ws.getParent(), "ab-build-");
        try {
            final var scratchCopyMethod = BuildChecks.class.getDeclaredMethod("scratchCopy", Path.class, Path.class);
            scratchCopyMethod.setAccessible(true);
            final Path copy = (Path) scratchCopyMethod.invoke(null, ws, tmp);

            assertEquals(ws.getParent(), tmp.getParent(), "the scratch dir must be a sibling of ws, not under the system default temp dir");
            assertTrue(Files.isRegularFile(copy.resolve("src/main/java/app/App.java")), "the agent's real source file must be copied");
            assertTrue(Files.isRegularFile(copy.resolve("build.gradle")), "the agent's real build file must be copied");
            assertFalse(Files.exists(copy.resolve("build/classes/App.class")), "SKIP_DIRS (build/) must still be excluded from the copy");
        } finally {
            try (var s = Files.walk(tmp)) { s.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} }); }
            try (var s = Files.walk(parent)) { s.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} }); }
        }
    }
}
