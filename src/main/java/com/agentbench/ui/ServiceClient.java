package com.agentbench.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin typed client for agentbench-trading-service's JSON API.
 * Blocking calls are fine here: it is a local, single-operator service.
 */
@Component
public class ServiceClient {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final RestClient http;
    private final String baseUrl;

    public ServiceClient(ServiceProperties properties) {
        this.baseUrl = properties.baseUrl();
        this.http = RestClient.builder().baseUrl(baseUrl).build();
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
     * Paths come from our own listing of safe names (no encoding needed for [A-Za-z0-9._/-]).
     */
    public String runFileText(String runId, String relativePath) {
        if (relativePath.matches(".*[?#].*") || relativePath.contains("..")) {
            throw new IllegalArgumentException("unsafe file path: " + relativePath);
        }
        return http.get().uri("/runs/" + runId + "/files/" + relativePath).retrieve().body(String.class);
    }

    /** Human-readable text for anything the client throws. */
    public String errorText(Exception e) {
        if (e instanceof RestClientResponseException responseException) {
            try {
                tools.jackson.databind.JsonNode body = JSON.readTree(responseException.getResponseBodyAsString());
                if (body.has("detail")) {
                    tools.jackson.databind.JsonNode detail = body.get("detail");
                    if (detail.isTextual()) {
                        return detail.asText();
                    }
                    if (detail.isArray()) { // pydantic validation errors
                        StringBuilder sb = new StringBuilder();
                        for (tools.jackson.databind.JsonNode err : detail) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(err.path("loc").path(1).asText(err.path("loc").asText("?")))
                                    .append(": ").append(err.path("msg").asText("invalid"));
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
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static Optional<String> blankToNone(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
