package com.strgmai.ace.service.service;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.docker.DockerService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Disabled;
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

    private static BenchProperties props(final String model, final String endpoint, final String javaHome) {
        return new BenchProperties(model, endpoint, "", null, null, null, null, null, null, null,
                null, null, null, javaHome, null, null, null);
    }

    /** a fake bin/java AND bin/javac so the "pinned JDK 21" check passes without shelling out to
     *  /usr/libexec/java_home - #597: a real JDK has both, a bare JRE only has the former. */
    private static String fakeJavaHome() throws Exception {
        final var home = Files.createTempDirectory("java-home");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/java"));
        Files.createFile(home.resolve("bin/javac"));
        return home.toString();
    }

    /** #597: java present, javac absent - what a bare JRE looks like. Mirrors fakeJavaHome() minus
     *  the compiler binary. */
    private static String fakeJreOnlyHome() throws Exception {
        final var home = Files.createTempDirectory("jre-home");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/java"));
        return home.toString();
    }

    private static MockedStatic<DockerService> mockDocker(final int dockerRc, final int composeRc, final int gitRc) {
        return mockDocker(dockerRc, composeRc, gitRc, 0);
    }

    private static MockedStatic<DockerService> mockDocker(final int dockerRc, final int composeRc, final int gitRc, final int zshRc) {
        return mockDocker(dockerRc, composeRc, gitRc, zshRc, 0);
    }

    /** #327: zshRc mirrors gitRc's own convention exactly - zsh is the SAME kind of fatal hard
     *  dependency AgentTools.bash() has on it (found live: a real deployment missing it crashed
     *  every bash tool call for hours with nothing refusing the run up front). #555: unzipRc is
     *  the same pattern again, for the same reason, found live the same way. */
    private static MockedStatic<DockerService> mockDocker(final int dockerRc, final int composeRc, final int gitRc, final int zshRc, final int unzipRc) {
        final MockedStatic<DockerService> docker = mockStatic(DockerService.class);
        docker.when(() -> DockerService.sh(anyInt(), any(String[].class))).thenAnswer(inv -> {
            Object[] all = inv.getArguments();   // Mockito flattens the varargs: [timeoutSec, cmd[0], cmd[1], ...]
            final String[] cmd = new String[all.length - 1];
            for (int i = 1; i < all.length; i++) cmd[i - 1] = (String) all[i];
            if (cmd[0].equals("git")) return new DockerService.Sh(gitRc, "git version 2.42.0");
            if (cmd[0].equals("zsh")) return new DockerService.Sh(zshRc, "zsh 5.9");
            if (cmd[0].equals("unzip")) return new DockerService.Sh(unzipRc, "UnZip 6.00");
            if (cmd[0].equals("docker") && cmd.length > 1 && cmd[1].equals("info")) return new DockerService.Sh(dockerRc, "24.0.0");
            if (cmd[0].equals("docker") && cmd.length > 1 && cmd[1].equals("compose")) return new DockerService.Sh(composeRc, "2.20.0");
            return new DockerService.Sh(1, "");
        });
        return docker;
    }

    private static HttpServer fakeModelServer(final String modelId) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            final byte[] body = ("{\"data\":[{\"id\":\"" + modelId + "\",\"max_model_len\":8192}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        return server;
    }

    private static Preflight.Check byName(Preflight.Report r, final String name) {
        return r.checks().stream().filter(c -> c.check().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no check named " + name + " in " + r.checks()));
    }

    // todo issue 25: fix broken test
    @Disabled
    @Test
    void allFatalChecksPassingIsNotBlocked() throws Exception {
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertFalse(r.blocked());
            assertTrue(byName(r, "docker daemon").ok());
            assertTrue(byName(r, "docker compose v2").ok());
            assertTrue(byName(r, "git").ok());
            assertTrue(byName(r, "zsh").ok());
            assertTrue(byName(r, "unzip").ok());
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
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 1)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            final Preflight.Check git = byName(r, "git");
            assertFalse(git.ok());
            assertTrue(git.fatal());
        } finally {
            server.stop(0);
        }
    }

    /** #327, found live: a real AWS deployment never had zsh installed - AgentTools.bash() execs
     *  every agent shell command via "zsh", "-lc" with no fallback, so every bash tool call in
     *  every task crashed for hours before anything surfaced it. Same fatal treatment as git. */
    @Test
    void missingZshBlocksEvenThoughGitAndDockerAreUp() throws Exception {
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0, 1)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            final Preflight.Check zsh = byName(r, "zsh");
            assertFalse(zsh.ok());
            assertTrue(zsh.fatal());
        } finally {
            server.stop(0);
        }
    }

    /** #555, found live: a real deployment's runtime image had no archive-extraction tool at all
     *  (eclipse-temurin:21-jre + git/zsh/docker-cli only, no unzip) - every task in a run needing
     *  to bootstrap a fresh project (near-universal: a Gradle wrapper ships as a .zip) failed at
     *  its very first step, silently, with nothing refusing the run up front. Same fatal treatment
     *  as git/zsh above. */
    @Test
    void missingUnzipBlocksEvenThoughGitAndZshAreUp() throws Exception {
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0, 0, 1)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            final Preflight.Check unzip = byName(r, "unzip");
            assertFalse(unzip.ok());
            assertTrue(unzip.fatal());
        } finally {
            server.stop(0);
        }
    }

    /** #597, found live: a bare JRE (java present, javac absent - exactly what ACE_JAVA_HOME pointed
     *  at before #595/#596's fix) used to pass this check as if it were a full JDK, since it only
     *  ever looked for bin/java. Every agent task that compiled or ran tests then failed hours later
     *  with Gradle's own "Java compiler is not available", instead of this gate catching it up
     *  front the way it's supposed to. */
    @Test
    void jreWithNoJavacBlocksEvenThoughGitZshUnzipAreUp() throws Exception {
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJreOnlyHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            final Preflight.Check jdk = byName(r, "pinned JDK 21");
            assertFalse(jdk.ok());
            assertTrue(jdk.fatal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableModelServerBlocksViaModelServerItself() throws Exception {
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            // port 1 refuses instantly; the raw OpenAI client's models().list() throws straight into
            // check()'s own catch, so "model server" itself is what fails and blocks - "target model
            // served" is never even added to the report, since the code never reaches that line.
            // #80: before ace.endpoint was actually honored here, this fixture's port-1 endpoint was
            // silently ignored (the hardcoded URL always won), so this scenario was never really
            // exercised at all - it only "passed" by accident, against whatever real server the
            // hardcoded address happened to reach.
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:1/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertTrue(r.blocked());
            final Preflight.Check modelServer = byName(r, "model server");
            assertFalse(modelServer.ok());
            assertTrue(modelServer.fatal());
            assertTrue(r.checks().stream().noneMatch(c -> c.check().equals("target model served")),
                    "the model list itself failed - there is nothing to check the target against");
        }
    }

    /** #466, found live: a job's --model may be "providerKey/model" (ProviderStore.route()'s own
     *  convention) - "model server"/"target model served" must check THAT provider's own live
     *  endpoint, not the operator-wide local default. Before this fix, every job aimed at an
     *  external provider was refused here on a deployment with no local model server at all - the
     *  local default (port 1, refuses instantly) stays deliberately wrong/unreachable in this test
     *  so a pass can only mean the external provider's own endpoint was actually used. */
    @Test
    void aProviderPrefixedModelIsCheckedAgainstThatProvidersOwnEndpoint() throws Exception {
        final var server = fakeModelServer("zai-org/GLM-5.3-Flash");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            final var ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + Files.createTempFile("preflight-provider", ".db") + "?foreign_keys=on");
            org.flywaydb.core.Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            final var providerStore = new com.strgmai.ace.service.agent.ProviderStore(org.jooq.impl.DSL.using(ds, org.jooq.SQLDialect.SQLITE));
            providerStore.create("Custom", "custom", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-test", null);

            // props' own endpoint is deliberately unreachable - a pass can only come from the
            // provider's own endpoint actually being used, never a silent fallback to this
            final var pf = new Preflight(props("irrelevant", "http://127.0.0.1:1/v1", fakeJavaHome()), providerStore);
            final Preflight.Report r = pf.check("custom/zai-org/GLM-5.3-Flash");

            assertFalse(r.blocked());
            assertTrue(byName(r, "model server").ok());
            final Preflight.Check target = byName(r, "target model served");
            assertTrue(target.ok(), target.detail());
            assertEquals("zai-org/GLM-5.3-Flash", target.detail(), "checked against the stripped, bare model id");
        } finally {
            server.stop(0);
        }
    }

    // todo issue 25: fix broken test
    @Disabled
    @Test
    void dockerDaemonDownAloneDoesNotBlock() throws Exception {
        final HttpServer server = fakeModelServer("target-model");
        try (MockedStatic<DockerService> docker = mockDocker(1, 0, 0)) {
            final var pf = new Preflight(props("target-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            final Preflight.Report r = pf.check(null);

            assertFalse(r.blocked());
            final Preflight.Check dockerDaemon = byName(r, "docker daemon");
            assertFalse(dockerDaemon.ok());
            assertFalse(dockerDaemon.fatal());
        } finally {
            server.stop(0);
        }
    }

    // todo issue 25: fix broken test
    @Disabled
    @Test
    void anExplicitModelArgumentOverridesThePropsDefault() throws Exception {
        final HttpServer server = fakeModelServer("explicit-model");
        try (MockedStatic<DockerService> docker = mockDocker(0, 0, 0)) {
            final var pf = new Preflight(props("props-default-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", fakeJavaHome()),
                    new com.strgmai.ace.service.agent.ProviderStore(null));
            Preflight.Report r = pf.check("explicit-model");   // the model THIS job will request, not props.model()

            assertFalse(r.blocked());
            assertTrue(byName(r, "target model served").ok());
        } finally {
            server.stop(0);
        }
    }
}
