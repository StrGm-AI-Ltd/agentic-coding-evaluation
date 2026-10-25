package com.strgmai.ace.service.agent;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The LLM Providers registry (V7's own migration + seed data) - replaces the old hardcoded
 *  ProviderConfig enum. Each test gets a fresh, freshly-migrated SQLite DB (same convention
 *  ExperimentsServiceTest/BenchControllerTest already use) so the 5 seed rows are always there,
 *  untouched by any other test. */
class ProviderStoreTest {

    private static ProviderStore freshStore(final String name) throws Exception {
        final var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + Files.createTempFile(name, ".db") + "?foreign_keys=on");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return new ProviderStore(DSL.using(ds, SQLDialect.SQLITE));
    }

    @Test
    void listReturnsTheFiveSeededProviders() throws Exception {
        final var store = freshStore("provider-store-list");
        final var names = store.list().stream().map(ProviderStore.Provider::providerKey).sorted().toList();
        assertEquals(List.of("anthropic", "gemini", "nebius", "openai", "openrouter"), names);
    }

    @Test
    void nebiusIsSeededWithItsOwnDefaultModel() throws Exception {
        final var store = freshStore("provider-store-nebius-default");
        final var nebius = store.byKey("nebius");
        assertNotNull(nebius);
        assertEquals("zai-org/GLM-5.3-Flash", nebius.defaultModel());
        assertEquals("https://api.tokenfactory.us-central1.nebius.com/v1", nebius.baseUrl());
    }

    @Test
    void byKeyIsNullForAnUnknownProvider() throws Exception {
        assertNull(freshStore("provider-store-unknown-key").byKey("not-a-real-provider"));
    }

    @Test
    void byBaseUrlToleratesTrailingSlashAndVersionedPathVariants() throws Exception {
        final var store = freshStore("provider-store-url-variants");
        assertEquals("openai", store.byBaseUrl("https://api.openai.com/v1/").providerKey());
        assertEquals("anthropic", store.byBaseUrl("https://api.anthropic.com/v1/2023-06-01").providerKey());
        assertNull(store.byBaseUrl("https://not-a-known-host.example/v1"));
    }

    @Test
    void allCredentialsOnlyIncludesProvidersWithARealKeyConfigured() throws Exception {
        final var store = freshStore("provider-store-credentials");
        assertTrue(store.allCredentials().isEmpty());
        store.update(store.byKey("openai").id(), "OpenAI", "openai", "https://api.openai.com/v1", "sk-test", null);
        assertEquals(Map.of("openai", "sk-test"), store.allCredentials());
    }

    @Test
    void resolveModelReturnsTheFallbackWhenBlank() throws Exception {
        final var store = freshStore("provider-store-resolve-blank");
        assertEquals("omlx/local-model", store.resolveModel(null, "omlx/local-model"));
        assertEquals("omlx/local-model", store.resolveModel("  ", "omlx/local-model"));
    }

    @Test
    void resolveModelReturnsAnAlreadyQualifiedStringAsIs() throws Exception {
        final var store = freshStore("provider-store-resolve-qualified");
        assertEquals("openai/gpt-5", store.resolveModel("openai/gpt-5", "omlx/local-model"));
    }

    @Test
    void resolveModelExpandsABareKnownProviderKeyToItsOwnDefaultModel() throws Exception {
        final var store = freshStore("provider-store-resolve-bare-known");
        assertEquals("nebius/zai-org/GLM-5.3-Flash", store.resolveModel("nebius", "omlx/local-model"));
    }

    @Test
    void resolveModelRejectsABareKeyForAProviderWithNoDefaultModelConfigured() throws Exception {
        final var store = freshStore("provider-store-resolve-no-default");
        final var e = assertThrows(IllegalArgumentException.class, () -> store.resolveModel("openai", "omlx/local-model"));
        assertTrue(e.getMessage().contains("openai"), e.getMessage());
    }

    @Test
    void resolveModelRejectsABareUnknownProviderKey() throws Exception {
        final var store = freshStore("provider-store-resolve-unknown");
        assertThrows(IllegalArgumentException.class, () -> store.resolveModel("not-a-real-provider", "omlx/local-model"));
    }

    @Test
    void createInsertsANewProviderRow() throws Exception {
        final var store = freshStore("provider-store-create");
        final var p = store.create("Custom", "custom", "https://custom.example/v1", "sk-custom", "some/model");
        assertNotNull(p.id());
        final var reread = store.byKey("custom");
        assertEquals("Custom", reread.name());
        assertEquals("sk-custom", reread.apiKey());
        assertEquals("some/model", reread.defaultModel());
    }

    @Test
    void updateWithANullApiKeyLeavesTheStoredKeyUnchanged() throws Exception {
        final var store = freshStore("provider-store-update-null-key");
        final var id = store.create("Custom", "custom", "https://custom.example/v1", "sk-original", null).id();
        store.update(id, "Custom Renamed", "custom", "https://custom.example/v2", null, "some/model");
        final var updated = store.byKey("custom");
        assertEquals("Custom Renamed", updated.name());
        assertEquals("https://custom.example/v2", updated.baseUrl());
        assertEquals("sk-original", updated.apiKey(), "a null apiKey on update must leave the stored secret untouched");
        assertEquals("some/model", updated.defaultModel());
    }

    @Test
    void updateWithAnEmptyStringApiKeyClearsIt() throws Exception {
        final var store = freshStore("provider-store-update-clear-key");
        final var id = store.create("Custom", "custom", "https://custom.example/v1", "sk-original", null).id();
        store.update(id, "Custom", "custom", "https://custom.example/v1", "", null);
        assertNull(store.byKey("custom").apiKey());
    }

    @Test
    void deleteRemovesTheRow() throws Exception {
        final var store = freshStore("provider-store-delete");
        final UUID id = store.create("Custom", "custom", "https://custom.example/v1", null, null).id();
        store.delete(id);
        assertNull(store.byKey("custom"));
    }
}
