package com.bloxbean.cardano.julc.verification.dsl.ir;

/** Types admitted by the first version of the symbolic verification IR. */
public enum DslType {
    BOOL,
    INTEGER,
    BYTE_STRING,
    DATA,
    SCRIPT_CONTEXT,
    TX_INFO,
    POLICY_ID,
    MINT_VALUE,
    TX_OUT_REF,
    TX_OUT,
    VALUE,
    ADDRESS,
    CREDENTIAL,
    WITHDRAWALS,
    WITHDRAWAL_ENTRY,
    LIST_TX_OUT,
    LIST_TX_IN_INFO,
    LIST_BYTE_STRING
}
