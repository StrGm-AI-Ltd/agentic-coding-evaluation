package com.strgmai.ace.service.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The packs' reproducibility contract and the tolerant review/parallel-plan parsers. */
class PacksTest {

    // temp files accumulate in the OS temp dir across local/CI runs - track and delete them
        private final Set<Path> temps = new HashSet<>();

    private Path track(Path p) { temps.add(p); return p; }

    @AfterEach
    void cleanUp() throws IOException {
        for (Path p : temps) Files.deleteIfExists(p);
        temps.clear();
    }


    @Test
    void capsScaleWithTheWindowAndTheContractNeverTruncates() {
        Map<String, Integer> half = Packs.setScale(0.5, Map.of("contract", 9000));   // a 9000-char contract keeps its real size (R5 C-25)
        assertEquals(9000, half.get("contract"));
        assertEquals(2000, half.get("plan"));   // 4000 x 0.5
        Map<String, Integer> floor = Packs.setScale(0.1, Map.of());   // below pack_scale_min: 0.25 floors
        assertEquals(1000, floor.get("plan"));
        // no assertion on purpose: a scale of 1.0 with an empty map must simply not throw (setScale is a mutating no-op then)
        Packs.setScale(1.0, Map.of());
    }

    @Test
    void theHygieneRulesNameTheRunsWindow() {
        Packs.setWindow(79872);
        assertTrue(Packs.hygiene().contains("79k context"));
        Packs.setWindow(65536);
        assertTrue(Packs.hygiene().contains("65k context"));
    }

    @Test
    void contractSectionStripsThePlanningProtocolAndMonolithicBudget() {
        String prompt = """
                # Task
                Build a trading service.

                ## Protocol for this rung
                First write docs/IMPLEMENTATION_PLAN.md, then implement.

                ## Frozen API contract
                All money is BigDecimal.
                `GET /accounts/{id}` returns availableBalance at 2dp.
                """;
        final String section = Packs.contractSection(prompt);
        assertTrue(section.contains("Frozen API contract"));
        assertTrue(section.contains("BigDecimal"));
        assertFalse(section.contains("Protocol for this rung"), "the plan protocol is stripped in orchestrated mode (C-11)");
        assertFalse(section.contains("First write docs/IMPLEMENTATION_PLAN.md"));
    }

    @Test
    void parseSelfReviewIsTolerantOfPercentageFractionsAndSeveritySynonyms() throws Exception {
        final Path f = track(Files.createTempFile("review", ".json"));
        Files.writeString(f, """
                some prose before the JSON, because models do that
                {"score": "85%", "confidence": 0.7, "categories": {"tests": 0.9},
                 "findings": [{"severity": "CRITICAL", "file": "A.java", "line": 3, "issue": "asOf boundary is inclusive", "fix": "make it exclusive"}],
                 "would_ship": true}
                """);
        final Map<String, Object> parsed = Packs.parseSelfReview(f);
        assertNotNull(parsed);
        assertEquals(85.0, parsed.get("score"), "a percentage string is 85, not 0.85 (R4 C-12)");
        assertEquals(0.7, parsed.get("confidence"));
        assertEquals(90.0, ((Map<?, ?>) parsed.get("categories")).get("tests"), "a 0-1 fraction category is a percentage in disguise");
        assertEquals(1L, ((Map<?, Long>) parsed.get("findings_by_severity")).get("HIGH"), "CRITICAL collapses to HIGH");
        assertEquals(true, parsed.get("would_ship"));
        assertTrue(Packs.findingCheckIds("the asOf boundary is inclusive").contains("F8"), "the keyword heuristic maps findings to oracle checks");
    }

    @Test
    void parseSelfReviewRejectsScorelessJson() throws Exception {
        final Path f = track(Files.createTempFile("review", ".json"));
        Files.writeString(f, "{\"confidence\": 0.5}");
        assertNull(Packs.parseSelfReview(f));
        assertNull(Packs.parseSelfReview(Path.of("/nonexistent")));
    }

    @Test
    void parseTrajectoryReviewKeepsItsOwnFields() throws Exception {
        final Path f = track(Files.createTempFile("traj", ".json"));
        Files.writeString(f, "{\"score\": 70, \"confidence\": 0.8, \"categories\": {\"efficiency\": 60}, \"findings\": [], \"wasted_turns_estimate\": 12, \"would_trust_unsupervised\": false}");
        final Map<String, Object> parsed = Packs.parseTrajectoryReview(f);
        assertEquals(70.0, parsed.get("score"));
        assertEquals(12, parsed.get("wasted_turns_estimate"));
        assertEquals(false, parsed.get("would_trust_unsupervised"));
    }

    @Test
    void parseParallelPlanNormalizesIdsAndAcceptsBareTaskWaves() throws Exception {
        final Path f = track(Files.createTempFile("pp", ".json"));
        Files.writeString(f, """
                noise before
                {"waves": [["Task 1"], ["ST-02", "T4"], "T3"],
                 "ownership": {"Task 1": ["account-service/**"], "T2": "orders/Orders.java"},
                 "shared_files": ["settings.gradle"], "rationale": "because"}
                """);
        final Map<String, Object> parsed = Packs.parseParallelPlan(f);
        assertEquals(List.of(List.of("T1"), List.of("T2", "T4"), List.of("T3")), parsed.get("waves"));
        assertEquals(List.of("account-service/**"), ((Map<?, ?>) parsed.get("ownership")).get("T1"));
        assertEquals(List.of("orders/Orders.java"), ((Map<?, ?>) parsed.get("ownership")).get("T2"));   // a bare glob is a one-glob list
        assertEquals(List.of("settings.gradle"), parsed.get("shared_files"));
        assertNull(Packs.parseParallelPlan(Path.of("/nonexistent")));
        final Path bad = track(Files.createTempFile("pp-bad", ".json"));
        Files.writeString(bad, "{\"ownership\": {}}");
        assertNull(Packs.parseParallelPlan(bad), "no waves -> no plan");
    }

    @Test
    void budgetSectionCarriesTheDeadlineClock() {
        final String s = Packs.budgetSection("T3", "10:00", "11:00", 60);
        assertTrue(s.contains("hard deadline **11:00**"));
        assertTrue(s.contains("T3"));
    }
}
