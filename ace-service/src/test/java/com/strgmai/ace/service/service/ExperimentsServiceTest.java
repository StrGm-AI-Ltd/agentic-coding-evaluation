package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.JOBS;

import static org.junit.jupiter.api.Assertions.*;

/** Golden tests for ExperimentsService.plan() (the exact RunSpec each template's arms construct,
 *  including #10's per-arm context-window resolution) plus a real-DB test locking down #3: a
 *  mid-loop enqueue failure must roll back the whole experiment, not leave orphans. */
class ExperimentsServiceTest {

    private static BenchProperties props(final String endpoint) {
        return new BenchProperties(null, endpoint, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static ExperimentsService serviceWithUnreachableModelServer() {
        // port 1 refuses instantly: ContextProbe.models() catches the failure and returns {}, so
        // contextWindow() falls through to null - deterministic, no real network dependency
        return new ExperimentsService(null, null, props("http://127.0.0.1:1/v1"), null);
    }

    @Test
    void harnessEffectDefaultArmsCarryTheMonolithicBudgetMultiplierAndNullContextWindow() {
        final ExperimentsService svc = serviceWithUnreachableModelServer();
        final int n = ExperimentsService.taskCount();
        final List<ExperimentsService.ArmSpec> specs = svc.plan("harness_effect", Map.of("model", "modelX"), 1);

        assertEquals(2, specs.size());
        final ExperimentsService.ArmSpec orch = specs.stream().filter(s -> s.arm().equals("orch")).findFirst().orElseThrow();
        final ExperimentsService.ArmSpec mono = specs.stream().filter(s -> s.arm().equals("mono")).findFirst().orElseThrow();

        assertEquals("orchestrated", orch.spec().mode());
        assertNull(orch.spec().implWall());
        assertNull(orch.spec().implTokens());
        assertTrue(orch.spec().runId().matches("he-\\d{8}-\\d{6}-modelX-orch-r1"), orch.spec().runId());
        assertNull(orch.spec().contextWindow());

        assertEquals("monolithic", mono.spec().mode());
        assertEquals(3600 * n, mono.spec().implWall());       // the monolithic impl budget = N x task budget
        assertEquals(60000 * n, mono.spec().implTokens());
        assertTrue(mono.spec().runId().matches("he-\\d{8}-\\d{6}-modelX-mono-r1"), mono.spec().runId());
        assertNull(mono.spec().contextWindow());
    }

    @Test
    void modelAbResolvesEachArmsContextWindowIndependently() throws Exception {
        // model_a and model_b are different-sized models on the same server: windowA must not leak into windowB
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            byte[] body = ("{\"data\":[{\"id\":\"qwenmodel\",\"max_model_len\":32768}," +
                    "{\"id\":\"llamamodel\",\"max_model_len\":8192}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            ExperimentsService svc = new ExperimentsService(null, null,
                    props("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), null);
            List<ExperimentsService.ArmSpec> specs = svc.plan("model_ab",
                    Map.of("model_a", "qwenmodel", "model_b", "llamamodel"), 1);

            assertEquals(2, specs.size());
            final ExperimentsService.ArmSpec a = specs.stream().filter(s -> s.arm().equals("A")).findFirst().orElseThrow();
            final ExperimentsService.ArmSpec b = specs.stream().filter(s -> s.arm().equals("B")).findFirst().orElseThrow();
            assertEquals(32768, a.spec().contextWindow());
            assertEquals(8192, b.spec().contextWindow());
            assertEquals("qwenmodel", a.spec().model());
            assertEquals("llamamodel", b.spec().model());
            assertTrue(a.spec().runId().matches("ab-\\d{8}-\\d{6}-qwenmodel-a-r1"), a.spec().runId());
            assertTrue(b.spec().runId().matches("ab-\\d{8}-\\d{6}-llamamodel-b-r1"), b.spec().runId());
            assertTrue(a.spec().selfReview() && a.spec().trajectoryReview());   // model-ab always reviews both sides
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentAbComparesHarnessFlagAtEqualBudget() {
        final ExperimentsService svc = serviceWithUnreachableModelServer();
        final List<ExperimentsService.ArmSpec> specs = svc.plan("agent_ab", Map.of("model", "modelX"), 1);

        assertEquals(2, specs.size());
        final ExperimentsService.ArmSpec ref = specs.stream().filter(s -> s.arm().equals("ref")).findFirst().orElseThrow();
        final ExperimentsService.ArmSpec pi = specs.stream().filter(s -> s.arm().equals("pi")).findFirst().orElseThrow();
        assertEquals("ref", ref.spec().harness());
        assertEquals("pi", pi.spec().harness());
        assertEquals(3600, ref.spec().taskWall());
        assertEquals(3600, pi.spec().taskWall());              // --harness is the ONLY thing that differs
        assertTrue(ref.spec().runId().matches("aa-\\d{8}-\\d{6}-modelX-ref-r1"));
        assertTrue(pi.spec().runId().matches("aa-\\d{8}-\\d{6}-modelX-pi-r1"));
    }

    @Test
    void harnessEffectReviewsAreOptInOnAReviewerModelBeingSet() {
        final ExperimentsService svc = serviceWithUnreachableModelServer();
        final List<ExperimentsService.ArmSpec> noReviewer = svc.plan("harness_effect", Map.of("model", "modelX"), 1);
        assertTrue(noReviewer.stream().noneMatch(s -> s.spec().selfReview() || s.spec().trajectoryReview()), "no reviewer_model -> reviews off, unlike model_ab which always reviews");
        assertTrue(noReviewer.stream().allMatch(s -> s.spec().reviewerModel() == null));

        final List<ExperimentsService.ArmSpec> withReviewer = svc.plan("harness_effect", Map.of("model", "modelX", "reviewer_model", "openai/gpt-5"), 1);
        assertTrue(withReviewer.stream().allMatch(s -> s.spec().selfReview() && s.spec().trajectoryReview()));
        assertTrue(withReviewer.stream().allMatch(s -> "openai/gpt-5".equals(s.spec().reviewerModel())));
    }

    @Test
    void agentAbReviewsAreOptInOnAReviewerModelBeingSet() {
        final ExperimentsService svc = serviceWithUnreachableModelServer();
        final List<ExperimentsService.ArmSpec> noReviewer = svc.plan("agent_ab", Map.of("model", "modelX"), 1);
        assertTrue(noReviewer.stream().noneMatch(s -> s.spec().selfReview() || s.spec().trajectoryReview()));

        final List<ExperimentsService.ArmSpec> withReviewer = svc.plan("agent_ab", Map.of("model", "modelX", "reviewer_model", "openai/gpt-5"), 1);
        assertTrue(withReviewer.stream().allMatch(s -> s.spec().selfReview() && s.spec().trajectoryReview()));
        assertTrue(withReviewer.stream().allMatch(s -> "openai/gpt-5".equals(s.spec().reviewerModel())));
    }

    @Test
    void noContextProbeFlowsThroughToEveryTemplateArm() {
        final ExperimentsService svc = serviceWithUnreachableModelServer();
        final List<ExperimentsService.ArmSpec> off = svc.plan("harness_effect", Map.of("model", "modelX"), 1);
        assertTrue(off.stream().noneMatch(s -> s.spec().noContextProbe()));

        final List<ExperimentsService.ArmSpec> on = svc.plan("harness_effect", Map.of("model", "modelX", "no_context_probe", true), 1);
        assertTrue(on.stream().allMatch(s -> s.spec().noContextProbe()));
    }

    // --- #3: enqueue() rolls back the whole experiment on a mid-loop failure ---------------------

    @Test
    void aMidLoopEnqueueFailureRollsBackTheWholeExperiment() throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile("ace-experiments-test", ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        // plain DSL.using(dataSource, dialect) grabs a FRESH connection per statement, so it never
        // sees the connection a TransactionTemplate-managed transaction is bound to - every JOOQ
        // write would auto-commit on its own connection and rollback would do nothing. Wrapping the
        // DataSource in a TransactionAwareDataSourceProxy (what spring-boot-starter-jooq's own
        // auto-configuration does for the real app's DSLContext bean) makes JOOQ hand back the
        // SAME connection the current Spring transaction owns.
        final var txAwareDs = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(ds);
        final DSLContext db = DSL.using(new org.jooq.impl.DataSourceConnectionProvider(txAwareDs), SQLDialect.SQLITE);
        final var queue = new JobQueue(db);
        final var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        final var svc = new ExperimentsService(db, queue, props("http://127.0.0.1:1/v1"), tx);
        final var params = Map.of("model", "modelX", "arms", List.of("orch"));

        // arm r2's run id is deterministic given the tag; plan()'s tag has second resolution, so peek
        // and the real enqueue() below must land in the same wall-clock second. Both calls are pure/fast
        // (no I/O beyond the already-failed-fast unreachable model server), so the gap is sub-millisecond;
        // retry the rare case where the clock ticks over between the two.
        for (int attempt = 0; attempt < 5; attempt++) {
            db.deleteFrom(JOBS).execute();
            db.deleteFrom(EXPERIMENTS).execute();
            String collideRunId = svc.plan("harness_effect", params, 2).get(1).spec().runId();   // r2
            db.insertInto(JOBS, JOBS.KIND, JOBS.RUN_ID, JOBS.ARGV).values("run", collideRunId, "[]").execute();

            IllegalArgumentException thrown = null;
            try {
                svc.enqueue("exp", "harness_effect", params, 2, "/tmp/ace-service-nonexistent", "r", "o");
            } catch (IllegalArgumentException e) {
                thrown = e;
            }
            if (thrown == null) continue;   // the tag ticked over between peek and enqueue(); retry
            assertTrue(thrown.getMessage().contains("already queued"), thrown.getMessage());

            assertEquals(0, db.fetchCount(EXPERIMENTS), "the experiment row must not survive a rolled-back arm");
            assertEquals(1, db.fetchCount(JOBS), "only the seed collision row should remain: arm r1's job must have rolled back too");
            return;
        }
        fail("could not get the collision to land in the same clock-second after 5 attempts");
    }
}
