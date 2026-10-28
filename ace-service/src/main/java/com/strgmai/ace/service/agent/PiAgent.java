package com.strgmai.ace.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.strgmai.ace.service.backend.LocalModelBackend;
import com.strgmai.ace.service.runner.JournalFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** #236: the "pi" harness - a real, external coding-assistant CLI (confirmed installed:
 *  `pi --version`), NOT something this project reimplements. Sibling to ReferenceAgent, same
 *  call-site contract (same argument list, same ReferenceAgent.SessionResult return type) so
 *  RunBench can dispatch between the two without touching sessionWithPolicy/runBounded at all.
 *
 *  Unlike ReferenceAgent (in-process LangChain4j calls), pi is spawned as a genuine OS subprocess -
 *  this class owns that subprocess's full lifecycle: building its command line, pointing its
 *  "omlx" provider at this session's RecordingProxy (pi has no --base-url flag; this is the only
 *  way to redirect it - confirmed via `pi -p --help`), enforcing the wall budget itself via
 *  Process.waitFor(timeout), and killing the full descendant tree on timeout via
 *  ProcessHandle.descendants() (RunBenchSupport's own javadoc notes Python's env-var-sweep
 *  kill_tree was never ported for lack of a Java equivalent - that limitation doesn't apply here,
 *  since we already hold the exact child Process/PID directly; no sweep needed).
 *
 *  Token-budget enforcement needs no special handling: RecordingProxy's 429 refusal is already
 *  transparent to ANY HTTP client on its port, pi included - exactly the same mechanism
 *  ReferenceAgent relies on.
 *
 *  Scope, deliberately bounded: sequential sessions only (mode=monolithic, or orchestrated with
 *  parallel&lt;=1 - enforced by RunBench, not just documented here). Concurrent pi sessions would
 *  each need their own HOME-scoped models.json copy (mirroring the Python original's per-task
 *  sess["home"]), real additional work not needed by the motivating case: ExperimentsService's
 *  agent_ab template never sets .parallel(...) on any arm. Also deliberately NOT ported: Python's
 *  pi_packages step (installing todo/goal npm extensions via `pi install`) - not required for pi
 *  to function, out of scope for fixing agent_ab's silently-meaningless ref-vs-ref comparisons;
 *  a documented simplification, not a silent gap.
 *
 *  Reviewer sessions (Reviews.java's self-review/trajectory-review) are UNAFFECTED by this class -
 *  they always use ReferenceAgent regardless of a run's own harness, since harness selects who
 *  performs the TASK, never who reviews it. */
@Component
public class PiAgent {
    private static final Logger log = LoggerFactory.getLogger(PiAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** turns/toolErrors/compactions have no pi equivalent - an external closed-box CLI exposes none
     *  of ReferenceAgent's own in-process introspection. A sentinel, not a silently-wrong 0. */
    private static final int NOT_OBSERVABLE = -1;
    private final LocalModelBackend backend;

    public PiAgent(final LocalModelBackend backend) { this.backend = backend; }

    /** One-time per-run HOME setup (mirrors Python's run_once() setup + fresh_home()): copies the
     *  user's own ~/.pi/agent/models.json local-provider entry (keyed under backend.providerKey(),
     *  #65 - was a hardcoded "omlx" literal, matching the operator's own pi config convention)
     *  into this run's scrubbed HOME, pinned to the run's own measured context/output-token
     *  budget, and writes a minimal settings.json - defaultProjectTrust="always" is load-bearing,
     *  not cosmetic: without it pi prompts interactively for project trust and hangs in this
     *  non-interactive harness. A no-op (pi will fail on its own, clearly, the first time it's
     *  actually invoked) when the real models.json has no matching provider to seed from at all. */
    public void seedHome(final Path home, final String model, final int usableContext, final int maxOutputTokens) throws IOException {
        final String key = backend.providerKey();
        final Path piAgentDir = home.resolve(".pi/agent");
        Files.createDirectories(piAgentDir);
        final Path realModelsJson = Path.of(System.getProperty("user.home"), ".pi", "agent", "models.json");
        if (Files.isRegularFile(realModelsJson)) {
            final ObjectNode root = (ObjectNode) MAPPER.readTree(realModelsJson.toFile());
            final JsonNode local = root.path("providers").path(key);
            final ObjectNode providers = MAPPER.createObjectNode();
            if (local.isObject()) providers.set(key, local);
            root.set("providers", providers);
            if (local.isObject()) pinModelEntry((ObjectNode) local, model, usableContext, maxOutputTokens);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(piAgentDir.resolve("models.json").toFile(), root);
        } else {
            log.warn("~/.pi/agent/models.json has no '{}' provider to seed the pi harness's HOME from - "
                    + "pi will fail with its own clear error the first time a pi session actually runs", key);
        }
        final ObjectNode settings = MAPPER.createObjectNode();
        settings.put("defaultProvider", key);
        settings.put("defaultModel", model);
        settings.put("defaultProjectTrust", "always");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(piAgentDir.resolve("settings.json").toFile(), settings);
    }

    /** Ensures THIS run's own requested model is in the "omlx" provider's models[] catalog with the
     *  run's own measured budget, synthesizing an entry if the user's interactive config happens to
     *  only list a different model - pi's own model validation is catalog-based (its --help
     *  describes --model as a "pattern" matched against models.json), so a run using a model the
     *  user's own ~/.pi/agent/models.json doesn't happen to list must not silently fail here. */
    private static void pinModelEntry(final ObjectNode omlx, final String model, final int usableContext, final int maxOutputTokens) {
        final String bareModel = model.contains("/") ? model.substring(model.indexOf('/') + 1) : model;
        ObjectNode entry = null;
        if (omlx.get("models") instanceof com.fasterxml.jackson.databind.node.ArrayNode models) {
            for (final JsonNode m : models)
                if (m.isObject() && bareModel.equals(m.path("id").asText())) { entry = (ObjectNode) m; break; }
            if (entry == null) {
                entry = MAPPER.createObjectNode();
                entry.put("id", bareModel); entry.put("name", bareModel); entry.put("reasoning", false);
                entry.putArray("input").add("text");
                final ObjectNode cost = entry.putObject("cost");
                cost.put("input", 0); cost.put("output", 0); cost.put("cacheRead", 0); cost.put("cacheWrite", 0);
                models.add(entry);
            }
        }
        if (entry != null) { entry.put("contextWindow", usableContext); entry.put("maxTokens", maxOutputTokens); }
    }

    /** Per-call: points providers.&lt;backend.providerKey()&gt;.baseUrl at THIS session's
     *  RecordingProxy - mirrors Python's own per-call m["providers"]["omlx"]["baseUrl"] = base. A
     *  no-op if this run's own (already seeded) models.json or its matching provider entry is
     *  missing - pi fails on its own with a clear error rather than this method inventing a
     *  misleading one. Package-private: directly unit-tested. */
    void patchBaseUrl(final Path home, final String proxyBase) throws IOException {
        final Path mj = home.resolve(".pi/agent/models.json");
        if (!Files.isRegularFile(mj)) return;
        final ObjectNode root = (ObjectNode) MAPPER.readTree(mj.toFile());
        if (root.path("providers").path(backend.providerKey()) instanceof ObjectNode local) {
            local.put("baseUrl", proxyBase);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(mj.toFile(), root);
        }
    }

    /** The `pi -p ...` command line - matches the original Python harness's exact flag shape.
     *  Package-private: directly unit-tested without spawning anything. */
    static List<String> buildCommand(final String instruction, final String model, final Path sessionDir,
                                      final String sessionId, final boolean continueSession, final String name,
                                      final String appendSystem) {
        final List<String> cmd = new ArrayList<>(List.of("pi", "-p", instruction, "--model", model,
                "--session-dir", sessionDir.toString(), "--no-context-files"));
        if (continueSession) cmd.addAll(List.of("--session", sessionId));
        else cmd.addAll(List.of("--session-id", sessionId, "-n", name));
        // pi's own --append-system-prompt auto-detects a file path vs literal text (confirmed via
        // --help); RunBench always passes appendSystem as a file path (e.g. packs/stable.md),
        // exactly what ReferenceAgent itself reads via Files.readString - no translation needed
        if (appendSystem != null && !appendSystem.isBlank()) cmd.addAll(List.of("--append-system-prompt", appendSystem));
        return cmd;
    }

    /** Same argument list as ReferenceAgent.run()'s full form, so RunBench's dispatch is a one-line
     *  swap at each call site. firstTokenTimeoutMs/compactionTrigger/maxTurns are accepted for
     *  interchangeability but ignored - confirmed via `pi -p --help` that no equivalent flag exists
     *  in the installed version; these are ReferenceAgent-only in-process loop controls. */
    public ReferenceAgent.SessionResult run(final String name, final String instruction, final long wallSec, final Long tokenBudget,
                                            final Path sessionDir, final String sessionId, final boolean continueSession,
                                            final String appendSystem, final String cwd, final String proxyBase, final Consumer<String> abortProxy,
                                            final long firstTokenTimeoutMs, final int compactionTrigger, final int maxTurns, final String model,
                                            final Map<String, String> extraEnv) throws Exception {
        final Instant start = Instant.now();
        final String home = extraEnv == null ? null : extraEnv.get("HOME");   // scrubbedEnv() always sets HOME; null only in a bare unit-test context
        if (home != null) patchBaseUrl(Path.of(home), proxyBase);
        final List<String> cmd = buildCommand(instruction, model, sessionDir, sessionId, continueSession, name, appendSystem);
        final Path logFile = sessionDir.getParent().resolve(name + ".pi.log");
        final ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of(cwd).toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().clear();
        if (extraEnv != null) pb.environment().putAll(extraEnv);
        final Process process = pb.start();
        final long waitSec = wallSec > 0 ? wallSec : Long.MAX_VALUE;   // wallSec <= 0 means unlimited (the system-wide "0 = no budget" convention)
        int rc;
        try {
            if (process.waitFor(waitSec, TimeUnit.SECONDS)) {
                rc = process.exitValue();
            } else {
                log.warn("session {} exceeded its {}s wall budget - killing the pi process tree", name, wallSec);
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                rc = 124;
            }
        } catch (final InterruptedException ie) {
            // #272: an external interrupt (job cancellation, or RunBenchSupport.runBounded()'s own
            // timeout cancelling the inner future) used to propagate straight out of waitFor() and
            // past BOTH branches above - the process-tree kill only ever ran on the TIMEOUT path,
            // never on an INTERRUPT. The real `pi` subprocess (and anything it spawned) kept
            // running, unkilled, continuing to call the model server, exactly the class of bug
            // already fixed for the reference-agent path (#151/#222/#99) but never brought here.
            log.warn("session {} was interrupted - killing the pi process tree", name);
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ie;
        }
        // the journal lives at sessionDir's sibling (RunBench: sessionDir = rd.resolve("sessions"),
        // journal = rd.resolve("interactions.jsonl")) - derivable from the same arg ReferenceAgent's
        // own signature already carries, so no new parameter is needed for call-site interchangeability
        final Path journal = sessionDir.getParent().resolve("interactions.jsonl");
        final String finish = JournalFacts.lastFinish(journal.toString(), start.toString());
        final Instant end = Instant.now();
        final double seconds = Math.round(java.time.Duration.between(start, end).toMillis() / 100.0) / 10.0;
        return new ReferenceAgent.SessionResult(name, rc, seconds, finish, NOT_OBSERVABLE, NOT_OBSERVABLE, NOT_OBSERVABLE, null, start, end);
    }
}
