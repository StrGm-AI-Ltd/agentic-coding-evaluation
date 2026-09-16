package com.agentbench.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The queue against real Postgres (like the Python suite's agentbench_test DB). Auto-skipped when
 *  no local Postgres is reachable, so `gradle test` stays green anywhere. */
class JobQueueIT {

    private JdbcTemplate jdbc() throws Exception {
        String dsn = System.getenv().getOrDefault("AB_JLS_TEST_DSN", "jdbc:postgresql://localhost/agentbench_jls_test");
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(dsn);
        try (Connection c = ds.getConnection()) {              // fails fast (and skips) when there is no local Postgres
            Assumptions.assumeTrue(c.createStatement().executeQuery("SELECT 1").next());
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "no local Postgres, skipping: " + e.getMessage());
            return null;
        }
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("DROP SCHEMA IF EXISTS public CASCADE");
        db.execute("CREATE SCHEMA public");
        return db;
    }

    /** apply schema.sql the way Spring does for the app: ScriptUtils (comments + multi-statement) */
    private void applySchema(JdbcTemplate db) throws Exception {
        try (var c = db.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                    new org.springframework.core.io.FileSystemResource("src/main/resources/schema.sql"));
        }
    }

    @Test
    void enqueueClaimPriorityCancelAndRequeue() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        applySchema(db);
        JobQueue q = new JobQueue(db);
        String results = Files.createTempDirectory("results").toString();

        Map<String, Object> low = q.enqueue(spec("low-1"), 0, results, "r", "o", null, null, null);
        Map<String, Object> high = q.enqueue(spec("high-1"), 5, results, "r", "o", null, null, null);
        JobQueue.Job claimed = q.claim();
        assertEquals("high-1", claimed.runId());                     // priority DESC wins

        q.setStatus(claimed.id(), "failed", "boom");
        assertEquals("failed", q.get(claimed.id()).get("status"));

        java.nio.file.Path dir = java.nio.file.Path.of(results, "high-1");
        Files.createDirectories(dir);                                 // ...a re-run would mix its files
        IllegalStateException mixed = assertThrows(IllegalStateException.class, () -> q.requeue(claimed.id(), results));
        assertTrue(mixed.getMessage().contains("would mix its files"));
        Files.delete(dir);
        q.requeue(claimed.id(), results);
        assertEquals("queued", q.get(claimed.id()).get("status"));

        JobQueue.Job again = q.claim();
        assertEquals("high-1", again.runId());   // the requeued job kept its priority: it wins the next claim again
        q.cancel(again.id());
        assertEquals("running", q.get(again.id()).get("status"));   // a RUNNING job gets the flag; the worker stops it
        assertTrue((Boolean) q.get(again.id()).get("cancel_requested"));

        JobQueue.Job second = q.claim();
        assertEquals("low-1", second.runId());
        q.setStatus(second.id(), "queued", null);   // a non-running job cancels outright
        q.cancel(second.id());
        assertEquals("cancelled", q.get(second.id()).get("status"));
        assertTrue((Boolean) q.get(second.id()).get("cancel_requested"));

        assertThrows(IllegalArgumentException.class, () -> q.enqueue(spec("low-1"), 0, results, "r", "o", null, null, null));   // never twice
    }

    @Test
    void skipLockedLetsTwoClaimersNotFight() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        applySchema(db);
        JobQueue a = new JobQueue(db), b = new JobQueue(db);
        String results = Files.createTempDirectory("results").toString();
        a.enqueue(spec("only-1"), 0, results, "r", "o", null, null, null);
        assertEquals("only-1", a.claim().runId());
        assertNull(b.claim());                                        // the row is locked: the second claimer sees nothing
    }

    private static RunSpec spec(String runId) {
        return new RunSpec("L3p_point_in_time", "m", null, "monolithic", "agent", 3600, null, null, null, null, false, false, false, null, false, true, runId);
    }
}
