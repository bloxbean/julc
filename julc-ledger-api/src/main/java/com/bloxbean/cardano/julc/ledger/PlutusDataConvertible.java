package com.bloxbean.cardano.julc.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * Interface for ledger types that can be converted to PlutusData.
 * Each implementing type provides its own encoding convention
 * matching the Haskell/Scalus reference implementation.
 */
public interface PlutusDataConvertible {
    PlutusData toPlutusData();
}
