package com.strgmai.ace.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ace")
public record BenchProperties(
        String model,
        String endpoint,
        String apiKey,
        Double temperature,
        Double topP,
        Integer seed,
        Integer maxOutputTokens,
        Integer contextWindow,
        Integer keepRecentTurns,
        Integer compactionTrigger,
        String resultsDir,
        String workspaceRoot,
        Integer pollSec,
        Double maxErrorRate,
        Integer referenceServerPort,
        String javaHome,
        String omlxServerLog) {

    public static final String HARNESS_VERSION = "jls-ref-1.0";   // goes into provenance + the comparability key
    public static final int RESULT_SCHEMA = 3;                   // port of registry.RESULT_SCHEMA

    /** budgets per phase, wall seconds — port of cfg["budgets"]. 0 means unlimited (the system-wide
     *  "0 = no budget" convention); every phase defaults to that unless the operator pins one. */
    public int phaseWall(final String phase) { return 0; }

    /** completion-token budgets per phase, enforced by the recording proxy — port of
     *  cfg["token_budgets"]. 0 means unlimited, same convention as phaseWall(). */
    public int phaseTokens(final String phase) { return 0; }

    /** the operator's UPPER BOUND on the window; null = none (the probe owns the window, Python default) */
    public Integer effectiveContextWindow() { return contextWindow() == null || contextWindow() <= 0 ? null : contextWindow(); }

    public String upstreamBase() {                       // the oMLX/OpenAI-compatible server, without /v1
        return endpoint == null || endpoint.isBlank() ? "http://127.0.0.1:9191"
                : endpoint.endsWith("/v1") ? endpoint.substring(0, endpoint.length() - 3) : endpoint;
    }
}
