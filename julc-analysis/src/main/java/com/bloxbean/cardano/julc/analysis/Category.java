package com.bloxbean.cardano.julc.analysis;

/**
 * Vulnerability category for Cardano smart contract findings.
 */
public enum Category {
    DOUBLE_SATISFACTION,
    MISSING_AUTHORIZATION,
    VALUE_LEAK,
    TIME_VALIDATION,
    STATE_TRANSITION,
    UNBOUNDED_EXECUTION,
    HARDCODED_CREDENTIAL,
    DATUM_INTEGRITY,
    GENERAL
}
