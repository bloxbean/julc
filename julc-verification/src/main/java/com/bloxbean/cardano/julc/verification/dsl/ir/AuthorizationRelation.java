package com.bloxbean.cardano.julc.verification.dsl.ir;

/** Closed set-like authorization relations over distinct key-hash identities. */
public enum AuthorizationRelation {
    ANY_SIGNED,
    ALL_SIGNED,
    NONE_SIGNED,
    AT_LEAST_SIGNED,
    EXACTLY_SIGNED,
    NO_UNEXPECTED_SIGNERS,
    EXACT_SIGNER_SET
}
