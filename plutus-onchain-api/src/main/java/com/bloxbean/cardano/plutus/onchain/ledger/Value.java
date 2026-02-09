package com.bloxbean.cardano.plutus.onchain.ledger;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A multi-asset value: Map of PolicyId -> Map of TokenName -> quantity.
 * <p>
 * This is an IDE stub. The compiler uses the schema from LedgerTypeRegistry.
 */
public record Value(Map<byte[], Map<byte[], BigInteger>> inner) {

    /** Create a Value containing only lovelace (ADA). */
    public static Value lovelace(BigInteger amount) {
        var tokenMap = new LinkedHashMap<byte[], BigInteger>();
        tokenMap.put(new byte[0], amount);
        var outer = new LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        outer.put(new byte[0], tokenMap);
        return new Value(outer);
    }

    /** Create a zero-value (0 lovelace). */
    public static Value zero() {
        return lovelace(BigInteger.ZERO);
    }

    /** Create a Value containing a single native asset. */
    public static Value of(byte[] policyId, byte[] tokenName, BigInteger amount) {
        var tokenMap = new LinkedHashMap<byte[], BigInteger>();
        tokenMap.put(tokenName, amount);
        var outer = new LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        outer.put(policyId, tokenMap);
        return new Value(outer);
    }

    /** Create a Value containing lovelace and a single native asset. */
    public static Value withLovelace(BigInteger lovelace, byte[] policyId, byte[] tokenName, BigInteger amount) {
        var adaTokenMap = new LinkedHashMap<byte[], BigInteger>();
        adaTokenMap.put(new byte[0], lovelace);
        var assetTokenMap = new LinkedHashMap<byte[], BigInteger>();
        assetTokenMap.put(tokenName, amount);
        var outer = new LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        outer.put(new byte[0], adaTokenMap);
        outer.put(policyId, assetTokenMap);
        return new Value(outer);
    }
}
