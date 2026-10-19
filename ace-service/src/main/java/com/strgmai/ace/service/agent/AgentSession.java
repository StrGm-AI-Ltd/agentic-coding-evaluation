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
                // #305, found live: a restarted task leaves its OLD file (same sessionId, different
                // timestamp prefix) sitting alongside the new one Files.list() gives no ordering
                // guarantee at all, so .findFirst() could return either - comparing filenames picks
                // the most recent one instead, since the filename's own leading ISO-8601 timestamp
                // (set below, identical width/format on every file) sorts lexically exactly like it
                // sorts chronologically. Deliberately NOT Files.getLastModifiedTime(): a bulk
                // operation touching several files' mtimes to the same instant has already been
                // observed live, drifting mtime away from actual write order.
                found = s.filter(p -> p.getFileName().toString().contains(sessionId) && p.getFileName().toString().endsWith(".jsonl"))
                        .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
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

    public void header(final String agentVersion, final String model, final String cwd, final String reasoningEffort,
                        final String label) throws IOException {
        write(Map.of("type", "session", "id", sessionId, "agent", agentVersion, "model", model,
                "cwd", cwd, "reasoning_effort", reasoningEffort == null ? "" : reasoningEffort,
                "label", label == null ? "" : label, "ts", Instant.now().toString()));
    }

    public void system(String text) throws IOException { message("system", text); }
    public void user(String text) throws IOException { message("user", text); }

    public void assistant(String thinking, String text, List<ToolExecutionRequest> calls, final String finish, final Map<String, Object> usage) throws IOException {
        final List<Map<String, Object>> parts = new ArrayList<>();
        // recorded for analysis (e.g. a reviewer session that burns its whole budget without ever
        // producing final text still has its actual work on record here) - loadMessages() below
        // intentionally does not fold this back into resumed conversation history, only "text"/"toolCall"
        if (thinking != null && !thinking.isEmpty()) parts.add(Map.of("type", "thinking", "text", thinking));
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

    /** #417 follow-up review (C7): `stubbed` and `summarized` are two genuinely different
     *  mechanisms' own contributions (tool_result_mask's stub count vs. whether structured_eviction
     *  wrote a summary this pass) - keeping them as separate fields instead of folding a summary
     *  into the stub count (the old 2-arg form effectively did, via CompactionStrategies.apply()'s
     *  own summed return value) means TaskTranscript can say "a structured summary written"
     *  instead of misreporting it as "N turns stubbed" when no masking happened at all. */
    public void compaction(final int stubbed, final boolean summarized, final int promptTokensBefore) throws IOException {
        write(Map.of("type", "compaction", "stubbed", stubbed, "summarized", summarized,
                "prompt_tokens_before", promptTokensBefore, "ts", Instant.now().toString()));
    }

    /** #417 follow-up review (C8): a side LLM call outside the main turn loop (structured_eviction's
     *  own one-shot summarization request) still spends real tokens the task's budget is charged
     *  for via RecordingProxy - but nothing recorded it in the SESSION file's own accounting, so
     *  usage() (the fallback token source for a session with no proxy - see its own javadoc) was
     *  silently undercounting it, while the proxy's own journal counted it correctly: two sources
     *  of truth disagreeing about the same task's real cost. A distinct record type, not an
     *  "assistant" message: loadMessages() only ever rebuilds conversation history from
     *  type=="message", so this is invisible to a --continue resume and cannot corrupt a resumed
     *  session's own trajectory - it only adds to usage()'s own tally. */
    public void sidecarUsage(final String label, final long input, final long output) throws IOException {
        write(Map.of("type", "sidecar_usage", "label", label, "input", input, "output", output, "ts", Instant.now().toString()));
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

    /** #417 follow-up review (C8): sidecar_usage records (structured_eviction's own summarization
     *  calls) are not conversation turns, so they're summed separately from usage()'s own
     *  "turns"-counted tally above rather than folded into it - callers that want the FULL token
     *  cost of a session (sidecar calls included) add this to usage()'s own input/output. */
    public static Map<String, Long> sidecarUsage(final Path sessionFile) {
        final Map<String, Long> t = new LinkedHashMap<>(Map.of("input", 0L, "output", 0L));
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) return t;
        try {
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                JsonNode r;
                try { r = JSON.readTree(line); } catch (Exception e) { log.debug("could not parse session line for sidecar usage accounting, skipping it: {}", e.toString()); continue; }
                if (!"sidecar_usage".equals(r.path("type").asText())) continue;
                t.merge("input", r.path("input").asLong(0), Long::sum);
                t.merge("output", r.path("output").asLong(0), Long::sum);
            }
        } catch (IOException e) { log.warn("could not read session {} for sidecar usage accounting: {}", sessionFile, e.toString()); }
        return t;
    }

    /** #424: the tool-output archive IS the session file itself - toolResult() above already writes
     *  every tool result's own text, unconditionally, before anything ever gets masked (compaction
     *  only ever mutates the in-memory msgs list, never this file). #417 follow-up review (C11):
     *  that text is the SAME one AgentTools already capped before the result ever entered context
     *  in the first place (AgentTools.READ_MAX_CHARS/BASH_MAX_CHARS etc.) - "un-stubbed" is
     *  accurate (nothing here is EVER the masking placeholder), but recalling it is not a way to
     *  see MORE than the agent originally saw, only the same thing again. Returns the LAST
     *  recorded text for toolCallId (a tool call id is unique within one session, so "last" and
     *  "only" coincide in practice), or null if nothing matches - same "re-read the session file
     *  for something not otherwise available" pattern as usage()/lastAssistantText() above. */
    public static String recallToolOutput(final Path sessionFile, final String toolCallId) {
        if (sessionFile == null || !Files.isRegularFile(sessionFile) || toolCallId == null || toolCallId.isBlank()) return null;
        String found = null;
        try {
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode r;
                try { r = JSON.readTree(line); } catch (Exception e) { log.debug("could not parse session line while recalling a tool output, skipping it: {}", e.toString()); continue; }
                final JsonNode m = r.path("message");
                if (!"message".equals(r.path("type").asText()) || !"toolResult".equals(m.path("role").asText())) continue;
                if (!toolCallId.equals(m.path("toolCallId").asText())) continue;
                final JsonNode c = m.path("content").path(0);
                if (!c.isMissingNode()) found = c.path("text").asText();
            }
        } catch (IOException e) { log.warn("could not read session {} to recall tool output {}: {}", sessionFile, toolCallId, e.toString()); }
        return found;
    }

    /** #265: the LAST assistant turn's own text, straight from the session file - for a session
     *  with no other return channel for its own output (e.g. the judge, whose SessionResult alone
     *  carries no text at all). Same "re-read the session file for something SessionResult doesn't
     *  carry" pattern as usage() above. Blank (never null) if the session never produced any text
     *  (e.g. it only ever called tools, or the file is missing/unreadable). */
    public static String lastAssistantText(final Path sessionFile) {
        if (sessionFile == null || !Files.isRegularFile(sessionFile)) return "";
        String last = "";
        try {
            for (String line : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                JsonNode r;
                try { r = JSON.readTree(line); } catch (Exception e) { log.debug("could not parse session line looking for the last assistant text, skipping it: {}", e.toString()); continue; }
                if (!"message".equals(r.path("type").asText()) || !"assistant".equals(r.path("message").path("role").asText())) continue;
                for (JsonNode part : r.path("message").path("content")) {
                    if ("text".equals(part.path("type").asText()) && part.hasNonNull("text")) last = part.get("text").asText();
                }
            }
        } catch (IOException e) { log.warn("could not read session {} for its last assistant text: {}", sessionFile, e.toString()); }
        return last;
    }

    /** the turn-count boundary alone: index of the first message that belongs to the newest
     *  `keepRecentTurns` assistant turns, never touching the system prompt or the first user
     *  message (the harness's pack). A negative keepRecentTurns is an operator misconfiguration,
     *  not a reachable intent - clamped to 0 ("keep nothing verbatim") rather than indexing past
     *  the end of idxAssist and crashing the whole session (a harness-side crash from a bad config
     *  value must never read as the agent's own failure). 0 itself cuts at msgs.size() - idxAssist's
     *  own last entry is still "the newest 0 turns", i.e. none of them, so everything from index 2
     *  onward is eligible. Always lands exactly on an AiMessage index, so msgs.subList(0, cut) is
     *  always a structurally complete slice (never an AiMessage with some, but not all, of its own
     *  tool results left outside the slice) - the only boundary safe to hand to anything that sends
     *  a sub-range as its OWN standalone request (CompactionStrategies' structured_eviction). */
    public static int cutIndex(final List<ChatMessage> msgs, final int keepRecentTurns) {
        if (keepRecentTurns < 0) log.warn("keepRecentTurns={} is negative - clamping to 0 (\"keep nothing verbatim\")", keepRecentTurns);
        final int keep = Math.max(0, keepRecentTurns);
        final List<Integer> idxAssist = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) if (msgs.get(i) instanceof AiMessage) idxAssist.add(i);
        return idxAssist.size() <= keep ? 0 : (keep == 0 ? msgs.size() : idxAssist.get(idxAssist.size() - keep));
    }

    /** original form (#417's own `tool_result_mask` strategy, pre-token-budget): the turn-count
     *  boundary alone, no ceiling on its own size. Kept as the 2-arg form so every existing caller
     *  (the rescue step's compact-on-load, this class's own tests) keeps compiling unchanged. */
    public static int compact(final List<ChatMessage> msgs, final int keepRecentTurns) {
        return compact(msgs, keepRecentTurns, 0);
    }

    /** #417: the turn-count floor alone never shrinks, no matter how large those protected turns'
     *  own tool outputs grow - that is this issue's own root cause (found live: five compactions in
     *  one task freeing ~21k/11k/8k/8k/4k tokens, converging to near-zero as the protected window's
     *  own footprint ate the entire budget). Re-checks the turn-count-protected region's OWN
     *  estimated size (RecordingProxy's chars/token estimate) and shrinks the cut forward -
     *  protecting fewer, more recent turns - until it actually fits keepTokenBudget.
     *  keepTokenBudget<=0 disables the shrink (this codebase's usual "0 = no budget" convention),
     *  giving the exact original turn-count-only boundary.
     *
     *  #417 follow-up review (C4/C10): factored out of compact() so structured_eviction's own
     *  eligible range (CompactionStrategies.structuredEvict) matches EXACTLY what tool_result_mask
     *  actually protects, instead of structured_eviction using cutIndex()'s turn-count-only
     *  boundary while compact() shrank its own boundary further - a divergence that could leave a
     *  blind spot in exactly the region the token pressure that triggered compaction came from,
     *  one neither mechanism covered. Also enforces a FLOOR (found live): if the single newest
     *  turn alone exceeds keepTokenBudget, the shrink loop below would otherwise push cut all the
     *  way to msgs.size() - masking (or excluding from the summary) the very turn and tool results
     *  the model is about to act on next. Never shrinks past the newest AiMessage's own index. */
    static int effectiveCut(final List<ChatMessage> msgs, final int keepRecentTurns, final int keepTokenBudget) {
        int cut = cutIndex(msgs, keepRecentTurns);
        if (keepTokenBudget <= 0 || cut >= msgs.size()) return cut;
        int lastAssistantIdx = -1;
        for (int i = msgs.size() - 1; i >= 0; i--) if (msgs.get(i) instanceof AiMessage) { lastAssistantIdx = i; break; }
        long tokens = 0;
        for (int i = msgs.size() - 1; i >= Math.max(cut, 2); i--) {
            tokens += estimateTokens(msgs.get(i));
            if (tokens > keepTokenBudget) { cut = i + 1; break; }
        }
        return lastAssistantIdx < 0 ? cut : Math.min(cut, lastAssistantIdx);
    }

    /** #417 follow-up review (found live on a real task, 2026-10-19/T21): masking a tool RESULT's
     *  own text, alone, still left the AiMessage that ISSUED that call untouched forever - its own
     *  tool-call arguments (a full `bash` command string, a full `edit` old_string/new_string, a
     *  full `write` file's content) are never masked, no matter how long ago its own result was
     *  already stubbed. Measured live: this grows LINEARLY with turn count with nothing ever
     *  shrinking it - 8.5k -> 13.3k -> 18.1k tokens across three real compactions on one task,
     *  becoming the dominant and still-growing share of the SAME trigger budget, which is exactly
     *  why compaction kept freeing progressively less even after the protected-window-size bug
     *  (#417's own original finding) was fixed. A short, fixed, valid-JSON placeholder - same
     *  one-shot-per-message spirit as the result stub above, just on the call side. */
    private static final String DROPPED_ARGS = "{\"_dropped\":true}";

    public static int compact(final List<ChatMessage> msgs, final int keepRecentTurns, final int keepTokenBudget) {
        final int cut = effectiveCut(msgs, keepRecentTurns, keepTokenBudget);
        int n = 0;
        for (int i = 2; i < cut; i++) {
            if (msgs.get(i) instanceof ToolExecutionResultMessage m) {
                if (!m.text().startsWith("[output dropped")) {
                    msgs.set(i, ToolExecutionResultMessage.from(
                            ToolExecutionRequest.builder().id(m.id()).name(m.toolName()).build(),
                            // #424: the exact "[output dropped" PREFIX is load-bearing - it's this
                            // method's own one-shot guard (the condition right above), so changing it
                            // would make a later pass re-stub (and re-count) the same message
                            "[output dropped by the harness to save context; call recall_tool_output(tool_call_id=\"" + m.id() + "\") to see it again instead of re-running the command]"));
                    n++;
                }
                // whether masked just now or on an earlier pass (this range is re-scanned every
                // call, so a result masked before this fix shipped is caught up retroactively the
                // next time compaction fires) - its own ORIGINATING call's arguments are shrunk too.
                shrinkOwningCallArguments(msgs, i, m.id());
            }
        }
        return n;
    }

    /** Scans backward from a masked tool result's own index to find the AiMessage that issued it -
     *  no OTHER AiMessage can sit between them by construction, only sibling tool results from the
     *  SAME batch (a turn can issue more than one call at once) - then replaces just THAT one
     *  call's own arguments with DROPPED_ARGS, leaving any other, still-live sibling call's
     *  arguments (and the AiMessage's own text) untouched. Thinking content is NOT preserved across
     *  the rebuild (AiMessage.from(text, calls) below has no thinking parameter) - confirmed live
     *  against real request payloads that this is harmless: thinking is never resent to the model
     *  on any later request regardless (the wire payload for a historical assistant turn is just
     *  {role, content, tool_calls} - no reasoning field at all), the exact same drop
     *  loadMessages() already performs unconditionally on every --continue resume. A missing owner
     *  (a malformed or hand-edited history) is a silent no-op, not a crash - this is best-effort
     *  token hygiene, never load-bearing for correctness. Note for anyone combining this with
     *  structured_eviction: ordering it BEFORE tool_result_mask (see CompactionStrategies.apply()'s
     *  own javadoc) now matters even more than before - after tool_result_mask runs, an old turn's
     *  call arguments AND its result are both gone, leaving less for a summary to work from. */
    private static void shrinkOwningCallArguments(final List<ChatMessage> msgs, final int resultIdx, final String toolCallId) {
        int aiIdx = -1;
        for (int j = resultIdx - 1; j >= 0; j--) {
            if (msgs.get(j) instanceof AiMessage) { aiIdx = j; break; }
        }
        if (aiIdx < 0 || !(msgs.get(aiIdx) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) return;
        boolean changed = false;
        final List<ToolExecutionRequest> shrunk = new ArrayList<>(ai.toolExecutionRequests().size());
        for (final ToolExecutionRequest c : ai.toolExecutionRequests()) {
            if (c.id().equals(toolCallId) && !DROPPED_ARGS.equals(c.arguments())) {
                shrunk.add(ToolExecutionRequest.builder().id(c.id()).name(c.name()).arguments(DROPPED_ARGS).build());
                changed = true;
            } else {
                shrunk.add(c);
            }
        }
        if (changed) msgs.set(aiIdx, AiMessage.from(ai.text(), shrunk));
    }

    private static long estimateTokens(final ChatMessage m) {
        final StringBuilder sb = new StringBuilder();
        if (m instanceof AiMessage ai) {
            if (ai.text() != null) sb.append(ai.text());
            if (ai.hasToolExecutionRequests()) for (ToolExecutionRequest c : ai.toolExecutionRequests()) sb.append(c.arguments());
        } else if (m instanceof ToolExecutionResultMessage t) sb.append(t.text());
        else if (m instanceof UserMessage u) sb.append(u.singleText());
        else if (m instanceof SystemMessage s) sb.append(s.text());
        return (long) (sb.length() / com.strgmai.ace.service.proxy.RecordingProxy.CHARS_PER_TOKEN);
    }
}
