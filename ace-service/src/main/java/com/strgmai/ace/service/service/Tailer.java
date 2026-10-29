package com.strgmai.ace.service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strgmai.ace.service.backend.LocalModelBackend;
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
    private boolean executionOrderReported;

    // mirrors JobLiveState.LABEL_SUFFIXES (ace-ui-vaadin) - a continuation/wrapup/handoff/fix
    // session shares its base task's own pack file, not one of its own
    private static final List<String> LABEL_SUFFIXES = List.of("-continue", "-wrapup", "-handoff", "-fix");

    public List<Map<String, Object>> poll(final Path runDir, final LocalModelBackend backend) {
        final List<Map<String, Object>> events = new ArrayList<>();
        newFiles(runDir.resolve("packs"), events);
        newFiles(runDir.resolve("instructions"), events);
        newSessions(runDir.resolve("sessions"), events);
        newExecutionOrder(runDir.resolve("execution_order.json"), events);
        tailRequests(runDir.resolve("interactions.jsonl"), events, backend);
        newRescueMarkers(runDir.resolve("rescue"), events);
        return events;
    }

    /** RunBench.rescueStep() writes a plain "<taskId>.start" file the moment a budget-rescue attempt
     *  begins for that task, and a "<taskId>.done" file once it's over (succeeded or not) - the only
     *  live signal that a rescue is in progress, since the rescue call itself reuses the task's own
     *  canonical session id with continueSession=true and so never writes a distinguishing session
     *  header (see rescueStep()'s own javadoc). Same seenFiles-by-filename dedup as newFiles(), but
     *  with its own event shape (rescue_started/rescue_done, keyed by task id) rather than
     *  step_started - a rescue is never a new "step", it's a continuation of the SAME task. */
    private void newRescueMarkers(final Path dir, final List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {   // the stream holds a directory FD - it must be closed
            paths = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) { log.debug("could not list {}: {}", dir, e.toString()); return; }
        for (Path p : paths) {
            final String key = "rescue/" + p.getFileName();
            if (seenFiles.contains(key)) continue;
            seenFiles.add(key);
            final String name = p.getFileName().toString();
            if (name.endsWith(".start")) {
                final String taskId = name.substring(0, name.length() - ".start".length());
                String reason;
                try { reason = Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { reason = ""; }
                events.add(Map.of("type", "rescue_started", "task", taskId, "reason", reason));
            } else if (name.endsWith(".done")) {
                final String taskId = name.substring(0, name.length() - ".done".length());
                events.add(Map.of("type", "rescue_done", "task", taskId));
            }
        }
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

    /** Found live: a restarted task's old session file sits alongside the new one, same canonical
     *  id (same bug class as #305's AgentSession lookup). Tailer is stateless per connection, so a
     *  reconnect replays every session_started it finds from scratch - and Files.newDirectoryStream
     *  gives NO ordering guarantee at all, meaning the replay used to arrive in essentially random
     *  order. Since the live view's "which session is this task's current one" logic just keeps the
     *  LAST sighting in stream order, an out-of-order replay could leave an OLDER, already-concluded
     *  session's status (e.g. "budget") stuck showing as the task's current state, with the actual
     *  newer, still-running one never surfacing. Sorted by filename (its own ISO-timestamp prefix) -
     *  same filename-comparison convention #305 already established, rather than mtime (what
     *  newFiles() above sorts by): a session file is never touched again after creation, so its name
     *  alone is the reliable chronological key. */
    private void newSessions(final Path dir, final List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {   // the stream holds a directory FD - it must be closed
            paths = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) { log.debug("could not list session dir {}: {}", dir, e.toString()); return; }
        for (Path p : paths) {
            final String key = p.getFileName().toString();
            if (!sessionIds.contains(key)) {
                try {
                    final String first = Files.readString(p, StandardCharsets.UTF_8).split("\n", 2)[0];
                    final JsonNode r = JSON.readTree(first);
                    if (!"session".equals(r.path("type").asText())) continue;
                    sessionIds.add(key);
                    sessionIdByFile.put(key, r.path("id").asText());
                    final var label = r.path("label").asText("");
                    // Map.of is null-hostile; task_title is absent whenever there's no matching pack
                    final var event = new LinkedHashMap<String, Object>();
                    event.put("type", "session_started");
                    event.put("session_id", r.path("id").asText());
                    event.put("agent", r.path("agent").asText());
                    event.put("model", r.path("model").asText());
                    event.put("label", label);
                    event.put("ts", r.path("ts").asText(""));
                    event.put("path", "sessions/" + p.getFileName());
                    final var title = taskTitle(dir, label);
                    if (title != null) event.put("task_title", title);
                    events.add(event);
                } catch (Exception e) {
                    log.debug("session header for {} not fully written yet, retrying next poll: {}", p, e.toString());
                    continue;
                }
            }
            sessionEnd(p, key, events);
        }
    }

    /** A plan task's human-readable title, straight from Packs.taskPack()'s own first line
     *  ("# Current task: &lt;id&gt; — &lt;title&gt;") - the only place it lives while a run is still in
     *  progress (the manifest, which also carries it, isn't written until the whole run finishes).
     *  "Task T1" on its own says nothing about what T1 actually is; this is what lets the live
     *  sessions grid show "Task T1 — Real Gradle wrapper + project baseline" instead. null when
     *  there's no matching pack file (PARALLEL_PLAN/INTEGRATION/REVIEW/the monolithic phases all
     *  lack one) or the first line doesn't parse - a session with no title is left unenriched,
     *  never given a wrong one. */
    private static String taskTitle(final Path sessionsDir, final String label) {
        if (label == null || label.isBlank()) return null;
        var baseId = label;
        for (final String suffix : LABEL_SUFFIXES)
            if (baseId.endsWith(suffix)) { baseId = baseId.substring(0, baseId.length() - suffix.length()); break; }
        final Path pack = sessionsDir.getParent().resolve("packs").resolve(baseId + ".md");
        if (!Files.isRegularFile(pack)) return null;
        final String prefix = "# Current task: " + baseId + " — ";
        try (var reader = Files.newBufferedReader(pack, StandardCharsets.UTF_8)) {
            final String firstLine = reader.readLine();
            return firstLine != null && firstLine.startsWith(prefix) ? firstLine.substring(prefix.length()) : null;
        } catch (IOException e) {
            log.debug("could not read the task title out of {}: {}", pack, e.toString());
            return null;
        }
    }

    /** A session's own transcript carries its real completion signal — a trailing {"type":"end",
     *  "finish":..., "turns":..., ...} line — unlike the old ported-from-Python progress.py, which
     *  looked for a "done after N turns (finish=X, ...)" line in a *.log file nothing in this Java
     *  runner ever writes; that mechanism never fired, so a session that finished stayed "running" in
     *  the live view for the rest of the job. Matched by the session's real id (from its header), not
     *  guessed as "the oldest still-open session" — correct under concurrent (parallel-wave) sessions
     *  too, not just ones that happen to finish in start order.
     *
     *  Found live (#322): a length-retry/re-engage continuation appends a SECOND "end" line to this
     *  SAME file (same session id, same path) once it finishes for real - returning on the first
     *  "end" line found (and never looking at this file again) meant the live view got stuck forever
     *  on the truncated call's own "length"/"stop"-with-no-status outcome, even once the
     *  continuation actually finished the work. tailLines() is already byte-offset incremental, so
     *  re-scanning on every poll costs nothing once a file has no new lines; this now takes the LAST
     *  "end" line seen in each poll's new lines, so a later, truer outcome always wins. */
    private void sessionEnd(final Path p, final String key, final List<Map<String, Object>> events) {
        JsonNode last = null;
        for (String line : tailLines(p)) {
            if (line.isBlank()) continue;
            try {
                final JsonNode r = JSON.readTree(line);
                if ("end".equals(r.path("type").asText())) last = r;
            } catch (Exception e) { log.debug("could not parse a session line for end-detection, skipping it: {}", e.toString()); }
        }
        if (last != null) {
            // found live on T14: a rescue attempt that itself made zero further turns before also
            // ending wrote "finish":"" (present and textual, so the old check let it straight
            // through) rather than omitting the field - the Stats-tab status column has no other
            // fallback for an EMPTY string the way it does for a genuinely missing one, so the
            // operator just saw a blank cell where a status word belongs. isBlank(), not just
            // isTextual(), so an empty/whitespace-only finish gets the same "?" placeholder a
            // missing one already does.
            final var finishNode = last.path("finish");
            final var finish = finishNode.isTextual() && !finishNode.asText().isBlank() ? finishNode.asText() : "?";
            events.add(Map.of("type", "session_done", "session_id", sessionIdByFile.getOrDefault(key, ""),
                    "finish", finish,
                    "ts", last.path("ts").isTextual() ? last.path("ts").asText() : ""));
        }
    }

    private void tailRequests(final Path path, final List<Map<String, Object>> events, final LocalModelBackend backend) {
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
                final Double latencySec = r.path("latency_sec").isNumber() ? r.path("latency_sec").asDouble() : null;
                final Double ttftSec = r.path("first_byte_ms").isNumber() ? r.path("first_byte_ms").asDouble() / 1000.0 : null;
                e.put("latency_sec", latencySec);
                e.put("ttft_sec", ttftSec);
                // session_id: set by RecordingProxy from the agent's X-Ace-Session-Id header, so a
                // request is attributed to its session unambiguously even under concurrent (parallel-
                // wave) sessions. prefill/decode speed: the backend reports these itself per response
                // (#65: field names are backend.speedFieldNames(), not a literal oMLX field name) -
                // ground truth from the inference engine, not re-derived from proxy-observed timings.
                e.put("session_id", r.path("session_id").isTextual() ? r.path("session_id").asText() : null);
                final JsonNode usage = r.path("response").path("usage");
                final LocalModelBackend.SpeedFieldNames speedFields = backend.speedFieldNames();
                final Long completionTokens = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asLong() : null;
                final Long promptTokens = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asLong() : null;
                Double prefillTps = usage.path(speedFields.prefillTpsField()).isNumber() ? usage.path(speedFields.prefillTpsField()).asDouble() : null;
                Double decodeTps = usage.path(speedFields.decodeTpsField()).isNumber() ? usage.path(speedFields.decodeTpsField()).asDouble() : null;
                // #590-followup, found live: a remote OpenAI-compatible provider (Nebius and
                // friends) never reports the backend's own native speed fields - every one of its
                // requests left prefill/decode tok/s null forever in the live Sessions grid, the
                // exact same gap #558 already fixed for the Stats tab's own (separate, batch-read)
                // request-speed charts. Same fallback formula as BenchController.
                // parseRequestSpeedEntry(): prefill tok/s from prompt_tokens over ttft, decode tok/s
                // from completion_tokens over the remaining (latency - ttft) span - never
                // overwriting a backend's own reported figures when those are present.
                if (prefillTps == null && promptTokens != null && ttftSec != null && ttftSec > 0)
                    prefillTps = promptTokens / ttftSec;
                if (decodeTps == null && completionTokens != null && latencySec != null && ttftSec != null && latencySec - ttftSec > 0)
                    decodeTps = completionTokens / (latencySec - ttftSec);
                e.put("prefill_tok_per_sec", prefillTps);
                e.put("decode_tok_per_sec", decodeTps);
                // per-request completion tokens, summed per session for the sessions grid's "total
                // tokens" column - budget_spent_completion_tokens is a per-PROXY running counter that
                // RESETS on a continuation's fresh proxy (given the REMAINING budget, not zero), so it
                // undercounts a session that spans one; summing this instead is correct regardless.
                e.put("completion_tokens", completionTokens);
                // the request's own prompt (prefill) token count, for the run-wide "total prompt
                // tokens" live figure - summed client-side the same way completion_tokens already is.
                e.put("prompt_tokens", promptTokens);
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
