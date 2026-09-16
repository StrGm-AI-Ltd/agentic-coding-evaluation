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
}
