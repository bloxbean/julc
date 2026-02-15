package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.Value;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * On-chain Value and asset operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
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
        for (var pid : value.inner().keys()) {
            if (Arrays.equals(pid.hash(), policyId)) {
                var tokens = value.inner().get(pid);
                if (tokens != null) {
                    for (var tn : tokens.keys()) {
                        if (Arrays.equals(tn.name(), tokenName)) {
                            BigInteger amount = tokens.get(tn);
                            return amount != null ? amount : BigInteger.ZERO;
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
        for (var pid : b.inner().keys()) {
            var tokens = b.inner().get(pid);
            for (var tn : tokens.keys()) {
                BigInteger aAmount = assetOf(a, pid.hash(), tn.name());
                if (aAmount.compareTo(tokens.get(tn)) < 0) return false;
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
        for (var pid : value.inner().keys()) {
            var tokens = value.inner().get(pid);
            for (var tn : tokens.keys()) {
                if (tokens.get(tn).compareTo(BigInteger.ZERO) != 0) return false;
            }
        }
        return true;
    }

    /** Construct a Value containing a single asset. */
    public static Value singleton(byte[] policyId, byte[] tokenName, BigInteger amount) {
        return new Value(JulcMap.of(new PolicyId(policyId), JulcMap.of(new TokenName(tokenName), amount)));
    }

    /** Negate all amounts in a value. */
    public static Value negate(Value value) {
        if (value == null || value.inner() == null) return value;
        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcMap.empty();
        for (var pid : value.inner().keys()) {
            var tokens = value.inner().get(pid);
            JulcMap<TokenName, BigInteger> negatedTokens = JulcMap.empty();
            for (var tn : tokens.keys()) {
                negatedTokens = negatedTokens.insert(tn, tokens.get(tn).negate());
            }
            result = result.insert(pid, negatedTokens);
        }
        return new Value(result);
    }

    /** Flatten a Value into a list of triples. Off-chain returns a list representation. */
    public static java.util.List<PlutusData> flatten(Value value) {
        var result = new java.util.ArrayList<PlutusData>();
        if (value == null || value.inner() == null) return result;
        for (var pid : value.inner().keys()) {
            var tokens = value.inner().get(pid);
            for (var tn : tokens.keys()) {
                result.add(new PlutusData.ConstrData(0, java.util.List.of(
                        new PlutusData.BytesData(pid.hash()),
                        new PlutusData.BytesData(tn.name()),
                        new PlutusData.IntData(tokens.get(tn)))));
            }
        }
        return result;
    }

    /** Add two Values together. */
    public static Value add(Value a, Value b) {
        // Start with a's entries
        JulcMap<PolicyId, JulcMap<TokenName, BigInteger>> result = JulcMap.empty();
        if (a != null && a.inner() != null) {
            for (var pid : a.inner().keys()) {
                var tokens = a.inner().get(pid);
                JulcMap<TokenName, BigInteger> copy = JulcMap.empty();
                for (var tn : tokens.keys()) {
                    copy = copy.insert(tn, tokens.get(tn));
                }
                result = result.insert(pid, copy);
            }
        }
        // Add all from b
        if (b != null && b.inner() != null) {
            for (var pid : b.inner().keys()) {
                var bTokens = b.inner().get(pid);
                // Find existing by byte comparison
                PolicyId matchedPid = null;
                for (var existingPid : result.keys()) {
                    if (Arrays.equals(existingPid.hash(), pid.hash())) {
                        matchedPid = existingPid;
                        break;
                    }
                }
                if (matchedPid == null) {
                    JulcMap<TokenName, BigInteger> copy = JulcMap.empty();
                    for (var tn : bTokens.keys()) {
                        copy = copy.insert(tn, bTokens.get(tn));
                    }
                    result = result.insert(pid, copy);
                } else {
                    var existing = result.get(matchedPid);
                    for (var tn : bTokens.keys()) {
                        // Find existing token by byte comparison
                        TokenName matchedTn = null;
                        for (var existingTn : existing.keys()) {
                            if (Arrays.equals(existingTn.name(), tn.name())) {
                                matchedTn = existingTn;
                                break;
                            }
                        }
                        if (matchedTn == null) {
                            existing = existing.insert(tn, bTokens.get(tn));
                        } else {
                            BigInteger sum = existing.get(matchedTn).add(bTokens.get(tn));
                            existing = existing.delete(matchedTn).insert(matchedTn, sum);
                        }
                    }
                    result = result.delete(matchedPid).insert(matchedPid, existing);
                }
            }
        }
        return new Value(result);
    }

    /** Subtract value b from value a. */
    public static Value subtract(Value a, Value b) {
        return add(a, negate(b));
    }

    /** Internal zero-overhead singleton for cost-sensitive on-chain use. */
    public static Value _singleton(PlutusData.BytesData policyId, PlutusData.BytesData tokenName, BigInteger amount) {
        return singleton(policyId.value(), tokenName.value(), amount);
    }

    /** Internal zero-overhead assetOf for cost-sensitive on-chain use. */
    public static BigInteger _assetOf(Value value, PlutusData.BytesData policyId, PlutusData.BytesData tokenName) {
        return assetOf(value, policyId.value(), tokenName.value());
    }
}
