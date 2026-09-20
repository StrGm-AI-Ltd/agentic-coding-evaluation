package com.agentbench.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * V-3, pinned: a UI session may be persisted and restored — the wire handles are
 * transient and must be rebuilt on deserialization, preserving the base URL.
 */
class ServiceClientSerializationTest {

    @Test
    void serializeThenDeserializeRebuildsWireHandles() throws Exception {
        // in-process stub (like ServiceClientWireTest): the restored client must survive a
        // real round-trip, not merely be non-null
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/models", exchange -> {
            byte[] bytes = "[\"m1\",\"m2\"]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ServiceClient original = new ServiceClient(
                new ServiceProperties(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                RestClient.builder());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        ServiceClient restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ServiceClient) in.readObject();
        }

        assertEquals(baseUrl, restored.baseUrl());
        assertNotNull(wire(restored, "http"), "the RestClient handle is rebuilt");
        assertNotNull(wire(restored, "sseClient"), "the SSE HttpClient is rebuilt");

        // and the rebuilt RestClient actually works — one call through the wire stub
        assertEquals(List.of("m1", "m2"), restored.models());

        server.stop(0);
    }

    private static Object wire(ServiceClient client, String field) throws Exception {
        Field handle = ServiceClient.class.getDeclaredField(field);
        handle.setAccessible(true);
        return handle.get(client);
    }
}
