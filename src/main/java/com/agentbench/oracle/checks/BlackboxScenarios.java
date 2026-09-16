package com.agentbench.oracle.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Port of oracle/checks/06_blackbox.py: THE correctness score. Oracle-owned scenarios driven
 *  over HTTP against the frozen contract; the agent never sees them. Every scenario creates ITS
 *  OWN account, so scenarios are independent items (pass^k is meaningful). Time comes from the
 *  SERVER (`executedAt`), never from the oracle's clock, so a drifting clock cannot fail a correct
 *  implementation. F6 grades every observed response against the frozen contract's declared
 *  status codes and the SCHEMAS table, plus the agent's own shipped OpenAPI spec. */
public final class BlackboxScenarios {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String base;
    public final Map<String, String> notes = new LinkedHashMap<>();
    public final List<Object[]> calls = new ArrayList<>();   // [method, path, status, bodyJson] - F6 grades these

    public static final Map<String, String> PRICES = Map.of("AAPL", "10.00", "TSLA", "250.00", "EURUSD", "1.0850");

    public BlackboxScenarios(String base) { this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base; }

    static final DateTimeFormatter ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** status only: the /health body is unconstrained */
    public boolean up() {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    record Resp(int status, JsonNode body) {}

    Resp call(String method, String path, String bodyJson, String idempotencyKey) {
        int status = 599;
        JsonNode body = json.createObjectNode();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").header("Accept", "application/json");
            if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
            HttpRequest req = bodyJson == null ? b.method(method, HttpRequest.BodyPublishers.noBody()).build()
                    : b.method(method, HttpRequest.BodyPublishers.ofString(bodyJson)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            body = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (Exception e) {
            body = json.createObjectNode().put("error", e.toString());
        }
        calls.add(new Object[]{method, path.split("\\?")[0], status, body});
        return new Resp(status, body);
    }

    private String account(String deposit) {   // every scenario gets its OWN account: independence
        Resp r = call("POST", "/accounts", "{\"currency\":\"USD\"}", null);
        if (r.status() != 201 || r.body().path("accountId").asText("").isEmpty())
            throw new IllegalStateException("cannot create account: " + r.status() + " " + r.body().toString().substring(0, Math.min(80, r.body().toString().length())));
        String aid = r.body().path("accountId").asText();
        if (deposit != null && call("POST", "/accounts/" + aid + "/deposits", "{\"amount\":\"" + deposit + "\"}", null).status() != 200)
            throw new IllegalStateException("deposit " + deposit + " rejected");
        return aid;
    }

    private BigDecimal balance(String aid) {
        Resp a = call("GET", "/accounts/" + aid, null, null);
        return a.status() == 200 ? dec(a.body().path("availableBalance").asText(null)) : null;
    }

    private Resp order(String aid, String side, String qty, String symbol, String idemKey) {
        return call("POST", "/orders", "{\"accountId\":\"" + aid + "\",\"symbol\":\"" + symbol + "\",\"side\":\"" + side
                + "\",\"quantity\":\"" + qty + "\",\"limitPrice\":\"" + PRICES.get(symbol) + "\"}", idemKey);
    }

    private record Hold(int status, BigDecimal qty, Map<String, Object> raw) {}
    private Hold holdings(String aid, String asOf) { return holdings(aid, asOf, "AAPL"); }
    private Hold holdings(String aid, String asOf, String symbol) {
        Resp h = call("GET", "/accounts/" + aid + "/holdings" + (asOf == null ? "" : "?asOf=" + asOf), null, null);
        return new Hold(h.status(), h.status() == 200 ? dec(h.body().path("holdings").path(symbol).asText("0")) : null,
                h.status() == 200 ? json.convertValue(h.body(), Map.class) : Map.of());
    }

    private static BigDecimal dec(String s) {
        try { return s == null || s.isEmpty() ? null : new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static OffsetDateTime parseTs(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return OffsetDateTime.parse(s.replace("Z", "+00:00")); } catch (Exception e) { return null; }
    }

    private boolean fail(String id, String why) { notes.put(id, why); return false; }
    private boolean check(String id, boolean ok, String detail) { notes.put(id, detail); return ok; }

    /** F1: symmetry — buy 2 TSLA @250, sell 2 -> balance and holdings restored; the INTERMEDIATE
     *  state must be real (C-1: a no-op server must not pass) */
    public boolean f1BuyThenSellRestoresHoldings() {
        String aid = account("1000.00");
        BigDecimal b0 = balance(aid);
        Hold h0 = holdings(aid, null, "TSLA");
        Resp b = order(aid, "BUY", "2", "TSLA", null);
        BigDecimal bmid = balance(aid);
        Hold hmid = holdings(aid, null, "TSLA");
        Resp s = order(aid, "SELL", "2", "TSLA", null);
        BigDecimal b1 = balance(aid);
        Hold h1 = holdings(aid, null, "TSLA");
        boolean ok = b.status() == 201 && s.status() == 201 && h0.status() == 200
                && dec("1000.00").compareTo(b0) == 0 && dec("500.00").compareTo(bmid) == 0
                && hmid.qty().compareTo(BigDecimal.valueOf(2)) == 0
                && b1.compareTo(b0) == 0 && h0.qty().signum() == 0 && h1.qty().signum() == 0;
        return check("F1", ok, "buy=" + b.status() + " sell=" + s.status() + " balance " + b0 + "->" + bmid + "(exp 500.00)->" + b1
                + " TSLA " + h0.qty() + "->" + hmid.qty() + "(exp 2)->" + h1.qty());
    }

    /** F2: point-in-time between the buy and the sell shows the position BEFORE the sell; after the sell it is 0 */
    public boolean f2PointInTimeBetweenBuyAndSell() throws InterruptedException {
        String aid = account("1000.00");
        Resp buy = order(aid, "BUY", "5", "AAPL", null);
        OffsetDateTime tb = parseTs(buy.body().path("executedAt").asText(null));
        if (buy.status() != 201 || tb == null) return fail("F2", "buy -> " + buy.status() + ", executedAt=" + buy.body().path("executedAt"));
        Thread.sleep(1500);
        Resp sell = order(aid, "SELL", "5", "AAPL", null);
        OffsetDateTime ts = parseTs(sell.body().path("executedAt").asText(null));
        if (sell.status() != 201 || ts == null) return fail("F2", "sell -> " + sell.status() + ", executedAt=" + sell.body().path("executedAt"));
        String tBetween = ISO_MS.format(tb.plusSeconds(1)), tAfter = ISO_MS.format(ts.plusSeconds(1));
        Hold qBetween = holdings(aid, tBetween), qAfter = holdings(aid, tAfter);
        boolean ok = qBetween.status() == 200 && qAfter.status() == 200
                && qBetween.qty().compareTo(BigDecimal.valueOf(5)) == 0 && qAfter.qty().signum() == 0;
        return check("F2", ok, "asOf between=" + qBetween.qty() + " (exp 5), asOf after sell=" + qAfter.qty() + " (exp 0; 10 => replay ADDS sells)");
    }

    /** F3: insufficient funds -> 422 with balance unchanged; insufficient HOLDINGS on the SELL side -> 422 too */
    public boolean f3InsufficientBalanceRejectedWith422() {
        String aid = account("1000.00");
        BigDecimal b0 = balance(aid);
        Resp buy = order(aid, "BUY", "1000000", "AAPL", null);
        BigDecimal b1 = balance(aid);
        Resp sell = order(aid, "SELL", "1", "EURUSD", null);
        BigDecimal b2 = balance(aid);
        return check("F3", buy.status() == 422 && sell.status() == 422
                        && b0.compareTo(b1) == 0 && b1.compareTo(b2) == 0,
                "BUY 1e6 -> " + buy.status() + " (exp 422), SELL without holdings -> " + sell.status() + " (exp 422); balance " + b0 + "->" + b1 + "->" + b2);
    }

    /** F4: illegal transition — cancel an already-FILLED order -> 409, status unchanged */
    public boolean f4IllegalTransitionRejectedWith409() {
        String aid = account("1000.00");
        Resp o = order(aid, "BUY", "1", "AAPL", null);
        String oid = o.body().path("orderId").asText("");
        if (o.status() != 201 || oid.isEmpty()) return fail("F4", "buy -> " + o.status() + " " + o.body().toString().substring(0, Math.min(60, o.body().toString().length())));
        Resp c = call("POST", "/orders/" + oid + "/cancel", "{}", null);
        Resp o2 = call("GET", "/orders/" + oid, null, null);
        return check("F4", c.status() == 409 && "FILLED".equals(o2.body().path("status").asText()),
                "cancel FILLED -> " + c.status() + " (expected 409); status after = " + o2.body().path("status").asText());
    }

    /** F5: exact decimal round-trip — 0.10 + 0.20 == "0.30" as a STRING */
    public boolean f5DecimalAmountsRoundTripExactly() {
        String aid = account("0.10");
        Resp r = call("POST", "/accounts/" + aid + "/deposits", "{\"amount\":\"0.20\"}", null);
        String raw = r.body().path("availableBalance").isTextual() ? r.body().path("availableBalance").asText() : null;
        return check("F5", raw != null && dec(raw) != null && dec(raw).compareTo(dec("0.30")) == 0,
                "0.10+0.20 -> " + (raw == null ? "null" : "'" + raw + "'") + " (expected string '0.30')");
    }

    /** F7: bespoke — HALF_EVEN rounding on deposits */
    public boolean f7DepositsRoundHalfEven() {
        String a1 = account(null), a2 = account(null);
        Resp r1 = call("POST", "/accounts/" + a1 + "/deposits", "{\"amount\":\"0.005\"}", null);
        Resp r2 = call("POST", "/accounts/" + a2 + "/deposits", "{\"amount\":\"0.015\"}", null);
        String v1 = r1.body().path("availableBalance").asText(null), v2 = r2.body().path("availableBalance").asText(null);
        boolean ok = v1 != null && v2 != null && dec(v1) != null && dec(v2) != null
                && dec(v1).compareTo(dec("0.00")) == 0 && dec(v2).compareTo(dec("0.02")) == 0;
        return check("F7", ok, "0.005->'" + v1 + "' (exp 0.00), 0.015->'" + v2 + "' (exp 0.02); HALF_UP gives 0.01/0.02, truncation 0.00/0.01");
    }

    /** F8: bespoke — asOf EXCLUSIVE at the boundary + asOfApplied echo at ms precision. Two buys:
     *  asOf = the second's executedAt must show ONLY the first (non-zero, so an always-empty
     *  endpoint fails — C-1) */
    public boolean f8AsOfBoundaryExclusiveAndEchoed() throws InterruptedException {
        String aid = account("1000.00");
        Resp o1 = order(aid, "BUY", "3", "AAPL", null);
        OffsetDateTime t1 = parseTs(o1.body().path("executedAt").asText(null));
        if (o1.status() != 201 || t1 == null) return fail("F8", "buy1 -> " + o1.status() + ", executedAt=" + o1.body().path("executedAt") + " (must be ISO-8601 UTC)");
        Thread.sleep(1200);
        Resp o2 = order(aid, "BUY", "2", "AAPL", null);
        String ex2 = o2.body().path("executedAt").asText(null);
        OffsetDateTime t2 = parseTs(ex2);
        if (o2.status() != 201 || t2 == null) return fail("F8", "buy2 -> " + o2.status() + ", executedAt=" + ex2);
        Hold h = holdings(aid, ex2);
        OffsetDateTime echoed = parseTs(String.valueOf(h.raw().get("asOfApplied")));
        boolean ok = h.status() == 200 && h.qty().compareTo(BigDecimal.valueOf(3)) == 0
                && echoed != null && Math.abs(Duration.between(echoed, t2).toMillis()) < 1
                && String.valueOf(h.raw().get("asOfApplied")).endsWith("Z");
        return check("F8", ok, "asOf=buy2.executedAt(" + ex2 + ") -> holdings " + h.qty() + " (exp 3: buy1 in, buy2 excluded); asOfApplied=" + h.raw().get("asOfApplied"));
    }

    /** F9: bespoke — Idempotency-Key repeat -> same orderId, 200, no second execution (the FIRST order did execute: C-1) */
    public boolean f9IdempotencyKeyRepeat() {
        String aid = account("1000.00");
        String key = "k-" + System.currentTimeMillis();
        Resp first = order(aid, "BUY", "2", "AAPL", key);
        BigDecimal b1 = balance(aid);
        Resp repeat = order(aid, "BUY", "2", "AAPL", key);
        BigDecimal b2 = balance(aid);
        boolean same = !first.body().path("orderId").asText("").isEmpty()
                && first.body().path("orderId").asText().equals(repeat.body().path("orderId").asText());
        Hold q = holdings(aid, null);
        return check("F9", first.status() == 201 && repeat.status() == 200 && same
                        && b1 != null && dec("980.00").compareTo(b1) == 0 && b2.compareTo(b1) == 0
                        && q.qty().compareTo(BigDecimal.valueOf(2)) == 0,
                "first " + first.status() + ", repeat " + repeat.status() + " (exp 200), sameId=" + same + ", balance " + b1 + "(exp 980.00)->" + b2 + ", holdings " + q.qty() + "(exp 2)");
    }

    // ---- F6: conformance of everything observed, against the FROZEN contract + the agent's own spec ----
    static final String DEC = "decimal-string", TS = "timestamp";
    static final Map<String, Map<String, String>> SCHEMAS = Map.ofEntries(
            Map.entry("POST /accounts 201", Map.of("accountId", "str", "currency", "str", "availableBalance", DEC)),
            Map.entry("GET /accounts/{id} 200", Map.of("accountId", "str", "currency", "str", "availableBalance", DEC, "reservedBalance", DEC)),
            Map.entry("POST /accounts/{id}/deposits 200", Map.of("availableBalance", DEC)),
            Map.entry("POST /orders 201", Map.of("orderId", "str", "status", "str", "executedAt", TS)),
            Map.entry("POST /orders 200", Map.of("orderId", "str", "status", "str")),
            Map.entry("GET /orders/{id} 200", Map.of("orderId", "str", "status", "str", "side", "str", "symbol", "str", "quantity", DEC, "limitPrice", DEC)),
            Map.entry("POST /orders/{id}/cancel 200", Map.of("orderId", "str", "status", "str")),
            Map.entry("GET /accounts/{id}/holdings 200", Map.of("holdings", "dict", "asOfApplied", TS)),
            Map.entry("GET /prices/{symbol} 200", Map.of("symbol", "str", "price", DEC)));

    /** minimal paths parser for the frozen-contract YAML shape: path -> method -> status codes (a
     *  full YAML parser is not needed for `paths: { /x: { get: { responses: { 200: ... } } } }`) */
    static Map<String, Map<String, List<String>>> parseOpenApiPaths(String yaml) {
        // for each path, for each method at +2 indent, collect the `responses:` status codes at +4
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        String path = null, method = null;
        int pIndent = -1;
        for (String line : yaml.split("\n")) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int indent = line.indexOf(line.strip());
            String t = line.strip();
            if (t.startsWith("/")) { path = t.split(":")[0]; out.putIfAbsent(path, new LinkedHashMap<>()); pIndent = indent; method = null; }
            else if (path != null && indent == pIndent + 2 && List.of("get:", "post:", "put:", "delete:", "patch:").contains(t)) {
                method = t.replace(":", "");
                out.get(path).putIfAbsent(method, new ArrayList<>());
            } else if (method != null && indent == pIndent + 4 && t.matches("\\d{3}:.*")) {
                out.get(path).get(method).add(t.split(":")[0]);
            }
        }
        return out;
    }

    static String matchTemplate(Map<String, Map<String, List<String>>> paths, String method, String path) {
        for (Map.Entry<String, Map<String, List<String>>> e : paths.entrySet()) {
            String rx = "^" + e.getKey().replaceAll("\\{[^}]+\\}", "[^/]+") + "$";
            if (Pattern.compile(rx).matcher(path).matches() && e.getValue().containsKey(method.toLowerCase()))
                return e.getKey();
        }
        return null;
    }

    static String bodyOk(Map<String, String> schema, JsonNode body) {
        if (body == null || !body.isObject()) return "body is not a JSON object";
        for (Map.Entry<String, String> k : schema.entrySet()) {
            JsonNode v = body.get(k.getKey());
            if (v == null || v.isNull()) return "missing " + k.getKey();
            switch (k.getValue()) {
                case DEC -> { if (!v.isTextual() || dec(v.asText()) == null) return k.getKey() + " must be a decimal STRING, got " + v.getNodeType() + " " + v.asText(); }
                case TS -> { if (!v.isTextual() || parseTs(v.asText()) == null) return k.getKey() + " must be ISO-8601, got " + v.asText(); }
                case "str" -> { if (!v.isTextual()) return k.getKey() + " must be String"; }
                case "dict" -> { if (!v.isObject()) return k.getKey() + " must be a map"; }
            }
        }
        return "";
    }

    /** F6: grade every observed response against the contract's declared statuses, the SCHEMAS
     *  table, and the agent's own shipped OpenAPI spec (`default` earns nothing). */
    public boolean f6ContractConformance(Path ws) {
        if (calls.isEmpty()) {   // F6 alone: probe the endpoints the rung must have
            account("1.00");
            call("GET", "/prices/AAPL", null, null);
        }
        Map<String, Map<String, List<String>>> contract;
        try (var in = getClass().getResourceAsStream("/contract/openapi.yaml")) {
            contract = parseOpenApiPaths(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return fail("F6", "frozen contract unreadable: " + e); }
        List<String> problems = new ArrayList<>();
        for (Object[] c : calls) {
            String m = (String) c[0], p = (String) c[1];
            int st = (Integer) c[2];
            JsonNode body = (JsonNode) c[3];
            String tpl = matchTemplate(contract, m, p);
            if (tpl == null) { problems.add(m + " " + p + " not in contract"); continue; }
            if (!contract.get(tpl).get(m.toLowerCase()).contains(String.valueOf(st))) {
                problems.add(m + " " + tpl + " -> " + st + " undeclared in contract");
                continue;
            }
            Map<String, String> sch = SCHEMAS.get(m + " " + tpl + " " + st);
            if (sch != null) {
                String err = bodyOk(sch, body);
                if (!err.isEmpty()) problems.add(m + " " + tpl + " " + st + ": " + err);
            }
        }
        Map<String, Map<String, List<String>>> spec = null;
        String where = null;
        for (Path p : StructureChecks.glob(ws, "**/*")) {
            String n = p.getFileName().toString().toLowerCase();
            if ((n.startsWith("openapi") || n.startsWith("api")) && (n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"))
                    && !StructureChecks.skip(p)) {
                try {
                    spec = parseOpenApiPaths(Files.readString(p));
                    where = ws.relativize(p).toString();
                    if (!spec.isEmpty()) break;
                } catch (Exception ignore) {}
            }
        }
        if (spec == null || spec.isEmpty()) problems.add("agent ships no parseable OpenAPI spec with paths");
        else
            for (Object[] c : calls) {
                String m = (String) c[0], p = (String) c[1];
                int st = (Integer) c[2];
                String tpl = matchTemplate(spec, m, p);
                if (tpl == null || !spec.get(tpl).get(m.toLowerCase()).contains(String.valueOf(st)))
                    problems.add("agent spec (" + where + ") does not declare " + m + " " + p + " -> " + st);
            }
        List<String> uniq = problems.stream().distinct().sorted().toList();
        return check("F6", uniq.isEmpty(), calls.size() + " observed responses checked against contract+schemas+agent spec"
                + (uniq.isEmpty() ? "" : "; problems: " + uniq.subList(0, Math.min(3, uniq.size()))));
    }
}
