package com.strgmai.ace.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Port of service/progress.py (WITH the byte-exact offset fix from the Python review): live
 *  progress inferred from the files a run already writes. Stateless across connections: each caller
 *  owns one Tailer and keeps polling it. Offsets stay in BYTES — advancing by the re-encoded length
 *  of replace-decoded text would drift past the file's real end on invalid UTF-8. */
public final class Tailer {
    private static final Logger log = LoggerFactory.getLogger(Tailer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<String> seenFiles = new HashSet<>();
    private final Map<String, Long> offsets = new HashMap<>();
    private final Set<String> sessionIds = new HashSet<>();
    private final Map<String, String> sessionIdByFile = new HashMap<>();
    private final Set<String> doneSessionFiles = new HashSet<>();
    private boolean executionOrderReported;

    public List<Map<String, Object>> poll(final Path runDir) {
        final List<Map<String, Object>> events = new ArrayList<>();
        newFiles(runDir.resolve("packs"), events);
        newFiles(runDir.resolve("instructions"), events);
        newSessions(runDir.resolve("sessions"), events);
        newExecutionOrder(runDir.resolve("execution_order.json"), events);
        tailRequests(runDir.resolve("interactions.jsonl"), events);
        return events;
    }

    /** RunBench writes this once, before any task session starts: the real planned task order
     *  (waves), so a live viewer can see e.g. "T2, T4" are a wave (either can run first/only one at
     *  a time under parallel=1) BEFORE a numerically-later task's session starting looks like the
     *  runner going backwards. One-shot: the file never changes after it's written. */
    private void newExecutionOrder(final Path path, final List<Map<String, Object>> events) {
        if (executionOrderReported || !Files.isRegularFile(path)) return;
        try {
            final JsonNode waves = JSON.readTree(path.toFile());
            if (!waves.isArray()) return;
            executionOrderReported = true;
            events.add(Map.of("type", "execution_order", "waves", waves));
        } catch (Exception e) {
            log.debug("execution_order.json for {} not fully written yet, retrying next poll: {}", path, e.toString());
        }
    }

    private void newFiles(final Path dir, final List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {   // the stream holds a directory FD - it must be closed
            paths = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { log.debug("could not read mtime of {}, sorting first: {}", p, e.toString()); return 0L; }
                    })).toList();
        }
        catch (IOException e) { log.debug("could not list {}: {}", dir, e.toString()); return; }
        for (Path p : paths) {
            final String key = dir.getFileName() + "/" + p.getFileName();
            if (!Files.isRegularFile(p) || seenFiles.contains(key)) continue;
            seenFiles.add(key);
            String stem = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            if ("packs".equals(dir.getFileName().toString()) && "stable".equals(stem)) continue;   // the persistent pack, not a step
            final boolean continuation = stem.endsWith("-continue");
            if (continuation) stem = stem.substring(0, stem.length() - "-continue".length());
            events.add(Map.of("type", "step_started", "step", stem, "continuation", continuation, "source", dir.getFileName().toString()));
        }
    }

    private void newSessions(final Path dir, final List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (!Files.isRegularFile(p)) continue;
                final String key = p.getFileName().toString();
                if (!sessionIds.contains(key)) {
                    try {
                        final String first = Files.readString(p, StandardCharsets.UTF_8).split("\n", 2)[0];
                        final JsonNode r = JSON.readTree(first);
                        if (!"session".equals(r.path("type").asText())) continue;
                        sessionIds.add(key);
                        sessionIdByFile.put(key, r.path("id").asText());
                        events.add(Map.of("type", "session_started", "session_id", r.path("id").asText(),
                                "agent", r.path("agent").asText(), "model", r.path("model").asText(),
                                "label", r.path("label").asText(""), "ts", r.path("ts").asText(""),
                                "path", "sessions/" + p.getFileName()));
                    } catch (Exception e) {
                        log.debug("session header for {} not fully written yet, retrying next poll: {}", p, e.toString());
                        continue;
                    }
                }
                if (!doneSessionFiles.contains(key)) sessionEnd(p, key, events);
            }
        } catch (IOException e) { log.debug("could not list session dir {}: {}", dir, e.toString()); }
    }

    /** A session's own transcript carries its real completion signal — a trailing {"type":"end",
     *  "finish":..., "turns":..., ...} line — unlike the old ported-from-Python progress.py, which
     *  looked for a "done after N turns (finish=X, ...)" line in a *.log file nothing in this Java
     *  runner ever writes; that mechanism never fired, so a session that finished stayed "running" in
     *  the live view for the rest of the job. Matched by the session's real id (from its header), not
     *  guessed as "the oldest still-open session" — correct under concurrent (parallel-wave) sessions
     *  too, not just ones that happen to finish in start order. */
    private void sessionEnd(final Path p, final String key, final List<Map<String, Object>> events) {
        for (String line : tailLines(p)) {
            if (line.isBlank()) continue;
            try {
                final JsonNode r = JSON.readTree(line);
                if (!"end".equals(r.path("type").asText())) continue;
                doneSessionFiles.add(key);
                events.add(Map.of("type", "session_done", "session_id", sessionIdByFile.getOrDefault(key, ""),
                        "finish", r.path("finish").isTextual() ? r.path("finish").asText() : "?",
                        "ts", r.path("ts").isTextual() ? r.path("ts").asText() : ""));
                return;
            } catch (Exception e) { log.debug("could not parse a session line for end-detection, skipping it: {}", e.toString()); }
        }
    }

    private void tailRequests(final Path path, final List<Map<String, Object>> events) {
        for (String line : tailLines(path)) {
            if (line.isBlank()) continue;
            try {
                final JsonNode r = JSON.readTree(line);
                if (!r.path("path").asText("").startsWith("/v1/chat/completions")) continue;
                Map<String, Object> e = new LinkedHashMap<>();   // Map.of is null-hostile; `tag` is absent on most records
                e.put("type", "request");
                e.put("seq", r.path("seq").asInt());
                e.put("ts", r.path("ts").asText(""));
                e.put("status", r.path("status").asInt());
                e.put("budget_spent_completion_tokens", r.path("budget_spent_completion_tokens").asLong());
                e.put("client_aborted", r.path("client_aborted").asBoolean(false));
                e.put("abort_reason", r.path("abort_reason").isTextual() ? r.path("abort_reason").asText() : null);
                e.put("tag", r.path("task").isTextual() ? r.path("task").asText() : null);
                // RecordingProxy always journals latency_sec; first_byte_ms only for streamed
                // requests - both were parsed here but never forwarded, so the live requests grid's
                // latency/ttft columns always read "-" even mid-run. ttft_sec: first_byte_ms is
                // milliseconds, the client's field name implies seconds - convert at the source
                // rather than touch the (already correct) client.
                e.put("latency_sec", r.path("latency_sec").isNumber() ? r.path("latency_sec").asDouble() : null);
                e.put("ttft_sec", r.path("first_byte_ms").isNumber() ? r.path("first_byte_ms").asDouble() / 1000.0 : null);
                // session_id: set by RecordingProxy from the agent's X-Ace-Session-Id header, so a
                // request is attributed to its session unambiguously even under concurrent (parallel-
                // wave) sessions. prefill/decode speed: oMLX reports these itself per response
                // (usage.prompt_tokens_per_second / generation_tokens_per_second) - ground truth from
                // the inference engine, not re-derived from proxy-observed timings.
                e.put("session_id", r.path("session_id").isTextual() ? r.path("session_id").asText() : null);
                final JsonNode usage = r.path("response").path("usage");
                e.put("prefill_tok_per_sec", usage.path("prompt_tokens_per_second").isNumber() ? usage.path("prompt_tokens_per_second").asDouble() : null);
                e.put("decode_tok_per_sec", usage.path("generation_tokens_per_second").isNumber() ? usage.path("generation_tokens_per_second").asDouble() : null);
                // per-request completion tokens, summed per session for the sessions grid's "total
                // tokens" column - budget_spent_completion_tokens is a per-PROXY running counter that
                // RESETS on a continuation's fresh proxy (given the REMAINING budget, not zero), so it
                // undercounts a session that spans one; summing this instead is correct regardless.
                e.put("completion_tokens", usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asLong() : null);
                events.add(e);
            } catch (Exception ex) { log.debug("could not parse journal line for the live view, skipping it: {}", ex.toString()); }
        }
    }

    /** byte-exact tailing: consume through the last newline; a partial line waits for the next poll */
    List<String> tailLines(Path path) {
        try {
            final long size = Files.size(path);
            final long start = offsets.getOrDefault(path.toString(), 0L);
            if (size < start) offsets.put(path.toString(), 0L);   // truncated/rotated: restart from the top, else we'd never read again
            if (size <= start) return List.of();
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(start);
                long len = size - start;
                if (len > Integer.MAX_VALUE) len = Integer.MAX_VALUE;   // clamp; the remainder is picked up on the next poll
                final byte[] data = new byte[(int) len];
                final int read = raf.read(data);
                if (read <= 0) return List.of();
                int lastNl = -1;
                for (int i = read - 1; i >= 0; i--) if (data[i] == '\n') { lastNl = i; break; }   // BYTE index, not a char index
                if (lastNl < 0) return List.of();                       // no complete line yet; retry next poll
                final List<String> lines = new ArrayList<>();
                int lineStart = 0;
                for (int i = 0; i <= lastNl; i++)
                    if (data[i] == '\n') { lines.add(new String(data, lineStart, i - lineStart, StandardCharsets.UTF_8)); lineStart = i + 1; }
                offsets.put(path.toString(), start + lastNl + 1);      // byte-exact: valid multi-byte UTF-8 never drifts
                return lines;
            }
        } catch (IOException e) {
            // the live view silently shows no progress with nothing pointing at a read failure
            log.debug("could not tail {}: {}", path, e.toString());
            return List.of();
        }
    }
}
