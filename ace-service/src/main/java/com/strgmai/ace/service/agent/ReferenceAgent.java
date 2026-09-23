package com.strgmai.ace.service.agent;

import com.strgmai.ace.service.config.BenchProperties;
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
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

    public record SessionResult(String id, int rc, double seconds, String finish, int turns, int toolErrors,
                                int compactions, Path sessionFile, Instant start, Instant end) {}

    private final BenchProperties props;

    public ReferenceAgent(BenchProperties props) { this.props = props; }

    static List<ToolSpecification> toolSpecs() {
        return List.of(
                ToolSpecification.builder().name("read")
                        .description("Read a text file (or a line range of it). Prefer ranges: locate with `bash` (grep -n) first, then read only what you need.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("path", "the file to read")
                                .addIntegerProperty("start_line", "1-based, inclusive")
                                .addIntegerProperty("end_line", "inclusive")
                                .required("path").build())
                        .build(),
                ToolSpecification.builder().name("write")
                        .description("Create or overwrite a file with the given content (directories are created).")
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
                        .build());
    }

    /** run one non-interactive session (or a continuation). The proxy for this session is passed in:
     *  the caller (RunBench) starts a fresh proxy per phase/task with that session's token budget. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Map<String, String> extraEnv) throws Exception {
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, () -> {}, DEFAULT_FIRST_TOKEN_TIMEOUT_MS, props.compactionTrigger(), null, extraEnv);
    }

    /** full form: `model` overrides the configured one (a reviewer, a parallel task, a probe).
     *  `abortProxy` closes the CALLER's RecordingProxy's own upstream connection when this method
     *  gives up on a retry attempt (see chatWithRetry) - cancelling the agent's own
     *  StreamingHandle only closes the agent<->proxy hop; it does nothing for the proxy's
     *  separate, independently-blocking read from oMLX (R12). `firstTokenTimeoutMs` caps how long
     *  a stream may sit with NO chunk at all before it counts as stalled; once the first chunk
     *  arrives, IDLE_TIMEOUT_MS governs instead (see awaitStream). `compactionTrigger` overrides
     *  BenchProperties' operator default (R16): compaction stubs OLD tool outputs to shrink the
     *  prompt, but that edit invalidates oMLX's prefix cache for everything after it, forcing a
     *  full re-prefill under whatever memory pressure the machine is already under - confirmed live
     *  (oMLX's own log: "Prefill interrupted at 16384/18602 tokens" during exactly such a
     *  post-compaction re-prefill, repeatedly, never completing across 4 retries). 0 disables
     *  compaction entirely for a run where that trade is worse than just keeping the full prompt. */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, Runnable abortProxy, long firstTokenTimeoutMs, int compactionTrigger, String model, Map<String, String> extraEnv) throws Exception {
        final long t0 = System.nanoTime();
        final var start = Instant.now();
        final String reasoningEffort = DEFAULT_REASONING.getOrDefault(reasoningKind(name), "medium");
        final var session = new AgentSession(sessionDir, sessionId, continueSession);
        List<ChatMessage> msgs;
        if (continueSession && session.exists()) {
            msgs = session.loadMessages();
            msgs.add(UserMessage.from(instruction));
            session.user(instruction);
        } else {
            String system = SYSTEM.replace("{cwd}", cwd).replace("{date}", start.toString().substring(0, 10))
                    + (appendSystem == null ? "" : "\n\n" + java.nio.file.Files.readString(Path.of(appendSystem)));
            msgs = new ArrayList<>(List.of(SystemMessage.from(system), UserMessage.from(instruction)));
            session.header(AGENT_VERSION, model == null ? props.model() : model, cwd, reasoningEffort);
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
        // meant StreamCollector.touch() only ever saw content/tool-call deltas, so IDLE_TIMEOUT_MS/
        // firstTokenTimeoutMs were blind to a session spending its whole budget reasoning before
        // its first visible content or tool call - exactly the "no first token in 180s" abort found
        // live on a PARALLEL_PLAN session that had 8861 chars of reasoning already in the proxy's
        // own journal at the moment it was killed.
        StreamingChatModel chatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(proxyBase == null ? props.endpoint() : proxyBase)
                .apiKey(apiKeyFor(proxyBase, model, extraEnv))
                .modelName(model == null ? props.model() : model)
                .defaultRequestParameters(OpenAiChatRequestParameters.builder().reasoningEffort(reasoningEffort).build())
                .returnThinking(true)
                .build();
        final List<ToolSpecification> specs = toolSpecs();
        // the run's SCRUBBED environment (fresh HOME, docker shim, pinned JAVA_HOME, AB_RUN_ID) is the
        // base for every tool call; extraEnv null = a bare unit-test context
        Map<String, String> env = extraEnv != null ? new LinkedHashMap<>(extraEnv)
                : new LinkedHashMap<>(Map.of("HOME", System.getProperty("user.home"), "PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), "LANG", "en_US.UTF-8"));
        env.putIfAbsent("CI", "1");
        env.putIfAbsent("NO_COLOR", "1");

        int turns = 0, toolErrors = 0, compactions = 0, lastPrompt = 0;
        String finish = null; int rc = 0;
        final int MAX_TURNS = 400;
        final long deadline = System.currentTimeMillis() + wallSec * 1000;
        while (turns < MAX_TURNS) {
            if (compactionTrigger > 0 && lastPrompt > 0 && lastPrompt > compactionTrigger) {
                final int n = AgentSession.compact(msgs, props.keepRecentTurns());
                if (n > 0) { compactions++; session.compaction(n, lastPrompt); }
            }
            ChatResponse resp;
            try {
                resp = chatWithRetry(chatModel, ChatRequest.builder().messages(msgs).toolSpecifications(specs).build(), deadline, abortProxy, firstTokenTimeoutMs);
            } catch (BudgetExhausted e) {
                rc = 3; finish = "budget";
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
                    "output", completion == null ? 0 : completion, "cached", 0));
            msgs.add(ai);
            if (calls.isEmpty()) {
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);   // done: answered without a tool call
            }
            for (ToolExecutionRequest c : calls) {
                Map<String, Object> args;
                try { args = MAPPER.readValue(c.arguments(), Map.class); }
                catch (Exception e) {
                    // executing the tool with {} instead of the model's real (malformed) args
                    // silently changes what the tool actually does, with nothing pointing at why
                    log.warn("could not parse tool call arguments for {} ({}), executing with no arguments: {}", c.name(), c.id(), e.toString());
                    args = Map.of();
                }
                final AgentTools.Outcome out = executeTool(c.name(), args, cwd, env);
                if (out.isError()) toolErrors++;
                msgs.add(ToolExecutionResultMessage.from(c, out.output()));
                session.toolResult(c.id(), c.name(), out.output(), out.isError());
            }
            if (System.currentTimeMillis() >= deadline) { rc = 124; break; }   // wall budget: the harness kills the tree too
        }
        // a turn-capped exit (rc still 0) means the model never stopped on its own: report a failure, not a success
        if (rc == 0 && turns >= MAX_TURNS) { rc = 1; finish = finish == null ? "turn_limit" : finish; }
        session.end(turns, toolErrors, compactions, finish);
        return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
    }

    private SessionResult result(final String id, final int rc, final long t0, final String finish, final int turns, final int toolErrors, final int compactions, final AgentSession s, final Instant start) {
        final double secs = (System.nanoTime() - t0) / 1e9;
        return new SessionResult(id, rc, Math.round(secs * 10) / 10.0, finish, turns, toolErrors, compactions, s.path(), start, Instant.now());
    }

    private AgentTools.Outcome executeTool(final String name, final Map<String, Object> args, final String cwd, final Map<String, String> env) {
        try {
            return switch (name) {
                case "read" -> AgentTools.read(cwd, args);
                case "write" -> AgentTools.write(cwd, args);
                case "edit" -> AgentTools.edit(cwd, args);
                case "bash" -> AgentTools.bash(cwd, args, env);
                default -> new AgentTools.Outcome("error: unknown tool " + name, true);
            };
        } catch (Exception e) { return new AgentTools.Outcome("error: " + e, true); }
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
     *  abortProxy.run() on every way out of an attempt (R12): confirmed live via jstack that
     *  giving up here - idle-timeout, a real error, or interruption - left RecordingProxy's OWN
     *  relay thread for that attempt permanently blocked reading from oMLX, one per abandoned
     *  attempt, accumulating (6 stuck threads found after normal today's-worth of testing).
     *  collector.cancelIfPossible() inside awaitStream only closes the agent<->proxy hop; this is
     *  the proxy<->oMLX hop, where the actual GPU-consuming work was still happening. Harmless to
     *  call after the exchange already finished on its own (onError already fired) - closing an
     *  already-closed/already-removed stream is a no-op in RecordingProxy.abortInflight(). */
    static ChatResponse chatWithRetry(final StreamingChatModel model, final ChatRequest req, final long deadline, final Runnable abortProxy, final long firstTokenTimeoutMs) {
        RuntimeException last = null;
        long delay = 2000;
        for (int attempt = 0; attempt < 4; attempt++) {
            final StreamCollector collector = new StreamCollector();
            try {
                model.chat(req, collector);
                return awaitStream(collector, firstTokenTimeoutMs);
            }
            catch (InterruptedException ie) {
                // a cancelled job interrupts this thread (WorkerService.Future.cancel(true)) - restoring
                // the flag and retrying anyway would absorb the cancellation as just one more transient
                // failure and keep going for up to 3 more attempts; terminal, not classified below
                Thread.currentThread().interrupt();
                abortProxy.run();
                throw new TransientError(ie);
            }
            catch (RuntimeException e) {
                abortProxy.run();
                last = e;
                if (isBudgetRefusal(e)) throw new BudgetExhausted(e);
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

    /** how long a stream already producing output may go quiet before it counts as stalled, not
     *  slow. Was 90s; raised after live journal data on a slow local reasoning model
     *  (Qwen3.8-27B-graft on oMLX, ~2-4 tok/s under load) showed 67% of one job's calls hitting
     *  this exact threshold mid-reasoning - not stuck, still emitting reasoning_content deltas
     *  (each correctly re-arming this timeout via StreamCollector.touch(), confirmed against
     *  ChatCompletionEventDispatcher's bytecode), just occasionally slower between deltas than
     *  90s allowed for. 180s matches DEFAULT_FIRST_TOKEN_TIMEOUT_MS below rather than introducing
     *  a second magic number. */
    static final long IDLE_TIMEOUT_MS = 180_000;
    /** default cap on time-to-FIRST-token, before any chunk has arrived at all - a separate knob
     *  from IDLE_TIMEOUT_MS (same default value today, but independently configurable per run):
     *  a cold model server (loading weights, queued behind another request, prefill on a long
     *  prompt) stalls for a structurally different reason than a mid-response stall does.
     *  Per-run override: RunSpec.firstTokenTimeout / --first-token-timeout. */
    static final long DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 180_000;
    private static final long POLL_MS = 2_000;

    /** Waits for one streaming attempt by polling instead of blocking on it, so an interrupt
     *  (cancellation) is noticed within POLL_MS instead of depending on the HTTP call itself
     *  honoring Thread.interrupt() - which the old synchronous client did not (see the chatModel
     *  comment above). langchain4j 1.20.0's PartialResponseContext/PartialThinkingContext/
     *  PartialToolCallContext (see StreamCollector) close the gap 1.1.0 had here: text, thinking
     *  AND tool-call deltas all update lastActivityMs now, not just visible text - confirmed
     *  against ChatCompletionEventDispatcher's bytecode, all three route through
     *  InternalStreamingChatResponseHandlerUtils with a real StreamingHandle attached. That's why
     *  IDLE_TIMEOUT_MS can be tighter than the 4 min the pre-upgrade version needed as a safety
     *  margin against that blind spot - 90s is now a genuine "nothing at all is happening" signal,
     *  not a guess that also has to cover ordinary tool-call generation.
     *  Remaining gap, not closed by this upgrade: the handle is only populated once the FIRST
     *  chunk of any kind arrives (langchain4j/langchain4j#6304, still open as of 1.20.0) - there
     *  is no hook between "request sent" and "first byte back", so a stall in that specific window
     *  (e.g. a reasoning model's silent thinking pause before anything streams, or a slow network
     *  handshake) still can't be force-cancelled, only waited out for firstTokenTimeoutMs same as
     *  before. collector.cancelIfPossible() below is a no-op until the handle exists.
     *  Two thresholds, not one: before the first chunk of any kind, lastActivityMs is still the
     *  attempt's start time, so this is really "time to first token" and gets the looser,
     *  configurable firstTokenTimeoutMs; once collector.gotFirstToken flips (see StreamCollector),
     *  the tighter, fixed IDLE_TIMEOUT_MS takes over for genuine mid-stream stalls. */
    private static ChatResponse awaitStream(final StreamCollector collector, final long firstTokenTimeoutMs) throws InterruptedException {
        while (true) {
            try {
                return collector.future.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException pollTimeout) {
                final long limit = collector.gotFirstToken ? IDLE_TIMEOUT_MS : firstTokenTimeoutMs;
                if (System.currentTimeMillis() - collector.lastActivityMs > limit) {
                    collector.cancelIfPossible();   // real abort now (R10), not just walking away
                    throw new RuntimeException((collector.gotFirstToken ? "no data from the model in " : "no first token from the model in ")
                            + (limit / 1000) + "s (stream stalled)");
                }
                // still within budget, or receiving text/thinking/tool-call deltas - keep polling
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
     *  only as the exception message. */
    static final String BUDGET_REFUSAL = "ace-service: phase output-token budget exhausted";

    static boolean isBudgetRefusal(final Throwable e) {
        final Integer status = httpStatus(e);
        if (status != null && status != 429) return false;
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause())
            if (t.getMessage() != null && t.getMessage().contains(BUDGET_REFUSAL)) return true;
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

    /** the oMLX key for the proxy; an EXTERNAL reviewer's provider key from its env var (run_bench Agent.run) */
    String apiKeyFor(String proxyBase, String model, Map<String, String> extraEnv) {
        if (extraEnv != null && (proxyBase == null || !proxyBase.contains("127.0.0.1"))) {
            String provider = proxyBase == null ? "" : switch (proxyBase) {
                case "https://api.openai.com/v1" -> "OPENAI_API_KEY";
                case "https://openrouter.ai/api/v1" -> "OPENROUTER_API_KEY";
                case "https://api.anthropic.com/v1" -> "ANTHROPIC_API_KEY";
                case String u when u.contains("generativelanguage") -> "GEMINI_API_KEY";
                case String u when u.contains("nebius") -> "NEBIUS_API_KEY";
                default -> "";
            };
            if (!provider.isEmpty() && extraEnv.containsKey(provider)) return extraEnv.get(provider);
        }
        return props.apiKey() == null || props.apiKey().isBlank() ? "none" : props.apiKey();
    }

    static final class BudgetExhausted extends RuntimeException { BudgetExhausted(Throwable c) { super(c); } }
    static final class TransientError extends RuntimeException { TransientError(Throwable c) { super(c); } }
}
