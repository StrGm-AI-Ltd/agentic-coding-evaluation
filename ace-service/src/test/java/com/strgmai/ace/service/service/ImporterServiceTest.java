package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Locks down review findings #1 (INSERT/VALUES/ON CONFLICT column-count mismatch) and #2 (the
 *  ON CONFLICT DO UPDATE refreshing every column the INSERT sets, not just 5 of 23). Skipped like
 *  JobQueueIT when no local Postgres is reachable. */
class ImporterServiceTest {

    private JdbcTemplate jdbc() throws Exception {
        String dsn = System.getenv().getOrDefault("ACE_JLS_TEST_DSN", "jdbc:postgresql://localhost/ace_jls_test");
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(dsn);
        try (Connection c = ds.getConnection()) {
            Assumptions.assumeTrue(c.createStatement().executeQuery("SELECT 1").next());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "no local Postgres, skipping: " + e.getMessage());
            return null;
        }
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("DROP SCHEMA IF EXISTS public CASCADE");
        db.execute("CREATE SCHEMA public");
        try (var c = db.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                    new org.springframework.core.io.FileSystemResource("src/main/resources/schema.sql"));
        }
        return db;
    }

    private static void write(Path dir, String name, String json) throws Exception {
        Files.writeString(dir.resolve(name), json);
    }

    @Test
    void insertingARunSucceeds() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        Path dir = Files.createTempDirectory("run");
        write(dir, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 80.0,
                 "functional_score_pct": 90.0, "functional_points_got": 9, "functional_denominator": 10,
                 "points_got": 8, "denominator": 10, "partial_score_pct": 75.0,
                 "results": [{"id": "S1", "status": "pass", "detail": null}]}""");
        ImporterService importer = new ImporterService(db);

        importer.importRun(dir, null);   // must not throw (was #1: column-count mismatch)

        Map<String, Object> run = db.queryForMap("SELECT * FROM runs WHERE run_id = ?", dir.getFileName().toString());
        assertEquals(80.0, (Double) run.get("weighted_score_pct"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM check_results WHERE run_id = ?", Integer.class, dir.getFileName().toString()));
    }

    @Test
    void reimportRefreshesEveryColumnTheInsertSets() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        Path dir = Files.createTempDirectory("run");
        ImporterService importer = new ImporterService(db);

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

        Map<String, Object> run = db.queryForMap("SELECT * FROM runs WHERE run_id = ?", dir.getFileName().toString());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM runs WHERE run_id = ?", Integer.class, dir.getFileName().toString()),
                "an upsert, never a duplicate row");
        assertEquals(99.0, (Double) run.get("weighted_score_pct"));
        assertEquals(95.0, (Double) run.get("functional_score_pct"));
        assertEquals(9, run.get("functional_points_got"));
        assertEquals(9, run.get("points_got"));
        assertEquals(90.0, (Double) run.get("partial_score_pct"));
        assertEquals("model-v2", run.get("model"));
        assertEquals("ref2", run.get("harness"));
        assertEquals("orchestrated", run.get("mode"));
        assertEquals(false, run.get("valid"));
        assertEquals(250.5, (Double) run.get("wall_sec"));
        assertEquals(2500L, ((Number) run.get("completion_tokens")).longValue());
        assertTrue(String.valueOf(run.get("validity_reasons")).contains("timeout"));

        // check_results was replaced too, not accumulated
        assertEquals(1, db.queryForObject("SELECT count(*) FROM check_results WHERE run_id = ?", Integer.class, dir.getFileName().toString()));
        assertEquals("fail", db.queryForObject("SELECT status FROM check_results WHERE run_id = ?", String.class, dir.getFileName().toString()));
    }

    /** The Vaadin UI's Api.ImportResult(List<String> imported, List<String> skipped) - ported from
     *  the Python service's own importer.import_all contract, run-id lists, not counts - failed to
     *  deserialize the jls response with a JSON parse error ("Cannot deserialize ArrayList<String>
     *  from Integer") because importAll() here returned {"imported": N, "skipped": M} instead. */
    @Test
    void importAllReturnsRunIdListsNotCounts() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        Path resultsDir = Files.createTempDirectory("results");
        Path scored = resultsDir.resolve("run-scored");
        Files.createDirectories(scored);
        write(scored, "oracle.json", """
                {"task": "L3p_point_in_time", "schema_version": 3, "weighted_score_pct": 80.0,
                 "functional_score_pct": 90.0, "functional_points_got": 9, "functional_denominator": 10,
                 "points_got": 8, "denominator": 10, "partial_score_pct": 75.0, "results": []}""");
        Files.createDirectories(resultsDir.resolve("run-unscored"));   // no oracle.json: not yet finished

        Map<String, List<String>> result = new ImporterService(db).importAll(resultsDir);

        assertEquals(List.of("run-scored"), result.get("imported"));
        assertEquals(List.of("run-unscored"), result.get("skipped"));
    }
}
