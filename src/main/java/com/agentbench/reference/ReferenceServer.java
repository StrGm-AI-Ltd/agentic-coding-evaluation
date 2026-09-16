package com.agentbench.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;

/** Port of oracle/reference_server.py's entrypoint: the reference trading server on a plain JDK
 *  HttpServer (the Spring TradingController serves the same contract inside the app; this is the
 *  standalone twin used to CALIBRATE the black-box suite, with BUGS injection). */
public final class ReferenceServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TradingService svc;
    private HttpServer server;

    public ReferenceServer(TradingService svc) { this.svc = svc; }
    public ReferenceServer(java.util.Set<String> bugs) { this(new TradingService(bugs)); }
    public ReferenceServer() { this(new TradingService()); }

    public int start(int port) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port == 0 ? 0 : port), 0);
        server.createContext("/", x -> { try { route(x); } catch (Exception e) { try { x.close(); } catch (Exception ignore) {} } });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void route(HttpExchange x) throws Exception {
        String p = x.getRequestURI().getPath();
        String q = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
        String raw = new String(x.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        String method = x.getRequestMethod();
        try {
            if (p.equals("/health")) { reply(x, 200, Map.of("status", "UP")); return; }
            String[] parts = p.replaceAll("^/|/$", "").split("/");
            if (method.equals("GET") && parts[0].equals("prices") && parts.length == 2) {
                Map<String, Object> r = svc.price(parts[1]);
                reply(x, r == null ? 404 : 200, r == null ? Map.of("error", "unknown symbol") : r);
            } else if (method.equals("POST") && p.equals("/accounts")) {
                reply(x, 201, svc.createAccount(body.path("currency").asText(null)));
            } else if (method.equals("GET") && parts[0].equals("accounts") && parts.length >= 2) {
                Map<String, Object> a = svc.account(parts[1]);
                if (a == null) { reply(x, 404, Map.of("error", "no account")); return; }
                if (parts.length == 2) { reply(x, 200, a); return; }
                if (parts[2].equals("holdings")) {
                    String asOf = param(q, "asOf");
                    Instant as = asOf == null ? null : Instant.parse(asOf);
                    reply(x, 200, svc.holdings(parts[1], as));
                }
            } else if (method.equals("POST") && parts.length == 3 && parts[2].equals("deposits")) {
                Map<String, Object> r = svc.deposit(parts[1], body.path("amount").asText("0"));
                if (r == null) reply(x, 404, Map.of("error", "no account"));
                else if (r.containsKey("error")) reply(x, 400, r);
                else reply(x, 200, r);
            } else if (method.equals("POST") && p.equals("/orders")) {
                TradingService.OrderOutcome o = svc.order(body.path("accountId").asText(null), body.path("symbol").asText(null),
                        body.path("side").asText(null), body.path("quantity").asText("0"), body.path("limitPrice").asText("0"),
                        x.getRequestHeaders().getFirst("Idempotency-Key"));
                reply(x, o.status(), o.body());
            } else if (method.equals("GET") && parts[0].equals("orders") && parts.length == 2) {
                Map<String, Object> o = svc.order(parts[1]);
                reply(x, o == null ? 404 : 200, o == null ? Map.of("error", "no order") : o);
            } else if (method.equals("POST") && parts.length == 3 && parts[2].equals("cancel")) {
                TradingService.CancelOutcome c = svc.cancel(parts[1]);
                reply(x, c.status(), c.body());
            } else reply(x, 404, Map.of("error", "not found"));
        } catch (IllegalArgumentException e) {
            reply(x, 400, Map.of("error", "bad request"));
        }
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        return null;
    }

    private static void reply(HttpExchange x, int status, Object body) throws Exception {
        byte[] out = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        try (OutputStream os = x.getResponseBody()) { os.write(out); os.flush(); }
    }
}
