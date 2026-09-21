package com.strgmai.ace.service.oracle.checks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** F6 (OpenAPI conformance) failed on every account-path call against this port's own frozen
 *  contract, which is written entirely in flow-style YAML (`/accounts/{id}: {get: {responses:
 *  {"200": {...}}}}}`) - parseOpenApiPaths only understood block style, so every method/status
 *  came back empty and matchTemplate never matched. This pins the fix. */
class BlackboxScenariosF6Test {

    @Test
    void parsesTheRealFrozenContractsFlowStylePaths() throws Exception {
        String yaml;
        try (var in = getClass().getResourceAsStream("/contract/openapi.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final Map<String, Map<String, java.util.List<String>>> paths = BlackboxScenarios.parseOpenApiPaths(yaml);
        assertTrue(paths.containsKey("/accounts/{id}"), "paths parsed: " + paths.keySet());
        assertEquals(java.util.List.of("200", "404"), paths.get("/accounts/{id}").get("get"));
        assertEquals(java.util.List.of("200", "400", "404"), paths.get("/accounts/{id}/holdings").get("get"));
        assertEquals(java.util.List.of("201", "400"), paths.get("/accounts").get("post"));

        assertEquals("/accounts/{id}", BlackboxScenarios.matchTemplate(paths, "GET", "/accounts/66e46cae-1a58-42ca-8bba-35e9f7f75143"));
        assertEquals("/accounts/{id}/holdings", BlackboxScenarios.matchTemplate(paths, "GET", "/accounts/8268d59f-8114-426f-a942-0d615bad2ad3/holdings"));
    }

    @Test
    void mixedBlockAndFlowStyleBothParse() {
        // standard OpenAPI block style has an explicit `responses:` wrapper level between the
        // method and its status codes - this is the shape real (including agent-produced) YAML
        // actually uses, and multiple methods per path must not bleed into each other.
        String yaml = """
                /flow/{id}: {get: {responses: {"200": {description: ok}, "404": {description: nf}}}}
                /block/{id}:
                  get:
                    summary: fetch it
                    responses:
                      "200":
                        description: ok
                  post:
                    responses:
                      "201":
                        description: created
                      "400":
                        description: bad
                """;
        final var paths = BlackboxScenarios.parseOpenApiPaths(yaml);
        assertEquals(java.util.List.of("200", "404"), paths.get("/flow/{id}").get("get"));
        assertEquals(java.util.List.of("200"), paths.get("/block/{id}").get("get"));
        assertEquals(java.util.List.of("201", "400"), paths.get("/block/{id}").get("post"));
    }

    @Test
    void parsesThePositiveControlFixturesOwnStandardBlockStyleSpec() throws Exception {
        final String yaml = java.nio.file.Files.readString(java.nio.file.Path.of("fixtures/positive/openapi.yaml"));
        final var paths = BlackboxScenarios.parseOpenApiPaths(yaml);
        assertEquals(java.util.List.of("200", "503"), paths.get("/health").get("get"));
        assertEquals(java.util.List.of("201", "400"), paths.get("/accounts").get("post"));
        assertEquals("/accounts/{id}", BlackboxScenarios.matchTemplate(paths, "GET", "/accounts/66e46cae-1a58-42ca-8bba-35e9f7f75143"));
    }
}
