package com.strgmai.ace.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #236: buildCommand()/patchBaseUrl() are the pure, directly-testable pieces of the pi harness -
 *  everything else (ProcessBuilder, the real ~/.pi/agent/models.json) needs either a live pi
 *  binary or the real interactive user's own config, neither of which a unit test should touch. */
class PiAgentTest {

    @Test
    void buildCommandForAFreshSessionUsesSessionIdAndName() {
        final var cmd = PiAgent.buildCommand("do the task", "omlx/qwen3", Path.of("/r/sessions"),
                "sid-1", false, "T1", null);
        assertEquals(java.util.List.of("pi", "-p", "do the task", "--model", "omlx/qwen3",
                "--session-dir", "/r/sessions", "--no-context-files", "--session-id", "sid-1", "-n", "T1"), cmd);
    }

    @Test
    void buildCommandForAContinuationUsesSessionInsteadOfSessionIdAndName() {
        final var cmd = PiAgent.buildCommand("keep going", "omlx/qwen3", Path.of("/r/sessions"),
                "sid-1", true, "T1-continue", null);
        assertTrue(cmd.contains("--session"));
        assertEquals("sid-1", cmd.get(cmd.indexOf("--session") + 1));
        assertFalse(cmd.contains("--session-id"));
        assertFalse(cmd.contains("-n"));
    }

    @Test
    void buildCommandAppendsSystemPromptOnlyWhenSet() {
        final var withPrompt = PiAgent.buildCommand("t", "m", Path.of("/r/sessions"), "sid", false, "T1", "packs/stable.md");
        assertTrue(withPrompt.contains("--append-system-prompt"));
        assertEquals("packs/stable.md", withPrompt.get(withPrompt.indexOf("--append-system-prompt") + 1));

        final var withoutPrompt = PiAgent.buildCommand("t", "m", Path.of("/r/sessions"), "sid", false, "T1", null);
        assertFalse(withoutPrompt.contains("--append-system-prompt"));

        final var blankPrompt = PiAgent.buildCommand("t", "m", Path.of("/r/sessions"), "sid", false, "T1", "  ");
        assertFalse(blankPrompt.contains("--append-system-prompt"));
    }

    @Test
    void patchBaseUrlRewritesOnlyTheOmlxProviderBaseUrl(@TempDir final Path tmp) throws IOException {
        final Path modelsJson = tmp.resolve(".pi/agent/models.json");
        Files.createDirectories(modelsJson.getParent());
        Files.writeString(modelsJson, """
                {"providers":{"omlx":{"baseUrl":"http://old:1","apiKey":"edding345","models":[{"id":"m1"}]}}}""");

        PiAgent.patchBaseUrl(tmp, "http://127.0.0.1:54321");

        final var root = (ObjectNode) new ObjectMapper().readTree(modelsJson.toFile());
        final var omlx = root.path("providers").path("omlx");
        assertEquals("http://127.0.0.1:54321", omlx.path("baseUrl").asText());
        assertEquals("edding345", omlx.path("apiKey").asText(), "unrelated fields must be preserved");
        assertEquals("m1", omlx.path("models").get(0).path("id").asText());
    }

    @Test
    void patchBaseUrlIsANoOpWhenTheRunsOwnModelsJsonDoesNotExist(@TempDir final Path tmp) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> PiAgent.patchBaseUrl(tmp, "http://127.0.0.1:1"));
    }
}
