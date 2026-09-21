package com.strgmai.ace.service.proxy;

import com.strgmai.ace.service.config.BenchProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Port of runner/record_proxy.py: transparent recording proxy, agent -> :PORT -> upstream.
 *  Journals EVERY interaction as JSONL (ts, method, path, request as forwarded, response, status,
 *  latency): the run's ground truth for metrics and validity. Pins the sampler params onto every
 *  chat request (what the manifest claims is what is sent) and REFUSES with 429 once the phase's
 *  completion-token budget is spent (budget_exceeded=true in the journal — the turn the trajectory
 *  analysis needs). Streams SSE line-by-line upstream; asks the server to append a usage chunk.
 *  Not a singleton: RunBench's RecordingProxyFactory creates one per phase/task/parallel session. */
public class RecordingProxy {
    private static final Logger log = LoggerFactory.getLogger(RecordingProxy.class);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicLong spent = new AtomicLong();
    private final BenchProperties props;

    private HttpServer server;
    private Path journal;
    private Long tokenBudget;
    private String tag;
    private volatile boolean stopping;
    private final Set<HttpResponse<InputStream>> inflight = ConcurrentHashMap.newKeySet();
    public static final double CHARS_PER_TOKEN = 3.6;   // the usage ESTIMATE of an abandoned stream

    public RecordingProxy(BenchProperties props) { this.props = props; }

    /** start on a free port; the journal is APPENDED across restarts (one proxy per phase/task, like the Python original) */
    public synchronized String start(Path journal, Long tokenBudget, String tag) throws Exception {
        stop();
        this.journal = journal; this.tokenBudget = tokenBudget; this.tag = tag; this.stopping = false;
        Files.createDirectories(journal.toAbsolutePath().getParent());
        if (!Files.exists(journal)) Files.createFile(journal);
        // --append: the seq continues the journal, as the Python proxy does. A failed count here
        // silently restarts seq at 0, which can collide with existing entries - worth knowing about.
        try { seq.set((int) Files.lines(journal).count()); }
        catch (Exception e) { log.warn("could not count existing journal lines in {}, seq restarts at 0: {}", journal, e.toString()); }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            try { forward(x); }
            catch (Exception e) {
                log.warn("recording proxy: unhandled failure forwarding {} {}: {}", x.getRequestMethod(), x.getRequestURI(), e.toString());
                try { x.close(); } catch (Exception ignore) {}
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** SIGTERM-equivalent: stop accepting, abort in-flight streams (they are journaled as drain_aborted) */
    public synchronized void stop() {
        if (server == null) return;
        stopping = true;
        for (HttpResponse<InputStream> r : inflight) {
            try { r.body().close(); } catch (Exception e) { log.debug("closing an in-flight stream on stop(): {}", e.toString()); }
        }
        server.stop(0);
        server = null;
    }

    private void forward(final com.sun.net.httpserver.HttpExchange x) throws Exception {
        byte[] body = x.getRequestBody().readAllBytes();
        final String path = x.getRequestURI().getPath();
        final ObjectNode rec = json.createObjectNode();
        rec.put("ts", Instant.now().toString());
        rec.put("method", x.getRequestMethod());
        rec.put("path", path);
        JsonNode req = null;
        // a chat request that fails to parse here silently skips budget enforcement and sampler
        // pinning below (isChat requires a parsed object) - worth knowing about, not just "not chat"
        try { if (body.length > 0) req = json.readTree(body); }
        catch (Exception e) { log.warn("could not parse request body as JSON for {} {}: {}", x.getRequestMethod(), path, e.toString()); }
        final boolean isChat = path.startsWith("/v1/chat/completions") && req != null && req.isObject();
        if (isChat) {
            final ObjectNode r = (ObjectNode) req;
            if (props.temperature() != null) r.put("temperature", props.temperature());
            if (props.topP() != null) r.put("top_p", props.topP());
            if (props.seed() != null) r.put("seed", props.seed());
            r.put("max_tokens", props.maxOutputTokens());
            if (r.path("stream").asBoolean(false)) {   // ask the server to append a usage chunk (transparent to the client)
                ((ObjectNode) r.with("stream_options")).put("include_usage", true);
                body = json.writeValueAsBytes(r);
            } else body = json.writeValueAsBytes(r);
            if (tokenBudget != null && spent.get() >= tokenBudget) {
                byte[] out = json.writeValueAsBytes(json.createObjectNode().set("error",
                        json.createObjectNode().put("message", "ace-service: phase output-token budget exhausted (" + spent.get() + "/" + tokenBudget + " completion tokens)").put("type", "budget_exceeded")));
                reply(x, 429, out);
                journalRecord(rec.put("status", 429).put("budget_exceeded", true)
                        .put("latency_sec", 0.0), req, null);
                return;
            }
        }
        final long t0 = System.nanoTime();
        HttpRequest.Builder ub = HttpRequest.newBuilder(URI.create(props.upstreamBase() + path))
                .method(x.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body.length > 0 ? body : new byte[0]));
        x.getRequestHeaders().forEach((k, v) -> { if (!List.of("Host", "Content-length", "Connection").contains(k)) ub.header(k, v.get(0)); });
        if (body.length > 0 && x.getRequestHeaders().getFirst("Content-Type") == null) ub.header("Content-Type", "application/json");
        try {
            final HttpResponse<InputStream> up = http.send(ub.build(), HttpResponse.BodyHandlers.ofInputStream());
            inflight.add(up);
            final String ctype = up.headers().firstValue("Content-Type").orElse("");
            if (ctype.contains("text/event-stream")) {
                streamSse(x, up, rec, req, isChat, t0);
            } else {
                final byte[] out = up.body().readAllBytes();
                reply(x, up.statusCode(), out);
                JsonNode resp = null;
                // an unparseable upstream response loses the response data for this turn in the
                // journal, which is the run's ground truth for metrics - worth knowing about
                try { if (isChat) resp = json.readTree(out); }
                catch (Exception e) { log.warn("could not parse upstream response as JSON for {}: {}", path, e.toString()); }
                journalRecord(rec.put("status", up.statusCode()).put("streamed", false)
                        .put("latency_sec", elapsed(t0)), req, resp);
            }
            inflight.remove(up);
        } catch (Exception e) {
            journalRecord(rec.put("status", 502).put("upstream_error", true)
                    .put("error", e.toString()).put("latency_sec", elapsed(t0)), req, null);
            reply(x, 502, json.writeValueAsBytes(json.createObjectNode().set("error",
                    json.createObjectNode().put("message", e.toString()).put("type", "upstream_error"))));
        }
    }

    private void streamSse(com.sun.net.httpserver.HttpExchange x, HttpResponse<InputStream> up,
                           ObjectNode rec, JsonNode req, boolean isChat, long t0) throws Exception {
        final List<byte[]> chunks = new CopyOnWriteArrayList<>();
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.sendResponseHeaders(up.statusCode(), 0);
        boolean clientAborted = false, drainAborted = false;
        try (InputStream in = up.body(); OutputStream out = x.getResponseBody()) {
            final byte[] buf = new byte[8192]; int n; StringBuilder line = new StringBuilder();
            while ((n = in.read(buf)) >= 0) {
                final var s = new String(buf, 0, n, StandardCharsets.UTF_8);
                int start = 0;
                while (start <= s.length()) {
                    final int nl = s.indexOf('\n', start);
                    if (nl < 0) { line.append(s, start, s.length()); break; }
                    line.append(s, start, nl + 1);
                    final byte[] c = line.toString().getBytes(StandardCharsets.UTF_8);
                    line.setLength(0);
                    chunks.add(c);
                    out.write(c); out.flush();
                    start = nl + 1;
                }
                if (stopping) { drainAborted = true; break; }
            }
            out.flush();
        } catch (Exception e) {
            // client_aborted IS journaled below - this only adds the reason for diagnosing whether
            // it was a genuine client disconnect or a bug in the read/relay loop itself
            log.debug("SSE stream ended abnormally: {}", e.toString());
            clientAborted = true;
        }
        if (stopping) drainAborted = true;
        final SseAssembler.Assembled asm = isChat ? SseAssembler.parse(chunks) : null;
        final ObjectNode resp = json.createObjectNode();
        if (asm != null) {
            resp.put("content", asm.content()); resp.put("reasoning", asm.reasoning()); resp.put("finish_reason", asm.finishReason());
            resp.set("tool_calls", json.valueToTree(asm.toolCalls()));
            if (asm.usage() != null) resp.set("usage", asm.usage());
            else if (drainAborted)   // an abandoned stream has no usage chunk: estimate, and say so
                resp.putPOJO("usage", estimatedUsage(asm));
        } else resp.put("_bytes", chunks.stream().mapToLong(c -> c.length).sum());
        rec.put("status", up.statusCode()).put("streamed", true).put("latency_sec", elapsed(t0));
        if (clientAborted) rec.put("client_aborted", true);
        if (drainAborted) rec.put("drain_aborted", true);
        journalRecord(rec, req, resp);
        inflight.remove(up);
    }

    private ObjectNode estimatedUsage(final SseAssembler.Assembled asm) {
        int chars = asm.content().length() + asm.reasoning().length();
        for (Map<String, Object> t : asm.toolCalls()) chars += String.valueOf(t.get("arguments")).length();
        final ObjectNode u = json.createObjectNode();
        u.put("completion_tokens", (int) (chars / CHARS_PER_TOKEN));
        u.put("estimated", true);
        return u;
    }

    private static double elapsed(long t0) { return Math.round((System.nanoTime() - t0) / 1e7) / 100.0; }

    private void reply(final com.sun.net.httpserver.HttpExchange x, final int status, final byte[] out) throws Exception {
        try {
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) { x.getResponseBody().write(out); x.getResponseBody().flush(); }
        } catch (Exception e) {
            log.debug("could not write response to client (likely disconnected): {}", e.toString());
        } finally { try { x.close(); } catch (Exception ignore) {} }
    }

    private synchronized void journalRecord(final ObjectNode rec, final JsonNode req, final JsonNode resp) {
        try {
            if (req != null) rec.set("request", req);
            if (resp != null) rec.set("response", resp);
            if (tag != null) rec.put("task", tag);
            final JsonNode u = resp == null ? null : resp.get("usage");
            if (u != null && u.hasNonNull("completion_tokens")) spent.addAndGet(u.get("completion_tokens").asLong());
            rec.put("budget_spent_completion_tokens", spent.get());
            rec.put("seq", seq.incrementAndGet());
            Files.write(journal, json.writeValueAsBytes(rec), StandardOpenOption.CREATE, StandardOpenOption.APPEND);   // APPEND: a journal is never truncated
            Files.writeString(journal, "\n", StandardOpenOption.APPEND);
        } catch (Exception e) {
            // the journal must never break the proxy - but losing a journal record (the run's own
            // ground truth for metrics/validity) is worth knowing about, so log it rather than vanish it
            log.warn("failed to write journal record to {}: {}", journal, e.toString());
        }
    }

    public long spent() { return spent.get(); }
}
