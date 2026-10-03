package com.strgmai.ace.service.service;

import com.openai.models.models.Model;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.oracle.CheckId;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.util.*;

import static com.strgmai.ace.service.jooq.Tables.CHECK_RESULTS;
import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.JOBS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;

/** Security: files are confined to the run's own results directory (resolve + verified
 *  containment) and workspace/ — the agent's live tree the listing never shows — is never served. */
@RestController
public class BenchController {
    private static final Logger log = LoggerFactory.getLogger(BenchController.class);
    private final DSLContext dsl;
    private final JobQueue queue;
    private final ImporterService importer;
    private final ExperimentsService experiments;
    private final StatsService stats;
    private final WorkerService worker;
    private final BenchProperties props;

    private final Preflight preflight;
    private final TreatmentPin pin;

    public BenchController(DSLContext dsl, JobQueue queue, ImporterService importer, ExperimentsService experiments,
                           StatsService stats, WorkerService worker, BenchProperties props, Preflight preflight,
                           TreatmentPin pin) {
        this.dsl = dsl; this.queue = queue; this.importer = importer; this.experiments = experiments;
        this.stats = stats; this.worker = worker; this.props = props; this.preflight = preflight; this.pin = pin;
    }

    @GetMapping("/api/preflight")
    public Map<String, Object> preflight(@RequestParam(required = false) String model) {
        final Preflight.Report r = preflight.check(model);
        return Map.of("checks", r.checks(), "blocked", r.blocked(), "verdict", r.blocked() ? "BLOCKED" : "runnable");
    }

    @GetMapping("/api/models")
    public List<String> models() {
        return Preflight.modelClient(props).models().list().data().stream().map(Model::id).sorted().toList();
    }

    /** every rung tasks/ladder.json declares - "_doc" is a documentation string, not a rung.
     *  Lets the UI offer every valid --task value, not just ones a run has already used. */
    @GetMapping("/api/tasks")
    public List<String> tasks() throws Exception {
        final var ladderStream = getClass().getResourceAsStream("/tasks/ladder.json");
        if (ladderStream == null) throw new IllegalStateException("ladder.json resource not found on classpath");
        final com.fasterxml.jackson.databind.JsonNode ladder = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ladderStream);
        final List<String> out = new ArrayList<>();
        ladder.fieldNames().forEachRemaining(name -> { if (!"_doc".equals(name)) out.add(name); });
        return out.stream().sorted().toList();
    }

    /** Every rung's own description/budget/denominator/checks, each check resolved to its own
     *  category/weight/description from CheckId (the single source of truth RunOracle.score()
     *  itself reads) - lets the New Job form show what a rung actually tests before enqueuing one,
     *  not just its bare name. Cheap to return all 8 in one call; "all" (L7) resolves to the full
     *  CheckId set the same way RunOracle.score() already does, never leaking the literal string. */
    @GetMapping("/api/tasks/details")
    public List<Map<String, Object>> taskDetails() throws Exception {
        final var ladderStream = getClass().getResourceAsStream("/tasks/ladder.json");
        if (ladderStream == null) throw new IllegalStateException("ladder.json resource not found on classpath");
        final com.fasterxml.jackson.databind.JsonNode ladder = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ladderStream);
        final List<Map<String, Object>> out = new ArrayList<>();
        final var names = new ArrayList<String>();
        ladder.fieldNames().forEachRemaining(name -> { if (!"_doc".equals(name)) names.add(name); });
        for (final String name : names.stream().sorted().toList()) {
            final var rung = ladder.get(name);
            final Set<CheckId> ids = rung.get("checks").isTextual() && rung.get("checks").asText().equals("all")
                    ? EnumSet.allOf(CheckId.class)
                    : java.util.stream.StreamSupport.stream(rung.get("checks").spliterator(), false)
                            .map(c -> CheckId.valueOf(c.asText())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            final List<Map<String, Object>> checks = ids.stream()
                    .map(id -> Map.<String, Object>of("check_id", id.name(), "category", id.category, "weight", id.weight, "description", id.description))
                    .toList();
            out.add(Map.of("name", name, "description", rung.path("description").asText(""),
                    "budget_sec", rung.get("budget_sec").asInt(), "denominator", rung.get("denominator").asInt(), "checks", checks));
        }
        return out;
    }

    @GetMapping("/api/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String task, @RequestParam(required = false) String model,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false, defaultValue = "") String valid,
                                          @RequestParam(required = false, defaultValue = "") String poolable) {
        final List<org.jooq.Condition> where = new ArrayList<>();
        if (task != null && !task.isBlank()) where.add(RUNS.TASK.eq(task));
        if (model != null && !model.isBlank()) where.add(RUNS.MODEL.eq(model));
        if (mode != null && !mode.isBlank()) where.add(RUNS.MODE.eq(mode));
        if (!valid.isBlank()) where.add(RUNS.VALID.eq("true".equals(valid)));
        // #236-adjacent: found in the same audit - the UI's "Poolable" filter (RunsView) has sent
        // this param since it was added, but nothing here ever read it back out of the request
        if (!poolable.isBlank()) where.add(RUNS.POOLABLE.eq("true".equals(poolable)));
        // #201: this explicit column list had fallen behind Api.Run's own DTO - POOLABLE and
        // PARTIAL_SCORE_PCT are real columns real consumers need (CompareView.poolableRunIds(),
        // RunsView's partial-score fallback) but were silently never selected here, so every
        // Api.Run.poolable()/partial_score_pct() off this endpoint deserialized to false/null
        // regardless of the row's actual value.
        return JsonColumns.parseAll(dsl.select(RUNS.RUN_ID, RUNS.TASK, RUNS.MODE, RUNS.MODEL, RUNS.HARNESS,
                        RUNS.FUNCTIONAL_SCORE_PCT, RUNS.WEIGHTED_SCORE_PCT, RUNS.VALID, RUNS.CONTENDED, RUNS.WALL_SEC, RUNS.COMPLETION_TOKENS,
                        RUNS.POOLABLE, RUNS.PARTIAL_SCORE_PCT, RUNS.ORACLE, RUNS.STARTED, RUNS.EARLIER_ATTEMPTS)
                .from(RUNS).where(where).orderBy(RUNS.IMPORTED_AT.desc()).limit(500).fetch().intoMaps())
                .stream().map(BenchController::hoistFunctionalIds).toList();
    }

    @GetMapping("/api/runs/{id}")
    public ResponseEntity<?> run(final @PathVariable String id) {
        final var rec = dsl.selectFrom(RUNS).where(RUNS.RUN_ID.eq(id)).fetchOne();
        if (rec == null) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
        final Map<String, Object> run = hoistFunctionalIds(JsonColumns.parse(rec.intoMap()));
        run.put("checks", dsl.select(CHECK_RESULTS.CHECK_ID, CHECK_RESULTS.CATEGORY, CHECK_RESULTS.WEIGHT, CHECK_RESULTS.STATUS, CHECK_RESULTS.DETAIL, CHECK_RESULTS.DESCRIPTION)
                .from(CHECK_RESULTS).where(CHECK_RESULTS.RUN_ID.eq(id)).orderBy(CHECK_RESULTS.CHECK_ID)
                .fetch().intoMaps().stream().map(JsonColumns::parse).toList());
        return ResponseEntity.ok(run);
    }

    /** #201: Api.Run.functional_ids() has no backing COLUMN at all - "functional_ids" only ever
     *  existed nested inside the oracle JSON blob (oracle.functional_ids), so the UI's flat
     *  accessor was always null on both this endpoint and the list endpoint above. Hoists it to
     *  the top level the same way a real column would appear, rather than a SQL change (there is
     *  no column to add). */
    private static Map<String, Object> hoistFunctionalIds(final Map<String, Object> row) {
        if (row.get("oracle") instanceof com.fasterxml.jackson.databind.JsonNode oracle && oracle.has("functional_ids")) {
            final var ids = new ArrayList<String>();
            oracle.get("functional_ids").forEach(n -> ids.add(n.asText()));
            row.put("functional_ids", ids);
        }
        return row;
    }

    /** files of a run, confined to its results dir; workspace/ is never listed nor served.
     *  {*path} (not {path}) so a nested path like packs/INTEGRATION.md or sessions/foo.jsonl
     *  matches too - a plain {path} only ever captures a single segment, up to the first "/". */
    @GetMapping("/api/runs/{id}/files/{*path}")
    public ResponseEntity<?> file(final @PathVariable String id, final @PathVariable String path) {
        final String cleanPath = path.startsWith("/") ? path.substring(1) : path;   // {*path} keeps the leading "/"
        try {
            final String resultsDir = dsl.select(RUNS.RESULTS_DIR).from(RUNS).where(RUNS.RUN_ID.eq(id)).fetchOne(RUNS.RESULTS_DIR);
            if (resultsDir == null) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
            final var base = Path.of(resultsDir).toRealPath();
            if (cleanPath.equals("workspace") || cleanPath.startsWith("workspace/"))
                return ResponseEntity.status(404).body(Map.of("detail", "workspace is the agent's live tree; not part of the record"));
            final Path target = base.resolve(cleanPath).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target) || !isContainedEvenViaSymlinks(base, target))
                return ResponseEntity.status(404).body(Map.of("detail", "not found"));
            return ResponseEntity.ok().header("Content-Type", "text/plain; charset=utf-8").body(Files.readString(target));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("detail", "not found"));
        }
    }

    @PostMapping("/api/import")
    public Map<String, List<String>> importAll() throws Exception { return importer.importAll(Path.of(props.resultsDir())); }

    @GetMapping("/api/jobs")
    public List<Map<String, Object>> jobs() { return queue.list(); }

    /** Was never wired up despite JobQueue.get() already existing and already 404ing correctly
     *  (EmptyResultDataAccessException, mapped by ApiExceptionHandler) - the Vaadin UI's job detail
     *  page (GET /api/jobs/{id}) had nothing to call. */
    @GetMapping("/api/jobs/{id}")
    public Map<String, Object> job(@PathVariable UUID id) { return queue.get(id); }

    @PostMapping("/api/jobs")
    public Map<String, Object> enqueue(final @RequestBody Map<String, Object> body) {
        final Map<String, Object> spec = (Map<String, Object>) body.get("spec");
        if (spec == null) throw new IllegalArgumentException("missing 'spec' in request body");   // a null spec would NPE on the very next line
        final int priority = body.get("priority") instanceof Number n ? n.intValue() : 0;
        final RunSpec rs = RunSpec.from(spec);
        return queue.enqueue(rs, priority, props.resultsDir(), pin.current(), pin.current(), null, null, null);
    }

    @PostMapping("/api/jobs/{id}/cancel") public Map<String, Object> cancel(@PathVariable UUID id) {
        final Map<String, Object> job = queue.cancel(id);
        // a still-queued job is cancelled outright here (the worker never sees it) - finalizeIfDone
        // otherwise only fires from WorkerService, so cancelling the last pending job of an experiment
        // straight from this endpoint would leave it stuck at "queued" forever (issue #18)
        if (job.get("experiment_id") instanceof UUID experimentId) experiments.finalizeIfDone(experimentId);
        return job;
    }
    @PostMapping("/api/jobs/{id}/requeue") public Map<String, Object> requeue(@PathVariable UUID id) { return queue.requeue(id, props.resultsDir()); }
    @PostMapping("/api/jobs/{id}/priority") public Map<String, Object> priority(final @PathVariable UUID id, final @RequestBody Map<String, Object> body) {
        // a missing or non-numeric priority would NPE/CCE into a 500 on the raw (int) cast
        if (!(body.get("priority") instanceof Number n)) throw new IllegalArgumentException("'priority' must be a number");
        queue.setPriority(id, n.intValue());
        return queue.get(id);
    }

    @GetMapping("/api/experiments") public List<Map<String, Object>> experiments() {
        // newest-first: created_at, not id - a UUID primary key carries no ordering of its own
        return JsonColumns.parseAll(dsl.selectFrom(EXPERIMENTS).orderBy(EXPERIMENTS.CREATED_AT.desc()).fetch().intoMaps());
    }

    @PostMapping("/api/experiments")
    public Map<String, Object> createExperiment(final @RequestBody Map<String, Object> body) {
        final String template = (String) body.getOrDefault("template", "harness_effect");
        final int k = body.get("k") instanceof Number n ? n.intValue() : 3;
        if (k < 1 || k > 20) throw new IllegalArgumentException("k must be 1..20");
        @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        return experiments.enqueue((String) body.getOrDefault("name", template + " " + ExperimentsService.defaultTag()),
                template, params, k, props.resultsDir(), pin.current(), pin.current());
    }

    @GetMapping("/api/experiments/{id}")
    public ResponseEntity<?> experiment(final @PathVariable UUID id) {
        final var rec = dsl.selectFrom(EXPERIMENTS).where(EXPERIMENTS.ID.eq(id)).fetchOne();
        if (rec == null) return ResponseEntity.status(404).body(Map.of("detail", "experiment " + id + " does not exist"));
        final Map<String, Object> out = JsonColumns.parse(rec.intoMap());
        out.put("jobs", dsl.select(JOBS.ID, JOBS.ARM, JOBS.REPEAT, JOBS.RUN_ID, JOBS.STATUS, JOBS.RESULT_LINE)
                .from(JOBS).where(JOBS.EXPERIMENT_ID.eq(id)).orderBy(JOBS.REPEAT, JOBS.ARM).fetch().intoMaps());
        return ResponseEntity.ok(out);
    }

    /** port of compare: the stats output, refusals included */
    @PostMapping("/api/compare")
    public ResponseEntity<?> compare(final @RequestBody Map<String, Object> body) throws Exception {
        final List<String> a = (List<String>) body.get("a"), b = (List<String>) body.get("b");
        // validate BEFORE the loops: a null list NPEs in the for, an empty list later blows up on ra.get(0)
        if (a == null || a.isEmpty() || b == null || b.isEmpty())
            throw new IllegalArgumentException("a and b must be non-empty lists of run ids");
        final String metric = String.valueOf(body.getOrDefault("metric", "functional"));
        final boolean modelAb = Boolean.TRUE.equals(body.get("model_ab"));
        final boolean allowPartial = Boolean.TRUE.equals(body.get("allow_partial"));
        final boolean includeInvalid = Boolean.TRUE.equals(body.get("include_invalid"));
        final boolean allowMismatch = Boolean.TRUE.equals(body.get("allow_budget_mismatch"));
        List<StatsService.RunSummary> ra = new ArrayList<>(), rb = new ArrayList<>();
        for (String id : a) ra.add(stats.load(Path.of(props.resultsDir(), id)));
        for (String id : b) rb.add(stats.load(Path.of(props.resultsDir(), id)));
        try {
            for (String k : StatsService.SHARED_WITH_MODEL_AB) {   // both sides of ANY comparison share task/oracle/contract/prompt
                if (!Objects.equals(ra.get(0).key().get(k), rb.get(0).key().get(k)))
                    return ResponseEntity.ok(Map.of("refused", "A and B ran different tasks/oracles/contracts/prompts: not comparable: " + k));
            }
            ra = stats.filterRuns(ra, allowPartial, includeInvalid, "A");
            rb = stats.filterRuns(rb, allowPartial, includeInvalid, "B");
            if (modelAb) {
                for (String k : StatsService.KEY_FIELDS) {
                    if (StatsService.MODEL_AB_EXEMPT.contains(k)) continue;
                    if (!Objects.equals(ra.get(0).key().get(k), rb.get(0).key().get(k)))
                        return ResponseEntity.ok(Map.of("refused", "model-ab: harness mode/options/budgets must be identical on both sides; differing: " + k));
                }
                final Map<String, Object> setupDiff = new LinkedHashMap<>();
                for (String k : StatsService.MODEL_AB_EXEMPT)
                    setupDiff.put(k, List.of(String.valueOf(ra.get(0).key().get(k)), String.valueOf(rb.get(0).key().get(k))));
                log.info("model-ab compare: setup differences (properties of the setups under test, not of the harness): {}", setupDiff);
            }
            String modelWarning = null;
            if (!modelAb) {
                stats.requireMatchedBudgets(ra.get(0), rb.get(0), allowMismatch);
                final String modelA = ra.get(0).model(), modelB = rb.get(0).model();
                if (!Objects.equals(modelA, modelB)) {
                    log.warn("compare: A ({}) and B ({}) are different models - proceeding without model_ab's harness-identity checks", modelA, modelB);
                    modelWarning = "A (" + modelA + ") and B (" + modelB + ") are different models - interpret this comparison accordingly; pass model_ab for the full harness-identity check instead";
                }
            }
            final List<Double> fa = metricValues(ra, metric), fb = metricValues(rb, metric);
            if (fa.isEmpty() || fb.isEmpty()) return ResponseEntity.ok(Map.of("refused", "no " + metric + " scores to compare on a side"));
            final var result = stats.compare(fa, fb, metric);
            if (modelWarning != null) result.put("model_warning", modelWarning);
            // the success path never wrapped its response in {"result": ..., "printed": ...}
            // (Api.CompareResponse's actual shape, mirroring ExperimentsService.java:448,458's own
            // compare-result wrapping) - it returned the bare stats.compare() map, leaving
            // response.result()/printed() always null on the UI side. Dormant until now because
            // every prior test only exercised the "refused" paths, which happen to deserialize
            // correctly on their own (they set only the "refused" key).
            return ResponseEntity.ok(Map.of("result", result, "printed", StatsService.printCompare(result)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("refused", e.getMessage()));
        }
    }

    static List<Double> metricValues(final List<StatsService.RunSummary> runs, final String metric) {
        return switch (metric) {
            case "score" -> runs.stream().map(StatsService.RunSummary::score).filter(Objects::nonNull).toList();
            case "agent_result" -> runs.stream().map(StatsService.RunSummary::agentResult).filter(Objects::nonNull).toList();
            default -> runs.stream().map(StatsService.RunSummary::functional).filter(Objects::nonNull).toList();
        };
    }

    /** port of list_groups(): poolable runs grouped by (task, model, key_hash), each summarized
     *  fresh from its own results files - matches the Python original and this service's own "the
     *  files are the source of truth" invariant, not the DB's already-cached scores. A leaderboard
     *  entry needs k >= 5 comparable, valid runs; smaller groups are indicative and never ranked. */
    @GetMapping("/api/groups")
    public Map<String, Object> groups() throws Exception {
        final Map<List<Object>, List<Map<String, Object>>> byKey = dsl
                .select(RUNS.RUN_ID, RUNS.TASK, RUNS.MODEL, RUNS.MODE, RUNS.KEY_HASH, RUNS.RESULTS_DIR)
                .from(RUNS).where(RUNS.POOLABLE.isTrue()).orderBy(RUNS.RUN_ID)
                .fetchGroups(r -> List.of(r.get(RUNS.TASK), r.get(RUNS.MODEL), r.get(RUNS.KEY_HASH)), org.jooq.Record::intoMap);

        final List<Map<String, Object>> ranked = new ArrayList<>(), indicative = new ArrayList<>();
        for (var entry : byKey.entrySet()) {
            final List<Map<String, Object>> groupRows = entry.getValue();
            final Map<String, Object> group = new LinkedHashMap<>();
            group.put("task", entry.getKey().get(0));
            group.put("model", entry.getKey().get(1));
            group.put("key_hash", entry.getKey().get(2));
            group.put("mode", groupRows.get(0).get("mode"));
            group.put("run_ids", groupRows.stream().map(r -> r.get("run_id")).toList());
            group.put("summary", Map.of("k", 0));
            group.put("printed", "");
            group.put("refused", null);
            try {
                List<StatsService.RunSummary> summaries = new ArrayList<>();
                for (Map<String, Object> r : groupRows) summaries.add(stats.load(Path.of((String) r.get("results_dir"))));
                summaries = stats.filterRuns(summaries, false, false, group.get("task") + "/" + group.get("model"));
                final var summary = stats.summarize(summaries);
                group.put("summary", summary);
                group.put("printed", StatsService.printSummary(summary));
            } catch (Exception e) {
                group.put("refused", e.getMessage());
            }
            final Object kValue = ((Map<?, ?>) group.get("summary")).get("k");
            final int k = kValue instanceof Number n ? n.intValue() : 0;
            (k >= 5 ? ranked : indicative).add(group);
        }
        ranked.sort(Comparator.comparingDouble(BenchController::functionalMean).reversed());
        return Map.of("ranked", ranked, "indicative", indicative);
    }

    private static double functionalMean(final Map<String, Object> group) {
        if (!(group.get("summary") instanceof Map<?, ?> summary)) return 0;
        if (!(summary.get("functional") instanceof Map<?, ?> functional)) return 0;
        return functional.get("mean") instanceof Number n ? n.doubleValue() : 0;
    }

    /** One bounded, daemon pool for ALL SSE connections: an unbounded raw Thread per client is a
     *  DoS vector, and non-daemon threads block JVM shutdown. A timed-out emitter makes the loop's
     *  next send throw, which releases its worker - the JOB keeps running, only the stream ends. */
    private static final java.util.concurrent.ExecutorService SSE_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(16, r -> {
                final var t = new Thread(r, "job-events");
                t.setDaemon(true);
                return t;
            });

    /** port of the jobs SSE endpoint: live progress from the files the run writes, plus job status */
    @GetMapping("/api/jobs/{id}/events")
    public SseEmitter events(final @PathVariable UUID id) {
        // 30-min idle timeout as a safety net; a healthy stream self-terminates on a terminal status
        final var emitter = new SseEmitter(30 * 60 * 1000L);
        final var tailer = new Tailer();
        SSE_EXECUTOR.submit(() -> {
            try {
                while (true) {
                    final Map<String, Object> job = queue.get(id);
                    for (Map<String, Object> e : tailer.poll(Path.of(props.resultsDir(), (String) job.get("run_id"))))
                        emitter.send(SseEmitter.event().name(String.valueOf(e.get("type"))).data(e));
                    emitter.send(SseEmitter.event().name("status").data(Map.of("status", job.get("status"))));
                    if (RunSpec.TERMINAL.contains(job.get("status"))) { emitter.complete(); return; }
                    Thread.sleep(2000);
                }
            } catch (java.io.IOException e) {
                // a broken pipe from a closed browser tab is normal, not a bug - nothing to log
                emitter.complete();
            } catch (Exception e) {
                // completeWithError (not complete()): JobEventLoop on the client treats a clean
                // stream end as "the job reached a terminal state" - silently calling complete()
                // here on a genuine bug would make the UI think a still-running job had finished
                log.warn("SSE stream for job {} failed unexpectedly", id, e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of("harness_version", BenchProperties.HARNESS_VERSION, "result_schema", BenchProperties.RESULT_SCHEMA,
                "checks", CheckId.values().length, "worker_busy", worker.busyNow());
    }

    /** #85: target.normalize() only collapses ".."/"." lexically - it does not follow symlinks, so a
     *  symlink planted inside the results tree pointing outside `base` would pass a plain
     *  target.startsWith(base) check and still be followed at read time. Re-checks containment
     *  against the REAL (symlink-resolved) path too - defense-in-depth given this threat model
     *  literally involves executing arbitrary LLM-directed shell/file actions elsewhere in the same
     *  run. Only called once `target` is already confirmed to be an existing regular file, so
     *  toRealPath() is safe to call here. */
    static boolean isContainedEvenViaSymlinks(final Path base, final Path target) {
        try { return target.toRealPath().startsWith(base); }
        catch (Exception e) { return false; }
    }
}
