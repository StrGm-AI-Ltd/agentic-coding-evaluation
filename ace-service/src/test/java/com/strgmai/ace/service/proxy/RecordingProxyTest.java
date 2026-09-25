package com.strgmai.ace.service.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strgmai.ace.service.config.BenchProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RecordingProxy attributes each journaled request to its session via the agent's
 *  X-Ace-Session-Id header (ReferenceAgent sets it per session), for the live sessions grid's
 *  per-session prefill/decode speed averages - the header must be captured into the journal and
 *  stripped before forwarding upstream (oMLX has no use for it). */
class RecordingProxyTest {

    private static BenchProperties props(final String upstreamBase) {
        return new BenchProperties(null, upstreamBase, null, null, null, null, 100,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** forward() replies to the client BEFORE its own journalRecord() write, so a test reading the
     *  journal right after client.send() returns can beat that write by a handful of microseconds -
     *  not a production bug (nothing here depends on read-your-own-write ordering), just a race this
     *  test must not have. */
    private static List<String> awaitJournalLines(final Path journal) throws Exception {
        for (int i = 0; i < 100; i++) {
            final var lines = Files.readAllLines(journal);
            if (!lines.isEmpty()) return lines;
            Thread.sleep(20);
        }
        return Files.readAllLines(journal);
    }

    @Test
    void sessionHeaderIsJournaledAndStrippedBeforeForwardingUpstream() throws Exception {
        final AtomicReference<List<String>> upstreamSawSessionHeader = new AtomicReference<>();
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            upstreamSawSessionHeader.set(ex.getRequestHeaders().get(RecordingProxy.SESSION_HEADER));
            ex.getRequestBody().readAllBytes();
            final byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);
            final var client = HttpClient.newHttpClient();
            final var request = HttpRequest.newBuilder(URI.create(proxyBase + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header(RecordingProxy.SESSION_HEADER, "test-session-123")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[],\"stream\":false}"))
                    .build();
            final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            assertTrue(upstreamSawSessionHeader.get() == null || upstreamSawSessionHeader.get().isEmpty(),
                    "the internal session header must not reach the upstream model server");

            final var lines = awaitJournalLines(journal);
            assertEquals(1, lines.size());
            final var rec = new ObjectMapper().readTree(lines.get(0));
            assertEquals("test-session-123", rec.path("session_id").asText());
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    @Test
    void noSessionHeaderMeansNoSessionIdInTheJournal() throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            final byte[] body = "{\"choices\":[],\"usage\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);
            final var client = HttpClient.newHttpClient();
            final var request = HttpRequest.newBuilder(URI.create(proxyBase + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[],\"stream\":false}"))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());

            final var rec = new ObjectMapper().readTree(awaitJournalLines(journal).get(0));
            assertFalse(rec.has("session_id"));
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    /** 2026-09-25: abortInflight(reason) (called by ReferenceAgent.chatWithRetry and the harness's
     *  own task-wall timeout in RunBenchSupport.runBounded) now names WHY it is giving up on the
     *  in-flight attempt, so the requests grid can show it instead of a bare "aborted" flag. */
    @Test
    void abortReasonIsJournaledWhenAStreamingRequestIsAborted() throws Exception {
        final CountDownLatch firstChunkSent = new CountDownLatch(1);
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (var os = ex.getResponseBody()) {
                os.write("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                firstChunkSent.countDown();
                Thread.sleep(5_000);   // never reached: the proxy closes this side first
            } catch (Exception expectedOnceTheProxyAborts) { /* the client side of this exchange just closed */ }
        });
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);
            final var client = HttpClient.newHttpClient();
            final var request = HttpRequest.newBuilder(URI.create(proxyBase + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[],\"stream\":true}"))
                    .build();
            // the proxy holds this connection open until aborted, so client.send() must not block this thread
            final CompletableFuture<Void> clientDone = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try { client.send(request, HttpResponse.BodyHandlers.ofString()); } catch (Exception ignore) {}
                finally { clientDone.complete(null); }
            });

            assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS), "the upstream must have started streaming");
            Thread.sleep(200);   // give the proxy's own relay loop time to actually read that first chunk
            proxy.abortInflight("test abort reason");
            clientDone.get(5, TimeUnit.SECONDS);

            final var rec = new ObjectMapper().readTree(awaitJournalLines(journal).get(0));
            assertTrue(rec.path("client_aborted").asBoolean(false), "the aborted request must be journaled as such");
            assertEquals("test abort reason", rec.path("abort_reason").asText());
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    /** 2026-09-25: the context probe derives a per-run max_output_tokens (a safe output cap for
     *  THIS model/hardware/context-window combination), but every request's own max_tokens field
     *  was silently using the flat, process-wide ace.max-output-tokens default instead - the
     *  derived value was computed and stored in cfg, but never reached the actual request. */
    @Test
    void perRunMaxOutputTokensOverridesTheOperatorDefaultOnTheForwardedRequest() throws Exception {
        final AtomicReference<Integer> upstreamSawMaxTokens = new AtomicReference<>();
        final HttpServer upstream = upstreamCapturingMaxTokens(upstreamSawMaxTokens);
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null,
                    new RecordingProxy.SamplerOverrides(null, null, null, null, 555, null, null));
            sendPlainChatRequest(proxyBase);
            assertEquals(555, upstreamSawMaxTokens.get(), "the run's own derived cap must reach the actual request");
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    @Test
    void noPerRunMaxOutputTokensFallsBackToTheOperatorDefault() throws Exception {
        final AtomicReference<Integer> upstreamSawMaxTokens = new AtomicReference<>();
        final HttpServer upstream = upstreamCapturingMaxTokens(upstreamSawMaxTokens);
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));   // props(...) sets maxOutputTokens=100
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);   // no per-run override (the pre-2026-09-25 call shape)
            sendPlainChatRequest(proxyBase);
            assertEquals(100, upstreamSawMaxTokens.get(), "with no run-specific cap, the operator-wide default must still apply");
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    private static HttpServer upstreamCapturingMaxTokens(final AtomicReference<Integer> sawMaxTokens) throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            final var body = new ObjectMapper().readTree(ex.getRequestBody().readAllBytes());
            sawMaxTokens.set(body.path("max_tokens").asInt());
            final byte[] resp = "{\"choices\":[],\"usage\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (var os = ex.getResponseBody()) { os.write(resp); }
        });
        return upstream;
    }

    /** 2026-09-25 (#72): top_k/repetition_penalty/reasoning_effort have no operator-wide ace.*
     *  default (unlike temperature/top_p/max_tokens) - each must be omitted entirely from the
     *  request when the run didn't set one, and present with the run's exact value when it did. */
    @Test
    void samplerOverridesReachTheForwardedRequestOnlyWhenSet() throws Exception {
        final AtomicReference<JsonNode> sawBody = new AtomicReference<>();
        final HttpServer upstream = upstreamCapturingBody(sawBody);
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null,
                    new RecordingProxy.SamplerOverrides(0.7, 0.9, 40, 1.1, null, "high", null));
            sendPlainChatRequest(proxyBase);
            final var body = sawBody.get();
            assertEquals(0.7, body.path("temperature").asDouble(), 0.001);
            assertEquals(0.9, body.path("top_p").asDouble(), 0.001);
            assertEquals(40, body.path("top_k").asInt());
            assertEquals(1.1, body.path("repetition_penalty").asDouble(), 0.001);
            assertEquals("high", body.path("reasoning_effort").asText());
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    /** #92: the proxy's own upstream connect wait must scale with the run's first-token-timeout
     *  setting, plus a margin so it's never the TIGHTER of the two - it used to be a hardcoded 290s
     *  regardless of what --first-token-timeout was actually configured to, silently capping any run
     *  that raised it past ~290s. */
    @Test
    void upstreamTimeoutScalesWithTheRunsFirstTokenTimeout() {
        assertEquals(180 + 30, RecordingProxy.upstreamTimeoutSec(RecordingProxy.SamplerOverrides.NONE),
                "unset falls back to ReferenceAgent's own 180s default, plus the margin");
        assertEquals(600 + 30, RecordingProxy.upstreamTimeoutSec(
                new RecordingProxy.SamplerOverrides(null, null, null, null, null, null, 600)),
                "an operator-configured value well above the old hardcoded 290s ceiling must actually apply");
        assertEquals(60 + 30, RecordingProxy.upstreamTimeoutSec(
                new RecordingProxy.SamplerOverrides(null, null, null, null, null, null, 60)));
    }

    @Test
    void unsetTopKAndRepetitionPenaltyAndReasoningEffortAreOmittedEntirely() throws Exception {
        final AtomicReference<JsonNode> sawBody = new AtomicReference<>();
        final HttpServer upstream = upstreamCapturingBody(sawBody);
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);   // SamplerOverrides.NONE
            sendPlainChatRequest(proxyBase);
            final var body = sawBody.get();
            assertFalse(body.has("top_k"), "no run-specific top_k must mean no top_k on the wire at all");
            assertFalse(body.has("repetition_penalty"));
            assertFalse(body.has("reasoning_effort"));
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }

    private static HttpServer upstreamCapturingBody(final AtomicReference<JsonNode> sawBody) throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            sawBody.set(new ObjectMapper().readTree(ex.getRequestBody().readAllBytes()));
            final byte[] resp = "{\"choices\":[],\"usage\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (var os = ex.getResponseBody()) { os.write(resp); }
        });
        return upstream;
    }

    private static void sendPlainChatRequest(final String proxyBase) throws Exception {
        final var client = HttpClient.newHttpClient();
        final var request = HttpRequest.newBuilder(URI.create(proxyBase + "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[],\"stream\":false}"))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** #93: a chat request that isn't a parseable JSON object must be refused outright - not
     *  silently forwarded unmetered and unpinned by falling through to plain proxying. */
    @Test
    void unparseableChatRequestBodyIsRefusedNotSilentlyForwarded() throws Exception {
        final var upstreamCalled = new AtomicReference<>(false);
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", ex -> {
            upstreamCalled.set(true);
            final byte[] resp = "{\"choices\":[],\"usage\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (var os = ex.getResponseBody()) { os.write(resp); }
        });
        upstream.start();
        final var journal = Files.createTempFile("proxy-test", ".jsonl");
        final var proxy = new RecordingProxy(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        try {
            final var proxyBase = proxy.start(journal, 1000L, null);
            final var client = HttpClient.newHttpClient();
            for (String malformed : List.of("not json at all", "[]", "\"a bare string\"")) {
                final var request = HttpRequest.newBuilder(URI.create(proxyBase + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(malformed))
                        .build();
                final var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(400, response.statusCode(), "malformed body: " + malformed);
                assertTrue(response.body().contains("invalid_request"), response.body());
            }
            assertFalse(upstreamCalled.get(), "an unparseable request must never reach upstream unmetered/unpinned");
        } finally {
            proxy.stop();
            upstream.stop(0);
        }
    }
}
