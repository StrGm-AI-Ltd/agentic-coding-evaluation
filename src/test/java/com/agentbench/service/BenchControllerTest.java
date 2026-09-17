package com.agentbench.service;

import com.agentbench.config.BenchProperties;
import com.agentbench.metrics.StatsService;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** #6: jsonb columns come back from JdbcTemplate as a raw org.postgresql.util.PGobject; with no
 *  Jackson customization, Jackson bean-serializes it via its getType()/getValue() getters into
 *  {"type":"jsonb","value":"<escaped string>"} instead of real nested JSON. This slice test drives
 *  the SAME Jackson pipeline the running app uses (real HTTP round-trip through MockMvc, no
 *  hand-built ObjectMapper) with a PGobject built exactly as the postgresql driver builds one, so
 *  it needs no live Postgres to reproduce or lock down the fix. It also covers the ApiExceptionHandler
 *  (#13) status/body mapping. @WebMvcTest loads only BenchController + MVC/Jackson infrastructure -
 *  it never touches TradingController, so it is unaffected by that controller's unrelated missing-bean
 *  startup issue (see the session report). */
@WebMvcTest(BenchController.class)
class BenchControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private JdbcTemplate jdbc;
    @MockitoBean private JobQueue queue;
    @MockitoBean private ImporterService importer;
    @MockitoBean private ExperimentsService experiments;
    @MockitoBean private StatsService stats;
    @MockitoBean private WorkerService worker;
    @MockitoBean private BenchProperties props;
    @MockitoBean private Preflight preflight;
    @MockitoBean private TreatmentPin pin;

    private static PGobject jsonb(String json) throws Exception {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(json);
        return pg;
    }

    @Test
    void jsonbColumnsComeBackAsRealNestedJsonNotAPGobjectWrapper() throws Exception {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", 1L);
        job.put("run_id", "run-1");
        job.put("argv", jsonb("[\"--task=L3p\",\"--model=m\"]"));   // exactly what the pg driver hands back for a jsonb column
        when(queue.list()).thenReturn(List.of(job));

        mvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].argv").isArray())
                .andExpect(jsonPath("$[0].argv[0]").value("--task=L3p"))
                .andExpect(jsonPath("$[0].argv[1]").value("--model=m"))
                .andExpect(jsonPath("$[0].argv.type").doesNotExist())     // must NOT be {"type":"jsonb","value":"..."}
                .andExpect(jsonPath("$[0].argv.value").doesNotExist());
    }

    @Test
    void jsonbObjectColumnAlsoComesBackNested() throws Exception {
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("id", 1L);
        exp.put("params", jsonb("{\"model\":\"m\",\"nested\":{\"a\":1}}"));
        when(jdbc.queryForList("SELECT * FROM experiments ORDER BY id DESC")).thenReturn(List.of(exp));

        mvc.perform(get("/api/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].params.model").value("m"))
                .andExpect(jsonPath("$[0].params.nested.a").value(1))
                .andExpect(jsonPath("$[0].params.type").doesNotExist());
    }

    @Test
    void modelsListsWhateverTheModelServerCurrentlyServesSorted() throws Exception {
        when(experiments.localModelSpecs()).thenReturn(Map.of("Qwen3.6-27B-graft", 65536, "Qwen3.8-27B-graft", 131072));

        mvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Qwen3.6-27B-graft"))   // sorted, not insertion order
                .andExpect(jsonPath("$[1]").value("Qwen3.8-27B-graft"));
    }

    @Test
    void modelsIsAnEmptyListNotA500WhenTheModelServerIsUnreachable() throws Exception {
        when(experiments.localModelSpecs()).thenReturn(Map.of());

        mvc.perform(get("/api/models")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void importAllServesRunIdListsAsJsonArraysNotCounts() throws Exception {
        // the actual bug reported: the Vaadin UI's Api.ImportResult expects List<String>, and this
        // endpoint used to serve {"imported": 2, "skipped": 1} - a JSON parse error on the UI side
        when(props.resultsDir()).thenReturn("/tmp/results");
        when(importer.importAll(any())).thenReturn(Map.of("imported", List.of("run-a", "run-b"), "skipped", List.of("run-c")));

        mvc.perform(post("/api/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").isArray())
                .andExpect(jsonPath("$.imported[0]").value("run-a"))
                .andExpect(jsonPath("$.skipped[0]").value("run-c"));
    }

    @Test
    void jobByIdReturnsTheJob() throws Exception {
        when(queue.get(1L)).thenReturn(Map.of("id", 1L, "run_id", "run-1", "status", "queued"));

        mvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value("run-1"))
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void jobByIdMapsTheMissingJobTo404() throws Exception {
        // JobQueue.get() already throws this for a missing row; the endpoint was simply never wired
        when(queue.get(99L)).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        mvc.perform(get("/api/jobs/99")).andExpect(status().isNotFound());
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
        doThrow(new NoSuchElementException("job 42 does not exist")).when(queue).setPriority(42L, 5);

        mvc.perform(post("/api/jobs/42/priority").contentType(MediaType.APPLICATION_JSON).content("{\"priority\": 5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("job 42 does not exist"));
    }

    @Test
    void illegalStateExceptionMapsTo409WithDetailBody() throws Exception {
        when(queue.cancel(7L)).thenThrow(new IllegalStateException("job 7 is already succeeded"));

        mvc.perform(post("/api/jobs/7/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("job 7 is already succeeded"));
    }

    private static Map<String, Object> runRow(String task, String model, String keyHash, String runId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runId); row.put("task", task); row.put("model", model);
        row.put("mode", "monolithic"); row.put("key_hash", keyHash); row.put("results_dir", "/results/" + runId);
        return row;
    }

    /** /api/groups' own orchestration - grouping DB rows by (task, model, key_hash), the k>=5
     *  ranked/indicative split, and sorting ranked by functional mean descending - independent of
     *  the math inside StatsService.summarize() (covered by StatsServiceTest). stats is a full mock
     *  here: summarize()'s return drives the branching exactly as the real one would from real k. */
    @Test
    void groupsSplitsRankedFromIndicativeByKAndSortsRankedByFunctionalMeanDescending() throws Exception {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) rows.add(runRow("L3p_point_in_time", "low-scorer", "keyA", "runA" + i));
        for (int i = 1; i <= 2; i++) rows.add(runRow("L3p_point_in_time", "too-few", "keyB", "runB" + i));
        for (int i = 1; i <= 5; i++) rows.add(runRow("L3p_point_in_time", "high-scorer", "keyC", "runC" + i));
        when(jdbc.queryForList("SELECT run_id, task, model, mode, key_hash, results_dir FROM runs WHERE poolable ORDER BY run_id"))
                .thenReturn(rows);
        // stats.load(path) tags each summary with the model its results_dir belongs to, so the
        // summarize() stub below can tell the three groups apart without inspecting real files
        when(stats.load(any())).thenAnswer(inv -> {
            String dir = inv.getArgument(0).toString();
            String model = dir.contains("runA") ? "low-scorer" : dir.contains("runB") ? "too-few" : "high-scorer";
            return new StatsService.RunSummary(dir, "L3p_point_in_time", model, "monolithic", null, null, null, null,
                    true, List.of(), false, true, 0, 0, Map.of(), List.of(), Map.of());
        });
        when(stats.filterRuns(any(), eq(false), eq(false), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stats.summarize(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") List<StatsService.RunSummary> runs = (List<StatsService.RunSummary>) inv.getArgument(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("k", runs.size());
            double mean = switch (runs.get(0).model()) { case "low-scorer" -> 40.0; case "high-scorer" -> 90.0; default -> 10.0; };
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
