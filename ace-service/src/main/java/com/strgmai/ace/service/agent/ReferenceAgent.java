package com.strgmai.ace.service.agent;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.pack.Packs;
import com.strgmai.ace.service.proxy.RecordingProxy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Port of runner/agent_loop.py, the reference agent, on LangChain4j. The harness owns every part
 *  of the loop: four tools (read/write/edit/bash with mechanical output hygiene), structural
 *  compaction (not an LLM summary), retries with backoff on transient errors, the proxy's 429
 *  (budget exhausted) ends the session with rc=3, a `length` finish is recorded and left to the
 *  harness's policy, and the stop rule is: the model answers with no tool call -> done.
 *  The agent talks to the model THROUGH the recording proxy, which pins the sampler params and
 *  enforces the token budget. */
@Component
public class ReferenceAgent {
    private static final Logger log = LoggerFactory.getLogger(ReferenceAgent.class);
    public static final String AGENT_VERSION = "jls-ref-1.0";
    /** #95: the default turn cap when a run doesn't override it via --max-turns. */
    public static final int DEFAULT_MAX_TURNS = 400;
    /** #653-followup: how many times looksLikeATextToolCallAttempt() gets nudged in one session -
     *  see nudgedForTextToolCall's own call site for why more than one. */
    static final int MAX_TEXT_TOOL_CALL_NUDGES = 3;
    /** ObjectMapper is thread-safe and designed for reuse; constructing one per tool call (up to 400
     * turns x several calls) would allocate hundreds of expensive serializer/deserializer instances. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public static final String SYSTEM = """
            You are an autonomous software engineer working non-interactively in the repository at {cwd} (today {date}). 
            You have four tools: read, write, edit, bash. Work in small verified steps: locate before reading (grep -n, find -maxdepth), read line 
            ranges instead of whole files, keep command output short (tail/grep), never re-run a command whose output you already have, run the 
            tests after every change and react to what they say. Bias toward action: the build and tests are your feedback loop, so prefer 
            writing a first version and running it over researching to eliminate uncertainty up front - gather only what you need for 
            the next concrete step, and once you can write a file, write it. Use conventional, known-good versions of tools and 
            dependencies from your own knowledge; do NOT spend turns fetching remote metadata (package registries, plugin portals) 
            to pin exact versions - pick a reasonable recent version and let the build tell you if it is wrong. When the task is 
            complete - or when your budget is nearly spent - stop by answering with a short final message and no tool call. 
            Never claim something works that you did not see pass.""";

    /** the per-session-kind reasoning effort — a scored treatment, sent via OpenAiChatRequestParameters
     *  (the standard OpenAI field; the original's oMLX-specific chat_template_kwargs.reasoning_effort
     *  is deliberately not used here — see README-JLS.md) and recorded in the session header for
     *  provenance: "record what was sent, not what was configured" (design rule 5). */
    public static final Map<String, String> DEFAULT_REASONING = Map.of(
            "definition", "high", "plan", "high", "implement", "medium", "integrate", "medium",
            "fix", "medium", "status", "low", "review", "medium", "handoff", "low");

    /** maps a session name (a phase id like "p0_definition"/"p1_plan"/"p2_implementation", a task id
     *  like "T3", or a suffixed continuation like "T3-wrapup"/"T3-handoff"/"T3-fix") to a
     *  DEFAULT_REASONING key. Unrecognised names (plain task ids) default to "implement". */
    static String reasoningKind(final String name) {
        if (name.endsWith("-continue")) return reasoningKind(name.substring(0, name.length() - "-continue".length()));
        if (name.endsWith("-wrapup")) return "status";
        if (name.endsWith("-handoff")) return "handoff";
        if (name.endsWith("-fix")) return "fix";
        if (name.startsWith("p0")) return "definition";
        if (name.startsWith("p1") || name.equals("PARALLEL_PLAN")) return "plan";
        if (name.startsWith("p2") || name.equals("implement")) return "implement";
        if (name.equals("INTEGRATION")) return "integrate";
        if (name.equals("REVIEW") || name.equals("TRAJECTORY_REVIEW")) return "review";
        return "implement";
    }

    /** #248: found live - oMLX's prefix cache genuinely works (verified against the raw
     *  interactions.jsonl response bodies, which show real, growing cached-token counts), but every
     *  session log in this project's history has recorded "cached": 0 regardless, because the only
     *  call site writing that field never read it at all. The real value rides along on the
     *  OpenAI-compatible response as tokenUsage's own subtype. */
    static int cachedTokens(final ChatResponse resp) {
        if (resp.metadata() == null || !(resp.metadata().tokenUsage() instanceof OpenAiTokenUsage oaiUsage)) return 0;
        if (oaiUsage.inputTokensDetails() == null || oaiUsage.inputTokensDetails().cachedTokens() == null) return 0;
        return oaiUsage.inputTokensDetails().cachedTokens();
    }

    /** #256: a stream that completes (onCompleteResponse, not onError) with no text, no tool calls,
     *  and no finish reason is a dead turn, not a legitimate content-free one - a real "model
     *  stopped" always carries a finish reason from a stream that actually completed. Confirmed live
     *  against a journaled proxy record ({"content":"","reasoning":"","finish_reason":null,
     *  "tool_calls":[]}) during a host memory squeeze; requiring all three conditions keeps this from
     *  ever matching a normal turn. */
    static boolean isEmptyModelResponse(final ChatResponse resp) {
        final AiMessage ai = resp.aiMessage();
        final boolean noText = ai.text() == null || ai.text().isBlank();
        final boolean noToolCalls = !ai.hasToolExecutionRequests();
        final boolean noFinish = resp.metadata() == null || resp.metadata().finishReason() == null;
        return noText && noToolCalls && noFinish;
    }

    /** found live (job cd4c11a8, T6): some models occasionally write a tool call as literal text
     *  (e.g. "<function=bash><parameter=command>...</parameter></function></tool_call>") instead of
     *  using the real function-calling mechanism - the response carries zero real tool calls and a
     *  normal "stop" finish reason, so it looks exactly like an intentional, content-only turn
     *  unless this specific text shape is checked for. Only called when calls.isEmpty() is already
     *  true (see its own call site), so this narrows further: among turns with no real tool call,
     *  is this one a FAILED tool-call attempt rather than a genuine final answer. */
    static boolean looksLikeATextToolCallAttempt(final String text) {
        return text != null && text.contains("<function=");
    }

    public record SessionResult(String id, int rc, double seconds, String finish, int turns, int toolErrors,
                                int compactions, Path sessionFile, Instant start, Instant end) {}

    /** a mid-task trajectory check, invoked every N turns from inside the loop below (turn count
     *  is never observable from outside this method, so the hook has to live here). `trajectory`
     *  is the SAME live `msgs` list the loop itself mutates - no disk re-read needed. Returning
     *  null/blank means "no intervention"; otherwise the text is appended as the next turn's own
     *  instruction. See Judge.checkpoint() for what actually builds that text. */
    public interface JudgeCheckpoint {
        String check(int turn, List<ChatMessage> trajectory);
    }

    /** #300: a one-shot hook that fires at most once per run() call, right at the moment the loop
     *  would otherwise naturally stop (the model answered with no tool call) - BEFORE session.end()/
     *  return. Returning null/blank means "nothing more to ask, stop here" (most calls never get a
     *  real hook at all). A non-blank return is injected as one more live turn in the SAME
     *  session/connection, letting the loop continue instead of ending - this is what lets a
     *  trailing ask (e.g. "write a handoff note") reuse the model server's own prefix cache for
     *  everything already in `trajectory`, instead of paying for a brand-new continueSession=true
     *  run() call later that has to re-send the whole history cold over a fresh connection (see
     *  RunBench's handoff fast path, issue #300). Once fired (with any result, blank or not), a
     *  second natural stop in the same call always ends normally - `trajectory` is the SAME live
     *  `msgs` list the loop itself mutates, so the hook sees the full, settled history as of right
     *  now, same as JudgeCheckpoint above. */
    public interface FinalTurn {
        String ask(List<ChatMessage> trajectory);
    }

    /** #332: backs the record_lesson tool - offered to the main task session and the handoff
     *  session so either can report a cross-cutting finding (any fact worth telling every LATER
     *  task regardless of the dependency graph) without waiting for a judge's own off-track
     *  verdict or a handoff note that only reaches direct successors. null means the tool is
     *  unavailable for this session (same convention as JudgeCheckpoint/FinalTurn above) - only
     *  RunBench's own task-loop and handoff call sites ever build a real one, and the
     *  implementation (appending to the run's own lessons.md, with size-capping and compaction)
     *  deliberately lives in RunBench, not here: ReferenceAgent (package `agent`) must never
     *  import RunBench (package `runner`) directly, which is exactly why this is an interface
     *  RunBench implements and passes down, not a direct call. */
    public interface LessonRecorder {
        void record(String text);
    }

    private final BenchProperties props;
    private final ProviderStore providerStore;

    public ReferenceAgent(BenchProperties props, ProviderStore providerStore) { this.props = props; this.providerStore = providerStore; }

    /** #424: `recallEnabled=false` leaves recall_tool_output out of the offered tools entirely -
     *  the model structurally cannot call a tool it was never told exists, matching
     *  AgentSession.compact()'s own identical check for which stub wording to write (a disabled
     *  session's stub never points at a tool this list doesn't offer). */
    static List<ToolSpecification> toolSpecs(final boolean recallEnabled, final boolean lessonRecordingEnabled) {
        final var specs = new ArrayList<ToolSpecification>(List.of(
                ToolSpecification.builder().name("read")
                        .description("Read a text file (or a line range of it). Prefer ranges: locate with `bash` (grep -n) first, then read only what you need.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "the file to read")
                                .addIntegerProperty("start_line", "1-based, inclusive")
                                .addIntegerProperty("end_line", "inclusive")
                                .required("path").build())
                        .build(),
                ToolSpecification.builder().name("write")
                        // #325: prefer edit for an existing file - a full rewrite's own argument is
                        // far larger than a diff, and (unlike a tool result) never shrinks until a
                        // LATER write/edit/read on the same path makes it provably stale
                        .description("Create a new file, or fully overwrite an existing one (directories are created). "
                                + "Prefer `edit` for changes to an existing file; reserve this for new files or rewrites "
                                + "so pervasive a diff wouldn't be meaningfully smaller.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "where to write")
                                .addStringProperty("content", "the full file content")
                                .required("path", "content").build())
                        .build(),
                ToolSpecification.builder().name("edit")
                        .description("Replace an exact, unique occurrence of old_string in a file with new_string (include enough context to make it unique; use replace_all for every occurrence).")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "the file to edit")
                                .addStringProperty("old_string", "the exact text to replace")
                                .addStringProperty("new_string", "the replacement")
                                .addBooleanProperty("replace_all", "replace every occurrence")
                                .required("path", "old_string", "new_string").build())
                        .build(),
                ToolSpecification.builder().name("bash")
                        .description("Run a shell command in the repository (zsh, non-interactive). Output is capped: filter noisy commands yourself (`| tail -40`, `| grep -E 'error|FAILED'`).")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("command", "the command to run")
                                .addIntegerProperty("timeout_sec", "default 600, max 1800")
                                .required("command").build())
                        .build()));
        // #424: formalizes the stub text's own "re-run the command if you need it" - the
        // output is already sitting in this session's own journal (toolResult() writes it
        // unconditionally, before anything is ever masked), so recalling it is free;
        // re-running could mean redoing an expensive build/docker-compose step.
        // #417 follow-up review (C11): the description used to say "the full, original
        // output" - inaccurate, since what's journaled (and so what this returns) is the
        // SAME output already capped by AgentTools' own head/tail/size limits before it
        // ever entered context in the first place, not a fuller version that was never
        // seen. (C13): this is an exact tool_call_id lookup, not the content SEARCH Letta's
        // own filesystem-memory benchmark demonstrated beating a purpose-built memory
        // product with (https://www.letta.com/blog/benchmarking-ai-agent-memory/) - a
        // deliberately narrower v1 of that result, not an implementation of it.
        if (recallEnabled) specs.add(ToolSpecification.builder().name("recall_tool_output")
                .description("Recall the SAME output you already saw for an earlier tool call before it was dropped to save context "
                        + "(exactly what was shown then, including any truncation already applied - not a fuller version, and it does not "
                        + "re-run the command). Use the tool_call_id named in its \"[output dropped...]\" stub text.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("tool_call_id", "the id named in the \"[output dropped...]\" stub text")
                        .required("tool_call_id").build())
                .build());
        // #332: generic - not scoped to environment facts specifically. Any cross-cutting
        // finding (a host/toolchain quirk, a discovered constraint, anything worth telling
        // every LATER task regardless of the dependency graph) belongs here, not a task-specific
        // detail that belongs in this task's own handoff note instead.
        if (lessonRecordingEnabled) specs.add(ToolSpecification.builder().name("record_lesson")
                .description("Record a finding worth telling EVERY later task in this run, regardless of the dependency graph - "
                        + "a host/toolchain quirk, a discovered constraint, anything cross-cutting. Not for task-specific details "
                        + "(those belong in this task's own handoff note instead). Keep it short and factual - this is a shared, "
                        + "size-limited record, not a scratchpad.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("text", "the finding, short and factual")
                        .required("text").build())
                .build());
        return specs;
    }

    /** run one non-interactive session (or a continuation). The proxy for this session is passed in:
     *  the caller (RunBench) starts a fresh proxy per phase/task with that session's token budget. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Map<String, String> extraEnv) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, reason -> {}, DEFAULT_FIRST_TOKEN_TIMEOUT_MS, props.compactionTrigger(), DEFAULT_MAX_TURNS, null, extraEnv);
    }

    /** full form: `model` overrides the configured one (a reviewer, a parallel task, a probe).
     *  `abortProxy` closes the CALLER's RecordingProxy's own upstream connection when this method
     *  gives up on a retry attempt (see chatWithRetry) - cancelling the agent's own
     *  StreamingHandle only closes the agent<->proxy hop; it does nothing for the proxy's
     *  separate, independently-blocking read from oMLX (R12). `firstTokenTimeoutMs` caps how long
     *  a stream may sit with NO chunk at all before it counts as stalled; once the first chunk
     *  arrives, nothing in here bounds it any further - only the harness's own preemptive task-wall
     *  timeout may cut it off (see RunBench.sessionWithPolicy, and awaitStream below). `compactionTrigger` overrides
     *  BenchProperties' operator default (R16): compaction stubs OLD tool outputs to shrink the
     *  prompt, but that edit invalidates oMLX's prefix cache for everything after it, forcing a
     *  full re-prefill under whatever memory pressure the machine is already under - confirmed live
     *  (oMLX's own log: "Prefill interrupted at 16384/18602 tokens" during exactly such a
     *  post-compaction re-prefill, repeatedly, never completing across 4 retries). 0 disables
     *  compaction entirely for a run where that trade is worse than just keeping the full prompt.
     *  `maxTurns` (#95) overrides DEFAULT_MAX_TURNS: unlike every other phase/session budget, this
     *  turn cap used to be a hardcoded local constant with no run-level override at all - a session
     *  behaving correctly but genuinely needing more turns for a large task had no knob to reach for. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, null);
    }

    /** #265: full form with a mid-task judge checkpoint (see JudgeCheckpoint above) - every other
     *  call site (reviews, parallel plan, p0/p1, wrap-up/continue/handoff) goes through the
     *  convenience overload above with judge=null; only RunBench's own task-loop call sites build
     *  a real one, since the judge is deliberately scoped to a task's own main implementation
     *  session, not every session kind. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, null);
    }

    /** #300: adds a one-shot FinalTurn hook (see its own javadoc) on top of the judge-aware form
     *  above - RunBench's task loop is the only caller that ever passes a real one (its handoff
     *  fast path); every other call site goes through one of the convenience overloads above with
     *  finalTurn=null. advisoryOnly defaults to false here since Judge's own session (the only
     *  caller that needs advisoryOnly=true) has no use for a trailing handoff-style ask either. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, FinalTurn finalTurn) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, false, finalTurn);
    }

    /** #265: `advisoryOnly` is for the judge's OWN session specifically - it means two things at
     *  once, not just one: (1) `appendSystem` is used VERBATIM as the whole system prompt (no file
     *  read, no {cwd}/{date} substitution, none of this class's own implementer SYSTEM text mixed
     *  in) - the judge must never see "you have four tools... bias toward action... once you can
     *  write a file, write it", which is what it was silently getting before this parameter
     *  existed (appendSystem=null just fell through to the implementer prompt alone); and (2) no
     *  tool specs are offered at all, so the model structurally cannot call read/write/edit/bash
     *  against `cwd` - which, for a judge, IS the main session's own live workspace. A system
     *  prompt alone only asks nicely; an advisory session that still had tools available could
     *  still decide to "just fix it" and mutate the task's real files mid-task, exactly what this
     *  checkpoint must never do. With no tool specs offered, `calls` below is always empty, so the
     *  loop always returns after turn 1 - JUDGE_MAX_TURNS stays only as a defensive cap. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, boolean advisoryOnly) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, advisoryOnly, null);
    }

    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, boolean advisoryOnly, FinalTurn finalTurn) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, advisoryOnly, finalTurn, null);
    }

    /** #307: `compactOnLoadKeepRecentTurns` is this issue's own original #300 fix, originally scoped
     *  to the ONE caller that genuinely needed a cold `continueSession=true` reconnect to a large
     *  history - the budget-exhaustion rescue step - but #580 found live that RunBench.java's own
     *  shared call-through helper now passes `props.keepRecentTurns()` through this parameter
     *  UNCONDITIONALLY, for every `continueSession=true` resume it drives, not rescue alone (that
     *  helper's own comment says as much: "a no-op for the overwhelmingly common case... only ever
     *  fires when there is real history to shrink before the first send" - describing a much wider
     *  reach than this method's own doc here used to claim). A resumed session deliberately wants its
     *  OLD history compacted down to its most recent turns before ever being sent - cheap prefill
     *  (old verbose tool outputs become one-line stubs) without losing what the session most recently
     *  tried and why, which a trajectory-free fresh pack alone cannot carry. `null` disables this
     *  (every call site that doesn't resolve a real turn count) - deliberately NOT `0`, since `0` is
     *  itself a legitimate, maximally aggressive compaction target (`AgentSession.compact()` already
     *  supports it, and `RunSpec`'s own `rescueKeepRecentTurns` validation explicitly allows it) that
     *  must still fire, not be mistaken for "feature off". Applied once, immediately after
     *  `loadMessages()`, before the turn loop's own reactive `lastPrompt`-driven compaction ever gets
     *  a chance to run - which, for turn 1 of any continuation, it structurally never does (see
     *  #300's own diagnosis). #580's own practical consequence: since turn-limited/length-capped
     *  continuations are this harness's NORMAL, frequent case rather than a rare edge case, "turn 1 of
     *  a continuation" has become the common place compaction actually fires - so the compaction(...)
     *  2-arg overload (no prompt_tokens_before - see its own javadoc) records nearly every compaction
     *  event this harness produces, not the reactive 3-arg one; the Stats tab's "Reclaimed context"
     *  figures stay empty far more often than this method's own original design anticipated. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, boolean advisoryOnly, FinalTurn finalTurn, Integer compactOnLoadKeepRecentTurns) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, advisoryOnly, finalTurn, compactOnLoadKeepRecentTurns, CompactionStrategies.DEFAULT);
    }

    /** #417: `compactionStrategies` selects which compaction mechanism(s) the reactive,
     *  mid-loop compaction below uses, and in what order (see CompactionStrategies) - every other
     *  call site (Judge, the rescue step's own compact-on-load, p0/p1/handoff/wrap-up) goes through
     *  one of the convenience overloads above with CompactionStrategies.DEFAULT (today's exact
     *  existing behavior, unchanged); only RunBench's own main task-session call site
     *  (sessionWithPolicy, the one place #417's own diminishing-returns problem was found) resolves
     *  and passes a real, possibly task-overridden list. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, boolean advisoryOnly, FinalTurn finalTurn, Integer compactOnLoadKeepRecentTurns, List<String> compactionStrategies) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, abortProxy, firstTokenTimeoutMs, compactionTrigger, maxTurns, model, extraEnv, judge, advisoryOnly, finalTurn, compactOnLoadKeepRecentTurns, compactionStrategies, null);
    }

    /** #332: `lessonRecorder` offers the record_lesson tool at all - see LessonRecorder's own
     *  javadoc. null (every call site above) means the tool is unavailable for this session;
     *  only RunBench's own task-loop and handoff call sites build a real one. */
    //todo ReferenceAgent should be LangChain AiService
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Consumer<String> abortProxy, long firstTokenTimeoutMs, int compactionTrigger, int maxTurns, String model, Map<String, String> extraEnv, JudgeCheckpoint judge, boolean advisoryOnly, FinalTurn finalTurn, Integer compactOnLoadKeepRecentTurns, List<String> compactionStrategies, LessonRecorder lessonRecorder) throws Exception {
        final long t0 = System.nanoTime();
        final var start = Instant.now();
        final String reasoningEffort = DEFAULT_REASONING.getOrDefault(reasoningKind(name), "medium");
        final var session = new AgentSession(sessionDir, sessionId, continueSession);
        List<ChatMessage> msgs;
        int preloadCompactions = 0;
        if (continueSession && session.exists()) {
            msgs = session.loadMessages();
            if (compactOnLoadKeepRecentTurns != null) {
                preloadCompactions = AgentSession.compact(msgs, compactOnLoadKeepRecentTurns, 0, compactionStrategies.contains(CompactionStrategies.TOOL_OUTPUT_RECALL));
                // compacted proactively on load, not reactively at an observed over-threshold prompt
                // in THIS invocation's own in-memory lastPrompt (which starts at 0 for every fresh
                // run() call - #300's own diagnosis) - but the session being RESUMED almost always
                // already has a real, recent prompt size recorded from its own prior turns, on disk.
                // #580, found live: before this, every preload compaction wrote NO "prompt tokens
                // before" at all (the 2-arg overload - #505's own documented, deliberate choice for
                // a session with genuinely nothing to measure yet) - but since this harness's own
                // turn-limited/length-capped continuations are a frequent, normal occurrence, "no
                // measurement" had become the common outcome recorded, not the rare edge case #505
                // anticipated. AgentSession.lastPromptTokens() reads the session's own last known
                // real figure back off disk - still null (2-arg, unmeasured) for a GENUINELY fresh
                // session with no prior assistant turn at all, same as before.
                if (preloadCompactions > 0) {
                    final Long lastKnownPrompt = AgentSession.lastPromptTokens(session.path());
                    if (lastKnownPrompt != null && lastKnownPrompt > 0) session.compaction(preloadCompactions, false, lastKnownPrompt.intValue());
                    else session.compaction(preloadCompactions, false);
                }
            }
            msgs.add(UserMessage.from(instruction));
            session.user(instruction);
        } else {
            String system = advisoryOnly ? appendSystem
                    : SYSTEM.replace("{cwd}", cwd).replace("{date}", start.toString().substring(0, 10))
                            + (appendSystem == null ? "" : "\n\n" + java.nio.file.Files.readString(Path.of(appendSystem)));
            msgs = new ArrayList<>(List.of(SystemMessage.from(system), UserMessage.from(instruction)));
            session.header(AGENT_VERSION, model == null ? props.model() : model, cwd, reasoningEffort, name);
            session.system(system);
            session.user(instruction);
        }
        // Streaming, not the request/response OpenAiChatModel this used to be (R10). The blocking
        // model was two bugs at once: (1) its synchronous JDK HttpClient.send() does NOT reliably
        // abort on Thread.interrupt() (confirmed live via jstack - a cancelled job sat WAITING
        // there for 1168s+ with cancel_requested already true and Future.cancel(true) already
        // called), so cancellation only worked at all because of a blanket per-call timeout; and
        // (2) that same blanket timeout gave up on calls that were still genuinely working (this
        // local model server can run under 5 tok/s under real load - Docker and the model compete
        // for memory, per the task prompt), and because oMLX only notices a client is gone when it
        // next tries to WRITE to it, an abandoned-but-not-dead generation just kept running to
        // completion in the background, competing for the same GPU as the retry that replaced it -
        // the oMLX dashboard showed 5 concurrent "Generating..." entries from this, a feedback loop
        // (each retry slower, causing more retries).
        //
        // langchain4j-bom bumped 1.1.0 -> 1.20.0 alongside this (R11) specifically for
        // StreamingHandle: 1.1.0's StreamingChatResponseHandler had no way to reach the
        // underlying connection at all, so giving up on a stalled stream really was just walking
        // away and hoping oMLX noticed. 1.20.0's PartialResponseContext/PartialThinkingContext/
        // PartialToolCallContext (see StreamCollector) expose a real handle.cancel() that closes
        // the InputStream and actually aborts the exchange - confirmed against
        // ChatCompletionEventDispatcher's bytecode for the OpenAI-compatible client this project
        // uses. No .maxRetries(...) here: OpenAiStreamingChatModel's builder doesn't have one -
        // streaming has no langchain4j-internal retry to disable in the first place.
        //
        // .returnThinking(true) (R15): without it, onPartialThinking NEVER fires - reproduced in
        // isolation directly against oMLX (0 calls with reasoning_content genuinely streaming for
        // 40+s; 6 calls, immediately, once this flag is set - same request otherwise). Silently
        // meant StreamCollector.touch() only ever saw content/tool-call deltas, so
        // firstTokenTimeoutMs was blind to a session spending its whole budget reasoning before
        // its first visible content or tool call - exactly the "no first token in 180s" abort found
        // live on a PARALLEL_PLAN session that had 8861 chars of reasoning already in the proxy's
        // own journal at the moment it was killed.
        StreamingChatModel chatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(proxyBase == null ? props.endpoint() : proxyBase)
                .apiKey(apiKeyFor(proxyBase, model, extraEnv))
                .modelName(model == null ? props.model() : model)
                .defaultRequestParameters(OpenAiChatRequestParameters.builder().reasoningEffort(reasoningEffort).build())
                .returnThinking(true)
                // lets RecordingProxy attribute every journaled request to this session
                // unambiguously (even under concurrent parallel-wave sessions), for the live
                // per-session prefill/decode speed averages
                .customHeaders(Map.of(com.strgmai.ace.service.proxy.RecordingProxy.SESSION_HEADER, session.sessionId))
                .build();
        // #332: unlike recall, lesson recording is NOT a CompactionStrategies member - it has
        // nothing to do with context compaction, it's a cross-task knowledge-sharing capability
        // that happens to need the same "null caller == disabled" shape judge/finalTurn already
        // use. null (every call site that doesn't build a real one) already means disabled.
        final List<ToolSpecification> specs = advisoryOnly ? List.of() : toolSpecs(compactionStrategies.contains(CompactionStrategies.TOOL_OUTPUT_RECALL),
                lessonRecorder != null);
        // the run's SCRUBBED environment (fresh HOME, docker shim, pinned JAVA_HOME, AB_RUN_ID) is the
        // base for every tool call; extraEnv null = a bare unit-test context
        Map<String, String> env = toolEnv(extraEnv, cwd);

        int turns = 0, toolErrors = 0, compactions = preloadCompactions, lastPrompt = 0;
        String finish = null; int rc = 0;
        FinalTurn pendingFinalTurn = finalTurn;   // consumed (set null) the first time it fires - see FinalTurn's own javadoc
        // found live (job cd4c11a8, T6): some models occasionally WRITE a tool call as literal text
        // (e.g. "<function=bash><parameter=command>...</parameter></function></tool_call>") instead
        // of actually using the real function-calling mechanism - finish_reason comes back "stop"
        // with zero real tool calls, so the turn looks like a natural, intentional stop. The task
        // then silently ends one real action short of what the model was clearly trying to do.
        // #653-followup, found live (job 76b308ac, T8): a ONE-shot nudge (the original fix here)
        // is not enough - the SAME model repeated the EXACT same text-mimicked-call mistake on the
        // very next turn, immediately after being nudged, and with no nudge left for a second
        // occurrence, burned its remaining turn budget without ever recovering ("unfinished -
        // budget exhausted... no further output after 31 turns"). A nudge costs one cheap turn
        // either way (the model was going to consume a turn regardless of whether this fires), so
        // capping at a handful of attempts costs nothing extra on the failure path while giving a
        // model that eventually self-corrects several real chances to, instead of just one.
        int textToolCallNudges = 0;
        // #417 follow-up review: both scoped to THIS session's own turn loop, never shared across
        // sessions - compactionState lets structured_eviction skip a redundant re-summarization
        // (C9, see CompactionStrategies.State's own javadoc); recallCounts bounds how many times
        // the SAME archived tool-call id can be recalled this session (C12 below).
        final CompactionStrategies.State compactionState = new CompactionStrategies.State();
        final Map<String, Integer> recallCounts = new HashMap<>();
        // #326: a cheap, deterministic, same-turn circuit breaker for literal tool-call repetition -
        // see ThrashGuard's own javadoc for why this is a loop mechanic (always on, zero config),
        // not routed through JudgeCheckpoint the way the policy-configurable LLM judge is.
        final ThrashGuard.State thrashState = new ThrashGuard.State();
        // wallSec <= 0 means unlimited (the harness's own runBounded() is the real enforcement for
        // that - see its javadoc); deadline here only clamps chatWithRetry's exponential backoff
        // sleep, so it must stay far in the future rather than collapsing to "now" (which would
        // hammer the model server with ~100ms retries instead of a real backoff).
        final long deadline = wallSec > 0 ? System.currentTimeMillis() + wallSec * 1000 : Long.MAX_VALUE;
        while (turns < maxTurns) {
            if (compactionTrigger > 0 && lastPrompt > 0 && lastPrompt > compactionTrigger) {
                // #417: the protected window's own ceiling is half of whatever triggers compaction
                // in the first place - self-scaling with compactionTrigger (28000 operator default,
                // or whatever a run/task override sets) rather than a second magic number, and
                // guarantees there is always real headroom left to free (the root cause found live:
                // a protected window with NO ceiling can itself grow to consume the entire budget).
                final CompactionStrategies.Result cr = CompactionStrategies.apply(compactionStrategies, msgs, props.keepRecentTurns(), compactionTrigger / 2, compactionState,
                        range -> {
                            // #417 follow-up review (C5): the range's OWN SystemMessage (always
                            // range.get(0)) used to sit alongside a SECOND SystemMessage prepended
                            // here, with the actual instruction maximally far from the generation
                            // point - two chat-template-dependent risks "The Pitfalls of KV Cache
                            // Compression" (arXiv 2510.00231) names as real degradation factors
                            // (multiple system-ish messages, instruction order) for exactly this
                            // kind of request. One real SystemMessage, instruction last (closest to
                            // generation) as a plain UserMessage, same as any other ask-the-model-
                            // to-do-one-thing request elsewhere in this class.
                            final List<ChatMessage> req = new ArrayList<>(range);
                            req.add(UserMessage.from(Packs.STRUCTURED_EVICTION_INSTRUCTION));
                            final ChatResponse r = chatWithRetry(chatModel, ChatRequest.builder().messages(req).build(), deadline, abortProxy, firstTokenTimeoutMs);
                            // #417 follow-up review (C8): this side call spends real tokens
                            // RecordingProxy already charges to the task's own budget, but nothing
                            // recorded it in the SESSION file's own accounting - AgentSession.usage()
                            // (the fallback token source for a session with no proxy) silently
                            // undercounted it. A journaling failure must never discard an otherwise-
                            // good summary, so it's caught and logged here, not left to propagate
                            // into this method's own caller (which would wrongly treat it as if the
                            // summarization itself had failed).
                            try {
                                final var u = r.metadata() == null ? null : r.metadata().tokenUsage();
                                session.sidecarUsage("structured_eviction", u == null || u.inputTokenCount() == null ? 0 : u.inputTokenCount(),
                                        u == null || u.outputTokenCount() == null ? 0 : u.outputTokenCount());
                            } catch (IOException e) {
                                log.warn("could not journal structured_eviction's own token usage for session {}: {}", session.sessionId, e.toString());
                            }
                            return r.aiMessage().text();
                        });
                if (cr.masked() > 0 || cr.summarized()) { compactions++; session.compaction(cr.masked(), cr.summarized(), lastPrompt); }
            }
            ChatResponse resp;
            try {
                resp = chatWithRetry(chatModel, ChatRequest.builder().messages(msgs).toolSpecifications(specs).build(), deadline, abortProxy, firstTokenTimeoutMs);
            } catch (BudgetExhausted e) {
                rc = 3; finish = "budget";
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
            } catch (ContextWindowExceeded e) {
                rc = 4; finish = "context_window";
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
            } catch (TransientError e) {
                // rc=2 alone gives no way to tell auth failure/5xx/network drop apart after the
                // fact - the real cause (e's cause carries the HTTP status per chatWithRetry) is
                // otherwise gone the moment this method returns. Passed as a trailing Throwable,
                // not a {} substitution: SLF4J's MessageFormatter strips a trailing Throwable arg
                // for stack-trace purposes even when a matching {} placeholder was intended to
                // consume it, silently leaving that placeholder unprinted with no trace at all
                // (verified live - this line printed "...exhausted: {}" with nothing following).
                // A real stack trace here is strictly more useful than the toString() this was
                // going for anyway.
                log.warn("session {} ended (rc=2) after retries were exhausted", name, e.getCause() == null ? e : e.getCause());
                session.end(turns, toolErrors, compactions, finish);
                return result(name, 2, t0, finish, turns, toolErrors, compactions, session, start);
            }
            turns++;
            final AiMessage ai = resp.aiMessage();
            finish = resp.metadata() == null || resp.metadata().finishReason() == null ? finish
                    : resp.metadata().finishReason().name().toLowerCase();
            Integer promptTokens = resp.metadata() == null || resp.metadata().tokenUsage() == null || resp.metadata().tokenUsage().inputTokenCount() == null
                    ? null : resp.metadata().tokenUsage().inputTokenCount();
            if (promptTokens != null) lastPrompt = promptTokens;
            Integer completion = resp.metadata() == null || resp.metadata().tokenUsage() == null || resp.metadata().tokenUsage().outputTokenCount() == null
                    ? null : resp.metadata().tokenUsage().outputTokenCount();
            final List<ToolExecutionRequest> calls = ai.hasToolExecutionRequests() ? ai.toolExecutionRequests() : List.of();
            session.assistant(ai.thinking(), ai.text(), calls, finish, Map.of("input", promptTokens == null ? 0 : promptTokens,
                    "output", completion == null ? 0 : completion, "cached", cachedTokens(resp)));
            msgs.add(sanitizedForHistory(ai, calls));
            if (calls.isEmpty()) {
                // see looksLikeATextToolCallAttempt()'s own javadoc - checked BEFORE the FinalTurn
                // hook below, since this is a formatting failure to correct, not a genuine
                // "the agent is done" signal.
                if (textToolCallNudges < MAX_TEXT_TOOL_CALL_NUDGES && looksLikeATextToolCallAttempt(ai.text())) {
                    textToolCallNudges++;
                    final String nudge = "Your previous message looked like an attempted tool call written as "
                            + "plain text, not a real tool call - nothing executed. Make the SAME call again, "
                            + "but using your actual tool-calling mechanism, not text.";
                    msgs.add(UserMessage.from(nudge));
                    session.user(nudge);
                    continue;
                }
                // #300/#302: the task is about to naturally stop - give the one-shot FinalTurn hook
                // a chance to ask for one more live turn (e.g. a handoff note) before actually
                // ending, while this connection's own accumulated prefix cache is still warm.
                // Consumed unconditionally (pendingFinalTurn = null) whether or not it asks for
                // anything, so a SECOND natural stop later in this same call always ends for real.
                // Never offered on a `length` finish: calls is empty here because the response was
                // CUT OFF, not because the model actually finished - RunBench's own length-retry
                // mechanism (shouldRetryOnLengthFinish) needs this call's real `finish` value
                // untouched to decide whether to continue it; firing the hook here would overwrite
                // `finish` with whatever THIS turn's own response produces instead, silently
                // skipping that retry and asking for a handoff on a task that may not have actually
                // finished writing its real output yet.
                if (pendingFinalTurn != null && !"length".equals(finish)) {
                    final String ask = pendingFinalTurn.ask(msgs);
                    pendingFinalTurn = null;
                    if (ask != null && !ask.isBlank()) {
                        msgs.add(UserMessage.from(ask));
                        session.user(ask);
                        continue;
                    }
                }
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);   // done: answered without a tool call
            }
            String thrashWarning = null;
            for (ToolExecutionRequest c : calls) {
                Map<String, Object> args;
                try { args = MAPPER.readValue(c.arguments(), Map.class); }
                catch (Exception e) {
                    // executing the tool with {} instead of the model's real (malformed) args
                    // silently changes what the tool actually does, with nothing pointing at why
                    log.warn("could not parse tool call arguments for {} ({}), executing with no arguments: {}", c.name(), c.id(), e.toString());
                    args = Map.of();
                }
                final AgentTools.Outcome out = executeTool(c.name(), args, cwd, env, name, session, recallCounts, compactionStrategies, lessonRecorder);
                if (out.isError()) toolErrors++;
                msgs.add(ToolExecutionResultMessage.from(c, out.output()));
                session.toolResult(c.id(), c.name(), out.output(), out.failureCategory());
                // #326: checked AFTER the call executes (a repeated call that keeps erroring out is
                // the single most common real thrash shape) - last one wins if a turn somehow trips
                // it on more than one of its own calls.
                final String w = ThrashGuard.check(c.name(), c.arguments(), args, thrashState);
                if (w != null) thrashWarning = w;
            }
            if (thrashWarning != null) {
                msgs.add(UserMessage.from(thrashWarning));
                session.user(thrashWarning);
            }
            // #265: a mid-task judge checkpoint - this turn's own output/tool-results are already
            // appended to msgs above, so the judge sees the full, settled trajectory through right
            // now; its recommendation (if any) lands as the NEXT turn's own instruction, same as
            // the length-continuation nudge already does elsewhere. The every-N-turns decision is
            // deliberately NOT made here - this loop just calls the hook every turn and lets the
            // caller's own closure (RunBench) decide whether turn N is actually due for a check,
            // same "ReferenceAgent owns loop mechanics, RunBench owns policy" split abortProxy and
            // every other callback parameter already follow.
            if (judge != null) {
                final String rec = judge.check(turns, msgs);
                if (rec != null && !rec.isBlank()) {
                    msgs.add(UserMessage.from(rec));
                    session.user(rec);
                }
            }
            // no wall-budget exit here anymore: the harness enforces task-wall preemptively, from
            // outside, even mid-request (RunBench.sessionWithPolicy) - a session that overruns it is
            // interrupted and the harness itself builds the rc=124 result, not this loop
        }
        // a turn-capped exit (rc still 0) means the model never stopped on its own: report a failure, not a success
        if (rc == 0 && turns >= maxTurns) { rc = 1; finish = finish == null ? "turn_limit" : finish; }
        session.end(turns, toolErrors, compactions, finish);
        return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
    }

    private SessionResult result(final String id, final int rc, final long t0, final String finish, final int turns, final int toolErrors, final int compactions, final AgentSession s, final Instant start) {
        final double secs = (System.nanoTime() - t0) / 1e9;
        return new SessionResult(id, rc, Math.round(secs * 10) / 10.0, finish, turns, toolErrors, compactions, s.path(), start, Instant.now());
    }

    /** #417 follow-up review (C12): bounds how many times the SAME archived tool-call id may be
     *  recalled within one session - found live: a recalled output is journaled as a fresh
     *  toolResult (via the ordinary msgs.add()/session.toolResult() path right after this method
     *  returns), which is itself immediately re-maskable by the NEXT compaction pass, making
     *  recall -> mask -> recall of the SAME id an unguarded cycle, each lap costing a full tool
     *  output of context. A small cap breaks the cycle while still allowing the legitimate case
     *  (recalling a few different ids once each) unhindered. */
    private static final int MAX_RECALLS_PER_ID = 3;

    // package-private (not private) and static: no instance state is used, matching this class's
    // existing pattern for directly-testable helpers (reasoningKind/isEmptyModelResponse/
    // cachedTokens) - recall_tool_output's own cap (C12) and blank-id handling (C14) are tested
    // directly against this method rather than through a full live run() call.
    /** `enabledStrategies` defaults to CompactionStrategies.DEFAULT at every call site that does
     *  not care about #423/#424 specifically (this class's own tests exercising read/write/edit,
     *  or recall WHILE enabled) - both toggles are on by default, matching today's own existing
     *  behavior before either had an off switch. */
    static AgentTools.Outcome executeTool(final String name, final Map<String, Object> args, final String cwd, final Map<String, String> env,
                                           final String sessionName, final AgentSession session, final Map<String, Integer> recallCounts,
                                           final List<String> enabledStrategies) {
        return executeTool(name, args, cwd, env, sessionName, session, recallCounts, enabledStrategies, null);
    }

    /** #332: `lessonRecorder` backs the record_lesson case below - null (every call site above)
     *  means the tool is unavailable, same convention as every other optional capability here. */
    static AgentTools.Outcome executeTool(final String name, final Map<String, Object> args, final String cwd, final Map<String, String> env,
                                           final String sessionName, final AgentSession session, final Map<String, Integer> recallCounts,
                                           final List<String> enabledStrategies, final LessonRecorder lessonRecorder) {
        try {
            return switch (name) {
                case "read" -> AgentTools.read(cwd, args);
                case "write" -> AgentTools.write(cwd, args);
                case "edit" -> AgentTools.edit(cwd, args);
                case "bash" -> AgentTools.bash(cwd, args, env, enabledStrategies.contains(CompactionStrategies.ERROR_LINE_EXTRACTION));
                case "record_lesson" -> {
                    // #332: structurally unreachable once toolSpecs()'s own identical null-check
                    // stops offering this tool at all - same "a real behavior change, not just
                    // hiding the affordance" reasoning as recall_tool_output's own identical guard
                    // below. Not a CompactionStrategies member (see toolSpecs' own call site) -
                    // null alone, same convention judge/finalTurn already use, means disabled.
                    if (lessonRecorder == null)
                        yield new AgentTools.Outcome("error: record_lesson is disabled for this session", AgentTools.FailureCategory.MALFORMED_ARGS);
                    final String text = String.valueOf(args.getOrDefault("text", "")).strip();
                    if (text.isBlank()) yield new AgentTools.Outcome("error: text is required", AgentTools.FailureCategory.MALFORMED_ARGS);
                    lessonRecorder.record(text);
                    yield new AgentTools.Outcome("recorded", null);
                }
                case "recall_tool_output" -> {
                    // #423/#424: structurally unreachable once toolSpecs()'s own identical check
                    // stops offering this tool at all - this guard is what makes the toggle a real
                    // behavior change rather than just hiding the affordance (a test, or a model
                    // that already has this id in context from before the toggle flipped, could
                    // still try calling it directly).
                    if (!enabledStrategies.contains(CompactionStrategies.TOOL_OUTPUT_RECALL))
                        yield new AgentTools.Outcome("error: recall_tool_output is disabled for this session", AgentTools.FailureCategory.MALFORMED_ARGS);
                    final String id = String.valueOf(args.getOrDefault("tool_call_id", ""));
                    // #417 follow-up review (C14): a blank id used to fall through to "error: no
                    // archived output for tool_call_id " with a confusing trailing space - callable
                    // in practice if the model omits the required argument (MAPPER.readValue above
                    // can still hand this method an empty Map on a malformed tool call).
                    if (id.isBlank()) yield new AgentTools.Outcome("error: tool_call_id is required", AgentTools.FailureCategory.MALFORMED_ARGS);
                    final int seen = recallCounts.merge(id, 1, Integer::sum);
                    if (seen > MAX_RECALLS_PER_ID) {
                        log.info("session {} refused a repeated recall of {} (requested {} times this session)", sessionName, id, seen);
                        yield new AgentTools.Outcome("error: tool_call_id " + id + " has already been recalled " + (seen - 1)
                                + " time(s) this session - repeatedly re-reading the same dropped output usually means a "
                                + "different approach is needed (e.g. reading the relevant file/state directly instead)", AgentTools.FailureCategory.MALFORMED_ARGS);
                    }
                    final String text = AgentSession.recallToolOutput(session.path(), id);
                    // #424: a usage metric the review found missing - MemGPT/Letta's own result
                    // depends on the model actually paging content back in; without a log line
                    // there would be no way to tell whether a local model ever does, versus this
                    // whole mechanism quietly doing nothing
                    log.info("session {} recalled archived tool output {} ({}, attempt {})", sessionName, id, text == null ? "not found" : "found", seen);
                    yield text == null ? new AgentTools.Outcome("error: no archived output for tool_call_id " + id, AgentTools.FailureCategory.NOT_FOUND)
                            : new AgentTools.Outcome(text, null);
                }
                default -> new AgentTools.Outcome("error: unknown tool " + name, AgentTools.FailureCategory.MALFORMED_ARGS);
            };
        } catch (Exception e) { return new AgentTools.Outcome("error: " + e, AgentTools.FailureCategory.COMMAND_CRASHED); }
    }

    /** found live (T4 rescue, job 416dcfb6): a model that emits a syntactically invalid tool-call
     *  `arguments` string (seen: Nemotron truncating a JSON object mid-emission, missing its
     *  closing brace) must not poison every later request in THIS live turn loop. AgentSession's
     *  own persisted record already recovers from this (falls back to {"_unparsed": ...} - see
     *  AgentSession.assistant()), but msgs here was always built straight from the model's raw
     *  AiMessage, so the same malformed string got resent verbatim as conversation history on
     *  every subsequent request - and most OpenAI-compatible providers themselves validate that
     *  `arguments` is well-formed JSON, so this turned one bad tool call into an immediate,
     *  non-retryable 400 (chatWithRetry's own "4xx are final") on the very next request, ending
     *  the whole turn loop (rc=2) instead of just costing the one turn. Mirrors AgentSession's own
     *  {"_unparsed": ...} rewrite exactly, so a resumed (--continue) session and a live turn loop
     *  see byte-identical history for the same malformed call. */
    static AiMessage sanitizedForHistory(final AiMessage ai, final List<ToolExecutionRequest> calls) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (calls.isEmpty()) return ai;
        boolean anyBad = false;
        final List<ToolExecutionRequest> fixed = new ArrayList<>(calls.size());
        for (final ToolExecutionRequest c : calls) {
            try { MAPPER.readTree(c.arguments()); fixed.add(c); }
            catch (Exception e) {
                anyBad = true;
                fixed.add(ToolExecutionRequest.builder().id(c.id()).name(c.name())
                        .arguments(MAPPER.writeValueAsString(Map.of("_unparsed", c.arguments()))).build());
            }
        }
        return anyBad ? AiMessage.from(ai.text(), fixed) : ai;
    }

    /** port of chat_retry: 4 attempts with backoff on transient (5xx/connection) errors; 4xx are
     *  final; the proxy's 429 budget refusal ends the session.
     *
     *  LangChain4j 1.1.0 classifies for us: its ExceptionMapper turns the HTTP status into a typed
     *  exception (HttpException carries statusCode(); 5xx -> InternalServerException, 401/403 ->
     *  AuthenticationException, 404 -> ModelNotFoundException, 408 -> TimeoutException, 429 ->
     *  RateLimitException, other 4xx -> InvalidRequestException) under RetriableException /
     *  NonRetriableException. Status codes, not substrings, decide here - onError hands back the
     *  same exception shapes the old synchronous chat() threw, so this classification is unchanged.
     *
     *  abortProxy.accept(reason) on every way out of an attempt (R12): confirmed live via jstack
     *  that giving up here - idle-timeout, a real error, or interruption - left RecordingProxy's OWN
     *  relay thread for that attempt permanently blocked reading from oMLX, one per abandoned
     *  attempt, accumulating (6 stuck threads found after normal today's-worth of testing).
     *  collector.cancelIfPossible() inside awaitStream only closes the agent<->proxy hop; this is
     *  the proxy<->oMLX hop, where the actual GPU-consuming work was still happening. Harmless to
     *  call after the exchange already finished on its own (onError already fired) - closing an
     *  already-closed/already-removed stream is a no-op in RecordingProxy.abortInflight(). The
     *  reason string is journaled (RecordingProxy) for display against the aborted request - an
     *  interrupt here is ambiguous from inside chatWithRetry alone (a cancelled job and the
     *  harness's own task-wall timeout both just look like Thread.interrupt()); the harness's own
     *  runBounded names the more specific "task-wall budget exceeded" reason itself when that is
     *  the actual cause, which may race harmlessly with this generic one (both diagnostic only). */
    static ChatResponse chatWithRetry(final StreamingChatModel model, final ChatRequest req, final long deadline, final Consumer<String> abortProxy, final long firstTokenTimeoutMs) {
        RuntimeException last = null;
        long delay = 2000;
        for (int attempt = 0; attempt < 4; attempt++) {
            final StreamCollector collector = new StreamCollector();
            try {
                model.chat(req, collector);
                final ChatResponse resp = awaitStream(collector, firstTokenTimeoutMs);
                if (isEmptyModelResponse(resp)) {
                    // the stream itself completed (onCompleteResponse fired, not onError) - nothing
                    // is stuck on the proxy<->oMLX hop to abort here, unlike the RuntimeException/
                    // interrupt paths below, so abortProxy is deliberately not called
                    log.warn("chatWithRetry attempt {}/4 got an empty model response (no content, "
                            + "no tool_calls, no finish_reason) - retrying as transient", attempt + 1);
                    last = new RuntimeException("empty model response (no content, no tool_calls, no finish_reason)");
                    try { Thread.sleep(Math.min(delay, Math.max(100, deadline - System.currentTimeMillis()))); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new TransientError(ie);
                    }
                    delay = (long) (delay * 2.5);
                    continue;
                }
                return resp;
            }
            catch (InterruptedException ie) {
                // a cancelled job interrupts this thread (WorkerService.Future.cancel(true)) - restoring
                // the flag and retrying anyway would absorb the cancellation as just one more transient
                // failure and keep going for up to 3 more attempts; terminal, not classified below
                Thread.currentThread().interrupt();
                abortProxy.accept("interrupted (job cancelled, or the task-wall budget was reached)");
                throw new TransientError(ie);
            }
            catch (RuntimeException e) {
                abortProxy.accept(e.getMessage() != null ? e.getMessage() : e.toString());
                last = e;
                if (isBudgetRefusal(e)) throw new BudgetExhausted(e);
                if (isContextWindowRefusal(e)) throw new ContextWindowExceeded(e);
                final Integer status = httpStatus(e);
                boolean clientError = status != null ? status >= 400 && status < 500 && status != 429
                        : hasCause(e, dev.langchain4j.exception.NonRetriableException.class);
                if (clientError) throw new TransientError(e);   // final client errors
                try { Thread.sleep(Math.min(delay, Math.max(100, deadline - System.currentTimeMillis()))); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TransientError(ie);
                }
                delay = (long) (delay * 2.5);
            }
        }
        throw new TransientError(last);
    }

    /** default cap on time-to-FIRST-token, before any chunk has arrived at all: a cold model server
     *  (loading weights, queued behind another request, prefill on a long prompt) stalls for a
     *  structurally different reason than a mid-response stall does (see MID_STREAM_IDLE_TIMEOUT_MS
     *  below for that one) - a request that never starts producing anything has no other signal to
     *  wait on. Per-run override: RunSpec.firstTokenTimeout / --first-token-timeout. 0 means
     *  unlimited (the system-wide "0 = no budget" convention). */
    static final long DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 0;

    /** #466, found live 2026-10-27: a stream that genuinely died mid-response - a dead SSH-tunneled
     *  connection, a crashed upstream - had no bound at all once past its first token, only the
     *  harness's own task-wall budget (hours, not minutes). One dead stream sat idle for ~6 hours
     *  before anything noticed (confirmed dead, not slow: near-zero CPU on the model server process
     *  the whole time, not throttled computation). A mid-stream idle ceiling existed here once and
     *  was removed 2026-09-25 after it fired on oMLX's own legitimate memory-pressure throttling
     *  (prefill LRU eviction cycles running well past whatever fixed value it used) - discarding real
     *  partial generation. 30 minutes of LITERAL zero new chunks is deliberately far more generous
     *  than that: legitimate throttling still produces some progress within tens of minutes, a truly
     *  dead stream produces none at all, ever. Unconditional (not gated behind firstTokenTimeoutMs>0
     *  the way the first-token check is) - a dead connection should never be allowed to run out the
     *  clock regardless of whether the operator wants an unlimited time-to-first-token. */
    private static final long MID_STREAM_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long POLL_MS = 2_000;

    /** provider API keys that may reach {@code extraEnv} (an external reviewer's own credential, or a
     *  provider key from the operator's environment) - never let one of these into the tool-execution
     *  environment handed to the (untrusted) model's own bash tool (#98). Kept in sync by hand with
     *  {@code apiKeyFor()}'s and {@code Reviews.externalCredentials()}'s provider lists. */
    static final Set<String> CREDENTIAL_ENV_KEYS = Set.of(
            "OPENAI_API_KEY", "OPENROUTER_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "NEBIUS_API_KEY");

    /** the tool-execution environment for a session: {@code extraEnv} when given (the run's own
     *  scrubbed environment - RunBenchSupport.scrubbedEnv - or an external reviewer's credential-only
     *  map from Reviews.externalCredentials), with any provider API key stripped (#98: the model's own
     *  bash tool must never see the credential apiKeyFor() uses for the chat client) and a baseline
     *  PATH/HOME/LANG guaranteed even when extraEnv didn't carry one (an external reviewer's
     *  credential-only map otherwise leaves its bash tool unable to find any binary at all).
     *
     *  #149 (live incident, 2026-09-27): HOME here NEVER falls back to the real operator home - a
     *  reviewer/bare-context session that reaches this fallback gets one isolated under cwd instead.
     *  That alone is not enough, though: a JVM-based tool the agent runs (gradle, maven, a bare
     *  `java`) resolves its OWN home via System.getProperty("user.home"), which on this JVM/OS reads
     *  the OS password database (getpwuid), not the HOME env var - so even scrubbedEnv's already-
     *  isolated HOME never reached it. `gradle --stop` run inside a "sandboxed" session therefore
     *  targeted the REAL ~/.gradle/daemon registry and killed two unrelated host bootRun daemons.
     *  JAVA_TOOL_OPTIONS is honored by every JVM (not just the java launcher) and pins user.home to
     *  match, closing that specific vector for every tool-execution env that passes through here. */
    static Map<String, String> toolEnv(final Map<String, String> extraEnv, final String cwd) {
        final Map<String, String> env = extraEnv != null ? new LinkedHashMap<>(extraEnv) : new LinkedHashMap<>();
        env.keySet().removeAll(CREDENTIAL_ENV_KEYS);
        env.putIfAbsent("HOME", Path.of(cwd, ".ace-tool-home").toString());
        env.putIfAbsent("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        env.putIfAbsent("LANG", "en_US.UTF-8");
        env.putIfAbsent("CI", "1");
        env.putIfAbsent("NO_COLOR", "1");
        env.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + env.get("HOME"));
        return env;
    }

    /** Waits for one streaming attempt by polling instead of blocking on it, so an interrupt
     *  (cancellation, or the harness's own task-wall timeout - see RunBench.sessionWithPolicy) is
     *  noticed within POLL_MS instead of depending on the HTTP call itself honoring
     *  Thread.interrupt() - which the old synchronous client did not (see the chatModel comment
     *  above). Two independent idle ceilings, both measured off the same collector.lastActivityMs:
     *  firstTokenTimeoutMs (only before anything has arrived - see its own comment) and
     *  MID_STREAM_IDLE_TIMEOUT_MS (only after - see its own comment on why 30 minutes doesn't repeat
     *  2026-09-25's false-positive). Past both, only the harness's task-wall timeout (an external
     *  interrupt) bounds this further. */
    private static ChatResponse awaitStream(final StreamCollector collector, final long firstTokenTimeoutMs) throws InterruptedException {
        while (true) {
            try {
                return collector.future.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException pollTimeout) {
                final long idleMs = System.currentTimeMillis() - collector.lastActivityMs;
                // firstTokenTimeoutMs <= 0 means unlimited (the system-wide "0 = no budget"
                // convention) - never time out waiting for a first token in that case
                if (firstTokenTimeoutMs > 0 && !collector.gotFirstToken && idleMs > firstTokenTimeoutMs) {
                    collector.cancelIfPossible();   // real abort now (R10), not just walking away
                    throw new RuntimeException("no first token from the model in " + (firstTokenTimeoutMs / 1000) + "s");
                }
                if (collector.gotFirstToken && idleMs > MID_STREAM_IDLE_TIMEOUT_MS) {
                    collector.cancelIfPossible();
                    throw new RuntimeException("no new output from the model in " + (MID_STREAM_IDLE_TIMEOUT_MS / 1000) + "s - stream appears dead");
                }
                // still within both budgets
            } catch (ExecutionException ee) {
                final var cause = ee.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                collector.cancelIfPossible();
                throw ie;
            }
        }
    }

    /** Accumulates one streaming attempt's outcome into a plain future chatWithRetry can poll, and
     *  captures the first StreamingHandle handed back by any partial-content callback so a stalled
     *  or abandoned stream can be genuinely cancelled (R10) instead of just left running - which is
     *  exactly what orphaned generations on the model server before this upgrade (langchain4j 1.1.0
     *  exposed no such handle at all; see the chatModel comment above). langchain4j reassembles the
     *  full response (text + tool calls, across however many chunks they arrived in) internally -
     *  onCompleteResponse hands back the same ChatResponse shape the old synchronous chat()
     *  returned, so nothing downstream of chatWithRetry needed to change for that part. */
    private static final class StreamCollector implements StreamingChatResponseHandler {
        private final CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        private volatile long lastActivityMs = System.currentTimeMillis();
        private volatile boolean gotFirstToken = false;
        private volatile StreamingHandle handle;

        @Override public void onPartialResponse(final PartialResponse response, final PartialResponseContext context) {
            touch(context.streamingHandle());
        }
        @Override public void onPartialThinking(final PartialThinking thinking, final PartialThinkingContext context) {
            touch(context.streamingHandle());
        }
        @Override public void onPartialToolCall(final PartialToolCall toolCall, final PartialToolCallContext context) {
            touch(context.streamingHandle());
        }
        @Override public void onCompleteResponse(final ChatResponse response) { future.complete(response); }
        @Override public void onError(final Throwable error) { future.completeExceptionally(error); }

        private void touch(final StreamingHandle h) {
            lastActivityMs = System.currentTimeMillis();
            gotFirstToken = true;
            if (handle == null) handle = h;
        }

        /** best-effort: handle is null until the first chunk of any kind arrives (#6304) */
        void cancelIfPossible() {
            final var h = handle;
            if (h != null && !h.isCancelled()) h.cancel();
        }
    }

    /** the recording proxy's own 429: it is the phase budget's verdict, not a rate limit, and it ends
     *  the session. The proxy is ours, so the marker is its exact literal (RecordingProxy's error body)
     *  — there is no status code that separates it from a real 429, and LangChain4j hands the body on
     *  only as the exception message. #94: referenced directly from RecordingProxy.BUDGET_REFUSAL_MARKER
     *  rather than re-declared here as its own separate literal - a future wording edit to the message
     *  can no longer silently desync the two. */
    static boolean isBudgetRefusal(final Throwable e) {
        final Integer status = httpStatus(e);
        if (status != null && status != 429) return false;
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
            if (t.getMessage() != null && t.getMessage().contains(com.strgmai.ace.service.proxy.RecordingProxy.BUDGET_REFUSAL_MARKER)) return true;
        return false;
    }

    /** the recording proxy's own 400: it estimates the next request's prompt size BEFORE sending
     *  it and refuses proactively rather than letting the model server fail on an overshoot however
     *  it sees fit to (see RecordingProxy's own comment on that check) - same "our own proxy, so
     *  the marker is its exact literal" reasoning isBudgetRefusal() above already applies, since a
     *  plain 400 status alone means nothing on its own. */
    static boolean isContextWindowRefusal(final Throwable e) {
        final Integer status = httpStatus(e);
        if (status != null && status != 400) return false;
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
            if (t.getMessage() != null && t.getMessage().contains(com.strgmai.ace.service.proxy.RecordingProxy.CONTEXT_WINDOW_REFUSAL_MARKER)) return true;
        return false;
    }

    /** the HTTP status LangChain4j saw, when it kept one (HttpException anywhere in the cause chain) */
    static Integer httpStatus(final Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
            if (t instanceof dev.langchain4j.exception.HttpException h) return h.statusCode();
        return null;
    }

    static boolean hasCause(final Throwable e, final Class<? extends Throwable> type) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
            if (type.isInstance(t)) return true;
        return false;
    }

    /** the oMLX key for the proxy; an EXTERNAL reviewer's own key, from the LLM Providers registry
     *  (#106 note: ProviderStore replaced the old hardcoded ProviderConfig enum here). */
    String apiKeyFor(String proxyBase, String model, Map<String, String> extraEnv) {
        if (extraEnv != null && (proxyBase == null || !proxyBase.contains("127.0.0.1"))) {
            final ProviderStore.Provider provider = providerStore.byBaseUrl(proxyBase);
            if (provider != null && extraEnv.containsKey(provider.providerKey())) return extraEnv.get(provider.providerKey());
        }
        return props.apiKey() == null || props.apiKey().isBlank() ? "none" : props.apiKey();
    }

    static final class BudgetExhausted extends RuntimeException { BudgetExhausted(Throwable c) { super(c); } }
    /** #299-adjacent: distinct from BudgetExhausted even though both are the harness's own proxy
     *  proactively refusing a request rather than the model server failing - RunBench.shouldRescue()
     *  treats both as rescue-eligible (a compacted reconnect fixes either: too much spent, or too
     *  much accumulated history to fit), but they stay separate rc values/finish reasons so a
     *  result still says WHICH one happened, same as this codebase's own convention of keeping
     *  distinct failure reasons distinct everywhere else (rc=124 wall timeout vs rc=3 budget, "stop"
     *  vs "tool_execution" finish reasons, ...). */
    static final class ContextWindowExceeded extends RuntimeException { ContextWindowExceeded(Throwable c) { super(c); } }
    static final class TransientError extends RuntimeException { TransientError(Throwable c) { super(c); } }
}
