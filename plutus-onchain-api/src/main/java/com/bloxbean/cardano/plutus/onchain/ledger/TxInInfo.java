package com.bloxbean.cardano.plutus.onchain.ledger;

/**
 * An input to a transaction: reference to the UTXO and its resolved output.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record TxInInfo(TxOutRef outRef, TxOut resolved) {}
