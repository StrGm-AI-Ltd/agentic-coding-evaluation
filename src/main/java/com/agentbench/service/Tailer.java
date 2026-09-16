package com.agentbench.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Port of service/progress.py (WITH the byte-exact offset fix from the Python review): live
 *  progress inferred from the files a run already writes. Stateless across connections: each caller
 *  owns one Tailer and keeps polling it. Offsets stay in BYTES — advancing by the re-encoded length
 *  of replace-decoded text would drift past the file's real end on invalid UTF-8. */
public final class Tailer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DONE = Pattern.compile("done after (\\d+) turns \\(finish=(\\w+), tool errors (\\d+), compactions (\\d+)\\)");

    private final Set<String> seenFiles = new HashSet<>();
    private final Map<String, Long> offsets = new HashMap<>();
    private final Set<String> sessionIds = new HashSet<>();

    public List<Map<String, Object>> poll(Path runDir) {
        List<Map<String, Object>> events = new ArrayList<>();
        newFiles(runDir.resolve("packs"), events);
        newFiles(runDir.resolve("instructions"), events);
        newSessions(runDir.resolve("sessions"), events);
        tailRequests(runDir.resolve("interactions.jsonl"), events);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(runDir, "*.log")) {
            for (Path p : s) {
                String finish = null;
                for (String line : tailLines(p)) {
                    var m = DONE.matcher(line);
                    if (m.find()) finish = m.group(2);
                }
                if (finish != null)
                    events.add(Map.of("type", "session_done", "log", p.getFileName().toString(), "finish", finish));
            }
        } catch (IOException ignore) {}
        return events;
    }

    private void newFiles(Path dir, List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        List<Path> paths;
        try { paths = Files.list(dir).filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } })).toList(); }
        catch (IOException e) { return; }
        for (Path p : paths) {
            String key = dir.getFileName() + "/" + p.getFileName();
            if (!Files.isRegularFile(p) || seenFiles.contains(key)) continue;
            seenFiles.add(key);
            String stem = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            if ("packs".equals(dir.getFileName().toString()) && "stable".equals(stem)) continue;   // the persistent pack, not a step
            boolean continuation = stem.endsWith("-continue");
            if (continuation) stem = stem.substring(0, stem.length() - "-continue".length());
            events.add(Map.of("type", "step_started", "step", stem, "continuation", continuation, "source", dir.getFileName().toString()));
        }
    }

    private void newSessions(Path dir, List<Map<String, Object>> events) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (!Files.isRegularFile(p) || sessionIds.contains(p.getFileName().toString())) continue;
                try {
                    String first = Files.readString(p, StandardCharsets.UTF_8).split("\n", 2)[0];
                    JsonNode r = JSON.readTree(first);
                    if (!"session".equals(r.path("type").asText())) continue;
                    sessionIds.add(p.getFileName().toString());
                    events.add(Map.of("type", "session_started", "session_id", r.path("id").asText(),
                            "agent", r.path("agent").asText(), "model", r.path("model").asText(),
                            "path", "sessions/" + p.getFileName()));
                } catch (Exception ignore) { /* header not fully written yet; retry next poll */ }
            }
        } catch (IOException ignore) {}
    }

    private void tailRequests(Path path, List<Map<String, Object>> events) {
        for (String line : tailLines(path)) {
            if (line.isBlank()) continue;
            try {
                JsonNode r = JSON.readTree(line);
                if (!r.path("path").asText("").startsWith("/v1/chat/completions")) continue;
                Map<String, Object> e = new LinkedHashMap<>();   // Map.of is null-hostile; `tag` is absent on most records
                e.put("type", "request");
                e.put("seq", r.path("seq").asInt());
                e.put("ts", r.path("ts").asText(""));
                e.put("status", r.path("status").asInt());
                e.put("budget_spent_completion_tokens", r.path("budget_spent_completion_tokens").asLong());
                e.put("client_aborted", r.path("client_aborted").asBoolean(false));
                e.put("tag", r.path("task").isTextual() ? r.path("task").asText() : null);
                events.add(e);
            } catch (Exception ignore) {}
        }
    }

    /** byte-exact tailing: consume through the last newline; a partial line waits for the next poll */
    List<String> tailLines(Path path) {
        try {
            long size = Files.size(path);
            long start = offsets.getOrDefault(path.toString(), 0L);
            if (size <= start) return List.of();
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(start);
                byte[] data = new byte[(int) (size - start)];
                int read = raf.read(data);
                if (read <= 0) return List.of();
                int lastNl = -1;
                for (int i = read - 1; i >= 0; i--) if (data[i] == '\n') { lastNl = i; break; }   // BYTE index, not a char index
                if (lastNl < 0) return List.of();                       // no complete line yet; retry next poll
                List<String> lines = new ArrayList<>();
                int lineStart = 0;
                for (int i = 0; i <= lastNl; i++)
                    if (data[i] == '\n') { lines.add(new String(data, lineStart, i - lineStart, StandardCharsets.UTF_8)); lineStart = i + 1; }
                offsets.put(path.toString(), start + lastNl + 1);      // byte-exact: valid multi-byte UTF-8 never drifts
                return lines;
            }
        } catch (IOException e) { return List.of(); }
    }
}
