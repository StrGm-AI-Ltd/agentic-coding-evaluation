package com.strgmai.ace.service.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void badRequestReturns400WithTheExceptionMessage() {
        final var res = handler.badRequest(new IllegalArgumentException("task must be set"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("task must be set", res.getBody().get("detail"));
    }

    @Test
    void notFoundReturns404WithTheExceptionMessage() {
        final var res = handler.notFound(new NoSuchElementException("job abc123 does not exist"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("job abc123 does not exist", res.getBody().get("detail"));
    }

    @Test
    void conflictReturns409WithTheExceptionMessage() {
        final var res = handler.conflict(new IllegalStateException("job abc123 is already succeeded"));
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertEquals("job abc123 is already succeeded", res.getBody().get("detail"));
    }

    /** #86: unlike the three handlers above (deliberately user-facing messages), an uncaught
     *  exception's own message must never reach the client - it could carry internal detail (class
     *  names, paths, SQL) that has nothing to do with the request. Full detail stays in the server
     *  log (logged separately, not asserted here); the client gets a generic message instead. */
    @Test
    void serverErrorReturns500WithAGenericMessageNotTheRawException() {
        final HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/api/jobs");
        final var sensitive = new RuntimeException("connection refused to jdbc:sqlite:/Users/andrei_andriiuk/.cache/agentbench/ace-service.db");

        final var res = handler.serverError(sensitive, req);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        final var detail = String.valueOf(res.getBody().get("detail"));
        assertFalse(detail.contains("jdbc:sqlite"), "the raw exception message must never reach the client: " + detail);
        assertFalse(detail.contains("/Users/"), "no internal path must leak into the client-visible message: " + detail);
    }
}
