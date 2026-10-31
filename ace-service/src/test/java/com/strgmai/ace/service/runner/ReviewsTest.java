package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ProviderStore;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.backend.OmlxBackend;
import com.strgmai.ace.service.config.BenchProperties;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #106/LLM-Providers: externalBase()/externalCredentials() used to keep their own copy of the
 *  provider table ReferenceAgent.apiKeyFor() also kept; pin the behavior now that both read the
 *  same ProviderStore (a real one here, backed by a fresh migrated SQLite DB, so these assertions
 *  exercise the actual seeded provider rows - V7's own seed data uses the exact same URLs
 *  ProviderConfig used to hardcode, so these values are unchanged from before that migration). */
class ReviewsTest {

    private static DSLContext freshDb(final String name) throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile(name, ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return DSL.using(ds, SQLDialect.SQLITE);
    }

    private final ProviderStore providerStore;
    private final Reviews reviews;

    ReviewsTest() throws Exception {
        providerStore = new ProviderStore(freshDb("reviews-test"));
        reviews = new Reviews(
                new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null),
                new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null), providerStore),
                new Intervene(), providerStore, new OmlxBackend(Files.createTempDirectory("omlx-test")));
    }

    @Test
    void externalBaseResolvesEveryKnownProviderPrefix() {
        assertEquals("https://api.openai.com/v1", reviews.externalBase("openai/gpt-4", Map.of()));
        assertEquals("https://openrouter.ai/api/v1", reviews.externalBase("openrouter/some-model", Map.of()));
        assertEquals("https://api.anthropic.com/v1", reviews.externalBase("anthropic/claude", Map.of()));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", reviews.externalBase("gemini/pro", Map.of()));
        assertEquals("https://api.tokenfactory.us-central1.nebius.com/v1", reviews.externalBase("nebius/llama", Map.of()));
    }

    @Test
    void externalBaseFallsBackToTheConfiguredEndpointForAnUnknownProvider() {
        assertEquals("http://127.0.0.1:9191/v1", reviews.externalBase("localmodel/whatever", Map.of()));
    }

    /** #295, found live: a reviewer-model override with no "provider/" prefix at all (a plausible
     *  mistake - the run's own --model flag is a bare name everywhere else) made indexOf('/')
     *  return -1 and substring(0, -1) throw, with nothing in Reviews.java catching it - failing
     *  the whole run over one optional, calibration-only review step. No "/" is just an
     *  unrecognised provider, same as any other unknown one. */
    @Test
    void externalBaseDoesNotThrowOnAReviewerStringWithNoProviderPrefixAtAll() {
        assertEquals("http://127.0.0.1:9191/v1", reviews.externalBase("bare-model-name-no-slash", Map.of()));
    }

    /** #106/LLM-Providers: the old "credential_env" override (an explicit list of env-var names to
     *  forward instead of every known provider's own) is gone, not reimplemented - it was never
     *  wired to any real config path (confirmed via repo-wide grep), only this test exercised it
     *  directly. externalCredentials() is now a plain pass-through to the shared registry's own
     *  allCredentials() - a provider with no key configured is correctly absent, never a blank
     *  placeholder entry. */
    @Test
    void externalCredentialsReturnsOnlyProvidersWithARealKeyConfigured() {
        assertTrue(reviews.externalCredentials().isEmpty(), "none of the seeded providers have a key set in this fresh test DB");
        providerStore.update(providerStore.byKey("nebius").id(), "Nebius", "nebius",
                "https://api.tokenfactory.us-central1.nebius.com/v1", "sk-nebius-test", "zai-org/GLM-5.3-Flash");
        final var extra = reviews.externalCredentials();
        assertEquals(Map.of("nebius", "sk-nebius-test"), extra);
        assertFalse(extra.containsKey("openai"), "openai has no key configured, so it must be absent, not blank");
    }

    /** #628: a requeue/restart that lands after self-review already completed must skip straight
     *  to reconstructing its own result from disk, never re-invoking an already-settled reviewer
     *  session. Proven by pointing `reviews` at an endpoint with nothing listening
     *  (http://127.0.0.1:9191/v1, this constructor's own default) - if the resume guard were
     *  missing or broken, selfReview() would attempt a real network call and this test would hang
     *  or throw a connection exception instead of returning cleanly. */
    @Test
    void selfReviewSkipsAnAlreadyCompletedReviewInsteadOfReInvokingTheReviewerSession() throws Exception {
        final Path ws = Files.createTempDirectory("reviews-resume-ws");
        final Path rd = Files.createTempDirectory("reviews-resume-rd");
        RunBenchSupport.snapshot(ws, "run/start");
        RunBenchSupport.snapshot(ws, "phase/pre-review");
        RunBenchSupport.snapshot(ws, "phase/review");   // the completion tag selfReview()'s own guard checks for

        final Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", "resume-test-run");
        final String sessionId = UUID.nameUUIDFromBytes("agentbench/resume-test-run/REVIEW".getBytes()).toString();
        Files.createDirectories(rd.resolve("sessions"));
        Files.writeString(rd.resolve("sessions").resolve("2026-01-01T00-00-00.000Z_" + sessionId + ".jsonl"),
                "{\"type\":\"session\",\"id\":\"" + sessionId + "\",\"label\":\"REVIEW\",\"ts\":\"2026-01-01T00:00:00.000Z\"}\n"
                        + "{\"type\":\"end\",\"finish\":\"stop\",\"turns\":1,\"ts\":\"2026-01-01T00:01:00.000Z\"}\n");
        Files.createDirectories(ws.resolve("review"));
        Files.writeString(ws.resolve("review/self_review.json"), "{\"score\": 80}");

        final Map<String, Object> out = reviews.selfReview(Map.of(), ws, rd, manifest, List.of(), "a prompt",
                new RecordingProxyFactory(new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null)), null, false);

        assertEquals(true, out.get("parse_ok"));
        assertEquals(80.0, out.get("score"), "the already-written self_review.json must be re-parsed, not lost");
        assertEquals(0, out.get("rc"));
        assertEquals(1, ((List<?>) manifest.get("review_windows")).size(), "the recovered session span must still be recorded");
    }

    /** #628: trajectoryReview()'s identical guard. */
    @Test
    void trajectoryReviewSkipsAnAlreadyCompletedReviewInsteadOfReInvokingTheReviewerSession() throws Exception {
        final Path ws = Files.createTempDirectory("reviews-resume-traj-ws");
        final Path rd = Files.createTempDirectory("reviews-resume-traj-rd");
        RunBenchSupport.snapshot(ws, "run/start");
        RunBenchSupport.snapshot(ws, "phase/pre-trajectory-review");
        RunBenchSupport.snapshot(ws, "phase/trajectory_review");

        final Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", "resume-test-run");
        final String sessionId = UUID.nameUUIDFromBytes("agentbench/resume-test-run/TRAJECTORY_REVIEW".getBytes()).toString();
        Files.createDirectories(rd.resolve("sessions"));
        Files.writeString(rd.resolve("sessions").resolve("2026-01-01T00-00-00.000Z_" + sessionId + ".jsonl"),
                "{\"type\":\"session\",\"id\":\"" + sessionId + "\",\"label\":\"TRAJECTORY_REVIEW\",\"ts\":\"2026-01-01T00:00:00.000Z\"}\n"
                        + "{\"type\":\"end\",\"finish\":\"stop\",\"turns\":1,\"ts\":\"2026-01-01T00:01:00.000Z\"}\n");
        Files.createDirectories(ws.resolve("review"));
        Files.writeString(ws.resolve("review/trajectory_review.json"), "{\"score\": 70}");

        final Map<String, Object> out = reviews.trajectoryReview(Map.of(), ws, rd, manifest, null,
                new RecordingProxyFactory(new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null)));

        assertEquals(true, out.get("parse_ok"));
        assertEquals(70.0, out.get("score"), "the already-written trajectory_review.json must be re-parsed, not lost");
        assertEquals(0, out.get("rc"));
    }
}
