package com.strgmai.ace.service.reference;

import com.strgmai.ace.service.reference.TradingService.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/** The reference trading server as a Spring REST controller — the frozen contract the agent's
 *  system is scored against (F1-F9), and the calibration twin for the black-box suite. */
@RestController
public class TradingController {
    private final TradingService svc;

    public TradingController(TradingService svc) { this.svc = svc; }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "UP"); }

    @GetMapping("/prices/{symbol}") public ResponseEntity<?> price(@PathVariable String symbol) {
        Map<String, Object> p = svc.price(symbol);
        return p == null ? ResponseEntity.status(404).body(Map.of("error", "unknown symbol")) : ResponseEntity.ok(p);
    }

    @PostMapping("/accounts") public ResponseEntity<?> create(@RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.status(201).body(svc.createAccount(body == null ? null : body.get("currency")));
    }

    @GetMapping("/accounts/{id}") public ResponseEntity<?> account(@PathVariable String id) {
        Map<String, Object> a = svc.account(id);
        return a == null ? ResponseEntity.status(404).body(Map.of("error", "no account")) : ResponseEntity.ok(a);
    }

    @PostMapping("/accounts/{id}/deposits")
    public ResponseEntity<?> deposit(@PathVariable String id, @RequestBody Map<String, String> body) {
        Map<String, Object> r = svc.deposit(id, body == null ? null : body.get("amount"));
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "no account"));
        if (r.containsKey("error")) return ResponseEntity.status(400).body(r);
        return ResponseEntity.ok(r);
    }

    @GetMapping("/accounts/{id}/holdings")
    public ResponseEntity<?> holdings(@PathVariable String id, @RequestParam(required = false) String asOf) {
        Instant as = null;
        if (asOf != null && !asOf.isBlank()) {
            try { as = Instant.parse(asOf.replace(' ', 'T').endsWith("Z") ? asOf.replace(' ', 'T') : asOf.replace(' ', 'T') + "Z"); }
            catch (Exception e) { return ResponseEntity.status(400).body(Map.of("error", "bad asOf")); }
        }
        Map<String, Object> h = svc.holdings(id, as);
        return h == null ? ResponseEntity.status(404).body(Map.of("error", "no account")) : ResponseEntity.ok(h);
    }

    @PostMapping("/orders")
    public ResponseEntity<?> order(@RequestBody Map<String, String> body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        OrderOutcome o = svc.order(body == null ? null : body.get("accountId"), body == null ? null : body.get("symbol"),
                body == null ? null : body.get("side"), body == null ? null : body.get("quantity"),
                body == null ? null : body.get("limitPrice"), idemKey);
        return ResponseEntity.status(o.status()).body(o.body());
    }

    @GetMapping("/orders/{id}") public ResponseEntity<?> order(@PathVariable String id) {
        Map<String, Object> o = svc.order(id);
        return o == null ? ResponseEntity.status(404).body(Map.of("error", "no order")) : ResponseEntity.ok(o);
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        CancelOutcome c = svc.cancel(id);
        return ResponseEntity.status(c.status()).body(c.body());
    }
}
