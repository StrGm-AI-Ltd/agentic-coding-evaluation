package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** #106: this table used to be duplicated independently across ReferenceAgent.apiKeyFor() and
 *  Reviews.externalBase()/externalCredentials(); pin the lookups both of those now rely on. */
class ProviderConfigTest {

    @Test
    void byNameFindsEveryKnownProvider() {
        assertSame(ProviderConfig.OPENAI, ProviderConfig.byName("openai"));
        assertSame(ProviderConfig.OPENROUTER, ProviderConfig.byName("openrouter"));
        assertSame(ProviderConfig.ANTHROPIC, ProviderConfig.byName("anthropic"));
        assertSame(ProviderConfig.GEMINI, ProviderConfig.byName("gemini"));
        assertSame(ProviderConfig.NEBIUS, ProviderConfig.byName("nebius"));
    }

    @Test
    void byNameIsNullForAnUnknownProvider() {
        assertNull(ProviderConfig.byName("some-local-model"));
    }

    @Test
    void byBaseUrlToleratesTrailingSlashesAndVersionedPaths() {
        assertSame(ProviderConfig.OPENAI, ProviderConfig.byBaseUrl("https://api.openai.com/v1/"));
        assertSame(ProviderConfig.ANTHROPIC, ProviderConfig.byBaseUrl("https://api.anthropic.com/v1/2023-06-01"));
        assertSame(ProviderConfig.OPENROUTER, ProviderConfig.byBaseUrl("https://openrouter.ai/api/v1/chat"));
        assertSame(ProviderConfig.GEMINI, ProviderConfig.byBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai"));
        assertSame(ProviderConfig.NEBIUS, ProviderConfig.byBaseUrl("https://api.tokenfactory.us-central1.nebius.com/v1"));
    }

    @Test
    void byBaseUrlIsNullForALocalOrUnknownUrl() {
        assertNull(ProviderConfig.byBaseUrl("http://127.0.0.1:9191/v1"));
        assertNull(ProviderConfig.byBaseUrl(null));
    }

    @Test
    void everyProviderHasItsOwnBaseUrlAndCredentialEnvVar() {
        for (final ProviderConfig p : ProviderConfig.values()) {
            assertEquals(p, ProviderConfig.byName(p.providerName));
            assertEquals(p, ProviderConfig.byBaseUrl(p.baseUrl));
        }
    }
}
