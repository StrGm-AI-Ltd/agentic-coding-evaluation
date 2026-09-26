package com.strgmai.ace.service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strgmai.ace.service.jooq.tables.records.CheckResultsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static com.strgmai.ace.service.jooq.Tables.CHECK_RESULTS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.excluded;

/** Port of service/importer.py: results/<run_id>/ -> runs + check_results. Idempotent: the result
 *  files stay the source of truth; the DB row is an upsert. poolable = the schema is current (a
 *  poolable run can enter leaderboards). */
@Service
public class ImporterService {
    private static final Logger log = LoggerFactory.getLogger(ImporterService.class);
    private final DSLContext dsl;
    private final ObjectMapper json = new ObjectMapper();

    public ImporterService(DSLContext dsl) { this.dsl = dsl; }

    public Map<String, Object> importRun(final Path runDir, final UUID jobId) throws Exception {
        final Map<String, Object> oracle = json.readValue(runDir.resolve("oracle.json").toFile(), Map.class);
        Map<String, Object> manifest = Files.exists(runDir.resolve("manifest.json"))
                ? json.readValue(runDir.resolve("manifest.json").toFile(), Map.class) : new LinkedHashMap<>();
        Map<String, Object> metrics = Files.exists(runDir.resolve("metrics.json"))
                ? json.readValue(runDir.resolve("metrics.json").toFile(), Map.class) : new LinkedHashMap<>();
        final Map<String, Object> prov = (Map<String, Object>) manifest.getOrDefault("provenance", Map.of());
        final Map<String, Object> validity = (Map<String, Object>) manifest.getOrDefault("validity", Map.of());
        boolean poolable = Objects.equals(oracle.get("schema_version"), com.strgmai.ace.service.config.BenchProperties.RESULT_SCHEMA)
                && oracle.get("weighted_score_pct") != null;
        final Map<String, Object> contention = manifest.get("contention") instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
        final Map<String, Object> leaderboard = metrics.get("leaderboard") instanceof Map<?, ?> lb ? (Map<String, Object>) lb : Map.of();
        final String runId = runDir.getFileName().toString();
        // a re-import/rescore refreshes EVERY column the insert sets (importer.py builds `updates`
        // from the whole row); job_id is the one exception — importAll passes null and must not
        // orphan the run from the job that produced it (COALESCE onto the existing row's job_id).
        // #89: one shared column->value map for insert and update, so a column added to it can't be
        // forgotten on the other side the way two hand-duplicated .set() chains could silently drift.
        final Map<org.jooq.Field<?>, Object> values = new LinkedHashMap<>();
        values.put(RUNS.RESULTS_DIR, runDir.toAbsolutePath().toString());
        values.put(RUNS.TASK, str(oracle.get("task")));
        values.put(RUNS.MODE, str(manifest.getOrDefault("mode", "monolithic")));
        values.put(RUNS.MODEL, str(prov.get("model")));
        values.put(RUNS.HARNESS, str(prov.get("harness")));
        values.put(RUNS.SCHEMA_VERSION, num(oracle.get("schema_version")));
        values.put(RUNS.POOLABLE, poolable);
        values.put(RUNS.FUNCTIONAL_SCORE_PCT, flt(oracle.get("functional_score_pct")));
        values.put(RUNS.FUNCTIONAL_POINTS_GOT, num(oracle.get("functional_points_got")));
        values.put(RUNS.FUNCTIONAL_DENOMINATOR, num(oracle.get("functional_denominator")));
        values.put(RUNS.WEIGHTED_SCORE_PCT, flt(oracle.get("weighted_score_pct")));
        values.put(RUNS.POINTS_GOT, num(oracle.get("points_got")));
        values.put(RUNS.DENOMINATOR, num(oracle.get("denominator")));
        values.put(RUNS.PARTIAL_SCORE_PCT, flt(oracle.get("partial_score_pct")));
        values.put(RUNS.VALID, (Boolean) validity.getOrDefault("valid", true));
        values.put(RUNS.VALIDITY_REASONS, toJson(validity.getOrDefault("reasons", List.of())));
        values.put(RUNS.CONTENDED, Boolean.TRUE.equals(contention.get("docker_up")) || Boolean.TRUE.equals(contention.get("slow_decode")));
        values.put(RUNS.KEY_HASH, keyHash(oracle, manifest));
        values.put(RUNS.WALL_SEC, flt(leaderboard.get("total_wall_sec")));
        values.put(RUNS.COMPLETION_TOKENS, num(leaderboard.get("completion_tokens")));
        values.put(RUNS.MANIFEST, toJson(manifest));
        values.put(RUNS.ORACLE, toJson(oracle));
        values.put(RUNS.METRICS, toJson(metrics));
        final Map<org.jooq.Field<?>, Object> updates = new LinkedHashMap<>();
        values.forEach((field, v) -> updates.put(field, excluded(field)));
        dsl.insertInto(RUNS)
                .set(RUNS.RUN_ID, runId)
                .set(RUNS.JOB_ID, jobId)
                .set(values)
                .onConflict(RUNS.RUN_ID).doUpdate()
                .set(updates)
                .set(RUNS.JOB_ID, coalesce(excluded(RUNS.JOB_ID), RUNS.JOB_ID))
                .set(RUNS.IMPORTED_AT, Instant.now().toString())
                .execute();
        dsl.deleteFrom(CHECK_RESULTS).where(CHECK_RESULTS.RUN_ID.eq(runId)).execute();
        for (Map<String, Object> r : (List<Map<String, Object>>) oracle.getOrDefault("results", List.of())) {
            final String id = (String) r.get("id");
            final var check = com.strgmai.ace.service.oracle.CheckId.valueOf(id);
            final CheckResultsRecord rec = dsl.newRecord(CHECK_RESULTS);
            rec.setRunId(runId);
            rec.setCheckId(id);
            rec.setCategory(check.category);
            rec.setWeight(check.weight);
            rec.setDescription(check.description);
            rec.setStatus(str(r.get("status")));
            rec.setDetail(toJson(r.get("detail")));
            rec.insert();
        }
        return oracle;
    }

    /** run-id lists, not counts - the Vaadin UI's Api.ImportResult (ported from the Python service's
     *  own importer.import_all contract) deserializes "imported"/"skipped" as List&lt;String&gt;. */
    public Map<String, List<String>> importAll(final Path resultsDir) throws Exception {
        final List<String> imported = new ArrayList<>(), skipped = new ArrayList<>();
        // a results dir that has never been created yet (a fresh checkout, or ACE_RESULTS_DIR
        // pointing somewhere nothing has run) means "nothing to import", not a server error -
        // Files.newDirectoryStream throws NoSuchFileException on a missing directory
        if (!Files.isDirectory(resultsDir)) return Map.of("imported", imported, "skipped", skipped);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(resultsDir)) {
            for (Path p : s) {
                if (!Files.isDirectory(p) || p.getFileName().toString().startsWith("_")) continue;
                final String runId = p.getFileName().toString();
                if (Files.isRegularFile(p.resolve("oracle.json"))) { importRun(p, null); imported.add(runId); }
                else skipped.add(runId);
            }
        }
        return Map.of("imported", imported, "skipped", skipped);
    }

    static String keyHash(final Map<String, Object> oracle, final Map<String, Object> manifest) {
        // sha256 of the full comparability keytuple (stats.py load(): KEY_FIELDS), not a 3-field stand-in
        final List<String> tuple = new ArrayList<>();
        for (String k : com.strgmai.ace.service.metrics.StatsService.KEY_FIELDS)
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
        catch (Exception e) {
            // a null key_hash silently opts this run out of leaderboard pooling/comparisons
            log.warn("could not compute the comparability key hash: {}", e.toString());
            return null;
        }
    }

    private String toJson(final Object o) {
        try { return json.writeValueAsString(o); }
        catch (Exception e) { log.warn("could not serialize {} for storage, storing as empty: {}", o, e.toString()); return "{}"; }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Integer num(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private static Float flt(Object o) { return o instanceof Number n ? n.floatValue() : null; }
}
