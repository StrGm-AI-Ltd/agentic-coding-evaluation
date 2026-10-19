package com.strgmai.ace.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** An operator-facing, plain-text rendering of a task's own live session, for a dedicated "Live"
 *  tab on the task dialog - distinct from the Judge's own trajectory rendering
 *  (Judge.renderTrajectory()), which works on already-parsed ChatMessage objects and deliberately
 *  never sees "thinking" content (AgentSession.loadMessages() drops it, on purpose, before it
 *  re-enters conversation history on a --continue). This reads the session's own raw JSONL
 *  directly instead, specifically so the operator's live view includes the model's reasoning, not
 *  just its final text and tool calls.
 *
 *  Stateless across connections, same convention Tailer already established: one instance lives
 *  for the lifetime of one SSE connection (BenchController.taskTranscript()), tracking which
 *  session file it has settled on for this task and how far it has already read from it. */
final class TaskTranscript {
    private static final Logger log = LoggerFactory.getLogger(TaskTranscript.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Tailer tailer = new Tailer();
    private final Map<Path, String> labelCache = new HashMap<>();
    private Path currentFile;

    /** Null when nothing is new (no session yet for this task, or no new lines since the last
     *  call). On the FIRST sighting of a given underlying file - including a restarted task's
     *  own NEW file replacing an earlier, abandoned one, same "pick latest by filename" rule
     *  #305/Tailer's own ordering fix already established - everything accumulated in it so far
     *  is rendered and capped to its own tail (tailCapChars); every call after that returns only
     *  the small incremental amount newly written, uncapped. */
    String poll(final Path sessionsDir, final String taskId, final int tailCapChars) {
        final Path latest = findLatest(sessionsDir, taskId);
        if (latest == null) return null;
        final boolean firstSightingOfThisFile = !latest.equals(currentFile);
        currentFile = latest;
        final StringBuilder rendered = new StringBuilder();
        for (final String line : tailer.tailLines(latest)) {
            final String block = renderLine(line);
            if (block != null) { if (!rendered.isEmpty()) rendered.append("\n\n"); rendered.append(block); }
        }
        if (rendered.isEmpty()) return null;
        return firstSightingOfThisFile ? capTail(rendered.toString(), tailCapChars) : rendered.toString();
    }

    /** The task's own main implementation session - exact label match only (never "T13-handoff",
     *  "T13-judge-turn10", "T13-rescue", ...; the operator watching THIS task wants its own coding
     *  session, not a sub-session working alongside it). Reads each candidate file's header once
     *  and remembers it, rather than re-reading every file's first line on every poll tick. */
    private Path findLatest(final Path sessionsDir, final String taskId) {
        if (!Files.isDirectory(sessionsDir)) return null;
        Path best = null;
        try (var stream = Files.list(sessionsDir)) {
            for (final Path p : stream.toList()) {
                if (!Files.isRegularFile(p)) continue;
                final String label = labelCache.computeIfAbsent(p, TaskTranscript::readLabel);
                if (!taskId.equals(label)) continue;
                if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0) best = p;
            }
        } catch (final IOException e) {
            log.debug("could not list session dir {}: {}", sessionsDir, e.toString());
        }
        return best;
    }

    /** null (NOT "") specifically for "could not read this yet" - a file visible in the directory
     *  listing whose header line hasn't fully landed, the exact race Tailer.newSessions() already
     *  retries on every poll instead of caching the failure. Map.computeIfAbsent()'s own contract
     *  ("if the function returns null, no mapping is recorded") makes this the whole fix: a real,
     *  parsed label (even "" for a confirmed non-session file) is cacheable forever since a
     *  session file's header is never rewritten once written; a transient read failure must not
     *  be, or a restarted task's own new file - caught mid-write once - would never be recognized
     *  as this task's again for the rest of the SSE connection. */
    private static String readLabel(final Path p) {
        try {
            final String first = Files.readString(p, StandardCharsets.UTF_8).split("\n", 2)[0];
            final JsonNode r = JSON.readTree(first);
            return "session".equals(r.path("type").asText()) ? r.path("label").asText("") : "";
        } catch (final Exception e) {
            return null;
        }
    }

    /** One raw JSONL line -&gt; a human-readable block, or null for a type/role the operator gains
     *  nothing from seeing (the session header - already shown via the Prompt tab's own task info;
     *  the system prompt - boilerplate repeated on every message, same as Judge.renderTrajectory()
     *  already skips it). Thinking is included (ASSISTANT (thinking):) - the one thing this
     *  renderer carries that Judge's own trajectory rendering structurally cannot (see this class's
     *  own javadoc). Tool call arguments and tool results are capped (cap()) - a `write` of a large
     *  file, or a long `read`/`grep` result, would otherwise flood the whole view with one entry. */
    static String renderLine(final String rawJsonLine) {
        if (rawJsonLine == null || rawJsonLine.isBlank()) return null;
        final JsonNode r;
        try { r = JSON.readTree(rawJsonLine); } catch (final Exception e) { return null; }
        return switch (r.path("type").asText()) {
            case "message" -> {
                final JsonNode m = r.path("message");
                yield switch (m.path("role").asText()) {
                    case "user" -> "USER:\n" + firstText(m);
                    case "assistant" -> renderAssistant(m.path("content"));
                    case "toolResult" -> "TOOL RESULT (" + m.path("toolName").asText("?")
                            + (m.path("isError").asBoolean(false) ? ", ERROR" : "") + "):\n" + cap(firstText(m), 2000);
                    default -> null; // "system" - boilerplate, repeated on every message
                };
            }
            // #417 follow-up review (C7): "stubbed" and "summarized" are two genuinely different
            // mechanisms' own contributions (AgentSession.compaction()'s own javadoc) - rendering
            // them separately instead of always saying "N turns stubbed" avoids misreporting a
            // structured_eviction-only pass (which stubs nothing at all) as if masking had happened.
            case "compaction" -> {
                final int stubbed = r.path("stubbed").asInt();
                final boolean summarized = r.path("summarized").asBoolean(false);
                final var parts = new java.util.ArrayList<String>();
                if (stubbed > 0) parts.add(stubbed + " tool result(s) stubbed");
                if (summarized) parts.add("a structured summary written");
                yield "[history compacted: " + (parts.isEmpty() ? "no changes" : String.join(", ", parts)) + "]";
            }
            case "end" -> "[session ended: finish=" + r.path("finish").asText("?") + ", " + r.path("turns").asInt() + " turns]";
            default -> null; // "session" header - nothing the operator needs mid-stream
        };
    }

    private static String renderAssistant(final JsonNode content) {
        final java.util.List<String> parts = new java.util.ArrayList<>();
        for (final JsonNode part : content) {
            final String block = switch (part.path("type").asText()) {
                case "thinking" -> "ASSISTANT (thinking):\n" + part.path("text").asText();
                case "text" -> "ASSISTANT:\n" + part.path("text").asText();
                case "toolCall" -> "ASSISTANT CALLED " + part.path("name").asText("?")
                        + "(" + cap(part.path("arguments").toString(), 500) + ")";
                default -> null;
            };
            if (block != null) parts.add(block);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static String firstText(final JsonNode message) {
        return message.path("content").path(0).path("text").asText("");
    }

    // package-private (not private): tested directly, same reasoning Judge.cap()/capTail() and
    // Packs.cap()/capTail() already follow.
    static String cap(final String text, final int n) {
        return text == null ? "" : text.length() <= n ? text : text.substring(0, n) + " … [truncated at " + n + " chars]";
    }

    static String capTail(final String text, final int n) {
        return text.length() <= n ? text : "… [" + (text.length() - n) + " older chars omitted]\n" + text.substring(text.length() - n);
    }
}
