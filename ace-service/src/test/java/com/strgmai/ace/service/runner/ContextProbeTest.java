package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.config.BenchProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The context probe's pure derivations: the effective window is min(memory, performance, operator
 *  cap); the decode curve interpolates; budgets in windows are capped by what can be generated. */
class ContextProbeTest {

    private BenchProperties props(final Number windowCap) {
        return new BenchProperties("m", "http://127.0.0.1:9191/v1", "k", 1.0, 0.95, null, 8192,
                windowCap == null ? null : windowCap.intValue(), 8, 28000, "./results", "/tmp/ab-ws", 10, 0.2, 0, "", null);
    }

    @Test
    void theEffectiveWindowIsTheSmallestCandidate() {
        Map<String, Object> rec = Map.of("max_ok_prompt_tokens", 80000, "binding", "memory_or_server",
                "decode_tps_short", 15.0, "cap_max_model_len", 262144,
                "curve", List.of(Map.of("status", "200", "prompt_tokens", 40000, "decode_tps", 15.0),
                        Map.of("status", "200", "prompt_tokens", 80000, "decode_tps", 3.0)));
        final Map<String, Object> d = ContextProbe.derive(rec, props(65536), 3600);
        // memory = 80000 - 4096 floored to 2048s; performance: decode >= 0.5x15=7.5 only at 40000 -> 40000/2048*2048; operator cap 65536
        assertEquals(38912, d.get("usable_context"), "the performance window (40000, 2048-floored to 38912) binds");
        assertEquals("performance", d.get("window_limited_by"));
        assertTrue((Boolean) d.get("safety_applied"), "a memory-bound window gets the safety margin");
        assertEquals(75776, d.get("memory_window"));   // (80000-4096)//2048*2048 = 37x2048
        assertEquals(9.0, d.get("min_decode_tps"));    // 0.6 x 15
        // every knob follows the EFFECTIVE window (38912), not the raw candidates
        assertEquals((int) (38912 * 0.125) / 512 * 512, d.get("max_output_tokens"));
        assertEquals((int) (38912 * 0.43) / 256 * 256, d.get("compaction_trigger_tokens"));
        final int expectTaskTokens = Math.min((int) (38912 * 1.25) / 1000 * 1000, Math.max((int) (3600 * 15 * 0.8) / 1000 * 1000, 10000));
        assertEquals(expectTaskTokens, d.get("task_tokens"), "the window budget is capped by what the wall can generate");
    }

    @Test
    void aCapBoundProbeTrustsTheCapExactly() {
        final var rec = Map.of("max_ok_prompt_tokens", 131000, "binding", "cap", "cap_max_model_len", 131072, "decode_tps_short", 15.0, "curve", List.of());
        final Map<String, Object> d = ContextProbe.derive(rec, props(null), null);
        assertEquals(131072, d.get("memory_window"), "a cap is exact: no safety margin (after prepare_cap it is the positional limit)");
        assertEquals("positional/cap", d.get("window_limited_by"));
        assertTrue((Boolean) d.get("safety_applied") == false);
    }

    @Test
    void decodeAtInterpolatesTheCurveAndFlattensPastTheEnds() {
        final var curve = List.of(new double[]{1000, 20.0}, new double[]{40000, 10.0}, new double[]{80000, 4.0});
        assertEquals(20.0, ContextProbe.decodeAt(curve, 500));
        assertEquals(15.0, ContextProbe.decodeAt(curve, 20500), "midpoint interpolates");
        assertEquals(4.0, ContextProbe.decodeAt(curve, 200000), "flat past the last point");
        assertNull(ContextProbe.decodeAt(List.of(), 100));
    }

    @Test
    void aWindowBelowMinUsableIsRefused() {
        final var rec = Map.of("max_ok_prompt_tokens", 10000, "binding", "memory_or_server", "decode_tps_short", 15.0, "curve", List.of());
        final Map<String, Object> d = ContextProbe.derive(rec, props(null), null);
        assertTrue((Boolean) d.get("too_small"), "a window that cannot hold the packs is not benchmarkable");
    }

    @Test
    void performanceWindowTakesTheLastPointThatMetTheFloor() {
        Map<String, Object> rec = Map.of("curve", List.of(
                Map.of("status", "200", "prompt_tokens", 8000, "decode_tps", 20.0),
                Map.of("status", "200", "prompt_tokens", 20000, "decode_tps", 9.0),
                Map.of("status", "200", "prompt_tokens", 40000, "decode_tps", 4.0)));
        assertEquals(20000, ContextProbe.performanceWindow(rec, 8.0));
        assertNull(ContextProbe.performanceWindow(rec, null));
    }

    /** Found live 2026-09-29: Docker Desktop's VM crashed under RAM pressure from a co-resident
     *  model server - unloadModel()/loadModel() are the lever oracleWithDocker() now pulls to free
     *  that memory before Docker comes up, using oMLX's own per-model admin endpoints. */
    @Test
    void unloadModelPostsToTheModelsUnloadEndpointWithTheApiKey() throws Exception {
        final AtomicReference<String> sawMethod = new AtomicReference<>();
        final AtomicReference<String> sawPath = new AtomicReference<>();
        final AtomicReference<String> sawAuth = new AtomicReference<>();
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            sawMethod.set(ex.getRequestMethod());
            sawPath.set(ex.getRequestURI().getPath());
            sawAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            final byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        try {
            final boolean ok = new ContextProbe().unloadModel("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "secret-key", "Qwen3.8-27B-orig-4bit");
            assertTrue(ok);
            assertEquals("POST", sawMethod.get());
            assertEquals("/v1/models/Qwen3.8-27B-orig-4bit/unload", sawPath.get());
            assertEquals("Bearer secret-key", sawAuth.get());
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void loadModelPostsToTheModelsLoadEndpoint() throws Exception {
        final AtomicReference<String> sawPath = new AtomicReference<>();
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            sawPath.set(ex.getRequestURI().getPath());
            final byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        try {
            final boolean ok = new ContextProbe().loadModel("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "secret-key", "Qwen3.8-27B-orig-4bit");
            assertTrue(ok);
            assertEquals("/v1/models/Qwen3.8-27B-orig-4bit/load", sawPath.get());
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void unloadModelReturnsFalseRatherThanThrowingOnAnErrorResponse() throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            final byte[] body = "{\"detail\":\"Model not loaded\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(400, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        try {
            assertFalse(new ContextProbe().unloadModel("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "k", "m"));
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void unloadModelReturnsFalseRatherThanThrowingWhenUpstreamIsUnreachable() {
        assertFalse(new ContextProbe().unloadModel("http://127.0.0.1:1", "k", "m"));
    }
}
