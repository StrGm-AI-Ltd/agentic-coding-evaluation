package com.strgmai.ace.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire tests against an in-process stub of the FastAPI service (zero extra
 * dependencies). Every endpoint, both POST body shapes, the 422/404 paths and
 * the read-timeout budget are covered — with no live service required.
 */
class ServiceClientWireTest {

    private record Captured(String method, String uri, String body) {
    }

    private static final String RUN_LIST_JSON = """
            [
              {"run_id":"r1","task":"L3p_point_in_time","mode":"orchestrated","model":"qwen","harness":"ref",
               "schema_version":3,"poolable":true,
               "functional_score_pct":95.0,"functional_points_got":19,"functional_denominator":20,
               "functional_ids":["F1","F2"],
               "weighted_score_pct":90.0,"points_got":65,"denominator":77,
               "valid":true,"validity_reasons":[],"contended":false,
               "wall_sec":1234.5,"completion_tokens":45678,"started":"2026-09-15T23:57:00Z"},
              {"run_id":"r2","task":"L2_one_endpoint","poolable":false,"valid":null},
              {"run_id":"r3","poolable":null}
            ]""";

    private static final String RUN_DETAIL_JSON = """
            {"run_id":"r1","results_dir":"/tmp/r1","task":"L3p_point_in_time","poolable":true,
             "manifest":{"provenance":{"model":"qwen","java_version":"21"},"usable_context":32000},
             "oracle":{"functional_ids":["F1"]},
             "metrics":{"per_task":{"T1":{"reported":"ok","done_verified":true,"requests":10,
                     "completion_tokens":100,"max_prompt":2000,"task_wall_sec":61.0,
                       "files_changed":2,"over_budget":false}},
                        "steps":{"plan":{"score_pct":100.0,"measured":true}}},
             "checks":[
               {"check_id":"F1","category":"functional","weight":1.0,"status":"pass","detail":null,"description":"F1 scenario"},
               {"check_id":"B1","category":"build","weight":4.0,"status":"fail","detail":"compile error","description":"gradle build"}
             ]}""";

    private static final String JOB_JSON = """
            {"id":"5","experiment_id":null,"arm":null,"repeat":null,"kind":"run","run_id":"r1",
             "argv":["--task=L1"],"status":"queued","blocked_reason":null,"priority":0,
             "pid":null,"exit_code":null,"cancel_requested":false,"stdout_path":"/tmp/x.log",
             "result_line":null,"enqueued_at":"2026-09-15 23:57:00","started_at":null,"finished_at":null}""";

    private static final String GROUPS_JSON = """
            {"ranked":[{"task":"L7_full_platform","model":"qwen","key_hash":"abc","mode":"orchestrated",
                        "run_ids":["r1","r2"],
                        "summary":{"k":6,"functional":{"mean":80.0,"ci90":[75.0,85.0],"n":6},
                                   "matrix":{"F1":{"pass_k":true,"pass_rate":1.0},"B1":{"pass_k":false,"pass_rate":0.4}}},
                        "printed":"k=6","refused":null}],
             "indicative":[]}""";

    private static final String EXPERIMENT_JSON = """
            {"id":"7","name":"he test","tag":"20260916-0000","template":"harness_effect",
             "params":{"arms":["orch","mono"]},"k":3,"status":"queued","comparison":null,
             "created_at":"2026-09-15 23:57:00","pinned_runner_sha":null,"pinned_oracle_sha":null,
             "jobs":[{"id":"5","arm":"orch","repeat":1,"run_id":"r1","status":"queued","result_line":null},
                     {"id":"6","arm":"mono","repeat":1,"run_id":"r2","status":"queued","result_line":null}]}""";

    private static final String PREFLIGHT_JSON = """
            {"running":false,"started_at":"2026-09-15 23:57:00","finished_at":"2026-09-15 23:58:00",
             "results":{"preflight":"ok"},"error":null}""";

    private static HttpServer server;
    private static ServiceClient client;
    private static final List<Captured> captured = new ArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String uri = exchange.getRequestURI().toString();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(method, uri, body));

            switch (method + " " + uri) {
                case "GET /api/runs/bad422" -> respond(exchange, 422, """
                        {"detail":[{"loc":["body","spec","harness"],"msg":"Input should be 'ref' or 'pi'"}]}""");
                case "GET /api/runs/missing" -> respond(exchange, 404, "{\"detail\":\"run missing is not imported\"}");
                case "GET /jobs/30/events" -> respond(exchange, 200, """
                        event: step_started
                        data: {"type": "step_started", "step": "T1", "continuation": false, "source": "packs"}

                        event: session_started
                        data: {"session_id": "755478fc-aaaa-bbbb-cccc-1a5b10584e41", "reasoning_effort": "high"}

                        event: request
                        data: {"type": "request", "seq": 1, "ts": "t1", "status": 200, "latency_sec": 1.5, "ttft_sec": 0.5, "budget_spent_completion_tokens": 557, "client_aborted": false}

                        event: status
                        data: {"status": "succeeded", "pid": 99, "result_line": "all done"}

                        """);
                case "GET /jobs/31/events" -> respond(exchange, 200, """
                        event: broken
                        data: {not json

                        event: status
                        data: {"status": "running", "pid": 7}

                        """);
                case "GET /runs/r1/files/slow" -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, "late");
                }
                default -> respond(exchange, 200, route(method, uri, body));
            }
        });
        server.start();
        client = new ServiceClient(
                new ServiceProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                RestClient.builder());
    }

    /** The list endpoint never carries jobs — same experiment, jobs stripped. */
    private static String experimentListJson() {
        return "[" + EXPERIMENT_JSON.replaceFirst("(?s),\\s*\\\"jobs\\\".*$", "}") + "]";
    }

    /** Route → canned response for the happy paths. */
    private static String route(String method, String uri, String body) {
        if (uri.startsWith("/api/runs?") || "GET /api/runs".equals(method + " " + uri)) {
            return RUN_LIST_JSON;
        }
        if ("GET /api/runs/r1".equals(method + " " + uri)) return RUN_DETAIL_JSON;
        if ("POST /api/runs/r1/rescore".equals(method + " " + uri)) return JOB_JSON;
        if ("GET /runs/r1/files/oracle.json".equals(method + " " + uri)) return "file content";
        if ("GET /runs/r1/files/a%20b.txt".equals(method + " " + uri)) return "spaced";
        if ("GET /api/jobs".equals(method + " " + uri)) return "[" + JOB_JSON + "]";
        if ("POST /api/jobs".equals(method + " " + uri)) return JOB_JSON;
        if ("POST /api/jobs/5/cancel".equals(method + " " + uri)) return JOB_JSON.replace("queued", "cancelled");
        if ("POST /api/jobs/5/requeue".equals(method + " " + uri)) return JOB_JSON;
        if ("PATCH /api/jobs/5".equals(method + " " + uri)) return JOB_JSON;
        if ("GET /api/jobs/5".equals(method + " " + uri)) return JOB_JSON;
        if ("GET /api/groups".equals(method + " " + uri)) return GROUPS_JSON;
        if ("POST /api/compare".equals(method + " " + uri)) {
            return body.contains("\"include_invalid\":true")
                    ? "{\"result\":null,\"printed\":\"refuse\",\"refused\":\"k < 5\"}"
                    : "{\"result\":{\"p\":0.4},\"printed\":\"Mann-Whitney\",\"refused\":null}";
        }
        if ("POST /api/import".equals(method + " " + uri)) {
            // the REAL shape: importer.import_all returns run-id lists, not counts
            return "{\"imported\":[\"r1\",\"r3\"],\"skipped\":[\"r2\"]}";
        }
        if ("POST /api/experiments".equals(method + " " + uri)) return EXPERIMENT_JSON;
        if ("GET /api/experiments/7".equals(method + " " + uri)) return EXPERIMENT_JSON;
        if (uri.equals("/api/experiments")) return experimentListJson();
        if (uri.equals("/api/preflight")) return PREFLIGHT_JSON;
        if (uri.equals("/api/models")) return "[\"Qwen3.6-27B-graft\",\"Qwen3.8-27B-graft\"]";
        throw new IllegalStateException("unexpected " + method + " " + uri);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static Captured last() {
        return captured.get(captured.size() - 1);
    }

    @Test
    void runs_omitsBlankFiltersAndMapsSnakeCase() {
        List<Api.Run> runs = client.runs(null, null, null, null, null);
        assertEquals("/api/runs", last().uri(), "blank filters must produce no query string");
        assertEquals(3, runs.size());
        Api.Run first = runs.get(0);
        assertEquals("r1", first.run_id());
        assertEquals("L3p_point_in_time", first.task());
        assertTrue(first.poolable());
        assertEquals(95.0, first.functional_score_pct());
        assertEquals(List.of("F1", "F2"), first.functional_ids());
        assertNull(first.checks(), "the list endpoint carries no checks");
        Api.Run second = runs.get(1);
        assertNull(second.valid());
        // the service answers null when it cannot decide — must map to false, not fail the list
        assertFalse(runs.get(2).poolable());
    }

    @Test
    void runs_sendsEachFilterWhenSet() {
        client.runs("L7_full_platform", "qwen", "orchestrated", "true", "false");
        String uri = last().uri();
        assertTrue(uri.startsWith("/api/runs?"));
        for (String expected : new String[]{"task=L7_full_platform", "model=qwen", "mode=orchestrated",
                "valid=true", "poolable=false"}) {
            assertTrue(uri.contains(expected), uri + " must contain " + expected);
        }
    }

    @Test
    void runDetail_mapsChecksManifestOracleMetrics() {
        Api.Run run = client.run("r1");
        assertEquals(2, run.checks().size());
        assertEquals("F1", run.checks().get(0).check_id());
        assertNull(run.checks().get(0).detail());
        assertEquals("compile error", run.checks().get(1).detail());
        assertEquals("qwen", run.manifest().get("provenance").get("model").asText());
        assertTrue(run.oracle().has("functional_ids"));
        assertEquals(10, run.metrics().get("per_task").get("T1").get("requests").asInt());
        assertEquals(32000, run.manifest().get("usable_context").asInt());
    }

    @Test
    void rescore_postsToCorrectPathAndMapsJob() {
        Api.Job job = client.rescore("r1");
        assertEquals("POST", last().method());
        assertEquals("/api/runs/r1/rescore", last().uri());
        assertEquals("5", job.id());
        assertEquals("run", job.kind(), "fixture carries the kind");
    }

    @Test
    void enqueueJob_bodyShapeMatchesJobRequest() {
        Api.Job job = client.enqueueJob(Map.of("task", "L1", "manage_docker", true, "model", "qwen"), 2);
        assertEquals("POST", last().method());
        assertEquals("/api/jobs", last().uri());
        JsonNode body = Json.MAPPER.readTree(last().body());
        assertEquals("L1", body.path("spec").path("task").asText());
        assertEquals("qwen", body.path("spec").path("model").asText());
        assertTrue(body.path("spec").path("manage_docker").asBoolean());
        assertEquals(2, body.path("priority").asInt());
        assertEquals(3, body.path("spec").size(), "only the keys we sent travel the wire");
        assertEquals("5", job.id());
    }

    @Test
    void createExperiment_bodyShapeMatchesExperimentRequest() {
        Api.Experiment experiment = client.createExperiment("he test", "harness_effect",
                Map.of("model", "qwen", "arms", List.of("orch"), "parallel", 3), 3);
        assertEquals("POST", last().method());
        assertEquals("/api/experiments", last().uri());
        JsonNode body = Json.MAPPER.readTree(last().body());
        assertEquals("he test", body.path("name").asText());
        assertEquals("harness_effect", body.path("template").asText());
        assertEquals("qwen", body.path("params").path("model").asText());
        assertEquals("orch", body.path("params").path("arms").get(0).asText());
        assertEquals(3, body.path("params").path("parallel").asInt());
        assertEquals(3, body.path("k").asInt());
        assertEquals("7", experiment.id());
        assertEquals(2, experiment.jobs().size());
        assertEquals("orch", experiment.jobs().get(0).arm());
    }

    @Test
    void setPriority_patchesJobsIdWithPriority() {
        Api.Job job = client.setPriority("5", 2);
        assertEquals("PATCH", last().method());
        assertEquals("/api/jobs/5", last().uri());
        assertEquals(2, Json.MAPPER.readTree(last().body()).path("priority").asInt());
        assertEquals("5", job.id());
    }

    @Test
    void cancelAndRequeue_postCorrectPaths() {
        assertEquals("cancelled", client.cancel("5").status());
        assertEquals("POST /api/jobs/5/cancel", last().method() + " " + last().uri());
        assertEquals("queued", client.requeue("5").status());
        assertEquals("POST /api/jobs/5/requeue", last().method() + " " + last().uri());
    }

    @Test
    void groups_mapsRankedIndicativeAndSummary() {
        Api.GroupResponse groups = client.groups();
        assertEquals(1, groups.ranked().size());
        assertEquals(0, groups.indicative().size());
        Api.Group group = groups.ranked().get(0);
        assertEquals(6, group.summary().k());
        assertEquals(80.0, group.summary().functional().mean());
        assertEquals(75.0, group.summary().functional().ci90().get(0));
        assertTrue(group.summary().matrix().get("F1").pass_k());
        assertEquals(0.4, group.summary().matrix().get("B1").pass_rate());
        assertEquals(List.of("r1", "r2"), group.run_ids());
        assertNull(group.refused());
    }

    @Test
    void compare_postsSpecAndMapsBothOutcomes() {
        Api.CompareResponse response = client.compare(new Api.CompareRequest(
                List.of("r1"), List.of("r2"), "functional", false, false, false, false));
        JsonNode body = Json.MAPPER.readTree(last().body());
        assertEquals("r1", body.path("a").get(0).asText());
        assertEquals("r2", body.path("b").get(0).asText());
        assertEquals("functional", body.path("metric").asText());
        assertFalse(body.path("model_ab").asBoolean());
        assertFalse(body.path("allow_partial").asBoolean());
        assertFalse(body.path("include_invalid").asBoolean());
        assertFalse(body.path("allow_budget_mismatch").asBoolean());
        assertEquals("Mann-Whitney", response.printed());
        assertNull(response.refused());
        assertEquals(0.4, response.result().path("p").asDouble());

        Api.CompareResponse refused = client.compare(new Api.CompareRequest(
                List.of("r1"), List.of("r2"), "functional", false, false, true, false));
        assertEquals("k < 5", refused.refused());
        assertTrue(refused.result() == null || refused.result().isNull(),
                "a JSON null JsonNode deserializes to NullNode, not Java null");
    }

    @Test
    void importAll_mapsRunIdListsNotCounts() {
        Api.ImportResult result = client.importAll();
        assertEquals("POST /api/import", last().method() + " " + last().uri());
        assertEquals(List.of("r1", "r3"), result.imported(),
                "importer.import_all returns the run-id lists, not counts — the 2026-09-16 rescan bug");
        assertEquals(List.of("r2"), result.skipped());
    }

    @Test
    void jobs_listAndDetailMapStatusFields() {
        List<Api.Job> jobs = client.jobs();
        assertEquals(1, jobs.size());
        assertEquals(List.of("--task=L1"), jobs.get(0).argv());
        assertFalse(jobs.get(0).cancel_requested());
        assertEquals("queued", client.job("5").status());
    }

    @Test
    void experiments_listAndDetail() {
        List<Api.Experiment> experiments = client.experiments();
        assertEquals(1, experiments.size());
        assertNull(experiments.get(0).jobs(), "the list endpoint carries no jobs");
        Api.Experiment detail = client.experiment("7");
        assertEquals("7", detail.id());
        assertEquals(2, detail.jobs().size());
        assertEquals("mono", detail.jobs().get(1).arm());
    }

    @Test
    void models_mapsTheModelServerList() {
        assertEquals(List.of("Qwen3.6-27B-graft", "Qwen3.8-27B-graft"), client.models());
        assertEquals("GET /api/models", last().method() + " " + last().uri());
    }

    @Test
    void preflight_getAndPost() {
        Api.PreflightState state = client.preflight();
        assertEquals("GET /api/preflight", last().method() + " " + last().uri());
        assertFalse(state.running());
        assertEquals("ok", state.results().get("preflight").asText());
        client.startPreflight();
        assertEquals("POST /api/preflight", last().method() + " " + last().uri());
    }

    @Test
    void runFileText_fetchesContentAndEncodesSegments() {
        assertEquals("file content", client.runFileText("r1", "oracle.json"));
        assertEquals("/runs/r1/files/oracle.json", last().uri());
        assertEquals("spaced", client.runFileText("r1", "a b.txt"));
        assertEquals("/runs/r1/files/a%20b.txt", last().uri(), "spaces are %20-encoded per segment");
    }

    @Test
    void http422_surfacesFieldNameThroughErrorText() {
        RestClientResponseException e = assertThrows(RestClientResponseException.class,
                () -> client.run("bad422"));
        assertEquals("harness: Input should be 'ref' or 'pi'", client.errorText(e));
    }

    @Test
    void http404_surfacesJobErrorDetailString() {
        RestClientResponseException e = assertThrows(RestClientResponseException.class,
                () -> client.run("missing"));
        assertEquals("run missing is not imported", client.errorText(e));
    }

    @Test
    void readTimeout_failsFastWithinBudget() {
        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> client.runFileText("r1", "slow"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2500, "must fail within the 1s read timeout, took " + elapsedMs + "ms");
    }

    /** The live panel's transport: the SSE stream the Jinja page's EventSource uses. */
    @Test
    void streamJobEvents_deliversParsedEventsUntilStreamEnd() throws Exception {
        List<SseEvent> received = new java.util.ArrayList<>();
        client.streamJobEvents("30", received::add); // returns when the stub stream ends
        assertEquals(4, received.size());
        assertEquals("step_started", received.get(0).type());
        assertEquals("T1", received.get(0).data().path("step").asText());
        assertEquals("session_started", received.get(1).type());
        assertEquals("request", received.get(2).type());
        assertEquals(557, received.get(2).data().path("budget_spent_completion_tokens").intValue());
        assertEquals("status", received.get(3).type());
        assertEquals("all done", received.get(3).data().path("result_line").asText());
    }

    @Test
    void streamJobEvents_throwingFromTheConsumerAbortsTheConnection() {
        List<SseEvent> seen = new java.util.ArrayList<>();
        assertThrows(RuntimeException.class, () -> client.streamJobEvents("30", event -> {
            seen.add(event);
            throw new IllegalStateException("view detached");
        }));
        assertEquals(1, seen.size(), "the abort happens on the first delivered event");
    }

    /** A malformed block mid-stream is skipped, not fatal — the stream continues. */
    @Test
    void streamJobEvents_skipsMalformedBlockInsideStream() throws Exception {
        List<SseEvent> received = new java.util.ArrayList<>();
        client.streamJobEvents("31", received::add);
        assertEquals(1, received.size(), "only the well-formed block is delivered");
        assertEquals("status", received.get(0).type());
        assertEquals(7, received.get(0).data().path("pid").intValue());
    }
}
