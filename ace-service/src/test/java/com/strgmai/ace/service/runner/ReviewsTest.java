package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #106: externalBase()/externalCredentials() used to keep their own copy of the provider table
 *  ReferenceAgent.apiKeyFor() also kept; pin the behavior now that both read ProviderConfig. */
class ReviewsTest {

    private final Reviews reviews = new Reviews(
            new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null),
            new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null)));

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

    @Test
    void externalCredentialsHonoursAnExplicitCredentialEnvOverride() {
        // PATH is set in any environment this test runs in, unlike the provider API keys - a
        // reliable way to pin that an explicit override replaces the ProviderConfig-derived default
        final var extra = reviews.externalCredentials(Map.of("credential_env", List.of("PATH")));
        assertTrue(extra.containsKey("PATH"));
    }
}
