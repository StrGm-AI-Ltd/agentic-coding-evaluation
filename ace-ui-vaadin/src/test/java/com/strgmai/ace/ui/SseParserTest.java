package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SSE wire format the service emits (api.py sse()). */
class SseParserTest {

    @Test
    void parsesTheServiceBlocks() {
        List<SseEvent> events = SseParser.parseAll("""
                event: step_started
                data: {"type": "step_started", "step": "T1", "continuation": false, "source": "packs"}

                event: request
                data: {"type": "request", "seq": 1, "ts": "2026-09-15T23:09:05.610Z", "status": 200, "latency_sec": 53.688, "ttft_sec": 27.289, "budget_spent_completion_tokens": 557, "client_aborted": true}

                """);
        assertEquals(2, events.size());
        assertEquals("step_started", events.get(0).type());
        assertEquals("T1", events.get(0).data().path("step").asText());
        assertEquals("request", events.get(1).type());
        assertEquals(200, events.get(1).data().path("status").intValue());
        assertEquals(true, events.get(1).data().path("client_aborted").asBoolean());
    }

    @Test
    void incrementalFeedEmitsAtBlankLines() {
        SseParser parser = new SseParser();
        assertNull(parser.accept("event: status"));
        assertNull(parser.accept("data: {\"status\": \"running\", \"pid\": 1}"));
        SseEvent first = parser.accept("");
        assertEquals("status", first.type());
        assertEquals("running", first.data().path("status").asText());

        assertNull(parser.accept("event: status"));
        assertNull(parser.accept("data: {\"status\": \"succeeded\"}"));
        SseEvent second = parser.accept("");
        assertEquals("succeeded", second.data().path("status").asText(),
                "the parser resets between blocks");
    }

    @Test
    void commentsAndKeepAliveLinesIgnored() {
        SseParser parser = new SseParser();
        assertNull(parser.accept(": heartbeat"));
        assertNull(parser.accept("id: 42"));
        assertNull(parser.accept("event: request"));
        assertNull(parser.accept("data: {\"seq\": 2}"));
        SseEvent event = parser.accept("");
        assertEquals("request", event.type());
    }

    @Test
    void malformedDataIsSkippedNotFatal() {
        List<SseEvent> events = SseParser.parseAll("""
                event: broken
                data: {not json

                event: fine
                data: {"ok": true}

                """);
        assertEquals(1, events.size());
        assertEquals("fine", events.get(0).type());
        assertTrue(events.get(0).data().path("ok").asBoolean());
    }

    @Test
    void nullAndBlankLinesAreSafe() {
        SseParser parser = new SseParser();
        assertNull(parser.accept(null));
        assertNull(parser.accept(""));
    }
}
