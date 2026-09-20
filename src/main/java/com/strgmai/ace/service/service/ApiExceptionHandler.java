package com.strgmai.ace.service.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** One error shape for the whole API: {"detail": "..."} — the shape the hand-rolled 404s in
 *  BenchController already return (and the one FastAPI's HTTPException produces in the Python
 *  service), now covering the exceptions that used to fall through to Spring Boot's default 500
 *  body for what are really 400/404/409 errors.
 *
 *  {"refused": ...} on a 200 deliberately stays as it is: a refusal is the comparison's own verdict
 *  ("these runs are not comparable"), a successful answer to the question asked, not an error. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** a bad request body/parameter: RunSpec validation, unknown template, k out of range, a run id
     *  already queued, a results dir already on disk (JobQueue.enqueue) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    /** the row is not there: JobQueue.setPriority, and JdbcTemplate's own empty-result failure from
     *  queryForMap on a missing job/experiment */
    @ExceptionHandler({NoSuchElementException.class, org.springframework.dao.EmptyResultDataAccessException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    /** the row exists but is in the wrong state: cancelling a terminal job, requeueing a running one,
     *  requeueing over an existing results dir */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, e);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, Exception e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detail", e.getMessage() == null ? e.toString() : e.getMessage());
        return ResponseEntity.status(status).body(out);
    }
}
