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
        String src = "class A {\n  // sell: .subtract(x)\n  BigDecimal b = x.subtract(y); // -qty\n  String s = \".negate()\";\n}";
        String masked = BuildChecks.mask(src, true);
        assertEquals(src.length(), masked.length(), "masking must preserve offsets");
        int i = masked.indexOf(".subtract(");
        assertTrue(i > 0 && src.startsWith(".subtract(", i), "a match in the masked text must apply to the original at the same index");
        assertFalse(masked.contains("sell: .subtract"), "comments must be blanked");
        assertFalse(BuildChecks.mask(src, true).substring(src.indexOf("String s")).contains(".negate()"), "string literals must be blanked");
        assertTrue(BuildChecks.mask(src, false).contains(".negate()"), "with strings=false only comments are blanked");
    }

    @Test
    void textBlocksAreMaskedAsStrings() {
        String src = "class A { String s = \"\"\"\n  .subtract(inside a text block)\n\"\"\"; int x = 1; }";
        assertEquals(src.length(), BuildChecks.mask(src, true).length());
        assertFalse(BuildChecks.mask(src, true).contains(".subtract(inside"));
    }

    @Test
    void pickMutationsPrefersTheNearestAnchorAndSkipsNonSellCode() throws Exception {
        Path ws = Files.createTempDirectory("mut");
        Path src = ws.resolve("src/main/java/app/Ledger.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package app;
                public class Ledger {
                    public int far(String side) {
                        if (side.equals("SELL")) return 0;   // anchor at the top
                        return 1;
                    }
                    public java.math.BigDecimal holdingsAt(java.time.Instant asOf, java.math.BigDecimal qty) {
                        return qty.subtract(java.math.BigDecimal.TEN);   // the target, near the PIT method
                    }
                }
                """);
        Path far = ws.resolve("src/main/java/app/Far.java");
        Files.writeString(far, "package app;\npublic class Far { int x = 1 - quantityHolder; }\n");   // no anchor, no hint
        List<BuildChecks.Candidate> cands = BuildChecks.pickMutations(ws, 4);
        assertFalse(cands.isEmpty(), "the SELL-anchored .subtract must be found");
        assertEquals("subtract->add", cands.get(0).name());
        assertTrue(cands.get(0).anchor().contains("holdingsAt"), "the PIT method anchors it: " + cands.get(0).anchor());
        assertTrue(cands.stream().noneMatch(c -> c.file().endsWith("Far.java")), "unanchored, unhinted files are not mutated");
        // applying the candidate to the ORIGINAL text must produce the mutated source
        BuildChecks.Candidate c = cands.get(0);
        String original = Files.readString(c.file());
        String mutated = original.substring(0, c.start()) + c.replacement() + original.substring(c.end());
        assertTrue(mutated.contains("qty.add(") || mutated.contains(".add(java.math.BigDecimal.TEN)"), mutated);
    }

    @Test
    void aHoldingsNamedFileIsItsOwnAnchor() throws Exception {
        Path ws = Files.createTempDirectory("mut2");
        Path f = ws.resolve("src/main/java/app/PositionService.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\npublic class PositionService {\n  double net(double qty) { return scale(-qty); }\n}\n");
        List<BuildChecks.Candidate> cands = BuildChecks.pickMutations(ws, 4);
        assertFalse(cands.isEmpty());
        assertTrue(cands.get(0).anchor().startsWith("file:"), "a holdings-shaped file name anchors its own mutants");
    }

    @Test
    void commentsNeverCountAsAnchors() throws Exception {
        Path ws = Files.createTempDirectory("mut3");
        Path f = ws.resolve("src/main/java/app/Thing.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\n// SELL SELL SELL\npublic class Thing { int x = 1; }\n");
        assertTrue(BuildChecks.pickMutations(ws, 4).isEmpty(), "a SELL only in a comment anchors nothing");
    }

    @Test
    void aNeuteredTestTaskIsCaught() throws Exception {
        Path ws = Files.createTempDirectory("neut");
        Path root = ws.resolve("svc");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\ntasks.test { ignoreFailures = true }\n");
        List<String> hits = BuildChecks.neutered(List.of(root), ws);
        assertEquals(List.of(ws.relativize(root.resolve("build.gradle")).toString()), hits);

        Path ws2 = Files.createTempDirectory("neut2");
        Path root2 = ws2.resolve("svc");
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
        Path root = Files.createTempDirectory("stats");
        Path results = root.resolve("build/test-results/test");
        Files.createDirectories(results);
        Files.writeString(results.resolve("A.xml"), "<?xml version=\"1.0\"?><testsuite tests=\"10\" failures=\"2\" errors=\"1\"/>");
        Files.writeString(results.resolve("B.xml"), "<?xml version=\"1.0\"?><testsuite tests=\"5\" failures=\"0\" errors=\"0\"/>");
        int[] st = BuildChecks.testStats(root);
        assertEquals(15, st[0]);
        assertEquals(3, st[1]);
    }
}
