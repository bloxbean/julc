package com.bloxbean.cardano.julc.verification.dsl.ir;

/** Closed, reviewed theorem domains. This is deliberately not an arbitrary assumption. */
public enum DslDomain {
    NONE,
    VALID_SPENDING_V3_PINNED,
    VALID_MINTING_V3_PINNED,
    VALID_REWARDING_V3_PINNED,
    VALID_CERTIFYING_V3_PINNED
}
