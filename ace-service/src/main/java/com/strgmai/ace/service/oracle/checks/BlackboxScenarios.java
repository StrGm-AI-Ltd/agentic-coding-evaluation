package com.strgmai.ace.service.oracle.checks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of oracle/checks/06_blackbox.py: THE correctness score. Oracle-owned scenarios driven
 *  over HTTP against the frozen contract; the agent never sees them. Every scenario creates ITS
 *  OWN account, so scenarios are independent items (pass^k is meaningful). Time comes from the
 *  SERVER (`executedAt`), never from the oracle's clock, so a drifting clock cannot fail a correct
 *  implementation. F6 grades every observed response against the frozen contract's declared
 *  status codes and the SCHEMAS table, plus the agent's own shipped OpenAPI spec. */
public final class BlackboxScenarios {
    private static final Logger log = LoggerFactory.getLogger(BlackboxScenarios.class);
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
        } catch (Exception e) { log.debug("health probe against {} failed: {}", base, e.toString()); return false; }
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
            final HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            status = r.statusCode();
            body = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (Exception e) {
            body = json.createObjectNode().put("error", e.toString());
        }
        calls.add(new Object[]{method, path.split("\\?")[0], status, body});
        return new Resp(status, body);
    }

    private String account(String deposit) {   // every scenario gets its OWN account: independence
        final Resp r = call("POST", "/accounts", "{\"currency\":\"USD\"}", null);
        if (r.status() != 201 || r.body().path("accountId").asText("").isEmpty())
            throw new IllegalStateException("cannot create account: " + r.status() + " " + r.body().toString().substring(0, Math.min(80, r.body().toString().length())));
        final String aid = r.body().path("accountId").asText();
        if (deposit != null && call("POST", "/accounts/" + aid + "/deposits", "{\"amount\":\"" + deposit + "\"}", null).status() != 200)
            throw new IllegalStateException("deposit " + deposit + " rejected");
        return aid;
    }

    private BigDecimal balance(String aid) {
        final Resp a = call("GET", "/accounts/" + aid, null, null);
        return a.status() == 200 ? dec(a.body().path("availableBalance").asText(null)) : null;
    }

    private Resp order(String aid, final String side, final String qty, final String symbol, final String idemKey) {
        return call("POST", "/orders", "{\"accountId\":\"" + aid + "\",\"symbol\":\"" + symbol + "\",\"side\":\"" + side
                + "\",\"quantity\":\"" + qty + "\",\"limitPrice\":\"" + PRICES.get(symbol) + "\"}", idemKey);
    }

    private record Hold(int status, BigDecimal qty, Map<String, Object> raw) {}
    private Hold holdings(String aid, String asOf) { return holdings(aid, asOf, "AAPL"); }
    private Hold holdings(String aid, String asOf, final String symbol) {
        final Resp h = call("GET", "/accounts/" + aid + "/holdings" + (asOf == null ? "" : "?asOf=" + asOf), null, null);
        return new Hold(h.status(), h.status() == 200 ? dec(h.body().path("holdings").path(symbol).asText("0")) : null,
                h.status() == 200 ? json.convertValue(h.body(), Map.class) : Map.of());
    }

    private static BigDecimal dec(String s) {
        try { return s == null || s.isEmpty() ? null : new BigDecimal(s); }
        catch (NumberFormatException e) {
            // a null here can make a real money value silently read as absent, potentially false-
            // passing a check that exists to catch exactly this kind of money-handling bug
            log.warn("could not parse '{}' as a decimal amount: {}", s, e.toString());
            return null;
        }
    }

    private static OffsetDateTime parseTs(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return OffsetDateTime.parse(s.replace("Z", "+00:00")); }
        catch (Exception e) { log.warn("could not parse '{}' as a timestamp: {}", s, e.toString()); return null; }
    }

    private boolean fail(String id, String why) { notes.put(id, why); return false; }
    private boolean check(String id, boolean ok, String detail) { notes.put(id, detail); return ok; }

    /** F1: symmetry — buy 2 TSLA @250, sell 2 -> balance and holdings restored; the INTERMEDIATE
     *  state must be real (C-1: a no-op server must not pass) */
    public boolean f1BuyThenSellRestoresHoldings() {
        final String aid = account("1000.00");
        final BigDecimal b0 = balance(aid);
        final Hold h0 = holdings(aid, null, "TSLA");
        final Resp b = order(aid, "BUY", "2", "TSLA", null);
        final BigDecimal bmid = balance(aid);
        final Hold hmid = holdings(aid, null, "TSLA");
        final Resp s = order(aid, "SELL", "2", "TSLA", null);
        final BigDecimal b1 = balance(aid);
        final Hold h1 = holdings(aid, null, "TSLA");
        boolean ok = b.status() == 201 && s.status() == 201 && h0.status() == 200
                && dec("1000.00").compareTo(b0) == 0 && dec("500.00").compareTo(bmid) == 0
                && hmid.qty().compareTo(BigDecimal.valueOf(2)) == 0
                && b1.compareTo(b0) == 0 && h0.qty().signum() == 0 && h1.qty().signum() == 0;
        return check("F1", ok, "buy=" + b.status() + " sell=" + s.status() + " balance " + b0 + "->" + bmid + "(exp 500.00)->" + b1
                + " TSLA " + h0.qty() + "->" + hmid.qty() + "(exp 2)->" + h1.qty());
    }

    /** F2: point-in-time between the buy and the sell shows the position BEFORE the sell; after the sell it is 0 */
    public boolean f2PointInTimeBetweenBuyAndSell() throws InterruptedException {
        final String aid = account("1000.00");
        final Resp buy = order(aid, "BUY", "5", "AAPL", null);
        final OffsetDateTime tb = parseTs(buy.body().path("executedAt").asText(null));
        if (buy.status() != 201 || tb == null) return fail("F2", "buy -> " + buy.status() + ", executedAt=" + buy.body().path("executedAt"));
        Thread.sleep(1500);
        Resp sell = order(aid, "SELL", "5", "AAPL", null);
        final OffsetDateTime ts = parseTs(sell.body().path("executedAt").asText(null));
        if (sell.status() != 201 || ts == null) return fail("F2", "sell -> " + sell.status() + ", executedAt=" + sell.body().path("executedAt"));
        final String tBetween = ISO_MS.format(tb.plusSeconds(1)), tAfter = ISO_MS.format(ts.plusSeconds(1));
        final Hold qBetween = holdings(aid, tBetween), qAfter = holdings(aid, tAfter);
        boolean ok = qBetween.status() == 200 && qAfter.status() == 200
                && qBetween.qty().compareTo(BigDecimal.valueOf(5)) == 0 && qAfter.qty().signum() == 0;
        return check("F2", ok, "asOf between=" + qBetween.qty() + " (exp 5), asOf after sell=" + qAfter.qty() + " (exp 0; 10 => replay ADDS sells)");
    }

    /** F3: insufficient funds -> 422 with balance unchanged; insufficient HOLDINGS on the SELL side -> 422 too */
    public boolean f3InsufficientBalanceRejectedWith422() {
        final String aid = account("1000.00");
        final BigDecimal b0 = balance(aid);
        final Resp buy = order(aid, "BUY", "1000000", "AAPL", null);
        final BigDecimal b1 = balance(aid);
        final Resp sell = order(aid, "SELL", "1", "EURUSD", null);
        final BigDecimal b2 = balance(aid);
        return check("F3", buy.status() == 422 && sell.status() == 422
                        && b0.compareTo(b1) == 0 && b1.compareTo(b2) == 0,
                "BUY 1e6 -> " + buy.status() + " (exp 422), SELL without holdings -> " + sell.status() + " (exp 422); balance " + b0 + "->" + b1 + "->" + b2);
    }

    /** F4: illegal transition — cancel an already-FILLED order -> 409, status unchanged */
    public boolean f4IllegalTransitionRejectedWith409() {
        final String aid = account("1000.00");
        final Resp o = order(aid, "BUY", "1", "AAPL", null);
        final String oid = o.body().path("orderId").asText("");
        if (o.status() != 201 || oid.isEmpty()) return fail("F4", "buy -> " + o.status() + " " + o.body().toString().substring(0, Math.min(60, o.body().toString().length())));
        final Resp c = call("POST", "/orders/" + oid + "/cancel", "{}", null);
        final Resp o2 = call("GET", "/orders/" + oid, null, null);
        return check("F4", c.status() == 409 && "FILLED".equals(o2.body().path("status").asText()),
                "cancel FILLED -> " + c.status() + " (expected 409); status after = " + o2.body().path("status").asText());
    }

    /** F5: exact decimal round-trip — 0.10 + 0.20 == "0.30" as a STRING */
    public boolean f5DecimalAmountsRoundTripExactly() {
        final String aid = account("0.10");
        final Resp r = call("POST", "/accounts/" + aid + "/deposits", "{\"amount\":\"0.20\"}", null);
        final String raw = r.body().path("availableBalance").isTextual() ? r.body().path("availableBalance").asText() : null;
        return check("F5", raw != null && dec(raw) != null && dec(raw).compareTo(dec("0.30")) == 0,
                "0.10+0.20 -> " + (raw == null ? "null" : "'" + raw + "'") + " (expected string '0.30')");
    }

    /** F7: bespoke — HALF_EVEN rounding on deposits */
    public boolean f7DepositsRoundHalfEven() {
        final String a1 = account(null), a2 = account(null);
        final Resp r1 = call("POST", "/accounts/" + a1 + "/deposits", "{\"amount\":\"0.005\"}", null);
        final Resp r2 = call("POST", "/accounts/" + a2 + "/deposits", "{\"amount\":\"0.015\"}", null);
        final String v1 = r1.body().path("availableBalance").asText(null), v2 = r2.body().path("availableBalance").asText(null);
        boolean ok = v1 != null && v2 != null && dec(v1) != null && dec(v2) != null
                && dec(v1).compareTo(dec("0.00")) == 0 && dec(v2).compareTo(dec("0.02")) == 0;
        return check("F7", ok, "0.005->'" + v1 + "' (exp 0.00), 0.015->'" + v2 + "' (exp 0.02); HALF_UP gives 0.01/0.02, truncation 0.00/0.01");
    }

    /** F8: bespoke — asOf EXCLUSIVE at the boundary + asOfApplied echo at ms precision. Two buys:
     *  asOf = the second's executedAt must show ONLY the first (non-zero, so an always-empty
     *  endpoint fails — C-1) */
    public boolean f8AsOfBoundaryExclusiveAndEchoed() throws InterruptedException {
        final String aid = account("1000.00");
        final Resp o1 = order(aid, "BUY", "3", "AAPL", null);
        final OffsetDateTime t1 = parseTs(o1.body().path("executedAt").asText(null));
        if (o1.status() != 201 || t1 == null) return fail("F8", "buy1 -> " + o1.status() + ", executedAt=" + o1.body().path("executedAt") + " (must be ISO-8601 UTC)");
        Thread.sleep(1200);
        final Resp o2 = order(aid, "BUY", "2", "AAPL", null);
        final String ex2 = o2.body().path("executedAt").asText(null);
        final OffsetDateTime t2 = parseTs(ex2);
        if (o2.status() != 201 || t2 == null) return fail("F8", "buy2 -> " + o2.status() + ", executedAt=" + ex2);
        final Hold h = holdings(aid, ex2);
        final OffsetDateTime echoed = parseTs(String.valueOf(h.raw().get("asOfApplied")));
        boolean ok = h.status() == 200 && h.qty().compareTo(BigDecimal.valueOf(3)) == 0
                && echoed != null && Math.abs(Duration.between(echoed, t2).toMillis()) < 1
                && String.valueOf(h.raw().get("asOfApplied")).endsWith("Z");
        return check("F8", ok, "asOf=buy2.executedAt(" + ex2 + ") -> holdings " + h.qty() + " (exp 3: buy1 in, buy2 excluded); asOfApplied=" + h.raw().get("asOfApplied"));
    }

    /** F9: bespoke — Idempotency-Key repeat -> same orderId, 200, no second execution (the FIRST order did execute: C-1) */
    public boolean f9IdempotencyKeyRepeat() {
        final String aid = account("1000.00");
        final String key = "k-" + System.currentTimeMillis();
        final Resp first = order(aid, "BUY", "2", "AAPL", key);
        final BigDecimal b1 = balance(aid);
        final Resp repeat = order(aid, "BUY", "2", "AAPL", key);
        final BigDecimal b2 = balance(aid);
        boolean same = !first.body().path("orderId").asText("").isEmpty()
                && first.body().path("orderId").asText().equals(repeat.body().path("orderId").asText());
        final Hold q = holdings(aid, null);
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
    private static final List<String> METHOD_KEYS = List.of("get:", "post:", "put:", "delete:", "patch:");
    private static final Pattern STATUS_LINE = Pattern.compile("^\"?(\\d{3})\"?\\s*:");

    /** For each path, for each method, collect the status codes under its `responses:` block -
     *  standard OpenAPI YAML is 4 levels deep (path > method > `responses:` > status), tracked
     *  RELATIVE to each other (not fixed absolute offsets) so it does not care whether the file
     *  indents with 2 or 4 spaces. A path's entry may instead be inline flow-style JSON on one line
     *  (the shape this port's own contract/openapi.yaml uses: `/accounts/{id}: {get: {responses:
     *  {"200": {...}}}}}`) - that is parsed separately by parseFlowPathValue. A file can mix both
     *  styles per path. */
    static Map<String, Map<String, List<String>>> parseOpenApiPaths(final String yaml) {
        final Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        String path = null, method = null;
        int pIndent = -1, mIndent = -1, rIndent = -1;
        for (String line : yaml.split("\n")) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            final int indent = line.indexOf(line.strip());
            final String t = line.strip();
            if (t.startsWith("/")) {
                final int colon = t.indexOf(':');
                path = colon < 0 ? t : t.substring(0, colon);
                out.putIfAbsent(path, new LinkedHashMap<>());
                pIndent = indent; method = null; mIndent = -1; rIndent = -1;
                final String rest = colon < 0 ? "" : t.substring(colon + 1).strip();
                if (rest.startsWith("{")) parseFlowPathValue(rest, out.get(path));
                continue;
            }
            if (path == null || indent <= pIndent) { path = null; continue; }   // outside any path block
            if (METHOD_KEYS.contains(t) && (method == null || indent <= mIndent)) {
                method = t.substring(0, t.length() - 1);
                mIndent = indent; rIndent = -1;
                out.get(path).putIfAbsent(method, new ArrayList<>());
                continue;
            }
            if (method == null || indent <= mIndent) continue;   // not inside a method block
            if (t.equals("responses:")) { rIndent = indent; continue; }
            if (rIndent >= 0 && indent > rIndent) {
                final Matcher sc = STATUS_LINE.matcher(t);
                if (sc.find()) out.get(path).get(method).add(sc.group(1));
            }
        }
        return out;
    }

    /** parse one path's inline flow-style value, e.g. `{get: {responses: {"200": {...}, "404":
     *  {...}}}, post: {responses: {"201": {...}}}}`, into method -> [status codes]. Brace-matched
     *  (not a fixed-depth regex), since each status entry nests its own {description: ...} object;
     *  scoped to this shape, not a general JSON/YAML parser. */
    private static void parseFlowPathValue(final String flow, final Map<String, List<String>> methods) {
        final Matcher m = Pattern.compile("\\b(get|post|put|delete|patch)\\s*:\\s*\\{").matcher(flow);
        while (m.find()) {
            final int open = m.end() - 1, close = matchingBrace(flow, open);
            if (close < 0) continue;
            final List<String> codes = methods.computeIfAbsent(m.group(1), k -> new ArrayList<>());
            final Matcher c = Pattern.compile("\"(\\d{3})\"\\s*:").matcher(flow.substring(open, close + 1));
            while (c.find()) codes.add(c.group(1));
        }
    }

    private static int matchingBrace(final String s, final int openPos) {
        int depth = 0;
        for (int i = openPos; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    static String matchTemplate(final Map<String, Map<String, List<String>>> paths, final String method, final String path) {
        for (Map.Entry<String, Map<String, List<String>>> e : paths.entrySet()) {
            final String rx = "^" + e.getKey().replaceAll("\\{[^}]+\\}", "[^/]+") + "$";
            if (Pattern.compile(rx).matcher(path).matches() && e.getValue().containsKey(method.toLowerCase()))
                return e.getKey();
        }
        return null;
    }

    static String bodyOk(final Map<String, String> schema, JsonNode body) {
        if (body == null || !body.isObject()) return "body is not a JSON object";
        for (Map.Entry<String, String> k : schema.entrySet()) {
            final JsonNode v = body.get(k.getKey());
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
    public boolean f6ContractConformance(final Path ws) {
        if (calls.isEmpty()) {   // F6 alone: probe the endpoints the rung must have
            account("1.00");
            call("GET", "/prices/AAPL", null, null);
        }
        Map<String, Map<String, List<String>>> contract;
        try (var in = getClass().getResourceAsStream("/contract/openapi.yaml")) {
            contract = parseOpenApiPaths(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return fail("F6", "frozen contract unreadable: " + e); }
        final List<String> problems = new ArrayList<>();
        for (Object[] c : calls) {
            final String m = (String) c[0], p = (String) c[1];
            final int st = (Integer) c[2];
            final JsonNode body = (JsonNode) c[3];
            final String tpl = matchTemplate(contract, m, p);
            if (tpl == null) { problems.add(m + " " + p + " not in contract"); continue; }
            if (!contract.get(tpl).get(m.toLowerCase()).contains(String.valueOf(st))) {
                problems.add(m + " " + tpl + " -> " + st + " undeclared in contract");
                continue;
            }
            final Map<String, String> sch = SCHEMAS.get(m + " " + tpl + " " + st);
            if (sch != null) {
                final String err = bodyOk(sch, body);
                if (!err.isEmpty()) problems.add(m + " " + tpl + " " + st + ": " + err);
            }
        }
        Map<String, Map<String, List<String>>> spec = null;
        String where = null;
        for (Path p : StructureChecks.glob(ws, "**/*")) {
            final String n = p.getFileName().toString().toLowerCase();
            if ((n.startsWith("openapi") || n.startsWith("api")) && (n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json"))
                    && !StructureChecks.skip(p)) {
                try {
                    spec = parseOpenApiPaths(Files.readString(p));
                    where = ws.relativize(p).toString();
                    if (!spec.isEmpty()) break;
                } catch (Exception e) { log.debug("could not parse {} as an OpenAPI spec, trying the next candidate: {}", p, e.toString()); }
            }
        }
        if (spec == null || spec.isEmpty()) problems.add("agent ships no parseable OpenAPI spec with paths");
        else
            for (Object[] c : calls) {
                final String m = (String) c[0], p = (String) c[1];
                final int st = (Integer) c[2];
                final String tpl = matchTemplate(spec, m, p);
                if (tpl == null || !spec.get(tpl).get(m.toLowerCase()).contains(String.valueOf(st)))
                    problems.add("agent spec (" + where + ") does not declare " + m + " " + p + " -> " + st);
            }
        final List<String> uniq = problems.stream().distinct().sorted().toList();
        return check("F6", uniq.isEmpty(), calls.size() + " observed responses checked against contract+schemas+agent spec"
                + (uniq.isEmpty() ? "" : "; problems: " + uniq.subList(0, Math.min(3, uniq.size()))));
    }
}
