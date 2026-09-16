package com.agentbench.service;

import com.agentbench.config.BenchProperties;
import com.agentbench.metrics.StatsService;
import com.agentbench.oracle.CheckId;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.util.*;

/** Port of service/api.py as a JSON API (the HTML UI is not ported; every page has a JSON twin).
 *  Security carries over: files are confined to the run's own results directory (resolve + verified
 *  containment) and workspace/ — the agent's live tree the listing never shows — is never served. */
@RestController
public class BenchController {
    private final JdbcTemplate jdbc;
    private final JobQueue queue;
    private final ImporterService importer;
    private final ExperimentsService experiments;
    private final StatsService stats;
    private final WorkerService worker;
    private final BenchProperties props;

    private final Preflight preflight;
    private final TreatmentPin pin;

    public BenchController(JdbcTemplate jdbc, JobQueue queue, ImporterService importer, ExperimentsService experiments,
                           StatsService stats, WorkerService worker, BenchProperties props, Preflight preflight,
                           TreatmentPin pin) {
        this.jdbc = jdbc; this.queue = queue; this.importer = importer; this.experiments = experiments;
        this.stats = stats; this.worker = worker; this.props = props; this.preflight = preflight; this.pin = pin;
    }

    @GetMapping("/api/preflight")
    public Map<String, Object> preflight(@RequestParam(required = false) String model) {
        Preflight.Report r = preflight.check(model);
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
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (task != null && !task.isBlank()) { where.append(" AND task = ?"); args.add(task); }
        if (model != null && !model.isBlank()) { where.append(" AND model = ?"); args.add(model); }
        if (!valid.isBlank()) { where.append(" AND valid = ?"); args.add("true".equals(valid)); }
        return jdbc.queryForList("SELECT run_id, task, mode, model, harness, functional_score_pct, weighted_score_pct, "
                + "valid, contended, wall_sec, completion_tokens FROM runs WHERE true" + where + " ORDER BY imported_at DESC LIMIT 500", args.toArray());
    }

    @GetMapping("/api/runs/{id}")
    public ResponseEntity<?> run(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM runs WHERE run_id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
        Map<String, Object> run = new LinkedHashMap<>(rows.get(0));
        run.put("checks", jdbc.queryForList("SELECT check_id, category, weight, status, detail FROM check_results WHERE run_id = ? "
                + "ORDER BY check_id", id));
        return ResponseEntity.ok(run);
    }

    /** files of a run, confined to its results dir; workspace/ is never listed nor served */
    @GetMapping("/api/runs/{id}/files/{path}")
    public ResponseEntity<?> file(@PathVariable String id, @PathVariable String path) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT results_dir FROM runs WHERE run_id = ?", id);
            if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
            Path base = Path.of((String) rows.get(0).get("results_dir")).toRealPath();
            if (path.equals("workspace") || path.startsWith("workspace/"))
                return ResponseEntity.status(404).body(Map.of("detail", "workspace is the agent's live tree; not part of the record"));
            Path target = base.resolve(path).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target))
                return ResponseEntity.status(404).body(Map.of("detail", "not found"));
            return ResponseEntity.ok().header("Content-Type", "text/plain; charset=utf-8").body(Files.readString(target));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("detail", "not found"));
        }
    }

    @PostMapping("/api/import")
    public Map<String, Integer> importAll() throws Exception { return importer.importAll(Path.of(props.resultsDir())); }

    @GetMapping("/api/jobs")
    public List<Map<String, Object>> jobs() { return queue.list(); }

    @PostMapping("/api/jobs")
    public Map<String, Object> enqueue(@RequestBody Map<String, Object> body) {
        Map<String, Object> spec = (Map<String, Object>) body.get("spec");
        int priority = body.get("priority") instanceof Number n ? n.intValue() : 0;
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

    @PostMapping("/api/jobs/{id}/cancel") public Map<String, Object> cancel(@PathVariable long id) { return queue.cancel(id); }
    @PostMapping("/api/jobs/{id}/requeue") public Map<String, Object> requeue(@PathVariable long id) { return queue.requeue(id, props.resultsDir()); }
    @PostMapping("/api/jobs/{id}/priority") public Map<String, Object> priority(@PathVariable long id, @RequestBody Map<String, Object> body) {
        queue.setPriority(id, (int) body.get("priority"));
        return queue.get(id);
    }

    @GetMapping("/api/experiments") public List<Map<String, Object>> experiments() {
        return jdbc.queryForList("SELECT * FROM experiments ORDER BY id DESC");
    }

    @PostMapping("/api/experiments")
    public Map<String, Object> createExperiment(@RequestBody Map<String, Object> body) {
        String template = (String) body.getOrDefault("template", "harness_effect");
        int k = body.get("k") instanceof Number n ? n.intValue() : 3;
        if (k < 1 || k > 20) throw new IllegalArgumentException("k must be 1..20");
        @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        return experiments.enqueue((String) body.getOrDefault("name", template + " " + ExperimentsService.defaultTag()),
                template, params, k, props.resultsDir(), pin.current(), pin.current());
    }

    @GetMapping("/api/experiments/{id}")
    public ResponseEntity<?> experiment(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM experiments WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("detail", "experiment " + id + " does not exist"));
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("jobs", jdbc.queryForList("SELECT id, arm, repeat, run_id, status, result_line FROM jobs "
                + "WHERE experiment_id = ? ORDER BY repeat, arm", id));
        return ResponseEntity.ok(out);
    }

    /** port of compare: the stats output, refusals included */
    @PostMapping("/api/compare")
    public ResponseEntity<?> compare(@RequestBody Map<String, Object> body) throws Exception {
        List<String> a = (List<String>) body.get("a"), b = (List<String>) body.get("b");
        String metric = String.valueOf(body.getOrDefault("metric", "functional"));
        boolean modelAb = Boolean.TRUE.equals(body.get("model_ab"));
        boolean allowPartial = Boolean.TRUE.equals(body.get("allow_partial"));
        boolean includeInvalid = Boolean.TRUE.equals(body.get("include_invalid"));
        boolean allowMismatch = Boolean.TRUE.equals(body.get("allow_budget_mismatch"));
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
                Map<String, Object> setupDiff = new LinkedHashMap<>();
                for (String k : StatsService.MODEL_AB_EXEMPT)
                    setupDiff.put(k, List.of(String.valueOf(ra.get(0).key().get(k)), String.valueOf(rb.get(0).key().get(k))));
                System.out.println("  setup differences (properties of the setups under test, not of the harness): " + setupDiff);
            } else {
                stats.requireMatchedBudgets(ra.get(0), rb.get(0), allowMismatch);
                if (!Objects.equals(ra.get(0).model(), rb.get(0).model()))
                    throw new IllegalArgumentException("A and B are different models; pass model_ab for a model comparison (same harness/budgets required)");
            }
            List<Double> fa = metricValues(ra, metric), fb = metricValues(rb, metric);
            if (fa.isEmpty() || fb.isEmpty()) return ResponseEntity.ok(Map.of("refused", "no " + metric + " scores to compare on a side"));
            return ResponseEntity.ok(stats.compare(fa, fb, metric));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("refused", e.getMessage()));
        }
    }

    static List<Double> metricValues(List<StatsService.RunSummary> runs, String metric) {
        return switch (metric) {
            case "score" -> runs.stream().map(StatsService.RunSummary::score).filter(Objects::nonNull).toList();
            case "agent_result" -> runs.stream().map(StatsService.RunSummary::agentResult).filter(Objects::nonNull).toList();
            default -> runs.stream().map(StatsService.RunSummary::functional).filter(Objects::nonNull).toList();
        };
    }

    /** port of the jobs SSE endpoint: live progress from the files the run writes, plus job status */
    @GetMapping("/api/jobs/{id}/events")
    public SseEmitter events(@PathVariable long id) {
        SseEmitter emitter = new SseEmitter(0L);
        Tailer tailer = new Tailer();
        new Thread(() -> {
            try {
                while (true) {
                    Map<String, Object> job = queue.get(id);
                    for (Map<String, Object> e : tailer.poll(Path.of(props.resultsDir(), (String) job.get("run_id"))))
                        emitter.send(SseEmitter.event().name(String.valueOf(e.get("type"))).data(e));
                    emitter.send(SseEmitter.event().name("status").data(Map.of("status", job.get("status"))));
                    if (RunSpec.TERMINAL.contains(job.get("status"))) { emitter.complete(); return; }
                    Thread.sleep(2000);
                }
            } catch (Exception e) { emitter.complete(); }
        }, "job-events-" + id).start();
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
