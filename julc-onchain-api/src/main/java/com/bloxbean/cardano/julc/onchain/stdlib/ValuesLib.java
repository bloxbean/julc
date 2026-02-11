package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.ledger.Value;

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

    /** Check if value a >= value b for ALL policy/token pairs (multi-asset). */
    public static boolean geqMultiAsset(Value a, Value b) {
        if (b == null || b.inner() == null) return true;
        for (Map.Entry<byte[], Map<byte[], BigInteger>> policyEntry : b.inner().entrySet()) {
            for (Map.Entry<byte[], BigInteger> tokenEntry : policyEntry.getValue().entrySet()) {
                BigInteger aAmount = assetOf(a, policyEntry.getKey(), tokenEntry.getKey());
                if (aAmount.compareTo(tokenEntry.getValue()) < 0) return false;
            }
        }
        return true;
    }

    /** Check if value a <= value b (multi-asset). */
    public static boolean leq(Value a, Value b) {
        return geqMultiAsset(b, a);
    }

    /** Check if two values are equal (multi-asset). */
    public static boolean eq(Value a, Value b) {
        return geqMultiAsset(a, b) && geqMultiAsset(b, a);
    }

    /** Check if a value is zero (all amounts == 0). */
    public static boolean isZero(Value value) {
        if (value == null || value.inner() == null) return true;
        for (Map.Entry<byte[], Map<byte[], BigInteger>> policyEntry : value.inner().entrySet()) {
            for (Map.Entry<byte[], BigInteger> tokenEntry : policyEntry.getValue().entrySet()) {
                if (tokenEntry.getValue().compareTo(BigInteger.ZERO) != 0) return false;
            }
        }
        return true;
    }

    /** Construct a Value containing a single asset. */
    public static Value singleton(byte[] policyId, byte[] tokenName, BigInteger amount) {
        var inner = new java.util.LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        var tokens = new java.util.LinkedHashMap<byte[], BigInteger>();
        tokens.put(tokenName, amount);
        inner.put(policyId, tokens);
        return new Value(inner);
    }

    /** Negate all amounts in a value. */
    public static Value negate(Value value) {
        if (value == null || value.inner() == null) return value;
        var inner = new java.util.LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        for (Map.Entry<byte[], Map<byte[], BigInteger>> pe : value.inner().entrySet()) {
            var tokens = new java.util.LinkedHashMap<byte[], BigInteger>();
            for (Map.Entry<byte[], BigInteger> te : pe.getValue().entrySet()) {
                tokens.put(te.getKey(), te.getValue().negate());
            }
            inner.put(pe.getKey(), tokens);
        }
        return new Value(inner);
    }

    /** Flatten a Value into a list of triples. Off-chain returns a list representation. */
    public static java.util.List<PlutusData> flatten(Value value) {
        var result = new java.util.ArrayList<PlutusData>();
        if (value == null || value.inner() == null) return result;
        for (Map.Entry<byte[], Map<byte[], BigInteger>> pe : value.inner().entrySet()) {
            for (Map.Entry<byte[], BigInteger> te : pe.getValue().entrySet()) {
                result.add(new PlutusData.Constr(0, java.util.List.of(
                        new PlutusData.BytesData(pe.getKey()),
                        new PlutusData.BytesData(te.getKey()),
                        new PlutusData.IntData(te.getValue()))));
            }
        }
        return result;
    }

    /** Add two Values together. */
    public static Value add(Value a, Value b) {
        var inner = new java.util.LinkedHashMap<byte[], Map<byte[], BigInteger>>();
        // Copy all from a
        if (a != null && a.inner() != null) {
            for (Map.Entry<byte[], Map<byte[], BigInteger>> pe : a.inner().entrySet()) {
                var tokens = new java.util.LinkedHashMap<byte[], BigInteger>();
                for (Map.Entry<byte[], BigInteger> te : pe.getValue().entrySet()) {
                    tokens.put(te.getKey(), te.getValue());
                }
                inner.put(pe.getKey(), tokens);
            }
        }
        // Add all from b
        if (b != null && b.inner() != null) {
            for (Map.Entry<byte[], Map<byte[], BigInteger>> pe : b.inner().entrySet()) {
                byte[] policy = pe.getKey();
                Map<byte[], BigInteger> existing = null;
                // Find existing policy by array equality
                for (Map.Entry<byte[], Map<byte[], BigInteger>> ie : inner.entrySet()) {
                    if (Arrays.equals(ie.getKey(), policy)) {
                        existing = ie.getValue();
                        break;
                    }
                }
                if (existing == null) {
                    var tokens = new java.util.LinkedHashMap<byte[], BigInteger>();
                    for (Map.Entry<byte[], BigInteger> te : pe.getValue().entrySet()) {
                        tokens.put(te.getKey(), te.getValue());
                    }
                    inner.put(policy, tokens);
                } else {
                    for (Map.Entry<byte[], BigInteger> te : pe.getValue().entrySet()) {
                        boolean found = false;
                        for (Map.Entry<byte[], BigInteger> et : existing.entrySet()) {
                            if (Arrays.equals(et.getKey(), te.getKey())) {
                                et.setValue(et.getValue().add(te.getValue()));
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            existing.put(te.getKey(), te.getValue());
                        }
                    }
                }
            }
        }
        return new Value(inner);
    }

    /** Subtract value b from value a. */
    public static Value subtract(Value a, Value b) {
        return add(a, negate(b));
    }
}
