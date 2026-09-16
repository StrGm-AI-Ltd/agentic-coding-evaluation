package com.agentbench.service;

import com.agentbench.config.BenchProperties;
import com.agentbench.docker.DockerService;
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

    public Preflight(BenchProperties props) { this.props = props; }

    public record Check(String check, boolean ok, String detail, boolean fatal) {}
    public record Report(List<Check> checks, boolean blocked) {}

    public Report check(String model) {
        List<Check> out = new ArrayList<>();
        DockerService.Sh d = DockerService.sh(20, "docker", "info", "--format", "{{.ServerVersion}}");
        add(out, "docker daemon", d.rc() == 0, first(d.out()), false);
        DockerService.Sh c = DockerService.sh(20, "docker", "compose", "version", "--short");
        add(out, "docker compose v2", c.rc() == 0, first(c.out()), false);
        add(out, "git", DockerService.sh(15, "git", "--version").rc() == 0, "git", true);
        try {
            Class.forName("com.agentbench.agent.ReferenceAgent");
            add(out, "reference agent", true, BenchProperties.HARNESS_VERSION, true);
        } catch (Exception e) { add(out, "reference agent", false, String.valueOf(e), true); }

        // the JDK the RUN will pin - the one thing that broke silently before (R4 C-2)
        String jh = props.javaHome() == null || props.javaHome().isBlank()
                ? DockerService.sh(20, "/usr/libexec/java_home", "-v", "21").out().strip() : props.javaHome();
        boolean jdkOk = !jh.isBlank() && Files.isRegularFile(Path.of(jh, "bin", "java"));
        add(out, "pinned JDK 21", jdkOk, jh.isBlank() ? "none found: set agentbench.java-home or $AB_JAVA_HOME" : jh, true);

        try {   // the oMLX/OpenAI-compatible server + the model an actual run will request
            var models = new com.agentbench.runner.ContextProbe().models(props.endpoint(), props.apiKey() == null ? "" : props.apiKey());
            add(out, "model server", true, models.size() + " models", true);
            String target = model == null || model.isBlank() ? props.model() : model;
            add(out, "target model served", models.containsKey(target), models.containsKey(target) ? target : target + " not in " + models.keySet().stream().limit(3).toList(), true);
        } catch (Exception e) {
            add(out, "model server", false, String.valueOf(e).substring(0, Math.min(70, String.valueOf(e).length())), true);
        }
        boolean blocked = out.stream().anyMatch(x -> x.fatal() && !x.ok());
        return new Report(out, blocked);
    }

    static String first(String s) { String t = s.strip(); return t.isEmpty() ? "no output" : t.split("\n")[0]; }

    static void add(List<Check> out, String name, boolean ok, String detail, boolean fatal) {
        out.add(new Check(name, ok, detail, fatal));
    }
}
