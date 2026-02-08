package com.bloxbean.cardano.plutus.onchain.ledger;

import java.math.BigInteger;
import java.util.Map;

/**
 * A multi-asset value: Map of PolicyId -> Map of TokenName -> quantity.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record Value(Map<byte[], Map<byte[], BigInteger>> inner) {}
