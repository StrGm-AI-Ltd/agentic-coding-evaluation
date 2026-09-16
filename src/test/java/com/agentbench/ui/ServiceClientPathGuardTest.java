package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceClientPathGuardTest {

    private static final ServiceClient CLIENT = new ServiceClient(
            new ServiceProperties("http://127.0.0.1:8765", Duration.ofSeconds(1), Duration.ofSeconds(1)),
            RestClient.builder());

    @Test
    void rejectsTraversalAndQueryFragment() {
        for (String path : new String[]{"../x", "a/../b", "f?x", "f#y"}) {
            // the guard throws before any HTTP traffic, so no server is needed
            assertThrows(IllegalArgumentException.class, () -> CLIENT.runFileText("r1", path), path);
        }
    }

    @Test
    void encodesEachSegmentKeepingSlashes() {
        assertEquals("packs/T1.json", ServiceClient.encodePath("packs/T1.json"));
        assertEquals("a%20b/c.txt", ServiceClient.encodePath("a b/c.txt"), "spaces become %20, not +");
        assertEquals("100%25.json", ServiceClient.encodePath("100%.json"), "percent is escaped");
        assertEquals("t%C3%A9.json", ServiceClient.encodePath("té.json"), "non-ASCII is escaped");
        assertEquals("x.json", ServiceClient.encodePath("/x.json"), "a leading slash yields no empty segment");
    }
}
