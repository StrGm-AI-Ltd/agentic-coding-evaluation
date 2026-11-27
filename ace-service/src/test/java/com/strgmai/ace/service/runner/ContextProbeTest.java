package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.backend.OmlxBackend;
import com.strgmai.ace.service.config.BenchProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/** The context probe's pure derivations: the effective window is min(memory, performance, operator
 *  cap); the decode curve interpolates; budgets in windows are capped by what can be generated. */
class ContextProbeTest {

    /** A fresh temp dir per probe - these tests never want to touch the operator's real oMLX
     *  install (same guardrail restoreCap()'s own tests below already documented, now backed by a
     *  real seam instead of just a comment). */
    private static ContextProbe probe() throws Exception {
        return new ContextProbe(new OmlxBackend(Files.createTempDirectory("omlx-test")));
    }

    private BenchProperties props(final Number windowCap) {
        return new BenchProperties("m", "http://127.0.0.1:9191/v1", "k", 1.0, 0.95, null, 8192,
                windowCap == null ? null : windowCap.intValue(), 8, 28000, "./results", "/tmp/ab-ws", 0.2, "", null, null, null, null);
    }

    @Test
    void theEffectiveWindowIsTheSmallestCandidate() {
        Map<String, Object> rec = Map.of("max_ok_prompt_tokens", 80000, "binding", "memory_or_server",
                "decode_tps_short", 15.0, "cap_max_model_len", 262144,
                "curve", List.of(Map.of("status", "200", "prompt_tokens", 40000, "decode_tps", 15.0),
                        Map.of("status", "200", "prompt_tokens", 80000, "decode_tps", 3.0)));
        final Map<String, Object> d = ContextProbe.derive(rec, props(65536), 3600, 0.5);
        // memory = 80000 - 4096 floored to 2048s; performance: decode >= 0.5x15=7.5 only at 40000 -> 40000/2048*2048; operator cap 65536
        assertEquals(38912, d.get("usable_context"), "the performance window (40000, 2048-floored to 38912) binds");
        assertEquals("performance", d.get("window_limited_by"));
        assertTrue((Boolean) d.get("safety_applied"), "a memory-bound window gets the safety margin");
        assertEquals(75776, d.get("memory_window"));   // (80000-4096)//2048*2048 = 37x2048
        assertEquals(9.0, d.get("min_decode_tps"));    // 0.6 x 15
        // every knob follows the EFFECTIVE window (38912), not the raw candidates - at 0.5 this
        // would be 19456, but props(65536)'s own maxOutputTokens (8192) caps it first
        assertEquals(min(8192, (int) (38912 * 0.5) / 512 * 512), d.get("max_output_tokens"));
        assertEquals((int) (38912 * 0.43) / 256 * 256, d.get("compaction_trigger_tokens"));
        final int expectTaskTokens = min((int) (38912 * 1.25) / 1000 * 1000, max((int) (3600 * 15 * 0.8) / 1000 * 1000, 10000));
        assertEquals(expectTaskTokens, d.get("task_tokens"), "the window budget is capped by what the wall can generate");
    }

    @Test
    void maxOutputFractionOverridesTheDerivedOutputCap() {
        Map<String, Object> rec = Map.of("max_ok_prompt_tokens", 80000, "binding", "memory_or_server",
                "decode_tps_short", 15.0, "cap_max_model_len", 262144,
                "curve", List.of(Map.of("status", "200", "prompt_tokens", 40000, "decode_tps", 15.0),
                        Map.of("status", "200", "prompt_tokens", 80000, "decode_tps", 3.0)));
        // kept below props(65536)'s own maxOutputTokens cap (8192) so the fraction's own effect is
        // visible rather than clipped away by that unrelated ceiling
        final Map<String, Object> bigger = ContextProbe.derive(rec, props(65536), 3600, 0.2);
        final Map<String, Object> smaller = ContextProbe.derive(rec, props(65536), 3600, 0.1);
        assertEquals((int) (38912 * 0.2) / 512 * 512, bigger.get("max_output_tokens"));
        assertEquals((int) (38912 * 0.1) / 512 * 512, smaller.get("max_output_tokens"));
        assertTrue((Integer) bigger.get("max_output_tokens") > (Integer) smaller.get("max_output_tokens"),
                "a larger fraction must yield a larger output cap, not a fixed value regardless of the argument");
    }

    @Test
    void aCapBoundProbeTrustsTheCapExactly() {
        final var rec = Map.of("max_ok_prompt_tokens", 131000, "binding", "cap", "cap_max_model_len", 131072, "decode_tps_short", 15.0, "curve", List.of());
        final Map<String, Object> d = ContextProbe.derive(rec, props(null), null, 0.5);
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
        final Map<String, Object> d = ContextProbe.derive(rec, props(null), null, 0.5);
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

    /** #170: a validation error (400/413) arrives as a plain JSON body, not SSE - its own message
     *  text must survive into Post.error() since that's what the binding classification (cap vs
     *  memory guard) matches against; it used to be hardcoded null. */
    @Test
    void postExtractsTheErrorMessageFromANonStreamingErrorResponse() throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/chat/completions", ex -> {
            final byte[] body = "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens\",\"type\":\"invalid_request_error\"}}".getBytes(UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(400, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        try {
            final var r = probe().post("http://127.0.0.1:" + upstream.getAddress().getPort(), "k", Map.of("model", "m", "messages", List.of()), 15);
            assertEquals(400, r.status());
            assertEquals("This model's maximum context length is 65536 tokens", r.error());
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void postLeavesErrorNullWhenTheNonStreamingResponseHasNoErrorField() throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/chat/completions", ex -> {
            final byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5}}".getBytes(UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        try {
            final var r = probe().post("http://127.0.0.1:" + upstream.getAddress().getPort(), "k", Map.of("model", "m", "messages", List.of()), 15);
            assertEquals(200, r.status());
            assertNull(r.error());
        } finally {
            upstream.stop(0);
        }
    }

    // #340: restoreCap() must never touch the backend (setModelCap/restart, real-filesystem/
    // real-process operations even against this test's own temp-dir-backed OmlxBackend) unless
    // THIS job's own prepareCap() actually raised something - these two guard-clause cases are
    // exactly the ones a future change to restoreCap()'s condition could silently break into a real
    // restart during `./gradlew test`.
    @Test
    void restoreCapIsANoOpWhenPrepareCapNeverRaisedAnything() throws Exception {
        probe().restoreCap("some-model", "http://127.0.0.1:9191/v1", "k", Map.of());
    }

    @Test
    void restoreCapIsANoOpWhenCapInfoExplicitlyMarksNoRaise() throws Exception {
        probe().restoreCap("some-model", "http://127.0.0.1:9191/v1", "k", Map.of("raised", false));
    }
}
