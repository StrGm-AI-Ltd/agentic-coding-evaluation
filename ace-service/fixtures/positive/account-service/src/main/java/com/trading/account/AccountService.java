package com.trading.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {
    static final Duration IDEMPOTENCY_TTL = Duration.ofSeconds(60);
    static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;

    AccountService(AccountRepository accounts, OrderRepository orders, LedgerRepository ledger, IdempotencyRepository idempotency) {
        this.accounts = accounts; this.orders = orders; this.ledger = ledger; this.idempotency = idempotency;
    }

    @Transactional
    public AccountEntity create(String currency) {
        AccountEntity a = new AccountEntity();
        a.accountId = UUID.randomUUID();
        a.currency = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
        return accounts.save(a);
    }

    public AccountEntity get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new ApiException(404, "no such account"));
    }

    /** Positivity is judged on the RAW amount; the bespoke HALF_EVEN rounding is applied afterwards. */
    @Transactional
    public AccountEntity deposit(UUID id, BigDecimal rawAmount) {
        if (rawAmount.signum() <= 0) throw new ApiException(400, "amount must be positive");
        AccountEntity a = get(id);
        a.availableBalance = a.availableBalance.add(Money.scale2(rawAmount));
        return accounts.save(a);
    }

    /** Replays the ledger STRICTLY BEFORE asOf (exclusive boundary); sells subtract. */
    public Map<String, BigDecimal> holdingsAt(UUID accountId, Instant asOf) {
        Map<String, BigDecimal> position = new LinkedHashMap<>();
        for (LedgerEntry e : ledger.findByAccountIdAndExecutedAtLessThanOrderByExecutedAtAscEntryIdAsc(accountId, asOf)) {
            BigDecimal current = position.getOrDefault(e.symbol, BigDecimal.ZERO);
            if ("SELL".equals(e.side)) {
                position.put(e.symbol, current.subtract(e.quantity));
            } else {
                position.put(e.symbol, current.add(e.quantity));
            }
        }
        position.entrySet().removeIf(en -> en.getValue().signum() == 0);
        return position;
    }

    public record Placed(OrderEntity order, boolean replay) {}

    @Transactional
    public Placed placeOrder(UUID accountId, String symbol, String side, BigDecimal quantity, BigDecimal limitPrice, String idemKey) {
        Instant now = Money.nowMillis();
        if (idemKey != null && !idemKey.isBlank()) {
            IdempotencyKey k = idempotency.findById(idemKey).orElse(null);
            if (k != null && k.expiresAt.isAfter(now)) {
                return new Placed(orders.findById(k.orderId).orElseThrow(), true);
            }
        }
        AccountEntity a = accounts.findById(accountId).orElseThrow(() -> new ApiException(400, "unknown account"));
        if (!"BUY".equals(side) && !"SELL".equals(side)) throw new ApiException(400, "side must be BUY or SELL");
        if (symbol == null || symbol.isBlank()) throw new ApiException(400, "symbol required");
        if (quantity.signum() <= 0 || limitPrice.signum() <= 0) throw new ApiException(400, "quantity and limitPrice must be positive");
        BigDecimal cost = Money.scale2(quantity.multiply(limitPrice));
        if ("BUY".equals(side)) {
            if (cost.compareTo(a.availableBalance) > 0) throw new ApiException(422, "insufficient funds");
            a.availableBalance = a.availableBalance.subtract(cost);
        } else {
            BigDecimal held = holdingsAt(accountId, FAR_FUTURE).getOrDefault(symbol.toUpperCase(), BigDecimal.ZERO);
            if (quantity.compareTo(held) > 0) throw new ApiException(422, "insufficient holdings");
            a.availableBalance = a.availableBalance.add(cost);
        }
        accounts.save(a);
        OrderEntity o = new OrderEntity();
        o.orderId = UUID.randomUUID(); o.accountId = accountId; o.symbol = symbol.toUpperCase(); o.side = side;
        o.quantity = quantity; o.limitPrice = limitPrice; o.status = "FILLED"; o.executedAt = now;   // immediate fill
        orders.save(o);
        LedgerEntry e = new LedgerEntry();
        e.accountId = accountId; e.symbol = o.symbol; e.side = side; e.quantity = quantity; e.executedAt = now;
        ledger.save(e);
        if (idemKey != null && !idemKey.isBlank()) {
            IdempotencyKey k = new IdempotencyKey();
            k.key = idemKey; k.orderId = o.orderId; k.expiresAt = now.plus(IDEMPOTENCY_TTL);
            idempotency.save(k);
        }
        return new Placed(o, false);
    }

    public OrderEntity getOrder(UUID id) {
        return orders.findById(id).orElseThrow(() -> new ApiException(404, "no such order"));
    }

    /** NEW -> CANCELLED only. FILLED and CANCELLED are terminal: 409. */
    @Transactional
    public OrderEntity cancel(UUID id) {
        OrderEntity o = getOrder(id);
        if (!"NEW".equals(o.status)) throw new ApiException(409, "illegal transition from " + o.status);
        o.status = "CANCELLED";
        return orders.save(o);
    }
}
