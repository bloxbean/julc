package com.bloxbean.cardano.julc.vm;

/** Raised when JuLC has no pinned ledger profile for a requested target. */
public final class UnsupportedLedgerTargetException extends IllegalArgumentException {
    public UnsupportedLedgerTargetException(String message) {
        super(message);
    }
}
