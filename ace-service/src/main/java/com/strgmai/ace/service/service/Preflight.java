package com.strgmai.ace.service.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.models.Model;
import com.strgmai.ace.service.agent.ProviderStore;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Port of runner/preflight.py: prerequisite check before a run spends its budget. Every probe is
 *  timeout-guarded (a hung daemon must never hang the benchmark). FATAL = the run cannot start;
 *  degraded = dependent oracle checks will SKIP, not fail. */
@Service
public class Preflight {
    private final BenchProperties props;
    private final ProviderStore providerStore;

    public Preflight(BenchProperties props, ProviderStore providerStore) {
        this.props = props;
        this.providerStore = providerStore;
    }

    public record Check(String check, boolean ok, String detail, boolean fatal) {}
    public record Report(List<Check> checks, boolean blocked) {}

    public Report check(final String model) {
        final List<Check> out = new ArrayList<>();
        final DockerService.Sh d = DockerService.sh(20, "docker", "info", "--format", "{{.ServerVersion}}");
        add(out, "docker daemon", d.rc() == 0, first(d.out()), false);
        final DockerService.Sh c = DockerService.sh(20, "docker", "compose", "version", "--short");
        add(out, "docker compose v2", c.rc() == 0, first(c.out()), false);
        add(out, "git", DockerService.sh(15, "git", "--version").rc() == 0, "git", true);
        try {
            Class.forName("com.strgmai.ace.service.agent.ReferenceAgent");
            add(out, "reference agent", true, BenchProperties.HARNESS_VERSION, true);
        } catch (Exception e) { add(out, "reference agent", false, String.valueOf(e), true); }

        // the JDK the RUN will pin - the one thing that broke silently before (R4 C-2)
        String jh = props.javaHome() == null || props.javaHome().isBlank()
                ? DockerService.sh(20, "/usr/libexec/java_home", "-v", "21").out().strip() : props.javaHome();
        final boolean jdkOk = !jh.isBlank() && Files.isRegularFile(Path.of(jh, "bin", "java"));
        add(out, "pinned JDK 21", jdkOk, jh.isBlank() ? "none found: set ace.java-home or $ACE_JAVA_HOME" : jh, true);

        // #466: a job's own model may be "providerKey/model" (the same registered-prefix
        // convention ProviderStore.route() uses for the main session) - check
        // reachability/membership against THAT provider's own live endpoint, not always the
        // operator-wide local default. Without this, every job aimed at an external provider is
        // refused here on any deployment with no local model server reachable at all - this check
        // predates external-provider routing entirely and never knew to look elsewhere. A bare
        // model (the overwhelming majority of runs) routes local, unchanged from before.
        //
        // Deliberately NOT ProviderStore.route(): its own Upstream.baseUrl() is stripped of "/v1"
        // for RecordingProxy's opposite convention (its relay path already supplies "/v1") - the
        // OpenAI SDK client below needs base_url AS STORED (WITH "/v1"), the same way
        // BenchController.providerModels() already uses it. Found live: reusing route() here 404'd
        // every external check (".../v1" got stripped, then the SDK's own "/models" suffix landed
        // on the bare host with no "/v1" at all).
        final String raw = model == null || model.isBlank() ? props.model() : model;
        final int slash = raw.indexOf('/');
        final var provider = slash < 0 ? null : providerStore.byKey(raw.substring(0, slash));
        final String baseUrl = provider == null ? props.upstreamBase() + "/v1" : provider.baseUrl();
        final String apiKey = provider == null ? props.apiKey() : provider.apiKey();
        final String target = provider == null ? raw : raw.substring(slash + 1);
        try {
            final var models = modelClient(baseUrl, apiKey).models().list().data().stream().map(Model::id).sorted().toList();
            add(out, "model server", true, models.size() + " models", true);
            add(out, "target model served", models.contains(target), models.contains(target) ? target : target + " not in " + models.stream().limit(3).toList(), true);
        } catch (Exception e) {
            add(out, "model server", false, String.valueOf(e).substring(0, Math.min(70, String.valueOf(e).length())), true);
        }
        final boolean blocked = out.stream().anyMatch(x -> x.fatal() && !x.ok());
        return new Report(out, blocked);
    }

    // todo issue 25: define a service for available models
    // #80: was hardcoded to the default endpoint/key regardless of ace.endpoint/ace.api-key
    static OpenAIClient modelClient(final BenchProperties props) {
        return modelClient(props.upstreamBase() + "/v1", props.apiKey());
    }

    static OpenAIClient modelClient(final String baseUrl, final String apiKey) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? "none" : apiKey)
                .build();
    }

    static String first(String s) { String t = s.strip(); return t.isEmpty() ? "no output" : t.split("\n")[0]; }

    static void add(final List<Check> out, final String name, final boolean ok, final String detail, final boolean fatal) {
        out.add(new Check(name, ok, detail, fatal));
    }
}
