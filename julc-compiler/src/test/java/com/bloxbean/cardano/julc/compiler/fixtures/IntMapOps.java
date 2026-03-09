package com.bloxbean.cardano.julc.compiler.fixtures;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Fixture for testing JulcMap methods with integer values.
 * Map type: JulcMap<PlutusData, BigInteger> — value auto-unwrap via UnIData.
 */
public class IntMapOps {

    @SuppressWarnings("unchecked")
    static BigInteger get(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        return map.get(key);
    }

    @SuppressWarnings("unchecked")
    static boolean lookupPresent(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        Optional<BigInteger> result = map.lookup(key);
        return result.isPresent();
    }

    @SuppressWarnings("unchecked")
    static boolean lookupMissing(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        Optional<BigInteger> result = map.lookup(key);
        return result.isEmpty();
    }

    @SuppressWarnings("unchecked")
    static boolean containsKey(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        return map.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    static long size(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        return map.size();
    }

    @SuppressWarnings("unchecked")
    static boolean isEmpty(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        return map.isEmpty();
    }

    @SuppressWarnings("unchecked")
    static long keys(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        var k = map.keys();
        return k.size();
    }

    @SuppressWarnings("unchecked")
    static long values(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        var v = map.values();
        return v.size();
    }

    @SuppressWarnings("unchecked")
    static long insertThenSize(PlutusData m, PlutusData key, PlutusData val) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        JulcMap<PlutusData, PlutusData> updated = map.insert(key, val);
        return updated.size();
    }

    @SuppressWarnings("unchecked")
    static long deleteThenSize(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        JulcMap<PlutusData, PlutusData> updated = map.delete(key);
        return updated.size();
    }

    @SuppressWarnings("unchecked")
    static BigInteger getWithIntKey(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        long key = 1;
        return map.get((PlutusData) (Object) Builtins.iData(key));
    }

    @SuppressWarnings("unchecked")
    static boolean containsKeyWithIntKey(PlutusData m) {
        JulcMap<PlutusData, BigInteger> map = (JulcMap) (Object) m;
        long key = 1;
        return map.containsKey((PlutusData) (Object) Builtins.iData(key));
    }
}
