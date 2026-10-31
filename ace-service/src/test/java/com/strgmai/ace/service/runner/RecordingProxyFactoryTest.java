package com.strgmai.ace.service.runner;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** abortAllInflight() is the fix for a gap found live on T17: WorkerService's cancel-watch thread
 *  used to only interrupt the WAITING thread (blocked in RunBenchSupport.runBounded()'s own
 *  future.get()) - the WORKING thread actually streaming the model's response could still be stuck
 *  in a non-interruptible read, so the job's own status flipped to "paused" while real work kept
 *  going underneath it. These tests prove the fix the same end-to-end way RecordingProxyTest proves
 *  RecordingProxy.abortInflight() itself: a real stuck upstream, a real client blocked on it. */
class RecordingProxyFactoryTest {

    private static BenchProperties props(final String upstreamBase) {
        return new BenchProperties(null, upstreamBase, null, null, null, null, 100,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void abortAllInflightUnblocksAClientStuckOnACurrentlyActiveSession() throws Exception {
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
                Thread.sleep(5_000);   // never reached: abortAllInflight() closes this side first
            } catch (Exception expectedOnceAborted) { /* the client side of this exchange just closed */ }
        });
        upstream.start();
        final var journal = Files.createTempFile("proxy-factory-test", ".jsonl");
        final var factory = new RecordingProxyFactory(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        final var session = factory.start(journal, 1000L, null);
        try {
            final var client = HttpClient.newHttpClient();
            final var request = HttpRequest.newBuilder(URI.create(session.base() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[],\"stream\":true}"))
                    .build();
            final CompletableFuture<Void> clientDone = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try { client.send(request, HttpResponse.BodyHandlers.ofString()); } catch (Exception ignore) {}
                finally { clientDone.complete(null); }
            });

            assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS), "the upstream must have started streaming");
            Thread.sleep(200);   // give the proxy's own relay loop time to actually read that first chunk
            factory.abortAllInflight("paused");
            clientDone.get(5, TimeUnit.SECONDS);   // times out (fails the test) if abortAllInflight() didn't reach it
        } finally {
            session.stop().run();
            upstream.stop(0);
        }
    }

    @Test
    void abortAllInflightNeverCallsAStoppedSessionsOwnAbort() throws Exception {
        final HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.start();
        final var journal = Files.createTempFile("proxy-factory-test", ".jsonl");
        final var factory = new RecordingProxyFactory(props("http://127.0.0.1:" + upstream.getAddress().getPort()));
        final var session = factory.start(journal, 1000L, null);
        session.stop().run();
        upstream.stop(0);

        // nothing left to abort - must be a harmless no-op, not an error against an already-torn-down proxy
        assertDoesNotThrow(() -> factory.abortAllInflight("cancelled"));
    }

    @Test
    void abortAllInflightWithNoActiveSessionsIsANoOp() {
        final var factory = new RecordingProxyFactory(props("http://127.0.0.1:1"));
        assertDoesNotThrow(() -> factory.abortAllInflight("cancelled"));
    }
}
