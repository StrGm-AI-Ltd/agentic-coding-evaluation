package com.trading.account;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Every decimal leaves as a STRING; every timestamp as UTC millis; status codes per the frozen contract. */
@RestController
public class AccountController {
    private final AccountService service;
    AccountController(AccountService service) { this.service = service; }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        AccountEntity a = service.create(body == null ? null : String.valueOf(body.getOrDefault("currency", "USD")));
        return ResponseEntity.status(201).body(accountJson(a));
    }

    @GetMapping("/accounts/{id}")
    public Map<String, Object> get(@PathVariable String id) { return accountJson(service.get(uuid(id))); }

    @PostMapping("/accounts/{id}/deposits")
    public Map<String, Object> deposit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("amount");
        if (raw == null) throw new ApiException(400, "amount required");
        AccountEntity a = service.deposit(uuid(id), Money.amount(String.valueOf(raw)));
        return Map.of("availableBalance", Money.str(a.availableBalance));
    }

    @GetMapping("/accounts/{id}/holdings")
    public Map<String, Object> holdings(@PathVariable String id, @RequestParam(required = false) String asOf) {
        UUID aid = uuid(id); service.get(aid);
        Instant applied = asOf == null ? Money.nowMillis() : Money.parseInstant(asOf);
        Map<String, String> h = new LinkedHashMap<>();
        service.holdingsAt(aid, applied).forEach((sym, q) -> h.put(sym, Money.qty(q)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holdings", h); out.put("asOfApplied", Money.iso(applied));
        return out;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> order(@RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        for (String k : new String[] {"accountId", "symbol", "side", "quantity", "limitPrice"})
            if (body.get(k) == null) throw new ApiException(400, k + " required");
        AccountService.Placed p = service.placeOrder(uuid(String.valueOf(body.get("accountId"))), String.valueOf(body.get("symbol")),
                String.valueOf(body.get("side")), Money.amount(String.valueOf(body.get("quantity"))), Money.amount(String.valueOf(body.get("limitPrice"))), idemKey);
        return ResponseEntity.status(p.replay() ? 200 : 201).body(orderJson(p.order()));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) { return orderJson(service.getOrder(uuid(id))); }

    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        OrderEntity o = service.cancel(uuid(id));
        return Map.of("orderId", o.orderId.toString(), "status", o.status);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.status(400).body(Map.of("error", "bad request"));
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { throw new ApiException(404, "no such id"); }
    }

    private static Map<String, Object> accountJson(AccountEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.accountId.toString()); m.put("currency", a.currency);
        m.put("availableBalance", Money.str(a.availableBalance)); m.put("reservedBalance", Money.str(a.reservedBalance));
        return m;
    }

    private static Map<String, Object> orderJson(OrderEntity o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.orderId.toString()); m.put("status", o.status); m.put("side", o.side); m.put("symbol", o.symbol);
        m.put("quantity", Money.qty(o.quantity)); m.put("limitPrice", Money.str(o.limitPrice));
        if (o.executedAt != null) m.put("executedAt", Money.iso(o.executedAt));
        return m;
    }
}
