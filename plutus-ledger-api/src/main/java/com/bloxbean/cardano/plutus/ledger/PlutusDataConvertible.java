package com.bloxbean.cardano.plutus.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * Interface for ledger types that can be converted to PlutusData.
 * Each implementing type provides its own encoding convention
 * matching the Haskell/Scalus reference implementation.
 */
public interface PlutusDataConvertible {
    PlutusData toPlutusData();
}
