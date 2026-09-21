package com.trading.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** The public edge on :8080. /prices -> market-service, everything else -> account-service;
 *  /health is 200 only when both upstreams answer 200. Bodies and status codes pass through untouched. */
@RestController
public class ProxyController {
    private final RestClient client = RestClient.builder().build();
    private final String accountUrl;
    private final String marketUrl;

    ProxyController(@Value("${gateway.account-url}") String accountUrl, @Value("${gateway.market-url}") String marketUrl) {
        this.accountUrl = accountUrl; this.marketUrl = marketUrl;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean account = up(accountUrl), market = up(marketUrl);
        Map<String, Object> body = Map.of("status", account && market ? "UP" : "DOWN", "account-service", account, "market-service", market);
        return ResponseEntity.status(account && market ? 200 : 503).body(body);
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> forward(HttpServletRequest req, @RequestBody(required = false) byte[] body) {
        String path = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        String target = (path.startsWith("/prices") ? marketUrl : accountUrl) + path;
        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(req.getMethod())).uri(target)
                .headers(h -> {
                    String ct = req.getHeader(HttpHeaders.CONTENT_TYPE); if (ct != null) h.set(HttpHeaders.CONTENT_TYPE, ct);
                    String idem = req.getHeader("Idempotency-Key"); if (idem != null) h.set("Idempotency-Key", idem);
                    h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                });
        if (body != null && body.length > 0) spec = (RestClient.RequestBodySpec) spec.body(body);
        ResponseEntity<byte[]> r = spec.retrieve().onStatus(s -> true, (rq, rs) -> {}).toEntity(byte[].class);
        HttpHeaders out = new HttpHeaders();
        MediaType ct = r.getHeaders().getContentType(); if (ct != null) out.setContentType(ct);
        return ResponseEntity.status(r.getStatusCode()).headers(out).body(r.getBody());
    }

    private boolean up(String base) {
        try { return client.get().uri(base + "/health").retrieve().onStatus(s -> true, (rq, rs) -> {}).toBodilessEntity().getStatusCode().value() == 200; }
        catch (RuntimeException e) { return false; }
    }
}
