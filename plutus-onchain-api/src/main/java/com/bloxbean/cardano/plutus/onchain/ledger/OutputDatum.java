package com.bloxbean.cardano.plutus.onchain.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * The datum attached to a transaction output.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public sealed interface OutputDatum {
    record NoOutputDatum() implements OutputDatum {}
    record OutputDatumHash(byte[] hash) implements OutputDatum {}
    record OutputDatumInline(PlutusData datum) implements OutputDatum {}
}
