package com.strgmai.ace.service.oracle;

import java.util.*;

/** Port of run_oracle.score(): independent verification with a FIXED denominator.
 *  Invariants: 1. every id gets exactly one record, missing => FAIL (a crashing checker can never
 *  shrink the denominator and raise the score). 2. NOT_ATTEMPTED counts as FAIL. 3. SKIPPED/INFRA
 *  are excluded from the denominator but BLOCK the headline: you get partial_score_pct + the
 *  denominator, never weighted_score_pct. 4. two scores: functional (F1-F9, PRIMARY) and composite. */
public final class Scorer {
    private Scorer() {}

    public static Map<String, Object> score(Collection<CheckResult> records) {
        List<CheckResult> all = new ArrayList<>(records);
        List<CheckResult> counted = all.stream().filter(r -> !r.status().unscored()).toList();
        List<String> unscored = all.stream().filter(r -> r.status().unscored()).map(r -> r.id().name()).toList();
        int denom = counted.stream().mapToInt(r -> r.id().weight).sum();
        int got = counted.stream().filter(r -> r.status() == CheckStatus.PASS).mapToInt(r -> r.id().weight).sum();
        int fullDenom = all.stream().mapToInt(r -> r.id().weight).sum();
        List<CheckResult> fAll = all.stream().filter(r -> CheckId.functional().contains(r.id())).toList();
        List<CheckResult> fCounted = fAll.stream().filter(r -> !r.status().unscored()).toList();
        int fDenom = fCounted.stream().mapToInt(r -> r.id().weight).sum();
        int fGot = fCounted.stream().filter(r -> r.status() == CheckStatus.PASS).mapToInt(r -> r.id().weight).sum();

        Map<String, Map<String, Object>> byCat = new LinkedHashMap<>();
        for (CheckResult r : all) {
            Map<String, Object> d = byCat.computeIfAbsent(r.id().category, x -> new LinkedHashMap<>());
            d.merge("pass", r.status() == CheckStatus.PASS ? 1 : 0, (a, b) -> (int) a + (int) b);
            d.merge("fail", r.status() != CheckStatus.PASS && !r.status().unscored() ? 1 : 0, (a, b) -> (int) a + (int) b);
            d.merge("unscored", r.status().unscored() ? 1 : 0, (a, b) -> (int) a + (int) b);
            d.merge("weight_max", r.id().weight, (a, b) -> (int) a + (int) b);
            d.merge("weight_got", r.status() == CheckStatus.PASS ? r.id().weight : 0, (a, b) -> (int) a + (int) b);
        }

        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("schema_version", com.strgmai.ace.service.config.BenchProperties.RESULT_SCHEMA);   // poolable runs are recognised by it
        rep.put("checks_expected", all.size());
        rep.put("checks_reported", all.size());
        rep.put("passed", (int) counted.stream().filter(r -> r.status() == CheckStatus.PASS).count());
        rep.put("points_got", got);
        rep.put("functional_ids", fAll.stream().map(r -> r.id().name()).toList());
        rep.put("functional_points_got", fGot);
        rep.put("not_attempted", (int) counted.stream().filter(r -> r.status() == CheckStatus.NOT_ATTEMPTED).count());
        rep.put("skipped", all.stream().filter(r -> r.status() == CheckStatus.SKIPPED).map(r -> r.id().name()).toList());
        rep.put("infra", all.stream().filter(r -> r.status() == CheckStatus.INFRA).map(r -> r.id().name()).toList());
        rep.put("denominator", denom);
        rep.put("full_denominator", fullDenom);
        // counted denominator (consistent with `denominator` above) - fpct is computed over fDenom,
        // so on a partial run the full value would mislead any consumer that validates the stored points
        rep.put("functional_denominator", fDenom);
        rep.put("functional_full_denominator", fAll.stream().mapToInt(r -> r.id().weight).sum());
        rep.put("by_category", byCat);
        double pct = denom > 0 ? round1(100.0 * got / denom) : 0.0;   // Python round(x, 1) is banker's rounding
        Double fpct = fDenom > 0 ? round1(100.0 * fGot / fDenom) : null;
        if (!unscored.isEmpty()) {
            rep.put("partial_score_pct", pct);
            rep.put("weighted_score_pct", null);
            rep.put("functional_score_pct", null);
            rep.put("partial_functional_score_pct", fpct);
            rep.put("note", "PARTIAL: " + unscored.size() + " check(s) unscored (" + String.join(",", unscored) + "); denominator "
                    + denom + "/" + fullDenom + ". Not comparable to a full run.");
        } else {
            rep.put("weighted_score_pct", pct);
            rep.put("functional_score_pct", fpct);     // null when the rung has no F checks
        }
        return rep;
    }

    static double round1(double x) {
        return java.math.BigDecimal.valueOf(x).setScale(1, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    /** convenience for callers holding one record per id of a subset */
    public static List<CheckResult> missingAsFail(List<CheckId> owned, Map<CheckId, CheckResult> got, String why) {
        List<CheckResult> out = new ArrayList<>();
        for (CheckId id : owned)
            out.add(got.containsKey(id) ? got.get(id) : CheckResult.fail(id, "checker missing/crashed: " + why));
        return out;
    }
}
