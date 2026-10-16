package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.runner.Intervene;
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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
    private Intervene intervene;
    private com.strgmai.ace.service.runner.TaskMatcher taskMatcher;

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
        intervene = new Intervene();
        taskMatcher = mock(com.strgmai.ace.service.runner.TaskMatcher.class);
        final var controller = new BenchController(dsl, queue, importer, experiments, stats, worker, props, preflight, pin, intervene, taskMatcher);
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

    /** #296: events() used to validate the job id ASYNCHRONOUSLY, inside SSE_EXECUTOR, after the
     *  HTTP 200 text/event-stream response was already committed - EmptyResultDataAccessException
     *  thrown that late never reaches ApiExceptionHandler, so a bad id surfaced as a broken SSE
     *  connection instead of a clean 404 like every other /api/jobs/{id}/* endpoint. */
    @Test
    void eventsForAMissingJobMapsTo404NotABrokenSseStream() throws Exception {
        when(queue.get(JOB_99)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        mvc.perform(get("/api/jobs/" + JOB_99 + "/events")).andExpect(status().isNotFound());
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
        // no workspace at this path at all (not even a .git) - implemented/handoff_done must read as
        // false, not throw, same as RunBenchSupport.tagExists() already guarantees for a bare path
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws-root").toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value("T1"))
                .andExpect(jsonPath("$.tasks[0].goal").value("create the entities"))
                .andExpect(jsonPath("$.tasks[0].order").value(0))
                .andExpect(jsonPath("$.tasks[0].implemented").value(false))
                .andExpect(jsonPath("$.tasks[0].handoff_done").value(false))
                .andExpect(jsonPath("$.tasks[1].id").value("T2"))
                .andExpect(jsonPath("$.tasks[1].goal").value("the REST endpoints"))
                .andExpect(jsonPath("$.tasks[1].order").value(1));
    }

    /** #254, found live: a task finished in a PRIOR attempt is resumed straight past (R18) and so
     *  never produces a live session event in a fresh page view - the UI's status column must fall
     *  back to these flags (the workspace's own git tags) rather than reading a genuinely-finished
     *  task as "not started" forever. */
    @Test
    void jobPlanReadsImplementedAndHandoffDoneFromTheWorkspacesOwnR18Tags() throws Exception {
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
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1-handoff");
        // T2 is implemented but its handoff hasn't run yet - a real, common mid-run state
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value("T1"))
                .andExpect(jsonPath("$.tasks[0].implemented").value(true))
                .andExpect(jsonPath("$.tasks[0].handoff_done").value(true))
                .andExpect(jsonPath("$.tasks[1].id").value("T2"))
                .andExpect(jsonPath("$.tasks[1].implemented").value(true))
                .andExpect(jsonPath("$.tasks[1].handoff_done").value(false));
    }

    /** #261/#262: unlike implemented/handoff_done (which only say the SESSION ran to completion),
     *  reported_status carries whether the task actually SUCCEEDED - its own self-reported
     *  done/partial/blocked line, or the harness's "unfinished" fallback when even a dedicated
     *  wrap-up chance came back empty - and reported_detail carries WHY (how it was verified, or
     *  what cut it off). T3 here is implemented but has no PROGRESS.md line at all yet (its own
     *  wrap-up/fallback write hasn't happened) - both must be null, not a false "done", for a task
     *  still genuinely mid-session. */
    @Test
    void jobPlanReadsReportedStatusFromProgressMdIncludingTheHarnessFallback() throws Exception {
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
                ## T3: a third task
                Goal: something else
                Dependencies: T2
                """);
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/PROGRESS.md"), """
                T1 | done | all tests green
                T2 | unfinished | budget exhausted before a status was written
                """);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value("T1"))
                .andExpect(jsonPath("$.tasks[0].reported_status").value("done"))
                .andExpect(jsonPath("$.tasks[0].reported_detail").value("all tests green"))
                .andExpect(jsonPath("$.tasks[1].id").value("T2"))
                .andExpect(jsonPath("$.tasks[1].reported_status").value("unfinished"))
                .andExpect(jsonPath("$.tasks[1].reported_detail").value("budget exhausted before a status was written"))
                .andExpect(jsonPath("$.tasks[2].id").value("T3"))
                .andExpect(jsonPath("$.tasks[2].reported_status").doesNotExist())
                .andExpect(jsonPath("$.tasks[2].reported_detail").doesNotExist());
    }

    /** #257's same leak, a different field: a model bolding the whole line (`**T1** | **blocked** |
     *  **why**`) must not leave a trailing "**" in the captured detail. */
    @Test
    void jobPlanStripsTrailingBoldStarsFromReportedDetail() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), "## T1 — a task\n- Goal: something\n");
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/PROGRESS.md"), "**T1** | **blocked** | **missing a DB migration**\n");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].reported_status").value("blocked"))
                .andExpect(jsonPath("$.tasks[0].reported_detail").value("missing a DB migration"));
    }

    /** #337: a visible effort signal per task - how many attempts task_attempts.jsonl actually
     *  shows, and whether any of them needed a rescue, so a clean pass and a rescued/retried one
     *  read as different outcomes in the Plan grid. */
    @Test
    void jobPlanSurfacesAttemptCountAndRescuedFromTaskAttemptsFile() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"),
                "## T1 — a task\n- Goal: something\n\n## T2 — another\n- Goal: something else\n");
        Files.writeString(runDir.resolve("task_attempts.jsonl"), String.join("\n",
                "{\"task_id\":\"T1\",\"attempt_number\":1,\"rc\":3}",
                "{\"task_id\":\"T1\",\"attempt_number\":2,\"rc\":0}",
                "{\"task_id\":\"T2\",\"attempt_number\":1,\"rc\":0,\"rescue_succeeded\":true}") + "\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws-root").toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].id").value("T1"))
                .andExpect(jsonPath("$.tasks[0].attempts").value(2))
                .andExpect(jsonPath("$.tasks[0].rescued").value(false))
                .andExpect(jsonPath("$.tasks[1].id").value("T2"))
                .andExpect(jsonPath("$.tasks[1].attempts").value(1))
                .andExpect(jsonPath("$.tasks[1].rescued").value(true));
    }

    @Test
    void jobPlanReportsZeroAttemptsWithNoTaskAttemptsFile() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), "## T1 — a task\n- Goal: something\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(Files.createTempDirectory("ws-root").toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].attempts").value(0))
                .andExpect(jsonPath("$.tasks[0].rescued").value(false));
    }

    /** #339: the endpoint must actually re-parse BOTH runs' own IMPLEMENTATION_PLAN.md (not reuse
     *  a job's live-plan machinery, which is keyed by job id / a live workspace, not a finished
     *  run) and hand the real parsed task lists to TaskMatcher, returning whatever it matches. */
    @Test
    void matchTasksParsesBothRunsPlansAndReturnsTheMatcherResult() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-a"));
        Files.createDirectories(resultsDir.resolve("run-b"));
        Files.writeString(resultsDir.resolve("run-a/IMPLEMENTATION_PLAN.md"), "## T1 — deposits endpoint\n- Goal: handle deposits\n");
        Files.writeString(resultsDir.resolve("run-b/IMPLEMENTATION_PLAN.md"), "## T9 — account and deposits\n- Goal: accounts and money rules\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(taskMatcher.match(argThat(l -> l.size() == 1 && l.get(0).id.equals("T1")),
                argThat(l -> l.size() == 1 && l.get(0).id.equals("T9")), isNull(), any()))
                .thenReturn(List.of(new com.strgmai.ace.service.runner.TaskMatcher.TaskMatch("T1", "deposits endpoint", "T9", "account and deposits")));

        mvc.perform(post("/api/runs/run-a/compare/run-b/match-tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].aId").value("T1"))
                .andExpect(jsonPath("$.matches[0].bId").value("T9"))
                .andExpect(jsonPath("$.matches[0].bTitle").value("account and deposits"));
    }

    @Test
    void matchTasksTreatsAMissingPlanFileAsAnEmptyTaskListNotAnError() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-a"));
        Files.createDirectories(resultsDir.resolve("run-b"));
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(taskMatcher.match(List.of(), List.of(), null, resultsDir.resolve("run-a").toRealPath())).thenReturn(List.of());

        mvc.perform(post("/api/runs/run-a/compare/run-b/match-tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches").isEmpty());
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

    /** #355/#355-followup: once a task finishes, manifest.json carries its OWN rc/seconds/finish/
     *  etc. (RunBench writes it incrementally, not just at the end of the run) - but turns/
     *  compactions/tool_errors must come from the session file's own live count, NEVER from
     *  manifest's own copies of those three fields (#358 found manifest's own copy can be flat
     *  wrong - a timed-out attempt's result is partly synthetic). This fixture's manifest entry
     *  deliberately carries DIFFERENT turns/compactions/tool_errors values than the session file
     *  actually has, so a test that passed by accident (reading the manifest's own stale numbers)
     *  cannot happen here - the live session-file numbers must win. */
    @Test
    void taskStatsReadsTheTasksOwnManifestFieldsButAlwaysLiveCountsTurnsCompactionsAndToolErrors() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("manifest.json"), """
                {"tasks": [{"id": "T1", "rc": 0, "seconds": 120.5, "turns": 0, "compactions": 0,
                            "tool_errors": 0, "finish": "stop", "reported": "done"}]}""");
        Files.writeString(runDir.resolve("sessions/a.jsonl"), String.join("\n",
                "{\"label\":\"T1\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}",
                "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"read\",\"isError\":false}}",
                "{\"type\":\"compaction\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}",
                "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"bash\",\"isError\":true}}",
                "{\"type\":\"message\",\"message\":{\"role\":\"toolResult\",\"toolName\":\"bash\",\"isError\":false}}") + "\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"))
                .andExpect(jsonPath("$.rc").value(0))
                .andExpect(jsonPath("$.seconds").value(120.5))
                .andExpect(jsonPath("$.turns").value(2))
                .andExpect(jsonPath("$.compactions").value(1))
                .andExpect(jsonPath("$.tool_errors").value(1))
                .andExpect(jsonPath("$.tool_calls.read.count").value(1))
                .andExpect(jsonPath("$.tool_calls.bash.count").value(2))
                .andExpect(jsonPath("$.tool_calls.bash.errors").value(1));
    }

    /** #355-followup: no manifest.tasks entry yet (the task hasn't finished), but its own session
     *  file already exists - genuinely still running, or already past its main session and into
     *  its own wrap-up/rescue/handoff tail (which run AFTER the main session but BEFORE the
     *  manifest.tasks entry is written). Either way, live turns/compactions/tool_calls must be
     *  available WHILE the task is still in flight, not only once it is done - this is the whole
     *  point of reading the session file instead of a one-time snapshot. */
    @Test
    void taskStatsReportsRunningWithLiveCountsWhenASessionFileExistsButNoManifestEntryYet() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("manifest.json"), "{\"tasks\": []}");
        Files.writeString(runDir.resolve("sessions/a.jsonl"), String.join("\n",
                "{\"label\":\"T1\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}",
                "{\"type\":\"compaction\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}") + "\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.turns").value(2))
                .andExpect(jsonPath("$.compactions").value(1))
                .andExpect(jsonPath("$.rc").doesNotExist());
    }

    /** #355-followup: a task that got a false start (e.g. interrupted by an operator pause right
     *  after it began) ends up with TWO session files sharing its label - an empty/near-empty
     *  stale one from the false start, and the real one from the attempt that actually ran.
     *  sessionCounts() must pick the one with the latest mtime, not whichever Files.list() happens
     *  to enumerate first (that order is filesystem-dependent, not chronological) - otherwise the
     *  Stats tab can silently show zeros for a task that actually ran plenty of turns. */
    @Test
    void taskStatsUsesTheLatestSessionFileWhenMultipleFilesShareTheSameLabel() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("manifest.json"), "{\"tasks\": []}");
        final var staleFile = runDir.resolve("sessions/z_falsestart.jsonl");
        Files.writeString(staleFile, "{\"label\":\"T3\"}\n");
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.parse("2026-10-14T00:00:00Z")));
        final var realFile = runDir.resolve("sessions/a_real.jsonl");
        Files.writeString(realFile, String.join("\n",
                "{\"label\":\"T3\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}",
                "{\"type\":\"compaction\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"}}") + "\n");
        Files.setLastModifiedTime(realFile, FileTime.from(Instant.parse("2026-10-14T06:00:00Z")));
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T3/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.turns").value(2))
                .andExpect(jsonPath("$.compactions").value(1));
    }

    /** #355-followup: a task RunBench finds already committed on resume (its phase/<id> tag
     *  already exists from a prior attempt) gets a bare {id, rc, resumed} manifest entry -
     *  RunBench never carries the real seconds/turns/etc. forward for a skip-resumed task, see
     *  sessionCounts()'s own javadoc. turns/compactions were already fixed to always read live
     *  (previous test) - "seconds" must fall back to the session file's own first-to-last
     *  timestamp span the same way, rather than showing nothing just because this one task
     *  happened to be skip-resumed instead of freshly run. */
    @Test
    void taskStatsFallsBackToTheSessionFilesOwnTimestampSpanWhenTheManifestEntryHasNoSecondsField() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("manifest.json"), """
                {"tasks": [{"id": "T3", "rc": 0, "resumed": true}]}""");
        Files.writeString(runDir.resolve("sessions/a.jsonl"), String.join("\n",
                "{\"label\":\"T3\",\"ts\":\"2026-10-14T12:00:00Z\"}",
                "{\"type\":\"message\",\"message\":{\"role\":\"assistant\"},\"ts\":\"2026-10-14T12:00:10Z\"}",
                "{\"type\":\"end\",\"turns\":1,\"ts\":\"2026-10-14T12:05:00Z\"}") + "\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T3/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("done"))
                .andExpect(jsonPath("$.seconds").value(300.0));
    }

    /** #355: once the WHOLE run finishes (not just this task), oracle.json/metrics.json also
     *  exist alongside manifest.json - this task's own check-score attribution (metrics.json's
     *  "steps" block, #337-adjacent) and compaction overhead (#353/#354) merge in too, read from
     *  the exact same directory, no job-to-run bridge needed at all. */
    @Test
    void taskStatsMergesInCheckScoreAndCompactionOverheadOnceTheWholeRunIsScored() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir.resolve("sessions"));
        Files.writeString(runDir.resolve("manifest.json"), "{\"tasks\": [{\"id\": \"T1\", \"rc\": 0}]}");
        Files.writeString(runDir.resolve("metrics.json"), """
                {"steps": {"T1": {"score_pct": 100.0, "points": [3, 3]}},
                 "compaction_overhead": {"total_sec": 40.0, "tasks": {"T1": {"overhead_sec": 40.0, "events": 1}}}}""");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check_score.score_pct").value(100.0))
                .andExpect(jsonPath("$.check_score.points[0]").value(3))
                .andExpect(jsonPath("$.compaction_overhead.overhead_sec").value(40.0))
                .andExpect(jsonPath("$.compaction_overhead.events").value(1));
    }

    /** #355-followup: no manifest.tasks entry AND no session file for this task at all - genuinely
     *  hasn't started yet, distinct from "running" (a session file exists but no manifest entry
     *  yet - see taskStatsReportsRunningWithLiveCountsWhenASessionFileExistsButNoManifestEntryYet). */
    @Test
    void taskStatsReportsNotStartedWhenNeitherAManifestEntryNorASessionFileExistsYet() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-1"));
        Files.writeString(resultsDir.resolve("run-1/manifest.json"), "{\"tasks\": []}");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T9/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("not_started"));
    }

    @Test
    void taskStatsReportsNotStartedWhenNoManifestExistsAtAll() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        Files.createDirectories(resultsDir.resolve("run-1"));
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("not_started"));
    }

    /** #259: a finished task's own net change is the diff between its phase tag and that tag's
     *  first parent - exactly what that task's own commit introduced, nothing from before it. */
    @Test
    void taskDiffShowsTheNetChangeBetweenTheTaskTagAndItsFirstParentWhenFinished() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App { int x = 1; }");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.diff", containsString("App.java")))
                .andExpect(jsonPath("$.diff", containsString("+class App")))
                .andExpect(jsonPath("$.new_files").isEmpty());
    }

    /** #260, found live: a full unpacked Eclipse Temurin JDK landed at .toolchains/ straight in a
     *  real run's project root and got committed whole alongside the task's real source - with
     *  nothing to exclude it (predating VENDORED_PATHS growing that entry), it's still TRACKED in
     *  history, so the fix has to drop it at display time, not just stop it being re-added. */
    @Test
    void taskDiffDropsVendoredFilesLikeAnUnpackedJdkEvenWhenTheyAreTrackedInHistory() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App { int x = 1; }");
        Files.createDirectories(ws.resolve(".toolchains/jdk-21/Contents/Home/bin"));
        Files.writeString(ws.resolve(".toolchains/jdk-21/Contents/Home/NOTICE"), "Notices for Eclipse Temurin");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff", containsString("App.java")))
                .andExpect(jsonPath("$.diff", not(containsString(".toolchains"))))
                .andExpect(jsonPath("$.diff", not(containsString("Temurin"))));
    }

    /** #260 regression, found live: a first attempt at this filter post-processed the diff TEXT,
     *  skipping a file's hunk only once its own "+++ b/"/"--- a/" line confirmed a vendored path -
     *  but a BINARY file (every single file in a real unpacked JDK, confirmed live) gets neither
     *  line at all; git emits "Binary files a/X and b/X differ" instead, so that filter missed
     *  every one of them. The actual fix pushes the exclusion into the git invocation itself
     *  (pathspec ":!<prefix>**"), which handles binary/text/renamed/mode-only changes identically
     *  since it operates before git ever produces output, not after. */
    @Test
    void taskDiffDropsVendoredBinaryFilesToo() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App { int x = 1; }");
        Files.createDirectories(ws.resolve(".toolchains/jdk-21/Contents/Home/bin"));
        // a NUL byte is exactly what makes git classify a file as binary, same as a real `java` executable
        Files.write(ws.resolve(".toolchains/jdk-21/Contents/Home/bin/java"), new byte[]{0x7f, 'E', 'L', 'F', 0x00, 0x01, 0x02});
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff", containsString("App.java")))
                .andExpect(jsonPath("$.diff", not(containsString(".toolchains"))))
                .andExpect(jsonPath("$.diff", not(containsString("Binary files"))));
    }

    /** Same filter, the in-progress/untracked path - deliberately set up like a workspace
     *  INITIALISED BEFORE .toolchains/ was ever added to VENDORED_PATHS (its own .git/info/exclude
     *  predates the fix and has no idea about it), so only the code-level filter in taskDiff()
     *  itself - not git's own --exclude-standard - can be what catches this. */
    @Test
    void taskDiffDropsVendoredNewFilesWhenStillInProgress() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        new ProcessBuilder("git", "-C", ws.toString(), "init", "-q").start().waitFor();
        new ProcessBuilder("git", "-C", ws.toString(), "config", "user.email", "bench@local").start().waitFor();
        new ProcessBuilder("git", "-C", ws.toString(), "config", "user.name", "bench").start().waitFor();
        Files.createDirectories(ws.resolve(".git/info"));
        Files.writeString(ws.resolve(".git/info/exclude"), "build/\n.gradle/\n");   // no .toolchains/ - an old workspace
        new ProcessBuilder("git", "-C", ws.toString(), "commit", "-q", "--allow-empty", "-m", "phase/start").start().waitFor();
        new ProcessBuilder("git", "-C", ws.toString(), "tag", "phase/start").start().waitFor();

        Files.writeString(ws.resolve("Service.java"), "class Service {}");
        Files.createDirectories(ws.resolve(".toolchains/jdk-21/Contents/Home/bin"));
        Files.writeString(ws.resolve(".toolchains/jdk-21/Contents/Home/NOTICE"), "Notices for Eclipse Temurin");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.new_files[0].path").value("Service.java"))
                .andExpect(jsonPath("$.new_files", org.hamcrest.Matchers.hasSize(1)));
    }

    /** #259 regression, found live: the AGENT's own session can `git commit` its own work itself
     *  (its own message, nothing to do with our phase/* tags) before our own snapshot() ever runs -
     *  when that happens, snapshot()'s wrapper commit has nothing left to add (--allow-empty lets
     *  it succeed anyway), so the task's real change sits in the tag's PARENT commit, not inside
     *  the tag's own commit at all. A naive <tag>^..{tag} diff came back silently empty on the very
     *  first real task of a real run despite 38 real files having been added - this is exactly what
     *  nearestPhaseTagSha()'s tag-walk (not a plain git-parent walk) exists to survive. */
    @Test
    void taskDiffFindsTheRealChangeEvenWhenTheAgentCommittedItBeforeOurOwnSnapshotDid() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App { int x = 1; }");
        new ProcessBuilder("git", "-C", ws.toString(), "add", "-A").start().waitFor();
        new ProcessBuilder("git", "-C", ws.toString(), "commit", "-q", "-m", "T1: agent's own commit message").start().waitFor();
        // our own snapshot() finds nothing left to add - its commit is empty, same as it was live
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.diff", containsString("App.java")))
                .andExpect(jsonPath("$.diff", containsString("+class App")));
    }

    /** A task still running (or paused mid-session) has no phase tag yet: "before" is HEAD and the
     *  change so far is the live, uncommitted working tree - a modified tracked file shows up in
     *  diff, and a brand new untracked file (git diff alone never shows those) shows up in
     *  new_files instead, full content included. */
    @Test
    void taskDiffShowsTheLiveWorkingTreeAgainstHeadWhenStillInProgress() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("App.java"), "class App {}");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        Files.writeString(ws.resolve("App.java"), "class App { int y = 2; }");
        Files.writeString(ws.resolve("NewThing.java"), "class NewThing {}");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T2/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.after_sha").value(""))
                .andExpect(jsonPath("$.diff", containsString("App.java")))
                .andExpect(jsonPath("$.new_files[0].path").value("NewThing.java"))
                .andExpect(jsonPath("$.new_files[0].content", containsString("class NewThing")));
    }

    /** Same bug, same fix, the in-progress branch: before HAS to walk past any commit the agent
     *  already made on its own, not stop at git-parent-of-HEAD (which IS that commit whenever one
     *  exists) - otherwise the diff would only ever show the delta since that commit, missing
     *  everything the agent already committed itself earlier in the same still-running session. */
    @Test
    void taskDiffIncludesWorkTheAgentAlreadyCommittedItselfPlusWhateverIsStillUncommitted() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        // T2 is in progress: the agent already committed some of its own work, unprompted...
        Files.writeString(ws.resolve("Service.java"), "class Service { int a = 1; }");
        new ProcessBuilder("git", "-C", ws.toString(), "add", "-A").start().waitFor();
        new ProcessBuilder("git", "-C", ws.toString(), "commit", "-q", "-m", "T2: wip").start().waitFor();
        // ...and still has further, uncommitted work on top of that
        Files.writeString(ws.resolve("Service.java"), "class Service { int a = 1; int b = 2; }");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T2/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(false))
                // Service.java must show as a brand NEW file (relative to phase/T1) - if "before"
                // had stopped at the agent's own commit instead, this would be a plain one-line
                // modification with no "new file mode" header at all
                .andExpect(jsonPath("$.diff", containsString("new file mode")))
                .andExpect(jsonPath("$.diff", containsString("a = 1")))
                .andExpect(jsonPath("$.diff", containsString("b = 2")));
    }

    @Test
    void taskDiffIsNotStartedWhenTheWorkspaceDoesNotExistYet() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");   // no run-1 dir created inside at all
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T1/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.finished").value(false));
    }

    /** Found live: a restarted task's judge checkpoints used to share the exact same filenames as
     *  its earlier, abandoned attempt's (judge/T13-turn10.md, both times) - the page showed a mix
     *  of stale and current comments with no way to tell them apart. Two attempt stamps here,
     *  written out of chronological order on disk, to confirm the LATER one wins regardless of
     *  write order (same reasoning as Tailer's own filename-sort fix). */
    @Test
    void taskJudgeShowsOnlyTheLatestAttemptsCheckpoints() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var judgeDir = resultsDir.resolve("run-1/judge");
        Files.createDirectories(judgeDir);
        Files.writeString(judgeDir.resolve("T13-2026-10-10T08-00-00.000Z-turn10.md"),
                "# Judge checkpoint: T13, turn 10 (attempt 2026-10-10T08-00-00.000Z)\n\n"
                        + "## Prompt\n\nold prompt\n\n## Recommendation\n\nstale, from the abandoned attempt\n");
        Files.writeString(judgeDir.resolve("T13-2026-10-10T15-00-00.000Z-turn10.md"),
                "# Judge checkpoint: T13, turn 10 (attempt 2026-10-10T15-00-00.000Z)\n\n"
                        + "## Prompt\n\nnew prompt\n\n## Recommendation\n\ncurrent, from the restarted attempt\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T13/judge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].turn").value(10))
                .andExpect(jsonPath("$[0].recommendation").value("current, from the restarted attempt"));
    }

    /** Backward compatibility: a judge file written before this fix existed has no attempt stamp
     *  at all (judge/T13-turn5.md) - it must still be read, not silently become invisible. */
    @Test
    void taskJudgeStillReadsLegacyUnstampedCheckpointFiles() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var judgeDir = resultsDir.resolve("run-1/judge");
        Files.createDirectories(judgeDir);
        Files.writeString(judgeDir.resolve("T13-turn5.md"), "# Judge checkpoint: T13, turn 5\n\n"
                + "## Prompt\n\np\n\n## Recommendation\n\nlegacy, unstamped\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T13/judge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recommendation").value("legacy, unstamped"));
    }

    /** A legacy unstamped file and a NEW stamped attempt can coexist on disk (a task restarted
     *  right after this fix shipped) - the stamped one must still win, since "" (no stamp) sorts
     *  before any real timestamp. */
    @Test
    void taskJudgePrefersAStampedAttemptOverALegacyUnstampedOne() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var judgeDir = resultsDir.resolve("run-1/judge");
        Files.createDirectories(judgeDir);
        Files.writeString(judgeDir.resolve("T13-turn5.md"), "# Judge checkpoint: T13, turn 5\n\n"
                + "## Prompt\n\np\n\n## Recommendation\n\nlegacy, unstamped\n");
        Files.writeString(judgeDir.resolve("T13-2026-10-10T15-00-00.000Z-turn5.md"),
                "# Judge checkpoint: T13, turn 5 (attempt 2026-10-10T15-00-00.000Z)\n\n"
                        + "## Prompt\n\np\n\n## Recommendation\n\ncurrent, stamped\n");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1"));

        mvc.perform(get("/api/jobs/" + JOB_1 + "/tasks/T13/judge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recommendation").value("current, stamped"));
    }

    @Test
    void interveneQueuesTheOperatorsTextForTheGivenTask() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "running"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/intervene").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"stop retrying X, do Y instead\"}"))
                .andExpect(status().isOk());

        final var delivered = intervene.poll("run-1", "T2");
        assertTrue(delivered.contains("stop retrying X, do Y instead"));
    }

    @Test
    void interveneRejectsBlankText() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "running"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/intervene").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restartTaskRequiresAPausedJob() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "running"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/restart").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("paused")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void restartTaskRequiresAnExistingPhaseTag() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");   // T2 itself never ran
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/restart").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("nothing to restart")));
    }

    @Test
    void restartTaskRejectsUnknownOverrideFields() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/restart").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"model\":\"some-other-model\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("model")));
    }

    /** #263: the full flow - a backup tag preserves the discarded state, the workspace resets to
     *  right before T2 (T2's own net change AND T3's, which depends on it, both get discarded),
     *  phase/T2 and phase/T3's own tags (and their handoffs) are gone so R18 will redo them on
     *  resume, T1's own tag is untouched, and the override gets persisted for T2 specifically. */
    @Test
    void restartTaskCreatesBackupResetsWorkspaceAndCascadesTagDeletion() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), """
                ## T1 — first
                - Goal: a
                ## T2 — second
                - Goal: b
                - Dependencies: T1
                ## T3 — third
                - Goal: c
                - Dependencies: T2
                """);
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("T1.txt"), "t1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1-handoff");
        Files.writeString(ws.resolve("T2.txt"), "t2 (the buggy attempt)");
        final String beforeT2 = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2-handoff");
        Files.writeString(ws.resolve("T3.txt"), "t3 (built on the buggy T2)");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T3");
        final String beforeRestartHead = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));
        when(queue.setTaskOverride(eq(JOB_1), eq("T2"), anyMap()))
                .thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/restart").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"task_wall_sec\":40000}}"))
                .andExpect(status().isOk());

        verify(queue).setTaskOverride(JOB_1, "T2", Map.of("task_wall_sec", 40000));
        // the backup tag exists and still points at the pre-reset HEAD (T3's own commit)
        final String backupTags = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "-l", "backup/T2-*");
        assertFalse(backupTags.isBlank(), "a backup tag must exist before anything is discarded");
        assertEquals(beforeRestartHead, com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", backupTags.strip()),
                "the backup tag must capture HEAD exactly as it was right before the reset (T3's own commit)");
        // the workspace itself is back to exactly T2's own pre-state - T3's file is gone, T2's own is gone too
        assertEquals(beforeT2, com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD"));
        assertTrue(Files.isRegularFile(ws.resolve("T1.txt")));
        assertFalse(Files.exists(ws.resolve("T2.txt")));
        assertFalse(Files.exists(ws.resolve("T3.txt")));
        // T1 is untouched; T2 and T3 (cascaded, since T3 depends on T2) are cleared for R18 to redo
        assertTrue(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T1"));
        assertTrue(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T1-handoff"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T2"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T2-handoff"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T3"));
    }

    /** #321: the pre-run counterpart of restartTask() - a task that has never run has no
     *  phase/{id} tag to reset to, so this endpoint takes a plain paused job and writes the
     *  override with no git involvement at all. */
    @Test
    void pendingOverrideRequiresAPausedJob() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "running"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/override").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("paused")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void pendingOverrideRejectsATaskThatAlreadyRan() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");   // T2 already ran
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/override").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already run")))
                .andExpect(jsonPath("$.detail", containsString("/restart")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void pendingOverrideRejectsAnUnknownTaskId() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), "## T2 — second\n- Goal: b\n");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T99/override").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("T99")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void pendingOverrideRejectsUnknownOverrideFields() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), "## T2 — second\n- Goal: b\n");
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/override").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"model\":\"some-other-model\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("model")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void pendingOverrideWritesTheOverrideWithNoGitSideEffects() throws Exception {
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");   // T2 itself never ran
        final String beforeHead = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD");
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), """
                ## T1 — first
                - Goal: a
                ## T2 — second
                - Goal: b
                - Dependencies: T1
                """);
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));
        when(queue.setTaskOverride(eq(JOB_1), eq("T2"), anyMap()))
                .thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/tasks/T2/override").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"judge_enabled\":true,\"judge_every_n_turns\":10,\"budget_rescue_enabled\":true}}"))
                .andExpect(status().isOk());

        verify(queue).setTaskOverride(JOB_1, "T2", Map.of("judge_enabled", true, "judge_every_n_turns", 10, "budget_rescue_enabled", true));
        // unlike restartTask(), nothing in the workspace is touched - no backup tag, no reset
        assertTrue(com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "-l", "backup/*").isBlank());
        assertEquals(beforeHead, com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T2"));
    }

    @Test
    void restartSessionRejectsAnUnknownName() throws Exception {
        mvc.perform(post("/api/jobs/" + JOB_1 + "/sessions/T2/restart").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not a restartable phase session")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    @Test
    void restartSessionRequiresAPausedJob() throws Exception {
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "running"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/sessions/p1_plan/restart").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("paused")));
        verify(queue, never()).setTaskOverride(any(), any(), any());
    }

    /** #264: restarting p1_plan - a PRE-task phase - must cascade through PARALLEL_PLAN and every
     *  task (T1, T2), since the plan itself is being redone, but must leave phase/p0 (an EARLIER
     *  phase) completely untouched. Mirrors restartTaskCreatesBackupResetsWorkspaceAndCascadesTagDeletion's
     *  own shape, one phase up the chain. */
    @Test
    void restartSessionCascadesThroughLaterPhasesAndEveryTask() throws Exception {
        final var resultsDir = Files.createTempDirectory("results");
        final var runDir = resultsDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("IMPLEMENTATION_PLAN.md"), """
                ## T1 — first
                - Goal: a
                ## T2 — second
                - Goal: b
                - Dependencies: T1
                """);
        final var wsRoot = Files.createTempDirectory("ws-root");
        final var ws = wsRoot.resolve("run-1").resolve("workspace");
        Files.createDirectories(ws);
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/start");
        Files.writeString(ws.resolve("p0.txt"), "task definition");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/p0");
        final String beforeP1 = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD");
        Files.writeString(ws.resolve("p1.txt"), "the plan (truncated, this is the bug)");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/p1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/parallel_plan");
        Files.writeString(ws.resolve("T1.txt"), "t1");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T1");
        Files.writeString(ws.resolve("T2.txt"), "t2");
        com.strgmai.ace.service.runner.RunBenchSupport.snapshot(ws, "phase/T2");
        final String beforeRestartHead = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD");
        when(props.resultsDir()).thenReturn(resultsDir.toString());
        when(props.workspaceRoot()).thenReturn(wsRoot.toString());
        when(queue.get(JOB_1)).thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));
        when(queue.setTaskOverride(eq(JOB_1), eq("p1_plan"), anyMap()))
                .thenReturn(Map.of("id", JOB_1.toString(), "run_id", "run-1", "status", "paused"));

        mvc.perform(post("/api/jobs/" + JOB_1 + "/sessions/p1_plan/restart").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":{\"task_wall_sec\":5000}}"))
                .andExpect(status().isOk());

        verify(queue).setTaskOverride(JOB_1, "p1_plan", Map.of("task_wall_sec", 5000));
        final String backupTags = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "-l", "backup/p1_plan-*");
        assertFalse(backupTags.isBlank(), "a backup tag must exist before anything is discarded");
        assertEquals(beforeRestartHead, com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", backupTags.strip()));
        assertEquals(beforeP1, com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", "HEAD"));
        assertTrue(Files.isRegularFile(ws.resolve("p0.txt")));
        assertFalse(Files.exists(ws.resolve("p1.txt")));
        assertFalse(Files.exists(ws.resolve("T1.txt")));
        assertFalse(Files.exists(ws.resolve("T2.txt")));
        // p0 is EARLIER and untouched; p1/parallel_plan/every task are cleared for R18 to redo
        assertTrue(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/p0"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/p1"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/parallel_plan"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T1"));
        assertFalse(com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/T2"));
    }

    @Test
    void sessionPromptReturnsTheFixedInstructionForPlainPlanPhases() throws Exception {
        mvc.perform(get("/api/jobs/" + JOB_1 + "/sessions/p1_plan/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.text", containsString("IMPLEMENTATION_PLAN.md")));
    }

    @Test
    void sessionPromptIsGracefulForAnUnknownName() throws Exception {
        mvc.perform(get("/api/jobs/" + JOB_1 + "/sessions/T7/prompt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.text").value(""));
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

    // ---- #270: GET /api/runs/{id}/files - the listing moved here from ace-ui-vaadin's own
    // FilesBrowser, which used to read run.results_dir() off the local filesystem directly; these
    // pin the exact semantics FilesBrowserTest's own (now-removed) listFiles()/sizeOf() tests used
    // to cover, now against a real response from this endpoint.

    @Test
    void filesListsTopLevelSortedPlusOneSubdirLevelExcludingWorkspace() throws Exception {
        final var resultsDir = Files.createTempDirectory("files-test");
        Files.writeString(resultsDir.resolve("oracle.json"), "{}");
        Files.writeString(resultsDir.resolve("alpha.log"), "x");
        Files.createDirectories(resultsDir.resolve("instructions"));
        Files.writeString(resultsDir.resolve("instructions/T1.json"), "{}");
        Files.writeString(resultsDir.resolve("instructions/T2.json"), "{}");
        Files.createDirectories(resultsDir.resolve("instructions/deep"));
        Files.writeString(resultsDir.resolve("instructions/deep/T3.json"), "{}");   // two levels deep: must not appear
        Files.createDirectories(resultsDir.resolve("workspace"));
        Files.writeString(resultsDir.resolve("workspace/secret.json"), "{}");   // never listed
        Files.createDirectory(resultsDir.resolve("emptydir"));   // a dir with no regular files contributes nothing

        dsl.insertInto(RUNS).set(RUNS.RUN_ID, "files-run").set(RUNS.RESULTS_DIR, resultsDir.toString())
                .set(RUNS.TASK, "t").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}").execute();

        mvc.perform(get("/api/runs/files-run/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].path").value("alpha.log"))
                .andExpect(jsonPath("$[1].path").value("instructions/T1.json"))
                .andExpect(jsonPath("$[2].path").value("instructions/T2.json"))
                .andExpect(jsonPath("$[3].path").value("oracle.json"))
                .andExpect(jsonPath("$[*].path", not(hasItem(containsString("workspace")))))
                .andExpect(jsonPath("$[*].path", not(hasItem(containsString("deep")))));
    }

    @Test
    void filesReportsTheRealByteSizeOfEachFile() throws Exception {
        final var resultsDir = Files.createTempDirectory("files-test-size");
        Files.writeString(resultsDir.resolve("a.json"), "x".repeat(512));

        dsl.insertInto(RUNS).set(RUNS.RUN_ID, "files-size-run").set(RUNS.RESULTS_DIR, resultsDir.toString())
                .set(RUNS.TASK, "t").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}").execute();

        mvc.perform(get("/api/runs/files-size-run/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("a.json"))
                .andExpect(jsonPath("$[0].size").value(512));
    }

    @Test
    void filesOnAnUnimportedRunIdIs404NotAnEmptyList() throws Exception {
        mvc.perform(get("/api/runs/not-a-real-run/files")).andExpect(status().isNotFound());
    }

    @Test
    void aBareFilesRequestWithNoPathSegmentListsRatherThanFallingIntoTheFileServingRoute() throws Exception {
        // regression guard: /api/runs/{id}/files/{*path} (the file-SERVING sibling) must never
        // swallow this bare, path-less request - it would otherwise try to serve a file literally
        // named "" and 404 for the wrong reason (not found) instead of this returning a real list.
        final var resultsDir = Files.createTempDirectory("files-test-bare");
        Files.writeString(resultsDir.resolve("only.txt"), "x");
        dsl.insertInto(RUNS).set(RUNS.RUN_ID, "files-bare-run").set(RUNS.RESULTS_DIR, resultsDir.toString())
                .set(RUNS.TASK, "t").set(RUNS.MODEL, "m").set(RUNS.MODE, "monolithic")
                .set(RUNS.KEY_HASH, "k").set(RUNS.POOLABLE, true).set(RUNS.ORACLE, "{}").execute();

        mvc.perform(get("/api/runs/files-bare-run/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("only.txt"));
    }
}
