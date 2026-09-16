package com.agentbench.ui;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One shared, immutable Jackson 3 mapper for the whole app (C-9). */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }
}
