package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(37L, RunDetailView.jobForRun(jobs, "r-37").id());
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
}
