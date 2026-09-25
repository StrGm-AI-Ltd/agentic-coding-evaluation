package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The run page's not-imported gate: only a real 404 opens the self-service panel. */
class RunDetailViewTest {

    @Test
    void notImportedOn404Only() {
        assertTrue(RunDetailView.isNotImported(ApiFixtures.http(404)),
                "404 = the run is not imported (yet)");
    }

    @Test
    void otherFailuresAreNotTheImportGate() {
        assertFalse(RunDetailView.isNotImported(ApiFixtures.http(422)));
        assertFalse(RunDetailView.isNotImported(ApiFixtures.http(500)));
        assertFalse(RunDetailView.isNotImported(new ResourceAccessException("service down")));
        assertFalse(RunDetailView.isNotImported(new IllegalStateException("unrelated")));
        assertFalse(RunDetailView.isNotImported(null));
    }

    /**
     * The 2026-09-16 runs-page crash, pinned: in real manifests provenance.quantization
     * is an 11-property nested map (not a string), and provenance values may also be
     * booleans/numbers. provenanceLines must render all of them without throwing.
     */
    @Test
    void provenanceLines_renderRealManifestShapesWithoutThrowing() {
        String manifestJson = """
                {"provenance": {
                   "model": "Qwen3.8-27B-graft",
                   "quantization": {
                     "bits": 4, "mode": "affine", "group_size": 64,
                     "language_model.mtp.fc": {"bits": 4, "mode": "affine", "group_size": 64},
                     "language_model.mtp.layers.0.mlp.up_proj": {"bits": 4, "mode": "affine"},
                     "language_model.mtp.layers.0.mlp.down_proj": {"bits": 4},
                     "language_model.mtp.layers.0.mlp.gate_proj": {"bits": 4},
                     "language_model.mtp.layers.1.mlp.up_proj": {"bits": 4},
                     "language_model.mtp.layers.1.mlp.down_proj": {"bits": 4},
                     "language_model.mtp.layers.1.mlp.gate_proj": {"bits": 4},
                     "layer_count": 27, "attention": "mtp"
                   },
                   "pyyaml": false,
                   "harness": "ref",
                   "host": {"os": "macOS 26.6.2", "cpu": "Apple M3 Pro"}
                 },
                 "usable_context": 32000}""";
        var manifest = Json.MAPPER.readTree(manifestJson);

        List<String> lines = RunDetailView.provenanceLines(manifest, "/tmp/results/r1");

        assertTrue(lines.stream().anyMatch(line -> line.equals("model: Qwen3.8-27B-graft")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("harness: ref")));
        String quantization = lines.stream().filter(l -> l.startsWith("quantization: ")).findFirst().orElseThrow();
        assertTrue(quantization.contains("\"bits\" : 4"),
                "the 11-property quantization map renders as pretty JSON, not asText()");
        assertTrue(lines.stream().anyMatch(line -> line.equals("usable_context: 32,000")));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("host:")),
                "unknown manifest keys are ignored (the Jinja2 key list)");
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("pyyaml:")),
                "keys outside the Jinja2 list never appear even when non-textual");
        assertEquals("results: /tmp/results/r1", lines.get(lines.size() - 1));
    }

    @Test
    void provenanceLines_degenerateManifests() {
        assertEquals(List.of(), RunDetailView.provenanceLines(null, null));
        assertEquals(List.of("results: /tmp/x"),
                RunDetailView.provenanceLines(Json.MAPPER.readTree("{}"), "/tmp/x"));
        // non-object provenance / non-number usable_context are handled, not thrown at
        List<String> lines = RunDetailView.provenanceLines(
                Json.MAPPER.readTree("{\"provenance\": \"bogus\", \"usable_context\": \"big\"}"), null);
        assertEquals(List.of("usable_context: big"), lines);
    }

    /** The 2026-09-16 empty-run-page question, pinned: the panel explains the run's own job. */
    @Test
    void jobForRun_findsTheRunsJobRow() {
        List<Api.Job> jobs = List.of(
                ApiFixtures.job(36, "cancelled", null),
                ApiFixtures.job(37, "running", null));
        assertEquals("37", RunDetailView.jobForRun(jobs, "r-37").id());
        assertNull(RunDetailView.jobForRun(jobs, "no-such-run"));
    }

    @Test
    void notImportedKind_matrix() {
        assertEquals(RunDetailView.NotImportedKind.IN_FLIGHT,
                RunDetailView.notImportedKind(ApiFixtures.job(37, "running", null)),
                "an in-flight run routes to the live job page");
        assertEquals(RunDetailView.NotImportedKind.IN_FLIGHT,
                RunDetailView.notImportedKind(ApiFixtures.job(37, "queued", null)));
        assertEquals(RunDetailView.NotImportedKind.BLOCKED,
                RunDetailView.notImportedKind(ApiFixtures.job(37, "blocked", "dirty tree")),
                "a blocked job needs a requeue, not a wait");
        assertEquals(RunDetailView.NotImportedKind.ENDED_WITHOUT_SCORE,
                RunDetailView.notImportedKind(ApiFixtures.job(36, "cancelled", null)));
        assertEquals(RunDetailView.NotImportedKind.ENDED_WITHOUT_SCORE,
                RunDetailView.notImportedKind(ApiFixtures.job(36, "failed", null)));
        assertEquals(RunDetailView.NotImportedKind.ENDED_WITHOUT_SCORE,
                RunDetailView.notImportedKind(ApiFixtures.job(36, "succeeded", null)),
                "succeeded but unimported — the rare rescan case");
        assertEquals(RunDetailView.NotImportedKind.UNKNOWN, RunDetailView.notImportedKind(null));
    }

    /** #32: run stats had no latency/TTFT - both now ride along on the meta line once the
     *  leaderboard carries them. */
    @Test
    void metaLine_includesAvgLatencyAndTtftWhenPresent() {
        var metrics = Json.MAPPER.readTree("{\"leaderboard\": {\"avg_latency_sec\": 12.34, \"avg_first_byte_ms\": 250}}");
        var run = new Api.Run("r1", null, null, "L3p_point_in_time", "orchestrated", "m", null, 3, true,
                null, null, null, null, null, null, null, null, null, null,
                null, null, 3600.0, 1000L, null, null, null, metrics, null);

        var line = new RunDetailView(mock(ServiceClient.class)).metaLine(run);

        assertTrue(line.contains("avg latency 12.3s"), line);
        assertTrue(line.contains("avg TTFT 250ms"), line);
    }

    /** New metric: metrics.speed_by_context rows become chart-ready (label, prefill, decode). The
     *  label is just the bucket's right edge in K tokens (no range, no unit) - a range like "2K-3K"
     *  repeated on 50+ ticks is clutter the axis title/tooltip already cover. */
    @Test
    void speedByContextRows_buildsLabelsFromTheBucketsRightEdge() {
        var metrics = Json.MAPPER.readTree("""
                {"speed_by_context": [
                    {"context_lo": 0, "context_hi": 8192, "requests": 2, "avg_prefill_tok_per_sec": 150.0, "avg_decode_tok_per_sec": 30.0},
                    {"context_lo": 8192, "context_hi": 16384, "requests": 1, "avg_prefill_tok_per_sec": 90.0, "avg_decode_tok_per_sec": null}
                ]}""");

        var rows = RunDetailView.speedByContextRows(metrics);

        assertEquals(2, rows.size());
        assertEquals("8", rows.get(0).label());
        assertEquals(150.0, rows.get(0).avgPrefillTokPerSec());
        assertEquals(30.0, rows.get(0).avgDecodeTokPerSec());
        assertEquals(2, rows.get(0).requests());
        assertEquals("16", rows.get(1).label());
        assertNull(rows.get(1).avgDecodeTokPerSec(), "a bucket with no streamed requests has no decode speed to average");
        assertEquals(1, rows.get(1).requests());
    }

    @Test
    void speedByContextRows_emptyWhenAbsentOrNotAnArray() {
        assertEquals(List.of(), RunDetailView.speedByContextRows(null));
        assertEquals(List.of(), RunDetailView.speedByContextRows(Json.MAPPER.readTree("{}")));
        assertEquals(List.of(), RunDetailView.speedByContextRows(Json.MAPPER.readTree("{\"speed_by_context\": []}")));
    }

    /** #new metric: at a fixed 1024-token bucket width a run can easily have 50+ points, so some rest
     *  on very few requests - the tooltip must surface each bucket's own request count (not just the
     *  averages), baked in as a JS array literal indexed by dataPointIndex since ApexCharts' custom
     *  tooltip is a raw JS function string with no per-point Java callback hook in this wrapper. */
    @Test
    void speedByContextTooltipJs_embedsEachBucketsRequestCountInOrder() {
        final var rows = List.of(
                new RunDetailView.SpeedBucketRow("5", 150.0, 30.0, 27),
                new RunDetailView.SpeedBucketRow("10", 700.0, 15.0, 7));

        final var js = RunDetailView.speedByContextTooltipJs(rows);

        assertTrue(js.contains("[27,7]"), js);
        assertTrue(js.contains("struct.dataPointIndex"), "indexes into the embedded array by the hovered point");
    }

    @Test
    void metaLine_omitsLatencyAndTtftWhenAbsent() {
        var run = new Api.Run("r1", null, null, "L3p_point_in_time", "orchestrated", "m", null, 3, true,
                null, null, null, null, null, null, null, null, null, null,
                null, null, 3600.0, 1000L, null, null, null, null, null);

        var line = new RunDetailView(mock(ServiceClient.class)).metaLine(run);

        assertFalse(line.contains("latency"), line);
        assertFalse(line.contains("TTFT"), line);
    }
}
