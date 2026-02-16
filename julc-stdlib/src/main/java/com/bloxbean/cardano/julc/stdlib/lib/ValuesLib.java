package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.Builtins;

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

    /** Extracts the lovelace (ADA) amount from a Value. Assumes empty-BS policy is first entry. */
    public static BigInteger lovelaceOf(Value value) {
        var pairs = Builtins.unMapData(value);
        var firstPair = Builtins.headList(pairs);
        var tokenMapData = Builtins.sndPair(firstPair);
        var tokenPairs = Builtins.unMapData(tokenMapData);
        var firstTokenPair = Builtins.headList(tokenPairs);
        var amountData = Builtins.sndPair(firstTokenPair);
        return Builtins.unIData(amountData);
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

    /** Negates all amounts in a value. */
    public static Value negate(Value value) {
        var outerPairs = Builtins.unMapData(value);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
            var negatedInnerMap = negateTokenMap(innerMap);
            var newOuterPair = Builtins.mkPairData(policyKey, negatedInnerMap);
            result = Builtins.mkCons(newOuterPair, result);
            current = Builtins.tailList(current);
        }
        return (Value)(Object) Builtins.mapData(result);
    }

    /** Negate all amounts in a token map pair list. */
    public static PlutusData.MapData negateTokenMap(PlutusData.MapData innerPairs) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amt = Builtins.unIData(Builtins.sndPair(pair));
            var negAmt = amt.negate();
            var newPair = Builtins.mkPairData(tokenKey, Builtins.iData(negAmt));
            result = Builtins.mkCons(newPair, result);
            current = Builtins.tailList(current);
        }
        return Builtins.mapData(result);
    }

    /** Flattens a Value into a list of (policy, token, amount) triples as ConstrData(0, [p, t, amt]). */
    public static PlutusData.ListData flatten(Value value) {
        PlutusData.ListData result = Builtins.mkNilData();
        var outerPairs = Builtins.unMapData(value);
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyData = Builtins.fstPair(outerPair);
            PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
            result = flattenPolicy(policyData, innerMap, result);
            current = Builtins.tailList(current);
        }
        return ListsLib.reverse(result);
    }

    /** Flatten a single policy's token entries into the accumulator list. */
    public static PlutusData.ListData flattenPolicy(PlutusData policyData, PlutusData.MapData innerPairs, PlutusData.ListData acc) {
        PlutusData.ListData result = acc;
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenData = Builtins.fstPair(pair);
            var amountData = Builtins.sndPair(pair);
            var tripleFields = Builtins.mkCons(policyData, Builtins.mkCons(tokenData, Builtins.mkCons(amountData, Builtins.mkNilData())));
            var triple = Builtins.constrData(0, tripleFields);
            result = Builtins.mkCons(triple, result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Adds two Values together (union, adding amounts for matching policy/token). */
    public static Value add(Value a, Value b) {
        Value adjustedMap = adjustOuterForAdd(a, b);
        Value extraMap = extraOuterEntries(b, a);
        // Concat the two pair lists: prepend all of extraMap onto adjustedMap
        PlutusData result = Builtins.unMapData(adjustedMap);
        PlutusData current = Builtins.unMapData(extraMap);
        while (!Builtins.nullList(current)) {
            result = Builtins.mkCons(Builtins.headList(current), result);
            current = Builtins.tailList(current);
        }
        return (Value)(Object) Builtins.mapData(result);
    }

    /** Walk a's outer map, adjust each token amount by adding assetOf(other). */
    public static Value adjustOuterForAdd(Value a, Value other) {
        var outerPairs = Builtins.unMapData(a);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
            var adjustedInnerMap = adjustInnerForAdd(innerMap, other, policyKey);
            var newOuterPair = Builtins.mkPairData(policyKey, adjustedInnerMap);
            result = Builtins.mkCons(newOuterPair, result);
            current = Builtins.tailList(current);
        }
        return (Value)(Object) Builtins.mapData(result);
    }

    /** Adjust each token amount: new_amt = amt + assetOf(other, policy, token). */
    public static PlutusData.MapData adjustInnerForAdd(PlutusData.MapData innerPairs, Value other, PlutusData policyKey) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amtA = Builtins.unIData(Builtins.sndPair(pair));
            var amtOther = _assetOf(other, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            var newAmt = amtA.add(amtOther);
            var newPair = Builtins.mkPairData(tokenKey, Builtins.iData(newAmt));
            result = Builtins.mkCons(newPair, result);
            current = Builtins.tailList(current);
        }
        return Builtins.mapData(result);
    }

    /** Walk b's outer map, collect entries where assetOf(base) == 0 (not in base). */
    public static Value extraOuterEntries(Value b, Value base) {
        var outerPairs = Builtins.unMapData(b);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            PlutusData.MapData innerMap = (PlutusData.MapData) Builtins.sndPair(outerPair);
            var extraInnerMap = extraInnerEntries(innerMap, base, policyKey);
            if (Builtins.nullList(Builtins.unMapData(extraInnerMap))) {
                result = result;
            } else {
                var newOuterPair = Builtins.mkPairData(policyKey, extraInnerMap);
                result = Builtins.mkCons(newOuterPair, result);
            }
            current = Builtins.tailList(current);
        }
        return (Value)(Object) Builtins.mapData(result);
    }

    /** Collect inner entries where assetOf(base, policy, token) == 0. */
    public static PlutusData.MapData extraInnerEntries(PlutusData.MapData innerPairs, Value base, PlutusData policyKey) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = Builtins.unMapData(innerPairs);
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amtInBase = _assetOf(base, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            if (amtInBase.equals(BigInteger.ZERO)) {
                result = Builtins.mkCons(pair, result);
            } else {
                result = result;
            }
            current = Builtins.tailList(current);
        }
        return Builtins.mapData(result);
    }

    /** Subtracts value b from value a: add(a, negate(b)). */
    public static Value subtract(Value a, Value b) {
        return add(a, negate(b));
    }
}
