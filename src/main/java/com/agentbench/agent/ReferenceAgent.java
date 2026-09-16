package com.agentbench.agent;

import com.agentbench.config.BenchProperties;
import com.agentbench.proxy.RecordingProxy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Port of runner/agent_loop.py, the reference agent, on LangChain4j. The harness owns every part
 *  of the loop: four tools (read/write/edit/bash with mechanical output hygiene), structural
 *  compaction (not an LLM summary), retries with backoff on transient errors, the proxy's 429
 *  (budget exhausted) ends the session with rc=3, a `length` finish is recorded and left to the
 *  harness's policy, and the stop rule is: the model answers with no tool call -> done.
 *  The agent talks to the model THROUGH the recording proxy, which pins the sampler params and
 *  enforces the token budget. */
@Component
public class ReferenceAgent {
    public static final String AGENT_VERSION = "jls-ref-1.0";

    public static final String SYSTEM = """
            You are an autonomous software engineer working non-interactively in the repository at {cwd} (today {date}).
            You have four tools: read, write, edit, bash. Work in small verified steps: locate before reading (grep -n, find -maxdepth), read line
            ranges instead of whole files, keep command output short (tail/grep), never re-run a command whose output you already have, run the
            tests after every change and react to what they say. Bias toward action: the build and tests are your feedback loop, so prefer writing a first version and running it over researching to eliminate uncertainty up front - gather only what you need for the next concrete step, and once you can write a file, write it. Use conventional, known-good versions of tools and dependencies from your own knowledge; do NOT spend turns fetching remote metadata (package registries, plugin portals) to pin exact versions - pick a reasonable recent version and let the build tell you if it is wrong. When the task is complete - or when your budget is nearly spent - stop by
            answering with a short final message and no tool call. Never claim something works that you did not see pass.""";

    /** the per-session-kind reasoning effort — a scored treatment in the Python original; kept for the record */
    public static final Map<String, String> DEFAULT_REASONING = Map.of(
            "definition", "high", "plan", "high", "implement", "medium", "integrate", "medium",
            "fix", "medium", "status", "low", "review", "medium", "handoff", "low");

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
        return run(name, instruction, wallSec, tokenBudget, sessionDir, sessionId, continueSession, appendSystem, cwd, proxyBase, null, extraEnv);
    }

    /** full form: `model` overrides the configured one (a reviewer, a parallel task, a probe) */
    public SessionResult run(String name, String instruction, long wallSec, Long tokenBudget,
                             Path sessionDir, String sessionId, boolean continueSession,
                             String appendSystem, String cwd, String proxyBase, String model, Map<String, String> extraEnv) throws Exception {
        long t0 = System.nanoTime();
        Instant start = Instant.now();
        AgentSession session = new AgentSession(sessionDir, sessionId, continueSession);
        List<ChatMessage> msgs;
        if (continueSession && session.exists()) {
            msgs = session.loadMessages();
            msgs.add(UserMessage.from(instruction));
            session.user(instruction);
        } else {
            String system = SYSTEM.replace("{cwd}", cwd).replace("{date}", start.toString().substring(0, 10))
                    + (appendSystem == null ? "" : "\n\n" + java.nio.file.Files.readString(Path.of(appendSystem)));
            msgs = new ArrayList<>(List.of(SystemMessage.from(system), UserMessage.from(instruction)));
            session.header(AGENT_VERSION, model == null ? props.model() : model, cwd, null);
            session.system(system);
            session.user(instruction);
        }
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(proxyBase == null ? props.endpoint() : proxyBase)
                .apiKey(apiKeyFor(proxyBase, model, extraEnv))
                .modelName(model == null ? props.model() : model)
                .timeout(Duration.ofSeconds(3600))
                .build();
        List<ToolSpecification> specs = toolSpecs();
        // the run's SCRUBBED environment (fresh HOME, docker shim, pinned JAVA_HOME, AB_RUN_ID) is the
        // base for every tool call; extraEnv null = a bare unit-test context
        Map<String, String> env = extraEnv != null ? new LinkedHashMap<>(extraEnv)
                : new LinkedHashMap<>(Map.of("HOME", System.getProperty("user.home"), "PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), "LANG", "en_US.UTF-8"));
        env.putIfAbsent("CI", "1");
        env.putIfAbsent("NO_COLOR", "1");

        int turns = 0, toolErrors = 0, compactions = 0, lastPrompt = 0;
        String finish = null; int rc = 0;
        final int MAX_TURNS = 400;
        long deadline = System.currentTimeMillis() + wallSec * 1000;
        while (turns < MAX_TURNS) {
            if (lastPrompt > 0 && lastPrompt > props.compactionTrigger()) {
                int n = AgentSession.compact(msgs, props.keepRecentTurns());
                if (n > 0) { compactions++; session.compaction(n, lastPrompt); }
            }
            ChatResponse resp;
            try {
                resp = chatWithRetry(chatModel, ChatRequest.builder().messages(msgs).toolSpecifications(specs).build(), deadline);
            } catch (BudgetExhausted e) {
                rc = 3; finish = "budget";
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
            } catch (TransientError e) {
                session.end(turns, toolErrors, compactions, finish);
                return result(name, 2, t0, finish, turns, toolErrors, compactions, session, start);
            }
            turns++;
            AiMessage ai = resp.aiMessage();
            finish = resp.metadata() == null || resp.metadata().finishReason() == null ? finish
                    : resp.metadata().finishReason().name().toLowerCase();
            Integer promptTokens = resp.metadata() == null || resp.metadata().tokenUsage() == null || resp.metadata().tokenUsage().inputTokenCount() == null
                    ? null : resp.metadata().tokenUsage().inputTokenCount();
            if (promptTokens != null) lastPrompt = promptTokens;
            Integer completion = resp.metadata() == null || resp.metadata().tokenUsage() == null || resp.metadata().tokenUsage().outputTokenCount() == null
                    ? null : resp.metadata().tokenUsage().outputTokenCount();
            List<ToolExecutionRequest> calls = ai.hasToolExecutionRequests() ? ai.toolExecutionRequests() : List.of();
            session.assistant(ai.text(), calls, finish, Map.of("input", promptTokens == null ? 0 : promptTokens,
                    "output", completion == null ? 0 : completion, "cached", 0));
            msgs.add(ai);
            if (calls.isEmpty()) {
                session.end(turns, toolErrors, compactions, finish);
                return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);   // done: answered without a tool call
            }
            for (ToolExecutionRequest c : calls) {
                Map<String, Object> args;
                try { args = new com.fasterxml.jackson.databind.ObjectMapper().readValue(c.arguments(), Map.class); }
                catch (Exception e) { args = Map.of(); }
                AgentTools.Outcome out = executeTool(c.name(), args, cwd, env);
                if (out.isError()) toolErrors++;
                msgs.add(ToolExecutionResultMessage.from(c, out.output()));
                session.toolResult(c.id(), c.name(), out.output(), out.isError());
            }
            if (System.currentTimeMillis() >= deadline) { rc = 124; break; }   // wall budget: the harness kills the tree too
        }
        session.end(turns, toolErrors, compactions, finish);
        return result(name, rc, t0, finish, turns, toolErrors, compactions, session, start);
    }

    private SessionResult result(String id, int rc, long t0, String finish, int turns, int toolErrors, int compactions, AgentSession s, Instant start) {
        double secs = (System.nanoTime() - t0) / 1e9;
        return new SessionResult(id, rc, Math.round(secs * 10) / 10.0, finish, turns, toolErrors, compactions, s.path(), start, Instant.now());
    }

    private AgentTools.Outcome executeTool(String name, Map<String, Object> args, String cwd, Map<String, String> env) {
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
     *  final; the proxy's 429 budget refusal (detected by its message) ends the session */
    static ChatResponse chatWithRetry(ChatModel model, ChatRequest req, long deadline) {
        RuntimeException last = null;
        long delay = 2000;
        for (int attempt = 0; attempt < 4; attempt++) {
            try { return model.chat(req); }
            catch (RuntimeException e) {
                last = e;
                String msg = String.valueOf(e);
                if (msg.contains("budget exhausted")) throw new BudgetExhausted(e);
                if (msg.matches("(?s).*40[0134].*") || msg.contains("413") || msg.contains("422")) throw new TransientError(e);   // final client errors
                try { Thread.sleep(Math.min(delay, Math.max(100, deadline - System.currentTimeMillis()))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delay = (long) (delay * 2.5);
            }
        }
        throw new TransientError(last);
    }

    /** the oMLX key for the proxy; an EXTERNAL reviewer's provider key from its env var (run_bench Agent.run) */
    String apiKeyFor(String proxyBase, String model, Map<String, String> extraEnv) {
        if (extraEnv != null && (proxyBase == null || !proxyBase.contains("127.0.0.1"))) {
            String provider = proxyBase == null ? "" : switch (proxyBase) {
                case "https://api.openai.com/v1" -> "OPENAI_API_KEY";
                case "https://openrouter.ai/api/v1" -> "OPENROUTER_API_KEY";
                case "https://api.anthropic.com/v1" -> "ANTHROPIC_API_KEY";
                case String u && u.contains("generativelanguage") -> "GEMINI_API_KEY";
                case String u && u.contains("nebius") -> "NEBIUS_API_KEY";
                default -> "";
            };
            if (!provider.isEmpty() && extraEnv.containsKey(provider)) return extraEnv.get(provider);
        }
        return props.apiKey() == null || props.apiKey().isBlank() ? "none" : props.apiKey();
    }

    static final class BudgetExhausted extends RuntimeException { BudgetExhausted(Throwable c) { super(c); } }
    static final class TransientError extends RuntimeException { TransientError(Throwable c) { super(c); } }
}
