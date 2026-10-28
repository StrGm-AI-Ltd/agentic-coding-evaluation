package com.strgmai.ace.service.service;

import com.openai.models.models.Model;
import com.strgmai.ace.service.agent.AgentSession;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.config.JsonColumns;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.oracle.CheckId;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.plan.PlanParser;
import com.strgmai.ace.service.plan.PlanTask;
import com.strgmai.ace.service.runner.Intervene;
import com.strgmai.ace.service.runner.PauseAfterTask;
import com.strgmai.ace.service.runner.TaskMatcher;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final PauseAfterTask pauseAfterTask;
    private final com.strgmai.ace.service.agent.ProviderStore providerStore;

    public BenchController(DSLContext dsl, JobQueue queue, ImporterService importer, ExperimentsService experiments,
                           StatsService stats, WorkerService worker, BenchProperties props, Preflight preflight,
                           TreatmentPin pin, Intervene intervene, TaskMatcher taskMatcher, PauseAfterTask pauseAfterTask,
                           com.strgmai.ace.service.agent.ProviderStore providerStore) {
        this.dsl = dsl; this.queue = queue; this.importer = importer; this.experiments = experiments;
        this.stats = stats; this.worker = worker; this.props = props; this.preflight = preflight; this.pin = pin;
        this.intervene = intervene; this.taskMatcher = taskMatcher; this.pauseAfterTask = pauseAfterTask;
        this.providerStore = providerStore;
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
    /** The harness's own pipeline stages, shown as rows alongside the real plan tasks so the
     *  operator gets the same Stats/Live/History features for them too - the backend endpoints
     *  those already call (sessionCounts/scanAttempt/TaskTranscript) match a session purely by its
     *  own JSONL header "label" field, so using that EXACT label as the stage's own "id" here means
     *  no new plumbing is needed there at all. Each entry is {sessionLabel, title, completionTag} -
     *  completionTag is the git tag naming that stage's OWN finished state, which is irregular per
     *  stage (found live, same issue PHASE_TAGS below already documents for the pre-task phases):
     *  p0/p1 truncate their own label, PARALLEL_PLAN case-folds it, and REVIEW's is
     *  Reviews.selfReview()'s own "phase/review" - written only after the reviewer session AND its
     *  own revert-anything-outside-review/ cleanup both finish, not merely once it starts. Only
     *  included in the response when a session with that exact label actually exists (see
     *  stageRow()'s own sessionCounts().found() check) - a run without --parallel-plan never has a
     *  PARALLEL_PLAN session, and self-review only starts once every task is done, same "show what's
     *  actually there" rule the real per-task rows already follow. */
    private static final List<String[]> PRE_TASK_STAGES = List.of(
            new String[]{"p0_definition", "Definition", "phase/p0"},
            new String[]{"p1_plan", "Plan", "phase/p1"},
            new String[]{"PARALLEL_PLAN", "Parallel plan", "phase/parallel_plan"});
    /** RunBench's own fixed final step after every task wave finishes (RunBench.java's "---- integration:
     *  the fixed final task ----") - runs for every orchestrated multi-task plan, but only once the
     *  task loop has actually reached it, so this still goes through the same sessionCounts().found()
     *  gate as every other stage below ("show what's actually there"), not an unconditional row. */
    private static final String[] INTEGRATION_STAGE = {"INTEGRATION", "Integration", "phase/INTEGRATION"};
    private static final String[] POST_TASK_STAGE = {"REVIEW", "Self-review", "phase/review"};

    @GetMapping("/api/jobs/{id}/plan")
    public Map<String, Object> plan(@PathVariable UUID id) {
        final Map<String, Object> job = queue.get(id);
        final List<PlanTask> tasks;
        final Path base;
        try {
            base = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath();
            final Path planFile = base.resolve("IMPLEMENTATION_PLAN.md");
            if (!Files.isRegularFile(planFile) || !isContainedEvenViaSymlinks(base, planFile)) return Map.of("tasks", List.of());
            tasks = PlanParser.parseFile(planFile);
        } catch (final com.strgmai.ace.service.plan.PlanError e) { return Map.of("tasks", List.of(), "error", e.toString()); }
        catch (final Exception e) { return Map.of("tasks", List.of()); }
        final Path ws = Path.of(props.workspaceRoot(), String.valueOf(job.get("run_id")), "workspace");
        final Map<String, List<Map<String, Object>>> attemptsByTask = taskAttemptsByTaskId(job);
        final List<Map<String, Object>> rows = new ArrayList<>();
        int order = 0;
        for (final String[] stage : PRE_TASK_STAGES)
            if (sessionCounts(base, stage[0]).found()) rows.add(stageRow(order++, stage, ws));
        int waveIndex = 0;
        for (final List<PlanTask> wave : PlanParser.waves(tasks)) {
            waveIndex++;
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
                row.put("stage", false);
                rows.add(row);
            }
            // the FIX step (RunBench.java's own "FIX-W" + wave count naming - 1-indexed, matching
            // this wave's own position in PlanParser.waves()) only exists when THIS wave broke and
            // needed repair - same "show what's actually there" gate as every other stage here.
            final String fixId = "FIX-W" + waveIndex;
            if (sessionCounts(base, fixId).found())
                rows.add(stageRow(order++, new String[]{fixId, "Fix (wave " + waveIndex + ")", "phase/" + fixId}, ws));
        }
        if (sessionCounts(base, INTEGRATION_STAGE[0]).found()) rows.add(stageRow(order++, INTEGRATION_STAGE, ws));
        if (sessionCounts(base, POST_TASK_STAGE[0]).found()) rows.add(stageRow(order, POST_TASK_STAGE, ws));
        return Map.of("tasks", rows);
    }

    /** One pipeline-stage row, shaped exactly like a real task's own row above (so the UI's
     *  Api.PlanTask - and everything that reads one - needs no per-field null-handling beyond what
     *  a real task already tolerates) minus the fields that genuinely don't apply to a stage: no
     *  goal/deps (nothing in IMPLEMENTATION_PLAN.md describes a stage), no handoff step, no
     *  self-reported done/partial/blocked status (that convention is docs/PROGRESS.md's own, task-
     *  specific one), no task_attempts.jsonl history (logTaskAttempt() is only ever called for a
     *  real task). "stage" is the one new field the UI uses to tell the two apart - e.g. to hide the
     *  Restart tab, since the per-task restart endpoint assumes a "phase/" + id tag that doesn't
     *  match any of these stages' own irregular tag names. */
    private Map<String, Object> stageRow(final int order, final String[] stage, final Path ws) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("order", order);
        row.put("id", stage[0]);
        row.put("title", stage[1]);
        row.put("goal", "");
        row.put("deps", List.of());
        row.put("implemented", com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, stage[2]));
        row.put("handoff_done", false);
        row.put("reported_status", null);
        row.put("reported_detail", null);
        row.put("attempts", 0);
        row.put("rescued", false);
        row.put("stage", true);
        return row;
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
    // #398: task_granularity is the odd one out - it only takes effect on a restarted "p1_plan"
    // session specifically (RunBench.sessionWithPolicy's own name-gated check), a no-op on every
    // other phase/task restart, same "lookup is a no-op for a name that was never written"
    // reasoning every other override field here already relies on.
    private static final Set<String> RESTART_OVERRIDE_KEYS = Set.of(
            "task_wall_sec", "task_tokens", "max_turns", "wrapup_wall_sec", "wrapup_tokens", "first_token_timeout_sec",
            "judge_enabled", "judge_every_n_turns", "judge_model", "budget_rescue_enabled",
            "compaction_trigger", "compaction_strategies", "max_tokens", "temperature", "top_p", "top_k", "repetition_penalty", "reasoning_effort",
            "rescue_wall_sec", "rescue_tokens", "rescue_keep_recent_turns", "task_granularity", "lesson_recording_enabled");

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

        // #417-followup (found live, 2026-10-20): every main-session call now passes
        // continueSession=true (see RunBench.sessionWithPolicy()'s own fix), so a stale session
        // file left behind here would get silently RESUMED on the next attempt - the agent
        // reconstructing a conversation about code this git reset just discarded. Archiving each
        // invalidated tag's own session (never deleting - same "nothing here is ever destroyed,
        // just set aside" philosophy the backup git tag above already follows) is what makes that
        // continueSession=true safe for an EXPLICIT restart too, not just an interrupt-and-resume.
        final Path sessionsDir = Path.of(props.resultsDir(), String.valueOf(job.get("run_id"))).toRealPath().resolve("sessions");
        for (final String tag : tagsToDeleteSupplier.get()) {
            if (com.strgmai.ace.service.runner.RunBenchSupport.tagExists(ws, tag))
                com.strgmai.ace.service.runner.RunBenchSupport.gitOut(ws, "tag", "-d", tag);
            archiveSessionsForLabel(sessionsDir, labelForTag(tag));
        }

        return queue.setTaskOverride(id, overrideKey, overrides);
    }

    /** The session label a git tag names - the inverse of PHASE_TAGS for p0/p1/PARALLEL_PLAN's own
     *  irregular names, a plain "phase/" prefix strip for everything else (a real task id or its
     *  own "-handoff" sub-session, both identity-named). */
    private static String labelForTag(final String tag) {
        for (final var e : PHASE_TAGS.entrySet()) if (e.getValue().equals(tag)) return e.getKey();
        return tag.startsWith("phase/") ? tag.substring("phase/".length()) : tag;
    }

    /** Moves aside (never deletes) every session file whose own JSONL header "label" exactly
     *  matches, into sessions/archived/ - invisible to every existing per-label scan (scanAttempt(),
     *  TaskTranscript, compactionEffectiveness, ...), all of which list the sessions/ directory
     *  itself non-recursively, never walk into a subdirectory - but still present on disk,
     *  recoverable the same way the backup git tag is. Best-effort: a missing sessions/ directory
     *  or an unreadable file must never fail the restart itself. */
    private void archiveSessionsForLabel(final Path sessionsDir, final String label) {
        if (!Files.isDirectory(sessionsDir)) return;
        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var stream = Files.list(sessionsDir)) {
            for (final Path f : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                final String firstLine;
                try (var lines = Files.lines(f)) { firstLine = lines.findFirst().orElse(null); } catch (final Exception ignore) { continue; }
                if (firstLine == null) continue;
                final Map<String, Object> header;
                try { header = mapper.readValue(firstLine, Map.class); } catch (final Exception ignore) { continue; }
                if (!label.equals(header.get("label"))) continue;
                final Path archiveDir = sessionsDir.resolve("archived");
                Files.createDirectories(archiveDir);
                Files.move(f, archiveDir.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final Exception e) { log.warn("could not archive stale sessions for label {}: {}", label, e.toString()); }
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
        return taskAttemptsForRun(String.valueOf(job.get("run_id")), taskId);
    }

    /** #472/#475: the finished-run counterpart of every taskXxx() endpoint in this file - a
     *  results directory has always been the real unit these operate on (job.get("run_id")
     *  was only ever a lookup key to FIND it), so a run_id-scoped route needs nothing new, just
     *  the same core skipping the queue.get(id) indirection. Used by RunDetailView's own per-task
     *  dialog so a finished (or externally-imported/rescanned) run gets the exact same
     *  prompt/diff/history/judge/stats detail the live job page already has - #472's own ask. */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/attempts")
    public List<Map<String, Object>> taskAttemptsForRun(@PathVariable String runId, @PathVariable String taskId) {
        try {
            final var base = Path.of(props.resultsDir(), runId).toRealPath();
            final List<Map<String, Object>> all = new ArrayList<>(readTaskAttempts(base, taskId));
            Collections.reverse(all);
            return all;
        } catch (final Exception e) { return List.of(); }
    }

    /** this task's own task_attempts.jsonl rows, in file (chronological, oldest-first) order -
     *  the shared read taskAttempts() above (newest-first, for the History tab) and taskStats()
     *  below (matched up against sessionCounts()'s own per-attempt breakdown, for the Stats tab)
     *  both build on. Empty on any read failure, same as every other best-effort fallback here. */
    private List<Map<String, Object>> readTaskAttempts(final Path base, final String taskId) throws Exception {
        final Path file = base.resolve("task_attempts.jsonl");
        if (!Files.isRegularFile(file) || !isContainedEvenViaSymlinks(base, file)) return List.of();
        final var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        final List<Map<String, Object>> all = new ArrayList<>();
        for (final String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            final Map<String, Object> entry = mapper.readValue(line, Map.class);
            if (taskId.equals(entry.get("task_id"))) all.add(entry);
        }
        return all;
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
    /** compactionsBeforeTurn: 1-based ordinals of the model's own assistant turns that were
     *  immediately preceded by a compaction - e.g. [5, 23] means turns 5 and 23 each followed a
     *  compaction. Assumed (not verified per-entry) to line up 1:1 with the SAME attempt's own
     *  request_speeds list, oldest-first - both are built by walking the SAME underlying call
     *  sequence for this session (the session file's own assistant turns, interactions.jsonl's
     *  own entries for the same session_id), so the Nth turn here should be the Nth request
     *  there. The one way this can drift: a request_speeds entry gets dropped for lacking a
     *  usable usage block (parseRequestSpeedEntry - a failed/non-streamed call), shifting every
     *  ordinal after it by one. Good enough for a chart annotation, not something to build a
     *  stricter timestamp-based join for without real evidence this actually happens live. */
    /** compactionEvents: per-attempt compaction effectiveness - how many tokens each compaction
     *  actually reclaimed and how it's distributed across this attempt's own requests (see
     *  AgentSession.compactionEffectiveness()'s own javadoc for the real-reclaimed-vs-stubbed-count
     *  distinction this exists to surface). A SEPARATE read of the same file compactionsBeforeTurn
     *  above is built from, rather than derived from it - the two fields label the SAME events by
     *  slightly different conventions (the turn immediately AFTER vs. the turn immediately BEFORE
     *  each compaction), and compactionsBeforeTurn already powers an existing, working chart this
     *  change has no reason to touch. */
    record AttemptCounts(String sessionId, String sessionUuid, String startedIso, String endedIso, int turns, int compactions,
                          int toolErrorsTotal, Map<String, Object> toolCalls, double wallSec, List<Integer> compactionsBeforeTurn,
                          List<AgentSession.CompactionEvent> compactionEvents) {
    }

    /** aggregate (summed across every attempt - a false-start-then-real-redo pair counts as the
     *  task's real total cost, same convention Collect.compactionOverhead() (#361) already applies
     *  to its own per-task total) plus the individual, chronologically-ordered per-attempt
     *  breakdown - #355-followup-2: a task restarted partway through (an operator pause landing at
     *  an unlucky moment, a budget-exhausted redo) leaves MULTIPLE session files sharing its label,
     *  and collapsing them into one number hides that history from the Stats tab entirely. */
    record SessionCounts(boolean found, int turns, int compactions, int toolErrorsTotal, Map<String, Object> toolCalls,
                          double wallSec, List<AttemptCounts> attempts) {
        static final SessionCounts NOT_FOUND = new SessionCounts(false, 0, 0, 0, Map.of(), 0.0, List.of());
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
        final List<Path> matches = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (final Path f : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                final String firstLine;
                try (var lines = Files.lines(f)) { firstLine = lines.findFirst().orElse(null); } catch (final Exception ignore) { continue; }
                if (firstLine == null) continue;
                final Map<String, Object> header;
                try { header = mapper.readValue(firstLine, Map.class); } catch (final Exception ignore) { continue; }
                if (taskId.equals(header.get("label"))) matches.add(f);
            }
        } catch (final Exception e) { return SessionCounts.NOT_FOUND; }
        if (matches.isEmpty()) return SessionCounts.NOT_FOUND;
        final List<AttemptCounts> attempts = new ArrayList<>();
        for (final Path f : matches) {
            final AttemptCounts a = scanAttempt(mapper, f);
            if (a != null) attempts.add(a);
        }
        if (attempts.isEmpty()) return SessionCounts.NOT_FOUND;
        // chronological: oldest attempt first, so "Attempt 1" in the UI really is the earliest one
        attempts.sort(Comparator.comparing(a -> a.startedIso() == null ? "" : a.startedIso()));
        int turns = 0, compactions = 0, toolErrorsTotal = 0;
        double wallSec = 0.0;
        final Map<String, int[]> toolCounts = new LinkedHashMap<>();   // toolName -> [total, errors], summed across every attempt
        for (final AttemptCounts a : attempts) {
            turns += a.turns();
            compactions += a.compactions();
            toolErrorsTotal += a.toolErrorsTotal();
            wallSec += a.wallSec();
            a.toolCalls().forEach((name, v) -> {
                final Map<?, ?> cm = (Map<?, ?>) v;
                final int[] c = toolCounts.computeIfAbsent(name, k -> new int[2]);
                c[0] += ((Number) cm.get("count")).intValue();
                c[1] += ((Number) cm.get("errors")).intValue();
            });
        }
        final Map<String, Object> toolCallsOut = new LinkedHashMap<>();
        toolCounts.forEach((name, c) -> toolCallsOut.put(name, Map.of("count", c[0], "errors", c[1])));
        return new SessionCounts(true, turns, compactions, toolErrorsTotal, toolCallsOut, wallSec, attempts);
    }

    /** one session file's own turns/compactions/tool_errors/tool_calls/wall-time span - the
     *  per-attempt unit sessionCounts() sums over and the Stats tab's per-attempt sections are
     *  built from directly. null on a missing/unreadable file; best-effort per line otherwise -
     *  one bad line must not sink the whole attempt's count. */
    private AttemptCounts scanAttempt(final com.fasterxml.jackson.databind.ObjectMapper mapper, final Path f) {
        final List<String> lines;
        try { lines = Files.readAllLines(f); } catch (final Exception e) { return null; }
        int turns = 0, compactions = 0, toolErrorsTotal = 0;
        final Map<String, int[]> toolCounts = new LinkedHashMap<>();   // toolName -> [total, errors]
        final List<Integer> compactionsBeforeTurn = new ArrayList<>();   // 1-based turn ordinals
        boolean pendingCompaction = false;
        Instant firstTs = null, lastTs = null;
        String sessionUuid = null;
        for (final String line : lines) {
            if (line.isBlank()) continue;
            final Map<String, Object> e;
            try { e = mapper.readValue(line, Map.class); } catch (final Exception ignore) { continue; }
            if (e.get("ts") instanceof String ts) {
                try {
                    final Instant parsed = Instant.parse(ts);
                    if (firstTs == null) firstTs = parsed;
                    lastTs = parsed;
                } catch (final Exception ignore) { /* malformed ts must not sink the whole count */ }
            }
            final String type = String.valueOf(e.get("type"));
            if ("session".equals(type)) { if (e.get("id") instanceof String id) sessionUuid = id; continue; }
            if ("compaction".equals(type)) { compactions++; pendingCompaction = true; continue; }
            if (!"message".equals(type) || !(e.get("message") instanceof Map<?, ?> m)) continue;
            final Object role = m.get("role");
            if ("assistant".equals(role)) {
                turns++;
                if (pendingCompaction) { compactionsBeforeTurn.add(turns); pendingCompaction = false; }
            } else if ("toolResult".equals(role)) {
                final int[] c = toolCounts.computeIfAbsent(String.valueOf(m.get("toolName")), k -> new int[2]);
                c[0]++;
                if (Boolean.TRUE.equals(m.get("isError"))) { c[1]++; toolErrorsTotal++; }
            }
        }
        final Map<String, Object> toolCallsOut = new LinkedHashMap<>();
        toolCounts.forEach((name, c) -> toolCallsOut.put(name, Map.of("count", c[0], "errors", c[1])));
        final double wallSec = firstTs == null ? 0.0 : Duration.between(firstTs, lastTs).toMillis() / 1000.0;
        return new AttemptCounts(f.getFileName().toString(), sessionUuid, firstTs == null ? null : firstTs.toString(),
                lastTs == null ? null : lastTs.toString(), turns, compactions, toolErrorsTotal, toolCallsOut, wallSec, compactionsBeforeTurn,
                AgentSession.compactionEffectiveness(f));
    }

    /** #355-followup-2: each session-file attempt, enriched with the params the run actually used
     *  for it and its own harness-reported outcome - both sourced from task_attempts.jsonl, the
     *  History tab's own data (RunBench.logTaskAttempt()). Paired by nearest attempt_started to
     *  the session's own first ts, since logTaskAttempt() writes attempt_started at the exact
     *  moment the session that's about to run was created - a close match is strong evidence it's
     *  the same attempt, an index-based pairing is not: a session cut off before logTaskAttempt()
     *  ever ran (an operator pause landing mid-attempt) leaves a session file with NO corresponding
     *  task_attempts.jsonl row at all, which would silently misalign every later index.
     *
     *  Matching is GLOBAL (every attempt/record pair's own diff, smallest first), not greedy by
     *  attempt order - found live on T3: its one false-start session (22:26) sits only 3 minutes
     *  before the one real task_attempts.jsonl row (22:29:55, for the REAL attempt that started
     *  right after it), which is well inside a naive per-attempt-in-order "first match within
     *  tolerance wins" scan - the false start would have claimed the real attempt's own record,
     *  leaving the real attempt with none. Processing diffs smallest-first guarantees the near-
     *  exact match (microseconds, logTaskAttempt's own write) wins before the merely-close one
     *  (minutes) is even considered. A session attempt with no match within 5 minutes just
     *  carries no params - correctly reflecting that the harness never got to record it. */
    private List<Map<String, Object>> attemptSections(final Path base, final String taskId, final List<AttemptCounts> attempts) throws Exception {
        // #355-followup-4/6: the operator asked for a chart per attempt that actually made a
        // request, not one combined chart for the whole task - see requestSpeedsPerAttempt()'s
        // own javadoc for why session id alone can't do this split (found live on T11: every
        // attempt of the same task shares ONE deterministic session id, so a false start and the
        // real redo's requests both landed in the same bucket and every attempt's chart showed
        // the SAME combined data instead of its own).
        final List<List<Map<String, Object>>> speedsPerAttempt = requestSpeedsPerAttempt(base, attempts);
        final List<Map<String, Object>> records = readTaskAttempts(base, taskId);   // chronological, oldest first
        final int[] matchedRecordIdx = new int[attempts.size()];
        Arrays.fill(matchedRecordIdx, -1);
        final List<long[]> candidates = new ArrayList<>();   // [attemptIdx, recordIdx, diffMs]
        for (int ai = 0; ai < attempts.size(); ai++) {
            final Instant sessionStart = parseInstantOrNull(attempts.get(ai).startedIso());
            if (sessionStart == null) continue;
            for (int ri = 0; ri < records.size(); ri++) {
                if (!(records.get(ri).get("attempt_started") instanceof String s)) continue;
                final Instant recStart = parseInstantOrNull(s);
                if (recStart == null) continue;
                candidates.add(new long[]{ai, ri, Math.abs(Duration.between(sessionStart, recStart).toMillis())});
            }
        }
        candidates.sort(Comparator.comparingLong(c -> c[2]));
        final boolean[] recordConsumed = new boolean[records.size()];
        for (final long[] c : candidates) {
            if (c[2] > 300_000) break;   // 5 minutes - generous given a true match should be near-instant
            final int ai = (int) c[0], ri = (int) c[1];
            if (matchedRecordIdx[ai] >= 0 || recordConsumed[ri]) continue;   // attempt or record already spoken for by a closer pair
            matchedRecordIdx[ai] = ri;
            recordConsumed[ri] = true;
        }
        // #493: every judge attempt stamp that falls inside THIS attempt's own [started, ended]
        // window, not just the single nearest one - see judgeStampsInAttemptWindow()'s own javadoc
        // (a silent mid-session reconnect, not a Restart, can legitimately produce more than one
        // stamp for the same real attempt).
        final Map<String, List<JudgeCheckpoint>> judgeByStamp = judgeCheckpointsByAttemptStamp(base, taskId);
        final List<Map<String, Object>> out = new ArrayList<>();
        for (int ai = 0; ai < attempts.size(); ai++) {
            final AttemptCounts a = attempts.get(ai);
            final Map<String, Object> am = new LinkedHashMap<>();
            am.put("session_id", a.sessionId());
            am.put("started_iso", a.startedIso());
            am.put("ended_iso", a.endedIso());
            am.put("turns", a.turns());
            am.put("compactions", a.compactions());
            am.put("tool_errors", a.toolErrorsTotal());
            am.put("tool_calls", a.toolCalls());
            am.put("seconds", a.wallSec());
            if (matchedRecordIdx[ai] >= 0) {
                final Map<String, Object> rec = records.get(matchedRecordIdx[ai]);
                am.put("params", rec.get("params"));
                am.put("overridden", rec.get("overridden"));
                am.put("attempt_rc", rec.get("rc"));
                am.put("reported_status", rec.get("reported_status"));
                am.put("reported_detail", rec.get("reported_detail"));
            }
            am.put("request_speeds", speedsPerAttempt.get(ai));
            am.put("compactions_before_request", a.compactionsBeforeTurn());
            // per-attempt compaction effectiveness - two tables, one row per compaction event: how
            // the events are distributed across this attempt's own requests, and how many tokens
            // each one actually reclaimed (see AttemptCounts.compactionEvents's own javadoc)
            final List<Map<String, Object>> compactionDistribution = new ArrayList<>(a.compactionEvents().size());
            final List<Map<String, Object>> compactionReclaimed = new ArrayList<>(a.compactionEvents().size());
            for (final AgentSession.CompactionEvent ce : a.compactionEvents()) {
                final Map<String, Object> cd = new LinkedHashMap<>();
                cd.put("request_number", ce.requestNumber());
                cd.put("requests_since_previous", ce.requestsSincePrevious());
                compactionDistribution.add(cd);
                final Map<String, Object> rc = new LinkedHashMap<>();
                rc.put("request_number", ce.requestNumber());
                rc.put("prompt_tokens_before", ce.promptTokensBefore());
                rc.put("reclaimed_tokens", ce.reclaimedTokens());
                compactionReclaimed.add(rc);
            }
            am.put("compaction_count", a.compactionEvents().size());
            am.put("compaction_distribution", compactionDistribution);
            am.put("compaction_reclaimed", compactionReclaimed);
            final List<JudgeCheckpoint> checkpoints = new ArrayList<>();
            for (final String stamp : judgeStampsInAttemptWindow(judgeByStamp.keySet(), a))
                checkpoints.addAll(judgeByStamp.getOrDefault(stamp, List.of()));
            checkpoints.sort(Comparator.comparingInt(JudgeCheckpoint::turn));
            final List<Integer> judgeOnTrack = new ArrayList<>();
            final List<Integer> judgeOffTrack = new ArrayList<>();
            for (final JudgeCheckpoint c : checkpoints) (c.offTrack() ? judgeOffTrack : judgeOnTrack).add(c.turn());
            am.put("judge_on_track_requests", judgeOnTrack);
            am.put("judge_off_track_requests", judgeOffTrack);
            out.add(am);
        }
        return out;
    }

    private static Instant parseInstantOrNull(final String iso) {
        if (iso == null) return null;
        try { return Instant.parse(iso); } catch (final Exception e) { return null; }
    }

    /** #355-followup-3/4: per-request prefill speed for the Stats tab's per-attempt chart (the
     *  operator asked for one chart per attempt that actually made a request, not one combined
     *  chart for the whole task) - found live that this run's own interactions.jsonl (the proxy's
     *  journal of every call through it, RecordingProxy) was 208MB across only 1472 lines: each
     *  line carries the full request (growing conversation history + tool specs) and response
     *  (generated text) alongside the tiny "usage" object actually needed here. A plain
     *  ObjectMapper.readValue() per line would materialize all of that just to read 2 numbers,
     *  repeatedly, on every poll while a task's dialog is open - this streams the file line by
     *  line (never holding more than one line in memory) and, line by line, uses Jackson's
     *  low-level streaming parser to walk down to "response.usage" and skipChildren() everything
     *  else ("request", "reasoning", "content", "tool_calls") without ever building a tree for
     *  them. A quick substring pre-check against this task's own session id(s) (read from each
     *  session file's own header "id" field, the same value AgentSession/ReferenceAgent stamp as
     *  X-Ace-Session-Id) skips full parsing for every line that cannot possibly be this task's
     *  before paying for even the streaming parse. One pass over the file buckets every matching
     *  line by its own session_id, so a multi-attempt task costs one scan, not one per attempt. */
    private Map<String, List<Map<String, Object>>> requestSpeedsBySession(final Path base, final Set<String> sessionIds) {
        // never Map.of(): an attempt with no session id at all (sessionUuid() null - an older
        // session file predating the "type":"session" header field) still gets looked up via
        // getOrDefault(null, ...) below, and Map.of()'s immutable maps throw NPE on ANY null-key
        // lookup, not just insertion - found live, failing every test fixture missing that header
        if (sessionIds.isEmpty()) return new LinkedHashMap<>();
        final Path file = base.resolve("interactions.jsonl");
        if (!Files.isRegularFile(file) || !isContainedEvenViaSymlinks(base, file)) return new LinkedHashMap<>();
        final var factory = new com.fasterxml.jackson.core.JsonFactory();
        final Map<String, List<Map<String, Object>>> bySession = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || sessionIds.stream().noneMatch(line::contains)) continue;
                final Map<String, Object> entry = parseRequestSpeedEntry(factory, line, sessionIds);
                if (entry != null) bySession.computeIfAbsent((String) entry.remove("session_id"), k -> new ArrayList<>()).add(entry);
            }
        } catch (final Exception e) { return bySession; }
        bySession.values().forEach(list -> list.sort(Comparator.comparingLong(e -> ((Number) e.get("seq")).longValue())));
        return bySession;
    }

    /** #355-followup-6: found live on T11 - session_id alone cannot tell two attempts of the
     *  SAME task apart. RunBenchSupport's own session-id scheme is deterministic, keyed only by
     *  (runId, taskId) - UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + taskId)...) - so a
     *  false start and its real redo, two entirely separate session FILES with their own distinct
     *  [startedIso, endedIso] windows hours apart, still share the identical session_id in both
     *  files' own header. A naive per-session bucket (requestSpeedsBySession() above) handed the
     *  SAME combined list to every attempt sharing that id - attempt 1's own chart showed
     *  attempt 2's 80+ requests despite attempt 1's own wall time being 13 seconds.
     *
     *  Attempts that DON'T share their session id with any other attempt (the common case - a
     *  task's real attempt predating a since-retired continuation scheme, or simply the only
     *  attempt) get their session's whole bucket directly, unchanged. Attempts that DO share one
     *  get that bucket split by matching each request's own "ts" against which sharing attempt's
     *  own [startedIso, endedIso] window it falls inside (or is nearest to, for a request logged
     *  a moment before/after the session file's own first/last message - real skew, not an
     *  error). "ts"/"session_id" are stripped from the final per-request maps - both were only
     *  ever needed for this split, not part of the Stats tab's own response shape. */
    /** #497, found live on a real run's T12 (explicitly Restarted earlier in its own run): a
     *  request/judge/compaction ordinal mismatch's root cause, one level up from #493's judge-
     *  checkpoint fix - interactions.jsonl is a per-run log, and RunBenchSupport's own session-id
     *  scheme is deterministic per (runId, taskId), so a Restart's own DISCARDED, archived attempt
     *  shares the identical session id with whatever attempt replaced it. sessionCounts() never
     *  sees the archived file (it only scans the live sessions/ directory), so that abandoned
     *  attempt's own proxy calls - sometimes hours of them - still land in the SAME interactions.
     *  jsonl bucket as the current attempt's real ones. The single-sharer branch used to take that
     *  WHOLE bucket unconditionally, silently mixing in the abandoned attempt's traffic: T12's real
     *  25-turn attempt ended up with a 76-entry request_speeds list, the extra ~51 being the
     *  archived attempt's own hours-earlier calls (sorted first by the proxy's global seq counter)
     *  - which is exactly why its compaction/judge markers (1-based ordinals among the real 25
     *  turns) landed on the wrong, unrelated request entirely instead of a visible line at the
     *  right spot. A missing "ts" (only ever a hand-written test fixture shape - every real
     *  interactions.jsonl line has one) still passes through unfiltered: there is no timestamp to
     *  test, and - in the single-sharer case - no second candidate to confuse it with either. */
    private static final Duration REQUEST_SPEED_WINDOW_TOLERANCE = Duration.ofMinutes(30);

    private List<List<Map<String, Object>>> requestSpeedsPerAttempt(final Path base, final List<AttemptCounts> attempts) {
        final Map<String, List<Map<String, Object>>> bySession = requestSpeedsBySession(base,
                attempts.stream().map(AttemptCounts::sessionUuid).filter(Objects::nonNull).collect(Collectors.toSet()));
        final List<List<Map<String, Object>>> perAttempt = new ArrayList<>();
        for (int i = 0; i < attempts.size(); i++) perAttempt.add(new ArrayList<>());
        final Map<String, List<Integer>> indicesBySessionUuid = new LinkedHashMap<>();
        for (int i = 0; i < attempts.size(); i++) {
            final String uuid = attempts.get(i).sessionUuid();
            if (uuid != null) indicesBySessionUuid.computeIfAbsent(uuid, k -> new ArrayList<>()).add(i);
        }
        for (final var group : indicesBySessionUuid.entrySet()) {
            final List<Map<String, Object>> sessionEntries = bySession.getOrDefault(group.getKey(), List.of());
            final List<Integer> sharingIndices = group.getValue();
            if (sharingIndices.size() == 1) {
                final int idx = sharingIndices.get(0);
                final Instant start = parseInstantOrNull(attempts.get(idx).startedIso());
                final Instant windowStart = start == null ? null : start.minus(REQUEST_SPEED_WINDOW_TOLERANCE);
                final Instant end = parseInstantOrNull(attempts.get(idx).endedIso());
                final Instant windowEnd = end == null ? null : end.plus(REQUEST_SPEED_WINDOW_TOLERANCE);
                for (final Map<String, Object> reqEntry : sessionEntries) {
                    final Instant reqTs = parseInstantOrNull((String) reqEntry.get("ts"));
                    final boolean inWindow = reqTs == null || windowStart == null
                            || (!reqTs.isBefore(windowStart) && (windowEnd == null || !reqTs.isAfter(windowEnd)));
                    if (inWindow) perAttempt.get(idx).add(reqEntry);
                }
                continue;
            }
            for (final Map<String, Object> reqEntry : sessionEntries) {
                final Instant reqTs = parseInstantOrNull((String) reqEntry.get("ts"));
                if (reqTs == null) { perAttempt.get(sharingIndices.get(0)).add(reqEntry); continue; }
                int bestIdx = -1;
                long bestDist = Long.MAX_VALUE;
                for (final int idx : sharingIndices) {
                    final AttemptCounts att = attempts.get(idx);
                    final Instant start = parseInstantOrNull(att.startedIso());
                    final Instant end = parseInstantOrNull(att.endedIso());
                    if (start == null || end == null) continue;
                    final long dist = !reqTs.isBefore(start) && !reqTs.isAfter(end) ? 0
                            : Math.min(Math.abs(Duration.between(start, reqTs).toMillis()), Math.abs(Duration.between(end, reqTs).toMillis()));
                    if (dist < bestDist) { bestDist = dist; bestIdx = idx; }
                }
                if (bestIdx >= 0 && bestDist <= REQUEST_SPEED_WINDOW_TOLERANCE.toMillis()) perAttempt.get(bestIdx).add(reqEntry);
            }
        }
        for (final List<Map<String, Object>> list : perAttempt) {
            list.sort(Comparator.comparingLong(e -> ((Number) e.get("seq")).longValue()));
            for (final Map<String, Object> e : list) { e.remove("ts"); e.remove("session_id"); }
        }
        return perAttempt;
    }

    /** one interactions.jsonl line -> {seq, session_id, prompt_tokens, prefill_tok_per_sec,
     *  generation_tok_per_sec, time_to_first_token_sec, total_time_sec}, or null when the line's
     *  own session_id doesn't match (the caller's substring pre-check is only a hint - this
     *  confirms it against the real field) or it carries no usable usage block (a non-streamed or
     *  failed call). response.usage's own prompt_tokens_per_second/generation_tokens_per_second/
     *  time_to_first_token/total_time are oMLX's own reported figures - never re-derived here.
     *  generation_tok_per_sec, time_to_first_token_sec and total_time_sec are all optional (null
     *  when absent) - unlike prefill speed, none of them is required for a line to count. */
    private static Map<String, Object> parseRequestSpeedEntry(final com.fasterxml.jackson.core.JsonFactory factory, final String line, final Set<String> sessionIds) {
        try (var p = factory.createParser(line)) {
            if (p.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) return null;
            Long seq = null;
            String sessionId = null;
            String ts = null;
            Integer promptTokens = null;
            Double prefillTps = null;
            Double generationTps = null;
            Double timeToFirstToken = null;
            Double totalTime = null;
            while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                final String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "seq" -> seq = p.getLongValue();
                    case "session_id" -> sessionId = p.getText();
                    case "ts" -> ts = p.getText();
                    case "response" -> {
                        if (p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                            while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                                final String rf = p.currentName();
                                p.nextToken();
                                if ("usage".equals(rf) && p.currentToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                                    while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                                        final String uf = p.currentName();
                                        p.nextToken();
                                        if ("prompt_tokens".equals(uf)) promptTokens = p.getIntValue();
                                        else if ("prompt_tokens_per_second".equals(uf)) prefillTps = p.getDoubleValue();
                                        else if ("generation_tokens_per_second".equals(uf)) generationTps = p.getDoubleValue();
                                        else if ("time_to_first_token".equals(uf)) timeToFirstToken = p.getDoubleValue();
                                        else if ("total_time".equals(uf)) totalTime = p.getDoubleValue();
                                        else p.skipChildren();
                                    }
                                } else p.skipChildren();
                            }
                        } else p.skipChildren();
                    }
                    default -> p.skipChildren();
                }
            }
            if (sessionId == null || !sessionIds.contains(sessionId) || seq == null || promptTokens == null || prefillTps == null) return null;
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", seq);
            out.put("session_id", sessionId);
            out.put("ts", ts);
            out.put("prompt_tokens", promptTokens);
            out.put("prefill_tok_per_sec", prefillTps);
            out.put("generation_tok_per_sec", generationTps);
            out.put("time_to_first_token_sec", timeToFirstToken);
            out.put("total_time_sec", totalTime);
            return out;
        } catch (final Exception e) { return null; }
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
        return taskStatsForRun(String.valueOf(job.get("run_id")), taskId);
    }

    /** #472/#475: the finished-run counterpart of taskStats() above - see taskAttemptsForRun(). */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/stats")
    public Map<String, Object> taskStatsForRun(@PathVariable String runId, @PathVariable String taskId) {
        try {
            final var base = Path.of(props.resultsDir(), runId).toRealPath();
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
            // a task resumed past on a later attempt (RunBench finds its phase/<id> tag already
            // committed and skips re-running it) gets a bare {id, rc, resumed} manifest entry with
            // no "seconds" at all - fall back to the session file's own first-to-last ts span
            // rather than showing nothing just because this one task happened to be skip-resumed
            if (out.get("seconds") == null && live.found()) out.put("seconds", live.wallSec());
            // found live: the SAME bare-entry gap Collect.perTask() (#501) already fixed for the
            // finished-run Plan tasks grid also hits this, LIVE, endpoint's own "reported"/
            // "verification"/"done_verified" - a resumed-past task showed NOTHING for any of the
            // three here, even though its real attempt's own record survives, untouched, in
            // task_attempts.jsonl (append-only; a resumed-past task never logs a new entry there -
            // see RunBench.logTaskAttempt()'s own two call sites). Same fallback, same source.
            if (out.get("reported") == null || out.get("verification") == null) {
                try {
                    final List<Map<String, Object>> attempts = readTaskAttempts(base, taskId);
                    if (!attempts.isEmpty()) {
                        final Map<String, Object> latest = attempts.get(attempts.size() - 1);
                        out.putIfAbsent("reported", latest.get("reported_status"));
                        if (out.get("verification") == null && latest.get("verification") instanceof Map<?, ?> v) out.put("verification", v);
                        if (out.get("done_verified") == null && latest.containsKey("done_verified")) out.put("done_verified", latest.get("done_verified"));
                    }
                } catch (final Exception e) {
                    // best-effort enrichment only - the live data already in `out` must still be
                    // returned even if task_attempts.jsonl itself can't be read right now
                    log.debug("could not fall back to task_attempts.jsonl for run {} task {}: {}", runId, taskId, e.toString());
                }
            }
            out.put("attempts", attemptSections(base, taskId, live.attempts()));
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
            log.warn("could not read stats for run {} task {}: {}", runId, taskId, e.toString());
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
        return taskJudgeForRun(String.valueOf(job.get("run_id")), taskId);
    }

    /** #472/#475: the finished-run counterpart of taskJudge() above - see taskAttemptsForRun(). */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/judge")
    public List<Map<String, Object>> taskJudgeForRun(@PathVariable String runId, @PathVariable String taskId) {
        try {
            final var base = Path.of(props.resultsDir(), runId).toRealPath();
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

            // #493, found live (T11: one real session, 4 real checkpoints across two judge
            // attempt stamps - a silent mid-session reconnect, not a Restart): every stamp that
            // falls inside a REAL (current, non-archived) attempt's own [started, ended] window,
            // not just whichever stamp sorts lexically latest - see
            // judgeStampsInAttemptWindow()'s own javadoc for why.
            final var attempts = sessionCounts(base, taskId).attempts();
            final Set<String> wanted = new LinkedHashSet<>();
            for (final AttemptCounts a : attempts) wanted.addAll(judgeStampsInAttemptWindow(byAttempt.keySet(), a));
            if (wanted.isEmpty()) wanted.add(byAttempt.keySet().stream().max(Comparator.naturalOrder()).orElseThrow());

            record Entry(String stamp, int turn, String prompt, String recommendation) {}
            final List<Entry> entries = new ArrayList<>();
            for (final String stamp : wanted) {
                for (final Path f : byAttempt.getOrDefault(stamp, List.of())) {
                    final var m = pattern.matcher(f.getFileName().toString());
                    m.matches();
                    final int turn = Integer.parseInt(m.group(2));
                    final String content = Files.readString(f);
                    final int promptIdx = content.indexOf("## Prompt");
                    final int recIdx = content.indexOf("## Recommendation");
                    final String prompt = promptIdx >= 0 && recIdx > promptIdx
                            ? content.substring(promptIdx + "## Prompt".length(), recIdx).strip() : "";
                    final String recommendation = recIdx >= 0 ? content.substring(recIdx + "## Recommendation".length()).strip() : content.strip();
                    entries.add(new Entry(stamp, turn, prompt, recommendation));
                }
            }
            // stamp first (true chronological order across reconnects), then turn within a stamp -
            // two reconnects of the same task can each independently reach "turn 5" (the judge's own
            // interval state resets per reconnect, same root cause as the missing-checkpoints bug
            // this fixes), so a plain turn-only sort would interleave them confusingly
            entries.sort(Comparator.comparing(Entry::stamp).thenComparingInt(Entry::turn));
            return entries.stream().map(e -> Map.<String, Object>of(
                    "turn", e.turn(), "prompt", e.prompt(), "recommendation", e.recommendation())).toList();
        } catch (final Exception e) { return List.of(); }
    }

    /** #493: a stamp belongs to an attempt if it falls inside that attempt's own [started, ended]
     *  window, padded by the same 5-minute tolerance the task_attempts.jsonl match above uses - a
     *  judge checkpoint file is written some real wall-clock time (the judge's own LLM call) after
     *  the turn that triggered it, so its stamp can legitimately land a little outside the session's
     *  own first/last MESSAGE timestamps, especially for a short session or one whose final turn is
     *  also its last judge check. Open-ended on the end side while the attempt is still running. See
     *  taskJudge()'s own javadoc for why this, not nearest-single-match, is the right test at all:
     *  a task's session can be silently interrupted and reconnected (continueSession=true, NOT a
     *  user-triggered Restart) - each reconnect gets its own fresh judge attempt stamp
     *  (RunBench.buildJudgeCheckpoint()/Judge.checkpoint() both capture System.currentTimeMillis()
     *  fresh on every top-level task-loop entry) even though the underlying session (and so the
     *  task's real progress) is continuous, so more than one stamp can legitimately belong to the
     *  SAME one real attempt. Still correctly excludes a genuinely abandoned pre-Restart attempt's
     *  stale checkpoints, since a real Restart's new attempt starts well outside their tolerance. */
    private static final Duration JUDGE_STAMP_TOLERANCE = Duration.ofMinutes(5);

    private static List<String> judgeStampsInAttemptWindow(final Collection<String> stamps, final AttemptCounts a) {
        final Instant start = parseInstantOrNull(a.startedIso());
        if (start == null) return List.of();
        final Instant windowStart = start.minus(JUDGE_STAMP_TOLERANCE);
        final Instant end = parseInstantOrNull(a.endedIso());   // null (still running): window stays open-ended
        final Instant windowEnd = end == null ? null : end.plus(JUDGE_STAMP_TOLERANCE);
        final List<String> out = new ArrayList<>();
        for (final String stamp : stamps) {
            if (stamp.isEmpty()) continue;   // the legacy, unstamped shape has no timestamp to test
            final Instant stampInstant;
            try { stampInstant = Instant.from(JUDGE_ATTEMPT_STAMP_FORMAT.parse(stamp)); }
            catch (final Exception e) { continue; }
            if (!stampInstant.isBefore(windowStart) && (windowEnd == null || !stampInstant.isAfter(windowEnd))) out.add(stamp);
        }
        return out;
    }

    private record JudgeCheckpoint(int turn, boolean offTrack) {
    }

    /** matches Judge.ATTEMPT_STAMP_FORMAT exactly (hyphens, not colons, in the time portion - a
     *  colon-free, filesystem-safe stamp, per that class's own javadoc) - needed here to parse a
     *  judge checkpoint filename's own stamp back into an Instant for the nearest-attempt match
     *  below; judgeTurnsByAttemptStamp() reads the raw stamp string, this turns it back into
     *  something comparable against an attempt's own startedIso(). */
    private static final DateTimeFormatter JUDGE_ATTEMPT_STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    /** Judge checkpoint verdicts (judge/&lt;taskId&gt;[-&lt;attemptStamp&gt;]-turn&lt;N&gt;.md), grouped
     *  by their own attempt stamp - unlike taskJudge() above, every attempt's own checkpoints are
     *  kept here, not just the latest, so attemptSections() below can mark each attempt's own
     *  chart with its own judge markers rather than only the task's most recent attempt. Each
     *  checkpoint's verdict is its own leading ON TRACK/OFF TRACK line, same check as
     *  Judge.isOffTrack() (package-private, a different package, so mirrored rather than reused -
     *  same fail-safe default: unparseable/missing reads as on track, never off track on noise
     *  alone). "" (the legacy, unstamped shape) groups together too but has no real timestamp to
     *  match against an attempt's own start time, so those checkpoints are never attributed to
     *  any one attempt - an acceptable gap for pre-#307 runs, not this one. */
    private static Map<String, List<JudgeCheckpoint>> judgeCheckpointsByAttemptStamp(final Path base, final String taskId) {
        final Path dir = base.resolve("judge");
        if (!Files.isDirectory(dir) || !isContainedEvenViaSymlinks(base, dir)) return Map.of();
        final Pattern pattern = Pattern.compile(Pattern.quote(taskId) + "-(?:(.+)-)?turn(\\d+)\\.md");
        final Map<String, List<JudgeCheckpoint>> byStamp = new LinkedHashMap<>();
        try (var stream = Files.list(dir)) {
            for (final Path f : stream.toList()) {
                final var m = pattern.matcher(f.getFileName().toString());
                if (!m.matches()) continue;
                final String stamp = m.group(1);
                if (stamp == null || stamp.isEmpty()) continue;
                final int turn = Integer.parseInt(m.group(2));
                final String content = Files.readString(f);
                final int recIdx = content.indexOf("## Recommendation");
                final String recommendation = recIdx >= 0 ? content.substring(recIdx + "## Recommendation".length()).strip() : null;
                final String firstLine = recommendation == null ? "" : recommendation.lines().findFirst().orElse("");
                final boolean offTrack = firstLine.strip().toUpperCase(Locale.ROOT).startsWith("OFF TRACK");
                byStamp.computeIfAbsent(stamp, k -> new ArrayList<>()).add(new JudgeCheckpoint(turn, offTrack));
            }
        } catch (final Exception e) { return Map.of(); }
        byStamp.values().forEach(list -> list.sort(Comparator.comparingInt(JudgeCheckpoint::turn)));
        return byStamp;
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
        return taskPromptForRun(String.valueOf(job.get("run_id")), taskId);
    }

    /** #472/#475: the finished-run counterpart of taskPrompt() above - see taskAttemptsForRun(). */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/prompt")
    public Map<String, Object> taskPromptForRun(@PathVariable String runId, @PathVariable String taskId) {
        // INTEGRATION/FIX-W* are stage rows now too (see plan()'s own INTEGRATION_STAGE/FIX-W
        // handling) - Packs.taskInstruction()'s generic "Implement task X only" framing is wrong
        // for either (they're not real plan tasks), so each gets its own REAL instruction instead,
        // the exact text RunBench.java itself sends. FIX_INSTRUCTION's "{tasks}" placeholder names
        // the broken wave's own task ids at the real call site - not worth re-deriving here just for
        // this preamble, when the pack file right below it already carries the concrete problem list.
        final String instruction = "INTEGRATION".equals(taskId) ? Packs.INTEGRATION_INSTRUCTION
                : taskId.startsWith("FIX-W") ? Packs.FIX_INSTRUCTION.replace("{id}", taskId).replace("{tasks}", "the merged tasks")
                : Packs.taskInstruction(taskId);
        try {
            final var base = Path.of(props.resultsDir(), runId).toRealPath();
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

    /** #472/#475: the finished-run counterpart of taskDiff() above - see taskAttemptsForRun(). */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/diff")
    public Map<String, Object> taskDiffForRun(@PathVariable String runId, @PathVariable String taskId) {
        return diffAgainstTagForRun(runId, "phase/" + taskId);
    }

    /** the shared core of taskDiff() (above) and sessionDiff() (phase sessions: Definition/Plan/
     *  Parallel plan) - identical boundary logic either way, just parameterized by which tag marks
     *  "done" for the thing being diffed. See taskDiff's own javadoc for why "before" is always the
     *  nearest ANCESTOR phase/* tag, never a literal ^-parent. */
    private Map<String, Object> diffAgainstTag(final UUID id, final String tag) {
        final Map<String, Object> job = queue.get(id);
        return diffAgainstTagForRun(String.valueOf(job.get("run_id")), tag);
    }

    /** #472/#475: the run_id-scoped core of diffAgainstTag() above - see taskAttemptsForRun(). */
    private Map<String, Object> diffAgainstTagForRun(final String runId, final String tag) {
        final Path ws = Path.of(props.workspaceRoot(), runId, "workspace");
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
    // the gentler alternative to the immediate pause above: queues the request rather than acting
    // on it now, so RunBench's own orchestration loop can trigger the EXACT same queue.pause(id)
    // once the task currently in progress actually finishes, instead of mid-task where it can
    // discard real work. A job that is not currently running has no "current task" to wait for, so
    // this just falls back to pausing it outright, the same as the immediate endpoint would.
    @PostMapping("/api/jobs/{id}/pause-after-task") public Map<String, Object> pauseAfterTask(@PathVariable UUID id) {
        final Map<String, Object> job = queue.get(id);
        if (!"running".equals(job.get("status"))) return queue.pause(id);
        pauseAfterTask.request(String.valueOf(job.get("run_id")), id);
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

    /** The "LLM Providers" registry - replaces the old hardcoded ProviderConfig enum. Never returns
     *  the raw api_key value, only whether one is set - the UI's own edit form starts that field
     *  blank regardless, so a round-tripped secret would serve no purpose but to leak it. */
    private static Map<String, Object> providerRow(final com.strgmai.ace.service.agent.ProviderStore.Provider p) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.id());
        row.put("name", p.name());
        row.put("provider_key", p.providerKey());
        row.put("base_url", p.baseUrl());
        row.put("default_model", p.defaultModel());
        row.put("has_api_key", p.apiKey() != null && !p.apiKey().isBlank());
        return row;
    }

    @GetMapping("/api/providers")
    public List<Map<String, Object>> providers() {
        return providerStore.list().stream().map(BenchController::providerRow).toList();
    }

    @PostMapping("/api/providers")
    public Map<String, Object> createProvider(final @RequestBody Map<String, Object> body) {
        final var p = providerStore.create(str(body.get("name")), str(body.get("provider_key")), str(body.get("base_url")),
                str(body.get("api_key")), str(body.get("default_model")));
        return providerRow(p);
    }

    @PutMapping("/api/providers/{id}")
    public Map<String, Object> updateProvider(final @PathVariable UUID id, final @RequestBody Map<String, Object> body) {
        // #api_key is the one field where "absent from the request" and "present but blank" mean
        // different things (leave unchanged vs clear it) - containsKey, not getOrDefault, is the
        // only way to tell them apart; the UI never round-trips the real value back, so it can
        // only ever send a NEW key, omit the field entirely, or send "" to clear it.
        final String apiKey = body.containsKey("api_key") ? str(body.get("api_key")) : null;
        return providerRow(providerStore.update(id, str(body.get("name")), str(body.get("provider_key")), str(body.get("base_url")),
                apiKey, str(body.get("default_model"))));
    }

    @DeleteMapping("/api/providers/{id}")
    public void deleteProvider(final @PathVariable UUID id) {
        // no cascade needed: a job's own reviewer_model/judge_model strings are plain text frozen
        // into its argv at enqueue time, never a live reference back to this table
        providerStore.delete(id);
    }

    /** The external-provider counterpart of GET /api/models above - same OpenAIOkHttpClient-based
     *  listing call, just pointed at a registered provider's own base URL/key instead of the local
     *  model server's. Unlike BenchProperties.upstreamBase(), a provider's base_url already includes
     *  "/v1" (see V7's seed data), so it is used as-is, never concatenated with another "/v1". A
     *  provider with no/invalid key, or whose own API doesn't expose this listing shape, throws -
     *  rethrown below as an IllegalArgumentException (ApiExceptionHandler's own "client-correctable
     *  configuration problem" bucket - a bad/missing key is exactly that) carrying the SDK's own
     *  message, rather than left to fall into the generic catch-all 500, which deliberately strips
     *  the real exception message (#86) and would otherwise hide "the key is wrong" behind an opaque
     *  "internal error" the operator has no way to act on. */
    @GetMapping("/api/providers/{id}/models")
    public List<String> providerModels(final @PathVariable UUID id) {
        final var provider = providerStore.list().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no such provider: " + id));
        final var client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(provider.apiKey() == null || provider.apiKey().isBlank() ? "none" : provider.apiKey())
                .build();
        try {
            return client.models().list().data().stream().map(Model::id).sorted().toList();
        } catch (final com.openai.errors.OpenAIException e) {
            throw new IllegalArgumentException("could not list models for " + provider.name() + ": " + e.getMessage(), e);
        }
    }

    private static String str(final Object o) { return o == null ? null : String.valueOf(o); }

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

    /** #472/#475: the finished-run counterpart of taskTranscript() above - a plain one-shot GET
     *  instead of an SSE stream, since nothing is "live" once a run is done. A fresh TaskTranscript
     *  instance renders the task's whole session (tail-capped) on its very first poll() call - see
     *  that class's own javadoc - so there's no polling loop to run here. */
    @GetMapping("/api/runs/{runId}/tasks/{taskId}/transcript")
    public Map<String, Object> taskTranscriptForRun(@PathVariable String runId, @PathVariable String taskId) {
        final var sessionsDir = Path.of(props.resultsDir(), runId, "sessions");
        final String block = new TaskTranscript().poll(sessionsDir, taskId, TRANSCRIPT_TAIL_CAP_CHARS);
        return Map.of("text", block == null ? "" : block);
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
