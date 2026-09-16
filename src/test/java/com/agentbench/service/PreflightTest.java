package com.agentbench.service;

import com.agentbench.config.BenchProperties;
import com.agentbench.docker.DockerService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

/** Unit tests for Preflight.check()'s individual probes. docker/git are mocked via DockerService's
 *  static sh() (so the result never depends on whether Docker Desktop happens to be running on this
 *  machine); the model server is a real loopback HttpServer for the "reachable" cases and a
 *  guaranteed-refused port for the "unreachable" case - no external services required either way. */
class PreflightTest {

    private static BenchProperties props(String model, String endpoint, String javaHome) {
        return new BenchProperties(model, endpoint, "", null, null, null, null, null, null, null,
                null, null, null, null, null, javaHome);
    }

    /** a fake bin/java so the "pinned JDK 21" check passes without shelling out to /usr/libexec/java_home */
    private static String fakeJavaHome() throws Exception {
        Path home = Files.createTempDirectory("java-home");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/java"));
        return home.toString();
    }

    private static MockedStatic<DockerService> mockDocker(int dockerRc, int composeRc, int gitRc) {
        MockedStatic<DockerService> docker = mockStatic(DockerService.class);
        docker.when(() -> DockerService.sh(anyInt(), any(String[].class))).thenAnswer(inv -> {
            Object[] all = inv.getArguments();   // Mockito flattens the varargs: [timeoutSec, cmd[0], cmd[1], ...]
            String[] cmd = new String[all.length - 1];
            for (int i = 1; i < all.length; i++) cmd[i - 1] = (String) all[i];
            if (cmd[0].equals("git")) return new DockerService.Sh(gitRc, "git version 2.42.0");
            if (cmd[0].equals("docker") && cmd.length > 1 && cmd[1].equals("info")) return new DockerService.Sh(dockerRc, "24.0.0");
            if (cmd[0].equals("docker") && cmd.length > 1 && cmd[1].equals("compose")) return new DockerService.Sh(composeRc, "2.20.0");
            return new DockerService.Sh(1, "");
        });
        return docker;
    }

    private static HttpServer fakeModelServer(String modelId) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            byte[] body = ("{\"data\":[{\"id\":\"" + modelId + "\",\"max_model_len\":8192}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        return server;
    }

    private static Preflight.Check byName(Preflight.Report r, String name) {
        return r.checks().stream().filter(c -> c.check().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name + " in " + r.checks()));
    }

    @Test
    void allFatalChecksPassingIsNotBlocked() throws Exception {
        HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            Preflight pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()));
            Preflight.Report r = pf.check(null);

            assertFalse(r.blocked());
            assertTrue(byName(r, "docker daemon").ok());
            assertTrue(byName(r, "docker compose v2").ok());
            assertTrue(byName(r, "git").ok());
            assertTrue(byName(r, "reference agent").ok());
            assertTrue(byName(r, "pinned JDK 21").ok());
            assertTrue(byName(r, "model server").ok());
            assertTrue(byName(r, "target model served").ok());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingGitBlocksEvenThoughDockerIsUp() throws Exception {
        HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 1)) {
            Preflight pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()));
            Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            Preflight.Check git = byName(r, "git");
            assertFalse(git.ok());
            assertTrue(git.fatal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableModelServerBlocksViaTargetModelServedNotViaModelServerItself() throws Exception {
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            // port 1 refuses instantly; ContextProbe.models() swallows the failure and returns {},
            // so "model server" itself reads ok (0 models) and it's "target model served" that fails
            Preflight pf = new Preflight(props("target-model", "http://127.0.0.1:1/v1", fakeJavaHome()));
            Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            assertTrue(byName(r, "model server").ok());
            Preflight.Check target = byName(r, "target model served");
            assertFalse(target.ok());
            assertTrue(target.fatal());
        }
    }

    @Test
    void dockerDaemonDownAloneDoesNotBlock() throws Exception {
        HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(1, 0, 0)) {
            Preflight pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()));
            Preflight.Report r = pf.check(null);

            assertFalse(r.blocked());
            Preflight.Check dockerDaemon = byName(r, "docker daemon");
            assertFalse(dockerDaemon.ok());
            assertFalse(dockerDaemon.fatal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anExplicitModelArgumentOverridesThePropsDefault() throws Exception {
        HttpServer server = fakeModelServer("explicit-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            Preflight pf = new Preflight(props("props-default-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()));
            Preflight.Report r = pf.check("explicit-model");   // the model THIS job will request, not props.model()

            assertFalse(r.blocked());
            assertTrue(byName(r, "target model served").ok());
        } finally {
            server.stop(0);
        }
    }
}
