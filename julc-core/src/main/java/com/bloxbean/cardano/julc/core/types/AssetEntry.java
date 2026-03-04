package com.bloxbean.cardano.julc.core.types;

import java.math.BigInteger;

/**
 * A typed triple representing a single asset entry from a flattened Value.
 * <p>
 * On-chain: compiled to {@code ConstrData(0, [BData(policyId), BData(tokenName), IData(amount)])}.
 * Field access auto-unwraps: {@code entry.policyId()} returns {@code byte[]} directly.
 * <p>
 * Returned by {@code ValuesLib.flatten()} for type-safe iteration over Value entries.
 */
public record AssetEntry(byte[] policyId, byte[] tokenName, BigInteger amount) {}
