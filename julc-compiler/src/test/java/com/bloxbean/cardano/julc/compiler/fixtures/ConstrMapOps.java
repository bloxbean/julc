package com.bloxbean.cardano.julc.compiler.fixtures;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcMap;

import java.util.Optional;

/**
 * Fixture for testing JulcMap methods with ConstrData values (no auto-unwrap).
 * Map type: JulcMap<PlutusData, PlutusData> — values stay as raw Data (pass-through).
 */
public class ConstrMapOps {

    @SuppressWarnings("unchecked")
    static PlutusData get(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        return map.get(key);
    }

    @SuppressWarnings("unchecked")
    static boolean lookupPresent(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        Optional<PlutusData> result = map.lookup(key);
        return result.isPresent();
    }

    @SuppressWarnings("unchecked")
    static boolean lookupMissing(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        Optional<PlutusData> result = map.lookup(key);
        return result.isEmpty();
    }

    @SuppressWarnings("unchecked")
    static boolean containsKey(PlutusData m, PlutusData key) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        return map.containsKey(key);
    }

    @SuppressWarnings("unchecked")
    static long size(PlutusData m) {
        JulcMap<PlutusData, PlutusData> map = (JulcMap) (Object) m;
        return map.size();
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
}
