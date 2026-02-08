package com.bloxbean.cardano.plutus.onchain.ledger;

import com.bloxbean.cardano.plutus.core.PlutusData;

/**
 * The script context passed to validators (V3).
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record ScriptContext(TxInfo txInfo, PlutusData redeemer, ScriptInfo scriptInfo) {}
