package com.agentbench.ui;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** One parsed server-sent event: the "event:" type and the "data:" JSON payload. */
public record SseEvent(String type, JsonNode data) {

    /**
     * The payload's own "type" field, matching the server's event dicts. Never null:
     * an event with neither payload type nor event: type yields the empty string,
     * which consumers' default branches ignore — a String switch on null would NPE
     * on the SSE consumer thread.
     */
    public String payloadType() {
        if (data != null && data.hasNonNull("type")) {
            return Fmt.textOr(data.get("type"), type);
        }
        return type != null ? type : "";
    }
}
