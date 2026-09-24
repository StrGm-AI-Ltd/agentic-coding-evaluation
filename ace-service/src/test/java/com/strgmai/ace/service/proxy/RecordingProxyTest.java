package com.strgmai.ace.service.proxy;

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
                null, null, null, null, null, null, null, null, null);
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
}
