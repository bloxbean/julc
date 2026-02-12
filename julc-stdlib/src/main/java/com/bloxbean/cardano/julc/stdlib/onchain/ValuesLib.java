package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

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
    public static long lovelaceOf(PlutusData.MapData value) {
        var pairs = Builtins.unMapData(value);
        var firstPair = Builtins.headList(pairs);
        var tokenMapData = Builtins.sndPair(firstPair);
        var tokenPairs = Builtins.unMapData(tokenMapData);
        var firstTokenPair = Builtins.headList(tokenPairs);
        var amountData = Builtins.sndPair(firstTokenPair);
        return Builtins.unIData(amountData);
    }

    /** Checks if lovelaceOf(a) >= lovelaceOf(b). */
    public static boolean geq(PlutusData.MapData a, PlutusData.MapData b) {
        var aLov = lovelaceOf(a);
        var bLov = lovelaceOf(b);
        return bLov <= aLov;
    }

    /** Extracts the amount of a specific asset. Returns 0 if not found. */
    public static long assetOf(PlutusData.MapData value, PlutusData.BytesData policyId, PlutusData.BytesData tokenName) {
        var outerPairs = Builtins.unMapData(value);
        var result = 0L;
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(outerPair), policyId)) {
                var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
                result = findTokenAmount(innerPairs, tokenName);
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Search inner token map for a token name, return amount or 0. */
    public static long findTokenAmount(PlutusData innerPairs, PlutusData.BytesData tokenName) {
        var result = 0L;
        PlutusData current = innerPairs;
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
    public static boolean geqMultiAsset(PlutusData.MapData a, PlutusData.MapData b) {
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
    public static boolean checkPolicyGeq(PlutusData.MapData a, PlutusData outerPair) {
        var policyKey = Builtins.fstPair(outerPair);
        var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
        var result = true;
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var bAmount = Builtins.unIData(Builtins.sndPair(pair));
            var aAmount = assetOf(a, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            if (bAmount <= aAmount) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Checks if value a <= value b (multi-asset). */
    public static boolean leq(PlutusData.MapData a, PlutusData.MapData b) {
        return geqMultiAsset(b, a);
    }

    /** Checks if two values are equal (multi-asset). */
    public static boolean eq(PlutusData.MapData a, PlutusData.MapData b) {
        if (geqMultiAsset(a, b)) {
            return geqMultiAsset(b, a);
        } else {
            return false;
        }
    }

    /** Checks if a value is zero (all amounts == 0). */
    public static boolean isZero(PlutusData.MapData value) {
        var result = true;
        var outerPairs = Builtins.unMapData(value);
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            if (isTokenMapZero(Builtins.unMapData(Builtins.sndPair(outerPair)))) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Check if all amounts in a token map pair list are zero. */
    public static boolean isTokenMapZero(PlutusData innerPairs) {
        var result = true;
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var amt = Builtins.unIData(Builtins.sndPair(pair));
            if (amt == 0) {
                current = Builtins.tailList(current);
            } else {
                result = false;
                current = Builtins.mkNilPairData();
            }
        }
        return result;
    }

    /** Constructs a Value containing a single asset: Map[(policy, Map[(token, amount)])]. */
    public static PlutusData.MapData singleton(PlutusData.BytesData policyId, PlutusData.BytesData tokenName, long amount) {
        var emptyPairList = Builtins.mkNilPairData();
        var innerPair = Builtins.mkPairData(tokenName, Builtins.iData(amount));
        var innerList = Builtins.mkCons(innerPair, emptyPairList);
        var innerMap = Builtins.mapData(innerList);
        var outerPair = Builtins.mkPairData(policyId, innerMap);
        var outerList = Builtins.mkCons(outerPair, emptyPairList);
        return Builtins.mapData(outerList);
    }

    /** Negates all amounts in a value. */
    public static PlutusData.MapData negate(PlutusData.MapData value) {
        var outerPairs = Builtins.unMapData(value);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
            var negatedInner = negateTokenMap(innerPairs);
            var newInnerMap = Builtins.mapData(negatedInner);
            var newOuterPair = Builtins.mkPairData(policyKey, newInnerMap);
            result = Builtins.mkCons(newOuterPair, result);
            current = Builtins.tailList(current);
        }
        return Builtins.mapData(result);
    }

    /** Negate all amounts in a token map pair list. */
    public static PlutusData negateTokenMap(PlutusData innerPairs) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amt = Builtins.unIData(Builtins.sndPair(pair));
            var negAmt = 0 - amt;
            var newPair = Builtins.mkPairData(tokenKey, Builtins.iData(negAmt));
            result = Builtins.mkCons(newPair, result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Flattens a Value into a list of (policy, token, amount) triples as ConstrData(0, [p, t, amt]). */
    public static PlutusData.ListData flatten(PlutusData.MapData value) {
        PlutusData.ListData result = Builtins.mkNilData();
        var outerPairs = Builtins.unMapData(value);
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyData = Builtins.fstPair(outerPair);
            var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
            result = flattenPolicy(policyData, innerPairs, result);
            current = Builtins.tailList(current);
        }
        return ListsLib.reverse(result);
    }

    /** Flatten a single policy's token entries into the accumulator list. */
    public static PlutusData.ListData flattenPolicy(PlutusData policyData, PlutusData innerPairs, PlutusData.ListData acc) {
        PlutusData.ListData result = acc;
        PlutusData current = innerPairs;
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
    public static PlutusData.MapData add(PlutusData.MapData a, PlutusData.MapData b) {
        var adjustedPairs = adjustOuterForAdd(a, b);
        var extraPairs = extraOuterEntries(b, a);
        var combined = concatPairLists(adjustedPairs, extraPairs);
        return Builtins.mapData(combined);
    }

    /** Walk a's outer map, adjust each token amount by adding assetOf(other). */
    public static PlutusData adjustOuterForAdd(PlutusData.MapData a, PlutusData.MapData other) {
        var outerPairs = Builtins.unMapData(a);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
            var adjustedInner = adjustInnerForAdd(innerPairs, other, policyKey);
            var newInnerMap = Builtins.mapData(adjustedInner);
            var newOuterPair = Builtins.mkPairData(policyKey, newInnerMap);
            result = Builtins.mkCons(newOuterPair, result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Adjust each token amount: new_amt = amt + assetOf(other, policy, token). */
    public static PlutusData adjustInnerForAdd(PlutusData innerPairs, PlutusData.MapData other, PlutusData policyKey) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amtA = Builtins.unIData(Builtins.sndPair(pair));
            var amtOther = assetOf(other, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            var newAmt = amtA + amtOther;
            var newPair = Builtins.mkPairData(tokenKey, Builtins.iData(newAmt));
            result = Builtins.mkCons(newPair, result);
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Walk b's outer map, collect entries where assetOf(base) == 0 (not in base). */
    public static PlutusData extraOuterEntries(PlutusData.MapData b, PlutusData.MapData base) {
        var outerPairs = Builtins.unMapData(b);
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = outerPairs;
        while (!Builtins.nullList(current)) {
            var outerPair = Builtins.headList(current);
            var policyKey = Builtins.fstPair(outerPair);
            var innerPairs = Builtins.unMapData(Builtins.sndPair(outerPair));
            var extraInner = extraInnerEntries(innerPairs, base, policyKey);
            if (Builtins.nullList(extraInner)) {
                result = result;
            } else {
                var newInnerMap = Builtins.mapData(extraInner);
                var newOuterPair = Builtins.mkPairData(policyKey, newInnerMap);
                result = Builtins.mkCons(newOuterPair, result);
            }
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Collect inner entries where assetOf(base, policy, token) == 0. */
    public static PlutusData extraInnerEntries(PlutusData innerPairs, PlutusData.MapData base, PlutusData policyKey) {
        PlutusData result = Builtins.mkNilPairData();
        PlutusData current = innerPairs;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            var tokenKey = Builtins.fstPair(pair);
            var amtInBase = assetOf(base, (PlutusData.BytesData) policyKey, (PlutusData.BytesData) tokenKey);
            if (amtInBase == 0) {
                result = Builtins.mkCons(pair, result);
            } else {
                result = result;
            }
            current = Builtins.tailList(current);
        }
        return result;
    }

    /** Subtracts value b from value a: add(a, negate(b)). */
    public static PlutusData.MapData subtract(PlutusData.MapData a, PlutusData.MapData b) {
        return add(a, negate(b));
    }

    /** Concatenate two pair lists (prepends all of listB onto listA). */
    public static PlutusData concatPairLists(PlutusData listA, PlutusData listB) {
        PlutusData result = listA;
        PlutusData current = listB;
        while (!Builtins.nullList(current)) {
            result = Builtins.mkCons(Builtins.headList(current), result);
            current = Builtins.tailList(current);
        }
        return result;
    }
}
