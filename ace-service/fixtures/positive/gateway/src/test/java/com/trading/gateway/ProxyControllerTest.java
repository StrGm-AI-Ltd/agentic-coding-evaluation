package com.trading.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Fake upstreams on loopback: proves routing, status pass-through and the aggregated /health. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyControllerTest {
    static HttpServer account, market;
    @Autowired TestRestTemplate http;

    @BeforeAll static void upstreams() throws Exception {
        account = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        account.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            byte[] b = (p.equals("/health") ? "{\"status\":\"UP\"}" : p.equals("/accounts/x") ? "{\"error\":\"no such account\"}" : "{\"accountId\":\"a1\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(p.equals("/accounts/x") ? 404 : p.equals("/accounts") ? 201 : 200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        market = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        market.createContext("/", ex -> {
            byte[] b = "{\"symbol\":\"AAPL\",\"price\":\"10.00\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        account.start(); market.start();
    }
    @AfterAll static void down() { account.stop(0); market.stop(0); }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("gateway.account-url", () -> "http://127.0.0.1:" + account.getAddress().getPort());
        r.add("gateway.market-url", () -> "http://127.0.0.1:" + market.getAddress().getPort());
    }

    @Test void routesPricesToMarketAndRestToAccount() {
        assertEquals("10.00", http.getForEntity("/prices/AAPL", Map.class).getBody().get("price"));
        ResponseEntity<Map> created = http.postForEntity("/accounts", Map.of("currency", "USD"), Map.class);
        assertEquals(201, created.getStatusCode().value());
        assertEquals("a1", created.getBody().get("accountId"));
    }
    @Test void upstreamStatusPassesThrough() {
        assertEquals(404, http.getForEntity("/accounts/x", Map.class).getStatusCode().value());
    }
    @Test void healthAggregatesUpstreams() {
        assertEquals(200, http.getForEntity("/health", Map.class).getStatusCode().value());
    }
}
