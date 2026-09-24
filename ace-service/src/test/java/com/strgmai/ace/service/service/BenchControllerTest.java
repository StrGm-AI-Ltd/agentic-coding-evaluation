package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.metrics.StatsService;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;
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
            out.put("functional", Map.of("mean", mean));
            return out;
        });

        mvc.perform(get("/api/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranked.length()").value(2))                    // keyA and keyC: k=5
                .andExpect(jsonPath("$.indicative.length()").value(1))                // keyB: k=2, never ranked
                .andExpect(jsonPath("$.indicative[0].key_hash").value("keyB"))
                .andExpect(jsonPath("$.ranked[0].key_hash").value("keyC"))            // 90.0 mean sorts before 40.0
                .andExpect(jsonPath("$.ranked[1].key_hash").value("keyA"));
    }
}
