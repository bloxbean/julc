package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.onchain.ledger.Value;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;

/**
 * On-chain Value and asset operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 * <p>
 * Note: byte[] keys in Map don't have correct equals()/hashCode().
 * All lookups must iterate with Arrays.equals(), never use Map.get().
 */
public final class ValuesLib {

    private static final byte[] EMPTY = new byte[0];

    private ValuesLib() {}

    /** Extract lovelace (ADA) amount from a Value. */
    public static BigInteger lovelaceOf(Value value) {
        return assetOf(value, EMPTY, EMPTY);
    }

    /** Check if value {@code a} is greater than or equal to value {@code b} (by lovelace). */
    public static boolean geq(Value a, Value b) {
        return lovelaceOf(a).compareTo(lovelaceOf(b)) >= 0;
    }

    /** Extract the amount of a specific asset from a Value. */
    public static BigInteger assetOf(Value value, byte[] policyId, byte[] tokenName) {
        if (value == null || value.inner() == null) return BigInteger.ZERO;
        for (Map.Entry<byte[], Map<byte[], BigInteger>> policyEntry : value.inner().entrySet()) {
            if (Arrays.equals(policyEntry.getKey(), policyId)) {
                Map<byte[], BigInteger> tokens = policyEntry.getValue();
                if (tokens != null) {
                    for (Map.Entry<byte[], BigInteger> tokenEntry : tokens.entrySet()) {
                        if (Arrays.equals(tokenEntry.getKey(), tokenName)) {
                            return tokenEntry.getValue() != null ? tokenEntry.getValue() : BigInteger.ZERO;
                        }
                    }
                }
                return BigInteger.ZERO;
            }
        }
        return BigInteger.ZERO;
    }
}
