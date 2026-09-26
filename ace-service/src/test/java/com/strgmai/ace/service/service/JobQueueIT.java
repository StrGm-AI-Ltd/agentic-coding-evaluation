package com.strgmai.ace.service.service;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The queue against a real, fresh-per-test SQLite database - no external DB server or Testcontainers
 *  needed now that the service itself uses SQLite (this was Postgres via Testcontainers before).
 *
 *  Two tests are gone, not adapted: skipLockedLetsTwoClaimersNotFight and
 *  twoClaimersRacingOneRowProduceOneWinner tested FOR UPDATE SKIP LOCKED specifically - two
 *  connections racing to claim, one holding an uncommitted row lock while the other must skip to the
 *  next candidate rather than block. SQLite has no such mechanism, and JobQueue.claim() no longer
 *  claims to provide it (see its class javadoc): the service's worker is single-threaded, so a plain
 *  atomic UPDATE is enough and there is never a second claimer to skip past. Testing "does SQLite
 *  block or throw SQLITE_BUSY under a held lock" would be testing SQLite itself, not this code. */
class JobQueueIT {

    private DSLContext dsl() throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile("ace-jobqueue-test", ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    @Test
    void enqueueClaimPriorityCancelAndRequeue() throws Exception {
        final var q = new JobQueue(dsl());
        final var results = Files.createTempDirectory("results").toString();

        q.enqueue(spec("low-1"), 0, results, "r", "o", null, null, null);
        q.enqueue(spec("high-1"), 5, results, "r", "o", null, null, null);
        final JobQueue.Job claimed = q.claim();
        assertEquals("high-1", claimed.runId());                     // priority DESC wins

        q.setStatus(claimed.id(), "failed", "boom");
        assertEquals("failed", q.get(claimed.id()).get("status"));

        final java.nio.file.Path dir = java.nio.file.Path.of(results, "high-1");
        Files.createDirectories(dir);                                 // ...a re-run would mix its files
        final IllegalStateException mixed = assertThrows(IllegalStateException.class, () -> q.requeue(claimed.id(), results));
        assertTrue(mixed.getMessage().contains("would mix its files"));
        Files.delete(dir);
        q.requeue(claimed.id(), results);
        assertEquals("queued", q.get(claimed.id()).get("status"));

        final JobQueue.Job again = q.claim();
        assertEquals("high-1", again.runId());   // the requeued job kept its priority: it wins the next claim again
        q.cancel(again.id());
        assertEquals("running", q.get(again.id()).get("status"));   // a RUNNING job gets the flag; the worker stops it
        assertTrue((Boolean) q.get(again.id()).get("cancel_requested"));

        final JobQueue.Job second = q.claim();
        assertEquals("low-1", second.runId());
        q.setStatus(second.id(), "queued", null);   // a non-running job cancels outright
        q.cancel(second.id());
        assertEquals("cancelled", q.get(second.id()).get("status"));
        assertTrue((Boolean) q.get(second.id()).get("cancel_requested"));

        assertThrows(IllegalArgumentException.class, () -> q.enqueue(spec("low-1"), 0, results, "r", "o", null, null, null));   // never twice
    }

    /** #87: two near-simultaneous enqueue() calls for the SAME explicit run id race past the
     *  pre-checks (not atomic with the insert) and both attempt it - the DB's own
     *  uq_jobs_kind_run_id constraint is the real backstop, but the LOSING request used to surface
     *  as a raw, unmapped jOOQ exception instead of a clean IllegalStateException (409 via
     *  ApiExceptionHandler, matching cancel()/requeue()'s existing "changed concurrently" handling). */
    @Test
    void concurrentEnqueueOfTheSameRunIdLosesWithIllegalStateExceptionNotARawDbException() throws Exception {
        final var q = new JobQueue(dsl());
        final var results = Files.createTempDirectory("results").toString();
        final var barrier = new java.util.concurrent.CyclicBarrier(2);
        final var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<Object>();

        final Runnable attempt = () -> {
            try {
                barrier.await();
                outcomes.add(q.enqueue(spec("race-1"), 0, results, "r", "o", null, null, null));
            } catch (Throwable t) {
                outcomes.add(t);
            }
        };
        final Thread t1 = new Thread(attempt), t2 = new Thread(attempt);
        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        assertEquals(2, outcomes.size());
        final long succeeded = outcomes.stream().filter(o -> o instanceof Map).count();
        final long failed = outcomes.stream().filter(o -> o instanceof Throwable).count();
        assertEquals(1, succeeded, "exactly one of the two racing enqueues must win: " + outcomes);
        assertEquals(1, failed, "the other must lose, not also succeed (a real double-insert): " + outcomes);
        final var loser = outcomes.stream().filter(o -> o instanceof Throwable).findFirst().orElseThrow();
        assertTrue(loser instanceof IllegalStateException,
                "the losing request must surface as IllegalStateException (409), not a raw DB exception: " + loser);
    }

    private static RunSpec spec(final String runId) {
        return new RunSpec("L3p_point_in_time", "m", null, "monolithic", "agent", 3600, null, null, null, null, false, false, false, null, false, true, false, false, null, null, null, null, false, null, null, null, null, runId, null, null, null, null, null, null, null, null, null, null);
    }
}
