package com.strgmai.ace.service.pack;

import com.strgmai.ace.service.plan.PlanTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of runner/pack.py: deterministic context packs for the orchestrated harness mode.
 *  Two parts, kept apart on purpose: stable_pack(...) is byte-identical for every task of a run
 *  and goes into the SYSTEM prompt so the prefix cache serves it; task_pack(...) is the task
 *  entry, the INTERFACES of its dependencies (never bodies), the tree, the previous task's diff
 *  --stat and last failing test output, PROGRESS.md. Every section is capped; the caps are the
 *  reproducibility contract and are recorded with the pack. Also carries the review/trajectory
 *  roles, instructions and tolerant parsers, and the parallelisation-plan step's pack + parser. */
public final class Packs {
    private Packs() {}
    private static final Logger log = LoggerFactory.getLogger(Packs.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- the caps for a 65,536-token window; set_scale() rescales them to the setup's measured window
    static final Map<String, Integer> BASE_CAPS = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("contract", 12000), Map.entry("plan", 4000), Map.entry("task", 2500), Map.entry("iface_file", 6000),
            Map.entry("iface_total", 14000), Map.entry("tree", 120), Map.entry("diffstat", 40), Map.entry("test_tail", 40),
            Map.entry("progress", 3000), Map.entry("handoff_each", 4000), Map.entry("handoff_total", 12000),
            Map.entry("transcript_head", 24000), Map.entry("lessons", 6000)));
    public static final Map<String, Integer> CAPS = new LinkedHashMap<>();
    static { setScale(1.0, Map.of()); }

    /** the task-start checkpoint (stable pack + task pack) must fit the setup's window: every cap
     *  scales with it (floor pack_scale_min); `floors` gives sections that must never be truncated
     *  (the contract: R5 C-25) their real size as a minimum. */
    public static Map<String, Integer> setScale(final double scale, final Map<String, Integer> floors) {
        final double s = Math.max(0.25, Math.min(1.0, scale));
        for (Map.Entry<String, Integer> e : BASE_CAPS.entrySet())
            CAPS.put(e.getKey(), Math.max("tree diffstat test_tail".contains(e.getKey()) ? 10 : 200,
                    Math.max((int) (e.getValue() * s), floors.getOrDefault(e.getKey(), 0))));
        return CAPS;
    }

    static final String HYGIENE_TEMPLATE = """
            ## Working rules ({window}k context: every byte of tool output is paid on every turn)
            - Never dump a whole file: locate first (`grep -n`, `find -maxdepth 2`), then read a line range.
            - Cap noisy commands: `... 2>&1 | tail -40` or `| grep -iE "error|fail|exception" | head -30`. No `ls -R`, no `find` without `-maxdepth`.
            - Gradle: `./gradlew <task> -q --console=plain 2>&1 | grep -E "^e: |error:|FAILED|BUILD|tests completed" | head -40`; compile first, run one test class, full build last. Read only the failing `<failure>` from `build/test-results/test/*.xml`.
            - Java compiler errors are `path:line: error:`; Kotlin's are `e: path:line:col`.
            - Money: `BigDecimal` only, explicit scale + `RoundingMode`, compare with `compareTo`.
            - Spring: slice tests over `@SpringBootTest` where a DB is not needed; for a full HTTP integration test use `@SpringBootTest(webEnvironment=RANDOM_PORT)` with an injected `TestRestTemplate` (already on the test classpath via `spring-boot-starter-test`) or a plain `RestTemplate` to `http://localhost:${port}` - do NOT remove `spring-boot-starter-test` or its autoconfigure.
            - Do not re-run a command whose output you already have; do not `cat` a file you just wrote.
            - Delete exploratory stubs, dead helpers, and unused scaffolding before considering a file done - a method that returns null/does nothing and is never called is not finished work. It is a real, found-live cost (wasted turns at best, a compile risk at worst), not a harmless leftover.
            - During iterative debugging, run a SCOPED test (`./gradlew :module:test --tests 'SpecificClass'`), not the full suite - a full-suite run recompiles and re-resolves everything else too, and most of that result is unrelated to the one failure you are chasing. Reserve a full `./gradlew build`/`test` run for final verification, once the specific test you are fixing is already green on its own.""";

    private static String HYGIENE = HYGIENE_TEMPLATE.replace("{window}", "65");

    /** the working rules name the run's measured window (R5 C-16); byte-stable within a run. */
    public static String setWindow(final int usableContext) {
        HYGIENE = HYGIENE_TEMPLATE.replace("{window}", String.valueOf(Math.max(1, usableContext / 1000)));
        return HYGIENE;
    }
    public static String hygiene() { return HYGIENE; }

    /** #403: operator-curated, cross-run gotchas - found live, repeatedly, on this task suite -
     *  that cost a judge intervention (or worse) to catch after the fact. Unlike the per-run
     *  lessons file (#404, dynamically populated by the judge DURING a run and scoped to that run
     *  only), this is static and ships in every run from the moment it's added here; a developer
     *  adds an entry when a new gotcha is found, the same way HYGIENE_TEMPLATE above is maintained. */
    static final String KNOWN_GOTCHAS = """
            ## Known gotchas (apply proactively - found live, repeatedly, on this task suite)
            - PostgreSQL JDBC + `Instant`: a bare `Instant` passed to `JdbcTemplate`'s untyped `setObject` fails on real PostgreSQL ("Bad value for type timestamp") even though it works on the H2 fallback. Convert with `java.sql.Timestamp.from(instant)` before binding.
            - `JdbcTemplate` insert + generated key: use the `update(PreparedStatementCreator, KeyHolder)` two-argument overload, not the varargs `update(String, Object...)` overload with a `KeyHolder` appended as if it were a bind parameter - the varargs form silently binds the `KeyHolder` itself and throws `NotSerializableException` on H2.
            - H2 database triggers: H2's embedded-Java trigger syntax (`CREATE TRIGGER ... AS 'void fire(...)'`) is fragile and version-sensitive. Prefer enforcing invariants (e.g. append-only/immutable tables) at the application layer instead of a cross-database DDL trigger, unless you have already confirmed the exact working syntax against this project's own H2 version.
            - In-process test mock servers: never hardcode a port for a mock HTTP server used in tests. Bind to port 0 (an OS-assigned ephemeral port) and read back the actual bound port - a hardcoded port collides with any other test class doing the same thing once tests run in parallel.
            - macOS shell tools: `cat -A` is a GNU coreutils flag and fails on macOS's BSD `cat`; use `cat -v` instead, which both support.
            - H2 vs PostgreSQL SQL dialect gaps: `ON CONFLICT DO NOTHING` is PostgreSQL-only and breaks H2 (use `WHERE NOT EXISTS` guards instead); a `LEFT JOIN (SELECT ... GROUP BY ...) alias` derived-table form is rejected by H2 (keep to flat, non-nested queries); H2's `INFORMATION_SCHEMA` uppercases identifiers and reports NULL for precision/scale fields PostgreSQL populates - write backend-agnostic, case-insensitive assertions, never assume one backend's metadata shape.
            - Fixed-length ID columns + generated test UUIDs: if a column is sized to exactly fit a bare UUID (e.g. `VARCHAR(36)`), never prefix or suffix a generated test id - it silently overflows the column/constraint, and the failure can be masked as a misleadingly successful response (e.g. a 200 with no actual effect) rather than an obvious error. Use a bare UUID string for test fixture ids.
            - Jackson JSON null handling: a JSON `null` parsed via `ObjectMapper.readTree(...)`'s tree API is a `NullNode`, not a Java `null` reference. Check `node.isNull()` (or its node type), never `node == null`, when asserting a field is absent/null in a response body.
            - Gradle dependency-lock staleness: this kind of multi-module build often uses dependency locking. Any new or changed dependency in any subproject requires re-running `./gradlew --write-locks` (over a task that resolves the full graph, including `test`) and committing the updated lockfiles - otherwise the very next build fails at the lock-state check before any code even compiles.
            - Spring `@TestConfiguration` bean-name collisions: a `@TestConfiguration`'s `@Primary` bean sharing a name with a production `@Configuration` bean needs `@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true"})`, or the context fails to start with `BeanDefinitionOverrideException`.
            - CGLIB + Kotlin (only if implementing in Kotlin): a Spring-proxied bean (`@Repository`, `@Transactional`, etc.) needs its methods marked `open` - Kotlin methods are final by default, and CGLIB's Objenesis-constructed proxy shell cannot override a final method, leaving constructor-initialized fields silently null on the proxy's own copy (a runtime NPE on first use, not a compile error).
            - Testing a scheduled/periodic job (`@Scheduled`, a background reaper, a TTL eviction loop): never test it by advancing a fake `Clock` and waiting for the real scheduler to actually tick - the two race, especially under parallel test execution, and the result is flaky, hard-to-reproduce failures. Expose a manually-triggerable method (e.g. `evict()`/`tick()`) that both the real scheduled job and the test call directly; the test advances the fake clock then calls that method synchronously, with no dependency on the scheduler's own timing at all.""";

    static final List<String> SKIP = List.of(".git", "build", "node_modules", ".gradle", ".gradle-cache", "target", ".idea", "dist", "out");

    static String cap(String text, int n) { return text.length() <= n ? text : text.substring(0, n) + "\n… [truncated at " + n + " chars]"; }

    /** keep the NEWEST part (PROGRESS.md grows at the bottom — C-15) */
    static String capTail(String text, int n) { return text.length() <= n ? text : "… [" + (text.length() - n) + " older chars omitted]\n" + text.substring(text.length() - n); }

    static String linesCap(final String text, final int n) {
        final String[] ls = text.split("\n", -1);
        return String.join("\n", Arrays.asList(ls).subList(0, Math.min(n, ls.length)))
                + (ls.length > n ? "\n… [" + (ls.length - n) + " more lines]" : "");
    }

    /** the frozen contract of task/PROMPT.md (from its '## Frozen API contract' heading on); the
     *  whole prompt when it has no such heading. The rung's PLANNING protocol is stripped: in
     *  orchestrated mode the plan already exists and is read-only (C-11); the rung's monolithic
     *  budget text is dropped (it contradicts the per-task Budget block — R4 C-20). */
    public static String contractSection(final String promptText) {
        final int i = promptText.indexOf("## Frozen API contract");
        String txt = i >= 0 ? promptText.substring(i) : promptText;
        final var out = new StringBuilder();
        for (String l : txt.split("\n"))
            if (!(l.contains("Protocol for this rung") || (l.contains("IMPLEMENTATION_PLAN.md") && !l.contains("read-only")))) out.append(l).append('\n');
        txt = Pattern.compile("^(#[^\\n]*?)\\s*\\((?:total )?budget[^)]*\\)", Pattern.MULTILINE).matcher(out.toString()).replaceFirst("$1");
        return cap(txt, CAPS.get("contract"));
    }

    public static String planOutline(final List<PlanTask> tasks) {
        final var b = new StringBuilder();
        for (PlanTask t : tasks) {
            b.append("- ").append(t.id).append(" — ").append(t.title).append(": ")
                    .append(t.goal == null ? "" : t.goal.substring(0, Math.min(160, t.goal.length())));
            if (t.deps != null && !t.deps.isEmpty()) b.append(" (after ").append(String.join(", ", t.deps)).append(")");
            b.append('\n');
        }
        return cap(b.toString(), CAPS.get("plan"));
    }

    public static String stablePack(final String promptText, final List<PlanTask> tasks) {
        return String.join("\n\n", List.of(
                "# Benchmark context (identical for every task of this run)",
                "You are implementing ONE task of a multi-task plan at a time. Other tasks are done in separate sessions; do not start them. The plan in docs/IMPLEMENTATION_PLAN.md already exists and is READ-ONLY: do not rewrite it. Everything you need to know about the system is below; the current task follows in the user message.",
                "## Specification and frozen contract\n" + contractSection(promptText),
                "## The whole plan (ids and goals only)\n" + planOutline(tasks),
                KNOWN_GOTCHAS,
                HYGIENE));
    }

    public static String repoTree(final Path ws, final int depth) {
        final List<String> out = new ArrayList<>();
        try (var walk = Files.walk(ws, depth)) {
            walk.forEach(p -> {
                if (p.equals(ws)) return;
                final Path rel = ws.relativize(p);
                if (rel.getNameCount() > 0 && (SKIP.contains(rel.getName(0).toString()) || rel.getName(0).toString().startsWith("."))) return;
                if (rel.getNameCount() > 0 && rel.getName(rel.getNameCount() - 1).toString().startsWith(".")) return;
                out.add(rel.toString());
            });
        } catch (IOException e) { log.warn("could not walk {} for the repo tree pack: {}", ws, e.toString()); }
        Collections.sort(out);
        return linesCap(String.join("\n", out), CAPS.get("tree"));
    }

    static final Pattern SIG_RE = Pattern.compile("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public|protected|open|override|fun|interface|class|record|enum|abstract|sealed|data class)\\b[^;{=]*", Pattern.MULTILINE);

    /** public surface of a source file: package, type headers, public members. Migrations and
     *  OpenAPI are interfaces and go whole; build scripts and config are summarised to their
     *  plugin/dependency/port lines. */
    public static String signatures(final String path, final String text) {
        final String low = path.toLowerCase();
        if (low.endsWith(".sql") || low.contains("openapi") || (low.endsWith(".yaml") || low.endsWith(".yml")) && path.toLowerCase().contains("api"))
            return cap(text, CAPS.get("iface_file"));
        if (low.endsWith(".gradle") || low.endsWith(".kts") || low.endsWith(".properties") || low.endsWith(".yaml") || low.endsWith(".yml") || low.endsWith(".json")) {
            final List<String> keep = new ArrayList<>();
            for (String l : text.split("\n"))
                if (Pattern.compile("plugins|id ['\"]|implementation|runtimeOnly|testImplementation|version|include|rootProject|server:|port:|datasource|url:|flyway|distributionUrl").matcher(l).find())
                    keep.add(l.stripTrailing());
            return cap(String.join("\n", keep.subList(0, Math.min(40, keep.size()))), CAPS.get("iface_file") / 4);
        }
        final List<String> keep = new ArrayList<>();
        for (String line : text.split("\n")) {
            final String s = line.strip();
            if (s.startsWith("package ") || List.of("@Entity", "@Table", "@RestController", "@RequestMapping", "@GetMapping", "@PostMapping", "@Service", "@Repository").stream().anyMatch(s::startsWith)) keep.add(s);
            else if (SIG_RE.matcher(line).find() && !(s.startsWith("private") || s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")))
                keep.add(line.stripTrailing().replaceAll("\\s*\\{\\s*$", ""));
            else if (Pattern.compile("^\\s*@Column|^\\s*@Id\\b|^\\s*@JoinColumn").matcher(s).find()) keep.add(s);
        }
        return cap(String.join("\n", keep), CAPS.get("iface_file"));
    }

    /** files changed by the task's dependencies (from their snapshot diffs) -> public surface only */
    public static String dependencyInterfaces(final Path ws, final PlanTask task, final Map<String, String[]> snapshots, final Set<String> done) {
        final List<String> files = new ArrayList<>();
        for (String d : task.deps == null ? List.<String>of() : task.deps) {
            if (!done.contains(d) || !snapshots.containsKey(d)) continue;
            final String[] s = snapshots.get(d);
            for (String f : git(ws, "diff", "--name-only", s[0] + ".." + s[1]).split("\n"))
                if (!f.isBlank() && Files.isRegularFile(ws.resolve(f)) && !files.contains(f)) files.add(f);
        }
        for (String pat : List.of("**/openapi*.y*ml", "**/api*.y*ml", "**/db/migration/*.sql"))   // canonical order (R4 C-21)
            for (Path p : com.strgmai.ace.service.oracle.checks.StructureChecks.glob(ws, pat)) {
                final String rel = ws.relativize(p).toString();
                if (!files.contains(rel)) files.add(rel);
            }
        final boolean hasSnapDeps = task.deps != null && task.deps.stream().anyMatch(snapshots::containsKey);
        if (task.deps != null && !task.deps.isEmpty() && !hasSnapDeps)
            for (Path p : com.strgmai.ace.service.oracle.checks.StructureChecks.glob(ws, "**/src/main/**/*.*")) {
                final String rel = ws.relativize(p).toString();
                if (!files.contains(rel)) files.add(rel);
            }
        final var parts = new StringBuilder();
        int total = 0;
        for (String f : files) {
            if (!List.of(".java", ".kt", ".kts", ".gradle", ".yaml", ".yml", ".sql", ".json", ".properties").stream().anyMatch(f::endsWith)) continue;
            String txt;
            try { txt = Files.readString(ws.resolve(f)); }
            catch (IOException e) { log.debug("could not read {} for dependency interfaces: {}", f, e.toString()); continue; }
            final String sig = signatures(f, txt);
            if (sig.isBlank()) continue;
            final String block = "### " + f + "\n```\n" + sig + "\n```";
            if (total + block.length() > CAPS.get("iface_total")) { parts.append("… [").append(files.size()).append(" more files omitted by the interface cap]"); break; }
            parts.append(block).append('\n');
            total += block.length();
        }
        return parts.isEmpty() ? "(no dependency files yet)" : parts.toString();
    }

    /** #572, found live: this used to be a raw `new ProcessBuilder(cmd).start()` +
     *  `p.waitFor()` BEFORE ever reading `p.getInputStream()` - the exact read-after-wait deadlock
     *  DockerService.proc()'s own javadoc exists to prevent ("read-after-wait deadlocks on >64KB":
     *  the child blocks on write() once the OS pipe buffer fills, the parent blocks on waitFor()
     *  forever, since nothing is draining the pipe to let the child's write() complete). Hung LIVE
     *  in production (2026-10-29, job 44484604/T1->T2): the agent had committed an entire
     *  third-party vendor directory (an unpacked Gradle distribution, 20,895 files) into the
     *  workspace repo, so this call's own "diff --stat"/"diff --name-only" output for the next
     *  task's pack vastly exceeded the pipe buffer - the whole run stalled indefinitely with no
     *  error, timeout, or crash, silently burning budget on every later task's prompt build (every
     *  taskPack() call needs this for its own "previous task changed" section). Every OTHER git
     *  call site in this codebase already goes through DockerService.sh() (RunBench.java/
     *  RunBenchSupport.java, dozens of call sites) - this was the one remaining holdout with its
     *  own hand-rolled, buggy duplicate. 20s matches the timeout already used for RunBench.java's
     *  own equivalent diff --name-only/rev-parse calls (lines 2358/2383/2392) - plenty for a
     *  legitimate diff, short enough to fail fast instead of hanging the whole run again. */
    static String git(final Path ws, final String... args) {
        final List<String> cmd = new ArrayList<>(List.of("git", "-C", ws.toString()));
        cmd.addAll(List.of(args));
        final var r = com.strgmai.ace.service.docker.DockerService.sh(20, cmd.toArray(String[]::new));
        if (r.rc() != 0)
            // an empty result here silently produces a dependency-interfaces pack missing the
            // files that actually changed - the agent then sees stale/absent context, not an error
            log.warn("`git {}` in {} failed (rc={}): {}", String.join(" ", args), ws, r.rc(), r.out());
        return r.rc() == 0 ? r.out() : "";
    }

    static final Pattern TEST_CMD = Pattern.compile("gradle|gradlew|pytest|npm test|mvn");

    /** the LAST test/build run the agent made in a session, green or red, as (label, tail). Tool
     *  results are paired with their tool call by id (a `read` of a test file is not a test run — C-7). */
    public static String[] lastTestOutput(final Path sessionPath) {
        if (sessionPath == null || !Files.isRegularFile(sessionPath)) return new String[]{"", ""};
        final Map<String, String> cmds = new HashMap<>();
        String[] last = {"", ""};
        try {
            for (String line : Files.readAllLines(sessionPath)) {
                if (line.isBlank()) continue;
                JsonNode r;
                try { r = JSON.readTree(line); } catch (Exception e) { log.debug("could not parse session line as JSON, skipping it: {}", e.toString()); continue; }
                final JsonNode msg = r.path("message");
                if (!"message".equals(r.path("type").asText())) continue;
                if ("assistant".equals(msg.path("role").asText()))
                    for (JsonNode c : msg.path("content"))
                        if ("toolCall".equals(c.path("type").asText()) && "bash".equals(c.path("name").asText()))
                            cmds.put(c.path("id").asText(), c.path("arguments").path("command").asText(""));
                else if ("toolResult".equals(msg.path("role").asText()) && "bash".equals(msg.path("toolName").asText())) {
                    final String cmd = cmds.getOrDefault(msg.path("toolCallId").asText(), "");
                    if (!TEST_CMD.matcher(cmd).find()) continue;
                    final String txt = msg.path("content").path(0).path("text").asText("");
                    boolean red = Pattern.compile("FAILED|BUILD FAILED|error:|Exception|tests? failed").matcher(txt).find()
                            || msg.path("isError").asBoolean(false);
                    last = new String[]{red ? "red" : "green", linesCap(txt.strip(), CAPS.get("test_tail"))};
                }
            }
        } catch (IOException e) { log.warn("could not read session {} for the last-test-output pack: {}", sessionPath, e.toString()); }
        return last;
    }

    /** #304, found live: `handoffs` accumulates in plan order, not in order of relevance to the
     *  task being packed - taking however many whole notes fit a total cap starting from the front
     *  of that list means an early, size-heavy, UNRELATED task's note can starve out the ones this
     *  task actually depends on. When `deps` is non-empty, only those tasks' own notes are even
     *  candidates; a task with no declared deps at all (e.g. the synthetic INTEGRATION task, which
     *  has no single dependency to scope to) still sees whatever fits from the full list. */
    static List<String[]> relevantHandoffs(final List<String[]> handoffs, final List<String> deps) {
        return deps == null || deps.isEmpty() ? handoffs : handoffs.stream().filter(h -> deps.contains(h[0])).toList();
    }

    /** #304: relevantHandoffs() scopes this down to the task's own direct deps - typically a
     *  handful of notes, not the whole run's worth - so the total cap below rarely bites in that
     *  case. But relevantHandoffs() falls back to the FULL, unfiltered list for a task with no
     *  declared deps at all (e.g. the synthetic INTEGRATION task) - found live (#309): with
     *  handoff_total removed entirely, INTEGRATION (which runs last, after every other task has
     *  already left a note) got every note ever recorded, each capped individually
     *  (handoff_each) but with no bound on their sum. Same running-total/break-with-omitted-
     *  marker shape dependencyInterfaces()'s own iface_total cap already uses. Extracted (package-
     *  private, tested directly) rather than only reachable through the full taskPack() call,
     *  same reasoning this file's other pure helpers already follow. */
    static String handoffBlocks(final List<String[]> relevant) {
        final var blocks = new StringBuilder();
        int total = 0;
        for (int i = 0; i < relevant.size(); i++) {
            final String[] h = relevant.get(i);
            final String block = "### Handoff from " + h[0] + "\n" + cap(h[1], CAPS.get("handoff_each")) + "\n";
            if (total + block.length() > CAPS.get("handoff_total")) {
                blocks.append("… [").append(relevant.size() - i).append(" more handoffs omitted by the handoff cap]");
                break;
            }
            blocks.append(block);
            total += block.length();
        }
        return blocks.toString();
    }

    /** the per-task pack: entry, dependency interfaces, tree, parallel-with notes, merge conflicts,
     *  previous task's diffstat + harness verification + last test run, handoffs, lessons,
     *  PROGRESS.md tail. */
    public static String taskPack(Path ws, PlanTask t, List<PlanTask> tasks, Map<String, String[]> snapshots, Set<String> done,
                                  String prevTask, Path prevSession, List<String[]> handoffs, String lessons, String verified,
                                  List<String[]> mergeConflicts, List<String> parallelWith) {
        final var entry = new StringBuilder();
        for (String k : List.of("id", "title", "goal", "services", "acceptance"))
            entry.append("- ").append(k).append(": ").append(field(t, k)).append('\n');
        entry.append(t.deps == null || t.deps.isEmpty() ? "- depends on: nothing" : "- depends on: " + String.join(", ", t.deps));
        List<String> parts = new ArrayList<>(List.of(
                "# Current task: " + t.id + " — " + t.title,
                cap(entry.toString(), CAPS.get("task")),
                "## Interfaces of what already exists (dependencies; signatures and contracts only — read the files for bodies)\n" + dependencyInterfaces(ws, t, snapshots, done),
                "## Repository tree\n```\n" + repoTree(ws, 8) + "\n```"));
        if (parallelWith != null && !parallelWith.isEmpty())
            parts.add("## Working in parallel\nTasks " + String.join(", ", parallelWith) + " are being implemented AT THE SAME TIME by other sessions in separate copies of this repository; "
                    + "the harness merges all copies afterwards. Touch only the files your task needs, do not edit files that belong to those tasks, and do not rename or move shared files (build scripts, migrations, application config) — add to them at the end.");
        if (mergeConflicts != null && !mergeConflicts.isEmpty()) {
            final var files = new TreeSet<String>();
            mergeConflicts.forEach(c -> files.add(c[1]));
            parts.add("## Merge conflicts to resolve\nThe harness merged parallel tasks and kept their conflicts with `<<<<<<<`/`>>>>>>>` markers in: "
                    + String.join(", ", files.stream().limit(20).toList()) + ". Resolve them first (the build fails until you do).");
        }
        if (prevTask != null && snapshots.containsKey(prevTask)) {
            final String[] s = snapshots.get(prevTask);
            parts.add("## Previous task (" + prevTask + ") changed\n```\n" + linesCap(git(ws, "diff", "--stat", s[0] + ".." + s[1]), CAPS.get("diffstat")).strip() + "\n```");
            if (verified != null) parts.add("## Harness verification after " + prevTask + "\n" + verified);
            final String[] lt = lastTestOutput(prevSession);
            if (!lt[1].isBlank()) parts.add("## Last test/build run the agent made in " + prevTask + " (" + lt[0] + ")\n```\n" + lt[1] + "\n```");
        } else if (verified != null) parts.add("## Harness verification\n" + verified);
        if (handoffs != null && !handoffs.isEmpty()) {
            final String blocks = handoffBlocks(relevantHandoffs(handoffs, t.deps));
            if (!blocks.isBlank()) parts.add("## Notes left by earlier tasks\n" + blocks);
        }
        // #404: unlike handoffs (scoped to this task's own declared deps), lessons are NOT
        // filtered by dependency - a judge catching a different, unrelated task off track found
        // a fact worth knowing regardless of the dependency graph (see #398/#402: three separate
        // tasks with no dependency relationship to each other each independently rediscovered the
        // same JDBC binding bug this run).
        if (lessons != null && !lessons.isBlank())
            parts.add("## Lessons learned earlier in this run (apply proactively - the judge caught another task off track on this)\n" + cap(lessons, CAPS.get("lessons")));
        final Path pp = ws.resolve("docs/PROGRESS.md");
        if (Files.exists(pp)) { try { parts.add("## docs/PROGRESS.md\n" + capTail(Files.readString(pp), CAPS.get("progress"))); } catch (IOException e) { log.debug("could not read {} for the pack: {}", pp, e.toString()); } }
        return String.join("\n\n", parts);
    }

    static String field(final PlanTask t, String k) {
        return switch (k) {
            case "id" -> t.id; case "title" -> t.title == null ? "" : t.title; case "goal" -> t.goal == null ? "" : t.goal;
            case "services" -> t.services == null ? "" : t.services; case "acceptance" -> t.acceptance == null ? "" : t.acceptance;
            default -> "";
        };
    }

    // ---- the instruction texts (verbatim ports)
    public static String taskInstruction(final String id) {
        return "You already have this task's own goal, acceptance criterion, dependency interfaces, and any prior tasks' handoff notes "
                + "right below - prefer writing code over re-reading them again; re-exploring what you already have in front of you is a "
                + "real, found-live cost, not a safe way to start. "
                + "Implement task " + id + " only. Do not start other tasks. Write tests that prove its acceptance criterion and run them; "
                + "your tests must use DISCRIMINATING inputs - non-default values a hardcoded or ignored-input implementation would get "
                + "wrong (e.g., POST a non-USD currency and assert THAT currency echoes back, not the default; assert a non-zero, "
                + "non-default quantity round-trips) - happy-path defaults prove nothing. "
                + "Stop when they pass OR when your budget is nearly spent (see the Budget section: check `date` between steps). "
                + "Before you stop, append exactly one line to docs/PROGRESS.md: `" + id + " | done | <how verified>` when the acceptance "
                + "criterion holds, `" + id + " | partial | <what works, what remains>` when it does not yet, or `" + id + " | blocked | <why>`.";
    }
    /** #398: appended to the p1_plan instruction only when a run (or a restarted p1_plan's own
     *  override) set a granularity level - an unrecognized/blank level returns "" so the caller's
     *  own blank check already decides whether to append anything at all. Examples mirror the
     *  dropdown's own tooltip text, kept non-OOP-specific since ladder rungs span migrations and
     *  sagas as much as endpoints and classes. */
    public static String taskGranularityInstruction(final String level) {
        return switch (level) {
            case "fine" -> "Favor FINE-grained subtasks: the smallest sensible unit plus its test - e.g. one "
                    + "method, one migration script, or one small fix per subtask.";
            case "medium" -> "Favor MEDIUM-grained subtasks: a class, module, or single endpoint plus its tests per subtask.";
            case "coarse" -> "Favor COARSE-grained subtasks: a full feature slice end-to-end (e.g. a saga across "
                    + "services, or a complete HTTP contract) plus its tests per subtask.";
            case null, default -> "";
        };
    }
    public static String wrapupInstruction(final String id) {
        return "Time is up for " + id + ". Do NOT write or edit any more code and do not run builds or tests. Append exactly one line to "
                + "docs/PROGRESS.md: `" + id + " | done | <how verified>` if the acceptance criterion holds, otherwise "
                + "`" + id + " | partial | <what works, what remains, last test result>` or `" + id + " | blocked | <why>`. Then stop.";
    }
    /** Unlike wrapupInstruction above, this fires while real budget still remains - the previous
     *  turn ended with no tool call and no status, which could mean the task is genuinely done and
     *  the agent simply forgot the status line, or that it lost the thread mid-reasoning. Deliberately
     *  does NOT ban further tool use: there is room left to actually finish, not just report. */
    public static String reEngageInstruction(final String id) {
        return "Your last turn for " + id + " ended with no tool call and no docs/PROGRESS.md status line - you may be "
                + "genuinely done, or you may have lost the thread. You still have budget left: if the acceptance "
                + "criterion already holds, verify it and append the status line now (`" + id + " | done | <how verified>`); "
                + "if it does not, continue the task - use a tool for your very next step.";
    }
    public static String fallbackStatus(final String id, final String tail) {
        return id + " | unfinished | budget exhausted before a status was written" + (tail == null || tail.isBlank() ? "" : tail);
    }
    public static final String MONO_STATUS_INSTRUCTION = "Before you stop, append a final section `## Status` to docs/PROGRESS.md with one line per plan subtask: "
            + "`<id> | done | <how verified>`, `<id> | partial | <what works, what remains>` or `<id> | blocked | <why>`.";
    public static final String MONO_WRAPUP_INSTRUCTION = "Time is up. Do NOT write or edit any more code and do not run builds or tests. Append a final section `## Status` to "
            + "docs/PROGRESS.md with one line per plan subtask: `<id> | done | <how verified>`, `<id> | partial | <what works, what "
            + "remains, last test result>` or `<id> | blocked | <why>`. Then stop.";
    public static final String DOCKER_NOTE = "## Docker\n- Docker's availability depends entirely on whatever the operator already has running on "
            + "this host - the harness does not start or stop Docker Desktop for you. Check once (e.g. `docker info`) before relying on it.\n"
            + "- If `docker` does not succeed within ~60s the daemon is unavailable on this host (it competes with the model for "
            + "memory): do NOT keep retrying, run `open -a Docker`, or spawn your own backend - write the Dockerfile/compose.yml from your "
            + "own knowledge, verify behaviour against the provided SPRING_DATASOURCE_*/H2 datasource, and rely on the grader (it builds "
            + "and runs the containers on a clean checkout after your session).";
    public static String budgetSection(final String taskId, final String startLocal, final String deadlineLocal, final int minutes) {
        return "## Budget for " + taskId + "\n- Started " + startLocal + ", hard deadline **" + deadlineLocal + "** (local time, " + minutes
                + " min). The harness stops the session at the deadline.\n- Run `date` between steps. When fewer than 5 minutes remain, stop coding and write the docs/PROGRESS.md status line described in the instruction.";
    }
    /** wall==0 (the system-wide "0 = no budget" convention): telling the agent it has a "hard
     *  deadline" of right now (minutes=0) would be actively misleading - there genuinely is none. */
    public static String unlimitedBudgetSection(final String taskId) {
        return "## Budget for " + taskId + "\n- No wall-clock deadline for this session (the operator configured an unlimited budget).\n"
                + "- Still write the docs/PROGRESS.md status line described in the instruction once the task is genuinely done.";
    }
    public static final String INTEGRATION_INSTRUCTION = "Run the complete test suite of every service from the repository root and fix every failure. Check that "
            + "docker-compose.yml, the Dockerfiles and the healthchecks are consistent with the code and that `docker compose build` "
            + "and `docker compose up` work when Docker is available (it may not be available on this host at all - see the Docker "
            + "section's ~60s fallback and do not get stuck retrying; the grader will run `docker compose build` on a clean "
            + "checkout WITHOUT build outputs, so every image must build the application inside the image - a Dockerfile that COPYs a "
            + "host-built jar fails). Stop when everything is green OR when your budget is nearly spent (see the Budget section; "
            + "check `date`). Before you stop, append exactly one line to docs/PROGRESS.md: `INTEGRATION | done | <evidence>` "
            + "or `INTEGRATION | partial | <what remains>`.";
    // #245: found live - this session continues the task's own session (continueSession=true) and
    // already has everything it needs, but kept re-deriving it from scratch anyway: re-running
    // tests, reading git history, re-reading source files, burning most of its budget before ever
    // writing the file. Saying so explicitly, not just implying it via "then stop".
    public static String handoffInstruction(final String id) {
        return "Write the file handoff/" + id + ".md for a colleague who will implement the remaining tasks in a fresh session: the "
                + "decisions you made, gotchas, conventions, and facts they need (endpoints, class names, config keys). You already "
                + "have everything you need from this task's own session - do not run tests, inspect git history, or re-read source "
                + "files to double-check yourself; write directly from what you already know. At most 600 words, no code listings. "
                + "Then stop.";
    }
    // #307: the budget-exhaustion rescue step's own ask - fired once, in a reconnect whose own
    // loaded history was already compacted to its most recent turns (see ReferenceAgent's
    // compactOnLoadKeepRecentTurns), with a fresh, separate budget for this session specifically.
    // Names the CURRENT, concrete failure explicitly (the harness's own fresh verification run) -
    // the model's own last tool call before running out of budget was almost certainly not "run the
    // tests", so nothing in its own trajectory reliably surfaces this on its own - and tells it not
    // to re-explore, mirroring handoffInstruction()'s own "you already have everything you need".
    public static String rescueInstruction(final String id, final String reason, final String verificationSummary) {
        return "Your previous session on " + id + " " + reason + " before finishing. You now have a new, separate "
                + "budget for this session specifically to close the gap - do not re-read files you already know, do not re-derive "
                + "context you already have, do not restart the approach from scratch.\n\n"
                + "The harness just ran the test suite again. Current state:\n" + verificationSummary + "\n\n"
                + "Fix exactly this - the smallest change that makes it pass - then stop. If you genuinely cannot finish, update the "
                + "docs/PROGRESS.md status line honestly (`" + id + " | partial | <what remains and why>`) and stop; do not keep "
                + "spending budget once you have made a real attempt.";
    }
    // #417: structured_eviction's own one-shot summarization ask - asks for a fixed outline instead
    // of free prose specifically because a narrative summary collapses the causal/provenance
    // structure a long session's own trajectory carries (see issue #417's research) - the same
    // reason ACE's own inter-task handoff-notes/lessons.md are already structured documents, not
    // prose summaries.
    public static final String STRUCTURED_EVICTION_INSTRUCTION = "The conversation above is being compacted to save context. "
            + "Write a structured summary of it under exactly these six headings, each a short bullet list (omit a heading only "
            + "if genuinely empty):\n\n"
            + "## LANDED\nWhat is actually done and verified (tests passing, files written).\n\n"
            + "## STATE\nThe current state of the workspace right now - what exists, what doesn't yet.\n\n"
            + "## INVARIANTS\nFacts and constraints that must keep holding (API shapes, config values, conventions discovered).\n\n"
            + "## TASKS\nWhat is still open or in progress.\n\n"
            + "## DO NEXT\nThe single most useful next step.\n\n"
            + "## DECISIONS\nNon-obvious choices made and why, so they are not silently re-litigated.\n\n"
            + "Write nothing except this summary - no preamble, no code listings, no apology.";
    public static final String FIX_INSTRUCTION = """
            The harness merged the parallel tasks {tasks} into this repository and found the problems listed above. Fix them: resolve every
            conflict marker, make the build and the complete test suite green from the repository root, and keep every task's behaviour as
            its docs/PROGRESS.md status describes. Do not start new tasks. Stop when `gradle test` is green OR when your budget is nearly spent
            (see the Budget section; check `date`). Before you stop, append exactly one line to docs/PROGRESS.md:
            `{id} | done | <what was fixed>` or `{id} | partial | <what remains>`.""";

    // ---------------------------------------------------------------- self-review (end-of-run)
    public static final String REVIEW_ROLE = """
            # Role: senior code reviewer (fresh eyes)
            You are a senior backend engineer reviewing a service written by someone else against a frozen API contract. You did not
            write this code and have no stake in it. Your review will be compared with an independent black-box test suite that you
            cannot see, so the most valuable thing you can do is be ACCURATE: find the real defects, rank them honestly, and give a
            grade that matches what the tests will find. Over-grading and under-grading are both scored against you.

            Rules: read the code (grep/read); run the existing tests if useful; do NOT modify any source, test, build or config file -
            the code is frozen; write only under review/. Prefer specific findings with file:line and a concrete failure scenario over
            style remarks. Contract conformance (status codes, body shapes, decimal strings, timestamp precision, boundary semantics),
            money arithmetic, packaging (does `docker compose up` work from a clean checkout?) and test evidence matter most.""";

    public static final String REVIEW_INSTRUCTION = """
            Review the code in this repository against the specification and contract in the system prompt.

            Deliverables (both mandatory):
            1. `review/SELF_REVIEW.md` - findings ranked most severe first, each with file:line, the failure scenario, and a fix; then a short verdict.
            2. `review/self_review.json` - machine-readable, exactly this shape:
            {"score": <integer PERCENTAGE 0-100 (not a fraction)>, "confidence": <0.0-1.0>, "categories": {...}, "findings": [...], "would_ship": <true|false>}
            Write the JSON with a heredoc or the write tool; validate it parses. Then stop.""";

    /** blind=true: no implementer claims and no harness verdict — the anchoring control for reviewer validation (R4 K-1) */
    public static String reviewPack(final Path ws, final List<PlanTask> tasks, final String verificationText, final boolean blind) {
        final List<String> parts = new ArrayList<>(List.of("# Code under review", "## Repository tree\n```\n" + repoTree(ws, 8) + "\n```"));
        if (tasks != null && !tasks.isEmpty()) parts.add("## The plan the code was built from\n" + planOutline(tasks));
        if (blind) return String.join("\n\n", parts);
        if (verificationText != null) parts.add("## Harness verification (the tests in the repository, run by the harness)\n" + verificationText);
        final Path pp = ws.resolve("docs/PROGRESS.md");
        if (Files.exists(pp)) { try { parts.add("## What the implementer claimed (docs/PROGRESS.md)\n" + capTail(Files.readString(pp), CAPS.get("progress"))); } catch (IOException e) { log.debug("could not read {} for the pack: {}", pp, e.toString()); } }
        return String.join("\n\n", parts);
    }

    public static String reviewSystem(final String promptText) {
        return REVIEW_ROLE + "\n\n## Specification and frozen contract (what the code must satisfy)\n" + contractSection(promptText);
    }

    // which oracle check a free-text finding is about (keyword heuristic for the HIGH-finding precision proxy, R4 K-1)
    static final Map<String, Pattern> FINDING_KEYWORDS = Map.ofEntries(
            Map.entry("F8", Pattern.compile("asOf|as-of|exclusive|boundary|asOfApplied", Pattern.CASE_INSENSITIVE)),
            Map.entry("F2", Pattern.compile("point.in.time|between (a )?buy|replay|holdings at", Pattern.CASE_INSENSITIVE)),
            Map.entry("F1", Pattern.compile("restore|equal sell|holdings after|subtract", Pattern.CASE_INSENSITIVE)),
            Map.entry("F3", Pattern.compile("422|insufficient|balance check|holdings check", Pattern.CASE_INSENSITIVE)),
            Map.entry("F4", Pattern.compile("409|transition|state machine|cancel", Pattern.CASE_INSENSITIVE)),
            Map.entry("F5", Pattern.compile("round.?trip|scale|decimal string|trailing zero", Pattern.CASE_INSENSITIVE)),
            Map.entry("F6", Pattern.compile("contract|openapi|schema|status code|body shape|field name", Pattern.CASE_INSENSITIVE)),
            Map.entry("F7", Pattern.compile("HALF_EVEN|rounding|0\\.005|0\\.015", Pattern.CASE_INSENSITIVE)),
            Map.entry("F9", Pattern.compile("idempoten", Pattern.CASE_INSENSITIVE)),
            Map.entry("C1", Pattern.compile("healthcheck|health check|compose|docker|container", Pattern.CASE_INSENSITIVE)),
            Map.entry("C2", Pattern.compile("/health|readiness", Pattern.CASE_INSENSITIVE)),
            Map.entry("B1", Pattern.compile("compile|does not build|build fail", Pattern.CASE_INSENSITIVE)),
            Map.entry("B2", Pattern.compile("\\btests? (fail|red|do not run|missing)|no tests", Pattern.CASE_INSENSITIVE)),
            Map.entry("B3", Pattern.compile("coverage|assert|mutation|tests? (do|does) not (check|verify)|weak test", Pattern.CASE_INSENSITIVE)),
            Map.entry("M1", Pattern.compile("\\bdouble\\b|\\bfloat\\b")),
            // #112: M2 ("BigDecimal imported where money is handled") had no entry here at all,
            // even though ladder.json scores it on 4 rungs (L1, L2, L3, L3p) - a self-review finding
            // correctly identifying a missing/absent BigDecimal could never be matched to it.
            Map.entry("M2", Pattern.compile("BigDecimal", Pattern.CASE_INSENSITIVE)),
            Map.entry("M3", Pattern.compile("\\.equals\\(|equals on")),
            Map.entry("M4", Pattern.compile("sells? (are|is) (not )?subtract|adding replay", Pattern.CASE_INSENSITIVE)));

    public static List<String> findingCheckIds(final String issue) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : FINDING_KEYWORDS.entrySet())
            if (e.getValue().matcher(issue == null ? "" : issue).find()) out.add(e.getKey());
        Collections.sort(out);
        return out;
    }

    /** tolerant parse of review/self_review.json -> normalised fields, or null ("85%" is 85, a
     *  fraction 0-1 is a percentage in disguise — R4 C-12; severity synonyms collapse to HIGH). */
    public static Map<String, Object> parseSelfReview(final Path path) {
        return parseReviewShape(path, false);
    }

    static Map<String, Object> parseReviewShape(final Path path, final boolean trajectory) {
        if (path == null || !Files.isRegularFile(path)) return null;
        String txt;
        try { txt = Files.readString(path); }
        catch (IOException e) { log.warn("could not read {} (exists but unreadable): {}", path, e.toString()); return null; }
        JsonNode d;
        try { d = JSON.readTree(txt); } catch (Exception e) {
            final Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(txt);
            if (!m.find()) { log.warn("{} is not valid JSON and has no embedded {{...}} block, review discarded: {}", path, e.toString()); return null; }
            try { d = JSON.readTree(m.group(0)); }
            catch (Exception e2) { log.warn("could not parse the embedded JSON block in {}, review discarded: {}", path, e2.toString()); return null; }
        }
        if (!d.isObject()) return null;
        final Double score = pct(d.path("score"));
        if (score == null) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("confidence", num(d.path("confidence"), 0, 1));
        final Map<String, Double> cats = new LinkedHashMap<>();
        d.path("categories").fields().forEachRemaining(e -> { Double v = pct(e.getValue()); if (v != null) cats.put(e.getKey(), v); });
        out.put("categories", cats);
        out.put("would_ship", d.path("would_ship").isBoolean() ? d.path("would_ship").asBoolean() : null);
        final List<Map<String, Object>> findings = new ArrayList<>();
        for (JsonNode f : d.path("findings")) {
            if (!f.isObject()) continue;
            String sev = f.path("severity").asText("").toUpperCase();
            sev = List.of("CRITICAL", "BLOCKER", "SEVERE").contains(sev) ? "HIGH" : List.of("HIGH", "MEDIUM", "LOW").contains(sev) ? sev : "LOW";
            final Map<String, Object> fd = new LinkedHashMap<>();
            fd.put("severity", sev);
            fd.put("file", head(f.path("file").asText(""), 200));
            fd.put("line", f.path("line").isInt() ? f.path("line").asInt() : null);
            fd.put("issue", head(f.path("issue").asText(""), 300));
            fd.put("fix", head(f.path("fix").asText(""), 300));
            if (trajectory && f.path("turns").isArray()) {
                final List<Integer> turns = new ArrayList<>();
                f.path("turns").forEach(t -> turns.add(t.asInt()));
                fd.put("turns", turns);
            }
            findings.add(fd);
        }
        out.put("findings", findings);
        final Map<String, Long> bySev = new LinkedHashMap<>();
        for (String s : List.of("HIGH", "MEDIUM", "LOW")) bySev.put(s, findings.stream().filter(f -> s.equals(f.get("severity"))).count());
        out.put("findings_by_severity", bySev);
        if (trajectory) {
            out.put("wasted_turns_estimate", d.path("wasted_turns_estimate").isInt() ? d.path("wasted_turns_estimate").asInt() : null);
            out.put("would_trust_unsupervised", d.path("would_trust_unsupervised").isBoolean() ? d.path("would_trust_unsupervised").asBoolean() : null);
        }
        return out;
    }

    static Double pct(final JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return null;
        final Double x = num(v, 0, 100);
        if (x != null && x > 0 && x <= 1 && !v.isTextual()) return x * 100;   // a fraction 0-1 is a percentage in disguise
        return x;
    }
    static Double num(final JsonNode v, final double lo, final double hi) {
        double x;
        if (v.isNumber()) x = v.asDouble();
        else if (v.isTextual()) {
            try { x = Double.parseDouble(v.asText().strip().replaceAll("%$", "")); }
            catch (NumberFormatException e) { log.debug("could not parse '{}' as a number: {}", v.asText(), e.toString()); return null; }
        }
        else return null;
        return Math.max(lo, Math.min(hi, x));
    }
    static String head(String s, int n) { return s == null ? "" : s.substring(0, Math.min(n, s.length())); }

    // ---------------------------------------------------------------- trajectory review (end-of-run)
    public static final String TRAJ_ROLE = """
            # Role: senior agent engineer reviewing an autonomous coding agent's TRAJECTORY
            You are reviewing how an autonomous coding agent (an LLM driving read/bash/edit/write tools) went about a task - not the
            code itself, the process: every turn it took, what it ran, what came back, what it cost. You did not run this session
            and have no stake in it. Your assessment will be compared with objective measurements of the same trajectory (repeated
            identical calls, edit/read ping-pong, errors, stalls, tasks over budget, "done" claims the harness could not verify),
            so be ACCURATE: over-grading and under-grading are both scored against you.

            Rules: the transcript and the summary are under trajectory/ in the workspace (read them; grep by turn number). Do NOT
            modify any file outside review/. Cite turn numbers in every finding.""";

    public static final String TRAJ_INSTRUCTION = """
            Review the agent's trajectory (trajectory/TRAJECTORY.md, trajectory/summary.json) against the role above.

            Deliverables (both mandatory):
            1. `review/TRAJECTORY_REVIEW.md` - findings ranked most severe first, each citing turn numbers, with what a better agent
               would have done; then a short verdict on the agent as a process.
            2. `review/trajectory_review.json` - exactly this shape:
            {"score": <0-100>, "confidence": <0.0-1.0>, "categories": {...}, "findings": [{"severity": "...", "turns": [...], "issue": "...", "better": "..."}], "wasted_turns_estimate": <int>, "would_trust_unsupervised": <true|false>}
            Validate it parses. Then stop.""";

    public static String trajectoryReviewPack(final Map<String, Object> summary, final String transcriptHead) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        for (String k : List.of("turns", "completion_tokens_total", "tool_calls_total", "tool_profile", "identical_calls_repeated", "edit_read_pingpong",
                "http_errors", "stalled_turns_gt600s", "prompt_tokens_max", "context_drops_gt30pct", "reasoning_share_pct", "first_artifact_turn", "flags", "per_task"))
            if (summary.containsKey(k)) facts.put(k, summary.get(k));
        String json;
        try { json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(facts); }
        catch (Exception e) { log.warn("could not serialize the trajectory summary for the review pack: {}", e.toString()); json = "{}"; }
        return String.join("\n\n", List.of(
                "# Trajectory under review",
                "## Measured summary (trajectory/summary.json)\n```json\n" + json.substring(0, Math.min(6000, json.length())) + "\n```",
                "## Transcript (the first " + CAPS.get("transcript_head") + " characters; the COMPLETE transcript with a per-task turn index is trajectory/TRAJECTORY.md - read the rest with grep/sed by turn number before judging)\n"
                        + cap(transcriptHead == null ? "" : transcriptHead, CAPS.get("transcript_head"))));
    }

    public static Map<String, Object> parseTrajectoryReview(final Path path) {
        return parseReviewShape(path, true);
    }

    // ---------------------------------------------------------------- parallelisation plan (a step of the flow; scored)
    public static final String PARALLEL_PLAN_INSTRUCTION = """
            Read docs/IMPLEMENTATION_PLAN.md (the plan is fixed; do not change it) and decide how its tasks should be scheduled so that
            independent tasks are implemented AT THE SAME TIME by separate sessions, each in its own copy of the repository, merged by the
            harness afterwards. Think about which tasks truly depend on each other's code, which files each task will create or edit, and
            which files are shared (build scripts, migrations, application config) and therefore risky to edit concurrently.

            Deliverables (both mandatory; write no code):
            1. docs/PARALLEL_PLAN.md - the schedule as ordered waves, the file ownership of each task, the shared files and how to avoid
               conflicts on them, and your reasoning.
            2. docs/parallel_plan.json - exactly this shape:
            {"waves": [["T1"], ["T2", "T4"]], "ownership": {"T1": ["account-service/**"]}, "shared_files": ["..."], "rationale": "<one paragraph>"}
            Every task id of the plan appears exactly once; a task may only be in a wave AFTER all the tasks it depends on; ownership paths are
            repository-relative globs. Validate the JSON parses. Then stop.""";

    public static String parallelPlanPack(final List<PlanTask> tasks) {
        return "# Tasks of the plan (ids, goals, declared dependencies)\n" + planOutline(tasks);
    }

    /** tolerant parse of docs/parallel_plan.json -> {waves, ownership, shared_files} or null */
    public static Map<String, Object> parseParallelPlan(final Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        String txt;
        try { txt = Files.readString(path); }
        catch (IOException e) { log.warn("could not read {} (exists but unreadable): {}", path, e.toString()); return null; }
        JsonNode d;
        try { d = JSON.readTree(txt); } catch (Exception e) {
            final Matcher m = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(txt);
            if (!m.find()) { log.warn("{} is not valid JSON and has no embedded {{...}} block, plan discarded: {}", path, e.toString()); return null; }
            try { d = JSON.readTree(m.group(0)); }
            catch (Exception e2) { log.warn("could not parse the embedded JSON block in {}, plan discarded: {}", path, e2.toString()); return null; }
        }
        if (!d.isObject() || !d.path("waves").isArray()) return null;
        final Pattern idNorm = Pattern.compile("^(?:T|ST|Task|Subtask)[- ]?0*(\\d+)$", Pattern.CASE_INSENSITIVE);
        final List<List<String>> waves = new ArrayList<>();
        for (JsonNode w : d.get("waves")) {
            final List<String> wv = new ArrayList<>();
            for (JsonNode t : (w.isArray() ? w : List.of(w))) {   // a bare task id counts as a one-task wave
                final Matcher m = idNorm.matcher(t.asText().strip());
                wv.add(m.find() ? "T" + Integer.parseInt(m.group(1)) : t.asText().strip());
            }
            waves.add(wv);
        }
        final Map<String, List<String>> own = new LinkedHashMap<>();
        d.path("ownership").fields().forEachRemaining(e -> {
            if (e.getKey() != null) {
                final Matcher m = idNorm.matcher(e.getKey().strip());
                final String k = m.find() ? "T" + Integer.parseInt(m.group(1)) : e.getKey();
                final List<String> globs = new ArrayList<>();
                if (e.getValue().isArray()) e.getValue().forEach(g -> globs.add(g.asText()));
                else if (e.getValue().isTextual()) globs.add(e.getValue().asText());   // a bare glob is a one-glob list
                own.put(k, globs);
            }
        });
        final List<String> shared = new ArrayList<>();
        d.path("shared_files").forEach(s -> shared.add(s.asText()));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("waves", waves);
        out.put("ownership", own);
        out.put("shared_files", shared.subList(0, Math.min(50, shared.size())));
        out.put("rationale", head(d.path("rationale").asText(""), 1000));
        return out;
    }

    /** what the merge of a parallel wave broke, for the dedicated fix session */
    public static String fixPack(final Path ws, final Map<String, Object> problems) {
        final List<String> parts = new ArrayList<>(List.of("# Problems after merging a parallel wave"));
        if (problems.get("conflicts") instanceof List<?> cs && !cs.isEmpty()) {
            final var b = new StringBuilder();
            cs.forEach(c -> b.append("- ").append(((String[]) c)[0]).append(": ").append(((String[]) c)[1]).append('\n'));
            parts.add("## Merge conflicts (markers `<<<<<<<`/`>>>>>>>` left in place)\n" + b);
        }
        if (problems.get("verification") != null) parts.add("## Harness verification of the merged tree\n" + problems.get("verification"));
        if (problems.get("overlaps") instanceof List<?> os && !os.isEmpty()) {
            final var b = new StringBuilder();
            os.forEach(o -> { Object[] x = (Object[]) o; b.append("- ").append(x[0]).append(": ").append(x[1]).append('\n'); });
            parts.add("## Files edited by more than one task in the same wave\n" + b);
        }
        if (problems.get("ownership_violations") instanceof List<?> vs && !vs.isEmpty()) {
            final var b = new StringBuilder();
            vs.stream().limit(30).forEach(v -> { Object[] x = (Object[]) v; b.append("- ").append(x[0]).append(": ").append(x[1]).append('\n'); });
            parts.add("## Files a task edited outside the ownership it declared\n" + b);
        }
        parts.add("## Repository tree\n```\n" + repoTree(ws, 8) + "\n```");
        final Path pp = ws.resolve("docs/PROGRESS.md");
        if (Files.exists(pp)) { try { parts.add("## docs/PROGRESS.md\n" + capTail(Files.readString(pp), CAPS.get("progress"))); } catch (IOException e) { log.debug("could not read {} for the pack: {}", pp, e.toString()); } }
        return String.join("\n\n", parts);
    }
}
