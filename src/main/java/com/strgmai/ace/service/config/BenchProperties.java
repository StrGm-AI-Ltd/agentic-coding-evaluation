package com.strgmai.ace.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Port of config/default.json (the knobs that survive the port; the oMLX probe/parallel/review
 *  machinery is documented as not ported — see README-JLS.md). */
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
        String javaHome) {

    public static final String HARNESS_VERSION = "jls-ref-1.0";   // goes into provenance + the comparability key
    public static final int RESULT_SCHEMA = 3;                   // port of registry.RESULT_SCHEMA

    /** budgets per phase, wall seconds — port of cfg["budgets"] */
    public int phaseWall(String phase) {
        return switch (phase) {
            case "p0_definition", "p1_plan" -> 3600;
            case "p2_implementation" -> 14400;
            default -> 7200;
        };
    }

    /** completion-token budgets per phase, enforced by the recording proxy — port of cfg["token_budgets"] */
    public int phaseTokens(String phase) {
        return switch (phase) {
            case "p0_definition", "p1_plan" -> 60000;
            default -> 400000;
        };
    }

    /** the operator's UPPER BOUND on the window; null = none (the probe owns the window, Python default) */
    public Integer effectiveContextWindow() { return contextWindow() == null || contextWindow() <= 0 ? null : contextWindow(); }

    public String upstreamBase() {                       // the oMLX/OpenAI-compatible server, without /v1
        return endpoint == null || endpoint.isBlank() ? "http://127.0.0.1:9191"
                : endpoint.endsWith("/v1") ? endpoint.substring(0, endpoint.length() - 3) : endpoint;
    }
}
