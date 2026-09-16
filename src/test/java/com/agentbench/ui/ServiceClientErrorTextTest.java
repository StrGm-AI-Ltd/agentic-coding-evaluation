package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceClientErrorTextTest {

    private static final ServiceClient CLIENT = new ServiceClient(
            new ServiceProperties("http://127.0.0.1:8765", Duration.ofSeconds(1), Duration.ofSeconds(1)),
            RestClient.builder());

    private static RestClientResponseException response(int status, String body) {
        return new RestClientResponseException("boom", status, "status", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void fastApiTextDetailExtracted() {
        assertEquals("run x is not imported",
                CLIENT.errorText(response(404, "{\"detail\": \"run x is not imported\"}")));
    }

    /** The S-2 regression: nested loc ["body","spec","harness"] must show "harness", not "spec". */
    @Test
    void fastApi422NestedLocShowsFieldName() {
        String body = """
                {"detail": [
                  {"type": "literal_error", "loc": ["body", "spec", "harness"],
                   "msg": "Input should be 'ref' or 'pi'", "input": "x"},
                  {"type": "literal_error", "loc": ["body", "spec", "mode"],
                   "msg": "Input should be 'monolithic' or 'orchestrated'", "input": "y"}
                ]}""";
        assertEquals("""
                harness: Input should be 'ref' or 'pi'
                mode: Input should be 'monolithic' or 'orchestrated'""",
                CLIENT.errorText(response(422, body)));
    }

    @Test
    void fastApi422TopLevelLocStillWorks() {
        String body = """
                {"detail": [{"type": "string_type", "loc": ["body", "task"], "msg": "should be a string"}]}""";
        assertEquals("task: should be a string", CLIENT.errorText(response(422, body)));
    }

    @Test
    void nonJsonBodyFallsBackToStatusAndRawBody() {
        String text = CLIENT.errorText(response(404, "{oops"));
        assertTrue(text.contains("404"));
        assertTrue(text.contains("{oops"));
    }

    @Test
    void detailObjectFallsBack() {
        String text = CLIENT.errorText(response(500, "{\"detail\": {\"nested\": true}}"));
        assertTrue(text.contains("500"));
    }

    @Test
    void resourceAccessExceptionGivesFriendlyHint() {
        String text = CLIENT.errorText(new ResourceAccessException("refused"));
        assertTrue(text.contains("127.0.0.1:8765"), "should name the configured base URL");
        assertTrue(text.contains("uv run agentbench-service"), "should name the start command");
    }

    @Test
    void genericExceptionUsesMessage() {
        assertEquals("boom", CLIENT.errorText(new IllegalStateException("boom")));
        assertEquals("java.lang.IllegalStateException", // toString(): no ": null" suffix without a message
                CLIENT.errorText(new IllegalStateException()));
    }
}
