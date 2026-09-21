package com.strgmai.ace.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Port of agent_loop.py's session store + structural compaction. The session file is JSONL in the
 *  SAME record shape the Python harness consumes: session header, user/assistant/toolResult
 *  messages (assistant messages carry toolCall parts and usage), compaction and end records.
 *  --continue resumes from the file: the API messages are rebuilt in order. */
public final class AgentSession {
    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    public final String sessionId;

    public AgentSession(final Path sessionDir, String sessionId, final boolean appendIfExists) throws IOException {
        this.sessionId = sessionId;
        Files.createDirectories(sessionDir);
        Path found = null;
        if (appendIfExists)
            try (var s = Files.list(sessionDir)) {
                found = s.filter(p -> p.getFileName().toString().contains(sessionId) && p.getFileName().toString().endsWith(".jsonl"))
                        .findFirst().orElse(null);
            } catch (IOException e) {
                // found stays null below, so --continue silently starts a NEW session instead of
                // resuming the existing one - a real correctness issue, not just a display glitch
                log.warn("could not list {} to find the existing session for --continue, starting a new session: {}", sessionDir, e.toString());
            }
        this.file = found != null ? found
                : sessionDir.resolve(Instant.now().toString().replace(':', '-').substring(0, 23) + "Z_" + sessionId + ".jsonl");
    }

    public Path path() { return file; }
    public boolean exists() {
        try { return Files.exists(file) && Files.size(file) > 0; }
        catch (IOException e) { log.debug("could not stat session file {}: {}", file, e.toString()); return false; }
    }

    public void write(Map<String, Object> rec) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(rec) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void header(final String agentVersion, final String model, final String cwd, final String reasoningEffort) throws IOException {
        write(Map.of("type", "session", "id", sessionId, "agent", agentVersion, "model", model,
                "cwd", cwd, "reasoning_effort", reasoningEffort == null ? "" : reasoningEffort, "ts", Instant.now().toString()));
    }

    public void system(String text) throws IOException { message("system", text); }
    public void user(String text) throws IOException { message("user", text); }

    public void assistant(String text, List<ToolExecutionRequest> calls, final String finish, final Map<String, Object> usage) throws IOException {
        final List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null && !text.isEmpty()) parts.add(Map.of("type", "text", "text", text));
        for (ToolExecutionRequest c : calls) {
            Map<String, Object> args;
            try { args = JSON.readValue(c.arguments(), Map.class); }
            catch (Exception e) {
                // silently recording {} here reads as "the model called the tool with no arguments" -
                // it isn't; the model's real (malformed) output is preserved instead of thrown away
                log.warn("could not parse tool call arguments for {} ({}): {}", c.name(), c.id(), e.toString());
                args = new LinkedHashMap<>();
                args.put("_unparsed", c.arguments());
            }
            parts.add(Map.of("type", "toolCall", "id", c.id(), "name", c.name(), "arguments", args));
        }
        final Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", parts);
        final Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "message");
        rec.put("message", msg);
        rec.put("finish", finish);
        rec.put("usage", usage == null ? Map.of() : usage);
        rec.put("ts", Instant.now().toString());
        write(rec);
    }

    public void toolResult(final String toolCallId, final String toolName, String text, final boolean isError) throws IOException {
        final Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "toolResult");
        msg.put("toolCallId", toolCallId);
        msg.put("toolName", toolName);
        msg.put("content", List.of(Map.of("type", "text", "text", text)));
        msg.put("isError", isError);
        write(Map.of("type", "message", "message", msg, "ts", Instant.now().toString()));
    }

    public void compaction(final int stubbed, final int promptTokensBefore) throws IOException {
        write(Map.of("type", "compaction", "stubbed", stubbed, "prompt_tokens_before", promptTokensBefore, "ts", Instant.now().toString()));
    }

    public void end(final int turns, final int toolErrors, final int compactions, final String finish) throws IOException {
        write(Map.of("type", "end", "turns", turns, "tool_errors", toolErrors, "compactions", compactions,
                "finish", finish == null ? "" : finish, "ts", Instant.now().toString()));
    }

    private void message(final String role, String text) throws IOException {
        final Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", List.of(Map.of("type", "text", "text", text)));
        write(Map.of("type", "message", "message", msg, "ts", Instant.now().toString()));
    }

    /** rebuild the API messages from the session file (for --continue) */
    public List<ChatMessage> loadMessages() throws IOException {
        final List<ChatMessage> msgs = new ArrayList<>();
        if (!Files.exists(file)) return msgs;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode r;
            // a resumed session silently missing this turn from its conversation history is a real
            // correctness issue - the model resumes not knowing what actually happened, not a cosmetic gap
            try { r = JSON.readTree(line); } catch (Exception e) { log.warn("could not parse session line for --continue, skipping it: {}", e.toString()); continue; }
            final JsonNode m = r.path("message");
            if (!"message".equals(r.path("type").asText())) continue;
            switch (m.path("role").asText()) {
                case "system" -> {
                    // path(0) yields MissingNode (not null) when content is absent/empty, so a truncated
                    // or hand-edited session file degrades to a skipped message instead of an NPE on resume
                    final JsonNode c = m.path("content").path(0);
                    if (!c.isMissingNode()) msgs.add(SystemMessage.from(c.path("text").asText()));
                }
                case "user" -> {
                    final JsonNode c = m.path("content").path(0);
                    if (!c.isMissingNode()) msgs.add(UserMessage.from(c.path("text").asText()));
                }
                case "assistant" -> {
                    final var text = new StringBuilder();
                    final List<ToolExecutionRequest> calls = new ArrayList<>();
                    for (JsonNode c : m.path("content")) {
                        if ("text".equals(c.path("type").asText())) text.append(c.path("text").asText());
                        if ("toolCall".equals(c.path("type").asText()))
                            calls.add(ToolExecutionRequest.builder().id(c.path("id").asText()).name(c.path("name").asText())
                                    .arguments(c.path("arguments").toString()).build());
                    }
                    msgs.add(calls.isEmpty() ? AiMessage.from(text.toString()) : AiMessage.from(text.toString(), calls));
                }
                case "toolResult" -> {
                    // path(0) -> MissingNode (never null) for an empty/missing content array, so a corrupt
                    // session file skips the record instead of throwing NPE on resume
                    final JsonNode c = m.path("content").path(0);
                    if (!c.isMissingNode()) msgs.add(ToolExecutionResultMessage.from(
                            ToolExecutionRequest.builder().id(m.path("toolCallId").asText()).name(m.path("toolName").asText()).build(),
                            c.path("text").asText()));
                }
            }
        }
        return msgs;
    }

    /** usage from the session file's assistant records — the only token source for a session that did not go through the proxy */
    public static Map<String, Long> usage(final Path sessionFile) {
        final Map<String, Long> t = new LinkedHashMap<>(Map.of("input", 0L, "output", 0L, "turns", 0L));
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) return t;
        try {
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                JsonNode r;
                try { r = JSON.readTree(line); } catch (Exception e) { log.debug("could not parse session line for usage accounting, skipping it: {}", e.toString()); continue; }
                if (!"message".equals(r.path("type").asText()) || !"assistant".equals(r.path("message").path("role").asText())) continue;
                final JsonNode u = r.path("usage");
                if (u.has("input")) t.merge("input", u.get("input").asLong(), Long::sum);
                if (u.has("output")) t.merge("output", u.get("output").asLong(), Long::sum);
                t.merge("turns", 1L, Long::sum);
            }
        } catch (IOException e) { log.warn("could not read session {} for usage accounting: {}", sessionFile, e.toString()); }
        return t;
    }

    /** port of compact(): stub the oldest tool outputs, keep the newest `keepRecentTurns` assistant
     *  turns intact, never touch the system prompt or the first user message (the harness's pack). */
    public static int compact(final List<ChatMessage> msgs, final int keepRecentTurns) {
        final List<Integer> idxAssist = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) if (msgs.get(i) instanceof AiMessage) idxAssist.add(i);
        if (idxAssist.size() <= keepRecentTurns) return 0;
        final int cut = idxAssist.get(idxAssist.size() - keepRecentTurns);
        int n = 0;
        for (int i = 2; i < cut; i++) {
            if (msgs.get(i) instanceof ToolExecutionResultMessage m && !m.text().startsWith("[output dropped")) {
                msgs.set(i, ToolExecutionResultMessage.from(
                        ToolExecutionRequest.builder().id(m.id()).name(m.toolName()).build(),
                        "[output dropped by the harness to save context; re-run the command if you need it]"));
                n++;
            }
        }
        return n;
    }
}
