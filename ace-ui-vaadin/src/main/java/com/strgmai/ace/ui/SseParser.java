package com.strgmai.ace.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental server-sent-events parser for the service's format
 * (api.py sse()): "event: &lt;type&gt;\ndata: &lt;json&gt;\n\n". Feed it lines from
 * the stream; it returns a completed {@link SseEvent} at each blank line.
 */
public final class SseParser {

    private String eventType;
    private final StringBuilder data = new StringBuilder();

    /** Feeds one line; returns the completed event at block end, null otherwise. */
    public SseEvent accept(final String line) {
        if (line == null) {
            return null;
        }
        if (line.isBlank()) { // blank line terminates the block
            return emit();
        }
        if (line.startsWith(":")) { // SSE comment/keep-alive
            return null;
        }
        if (line.startsWith("event:")) {
            eventType = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(line.substring("data:".length()).stripLeading());
        }
        return null;
    }

    private SseEvent emit() {
        final var payload = data.toString();
        final var type = eventType;
        eventType = null;
        data.setLength(0);
        if (payload.isBlank()) {
            return null;
        }
        try {
            return new SseEvent(type, Json.MAPPER.readTree(payload));
        } catch (final Exception e) {
            return null; // malformed data is skipped, not fatal to the stream
        }
    }

    /** Splits a whole SSE payload (for tests and one-shot reads). */
    static List<SseEvent> parseAll(final String sseText) {
        // returned as List<SseEvent>; empty-diamond under var would infer <Object>
        final List<SseEvent> events = new ArrayList<>();
        final var parser = new SseParser();
        for (final var line : sseText.split("\n", -1)) {
            final var event = parser.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }
}
