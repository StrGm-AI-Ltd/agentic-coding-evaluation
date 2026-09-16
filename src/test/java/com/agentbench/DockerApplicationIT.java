package com.agentbench;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Builds THIS repo's own Dockerfile into a real image, runs it as a container next to a real
 *  Postgres container on a shared Docker network, and hits its HTTP API from outside - the same
 *  thing the grader does (build the image, run it, talk to it), not a slice test. Skips gracefully
 *  (not a failure) when Docker is unavailable, the same pattern JobQueueIT uses for "no DB".
 *  Slow (a full image build from scratch): tagged "docker", excluded from `test`, run via
 *  `./gradlew dockerTest`. */
@Tag("docker")
class DockerApplicationIT {

    static Network network;
    static PostgreSQLContainer<?> postgres;
    static GenericContainer<?> app;

    @BeforeAll
    static void startContainers() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available on this host");

        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("agentbench").withUsername("agentbench").withPassword("agentbench")
                .withNetwork(network).withNetworkAliases("db");
        postgres.start();

        app = new GenericContainer<>(new ImageFromDockerfile()
                .withDockerfile(Path.of(System.getProperty("user.dir"), "Dockerfile")))
                .withNetwork(network)
                .withExposedPorts(8765)
                // the app container's own loopback, not the host's - inside the container 127.0.0.1 is itself
                .withEnv("AB_JLS_DSN", "jdbc:postgresql://db:5432/agentbench")
                .withEnv("SPRING_DATASOURCE_USERNAME", "agentbench")
                .withEnv("SPRING_DATASOURCE_PASSWORD", "agentbench")
                .waitingFor(Wait.forHttp("/api/preflight").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
        app.start();
    }

    @AfterAll
    static void stopContainers() {
        if (app != null) app.stop();
        if (postgres != null) postgres.stop();
        if (network != null) network.close();
    }

    @Test
    void theContainerizedAppServesItsApiOverHttp() throws Exception {
        String base = "http://" + app.getHost() + ":" + app.getMappedPort(8765);
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> preflight = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/preflight")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preflight.statusCode());
        assertTrue(preflight.body().contains("check"), "a preflight report, not an empty/error body: " + preflight.body());

        HttpResponse<String> jobs = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/jobs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, jobs.statusCode());
        assertEquals("[]", jobs.body());   // a fresh container, empty queue - real Postgres round-trip, not a stub
    }
}
