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
    private ExecutorService serverExecutor;
    private Path journal;
    private Long tokenBudget;
    private String tag;
    // the run's own per-run sampler overrides - null fields fall back to the operator-wide ace.*
    // default (temperature/topP/maxOutputTokens) or are simply omitted from the request
    // (topK/repetitionPenalty/reasoningEffort - no such process-wide default exists for these).
    // See forward() below.
    private SamplerOverrides overrides = SamplerOverrides.NONE;

    /** Per-run sampler knobs pinned onto every chat request this proxy relays - "pins the sampler
     *  params onto every chat request" (see the class doc): what the manifest claims is what is
     *  sent. maxOutputTokens: the run's own context-probe-derived (or explicitly operator-set)
     *  output-token cap for ONE request - distinct from tokenBudget (the whole phase's
     *  completion-token budget across every turn). reasoningEffort overrides whatever
     *  ReferenceAgent's own per-phase-kind DEFAULT_REASONING already put on the request - this
     *  proxy runs last, closest to the wire, so it always wins when set. */
    public record SamplerOverrides(Double temperature, Double topP, Integer topK, Double repetitionPenalty,
                                    Integer maxOutputTokens, String reasoningEffort) {
        public static final SamplerOverrides NONE = new SamplerOverrides(null, null, null, null, null, null);
    }
    private volatile boolean stopping;
    private final Set<HttpResponse<InputStream>> inflight = ConcurrentHashMap.newKeySet();
    // set by whoever decides to give up on the current attempt (ReferenceAgent.chatWithRetry, or
    // the harness's own task-wall timeout in RunBenchSupport.runBounded) just before/as it calls
    // abortInflight(reason); read and cleared by streamSse() below for the journal's abort_reason.
    // A single field, not one per in-flight request: in practice a proxy has at most one in-flight
    // exchange at a time (sessions process turns sequentially), so the two writers this can ever
    // have (a generic "interrupted" from chatWithRetry and a more specific one from runBounded) are
    // racing over the SAME attempt's own reason - a diagnostic label, not correctness-affecting.
    private volatile String pendingAbortReason;
    public static final double CHARS_PER_TOKEN = 3.6;   // the usage ESTIMATE of an abandoned stream
    // the agent's chat model sets this per session (ReferenceAgent) so every journaled request can
    // be attributed to its session unambiguously, even under concurrent (parallel-wave) sessions -
    // unlike a time-window heuristic. Stripped before forwarding upstream: oMLX has no use for it.
    public static final String SESSION_HEADER = "X-Ace-Session-Id";
    // java.net.http.HttpRequest.Builder.header() rejects these - lower-case, matched case-insensitively
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade",
            SESSION_HEADER.toLowerCase(Locale.ROOT));

    public RecordingProxy(BenchProperties props) { this.props = props; }

    /** start on a free port; the journal is APPENDED across restarts (one proxy per phase/task, like the Python original) */
    public synchronized String start(Path journal, Long tokenBudget, String tag) throws Exception {
        return start(journal, tokenBudget, tag, SamplerOverrides.NONE);
    }

    public synchronized String start(Path journal, Long tokenBudget, String tag, SamplerOverrides overrides) throws Exception {
        stop();
        this.journal = journal; this.tokenBudget = tokenBudget; this.tag = tag;
        this.overrides = overrides == null ? SamplerOverrides.NONE : overrides;
        this.stopping = false;
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
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Closes whatever is CURRENTLY relaying through this proxy - WITHOUT stopping the listening
     *  server, unlike stop(). For when the agent gives up on one retry attempt but will
     *  immediately start another through this SAME proxy (same session, same token budget,
     *  same journal): the agent's own StreamingHandle.cancel() only closes ITS side of the
     *  agent<->proxy hop; it does nothing for this proxy's OWN blocking read from upstream
     *  (R12 - confirmed live via jstack: RecordingProxy relay threads sat blocked in
     *  HttpResponseInputStream.read() for 750s+, one per abandoned attempt, each still
     *  faithfully waiting on oMLX with nothing telling either side the agent had moved on -
     *  exactly the still-"Generating..." entries piling up on oMLX's own dashboard). Closing the
     *  tracked upstream body here is what actually reaches that hop. */
    public void abortInflight() { abortInflight(null); }

    /** same as abortInflight(), plus names WHY for the journal's abort_reason (see pendingAbortReason). */
    public void abortInflight(final String reason) {
        if (reason != null) pendingAbortReason = reason;
        for (HttpResponse<InputStream> r : inflight) {
            try { r.body().close(); } catch (Exception e) { log.debug("closing an in-flight stream on abortInflight(): {}", e.toString()); }
        }
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
        // HttpServer.stop() does not shut down a custom executor supplied via setExecutor() - left
        // running, its cached threads (and their per-thread kqueue/pipe fds) linger for up to 60s
        // idle each, compounding across the many proxies a long benchmark run cycles through
        if (serverExecutor != null) { serverExecutor.shutdownNow(); serverExecutor = null; }
    }

    //todo refactor this
    private void forward(final com.sun.net.httpserver.HttpExchange x) throws Exception {
        byte[] body = x.getRequestBody().readAllBytes();
        final String path = x.getRequestURI().getPath();
        final ObjectNode rec = json.createObjectNode();
        rec.put("ts", Instant.now().toString());
        rec.put("method", x.getRequestMethod());
        rec.put("path", path);
        final String sessionId = x.getRequestHeaders().getFirst(SESSION_HEADER);
        if (sessionId != null) rec.put("session_id", sessionId);
        JsonNode req = null;
        // a chat request that fails to parse here silently skips budget enforcement and sampler
        // pinning below (isChat requires a parsed object) - worth knowing about, not just "not chat"
        try { if (body.length > 0) req = json.readTree(body); }
        catch (Exception e) { log.warn("could not parse request body as JSON for {} {}: {}", x.getRequestMethod(), path, e.toString()); }
        final boolean isChat = path.startsWith("/v1/chat/completions") && req != null && req.isObject();
        if (isChat) {
            final ObjectNode r = (ObjectNode) req;
            final Double temp = overrides.temperature() != null ? overrides.temperature() : props.temperature();
            if (temp != null) r.put("temperature", temp);
            final Double topP = overrides.topP() != null ? overrides.topP() : props.topP();
            if (topP != null) r.put("top_p", topP);
            if (props.seed() != null) r.put("seed", props.seed());
            r.put("max_tokens", overrides.maxOutputTokens() != null ? overrides.maxOutputTokens() : props.maxOutputTokens());
            // top_k/repetition_penalty/reasoning_effort: no operator-wide ace.* default for any of
            // these - omitted entirely (letting oMLX/the model use its own default) unless a run
            // explicitly set one. reasoning_effort overrides whatever ReferenceAgent's own
            // per-phase-kind DEFAULT_REASONING already put here - this proxy runs last, so it wins.
            if (overrides.topK() != null) r.put("top_k", overrides.topK());
            if (overrides.repetitionPenalty() != null) r.put("repetition_penalty", overrides.repetitionPenalty());
            if (overrides.reasoningEffort() != null) r.put("reasoning_effort", overrides.reasoningEffort());
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
                .method(x.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body.length > 0 ? body : new byte[0]))
                // bounds only the wait for oMLX to start responding (headers), not a streamed body
                // read afterward - without this, an upstream hang here blocks this handler thread
                // forever, same class of bug as ReferenceAgent's chatModel timeout (see its comment).
                // Kept under the agent's own 300s so this proxy fails first with a clean 502 the
                // agent's retry logic can classify, instead of both sides timing out independently.
                .timeout(java.time.Duration.ofSeconds(290));
        // java.net.http.HttpRequest.Builder throws IllegalArgumentException on these - the JDK
        // manages them itself. Matched case-insensitively: com.sun.net.httpserver.Headers presents
        // "Content-Length" (title case), not the "Content-length" this used to compare against, so
        // that exclusion silently never matched either. Missing "Upgrade" made EVERY real client
        // request (the agent's own JDK HttpClient sends it for h2c negotiation) throw before ever
        // reaching upstream - the exchange closed with no response, read by the client as a
        // connection failure and retried into the ground (R9).
        x.getRequestHeaders().forEach((k, v) -> {
            if (!RESTRICTED_HEADERS.contains(k.toLowerCase(Locale.ROOT))) ub.header(k, v.get(0));
        });
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
        // read-level timing (not per SSE event - one read() can carry several/partial lines):
        // diagnoses whether a stalled/aborted stream was a steady trickle or one long silent gap,
        // which the assembled content alone can't distinguish (R13 - this is exactly the question
        // that came up live: 4000+ chars of reasoning had accumulated by the time a 90s-idle abort
        // fired, which looks like "still working" from the final content, but only firstByteMs/
        // maxReadGapMs/reads actually show whether it was arriving continuously or not)
        int reads = 0; long lastReadAt = t0, maxReadGapMs = 0, firstByteMs = -1;
        try (InputStream in = up.body(); OutputStream out = x.getResponseBody()) {
            final byte[] buf = new byte[8192]; int n; StringBuilder line = new StringBuilder();
            while ((n = in.read(buf)) >= 0) {
                final long now = System.nanoTime();
                maxReadGapMs = Math.max(maxReadGapMs, (now - lastReadAt) / 1_000_000);
                if (firstByteMs < 0) firstByteMs = (now - t0) / 1_000_000;
                lastReadAt = now; reads++;
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
        // the abort itself always looks like a "gap" (read() never returns again) - that tail is
        // not evidence of anything upstream was doing, so it is deliberately excluded from
        // maxReadGapMs; what matters here is the largest gap BETWEEN chunks that did arrive
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
        rec.put("reads", reads).put("max_read_gap_ms", maxReadGapMs);
        if (firstByteMs >= 0) rec.put("first_byte_ms", firstByteMs);
        if (clientAborted) {
            rec.put("client_aborted", true);
            // consumed, not just read: the NEXT attempt through this same proxy must not inherit a
            // stale reason from an earlier, unrelated abort
            final String reason = pendingAbortReason;
            pendingAbortReason = null;
            if (reason != null) rec.put("abort_reason", reason);
        }
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
