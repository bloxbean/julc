package com.bloxbean.cardano.plutus.onchain.stdlib;

import com.bloxbean.cardano.plutus.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-chain map (association list) operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 * <p>
 * Maps are represented as {@code List<Pair<Data, Data>>} association lists.
 */
public final class MapLib {

    private MapLib() {}

    /** Look up a key in the map, returning Optional. */
    public static Optional<PlutusData> lookup(PlutusData map, PlutusData key) {
        for (var pair : toEntries(map)) {
            if (pair.fields().get(0).equals(key)) {
                return Optional.of(pair.fields().get(1));
            }
        }
        return Optional.empty();
    }

    /** Check if a key exists in the map. */
    public static boolean member(PlutusData map, PlutusData key) {
        return lookup(map, key).isPresent();
    }

    /** Insert a key-value pair (prepends, shadows existing). */
    public static PlutusData insert(PlutusData map, PlutusData key, PlutusData value) {
        var entries = new ArrayList<>(toEntries(map));
        entries.addFirst(new PlutusData.Constr(0, List.of(key, value)));
        return new PlutusData.ListData(new ArrayList<>(entries));
    }

    /** Delete a key from the map. */
    public static PlutusData delete(PlutusData map, PlutusData key) {
        var result = new ArrayList<PlutusData>();
        for (var pair : toEntries(map)) {
            if (!pair.fields().get(0).equals(key)) {
                result.add(pair);
            }
        }
        return new PlutusData.ListData(result);
    }

    /** Extract all keys. */
    public static PlutusData keys(PlutusData map) {
        var result = new ArrayList<PlutusData>();
        for (var pair : toEntries(map)) {
            result.add(pair.fields().get(0));
        }
        return new PlutusData.ListData(result);
    }

    /** Extract all values. */
    public static PlutusData values(PlutusData map) {
        var result = new ArrayList<PlutusData>();
        for (var pair : toEntries(map)) {
            result.add(pair.fields().get(1));
        }
        return new PlutusData.ListData(result);
    }

    /** Convert map to its underlying pair list. */
    public static PlutusData toList(PlutusData map) {
        return map; // maps ARE pair lists
    }

    /** Construct a map from a pair list. */
    public static PlutusData fromList(PlutusData list) {
        return list; // pair lists ARE maps
    }

    /** Number of entries in the map. */
    public static BigInteger size(PlutusData map) {
        return BigInteger.valueOf(toEntries(map).size());
    }

    private static List<PlutusData.Constr> toEntries(PlutusData map) {
        if (map instanceof PlutusData.ListData ld) {
            var result = new ArrayList<PlutusData.Constr>();
            for (var item : ld.items()) {
                if (item instanceof PlutusData.Constr c) result.add(c);
            }
            return result;
        }
        throw new IllegalArgumentException("Expected ListData (map as pair list), got: " + map.getClass().getSimpleName());
    }
}
