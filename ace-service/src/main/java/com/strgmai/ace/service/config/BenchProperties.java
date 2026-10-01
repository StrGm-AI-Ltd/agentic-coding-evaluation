package com.strgmai.ace.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

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
        String omlxServerLog,
        Map<String, Integer> phaseWallSec,
        Map<String, Integer> phaseTokenBudgets) {

    public static final String HARNESS_VERSION = "jls-ref-1.0";   // goes into provenance + the comparability key
    public static final int RESULT_SCHEMA = 3;                   // port of registry.RESULT_SCHEMA

    /** #173: used to be a stub ignoring `phase` entirely and always returning 0 - a real
     *  @ConfigurationProperties record advertising a per-phase budget surface with no backing field
     *  for it at all. Operator-wide default, keyed by phase id (e.g. "p0_definition", "p1_plan");
     *  unset (no entry, or the map itself absent) means unlimited, same "0 = no budget" convention
     *  every other budget in this codebase already uses. A run's own --impl-wall/--impl-tokens
     *  (JobCfgFactory -> cfg) wins over this default for the implementation phase specifically -
     *  see RunBench.monolithicPhases(). */
    public int phaseWall(final String phase) { return phaseWallSec == null ? 0 : phaseWallSec.getOrDefault(phase, 0); }

    /** completion-token budgets per phase, enforced by the recording proxy - same convention as
     *  phaseWall() above. */
    public int phaseTokens(final String phase) { return phaseTokenBudgets == null ? 0 : phaseTokenBudgets.getOrDefault(phase, 0); }

    /** the operator's UPPER BOUND on the window; null = none (the probe owns the window, Python default) */
    public Integer effectiveContextWindow() { return contextWindow() == null || contextWindow() <= 0 ? null : contextWindow(); }

    public String upstreamBase() {                       // the oMLX/OpenAI-compatible server, without /v1
        return endpoint == null || endpoint.isBlank() ? "http://127.0.0.1:9191"
                : endpoint.endsWith("/v1") ? endpoint.substring(0, endpoint.length() - 3) : endpoint;
    }
}
