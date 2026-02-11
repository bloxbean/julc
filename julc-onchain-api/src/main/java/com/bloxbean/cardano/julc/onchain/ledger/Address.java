package com.bloxbean.cardano.julc.onchain.ledger;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * A Cardano address with payment credential and optional staking credential.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record Address(Credential credential, PlutusData stakingCredential) {}
