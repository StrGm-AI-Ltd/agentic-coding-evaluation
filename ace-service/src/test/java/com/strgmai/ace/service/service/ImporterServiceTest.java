package com.strgmai.ace.service.service;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.strgmai.ace.service.jooq.Tables.CHECK_RESULTS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;
import static org.junit.jupiter.api.Assertions.*;

/** Locks down review findings #1 (INSERT/VALUES/ON CONFLICT column-count mismatch) and #2 (the
 *  ON CONFLICT DO UPDATE refreshing every column the INSERT sets, not just 5 of 23). Runs against a
 *  real, fresh-per-test SQLite database - no external DB server or Testcontainers needed. */
class ImporterServiceTest {

    private DSLContext dsl() throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile("ace-importer-test", ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    private static void write(Path dir, final String name, final String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }

    @Test
    void insertingARunSucceeds() throws Exception {
        final DSLContext db = dsl();
        final var dir = Files.createTempDirectory("run");
        write(dir, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 80.0,
                 "functional_score_pct": 90.0, "functional_points_got": 9, "functional_denominator": 10,
                 "points_got": 8, "denominator": 10, "partial_score_pct": 75.0,
                 "results": [{"id": "S1", "status": "pass", "detail": null}]}""");
        final var importer = new ImporterService(db);

        importer.importRun(dir, null);   // must not throw (was #1: column-count mismatch)

        final var run = db.selectFrom(RUNS).where(RUNS.RUN_ID.eq(dir.getFileName().toString())).fetchOne();
        assertEquals(80.0, run.getWeightedScorePct(), 0.001);
        assertEquals(1, db.fetchCount(CHECK_RESULTS, CHECK_RESULTS.RUN_ID.eq(dir.getFileName().toString())));
    }

    @Test
    void reimportRefreshesEveryColumnTheInsertSets() throws Exception {
        final DSLContext db = dsl();
        final var dir = Files.createTempDirectory("run");
        final var importer = new ImporterService(db);

        write(dir, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 50.0,
                 "functional_score_pct": 60.0, "functional_points_got": 6, "functional_denominator": 10,
                 "points_got": 5, "denominator": 10, "partial_score_pct": 55.0,
                 "results": [{"id": "S1", "status": "pass", "detail": null}]}""");
        write(dir, "manifest.json", """
                {"mode": "monolithic", "provenance": {"model": "model-v1", "harness": "ref"},
                 "validity": {"valid": true, "reasons": []}}""");
        write(dir, "metrics.json", """
                {"leaderboard": {"total_wall_sec": 100.0, "completion_tokens": 1000}}""");
        importer.importRun(dir, null);

        // re-run with every value changed: a reimport/rescore must refresh the WHOLE row, not 5 of 23 columns
        write(dir, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 99.0,
                 "functional_score_pct": 95.0, "functional_points_got": 9, "functional_denominator": 10,
                 "points_got": 9, "denominator": 10, "partial_score_pct": 90.0,
                 "results": [{"id": "S1", "status": "fail", "detail": null}]}""");
        write(dir, "manifest.json", """
                {"mode": "orchestrated", "provenance": {"model": "model-v2", "harness": "ref2"},
                 "validity": {"valid": false, "reasons": ["timeout"]}}""");
        write(dir, "metrics.json", """
                {"leaderboard": {"total_wall_sec": 250.5, "completion_tokens": 2500}}""");
        importer.importRun(dir, null);

        final String runId = dir.getFileName().toString();
        final var run = db.selectFrom(RUNS).where(RUNS.RUN_ID.eq(runId)).fetchOne();
        assertEquals(1, db.fetchCount(RUNS, RUNS.RUN_ID.eq(runId)), "an upsert, never a duplicate row");
        assertEquals(99.0, run.getWeightedScorePct(), 0.001);
        assertEquals(95.0, run.getFunctionalScorePct(), 0.001);
        assertEquals(9, run.getFunctionalPointsGot());
        assertEquals(9, run.getPointsGot());
        assertEquals(90.0, run.getPartialScorePct(), 0.001);
        assertEquals("model-v2", run.getModel());
        assertEquals("ref2", run.getHarness());
        assertEquals("orchestrated", run.getMode());
        assertEquals(false, run.getValid());
        assertEquals(250.5, run.getWallSec(), 0.001);
        assertEquals(2500, run.getCompletionTokens());
        assertTrue(run.getValidityReasons().contains("timeout"));

        // check_results was replaced too, not accumulated
        assertEquals(1, db.fetchCount(CHECK_RESULTS, CHECK_RESULTS.RUN_ID.eq(runId)));
        assertEquals("fail", db.select(CHECK_RESULTS.STATUS).from(CHECK_RESULTS).where(CHECK_RESULTS.RUN_ID.eq(runId)).fetchOne(CHECK_RESULTS.STATUS));
    }

    /** The Vaadin UI's Api.ImportResult(List<String> imported, List<String> skipped) - ported from
     *  the Python service's own importer.import_all contract, run-id lists, not counts - failed to
     *  deserialize the jls response with a JSON parse error ("Cannot deserialize ArrayList<String>
     *  from Integer") because importAll() here returned {"imported": N, "skipped": M} instead. */
    @Test
    void importAllReturnsRunIdListsNotCounts() throws Exception {
        final DSLContext db = dsl();
        final var resultsDir = Files.createTempDirectory("results");
        final Path scored = resultsDir.resolve("run-scored");
        Files.createDirectories(scored);
        write(scored, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 80.0,
                 "functional_score_pct": 90.0, "functional_points_got": 9, "functional_denominator": 10,
                 "points_got": 8, "denominator": 10, "partial_score_pct": 75.0, "results": []}""");
        Files.createDirectories(resultsDir.resolve("run-unscored"));   // no oracle.json: not yet finished

        final Map<String, List<String>> result = new ImporterService(db).importAll(resultsDir);

        assertEquals(List.of("run-scored"), result.get("imported"));
        assertEquals(List.of("run-unscored"), result.get("skipped"));
    }
}
