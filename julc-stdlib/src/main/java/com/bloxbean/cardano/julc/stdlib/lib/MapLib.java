package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.util.Optional;

/**
 * Map (association list) operations compiled from Java source to UPLC.
 * <p>
 * Uses {@link JulcMap} for type-safe, readable map manipulation.
 * On-chain, JulcMap methods are dispatched via TypeMethodRegistry to UPLC builtins.
 * <p>
 * In Plutus, maps are {@code List<Pair<Data, Data>>} (association lists).
 * These are NOT hash maps — lookups are O(n).
 */
@OnchainLibrary
public class MapLib {

    /** Look up a key. Returns Optional.of(value) if found, Optional.empty() if not. */
    @SuppressWarnings("unchecked")
    public static Optional<PlutusData> lookup(JulcMap<PlutusData, PlutusData> map, PlutusData key) {
        Optional<PlutusData> result = Optional.empty();
        PlutusData current = (PlutusData)(Object) map;
        while (!Builtins.nullList(current)) {
            var pair = Builtins.headList(current);
            if (Builtins.equalsData(Builtins.fstPair(pair), key)) {
                result = Optional.of(Builtins.sndPair(pair));
                current = Builtins.mkNilPairData();
            } else {
                current = Builtins.tailList(current);
            }
        }
        return result;
    }

    /** Check if key exists in map. */
    public static boolean member(JulcMap<PlutusData, PlutusData> map, PlutusData key) {
        return map.containsKey(key);
    }

    /** Insert key-value pair (prepends; shadows existing). */
    public static JulcMap<PlutusData, PlutusData> insert(JulcMap<PlutusData, PlutusData> map, PlutusData key, PlutusData value) {
        return map.insert(key, value);
    }

    /** Delete a key from the map. */
    public static JulcMap<PlutusData, PlutusData> delete(JulcMap<PlutusData, PlutusData> map, PlutusData key) {
        return map.delete(key);
    }

    /** Extract all keys from a map as a list. */
    public static JulcList<PlutusData> keys(JulcMap<PlutusData, PlutusData> map) {
        return map.keys();
    }

    /** Extract all values from a map as a list. */
    public static JulcList<PlutusData> values(JulcMap<PlutusData, PlutusData> map) {
        return map.values();
    }

    /** Convert map to pair list (identity — MapType vars already hold pair lists). */
    public static JulcMap<PlutusData, PlutusData> toList(JulcMap<PlutusData, PlutusData> map) {
        return map;
    }

    /** Construct map from pair list (MapData). */
    @SuppressWarnings("unchecked")
    public static JulcMap<PlutusData, PlutusData> fromList(PlutusData list) {
        return (JulcMap<PlutusData, PlutusData>)(Object) Builtins.mapData(list);
    }

    /** Count entries in the map. */
    public static long size(JulcMap<PlutusData, PlutusData> map) {
        return map.size();
    }
}
