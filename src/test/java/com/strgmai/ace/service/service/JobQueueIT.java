package com.strgmai.ace.service.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The queue against real Postgres (like the Python suite's agentbench_test DB). Auto-skipped when
 *  no local Postgres is reachable, so `gradle test` stays green anywhere. */
class JobQueueIT {

    private JdbcTemplate jdbc() throws Exception {
        String dsn = System.getenv().getOrDefault("ACE_JLS_TEST_DSN", "jdbc:postgresql://localhost/ace_jls_test");
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

    /** The real contention SKIP LOCKED exists for: claimer A holds an UNCOMMITTED claim on the top
     *  candidate row (its own connection, autocommit off, so the row lock is still held) while
     *  claimer B claims on a second connection at the same time. B must neither block nor steal A's
     *  row: it skips to the next candidate. Remove SKIP LOCKED and B blocks on A's lock instead —
     *  the bounded wait below then times out and this test fails. */
    @Test
    void skipLockedLetsTwoClaimersNotFight() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        applySchema(db);
        String results = Files.createTempDirectory("results").toString();
        new JobQueue(db).enqueue(spec("race-1"), 5, results, "r", "o", null, null, null);   // priority DESC: the top candidate
        new JobQueue(db).enqueue(spec("race-2"), 0, results, "r", "o", null, null, null);

        SingleConnectionDataSource holder = connection(false);        // A: claims and keeps the transaction open
        SingleConnectionDataSource other = connection(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            JobQueue.Job a = new JobQueue(new JdbcTemplate(holder)).claim();
            assertEquals("race-1", a.runId());                        // A holds an uncommitted row lock on race-1

            JobQueue b = new JobQueue(new JdbcTemplate(other));
            Future<JobQueue.Job> claimed = pool.submit(b::claim);
            JobQueue.Job second = claimed.get(15, TimeUnit.SECONDS);   // would time out without SKIP LOCKED
            assertNotNull(second, "the second claimer must skip the locked row, not come back empty");
            assertEquals("race-2", second.runId());                    // never A's row, and not a wait for it

            Future<JobQueue.Job> nothingLeft = pool.submit(new JobQueue(new JdbcTemplate(other))::claim);
            assertNull(nothingLeft.get(15, TimeUnit.SECONDS));         // the only other candidate is A's locked row
        } finally {
            pool.shutdownNow();
            holder.getConnection().commit();
            holder.destroy();
            other.destroy();
        }
        assertEquals("running", new JobQueue(db).get(1L).get("status"));
    }

    /** two claimers racing the SAME single candidate: exactly one wins, the other gets nothing */
    @Test
    void twoClaimersRacingOneRowProduceOneWinner() throws Exception {
        JdbcTemplate db = jdbc();
        if (db == null) return;
        applySchema(db);
        String results = Files.createTempDirectory("results").toString();
        new JobQueue(db).enqueue(spec("only-1"), 0, results, "r", "o", null, null, null);

        SingleConnectionDataSource holder = connection(false), other = connection(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            JobQueue.Job winner = new JobQueue(new JdbcTemplate(holder)).claim();
            assertEquals("only-1", winner.runId());
            Future<JobQueue.Job> loser = pool.submit(new JobQueue(new JdbcTemplate(other))::claim);
            assertNull(loser.get(15, TimeUnit.SECONDS), "the row is locked: the second claimer skips it, it does not wait");
        } finally {
            pool.shutdownNow();
            holder.getConnection().commit();
            holder.destroy();
            other.destroy();
        }
    }

    /** a connection of its own, so two claimers can genuinely overlap (one DataSource = one Connection) */
    private SingleConnectionDataSource connection(boolean autoCommit) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName(org.postgresql.Driver.class.getName());
        ds.setUrl(System.getenv().getOrDefault("ACE_JLS_TEST_DSN", "jdbc:postgresql://localhost/ace_jls_test"));
        ds.setSuppressClose(true);
        ds.setAutoCommit(autoCommit);
        return ds;
    }

    private static RunSpec spec(String runId) {
        return new RunSpec("L3p_point_in_time", "m", null, "monolithic", "agent", 3600, null, null, null, null, false, false, false, null, false, true, null, runId);
    }
}
