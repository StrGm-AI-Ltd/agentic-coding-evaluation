package com.strgmai.ace.ui;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Thin typed client for ace-service's JSON API. Blocking calls are
 * fine here: it is a local, single-operator service — but they are always bounded
 * by the configured connect/read timeouts (S-1), never unbounded.
 *
 * Serializable (V-3): the wire handle is transient and rebuilt on deserialization
 * from the properties, so a Vaadin UI session can be persisted and restored without
 * leaving views holding a dead/null client.
 */
@Component
public class ServiceClient implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ServiceProperties properties;
    private final String baseUrl;
    private transient RestClient http;
    private transient HttpClient sseClient;

    /** Production constructor: Boot's auto-configured RestClient.Builder is injected. */
    public ServiceClient(final ServiceProperties properties, final RestClient.Builder builder) {
        this.properties = properties;
        this.baseUrl = properties.baseUrl();
        this.http = build(properties, builder);
        this.sseClient = HttpClient.newBuilder() // no read timeout: SSE is long-lived
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    private static RestClient build(final ServiceProperties properties, final RestClient.Builder builder) {
        final var jdk = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        final var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone() // never mutate the injected prototype (mock/test seam stays intact)
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .messageConverters(cs -> {
                    // C-9: one shared mapper for the whole app — replace the framework-default
                    // Jackson converter so the lenient null-to-primitive setting applies here.
                    cs.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter);
                    cs.add(new JacksonJsonHttpMessageConverter(Json.MAPPER)); // appended, so text/plain converters keep winning for String targets
                })
                .build();
    }

    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        http = build(properties, RestClient.builder());
        sseClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public List<Api.Run> runs(final String task, final String model, final String mode, final String valid, final String poolable) {
        return http.get()
                .uri(b -> b.path("/api/runs")
                        .queryParamIfPresent("task", blankToNone(task))
                        .queryParamIfPresent("model", blankToNone(model))
                        .queryParamIfPresent("mode", blankToNone(mode))
                        .queryParamIfPresent("valid", blankToNone(valid))
                        .queryParamIfPresent("poolable", blankToNone(poolable))
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Api.Run>>() {
                });
    }

    public Api.Run run(final String runId) {
        return http.get().uri("/api/runs/{id}", runId).retrieve().body(Api.Run.class);
    }

    public Api.Job rescore(final String runId) {
        return http.post().uri("/api/runs/{id}/rescore", runId).retrieve().body(Api.Job.class);
    }

    /** Whatever the model server currently serves, per GET /api/models — an empty list (not an
     *  error) when the model server is unreachable, the same fallback the endpoint itself uses. */
    public List<String> models() {
        return http.get().uri("/api/models").retrieve().body(new ParameterizedTypeReference<List<String>>() {
        });
    }

    public Api.GroupResponse groups() {
        return http.get().uri("/api/groups").retrieve().body(Api.GroupResponse.class);
    }

    public Api.CompareResponse compare(final Api.CompareRequest request) {
        return http.post().uri("/api/compare")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Api.CompareResponse.class);
    }

    public Api.ImportResult importAll() {
        return http.post().uri("/api/import").retrieve().body(Api.ImportResult.class);
    }

    public List<Api.Job> jobs() {
        return http.get().uri("/api/jobs").retrieve().body(new ParameterizedTypeReference<List<Api.Job>>() {
        });
    }

    public Api.Job job(final String jobId) {
        return http.get().uri("/api/jobs/{id}", jobId).retrieve().body(Api.Job.class);
    }

    public Api.Job cancel(final String jobId) {
        return http.post().uri("/api/jobs/{id}/cancel", jobId).retrieve().body(Api.Job.class);
    }

    /** POST /api/jobs — enqueue a run; spec maps 1:1 onto the service's RunSpec (snake_case flags). */
    public Api.Job enqueueJob(final Map<String, Object> spec, final int priority) {
        return http.post().uri("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("spec", spec, "priority", priority))
                .retrieve()
                .body(Api.Job.class);
    }

    public Api.Job requeue(final String jobId) {
        return http.post().uri("/api/jobs/{id}/requeue", jobId).retrieve().body(Api.Job.class);
    }

    public Api.Job setPriority(final String jobId, final int priority) {
        return http.patch().uri("/api/jobs/{id}", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("priority", priority))
                .retrieve()
                .body(Api.Job.class);
    }

    public List<Api.Experiment> experiments() {
        return http.get().uri("/api/experiments").retrieve().body(new ParameterizedTypeReference<List<Api.Experiment>>() {
        });
    }

    public Api.Experiment experiment(final String experimentId) {
        return http.get().uri("/api/experiments/{id}", experimentId).retrieve().body(Api.Experiment.class);
    }

    /** POST /api/experiments — enqueue a whole experiment; params per template (experiments.py TEMPLATE_PARAMS). */
    public Api.Experiment createExperiment(final String name, final String template, final Map<String, Object> params, final int k) {
        final var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("template", template);
        body.put("params", params);
        body.put("k", k);
        return http.post().uri("/api/experiments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Api.Experiment.class);
    }

    public Api.PreflightState preflight() {
        return http.get().uri("/api/preflight").retrieve().body(Api.PreflightState.class);
    }

    public Api.PreflightState startPreflight() {
        return http.post().uri("/api/preflight").retrieve().body(Api.PreflightState.class);
    }

    /**
     * GET /api/runs/{runId}/files/{path} — raw file content from the run's results dir.
     * Each path segment is URL-encoded (J-1), so spaces, %, and non-ASCII are safe.
     */
    public String runFileText(final String runId, final String relativePath) {
        if (relativePath.matches(".*[?#].*") || relativePath.contains("..")) {
            throw new IllegalArgumentException("unsafe file path: " + relativePath);
        }
        final var uri = URI.create(baseUrl + "/api/runs/" + encodeSegment(runId) + "/files/"
                + encodePath(relativePath));
        return http.get().uri(uri).retrieve().body(String.class);
    }

    static String encodePath(final String relativePath) {
        final var sb = new StringBuilder();
        for (final var segment : relativePath.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(encodeSegment(segment));
        }
        return sb.toString();
    }

    static String encodeSegment(final String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /**
     * Blocking consumption of the service's SSE stream (/api/jobs/{id}/events — the Java
     * port of the endpoint the Jinja page's EventSource uses). Each parsed event is handed to {@code onEvent};
     * returns when the server ends the stream (terminal job). Throwing from {@code onEvent}
     * aborts the connection — that is the page's detach path.
     */
    public void streamJobEvents(final String jobId, final Consumer<SseEvent> onEvent) throws IOException, InterruptedException {
        final var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/jobs/" + jobId + "/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        final var response = sseClient.send(request, HttpResponse.BodyHandlers.ofLines());
        try (var lines = response.body()) {
            final var parser = new SseParser();
            lines.forEach(line -> {
                final var event = parser.accept(line);
                if (event != null) {
                    onEvent.accept(event); // a RuntimeException here aborts the stream/connection
                }
            });
        }
    }

    /** Human-readable text for anything the client throws. */
    public String errorText(final Exception e) {
        if (e instanceof RestClientResponseException responseException) {
            try {
                final var body = Json.MAPPER.readTree(responseException.getResponseBodyAsString());
                if (body.has("detail")) {
                    final var detail = body.get("detail");
                    if (detail.isTextual()) {
                        return detail.asText();
                    }
                    if (detail.isArray()) { // pydantic validation errors; the field name is the LAST loc segment
                        final var sb = new StringBuilder();
                        for (final var err : detail) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            final var loc = err.path("loc");
                            final var field = loc.isArray() && !loc.isEmpty()
                                    ? Fmt.textOr(loc.get(loc.size() - 1), "?") : "?";
                            sb.append(field).append(": ").append(Fmt.textOr(err.path("msg"), "invalid"));
                        }
                        return sb.toString();
                    }
                }
            } catch (final Exception ignored) {
                // fall through to the raw body
            }
            return responseException.getStatusCode() + ": " + responseException.getResponseBodyAsString();
        }
        if (e instanceof ResourceAccessException) {
            return "Cannot reach ace-service at " + baseUrl
                    + " — is it running? (from the repo root: ./gradlew :ace-service:bootRun)";
        }
        if (e instanceof RestClientException restClientException) {
            // a failed conversion carries the interesting text in its cause chain
            // (e.g. "Error while extracting response … — JsonMappingException: …"), not the wrapper
            final var text = new StringBuilder(restClientException.getMessage() != null
                    ? restClientException.getMessage() : restClientException.toString());
            var cause = restClientException.getCause();   // reassigned below - not final
            while (cause != null) {
                text.append(" — ").append(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                cause = cause.getCause();
            }
            return text.toString();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static Optional<String> blankToNone(final String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
