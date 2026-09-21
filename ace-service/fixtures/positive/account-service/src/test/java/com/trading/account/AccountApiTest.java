package com.trading.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against the PostgreSQL the grader provides through SPRING_DATASOURCE_URL (Flyway migrates it). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTest {
    @Autowired TestRestTemplate http;

    private String account(String deposit) {
        ResponseEntity<Map> r = http.postForEntity("/accounts", Map.of("currency", "USD"), Map.class);
        assertEquals(201, r.getStatusCode().value());
        String id = (String) r.getBody().get("accountId");
        if (deposit != null) assertEquals(200, http.postForEntity("/accounts/" + id + "/deposits", Map.of("amount", deposit), Map.class).getStatusCode().value());
        return id;
    }

    private ResponseEntity<Map> order(String id, String side, String qty, HttpHeaders headers) {
        HttpEntity<Map<String, String>> e = new HttpEntity<>(Map.of("accountId", id, "symbol", "AAPL", "side", side, "quantity", qty, "limitPrice", "10.00"), headers);
        return http.postForEntity("/orders", e, Map.class);
    }

    private String balance(String id) { return (String) http.getForEntity("/accounts/" + id, Map.class).getBody().get("availableBalance"); }

    @SuppressWarnings("unchecked")
    private Map<String, String> holdings(String id, String asOf) {
        return (Map<String, String>) http.getForEntity("/accounts/" + id + "/holdings" + (asOf == null ? "" : "?asOf=" + asOf), Map.class).getBody().get("holdings");
    }

    @Test void depositsRoundHalfEvenAndReadBackAsStrings() {
        String a = account(null);
        assertEquals("0.00", http.postForEntity("/accounts/" + a + "/deposits", Map.of("amount", "0.005"), Map.class).getBody().get("availableBalance"));
        String b = account(null);
        assertEquals("0.02", http.postForEntity("/accounts/" + b + "/deposits", Map.of("amount", "0.015"), Map.class).getBody().get("availableBalance"));
        String c = account("0.10");
        assertEquals("0.30", http.postForEntity("/accounts/" + c + "/deposits", Map.of("amount", "0.20"), Map.class).getBody().get("availableBalance"));
        assertEquals(400, http.postForEntity("/accounts/" + c + "/deposits", Map.of("amount", "0"), Map.class).getStatusCode().value());
    }

    @Test void buyThenEqualSellRestoresBalanceAndHoldings() {
        String a = account("1000.00");
        assertEquals(201, order(a, "BUY", "5", null).getStatusCode().value());
        assertEquals("950.00", balance(a));
        assertEquals("5", holdings(a, null).get("AAPL"));
        assertEquals(201, order(a, "SELL", "5", null).getStatusCode().value());
        assertEquals("1000.00", balance(a));
        assertNull(holdings(a, null).get("AAPL"));
    }

    @Test void partialSellSubtractsFromHoldings() {   // an adding replay would report 7
        String a = account("1000.00");
        order(a, "BUY", "5", null);
        order(a, "SELL", "2", null);
        assertEquals("3", holdings(a, null).get("AAPL"));
        assertEquals(0, new BigDecimal("970.00").compareTo(new BigDecimal(balance(a))));
    }

    @Test void insufficientFundsAndHoldingsAre422() {
        String a = account("10.00");
        assertEquals(422, order(a, "BUY", "1000000", null).getStatusCode().value());
        assertEquals("10.00", balance(a));
        assertEquals(422, order(a, "SELL", "1", null).getStatusCode().value());
    }

    @Test void cancellingAFilledOrderIs409() {
        String a = account("100.00");
        String oid = (String) order(a, "BUY", "1", null).getBody().get("orderId");
        assertEquals(409, http.postForEntity("/orders/" + oid + "/cancel", null, Map.class).getStatusCode().value());
        assertEquals("FILLED", http.getForEntity("/orders/" + oid, Map.class).getBody().get("status"));
        assertEquals(404, http.getForEntity("/orders/00000000-0000-0000-0000-000000000000", Map.class).getStatusCode().value());
    }

    @Test void asOfBoundaryIsExclusiveAndEchoed() {
        String a = account("100.00");
        ResponseEntity<Map> r = order(a, "BUY", "3", null);
        String executedAt = (String) r.getBody().get("executedAt");
        ResponseEntity<Map> h = http.getForEntity("/accounts/" + a + "/holdings?asOf=" + executedAt, Map.class);
        assertEquals(200, h.getStatusCode().value());
        assertNull(((Map<?, ?>) h.getBody().get("holdings")).get("AAPL"));
        assertEquals(executedAt, h.getBody().get("asOfApplied"));
        assertTrue(((String) h.getBody().get("asOfApplied")).endsWith("Z"));
        assertEquals(400, http.getForEntity("/accounts/" + a + "/holdings?asOf=yesterday", Map.class).getStatusCode().value());
    }

    @Test void idempotencyKeyReplaysWithout2ndExecution() {
        String a = account("100.00");
        HttpHeaders hd = new HttpHeaders(); hd.set("Idempotency-Key", "k-" + System.nanoTime());
        ResponseEntity<Map> first = order(a, "BUY", "2", hd);
        ResponseEntity<Map> again = order(a, "BUY", "2", hd);
        assertEquals(201, first.getStatusCode().value());
        assertEquals(200, again.getStatusCode().value());
        assertEquals(first.getBody().get("orderId"), again.getBody().get("orderId"));
        assertEquals("80.00", balance(a));
    }

    @Test void unknownAccountIs404() {
        assertEquals(404, http.getForEntity("/accounts/00000000-0000-0000-0000-000000000000", Map.class).getStatusCode().value());
        assertEquals(404, http.getForEntity("/accounts/not-a-uuid", Map.class).getStatusCode().value());
    }
}
