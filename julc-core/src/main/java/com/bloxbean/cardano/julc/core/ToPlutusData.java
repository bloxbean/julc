package com.bloxbean.cardano.julc.core;

/**
 * A value that has a canonical off-chain representation as {@link PlutusData}.
 * <p>
 * This interface lives in {@code julc-core} so core collection types can expose
 * conversion without depending on the ledger API.
 */
public interface ToPlutusData {

    /** Convert this value to its canonical Plutus Data representation. */
    PlutusData toPlutusData();
}
