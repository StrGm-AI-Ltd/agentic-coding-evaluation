package com.agentbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Port of service/importer.py: results/<run_id>/ -> runs + check_results. Idempotent: the result
 *  files stay the source of truth; the DB row is an upsert. poolable = the schema is current (a
 *  poolable run can enter leaderboards). */
@Service
public class ImporterService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ImporterService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> importRun(Path runDir, Long jobId) throws Exception {
        Map<String, Object> oracle = json.readValue(runDir.resolve("oracle.json").toFile(), Map.class);
        Map<String, Object> manifest = Files.exists(runDir.resolve("manifest.json"))
                ? json.readValue(runDir.resolve("manifest.json").toFile(), Map.class) : new LinkedHashMap<>();
        Map<String, Object> metrics = Files.exists(runDir.resolve("metrics.json"))
                ? json.readValue(runDir.resolve("metrics.json").toFile(), Map.class) : new LinkedHashMap<>();
        Map<String, Object> prov = (Map<String, Object>) manifest.getOrDefault("provenance", Map.of());
        Map<String, Object> validity = (Map<String, Object>) manifest.getOrDefault("validity", Map.of());
        boolean poolable = Objects.equals(oracle.get("schema_version"), com.agentbench.config.BenchProperties.RESULT_SCHEMA)
                && oracle.get("weighted_score_pct") != null;
        Map<String, Object> contention = manifest.get("contention") instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
        Map<String, Object> leaderboard = metrics.get("leaderboard") instanceof Map<?, ?> lb ? (Map<String, Object>) lb : Map.of();
        jdbc.update("""
                INSERT INTO runs (run_id, results_dir, job_id, task, mode, model, harness, schema_version, poolable,
                    functional_score_pct, functional_points_got, functional_denominator, weighted_score_pct, points_got,
                    denominator, partial_score_pct, valid, validity_reasons, contended, key_hash, manifest, oracle, metrics)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb)
                ON CONFLICT (run_id) DO UPDATE SET results_dir = EXCLUDED.results_dir, poolable = EXCLUDED.poolable,
                    functional_score_pct = EXCLUDED.functional_score_pct, weighted_score_pct = EXCLUDED.weighted_score_pct,
                    valid = EXCLUDED.valid, imported_at = now()""",
                runDir.getFileName().toString(), runDir.toAbsolutePath().toString(), jobId,
                oracle.get("task"), manifest.getOrDefault("mode", "monolithic"), prov.get("model"), prov.get("harness"),
                oracle.get("schema_version"), poolable,
                oracle.get("functional_score_pct"), oracle.get("functional_points_got"), oracle.get("functional_denominator"),
                oracle.get("weighted_score_pct"), oracle.get("points_got"), oracle.get("denominator"), oracle.get("partial_score_pct"),
                validity.getOrDefault("valid", true), json.writeValueAsString(validity.getOrDefault("reasons", List.of())),
                Boolean.TRUE.equals(contention.get("docker_up")) || Boolean.TRUE.equals(contention.get("slow_decode")),
                keyHash(oracle, manifest),
                leaderboard.get("total_wall_sec"), leaderboard.get("completion_tokens"),
                json.writeValueAsString(manifest), json.writeValueAsString(oracle), json.writeValueAsString(metrics));
        jdbc.update("DELETE FROM check_results WHERE run_id = ?", runDir.getFileName().toString());
        for (Map<String, Object> r : (List<Map<String, Object>>) oracle.getOrDefault("results", List.of())) {
            String id = (String) r.get("id");
            var check = com.agentbench.oracle.CheckId.valueOf(id);
            jdbc.update("INSERT INTO check_results (run_id, check_id, category, weight, status, detail) VALUES (?,?,?,?,?,?::jsonb)",
                    runDir.getFileName().toString(), id, check.category, check.weight, r.get("status"), json.writeValueAsString(r.get("detail")));
        }
        return oracle;
    }

    public Map<String, Integer> importAll(Path resultsDir) throws Exception {
        int imported = 0, skipped = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(resultsDir)) {
            for (Path p : s) {
                if (!Files.isDirectory(p) || p.getFileName().toString().startsWith("_")) continue;
                if (Files.isRegularFile(p.resolve("oracle.json"))) { importRun(p, null); imported++; }
                else skipped++;
            }
        }
        return Map.of("imported", imported, "skipped", skipped);
    }

    static String keyHash(Map<String, Object> oracle, Map<String, Object> manifest) {
        // sha256 of the full comparability keytuple (stats.py load(): KEY_FIELDS), not a 3-field stand-in
        List<String> tuple = new ArrayList<>();
        for (String k : com.agentbench.metrics.StatsService.KEY_FIELDS)
            tuple.add(String.valueOf(switch (k) {
                case "task" -> oracle.get("task");
                case "mode" -> manifest.getOrDefault("mode", "monolithic");
                case "plan_source" -> manifest.getOrDefault("plan_source", "agent");
                case "harness_version" -> ((Map<String, Object>) manifest.getOrDefault("provenance", Map.of())).get("harness_version");
                case "budgets_wall" -> manifest.get("budgets") instanceof Map<?, ?> b ? ((Map<?, ?>) b).get("wall_sec") : null;
                case "budgets_tokens" -> manifest.get("budgets") instanceof Map<?, ?> b ? ((Map<?, ?>) b).get("completion_tokens") : null;
                case "sampler" -> manifest.get("journal_facts") instanceof Map<?, ?> j ? ((Map<?, ?>) j).get("sampler_effective") : null;
                case "system_prompt_sha" -> manifest.get("journal_facts") instanceof Map<?, ?> j ? ((Map<?, ?>) j).get("system_prompt_sha") : null;
                default -> manifest.get(k);
            }));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("|", tuple).getBytes())).substring(0, 16); }
        catch (Exception e) { return null; }
    }
}
