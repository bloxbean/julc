package com.bloxbean.cardano.julc.onchain.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * A transaction output with address, value, datum, and optional reference script.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record TxOut(Address address, Value value, OutputDatum datum, PlutusData referenceScript) {}
