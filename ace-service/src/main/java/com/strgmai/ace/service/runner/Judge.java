package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ProviderConfig;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.agent.AgentSession;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.proxy.RecordingProxy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** #265: a mid-task trajectory checkpoint - every N turns of a task's own main implementation
 *  session, a SEPARATE LLM session reads the task's goal/budget, what's actually been produced so
 *  far, and a capped tail of the running trajectory, and returns plain-text guidance that gets
 *  appended as the next turn's own instruction (see ReferenceAgent.JudgeCheckpoint). Found live:
 *  T3 spent 5+ hours building a JVM-level network guard the task never asked for, with nothing in
 *  the harness noticing - this is the automated tripwire for exactly that failure shape.
 *
 *  Deliberately NOT part of Reviews.java: that class is end-of-run/calibration-only and reverts
 *  anything its own reviewer touched - wrong semantics here, since the main session's own files
 *  must survive untouched while this runs alongside it. Reuses Reviews.java's exact local/external
 *  "provider/model" convention (small and stable enough to duplicate rather than cross-wire the
 *  two otherwise-independent classes over). */
@Component
public class Judge {
    private static final Logger log = LoggerFactory.getLogger(Judge.class);
    private final BenchProperties props;
    private final ReferenceAgent agent;

    public Judge(final BenchProperties props, final ReferenceAgent agent) { this.props = props; this.agent = agent; }

    // character caps (not token caps, same convention Packs.cap/capTail already use elsewhere) -
    // an unbounded dump of every file plus the whole history is exactly the shape that caused
    // #256's own original failure; this keeps the judge's own prompt from becoming the next one.
    private static final int MAX_DIFF_CHARS = 12_000;
    private static final int MAX_TRAJECTORY_CHARS = 16_000;
    private static final int JUDGE_MAX_TURNS = 4;

    private static final String SYSTEM = """
            You are a trajectory judge for an autonomous coding agent working on ONE task of a larger
            benchmark. You are NOT the implementer - do not use any tools, do not write or edit files.
            Your answer is injected DIRECTLY into the agent's own conversation as its next
            instruction - the agent itself reads it, not a human operator, so never describe it in
            the third person ("the agent is..."); address it as "you".

            Read the task goal, its budget and elapsed time, what it has produced so far, and its
            recent trajectory. Answer in a SHORT plain-text paragraph (no tools, no file writes):
            is this still the most efficient path to the stated goal, and will it meet its deadline?

            If it is on track, say that briefly and let it continue - no further comment needed
            beyond confirming that.

            If it is drifting - solving a harder or different problem than the goal actually asks
            for, repeating the same failing approach, or unlikely to finish in time - do not just
            describe the problem: tell it directly, as a command, what to stop doing and what to do
            instead right now (e.g. "You are stuck re-disassembling bytecode. Stop. Check whether
            the test's Spring context provides a DataSource bean instead."), addressed to "you",
            not written about "the agent".""";

    /** builds the checkpoint callback RunBench wires into a task's main session via the new
     *  ReferenceAgent.run() overload. `judgeCfg` is the effective (override-resolved) judge
     *  settings for THIS task - never the task's own budget. A judge failure must never take the
     *  task down with it: any exception here is logged and treated as "no recommendation this
     *  time," same as a turn where the judge genuinely had nothing to add. */
    public ReferenceAgent.JudgeCheckpoint checkpoint(final Map<String, Object> cfg, final String runId, final Path ws, final Path rd,
            final RecordingProxyFactory proxies, final String taskId, final String goal,
            final long taskWallSec, final long taskTokens, final long taskStartMs, final Map<String, Object> judgeCfg) {
        return (turn, trajectory) -> {
            try {
                return runCheckpoint(cfg, runId, ws, rd, proxies, taskId, goal, taskWallSec, taskTokens, taskStartMs, judgeCfg, turn, trajectory);
            } catch (final Exception e) {
                log.warn("judge checkpoint failed for {} at turn {}, continuing without it: {}", taskId, turn, e.toString());
                return null;
            }
        };
    }

    private String runCheckpoint(final Map<String, Object> cfg, final String runId, final Path ws, final Path rd,
            final RecordingProxyFactory proxies, final String taskId, final String goal,
            final long taskWallSec, final long taskTokens, final long taskStartMs, final Map<String, Object> judgeCfg,
            final int turn, final List<ChatMessage> trajectory) throws Exception {
        final long elapsedSec = (System.currentTimeMillis() - taskStartMs) / 1000;
        final String prompt = buildPrompt(ws, taskId, goal, taskWallSec, taskTokens, elapsedSec, trajectory);

        String reviewer = judgeCfg.get("model") instanceof String m && !m.isBlank()
                ? m : "omlx/" + cfg.getOrDefault("model", props.model());
        final boolean external = !reviewer.startsWith("omlx/");
        final long wallSec = judgeCfg.get("wall_sec") instanceof Number w ? w.longValue() : 0L;
        final long tokens = judgeCfg.get("tokens") instanceof Number t ? t.longValue() : 0L;
        final String model = reviewer.startsWith("omlx/") ? reviewer.substring("omlx/".length()) : reviewer.substring(reviewer.indexOf('/') + 1);
        final RecordingProxyFactory.ProxySession proxy = external ? null
                : proxies.start(rd.resolve("interactions.jsonl"), tokens, null, (RecordingProxy.SamplerOverrides) cfg.get("_sampler_overrides"));
        final Map<String, String> extraEnv = external ? externalCredentials(judgeCfg) : null;
        final long firstTokenTimeoutMs = cfg.get("first_token_timeout_ms") instanceof Number n ? n.longValue() : 0L;
        final int compactionTrigger = cfg.get("compaction_trigger") instanceof Number ct ? ct.intValue() : props.compactionTrigger();
        final String name = taskId + "-judge-turn" + turn;
        try {
            // #290: cfg never carries a "run_id" key (RunBench only ever puts that into its own
            // manifest map) - reading it off cfg here seeded every judge session's UUID with the
            // literal string "null", unlike every other session-id call site in this codebase,
            // which all build theirs from the real run id. Benign only because sessions already
            // live under a per-run directory and one run executes at a time; latent collision risk
            // the moment either of those stops being true.
            final String sid = UUID.nameUUIDFromBytes(("agentbench/" + runId + "/" + name).getBytes()).toString();
            final Consumer<String> abort = proxy == null ? reason -> {} : proxy.abort();
            final ReferenceAgent.SessionResult res = RunBenchSupport.runBounded(wallSec, name, rd.resolve("sessions"), sid, abort,
                    () -> agent.run(name, prompt, wallSec, tokens, rd.resolve("sessions"), sid, false, SYSTEM, ws.toString(),
                            proxy == null ? externalBase(reviewer, cfg) : proxy.base(), abort, firstTokenTimeoutMs, compactionTrigger,
                            JUDGE_MAX_TURNS, model, extraEnv, null, true));
            final String recommendation = AgentSession.lastAssistantText(res.sessionFile()).strip();
            Files.createDirectories(rd.resolve("judge"));
            Files.writeString(rd.resolve("judge").resolve(taskId + "-turn" + turn + ".md"),
                    "# Judge checkpoint: " + taskId + ", turn " + turn + "\n\n## Prompt\n\n" + prompt
                            + "\n\n## Recommendation\n\n" + (recommendation.isEmpty() ? "(none)" : recommendation) + "\n");
            return recommendation;
        } finally {
            if (proxy != null) proxy.stop();
        }
    }

    private String buildPrompt(final Path ws, final String taskId, final String goal, final long wallSec,
            final long tokenBudget, final long elapsedSec, final List<ChatMessage> trajectory) {
        final String diff = cap(currentDiffSummary(ws), MAX_DIFF_CHARS);
        final String traj = capTail(renderTrajectory(trajectory), MAX_TRAJECTORY_CHARS);
        return "## Task " + taskId + "\n" + (goal == null ? "" : goal) + "\n\n"
                + "## Budget\n" + (wallSec > 0 ? "wall: " + wallSec + "s (elapsed: " + elapsedSec + "s)" : "wall: unlimited (elapsed: " + elapsedSec + "s)")
                + ", tokens: " + (tokenBudget > 0 ? String.valueOf(tokenBudget) : "unlimited") + "\n\n"
                + "## What has been produced so far\n" + (diff.isBlank() ? "(nothing yet - no files changed or created)" : diff) + "\n\n"
                + "## Recent trajectory\n" + traj;
    }

    /** the task's own net change so far - same before/after logic BenchController.taskDiff()'s
     *  in-progress branch already uses (nearest ancestor phase/* tag vs. HEAD, plus untracked
     *  files), just capped and text-only rather than returned as a structured API response. */
    private String currentDiffSummary(final Path ws) {
        try {
            if (!Files.exists(ws.resolve(".git"))) return "";
            final String before = RunBenchSupport.nearestPhaseTagSha(ws, "HEAD");
            final StringBuilder out = new StringBuilder(RunBenchSupport.gitOut(ws, "diff", before, "--", "."));
            final String untracked = RunBenchSupport.gitOut(ws, "ls-files", "--others", "--exclude-standard");
            if (!untracked.isBlank()) out.append("\n\n[untracked files]\n").append(untracked);
            return out.toString();
        } catch (final Exception e) {
            log.debug("could not build the judge's diff summary: {}", e.toString());
            return "";
        }
    }

    /** a plain-text rendering of the running message list - skips the system prompt (the judge
     *  gets its own, and the task's system prompt is boilerplate it doesn't need to re-read).
     *  package-private (not private): tested directly (#267), same reasoning cap()/capTail() above
     *  already follow. */
    String renderTrajectory(final List<ChatMessage> trajectory) {
        final StringBuilder out = new StringBuilder();
        for (final ChatMessage m : trajectory) {
            if (m instanceof UserMessage um) {
                out.append("USER: ").append(um.singleText()).append("\n\n");
            } else if (m instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isBlank()) out.append("ASSISTANT: ").append(ai.text()).append("\n");
                if (ai.hasToolExecutionRequests())
                    for (final ToolExecutionRequest c : ai.toolExecutionRequests())
                        out.append("ASSISTANT CALLED ").append(c.name()).append("(").append(c.arguments()).append(")\n");
                out.append("\n");
            } else if (m instanceof ToolExecutionResultMessage tr) {
                out.append("TOOL RESULT (").append(tr.toolName()).append("): ").append(cap(tr.text(), 1000)).append("\n\n");
            }
        }
        return out.toString();
    }

    // package-private (not private): tested directly, same reasoning as RunBench's own
    // taskOverride/effectiveInt/shouldRetryOnLengthFinish - pure, worth testing in isolation
    // rather than only indirectly through a full checkpoint() call.
    static String cap(final String text, final int n) {
        return text == null ? "" : text.length() <= n ? text : text.substring(0, n) + "\n… [truncated at " + n + " chars]";
    }

    /** keep the NEWEST part, same reasoning Packs.capTail already applies to PROGRESS.md - the
     *  judge cares about what just happened, not the start of a long session. */
    static String capTail(final String text, final int n) {
        return text.length() <= n ? text : "… [" + (text.length() - n) + " older chars omitted]\n" + text.substring(text.length() - n);
    }

    Map<String, String> externalCredentials(final Map<String, Object> judgeCfg) {
        final List<String> defaultKeys = java.util.Arrays.stream(ProviderConfig.values()).map(p -> p.credentialEnvVar).toList();
        final Map<String, String> extra = new LinkedHashMap<>();
        for (final String k : judgeCfg.get("credential_env") instanceof List<?> l ? (List<String>) l : defaultKeys)
            if (System.getenv(k) != null) extra.put(k, System.getenv(k));
        return extra;
    }

    /** external providers bypass the proxy (their credentials and endpoints are their own) -
     *  identical to Reviews.externalBase(), duplicated rather than shared for the same reason the
     *  local/external routing convention itself is duplicated (see the class javadoc). */
    String externalBase(final String reviewer, final Map<String, Object> cfg) {
        // #295: the twin bug Reviews.externalBase() had - see that method's own comment. Harmless
        // here today only because checkpoint()'s own try/catch already turns any exception into
        // "no recommendation this turn"; fixed anyway so the twins stay structurally identical.
        final int slash = reviewer.indexOf('/');
        final String provider = slash < 0 ? "" : reviewer.substring(0, slash);
        final var known = ProviderConfig.byName(provider);
        return known != null ? known.baseUrl : String.valueOf(cfg.getOrDefault("endpoint", props.endpoint()));
    }
}
