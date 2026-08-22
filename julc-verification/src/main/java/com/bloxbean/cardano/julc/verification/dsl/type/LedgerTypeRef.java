package com.bloxbean.cardano.julc.verification.dsl.type;

import java.util.Objects;

/** Closed reference to a type in the pinned CardanoLedgerApiBlaster V3 model. */
public record LedgerTypeRef(LedgerKind ledgerType) implements VerificationTypeRef {
    public LedgerTypeRef {
        ledgerType = Objects.requireNonNull(ledgerType, "ledgerType");
    }

    public enum LedgerKind {
        SCRIPT_CONTEXT,
        TX_INFO,
        TX_IN_INFO,
        TX_OUT_REF,
        TX_ID,
        TX_OUT,
        VALUE,
        MINT_VALUE,
        VALUE_DELTA,
        VALUE_POLICY_ENTRY,
        VALUE_TOKEN_ENTRY,
        TOKEN_NAME,
        ADDRESS,
        CREDENTIAL,
        STAKING_CREDENTIAL,
        OUTPUT_DATUM,
        DATUM_HASH,
        SCRIPT_HASH,
        PUB_KEY_HASH,
        SCRIPT_PURPOSE,
        CURRENCY_SYMBOL,
        TX_CERT,
        DELEGATEE,
        DREP,
        OPAQUE_VOTER,
        OPAQUE_PROPOSAL
    }
}
