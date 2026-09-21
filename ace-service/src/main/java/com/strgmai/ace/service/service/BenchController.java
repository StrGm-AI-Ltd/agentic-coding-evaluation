package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.oracle.CheckId;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.util.*;

import static com.strgmai.ace.service.jooq.Tables.CHECK_RESULTS;
import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.JOBS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;

/** Port of service/api.py as a JSON API (the HTML UI is not ported; every page has a JSON twin).
 *  Security carries over: files are confined to the run's own results directory (resolve + verified
 *  containment) and workspace/ — the agent's live tree the listing never shows — is never served. */
@RestController
public class BenchController {
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

    /** Whatever the model server currently serves, sorted - the picker source neither this API nor
     *  the Python one's ever exposed; the HTML UI only had it because it renders server-side. Best
     *  effort: an unreachable server means an empty list, not a 500 (same fallback as the queue's own
     *  context-window resolution, which shares this data). */
    @GetMapping("/api/models")
    public List<String> models() {
        return experiments.localModelSpecs().keySet().stream().sorted().toList();
    }

    @GetMapping("/api/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String task, @RequestParam(required = false) String model,
                                          @RequestParam(required = false, defaultValue = "") String valid) {
        final List<org.jooq.Condition> where = new ArrayList<>();
        if (task != null && !task.isBlank()) where.add(RUNS.TASK.eq(task));
        if (model != null && !model.isBlank()) where.add(RUNS.MODEL.eq(model));
        if (!valid.isBlank()) where.add(RUNS.VALID.eq("true".equals(valid)));
        return JsonColumns.parseAll(dsl.select(RUNS.RUN_ID, RUNS.TASK, RUNS.MODE, RUNS.MODEL, RUNS.HARNESS,
                        RUNS.FUNCTIONAL_SCORE_PCT, RUNS.WEIGHTED_SCORE_PCT, RUNS.VALID, RUNS.CONTENDED, RUNS.WALL_SEC, RUNS.COMPLETION_TOKENS)
                .from(RUNS).where(where).orderBy(RUNS.IMPORTED_AT.desc()).limit(500).fetch().intoMaps());
    }

    @GetMapping("/api/runs/{id}")
    public ResponseEntity<?> run(final @PathVariable String id) {
        final var rec = dsl.selectFrom(RUNS).where(RUNS.RUN_ID.eq(id)).fetchOne();
        if (rec == null) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
        final Map<String, Object> run = JsonColumns.parse(rec.intoMap());
        run.put("checks", dsl.select(CHECK_RESULTS.CHECK_ID, CHECK_RESULTS.CATEGORY, CHECK_RESULTS.WEIGHT, CHECK_RESULTS.STATUS, CHECK_RESULTS.DETAIL)
                .from(CHECK_RESULTS).where(CHECK_RESULTS.RUN_ID.eq(id)).orderBy(CHECK_RESULTS.CHECK_ID)
                .fetch().intoMaps().stream().map(JsonColumns::parse).toList());
        return ResponseEntity.ok(run);
    }

    /** files of a run, confined to its results dir; workspace/ is never listed nor served */
    @GetMapping("/api/runs/{id}/files/{path}")
    public ResponseEntity<?> file(final @PathVariable String id, final @PathVariable String path) {
        try {
            final String resultsDir = dsl.select(RUNS.RESULTS_DIR).from(RUNS).where(RUNS.RUN_ID.eq(id)).fetchOne(RUNS.RESULTS_DIR);
            if (resultsDir == null) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
            final var base = Path.of(resultsDir).toRealPath();
            if (path.equals("workspace") || path.startsWith("workspace/"))
                return ResponseEntity.status(404).body(Map.of("detail", "workspace is the agent's live tree; not part of the record"));
            final Path target = base.resolve(path).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target))
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
        RunSpec rs = new RunSpec(str(spec.get("task")), str(spec.get("model")), str(spec.get("harness")), str(spec.get("mode")), str(spec.get("plan_source")),
                spec.get("task_wall") instanceof Number n ? n.intValue() : intOf(spec.get("task_wall")),
                spec.get("task_tokens") instanceof Number n ? n.intValue() : intOf(spec.get("task_tokens")),
                spec.get("impl_wall") instanceof Number n ? n.intValue() : intOf(spec.get("impl_wall")),
                spec.get("impl_tokens") instanceof Number n ? n.intValue() : intOf(spec.get("impl_tokens")),
                str(spec.get("parallel")), Boolean.parseBoolean(String.valueOf(spec.getOrDefault("system_rules", "false"))),
                Boolean.parseBoolean(String.valueOf(spec.getOrDefault("self_review", "false"))),
                Boolean.parseBoolean(String.valueOf(spec.getOrDefault("trajectory_review", "false"))),
                str(spec.get("reviewer_model")),
                Boolean.parseBoolean(String.valueOf(spec.getOrDefault("handoff_notes", "false"))),
                Boolean.parseBoolean(String.valueOf(spec.getOrDefault("manage_docker", "true"))),
                spec.get("context_window") instanceof Number n ? n.intValue() : intOf(spec.get("context_window")),
                str(spec.get("run_id")));
        return queue.enqueue(rs, priority, props.resultsDir(), pin.current(), pin.current(), null, null, null);
    }

    @PostMapping("/api/jobs/{id}/cancel") public Map<String, Object> cancel(@PathVariable UUID id) { return queue.cancel(id); }
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
                System.out.println("  setup differences (properties of the setups under test, not of the harness): " + setupDiff);
            } else {
                stats.requireMatchedBudgets(ra.get(0), rb.get(0), allowMismatch);
                if (!Objects.equals(ra.get(0).model(), rb.get(0).model()))
                    throw new IllegalArgumentException("A and B are different models; pass model_ab for a model comparison (same harness/budgets required)");
            }
            final List<Double> fa = metricValues(ra, metric), fb = metricValues(rb, metric);
            if (fa.isEmpty() || fb.isEmpty()) return ResponseEntity.ok(Map.of("refused", "no " + metric + " scores to compare on a side"));
            return ResponseEntity.ok(stats.compare(fa, fb, metric));
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
        List<Map<String, Object>> rows = dsl.select(RUNS.RUN_ID, RUNS.TASK, RUNS.MODEL, RUNS.MODE, RUNS.KEY_HASH, RUNS.RESULTS_DIR)
                .from(RUNS).where(RUNS.POOLABLE.isTrue()).orderBy(RUNS.RUN_ID).fetch().intoMaps();
        final Map<List<Object>, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
            byKey.computeIfAbsent(List.of(row.get("task"), row.get("model"), row.get("key_hash")), k -> new ArrayList<>()).add(row);

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
                group.put("summary", stats.summarize(summaries));
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
            } catch (Exception e) { emitter.complete(); }
        });
        return emitter;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return Map.of("harness_version", BenchProperties.HARNESS_VERSION, "result_schema", BenchProperties.RESULT_SCHEMA,
                "checks", CheckId.values().length, "worker_busy", worker.busyNow());
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static Integer intOf(Object o) { try { return o == null || String.valueOf(o).isBlank() ? null : Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + o); } }
}
