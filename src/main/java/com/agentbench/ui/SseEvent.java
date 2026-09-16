package com.agentbench.ui;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** One parsed server-sent event: the "event:" type and the "data:" JSON payload. */
public record SseEvent(String type, JsonNode data) {

    /** The payload's own "type" field, matching the server's event dicts. */
    public String payloadType() {
        return data != null && data.hasNonNull("type") ? data.get("type").asText() : type;
    }
}
