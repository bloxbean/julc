package com.bloxbean.cardano.plutus.onchain.ledger;

import java.math.BigInteger;

/**
 * Reference to a transaction output (transaction ID + output index).
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record TxOutRef(byte[] txId, BigInteger index) {}
