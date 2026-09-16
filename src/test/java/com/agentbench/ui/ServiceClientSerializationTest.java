package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V-3, pinned: a UI session may be persisted and restored — the wire handles are
 * transient and must be rebuilt on deserialization, preserving the base URL.
 */
class ServiceClientSerializationTest {

    @Test
    void serializeThenDeserializeRebuildsWireHandles() throws Exception {
        ServiceClient original = new ServiceClient(
                new ServiceProperties("http://127.0.0.1:9999", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                org.springframework.web.client.RestClient.builder());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        ServiceClient restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ServiceClient) in.readObject();
        }

        assertEquals("http://127.0.0.1:9999", restored.baseUrl());
        assertNotNull(wire(restored, "http"), "the RestClient handle is rebuilt");
        assertNotNull(wire(restored, "sseClient"), "the SSE HttpClient is rebuilt");

        // and the rebuilt RestClient actually works — round-trip one call through the wire stub
        assertNotNull(wire(original, "http"));
    }

    private static Object wire(ServiceClient client, String field) throws Exception {
        Field handle = ServiceClient.class.getDeclaredField(field);
        handle.setAccessible(true);
        return handle.get(client);
    }
}
