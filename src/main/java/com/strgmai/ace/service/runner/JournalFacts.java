package com.strgmai.ace.service.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Port of run_bench.py's journal_facts (WITH the parse-once-per-version cache from the Python fix):
 *  what was ACTUALLY sent — request counts, error classes, budget refusals, completion tokens,
 *  reasoning efforts, the first in-window request's sampler/system-prompt facts — within a window,
 *  never inside exclude windows (review traffic is not the agent's). The journal is parsed once per
 *  file version into compact entries (no request/response bodies); appends extend the cache; a
 *  rewrite rebuilds it; the one record whose request is needed is re-read from its byte offset. */
public final class JournalFacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WS_REF = Pattern.compile("agentbench-ws/([A-Za-z0-9][A-Za-z0-9._-]{3,})");

    /** cache: path -> [bytes consumed, mtimeNs, entries]; guarded so parallel tasks can share it */
    private static final Map<String, Cache> CACHE = new ConcurrentHashMap<>();
    record Cache(long consumed, long mtimeNs, List<Entry> entries) {}

    record Entry(long off, String task, OffsetDateTime ts, int status, boolean budget, boolean upstream,
                 boolean clientAbort, boolean drainAbort, boolean truncated, long ct, boolean est, Object pt,
                 String fin, String effort, boolean reqDict, List<String> ws) {}

    public static Map<String, Object> facts(String path, String sinceIso, String untilIso,
                                            List<String[]> exclude, List<String> normalise, String tag) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("requests", 0); f.put("errors", 0); f.put("budget_refusals", 0); f.put("upstream_errors", 0);
        f.put("client_aborts", 0); f.put("drain_aborted", 0); f.put("truncated", 0);
        f.put("estimated_completion_tokens", 0L); f.put("completion_tokens", 0L); f.put("foreign_workspace_refs", 0);
        if (path == null || !Files.isRegularFile(Path.of(path))) return f;
        OffsetDateTime lo = sinceIso == null ? null : OffsetDateTime.parse(sinceIso);
        OffsetDateTime hi = untilIso == null ? null : OffsetDateTime.parse(untilIso);
        List<String[]> ex = exclude == null ? List.of() : exclude;
        String wsRoot = normalise == null ? null : normalise.stream().filter(n -> n != null && n.contains("agentbench-ws")).findFirst().orElse(null);
        String own = wsRoot == null ? null : Path.of(wsRoot).getParent().getFileName().toString();
        boolean window = lo != null || hi != null || !ex.isEmpty();
        for (Entry e : entries(Path.of(path))) {
            if (tag != null && !Objects.equals(e.task(), tag)) continue;          // a parallel task's own records
            if (window) {
                if (e.ts() == null || (lo != null && e.ts().isBefore(lo)) || (hi != null && e.ts().isAfter(hi))
                        || ex.stream().anyMatch(w -> { OffsetDateTime a = OffsetDateTime.parse(w[0]), b = OffsetDateTime.parse(w[1]);
                            return !e.ts().isBefore(a) && !e.ts().isAfter(b); })) continue;
            }
            f.merge("requests", 1, (a, b) -> (int) a + 1);
            if (e.budget()) f.merge("budget_refusals", 1, (a, b) -> (int) a + 1);
            else if (e.upstream()) f.merge("upstream_errors", 1, (a, b) -> (int) a + 1);
            else if (e.status() >= 400) f.merge("errors", 1, (a, b) -> (int) a + 1);
            if (wsRoot != null && e.ws() != null && e.ws().stream().anyMatch(x -> !x.equals(own)))
                f.merge("foreign_workspace_refs", 1, (a, b) -> (int) a + 1);      // a reference to ANOTHER run's workspace
            if (e.clientAbort()) f.merge("client_aborts", 1, (a, b) -> (int) a + 1);
            if (e.drainAbort()) f.merge("drain_aborted", 1, (a, b) -> (int) a + 1);
            if (e.truncated()) f.merge("truncated", 1, (a, b) -> (int) a + 1);
            f.merge("completion_tokens", e.ct(), (a, b) -> (long) a + (long) b);
            if (e.est()) f.merge("estimated_completion_tokens", e.ct(), (a, b) -> (long) a + (long) b);
            if (e.effort() != null) {
                @SuppressWarnings("unchecked") Map<String, Integer> eff = (Map<String, Integer>) f.computeIfAbsent("reasoning_efforts", x -> new LinkedHashMap<String, Integer>());
                eff.merge(e.effort(), 1, Integer::sum);
            }
            if (!f.containsKey("sampler_effective") && e.reqDict()) {
                JsonNode req = requestAt(Path.of(path), e.off());
                // the normalised system-prompt hash: run-specific strings replaced, dates folded (R4 C-6) - comparable across runs
                if (req.path("messages").isArray()) {
                    StringBuilder sysm = new StringBuilder("[");
                    for (JsonNode m : req.get("messages"))
                        if ("system".equals(m.path("role").asText())) sysm.append(m.path("content").isTextual() ? jsonQuote(m.path("content").asText()) : m.path("content").toString()).append(',');
                    String raw = sysm.append("]").toString();
                    for (String n : normalise == null ? List.<String>of() : normalise) if (n != null && !n.isBlank()) raw = raw.replace(n, "<run>");
                    f.put("system_prompt_sha", sha(raw.replaceAll("\\d{4}-\\d{2}-\\d{2}", "<date>")));
                }
                Map<String, Object> sampler = new LinkedHashMap<>();
                for (String k : List.of("temperature", "top_p", "seed", "max_tokens", "max_completion_tokens"))
                    sampler.put(k, req.path(k).isNumber() ? req.path(k).numberValue() : req.path(k).isTextual() ? req.path(k).asText() : null);
                f.put("sampler_effective", sampler);
                f.put("system_prompt_sha_raw", sha(jsonOf(req.path("messages"))));
                f.put("first_prompt_tokens", e.pt());
            }
        }
        return f;
    }

    private static String jsonOf(JsonNode n) { return n == null || n.isMissingNode() ? "[]" : n.toString(); }

    static List<Entry> entries(Path p) {
        try {
            long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
            Cache c = CACHE.get(p.toString());
            if (c != null && (size < c.consumed() || mtime != c.mtimeNs())) c = null;      // truncated or rewritten: reparse
            long start = c == null ? 0 : c.consumed();
            List<Entry> entries = c == null ? new ArrayList<>() : new ArrayList<>(c.entries());
            if (size > start) {
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
                    raf.seek(start);
                    byte[] data = new byte[(int) (size - start)];
                    int read = raf.read(data);
                    if (read > 0) {
                        int lastNl = -1;
                        for (int i = read - 1; i >= 0; i--) if (data[i] == '\n') { lastNl = i; break; }   // BYTE index
                        if (lastNl >= 0) {
                            long off = start;
                            int lineStart = 0;
                            for (int i = 0; i <= lastNl; i++) {
                                if (data[i] == '\n') {
                                    if (i > lineStart) {
                                        Entry e = parseEntry(new String(data, lineStart, i - lineStart, StandardCharsets.UTF_8), off);
                                        if (e != null) entries.add(e);
                                    }
                                    off += (i - lineStart) + 1;   // byte-exact: offsets stay in BYTES whatever the encoding
                                    lineStart = i + 1;
                                }
                            }
                            start += lastNl + 1;      // the trailing partial line waits for the next append (BYTES)
                        }
                    }
                }
                CACHE.put(p.toString(), new Cache(start, Files.getLastModifiedTime(p).toMillis(), List.copyOf(entries)));
            }
            return entries;
        } catch (IOException e) { return List.of(); }
    }

    static Entry parseEntry(String line, long off) {
        JsonNode r;
        try { r = JSON.readTree(line); } catch (Exception e) { return null; }
        if (!r.isObject() || !r.path("path").asText("").startsWith("/v1/chat/completions")) return null;
        JsonNode resp = r.get("response");
        JsonNode u = resp != null && resp.isObject() && resp.path("usage").isObject() ? resp.get("usage") : null;
        JsonNode req = r.get("request");
        OffsetDateTime ts = null;
        try { ts = r.hasNonNull("ts") ? OffsetDateTime.parse(r.get("ts").asText()) : null; } catch (Exception ignore) {}
        List<String> ws = line.contains("agentbench-ws/")
                ? WS_REF.matcher(line).results().map(m -> m.group(1)).distinct().toList() : null;
        String effort = null;
        if (req != null && req.isObject())
            effort = String.valueOf(req.path("chat_template_kwargs").path("reasoning_effort").asText(null) == null ? "none"
                    : req.path("chat_template_kwargs").path("reasoning_effort").asText());
        return new Entry(off, r.path("task").asText(null), ts, r.path("status").asInt(0),
                r.path("budget_exceeded").asBoolean(false), r.path("upstream_error").asBoolean(false),
                r.path("client_aborted").asBoolean(false), r.path("drain_aborted").asBoolean(false),
                r.path("truncated").asBoolean(false),
                u == null ? 0 : u.path("completion_tokens").asLong(0), u != null && u.path("estimated").asBoolean(false),
                u == null || u.path("prompt_tokens").isMissingNode() ? null : u.path("prompt_tokens").asLong(),
                resp == null || !resp.isObject() ? null : resp.path("finish_reason").asText(null),
                effort, req != null && req.isObject(), ws);
    }

    static JsonNode requestAt(Path p, long off) {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            raf.seek(off);
            String line = raf.readLine();
            JsonNode r = JSON.readTree(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            return r.path("request");
        } catch (Exception e) { return JSON.createObjectNode(); }
    }

    /** port of last_finish: the finish_reason of the last chat completion after sinceIso */
    public static String lastFinish(String journal, String sinceIso) {
        if (journal == null || !Files.isRegularFile(Path.of(journal))) return null;
        OffsetDateTime since = OffsetDateTime.parse(sinceIso);
        String fin = null;
        for (Entry e : entries(Path.of(journal)))
            if (e.ts() != null && !e.ts().isBefore(since) && e.fin() != null) fin = e.fin();
        return fin;
    }

    /** port of last_prompt_tokens: how full the context was when the session ended */
    public static Long lastPromptTokens(String journal, String sinceIso) {
        if (journal == null || !Files.isRegularFile(Path.of(journal))) return null;
        OffsetDateTime since = OffsetDateTime.parse(sinceIso);
        Long pt = null;
        for (Entry e : entries(Path.of(journal)))
            if (e.ts() != null && !e.ts().isBefore(since) && e.pt() instanceof Long l) pt = l;
        return pt;
    }

    static String jsonQuote(String t) { return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    static String sha(String s) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16); }
        catch (Exception e) { return null; }
    }
}
