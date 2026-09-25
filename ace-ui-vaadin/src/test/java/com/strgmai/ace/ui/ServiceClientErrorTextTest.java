package com.strgmai.ace.ui;

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

    private static RestClientResponseException response(final int status, final String body) {
        return new RestClientResponseException("boom", status, "status", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void fastApiTextDetailExtracted() {
        assertEquals("run x is not imported",
                CLIENT.errorText(response(404, "{\"detail\": \"run x is not imported\"}")));
    }

    // #108: the pydantic array-detail shape these two tests used to cover (a FastAPI 422
    // validation-error response) was the pre-port Python service's contract - ApiExceptionHandler
    // (the current Spring backend) always returns "detail" as a plain string, and never a 422 at
    // all (IllegalArgumentException maps to 400). Removed along with the dead parsing branch in
    // ServiceClient.errorText() that only ever existed to handle this now-unreachable shape.

    @Test
    void nonJsonBodyFallsBackToStatusAndRawBody() {
        final var text = CLIENT.errorText(response(404, "{oops"));
        assertTrue(text.contains("404"));
        assertTrue(text.contains("{oops"));
    }

    @Test
    void detailObjectFallsBack() {
        final var text = CLIENT.errorText(response(500, "{\"detail\": {\"nested\": true}}"));
        assertTrue(text.contains("500"));
    }

    @Test
    void resourceAccessExceptionGivesFriendlyHint() {
        final var text = CLIENT.errorText(new ResourceAccessException("refused"));
        assertTrue(text.contains("127.0.0.1:8765"), "should name the configured base URL");
        assertTrue(text.contains("./gradlew :ace-service:bootRun"), "should name the start command");
    }

    /** A failed response conversion hides the real cause in the exception chain; surface it. */
    @Test
    void decodingFailureSurfacesRootCauseChain() {
        final var e =
                new org.springframework.web.client.RestClientException(
                        "Error while extracting response for type [com.strgmai.ace.ui.Api$ImportResult] "
                                + "and content type [application/json]",
                        new RuntimeException("Unexpected token (STRING), expected VALUE_INT"));
        final var text = CLIENT.errorText(e);
        assertTrue(text.contains("Error while extracting response"), "the wrapper message stays visible");
        assertTrue(text.contains("Unexpected token (STRING), expected VALUE_INT"),
                "the root cause is appended so the operator can diagnose the wire mismatch");
    }

    @Test
    void genericExceptionUsesMessage() {
        assertEquals("boom", CLIENT.errorText(new IllegalStateException("boom")));
        assertEquals("java.lang.IllegalStateException", // toString(): no ": null" suffix without a message
                CLIENT.errorText(new IllegalStateException()));
    }
}
