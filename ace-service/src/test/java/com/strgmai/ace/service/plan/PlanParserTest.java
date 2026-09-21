package com.strgmai.ace.service.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Port of the plan-parser behaviours the Python suite pinned: ids, fields, topological order,
 *  cycle detection, dependency waves and the parallel-plan evaluation. */
class PlanParserTest {

    @Test
    void parsesHeadingsListItemsAndFields() {
        String md = """
                # Plan
                ## T1 — model the schema
                - Goal: create the entities
                - Services: migration-service
                - Dependencies: none
                - Acceptance: tables exist in H2
                ## T2: implement the API
                Goal: the REST endpoints
                Services: api-service
                Dependencies: T1
                Acceptance: /orders returns 201
                """;
        final List<PlanTask> tasks = PlanParser.parse(md);
        assertEquals(List.of("T1", "T2"), tasks.stream().map(t -> t.id).toList());
        assertEquals("create the entities", tasks.get(0).goal);
        assertEquals(List.of("T1"), tasks.get(1).deps);
        assertEquals("the REST endpoints", tasks.get(1).goal);
    }

    @Test
    void aTableRowIsATask() {
        final String md = "| T1 | model the schema | migration-service | - | tables exist |\n| T2 | implement the API | api-service | T1 | /orders returns 201 |\n";
        final List<PlanTask> tasks = PlanParser.parse(md);
        assertEquals(2, tasks.size());
        assertEquals("model the schema", tasks.get(0).goal);
        assertEquals(List.of("T1"), tasks.get(1).deps);
    }

    @Test
    void orderIsTopologicalTiesByIdNumber() {
        final List<PlanTask> tasks = PlanParser.parse("## T10 depends on T2\n- Goal: a\n- Dependencies: T2\n## T2\n- Goal: b\n- Dependencies: T1\n## T1\n- Goal: c\n");
        assertEquals(List.of("T1", "T2", "T10"), tasks.stream().map(t -> t.id).toList());
        assertEquals(List.of(List.of("T1"), List.of("T2"), List.of("T10")),
                PlanParser.waves(tasks).stream().map(w -> w.stream().map(t -> t.id).toList()).toList());
    }

    @Test
    void aCycleIsAPlanError() {
        PlanError e = assertThrows(PlanError.class, () -> PlanParser.parse("""
                ## T1 - first
                - Dependencies: T2
                ## T2 - second
                - Dependencies: T1
                """));
        assertTrue(e.getMessage().contains("dependency cycle"), e.getMessage());
    }

    @Test
    void noTasksIsAPlanError() {
        assertThrows(PlanError.class, () -> PlanParser.parse("# Just a heading, no ids\nsome text\n"));
    }

    @Test
    void anIdOccurringTwiceKeepsTheRicherBlock() {
        String md = """
                | T1 | overview | | | |
                ## T1 — the real section
                - Goal: the detailed goal
                - Acceptance: tests are green
                """;
        final List<PlanTask> tasks = PlanParser.parse(md);
        assertEquals(1, tasks.size());
        assertEquals("the detailed goal", tasks.get(0).goal);
    }

    @Test
    void fieldSubHeadingsCarryTheirValue() {
        List<PlanTask> tasks = PlanParser.parse("""
                ### T1 the thing
                #### Goal
                the goal under a subheading
                #### Acceptance
                the criterion
                """);
        assertEquals("the goal under a subheading", tasks.get(0).goal);
        assertEquals("the criterion", tasks.get(0).acceptance);
    }

    @Test
    void parallelPlanEvaluationValidDepthsAndErrors() {
        final List<PlanTask> tasks = PlanParser.parse("## T1\n- Goal: a\n## T2\n- Goal: b\n- Dependencies: T1\n");
        var valid = PlanParser.evaluateParallelPlan(java.util.Map.of("waves", java.util.List.of(
                java.util.List.of("T1"), java.util.List.of("T2"))), tasks);
        assertEquals(Boolean.TRUE, valid.get("valid"));
        assertEquals(100.0, valid.get("parallelism_pct"));   // the dependency chain leaves nothing to parallelise

        var beforeDep = PlanParser.evaluateParallelPlan(java.util.Map.of("waves", java.util.List.of(
                java.util.List.of("T2"), java.util.List.of("T1"))), tasks);
        assertEquals(Boolean.FALSE, beforeDep.get("valid"));
        assertTrue(((List<String>) beforeDep.get("errors")).get(0).contains("before/beside its dependency"));

        assertEquals(Boolean.FALSE, PlanParser.evaluateParallelPlan(java.util.Map.of(), tasks).get("valid"));
    }
}
