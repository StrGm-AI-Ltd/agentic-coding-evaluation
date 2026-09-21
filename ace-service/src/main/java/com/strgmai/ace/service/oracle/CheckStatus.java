package com.strgmai.ace.service.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Port of registry.STATUSES. SKIPPED = infra unavailable BEFORE the check ran; INFRA = infra failed
 *  mid-way. Both are excluded from the denominator but BLOCK the headline score. NOT_ATTEMPTED
 *  (feature simply absent) counts as FAIL. */
public enum CheckStatus { PASS, FAIL, NOT_ATTEMPTED, SKIPPED, INFRA;

    private static final Logger log = LoggerFactory.getLogger(CheckStatus.class);

    public boolean unscored() { return this == SKIPPED || this == INFRA; }
    public static CheckStatus parse(final String s) {
        if (s == null) return FAIL;   // a null status is a FAIL like any unknown one, never an NPE
        try { return valueOf(s.toUpperCase().trim()); }
        catch (IllegalArgumentException e) {
            log.warn("unknown check status '{}', treating as FAIL", s);   // never silence
            return FAIL;
        }
    }
}
