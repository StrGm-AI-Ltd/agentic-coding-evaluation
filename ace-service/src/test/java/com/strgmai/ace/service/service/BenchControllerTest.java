package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.pack.Packs;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** #6: jsonb-shaped TEXT columns (params/argv/manifest/oracle/metrics/validity_reasons/comparison/
 *  detail) must reach the client as real nested JSON, not a JSON string — the job of
 *  {@link com.strgmai.ace.service.config.JsonColumns}, applied inside JobQueue/ExperimentsService and
 *  directly in BenchController wherever it reads rows itself. Standalone MockMvc (not @WebMvcTest):
 *  DSLContext is a REAL one against a fresh SQLite database (there is no Postgres-driver PGobject to
 *  hand-build anymore, and mocking jOOQ's fluent chain step-by-step would be more fragile than just
 *  using a real embedded database), while the other collaborators stay plain Mockito mocks. Also
 *  covers the ApiExceptionHandler (#13) status/body mapping. */
class BenchControllerTest {

    private MockMvc mvc;
    private JobQueue queue;
    private ImporterService importer;
    private ExperimentsService experiments;
    private StatsService stats;
    private DSLContext dsl;
    private BenchProperties props;

    @BeforeEach
    void setUp() throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile("ace-benchcontroller-test", ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        dsl = DSL.using(ds, SQLDialect.SQLITE);
        queue = mock(JobQueue.class);
        importer = mock(ImporterService.class);
        experiments = mock(ExperimentsService.class);
        stats = mock(StatsService.class);
        final WorkerService worker = mock(WorkerService.class);
        props = mock(BenchProperties.class);
        final Preflight preflight = mock(Preflight.class);
        final TreatmentPin pin = mock(TreatmentPin.class);
        final var controller = new BenchController(dsl, queue, importer, experiments, stats, worker, props, preflight, pin);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void jsonbColumnsComeBackAsRealNestedJsonNotAStringOnJobsList() throws Exception {
        final Map<String, Object> job = new java.util.LinkedHashMap<>();
        job.put("id", 1);
        job.put("run_id", "run-1");
        job.put("argv", new com.fasterxml.jackson.databind.ObjectMapper().readTree("[\"--task=L3p\",\"--model=m\"]"));
        when(queue.list()).thenReturn(List.of(job));

        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].argv").isArray())
                .andExpect(jsonPath("$[0].argv[0]").value("--task=L3p"))
                .andExpect(jsonPath("$[0].argv[1]").value("--model=m"));
    }

    @Test
    void jsonbObjectColumnAlsoComesBackNestedOnExperimentsList() throws Exception {
        dsl.insertInto(EXPERIMENTS)
                .set(EXPERIMENTS.ID, UUID.randomUUID())   // no AUTOINCREMENT on a UUID PK - the real service always assigns one
                .set(EXPERIMENTS.NAME, "exp").set(EXPERIMENTS.TAG, "t").set(EXPERIMENTS.TEMPLATE, "harness_effect")
                .set(EXPERIMENTS.PARAMS, "{\"model\":\"m\",\"nested\":{\"a\":1}}").set(EXPERIMENTS.K, 1)
                .execute();

        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].params.model").value("m"))
                .andExpect(jsonPath("$[0].params.nested.a").value(1));
    }

    // todo issue 25: fix the broken test
    @Disabled
    @Test
    void modelsListsWhateverTheModelServerCurrentlyServesSorted() throws Exception {
        when(experiments.localModelSpecs()).thenReturn(Map.of("Qwen3.6-27B-graft", 65536, "Qwen3.8-27B-graft", 131072));

        mvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Qwen3.6-27B-graft"))   // sorted, not insertion order
                .andExpect(jsonPath("$[1]").value("Qwen3.8-27B-graft"));
    }

    // todo issue 25 fix the broken test
    @Disabled
    @Test
    void modelsIsAnEmptyListNotA500WhenTheModelServerIsUnreachable() throws Exception {
        when(experiments.localModelSpecs()).thenReturn(Map.of());

        mvc.perform(get("/api/models")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    /** every rung tasks/ladder.json declares, so the UI's "task" combobox can offer all of them,
     *  not just ones a run has already used (the DB-only list used to hide every rung this
     *  dataset had never happened to run yet). */
    @Test
    void tasksListsEveryLadderRungSortedAndExcludesTheDocKey() throws Exception {
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder("L1_migration_entity", "L2_one_endpoint", "L3_point_in_time",
                        "L3p_point_in_time", "L4_state_machine", "L5_second_service", "L6_compose_health", "L7_full_platform")))
                .andExpect(jsonPath("$[0]").value("L1_migration_entity"));   // sorted
    }

    /** every rung's own description/budget/denominator, with each check resolved to its real
     *  category/weight/description from CheckId rather than a bare id - and L7's "all" resolved
     *  to the full CheckId set, never leaking the literal string through to the UI. */
    @Test
    void taskDetailsResolvesEveryRungsChecksFromCheckIdAndExpandsAllForL7() throws Exception {
        mvc.perform(get("/api/tasks/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].name").value("L1_migration_entity"))   // sorted
                .andExpect(jsonPath("$[0].description").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$[0].budget_sec").value(2700))
                .andExpect(jsonPath("$[0].denominator").value(11));

        final var l7 = mvc.perform(get("/api/tasks/details")).andReturn().getResponse().getContentAsString();
        final var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(l7);
        com.fasterxml.jackson.databind.JsonNode l7Node = null, l3pNode = null;
        for (final var rung : tree) {
            if ("L7_full_platform".equals(rung.get("name").asText())) l7Node = rung;
            if ("L3p_point_in_time".equals(rung.get("name").asText())) l3pNode = rung;
        }
        assertEquals(com.strgmai.ace.service.oracle.CheckId.values().length, l7Node.get("checks").size(),
                "L7's \"all\" must resolve to the full CheckId set, not the literal string");

        boolean foundB3 = false;
        for (final var check : l3pNode.get("checks"))
            if ("B3".equals(check.get("check_id").asText())) {
                foundB3 = true;
                assertEquals("build", check.get("category").asText());
                assertEquals(3, check.get("weight").asInt());
                assertEquals("agent suite FAILS on a seeded mutation", check.get("description").asText());
            }
        assertTrue(foundB3, "L3p_point_in_time must include B3");
    }

    @Test
    void importAllServesRunIdListsAsJsonArraysNotCounts() throws Exception {
        // the actual bug reported: the Vaadin UI's Api.ImportResult expects List<String>, and this
        // endpoint used to serve {"imported": 2, "skipped": 1} - a JSON parse error on the UI side
        when(importer.importAll(any())).thenReturn(Map.of("imported", List.of("run-a", "run-b"), "skipped", List.of("run-c")));
        // BenchController.importAll() always calls props.resultsDir(); an unstubbed mock returns
        // null and Path.of(null) NPEs before importer.importAll() is ever reached
        when(props.resultsDir()).thenReturn("/tmp/results");

        mvc.perform(post("/api/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").isArray())
                .andExpect(jsonPath("$.imported[0]").value("run-a"))
                .andExpect(jsonPath("$.skipped[0]").value("run-c"));
    }

    private static final UUID JOB_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID JOB_7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID JOB_42 = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID JOB_99 = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Test
    void jobByIdReturnsTheJob() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "queued"));

        mvc.perform(get("/api/jobs/" + JOB_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value("run-1"))
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void jobByIdMapsTheMissingJobTo404() throws Exception {
        // JobQueue.get() already throws this for a missing row; the endpoint was simply never wired
        when(queue.get(JOB_99)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        mvc.perform(get("/api/jobs/" + JOB_99)).andExpect(status().isNotFound());
    }

    /** keyed by job id, not run id - unlike /api/runs/{id}/files/..., this must work on a still-
     *  running, never-imported job (it just reads a results-dir file directly). */
    @Test
    void jobPlanParsesTheResultsDirsPlanFileInExecutionOrder() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), """
                # Plan
                ## T1 — model the schema
                - Goal: create the entities
                - Dependencies: none
                ## T2: implement the API
                Goal: the REST endpoints
                Dependencies: T1
                """);
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value("T1"))
                .andExpect(jsonPath("$.tasks[0].goal").value("create the entities"))
                .andExpect(jsonPath("$.tasks[0].order").value(0))
                .andExpect(jsonPath("$.tasks[1].id").value("T2"))
                .andExpect(jsonPath("$.tasks[1].goal").value("the REST endpoints"))
                .andExpect(jsonPath("$.tasks[1].order").value(1));
    }

    @Test
    void jobPlanIsAnEmptyListBeforeAnyPlanFileExists() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-1"));   // the run dir exists; no plan yet
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void taskPromptReturnsTheRealRecordedPromptOnceTheTaskHasStarted() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var packs = resultsDir.resolve("run-1/packs");
        Files.createDirectories(packs);
        Files.writeString(packs.resolve("T1.md"), "## Frozen API contract\nAll money is BigDecimal.");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.text", containsString("Frozen API contract")))
                .andExpect(jsonPath("$.text", containsString("BigDecimal")));
    }

    /** the task hasn't run yet: still shows the static instruction every task shares (a pure
     *  function of the id - safe to compute ahead of time), just not enriched with the dynamic part
     *  yet - see Packs.taskPack()'s own dependence on live workspace state (RunBench.java). */
    @Test
    void taskPromptShowsTheStaticInstructionBeforeThePackFileExists() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-1"));
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T3/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.text").value(Packs.taskInstruction("T3")));
    }

    @Test
    void illegalArgumentExceptionMapsTo400WithDetailBody() throws Exception {
        // RunSpec's own validation throws before queue.enqueue() is ever called
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spec\": {\"task\": \"L3p_point_in_time\", \"mode\": \"bogus\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("mode must be monolithic or orchestrated"));
    }

    @Test
    void noSuchElementExceptionMapsTo404WithDetailBody() throws Exception {
        doThrow(new NoSuchElementException("job 42 does not exist")).when(queue).setPriority(JOB_42, 5);

        mvc.perform(post("/api/jobs/" + JOB_42 + "/priority").contentType(MediaType.APPLICATION_JSON).content("{\"priority\": 5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("job 42 does not exist"));
    }

    @Test
    void illegalStateExceptionMapsTo409WithDetailBody() throws Exception {
        when(queue.cancel(JOB_7)).thenThrow(new IllegalStateException("job 7 is already succeeded"));

        mvc.perform(post("/api/jobs/" + JOB_7 + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("job 7 is already succeeded"));
    }

    /** issue #18: a still-queued job is cancelled straight in the DB, the worker never sees it - so
     *  this endpoint, not WorkerService, is the only place that can notice "that was the experiment's
     *  last pending job" and finalize it. */
    @Test
    void cancellingAJobsExperimentsLastPendingJobFinalizesTheExperiment() throws Exception {
        final UUID experimentId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        final Map<String, Object> cancelled = new java.util.LinkedHashMap<>();
        cancelled.put("id", JOB_7);
        cancelled.put("experiment_id", experimentId);
        cancelled.put("status", "cancelled");
        when(queue.cancel(JOB_7)).thenReturn(cancelled);

        mvc.perform(post("/api/jobs/" + JOB_7 + "/cancel")).andExpect(status().isOk());

        verify(experiments).finalizeIfDone(experimentId);
    }

    @Test
    void cancellingAJobWithNoExperimentNeverCallsFinalize() throws Exception {
        final Map<String, Object> cancelled = new java.util.LinkedHashMap<>();
        cancelled.put("id", JOB_7);
        cancelled.put("experiment_id", null);
        cancelled.put("status", "cancelled");
        when(queue.cancel(JOB_7)).thenReturn(cancelled);

        mvc.perform(post("/api/jobs/" + JOB_7 + "/cancel")).andExpect(status().isOk());

        verify(experiments, never()).finalizeIfDone(any());
    }

    private void runRow(final String task, String model, final String keyHash, final String runId) {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, runId).set(RUNS.RESULTS_DIR, "/results/" + runId)
                .set(RUNS.TASK, task).set(RUNS.MODEL, model).set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, keyHash).set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}")
                .execute();
    }

    /** /api/groups' own orchestration - grouping DB rows by (task, model, key_hash), the k>=5
     *  ranked/indicative split, and sorting ranked by functional mean descending - independent of
     *  the math inside StatsService.summarize() (covered by StatsServiceTest). stats is a full mock
     *  here: summarize()'s return drives the branching exactly as the real one would from real k. */
    @Test
    void groupsSplitsRankedFromIndicativeByKAndSortsRankedByFunctionalMeanDescending() throws Exception {
        for (int i = 1; i <= 5; i++) runRow("L3p_point_in_time", "low-scorer", "keyA", "runA" + i);
        for (int i = 1; i <= 2; i++) runRow("L3p_point_in_time", "too-few", "keyB", "runB" + i);
        for (int i = 1; i <= 5; i++) runRow("L3p_point_in_time", "high-scorer", "keyC", "runC" + i);
        // stats.load(path) tags each summary with the model its results_dir belongs to, so the
        // summarize() stub below can tell the three groups apart without inspecting real files
        when(stats.load(any())).thenAnswer(inv -> {
            final String dir = inv.getArgument(0).toString();
            final String model = dir.contains("runA") ? "low-scorer" : dir.contains("runB") ? "too-few" : "high-scorer";
            return new StatsService.RunSummary(dir, "L3p_point_in_time", model, "monolithic", null, null, null, null,
                    true, List.of(), false, true, 0, 0, Map.of(), List.of(), Map.of());
        });
        when(stats.filterRuns(any(), eq(false), eq(false), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.summarize(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") List<StatsService.RunSummary> runs = (List<StatsService.RunSummary>) inv.getArgument(0);
            final Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("k", runs.size());
            final double mean = switch (runs.get(0).model()) { case "low-scorer" -> 40.0; case "high-scorer" -> 90.0; default -> 10.0; };
            out.put("functional", Map.of("mean", mean, "ci90", List.of(mean - 5, mean + 5), "n", runs.size()));
            return out;
        });

        mvc.perform(get("/api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranked.length()").value(2))                    // keyA and keyC: k=5
                .andExpect(jsonPath("$.indicative.length()").value(1))                // keyB: k=2, never ranked
                .andExpect(jsonPath("$.indicative[0].key_hash").value("keyB"))
                .andExpect(jsonPath("$.ranked[0].key_hash").value("keyC"))            // 90.0 mean sorts before 40.0
                .andExpect(jsonPath("$.ranked[1].key_hash").value("keyA"))
                .andExpect(jsonPath("$.ranked[0].printed").value(containsString("functional: mean 90.0")));
    }

    /** #201: this endpoint's explicit column list had fallen behind Api.Run's own DTO - poolable
     *  and partial_score_pct are real columns real consumers need (CompareView.poolableRunIds(),
     *  RunsView's partial-score fallback) but were silently never selected, so every run came back
     *  as poolable=false/partial_score_pct=null regardless of its real value. functional_ids has
     *  no backing column at all - it only ever existed nested inside the oracle JSON blob - so it
     *  must be hoisted to the top level in Java rather than added to the SELECT. */
    @Test
    void runsListIncludesPoolablePartialScoreAndFunctionalIds() throws Exception {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run1").set(RUNS.RESULTS_DIR, "/results/run1")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k1").set(RUNS.POOLABLE, true).set(RUNS.PARTIAL_SCORE_PCT, 42.5f)
                .set(RUNS.ORACLE, "{\"functional_ids\":[\"F1\",\"F2\"]}")
                .execute();

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].poolable").value(true))
                .andExpect(jsonPath("$[0].partial_score_pct").value(42.5))
                .andExpect(jsonPath("$[0].functional_ids.length()").value(2))
                .andExpect(jsonPath("$[0].functional_ids[0]").value("F1"))
                .andExpect(jsonPath("$[0].functional_ids[1]").value("F2"));
    }

    /** #207: "started" has a real backing column now (V3 migration) but was still missing from
     *  this endpoint's explicit SELECT - same class of gap #201 already found for poolable/
     *  partial_score_pct. */
    @Test
    void runsListIncludesStarted() throws Exception {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run3").set(RUNS.RESULTS_DIR, "/results/run3")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k3").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}")
                .set(RUNS.STARTED, "2026-09-21T18:32:32.415417Z")
                .execute();

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].started").value("2026-09-21T18:32:32.415417Z"));
    }

    /** #217: earlier_attempts has a real backing column (V4 migration, computed at import time
     *  from superseded session files) but was still missing from this endpoint's explicit SELECT -
     *  same class of gap #201/#207 already found for poolable/partial_score_pct/started. */
    @Test
    void runsListIncludesEarlierAttempts() throws Exception {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run4").set(RUNS.RESULTS_DIR, "/results/run4")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k4").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}")
                .set(RUNS.EARLIER_ATTEMPTS, 3)
                .execute();

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].earlier_attempts").value(3));
    }

    /** Found in the #236 audit: RunsView's Mode/Poolable filters (ServiceClient.runs()) have sent
     *  these query params since they were added, but this endpoint never declared or read either
     *  one back out of the request - filtering by mode or poolable silently did nothing server-side. */
    @Test
    void runsListFiltersByModeAndPoolable() throws Exception {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run-mono").set(RUNS.RESULTS_DIR, "/results/run-mono")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k1").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}")
                .execute();
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run-orch").set(RUNS.RESULTS_DIR, "/results/run-orch")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "orchestrated")
                .set(RUNS.KEY_HASH, "k2").set(RUNS.POOLABLE, false).set(RUNS.ORACLE, "{}")
                .execute();

        mvc.perform(get("/api/runs").param("mode", "orchestrated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].run_id").value("run-orch"));

        mvc.perform(get("/api/runs").param("poolable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].run_id").value("run-mono"));
    }

    @Test
    void runByIdAlsoIncludesFunctionalIdsHoistedFromOracle() throws Exception {
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, "run2").set(RUNS.RESULTS_DIR, "/results/run2")
                .set(RUNS.TASK, "L3p_point_in_time").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k2").set(RUNS.POOLABLE, true)
                .set(RUNS.ORACLE, "{\"functional_ids\":[\"F3\"]}")
                .execute();

        mvc.perform(get("/api/runs/run2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functional_ids.length()").value(1))
                .andExpect(jsonPath("$.functional_ids[0]").value("F3"));
    }

    /** #85: a symlink planted inside the served results subtree, pointing outside it, must not be
     *  followed - normalize() alone (lexical "../." collapsing) cannot catch this, only resolving
     *  the real path can. */
    @Test
    void isContainedEvenViaSymlinks_rejectsASymlinkPointingOutsideBase() throws Exception {
        final Path base = Files.createTempDirectory("results-base").toRealPath();
        final Path outside = Files.createTempDirectory("outside");
        final Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");

        final Path normalFile = base.resolve("oracle.json");
        Files.writeString(normalFile, "{}");
        assertTrue(BenchController.isContainedEvenViaSymlinks(base, normalFile), "an ordinary file inside base must pass");

        final Path escapingLink = base.resolve("innocuous-looking-file.txt");
        Files.createSymbolicLink(escapingLink, secret);
        assertFalse(BenchController.isContainedEvenViaSymlinks(base, escapingLink),
                "a symlink inside base pointing outside it must be rejected, not silently followed");
    }

    private static StatsService.RunSummary runSummary(final String dir, final String model) {
        return new StatsService.RunSummary(dir, "L3p_point_in_time", model, "orchestrated", 90.0, 80.0, null, null,
                true, List.of(), false, true, 600, 10000, Map.of(), List.of(), Map.of());
    }

    /** A quant variant of the same model (e.g. "-5bit") is not the kind of "different model" this
     *  check exists to guard against - refusing outright was too restrictive. Without model_ab, a
     *  model mismatch now warns and still returns a real comparison, rather than refusing. */
    @Test
    void compareWithDifferentModelsWithoutModelAbWarnsAndProceeds() throws Exception {
        when(props.resultsDir()).thenReturn("/tmp/results");
        when(stats.load(Path.of("/tmp/results", "runA"))).thenReturn(runSummary("/tmp/results/runA", "Qwen3.8-27B-graft"));
        when(stats.load(Path.of("/tmp/results", "runB"))).thenReturn(runSummary("/tmp/results/runB", "Qwen3.8-27B-graft-5bit"));
        when(stats.filterRuns(any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.compare(any(), any(), any())).thenReturn(fullCompareResult(5.0));

        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\": [\"runA\"], \"b\": [\"runB\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").doesNotExist())
                .andExpect(jsonPath("$.result.diff").value(5.0))
                .andExpect(jsonPath("$.result.model_warning").value(
                        "A (Qwen3.8-27B-graft) and B (Qwen3.8-27B-graft-5bit) are different models - interpret this comparison accordingly; pass model_ab for the full harness-identity check instead"))
                .andExpect(jsonPath("$.printed").value(containsString("NOTE:")));
    }

    @Test
    void compareWithSameModelHasNoModelWarning() throws Exception {
        when(props.resultsDir()).thenReturn("/tmp/results");
        when(stats.load(Path.of("/tmp/results", "runA"))).thenReturn(runSummary("/tmp/results/runA", "Qwen3.8-27B-graft"));
        when(stats.load(Path.of("/tmp/results", "runB"))).thenReturn(runSummary("/tmp/results/runB", "Qwen3.8-27B-graft"));
        when(stats.filterRuns(any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.compare(any(), any(), any())).thenReturn(fullCompareResult(0.0));

        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\": [\"runA\"], \"b\": [\"runB\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.model_warning").doesNotExist());
    }

    /** model_ab is a separate, unaffected path: it already has its own harness-identity check and
     *  must keep behaving exactly as before - this change only touches the non-model_ab branch. */
    @Test
    void compareWithModelAbAndDifferentModelsHasNoModelWarning() throws Exception {
        when(props.resultsDir()).thenReturn("/tmp/results");
        when(stats.load(Path.of("/tmp/results", "runA"))).thenReturn(runSummary("/tmp/results/runA", "Qwen3.8-27B-graft"));
        when(stats.load(Path.of("/tmp/results", "runB"))).thenReturn(runSummary("/tmp/results/runB", "Qwen3.8-27B-graft-5bit"));
        when(stats.filterRuns(any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.compare(any(), any(), any())).thenReturn(fullCompareResult(5.0));

        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\": [\"runA\"], \"b\": [\"runB\"], \"model_ab\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").doesNotExist())
                .andExpect(jsonPath("$.result.model_warning").doesNotExist());
    }

    /** The success path never wrapped its response in {"result": ..., "printed": ...} - the shape
     *  Api.CompareResponse (the UI side) actually deserializes, mirroring
     *  ExperimentsService.java:448,458's own compare-result wrapping. It returned the bare
     *  stats.compare() map instead, so response.result()/printed() were always null on a real
     *  success - dormant because every prior test only exercised the "refused" paths. */
    @Test
    void compareOnSuccessWrapsTheStatsResultUnderTheResultKey() throws Exception {
        when(props.resultsDir()).thenReturn("/tmp/results");
        when(stats.load(Path.of("/tmp/results", "runA"))).thenReturn(runSummary("/tmp/results/runA", "Qwen3.8-27B-graft"));
        when(stats.load(Path.of("/tmp/results", "runB"))).thenReturn(runSummary("/tmp/results/runB", "Qwen3.8-27B-graft"));
        when(stats.filterRuns(any(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.compare(any(), any(), any())).thenReturn(fullCompareResult(3.5));

        mvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\": [\"runA\"], \"b\": [\"runB\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.diff").value(3.5))
                .andExpect(jsonPath("$.printed").value(not(emptyString())))
                .andExpect(jsonPath("$.printed").value(containsString("functional")));
    }

    /** a realistic stats.compare() result - every key printCompare() reads, so mocking it doesn't
     *  trip over a shape no real caller would ever produce. */
    private static Map<String, Object> fullCompareResult(final double diff) {
        final var m = new java.util.LinkedHashMap<String, Object>();
        m.put("metric", "functional");
        m.put("diff", diff);
        m.put("ci90", List.of(0.0, 10.0));
        m.put("ci90_width", 10.0);
        m.put("p", 0.5);
        m.put("one_sided", true);
        m.put("verdict", "NOT supported at alpha=0.10");
        m.put("note", "this comparison's own 90% CI is 10.0 points wide");
        return m;
    }
}
