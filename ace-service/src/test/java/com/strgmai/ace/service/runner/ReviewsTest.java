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
import java.util.Map;

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
                        null, null, null, null, null, null, null),
                new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null), providerStore),
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
}
