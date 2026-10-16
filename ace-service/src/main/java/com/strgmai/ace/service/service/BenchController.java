package com.strgmai.ace.service.service;

import com.openai.models.models.Model;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.oracle.CheckId;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.plan.PlanParser;
import com.strgmai.ace.service.plan.PlanTask;
import com.strgmai.ace.service.runner.Intervene;
import com.strgmai.ace.service.runner.TaskMatcher;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import static com.strgmai.ace.service.jooq.Tables.CHECK_RESULTS;
import static com.strgmai.ace.service.jooq.Tables.EXPERIMENTS;
import static com.strgmai.ace.service.jooq.Tables.JOBS;
import static com.strgmai.ace.service.jooq.Tables.RUNS;

/** Security: files are confined to the run's own results directory (resolve + verified
 *  containment); workspace/ — the agent's live tree — is never served by raw path. taskDiff()
 *  below is the one narrow exception: it only ever runs fixed git subcommands against a task's
 *  own ref (or a path git itself already enumerated as untracked), never an arbitrary
 *  caller-supplied filesystem path into workspace/. */
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
    private final Intervene intervene;
    private final TaskMatcher taskMatcher;

    public BenchController(DSLContext dsl, JobQueue queue, ImporterService importer, ExperimentsService experiments,
                           StatsService stats, WorkerService worker, BenchProperties props, Preflight preflight,
                           TreatmentPin pin, Intervene intervene, TaskMatcher taskMatcher) {
        this.dsl = dsl; this.queue = queue; this.importer = importer; this.experiments = experiments;
        this.stats = stats; this.worker = worker; this.props = props; this.preflight = preflight; this.pin = pin;
        this.intervene = intervene; this.taskMatcher = taskMatcher;
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

    /** #270: the file LIST for a run - top-level files plus one level of subdirs, excluding
     *  workspace/ (mirrors FilesBrowser's own previous listFiles()/sizeBytes(), which read the
     *  filesystem directly from the ace-ui-vaadin process, assuming it is colocated with
     *  ace-service on the same host/mount - not guaranteed, per this project's own README, which
     *  explicitly describes a UI-only, non-colocated ace-service as a supported setup). Declared
     *  before the file-serving {*path} mapping below so a bare .../files request (no path segment)
     *  resolves here, not there. */
    @GetMapping("/api/runs/{id}/files")
    public ResponseEntity<?> files(final @PathVariable String id) {
        final String resultsDir = dsl.select(RUNS.RESULTS_DIR).from(RUNS).where(RUNS.RUN_ID.eq(id)).fetchOne(RUNS.RESULTS_DIR);
        if (resultsDir == null) return ResponseEntity.status(404).body(Map.of("detail", "run " + id + " is not imported"));
        try {
            final var base = Path.of(resultsDir).toRealPath();
            final List<Map<String, Object>> out = new ArrayList<>();
            try (var top = Files.list(base)) {
                for (final var path : top.sorted().toList()) {
                    final var name = path.getFileName().toString();
                    if (Files.isRegularFile(path) && isContainedEvenViaSymlinks(base, path)) {
                        out.add(fileEntry(name, path));
                    } else if (Files.isDirectory(path) && !"workspace".equals(name)) {
                        try (var inner = Files.list(path)) {
                            for (final var child : inner.sorted().filter(Files::isRegularFile).toList())
                                if (isContainedEvenViaSymlinks(base, child)) out.add(fileEntry(name + "/" + child.getFileName(), child));
                        }
                    }
                }
            }
            return ResponseEntity.ok(out);
        } catch (final Exception e) {
            return ResponseEntity.status(404).body(Map.of("detail", "not found"));
        }
    }

    private static Map<String, Object> fileEntry(final String relativePath, final Path absolute) {
        long size;
        try { size = Files.size(absolute); }
        catch (final java.io.IOException e) { size = -1; }   // mirrors FilesBrowser's own "–" on a stat failure
        return Map.of("path", relativePath, "size", size);
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

    /** the plan's own task list (id/title/goal/deps), in planned execution order - keyed by job id,
     *  not run id, so it works on a still-running job (just reads a results-dir file directly; no
     *  import/manifest needed, unlike /api/runs/{id}). Empty "tasks" before p1_plan has produced a
     *  plan at all (monolithic mode, or an orchestrated run that hasn't reached p1 yet).
     *
     *  #254, found live: "implemented"/"handoff_done" come from the workspace's own R18 git tags
     *  (phase/{id}, phase/{id}-handoff), not from live SSE session events - a task completed in a
     *  PRIOR attempt (resumed straight past, per R18) never produces a session_started/session_done
     *  event in THIS page's live view at all, so the UI's own session-derived status alone always
     *  read a genuinely-finished task as "not started". The git tags are the real, resume-independent
     *  source of truth for "is this task actually done" - the UI still prefers live session data when
     *  it has it (for a real-time "running" state), falling back to these only when it doesn't.
     *
     *  #261: "implemented"/"handoff_done" alone only say a task's SESSION ran to completion, not
     *  whether it actually succeeded - a budget-exhausted session is just as "implemented" as a
     *  genuinely finished one. reported_status carries the task's own self-reported outcome
     *  (done/partial/blocked), or the harness's "unfinished" fallback when even its dedicated
     *  wrap-up chance came back empty - null until the task's main session has actually ended. */
    @GetMapping("/api/jobs/{id}/plan")
    public Map<String, Object> plan(@PathVariable UUID id) {
        final Map<String, Object> job = queue.get(id);
        final List<PlanTask> tasks;
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path planFile = base.resolve("IMPLEMENTATION_PLAN.md");
            if (!Files.isRegularFile(planFile) || !isContainedEvenViaSymlinks(base, planFile)) return Map.of("tasks", List.of());
            tasks = PlanParser.parseFile(planFile);
        } catch (final com.strgmai.ace.service.plan.PlanError e) { return Map.of("tasks", List.of(), "error", e.toString()); }
        catch (final Exception e) { return Map.of("tasks", List.of()); }
        final Path ws = Path.of(props.workspaceRoot(), String.valueOf(job.get("run_id")), "workspace");
        final Map<String, List<Map<String, Object>>> attemptsByTask = taskAttemptsByTaskId(job);
        final List<Map<String, Object>> rows = new ArrayList<>();
        int order = 0;
        for (final List<PlanTask> wave : PlanParser.waves(tasks))
            for (final PlanTask t : wave) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("order", order++);
                row.put("id", t.id);
                row.put("title", t.title == null ? "" : t.title);
                row.put("goal", t.goal == null ? "" : t.goal);
                row.put("deps", t.deps == null ? List.of() : t.deps);
                row.put("implemented", com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/" + t.id));
                row.put("handoff_done", com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/" + t.id + "-handoff"));
                // #261/#262: the agent's own self-reported outcome (done/partial/blocked) and WHY
                // (how it was verified, what's missing, why it's blocked), or the harness's
                // "unfinished" fallback (with its own fixed explanation) when even a dedicated
                // wrap-up chance came back empty - both null until the task's main session
                // actually ends. See RunBench.reportedStatus().
                final var reported = com.strgmai.ace.service.runner.RunBench.reportedStatus(ws, t.id);
                row.put("reported_status", reported == null ? null : reported.status());
                row.put("reported_detail", reported == null ? null : reported.detail());
                // #337: visible effort signal - how many attempts this task's own task_attempts.jsonl
                // history actually shows, and whether any of them needed a same-session rescue, so a
                // clean first-try pass and a costly rescued/retried one read as different outcomes.
                final List<Map<String, Object>> taskAttempts = attemptsByTask.getOrDefault(t.id, List.of());
                row.put("attempts", taskAttempts.size());
                row.put("rescued", taskAttempts.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("rescue_succeeded"))));
                rows.add(row);
            }
        return Map.of("tasks", rows);
    }

    /** #337: task_attempts.jsonl grouped by task_id, read once per plan() call rather than once per
     *  task (same file taskAttempts() below reads per-task, on demand) - empty map on any read
     *  failure, same as every other best-effort fallback in this method. */
    private Map<String, List<Map<String, Object>>> taskAttemptsByTaskId(final Map<String, Object> job) {
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path file = base.resolve("task_attempts.jsonl");
            if (!Files.isRegularFile(file) || !isContainedEvenViaSymlinks(base, file)) return Map.of();
            final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            final Map<String, List<Map<String, Object>>> byTask = new LinkedHashMap<>();
            for (final String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                final Map<String, Object> entry = mapper.readValue(line, Map.class);
                byTask.computeIfAbsent(String.valueOf(entry.get("task_id")), k -> new ArrayList<>()).add(entry);
            }
            return byTask;
        } catch (final Exception e) { return Map.of(); }
    }

    /** the plan's task ids in execution (wave) order - same parse plan() above already does,
     *  extracted so restartTask() can compute "every task at or after this one" the same way. */
    private List<String> orderedTaskIds(final Path base) throws Exception {
        final Path planFile = base.resolve("IMPLEMENTATION_PLAN.md");
        if (!Files.isRegularFile(planFile) || !isContainedEvenViaSymlinks(base, planFile)) return List.of();
        final List<String> ids = new ArrayList<>();
        for (final List<PlanTask> wave : PlanParser.waves(PlanParser.parseFile(planFile)))
            for (final PlanTask t : wave) ids.add(t.id);
        return ids;
    }

    // #265: judge_* only ever takes effect for a TASK restart - RunBench.buildJudgeCheckpoint()
    // reads them via taskOverride(cfg, t.id), which is only ever called keyed by a real task id,
    // so they naturally no-op if ever sent on a phase-session restart (same "lookup is a no-op for
    // a name that was never written" principle every other override field here already relies on).
    // #307: budget_rescue_enabled is the rescue step's own equivalent - RunBench.rescueStep() reads
    // it via effectiveRescueCfg(cfg, taskOverride(cfg, t.id)), same no-op-on-phase-restart reasoning.
    // #322: compaction_trigger/max_tokens/temperature/top_p/top_k/repetition_penalty/
    // reasoning_effort are the per-call session-shaping knobs - RunBench.effectiveCompactionTrigger()/
    // effectiveSamplerOverrides() read them the same way, at every call site for this task's own
    // session (main, its continuations, its handoff, its rescue). rescue_wall_sec/rescue_tokens/
    // rescue_keep_recent_turns were deliberately operator-wide-only before (RunBench.
    // effectiveRescueCfg()'s own history) - lifted for the same reason budget_rescue_enabled itself
    // is overridable: a task that already needed rescuing once should be able to get a bigger
    // rescue attempt next time, not just a bigger main-session budget. Deliberately NOT here:
    // model/harness/mode/plan_source/parallel*/context_window/java_home/run_id/phases and the
    // self-review/trajectory-review family - these are run-wide identity/infrastructure/lifecycle
    // choices with no per-task session to attach to; nothing reads a task_override for them, so
    // allowing them here would silently no-op rather than do anything.
    private static final Set<String> RESTART_OVERRIDE_KEYS = Set.of(
            "task_wall_sec", "task_tokens", "max_turns", "wrapup_wall_sec", "wrapup_tokens", "first_token_timeout_sec",
            "judge_enabled", "judge_every_n_turns", "judge_model", "budget_rescue_enabled",
            "compaction_trigger", "max_tokens", "temperature", "top_p", "top_k", "repetition_penalty", "reasoning_effort",
            "rescue_wall_sec", "rescue_tokens", "rescue_keep_recent_turns");

    /** the three pre-task phases that can be inspected/restarted via /sessions/{name}/... - an
     *  explicit, small map because the name-to-tag relationship is irregular (found live): p0/p1
     *  truncate ("p0_definition"->"phase/p0"), PARALLEL_PLAN case-folds ("PARALLEL_PLAN"->
     *  "phase/parallel_plan") - neither is a generic rule, unlike task ids/"-handoff" (identity). */
    private static final Map<String, String> PHASE_TAGS = Map.of(
            "p0_definition", "phase/p0", "p1_plan", "phase/p1", "PARALLEL_PLAN", "phase/parallel_plan");
    private static final List<String> PHASE_ORDER = List.of("p0_definition", "p1_plan", "PARALLEL_PLAN");

    /** #263: prepares a PAUSED job to redo taskId - and, since later tasks may depend on what it
     *  was supposed to deliver, every task after it in plan order too - on its next resume. Does
     *  NOT itself resume the job: the caller still POSTs /requeue afterward, same split as
     *  pause()/requeue() already have. A backup tag is created before anything else is touched, so
     *  the discarded state stays recoverable (git never garbage-collects a committed tree while a
     *  tag still points at it) - the tag is never deleted by this flow and isn't surfaced in the
     *  UI, but a plain `git tag -l 'backup/*'` in the workspace finds it. */
    @PostMapping("/api/jobs/{id}/tasks/{taskId}/restart")
    public Map<String, Object> restartTask(@PathVariable UUID id, @PathVariable String taskId,
                                           @RequestBody(required = false) Map<String, Object> body) throws Exception {
        final Map<String, Object> job = queue.get(id);
        return doRestart(id, job, taskId, "phase/" + taskId, () -> {
            try {
                final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
                final List<String> orderedIds = orderedTaskIds(base);
                final int fromIndex = orderedIds.indexOf(taskId);
                final List<String> cascadeIds = fromIndex < 0 ? List.of(taskId) : orderedIds.subList(fromIndex, orderedIds.size());
                return cascadeIds.stream().flatMap(cid -> java.util.stream.Stream.of("phase/" + cid, "phase/" + cid + "-handoff")).toList();
            } catch (final Exception e) { throw new RuntimeException(e); }
        }, body);
    }

    /** #264: the phase-session counterpart of restartTask() - Definition/Plan/Parallel plan have
     *  no task id, but the same backup/reset/cascade-delete flow applies, using PHASE_TAGS to
     *  resolve the tag and PHASE_ORDER to know what's downstream. Restarting p0_definition
     *  invalidates p1_plan, PARALLEL_PLAN, and every task; p1_plan invalidates PARALLEL_PLAN and
     *  every task (keeps p0); PARALLEL_PLAN invalidates every task only (keeps p0 and p1) -
     *  conservative on purpose, matching the task cascade's own "anything downstream is
     *  invalidated" precedent rather than trying to partially invalidate. */
    @PostMapping("/api/jobs/{id}/sessions/{name}/restart")
    public Map<String, Object> restartSession(@PathVariable UUID id, @PathVariable String name,
                                              @RequestBody(required = false) Map<String, Object> body) throws Exception {
        if (!PHASE_TAGS.containsKey(name))
            throw new IllegalArgumentException("not a restartable phase session: " + name + "; allowed: " + PHASE_TAGS.keySet());
        final Map<String, Object> job = queue.get(id);
        return doRestart(id, job, name, PHASE_TAGS.get(name), () -> {
            try {
                final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
                final int idx = PHASE_ORDER.indexOf(name);
                final List<String> tagsToDelete = new ArrayList<>();
                for (final String laterPhase : PHASE_ORDER.subList(idx, PHASE_ORDER.size())) tagsToDelete.add(PHASE_TAGS.get(laterPhase));
                for (final String taskId : orderedTaskIds(base))
                    tagsToDelete.addAll(List.of("phase/" + taskId, "phase/" + taskId + "-handoff"));
                return tagsToDelete;
            } catch (final Exception e) { throw new RuntimeException(e); }
        }, body);
    }

    private Map<String, Object> doRestart(final UUID id, final Map<String, Object> job, final String overrideKey,
                                          final String ownTag, final java.util.function.Supplier<List<String>> tagsToDeleteSupplier,
                                          final Map<String, Object> body) throws Exception {
        if (!"paused".equals(job.get("status")))
            throw new IllegalStateException("job " + id + " is " + job.get("status") + "; only a paused job can be restarted");
        final Path ws = Path.of(props.workspaceRoot(), String.valueOf(job.get("run_id")), "workspace");
        if (!com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, ownTag))
            throw new IllegalStateException(overrideKey + " has no " + ownTag + " tag yet - nothing to restart");
        final Object rawOverrides = body == null ? null : body.get("overrides");
        final Map<String, Object> overrides = rawOverrides instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
        final var unknown = overrides.keySet().stream().filter(k -> !RESTART_OVERRIDE_KEYS.contains(k)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown override field(s): " + unknown + "; allowed: " + RESTART_OVERRIDE_KEYS);

        com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "backup/" + overrideKey + "-" + (System.currentTimeMillis() / 1000), "HEAD");
        final String before = com.strgmai.ace.service.runner.RunBenchSupport.nearestPhaseTagSha(ws, ownTag + "^");
        com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "reset", "--hard", before);

        for (final String tag : tagsToDeleteSupplier.get())
            if (com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, tag))
                com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "-d", tag);

        return queue.setTaskOverride(id, overrideKey, overrides);
    }

    /** #321: unlike restartTask() above, this is for a task that has NOT run yet - no phase/{id}
     *  tag exists to reset to, and there is nothing to discard, so none of doRestart()'s git
     *  machinery (backup tag, reset, cascade tag deletion) applies. Kept as its own endpoint rather
     *  than a loosened precondition on /restart: that flow's tag-existence check is load-bearing
     *  for its reset logic, not incidental, so the two stay non-overlapping - an already-run task
     *  is told to use /restart instead. Still requires a paused job for the same reason /restart
     *  does: WorkerService.java builds `cfg` (and so `task_overrides`) once when a job is claimed,
     *  so a write made while the job is actively running would not take effect until the NEXT
     *  invocation anyway - requiring "paused" avoids an operator believing it applied immediately. */
    @PostMapping("/api/jobs/{id}/tasks/{taskId}/override")
    public Map<String, Object> setPendingTaskOverride(@PathVariable UUID id, @PathVariable String taskId,
                                                       @RequestBody(required = false) Map<String, Object> body) throws Exception {
        final Map<String, Object> job = queue.get(id);
        if (!"paused".equals(job.get("status")))
            throw new IllegalStateException("job " + id + " is " + job.get("status") + "; only a paused job can have a pending task override set");
        final Path ws = Path.of(props.workspaceRoot(), String.valueOf(job.get("run_id")), "workspace");
        if (com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, "phase/" + taskId))
            throw new IllegalStateException(taskId + " has already run - use /restart instead to redo it with new settings");
        final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
        if (!orderedTaskIds(base).contains(taskId))
            throw new IllegalArgumentException("unknown task id: " + taskId);
        final Object rawOverrides = body == null ? null : body.get("overrides");
        final Map<String, Object> overrides = rawOverrides instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
        final var unknown = overrides.keySet().stream().filter(k -> !RESTART_OVERRIDE_KEYS.contains(k)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown override field(s): " + unknown + "; allowed: " + RESTART_OVERRIDE_KEYS);
        return queue.setTaskOverride(id, taskId, overrides);
    }

    /** #263: this task's own attempt history (every run, not just a restarted/overridden one -
     *  see RunBench.logTaskAttempt()), newest first - the params used and the outcome, so deciding
     *  what to adjust before a restart doesn't need manual interactions.jsonl archaeology. */
    @GetMapping("/api/jobs/{id}/tasks/{taskId}/attempts")
    public List<Map<String, Object>> taskAttempts(@PathVariable UUID id, @PathVariable String taskId) {
        final Map<String, Object> job = queue.get(id);
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path file = base.resolve("task_attempts.jsonl");
            if (!Files.isRegularFile(file) || !isContainedEvenViaSymlinks(base, file)) return List.of();
            final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            final List<Map<String, Object>> all = new ArrayList<>();
            for (final String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                final Map<String, Object> entry = mapper.readValue(line, Map.class);
                if (taskId.equals(entry.get("task_id"))) all.add(entry);
            }
            Collections.reverse(all);
            return all;
        } catch (final Exception e) { return List.of(); }
    }

    /** #355-followup: turns/compactions/tool_errors must be LIVE - calculated from the task's own
     *  session file on every call, not trusted from manifest.json's one-time snapshot. Two
     *  concrete reasons found live: (1) manifest.tasks' own entry doesn't exist at all until the
     *  task finishes, so a still-running task had nothing to show here before; (2) even once
     *  written, that snapshot can be flat wrong - #358 found manifest.tasks reporting turns=0/
     *  compactions=0/tool_errors=0 for a task (T7) that had genuinely run 142 turns/51
     *  compactions/6 tool errors, because a timed-out attempt's result is partly SYNTHETIC
     *  (Future.cancel(true) never hands back the interrupted thread's real final state - see
     *  RunBenchSupport.runBounded's own fix for the manifest-write side of this). Reading the
     *  session file directly - the same technique Collect.compactionOverhead() (#354) and this
     *  method's own prior, narrower form already used - sidesteps both problems at once: it is
     *  unconditionally the task's own ground truth, whether the task is still running, just
     *  finished, or finished a while ago. found=false means no session file for this task exists
     *  AT ALL yet (genuinely not started); found=true with every count still 0 is a real, distinct
     *  state (started, nothing logged yet) - never confused with "not started". */
    record SessionCounts(boolean found, int turns, int compactions, int toolErrorsTotal, Map<String, Object> toolCalls) {
        static final SessionCounts NOT_FOUND = new SessionCounts(false, 0, 0, 0, Map.of());
    }

    /** #355: this task's own tool-call breakdown - counts (and each tool's own error count)
     *  tallied from its session file's own "toolResult" messages (AgentSession.toolResult() -
     *  one per actual tool invocation, already carrying toolName/isError directly, no need to
     *  parse the assistant's own "toolCall" request parts instead) - alongside turns ("message"
     *  entries with role "assistant") and compactions ("compaction" type entries), all three
     *  tallied in the SAME single pass over the file rather than three separate reads. The
     *  session file is found by its JSONL header's "label" field (written once, on the task's
     *  first - non-continued - session; a later wrap-up/rescue continuation reuses the SAME file
     *  and never rewrites the header), same technique Collect.compactionOverhead() (#354) already
     *  uses from a different package, rather than recomputing RunBench's own session-id UUID
     *  scheme here too. Best-effort: a missing/unreadable session reads as SessionCounts.NOT_FOUND,
     *  never throws. */
    private SessionCounts sessionCounts(final Path base, final String taskId) {
        final Path dir = base.resolve("sessions");
        if (!Files.isDirectory(dir)) return SessionCounts.NOT_FOUND;
        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path latest = null;
        java.nio.file.attribute.FileTime latestMtime = null;
        try (var stream = Files.list(dir)) {
            for (final Path f : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                final String firstLine;
                try (var lines = Files.lines(f)) { firstLine = lines.findFirst().orElse(null); } catch (final Exception ignore) { continue; }
                if (firstLine == null) continue;
                final Map<String, Object> header;
                try { header = mapper.readValue(firstLine, Map.class); } catch (final Exception ignore) { continue; }
                if (!taskId.equals(header.get("label"))) continue;
                final java.nio.file.attribute.FileTime mtime;
                try { mtime = Files.getLastModifiedTime(f); } catch (final Exception ignore) { continue; }
                if (latestMtime == null || mtime.compareTo(latestMtime) > 0) { latest = f; latestMtime = mtime; }
            }
        } catch (final Exception e) { return SessionCounts.NOT_FOUND; }
        if (latest == null) return SessionCounts.NOT_FOUND;
        final List<String> lines;
        try { lines = Files.readAllLines(latest); } catch (final Exception e) { return SessionCounts.NOT_FOUND; }
        int turns = 0, compactions = 0, toolErrorsTotal = 0;
        final Map<String, int[]> toolCounts = new LinkedHashMap<>();   // toolName -> [total, errors]
        for (final String line : lines) {
            if (line.isBlank()) continue;
            final Map<String, Object> e;
            try { e = mapper.readValue(line, Map.class); } catch (final Exception ignore) { continue; }
            final String type = String.valueOf(e.get("type"));
            if ("compaction".equals(type)) { compactions++; continue; }
            if (!"message".equals(type) || !(e.get("message") instanceof Map<?, ?> m)) continue;
            final Object role = m.get("role");
            if ("assistant".equals(role)) turns++;
            else if ("toolResult".equals(role)) {
                final int[] c = toolCounts.computeIfAbsent(String.valueOf(m.get("toolName")), k -> new int[2]);
                c[0]++;
                if (Boolean.TRUE.equals(m.get("isError"))) { c[1]++; toolErrorsTotal++; }
            }
        }
        final Map<String, Object> toolCallsOut = new LinkedHashMap<>();
        toolCounts.forEach((name, c) -> toolCallsOut.put(name, Map.of("count", c[0], "errors", c[1])));
        return new SessionCounts(true, turns, compactions, toolErrorsTotal, toolCallsOut);
    }

    /** #355/#355-followup: the task detail dialog's own Stats tab. Three states: "done"
     *  (manifest.tasks has this task's own completed record - RunBench.snapshotRunProgress()
     *  writes it incrementally after EVERY task, not just at the end of the run - carrying rc,
     *  seconds, finish, verification, done_verified, reported, the wrapup_, rescue_ and handoff_
     *  prefixed fields, requests, and the budget flags, with zero new computation for those); "running"
     *  (no manifest entry yet, but a session file for this task already exists - e.g. still
     *  mid-session, or already past it but still in its own wrap-up/rescue/handoff tail, which
     *  run AFTER the main session ends but BEFORE the task's manifest.tasks entry is written);
     *  "not_started" (neither exists yet). turns/compactions/tool_errors/tool_calls are ALWAYS
     *  read live from the session file via sessionCounts() regardless of state - see its own
     *  javadoc for why trusting manifest.tasks' own copies of these three fields is wrong, not
     *  just stale. oracle.json/metrics.json only exist once the WHOLE run finishes (not per
     *  task) - when they do (and only once "done"), this also merges in this task's own
     *  check-score attribution (metrics.json's own "steps" block, by task id) and compaction
     *  overhead (#353/#354's "compaction_overhead.tasks", also by task id). */
    @GetMapping("/api/jobs/{id}/tasks/{taskId}/stats")
    public Map<String, Object> taskStats(@PathVariable UUID id, @PathVariable String taskId) {
        final Map<String, Object> job = queue.get(id);
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> entry = null;
            final Path manifestFile = base.resolve("manifest.json");
            if (Files.isRegularFile(manifestFile) && isContainedEvenViaSymlinks(base, manifestFile)) {
                final Map<String, Object> manifest = mapper.readValue(manifestFile.toFile(), Map.class);
                for (final Object o : (List<?>) manifest.getOrDefault("tasks", List.of()))
                    if (o instanceof Map<?, ?> t && taskId.equals(t.get("id"))) entry = (Map<String, Object>) t;
            }
            final SessionCounts live = sessionCounts(base, taskId);
            if (entry == null && !live.found()) return Map.of("state", "not_started");
            final Map<String, Object> out = entry == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry);
            out.put("state", entry == null ? "running" : "done");
            // overrides whatever entry may have carried for these three keys - never the stale/
            // synthetic manifest.json snapshot, always this call's own fresh read
            out.put("turns", live.turns());
            out.put("compactions", live.compactions());
            out.put("tool_errors", live.toolErrorsTotal());
            out.put("tool_calls", live.toolCalls());
            if (entry != null) {
                final Path metricsFile = base.resolve("metrics.json");
                if (Files.isRegularFile(metricsFile) && isContainedEvenViaSymlinks(base, metricsFile)) {
                    final Map<String, Object> metrics = mapper.readValue(metricsFile.toFile(), Map.class);
                    if (metrics.get("steps") instanceof Map<?, ?> steps && steps.get(taskId) != null) out.put("check_score", steps.get(taskId));
                    if (metrics.get("compaction_overhead") instanceof Map<?, ?> co && co.get("tasks") instanceof Map<?, ?> tasks && tasks.get(taskId) != null)
                        out.put("compaction_overhead", tasks.get(taskId));
                }
            }
            return out;
        } catch (final Exception e) {
            log.warn("could not read stats for job {} task {}: {}", id, taskId, e.toString());
            return Map.of("state", "not_started");
        }
    }

    /** #265: this task's own mid-task judge checkpoints (see Judge.java) - one per turn the judge
     *  fired, each file holding the exact prompt it saw and its recommendation. Turn order
     *  (ascending), unlike attempts/history above: these read as a timeline of one task's own
     *  progression, not a list of outcomes to compare. */
    /** #307-adjacent, found live: judge/&lt;taskId&gt;[-&lt;attemptStamp&gt;]-turn&lt;N&gt;.md - the attempt
     *  stamp (Judge.attemptStamp(), absent on files written before this fix existed) disambiguates
     *  a restarted task's own checkpoints from an earlier, now-abandoned attempt's: the SAME turn
     *  numbers would otherwise collide/coexist with no way to tell which attempt produced either
     *  one, mixing stale and current comments on the same page with no visual distinction. Only
     *  the LATEST attempt (by stamp - sorts correctly as a plain string, same convention #305
     *  established) is ever shown; "" (the legacy, unstamped shape) sorts before any real
     *  timestamp, so a task restarted after this fix shipped still correctly prefers its new,
     *  stamped checkpoints over whatever pre-existing legacy ones happen to still be on disk. */
    @GetMapping("/api/jobs/{id}/tasks/{taskId}/judge")
    public List<Map<String, Object>> taskJudge(@PathVariable UUID id, @PathVariable String taskId) {
        final Map<String, Object> job = queue.get(id);
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path dir = base.resolve("judge");
            if (!Files.isDirectory(dir) || !isContainedEvenViaSymlinks(base, dir)) return List.of();
            final Pattern pattern = Pattern.compile(Pattern.quote(taskId) + "-(?:(.+)-)?turn(\\d+)\\.md");
            final Map<String, List<Path>> byAttempt = new LinkedHashMap<>();
            try (var stream = Files.list(dir)) {
                for (final Path f : stream.toList()) {
                    final var m = pattern.matcher(f.getFileName().toString());
                    if (!m.matches()) continue;
                    byAttempt.computeIfAbsent(m.group(1) == null ? "" : m.group(1), k -> new ArrayList<>()).add(f);
                }
            }
            if (byAttempt.isEmpty()) return List.of();
            final String latest = byAttempt.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
            final List<Map<String, Object>> out = new ArrayList<>();
            for (final Path f : byAttempt.get(latest)) {
                final var m = pattern.matcher(f.getFileName().toString());
                m.matches();
                final int turn = Integer.parseInt(m.group(2));
                final String content = Files.readString(f);
                final int promptIdx = content.indexOf("## Prompt");
                final int recIdx = content.indexOf("## Recommendation");
                final String prompt = promptIdx >= 0 && recIdx > promptIdx
                        ? content.substring(promptIdx + "## Prompt".length(), recIdx).strip() : "";
                final String recommendation = recIdx >= 0 ? content.substring(recIdx + "## Recommendation".length()).strip() : content.strip();
                out.add(Map.of("turn", turn, "prompt", prompt, "recommendation", recommendation));
            }
            out.sort(Comparator.comparingInt(m -> (Integer) m.get("turn")));
            return out;
        } catch (final Exception e) { return List.of(); }
    }

    /** An operator's own mid-task steering message - delivered through the exact same per-turn
     *  hook Judge's own recommendations already use (see RunBench.buildJudgeCheckpoint()), just
     *  operator-triggered instead of LLM-triggered, and independent of whether this task's own
     *  judge is enabled. Queued, not applied synchronously: it reaches the agent at its own next
     *  turn boundary, whenever that is - delivery (and the resulting durable log entry in the
     *  task's own session) happens over there, not here. */
    @PostMapping("/api/jobs/{id}/tasks/{taskId}/intervene")
    public void intervene(@PathVariable UUID id, @PathVariable String taskId, @RequestBody Map<String, String> body) {
        final Map<String, Object> job = queue.get(id);   // #296's own reasoning: a bad id must 404 before anything else
        final String text = body.get("text");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        intervene.offer(String.valueOf(job.get("run_id")), taskId, text);
    }

    /** the task's prompt, progressively enriched. taskInstruction(id) is a fixed template (just a
     *  function of the id) so it's always shown, even before the task starts; the dynamic part -
     *  packs/{id}.md - only exists once the harness has actually computed it (Packs.taskPack()
     *  depends on the live, mutating workspace: git history, docs/PROGRESS.md, prior tasks' diffs,
     *  not just the plan), so "started" tells the UI whether it's looking at the real, full prompt
     *  or just the static instruction every task shares. */
    @GetMapping("/api/jobs/{id}/tasks/{taskId}/prompt")
    public Map<String, Object> taskPrompt(@PathVariable UUID id, @PathVariable String taskId) {
        final Map<String, Object> job = queue.get(id);
        final String instruction = Packs.taskInstruction(taskId);
        try {
            final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path pack = base.resolve("packs").resolve(taskId + ".md");
            if (!Files.isRegularFile(pack) || !isContainedEvenViaSymlinks(base, pack))
                return Map.of("started", false, "text", instruction);
            return Map.of("started", true, "text", instruction + "\n\n" + Files.readString(pack));
        } catch (final Exception e) {
            return Map.of("started", false, "text", instruction);
        }
    }

    /** #259: the task's own net change in its workspace. "before" is NEVER just the task's own tag's
     *  git-parent (<id>^) - found live on the very first real task of a real run: the agent's own
     *  session had ALREADY run its own `git commit` before our own snapshot() ran, so phase/{id}'s
     *  sole parent was the agent's OWN commit (already containing every file the task touched),
     *  not the true pre-task state - <id>^..{id} came back a silent, empty diff. The actual
     *  boundary is always the nearest ANCESTOR phase/* tag, however many un-tagged commits (ours or
     *  the agent's own) sit in between - `git describe --tags --match phase/* --abbrev=0` finds
     *  exactly that, walking past anything untagged. For a FINISHED task (its own phase/{id} tag
     *  exists), before = the nearest phase/* tag reachable from phase/{id}'s parent, after =
     *  phase/{id} itself. For one still IN PROGRESS (or paused mid-session) there is no tag yet -
     *  before = the nearest phase/* tag reachable from HEAD, and the change so far is `git diff
     *  <before>` (the single-ref form: before vs the live working tree, covering both any
     *  untagged commits already made AND whatever is still uncommitted), plus the full content of
     *  every file the task created that git doesn't know about at all yet (plain `git diff` never
     *  shows those). */
    @GetMapping("/api/jobs/{id}/tasks/{taskId}/diff")
    public Map<String, Object> taskDiff(@PathVariable UUID id, @PathVariable String taskId) {
        return diffAgainstTag(id, "phase/" + taskId);
    }

    /** the shared core of taskDiff() (above) and sessionDiff() (phase sessions: Definition/Plan/
     *  Parallel plan) - identical boundary logic either way, just parameterized by which tag marks
     *  "done" for the thing being diffed. See taskDiff's own javadoc for why "before" is always the
     *  nearest ANCESTOR phase/* tag, never a literal ^-parent. */
    private Map<String, Object> diffAgainstTag(final UUID id, final String tag) {
        final Map<String, Object> job = queue.get(id);
        final Path ws = Path.of(props.workspaceRoot(), String.valueOf(job.get("run_id")), "workspace");
        if (!Files.exists(ws.resolve(".git")))
            return Map.of("started", false, "finished", false, "diff", "", "new_files", List.of());
        try {
            final boolean finished = com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, tag);
            final String before, after, diffText;
            final List<Map<String, Object>> newFiles = new ArrayList<>();
            boolean newFilesTruncated = false;
            if (finished) {
                after = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "rev-parse", tag);
                before = com.strgmai.ace.service.runner.RunBenchSupport.nearestPhaseTagSha(ws, tag + "^");
                diffText = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, diffArgs(before, after));
            } else {
                before = com.strgmai.ace.service.runner.RunBenchSupport.nearestPhaseTagSha(ws, "HEAD");
                after = null;
                diffText = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, diffArgs(before, null));
                final String untracked = com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "ls-files", "--others", "--exclude-standard");
                for (final String rel : untracked.split("\n")) {
                    if (rel.isBlank() || isVendored(rel)) continue;
                    if (newFiles.size() >= 50) { newFilesTruncated = true; break; }
                    final Path f = ws.resolve(rel);
                    String content;
                    try { content = Files.isRegularFile(f) ? Files.readString(f) : "(not a regular file)"; }
                    catch (final Exception e) { content = "(could not read: " + e.getMessage() + ")"; }
                    final boolean fileTruncated = content.length() > 50_000;
                    newFiles.add(Map.of("path", rel, "content", fileTruncated ? content.substring(0, 50_000) : content, "truncated", fileTruncated));
                }
            }
            final boolean diffTruncated = diffText.length() > 200_000;
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("started", true);
            result.put("finished", finished);
            result.put("before_sha", before);
            result.put("after_sha", after == null ? "" : after);
            result.put("diff", diffTruncated ? diffText.substring(0, 200_000) : diffText);
            result.put("diff_truncated", diffTruncated);
            result.put("new_files", newFiles);
            result.put("new_files_truncated", newFilesTruncated);
            return result;
        } catch (final Exception e) {
            return Map.of("started", true, "finished", false, "error", e.toString());
        }
    }

    /** #264: the phase-session counterpart of taskPrompt() - Definition/Plan run plainPlan=true
     *  with nothing dynamic beyond budget/docker boilerplate, so RunBench.PHASE_TEXT's fixed
     *  string genuinely is the whole prompt; Parallel plan mirrors taskPrompt's own two-part
     *  pattern exactly (a fixed instruction + the one real dynamic file written for it). Any other
     *  name (a task id, INTEGRATION, a continuation) returns the graceful "nothing to show" shape -
     *  this view is scoped to the three phases with no other way to be inspected. */
    @GetMapping("/api/jobs/{id}/sessions/{name}/prompt")
    public Map<String, Object> sessionPrompt(@PathVariable UUID id, @PathVariable String name) {
        if (!PHASE_TAGS.containsKey(name)) return Map.of("started", false, "text", "");
        if ("PARALLEL_PLAN".equals(name)) {
            final Map<String, Object> job = queue.get(id);
            try {
                final var base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
                final Path pack = base.resolve("packs").resolve("PARALLEL_PLAN.md");
                if (!Files.isRegularFile(pack) || !isContainedEvenViaSymlinks(base, pack))
                    return Map.of("started", false, "text", Packs.PARALLEL_PLAN_INSTRUCTION);
                return Map.of("started", true, "text", Packs.PARALLEL_PLAN_INSTRUCTION + "\n\n" + Files.readString(pack));
            } catch (final Exception e) {
                return Map.of("started", false, "text", Packs.PARALLEL_PLAN_INSTRUCTION);
            }
        }
        return Map.of("started", true, "text", com.strgmai.ace.service.runner.RunBench.PHASE_TEXT.get(name)[1]);
    }

    /** #264: the phase-session counterpart of taskDiff() - same diffAgainstTag() core, just keyed
     *  by PHASE_TAGS instead of a task id. Graceful empty response for any name not in that map. */
    @GetMapping("/api/jobs/{id}/sessions/{name}/diff")
    public Map<String, Object> sessionDiff(@PathVariable UUID id, @PathVariable String name) {
        if (!PHASE_TAGS.containsKey(name)) return Map.of("started", false, "finished", false, "diff", "", "new_files", List.of());
        return diffAgainstTag(id, PHASE_TAGS.get(name));
    }

    private static boolean isVendored(final String relPath) {
        return com.strgmai.ace.service.runner.RunBenchSupport.VENDORED_PATHS.stream().anyMatch(relPath::startsWith);
    }

    /** #260, found live: a workspace initialised before VENDORED_PATHS grew a given entry (here,
     *  .toolchains/ - a full unpacked Eclipse Temurin JDK the agent's own build downloaded straight
     *  into the project root) can still have it TRACKED from an earlier commit - the exclude list
     *  only ever stops something from being newly git-add'd, never un-tracks what already is, and
     *  `git diff` shows tracked paths regardless of what's excluded. Excluded here via git's own
     *  pathspec magic (":!<prefix>**") rather than post-processing the diff TEXT: an earlier
     *  attempt that stripped hunks by their "+++ b/"/"--- a/" line missed every one of these files,
     *  because they're all binary - git emits "Binary files a/X and b/X differ" for those instead,
     *  with no +++/--- line at all (same gap for a pure rename or a mode-only change). Pushing the
     *  exclusion into the git invocation itself sidesteps every one of those formats at once. */
    private static String[] diffArgs(final String... refs) {
        final var args = new ArrayList<String>(List.of("diff"));
        for (final String ref : refs) if (ref != null) args.add(ref);
        args.add("--");
        args.add(".");
        for (final String vendored : com.strgmai.ace.service.runner.RunBenchSupport.VENDORED_PATHS) args.add(":!" + vendored + "**");
        return args.toArray(new String[0]);
    }

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
    // unlike cancel just above, a still-queued job pauses outright as a non-terminal state (not
    // finished for its arm) - no finalizeIfDone here
    @PostMapping("/api/jobs/{id}/pause") public Map<String, Object> pause(@PathVariable UUID id) { return queue.pause(id); }
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

    /** #339: a standalone, post-hoc "match tasks between these two completed runs" tool for the
     *  Compare tab - independent of #322's own live automation loop (which this shares its
     *  matching mechanism with, but doesn't require). Re-parses each run's own
     *  IMPLEMENTATION_PLAN.md (the only place a finished run's task goal/acceptance text still
     *  lives - manifest.plan.tasks only carries id/title/deps/checks) the same safe way
     *  file()/plan() already resolve a run's own files. */
    @PostMapping("/api/runs/{a}/compare/{b}/match-tasks")
    public Map<String, Object> matchTasks(@PathVariable String a, @PathVariable String b,
                                          @RequestBody(required = false) Map<String, Object> body) throws Exception {
        final List<PlanTask> aTasks = runPlanTasks(a);
        final List<PlanTask> bTasks = runPlanTasks(b);
        final String reviewerModel = body == null ? null : (String) body.get("reviewer_model");
        final Path rd = Path.of(props.resultsDir(), a).toRealPath();
        final List<TaskMatcher.TaskMatch> matches = taskMatcher.match(aTasks, bTasks, reviewerModel, rd);
        return Map.of("matches", matches);
    }

    /** a finished run's own task list, straight from its IMPLEMENTATION_PLAN.md - empty if the run
     *  isn't imported or never produced a plan file (same graceful-empty convention plan() uses
     *  for a live job). */
    private List<PlanTask> runPlanTasks(final String runId) {
        try {
            final var base = Path.of(props.resultsDir(), runId).toRealPath();
            final Path planFile = base.resolve("IMPLEMENTATION_PLAN.md");
            if (!Files.isRegularFile(planFile) || !isContainedEvenViaSymlinks(base, planFile)) return List.of();
            return PlanParser.parse(Files.readString(planFile));
        } catch (final Exception e) { return List.of(); }
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
        // #296: validated synchronously, on THIS request thread, before the stream is ever opened -
        // EmptyResultDataAccessException from here reaches ApiExceptionHandler's normal 404 mapping
        // the same way every other /api/jobs/{id}/* endpoint's does. The loop below still re-fetches
        // every 2s to notice the job's own status changing (that part is unaffected) - this is only
        // about a BAD id never being allowed to commit an HTTP 200 text/event-stream response first.
        queue.get(id);
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

    /** An operator-facing, plain-text peek into a task's own live coding session - see
     *  TaskTranscript's own class javadoc for why this reads the session's raw JSONL directly
     *  rather than reusing Judge's trajectory rendering (it would silently drop the model's own
     *  thinking). Same SSE shape as events() above - a tail-capped catch-up batch on first
     *  connecting (or whenever the task's own session file changes, e.g. a restart), small
     *  incremental text after that - but scoped to one task's own session, not the whole job. */
    private static final int TRANSCRIPT_TAIL_CAP_CHARS = 20_000;

    @GetMapping("/api/jobs/{id}/tasks/{taskId}/transcript")
    public SseEmitter taskTranscript(final @PathVariable UUID id, final @PathVariable String taskId) {
        queue.get(id); // #296's own reasoning: a bad id must 404 before the stream ever opens
        final var emitter = new SseEmitter(30 * 60 * 1000L);
        final var transcript = new TaskTranscript();
        SSE_EXECUTOR.submit(() -> {
            try {
                while (true) {
                    final Map<String, Object> job = queue.get(id);
                    final var sessionsDir = Path.of(props.resultsDir(), String.valueOf(job.get("run_id")), "sessions");
                    final String block = transcript.poll(sessionsDir, taskId, TRANSCRIPT_TAIL_CAP_CHARS);
                    // sent as a JSON object (never a raw multi-line String - every block here IS
                    // multi-line) for the same reason every other event on this stream already is:
                    // a raw string with embedded newlines breaks SSE's own line-oriented framing
                    // (a spec-compliant multi-line "data:" payload needs one "data:" prefix PER
                    // line, which this app's own SseParser does not reconstruct from a raw blob).
                    // JSON-encoding sidesteps that entirely - a JSON string escapes \n, so the
                    // whole object is always exactly one wire line.
                    if (block != null) emitter.send(SseEmitter.event().name("line").data(Map.of("type", "line", "text", block)));
                    emitter.send(SseEmitter.event().name("status").data(Map.of("status", job.get("status"))));
                    if (RunSpec.TERMINAL.contains(job.get("status"))) { emitter.complete(); return; }
                    Thread.sleep(2000);
                }
            } catch (java.io.IOException e) {
                emitter.complete(); // a broken pipe from a closed dialog is normal, not a bug
            } catch (Exception e) {
                log.warn("task transcript SSE stream for job {} task {} failed unexpectedly", id, taskId, e);
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
