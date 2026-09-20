package com.agentbench.ui;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One shared, immutable Jackson 3 mapper for the whole app (C-9). */
public final class Json {

    /**
     * The service occasionally answers with {@code null} for a boolean it cannot decide
     * (seen live: {@code "poolable": null} on a run in GET /api/runs). With the Jackson
     * default ({@code FAIL_ON_NULL_FOR_PRIMITIVES}), that would fail the whole list and
     * blank the page; instead null maps to the primitive default ({@code false} —
     * "flag not set"), which matches how every consumer treats the flags.
     */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private Json() {
    }
}
