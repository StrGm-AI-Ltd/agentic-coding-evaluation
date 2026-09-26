package com.strgmai.ace.service.agent;

/** #106: the single source of truth for "which external provider is this" - ReferenceAgent.apiKeyFor()
 *  needs it by base URL (a proxyBase LangChain4j is about to call), Reviews.externalBase()/
 *  externalCredentials() need it by name (a reviewer string's "provider/model" prefix) and by the
 *  full set of forwardable credential env vars. Before this, each of those three kept its own copy
 *  of the same provider table, with no compiler or test signal when one drifted from another. */
public enum ProviderConfig {
    OPENAI("openai", "https://api.openai.com/v1", "api.openai.com", "OPENAI_API_KEY"),
    OPENROUTER("openrouter", "https://openrouter.ai/api/v1", "openrouter.ai", "OPENROUTER_API_KEY"),
    ANTHROPIC("anthropic", "https://api.anthropic.com/v1", "api.anthropic.com", "ANTHROPIC_API_KEY"),
    GEMINI("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "generativelanguage", "GEMINI_API_KEY"),
    NEBIUS("nebius", "https://api.tokenfactory.us-central1.nebius.com/v1", "nebius", "NEBIUS_API_KEY");

    public final String providerName;
    public final String baseUrl;
    final String hostToken;
    public final String credentialEnvVar;

    ProviderConfig(final String providerName, final String baseUrl, final String hostToken, final String credentialEnvVar) {
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.hostToken = hostToken;
        this.credentialEnvVar = credentialEnvVar;
    }

    /** Reviews.externalBase(): a reviewer string's "provider/model" prefix, e.g. "openai" */
    public static ProviderConfig byName(final String providerName) {
        for (final ProviderConfig p : values()) if (p.providerName.equals(providerName)) return p;
        return null;
    }

    /** ReferenceAgent.apiKeyFor(): matches a base URL variant (trailing slash, versioned/regional
     *  path) the same tolerant way for every provider, not just the ones that happened to need it. */
    public static ProviderConfig byBaseUrl(final String url) {
        if (url == null) return null;
        for (final ProviderConfig p : values()) if (url.contains(p.hostToken)) return p;
        return null;
    }
}
