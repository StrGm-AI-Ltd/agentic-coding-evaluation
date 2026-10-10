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
        // #298: pollSec and referenceServerPort removed. pollSec was dead SURFACE only, not a dead
        // BINDING - WorkerService's own @Scheduled(fixedDelayString = "${ace.poll-sec:10}000")
        // reads the SAME ace.poll-sec property directly via Spring's own placeholder resolution
        // (the poll interval is genuinely honored; only this record's own accessor for it was
        // never read by anything). referenceServerPort was fully dead: nothing in main code ever
        // references ReferenceServer at all (it has no main(), constructed only from one test with
        // its own explicit port) - the oracle does not, in fact, start one on demand, despite what
        // application.yml's own comment used to claim.
        Double maxErrorRate,
        String javaHome,
        String omlxServerLog,
        Map<String, Integer> phaseWallSec,
        Map<String, Integer> phaseTokenBudgets) {

    public static final String HARNESS_VERSION = "jls-ref-1.0";   // goes into provenance + the comparability key
    public static final int RESULT_SCHEMA = 3;                   // port of registry.RESULT_SCHEMA

    /** #277: "this run's schema matches the current build and it actually has a full weighted
     *  score" used to be two independently hand-written boolean expressions - ImporterService's
     *  own (over a plain Map, persisted into RUNS.POOLABLE) and StatsService's own (over a Jackson
     *  JsonNode, recomputed fresh whenever /api/compare or /api/groups loads a run from disk) -
     *  easy to update one on a future schema change and forget the other, silently desyncing the
     *  persisted column from what a fresh computation would say. `hasWeightedScorePct` is a plain
     *  boolean, not the raw Map/JsonNode, so this works identically from either representation -
     *  each caller decides what "has a real weighted score" means for ITS OWN representation
     *  (a plain Map's null check vs. a JsonNode's isNull()/has() distinction) and passes the result in. */
    public static boolean poolable(final Object schemaVersion, final boolean hasWeightedScorePct) {
        return java.util.Objects.equals(schemaVersion, RESULT_SCHEMA) && hasWeightedScorePct;
    }

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
