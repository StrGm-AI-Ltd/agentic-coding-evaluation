package com.agentbench.oracle;

/** Port of registry.STATUSES. SKIPPED = infra unavailable BEFORE the check ran; INFRA = infra failed
 *  mid-way. Both are excluded from the denominator but BLOCK the headline score. NOT_ATTEMPTED
 *  (feature simply absent) counts as FAIL. */
public enum CheckStatus { PASS, FAIL, NOT_ATTEMPTED, SKIPPED, INFRA;

    public boolean unscored() { return this == SKIPPED || this == INFRA; }
    public static CheckStatus parse(String s) {
        try { return valueOf(s.toUpperCase().trim()); }
        catch (IllegalArgumentException e) { return FAIL; }   // an unknown status from a checker is a FAIL, never silence
    }
}
