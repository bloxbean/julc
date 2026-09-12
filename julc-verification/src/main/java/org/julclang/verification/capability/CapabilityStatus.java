package org.julclang.verification.capability;

/** Honest support state for one pinned ledger-model surface. */
public enum CapabilityStatus {
    TYPED,
    RAW_DATA_ONLY,
    UNSUPPORTED_IR,
    UNSUPPORTED_SOLVER,
    NOT_MODELED_UPSTREAM
}
