package com.bloxbean.cardano.julc.verification.dsl.ir;

/** Types admitted by the first version of the symbolic verification IR. */
public enum DslType {
    BOOL,
    INTEGER,
    BYTE_STRING,
    DATA,
    SCRIPT_CONTEXT,
    TX_INFO,
    TX_OUT,
    VALUE,
    ADDRESS,
    CREDENTIAL,
    LIST_TX_OUT,
    LIST_BYTE_STRING
}
