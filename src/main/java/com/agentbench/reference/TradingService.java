package com.agentbench.reference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Port of oracle/reference_server.py: the reference implementation of the frozen contract,
 *  CORRECT BY DEFAULT, used to calibrate the black-box suite. BUGS (a comma list via the `bugs`
 *  field) injects the real-world failure modes so each scenario can be proven to catch what it
 *  claims. Money is BigDecimal with explicit scale 2 and RoundingMode.HALF_EVEN — deposits round
 *  0.005 -> 0.00 and 0.015 -> 0.02 (the bespoke F7 rule). */
public class TradingService {
    public static final Set<String> KNOWN_BUGS = Set.of("float", "selladd", "no409", "no422", "asym", "halfup", "inclusive", "noidem", "hollow");

    private final Set<String> bugs;
    private static final int MONEY_SCALE = 2;
    private static final Map<String, BigDecimal> PRICES = Map.of(
            "AAPL", new BigDecimal("10.00"), "TSLA", new BigDecimal("250.00"), "EURUSD", new BigDecimal("1.0850"));

    public record Account(String id, String currency, BigDecimal balance) {}
    public record Order(String orderId, String status, String side, String symbol, BigDecimal quantity, BigDecimal limitPrice, Instant executedAt) {}
    record LedgerEntry(Instant ts, String accountId, String symbol, String side, BigDecimal qty) {}

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final List<LedgerEntry> ledger = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Idem> idem = new ConcurrentHashMap<>();   // Idempotency-Key -> (orderId, expires)
    record Idem(String orderId, Instant expires) {}

    public TradingService(Set<String> bugs) { this.bugs = new HashSet<>(bugs); }
    public TradingService() { this(Set.of()); }

    boolean bug(String b) { return bugs.contains(b); }

    /** money(): scale-2 quantize; HALF_EVEN by contract, HALF_UP under the `halfup` bug; `float` returns the raw float path.
     *  BigDecimal with explicit scale + RoundingMode, compared with compareTo — never equals (the scale trap, M3). */
    BigDecimal money(BigDecimal x) {
        if (bug("float")) return x.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new BigDecimal(x.toString()).setScale(MONEY_SCALE, bug("halfup") ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN);
    }

    public Map<String, Object> price(String symbol) {
        BigDecimal p = PRICES.get(symbol.toUpperCase());
        return p == null ? null : Map.of("symbol", symbol.toUpperCase(), "price", p.toPlainString());
    }

    public Map<String, Object> createAccount(String currency) {
        String id = UUID.randomUUID().toString();
        accounts.put(id, new Account(id, currency == null || currency.isBlank() ? "USD" : currency, money(BigDecimal.ZERO)));
        return accountView(accounts.get(id));
    }

    public Map<String, Object> account(String id) {
        Account a = accounts.get(id);
        return a == null ? null : accountView(a);
    }

    /** deposits: positivity on the RAW amount, then the bespoke rounding. The `float` bug does the
     *  arithmetic in double space (0.1 + 0.2 = 0.30000000000000004) exactly like the Python twin. */
    public Map<String, Object> deposit(String accountId, String amount) {
        Account a = accounts.get(accountId);
        if (a == null) return null;
        BigDecimal raw = new BigDecimal(amount == null || amount.isBlank() ? "0" : amount);
        if (raw.compareTo(BigDecimal.ZERO) <= 0) return Map.of("error", "non-positive");
        if (bug("float")) {
            double bal = a.balance().doubleValue() + raw.doubleValue();
            Account updated = new Account(a.id(), a.currency(), BigDecimal.valueOf(bal));
            accounts.put(a.id(), updated);
            return accountView(updated);
        }
        BigDecimal amt = money(raw);
        Account updated = new Account(a.id(), a.currency(), a.balance().add(amt));
        accounts.put(a.id(), updated);
        return accountView(updated);
    }

    /** orders: BUY needs funds (422), SELL needs holdings (422); both write the ledger and fill at the limit price */
    public OrderOutcome order(String accountId, String symbol, String side, String quantity, String limitPrice, String idempotencyKey) {
        if (idempotencyKey != null && !bug("noidem")) {
            Idem hit = idem.get(idempotencyKey);
            if (hit != null && hit.expires().isAfter(now())) return new OrderOutcome(200, orderView(orders.get(hit.orderId())), null);
        }
        Account a = accounts.get(accountId);
        if (a == null) return new OrderOutcome(400, Map.of("error", "bad account"), null);
        String sym = symbol == null ? "" : symbol.toUpperCase();
        BigDecimal qty = new BigDecimal(quantity == null || quantity.isBlank() ? "0" : quantity);
        BigDecimal px = money(new BigDecimal(limitPrice == null || limitPrice.isBlank() ? "0" : limitPrice));
        if (!PRICES.containsKey(sym) || side == null || !(side.equals("BUY") || side.equals("SELL")) || qty.compareTo(BigDecimal.ZERO) <= 0)
            return new OrderOutcome(400, Map.of("error", "invalid"), null);
        BigDecimal cost = money(qty.multiply(px));

        if (bug("hollow")) {   // orders return 201 + executedAt but change NO state and write NO ledger
            Order o = filled(UUID.randomUUID().toString(), side, sym, qty, px);
            if (idempotencyKey != null) idem.put(idempotencyKey, new Idem(o.orderId(), now().plusSeconds(60)));
            return new OrderOutcome(201, orderView(o), null);
        }
        Account updated;
        if ("BUY".equals(side)) {
            if (cost.compareTo(a.balance()) > 0 && !bug("no422")) return new OrderOutcome(422, Map.of("error", "insufficient funds"), null);
            updated = new Account(a.id(), a.currency(), a.balance().subtract(cost));
        } else {
            BigDecimal held = held(a.id(), sym);
            if (qty.compareTo(held) > 0 && !bug("no422")) return new OrderOutcome(422, Map.of("error", "insufficient holdings"), null);
            BigDecimal credit = bug("asym") ? money(cost.multiply(new BigDecimal("0.99"))) : cost;
            updated = new Account(a.id(), a.currency(), a.balance().add(credit));
        }
        accounts.put(a.id(), updated);
        Instant ts = now();
        synchronized (ledger) { ledger.add(new LedgerEntry(ts, a.id(), sym, side, qty)); }
        Order o = new Order(UUID.randomUUID().toString(), "FILLED", side, sym, qty, px, ts);
        orders.put(o.orderId(), o);
        if (idempotencyKey != null) idem.put(idempotencyKey, new Idem(o.orderId(), now().plusSeconds(60)));
        return new OrderOutcome(201, orderView(o), null);
    }

    public Map<String, Object> order(String id) {
        Order o = orders.get(id);
        return o == null ? null : orderView(o);
    }

    /** cancel: cancelling a FILLED/CANCELLED order is an illegal transition (409) */
    public CancelOutcome cancel(String orderId) {
        Order o = orders.get(orderId);
        if (o == null) return new CancelOutcome(404, Map.of("error", "no order"));
        if ((o.status().equals("FILLED") || o.status().equals("CANCELLED")) && !bug("no409"))
            return new CancelOutcome(409, Map.of("error", "illegal transition"));
        Order cancelled = new Order(o.orderId(), "CANCELLED", o.side(), o.symbol(), o.quantity(), o.limitPrice(), o.executedAt());
        orders.put(o.orderId(), cancelled);
        return new CancelOutcome(200, Map.of("orderId", o.orderId(), "status", "CANCELLED"));
    }

    /** holdings with an OPTIONAL asOf point-in-time query. The boundary is EXCLUSIVE, and the applied
     *  instant is always echoed as asOfApplied. Sells SUBTRACT (the `selladd` bug adds them). */
    public Map<String, Object> holdings(String accountId, Instant asOf) {
        Account a = accounts.get(accountId);
        if (a == null) return null;
        Map<String, BigDecimal> h = new TreeMap<>();
        synchronized (ledger) {
            for (LedgerEntry e : ledger) {
                if (!e.accountId().equals(a.id())) continue;
                if (asOf != null && (bug("inclusive") ? e.ts().isAfter(asOf) : !e.ts().isBefore(asOf))) continue;   // EXCLUSIVE boundary
                int sign = e.side().equals("BUY") || bug("selladd") ? 1 : -1;
                h.merge(e.symbol(), e.qty().multiply(BigDecimal.valueOf(sign)), BigDecimal::add);
            }
        }
        Map<String, String> out = new TreeMap<>();
        h.forEach((k, v) -> { if (v.signum() != 0) out.put(k, v.toPlainString()); });
        Instant applied = asOf != null ? asOf : now();
        return Map.of("holdings", out, "asOfApplied", iso(applied));
    }

    BigDecimal held(String accountId, String symbol) {   // net bought minus sold, for the SELL-side check
        BigDecimal held = BigDecimal.ZERO;
        synchronized (ledger) {
            for (LedgerEntry e : ledger)
                if (e.accountId().equals(accountId) && e.symbol().equals(symbol))
                    held = held.add(e.qty().multiply(e.side().equals("BUY") ? BigDecimal.ONE : BigDecimal.ONE.negate()));
        }
        return held;
    }

    private Order filled(String id, String side, String sym, BigDecimal qty, BigDecimal px) {
        return new Order(id, "FILLED", side, sym, qty, px, now());
    }

    private Map<String, Object> accountView(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.id());
        m.put("currency", a.currency());
        m.put("availableBalance", bug("float") ? String.valueOf(a.balance().doubleValue()) : a.balance().toPlainString());
        return m;
    }

    private Map<String, Object> orderView(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.orderId());
        m.put("status", o.status());
        m.put("side", o.side());
        m.put("symbol", o.symbol());
        m.put("quantity", o.quantity().toPlainString());
        m.put("limitPrice", bug("float") ? String.valueOf(o.limitPrice().doubleValue()) : o.limitPrice().toPlainString());
        m.put("executedAt", iso(o.executedAt()));
        return m;
    }

    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    public static String iso(Instant t) { return t.toString(); }

    public record OrderOutcome(int status, Map<String, Object> body, Object raw) {}
    public record CancelOutcome(int status, Map<String, Object> body) {}
}
