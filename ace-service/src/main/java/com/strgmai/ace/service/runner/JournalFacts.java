package com.strgmai.ace.service.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(JournalFacts.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WS_REF = Pattern.compile("agentbench-ws/([A-Za-z0-9][A-Za-z0-9._-]{3,})");

    /** cache: path -> [bytes consumed, mtimeNs, entries]; guarded so parallel tasks can share it */
    private static final Map<String, Cache> CACHE = new ConcurrentHashMap<>();
    record Cache(long consumed, long mtimeNs, List<Entry> entries) {}

    record Entry(long off, String task, OffsetDateTime ts, int status, boolean budget, boolean upstream,
                 boolean clientAbort, boolean drainAbort, boolean truncated, long ct, boolean est, Object pt,
                 String fin, String effort, boolean reqDict, List<String> ws,
                 double latencySec, Long firstByteMs, Double prefillTps, Double decodeTps) {}

    /** context-size buckets for speed_by_context: a fixed 1024-token width - fine enough to show real
     *  texture in the prefill/decode-vs-context trend across the range typical runs actually span. */
    private static final long SPEED_BY_CONTEXT_BUCKET_WIDTH = 1024;

    static int contextBucket(final long promptTokens) {
        return (int) (promptTokens / SPEED_BY_CONTEXT_BUCKET_WIDTH);
    }

    static long[] bucketRange(final int bucket) {
        return new long[]{bucket * SPEED_BY_CONTEXT_BUCKET_WIDTH, (bucket + 1) * SPEED_BY_CONTEXT_BUCKET_WIDTH};
    }

    public static Map<String, Object> facts(String path, String sinceIso, String untilIso,
                                            List<String[]> exclude, List<String> normalise, String tag) {
        final Map<String, Object> f = new LinkedHashMap<>();
        f.put("requests", 0); f.put("errors", 0); f.put("budget_refusals", 0); f.put("upstream_errors", 0);
        f.put("client_aborts", 0); f.put("drain_aborted", 0); f.put("truncated", 0);
        f.put("estimated_completion_tokens", 0L); f.put("completion_tokens", 0L); f.put("foreign_workspace_refs", 0);
        if (path == null || !Files.isRegularFile(Path.of(path))) return f;
        final OffsetDateTime lo = sinceIso == null ? null : OffsetDateTime.parse(sinceIso);
        final OffsetDateTime hi = untilIso == null ? null : OffsetDateTime.parse(untilIso);
        final List<String[]> ex = exclude == null ? List.of() : exclude;
        final String wsRoot = normalise == null ? null : normalise.stream().filter(n -> n != null && n.contains("agentbench-ws")).findFirst().orElse(null);
        final String own = wsRoot == null ? null : Path.of(wsRoot).getParent().getFileName().toString();
        final boolean window = lo != null || hi != null || !ex.isEmpty();
        double latencySum = 0; int latencyN = 0;
        long ttftSum = 0; int ttftN = 0;
        final Map<Integer, long[]> bucketRequests = new TreeMap<>();
        final Map<Integer, double[]> bucketPrefill = new TreeMap<>();   // bucket -> [sum, n]
        final Map<Integer, double[]> bucketDecode = new TreeMap<>();    // bucket -> [sum, n]
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
            // budget refusals are instant local rejections (always latency_sec=0), not real network
            // latency - excluded so they don't drag the average down
            if (!e.budget()) { latencySum += e.latencySec(); latencyN++; }
            // only streamed requests carry a first byte time - the request's actual time-to-first-token
            if (e.firstByteMs() != null) { ttftSum += e.firstByteMs(); ttftN++; }
            // how prefill/decode speed depends on context size (#new metric): prompt_tokens as the
            // context-size proxy, oMLX's own reported tok/s per request - bucketed so the run page can
            // chart a trend without re-parsing the (possibly tens-of-MB) journal itself
            if (e.pt() instanceof Long pt) {
                final int bucket = contextBucket(pt);
                bucketRequests.computeIfAbsent(bucket, k -> new long[1])[0]++;
                if (e.prefillTps() != null) {
                    final double[] s = bucketPrefill.computeIfAbsent(bucket, k -> new double[2]);
                    s[0] += e.prefillTps(); s[1]++;
                }
                if (e.decodeTps() != null) {
                    final double[] s = bucketDecode.computeIfAbsent(bucket, k -> new double[2]);
                    s[0] += e.decodeTps(); s[1]++;
                }
            }
            f.merge("completion_tokens", e.ct(), (a, b) -> (long) a + (long) b);
            if (e.est()) f.merge("estimated_completion_tokens", e.ct(), (a, b) -> (long) a + (long) b);
            if (e.effort() != null) {
                @SuppressWarnings("unchecked") Map<String, Integer> eff = (Map<String, Integer>) f.computeIfAbsent("reasoning_efforts", x -> new LinkedHashMap<String, Integer>());
                eff.merge(e.effort(), 1, Integer::sum);
            }
            if (!f.containsKey("sampler_effective") && e.reqDict()) {
                final JsonNode req = requestAt(Path.of(path), e.off());
                // the normalised system-prompt hash: run-specific strings replaced, dates folded (R4 C-6) - comparable across runs
                if (req.path("messages").isArray()) {
                    final var sysm = new StringBuilder("[");
                    for (JsonNode m : req.get("messages"))
                        if ("system".equals(m.path("role").asText())) sysm.append(m.path("content").isTextual() ? jsonQuote(m.path("content").asText()) : m.path("content").toString()).append(',');
                    String raw = sysm.append("]").toString();
                    for (String n : normalise == null ? List.<String>of() : normalise) if (n != null && !n.isBlank()) raw = raw.replace(n, "<run>");
                    f.put("system_prompt_sha", sha(raw.replaceAll("\\d{4}-\\d{2}-\\d{2}", "<date>")));
                }
                final Map<String, Object> sampler = new LinkedHashMap<>();
                for (String k : List.of("temperature", "top_p", "seed", "max_tokens", "max_completion_tokens"))
                    sampler.put(k, req.path(k).isNumber() ? req.path(k).numberValue() : req.path(k).isTextual() ? req.path(k).asText() : null);
                f.put("sampler_effective", sampler);
                f.put("system_prompt_sha_raw", sha(jsonOf(req.path("messages"))));
                f.put("first_prompt_tokens", e.pt());
            }
        }
        if (latencyN > 0) f.put("avg_latency_sec", Math.round(latencySum / latencyN * 100) / 100.0);
        if (ttftN > 0) f.put("avg_first_byte_ms", Math.round((double) ttftSum / ttftN));
        if (!bucketRequests.isEmpty()) {
            final List<Map<String, Object>> buckets = new ArrayList<>();
            for (final var b : bucketRequests.entrySet()) {
                final long[] range = bucketRange(b.getKey());
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("context_lo", range[0]);
                row.put("context_hi", range[1]);
                row.put("requests", b.getValue()[0]);
                final double[] pfx = bucketPrefill.get(b.getKey());
                row.put("avg_prefill_tok_per_sec", pfx == null ? null : Math.round(pfx[0] / pfx[1] * 100) / 100.0);
                final double[] dec = bucketDecode.get(b.getKey());
                row.put("avg_decode_tok_per_sec", dec == null ? null : Math.round(dec[0] / dec[1] * 100) / 100.0);
                buckets.add(row);
            }
            f.put("speed_by_context", buckets);
        }
        return f;
    }

    private static String jsonOf(JsonNode n) { return n == null || n.isMissingNode() ? "[]" : n.toString(); }

    static List<Entry> entries(final Path p) {
        try {
            final long size = Files.size(p), mtime = Files.getLastModifiedTime(p).toMillis();
            Cache c = CACHE.get(p.toString());
            if (c != null && (size < c.consumed() || mtime != c.mtimeNs())) c = null;      // truncated or rewritten: reparse
            long start = c == null ? 0 : c.consumed();
            final List<Entry> entries = c == null ? new ArrayList<>() : new ArrayList<>(c.entries());
            if (size > start) {
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
                    raf.seek(start);
                    final byte[] data = new byte[(int) (size - start)];
                    final int read = raf.read(data);
                    if (read > 0) {
                        int lastNl = -1;
                        for (int i = read - 1; i >= 0; i--) if (data[i] == '\n') { lastNl = i; break; }   // BYTE index
                        if (lastNl >= 0) {
                            long off = start;
                            int lineStart = 0;
                            for (int i = 0; i <= lastNl; i++) {
                                if (data[i] == '\n') {
                                    if (i > lineStart) {
                                        final Entry e = parseEntry(new String(data, lineStart, i - lineStart, StandardCharsets.UTF_8), off);
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
        } catch (IOException e) {
            // this journal's request counts/errors/budget refusals all silently read as zero -
            // worth knowing about rather than mistaking for a genuinely uneventful run
            log.warn("could not read journal {}: {}", p, e.toString());
            return List.of();
        }
    }

    static Entry parseEntry(String line, final long off) {
        JsonNode r;
        try { r = JSON.readTree(line); }
        catch (Exception e) { log.debug("could not parse journal line as JSON, dropping it: {}", e.toString()); return null; }
        if (!r.isObject() || !r.path("path").asText("").startsWith("/v1/chat/completions")) return null;
        final JsonNode resp = r.get("response");
        final JsonNode u = resp != null && resp.isObject() && resp.path("usage").isObject() ? resp.get("usage") : null;
        final JsonNode req = r.get("request");
        OffsetDateTime ts = null;
        try { ts = r.hasNonNull("ts") ? OffsetDateTime.parse(r.get("ts").asText()) : null; }
        catch (Exception e) { log.debug("could not parse journal entry timestamp: {}", e.toString()); }
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
                effort, req != null && req.isObject(), ws,
                r.path("latency_sec").asDouble(0.0),
                r.path("first_byte_ms").isMissingNode() ? null : r.path("first_byte_ms").asLong(),
                u == null || !u.path("prompt_tokens_per_second").isNumber() ? null : u.path("prompt_tokens_per_second").asDouble(),
                u == null || !u.path("generation_tokens_per_second").isNumber() ? null : u.path("generation_tokens_per_second").asDouble());
    }

    static JsonNode requestAt(final Path p, final long off) {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            raf.seek(off);
            final String line = raf.readLine();
            final JsonNode r = JSON.readTree(new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            return r.path("request");
        } catch (Exception e) {
            // sampler_effective/system_prompt_sha silently end up uncomputed for this journal - a
            // one-shot lookup (not per-line), so worth surfacing at WARN
            log.warn("could not re-read the request at offset {} in {}: {}", off, p, e.toString());
            return JSON.createObjectNode();
        }
    }

    /** port of last_finish: the finish_reason of the last chat completion after sinceIso */
    public static String lastFinish(final String journal, final String sinceIso) {
        if (journal == null || !Files.isRegularFile(Path.of(journal))) return null;
        final OffsetDateTime since = OffsetDateTime.parse(sinceIso);
        String fin = null;
        for (Entry e : entries(Path.of(journal)))
            if (e.ts() != null && !e.ts().isBefore(since) && e.fin() != null) fin = e.fin();
        return fin;
    }

    /** port of last_prompt_tokens: how full the context was when the session ended */
    public static Long lastPromptTokens(final String journal, final String sinceIso) {
        if (journal == null || !Files.isRegularFile(Path.of(journal))) return null;
        final OffsetDateTime since = OffsetDateTime.parse(sinceIso);
        Long pt = null;
        for (Entry e : entries(Path.of(journal)))
            if (e.ts() != null && !e.ts().isBefore(since) && e.pt() instanceof Long l) pt = l;
        return pt;
    }

    static String jsonQuote(String t) { return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    static String sha(final String s) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16); }
        catch (Exception e) { log.debug("SHA-256 unavailable: {}", e.toString()); return null; }
    }
}
