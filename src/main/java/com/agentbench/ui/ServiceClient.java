package com.agentbench.ui;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin typed client for agentbench-trading-service's JSON API. Blocking calls are
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

    /** Production constructor: Boot's auto-configured RestClient.Builder is injected. */
    public ServiceClient(ServiceProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.baseUrl = properties.baseUrl();
        this.http = build(properties, builder);
    }

    private static RestClient build(ServiceProperties properties, RestClient.Builder builder) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone() // never mutate the injected prototype (mock/test seam stays intact)
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        http = build(properties, RestClient.builder());
    }

    public String baseUrl() {
        return baseUrl;
    }

    public List<Api.Run> runs(String task, String model, String mode, String valid, String poolable) {
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

    public Api.Run run(String runId) {
        return http.get().uri("/api/runs/{id}", runId).retrieve().body(Api.Run.class);
    }

    public Api.Job rescore(String runId) {
        return http.post().uri("/api/runs/{id}/rescore", runId).retrieve().body(Api.Job.class);
    }

    public Api.GroupResponse groups() {
        return http.get().uri("/api/groups").retrieve().body(Api.GroupResponse.class);
    }

    public Api.CompareResponse compare(Api.CompareRequest request) {
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

    public Api.Job job(long jobId) {
        return http.get().uri("/api/jobs/{id}", jobId).retrieve().body(Api.Job.class);
    }

    public Api.Job cancel(long jobId) {
        return http.post().uri("/api/jobs/{id}/cancel", jobId).retrieve().body(Api.Job.class);
    }

    /** POST /api/jobs — enqueue a run; spec maps 1:1 onto the service's RunSpec (snake_case flags). */
    public Api.Job enqueueJob(Map<String, Object> spec, int priority) {
        return http.post().uri("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("spec", spec, "priority", priority))
                .retrieve()
                .body(Api.Job.class);
    }

    public Api.Job requeue(long jobId) {
        return http.post().uri("/api/jobs/{id}/requeue", jobId).retrieve().body(Api.Job.class);
    }

    public Api.Job setPriority(long jobId, int priority) {
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

    public Api.Experiment experiment(long experimentId) {
        return http.get().uri("/api/experiments/{id}", experimentId).retrieve().body(Api.Experiment.class);
    }

    /** POST /api/experiments — enqueue a whole experiment; params per template (experiments.py TEMPLATE_PARAMS). */
    public Api.Experiment createExperiment(String name, String template, Map<String, Object> params, int k) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
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
     * GET /runs/{runId}/files/{path} — raw file content from the run's results dir.
     * Each path segment is URL-encoded (J-1), so spaces, %, and non-ASCII are safe.
     */
    public String runFileText(String runId, String relativePath) {
        if (relativePath.matches(".*[?#].*") || relativePath.contains("..")) {
            throw new IllegalArgumentException("unsafe file path: " + relativePath);
        }
        URI uri = URI.create(baseUrl + "/runs/" + encodeSegment(runId) + "/files/"
                + encodePath(relativePath));
        return http.get().uri(uri).retrieve().body(String.class);
    }

    static String encodePath(String relativePath) {
        StringBuilder sb = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(encodeSegment(segment));
        }
        return sb.toString();
    }

    static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** Human-readable text for anything the client throws. */
    public String errorText(Exception e) {
        if (e instanceof RestClientResponseException responseException) {
            try {
                JsonNode body = Json.MAPPER.readTree(responseException.getResponseBodyAsString());
                if (body.has("detail")) {
                    JsonNode detail = body.get("detail");
                    if (detail.isTextual()) {
                        return detail.asText();
                    }
                    if (detail.isArray()) { // pydantic validation errors; the field name is the LAST loc segment
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode err : detail) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            JsonNode loc = err.path("loc");
                            String field = loc.isArray() && !loc.isEmpty()
                                    ? Fmt.textOr(loc.get(loc.size() - 1), "?") : "?";
                            sb.append(field).append(": ").append(Fmt.textOr(err.path("msg"), "invalid"));
                        }
                        return sb.toString();
                    }
                }
            } catch (Exception ignored) {
                // fall through to the raw body
            }
            return responseException.getStatusCode() + ": " + responseException.getResponseBodyAsString();
        }
        if (e instanceof ResourceAccessException) {
            return "Cannot reach agentbench-service at " + baseUrl
                    + " — is it running? (cd agentbench-trading-service/service && uv run agentbench-service)";
        }
        if (e instanceof RestClientException restClientException) {
            // a failed conversion carries the interesting text in its cause chain
            // (e.g. "Error while extracting response … — JsonMappingException: …"), not the wrapper
            StringBuilder text = new StringBuilder(restClientException.getMessage() != null
                    ? restClientException.getMessage() : restClientException.toString());
            Throwable cause = restClientException.getCause();
            while (cause != null) {
                text.append(" — ").append(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                cause = cause.getCause();
            }
            return text.toString();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static Optional<String> blankToNone(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
