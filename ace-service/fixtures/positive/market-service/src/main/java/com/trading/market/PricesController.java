package com.trading.market;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/** The contract's fixed price table. Prices are BigDecimal internally and STRINGS on the wire. */
@RestController
public class PricesController {
    static final Map<String, BigDecimal> PRICES = Map.of(
            "AAPL", new BigDecimal("10.00"), "TSLA", new BigDecimal("250.00"), "EURUSD", new BigDecimal("1.0850"));

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }

    @GetMapping("/prices/{symbol}")
    public ResponseEntity<Map<String, String>> price(@PathVariable String symbol) {
        BigDecimal price = PRICES.get(symbol.toUpperCase());
        if (price == null) return ResponseEntity.status(404).body(Map.of("error", "unknown symbol"));
        return ResponseEntity.ok(Map.of("symbol", symbol.toUpperCase(), "price", price.toPlainString()));
    }
}
