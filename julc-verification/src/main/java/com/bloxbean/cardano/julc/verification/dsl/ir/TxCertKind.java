package com.bloxbean.cardano.julc.verification.dsl.ir;

/** Closed constructor inventory for the pinned Conway-era V3 TxCert model. */
public enum TxCertKind {
    REG_STAKING,
    UNREG_STAKING,
    DELEG_STAKING,
    REG_DELEG,
    REG_DREP,
    UPDATE_DREP,
    UNREG_DREP,
    POOL_REGISTER,
    POOL_RETIRE,
    AUTH_HOT_COMMITTEE,
    RESIGN_COLD_COMMITTEE
}
