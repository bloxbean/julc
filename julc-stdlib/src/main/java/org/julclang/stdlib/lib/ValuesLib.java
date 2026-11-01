package org.julclang.stdlib.lib;

import org.julclang.core.PlutusData;
import org.julclang.core.types.AssetEntry;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;

import java.util.Optional;

import java.math.BigInteger;

/**
 * Value manipulation operations compiled from Java source to UPLC.
 * <p>
 * In Plutus, a Value is represented as:
 * {@code Map<ByteString, Map<ByteString, Integer>>}
 * (map from currency symbol to map from token name to amount).
 * Lovelace is stored under empty bytestring policy and token name.
 */
@OnchainLibrary
public class ValuesLib {

    /**
     * The lovelace (ADA) amount of a Value: its amount under the empty policy and the empty token name, by key, wherever
     * that entry is. Returns 0 if there is none.
     */
    public static BigInteger lovelaceOf(Value value) {
        return value.lovelaceOf();
    }

    /** Check if a policy ID (as bytes) exists in a Value. */
    public static boolean containsPolicy(Value value, byte[] policyId) {
        return _containsPolicy(value, Builtins.bData(policyId));
    }

    /** Check if a policy ID (as Data) exists in a Value. */
    @SuppressWarnings("unchecked")
    public static boolean _containsPolicy(Value value, PlutusData policyId) {
        return ((JulcMap)(Object) value).containsKey(policyId);
    }

    /** Checks if lovelaceOf(a) >= lovelaceOf(b). */
    public static boolean geq(Value a, Value b) {
        var aLov = lovelaceOf(a);
        var bLov = lovelaceOf(b);
        return aLov.compareTo(bLov) >= 0;
    }

    /** Extracts the amount of a specific asset. Returns 0 if not found. */
    @SuppressWarnings("unchecked")
    public static BigInteger assetOf(Value value, byte[] policyId, byte[] tokenName) {
        return _assetOf(value,
                Builtins.bData(policyId),
                Builtins.bData(tokenName));
    }

    /** Internal zero-overhead assetOf for cost-sensitive on-chain use. */
    public static BigInteger _assetOf(Value value, PlutusData.BytesData policyId, PlutusData.BytesData tokenName) {
        var outerPairs = Builtins.unMapData(value);
        BigInteger result = BigInteger.ZERO;
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyId)) {
                PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
                result = findTokenAmount(innerMap, tokenName);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Search inner token map for a token name, return amount or 0. */
    public static BigInteger findTokenAmount(PlutusData.MapData innerPairs, PlutusData.BytesData tokenName) {
        BigInteger result = BigInteger.ZERO;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), tokenName)) {
                result = Builtins.unIData(Builtins.sndPair(pair));
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Checks if value a >= value b for ALL policy/token pairs (multi-asset). */
    public static boolean geqMultiAsset(Value a, Value b) {
        var result = true;
        var outerPairs = Builtins.unMapData(b);
        PlutusData outerCurrent = outerPairs;
        while (!Builtins.nullList(outerCurrent)) {
            var outerPair = Builtins.headList(outerCurrent);
            if (checkPolicyGeq(a, outerPair)) {
                outerCurrent = Builtins.tailList(outerCurrent);
            } else {
                result = false;
                outerCurrent = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Check all tokens under a policy pair have sufficient amounts in value a. */
    public static boolean checkPolicyGeq(Value a, PlutusData outerPair) {
        var policyKey = Builtins.fstPair(outerPair);
        var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
        var result = true;
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var bAmount = Builtins.unIData(Builtins.sndPair(pair));
            var aAmount = _assetOf(a, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            if (aAmount.compareTo(bAmount) >= 0) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Checks if value a <= value b (multi-asset). */
    public static boolean leq(Value a, Value b) {
        return geqMultiAsset(b, a);
    }

    /** Checks if two values are equal (multi-asset). */
    public static boolean eq(Value a, Value b) {
        if (geqMultiAsset(a, b)) {
            return geqMultiAsset(b, a);
        } else {
            return false;
        }
    }

    /** Checks if a value is zero (all amounts == 0). */
    public static boolean isZero(Value value) {
        var result = true;
        var outerPairs = Builtins.unMapData(value);
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (isTokenMapZero((PlutusData.MapData) Builtins.sndPair(outerPair))) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Check if all amounts in a token map pair list are zero. */
    public static boolean isTokenMapZero(PlutusData.MapData innerPairs) {
        var result = true;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var amt = Builtins.unIData(Builtins.sndPair(pair));
            if (amt.equals(BigInteger.ZERO)) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Constructs a Value containing a single asset: Map[(policy, Map[(token, amount)])]. */
    @SuppressWarnings("unchecked")
    public static Value singleton(byte[] policyId, byte[] tokenName, BigInteger amount) {
        return _singleton(
                Builtins.bData(policyId),
                Builtins.bData(tokenName),
                amount);
    }

    /** Internal zero-overhead singleton for cost-sensitive on-chain use. */
    public static Value _singleton(PlutusData.BytesData policyId, PlutusData.BytesData tokenName, BigInteger amount) {
        var emptyPairList = Builtins.mkNilPairData();
        var innerPair = Builtins.mkPairData(tokenName, Builtins.iData(amount));
        var innerList = Builtins.mkCons(innerPair, emptyPairList);
        var innerMap = Builtins.mapData(innerList);
        var outerPair = Builtins.mkPairData(policyId, innerMap);
        var outerList = Builtins.mkCons(outerPair, emptyPairList);
        return (Value)(Object) Builtins.mapData(outerList);
    }

    /** Negates all amounts in a value, keeping its order. */
    public static Value negate(Value value) {
        return (Value)(Object) Builtins.mapData(_negatePolicies(Builtins.unMapData(value)));
    }

    /** Each policy of a pair list with its amounts negated, in order. */
    public static PlutusData _negatePolicies(PlutusData policies) {
        if (Builtins.nullList(policies)) {
            return policies;
        }
        var pair = Builtins.headList(policies);
        var tokens = negateTokenMap((PlutusData.MapData) Builtins.sndPair(pair));
        return Builtins.mkCons(Builtins.mkPairData(Builtins.fstPair(pair), tokens),
                _negatePolicies(Builtins.tailList(policies)));
    }

    /** Negate all amounts in a token map, keeping its order. */
    public static PlutusData.MapData negateTokenMap(PlutusData.MapData innerPairs) {
        return Builtins.mapData(_negateAmounts(Builtins.unMapData(innerPairs)));
    }

    /** Each token of a pair list with its amount negated, in order. */
    public static PlutusData _negateAmounts(PlutusData tokens) {
        if (Builtins.nullList(tokens)) {
            return tokens;
        }
        var pair = Builtins.headList(tokens);
        var amount = Builtins.unIData(Builtins.sndPair(pair));
        return Builtins.mkCons(Builtins.mkPairData(Builtins.fstPair(pair), Builtins.iData(amount.negate())),
                _negateAmounts(Builtins.tailList(tokens)));
    }

    /**
     * Flattens a Value into a list of (policy, token, amount) triples as ConstrData(0, [p, t, amt]).
     * <p>
     * Each element has the same layout as {@link AssetEntry}: {@code ConstrData(0, [BData, BData, IData])}.
     * Cast the result for typed field access:
     * <pre>{@code
     * for (AssetEntry entry : (JulcList<AssetEntry>)(Object) ValuesLib.flatten(txInfo.mint())) {
     *     byte[] policy = entry.policyId();
     *     byte[] name = entry.tokenName();
     *     BigInteger qty = entry.amount();
     * }
     * }</pre>
     * Or use {@link #flattenTyped(Value)} for a typed alternative.
     *
     * Note: Split into separate step methods to avoid nested while loops
     * (compiler limitation — while-loop calling a function with a while-loop).
     */
    @SuppressWarnings("unchecked")
    public static JulcList<PlutusData> flatten(Value value) {
        return flattenStep(Builtins.unMapData(value), JulcList.empty());
    }

    /**
     * Typed variant of {@link #flatten(Value)} that returns {@code JulcList<AssetEntry>}.
     * Each element provides typed field access: {@code entry.policyId()}, {@code entry.tokenName()},
     * {@code entry.amount()}.
     */
    @SuppressWarnings("unchecked")
    public static JulcList<AssetEntry> flattenTyped(Value value) {
        return (JulcList<AssetEntry>)(Object) flatten(value);
    }

    /** Process one policy from the outer map, then recurse on the rest. */
    public static JulcList<PlutusData> flattenStep(PlutusData outerPairs, JulcList<PlutusData> acc) {
        if (Builtins.nullList(outerPairs)) {
            return ListsLib.reverse(acc);
        }
        var outerPair = Builtins.headList(outerPairs);
        var policyData = Builtins.fstPair(outerPair);
        PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
        JulcList<PlutusData> updated = flattenPolicy(policyData, innerMap, acc);
        return flattenStep(Builtins.tailList(outerPairs), updated);
    }

    /** Flatten a single policy's token entries into the accumulator list. */
    public static JulcList<PlutusData> flattenPolicy(PlutusData policyData, PlutusData.MapData innerPairs, JulcList<PlutusData> acc) {
        JulcList<PlutusData> result = acc;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenData = Builtins.fstPair(pair);
            var amountData = Builtins.sndPair(pair);
            var tripleFields = Builtins.mkCons(policyData, Builtins.mkCons(tokenData, Builtins.mkCons(amountData, Builtins.mkNilData())));
            var triple = Builtins.constrData(0, tripleFields);
            result = result.prepend(triple);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /**
     * Adds two Values by key: each (policy, token) appears once, with the sum of its amounts, and a policy in both has one
     * entry, with their tokens merged. An amount of 0 stays an entry. Inputs sorted by key at both levels give a result
     * sorted at both levels; for any other input, every entry is still there once.
     * <p>
     * Complexity: O(P * P + T * T) for P policies and T tokens under a policy, since each key is looked up in the other
     * Value. Suitable for typical on-chain Values, but may be expensive for Values with many distinct policies.
     */
    public static Value add(Value a, Value b) {
        var aPolicies = Builtins.unMapData(a);
        var bPolicies = Builtins.unMapData(b);
        return (Value)(Object) Builtins.mapData(
                _mergeByKey(_addPolicies(aPolicies, bPolicies), _entriesNotIn(bPolicies, aPolicies)));
    }

    /** Each policy of {@code policies}, in order, with the tokens {@code other} holds under it added. */
    public static PlutusData _addPolicies(PlutusData policies, PlutusData other) {
        if (Builtins.nullList(policies)) {
            return policies;
        }
        var pair = Builtins.headList(policies);
        var policy = Builtins.fstPair(pair);
        var tokens = _addTokens((PlutusData.MapData) Builtins.sndPair(pair), _tokensOf(other, policy));
        return Builtins.mkCons(Builtins.mkPairData(policy, tokens), _addPolicies(Builtins.tailList(policies), other));
    }

    /** The tokens {@code policies} holds under {@code policy}, or an empty map. */
    public static PlutusData.MapData _tokensOf(PlutusData policies, PlutusData policy) {
        if (Builtins.nullList(policies)) {
            return Builtins.mapData(policies);
        }
        var pair = Builtins.headList(policies);
        if (Builtins.equalsData(Builtins.fstPair(pair), policy)) {
            return (PlutusData.MapData) Builtins.sndPair(pair);
        }
        return _tokensOf(Builtins.tailList(policies), policy);
    }

    /** Two token maps added by key. */
    public static PlutusData.MapData _addTokens(PlutusData.MapData a, PlutusData.MapData b) {
        var aTokens = Builtins.unMapData(a);
        return Builtins.mapData(_mergeByKey(_addAmounts(aTokens, b), _entriesNotIn(Builtins.unMapData(b), aTokens)));
    }

    /** Each token of {@code tokens}, in order, with its amount in {@code other} added. */
    public static PlutusData _addAmounts(PlutusData tokens, PlutusData.MapData other) {
        if (Builtins.nullList(tokens)) {
            return tokens;
        }
        var pair = Builtins.headList(tokens);
        var token = Builtins.fstPair(pair);
        var amount = Builtins.unIData(Builtins.sndPair(pair)).add(findTokenAmount(other, (PlutusData.BytesData) token));
        return Builtins.mkCons(Builtins.mkPairData(token, Builtins.iData(amount)),
                _addAmounts(Builtins.tailList(tokens), other));
    }

    /** The entries of a pair list whose key the pair list {@code other} does not hold, in order. */
    public static PlutusData _entriesNotIn(PlutusData pairs, PlutusData other) {
        if (Builtins.nullList(pairs)) {
            return pairs;
        }
        var pair = Builtins.headList(pairs);
        var rest = _entriesNotIn(Builtins.tailList(pairs), other);
        if (_hasKey(other, Builtins.fstPair(pair))) {
            return rest;
        }
        return Builtins.mkCons(pair, rest);
    }

    /** Whether a pair list holds an entry of {@code key}. */
    public static boolean _hasKey(PlutusData pairs, PlutusData key) {
        if (Builtins.nullList(pairs)) {
            return false;
        }
        if (Builtins.equalsData(Builtins.fstPair(Builtins.headList(pairs)), key)) {
            return true;
        }
        return _hasKey(Builtins.tailList(pairs), key);
    }

    /**
     * Merges two pair lists with no key in common, taking the lesser key first. Two sorted lists give a sorted list, and
     * any others keep every entry.
     */
    public static PlutusData _mergeByKey(PlutusData a, PlutusData b) {
        if (Builtins.nullList(a)) {
            return b;
        }
        if (Builtins.nullList(b)) {
            return a;
        }
        var first = Builtins.headList(a);
        var second = Builtins.headList(b);
        if (Builtins.lessThanByteString(Builtins.unBData(Builtins.fstPair(second)), Builtins.unBData(Builtins.fstPair(first)))) {
            return Builtins.mkCons(second, _mergeByKey(a, Builtins.tailList(b)));
        }
        return Builtins.mkCons(first, _mergeByKey(Builtins.tailList(a), b));
    }

    /** Subtracts value b from value a: add(a, negate(b)). */
    public static Value subtract(Value a, Value b) {
        return add(a, negate(b));
    }

    // =========================================================================
    // Mint helpers — common patterns for validating minting/burning transactions
    // =========================================================================

    /**
     * Count tokens with a specific quantity under a specific policy in a Value/mint.
     * E.g., count how many tokens were minted with qty=1 under a given policy.
     */
    public static BigInteger countTokensWithQty(Value mint, byte[] policyId, BigInteger expectedQty) {
        return _countTokensWithQty(mint, Builtins.bData(policyId), expectedQty);
    }

    /** Internal implementation: count tokens matching expectedQty under a policy. */
    public static BigInteger _countTokensWithQty(Value mint, PlutusData policyData, BigInteger expectedQty) {
        var outerPairs = Builtins.unMapData(mint);
        BigInteger count = BigInteger.ZERO;
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyData)) {
                count = count.add(_countInnerTokensWithQty(
                        (PlutusData.MapData) Builtins.sndPair(outerPair), expectedQty));
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return count;
    }

    /** Count tokens matching expectedQty in an inner token map (single policy). */
    public static BigInteger _countInnerTokensWithQty(PlutusData.MapData innerPairs, BigInteger expectedQty) {
        BigInteger count = BigInteger.ZERO;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var qty = Builtins.unIData(Builtins.sndPair(pair));
            if (qty.equals(expectedQty)) {
                count = count.add(BigInteger.ONE);
            }
            current = Builtins.tailList(current);
        }
        return count;
    }

    /**
     * Find the first token name with a specific quantity under a specific policy.
     * Returns empty bytestring if not found.
     */
    public static byte[] findTokenName(Value mint, byte[] policyId, BigInteger expectedQty) {
        return _findTokenName(mint, Builtins.bData(policyId), expectedQty);
    }

    /** Internal implementation: find first token name matching expectedQty under a policy. */
    public static byte[] _findTokenName(Value mint, PlutusData policyData, BigInteger expectedQty) {
        var outerPairs = Builtins.unMapData(mint);
        byte[] result = Builtins.emptyByteString();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyData)) {
                result = _findInnerTokenName(
                        (PlutusData.MapData) Builtins.sndPair(outerPair), expectedQty);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Find first token name matching expectedQty in an inner token map. Returns empty bytestring if not found. */
    public static byte[] _findInnerTokenName(PlutusData.MapData innerPairs, BigInteger expectedQty) {
        byte[] result = Builtins.emptyByteString();
        boolean found = false;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current) && !found) {
            var pair = Builtins.headList(current);
            var qty = Builtins.unIData(Builtins.sndPair(pair));
            if (qty.equals(expectedQty)) {
                result = Builtins.unBData(Builtins.fstPair(pair));
                found = true;
            }
            current = Builtins.tailList(current);
        }
        return result;
    }

    /**
     * Seed bytes uniquely identifying a TxOutRef: {@code txId ++ integerToByteString(true, 2, index)}.
     * <p>
     * The index is encoded as fixed 2-byte big-endian, so the result is deterministic and
     * identical on-chain and off-chain (a width of 0 would encode index 0 as empty bytes on-chain).
     * Fails for index &gt; 65535, which cannot occur for real transaction outputs.
     * <p>
     * Use this as input to a hash of your choice, e.g.
     * {@code CryptoLib.sha2_256(ValuesLib.refBytes(ref))}, or use
     * {@link #uniqueTokenName(TxOutRef)} for the canonical blake2b_256 derivation.
     */
    public static byte[] refBytes(TxOutRef ref) {
        byte[] idxBytes = Builtins.integerToByteString(true, 2, ref.index());
        return Builtins.appendByteString(ref.txId().hash(), idxBytes);
    }

    /**
     * Canonical token name for a spent TxOutRef: {@code blake2b_256(refBytes(ref))}.
     * <p>
     * The 32-byte output (the maximum Cardano asset-name length) is
     * collision-resistant and deterministically tied to the reference. A one-shot
     * minting policy must also enforce that the referenced output is consumed.
     */
    public static byte[] uniqueTokenName(TxOutRef ref) {
        return Builtins.blake2b_256(refBytes(ref));
    }
}
